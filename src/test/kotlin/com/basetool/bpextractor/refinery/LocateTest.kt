package com.basetool.bpextractor.refinery

import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the classical-CV Locate + Normalize stages against synthetic frames that reproduce the
 * verified colour anchors (maroon tab strip ~RGB(72,49,45), orange CTA) at known positions —
 * including the owner rule "leftmost panel = newest order = extraction target" with two panels
 * side by side (the Auftrag 2 layout).
 */
class LocateTest {

    private val maroon = Color(72, 49, 45)
    private val cta = Color(231, 126, 35)
    private val dark = Color(18, 18, 18)

    /** Paint a synthetic panel: a maroon tab strip plus an orange CTA bar near the bottom. */
    private fun paintPanel(img: BufferedImage, x: Int, y: Int, panelWidth: Int, panelHeight: Int) {
        val g = img.createGraphics()
        try {
            // Tab strip: left part of the panel, a few dozen native rows tall.
            g.color = maroon
            g.fillRect(x, y, (panelWidth * 0.55).toInt(), 36)
            // CTA button: right-aligned near the panel bottom.
            g.color = cta
            g.fillRect(x + (panelWidth * 0.55).toInt(), y + panelHeight - 60, (panelWidth * 0.4).toInt(), 40)
        } finally {
            g.dispose()
        }
    }

    private fun frame(width: Int = 3840, height: Int = 2160): BufferedImage {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        try {
            g.color = dark
            g.fillRect(0, 0, width, height)
        } finally {
            g.dispose()
        }
        return img
    }

    @Test
    fun `finds a single panel via the colour anchors`() {
        val img = frame()
        paintPanel(img, x = 1000, y = 400, panelWidth = 900, panelHeight = 1400)

        val boxes = Locate.locatePanels(img)

        assertEquals(1, boxes.size)
        val box = boxes.single()
        // The strip anchors the left edge; tolerances cover the 1/4-scale rounding + margins.
        assertTrue(box.x in 900..1010, "x=${box.x}")
        assertTrue(box.y in 250..410, "y=${box.y}")
        assertTrue(box.x + box.width >= 1850, "right=${box.x + box.width}")
        assertTrue(box.y + box.height >= 1700, "bottom=${box.y + box.height}")
    }

    @Test
    fun `with two panels side by side the LEFTMOST is the extraction target`() {
        // Auftrag 2 layout: the newest order's SETUP panel sits LEFT of a running second panel.
        val img = frame()
        paintPanel(img, x = 700, y = 400, panelWidth = 800, panelHeight = 1400)
        paintPanel(img, x = 2100, y = 400, panelWidth = 800, panelHeight = 1400)

        val boxes = Locate.locatePanels(img)
        val target = Locate.locatePanel(img)

        assertEquals(2, boxes.size)
        assertTrue(boxes[0].x < boxes[1].x, "candidates must come back left to right")
        assertEquals(boxes[0], target, "owner rule: leftmost = newest = target")
        assertTrue(target.x in 600..710)
        // The left panel's box must not bleed into the right panel.
        assertTrue(target.x + target.width < 2100, "right edge ${target.x + target.width} bleeds into the neighbour")
    }

    @Test
    fun `no anchors falls back to the scaled 4K geometry`() {
        val img = frame(1920, 1080)

        val boxes = Locate.locatePanels(img)
        val fallback = Locate.locatePanel(img)

        assertTrue(boxes.isEmpty())
        // 4K geometry (950, 350, 920, 1500) at half scale.
        assertEquals(475, fallback.x)
        assertEquals(175, fallback.y)
        assertEquals(460, fallback.width)
        assertEquals(750, fallback.height)
    }

    @Test
    fun `the fallback geometry keeps the 16-9 panel size on ultrawide frames`() {
        val fallback = Locate.fallbackPanel(frame(5120, 1440))

        // Position scales per axis; the SIZE scales with the height only (fy = 2/3) — the game
        // renders the panel at its 16:9 size, width-proportional scaling would distort the crop.
        assertEquals((950 * 5120 / 3840.0).toInt(), fallback.x)
        assertEquals((350 * 2 / 3.0).toInt(), fallback.y)
        assertEquals((920 * 2 / 3.0).toInt(), fallback.width)
        assertEquals(1000, fallback.height)
    }

