# Shared Contracts

This directory holds language-neutral fixtures, golden cases, and behavior notes that both apps validate.

## Files

- `portfolio-ranking.json`:
  candidate ranking, watchlist filtering, query filtering, opportunity ranking, and symbol-detail projection
- `chart-ranges.json`:
  canonical chart-range order and display labels used in the product surface
- `dcf-source-selection.json`:
  golden resolver-state cases for selected, unavailable, disabled/absent, and uncertain DCF source decisions
- `persistence-semantics.md`:
  storage behavior that must stay aligned even though Rust and Kotlin use different persistence formats

## Scope

These files are intentionally behavior-focused. They are not shared runtime code, a shared engine, or an FFI boundary.
