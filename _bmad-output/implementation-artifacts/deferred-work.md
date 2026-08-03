# Deferred Work

## 2026-08-02 — Quant / valuation motor (handover)

Source: [`handover-quant-valuation-engine-2026-08-02.md`](handover-quant-valuation-engine-2026-08-02.md)

- **P0 (active):** Fix owner-earnings **maintenance CapEx** so CHTR-class structural network CapEx is not treated as nearly all growth (FCFF/sh ~$141 fails sniff vs ~$30–70). No ticker ifs; multi-name baseline must stay green; do not blindly undo AMZN OE path.
- **Blocked on P0:** Re-close EPS-vs-FCFF for CHTR; re-run attribution section-3 method column as “clean evidence.”
- **Open process workstreams:** continuous CoE risk function; CapEx cycle regime (MPWR/WDC); high-signal 26/26; version governance hardening.
- **Separate project (do not assume):** PIT fundamentals store + empirical fade calibration + primary PIT driver backtest.
- **Deferred doc:** full `valuation-policy-calibration-process.md` until FCFF run-rate sniff is honest (schema of attribution is already in contract).
- **Agreed findings to preserve:** horizon inert when hold/fade match baseline; CHTR vs T are different overvalue mechanisms; naive FCFF must use WACC+net debt (CoE-on-FCFF was a bug).

## Deferred from: code review of plan-foundation-0a-execution-2026-08-01.md (2026-08-02)

- Quant Lens still runs demand FCFF/model routing for the core report when opening the panel — pre-existing Detail/QL path, not a FEM ranking write. Address only if 1C is redefined to forbid any valuation demand side-effect when reading the diagnostic lane.
- `QuantLensSection` is untyped regarding diagnostic-only membership; a future refactor that recomputes `worst_status` over all sections could re-pollute `primary_status`. Add a typed diagnostic flag when the section model is next revised.

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

## 2026-07-30 — Detail decision-summary clarity

- source_spec: none
  summary: Make the Detail analysis summary present its operational plan as primary and neutralize the raw score-decision copy so “wait” is never shown beside “good investment timing.”
  evidence: The user chose the recurrent SEC-to-QuantEngine data-normalization issue as the first independent deliverable; this presentation fix can be reviewed and shipped separately.
- source_spec: `_bmad-output/implementation-artifacts/spec-evidence-routed-operating-valuation-core.md`
  summary: Make legacy Windows cost-of-equity resolution return a typed refusal on extreme fixed-point rate inputs.
  status: resolved 2026-07-31
  evidence: `dcf_model::resolve_cost_of_equity` now uses checked integer arithmetic and returns typed invalid-market/arithmetic-overflow/out-of-range failures; `cost_of_equity_extremes_refuse_instead_of_saturating` covers the boundary.
