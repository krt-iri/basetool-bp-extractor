package com.basetool.bpextractor.net.auth

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the device-grant flow against a local stand-in for Keycloak (JDK [HttpServer]) — no
 * real credentials, no real network (CLAUDE.md test rule). Covers the device-code request, the
 * authorization_pending → success poll, and the denial path.
 */
class DeviceGrantClientTest {

    private lateinit var server: HttpServer
    private lateinit var issuer: String
    private val tokenCalls = AtomicInteger(0)

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/protocol/openid-connect/auth/device") { ex ->
            respond(
                ex,
                200,
                """{"device_code":"DEV-1","user_code":"WXYZ-1234",
                   "verification_uri":"https://kc/device",
                   "verification_uri_complete":"https://kc/device?user_code=WXYZ-1234",
                   "expires_in":600,"interval":1}""",
            )
        }
        server.start()
        issuer = "http://localhost:${server.address.port}"
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
    }

    private fun client() = DeviceGrantClient(issuer = issuer)

    private fun respond(ex: HttpExchange, code: Int, body: String) {
        val bytes = body.trimIndent().toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    @Test
    fun requestsDeviceCode() {
        val device = client().requestDeviceCode()
        assertEquals("DEV-1", device.deviceCode)
        assertEquals("WXYZ-1234", device.userCode)
        assertEquals("https://kc/device?user_code=WXYZ-1234", device.browserUrl())
    }

    @Test
    fun pollsThroughPendingThenSucceeds() {
        server.createContext("/protocol/openid-connect/token") { ex ->
            if (tokenCalls.getAndIncrement() == 0) {
                respond(ex, 400, """{"error":"authorization_pending"}""")
            } else {
                respond(ex, 200, """{"access_token":"AT","refresh_token":"RT","token_type":"Bearer","expires_in":300}""")
            }
        }
        val device = client().requestDeviceCode()
        val token = client().pollForToken(device, sleep = {}, nowMillis = { 1_000L })
        assertEquals("AT", token.accessToken)
        assertEquals("RT", token.refreshToken)
        assertTrue(tokenCalls.get() >= 2)
    }

    @Test
    fun deniedPollFails() {
        server.createContext("/protocol/openid-connect/token") { ex ->
            respond(ex, 400, """{"error":"access_denied"}""")
        }
        val device = client().requestDeviceCode()
        assertFailsWith<DeviceGrantException> {
            client().pollForToken(device, sleep = {}, nowMillis = { 1_000L })
        }
    }

    @Test
    fun rejectsNonHttpsIssuer() {
        assertFailsWith<IllegalArgumentException> { DeviceGrantClient(issuer = "http://evil.example") }
    }

    @Test
    fun refreshExchangesStoredTokenForRotatedOne() {
        server.createContext("/protocol/openid-connect/token") { ex ->
            respond(ex, 200, """{"access_token":"AT2","refresh_token":"RT2","token_type":"Bearer","expires_in":300}""")
        }
        val token = client().refreshAccessToken("OLD-RT")
        assertEquals("AT2", token.accessToken)
        assertEquals("RT2", token.refreshToken)
    }

    @Test
    fun refreshRejectionThrows() {
        server.createContext("/protocol/openid-connect/token") { ex ->
            respond(ex, 400, """{"error":"invalid_grant"}""")
        }
        assertFailsWith<DeviceGrantException> { client().refreshAccessToken("DEAD-RT") }
    }

    @Test
    fun revokePostsTokenToRevocationEndpoint() {
        val captured = AtomicReference<String>("")
        server.createContext("/protocol/openid-connect/revoke") { ex ->
            captured.set(ex.requestBody.readBytes().decodeToString())
            ex.sendResponseHeaders(200, -1)
            ex.close()
        }
        client().revoke("RT-TO-REVOKE")
        assertTrue(captured.get().contains("token=RT-TO-REVOKE"))
        assertTrue(captured.get().contains("token_type_hint=refresh_token"))
    }

    // --- DPoP (RFC 9449, REQ-INGEST-012) -------------------------------------------------------

    /** Records the `DPoP` header of every request that reaches a context, in order. */
    private val proofs = mutableListOf<String?>()

    private fun tokenEndpointRecording(handle: (HttpExchange, Int) -> Unit) {
        server.createContext("/protocol/openid-connect/token") { ex ->
            val attempt = synchronized(proofs) { proofs.add(ex.requestHeaders.getFirst("DPoP")); proofs.size }
            handle(ex, attempt)
        }
    }

    @Test
    fun `a refresh carries a proof bound to the token endpoint and no ath`() {
        tokenEndpointRecording { ex, _ ->
            respond(ex, 200, """{"access_token":"AT2","refresh_token":"RT2","token_type":"DPoP","expires_in":300}""")
        }

        val token = client().refreshAccessToken("OLD-RT", DpopKey.generate())

        assertTrue(token.isDpopBound(), "token_type DPoP is what says the server really bound it")
        val proof = assertNotNull(proofs.single(), "the refresh must carry a proof")
        assertEquals("POST", DpopProofs.claim(proof, "htm"))
        assertEquals("$issuer/protocol/openid-connect/token", DpopProofs.claim(proof, "htu"))
        // Nothing is presented yet at the token endpoint, so there is no token to hash.
        assertNull(DpopProofs.claims(proof)["ath"])
        assertNull(DpopProofs.claims(proof)["nonce"])
    }

    @Test
    fun `no key means no DPoP header at all — the pre-DPoP behaviour is untouched`() {
        tokenEndpointRecording { ex, _ ->
            respond(ex, 200, """{"access_token":"AT2","refresh_token":"RT2","token_type":"Bearer","expires_in":300}""")
        }

        val token = client().refreshAccessToken("OLD-RT")

        assertNull(proofs.single(), "without a key nothing DPoP-shaped may go out")
        assertFalse(token.isDpopBound())
    }

    @Test
    fun `a nonce challenge is retried exactly once, with the nonce echoed back`() {
        // RFC 9449 §8. Without this the whole flow breaks the moment Keycloak turns nonces on.
        tokenEndpointRecording { ex, attempt ->
            if (attempt == 1) {
                ex.responseHeaders.add(DpopNonce.HEADER, "N-1")
                respond(ex, 400, """{"error":"use_dpop_nonce","error_description":"nonce required"}""")
            } else {
                respond(ex, 200, """{"access_token":"AT2","refresh_token":"RT2","token_type":"DPoP","expires_in":300}""")
            }
        }

        val token = client().refreshAccessToken("OLD-RT", DpopKey.generate())

        assertEquals("AT2", token.accessToken)
        assertEquals(2, proofs.size, "exactly one retry")
        assertNull(DpopProofs.claim(proofs[0]!!, "nonce"), "the first attempt cannot know the nonce")
        assertEquals("N-1", DpopProofs.claim(proofs[1]!!, "nonce"), "the retry must echo it")
        assertNotEquals(
            DpopProofs.claim(proofs[0]!!, "jti"),
            DpopProofs.claim(proofs[1]!!, "jti"),
            "the retry is a new proof, not a re-sent one",
        )
    }

    @Test
    fun `an ordinary rejection is never retried, even when a nonce rides along`() {
        // A dead refresh token must fail fast into a fresh login; re-posting a grant in a loop is
        // exactly what the single-retry rule exists to prevent.
        tokenEndpointRecording { ex, _ ->
            ex.responseHeaders.add(DpopNonce.HEADER, "N-1")
            respond(ex, 400, """{"error":"invalid_grant"}""")
        }

        assertFailsWith<DeviceGrantException> {
            client().refreshAccessToken("DEAD-RT", DpopKey.generate())
        }

        assertEquals(1, proofs.size, "only a use_dpop_nonce answer earns a second request")
    }

    @Test
    fun `the poll and the revoke carry proofs too`() {
        tokenEndpointRecording { ex, _ ->
            respond(ex, 200, """{"access_token":"AT","refresh_token":"RT","token_type":"DPoP","expires_in":300}""")
        }
        val revokeProof = AtomicReference<String?>(null)
        server.createContext("/protocol/openid-connect/revoke") { ex ->
            revokeProof.set(ex.requestHeaders.getFirst("DPoP"))
            ex.sendResponseHeaders(200, -1)
            ex.close()
        }
        val key = DpopKey.generate()
        val client = client()

        client.pollForToken(client.requestDeviceCode(), sleep = {}, nowMillis = { 1_000L }, dpopKey = key)
        client.revoke("RT-TO-REVOKE", key)

        assertEquals(
            "$issuer/protocol/openid-connect/token",
            DpopProofs.claim(assertNotNull(proofs.single()), "htu"),
        )
        assertEquals(
            "$issuer/protocol/openid-connect/revoke",
            DpopProofs.claim(assertNotNull(revokeProof.get()), "htu"),
            "a bound refresh token is only revocable with a proof for that endpoint",
        )
    }
}
