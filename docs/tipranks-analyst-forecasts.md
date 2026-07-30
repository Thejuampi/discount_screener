# TipRanks analyst forecasts (experimental)

The Windows stock-detail view can enrich Yahoo-backed dashboard data with
individual TipRanks analyst ratings and price targets via the authenticated
remote MCP service. This integration does not change dashboard ranking,
scoring, Quant Lens evidence, or the Yahoo analyst anchor.

## Configuration

1. Create a TipRanks MCP API key at [mcp.tipranks.com/dev/signup](https://mcp.tipranks.com/dev/signup).
2. Open **Settings → TipRanks**.
3. Save the key, then use **Test**.

The key is stored in Windows Credential Manager under
`com.discount-screener.vantage` / `tipranks-api-key`. It is never returned by a
Tauri command, written to SQLite, logged, or placed in diagnostics.

**Test** deliberately performs one budgeted `get_recent_analyst_ratings` call
even when AAPL is already cached. Cached data cannot validate a newly saved
credential.

## Fetch and cache behavior

- Opening stock detail is **cache-only**. Uncached symbols show an explicit
  unloaded state and a backend-authorized load action. Opening never spends
  TipRanks quota.
- Clicking **Load** (or **Refresh** when cache is stale) issues exactly one
  counted `get_recent_analyst_ratings` MCP tool call when authorized.
- Cache is scoped to the UTC calendar month. Prior months are pruned and never
  shown as current.
- Cache age: **fresh** ≤24h, **aging** 1–7d, **stale** >7d.
- Observation age (latest published opinion): **current** ≤30d, **aging** 30–90d,
  **stale** >90d — independent of cache age.
- No automatic precache of the ranking universe or top-N warm path.

## Quota and rate limits

| Cap | Value |
| --- | --- |
| Monthly counted calls | 50 (warn at 25) |
| Rolling rate | 10 / minute |
| Usage reconciliation | free `get_my_usage` (not counted) |

When reconciliation is available, remaining is the stricter of local and
provider. When unavailable, the UI shows a local **estimated** remaining.

A 429 surfaces retry metadata without retry loops. Failed refresh keeps the
prior cache visible with an error banner.

## Weighting

With ≥3 distinct analyst/firm identities:

`weight = clamp(1 + 0.15 × (stars − 3), 0.70, 1.30)`

Otherwise weighted consensus is unavailable. Stars, rank, and contribution
weight are shown per row when TipRanks provides them.

## Live verification (opt-in)

From `apps/windows/src-tauri`:

```powershell
# Prefer process env. If the key lives only in the User environment, inject it:
$env:TIPRANKS_API_KEY = [Environment]::GetEnvironmentVariable('TIPRANKS_API_KEY','User')
# or set explicitly for the shell (never commit the value):
# $env:TIPRANKS_API_KEY = "tr_live_..."
cargo test --lib opt_in_live_contract_covers_five_distinct_symbols -- --ignored --nocapture
```

On Windows the ignored test also falls back to the User-scope `TIPRANKS_API_KEY`
when the process env is empty. Covers AAPL, MSFT, ACGL, TSLA, and JPM without
committing licensed payloads. Confirm no key appears in UI or logs after a manual
detail open.

## Attribution

Visible provider label: **Data by TipRanks**.
