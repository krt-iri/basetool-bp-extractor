package com.basetool.bpextractor.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration

/** One release asset from the GitHub API — only the fields the update check needs. */
@Serializable
data class GitHubAsset(
    val name: String = "",
    @SerialName("browser_download_url") val downloadUrl: String = "",
    val size: Long = 0,
    /** GitHub-computed checksum (`sha256:<hex>`), verified after the download when present. */
    val digest: String? = null,
)

/** The `releases/latest` answer from the GitHub API — only the fields the update check needs. */
@Serializable
data class GitHubRelease(
    @SerialName("tag_name") val tagName: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubAsset> = emptyList(),
)

/** An offerable update: a released version newer than the running one, with its MSI asset. */
data class UpdateInfo(
    /** Release version without the leading `v`, e.g. `2.4.0` — for display and the file name. */
    val version: String,
    val tagName: String,
    val msiUrl: String,
    val msiSizeBytes: Long,
    /** Expected SHA-256 of the MSI (lowercase hex) when the API provided a digest. */
    val msiSha256: String? = null,
)

/** Major/minor/patch plus an optional pre-release suffix (`2.4.0-rc1`). */
internal data class ParsedVersion(val major: Int, val minor: Int, val patch: Int, val preRelease: String?)

/**
 * The GUI's update check against the public GitHub releases of this repo: on app start the latest
 * release is fetched (silently skipped on any failure — the check must never block or break the
 * app); when it is newer than the running version, the start screen offers to download the MSI and
 * install it. The download goes to a fixed folder under the user's temp dir — NEVER the install
 * dir (CLAUDE.md guardrail 2) — and a detached PowerShell helper runs `msiexec /i` after the app
 * exits, then deletes the MSI, the helper script and the folder again. [cleanupLeftovers] sweeps
 * that folder on every start as a belt-and-braces guard against a crashed or killed helper.
 *
 * Only release *metadata* is fetched from GitHub; no usage data is sent (the request carries
 * nothing but the standard headers).
 */
object UpdateChecker {

    /** The public repo whose releases are checked. */
    const val REPO = "krt-profit/basetool-sc-extractor"

    private val json = Json { ignoreUnknownKeys = true }

