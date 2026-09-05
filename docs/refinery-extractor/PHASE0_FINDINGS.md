# Phase 0 spike report — refinery screenshot extraction (#433)

> **Doc type:** Spike report — frozen once Phase 0 closes; Phase 3 (#436) consumes its
> artifacts. **Status: complete** (one owner action open: low-resolution captures, §10).
> Epic: [krt-profit/basetool#439](https://github.com/krt-profit/basetool/issues/439) ·
> Plan: `basetool/docs/REFINERY_SCREENSHOT_IMPORT_PLAN.md` §Phase 0.
> Date: 2026-06-10 · Hardware: RTX 5090 32 GB VRAM, Ryzen 9 9950X3D, 95.6 GB RAM,
> Ollama 0.30.7, Python 3.13 + Pillow 12.2.

All spike code lives in `spike-phase0/harness/` (branch `spike/refinery-phase0`);
private inputs/outputs stay under the gitignored `spike-phase0/work/`.

## 1. Golden test set (deliverable 4)

12 images from the owner's example set, transcribed triple-blind (three independent
readers, zero disputes), stored as `work/golden/<id>.json`:

| Group | Images | Covers |
|---|---|---|
| Auftrag 1 | 4 × 4K frames | quoted order, duplicate materials, **scrolled viewport** (same header, different rows) |
| Auftrag 2 | 4 × 4K frames | **un-quoted GET-QUOTE state** (3 frames), the quote transition (1 frame), **HUD-marker overlap**, second PROCESSING panel beside the SETUP panel |
| Auftrag 3/4/6 | 3 pre-cropped panels | pre-cropped input path, `(ORE)` suffixes, REFINE-OFF rows, `IN MANIFEST ≠ TO REFINE` (a4) |
| Auftrag 5 | 1 × 4K frame | game-UI-truncated name (`CONSTRUCTION S`), large quantities (55 100) |

A "cell" for scoring = 6 order-level fields + 5 columns × every non-partial golden row;
exact match after normalization (`harness/score.py`: numeric folding `48928.00 == 48928`,
case/whitespace/bracket folding for names, `null == "--"` for absent values — the
terminal renders absent cost/time/yield as `--`).

**Coverage gaps (owner action, plan §Phase 0 Inputs):** native 1080p / 1440p / ultrawide
captures still need to be collected; the current set is 4K + pre-cropped only.
*(Partially superseded: since 2026-06-12 the golden set is 10 orders / 19 images
including native ultrawide — see the addendum "Golden set, current composition".)*

## 2. Model bake-off (deliverable 1)

All 7 models × 2 strategies × 12 images (scorer of 2026-06-10, after the `null == "--"`
fix); raw outputs under `work/bakeoff/<model>/<strategy>/`:

| Model | schema | markdown | sec/image (GPU) | Verdict |
|---|---|---|---|---|
| `qwen3-vl:8b-instruct` (q4_K_M) | 0.9821 | **0.9872** | 4.2–4.4 | **winner / default pin** |
| `qwen3-vl:8b-instruct-q8_0` | 0.9796 | 0.9872 | 4.3–7.3 | no gain over q4; not needed |
| `qwen3-vl:4b-instruct` | 0.9209 | 0.9872 | 3.1–4.3 | **low-VRAM/CPU fallback** |
| `gemma4:12b` | 0.9235 | 0.8852 | 22–29 | behind, slow |
| `glm-ocr` (0.9B) | 0.8929 | 0.0714 | 3.8–6.0 | viable only schema-forced; beaten by 4b |
| `qwen3.5:9b` | 0.5357 | 0.4872 | ~43 | unusable (5 parse failures) |
| `gemma4:e4b-it-qat` | 0.2755 | 0.3699 | 7.5–10.4 | unusable |

Decisive tie-breaker among the three 0.9872 results: **which errors survive the
production mitigations (§6)**. The 8b variants' errors are all absorbed (HUD overlay →
numeric validation; name garble/trailing dot → fuzzy matching; method → enum
nearest-match; `? AUEC` → numeric validation). The 4b's two REFINE-toggle misreads
(OFF→ON, a4) are the **only error class with no validation net** — they also corrupt the
`SUM_MISMATCH` checksum. Hence: **default `qwen3-vl:8b-instruct` q4_K_M; fallback
`qwen3-vl:4b-instruct`** (documented weakness: REFINE toggles on small/distant panels).

The documented qwen-vl table-repetition-loop bug never appeared in either quantization
(0 parse failures, 0 extra rows across 48 runs) — q8_0 is not needed as a mitigation.
`glm-ocr`'s markdown failure is expected: the OCR specialist does not follow the layout
instruction; with schema-forcing it is decent but strictly dominated by the 4b. Identical
accuracy across the re-timed q4 runs confirms temp-0 determinism end-to-end.

### 2.1 Error taxonomy (primary model, every remaining mismatch)

