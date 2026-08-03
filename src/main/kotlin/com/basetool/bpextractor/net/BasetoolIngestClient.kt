package com.basetool.bpextractor.net

import com.basetool.bpextractor.net.auth.ServerClock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/** The ingest gateway's success answer: where the staged draft was put + the browser landing URL. */
@Serializable
data class IngestResponse(
    val handoffId: String = "",
    val kind: String = "",
    val frontendUrl: String = "",
)

/** RFC 7807 problem body the gateway returns on error — only the fields worth surfacing. */
@Serializable
data class IngestProblem(
    val title: String = "",
    val detail: String = "",
    val status: Int = 0,
    val code: String = "",
    /**
     * Per-field bean-validation messages (`"orders[0].goods[3].inputQuantity: must not be null"`),
     * present on the gateway's `VALIDATION_FAILED` problem whose `detail` is only the generic
     * "Validation failed." — without them the user cannot tell WHICH field was rejected.
     */
    val fieldErrors: List<String> = emptyList(),
) {
    companion object {
        /**
         * The gateway's stable code for "this client software is not approved" (`REQ-INGEST-011`):
         * the token authenticated fine, but its {@code azp} / scope / payload {@code tool} is not on
         * the server-side allowlist. **Permanent by construction** — the same binary will be refused
         * every time — so it must never be treated as a transient failure worth retrying.
         */
        const val CLIENT_NOT_ALLOWED = "CLIENT_NOT_ALLOWED"
    }
}

/**
 * Signals an ingest send failure; [message] is the (already-localized) detail, safe to show.
 *
 * @param message the RFC 7807 detail (plus field errors) to put in front of the user
 * @param code the problem's stable {@code code}, e.g. [IngestProblem.CLIENT_NOT_ALLOWED], so the UI
 *   can explain a specific rejection instead of only echoing the server's sentence; empty when the
 *   answer carried no code
 */
class IngestException(message: String, val code: String = "") : Exception(message)

/**
 * Sends the locally-produced export JSON to the basetool ingest gateway (epic
 * krt-profit/basetool#639, the `:ingest` module). The caller supplies the access token obtained via
 * the device grant; this client never authenticates.
 *
 * <p>The gateway terminates TLS at nginx-proxy-manager and runs plain HTTP behind it, so the prod
 * base URL is a publicly-trusted {@code https://ingest.<domain>} (standard TLS — no custom trust)
 * and the only non-TLS escape is an explicit {@code http://localhost} / {@code http://127.0.0.1}
 * for the dev stack. There is **no** global trust-all and no self-signed handling. Mirrors
 * {@code UpdateChecker}'s HTTP discipline; surfaces the RFC 7807 {@code detail} (localized via the
 * relayed {@code Accept-Language}).
 *
 * <p>**Always `Bearer`, never `DPoP` (RFC 9449, `REQ-INGEST-012`).** This client deliberately does
 * not present a sender-constrained token, because the gateway is a **relay**: it forwards the access
 * token onward to the basetool backend. A DPoP-bound token is bound to this client's key and, via
 * `htu`, to the *gateway's* URL — the second hop can neither carry a proof nor be covered by the
 * first one, so the backend receives a token issued for DPoP as a plain bearer and refuses it. That
 * is what broke every blueprint send on 2026-08-03, surfacing as the backend's opaque "you must sign
 * in" three layers from the cause.
 *
 * <p>And even where the backend accepted it, the binding would end at the gateway — which is exactly
 * where it would have to hold. Sender-constraining an access token only pays when the party that
 * validates it is the party that consumes it.
 *
 * <p>DPoP is still very much in use, one layer up: [DeviceGrantClient] proves possession at the
 * **token endpoint**, so Keycloak binds the **refresh token** — the long-lived credential this app
 * persists to disk. That is the credential worth protecting; an access token lives five minutes in
 * memory.
 */
