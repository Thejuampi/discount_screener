---
title: Android Quant Lens Epics And Stories
date: 2026-05-03
phase: 3 Plan Repair
status: repaired draft
owner: BMAD story-planning worker
artifact_type: epics_stories
source_prd: _bmad-output/planning-artifacts/android-quant-lens-prd-2026-05-03.md
source_ux: _bmad-output/planning-artifacts/android-quant-lens-ux-spec-2026-05-03.md
source_architecture: _bmad-output/planning-artifacts/android-quant-lens-architecture-2026-05-03.md
source_context:
  - _bmad-output/project-context.md
  - apps/android/README.md
selected_use_cases:
  - Evidence Strength
  - Expected Value Range
  - Correlation Risk
  - Trend Reliability
  - Similar Setups
---

## Overview

## Planning Inputs Read

- `_bmad-output/planning-artifacts/android-quant-lens-prd-2026-05-03.md`
- `_bmad-output/planning-artifacts/android-quant-lens-ux-spec-2026-05-03.md`
- `_bmad-output/planning-artifacts/android-quant-lens-architecture-2026-05-03.md`
- `_bmad-output/project-context.md`
- `apps/android/README.md`
- BMAD create-epics-and-stories and sprint-planning workflow guidance

## Implementation Guardrails

- Keep Quant Lens business rules in `apps/android/core` pure Kotlin model and engine code.
- Keep Android `app/data` as the imperative shell for loading, cache ownership, selected-detail orchestration, and snapshot assembly.
- Keep `DashboardViewModel` as Presenter: map domain reports into display state and route actions only.
- Keep Compose passive: render supplied labels, chips, sections, and actions only.
- Do not persist Quant Lens reports in SQLite for MVP. Reports are derived from canonical inputs.
- Do not fetch or backfill full history for every symbol at startup. Full history remains per-symbol and demand-driven.
- Do not add trading advice language or calibrated outcome claims. Use evidence, scenario, local-history, fit-quality, and current-setup language.
- Preserve fixed-point public outputs: `*_cents`, `*_bps`, `*_hundredths`, and `*_millis` where financial or probability-like values are exposed.
- Use strict TDD for implementation stories. Add failing core tests first for business rules, then repository/presenter/UI tests for app-layer behavior.
- Later implementation validation should use `scripts/validate-android.ps1`, but no validation commands are part of this planning artifact creation.

## Shared Story Notes

### Canonical Degradation Precedence And UI Labels

Core must keep primary availability, lens-specific band, freshness/source qualifiers, and reason codes separate. When multiple degraded states apply, stories use this canonical precedence for `primaryStatus`: `Unavailable` > `Sparse` or `Insufficient` > `Stale` > `Provisional` or `Partial` > `Available`. If sparse or insufficient inputs and stale/restored inputs both apply, `Sparse` or `Insufficient` wins primary status and stale is emitted only as `freshnessQualifier` or a secondary freshness chip. Evidence `Mixed` is a lens band, not a degradation status; if evidence is both mixed and sparse, the primary status is `Sparse` and the conflict remains a structured reason code.

Presenter-owned UI label mapping is canonical for every story:

| Lens | Available labels | Sparse/insufficient label | Stale label | Unavailable label |
| --- | --- | --- | --- | --- |
| Evidence Strength | `Evidence strong`, `Evidence provisional`, `Evidence mixed` | `Evidence sparse` | `Evidence stale` | `Evidence unavailable` |
| Expected Value Range | `EV -4..+12%`, `EV +8..+24%`, `EV mixed` | `EV sparse` | `EV stale` | `EV unavailable` |
| Correlation Risk | `Corr low`, `Corr elevated`, `Corr high` | `Corr sparse` | `Corr stale` | `Corr unavailable` |
| Trend Reliability | `Trend reliable`, `Trend moderate`, `Trend noisy`, `Trend flat` | `Trend sparse` | `Trend stale` | `Trend unavailable` |
| Similar Setups | `Similar 3`, `Similar close`, `Similar near` | `Similar sparse` | `Similar stale` | `Similar unavailable` |

All story-visible limited-input labels and tests use `sparse` unless a core field is explicitly named `Insufficient` for sample math.

Correlation Risk means local close-to-close return correlation only. Provider/source redundancy, analyst concentration, source-family overlap, DCF/fundamental dependency, sector membership, and portfolio diversification claims belong outside Correlation Risk. Source concentration can be an Evidence Strength reason only.

Expected Value Range emits scenario-weighted value, weighted upside, range width, spread, and range rail only when one selected source has three positive anchors. DCF bear/base/bull is primary. Analyst low/primary/high is fallback. One or two positive anchors are reference-only sparse context.

Trend Reliability must fit least-squares slope and R-squared in core from loaded sample series: selected `HistoricalCandle` close values, collapsed analyst target observations from `SymbolRevision`, and saved `IndexEstimatesReport` scenario histories. `ChartRangeSummary`, EMA, MACD, or any summary-only input is insufficient for a fit.

Similar Setups compares current-universe nearest neighbors only using canonical feature weights `3/3/2/1/1` for valuation upside, evidence ordinal, opportunity score/buckets, trend ordinal/slope, and EV spread. It returns exactly the top 3 matches when at least three qualifying comparable symbols, excluding the target, have at least three shared features. Exactly two qualifying comparables is `Sparse` with qualifying count 2 and no top-3 list. It never uses historical analog dates, follow-through returns, median outcomes, future windows, or outcome cohort summaries.

### Per-Story Definition Of Done

Every implementation story below is incomplete until its implementation notes record this story-level Definition of Done:

- Strict TDD was followed: failing test first, smallest implementation to green, then refactor while tests stay green.
- Tests stay focused on one behavior. Use one hard assertion per test when practical; use SoftAssertions only when a compound report requires checking multiple related fields together.
- Targeted tests for the changed layer passed before broad validation: core tests for rules, repository tests for boundaries/cache, ViewModel tests for mapping/navigation, and Compose tests for rendering/actions.
- `scripts/validate-android.ps1` was run from the repo root, or the story notes state the SDK fallback/blocker clearly and include which script phases could not run.
- Visible UI changes received live QA on the installed app when an Android device/emulator and SDK are available; otherwise the story notes document the live-QA gap.
- Mutation testing was run around changed behavior when practical, or the story notes document the manual mutation performed or why mutation testing was not practical.

## File Ownership Map

- Core model and rules: `apps/android/core/src/main/kotlin/com/discountscreener/core/model/QuantLensModels.kt`, `apps/android/core/src/main/kotlin/com/discountscreener/core/engine/QuantLensEngine.kt`, and focused core tests under `apps/android/core/src/test/kotlin/com/discountscreener/core`.
- Existing core inputs: `apps/android/core/src/main/kotlin/com/discountscreener/core/model/Models.kt`, `OpportunityEngine.kt`, `ChartAnalysis.kt`, `DcfAnalysisEngine.kt`, and `IndexEstimatesEngine.kt` are reference inputs, not UI homes.
- App domain snapshot: `apps/android/app/src/main/kotlin/com/discountscreener/android/domain/model/DashboardSnapshot.kt` carries selected-symbol derived output and any row display summary contracts.
- Repository orchestration: `apps/android/app/src/main/kotlin/com/discountscreener/android/data/repository/DefaultDashboardRepository.kt` builds inputs, owns in-memory cache/fingerprint, and must not trigger non-selected fetches for Quant Lens.
- Presenter mapping: `apps/android/app/src/main/kotlin/com/discountscreener/android/presentation/dashboard/DashboardViewModel.kt` plus optional new presentation UI model files map labels and route actions.
- Passive Compose: `DashboardLists.kt`, `DetailScreen.kt`, `EstimatesScreen.kt`, and optional `QuantLensContent.kt` render provided state without thresholds.
- Tests: core tests own pure behavior; repository tests own startup/detail/cache/fetch boundaries; ViewModel tests own mapping/navigation; Compose tests own rendering/actions only.

## Implementation Order

1. Story 1.1: Quant Lens Core Contract And Sparse Report Scaffold
2. Story 1.2: Selected Detail Input Boundary And In-Memory Report Cache
3. Story 1.3: Presenter Display Contract And Passive UI Scaffolding
4. Story 1.4: Bounded Row Summary Contract And Label Precedence
5. Story 2.1: Evidence Strength Core Analyzer
6. Story 2.2: Evidence Strength Presenter Mapping And Detail Section
7. Story 2.3: Expected Value Range Core Analyzer
8. Story 2.4: Expected Value Range Presenter Mapping And Range Rail
9. Story 3.1: Correlation Risk Core Analyzer
10. Story 3.2: Local History Correlation Integration Without Fan-Out
11. Story 3.3: Trend Reliability Core Analyzer
12. Story 3.4: Trend Reliability On Detail History And Estimates
13. Story 4.1: Similar Setups Core Analyzer
14. Story 4.2: Comparable Universe Assembly Without Fetching
15. Story 4.3: Similar Setups Detail Rows And Symbol Navigation
16. Story 5.1: Dashboard Quant Lens Strip Across Opps Upside And Watch
17. Story 5.2: Lens Tab Navigation Snapshot Mini Strip And Section Focus
18. Story 5.3: Anti-Overclaiming Copy Performance And Regression Hardening

