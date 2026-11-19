package net.pekmen.one.core.cloud.auth

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.URI
import java.util.Base64

class SupabaseAuthClientTest {
    @Test
    fun passwordUsesSupabaseGrantAndKeepsSecretsOutOfTheUrl() = runTest {
        val transport = RecordingTransport()
        val session = client(transport).signIn("focus@example.test", "test password")
        val sent = transport.calls.single()
        assertEquals("POST", sent.method)
        assertEquals("/auth/v1/token", sent.url.path)
        assertEquals("grant_type=password", sent.url.query)
        assertEquals("test password", sent.body?.get("password")?.jsonPrimitive?.content)
        assertEquals("b5b71280-3bdc-4b4a-8446-22f76976c71b", session.userId)
        assertEquals(NOW + 3_600_000L, session.expiresAtWallMs)
        assertFalse(session.toString().contains("test-access"))
    }

    @Test
    fun googleAndEmailUsePkceAndOnlyAcceptTheConfiguredCallback() = runTest {
        val transport = RecordingTransport()
        val client = client(transport)
        val link = client.createLink(AuthLinkPurpose.GOOGLE)
        val url = client.googleAuthorization(link)
        assertEquals("/auth/v1/authorize", url.path)
        assertFalse(url.toASCIIString().contains(link.verifier))
        val wrongLink = runCatching {
            client.exchange(link, URI("https://other.example.test/auth/pekmen-one?code=opaque_code_123"))
        }.exceptionOrNull() as IdentityFailure
        assertEquals(IdentityError.LINK, wrongLink.reason)
        assertEquals(0, transport.calls.size)

        client.exchange(link, URI("https://links.example.test/auth/pekmen-one?code=opaque_code_123"))
        assertEquals(link.verifier, transport.calls.single().body?.get("code_verifier")?.jsonPrimitive?.content)
        assertEquals("grant_type=pkce", transport.calls.single().url.query)

        val signup = client.createLink(AuthLinkPurpose.SIGNUP)
        client.signUp("focus@example.test", "test password", signup)
        val signupCall = transport.calls.last()
        assertEquals("/auth/v1/signup", signupCall.url.path)
        assertNotNull(signupCall.body?.get("code_challenge"))
        assertNull(signupCall.body?.get("code_verifier"))
    }

    @Test
    fun errorsDoNotCarryServerBodiesAndSecretKeysAreRejected() = runTest {
        val response = AuthHttpResponse(400, """{"error_code":"email_not_confirmed","message":"private"}""")
        val transport = RecordingTransport(response)
        val error = runCatching { client(transport).signIn("private@example.test", "private") }
            .exceptionOrNull() as IdentityFailure
        assertEquals(IdentityError.EMAIL_UNVERIFIED, error.reason)
        assertFalse(error.message.orEmpty().contains("private"))
        val claims = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("""{"role":"service_role"}""".encodeToByteArray())
        val elevatedKey = "header.$claims.signature"
        assertNull(SupabaseAuthConfig.parse("https://db.example.test", elevatedKey, "links.example.test"))
        assertNull(SupabaseAuthConfig.parse("http://db.example.test", "sb_publishable_test", ""))
    }

    private fun client(transport: RecordingTransport) = SupabaseAuthClient(
        requireNotNull(
            SupabaseAuthConfig.parse("https://db.example.test", "sb_publishable_test", "links.example.test")
        ),
        transport,
        { NOW }
    )

    private data class Call(val method: String, val url: URI, val body: JsonObject?)

    private class RecordingTransport(
        private val response: AuthHttpResponse = AuthHttpResponse(200, SESSION)
    ) : AuthHttpTransport {
        val calls = mutableListOf<Call>()
        override suspend fun request(
            method: String,
            url: URI,
            key: String,
            bearer: String?,
            body: JsonObject?
        ): AuthHttpResponse {
            calls += Call(method, url, body)
            return response
        }
    }

    private companion object {
        const val NOW = 2_000_000_000L
        const val SESSION = """{
          "access_token":"test-access","refresh_token":"test-refresh","expires_in":3600,
          "user":{"id":"b5b71280-3bdc-4b4a-8446-22f76976c71b","email":"focus@example.test","email_confirmed_at":"yes"}
        }"""
    }
}
