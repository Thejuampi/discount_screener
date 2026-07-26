# Deferred Work

## 2026-07-16 — Windows dashboard startup review

- `apps/windows/src-tauri/src/commands.rs` / remote ticker search: distinguish transient failure from a successful empty Yahoo result before caching an empty response, so a temporary outage does not suppress results for the cache TTL.
- `apps/windows/src-tauri/src/fetcher.rs` / candle requests: apply the existing Yahoo share-class symbol mapping to chart endpoints as well as quote-summary endpoints (for example `BRK.B` → `BRK-B`).
- `apps/windows/src-tauri/src/engine.rs` / volume ratio: honor the requested recent lookback and ignore invalid zero-volume entries instead of taking the median across the complete candle history.

## 2026-07-21 — Windows Short presentation review

- `apps/windows` / ESLint baseline: the repository-wide `npm run lint` currently reports 41 pre-existing errors (primarily React hook purity/state-in-effect rules) and one warning across unrelated modules. The files newly introduced for model-aware presentation and the changed App/list/technical/alert/test files lint clean; schedule a separate lint-baseline cleanup rather than mixing it into the Short semantics fix.

## 2026-07-23 — Windows market-regime engine review

- `apps/windows/src-tauri/src/regime/mod.rs`: move the synchronous multi-provider regime refresh off the Tauri command thread and add a single-flight/in-flight guard so concurrent cache misses cannot stampede Yahoo/CNN.
- `apps/windows/src-tauri/src/regime/composite.rs`: keep a fully data-free regime as `Unknown` with no actionable exposure guidance instead of allowing zero scores to appear Neutral/Range.
- `apps/windows/src-tauri/src/regime/regime_fit.rs`: audit fractional quality weighting, Short-side signs for quality/value/low-beta, and effective-feature coverage so zero-weight sector flags cannot satisfy minimum coverage.
- `apps/windows/src-tauri/src/regime/pillars.rs`: derive trend from fetched SPY closes when no cached summary exists, make correlation sampling deterministic, and validate the market breadth universe/sample before treating it as representative.
- `apps/windows/src-tauri/src/regime/`: distinguish all-time-high drawdown from a one-year high, validate CNN snapshot age/ranges, and retain failed-source data only with an explicit stale state.
- `apps/windows/src-tauri/src/regime/composite.rs`: reconcile `cash_buffer_pct` with suggested exposure or expose the otherwise unallocated remainder explicitly.
- `apps/windows/src/components/RegimeBanner.tsx`: add responsive behavior for the fixed three-column banner grid on narrow Windows viewports.
