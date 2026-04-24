---
stepsCompleted: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]
inputDocuments:
  - "G:\\dev\\repos\\discount_screener\\_bmad-output\\planning-artifacts\\product-brief-valuation-change-visibility.md"
  - "G:\\dev\\repos\\discount_screener\\_bmad-output\\planning-artifacts\\product-brief-valuation-change-visibility-distillate.md"
  - "G:\\dev\\repos\\discount_screener\\_bmad-output\\planning-artifacts\\ux-design-specification.md"
  - "G:\\dev\\repos\\discount_screener\\_bmad-output\\planning-artifacts\\architecture.md"
  - "G:\\dev\\repos\\discount_screener\\apps\\android\\README.md"
status: "complete"
workflowType: "prd"
project_name: "Valuation Change Visibility"
user_name: "Juan"
date: "2026-04-23"
---

# Product Requirements Document - Valuation Change Visibility

**Author:** Juan  
**Date:** 2026-04-23

## Executive Summary

Discount Screener already helps analysts find undervalued stocks, but it still leaves a high-friction question unresolved: **what changed since the last time I looked, and does it matter?** On Android, users can see current price, target, and upside, yet they still have to reconstruct whether a symbol became more attractive because the market moved, because analyst targets changed, because the ranking changed relative to peers, or because the app simply restored cached state and then refreshed live data.

Valuation Change Visibility turns that ambiguity into a decision-support capability. The Android app must help a returning analyst understand, in seconds, **what changed, by how much, since when, why rank moved, and whether the signal is trustworthy**. The experience is intentionally hierarchical:

1. **Tracked list = triage**
2. **Detail = explanation**
3. **History = confidence over time**

This is not a generic charting enhancement. It is an interpretation layer built on top of existing ranking, persistence, and revision history so the product can behave like an analyst assistant rather than a raw data log.

## Product Vision

The product should make valuation change visible enough that a returning analyst can reopen the app, scan the tracked list, and immediately know which symbols deserve re-review now. The system must separate meaningful movement from noise, clearly distinguish restored state from fresh state, and provide enough evidence that the user can validate the explanation without leaving the app.

If this succeeds, Discount Screener becomes not just a place to see current valuation, but a place to track how conviction is evolving over time.

## Success Criteria

### User Success

- A returning analyst can identify the symbols that changed meaningfully since the last baseline without manually comparing remembered values.
- A returning analyst can distinguish **restored**, **live updating**, **live refreshed**, and **stale/unavailable** states at a glance.
- A user can explain why a symbol moved in rank in under **5 seconds** for the common cases covered by v1.
- A user can validate any summary claim by seeing supporting before/now values, timestamp context, and recent history evidence.
- A user can tell whether analyst valuation movement is broadly improving, broadly deteriorating, mixed, or too sparse to interpret.

### Business Success

- The tracked list becomes a reliable re-triage surface for repeat sessions, reducing the need for external notes or manual memory checks.
- The Android app becomes more valuable as a repeat-use reassessment tool rather than only a point-in-time screener.
- Product trust improves because the app explains movement rather than silently reordering symbols.

### Technical Success

- Baseline semantics are deterministic and shared across list, detail, and history.
- Materiality and attribution rules are centralized so equivalent data yields equivalent labels on every surface.
- Historical revisions are reconstructed from persisted historical payloads rather than current in-memory state.
- Partial refresh, missing analyst data, and stale data degrade gracefully without creating false explanations.

### Measurable Outcomes

- In scenario-based acceptance testing, the app correctly differentiates no meaningful change, significant change, and major change for valuation movement.
- In scenario-based acceptance testing, the app correctly differentiates price-driven movement, target-driven movement, relative re-rank, and combined movement for covered cases.
- In walkthrough testing, a reviewer can determine the top changed tracked symbol and its primary explanation in under 5 seconds.
- In history review testing, a reviewer can tell whether a valuation path is improving, deteriorating, mixed, or insufficient without reading every row.

