# Screen Guide

This document shows the current terminal layouts and explains what each area means.

The layouts below are representative. Exact spacing depends on terminal width and height.

## Screen 1: Live Session

```text
DISCOUNT TERMINAL  |  j/k move  |  d detail  |  w watch  |  / filter  |  s symbol  |  l logs  |  f watch filter  |  space pause  |  q quit
Mode: live  Source: yahoo  Feed: running  Tracked: 8  Loaded: 8  Applied: 48  Pending: 0  Rate: 1/s
Health: healthy  Active issues: 0  Resolved: 0  Press l for issue log
Issue rail: no active operational issues.
Tracked symbols: AAPL, MSFT, NVDA, AMZN, META, GOOG, TSLA, AMD
Filter: query='' watchlist_only=off input_mode=normal selected=NVDA
Use / to filter visible rows, s to track a symbol, l to open issues, Backspace to go back, or Ctrl+C to quit from any mode.

TOP CANDIDATES
Idx  W  Symbol  Price      Fair       Gap      Confidence
>  0  *  NVDA     $890.00    $980.00   9.18%   provisional
   1     AAPL     $190.00    $205.00   7.31%   high
   2     TSLA     $229.00    $246.00   6.91%   low

DETAIL
Symbol: NVDA
Watched: yes
Qualification: qualified
Price: $890.00
Fair value: $980.00
Gap: 9.18%
Confidence: provisional
External: missing
Seq: 2451
Updates: 307

ALERTS
NVDA   kind=entered-qualified seq=2442
AAPL   kind=confidence-upgraded seq=2437

RECENT TAPE
NVDA   gap=9.18% qualified=yes confidence=provisional
AAPL   gap=7.31% qualified=yes confidence=high
```

### Callouts

1. Header: command legend for the interactive session
2. Status line: mode, feed state, tracked universe size, loaded symbol count, pending backlog, and update rate
3. Health banner: overall operational state
4. Issue rail: most important active problem, if any
5. Tracked symbols line: the current live universe
6. Filter line: active query, watchlist-only mode, input mode, and selected symbol
7. Prompt line: current input help or status message
8. Candidate grid: ranked symbol list
9. Detail panel: explainable state for the selected symbol
10. Alert panel and recent tape: transition history and latest event-derived snapshots

## Screen 2: Filter Mode

```text
DISCOUNT TERMINAL  |  j/k move  |  d detail  |  w watch  |  / filter  |  s symbol  |  l logs  |  f watch filter  |  space pause  |  q quit
Mode: live  Source: yahoo  Feed: running  Tracked: 8  Loaded: 8  Applied: 54  Pending: 0  Rate: 1/s
Health: healthy  Active issues: 0  Resolved: 0  Press l for issue log
Issue rail: no active operational issues.
Tracked symbols: AAPL, MSFT, NVDA, AMZN, META, GOOG, TSLA, AMD
Filter: query='NV' watchlist_only=off input_mode=filter selected=NVDA
Filter rows: 'NV'  Enter apply  Backspace delete or go back  Esc cancel  Ctrl+C quit

TOP CANDIDATES
Idx  W  Symbol  Price      Fair       Gap      Confidence
>  0     NVDA     $892.10    $980.00   8.96%   provisional
```

### How To Read It

- Filter mode is shown by `input_mode=filter`.
- The query buffer is displayed immediately in the filter line.
- Press `Enter` to apply the query.
- Press `Backspace` on an empty buffer to leave filter mode.
- `Esc` still leaves filter mode.

## Screen 3: Symbol Lookup Mode

```text
DISCOUNT TERMINAL  |  j/k move  |  d detail  |  w watch  |  / filter  |  s symbol  |  l logs  |  f watch filter  |  space pause  |  q quit
Mode: live  Source: yahoo  Feed: running  Tracked: 9  Loaded: 8  Applied: 56  Pending: 0  Rate: 1/s
Health: degraded  Active issues: 1  Resolved: 0  Press l for issue log
Popup: [warn][feed] Live source partially degraded. Loaded 8 of 9 tracked symbols.
Tracked symbols: AAPL, MSFT, NVDA, AMZN, META, GOOG, TSLA, AMD, AVGO
Filter: query='AVGO' watchlist_only=off input_mode=symbol selected=AVGO
Track symbol: 'AVGO'  Enter add  Backspace delete or go back  Esc cancel  Ctrl+C quit
```

### How To Read It

- Symbol lookup mode is shown by `input_mode=symbol`.
- Enter one ticker or a comma-separated list.
- After applying, the new symbol is added to the tracked live universe and focused in the table filter.

## Screen 4: Ticker Detail View

```text
TICKER DETAIL  |  j/k next ticker  |  w watch  |  l logs  |  Backspace or d or Enter close  |  Ctrl+C quit
Symbol: NVDA  Position: 1/8 filtered tickers  Watched: no
Price: $172.70  Mean target: $269.23  Median target: $265.00
Discount to mean: $96.53  Gap to mean: 35.85%
Qualification: qualified  Confidence: high  External: supportive  Profitable: yes
Last sequence: 22  Updates: 4  Visible filter: query='' watchlist_only=off

ANALYST CONSENSUS
Targets: mean $269.23  median $265.00  low $185.00  high $320.00
Target range width: $135.00 = $320.00 - $185.00
Analysts: 42  Recommendation mean: 1.85 (1.00=strong buy, 5.00=strong sell)
Ratings: strong buy 20  buy 10  hold 8  sell 3  strong sell 1

QUALIFICATION
Profitability gate: actual=yes  required=yes
Internal gap: actual=35.85%  required>=20.00%
Internal discount: $96.53 = $269.23 - $172.70
Result: qualified because profitable=yes and 35.85% >= 20.00%.

CONFIDENCE
External status: supportive
External fair value: $265.00  external gap: 34.83%  support threshold: >=20.00%
External signal age: 6s  freshness limit: <=30s
Result: high because internal qualification is qualified and external status is supportive.

RECENT SYMBOL ALERTS
kind=confidence-upgraded seq=6
kind=entered-qualified seq=5

RECENT SYMBOL TAPE
gap=35.85% qualified=yes confidence=high
gap=35.85% qualified=yes confidence=provisional
```

