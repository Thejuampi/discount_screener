---
title: Android Quant Lens Architecture
project: discount_screener
phase: 3 Plan Repair Round 2
date: 2026-05-03
status: proposed
scope: Android implementation architecture for the selected Quant Lens top 5 use cases
selected_use_cases:
  - Evidence Strength
  - Expected Value Range
  - Correlation Risk
  - Trend Reliability
  - Similar Setups
---

## Executive Decision

Build Quant Lens as a selected-symbol, on-demand analysis surface in the Android detail experience. The analysis semantics belong in the pure Kotlin `core` module; the Android `app` module only orchestrates loading, maps domain output into UI state, and renders passive Compose views.

The feature is a go with guardrails:

- Preserve functional core / imperative shell / strict MVP.
- Do not run expensive Quant Lens work for the whole startup universe.
- Do not add new market-data fetches for Quant Lens beyond the existing selected-detail hydration path.
- Do not present outputs as trading advice, buy/sell directives, or predictions of investment outcome.
- Treat sparse, stale, restored, provider-uncertain, and unavailable inputs as first-class states.

Quant Lens should answer: "What evidence do we have, how wide is the valuation range, which local peers move with this symbol, how reliable is the trend fit, and which already-known current symbols look structurally similar?" It should not answer: "Should I trade this?"

## Source Context Read

This architecture is grounded in these existing Android boundaries:

- `apps/android/README.md`: module map and current Android surface.
- `apps/android/core/src/main/kotlin/com/discountscreener/core/model/Models.kt`: shared domain models, fixed-point financial values, provenance/evidence/risk primitives, chart summaries, DCF analysis, symbol revisions.
- `apps/android/core/src/main/kotlin/com/discountscreener/core/engine/ReportingEngine.kt`: current symbol aggregation and `SymbolDetail` construction.
- `apps/android/core/src/main/kotlin/com/discountscreener/core/engine/OpportunityEngine.kt`: current opportunity scoring model and signal collapsing patterns.
- `apps/android/core/src/main/kotlin/com/discountscreener/core/engine/ChartAnalysis.kt`: chart summary, replay, and volume-profile pure helpers.
- `apps/android/core/src/main/kotlin/com/discountscreener/core/engine/DcfAnalysisEngine.kt`: DCF scenario computation.
- `apps/android/app/src/main/kotlin/com/discountscreener/android/data/repository/DefaultDashboardRepository.kt`: imperative shell for restore, refresh, selected-detail hydration, chart cache, DCF cache, revision history, and snapshot production.
- `apps/android/app/src/main/kotlin/com/discountscreener/android/domain/model/DashboardSnapshot.kt`: app-domain snapshot passed to presentation.
- `apps/android/app/src/main/kotlin/com/discountscreener/android/presentation/dashboard/DashboardViewModel.kt`: Presenter that maps snapshots to UI state and routes actions.
- `apps/android/app/src/main/kotlin/com/discountscreener/android/ui/dashboard/DetailScreen.kt`: passive detail surface with Snapshot and History subtabs today.

## Architectural Principles

### Functional Core

`core` owns all Quant Lens rules, calculations, status classification, confidence treatment, and risk interpretation. Quant Lens engines must be pure functions over explicit inputs. They must not call Android APIs, network clients, SQLite, clocks, logging frameworks, or coroutines.

The core API should make sparse data impossible to confuse with negative data. Missing DCF, missing analyst range, insufficient chart candles, stale external signals, and unavailable comparables produce explicit status values, not silent zeroes.

### Imperative Shell

`app/data` owns data availability and lifecycle:

- restore persisted canonical inputs;
- refresh provider inputs;
- load selected-detail history and chart candles;
- keep small in-memory caches;
- call pure Quant Lens engines with a complete input object;
- emit `DashboardSnapshot` updates.

The repository may cache derived Quant Lens reports in memory, but SQLite should continue to store canonical inputs first: snapshots, external signals, fundamentals, chart candles/summaries, DCF analysis, revisions, and issues.

### Strict MVP

- Model: `core` models plus app-domain snapshot state.
- Presenter: `DashboardViewModel` maps `DashboardSnapshot.selectedQuantLens` into display-oriented `QuantLensUiState`, routes tab/action changes, and preserves current selected ticker behavior.
- View: Compose renders `QuantLensUiState` and emits actions only. Compose must not compute evidence strength, expected-value ranges, correlation risk, trend reliability, or similar-setup matches.

## Existing Fit

The current system already has most canonical inputs:

- `SymbolDetail` includes price, intrinsic value, analyst target range, weighted target, analyst counts, recommendation mean, confidence, fundamentals, external status, and freshness age.
- `ChartRangeSummary` includes candle count, latest close, EMA 20/50/200, MACD, signal, and histogram.
- `DcfAnalysis` includes bear/base/bull intrinsic values, WACC, base growth, net debt, and source metadata.
- `SymbolRevision` preserves evaluated historical truth with detail, chart summaries, and DCF at that time.
- `OpportunityEngine` already demonstrates useful patterns: bucketed evidence, signal collapsing, smooth ramps, coverage-aware scoring, and avoiding overcounting correlated signals.
- `DefaultDashboardRepository.ensureDetailLoaded` is already the selected-detail demand boundary for revision history, saved pricing, chart ranges, timeseries, and DCF.

Therefore Quant Lens should extend the selected-detail flow instead of creating a parallel startup worker.

## Core Model Design

Add a small Quant Lens model family under `core/model`. Keep values serializable, integer-based where financial or probability-like, and provenance-aware.

Recommended model names:

