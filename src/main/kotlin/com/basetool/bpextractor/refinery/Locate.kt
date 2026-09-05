package com.basetool.bpextractor.refinery

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A panel candidate box in native pixels. */
data class PanelBox(val x: Int, val y: Int, val width: Int, val height: Int)

/** The prepared inputs for one screenshot: the normalized panel crop + optional location strip. */
data class PreparedImage(
    /** The normalized panel image handed to the VLM read. */
    val readImage: BufferedImage,
    /** The terminal-header strip (location read); null for pre-cropped input. */
    val locationImage: BufferedImage?,
    /** The native-pixel panel box; null when the input was pre-cropped. */
    val panelBox: PanelBox?,
    /** Contract `cropMode`: `vlm` (auto-located) or `precropped`. */
    val cropMode: String,
)

/**
 * The Locate → Normalize stages, ported from the Phase 0 spike harness (`normalize.py` — the
 * verified classical-CV approach; `PHASE0_FINDINGS.md` §3):
 *
 * - **Locate** finds work-order panels on a 1/4-scale frame via two colour anchors: the maroon
 *   SETUP tab strip (~RGB(72,49,45); gap-tolerant runs, width-similarity clustering) plus a CTA
 *   element below it. Pure luminance profiling does not work — the terminal interior is uniformly
 *   dark (measured mean 35–64 across the golden set).
 * - **Two terminal skins**, and both anchors moved between them. The sample corpus changes over
 *   from the *amber* skin to what its own header calls `// REFINERY SYSTEM C47.02` — the **C47**
 *   skin — between the captures of 2026-07-19 and 2026-07-20. Amber: orange CONFIRM, tab strip a
 *   solid bar across the panel header. C47: green CONFIRM ([isCtaGreen]), tab strip a diagonal
 *   hatch block over the panel's top-left ~0.37 (which is why [scaleDown] must box-filter). Three
 *   guards keep the extra candidates that costs us out: the top-edge banner ([TOP_BANNER_GUARD]),
 *   the world outside the terminal ([MAX_UI_SATURATION]) and the short sidebar look-alike.
 * - **Owner-confirmed domain rule (2026-06-10):** with several panels side by side, the NEWEST
 *   order is always the LEFTMOST — candidates are returned left to right and callers take the
 *   first; the VLM read's panel type is a validation, never a selection mechanism.
 * - **Three capture classes** (decided by shape, see [isPrecropped]): landscape full frames
 *   (locate + the verified full-frame header-strip geometry for the location read), portrait
 *   terminal-area crops — header + sidebar + panel, the Auftrag 10/12 class — (locate with a
 *   relaxed strip-width ceiling + location from the crop's top-left strip), and narrow portrait
 *   panel-only crops (skip Locate, no location anywhere — `cropMode = precropped`).
 * - **Normalize** always runs client-side (Ollama silently downscales > ~3.2 MP): crop from the
 *   native frame, resize to a long edge of [TARGET_LONG_EDGE] px (pre-cropped input capped at
 *   [PRECROP_MAX_DIM] — upscaling a ~500 px panel beyond ~2.4× only blurs), dimensions snapped
 *   to multiples of 32. AWT bicubic interpolation; a hand-rolled Lanczos-3 kernel was not needed
 *   on the golden set (plan §9 Phase 3 note).
 */
object Locate {

    /** The VLM's sweet spot for the long edge (master plan §9 / Phase 0). */
    const val TARGET_LONG_EDGE = 1536

    /** Upscale cap for pre-cropped panels. */
    const val PRECROP_MAX_DIM = 1200

    /** Verified 4K fallback geometry (x, y, w, h) when no colour-anchor candidate is found. */
    private val PANEL_4K = PanelBox(950, 350, 920, 1500)

    /** Terminal-header strip holding the location, 4K reference coordinates. */
    private val LOCATION_4K = PanelBox(250, 200, 900, 220)

    private const val SCALE = 4

