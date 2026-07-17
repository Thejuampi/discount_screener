# Android Centralized Screen-Data Provider/Calculator Redesign

Date: 2026-05-04
Owner: W14 / Spec Repair Integrator
Status: Ready for W10 cold-read re-review. Planning artifact only; no source, test, Gradle, docs, branch, commit, or PR side effects.

## 1. Title and Status

This artifact specifies the Android-first redesign for centralized screen-data providers and calculators so Opportunities, Tracked, Watchlist, Detail, Quant Lens, Estimates, and provider/system state present consistent values, labels, confidence, and provenance.

Implementation status is `Queued`. The required outcome is a single pure semantic projection boundary in Android core/domain, fed by the app/data repository shell, consumed by presentation mappers, and rendered by passive Compose screens.

## 2. Refined Requirement

The Android app must stop letting each screen or mapper independently choose financial anchors, source labels, confidence, DCF state, upside math, and chart-derived signals. Instead, the repository assembles facts once under its state mutex, calls a pure screen semantic projection/calculator boundary, and exposes one consistent screen data contract to presenters.

The redesign must preserve the existing functional core, imperative shell, strict MVP architecture:

- Android `core` owns pure financial calculators, policy rules, confidence decisions, source semantics, and reusable screen semantic models.
- Android `app/data` fetches from Yahoo/optional secondary providers, restores/persists SQLite state, assembles facts under `DefaultDashboardRepository` mutex, and invokes pure core logic.
- Android `app/domain` exposes repository/use-case contracts without recalculating screen semantics.
- Android `presentation` maps semantic outputs to UI state and copy.
- Compose renders the provided state only.

## 3. Scope and Non-goals

In scope:

- Android dashboard list rows: Opportunities, Tracked, Watchlist-derived rows, row decision chip, row trust/source signals, row Quant Lens summary.
- Android detail: Snapshot, Valuation, History, chart-derived technical summaries, Quant Lens, fair-value labels, source/provenance state.
- Android Estimates: index estimate scenarios, DCF coverage status, source distribution, confidence/status banners.
- Android system/provider state: live, restored, stale, unavailable, provider uncertain, disabled, retryable, not eligible, and issue summaries.
- Existing Android calculators and policies: `ReportingEngine`, `OpportunityEngine`, `ChartAnalysis`, `DcfAnalysisEngine`, `DcfSourceSelectionPolicy`, `QuantLensEngine`, and `IndexEstimatesEngine`.
- Fixture-driven consistency tests proving the same facts produce the same values across row, detail, Quant Lens, estimates, and provider state.

Out of scope:

- Desktop parity implementation, unless the implementation changes shared contracts. If shared contracts are changed, park and route a separate desktop parity/spec task.
- Enabling SEC EDGAR as default secondary or primary provider. The current Android default keeps the secondary timeseries provider disabled.
- Live provider sample collection beyond the existing SEC-primary evidence gate.
- New investment recommendation logic. `Act`, `Watch`, and `Avoid` semantics may be centralized, but not product-expanded.
- UI redesign for visual style beyond source/trust/provenance labels required for consistency.

## 4. Assumptions

- The current Android code already has first-slice DCF provenance, `DcfSourceCoordinator`, `DcfSourceSelectionPolicy`, Quant Lens horizon context, and DCF coverage summaries. The redesign consolidates screen semantics around those and removes duplicate downstream interpretation.
- Repository state remains the concurrency owner. The central projection is pure and receives immutable fact snapshots; it is not a singleton, cache, service locator, or provider fetcher.
- The implementation may add models/engines/tests inside Android `core` and Android `app` layers, but must not move business rules into Compose.
- Provider uncertainty is a confidence/provenance state, not an automatic action override.
- Existing fixed-point style stays intact: cents, bps, hundredths, and millis remain integer-based.

## 5. Final Design Decisions

1. Add `ScreenDataProjectionEngine` in Android `core`, package `com.discountscreener.core.engine`, with request/output models in `com.discountscreener.core.model`.
2. The exact projection contract is `ScreenDataProjectionRequest -> ProjectedDashboardData`. It is pure Kotlin: no coroutines, Android imports, repository imports, clock reads, network, SQLite, mutable singleton state, or service locator access.
3. `core/domain` in this artifact means the Android `core` module's domain semantics. App `domain` remains a repository/use-case contract layer and must not own financial interpretation rules.
4. Dependency direction is fixed: `app/data -> app/domain + core`, `app/domain -> core`, `presentation -> app/domain + core models`, `ui -> presentation`. `core` must not import `com.discountscreener.android.*`.
5. `DefaultDashboardRepository` remains the imperative shell. It assembles immutable facts under `stateMutex`, translates app/provider diagnostics into core facts, calls `ScreenDataProjectionEngine.project(request)` once per `DashboardSnapshot`, and exposes the projected result.
6. `DashboardSnapshot` gains `screenData: ProjectedDashboardData`; all row, detail, Quant Lens, estimates, and provider/system semantics must come from that field. Transitional legacy fields may remain only if populated from `screenData`.
7. Reuse existing engines through the projection boundary: `ReportingEngine`, `OpportunityEngine`, `ChartAnalysis`, `DcfAnalysisEngine`, `DcfSourceSelectionPolicy`, `QuantLensEngine`, `IndexEstimatesEngine`, and `DcfSourceCoordinator` policy outputs.
8. Remove duplicate interpretation now found around repository row summaries/fallbacks, `GetIndexEstimatesUseCase` separate reads, `QuantLensUiModels` upside/freshness recomputation, `DetailChartModel` EMA/MACD calculations, and `DetailScreen` valuation anchors.
9. Fair-value labels are projected by precedence. Analyst words (`Analyst`, `Weighted`, `Median`, `Mean`, `Low`, `High`, `Consensus`) are legal only for real analyst target data. DCF, restored, source-free, intrinsic, and retained model values use `Model fair value` or `Fair value unavailable`.
10. `ProviderUncertain` lowers dependent DCF, EV, estimate, and decision confidence, then normal decision policy runs. It must not directly force `Act`, `Watch`, or `Avoid`.
11. Estimates consume the projected current report from `DashboardSnapshot.screenData.estimates.report`; they do not recompute current coverage/status/source distributions from separate repository reads.
12. Chart technical meaning lives in core projection/`ChartAnalysis`; Compose owns drawing only.
13. SEC secondary provider remains disabled by default. `defaultSecondaryTimeseriesProvider()` stays `null`; changing that requires the separate five-sample SEC evidence artifact and release decision.
14. Release readiness requires the required fixture IDs in this artifact, Android validation evidence, mutation or manual mutation evidence, and manual visible-flow QA across list, detail, Quant Lens, estimates, and provider/system state.