- `QuantLensReport`
- `QuantLensReport`
- `QuantLensStatus`
- `QuantLensPrimaryStatus`
- `QuantLensFreshnessQualifier`
- `QuantLensInput`
- `QuantLensEvidenceStrength`
- `QuantLensExpectedValueRange`
- `QuantLensCorrelationRisk`
- `QuantLensTrendReliability`
- `QuantLensSimilarSetups`
- `QuantLensEvidenceItem`
- `QuantLensRiskFlag`
- `QuantLensComparable`
- `SimilarSetupMatch`
- `QuantLensRowSummary`
- `QuantLensLensRowState`
- `QuantLensLensBand`
- `QuantLensRowLabel`
- `QuantLensReasonCode`
- `QuantLensTrendSampleSeries`
- `QuantLensCorrelationSeries`
- `QuantLensTrendSampleSeries`
- `QuantLensCorrelationSeries`

Recommended canonical core statuses and bands:

- `QuantLensPrimaryStatus`: `Available`, `Partial`, `Provisional`, `Sparse`, `Insufficient`, `Unavailable`.
- `QuantLensFreshnessQualifier`: `Fresh`, `Stale`, `Restored`, `ProviderUncertain`.
- `QuantLensAvailability`: retain only if needed as a report-level alias over `QuantLensPrimaryStatus`; do not make stale a primary availability value.
- `EvidenceStrengthBand`: `Strong`, `Provisional`, `Mixed`, `Sparse`, `Unavailable`; stale evidence is represented by `QuantLensFreshnessQualifier.Stale`.
- `ExpectedValueRangeBand`: `ScenarioWeighted`, `ReferenceOnly`, `Sparse`, `Unavailable`.
- `CorrelationRiskBand`: `Low`, `Elevated`, `High`, `Sparse`, `Unavailable`.
- `TrendReliabilityBand`: `Reliable`, `Moderate`, `Noisy`, `Flat`, `Insufficient`, `Unavailable`.
- `SimilarSetupsBand`: `Available`, `Sparse`, `Unavailable`.

These are domain values, not UI strings. The presenter maps them to labels such as `Evidence strong`, `EV sparse`, or `Corr high`. Core must not carry Android copy, color tokens, or display precedence.

Canonical degradation precedence is fixed for every lens, selected-detail report section, row summary, and presentation chip:

1. `Unavailable`
2. `Sparse` or `Insufficient`
3. `Stale`
4. `Provisional` or `Partial`
5. `Available`

`Stale` is a freshness qualifier when a sparse or insufficient condition also applies. For example, sparse stale analyst data has `primaryStatus = Sparse` or `Insufficient` and `freshnessQualifier = Stale`; it must not be promoted to a primary stale state. Presenter mapping may use the precedence to choose one compact label, but it must not rewrite core results or infer primary stale/sparse/unavailable states from freeform reason text.

Recommended selected-detail report shape:

```kotlin
data class QuantLensReport(
    val symbol: String,
  val selectedRange: ChartRange,
    val computedAtEpochSeconds: Long,
    val modelVersion: Int,
  val inputFingerprint: String,
  val primaryStatus: QuantLensPrimaryStatus,
  val freshnessQualifier: QuantLensFreshnessQualifier? = null,
    val evidenceStrength: QuantLensEvidenceStrength,
    val expectedValueRange: QuantLensExpectedValueRange,
    val correlationRisk: QuantLensCorrelationRisk,
    val trendReliability: QuantLensTrendReliability,
    val similarSetups: QuantLensSimilarSetups,
    val notices: List<String>,
)
```

`QuantLensReport` is the full selected-detail artifact. It may include detailed evidence rows, scenario anchors, valid pair correlations, selected-range trend samples, target-history trend, estimate-history trend, and top similar setup rows. It is built only when a selected detail route has the local inputs already loaded or already restored.

Recommended bounded row-summary shape:

```kotlin
enum class QuantLensLensId {
  EvidenceStrength,
  ExpectedValueRange,
  CorrelationRisk,
  TrendReliability,
  SimilarSetups,
}

data class QuantLensLensRowState(
  val lensId: QuantLensLensId,
  val primaryStatus: QuantLensPrimaryStatus,
  val band: QuantLensLensBand? = null,
  val label: QuantLensRowLabel? = null,
  val freshnessQualifier: QuantLensFreshnessQualifier? = null,
  val reasonCodes: List<QuantLensReasonCode>,
)

data class QuantLensRowSummary(
  val symbol: String,
  val fingerprint: String,
  val lensStates: List<QuantLensLensRowState>,
)
```

`QuantLensRowSummary` is intentionally bounded and cheap. It can be built from data already present on `OpportunityListRow`, `TrackedSymbolRow`, `SymbolDetail`, and current row freshness/trust state. It must never trigger provider calls, SQLite history reads, chart-history hydration, DCF/timeseries computation, revision-history loading, or comparable fan-out. When row-present data is insufficient for EV, correlation, trend, or similar setup labels, the summary returns structured per-lens sparse/unavailable states instead of silently running the full selected-detail report for every row.

Every row-summary lens state must include the lens id, `primaryStatus`, optional lens-specific band, optional semantic label key, optional `freshnessQualifier`, and typed reason codes. Reason codes are diagnostics and detail-row inputs only. Presenter and Compose must never parse reason-code strings to infer whether a lens is stale, sparse, insufficient, or unavailable.

Use fixed-point fields for derived quantities:

- probability-like values: `*_bps`, from 0 to 10,000.
- confidence/reliability scores: `*_bps`, from 0 to 10,000.
- log odds: `*_millis` or avoid exposing if not needed by UI.
- money: `*_cents`.
- distances/similarity: `*_bps`, where 10,000 means strongest match.

