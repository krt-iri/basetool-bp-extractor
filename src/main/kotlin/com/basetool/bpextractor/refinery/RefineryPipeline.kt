package com.basetool.bpextractor.refinery

import com.basetool.bpextractor.refinery.model.RefineryExtract
import com.basetool.bpextractor.refinery.model.RefineryExtractImage
import kotlinx.serialization.json.Json
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import javax.imageio.ImageIO

/** One input screenshot, already decoded — decoding stays at the edge so tests build images. */
data class PipelineInput(
    val name: String,
    val image: BufferedImage,
    /** Capture instant ([CaptureTime], read at the file edge); null when undeterminable. */
    val capturedAt: Instant? = null,
)

/** The per-image pipeline stages, in run order — the §5.3 stage track of the extraction UI. */
enum class PipelineStage { LOCATE, NORMALIZE, READ, VERIFY }

/** Per-image outcome for the UI: crop mode, quoted state (§5.3 amber warning) and row count. */
data class ImageOutcome(
    val name: String,
    val cropMode: String,
    /** Whether this capture saw the quoted state; null when the read failed entirely. */
    val quoted: Boolean?,
    /** Parsed (non-partial) rows this capture contributed. */
    val rows: Int,
)

/** Progress callbacks for the extraction UI; every method defaults to no-op. */
interface PipelineListener {
    /** [stage] started for image [index] ([name]) — exactly one image is active at a time. */
    fun onStage(index: Int, name: String, stage: PipelineStage) {}

    /** Image [index] finished all stages. */
    fun onImageDone(index: Int, outcome: ImageOutcome) {}

    /** One console line (§5.3 console pane). */
    fun onLog(line: String) {}
}

/** Thrown when the caller's `isActive` gate turned false between images. */
class PipelineCancelledException : RuntimeException("extraction cancelled")

/** The full pipeline result: the contract document + the pre-export review data. */
data class PipelineResult(
    val extract: RefineryExtract,
    val validated: ValidatedOrder,
    val location: String?,
    val outcomes: List<ImageOutcome>,
)

/**
 * Orchestrates one extraction run (master plan §9 Phase 3): for each screenshot — strictly one at
 * a time, matching Ollama's default `OLLAMA_NUM_PARALLEL=1` and the resource-safety throttle —
 * Locate → Normalize → Read, then Stitch across images, Validate (Phase 0 confidence policy) and
 * assemble the frozen [RefineryExtract] contract. The refinery location is read once, from the
 * terminal-header strip of the first full-frame capture (pre-cropped input has none). The model
 * stays pinned (`keep_alive 10m`) across the batch; an explicit [OllamaApi.unload] releases each
 * model as soon as the run is done with it (the primary before the verify pass starts, the last
 * active model at the end — best-effort, a failed unload only logs).
 */
