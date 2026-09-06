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

Discount Screener is a personal workstation for Juan. The monorepo has three user-facing clients:

- a Rust terminal workstation under `apps/desktop`
- a Tauri/React Windows workstation under `apps/windows`
- a native Android app under `apps/android`

The clients rank public companies from quotes, analyst targets, candles, local persistence, and a valuation model family. The project is not investment advice.

## Users And Jobs

### Primary Users

- Juan, a single self-directed analyst. Multi-user growth and onboarding are out of scope.

### Core Jobs

- Find potentially undervalued profitable companies.
- Reopen the app and quickly understand which names deserve attention.
- Drill into a ticker to inspect valuation, consensus, price history, technical indicators, and evidence.
- Preserve local context across sessions through warm-start persistence.
- On Android, judge pre-earnings event risk without mixing it into DCF.

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

### Windows Workstation

- Opportunity scoring, Quant Lens, and residual-income / FCFF valuation.
- Advisor CSV import: holdings snapshot vs Chase 90-day blotter. Warn, then confirm.

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
- Plans tab (Android-only). Specs: `dip-board-spec-v1.md`, `cross-board-spec-v1.md`, `leftover-board-spec-v1.md`.
- Earnings tab (Android-only). PRD: `prd-pre-earnings-risk-gate-2026-08-27.md`.

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

- The system uses Yahoo Finance public endpoints/HTML pages for quotes, fundamentals, coverage, analyst targets, cash-flow history, candles, and option chains.
- Android earnings also reads EDGAR 8-K item 2.02 for report dates and Alpha Vantage for SUE history. The Alpha Vantage key lives on device. It never enters git.
- Provider parsing must handle missing, sparse, stale, or unavailable fields without inventing values.

## Nonfunctional Requirements

- Startup must remain bounded and usable even with large retained local databases.
- List triage should remain usable while live refresh is in progress.
- Business rules should be deterministic for the same input state.
- UI should expose evidence for summary claims rather than hiding raw supporting values.
- User-visible cross-platform capability should remain in parity by default unless a platform-specific exception is documented. Android Plans and Android Earnings are documented exceptions.
- Financial values should preserve fixed-point integer style.
- Rendering should not perform network or storage work.

## Architecture Constraints

- Rust desktop business logic belongs in `apps/desktop/src/lib.rs` or owning modules; `main.rs` stays orchestration-focused.
- Desktop Yahoo fetching, persistence, profiles, rendering, and event-loop responsibilities stay separated.
- Android `core/` is pure Kotlin business logic. Valuation, ranking, and the pre-earnings gate live there.
- Android `app/` is the imperative Android shell with `domain`, `data`, `presentation`, and `ui` boundaries.
- Compose screens are passive views; presenters and repositories provide state.
- Shared behavior belongs in `shared/contracts` when cross-platform semantics matter.

## Verification Baseline

- Desktop: `cargo fmt`, `cargo test`, and smoke run.
- Android: `scripts/validate-android.ps1`.
- Installed Android app behavior: `make android-run-qa` when a change reaches app UI/startup/runtime behavior.
- Provider-shape changes: at least 5 real Yahoo samples.
- Meaningful behavior changes: mutation testing or an explicit gap plus manual mutation checks.

## Current Known Planning State

- The Valuation Change Visibility feature has PRD, UX, architecture, epics, readiness report, and sprint status artifacts.
- Several list/history foundations are already implemented.
- Android pre-earnings Wave 0, 1-A, and §4.4 are built. Open work: `deferred-work.md`.

## Out Of Scope For This Baseline

- New investment recommendation logic.
- Cloud sync or cross-device state.
- Social/community features.
- Broker integrations or trade execution.
- Guaranteed real-time market data.

## Maintenance

Update this PRD when shipped functionality changes materially. Feature-specific PRDs should describe proposed changes; this document should describe the product baseline after those changes ship.