Do not expose floating-point output from core models. Floating point may be used internally for ramps when the current engine style already does so, then rounded and clamped before returning.

## Core Engine Design

Add one public pure engine first:

```kotlin
object QuantLensEngine {
    fun analyze(input: QuantLensInput): QuantLensReport
}
```

Inside the engine, split private or internal helpers by use case only when tests or readability need it:

- `EvidenceStrengthAnalyzer`
- `ExpectedValueRangeAnalyzer`
- `CorrelationRiskAnalyzer`
- `TrendReliabilityAnalyzer`
- `SimilarSetupsAnalyzer`

Keep the public API small. The first implementation should prefer one orchestrating engine plus testable internal helpers over five broad public engines.

### QuantLensInput

`QuantLensInput` should contain only canonical inputs and prebounded comparison candidates:

```kotlin
data class QuantLensInput(
    val detail: SymbolDetail,
  val selectedRange: ChartRange,
  val selectedCandlesByRange: Map<ChartRange, List<HistoricalCandle>>,
  val chartSummaries: Map<ChartRange, ChartRangeSummary>,
    val dcfAnalysis: DcfAnalysis?,
    val revisions: List<SymbolRevision>,
  val estimateHistory: List<IndexEstimatesReport>,
  val opportunityRows: List<OpportunityRow>,
  val comparableUniverse: List<QuantLensComparable>,
  val correlationSeries: List<QuantLensCorrelationSeries>,
  val scoringModel: OpportunityScoringModel,
  val scoringVersion: Int,
    val nowEpochSeconds: Long,
)
```

`selectedCandlesByRange` contains only candles already loaded through the selected-detail path. `estimateHistory` contains only `IndexEstimatesReport` rows already loaded through the estimates use case. `comparableUniverse` and `correlationSeries` are supplied by the app shell from currently known tracked/watch/opportunity symbols and already-present in-memory cache entries. None of these inputs may force provider fetches, SQLite history reads, DCF computation, or full candle loads for symbols that are not selected.

### Evidence Strength

Purpose: show how much independent evidence supports the current opportunity thesis, without claiming probability of profit or trading success.

Inputs:

- qualification and current upside from `SymbolDetail`;
- `ConfidenceBand` and `ExternalSignalStatus`;
- analyst breadth and recommendation mean;
- weighted fair value and target range when present;
- DCF margin from `DcfAnalysis` when present;
- DCF source, source fingerprint, source coverage, and provider uncertainty when present;
- fundamentals availability and basic quality signals;
- technical summary availability from preferred chart range;
- trend reliability output when available;
- opportunity row coverage, forecast, technical, fundamentals, and composite scores when available.

Design:

- Gate first on base availability: positive market price and at least one usable valuation signal are required before any non-unavailable evidence band can be emitted.
- Treat source/provider redundancy as Evidence Strength reliability metadata, not as Correlation Risk. Yahoo-only analyst inputs, low analyst breadth, provider-uncertain fields, DCF source uncertainty, and repeated same-family signals may reduce evidence from `Strong` to `Provisional`, `Mixed`, or `Sparse`, but they do not create a correlation-risk label.
- Collapse related evidence before support/conflict counts:
  - analyst target, weighted target, recommendation mean, and analyst count share an analyst-provider family;
  - EMA stack and MACD share chart-price-history family;
  - fundamentals and DCF share fundamental-accounting family.
- Classify each family as support, conflict, neutral, stale, sparse, or unavailable using core-owned thresholds.
- Return deterministic support, conflict, neutral, stale, and unavailable counts plus reason codes.
- Return a canonical band: `Strong`, `Provisional`, `Mixed`, `Sparse`, or `Unavailable`.
- Return `primaryStatus` and optional `freshnessQualifier` separately from the band. Stale source inputs qualify the evidence state, but a sparse input set remains primarily sparse according to the canonical degradation precedence.

Output naming should avoid overclaiming. Prefer `evidenceStrengthBps` and `supportBand`; avoid labels like `winProbability`.

### Expected Value Range

Purpose: display the valuation range implied by available DCF and analyst inputs.

Inputs:

- market price from `SymbolDetail`;
- internal intrinsic value;
- analyst low/high/weighted/median fair values;
- DCF bear/base/bull intrinsic values;
- analyst count and external freshness;
- DCF source/fingerprint when present.

Design:

- Require a positive current market price before any upside or spread can be emitted.
- Build scenario anchors from canonical values only. Do not invent missing bear/base/bull values and do not synthesize analyst low/high from one midpoint.
- Prefer DCF only when all three DCF anchors are present and positive: bear, base, and bull intrinsic value.
- Use analyst anchors only when all three analyst anchors are present and positive: low fair value, primary fair value, and high fair value. The primary analyst fair value is the weighted fair value when present, otherwise the external fair value.
- If both complete DCF and complete analyst sets exist, select DCF as the weighted EV source and keep analyst anchors as references. Do not blend sources silently.
- Compute weighted fair value using fixed MVP weights of 25/50/25 only for a complete three-anchor scenario set.
- Compute weighted upside, low/high range, and spread only for a complete three-anchor scenario set.
- One or two positive anchors are reference-only. They may be returned in `anchorReferences`, but the EV section status is `Sparse` with band `ReferenceOnly`, and no weighted value, weighted upside, range width, or spread is emitted.
- Zero or negative market price is `Unavailable`; fewer than three positive anchors is `Sparse`.

This section may use `Expected value range` wording, but UI copy must make it clear that the range is an estimate from current inputs, not a forecast or recommendation.

### Correlation Risk

Purpose: warn when the selected symbol is locally moving with other current tracked, watchlist, or opportunity symbols.

Inputs:

