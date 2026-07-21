package com.basetool.bpextractor.refinery

import com.basetool.bpextractor.refinery.model.RefineryExtract
import kotlinx.serialization.json.Json
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the pipeline orchestration (master plan §9 Phase 3) against a scripted [OllamaApi] —
 * recorded golden-style markdown answers instead of a live GPU: stage order (one image at a
 * time), the keep_alive pin/release protocol, the single location read from the first full
 * frame, failed-read tolerance, cancellation, and the assembled contract document.
 */
class RefineryPipelineTest {

    private companion object {
        const val VERIFY_MODEL = "verify-model"
    }

    /** A scripted Ollama: panel/verify answers from queues (routed by model), location fixed. */
    private class FakeOllama(
        panelAnswers: List<String>,
        private val locationAnswer: String = "LEVSKI",
        verifyAnswers: List<String> = emptyList(),
    ) : OllamaApi {
        /** (kind, keepAlive) per chat call — and ("unload", model) per release — in order. */
        val calls = mutableListOf<Pair<String, String>>()
        private val queue = ArrayDeque(panelAnswers)
        private val verifyQueue = ArrayDeque(verifyAnswers)

        override fun installedModels(): List<OllamaModel> = emptyList()

        override fun loadedModels(): List<OllamaModel> = emptyList()

        override fun chat(model: String, prompt: String, imageB64: String, numPredict: Int, keepAlive: String, numGpu: Int?): ChatResult =
            when {
                prompt.contains("station/outpost") -> {
                    calls += "location" to keepAlive
                    ChatResult(locationAnswer, "stop")
                }
                model == VERIFY_MODEL -> {
                    calls += "verify" to keepAlive
                    ChatResult(verifyQueue.removeFirst(), "stop")
                }
                else -> {
                    calls += "panel" to keepAlive
                    ChatResult(queue.removeFirst(), "stop")
                }
            }

        override fun pull(model: String, onProgress: (Long?, Long?, String) -> Unit) = error("not used")

        var failUnload = false

        override fun unload(model: String) {
            calls += "unload" to model
            if (failUnload) error("ollama went away")
        }
    }

    private val fixedNow: () -> Instant = { Instant.parse("2026-06-10T12:00:00Z") }

    private fun pipeline(ollama: OllamaApi, isActive: () -> Boolean = { true }, verifyModel: String? = null) =
        RefineryPipeline(
            ollama,
            model = "qwen3-vl:8b-instruct",
            toolVersion = "0.0.0-test",
            now = fixedNow,
            isActive = isActive,
            verifyModel = verifyModel,
            ocr = { null }, // unit tests stay offline — no ONNX models loaded
        )

    /** A dark full frame (no colour anchors → verified fallback geometry, still a full frame). */
    private fun fullFrame(width: Int = 1920, height: Int = 1080): BufferedImage {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        try {
            g.color = Color(18, 18, 18)
            g.fillRect(0, 0, width, height)
        } finally {
            g.dispose()
        }
        return img
    }

    /** A pre-cropped portrait panel shot (no terminal header → no location read possible). */
    private fun precropped(): BufferedImage = fullFrame(500, 1400)

    /**
     * A dark full frame carrying a tall bright-white UI-text band on the left — enough for
     * [Locate.terminalExtentBox] to find a terminal extent (bright [isUiText] columns) that differs
     * from the anchor-less [Locate.fallbackPanel] geometry, so the empty-quantities rescue can fire.
     */
    private fun frameWithTextBand(width: Int = 1920, height: Int = 1080): BufferedImage {
        val img = fullFrame(width, height)
        val g = img.createGraphics()
        try {
            g.color = Color(240, 240, 240)
            g.fillRect(width / 16, height / 10, width / 8, height * 8 / 10)
        } finally {
            g.dispose()
        }
        return img
    }

    private val upperAnswer = """
        METHOD: FERRON EXCHANGE
        QUOTED: YES
        IN_MANIFEST: 32295
        TO_REFINE: 32295
        TOTAL_COST: 48928
        PROCESSING_TIME: 20H 58M
        CTA: CONFIRM

        | MATERIAL | QUALITY | QTY | YIELD | REFINE |
        | --- | --- | --- | --- | --- |
        | LINDINIUM (ORE) | 618 | 957 | 448 | ON |
        | TUNGSTEN (ORE) | 530 | 1104 | 560 | ON |
    """.trimIndent()