## Epic 1: Quant Lens Contract And Selected-Detail Foundation

Goal: Establish a pure core report contract, selected-detail app boundary, bounded row-summary contract, and passive display scaffolding without implementing the full use-case math yet. This epic lets later stories add one lens at a time while preserving startup performance and MVP boundaries.

### Story 1.1: Quant Lens Core Contract And Sparse Report Scaffold

Status: Ready for dev

Use cases covered: Evidence Strength, Expected Value Range, Correlation Risk, Trend Reliability, Similar Setups as explicit placeholder sections.

Dependencies: None.

Likely files:

- `apps/android/core/src/main/kotlin/com/discountscreener/core/model/QuantLensModels.kt`
- `apps/android/core/src/main/kotlin/com/discountscreener/core/engine/QuantLensEngine.kt`
- `apps/android/core/src/test/kotlin/com/discountscreener/core/model/QuantLensModelTest.kt`
- `apps/android/core/src/test/kotlin/com/discountscreener/core/engine/QuantLensEngineTest.kt`

File ownership boundaries:

- Core owns statuses, labels as domain bands, fixed-point output fields, invariants, and pure analysis inputs.
- Do not add Android imports, coroutines, clocks, repository calls, or UI text formatting to core.
- Do not touch repository, presenter, or Compose files in this story.

Acceptance criteria:

- AQL-1.1-AC1: Given a minimal `QuantLensInput` with a selected `SymbolDetail`, when `QuantLensEngine.analyze` runs, then it returns a deterministic `QuantLensReport` containing all five lens sections.
- AQL-1.1-AC2: Given required inputs are absent for a section, when the report is built, then that section is explicit `Unavailable`, `Sparse`, or `Insufficient` rather than omitted or zero-filled.
- AQL-1.1-AC3: Given two identical inputs, when analysis runs twice, then report status, model version, section statuses, and reasons are identical except for an explicitly supplied computed timestamp.
- AQL-1.1-AC4: Given any public money, range, score, reliability, correlation, or distance output, then exposed fields use fixed-point integer naming and not raw floating-point public properties.
- AQL-1.1-AC5: Given a report is serializable, then model invariants reject blank symbols, invalid ranges, empty required reason lists for available states, and out-of-bounds bps fields.

Tasks:

- AQL-1.1-T1: Add Quant Lens status enums and section model types for evidence strength, expected value range, correlation risk, trend reliability, and similar setups.
- AQL-1.1-T2: Add `QuantLensInput`, `QuantLensReport`, `QuantLensComparable`, and `SimilarSetupMatch` or equivalent model names.
- AQL-1.1-T3: Add `QuantLensEngine.analyze(input)` as a pure function returning scaffolded sparse/unavailable sections.
- AQL-1.1-T4: Encode model version and supplied `computedAtEpochSeconds` in the report.
- AQL-1.1-T5: Add model invariant tests before implementation.

Tests:

- Core model tests for invariants and fixed-point bounds.
- Core engine tests for all-five-section scaffold, deterministic output, and sparse/unavailable defaults.

### Story 1.2: Selected Detail Input Boundary And In-Memory Report Cache

Status: Planned

Use cases covered: All five use cases at the selected-detail orchestration boundary.

Dependencies: Story 1.1.

Likely files:

- `apps/android/app/src/main/kotlin/com/discountscreener/android/domain/model/DashboardSnapshot.kt`
- `apps/android/app/src/main/kotlin/com/discountscreener/android/data/repository/DefaultDashboardRepository.kt`
- `apps/android/app/src/test/kotlin/com/discountscreener/android/data/repository/DefaultDashboardRepositoryTest.kt`

File ownership boundaries:

- Repository may gather canonical selected-detail inputs and call core.
- Repository may keep a small in-memory cache keyed by selected-symbol fingerprint.
- Repository must not define thresholds, labels, or lens business semantics.
- Do not change SQLite schema or persist `QuantLensReport`.
- Do not add list-wide startup computation.

Acceptance criteria:

- AQL-1.2-AC1: Given the dashboard starts without a selected symbol, when a snapshot is produced, then no selected Quant Lens report is computed or exposed.
- AQL-1.2-AC2: Given a selected symbol detail is loaded, when `snapshotLocked` builds the selected snapshot, then it attaches a selected-symbol `QuantLensReport` built from in-memory canonical inputs.
- AQL-1.2-AC3: Given the selected symbol has unchanged fingerprint inputs, when consecutive snapshots are built, then the repository reuses the in-memory cached report.
- AQL-1.2-AC4: Given any selected-symbol fingerprint component changes, when a snapshot is built, then the cached report is not reused and the report recomputes.
- AQL-1.2-AC5: Given profile switch, clear data, or reset flows run, then any Quant Lens cache is cleared with other in-memory state.
- AQL-1.2-AC6: Given similar/correlation inputs are missing for non-selected symbols, then the repository does not fetch Yahoo, DCF, SQLite history, or chart history solely for Quant Lens.
- AQL-1.2-AC7: Given the selected range, selected candle count/latest epoch/latest close/ordered close hash, revision target hash, estimate-history hash, DCF source or bear/base/bull values, comparable-universe fingerprint including actual comparable feature values, correlation-series fingerprint, scoring version, or Quant Lens model version changes, then the fingerprint changes.
- AQL-1.2-AC8: Given the selected symbol, profile, or current universe changes, then the cache cannot reuse a report from the prior symbol/profile/universe even if other numeric inputs are equal.

Tasks:

- AQL-1.2-T1: Extend `DashboardSnapshot` with selected-symbol Quant Lens output, defaulting to null.
- AQL-1.2-T2: Build `QuantLensInput` from `selectedDetail`, `selectedCharts`, `selectedHistory`, selected DCF cache, chart summaries, and bounded comparables already in memory.
- AQL-1.2-T3: Add fingerprint data class or equivalent private cache key.
- AQL-1.2-T4: Add repository tests using fakes that count provider and persistence calls.
- AQL-1.2-T5: Add cache clear points for profile switch/reset without adding persistence.
- AQL-1.2-T6: Add focused fingerprint tests that mutate one fingerprint component at a time, including one actual comparable feature value, and prove recomputation occurs.

Tests:

- Repository tests for no startup report, selected-detail report, cache reuse, single-component cache invalidation including comparable feature-value changes, profile/symbol/universe isolation, profile switch clear, and no comparable fetch fan-out.

### Story 1.3: Presenter Display Contract And Passive UI Scaffolding

Status: Planned

Use cases covered: All five use cases as display-state placeholders.

Dependencies: Stories 1.1 and 1.2.

Likely files:

- `apps/android/app/src/main/kotlin/com/discountscreener/android/presentation/dashboard/DashboardViewModel.kt`
- `apps/android/app/src/main/kotlin/com/discountscreener/android/presentation/dashboard/QuantLensUiModels.kt`
- `apps/android/app/src/main/kotlin/com/discountscreener/android/ui/dashboard/DetailScreen.kt`
- `apps/android/app/src/main/kotlin/com/discountscreener/android/ui/dashboard/QuantLensContent.kt`
- `apps/android/app/src/test/kotlin/com/discountscreener/android/presentation/dashboard/DashboardViewModelTest.kt`
- `apps/android/app/src/test/kotlin/com/discountscreener/android/ui/dashboard/DetailScreenTest.kt`

File ownership boundaries:

- Presenter maps core statuses to UI labels, severity tokens, order, freshness text, and display rows.
- Compose renders `QuantLensUiState` and emits actions only.
- Do not compute thresholds or inspect raw candles in ViewModel or Compose.

Acceptance criteria:

- AQL-1.3-AC1: Given `DashboardSnapshot.selectedQuantLens` is null, when mapped to UI state, then `detailQuantLens` is null or loading/unavailable according to route state.
- AQL-1.3-AC2: Given a scaffolded report with five sparse/unavailable sections, when mapped, then the UI state contains five ordered section display models.
- AQL-1.3-AC3: Given the detail route is active, when the detail screen renders, then a passive Quant Lens placeholder can render without crashing and without formulas or advice language.
- AQL-1.3-AC4: Given Compose receives labels from presenter, then no Compose function derives Quant Lens labels from raw domain inputs.

