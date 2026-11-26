package com.gotechdynamics.belajarsso2

import android.util.Base64
import com.gotechdynamics.belajarsso2.feature.auth.data.PkceGenerator
import java.security.MessageDigest
import java.security.SecureRandom

class AndroidPkceGenerator : PkceGenerator {
    private val random = SecureRandom()
    override fun newVerifier(): String = randomUrlSafe(64)
    override fun newState(): String = randomUrlSafe(32)
    override fun challenge(verifier: String): String = encode(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    private fun randomUrlSafe(size: Int): String = encode(ByteArray(size).also(random::nextBytes))
    private fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}

object CallbackValidator {
    const val SCHEME = "com.gotechdynamics.belajarsso2"
    const val HOST = "oauth"
    const val PATH = "/callback"
}
