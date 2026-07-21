package com.basetool.bpextractor.ui.i18n

import androidx.compose.runtime.staticCompositionLocalOf

/** The two UI languages: German is the default, English has full parity (design spec §6). */
enum class Lang { DE, EN }

/**
 * Strings for the one-click "An Basetool senden" flow (epic krt-profit/basetool#639). A cohesive
 * sub-surface kept in its own holder (accessed via `strings.send`). Originally extracted to shrink
 * the old flat [Strings] constructor; now that [Strings] is a constructor-less interface the
 * grouping is optional, but it stays for organisation.
 */
class SendStrings(
    val button: String,
    val consentTitle: String,
    val consentBody: String,
    val consentConfirm: String,
    val authTitle: String,
    val authBody: String,
    val authCode: (String) -> String,
    val authOpenBrowser: String,
    val waiting: String,
    val inProgress: String,
    val resultTitle: String,
    val resultBody: String,
    val openInBasetool: String,
    val saveLocally: String,
    val error: (String) -> String,
)

/**
 * Strings for the "remember me" account surface (epic krt-profit/basetool#639, sub-issue #648).
 * An optional organisational holder like [SendStrings] (accessed via `strings.account`).
 */
class AccountStrings(
    val connected: String,
    val disconnected: String,
    val disconnect: String,
    val disconnectTitle: String,
    val disconnectBody: String,
    val disconnectConfirm: String,
)

/**
 * Every user-facing UI string of the app, one property (or formatter) per string. The design spec
 * (DESIGN_SC_EXTRACTOR.md §6) demands German default with full English parity and a title-bar
 * DE/EN toggle, so strings live in this lightweight catalogue instead of being hardcoded at the
 * call sites. Parameterised messages are lambdas so call sites stay type-safe without a template
 * engine. Brand names (app title, workflow product names) stay identical across languages.
 *
 * **Why an interface, not a data class — do NOT change this back.** A single constructor (or any
 * method) may take at most 254 value parameters; a flat `class Strings(val …: String, …)` exceeds
 * that once the catalogue grows, and the JVM rejects the class at LOAD time with
 * `ClassFormatError: Too many arguments` — which the compiler and unit tests do NOT catch (only
 * launching the GUI does). Modelling the catalogue as an interface with abstract vals removes the
 * constructor entirely, so that limit can never be hit; each [StringsDe]/[StringsEn] `object`
 * initialises its fields in `<init>`, bounded only by the 64 KB method-size limit (thousands of
 * strings away). The earlier [SendStrings]/[AccountStrings] grouping was a stop-gap for the old
 * constructor and is no longer required — keep it only if a sub-surface is genuinely cohesive.
 *
 * **To add a string:** add `val name: Type` here, then provide `override val name = "…"` in BOTH
 * [StringsDe] and [StringsEn] (full parity is the contract). Function-typed entries need the
 * explicit type on the override, e.g. `override val foo: (Int) -> String = { n -> "…" }`, because
 * Kotlin will not infer a lambda's parameter types from the overridden member.
 */
interface Strings {
    // --- tabs / shell ---
    val tabStart: String
    val tabBlueprints: String
    val tabRefinery: String
    val bpStepOverline: (Int) -> String
    val rfStepOverline: (Int) -> String
    val cancel: String
    val close: String
    val footerManual: String

    // --- start screen (launcher) ---
    val startTitle: String
    val startSubtitle: String
    val startChooseWorkflow: String
    val startLocalNote: String
    val startOpen: String
    val bpCardTitle: String
    val bpCardDesc: String
    val bpCardInputHint: String
    val bpCardBullets: List<String>
    val rfCardTitle: String
    val rfCardDesc: String
    val rfCardInputHint: String
    val rfCardBullets: List<String>
    val unofficialChip: String

    // --- update check (start-screen banner) ---
    val updTitle: String
    val updBody: (String, String) -> String
    val updSize: (String) -> String
    val updInstall: String
    val updLater: String
    val updDownloading: String
    val updInstalling: String
    val updFailed: (String) -> String
    val updRetry: String

    // --- blueprint workflow ---
    val bpSteps: List<String>
    val bpAgain: String
    val bpCtaExport: String
    val bpConfigSubtitle: String
    val bpRunningTitle: String
    val bpSummaryTitle: String
    val bpFootReadOnly: String
    val bpFootAnchored: String
    val bpCtxTitle: String
    val bpCtxItems: List<String>
    val bpCtxLastRun: String
    val bpCtxNoRun: String
    val bpSumSuccessTitle: String
    val bpSumSuccessDetail: (Int, Int) -> String
    val bpLabelChannelFolder: String
    val bpPlaceholderChannel: String
    val bpLabelOutputJson: String
    val bpPlaceholderOutput: String
    val browse: String
    val bpPickerChannelTitle: String
    val bpPickerChannelConfirm: String
    val bpPickerSaveTitle: String
    val bpPickerSaveConfirm: String
    val bpCta: String
    val bpHintReadsLogs: String
    val bpHintFolderMissing: String
    val bpHintWrongFolder: String
    val bpHintValidFolder: (String) -> String
    val bpHotfixNote: String
    val bpErrSelectChannel: String
    val bpErrFolderNotFound: (String) -> String
    val bpErrSelectOutput: String
    val bpErrOutputIsFolder: String
    val bpErrOutputParentNotWritable: String
    val bpErrOutputFileReadOnly: String
    val bpStatusFixFields: String
    val bpStatusSearching: String
    val bpStatusEvaluating: String
    val bpStatusProcessing: (Int, Int, String) -> String
    val bpStatusNoLogs: String
    val bpStatusAllSkipped: (Int) -> String
    val bpSkippedNote: (Int, String) -> String
    val bpToastNoLogsTitle: String
    val bpToastNoLogsBody: String
    val bpStatusDone: (Int, Int, String) -> String
    val bpToastDoneTitle: String
    val bpToastDoneBody: (Int) -> String
    val bpStatusError: (String) -> String
    val bpToastErrorTitle: String
    val unknownError: String
    val bpShowInFolder: String
    val bpOpenJson: String
    val cannotOpen: (String, String) -> String
    val bpSummaryPlayers: String
    val bpSummaryNoPlayer: String
    val bpSummaryByCategory: String
    val bpSummaryRecent: String

    // --- refinery workflow ---
    val rfSteps: List<String>
    val back: String

    // §5.1 Vorprüfung
    val rfPreflightTitle: String
    val rfPreflightSubtitle: String
    val rfOllamaCardTitle: String
    val rfEndpointLabel: String
    val rfModelLabel: String
    val rfChecking: String
    val rfOllamaReady: String
    val rfOllamaModelMissing: (String) -> String
    val rfPullCta: String
    val rfPullProgress: (String) -> String
    val rfPullFailed: (String) -> String
    val rfOllamaUnreachable: String
    val rfInstallHint1: String
    val rfInstallHint2: String
    val rfRetry: String
    val rfHardwareCardTitle: String
    val rfGpuRow: String
    val rfVramRow: String
    val rfRamRow: String
    val rfUnknown: String
    val rfAutoModelChip: (String) -> String
    val rfTierCpu: String
    val rfTierMin: String
    val rfTierRecommended: String
    val rfTierAboveRecommended: String
    val rfTierMinimumInfo: String
    val rfTierBelowMinimum: String
    val rfFallbackLowVram: (String) -> String
    val rfFallbackCpu: (Int) -> String
    val rfScRunningWarning: String
    val rfScAcknowledge: String
    val rfScNotRunning: String
    val rfThrottleNote: String
    val rfEtaPerImage: (Int) -> String
    val rfCtaToImages: String