## 6. Acceptance Criteria

AC-1. Android scope is explicit: implementation affects Android screen semantics only unless shared contracts are deliberately changed and separately routed.

AC-2. A single pure projection boundary accepts repository-assembled facts and returns row, detail, Quant Lens, estimates, and provider/system semantics used by all Android screens.

AC-3. `DefaultDashboardRepository` fetches, restores, persists, caches, and assembles facts under its mutex, but does not own financial interpretation rules that belong in core calculators/policies.

AC-4. Existing calculators are inventoried and either reused directly or wrapped by the projection boundary; no duplicate opportunity, DCF, EV/upside, EMA, MACD, coverage, or confidence rule remains in UI code.

AC-5. Opportunities, Tracked, and Watchlist row surfaces use the same projected market price, fair value, upside, confidence, freshness, trust/source state, row explanation, row decision, and Quant Lens summary inputs.

AC-6. Detail Snapshot, Valuation, History, and Quant Lens use the same projected anchors and provenance as rows and estimates for the selected symbol.

AC-7. Non-analyst fallback fair value is labeled `Model fair value`; analyst-specific labels are used only when actual analyst target/consensus data backs them.

AC-8. `ProviderUncertain` is represented in projected provenance and lowers confidence for dependent DCF, EV, estimates, and decisions, without mechanically changing `Act`, `Watch`, or `Avoid`.

AC-9. Estimates display projected DCF coverage status and concise source distribution/status; Compose does not compute coverage thresholds or source counts.

AC-10. SEC secondary provider remains disabled by default in app composition unless a separate SEC-primary evidence gate explicitly approves a change.

AC-11. Fixture-driven consistency tests prove that one fixture yields matching row/detail/Quant Lens/estimate values, labels, confidence, and provenance.

AC-12. Strict MVP remains intact: presentation maps projected semantics to UI state, and Compose renders only provided state/copy.

AC-13. Warm-start/restored and legacy source-free data are never relabeled as live provider data; restored/stale/source-free status remains visible where it can affect trust.

AC-14. Release gate includes Android validation plus manual QA of the visible flows named in this artifact.

## 7. Task Completion Frontier

User-requested outcome: A practical Android redesign spec for centralized data providers/calculators so every screen presents consistent values, labels, confidence, and provenance.

In-scope files/areas: Android `core` models/engines/tests; Android `app/domain` dashboard contracts/use cases; Android `app/data/repository` fact assembly and caches; Android `app/presentation/dashboard` UI state mappers; Android `app/ui/dashboard` passive rendering of rows/detail/estimates/system state; Android validation scripts only for verification.

Out-of-scope files/areas: Desktop implementation; shared contracts unless deliberately routed; Gradle configuration; release signing; source provider default changes; docs outside implementation-required user-visible updates; unrelated refactors.

Required candidate inventory: `ReportingEngine`, `OpportunityEngine`, `ChartAnalysis`, `DcfAnalysisEngine`, `DcfSourceSelectionPolicy`, `QuantLensEngine`, `IndexEstimatesEngine`, `DcfSourceCoordinator`, `DefaultDashboardRepository`, `GetIndexEstimatesUseCase`, `QuantLensUiModels`, `DetailChartModel`, `DetailScreen`, `EstimatesScreen`, `DashboardSnapshot`, provider/system state models, persistence restore of DCF/revisions/raw captures.

WQ-01 is a required inventory exit gate before WQ-02. WQ-02 must not start until every candidate above has an explicit decision: reuse, wrap, move, delete duplicate, or defer through a park-and-route note.

Required work queue: Complete WQ-01 through WQ-12 below, in order unless a dependency allows safe parallel work.

Required validation: New and updated fixture tests, `./gradlew :core:test`, app unit tests when SDK is configured, `scripts/validate-android.ps1`, and mutation/manual mutation around projection rules that affect confidence/source/coverage labels.

Required QA/review: Manual visible-flow QA on Opportunities, Tracked, Watchlist, Detail Snapshot, Detail Valuation, Detail History, Detail Quant Lens, Estimates, provider/system state, warm start, profile switch, no-analyst data, restored data, and provider-uncertain fixture state.

Completion condition: All ACs are satisfied, WQ evidence is attached, validation passes or documented environment blockers are accepted, manual QA notes/screenshots exist, and no Android screen computes an inconsistent financial/source/trust semantic outside the central projection boundary.

## 8. Architecture Boundary and Data Flow

Target boundary:

```text
Yahoo/optional provider, SQLite, in-memory caches
    -> app/data repository fact assembly under stateMutex
    -> core ScreenDataProjectionEngine
    -> app/domain DashboardSnapshot.screenData
    -> presentation UI-state mapping
    -> passive Compose rendering
```

### 8.1 Module Ownership

| Module / package | Owns | Must not own |
| --- | --- | --- |
| `apps/android/core`, `com.discountscreener.core.engine` | `ScreenDataProjectionEngine`, projection policy orchestration, confidence lowering, fair-value precedence, estimate coverage/status, provider-state classification, chart semantic wrappers. | Android APIs, repositories, SQLite, network, provider clients, Compose state. |
| `apps/android/core`, `com.discountscreener.core.model` | `ScreenDataProjectionRequest`, `ProjectedDashboardData`, supporting projected models/enums, immutable facts, fixed-point values. | App/data diagnostics or UI copy layout decisions. |
| `apps/android/app/domain` | `DashboardSnapshot`, repository/use-case contracts, app-facing response wrappers. | Financial anchor selection, confidence thresholds, DCF coverage rules, chart technical meaning. |
| `apps/android/app/data` | Provider fetching, persistence, cache hydration, generation safety, raw diagnostics, fact assembly under `stateMutex`, translating app/data types into core facts. | Row decisions, trust labels, fair-value labels, Quant Lens row summaries, estimate thresholds/source counts, EMA/MACD signal meaning. |
| `apps/android/app/presentation` | Mapping projected enums/values to UI state, ordering, formatted copy, severity colors. | Recomputing financial/source/trust semantics from raw details. |
| `apps/android/app/ui` | Passive Compose rendering, layout, gestures, charts as drawings. | Business rules, provider-state classification, fair-value/source/confidence calculations. |

Dependency direction is fixed: `app/data -> app/domain + core`, `app/domain -> core`, `presentation -> app/domain + core models`, `ui -> presentation`. `core` must not import `com.discountscreener.android.*`.

### 8.2 Projection Contract

The core engine shape is exact:

```kotlin
package com.discountscreener.core.engine

class ScreenDataProjectionEngine {
    fun project(request: ScreenDataProjectionRequest): ProjectedDashboardData
}
```

