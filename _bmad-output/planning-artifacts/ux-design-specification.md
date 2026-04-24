---
stepsCompleted: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14]
inputDocuments:
  - "G:\\dev\\repos\\discount_screener\\_bmad-output\\planning-artifacts\\product-brief-valuation-change-visibility.md"
  - "G:\\dev\\repos\\discount_screener\\_bmad-output\\planning-artifacts\\product-brief-valuation-change-visibility-distillate.md"
  - "G:\\dev\\repos\\discount_screener\\apps\\android\\README.md"
  - "G:\\dev\\repos\\discount_screener\\README.md"
  - "UX hierarchy party-mode roundtable"
status: "complete"
title: "UX Design Specification: Valuation Change Visibility"
author: "Juan"
date: "2026-04-23"
---

# UX Design Specification: Valuation Change Visibility

## Executive Summary

### Project Vision

Valuation Change Visibility turns Discount Screener from a snapshot tool into a **reassessment tool**. The Android experience should help a returning analyst understand, in under five seconds, what changed in the tracked universe, whether that change is meaningful, and why the list now looks different. The experience must privilege **trust, signal, and explanation** over density.

### Target Users

**Primary user:** self-directed analysts and investors who revisit the Android app frequently to reassess conviction across a ranked list of symbols.  
**Secondary user:** power users who compare many names quickly and need lightweight triage in the list plus richer validation in symbol detail.

### Key Design Challenges

- make restored vs live state unmistakable without cluttering every row
- surface meaningful analyst-target revisions without making small fluctuations feel urgent
- explain rank movement simply enough for a fast scan, but credibly enough for an analyst
- keep history interpretable on a mobile screen without becoming a raw audit log

### Design Opportunities

- make the tracked list feel like an **attention triage dashboard**
- use detail/history to build **conviction support**, not just data recall
- create a shared vocabulary of **provenance, cause, and materiality** that can later port to desktop

## Core User Experience

### Defining Experience

The experience is built on a strict hierarchy:

1. **List surfaces = triage**  
   Primary entry: **Opportunities default surface**, with tracked as a peer surface using the same vocabulary  
   Answer: **what changed, how much, since when, does it matter, can I trust it**
2. **Symbol detail = explanation**  
   Answer: **why did this symbol move**
3. **History = confidence over time**  
   Answer: **is this a one-off revision or a real trend**

The list surfaces should resolve most revisit questions without forcing a tap. The detail screen should validate and explain. The history view should show whether valuation movement is building, fading, or mixed.

### Platform Strategy

- **Platform:** Android-first, mobile touch interaction
- **Primary context:** short repeat sessions, quick list scans, selective drill-down
- **Interaction model:** tap-driven progressive disclosure; avoid horizontal complexity above the fold
- **Offline/restore context:** users may reopen into warm-restored state before live refresh finishes; the UI must communicate freshness continuously

### Effortless Interactions

These interactions should require almost no thought:

- scanning the list for symbols that changed meaningfully
- recognizing whether a signal is restored or live
- understanding the dominant cause of change
- opening a symbol and immediately seeing a before/now summary
- confirming whether a valuation move is isolated or part of a trend

### Critical Success Moments

- user opens the app and instantly spots which symbols need re-review
- user understands why one symbol rose in rank without manual comparison
- user opens History and immediately sees whether analyst targets are improving, deteriorating, or mixed
- degraded states still preserve trust by clearly stating what is missing and what is still reliable

### Experience Principles

- **Meaning before mechanics** — show the takeaway before the underlying system state
- **Signal over noise** — de-emphasize minor movement, emphasize material revisions
- **Trust through provenance** — every change signal needs freshness and context
- **Explain, don’t merely display** — history should answer a question, not dump a log
- **Validate fast** — summaries must be easy to confirm with raw values and timestamps

## Emotional Response

### Desired Feelings

- **Clarity:** “I understand what changed.”
- **Confidence:** “I can trust whether this is fresh or stale.”
- **Control:** “I know which symbol deserves attention next.”
- **Calm urgency:** major moves should feel important, never alarmist

### Emotional Risks to Avoid

- confusion from too many equal-weight badges
- mistrust when restored and live states look similar
- fatigue from over-highlighting small revisions
- skepticism when summaries cannot be validated quickly

## Inspiration and Reference Patterns

The spec borrows from effective finance UX patterns without copying visual style directly:

- **timeline/change-history views** for recency and sequence
- **threshold-based emphasis** so major moves visually outrank minor ones
- **summary-first insight modules** before raw detail
- **progressive disclosure** from list → detail → historical evidence

Reference principle: the product should behave like an analyst assistant, not a ticker tape.

## Design System Guidance

### Information Hierarchy

Each row and detail module should follow this order:

1. symbol identity
2. significance of change
3. cause of change
4. freshness / provenance
5. supporting values

### Semantic Color Rules

- **Muted / no meaningful move:** neutral outline/surface tones
- **Significant positive (5% to 19.9%):** restrained green
- **Major positive (20%+):** stronger green emphasis
- **Significant negative (5% to 19.9%):** restrained red
- **Major negative (20%+):** stronger red emphasis
- provenance states should use neutral/status styling, not compete with valuation direction

### Typography Rules

- summary line: short, prominent, analyst-grade
- badges/chips: compact label size, semibold, no sentence case paragraphs
- raw supporting values: smaller, secondary text
- timestamps: visible but visually quiet

### Badge and Chip Language

Use short, deterministic labels:

- `Restored`
- `Live updating`
- `Live refreshed`
- `Stale`
- `No baseline`
- `Price moved`
- `Target changed`
- `Relative re-rank`
- `Combined move`
- `No meaningful change`

