package com.gotechdynamics.belajarsso2

import com.gotechdynamics.belajarsso2.core.network.OAuthCodec
import com.gotechdynamics.belajarsso2.feature.auth.data.CallbackResult
import com.gotechdynamics.belajarsso2.feature.auth.data.validateCallback
import com.gotechdynamics.belajarsso2.feature.auth.data.model.PendingTransaction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AuthRepositoryTest {
    @Test
    fun validCallbackReturnsCodeAndVerifier() {
        val result = validateCallback(PendingTransaction("state", "verifier", 1_000), "state", "code", null, 2_000)
        assertEquals(CallbackResult.Code("code", "verifier"), result)
    }

    @Test
    fun callbackCannotBeReplayed() {
        assertIs<CallbackResult.Rejected>(validateCallback(null, "state", "code", null, 2_000))
    }

    @Test
    fun numericUserIdIsPreservedAsString() {
        val user = OAuthCodec.decodeUser("{\"id\":123,\"name\":\"User\"}")
        assertEquals("123", user.id)
        assertEquals("User", user.name)
    }
}