- selected symbol candle series for the selected chart range, from saved or already-loaded `HistoricalCandle` data;
- current tracked symbols, watchlist symbols, and opportunity rows, deduped by symbol;
- comparable candle series already present in `chartCache` or selected-detail memory;
- range identity and freshness metadata for every supplied series.

Design:

- Compute simple close-to-close return series from positive close prices.
- Align pair returns by candle `epochSeconds` when possible.
- If exact epoch alignment is unavailable for a pair, use only same-range ordered overlap and record that ordered-alignment mode in the pair reason. Ordered overlap still requires an explicit overlapping sample count.
- Require at least 30 overlapping return observations for a pair. Pairs below this gate are excluded from the band calculation and listed as insufficient when detail reasons are shown.
- Require at least three universe symbols with sufficient local history before the lens can be available.
- Require at least two valid pair correlations for `Low` or `Elevated`; a single valid pair below 0.85 is `Sparse`, while a single valid pair at or above 0.85 is `High` with a single-pair reason.
- Use absolute correlation for risk labels. Negative correlations may be displayed with their sign in detail, but the band is based on absolute local co-movement.
- `Low`: all valid pair absolute correlations are below 0.70.
- `Elevated`: one valid pair is at least 0.70 and below 0.85, with no second pair above 0.70.
- `High`: at least two valid pairs are at least 0.70, or one valid pair is at least 0.85.
- Provider/source redundancy, analyst concentration, technical redundancy, and stale/restored evidence concentration belong to Evidence Strength, not Correlation Risk.

Output:

- `riskBand`: `Low`, `Elevated`, `High`, `Sparse`, or `Unavailable`.
- top correlated symbols capped at three, ordered by descending absolute correlation and then symbol.
- each valid pair's symbol, signed correlation in bps or ten-thousandths, overlapping return count, selected range, and alignment mode.
- insufficient-pair reasons with observed overlap counts.

This is local return correlation only. It must not be called market beta, portfolio risk, diversification, or provider correlation.

### Trend Reliability

Purpose: grade whether a visible price, analyst-target, or estimate-history series has a usable least-squares fit.

Inputs:

- selected candle series for the selected chart range: `HistoricalCandle.closeCents` ordered by epoch;
- analyst target history from selected `SymbolRevision` rows, using the same preferred analyst target rule as the app domain;
- estimate history from already-loaded `IndexEstimatesReport` rows, grouped by `EstimateScenario` and using `ScenarioEstimate.impliedUpsideBps`;
- freshness/captured-at metadata for display reasons.

Design:

- Core computes a simple least-squares line `y = a + b*x` over normalized observation index or elapsed time for each selected loaded sample series only. Summary metadata, missing samples, unloaded chart ranges, and candidate-only row fields are never substitutes for the fitted samples.
- Core computes slope, R-squared, sample count, materiality, direction, and reliability band. Presenter and Compose never compute fit quality.
- `ChartRangeSummary` can provide freshness, latest close, or supporting display metadata, but it must not be used to fake a fit when the underlying selected candles are missing.
- Price trend requires at least 20 selected candles.
- Analyst target trend requires at least three target observations after core collapses repeated unchanged flat spans.
- Estimate trend requires at least three saved reports for the scenario being evaluated.
- Slope, intercept, fitted start/end values, residual sums, and R-squared must all be finite before an available trend state is emitted.
- Materiality is based on fitted end-to-end movement, not raw endpoint difference: `abs(fittedEnd - fittedStart) / max(abs(fittedStart), 1) >= 200 bps`.
- Meaningful direction is emitted only when fitted end-to-end movement is at least 200 bps and fit quality is not weak. `Reliable` and `Moderate` may emit `Up` or `Down`; `Noisy`, `Sparse`, `Insufficient`, and `Unavailable` emit `Unclear`, while `Flat` emits `Flat`. There is no directional weak/noisy state.
- `Reliable`: R-squared at least 0.70 and fitted end-to-end movement is at least 200 bps.
- `Moderate`: R-squared at least 0.40 and fitted end-to-end movement is at least 200 bps.
- `Noisy`: R-squared below 0.40 while fitted end-to-end movement is at least 200 bps; direction remains unclear because fit quality is weak.
- `Flat`: fitted end-to-end movement is below 200 bps, regardless of R-squared. A sub-200 bps fitted end-to-end movement is always `Flat`, never weak directional or noisy.
- `Insufficient`: sample count is below the target minimum.
- Zero x variance, constant y-series, zero total-sum-of-squares denominators, constant return series, and non-finite intermediate values must resolve to deterministic finite states: `Flat` when the minimum selected sample count is met and the observed series is valid but constant; otherwise `Sparse` or `Insufficient` with a typed reason code. Do not emit NaN, infinity, or sentinel doubles from core.
- Reliability is not bullishness. A reliable downtrend can have high fit reliability; an uptrend with poor R-squared is noisy.

Output:

- `priceTrend`: selected-range slope, R-squared, sample count, direction, and band.
- `analystTargetTrend`: collapsed target-history slope, R-squared, sample count, direction, and band.
- `estimateScenarioTrends`: per-scenario slope, R-squared, sample count, direction, and band.
- fixed-point outputs such as slope bps per window or per observation and R-squared bps.
- insufficient-data notes for each target, including the required sample minimum.

### Similar Setups

Purpose: identify current-universe nearest neighbors with similar evidence structure so the user can compare contexts.

Inputs:

- selected `SymbolDetail`, opportunity row data, evidence band, EV range band/spread, and trend band when already computed;
- current tracked symbols, watchlist symbols, and opportunity rows, deduped by symbol;
- in-memory `SymbolDetail`, opportunity scoring fields, chart summaries, and DCF cache values already present for comparable candidates;
- missing-feature metadata for each candidate.