The request/output schema is exact enough for implementation; workers may add fields only when they preserve the same ownership and invariants.

```kotlin
data class ScreenDataProjectionRequest(
    val profile: ProjectionProfileFacts,
    val route: ProjectionRoute,
    val nowEpochSeconds: Long,
    val trackedSymbols: List<String>,
    val watchlistSymbols: Set<String>,
    val detailsBySymbol: Map<String, SymbolDetail>,
    val candidateRows: List<CandidateRow>,
    val chartCandles: Map<SymbolRangeKey, List<HistoricalCandle>>,
    val chartSummariesBySymbol: Map<String, Map<ChartRange, ChartRangeSummary>>,
    val dcfBySymbol: Map<String, DcfAnalysis>,
    val revisionsBySymbol: Map<String, List<SymbolRevision>>,
    val alertsBySymbol: Map<String, List<AlertEvent>>,
    val issues: List<IssueRecord>,
    val symbolStateBySymbol: Map<String, ProjectionSymbolState>,
    val baselines: ProjectionComparisonBaselines,
)

data class ProjectionRoute(
    val filter: ViewFilter,
    val selectedSymbol: String?,
    val selectedRange: ChartRange,
    val replayOffset: Int,
    val opportunityScoringModel: OpportunityScoringModel,
    val volumeProfileBinCount: Int = 24,
)

data class ProjectedDashboardData(
    val trackedRows: List<ProjectedTrackedRow>,
    val opportunityRows: List<ProjectedOpportunityRow>,
    val candidateRows: List<CandidateRow>,
    val selectedDetail: ProjectedDetailData?,
    val estimates: ProjectedEstimatesData,
    val providerState: ProjectedProviderState,
)
```

Required supporting model families: `ProjectionProfileFacts`, `ProjectionSymbolState`, `ProjectionComparisonBaselines`, `SymbolRangeKey`, `ProjectedFairValueAnchor`, `ProjectedTrustSignal`, `ProjectedRowDecision`, `ProjectedRowFreshness`, `ProjectedRowExplanation`, `ProjectedChartData`, `ProjectedEstimatesData`, and `ProjectedProviderState`.

`chartSummariesBySymbol` may carry persisted/restored chart-summary facts. For rendered detail charts, when `chartCandles` are present for the selected symbol/range, projection derives the displayed chart semantics from those candles; UI receives the projected result and does not recompute EMA/MACD meaning.

### 8.3 Projection Invariants

| Invariant | Rule |
| --- | --- |
| Purity | Output depends only on `ScreenDataProjectionRequest`; all time comes from `nowEpochSeconds`. |
| Symbol validity | Blank symbols are rejected at the boundary. Row outputs are keyed by request symbols and sorted by projection policy, not UI. |
| Fixed-point values | Cents, bps, hundredths, and millis stay integer-based. |
| Label authority | Projection owns all fair-value display labels, source labels, compact labels, provenance states, and analyst-history eligibility. |
| Confidence | `ProviderUncertain` lowers dependent confidence one step: `High -> Provisional`, `Provisional -> Low`, `Low -> Low`, `Unavailable -> Unavailable`. |
| Decisions | `ProviderUncertain` adds caution/trust state and uses lowered confidence; it never directly maps to `Act`, `Watch`, or `Avoid`. |
| Estimates | DCF coverage thresholds stay in core: `0 = Unavailable`, `<2500 = LowConfidence`, `<5000 = Partial`, `<9500 = Provisional`, otherwise `Ready`. |
| Restored data | Restored and source-free data must never be relabeled as live provider data. |
| App/data boundary | App/data types such as `ProviderDiagnostic` are translated into core issue/provenance facts before projection. |
| Chart semantics | EMA/MACD/replay/volume-profile numeric meaning is projected; UI receives render-ready semantic data. |

### 8.4 DashboardSnapshot Relation

`DashboardSnapshot` stays in app `domain` as the repository response wrapper and adds `screenData: ProjectedDashboardData`. It is the only source for row, detail, Quant Lens, estimate, and provider/system semantics. Existing `trackedRows`, `opportunityRows`, `selectedDetail`, and `selectedQuantLens` may remain only during migration and must be populated from `screenData` or removed. They must not be computed separately.

`GetIndexEstimatesUseCase` must not call `trackedSymbolDetails()`, `dcfSnapshot()`, and `currentSnapshot()` separately to recompute a current estimates report. It must return `DashboardSnapshot.screenData.estimates.report` from a supplied/current snapshot or be removed.

### 8.5 Repository Rules

| Level | Rule |
| --- | --- |
| May | Fetch providers, hydrate SQLite, cache charts/DCF, merge persistence, record issues, normalize raw route/filter values, and persist projected estimate history. |
| Must | Build `ScreenDataProjectionRequest` under `stateMutex`; include restored/stale/refreshed/source-free state; call `ScreenDataProjectionEngine.project(request)` once per `DashboardSnapshot`; expose `screenData`; keep SEC secondary disabled by default. |
| Must not | Compute row decisions, trust labels, fair-value labels, Quant Lens row summaries, estimate coverage/status, source distributions, EMA/MACD signals, provider confidence lowering, or chart technical meaning outside core projection. |
| Must not | Pass app/data types into core; translate raw diagnostics into core issue/provenance facts first. |

### 8.6 Estimates Integration Contract

Current Estimates consume `DashboardSnapshot.screenData.estimates.report`. Estimate history persistence may remain a separate repository/use-case path, but it stores the projected `IndexEstimatesReport` only.

| Recompute current estimates when this changes | Do not recompute current estimates for this |
| --- | --- |
| Profile, tracked symbols, symbol details, market caps, DCF values, DCF resolver states, DCF provenance, material estimate fingerprint. | Query text, selected detail symbol, chart range, history subview, replay offset, tab changes. |

## 9. UX/Policy/State Contract

### 9.1 Fair-Value Source and Label Precedence

`ProjectedFairValueAnchor` exposes `valueCents`, `role`, `displayLabel`, `compactLabel`, `sourceLabel`, `provenanceState`, `trustReason`, and `canPopulateAnalystHistory`. Compose renders projected fields only.

