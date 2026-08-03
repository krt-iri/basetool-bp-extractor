package com.basetool.bpextractor

import java.io.File

/**
 * Reads Star Citizen's own localisation files so the blueprint label does not have to be guessed.
 *
 * The log only ever carries the *rendered* notification text, and that text is translated. What is
 * invariant is the localisation **key**: `crafting_hud_notification_received_blueprint`. Its value
 * is a format string whose `%s` is the item name:
 *
 * ```
 * crafting_hud_notification_received_blueprint=Received Blueprint: %s
 * crafting_hud_notification_received_blueprint,P=Bauplan erhalten: %s
 * ```
 *
 * That mapping sits in a plain file inside the very folder the user already picks —
 * `<channel>\data\Localization\<language>\global.ini` — whenever a language pack is installed
 * (which is exactly the case where the label is not English). So instead of shipping a guess per
 * language, we read the string the game will actually write. A rewording by CIG or by the
 * translation team is then picked up on the next run, with no release on our side.
 *
 * Vanilla English installs keep `global.ini` inside `Data.p4k` and have no loose file; that costs
 * nothing, because English is a built-in format anyway
 * ([BlueprintParser.BUILT_IN_FORMATS]).
 *
 * Everything here is best-effort and read-only: a missing folder, an unreadable file or a missing
 * key yields an empty result, never an exception. Nothing is ever written.
 */
object ScLocalization {

    /** The one key that carries the "you received a blueprint" notification text. */
    const val BLUEPRINT_KEY = "crafting_hud_notification_received_blueprint"

    /** `<channel>\data\Localization\<language>\global.ini`. */
    private const val LOCALIZATION_DIR = "data/Localization"

    /** `<channel>\user.cfg`, where `g_language` names the active language folder. */
    private const val USER_CFG = "user.cfg"

    /**
     * Both files ship as UTF-8 **with** a byte-order mark, and the JDK's UTF-8 decoder hands that
     * mark through as a leading U+FEFF on line 1 rather than swallowing it. Only `user.cfg` is
     * realistically affected (its first line can be the setting), but both parsers strip it so
     * neither depends on where in the file its line happens to sit.
     */
    private const val BOM = "\uFEFF"

    /**
     * What the picked folder told us about its own localisation.
     *
     * @param activeLanguage the `g_language` value from `user.cfg`, or `null` if there is none
     *   (then the game runs English)
     * @param installedLanguages every language folder found under `data/Localization`
     * @param formats the [BLUEPRINT_KEY] value of each of those languages that carries the key,
     *   in the same order — a language pack older than the crafting feature simply contributes
     *   nothing
     */
    data class Detected(
        val activeLanguage: String?,
        val installedLanguages: List<String>,
        val formats: List<String>,
    ) {
        companion object {
            val NONE = Detected(activeLanguage = null, installedLanguages = emptyList(), formats = emptyList())
        }
    }

    /**
     * Inspect [channelFolder] for its localisation setup.
     *
     * Reads **every** installed language, not just the active one: a scan covers months of logs and
     * the player may well have switched language in between, so the labels of all of them are
     * relevant to the same run.
     */
    fun detect(channelFolder: File): Detected {
        val languages = languageFolders(channelFolder)
        return Detected(
            activeLanguage = activeLanguage(channelFolder),
            installedLanguages = languages.map { it.name },
            formats = languages.mapNotNull { blueprintFormatIn(File(it, "global.ini")) }.distinct(),
        )
    }

    /** The `g_language` value from `<channel>\user.cfg`, or `null` when absent/unreadable. */
    fun activeLanguage(channelFolder: File): String? =
        runCatching {
            val cfg = File(channelFolder, USER_CFG)
            if (!cfg.isFile) return@runCatching null
            cfg.useLines(Charsets.UTF_8) { lines ->
                lines.firstNotNullOfOrNull { parseUserCfgLanguage(it) }
            }
        }.getOrNull()

    /** Language folders under `<channel>\data\Localization`, sorted for a stable result. */
    private fun languageFolders(channelFolder: File): List<File> =
        runCatching {
            File(channelFolder, LOCALIZATION_DIR).listFiles()
                ?.filter { it.isDirectory }
                ?.sortedBy { it.name }
                .orEmpty()
        }.getOrElse { emptyList() }

    /**
     * The [BLUEPRINT_KEY] value from one `global.ini`, or `null` if the file is unreadable or the
     * key is absent (a language pack older than the crafting feature has neither).
     *
     * The file is ~11 MB, so it is streamed and abandoned the moment the key is found, behind a
     * literal substring guard — the same shape as the log hot path in [BlueprintParser].
     * `useLines` with UTF-8 covers the BOM these files carry, because the BOM sits on line 1 and
     * the key never does.
     */
    fun blueprintFormatIn(globalIni: File): String? =
        runCatching {
            if (!globalIni.isFile) return@runCatching null
            globalIni.useLines(Charsets.UTF_8) { lines ->
                lines.firstNotNullOfOrNull { line ->
                    if (BLUEPRINT_KEY !in line) null else parseIniLine(line)
                }
            }
        }.getOrNull()

    // --- Pure parsing (unit-testable without touching a disk) -------------------

    /**
     * The value of a `global.ini` line for exactly [BLUEPRINT_KEY], or `null` for anything else.
     *
     * Both shapes occur in the wild — the shipped file writes `key=value`, the translation repo
     * writes `key,P=value` where the suffix is a CIG-side flag. Matching the key exactly (rather
     * than by prefix) is what keeps a longer key that merely *starts* with ours from being picked
     * up.
     */
    fun parseIniLine(line: String): String? {
        val eq = line.indexOf('=')
        if (eq < 0) return null
        val rawKey = line.substring(0, eq)
        val key = rawKey.substringBefore(',').trim().removePrefix(BOM)
        if (!key.equals(BLUEPRINT_KEY, ignoreCase = true)) return null
        return line.substring(eq + 1).trim().ifEmpty { null }
    }

    /** `g_language = german_(germany)` → `german_(germany)`; `null` for any other line. */
    fun parseUserCfgLanguage(line: String): String? {
        val stripped = line.substringBefore("--").substringBefore(";").trim().removePrefix(BOM)
        val eq = stripped.indexOf('=')
        if (eq < 0) return null
        if (!stripped.substring(0, eq).trim().equals("g_language", ignoreCase = true)) return null
        return stripped.substring(eq + 1).trim().trim('"').ifEmpty { null }
    }
}
