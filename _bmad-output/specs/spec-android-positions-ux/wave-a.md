# Wave A: portfolio data

Read SPEC.md, every companion, and .memlog.md first. This is a bounded implementation of wave A only.
The parent owns design and architecture. Raise gaps to the parent; do not invent another policy.
You are not alone in the codebase. Preserve all existing and concurrent edits.

## Ownership

- New Core portfolio exposure and research files under `apps/android/core/src/main/kotlin/com/discountscreener/core/portfolio/`.
- `apps/android/app/src/main/kotlin/com/discountscreener/android/presentation/dashboard/PositionsPresentation.kt`.
- Focused tests for those files under the matching Core and app test paths.

Do not edit DashboardViewModel, Compose files, import parsers, engines, or documentation outside your result report.
Keep current `projectPositions` signature compatible. Every call must return enriched rows with shared-book weights.
Expose a pure book-summary presenter from those rows, plus sort helpers for the UI wave.
Retain raw input data if the summary needs exact aggregate math. Do not total rounded row dollars.
Existing `PositionsRow` fixture constructors should remain source-compatible through defaulted new fields.
The parent and UI wave will consume the new row and summary fields.

## Execution and evidence

Write failing behavior tests first. Run focused tests and retain the red then green command evidence.
Run Core exposure/research tests and app PositionsPresentationTest with one `--rerun` per test task.
Use offline fixtures. Do not run any device or provider command.
Do not commit or push. Do not spawn another agent.
Write `wave-a-result.md` with exact public API, changed files, test commands/results, and any gap.
Map each relevant examples.md Case to its test. Return that compact report to the parent.
