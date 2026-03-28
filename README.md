# Discount Screener Workstation

## Disclaimer

This project is provided for informational and educational purposes only. It is not trading advice, investment advice, financial advice, legal advice, tax advice, accounting advice, or a recommendation to buy, sell, or hold any security.

Market data, analyst targets, ratings, and derived signals may be delayed, incomplete, inaccurate, or unavailable. You are solely responsible for any decisions or actions you take based on this software or its output. Always verify information independently and consult a qualified professional where appropriate.

## Overview

Discount Screener Workstation is a Rust terminal application for monitoring profitable companies trading below free public fair-value estimates, using Yahoo Finance public quote data, analyst-target data, and historical chart data.

Current product shape:

- ranked live candidate table
- low-noise ticker detail view
- real Yahoo historical OHLC candles in detail view
- chart ranges for `D`, `W`, `M`, `1Y`, `5Y`, and `10Y`
- `EMA20`, `EMA50`, `EMA200`, volume, and MACD panes
- issue rail, popup issue notices, and issue log viewer
- watchlists and watchlist-only filtering
- durable journal persistence and replay mode
- in-terminal symbol tracking and row filtering

The main table is capped for terminal readability. The ticker detail screen still lets you navigate the full filtered ticker set.

## Screenshots

Main ranked view with health status, candidate ranking, alerts, recent tape, and the selected-row summary:

![Main ranked view](docs/screenshots/main.png)

Ticker detail view with historical candles, EMA overlays, volume, MACD, valuation map, consensus, and recent context:

![Ticker detail view](docs/screenshots/ticker-details.png)

## Build And Run

From the project root:

```bash
cargo run
```

Live mode requires outbound HTTPS access to Yahoo Finance public endpoints.

By default the workstation starts with the built-in 503-symbol live universe.

Use a predefined startup profile:

```bash
cargo run -- --profile sp500
cargo run -- --profile dow
cargo run -- --profile merval
```

Use a custom initial symbol list:

```bash
cargo run -- --symbols AAPL,MSFT,NVDA
```

The CLI symbol list only seeds the initial live universe. When `--profile` and `--symbols` are used together, the custom symbols are appended to the selected profile. During a live session you can add more tracked symbols directly in the terminal with `s`.

Smoke mode:

```bash
cargo run -- --smoke
```

Smoke mode is a static verification path and does not hit the network.

Run with journal persistence:

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

Replay mode is journal-backed for workstation state. The ticker detail screen can still fetch Yahoo historical chart data on demand.

## Keyboard Controls

Main view:

- `j` or Down: move selection down
- `k` or Up: move selection up
- `d` or `Enter`: open ticker detail for the selected row
- `w`: toggle watchlist on the selected symbol
- `Space`: pause or resume live feed application
- `/`: enter row-filter mode
- `s`: add one or more live symbols from inside the UI
- `l`: open the issue log viewer
- `f`: toggle watchlist-only filtering
- `Enter`: apply the active filter or symbol input buffer
- `Backspace`: go back in every screen and mode; in text entry it deletes characters until the buffer is empty, then goes back
- `Esc`: clear the active filter in normal mode, or leave the active input mode
- `q`: quit from normal mode
- `Ctrl+C`: quit from any mode

Ticker detail view:

- `j` or `k`: move to the previous or next ticker in the full filtered set
- `1` through `6`: jump chart range between `D`, `W`, `M`, `1Y`, `5Y`, and `10Y`
- `[` or `]`: cycle chart range backward or forward
- `w`: toggle watchlist on the active ticker
- `l`: open the issue log
- `Backspace`, `d`, `Enter`, or `Esc`: close the detail view

## Journal And Watchlist Files

Journal file:

- one event per line
- snapshot lines support 6 or 7 fields
- external valuation lines support 5, 14, or 16 fields

Snapshot line format:

```text
S|sequence|symbol|profitable_flag|market_price_cents|intrinsic_value_cents
S|sequence|symbol|profitable_flag|market_price_cents|intrinsic_value_cents|company_name
```

External valuation line formats:

```text
E|sequence|symbol|fair_value_cents|age_seconds
E|sequence|symbol|fair_value_cents|age_seconds|low_fair_value_cents|high_fair_value_cents|analyst_opinion_count|recommendation_mean_hundredths|strong_buy_count|buy_count|hold_count|sell_count|strong_sell_count
E|sequence|symbol|fair_value_cents|age_seconds|low_fair_value_cents|high_fair_value_cents|analyst_opinion_count|recommendation_mean_hundredths|strong_buy_count|buy_count|hold_count|sell_count|strong_sell_count|weighted_fair_value_cents|weighted_analyst_count
```

Watchlist file:

- one symbol per line

## Verification

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
- [Screen Guide](docs/SCREENS.md)
- [User Manual](docs/USER_MANUAL.md)
