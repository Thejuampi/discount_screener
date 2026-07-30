---
title: 'FMP analyst forecasts detail PoC'
type: 'feature'
created: '2026-07-29'
status: 'done'
baseline_commit: 'f973fee49c597d0e685bb152047260c64484b045'
context:
  - '_bmad-output/project-context.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The market dashboard must keep using Yahoo analyst estimates for ranking, while stock detail lacks the richer individual FMP price-target view. FMP's 250-call daily allowance also needs strict backend control so normal browsing and top-stock warming cannot waste quota.

**Approach:** Add a backend-owned FMP REST adapter, provider-day cache and budget controller. Fetch a symbol on demand from stock detail and automatically pre-cache the final top 10 only after the backend declares the initial universe pass complete; expose presentation-ready forecast models to a passive React UI.

## Boundaries & Constraints

**Always:** Keep control and business logic in Rust: initial-load completion, ranking, top 10 selection, eligibility, fetch scheduling, single-flight, cache freshness, quota accounting, normalization, deduplication, assumed horizons, statistics, histogram bins, weighting availability and UI states. Cache each definitive symbol result for the FMP quota day, including valid empty results, so reopening never calls REST again that day. Count actual outbound attempts against a local 250-call estimate and warn at 125. Store the API key in Windows Credential Manager and never return or log it. Use FMP only in stock detail; Yahoo remains the dashboard/score source.

**Ask First:** Any change to ranking/Quant Lens evidence, a second FMP call per symbol, persistent multi-day licensed history, a paid endpoint, or a materially different FMP response/licensing contract discovered during live verification.

**Never:** Put ranking, cache, quota, histogram or forecast calculations in React; send the key in a URL/query string; average FMP with Yahoo; trigger pre-cache from UI polling; fabricate analyst accuracy or weights not supported by available data; scrape TradingView/Yahoo for this feature.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|---------------|----------------------------|----------------|
| Detail miss | Eligible US stock, configured key | One FMP call; normalized targets, assumed 12-month horizons, distribution and summaries returned | Explicit loading/provider state |
| Detail hit | Same symbol and provider day | Cached model returned; zero calls | Include cache provenance |
| Top 10 warm | Initial universe pass terminal, current generation | Backend ranks final snapshot and warms at most ten eligible distinct symbols | Bounded concurrency; generation guard |
| Overlap | Detail and warm request same symbol | One shared in-flight call | Both consumers receive one result |
| Budget warning | Local attempts reach 125/250 | Presentation model exposes warning and remaining estimate | No frontend threshold logic |
| Exhausted or unavailable | 250 attempts, missing/invalid key, quota response, outage, empty coverage | No unsafe retry loop; typed state returned | Preserve cached definitive results; never leak secrets |
| Sparse identities | Fewer than three distinct analyst/firm identities | Show observations and simple statistics | Weighted consensus marked unavailable |

</frozen-after-approval>

## Code Map

- `apps/windows/src-tauri/src/analyst_forecasts.rs` -- FMP adapter, domain normalization, summaries, bins, budget/cache orchestration and single-flight.
- `apps/windows/src-tauri/src/{state,db,commands,lib}.rs` -- service ownership, replaceable SQLite cache, feed-completion warm hook and Tauri commands.
- `apps/windows/src-tauri/Cargo.toml` -- Windows Credential Manager dependency.
- `apps/windows/src/api.ts` -- typed presentation-only command boundary.
- `apps/windows/src/components/{DetailPanel,AnalystForecastsPanel,SettingsPanel,FmpConnect}.tsx` -- passive rendering and credential actions.
- `apps/windows/src/App.css` -- forecast timeline, distribution and provider-state styling.
- `apps/windows/tests/` -- pure UI-boundary/format contract checks.

## Tasks & Acceptance

**Execution:**
- [x] Add failing Rust tests for normalization, identity deduplication, 12-month horizon, statistics/bins, cache day, quota boundary, single-flight and provider failures; implement the isolated FMP service.
- [x] Add replaceable SQLite cache/budget records and injectable Credential Manager storage; ensure secrets cannot cross response or logging boundaries.
- [x] Add an explicit generation-bound initial-pass completion hook that ranks the backend snapshot and schedules at most ten cached/budgeted fetches.
- [x] Add detail/status/settings Tauri commands returning fully computed presentation models.
- [x] Add the passive detail forecast panel and settings controls, plus boundary tests and operator documentation.
- [x] Add an opt-in live contract test for AAPL, MSFT, ACGL, TSLA and JPM without committing provider payloads.

