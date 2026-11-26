package com.gotechdynamics.belajarsso2.core.network

import com.gotechdynamics.belajarsso2.feature.auth.data.model.OAuthUser
import com.gotechdynamics.belajarsso2.feature.auth.data.model.TokenResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

interface OAuthApi {
    suspend fun exchange(code: String, verifier: String): TokenResponse
    suspend fun refresh(refreshToken: String): TokenResponse
    suspend fun profile(accessToken: String): OAuthUser
    fun close()
}

class OAuthFailure(val status: Int, val invalidGrant: Boolean = false) : Exception("OAuth request failed")

class KtorOAuthApi(private val config: OAuthConfig) : OAuthApi {
    private val client = HttpClient {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
        }
    }

    private suspend fun token(fields: Map<String, String>): TokenResponse {
        check(config.isUsable())
        val response = client.post("${config.issuer}/oauth/token") {
            accept(ContentType.Application.Json)
            setBody(FormDataContent(Parameters.build { fields.forEach { (key, value) -> append(key, value) } }))
        }
        if (response.status.value !in 200..299) {
            val payload = response.body<String>()
            val invalid = runCatching {
                Json.parseToJsonElement(payload).jsonObject["error"]?.jsonPrimitive?.content == "invalid_grant"
            }.getOrDefault(false)
            throw OAuthFailure(response.status.value, invalid)
        }
        return response.body<TokenResponse>().also {
            require(it.accessToken.isNotBlank() && it.tokenType.equals("Bearer", ignoreCase = true))
        }
    }

    override suspend fun exchange(code: String, verifier: String) = token(exchangeFields(config, code, verifier))
    override suspend fun refresh(refreshToken: String) = token(
        mapOf("grant_type" to "refresh_token", "client_id" to config.clientId, "refresh_token" to refreshToken),
    )

    override suspend fun profile(accessToken: String): OAuthUser {
        check(config.isUsable())
        val response = client.get("${config.issuer}/api/user") {
            accept(ContentType.Application.Json)
            bearerAuth(accessToken)
        }
        if (response.status.value !in 200..299) throw OAuthFailure(response.status.value)
        return OAuthCodec.decodeUser(response.body())
    }

    override fun close() = client.close()
}
