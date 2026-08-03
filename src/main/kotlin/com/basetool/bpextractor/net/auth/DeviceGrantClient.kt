package com.basetool.bpextractor.net.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import kotlin.math.abs

/** The Keycloak device-authorization answer — only the fields the flow needs. */
@Serializable
data class DeviceCodeResponse(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri") val verificationUri: String = "",
    @SerialName("verification_uri_complete") val verificationUriComplete: String = "",
    @SerialName("expires_in") val expiresIn: Long = 600,
    /** Minimum seconds between token polls; Keycloak default is 5. */
    val interval: Long = 5,
) {
    /** The URL to open in the browser: the complete form (with the code) when present. */
    fun browserUrl(): String = verificationUriComplete.ifBlank { verificationUri }
}

/** The token answer (or, on the error path, [TokenErrorResponse]). */
@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String = "",
    @SerialName("refresh_token") val refreshToken: String = "",
    @SerialName("token_type") val tokenType: String = "",
    @SerialName("expires_in") val expiresIn: Long = 0,
) {
    /**
     * Whether the server actually sender-constrained this token: RFC 9449 §5 requires the answer's
     * {@code token_type} to be {@code DPoP} (not {@code Bearer}) for a bound token, so this is the
     * authoritative signal — and the reason a proof can be *offered* unconditionally without risk. A
     * Keycloak with DPoP switched off ignores the proof and keeps answering {@code Bearer}, and the
     * caller then keeps presenting the token under the bearer scheme exactly as before. The
     * comparison is case-insensitive per RFC 9110 §11.1.
     *
     * @return {@code true} when the token must be presented under the {@code DPoP} scheme
     */
    fun isDpopBound(): Boolean = tokenType.equals("DPoP", ignoreCase = true)
}

/** The OAuth2 error body returned by the token endpoint while/if the grant is not (yet) ready. */
@Serializable
data class TokenErrorResponse(
    val error: String = "",
    @SerialName("error_description") val errorDescription: String = "",
)

/**
 * Signals that the device-grant flow could not complete; [message] is safe to show (no token).
 *
 * @param message the already-safe detail to surface
 * @param oauthError the OAuth2 {@code error} code the server named, when it named one. Load-bearing
 *   on the refresh path: only a verdict that the *grant* is dead ([TOKEN_REJECTED]) justifies
 *   throwing the stored credential away. Since DPoP, this same exception also covers proof-level
 *   failures — Keycloak validates the proof **before** the grant and reports every proof defect as a
 *   generic {@code invalid_request} — which say nothing at all about the refresh token. Empty for
 *   transport failures and unparseable answers.
 * @param clockOffsetSeconds the measured deviation of this machine's clock from the server's, but
 *   only once it is large enough to be the plausible cause ([ServerClock.REPORTABLE_SECONDS]) and
 *   only after correcting for it did not rescue the request; {@code 0} otherwise. It is a *measured*
 *   value, taken from the server's own {@code Date} header — so the UI can state it as fact rather
 *   than guess at a cause.
 */
class DeviceGrantException(
    message: String,
    val oauthError: String = "",
    val clockOffsetSeconds: Long = 0,
) : Exception(message) {
    companion object {
        /**
         * The OAuth2 error codes that mean the presented token is unusable and worth deleting
         * (RFC 6749 §5.2): expired, revoked, or reuse-detected. Everything else — a proof rejection,
         * a 5xx, a dropped connection — leaves the stored credential alone.
         */
        val TOKEN_REJECTED = setOf("invalid_grant", "invalid_token")
    }
}

/**
 * OAuth2 Device Authorization Grant (RFC 8628) client for Keycloak (epic
 * krt-profit/basetool#639, sub-issue #641's public client `basetool-sc-extractor`). It requests a
 * device + user code, the UI shows the user code and opens the verification URL in the browser,
 * and this client polls the token endpoint until the user approves — one click under an active
 * SSO session.
 *
 * <p>Mirrors {@code UpdateChecker}'s HTTP discipline: a shared timeout-bounded [HttpClient],
 * https-only, kotlinx-serialization with {@code ignoreUnknownKeys}. The realm issuer is a hardcoded
 * **prod** constant — only the ingest base URL is configurable (it lives in the ingest client) —
 * but the constructor accepts an override so tests can point at a local stand-in. No secret is held
 * (public client, no client secret); PKCE is not used because the device-code itself is the
 * proof-of-possession in this grant.
 *
 * <p>**DPoP (RFC 9449, `REQ-INGEST-012`).** Every request to the *token* endpoint may carry a proof
 * signed by the caller's [DpopKey]; Keycloak then binds the issued access **and refresh** token to
 * that key and answers {@code token_type: DPoP}. The proof is optional at this layer on purpose —
 * passing `null` reproduces the pre-DPoP behaviour exactly, and a Keycloak without the feature
 * simply ignores the header and keeps issuing plain bearer tokens. The device-authorization endpoint
 * takes no proof (nothing is issued there).
 */
