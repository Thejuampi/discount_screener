# Shared Contracts

This directory holds language-neutral fixtures, golden cases, and behavior notes that both apps validate.

## Files

- `portfolio-ranking.json`:
  candidate ranking, watchlist filtering, query filtering, opportunity ranking, and symbol-detail projection
- `chart-ranges.json`:
  canonical chart-range order and display labels used in the product surface
- `dcf-source-selection.json`:
  golden resolver-state cases for selected, unavailable, disabled/absent, and uncertain DCF source decisions
- `valuation-model-family.json`:
  business-class classifier and model-selection goldens (FCFF vs residual income); forbids price-multiple hard caps as acceptance; ACGL-class regression notes
- `persistence-semantics.md`:
  storage behavior that must stay aligned even though Rust and Kotlin use different persistence formats

## Scope

These files are intentionally behavior-focused. They are not shared runtime code, a shared engine, or an FFI boundary.

## Related agent docs

- Root [Agents.md](../Agents.md) — valuation model family and Quant Lens conventions for implementers
- [Valuation Model Family Architecture](../_bmad-output/planning-artifacts/valuation-model-family-architecture.md) — ADRs and phased delivery
- [project-context.md](../_bmad-output/project-context.md) — lean AI rules including dynamic parameters and forbidden output clamps
