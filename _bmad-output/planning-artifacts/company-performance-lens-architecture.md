---
title: "Architecture: Company Performance Lens"
status: "complete"
created: "2026-04-25T19:11:21-04:00"
updated: "2026-04-25T19:11:21-04:00"
stepsCompleted:
  - inputs-reviewed
  - domain-model-defined
  - boundaries-defined
  - verification-defined
inputs:
  - "_bmad-output/planning-artifacts/company-performance-lens-prd.md"
  - "_bmad-output/planning-artifacts/company-performance-lens-ux-design-specification.md"
  - "_bmad-output/planning-artifacts/company-performance-lens-prd-validation-report.md"
  - "_bmad-output/project-context.md"
---

# Architecture: Company Performance Lens

## Architectural Goal

Company Performance Lens is a deterministic interpretation pipeline that converts existing market, fundamental, DCF, history, and provider-state inputs into portable business-health read models. UI surfaces render the read models; they do not compute business rules.

## Ownership Boundaries

### Android

- `apps/android/core` owns portable domain models and lens computation for Android.
- `apps/android/app/domain` owns repository contracts and use-case boundaries.
- `apps/android/app/data` maps Yahoo, SQLite, and repository state into core input models.
- `apps/android/app/presentation` maps core outputs into Compose UI state.
- `apps/android/app/ui` renders passive views only.

### Desktop

- Reusable lens types and business logic live in `apps/desktop/src/lib.rs` or a focused owning module such as `performance_lens.rs`.
- Yahoo parsing remains in `apps/desktop/src/market_data.rs`.
- SQLite persistence remains in `apps/desktop/src/persistence.rs`.
- Terminal orchestration and rendering consume prepared lens outputs and must not fetch, persist, or infer lens semantics.

### Shared Contracts

- `shared/contracts` owns language-neutral fixtures for semantic parity.
- Contract fixtures should cover the output model, not implementation internals.

## Domain Model

### `TrendSignal`

Represents direction for a metric or section:

- `Improving`
- `Stable`
- `Deteriorating`
- `Mixed`
- `Volatile`
- `InsufficientData`

### `ConfidenceLevel`

Represents trust in the lens output:

- `High`
- `Medium`
- `Low`
- `Unavailable`

Confidence is derived from freshness, completeness, source availability, parse/provider uncertainty, and critical missing fields.

### `Provenance`

Captures source context:

- source component: quote, fundamentals, analyst target, DCF, cash flow history, persisted revision, chart history
- timestamp if known
- state: restored, live updating, live refreshed, stale, unavailable
- missing fields
- provider issue code or parser warning when available

### `PerformanceMetricEvidence`

Fields:

- metric id
- display label
- latest value using fixed-point style
- prior/comparison value when available
- `TrendSignal`
- `ConfidenceLevel`
- `Provenance`
- explanation key

### `PerformanceScorecardSection`

Fields:

- section: growth, profitability, cash conversion, balance sheet, valuation, sentiment, confidence
- status: positive, neutral, negative, mixed, unavailable
- section trend
- section confidence
- evidence rows

### `RiskFlag`

Fields:

- kind: `CashConversionWeak`, `MarginCompression`, `DebtStress`, `LowCoverage`, `StaleEvidence`, `ValuationContradiction`, `PriceFundamentalDivergence`, `DcfDebtConflict`
- severity: low, medium, high
- evidence ids
- deterministic explanation key
- provenance

### `DecisionReadiness`

Values:

- `ReadyForReview`
- `NeedsUpdatedData`
- `NeedsPeerContext`
- `NeedsManualThesisCheck`
- `TooSparseToJudge`

Readiness is derived after trajectory, scorecard, confidence, and risk flags. `TooSparseToJudge` overrides positive classification when critical evidence is unavailable.

### `PerformanceLens`

Top-level read model:

- symbol
- trajectory
- decision readiness
- confidence
- provenance summary
- scorecard sections
- risk flags
- deterministic summary messages
- degraded-state messages

## Pipeline

1. Gather existing symbol detail, fundamentals, quote, analyst target, DCF cache state, history baseline, and provider component state.
2. Normalize inputs into typed lens input structures.
3. Build metric evidence rows.
4. Aggregate evidence into scorecard sections.
5. Derive trajectory.
6. Derive confidence.
7. Generate risk flags.
8. Derive decision readiness.
9. Produce list and detail read models.

## Threshold Policy

Thresholds should be centralized in core/domain configuration, not scattered in UI. Initial thresholds should be conservative and test-driven:

- material revenue/profit/margin movement should require enough magnitude to avoid noise
- stale windows should be explicit per provider component
- low analyst coverage should lower confidence but not automatically imply poor performance
- missing DCF should degrade DCF-specific evidence only, not the whole lens

Exact values can be finalized story-by-story, but each threshold must be named and covered by tests.

## Persistence Strategy

V1 does not require a new persistence table. It can compute the lens from current restored/live state and existing persisted revisions.

Persistence expansion is required only if the product needs historical performance lens revisions independent of existing symbol revisions. If added later, persistence must store source payload truth or stable derived snapshots, not rebuild old conclusions from current state.

## Startup And Performance

- Do not load complete per-symbol histories during startup.
- Do not trigger DCF analysis globally to fill lens fields.
- Compute the lightweight current lens during snapshot/reporting flows.
- Compute history-dependent lens details on demand for the selected symbol.
- Cache per-symbol lens outputs within the same snapshot where useful, but invalidate on relevant quote/fundamental/DCF/history changes.

## UI Contracts

List model:

- trajectory label
- confidence label
- readiness label
- top risk or material risk count
- degraded-state summary

Detail model:

- full `PerformanceLens`
- section ordering
- evidence rows
- provenance labels
- deterministic summary messages

## Testing Strategy

- Core unit tests for trajectory, confidence, risk flag, and readiness derivation.
- Contract fixtures under `shared/contracts` for cross-platform expected outputs.
- Android repository/presenter tests proving UI state maps prepared models without inventing rules.
- Desktop tests proving terminal read models consume core outputs.
- Degraded-state tests for missing fundamentals, stale provider data, missing analyst target, no DCF, no baseline, and parse uncertainty.
- Manual mutation checks or automated mutation testing around threshold and override behavior.

## Implementation Guardrails

- No business rules in Compose.
- No business rules in terminal rendering.
- No Yahoo fetches or SQLite queries from render paths.
- No AI-generated investment summaries.
- No single opaque aggregate health score in v1.
- No provider fixture fabrication for parser-dependent changes.
