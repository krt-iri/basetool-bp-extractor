package com.basetool.bpextractor

import com.basetool.bpextractor.model.BlueprintEvent
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream

/**
 * Pure, side-effect-free parsing of Star Citizen Game.log files for received
 * blueprints. No mission logic — by design we only care about what blueprints a
 * player received and everything the log tells us about each one.
 *
 * The signal line looks like:
 * ```
 * <2026-03-26T16:49:31.050Z> [Notice] <SHUDEvent_OnNotification> Added notification
 *   "Received Blueprint: Yubarev "Mirage" Pistol: " [19] to queue. New queue size: 2,
 *   MissionId: [00000000-0000-0000-0000-000000000000], ObjectiveId: [] [...]
 * ```
 * Note three real-world quirks the patterns below handle:
 *  - the item name can itself contain double quotes (`Yubarev "Mirage" Pistol`),
 *  - it can contain parentheses, slashes and hyphens (`Yubarev Pistol Battery (10 cap)`,
 *    `Sth/2/C Cirrus`, `ADP-mk4 Core Woodland`),
 *  - it can carry a trailing space (`Antium Legs Moss Camo `) which we trim.
 *
 * The `Received Blueprint` label in front of the name is **localised** — everything around it
 * is a C++ format literal and stays English. See [BLUEPRINT_LABELS].
 *
 * The MissionId on a blueprint line is always all-zero, so it is useless for
 * attribution — which is fine, because mission data is explicitly out of scope.
 * The *receiving player* instead comes from the login lines of the same file.
 */
object BlueprintParser {

    /** Leading `<ISO-8601>` timestamp at the very start of every log line. */
    private val TIMESTAMP = Regex("""^<([^>]+)>""")

    /**
     * The localised labels the game puts in front of the item name. Star Citizen renders this
     * label from its localisation tables (`g_language` in `user.cfg`), while the line around it
     * — `Added notification "<label>: <name>: " [<id>] to queue. New queue size: …` — is a C++
     * format literal and stays English in every language. Matching only the English label is
     * therefore a *silent total* failure on a localised client: no error, no skipped file, just
     * an export with zero blueprints.
     *
     * Deliberately a **closed whitelist**: a label goes in here only once it has been seen in a
     * real log of that client language. A guessed translation is at best dead weight and at
     * worst matches the wrong notification kind — and the whitelist is what keeps the
     * anti-overcounting guarantee below narrow.
     */
    private val BLUEPRINT_LABELS = listOf("Received Blueprint", "Bauplan erhalten")

    /**
     * The one authoritative "you received a blueprint" line. Anchored on
     * `Added notification` so the noisy follow-up lines (the bare queue echo and
     * the later `UpdateNotificationItem` Next/StartFade/Remove lines) are ignored
     * — they all repeat the same text and would otherwise inflate the count ~6x.
     * (Measured on the private corpus: 1063 blueprint mentions for 179 real events.)
     *
     * Group 1 = item name (non-greedy, up to the `: " [<digits>]` terminator),
     * Group 2 = notification id.
     */
    private val BLUEPRINT = Regex(
        """Added notification "(?i:""" +
            BLUEPRINT_LABELS.joinToString("|") { Regex.escape(it) } +
            """): (.+?): " \[(\d+)]""",
    )

    /**
     * Cheap literal prefilter for [BLUEPRINT]: a plain substring check skips the regex for
     * ~99.5% of lines (logs run to hundreds of MB). Must stay a literal prefix of the regex —
     * which is why it stops before the label, now that the label is a set rather than one
     * string. Measured over the 424-file corpus: 41 560 of 7 863 351 lines reach the regex,
     * against 179 before; the scan is I/O-bound, so the cost does not show up.
     *
     * **Case:** this check is exact, and correspondingly the leading `Added notification "` in
     * [BLUEPRINT] is the one part *not* wrapped in `(?i:…)`. That is not an oversight — it is the
     * invariant that keeps the two consistent. `Added notification "` is a compile-time format
     * literal in the game binary and cannot vary; the label after it comes from the translation
     * tables and can, which is exactly the part matched case-insensitively. Making the prefilter
     * itself case-insensitive would give up the intrinsified `String.indexOf` on every one of
     * ~7.9M lines to guard a string that cannot change.
     */
    private const val BLUEPRINT_MARKER = "Added notification \""