## Experience Definition

### Primary Task

The most frequent user action is reopening the tracked list and deciding which symbols deserve re-review.

### Default Comparison Anchors

- **List:** compare current live value to the most recent stored session baseline on reopen; once live, compare against the latest live update baseline
- **History:** compare each revision to the immediately prior recorded revision in the selected time window

### Materiality Rules

- `< 5%` valuation change: visible only as passive context
- `5% to 19.9%`: significant
- `20%+`: major

These rules should be visible in behavior but not explained with numeric jargon unless the user drills into help or validation details.

## Visual Foundation

### Screen-Level Hierarchy

**List row (tracked or opportunity)**

1. symbol + company name
2. primary change badge (`Target +12.4%`)
3. rank movement badge (`↑3 rank`)
4. cause label
5. provenance label + relative time
6. supporting metrics: price, fair value, upside, qualification/confidence

**Detail screen above the fold**

1. summary sentence
2. before/now comparison
3. cause breakdown
4. rank movement explanation
5. trust state / freshness

**History screen**

1. trend summary card
2. recent significant revisions
3. timeline/chart
4. reverse-chron table for validation

### Copy Style

- plain English first
- one idea per line
- state fact before interpretation
- keep tone calm, precise, analyst-grade

Good example:
`Up 22%, since yesterday, mostly from target change; rank moved 9 -> 4.`

## Design Directions

### Direction A: Calm Analyst Console

- low-noise base UI
- selective bold emphasis on significant/major moves
- compact chips
- chart surfaces secondary to summary and evidence

### Direction B: Event-Led Monitoring

- slightly more prominent event cards in history
- clearer sequencing of revisions
- useful if the product later expands to alerts and daily digests

**Recommended direction for v1:** Direction A. The core problem is confusion, not lack of activity. Calm analyst console best supports trust.

## User Journeys

### Journey 1: Returning Analyst Triage

1. User reopens app
2. Splash resolves into the warm-restored opportunities surface, with tracked one tab away
3. Rows show `Restored` or `Live updating` while live updates reconnect
4. Live refresh completes and changed rows update with materiality, cause, provenance, and trust cues
5. User identifies top symbols needing re-review in under five seconds

**Success condition:** user can name the top changed symbol and why it moved without opening detail.

### Journey 2: Explain a Rank Jump

1. User sees `↑4 rank`
2. User reads cause chip and target delta
3. User opens symbol detail
4. Summary card explains what changed and whether the move was driven by target, price, or both
5. User confirms with before/now values

**Success condition:** no manual reconstruction from memory or screenshots.

### Journey 3: Validate Longitudinal Trend

1. User opens History
2. Summary card states trend direction and confidence
3. Recent significant revisions show event-level movement
4. Timeline/table confirm cadence and magnitude

**Success condition:** user can determine whether the valuation thesis is strengthening, weakening, or mixed.

### Journey 4: Degraded / Sparse Data

1. User opens a symbol with no prior baseline or sparse analyst coverage
2. UI states the limitation clearly
3. User still sees whatever is reliable without overclaiming

**Success condition:** trust is preserved even when data is incomplete.

## Component Strategy

### Tracked List Components

- **Primary change badge** — signed valuation delta with severity styling
- **Rank movement badge** — previous rank implied by direction and count
- **Cause chip** — single dominant explanation or `Combined move`
- **Provenance chip** — freshness state
- **Relative time label** — `since 2h ago`, `saved yesterday`, etc.

### Detail Components

- **Summary card** — “what / how much / since when / why”
- **Before vs Now card** — target, price, upside, rank
- **Cause breakdown module** — concise explanation of the dominant drivers
- **Trust state module** — explains restored/live/stale state

### History Components

- **Trend summary card**
- **Recent significant revisions strip/list**
- **Target trend chart or sparkline with event markers**
- **History table** with timestamp, target, price, upside, and revision badge

## UX Patterns

### Progressive Disclosure

- list shows triage-ready insight only
- detail explains the latest movement
- history validates the ongoing pattern

### Cause Attribution Pattern

Show one dominant label unless confidence is mixed; then use `Combined move` instead of fake precision.

### Noise Suppression Pattern

Small moves remain visible in raw values and tables, but only material moves receive visual emphasis in list and summary surfaces.

### Summary-Then-Evidence Pattern

Always present:

1. summary sentence
2. supporting values
3. historical evidence

Never reverse that order in primary flows.

## Responsive and Accessibility Guidance

### Small-Screen Rules

- preserve summary sentence and primary change badge at all widths
- collapse secondary metrics before collapsing provenance or cause
- avoid horizontal comparison layouts that require scrolling in primary states

### Accessibility

- do not rely on color alone to convey significance
- use explicit text labels for direction and provenance
- maintain minimum readable tap targets for row chips
- ensure charts/timelines have text equivalents in summary and table views
- keep copy deterministic and screen-reader friendly

### Empty and Degraded States

Use explicit, trust-preserving language:

- `No prior baseline yet — changes appear after the next refresh.`
- `Showing last saved state while live data reconnects.`
- `Live data unavailable — history may be outdated.`
- `No analyst target history yet.`
- `No meaningful change since the last baseline.`

## UX Acceptance Criteria

- a returning user can identify changed symbols needing re-review in under five seconds
- list rows always communicate **what changed, by how much, since when, why, and freshness**
- detail screens explain rank movement in plain language
- history shows trend direction and recent significant revisions without requiring log reading
- degraded states preserve trust and never imply unsupported certainty