## Product Scope

### MVP - Minimum Viable Product

- Android list-surface visibility for meaningful valuation change and rank movement on **tracked and opportunity rows**
- Opportunities as the default landing surface, with aggressive scoring selected by default and legacy still available on demand
- Opportunity ranking and comparison baselines kept separate from tracked ranking semantics
- Explicit restore-to-live provenance states
- Cause labels for why the symbol moved
- Detail explanation with before/now evidence, no-baseline handling, and no-meaningful-change handling
- Symbol-detail history focused on weighted analyst target movement over time
- Trend summary and recent significant revision summaries
- Threshold-based emphasis that suppresses low-signal fluctuations
- Empty and degraded states for no history, stale-only data, partial refresh, no baseline, and missing analyst coverage

### Growth Features (Post-MVP)

- Configurable thresholds and user preference controls
- Rank history as a separate visual lane
- Export/share of valuation change summaries
- Alerts and notification routing for major target changes

### Vision (Future)

- Shared cross-platform valuation change intelligence across Android, desktop, and shared contracts
- Multi-session and multi-device continuity for “since last seen”
- Portfolio-level change intelligence and digest-style summaries

## User Journeys

### Journey 1: Returning Analyst Triage

1. User opens the app into a warm-restored state.
2. The opportunities surface is visible immediately as the default triage view, and tracked remains available as a sibling list surface.
3. Rows expose provenance states indicating restored, live updating, live refreshed, stale, or unavailable conditions.
4. Live refresh completes and materially changed rows surface rank movement, valuation movement, and cause/trust context.
4. The user identifies which symbols deserve re-review without opening every detail screen.

**Success condition:** the user can name the top changed symbol and why it moved without external notes.

### Journey 2: Explain a Rank Jump

1. User sees a row move up or down in rank.
2. The row surfaces rank movement, valuation movement if significant, and a cause label.
3. User opens detail to confirm before/now values and freshness state.
4. The detail view explains whether the move came from price, target, relative re-rank, or a combination.

**Success condition:** the user does not need to reconstruct the change manually from memory.

### Journey 3: Validate Longitudinal Trend

1. User opens the history screen for a symbol.
2. The screen provides a summary of recent valuation direction and confidence.
3. Recent significant revisions and historical evidence confirm whether the move is steady, deteriorating, mixed, or sparse.

**Success condition:** the user can judge whether the change appears to be part of a broader valuation trend.

### Journey 4: Trust a Degraded State

1. User opens a symbol when live data is missing, stale, or only partially refreshed.
2. The UI says what is known, what is not known, and what comparisons remain valid.
3. The system suppresses false confidence and avoids overclaiming cause or trend.

**Success condition:** the user understands the limitation and does not mistake degraded data for fresh interpretation.

## Domain Requirements

### Domain Context

This feature operates in an investment-research context. The product supports reassessment and prioritization; it does **not** provide buy/hold/sell advice or regulated recommendations.

### Domain-Specific Requirements

- The system must communicate facts and interpretations in plain language without implying investment advice.
- Every meaningful change signal must carry supporting evidence that lets the user validate the explanation.
- When data quality is insufficient, the system must prefer explicit uncertainty over a confident but weak explanation.
- Time, baseline, and provenance semantics must be clear enough that users can interpret “changed since” consistently.

## Innovation and Differentiation

The differentiator is **valuation change intelligence**, not generic historical display. The product should not merely show old values; it should explain why the present state differs from the remembered state and whether the difference is meaningful.

Key differentiators:

- list-level re-triage instead of history hidden behind drill-down
- provenance-aware interpretation rather than silent cache-to-live transitions
- summary-first history that helps a user form conviction quickly
- clear suppression of noise so only meaningful revisions stand out

## Project Type and Delivery Context

This is a **brownfield Android feature enhancement** within an existing product. The current Android app already has:

- tracked list and symbol detail surfaces
- warm restore and SQLite persistence
- revision history
- ranking and valuation data based on existing reporting flows

