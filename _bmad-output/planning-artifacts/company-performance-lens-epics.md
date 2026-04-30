---
title: "Epics and Stories: Company Performance Lens"
status: "complete"
created: "2026-04-25T19:11:21-04:00"
updated: "2026-04-26T00:00:00-04:00"
stepsCompleted:
  - prerequisites-validated
  - epics-created
  - stories-created
  - readiness-assessment-updates-applied
inputs:
  - "_bmad-output/planning-artifacts/company-performance-lens-prd.md"
  - "_bmad-output/planning-artifacts/company-performance-lens-ux-design-specification.md"
  - "_bmad-output/planning-artifacts/company-performance-lens-architecture.md"
  - "_bmad-output/planning-artifacts/company-performance-lens-implementation-readiness-assessment-2026-04-25.md"
---

# Epics and Stories: Company Performance Lens

## Epic 1: Classify Company Performance With Inspectable Evidence

Deliver deterministic company-performance conclusions that users and downstream platform surfaces can inspect through evidence, confidence, risk, and readiness outputs.

### Story 1.1: Represent performance lens states without invalid or hidden data states

**As a** user comparing company quality across platforms,  
**I want** every performance lens state to represent unavailable, degraded, and confident conclusions explicitly,  
**So that** the product never hides missing data or presents an invalid interpretation as a real conclusion.

Acceptance criteria:

- Domain types exist for `TrendSignal`, `ConfidenceLevel`, `Provenance`, `PerformanceMetricEvidence`, `PerformanceScorecardSection`, `RiskFlag`, `DecisionReadiness`, and `PerformanceLens`.
- Types preserve fixed-point value conventions.
- Types can represent unavailable, stale, sparse, and parse/provider uncertainty states.
- Invalid combinations, such as a confident trajectory without supporting evidence or readiness without provenance, are prevented by constructors, enums, or private fields.
- Serialization or projection shape exposes degraded states explicitly instead of omitting them.
- Tests cover construction and serialization shape where applicable.

### Story 1.2: Generate scorecard evidence from existing symbol data

**As a** user,  
**I want** the lens to turn existing fundamentals, valuation, DCF, and provider state into evidence rows,  
**So that** conclusions can be inspected instead of trusted blindly.

Acceptance criteria:

- Evidence rows can be generated for available growth, profitability, cash conversion, balance sheet, valuation, sentiment, and confidence inputs.
- Missing inputs create unavailable evidence states.
- DCF absence degrades only DCF-dependent evidence.
- Lens evidence generation does not load complete per-symbol history at startup.
- Lens evidence generation does not trigger global DCF analysis or backfill work.
- Unit tests cover complete, partial, and missing-input scenarios.

### Story 1.3: Derive trajectory, confidence, risks, and readiness

**As a** user,  
**I want** deterministic performance interpretation,  
**So that** I can tell whether a company is improving, deteriorating, mixed, or too sparse to judge.

Acceptance criteria:

- The lens derives `Improving`, `Stable`, `Deteriorating`, `Mixed`, `Volatile`, and `InsufficientData`.
- Completion requires named threshold constants or configuration values for trajectory, volatility, confidence, and risk severity.
- Threshold boundary tests cover values immediately below, at, and above each named threshold.
- Confidence considers freshness, completeness, source availability, missing fields, and provider uncertainty.
- Risk flags are generated with severity and evidence references.
- Decision readiness follows override rules, including `TooSparseToJudge` and `NeedsManualThesisCheck`.
- Tests cover improving, stable, deteriorating, mixed, volatile, stale, sparse, and contradiction scenarios.

### Story 1.4: Add shared contract fixtures for lens semantics

**As a** cross-platform maintainer,  
**I want** language-neutral fixtures for performance lens scenarios,  
**So that** Android and desktop stay aligned.

Acceptance criteria:

- `shared/contracts` includes fixtures for the key lens scenarios.
- Android core tests validate fixture outputs.
- Desktop tests validate fixture outputs or documented equivalent projections.
- Fixtures include degraded states and contradictions, not only happy paths.

## Epic 2: Surface Company Performance Lens In Detail Views

Render the full evidence-backed lens in selected-symbol detail surfaces without moving business rules into UI.

### Story 2.1: Add Android detail Performance Lens section

**As an** Android user,  
**I want** a detail section showing trajectory, readiness, confidence, risks, and evidence,  
**So that** I can validate whether business performance supports the opportunity.

