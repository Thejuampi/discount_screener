# Project Guidelines

## Architecture

- Keep reusable business logic and shared data types in `src/lib.rs`; reserve `src/main.rs` for terminal UI flow and application orchestration.
- Keep external boundaries separate: Yahoo data fetching lives in `src/market_data.rs`, SQLite persistence and restore logic live in `src/persistence.rs`, and startup symbol profiles live in `src/profiles.rs` with data files under `src/profile_data/`.
- Prefer extending the existing module that owns a concern rather than adding cross-cutting logic to the terminal entrypoint.
- Integration tests live in `tests/` and commonly share setup helpers from `tests/support/mod.rs`.

## Build And Test

- Use strict TDD for behavior changes: write the failing test first, implement the smallest change to reach GREEN, then REFACTOR only while the test suite stays green.
- Run `cargo test` for the main verification pass.
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
