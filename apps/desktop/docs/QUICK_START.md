# Quick Start

## Goal

Get the workstation running in a few minutes and know the minimum controls needed to use it.

## Disclaimer

This tool is for informational and educational use only. It is not trading or investment advice, and any data or rankings shown by the workstation should be independently verified before use.

## Prerequisites

- Rust installed
- a terminal that supports alternate screen mode
- outbound HTTPS access to Yahoo Finance public endpoints for live mode

## 1. Verify The Build

From `apps/desktop`:

```bash
cargo test
```

If the tests pass, the workstation is ready to run.

## 2. Run A Smoke Check

```bash
cargo run -- --smoke
```

Expected result:

- the binary builds
- a short summary prints to the terminal
- at least one candidate row and alert line appear

## 3. Start The Workstation

```bash
cargo run
```

This starts the interactive terminal with the built-in 503-symbol live universe.

Use a custom initial symbol set:

```bash
cargo run -- --symbols AAPL,MSFT,NVDA
```

The CLI list only seeds the initial live universe. During a live session you can add more symbols from inside the terminal with `s`.
When you start with `--symbols`, those tracked symbols stay visible in `Top Candidates` even if they are currently low-confidence or temporarily unavailable from the live provider.

## 4. Learn The Minimum Controls

Main view:

- `j` or Down: move down
- `k` or Up: move up
- `Home`: jump to the first row in the current list view
- `End`: jump to the last row in the current list view
- `PageDown`: move down by one visible page in the current list view
- `PageUp`: move up by one visible page in the current list view
- `o`: toggle between the `Top Candidates` and `Top Opportunities` list views
- `d` or `Enter`: open ticker detail
- `w`: toggle watchlist on the selected symbol
- `Space`: pause or resume live updates
- `/`: filter the visible rows
- `s`: add live symbols from the terminal UI
- `l`: open the issue log viewer
- `f`: toggle watchlist-only mode
- `Backspace`: go back; in text entry it deletes characters until the buffer is empty, then goes back
- `Esc`: clear filters or leave the active input mode
- `q`: quit from normal mode
- `Ctrl+C`: quit from any mode

Ticker detail view:

- `j` or `k`: move through the full filtered ticker set
- `1` through `6`: switch chart range between `D`, `W`, `M`, `1Y`, `5Y`, and `10Y`
- `[` or `]`: cycle chart range backward or forward
- `←` or `→`: step through chart bars one at a time (replay)
- `h`: toggle between `Snapshot` and `History` tabs
- `w`: toggle watchlist for the active ticker
- `l`: open the issue log
- `Backspace`, `d`, `Enter`, or `Esc`: close the detail view

The detail view is chart-first. It shows real Yahoo OHLC candles plus `EMA20`, `EMA50`, `EMA200`, volume, MACD, valuation context, and analyst consensus. Press `h` to switch to the History tab for time-series graphs and tables. See [HISTORY_TIME_SERIES.md](HISTORY_TIME_SERIES.md) for details.

## 5. Persistence

The workstation stores state automatically in a SQLite database. Tracked symbols, watchlist, evaluated states, and issue history survive restarts.

No extra flags are needed. State is restored on next launch.

Override the database path:

```bash
cargo run -- --state-db path/to/custom.sqlite3
```

Start a live-only session without persistence:

```bash
cargo run -- --no-persist
```

## 6. Use Startup Profiles

Load a predefined symbol universe instead of the default:

```bash
cargo run -- --profile dow
```

Available profiles: `sp500`, `dow`, `russell`, `merval`, `nikkei`, `europe`, `asia`.

Combine with custom symbols:

```bash
cargo run -- --profile dow --symbols AAPL,MSFT
```

## 7. Know What You Are Looking At

The main terminal is organized into:

1. command legend and status
2. health banner and issue rail
3. top-candidate or top-opportunities table (toggle with `o`)
4. selected symbol summary or opportunity rationale
5. recent alerts
6. recent tape

The live status line includes tracked count, loaded count, unavailable count, applied event count, pending backlog, and rate.

The main table is capped for readability. In `Top Opportunities`, `j` and `k` now scroll that 20-row viewport through the full active opportunity ranking. The detail view still navigates the full active ranked set for the current list view.

Selection is remembered per list view by ticker. The first time you enter `Top Opportunities`, the selection starts at the first ranked symbol; after that, toggling with `o` restores the last ticker you had selected in each list.

If the live source or persistence path has problems, the main view shows them in the health banner and issue rail. Press `l` to open the full issue log viewer.

If the live feed is moving too fast, press `Space` to pause application of incoming updates. The screen stays stable and the pending backlog count continues to increase until you resume.

## 8. Next Documents

- full operator manual: [USER_MANUAL.md](USER_MANUAL.md)
- screen guide: [SCREENS.md](SCREENS.md)
- history and time-series manual: [HISTORY_TIME_SERIES.md](HISTORY_TIME_SERIES.md)
- product overview: [../README.md](../README.md)
