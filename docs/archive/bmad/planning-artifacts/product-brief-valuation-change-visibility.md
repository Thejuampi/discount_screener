---
title: "Product Brief: Valuation Change Visibility"
status: "complete"
created: "2026-04-23T19:04:08-04:00"
updated: "2026-04-23T19:08:08-04:00"
inputs:
  - "apps/android/README.md"
  - "README.md"
  - "Party mode requirement roundtable"
  - "Repository artifact analysis"
  - "Targeted web research on mobile finance UX patterns"
---

# Product Brief: Valuation Change Visibility

## Executive Summary

Discount Screener already helps analysts spot undervalued companies, but it still leaves one high-friction question too hard to answer: **what actually changed since the last time I looked, and does it matter?** Users can see current price, fair value, upside, and rank, yet they still have to mentally reconstruct whether a symbol became more attractive because the market moved, because analyst targets changed, or because the app simply reloaded cached state and re-ranked the list.

Valuation Change Visibility turns that ambiguity into a decision-support capability. In the Android app, analysts should be able to scan the tracked list and immediately see **rank movement, meaningful analyst-target revisions, clear freshness/provenance, and a plain-language cause of change**. At the symbol level, they should be able to open a concise history view that makes valuation direction, revision magnitude, trend quality, and recent significant events obvious without wading through raw logs or noisy micro-moves.

## The Problem

Today, valuation history is easy to store but harder to interpret. An analyst returning to the app after a warm restore may see the list move around and wonder whether the investment case changed or whether the app simply resumed and recomputed. Even when analyst targets change over time, small revisions and large revisions can look too similar, which forces users to inspect multiple screens or remember past values manually. That slows conviction updates and undermines trust in the signal.

The cost of the status quo is not missing data; it is **missing clarity**. If the app cannot quickly explain what changed, by how much, when it changed, why rank moved, and whether the signal is fresh, the user has to do that synthesis themselves. In an investing workflow, that means slower reactions, lower confidence, and more time spent validating the tool instead of acting on it.

## The Solution

Valuation Change Visibility gives analysts a two-level answer.

At the **tracked-list level**, each symbol surfaces the latest meaningful change at a glance: rank movement, analyst target movement, restore-vs-live context, and a simple reason label such as **Price moved**, **Target changed**, or **Relative re-rank**. The experience should emphasize signal over noise by visually elevating only meaningful revisions and clearly separating cached state from fresh updates.

At the **symbol-detail level**, the History view becomes an interpretation surface rather than a raw record. Users can review valuation movement over time, see recent significant revisions, read a short trend summary, and understand whether analyst sentiment is steadily improving, steadily deteriorating, or mixed. Every visible change signal must carry **magnitude, timestamp, and provenance** so the user can validate the summary quickly. The outcome is simple: the user can tell what changed and whether the investment thesis strengthened or weakened.

## What Makes This Different

Most finance screens either show a single current target or dump a dense history with little interpretation. This approach is different because it treats valuation movement as **decision-support intelligence**, not a charting problem. The product does not just display past values; it explains why the present list looks different from the remembered one and helps the user separate material movement from churn.

The unfair advantage here is not proprietary data. It is tighter integration between persistent history, live refresh state, ranking logic, and UX that is explicitly optimized for analyst trust.

## Who This Serves

**Primary user:** self-directed analysts and investors who track undervalued stocks and revisit the app frequently to reassess conviction. Success means they can reopen the app and, in seconds, understand which symbols deserve a deeper look.

**Secondary user:** power users comparing many names across a ranked list who need lightweight signals in the list and richer interpretation in detail, without flipping to external tools for target history.

## Success Criteria

### Product rules for v1

- **Meaningful valuation change:** less than **5%** is de-emphasized, **5% to 19.9%** is significant, and **20% or more** is major.
- **Default comparison anchor in the list:** the most recent stored session baseline when reopening the app, then the most recent live update once the session is active.
- **Default comparison anchor in history:** the immediately prior recorded analyst-target revision within the selected time window.
- **Required provenance states:** Restored, Live updating, Live refreshed, and Stale/Unavailable when history or analyst data is incomplete.
- **Required attribution outputs:** Price moved, Target changed, Relative re-rank, or Combined move when more than one driver materially changed.

The first version is successful when:

- users can identify symbols with **material valuation change since last seen**
- users can distinguish **warm-restored state** from **fresh live refresh**
- users can tell whether rank movement came from **fair-value change, price change, relative re-ranking, or a combination**
- the history view lets users understand valuation direction over time without reading raw logs
- low-signal fluctuations are de-emphasized so major revisions stand out
- a returning user can explain **what changed and why** for a symbol in **under 5 seconds**
- the list helps users reprioritize which symbols need re-review first

Verification should use scenario-based acceptance tests, including warm restore reordering, quote-only refreshes, fair-value-only revisions, combined quote and fair-value moves, and insignificant fluctuations that should not be emphasized.

## Scope

### In for the first version

- tracked-list visibility for meaningful rank movement and analyst-target movement
- explicit restore-vs-live context in the interaction model
- explicit cause labels for why movement happened
- symbol-level valuation history focused on analyst target movement over time
- trend summary and recent significant revision summaries
- threshold-based emphasis that separates material moves from noise
- empty and degraded states for no history yet, stale-only rows, partial refresh, and missing analyst coverage

### Out for the first version

- predictive recommendations such as buy/hold/sell advice
- exhaustive audit-log style history views
- user-configurable significance thresholds
- desktop parity work in the same planning slice
- notification systems or alert routing outside the existing in-app experience

## Non-Goals and Risks

This brief does **not** promise portfolio advice, automated recommendations, or perfect causal certainty in every edge case. When multiple drivers move together, the system should prefer a **Combined move** label instead of pretending to isolate a single cause with false precision.

The main risk in this feature is trust erosion through ambiguity. If provenance is weak, thresholds are inconsistent, or summaries cannot be validated against raw values and timestamps, users will discount the feature as decorative rather than useful. The UX must therefore prioritize explanation over density and validation over visual flourish.

## Vision

If this succeeds, Discount Screener becomes a tool that does more than identify undervaluation in the moment. It becomes a place where analysts can **track conviction over time**: how targets evolve, how rankings react, and how sentiment is compounding or breaking down across the watchlist. Android is the first shell, but the long-term capability is platform-agnostic: a consistent valuation-history intelligence layer that can later be carried back into desktop and shared contracts.
