# Screen Guide

This document shows the current terminal layouts and explains what each area means.

The layouts are viewport-aware. Exact spacing, pane height, and how much secondary text is shown depend on terminal width and height.

## Screen 1: Live Main View

![Main ranked view](screenshots/main.png)

### What You Are Seeing

1. Header: main controls for the live session
2. Status line: mode, source, feed state, tracked symbols, loaded symbols, unavailable symbols, applied events, pending backlog, and update rate
3. Health banner: overall operational state
4. Issue rail or popup: the most important current issue, or a temporary warning/error popup
5. Tracked symbols line: current live universe
6. Filter line: current query, watchlist-only mode, input mode, and selected symbol
7. Prompt line: current input hint or status message
8. Top candidates or top opportunities table: ranked rows, capped in the main screen for readability
9. Selected detail summary or opportunity rationale: compact explanation for the highlighted row
10. Alerts and recent tape: recent transitions and latest event-derived state changes

### Current Main-View Notes

- `Unavailable` is the tracked symbol count that is not currently loaded.
- `o` toggles between the baseline `Top Candidates` table and the composite `Top Opportunities` table.
- The main table is intentionally capped. Open ticker detail to navigate the full filtered set.
- Sessions started with `--symbols` keep that explicit tracked set visible in `Top Candidates`, including low-confidence rows and provider failures.
- Tracked symbols with live-provider failures stay visible as unavailable rows so you can open ticker detail and inspect the current diagnostics.
- In `Top Opportunities`, the rank uses available fundamentals, 1Y technical confirmation from cached chart summaries, and forecast support from analyst targets plus DCF when the analysis cache is ready.
- In `Top Opportunities`, `j` and `k` move through the full ranked opportunity set while the visible table window follows the selected symbol.
- `Home`, `End`, `PageUp`, and `PageDown` use the same ticker-based selection model across both main list views.
- The `Idx` column in `Top Opportunities` reflects the symbol's absolute rank inside the full opportunity order, not just its position inside the visible window.
- The first entry into `Top Opportunities` starts at the first ranked ticker, and later toggles between `Top Candidates` and `Top Opportunities` restore the last selected ticker for each view.
- `Upside` is the table percentage column shown in the current UI.
- `w` toggles watchlist membership and `f` toggles watchlist-only mode.
- `Space` pauses live application of feed updates without leaving the main screen.
- On narrower terminals, the header, status line, and prompt switch to compact variants before the right edge is clipped.
- Symbols that were restored from the warm-start cache but not yet live-refreshed are shown in dark grey. Once a live feed update arrives for a symbol, it switches to its normal confidence-based color.

Representative current header and status strings:

```text
DISCOUNT TERMINAL  |  j/k move  |  o view  |  d detail  |  w watch  |  / filter  |  s symbol  |  l logs  |  f watch filter  |  space pause  |  q quit
Mode: live  Source: yahoo  Feed: running  Tracked: 503  Loaded: 434  Unavailable: 69  Applied: 71426  Pending: 0  Rate: 4/s
Health: degraded  Active issues: 1  Resolved: 0  Press l for issue log
```

## Screen 2: Filter Mode

Representative filter prompt:

```text
Filter: query='NV' watchlist_only=off input_mode=filter selected=NVDA
Filter rows: 'NV'  Enter apply  Backspace delete or go back  Esc cancel  Ctrl+C quit
```

### Filter Notes

- `input_mode=filter` means the row filter is active.
- Matching is case-insensitive against visible symbol text.
- Press `Backspace` on an empty buffer to leave filter mode.

## Screen 3: Symbol Entry Mode

Representative symbol-entry prompt:

```text
Filter: query='AVGO' watchlist_only=off input_mode=symbol selected=AVGO
Track symbol: 'AVGO'  Enter add  Backspace delete or go back  Esc cancel  Ctrl+C quit
```

### Symbol Entry Notes

- Symbol entry is available in live mode.
- Enter one ticker or a comma-separated list.
- After applying, the new symbol is added to the tracked live universe and focused in the filter.

## Screen 4: Ticker Detail View

![Ticker detail view](screenshots/ticker-details.png)

### Detail Layout

