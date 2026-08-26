# Submits a release artifact to VirusTotal and renders the release-notes section for it.
#
# Why this exists: the MSI is unsigned, brand-new on every release and downloaded by a
# handful of people, which is exactly the input Microsoft Defender's ML classifier turns
# into a Trojan:Script/Wacatac.H!ml false positive. A VirusTotal permalink in the release
# notes lets anyone check the artifact against ~70 engines and verify, via the SHA-256,
# that the file they downloaded is the one CI built.
#
# Licensing (checked 2026-08-19, https://docs.virustotal.com/reference/public-vs-premium-api):
#   The Public API is free. Its two restrictions both hold for this repo:
#     * "must not be used in commercial products or services" - this is a GPL-3.0
#       community tool, not something that is sold.
#     * "must not be used in business workflows that do not contribute new files" -
#       contributing a new file per release is this workflow's whole point.
#   Quotas: 500 requests/day, 4 requests/minute. One release costs ~2 requests plus one
#   poll every $PollSeconds, so the poll interval must stay >= 15s to stay inside them.
#   NOTE: anything uploaded through the Public API becomes PUBLIC on VirusTotal. Only ever
#   pass the MSI here - it is already a public GitHub release asset. Never a Game.log,
#   never a refinery screenshot (CLAUDE.md guardrails 1 / 1a).
#
# Failure policy: this NEVER blocks a release, and a detection never fails the build (the
# known Defender false positive would block every single release). Any problem - missing
# key, quota, outage, slow analysis - degrades to an empty release_note and exit 0.
#
# Usage:  .\.github\scripts\virustotal-scan.ps1 -Path dist\Foo-1.2.3.msi
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Path,
    [string]$ApiKey = $env:VIRUSTOTAL_API_KEY,
    # Analysing a ~100 MB installer across ~70 engines usually lands in 2-5 minutes; this
    # is the give-up point, not the expected duration.
    [int]$TimeoutMinutes = 20,
    # >= 15s keeps us inside the 4 requests/minute public-API rate limit.
    [int]$PollSeconds = 30
)

$ErrorActionPreference = "Stop"

$api = "https://www.virustotal.com/api/v3"
# The /files endpoint takes at most 32 MB; bigger files go through /files/upload_url.
$directUploadLimit = 32MB

# --- Output plumbing ------------------------------------------------------------------
# Every exit path writes every output, so the release step can reference them blindly.
function Write-Outputs([hashtable]$Values) {
    if (-not $env:GITHUB_OUTPUT) {
        $Values.GetEnumerator() | ForEach-Object { Write-Host "[output] $($_.Key) = $($_.Value)" }
        return
    }
    foreach ($entry in $Values.GetEnumerator()) {
        if ("$($entry.Value)" -match "`n") {
            # Multiline outputs need a delimiter that cannot occur inside the payload.
            $delim = "vt_$([guid]::NewGuid().ToString('N'))"
            "$($entry.Key)<<$delim" | Out-File $env:GITHUB_OUTPUT -Append -Encoding utf8
            "$($entry.Value)"       | Out-File $env:GITHUB_OUTPUT -Append -Encoding utf8
            $delim                  | Out-File $env:GITHUB_OUTPUT -Append -Encoding utf8
        } else {
            "$($entry.Key)=$($entry.Value)" | Out-File $env:GITHUB_OUTPUT -Append -Encoding utf8
        }
    }
}

function Stop-WithoutNote([string]$Reason, [string]$Sha = "") {
    Write-Host "::warning title=VirusTotal::$Reason - the release is published without the scan section."
    Write-Outputs @{ status = "unavailable"; sha256 = $Sha; permalink = ""; release_note = "" }
    exit 0
}

# --- 1. The artifact ------------------------------------------------------------------
$file = Get-Item -LiteralPath $Path -ErrorAction SilentlyContinue
if (-not $file) { Stop-WithoutNote "No artifact at '$Path'" }

$sha256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
# The canonical permalink is keyed by the hash, so it survives re-analyses and can be
# printed even when the analysis has not finished yet.
$permalink = "https://www.virustotal.com/gui/file/$sha256"
Write-Host "Artifact : $($file.Name) ($([math]::Round($file.Length / 1MB, 1)) MB)"
Write-Host "SHA-256  : $sha256"

if (-not $ApiKey) { Stop-WithoutNote "VIRUSTOTAL_API_KEY is not set" $sha256 }

