# Vantage Handoff

## Start On The New Machine

Use Windows with Git, Node.js 24, rustup, MSVC C++ build tools, the Windows SDK, and WebView2.
The handoff build used Node.js 24.13.0, npm 11.6.2, and Rust 1.93.0.
Use Chrome and a matching ChromeDriver for the browser tests.

In PowerShell, clone the branch that contains this file.

```powershell
git clone --branch codex/vantage-android-parity https://github.com/Thejuampi/discount_screener.git
Set-Location discount_screener
git status --short --branch
git log -1 --oneline
```

In an existing clean clone, create a continuation branch from the remote handoff.

```powershell
git fetch origin
git switch --create codex/vantage-continuation --track origin/codex/vantage-android-parity
```

Use the repository root for the commands below.
Read the [product principles](../../AGENTS.md), the [Windows guide](../../apps/windows/AGENTS.md), and the [validation guide](change-validation.md).

## Build And Validate

Use this toolchain to reproduce the native validation run.

```powershell
rustup toolchain install 1.93.0-x86_64-pc-windows-msvc
rustup override set 1.93.0-x86_64-pc-windows-msvc
```

Install locked dependencies once. This step needs network access to the package registries.

```powershell
Push-Location apps/windows
npm.cmd ci
Pop-Location
cargo fetch --locked --manifest-path apps/windows/src-tauri/Cargo.toml --target x86_64-pc-windows-msvc
```

Run frontend unit tests and build the assets for Tauri.
Set `CHROMEDRIVER_PATH` to the absolute path of the local `chromedriver.exe` before running browser tests.
The browser tests use simulated Tauri commands and a server on `127.0.0.1:1421`.

```powershell
Push-Location apps/windows
npm.cmd test
npm.cmd run build
npm.cmd run test:e2e
Pop-Location
```

Run the native offline suite and build the executable with the bundled frontend.
Use the explicit binary name because the workspace also contains `vantage-headless`.

```powershell
Push-Location apps/windows/src-tauri
$vantageTestTemp = Join-Path (Get-Location) 'target\vantage-test-temp'
New-Item -ItemType Directory -Path $vantageTestTemp -Force | Out-Null
$env:TMP = $vantageTestTemp
$env:TEMP = $vantageTestTemp
cargo test --locked --offline -- `
  --skip high_signal_screener_cohort_all_members_pass `
  --skip export_random20_sp500_parity_snapshot `
  --skip ensure_symbol_loaded_does_not_grow_active_symbols `
  --skip reapply_same_symbol_set_is_idempotent `
  --skip rank_input_bumps_on_profile_switch_clear_universe
cargo build --locked --offline --bin discount-screener-windows --features tauri/custom-protocol
Pop-Location
```

The default executable path is `apps/windows/src-tauri/target/debug/discount-screener-windows.exe`.
For normal use, start this executable or point a desktop shortcut at it.
Use **Salir** from the tray before switching executables. The close button can leave Vantage running in the tray.
Vantage can focus the existing instance when a second executable starts. Verify the running path after a switch.
Run live QA only with Juan's authorization and the [QA procedure](valuation-live-qa.md).

### Validation Baseline

The frontend passed 240 unit tests. The full browser suite passed 49 cases in 14 specs.
The native run passed 775 tests and had 25 ignored tests and five exclusions.
It reproduced these three baseline failures. Compare new failures with the [feed validation record](windows-feed-refresh.md#detail-and-advisor-baseline).

| Rust test | Known failure |
| --- | --- |
| `durable_reported_and_holdout_cohorts_recompute_in_normal_gate` | An operating valuation cohort exceeds its error threshold. |
| `write_input_blob_quarantines_directory` | A Windows write fails after moving a directory. |
| `write_input_blob_repairs_zero_and_flipped_then_restore_ok` | Windows denies directory synchronization. |

## Work In This Branch

| Area | State |
| --- | --- |
| Feed | Refresh uses the active universe. Vantage shows progress, coverage, and provider failures. |
| Warm start | Vantage restores rows, ticker Detail, and requested candles. Restored data stays outside ranking and alerts. |
| Ticker tools | Detail shows saved lots, Watchlist controls, and ticker notes. Feed membership edits persist per profile. |
| Markets | Separate Opportunities and Upside views show visible ordinals. Android Long V3 is opt-in. |
| Estimates | Index DCF stays hidden. Analyst range endpoints need coverage. Timed return requires a target date. |
| Portfolio | The shared session cache supplies ticker evidence across profiles. Portfolio requests data for held symbols. |
| Blaze | Linux and WSL checks are optional. Use Cargo for the native build and full suite. |

Use the [capability inventory](../product/android-vantage-parity.md) for the remaining Android differences.
Use the [parity architecture](../architecture/cross-platform-parity.md) for cache ownership and profile boundaries.
The branch carries application code and fixtures. Vantage stores local data in `%APPDATA%\com.discountscreener.windows\history.sqlite`.

## First Task For The Next Agent

Implement the profitability requirement for Vantage Long candidates. The product guide defines the rule and its scope.

- Reproduce JBIO with a fixture. Vantage showed `Unprofitable` in Detail and ranked the company `#1` with `BUY +40`.
- The Long V3 projection derives its decision and setup from the composite score. Start in [`commands.rs`](../../apps/windows/src-tauri/src/commands.rs).
- The quote parser can collapse missing EPS into `profitable = false`. Inspect [`quote_summary.rs`](../../apps/windows/src-tauri/src/quote_summary.rs).
- Resolve eligibility before publishing positive Long decisions or rankings. Keep the underlying evidence visible for research.
- Test a loss-making company, a company with missing profitability evidence, and an eligible profitable control.
- Show distinct states for losses and missing evidence. Test existing snapshots when the evidence format changes.
- Use the existing rules for Short, ETFs, and crypto.
- Verify the Opportunities list and dashboard picks. Check shared consumers in Advisor, alerts, and Headless.
- Use recorded inputs and browser mocks. Compare results with Android and update current product documentation.

The profitability change is pending. The observed JBIO result is a regression case for that work.