    /**
     * Frames wider than this aspect are treated as ultrawide ([isUltrawide]): the orange ship-hull
     * fills the wide side margins, so the per-panel colour search can mis-box the work-order panel
     * and the fixed full-frame header geometry misses the station name. It sits above 16:9 (≈ 1.78)
     * and 16:10 (1.6) so ordinary captures are untouched, and below 21:9 (≈ 2.33) so every true
     * ultrawide — up to the 32:9 frames this was verified on (≈ 3.56, the Auftrag 8/9/16 orders) —
     * is covered. Drives the header strip ([prepare]) and the pipeline's terminal-extent rescue.
     */
    private const val ULTRAWIDE_ASPECT = 2.0

    /**
     * Maroon runs starting within this fraction of the capture height of its TOP EDGE are the
     * terminal's system banner, not a tab strip — see the guard in [locatePanels]. Measured: the
     * banner sits at 0–3.2 % of the capture height across the whole sample set, while the lowest
     * real tab strip sits at 7.8 % — portrait crops, full frames and 32:9 frames alike.
     */
    private const val TOP_BANNER_GUARD = 0.06

    /**
     * How far right of the tab strip's left edge the CTA search may reach, in strip widths — the
     * panel-width proxy. See the bound in [locatePanels]: the C47 UI's strip is a small hatched
     * block (~0.37 of the panel width) where the amber UI's spans the whole panel header.
     */
    private const val CTA_SEARCH_STRIPS = 2.9

    /** [CTA_SEARCH_STRIPS] on ultrawide frames — the verified narrow window, see [locatePanels]. */
    private const val CTA_SEARCH_STRIPS_ULTRAWIDE = 1.9

    /**
     * Mean per-pixel saturation ceiling for a panel candidate — see the guard in [locatePanels].
     * Measured across the whole sample set: 10–17 on every work-order panel, 28 on the warmest
     * (ambient-lit) real panel, 51 on the orange-lit rock face that the colour anchors otherwise
     * accept as the leftmost candidate on a 32:9 frame.
     */
    private const val MAX_UI_SATURATION = 35.0

    /**
     * How far down the frame a tab strip may sit to still qualify for the CTA-less rescue in
     * [locatePanels]. The panel hangs below its strip and fills the capture, so a real strip is in
     * the top third; the sample set's rescue case has it at 11 % and its sidebar decoys at 35 % and
     * 80 %.
     */
    private const val RESCUE_MAX_STRIP_TOP = 0.35

    /**
     * A pre-cropped panel image is small and NARROW portrait (golden-set panel-only crops are
     * ~480–520×915–940, w/h ≈ 0.52–0.55). Squarer portrait captures of the whole terminal area
     * — header bar + sidebar + panel, the Auftrag 10/12 class at ~910–970×1050–1090
     * (w/h ≈ 0.84–0.89) — are NOT pre-cropped: they carry a locatable panel AND the location
     * header, so they must go through Locate like a full frame.
     */
    fun isPrecropped(width: Int, height: Int): Boolean =
        width < 1000 && height > width && width * 10 < height * 7

    /** The SETUP tab strip chrome: dark desaturated red, ~RGB(72,49,45) at 4K. */
    internal fun isMaroon(r: Int, g: Int, b: Int): Boolean =
        r in 55..115 && g <= 75 && b <= 70 && r - g >= 14 && r - b >= 16

    /** The CONFIRM / GET QUOTE button fill in the amber refinery UI: bright KRT-style orange. */
    internal fun isCtaOrange(r: Int, g: Int, b: Int): Boolean =
        r >= 170 && g in 110..200 && b <= 110 && r - b >= 90

    /**
     * The CONFIRM button fill in the **C47 refinery UI**: a yellow-green. It is NOT orange — `r`
     * sits under [isCtaOrange]'s 170 floor — so before this predicate existed the whole capture
     * class had no CTA anchor at all and every panel fell back to the fixed 4K geometry (Auftrag
     * 19–34). The band has to hold both exposures the sample set contains: ~RGB(154,182,59) on the
     * bright captures (Auftrag 23–34) and ~RGB(117,126,54) on the dim ones (Auftrag 19–22), where
     * green leads red by only ~9. Kept green-dominant (`g > r`) so it can never swallow the amber
     * fill, and blue-poor so the terminal's cyan UI text stays out.
     */
    internal fun isCtaGreen(r: Int, g: Int, b: Int): Boolean =
        g in 110..220 && r in 90..200 && b <= 110 && g - b >= 55 && g - r >= 6