    // §4.3a Hilfeseite (Vorprüfung)
    val help: String
    val helpTitle: String
    val helpSubtitle: String
    val helpSec1Title: String
    val helpSec1Body: String
    val helpSec2Title: String
    val helpSec2Body: String
    val helpTierRows: List<List<String>>
    val helpSec3Title: String
    val helpStep1Title: String
    val helpStep1Body: String
    val helpStep2Title: String
    val helpStep2Body: String
    val helpStep3Title: String
    val helpStep3Body: String
    val helpInfoTitle: String
    val helpInfoBody: String
    val helpSec4Title: String
    val helpTips: List<List<String>>
    val helpMore: String
    val helpGotIt: String

    // §5.2 Bilder
    val rfImagesTitle: String
    val rfImagesSubtitle: String
    val rfFolderLabel: String
    val rfPickFolder: String
    val rfPickerImagesTitle: String
    val rfPickerImagesConfirm: String
    val rfNoImagesInFolder: String
    val rfStatImages: String
    val rfStatSelected: String
    val rfStatOrder: String
    val rfStatResolution: String
    val rfStatModel: String
    val rfSelectAll: String
    val rfDeselectAll: String
    val rfCropTagAuto: String
    val rfCropTagPre: String
    val rfPasteDropHint: String
    val rfCaptureAberrationTitle: String
    val rfCaptureAberrationHint: String
    val rfCaptureFramingTitle: String
    val rfCaptureFramingHint: String
    val rfLowResNote: (Int) -> String
    val rfTempNote: String
    val rfCtaStartExtraction: String

    // §5.3 Extraktion
    val rfExtractTitle: String
    val rfImageOf: (Int, Int) -> String
    val rfConsoleTitle: String
    val rfCancel: String
    val rfCancelled: String
    val rfUnquotedWarning: String
    val rfAllUnquotedNotice: String
    val rfExtractionFailed: (String) -> String
    val rfEtaRemaining: (Int) -> String
    val rfCtaToReview: String
    val rfCtaToExport: String

    // §5.4 Review
    val rfReviewTitle: String
    val rfReviewSubtitle: String
    val rfBadgeLayout: (Int) -> String
    val rfFlaggedWarnings: (Int) -> String
    val rfNoFlags: String
    val rfHdrLocation: String
    val rfHdrMethod: String
    val rfHdrCost: String
    val rfHdrDuration: String
    val rfMissingValue: String
    val rfColMaterial: String
    val rfColQuality: String
    val rfColInput: String
    val rfColYield: String
    val rfColRefine: String
    val rfColConfidence: String
    val rfWarningLabel: (String) -> String
    val rfManualNote: String
    val rfRecaptureHint: String
    val rfEditRow: String
    val rfEditApply: String
    val rfEditCancel: String
    val rfEditRevert: String
    val rfEditedTag: String
    val rfUnsavedTitle: String
    val rfUnsavedBody: String
    val rfUnsavedDiscard: String
    val rfUnsavedBack: String
    val rfWarningResolved: String
    val rfAllWarningsResolved: String
    val rfCtaExport: String
    val rfPickerExportTitle: String
    val rfPickerExportConfirm: String
    val rfExportFailed: (String) -> String

    // §5.5 Export
    val rfExportTitle: String
    val rfExportSuccess: (String) -> String
    /** Blocks the send when rows carry no quantity (the basetool ingest edge rejects a null qty). */
    val rfSendBlockedMissingQty: (Int) -> String
    val rfUploadCardTitle: String
    val rfUploadSteps: List<String>
    val rfProvenanceTitle: String
    val rfProvTool: String
    val rfProvVersion: String
    val rfProvModel: String
    val rfProvSchema: String
    val rfProvPanel: String
    val rfProvGenerated: String
    val rfNewExtraction: String

    // --- KRT file picker (§10) ---
    val pickerComputer: String
    val pickerParentFolder: String
    val pickerFilter: String
    val pickerNewFolder: String
    val pickerCreate: String
    val pickerQuickAccess: String
    val pickerDrives: String
    val pickerHome: String
    val pickerDocuments: String
    val pickerDesktop: String
    val pickerDownloads: String
    val pickerColName: String
    val pickerColSize: String
    val pickerColModified: String
    val pickerFolderType: String
    val pickerEmpty: String
    val pickerNoMatch: String
    val pickerDenied: String
    val pickerLoading: String
    val pickerShowing: (String) -> String
    val pickerSelected: String
    val pickerFileName: String
    val pickerOverwrite: String
    val pickerInvalidName: String
    val pickerCreateFailed: String
    val pickerPathPlaceholder: String
    val pickerPathNotFound: String
    val pickerClearPath: String
    // --- grouped holders: kept off the flat constructor to stay under the JVM 255-arg limit ---
    val send: SendStrings
    val account: AccountStrings
}

/** German catalogue — the default language. */
object StringsDe : Strings {
    override val tabStart = "Start"
    override val tabBlueprints = "Blueprints"
    override val tabRefinery = "Refinery"
    override val bpStepOverline: (Int) -> String = { n -> "Blueprints · Schritt $n / 3" }
    override val rfStepOverline: (Int) -> String = { n -> "Refinery · Schritt $n / 5" }
    override val cancel = "Abbrechen"
    override val close = "Schließen"
    override val footerManual = "Handbuch"

    override val startTitle = "Basetool SC Extractor"
    override val startSubtitle = "Liest Star-Citizen-Daten lokal aus und erzeugt JSON-Dateien für das Basetool — gesendet wird nur, wenn du es auslöst."
    override val startChooseWorkflow = "Workflow wählen"
    override val startLocalNote = "Beide Workflows laufen lokal; Daten verlassen den Rechner nur, wenn du sie ans Basetool sendest."
    override val startOpen = "Öffnen →"
    override val bpCardTitle = "Blueprints"
    override val bpCardDesc = "Liest die erhaltenen Blueprints aus den Game.log-Dateien aus und schreibt sie als JSON."
    override val bpCardInputHint = "Game.log"
    override val bpCardBullets = listOf(
        "Eingabe: Game.log + logbackups",
        "Ausgabe: blueprints.json",
        "Nur Lesezugriff · keine KI nötig",
    )
    override val rfCardTitle = "Refinery"
    override val rfCardDesc = "Liest Raffinerie-Auftragsdaten aus SETUP-Screenshots per lokalem KI-Modell (Ollama) aus und erzeugt eine RefineryExtract-JSON."
    override val rfCardInputHint = "Screenshots + Ollama"
    override val rfCardBullets = listOf(
        "Eingabe: Screenshots (ein Ordner = ein Auftrag)",
        "Ausgabe: RefineryExtract.json",
        "Läuft lokal über Ollama · kein automatischer Upload",
    )
    override val unofficialChip = "Inoffizielles Fan-Tool"

    override val updTitle = "Update verfügbar"
    override val updBody: (String, String) -> String = { new, current -> "Version $new ist auf GitHub veröffentlicht — installiert ist v$current." }
    override val updSize: (String) -> String = { mb -> "≈ $mb MB" }
    override val updInstall = "Herunterladen & installieren"
    override val updLater = "Später"
    override val updDownloading = "Update wird heruntergeladen…"
    override val updInstalling = "Installer gestartet — die App wird jetzt beendet und startet nach dem Update automatisch neu. Die Update-Datei wird danach automatisch gelöscht."
    override val updFailed: (String) -> String = { msg -> "Update fehlgeschlagen: $msg" }
    override val updRetry = "Erneut versuchen"