# --- 2. HTTP helpers ------------------------------------------------------------------
function Invoke-Vt {
    param([string]$Uri, [string]$Method = 'Get', [hashtable]$Form, [int]$TimeoutSec = 120)
    $params = @{
        Uri        = $Uri
        Method     = $Method
        Headers    = @{ 'x-apikey' = $ApiKey; 'accept' = 'application/json' }
        TimeoutSec = $TimeoutSec
    }
    if ($Form) { $params.Form = $Form }
    Invoke-RestMethod @params
}

function Get-HttpStatus($ErrorRecord) {
    if ($ErrorRecord.Exception.Response) { return [int]$ErrorRecord.Exception.Response.StatusCode }
    return 0
}

# Posts $File as a multipart/form-data "file" field, returning @{ Ok; Status; Body; CurlExit }.
#
# Why curl and not `Invoke-RestMethod -Form`: VirusTotal documents this upload as
# `curl -F file=@...`, and curl is what the endpoint is actually tested against. Doing it
# in-process means reproducing curl's exact multipart bytes, and .NET's
# MultipartFormDataContent differs in three ways that VirusTotal's Google App Engine
# blobstore rejects with `HTTP 400 Malformed multipart body`: it quotes the boundary
# (`boundary="..."`), writes `name=file` unquoted, and appends an RFC 5987
# `filename*=utf-8''...` because our file name contains spaces. None of that is reachable
# through -Form. curl.exe has shipped in Windows since 1803 and is on every windows-latest
# runner, so this is both the simpler and the better-tested path.
function Send-MultipartFile([string]$Uri, [System.IO.FileInfo]$File) {
    $curl = Join-Path $env:SystemRoot 'System32\curl.exe'
    if (-not (Test-Path -LiteralPath $curl)) { $curl = 'curl.exe' }   # PATH fallback

    # The key travels through a stdin config file so it never lands in the process command
    # line. Quoting it raw is safe - API keys are hex, and curl's config parser would treat
    # a backslash as an escape.
    $keyConfig = 'header = "x-apikey: {0}"' -f $ApiKey

    # --fail-with-body: non-zero exit on 4xx/5xx but still print VirusTotal's error JSON.
    # --write-out: append the status code on its own final line.
    # NOTE: curl's -F treats ';' and ',' in the value as separators. Our MSI name (from
    # build.gradle.kts) contains neither; spaces are fine as one argv element.
    $lines = $keyConfig | & $curl --config - `
        --silent --show-error --fail-with-body `
        --max-time 900 `
        --header 'accept: application/json' `
        --form "file=@$($File.FullName)" `
        --write-out '\n%{http_code}' `
        --url $Uri
    $exit = $LASTEXITCODE

    $all = @($lines)
    $status = 0
    if ($all.Count) { [int]::TryParse(("$($all[-1])").Trim(), [ref]$status) | Out-Null }
    $body = if ($all.Count -gt 1) { ($all[0..($all.Count - 2)]) -join "`n" } else { '' }

    [pscustomobject]@{
        Ok       = ($exit -eq 0 -and $status -ge 200 -and $status -lt 300)
        Status   = $status
        Body     = $body
        CurlExit = $exit
    }
}

