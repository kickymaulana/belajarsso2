package com.gotechdynamics.belajarsso2.core.network

import com.gotechdynamics.belajarsso2.feature.auth.data.model.OAuthUser
import com.gotechdynamics.belajarsso2.feature.auth.data.model.PendingTransaction
import com.gotechdynamics.belajarsso2.feature.auth.data.model.TokenRecord
import io.ktor.http.URLBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** Konfigurasi publik tanpa client secret. HTTP hanya boleh untuk emulator debug. */
data class OAuthConfig(
    val issuer: String,
    val clientId: String,
    val redirectUri: String,
    val allowEmulatorHttp: Boolean = false,
) {
    fun isUsable(): Boolean = clientId.isNotBlank() && !clientId.contains('<') &&
        !clientId.contains("ISI_CLIENT", ignoreCase = true) &&
        (issuer.startsWith("https://") || (allowEmulatorHttp && issuer == "http://10.0.2.2:8000"))
}

object OAuthCodec {
    private val json = Json { ignoreUnknownKeys = true }
    fun encodeTokens(value: TokenRecord): String = json.encodeToString(value)
    fun decodeTokens(value: String): TokenRecord = json.decodeFromString(value)
    fun encodeTransaction(value: PendingTransaction): String = json.encodeToString(value)
    fun decodeTransaction(value: String): PendingTransaction = json.decodeFromString(value)
    fun decodeUser(value: String): OAuthUser {
        val root = json.parseToJsonElement(value).jsonObject
        val user = root["data"] as? JsonObject ?: root
        fun field(key: String): String? = (user[key] as? JsonPrimitive)?.contentOrNull
        return OAuthUser(field("id"), field("nik"), field("name"), field("email"), field("avatar_url"))
    }
}

internal fun exchangeFields(config: OAuthConfig, code: String, verifier: String): Map<String, String> = linkedMapOf(
    "grant_type" to "authorization_code",
    "client_id" to config.clientId,
    "redirect_uri" to config.redirectUri,
    "code" to code,
    "code_verifier" to verifier,
)

fun authorizationUrl(config: OAuthConfig, state: String, challenge: String): String {
    require(config.isUsable())
    return URLBuilder("${config.issuer}/oauth/authorize").apply {
        parameters.append("response_type", "code")
        parameters.append("client_id", config.clientId)
        parameters.append("redirect_uri", config.redirectUri)
        parameters.append("state", state)
        parameters.append("code_challenge", challenge)
        parameters.append("code_challenge_method", "S256")
    }.buildString()
}