    override val bpSteps = listOf("Konfiguration", "Extraktion", "Zusammenf.")
    override val bpAgain = "Erneut"
    override val bpCtaExport = "Als JSON exportieren"
    override val bpConfigSubtitle = "Quelle und Ziel wählen, dann extrahieren."
    override val bpRunningTitle = "Extraktion läuft"
    override val bpSummaryTitle = "Zusammenfassung"
    override val bpFootReadOnly = "Nur Lesezugriff · gesendet wird nur auf deinen Knopfdruck."
    override val bpFootAnchored = "Verankert auf „Added notification\" — jedes Blueprint wird genau einmal gezählt."
    override val bpCtxTitle = "Was gelesen wird"
    override val bpCtxItems = listOf(
        "Game.log im Channel-Ordner",
        "Alle logbackups\\*.log (plus HOTFIX-Ordner daneben)",
        "Nur Blueprint-Notifications — sonst nichts",
    )
    override val bpCtxLastRun = "Letzter Lauf"
    override val bpCtxNoRun = "Noch kein Lauf in dieser Sitzung."
    override val bpSumSuccessTitle = "Export erfolgreich geschrieben"
    override val bpSumSuccessDetail: (Int, Int) -> String = { count, files -> "$count Blueprint(s) · $files Log(s) gescannt" }
    override val bpLabelChannelFolder = "Star-Citizen-Channel-Ordner"
    override val bpPlaceholderChannel = "z. B. C:\\Program Files\\Roberts Space Industries\\StarCitizen\\LIVE"
    override val bpLabelOutputJson = "Ausgabe-JSON (Ziel)"
    override val bpPlaceholderOutput = "z. B. …\\Dokumente\\blueprints.json"
    override val browse = "Durchsuchen…"
    override val bpPickerChannelTitle = "Channel-Ordner wählen"
    override val bpPickerChannelConfirm = "Diesen Ordner wählen"
    override val bpPickerSaveTitle = "JSON-Ausgabedatei wählen"
    override val bpPickerSaveConfirm = "Speichern"
    override val bpCta = "Blueprints extrahieren"
    override val bpHintReadsLogs = "Liest die Game.log in diesem Ordner und alle Logs im Unterordner „logbackups\"."
    override val bpHintFolderMissing = "Ordner existiert nicht."
    override val bpHintWrongFolder = "Ordner gefunden, aber keine Game.log/logbackups — evtl. der falsche Ordner."
    override val bpHintValidFolder: (String) -> String = { found -> "Gültiger Channel-Ordner ($found erkannt)." }
    override val bpHotfixNote = "HOTFIX-Ordner daneben gefunden — dessen Logs werden zusätzlich ausgelesen."
    override val bpErrSelectChannel = "Bitte einen Channel-Ordner auswählen."
    override val bpErrFolderNotFound: (String) -> String = { path -> "Ordner nicht gefunden: $path" }
    override val bpErrSelectOutput = "Bitte einen Ziel-Pfad für die JSON angeben."
    override val bpErrOutputIsFolder = "Der Ziel-Pfad ist ein Ordner — bitte einen Dateinamen angeben."
    override val bpErrOutputParentNotWritable = "Der Ziel-Ordner kann nicht angelegt oder beschrieben werden."
    override val bpErrOutputFileReadOnly = "Die Ziel-Datei ist schreibgeschützt."
    override val bpStatusFixFields = "Bitte die markierten Felder korrigieren."
    override val bpStatusSearching = "Suche Log-Dateien…"
    override val bpStatusEvaluating = "Werte aus…"
    override val bpStatusProcessing: (Int, Int, String) -> String = { done, total, label -> "Verarbeite Datei $done/$total: $label" }
    override val bpStatusNoLogs = "Keine Game.log und kein „logbackups\"-Ordner im Channel-Ordner gefunden."
    override val bpStatusAllSkipped: (Int) -> String = { count -> "Keine Log-Datei lesbar — $count Datei(en) übersprungen." }
    override val bpSkippedNote: (Int, String) -> String = { count, names -> "$count Log(s) übersprungen (nicht lesbar): $names" }
    override val bpToastNoLogsTitle = "Keine Logs gefunden"
    override val bpToastNoLogsBody = "Im Channel-Ordner wurde keine Game.log gefunden."
    override val bpStatusDone: (Int, Int, String) -> String = { count, files, path -> "Fertig: $count Blueprint(s) aus $files Datei(en) geschrieben nach $path" }
    override val bpToastDoneTitle = "Fertig"
    override val bpToastDoneBody: (Int) -> String = { count -> "$count Blueprint(s) gespeichert." }
    override val bpStatusError: (String) -> String = { msg -> "Fehler: $msg" }
    override val bpToastErrorTitle = "Fehler"
    override val unknownError = "Unbekannter Fehler"
    override val bpShowInFolder = "Im Ordner anzeigen"
    override val bpOpenJson = "JSON öffnen"
    override val cannotOpen: (String, String) -> String = { name, msg -> "Konnte „$name\" nicht öffnen: $msg" }
    override val bpSummaryPlayers = "Spieler:"
    override val bpSummaryNoPlayer = "(keiner erkannt)"
    override val bpSummaryByCategory = "Blueprints nach Kategorie:"
    override val bpSummaryRecent = "Letzte erhaltene Blueprints:"

    override val rfSteps = listOf("Vorprüfung", "Bilder", "Extraktion", "Review", "Export")
    override val back = "Zurück"

    override val rfPreflightTitle = "Vorprüfung & Setup"
    override val rfPreflightSubtitle = "Prüft Ollama, das KI-Modell und die Hardware, bevor die Extraktion startet."
    override val rfOllamaCardTitle = "Ollama-Laufzeit"
    override val rfEndpointLabel = "Endpoint"
    override val rfModelLabel = "Modell"
    override val rfChecking = "Prüfe…"
    override val rfOllamaReady = "Erreichbar · Modell vorhanden · bereit."
    override val rfOllamaModelMissing: (String) -> String = { model -> "Modell „$model\" ist nicht installiert." }
    override val rfPullCta = "Laden"
    override val rfPullProgress: (String) -> String = { status -> "Lade Modell… $status" }
    override val rfPullFailed: (String) -> String = { msg -> "Modell-Download fehlgeschlagen: $msg" }
    override val rfOllamaUnreachable = "Ollama ist nicht erreichbar."
    override val rfInstallHint1 = "1. Ollama von ollama.com herunterladen und installieren."
    override val rfInstallHint2 = "2. „ollama serve\" starten (Standard-Port 11434), dann erneut prüfen."
    override val rfRetry = "Erneut prüfen"
    override val rfHardwareCardTitle = "Hardware-Vorprüfung"
    override val rfGpuRow = "GPU"
    override val rfVramRow = "VRAM"
    override val rfRamRow = "RAM"
    override val rfUnknown = "unbekannt"
    override val rfAutoModelChip: (String) -> String = { model -> "Auto-Auswahl: $model" }
    override val rfTierCpu = "CPU"
    override val rfTierMin = "MIN 8 GB"
    override val rfTierRecommended = "EMPF 12 GB+"
    override val rfTierAboveRecommended = "Empfohlene Stufe erreicht — volle Genauigkeit auf der GPU."
    override val rfTierMinimumInfo = "8-GB-Stufe: kompaktes Fallback-Modell (gleiche validierte Genauigkeit)."
    override val rfTierBelowMinimum = "Unter der Mindeststufe — Fallback wählen:"
    override val rfFallbackLowVram: (String) -> String = { model -> "Low-VRAM-Modell ($model)" }
    override val rfFallbackCpu: (Int) -> String = { eta -> "CPU-Modus — funktioniert, aber langsam (≈ ${eta}s pro Bild)" }
    override val rfScRunningWarning = "Star Citizen läuft — VLM und Spiel teilen sich GPU/VRAM. Für eine sichere Extraktion SC schließen."
    override val rfScAcknowledge = "Trotzdem fortfahren"
    override val rfScNotRunning = "Star Citizen läuft nicht — GPU frei."
    override val rfThrottleNote = "Drosselung aktiv · ein Bild nach dem anderen."
    override val rfEtaPerImage: (Int) -> String = { s -> "≈ ${s}s pro Bild" }
    override val rfCtaToImages = "Weiter: Bilder laden"