class DeviceGrantClient(
    private val issuer: String = PROD_ISSUER,
    private val clientId: String = CLIENT_ID,
    private val http: HttpClient = defaultHttp(),
) {

    private val json = Json { ignoreUnknownKeys = true }

    private val deviceEndpoint = "$issuer/protocol/openid-connect/auth/device"
    private val tokenEndpoint = "$issuer/protocol/openid-connect/token"

    /**
     * This authorization server's clock, learned from its `Date` headers. Keycloak's `iat` window is
     * the tightest in the system (−25s…+15s), so this is what keeps a desktop with a drifting clock
     * able to authenticate at all. It belongs to this server alone — clocks, like nonces, are not
     * shared between the authorization server and the gateway.
     */
    private val clock = ServerClock()

    init {
        require(issuer.startsWith("https://") || issuer.startsWith("http://localhost")) {
            "refusing a non-https issuer: $issuer"
        }
    }

    /**
     * Starts the flow: asks Keycloak for a device + user code. The caller shows
     * [DeviceCodeResponse.userCode] and opens [DeviceCodeResponse.browserUrl].
     *
     * @return the device-code grant details
     * @throws DeviceGrantException when the request fails or the answer is unparseable
     */
    fun requestDeviceCode(): DeviceCodeResponse {
        val form =
            encodeForm(
                "client_id" to clientId,
                "scope" to "openid $INGEST_SCOPE",
            )
        val request =
            HttpRequest.newBuilder(URI.create(deviceEndpoint))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build()
        val response =
            try {
                val sentAt = Instant.now()
                http.send(request, HttpResponse.BodyHandlers.ofString()).also { observeClock(it, sentAt) }
            } catch (e: Exception) {
                throw DeviceGrantException("device-authorization request failed: ${e.message}")
            }
        // This call carries no proof, so it is the free opportunity to learn the server's clock
        // before the first one is signed — the interactive path never needs a correction retry.
        if (response.statusCode() != 200) {
            throw DeviceGrantException("device-authorization request failed: HTTP ${response.statusCode()}")
        }
        return try {
            json.decodeFromString<DeviceCodeResponse>(response.body())
        } catch (e: Exception) {
            throw DeviceGrantException("device-authorization answer was not parseable: ${e.message}")
        }
    }

    /**
     * Polls the token endpoint until the user approves, the device code expires, or the user
     * denies. Honors the OAuth2 device-grant signals: {@code authorization_pending} keeps polling,
     * {@code slow_down} widens the interval, anything else is terminal.
     *
     * @param device the device-code grant from [requestDeviceCode]
     * @param sleep how to wait between polls (injected so tests run without real delays); seconds
     * @param nowMillis the clock (injected for tests); defaults to {@link System#currentTimeMillis}
     * @param dpopKey the key to bind the issued tokens to, or {@code null} for a plain bearer grant
     * @return the token answer once granted
     * @throws DeviceGrantException on denial, expiry, or an unexpected error
     */
    fun pollForToken(
        device: DeviceCodeResponse,
        sleep: (Long) -> Unit = { seconds -> Thread.sleep(seconds * 1000L) },
        nowMillis: () -> Long = { System.currentTimeMillis() },
        dpopKey: DpopKey? = null,
    ): TokenResponse {
        var intervalSeconds = device.interval.coerceAtLeast(1)
        val deadline = nowMillis() + device.expiresIn * 1000L
        while (nowMillis() < deadline) {
            sleep(intervalSeconds)
            val form =
                encodeForm(
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code",
                    "device_code" to device.deviceCode,
                    "client_id" to clientId,
                )
            val response =
                try {
                    postToToken(form, dpopKey)
                } catch (e: Exception) {
                    throw DeviceGrantException("token request failed: ${e.message}")
                }
            if (response.statusCode() == 200) {
                return try {
                    json.decodeFromString<TokenResponse>(response.body())
                } catch (e: Exception) {
                    throw DeviceGrantException("token answer was not parseable: ${e.message}")
                }
            }
            when (val error = parseError(response.body())) {
                "authorization_pending" -> {} // keep waiting
                "slow_down" -> intervalSeconds += 5
                "expired_token" ->
                    throw DeviceGrantException("the approval window expired — please try again")
                "access_denied" -> throw DeviceGrantException("the request was denied")
                else ->
                    throw DeviceGrantException(describeError(response), error, reportableClockOffset())
            }
        }
        throw DeviceGrantException("the approval window expired — please try again")
    }

    /**
     * Exchanges a stored refresh token for a fresh access token (and, with rotation enabled on the
     * client, a new refresh token) — the "remember me" silent path (#648). On any failure (expired,
     * revoked, reuse-detected) it throws so the caller falls back to an interactive device grant.
     *
     * <p>A DPoP-bound refresh token can only be redeemed with a proof from the **same** key, which is
     * why [StoredCredential] keeps the two together; pass the key that came out of the vault.
     *
     * @param refreshToken the persisted refresh token
     * @param dpopKey the key the stored token is bound to, or {@code null} for an unbound token
     * @return the new token answer (its {@code refreshToken} is the rotated one to re-persist)
     * @throws DeviceGrantException when the refresh is rejected or the answer is unparseable
     */
    fun refreshAccessToken(refreshToken: String, dpopKey: DpopKey? = null): TokenResponse {
        val form =
            encodeForm(
                "grant_type" to "refresh_token",
                "refresh_token" to refreshToken,
                "client_id" to clientId,
            )
        val response =
            try {
                postToToken(form, dpopKey)
            } catch (e: Exception) {
                throw DeviceGrantException("token refresh failed: ${e.message}")
            }
        if (response.statusCode() != 200) {
            // The error code rides along so the caller can tell "this refresh token is dead" from
            // "that proof was not accepted" — only the former may cost the user their stored login.
            throw DeviceGrantException(
                "token refresh rejected — ${describeError(response)}",
                parseError(response.body()),
                reportableClockOffset(),
            )
        }
        return try {
            json.decodeFromString<TokenResponse>(response.body())
        } catch (e: Exception) {
            throw DeviceGrantException("token-refresh answer was not parseable: ${e.message}")
        }
    }

    /**
     * Revokes a refresh token at Keycloak's RFC 7009 revocation endpoint — the server side of "Vom
     * Basetool trennen" (#648). Best-effort: a failure is swallowed because the local credential is
     * deleted regardless, and a stale server-side token expires on its own.
     *
     * <p>The proof is sent for completeness: Keycloak's revocation endpoint ignores it today, but the
     * opt-in {@code dpop-bind-enforcer} client policy covers {@code TOKEN_REVOKE} and would then
     * require one. An ignored header costs nothing; a missing one would silently strand the token
     * server-side the day that policy is switched on.
     *
     * @param refreshToken the refresh token to revoke
     * @param dpopKey the key the token is bound to, or {@code null} for an unbound token
     */
    fun revoke(refreshToken: String, dpopKey: DpopKey? = null) {
        val form =
            encodeForm(
                "token" to refreshToken,
                "token_type_hint" to "refresh_token",
                "client_id" to clientId,
            )
        try {
            postForm("$issuer/protocol/openid-connect/revoke", form, dpopKey)
        } catch (_: Exception) {
            // Best effort — local deletion is what actually disconnects the user.
        }
    }

    /** [postForm] against the token endpoint — the one URL all three grant calls share. */
    private fun postToToken(form: String, dpopKey: DpopKey?): HttpResponse<String> =
        postForm(tokenEndpoint, form, dpopKey)

    /**
     * POSTs a form-encoded body, carrying a fresh DPoP proof when [dpopKey] is given.
     *
     * <p>Exactly **one** retry, and only for the one thing a first proof can legitimately get wrong
     * on its own: an `iat` written before this machine's clock had ever been measured against the
     * server's. That is self-correcting on the second attempt and cannot repeat, so re-posting a
     * device-code or refresh-token grant can never loop. A nonce challenge is *not* retried — see
     * [DpopNonce].
     */
    private fun postForm(endpoint: String, form: String, dpopKey: DpopKey?): HttpResponse<String> {
        val offsetBefore = clock.offsetSeconds()
        val response = postOnce(endpoint, form, dpopKey)
        if (dpopKey == null) return response
        val clockJustCorrected =
            (response.statusCode() == 400 || response.statusCode() == 401) &&
                abs(clock.offsetSeconds() - offsetBefore) >= ServerClock.MATERIAL_SECONDS
        return if (clockJustCorrected) postOnce(endpoint, form, dpopKey) else response
    }

    private fun postOnce(endpoint: String, form: String, dpopKey: DpopKey?): HttpResponse<String> {
        val builder =
            HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form))
        if (dpopKey != null) {
            builder.header(
                "DPoP",
                dpopKey.proof(
                    htm = "POST",
                    htu = DpopKey.htu(URI.create(endpoint)),
                    issuedAt = clock.now(),
                ),
            )
        }
        val sentAt = Instant.now()
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        observeClock(response, sentAt)
        if (dpopKey != null && parseError(response.body()) == DpopNonce.USE_DPOP_NONCE) {
            DpopNonce.reportChallenge(endpoint)
        }
        return response
    }

    private fun observeClock(response: HttpResponse<*>, sentAt: Instant) {
        clock.observe(response.headers().firstValue("Date").orElse(null), sentAt)
    }

    /**
     * The measured clock deviation, but only when it is big enough to be the plausible cause of a
     * rejected proof. Reported only from the terminal failure paths — i.e. after the corrected retry
     * in [postForm] already had its chance — so the user is never told to fix a clock that was in
     * fact fixed automatically.
     */
    private fun reportableClockOffset(): Long =
        clock.offsetSeconds().takeIf { abs(it) >= ServerClock.REPORTABLE_SECONDS } ?: 0L

    /**
     * A safe, *diagnosable* one-liner for a rejected token request: the OAuth2 {@code error} and
     * {@code error_description} the server sent, falling back to the status alone.
     *
     * <p>Worth the extra words because of DPoP. Keycloak validates the proof **before** it looks at
     * the grant, and it reports every proof failure as a generic {@code invalid_request} — so a
     * clock more than ~15s fast (its window is {@code iat} within −25s…+15s) or any proof defect
     * would otherwise surface as a bare "HTTP 400" with nothing to act on. The server's
     * {@code error_description} names it ("DPoP proof is not active", "DPoP proof is missing").
     * Neither field ever carries a token or key — they are server-authored diagnostics.
     */
    private fun describeError(response: HttpResponse<String>): String {
        val parsed =
            try {
                json.decodeFromString<TokenErrorResponse>(response.body())
            } catch (_: Exception) {
                null
            }
        val error = parsed?.error?.ifBlank { null }
        val description = parsed?.errorDescription?.ifBlank { null }
        val detail = listOfNotNull(error, description).joinToString(": ")
        return if (detail.isBlank()) {
            "authentication failed (HTTP ${response.statusCode()})"
        } else {
            "$detail (HTTP ${response.statusCode()})"
        }
    }

    private fun parseError(body: String): String =
        try {
            json.decodeFromString<TokenErrorResponse>(body).error
        } catch (_: Exception) {
            ""
        }

    /** OAuth2 form bodies are {@code x-www-form-urlencoded}; encode each key and value. */
    private fun encodeForm(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, StandardCharsets.UTF_8)}=${URLEncoder.encode(v, StandardCharsets.UTF_8)}"
        }

    companion object {
        /** Prod Keycloak realm issuer (hardcoded per #645; only the ingest base URL is config). */
        const val PROD_ISSUER = "https://keycloak.profit-base.online/realms/iri"

        /**
         * The public device-grant client provisioned in Keycloak (#641).
         *
         * <p>**Contractual — do not change casually.** The ingest gateway matches the token's
         * {@code azp} against a server-side allowlist (`REQ-INGEST-011`,
         * `APP_INGEST_CLIENT_IDENTITY_ALLOWED_CLIENT_IDS`); a value that is not on it is refused
         * with {@code 403 CLIENT_NOT_ALLOWED} for every user. Changing it is a coordinated,
         * two-sided rotation: add the new id to the allowlist first, ship, then drop the old one
         * once the per-`client_id` metric shows no traffic on it.
         */
        const val CLIENT_ID = "basetool-sc-extractor"

        /** The client scope that stamps {@code aud=basetool-backend} on the token (#641). */
        const val INGEST_SCOPE = "extractor-ingest"

        private fun defaultHttp(): HttpClient =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    }
}
