# Dip board spec v1

Android Plans tab. Locked after Sensei + Advisor revise (P0s 001–008, ADV-001–006).

This filter does **not** change V2, V3, or V4 composites. `RANKING_INCLUDES_QUANT_ENGINE` stays false.

Windows Act / Scale / Wait stays as it is. This slice is Android-only.

## Job

Show names that match Juan's hunt:

1. Good F on the active model.
2. A real dip vs the 20-day high.
3. RSI oversold and easing.
4. MACD histogram still ≤ 0 and turning toward a golden cross.
5. Street 12-month target ≥ 20% above last.
6. DCF / residual income is a tag only.

## Universe

Name the universe on the board. v1 uses `opportunities` (current Opps rows).

The tab does not fetch extra Yahoo data. It reads cached Year candles and cached valuation.

Profile `qa` is small. A full AND name may not appear on a live `qa` device. Prove the AND with fixtures. Do not switch to `sp500` for QA.

## Locked cuts

| Token | Value | Role |
| --- | --- | --- |
| `DIP_ATR_MIN` | `1.0` | `drawdown_vs_20d_high / ATR14` |
| `ATR_PERIOD` | `14` | Wilder ATR |
| `DIP_RANGE` | `20` | High window, daily bars |
| `MACD` | `12 / 26 / 9` | Same as `ChartAnalysis` |
| `MACD_N` | `3` | Slope and accel lookback |
| `IMMINENT_HIST_ATR` | `0.25` | `\|hist\| / ATR` |
| `RSI_PERIOD` | `14` | Wilder |
| `RSI_NOW` | `[25, 45]` | Inclusive |
| `RSI_HOT` | `55` | Above this is Out |
| `RSI_EASING` | `slope > 0` | Sign only |
| `STREET_NOW_BPS` | `2000` | `(base − last) / last` |
| `STREET_ALMOST_BPS` | `1500` | Inclusive lower Almost band |
| `F_FLOOR` | `0` | Active-model F. Null fails |
| `NOW_CAP` | `6` | After total order |
| `LATER_CAP` | `4` | Almost, after same order |

## Tape

Use closed daily Year candles as stored. Do not invent bars.

`drawdown = 20d_high − last_close`. Fail closed when ATR is missing or the series is too short for ATR, 20-day high, RSI slope/accel, or MACD slope/accel.

Dip units = `drawdown / ATR14`. Need `≥ 1.0`.

## MACD

Compute on close cents. Same 12/26/9 as the chart.

`slope = hist[last] − hist[last − n]`, `n = 3`.

`accel = slope[last] − slope[last − n]` (same n on the slope series).

| Phase | Rule |
| --- | --- |
| `Unavailable` | Series too short. Out. |
| `Imminent` | `\|hist\| / ATR < 0.25` |
| `Turning` | `hist ≤ 0` and `slope > 0` and `accel > 0` |
| `Flipped` | `hist > 0`. Tag only. Never Now. |
| `Distant` | Else |

Now needs `hist ≤ 0` and (`Imminent` or `Turning`).

## RSI

Use the same Wilder level, slope, and accel as Detail / `ChartAnalysis` (smoothed derivative of the chart RSI signal). Do not use a second n=3 lookback for RSI.

Now needs RSI in `[25, 45]` and `slope > 0`.

Hot: RSI `> 55` → Out.

Knife **before** Now / Almost: RSI slope `< 0` **and** RSI accel `< 0` **and** MACD slope `< 0` **and** MACD accel `< 0`.

## Street

`checkedUpsideBps(last, street_base)`. Missing or non-positive last/base → refuse.

Coverage `0` when the field is present → thin → refuse.

Label the percent as a **12-month target**, not a bounce.

- `≥ 2000` can be Now.
- `[1500, 2000)` can be Almost.
- `< 1500` is Out.

## F

Use the active-model fundamentals score. Null is Out. `F < 0` is Out.

## Valuation tag (not a gate)

Cache only. Two fields:

1. Relation from `ValuationDecisionPolicy` (2500 / 5000): Aligned / Tension / Disputed / SingleSource / Unavailable.
2. Quality: Solid or Soft (`pointEstimateUnreliable` or unordered / wide scenarios → Soft).

Do not drop Tension. Do not sort by this tag.

Residual income is **not** labeled DCF. Use `Residual income` or `FCFF DCF`.

Unclassified / not eligible / wrong model for class → no model tag.

## Death cross

Year `EMA50 < EMA200` (same as Detail `E50/E200` bear) stays **in**. Tag it. It does not refuse. Do not depend on Opps signal tokens.

## Lanes

Hard filters (must hold for Now and Almost):

- F present and `≥ 0`
- Tape present
- Dip units `≥ 1.0`
- Not knife
- RSI known and `≤ 55`
- MACD available
- Street present, not thin, `≥ 1500` bps

Now adds:

- RSI in `[25, 45]` and easing
- `hist ≤ 0` and (`Imminent` or `Turning`)
- Street `≥ 2000` bps

Almost = hard filters pass and Now fails.

Empty Now stays empty. Do not fill it with Almost.

## Rank

Total order, then cap. Never sort by Σ. Never sort by DCF.

1. MACD phase: Imminent, Turning, Flipped, Distant
2. Street bps descending
3. Dip ATR units descending
4. F descending
5. Symbol ascending

## UI

Compose is a passive View. All rules live in `:core`.

Show Now and Almost cards. Show the named universe. Show refuse count. Street line says 12-month target. Do not show Σ as the rank key.

## Out of scope (v1)

- Windows Dashboard 2.0 rewrite
- Crypto / short
- Extra Yahoo
- V2 / V3 / V4 score edits
- Filling Now from a weak universe
