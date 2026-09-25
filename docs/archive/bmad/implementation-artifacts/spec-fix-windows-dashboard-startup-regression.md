---
title: 'Fix Windows dashboard startup regression'
type: 'bugfix'
created: '2026-07-16'
status: 'done'
baseline_commit: '9bffa89e957e7bbb735491356f37aae5195834d1'
context:
  - '{project-root}/_bmad-output/project-context.md'
  - '{project-root}/apps/windows/README.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** The Windows dashboard now starts slowly, often shows only sparse ETF/crypto rows with blank columns, and can omit stock tickers entirely. The new feed coordinator blocks startup on Yahoo session warmup, retries rate limits per symbol, and refuses to publish chart-recovered stocks until full fundamentals arrive; aggressive overlapping UI polling further competes with ingestion.

**Approach:** Restore progressive hydration: return from startup immediately, publish every usable ticker/price row as soon as lightweight data exists, enrich target/analyst/sector fields when full Yahoo data arrives, and coordinate retries/cooldowns once per batch. Make dashboard refresh single-flight and render explicit partial/loading states instead of visually empty sections.

## Boundaries & Constraints

**Always:** Preserve the user's existing uncommitted Windows changes; keep fixed-point financial fields and current Tauri/React boundaries; merge enrichment without erasing previously known fields; test behavior before implementation; validate provider-facing behavior against at least five live symbols; keep startup bounded and observable.

**Ask First:** Any SQLite schema change, new frontend test dependency, removal of existing scoring/search features, or change to the 500+ symbol universe.

**Never:** Wait on network I/O inside `start_feed`; require full fundamentals before a priced stock becomes visible; sleep independently for every symbol during one shared Yahoo cooldown; fabricate Yahoo fixtures; load complete saved history at startup; revert unrelated working-tree changes.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Healthy cold start | Empty state, Yahoo available | `start_feed` returns promptly; priority tickers appear progressively with price/name, then columns enrich | Sparse fields render `—`, never blank cells |
| Quote-summary failure | Chart price/name succeeds; fundamentals request fails or returns 429 | Stock ticker remains visible as provisional and is queued for enrichment | One coordinator cooldown/backoff; no per-symbol blocking sleep |
| Later partial refresh | Existing target/sector/analyst data plus price-only response | Price may update while prior enrichment remains intact | Partial data must not erase known values |
| Slow UI request | Poll fires while previous refresh is unresolved | At most one refresh is in flight and stale responses cannot replace newer rows | Continue polling after failure without dropping successful row data |

</frozen-after-approval>

## Code Map

- `apps/windows/src-tauri/src/commands.rs` -- feed orchestration, row ingestion, retry policy, opportunity snapshot command.
- `apps/windows/src-tauri/src/fetcher.rs` -- Yahoo full/partial fetch outcomes and chart fallback.
- `apps/windows/src-tauri/src/yahoo_session.rs` -- shared crumb and rate-limit cooldown state.
- `apps/windows/src-tauri/src/quote_summary.rs` -- completeness classification for progressively hydrated rows.
- `apps/windows/src/App.tsx` -- dashboard refresh scheduling and row/status state updates.
- `apps/windows/src/components/DashboardPanel.tsx` -- visible loading/empty states for partial startup data.
- `apps/windows/src/components/OpportunityList.tsx` -- missing-value rendering for provisional rows.

## Tasks & Acceptance

**Execution:**
- [x] `apps/windows/src-tauri/src/commands.rs`, `fetcher.rs`, `quote_summary.rs`, `yahoo_session.rs` -- add failing orchestration/merge/cooldown tests, then separate row visibility from enrichment completeness and make startup/cooldown batch-scoped.
- [x] `apps/windows/src/App.tsx` -- prevent overlapping refreshes and commit opportunities independently from status failures.
- [x] `apps/windows/src/components/DashboardPanel.tsx`, `OpportunityList.tsx` -- show progress/placeholders and normalize absent nullable values to `—`.
- [x] Provider/live verification -- sample at least five distinct real tickers and confirm progressive price/name plus eventual enrichment without request storms.

**Acceptance Criteria:**
- Given a cold launch with a slow or rate-limited Yahoo fundamentals endpoint, when the main screen opens, then the startup command is non-blocking and priority stock tickers become visible from usable price data rather than waiting for every column.
- Given full data later succeeds, when enrichment is merged, then target, gap, analysts, sector, scores, and decisions populate without removing the ticker or regressing known fields.
- Given dashboard polling is slower than its interval, when another tick occurs, then no overlapping request or stale overwrite occurs.
- Given the current working tree, when verification completes, then Rust tests and the TypeScript/Vite production build pass without reverting unrelated changes.

