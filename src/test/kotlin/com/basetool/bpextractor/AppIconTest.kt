package com.basetool.bpextractor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the Windows installer icon.
 *
 * The packaging block wires `iconFile` only `if (icon.exists())` — a deleted or renamed
 * `app.ico` therefore does not fail the build, it silently ships an MSI whose Start-menu
 * entry, desktop shortcut and Apps-&-Features row all wear the generic Java cup. That is
 * invisible in every other test and only shows up after an install.
 *
 * The frame set matters as much as the file: Explorer, the taskbar and the installer each
 * ask for a different pixel size, and an ICO carrying only one large frame gets resampled
 * down by Windows, which smears the thin ring of the extractor mark. ImageIO ships no ICO
 * reader, so the directory is parsed by hand — the format is a 6-byte header followed by
 * 16-byte entries whose first two bytes are width and height (0 meaning 256).
 */
class AppIconTest {

    /** Sizes generated from `assets/basetool-extractor-icon-512.png`, smallest to largest. */
    private val expectedSizes = setOf(16, 24, 32, 48, 64, 128, 256)

    @Test
    fun appIconShipsOnTheClasspath() {
        val bytes = javaClass.getResourceAsStream("/app.ico")?.readBytes()
        assertNotNull(bytes, "app.ico must ship in src/main/resources — packaging skips it silently when absent")
        assertTrue(bytes.size > 1024, "app.ico looks truncated (${bytes.size} bytes)")
    }

    @Test
    fun appIconCarriesEveryFrameWindowsAsksFor() {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/app.ico")?.readBytes())

        // Header: 2 bytes reserved (0), 2 bytes type (1 = icon), 2 bytes image count — all little-endian.
        assertEquals(0, bytes[0].toInt() and 0xFF, "ICO header byte 0 must be 0")
        assertEquals(0, bytes[1].toInt() and 0xFF, "ICO header byte 1 must be 0")
        assertEquals(1, bytes[2].toInt() and 0xFF, "ICO type must be 1 (icon, not cursor)")
        val count = (bytes[4].toInt() and 0xFF) or ((bytes[5].toInt() and 0xFF) shl 8)

        val sizes = (0 until count).map { i ->
            val entry = 6 + i * 16
            // A stored 0 means 256 — the byte cannot hold 256 itself.
            val width = (bytes[entry].toInt() and 0xFF).let { if (it == 0) 256 else it }
            val height = (bytes[entry + 1].toInt() and 0xFF).let { if (it == 0) 256 else it }
            assertEquals(width, height, "frame $i is not square (${width}x$height)")
            width
        }.toSet()

        assertEquals(expectedSizes, sizes, "app.ico frame set drifted")
    }
}