    /** Either CTA fill — the amber UI's orange or the C47 UI's green. */
    internal fun isCta(r: Int, g: Int, b: Int): Boolean = isCtaOrange(r, g, b) || isCtaGreen(r, g, b)

    /**
     * Find all work-order panel candidates, left to right, in native pixels. Empty when the
     * colour anchors match nothing (caller falls back to [locatePanel]'s fixed geometry).
     */
    fun locatePanels(img: BufferedImage): List<PanelBox> {
        val small = scaleDown(img, SCALE)
        val w = small.width
        val h = small.height
        // How far the CTA search may look, and whether it may union runs above the bottom-most CTA
        // row. Both are widened everywhere EXCEPT on ultrawide frames: those are the ones that put
        // several panels side by side under an orange ship hull, the narrow search is what was
        // verified there (and their failure mode has its own terminal-extent rescue in the
        // pipeline), while the wide search is what the C47 UI's small hatched strip needs.
        val wideCta = !isUltrawide(img)
        val maxGap = max(6, w / 80)
        // Strip-width ceiling, frame-relative: on a (landscape) full frame the tab strip never
        // nears half the screen, but on a portrait terminal-area crop (Auftrag 10/12 class) the
        // panel — and its strip — legitimately spans well past 45% of the image width.
        val maxRunW = (if (h > w) 0.75 else 0.45) * w

        // 1. Per row: gap-tolerant maroon runs of plausible strip width.
        data class RowRun(val y: Int, val x0: Int, val x1: Int)
        val rowRuns = mutableListOf<RowRun>()
        for (y in 0 until h) {
            val matches = BooleanArray(w)
            for (x in 0 until w) {
                val rgb = small.getRGB(x, y)
                matches[x] = isMaroon((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
            }
            for ((x0, x1) in runs(matches, maxGap)) {
                val width = x1 - x0
                var density = 0
                for (x in x0..x1) if (matches[x]) density++
                if (width >= 0.08 * w && width <= maxRunW && density.toDouble() / (width + 1) >= 0.35) {
                    rowRuns += RowRun(y, x0, x1)
                }
            }
        }

        // 2. Cluster runs that overlap horizontally, sit on consecutive rows AND share a similar
        //    width (environment noise above the strip must not chain in).
        val clusters = mutableListOf<MutableList<RowRun>>()
        for (run in rowRuns.sortedWith(compareBy({ it.y }, { it.x0 }))) {
            var placed = false
            for (cluster in clusters) {
                val last = cluster.last()
                val sameWidth = kotlin.math.abs((run.x1 - run.x0) - (last.x1 - last.x0)) <= 0.4 * max(1, last.x1 - last.x0)
                val overlaps = min(run.x1, last.x1) - max(run.x0, last.x0) > 0.5 * (run.x1 - run.x0)
                if (run.y - last.y <= 3 && sameWidth && overlaps) {
                    cluster += run
                    placed = true
                    break
                }
            }
            if (!placed) clusters += mutableListOf(run)
        }

        // 3. Reduce each cluster to a strip, left to right. A run this close to the capture's TOP
        //    EDGE is the terminal's own system banner ("// REFINERY SYSTEM C47.02", a dark-red
        //    gradient bar), never a panel tab strip: the SETUP strip always has the panel border
        //    and the WORK ORDER bar above it, and a capture that began ON the strip would be a
        //    panel-only crop, which never reaches Locate ([isPrecropped]). Without the guard the
        //    banner forms a wide candidate that — being the leftmost — wins the newest-order rule
        //    and crops the STATION PROFILE sidebar instead of the panel (Auftrag 19–34).
        data class Strip(val x0: Int, val x1: Int, val top: Int, val bottom: Int)
        val allStrips = clusters.mapNotNull { cluster ->
            if (cluster.size < 2) return@mapNotNull null // a real strip is several rows tall even at 1/4 scale
            val ys = cluster.map { it.y }
            val stripTop = ys.min()
            val stripBottom = ys.max()
            if (stripBottom - stripTop > 12) return@mapNotNull null // too tall to be a tab strip
            Strip(
                x0 = cluster.map { it.x0 }.sorted()[cluster.size / 2],
                x1 = cluster.map { it.x1 }.sorted()[cluster.size / 2],
                top = stripTop,
                bottom = stripBottom,
            )
        }.sortedBy { it.x0 }
        // Drop the banner only while something else is left: the guard is about telling the banner
        // apart from the tab strip BELOW it, and a capture whose only strip hugs the top edge is
        // far more likely a tightly cropped panel than a banner on its own.
        val strips = allStrips.filterNot { it.top < TOP_BANNER_GUARD * h }.ifEmpty { allStrips }

        // 4. Turn each strip into a panel candidate, confirmed by a CTA below it. The maroon
        //    strip covers only the panel's LEFT part; the CTA is right-aligned — search to the
        //    right of the strip too, with an absolute run threshold (small/distant panels have
        //    small buttons). Do NOT scan the whole body width: with two panels side by side that
        //    bleeds into the neighbour's progress bar (verified spike regression). The bound is a
        //    multiple of the strip width — the panel-width proxy — and it has to be generous,
        //    because the strip is only a good proxy in the amber UI, where it spans the panel
        //    header end to end. The C47 UI draws a small hatched block at the panel's top-left,
        //    ~0.37 of the panel width; at the old 1.9× the search stopped short of CONFIRM and the
        //    crop cut the YIELD and REFINE columns off (Auftrag 23–34).
        fun candidate(strip: Strip, requireCta: Boolean): PanelBox? {
            val (x0, x1, stripTop, stripBottom) = strip
            val stripW = x1 - x0
            val reach = if (wideCta) CTA_SEARCH_STRIPS else CTA_SEARCH_STRIPS_ULTRAWIDE
            val searchX1 = min(w - 1, x0 + (stripW * reach).toInt())
            //    The panel's BOTTOM is the lowest CTA row; its RIGHT EDGE is the widest CTA run
            //    anywhere between the strip and that row. One row is not enough: the CANCEL and
            //    CONFIRM fills break into separate runs at the button's border rows, so the lowest
            //    row alone can stop ~40 px short of the panel and cut the REFINE column off
            //    (Auftrag 32). Scanning up to the strip also picks up the C47 UI's method selector,
            //    whose orange outline spans the panel edge to edge.
            var ctaRow: Int? = null
            var ctaRight = x1
            for (y in h - 1 downTo stripBottom + 6) {
                val matches = BooleanArray(searchX1 - x0 + 1)
                for (x in x0..searchX1) {
                    val rgb = small.getRGB(x, y)
                    matches[x - x0] = isCta((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)
                }
                val ctaRuns = runs(matches, 2).filter { it.second - it.first >= max(6, stripW / 12) }
                if (ctaRuns.isNotEmpty()) {
                    if (ctaRow == null) ctaRow = y
                    ctaRight = max(ctaRight, x0 + ctaRuns.maxOf { it.second })
                    if (!wideCta) break
                }
            }
            if (ctaRow == null && requireCta) return null
            // Without a CTA the panel runs to the bottom edge and only the strip bounds its width.
            val bottom = ctaRow ?: (h - 1)
            val right = if (ctaRow != null) ctaRight else searchX1
            // 5. The candidate must be TERMINAL, not world. Both colour anchors also occur in the
            //    orange-lit hangar around the terminal — a rock face under the refinery's work
            //    lights matches maroon, its highlights match the CTA — and on a 32:9 frame such a
            //    patch sits LEFT of the real panel, where the newest-order rule would hand it to
            //    the model (Auftrag 9). The terminal interior is a desaturated dark UI, the world
            //    is not: mean per-pixel saturation is 10–28 across every real panel of the sample
            //    set and 51 on that rock face. Luminance cannot do this — the scenery is dark too.
            if (meanSaturation(small, x0, stripTop, min(w - 1, right), bottom) > MAX_UI_SATURATION) return null
            val margin = max(3, stripW / 40)
            val top = max(0, stripTop - 4 * margin) // include the WORK ORDER bar above
            val bot = min(h - 1, bottom + 3 * margin)
            return PanelBox(
                x = max(0, x0 - margin) * SCALE,
                y = top * SCALE,
                width = (right - x0 + 2 * margin) * SCALE,
                height = (bot - top) * SCALE,
            )
        }

        val boxes = strips.mapNotNull { candidate(it, requireCta = true) }.toMutableList()
        if (boxes.isEmpty()) {
            // 6. CTA-less rescue. A capture whose bottom edge cuts the CANCEL/CONFIRM row off has
            //    no CTA to confirm with (Auftrag 23), and the fixed-geometry fallback is a much
            //    worse guess than the strip we did find. Only when NOTHING else was confirmed, and
            //    only for a strip in the upper part of the frame — a tab strip sits at the top of a
            //    panel that then fills the capture, so a maroon run down in the sidebar cannot
            //    stand in for one. The saturation guard still applies.
            boxes += strips.filter { it.top < RESCUE_MAX_STRIP_TOP * h }
                .mapNotNull { candidate(it, requireCta = false) }
        }

        // Drop sidebar look-alikes: the MATERIAL SELECTION box mimics BOTH anchors (dark-red
        // USER DETAILS strip above an orange SETUP WORK ORDER button — the Auftrag 10 false
        // positive that, as the leftmost candidate, would win the newest-order rule) but is
        // far SHORTER than any work-order panel. Relative to the tallest candidate so the
        // rule holds at any capture distance; a lone candidate is never dropped.
        val tallest = boxes.maxOfOrNull { it.height } ?: 0
        boxes.removeAll { it.height < 0.6 * tallest }

        // Merge near-duplicate candidates (overlapping clusters from split strips), keep
        // left-to-right order — the leftmost candidate is the extraction target.
        boxes.sortBy { it.x }
        val merged = mutableListOf<PanelBox>()
        for (box in boxes) {
            val last = merged.lastOrNull()
            if (last != null && box.x < last.x + last.width * 0.5) {
                val x0n = min(last.x, box.x)
                val y0n = min(last.y, box.y)
                merged[merged.size - 1] = PanelBox(
                    x = x0n,
                    y = y0n,
                    width = max(last.x + last.width, box.x + box.width) - x0n,
                    height = max(last.y + last.height, box.y + box.height) - y0n,
                )
            } else {
                merged += box
            }
        }
        return merged
    }

    /**
     * The extraction target: the LEFTMOST candidate (= newest order, owner rule 2026-06-10), or
     * null when the colour anchors matched nothing — the caller decides whether to surface the
     * miss before falling back to [fallbackPanel] (the fixed geometry is a guess, not a find).
     */
    fun locatePanelOrNull(img: BufferedImage): PanelBox? = locatePanels(img).firstOrNull()

    /**
     * The verified 4K geometry scaled to the frame. Position scales with each axis; the panel
     * SIZE scales with the height only — on an ultrawide (e.g. 5120×1440) the game renders the
     * panel at the 16:9 size, so width-proportional scaling would distort the crop.
     */
    fun fallbackPanel(img: BufferedImage): PanelBox {
        val fx = img.width / 3840.0
        val fy = img.height / 2160.0
        return PanelBox(
            (PANEL_4K.x * fx).toInt(),
            (PANEL_4K.y * fy).toInt(),
            (PANEL_4K.width * fy).toInt().coerceAtMost(img.width - (PANEL_4K.x * fx).toInt()),
            (PANEL_4K.height * fy).toInt(),
        )
    }

    /** [locatePanelOrNull] with the silent [fallbackPanel] — kept for callers without a UI. */
    fun locatePanel(img: BufferedImage): PanelBox = locatePanelOrNull(img) ?: fallbackPanel(img)

    /** Snap a dimension to the nearest multiple of 32 (≥ 32). */
    fun snap32(v: Int): Int = max(32, (v / 32.0).roundToInt() * 32)

    /**
     * Resize so the long edge hits the model sweet spot, dimensions snapped to /32. The
     * [PRECROP_MAX_DIM] cap applies only to small images of unknown provenance (= user
     * pre-cropped panels); panels WE crop out of a larger frame go to the full
     * [TARGET_LONG_EDGE] via the explicit-target overload — capping those starves them of
     * resolution the source frame actually has (the Auftrag 10 digit regression).
     */
    fun normalize(img: BufferedImage): BufferedImage {
        val longEdge = max(img.width, img.height)
        val target = if (longEdge < 1000) min(TARGET_LONG_EDGE, PRECROP_MAX_DIM) else TARGET_LONG_EDGE
        return normalize(img, target)
    }

    /** [normalize] to an explicit long-edge [target]. */
    fun normalize(img: BufferedImage, target: Int): BufferedImage {
        val factor = target.toDouble() / max(img.width, img.height)
        val nw = snap32(kotlin.math.ceil(img.width * factor).toInt())
        val nh = snap32(kotlin.math.ceil(img.height * factor).toInt())
        return resize(img, nw, nh)
    }

    /** Locate + Normalize one screenshot into the VLM-ready inputs. */
    fun prepare(img: BufferedImage): PreparedImage {
        val box = if (isPrecropped(img.width, img.height)) null else locatePanel(img)
        return prepare(img, box)
    }

    /**
     * Normalize with a pre-computed panel [box] (null = pre-cropped input) — split out so the
     * pipeline can report Locate and Normalize as separate progress stages (design spec §5.3).
     */
    fun prepare(img: BufferedImage, box: PanelBox?): PreparedImage {
        if (box == null) {
            return PreparedImage(normalize(img), null, null, "precropped")
        }
        val panel = img.getSubimage(
            box.x.coerceIn(0, img.width - 1),
            box.y.coerceIn(0, img.height - 1),
            box.width.coerceAtMost(img.width - box.x),
            box.height.coerceAtMost(img.height - box.y),
        )
        val ultrawideExtent = if (isUltrawide(img)) terminalExtentX(img) else null
        val loc = if (ultrawideExtent != null) {
            // Ultrawide: the fixed full-frame header geometry lands on the hull, and [box] is the
            // per-panel crop (its top is the panel, not the header). The station name sits at the
            // top-left of the whole TERMINAL — found by its content extent — but below the frame top
            // (status bar + bezel above it), so take a taller band (~22% height) than the portrait
            // path's thin strip. 2/3 of the extent width drops the REFINEMENT CENTER / PROCESSING
            // titles. Verified to read CHECKMATE / LEVSKI / CRU-L1 … on the Auftrag 8/9/16 captures.
            val (ex0, ex1) = ultrawideExtent
            val stripW = ((ex1 - ex0) * 2 / 3).coerceAtMost(img.width - ex0)
            val stripH = (img.height * 0.22).toInt().coerceIn(1, img.height)
            img.getSubimage(ex0, 0, stripW, stripH)
        } else if (img.height > img.width) {
            // Portrait = a terminal-area crop (the game only renders landscape frames): the fixed
            // full-frame header geometry does not apply — the header bar with the location name
            // sits at the very top of the crop, the name on its left. Cut the strip just past the
            // station name (its leftmost bright-text run, [headerNameRight]) so a LONG name is kept
            // whole while a right-side REFINEMENT title is still dropped — the old fixed 2/3-width
            // cut clipped "MIC-L1 SHALLOW FRONTIER STATION" to "…STAT" (Auftrag 19–22).
            val stripH = max(40, img.height / 10).coerceAtMost(img.height)
            val header = img.getSubimage(0, 0, img.width, stripH)
            header.getSubimage(0, 0, headerNameRight(header, img.width * 2 / 3), stripH)
        } else {
            val fx = img.width / 3840.0
            val fy = img.height / 2160.0
            img.getSubimage(
                (LOCATION_4K.x * fx).toInt(),
                (LOCATION_4K.y * fy).toInt(),
                (LOCATION_4K.width * fx).toInt().coerceAtMost(img.width - (LOCATION_4K.x * fx).toInt()),
                (LOCATION_4K.height * fy).toInt().coerceAtMost(img.height - (LOCATION_4K.y * fy).toInt()),
            )
        }
        // The location strip is small text — a 2× upscale puts it in the model's sweet spot.
        val locNorm = resize(loc, snap32(loc.width * 2), snap32(loc.height * 2))
        // Our own crop from a known frame: aim at the sweet spot but never upscale beyond
        // ~1.4× — blowing a small panel up further degrades the digit reads (measured on the
        // Auftrag 12 terminal-area crop: 1.65× turned a clean 510 into 518), while capping at
        // PRECROP_MAX_DIM starves it below what the source frame carries (Auftrag 10, 105→185).
        val readTarget = min(TARGET_LONG_EDGE, (max(panel.width, panel.height) * 1.4).toInt())
        return PreparedImage(normalize(panel, readTarget), locNorm, box, "vlm")
    }

    /** A bright near-white / cyan UI glyph pixel — the terminal's text, never the orange hull. */
    private fun isUiText(r: Int, g: Int, b: Int): Boolean = r > 170 && g > 170 && b > 150

    /**
     * The right edge (strip-local px) to cut a portrait header location strip at: just past the
     * station name, so the crop keeps the WHOLE name yet drops any right-side title (REFINEMENT …).
     * The name is the LEFTMOST run of bright UI-text columns ([isUiText]) — word spaces inside a
     * long name are bridged (gap ~ 1/25 of the strip width), the wide dark gap before a right-side
     * title is not, and stray sub-name-width runs (single glyphs / noise) are skipped. Falls back
     * to [fallbackRight] when no name text stands out, preserving the historical 2/3-width crop on
     * blank strips. Fixes the long-name clip that cut "MIC-L1 SHALLOW FRONTIER STATION" to "…STAT"
     * (Auftrag 19–22); short names (LEVSKI) and their right-side REFINEMENT title are unaffected.
     */
    internal fun headerNameRight(strip: BufferedImage, fallbackRight: Int): Int {
        val w = strip.width
        val h = strip.height
        val col = IntArray(w)
        for (x in 0 until w) {
            var c = 0
            for (y in 0 until h) {
                val rgb = strip.getRGB(x, y)
                if (isUiText((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)) c++
            }
            col[x] = c
        }
        val peak = col.maxOrNull() ?: 0
        if (peak < 3) return fallbackRight
        val threshold = max(2, (peak * 0.10).toInt())
        val mask = BooleanArray(w) { col[it] >= threshold }
        val nameRun = runs(mask, max(8, w / 25)).firstOrNull { it.second - it.first >= w / 40 }
            ?: return fallbackRight
        val margin = max(8, w / 40)
        return min(w, nameRun.second + margin + 1)
    }

    /**
     * The terminal's horizontal content extent `(x0, x1)` on an ultrawide frame, in native pixels,
     * or null when no UI text stands out. The signal is the per-column count of bright UI-text
     * pixels ([isUiText]) — hull-independent, unlike the maroon/orange anchors. The terminal is the
     * WIDEST contiguous run of text-rich columns (small inter-column gaps bridged); a stray wall
     * console or holo-display elsewhere on the frame forms its own, narrower run and is dropped.
     */
    internal fun terminalExtentX(img: BufferedImage): Pair<Int, Int>? {
        val small = scaleDown(img, SCALE)
        val w = small.width
        val h = small.height
        val col = IntArray(w)
        for (x in 0 until w) {
            var c = 0
            for (y in 0 until h) {
                val rgb = small.getRGB(x, y)
                if (isUiText((rgb shr 16) and 0xFF, (rgb shr 8) and 0xFF, rgb and 0xFF)) c++
            }
            col[x] = c
        }
        val peak = col.maxOrNull() ?: 0
        if (peak < 4) return null
        val threshold = max(2, (peak * 0.04).toInt())
        val mask = BooleanArray(w) { col[it] >= threshold }
        // Bridge the gaps between the terminal's columns (sidebar | SETUP | PROCESSING) but not the
        // far wider dark margin out to a wall console: ~1/16 of the frame width.
        val best = runs(mask, max(8, w / 16)).maxByOrNull { it.second - it.first } ?: return null
        val margin = max(8, (best.second - best.first) / 25)
        val x0 = max(0, best.first - margin) * SCALE
        val x1 = (min(w - 1, best.second + margin) + 1) * SCALE
        return x0 to min(img.width, x1)
    }

    /** True for ultrawide / multi-monitor frames (aspect > [ULTRAWIDE_ASPECT]) — see that const. */
    fun isUltrawide(img: BufferedImage): Boolean = img.width > img.height * ULTRAWIDE_ASPECT

    /**
     * The whole terminal as one full-height [PanelBox] (its [terminalExtentX] width, y `0..height`),
     * or null when no UI text stands out. The pipeline's ultrawide rescue ([RefineryPipeline]) hands
     * this to the VLM when the per-panel crop reads no numbers: the orange hull breaks the per-panel
     * search on some 32:9 frames (Auftrag 16 boxed the STATION-PROFILE sidebar, clipping the number
     * columns), but the model reads the SETUP panel out of the full terminal reliably — the same way
     * the portrait terminal-area captures work. NOT used as the primary crop: where the per-panel
     * search already succeeds (Auftrag 8/9), its tighter, higher-resolution crop reads the digits
     * better, so the rescue only fires when the first read came back empty-handed.
     */
    fun terminalExtentBox(img: BufferedImage): PanelBox? =
        terminalExtentX(img)?.let { (x0, x1) -> PanelBox(x0, 0, x1 - x0, img.height) }

    /**
     * Mean per-pixel saturation (max channel − min channel) over an inclusive box of [small] —
     * the "is this UI or is this the world" test, see the guard in [locatePanels]. Subsampled
     * every other pixel; the signal is a bulk property, not a per-pixel one.
     */
    private fun meanSaturation(small: BufferedImage, x0: Int, y0: Int, x1: Int, y1: Int): Double {
        var sum = 0L
        var n = 0
        var y = y0
        while (y <= y1) {
            var x = x0
            while (x <= x1) {
                val rgb = small.getRGB(x, y)
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                sum += (max(r, max(g, b)) - min(r, min(g, b))).toLong()
                n++
                x += 2
            }
            y += 2
        }
        return if (n == 0) 0.0 else sum.toDouble() / n
    }

    /** Gap-tolerant contiguous runs over a boolean row (gaps = strip text holes). */
    internal fun runs(matches: BooleanArray, maxGap: Int): List<Pair<Int, Int>> {
        val result = mutableListOf<Pair<Int, Int>>()
        var start = -1
        var last = -1
        for (x in matches.indices) {
            if (matches[x]) {
                if (start < 0) start = x
                last = x
            } else if (start >= 0 && x - last > maxGap) {
                result += start to last
                start = -1
                last = -1
            }
        }
        if (start >= 0) result += start to last
        return result
    }

    /**
     * Downscale by an exact integer [factor] with a BOX filter — the mean of each factor×factor
     * block. Deliberately not the AWT bicubic [resize] used everywhere else: on a 4× reduction
     * bicubic point-samples, and it aliases the C47 refinery UI's tab strip away, because that
     * strip is a DIAGONAL HATCH rather than the amber UI's solid fill. Measured on the hatch
     * block: 83 % of the 1/4-scale pixels match [isMaroon] under a box filter, 25 % under
     * bicubic — far below the run-density floor, so the whole capture class located nothing and
     * fell back to the fixed 4K geometry (Auftrag 19–34). A solid strip averages to itself, so
     * the amber captures are unaffected. Alpha is ignored: screenshots are opaque.
     */
    private fun scaleDown(img: BufferedImage, factor: Int): BufferedImage {
        // Smaller than one block in either axis: no block to average, and the loops below would
        // read past the source. Nothing real gets here, but bicubic answers it without a crash.
        if (img.width < factor || img.height < factor) {
            return resize(img, max(1, img.width / factor), max(1, img.height / factor))
        }
        val w = img.width / factor
        val h = img.height / factor
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val src = IntArray(img.width)
        val r = IntArray(w)
        val g = IntArray(w)
        val b = IntArray(w)
        val n = factor * factor
        for (by in 0 until h) {
            r.fill(0)
            g.fill(0)
            b.fill(0)
            for (dy in 0 until factor) {
                img.getRGB(0, by * factor + dy, img.width, 1, src, 0, img.width)
                for (bx in 0 until w) {
                    val base = bx * factor
                    for (dx in 0 until factor) {
                        val p = src[base + dx]
                        r[bx] += (p shr 16) and 0xFF
                        g[bx] += (p shr 8) and 0xFF
                        b[bx] += p and 0xFF
                    }
                }
            }
            for (bx in 0 until w) {
                out.setRGB(bx, by, ((r[bx] / n) shl 16) or ((g[bx] / n) shl 8) or (b[bx] / n))
            }
        }
        return out
    }

    private fun resize(img: BufferedImage, w: Int, h: Int): BufferedImage {
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            g.drawImage(img, 0, 0, w, h, null)
        } finally {
            g.dispose()
        }
        return out
    }
}