Acceptance criteria:

- Detail UI renders prepared lens state from presentation models.
- Compose contains no business-rule interpretation.
- Compose rendering does not fetch network data, query SQLite, or compute lens rules.
- The section shows header, provenance, risks, scorecard groups, and evidence rows.
- Degraded states render explicitly.
- Unit tests cover UI state mapping and representative Compose rendering.

### Story 2.2: Add terminal detail Performance Lens block

**As a** terminal workstation user,  
**I want** a compact performance lens block in ticker detail,  
**So that** I can inspect business health without leaving the keyboard workflow.

Acceptance criteria:

- Detail render model includes trajectory, readiness, confidence, top risks, and evidence rows.
- Compact and full terminal layouts preserve existing valuation/chart readability.
- Rendering consumes prepared lens outputs only.
- Terminal rendering does not fetch network data, query SQLite, compute DCF, or compute lens rules.
- Tests cover compact/full layout text for healthy, mixed, and sparse symbols.

### Story 2.3: Connect detail lens to existing degraded-state and issue language

**As a** user,  
**I want** performance lens degraded states to match existing trust labels,  
**So that** stale, restored, unavailable, and live states remain understandable.

Acceptance criteria:

- Labels align with existing restored/live/stale/unavailable language.
- No degraded state is represented only by absence.
- Tests cover no fundamentals, no cash-flow history, missing analyst target, no DCF, stale data, and parse uncertainty.

## Epic 3: Add List-Level Triage Signals

Summarize the lens in tracked, opportunity, and watchlist rows while preserving dense scan behavior.

### Story 3.1: Project compact list summaries from the full lens

**As a** user scanning rows,  
**I want** compact trajectory, confidence, readiness, and risk context,  
**So that** I can prioritize deeper reviews.

Acceptance criteria:

- A list summary model includes trajectory label, confidence label, readiness label, and top risk or risk count.
- Summary projection truncates or degrades safely when space is limited.
- Sparse and stale states remain visible.
- Tests cover summary projection for healthy, deteriorating, mixed, stale, and sparse symbols.

### Story 3.2: Render Android row summaries

**As an** Android user,  
**I want** performance lens row cues on tracked, opportunity, and watchlist rows,  
**So that** cheap but weak names do not look equivalent to healthy opportunities.

Acceptance criteria:

- Rows show compact lens context when available.
- Rows use restrained status styling.
- Rows do not imply investment recommendations.
- Compose tests cover representative row states.

### Story 3.3: Render terminal row summaries

**As a** terminal user,  
**I want** compact performance context in list rows when width allows,  
**So that** I can scan business-health signals without opening every detail.

Acceptance criteria:

- Terminal row summaries appear only when layout can support them.
- Existing symbol, price, discount, rank, and issue fields keep priority.
- Narrow layouts degrade predictably.
- Render tests cover wide and narrow terminal widths.

## Epic 4: Validate, Harden, And Prepare Implementation

Prove the feature is reliable enough for implementation and future extension.

### Story 4.1: Add threshold and override regression tests

**As a** maintainer,  
**I want** threshold and override behavior covered by tests,  
**So that** future changes do not silently convert sparse or mixed evidence into confident claims.

Acceptance criteria:

- Tests cover threshold boundaries for trajectory, confidence, and risk severity.
- Tests prove `TooSparseToJudge` overrides positive-looking partial evidence.
- Tests prove missing DCF does not invalidate unrelated sections.
- Manual or automated mutation testing is recorded.

### Story 4.2: Document operator behavior

**As a** user,  
**I want** docs that explain lens labels and degraded states,  
**So that** I can interpret the feature correctly.

Acceptance criteria:

- Android and desktop docs link or describe the new lens behavior.
- Docs repeat the investment-advice limitation where relevant.
- Docs explain confidence, readiness, and insufficient-data states.

### Story 4.3: Perform live and smoke verification

**As a** maintainer,  
**I want** verified behavior across core tests, app tests, smoke checks, and live Android where applicable,  
**So that** UI behavior matches the planning artifacts.

Acceptance criteria:

- Android validation entrypoint is run when app work lands.
- Desktop `cargo fmt`, tests, and smoke are run when desktop work lands.
- Installed Android QA is recorded for UI changes.
- Provider-related changes include at least 5 real Yahoo samples.
