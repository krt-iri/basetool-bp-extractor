package com.basetool.bpextractor

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlueprintParserTest {

    private fun sampleFile(): File = resource("sample.log")

    /** A synthetic log from a German-localised client — see [BlueprintParser] on localised labels. */
    private fun germanSampleFile(): File = resource("sample-de.log")

    private fun resource(name: String): File {
        val url = requireNotNull(javaClass.classLoader.getResource(name)) {
            "$name test resource missing"
        }
        return File(url.toURI())
    }

    /** Parse ad-hoc log lines through a throwaway file, optionally with non-default patterns. */
    private fun parseLines(
        vararg lines: String,
        patterns: List<Regex> = BlueprintParser.BUILT_IN_PATTERNS,
    ): List<com.basetool.bpextractor.model.BlueprintEvent> {
        val tmp = File.createTempFile("lines", ".log")
        tmp.deleteOnExit()
        tmp.writeText(lines.joinToString("\n"))
        return BlueprintParser.parseFile(tmp, patterns).blueprints
    }

    @Test
    fun `parses exactly one event per blueprint, ignoring the duplicate follow-up lines`() {
        val result = BlueprintParser.parseFile(sampleFile())
        // 5 distinct blueprints; the Yubarev "Mirage" Pistol appears on 4 lines
        // (Added + bare echo + Next + StartFade + Remove) but must count once.
        assertEquals(5, result.blueprints.size)
    }

    @Test
    fun `extracts item name with embedded quotes`() {
        val bp = BlueprintParser.parseFile(sampleFile()).blueprints
        assertTrue(bp.any { it.productName == "Yubarev \"Mirage\" Pistol" })
    }

    @Test
    fun `trims trailing space in item name`() {
        val bp = BlueprintParser.parseFile(sampleFile()).blueprints
        assertTrue(bp.any { it.productName == "Antium Legs Moss Camo" })
        assertFalse(bp.any { it.productName.endsWith(" ") })
    }

    @Test
    fun `handles slashes and parentheses in names`() {
        val bp = BlueprintParser.parseFile(sampleFile()).blueprints
        assertTrue(bp.any { it.productName == "Sth/2/C Cirrus" })
        assertTrue(bp.any { it.productName == "Yubarev Pistol Battery (10 cap)" })
    }

    @Test
    fun `captures timestamp, notification id and queue size`() {
        val bp = BlueprintParser.parseFile(sampleFile()).blueprints
            .first { it.productName == "Yubarev \"Mirage\" Pistol" }
        assertEquals("2026-03-26T16:49:31.050Z", bp.receivedAt)
        assertEquals(19, bp.notificationId)
        assertEquals(2, bp.queueSize)
    }

    @Test
    fun `resolves player handle from the character-status line`() {
        val result = BlueprintParser.parseFile(sampleFile())
        val player = assertNotNull(result.player)
        assertEquals("greluc", player.handle)
        assertTrue(result.blueprints.all { it.player == "greluc" })
    }

    @Test
    fun `categorizes items by name`() {
        assertEquals("Weapon", BlueprintParser.categorize("Yubarev \"Mirage\" Pistol"))
        assertEquals("Ammo", BlueprintParser.categorize("S71 Rifle Magazine (30 cap)"))
        assertEquals("MiningTool", BlueprintParser.categorize("Lancet MH2 Mining Laser"))
        assertEquals("Armor", BlueprintParser.categorize("Palatino Core Daystar"))
        assertEquals("Other", BlueprintParser.categorize("Norfield"))
    }

    @Test
    fun `categorize matches keywords on word boundaries only`() {
        // Keywords inside longer words must not hit: "gun" in Gungnir, "core" in Scored.
        assertEquals("Other", BlueprintParser.categorize("Gungnir"))
        assertEquals("Other", BlueprintParser.categorize("Scored Plate"))
        // Hyphen/slash-separated words still count as boundaries.
        assertEquals("Armor", BlueprintParser.categorize("ADP-mk4 Core Woodland"))
    }

    @Test
    fun `reports cumulative byte progress while streaming`() {
        val file = sampleFile()
        val reports = mutableListOf<Long>()
        BlueprintParser.parseFile(file) { reports += it }
        assertTrue(reports.isNotEmpty())
        assertEquals(reports.sorted(), reports) // monotonically increasing
        assertEquals(file.length(), reports.last())
    }

    @Test
    fun `extracts build number from file name`() {
        // parseFile reads the real file name; build only resolves for SC-named files.
        val tmp = File.createTempFile("Game Build(11518367) 26 Mar 26 (17 24 58)", ".log")
        tmp.deleteOnExit()
        sampleFile().copyTo(tmp, overwrite = true)
        val bp = BlueprintParser.parseFile(tmp).blueprints.first()
        assertEquals("11518367", bp.gameBuild)
    }

    // --- localised client (the label is translated, the line around it is not) ------

    @Test
    fun `reads blueprints from a German-localised client`() {
        val result = BlueprintParser.parseFile(germanSampleFile())
        assertEquals(
            listOf("Attrition-5 Repeater", "Scalpel Sniper Rifle Magazine (12 Schuss)"),
            result.blueprints.map { it.productName },
        )
    }

    @Test
    fun `the German label counts once despite the follow-up notification lines`() {
        val bp = BlueprintParser.parseFile(germanSampleFile()).blueprints
        assertEquals(1, bp.count { it.productName == "Attrition-5 Repeater" })
    }

    @Test
    fun `a localised non-blueprint notification of the same shape is not picked up`() {
        // The prefilter now stops at `Added notification "`, so the label whitelist is the only
        // thing keeping other notification kinds out — in every language.
        val bp = BlueprintParser.parseFile(germanSampleFile()).blueprints
        assertFalse(bp.any { it.productName.contains("Aberdeen") })
        assertFalse(BlueprintParser.parseFile(sampleFile()).blueprints.any { it.productName.contains("Aberdeen") })
    }

    @Test
    fun `the label is matched case-insensitively, the engine literal around it is not`() {
        val tmp = File.createTempFile("case", ".log")
        tmp.deleteOnExit()
        tmp.writeText(
            // Label in a different case: still a blueprint.
            """<2026-05-02T20:11:04.132Z> [Notice] <SHUDEvent_OnNotification> Added notification """ +
                """"BAUPLAN Erhalten: Attrition-5 Repeater: " [136] to queue. NEW QUEUE SIZE: 4""" + "\n" +
                // `Added notification "` is a compile-time literal in the game binary; a line that
                // does not carry it verbatim is not an SC notification line at all.
                """<2026-05-02T20:12:04.132Z> [Notice] <SHUDEvent_OnNotification> ADDED NOTIFICATION """ +
                """"Received Blueprint: Ghost Rifle: " [137] to queue. New queue size: 1""",
        )
        val bp = BlueprintParser.parseFile(tmp).blueprints
        assertEquals(listOf("Attrition-5 Repeater"), bp.map { it.productName })
        assertEquals(4, bp.single().queueSize)
    }

    @Test
    fun `the Swiss German variant of the same translation is recognised`() {
        // live-CH in rjcncpt/StarCitizen-Deutsch-INI ships a different wording for the same key.
        val bp = parseLines(
            """<2026-05-02T20:11:04.132Z> [Notice] <SHUDEvent_OnNotification> Added notification """ +
                """"Bauplan überchoo: Attrition-5 Repeater: " [136] to queue. New queue size: 1""",
        )
        assertEquals(listOf("Attrition-5 Repeater"), bp.map { it.productName })
    }

    @Test
    fun `a format whose placeholder comes first still yields the name`() {
        // No translation does this today, but a label-prefix rule could not express it at all.
        val patterns = BlueprintParser.compile(listOf("%s ist eingetroffen"))
        val bp = parseLines(
            """<2026-05-02T20:11:04.132Z> [Notice] <SHUDEvent_OnNotification> Added notification """ +
                """"Attrition-5 Repeater ist eingetroffen: " [136] to queue. New queue size: 1""",
            patterns = patterns,
        )
        assertEquals(listOf("Attrition-5 Repeater"), bp.map { it.productName })
        assertEquals(136, bp.single().notificationId)
    }

    @Test
    fun `regex metacharacters in a format are matched literally`() {
        val patterns = BlueprintParser.compile(listOf("Bauplan (neu) [!]: %s"))
        val bp = parseLines(
            """<2026-05-02T20:11:04.132Z> [Notice] <SHUDEvent_OnNotification> Added notification """ +
                """"Bauplan (neu) [!]: Attrition-5 Repeater: " [7] to queue. New queue size: 1""",
            patterns = patterns,
        )
        assertEquals(listOf("Attrition-5 Repeater"), bp.map { it.productName })
    }

    @Test
    fun `a format without a placeholder is dropped instead of matching everything`() {
        assertTrue(BlueprintParser.compile(listOf("Bauplan erhalten")).isEmpty())
        assertEquals(1, BlueprintParser.compile(listOf("Bauplan erhalten", "X: %s")).size)
    }

    @Test
    fun `captures id and queue size on a localised line too`() {
        val bp = BlueprintParser.parseFile(germanSampleFile()).blueprints
            .first { it.productName == "Scalpel Sniper Rifle Magazine (12 Schuss)" }
        assertEquals("2026-05-02T20:19:47.865Z", bp.receivedAt)
        assertEquals(141, bp.notificationId)
        assertEquals(2, bp.queueSize)
    }

    // --- build number from the header (the live Game.log has none in its name) ------

    @Test
    fun `falls back to the BackupNameAttachment header when the file name carries no build`() {
        // sample-de.log's own name has no `Build(n)`, exactly like a live Game.log.
        val bp = BlueprintParser.parseFile(germanSampleFile()).blueprints.first()
        assertEquals("11875683", bp.gameBuild)
    }

    @Test
    fun `the file name wins over the header when both state a build`() {
        val tmp = File.createTempFile("Game Build(11518367) 26 Mar 26 (17 24 58)", ".log")
        tmp.deleteOnExit()
        germanSampleFile().copyTo(tmp, overwrite = true) // header says 11875683
        val bp = BlueprintParser.parseFile(tmp).blueprints.first()
        assertEquals("11518367", bp.gameBuild)
    }

    @Test
    fun `no build at all stays null rather than guessing`() {
        val tmp = File.createTempFile("plain", ".log")
        tmp.deleteOnExit()
        tmp.writeText(
            """<2026-04-04T18:00:00.000Z> [Notice] <SHUDEvent_OnNotification> Added notification """ +
                """"Received Blueprint: Lancet MH2 Mining Laser: " [90] to queue. New queue size: 1, """ +
                """MissionId: [00000000-0000-0000-0000-000000000000], ObjectiveId: [] [Team_CoreGameplayFeatures]""",
        )
        assertNull(BlueprintParser.parseFile(tmp).blueprints.single().gameBuild)
    }
}
