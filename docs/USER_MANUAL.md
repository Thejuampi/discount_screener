# User Manual

Need the shortest path first: [Quick Start](QUICK_START.md)

## Disclaimer

This workstation is for informational and educational use only. It does not provide trading, investment, legal, tax, or financial advice, and it does not recommend any transaction or position.

Public market and analyst data may contain errors, omissions, stale values, or interpretation risk. You remain fully responsible for validating any information and for your own decisions.

## Purpose

Discount Screener Workstation is a Rust terminal application for tracking symbols that are profitable and trading below free public fair-value estimates.

It is designed for fast local interaction and deterministic replay.

## Before You Start

Requirements:

- Rust toolchain installed
- a terminal that supports alternate screen mode
- outbound HTTPS access to Yahoo Finance public quote pages

Project root commands assume you are in the repository root.

## Starting The Workstation

### Live Session

```bash
cargo run
```

This starts the interactive workstation using Yahoo Finance public quote and analyst-target data.

Default live symbols:

- `AAPL`
- `MSFT`
- `NVDA`
- `AMZN`
- `META`
- `GOOG`
- `TSLA`
- `AMD`

Use a custom live symbol set:

```bash
cargo run -- --symbols AAPL,MSFT,NVDA
```

That CLI list only seeds the initial live universe. During a live session you can add more symbols from inside the terminal with `s`.

### Live Session With Persistence

```bash
cargo run -- --journal-file data/session.journal --watchlist-file data/watchlist.txt
```

Use this mode when you want the workstation to restore previous journal history and watchlist state on restart.

Behavior:

- existing journal file is replayed at startup if present
- existing watchlist file is loaded at startup if present
- new journal events are appended while the session runs
- watchlist changes are saved when you toggle membership and again on exit

### Replay Session

```bash
cargo run -- --replay-file data/session.journal
```

Replay mode loads a saved journal and opens the workstation without starting the live feed.

Typical use cases:

- post-mortem review
- product demos
- deterministic debugging
- ranking regression review

### Smoke Mode

```bash
cargo run -- --smoke
```

Smoke mode prints a short non-interactive summary and exits.

## Navigation And Controls

### Selection

- `j` or Down moves to the next visible row
- `k` or Up moves to the previous visible row
- `d` or `Enter` opens the detailed screen for the selected ticker

### Ticker Detail Screen

- `d` or `Enter` opens the current ticker in a full-screen detail view
- `j` and `k` move to the previous or next ticker inside the current filtered set
- `w` toggles watchlist membership for the active ticker
- `l` opens the operational issue log viewer from the detail screen
- `Backspace`, `Esc`, `d`, or `Enter` closes the detail screen

The detail screen shows:

- current market price plus mean and median analyst targets
- low and high analyst target range
- dollar discount and percentage gap to the mean target
- analyst count and recommendation mean
- strong buy, buy, hold, sell, and strong sell counts
- qualification state and confidence state
- external signal status
- last sequence number and update count
- ticker-specific recent alerts and recent tape events

### Row Filter

- `/` enters row-filter mode
- type the query text
- `Enter` applies the filter query
- `Backspace` removes one character, or leaves filter mode when the buffer is empty
- `Esc` still leaves filter mode without applying the current buffer

Filter behavior:

- matching is case-insensitive
- filtering is applied to the visible symbol text

### Symbol Lookup

- `s` enters symbol lookup mode in live sessions
- type a ticker such as `NVDA`
- `Enter` adds the symbol to the live tracked universe
- comma-separated input such as `AAPL,MSFT,NVDA` adds multiple symbols at once
- `Backspace` removes one character, or leaves symbol lookup mode when the buffer is empty
- `Esc` still leaves symbol lookup mode without applying the current buffer

After a symbol is added, the table filter is focused on that ticker so you can inspect it immediately.

### Operational Issues

- `l` opens the operational issue log viewer
- `Backspace`, `Esc`, `l`, or `q` closes the issue log viewer
- `j` and `k` move through recorded issues in the viewer
- `c` clears resolved issues from the viewer

Operational issue behavior:

- the header shows a health banner with `healthy`, `degraded`, `down`, or `critical`
- the issue rail highlights the most important active operational problem
- new warnings and errors appear in a temporary popup-style banner
- the log viewer keeps both active and resolved issue history for the current session