    private val lowerAnswer = """
        METHOD: FERRON EXCHANGE
        QUOTED: YES
        IN_MANIFEST: 32295
        TO_REFINE: 32295
        TOTAL_COST: 48928
        PROCESSING_TIME: 20H 58M
        CTA: CONFIRM

        | MATERIAL | QUALITY | QTY | YIELD | REFINE |
        | --- | --- | --- | --- | --- |
        | TUNGSTEN (ORE) | 530 | 1104 | 560 | ON |
        | INERT MATERIALS | 0 | 5449 | 0 | OFF |
    """.trimIndent()

    @Test
    fun `two scrolled captures assemble into one contract order`() {
        val ollama = FakeOllama(listOf(upperAnswer, lowerAnswer))

        val result = pipeline(ollama).extract(
            listOf(
                PipelineInput(
                    "a_upper.png",
                    fullFrame(),
                    capturedAt = java.time.Instant.parse("2026-06-01T21:38:23.456Z"),
                ),
                PipelineInput("b_lower.png", fullFrame()),
            ),
        )

        val extract = result.extract
        assertEquals("basetool-sc-extractor", extract.tool)
        assertEquals("qwen3-vl:8b-instruct", extract.model)
        assertEquals("2026-06-10T12:00:00Z", extract.generatedAt)
        val order = extract.orders.single()
        assertEquals("LEVSKI", order.rawLocationName)
        assertEquals("FERRON EXCHANGE", order.rawMethodName)
        assertEquals(48928.0, order.expenses)
        assertEquals(20L * 60 + 58, order.durationMinutes)
        assertTrue(order.quoted)
        // Overlap-stitched: LINDINIUM, TUNGSTEN (shared), INERT — in on-screen order.
        assertEquals(
            listOf("LINDINIUM (ORE)", "TUNGSTEN (ORE)", "INERT MATERIALS"),
            order.goods.map { it.rawMaterialName },
        )
        assertEquals(listOf(0, 1, 2), order.goods.map { it.rowIndex })
        assertEquals(2, order.sourceImages.size)
        assertEquals(listOf("vlm", "vlm"), order.sourceImages.map { it.cropMode })
        assertEquals(1920, order.sourceImages[0].width)
        // capturedAt flows per image, truncated to seconds; an unknown capture stays null.
        assertEquals(listOf("2026-06-01T21:38:23Z", null), order.sourceImages.map { it.capturedAt })
    }

    @Test
    fun `keep_alive pins the batch and one explicit unload releases the model at the end`() {
        val ollama = FakeOllama(listOf(upperAnswer, lowerAnswer))

        pipeline(ollama).extract(
            listOf(PipelineInput("a.png", fullFrame()), PipelineInput("b.png", fullFrame())),
        )

        assertEquals(
            listOf("panel" to "10m", "location" to "10m", "panel" to "10m", "unload" to "qwen3-vl:8b-instruct"),
            ollama.calls,
        )
    }

    @Test
    fun `a single full-frame capture also ends on the explicit unload`() {
        val ollama = FakeOllama(listOf(upperAnswer))

        pipeline(ollama).extract(listOf(PipelineInput("only.png", fullFrame())))

        assertEquals(
            listOf("panel" to "10m", "location" to "10m", "unload" to "qwen3-vl:8b-instruct"),
            ollama.calls,
        )
    }

    @Test
    fun `pre-cropped input skips the location read and carries no location`() {
        val ollama = FakeOllama(listOf(upperAnswer))

        val result = pipeline(ollama).extract(listOf(PipelineInput("crop.png", precropped())))

        assertEquals(listOf("panel" to "10m", "unload" to "qwen3-vl:8b-instruct"), ollama.calls)
        assertNull(result.location)
        assertEquals("precropped", result.extract.orders.single().sourceImages.single().cropMode)
        assertNull(result.extract.orders.single().rawLocationName)
    }

    @Test
    fun `a failed read becomes a null-quoted outcome and the rest continues`() {
        val ollama = FakeOllama(listOf("I cannot help with that.", lowerAnswer))

        val result = pipeline(ollama).extract(
            listOf(PipelineInput("bad.png", fullFrame()), PipelineInput("good.png", fullFrame())),
        )

        assertNull(result.outcomes[0].quoted)
        assertEquals(0, result.outcomes[0].rows)
        assertEquals(true, result.outcomes[1].quoted)
        assertEquals(
            listOf("TUNGSTEN (ORE)", "INERT MATERIALS"),
            result.extract.orders.single().goods.map { it.rawMaterialName },
        )
    }

