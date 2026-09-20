# Cross board spec v1

Android Plans tab, third hunt. Dip and Leftover stay as they are.

This filter does **not** change V2, V3, or V4 composites. `RANKING_INCLUDES_QUANT_ENGINE` stays false.

Windows Act / Scale / Wait stays as it is. This slice is Android-only.

## Job

Show names whose 1Y MACD histogram is at the golden cross, or at most `flipped_bars_max` closed daily bars after it.

1. Good F on the active model.
2. MACD histogram `> 0` on cached Year daily bars.
3. Bars since the first positive histogram in `[0, flipped_bars_max]`.
4. Histogram slope `> 0` (still expanding). Fade belongs to Leftover.
5. Street 12-month target ≥ 20% above last for Now.
6. No ATR dip gate. No RSI 25–45 band.
7. DCF / residual income is a tag only.

## Universe

Name the universe on the board.

Default is `opportunities` (current Opps rows). A session **Full profile** switch scans the current profile (`trackedSymbols`) with the same Cross gates. The switch is the same session flag Dip uses.

The tab does not fetch extra Yahoo data. It reads cached Year candles, cached FiveYears candles, and cached valuation.

Profile `qa` is small. A full AND name may not appear on a live `qa` device. Prove the AND with fixtures. Do not switch to `sp500` for QA.

## Locked cuts

| Token | Value | Role |
| --- | --- | --- |
| `FLIPPED_BARS_MAX` | `3` | Inclusive bars after the first hist `> 0`. Policy knob. |
| `MACD` | `12 / 26 / 9` | Same as `ChartAnalysis` / Dip |
| `MACD_N` | `3` | Slope lookback, reuse Dip tape |
| `RSI_HOT` | `55` | Above this is Out |
| `STREET_NOW_BPS` | `2000` | Now |
| `STREET_ALMOST_BPS` | `1500` | Inclusive lower Almost band |
| `F_FLOOR` | `0` | Active-model F. Null fails |
| `NOW_CAP` | `120` | After total order |
| `LATER_CAP` | `80` | Almost, after same order |

## Bars since golden cross

Use closed daily Year candles as stored. Compute MACD histogram on close cents. Same 12/26/9 as the chart.

Walk the histogram series from the last bar backward while `hist > 0`.

| Last hist | First `≤ 0` found | `barsSinceCross` |
| --- | --- | --- |
| `≤ 0` | n/a | `null` — not a Cross name |
| `> 0` | at index `i` | `lastIndex - i - 1` (`0` = cross bar) |
| `> 0` | none in the series | series length — stale, Out |

Eligible Now/Almost: `barsSinceCross` in `[0, FLIPPED_BARS_MAX]`.

`4` with default max `3` is Out (`macd_stale`). Do not park stale names in Almost.

## Lanes

Hard filters (must hold for Now and Almost):

- F present and `≥ 0`
- Tape present
- MACD available
- Histogram `> 0`
- `barsSinceCross` in `[0, FLIPPED_BARS_MAX]`
- Histogram slope `> 0`
- Not knife (same Dip knife: RSI slope `< 0` and RSI accel `< 0` and MACD slope `< 0` and MACD accel `< 0`)
- RSI known and `≤ 55`
- Street present, not thin, `≥ 1500` bps

Now adds Street `≥ 2000` bps.

Almost = hard filters pass and Now fails (Street in `[1500, 2000)`).

Empty Now stays empty.

## 5Y MACD overlay

Cache-only `ChartRange.FiveYears`. Missing 5Y is 0, not Out. 1Y still leads rank.

| Token | Value | Role |
| --- | --- | --- |
| `ALIGN` | `+3` | 1Y expanding (`hist > 0`, slope `> 0`) **and** 5Y expanding |
| `DRAG` | `−1` | 5Y hist `< 0` and slope `≤ 0` |
| `FLAT` | `0` | Missing 5Y, or neither |

Rank overlay only. Not a Now gate.

## Rank

Total order, then cap. Never sort by Σ. Never sort by DCF.

1. `barsSinceCross` ascending (`0` first)
2. 5Y overlay score descending
3. Street bps descending
4. F descending
5. Symbol ascending

## UI

Plans hunt tabs: Dip | Cross | Leftover. Session-only. Default Dip.

Compose is a passive View. All Cross rules live in `:core` `CrossSignalEngine`.

Show Now and Almost cards. Show the named universe. Show refuse count. Street line says 12-month target.

## Out of scope (v1)

- Windows Dashboard 2.0 rewrite
- Extra Yahoo
- V2 / V3 / V4 score edits
- Changing Dip `Flipped` → Never Now
- Filling Now from a stale cross
