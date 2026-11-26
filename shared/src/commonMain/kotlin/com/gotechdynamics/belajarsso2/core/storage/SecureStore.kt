package com.gotechdynamics.belajarsso2.core.storage

interface SecureStore {
    suspend fun put(key: String, value: String)
    suspend fun get(key: String): String?
    suspend fun remove(key: String)
    suspend fun clear()
}