| Precedence | Input condition | Role | `displayLabel` | `compactLabel` / `sourceLabel` | Row trust chip | Analyst history |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | Valid `weightedExternalSignalFairValueCents` from analyst target feed | `AnalystWeightedTarget` | `Analyst fair value` | `Analyst weighted` / `Weighted target` | None unless coverage thin/unknown | Yes |
| 2 | Valid consensus target with statistic `Median` | `AnalystMedianTarget` | `Analyst fair value` | `Analyst median` / `Median target` | None unless coverage thin/unknown | Yes |
| 3 | Valid consensus target with statistic `Mean` | `AnalystMeanTarget` | `Analyst fair value` | `Analyst mean` / `Mean target` | None unless coverage thin/unknown | Yes |
| 4 | Valid consensus target with unknown statistic | `AnalystConsensusTarget` | `Analyst fair value` | `Analyst consensus` / `Consensus target` | None unless coverage thin/unknown | Yes |
| Reference | Valid low/high target range | `AnalystLowTarget` / `AnalystHighTarget` | Not primary | `Low target` / `High target` | None | No, except estimate range display |
| 5 | Selected live DCF with `baseIntrinsicValueCents > 0` | `DcfBaseModel` | `Model fair value` | `DCF model` / `DCF base - Yahoo Finance` or `DCF base - SEC EDGAR` | `No analyst target` when no valid analyst target fields exist; otherwise `Model value` | No |
| 6 | DCF resolver `ProviderUncertain` | `UncertainDcfModel` | `Model fair value` only for explicit retained value; otherwise `Fair value unavailable` | `DCF model` / `DCF base - source uncertain` | `Source uncertain` | No |
| 7 | DCF resolver `RestoredOnly`, non-source-free | `RestoredDcfModel` | `Model fair value` | `Saved model` / `Saved DCF base` | Freshness chip unless another trust reason applies | No |
| 8 | Legacy/source-free DCF where source is null, `Unknown`, or `LegacySourceFreePayload` | `SourceFreeModel` | `Model fair value` | `Saved model` / `Source unknown - saved model` | `Source unknown` | No |
| 9 | Valid `intrinsicValueCents` fallback | `IntrinsicModel` | `Model fair value` | `Intrinsic model` / `Intrinsic model` | `No analyst target` when no valid analyst target fields exist; otherwise `Model value` | No |
| Last | No valid candidate | `Unavailable` | `Fair value unavailable` | `No fair-value source` | Detail shows `No fair value`; rows omit if metrics absent | No |

`Weighted`, `Median`, `Mean`, `Low`, `High`, `Consensus`, and `Analyst` labels are forbidden for intrinsic, DCF, restored, source-free, retained, or unavailable values.

### 9.2 ProviderUncertain Truth Table

| Affected output | Input confidence | Projected confidence | Decision behavior | Required provenance |
| --- | --- | --- | --- | --- |
| DCF, EV, estimate, row decision depends on uncertain provider input | `High` | `Provisional` | Run normal policy with lowered confidence | `Source uncertain` |
| Same | `Provisional` | `Low` | Run normal policy with lowered confidence | `Source uncertain` |
| Same | `Low` | `Low` | Run normal policy with lowered confidence | `Source uncertain` |
| Same | `Unavailable` | `Unavailable` | No decision unless another valid anchor supports it | `Source uncertain` or `Fair value unavailable` |
| Output does not depend on uncertain provider input | Any | Unchanged | Run normal policy | Provider uncertainty may appear in system state only |

Forbidden implementation shortcut: `ProviderUncertain -> Watch`, `ProviderUncertain -> Avoid`, or `ProviderUncertain -> no action` as a direct rule. Projection first lowers confidence for dependent outputs, then applies the normal `Act`/`Watch`/`Avoid` rules.

### 9.3 Compact Row Trust and Source Visibility

Rows always render one projected freshness chip: `Loading`, `Updating`, `Updated`, `Restored`, `Stale`, or `Issue`. Rows may render at most one trust/source chip.

| Priority | Condition | Row chip | Visibility rule |
| --- | --- | --- | --- |
| 1 | Blocking provider issue and no usable detail | None | Show `Issue` freshness and provider issue text; omit trust chip. |
| 2 | Primary or dependent value has `ProviderUncertain` | `Source uncertain` | Always show, even with stale/restored freshness. |
| 3 | Primary value is source-free or unknown legacy | `Source unknown` | Always show. |
| 4 | Primary fair value role is DCF, intrinsic, or model and no valid analyst target fields exist | `No analyst target` | Show when no higher-priority chip applies. |
| 5 | Primary fair value role is DCF, intrinsic, or model | `Model value` | Render only when no higher-priority chip applies and analyst-target absence is not the key trust reason; otherwise keep it as a model-derived trust reason behind the selected higher-priority chip. |
| 6 | Analyst target coverage count is `1..2` | `Thin coverage` | Show only for actual analyst target roles. |
| 7 | Analyst target coverage count is null/unknown | `Coverage unknown` | Show only for actual analyst target roles. |
| 8 | Live analyst target coverage is `>= 3` | None | Freshness chip is sufficient. |

Rows keep one-line scannability. Add only one trust/source chip, cap Quant Lens row chips at three, and use single-line ellipsis for chips. On narrow widths, hide in this order: freshness time, explanation chip, rank chip, valuation-change chip, third Quant Lens chip, watchlist chip. Never hide `Issue`, `Restored`, `Stale`, `Source uncertain`, or `Source unknown`; if needed, render them as a one-line secondary row.

### 9.4 Full Provenance Visibility

| Surface | Required provenance slots | Exact display rules |
| --- | --- | --- |
| Detail Snapshot | `displayLabel`, value, `sourceLabel`, `provenanceState`, as-of/captured time when known, analyst coverage count when analyst. | Headline says `Analyst fair value`, `Model fair value`, or `Fair value unavailable`. Source chip uses the projected `sourceLabel`. |
| Detail Valuation | Same as Snapshot, plus reference anchors. | Low/high labels are `Low target` and `High target`. DCF bear/base/bull labels are model labels, never analyst labels. |
| Detail History | Target source, coverage, revision time, provenance state. | Analyst target history includes only analyst target roles. Model, DCF, restored source-free, and intrinsic values are excluded. Empty copy: `No analyst target history` and `Current fair value is model-based; analyst target history appears after Yahoo supplies target data.` |
| Quant Lens | EV source, freshness qualifier, anchor count, provider/trust qualifier. | Footer chips include affected source/state: `DCF model`, `Analyst targets`, `Intrinsic model`, `3 scenarios`, `Saved data`, `Stale`, `Source uncertain`, `Source unknown`. Restored/stale/source-free EV ranges set the rail stale flag. |
| Estimates | Coverage status, coverage ratio, source distribution, scenario source. | DCF scenarios show model source. Analyst scenarios show `Analyst low target` / `Analyst high target`. Restored, source-free, uncertain, unavailable, disabled, and not-eligible counts are visible and are not counted as live covered DCF. |
| Provider/System | Category, copy, retryability, affected symbols/counts. | Provider issue center groups by the categories in section 9.6 and does not show superseded refreshes as user errors. |

