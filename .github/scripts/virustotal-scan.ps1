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
        $submission = Invoke-Vt $uploadUrl -Method Post -Form @{ file = $file } -TimeoutSec 900
        $analysisId = $submission.data.id
        Write-Host "Analysis : $analysisId"
    }
} catch {
    $hint = switch (Get-HttpStatus $_) {
        401     { "the API key was rejected" }
        429     { "the public-API quota (500/day, 4/min) is exhausted" }
        default { $_.Exception.Message }
    }
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
# No file name in the hint: GitHub renames release assets (spaces become dots), so the
# name in dist\ is not the name the user ends up with in their Downloads folder.
$verify = "Der Hash gehört zu genau der MSI, die an diesem Release hängt - lokal prüfbar mit ``Get-FileHash <deine-datei>.msi``."

if (-not $stats) {
    Write-Host "::warning title=VirusTotal::Analysis still running after $TimeoutMinutes min - linking the pending report."
    $state = "pending"
    $note = @"
## Sicherheitsprüfung

Die MSI wurde direkt nach dem CI-Build zu VirusTotal hochgeladen; beim Erstellen dieser
Release-Notes lief die Analyse noch. Ergebnis: **[VirusTotal-Bericht]($permalink)**

SHA-256: ``$sha256``

$verify
"@
} else {
    $flagged = [int]$stats.malicious + [int]$stats.suspicious
    $engines = $flagged + [int]$stats.undetected + [int]$stats.harmless
    $state = if ($flagged -eq 0) { "clean" } else { "flagged" }
    Write-Host "Verdict  : $flagged / $engines engines flagged the file."

    $headline = if ($flagged -eq 0) {
        "Die MSI wurde direkt nach dem CI-Build automatisch bei VirusTotal geprüft: **0 von $engines Engines** schlagen an."
    } else {
        "Die MSI wurde direkt nach dem CI-Build automatisch bei VirusTotal geprüft: **$flagged von $engines Engines** melden einen Treffer."
    }
    $caveat = if ($flagged -eq 0) { "" } else { @"


Einzelne Treffer sind bei unsignierten Installern regelmäßig Fehlalarme - typisch
``Wacatac``/``!ml`` bei Microsoft Defender, weil die MSI keine Authenticode-Signatur trägt
und bei jedem Release neu und unbekannt ist. Der Bericht zeigt, welche Engine was meldet.
"@ }

    $note = @"
## Sicherheitsprüfung

$headline

- Bericht: **[VirusTotal]($permalink)**
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