Tasks:

- AQL-1.3-T1: Add `QuantLensUiState`, section UI models, chip UI models, and severity token enum or equivalent presentation contract.
- AQL-1.3-T2: Map selected report into display state in `DashboardViewModel`.
- AQL-1.3-T3: Add a minimal passive `QuantLensContent` placeholder that accepts display state.
- AQL-1.3-T4: Add ViewModel and UI tests for five-section sparse/unavailable rendering.

Tests:

- ViewModel mapping tests for null report, scaffold report, and route state.
- Compose/detail test for placeholder rendering and no crash.

### Story 1.4: Bounded Row Summary Contract And Label Precedence

Status: Planned

Use cases covered: Evidence Strength, Expected Value Range, Correlation Risk, Trend Reliability, Similar Setups as cheap row-level summaries.

Dependencies: Stories 1.1, 1.2, and 1.3.

Likely files:

- `apps/android/core/src/main/kotlin/com/discountscreener/core/model/QuantLensModels.kt`
- `apps/android/app/src/main/kotlin/com/discountscreener/android/domain/model/DashboardSnapshot.kt`
- `apps/android/app/src/main/kotlin/com/discountscreener/android/presentation/dashboard/QuantLensUiModels.kt`
- `apps/android/app/src/main/kotlin/com/discountscreener/android/presentation/dashboard/DashboardViewModel.kt`
- `apps/android/app/src/test/kotlin/com/discountscreener/android/data/repository/DefaultDashboardRepositoryTest.kt`
- `apps/android/app/src/test/kotlin/com/discountscreener/android/presentation/dashboard/DashboardViewModelTest.kt`

File ownership boundaries:

- Row summaries are bounded display hints, not full selected-detail reports.
- Core/app-domain row summary models expose exactly these top-level fields: `symbol`, `fingerprint`, and `lensStates`.
- Each row-summary lens state exposes exactly these structured semantic fields: `lensId`, `primaryStatus`, `band`, `label`, `freshnessQualifier`, and `reasonCodes`. EV row states may expose `evLowUpsideBps` and `evHighUpsideBps` only when the three-positive-anchor rule makes EV available.
- Row summaries must not carry raw candles, revisions, estimate histories, DCF payloads, provider clients, persistence handles, or full `QuantLensReport` objects.
- Presenter maps row summary fields to chip severity tokens, ordering, compact labels, and width caps from structured fields only. It must not infer primary status, band, label, or freshness from freeform reason-code text.
- Compose renders presenter-supplied chips only and never triggers recomputation.

Acceptance criteria:

- AQL-1.4-AC1: Given a row summary model is created, then it exposes only `symbol`, `fingerprint`, and `lensStates` at the top level; each lens state exposes only `lensId`, `primaryStatus`, `band`, `label`, `freshnessQualifier`, `reasonCodes`, plus EV upside bounds only when EV is available; and no history, DCF payload, provider, storage, or full-report fields are exposed.
- AQL-1.4-AC2: Given a row lacks a summary, when it is mapped for list display, then the presenter emits one `Lens loading` chip rather than blank space.
- AQL-1.4-AC3: Given a row summary exists, when chips are selected, then they follow priority order: Evidence, eligible EV, Elevated/High Corr, available Trend, available Similar.
- AQL-1.4-AC4: Given available width is at least 360 dp, then the visible row summary never exceeds three Quant Lens chips; given width is below 360 dp, it never exceeds two chips.
- AQL-1.4-AC5: Given more lens states exist than the visible cap allows, then lower-priority row chips are hidden from the row but their full states remain available in detail.
- AQL-1.4-AC6: Given EV has fewer than three positive anchors, then the row summary maps EV to `EV sparse` and does not emit `evLowUpsideBps`, `evHighUpsideBps`, weighted upside, or a row range label.
- AQL-1.4-AC7: Given Correlation Risk is not backed by an already-computed local close-to-close return-correlation result for the same fingerprint, then the row summary maps it to sparse/unavailable and never substitutes provider/source similarity.
- AQL-1.4-AC8: Given row-summary construction runs on cold start, warm start, or list refresh, then provider calls, DCF computation, SQLite history reads, chart-history loads, timeseries loads, revision loads, and comparable fan-out call counters remain unchanged.
- AQL-1.4-AC9: Given any row-summary lens has multiple degraded conditions, then `primaryStatus` follows `Unavailable` > `Sparse` or `Insufficient` > `Stale` > `Provisional` or `Partial` > `Available`, with stale/restored data retained only in `freshnessQualifier` when sparse or insufficient wins.
- AQL-1.4-AC10: Given presenter row-chip mapping receives reason codes that conflict with structured fields, then the chip uses `primaryStatus`, `band`, `label`, and `freshnessQualifier` directly and does not infer display state from reason-code text.

Tasks:

- AQL-1.4-T1: Add the bounded row-summary model contract with the exact top-level and per-lens fields listed above.
- AQL-1.4-T2: Add presenter row-chip mapping that applies canonical status precedence and consumes structured `primaryStatus`, `band`, `label`, and `freshnessQualifier` fields without deriving state from reason-code text.
- AQL-1.4-T3: Add chip cap logic for width below 360 dp and at or above 360 dp.
- AQL-1.4-T4: Add tests proving row-summary mapping is cheap and does not trigger provider, DCF, storage, chart, revision, or comparable fan-out.
- AQL-1.4-T5: Add tests for missing summary, chip order, EV three-anchor gating, Correlation local-return gating, degraded precedence, and narrow-width caps.
- AQL-1.4-T6: Add tests proving conflicting/freeform reason codes cannot change row chip status, label, band, or freshness when structured lens fields are present.

Tests:

- Model/presentation tests for exact row fields, structured per-lens state fields, status precedence, label mapping without reason-code inference, chip order, and chip caps.
- Repository/presenter tests with fake call counters proving row-summary construction does not trigger hidden work.

## Epic 2: Evidence Strength And Expected Value Range

Goal: Implement the two valuation/evidence lenses first because they rely mostly on already-present selected detail, DCF, analyst, scoring, and freshness inputs. These slices provide early user value without local-history fan-out.

### Story 2.1: Evidence Strength Core Analyzer

Status: Planned

Use cases covered: Evidence Strength.

Dependencies: Story 1.1.

Likely files:

- `apps/android/core/src/main/kotlin/com/discountscreener/core/engine/QuantLensEngine.kt`
- `apps/android/core/src/main/kotlin/com/discountscreener/core/model/QuantLensModels.kt`
- `apps/android/core/src/test/kotlin/com/discountscreener/core/engine/QuantLensEvidenceStrengthTest.kt`

File ownership boundaries:

- Core owns evidence sufficiency, freshness downgrade, conflict detection, and source-family collapse.
- App and UI may supply inputs only; they must not duplicate evidence thresholds.
- Existing `OpportunityEngine` scoring patterns may be referenced but should not be mutated unless required by tests.

Acceptance criteria:

- AQL-2.1-AC1: Given no market price or no usable intrinsic/fair value, when analysis runs, then evidence strength is `Unavailable` and no stronger label is emitted.
- AQL-2.1-AC2: Given base market/intrinsic evidence only, when analysis runs, then evidence strength is `Sparse` with sufficiency reasons.
- AQL-2.1-AC3: Given external signal age exceeds max age or restored/stale provenance applies, when analysis runs, then evidence is `Stale` only after `Unavailable` and `Sparse` or `Insufficient` have been ruled out; absent base evidence remains `Unavailable`, and sparse or insufficient evidence remains `Sparse` or `Insufficient` with stale only in `freshnessQualifier`.
- AQL-2.1-AC4: Given DCF opportunity conflicts materially with analyst, forecast, or technical evidence, when analysis runs, then evidence is `Mixed`, not `Strong`.
- AQL-2.1-AC5: Given fresh coverage count 3, sufficient analyst breadth where used, usable DCF when included, and no material disagreement, when analysis runs, then evidence is `Strong`.
- AQL-2.1-AC6: Given coverage count 2 or analyst coverage below high-confidence threshold with no conflicts, when analysis runs, then evidence is `Provisional`.
- AQL-2.1-AC7: Given identical inputs, then support/conflict/neutral counts and reasons are deterministic.

Tasks:

