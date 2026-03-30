# Project Guidelines

## Architecture

- Keep reusable business logic and shared data types in `src/lib.rs`; reserve `src/main.rs` for terminal UI flow and application orchestration.
- Keep external boundaries separate: Yahoo data fetching lives in `src/market_data.rs`, SQLite persistence and restore logic live in `src/persistence.rs`, and startup symbol profiles live in `src/profiles.rs` with data files under `src/profile_data/`.
- Prefer extending the existing module that owns a concern rather than adding cross-cutting logic to the terminal entrypoint.
- Integration tests live in `tests/` and commonly share setup helpers from `tests/support/mod.rs`.

### Event Loop

The main loop (`src/main.rs:~2790`) is an `mpsc::Receiver<AppEvent>` that processes a single event then calls `render()`. The `AppEvent` enum is the central dispatch:

| Variant | Source thread | Purpose |
|---|---|---|
| `Input(KeyEvent)` | crossterm reader thread | User keypresses |
| `Resize` | crossterm reader thread | Terminal resize |
| `FeedBatch(Vec<FeedEvent>)` | feed loop thread | Yahoo quote/fundamentals/coverage updates |
| `ChartData(ChartDataEvent)` | chart loop thread | Historical OHLC candles |
| `AnalysisData(AnalysisDataEvent)` | analysis worker thread | DCF computation results |
| `HistoryLoaded { .. }` | persistence thread | SQLite warm-start restore |

Each thread receives commands via its own `mpsc::Sender<*Control>` channel and publishes results back on the shared `AppEvent` channel. The main thread owns all mutable state (`AppState`, `TerminalState`) — no locking needed.

### Rendering

The `render()` function builds a full `Vec<RenderLine>` every call, then `ScreenRenderer` compares against the previous frame row-by-row. Only dirty rows are written to the terminal, bracketed by `BeginSynchronizedUpdate`/`EndSynchronizedUpdate` to prevent tearing. The ticker detail layout (`detail_layout()`) dynamically switches between compact and full modes based on viewport dimensions.

### Data Flow

```
Yahoo Finance HTML pages
    → MarketDataClient (src/market_data.rs) parses quote pages into FeedEvent
    → feed loop publishes AppEvent::FeedBatch
    → main loop updates TerminalState (src/lib.rs)
    → render() reads TerminalState + AppState to produce RenderLines
    → ScreenRenderer writes only changed rows via crossterm
```

DCF analysis is a second async path: `AppState` queues `AnalysisCacheEntry::Loading`, the analysis worker fetches Yahoo cash-flow history, computes bear/base/bull scenarios, and publishes `AppEvent::AnalysisData`.

### Key Types

- `TerminalState` (lib.rs) — the authoritative state for all tracked symbols, rankings, and alerts
- `AppState` (main.rs) — UI-specific state: selection, input modes, analysis/chart caches, issue center
- `SymbolDetail` (lib.rs) — per-ticker aggregate used by all render functions
- `FundamentalSnapshot` (lib.rs) — Yahoo fundamentals with fixed-point fields (`*_cents`, `*_bps`, `*_hundredths`, `*_millis`)
- `AnalysisCacheEntry` (main.rs) — per-symbol DCF state machine: `Loading → Ready | Failed`

## Build And Test

- Use strict TDD for behavior changes: write the failing test first, implement the smallest change to reach GREEN, then REFACTOR only while the test suite stays green.
- Run `cargo test` for the main verification pass.
- Run `cargo test --bin discount_screener -- <test_name>` to run a single unit test.
- Run `cargo test --bin discount_screener -- <substring>` to run tests matching a substring.
- Run `cargo run -- --smoke` for a non-interactive smoke check; it is the quickest way to verify the binary and does not require live network access.
- Run `cargo run` for the full workstation. Live mode needs a terminal that supports alternate screen mode and outbound HTTPS access to Yahoo Finance public endpoints.
- After finishing a task, do mutation testing locally around the changed behavior.
- Prefer an automatic framework such as `cargo-mutants` when practical, then add a few manual mutations around the changed logic to confirm the tests fail for incorrect behavior.
- If mutation testing is not practical in the current environment, say so explicitly and describe the gap.
- Run `cargo fmt` before finishing Rust changes.

## Conventions

- Preserve the fixed-point financial value style used across the crate: fields named `*_cents`, `*_bps`, `*_hundredths`, and `*_millis` should stay integer-based unless there is a strong reason not to.
- Prefer type-driven design: encode invariants in types so the compiler enforces them instead of scattering runtime validation across the codebase.
- Push validation to boundaries such as constructors, parsers, and deserialization, then keep inner fields private so downstream code works with already-valid values.
- Prefer domain types and enums such as validated string wrappers, non-empty collections, bounded values, mutually exclusive variants, state-specific types, and `NonZero*` primitives when they remove invalid states.
- When a value must satisfy an invariant everywhere, model that invariant once in the type instead of repeating checks at each call site.
- Keep persistence, market-data, and terminal UI concerns decoupled; avoid mixing network or storage behavior directly into rendering code.
- When testing or designing external-layer behavior such as Yahoo provider integration, base the work on at least 5 distinct real samples gathered from the live upstream system.
- Do not invent provider payloads from documentation or assumptions when the feature depends on external inputs; if live sampling is unavailable, stop and surface that blocker instead of fabricating fixtures.
- In tests, prefer small helpers and focused fixtures over hand-building large snapshots inline when existing helpers already cover the shape.
- When changing user-visible behavior, update or link the existing docs instead of duplicating long operational guidance in code comments.

## Documentation

- See `README.md` for the product overview, CLI entrypoints, and persistence examples.
- See `docs/QUICK_START.md` for the first-run flow and smoke-check expectations.
- See `docs/USER_MANUAL.md` for operator behavior and keyboard controls.
- See `docs/SCREENS.md` and `docs/HISTORY_TIME_SERIES.md` for UI layout and historical-chart details.
