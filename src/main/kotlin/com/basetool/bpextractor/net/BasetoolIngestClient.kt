package com.basetool.bpextractor.net

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
)

/** Signals an ingest send failure; [message] is the (already-localized) detail, safe to show. */
class IngestException(message: String) : Exception(message)

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
 */
class BasetoolIngestClient(
    private val baseUrl: String,
    private val http: HttpClient = defaultHttp(),
) {

    private val json = Json { ignoreUnknownKeys = true }

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
     * @param accessToken the bearer obtained via the device grant
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
     * @param accessToken the bearer obtained via the device grant
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
        val request =
            HttpRequest.newBuilder(URI.create(baseUrl.trimEnd('/') + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer $accessToken")
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Accept-Language", acceptLanguage)
                .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                .build()
        val response =
            try {
                http.send(request, HttpResponse.BodyHandlers.ofString())
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
        throw IngestException(problemDetail(response.body(), response.statusCode()))
    }

    /**
     * Extracts the RFC 7807 {@code detail} (already localized); appends the per-field validation
     * messages when present so a generic "Validation failed." names the offending field, and falls
     * back to a generic phrase when the body carries neither.
     */
    private fun problemDetail(body: String, status: Int): String {
        val problem =
            try {
                json.decodeFromString<IngestProblem>(body)
            } catch (_: Exception) {
                null
            }
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
