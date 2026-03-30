---
name: feature-addition-procedure
description: 'Add a feature to this repository using the established workflow. Use when implementing new behavior, extending the terminal UI, adding persistence or market-data capabilities, wiring new profiles or CLI options, updating tests, and verifying the change with cargo fmt, cargo test, and cargo run -- --smoke.'
argument-hint: 'Describe the feature or behavior to add'
user-invocable: true
---

# Feature Addition Procedure

Use this skill when a request is not just a bug fix or review, but a real feature addition in this repository.

## When to Use

- Add a new user-visible workstation capability.
- Extend ranking, valuation, history, persistence, or profile behavior.
- Add new keyboard actions, CLI flags, or startup options.
- Introduce new persisted state, issue handling, or data-fetching behavior.
- Add supporting tests and documentation for a new feature.

## Repository Workflow

This project expects features to be implemented in the module that owns the concern, with `src/main.rs` limited to terminal flow and orchestration.

## Procedure

1. Define the feature boundary.
   Restate the requested behavior, the user-visible outcome, and whether it affects UI flow, domain state, persistence, market data, startup profiles, or documentation.

2. Choose the owning module before editing.
   Put reusable business logic and shared types in `src/lib.rs`.
   Put Yahoo and chart-fetch behavior in `src/market_data.rs`.
   Put SQLite and warm-start behavior in `src/persistence.rs`.
   Put startup profile definitions and symbol lists in `src/profiles.rs` and `src/profile_data/`.
   Keep `src/main.rs` focused on input handling, rendering, issue presentation, and orchestration.

3. Check existing patterns before changing code.
   Read the closest implementation for the same feature area.
   Reuse existing data shapes, issue-reporting paths, and helper functions instead of adding parallel abstractions.

4. Implement the smallest cohesive change.
   Fix the feature at the owning boundary first, then wire it through callers.
   Avoid mixing persistence, network, and rendering logic in the same code path unless the existing design already requires it.
   Preserve the crate's fixed-point value conventions such as `*_cents`, `*_bps`, `*_hundredths`, and `*_millis`.

5. Add or extend tests in the right layer.
   Use unit tests near the owning module for pure logic.
   Use integration tests in `tests/` for user-facing behavior and state flows.
   Prefer helpers from `tests/support/mod.rs` over building large fixtures inline.
   When possible, validate persistence and restore behavior by round-tripping through journal or warm-start flows instead of asserting internals only.

6. Update user-facing documentation when behavior changes.
   Update `README.md` for high-level capabilities and CLI entrypoints.
   Update `docs/QUICK_START.md` if first-run flow or smoke expectations change.
   Update `docs/USER_MANUAL.md` for controls and operator behavior.
   Update `docs/SCREENS.md` or `docs/HISTORY_TIME_SERIES.md` when screen layout or history behavior changes.

7. Verify before finishing.
   Run `cargo fmt` after Rust edits.
   Run `cargo test` for the main verification pass.
   Run `cargo run -- --smoke` for a fast end-to-end sanity check that does not require live network access.
   If the feature depends on live mode or terminal behavior that smoke mode cannot cover, say so explicitly.

8. Close out with an implementation summary.
   State the user-visible outcome, the files changed, the verification performed, and any remaining limitations or follow-up work.

## Decision Points

- If the feature mostly changes calculation or ranking behavior, start in `src/lib.rs`.
- If it adds provider requests, parsing, or chart/fundamental retrieval, start in `src/market_data.rs`.
- If it changes durable state, schema, warm-start, or restore behavior, start in `src/persistence.rs`.
- If it adds startup universes or aliases, start in `src/profiles.rs` and `src/profile_data/`.
- If it only wires an already-defined domain behavior into controls or rendering, keep the change as thin as possible in `src/main.rs`.
- If the feature is user-visible, update the corresponding docs instead of hiding the behavior change only in code.

Use the subsystem routing guide when a feature spans multiple areas and you need to split responsibilities cleanly.

## Completion Checks

- The feature lives in the correct module boundary.
- `src/main.rs` did not absorb reusable business logic.
- Tests cover the new behavior at the right layer.
- Documentation matches the new behavior when users can observe it.
- `cargo fmt`, `cargo test`, and `cargo run -- --smoke` were run, or any skipped verification is explained.

## References

- Checklist: [feature-checklist.md](./references/feature-checklist.md)
- Subsystem routing: [subsystem-routing.md](./references/subsystem-routing.md)
- Workspace defaults: `AGENTS.md`
- Product overview and CLI behavior: `README.md`
- Operator docs: `docs/QUICK_START.md`, `docs/USER_MANUAL.md`, `docs/SCREENS.md`, `docs/HISTORY_TIME_SERIES.md`
