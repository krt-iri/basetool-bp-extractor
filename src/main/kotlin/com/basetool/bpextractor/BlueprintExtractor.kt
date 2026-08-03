package com.basetool.bpextractor

import com.basetool.bpextractor.model.BlueprintEvent
import com.basetool.bpextractor.model.BlueprintExport
import com.basetool.bpextractor.model.PlayerSummary
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.time.Instant

/**
 * Progress callback: the file counter plus a byte counter across ALL files, so a
 * progress bar can keep moving inside one huge `Game.log` instead of stalling
 * until the next file starts.
 */
typealias ProgressListener = (
    filesDone: Int,
    filesTotal: Int,
    bytesDone: Long,
    bytesTotal: Long,
    current: String,
) -> Unit

/**
 * Outcome of one extraction run: the export document plus the names of any log
 * files that could not be read (locked/corrupt) and were skipped. Skips are
 * reported to the user but deliberately kept out of the export JSON.
 */
data class ExtractionResult(
    val export: BlueprintExport,
    val skippedFiles: List<String>,
)

/**
 * Scans a folder of Game.log files, extracts every received blueprint, and
 * produces the export document. Orchestration only — the actual line parsing
 * lives in [BlueprintParser].
 */
object BlueprintExtractor {

    // Rebranded for the multi-workflow app (epic #439 Phase 3). The blueprint import in the
    // basetool parses exports by structure, not by this provenance string, so renaming is safe.
    const val TOOL_NAME = "Basetool SC Extractor"

