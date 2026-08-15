# Leftover board spec v1

Android Plans tab, second hunt. Dip hunter stays as it is.

This filter does **not** change V2, V3, or V4 composites. `RANKING_INCLUDES_QUANT_ENGINE` stays false.

Windows Act / Scale / Wait stays as it is. This slice is Android-only.

## Job

Show names on the **current profile** whose Street leftover is gone or tiny, and whose tape is fading.

Evidence only. Do not say sell, harvest, or may go down.

## Universe

Name the universe on the board. v1 uses the **current profile** (`trackedSymbols`: `sp500`, `russell`, `qa`, …).

Do **not** scan Opps. Opps is an upside filter. Names that already ran leave it.

Do **not** scan Discovery US.

The tab does not fetch extra Yahoo data. It reads cached Year candles and cached Street / F / DCF.

Profile `qa` is small. A full AND name may not appear on a live `qa` device. Prove the AND with fixtures. Do not switch to `sp500` for QA.

## Locked cuts

| Token | Value | Role |
| --- | --- | --- |
| `STREET_DOOR_BPS` | `500` | `checkedUpsideBps(last, 12-month target) ≤ 500` |
| `STRETCH_PRIMARY_MAX` | `1.0` | `(20d_high − last) / ATR14` |
| `STRETCH_OUT` | `2.0` | Above this is Out |
| `RSI_HOT` | `55` | RSI fade needs `>` this and slope `< 0` |
| `MACD fade` | hist `≥ 0` and hist slope `< 0` | One family with RSI |
| `NOW_CAP` | `120` | After leftover, then stretch, then symbol |
| `LATER_CAP` | `80` | Same order |

## Street door

Use `checkedUpsideBps`. Missing last or target → refuse. Coverage `0` when present → thin → refuse.

Label the percent as a **12-month target**.

- `≤ 500` can be primary or review.
- `> 500` is Out.

## Tape

Reuse `DipSignalEngine.measureTape`. Fail closed on a short series.

Fade is **one** family: RSI `> 55` and slope `< 0`, **or** MACD hist `≥ 0` and slope `< 0`. Do not count both as two votes.

## 5Y MACD overlay

Cache-only `ChartRange.FiveYears`. Do not fetch extra Yahoo. Missing 5Y is 0.

Invert the dip sense. Fade is the good side.

| Token | Value | Role |
| --- | --- | --- |
| `ALIGN` | `+3` | 1Y fade **and** 5Y fade (`hist ≥ 0`, slope `< 0`) |
| `DRAG` | `−1` | 5Y still expanding (`hist ≥ 0`, slope `> 0`) |
| `FLAT` | `0` | Missing 5Y, or neither |

Not a leftover door. Rank only.

Stretch: last still near the 20-day high (`dipAtrUnits ≤ 1.0`). Already dumped `> 2.0` ATR is Out.

## Lanes

Primary (Now) needs leftover door **and** fade **and** stretch `≤ 1.0`.

Review (Almost) needs leftover door. Fade is not on, or last is not near the high.

Out: leftover open, thin Street, missing tape, dumped `> 2` ATR.

Empty primary stays empty. Do not fill it from review.

F, DCF / residual income, death / golden cross are **tags**. Missing F can still be primary. Soft model cannot crown a rank.

## Rank

Total order, then cap. Never sort by Σ. Never sort by DCF. Never sort by fade-flag count.

1. Street leftover bps ascending
2. Stretch ATR units ascending
3. 5Y overlay score descending
4. Symbol ascending

## UI

Plans hunt tabs: Dip | Leftover. Session-only. Default Dip.

Compose is a passive View. All leftover rules live in `:core` `LeftoverSignalEngine`.

## Out of scope (v1)

- Windows Dashboard 2.0 rewrite
- Discovery US
- Holdings / P&L
- Extra Yahoo
- V2 / V3 / V4 score edits
- DCF as a gate
- Crypto / short