class BasetoolIngestClient(
    private val baseUrl: String,
    private val http: HttpClient = defaultHttp(),
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** This server's clock, learned from its `Date` headers — see [ServerClock]. Its own, not shared. */
    private val clock = ServerClock()

    init {
        require(
            baseUrl.startsWith("https://") ||
                baseUrl.startsWith("http://localhost") ||
                baseUrl.startsWith("http://127.0.0.1")
        ) {
            "refusing a non-https ingest base URL (localhost excepted for dev): $baseUrl"
        }
    }

    /**
     * Sends a {@code RefineryExtract} JSON document and returns the handoff.
     *
     * @param accessToken the token obtained via the device grant
     * @param extractJson the serialized {@code RefineryExtract}
     * @param acceptLanguage the UI locale to relay (so backend problems are localized)
     * @return the gateway handoff (id, kind, frontend URL)
     * @throws IngestException with the gateway's problem detail on any non-2xx / failure
     */
    fun sendRefinery(
        accessToken: String,
        extractJson: String,
        acceptLanguage: String,
    ): IngestResponse = send("/v1/refinery-extract", accessToken, extractJson, acceptLanguage)

    /**
     * Sends a blueprint export JSON document and returns the handoff.
     *
     * @param accessToken the token obtained via the device grant
     * @param blueprintJson the serialized blueprint export
     * @param acceptLanguage the UI locale to relay
     * @return the gateway handoff (id, kind, frontend URL)
     * @throws IngestException with the gateway's problem detail on any non-2xx / failure
     */
    fun sendBlueprint(
        accessToken: String,
        blueprintJson: String,
        acceptLanguage: String,
    ): IngestResponse = send("/v1/blueprint-preview", accessToken, blueprintJson, acceptLanguage)

    private fun send(
        path: String,
        accessToken: String,
        bodyJson: String,
        acceptLanguage: String,
    ): IngestResponse {
        val uri = URI.create(baseUrl.trimEnd('/') + path)
        // Nothing is retried. A 403 CLIENT_NOT_ALLOWED is permanent by construction (the same binary
        // is refused every time), and the clock-correction retry that used to live here only ever
        // made sense for a DPoP proof's `iat` window — a plain bearer has no such window. The clock
        // is still observed below, because the token-endpoint proofs in DeviceGrantClient need it.
        val response =
            try {
                post(uri, accessToken, bodyJson, acceptLanguage)
            } catch (e: Exception) {
                throw IngestException("could not reach the basetool: ${e.message}")
            }
        if (response.statusCode() in 200..299) {
            return try {
                json.decodeFromString<IngestResponse>(response.body())
            } catch (e: Exception) {
                throw IngestException("the basetool answer was not parseable: ${e.message}")
            }
        }
        val problem =
            try {
                json.decodeFromString<IngestProblem>(response.body())
            } catch (_: Exception) {
                null
            }
        throw IngestException(problemDetail(problem, response.statusCode()), problem?.code.orEmpty())
    }

    private fun post(
        uri: URI,
        accessToken: String,
        bodyJson: String,
        acceptLanguage: String,
    ): HttpResponse<String> {
        val request =
            HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                // Always Bearer. See the class doc: the gateway relays this token to the backend, so
                // a sender-constrained one cannot survive the second hop.
                .header("Authorization", "Bearer $accessToken")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Accept-Language", acceptLanguage)
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                .build()
        val sentAt = Instant.now()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        // Keep observing the server clock even without a proof here: DeviceGrantClient's
        // token-endpoint proofs are written from the same estimate, and Keycloak allows only a 15 s
        // skew, so every extra sample is worth having.
        clock.observe(response.headers().firstValue("Date").orElse(null), sentAt)
        return response
    }


    /**
     * Extracts the RFC 7807 {@code detail} (already localized); appends the per-field validation
     * messages when present so a generic "Validation failed." names the offending field, and falls
     * back to a generic phrase when the body carries neither.
     */
    private fun problemDetail(problem: IngestProblem?, status: Int): String {
        val detail = problem?.detail?.ifBlank { null }
        val fields = problem?.fieldErrors?.takeIf { it.isNotEmpty() }?.joinToString("; ")
        return when {
            detail != null && fields != null -> "$detail ($fields)"
            detail != null -> detail
            fields != null -> fields
            else -> "the basetool rejected the upload (HTTP $status)"
        }
    }

    companion object {
        private fun defaultHttp(): HttpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    }
}