    override val help = "Hilfe"
    override val helpTitle = "Hilfe — Voraussetzungen & Installation"
    override val helpSubtitle = "Was die Bilderkennung braucht und wie du die Software einrichtest."
    override val helpSec1Title = "So funktioniert die Bilderkennung"
    override val helpSec1Body = "Deine Raffinerie-Screenshots werden von einem lokalen KI-Vision-Modell (VLM) über Ollama ausgewertet. Es läuft komplett auf deinem Rechner — es werden zu keinem Zeitpunkt Bilder hochgeladen."
    override val helpSec2Title = "Hardware-Voraussetzungen"
    override val helpSec2Body = "Die App erkennt deine GPU automatisch und wählt die passende Stufe vor. Mehr VRAM = größeres Modell = höhere Genauigkeit und Tempo."
    override val helpTierRows = listOf(
        listOf("Empfohlen", "≥ 12 GB VRAM", "qwen3-vl:8b", "≈ 4–5 s / Bild"),
        listOf("Minimum", "≥ 8 GB VRAM", "qwen3-vl:4b", "≈ 4 s / Bild"),
        listOf("Darunter", "CPU-Modus", "qwen3-vl:4b", "≈ 30 s / Bild (langsam)"),
    )
    override val helpSec3Title = "Software installieren (Ollama)"
    override val helpStep1Title = "Ollama herunterladen & installieren"
    override val helpStep1Body = "Lade Ollama von der offiziellen Seite und installiere es."
    override val helpStep2Title = "Ollama-Dienst starten"
    override val helpStep2Body = "Startet den lokalen Dienst auf Port 11434 (läuft nach der Installation meist automatisch)."
    override val helpStep3Title = "Vision-Modell laden"
    override val helpStep3Body = "Lädt das Modell einmalig herunter (~6 GB). Oder einfach die App machen lassen — die Vorprüfung erkennt ein fehlendes Modell und lädt es auf Klick mit Fortschrittsanzeige."
    override val helpInfoTitle = "Kein separates Java/Python nötig"
    override val helpInfoBody = "Die App bringt ihre Laufzeit mit. Nur Ollama + das Modell sind extern."
    override val helpSec4Title = "Tipps für gute Ergebnisse"
    override val helpTips = listOf(
        listOf("Star Citizen vorher schließen", "VLM und Spiel teilen sich GPU/VRAM — bei laufendem SC drohen Ruckler und langsame Extraktion."),
        listOf("Erst „GET QUOTE\", dann Screenshot", "Vor der Quote zeigt das Panel keine Ausbeute, Kosten und Dauer — solche Aufnahmen werden als unvollständig markiert."),
        listOf("1 Ordner = 1 Auftrag", "Alle Screenshots eines Auftrags (auch gescrollte Teilbilder) in einen Ordner legen — die App fügt sie zusammen."),
        listOf("Auflösung 1080p bis 8K", "Auch Ultrawide; alternativ ein bereits manuell zugeschnittenes Panel-Bild (wird als „vorgecroppt\" erkannt)."),
    )
    override val helpMore = "Mehr unter ollama.com"
    override val helpGotIt = "Verstanden"

    override val rfImagesTitle = "Bilder laden"
    override val rfImagesSubtitle = "1 Ordner = 1 Auftrag — alle Screenshots desselben Auftrags in einen Ordner legen. Vor dem Aufnehmen im Spiel GET QUOTE drücken."
    override val rfFolderLabel = "Screenshot-Ordner"
    override val rfPickFolder = "Ordner wählen"
    override val rfPickerImagesTitle = "Screenshot-Ordner wählen"
    override val rfPickerImagesConfirm = "Diesen Ordner wählen"
    override val rfNoImagesInFolder = "Keine PNG/JPG-Bilder in diesem Ordner gefunden."
    override val rfStatImages = "Bilder"
    override val rfStatSelected = "Ausgewählt"
    override val rfStatOrder = "Auftrag"
    override val rfStatResolution = "Auflösung"
    override val rfStatModel = "Modell"
    override val rfSelectAll = "Alle auswählen"
    override val rfDeselectAll = "Alle abwählen"
    override val rfCropTagAuto = "vlm"
    override val rfCropTagPre = "vorgecroppt"
    override val rfPasteDropHint = "Strg+V fügt ein Bild aus der Zwischenablage ein (z. B. Snipping Tool) — Bilder lassen sich auch per Drag & Drop hierher ziehen."
    override val rfCaptureAberrationTitle = "Chromatische Aberration vor der Aufnahme ausschalten"
    override val rfCaptureAberrationHint = "Den Regler in den Star-Citizen-Grafikeinstellungen auf 0 stellen, bevor die Screenshots entstehen — die Farbsäume machen Ziffern für das Modell mehrdeutig und sind nachträglich nicht entfernbar."
    override val rfCaptureFramingTitle = "Frontal und in höchster Auflösung aufnehmen"
    override val rfCaptureFramingHint = "Gerade auf den Bildschirm blicken (nicht schräg/seitlich) und in der höchstmöglichen Monitorauflösung aufnehmen — verzerrte oder niedrig aufgelöste Ziffern liest das Modell unzuverlässig. 4K ist nicht nötig, aber je höher die Auflösung, desto sicherer."
    override val rfLowResNote: (Int) -> String = { n -> "$n Bild(er) niedrig aufgelöst (kein Vollbild) — für sichere Ziffern als Vollbild in höchster Auflösung erneut aufnehmen." }
    override val rfTempNote = "Eingefügte Bilder ohne gewählten Ordner liegen in einem temporären Ordner und werden beim Beenden gelöscht."
    override val rfCtaStartExtraction = "Extraktion starten"

    override val rfExtractTitle = "Extraktion"
    override val rfImageOf: (Int, Int) -> String = { done, total -> "Bild $done / $total" }
    override val rfConsoleTitle = "Konsole"
    override val rfCancel = "Abbrechen"
    override val rfCancelled = "Extraktion abgebrochen."
    override val rfUnquotedWarning = "Screenshot vor GET QUOTE aufgenommen — Ausbeute/Kosten fehlen. Im Spiel GET QUOTE drücken und erneut aufnehmen."
    override val rfAllUnquotedNotice = "Alle Bilder sind im GET-QUOTE-Zustand — der Export wird im Basetool als Auftrag ohne Quote markiert."
    override val rfExtractionFailed: (String) -> String = { msg -> "Extraktion fehlgeschlagen: $msg" }
    override val rfEtaRemaining: (Int) -> String = { s -> "≈ ${s}s verbleibend" }
    override val rfCtaToReview = "Weiter: Review"
    override val rfCtaToExport = "Weiter zum Export"