Design:

- Build a bounded `QuantLensComparable` vector in the app shell for every currently known symbol with detail or row data. Exclude the selected target.
- Do not fetch missing charts, DCF, fundamentals, revisions, estimates, or history for comparables during selected-detail analysis.
- Use exactly these normalized feature slots when available. Every normalized value is clamped to `[0.0, 1.0]` before distance calculation, and the artifact-level weights are the PRD weights `3/3/2/1/1`. Decimal normalized equivalents are implementation-local only when mathematically identical after shared-feature renormalization.

| Feature | Raw Input | Normalization | Weight |
| --- | --- | --- | --- |
| `valuationUpside` | current upside bps or weighted EV upside bps | clip to `[-10_000, 30_000]`, then map linearly to `[0.0, 1.0]` | `3` |
| `evidenceStrength` | evidence band | `Unavailable/Sparse = missing`, `Mixed = 0.40`, `Provisional = 0.60`, `Strong = 1.00` | `3` |
| `opportunityComposite` | composite score bps, or normalized bucket aggregate when composite is absent | clip to `[0, 10_000]`, divide by `10_000` | `2` |
| `trendReliability` | trend band with selected loaded samples | `Unavailable/Sparse/Insufficient = missing`, `Noisy = 0.25`, `Flat = 0.50`, `Moderate = 0.75`, `Reliable = 1.00` | `1` |
| `evSpread` | EV range spread or uncertainty width bps | clip to `[0, 20_000]`, divide by `20_000`; lower normalized distance means similar uncertainty width, not better value | `1` |

- Compute distance only across features available for both symbols.
- Distance is weighted Euclidean over shared normalized features with weights renormalized to the shared feature set: `sqrt(sum(weight * delta * delta) / sum(sharedWeight))`. Return `distanceBps = round(distance * 10_000)` and optionally `similarityBps = 10_000 - distanceBps` after clamping.
- Require at least three shared features for a candidate pair. Fewer shared features excludes the pair.
- `Available` requires at least three candidates that pass the shared-feature gate; return exactly the top three nearest neighbors.
- Fewer than three passing candidates is `Sparse` and does not show a successful top-3 list. Exactly two qualifying candidates is specifically `Sparse` with `candidateCount = 2` so the presenter can explain that one more comparable is needed.
- Order by ascending distance, then descending composite score, then symbol for deterministic ties. Shared-feature count is not a tie-break.
- Return reason codes based only on current features, such as valuation gap close, same evidence band, or similar spread. Do not use historical outcomes, follow-through, future return, winner/loser labels, or date-based analog claims.

Output:

- `matches: List<SimilarSetupMatch>` capped to exactly three when available.
- `coverageStatus` and `candidateCount`.
- `distanceBps` or `similarityBps`, shared feature count, and normalized feature reason codes per match.
- missing-feature notes and excluded-candidate counts for detail diagnostics.

## App Domain Mapping

Extend `DashboardSnapshot` with selected-symbol Quant Lens output and bounded row summaries:

```kotlin
data class DashboardSnapshot(
    ...,
  val rowQuantLensSummaries: Map<String, QuantLensRowSummary> = emptyMap(),
    val selectedQuantLens: QuantLensReport? = null,
)
```

The snapshot should include a full report only when `selectedDetail` is present. Startup snapshots and list-only refresh snapshots should keep `selectedQuantLens` null. Row summaries may be present for visible tracked/opportunity/watch rows, but they must be derived only from row-present data and current in-memory detail fields. They are display hints, not complete reports.

No new app-domain business interpretation should be introduced for Quant Lens. If app-specific UI needs display groups, add presentation models in `DashboardViewModel`, not domain rules in `DashboardSnapshot`.

## Repository Boundary

`DefaultDashboardRepository` should construct `QuantLensInput` inside the existing state mutex after selected-detail inputs are available.

Recommended flow:

1. `bootstrap` and normal list snapshots return `selectedQuantLens = null` unless a detail route is already selected.
2. `ensureDetailLoaded(symbol, ...)` keeps current behavior: load revision history, hydrate saved pricing, fetch missing selected-detail chart ranges, and resolve selected-detail timeseries/DCF only as needed.
3. `snapshotLocked(...)` builds `selectedQuantLens` only for `selectedSymbol` and only from in-memory canonical inputs.
4. `snapshotLocked(...)` may build `rowQuantLensSummaries` for visible list rows from already-present row/detail fields, but this helper must not call selected-detail hydration, providers, SQLite history, DCF/timeseries resolution, or chart loading.
5. The repository supplies comparables from the current tracked/watch/opportunity universe, deduped by symbol, capped and lightweight.
6. The repository supplies correlation candle series only when they already exist in selected-detail memory or `chartCache`; missing comparable series remain missing.
7. The repository may memoize the selected report by a fingerprint.

Recommended fingerprint inputs:

- selected symbol;
- selected range;
- `SymbolDetail.lastSequence`, `updateCount`, market price, intrinsic value, external signal age/max age, and confidence;
- selected candle fingerprints for every range used: range, candle count, latest epoch, latest close, and an ordered hash of epoch/close pairs;
- latest revision timestamp, revision count, and an ordered hash of preferred analyst target values used by target-trend fitting;
- estimate-history fingerprint: profile name, report count, latest `computedAtEpochSeconds`, scenario count, and ordered scenario implied-upside values used by trend fitting;
- DCF fingerprint: source fingerprint when present, source enum, and bear/base/bull intrinsic values;
- comparable universe fingerprint: deduped symbol list plus an ordered hash of the actual comparable feature values used by Similar Setups and row summaries, including normalized feature slots, shared-feature availability flags, lens row `primaryStatus`, bands, freshness qualifiers, typed reason-code ids, visible row/detail data versions, and scoring model/version. Counts alone are not sufficient;
- correlation-series fingerprint: for each supplied comparable series, symbol, range, candle count, latest epoch, latest close, and close-series hash;
- Quant Lens model version and scoring version.

