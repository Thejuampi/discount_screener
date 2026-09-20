---
title: "PRD: Company Performance Lens"
status: "complete"
created: "2026-04-25T19:11:21-04:00"
updated: "2026-04-25T19:11:21-04:00"
stepsCompleted:
  - brief-reviewed
  - requirements-defined
  - scope-bounded
inputs:
  - "_bmad-output/planning-artifacts/product-brief-company-performance-lens.md"
  - "_bmad-output/planning-artifacts/product-brief-company-performance-lens-distillate.md"
  - "_bmad-output/planning-artifacts/current-functionality-prd.md"
  - "_bmad-output/project-context.md"
---

# PRD: Company Performance Lens

## 1. Goal

Company Performance Lens helps Discount Screener users determine whether an undervalued company is performing well enough to deserve deeper review. It adds deterministic business-health interpretation across desktop and Android without creating buy/sell advice, opaque scores, or free-form AI recommendations.

The feature must make four things visible:

- business performance trajectory
- confidence and provenance behind the data
- risk and contradiction flags
- decision readiness for review

## 2. Problem Statement

Discount Screener currently surfaces valuation, analyst targets, charts, consensus, evidence, alerts, and history. That helps users find possible discounts, but it does not yet answer whether the underlying business performance supports the opportunity. Users must manually synthesize growth, profitability, cash conversion, debt, valuation, DCF context, analyst coverage, freshness, and historical change.

This creates false positives and missed priorities:

- weak companies can look attractive because upside is easier to notice than deterioration
- improving companies can be buried because momentum is scattered across detail sections
- stale or sparse Yahoo data can look cleaner than it is
- users have no single readiness state that says whether a symbol is reviewable, stale, conflicted, or too incomplete to judge

## 3. Users

Primary users are self-directed analysts and investors who review many public companies and use Discount Screener as a dense workstation-style triage tool.

Secondary users are watchlist-oriented power users who reopen the app and need to understand whether business performance has strengthened, weakened, or become harder to trust since the prior review.

## 4. Product Principles

- Evidence over decoration: every conclusion must expose supporting values, source, timestamp, and degraded-state context.
- Deterministic over generative: narrative snippets come from typed rules, not free-form AI text.
- Confidence is first-class: freshness, completeness, source quality, and provider uncertainty are part of the output.
- No forced certainty: insufficient, stale, mixed, and unavailable states must be explicit.
- No investment advice: the system can say "ready for review" or "deteriorating evidence"; it must not say "buy," "sell," or "hold."

## 5. Functional Requirements

### Performance Trajectory

- **FR1:** The system shall classify a symbol's company performance trajectory as `Improving`, `Stable`, `Deteriorating`, `Mixed`, `Volatile`, or `InsufficientData`.
- **FR2:** The system shall expose the evidence that produced the trajectory classification.
- **FR3:** The system shall prefer `Mixed` when strong positive and negative signals coexist.
- **FR4:** The system shall prefer `InsufficientData` when minimum required evidence is missing or stale.
- **FR5:** The system shall avoid ranking or color emphasis that makes `InsufficientData` look better than known weak performance.

### Performance Scorecard

- **FR6:** The system shall produce a `PerformanceScorecard` containing available sections for growth, profitability, cash conversion, balance sheet, valuation, sentiment, and confidence.
- **FR7:** Each scorecard section shall include a status: `Positive`, `Neutral`, `Negative`, `Mixed`, or `Unavailable`.
- **FR8:** Each section shall include compact evidence rows with `Metric`, `Latest`, `Direction`, and `Evidence`.
- **FR9:** The scorecard shall remain valid when only a subset of sections is available.
- **FR10:** Missing scorecard sections shall render as explicit unavailable/degraded states, not omitted silently.

### Confidence And Provenance

- **FR11:** The system shall represent data confidence as `High`, `Medium`, `Low`, or `Unavailable`.
- **FR12:** Confidence shall consider freshness, completeness, source, timestamp, missing fields, and provider/parse uncertainty.
- **FR13:** The system shall expose provenance for each input group used by the lens.
- **FR14:** The system shall distinguish restored state, live-updating state, live-refreshed state, stale state, and unavailable state where those states are known.
- **FR15:** The system shall not allow high-confidence labels when critical provider fields are missing or stale.

### Risk And Contradiction Flags

- **FR16:** The system shall generate typed `RiskFlag` values when business signals conflict with valuation or price signals.
- **FR17:** The system shall include at least these first-version flags: `CashConversionWeak`, `MarginCompression`, `DebtStress`, `LowCoverage`, `StaleEvidence`, `ValuationContradiction`, `PriceFundamentalDivergence`, and `DcfDebtConflict`.
- **FR18:** Each risk flag shall include severity, evidence, and a short deterministic explanation.
- **FR19:** The system shall avoid duplicate flags that describe the same underlying issue.
- **FR20:** Risk flags shall be visible in detail and summarized in list rows when severity is material.