    @Test
    fun `isUltrawide flags 32-9 but not 16-9 or just-below-2`() {
        assertTrue(Locate.isUltrawide(frame(5120, 1440)), "32:9 ≈ 3.56")
        assertTrue(Locate.isUltrawide(frame(3440, 1440)), "21:9 ≈ 2.39")
        assertFalse(Locate.isUltrawide(frame(3840, 2160)), "16:9 ≈ 1.78")
        assertFalse(Locate.isUltrawide(frame(2800, 1440)), "≈ 1.94 sits just under the 2.0 cutoff")
        assertFalse(Locate.isUltrawide(frame(909, 1101)), "a portrait terminal-area crop")
    }

    @Test
    fun `the terminal-extent box spans the widest text band full height, ignoring a stray console`() {
        // The ultrawide rescue crop: the whole terminal, isolated by its bright-UI-text columns.
        // The orange hull (no bright text) and a stray narrow wall console must be excluded.
        val img = frame(5120, 1440)
        val g = img.createGraphics()
        try {
            g.color = Color(150, 80, 40) // orange hull wings — not bright UI text, must be ignored
            g.fillRect(0, 0, 1200, 1440)
            g.fillRect(3000, 0, 2120, 1440)
            g.color = Color(230, 235, 240) // dense bright UI text across the terminal band
            for (y in 200 until 1300 step 12) g.fillRect(1500, y, 1100, 4)
            for (y in 300 until 900 step 12) g.fillRect(4800, y, 200, 4) // stray wall console (narrower)
        } finally {
            g.dispose()
        }

        val box = assertNotNull(Locate.terminalExtentBox(img))

        assertEquals(0, box.y, "full frame height")
        assertEquals(1440, box.height)
        assertTrue(box.x in 1400..1520, "left ${box.x} hugs the terminal band")
        assertTrue(box.x + box.width in 2560..2720, "right ${box.x + box.width} excludes the stray console at 4800+")
    }

    @Test
    fun `the terminal extent picks the widest text band, not a stray bright region`() {
        val img = frame(5120, 1440)
        val g = img.createGraphics()
        try {
            g.color = Color(225, 230, 235)
            for (y in 250 until 1250 step 10) g.fillRect(1600, y, 900, 3)
            for (y in 400 until 800 step 10) g.fillRect(4700, y, 150, 3)
        } finally {
            g.dispose()
        }

        val extent = Locate.terminalExtentX(img)

        assertNotNull(extent)
        assertTrue(extent.first in 1500..1620, "x0=${extent.first}")
        assertTrue(extent.second in 2480..2620, "x1=${extent.second}")
    }

    @Test
    fun `locatePanels is unchanged on non-ultrawide frames`() {
        // The per-panel colour search is the primary path for every capture; ultrawide handling now
        // lives in the pipeline rescue + the header strip, NOT here, so this must still locate.
        val img = frame(2800, 1440)
        paintPanel(img, x = 900, y = 250, panelWidth = 720, panelHeight = 1000)

        val box = Locate.locatePanelOrNull(img)

        assertNotNull(box, "located via the colour anchors")
        assertTrue(box.x in 800..960, "x=${box.x}")
        assertTrue(box.height < img.height, "a per-panel box")
    }

    @Test
    fun `precropped detection matches the golden-set shapes`() {
        assertTrue(Locate.isPrecropped(500, 1500), "a ~500px portrait panel crop")
        assertTrue(Locate.isPrecropped(518, 934), "Auftrag 3: narrow portrait panel-only crop")
        assertFalse(Locate.isPrecropped(3840, 2160), "a full 4K frame")
        assertFalse(Locate.isPrecropped(990, 700), "small but landscape is not a panel crop")
        // Squarer portrait crops of the whole terminal area carry the header + a locatable
        // panel — they must go through Locate, not the precropped shortcut.
        assertFalse(Locate.isPrecropped(914, 1053), "Auftrag 12: terminal-area crop")
        assertFalse(Locate.isPrecropped(969, 1090), "Auftrag 10: terminal-area crop")
    }

