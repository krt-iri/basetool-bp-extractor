package com.basetool.bpextractor.config

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Round-trips the non-secret app config through a throwaway dir (never the install dir). */
class AppConfigStoreTest {

    private lateinit var dir: File

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("bpext-config-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun returnsDefaultsWhenMissing() {
        val config = AppConfigStore(dir).load()
        assertEquals(AppConfig.DEFAULT_INGEST_BASE_URL, config.ingestBaseUrl)
        assertFalse(config.consentGiven)
    }

    @Test
    fun savesAndReloads() {
        val store = AppConfigStore(dir)
        store.save(AppConfig(ingestBaseUrl = "https://ingest.example", consentGiven = true))
        assertTrue(File(dir, "config.json").isFile)

        val reloaded = AppConfigStore(dir).load()
        assertEquals("https://ingest.example", reloaded.ingestBaseUrl)
        assertTrue(reloaded.consentGiven)
    }

    @Test
    fun fallsBackToDefaultsOnCorruptFile() {
        File(dir, "config.json").writeText("{ not valid json")
        val config = AppConfigStore(dir).load()
        assertEquals(AppConfig.DEFAULT_INGEST_BASE_URL, config.ingestBaseUrl)
    }

    @Test
    fun remembersTheLastChannelFolderAndDefaultsToNull() {
        assertNull(AppConfigStore(dir).load().lastChannelFolder)

        val store = AppConfigStore(dir)
        store.save(store.load().copy(lastChannelFolder = """D:\SC\LIVE"""))

        assertEquals("""D:\SC\LIVE""", AppConfigStore(dir).load().lastChannelFolder)
    }

    @Test
    fun aLaterWriterKeepsWhatAnEarlierOneStored() {
        // The blueprint step and the send flow each own an AppConfigStore. Both must
        // load-then-copy-then-save, or one silently drops the other's field.
        val blueprintSide = AppConfigStore(dir)
        blueprintSide.save(blueprintSide.load().copy(lastChannelFolder = """D:\SC\LIVE"""))

        val sendSide = AppConfigStore(dir)
        sendSide.save(sendSide.load().copy(consentGiven = true))

        val reloaded = AppConfigStore(dir).load()
        assertEquals("""D:\SC\LIVE""", reloaded.lastChannelFolder)
        assertTrue(reloaded.consentGiven)
    }

    @Test
    fun anOlderConfigWithoutTheFieldStillLoads() {
        // Forward/backward compatibility: config.json written by a previous release.
        File(dir, "config.json").writeText("""{"ingestBaseUrl":"https://ingest.example","consentGiven":true}""")
        val config = AppConfigStore(dir).load()
        assertEquals("https://ingest.example", config.ingestBaseUrl)
        assertTrue(config.consentGiven)
        assertNull(config.lastChannelFolder)
    }
}
