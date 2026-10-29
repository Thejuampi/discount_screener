# Decision rule V3.1 — SHADOW MODE

Status: **shadow only** (Juan, 2026-10-29: observe before picking a default). The official
Act/Watch/Avoid decision, ranking and `rule_version` (`V3`) are exactly V3 on Android and headless.
V3.1 is computed next to it for a fixed grid of configs and recorded for later evaluation.
**The m bound (and λ_M) is undecided.** Shadow mode exists to observe the grid on real forward
returns before any default is chosen; nothing here is a recommendation.

## Shadow rule (per config)

```
m        = clip((histogram / close) / bound, -1, 1)
S_adj    = S · exp(−λ_T · max(0, −T) / 100) · exp(−λ_M · max(0, −m))
shadow   = Watch  if official == Act and S_adj < 30
           official otherwise            (Avoid / Watch untouched; S_adj ≤ S so it can only demote)
```

S = V3 composite, T = V3 technical bucket, histogram = MACD(12,26) − signal(9) of the Year
(`1y`/`1wk`) chart, close = that summary's latest close (cents). λ_T = 1.5 for every config.

- **Unrounded histogram.** The shadow uses the unrounded histogram (`histogramRawCents` /
  `histogram_raw_cents`), never the official rounded `histogramCents`. That matters because the two
  sides round the official cents differently (Kotlin `roundToLong` is half-up, Rust `round_even`
  is half-even). Using raw values keeps the shadow identical on both sides, within 1e-9. The
  official rounded field and its rounding are unchanged. Summaries persisted before the raw field
  existed, such as the current daemons' `opportunity_snapshot.json`, fall back to the rounded
  cents on both sides. There the two rounding modes can differ only on an exact half-cent tie: at
  most 0.5 cent of histogram.
- **Warm-up.** With fewer than 35 weekly bars (26 + 9 for MACD + signal), m is null, `warmup` is
  true, and no momentum penalty applies. The T term still applies. The flag is stored per row.
- **Partial week.** The last weekly bar is the current, possibly incomplete, week, exactly as the
  official V3 technicals see it. So m can move intra-week. Each recorded run captures the value
  at its own time.
- **Missing inputs.** A missing T or a missing histogram/close
  gives no penalty for that term.

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

JSON: headless `/ranking` and `rank-snapshot --json` rows (and Android parity output) carry an additive,
optional `shadow` array (`rule_version` (`V3.1-shadow`), `config_id, bound, lambda_t, lambda_m, m, warmup,
s_adj, shadow_decision`; headless also `hist_source` = `raw`/`rounded`, omitted when m is null). Histogram
input: unrounded when available, else the rounded cents (older snapshots). Every row carries its own provenance; no existing field changes and `/ranking` is unchanged otherwise.

## Parity

`tools/android-jvm-parity/diff_headless.py` runs three checks, and a mismatch in any of them fails parity:

1. **Source check.** `SHADOW_RULE_VERSION`, `LAMBDA_T`, `ACT_THRESHOLD`, `MIN_WEEKLY_BARS` and the full config list (ids, bounds, λ_M, order) must match between the two source files.
2. **Runtime check.** The constants each side actually uses are compared: `v31_constants.json`, which the Kotlin parity run writes, against the headless dump test's output.
3. **Row check.** All official fields are compared exactly, as before. Every shadow config is compared per row: `m` and `sAdj` within 1e-9; `ruleVersion`, `configId`, `bound`, `lambdaT`, `lambdaM`, `warmup` and `shadowDecision` exactly.

Mutating a Kotlin default (a bound, λ_T, or the clip) fails parity. Unit tests on both sides pin λ_M (S=40, T=0, m=−1 → 40·e^−λ_M for every config) and λ_T. They also kill these mutations: dropping max(0,·), clip ±0.5, no clip, no clamp, and a changed bound. S_adj = 30 stays Act. CI runs the `opportunity_v31` and `score_` tests.

## Evaluation

See [decision-rule-v3-1-shadow-evaluation.md](decision-rule-v3-1-shadow-evaluation.md).