### Decision Readiness

- **FR21:** The system shall produce a `DecisionReadiness` state: `ReadyForReview`, `NeedsUpdatedData`, `NeedsPeerContext`, `NeedsManualThesisCheck`, or `TooSparseToJudge`.
- **FR22:** `ReadyForReview` shall require sufficient evidence and at least medium confidence.
- **FR23:** `TooSparseToJudge` shall override positive trajectory when critical inputs are unavailable.
- **FR24:** `NeedsManualThesisCheck` shall be used when conflicting signals cannot be resolved deterministically.
- **FR25:** Readiness states shall appear without implying advice or recommendation.

### List Surfaces

- **FR26:** Tracked, opportunity, and watchlist rows shall show compact performance lens context when enough data is available.
- **FR27:** Row context shall include trajectory, confidence, and material risk count or highest-severity flag.
- **FR28:** Rows shall visually separate healthy opportunities from deteriorating, conflicted, stale, and insufficient-data names.
- **FR29:** Row output shall stay compact enough for workstation-style scanning on Android and terminal.
- **FR30:** Row output shall degrade gracefully when the lens is unavailable for a symbol.

### Detail Surfaces

- **FR31:** Symbol detail shall include a Performance Lens section or tab area.
- **FR32:** Detail shall show trajectory, readiness, confidence, risk flags, scorecard sections, and evidence rows.
- **FR33:** Detail shall show clear unavailable states for missing fundamentals, cash-flow history, DCF output, analyst coverage, or history baseline.
- **FR34:** Detail shall include deterministic narrative summaries only when the evidence is sufficient to support them.
- **FR35:** Detail shall not perform network or storage work during rendering.

### Cross-Platform And Contracts

- **FR36:** Shared behavior shall be covered by language-neutral contract fixtures when the same semantics must appear in Rust desktop and Android.
- **FR37:** Android core shall own portable performance interpretation for Android.
- **FR38:** Desktop reusable logic shall live in `apps/desktop/src/lib.rs` or owning modules, not terminal orchestration.
- **FR39:** Compose and terminal rendering shall consume prepared read models and shall not invent lens semantics.
- **FR40:** Contract fixtures shall include happy path, deteriorating, mixed, stale, sparse, and contradiction scenarios.

## 6. Nonfunctional Requirements

- **NFR1:** Lens computation shall be deterministic for the same input state.
- **NFR2:** Startup shall remain bounded; no complete per-symbol history or DCF backfill may be loaded globally at warm start.
- **NFR3:** The lens shall use fixed-point financial value conventions for all financial values.
- **NFR4:** Provider-dependent work shall use real Yahoo samples when parsers or external payload assumptions change.
- **NFR5:** UI shall remain dense, calm, and evidence-first.
- **NFR6:** Rendering shall not perform Yahoo fetches, SQLite reads, or expensive analysis.
- **NFR7:** Tests shall cover degraded states as first-class behavior.

## 7. MVP Scope

In scope:

- trajectory classification
- confidence/provenance model
- scorecard sections for growth, profitability, cash conversion, balance sheet, valuation, sentiment, and confidence
- typed risk flags
- decision-readiness state
- Android and desktop detail presentation patterns
- compact row summaries where space allows
- shared contract fixtures for cross-platform semantics

Out of scope:

- buy/sell/hold recommendation
- portfolio allocation advice
- black-box aggregate health score
- free-form AI investment thesis
- notification or alert routing outside existing surfaces
- user-configurable scoring weights
- broad sector peer benchmarking unless reliable peer data is already available

## 8. Success Criteria

- A user can classify a symbol's performance direction in under 5 seconds.
- A user can identify whether a cheap-looking symbol is healthy, deteriorating, conflicted, stale, or too sparse to judge.
- A user can inspect the evidence behind every trajectory, readiness, and risk conclusion.
- Scenario tests cover improving, stable, deteriorating, mixed, volatile, stale, sparse, and contradiction cases.
- Android core tests and desktop tests prove equivalent semantics for shared contract fixtures.
- No UI code contains business-rule branching for trajectory, confidence, risk flags, or readiness.

## 9. Open Decisions For Implementation

- Exact metric thresholds for each scorecard section should be finalized during architecture and story refinement.
- Peer context should start with loaded-symbol/profile peers only if reliable data is already available; otherwise `NeedsPeerContext` can be emitted without peer benchmarking in v1.
- DCF scenario deltas should be treated as a supporting input in v1, not a required input for all symbols.
