# FMP analyst forecasts (experimental)

The Windows stock-detail view can enrich Yahoo-backed dashboard data with
individual Financial Modeling Prep price targets. This integration does not
change dashboard ranking, scoring, Quant Lens evidence, or the Yahoo analyst
anchor.

## Configuration

1. Create an FMP account and copy its API key.
2. Open **Settings → Financial Modeling Prep**.
3. Save the key, then use **Test**.

The key is stored in Windows Credential Manager. It is never returned by a
Tauri command, written to SQLite, placed in request URLs, or logged. Removing
the key does not delete a valid current-day forecast cache.

**Test** deliberately performs one budgeted provider request even when AAPL is
already cached. Cached data cannot validate a newly saved credential. A
successful test replaces that symbol's current-day cache; a failed test leaves
the valid cached payload untouched.

## Fetch and cache behavior

- Opening stock detail asks the Rust backend for the presentation model.
- The first eligible, uncached symbol in an FMP provider day uses one REST
  request. Reopening that symbol uses SQLite cache.
- Valid empty responses are cached as definitive for that provider day.
- After the Yahoo initial universe pass reaches its terminal state, the backend
  ranks the final snapshot and warms at most ten distinct stock symbols.
- If the initial pass finished before an FMP key was configured, saving the key
  schedules the same current-generation top-ten warm. Switching universes
  resets the completion marker so stale generations cannot trigger warming.
- Detail, warm and credential-test requests share a global two-request network
  limit. Detail and warm requests also share a single in-flight request for the
  same symbol and provider day.
- The provider day rolls at FMP's 3:00 PM America/New_York reset boundary.
  Older licensed response rows are pruned rather than accumulated.

The app maintains a local estimate of the free 250-request daily allowance.
Actual outbound attempts are reserved atomically before network access. A
warning appears at 125 attempts and the backend blocks requests at 250.
Invalid-key and quota failures open a provider-day circuit breaker; transient
provider failures pause new calls briefly.

## Interpretation

The panel shows price history, projected target horizons, backend-computed
histogram bins, individual analyst or firm rows, and min/max/simple mean.
Targets without an explicit date are labelled with an assumed 12-month
horizon.

No weighted consensus is shown because this endpoint does not provide a
licensed, defensible analyst-accuracy history. Fewer than three distinct
analyst or firm identities is reported as insufficient coverage.

## Live contract check

From `apps/windows/src-tauri`, set `FMP_API_KEY` only in the process
environment and run:

```powershell
cargo test opt_in_live_contract_covers_five_distinct_symbols -- --ignored --nocapture
```

The opt-in check requires `FMP_API_KEY`, requests AAPL, MSFT, ACGL, TSLA, and
JPM, and requires usable normalized coverage for each symbol. It does not
persist or commit upstream payloads.
