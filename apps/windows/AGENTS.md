# Windows Agent Guide

This guide extends the root `AGENTS.md` for the Tauri and React workstation.

## Ownership

- Keep valuation rules in `src-tauri/src/dcf_model.rs` or the owning valuation module.
- Keep Quant Lens rules in `src-tauri/src/quant_lens.rs`.
- Keep React components focused on presentation and actions.
- Keep typed unavailable reasons visible in Detail.
- Keep Advisor CSV import kinds distinct.

Read [Advisor CSV import](../../docs/product/advisor-csv-import.md) before import changes.

## Quant Lens

- Count independent evidence families.
- Do not count an analyst gap as a second analyst family.
- Require solid model quality for `Strong`.
- Prefer the model when a solid model agrees with analysts.
- Prefer analysts when a soft model agrees with analysts.
- Use `Disputed` when model and analyst anchors diverge materially.
- Show both disputed anchors.
- Do not publish one blended upside for a disputed result.
- Compute residual income on demand for financial services.
- Never route financial float through FCFF.

## Valuation Gate

For classifier, FCFF, WACC, CapEx, residual-income, or policy changes, run:

1. `cargo test --lib dcf_model::`
2. `cargo test --lib valuation_baseline::`
3. `cargo test --lib quant_lens::` when Quant Lens changes.

Run these commands from `apps/windows/src-tauri`.

The baseline requires all fixture slots. Do not call quarantine a pass.

Read [the baseline policy](../../docs/operations/valuation-baseline-policy.md) before these changes.

## Live QA

Run Windows live QA only after Juan requests it.

- Start one process with `npm run tauri:dev:qa` from `apps/windows`.
- Do not use bare `npm run tauri:dev` for agent QA.
- Keep the universe locked to `qa`.
- Use `npm run ds-ui:self-check` before the checklist.
- Use `npm run live-qa:checklist` for the full path.
- Restart only after a native rebuild.

Follow [the live QA checklist](../../docs/operations/valuation-live-qa.md).

Record each executed path as a use-case scenario. Mark other paths `Not run`.
