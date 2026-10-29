# Decision rule V3.1 — technically adjusted Act check

Decided by Juan on 2026-10-29. Applies to the AggressiveV3 Opportunities decision in Android core
and in headless (android semantics). Market context stays OFF in headless by default.

## Rule

    S_adj = S · exp(−λ_T · max(0, −T) / 100) · exp(−λ_M · max(0, −m))

- `S`: raw V3 composite (unchanged; **ranking still sorts on raw S**).
- `T`: V3 technical bucket (−100..100).
- `m`: MACD momentum in [−1, 1] (below).
- Defaults `λ_T = 1.5`, `λ_M = 0.3`.

Decision chain (only the Act line changed):

1. confidence Low, upside ≤ 0, or **raw** S < 0 → Avoid (unchanged)
2. trust signal present → Watch (unchanged)
3. confidence High and **S_adj ≥ 30** → Act
4. otherwise Watch

Only negative T and m penalise; T ≥ 0 and m ≥ 0 give `S_adj == S`. A missing T or m contributes no
penalty. Weak technicals can turn Act into Watch, never into Avoid.

Examples: S 36, T −18, m ≥ 0 → 27.47 (Watch). S 31, T 0, m −1 → 22.97 (Watch).

## Momentum m

    m = clip((histogram / close) / 0.005, −1, 1)

`histogram` = MACD(12, 26) − signal(9), from the same Android `ChartRange.Year` (`1y`/`1wk`) summary
the technical bucket already reads, in cents (rounded half-even on both sides); `close` = that
summary's latest close.

Why: the histogram is the momentum *change* (what the MACD panel shows as bearish/bullish),
dividing by price makes it comparable across tickers, and 0.005 (0.5% of price = full strength)
is the existing `V3_TECH_HISTOGRAM_BOUND` already used inside T, so no new tuning constant is
introduced. ATR scaling was considered but ATR is not part of the Android summary on both sides;
price scaling needs no new indicator and is bit-identical.

## Where

| | Android core | Headless |
|---|---|---|
| constants + formulas | `core/engine/V31ActAdjustment.kt` | `src/opportunity_v31.rs` |
| decision chain | `ScreenDataProjectionEngine.opportunityDecision` | `headless.rs` `android_project_row` |
| output | `ProjectedOpportunityRow.sAdj`, `.macdMomentum` | `AndroidRow`/`RankedRow` `s_adj`, `m`; `/ranking` `rule_version` |

## Parity

`tools/android-jvm-parity/diff_headless.py` checks the constants (λ_T, λ_M, bound, version) are
equal in both sources, compares `sAdj`/`m` per row with absolute tolerance `1e-9` and the decision
exactly.
