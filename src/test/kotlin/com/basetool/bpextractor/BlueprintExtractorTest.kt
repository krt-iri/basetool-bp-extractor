package com.basetool.bpextractor

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BlueprintExtractorTest {

    private fun tempChannel(): File = Files.createTempDirectory("sc-channel").toFile()

    /** A synthetic, realistically shaped blueprint log line (see sample.log for the real shape). */
    private fun blueprintLine(name: String, id: Int, ts: String) =
        "<$ts> [Notice] <SHUDEvent_OnNotification> Added notification \"Received Blueprint: $name: \" " +
            "[$id] to queue. New queue size: 1, MissionId: [00000000-0000-0000-0000-000000000000], " +
            "ObjectiveId: [] [Team_Feature][Notification]"

    private fun loginLine(handle: String) =
        "<2026-03-26T16:00:00.000Z> [Notice] <Legacy login response> Legacy login response - User Login Success - Handle[$handle] - x"

    @Test
    fun `picks Game_log and every log in logbackups`() {
        val channel = tempChannel()
        try {
            File(channel, "Game.log").writeText("x")
            val backups = File(channel, "logbackups").apply { mkdirs() }
            File(backups, "Game Build(1) 05 Dec 25 (15 44 14).log").writeText("x")
            File(backups, "Game Build(2) 06 Dec 25 (08 55 41).log").writeText("x")
            File(backups, "notes.txt").writeText("x") // ignored: not a .log

            val files = BlueprintExtractor.findLogFiles(channel)

            assertEquals(3, files.size)
            assertTrue(files.any { it.name == "Game.log" })
            assertTrue(files.any { it.name.startsWith("Game Build(1)") })
            assertTrue(files.any { it.name.startsWith("Game Build(2)") })
            assertFalse(files.any { it.name == "notes.txt" })
        } finally {
            channel.deleteRecursively()
        }
    }

    @Test
    fun `works with only logbackups (no current Game_log)`() {
        val channel = tempChannel()
        try {
            val backups = File(channel, "logbackups").apply { mkdirs() }
            File(backups, "Game Build(1) a.log").writeText("x")
            assertEquals(1, BlueprintExtractor.findLogFiles(channel).size)
        } finally {
            channel.deleteRecursively()
        }
    }

    @Test
    fun `empty when neither Game_log nor logbackups present`() {
        val channel = tempChannel()
        try {
            File(channel, "build_manifest.id").writeText("x") // unrelated file
            assertTrue(BlueprintExtractor.findLogFiles(channel).isEmpty())
        } finally {
            channel.deleteRecursively()
        }
    }

    @Test
    fun `a label the built-in list has never heard of is read from the installation`() {
        // The whole point of reading global.ini: a rewording, or a language we never shipped,
        // works without a release here.
        val channel = tempChannel()
        try {
            val lang = File(channel, "data/Localization/klingon").apply { mkdirs() }
            File(lang, "global.ini")
                .writeText("crafting_hud_notification_received_blueprint=wa' Bauplan {qImHa'}: %s")
            File(channel, "user.cfg").writeText("g_language = klingon\n")
            File(channel, "Game.log").writeText(
                "<2026-05-02T20:11:04.132Z> [Notice] <SHUDEvent_OnNotification> Added notification " +
                    "\"wa' Bauplan {qImHa'}: Attrition-5 Repeater: \" [136] to queue. New queue size: 1",
            )

            val result = BlueprintExtractor.extract(channel)

            assertEquals(1, result.export.blueprintCount)
            assertEquals("Attrition-5 Repeater", result.export.blueprints.single().productName)
            assertEquals("klingon", result.localization.activeLanguage)
            assertTrue(result.formatsUsed.contains("wa' Bauplan {qImHa'}: %s"))
            // The built-ins stay in play — old logs in another language sit in the same folder.
            assertTrue(result.formatsUsed.containsAll(BlueprintParser.BUILT_IN_FORMATS))
        } finally {
            channel.deleteRecursively()
        }
    }

    @Test
    fun `an install without a readable global_ini still matches the built-in labels`() {
        val channel = tempChannel()
        try {
            File(channel, "Game.log").writeText(
                "<2026-05-02T20:11:04.132Z> [Notice] <SHUDEvent_OnNotification> Added notification " +
                    "\"Bauplan erhalten: Attrition-5 Repeater: \" [136] to queue. New queue size: 1",
            )

            val result = BlueprintExtractor.extract(channel)

            assertEquals(1, result.export.blueprintCount)
            assertEquals(ScLocalization.Detected.NONE, result.localization)
            assertEquals(BlueprintParser.BUILT_IN_FORMATS, result.formatsUsed)
        } finally {
            channel.deleteRecursively()
        }
    }

    @Test
    fun `a flat archive folder of loose logs is read`() {
        val archive = tempChannel()
        try {
            File(archive, "Game Build(1) 05 Dec 25 (15 44 14).log").writeText("x")
            File(archive, "Game Build(2) 06 Dec 25 (08 55 41).log").writeText("x")
            File(archive, "notes.txt").writeText("x") // ignored: not a .log

            val files = BlueprintExtractor.findLogFiles(archive)

            assertEquals(2, files.size)
            assertEquals(listOf("Game Build(1) 05 Dec 25 (15 44 14).log", "Game Build(2) 06 Dec 25 (08 55 41).log"), files.map { it.name })
        } finally {
            archive.deleteRecursively()
        }
    }

    @Test
    fun `loose logs are ignored once the folder is a real channel folder`() {
        // The fallback must never widen a normal scan: a stray .log next to Game.log stays out.
        val channel = tempChannel()
        try {
            File(channel, "Game.log").writeText("x")
            File(channel, "logbackups").mkdirs()
            File(channel, "stray.log").writeText("x")

            val files = BlueprintExtractor.findLogFiles(channel)

            assertEquals(listOf("Game.log"), files.map { it.name })
        } finally {
            channel.deleteRecursively()
        }
    }

    @Test
    fun `the archive fallback does not recurse`() {
        // Pointing at a StarCitizen root must not walk every channel below it.
        val root = tempChannel()
        try {
            val live = File(root, "LIVE/logbackups").apply { mkdirs() }
            File(live, "Game Build(1) a.log").writeText("x")
            assertTrue(BlueprintExtractor.findLogFiles(root).isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `does not scan nested folders other than logbackups`() {
        val channel = tempChannel()
        try {
            val other = File(channel, "USER/Client/0/Logs").apply { mkdirs() }
            File(other, "stray.log").writeText("x")
            assertTrue(BlueprintExtractor.findLogFiles(channel).isEmpty())
        } finally {
            channel.deleteRecursively()
        }
    }

    @Test
    fun `LIVE folder also pulls in a sibling HOTFIX channel`() {
        val root = Files.createTempDirectory("StarCitizen").toFile()
        try {
            val live = File(root, "LIVE").apply { mkdirs() }
            File(live, "Game.log").writeText("x")
            val hotfix = File(root, "HOTFIX").apply { mkdirs() }
            File(hotfix, "Game.log").writeText("x")
            val hotfixBackups = File(hotfix, "logbackups").apply { mkdirs() }
            File(hotfixBackups, "Game Build(9) a.log").writeText("x")

            assertEquals("HOTFIX", BlueprintExtractor.siblingHotfixFolder(live)?.name)
            // LIVE Game.log + HOTFIX Game.log + HOTFIX backup
            assertEquals(3, BlueprintExtractor.findLogFiles(live).size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `no HOTFIX sibling pulled in when the folder is absent`() {
        val root = Files.createTempDirectory("StarCitizen").toFile()
        try {
            val live = File(root, "LIVE").apply { mkdirs() }
            File(live, "Game.log").writeText("x")

            assertNull(BlueprintExtractor.siblingHotfixFolder(live))
            assertEquals(1, BlueprintExtractor.findLogFiles(live).size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a sibling HOTFIX is ignored for a non-LIVE channel`() {
        val root = Files.createTempDirectory("StarCitizen").toFile()
        try {
            val ptu = File(root, "PTU").apply { mkdirs() }
            File(ptu, "Game.log").writeText("x")
            val hotfix = File(root, "HOTFIX").apply { mkdirs() }
            File(hotfix, "Game.log").writeText("x")

            assertNull(BlueprintExtractor.siblingHotfixFolder(ptu))
            assertEquals(1, BlueprintExtractor.findLogFiles(ptu).size) // only PTU's own log
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `an empty HOTFIX folder with no logs is not pulled in`() {
        val root = Files.createTempDirectory("StarCitizen").toFile()
        try {
            val live = File(root, "LIVE").apply { mkdirs() }
            File(live, "Game.log").writeText("x")
            File(root, "HOTFIX").mkdirs() // exists but carries no Game.log / logbackups

            assertNull(BlueprintExtractor.siblingHotfixFolder(live))
            assertEquals(1, BlueprintExtractor.findLogFiles(live).size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `identical events appearing in two files are counted once`() {
        val channel = tempChannel()
        try {
            val lines = loginLine("tester") + "\n" +
                blueprintLine("Yubarev Pistol", 19, "2026-03-26T16:49:31.050Z")
            File(channel, "Game.log").writeText(lines)
            // The same log content scanned again (e.g. a manually copied file).
            val backups = File(channel, "logbackups").apply { mkdirs() }
            File(backups, "copy.log").writeText(lines)

            val result = BlueprintExtractor.extract(channel)

            assertEquals(1, result.export.blueprintCount)
            assertEquals(1, result.export.players.single().blueprintCount)
        } finally {
            channel.deleteRecursively()
        }
    }

    @Test
    fun `same item received at different times stays two events`() {
        val channel = tempChannel()
        try {
            File(channel, "Game.log").writeText(
                loginLine("tester") + "\n" +
                    blueprintLine("Yubarev Pistol", 19, "2026-03-26T16:49:31.050Z") + "\n" +
                    blueprintLine("Yubarev Pistol", 23, "2026-03-27T10:00:00.000Z"),
            )

            assertEquals(2, BlueprintExtractor.extract(channel).export.blueprintCount)
        } finally {
            channel.deleteRecursively()
        }
    }

    @Test
    fun `export records the swept HOTFIX channel as additional source folder`() {
        val root = Files.createTempDirectory("StarCitizen").toFile()
        try {
            val live = File(root, "LIVE").apply { mkdirs() }
            File(live, "Game.log").writeText("x")
            val hotfix = File(root, "HOTFIX").apply { mkdirs() }
            File(hotfix, "Game.log").writeText("x")

            val withHotfix = BlueprintExtractor.extract(live).export
            assertEquals(listOf(hotfix.absolutePath), withHotfix.additionalSourceFolders)

            // Without a sibling the field stays null (and is still serialized, encodeDefaults).
            val alone = BlueprintExtractor.extract(hotfix).export
            assertNull(alone.additionalSourceFolders)
            assertTrue(BlueprintExtractor.toJson(alone).contains("\"additionalSourceFolders\": null"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `an unreadable file is skipped and reported instead of failing the run`() {
        val channel = tempChannel()
        try {
            File(channel, "Game.log").writeText(
                loginLine("tester") + "\n" + blueprintLine("Yubarev Pistol", 19, "2026-03-26T16:49:31.050Z"),
            )
            val backups = File(channel, "logbackups").apply { mkdirs() }
            val locked = File(backups, "locked.log")
            locked.writeText("some content that will be locked")

            java.io.RandomAccessFile(locked, "rw").use { raf ->
                raf.channel.lock() // mandatory on Windows: other handles can't read
                // Only meaningful where the OS actually enforces the lock against new readers.
                val lockEnforced = runCatching { locked.inputStream().use { it.read() } }.isFailure
                org.junit.jupiter.api.Assumptions.assumeTrue(lockEnforced)

                val result = BlueprintExtractor.extract(channel)

                assertEquals(listOf("locked.log"), result.skippedFiles)
                assertEquals(1, result.export.blueprintCount)
                assertEquals(1, result.export.logFilesScanned) // skipped file not counted as scanned
            }
        } finally {
            channel.deleteRecursively()
        }
    }
}