1. Header: detail controls including chart-range switching
2. Identity line: symbol, position inside the full filtered set, watchlist state, active chart range
3. Summary lines: price, mean, median, weighted target, qualification, confidence, external support, threshold, and discount
4. Price chart pane: Yahoo historical OHLC candles with `EMA20`, `EMA50`, and `EMA200`, rendered with high-density Unicode braille cells so the chart can use the full plot width without the old two-columns-per-candle cap. On viewports wider than 100 columns, a volume profile histogram is drawn on the right side of the price chart showing total volume traded at each price level, split into up-volume (yellow) and down-volume (cyan)
5. Volume pane: volume bars for the active visible range, rendered with the same high-density braille grid as the price pane
6. MACD pane: MACD line, signal line, and histogram, also rendered on the high-density braille grid
7. Valuation map: price position versus low, weighted, mean, median, and high target levels
8. Consensus: analyst breadth, recommendation mean, and rating breakdown
9. Evidence: compact explanation of why the ticker is qualified and how external support affects confidence
10. Recent context: recent alerts or qualifying tape for the active ticker

### Current Detail-View Notes

- Use `1` through `6` to jump between `D`, `W`, `M`, `1Y`, `5Y`, and `10Y`.
- Use `[` and `]` to cycle chart ranges.
- Use `←` and `→` to step through chart bars one at a time (replay). Left removes the latest bar, Right restores one. All indicators (EMA, MACD) recalculate for the visible range. The offset resets when changing symbol, range, or when new data arrives.
- The chart uses real Yahoo historical candles, not the earlier session-only synthetic price strip.
- The detail chart stack uses Unicode braille cells for higher vertical precision and to fit more visible bars into the same terminal width.
- The layout is price-first. On shorter terminals, recent context is dropped first, then secondary sections are compacted before the chart is heavily reduced.
- Detail navigation uses the full active ranked set for the current base view, not just the visible table window.
- When a symbol is stale (warm-start cached, not yet live-refreshed), the detail identity line is shown in dark grey instead of cyan.

Representative current detail header:

```text
TICKER DETAIL  |  j/k next ticker  |  1-6 range  |  [/] cycle  |  ←/→ replay  |  w watch  |  l logs  |  Backspace or d or Enter close  |  q quit  |  Ctrl+C quit
```

## Screen 5: Issue Log Viewer

Representative current issue-log layout:

```text
ISSUE LOG  |  j/k move  |  c clear resolved  |  Backspace or l close  |  Ctrl+C quit
Health: degraded  Active: 1  Total: 3  Resolved: 2
Idx  State     Sev      Source       Count  Title
>  0  active    warn     feed             3  Live source partially degraded
```

### Issue Log Notes

- Active issues appear before resolved issues.
- The detail panel below the table shows full current issue text and occurrence counts.
- Source and persistence problems surface here even if they were first shown as a popup or issue rail message.

## Screen 6: Watchlist-Only Mode

Representative current watchlist filter state:

```text
Filter: query='' watchlist_only=on input_mode=normal selected=AAPL
```

### Watchlist Notes

- The `W` column marks watched symbols with `*`.
- `watchlist_only=on` means only watched symbols are shown in the main ranked table.
- Watchlist toggling is available in both the main view and ticker detail view.

## Screen 7: Replay Session

Representative replay status:

```text
DISCOUNT TERMINAL  |  j/k move  |  o view  |  d detail  |  w watch  |  / filter  |  l logs  |  f watch filter  |  q quit
Mode: replay  Source: journal  Feed: running  Symbols: 138  Applied: 23370  Pending: 0  Rate: 0/s
Tracked symbols: replay session
```

### Replay Notes

- Replay mode does not start the live feed.
- The main state is restored from journal history.
- Ticker detail still uses on-demand Yahoo historical chart fetches when you open a symbol.

## Screen 8: Paused Live Session

Representative paused status:

```text
Mode: live  Source: yahoo  Feed: paused  Tracked: 503  Loaded: 434  Unavailable: 69  Applied: 71426  Pending: 12  Rate: 0/s
```

### Pause Notes

- `Feed: paused` means incoming feed events are being buffered instead of applied.
- `Pending` shows the backlog waiting to be applied once you resume.
- This is the safe mode for inspecting one symbol without the table constantly reshuffling.

## Screen 9: Smoke Output

Smoke mode is not interactive. It prints a short verification snapshot.

```text
DISCOUNT TERMINAL SMOKE
ACME price=$80.00 fair=$100.00 gap=20.00% confidence=high
alert=ACME kind=entered-qualified seq=1
alert=ACME kind=confidence-upgraded seq=2
```

### Use Cases

- quick verification in CI or local development
- validating build output
- confirming journal and alert logic still behave as expected

## Reading Notes

- Prices and target values are displayed in dollars, but the engine stores integer cents.
- The main table shows `Upside` as a percentage derived from price versus fair value.
- Qualification is internal and snapshot-first.
- Confidence is influenced by external support state.
- `Sequence` is the last event sequence applied to the selected symbol.
- `Updates` is the number of times the selected symbol state has been touched in the session.
