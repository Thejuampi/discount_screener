# Feature Addition Checklist

Use this as a compact companion to the main skill.

## Before Editing

- Restate the requested feature and the user-visible outcome.
- Identify the owning module before touching code.
- Read the closest existing implementation in the same subsystem.
- Note which docs may need updates if behavior changes.

## Module Ownership

- `src/lib.rs`: shared business logic and core data types.
- `src/main.rs`: terminal orchestration, rendering, and input flow.
- `src/market_data.rs`: Yahoo and historical data fetching or parsing.
- `src/persistence.rs`: SQLite persistence, warm-start, and restore logic.
- `src/profiles.rs` and `src/profile_data/`: startup universes and profile aliases.

## Implementation Rules

- Extend the owner module instead of pushing logic into `src/main.rs`.
- Preserve fixed-point finance fields such as `*_cents`, `*_bps`, `*_hundredths`, and `*_millis`.
- Reuse existing issue-reporting and test helpers when they already fit.
- Prefer focused changes over introducing new abstractions early.

## Tests

- Add unit tests near pure logic changes.
- Add or update integration tests in `tests/` for behavior that spans modules.
- Prefer helpers from `tests/support/mod.rs` for snapshots and fixtures.
- Use replay, journal, or persistence flows when the feature affects restore behavior.

## Docs

- `README.md` for capabilities and CLI flags.
- `docs/QUICK_START.md` for first-run and smoke-flow changes.
- `docs/USER_MANUAL.md` for controls and operator behavior.
- `docs/SCREENS.md` for layout and visible screen changes.
- `docs/HISTORY_TIME_SERIES.md` for history or persistence-facing behavior.

## Verification

- `cargo fmt`
- `cargo test`
- `cargo run -- --smoke`

## Final Review

- The feature is implemented at the correct architectural boundary.
- Tests and docs match the new behavior.
- Any unverified live-mode or terminal-specific risk is called out explicitly.