    /**
     * App version shown in the GUI (start screen / update banner) and written as the export's
     * `toolVersion`. Generated from the project version (the release tag in CI) by the
     * `generateBuildInfo` Gradle task — see [BuildInfo] — so it always matches the MSI version,
     * with no hand-edited constant to drift. A local dev build reports the build's dev-fallback
     * version.
     */
    val TOOL_VERSION: String = BuildInfo.VERSION

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
    }

    /** The channel folder a user normally points the tool at. */
    const val PRIMARY_CHANNEL_NAME = "LIVE"

    /**
     * The patch-cycle channel CIG spins up next to LIVE. Crafting knowledge is account-wide, but
     * each channel writes its own logs, so a blueprint first received while playing HOTFIX is
     * recorded only in the HOTFIX logs. A user who farmed on HOTFIX and then points the tool at
     * LIVE would silently lose those blueprints — so when the picked folder is LIVE we also sweep a
     * sibling HOTFIX folder (see [siblingHotfixFolder]).
     */
    const val SIBLING_CHANNEL_NAME = "HOTFIX"

    /**
     * Collect the SC log files to scan from a channel folder (e.g. `…\StarCitizen\LIVE`): the
     * current `Game.log` in that folder plus every `*.log` in its `logbackups` subfolder (the
     * `Game Build(...).log` session backups). When [channelFolder] is the LIVE channel and a
     * sibling `HOTFIX` folder holding logs sits next to it, that channel's logs are appended too
     * (see [siblingHotfixFolder]). Current first, then backups by name; the export is re-sorted by
     * blueprint timestamp anyway.
     *
     * A folder that is neither shape but holds loose `*.log` files is read as an archive — see
     * [looseLogsIn].
     */
    fun findLogFiles(channelFolder: File): List<File> {
        val files = collectChannelLogs(channelFolder).toMutableList()
        siblingHotfixFolder(channelFolder)?.let { files += collectChannelLogs(it) }
        return files
    }

    /**
     * The `Game.log` + every `*.log` in `logbackups` for a single channel folder, current first
     * then backups by name. Empty when [channelFolder] is not a directory or carries no logs.
     *
     * When the folder is neither — no `Game.log`, no `logbackups` — but holds `*.log` files
     * directly, those are taken instead (see [looseLogsIn]).
     */
    private fun collectChannelLogs(channelFolder: File): List<File> {
        if (!channelFolder.isDirectory) return emptyList()
        val files = mutableListOf<File>()
        val current = File(channelFolder, "Game.log")
        if (current.isFile) files += current
        val backups = File(channelFolder, "logbackups")
        if (backups.isDirectory) {
            backups.listFiles()
                ?.filter { it.isFile && it.extension.equals("log", ignoreCase = true) }
                ?.sortedBy { it.name }
                ?.let { files += it }
        }
        return files.ifEmpty { looseLogsIn(channelFolder) }
    }

    /**
     * Fallback for an **archive** folder: `*.log` files lying directly in [folder], used only when
     * it has neither a `Game.log` nor a `logbackups` subfolder.
     *
     * SC never produces this shape, but people do — copying `logbackups` out before the game
     * prunes it, or pulling the backups off a second PC. Without this the picker rejects a folder
     * full of perfectly good logs as "the wrong folder", which is the harshest possible answer to
     * a user who did the right thing. Deliberately **not** recursive: someone pointing at their
     * whole `StarCitizen` root must not kick off a multi-GB walk across five channels.
     */
    private fun looseLogsIn(folder: File): List<File> =
        folder.listFiles()
            ?.filter { it.isFile && it.extension.equals("log", ignoreCase = true) }
            ?.sortedBy { it.name }
            .orEmpty()

    /**
     * If [channelFolder] is the LIVE channel directory, returns the sibling [SIBLING_CHANNEL_NAME]
     * (HOTFIX) directory next to it (e.g. `…\StarCitizen\LIVE` → `…\StarCitizen\HOTFIX`) when that
     * folder exists and actually holds SC logs (a `Game.log` or a `logbackups` subfolder). Returns
     * `null` otherwise — when the picked folder isn't LIVE, has no parent, or no usable HOTFIX
     * sibling is present. Pure `File` stats; never writes or browses, so a caller can use it for a
     * cheap live UI hint as well as for the scan itself.
     */
    fun siblingHotfixFolder(channelFolder: File): File? {
        if (!channelFolder.name.equals(PRIMARY_CHANNEL_NAME, ignoreCase = true)) return null
        val parent = channelFolder.absoluteFile.parentFile ?: return null
        val sibling = File(parent, SIBLING_CHANNEL_NAME)
        if (!sibling.isDirectory) return null
        val hasLogs = File(sibling, "Game.log").isFile || File(sibling, "logbackups").isDirectory
        return if (hasLogs) sibling else null
    }

    /** Report within-file byte progress at most every this many bytes, to keep UI churn low. */
    private const val PROGRESS_BYTE_STEP = 4L * 1024 * 1024

    /**
     * Identity of one blueprint event independent of which file it was read from. Two
     * events with the same key are the same in-game notification — seen twice only when
     * the same log content was scanned twice (e.g. a manually copied log file).
     */
    private data class EventKey(
        val player: String?,
        val productName: String,
        val receivedAt: String,
        val notificationId: Int?,
    )

    /**
     * Run extraction over [channelFolder]. Returns the assembled [BlueprintExport] plus any
     * skipped (unreadable) files, without writing anything to disk (so callers can preview
     * first). A file that fails to read is skipped and reported — one locked or corrupt log
     * must not lose the whole run. Events whose identity was already seen in another file
     * (same player/name/timestamp/notification id ⇒ a duplicated log) are counted once.
     */
    fun extract(
        channelFolder: File,
        progress: ProgressListener? = null,
    ): ExtractionResult {
        val files = findLogFiles(channelFolder)
        val bytesTotal = files.sumOf { it.length() }

        val allBlueprints = mutableListOf<BlueprintEvent>()
        val countsByPlayer = linkedMapOf<String, Int>()
        val seenEvents = HashSet<EventKey>()
        val skipped = mutableListOf<String>()
        var bytesBefore = 0L

        files.forEachIndexed { index, file ->
            progress?.invoke(index, files.size, bytesBefore, bytesTotal, file.name)
            var lastReported = 0L
            val result = try {
                BlueprintParser.parseFile(file) { bytesRead ->
                    if (bytesRead - lastReported >= PROGRESS_BYTE_STEP) {
                        lastReported = bytesRead
                        progress?.invoke(index, files.size, bytesBefore + bytesRead, bytesTotal, file.name)
                    }
                }
            } catch (_: IOException) {
                skipped += file.name
                null
            }
            bytesBefore += file.length()
            result ?: return@forEachIndexed
            for (bp in result.blueprints) {
                if (!seenEvents.add(EventKey(bp.player, bp.productName, bp.receivedAt, bp.notificationId))) continue
                allBlueprints += bp
                bp.player?.let { countsByPlayer.merge(it, 1, Int::plus) }
            }
        }
        progress?.invoke(files.size, files.size, bytesTotal, bytesTotal, "")

        // Chronological order; events with an unparseable timestamp sink to the end.
        val sorted = allBlueprints.sortedBy { it.receivedAt.ifEmpty { "￿" } }

        val players = countsByPlayer.entries
            .sortedByDescending { it.value }
            .map { (handle, count) -> PlayerSummary(handle = handle, blueprintCount = count) }

        val export = BlueprintExport(
            tool = TOOL_NAME,
            toolVersion = TOOL_VERSION,
            generatedAt = Instant.now().toString(),
            sourceFolder = channelFolder.absolutePath,
            additionalSourceFolders = siblingHotfixFolder(channelFolder)
                ?.let { listOf(it.absolutePath) },
            logFilesScanned = files.size - skipped.size,
            blueprintCount = sorted.size,
            players = players,
            blueprints = sorted,
        )
        return ExtractionResult(export, skipped)
    }

    /** Serialize an export to pretty JSON text. */
    fun toJson(export: BlueprintExport): String = json.encodeToString(BlueprintExport.serializer(), export)

    /** Write an export to [output] as UTF-8 JSON, creating parent folders as needed. */
    fun writeJson(export: BlueprintExport, output: File) {
        output.absoluteFile.parentFile?.mkdirs()
        output.writeText(toJson(export), Charsets.UTF_8)
    }
}
