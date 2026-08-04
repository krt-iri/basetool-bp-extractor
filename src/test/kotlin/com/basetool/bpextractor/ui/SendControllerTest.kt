package com.basetool.bpextractor.ui

import com.basetool.bpextractor.config.AppConfig
import com.basetool.bpextractor.config.AppConfigStore
import com.basetool.bpextractor.net.auth.DeviceGrantClient
import com.basetool.bpextractor.net.auth.DpopKey
import com.basetool.bpextractor.net.auth.DpopProofs
import com.basetool.bpextractor.net.auth.FakeCredentialStore
import com.basetool.bpextractor.net.auth.StoredCredential
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Exercises the [SendController] "remember me" path (epic krt-profit/basetool#639, #648) against a
 * single local stand-in for Keycloak + the ingest gateway (JDK [HttpServer]) — no real credentials,
 * no real network. Proves the silent refresh skips the browser and re-persists the rotated token,
 * and that a dead stored token is dropped.
 */
class SendControllerTest {

    private lateinit var server: HttpServer
    private lateinit var base: String
    private val deviceCalls = AtomicInteger(0)
    private var browseCount = 0

    /** The `Authorization` / `DPoP` headers the gateway stand-in last saw (DPoP assertions). */
    private var ingestAuth: String? = null
    private var ingestProof: String? = null

    /** Per-test override for the gateway answer — the contexts are registered once, in [setUp]. */
    private var ingestHandler: ((HttpExchange, String) -> Unit)? = null

    @BeforeTest
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/protocol/openid-connect/auth/device") { ex ->
            deviceCalls.incrementAndGet()
            respond(ex, 400, """{"error":"unauthorized_client"}""") // fallback path: fail fast, no poll
        }
        server.createContext("/v1/refinery-extract") { ex ->
            ingest(ex, """{"handoffId":"H1","kind":"REFINERY","frontendUrl":"https://app/x?handoff=H1"}""")
        }
        server.createContext("/v1/blueprint-preview") { ex ->
            ingest(ex, """{"handoffId":"B1","kind":"BLUEPRINT","frontendUrl":"https://app/bp?handoff=B1"}""")
        }
        server.start()
        base = "http://localhost:${server.address.port}"
    }

    /** Records what the send actually presented, then answers [ok] unless a test overrode it. */
    private fun ingest(ex: HttpExchange, ok: String) {
        ingestAuth = ex.requestHeaders.getFirst("Authorization")
        ingestProof = ex.requestHeaders.getFirst("DPoP")
        val override = ingestHandler
        if (override != null) override(ex, ok) else respond(ex, 200, ok)
    }

    @AfterTest
    fun tearDown() {
        server.stop(0)
    }

    private fun respond(ex: HttpExchange, code: Int, body: String) {
        val bytes = body.toByteArray()
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    /** A consented config store pointing the ingest base URL at the local stand-in. */
    private fun consentedConfig(): AppConfigStore {
        val dir = Files.createTempDirectory("sc-send-test").toFile()
        val store = AppConfigStore(dir)
        store.save(AppConfig(ingestBaseUrl = base, consentGiven = true))
        return store
    }

    private fun controller(store: FakeCredentialStore) =
        SendController(
            configStore = consentedConfig(),
            deviceGrant = DeviceGrantClient(issuer = base),
            credentialStore = store,
            browse = { browseCount++ },
        )

    @Test
    fun `a stored token sends silently and re-persists the rotated token`() {
        server.createContext("/protocol/openid-connect/token") { ex ->
            respond(ex, 200, """{"access_token":"AT","refresh_token":"RT-ROTATED","token_type":"Bearer","expires_in":300}""")
        }
        val store = FakeCredentialStore("RT-STORED")
        val controller = controller(store)

        runBlocking { controller.request(this, SendKind.REFINERY, """{"x":1}""", "de") }

        assertTrue(controller.state is SendState.Done, "expected Done, was ${controller.state}")
        assertEquals("https://app/x?handoff=H1", (controller.state as SendState.Done).frontendUrl)
        // The vault blob is a StoredCredential record now (token + its DPoP key), not a bare token.
        assertEquals(
            "RT-ROTATED",
            StoredCredential.decode(assertNotNull(store.stored))?.refreshToken,
            "the rotated refresh token must be persisted",
        )
        assertEquals(1, store.saveCount)
        assertEquals(0, deviceCalls.get(), "the silent path must not start a device grant")
        assertEquals(0, browseCount, "the silent path must not open the browser")
    }

    @Test
    fun `a dead stored token is cleared before falling back to a fresh login`() {
        server.createContext("/protocol/openid-connect/token") { ex ->
            respond(ex, 400, """{"error":"invalid_grant"}""") // the stored refresh token is dead
        }
        val store = FakeCredentialStore("RT-DEAD")
        val controller = controller(store)

        runBlocking { controller.request(this, SendKind.REFINERY, """{"x":1}""", "de") }

        assertNull(store.stored, "the dead token must be dropped")
        assertEquals(1, deviceCalls.get(), "it must fall back to a device grant")
        assertTrue(controller.state is SendState.Error, "the stubbed device grant fails, so we end in Error")
    }

    // --- DPoP (RFC 9449, REQ-INGEST-012) -------------------------------------------------------

    @Test
    fun `a bound token goes to the gateway under DPoP with a proof, and its key is persisted`() {
        // ADR-0129: the gateway VALIDATES the proof now instead of relaying the token onward, so a
        // sender-constrained token finally pays — the party that checks the proof is the party that
        // consumes it. 2.7.x sent this same bound token as a plain bearer, which a resource server
        // refuses outright; that is what broke every send from 2026-08-03.
        server.createContext("/protocol/openid-connect/token") { ex ->
            respond(ex, 200, """{"access_token":"AT","refresh_token":"RT-ROTATED","token_type":"DPoP","expires_in":300}""")
        }
        val store = FakeCredentialStore("RT-STORED")

        runBlocking { controller(store).request(this, SendKind.REFINERY, """{"x":1}""", "de") }

        assertEquals("DPoP AT", ingestAuth, "a bound token goes out under the DPoP scheme")
        assertNotNull(ingestProof, "and carries the proof the gateway validates")

        // The refresh token and the key that redeems it have to survive together, or the credential
        // is unusable on the next start (a bound refresh token needs its own key).
        val stored = assertNotNull(StoredCredential.decode(assertNotNull(store.stored)))
        assertEquals("RT-ROTATED", stored.refreshToken)
        assertNotNull(DpopKey.fromEncoded(assertNotNull(stored.dpopKey)))
    }

    @Test
    fun `an unbound token keeps the plain bearer — the extractor follows the server`() {
        // Still load-bearing, and the reason the key stays optional: a Keycloak with DPoP off
        // answers token_type=Bearer, and presenting such a token under the DPoP scheme is a hard 401
        // — Spring's JwkThumbprintValidator requires cnf.jkt and says "jkt claim is required". The
        // extractor must follow the server, not its own wish to use DPoP. It is also the transition
        // safety net: the gateway keeps .jwt() alongside .dPoP(), so this path stays accepted.
        server.createContext("/protocol/openid-connect/token") { ex ->
            respond(ex, 200, """{"access_token":"AT","refresh_token":"RT-ROTATED","token_type":"Bearer","expires_in":300}""")
        }

        runBlocking { controller(FakeCredentialStore("RT-STORED")).request(this, SendKind.REFINERY, """{"x":1}""", "de") }

        assertEquals("Bearer AT", ingestAuth)
        assertNull(ingestProof, "an unbound token must not be accompanied by a proof")
    }

    @Test
    fun `a legacy stored token still refreshes and is upgraded to a keyed record`() {
        // Users of the already-released build have a bare refresh token in the vault. Keycloak binds
        // the newly issued pair to the proof's key, so the very next send upgrades the credential
        // in place — no forced re-login.
        val proofs = mutableListOf<String?>()
        server.createContext("/protocol/openid-connect/token") { ex ->
            proofs.add(ex.requestHeaders.getFirst("DPoP"))
            respond(ex, 200, """{"access_token":"AT","refresh_token":"RT-BOUND","token_type":"DPoP","expires_in":300}""")
        }
        val store = FakeCredentialStore("plain-legacy-refresh-token")

        runBlocking { controller(store).request(this, SendKind.REFINERY, """{"x":1}""", "de") }

        assertNotNull(proofs.single(), "the refresh of a legacy token still carries a proof")
        assertEquals(0, deviceCalls.get(), "and must not force an interactive login")
        val stored = assertNotNull(StoredCredential.decode(assertNotNull(store.stored)))
        assertEquals("RT-BOUND", stored.refreshToken)
        assertNotNull(stored.dpopKey, "the record is rewritten in the keyed shape")
    }

    @Test
    fun `the stored key is the one reused on the next send`() {
        // Reusing the key is the whole point: a refresh token bound to key A cannot be redeemed with
        // key B, so a controller that minted a fresh key per send would lock the user out.
        server.createContext("/protocol/openid-connect/token") { ex ->
            respond(ex, 200, """{"access_token":"AT","refresh_token":"RT","token_type":"DPoP","expires_in":300}""")
        }
        val store = FakeCredentialStore("RT-STORED")

        runBlocking { controller(store).request(this, SendKind.REFINERY, """{"x":1}""", "de") }
        val firstKey = assertNotNull(StoredCredential.decode(assertNotNull(store.stored))?.dpopKey)
        runBlocking { controller(store).request(this, SendKind.REFINERY, """{"x":1}""", "de") }
        val secondKey = assertNotNull(StoredCredential.decode(assertNotNull(store.stored))?.dpopKey)

        assertEquals(
            DpopKey.fromEncoded(firstKey)?.thumbprint,
            DpopKey.fromEncoded(secondKey)?.thumbprint,
            "a persisted key must be reused, never regenerated",
        )
    }

    @Test
    fun `a rejected proof does not cost the user their stored login`() {
        // Keycloak validates the DPoP proof BEFORE the grant and reports every proof defect as a
        // generic invalid_request — a clock more than 15s fast is enough. That says nothing about
        // the refresh token, so it must not be deleted, and there is no point opening a browser for
        // a device grant whose polls carry the very same proof.
        server.createContext("/protocol/openid-connect/token") { ex ->
            respond(ex, 400, """{"error":"invalid_request","error_description":"DPoP proof is not active"}""")
        }
        val store = FakeCredentialStore("RT-STILL-VALID")
        val controller = controller(store)

        runBlocking { controller.request(this, SendKind.REFINERY, """{"x":1}""", "de") }

        assertEquals("RT-STILL-VALID", store.stored, "a proof error must not destroy the credential")
        assertEquals(0, deviceCalls.get(), "and must not start a doomed interactive grant")
        assertEquals(0, browseCount)
        val state = controller.state
        assertTrue(state is SendState.Error, "expected Error, was $state")
        // The server's own words, so a clock-skew failure is diagnosable instead of a bare HTTP 400.
        assertTrue(
            state.message.contains("DPoP proof is not active"),
            "the reason must reach the user, was: ${state.message}",
        )
    }

    @Test
    fun `a 403 CLIENT_NOT_ALLOWED ends the flow with its code and no second attempt`() {
        server.createContext("/protocol/openid-connect/token") { ex ->
            respond(ex, 200, """{"access_token":"AT","refresh_token":"RT","token_type":"Bearer","expires_in":300}""")
        }
        val attempts = AtomicInteger(0)
        ingestHandler = { ex, _ ->
            attempts.incrementAndGet()
            respond(
                ex,
                403,
                """{"title":"Client not allowed","detail":"This client is not approved for the basetool """ +
                    """ingest path.","status":403,"code":"CLIENT_NOT_ALLOWED"}""",
            )
        }
        val controller = controller(FakeCredentialStore("RT-STORED"))

        runBlocking { controller.request(this, SendKind.BLUEPRINT, """{"x":1}""", "de") }

        val state = controller.state
        assertTrue(state is SendState.Error, "expected Error, was $state")
        // The code is what lets the overlay say "this build is not approved" instead of echoing an
        // English server sentence that reads like something a retry could fix.
        assertEquals("CLIENT_NOT_ALLOWED", state.code)
        assertEquals(1, attempts.get(), "a permanent refusal is never re-sent")
    }

    @Test
    fun `a blueprint send posts to the blueprint endpoint`() {
        server.createContext("/protocol/openid-connect/token") { ex ->
            respond(ex, 200, """{"access_token":"AT","refresh_token":"RT-ROTATED","token_type":"Bearer","expires_in":300}""")
        }
        val controller = controller(FakeCredentialStore("RT-STORED"))

        runBlocking { controller.request(this, SendKind.BLUEPRINT, """{"schemaVersion":1}""", "en") }

        // The BLUEPRINT kind routes to /v1/blueprint-preview, whose stand-in returns the B1 handoff.
        assertTrue(controller.state is SendState.Done, "expected Done, was ${controller.state}")
        assertEquals("https://app/bp?handoff=B1", (controller.state as SendState.Done).frontendUrl)
    }
}
