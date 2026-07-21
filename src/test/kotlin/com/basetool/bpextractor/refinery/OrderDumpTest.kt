package com.basetool.bpextractor.refinery

import com.basetool.bpextractor.refinery.model.RefineryExtractGood
import java.io.File
import java.time.Instant
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * Dev scaffolding (NOT a behaviour test): runs the REAL pipeline (live Ollama + OCR) over a set of
 * order folders and prints the emitted contract + warnings + any ingest-invalid rows. Gated on
 * `ORDER_DUMP_DIR` (a folder of order folders); trivially green when unset. Inputs are PRIVATE
 * captures — never commit the dumped output.
 */
class OrderDumpTest {

    @Test
    fun `dump real extraction for a set of orders`() {
        val root = System.getenv("ORDER_DUMP_DIR")?.takeUnless { it.isBlank() }?.let(::File) ?: return
        require(root.isDirectory) { "ORDER_DUMP_DIR is not a directory: $root" }
        val only = System.getenv("ORDER_DUMP_ONLY")?.split(",")?.map { it.trim() }?.toSet()
        val ollama = HttpOllamaClient()
        val verify = System.getenv("ORDER_DUMP_VERIFY")?.equals("true", ignoreCase = true) == true

        val orders = root.listFiles { f: File -> f.isDirectory }!!
            .filter { only == null || it.name in only }
            .sortedBy { it.name.filter(Char::isDigit).toIntOrNull() ?: Int.MAX_VALUE }

        for (folder in orders) {
            val imageFiles = folder.listFiles { f: File -> f.extension.lowercase() in setOf("png", "jpg", "jpeg") }!!
                .sortedBy { it.name }
            if (imageFiles.isEmpty()) continue
            val inputs = imageFiles.map { f ->
                PipelineInput(f.name, ImageIO.read(f), CaptureTime.of(f))
            }
            val pipeline = RefineryPipeline(
                ollama = ollama,
                model = Preflight.MODEL_RECOMMENDED,
                toolVersion = "dump",
                now = { Instant.parse("2026-07-21T00:00:00Z") },
                verifyModel = if (verify) Preflight.MODEL_VERIFY else null,
            )
            println("\n\n════════════════════ ${folder.name} ════════════════════")
            val result = try {
                pipeline.extract(inputs)
            } catch (t: Throwable) {
                println("  !! extract FAILED: ${t.message}")
                continue
            }
            val order = result.extract.orders.first()
            println("  location=${order.rawLocationName}  method=${order.rawMethodName}  quoted=${order.quoted}")
            println("  inManifest=${order.rawInManifestTotal}  toRefine=${order.rawToRefineTotal}  cost=${order.expenses}  dur=${order.durationMinutes}")
            println("  warnings=${result.validated.warnings}")
            println("  rows:")
            order.goods.forEach { g -> println("    ${fmt(g)}") }
            val invalid = order.goods.filter { it.inputQuantity == null }
            if (invalid.isNotEmpty()) {
                println("  !! INGEST-INVALID (inputQuantity==null → @NotNull reject): rows ${invalid.map { it.rowIndex }}")
            }
            val onSum = order.goods.filter { it.refine }.mapNotNull { it.inputQuantity }.sum()
            println("  Σ qty(ON)=$onSum  vs toRefine=${order.rawToRefineTotal}")
            println("  ── JSON ──")
            println(RefineryPipeline.toJson(result.extract))
        }
    }

    private fun fmt(g: RefineryExtractGood): String =
        "row=${g.rowIndex} name='${g.rawMaterialName}' q=${g.quality} qty=${g.inputQuantity} " +
            "yield=${g.outputQuantity} refine=${g.refine} conf=${g.confidence} src=${g.sourceImage}"
}