If the fingerprint has not changed, return the cached `QuantLensReport`. On profile switch, clear the cache with the other in-memory state.

## Persistence And Caching Stance

Initial implementation should not add SQLite schema or persist `QuantLensReport`.

Rationale:

- Quant Lens is deterministic from canonical persisted inputs.
- Persisting derived reports creates migration/versioning burden before model calibration stabilizes.
- Existing persisted revision payloads already hold enough historical truth for selected-detail reconstruction.
- Startup must stay bounded; restoring precomputed reports for the whole universe would push the design toward startup-wide analysis.

Allowed cache:

- Small in-memory `quantLensCache` in the repository keyed by selected symbol and fingerprint.
- No disk cache in the first implementation.
- No background precomputation for all tracked symbols.

Future persistence is acceptable only if there is an explicit audit/history requirement. If added later, persist `modelVersion`, input fingerprints, computed timestamp, and report payload, and still compute only on demand.

## Presentation Mapping

Extend `DashboardUiState` with a display state, not raw business calculations:

```kotlin
data class DashboardUiState(
    ...,
    val detailQuantLens: QuantLensUiState? = null,
)
```

Presenter responsibilities:

- map bands and statuses to display labels;
- map lens reason rows, evidence rows, sample counts, and source/freshness chips to compact UI rows;
- map missing/sparse states into neutral empty states;
- keep text professional and non-advice-oriented;
- preserve selected ticker navigation and replay state.

Presenter must not:

- recompute evidence strength;
- rank similar setups;
- derive expected-value anchors;
- classify correlation risk;
- infer primary stale, sparse, insufficient, or unavailable states from reason-code text;
- inspect raw candles beyond formatting already-prepared chart data.

Recommended route change:

```kotlin
enum class DetailSubtab {
    Snapshot,
    QuantLens,
    History,
}
```

If implementation risk is high, Quant Lens can first appear inside `Snapshot` under the existing valuation/fundamental sections, but the target architecture is a separate `QuantLens` subtab. A separate subtab keeps the detail screen dense without crowding current chart and replay workflows.

## UI Mapping

Add a passive `QuantLensContent` under `DetailScreen` that accepts `QuantLensUiState` and emits existing actions such as opening a similar symbol.

Recommended sections:

- Evidence Strength: compact band, score bar, source coverage chips, evidence rows, and copy that says `Evidence strength`, not `Buy signal`.
- Expected Value Range: horizontal range visualization with price marker, source anchors such as DCF bear/base/bull and analyst low/weighted/high, and unavailable states without smoothing missing anchors.
- Correlation Risk: local return-correlation band with top correlated symbols, sample counts, and alignment mode; emphasize saved/already-loaded local returns, not provider redundancy or portfolio risk.
- Trend Reliability: reliability band, direction, sample count, slope, and R-squared for the loaded target; never infer fit from summary-only data.
- Similar Setups: top three current-universe nearest neighbors with distance/similarity score, shared feature count, and current-feature reasons; tapping a match can dispatch `OpenDetail(symbol)` and reuse current selected-detail loading.

UI constraints:

- Compose must stay passive.
- No network, persistence, or domain-rule calls from UI.
- No visible instructional prose about how the feature works.
- No trading advice language such as `buy`, `sell`, `enter`, `exit`, `guaranteed`, or `expected return` as a recommendation.
- Empty states should be direct: `Not enough evidence`, `No comparable setups yet`, `Trend data unavailable`.

## On-Demand Computation Boundaries

Quant Lens must not run as a startup-wide pass.

Allowed computation points:

- selected detail open;
- selected detail refresh completion;
- selected detail range/history data hydration;
- opportunity scoring model change only if the report explicitly uses current opportunity rows or comparable ranking metadata.

Forbidden computation points:

- app bootstrap across all profile symbols;
- profile switch hydration across all symbols;
- background enrichment across all symbols solely for Quant Lens;
- Compose recomposition;
- every list row render.

Performance budget:

- Selected-symbol analysis should be cheap: O(1) for the selected report plus O(N) over currently known comparables, capped by the tracked universe already held in memory.
- Similar setups should cap candidate evaluation and output size. It may evaluate the current in-memory universe, but it must not load missing histories.
- Raw-candle scans are allowed only over selected-range or cached comparable candles already loaded before Quant Lens runs. Trend reliability must use real selected sample series; `ChartRangeSummary` is never a substitute for least-squares input samples. Correlation must not hydrate missing comparable candles.

## Data Availability Matrix

| Use Case | Required Minimum | Optional Enhancers | Sparse Behavior |
| --- | --- | --- | --- |
| Evidence Strength | positive market price plus usable valuation signal | DCF, chart summary, fundamentals, analyst breadth, source reliability metadata | explicit sparse/unavailable primary status with stale as freshness qualifier |
| Expected Value Range | positive market price plus three positive scenario anchors from DCF or analyst source | reference anchors from incomplete alternate source | one/two anchors are reference-only sparse; no weighted EV |
| Correlation Risk | at least 30 overlapping close-to-close returns per pair and at least three universe symbols with sufficient local history | already-loaded comparable candles and sample/freshness metadata | exclude insufficient pairs; sparse/unavailable with sample counts |
| Trend Reliability | selected loaded candles 20, collapsed target revisions 3, estimate reports 3 per scenario | freshness/captured-at metadata and selected range | finite flat/sparse/insufficient states; no summary-only fit |
| Similar Setups | at least three candidates with at least three shared normalized features | current row/detail/DCF/chart fields already in memory | fewer than three passing candidates is sparse with count; exactly two remains sparse; no historical outcomes |