    @Test
    fun `a per-panel crop that reads no quantities is rescued via the terminal extent`() {
        // Auftrag 21/22: the located box clipped the number columns (or landed on the sidebar), so
        // the first read returns material names but no quantity in any cell. The rescue re-reads the
        // whole terminal extent — now on ANY frame, not just ultrawide — and its numbers win.
        val noQuantities = """
            METHOD: DINYX SOLVENTATION
            QUOTED: YES
            IN_MANIFEST: 56061
            TO_REFINE: --
            CTA: CONFIRM

            | MATERIAL | QUALITY | QTY | YIELD | REFINE |
            | --- | --- | --- | --- | --- |
            | AGRICIUM (ORE) | 346 | -- | -- | ON |
            | AGRICIUM (ORE) | 588 | -- | -- | ON |
        """.trimIndent()
        val ollama = FakeOllama(listOf(noQuantities, upperAnswer))

        val result = pipeline(ollama).extract(listOf(PipelineInput("clipped.png", frameWithTextBand())))

        // The rescue read (upperAnswer) supplied the quantities; the clipped first read was dropped.
        val order = result.extract.orders.single()
        assertEquals(listOf("LINDINIUM (ORE)", "TUNGSTEN (ORE)"), order.goods.map { it.rawMaterialName })
        assertEquals(listOf(957L, 1104L), order.goods.map { it.inputQuantity })
        assertEquals(32295L, order.rawToRefineTotal)
        // Two panel reads (primary + rescue), then the single location read, then the unload.
        assertEquals(
            listOf("panel" to "10m", "panel" to "10m", "location" to "10m", "unload" to "qwen3-vl:8b-instruct"),
            ollama.calls,
        )
    }

    @Test
    fun `all reads failing throws instead of exporting an empty order`() {
        val ollama = FakeOllama(listOf("nope", "still nope"))

        assertFailsWith<IllegalStateException> {
            pipeline(ollama).extract(
                listOf(PipelineInput("a.png", fullFrame()), PipelineInput("b.png", fullFrame())),
            )
        }
    }

    @Test
    fun `cancellation between images aborts the run`() {
        val ollama = FakeOllama(listOf(upperAnswer, lowerAnswer))
        var checks = 0

        assertFailsWith<PipelineCancelledException> {
            pipeline(ollama, isActive = { checks++ < 1 }).extract(
                listOf(PipelineInput("a.png", fullFrame()), PipelineInput("b.png", fullFrame())),
            )
        }
        // The first image ran; the second never started a read.
        assertEquals(listOf("panel" to "10m", "location" to "10m"), ollama.calls)
    }

    @Test
    fun `stages fire in order with one image active at a time`() {
        val ollama = FakeOllama(listOf(upperAnswer, lowerAnswer))
        val events = mutableListOf<String>()
        val listener = object : PipelineListener {
            override fun onStage(index: Int, name: String, stage: PipelineStage) {
                events += "$index:$stage"
            }

            override fun onImageDone(index: Int, outcome: ImageOutcome) {
                events += "$index:DONE"
            }
        }

        pipeline(ollama).extract(
            listOf(PipelineInput("a.png", fullFrame()), PipelineInput("b.png", fullFrame())),
            listener,
        )

        assertEquals(
            listOf(
                "0:LOCATE", "0:NORMALIZE", "0:READ", "0:DONE",
                "1:LOCATE", "1:NORMALIZE", "1:READ", "1:DONE",
            ),
            events,
        )
    }

    private val unquotedAnswer = """
        METHOD: FERRON EXCHANGE
        QUOTED: NO
        IN_MANIFEST: 3941
        TO_REFINE: 3941
        TOTAL_COST: --
        PROCESSING_TIME: --
        CTA: GET QUOTE

        | MATERIAL | QUALITY | QTY | YIELD | REFINE |
        | --- | --- | --- | --- | --- |
        | LINDINIUM (ORE) | 505 | 2837 | -- | ON |
        | TUNGSTEN (ORE) | 530 | 1104 | -- | ON |
    """.trimIndent()

    @Test
    fun `an un-quoted capture is flagged in its outcome`() {
        val ollama = FakeOllama(listOf(unquotedAnswer))

        val result = pipeline(ollama).extract(listOf(PipelineInput("early.png", fullFrame())))

        assertEquals(false, result.outcomes.single().quoted)
        assertTrue(result.validated.isUnquoted())
        assertEquals(false, result.extract.orders.single().quoted)
    }

    private val verifyPrimaryAnswer = """
        METHOD: DINYX SOLVENTATION
        QUOTED: YES
        IN_MANIFEST: 600
        TO_REFINE: 449
        TOTAL_COST: 831
        PROCESSING_TIME: 3H 8M
        CTA: CONFIRM

        | MATERIAL | QUALITY | QTY | YIELD | REFINE |
        | --- | --- | --- | --- | --- |
        | GOLD (ORE) | 553 | 46 | 21 | ON |
        | BORASE (ORE) | 359 | 483 | 195 | ON |
    """.trimIndent()

