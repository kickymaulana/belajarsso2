package com.gotechdynamics.belajarsso2.feature.auth.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    @SerialName("token_type") val tokenType: String = "Bearer",
)

@Serializable
data class OAuthUser(
    val id: String? = null,
    val nik: String? = null,
    val name: String? = null,
    val email: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
)

@Serializable
data class TokenRecord(val accessToken: String, val refreshToken: String?, val expiresAt: Long?)

@Serializable
data class PendingTransaction(val state: String, val verifier: String, val createdAt: Long)

data class OAuthCallback(
    val state: String?,
    val code: String?,
    val error: String?,
    val isValidUri: Boolean,
)
