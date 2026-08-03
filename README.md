# Basetool SC Extractor

Eine **Kotlin-Desktop-App** (Compose for Desktop), die Star-Citizen-Daten **lokal**
ausliest und als JSON für das Basetool exportiert. Das Auslesen bleibt vollständig
lokal und **automatisch wird nichts hochgeladen** — der einzige Versand ist der
optionale Knopf **„An Basetool senden"**, den du selbst auslöst: er schickt die
erzeugte Export-JSON über eine verschlüsselte Verbindung an dein eigenes
Basetool-Konto (Screenshots verlassen deinen Rechner nie).
Zwei Workflows unter einem Dach (Top-Tabs: Start · Blueprints · Refinery, Sprache
über den DE/EN-Schalter in der Titelleiste):

- **Blueprints** — liest aus den `Game.log`-Dateien aus, **welche Blueprints ein
  Spieler erhalten hat** (inspiriert vom „SCMDB Log Watcher", bewusst auf
  Blueprints fokussiert — Missionsdaten werden nicht ausgewertet).
- **Refinery** — liest **Raffinerie-Auftragsdaten aus SETUP-Screenshots** per
  lokalem KI-Modell (Ollama-VLM) aus und erzeugt eine `RefineryExtract`-JSON, die
  das Basetool beim Anlegen eines Raffinerie-Auftrags vorausfüllt (Epic
  krt-profit/basetool#439).

<table>
<tr>
<td width="120" align="center">
<img width="88" alt="Made by the Community" src="docs/img/MadeByTheCommunity_White.png#gh-light-mode-only"><img width="88" alt="Made by the Community" src="docs/img/MadeByTheCommunity_Black.png#gh-dark-mode-only">
</td>
<td>
<b>Inoffizielles Star-Citizen-Fan-Tool</b> — nicht mit der Cloud-Imperium-Unternehmensgruppe affiliiert.<br>
Star Citizen®, Roberts Space Industries® and Cloud Imperium® are registered trademarks of Cloud Imperium Rights LLC
</td>
</tr>
</table>

Die App bringt eine **Installationsroutine** (MSI) mit und lässt sich wie jedes
normale Windows-Programm wieder **deinstallieren** (Einträge in „Apps & Features",
Startmenü, Desktop-Verknüpfung).

> **Die Ingest-Schnittstelle des Basetools darf ausschließlich von Client-Software
> genutzt werden, die von [@greluc](https://github.com/greluc) freigegeben ist.**
> Das Gateway prüft die Client-Kennung, den Scope und das `tool`-Feld der Nutzdaten
> gegen serverseitige Freigabelisten und weist alles andere mit
> `403 CLIENT_NOT_ALLOWED` ab (`REQ-INGEST-011` in `docs/specs/desktop-ingest.md`
> des Basetool-Repos). Wer *das Basetool nutzen* darf, ändert das nicht — jedes
> Mitglied kann Blueprints und Raffinerie-Aufträge hochladen, aber eben mit dem
> freigegebenen Extractor. Nutze deshalb eine offizielle Version von der
> [Releases-Seite](https://github.com/krt-profit/basetool-sc-extractor/releases);
> ein selbst gebauter Fork mit geänderter Kennung wird abgewiesen. Konkret sind
> `DeviceGrantClient.CLIENT_ID` und `RefineryPipeline.TOOL` vertragliche
> Konstanten — eine Änderung ist eine abgestimmte Umstellung auf beiden Seiten.

---

## Für Endnutzer

### Installieren

1. `Basetool SC Extractor-<version>.msi` doppelklicken.
2. Dem Installations-Assistenten folgen (es lässt sich ein Installationsordner
   wählen). Es werden **keine Administratorrechte** benötigt — die Installation
   erfolgt pro Benutzer.
3. Danach gibt es einen Startmenü-Eintrag unter **Basetool** und eine
   Desktop-Verknüpfung.

> Ein separates Java/JRE muss **nicht** installiert werden — die Laufzeit ist im
> Installer enthalten.

### Updates

Beim Start prüft die App still auf
[GitHub-Releases](https://github.com/krt-profit/basetool-sc-extractor/releases). Gibt
es eine neuere Version, erscheint auf der Start-Seite ein Banner mit
**Herunterladen & installieren**:

1. Der MSI-Installer wird in einen temporären Ordner heruntergeladen (nie in den
   Installationsordner) und per Größe + SHA-256-Checksumme verifiziert.
2. Der Installer startet, die App beendet sich, damit das Update den
   Programmordner ersetzen kann.
3. **Nach der Installation wird die Update-Datei automatisch wieder gelöscht** —
   auch bei einem abgebrochenen Setup bleibt nichts zurück.

**Später** blendet das Angebot für die laufende Sitzung aus. Abgerufen werden
ausschließlich die Release-Metadaten von GitHub; es werden keine Nutzungsdaten
gesendet. Ohne Internetverbindung passiert nichts — die Prüfung schlägt still
fehl und die App startet normal.

### Benutzen — Blueprints

1. App starten und auf der Start-Seite **Blueprints** öffnen.
2. **Star-Citizen-Channel-Ordner** wählen — z. B.
   `C:\Program Files\Roberts Space Industries\StarCitizen\LIVE`. Ausgelesen werden
   die `Game.log` in diesem Ordner **und** alle Logs im Unterordner `logbackups`
   (`Game Build(...).log`). Vorausgefüllt ist der Ordner des letzten erfolgreichen
   Laufs, sonst der Standard-LIVE-Pfad, sofern er auf deinem Rechner existiert.
   Wählst du einen **Archivordner** — einen Ordner, in dem lose `*.log`-Dateien
   liegen, ohne `Game.log` und ohne `logbackups` —, werden diese gelesen. Das ist
   für weggesicherte Logs gedacht; Star Citizen selbst räumt seine `logbackups`
   irgendwann auf.
3. **Ausgabe-JSON (Ziel)** wählen — wohin die JSON-Datei geschrieben werden soll.
4. Auf **Blueprints extrahieren** klicken.

Nach dem Lauf zeigt die App eine Zusammenfassung (erkannte Spieler, Blueprints
nach Kategorie, die zuletzt erhaltenen Blueprints) und schreibt die vollständige
Liste in die gewählte JSON-Datei.

### Benutzen — Refinery (Screenshot-Extraktion)

Der Refinery-Workflow liest die **SETUP-Ansicht** eines Raffinerie-Auftrags
(REFINEMENT CENTER) aus Screenshots aus — Materialien, Qualität, Menge, Ausbeute,
Refine-Schalter, Standort, Methode, Kosten und Dauer — und exportiert eine
`RefineryExtract.json`, die im Basetool unter *Refinery → Aufträge → Auftrag
importieren* das Anlege-Formular vorausfüllt. Zusätzlich wird pro Screenshot die
**Aufnahmezeit** mit exportiert (aus dem Zeitstempel im Dateinamen, z. B.
`Screenshot 2026-06-01 213823.png` oder `ScreenShot-2026-06-06_15-50-53-C28.jpg`,
sonst aus dem Datei-Änderungsdatum) — das Basetool übernimmt die Aufnahmezeit des
letzten Screenshots als **Startzeit** des Auftrags.

**Voraussetzung: Ollama (lokales KI-Modell).** Die Bilder werden zu **keinem**
Zeitpunkt hochgeladen — die Auswertung läuft komplett lokal über
[Ollama](https://ollama.com):

1. Ollama von ollama.com installieren und starten (`ollama serve`,
   Standard-Port 11434).
2. Das Modell laden: `ollama pull qwen3-vl:8b-instruct` — oder einfach die
   App machen lassen: Die **Vorprüfung** erkennt ein fehlendes Modell und lädt es
   auf Klick mit Fortschrittsanzeige herunter.

**Hardware-Stufen** (von der App automatisch erkannt und vorgewählt):

| Stufe | GPU-VRAM | Modell | Dauer pro Bild (gemessen) |
|---|---|---|---|
| Empfohlen | ≥ 12 GB | `qwen3-vl:8b-instruct` | ≈ 4–5 s |
| Minimum | ≥ 8 GB | `qwen3-vl:4b-instruct` | ≈ 4 s |
| Darunter | — | `qwen3-vl:4b-instruct`, CPU-Modus | ≈ 30 s (funktioniert, langsam) |

**Wichtig für gute Ergebnisse:**

- **Star Citizen vorher schließen.** Das KI-Modell und das Spiel teilen sich
  GPU und VRAM — bei laufendem SC drohen Ruckler bis hin zum Absturz und sehr
  langsame Extraktion. Die Vorprüfung erkennt ein laufendes
  `StarCitizen.exe` und warnt (fortfahren ist möglich, aber bewusst zu
  bestätigen).
- **Erst im Spiel „GET QUOTE" drücken, dann den Screenshot aufnehmen.** Vor der
  Quote zeigt das Panel keine Ausbeute, Kosten und Dauer (`--`) — solche
  Aufnahmen werden erkannt und als unvollständig markiert.
- **1 Ordner = 1 Auftrag.** Alle Screenshots desselben Auftrags (auch gescrollte
  Teilansichten der Materialliste) in einen Ordner legen; die App fügt die
  Zeilen automatisch zusammen. Auflösung 1080p bis 8K (auch Ultrawide) wird
  unterstützt; alternativ kann ein bereits **manuell zugeschnittenes**
  Panel-Bild verwendet werden (wird als „vorgecroppt" erkannt).
- **Bilder einfügen ohne Ordner:** Im Schritt *Bilder laden* lassen sich
  Screenshots auch direkt per **Strg+V** aus der Zwischenablage einfügen
  (z. B. aus dem Windows Snipping Tool) oder per **Drag & Drop** in das
  Fenster ziehen. Ist ein Screenshot-Ordner gewählt, werden sie dort
  gespeichert; ohne gewählten Ordner landen sie in einem temporären Ordner,
  der beim Beenden der App automatisch gelöscht wird.
- **Bilder gezielt abwählen:** Jede Kachel hat eine Checkbox (auch ein Klick
  auf das Vorschaubild schaltet um) — nur angehakte Bilder gehen in die
  Extraktion. Abgewählte Bilder bleiben im Raster sichtbar, werden aber
  ausgegraut dargestellt und übersprungen.
- **Ordner wird laufend überwacht:** Solange der Schritt *Bilder laden* offen
  ist, prüft die App den gewählten Ordner einmal pro Sekunde auf Änderungen —
  nachträglich abgelegte Screenshots erscheinen automatisch im Raster, aus dem
  Ordner gelöschte verschwinden. Gesetzte bzw. entfernte Haken bleiben dabei
  erhalten, und per ✕ entfernte Bilder werden nicht erneut hinzugefügt.
- Stehen mehrere Auftrags-Panels nebeneinander, wird das **linkeste** (= der
  neueste Auftrag) ausgelesen.

Die Extraktion verarbeitet **ein Bild nach dem anderen** (Drosselung), zeigt
pro Bild die Stufen *Locate → Normalize → Read* und endet in einem
Review-Schritt: Alle gelesenen Werte mit abgeleiteter Konfidenz prüfen, dann
**Als JSON exportieren**. Gespeichert wird erst beim Import im Basetool.

### Deinstallieren

Wie jedes Windows-Programm:
**Einstellungen → Apps → Installierte Apps → „Basetool SC Extractor" →
Deinstallieren** (oder klassisch über *Systemsteuerung → Programme und Features*).

**Restlose Entfernung — verifiziert.** Ein Install→Deinstall-Testzyklus bestätigt,
dass die Deinstallation **alles** entfernt:

| Artefakt | nach Deinstallation |
|---|---|
| Programmordner `%LOCALAPPDATA%\Basetool SC Extractor\` (gebündelte JRE, ~515 Dateien) | entfernt |
| Startmenü-Gruppe „Basetool" inkl. Verknüpfung | entfernt |
| Desktop-Verknüpfung | entfernt |
| „Apps & Features"-/Registry-Eintrag | entfernt |

Das funktioniert **restlos**, weil die App bewusst **nichts** in ihren eigenen
Installationsordner schreibt (keine `config.json`, keine `logs/` — anders als das
Python-Vorbild). Solche zur Laufzeit erzeugten Fremddateien sind der übliche Grund,
warum sonst ein leerer Programmordner zurückbleibt.

> Deine **exportierten JSON-Dateien** liegen am selbst gewählten Ort (Standard:
> `Dokumente\blueprints.json`) und werden bei der Deinstallation **absichtlich nicht**
> gelöscht — das sind deine Daten, kein Programm-Rest.

**„An Basetool senden" und die gemerkte Anmeldung.** Sobald du **„An Basetool senden"**
nutzt, legt die App zwei nutzerbezogene Dinge **außerhalb** des Programmordners ab — der
Programmordner bleibt also weiterhin restlos entfernbar:

- eine **`config.json`** unter `%APPDATA%\Basetool SC Extractor\` (kein Geheimnis: nur die
  Einwilligung zum Senden und die Ziel-URL). Roaming-Daten, kein Programm-Rest.
- ein **Auffrischungs-Token** (Refresh Token) im **Windows-Anmeldeinformations-Manager**
  (DPAPI-geschützt, pro Benutzer) — damit du beim nächsten Senden nicht erneut bestätigen
  musst. Im selben Eintrag liegt ein **privater Schlüssel** (EC P-256), an den das Token
  gebunden ist (DPoP, RFC 9449): Eine Kopie des Tokens allein ist damit **wertlos**, weil
  jede Anfrage zusätzlich mit diesem Schlüssel signiert werden muss — genau deshalb stehen
  beide zusammen in einem Datensatz. Dieser Eintrag wird bei der **Deinstallation nicht**
  entfernt; lösche ihn über **Start → „Vom Basetool trennen"** (das zieht das Token
  serverseitig zurück und löscht beides lokal) oder im
  Windows-Anmeldeinformations-Manager unter dem Eintrag „Basetool SC Extractor".

---

## Die JSON-Ausgabe

```jsonc
{
  "schemaVersion": 1,
  "tool": "Basetool Blueprint Extractor",
  "toolVersion": "1.0.0",
  "generatedAt": "2026-05-30T21:39:45Z",   // UTC, wann der Export erzeugt wurde
  "sourceFolder": "…\\StarCitizen\\LIVE",   // der Channel-Ordner
  "logFilesScanned": 424,                    // Game.log + logbackups\*.log
  "blueprintCount": 179,                     // Gesamtzahl erhaltener Blueprints
  "players": [
    {
      "handle": "greluc",                    // Spielername (aus Login-Zeilen)
      "blueprintCount": 179
    }
  ],
  "blueprints": [
    {
      "productName": "Yubarev \"Mirage\" Pistol", // exakter Item-Name (inkl. Anführungszeichen)
      "category": "Weapon",                       // abgeleitete Kategorie (s. u.)
      "receivedAt": "2026-03-26T16:49:31.050Z",   // Zeitpunkt des Erhalts (UTC)
      "player": "greluc",                         // Empfänger (aus der Quelldatei)
      "notificationId": 19,                       // In-Game-Notification-Index
      "queueSize": 2,                             // gemeldete Notification-Queue-Größe
      "gameBuild": "11518367",                    // SC-Build-Nr. (aus dem Dateinamen)
      "sourceFile": "Game Build(11518367) 26 Mar 26 (17 24 58).log"
    }
    // … chronologisch sortiert …
  ]
}
```

**Kategorien** (`category`) werden heuristisch aus dem Namen abgeleitet — der
Log selbst nennt keine Kategorie:
`MiningTool` · `Ammo` (Magazine/Batterien) · `Armor` (Helmet/Core/Arms/Legs/…) ·
`Weapon` (Pistol/Rifle/Shotgun/…) · `Other`.

---

## Wie das Auslesen funktioniert

Star Citizen schreibt beim Erhalt eines Blueprints eine Notification-Zeile:

```
<2026-03-26T16:49:31.050Z> [Notice] <SHUDEvent_OnNotification> Added notification
  "Received Blueprint: Yubarev "Mirage" Pistol: " [19] to queue. New queue size: 2,
  MissionId: [00000000-0000-0000-0000-000000000000], ObjectiveId: [] [...]
```

Der Parser ([`BlueprintParser.kt`](src/main/kotlin/com/basetool/bpextractor/BlueprintParser.kt))
behandelt die realen Eigenheiten dieser Zeilen:

- **Anker auf `Added notification`** — jede Blueprint-Meldung erscheint mehrfach
  im Log (die ursprüngliche Notification, eine Queue-Echo-Zeile und mehrere
  `UpdateNotificationItem`-Folgezeilen mit `Next`/`StartFade`/`Remove`). Nur die
  `Added notification`-Zeile wird gezählt, damit jeder Blueprint **genau einmal**
  gezählt wird (sonst ~6-fach).
- **Spielsprache** — das Etikett vor dem Namen (`Received Blueprint`) kommt aus den
  Lokalisierungstabellen des Spiels und ist übersetzt, wenn du einen
  Sprachpatch nutzt (`g_language` in der `user.cfg`); die Zeile drumherum bleibt
  englisch. Erkannt werden Englisch und Deutsch (`Bauplan erhalten`), Groß- und
  Kleinschreibung egal. Weitere Sprachen kommen dazu, sobald uns ein echtes Log
  in dieser Sprache vorliegt — geraten wird nicht.
- **Namen mit Anführungszeichen** (`Yubarev "Mirage" Pistol`), **Klammern**
  (`Yubarev Pistol Battery (10 cap)`), **Slashes** (`Sth/2/C Cirrus`) und
  **Bindestrichen** (`ADP-mk4 Core Woodland`) werden korrekt erfasst. Der Name
  endet stabil am `: " [<id>]`-Trenner.
- **Spielername** kommt aus den Login-Zeilen derselben Datei
  (`User Login Success - Handle[…]` bzw. die Charakter-Status-Zeile mit `geid`
  und `accountId`) — die `MissionId` auf der Blueprint-Zeile ist immer `0000…`
  und daher nutzlos.
- **Build-Nummer** stammt aus dem Dateinamen (`Game Build(11518367) …`).

Große Logs (teils > 30 MB) werden **zeilenweise gestreamt**, nie komplett in den
Speicher geladen.

---

## Selbst bauen (für Entwickler)

**Voraussetzung:** JDK 25 (z. B. Azul Zulu). Gradle wird über den mitgelieferten
Wrapper bereitgestellt.

```powershell
# Tests ausführen
.\gradlew.bat test

# App starten
.\gradlew.bat run

# Windows-Installer (MSI) bauen — IMMER über dieses Skript (s. WiX-Hinweis unten)
.\package-msi.ps1
```

Die fertige MSI liegt danach unter `dist\Basetool SC Extractor-<version>.msi`
(das Skript kopiert sie dorthin; das Gradle-Original liegt unter
`build\compose\binaries\main\msi\`).

### Hinweis zu WiX (MSI-Erzeugung)

`packageMsi` nutzt `jpackage`, das seit JDK 24 mit modernem WiX (**4+**) arbeitet
([JDK-8319457](https://bugs.openjdk.org/browse/JDK-8319457)): Es ruft das erste
`wix.exe` im `PATH` auf und braucht die Extensions `WixToolset.Util.wixext` und
`WixToolset.UI.wixext` im globalen Extension-Cache. Dabei gibt es zwei
Stolperfallen:

- **Gemischte WiX-Versionen:** jpackage übergibt die Extensions *unversioniert*,
  und `wix.exe` löst sie zur *höchsten* Version im Cache auf. Liegen dort
  Extensions eines neueren Majors (z. B. v7 neben einem v6-Toolset), bricht ein
  älteres `wix.exe` mit **Fehler WIX0144 / Exit-Code 144** ab — lange fälschlich
  für einen jpackage-Bug gehalten
  ([JDK-8356592](https://bugs.openjdk.org/browse/JDK-8356592)).
- **OSMF-EULA:** WiX **v7+** verweigert jeden Befehl, bis einmalig pro
  Benutzerkonto `wix eula accept wix7` ausgeführt wurde (Open Source Maintenance
  Fee; zahlungspflichtig erst ab ca. 10.000 US$ Jahresumsatz — Details:
  <https://docs.firegiant.com/wix/osmf/>).

Deshalb die MSI **immer** über das mitgelieferte Skript bauen:

```powershell
.\package-msi.ps1
```

Der Build ist auf **WiX 7** gepinnt: Das Skript nimmt das neueste installierte
WiX 7.x und stellt es nur für diesen Build-Prozess an den Anfang des `PATH`,
prüft EULA und Extensions vorab mit klaren Fehlermeldungen (die EULA wird nur auf
CI automatisch akzeptiert) und bootstrappt auf Maschinen ohne WiX 7 ein lokales
Exemplar unter `tools\wix` (dotnet tool, Version 7.0.0). Am System wird **nichts**
geändert; die fertige MSI landet in `dist\`.

### Installer-Verhalten anpassen

In [`build.gradle.kts`](build.gradle.kts) unter `windows { … }`:

| Option | Wirkung |
|---|---|
| `perUserInstall = true` | Installation ohne Admin, pro Benutzer in „Apps & Features" |
| `dirChooser = true` | Schritt zur Auswahl des Installationsordners |
| `menu = true` / `menuGroup` | Startmenü-Eintrag |
| `shortcut = true` | Desktop-Verknüpfung |
| `upgradeUuid` | stabile ID, damit neue Versionen die alte ersetzen |
| `iconFile` | eigenes Icon (`src/main/resources/app.ico` ablegen) |

> Die MSI ist ~60 MB. Gebündelt wird eine **schlanke** JDK-25-Laufzeit — nur die
> wirklich benötigten Module: `modules("java.instrument", "jdk.unsupported",
> "java.net.http", "jdk.management")` (HTTP-Client für Ollama, Speicher-Probe für
> die Hardware-Vorprüfung) plus die vom Compose-Plugin automatisch erkannten
> (`java.desktop` etc.), ermittelt via `gradlew suggestRuntimeModules`. Der Nutzer
> braucht trotzdem kein eigenes Java.
> (`jvmArgs += "--enable-native-access=ALL-UNNAMED"` unterdrückt die JDK-25-
> „native access"-Warnungen, die Skikos `System.load()` sonst auf stderr schreibt.)

---

## Projektstruktur

```
basetool-bp-extractor/
├── build.gradle.kts                  # Build + Compose-/MSI-Konfiguration
├── settings.gradle.kts
├── gradle.properties
├── gradlew(.bat)                     # Gradle-Wrapper (9.5.1)
├── package-msi.ps1                   # MSI-Build (WiX-Auswahl, EULA-/Extension-Preflight)
├── src/main/kotlin/com/basetool/bpextractor/
│   ├── Main.kt                       # Compose-GUI (Tabs/Shell), Einstiegspunkt
│   ├── BlueprintParser.kt            # Blueprint-Zeilen-Parsing (Kern)
│   ├── BlueprintExtractor.kt         # Ordner-Scan, Aggregation, JSON
│   ├── Legal.kt                      # Fankit-Pflichttexte (Markenhinweis, verbatim)
│   ├── refinery/                     # Refinery-Pipeline (pur, ohne UI)
│   │   ├── Locate.kt                 #   Panel-Detektion + Normalisierung (CV)
│   │   ├── PanelReader.kt / PanelRead.kt  # VLM-Read + Markdown-Reformat
│   │   ├── Stitcher.kt / Validation.kt    # Zeilen-Stitching + Konfidenz-Politik
│   │   ├── RefineryPipeline.kt       #   Orchestrierung + JSON-Export
│   │   ├── Preflight.kt              #   Hardware-Probes + Stufen-Entscheidung
│   │   ├── OllamaClient.kt           #   Ollama-HTTP-API (tags/ps/chat/pull)
│   │   └── model/RefineryExtract.kt  #   eingefrorener JSON-Contract (v1)
│   ├── ui/Theme.kt                   # KRT-Theme (Farben, Fonts, Typo, Shapes)
│   ├── ui/KrtComponents.kt           # HUD-Box, CTA-/Ghost-Buttons, Checkbox …
│   ├── ui/Navigation.kt              # CommandStrip (Tabs + Inline-Stepper), DE/EN-Toggle
│   ├── ui/StepScaffold.kt            # Schritt-Gerüst: Kopf · Scroll-Body · Footer-CTA
│   ├── ui/StartScreen.kt             # Launcher (Workflow-Karten)
│   ├── ui/RefineryScreen.kt          # Refinery-Workflow-Host (5 Schritte)
│   ├── ui/refinery/                  # die fünf Refinery-Screens, UI-State (Bild-Auswahl),
│   │                                 #   ImageIntake (Strg+V-/Drag&Drop-Aufnahme)
│   ├── ui/FilePicker.kt              # KRT-Datei-/Ordner-Picker (keine nativen Dialoge)
│   ├── ui/i18n/Strings.kt            # DE/EN-Stringkatalog
│   ├── ui/WindowChrome.kt            # undekorierte Titelleiste + Fenster-Buttons
│   └── model/Models.kt               # Blueprint-JSON-Datenmodelle
├── src/main/resources/               # Fonts (Lato), app.ico, Prompt v1, OCR-Modelle (ocr/)
├── src/main/composeResources/drawable/ # honeycomb_bg.svg, krt_icon.png, Made-by-the-Community-Logo
├── src/test/kotlin/…                 # Unit-Tests
├── src/test/resources/sample.log     # Test-Fixture (Edge-Cases)
├── docs/refinery-extractor/          # Phase-0-Findings (Modell-Bake-off etc.)
├── docs/img/                         # README-Bilder (Made-by-the-Community-Logo)
└── game-log/                         # private Beispiel-Logs (nicht im Repo)
```

## Design

Die GUI folgt dem **„Das Kartell" / KRT-Design-System** (Quelle: Claude-Skill
`das-kartell-design`): dunkles Sci-Fi-„HUD", Hausorange `#E77E23` auf Schwarz,
**Lato-only-Typografie** (Headlines: Lato **Bold** UPPERCASE mit 0.05em-Tracking,
Body: Lato Light — keine Audiowide/Ethnocentric mehr), durchweg scharfe
Ecken mit diagonalen HUD-Eckwinkeln, Orange-Bloom statt weicher Schatten. Umgesetzt
als Compose-Material3-Theme (`ui/Theme.kt`, `ui/KrtComponents.kt`) mit strenger
Action-Hierarchie: genau **eine** gefüllte Orange-CTA pro Kontext („Blueprints
extrahieren"), Sekundäraktionen als Ghost-Buttons, Labels neutral-grau.

Das **KRT-Logo** dient als App-/Fenster-Icon (`app.ico` für Installer/Exe,
`composeResources/drawable/krt_icon.png` fürs Fenster), und ein **dezenter Honeycomb-Hintergrund**
(`honeycomb_bg.svg` — orange Hexagone bei 10 % Deckkraft) liegt als Textur hinter
dem Inhalt. Logo + Honeycomb (und **Lato**) stammen aus dem Skill
`das-kartell-design/assets/`. Auf eine separate Display-Schrift wird bewusst
verzichtet: Das Design-System ist **Lato-only** — die markenseitig vorgesehene,
**kommerzielle** Ethnocentric wird nicht gebündelt, und Headlines tragen ihren
Charakter über Lato Bold + UPPERCASE + Tracking statt über eine eigene Schrift.

Das Fenster ist **undekoriert** (kein weißer OS-Rahmen): eine eigene dunkle
Titelleiste (`ui/WindowChrome.kt`) trägt Logo + Titel und eigene Minimieren-/
Maximieren-/Schließen-Buttons, mit scharfen Ecken, oranger Akzentlinie, dünnem
HUD-Rahmen und einer Resize-Ecke unten rechts.

## Lizenz

Dieses Programm ist **freie Software** unter der **GNU General Public License,
Version 3 oder (nach Wahl) später** (GPL-3.0-or-later). Den vollständigen Lizenztext
findest du in [`LICENSE`](LICENSE).

```
Copyright (C) 2026 Basetool

Dieses Programm ist freie Software: Du kannst es weitergeben und/oder verändern,
solange du dich an die Bedingungen der GNU General Public License hältst. Es wird
in der Hoffnung verteilt, dass es nützlich ist, jedoch OHNE JEDE GEWÄHRLEISTUNG.
```

### Drittanbieter-Komponenten

| Komponente | Lizenz |
|---|---|
| Kotlin, Compose Multiplatform, Material 3, Skiko, kotlinx-serialization/-coroutines | Apache-2.0 |
| Skia (via Skiko) | BSD-3-Clause |
| Gebündelte Java-Laufzeit (OpenJDK 25) | GPLv2 **mit Classpath Exception** |
| Schrift **Lato** | SIL Open Font License 1.1 |

Der OFL-Lizenztext der Schrift liegt unter
[`src/main/resources/fonts/`](src/main/resources/fonts/) (`Lato-OFL.txt`).
Die **Classpath Exception** der gebündelten JRE erlaubt die Weitergabe
zusammen mit diesem (GPL-)Programm, ohne dass die JRE selbst dadurch ihre Lizenz ändert;
ihre Notices liegen im Installationspaket unter `runtime/legal/`.

### Marken

Das **KRT-/„Das Kartell"-Logo** und zugehörige Markenzeichen sind Eigentum ihrer
Inhaber. Die GPL deckt den **Quellcode** dieses Programms ab, **nicht** die Marken-
und Logo-Assets — diese werden nicht zur freien Weiterverwendung lizenziert.

### Star Citizen

Dies ist ein **inoffizielles Star-Citizen-Fan-Tool** und steht in keiner Verbindung zur
Cloud-Imperium-Unternehmensgruppe. Es verwendet das **„Made by the Community"-Logo** und
den Marken-Hinweis gemäß dem offiziellen **Star Citizen Fankit** (Fankit Agreement, Fan
Style Guide und RSI Terms of Service). Es werden **keine** Star-Citizen-Spieldaten oder
-Assets mitgeliefert; die ausgelesenen `Game.log`-Dateien und die exportierten JSON-Daten
gehören dir.

> Star Citizen®, Roberts Space Industries® and Cloud Imperium® are registered trademarks of Cloud Imperium Rights LLC

Fankit-Assets wie das „Made by the Community"-Logo sind Eigentum von Cloud Imperium Rights
LLC / Cloud Imperium Rights Ltd. und werden unter den Fankit-Bedingungen verwendet — die
GPL dieses Projekts erstreckt sich **nicht** auf diese Marken und Assets.
