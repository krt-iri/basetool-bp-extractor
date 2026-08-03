package com.basetool.bpextractor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.basetool.bpextractor.config.AppConfigStore
import com.basetool.bpextractor.net.BasetoolIngestClient
import com.basetool.bpextractor.net.IngestException
import com.basetool.bpextractor.net.IngestProblem
import com.basetool.bpextractor.net.auth.CredentialStore
import com.basetool.bpextractor.net.auth.DeviceGrantClient
import com.basetool.bpextractor.net.auth.DeviceGrantException
import com.basetool.bpextractor.net.auth.DpopKey
import com.basetool.bpextractor.net.auth.DpopNonce
import com.basetool.bpextractor.net.auth.StoredCredential
import com.basetool.bpextractor.net.auth.TokenResponse
import com.basetool.bpextractor.net.auth.WinCredentialStore
import java.awt.Desktop
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The one-click-send overlay state machine (epic krt-profit/basetool#639). */
sealed interface SendState {
    /** No send in progress; the overlay is hidden. */
    data object Idle : SendState

    /** First-time consent before any data leaves the machine. */
    data object Consent : SendState

    /** Browser opened; waiting for the user to approve the shown code. */
    data class Authenticating(val userCode: String, val browserUrl: String) : SendState

    /** Token obtained; uploading the export to the gateway. */
    data object Sending : SendState

    /** Done; [frontendUrl] opens the pre-filled basetool page. */
    data class Done(val frontendUrl: String) : SendState

    /**
     * A safe-to-show failure message (auth / network / rejected).
     *
     * @param message the already-localized detail to put in front of the user
     * @param code a machine-readable reason the overlay can explain in plain language rather than
     *   only echoing the server's sentence: the gateway's stable RFC 7807 {@code code}
     *   ([IngestProblem.CLIENT_NOT_ALLOWED] above all), or a client-synthesized one
     *   ([DpopNonce.CODE]). Empty for ordinary auth/network failures.
     * @param clockOffsetSeconds this machine's *measured* deviation from the server's clock, set
     *   only when it is large enough to be the plausible cause and correcting for it already failed
     *   to help — see [DeviceGrantException.clockOffsetSeconds]. Zero otherwise.
     */
    data class Error(
        val message: String,
        val code: String = "",
        val clockOffsetSeconds: Long = 0,
    ) : SendState
}

/** Which ingest endpoint a send targets — the refinery extract or the blueprint export. */
enum class SendKind {
    /** {@code POST /v1/refinery-extract} — a {@code RefineryExtract} document. */
    REFINERY,

    /** {@code POST /v1/blueprint-preview} — a {@code BlueprintExport} document. */
    BLUEPRINT,
}

/**
 * Drives the "An Basetool senden" flow for a workflow export (refinery extract or blueprint):
 * one-time consent → Keycloak device grant (show the user code, open the browser, poll) → upload to
 * the ingest gateway → open the pre-filled basetool page. A Compose state holder ([state]); the
 * heavy work runs on
 * [Dispatchers.IO]. The collaborators are injected so the net layer stays pure and the controller
 * is exercisable without a real Keycloak/gateway.
 */
class SendController(
    private val configStore: AppConfigStore = AppConfigStore(),
    private val deviceGrant: DeviceGrantClient = DeviceGrantClient(),
    private val credentialStore: CredentialStore = WinCredentialStore(),
    private val ingestClientFor: (String) -> BasetoolIngestClient = { BasetoolIngestClient(it) },
    private val browse: (String) -> Unit = { url ->
        runCatching {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
            }
        }
    },
) {

    var state by mutableStateOf<SendState>(SendState.Idle)
        private set

    private var pendingJson: String = ""
    private var pendingLang: String = "de"
    private var pendingKind: SendKind = SendKind.REFINERY

    /** The ephemeral DPoP key for this process — see [sessionKey]. Never logged, never exported. */
    private var sessionKey: DpopKey? = null

    /**
     * Entry point from a workflow's export/summary step: stashes the payload + which ingest
     * endpoint it targets, then shows the consent overlay on the first ever send or starts the flow
     * directly once consent was given.
     *
     * @param scope the UI coroutine scope to run the flow on
     * @param kind which ingest endpoint to send to (refinery extract vs blueprint export)
     * @param exportJson the serialized export to send (RefineryExtract or BlueprintExport)
     * @param lang the UI locale tag to relay as Accept-Language
     */
    fun request(scope: CoroutineScope, kind: SendKind, exportJson: String, lang: String) {
        pendingKind = kind
        pendingJson = exportJson
        pendingLang = lang
        if (configStore.load().consentGiven) run(scope) else state = SendState.Consent
    }

    /** Records consent (persisted, non-secret) and starts the flow. */
    fun confirmConsent(scope: CoroutineScope) {
        val config = configStore.load()
        configStore.save(config.copy(consentGiven = true))
        run(scope)
    }

    /** Hides the overlay (cancel / close / dismiss an error). */
    fun dismiss() {
        state = SendState.Idle
    }

    /** Re-opens the verification URL if the browser did not come up the first time. */
    fun reopenBrowser() {
        (state as? SendState.Authenticating)?.let { browse(it.browserUrl) }
    }

    /** Opens the pre-filled basetool page after a successful send. */
    fun openResult() {
        (state as? SendState.Done)?.let { browse(it.frontendUrl) }
    }

    private fun run(scope: CoroutineScope) {
        scope.launch {
            try {
                val baseUrl = withContext(Dispatchers.IO) { configStore.load().ingestBaseUrl }
                val grant = withContext(Dispatchers.IO) { obtainToken() }
                // Persist (the possibly rotated) refresh token for the next silent send (#648) —
                // together with the DPoP key it is bound to, because a sender-constrained token
                // cannot be redeemed without it (REQ-INGEST-012).
                withContext(Dispatchers.IO) {
                    if (grant.token.refreshToken.isNotBlank()) {
                        credentialStore.saveCredential(
                            StoredCredential(grant.token.refreshToken, grant.key.encoded()),
                        )
                    }
                }
                state = SendState.Sending
                // Present the token under the DPoP scheme only if the server really bound it;
                // otherwise it stays a plain bearer, exactly as every build so far.
                val boundKey = grant.key.takeIf { grant.token.isDpopBound() }
                val response =
                    withContext(Dispatchers.IO) {
                        val client = ingestClientFor(baseUrl)
                        when (pendingKind) {
                            SendKind.REFINERY ->
                                client.sendRefinery(grant.token.accessToken, pendingJson, pendingLang, boundKey)
                            SendKind.BLUEPRINT ->
                                client.sendBlueprint(grant.token.accessToken, pendingJson, pendingLang, boundKey)
                        }
                    }
                state = SendState.Done(response.frontendUrl)
            } catch (e: DeviceGrantException) {
                // A nonce challenge and a clock that is genuinely off both get named for what they
                // are; everything else stays the plain failure line.
                state =
                    SendState.Error(
                        e.message ?: "authentication failed",
                        if (e.oauthError == DpopNonce.USE_DPOP_NONCE) DpopNonce.CODE else "",
                        e.clockOffsetSeconds,
                    )
            } catch (e: IngestException) {
                // Carries the gateway's stable code; a CLIENT_NOT_ALLOWED lands here exactly once —
                // there is no retry path, and the overlay explains it rather than inviting one.
                state = SendState.Error(e.message ?: "send failed", e.code)
            } catch (e: Exception) {
                state = SendState.Error(e.message ?: "send failed")
            }
        }
    }

    /** A token together with the DPoP key its proofs are (and its refresh token stays) signed by. */
    private data class Grant(val token: TokenResponse, val key: DpopKey)

    /**
     * Obtains an access token: the "remember me" silent refresh first (no browser, no overlay
     * step), falling back to an interactive device grant when there is no stored credential or the
     * refresh is rejected (expired / revoked / reuse-detected). Runs on the calling IO context.
     *
     * <p>The DPoP key comes from the vault when one was persisted — a bound refresh token is only
     * redeemable with the key it was issued to — and is otherwise the session key, which also covers
     * the upgrade case (a bare refresh token written by a pre-DPoP build) and the machine where the
     * vault is unavailable and nothing survives the process anyway.
     *
     * @return the token answer plus its key; the token's {@code refreshToken} is the one to persist
     * @throws DeviceGrantException when the interactive grant ultimately fails
     */
    private fun obtainToken(): Grant {
        credentialStore.loadCredential()?.let { stored ->
            val key = stored.dpopKey?.let { DpopKey.fromEncoded(it) } ?: sessionKey()
            try {
                return Grant(deviceGrant.refreshAccessToken(stored.refreshToken, key), key)
            } catch (e: DeviceGrantException) {
                // Delete the credential ONLY when the server said the grant itself is dead. Since
                // DPoP the same exception also covers proof rejections — Keycloak checks the proof
                // before the grant and calls every proof defect `invalid_request`, which a clock
                // more than 15s fast is enough to trigger — and those say nothing about the refresh
                // token. Clearing on one would log the user out over an unrelated fault, and the
                // interactive grant it fell back to would fail on the very same proof anyway.
                if (e.oauthError !in DeviceGrantException.TOKEN_REJECTED) throw e
                credentialStore.clear() // the stored credential is dead — drop it and log in afresh
            }
        }
        val key = sessionKey()
        val device = deviceGrant.requestDeviceCode()
        state = SendState.Authenticating(device.userCode, device.browserUrl())
        browse(device.browserUrl())
        return Grant(deviceGrant.pollForToken(device, dpopKey = key), key)
    }

    /**
     * The key used when nothing is persisted: generated once and reused for the rest of the process,
     * so a refresh issued during this session can still be redeemed later in it. Reached only from
     * the single in-flight send flow.
     */
    private fun sessionKey(): DpopKey = sessionKey ?: DpopKey.generate().also { sessionKey = it }
}
