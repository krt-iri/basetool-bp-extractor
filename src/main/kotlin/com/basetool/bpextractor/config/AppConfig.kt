package com.basetool.bpextractor.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Non-secret app configuration (epic krt-profit/basetool#639): the ingest base URL and whether the
 * user has accepted the one-time send consent. This is the app's **first** persisted state — it
 * lives under {@code %APPDATA%\Basetool SC Extractor\config.json} (the Roaming per-user data dir),
 * deliberately **outside** the {@code %LOCALAPPDATA%\Basetool SC Extractor\} install dir so the
 * install dir stays stateless and the MSI uninstall remains restloss (CLAUDE.md guardrail 2). It
 * holds **no secret** — the refresh token is stored separately in Windows Credential Manager (#648).
 *
 * @param ingestBaseUrl base URL of the ingest gateway the export is sent to
 * @param consentGiven {@code true} once the user accepted the first-send consent
 * @param lastChannelFolder the channel/archive folder the last successful extraction ran against,
 *   pre-filled on the next start; {@code null} until the first run. A convenience only — the
 *   blueprint step re-validates the path, so a stale or since-deleted folder just falls back to
 *   the standard install path.
 */
@Serializable
data class AppConfig(
    val ingestBaseUrl: String = DEFAULT_INGEST_BASE_URL,
    val consentGiven: Boolean = false,
    val lastChannelFolder: String? = null,
) {
    companion object {
        /** Prod ingest gateway host (behind nginx-proxy-manager); override in config.json for dev. */
        const val DEFAULT_INGEST_BASE_URL = "https://ingest.profit-base.online"
    }
}

/**
 * Loads and saves [AppConfig] as JSON under the per-user data dir. The directory is injectable so
 * tests run against a throwaway temp dir; production resolves {@code %LOCALAPPDATA%}.
 */
class AppConfigStore(private val dir: File = defaultDir()) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val file = File(dir, "config.json")

    /**
     * Reads the config, returning defaults when the file is missing or unreadable/malformed (a
     * corrupt config must never block the app).
     *
     * @return the persisted config, or defaults
     */
    fun load(): AppConfig =
        try {
            if (file.isFile) json.decodeFromString<AppConfig>(file.readText()) else AppConfig()
        } catch (_: Exception) {
            AppConfig()
        }

    /**
     * Persists the config, creating the data dir if needed. Best-effort: a write failure is
     * swallowed (the app keeps working with in-memory state) rather than crashing the UI.
     *
     * @param config the config to write
     */
    fun save(config: AppConfig) {
        try {
            if (!dir.isDirectory && !dir.mkdirs()) return
            file.writeText(json.encodeToString(AppConfig.serializer(), config))
        } catch (_: Exception) {
            // Best effort — a config write must never break the app (CLAUDE.md statelessness).
        }
    }

    companion object {
        /**
         * {@code %APPDATA%\Basetool SC Extractor} (Roaming), falling back to
         * {@code ~/AppData/Roaming}. Roaming, not Local, so this data dir is outside the
         * {@code %LOCALAPPDATA%} install dir and the MSI uninstall stays restloss.
         */
        private fun defaultDir(): File {
            val appData =
                System.getenv("APPDATA")
                    ?: (System.getProperty("user.home") + File.separator + "AppData" + File.separator + "Roaming")
            return File(appData, "Basetool SC Extractor")
        }
    }
}
