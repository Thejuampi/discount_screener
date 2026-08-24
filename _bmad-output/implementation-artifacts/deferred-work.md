# Deferred Work

## 2026-08-16 — Honest path and Street stretch

Source: [`handover-honest-path-street-stretch-2026-08-16.md`](handover-honest-path-street-stretch-2026-08-16.md)

- **P0 (done on Android 2026-08-16):** Live QA on profile `qa` after identity-visible CapEx/interest. AAPL remesure: SEC FCFF forms, red coupon caveat on Detail, Street primary on a 12361 bps fan. Note: [`live-qa-android-honest-path-2026-08-16.md`](live-qa-android-honest-path-2026-08-16.md). Debt-engine remesure the same day: [`live-qa-android-debt-engine-2026-08-16.md`](live-qa-android-debt-engine-2026-08-16.md). T/AMZN/AAPL show current instrument yield. CI/UNH/JPM stay residual. Windows live QA waits for the later port.
- **P1:** Windows still lacks Android `persist_frac`, expand-without-franchise, residual-path `/4`, NonHonest stretch UI, `coupon-resolution/1`, and `debt-resolution/1`. **Juan deferred the port (2026-08-16).** Do not start it.
- **P2:** NonHonest is a published diagnostic. Do not switch working mode, and do not build a multi-knob Street mix, unless Juan asks.
- **Do not:** retune CMCSA / AMAT / DELL / CPAY / DASH honest knobs to this holdout’s Street numbers.
- Honest extraction on this holdout is exhausted. Remaining gaps are Street vs identity.
- **Coupon estimates (Android `coupon-resolution/1`, 2026-08-16):** Juan locked confidence-based fill. Own last effective rate first. Else similar-issuer median. Filed tags replace estimates. Windows still drops the hole year.
- **Debt engine (Android `debt-resolution/1`, 2026-08-16):** Owns stock, coupon, and published k_d. Estimates stay in year-cash. `issuer-market-yield/2` attaches a current USD instrument yield from Markets Insider (median of remaining 4–15 year quotes). The yield sets k_d and keeps the tax-year window. Empty when the issuer or tenor has no quote. Windows port stays closed.
- **Factory plus lender (`component-sotp/2`, Android 2026-08-16):** Mixed industrial + finance filings value each part and add them. Factory cash adds depreciation back. Windows port stays closed. Live Android QA on General Motors waits until Juan asks.
- **Policy book (`valuation-policy/1`, Android 2026-08-16):** Engine knobs live in `shared/contracts/valuation-policy.yaml`. Windows and desktop still keep their own literals. Do not start that port.

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

## Deferred from: code review (2026-08-21)

- Pulse corroboration on thin/loss histories [medium] — positiveLevelTransitions drops loss years, so perpetual loss-makers now score an uncorroborated quarter EPS rate, stale profit-year pairs (2018-2020) can validate a current quarter, and isForeignTo is inert below three transitions. Old garbage math refused; new math accepts. Needs a stated corroboration policy (accept-unmarked vs refuse vs flag), which is a product call.
- Fail-open unknown sectorKey in FinancialClassPolicy [medium] — a bank whose assetProfile fails to parse carries null sector key and scores as a levered industrial. KDoc documents the choice; repo canon prefers refuse-with-reason. Needs a policy decision before changing.
- replayScreen task references untracked ScreenReplayKt sources [low] — pre-existing branch hunk that rode into the reviewed diff; the Gradle task fails on a clean checkout until core/src/main/kotlin/com/discountscreener/core/replay/ is committed.

## Deferred from: code review of spec-valuation-judgment-core-2026-08-15 (2026-08-21)

- Dip and Leftover keep their own quality rule without the provisional-WACC term [medium] - ``DipSignalEngine`` and ``LeftoverSignalEngine`` (core/plan) classify model quality from pointEstimateUnreliable, fan order, and fan width only; they never read ``waccInputs.isProvisional()``, so a provisional-WACC analysis can read Solid on those boards while Quant Lens reads Soft. Pre-existing code untouched by the reviewed diff. Adopting ``ValuationDecisionPolicy.isSoftModel`` would change board tags; that is a product call against the dip/leftover board specs.
- scenarioWidthBps can throw on pathological fans [low] - ``intValueExact()`` raises ArithmeticException when width exceeds Int range (bull/base ratio above ~21 400x). Pre-existing at every caller, including the two private softness copies this diff consolidated. A checked variant or try/catch-to-null would read such input as wide/soft instead of crashing.

## Deferred from: code review (2026-08-23)

Product call answered in [`product-call-android-fcf-score-2026-08-23.md`](product-call-android-fcf-score-2026-08-23.md). Shipped on Android: Q1 B equity cap, Q2 B class refuse, Q4 B OCF 0–10% (unmeasured flag), Q5 B class-only adaptive budget, Q6 B comparisons, Q7 B median after failed trim, Q8 C TTM when `§`. Q3 N/A. Q9 A and Q10 A: no code.

Still open:

- Windows still scores FCF / market cap and the old sign vote. Android ships alone (Q10 A).
- `Mult§` can mix a sector P/E with an absolute P/B and still say vs sector. Pre-existing panel rule.
- `formatDollars` jumps from `$999,999` to `$1M` with zero decimals on millions.
- Long Score comparison lines have no `maxLines`. Phone wrap is a layout call.
- `sizeForCashVote` copies period-average diluted shares into outstanding when cap is missing. That is not market cap.
- V3 FCF yield stays TTM (Q9 A).
- `shared/contracts/opportunity-v4.json` has no FCF cases.
- OCF 0–10% band is provisional. Calibrate once real OCF/FCF ratios exist.
- Q8 accepted asymmetry: a `§` sector scores FCF on TTM; a sector without a centre can use the multi-year series.
