package com.basetool.bpextractor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.basetool.bpextractor.net.auth.CredentialStore
import com.basetool.bpextractor.net.auth.DeviceGrantClient
import com.basetool.bpextractor.net.auth.DpopKey
import com.basetool.bpextractor.net.auth.WinCredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives the "remember me" account surface (epic krt-profit/basetool#639, sub-issue #648): reflects
 * whether a basetool refresh token is stored and runs the "Vom Basetool trennen" disconnect —
 * revoke the token at Keycloak (best-effort), then delete it from Windows Credential Manager. A
 * Compose state holder; the revoke/delete runs off the UI thread. Collaborators are injected so the
 * surface is exercisable without a real Keycloak or credential vault.
 */
class AccountController(
    private val credentialStore: CredentialStore = WinCredentialStore(),
    private val deviceGrant: DeviceGrantClient = DeviceGrantClient(),
) {

    /** Whether a stored token exists (the connected/disconnected indicator). */
    var connected by mutableStateOf(credentialStore.exists())
        private set

    /** Whether the disconnect-confirmation modal is showing. */
    var confirming by mutableStateOf(false)
        private set

    /** Whether a disconnect (revoke + delete) is in flight. */
    var working by mutableStateOf(false)
        private set

    /** Re-reads the store so the bar reflects a token a send persisted since composition. */
    fun refresh() {
        connected = credentialStore.exists()
    }

    /** Opens the disconnect confirmation (no native dialog — a KRT scrim modal). */
    fun requestDisconnect() {
        confirming = true
    }

    /** Dismisses the disconnect confirmation without disconnecting. */
    fun cancelDisconnect() {
        confirming = false
    }

    /**
     * Confirms the disconnect: revokes the stored refresh token at Keycloak (best-effort) and
     * deletes it locally, then updates [connected]. Runs the I/O off the UI thread.
     *
     * @param scope the UI coroutine scope to run the disconnect on
     */
    fun confirmDisconnect(scope: CoroutineScope) {
        confirming = false
        working = true
        scope.launch {
            withContext(Dispatchers.IO) {
                // A DPoP-bound refresh token is only revocable with a proof from the key it was
                // issued to, which is why the two are stored as one record (REQ-INGEST-012).
                credentialStore.loadCredential()?.let { stored ->
                    deviceGrant.revoke(stored.refreshToken, stored.dpopKey?.let(DpopKey::fromEncoded))
                }
                credentialStore.clear()
            }
            connected = credentialStore.exists()
            working = false
        }
    }
}