The feature is Android-first, but the rules for materiality, attribution, provenance, and trend interpretation should remain portable to shared logic later.

## Functional Requirements

### Comparison Baselines and Provenance

- **FR1:** The system can compare tracked-symbol state on reopen against the most recent stored session baseline.
- **FR2:** The system can compare tracked-symbol state during an active session against the most recent live update baseline.
- **FR3:** The system can compare history revisions against the immediately prior recorded revision within the selected time window.
- **FR4:** The system can expose a user-visible provenance state for restored, live updating, live refreshed, and stale/unavailable data.
- **FR5:** The system can surface when no comparable baseline exists for a symbol.
- **FR6:** The system can preserve enough historical context to reconstruct prior revisions from persisted historical data.

### Tracked List Triage

- **FR7:** An analyst can see whether a tracked symbol moved up or down in ranking relative to its comparison baseline.
- **FR8:** An analyst can see how many places a tracked symbol moved in ranking.
- **FR9:** An analyst can see when a tracked symbol’s weighted analyst target changed by a material amount.
- **FR10:** An analyst can distinguish between no meaningful target change, significant target change, and major target change.
- **FR11:** An analyst can identify which tracked symbols deserve re-review without opening every detail screen.
- **FR12:** The system can suppress prominent valuation-change emphasis for sub-threshold movement.

### Change Attribution and Explanation

- **FR13:** An analyst can see whether a material move is primarily attributed to price movement, target movement, relative re-rank, or a combined move.
- **FR14:** The system can avoid overstating attribution certainty when multiple drivers changed materially.
- **FR15:** The system can expose before/now comparison values that support the explanation shown.
- **FR16:** An analyst can tell when an explanation is unavailable or unreliable because data is incomplete or stale.

### Detail Explanation

- **FR17:** An analyst can open a symbol and see a concise explanation of what changed since the relevant baseline.
- **FR18:** An analyst can see the symbol’s current valuation context alongside the change explanation.
- **FR19:** An analyst can validate the explanation using raw supporting values and timestamps.
- **FR20:** An analyst can see the symbol’s current freshness state without leaving the detail flow.
- **FR21:** An analyst can understand whether the symbol’s rank movement reflects valuation change, price change, peer movement, or a combination.

### History and Trend Confidence

- **FR22:** An analyst can review weighted analyst target movement over time for a symbol.
- **FR23:** An analyst can see a history summary that classifies recent valuation direction as improving, deteriorating, mixed, flat, or insufficient to interpret.
- **FR24:** An analyst can see recent significant valuation revisions as distinct events.
- **FR25:** An analyst can inspect historical evidence that supports the summary interpretation.
- **FR26:** An analyst can use the selected history time window to scope the history interpretation.
- **FR27:** The system can suppress false trend claims when revision density is too sparse.

### Degraded and Edge-State Handling

- **FR28:** An analyst can distinguish between restored data awaiting refresh and data that has been refreshed live.
- **FR29:** The system can represent partial refresh states without implying that all tracked symbols have been refreshed equally.
- **FR30:** The system can represent missing analyst coverage without showing a misleading valuation-change explanation.
- **FR31:** The system can represent stale or unavailable history without inventing trends.
- **FR32:** The system can continue to show valid comparisons even when some explanation components are unavailable.

### Scope and Surface Boundaries

- **FR33:** An analyst can access valuation change visibility for tracked rows and opportunity rows in the Android app.
- **FR34:** The system can keep ad hoc notifications and desktop parity out of v1 unless explicitly added later.
- **FR35:** The system can apply a consistent change vocabulary across tracked list, detail, and history.
- **FR36:** Opportunity rows use opportunity-specific ranking and baseline semantics and do not borrow tracked-row rank context.
- **FR37:** The Android app opens on opportunities with the aggressive scoring model selected by default while preserving legacy as a manual option.

## Non-Functional Requirements

### Performance

