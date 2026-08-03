package com.basetool.bpextractor.net.auth

import com.basetool.bpextractor.net.RawHttpServer
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
    fun `a nonce challenge fails loudly and by name instead of being answered`() {
        // RFC 9449 §8 is deliberately NOT implemented: neither Keycloak nor the gateway ever issues
        // a challenge, and carrying an untested handshake for it would only guarantee that the first
        // real one is answered by code nobody has watched run. It is named so it needs a release.
        tokenEndpointRecording { ex, _ ->
            ex.responseHeaders.add(DpopNonce.HEADER, "N-1")
            respond(ex, 400, """{"error":"use_dpop_nonce","error_description":"nonce required"}""")
        }

        val failure =
            assertFailsWith<DeviceGrantException> {
                client().refreshAccessToken("OLD-RT", DpopKey.generate())
            }

        assertEquals(DpopNonce.USE_DPOP_NONCE, failure.oauthError, "the UI keys its wording off this")
        assertEquals(1, proofs.size, "the challenge must not be answered")
        assertNull(DpopProofs.claims(proofs.single()!!)["nonce"], "no proof ever carries a nonce")
    }

    @Test
    fun `an ordinary rejection is never retried, even when a nonce rides along`() {
        // A dead refresh token must fail fast into a fresh login; re-posting a grant in a loop is
        // exactly what the at-most-one-retry rule exists to prevent.
        tokenEndpointRecording { ex, _ ->
            ex.responseHeaders.add(DpopNonce.HEADER, "N-1")
            respond(ex, 400, """{"error":"invalid_grant"}""")
        }

        val failure =
            assertFailsWith<DeviceGrantException> {
                client().refreshAccessToken("DEAD-RT", DpopKey.generate())
            }

        assertEquals("invalid_grant", failure.oauthError)
        assertEquals(1, proofs.size, "nothing about this answer earns a second request")
    }

    // --- clock correction ------------------------------------------------------------------------

    @Test
    fun `an iat written from a drifting clock is corrected from the server's Date and retried once`() {
        // Keycloak accepts iat only in -25s..+15s and checks the proof BEFORE the grant, so a clock
        // a few tens of seconds off breaks authentication outright — where the plain-bearer builds,
        // which send no timestamp at all, were immune. The server's own Date header is the fix.
        val skew = 600L
        RawHttpServer { attempt, _ ->
            if (attempt == 1) {
                RawHttpServer.response(
                    "400 Bad Request",
                    """{"error":"invalid_request","error_description":"DPoP proof is not active"}""",
                    skew,
                )
            } else {
                RawHttpServer.response(
                    "200 OK",
                    """{"access_token":"AT2","refresh_token":"RT2","token_type":"DPoP","expires_in":300}""",
                    skew,
                )
            }
        }
            .use { raw ->
                val token =
                    DeviceGrantClient(issuer = raw.baseUrl).refreshAccessToken("OLD-RT", DpopKey.generate())

                assertEquals("AT2", token.accessToken, "the corrected retry must succeed")
                assertEquals(2, raw.received.size, "exactly one retry")
                val sent = raw.received.map { assertNotNull(it.header("DPoP")) }
                val shift =
                    DpopProofs.claim(sent[1], "iat")!!.toLong() - DpopProofs.claim(sent[0], "iat")!!.toLong()
                // The first proof used the raw local clock; the second is pulled onto the server's.
                assertTrue(
                    shift in (skew - 5)..(skew + 5),
                    "the retry's iat must be shifted onto the server's clock, was ${shift}s",
                )
                assertNotEquals(
                    DpopProofs.claim(sent[0], "jti"),
                    DpopProofs.claim(sent[1], "jti"),
                    "the retry is a new proof — a reused jti is what a replay looks like",
                )
            }
    }

    @Test
    fun `a rejection with the clock already in step is not retried`() {
        // Nothing was learned, so a second identical proof would only repeat the first rejection.
        RawHttpServer { _, _ ->
            RawHttpServer.response(
                "400 Bad Request",
                """{"error":"invalid_request","error_description":"bad proof"}""",
            )
        }
            .use { raw ->
                assertFailsWith<DeviceGrantException> {
                    DeviceGrantClient(issuer = raw.baseUrl).refreshAccessToken("OLD-RT", DpopKey.generate())
                }

                assertEquals(1, raw.received.size, "no correction was available, so no retry")
            }
    }

    @Test
    fun `a materially wrong clock is reported so the user can be told what to fix`() {
        val skew = 900L
        RawHttpServer { _, _ ->
            RawHttpServer.response(
                "400 Bad Request",
                """{"error":"invalid_request","error_description":"DPoP proof is not active"}""",
                skew,
            )
        }
            .use { raw ->
                val failure =
                    assertFailsWith<DeviceGrantException> {
                        DeviceGrantClient(issuer = raw.baseUrl).refreshAccessToken("OLD-RT", DpopKey.generate())
                    }

                // Reported only after the corrected retry ALSO failed — never blame a clock that the
                // correction already dealt with.
                assertEquals(2, raw.received.size)
                assertTrue(
                    failure.clockOffsetSeconds in (skew - 5)..(skew + 5),
                    "the measured offset must reach the UI, was ${failure.clockOffsetSeconds}",
                )
            }
    }

    @Test
    fun `a clock in step is never reported, so no one is told to fix a healthy machine`() {
        RawHttpServer { _, _ -> RawHttpServer.response("400 Bad Request", """{"error":"invalid_grant"}""") }
            .use { raw ->
                val failure =
                    assertFailsWith<DeviceGrantException> {
                        DeviceGrantClient(issuer = raw.baseUrl).refreshAccessToken("DEAD-RT", DpopKey.generate())
                    }

                assertEquals(0L, failure.clockOffsetSeconds)
                assertEquals("invalid_grant", failure.oauthError)
            }
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