### 9.5 Chart Semantics vs Rendering Boundary

| Core projection owns | UI/presentation owns |
| --- | --- |
| Replay window clamping and visible candles. | Canvas size, path coordinates, gestures, colors. |
| EMA 20/50/200 series and latest values. | Axis text formatting and tick placement. |
| MACD, signal, histogram series. | Pixel scaling from projected numeric domains. |
| Technical signal enums, cross state, bullish/bearish bias. | Copy/color mapping for projected enums. |
| Price domain including OHLC and EMA values. | Drawing candles, lines, legends. |
| Volume max and fixed-bin volume profile. | Physical row height and visual bar width. |
| Chart availability/status. | Empty-state layout. |

`DetailChartModel` may remain as a rendering adapter over `ProjectedChartData`; it must stop recomputing EMA/MACD semantics.

### 9.6 Provider/System State Categories and Copy

| Category | Source states/reasons | User-visible copy | Behavior |
| --- | --- | --- | --- |
| `Live` | Selected provider live | `Live provider data` | Counts as covered; no trust warning. |
| `Restored` | `RestoredOnly`, warm start | `Restored from saved data` | Visible on rows/detail/lens; not live coverage. |
| `Stale` | Stale freshness, stale fiscal/provider reason | `Stale provider data` | Visible where value is shown; confidence lowered. |
| `ProviderUncertain` | Provider disagreement, uncertain resolver state | `Sources disagree; confidence lowered` | Not an action override; not live DCF coverage. |
| `ParseUncertain` | Parse uncertain/provider format changed | `Provider format uncertain` | Warning; dependent confidence lowered. |
| `Unavailable` | Network, HTTP, rate, empty retryable | `Provider unavailable; retry pending` | Retryable; no live coverage. |
| `NotEligible` | Unsupported, non-US, missing inputs, non-positive FCF, insufficient periods | `Not eligible for DCF` | Terminal until inputs change; excluded from eligible denominator when policy says so. |
| `Disabled` | Provider disabled/no enabled providers | `Provider disabled` | Blocked until configuration changes; SEC remains disabled by default. |
| `Superseded` | Generation superseded/cancelled | `Refresh superseded` | Do not show as user error; keep newer/prior projected state. |
| `SourceUnknown` | Legacy source-free payload | `Saved value has no provider source` | Full provenance warning; never live/analyst coverage. |

### 9.7 Empty, Sparse, Stale, Error, and Restored Behavior

| Component | Empty/unavailable | Sparse/partial | Stale/restored/source-free | Error |
| --- | --- | --- | --- | --- |
| Rows | `Loading`; metrics text `Waiting for real Yahoo data`. | `Thin coverage` or `Coverage unknown` chip. | `Restored`/`Stale` freshness; add `Source unknown` for source-free. | `Issue` chip plus provider issue text; no decision. |
| Detail Snapshot/Valuation | `Fair value unavailable`; hide upside. | Show value with lowered confidence/provenance. | Full provenance strip; source-free says `Source unknown - saved model`. | Error banner/status; preserve last-good only if projected. |
| Detail History | `No analyst target history`. | `Only one analyst-target snapshot`. | Restored analyst history may show `Saved data`; source-free model excluded. | History error separate from fair-value source. |
| Quant Lens | `No estimate` or unavailable section. | `Estimate limited`, `Few signals`, or `Not enough data`. | Footer `Saved data`, `Stale`, or `Source unknown`; stale rail. | Section unavailable with reason chip. |
| Estimates | `No estimates available`, `No price data available yet`, or `Not enough DCF data`. | `Low confidence`, `Partial`, `Provisional`. | Restored/source-free/uncertain appear in distribution and are excluded from live covered count. | Retryable/unavailable banner with distribution. |
| Provider/System | `Provider unavailable`. | `Provider format uncertain` or `Low coverage`. | `Restored from saved data`, `Stale provider data`, or `Source unknown`. | Issue center groups by category and retryability. |

## 10. Implementation Work Queue

| ID | Work Item | Owner Worker | Depends On | Status | Validation Required | QA Required | Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- |
| WQ-01 | Required inventory exit gate: mark each existing calculator/hotspot as reuse, wrap, move, delete duplicate, or park-and-route defer. | Planning/Architecture Worker | None | Required before WQ-02 | Inventory checklist in PR notes. | Reviewer confirms no hotspot skipped. | File/path inventory with decision per candidate. |
| WQ-02 | Define `ScreenDataProjectionRequest -> ProjectedDashboardData`, `ScreenDataProjectionEngine`, supporting core models, invariants, and `DashboardSnapshot.screenData` relation exactly as sections 8.2-8.4 specify. | Core Projection Worker | WQ-01 exit gate | Queued | Core model/engine tests compile and cover invariants. | Architecture review for MVP boundary and dependency direction. | `ScreenDataProjectionEngineTest` or equivalent. |
| WQ-03 | Centralize row semantics for Tracked, Opportunities, Watchlist-derived rows, trust notes, explanations, decisions, and Quant Lens row summary inputs. | Core Projection Worker | WQ-02 | Queued | Fixture tests for row/detail consistency and provider uncertainty. | Manual list QA on Opportunities/Tracked/Watchlist. | Row fixture snapshots and screenshots/notes. |
| WQ-04 | Centralize detail snapshot, valuation anchors, fair-value labels, and provenance semantics. | Core Projection Worker | WQ-02 | Queued | Tests for model vs analyst label selection and restored/source-free state. | Manual Detail Snapshot/Valuation QA. | Detail fixture tests and before/after QA notes. |
| WQ-05 | Route chart technical semantics through `ChartAnalysis`/projection and leave UI/presentation as rendering adapters only. | Chart/Core Worker | WQ-02 | Queued | Chart analysis tests for EMA/MACD/replay summaries and price domains including EMA values. | Manual detail chart QA across ranges/replay. | Chart fixture tests. |
| WQ-06 | Align Quant Lens inputs and presentation with projected anchors/provenance; remove presentation-side upside/freshness reinterpretation. | Quant Lens Worker | WQ-03, WQ-04 | Queued | `QuantLensEngineTest` and `QuantLensUiModelsTest` fixture updates. | Manual Detail Quant Lens QA. | Quant Lens fixture tests. |
| WQ-07 | Make Estimates consume `DashboardSnapshot.screenData.estimates.report`; expose coverage/source distribution/status and remove current-report recomputation from separate repository reads. | Estimates Worker | WQ-02, WQ-03 | Queued | `IndexEstimatesEngineTest` plus use-case/repository consistency tests. | Manual Estimates QA for Ready/Provisional/Low/Unavailable. | Estimate fixture tests and coverage distribution evidence. |
| WQ-08 | Centralize provider/system state semantics, including restored, stale, retryable, disabled, provider uncertain, not eligible, and superseded. | Provider State Worker | WQ-02 | Queued | Provider-state fixture tests and repository generation tests. | Manual System/provider issue QA. | Provider state matrix test. |
| WQ-09 | Refactor `DefaultDashboardRepository` to build one coherent request under mutex, invoke projection once per snapshot, expose `screenData`, and remove screen-facing semantic helpers from row/detail/estimate paths. | Repository Worker | WQ-02 to WQ-08 | Queued | Repository tests for snapshot consistency, warm start, profile switch, generation safety. | Manual refresh/profile-switch QA. | Repository fixture tests. |
| WQ-10 | Refactor presentation and Compose so mappers format projected enums/values and Compose renders only. | Presentation/UI Worker | WQ-09 | Queued | UI mapper tests; no Compose financial/source calculations remain. | Manual row/detail/estimates visual QA. | Mapper tests and reviewer grep notes. |
| WQ-11 | Add the required FX fixture suite: `FX-LIVE-ANALYST`, `FX-LIVE-MODEL-ONLY`, `FX-PROVIDER-UNCERTAIN`, `FX-RESTORED`, `FX-NOT-ELIGIBLE`, `FX-UNAVAILABLE`, `FX-SOURCE-FREE-LEGACY`, `FX-SECONDARY-DISABLED`. | QA/Test Worker | WQ-03 to WQ-10 | Queued | Fixture suite passes; one assertion per test or SoftAssertions for grouped invariants. | Reviewer walks fixture coverage and manual harness. | Test file list, fixture matrix, expected outputs, mutation evidence. |
| WQ-12 | Run release validation and manual QA gate. | Release QA Worker | WQ-01 to WQ-11 | Queued | `scripts/validate-android.ps1`; document SDK blockers; mutation/manual mutation notes. | Visible-flow QA completed. | Command output summary, QA checklist, screenshots/notes. |

