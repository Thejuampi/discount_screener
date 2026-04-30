---
title: "UX Design Specification: Company Performance Lens"
status: "complete"
created: "2026-04-25T19:11:21-04:00"
updated: "2026-04-25T19:11:21-04:00"
stepsCompleted:
  - inputs-reviewed
  - interaction-model-defined
  - degraded-states-defined
inputs:
  - "_bmad-output/planning-artifacts/company-performance-lens-prd.md"
  - "_bmad-output/planning-artifacts/product-brief-company-performance-lens.md"
---

# UX Design Specification: Company Performance Lens

## UX Goal

Company Performance Lens should let users quickly decide whether business performance supports, contradicts, or weakens a valuation opportunity. The experience must stay dense, operational, and inspectable on both the Rust terminal workstation and Android.

## Information Architecture

The lens appears in two layers:

- **List layer:** compact triage signal for scan and prioritization.
- **Detail layer:** evidence-backed explanation for the selected symbol.

The detail layer is the source of truth for interpretation. List rows only summarize the most decision-relevant state.

## Core Presentation Model

Every rendered lens should use this order:

1. Trajectory
2. Decision readiness
3. Confidence/provenance
4. Material risk flags
5. Scorecard sections
6. Evidence rows

## Terminal UX

### Main Rows

Rows in `Top Candidates`, `Top Opportunities`, and watchlist-filtered views may include a compact lens segment when width allows:

```text
Perf: Improving | Conf: Med | Risk: Margin- | Ready
Perf: Mixed     | Conf: Low | Risk: 3       | Thesis check
Perf: Sparse    | Conf: --  | Risk: --      | Too sparse
```

When terminal width is constrained, collapse to:

```text
Perf: Improving Med
Perf: Mixed Low
Perf: Sparse --
```

The row must not shift ranking columns unpredictably. Lens fields should truncate before price, discount, rank, and symbol identity fields.

### Detail Snapshot

The terminal detail snapshot adds a `Performance Lens` block below valuation/consensus evidence and above lower-priority auxiliary sections when space permits.

Recommended layout:

```text
Performance Lens
Trajectory        Improving        Evidence: growth + cash conversion
Decision          Ready for review Confidence: Medium | Live refreshed
Risks             Margin compression (Med), Low coverage (Low)

Metric            Latest           Direction     Evidence
Revenue growth    12.4%            Up            Latest fundamentals, 2026-04-25
FCF margin        8.1%             Down          Cash flow history sparse
Debt / EBITDA     2.8x             Flat          Balance sheet latest
```

Compact mode should show only trajectory, readiness, confidence, and top risk. Full mode shows scorecard rows.

### Detail History

The History tab should connect performance trajectory to stored revisions only when enough historical evidence exists. If history is too sparse, it should show a direct state:

```text
Performance history: Too sparse to compare. Latest snapshot only.
```

## Android UX

### List Rows

Tracked, opportunity, and watchlist rows should display a single-line or two-line lens summary:

- trajectory label
- confidence label
- top material risk or risk count
- readiness label

Example:

```text
Improving · Medium confidence · Ready for review
Risk: Margin compression
```

Rows should use restrained status color:

- improving/ready: positive accent, not celebratory
- stable/neutral: muted neutral
- deteriorating/material risk: warning or negative accent
- mixed/manual thesis: caution accent
- sparse/stale/unavailable: neutral degraded-state styling

### Detail Screen

Android detail should present the lens as a dense section, not a marketing-style card stack. Compose screens remain passive and render a prepared UI model.

Recommended section order:

1. Header strip: trajectory, readiness, confidence
2. Provenance row: source state and timestamp
3. Risk flags list
4. Scorecard groups
5. Evidence table/list rows

### Android Evidence Rows

Use compact rows:

```text
Revenue growth
12.4% · Improving
Latest fundamentals · Live refreshed
```

When screen width allows, use columns aligned to `Metric | Latest | Direction | Evidence`. On narrow phones, stack secondary evidence under the metric.

## Copy Rules

- Use plain deterministic labels: `Improving`, `Stable`, `Deteriorating`, `Mixed`, `Volatile`, `Insufficient data`.
- Use `Ready for review`, not `Buy` or `Attractive`.
- Use `Needs manual thesis check`, not `Risky investment`.
- Use `Too sparse to judge`, not empty or hidden output.
- Use `Live refreshed`, `Restored`, `Stale`, and `Unavailable` consistently with existing trust-state language.

## Degraded States

The UI must explicitly render:

- no fundamentals available
- cash-flow history unavailable
- DCF analysis not run
- analyst coverage missing
- no prior baseline
- stale provider data
- parse/provider uncertainty
- conflicting evidence
- insufficient evidence for trajectory

No degraded state should be represented only by absence.

## Interaction Behavior

- List row selection opens detail as today.
- No new modal is required for v1.
- Existing detail tab mechanics can host the lens in Snapshot first; History integration can follow once persisted performance revisions exist.
- Android back behavior remains unchanged.
- Terminal keyboard behavior remains unchanged.

## Acceptance Checks

- A user can identify trajectory, confidence, readiness, and top risk from a list row when space allows.
- A user can validate a detail conclusion from visible evidence rows.
- A stale or sparse symbol never looks equivalent to a healthy high-confidence symbol.
- Compact terminal layout does not destabilize existing ranking and valuation columns.
- Android UI contains no business-rule interpretation in Compose.