    override val rfReviewTitle = "Review & Bestätigung"
    override val rfReviewSubtitle = "Extrahierte Werte prüfen und bei Bedarf korrigieren (✎) — gespeichert wird erst beim Import im Basetool."
    override val rfBadgeLayout: (Int) -> String = { pct -> "Layout $pct %" }
    override val rfFlaggedWarnings: (Int) -> String = { n -> "$n Auffälligkeit(en) — bitte prüfen." }
    override val rfNoFlags = "Keine Auffälligkeiten."
    override val rfHdrLocation = "Standort"
    override val rfHdrMethod = "Methode"
    override val rfHdrCost = "Gesamtkosten"
    override val rfHdrDuration = "Dauer"
    override val rfMissingValue = "fehlt"
    override val rfColMaterial = "Material"
    override val rfColQuality = "Qualität"
    override val rfColInput = "Input"
    override val rfColYield = "Ausbeute"
    override val rfColRefine = "Refine"
    override val rfColConfidence = "Konfidenz"
    override val rfWarningLabel: (String) -> String = { warning ->
        when (warning) {
            "UNQUOTED_ORDER" -> "Auftrag ohne Quote (vor GET QUOTE aufgenommen)"
            "SUM_MISMATCH" -> "Sichtbare Mengen übersteigen den TO-REFINE-Gesamtwert"
            "IMPLAUSIBLE_CELL" -> "Mindestens eine Zelle ist unplausibel (nicht numerisch)"
            "REFINE_CORRECTED" -> "Refine-Schalter anhand der YIELD-Spalte korrigiert"
            "VERIFY_CORRECTED" -> "Zweitmodell-Abgleich: Menge per TO-REFINE-Checksumme korrigiert"
            "VERIFY_MISMATCH" -> "Zweitmodell-Abgleich: Modelle widersprechen sich — markierte Zeilen prüfen"
            "CTA_MISMATCH" -> "Button-Beschriftung widerspricht dem Quote-Status — Kopfzeile prüfen"
            "YIELD_RATIO_OUTLIER" -> "Ausbeute/Menge passt nicht zu den anderen Zeilen dieses Materials — Ziffern prüfen"
            "STITCH_CONTESTED" -> "Zeile in mehreren Screenshots unterschiedlich gelesen — Wert prüfen"
            "CHECKSUM_REPAIRED" -> "QTY-Ziffer automatisch über die TO-REFINE-Summe korrigiert — bitte prüfen"
            "YIELD_REPAIRED" -> "Ausbeute-Ziffer automatisch über die Materialrate korrigiert — bitte prüfen"
            "YIELD_OCR_REPAIRED" -> "Ausbeute-Ziffer per OCR-Abgleich (durch die Materialrate bestätigt) korrigiert — bitte prüfen"
            "OCR_CORRECTED" -> "Qualität per 8b/4b/OCR-Mehrheit korrigiert — bitte prüfen"
            "OCR_CONTESTED" -> "Qualität: Modelle/OCR widersprechen sich ohne Mehrheit — markierte Zeile prüfen"
            "QTY_OCR_CONTESTED" -> "Menge einer Refine-OFF-Zeile: OCR liest sie anders als das Modell — markierte Zeile prüfen"
            "TO_REFINE_CONTESTED" -> "TO-REFINE-Gesamtwert (Checksummen-Anker) von OCR/Zweitmodell bestritten — Kopfzeile prüfen"
            else -> warning
        }
    }
    override val rfManualNote = "Bleibt manuell — bitte im Basetool ergänzen: Besitzer · Mission · Sonstige Kosten · Erzverkäufe · Start."
    override val rfRecaptureHint = "Unsichere Werte? Mit „Zurück\" zu den Bildern, den Auftrag näher/größer und frontal erneut aufnehmen (neue Screenshots werden automatisch eingelesen) und neu extrahieren."
    override val rfEditRow = "Zeile korrigieren"
    override val rfEditApply = "Übernehmen"
    override val rfEditCancel = "Verwerfen"
    override val rfEditRevert = "Gelesenen Wert wiederherstellen"
    override val rfEditedTag = "manuell"
    override val rfUnsavedTitle = "Ungespeicherte Korrektur"
    override val rfUnsavedBody = "Eine Zeile oder ein Feld ist noch in Bearbeitung und wurde nicht mit „Übernehmen“ (✓) bestätigt. " +
        "Wenn du jetzt fortfährst, geht diese Korrektur verloren."
    override val rfUnsavedDiscard = "Verwerfen & weiter"
    override val rfUnsavedBack = "Zurück zum Bearbeiten"
    override val rfWarningResolved = "durch Korrektur behoben"
    override val rfAllWarningsResolved = "Alle Auffälligkeiten durch Korrekturen behoben."
    override val rfCtaExport = "Als JSON exportieren"
    override val rfPickerExportTitle = "RefineryExtract-JSON speichern"
    override val rfPickerExportConfirm = "Speichern"
    override val rfExportFailed: (String) -> String = { msg -> "Export fehlgeschlagen: $msg" }

    override val rfExportTitle = "Export & Upload"
    override val rfExportSuccess: (String) -> String = { path -> "RefineryExtract-JSON geschrieben: $path" }
    override val rfSendBlockedMissingQty: (Int) -> String = { n ->
        "Senden nicht möglich: Bei $n Zeile(n) konnte die Menge nicht gelesen werden. Bitte im Schritt " +
            "„Review“ die betroffenen Zeilen korrigieren (✎) oder die Screenshots in höherer Auflösung neu aufnehmen."
    }
    override val rfUploadCardTitle = "In das Basetool hochladen"
    override val rfUploadSteps = listOf(
        "1. Basetool öffnen → Refinery → Aufträge.",
        "2. „Auftrag importieren\" wählen.",
        "3. Die exportierte JSON-Datei auswählen.",
        "4. Das vorausgefüllte Formular prüfen, ergänzen und speichern.",
    )
    override val rfProvenanceTitle = "Provenienz"
    override val rfProvTool = "Tool"
    override val rfProvVersion = "Version"
    override val rfProvModel = "Modell"
    override val rfProvSchema = "Schema"
    override val rfProvPanel = "Panel"
    override val rfProvGenerated = "Erstellt"
    override val rfNewExtraction = "Neue Extraktion"

    override val pickerComputer = "Computer"
    override val pickerParentFolder = "Übergeordneter Ordner"
    override val pickerFilter = "Filtern…"
    override val pickerNewFolder = "Neuer Ordner"
    override val pickerCreate = "Anlegen"
    override val pickerQuickAccess = "Schnellzugriff"
    override val pickerDrives = "Laufwerke"
    override val pickerHome = "Home"
    override val pickerDocuments = "Dokumente"
    override val pickerDesktop = "Desktop"
    override val pickerDownloads = "Downloads"
    override val pickerColName = "Name"
    override val pickerColSize = "Größe"
    override val pickerColModified = "Geändert"
    override val pickerFolderType = "Ordner"
    override val pickerEmpty = "Dieser Ordner ist leer."
    override val pickerNoMatch = "Kein Treffer."
    override val pickerDenied = "Kein Zugriff auf diesen Ordner."
    override val pickerLoading = "Lädt…"
    override val pickerShowing: (String) -> String = { pattern -> "Anzeige: $pattern" }
    override val pickerSelected = "Ausgewählt"
    override val pickerFileName = "Dateiname"
    override val pickerOverwrite = "Datei existiert bereits — wird überschrieben."
    override val pickerInvalidName = "Ungültiger Dateiname."
    override val pickerCreateFailed = "Ordner konnte nicht angelegt werden."
    override val pickerPathPlaceholder = "Pfad eingeben oder einfügen…"
    override val pickerPathNotFound = "Pfad existiert nicht oder ist nicht erreichbar."
    override val pickerClearPath = "Pfad leeren"
    override val send =
        SendStrings(
            button = "An Basetool senden",
            consentTitle = "An Basetool senden",
            consentBody =
                "Die erzeugte JSON-Datei wird über eine verschlüsselte Verbindung an dein eigenes " +
                    "Basetool-Konto gesendet (enthält dein Spieler-Handle und die abgefragten " +
                    "Mengen/Beträge). Danach öffnet sich die Basetool-Seite mit vorausgefüllten " +
                    "Werten — gespeichert wird erst nach deiner Prüfung dort. Bilder verlassen " +
                    "deinen Rechner nie.",
            consentConfirm = "Senden",
            authTitle = "Im Browser bestätigen",
            authBody =
                "Wir haben deinen Browser geöffnet. Melde dich an (falls nötig) und bestätige den " +
                    "unten gezeigten Code, um den Versand freizugeben.",
            authCode = { code -> "Code: $code" },
            authOpenBrowser = "Browser erneut öffnen",
            waiting = "Warte auf Freigabe…",
            inProgress = "Sende an Basetool…",
            resultTitle = "Gesendet",
            resultBody =
                "Die Daten liegen in deinem Basetool bereit. Öffne die Seite, um sie zu prüfen " +
                    "und zu speichern.",
            openInBasetool = "Im Basetool öffnen",
            saveLocally = "Stattdessen als JSON speichern",
            error = { msg -> "Versand fehlgeschlagen: $msg" },
        )
    override val account =
        AccountStrings(
            connected = "Mit Basetool verbunden",
            disconnected = "Nicht mit Basetool verbunden",
            disconnect = "Vom Basetool trennen",
            disconnectTitle = "Vom Basetool trennen",
            disconnectBody =
                "Die gespeicherte Anmeldung wird zurückgezogen und vom Rechner gelöscht. Beim " +
                    "nächsten Senden wird die Freigabe erneut abgefragt.",
            disconnectConfirm = "Trennen",
        )
}

