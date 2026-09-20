# Desktop Agent Guide

This guide extends the root `AGENTS.md` for the Rust terminal workstation.

## Architecture

- The main thread owns `AppState` and `TerminalState`.
- Workers send `AppEvent` values through the channel.
- The loop handles one event, then renders.
- Do not add shared locks around application state.
- Keep Yahoo access in `src/market_data.rs`.
- Keep SQLite access in `src/persistence.rs`.
- Keep startup profiles in `src/profiles.rs`.
- Keep reusable logic in `src/lib.rs` or the owning workstation module.
- Keep `src/main.rs` focused on orchestration.
- Build complete `RenderLine` frames before dirty-row output.
- Do not mix network or storage work into rendering.

## Data Boundaries

- Stream SEC company facts through `sec_company_facts_sieve`.
- Widen the sieve before a reader needs another fact.
- Treat provider data as sparse and unstable.
- Load complete price history only for the selected ticker.
- Keep startup restore bounded.
- Keep desktop valuation fail-closed for unknown business classes.

Read these documents before related work:

- [Valuation architecture](../../docs/architecture/valuation-model-family.md)
- [Provider source architecture](../../docs/architecture/dcf-source-consistency.md)
- [Cross-platform parity](../../docs/architecture/cross-platform-parity.md)

## Checks

Run these commands from `apps/desktop`:

1. `cargo fmt`
2. `cargo test`
3. `cargo run -- --smoke`

Use provider samples when parser behavior changes.