- AQL-2.1-T1: Add evidence support band/status values and factor rows.
- AQL-2.1-T2: Implement required base evidence gating.
- AQL-2.1-T3: Implement freshness downgrade using `externalSignalAgeSeconds`, `externalSignalMaxAgeSeconds`, and provenance/restored inputs supplied by app.
- AQL-2.1-T4: Implement material agreement/conflict rules across valuation, analyst, DCF, fundamentals, technical, and forecast buckets.
- AQL-2.1-T5: Add focused tests, one behavior per test; use soft assertions only when a compound report requires multiple field checks.

Tests:

- Core tests for unavailable, sparse, sparse-over-stale precedence, stale-after-sufficiency, mixed, provisional, strong, and deterministic reasons.

### Story 2.2: Evidence Strength Presenter Mapping And Detail Section

Status: Planned

Use cases covered: Evidence Strength.

Dependencies: Stories 1.3 and 2.1.

Likely files:

- `apps/android/app/src/main/kotlin/com/discountscreener/android/presentation/dashboard/DashboardViewModel.kt`
- `apps/android/app/src/main/kotlin/com/discountscreener/android/presentation/dashboard/QuantLensUiModels.kt`
- `apps/android/app/src/main/kotlin/com/discountscreener/android/ui/dashboard/QuantLensContent.kt`
- `apps/android/app/src/test/kotlin/com/discountscreener/android/presentation/dashboard/DashboardViewModelTest.kt`
- `apps/android/app/src/test/kotlin/com/discountscreener/android/ui/dashboard/DetailScreenTest.kt`

File ownership boundaries:

- Presenter chooses display labels such as `Evidence strong`, `Evidence mixed`, `Evidence sparse`, and source/freshness chips.
- Compose renders evidence rows and chips only.
- Do not expose posterior, probability, win-rate, buy/sell, or outcome language.

Acceptance criteria:

- AQL-2.2-AC1: Given a strong evidence report, when detail Lens renders, then the evidence section shows `Evidence strength`, a primary `Evidence strong` chip, and support/conflict/neutral counts.
- AQL-2.2-AC2: Given sparse, stale, mixed, provisional, and unavailable evidence reports, when mapped, then each has distinct visible label and body state.
- AQL-2.2-AC3: Given factor rows are present, then the detail section shows valuation, analyst revision, trend, correlation, and similar-setup rows only from presenter-supplied state.
- AQL-2.2-AC4: Given prohibited words are absent from presenter labels, then UI tests do not find `probability of success`, `win probability`, `expected to beat`, or `Bayesian probability`.

Tasks:

- AQL-2.2-T1: Add evidence-specific UI mapping from core report to display section.
- AQL-2.2-T2: Add passive evidence section rendering.
- AQL-2.2-T3: Add tests for all evidence states and prohibited copy.

Tests:

- ViewModel tests for evidence label mapping.
- Compose tests for evidence section states and copy guard.

### Story 2.3: Expected Value Range Core Analyzer

Status: Planned

Use cases covered: Expected Value Range.

Dependencies: Story 1.1.

Likely files:

- `apps/android/core/src/main/kotlin/com/discountscreener/core/engine/QuantLensEngine.kt`
- `apps/android/core/src/test/kotlin/com/discountscreener/core/engine/QuantLensExpectedValueRangeTest.kt`

File ownership boundaries:

- Core owns source selection, 25/50/25 weights, range math, spread math, fixed-point rounding, and sparse/unavailable status.
- App may pass DCF and analyst anchors only.
- UI must not blend sources or calculate scenario values.

Acceptance criteria:

- AQL-2.3-AC1: Given usable DCF bear/base/bull values and positive market price, when EV range is computed, then DCF is selected and output includes weighted value, weighted upside bps, low value, high value, and spread bps.
- AQL-2.3-AC2: Given no usable DCF but analyst low, primary, and high anchors are positive, then analyst anchors are selected with 25/50/25 weights.
- AQL-2.3-AC3: Given both DCF and analyst sets are available, then DCF is primary and analyst anchors are optional references, not silently blended.
- AQL-2.3-AC4: Given fewer than three positive scenario anchors, then status is `Sparse`, anchors are reference-only, and no weighted value, weighted upside, range width, spread, or full range output is emitted.
- AQL-2.3-AC5: Given market price is zero or negative, then status is `Unavailable` and no upside bps is shown.
- AQL-2.3-AC6: Given repeated identical scenario values, then weighted value and spread rounding are deterministic to cent/bps precision.
- AQL-2.3-AC7: Given one or two positive DCF or analyst anchors exist, then the core may return those anchors as reference rows, but it must not synthesize missing anchors or silently blend DCF and analyst sources.

Tasks:

- AQL-2.3-T1: Add EV source enum for DCF and analyst anchors.
- AQL-2.3-T2: Implement DCF primary selection and analyst fallback selection.
- AQL-2.3-T3: Implement fixed 25/50/25 weighted value, upside bps, low/high bounds, and spread.
- AQL-2.3-T4: Add sparse/unavailable reason outputs.
- AQL-2.3-T5: Add reference-only anchor outputs for one/two-anchor sparse cases without weighted calculations.
- AQL-2.3-T6: Add deterministic rounding tests.

Tests:

- Core tests for DCF, analyst fallback, DCF-over-analyst priority, one/two-anchor reference-only sparse cases, invalid market price, no source blending, and rounding.

### Story 2.4: Expected Value Range Presenter Mapping And Range Rail

Status: Planned

Use cases covered: Expected Value Range.

Dependencies: Stories 1.3 and 2.3.

Likely files:

- `apps/android/app/src/main/kotlin/com/discountscreener/android/presentation/dashboard/DashboardViewModel.kt`
- `apps/android/app/src/main/kotlin/com/discountscreener/android/ui/dashboard/QuantLensContent.kt`
- `apps/android/app/src/test/kotlin/com/discountscreener/android/presentation/dashboard/DashboardViewModelTest.kt`
- `apps/android/app/src/test/kotlin/com/discountscreener/android/ui/dashboard/DetailScreenTest.kt`

File ownership boundaries:

- Presenter formats compact labels such as `EV -4..+12%`, `EV mixed`, `EV sparse`, and source chips.
- Compose renders a stable-height range rail from supplied percentages/markers.
- Compose does not calculate scenario weights, anchors, or upside.

Acceptance criteria:

- AQL-2.4-AC1: Given available EV range, detail Lens shows source, low/base/high or bear/base/bull markers, weighted upside, and range/spread label.
- AQL-2.4-AC2: Given EV range crosses zero, compact label shows a mixed/cross-zero state without implying guaranteed return.
- AQL-2.4-AC3: Given sparse EV range with one or two positive anchors, UI shows known anchors as reference-only context and does not draw a range rail, weighted value, weighted upside, range width, spread, or row EV range.
- AQL-2.4-AC4: Given unavailable EV range, UI shows `EV unavailable` and `No current value range` or equivalent direct empty state.
- AQL-2.4-AC5: Given rendered copy, then UI does not display scenario probability percentages, formulas, `expected profit`, `probability of gain`, or `guaranteed return`.

Tasks:

- AQL-2.4-T1: Add EV display mapping and compact/list labels.
- AQL-2.4-T2: Add passive range rail component with stable height between 112 dp and 132 dp.
- AQL-2.4-T3: Add tests for available, cross-zero, sparse, unavailable, and prohibited copy.

Tests:

- ViewModel tests for EV labels and source chips.
- Compose tests for range rail visible state and copy guard.

## Epic 3: Local History Lenses

Goal: Add local-history-dependent lenses without breaking the on-demand selected-detail boundary. Correlation and trend reliability must use saved or already-loaded samples and must surface insufficiency explicitly.

### Story 3.1: Correlation Risk Core Analyzer

Status: Planned

Use cases covered: Correlation Risk.

Dependencies: Story 1.1.

Likely files:

- `apps/android/core/src/main/kotlin/com/discountscreener/core/engine/QuantLensEngine.kt`
- `apps/android/core/src/test/kotlin/com/discountscreener/core/engine/QuantLensCorrelationRiskTest.kt`

File ownership boundaries:

- Core owns return-series alignment, sample-count gating, correlation calculation, and risk labels.
- App supplies already-loaded candle series and universe membership only.
- Core must not know about repositories, Yahoo, SQLite, or Compose.
- Core must interpret Correlation Risk as local close-to-close return correlation only, never provider/source redundancy or portfolio diversification.

Acceptance criteria:

- AQL-3.1-AC1: Given fewer than 30 overlapping returns for a pair, then that pair is excluded and can be listed as insufficient in detail reasons.
- AQL-3.1-AC2: Given fewer than 3 universe symbols have sufficient local history, then result is `Unavailable`.
- AQL-3.1-AC3: Given the current symbol has one valid pair correlation below 0.85, then result is `Sparse`.
- AQL-3.1-AC4: Given the current symbol has one valid pair correlation at or above 0.85, then result is `High` with a single-pair reason.
- AQL-3.1-AC5: Given one valid pair has absolute correlation 0.72 and no other pair exceeds 0.70, then label is `Elevated` and that symbol is shown.
- AQL-3.1-AC6: Given two valid pairs have absolute correlations 0.71 and 0.77, then label is `High` and top correlated symbols are shown.
- AQL-3.1-AC7: Given all valid pairs are below 0.70, then label is `Low`.
- AQL-3.1-AC8: Given duplicate universe symbols, then they are deduped before evaluation.
- AQL-3.1-AC9: Given two symbols share provider/source family, analyst source, sector, or DCF/fundamental dependencies but lack sufficient overlapping close-to-close returns, then Correlation Risk remains `Unavailable` or `Sparse` according to local-history sufficiency.
- AQL-3.1-AC10: Given valid negative correlations exist, then detail can display signed correlation values, but the Low/Elevated/High risk band is based on absolute local co-movement and never described as diversification.
- AQL-3.1-AC11: Given zero-variance returns, constant-return pairs, non-finite intermediate values, or extreme fixed-point close values, then affected pairs resolve to `Insufficient` and aggregate output resolves to `Sparse` or `Unavailable` according to remaining valid samples, with no NaN, infinity, overflow, or non-finite public output.

Tasks:

- AQL-3.1-T1: Add comparable candle-series input shape for local correlation.
- AQL-3.1-T2: Implement close-to-close returns, epoch-aligned overlap, and same-range ordered overlap only when exact epoch alignment is unavailable and sample count remains explicit.
- AQL-3.1-T3: Implement pair exclusion and insufficient-history reasons.
- AQL-3.1-T4: Implement Low/Elevated/High/Sparse/Unavailable labels.
- AQL-3.1-T5: Add tests with deterministic synthetic candle series.
- AQL-3.1-T6: Add degenerate math guards for zero variance, constant returns, non-finite calculations, and extreme fixed-point inputs.

Tests:

- Core tests for close-to-close return calculation, overlap count, universe sufficiency, sparse single pair, high single pair, elevated pair, high multiple pairs, low correlations, negative signed display values, zero variance, constant returns, non-finite calculations, extreme fixed-point inputs, provider/source non-influence, and dedupe.

### Story 3.2: Local History Correlation Integration Without Fan-Out

Status: Planned

Use cases covered: Correlation Risk.

Dependencies: Stories 1.2 and 3.1.

Likely files:

- `apps/android/app/src/main/kotlin/com/discountscreener/android/data/repository/DefaultDashboardRepository.kt`
- `apps/android/app/src/test/kotlin/com/discountscreener/android/data/repository/DefaultDashboardRepositoryTest.kt`

File ownership boundaries:

- Repository owns collection of already-loaded selected and comparable candles from `chartCache` only.
- Repository may pass insufficient comparable history to core.
- Repository must not load missing history for non-selected symbols to improve correlation.
- Repository must not use provider/source metadata, analyst source overlap, sector tags, or DCF/fundamental dependencies as correlation inputs.

Acceptance criteria:

- AQL-3.2-AC1: Given selected detail is opened, then the repository passes selected local candle history already loaded for the chosen range to core.
- AQL-3.2-AC2: Given comparable symbols have no local `chartCache` entries, then repository passes them as insufficient or omits their pair data without provider/storage calls.
- AQL-3.2-AC3: Given a symbol appears in tracked, watchlist, and opportunities, then the comparable universe contains it once.
- AQL-3.2-AC4: Given selected range changes, then correlation input fingerprint changes and selected Quant Lens recomputes.
- AQL-3.2-AC5: Given startup bootstrap runs, then no correlation pass runs for all profile symbols.
- AQL-3.2-AC6: Given provider/source-family metadata exists for comparable symbols, then repository does not pass it as Correlation Risk input; it may be used only as Evidence Strength rationale in other stories.
- AQL-3.2-AC7: Given non-selected symbols lack cached local candles, then no Yahoo call, SQLite history read, selected-detail hydration, or chart-history load occurs to improve Correlation Risk.

Tasks:

- AQL-3.2-T1: Add repository helper for deduped current universe symbols.
- AQL-3.2-T2: Add selected-range local candle extraction from existing caches.
- AQL-3.2-T3: Add no-fetch tests using fake clients/stores with call counters.
- AQL-3.2-T4: Include selected range and local history counts in Quant Lens fingerprint.
- AQL-3.2-T5: Add tests proving provider/source overlap does not produce Corr Low/Elevated/High without local close-to-close return samples.

Tests:

- Repository tests for dedupe, no non-selected fetch/storage/chart-load/DCF fan-out, range invalidation, startup avoidance, and provider/source non-influence.

### Story 3.3: Trend Reliability Core Analyzer

Status: Planned

Use cases covered: Trend Reliability.

Dependencies: Story 1.1.

Likely files:

- `apps/android/core/src/main/kotlin/com/discountscreener/core/engine/QuantLensEngine.kt`
- `apps/android/core/src/test/kotlin/com/discountscreener/core/engine/QuantLensTrendReliabilityTest.kt`

File ownership boundaries:

- Core owns least-squares fit, R-squared, materiality thresholds, sample minimums, and labels.
- Core may use internal floating point for least-squares math but exposes fixed-point slope/fit outputs.
- Presenter/UI must not calculate fit quality.
- Core must fit only loaded sample series: selected candles, collapsed analyst target revision samples, and saved estimate-history reports. Summary-only inputs are insufficient.

Acceptance criteria:

- AQL-3.3-AC1: Given price history has fewer than 20 candles, then price trend reliability is `Insufficient` and no reliable/moderate/noisy label is emitted.
- AQL-3.3-AC2: Given 20 or more candles with fitted movement >= 200 bps and R-squared >= 0.70, then label is `Reliable uptrend`, `Reliable downtrend`, or equivalent split direction/reliability fields.
- AQL-3.3-AC3: Given 20 or more candles with fitted movement >= 200 bps and weak fit quality, then label is `Noisy` or equivalent split reliability field with no up/down direction rather than reliable, moderate, noisy uptrend, or noisy downtrend.
- AQL-3.3-AC4: Given fitted movement magnitude is below 200 bps, then label is `Flat` regardless of fit quality; no directional up/down label and no `Noisy` label is emitted for sub-material movement.
- AQL-3.3-AC5: Given analyst target history has repeated unchanged spans, then spans collapse before the minimum sample check.
- AQL-3.3-AC6: Given estimates history has at least 3 reports for a scenario, then trend output is available for that scenario.
- AQL-3.3-AC7: Given identical ordered samples, then slope, fit quality, sample count, and label are deterministic.
- AQL-3.3-AC8: Given only `ChartRangeSummary`, EMA, MACD, or other summary data is available without loaded candles, target revisions, or estimate-history samples, then Trend Reliability is `Insufficient` or `Unavailable` and no least-squares fit is emitted.
- AQL-3.3-AC9: Given loaded candle, target-revision, and estimate-history sample fixtures, then tests verify least-squares slope, R-squared, sample count, direction, and band for each target type.
- AQL-3.3-AC10: Given constant y values, zero variance, non-finite intermediate calculations, or non-finite candidate outputs, then Trend Reliability resolves to finite `Insufficient`, `Flat`, or `Noisy` states with no NaN, infinity, or overflow in public fixed-point fields.

Tasks:

- AQL-3.3-T1: Add trend target models for price, analyst target, and estimate scenarios.
- AQL-3.3-T2: Implement sample normalization and least-squares fit.
- AQL-3.3-T3: Implement R-squared and fixed-point output rounding.
- AQL-3.3-T4: Implement flat-span collapse for analyst target revisions.
- AQL-3.3-T5: Add loaded-candle, target-revision, and estimate-history sample fixtures with expected slope/R-squared outputs.
- AQL-3.3-T6: Add focused tests for materiality below 200 bps always mapping to flat, material movement at or above 200 bps, weak-fit noisy states only at or above 200 bps with no direction, each label, sample gate, summary-only rejection, and degenerate finite outputs.

Tests:

- Core tests for loaded candle fit, analyst target revision fit with span collapse, estimate scenario history fit, summary-only rejection, insufficient, reliable, moderate, flat sub-200 bps movement, >=200 bps directional movement, weak-fit noisy output only at or above 200 bps with no direction, constant y, zero variance, non-finite safety, bearish reliable trend, and determinism.

### Story 3.4: Trend Reliability On Detail History And Estimates

Status: Planned

Use cases covered: Trend Reliability.