## Math Safety And Determinism

Quant Lens math must be defensive at the core boundary:

- clamp fixed-point inputs before multiplication, weighting, normalization, and distance scoring;
- reject or downgrade non-finite floating-point intermediate results before returning any model value;
- handle zero variance in correlation inputs and constant return series as finite sparse/flat-style states with reason codes, not divide-by-zero errors;
- handle constant y-series and zero R-squared denominators in trend fitting as `Flat` when the selected sample minimum is met, otherwise `Insufficient`;
- saturate or clamp extreme fixed-point values into documented ranges before computing EV spreads, similar-setup features, materiality, and displayable bps outputs.

The core API must return only finite integers, enums, and nullable fields. NaN, infinity, overflow sentinel values, and exception-driven control flow are not valid Quant Lens outputs.

## Testing Strategy

### Core Tests

Use focused core unit tests around pure models and `QuantLensEngine`:

- model invariant tests for required symbol, score bounds, range ordering, and non-empty evidence rows when status is available;
- evidence-strength tests for independent bucket weighting, correlated-family collapse, stale provider downgrades, canonical degradation precedence, and missing-data neutrality;
- expected-value tests for complete DCF anchors, complete analyst fallback, DCF-over-analyst priority, one/two reference anchors, invalid market price, sparse anchors, and deterministic rounding;
- correlation-risk tests for close-to-close returns, epoch alignment, ordered same-range overlap, 30-sample pair gates, universe dedupe, sparse single pair, high single pair, elevated pair, high multiple pairs, low correlations, and negative signed display values;
- trend-reliability tests for loaded selected candles only, analyst flat-span collapse, estimate scenario histories, insufficient samples, reliable/moderate/noisy/flat labels, finite slope/R-squared rounding, 200 bps fitted movement materiality thresholds, weak-fit direction suppression, reliable downtrend distinction, and determinism;
- similar-setup tests for bounded feature normalization/clipping, ordinal mappings, feature weights, weighted Euclidean distance over shared features, missing-feature exclusion, three-shared-feature gates, sparse exactly-two-candidate behavior with count, exact top-three output, deterministic distance/composite/symbol tie-breaking, and no historical outcome fields;
- degenerate math tests for zero-variance correlation inputs, constant return series, constant y-series R-squared denominators, non-finite intermediate results, and extreme fixed-point values.

Keep one behavior per test. When a compound report requires checking several fields together, use soft assertions rather than many unrelated hard asserts.

### App Domain And Repository Tests

Add repository tests with fake providers/state store:

- bootstrap without selected symbol does not compute or expose Quant Lens;
- opening a detail computes Quant Lens from existing selected-detail data;
- opening a detail does not fetch data for similar-setup comparables;
- opening a detail does not fetch chart history for correlation comparables;
- restored chart summaries and DCF can produce explicit stale/sparse Quant Lens sections before live refresh;
- profile switch clears Quant Lens cache;
- selected-symbol refresh invalidates the fingerprint and recomputes;
- selected range, selected candle hash, revision target hash, estimates history, comparable universe, DCF fingerprint, scoring model, and scoring version invalidate the fingerprint;
- comparable-universe fingerprint changes when actual Similar Setups normalized feature values or row-summary lens states change, even if candidate counts stay the same;
- sparse selected detail produces explicit unavailable/sparse/insufficient states.

### Presentation Tests

Add ViewModel tests:

- maps `selectedQuantLens` into `detailQuantLens` only for the active detail route;
- maps `rowQuantLensSummaries` into bounded row chip state without requesting full reports;
- clears `detailQuantLens` on back navigation;
- preserves detail navigation and replay state when Quant Lens appears;
- uses neutral/non-advice display labels for all bands.

### Compose Tests

Add UI tests only for rendering and actions:

- Quant Lens tab appears for detail route;
- all five sections render with available data;
- sparse states render without crashes;
- similar setup tap dispatches open-detail action;
- UI does not render prohibited advice labels.

### Performance And Regression Tests

Add tests or fakes that count provider calls:

- startup does not call Quant Lens-selected detail hydration;
- row-summary creation does not call selected-detail hydration, provider fetch, SQLite history, chart load, or DCF computation;
- correlation comparables do not call Yahoo or SQLite history loaders for non-selected symbols;
- similar setups do not call Yahoo or SQLite history loaders for non-selected symbols;
- repeated snapshot with unchanged fingerprint reuses in-memory cached report.

Implementation validation should use the repository's Android verification path when code changes are later made. No Gradle or validation commands are part of this planning artifact creation.

## Risks And Mitigations

