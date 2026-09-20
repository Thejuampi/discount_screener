---
stepsCompleted: [1, 2, 3, 4]
inputDocuments:
  - "G:\\dev\\repos\\discount_screener\\_bmad-output\\planning-artifacts\\prd.md"
  - "G:\\dev\\repos\\discount_screener\\_bmad-output\\planning-artifacts\\architecture.md"
  - "G:\\dev\\repos\\discount_screener\\_bmad-output\\planning-artifacts\\ux-design-specification.md"
status: "complete"
project_name: "Valuation Change Visibility"
---

# Valuation Change Visibility - Epic Breakdown

## Overview

This document decomposes the Valuation Change Visibility PRD into implementable epics and stories for the Android-first scope. The breakdown preserves the UX hierarchy established in the planning artifacts:

1. **Tracked list = triage**
2. **Detail = explanation**
3. **History = confidence over time**

The epics are intentionally organized by user value rather than by technical layer.

## Requirements Inventory

### Functional Requirements

FR1: The system can compare tracked-symbol state on reopen against the most recent stored session baseline.  
FR2: The system can compare tracked-symbol state during an active session against the most recent live update baseline.  
FR3: The system can compare history revisions against the immediately prior recorded revision within the selected time window.  
FR4: The system can expose a user-visible provenance state for restored, live updating, live refreshed, and stale/unavailable data.  
FR5: The system can surface when no comparable baseline exists for a symbol.  
FR6: The system can preserve enough historical context to reconstruct prior revisions from persisted historical data.  
FR7: An analyst can see whether a tracked symbol moved up or down in ranking relative to its comparison baseline.  
FR8: An analyst can see how many places a tracked symbol moved in ranking.  
FR9: An analyst can see when a tracked symbol’s weighted analyst target changed by a material amount.  
FR10: An analyst can distinguish between no meaningful target change, significant target change, and major target change.  
FR11: An analyst can identify which tracked symbols deserve re-review without opening every detail screen.  
FR12: The system can suppress prominent valuation-change emphasis for sub-threshold movement.  
FR13: An analyst can see whether a material move is primarily attributed to price movement, target movement, relative re-rank, or a combined move.  
FR14: The system can avoid overstating attribution certainty when multiple drivers changed materially.  
FR15: The system can expose before/now comparison values that support the explanation shown.  
FR16: An analyst can tell when an explanation is unavailable or unreliable because data is incomplete or stale.  
FR17: An analyst can open a symbol and see a concise explanation of what changed since the relevant baseline.  
FR18: An analyst can see the symbol’s current valuation context alongside the change explanation.  
FR19: An analyst can validate the explanation using raw supporting values and timestamps.  
FR20: An analyst can see the symbol’s current freshness state without leaving the detail flow.  
FR21: An analyst can understand whether the symbol’s rank movement reflects valuation change, price change, peer movement, or a combination.  
FR22: An analyst can review weighted analyst target movement over time for a symbol.  
FR23: An analyst can see a history summary that classifies recent valuation direction as improving, deteriorating, mixed, flat, or insufficient to interpret.  
FR24: An analyst can see recent significant valuation revisions as distinct events.  
FR25: An analyst can inspect historical evidence that supports the summary interpretation.  
FR26: An analyst can use the selected history time window to scope the history interpretation.  
FR27: The system can suppress false trend claims when revision density is too sparse.  
FR28: An analyst can distinguish between restored data awaiting refresh and data that has been refreshed live.  
FR29: The system can represent partial refresh states without implying that all tracked symbols have been refreshed equally.  
FR30: The system can represent missing analyst coverage without showing a misleading valuation-change explanation.  
FR31: The system can represent stale or unavailable history without inventing trends.  
FR32: The system can continue to show valid comparisons even when some explanation components are unavailable.  
FR33: An analyst can access valuation change visibility for tracked symbols in the Android app.  
FR34: The system can keep ad hoc notifications, desktop parity, and non-tracked-surface expansion out of v1 unless explicitly added later.  
FR35: The system can apply a consistent change vocabulary across tracked list, detail, and history.

### NonFunctional Requirements