# --- 3. Upload, unless VirusTotal already knows this exact build -----------------------
# The lookup costs one request and saves a ~100 MB upload on a re-run of the release job.
$report = $null
$analysisId = $null
try {
    try { $report = Invoke-Vt "$api/files/$sha256" }
    catch { if ((Get-HttpStatus $_) -ne 404) { throw } }

    if ($report) {
        Write-Host "VirusTotal already has a report for this hash - skipping the upload."
    } else {
        if ($file.Length -le $directUploadLimit) {
            $uploadUrl = "$api/files"
        } else {
            # A single-use signed URL (bigfiles.virustotal.com), good for files up to
            # 650 MB. VirusTotal does not document whether this is public-tier or
            # premium-only - verified 2026-08-19 against a free Public API key, it works.
            # The premium counterpart is a separate endpoint, /private/files/upload_url.
            $uploadUrl = (Invoke-Vt "$api/files/upload_url").data
            Write-Host "Artifact exceeds 32 MB - using a one-shot upload URL."
        }
        Write-Host "Uploading..."
        $watch = [Diagnostics.Stopwatch]::StartNew()
        $response = Send-MultipartFile $uploadUrl $file
        Write-Host "Uploaded in $([math]::Round($watch.Elapsed.TotalSeconds, 1))s."
        if (-not $response.Ok) {
            # Status 0 means curl never got an HTTP answer at all - report its exit code.
            $why = if ($response.Status) { "HTTP $($response.Status) - $($response.Body)" }
                   else { "curl exit $($response.CurlExit)" }
            Stop-WithoutNote "Upload rejected ($why)" $sha256
        }
        $analysisId = ($response.Body | ConvertFrom-Json).data.id
        Write-Host "Analysis : $analysisId"
    }
} catch {
    # $_ must be captured BEFORE the switch: inside it, $_ is the switch's input (the
    # status code), so reading $_.Exception there yields nothing and the real cause is
    # lost. Elapsed time separates "rejected instantly" from "uploaded, then refused".
    $err = $_
    $status = Get-HttpStatus $err
    # VirusTotal returns its error JSON as the response body, which lands in ErrorDetails.
    $detail = if ($err.ErrorDetails.Message) { $err.ErrorDetails.Message } else { $err.Exception.Message }
    $hint = switch ($status) {
        401     { "HTTP 401 - the API key was rejected" }
        429     { "HTTP 429 - the public-API quota (500/day, 4/min) is exhausted" }
        0       { "$($err.Exception.GetType().Name): $detail" }
        default { "HTTP $status - $detail" }
    }
    if ($watch) { $hint += " (after $([math]::Round($watch.Elapsed.TotalSeconds, 1))s)" }
    Stop-WithoutNote "Upload failed ($hint)" $sha256
}

# --- 4. Wait for the verdict ----------------------------------------------------------
$stats = $null
if ($report) {
    $stats = $report.data.attributes.last_analysis_stats
} else {
    $deadline = (Get-Date).AddMinutes($TimeoutMinutes)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Seconds $PollSeconds
        try { $analysis = Invoke-Vt "$api/analyses/$analysisId" }
        catch {
            # A transient poll failure is not worth losing the whole scan over.
            Write-Host "  poll failed ($($_.Exception.Message)) - retrying."
            continue
        }
        Write-Host "  status: $($analysis.data.attributes.status)"
        if ($analysis.data.attributes.status -eq 'completed') {
            $stats = $analysis.data.attributes.stats
            break
        }
    }
}

# --- 5. Render the release-notes section ----------------------------------------------
# English on purpose: everything on GitHub - issues, pull requests, releases - is English.
# (The GUI is the only German-facing surface, and it goes through the i18n catalogue;
# the README has been English since 2026-08-19. See CLAUDE.md.)
#
# No file name in the hint: GitHub renames release assets (spaces become dots), so the
# name in dist\ is not the name the user ends up with in their Downloads folder.
$verify = "The hash belongs to exactly the MSI attached to this release - check it locally with ``Get-FileHash <your-file>.msi``."

if (-not $stats) {
    Write-Host "::warning title=VirusTotal::Analysis still running after $TimeoutMinutes min - linking the pending report."
    $state = "pending"
    $note = @"
## Security check

The MSI was uploaded to VirusTotal straight after the CI build; the analysis was still
running when these release notes were written. Result: **[VirusTotal report]($permalink)**

SHA-256: ``$sha256``

$verify
"@
} else {
    $flagged = [int]$stats.malicious + [int]$stats.suspicious
    $engines = $flagged + [int]$stats.undetected + [int]$stats.harmless
    $state = if ($flagged -eq 0) { "clean" } else { "flagged" }
    Write-Host "Verdict  : $flagged / $engines engines flagged the file."

    $headline = if ($flagged -eq 0) {
        "The MSI was scanned on VirusTotal automatically, straight after the CI build: **0 of $engines engines** flag it."
    } else {
        "The MSI was scanned on VirusTotal automatically, straight after the CI build: **$flagged of $engines engines** report a hit."
    }
    $caveat = if ($flagged -eq 0) { "" } else { @"


Individual hits on an unsigned installer are routinely false positives - typically
``Wacatac``/``!ml`` from Microsoft Defender, because the MSI carries no Authenticode
signature and is a brand-new, unknown file on every release. The report shows which
engine says what.
"@ }

    $note = @"
## Security check

$headline

- Report: **[VirusTotal]($permalink)**
- SHA-256: ``$sha256``

$verify$caveat
"@
}

Write-Outputs @{
    status       = $state
    sha256       = $sha256
    permalink    = $permalink
    release_note = $note
}
