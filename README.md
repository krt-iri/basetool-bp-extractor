# Basetool SC Extractor

A **Kotlin desktop app** (Compose for Desktop) that reads Star Citizen data
**locally** and exports it as JSON for the Basetool. Reading stays entirely local
and **nothing is uploaded automatically** — the only thing ever sent is the
optional **"Send to Basetool"** button that you press yourself: it transmits the
generated export JSON over an encrypted connection to your own Basetool account
(screenshots never leave your machine).
Two workflows under one roof (top tabs: Start · Blueprints · Refinery, language via
the DE/EN switch in the title bar):

- **Blueprints** — reads the `Game.log` files to extract **which blueprints a
  player has received** (inspired by the "SCMDB Log Watcher", deliberately focused
  on blueprints — mission data is not evaluated).
- **Refinery** — reads **refinery work-order data from SETUP screenshots** via a
  local AI model (Ollama VLM) and produces a `RefineryExtract` JSON that
  pre-fills the Basetool's form when you create a refinery work order (epic
  krt-profit/basetool#439).

<table>
<tr>
<td width="120" align="center">
<img width="88" alt="Made by the Community" src="docs/img/MadeByTheCommunity_White.png#gh-light-mode-only"><img width="88" alt="Made by the Community" src="docs/img/MadeByTheCommunity_Black.png#gh-dark-mode-only">
</td>
<td>
<b>Unofficial Star Citizen fan tool</b> — not affiliated with the Cloud Imperium group of companies.<br>
Star Citizen®, Roberts Space Industries® and Cloud Imperium® are registered trademarks of Cloud Imperium Rights LLC
</td>
</tr>
</table>

The app ships with an **installer** (MSI) and can be **uninstalled** like any
normal Windows program (entries in "Apps & features", Start menu, desktop
shortcut).

