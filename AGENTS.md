# Discount Screener Agent Guide

This file contains repository-wide rules. Each application has a scoped `AGENTS.md` with local rules.

## Product

Discount Screener is Juan's personal investment-analysis workstation.

- Juan is the only user.
- Do not add multi-user product work unless Juan asks.
- Show evidence, provenance, uncertainty, and unavailable states.
- Do not present investment advice.

## Communication

Use ASD-STE100 Simplified Technical English in replies and project documents.

- Keep each sentence to 20 words or less.
- Use active voice.
- Use simple present, past, or future tense.
- Use one word for one meaning.
- Put complex data in a table or vertical list.
- Give the result first.
- Do not add a summary to a short reply.

Technical names remain unchanged.

## Repository Map

| Path | Owner |
| --- | --- |
| `apps/desktop` | Rust terminal workstation |
| `apps/windows` | Tauri and React Windows workstation |
| `apps/android` | Kotlin Android workstation |
| `apps/flutter` | Flutter multiplatform workstation |
| `shared/contracts` | Cross-platform contracts and goldens |
| `docs/product` | Current product behavior |
| `docs/architecture` | Durable technical decisions |
| `docs/operations` | Test, QA, and delivery procedures |
| `docs/research` | Research evidence |
| `docs/archive` | Historical plans and generated artifacts |
| `lab` | Reproducible analysis code and recorded datasets |
| `scripts` | Repository automation |

Use [the documentation index](docs/index.md) to find the current source.

## Sources Of Truth

Use this order when two sources conflict:

1. Executable contracts in `shared/contracts`.
2. Current architecture and product documents in `docs`.
3. The nearest scoped `AGENTS.md`.
4. This file.
5. Archived documents.

The archive records history. It does not define current behavior.

BMad tools come from Juan's account profile. This repository contains no BMad installation.

Do not install BMad in this repository. Do not create `_bmad`, `_bmad-output`, or local skill catalogs.

BMad never defines the repository structure. Use it only when Juan asks for its workflow.

## Engineering Rules

- Put reusable business logic in the module that owns the concern.
- Keep entry points focused on orchestration.
- Keep UI components passive.
- Keep network, persistence, and rendering separate.
- Preserve fixed-point financial values.
- Use typed states for unavailable, stale, disputed, and provisional data.
- Prefer refusal with a reason over an invented value.
- Keep expensive work bounded and demand-driven.
- Preserve user data during app updates and QA.

Use `valuation_core::robust_mean` for issuer-series estimates. Do not add another plain mean implementation.

Use median and MAD for cross-sectional scores. Keep `MAX_ABSOLUTE_Z = 3.0`.

## Valuation Boundary

Read [the valuation architecture](docs/architecture/valuation-model-family.md) before valuation work.

- Operating companies use FCFF and WACC.
- Financial services use residual income and cost of equity.
- Payment networks use FCFF, even with a credit-services label.
- Unknown classifications fail closed.
- Missing required drivers produce an unavailable reason.
- Do not use output caps, sector haircuts, or silent model fallbacks.
- Keep model, policy, source, and rate provenance with each value.

Provider selection remains separate from valuation semantics.

Read [the source architecture](docs/architecture/dcf-source-consistency.md) before provider changes.

## Change Rules

- Use strict TDD for behavior changes.
- Add one test for each behavior case.
- Use `Scenario Outline` with at least two example rows for Gherkin.
- Update current documentation when user-visible behavior changes.
- Do not treat green tests as proof of correct operational behavior.
- Use at least five real upstream samples for provider-shape work.
- Run mutation tests around changed numerical logic when practical.
- Mutate numeric boundaries in both directions.
- Name the consumer for every new engine field.

Before presenting a numerical conclusion:

1. Compare it with a filing or fixture.
2. Investigate differences above about 50 percent.
3. Use at least two names for a cluster claim.
4. State the neutral baseline before the experiment.
5. Mark dubious inputs as pending.

## Test Entry Points

| Surface | Required command |
| --- | --- |
| Desktop | `cargo test` from `apps/desktop`, then `cargo run -- --smoke` |
| Windows | `cargo test` from `apps/windows/src-tauri`; run relevant frontend tests |
| Android | `pwsh -File scripts/validate-android.ps1` |
| Flutter | `pwsh -File scripts/validate-flutter.ps1` |
| Contracts | `make contracts-test` |

Run `cargo fmt` before you finish Rust changes.

Read the scoped guide before work in an application:

- [Desktop rules](apps/desktop/AGENTS.md)
- [Windows rules](apps/windows/AGENTS.md)
- [Android rules](apps/android/AGENTS.md)
- [Flutter rules](apps/flutter/AGENTS.md)

## Workspace Safety

- Preserve unrelated user changes.
- Do not clear Android app data.
- Do not uninstall the Android app to change certificates.
- Use the `qa` universe for agent or manual live QA.
- Do not run live QA until Juan says the product is ready.
- Keep secrets, databases, build products, and agent state out of Git.

## Documentation Maintenance

- Keep one current home for each fact.
- Use links instead of copied rules.
- Move obsolete plans to `docs/archive`.
- Do not cite archived documents as current policy.
- Update `docs/index.md` when a durable document moves.
