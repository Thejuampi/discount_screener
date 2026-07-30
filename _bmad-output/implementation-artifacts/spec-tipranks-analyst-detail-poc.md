---
title: 'TipRanks analyst forecasts detail PoC'
type: 'feature'
created: '2026-07-29'
status: 'done'
baseline_commit: '271873e6bdb7ff80fa29ef5bf5aec74d7be22560'
context:
  - '_bmad-output/project-context.md'
  - '_bmad-output/implementation-artifacts/spec-fmp-analyst-detail-poc.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The implemented FMP Free adapter cannot retrieve individual analyst targets: live verification returned 402/403 for the required endpoints. Vantage therefore cannot render the target distribution, analyst rows, horizons, or provisional analyst weighting.

**Approach:** Replace the FMP provider boundary with TipRanks' authenticated remote MCP service. Consume `get_recent_analyst_ratings` directly from the Rust backend through `rmcp 2.2.0`; no LLM participates. Opening stock detail is read-only and never spends quota: it shows the active cache or an explicit unloaded state. Only a user click on a backend-authorized load/refresh action may make a counted provider call. The panel distinguishes cache age from the publication age of the latest analyst observation.

## Boundaries & Constraints

**Always:** Rust owns credentials, MCP sessions, quota/rate enforcement, cache policy, eligibility, normalization, deduplication, horizons, weights, histogram and all presentation states. React renders the returned model and emits an explicit load action only. Store the TipRanks key in Windows Credential Manager and never return/log it or place it in diagnostics. Yahoo remains the market-dashboard and ranking source. Attribute visible data to TipRanks.

**Budget:** Treat the quota as 50 counted tool calls per UTC calendar month and warn at 25. There is no automatic precache. Cache each definitive result for the active quota month. Opening and ordinary loading always prefer cache. A user may explicitly refresh stale cache, with a confirmation that states “uses 1 call” and the backend-computed remaining quota. Rate-limit counted calls to at most ten per rolling minute. Use the free `get_my_usage` tool periodically to reconcile provider usage; if reconciliation is unavailable, enforce the stricter local estimate and label it estimated. A 429 exposes reset/retry metadata without retry loops.

**Freshness:** Backend classifies cache age as `fresh` (≤24 hours), `aging` (>24 hours and ≤7 days), or `stale` (>7 days). It separately exposes the latest observation date and classifies analyst coverage as `current` (≤30 days), `aging` (>30 and ≤90 days), or `stale` (>90 days); empty coverage is explicit. Previous quota-month cache is pruned and never presented as current. Thresholds, action availability, cost and messages are backend data, not React decisions.

**Ask First:** Any paid tier, more than one counted ratings call per symbol, persistent multi-month licensed history, Quant Lens/ranking changes, or fallback that fabricates individual observations from aggregate data.

**Never:** Invoke an AI model, scrape TipRanks, synthesize analyst identities, average TipRanks with Yahoo, fetch merely because detail opened, automatically warm any ranking cohort, or let React decide cache/quota eligibility.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Behavior |
| --- | --- | --- |
| Detail miss on open | Eligible stock without active cache | Return explicit unloaded state and enabled/disabled backend-computed load action; zero calls |
| Explicit load | User clicks load, configured key, budget available | One `get_recent_analyst_ratings` call; normalized panel cached for quota month |
| Fresh cache hit | Same symbol, fetched ≤24h | Return cache with fetched/latest-opinion times; load action costs zero |
| Aging cache | Fetched 1–7d ago | Show age notice and cached data; ordinary action costs zero |
| Stale cache | Fetched >7d ago in active month | Show prominent stale warning and an explicit refresh action marked as one call |
| Old analyst coverage | Latest published observation >30d / >90d | Show aging/stale coverage independently of cache freshness |
| Refresh failure with cache | Explicit refresh fails | Keep the prior cache visible and add provider/auth/quota error; never replace it with an empty panel |
| Usage drift | Key used outside Vantage | `get_my_usage` reconciles remaining/limit/reset without consuming quota |
| Sparse coverage | Fewer than three identities | Show data; weighted consensus unavailable |
| Provider/auth/quota failure | Invalid key, 429, outage, malformed payload | Typed state; no secret or unbounded retry |

</frozen-after-approval>

## Code Map

- `apps/windows/src-tauri/src/analyst_forecasts.rs` — replace `FmpRestProvider` with an `rmcp` streamable-HTTP TipRanks adapter; adapt provider-month clock, rate gate, usage reconciliation, cache/coverage freshness, backend-computed actions, normalization, weights and tests.
- `apps/windows/src-tauri/src/db.rs` — add replaceable TipRanks monthly cache plus persistent monthly budget reservation; leave legacy FMP tables inert for compatibility.
- `apps/windows/src-tauri/src/{commands,state,lib}.rs` — rename commands/status ownership, remove the final-generation warm hook and separate cache-only detail reads from explicit provider loads.
- `apps/windows/src-tauri/Cargo.toml` — add exact `rmcp 2.2.0` client/reqwest streamable-HTTP features.
- `apps/windows/src/{api.ts,i18n.tsx,components/AnalystForecastsPanel.tsx,components/FmpConnect.tsx,components/SettingsPanel.tsx}` — rename provider-facing contracts/text and passively render stars, rank, contribution, provider usage, fetched/latest-opinion timestamps, freshness banners and backend-authorized load/refresh actions.
- `apps/windows/src/App.css` — align the horizontal target-distribution bars with the chart price axis, matching the supplied sketch.
- `apps/windows/tests` and `apps/windows/e2e` — update passive-boundary and E2E contracts; keep provider responses mocked.
- `docs/windows-dashboard-2.0-manual-regression.md` — document live TipRanks key verification and quota behavior.

## Tasks & Acceptance

- [ ] Write failing Rust tests for cache-only reads, explicit load/refresh, freshness boundaries, stale-cache preservation on failure, UTC month/reset, 50/25 thresholds, external-usage reconciliation, 10-rpm pacing, TipRanks payload normalization, stars/weights/rank, cache and failures.
- [ ] Implement the MCP adapter, secure credential migration surface, monthly cache/budget and backend orchestration.
- [ ] Adapt presentation contracts and render the combined historical/horizon chart plus horizontally aligned target histogram.
- [ ] Update automated tests/docs and add an ignored five-symbol live contract without storing licensed payloads.

**Acceptance:**
- Given an uncached stock detail, opening it issues zero provider calls and presents the explicit load action.
- Given a configured TipRanks key, clicking load issues exactly one counted ratings call and renders real individual targets, concentration, assumed horizons, statistics and analyst contributions.
- Given fresh or aging cached data, reopening never calls TipRanks and shows both retrieval age and latest analyst-publication age.
- Given stale cached data, the panel keeps it visible, labels it stale and offers an explicit refresh confirmation showing one-call cost and remaining quota.
- Given a failed refresh, the previously cached chart remains visible with an error banner and unchanged provenance.
- Given any universe completion or ranking change, no TipRanks call occurs automatically.
- Given provider usage reaches 25/50 or diverges from local accounting, the backend exposes the reconciled warning/remaining/reset state and the UI only presents it.
- Given three or more distinct identities, weight is `clamp(1 + 0.15 × (stars − 3), 0.70, 1.30)`; otherwise weighted consensus is unavailable.

## Verification

- `cargo fmt --check && cargo test` in `apps/windows/src-tauri`
- `npm test && npm run build` in `apps/windows`
- E2E mocked credential/quota/cache/chart states
- Opt-in real-key checks for AAPL, MSFT, ACGL, TSLA and JPM; visually inspect one detail chart and confirm no key in UI/logs