- The tracked list must remain usable during warm restore and live refresh, with provenance state visible while fresh data is still arriving.
- Change indicators and summaries must appear quickly enough that a user can triage common reopen scenarios in under 5 seconds.
- History summaries must render from locally available data without requiring the user to wait for unrelated app surfaces.

### Reliability and Data Integrity

- Baseline selection, materiality classification, provenance labeling, and attribution outputs must be deterministic for the same input state.
- Historical views must be derived from persisted historical truth rather than recomputed from current symbol state.
- When data is missing, stale, sparse, or partial, the system must degrade by reducing certainty rather than fabricating precision.
- Equivalent data conditions must produce equivalent labels across tracked list, detail, and history surfaces.

### Accessibility

- Meaningful change states must not rely on color alone; directional and textual cues must also communicate significance.
- Provenance and degraded-state messaging must remain understandable to users scanning quickly or using assistive technologies.
- Summary-first content must remain readable on small Android screens without forcing dense visual parsing.

### Integration and Compatibility

- The feature must work within the existing Android architecture: pure interpretation rules should remain portable, while Android-specific persistence and orchestration stay in the app layer.
- The feature must consume existing locally persisted revision history and live data flows without requiring a new external data provider for v1.

### Maintainability and Testability

- Materiality thresholds, provenance states, and attribution vocabulary must be centralized so downstream features cannot silently drift.
- The capability must be testable through scenario-based acceptance cases covering warm restore, live refresh, partial refresh, no baseline, stale history, price-only movement, target-only movement, relative re-rank, and combined movement.

## Explicit Business Rules

### Valuation Source

- The primary valuation signal for this feature is **Yahoo weighted analyst fair value**.

### Materiality Thresholds

- Less than **5%** target movement is not a meaningful valuation change for emphasis purposes.
- **5% to 19.9%** target movement is significant.
- **20% or more** target movement is major.

### Baseline Rules

- On reopen, list comparisons use the most recent stored session baseline.
- During an active live session, list comparisons use the most recent live update baseline.
- History comparisons use the immediately prior revision within the selected time window.

### Attribution Rules

- `Price moved` is used when ranking or attractiveness changed primarily because price changed while target remained materially stable.
- `Target changed` is used when attractiveness changed primarily because the weighted analyst target changed materially.
- `Relative re-rank` is used when a symbol changed position versus peers without a dominant material change in its own price or target.
- `Combined move` is used when more than one material driver changed or the explanation is not strong enough to isolate a single dominant cause.

### Provenance Rules

- `Restored` means state came from the locally restored session and has not yet been confirmed by a fresh live update.
- `Live updating` means the app is actively refreshing and the symbol may still move as additional live data arrives.
- `Live refreshed` means the symbol has completed its current live refresh cycle.
- `Stale/Unavailable` means the required live or historical information is not fresh enough or not available enough to support a normal explanation.

## Out of Scope

- buy/hold/sell advice
- exhaustive audit-log history UX
- configurable materiality thresholds
- desktop parity work in this slice
- notification and alert expansion
- opportunity-surface parity unless explicitly added in a later planning pass

## Risks and Mitigations

### Trust Risk

If the app highlights movement without clear provenance, timestamps, and before/now evidence, users may treat the feature as decorative rather than useful.

**Mitigation:** every meaningful summary must be backed by visible evidence and explicit freshness context.

### Ambiguity Risk

If baseline semantics or attribution rules drift across surfaces, the same symbol state could tell different stories in different screens.

**Mitigation:** centralize baseline, materiality, provenance, and attribution rules and treat them as product contract, not UI convention.

### Noise Risk

If low-signal movements are emphasized too aggressively, important changes will be harder to spot.

**Mitigation:** enforce threshold-based suppression and summary-first hierarchy.

## Release Recommendation

Ship v1 as an Android tracked-symbol capability with three connected surfaces:

1. tracked-list triage
2. detail explanation
3. history confidence

Do not broaden to additional surfaces until the tracked-symbol workflow proves trustworthy and easy to interpret.