    /**
     * Optional sibling fields on the same blueprint line. Only ever run on a line [BLUEPRINT]
     * already matched, so case-insensitivity here is free.
     */
    private val QUEUE_SIZE = Regex("""New queue size: (\d+)""", RegexOption.IGNORE_CASE)

    /**
     * Build number embedded in the SC backup-log file name: `Game Build(11518367) ...` — and in
     * the header line described by [BUILD_HEADER_MARKER], which carries the same shape.
     */
    private val BUILD_FROM_NAME = Regex("""Build\((\d+)\)""", RegexOption.IGNORE_CASE)

    /**
     * Every SC log declares on its **first** line the name it will later be backed up as:
     * `<ts> BackupNameAttachment=" Build(11518367) 26 Mar 26 (17 24 58)"  -- used by backup system`.
     *
     * The rotated backups carry that build in their own file name, but the live `Game.log` does
     * not — so without reading the header, every event from the session the player just finished
     * exports `gameBuild = null`. Verified across the whole private corpus: the header is present
     * in all 424 files and always states the same build as the file name.
     */
    private const val BUILD_HEADER_MARKER = "BackupNameAttachment="

    // --- Player identity lines (first match in a file wins) ----------------
    //
    // All three are matched case-insensitively. Like [BLUEPRINT] they sit behind an exact literal
    // guard in [extractPlayer] (see there): the guard is the hot-path filter and pins the case of
    // the regex's own leading literal, so the two can never disagree.

    /** `<Legacy login response> ... User Login Success - Handle[greluc] - ...` */
    private val LOGIN_HANDLE = Regex("""User Login Success - Handle\[([^\]]+)]""", RegexOption.IGNORE_CASE)

    /**
     * `<AccountLoginCharacterStatus_Character> Character: ... geid 202153876894 -
     *  accountId 412645 - name greluc - state STATE_CURRENT`
     * Most reliable identity line — we anchor on the full geid/accountId/name
     * pattern for precision but keep only the handle (name); geid and accountId
     * are deliberately not stored or exported.
     */
    private val CHAR_STATUS = Regex(
        """geid (\d+) - accountId (\d+) - name (\S+) - state STATE_CURRENT""",
        RegexOption.IGNORE_CASE,
    )

    /** `... nickname="greluc" playerGEID=202153876894 ...` (network handshake fallback). */
    private val NICKNAME = Regex("""nickname="([^"]+)"""", RegexOption.IGNORE_CASE)

    /** Identity of the player a single log file belongs to. */
    data class PlayerIdentity(
        val handle: String,
    )

    /** Result of parsing one file: the player it belongs to plus its blueprints. */
    data class FileResult(
        val player: PlayerIdentity?,
        val blueprints: List<BlueprintEvent>,
    )

    /**
     * Parse a single Game.log file. Streams line by line so multi-hundred-MB
     * logs never get loaded whole. Unreadable bytes are replaced, never fatal.
     *
     * [onBytesRead] (optional) is called with the cumulative raw bytes consumed so far —
     * the within-file progress source for the GUI bar. Granularity follows the reader's
     * internal buffering (a few KB per step), which is plenty for a progress bar.
     */
    fun parseFile(file: File, onBytesRead: ((bytesRead: Long) -> Unit)? = null): FileResult {
        var gameBuild = BUILD_FROM_NAME.find(file.name)?.groupValues?.get(1)
        // Only the live Game.log lacks a build in its name; then, and only then, we look at the
        // header. Bounded to the very first line so this never touches the hot path.
        var buildHeaderPending = gameBuild == null
        val blueprints = mutableListOf<BlueprintEvent>()
        var player: PlayerIdentity? = null

        val input = file.inputStream().let { raw ->
            if (onBytesRead == null) raw else CountingInputStream(raw, onBytesRead)
        }
        input.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                if (buildHeaderPending) {
                    buildHeaderPending = false
                    if (line.contains(BUILD_HEADER_MARKER, ignoreCase = true)) {
                        gameBuild = BUILD_FROM_NAME.find(line)?.groupValues?.get(1)
                    }
                }

                // Resolve the player once, from whichever identity line shows up first.
                if (player == null) {
                    player = extractPlayer(line)
                }

                // Cheap literal prefilter — skips the regex for the vast majority of lines.
                if (BLUEPRINT_MARKER !in line) continue
                val bp = BLUEPRINT.find(line) ?: continue
                val name = bp.groupValues[1].trim()
                if (name.isEmpty()) continue

                blueprints += BlueprintEvent(
                    productName = name,
                    category = categorize(name),
                    receivedAt = TIMESTAMP.find(line)?.groupValues?.get(1) ?: "",
                    player = player?.handle,
                    notificationId = bp.groupValues[2].toIntOrNull(),
                    queueSize = QUEUE_SIZE.find(line)?.groupValues?.get(1)?.toIntOrNull(),
                    gameBuild = gameBuild,
                    sourceFile = file.name,
                )
            }
        }

