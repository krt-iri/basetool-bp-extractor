package com.basetool.bpextractor.refinery

import com.basetool.bpextractor.refinery.model.RefineryExtract
import com.basetool.bpextractor.refinery.model.RefineryExtractGood
import com.basetool.bpextractor.refinery.model.RefineryExtractImage
import com.basetool.bpextractor.refinery.model.RefineryExtractOrder
import com.basetool.bpextractor.refinery.model.ordersMissingGoods
import com.basetool.bpextractor.refinery.model.ordersMissingSourceImages
import com.basetool.bpextractor.refinery.model.rowsMissingQuantity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the frozen `RefineryExtract` v1 contract shape (master plan §5, ADR-0008): the §5 example
 * document must decode into the model verbatim, and the encoder must emit every contract field —
 * the backend's Jakarta validation rejects missing required fields, and `quoted` / `rowIndex` /
 * nullable `outputQuantity` carry semantics the import depends on.
 */
class RefineryExtractContractTest {

    private val json = Json { encodeDefaults = true }

    /** The §5 example, comments stripped — the exact wire shape both sides implement. */
    private val contractExample = """
        {
          "schemaVersion": 1,
          "tool": "basetool-sc-extractor",
          "toolVersion": "1.4.0",
          "model": "qwen3-vl:8b-instruct",
          "generatedAt": "2026-06-05T20:00:00Z",
          "clientLanguage": "en",
          "orders": [
            {
              "panelType": "SETUP",
              "quoted": true,
              "layoutConfidence": 0.92,
              "rawLocationName": "LEVSKI",
              "rawMethodName": "FERRON EXCHANGE",
              "rawInManifestTotal": 32295,
              "rawToRefineTotal": 32295,
              "expenses": 48928.00,
              "durationMinutes": 1258,
              "totalYieldScu": null,
              "sourceImages": [
                { "name": "frame_213823.png", "width": 3840, "height": 2160, "cropMode": "vlm", "capturedAt": "2026-06-05T19:38:23Z" }
              ],
              "goods": [
                {
                  "rowIndex": 0,
                  "rawMaterialName": "LINDINIUM (ORE)",
                  "quality": 618,
                  "inputQuantity": 957,
                  "outputQuantity": 448,
                  "refine": true,
                  "confidence": 0.95,
                  "sourceImage": "frame_213823.png"
                }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `the section-5 contract example decodes verbatim`() {
        val extract = json.decodeFromString<RefineryExtract>(contractExample)

        assertEquals(1, extract.schemaVersion)
        assertEquals("basetool-sc-extractor", extract.tool)
        assertEquals("en", extract.clientLanguage)
        val order = extract.orders.single()
        assertEquals("SETUP", order.panelType)
        assertTrue(order.quoted)
        assertEquals("LEVSKI", order.rawLocationName)
        assertEquals(32295L, order.rawToRefineTotal)
        assertEquals(48928.0, order.expenses)
        assertEquals(1258L, order.durationMinutes)
        assertNull(order.totalYieldScu)
        val image = order.sourceImages.single()
        assertEquals("2026-06-05T19:38:23Z", image.capturedAt)
        val good = order.goods.single()
        assertEquals(0, good.rowIndex)
        assertEquals("LINDINIUM (ORE)", good.rawMaterialName)
        assertEquals(448L, good.outputQuantity)
        assertEquals(0.95, good.confidence)
        assertEquals("frame_213823.png", good.sourceImage)
    }

    @Test
    fun `encoding emits every contract field including nullable ones`() {
        val extract = RefineryExtract(
            tool = "basetool-sc-extractor",
            toolVersion = "0.0.0-test",
            model = "qwen3-vl:8b-instruct",
            generatedAt = "2026-06-10T12:00:00Z",
            orders = listOf(
                RefineryExtractOrder(
                    panelType = "SETUP",
                    quoted = false,
                    layoutConfidence = 0.9,
                    sourceImages = listOf(RefineryExtractImage("a.png", 3840, 2160, "vlm")),
                    goods = listOf(
                        RefineryExtractGood(
                            rowIndex = 0,
                            rawMaterialName = "UCTION SALVAGE",
                            quality = 0,
                            inputQuantity = 76,
                            outputQuantity = null,
                            refine = true,
                            confidence = 0.95,
                            sourceImage = "a.png",
                        ),
                    ),
                ),
            ),
        )

        val encoded = json.encodeToString(RefineryExtract.serializer(), extract)
        val root = json.parseToJsonElement(encoded).jsonObject
        val order = root["orders"]!!.jsonObject(0)
        val image = order["sourceImages"]!!.jsonObject(0)
        val good = order["goods"]!!.jsonObject(0)

        // Required keys present at every level (the backend's @NotNull gate).
        for (key in listOf("schemaVersion", "tool", "toolVersion", "model", "generatedAt", "clientLanguage", "orders")) {
            assertTrue(key in root, "missing top-level key $key")
        }
        for (key in listOf(
            "panelType", "quoted", "layoutConfidence", "rawLocationName", "rawMethodName",
            "rawInManifestTotal", "rawToRefineTotal", "expenses", "durationMinutes",
            "totalYieldScu", "sourceImages", "goods",
        )) {
            assertTrue(key in order, "missing order key $key")
        }
        for (key in listOf("name", "width", "height", "cropMode", "capturedAt")) {
            assertTrue(key in image, "missing source-image key $key")
        }
        for (key in listOf(
            "rowIndex", "rawMaterialName", "quality", "inputQuantity", "outputQuantity",
            "refine", "confidence", "sourceImage",
        )) {
            assertTrue(key in good, "missing good key $key")
        }

        // The round trip is lossless.
        assertEquals(extract, json.decodeFromString<RefineryExtract>(encoded))
    }

    @Test
    fun `rowsMissingQuantity flags exactly the null-input rows the ingest edge rejects`() {
        fun good(rowIndex: Int, qty: Long?) = RefineryExtractGood(
            rowIndex = rowIndex,
            rawMaterialName = "AGRICIUM (ORE)",
            quality = 588,
            inputQuantity = qty,
            outputQuantity = null,
            refine = true,
            confidence = 0.4,
            sourceImage = "a.png",
        )
        fun extractOf(vararg goods: RefineryExtractGood) = RefineryExtract(
            tool = "basetool-sc-extractor",
            toolVersion = "0.0.0-test",
            model = "qwen3-vl:8b-instruct",
            generatedAt = "2026-07-21T00:00:00Z",
            orders = listOf(
                RefineryExtractOrder(
                    panelType = "SETUP",
                    quoted = true,
                    layoutConfidence = 0.4,
                    sourceImages = listOf(RefineryExtractImage("a.png", 850, 1005, "vlm")),
                    goods = goods.toList(),
                ),
            ),
        )

        // A clean order (Auftrag 21 after the rescue) is safe to send.
        assertEquals(emptyList(), extractOf(good(0, 11992), good(1, 44336)).rowsMissingQuantity())
        // The pre-rescue Auftrag 21 read (every QTY cell clipped) — every row blocks the send.
        assertEquals(
            listOf(0, 1, 2),
            extractOf(good(0, null), good(1, null), good(2, null)).rowsMissingQuantity(),
        )
        // A single unreadable row is enough to block, and only that row is reported.
        assertEquals(listOf(1), extractOf(good(0, 60), good(1, null), good(2, 43)).rowsMissingQuantity())
    }

    @Test
    fun `the empty-list guards flag exactly the orders the tightened contract rejects`() {
        // `orders[].sourceImages` and `orders[].goods` became @NotEmpty with the ADR-0008 amendment
        // (REQ-INGEST-010). This producer fills both, so the guard exists to name the violation
        // locally instead of letting the user run into an opaque 400 from the edge.
        fun order(images: Int, rows: Int) = RefineryExtractOrder(
            panelType = "SETUP",
            quoted = true,
            layoutConfidence = 0.4,
            sourceImages = (0 until images).map { RefineryExtractImage("a$it.png", 850, 1005, "vlm") },
            goods = (0 until rows).map {
                RefineryExtractGood(
                    rowIndex = it,
                    rawMaterialName = "AGRICIUM (ORE)",
                    quality = 588,
                    inputQuantity = 11_992,
                    outputQuantity = null,
                    refine = true,
                    confidence = 0.4,
                    sourceImage = "a0.png",
                )
            },
        )
        fun extractOf(vararg orders: RefineryExtractOrder) = RefineryExtract(
            tool = "basetool-sc-extractor",
            toolVersion = "0.0.0-test",
            model = "qwen3-vl:8b-instruct",
            generatedAt = "2026-07-21T00:00:00Z",
            orders = orders.toList(),
        )

        // What the pipeline actually produces: one order, one screenshot, rows read from it.
        val healthy = extractOf(order(images = 1, rows = 3))
        assertEquals(emptyList(), healthy.ordersMissingSourceImages())
        assertEquals(emptyList(), healthy.ordersMissingGoods())

        // A capture whose row area was cropped away stitches to zero rows — reachable in practice.
        assertEquals(listOf(0), extractOf(order(images = 1, rows = 0)).ordersMissingGoods())
        assertEquals(emptyList(), extractOf(order(images = 1, rows = 0)).ordersMissingSourceImages())

        // Provenance lost — a can't-happen the edge contract nonetheless refuses.
        assertEquals(listOf(0), extractOf(order(images = 0, rows = 2)).ordersMissingSourceImages())
        assertEquals(emptyList(), extractOf(order(images = 0, rows = 2)).ordersMissingGoods())

        // Only the offending order is reported, by index, even though v1 emits just one.
        val mixed = extractOf(order(1, 2), order(1, 0), order(0, 0))
        assertEquals(listOf(1, 2), mixed.ordersMissingGoods())
        assertEquals(listOf(2), mixed.ordersMissingSourceImages())
    }

    private fun kotlinx.serialization.json.JsonElement.jsonObject(index: Int) =
        (this as kotlinx.serialization.json.JsonArray)[index].jsonObject
}
