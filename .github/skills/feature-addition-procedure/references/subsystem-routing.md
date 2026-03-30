# Subsystem Routing

Use this guide when a feature touches more than one area and needs to be split cleanly across the codebase.

## Core Rule

Put the behavior in the module that owns it, then keep the wiring in `src/main.rs` as thin as possible.

## Routing Matrix

### Domain Logic And Shared State

Use `src/lib.rs` when the feature adds or changes:

- ranking or qualification logic
- shared structs or enums
- fixed-point financial calculations
- state transitions that are not specific to rendering or I/O

### Terminal UI And Orchestration

Use `src/main.rs` when the feature adds or changes:

- keyboard handling
- input modes
- rendering and layout decisions
- issue presentation and orchestration glue

Do not move reusable calculations or persistence rules here just because the feature is visible in the terminal.

### Market Data And External Fetching

Use `src/market_data.rs` when the feature adds or changes:

- Yahoo endpoints or request parameters
- parsing of quote pages or API responses
- historical candles, chart ranges, or fundamentals retrieval
- retry or fetch-specific behavior tied to external data collection

### Persistence And Warm Start

Use `src/persistence.rs` when the feature adds or changes:

- SQLite schema or migrations
- durable state capture
- warm-start hydration
- tracked symbol, watchlist, issue, or raw capture persistence

If schema changes are involved, keep `PRAGMA user_version` and migration compatibility in mind.

### Startup Profiles And Symbol Universes

Use `src/profiles.rs` and `src/profile_data/` when the feature adds or changes:

- named startup profiles
- profile aliases
- bundled symbol lists

## Cross-Cutting Features

When a feature spans multiple areas, apply the split in this order:

1. Add domain behavior in the owner module.
2. Extend persistence or market-data boundaries if needed.
3. Wire the final behavior through `src/main.rs`.
4. Add tests at the narrowest correct layer and then one behavior-level integration test if needed.
5. Update docs for any user-visible outcome.

## Typical Examples

- New ranking metric: `src/lib.rs`, tests near logic, maybe `src/main.rs` for display only.
- New detail screen panel backed by fetched data: `src/market_data.rs` for fetch/parse, `src/lib.rs` for shared shaping if needed, `src/main.rs` for rendering.
- New durable toggle or persisted issue state: `src/persistence.rs` plus thin `src/main.rs` wiring.
- New startup basket: `src/profiles.rs`, `src/profile_data/`, and CLI or usage docs if exposed.
