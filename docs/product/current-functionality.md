# Current Functionality

## Purpose

This document describes the shipped product. Use it before you plan a behavior change.

## Product Summary

Discount Screener is a personal workstation for Juan. The monorepo has four user-facing clients:

- a Rust terminal workstation under `apps/desktop`
- a Tauri/React Windows workstation under `apps/windows`
- a native Android app under `apps/android`
- a Flutter multiplatform client under `apps/flutter`

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
- Android explains earnings risk in Simple mode first. Options mode keeps the saved technical evidence.

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
- Vantage requests compact Yahoo summaries and batches missing Advisor prices.
- Detail requests monthly charts on demand. Chart controls reuse recent candles for the same range.
- SEC insider results follow a bounded freshness interval. Financial residual income reruns when its inputs change.
- Vantage scores opportunity rows outside the shared screener lock after it captures one input view.
- Vantage queues chart enrichment when each symbol becomes visible, including symbols that arrive late.
- Vantage ignores older opportunity responses after a universe change.

### Android App

- Opportunities as the default landing surface.
- Opportunity ordinals follow visible list order, including filtered and pinned rows.
- Background enrichment shows its pass and progress after the quote refresh reaches its total. System hides the feed counter when refresh ends.
- Android list refresh publishes its first new row immediately. Later progress updates arrive in bounded groups.
- System provider status reflects feed data. Detail keeps legacy DCF source warnings with each value.
- Empty year charts enter bounded retry rounds.
- System shows active errors only. Recovered errors stay in stored history.
- DCF disagreement does not dispute analyst ranges while the model remains provisional.
- Estimates withholds the index DCF projection. Analyst gaps show no timed return or zero without target coverage.
- Aggressive opportunity scoring selected by default with legacy scoring available.
- Tracked, opportunity, watchlist, system, and detail surfaces.
- Symbol detail with EMA/price/MACD charts, valuation, consensus, evidence, alerts, chart range selection, and replay controls.
- Volume profile in replay detail.
- Restore-to-live movement badges and analyst-target revision cues.
- Cause and trust state labels for price movement, analyst target changes, relative re-ranking, combined movement, no baseline, no meaningful change, missing analyst target, and freshness.
- History detail experience that summarizes analyst-target movement and saved price history.
- Startup splash during warm restore and one-time disclaimer gate.
- Local warm-start persistence for tracked symbols, watchlist, issues, chart cache, revision history, and on-demand complete ticker price history.
- Android restores saved rows at startup, then requests the same full refresh as the Refresh button.
- Cached rows stay visible during refresh. A decision can remain provisional until its new inputs arrive.
- Android requests that full refresh again after each profile switch.
- Settled decision tags use the same refresh path after startup and manual Refresh.
- A failed saved-data read preserves local data and shows an error. Refresh retries a failed warm restore.
- Add ticker requires the profile switch to finish. The app shows a reason if Add is requested during the switch.
- Plans tab (Android-only). See [Dip](android-plans-dip.md), [Cross](android-plans-cross.md), and [Leftover](android-plans-leftover.md).
- Earnings tab (Android-only). See [Earnings gate](earnings-gate.md).
- Daily V1-through-V5 evaluation capture after enrichment and market processing finish.
- Outcome reports from stored score cohorts and later daily company prices.

### Flutter Client

- Adaptive phone and desktop layouts.
- Pure Dart scoring, chart, valuation, and Quant Lens engines.
- Yahoo-backed progressive profile loading.
- JSON persistence for reports and candles.
- Android, iOS, Windows, macOS, and Linux targets.

## Core Functional Requirements

### Screening And Ranking

- The system ranks candidate symbols by discount/opportunity signals.
- The system exposes both candidate and opportunity list views where supported.
- The Android app supports aggressive and legacy opportunity scoring.
- The system preserves user-tracked symbols and watchlist membership across persisted sessions.

### Ticker Detail

- The system can open a selected ticker detail from list surfaces.
- Android opens ticker detail from saved data. Opening a ticker does not request provider data.
- Android shows Load when no saved detail exists. Load refreshes that ticker's cache and reloads its view.
- Android shows Refresh when saved detail exists. Refresh requests new data for that ticker and keeps saved data on failure.
- A completed ticker refresh writes its cache before the app shows the new quote. Navigation does not stop this write.
- Newer ticker quotes take priority over older profile responses and pending profile cache writes.
- If only other data arrives, Android keeps the saved quote marked as cached and shows the provider issue.
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
- Android preserves one atomic scoring evaluation snapshot per profile and UTC day.
- Warm-start reset preserves evaluation snapshots, score journals, and outcome candles.

### External Data

- The system uses Yahoo Finance public endpoints/HTML pages for quotes, fundamentals, coverage, analyst targets, cash-flow history, candles, and option chains.
- Android earnings also reads EDGAR 8-K item 2.02 for report dates and Alpha Vantage for SUE history. The Alpha Vantage key lives on device. It never enters git.
- Provider parsing must handle missing, sparse, stale, or unavailable fields without inventing values.
- Android requests compact Yahoo summaries and selected batch quote fields. Earnings consensus requests its own module.
- [Yahoo loading research](../research/yahoo-api-loading-2026-09-25.md) records endpoint limits, captured samples, and payload measurements.

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

## Open Work

Use [Deferred work](deferred-work.md) for known items that are not complete.

## Out Of Scope For This Baseline

- New investment recommendation logic.
- Cloud sync or cross-device state.
- Social/community features.
- Broker integrations or trade execution.
- Guaranteed real-time market data.

## Maintenance

Update this document when shipped functionality changes materially.