/** English catalogue — full parity with [StringsDe]. */
object StringsEn : Strings {
    override val tabStart = "Start"
    override val tabBlueprints = "Blueprints"
    override val tabRefinery = "Refinery"
    override val bpStepOverline: (Int) -> String = { n -> "Blueprints · step $n / 3" }
    override val rfStepOverline: (Int) -> String = { n -> "Refinery · step $n / 5" }
    override val cancel = "Cancel"
    override val close = "Close"
    override val footerManual = "Manual"

    override val startTitle = "Basetool SC Extractor"
    override val startSubtitle = "Extracts Star Citizen data locally and produces JSON files for the basetool — sent only when you choose to."
    override val startChooseWorkflow = "Choose a workflow"
    override val startLocalNote = "Both workflows run locally; data leaves your machine only if you send it to the basetool."
    override val startOpen = "Open →"
    override val bpCardTitle = "Blueprints"
    override val bpCardDesc = "Reads the received blueprints from the Game.log files and writes them as JSON."
    override val bpCardInputHint = "Game.log"
    override val bpCardBullets = listOf(
        "Input: Game.log + logbackups",
        "Output: blueprints.json",
        "Read-only · no AI required",
    )
    override val rfCardTitle = "Refinery"
    override val rfCardDesc = "Reads refinery work-order data from SETUP screenshots via a local AI model (Ollama) and produces a RefineryExtract JSON."
    override val rfCardInputHint = "Screenshots + Ollama"
    override val rfCardBullets = listOf(
        "Input: screenshots (one folder = one order)",
        "Output: RefineryExtract.json",
        "Runs locally via Ollama · no automatic upload",
    )
    override val unofficialChip = "Unofficial fan tool"

    override val updTitle = "Update available"
    override val updBody: (String, String) -> String = { new, current -> "Version $new has been released on GitHub — installed is v$current." }
    override val updSize: (String) -> String = { mb -> "≈ $mb MB" }
    override val updInstall = "Download & install"
    override val updLater = "Later"
    override val updDownloading = "Downloading update…"
    override val updInstalling = "Installer launched — the app will close now and reopen automatically after the update. The update file is deleted automatically afterwards."
    override val updFailed: (String) -> String = { msg -> "Update failed: $msg" }
    override val updRetry = "Try again"

    override val bpSteps = listOf("Setup", "Extract", "Summary")
    override val bpAgain = "Again"
    override val bpCtaExport = "Export as JSON"
    override val bpConfigSubtitle = "Pick source and target, then extract."
    override val bpRunningTitle = "Extraction running"
    override val bpSummaryTitle = "Summary"
    override val bpFootReadOnly = "Read-only · sent only when you choose to."
    override val bpFootAnchored = "Anchored on the \"Added notification\" line — each blueprint is counted exactly once."
    override val bpCtxTitle = "What gets read"
    override val bpCtxItems = listOf(
        "Game.log in the channel folder",
        "Every logbackups\\*.log (plus a HOTFIX folder next to it)",
        "Blueprint notifications only — nothing else",
    )
    override val bpCtxLastRun = "Last run"
    override val bpCtxNoRun = "No run in this session yet."
    override val bpSumSuccessTitle = "Export written successfully"
    override val bpSumSuccessDetail: (Int, Int) -> String = { count, files -> "$count blueprint(s) · $files log(s) scanned" }
    override val bpLabelChannelFolder = "Star Citizen channel folder"
    override val bpPlaceholderChannel = "e.g. C:\\Program Files\\Roberts Space Industries\\StarCitizen\\LIVE"
    override val bpLabelOutputJson = "Output JSON (target)"
    override val bpPlaceholderOutput = "e.g. …\\Documents\\blueprints.json"
    override val browse = "Browse…"
    override val bpPickerChannelTitle = "Choose channel folder"
    override val bpPickerChannelConfirm = "Select this folder"
    override val bpPickerSaveTitle = "Choose output JSON file"
    override val bpPickerSaveConfirm = "Save"
    override val bpCta = "Extract blueprints"
    override val bpHintReadsLogs = "Reads the Game.log in this folder and every log in its \"logbackups\" subfolder."
    override val bpHintFolderMissing = "Folder does not exist."
    override val bpHintWrongFolder = "Folder found, but no Game.log/logbackups — possibly the wrong folder."
    override val bpHintValidFolder: (String) -> String = { found -> "Valid channel folder ($found detected)." }
    override val bpHotfixNote = "HOTFIX folder found next to it — its logs are read as well."
    override val bpErrSelectChannel = "Please select a channel folder."
    override val bpErrFolderNotFound: (String) -> String = { path -> "Folder not found: $path" }
    override val bpErrSelectOutput = "Please provide a target path for the JSON."
    override val bpErrOutputIsFolder = "The target path is a folder — please provide a file name."
    override val bpErrOutputParentNotWritable = "The target folder cannot be created or written to."
    override val bpErrOutputFileReadOnly = "The target file is read-only."
    override val bpStatusFixFields = "Please correct the highlighted fields."
    override val bpStatusSearching = "Searching log files…"
    override val bpStatusEvaluating = "Evaluating…"
    override val bpStatusProcessing: (Int, Int, String) -> String = { done, total, label -> "Processing file $done/$total: $label" }
    override val bpStatusNoLogs = "No Game.log and no \"logbackups\" folder found in the channel folder."
    override val bpStatusAllSkipped: (Int) -> String = { count -> "No log file readable — $count file(s) skipped." }
    override val bpSkippedNote: (Int, String) -> String = { count, names -> "$count log(s) skipped (unreadable): $names" }
    override val bpToastNoLogsTitle = "No logs found"
    override val bpToastNoLogsBody = "No Game.log was found in the channel folder."
    override val bpStatusDone: (Int, Int, String) -> String = { count, files, path -> "Done: $count blueprint(s) from $files file(s) written to $path" }
    override val bpToastDoneTitle = "Done"
    override val bpToastDoneBody: (Int) -> String = { count -> "$count blueprint(s) saved." }
    override val bpStatusError: (String) -> String = { msg -> "Error: $msg" }
    override val bpToastErrorTitle = "Error"
    override val unknownError = "Unknown error"
    override val bpShowInFolder = "Show in folder"
    override val bpOpenJson = "Open JSON"
    override val cannotOpen: (String, String) -> String = { name, msg -> "Could not open \"$name\": $msg" }
    override val bpSummaryPlayers = "Players:"
    override val bpSummaryNoPlayer = "(none detected)"
    override val bpSummaryByCategory = "Blueprints by category:"
    override val bpSummaryRecent = "Most recently received blueprints:"

    override val rfSteps = listOf("Preflight", "Images", "Extraction", "Review", "Export")
    override val back = "Back"