    @Test
    fun `terminal-area crop - panel located despite a strip wider than the full-frame ceiling`() {
        // The Auftrag 10/12 class: a ~914×1053 portrait crop of the terminal area. The panel's
        // tab strip spans nearly the full panel width — more than 45% of the IMAGE width (the
        // full-frame ceiling), so this only locates with the portrait ceiling (75%).
        val img = frame(914, 1053)
        val g = img.createGraphics()
        try {
            g.color = maroon
            g.fillRect(430, 100, 470, 36) // tab strip across the whole panel width
            g.color = cta
            g.fillRect(640, 870, 240, 40) // CONFIRM, right-aligned near the panel bottom
        } finally {
            g.dispose()
        }

        val box = Locate.locatePanelOrNull(img)

        assertNotNull(box)
        assertTrue(box.x in 380..440, "x=${box.x}")
        assertTrue(box.y in 0..105, "y=${box.y}")
        assertTrue(box.x + box.width >= 870, "right=${box.x + box.width}")
        assertTrue(box.y + box.height >= 900, "bottom=${box.y + box.height}")
    }

    @Test
    fun `a short sidebar look-alike left of the panel is not the extraction target`() {
        // Auftrag 10 layout: the MATERIAL SELECTION box (dark-red strip + orange SETUP WORK
        // ORDER button, well under half the panel height) sits LEFT of the work-order panel —
        // it must not win the leftmost-is-newest rule.
        val img = frame(969, 1090)
        paintPanel(img, x = 16, y = 620, panelWidth = 360, panelHeight = 400) // sidebar box
        paintPanel(img, x = 400, y = 90, panelWidth = 460, panelHeight = 900) // the panel

        val target = Locate.locatePanel(img)

        assertTrue(target.x in 350..410, "x=${target.x} must be the panel, not the sidebar box")
    }

    @Test
    fun `terminal-area crop - location comes from the top-left strip of the image`() {
        val img = frame(914, 1053)
        paintPanel(img, x = 430, y = 100, panelWidth = 470, panelHeight = 830)

        val prepared = Locate.prepare(img, PanelBox(430, 80, 470, 860))

        assertEquals("vlm", prepared.cropMode)
        val loc = assertNotNull(prepared.locationImage, "a terminal-area crop carries the header")
        // Top-left strip (2/3 width × ~h/10), 2× upscaled and snapped to /32.
        assertTrue(loc.width % 32 == 0 && loc.height % 32 == 0)
        assertTrue(loc.width in (914 * 2 * 2 / 3 - 64)..(914 * 2 * 2 / 3 + 64), "width=${loc.width}")
        assertTrue(loc.height in (1053 / 10 * 2 - 64)..(1053 / 10 * 2 + 64), "height=${loc.height}")
    }

    @Test
    fun `headerNameRight keeps a long station name that runs past 2-3 width, dropping a right-side title`() {
        // Regression for Auftrag 19–22: "MIC-L1 SHALLOW FRONTIER STATION" runs past 2/3 of the
        // crop and the old fixed cut clipped it to "…STAT". The leftmost bright-text run must be
        // kept whole; a REFINEMENT title after the wide dark gap must still be dropped.
        val w = 600
        val strip = BufferedImage(w, 60, BufferedImage.TYPE_INT_RGB)
        val g = strip.createGraphics()
        try {
            g.color = dark
            g.fillRect(0, 0, w, 60)
            g.color = Color(230, 235, 240) // bright UI text (the station name), word-spaced glyphs
            var x = 20
            while (x < 470) { g.fillRect(x, 20, 14, 20); x += 22 } // ends ~468, past 2/3 (= 400)
            g.fillRect(540, 20, 50, 20) // a right-side title after a wide dark gap — must be excluded
        } finally {
            g.dispose()
        }

        val right = Locate.headerNameRight(strip, fallbackRight = w * 2 / 3)

        assertTrue(right > w * 2 / 3, "keeps the whole name past 2/3 width, got $right")
        assertTrue(right < 540, "drops the right-side title, got $right")
    }

