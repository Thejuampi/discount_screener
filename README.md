# Discount Screener Workstation

## Disclaimer

This project is provided for informational and educational purposes only. It is not trading advice, investment advice, financial advice, legal advice, tax advice, accounting advice, or a recommendation to buy, sell, or hold any security.

Market data, analyst targets, ratings, and derived signals may be delayed, incomplete, inaccurate, or unavailable. You are solely responsible for any decisions or actions you take based on this software or its output. Always verify information independently and consult a qualified professional where appropriate.

Rust terminal workstation for monitoring profitable companies trading at a discount to free public fair-value estimates, using Yahoo Finance public quote and analyst-target data in live mode.

The current product build is a fast terminal application with:

- ranked candidate view
- symbol detail view
- alert stream
- recent tape
- operational issue rail and health banner
- in-terminal issue log viewer
- durable event journal
- replay from disk
- persistent watchlists
- live row filtering, in-terminal symbol tracking, and watchlist filtering

## What It Does

The workstation ingests free public market snapshots and analyst target signals, computes a discount gap against the provider fair-value estimate, and ranks symbols by:

1. qualification state
2. gap size
3. confidence band
4. symbol name as a stable tie-breaker

Qualification is snapshot-first:

- `Qualified` means the symbol has positive trailing EPS and its live fair-value gap meets the configured threshold.
- secondary analyst target signals never create qualification on their own
- secondary analyst target signals only affect confidence

## Current Product Shape

This repository ships a terminal workstation with a best-effort free public market-data adapter. It is not an exchange-direct or broker-connected platform.

Included now:

- in-process Rust state engine
- free public Yahoo Finance live data adapter
- explainable qualification state
- replayable journal format
- disk-backed journal persistence
- disk-backed watchlist persistence
- interactive terminal workflow

Not included yet:

- paid or authenticated exchange and broker data adapters
- broker or execution connectivity
- networked API service layer
- multi-user storage and auth

## Build And Run

From the project root:

```bash
cargo run
```

Live mode requires outbound HTTPS access to Yahoo Finance public quote pages.

By default it polls the current S&P 500 constituents.

Run with a custom symbol list:

```bash
cargo run -- --symbols AAPL,MSFT,NVDA
```

The CLI symbol list only seeds the initial live universe. During a live session you can add more tracked symbols directly in the terminal with `s`.

Smoke mode:

```bash
cargo run -- --smoke
```

Smoke mode remains a static verification path and does not hit the network.

Run with a persistent journal file:

```bash
cargo run -- --journal-file data/session.journal
```

Run with both journal and watchlist persistence:

```bash
cargo run -- --journal-file data/session.journal --watchlist-file data/watchlist.txt
```

Replay a prior session:

```bash
cargo run -- --replay-file data/session.journal --watchlist-file data/watchlist.txt
```

## Keyboard Controls

- `j` or Down: move selection down
- `k` or Up: move selection up
- `d` or `Enter`: open the detailed screen for the selected ticker
- `w`: toggle watchlist on the selected symbol
- `Space`: pause or resume live application of feed updates
- `/`: enter row-filter mode for visible symbols
- `s`: add one or more live symbols from inside the terminal UI
- `l`: open the operational issue log viewer
- `Enter`: apply the active filter or symbol input buffer
- `Backspace`: go back in every screen and mode; in filter and symbol entry it deletes text until the buffer is empty, then goes back
- `f`: toggle watchlist-only filter
- `Esc`: clear the active filter in normal mode, or leave the active input mode
- `q`: quit from normal mode
- `Ctrl+C`: quit from any mode

Ticker detail screen:

- `j` or `k`: move to the previous or next filtered ticker
- `w`: toggle watchlist for the active ticker
- `l`: open the operational issue log
- `Backspace`, `d`, `Enter`, or `Esc`: close the detail screen
- shows mean, median, low, and high analyst targets
- shows analyst count, recommendation mean, and the strong-buy to strong-sell breakdown

The main view also includes:

- a health banner showing whether the workstation is healthy, degraded, or down
- an issue rail for the most important active operational issue
- a temporary popup-style issue banner for new warnings and errors

## File Formats

Journal file:

- one event per line
- snapshot line format:

```text
S|sequence|symbol|profitable_flag|market_price_cents|intrinsic_value_cents
```

- external valuation line format:

```text
E|sequence|symbol|fair_value_cents|age_seconds
```

Watchlist file:

- one symbol per line

## Test And Verification

Run all tests:

```bash
cargo test
```

Format code:

```bash
cargo fmt
```

## Documentation

- [Quick Start](docs/QUICK_START.md)
- [User Manual](docs/USER_MANUAL.md)
- [Screen Guide](docs/SCREENS.md)