Dependencies: Stories 1.2, 1.3, and 3.3.

Likely files:

- `apps/android/app/src/main/kotlin/com/discountscreener/android/data/repository/DefaultDashboardRepository.kt`
- `apps/android/app/src/main/kotlin/com/discountscreener/android/presentation/dashboard/DashboardViewModel.kt`
- `apps/android/app/src/main/kotlin/com/discountscreener/android/ui/dashboard/DetailScreen.kt`
- `apps/android/app/src/main/kotlin/com/discountscreener/android/ui/dashboard/EstimatesScreen.kt`
- `apps/android/app/src/test/kotlin/com/discountscreener/android/data/repository/DefaultDashboardRepositoryTest.kt`
- `apps/android/app/src/test/kotlin/com/discountscreener/android/presentation/dashboard/DashboardViewModelTest.kt`
- `apps/android/app/src/test/kotlin/com/discountscreener/android/ui/dashboard/DetailScreenTest.kt`
- `apps/android/app/src/test/kotlin/com/discountscreener/android/ui/dashboard/EstimatesScreenTest.kt`

File ownership boundaries:

- Repository supplies selected candles, selected revisions, and estimate report history already available through existing use cases.
- Presenter maps trend labels and sample counts.
- Compose renders detail Lens trend section, History target trend state, and Estimates scenario trend state without recalculation.
- Repository must not fake trend reliability from `ChartRangeSummary` alone.

Acceptance criteria:

- AQL-3.4-AC1: Given selected detail has enough price candles, then the Lens trend section shows label, sample count, and fit quality.
- AQL-3.4-AC2: Given selected detail lacks enough loaded candles, then Lens shows `Trend sparse` or `Trend unavailable` with sample count/reason.
- AQL-3.4-AC3: Given History has enough collapsed analyst target observations, then History can display target trend reliability without changing existing no-history/flat/no-target states.
- AQL-3.4-AC4: Given Estimates has at least 3 saved reports for a scenario, then Estimates can show scenario trend reliability without Compose recalculation.
- AQL-3.4-AC5: Given trend UI renders, then it does not say the trend will continue or imply breakout confirmation.
- AQL-3.4-AC6: Given only chart summaries are present and raw selected candles are absent, then repository/presenter expose an insufficient trend state rather than a summary-derived fit.

Tasks:

- AQL-3.4-T1: Pass selected trend inputs through Quant Lens input/report.
- AQL-3.4-T2: Map trend display labels and sample-count chips.
- AQL-3.4-T3: Add passive rendering to Lens, History, and Estimates surfaces where existing state supports it.
- AQL-3.4-T4: Add repository tests for loaded candles, target revisions, estimate history, and summary-only insufficient input assembly.
- AQL-3.4-T5: Add tests for sparse and available state rendering.

Tests:

- Repository tests for selected trend input assembly from loaded candles, selected revisions, and estimate history, plus summary-only rejection.
- ViewModel tests for trend labels and sample counts.
- Compose tests for Lens, History, and Estimates trend state rendering.

## Epic 4: Similar Setups

Goal: Add deterministic nearest-neighbor comparable opportunities from the current known universe while avoiding expensive non-selected fetches and outcome claims.

### Story 4.1: Similar Setups Core Analyzer

Status: Planned

Use cases covered: Similar Setups.

Dependencies: Stories 2.1, 2.3, and 3.3.

Likely files:

- `apps/android/core/src/main/kotlin/com/discountscreener/core/engine/QuantLensEngine.kt`
- `apps/android/core/src/test/kotlin/com/discountscreener/core/engine/QuantLensSimilarSetupsTest.kt`

File ownership boundaries:

- Core owns feature normalization, shared-feature gating, distance calculation, ordering, tie-breaking, and top-N caps.
- App supplies bounded comparable vectors from already-known symbols.
- Core must not fetch, persist, or inspect Android state.
- Core must compare current-universe feature vectors only and must not model historical analogs, follow-through, future outcome windows, median outcomes, or winner/loser cohorts.

Acceptance criteria:

- AQL-4.1-AC1: Given fewer than 3 other current-universe symbols have at least 3 shared features with the target, then similar setups status is `Sparse`, the qualifying comparable count is exposed, and no success-looking top-3 list is shown.
- AQL-4.1-AC2: Given 3 or more comparable symbols meet the shared-feature rule, then exactly the 3 smallest current-universe distances are returned, ordered by distance, then descending composite score, then symbol.
- AQL-4.1-AC3: Given two symbols have identical normalized feature values, then their distance is 0 and they sort ahead of non-identical symbols.
- AQL-4.1-AC4: Given a feature is missing for one side, then that feature is excluded and shared-feature count decreases.
- AQL-4.1-AC5: Given fewer than 3 shared features remain for a pair, then that pair is excluded.
- AQL-4.1-AC6: Given input universe order changes but data is identical, then top-3 output is stable.
- AQL-4.1-AC7: Given candidate data includes historical dates, realized returns, follow-through windows, median outcomes, or cohort labels, then those fields are ignored or rejected and never appear in the core output.
- AQL-4.1-AC8: Given exactly 2 comparable symbols meet the shared-feature rule, then similar setups status is `Sparse`, qualifying comparable count is 2, and no top-3 list is shown.
- AQL-4.1-AC9: Given feature values exceed normalization bounds, ordinal source bands are supplied, or canonical feature-weight slots are evaluated, then clipping, ordinal mapping, weights `3/3/2/1/1`, and weighted Euclidean distance are deterministic and covered by tests.

Tasks:

- AQL-4.1-T1: Define five normalized feature slots with canonical weights `3/3/2/1/1`: valuation upside, evidence ordinal, opportunity score/buckets, trend ordinal/slope, and EV spread.
- AQL-4.1-T2: Add feature clipping, evidence/trend ordinal mapping, and fixed-point normalized feature representation.
- AQL-4.1-T3: Implement shared-feature gating and weighted Euclidean distance scoring with explicit per-feature weights `3/3/2/1/1`.
- AQL-4.1-T4: Implement deterministic top-3 ordering and reason generation.
- AQL-4.1-T5: Add guards/tests proving historical outcome and follow-through fields are not part of the Similar Setups model.
- AQL-4.1-T6: Add tests for feature clipping, ordinal mapping, weights `3/3/2/1/1`, weighted Euclidean math, exact-two sparse with count, exact top 3, tie-breaking by distance/composite score/symbol, missing features, and stable order.

Tests:

- Core tests for current-universe-only inputs, feature clipping, ordinal mapping, per-feature weights `3/3/2/1/1`, weighted Euclidean distance math, shared-feature count, exact-two sparse with qualifying count, top-3 cap, missing-feature exclusion, tie order by distance/composite score/symbol, sparse comparable status, and no historical outcome/follow-through fields.

### Story 4.2: Comparable Universe Assembly Without Fetching

Status: Planned

Use cases covered: Similar Setups, with supporting Evidence Strength, EV Range, Correlation Risk, and Trend Reliability features where already available.

Dependencies: Stories 1.2 and 4.1.

Likely files:

- `apps/android/app/src/main/kotlin/com/discountscreener/android/data/repository/DefaultDashboardRepository.kt`
- `apps/android/app/src/test/kotlin/com/discountscreener/android/data/repository/DefaultDashboardRepositoryTest.kt`

File ownership boundaries:

- Repository owns current tracked/watch/opportunity universe assembly and conversion into `QuantLensComparable` inputs.
- Repository may include existing in-memory `SymbolDetail`, opportunity row scores, chart summaries, and DCF cache values.
- Repository must not fetch missing chart, DCF, revisions, or fundamentals for comparable candidates.
- Repository must not assemble historical analog cohorts, past setup dates, realized follow-through returns, median outcomes, or future windows for Similar Setups.

Acceptance criteria:

- AQL-4.2-AC1: Given tracked, watchlist, and opportunity rows contain overlapping symbols, then comparable universe is deduped by symbol and excludes the selected target.
- AQL-4.2-AC2: Given a comparable lacks optional feature inputs, then repository passes missing features rather than synthetic neutral values.
- AQL-4.2-AC3: Given no comparable details are in memory, then similar setups result is sparse/unavailable and no provider/storage calls occur.
- AQL-4.2-AC4: Given opportunity scoring model changes, the canonical `3/3/2/1/1` comparable feature-weight version changes, or any actual comparable feature value changes, then comparable feature fingerprint changes and selected Quant Lens recomputes.
- AQL-4.2-AC5: Given a comparable has sparse coverage, then its coverage metadata is passed so core can penalize or explain the match.
- AQL-4.2-AC6: Given comparable universe assembly runs, then it includes only current tracked, watchlist, and opportunity symbols, deduped by symbol, and excludes the selected target.
- AQL-4.2-AC7: Given historical revision data or saved returns exist for a comparable, then repository does not convert them into analog dates, follow-through fields, median outcomes, or winner/loser labels for Similar Setups.

