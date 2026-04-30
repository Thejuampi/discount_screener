---
status: complete
workflowType: current-state-prd
project_name: Discount Screener
user_name: Juan
date: 2026-04-25
sources:
  - ../../README.md
  - ../../apps/desktop/README.md
  - ../../apps/android/README.md
  - ../../shared/contracts/README.md
  - ../project-context.md
---

# Current Functionality PRD - Discount Screener

## Purpose

This document is the product baseline for what Discount Screener currently does. Use it before creating new feature PRDs so future planning starts from the shipped product, not from stale assumptions.

## Product Summary

Discount Screener is a monorepo with two user-facing clients:

- a Rust terminal workstation under `apps/desktop`
- a native Android app under `apps/android`

Both clients help users identify profitable companies trading below public fair-value signals from Yahoo Finance data. The product combines quote data, analyst targets, historical candles, local persistence, ranking, watchlists, and detail/history views. The project is not investment advice.

## Users And Jobs

### Primary Users

- Self-directed analysts and investors who review many public companies.
- Power users who want a fast workstation-style interface rather than a portfolio-tracking social app.

### Core Jobs

- Find potentially undervalued profitable companies.
- Reopen the app and quickly understand which names deserve attention.
- Drill into a ticker to inspect valuation, consensus, price history, technical indicators, and evidence.
- Preserve local context across sessions through warm-start persistence.

## Current Product Surfaces

### Desktop Workstation

- Ranked live candidate table.
- Top opportunities composite ranking view.
- Low-noise ticker detail screen.
- Real Yahoo historical OHLC candles for ranges `D`, `W`, `M`, `1Y`, `5Y`, and `10Y`.
- EMA20, EMA50, EMA200, volume, and MACD panes.
- Snapshot and History tabs in ticker detail.
- Watchlist toggling and watchlist-only filtering.
- Row filtering and in-terminal symbol additions.
- Issue rail, popup issue notices, and issue log viewer.
- SQLite warm-start persistence with automatic session restore.

### Android App

- Opportunities as the default landing surface.
- Aggressive opportunity scoring selected by default with legacy scoring available.
- Tracked, opportunity, watchlist, system, and detail surfaces.
- Symbol detail with EMA/price/MACD charts, valuation, consensus, evidence, alerts, chart range selection, and replay controls.
- Volume profile in replay detail.
- Restore-to-live movement badges and analyst-target revision cues.
- Cause and trust state labels for price movement, analyst target changes, relative re-ranking, combined movement, no baseline, no meaningful change, missing analyst target, and freshness.
- History detail experience that summarizes analyst-target movement and saved price history.
- Startup splash during warm restore and one-time disclaimer gate.
- Local warm-start persistence for tracked symbols, watchlist, issues, chart cache, revision history, and on-demand complete ticker price history.

## Core Functional Requirements

### Screening And Ranking

- The system ranks candidate symbols by discount/opportunity signals.
- The system exposes both candidate and opportunity list views where supported.
- The Android app supports aggressive and legacy opportunity scoring.
- The system preserves user-tracked symbols and watchlist membership across persisted sessions.

### Ticker Detail

- The system can open a selected ticker detail from list surfaces.
- The system shows current market price, fair value context, discount/upside, qualification, confidence, and external signal status.
- The system shows historical candles and chart-derived indicators for supported ranges.
- The system shows technical context including EMA and MACD where enough data exists.
- The system supports replay-style chart navigation through historical candles.

### History And Change Visibility

- The system persists symbol revisions and reconstructs historical detail truth from saved payloads.
- The system summarizes valuation/analyst-target movement over time when enough history exists.
- The system shows explicit empty, sparse, stale, no-baseline, no-analyst-target, and no-meaningful-change states.
- The Android app can display saved local price history for the selected ticker and range.
- Complete price history must be loaded on demand for a selected ticker, not globally during startup.

### Persistence

- The system uses SQLite for local warm-start persistence.
- Warm start restores bounded current state needed for initial UI readiness.
- Full per-ticker history is available through on-demand loading paths.
- Persistence failures degrade into reset/recovery paths rather than crashing the primary app flow where possible.

### External Data

- The system uses Yahoo Finance public endpoints/HTML pages for quotes, fundamentals, coverage, analyst targets, cash-flow history, and candles.
- Provider parsing must handle missing, sparse, stale, or unavailable fields without inventing values.

## Nonfunctional Requirements

- Startup must remain bounded and usable even with large retained local databases.
- List triage should remain usable while live refresh is in progress.
- Business rules should be deterministic for the same input state.
- UI should expose evidence for summary claims rather than hiding raw supporting values.
- User-visible cross-platform capability should remain in parity by default unless a platform-specific exception is documented.
- Financial values should preserve fixed-point integer style.
- Rendering should not perform network or storage work.

## Architecture Constraints

- Rust desktop business logic belongs in `apps/desktop/src/lib.rs` or owning modules; `main.rs` stays orchestration-focused.
- Desktop Yahoo fetching, persistence, profiles, rendering, and event-loop responsibilities stay separated.
- Android `core/` is pure Kotlin business logic.
- Android `app/` is the imperative Android shell with `domain`, `data`, `presentation`, and `ui` boundaries.
- Compose screens are passive views; presenters and repositories provide state.
- Shared behavior belongs in `shared/contracts` when cross-platform semantics matter.

## Verification Baseline

- Desktop: `cargo fmt`, `cargo test`, and smoke run.
- Android: `scripts/validate-android.ps1`.
- Installed Android app behavior: `make android-run` when a change reaches app UI/startup/runtime behavior.
- Provider-shape changes: at least 5 real Yahoo samples.
- Meaningful behavior changes: mutation testing or an explicit gap plus manual mutation checks.

## Current Known Planning State

- The Valuation Change Visibility feature has PRD, UX, architecture, epics, readiness report, and sprint status artifacts.
- Several list/history foundations are already implemented.
- Detail explanation and remaining degraded-state delivery should continue through the existing BMad sprint/story flow.

## Out Of Scope For This Baseline

- New investment recommendation logic.
- Cloud sync or cross-device state.
- Social/community features.
- Broker integrations or trade execution.
- Guaranteed real-time market data.

## Maintenance

Update this PRD when shipped functionality changes materially. Feature-specific PRDs should describe proposed changes; this document should describe the product baseline after those changes ship.
