package com.basetool.bpextractor.net

import com.basetool.bpextractor.net.auth.DpopKey
import com.basetool.bpextractor.net.auth.DpopNonce
import com.basetool.bpextractor.net.auth.DpopProofs
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Exercises the ingest client against a local stand-in for the gateway (JDK [HttpServer]): the
 * bearer + Accept-Language relay, the success parse, RFC 7807 detail surfacing, and the
 * https-or-localhost guard. No real credentials / network (CLAUDE.md test rule).
 */
class BasetoolIngestClientTest {

    private lateinit var server: HttpServer
    private lateinit var baseUrl: String
    private var seenAuth: String? = null
    private var seenLang: String? = null

    /** Every request's `DPoP` header, in order — also the request counter (no-retry assertions). */
    private val proofs = mutableListOf<String?>()

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        baseUrl = "http://localhost:${server.address.port}"
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
    }

    private fun respond(ex: HttpExchange, code: Int, body: String, contentType: String = "application/json") {
        seenAuth = ex.requestHeaders.getFirst("Authorization")
        seenLang = ex.requestHeaders.getFirst("Accept-Language")
        synchronized(proofs) { proofs.add(ex.requestHeaders.getFirst("DPoP")) }
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", contentType)
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    @Test
    fun sendsRefineryAndRelaysBearerAndLocale() {
        server.createContext("/v1/refinery-extract") { ex ->
            respond(ex, 200, """{"handoffId":"H1","kind":"REFINERY","frontendUrl":"https://app/x?handoff=H1"}""")
        }
        val response = BasetoolIngestClient(baseUrl).sendRefinery("tok-123", "{\"schemaVersion\":1}", "de")
        assertEquals("H1", response.handoffId)
        assertEquals("REFINERY", response.kind)
        assertEquals("https://app/x?handoff=H1", response.frontendUrl)
        assertEquals("Bearer tok-123", seenAuth)
        assertEquals("de", seenLang)
    }

    @Test
    fun surfacesProblemDetailOnError() {
        server.createContext("/v1/refinery-extract") { ex ->
            respond(
                ex,
                400,
                """{"title":"Bad request","detail":"Nicht unterstützte schemaVersion.","status":400,"code":"BAD_REQUEST"}""",
                "application/problem+json",
            )
        }
        val ex =
            assertFailsWith<IngestException> {
                BasetoolIngestClient(baseUrl).sendRefinery("tok", "{}", "de")
            }
        assertEquals("Nicht unterstützte schemaVersion.", ex.message)
    }

    @Test
    fun appendsFieldErrorsToGenericValidationDetail() {
        // The gateway's bean-validation problem carries only a generic detail; the field messages
        // are what tell the user WHICH field was rejected (e.g. a null inputQuantity from a read
        // that lost the number column) — the client must surface them.
        server.createContext("/v1/refinery-extract") { ex ->
            respond(
                ex,
                400,
                """{"title":"Validation failed","detail":"Validation failed.","status":400,""" +
                    """"code":"VALIDATION_FAILED","fieldErrors":["orders[0].goods[3].inputQuantity: must not be null"]}""",
                "application/problem+json",
            )
        }
        val ex =
            assertFailsWith<IngestException> {
                BasetoolIngestClient(baseUrl).sendRefinery("tok", "{}", "de")
            }
        assertEquals(
            "Validation failed. (orders[0].goods[3].inputQuantity: must not be null)",
            ex.message,
        )
    }

    @Test
    fun rejectsNonHttpsBaseUrl() {
        assertFailsWith<IllegalArgumentException> { BasetoolIngestClient("http://evil.example") }
    }

    // --- DPoP (RFC 9449, REQ-INGEST-012) -------------------------------------------------------

    @Test
    fun `a bound token travels under the DPoP scheme with an ath-carrying proof`() {
        server.createContext("/v1/refinery-extract") { ex ->
            respond(ex, 200, """{"handoffId":"H1","kind":"REFINERY","frontendUrl":"https://app/x"}""")
        }

        BasetoolIngestClient(baseUrl).sendRefinery("tok-123", "{}", "de", DpopKey.generate())

        assertEquals("DPoP tok-123", seenAuth, "a sender-constrained token is never a Bearer")
        val proof = assertNotNull(proofs.single())
        assertEquals("POST", DpopProofs.claim(proof, "htm"))
        assertEquals("$baseUrl/v1/refinery-extract", DpopProofs.claim(proof, "htu"))
        // ath is what stops a proof captured on one request being reused with a different token.
        assertEquals(DpopProofs.expectedAth("tok-123"), DpopProofs.claim(proof, "ath"))
    }

    @Test
    fun `without a key the request stays exactly the bearer every shipped build sends`() {
        // The whole migration rests on this: a gateway in dual mode, or a Keycloak that never bound
        // the token, must keep seeing precisely what it sees today.
        server.createContext("/v1/blueprint-preview") { ex ->
            respond(ex, 200, """{"handoffId":"B1","kind":"BLUEPRINT","frontendUrl":"https://app/bp"}""")
        }

        BasetoolIngestClient(baseUrl).sendBlueprint("tok-123", "{}", "de")

        assertEquals("Bearer tok-123", seenAuth)
        assertNull(proofs.single(), "no key ⇒ no DPoP header")
    }

    @Test
    fun `a nonce challenge is retried exactly once with the nonce echoed back`() {
        server.createContext("/v1/refinery-extract") { ex ->
            if (proofs.size == 0) {
                ex.responseHeaders.add(DpopNonce.HEADER, "N-7")
                respond(ex, 401, """{"title":"Unauthorized","status":401}""")
            } else {
                respond(ex, 200, """{"handoffId":"H2","kind":"REFINERY","frontendUrl":"https://app/y"}""")
            }
        }

        val response = BasetoolIngestClient(baseUrl).sendRefinery("tok", "{}", "de", DpopKey.generate())

        assertEquals("H2", response.handoffId)
        assertEquals(2, proofs.size, "exactly one retry")
        assertNull(DpopProofs.claim(proofs[0]!!, "nonce"))
        assertEquals("N-7", DpopProofs.claim(proofs[1]!!, "nonce"))
    }

    @Test
    fun `a 403 CLIENT_NOT_ALLOWED surfaces its code and is never re-sent`() {
        // The gateway refuses this client software outright (REQ-INGEST-011). It will refuse it
        // again on every attempt, so a retry is pure noise — and would re-upload the payload. The
        // DPoP-Nonce header below is deliberately adversarial: even that must not buy a second try.
        server.createContext("/v1/refinery-extract") { ex ->
            ex.responseHeaders.add(DpopNonce.HEADER, "N-9")
            respond(
                ex,
                403,
                """{"title":"Forbidden","detail":"Dieses Programm ist für die Schnittstelle nicht """ +
                    """freigegeben.","status":403,"code":"CLIENT_NOT_ALLOWED"}""",
                "application/problem+json",
            )
        }

        val failure =
            assertFailsWith<IngestException> {
                BasetoolIngestClient(baseUrl).sendRefinery("tok", "{}", "de", DpopKey.generate())
            }

        assertEquals(IngestProblem.CLIENT_NOT_ALLOWED, failure.code)
        assertEquals("Dieses Programm ist für die Schnittstelle nicht freigegeben.", failure.message)
        assertEquals(1, proofs.size, "a permanent rejection must be sent exactly once")
    }

    @Test
    fun `an ordinary rejection carries no code and is not retried either`() {
        server.createContext("/v1/refinery-extract") { ex ->
            respond(
                ex,
                400,
                """{"title":"Bad request","detail":"Nicht unterstützte schemaVersion.","status":400}""",
                "application/problem+json",
            )
        }

        val failure =
            assertFailsWith<IngestException> {
                BasetoolIngestClient(baseUrl).sendRefinery("tok", "{}", "de", DpopKey.generate())
            }

        assertEquals("", failure.code)
        assertEquals(1, proofs.size, "no nonce was offered, so there is nothing to retry with")
    }
}