NFR1: The tracked list remains usable during warm restore and live refresh.  
NFR2: Change indicators and summaries appear quickly enough to support under-5-second triage in common reopen scenarios.  
NFR3: History summaries render from locally available data without unrelated blocking.  
NFR4: Baseline selection, materiality classification, provenance labeling, and attribution outputs are deterministic for the same input state.  
NFR5: Historical views are derived from persisted historical truth rather than current recomputation.  
NFR6: Missing, stale, sparse, or partial data reduces certainty rather than inventing precision.  
NFR7: Equivalent data conditions produce equivalent labels across list, detail, and history.  
NFR8: Meaningful change states do not rely on color alone.  
NFR9: Provenance and degraded-state messaging remain understandable with assistive technologies and fast scanning.  
NFR10: The feature works inside the existing Android architecture and keeps pure interpretation rules portable.  
NFR11: The feature reuses existing persisted revision history and live data flows for v1.  
NFR12: The capability is covered by scenario-based acceptance cases for restore, refresh, partial refresh, no baseline, stale history, and driver-specific moves.

### Additional Requirements

- Keep persistence, refresh orchestration, and baseline capture in the Android app layer.
- Move pure interpretation rules for materiality, attribution, provenance, and trend derivation toward shared pure helpers in `apps/android/core`.
- Do not let Compose derive semantics from raw values; UI consumes already-labeled read models.
- Treat persisted revisions as historical facts and never rebuild past revisions from current in-memory detail.
- Preserve explicit degraded states for no baseline, partial refresh, stale/unavailable, and sparse history.
- Prefer `Combined move` over false precision when multiple drivers materially changed.
- Keep history summarization bounded by the selected time window.
- Preserve later portability back to desktop and shared contracts, but do not broaden v1 scope to those surfaces.

### UX Design Requirements

UX-DR1: Tracked rows include a primary change badge for valuation delta with severity-aware emphasis.  
UX-DR2: Tracked rows include a rank movement badge that communicates direction and number of places moved.  
UX-DR3: Tracked rows include a cause chip and provenance chip without overloading the row.  
UX-DR4: Tracked rows include a relative-time label that communicates recency without dominating the layout.  
UX-DR5: Detail includes a summary card that answers what changed, how much, since when, and why.  
UX-DR6: Detail includes a before-vs-now comparison card covering target, price, upside, and rank.  
UX-DR7: Detail includes a trust-state module that explains restored, live, stale, or missing-data conditions.  
UX-DR8: History includes a trend summary card, recent significant revisions, and evidence views.  
UX-DR9: History includes a text-validatable table with timestamp, target, price, upside, and revision context.  
UX-DR10: Small-screen layouts preserve the summary sentence and primary change badge before collapsing secondary metrics.  
UX-DR11: Significance and provenance use explicit text and directional cues, not color alone.  
UX-DR12: Empty and degraded states use trust-preserving language such as no baseline yet, showing last saved state, live data unavailable, and no analyst target history.

### FR Coverage Map

| Requirement Area | Covered By |
| --- | --- |
| Baselines, provenance, list-surface triage | Epic 1 / Stories 1.1, 1.2, 1.3 |
| Detail explanation and validation | Epic 2 / Stories 2.1, 2.2, 2.3 |
| History truth, summaries, evidence, sparse-data handling | Epic 3 / Stories 3.1, 3.2, 3.3 |
| Cross-surface consistency and scope boundaries | Stories 1.1, 2.3, 3.3 plus epic-level release scope |

## Epic List

1. **Epic 1: Make list-surface change triage trustworthy**
2. **Epic 2: Explain why an individual symbol moved**
3. **Epic 3: Turn revision history into confidence over time**

## Epic 1: Make list-surface change triage trustworthy

Deliver list-surface experiences that let a returning analyst immediately see what materially changed after restore, why it changed, and whether the signal is trustworthy enough to act on, with opportunities as the default entry surface and tracked as a peer surface.

### Story 1.1: Establish deterministic comparison baselines for tracked and opportunity symbols

As an analyst,  
I want tracked and opportunity symbols to compare against the correct restore or live baseline,  
So that rank and valuation movement reflect a real change rather than an arbitrary refresh moment.

**Covers:** FR1, FR2, FR4, FR5, FR28, FR29, FR33, FR34, FR36, FR37, NFR1, NFR4, NFR10

**Acceptance Criteria:**

**Given** a warm-restored session with persisted tracked symbols  
**When** the dashboard loads before fresh data completes  
**Then** each tracked symbol exposes a restore-aware comparison baseline  
**And** the UI can represent that the row is restored or live updating.

