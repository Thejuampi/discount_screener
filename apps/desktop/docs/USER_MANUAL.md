# User Manual

Need the shortest path first: [Quick Start](QUICK_START.md)

## Disclaimer

This workstation is for informational and educational use only. It does not provide trading, investment, legal, tax, or financial advice, and it does not recommend any transaction or position.

Public market and analyst data may contain errors, omissions, stale values, or interpretation risk. You remain fully responsible for validating any information and for your own decisions.

## Purpose

Discount Screener Workstation is a Rust terminal application for tracking profitable symbols trading below free public fair-value estimates.

It is designed for fast local interaction, automatic session persistence, and low-noise terminal operation.

## Before You Start

Requirements:

- Rust toolchain installed
- a terminal that supports alternate screen mode
- outbound HTTPS access to Yahoo Finance public endpoints for live mode and on-demand detail charts

Project root commands assume you are in the repository root.

## Starting The Workstation

### Live Session

```bash
cargo run
```

This starts the interactive workstation using the built-in 503-symbol live universe.

Use a custom live symbol set:

```bash
cargo run -- --symbols AAPL,MSFT,NVDA
```

That CLI list only seeds the initial live universe. During a live session you can add more symbols from inside the terminal with `s`.
When you start with `--symbols`, those tracked symbols stay visible in `Top Candidates` even if they are currently low-confidence or temporarily unavailable from the live provider.

### Startup Profiles

Use a predefined profile to set the initial symbol universe:

```bash
cargo run -- --profile sp500
```

Available profiles:

| Profile | Description |
|---|---|
| `sp500` | Built-in 503-symbol S&P 500 universe |
| `dow` | Dow Jones Industrial Average 30-stock basket |
| `russell` | Full iShares Russell 2000 holdings universe |
| `merval` | S&P Merval local constituent universe |
| `nikkei` | Nikkei 225 constituent universe |
| `europe` | Full iShares Core MSCI Europe holdings universe |
| `asia` | Full iShares AC Asia ex Japan holdings universe |

Combine `--profile` with `--symbols` to append custom tickers to the profile.

### Persistence

The workstation uses SQLite for automatic warm-start persistence. By default it stores state in:

- Windows: `%LOCALAPPDATA%\discount_screener\state.sqlite3`
- Linux: `$XDG_STATE_HOME/discount_screener/state.sqlite3` or `$HOME/.local/state/discount_screener/state.sqlite3`

Behavior:

- on startup the database is loaded if present, restoring tracked symbols, watchlist, evaluated state, chart cache, and issue history
- live updates are written to the database as they are processed
- watchlist changes and session state are saved on exit

Override the database path:

```bash
cargo run -- --state-db path/to/custom.sqlite3
```

Disable persistence entirely for a live-only session:

```bash
cargo run -- --no-persist
```

### Warm-Start Restore

When you start the workstation and a SQLite database exists from a prior session, the app automatically restores:

- tracked symbols
- watchlist membership
- evaluated symbol states
- cached chart data
- issue history

No separate CLI flag is needed. The restore happens before the live feed starts.

To start with a clean state, pass `--no-persist` or delete the database file.

### Smoke Mode

```bash
cargo run -- --smoke
```

Smoke mode prints a short non-interactive summary and exits.

## Navigation And Controls

### Main View

- `j` or Down moves to the next visible row
- `k` or Up moves to the previous visible row
- `Home` jumps to the first row in the current list view
- `End` jumps to the last row in the current list view
- `PageDown` moves down by one visible page in the current list view
- `PageUp` moves up by one visible page in the current list view
- `o` toggles between the `Top Candidates` and `Top Opportunities` list views
- `d` or `Enter` opens ticker detail for the selected row
- `w` toggles watchlist membership for the selected symbol
- `Space` pauses or resumes live application of feed updates
- `/` enters row-filter mode
- `s` enters symbol-entry mode in live sessions
- `l` opens the issue log viewer
- `f` toggles watchlist-only mode
- `Backspace` clears the active filter state in normal mode
- `Esc` clears the active filter state in normal mode, or leaves the active input mode
- `q` quits the workstation from normal mode
- `Ctrl+C` quits the workstation from any mode

### Ticker Detail View

- `d` or `Enter` opens the current ticker in a full-screen detail view
- `j` and `k` move to the previous or next ticker inside the full filtered set
- `1` through `6` jump chart range between `D`, `W`, `M`, `1Y`, `5Y`, and `10Y`
- `[` and `]` cycle chart range backward or forward
- `←` and `→` step through chart bars one at a time (replay mode); indicators recalculate for the visible range
- `h` toggles between `Snapshot` and `History` tabs inside ticker detail
- `w` toggles watchlist membership for the active ticker
- `l` opens the issue log viewer from the detail screen
- `Backspace`, `Esc`, `d`, or `Enter` closes the detail screen

The detail view is chart-first and currently shows:

- current market price, mean target, median target, and weighted target when available
- qualification state, confidence state, external support, threshold, and discount
- Yahoo historical OHLC candles for the selected range, rendered with a high-density Unicode braille grid
- `EMA20`, `EMA50`, and `EMA200` overlays
- a volume pane rendered on the same braille grid
- a MACD pane with MACD line, signal line, and histogram on the same braille grid
- a valuation map showing price versus low, weighted, mean, median, and high target levels
- analyst consensus breadth, recommendation mean, and rating breakdown
- compact evidence text explaining the current state
- recent symbol alerts or recent qualifying tape when room allows

The detail layout adapts to terminal size. On short terminals it preserves the chart stack first and compresses or removes lower-priority text sections before heavily reducing the chart. The chart stack uses braille cells so it can keep more visible bars and finer vertical steps within the same terminal width.

Press `h` from the `Snapshot` tab to switch to the `History` tab, which shows time-series graphs and tables for evaluated metric groups. Controls inside `History`: `g` toggles `Graphs`/`Table`, `1`-`5` switch metric groups (`Core`, `Fundamentals`, `Relative`, `DCF`, `Chart`), `[`/`]` cycle time windows, `n`/`p` move between symbols, `e` exports CSV. See [HISTORY_TIME_SERIES.md](HISTORY_TIME_SERIES.md) for the full reference.

### Row Filter

- `/` enters row-filter mode
- type the query text
- `Enter` applies the filter query
- `Backspace` removes one character, or leaves filter mode when the buffer is empty
- `Esc` leaves filter mode without applying the current buffer

Filter behavior:

- matching is case-insensitive
- filtering is applied to visible symbol text
- ticker detail navigation follows the current active ranked set, not just the rows shown in the capped main table

### Symbol Entry

- `s` enters symbol-entry mode in live sessions
- type a ticker such as `NVDA`
- `Enter` adds the symbol to the live tracked universe
- comma-separated input such as `AAPL,MSFT,NVDA` adds multiple symbols at once
- `Backspace` removes one character, or leaves symbol-entry mode when the buffer is empty
- `Esc` leaves symbol-entry mode without applying the current buffer

After a symbol is added, the table filter is focused on that ticker so you can inspect it immediately.

### Operational Issues

- `l` opens the issue log viewer
- `Backspace`, `Esc`, `l`, or `q` closes the issue log viewer
- `j` and `k` move through recorded issues in the viewer
- `c` clears resolved issues from the viewer

Operational issue behavior:

- the header shows a health banner with `healthy`, `degraded`, `down`, or `critical`
- the issue rail highlights the most important active operational problem
- new warnings and errors appear in a temporary popup-style banner
- the issue log keeps both active and resolved issue history for the current session

### Watchlist

- `w` toggles watchlist membership for the selected symbol in the main view
- `w` also toggles watchlist membership in ticker detail
- `f` toggles watchlist-only mode for the ranked table

When watchlist-only mode is on, only watched symbols remain in the main ranked table and the current filtered set.

### Top Opportunities View

- `Top Opportunities` is a separate main-view ranking focused on buy candidates rather than the baseline candidate sorter
- it ranks only the current filtered qualified universe
- it uses three evidence buckets when available: fundamentals, 1Y technical confirmation from cached chart summaries, and forecasts from analyst targets plus DCF when analysis is ready
- press `m` in `Top Opportunities` to switch between the legacy balanced model and the new aggressive model
- `Legacy` keeps the original even-weight bucket logic; `Aggressive` leans harder into upside, trend, and sharp penalties for weak balance-sheet or bearish setups
- missing buckets are treated as missing coverage, not automatic negative evidence
- the visible table stays capped for readability, but `j` and `k` now scroll that window through the full active opportunities set
- `Home`, `End`, `PageUp`, and `PageDown` operate on the full active opportunity order using the same ticker-based navigation model as the main candidate list
- the `Idx` column shows the symbol's absolute rank in the full opportunity order
- ticker detail and history navigation still use the full active opportunities set
- the first time you switch into `Top Opportunities`, the selection starts at the first ranked ticker; after that, each list view restores its own last selected ticker when you toggle with `o`

### Pause And Resume

- `Space` pauses live application of incoming feed updates
- press `Space` again to resume

While paused:

- the current screen stays stable
- your selected symbol remains fixed
- pending events accumulate in the status line instead of constantly reshuffling the table

## Screen Areas

The terminal is organized into these operator areas:

1. Header and control legend
2. Status line with source, tracked count, loaded count, unavailable count, applied event count, pending backlog, and update rate
3. Health banner and issue rail
4. Filter and input status lines
5. Top-candidate or top-opportunities table (toggle with `o`)
6. Selected ticker summary in the main view
7. Alerts and recent tape panels

On narrower terminals, the main-view header, status line, and prompt compress before the screen falls back to simple right-edge clipping.

For annotated layouts and screenshots, see [SCREENS.md](SCREENS.md).

## How Ranking Works

The ranked table is ordered by:

1. qualified symbols before non-qualified symbols
2. larger upside before smaller upside
3. higher confidence before lower confidence
4. symbol name for stable ordering

### Qualification Rules

A symbol is currently `qualified` when both conditions are true:

- the symbol has positive trailing EPS
- the internal upside meets or exceeds the configured threshold