> **The Basetool's ingest interface may only be used by client software approved
> by [@greluc](https://github.com/greluc).** The gateway checks the client id, the
> scope and the payload's `tool` field against server-side allowlists and rejects
> everything else with `403 CLIENT_NOT_ALLOWED` (`REQ-INGEST-011` in
> `docs/specs/desktop-ingest.md` of the Basetool repo). This does not change who
> may *use the Basetool* — every member can upload blueprints and refinery work
> orders, just with the approved extractor. So use an official build from the
> [releases page](https://github.com/krt-profit/basetool-sc-extractor/releases); a
> self-built fork with a changed id will be rejected. Concretely,
> `DeviceGrantClient.CLIENT_ID` and `RefineryPipeline.TOOL` are contractual
> constants — changing them is a coordinated rollout on both sides.

---

## For end users

### Installing

1. Double-click `Basetool SC Extractor-<version>.msi`.
2. Follow the installation wizard (you can pick an installation folder). **No
   administrator rights** are required — the installation is per user.
3. Afterwards you get a Start-menu entry under **Basetool** and a desktop
   shortcut.

> A separate Java/JRE is **not** required — the runtime is bundled with the
> installer.

### Does Windows Defender warn you?

The MSI is **not signed** — a code-signing certificate costs money every year. To
Microsoft Defender, every new version is therefore an unknown file with no
reputation, and it occasionally reports a **false positive**, usually as
`Trojan:Script/Wacatac.H!ml`. The `!ml` stands for "machine-learning heuristic" —
a guess, not a concrete finding. The fact that the MSI carries a PowerShell
snippet for installing updates (start `msiexec`, clean up afterwards) looks like
an installer trojan to such a classifier, even though it is exactly the documented
update behaviour.

As a counter-check, CI **uploads every release to VirusTotal** automatically
(~70 antivirus engines; a few skip installers this large, which is why the count
in the notes is lower). The release notes carry the link to the report and the
SHA-256 of the MSI — so you can verify that the file you downloaded is exactly the
one the build produced:

```powershell
Get-FileHash <your-file>.msi
```

### Where does the MSI come from?

Every release carries a signed
[build provenance attestation](https://github.com/krt-profit/basetool-sc-extractor/attestations)
(SLSA, via Sigstore). It records which commit, which workflow and which run produced
exactly this file, and it is signed with a short-lived certificate that only GitHub's
build machine can obtain — nobody can create one for an MSI built somewhere else. With
the [GitHub CLI](https://cli.github.com/) installed you can check your download against
it:

```powershell
gh attestation verify <your-file>.msi --repo krt-profit/basetool-sc-extractor
```

That is the strongest statement available without a code-signing certificate: it does
not stop Defender's heuristic, but it does prove the file is the one this repository's
release build produced.

### Updates

On start the app quietly checks
[GitHub releases](https://github.com/krt-profit/basetool-sc-extractor/releases). If
a newer version exists, a banner appears on the start screen offering
**Download & install**:

1. The MSI installer is downloaded into a temporary folder (never into the
   installation folder) and verified by size + SHA-256 checksum.
2. The installer starts and the app exits so the update can replace the program
   folder.
3. **After the installation the update file is deleted again automatically** —
   even an aborted setup leaves nothing behind.

**Later** hides the offer for the current session. Only release metadata is
fetched from GitHub; no usage data is sent. Without an internet connection
nothing happens — the check fails silently and the app starts normally.

### Using it — Blueprints

1. Start the app and open **Blueprints** on the start screen.
2. Pick the **Star Citizen channel folder** — e.g.
   `C:\Program Files\Roberts Space Industries\StarCitizen\LIVE`. Read are the
   `Game.log` in that folder **and** every log in the `logbackups` subfolder
   (`Game Build(...).log`). Pre-filled is the folder of the last successful run,
   otherwise the default LIVE path if it exists on your machine. If you pick an
   **archive folder** — a folder holding loose `*.log` files, without `Game.log`
   and without `logbackups` — those are read instead. That is meant for logs you
   put aside; Star Citizen itself eventually cleans up its `logbackups`.
3. Pick the **output JSON (target)** — where the JSON file should be written.
4. Click **Extract blueprints**.

After the run the app shows a summary (detected players, blueprints by category,
the most recently received blueprints) and writes the full list to the chosen JSON
file.

### Using it — Refinery (screenshot extraction)

The refinery workflow reads the **SETUP view** of a refinery work order
(REFINEMENT CENTER) from screenshots — materials, quality, amount, yield, refine
toggles, location, method, cost and duration — and exports a
`RefineryExtract.json` that pre-fills the creation form in the Basetool under
*Refinery → Work orders → Import work order*. In addition, each screenshot's
**capture time** is exported (from the timestamp in the file name, e.g.
`Screenshot 2026-06-01 213823.png` or `ScreenShot-2026-06-06_15-50-53-C28.jpg`,
otherwise from the file's modification date) — the Basetool takes the capture time
of the last screenshot as the work order's **start time**.

**Prerequisite: Ollama (local AI model).** The images are **never** uploaded — the
evaluation runs entirely locally via [Ollama](https://ollama.com):

1. Install Ollama from ollama.com and start it (`ollama serve`, default port
   11434).
2. Pull the model: `ollama pull qwen3-vl:8b-instruct` — or simply let the app do
   it: the **preflight** detects a missing model and downloads it on click, with a
   progress indicator.

**Hardware tiers** (detected and pre-selected automatically by the app):

| Tier | GPU VRAM | Model | Time per image (measured) |
|---|---|---|---|
| Recommended | ≥ 12 GB | `qwen3-vl:8b-instruct` | ≈ 4–5 s |
| Minimum | ≥ 8 GB | `qwen3-vl:4b-instruct` | ≈ 4 s |
| Below that | — | `qwen3-vl:4b-instruct`, CPU mode | ≈ 30 s (works, slow) |

**Important for good results:**

- **Close Star Citizen first.** The AI model and the game share GPU and VRAM —
  with SC running you risk stutter up to a crash, and very slow extraction. The
  preflight detects a running `StarCitizen.exe` and warns (you can continue, but
  have to confirm deliberately).
- **Press "GET QUOTE" in the game first, then take the screenshot.** Before the
  quote the panel shows no yield, cost and duration (`--`) — such captures are
  detected and marked as incomplete.
- **1 folder = 1 work order.** Put all screenshots of the same work order
  (including scrolled partial views of the material list) into one folder; the app
  stitches the rows together automatically. Resolutions from 1080p to 8K
  (including ultrawide) are supported; alternatively you can use an already
  **manually cropped** panel image (it is detected as "pre-cropped").
- **Pasting images without a folder:** in the *Load images* step you can paste
  screenshots straight from the clipboard with **Ctrl+V** (e.g. from the Windows
  Snipping Tool) or **drag & drop** them into the window. If a screenshot folder
  is selected they are saved there; without one they go into a temporary folder
  that is deleted automatically when the app exits.
- **Deselecting individual images:** every tile has a checkbox (clicking the
  thumbnail toggles it too) — only ticked images go into the extraction.
  Deselected images stay visible in the grid but are greyed out and skipped.
- **The folder is watched continuously:** while the *Load images* step is open the
  app checks the selected folder for changes once per second — screenshots added
  later appear in the grid automatically, ones deleted from the folder disappear.
  Ticks you set or cleared are preserved, and images removed via ✕ are not added
  back.
- If several work-order panels sit side by side, the **leftmost** one (= the
  newest work order) is read.

The extraction processes **one image at a time** (throttling), shows the stages
*Locate → Normalize → Read* per image and ends in a review step: check every value
that was read together with its derived confidence, then **Export as JSON**. It is
only stored once you import it in the Basetool.

### Uninstalling

Like any Windows program:
**Settings → Apps → Installed apps → "Basetool SC Extractor" → Uninstall** (or the
classic route via *Control Panel → Programs and Features*).

**Residue-free removal — verified.** An install→uninstall test cycle confirms that
uninstalling removes **everything**:

| Artefact | after uninstall |
|---|---|
| Program folder `%LOCALAPPDATA%\Basetool SC Extractor\` (bundled JRE, ~515 files) | removed |
| Start-menu group "Basetool" including the shortcut | removed |
| Desktop shortcut | removed |
| "Apps & features" / registry entry | removed |

This works **completely** because the app deliberately writes **nothing** into its
own installation folder (no `config.json`, no `logs/` — unlike the Python original
that inspired it). Such files created at runtime are the usual reason an empty
program folder is left behind otherwise.

> Your **exported JSON files** live wherever you chose (default:
> `Documents\blueprints.json`) and are **deliberately not** deleted on uninstall —
> that is your data, not a program leftover.

**"Send to Basetool" and the remembered sign-in.** As soon as you use **"Send to
Basetool"**, the app stores two user-specific things **outside** the program
folder — so the program folder itself stays completely removable:

- a **`config.json`** under `%APPDATA%\Basetool SC Extractor\` (no secret: only
  your consent to send and the target URL). Roaming data, not a program leftover.
- a **refresh token** in the **Windows Credential Manager** (DPAPI-protected, per
  user) — so you do not have to confirm again on the next send. The same record
  holds a **private key** (EC P-256) the token is bound to (DPoP, RFC 9449): a copy
  of the token alone is therefore **worthless**, because every request must
  additionally be signed with that key — which is exactly why the two live
  together in one record. This entry is **not** removed on **uninstall**; delete it
  via **Start → "Disconnect from Basetool"** (which revokes the token server-side
  and deletes both locally) or in the Windows Credential Manager under the entry
  "Basetool SC Extractor".

---

## The JSON output

```jsonc
{
  "schemaVersion": 1,
  "tool": "Basetool Blueprint Extractor",
  "toolVersion": "1.0.0",
  "generatedAt": "2026-05-30T21:39:45Z",   // UTC, when the export was created
  "sourceFolder": "…\\StarCitizen\\LIVE",   // the channel folder
  "logFilesScanned": 424,                    // Game.log + logbackups\*.log
  "blueprintCount": 179,                     // total blueprints received
  "players": [
    {
      "handle": "greluc",                    // player name (from login lines)
      "blueprintCount": 179
    }
  ],
  "blueprints": [
    {
      "productName": "Yubarev \"Mirage\" Pistol", // exact item name (quotes included)
      "category": "Weapon",                       // derived category (see below)
      "receivedAt": "2026-03-26T16:49:31.050Z",   // when it was received (UTC)
      "player": "greluc",                         // recipient (from the source file)
      "notificationId": 19,                       // in-game notification index
      "queueSize": 2,                             // reported notification queue size
      "gameBuild": "11518367",                    // SC build no. (from the file name)
      "sourceFile": "Game Build(11518367) 26 Mar 26 (17 24 58).log"
    }
    // … sorted chronologically …
  ]
}
```

**Categories** (`category`) are derived heuristically from the name — the log
itself names no category:
`MiningTool` · `Ammo` (magazines/batteries) · `Armor` (Helmet/Core/Arms/Legs/…) ·
`Weapon` (Pistol/Rifle/Shotgun/…) · `Other`.

---

## How the extraction works

When you receive a blueprint, Star Citizen writes a notification line:

```
<2026-03-26T16:49:31.050Z> [Notice] <SHUDEvent_OnNotification> Added notification
  "Received Blueprint: Yubarev "Mirage" Pistol: " [19] to queue. New queue size: 2,
  MissionId: [00000000-0000-0000-0000-000000000000], ObjectiveId: [] [...]
```

The parser ([`BlueprintParser.kt`](src/main/kotlin/com/basetool/bpextractor/BlueprintParser.kt))
handles the real-world quirks of these lines:

- **Anchored on `Added notification`** — every blueprint message appears several
  times in the log (the original notification, a queue echo line and several
  `UpdateNotificationItem` follow-ups with `Next`/`StartFade`/`Remove`). Only the
  `Added notification` line is counted, so each blueprint is counted **exactly
  once** (otherwise ~6×).
- **Game language — read from your installation.** The label in front of the name
  (`Received Blueprint`) comes from the game's localisation tables and is
  translated if you use a language patch; the line around it stays English.
  Instead of guessing translations, the app reads the key
  `crafting_hud_notification_received_blueprint` straight out of the
  `data\Localization\*\global.ini` of the folder you picked — that is, exactly the
  text your game is going to write. If the wording changes, that takes effect on
  the next run without a new release. **All** installed languages are read, not
  just the active one: a scan reaches months back. Without a readable `global.ini`
  (an unmodified English game, or an archive folder) the built-in texts for
  English, German and Swiss German apply.
- **Names with quotes** (`Yubarev "Mirage" Pistol`), **parentheses**
  (`Yubarev Pistol Battery (10 cap)`), **slashes** (`Sth/2/C Cirrus`) and
  **hyphens** (`ADP-mk4 Core Woodland`) are captured correctly. The name ends
  reliably at the `: " [<id>]` separator.
- **The player name** comes from the login lines of the same file
  (`User Login Success - Handle[…]` or the character-status line with `geid` and
  `accountId`) — the `MissionId` on the blueprint line is always `0000…` and
  therefore useless.
- **The build number** comes from the file name (`Game Build(11518367) …`).

Large logs (sometimes > 30 MB) are **streamed line by line**, never loaded into
memory as a whole.

---

## Building it yourself (for developers)

**Prerequisite:** JDK 25 (e.g. Azul Zulu). Gradle comes via the bundled wrapper.

```powershell
# run the tests
.\gradlew.bat test

# start the app
.\gradlew.bat run

# build the Windows installer (MSI) — ALWAYS via this script (see the WiX note below)
.\package-msi.ps1
```

The finished MSI then sits at `dist\Basetool SC Extractor-<version>.msi` (the
script copies it there; the Gradle original is under
`build\compose\binaries\main\msi\`).

### A note on WiX (MSI creation)

`packageMsi` uses `jpackage`, which since JDK 24 works with modern WiX (**4+**)
([JDK-8319457](https://bugs.openjdk.org/browse/JDK-8319457)): it invokes the first
`wix.exe` on the `PATH` and needs the extensions `WixToolset.Util.wixext` and
`WixToolset.UI.wixext` in the global extension cache. There are two pitfalls:

- **Mixed WiX versions:** jpackage passes the extensions *unversioned*, and
  `wix.exe` resolves them to the *highest* version in the cache. If extensions of
  a newer major are cached there (e.g. v7 next to a v6 toolset), an older
  `wix.exe` aborts with **error WIX0144 / exit code 144** — long misread as a
  jpackage bug ([JDK-8356592](https://bugs.openjdk.org/browse/JDK-8356592)).
- **OSMF EULA:** WiX **v7+** refuses every command until `wix eula accept wix7`
  has been run once per user account (Open Source Maintenance Fee; only payable
  above roughly US$ 10,000 annual revenue — details:
  <https://docs.firegiant.com/wix/osmf/>).

That is why the MSI must **always** be built via the bundled script:

```powershell
.\package-msi.ps1
```

The build is pinned to **WiX 7**: the script takes the newest installed WiX 7.x
and puts it at the front of the `PATH` for this build process only, preflights the
EULA and the extensions with clear error messages (the EULA is accepted
automatically on CI only) and, on machines without WiX 7, bootstraps a local copy
under `tools\wix` (dotnet tool, version 7.0.0). **Nothing** on the system is
changed; the finished MSI lands in `dist\`.

### Adjusting installer behaviour

In [`build.gradle.kts`](build.gradle.kts) under `windows { … }`:

| Option | Effect |
|---|---|
| `perUserInstall = true` | installation without admin rights, per user in "Apps & features" |
| `dirChooser = true` | a step for choosing the installation folder |
| `menu = true` / `menuGroup` | Start-menu entry |
| `shortcut = true` | desktop shortcut |
| `upgradeUuid` | stable id so new versions replace the old one |
| `iconFile` | custom icon (place it at `src/main/resources/app.ico`) |

> The MSI is ~108 MB. What gets bundled is a **slim** JDK 25 runtime — only the
> modules actually needed: `modules("java.instrument", "jdk.unsupported",
> "java.net.http", "jdk.management")` (HTTP client for Ollama, memory probe for the
> hardware preflight) plus the ones the Compose plugin detects automatically
> (`java.desktop` etc.), determined via `gradlew suggestRuntimeModules`. The user
> still needs no Java of their own.
> (`jvmArgs += "--enable-native-access=ALL-UNNAMED"` silences the JDK 25 "native
> access" warnings that Skiko's `System.load()` would otherwise write to stderr.)

---

## Project structure

```
basetool-bp-extractor/
├── build.gradle.kts                  # build + Compose/MSI configuration
├── settings.gradle.kts
├── gradle.properties
├── gradlew(.bat)                     # Gradle wrapper (9.7.0)
├── package-msi.ps1                   # MSI build (WiX selection, EULA/extension preflight)
├── src/main/kotlin/com/basetool/bpextractor/
│   ├── Main.kt                       # Compose GUI (tabs/shell), entry point
│   ├── BlueprintParser.kt            # blueprint line parsing (core)
│   ├── BlueprintExtractor.kt         # folder scan, aggregation, JSON
│   ├── Legal.kt                      # mandatory Fankit texts (trademark notice, verbatim)
│   ├── refinery/                     # refinery pipeline (pure, no UI)
│   │   ├── Locate.kt                 #   panel detection + normalisation (CV)
│   │   ├── PanelReader.kt / PanelRead.kt  # VLM read + markdown reformat
│   │   ├── Stitcher.kt / Validation.kt    # row stitching + confidence policy
│   │   ├── RefineryPipeline.kt       #   orchestration + JSON export
│   │   ├── Preflight.kt              #   hardware probes + tier decision
│   │   ├── OllamaClient.kt           #   Ollama HTTP API (tags/ps/chat/pull)
│   │   └── model/RefineryExtract.kt  #   frozen JSON contract (v1)
│   ├── ui/Theme.kt                   # KRT theme (colours, fonts, type, shapes)
│   ├── ui/KrtComponents.kt           # HUD box, CTA/ghost buttons, checkbox …
│   ├── ui/Navigation.kt              # CommandStrip (tabs + inline stepper), DE/EN toggle
│   ├── ui/StepScaffold.kt            # step frame: head · scroll body · footer CTA
│   ├── ui/StartScreen.kt             # launcher (workflow cards)
│   ├── ui/RefineryScreen.kt          # refinery workflow host (5 steps)
│   ├── ui/refinery/                  # the five refinery screens, UI state (image selection),
│   │                                 #   ImageIntake (Ctrl+V / drag & drop capture)
│   ├── ui/FilePicker.kt              # KRT file/folder picker (no native dialogs)
│   ├── ui/i18n/Strings.kt            # DE/EN string catalogue
│   ├── ui/WindowChrome.kt            # undecorated title bar + window buttons
│   └── model/Models.kt               # blueprint JSON data models
├── src/main/resources/               # fonts (Lato), app.ico, prompt v1, OCR models (ocr/)
├── src/main/composeResources/drawable/ # honeycomb_bg.svg, basetool_extractor_icon.png, Made-by-the-Community logo
├── src/test/kotlin/…                 # unit tests
├── src/test/resources/sample.log     # test fixture (edge cases)
├── docs/refinery-extractor/          # phase 0 findings (model bake-off etc.)
├── docs/img/                         # README images (Made-by-the-Community logo)
└── game-log/                         # private sample logs (not in the repo)
```

## Design

The GUI follows the **"Das Kartell" / KRT design system** (source: the
`das-kartell-design` Claude skill): a dark sci-fi "HUD", house orange `#E77E23` on
black, **Lato-only typography** (headlines: Lato **Bold** UPPERCASE with 0.05em
tracking, body: Lato Light — no more Audiowide/Ethnocentric), sharp corners
throughout with diagonal HUD corner brackets, orange bloom instead of soft
shadows. Implemented as a Compose Material 3 theme (`ui/Theme.kt`,
`ui/KrtComponents.kt`) with a strict action hierarchy: exactly **one** filled
orange CTA per context ("Extract blueprints"), secondary actions as ghost buttons,
labels in neutral grey.

The **Extractor mark** from the Basetool logo family serves as the app/window icon
(`app.ico` for installer and exe,
`composeResources/drawable/basetool_extractor_icon.png` for the window and title
bar). It shares circle and star with the Basetool mark of the web interface, but
inverts the wedge **downwards** into a catching bracket — the same DNA, the
mirrored statement: data is extracted from the 'verse. Behind the content sits a
**subtle honeycomb background** (`honeycomb_bg.svg` — orange hexagons at 10%
opacity) as a texture. The mark, the honeycomb and **Lato** all come from
`das-kartell-design/assets/`. A separate display typeface is deliberately omitted:
the design system is **Lato-only** — the **commercial** Ethnocentric the brand
would call for is not bundled, and headlines carry their character through Lato
Bold + UPPERCASE + tracking rather than through a typeface of their own.

The window is **undecorated** (no white OS frame): a custom dark title bar
(`ui/WindowChrome.kt`) carries the Extractor mark plus the title and its own
minimise/maximise/close buttons, with sharp corners, an orange accent line, a thin
HUD frame and a resize corner at the bottom right.

## Licence

This program is **free software** under the **GNU General Public License, version
3 or (at your option) any later version** (GPL-3.0-or-later). You find the full
licence text in [`LICENSE`](LICENSE).

```
Copyright (C) 2026 Basetool

This program is free software: you can redistribute it and/or modify it under the
terms of the GNU General Public License. It is distributed in the hope that it
will be useful, but WITHOUT ANY WARRANTY.
```

### Third-party components

| Component | Licence |
|---|---|
| Kotlin, Compose Multiplatform, Material 3, Skiko, kotlinx-serialization/-coroutines | Apache-2.0 |
| Skia (via Skiko) | BSD-3-Clause |
| Bundled Java runtime (OpenJDK 25) | GPLv2 **with Classpath Exception** |
| The **Lato** typeface | SIL Open Font License 1.1 |

The OFL licence text of the typeface sits under
[`src/main/resources/fonts/`](src/main/resources/fonts/) (`Lato-OFL.txt`). The
**Classpath Exception** of the bundled JRE permits redistribution together with
this (GPL) program without the JRE itself changing its licence because of it; its
notices are in the installation package under `runtime/legal/`.

### Trademarks

The **KRT / "Das Kartell" logo** and the associated trademarks are the property of
their owners. The GPL covers the **source code** of this program, **not** the
brand and logo assets — those are not licensed for free reuse.

### Star Citizen

This is an **unofficial Star Citizen fan tool** and is not affiliated with the
Cloud Imperium group of companies. It uses the **"Made by the Community" logo** and
the trademark notice in accordance with the official **Star Citizen Fankit**
(Fankit Agreement, Fan Style Guide and RSI Terms of Service). **No** Star Citizen
game data or assets are shipped with it; the `Game.log` files it reads and the JSON
data it exports are yours.

> Star Citizen®, Roberts Space Industries® and Cloud Imperium® are registered trademarks of Cloud Imperium Rights LLC

Fankit assets such as the "Made by the Community" logo are the property of Cloud
Imperium Rights LLC / Cloud Imperium Rights Ltd. and are used under the Fankit
terms — this project's GPL does **not** extend to those trademarks and assets.
