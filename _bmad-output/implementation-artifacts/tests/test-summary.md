# Test Automation Summary

## Scope

FMP analyst forecasts in the Windows stock-detail and Settings workflows.

## Generated Tests

### API and command boundary

- No live FMP REST test was added to the default suite because it would consume the limited provider budget and require a secret.
- The existing opt-in Rust contract test remains the real-provider path for AAPL, MSFT, ACGL, TSLA, and JPM.
- The generated browser tests mock the Tauri command boundary and assert that opening AAPL calls `get_analyst_forecasts` with `{ symbol: "AAPL" }`, while the settings flow sends the key only in the `fmp_save_key` command payload.

### E2E

- `apps/windows/e2e/specs/analyst-forecasts.e2e.ts`
  - Proves no analyst request occurs before the stock detail is opened.
  - Opens AAPL from the real screener UI.
  - Verifies provider/cache/quota presentation, budget warning, timeline, histogram, individual rows, simple statistics, unavailable weighted consensus, and horizon labels.
  - Covers `insufficient_coverage`, `empty`, `missing_key`, `invalid_key`, `quota_exhausted`, and `provider_unavailable`.
- `apps/windows/e2e/specs/fmp-settings.e2e.ts`
  - Exercises save, test, and remove.
  - Verifies the secret input is a password field, is cleared after save, and is not echoed into the rendered UI.
  - Covers unavailable key-status handling.

## Coverage Metrics

- 2 E2E spec files.
- 9 E2E scenarios: 9 passed.
- 7 analyst-detail scenarios, including 6 sparse/error states.
- 2 Settings scenarios.
- Zero FMP network calls and zero daily provider-budget usage during E2E.

## Test Quality Checklist

- [x] Uses WebdriverIO with the Tauri browser-mode service.
- [x] Uses the real React application flow with backend commands mocked at the IPC boundary.
- [x] Covers the happy path and critical provider/settings failures.
- [x] Uses user-visible text, roles, form attributes, and stable structural locators.
- [x] Contains no fixed sleeps or hardcoded wait delays.
- [x] Resets mocks and page state before every scenario.
- [x] Keeps application logic in the backend; the harness supplies backend-owned view models.

## Verification

- [x] `npm run test:e2e` — 9 passed.
- [x] `npx eslint e2e wdio.conf.ts` — passed.
- [x] `npm test` — 91 passed.
- [x] `npm run build` — passed.
- [x] `cargo test` — 177 passed, 2 ignored.
- [x] `npm audit --omit=dev` — 0 production vulnerabilities.
- [ ] Live FMP contract — intentionally not run; requires `FMP_API_KEY` and consumes provider quota.
- [ ] `cargo fmt --check` — blocked by pre-existing formatting differences in tracked valuation files outside this QA change.
- [ ] Repository-wide `npm run lint` — existing baseline has 51 errors and 1 warning across production files; the generated E2E files lint cleanly.

## Notes

- The E2E runner pins `@wdio/tauri-service` 1.2.0 and overrides its `@wdio/native-utils` dependency to 2.5.0 because the service imports a helper absent from its declared 2.4.0 dependency.
- The full development dependency audit reports 26 advisories (1 moderate, 25 high) in the current Vite/WebdriverIO toolchain; they are dev-only, and the production-only audit is clean.
- No licensed FMP response or API key is stored in the repository.