**Acceptance Criteria:**
- Given a fully loaded universe, when the backend closes its initial pass, then only the current generation's ten highest-scoring eligible stocks are warmed and Yahoo ranking inputs remain unchanged.
- Given a cached symbol, when detail is reopened that FMP quota day, then no network attempt occurs.
- Given individual targets, when detail renders, then it shows price history with projected target horizons, distribution, analyst/firm rows, min/max/simple mean, cache provenance, quota remaining and “Data by FMP.”
- Given no defensible analyst-performance data, the panel must not imply a differentiated weighted consensus.

## Spec Change Log

## Design Notes

The quota day follows FMP's documented reset boundary in America/New_York rather than browser time. FMP target news supplies the forecast observations; existing Yahoo price history supplies the historical price series without consuming another FMP call. Provider responses are normalized immediately and only the replaceable current-day model is retained.

## Verification

**Commands:**
- `cargo fmt --check && cargo test` from `apps/windows/src-tauri`
- `npm test && npm run build` from `apps/windows`
- opt-in live contract test with `FMP_API_KEY`, expected five distinct successful upstream samples

**Manual checks:**
- Save/test/delete a key; finish a universe load; inspect a warmed top-10 stock and a non-top-10 stock; reopen both; verify chart, distribution, states, remaining-call estimate and warning at 50%, with no secret in UI/logs.

## Suggested Review Order

**Backend control and quota safety**

- Start with the service boundary owning cache, quota, concurrency and provider state.
  [`analyst_forecasts.rs:237`](../../apps/windows/src-tauri/src/analyst_forecasts.rs#L237)

- Follow the cache-first and provider-day single-flight path used by detail and warming.
  [`analyst_forecasts.rs:344`](../../apps/windows/src-tauri/src/analyst_forecasts.rs#L344)

- Inspect the gated reservation, rollover retry and generation check before network access.
  [`analyst_forecasts.rs:621`](../../apps/windows/src-tauri/src/analyst_forecasts.rs#L621)

- Verify secrets remain header-only and redirects cannot forward credentials.
  [`analyst_forecasts.rs:843`](../../apps/windows/src-tauri/src/analyst_forecasts.rs#L843)

**Feed and persistence integration**

- Review backend ranking and bounded top-ten scheduling after initial-pass completion.
  [`commands.rs:508`](../../apps/windows/src-tauri/src/commands.rs#L508)

- Confirm the generation-bound completion hook precedes periodic refreshes.
  [`commands.rs:1701`](../../apps/windows/src-tauri/src/commands.rs#L1701)

- Inspect replaceable licensed cache and persistent daily-budget schema.
  [`db.rs:176`](../../apps/windows/src-tauri/src/db.rs#L176)

- Check monotonic provider-day pruning and cache reads.
  [`db.rs:300`](../../apps/windows/src-tauri/src/db.rs#L300)

**Normalization and presentation**

- Review symbol, temporal, identity and fixed-point normalization before aggregation.
  [`analyst_forecasts.rs:932`](../../apps/windows/src-tauri/src/analyst_forecasts.rs#L932)

- Inspect the passive detail surface consuming backend bins and state.
  [`AnalystForecastsPanel.tsx:17`](../../apps/windows/src/components/AnalystForecastsPanel.tsx#L17)

- Check Credential Manager actions and explicit unavailable status.
  [`FmpConnect.tsx:6`](../../apps/windows/src/components/FmpConnect.tsx#L6)

**Contracts and verification**

- Review the typed Tauri presentation model exposed to React.
  [`api.ts:345`](../../apps/windows/src/api.ts#L345)

- Run the ignored five-symbol live contract when a real key is available.
  [`analyst_forecasts.rs:1895`](../../apps/windows/src-tauri/src/analyst_forecasts.rs#L1895)

- Confirm command registration and both host-screen mounts.
  [`analystForecastBoundary.test.ts:62`](../../apps/windows/tests/analystForecastBoundary.test.ts#L62)
