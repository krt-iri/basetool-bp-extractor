package com.basetool.bpextractor.net


import com.basetool.bpextractor.net.auth.DpopKey
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
 * <p>**The scheme follows the server (RFC 9449, `REQ-INGEST-012`).** A proof accompanies the request
 * exactly when Keycloak actually bound the token (`token_type: DPoP`), and the `Authorization` header
 * switches to the `DPoP` scheme with it. Presenting an *unbound* token under that scheme is a hard
 * `401` — Spring's `JwkThumbprintValidator` demands `cnf.jkt` — so the decision belongs to the
 * server's answer, never to this client's preference. The gateway accepts both schemes, so a client
 * rollout needs no flag day.
 *
 * <p>**Why this was once the opposite.** While the gateway *relayed* the access token to the backend,
 * a bound token could not survive the second hop: the proof binds to this client's key and, via
 * `htu`, to the gateway's URL, so the backend received a DPoP-issued token as a plain bearer and
 * refused it. That broke every blueprint send on 2026-08-03, surfacing as the backend's opaque "you
 * must sign in" three layers from the cause. The conclusion drawn then — never bind the access
 * token — treated the relay as fixed. ADR-0129 removed the relay instead: the gateway validates the
 * proof itself and calls the backend under its own service account, so the party that validates the
 * token is now the party that consumes it, which is the only arrangement in which
 * sender-constraining an access token pays at all.
 *
 * <p>DPoP therefore protects both credentials now: the access token on this hop, and the **refresh
 * token** that [DeviceGrantClient] binds at the token endpoint — the long-lived one this app
 * persists to disk, and the one most worth protecting.
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
        dpopKey: DpopKey?,
    ): IngestResponse = send("/v1/refinery-extract", accessToken, extractJson, acceptLanguage, dpopKey)

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
        dpopKey: DpopKey?,
    ): IngestResponse = send("/v1/blueprint-preview", accessToken, blueprintJson, acceptLanguage, dpopKey)

    private fun send(
        path: String,
        accessToken: String,
        bodyJson: String,
        acceptLanguage: String,
        dpopKey: DpopKey?,
    ): IngestResponse {
        val uri = URI.create(baseUrl.trimEnd('/') + path)
        // Nothing is retried. A 403 CLIENT_NOT_ALLOWED is permanent by construction (the same binary
        // is refused every time), and a clock-correction retry is unnecessary rather than
        // inapplicable: this request DOES carry a proof whenever the token is bound, and that proof's
        // `iat` comes from [clock] — the gateway's own time, learned from its `Date` headers — so a
        // skewed local clock cannot produce a stale proof in the first place. Retrying would only
        // repeat a rejection whose cause the second attempt shares.
        val response =
            try {
                post(uri, accessToken, bodyJson, acceptLanguage, dpopKey)
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
        dpopKey: DpopKey?,
    ): HttpResponse<String> {
        val request =
            HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                // FOLLOW THE SERVER, never our own wish to use DPoP. A key is passed only when
                // Keycloak actually bound the token (token_type: DPoP), because presenting an
                // UNBOUND token under the DPoP scheme is a hard 401 — Spring's JwkThumbprintValidator
                // requires cnf.jkt and answers "jkt claim is required".
                //
                // When it is bound, the proof goes with it and the gateway validates it itself
                // (ADR-0129). `ath` binds the proof to THIS access token and `htu` to THIS URL, so
                // neither can be lifted onto another request. DpopKey.htu normalises scheme/host and
                // drops a default port to match what the gateway compares against — that comparison
                // is a byte-exact String.equals on both sides, so the two normalisations must agree.
                .header(
                    "Authorization",
                    if (dpopKey == null) "Bearer $accessToken" else "DPoP $accessToken",
                )
                .apply {
                    if (dpopKey != null) {
                        header(
                            "DPoP",
                            dpopKey.proof(htm = "POST", htu = DpopKey.htu(uri), accessToken = accessToken),
                        )
                    }
                }
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