    private val verifySecondaryAnswer = verifyPrimaryAnswer.replace("| 483 |", "| 403 |")

    @Test
    fun `the verify pass arbitrates a digit disagreement via the TO REFINE checksum`() {
        // Primary reads 483, the verify model 403; only 403 lands Σ QTY(ON) on the header
        // (46+403 = 449) — the Auftrag 10 class, auto-corrected instead of just flagged.
        val ollama = FakeOllama(listOf(verifyPrimaryAnswer), verifyAnswers = listOf(verifySecondaryAnswer))

        val result = pipeline(ollama, verifyModel = VERIFY_MODEL)
            .extract(listOf(PipelineInput("a.png", fullFrame())))

        val goods = result.extract.orders.single().goods
        assertEquals(403L, goods[1].inputQuantity)
        assertEquals(Validation.CONFIDENCE_VERIFY_CORRECTED, goods[1].confidence)
        assertTrue(ExtractWarning.VERIFY_CORRECTED in result.validated.warnings)
        assertTrue(ExtractWarning.VERIFY_MISMATCH !in result.validated.warnings)
        // The primary unloads BEFORE the partner loads (12-GB tier: both never resident at once);
        // the verify model unloads at the end of the run.
        assertEquals(
            listOf(
                "panel" to "10m",
                "location" to "10m",
                "unload" to "qwen3-vl:8b-instruct",
                "verify" to "10m",
                "unload" to VERIFY_MODEL,
            ),
            ollama.calls,
        )
    }

    @Test
    fun `the verify pass surfaces as a per-image VERIFY stage`() {
        val ollama = FakeOllama(listOf(verifyPrimaryAnswer), verifyAnswers = listOf(verifySecondaryAnswer))
        val events = mutableListOf<String>()
        val listener = object : PipelineListener {
            override fun onStage(index: Int, name: String, stage: PipelineStage) {
                events += "$index:$stage"
            }
        }

        pipeline(ollama, verifyModel = VERIFY_MODEL)
            .extract(listOf(PipelineInput("a.png", fullFrame())), listener)

        assertEquals(listOf("0:LOCATE", "0:NORMALIZE", "0:READ", "0:VERIFY"), events)
    }

    @Test
    fun `an off-script verify answer degrades to no cross-check instead of failing the run`() {
        val ollama = FakeOllama(listOf(verifyPrimaryAnswer), verifyAnswers = listOf("I cannot help with that."))

        val result = pipeline(ollama, verifyModel = VERIFY_MODEL)
            .extract(listOf(PipelineInput("a.png", fullFrame())))

        val goods = result.extract.orders.single().goods
        // The verify pass degraded (no cross-check). The single-model checksum repair does NOT fire:
        // BORASE is the only row of its material, so there is no leave-one-out yield-rate witness to
        // tie the checksum landing to it (and checksum-landing alone could corrupt a correct row), so
        // the read honestly stands and the order stays flagged for review.
        assertEquals(483L, goods[1].inputQuantity, "no single-model repair without a yield-rate witness")
        assertTrue(ExtractWarning.CHECKSUM_REPAIRED !in result.validated.warnings)
        assertTrue(ExtractWarning.VERIFY_CORRECTED !in result.validated.warnings)
        assertTrue(ExtractWarning.VERIFY_MISMATCH !in result.validated.warnings)
    }

    @Test
    fun `a failing unload never fails the run`() {
        val ollama = FakeOllama(listOf(upperAnswer)).apply { failUnload = true }

        val result = pipeline(ollama).extract(listOf(PipelineInput("a.png", fullFrame())))

        assertEquals(1, result.extract.orders.size)
    }

    @Test
    fun `without a verify model no verify call is made`() {
        val ollama = FakeOllama(listOf(upperAnswer))

        pipeline(ollama).extract(listOf(PipelineInput("a.png", fullFrame())))

        assertTrue(ollama.calls.none { it.first == "verify" })
    }

    @Test
    fun `writeJson emits a decodable contract document`() {
        val ollama = FakeOllama(listOf(upperAnswer))
        val result = pipeline(ollama).extract(listOf(PipelineInput("a.png", fullFrame())))
        val target = File.createTempFile("refinery-extract", ".json")

        try {
            RefineryPipeline.writeJson(result.extract, target)

            val decoded = Json.decodeFromString<RefineryExtract>(target.readText())
            assertEquals(result.extract, decoded)
            // Frozen-contract keys must be present literally even when null-valued.
            assertTrue(target.readText().contains("\"totalYieldScu\""))
        } finally {
            target.delete()
        }
    }
}