## 11. Test Strategy and Manual QA Plan

### 11.1 Required Fixture Matrix

Fixtures use fake repository inputs, decoded persisted state, or pure core model objects. Do not fake Yahoo/SEC parser payloads unless parser behavior is in scope and five live upstream samples are attached.

| ID | Input state | Expected row/detail/Quant Lens | Expected estimates/provider |
| --- | --- | --- | --- |
| `FX-LIVE-ANALYST` | Symbol `W13L`; market `10000`; model intrinsic `12000`; analyst low `11000`; weighted external analyst target `12500`; no consensus statistic; analyst high `14000`; analyst count `12`; DCF `YahooFinance`, `Selected`, bear/base/bull `11000/12500/16000`; market cap `1000`; freshness `Updated`. | Row price `$100.00`; fair value `$125.00`; upside `+2500 bps`; primary display label `Analyst fair value`; compact/source labels `Analyst weighted` / `Weighted target`; confidence `High`; provenance `Live - Yahoo`; no trust chip; decision `Act`. Detail uses the same `$125.00` anchor, primary display label `Analyst fair value`, and source/provenance label `Weighted target`. Quant Lens EV range is `+10.0%..+60.0% upside`, provenance `Live - Yahoo`. | Weighted price `10000`; Bear/Base/Bull DCF prices `11000/12500/16000`; implied upside `+1000/+2500/+6000 bps`; Analyst Low/High `11000/14000`, `+1000/+4000 bps`; DCF coverage `1/1`, `10000 bps`, `Ready`; distribution `yahoo=1`, all other counts `0`. |
| `FX-LIVE-MODEL-ONLY` | Symbol `W13M`; market `10000`; no analyst target fields; analyst count null; model/DCF fair value `13000`; DCF `YahooFinance`, `Selected`, bear/base/bull `11500/13000/15000`; market cap `1000`; freshness `Updated`. | Row price `$100.00`; fair value `$130.00`; upside `+3000 bps`; primary label `Model fair value`; confidence `Provisional`; provenance `Live - Yahoo`; trust/source chip `No analyst target`; decision `Watch`. Detail and History must not show `Analyst`, `Mean`, `Low`, `High`, or `Consensus` for the primary value. Quant Lens EV range `+15.0%..+50.0% upside`. | DCF coverage `1/1`, `Ready`, `yahoo=1`; Bear/Base/Bull DCF `11500/13000/15000`; Analyst Low/High coverage `0`, weighted price `0`, upside `0`. |
| `FX-PROVIDER-UNCERTAIN` | Symbol `W13U`; market `10000`; no analyst target; Yahoo and SEC fake timeseries both otherwise usable but materially disagree above `1000 bps`; `DcfSourceSelectionPolicy` returns `ResolverState.ProviderUncertain`, reason `ProviderDisagreement`; market cap `1000`. | Row keeps a model anchor only if projection labels it `Model fair value` with provenance `Source uncertain`; confidence is lowered to `Low` or `Provisional`, never `High`; decision is not forced to `Avoid` solely by uncertainty. Detail and Quant Lens show `Source uncertain`; Quant Lens DCF/EV sections degrade or become unavailable when selected DCF is absent. | DCF scenario coverage `0`; DCF coverage `0/1`, `Unavailable`; distribution `uncertain=1`, `yahoo=0`, `sec=0`, `restored=0`, `unavailable=0`. Provider category `ProviderUncertain`; refresh disposition is not `TerminalUntilInputsChange`. |
| `FX-RESTORED` | Seed SQLite with `SymbolRevisionInput` and `RawCapturePayload.Chart`; symbol `W13R`; market `10000`; fair value `12000`; analyst count `18`; startup phase `ShowingCached`; no live refresh completed. | Tracked row state `Cached`; freshness `Restored`; visible restored/saved timing; price `$100.00`; fair value `$120.00`; upside `+2000 bps`; provenance `Restored`; decision null because row is not live. Detail shows restored/stale provenance wherever trust affects interpretation. Quant Lens may render from cached facts but retains restored provenance. | Restored DCF does not count as live DCF coverage. If no live DCF exists, coverage `0`, status `Unavailable`; restored source count is visible if restored DCF is present. |
| `FX-NOT-ELIGIBLE` | Symbol `W13N`; market `10000`; analyst consensus `12000`; analyst count `12`; market cap `1000`; DCF selection `ResolverState.NotEligible`; reason `LatestFcfNonPositive` or `InsufficientAnnualPeriods`; refresh disposition `TerminalUntilInputsChange`. | Row can still use analyst consensus: price `$100.00`; fair value `$120.00`; label `Analyst consensus`; confidence `High`; decision `Act` if no other trust issue. Detail DCF/provider area shows `Not eligible`, not retryable error text. Quant Lens excludes DCF anchors and explains DCF not eligible. | `totalSymbols=1`; DCF denominator excludes this symbol, so `totalEligibleSymbols=0`; DCF covered `0`; status `Unavailable`; distribution `notEligible=1`; DCF scenario coverage `0`; Analyst Low/High scenarios may still count if analyst anchors exist. |
| `FX-UNAVAILABLE` | Symbol `W13X`; fake client returns null snapshot, null external signal, null fundamentals; diagnostics include provider `error` or `missing`; chart empty or throws retryable exception; no cached detail. | Row has no price/fair value; freshness `Issue`; provider issue text visible; confidence unavailable/low; decision null. Detail shows unavailable state rather than stale/live values. Quant Lens unavailable. System tab lists provider error/missing with severity/count. | DCF coverage `0/1`, `Unavailable`; distribution `unavailable=1`; all scenario coverage `0`; provider category `Unavailable` and retryable when diagnostic says retryable. |
| `FX-SOURCE-FREE-LEGACY` | Decode persisted `DcfAnalysis` JSON with no source fields, or construct equivalent legacy state: source null, `ResolverState.RestoredOnly`, provenance source `Unknown`, reasons `LegacySourceFreePayload` and `RestoredWithoutLiveRefresh`. | Displayed fair value from this DCF is `Model fair value`, never `Yahoo`, `SEC`, `Analyst`, `Mean`, or `Consensus`; provenance `Restored - Unknown source`; confidence lowered; live decision null when only restored/source-free DCF backs it. | Restored count `1`; covered DCF `0`; `Ready` forbidden; source distribution must not increment Yahoo or SEC. |
| `FX-SECONDARY-DISABLED` | App composition uses `defaultSecondaryTimeseriesProvider() == null`; repository constructed with `secondaryTimeseriesProvider = null`; Yahoo fake may be usable. | Provider/system state shows secondary DCF provider disabled only as configuration status, not an error. SEC provenance is absent. | SEC count `0`; source selection uses Yahoo when usable; no release may enable SEC default without separate evidence gate. |

