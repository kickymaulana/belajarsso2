package com.gotechdynamics.belajarsso2.core.network

/** Konfigurasi publik OAuth; aplikasi klien tidak boleh menyimpan client secret. */
data class OAuthConfig(
    val issuer: String,
    val clientId: String,
    val redirectUri: String,
    val allowEmulatorHttp: Boolean = false,
) {
    fun isUsable(): Boolean = clientId.isNotBlank() &&
        !clientId.contains('<') &&
        !clientId.contains("ISI_CLIENT", ignoreCase = true) &&
        (issuer.startsWith("https://") ||
            (allowEmulatorHttp && issuer == "http://10.0.2.2:8000"))
}