**Given** a symbol has completed a live update in the current session  
**When** subsequent change calculations run  
**Then** the latest live value becomes the active in-session comparison baseline  
**And** later comparisons use that baseline consistently.

**Given** a symbol has no comparable prior baseline  
**When** change metadata is built  
**Then** the row exposes a no-baseline state  
**And** no misleading movement explanation is emitted.

**Given** opportunities and tracked rows are ranked differently  
**When** rank-change metadata is built  
**Then** opportunities use opportunity-specific rank baselines  
**And** tracked rows do not inherit opportunity ranking context.

### Story 1.2: Show material rank and target movement in list rows

As an analyst,  
I want tracked rows and opportunity rows to highlight meaningful rank and target movement,  
So that I can quickly re-prioritize which symbols deserve attention.

**Covers:** FR7, FR8, FR9, FR10, FR11, FR12, FR33, FR35, FR36, FR37, NFR2, NFR7, UX-DR1, UX-DR2, UX-DR10, UX-DR11

**Acceptance Criteria:**

**Given** a tracked symbol moved in ranking since its active baseline  
**When** the tracked row is rendered  
**Then** the row shows direction and number of places moved  
**And** the badge remains readable on small screens.

**Given** a tracked symbol’s weighted analyst target moved by 5% or more  
**When** the row is rendered  
**Then** the row shows a valuation change badge with direction and significance  
**And** moves of 20% or more receive stronger emphasis than smaller significant moves.

**Given** a tracked symbol’s target moved by less than 5%  
**When** the row is rendered  
**Then** raw values may still update  
**And** no prominent change badge is shown.

**Given** the opportunities surface is the Android landing view  
**When** list rows render there  
**Then** the same movement vocabulary and thresholds apply  
**And** the aggressive model remains the default while legacy stays selectable.

### Story 1.3: Explain cause and trust state directly in list surfaces

As an analyst,  
I want tracked and opportunity list surfaces to tell me why a symbol moved and how trustworthy that explanation is,  
So that I can decide whether to drill in or wait for better data.

**Covers:** FR13, FR14, FR15, FR16, FR28, FR29, FR30, FR32, FR35, FR36, NFR6, NFR8, NFR9, UX-DR3, UX-DR4, UX-DR12

**Acceptance Criteria:**

**Given** a tracked symbol changed materially for a dominant reason  
**When** the row is rendered  
**Then** it shows one cause label from the approved vocabulary  
**And** the label matches the underlying change assessment.

**Given** more than one material driver changed or attribution is weak  
**When** the row is rendered  
**Then** the row shows `Combined move` or an equivalent degraded explanation  
**And** it does not overstate precision.

**Given** the row is restored, stale, partially refreshed, or missing analyst coverage  
**When** the row is rendered  
**Then** the provenance or degraded-state indicator explains the limitation  
**And** any unavailable explanation components are suppressed rather than fabricated.

**Given** a symbol is comparable but did not move meaningfully  
**When** the row is rendered  
**Then** the UI can express `No meaningful change` or a similarly quiet neutral state  
**And** the row avoids implying significance that is not present.

## Epic 2: Explain why an individual symbol moved

Deliver a symbol-detail experience that converts row-level movement into a plain-language explanation backed by before/now evidence and current trust state.

### Story 2.1: Generate a detail summary for the latest meaningful change

As an analyst,  
I want a detail summary that explains what changed since the relevant baseline,  
So that I can understand the movement without reconstructing it from multiple fields.

**Covers:** FR15, FR17, FR18, FR19, FR20, FR21, FR35, NFR4, NFR7, UX-DR5, UX-DR6

**Acceptance Criteria:**

**Given** a symbol has a comparable baseline and current state  
**When** the analyst opens detail  
**Then** the screen shows a concise summary of what changed, how much, since when, and why  
**And** the explanation uses the same approved vocabulary as the list.

**Given** the symbol changed in rank because of price, target, peer movement, or a combination  
**When** the summary is built  
**Then** the explanation names the dominant driver or combined state  
**And** the supporting values are available for validation.

**Given** no comparable baseline exists or the net result is below materiality thresholds  
**When** detail summary is built  
**Then** the screen uses an explicit no-baseline or no-meaningful-change explanation  
**And** it does not manufacture a stronger story than the data supports.

### Story 2.2: Show before/now evidence and trust state in detail