    override val rfPreflightTitle = "Preflight & setup"
    override val rfPreflightSubtitle = "Checks Ollama, the AI model and your hardware before the extraction starts."
    override val rfOllamaCardTitle = "Ollama runtime"
    override val rfEndpointLabel = "Endpoint"
    override val rfModelLabel = "Model"
    override val rfChecking = "Checking…"
    override val rfOllamaReady = "Reachable · model present · ready."
    override val rfOllamaModelMissing: (String) -> String = { model -> "Model \"$model\" is not installed." }
    override val rfPullCta = "Pull"
    override val rfPullProgress: (String) -> String = { status -> "Pulling model… $status" }
    override val rfPullFailed: (String) -> String = { msg -> "Model download failed: $msg" }
    override val rfOllamaUnreachable = "Ollama is not reachable."
    override val rfInstallHint1 = "1. Download and install Ollama from ollama.com."
    override val rfInstallHint2 = "2. Start \"ollama serve\" (default port 11434), then re-check."
    override val rfRetry = "Re-check"
    override val rfHardwareCardTitle = "Hardware preflight"
    override val rfGpuRow = "GPU"
    override val rfVramRow = "VRAM"
    override val rfRamRow = "RAM"
    override val rfUnknown = "unknown"
    override val rfAutoModelChip: (String) -> String = { model -> "Auto-selected: $model" }
    override val rfTierCpu = "CPU"
    override val rfTierMin = "MIN 8 GB"
    override val rfTierRecommended = "REC 12 GB+"
    override val rfTierAboveRecommended = "Recommended tier reached — full accuracy on the GPU."
    override val rfTierMinimumInfo = "8 GB tier: compact fallback model (same validated accuracy)."
    override val rfTierBelowMinimum = "Below the minimum tier — choose a fallback:"
    override val rfFallbackLowVram: (String) -> String = { model -> "Low-VRAM model ($model)" }
    override val rfFallbackCpu: (Int) -> String = { eta -> "CPU mode — works, but slow (≈ ${eta}s per image)" }
    override val rfScRunningWarning = "Star Citizen is running — the VLM and the game share GPU/VRAM. Close SC for a safe extraction."
    override val rfScAcknowledge = "Continue anyway"
    override val rfScNotRunning = "Star Citizen is not running — GPU free."
    override val rfThrottleNote = "Throttling on · one image at a time."
    override val rfEtaPerImage: (Int) -> String = { s -> "≈ ${s}s per image" }
    override val rfCtaToImages = "Next: load images"

    override val help = "Help"
    override val helpTitle = "Help — requirements & installation"
    override val helpSubtitle = "What the image recognition needs and how to set up the software."
    override val helpSec1Title = "How the image recognition works"
    override val helpSec1Body = "Your refinery screenshots are read by a local AI vision model (VLM) via Ollama. It runs entirely on your machine — images are never uploaded."
    override val helpSec2Title = "Hardware requirements"
    override val helpSec2Body = "The app detects your GPU automatically and pre-selects the right tier. More VRAM = larger model = higher accuracy and speed."
    override val helpTierRows = listOf(
        listOf("Recommended", "≥ 12 GB VRAM", "qwen3-vl:8b", "≈ 4–5 s / image"),
        listOf("Minimum", "≥ 8 GB VRAM", "qwen3-vl:4b", "≈ 4 s / image"),
        listOf("Below", "CPU mode", "qwen3-vl:4b", "≈ 30 s / image (slow)"),
    )
    override val helpSec3Title = "Install the software (Ollama)"
    override val helpStep1Title = "Download & install Ollama"
    override val helpStep1Body = "Download Ollama from the official site and install it."
    override val helpStep2Title = "Start the Ollama service"
    override val helpStep2Body = "Starts the local service on port 11434 (usually runs automatically after install)."
    override val helpStep3Title = "Pull the vision model"
    override val helpStep3Body = "Downloads the model once (~6 GB). Or just let the app do it — the preflight detects a missing model and pulls it on click with a progress bar."
    override val helpInfoTitle = "No separate Java/Python needed"
    override val helpInfoBody = "The app bundles its own runtime. Only Ollama + the model are external."
    override val helpSec4Title = "Tips for good results"
    override val helpTips = listOf(
        listOf("Close Star Citizen first", "The VLM and the game share GPU/VRAM — with SC running you risk stutter and slow extraction."),
        listOf("Press \"GET QUOTE\", then screenshot", "Before the quote the panel shows no yield, cost or duration — such captures are flagged incomplete."),
        listOf("1 folder = 1 order", "Put all screenshots of one order (including scrolled partials) in one folder — the app stitches them."),
        listOf("Resolution 1080p to 8K", "Ultrawide too; alternatively a manually cropped panel image (detected as \"pre-cropped\")."),
    )
    override val helpMore = "More at ollama.com"
    override val helpGotIt = "Got it"

    override val rfImagesTitle = "Load images"
    override val rfImagesSubtitle = "1 folder = 1 order — put all screenshots of the same order into one folder. Press GET QUOTE in-game before capturing."
    override val rfFolderLabel = "Screenshot folder"
    override val rfPickFolder = "Choose folder"
    override val rfPickerImagesTitle = "Choose screenshot folder"
    override val rfPickerImagesConfirm = "Select this folder"
    override val rfNoImagesInFolder = "No PNG/JPG images found in this folder."
    override val rfStatImages = "Images"
    override val rfStatSelected = "Selected"
    override val rfStatOrder = "Order"
    override val rfStatResolution = "Resolution"
    override val rfStatModel = "Model"
    override val rfSelectAll = "Select all"
    override val rfDeselectAll = "Deselect all"
    override val rfCropTagAuto = "vlm"
    override val rfCropTagPre = "pre-cropped"
    override val rfPasteDropHint = "Ctrl+V pastes an image from the clipboard (e.g. the snipping tool) — images can also be dragged & dropped here."
    override val rfCaptureAberrationTitle = "Turn off chromatic aberration before capturing"
    override val rfCaptureAberrationHint = "Set the slider to 0 in Star Citizen's graphics settings before taking the screenshots — the colour fringing makes digits ambiguous for the model and cannot be removed afterwards."
    override val rfCaptureFramingTitle = "Capture head-on and at the highest resolution"
    override val rfCaptureFramingHint = "Look straight at the screen (not from an angle/side) and capture at your monitor's highest available resolution — warped or low-resolution digits read unreliably. 4K isn't required, but the higher the resolution, the more reliable the read."
    override val rfLowResNote: (Int) -> String = { n -> "$n image(s) are low-resolution (not a full-screen capture) — for reliable digits, re-capture full-screen at the highest resolution." }
    override val rfTempNote = "Images pasted without a selected folder go to a temporary folder that is deleted on exit."
    override val rfCtaStartExtraction = "Start extraction"

    override val rfExtractTitle = "Extraction"
    override val rfImageOf: (Int, Int) -> String = { done, total -> "Image $done / $total" }
    override val rfConsoleTitle = "Console"
    override val rfCancel = "Cancel"
    override val rfCancelled = "Extraction cancelled."
    override val rfUnquotedWarning = "Screenshot captured before GET QUOTE — yields/cost are missing. Press GET QUOTE in-game and capture again."
    override val rfAllUnquotedNotice = "Every image is in the GET-QUOTE state — the export will be flagged as an un-quoted order in the basetool."
    override val rfExtractionFailed: (String) -> String = { msg -> "Extraction failed: $msg" }
    override val rfEtaRemaining: (Int) -> String = { s -> "≈ ${s}s remaining" }
    override val rfCtaToReview = "Next: review"
    override val rfCtaToExport = "Continue to export"

