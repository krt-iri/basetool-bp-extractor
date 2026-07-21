package com.basetool.bpextractor.net

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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
}
