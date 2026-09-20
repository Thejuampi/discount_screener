---
title: "Product Brief Distillate: Valuation Change Visibility"
type: llm-distillate
source: "product-brief-valuation-change-visibility.md"
created: "2026-04-23T19:08:08-04:00"
purpose: "Token-efficient context for downstream PRD creation"
---

# Product Brief Distillate: Valuation Change Visibility

## Core requirement

- The Android app must let an analyst clearly **see, trust, and interpret meaningful valuation change over time**.
- Required answers in seconds: **what changed, by how much, since when, why rank moved, and whether it matters**.
- This is not a charting enhancement; it is a **decision-support capability**.

## UX-first framing

- Primary UX promise: user should never wonder whether a stock became more attractive or whether the app simply reshuffled after restore.
- UX must distinguish **Restored** vs **Live refreshed** state clearly.
- UX should surface **signal over noise** and avoid making small fluctuations visually equal to major revisions.
- Every visible change signal should include **magnitude, timestamp, and provenance**.
- List view should answer most revisit questions without forcing immediate drill-down into detail.

## Product rules captured

- Significant valuation change threshold for v1: **5%+**
- Major valuation change threshold for v1: **20%+**
- List comparison anchor: most recent stored session baseline on reopen, then latest live update in-session
- History comparison anchor: immediately prior recorded revision in selected time window
- Required attribution labels: **Price moved**, **Target changed**, **Relative re-rank**, **Combined move**
- Required provenance states: **Restored**, **Live updating**, **Live refreshed**, **Stale/Unavailable**

## In-scope signals

- Rank movement in tracked list
- Weighted analyst target movement in tracked list
- Symbol-level valuation history with trend summary
- Recent significant revision summaries
- Empty/degraded states: no history, stale-only, partial refresh, missing analyst coverage

## Explicit non-goals

- No buy/hold/sell advice
- No exhaustive audit-log UX for v1
- No user-configurable thresholds in v1
- No desktop parity in the same planning slice
- No notification system expansion in this slice

## Reviewer insights preserved

- Skeptic lens: requirement must define “material,” “last seen,” provenance, attribution logic, and empty/error states or it will not be spec-ready.
- Opportunity lens: strongest positioning is **valuation change intelligence**, not generic history visibility.
- Trust lens: returning-user adoption depends on clear freshness labels and plain-language reason labels.

## Technical/product context

- Repository already has Android tracked-row movement badges and valuation-trend history summaries.
- Current Android surfaces: tracked list, opportunities, watchlist, detail, history, system.
- Warm restore and SQLite-backed revision history already exist; this feature builds on that foundation.
- Existing terminology in product/docs/code includes: **Target**, **Upside**, **Disc**, **Confidence**, **Cached**, **Live**, **Stale**.
- Cross-platform future matters; design should stay portable back to desktop and shared contracts later.

## Detailed user scenarios

- Returning analyst opens app after warm restore and needs to know whether top symbols changed because of analyst target revisions, price moves, or recomputation.
- Analyst scanning tracked list wants to triage which symbols deserve re-review now, not read every history row.
- Analyst opens symbol history and wants to understand if analyst sentiment is steadily improving, steadily deteriorating, or mixed.
- User needs to validate summary claims against raw values and timestamps without leaving the detail flow.

## Requirements hints for downstream PRD

- Add acceptance criteria for task speed: explain a symbol’s change in under 5 seconds.
- Define exact attribution behavior when multiple drivers move at once.
- Specify data retention window, revision cadence assumptions, and fallback behavior when analyst data is sparse.
- Require detail history to support both summary interpretation and raw-value validation.
- Decide whether “since last seen” is device-local, session-local, or persisted at user-state level.

## Open questions to resolve later

- Should rank movement explanation show single cause only or weighted multi-cause explanation?
- Should partial refresh states lock or soften attribution until all tracked symbols refresh?
- Should history expose rank trend as a separate lane in v1 or defer that to a later slice?
- When analyst revisions are sparse, what fallback wording best preserves trust without feeling empty?
