# Windows Feed Refresh Validation

The toolbar Refresh button wakes the same feed coordinator that runs the scheduled pass.
The pass requests a quote and one year of daily candles for each symbol in the active universe.
The coordinator combines requests that arrive while a pass is queued or active.
The pass keeps available rows and their coverage visible.
A profile switch invalidates requests and results from the previous profile.

The status bar shows current phase and processed symbols separately from available rows.
Quote and chart issues stay visible until a later request returns usable data.
The status bar shows symbols affected by the Yahoo rate limit.
Incomplete quote data can include missing fundamentals.
System errors stay separate from pass issues.

## Detail And Advisor Baseline

Commit `95de0e36` fixes the baseline for Windows ticker Detail and Advisor coverage.
The baseline opens Detail from session data and keeps it visible when a ticker request fails.
Advisor measures quote coverage and paired P/L coverage separately.
It withholds weights when the portfolio value is incomplete.

| Offline check | Baseline result | Feed refresh result | Detail restore result | Row and candle restore result |
| --- | --- | --- | --- | --- |
| Frontend unit suite | 195 passed. | 199 passed. | 204 passed. | 212 passed. |
| Browser suite with simulated providers | 13 passed. | 16 passed. | 19 passed. | 21 passed. |
| Rust suite | 709 passed. Three known tests failed. | 717 passed. The same three tests failed. | 725 passed. The same three tests failed. | 735 passed. The same three tests failed. |

The Rust run excludes the live high-signal recomputation and the export that needs local Random20 inputs.
The three failures reproduce before the feed changes.
All ten new Rust tests for row and candle restore passed.
The frontend and native Windows builds passed.

| Test | Baseline failure |
| --- | --- |
| `durable_reported_and_holdout_cohorts_recompute_in_normal_gate` | The reported operating valuation cohort exceeds its error threshold. |
| `write_input_blob_quarantines_directory` | The Windows test fails when it writes a blob after moving a directory aside. |
| `write_input_blob_repairs_zero_and_flipped_then_restore_ok` | The Windows test cannot open the input directory for synchronization. |

## Offline Checks

From `apps/windows`, run `npm test`.
Set `CHROMEDRIVER_PATH` to a local ChromeDriver that matches Chrome, then run `npm run test:e2e`.
Browser tests simulate Tauri commands and use a local test server.

Run this command from `apps/windows/src-tauri`.

```text
cargo test --offline -- --skip high_signal_screener_cohort_all_members_pass --skip export_random20_sp500_parity_snapshot --skip ensure_symbol_loaded_does_not_grow_active_symbols --skip reapply_same_symbol_set_is_idempotent --skip rank_input_bumps_on_profile_switch_clear_universe
```

The command skips three older tests that can start provider work.
Compare the results with the baseline. Report each failure separately.
Use the [Windows live QA guide](valuation-live-qa.md) for any requested live run.

## Detail Restore Checks

The Windows cache stores versioned Detail snapshots by universe profile and ticker.
It retains up to 128 snapshots across all profiles.
It saves available quote, consensus, fundamentals, valuation evidence, and chart summaries.
The displayed time records when Vantage saved the snapshot.

Open a previously saved ticker by exact symbol in the same profile after restart.
Verify that Detail shows the saved price, evidence, and restored marker before a session quote arrives.
Verify that a different profile cannot read the saved snapshot.
Verify that ranking, alerts, and Quant Lens receive inputs only from session state.
Verify that a failed or chart-only refresh retains the saved quote and marker.
Verify that a usable session quote replaces the restored snapshot.

## Universe Row And Candle Restore Checks

The Screener restores rows for the current membership of the active profile.
Vantage saves rows after the feed accepts usable session quotes.
The cache keeps up to 4,096 rows per profile.
Each row stores the symbol, company, asset type, quote, available target, and save time.

Close and reopen with saved rows and an empty session feed.
Verify that the Screener shows the saved group with its save times and an empty ranking.
Verify that the ranking, Advisor, and alerts read session state only.
Verify that a session quote replaces its matching row. Other saved rows must stay visible.
Verify that a failed quote request preserves the prior saved row.
Verify that a profile switch excludes rows from the prior profile and rows outside current membership.

Vantage saves session candles for chart ranges used in Detail.
The cache keeps up to 128 series across all profiles, with up to 2,048 candles and 256 KiB per series.
The chart reads saved candles only when it needs the selected profile, ticker, and range.
Verify that a saved series shows its restored marker and save time without a provider request.
Verify that a matching session series replaces the restored candles even while the quote remains restored.
Verify that failed requests keep the saved series and that native chart signals use session data only.

## Feed Membership Checks

Add a ticker and remove a catalog ticker from Feed tickers in Markets.
Verify that the coordinator uses the new membership and ignores results from the previous generation.
Reopen the database. Verify that the additions and exclusions apply only to the edited universe.
Verify that a removed ticker stays outside ranking and alerts after a one-time Detail request.
Verify that QA and launch-locked profiles reject membership writes.
Simulate a failed write. Verify that the feed keeps its previous membership.