| Risk | Impact | Mitigation |
| --- | --- | --- |
| Probabilistic terminology implies more precision than the data supports | User may overtrust output | Name output `Evidence strength`; expose coverage/source reliability; avoid `win probability` language |
| Similar setups become expensive on large profiles | Slow detail open | Use lightweight vectors from in-memory details; cap output; no non-selected fetches |
| Provider/source redundancy inflates confidence | Misleading report | Collapse source families inside Evidence Strength; do not model provider redundancy as Correlation Risk |
| Provider data is sparse or unstable | Empty or inconsistent reports | Preserve explicit sparse/unavailable states and provenance; do not invent anchors |
| EV range overstates precision from one or two anchors | User sees a false weighted scenario | Require three positive anchors for weighted EV; show incomplete anchors as sparse/reference-only |
| Correlation risk is mistaken for portfolio risk | User may overread local co-movement | Label as local return correlation; show sample count and avoid portfolio/diversification language |
| Trend fit is faked from summaries | False reliability labels | Require loaded sample series and core least-squares slope/R-squared; summary-only data is insufficient |
| UI becomes too dense on phone | Poor usability | Use a separate Quant Lens subtab with compact sections and collapsible/detail rows if needed |
| Persisting derived reports too early causes stale model artifacts | Migration and trust problems | Do not persist reports initially; persist canonical inputs only |
| Trend reliability is mistaken for bullishness | Misinterpretation | Separate direction from reliability in model and UI |
| Row chips accidentally trigger expensive analysis | Startup/list performance regression | Use bounded `QuantLensRowSummary`; never compute full selected reports for every row render |
| Feature drifts into trading advice | Product and trust risk | Ban advice wording; frame all sections as evidence review and uncertainty |

## Implementation Partitions

### Partition 1: Core Model Scaffold

- Add Quant Lens models in `core/model`.
- Add `QuantLensEngine.analyze` returning explicit sparse/unavailable reports.
- Add canonical statuses/bands and bounded `QuantLensRowSummary` contracts.
- Add model invariant tests.

Exit criteria:

- Engine is pure.
- Sparse report works with only `SymbolDetail`.
- No app-layer changes yet.

### Partition 2: Evidence Strength And Expected Value Range

- Implement evidence strength with source-family collapse.
- Implement expected value range only from complete three-anchor DCF or analyst scenario sets.
- Add focused core tests for missing, analyst-only, DCF-only, DCF priority, reference-only one/two anchors, and invalid market price cases.

Exit criteria:

- No output claims trading advice.
- All financial/probability-like outputs are fixed-point integers.

### Partition 3: Correlation Risk And Trend Reliability

- Implement local close-to-close return correlation over selected/cached candle series with pair alignment and sample gates.
- Implement trend reliability and direction separation from loaded selected candles, selected revisions, and estimate history, with finite slope/R-squared guards and 200 bps fitted movement materiality.
- Use core least-squares slope/R-squared; reject summary-only fake fits.

Exit criteria:

- Reliable bearish and unreliable bullish cases are distinct.
- Correlation Risk uses local returns only and never source/provider redundancy.
- Trend labels expose sample count and fit quality or explicit insufficiency.

### Partition 4: Similar Setups

- Add comparable vector creation contract.
- Implement pure current-universe nearest-neighbor scoring with bounded normalized features, explicit clipping/ordinal/weight constants, weighted Euclidean distance, three-shared-feature gates, exactly-two sparse behavior, and deterministic tie-breaking.
- Add exact top-three cap and current-feature match reasons.

Exit criteria:

- No provider or persistence dependency in core.
- Comparables with fewer than three shared features are excluded.
- Similar setup output contains no historical outcome claims.

### Partition 5: Repository And Snapshot Wiring

- Extend `DashboardSnapshot` with `selectedQuantLens`.
- Build `QuantLensInput` in the repository only for selected detail.
- Add in-memory cache keyed by fingerprint.
- Include selected candle hashes/latest epoch/latest close/count, comparable universe/data versions, revisions, estimates, DCF fingerprint, scoring model/version, and Quant Lens model version in the fingerprint.
- Hash actual comparable normalized feature values and row-summary lens state values, not only candidate/watch/opportunity counts.
- Build only bounded row summaries for list rows.
- Clear cache on profile switch/reset.
- Add repository tests for startup avoidance, selected-detail computation, cache invalidation, row-summary cheapness, and no comparable fetches.

Exit criteria:

- Startup remains bounded.
- Opening detail is the feature activation point.
- SQLite schema remains unchanged.

### Partition 6: Presenter And UI

- Extend `DashboardUiState` with `detailQuantLens`.
- Add `DetailSubtab.QuantLens`.
- Add passive `QuantLensContent` and five sections.
- Add ViewModel and Compose tests for mapping, tab behavior, sparse states, and similar setup action.

Exit criteria:

- Compose contains no Quant Lens business calculations.
- UI labels are evidence/uncertainty language, not trading advice.
- Existing Snapshot and History behavior remains intact.

## Open Architecture Questions

1. Should Quant Lens consume the existing `PerformanceLens` evidence primitives directly, or should it define parallel Quant Lens evidence rows and map only shared concepts? Recommendation: define Quant Lens-specific rows first, then refactor toward shared primitives only after overlap is proven.
2. Should Similar Setups compare only opportunities or all tracked symbols? Recommendation: start with currently known opportunity rows plus selected watchlist/tracked details; expose candidate count and coverage.
3. Should the Quant Lens tab be visible when data is sparse? Recommendation: yes. Show the tab and explicit sparse states so unavailable data is not hidden.
4. Should DCF source distribution affect evidence strength? Recommendation: yes, but only as reliability metadata. Do not make source preference a hidden valuation rule.

## No-Go Conditions For Implementation

Stop and revisit architecture if any implementation path requires:

- loading full price history for all symbols at startup;
- fetching DCF/timeseries for all symbols solely to support Similar Setups;
- adding Quant Lens logic to Compose;
- persisting derived Quant Lens reports before model versioning is designed;
- displaying Quant Lens as buy/sell/hold advice;
- treating missing evidence as bearish evidence without an explicit rule and test.

## Final Stance

Go for implementation, with the selected-detail, pure-core architecture above.

The design fits the existing Android system because it reuses canonical inputs, keeps expensive work demand-driven, and preserves the functional core / imperative shell / strict MVP boundary. The main implementation discipline is to keep Quant Lens as evidence review and uncertainty explanation, not as trading advice or a startup-wide scoring pass.