    @Test
    fun `headerNameRight falls back to the given width when no bright text stands out`() {
        // A blank / synthetic strip (no readable name) keeps the historical 2/3-width crop — this
        // is what pins the existing terminal-area location-strip test at fallback width.
        val strip = BufferedImage(600, 60, BufferedImage.TYPE_INT_RGB)
        val g = strip.createGraphics()
        try {
            g.color = dark
            g.fillRect(0, 0, 600, 60)
        } finally {
            g.dispose()
        }

        assertEquals(400, Locate.headerNameRight(strip, fallbackRight = 400))
    }

    @Test
    fun `portrait location strip widens to hold a long station name`() {
        // End-to-end through prepare: a portrait terminal-area crop whose header name runs past
        // 2/3 width must yield a location strip wider than the pre-fix 2/3 cut (× the 2× upscale).
        val w = 850
        val h = 1000
        val img = frame(w, h)
        val g = img.createGraphics()
        try {
            g.color = Color(230, 235, 240)
            var x = 20
            val nameEnd = (w * 0.72).toInt() // past 2/3 (= 566)
            while (x < nameEnd) { g.fillRect(x, 30, 16, 26); x += 24 }
        } finally {
            g.dispose()
        }

        val prepared = Locate.prepare(img)
        val loc = assertNotNull(prepared.locationImage, "a portrait crop carries the header strip")

        assertTrue(loc.width > (w * 2 / 3) * 2 - 32, "strip clipped the long name: width=${loc.width}")
    }

    @Test
    fun `snap32 rounds to multiples of 32 with a floor of 32`() {
        assertEquals(1536, Locate.snap32(1536))
        assertEquals(1536, Locate.snap32(1530))
        assertEquals(1568, Locate.snap32(1553))
        assertEquals(32, Locate.snap32(1))
    }

    @Test
    fun `normalize hits the sweet spot and snaps dimensions`() {
        val img = frame(3840, 2160)

        val out = Locate.normalize(img)

        assertTrue(out.width % 32 == 0 && out.height % 32 == 0)
        assertEquals(Locate.TARGET_LONG_EDGE, maxOf(out.width, out.height))
    }

    @Test
    fun `normalize caps the upscale of pre-cropped panels`() {
        val img = frame(500, 900)

        val out = Locate.normalize(img)

        assertTrue(maxOf(out.width, out.height) <= Locate.PRECROP_MAX_DIM + 31, "cap ~${Locate.PRECROP_MAX_DIM}")
    }

    @Test
    fun `prepare routes pre-cropped input past Locate and skips the location strip`() {
        val img = frame(500, 1400)

        val prepared = Locate.prepare(img)

        assertEquals("precropped", prepared.cropMode)
        assertNull(prepared.panelBox)
        assertNull(prepared.locationImage)
    }

    @Test
    fun `prepare yields a location strip for full frames`() {
        val img = frame()
        paintPanel(img, x = 1000, y = 400, panelWidth = 900, panelHeight = 1400)

        val prepared = Locate.prepare(img)

        assertEquals("vlm", prepared.cropMode)
        assertNotNull(prepared.panelBox)
        assertNotNull(prepared.locationImage)
        assertTrue(prepared.readImage.width % 32 == 0)
    }

    @Test
    fun `gap-tolerant runs bridge text holes in the strip`() {
        val matches = BooleanArray(40)
        for (x in 0..10) matches[x] = true
        for (x in 14..30) matches[x] = true // a 3-px hole (strip text) must not split the run

        val runs = Locate.runs(matches, maxGap = 6)

        assertEquals(listOf(0 to 30), runs)
    }
}