Tasks:

- AQL-4.2-T1: Add repository helper to assemble bounded comparable inputs.
- AQL-4.2-T2: Exclude selected symbol and dedupe current universe.
- AQL-4.2-T3: Add no-fetch tests for missing comparable details.
- AQL-4.2-T4: Add scoring-model, canonical `3/3/2/1/1` feature-weight version, actual comparable feature-value, and comparable-universe fingerprint inputs.
- AQL-4.2-T5: Add tests proving historical outcome fields are absent from assembled comparable inputs.

Tests:

- Repository tests for current-universe dedupe/exclusion, missing-feature handling, no-fetch behavior, scoring-model invalidation, canonical `3/3/2/1/1` feature-weight version invalidation, actual comparable feature-value fingerprint invalidation, sparse coverage propagation, and absence of historical outcome/follow-through inputs.

### Story 4.3: Similar Setups Detail Rows And Symbol Navigation

Status: Planned

Use cases covered: Similar Setups.

Dependencies: Stories 1.3, 4.1, and 4.2.

Likely files:

- `apps/android/app/src/main/kotlin/com/discountscreener/android/presentation/dashboard/DashboardViewModel.kt`
- `apps/android/app/src/main/kotlin/com/discountscreener/android/ui/dashboard/QuantLensContent.kt`
- `apps/android/app/src/test/kotlin/com/discountscreener/android/presentation/dashboard/DashboardViewModelTest.kt`
- `apps/android/app/src/test/kotlin/com/discountscreener/android/ui/dashboard/DetailScreenTest.kt`

File ownership boundaries:

- Presenter maps match rows, current setup labels, shared feature count, and compact reason text.
- Compose displays at most three rows and dispatches existing open-detail actions.
- Compose must not expose raw vectors, K controls, or distance formulas.
- Compose must not render historical dates, follow-through returns, median outcomes, outcome windows, or winner/loser language.

Acceptance criteria:

- AQL-4.3-AC1: Given similar setup matches are available, detail Lens shows rows with symbol, reason text, shared-feature count, and current upside/evidence labels.
- AQL-4.3-AC2: Given more than three matches exist in the report, then UI renders no more than three rows.
- AQL-4.3-AC3: Given the user taps a match row, then the existing detail-open action is dispatched for that symbol.
- AQL-4.3-AC4: Given sparse/unavailable similar setups, then UI shows `Similar sparse` or `Similar unavailable` and does not display an empty-looking success state.
- AQL-4.3-AC5: Given copy renders, then it says `Similar current setups` or `Closest by current features` and not `same outcome`, `lookalike winners`, `historical analog`, `follow-through`, `median outcome`, or `model predicts`.

Tasks:

- AQL-4.3-T1: Map similar setup matches into UI rows and chips.
- AQL-4.3-T2: Render passive rows with stable one-line symbol/setup labels and ellipsis for long text.
- AQL-4.3-T3: Wire tap action to existing detail navigation path.
- AQL-4.3-T4: Add tests for top-3 cap, sparse state, tap action, and copy guard.

Tests:

- ViewModel tests for match-row mapping.
- Compose tests for three-row cap, tap dispatch, sparse/unavailable rendering, and prohibited copy.

## Epic 5: Dashboard And Detail UX Completion

Goal: Complete the Android Quant Lens surface as a dense workstation layer across detail and eligible list surfaces, then harden copy, performance, and regression tests.

### Story 5.1: Dashboard Quant Lens Strip Across Opps Upside And Watch

Status: Planned

Use cases covered: Evidence Strength, Expected Value Range, Correlation Risk, Trend Reliability, Similar Setups as compact row chips.

Dependencies: Stories 1.3, 1.4, 2.2, 2.4, 3.2, and 4.3.

Likely files:

- `apps/android/app/src/main/kotlin/com/discountscreener/android/domain/model/DashboardSnapshot.kt`
- `apps/android/app/src/main/kotlin/com/discountscreener/android/presentation/dashboard/DashboardViewModel.kt`
- `apps/android/app/src/main/kotlin/com/discountscreener/android/ui/dashboard/DashboardLists.kt`
- `apps/android/app/src/test/kotlin/com/discountscreener/android/presentation/dashboard/DashboardViewModelTest.kt`
- `apps/android/app/src/test/kotlin/com/discountscreener/android/ui/dashboard/DashboardListsTest.kt`

File ownership boundaries:

- Row chips consume presenter-supplied row display summaries only.
- Do not run full Quant Lens analysis for every row during Compose rendering or startup.
- If full row reports are not available, presenter supplies `Lens loading`, unavailable, or cheap already-known summary states without hidden computation.
- Row chips apply the Story 1.4 bounded contract and width caps: at most three chips at or above 360 dp, and at most two chips below 360 dp.

Acceptance criteria:

- AQL-5.1-AC1: Given `Opps` renders an opportunity row with a row Quant Lens summary, then the existing signal chips render first, Quant Lens strip second, and existing score/metric tokens remain after it.
- AQL-5.1-AC2: Given `Upside` renders a tracked row with a row Quant Lens summary, then the strip renders after existing tracked signals and before tracked metrics.
- AQL-5.1-AC3: Given `Watch` renders watched rows, then it uses the same tracked row strip treatment.
- AQL-5.1-AC4: Given `System` or `Estimates` tabs render, then they do not show row Quant Lens chips.
- AQL-5.1-AC5: Given a row has no Quant Lens model yet, then it shows `Lens loading` instead of blank space.
- AQL-5.1-AC6: Given a specific use case is unavailable, sparse, or stale and selected into the visible cap, then its chip shows the canonical degraded label instead of disappearing.
- AQL-5.1-AC7: Given a dashboard row chip is tapped, then no separate chip action is fired; the row detail-open behavior remains unchanged.
- AQL-5.1-AC8: Given available width is at least 360 dp, then the row shows at most three Quant Lens chips; given width is below 360 dp, then the row shows at most two chips.
- AQL-5.1-AC9: Given row chips render, then EV range appears only when the three-positive-anchor rule is satisfied, Corr Elevated/High appears only from local close-to-close return correlation, Trend appears only from sample-backed fit output, and Similar appears only when at least three qualifying current-universe comparables produce top-3 data.

Tasks:

- AQL-5.1-T1: Add row-level Quant Lens display summary shape if needed, distinct from full selected-detail report.
- AQL-5.1-T2: Map selected/cached reports and cheap row states into row chip models without full list-wide analysis.
- AQL-5.1-T3: Add passive `QuantLensStrip` rendering to opportunity and tracked rows.
- AQL-5.1-T4: Add tests for Opps, Upside, Watch, System, Estimates, loading, degraded labels, passive tap, chip caps, and narrow wrapping behavior.

Tests:

- ViewModel tests for row summary mapping.
- Dashboard list Compose tests for placement, fixed chip order, loading/degraded labels, no chip action, tab exclusion, two-chip narrow cap, and three-chip standard cap.

### Story 5.2: Lens Tab Navigation Snapshot Mini Strip And Section Focus

Status: Planned

Use cases covered: Evidence Strength, Expected Value Range, Correlation Risk, Trend Reliability, Similar Setups in detail navigation.

Dependencies: Stories 1.3, 2.2, 2.4, and 4.3.

Likely files:

- `apps/android/app/src/main/kotlin/com/discountscreener/android/presentation/dashboard/DashboardViewModel.kt`
- `apps/android/app/src/main/kotlin/com/discountscreener/android/ui/dashboard/DetailScreen.kt`
- `apps/android/app/src/main/kotlin/com/discountscreener/android/ui/dashboard/QuantLensContent.kt`
- `apps/android/app/src/test/kotlin/com/discountscreener/android/presentation/dashboard/DashboardViewModelTest.kt`
- `apps/android/app/src/test/kotlin/com/discountscreener/android/ui/dashboard/DetailScreenTest.kt`

File ownership boundaries:

- Presenter owns route state and focused section target.
- Compose emits tab, chip focus, chart range, Prev, Next, Back, and OpenDetail actions only.
- Existing Snapshot and History behavior must remain intact.

Acceptance criteria:

- AQL-5.2-AC1: Given detail opens from a row, then the initial subtab remains `Snapshot`.
- AQL-5.2-AC2: Given detail tabs render, then order is `Snapshot`, `Lens`, `History`.
- AQL-5.2-AC3: Given Snapshot renders a loaded report, then a Quant Lens mini strip appears below the price/fair/discount headline and above chart-range chips.
- AQL-5.2-AC4: Given a Snapshot mini-strip chip is tapped, then route switches to `Lens` and records the matching section focus target.
- AQL-5.2-AC5: Given `Lens` renders, then sections appear in order: `Evidence strength`, `Expected value range`, `Correlation risk`, `Trend reliability`, `Similar setups`.
- AQL-5.2-AC6: Given user changes Lens chart window, then the same selected chart range route state used by Snapshot updates.
- AQL-5.2-AC7: Given user uses Prev or Next while on `Lens`, then the next ticker preserves the active `Lens` subtab.
- AQL-5.2-AC8: Given Back is pressed from detail, then user returns to originating dashboard tab and existing list scroll behavior is not regressed.

Tasks:

- AQL-5.2-T1: Extend `DetailSubtab` with `Lens` or `QuantLens` and update route tests.
- AQL-5.2-T2: Add focused section target to detail route or UI state.
- AQL-5.2-T3: Render Snapshot mini strip and Lens full content.
- AQL-5.2-T4: Wire chip focus, chart range, Prev/Next, and Back behaviors.
- AQL-5.2-T5: Add navigation and rendering tests.

Tests:

- ViewModel tests for initial tab, tab order state, chip focus, range sharing, Prev/Next preservation, and Back.
- Compose tests for Snapshot mini strip placement and Lens section order.

### Story 5.3: Anti-Overclaiming Copy Performance And Regression Hardening

Status: Planned

Use cases covered: Evidence Strength, Expected Value Range, Correlation Risk, Trend Reliability, Similar Setups.

Dependencies: Stories 5.1 and 5.2.

Likely files:

- `apps/android/core/src/test/kotlin/com/discountscreener/core/engine/QuantLensEngineTest.kt`
- `apps/android/app/src/test/kotlin/com/discountscreener/android/data/repository/DefaultDashboardRepositoryTest.kt`
- `apps/android/app/src/test/kotlin/com/discountscreener/android/presentation/dashboard/DashboardViewModelTest.kt`
- `apps/android/app/src/test/kotlin/com/discountscreener/android/ui/dashboard/DashboardListsTest.kt`
- `apps/android/app/src/test/kotlin/com/discountscreener/android/ui/dashboard/DetailScreenTest.kt`
- `apps/android/app/src/test/kotlin/com/discountscreener/android/ui/dashboard/EstimatesScreenTest.kt`

File ownership boundaries:

- This story should harden behavior and copy only. It must not introduce new Quant Lens business semantics.
- Keep tests in the owning layer: core for rules, repository for boundaries, presenter/UI for labels and rendering.
- Do not add docs or README edits unless a later implementation phase explicitly requests documentation changes.

Acceptance criteria:

- AQL-5.3-AC1: Given every primary Quant Lens UI surface renders available, sparse, stale, and unavailable states, then no prohibited investment-advice or outcome-prediction phrases appear.
- AQL-5.3-AC2: Given app bootstrap and profile switch tests run, then Quant Lens does not trigger all-symbol history, DCF, timeseries, or provider fetch fan-out.
- AQL-5.3-AC3: Given all lens outputs are unavailable, then opportunities, tracked, watch, detail, history, and estimates flows still render without crashes or hidden symbols.
- AQL-5.3-AC4: Given selected detail report fingerprint is unchanged, then repeated snapshots reuse cache; given any fingerprint component from Story 1.2 changes, then recomputation occurs and the stale cached report is not exposed.
- AQL-5.3-AC5: Given row and detail surfaces render on narrow layouts, then chip text remains legible, wraps without horizontal scrolling, and does not overlap nearby content.
- AQL-5.3-AC6: Given core report tests mutate key thresholds manually during local development, then focused tests fail for incorrect labels, sample gates, and deterministic ordering.
- AQL-5.3-AC7: Given visible UI is ready for QA, then the manual QA checklist below is completed or each blocked item is documented with the SDK/device reason.

Tasks:

- AQL-5.3-T1: Add a shared test fixture or helper for prohibited copy checks in app tests.
- AQL-5.3-T2: Add repository performance boundary tests with fake call counters.
- AQL-5.3-T3: Add cache/fingerprint regression tests that mutate selected range, candle hash, revision target hash, estimate history, DCF fingerprint, comparable universe, correlation series, scoring version, model version, selected symbol, and profile independently.
- AQL-5.3-T4: Add targeted manual mutation notes to the implementation PR or story completion notes.
- AQL-5.3-T5: Add all-unavailable regression tests across affected surfaces.
- AQL-5.3-T6: Run the repository's Android verification path during implementation, respecting SDK availability.

Manual QA checklist:

- Verify narrow rows at 320 dp and 360 dp respect the two-chip and three-chip row caps, remain legible, avoid horizontal scrolling, and do not overlap existing score or metric tokens.
- Verify cold/warm-start rows show `Lens loading`, `Lens sparse`, or `Lens unavailable` without chart, DCF, history, provider, or comparable fetches for non-selected symbols.
- Open detail from `Opps`, `Upside`, and `Watch`; verify default `Snapshot`, `Lens` tab order, Snapshot mini-strip placement, mini-strip focus behavior, Back, Prev, Next, and selected range sharing.
- Audit visible copy for advice, probability, formula, hidden-model, Bayesian, posterior/prior/likelihood, backtest, historical analog, follow-through, median outcome, and winner/loser language.
- Force each lens into sparse, stale, and unavailable states; verify canonical precedence selects the primary chip and secondary sample/anchor/freshness context remains visible.
- Verify EV with one or two positive anchors shows reference-only detail rows, no weighted upside, no range rail, no spread/range width, and no row EV range; verify three positive anchors unlock scenario-weighted display.
- Verify Correlation Risk says `Local return correlation`, shows overlapping return counts, uses only current tracked/watch/opportunity peers with local close-to-close returns, and does not describe provider/source concentration or portfolio diversification.
- Verify Trend Reliability labels come from least-squares slope, R-squared, and sample count over loaded candles, target revisions, or estimate history; EMA/MACD appear only as supporting context when present.
- Verify Similar Setups shows at most three current symbols with current setup reasons, similarity/shared-feature counts, and current labels; no dates, backtest fields, follow-through, median outcomes, or winner/loser language appear.
- Verify all-unavailable reports leave opportunities, tracked, watch, detail, history, and estimates flows usable without hiding symbols or crashing.

Tests:

- Core regression tests for final use-case labels and deterministic behavior.
- Repository tests for startup/profile switch/fingerprint boundaries and stale-cache prevention.
- ViewModel and Compose tests for copy, unavailable rendering, row/detail placement, and narrow state stability.

## Cross-Story Dependency Notes

- Stories 1.4, 2.2, 2.4, 3.4, 4.3, 5.1, and 5.2 all touch presentation/UI files. Keep these sequential unless a worker isolates new UI model files and passive composables first.
- Stories 1.2, 1.4, 3.2, and 4.2 all touch `DefaultDashboardRepository.kt` or its snapshot boundary. Keep them sequential and prefer small private helpers to reduce merge conflict risk.
- Stories 2.1, 2.3, 3.1, 3.3, and 4.1 all touch `QuantLensEngine.kt`. If the file grows quickly, split internal analyzers by use case while keeping one small public `QuantLensEngine.analyze` entrypoint.
- Trend reliability depends on selected chart range state. Complete chart-range route tests before adding row-level trend chips.
- Similar setups depends on evidence, EV, and trend feature outputs for richer vectors. Implement sparse behavior first, then improve match reasons once upstream features exist.

## Risks And Unresolved Planning Notes

- Product/architecture tension: UX asks row chips on Opps/Upside/Watch, while architecture forbids startup-wide Quant Lens computation. Story 1.4 resolves this with a bounded row-summary contract: full reports are selected-detail only, while row strips render `Lens loading`, degraded states, or cheap summaries from exact row fields without hidden work.
- Correlation risk may be sparse for many symbols because comparable candle history is local-only and on-demand. The UI must make `Insufficient local history` visible instead of hiding the chip.
- Similar setups can become unstable if sparse comparables are not penalized and ties are not deterministic. Core tests must lock top-N ordering and shared-feature gates.
- Trend reliability can be misread as bullishness. Models and UI must keep direction separate from reliability and allow reliable bearish trends.
- Expected value range wording can overclaim. It must name DCF or analyst source and use scenario-weighted/range language, not profit certainty.
- Repository shared-file overlap is unavoidable. Keep implementation stories sequential around `DefaultDashboardRepository.kt` and add private helpers rather than broad refactors.
