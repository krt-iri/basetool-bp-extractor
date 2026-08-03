package com.basetool.bpextractor.ui.refinery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.basetool.bpextractor.refinery.RefineryPipeline
import com.basetool.bpextractor.refinery.model.ordersMissingGoods
import com.basetool.bpextractor.refinery.model.ordersMissingSourceImages
import com.basetool.bpextractor.refinery.model.rowsMissingQuantity
import com.basetool.bpextractor.ui.CtaButton
import com.basetool.bpextractor.ui.GhostButton
import com.basetool.bpextractor.ui.Krt
import com.basetool.bpextractor.ui.PickerMode
import com.basetool.bpextractor.ui.PickerRequest
import com.basetool.bpextractor.ui.SendController
import com.basetool.bpextractor.ui.SendKind
import com.basetool.bpextractor.ui.SendOverlay
import com.basetool.bpextractor.ui.StepScaffold
import com.basetool.bpextractor.ui.hudBox
import com.basetool.bpextractor.ui.i18n.LocalStrings
import com.basetool.bpextractor.ui.i18n.StringsEn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File

/**
 * §5.5 Export & Versand on the [StepScaffold]: the reviewed contract is either **sent straight to
 * the basetool** (the single filled CTA — nothing is written to disk) or **saved as JSON locally**
 * (the alternative). Saving locally is never required for sending; if a send fails, the save-JSON
 * path stays available as a fallback (also offered inside the send overlay). After a local save the
 * green written-path alert + "show in folder" appear. A provenance panel mirrors the contract
 * fields, and the manual-upload card explains how to import a saved JSON by hand.
 *
 * @param state the refinery workflow state (provides the reviewed extract + the local-save write)
 * @param appScope the window-root scope long-running send/write work runs on
 * @param onPicker hosts the KRT save-file picker at the window root (no native dialogs)
 */
@Composable
fun ExportStep(state: RefineryUiState, appScope: CoroutineScope, onPicker: (PickerRequest) -> Unit) {
    val strings = LocalStrings.current
    val extract = state.reviewedExtract() ?: return
    val order = extract.orders.first()
    // Rows whose quantity could not be read (a clipped/too-small capture): the basetool ingest edge
    // rejects a null inputQuantity (@NotNull) with an opaque "Validation failed." 400. Block the send
    // here and point the user back to the review instead of shipping a payload the gateway rejects.
    val missingQtyRows = extract.rowsMissingQuantity()
    // Same reasoning for the two lists the frozen contract made @NotEmpty (ADR-0008 amendment):
    // sending an order without its screenshots or without a single goods row buys the same opaque
    // 400 from the edge, so it is named here instead.
    val ordersWithoutImages = extract.ordersMissingSourceImages()
    val ordersWithoutGoods = extract.ordersMissingGoods()
    val canSend = missingQtyRows.isEmpty() && ordersWithoutImages.isEmpty() && ordersWithoutGoods.isEmpty()
    val exportedFile = state.exportedFile
    val exportError = state.exportError
    val canOpen = remember { Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN) }
    val sendController = remember { SendController() }
    // The export's locale tag for the relayed Accept-Language (derived from the active catalogue).
    val langTag = if (strings === StringsEn) "en" else "de"

    // Save-JSON-locally: open the KRT save picker, then write the reviewed contract to the chosen
    // path. Used both as the explicit alternative to sending and as the fallback after a failed send.
    val saveJsonLocally = {
        onPicker(
            PickerRequest(
                mode = PickerMode.SAVE_FILE,
                title = strings.rfPickerExportTitle,
                confirmLabel = strings.rfPickerExportConfirm,
                initialPath = defaultExportPath(state),
            ) { path -> state.export(appScope, File(path), strings) },
        )
    }

    Box(Modifier.fillMaxSize()) {
        StepScaffold(
            overline = strings.rfStepOverline(5),
            title = strings.rfExportTitle,
            scrollBody = false,
            footer = {
                if (canOpen && exportedFile != null) {
                    GhostButton(
                        strings.bpShowInFolder,
                        onClick = {
                            val parent = exportedFile.absoluteFile.parentFile ?: return@GhostButton
                            appScope.launch {
                                withContext(Dispatchers.IO) { runCatching { Desktop.getDesktop().open(parent) } }
                            }
                        },
                    )
                }
                GhostButton(strings.rfNewExtraction, onClick = { state.newExtraction() })
                // Saving the JSON locally is the alternative to sending — no file is written unless used.
                GhostButton(strings.rfCtaExport, onClick = saveJsonLocally)
                Spacer(Modifier.weight(1f))
                CtaButton(
                    strings.send.button,
                    enabled = canSend,
                    onClick = {
                        // Send the reviewed contract straight from memory — nothing is written to disk.
                        if (!canSend) return@CtaButton
                        val json = runCatching { RefineryPipeline.toJson(extract) }.getOrNull()
                        if (json != null) sendController.request(appScope, SendKind.REFINERY, json, langTag)
                    },
                )
            },
        ) {
            if (missingQtyRows.isNotEmpty()) {
                AlertBox(Krt.Danger) {
                    Text(
                        strings.rfSendBlockedMissingQty(missingQtyRows.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Krt.Gray1,
                    )
                }
                Spacer(Modifier.height(14.dp))
            }
            if (ordersWithoutGoods.isNotEmpty()) {
                AlertBox(Krt.Danger) {
                    Text(
                        strings.rfSendBlockedNoGoods,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Krt.Gray1,
                    )
                }
                Spacer(Modifier.height(14.dp))
            }
            if (ordersWithoutImages.isNotEmpty()) {
                AlertBox(Krt.Danger) {
                    Text(
                        strings.rfSendBlockedNoSourceImages,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Krt.Gray1,
                    )
                }
                Spacer(Modifier.height(14.dp))
            }
            if (exportError != null) {
                Text(exportError, style = MaterialTheme.typography.bodySmall, color = Krt.Danger)
                Spacer(Modifier.height(8.dp))
            }
            if (exportedFile != null) {
                AlertBox(Krt.Success) {
                    Text(
                        strings.rfExportSuccess(exportedFile.absolutePath),
                        style = MaterialTheme.typography.bodyMedium,
                        color = Krt.Gray1,
                    )
                }
                Spacer(Modifier.height(14.dp))
            }

            Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // Manual-upload instructions — the fallback path if you saved the JSON instead of sending.
                Column(
                    modifier = Modifier.weight(1.4f).fillMaxHeight().hudBox().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        strings.rfUploadCardTitle.uppercase(),
                        style = MaterialTheme.typography.headlineSmall,
                        color = Krt.Orange,
                    )
                    strings.rfUploadSteps.forEach { stepText ->
                        Text(stepText, style = MaterialTheme.typography.bodyMedium, color = Krt.Gray1)
                    }
                }
                // Provenance panel mirroring the contract fields.
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight().hudBox(bracket = Krt.Gray3).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        strings.rfProvenanceTitle.uppercase(),
                        style = MaterialTheme.typography.headlineSmall,
                        color = Krt.Orange,
                    )
                    KeyValueRow(strings.rfProvTool, extract.tool)
                    KeyValueRow(strings.rfProvVersion, extract.toolVersion)
                    KeyValueRow(strings.rfProvModel, extract.model)
                    KeyValueRow(strings.rfProvSchema, extract.schemaVersion.toString())
                    KeyValueRow(strings.rfProvPanel, order.panelType)
                    KeyValueRow(strings.rfProvGenerated, extract.generatedAt)
                }
            }
        }

        SendOverlay(sendController, appScope, onSaveLocally = saveJsonLocally)
    }
}