class RefineryPipeline(
    private val ollama: OllamaApi,
    private val model: String,
    private val toolVersion: String,
    /** Injectable clock (the contract's `generatedAt`); tests pin it. */
    private val now: () -> Instant = Instant::now,
    /** Cooperative-cancel gate, checked between images. */
    private val isActive: () -> Boolean = { true },
    /** `0` forces CPU-only inference (below-minimum tier); null = automatic GPU offload. */
    private val numGpu: Int? = null,
    /**
     * Cross-model verify partner ([CrossModelVerify]); null disables the verify pass. All images
     * are read with the primary model first and with the verify model after — ONE model switch
     * per run instead of one per image (the swap, not the read, is what costs time).
     */
    private val verifyModel: String? = null,
    /**
     * Classical-OCR cross-reader provider ([OcrCrossCheck], default the bundled [OcrModels]).
     * Resolved lazily on the first run so a blueprint-only session never loads the models, and
     * injectable as `{ null }` so unit tests stay fast and offline (no ONNX). Null ⇒ no OCR pass.
     */
    private val ocr: () -> PanelOcr? = { OcrModels.get() },
) {

    /**
     * Run the pipeline over [inputs] (all captures of ONE order — 1 folder = 1 order). Throws
     * [PipelineCancelledException] when cancelled and [IllegalStateException] when not a single
     * image produced a readable panel.
     */
    fun extract(inputs: List<PipelineInput>, listener: PipelineListener = object : PipelineListener {}): PipelineResult {
        require(inputs.isNotEmpty()) { "no input images" }
        val reader = PanelReader(ollama, model, numGpu)

        val reads = mutableListOf<ImageRead>()
        val sourceImages = mutableListOf<RefineryExtractImage>()
        val outcomes = mutableListOf<ImageOutcome>()
        // Prepared read images kept for the verify pass (name → base64 PNG); empty when off.
        val verifyQueue = mutableListOf<Pair<String, String>>()
        // Prepared panels kept for the classical-OCR cross-check (name → normalized panel image).
        val panels = mutableMapOf<String, BufferedImage>()
        var location: String? = null
        var locationRead = false

        inputs.forEachIndexed { index, input ->
            if (!isActive()) throw PipelineCancelledException()
            val name = input.name

            listener.onStage(index, name, PipelineStage.LOCATE)
            val precropped = Locate.isPrecropped(input.image.width, input.image.height)
            val located = if (precropped) null else Locate.locatePanelOrNull(input.image)
            val box = if (precropped) null else located ?: Locate.fallbackPanel(input.image)
            listener.onLog(
                when {
                    precropped -> "· Locate — $name: pre-cropped panel, skipping"
                    located != null -> "· Locate — $name: panel at ${box!!.x},${box.y} ${box.width}×${box.height}"
                    else ->
                        "⚠ Locate — $name: colour anchors found nothing, using the fixed fallback " +
                            "geometry ${box!!.x},${box.y} ${box.width}×${box.height} — verify the crop"
                },
            )

            listener.onStage(index, name, PipelineStage.NORMALIZE)
            var prepared = Locate.prepare(input.image, box)
            listener.onLog("· Normalize — $name: ${prepared.readImage.width}×${prepared.readImage.height} (${prepared.cropMode})")

            listener.onStage(index, name, PipelineStage.READ)
            // The location is read from the first capture that has a header strip. Model
            // lifetime is NOT managed per call: every read pins for 10m and the explicit
            // unload below releases as soon as the run is done with a model.
            var panelB64 = toBase64Png(prepared.readImage)
            var panel = reader.readPanel(panelB64)
            // Empty-quantities rescue: the per-panel crop read material names but NO quantity in a
            // single cell, so the number columns were clipped off the crop — the located box was too
            // narrow (Auftrag 21: QTY/YIELD/REFINE cut off the right edge), landed on the wrong band
            // (Auftrag 22: the STATION-PROFILE sidebar, no materials table), or the orange hull drove
            // the per-panel search off on a 32:9 frame (Auftrag 16). Retry ONCE with the whole
            // terminal extent, which the model reads the SETUP panel out of reliably — the same
            // full-terminal layout the portrait terminal-area captures already work from (Auftrag
            // 10/12/19). This was ultrawide-only originally; it fires on ANY frame now because the
            // same "located box clips the number columns" failure hits small sub-HD portrait captures
            // too. The per-panel crop stays PRIMARY where it works (its tighter, higher-resolution
            // crop reads digits better), so the rescue fires only on an otherwise empty-handed read
            // and only replaces it when the wider re-read actually recovered quantities.
            if (box != null && panel.lacksQuantities()) {
                val extentBox = Locate.terminalExtentBox(input.image)
                if (extentBox != null && extentBox != box) {
                    val rescued = Locate.prepare(input.image, extentBox)
                    val rescuedB64 = toBase64Png(rescued.readImage)
                    val rescuedPanel = reader.readPanel(rescuedB64)
                    if (!rescuedPanel.lacksQuantities()) {
                        listener.onLog(
                            "· Locate — $name: per-panel crop read no numbers (clipped), " +
                                "rescued via the terminal extent ${extentBox.width}×${extentBox.height}",
                        )
                        prepared = rescued
                        panelB64 = rescuedB64
                        panel = rescuedPanel
                    }
                }
            }
            panels[name] = prepared.readImage
            if (verifyModel != null) {
                verifyQueue += name to panelB64
            }
            if (!locationRead && prepared.locationImage != null) {
                location = reader.readLocation(toBase64Png(prepared.locationImage))
                locationRead = true
                listener.onLog("· Location — $name: ${location ?: "not readable"}")
            }

            sourceImages += RefineryExtractImage(
                name = name,
                width = input.image.width,
                height = input.image.height,
                cropMode = prepared.cropMode,
                capturedAt = input.capturedAt?.truncatedTo(ChronoUnit.SECONDS)?.toString(),
            )
            val outcome = if (panel == null) {
                listener.onLog("⚠ Read — $name: no recognizable panel layout in the answer")
                ImageOutcome(name, prepared.cropMode, quoted = null, rows = 0)
            } else {
                reads += ImageRead(name, panel)
                val rows = panel.rows.count { !it.partial }
                listener.onLog("✓ Read — $name: $rows row(s)${if (!panel.quoted) ", un-quoted (GET-QUOTE state)" else ""}")
                ImageOutcome(name, prepared.cropMode, quoted = panel.quoted, rows = rows)
            }
            outcomes += outcome
            listener.onImageDone(index, outcome)
        }

        check(reads.isNotEmpty()) { "none of the ${inputs.size} image(s) produced a readable panel" }

        var stitched = Stitcher.stitch(reads)
        val crossCheck = if (verifyModel != null) {
            unload(model, listener) // free the VRAM before the partner loads
            runVerifyPass(verifyQueue, stitched, listener)
        } else {
            null
        }
        unload(verifyModel ?: model, listener)
        if (crossCheck != null) {
            stitched = stitched.copy(rows = crossCheck.rows)
        }
        // Classical-OCR cross-check (a decorrelated third reader): a QUALITY/YIELD vote per row
        // (matched by the QTY anchor), an OFF-row QTY review flag, and a TO_REFINE anchor cross-check.
        // Runs only when the bundled models are available, on the FINAL stitched rows. Best-effort —
        // any failure degrades to "no OCR" and the VLM result stands.
        val ocrResult = ocr()?.let { engine ->
            runCatching { OcrCrossCheck.read(stitched.rows, panels, engine, PanelValues.toQuantity(stitched.toRefine)) }
                .onFailure { listener.onLog("⚠ OCR — cross-check failed (${it.message}), keeping the VLM read") }
                .getOrNull()
        }
        if (ocrResult != null) {
            listener.onLog("· OCR — cross-checked ${ocrResult.readings.size} row(s) against the classical reader")
        }
        val validated = Validation.validate(
            stitched,
            crossCheck,
            ocr = ocrResult?.readings ?: emptyMap(),
            toRefineContested = ocrResult?.toRefineContested ?: false,
            qtyOcrContested = ocrResult?.qtyContested ?: emptySet(),
        )
        listener.onLog("✓ Stitch — ${validated.goods.size} row(s) from ${reads.size} read(s)")
        if (validated.warnings.isNotEmpty()) {
            listener.onLog("⚠ Validation — ${validated.warnings.joinToString(", ")}")
        }

        val extract = RefineryExtract(
            tool = TOOL,
            toolVersion = toolVersion,
            model = model,
            generatedAt = now().truncatedTo(ChronoUnit.SECONDS).toString(),
            orders = listOf(validated.toContractOrder(sourceImages, location)),
        )
        return PipelineResult(extract, validated, location, outcomes)
    }

    /** Best-effort model release — a failed unload must never fail the run. */
    private fun unload(model: String, listener: PipelineListener) {
        runCatching { ollama.unload(model) }
            .onFailure { listener.onLog("⚠ Unload — $model: ${it.message} (model stays on its keep_alive TTL)") }
    }

    /**
     * The cross-model verify pass: re-read every prepared panel with [verifyModel] (surfaced as
     * the per-image VERIFY stage), stitch, and merge against the primary [stitched] result
     * ([CrossModelVerify]). Failures are never fatal: the pass degrades to "no verify" with a
     * console line, the primary result stands.
     */
    private fun runVerifyPass(
        queue: List<Pair<String, String>>,
        stitched: StitchResult,
        listener: PipelineListener,
    ): CrossModelVerify.Outcome? {
        val verifier = PanelReader(ollama, verifyModel!!, numGpu)
        return try {
            val secondReads = mutableListOf<ImageRead>()
            queue.forEachIndexed { i, (name, b64) ->
                if (!isActive()) throw PipelineCancelledException()
                listener.onStage(i, name, PipelineStage.VERIFY)
                val panel = verifier.readPanel(b64)
                if (panel == null) {
                    listener.onLog("⚠ Verify — $name: no recognizable panel layout in the answer")
                } else {
                    secondReads += ImageRead(name, panel)
                }
            }
            if (secondReads.size < queue.size) {
                listener.onLog("⚠ Verify — incomplete second read, skipping the cross-check")
                return null
            }
            val outcome = CrossModelVerify.merge(stitched, Stitcher.stitch(secondReads))
            listener.onLog(
                if (!outcome.comparable) {
                    "⚠ Verify — $verifyModel reads a different row set, cross-check not comparable"
                } else {
                    "✓ Verify — $verifyModel: ${outcome.corrected.size} corrected, ${outcome.contested.size} contested row(s)"
                },
            )
            outcome
        } catch (e: PipelineCancelledException) {
            throw e
        } catch (e: Exception) {
            listener.onLog("⚠ Verify — pass failed (${e.message}), keeping the primary read")
            null
        }
    }

    /** Encode a prepared image as base64 PNG for the Ollama `images` field. */
    private fun toBase64Png(img: BufferedImage): String {
        val buffer = ByteArrayOutputStream()
        ImageIO.write(img, "png", buffer)
        return Base64.getEncoder().encodeToString(buffer.toByteArray())
    }

    companion object {
        /** Contract `tool` field (provenance). */
        const val TOOL = "basetool-sc-extractor"

        private val JSON = Json {
            prettyPrint = true
            encodeDefaults = true
        }

        /** Serialize the contract document to the exact JSON [writeJson] writes, without disk I/O. */
        fun toJson(extract: RefineryExtract): String =
            JSON.encodeToString(RefineryExtract.serializer(), extract)

        /** Write the contract document as pretty-printed JSON (every field explicit). */
        fun writeJson(extract: RefineryExtract, target: File) {
            target.absoluteFile.parentFile?.mkdirs()
            target.writeText(toJson(extract))
        }
    }
}

/**
 * The clipped-crop signature: no row carries a quantity (or the read failed entirely). A correctly
 * framed SETUP panel always shows the QTY column, so this means the number columns were cut off —
 * the trigger for the ultrawide terminal-extent rescue ([RefineryPipeline.extract]).
 */
private fun PanelRead?.lacksQuantities(): Boolean = this == null || rows.none { it.qty != null }
