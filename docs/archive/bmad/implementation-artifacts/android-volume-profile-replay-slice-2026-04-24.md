# Android Volume Profile Replay Slice

Date: 2026-04-24

## BMAD Party Output

### Problem

The desktop workstation has useful candle replay and a right-side volume profile in ticker detail, but Android only stores a replay offset and renders a simple volume histogram. Android users cannot scrub backward through a historical chart window or inspect where visible volume concentrated by price.

### Requirement

Implement an Android detail-chart experience that improves the desktop behavior for touch:

- The Snapshot chart uses a replay window derived from `DetailRoute.replayOffset`.
- Replay controls let the user step backward, step forward, and jump back to the live/latest window.
- The visible candle count, replay position, price range, and max volume are summarized near the chart.
- A right-side volume profile is rendered beside the price chart when chart data exists.
- Volume-profile bins are computed from the same visible replay candles as the price chart.
- Profile bins split volume into up-candle and down-candle volume.
- Empty, single-candle, and invalid price-range cases do not crash or invent data.
- Ticker navigation and chart-range changes reset replay to live.

### Architecture Notes

- Pure replay-window and volume-profile calculations belong in `apps/android/core`.
- `DashboardViewModel` remains the Presenter and owns route state.
- Compose remains a passive renderer of calculated models and dispatches explicit replay actions.
- Existing chart fetching and persistence already preserve `HistoricalCandle.volume`; no provider work is required.

### Acceptance Criteria

1. Given 10 candles and replay offset 3, Android renders the first 7 candles and reports `7 / 10`.
2. Given replay offset greater than candle history length, Android clamps to at least one visible candle.
3. Given an up candle and a down candle that share a price range, volume profile totals preserve the original up/down volumes.
4. Given a wide price candle crossing multiple bins, its volume is distributed across touched price bins with no loss.
5. Given no valid price range, volume profile returns zero-volume bins instead of misleading output.
6. Replay back cannot move beyond the oldest single-candle view.
7. Replay forward cannot move beyond live offset zero.
8. Changing chart range or ticker resets replay offset to zero.

### Out Of Scope

- New Yahoo endpoints or provider payload changes.
- Persistence schema changes.
- Cross-platform contract changes for chart rendering.
- Automated playback animation.