### Watchlist

- `w` toggles watchlist membership for the selected symbol
- `f` toggles watchlist-only mode for the table

When watchlist-only mode is on, only watched symbols remain in the top-candidate grid.

### Pause And Resume

- `Space` pauses live application of incoming feed updates
- press `Space` again to resume

While paused:

- the current screen stays stable
- your selected symbol remains fixed
- pending events accumulate in the status line instead of constantly reshuffling the table

### Reset

- `Backspace` in normal mode clears the active filter state
- `Esc` still clears the active filter state

### Exit

- `q` quits the workstation from normal mode
- `Ctrl+C` quits the workstation from any mode

## Screen Areas

The terminal is organized into five operator areas:

1. Header and control legend
2. Status line with source, tracked universe, symbol counts, event count, and update rate
3. Health banner and issue rail
4. Filter and input status lines
5. Top-candidate table
6. Detail panel for the selected symbol
7. Alerts and recent tape panels

For annotated layouts, see [SCREENS.md](SCREENS.md).

## How Ranking Works

The ranked table is ordered by:

1. qualified symbols before non-qualified symbols
2. larger discount gap before smaller gap
3. higher confidence before lower confidence
4. symbol name for stable ordering

### Qualification Rules

A symbol is currently `Qualified` when both conditions are true:

- the symbol has positive trailing EPS
- the fair-value gap in basis points is greater than or equal to the configured minimum gap threshold

### Confidence Rules

Confidence represents support from external valuation signals:

- `Provisional`: qualified, but no external signal is available
- `High`: qualified and externally supportive
- `Low`: unqualified, stale, or divergent external state

## Detail View Fields

The detail panel shows:

- symbol
- watched flag
- qualification status
- market price
- provider fair value
- gap percentage
- confidence band
- external signal status
- last event sequence number
- update count for that symbol

The full-screen ticker detail view expands that summary with qualification and confidence explanations plus ticker-specific alert and tape history.

## Alerts

The workstation emits three alert kinds:

- `entered-qualified`
- `exited-qualified`
- `confidence-upgraded`

Alerts are displayed in the alert panel and are also reconstructable from the replayed journal.

## Journal Files

Journal files store normalized events line by line.

### Snapshot Event

```text
S|sequence|symbol|profitable_flag|market_price_cents|intrinsic_value_cents
```

Example:

```text
S|1|BETA|1|8000|10000
```

### External Valuation Event

```text
E|sequence|symbol|fair_value_cents|age_seconds
```

Example:

```text
E|3|BETA|12000|5
```

## Watchlist Files

Watchlist files contain one symbol per line.

Example:

```text
ALFA
BETA
NVDA
```

## Typical Operator Workflows

### Workflow 1: Start A Durable Session

1. Start the app with `--journal-file` and `--watchlist-file`.
2. Let the live feed populate the table.
3. Press `s` and add any extra symbols you want to track.
3. Use `w` to mark important symbols.
4. Exit with `q`.
5. Restart with the same files to restore the session.

### Workflow 2: Review A Saved Session

1. Start the app with `--replay-file path/to/session.journal`.
2. Navigate the candidate list.
3. Inspect detail and alert history for symbols of interest.

### Workflow 3: Focus On A Theme Or Basket

1. Press `s` and add the symbols you want to track in the live universe.
2. Add relevant symbols to the watchlist.
3. Press `f` to show only watched symbols.
4. Press `/` and enter a symbol prefix to narrow the visible set.

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

### The app starts but no symbols appear

In live mode, verify outbound HTTPS access and use symbols with Yahoo Finance coverage for price, trailing EPS, and analyst targets.

If the source is failing, the health banner and issue rail should report the problem directly in the UI. Press `l` for the full issue history.

In replay mode, check that the replay file exists and contains valid journal lines.

### My watchlist did not restore

Check that:

- you passed `--watchlist-file`
- the file exists
- the file contains one symbol per line

### Replay fails to load

Replay will fail if a journal line is malformed. Each line must match one of the documented event formats.

## Current Release Notes

This release now uses a free public Yahoo Finance live feed in normal mode. Smoke mode remains static and replay mode remains journal-backed.