| Class | Instances | Production mitigation |
|---|---|---|
| HUD overlay read into a cell (`2.1KM` as yield) | 1 (both strategies) | numeric plausibility validation → review flag (§6) |
| Name spacing/garble (`SA VRIL IUM`, `SAVRILIMUM`) | 2 | absorbed by backend fuzzy matching (Phase 1, threshold 0.84) |
| Method "autocorrect" (`DINYX SOLVATION`) | 1 (both strategies) | nearest-match against the closed refining-method enum |
| Truncation artifact (`CONSTRUCTION S.` trailing dot) | 1 | fuzzy matching absorbs; or strip trailing punctuation |
| REFINE toggle misread (OFF → ON) | 2, **schema strategy only** | strategy choice (markdown); review UI shows toggles |

The schema strategy's two extra semantic errors (REFINE toggle) are consistent with the
"Format Tax" hypothesis (plan deliverable 2): schema-forcing during the vision pass
degrades visual judgment. Markdown + deterministic reformat wins on both accuracy and
error severity.

## 3. Locate → Normalize → Read (deliverable 3)

`harness/normalize.py`. **Locate** is classical CV on a 1/4-scale frame using two color
anchors — pure luminance profiling fails (the whole terminal interior is uniformly dark,
measured mean 35–64):

1. the maroon SETUP tab strip (~RGB(72,49,45); gap-tolerant runs, width-similarity
   clustering, strip height ≤ 12 rows at 1/4 scale), and
2. a bright-orange CTA/progress element below it (absolute run threshold — small/distant
   panels have small buttons).

The maroon strip covers only the panel's **left** part (the "WORK ORDER n" zone right of
it is dark gray, measured); the panel's right edge = max(strip end, CTA end) + margin.
Do **not** scan the whole body for orange — with two panels side by side that bleeds into
the neighbour's progress bar (verified regression during the spike).

**Multi-candidate + owner rule (2026-06-10):** a frame can hold several work-order panels
side by side (Auftrag 2: SETUP next to a running PROCESSING panel). The **newest order is
always the leftmost panel, and the leftmost is the extraction target** — candidates are
returned left→right and the pipeline takes `boxes[0]`. The VLM read's `panelType` is a
*validation* only (warn if not SETUP), never a selection mechanism.

Pre-cropped inputs (width < 1000 px and portrait) skip Locate. Fallback when no candidate
is found: the verified 4K geometry `(950, 350, 920, 1500)` scaled by frame size.

**Normalize** always runs client-side (Ollama silently downscales > ~3.2 MP): crop from
the native frame, resize to long edge 1536 px (pre-cropped capped at 1200 — upscaling a
~500 px panel beyond ~2.4× only blurs), dimensions snapped to multiples of 32.

**Location read** (terminal header strip, separate region `(250, 200, 900, 220)` @4K,
2× upscale): **9/9 exact matches** (`LEVSKI`), ~2.5 s/image, 3 output tokens.

## 4. Prompt + read strategy (deliverable 2)

