# Deferred Work

## 2026-07-16 — Windows dashboard startup review

- `apps/windows/src-tauri/src/commands.rs` / remote ticker search: distinguish transient failure from a successful empty Yahoo result before caching an empty response, so a temporary outage does not suppress results for the cache TTL.
- `apps/windows/src-tauri/src/fetcher.rs` / candle requests: apply the existing Yahoo share-class symbol mapping to chart endpoints as well as quote-summary endpoints (for example `BRK.B` → `BRK-B`).
- `apps/windows/src-tauri/src/engine.rs` / volume ratio: honor the requested recent lookback and ignore invalid zero-volume entries instead of taking the median across the complete candle history.