### How To Read It

- The detail view is opened with `d` or `Enter` from the main table.
- `j` and `k` move to the previous or next ticker inside the current filtered set.
- The detail view keeps the operator focused on one symbol while exposing target range, analyst breadth, and recommendation distribution, not just a single aggregate fair value.

## Screen 5: Issue Log Viewer

```text
ISSUE LOG  |  j/k move  |  c clear resolved  |  Backspace or l close  |  Ctrl+C quit
Health: degraded  Active: 1  Total: 3  Resolved: 2
Idx  State     Sev      Source       Count  Title
>  0  active    warn     feed             3  Live source partially degraded
   1  resolved  error    persistence      1  Journal persistence failed
   2  resolved  warn     persistence      2  Watchlist persistence failed

DETAIL
Title: Live source partially degraded  Source: feed  Severity: warn  State: active
Occurrences: 3  First seen: #5  Last seen: #12
Detail: Loaded 6 of 8 tracked symbols. 2 symbols returned incomplete coverage.
```

### How To Read It

- The issue log viewer is opened with `l`.
- Active issues appear before resolved ones.
- The detail section shows the full current issue message and repeat count.
- Press `c` to remove resolved issues after review.

## Screen 6: Watchlist-Only Mode

```text
DISCOUNT TERMINAL  |  j/k move  |  d detail  |  w watch  |  / filter  |  s symbol  |  l logs  |  f watch filter  |  space pause  |  q quit
Mode: live  Source: yahoo  Feed: running  Tracked: 8  Loaded: 8  Applied: 61  Pending: 0  Rate: 1/s
Health: healthy  Active issues: 0  Resolved: 0  Press l for issue log
Issue rail: no active operational issues.
Tracked symbols: AAPL, MSFT, NVDA, AMZN, META, GOOG, TSLA, AMD
Filter: query='' watchlist_only=on input_mode=normal selected=AAPL

TOP CANDIDATES
Idx  W  Symbol  Price      Fair       Gap      Confidence
>  0  *  AAPL     $191.20    $205.00   6.73%   high
   1  *  NVDA     $891.50    $980.00   9.03%   provisional
```

### How To Read It

- The `W` column marks watched symbols with `*`.
- `watchlist_only=on` means only watched symbols are shown.
- Press `w` on the selected row to add or remove it from the watchlist.

## Screen 7: Replay Session

```text
DISCOUNT TERMINAL  |  j/k move  |  d detail  |  w watch  |  / filter  |  l logs  |  f watch filter  |  q quit
Mode: replay  Source: journal  Feed: running  Symbols: 2  Applied: 3  Pending: 0  Rate: 0/s
Health: healthy  Active issues: 0  Resolved: 0  Press l for issue log
Issue rail: no active operational issues.
Tracked symbols: replay session
Filter: query='' watchlist_only=off input_mode=normal selected=ALFA
Use / to filter visible rows, l to open issues, Backspace to go back, or Ctrl+C to quit from any mode.

TOP CANDIDATES
Idx  W  Symbol  Price      Fair       Gap      Confidence
>  0     ALFA     $70.00     $100.00  30.00%   provisional
   1     BETA     $80.00     $100.00  20.00%   high

ALERTS
BETA   kind=confidence-upgraded seq=3
BETA   kind=entered-qualified seq=1
```

### How To Read It

- Replay mode does not start the live feed.
- The update rate is normally zero unless you add replay stepping later.
- This mode is for deterministic review of persisted journal history.

## Screen 8: Paused Live Session

```text
DISCOUNT TERMINAL  |  j/k move  |  d detail  |  w watch  |  / filter  |  s symbol  |  l logs  |  f watch filter  |  space pause  |  q quit
Mode: live  Source: yahoo  Feed: paused  Tracked: 8  Loaded: 8  Applied: 88  Pending: 8  Rate: 0/s
Health: healthy  Active issues: 0  Resolved: 0  Press l for issue log
Issue rail: no active operational issues.
Tracked symbols: AAPL, MSFT, NVDA, AMZN, META, GOOG, TSLA, AMD
Filter: query='' watchlist_only=off input_mode=normal selected=MSFT

TOP CANDIDATES
Idx  W  Symbol  Price      Fair       Gap      Confidence
>  0     MSFT     $405.20    $430.00   5.76%   provisional
   1     AAPL     $191.10    $205.00   6.78%   high
```

### How To Read It

- `Feed: paused` means incoming feed events are being buffered, not applied.
- `Pending` shows how many events are queued for later application.
- This is the safe mode for reviewing one symbol without the table constantly reshuffling.

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

## Screen Reading Notes

- `Price` and `Fair` are displayed in dollars, but the engine stores integer cents.
- `Gap` is displayed as a percentage, but internally uses basis points.
- `Confidence` depends on both live qualification and external signal state.
- `Seq` is the last event sequence applied to the selected symbol.
- `Updates` is the number of times the selected symbol state has been touched.