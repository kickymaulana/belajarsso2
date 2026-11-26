package com.gotechdynamics.belajarsso2.feature.auth.data

import com.gotechdynamics.belajarsso2.core.network.OAuthApi
import com.gotechdynamics.belajarsso2.core.network.OAuthCodec
import com.gotechdynamics.belajarsso2.core.network.OAuthConfig
import com.gotechdynamics.belajarsso2.core.network.OAuthFailure
import com.gotechdynamics.belajarsso2.core.network.authorizationUrl
import com.gotechdynamics.belajarsso2.core.storage.SecureStore
import com.gotechdynamics.belajarsso2.feature.auth.data.model.OAuthCallback
import com.gotechdynamics.belajarsso2.feature.auth.data.model.OAuthUser
import com.gotechdynamics.belajarsso2.feature.auth.data.model.PendingTransaction
import com.gotechdynamics.belajarsso2.feature.auth.data.model.TokenRecord
import com.gotechdynamics.belajarsso2.feature.auth.data.model.TokenResponse
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface PkceGenerator {
    fun newVerifier(): String
    fun newState(): String
    fun challenge(verifier: String): String
}

sealed interface CallbackResult {
    data class Code(val code: String, val verifier: String) : CallbackResult
    data class Rejected(val message: String) : CallbackResult
}

fun validateCallback(transaction: PendingTransaction?, state: String?, code: String?, error: String?, now: Long): CallbackResult {
    if (transaction == null) return CallbackResult.Rejected("Callback OAuth sudah diproses atau sesi masuk tidak ditemukan.")
    if (now < transaction.createdAt || now - transaction.createdAt >= 600_000L) return CallbackResult.Rejected("Sesi masuk kedaluwarsa. Silakan coba lagi.")
    val expected = transaction.state
    val received = state.orEmpty()
    var difference = expected.length xor received.length
    for (index in expected.indices) difference = difference or (expected[index].code xor (received.getOrNull(index)?.code ?: 0))
    if (expected.isBlank() || transaction.verifier.isBlank() || difference != 0) return CallbackResult.Rejected("State OAuth tidak valid.")
    if (error != null) return CallbackResult.Rejected(if (error == "access_denied") "Masuk dibatalkan." else "Otorisasi gagal. Silakan coba lagi.")
    if (code.isNullOrBlank()) return CallbackResult.Rejected("Kode otorisasi tidak ditemukan.")
    return CallbackResult.Code(code, transaction.verifier)
}

data class LoginRequest(val url: String)

class AuthRepository(
    private val store: SecureStore,
    private val api: OAuthApi,
    private val config: OAuthConfig,
    private val pkce: PkceGenerator,
    private val now: () -> Long,
) {
    private val lock = Mutex()

    suspend fun initialize(): OAuthUser? = lock.withLock {
        val pending = pending()
        if (pending != null && now() - pending.createdAt in 0 until 600_000L) throw LoginPending
        store.remove(TRANSACTION)
        loadProfileLocked()
    }

    suspend fun startLogin(): LoginRequest = lock.withLock {
        check(config.isUsable())
        val verifier = pkce.newVerifier()
        val transaction = PendingTransaction(pkce.newState(), verifier, now())
        store.put(TRANSACTION, OAuthCodec.encodeTransaction(transaction))
        LoginRequest(authorizationUrl(config, transaction.state, pkce.challenge(verifier)))
    }

    suspend fun handleCallback(callback: OAuthCallback): OAuthUser = lock.withLock {
        val transaction = pending()
        store.remove(TRANSACTION)
        val result = if (!callback.isValidUri) CallbackResult.Rejected("URI callback OAuth tidak valid.") else validateCallback(transaction, callback.state, callback.code, callback.error, now())
        when (result) {
            is CallbackResult.Rejected -> throw AuthRejected(result.message)
            is CallbackResult.Code -> {
                save(api.exchange(result.code, result.verifier))
                loadProfileLocked() ?: throw OAuthFailure(401)
            }
        }
    }

    suspend fun cancelLogin() = lock.withLock { store.remove(TRANSACTION) }
    suspend fun logout() = lock.withLock { store.clear() }
    fun close() = api.close()

    private suspend fun loadProfileLocked(): OAuthUser? {
        var record = tokens() ?: return null
        var refreshed = false
        if (record.expiresAt?.let { it <= now() } == true) { record = refresh(record); refreshed = true }
        return try { api.profile(record.accessToken) } catch (failure: OAuthFailure) {
            if (failure.status != 401 || refreshed) throw failure
            record = refresh(record)
            api.profile(record.accessToken)
        }
    }

    private suspend fun refresh(record: TokenRecord): TokenRecord {
        val refreshToken = record.refreshToken ?: throw OAuthFailure(401)
        return save(api.refresh(refreshToken), refreshToken)
    }

    private suspend fun save(response: TokenResponse, previousRefresh: String? = null): TokenRecord {
        currentCoroutineContext().ensureActive()
        val record = TokenRecord(response.accessToken, response.refreshToken ?: previousRefresh, response.expiresIn?.coerceIn(0, 315_360_000L)?.let { now() + it * 1000 })
        currentCoroutineContext().ensureActive()
        store.put(TOKENS, OAuthCodec.encodeTokens(record))
        return record
    }

    private suspend fun tokens(): TokenRecord? = store.get(TOKENS)?.let(OAuthCodec::decodeTokens)
    private suspend fun pending(): PendingTransaction? = store.get(TRANSACTION)?.let(OAuthCodec::decodeTransaction)

    object LoginPending : Exception()
    class AuthRejected(override val message: String) : Exception(message)

    companion object {
        const val TOKENS = "oauth_token_record"
        const val TRANSACTION = "oauth_transaction"
    }
}