### 11.2 Automated Test Map

| File / class | Add or update |
| --- | --- |
| `ScreenDataProjectionEngineTest.kt` in core tests | New suite covering all FX rows. Use SoftAssertions for grouped row/detail/Quant Lens/estimate invariants, or one assertion per focused test. |
| `IndexEstimatesEngineTest.kt` | Add coverage/distribution tests for `ProviderUncertain`, `RestoredOnly`, `NotEligible`, `Unavailable`, and source-free legacy DCF. |
| `DcfSourceSelectionPolicyTest.kt` | Add fake-timeseries tests for provider disagreement, terminal not-eligible, disabled/no provider, and retryable unavailable. |
| `DcfSourceModelTest.kt` | Extend legacy source-free assertions through projection-facing provenance and reason codes. |
| `QuantLensEngineTest.kt` | Assert Quant Lens consumes projected anchors/provenance and degrades provider-uncertain/restored/source-free inputs. |
| `DefaultDashboardRepositoryTest.kt` | Add local fake setup tests for every FX state. Use fake clients/providers and SQLite seeding only. |
| `DcfSourceCoordinatorTest.kt` | Assert secondary-disabled default and fake secondary disagreement without parser payloads. |
| `DetailScreenTest.kt` | Update valuation label tests: model fallback expects `Model fair value`; analyst labels appear only when analyst fields are present. |
| `DashboardListsTest.kt` | Add row chip/trust/freshness label tests for restored, source uncertain, unavailable, and not eligible. |
| `QuantLensUiModelsTest.kt` | Assert visible `Source uncertain`, restored, unavailable, and model-only Quant Lens copy from projected state. |
| `EstimatesScreenTest.kt` | Keep status-driven banner tests and add source-distribution rendering tests. UI must not recompute thresholds. |
| `DiscountScreenerAppContainerTest.kt` | Keep or extend the default secondary provider disabled gate. |

### 11.3 Manual Fixture Harness

Implementation must add or use a debug-only fixture harness before manual QA. It must launch the app with a fixture ID, seed a temporary SQLite store or fake repository facts, and block live network. Acceptable setup: debug build extra/shared pref `screenDataFixtureId=FX-*`, fake `YahooFinanceClient`, fake `FundamentalTimeseriesProvider`, and seeded `SQLiteStateStore`. Not acceptable: copied Yahoo/SEC HTML or invented parser JSON.

| Scenario | Setup | Actions | Expected visible output | Evidence |
| --- | --- | --- | --- | --- |
| Live analyst | Launch `FX-LIVE-ANALYST`. | Open Opportunities, Tracked, Watchlist, Detail Snapshot, Detail Lens, Estimates, System. | Row shows `$100.00`, `$125.00`, `+25.0%`, primary display label `Analyst fair value`, source/provenance label `Weighted target`, `Act`, no trust note. Detail and Quant Lens use the same anchor. Estimates shows Ready `1 / 1`, DCF `+10.0%/+25.0%/+60.0%`, Analyst Low/High `+10.0%/+40.0%`. System has no provider error. | Screenshots for list, detail snapshot, Quant Lens, Estimates. |
| Model-only | Launch `FX-LIVE-MODEL-ONLY`. | Open row, Detail Snapshot, History, Estimates. | Row/detail show `Model fair value`, `$130.00`, `+30.0%`, `No analyst target`, `Watch`. No analyst-specific label appears for the primary value. History says analyst target is unavailable/waiting. Estimates has DCF coverage but analyst scenarios show zero coverage. | Screenshots plus note confirming no `Analyst`, `Mean`, `Low`, `High`, `Consensus` primary label. |
| Provider uncertain | Launch `FX-PROVIDER-UNCERTAIN`. | Open row, Detail Lens, Estimates, System. | `Source uncertain` visible; confidence is not High; decision is not forced to Avoid solely by uncertainty. DCF/EV sections degrade. Estimates shows DCF unavailable `0 / 1` and distribution `uncertain=1`. | Screenshots of row trust, Quant Lens degraded section, Estimates distribution. |
| Restored warm start | Launch `FX-RESTORED` after clearing app data and seeding SQLite; do not refresh. | Open Tracked, Detail Snapshot, Detail History. | Rows show `Restored`/saved timing, state is cached, no decision chip, no live Yahoo label. Detail provenance remains restored/stale. | Screenshot before refresh and SQLite seed summary. |
| Not eligible | Launch `FX-NOT-ELIGIBLE`. | Open row, Detail Snapshot/Lens, Estimates. | Analyst-backed row can still be `Act`; DCF area says `Not eligible`; no retry/error wording. Estimates excludes symbol from DCF denominator and shows `notEligible=1`. | Screenshots of row, DCF status, Estimates. |
| Unavailable | Launch `FX-UNAVAILABLE`. | Open Tracked/System/Estimates. | Row has `Issue`, provider issue text, no stale fair value. System lists provider error/missing with count. Estimates unavailable with all coverage zero. | Screenshots of issue row and System issue card. |
| Source-free legacy | Launch `FX-SOURCE-FREE-LEGACY`. | Open restored row/detail/Estimates. | Source-free DCF is `Restored - Unknown source`, not Yahoo/SEC/live. Primary label is `Model fair value`; coverage is not Ready. | Screenshot and decoded payload/reason-code note. |
| Secondary disabled | Launch `FX-SECONDARY-DISABLED`. | Open System and Estimates. | Secondary provider disabled is visible as config status, not an error. SEC source count is zero. | Screenshot and app-container default test output. |

