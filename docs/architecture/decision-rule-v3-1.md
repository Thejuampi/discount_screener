# Decision rule V3.1 — SHADOW MODE

Status: **shadow only** (Juan, 2026-10-29: observe before picking a default). The official
Act/Watch/Avoid decision, ranking and `rule_version` (`V3`) are exactly V3 on Android and headless.
V3.1 is computed next to it for a fixed grid of configs and recorded for later evaluation.

## Shadow rule (per config)

```
m        = clip((histogram / close) / bound, -1, 1)
S_adj    = S · exp(−λ_T · max(0, −T) / 100) · exp(−λ_M · max(0, −m))
shadow   = Watch  if official == Act and S_adj < 30
           official otherwise            (Avoid / Watch untouched; S_adj ≤ S so it can only demote)
```

S = V3 composite, T = V3 technical bucket, histogram = MACD(12,26) − signal(9) of the Year
(`1y`/`1wk`) chart summary in cents (rounded half-even on both sides), close = that summary's
latest close. λ_T = 1.5 for every config.

## Config grid (stable ids — append only, never renumber)

| config_id | bound | λ_M |
|---|---|---|
| b0050_lm015 | 0.005 | 0.15 |
| b0050_lm030 | 0.005 | 0.30 |
| b0200_lm015 | 0.02 | 0.15 |
| b0200_lm030 | 0.02 | 0.30 |
| b0500_lm015 | 0.05 | 0.15 |
| b0500_lm030 | 0.05 | 0.30 |

Id = `b<bound·10⁴, 4 digits>_lm<λ_M·100, 3 digits>` (a Rust test enforces it).

Bound context (2026-10-29 snapshots, weekly bars, ranked rows), |hist/close| percentiles:
sp500 p50 1.81% / p75 2.45% / p90 4.08% / p95 4.79% / p99 5.67%;
russell 1.55% / 2.81% / 4.19% / 5.45% / 9.34%. At 0.005 |m| = 1 for 80–90% of rows (m ≈ sign),
at 0.05 for < 10%.

## Where

| | Android core | Headless |
|---|---|---|
| constants, grid, formulas | `core/engine/V31ActAdjustment.kt` | `src/opportunity_v31.rs` |
| wiring | `ScreenDataProjectionEngine.v31Shadow` → `ProjectedOpportunityRow.shadow` | `rank_android_v3` → `AndroidRow.shadow` / `RankedRow.shadow` |
| inputs | `ProjectedOpportunityDecisionFacts.technicalScore`, `.macdHistogramRatio` (never read by the official decision) | `technical`, `histogram_ratio(year)` |
| storage | none (Android has no score-history store; it computes shadow values for parity only) | `score_shadow` in `history.sqlite` |

JSON: rows gain an optional `shadow` array (`config_id, bound, lambda_t, lambda_m, m, s_adj,
shadow_decision`); no existing field changes and `/ranking` is unchanged otherwise.

## Parity

`tools/android-jvm-parity/diff_headless.py` checks `SHADOW_RULE_VERSION`, `LAMBDA_T`,
`ACT_THRESHOLD` and the full config list (ids, bounds, λ_M, order) in both sources, compares all
official fields exactly as before, and compares every shadow config per row (`m`, `sAdj` within
1e-9; `shadowDecision` exact).

## Evaluation

See [decision-rule-v3-1-shadow-evaluation.md](decision-rule-v3-1-shadow-evaluation.md).