    override val rfReviewTitle = "Review & confirmation"
    override val rfReviewSubtitle = "Review the extracted values and correct them where needed (✎) — nothing is saved until the basetool import."
    override val rfBadgeLayout: (Int) -> String = { pct -> "Layout $pct %" }
    override val rfFlaggedWarnings: (Int) -> String = { n -> "$n finding(s) — please review." }
    override val rfNoFlags = "No findings."
    override val rfHdrLocation = "Location"
    override val rfHdrMethod = "Method"
    override val rfHdrCost = "Total cost"
    override val rfHdrDuration = "Duration"
    override val rfMissingValue = "missing"
    override val rfColMaterial = "Material"
    override val rfColQuality = "Quality"
    override val rfColInput = "Input"
    override val rfColYield = "Yield"
    override val rfColRefine = "Refine"
    override val rfColConfidence = "Confidence"
    override val rfWarningLabel: (String) -> String = { warning ->
        when (warning) {
            "UNQUOTED_ORDER" -> "Un-quoted order (captured before GET QUOTE)"
            "SUM_MISMATCH" -> "Visible quantities exceed the TO REFINE total"
            "IMPLAUSIBLE_CELL" -> "At least one cell is implausible (non-numeric)"
            "REFINE_CORRECTED" -> "Refine toggle corrected from the YIELD column"
            "VERIFY_CORRECTED" -> "Cross-check: quantity corrected via the TO REFINE checksum"
            "VERIFY_MISMATCH" -> "Cross-check: models disagree — review the flagged rows"
            "CTA_MISMATCH" -> "Button label contradicts the quote state — check the header"
            "YIELD_RATIO_OUTLIER" -> "Yield/quantity doesn't fit this material's other rows — check the digits"
            "STITCH_CONTESTED" -> "Row read differently across screenshots — check the value"
            "CHECKSUM_REPAIRED" -> "A QTY digit was auto-corrected from the TO REFINE total — please verify"
            "YIELD_REPAIRED" -> "A yield digit was auto-corrected from the material yield rate — please verify"
            "YIELD_OCR_REPAIRED" -> "A yield digit was corrected via the OCR cross-read (confirmed by the material rate) — please verify"
            "OCR_CORRECTED" -> "Quality corrected by 8b/4b/OCR majority — please verify"
            "OCR_CONTESTED" -> "Quality: models/OCR disagree with no majority — review the flagged row"
            "QTY_OCR_CONTESTED" -> "A refine-OFF row's quantity: OCR reads it differently than the model — review the flagged row"
            "TO_REFINE_CONTESTED" -> "TO REFINE total (the checksum anchor) is disputed by OCR/the verify model — check the header"
            else -> warning
        }
    }
    override val rfManualNote = "Stays manual — complete in the basetool: owner · mission · other costs · ore sales · start."
    override val rfRecaptureHint = "Some values uncertain? Use Back to the images, re-capture the order closer/larger and head-on (new screenshots are ingested automatically) and extract again."
    override val rfEditRow = "Edit row"
    override val rfEditApply = "Apply"
    override val rfEditCancel = "Discard"
    override val rfEditRevert = "Restore the read value"
    override val rfEditedTag = "manual"
    override val rfUnsavedTitle = "Unsaved correction"
    override val rfUnsavedBody = "A row or field is still in edit mode and hasn't been confirmed with “Apply” (✓). " +
        "If you continue now, that correction will be lost."
    override val rfUnsavedDiscard = "Discard & continue"
    override val rfUnsavedBack = "Back to editing"
    override val rfWarningResolved = "resolved by correction"
    override val rfAllWarningsResolved = "All findings resolved by corrections."
    override val rfCtaExport = "Export as JSON"
    override val rfPickerExportTitle = "Save RefineryExtract JSON"
    override val rfPickerExportConfirm = "Save"
    override val rfExportFailed: (String) -> String = { msg -> "Export failed: $msg" }

    override val rfExportTitle = "Export & upload"
    override val rfExportSuccess: (String) -> String = { path -> "RefineryExtract JSON written: $path" }
    override val rfSendBlockedMissingQty: (Int) -> String = { n ->
        "Cannot send: $n row(s) have no quantity read. Correct the affected rows in the review step (✎) " +
            "or re-capture the screenshots at a higher resolution."
    }
    override val rfUploadCardTitle = "Upload into the basetool"
    override val rfUploadSteps = listOf(
        "1. Open the basetool → Refinery → Orders.",
        "2. Choose \"Import order\".",
        "3. Pick the exported JSON file.",
        "4. Review the pre-filled form, complete it and save.",
    )
    override val rfProvenanceTitle = "Provenance"
    override val rfProvTool = "Tool"
    override val rfProvVersion = "Version"
    override val rfProvModel = "Model"
    override val rfProvSchema = "Schema"
    override val rfProvPanel = "Panel"
    override val rfProvGenerated = "Generated"
    override val rfNewExtraction = "New extraction"

    override val pickerComputer = "Computer"
    override val pickerParentFolder = "Parent folder"
    override val pickerFilter = "Filter…"
    override val pickerNewFolder = "New folder"
    override val pickerCreate = "Create"
    override val pickerQuickAccess = "Quick access"
    override val pickerDrives = "Drives"
    override val pickerHome = "Home"
    override val pickerDocuments = "Documents"
    override val pickerDesktop = "Desktop"
    override val pickerDownloads = "Downloads"
    override val pickerColName = "Name"
    override val pickerColSize = "Size"
    override val pickerColModified = "Modified"
    override val pickerFolderType = "Folder"
    override val pickerEmpty = "This folder is empty."
    override val pickerNoMatch = "No match."
    override val pickerDenied = "No access to this folder."
    override val pickerLoading = "Loading…"
    override val pickerShowing: (String) -> String = { pattern -> "Showing: $pattern" }
    override val pickerSelected = "Selected"
    override val pickerFileName = "File name"
    override val pickerOverwrite = "File already exists — will be overwritten."
    override val pickerInvalidName = "Invalid file name."
    override val pickerCreateFailed = "Could not create the folder."
    override val pickerPathPlaceholder = "Type or paste a path…"
    override val pickerPathNotFound = "Path does not exist or is not reachable."
    override val pickerClearPath = "Clear path"
    override val send =
        SendStrings(
            button = "Send to basetool",
            consentTitle = "Send to basetool",
            consentBody =
                "The generated JSON file is sent over an encrypted connection to your own basetool " +
                    "account (it includes your player handle and the quoted amounts/balances). The " +
                    "basetool page then opens pre-filled — nothing is saved until you review it " +
                    "there. Images never leave your machine.",
            consentConfirm = "Send",
            authTitle = "Confirm in the browser",
            authBody =
                "We opened your browser. Sign in (if needed) and confirm the code shown below to " +
                    "authorize the send.",
            authCode = { code -> "Code: $code" },
            authOpenBrowser = "Open browser again",
            waiting = "Waiting for approval…",
            inProgress = "Sending to basetool…",
            resultTitle = "Sent",
            resultBody = "Your data is waiting in basetool. Open the page to review and save it.",
            openInBasetool = "Open in basetool",
            saveLocally = "Save as JSON instead",
            error = { msg -> "Send failed: $msg" },
        )
    override val account =
        AccountStrings(
            connected = "Connected to basetool",
            disconnected = "Not connected to basetool",
            disconnect = "Disconnect from basetool",
            disconnectTitle = "Disconnect from basetool",
            disconnectBody =
                "The saved login is revoked and removed from this machine. The next send will ask " +
                    "for approval again.",
            disconnectConfirm = "Disconnect",
        )
}

/** Resolve the catalogue for a language. */
fun stringsFor(lang: Lang): Strings = if (lang == Lang.DE) StringsDe else StringsEn

/**
 * Composition-local carrying the active string catalogue. Provided once at the window root by
 * `guiMain` (driven by the title-bar DE/EN toggle); every composable reads `LocalStrings.current`
 * instead of hardcoding text.
 */
val LocalStrings = staticCompositionLocalOf<Strings> { StringsDe }
