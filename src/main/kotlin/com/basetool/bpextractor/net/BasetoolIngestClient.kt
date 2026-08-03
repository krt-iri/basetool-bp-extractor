package com.basetool.bpextractor.net

import com.basetool.bpextractor.net.auth.DpopKey
import com.basetool.bpextractor.net.auth.DpopNonce
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

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
 * <p>**DPoP (RFC 9449, `REQ-INGEST-012`).** When the caller passes the [DpopKey] the access token is
 * bound to, the request goes out as {@code Authorization: DPoP <token>} with a matching proof that
 * additionally carries {@code ath} — the hash of the very token it accompanies, which is what stops
 * a proof captured on one request from being reused with a different token. Passing {@code null}
 * sends the plain {@code Bearer} of every build so far; the gateway accepts both while its
 * {@code dpop-required} flag is off, so the two modes coexist for the whole migration window.
 */
class BasetoolIngestClient(
    private val baseUrl: String,
    private val http: HttpClient = defaultHttp(),
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** This resource server's nonce — separate from the authorization server's (RFC 9449 §8/§9). */
    private val nonce = DpopNonce()

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
     * @param dpopKey the key [accessToken] is bound to, or {@code null} to send a plain bearer
     * @return the gateway handoff (id, kind, frontend URL)
     * @throws IngestException with the gateway's problem detail on any non-2xx / failure
     */
    fun sendRefinery(
        accessToken: String,
        extractJson: String,
        acceptLanguage: String,
        dpopKey: DpopKey? = null,
    ): IngestResponse = send("/v1/refinery-extract", accessToken, extractJson, acceptLanguage, dpopKey)

    /**
     * Sends a blueprint export JSON document and returns the handoff.
     *
     * @param accessToken the token obtained via the device grant
     * @param blueprintJson the serialized blueprint export
     * @param acceptLanguage the UI locale to relay
     * @param dpopKey the key [accessToken] is bound to, or {@code null} to send a plain bearer
     * @return the gateway handoff (id, kind, frontend URL)
     * @throws IngestException with the gateway's problem detail on any non-2xx / failure
     */
    fun sendBlueprint(
        accessToken: String,
        blueprintJson: String,
        acceptLanguage: String,
        dpopKey: DpopKey? = null,
    ): IngestResponse = send("/v1/blueprint-preview", accessToken, blueprintJson, acceptLanguage, dpopKey)

    private fun send(
        path: String,
        accessToken: String,
        bodyJson: String,
        acceptLanguage: String,
        dpopKey: DpopKey?,
    ): IngestResponse {
        val uri = URI.create(baseUrl.trimEnd('/') + path)
        var response =
            try {
                post(uri, accessToken, bodyJson, acceptLanguage, dpopKey)
            } catch (e: Exception) {
                throw IngestException("could not reach the basetool: ${e.message}")
            }
        // RFC 9449 §8: the server may refuse the proof and hand out a nonce it wants echoed back.
        // Retry EXACTLY once, with a fresh proof carrying it. The condition requires the server to
        // have supplied a nonce, which no other rejection does — in particular a 403
        // CLIENT_NOT_ALLOWED is permanent (the same binary is refused every time) and must never be
        // re-sent, and the status check alone already excludes it.
        if (dpopKey != null && isNonceChallenge(response)) {
            response =
                try {
                    post(uri, accessToken, bodyJson, acceptLanguage, dpopKey)
                } catch (e: Exception) {
                    throw IngestException("could not reach the basetool: ${e.message}")
                }
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
        val builder =
            HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                // RFC 9449 §7.1: a sender-constrained token travels under the DPoP scheme, never
                // Bearer — presenting a bound token as a bearer is exactly the downgrade the
                // gateway logs as suspicious.
                .header("Authorization", if (dpopKey == null) "Bearer $accessToken" else "DPoP $accessToken")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Accept-Language", acceptLanguage)
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
        if (dpopKey != null) {
            builder.header(
                "DPoP",
                dpopKey.proof(
                    htm = "POST",
                    htu = DpopKey.htu(uri),
                    accessToken = accessToken,
                    nonce = nonce.current(),
                ),
            )
        }
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        nonce.remember(response.headers().firstValue(DpopNonce.HEADER).orElse(null))
        return response
    }

    /**
     * Whether the answer is RFC 9449 §8's "retry with this nonce". Deliberately narrow: only the two
     * statuses a proof rejection uses, and only when the server actually supplied a nonce — so no
     * ordinary rejection (a {@code 400 VALIDATION_FAILED}, a {@code 403 CLIENT_NOT_ALLOWED}) can be
     * mistaken for something worth sending the payload for a second time.
     *
     * <p>**This is defence, not a live path.** Spring Security 7.1 — what the gateway runs — has no
     * nonce implementation at all on the resource-server side, so today it never challenges and this
     * never fires. It stays because the handshake is the client's half of the RFC, costs one branch,
     * and is exactly the kind of thing that is discovered the hard way when a future Spring version
     * or a fronting proxy starts demanding one. Nothing about the send depends on it.
     */
    private fun isNonceChallenge(response: HttpResponse<String>): Boolean =
        (response.statusCode() == 400 || response.statusCode() == 401) &&
            response.headers().firstValue(DpopNonce.HEADER).isPresent

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
