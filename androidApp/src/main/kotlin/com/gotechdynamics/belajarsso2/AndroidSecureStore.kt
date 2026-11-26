package com.gotechdynamics.belajarsso2

import android.content.Context
import com.gotechdynamics.belajarsso2.core.storage.SecureStore
import eu.anifantakis.lib.ksafe.KSafe
import eu.anifantakis.lib.ksafe.KSafeWriteMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class AndroidSecureStore(context: Context) : SecureStore {
    private val safe = KSafe(context.applicationContext)

    override suspend fun put(key: String, value: String) = serialized {
        requireOAuthKey(key)
        safe.put(key, value, mode = KSafeWriteMode.Encrypted())
    }

    override suspend fun get(key: String): String? = serialized {
        requireOAuthKey(key)
        safe.get<String?>(key, null)
    }

    override suspend fun remove(key: String) = serialized {
        requireOAuthKey(key)
        safe.delete(key)
    }

    override suspend fun clear() = serialized { OAUTH_KEYS.forEach { safe.delete(it) } }

    private fun requireOAuthKey(key: String) = require(key in OAUTH_KEYS)
    private suspend fun <T> serialized(block: suspend () -> T): T = mutex.withLock {
        withContext(NonCancellable + Dispatchers.IO) { block() }
    }

    private companion object {
        val mutex = Mutex()
        val OAUTH_KEYS = setOf("oauth_token_record", "oauth_transaction")
    }
}
