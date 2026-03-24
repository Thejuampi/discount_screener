# Quick Start

## Goal

Get the workstation running in under five minutes.

## Disclaimer

This tool is for informational and educational use only. It is not trading or investment advice, and any data or rankings shown by the workstation should be independently verified before use.

## Prerequisites

- Rust installed
- a terminal that supports alternate screen mode
- project dependencies resolved by Cargo
- outbound HTTPS access to Yahoo Finance public quote pages

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

This starts the interactive terminal with live Yahoo Finance public market data for the default symbol set.

The CLI symbol list is only the starting universe. In live mode you can add more symbols from inside the terminal by pressing `s`.

Default symbols:

- `AAPL`
- `MSFT`
- `NVDA`
- `AMZN`
- `META`
- `GOOG`
- `TSLA`
- `AMD`
- `CAT`
- `SPOT`
- `INTC`
- `MSTR`
- `MELI`
- `UBER`
- `FISV`
- `NFLX`
- `LLY`
- `WMT`
- `TMUS`
- `T`
- `NKE`
- `XOM`
- `TIGR`

To seed a custom initial symbol set:

```bash
cargo run -- --symbols AAPL,MSFT,NVDA
```

## 4. Learn The Minimum Controls

- `j` or Down: move down
- `k` or Up: move up
- `d` or `Enter`: open the detailed screen for the selected ticker
- `w`: toggle watchlist on the selected symbol
- `Space`: pause or resume live updates while keeping the current screen stable
- `/`: filter the visible rows by symbol text
- `s`: add one or more live symbols from the terminal UI
- `l`: open the operational issue log viewer
- `Enter`: apply the active filter or symbol input
- `Backspace`: go back in every mode; in text entry it deletes characters until the buffer is empty, then goes back
- `f`: show watched symbols only
- `Esc`: clear filters or leave the active input mode
- `q`: quit from normal mode
- `Ctrl+C`: quit from any mode

## 5. Run With Persistence

To keep journal history and watchlists between sessions:

```bash
cargo run -- --journal-file data/session.journal --watchlist-file data/watchlist.txt
```

Behavior:

- existing journal file is replayed at startup if present
- new events are appended during the session
- watchlist changes are saved to disk

## 6. Replay A Prior Session

```bash
cargo run -- --replay-file data/session.journal --watchlist-file data/watchlist.txt
```

Use replay mode for:

- reviewing previous sessions
- debugging ranking behavior
- demos

## 7. Know What You Are Looking At

The main terminal is split into:

1. command legend and status
2. health banner and issue rail
3. top candidates table
4. selected symbol detail
5. recent alerts
6. recent tape

For screen examples, see [SCREENS.md](SCREENS.md).

If the live source or persistence path has problems, the main view shows them in the health banner and issue rail. Press `l` to open the full issue log viewer.

If the live feed is moving too fast, press `Space` to pause application of incoming updates. The screen stays stable and the pending backlog count continues to increase until you resume.

Only symbols with a live price, trailing EPS, and analyst target coverage from Yahoo Finance will appear in the ranked table.

Use `s` and enter a ticker such as `NVDA` or a comma-separated list such as `AAPL,MSFT,NVDA` to add symbols without restarting the app.

Press `d` or `Enter` on a selected row to open the dedicated ticker detail screen. Inside that screen, use `j` and `k` to step through the filtered ticker set.

New warnings and errors also appear in a temporary popup-style banner near the top of the screen so operators do not miss them.

## 8. Next Documents

- full operator manual: [USER_MANUAL.md](USER_MANUAL.md)
- screen guide: [SCREENS.md](SCREENS.md)
- product overview: [../README.md](../README.md)