### 11.4 Mutation / Negative Test List

| Mutation | Required failure |
| --- | --- |
| Change model-only/source-free label from `Model fair value` to `Mean`, `Analyst`, or `Consensus`. | `FX-LIVE-MODEL-ONLY` and `FX-SOURCE-FREE-LEGACY` label tests fail. |
| Count `RestoredOnly` DCF as live complete DCF coverage. | Restored/source-free estimate coverage tests fail; `Ready` is forbidden. |
| Count `ProviderUncertain` DCF as selected/live. | `FX-PROVIDER-UNCERTAIN` coverage and Quant Lens degradation tests fail. |
| Include `NotEligible` symbols in DCF denominator. | `FX-NOT-ELIGIBLE` denominator/status/distribution tests fail. |
| Drop `LegacySourceFreePayload` or `RestoredWithoutLiveRefresh` reason codes on decode. | Source-free model/projection tests fail. |
| Map restored/source-free provenance to `Live - Yahoo`. | Restored/source-free row/detail/provider tests fail. |
| Remove provider-uncertain confidence lowering for dependent DCF/EV/decision outputs. | `FX-PROVIDER-UNCERTAIN` confidence test fails. |
| Mechanically force all provider-uncertain rows to `Avoid`. | `FX-PROVIDER-UNCERTAIN` decision test fails. |
| Recompute Estimates banner thresholds in Compose instead of using `DcfCoverageSummary.status`. | `EstimatesScreenTest.kt` fails. |
| Enable SEC secondary provider by default. | `DiscountScreenerAppContainerTest.kt` fails. |

### 11.5 Validation Gate and Blocker Handling

Release validation remains the repository Android gate: `scripts/validate-android.ps1`. The gate always runs `:core:test`; when an SDK is configured it also runs `:app:testDebugUnitTest` and `:app:assembleDebug`. A missing Android SDK is acceptable only if `:core:test` passes, the skip message is captured, and app unit tests, debug assemble, and manual on-device QA are recorded as blocked. A prior release build is not a substitute for this gate.

If no Kotlin mutation framework is practical, manually apply at least the label, confidence, coverage, restored, source-free, and SEC-default mutations above, verify the intended tests fail, revert each mutation, and record failing test names.

## 12. Traceability Matrix

| AC | Work Queue Items | Tests / QA Evidence |
| --- | --- | --- |
| AC-1 | WQ-01, WQ-12 | PR scope checklist; reviewer confirms no desktop/shared-contract drift. |
| AC-2 | WQ-02, WQ-09 | Core projection tests; repository snapshot consistency tests. |
| AC-3 | WQ-02, WQ-09 | Repository tests and architecture review of mutex/fact assembly boundary. |
| AC-4 | WQ-01, WQ-05, WQ-10 | Candidate inventory; grep/reviewer notes; chart/UI mapper tests. |
| AC-5 | WQ-03, WQ-11 | Row consistency fixtures; manual Opportunities/Tracked/Watchlist QA. |
| AC-6 | WQ-04, WQ-06, WQ-11 | Detail/Quant Lens consistency fixtures; manual Detail QA. |
| AC-7 | WQ-04, WQ-10, WQ-11 | Label-selection tests; Detail/History manual QA. |
| AC-8 | WQ-03, WQ-08, WQ-11 | Provider uncertain fixture tests; row/detail/estimates QA. |
| AC-9 | WQ-07, WQ-10, WQ-11 | `IndexEstimatesEngineTest`; Estimates UI mapper tests; manual Estimates QA. |
| AC-10 | WQ-01, WQ-08, WQ-12 | App composition review; provider disabled/default test or reviewer evidence. |
| AC-11 | WQ-11 | Cross-screen fixture suite and matrix. |
| AC-12 | WQ-02, WQ-09, WQ-10 | Architecture review; mapper tests; Compose grep/reviewer notes. |
| AC-13 | WQ-04, WQ-08, WQ-09, WQ-11 | Warm-start/restored fixture tests; manual warm-start QA. |
| AC-14 | WQ-12 | Validation command summary; manual QA checklist/screenshots. |

## 13. Park-and-Route Triggers

Park this redesign slice and route a separate planning/spec task if any of these appear:

- A proposed change touches desktop behavior or shared contracts beyond Android-facing models.
- SEC secondary or primary provider default enablement is requested.
- Live upstream provider evidence is required but cannot be sampled; do not invent provider payloads.
- Persistence schema migration becomes necessary beyond backward-compatible decode/defaulting.
- Product wants new recommendation semantics beyond centralizing `Act`, `Watch`, and `Avoid`.
- UI redesign expands beyond consistency/source/provenance display.
- Projection model changes become large enough to require cross-platform contract versioning.

## 14. PR Readiness Requirements

- All work queue rows are either complete with evidence or explicitly deferred through a park-and-route decision.
- The PR description includes the candidate inventory, final projection boundary, and list of removed duplicate calculators/interpretation paths.
- Tests cover live, restored, source-free legacy, no analyst target, model fallback, provider uncertain, unavailable, disabled, and not eligible states.
- Android validation is run with `scripts/validate-android.ps1`; any SDK limitation is documented with the portable test subset that did run.
- Manual QA notes cover Opportunities, Tracked, Watchlist, Detail Snapshot, Detail Valuation, Detail History, Detail Quant Lens, Estimates, and provider/system state.
- SEC secondary provider remains disabled by default unless the separate evidence-gated release decision is present.
- User-visible behavior changes update or link the existing docs in the implementation PR.
- No source screen computes a divergent financial anchor, source label, confidence threshold, DCF coverage rule, EMA/MACD interpretation, or provider trust state outside the projection boundary.
