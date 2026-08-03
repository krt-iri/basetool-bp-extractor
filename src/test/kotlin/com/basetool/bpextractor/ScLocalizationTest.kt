package com.basetool.bpextractor

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The shapes here are taken from the real files: the shipped `global.ini` writes `key=value`, the
 * translation repo (`rjcncpt/StarCitizen-Deutsch-INI`, what the SC Deutsch Launcher installs)
 * writes `key,P=value`, and both carry a UTF-8 BOM.
 */
class ScLocalizationTest {

    private lateinit var channel: File

    @BeforeTest
    fun setUp() {
        channel = Files.createTempDirectory("sc-loc-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        channel.deleteRecursively()
    }

    private fun writeLanguage(name: String, vararg lines: String) {
        val dir = File(channel, "data/Localization/$name").apply { mkdirs() }
        File(dir, "global.ini").writeText("﻿" + lines.joinToString("\n"), Charsets.UTF_8)
    }

    // --- pure line parsing --------------------------------------------------

    @Test
    fun `reads the plain key=value form the game ships`() {
        assertEquals(
            "Received Blueprint: %s",
            ScLocalization.parseIniLine("crafting_hud_notification_received_blueprint=Received Blueprint: %s"),
        )
    }

    @Test
    fun `reads the key,FLAG=value form the translation repo writes`() {
        assertEquals(
            "Bauplan erhalten: %s",
            ScLocalization.parseIniLine("crafting_hud_notification_received_blueprint,P=Bauplan erhalten: %s"),
        )
    }

    @Test
    fun `ignores any other key, including one that merely starts with ours`() {
        assertNull(ScLocalization.parseIniLine("crafting_ui_tabname_blueprints,P=Baupläne"))
        assertNull(
            ScLocalization.parseIniLine("crafting_hud_notification_received_blueprint_extra=Nope: %s"),
        )
        assertNull(ScLocalization.parseIniLine("no equals sign here"))
    }

    @Test
    fun `reads g_language, tolerating comments and quotes`() {
        assertEquals("german_(germany)", ScLocalization.parseUserCfgLanguage("g_language = german_(germany)"))
        assertEquals("english", ScLocalization.parseUserCfgLanguage("g_language=english"))
        assertEquals("english", ScLocalization.parseUserCfgLanguage("""g_language = "english" """))
        assertEquals("english", ScLocalization.parseUserCfgLanguage("g_language = english -- comment"))
        assertNull(ScLocalization.parseUserCfgLanguage("r_displayInfo = 0"))
    }

    // --- reading a real folder layout ---------------------------------------

    @Test
    fun `picks up every installed language, not just the active one`() {
        // A scan spans months of logs; the player may have switched language in between.
        writeLanguage("english", "irrelevant=x", "crafting_hud_notification_received_blueprint=Received Blueprint: %s")
        writeLanguage("german_(germany)", "crafting_hud_notification_received_blueprint,P=Bauplan erhalten: %s")
        File(channel, "user.cfg").writeText("g_language = english\n")

        val detected = ScLocalization.detect(channel)

        assertEquals("english", detected.activeLanguage)
        assertEquals(listOf("english", "german_(germany)"), detected.installedLanguages)
        assertEquals(listOf("Received Blueprint: %s", "Bauplan erhalten: %s"), detected.formats)
    }

    @Test
    fun `a language pack older than the crafting feature contributes nothing`() {
        // Verified against a real install: the German pack from March carries no crafting_hud key.
        writeLanguage("german_(germany)", "some_other_key=Irgendwas")

        val detected = ScLocalization.detect(channel)

        assertEquals(listOf("german_(germany)"), detected.installedLanguages)
        assertTrue(detected.formats.isEmpty())
    }

    @Test
    fun `an archive folder with no game install yields nothing and does not throw`() {
        val detected = ScLocalization.detect(channel)

        assertEquals(ScLocalization.Detected.NONE, detected)
    }

    @Test
    fun `a missing user_cfg leaves the language unknown rather than guessing`() {
        writeLanguage("english", "crafting_hud_notification_received_blueprint=Received Blueprint: %s")

        assertNull(ScLocalization.detect(channel).activeLanguage)
    }

    @Test
    fun `the BOM on the first line does not swallow the setting`() {
        File(channel, "user.cfg").writeText("﻿g_language = german_(germany)\n", Charsets.UTF_8)

        assertEquals("german_(germany)", ScLocalization.activeLanguage(channel))
    }
}
