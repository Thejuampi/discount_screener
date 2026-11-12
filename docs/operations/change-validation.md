# Change Validation

## Behavior Changes

- Write one failing test for each behavior case. Run it before you change the implementation.
- Use `Scenario Outline` with at least two example rows for Gherkin.
- Verify each change through the normal entry point. For ports, verify both clients.
- Name the consumer for every new engine field.
- Use at least five real upstream samples when changing the interpretation of a provider response.
- Use the [operational anti-patterns ledger](operational-anti-patterns.md) to select checks for known failures.

## Numerical Checks

- Use `valuation_core::robust_mean` for issuer series. Reuse the existing aggregation code.
- Use median and MAD for cross-sectional scores. Keep `MAX_ABSOLUTE_Z = 3.0`.
- Run mutation tests around changed numerical logic when practical. Move each numeric boundary in both directions.
- Compare numerical conclusions with a filing or fixture. Investigate differences above about 50 percent.
- Use at least two issuers for a cluster claim. State the neutral baseline and mark doubtful inputs as pending.

## Test Entry Points

| Surface | Required check |
| --- | --- |
| Rust terminal | Run `cargo test`, then `cargo run -- --smoke`, from `apps/desktop`. |
| Go CLI | Run `pwsh -File scripts/validate-go-cli.ps1` in checkouts with `apps/cli`. |
| Windows | Run `cargo test` from `apps/windows/src-tauri`. Run the relevant frontend tests. |
| Android | Run `pwsh -File scripts/validate-android.ps1`. |
| Flutter | Run `pwsh -File scripts/validate-flutter.ps1`. |
| Shared contracts | Run `make contracts-test`. |

Run `cargo fmt` before you finish Rust changes.
Use the [live QA procedure](valuation-live-qa.md) for authorized valuation checks.