## Spec Change Log

- 2026-07-16: Implemented progressive stock readiness, enrichment-preserving merges, batch cooldown retries, 90-second initial cooldown, single-flight UI refresh, and explicit sparse/loading placeholders. Live Yahoo verification passed for AAPL, MSFT, NVDA, AMZN, and GOOGL.

## Design Notes

Treat “visible row” and “fully enriched row” as separate states. Progress may report attempted/visible work while a separate pending set drives bounded enrichment retries; a transient provider failure must not make already usable market data disappear.

## Verification

**Commands:**
- `cargo test` from `apps/windows/src-tauri` -- all regression and existing Rust tests pass.
- `npm run build` from `apps/windows` -- TypeScript and Vite production build succeed.
- `cargo fmt --check` from `apps/windows/src-tauri` -- Rust formatting is clean.
- Targeted manual mutations around readiness, merge preservation, cooldown, and single-flight guards -- new tests fail for each incorrect behavior.

**Manual checks:**
- Launch the Windows app from a cold state and inspect five live equity symbols: tickers/prices appear progressively, sparse cells show `—`, enrichment fills available columns, and the UI stays responsive during Yahoo throttling.

## Suggested Review Order

**Progressive startup and hydration**

- Start here: background orchestration publishes usable rows without synchronous Yahoo startup I/O.
  [`commands.rs:791`](../../apps/windows/src-tauri/src/commands.rs#L791)

- Centralized ingestion distinguishes visible partial rows from completed provider responses.
  [`commands.rs:267`](../../apps/windows/src-tauri/src/commands.rs#L267)

- Per-symbol refresh always retains daily charts while safely scheduling incomplete enrichment.
  [`commands.rs:687`](../../apps/windows/src-tauri/src/commands.rs#L687)

- Successful sparse provider responses stop retrying while keeping unavailable columns explicit.
  [`quote_summary.rs:343`](../../apps/windows/src-tauri/src/quote_summary.rs#L343)

**Preservation and throttling**

- Sparse signals, snapshots, and fundamentals merge without erasing known enrichment.
  [`engine.rs:2179`](../../apps/windows/src-tauri/src/engine.rs#L2179)

- Yahoo backoff begins at 90 seconds and resets only after protected-request success.
  [`yahoo_session.rs:28`](../../apps/windows/src-tauri/src/yahoo_session.rs#L28)

- Full-universe refresh and deep chart enrichment are bounded to avoid request storms.
  [`commands.rs:593`](../../apps/windows/src-tauri/src/commands.rs#L593)

**Frontend responsiveness**

- Dashboard refresh is single-flight and commits rows independently from feed-status failures.
  [`App.tsx:103`](../../apps/windows/src/App.tsx#L103)

- The reusable guard also normalizes synchronous failures and reentrant calls.
  [`singleFlight.ts:2`](../../apps/windows/src/singleFlight.ts#L2)

- Loading and sparse states show explicit placeholders instead of blank dashboard sections.
  [`DashboardPanel.tsx:26`](../../apps/windows/src/components/DashboardPanel.tsx#L26)

- Provisional table rows normalize unavailable nullable fields to em dashes.
  [`OpportunityList.tsx:100`](../../apps/windows/src/components/OpportunityList.tsx#L100)

**Regression evidence**

- Backend regression coverage proves partial visibility and preservation through centralized ingestion.
  [`commands.rs:627`](../../apps/windows/src-tauri/src/commands.rs#L627)

- Merge tests cover analyst-signal loss discovered during adversarial review.
  [`engine.rs:2630`](../../apps/windows/src-tauri/src/engine.rs#L2630)

- Live verification samples five distinct Yahoo equities without fabricated payloads.
  [`fetcher.rs:822`](../../apps/windows/src-tauri/src/fetcher.rs#L822)

- Frontend tests cover overlap coalescing, rejection recovery, and synchronous exceptions.
  [`singleFlight.test.ts:26`](../../apps/windows/tests/singleFlight.test.ts#L26)

**Results (2026-07-16):**
- `cargo test`: 52 passed, 1 ignored live-provider test; no failures.
- `cargo check` and `cargo fmt --check`: passed (pre-existing warnings remain).
- `npm test`: single-flight regression test passed.
- `npm run build`: passed; Vite reports only the existing large-chunk advisory.
- Live ignored test run explicitly: AAPL, MSFT, NVDA, AMZN, and GOOGL each returned a usable price/name and complete target/analyst/sector enrichment.
- Manual mutations confirmed tests fail when progressive stock visibility, enrichment preservation, the 90-second first cooldown, or single-flight coalescing is removed.
