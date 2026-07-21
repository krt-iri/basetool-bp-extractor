package com.basetool.bpextractor.refinery.model

import kotlinx.serialization.Serializable

/**
 * The frozen `RefineryExtract` JSON contract, v1 — the single hand-off between this desktop tool,
 * the basetool frontend upload and the backend import endpoint (master plan §5, ADR-0008 in the
 * basetool repo). Field names and shapes are FROZEN: any breaking change requires bumping
 * [SCHEMA_VERSION] and a coordinated backend change. The backend accepts `schemaVersion == 1`
 * only and rejects anything else with a 400.
 */
@Serializable
data class RefineryExtract(
    val schemaVersion: Int = SCHEMA_VERSION,
    /** Producer name (provenance). */
    val tool: String,
    /** Producer version (provenance) — the MSI/app version. */
    val toolVersion: String,
    /** Which VLM produced the reads (provenance), e.g. `qwen3-vl:8b-instruct`. */
    val model: String,
    /** UTC ISO-8601 instant of the export. */
    val generatedAt: String,
    /** v1 supports the English game client only (the prompt and column anchors are English). */
    val clientLanguage: String = "en",
    /** v1 emits exactly one order; the backend processes `orders[0]`. */
    val orders: List<RefineryExtractOrder>,
) {
    companion object {
        /** Contract version this producer emits; the backend gate (REQ-REFINERY-001) matches it. */
        const val SCHEMA_VERSION = 1
    }
}

/** One extracted work order (master plan §5): header fields + the stitched goods rows. */
@Serializable
data class RefineryExtractOrder(
    /** `SETUP` | `PROCESSING` | `UNKNOWN`; v1 produces and the backend accepts `SETUP` only. */
    val panelType: String,
    /** `false` = captured in the GET-QUOTE state (YIELD/cost/time still `--`). */
    val quoted: Boolean,
    /** 0..1 — derived layout-parse confidence over all source images (never model-verbalized). */
    val layoutConfidence: Double,
    /** Verbatim location from the TERMINAL HEADER (outside the panel); null on pre-cropped input. */
    val rawLocationName: String? = null,
    /** Verbatim refining method as read, e.g. `FERRON EXCHANGE`; nullable. */
    val rawMethodName: String? = null,
    /** Panel header `IN MANIFEST` total; nullable — completeness checksum. */
    val rawInManifestTotal: Long? = null,
    /** Panel header `TO REFINE` total; nullable — completeness checksum. */
    val rawToRefineTotal: Long? = null,
    /** Total cost in aUEC; null when `quoted == false`. */
    val expenses: Double? = null,
    /** Processing time in minutes (from e.g. `20H 58M`); nullable. */
    val durationMinutes: Long? = null,
    /** PROCESSING-only total yield; always null on SETUP. */
    val totalYieldScu: Double? = null,
    /** The screenshots this order was stitched from. */
    val sourceImages: List<RefineryExtractImage>,
    /** The stitched, deduplicated rows in on-screen order (`rowIndex` ascending). */
    val goods: List<RefineryExtractGood>,
)

/** Provenance of one source screenshot (master plan §5). */
@Serializable
data class RefineryExtractImage(
    val name: String,
    val width: Int,
    val height: Int,
    /** How the panel was obtained: `vlm` (auto-located), `manual`, or `precropped`. */
    val cropMode: String,
    /**
     * UTC ISO-8601 capture instant of this screenshot ([CaptureTime][com.basetool.bpextractor.refinery.CaptureTime]:
     * file-name timestamp, else file modified time); null when neither is available. Optional
     * additive v1 field (ADR-0008) — the basetool derives the order's start time from the LATEST
     * capture across `sourceImages`.
     */
    val capturedAt: String? = null,
)

/** One stitched goods row (master plan §5). */
@Serializable
data class RefineryExtractGood(
    /** Stitched on-screen order, top row = 0. */
    val rowIndex: Int,
    /** Verbatim, including `(ORE)`/`(RAW)` suffixes; may be UI-truncated (`UCTION SALVAGE`). */
    val rawMaterialName: String,
    /** SC QUALITY column; expected 0..1000 (0 is valid — inert and some refine-ON salvage rows). */
    val quality: Int?,
    /** SC QTY column, ≥ 0. */
    val inputQuantity: Long?,
    /** SC YIELD column (projected); null when the row is un-quoted (read as `--`). */
    val outputQuantity: Long? = null,
    /** SC REFINE toggle (true = ON, false = OFF/inert). */
    val refine: Boolean,
    /**
     * 0..1 — DERIVED per-row confidence (deterministic validation + header checksum, Phase 0
     * policy in `docs/refinery-extractor/PHASE0_FINDINGS.md` §6) — never the model's verbalized
     * self-estimate.
     */
    val confidence: Double,
    /** Name of the screenshot this row was (first) read from. */
    val sourceImage: String,
)

/**
 * The `rowIndex`es whose `inputQuantity` is null — rows the basetool ingest edge HARD-REJECTS. The
 * server contract makes `inputQuantity` `@NotNull @PositiveOrZero`, but this producer emits `null`
 * when the QTY cell could not be read at all (a clipped or too-small capture, e.g. a panel crop that
 * lost the number columns). Sending such a payload makes the gateway answer `400` with an opaque
 * `"Validation failed."` problem, so the export screen blocks the send on this instead and points the
 * user back to the review to correct (or re-capture) the flagged rows. Empty ⇒ every row carries a
 * quantity and the payload clears the edge. Spans every order, in on-screen (`rowIndex`) order.
 */
fun RefineryExtract.rowsMissingQuantity(): List<Int> =
    orders.flatMap { it.goods }.filter { it.inputQuantity == null }.map { it.rowIndex }