Prompt v1 is externalized at `harness/prompts/setup_panel_v1.txt` (temperature 0,
`num_predict` 4096 with one retry at 8192 on `done_reason=length`, numeric cells read as
strings, parsed in code). One amendment shipped during the spike: the positive `quoted`
rule ("quoted is TRUE when the YIELD column shows numbers and the bottom button reads
CONFIRM; FALSE only when YIELD shows `--`") after a smoke-test miss.

**FROZEN: strategy (b) — freeform markdown read + deterministic reformat**
(`harness/read.py: read_markdown` + `_parse_markdown`). It beats schema-forcing on every
qwen-vl variant (0.9872 vs 0.9821/0.9796/0.9209) and avoids the schema strategy's
semantic-class errors (REFINE toggle) — the "Format Tax" confirmed on our domain. The
deterministic markdown→JSON parser is ~40 lines and fully unit-testable. Prompt v1 +
`MARKDOWN_INSTRUCTION` are the frozen artifacts Phase 3 ships as resources.

Note: the prompt asks the model to ignore overlapping AR markers, but the `2.1KM` case
(§2.1) shows prompt-hardening alone does not stop overlay bleed-through — the
**validation rule** (§6) is the reliable defence.

## 5. Hardware tiers (deliverable 5)

Measured on RTX 5090 32 GB / Ryzen 9 9950X3D (16C/32T); loaded sizes from the
vendor-neutral `ollama ps` probe (recorded per model under
`work/bakeoff/*_ollama_ps.txt`), **at Ollama's default 32k context**:

| Model | Loaded (GPU) | GPU sec/image | CPU-only sec/image (100% CPU probe) |
|---|---|---|---|
| `qwen3-vl:8b-instruct` q4_K_M | 10 GB | 4.2–4.4 | ~53 |
| `qwen3-vl:8b-instruct-q8_0` | 13 GB | 4.3–7.3 | not measured (not pinned) |
| `qwen3-vl:4b-instruct` | 7.9 GB | 3.1–4.3 | ~27 |
| `glm-ocr` | 4.0 GB | 3.8–6.0 | ~21 |

- **Tier table (preflight UI, `DESIGN_SC_EXTRACTOR.md` §5.1):** recommended = ≥12 GB
  VRAM → default 8b (10 GB loaded); minimum = ≥8 GB VRAM → 4b fallback (7.9 GB);
  below that → CPU mode with a "~1 minute per screenshot" warning (53 s on a top-tier
  16-core; weaker CPUs proportionally slower — surface the measured first-image time).
- **Phase-3 knob:** the loaded sizes include the default 32k context; our prompt + one
  image need a fraction of that. Shrinking `num_ctx` (e.g. 8k) cuts the KV-cache share
  and gives 12 GB cards real headroom for the 8b — measure once in Phase 3 and pin it.
- CPU mode is fully functional (accuracy identical — same model, same weights; verified
  36/36 on the probe image for both qwen variants).
- The auto-select policy stands: probe VRAM → pick 8b / 4b / CPU + warn; "close Star
  Citizen before extracting" stays (the game holds VRAM).

## 6. Confidence-derivation policy (deliverable 6) — **two-pass rejected**

The planned policy (exact-match agreement across two reads with varied crop) was
validated against the golden set (`harness/twopass.py`) and **fails on the data**:

| Pass-2 perturbation | Real errors caught | False flags (new pass-2 errors) |
|---|---|---|
| pad 24, scale 0.9 | **0 / 5** | 2 (digit flips `6811→6011`, `404→484`) |
| pad 40, scale 1.0 | **0 / 5** | 2 (digit flips `404→494`, `1802→1902`) |

All real errors are *systematic* — the model reads the same wrong value at every crop
(temp 0), so crop perturbation can never expose them; meanwhile the perturbed pass
*introduces* ~2 digit errors per 12 images and doubles latency.

**Frozen policy instead — deterministic validation, no second pass:**

1. **Numeric plausibility:** QTY / YIELD / header totals / cost must parse as a number or
   `--`; anything else (e.g. `2.1KM` HUD bleed-through) → row review flag. Catches error
   class 1.
2. **Closed-enum nearest-match** for the refining method (and `panelType`). Catches
   class 3.
3. **Backend fuzzy-match score** (Phase 1, threshold 0.84) is the per-row name
   confidence; classes 2/4 sit above the threshold and surface as match-with-score.
4. **Header-total checksum** per §7 (one-sided — see below).
5. Verbalized VLM self-confidence stays **forbidden** (uncalibrated for OCR, plan §9.5).

The §5.4 UI thresholds are unchanged; they are fed from (1)–(4).

## 7. Header-total semantics (deliverable 7) — verified

From the golden set (fully visible panels a3 / a5 / a6, plus a4's visible rows):

- **`TO REFINE` = Σ QTY of REFINE-ON rows**, within ±1 per panel (displayed integers are
  rounded from fractional internals): a3 1160≈1161, a5 55100=55100, a6 2826≈2827,
  a4 1947≈1948.
- **`IN MANIFEST` ≥ `TO REFINE`**; on a3/a5/a6 the OFF rows contribute 0 and
  `IM == TR`. On a4 (`2564` vs `1948`) the two small OFF rows (37+578=615≈616=IM−TR)
  are counted while the large OFF row is not — hypothesis: toggle state changed after
  quoting. IM's exact rule is **not** reliably reconstructible from one frame; do not
  validate against it.
- **The materials list is a scrolling ~6-row viewport** (Auftrag 1/2: identical header
  totals, different visible rows across frames). Σ of visible rows can therefore never
  equal the header on long orders — a hard equality check is impossible by design.

**Frozen `SUM_MISMATCH` rule (one-sided):** flag WARNING ("a scrolled screenshot may be
missing rows") when `Σ QTY(visible REFINE-ON rows) > toRefine + rowCount` (the +rowCount
absorbs ±1 rounding per row), or when any single row QTY exceeds `toRefine + 1`.
Equality is **not** required; a shortfall means scrolled-out rows, which the §5.4 review
UI surfaces via the row count.

## 8. Domain rules confirmed by the owner during the spike

- **Leftmost panel = newest order = extraction target** (2026-06-10, see §3).
- Real screenshots are **private** (player handle + account balance) — never committed;
  fixtures are synthesized/redacted.

## 9. Blueprint-parser regression (Definition of Done)

`.\gradlew.bat test --rerun` green on `spike/refinery-phase0` (2026-06-10) — the spike
touches no production parser code. The manual 179/`greluc` characterization check against
the private `game-log/` remains a manual step before merging Phase 3.

## 10. Open items

1. **Owner:** collect native 16:9 1080p / 1440p captures (§1) — tracked as a Phase 3
   risk; the Locate stage is resolution-agnostic by construction but unverified below
   4K except via downscaling. Ultrawide is covered since 2026-06-12: Auftrag 8/9 add
   native 5120×1440 captures, live-validated in the golden-set sweeps (addendum).
2. ~~Phase 3: measure and pin a reduced `num_ctx` (§5) and re-verify the 12 GB tier.~~
   **Done 2026-06-12** — pinned to 12288 in `HttpOllamaClient`; see the addendum.

Everything else (model pins, strategy/prompt freeze, hardware tiers, confidence policy,
header semantics, golden set, parser regression) is **done** — see the sections above.

## Addendum 2026-06-12 — REFINE correction from the YIELD column (Auftrag 10)

A field capture (Auftrag 10, two pre-cropped 4K panels) showed the REFINE misread class
on the pinned **8b** too, not just the 4b (§2): two refine-OFF rows (BEXALITE 597,
LARANITE 510) read as `ON` at full confidence. Root cause is visual, not strategic: the
toggle renders an **orange knob in BOTH states** — left = OFF, right = ON — so prompt
v1's cue ("filled/orange = ON, empty/gray = OFF") cannot discriminate.

Fix follows the §4 principle (validation rules, not prompt-hardening, are the reliable
defence) and extends the §6 policy with a deterministic rule: **in a quoted order the
YIELD column is the authoritative refine signal** — the terminal quotes a yield for
exactly the refine-ON rows and renders `--` for OFF rows (yield 0 stays ambiguous:
INERT MATERIALS shows 0 while OFF, so 0 falls back to the toggle read). A contradicting
toggle read is corrected at confidence 0.85 plus an order-level `REFINE_CORRECTED`
warning. Un-quoted orders carry no yield signal; there the toggle read still stands.

**Prompt v1 amendment 2 (2026-06-12):** the toggle cue is re-worded to knob POSITION
(right = ON, left = OFF; both states show an orange knob, fill never decides) plus the
yield correlation as a secondary hint — same amendment mechanism as the §4 `quoted`
rule. Smoke test (8b, temp 0, raw Auftrag 10 captures): capture 1 reads both OFF rows
correctly with the amended cue (was 0/2 before); capture 2 still reads its LARANITE
instance as ON — the amendment reduces but does NOT eliminate the misread class, so the
validation-layer correction above stays the authoritative defence for quoted orders.
The amendment mainly protects UN-quoted orders (no yield signal there); the golden-set
re-validation is the sweep below.

**Golden-set re-validation (2026-06-12, live 8b via the `PromptSmokeTest` harness —
env-gated on `PROMPT_SMOKE_DIR`, a no-op in CI):** the 10 sample-order folders
(`Beispiele Raffinerieaufträge`, private captures) ARE the Phase 0 golden set; the full
sweep surfaced and fixed two follow-up issues. (1) The yield-based refine correction
must gate on the ROW's surviving read having seen the quoted state, not on the
order-level `quoted` flag — Auftrag 2 mixes three pre-GET-QUOTE captures with one
quoted capture, and rows visible only in the early captures show `--` legitimately
(`StitchedRow.quotedRead` carries the provenance). (2) The model transcribes the
material suffix inconsistently as `(ORE)`/`[ORE]` across reads of the same panel; the
stitcher's row-identity key now folds the bracket style (exported names stay verbatim).
Result: orders 1/3/4/5/6/7 read clean (checksums consistent, no warnings); orders 8/9
have their misread ON toggles corrected by the yield rule with checksum-exact ON sums
(407≈408, 1316≈1317); order 10 corrects three toggle misreads and `SUM_MISMATCH`
correctly flags its remaining QTY digit misread (403→483); order 2 flags
`SUM_MISMATCH` over a residual digit misread — review-flow material, as designed.

Same capture, same root-cause family: a QTY digit misread (`403` → `483`) — caught by
the §7 checksum once the two corrected OFF rows no longer inflate Σ QTY(ON).

**Golden set, current composition (2026-06-12 — supersedes the 12-image set in §1):**
10 order folders, 19 images. New since §1: Auftrag 7–10, which add native **ultrawide
5120×1440 JPEG** captures (closing that part of the §1/§10 coverage gap) and the
REFINE-OFF/digit-misread cases that drove this addendum series.

| Group | Images | Covers |
|---|---|---|
| Auftrag 1 | 4 × 4K frames | quoted order, duplicate materials, **scrolled viewport** (same header, different rows) |
| Auftrag 2 | 4 × 4K frames | **un-quoted GET-QUOTE state** (3 frames) + the quote transition (1 frame), **HUD-marker overlap**, residual QTY digit misread (494→404, verify-corrected) |
| Auftrag 3/4/6/7 | 4 pre-cropped panels (~480–520 px wide) | pre-cropped input path, small-glyph reads, `(ORE)` suffixes, REFINE-OFF rows, `IN MANIFEST ≠ TO REFINE` (a4) |
| Auftrag 5 | 1 × 4K frame | game-UI-truncated name (`CONSTRUCTION S`), large quantities (55 100) |
| Auftrag 8/9 | 2 + 2 × **ultrawide 5120×1440 JPEGs** | native ultrawide aspect + JPEG compression; REFINE toggle misreads corrected by the yield rule; a9 = largest row set (11 rows) |
| Auftrag 10 | 2 pre-cropped panels (~910–970 px wide) | the **orange-knob REFINE-OFF class on the pinned 8b**, the `403`→`483` digit misread (checksum/verify case) |

Scoring stays per §1 (cell = order fields + 5 columns × non-partial row, normalized
exact match); re-validation runs live through the `PromptSmokeTest` harness rather than
the spike scorer. Still missing per §10: native 16:9 1080p / 1440p captures.

## Addendum 2026-06-12 — read-time fixes for the residual error classes: experiments

Both residual classes (REFINE toggles on UN-quoted orders; digit misreads) were probed
for read-time fixes against the golden set. Results, so nobody re-runs these:

**Digit misreads are NOT fixable at read time — three negatives.** The Auftrag 10 case
(`403` read as `483`; ground truth re-verified: the TO REFINE header pixels are an
unambiguous `1645`, and Σ QTY(ON) with 403 lands exactly on 1644≈1645):
1. `qwen3-vl:8b-instruct-q8_0` (higher weight precision) reads `483` too — not a
   quantization artefact. Auftrag 2's residual digit error also survives q8 unchanged.
2. Upscaling the pre-cropped panel to the full 1536 px (instead of the 1200 cap) reads
   `483` too — not an input-resolution artefact.
3. A focused, 4×-zoomed QTY-column strip read STILL reads `483` — the glyph pixels are
   genuinely ambiguous (the terminal's chromatic aberration turns the `0` 8-shaped).
   The information that it is 403 exists only in the header-checksum arithmetic.
   `SUM_MISMATCH` + review remains the correct and only defence. (A basetool-side
   yield-ratio plausibility check could narrow review further — it owns the refinery
   yield data; the extractor must not hardcode game-balance constants.)
4. Post-hoc chromatic-aberration removal does NOT help either. The actual R/B channel
   misalignment vs G is only ~±0.25–0.4 px (measured by glyph-masked NCC registration;
   full-area registration is biased to zero because SC applies film grain AFTER the CA,
   channel-correlated). Re-aligning the channels still reads `483`; dropping to the
   green channel alone reads `483` AND introduces a new digit error (105 → 165 — R+B
   carry real SNR for the white digits). The fringes are the visible symptom, but the
   damage (subpixel smear + grain + TAA on 2-px glyph strokes) is baked in at capture
   time and not invertible. The only effective lever is at the source: the images step
   now tells the user to set chromatic aberration to 0 in the game before capturing.

**One new deterministic rule shipped:** `YIELD > QTY` is physically impossible
(refining removes impurities) ⇒ guaranteed digit misread in one of the two cells; the
row drops to [implausible] like a non-numeric cell. Catches a subclass row-precisely.

**REFINE toggle strip re-read: prototyped, validated, REJECTED.** A cropped, zoomed
read of just the toggle column (third read region, analogous to the location read) was
prototyped for un-quoted panels. On the full golden set (19 images): 8 images fully
correct (including both Auftrag 10 captures the panel read got wrong), 8 degenerate
answers safely caught by a row-count gate — but **2 images (Auftrag 3/4, small
pre-crops) returned a count-matching list with 1–2 WRONG states**, i.e. the strip read
would inject toggle errors the panel read had right. Same failure pattern that
rejected two-pass agreement in §6: a second read that errs itself is no defence. NOT
shipped; un-quoted orders keep the §5.3 `UNQUOTED_ORDER` re-capture advice as designed.

## Addendum 2026-06-12 — model bake-off round 2 + cross-model verify (shipped)

**Candidate sweep** (live golden set, identical prompt/pipeline/`num_ctx`): no installed
or newly available Ollama vision model beats the pinned `qwen3-vl:8b-instruct` as the
primary. `q8_0` reads the same digit errors (not a quantization issue); `gemma4:12b`
breaks stitching with digit noise at ~40 s/image; `gemma4:e4b`, `glm-ocr`,
`deepseek-ocr`, `minicpm-v4.6` are structurally unusable on this panel;
`qwen3.5:9b` (thinking) needs ~66 s/image and loses 7/10 orders; `ministral-3:8b` is
mid-field; `minicpm-v4.5` LOOKS clean (and reads the famous `403` correctly!) but
silently DROPS and duplicates rows (81 vs 86 across the set; A9: 5 of 11 rows) and
flips toggles at full confidence — its clean checksums are shortfall artefacts. The
8b pin stands.

**Cross-model verify (shipped, `CrossModelVerify`):** §6 rejected two-pass agreement
with the SAME model (temp-0 errors are systematic — both passes read the same wrong
value). Two DIFFERENT vision encoders, however, err decorrelated: on the golden set
8b × 4b disagree on 8 of 430 cells and there is NO cell where both read the same wrong
value — the union of both error sets becomes visible. Mechanism (after stitching, one
model switch per run): rows pair 1:1 (count mismatch ⇒ conservative order-level
`VERIFY_MISMATCH` only); cosmetic name variants fold; a disagreeing QTY on a refine-ON
row is ARBITRATED by the TO-REFINE checksum (exactly one candidate lands the Σ inside
±rowCount ⇒ it wins, `VERIFY_CORRECTED`, confidence 0.85); everything else undecidable
is contested (confidence 0.75, `VERIFY_MISMATCH`). Live result: the two open digit
misreads (A2 494→404, A10 483→403) are now AUTO-CORRECTED — both former
`SUM_MISMATCH` orders validate cleanly — and the previously silent errors (A1 quality,
A9 quality+qty) are flagged row-precisely. Zero false corrections; +~1.5 s/image.

**Partner pin: `Preflight.MODEL_VERIFY = qwen3-vl:4b-instruct`** — it aligns row sets
on 8/10 orders (10/10 since prompt amendment 3 + garble-tolerant stitching, see the
follow-up addendum) and reads both critical digits correctly; `minicpm-v4.5` also reads
them but misaligns row sets on most orders (verify degrades to "not comparable").
Policy: verify runs on the RECOMMENDED tier (GPU) only and only when the partner is
already installed — an accuracy bonus, never an extra download or a below-tier
slowdown. Harness: `PROMPT_SMOKE_VERIFY_MODEL` re-runs this validation.

## Addendum 2026-06-12 — `num_ctx` measured and pinned (§10 item 2 closed)

Measured on the RTX 5090 box with a worst-case read image (full-frame 4K capture →
panel crop → 1536 px long edge, the normalize ceiling): the read needs **~2018 prompt
tokens** (text + vision) and **~250 output tokens** — Ollama's 32k default context was
almost pure KV-cache waste. Loaded sizes from `/api/ps` per `num_ctx`:

| `num_ctx` | 8b loaded | 4b loaded |
|---|---|---|
| default (32k) | 9.51 GB | 7.37 GB |
| 16384 | 7.23 GB | — |
| **12288 (pinned)** | **6.66 GB** | **4.51 GB** |
| 8192 | 6.09 GB | — |

**Pinned: `num_ctx = 12288`** (`HttpOllamaClient.NUM_CTX`) — the smallest size that
still holds prompt (~2k) plus the FULL retry output budget (`num_predict` 8192 on a
`length` stop) with margin; 8192 would silently cap the retry. Accuracy verified: the
golden-set sweep with the pin is value-identical to the unpinned sweep (only the known
nondeterministic `(ORE)`/`[ORE]` bracket artefact differs, which the stitcher key
folds). Tier outcome: the 8b now loads at ~6.7 GB (real headroom on 12 GB cards, as §5
hoped) and the 4b at ~4.5 GB (comfortable on the 8 GB minimum tier). Tier thresholds
(12/8 GB cards) stay unchanged.

## Addendum 2026-06-12 — prompt amendment 3 + garble-tolerant stitching: verify 10/10

Follow-up question after round 2: would adapted prompts or a different `num_ctx`
improve the candidates or the verify combination? Findings:

**`num_ctx` is not a lever — for ANY model.** Across all round-2 sweeps the Ollama
server log shows zero input truncation (`truncated = 0` on every read; largest input
~3.0k tokens against the 12288 pin). The only two `truncated = 1` events are runaway
GENERATIONS (6 995 output tokens) from already-disqualified candidates hitting the
retry budget — a symptom of their degenerate answers, not a cause. Raising `num_ctx`
cannot change any candidate's reading; the 12288 pin stands for both pinned models.

**Prompt amendment 3 (PARTIAL is a suffix, never a row).** The 4b's only structural
defect as verify partner was a literal ghost row `| PARTIAL | ? | … |` on A7/A8 — it
misread the edge-cut marker instruction as a row of its own (its 6/12 real rows were
value-identical to the 8b's!). The prompt's last line now spells out "PARTIAL is a
name suffix, NEVER a row of its own" (with an example), and — §4 principle: a prompt
fix is never the only defence — `MarkdownPanelParser` additionally drops any row whose
material name is exactly `PARTIAL` (no real material carries that name). Result: the
4b's row sets align with the 8b's on A7/A8; the 8b's own golden-set reads are
value-identical under the amended prompt (no primary regression).

**Garble-tolerant row identity (stitcher + verify).** A repeat combo sweep surfaced a
run-to-run flake: the 4b once transcribed `LINDINIMUM` for `LINDINIUM` (A1), which
broke the scroll-overlap detection → 5 duplicated rows → verify degraded to "not
comparable" on a previously clean order. Row identity in the stitcher and the verify
name comparison now tolerate a Levenshtein-1 name garble IFF quality AND qty are both
present and exactly equal — duplicate materials always differ in their numbers, so the
numbers disambiguate; exported names stay verbatim (basetool fuzzy-matches names
downstream, §2). This also protects the PRIMARY path against the same flake class.

**Result (live, full golden set + verify, 61 s):** verify coverage is now **10/10
orders comparable** (was 8/10): A2/A10 stay auto-corrected (`VERIFY_CORRECTED`), A1's
silent quality error and A9's silent quality+qty errors are contested row-precisely,
A3–A8 clean, zero false corrections.

**Adapted prompts do NOT rescue the rejected candidates.** `minicpm-v4.5` re-swept
under the amended prompt still reads structurally wrong row sets (A8: 4 of 12 rows;
A9: 8 of 11; extra ghost/duplicate rows on A2/A10) — the row-loss pathology is not
prompt-addressable. The other rejects fail structurally (answer format, speed, digit
noise), not on prompt wording; per-model prompt forks would add maintenance surface
for no measured gain. The single shared prompt and both model pins stand.

## Addendum 2026-06-12 — pipeline review round: new deterministic rules + plumbing

A full pass over the pipeline (architecture, algorithms, prompts) shipped the following;
golden-set sweep after the changes is value-identical to the pre-change state (10/10
verify coverage, A2/A10 corrected, zero new flags):

**`IN MANIFEST` is NOT checksummable — measured negative.** The hope was a second
checksum (Σ of ALL rows) that would arbitrate QTY disagreements on refine-OFF rows
(the A9 case). Measured across the golden set: on a1/a2/a3/a5/a6/a7 the header simply
equals `TO REFINE`; on a4/a8/a9/a10 it diverges with no relation derivable from the
visible rows (a1's visible row sum 37 736 even EXCEEDS its 32 295) — no rule ships,
OFF-row disagreements stay contested-only.

**New deterministic rules (all golden-set re-validated):**
1. *Physics arbitration in the verify merge* — refining removes impurities, so
   `YIELD ≤ QTY` always holds. When the models disagree on ONE of the two cells while
   agreeing on the other and exactly one candidate satisfies the bound, it wins
   (`VERIFY_CORRECTED`); reaches cells the header checksum cannot (OFF rows, missing
   header).
2. *Quoted-read-wins stitching* — the overlap fold now prefers the row variant whose
   READ saw the quoted state (not just "has a numeric yield"): an OFF row shows `--`
   in the quoted capture too, and the un-quoted variant surviving meant the yield-based
   refine correction was blind on exactly those rows in mixed capture sets.
3. *CTA cross-check* — the transcribed bottom button is a redundant read of the quote
   state; `CONFIRM`/`GET QUOTE` contradicting `quoted` flags `CTA_MISMATCH`.

**Plumbing:** the conditional per-call `keep_alive 0` release protocol is gone — every
read pins 10m and the pipeline issues explicit best-effort `unload` calls (primary
before the verify partner loads, the active model at run end), removing the bug-prone
conditional class. The verify pass surfaces as a per-image `VERIFY` stage in the §5.3
track. A silent Locate colour-anchor miss now logs a console warning, and the fixed
fallback geometry scales the panel SIZE by height only (the game renders the panel at
16:9 size on ultrawide). One transport-level retry on transient chat IO failures.
The smoke harness gained an env-gated golden-expected diff
(`PROMPT_SMOKE_EXPECTED`/`PROMPT_SMOKE_WRITE_EXPECTED`) so future prompt/model changes
fail loudly on regressions; the expected file holds private capture data and lives
next to the golden set, never in the repo.

## Addendum 2026-09-05 — the game reskinned the terminal; Locate broke silently

Twelve new sample orders (a4-era corpus grown to 34) turned out to be a **second terminal
skin**. The corpus changes over between the captures of 2026-07-19 and 2026-07-20; the new
one names itself in its own header bar, `// REFINERY SYSTEM C47.02`, so it is called the
**C47 skin** here. Everything §3 pinned about Locate is amber-skin-specific, and all of it
moved.

**The failure mode is the important part: nothing failed.** Locate's contract is "find the
panel, else fall back to fixed 4K geometry", so a wrong crop is indistinguishable from a
right one downstream — the model transcribes what it is shown, the stitcher merges it,
validation checks it against a header total that was cropped away too. Every C47 order
produced a work order, read out of the STATION PROFILE sidebar with the YIELD and REFINE
columns off the right edge. Only looking at the *crops* (`PanelDumpTest`) showed it.

**Three anchors, three independent breakages:**

1. *CONFIRM is green*, ~RGB(154,182,59) on the bright captures and ~RGB(117,126,54) on the
   dim ones — under `isCtaOrange`'s `r >= 170` floor, so the CTA anchor matched nothing.
   `isCtaGreen` spans both exposures; kept green-dominant so it can never take the amber fill.
2. *The tab strip is a diagonal hatch* over the panel's top-left ~0.37, not a solid bar across
   the header. It was invisible to the detector but not to the eye: the 1/4-scale downscale was
   AWT **bicubic**, which point-samples on a 4× reduction and hit the bright stripes and the dark
   gaps alternately, matching neither. Measured on the hatch block: **25 %** of the 1/4-scale
   pixels passing `isMaroon` under bicubic, **83 %** under a box filter (the mean of each 4×4
   block — the correct filter for a downscale, and the one the strip's *area mean* is maroon
   under). A solid strip averages to itself, so the amber captures are untouched.
3. *Captures now include the terminal's system banner*, a wide dark-red bar at the very top of
   the frame. It clusters like a tab strip and, being leftmost, wins the newest-order rule.
   Guarded by position: a maroon run inside the top **6 %** of the capture height is the banner
   (measured 0–3.2 % across the corpus; the lowest real tab strip sits at 7.8 %).

**What sampling correctly then surfaced** — the box filter also makes the *world* legible to the
anchors, and that needed answering:

- On a 32:9 frame a rock face under the refinery work lights matches maroon and its highlights
  match the CTA, and it sits LEFT of the real panel. Split by **mean per-pixel saturation**,
  ceiling 35: 10–17 across every real work-order panel, 28 on the warmest ambient-lit one, 51 on
  the rock. Luminance cannot separate them — §3's "the interior is uniformly dark" is true of the
  hangar too.
- The strip is only a panel-width proxy in the amber skin. The CTA search window goes to **2.9**
  strip widths (was 1.9), and the panel's right edge is now the **widest** CTA run between strip
  and CTA row, not the lowest row's: a button's fill breaks into separate runs at its border rows,
  and the lowest row alone stopped ~40 px short — exactly enough to cut REFINE off. Both widenings
  are held back on ultrawide frames, where the narrow window is what §3 verified and the
  terminal-extent rescue already covers the failure.
- One capture ends just above CANCEL/CONFIRM, so no CTA exists to confirm with. A **CTA-less
  rescue** takes the panel from the strip to the capture's bottom edge — only when no candidate
  was confirmed at all, only for a strip in the top third, still subject to the saturation guard.
  Fixed geometry is a far worse guess than the strip we did find.

**The sweep is deterministic — measured, against the standing assumption.** Three consecutive
full sweeps (34 orders, 52 images, `qwen3-vl:8b-instruct`, no verify partner) came back
**byte-identical**, so the diffs against the expected file are code behaviour, not run noise, and
were resolved cell by cell against the pixels of the captures rather than by re-blessing a run.

**The C47 REFINE toggle reads as ON on every row, and the prompt cannot fix it.** The restyle
turned the toggle from a square amber block into a rounded pill with a coloured knob (orange
right = ON, red left = OFF). An amended prompt naming both switch styles, both cues and the fact
that rows are usually mixed changed **nothing** — same all-ON answer on the same panels — so it
was not shipped: it is a visual limitation of this encoder, not a missing instruction. The
yield-based `REFINE_CORRECTED` repair absorbs it and the exported rows are right, at the cost of
that warning on nearly every C47 order and confidence 0.85 on its OFF rows.

**Model bake-off round 3 — the 8b pin stands.** Of everything that appeared in the Ollama vision
library since round 2, only `ornith-1.5:9b` (6.6 GB) fits the recommended tier at all: `qwen3.6`
(27B/35B), `qwen3.8` (27B), `muse-glimmer` (30B) and `ornith-1.5:35b` need 18–23 GB, and
`glm-5.3-flash` is a `:cloud` model, which this project may not use at any accuracy. Ornith reads
the C47 toggles **correctly** — the one cell class the pin gets wrong — but it is a *thinking*
model and pays for it. Same panel, same prompt, idle RTX 5090: **149 s/image against the pin's
7.2 s**, a factor of 21. With `think: false` it gets faster but **loses the toggle advantage**,
which was the whole reason to look at it. The tier budget is ~5 s on 12 GB; no change.

**The expected file now covers the whole corpus — 34 orders, 264 rows** (was 17), generated with
the config it has always been generated with: primary `qwen3-vl:8b-instruct`, verify partner
`qwen3-vl:4b-instruct`, bundled OCR. Five cells moved against the previous file, and each was
settled against the pixels of the capture rather than against a run:

| Order | Cell | Old → new | Verdict |
| --- | --- | --- | --- |
| a2 | BEXALITE 875, QTY | 494 → **404** | new is right; the old file recorded the misread §6/the verify addendum describe |
| a7 | INERT MATERIALS, QTY | 1484 → **1404** | new is right (a7 is pre-cropped — Locate never touched it, so this cell had been stale) |
| a10 | GOLD 553/559, QUALITY | 553 → 559 | **the new value is wrong**; the expected file now records a misread |
| a14 | LARANITE, QUALITY | 510 → 518 | **the new value is wrong**; likewise |
| a11 | CONSTRUCTION RUBBLE | `RUCTION…` → `'RUCTION…` | cosmetic garble of a name the crop clips; fuzzy matching absorbs either |

The two wrong ones are not a degradation of the read — they are the crop box moving a few pixels
(the box filter shifts it, which changes the upscale factor), landing the other way on the
1–2 pixel glyph damage §2.1 already established is **not invertible**. Neither the 4b verify partner
nor the OCR cross-reader catches them: all three readers agree on the wrong digit. They are recorded
here so nobody mistakes the expected file for ground truth. Against that, all sixteen C47 orders
went from unreadable to correct, a2 and a7 became correct, and every C47 header total and station
name was verified against the captures by hand.

**Cross-model verify is close to a no-op on this corpus now.** Over all 34 orders it changes
exactly one cell against the primary-only run: a18's LARANITE quality, whose misread had split one
physical row into two across five scrolled captures. That is worth having — it is a stitching
failure, not just a digit — but the round-2 claim that verify auto-corrects the open digit misreads
no longer has anything left to correct here.