As an analyst,  
I want the detail screen to prove the explanation with values and freshness context,  
So that I can trust the summary or know when to discount it.

**Covers:** FR15, FR16, FR18, FR19, FR20, FR28, FR30, FR32, NFR6, NFR8, NFR9, UX-DR6, UX-DR7, UX-DR12

**Acceptance Criteria:**

**Given** detail is opened for a symbol with current and baseline data  
**When** the evidence modules render  
**Then** the screen shows before-versus-now values for target, price, upside, and rank  
**And** the values align with the summary claim.

**Given** the symbol is restored, stale, partially refreshed, or missing analyst coverage  
**When** detail renders  
**Then** a trust-state module explains what is known and unknown  
**And** unsupported claims are not shown as confirmed explanations.

**Given** timestamps or recency are relevant to the explanation  
**When** detail evidence renders  
**Then** the screen includes visible time context  
**And** the summary and evidence remain easy to validate quickly.

### Story 2.3: Keep list and detail semantics consistent through shared read models

As an analyst,  
I want list and detail surfaces to tell the same story for the same symbol state,  
So that I do not lose trust when I drill down.

**Covers:** FR13, FR14, FR16, FR21, FR35, NFR4, NFR7, NFR10, NFR12

**Acceptance Criteria:**

**Given** the same symbol state is projected to tracked list and detail  
**When** both surfaces are rendered  
**Then** materiality tier, provenance state, and cause label remain consistent  
**And** differences in wording are limited to presentation, not meaning.

**Given** interpretation rules evolve  
**When** they are updated  
**Then** they are applied through shared pure helpers or shared read-model construction  
**And** Compose components do not derive those semantics independently.

## Epic 3: Turn revision history into confidence over time

Deliver a history experience that reconstructs real valuation revisions, summarizes directional trend, and preserves trust when history is sparse or degraded.

### Story 3.1: Reconstruct historical valuation truth from persisted revisions

As an analyst,  
I want history to reflect what the symbol actually looked like at each revision,  
So that trend summaries are based on historical truth rather than current-state reconstruction.

**Covers:** FR3, FR6, FR22, FR25, NFR3, NFR5, NFR11

**Acceptance Criteria:**

**Given** persisted revision history exists for a symbol  
**When** history is loaded  
**Then** each revision is reconstructed from its own persisted payload  
**And** historical target values can differ correctly across revisions.

**Given** a selected time window is applied  
**When** history is prepared  
**Then** comparisons use the immediately prior revision within that window  
**And** the evidence list aligns with the scoped dataset.

### Story 3.2: Summarize valuation direction and recent significant revisions

As an analyst,  
I want history to summarize whether analyst targets are improving, deteriorating, mixed, flat, or too sparse to interpret,  
So that I can judge trend quality without reading a raw log.

**Covers:** FR22, FR23, FR24, FR26, FR27, FR31, NFR2, NFR3, NFR6, UX-DR8

**Acceptance Criteria:**

**Given** a symbol has enough historical revisions in the selected window  
**When** history renders  
**Then** the screen shows a trend summary classification  
**And** recent significant revisions are called out as distinct events.

**Given** the selected window contains sparse or stale history  
**When** the summary is built  
**Then** the screen shows an insufficient or degraded interpretation  
**And** it avoids implying a reliable trend.

### Story 3.3: Provide history evidence views and degraded-state messaging

As an analyst,  
I want the history summary to be backed by concrete evidence and explicit degraded-state language,  
So that I can validate the trend or understand why it cannot be trusted yet.

**Covers:** FR19, FR25, FR26, FR27, FR30, FR31, FR32, FR35, NFR6, NFR8, NFR9, NFR12, UX-DR9, UX-DR11, UX-DR12

**Acceptance Criteria:**

**Given** history data is available  
**When** the evidence views render  
**Then** the user can inspect timestamps, target values, price context, upside context, and revision significance  
**And** the evidence supports the summary shown above it.

**Given** no history, no analyst target history, or degraded freshness is present  
**When** history renders  
**Then** the screen shows explicit trust-preserving empty or degraded copy  
**And** the UI does not present a fake chart or unsupported trend claim.

**Given** stale-only history, sparse revisions, partial refresh context, or missing analyst coverage is present  
**When** the evidence or empty state renders  
**Then** the screen distinguishes those cases explicitly  
**And** the user can tell why trend confidence is reduced.
