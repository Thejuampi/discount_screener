# Valuation multi-name baseline policy

**Status:** active  
**Date:** 2026-07-30  
**Fixture:** `apps/windows/src-tauri/tests/fixtures/valuation/baseline_cohort_2026-07-30.json`  
**Tests:** `apps/windows/src-tauri/src/valuation_baseline.rs` (module `valuation_baseline`)

## Why

A fix that improves one ticker (e.g. T levered soft WACC) must not silently destroy others (e.g. AMZN-class mega-cap collapse). Valuation is a **pure function of pinned inputs** — same inputs → same outputs — so multi-name regression is enforceable offline.

## Rule (merge bar)

**Valuation policy / engine changes that affect FCFF, WACC, growth, business-class routing, or CapEx→FCF construction require the multi-name baseline suite green** before claiming merge quality.

- Single-ticker green alone is **not** sufficient.
- Live screener re-rank is **not** the CI gate; the **pinned cohort fixture** is.

## Cohort definition (pinned)

1. Taken from the running Windows app’s `history.sqlite` (reuse process; do not start a second instance).
2. Latest snapshot per symbol where `confidence = High` and `gap_bps >= 2000` (20%+ discount).
3. Ordered by `gap_bps` DESC; top **20** symbols selected.
4. Driver snapshots: SEC companyfacts OCF − CapEx (PPE / ProductiveAssets) + share count; market price from selection snapshot.
5. Names that cannot form a valid FCFF path offline are **replaced** by the next High + ≥20% discount names that have usable SEC drivers — the pinned top-20 fixture must stay **20/20 non-quarantined**. Temporary quarantine with reason is only for mid-rebuild states, never a silent green.
6. **Quarantine ≠ success.** A green suite with open quarantine slots does **not** satisfy goals that require N active names. Either fix drivers, replace the slot, or **explicitly** rewrite acceptance to a reduced N (never claim “20 green” with quarantines).

## What tests enforce

| Check | Behavior |
| --- | --- |
| Determinism | Double `compute` on same fixture inputs → identical base/bear/bull/WACC |
| Sanity | Ordered scenarios; no absurd collapse: penny intrinsic; **base &lt; 10% of market** with material FCF; or **base &lt; 1/8 of selection-time intrinsic** only when selection ≤ 1.5× market (ignore inflated prior-model selection) |
| Recovery run-rate | When latest FCF &gt; 1.25× window mean, blend 50/50 latest+mean so CapEx-trough recovery is not diluted (engine `fcf_run_rate_dollars`) |
| Isolation | T-class levered soft stress remains in band **and** active cohort does not collapse |
| Mega-cap | AMZN-class CapEx-trough path not penny / not inverted |
| Managed care | CI-class residual income, not FCFF-primary on float |
| Financials | ACGL-class residual income, not FCFF-primary |
| Quarantine | **0** for the 20-slot fixture; labeled mid-rebuild only |

## High-signal screener cohort (goal gate, 2026-08-02)

**Contract:** `shared/contracts/valuation-high-signal-screener-cohort-v1.json`  
**Tests:** `cargo test --lib valuation_high_signal::`  
**Observation pin:** `apps/windows/src-tauri/tests/fixtures/valuation/high_signal_screener_observation_2026-08-02.json`

Separate from the 20-slot baseline merge bar: this is a **product goal integration** over the 26 names that were live-visible when “all soft / no confiable aún” was diagnosed. The test **recomputes** each name (Yahoo + SEC + live US 10Y) and **passes only when all 26 are high-signal** (solid rates/drivers, scale-coherent vs market, correct class, Street disagreement ≤ policy band). Soft provisional bootstrap is not a success path. Forbidden: clamp-to-Street, quarantine-as-green, single-ticker “wins,” hand-editing the observation audit to green.

**Status at handoff 2026-08-02:** recompute gate still open (~16/26 last observed). Engine work continues until 26/26 without clamps. Continuity brief: [`handover-quant-valuation-engine-2026-08-02.md`](handover-quant-valuation-engine-2026-08-02.md).

## Gap attribution (telemetry, not merge bar)

**Contract:** `shared/contracts/valuation-gap-attribution-v1.json`  
**Tests:** `cargo test --lib valuation_gap_attribution::`  
**Live diagnostic cohort:** CHTR, T, MPWR, WDC, GOOGL  

Shapley policy-delta waterfall. Street diagnostic only. Method column `v_naive_fcff_baseline` must use **WACC + net-debt bridge**. CHTR FCFF/sh from owner-earnings must pass external sniff (~$30–70) before any EPS-vs-FCFF conclusion — see handoff §3.3 / §4 P0.

## Extending the cohort

After the first 20 are green and stable, extend with additional High-confidence, high-score names using the same pin→fixture→sanity workflow. Do not live-depend on ranking inside tests.

## Anti-patterns

- Golden only on one symbol (T or AMZN).
- Clamping intrinsic to price or Street to pass.
- Silent skip of failed names.
- Claiming policy/2+ ready without `cargo test valuation_baseline`.