    private val http: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            // The asset download redirects from github.com to the objects CDN.
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    /**
     * Fetch the latest release and decide whether it is an offerable update over [currentVersion].
     * Returns null on *any* failure (offline, rate-limited, no releases, malformed answer): the
     * check is a courtesy, never an error surface.
     */
    fun checkForUpdate(currentVersion: String): UpdateInfo? =
        try {
            val request = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/$REPO/releases/latest"))
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "basetool-sc-extractor/$currentVersion")
                .GET()
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() == 200) {
                selectUpdate(parseLatestRelease(response.body()), currentVersion)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }

    /** Parse a `releases/latest` JSON body; null when it isn't one (error answers, garbage). */
    fun parseLatestRelease(body: String): GitHubRelease? =
        try {
            json.decodeFromString<GitHubRelease>(body)
        } catch (_: Exception) {
            null
        }

    /**
     * Decide whether [release] is an offerable update over [currentVersion]: a published (not
     * draft/pre-release) version that compares newer and actually carries an MSI asset over https.
     */
    fun selectUpdate(release: GitHubRelease?, currentVersion: String): UpdateInfo? {
        if (release == null || release.draft || release.prerelease) return null
        if (!isNewerVersion(release.tagName, currentVersion)) return null
        val msi = release.assets.firstOrNull { it.name.endsWith(".msi", ignoreCase = true) } ?: return null
        if (!msi.downloadUrl.startsWith("https://")) return null
        return UpdateInfo(
            version = release.tagName.trim().removePrefix("v"),
            tagName = release.tagName,
            msiUrl = msi.downloadUrl,
            msiSizeBytes = msi.size,
            msiSha256 = sha256FromDigest(msi.digest),
        )
    }

    /**
     * True when [remote] is a strictly newer version than [current]. Accepts an optional leading
     * `v` and a missing patch part; on equal numbers a final release beats a pre-release of the
     * same version (`2.4.0` > `2.4.0-rc1`). Unparseable versions are never "newer" — a malformed
     * tag must not produce an update offer.
     */
    fun isNewerVersion(remote: String, current: String): Boolean {
        val r = parseVersion(remote) ?: return false
        val c = parseVersion(current) ?: return false
        val numeric = compareValuesBy(r, c, { it.major }, { it.minor }, { it.patch })
        if (numeric != 0) return numeric > 0
        return c.preRelease != null && r.preRelease == null
    }

    internal fun parseVersion(raw: String): ParsedVersion? {
        val match = VERSION_REGEX.matchEntire(raw.trim()) ?: return null
        val (major, minor, patch, suffix) = match.destructured
        return try {
            ParsedVersion(major.toInt(), minor.toInt(), if (patch.isEmpty()) 0 else patch.toInt(), suffix.ifEmpty { null })
        } catch (_: NumberFormatException) {
            null
        }
    }

    /** Extract the lowercase hex from a GitHub `sha256:<hex>` digest; null for anything else. */
    internal fun sha256FromDigest(digest: String?): String? {
        if (digest == null || !digest.startsWith("sha256:")) return null
        val hex = digest.removePrefix("sha256:").lowercase()
        return hex.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }

    /**
     * The fixed download folder under the user's temp dir. Deliberately NOT the session temp dir
     * from `ImageIntake.tempFolder()`: that one is removed by a shutdown hook on exit — which
     * would race the installer helper that still needs the MSI after the app has quit.
     */
    fun updateDir(): File = File(System.getProperty("java.io.tmpdir"), "basetool-sc-extractor-update")

    /**
     * Best-effort sweep of [dir] on app start: removes MSIs/scripts a crashed or killed helper
     * left behind. Files still locked by a running helper just stay — never an error.
     */
    fun cleanupLeftovers(dir: File = updateDir()) {
        try {
            if (dir.exists()) dir.deleteRecursively()
        } catch (_: Exception) {
            // Best effort only — leftovers in the temp dir must never break the app.
        }
    }

    /**
     * Download the MSI of [info] into [targetDir], reporting (bytesDone, bytesTotal) along the
     * way. Verifies the byte count and — when the API provided one — the SHA-256 before returning;
     * a partial or corrupt file is deleted and the failure thrown to the caller (the banner shows
     * it with a retry).
     */
    fun downloadMsi(
        info: UpdateInfo,
        targetDir: File = updateDir(),
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): File {
        require(info.msiUrl.startsWith("https://")) { "refusing non-https download: ${info.msiUrl}" }
        if (!targetDir.isDirectory && !targetDir.mkdirs()) {
            throw IOException("cannot create download folder: ${targetDir.absolutePath}")
        }
        val safeVersion = info.version.filter { it.isLetterOrDigit() || it in "._-" }.ifEmpty { "latest" }
        val target = File(targetDir, "basetool-sc-extractor-$safeVersion.msi")
        val request = HttpRequest.newBuilder(URI.create(info.msiUrl))
            .timeout(Duration.ofMinutes(30))
            .header("Accept", "application/octet-stream")
            .header("User-Agent", "basetool-sc-extractor/update")
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != 200) {
            response.body().close()
            throw IOException("download failed: HTTP ${response.statusCode()}")
        }
        val total = response.headers().firstValueAsLong("content-length").orElse(info.msiSizeBytes)
        val sha256 = MessageDigest.getInstance("SHA-256")
        try {
            response.body().use { input ->
                target.outputStream().use { out ->
                    val buffer = ByteArray(256 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        sha256.update(buffer, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            if (info.msiSizeBytes > 0 && target.length() != info.msiSizeBytes) {
                throw IOException("download incomplete: ${target.length()} of ${info.msiSizeBytes} bytes")
            }
            if (info.msiSha256 != null) {
                val actual = sha256.digest().joinToString("") { "%02x".format(it) }
                if (actual != info.msiSha256) {
                    throw IOException("checksum mismatch — the downloaded file is corrupt")
                }
            }
            return target
        } catch (t: Throwable) {
            target.delete()
            throw t
        }
    }

    /**
     * The app's own launcher `.exe`, so the update helper can relaunch it once the install is
     * done (otherwise the user has to start the freshly-updated app by hand). Resolved from the
     * fixed jpackage app-image layout — `<installDir>\<AppName>.exe` next to the bundled runtime
     * at `<installDir>\runtime` (== `java.home`) — which survives the user picking a custom
     * install dir (guardrail: `dirChooser` is on) and stays valid across an in-place upgrade
     * (same dir, same exe name). [appPath] (jpackage's own `jpackage.app-path`, when present) is
     * tried first; both inputs are injectable so the resolver is unit-testable.
     *
     * Returns null when nothing resolves — most importantly a dev `gradlew run`, where `java.home`
     * is a plain JDK with no launcher beside it; the helper then simply skips the relaunch.
     */
    internal fun installedAppLauncher(
        appPath: String? = System.getProperty("jpackage.app-path"),
        javaHome: String? = System.getProperty("java.home"),
        launcherName: String = "Basetool SC Extractor.exe",
    ): File? {
        appPath?.takeIf { it.isNotBlank() }?.let { p ->
            val f = File(p)
            if (f.isFile) return f
        }
        val installDir = javaHome?.takeIf { it.isNotBlank() }?.let { File(it).parentFile } ?: return null
        File(installDir, launcherName).let { if (it.isFile) return it }
        // Fallback: the app-image install dir holds exactly one .exe (the launcher); the slim
        // runtime under runtime\ ships no java.exe, so a lone-*.exe match is unambiguous.
        return installDir.listFiles { f -> f.isFile && f.extension.equals("exe", ignoreCase = true) }
            ?.singleOrNull()
    }

    /**
     * The detached helper that performs the install after the app exits. PowerShell reads the
     * whole script file before running it, so the last line can delete the script's own folder.
     * The initial sleep gives the closing JVM time to release its files before msiexec checks
     * files-in-use; the MSI is deleted afterwards in every outcome (installed, cancelled or
     * failed) — the temp folder must end up empty either way. When `$AppPath` is given and still
     * exists, the helper **relaunches the app** at the very end (after the upgrade or its
     * rollback), so the user lands back in the running app without starting it manually.
     *
     * The first `msiexec /i` runs **without** elevation, so the common case (install under
     * `%LOCALAPPDATA%` on the system drive) stays a zero-friction, no-UAC update. Only when that
     * attempt does *not* succeed does the helper offer an **elevated retry** ("Als Administrator
     * wiederholen"): the app has already exited by install time, so this native Yes/No dialog is
     * the only place such a button can live. This is the escape hatch for the non-system-drive
     * failure — Windows-Installer error 1926 "Could not set file security for file
     * X:\Config.Msi\*.rbf. Error: 5", an endless per-`.rbf` loop that only an elevated install
     * (which holds the privilege to write the rollback files' security descriptors) can get past.
     * `$Lang` ("de"/"en") localizes the dialog to the language the app was showing; the file is
     * written with a UTF-8 BOM (see [launchInstaller]) so PowerShell 5.1 renders the umlauts.
     *
     * Success/benign exit codes (0 ok, 1602 user-cancelled, 3010/1641 reboot variants) skip the
     * prompt; anything else — 1603 among them, which is what the 1926 rollback returns — offers it.
     */
    internal val INSTALLER_SCRIPT = """
        param([string]${'$'}MsiPath, [string]${'$'}Lang = 'de', [string]${'$'}AppPath = '')
        Start-Sleep -Seconds 2

        function Invoke-MsiInstall([switch]${'$'}Elevated) {
            ${'$'}msiArgs = @('/i', ('"{0}"' -f ${'$'}MsiPath))
            if (${'$'}Elevated) {
                ${'$'}proc = Start-Process -FilePath 'msiexec.exe' -ArgumentList ${'$'}msiArgs -Verb RunAs -Wait -PassThru
            } else {
                ${'$'}proc = Start-Process -FilePath 'msiexec.exe' -ArgumentList ${'$'}msiArgs -Wait -PassThru
            }
            return ${'$'}proc.ExitCode
        }

        ${'$'}okCodes = @(0, 1602, 3010, 1641)
        try { ${'$'}code = Invoke-MsiInstall } catch { ${'$'}code = 1603 }
        if (${'$'}okCodes -notcontains ${'$'}code) {
            try {
                Add-Type -AssemblyName System.Windows.Forms
                if (${'$'}Lang -eq 'en') {
                    ${'$'}msg = "The update could not be installed. This usually happens when the app was installed on a non-system drive (D:, E:, ...), where Windows Installer cannot set the required file permissions.`n`nRetry as administrator?"
                } else {
                    ${'$'}msg = "Das Update konnte nicht installiert werden. Häufige Ursache: Installation auf einem Nicht-Systemlaufwerk (D:, E:, ...), auf dem Windows Installer die nötigen Dateirechte nicht setzen kann.`n`nAls Administrator erneut versuchen?"
                }
                ${'$'}btn = [System.Windows.Forms.MessageBoxButtons]::YesNo
                ${'$'}icon = [System.Windows.Forms.MessageBoxIcon]::Warning
                ${'$'}def = [System.Windows.Forms.MessageBoxDefaultButton]::Button1
                ${'$'}opt = [System.Windows.Forms.MessageBoxOptions]::DefaultDesktopOnly
                ${'$'}answer = [System.Windows.Forms.MessageBox]::Show(${'$'}msg, 'Basetool SC Extractor', ${'$'}btn, ${'$'}icon, ${'$'}def, ${'$'}opt)
                if (${'$'}answer -eq [System.Windows.Forms.DialogResult]::Yes) {
                    try { Invoke-MsiInstall -Elevated | Out-Null } catch { }
                }
            } catch { }
        }

        Remove-Item -LiteralPath ${'$'}MsiPath -Force -ErrorAction SilentlyContinue
        Set-Location -LiteralPath ${'$'}env:TEMP
        Remove-Item -LiteralPath (Split-Path -Parent ${'$'}MsiPath) -Recurse -Force -ErrorAction SilentlyContinue

        if (${'$'}AppPath -and (Test-Path -LiteralPath ${'$'}AppPath)) {
            try { Start-Process -FilePath ${'$'}AppPath } catch { }
        }
        """.trimIndent()

    /**
     * The helper invocation. Windows PowerShell 5.1 by absolute path (always present, unlike
     * pwsh); `-File` passes the MSI path as a plain positional argument, so no string ever needs
     * embedded quotes — paths with spaces or apostrophes survive ProcessBuilder's quoting as-is.
     * [lang] ("de"/"en") localizes the elevated-retry dialog to the language the GUI was showing
     * (any value other than "en" falls back to German); [appPath] is the launcher to relaunch
     * after the install (empty string to skip). Both are plain trailing positional arguments —
     * like the MSI path they carry no embedded quotes, so paths with spaces survive as-is.
     */
    internal fun installerCommand(scriptFile: File, msiFile: File, lang: String, appPath: String): List<String> = listOf(
        File(System.getenv("SystemRoot") ?: """C:\Windows""", """System32\WindowsPowerShell\v1.0\powershell.exe""").absolutePath,
        "-NoProfile",
        "-NonInteractive",
        "-ExecutionPolicy", "Bypass",
        "-WindowStyle", "Hidden",
        "-File", scriptFile.absolutePath,
        msiFile.absolutePath,
        if (lang == "en") "en" else "de",
        appPath,
    )

    /**
     * Write the helper script next to [msiFile] and launch it detached. The caller exits the app
     * right after — the helper waits, installs, removes the MSI and itself, then relaunches the
     * app. Its working dir is the temp root: never the install dir (which the MSI replaces) and
     * never the update dir (which the helper deletes at the end). [lang] localizes the
     * elevated-retry dialog; [appLauncher] is the exe to relaunch (defaults to
     * [installedAppLauncher]; null skips the relaunch, e.g. in a dev run).
     *
     * The script is written with a UTF-8 BOM: Windows PowerShell 5.1 decodes a BOM-less `.ps1` as
     * the ANSI code page, which would mangle the German umlauts in the failure dialog; the BOM
     * makes it read the file as UTF-8.
     */
    fun launchInstaller(msiFile: File, lang: String, appLauncher: File? = installedAppLauncher()) {
        val script = File(msiFile.parentFile, "install-update.ps1")
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        script.writeBytes(bom + INSTALLER_SCRIPT.toByteArray(Charsets.UTF_8))
        ProcessBuilder(installerCommand(script, msiFile, lang, appLauncher?.absolutePath ?: ""))
            .directory(msiFile.parentFile.parentFile ?: msiFile.parentFile)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    }

    private val VERSION_REGEX = Regex("""v?(\d+)\.(\d+)(?:\.(\d+))?(?:-([0-9A-Za-z.\-]+))?""")
}
