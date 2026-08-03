package com.basetool.bpextractor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.basetool.bpextractor.net.IngestProblem
import com.basetool.bpextractor.net.auth.DpopNonce
import com.basetool.bpextractor.ui.i18n.LocalStrings
import kotlinx.coroutines.CoroutineScope

/**
 * The "An Basetool senden" overlay (epic krt-profit/basetool#639): a KRT-styled scrim modal — never a
 * native dialog — that walks the user through consent → browser approval (device grant) → sending →
 * result, driven by [SendController.state]. Hidden when the state is [SendState.Idle].
 *
 * @param controller the send state machine + actions
 * @param appScope the UI scope the flow runs on
 * @param onSaveLocally optional "save the export as JSON instead" fallback shown on the error
 *     screen (the workflow's local-write action); null → no fallback button
 */
@Composable
fun SendOverlay(
    controller: SendController,
    appScope: CoroutineScope,
    onSaveLocally: (() -> Unit)? = null,
) {
    val strings = LocalStrings.current
    val state = controller.state
    if (state is SendState.Idle) return

    val title =
        when (state) {
            is SendState.Authenticating -> strings.send.authTitle
            is SendState.Done -> strings.send.resultTitle
            else -> strings.send.consentTitle
        }

    Box(
        modifier =
            Modifier.fillMaxSize()
                .background(Krt.Black.copy(alpha = 0.8f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { controller.dismiss() },
                )
                .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        val swallow = remember { MutableInteractionSource() }
        Column(
            modifier =
                Modifier.widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .drawBehind {
                        val grow = 28.dp.toPx()
                        drawRect(
                            brush =
                                Brush.radialGradient(
                                    colors =
                                        listOf(Krt.Orange.copy(alpha = 0.18f), Color.Transparent),
                                    center = center,
                                    radius = size.maxDimension / 2f + grow,
                                ),
                            topLeft = Offset(-grow, -grow),
                            size = Size(size.width + 2f * grow, size.height + 2f * grow),
                        )
                    }
                    .background(Krt.Black.copy(alpha = 0.97f))
                    .border(1.dp, Krt.Orange)
                    .clickable(interactionSource = swallow, indication = null, onClick = {}),
        ) {
            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .background(Krt.Gray4)
                        .drawBehind {
                            drawLine(
                                Krt.Orange,
                                Offset(0f, size.height),
                                Offset(size.width, size.height),
                                2.dp.toPx(),
                            )
                        }
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    title.uppercase(),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Krt.Orange,
                )
            }

            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (state) {
                    is SendState.Consent ->
                        Text(
                            strings.send.consentBody,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Krt.Gray1,
                        )
                    is SendState.Authenticating -> {
                        Text(
                            strings.send.authBody,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Krt.Gray1,
                        )
                        Text(
                            strings.send.authCode(state.userCode),
                            style = MaterialTheme.typography.headlineSmall,
                            color = Krt.White,
                        )
                        Text(
                            strings.send.waiting,
                            style = MaterialTheme.typography.bodySmall,
                            color = Krt.Gray2,
                        )
                    }
                    is SendState.Sending ->
                        Text(
                            strings.send.inProgress,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Krt.Gray1,
                        )
                    is SendState.Done ->
                        Text(
                            strings.send.resultBody,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Krt.Gray1,
                        )
                    is SendState.Error ->
                        Text(
                            // Three failures are worth naming instead of echoing a server sentence
                            // that reads like something a retry could fix: the gateway refusing this
                            // client software outright (REQ-INGEST-011), a server demanding a nonce
                            // handshake this build does not speak, and a system clock far enough off
                            // that no proof of ours can land inside the server's window.
                            when {
                                state.code == IngestProblem.CLIENT_NOT_ALLOWED ->
                                    strings.send.errorClientNotAllowed(state.message)
                                state.code == DpopNonce.CODE ->
                                    strings.send.errorDpopNonceRequired(state.message)
                                state.clockOffsetSeconds != 0L ->
                                    strings.send.errorClockSkew(state.clockOffsetSeconds, state.message)
                                else -> strings.send.error(state.message)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = Krt.Gray1,
                        )
                    is SendState.Idle -> {}
                }
            }

            Row(
                modifier =
                    Modifier.fillMaxWidth()
                        .background(Krt.Gray4.copy(alpha = 0.55f))
                        .drawBehind {
                            drawLine(Krt.Gray3, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
                        }
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                when (state) {
                    is SendState.Consent -> {
                        GhostButton(strings.cancel, onClick = { controller.dismiss() })
                        Spacer(Modifier.weight(1f))
                        CtaButton(
                            strings.send.consentConfirm,
                            onClick = { controller.confirmConsent(appScope) },
                        )
                    }
                    is SendState.Authenticating -> {
                        GhostButton(
                            strings.send.authOpenBrowser,
                            onClick = { controller.reopenBrowser() },
                        )
                        Spacer(Modifier.weight(1f))
                        GhostButton(strings.cancel, onClick = { controller.dismiss() })
                    }
                    is SendState.Sending -> Spacer(Modifier.height(1.dp))
                    is SendState.Done -> {
                        GhostButton(strings.close, onClick = { controller.dismiss() })
                        Spacer(Modifier.weight(1f))
                        CtaButton(strings.send.openInBasetool, onClick = { controller.openResult() })
                    }
                    is SendState.Error -> {
                        // Send failed → offer the local-write fallback (if the caller supplied one).
                        if (onSaveLocally != null) {
                            GhostButton(
                                strings.send.saveLocally,
                                onClick = {
                                    controller.dismiss()
                                    onSaveLocally()
                                },
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        GhostButton(strings.close, onClick = { controller.dismiss() })
                    }
                    is SendState.Idle -> {}
                }
            }
        }
    }
}