        // A player line can appear *after* an early blueprint in rare logs; back-fill.
        val resolved = player
        val finalBlueprints =
            if (resolved != null && blueprints.any { it.player == null }) {
                blueprints.map { if (it.player == null) it.copy(player = resolved.handle) else it }
            } else {
                blueprints
            }

        return FileResult(resolved, finalBlueprints)
    }

    private fun extractPlayer(line: String): PlayerIdentity? {
        // Each regex sits behind a literal substring guard — identity lines are rare,
        // so the regexes must not run on every line of a multi-hundred-MB log. The guards stay
        // case-exact for the same reason [BLUEPRINT_MARKER] does: they run per line in a file
        // whose player is still unresolved, and they guard engine-side format literals that
        // cannot vary in case. The regexes behind them are case-insensitive.
        if ("geid " in line) {
            CHAR_STATUS.find(line)?.let {
                return PlayerIdentity(handle = it.groupValues[3])
            }
        }
        if ("User Login Success" in line) {
            LOGIN_HANDLE.find(line)?.let {
                return PlayerIdentity(handle = it.groupValues[1])
            }
        }
        if ("nickname=\"" in line) {
            NICKNAME.find(line)?.let {
                return PlayerIdentity(handle = it.groupValues[1])
            }
        }
        return null
    }

    // --- Categorisation -----------------------------------------------------

    /** `(30 cap)`-style capacity suffix — the ammo marker that isn't a keyword. */
    private val CAP_SUFFIX = Regex("""\(\d+\s*cap\)""")

    /**
     * Word-boundary alternation over [words], so a keyword inside another word never
     * matches ("gun" must not hit "Gungnir", "core" must not hit "Scored").
     */
    private fun wordsRegex(words: List<String>): Regex =
        Regex("""\b(?:${words.joinToString("|") { Regex.escape(it) }})\b""")

    private val MINING_WORDS = wordsRegex(listOf("mining laser"))
    private val AMMO_WORDS = wordsRegex(listOf("magazine", "battery"))
    private val ARMOR_WORDS = wordsRegex(
        listOf("helmet", "core", "arms", "legs", "armor", "flight suit", "undersuit", "torso", "backpack"),
    )
    private val WEAPON_WORDS = wordsRegex(
        listOf("pistol", "rifle", "shotgun", "smg", "cannon", "sniper", "crossbow", "lmg", "gun", "launcher"),
    )

    /**
     * Best-effort item classification from the localised name. Purely derived
     * (the log doesn't state a category), provided as a convenience for filtering.
     * Keywords match on word boundaries; order matters: ammo/tool keywords are
     * checked before the broad weapon keywords so "S71 Rifle Magazine" lands in
     * Ammo, not Weapon.
     */
    fun categorize(name: String): String {
        val n = name.lowercase()
        return when {
            MINING_WORDS.containsMatchIn(n) -> "MiningTool"
            AMMO_WORDS.containsMatchIn(n) || CAP_SUFFIX.containsMatchIn(n) -> "Ammo"
            ARMOR_WORDS.containsMatchIn(n) -> "Armor"
            WEAPON_WORDS.containsMatchIn(n) -> "Weapon"
            else -> "Other"
        }
    }

    /**
     * Counts raw bytes as they stream past and reports the running total — feeds
     * within-file progress without buffering or copying anything.
     */
    private class CountingInputStream(
        delegate: InputStream,
        private val onCount: (Long) -> Unit,
    ) : FilterInputStream(delegate) {
        private var total = 0L

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) {
                total++
                onCount(total)
            }
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) {
                total += n
                onCount(total)
            }
            return n
        }
    }
}
