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

From the project root:

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

## 4. Learn The Minimum Controls

Main view:

- `j` or Down: move down
- `k` or Up: move up
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
- `w`: toggle watchlist for the active ticker
- `l`: open the issue log
- `Backspace`, `d`, `Enter`, or `Esc`: close the detail view

The detail view is chart-first. It shows real Yahoo OHLC candles plus `EMA20`, `EMA50`, `EMA200`, volume, MACD, valuation context, and analyst consensus.

## 5. Run With Persistence

To keep journal history and watchlists between sessions:

```bash
cargo run -- --journal-file data/session.journal --watchlist-file data/watchlist.txt
```

Behavior:

- an existing journal file is replayed at startup if present
- an existing watchlist file is loaded at startup if present
- new journal events are appended during the session
- watchlist changes are saved to disk

## 6. Replay A Prior Session

```bash
cargo run -- --replay-file data/session.journal --watchlist-file data/watchlist.txt
```

Use replay mode for:

- reviewing previous sessions
- debugging ranking behavior
- demos

Replay mode is journal-backed for workstation state. The ticker detail screen can still fetch Yahoo historical chart data on demand.

## 7. Know What You Are Looking At

The main terminal is organized into:

1. command legend and status
2. health banner and issue rail
3. top candidates table
4. selected symbol summary
5. recent alerts
6. recent tape

The live status line includes tracked count, loaded count, unavailable count, applied event count, pending backlog, and rate.

The main table is capped for readability. The detail view still navigates the full filtered ticker set.

If the live source or persistence path has problems, the main view shows them in the health banner and issue rail. Press `l` to open the full issue log viewer.

If the live feed is moving too fast, press `Space` to pause application of incoming updates. The screen stays stable and the pending backlog count continues to increase until you resume.

## 8. Next Documents

- full operator manual: [USER_MANUAL.md](USER_MANUAL.md)
- screen guide: [SCREENS.md](SCREENS.md)
- product overview: [../README.md](../README.md)