Qualification is snapshot-first. External analyst data does not create qualification by itself.

### Confidence Rules

Confidence represents support from external valuation signals:

- `provisional`: internally qualified but no supportive fresh external signal is available
- `high`: internally qualified and externally supportive
- `low`: unqualified, stale, missing, or divergent external state

## Ticker Detail Interpretation

### Price And Target Lines

The top summary lines show:

- current market price
- internal mean target
- external median target when available
- weighted target when available
- qualification, confidence, external status, threshold, and discount

### Chart Stack

The chart stack is split into:

- a price pane with OHLC candles and EMAs
- a volume pane
- a MACD pane

Range selection is session-local. When you move to another ticker with `j` or `k`, the selected chart range stays active.

### Valuation Map And Consensus

Below the chart stack, the detail view shows:

- a valuation map comparing current price to the analyst target range
- weighted target position if weighted consensus exists
- analyst count
- recommendation mean
- rating breakdown from strong buy through strong sell

### Evidence And Recent Context

The evidence section summarizes why the ticker is qualified and how external support affects confidence.

The recent context section shows recent ticker-specific alerts and qualifying tape when there is enough vertical space.

## Alerts

The workstation emits three alert kinds:

- `entered-qualified`
- `exited-qualified`
- `confidence-upgraded`

Alerts are displayed in the alert panel and are stored in the SQLite persistence layer.

## Persistence

The workstation uses SQLite for automatic session persistence. The default database path is platform-dependent:

- Windows: `%LOCALAPPDATA%\discount_screener\state.sqlite3`
- Linux: `$XDG_STATE_HOME/discount_screener/state.sqlite3` or `$HOME/.local/state/discount_screener/state.sqlite3`

Use `--state-db PATH` to override the path or `--no-persist` to disable persistence.

For the database schema, time-series queries, and CSV export, see [HISTORY_TIME_SERIES.md](HISTORY_TIME_SERIES.md).

## Typical Operator Workflows

### Workflow 1: Start A Durable Session

1. Start the app with `cargo run`.
2. Persistence is automatic. The SQLite database stores state across sessions.
3. Let the live feed populate the table.
4. Press `s` and add any extra symbols you want to track.
5. Use `w` to mark important symbols.
6. Exit with `q`.
7. Restart with `cargo run` to restore the prior session automatically.

### Workflow 2: Review A Prior Session

1. Start the app with `cargo run`.
2. The prior session state is restored automatically from the SQLite database.
3. Navigate the candidate list.
4. Open ticker detail for symbols of interest.
5. Use chart range switching and the History tab (`h`) to inspect different time horizons.

### Workflow 3: Focus On A Theme Or Basket

1. Press `s` and add the symbols you want to track in the live universe.
2. Add relevant symbols to the watchlist.
3. Press `f` to show only watched symbols.
4. Press `/` and enter a symbol prefix to narrow the visible set.
5. Open ticker detail and move through the filtered set with `j` and `k`.

### Workflow 4: Freeze The Screen During Heavy Activity

1. Start a live session.
2. When the feed becomes busy, press `Space`.
3. Review the selected symbol without the table moving under you.
4. Watch the pending count in the status line.
5. Press `Space` again to resume processing.

### Workflow 5: Investigate Operational Problems

1. Watch the health banner for `degraded`, `down`, or `critical` status.
2. Read the issue rail or popup banner to understand the newest problem.
3. Press `l` to open the issue log viewer.
4. Use `j` and `k` to inspect active and resolved issues.
5. Press `c` to remove resolved issues after review.

## Troubleshooting

### The app starts but few or no symbols appear

In live mode, verify outbound HTTPS access and use symbols with Yahoo Finance coverage for price, trailing EPS, and analyst-target data.

If a tracked symbol fails Yahoo coverage checks, it now stays visible in the main table as an unavailable row and can be opened in ticker detail to inspect the current provider diagnostics.

If the source is degraded, the health banner and issue rail should report the problem directly in the UI. The live status line also shows `Unavailable`. Press `l` for the full issue history.

### Ticker detail has no chart or shows a Yahoo fetch error

Ticker detail charts are fetched on demand from Yahoo historical chart endpoints.

Check:

- outbound HTTPS access
- the symbol has Yahoo chart coverage
- the issue rail or issue log for broader feed problems

The detail view may continue showing cached chart data while a refresh is in flight or after a fetch failure.

### State restore fails

If the SQLite database is corrupted, delete the file and restart. The workstation will start fresh.

Check the issue log inside the app for specific persistence errors. The default database path is platform-dependent (see the Persistence section above).

### Symbols or watchlist did not restore

Check that:

- you did not pass `--no-persist`
- the SQLite database file exists at the expected path
- the file is not locked by another process

Restore failures surface through the issue system and can be inspected in the issue log.

## Current Release Notes

This release uses Yahoo Finance public data for live price and analyst inputs, and Yahoo historical chart data for the ticker detail view. The main screen is optimized for signal density, while the detail view expands into a chart-first analysis screen with EMA, volume, and MACD support.
