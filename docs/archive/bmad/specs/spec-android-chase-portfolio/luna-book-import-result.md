# Luna book import result

Date: 2026-09-06. Worker: `luna_book_import`. Model: `gpt-5.6-luna`, effort `max`.

## Scope

This report covers review findings R4, R5, R6, and the field-label part of R10.

The first core quantity edits came from the interrupted worker. I preserved them and completed their tests.

The Windows parity fix owns the same projection filters and fixed-point boundary. Other Windows behavior stays unchanged.

## Fixed behavior

- Positive lots stay in both snapshot and unchanged trade-window projections.
- Quantity rounds to four decimals before Windows emits a lot. Rounded zero quantities are dropped.
- Android stores quantity as integer ten-thousandths. Both clients drop costs that round to zero cents.
- Android reads SAF input on `Dispatchers.IO`.
- Android stops input above four MiB before output growth.
- Android checks cancellation before opening and between reads.
- `runInterruptible` interrupts a blocked provider read after cancellation.
- The UI shows `Reading book…`, a `Cancel read` action, and read errors.
- A canceled or replaced read cannot clear the state of a newer read.
- Snapshot warnings show lot count, removals, omitted symbols, and ignored-row categories.
- Trade warnings show applied, skipped, ignored, parse-failure, removal, and next-as-of values.
- Positions labels now show `Shares` and `Average cost`.
- Existing signal strips, off-feed rows, and off-feed taps keep their behavior.

## Files

- `apps/android/core/src/main/kotlin/com/discountscreener/core/portfolio/AdvisorCsv.kt`
- `apps/android/core/src/test/kotlin/com/discountscreener/core/portfolio/AdvisorCsvTest.kt`
- `apps/android/app/src/main/kotlin/com/discountscreener/android/data/portfolio/BookCsvInput.kt`
- `apps/android/app/src/main/kotlin/com/discountscreener/android/ui/dashboard/ImportBook.kt`
- `apps/android/app/src/main/kotlin/com/discountscreener/android/ui/dashboard/DashboardLists.kt`
- `apps/android/app/src/main/kotlin/com/discountscreener/android/presentation/dashboard/HeldRowPresentation.kt`
- `apps/android/app/src/test/kotlin/com/discountscreener/android/ui/dashboard/BookCsvInputTest.kt`
- `apps/android/app/src/test/kotlin/com/discountscreener/android/presentation/dashboard/HeldRowPresentationTest.kt`
- `apps/android/app/src/test/kotlin/com/discountscreener/android/ui/dashboard/ImportBookScreenTest.kt`
- `apps/android/app/src/test/kotlin/com/discountscreener/android/ui/dashboard/PositionsScreenTest.kt`
- `apps/windows/src/portfolioCsv.ts`
- `apps/windows/tests/portfolioCsv.test.ts`
- `_bmad-output/planning-artifacts/prds/prd-android-chase-portfolio-2026-09-05/prd.md`
- `_bmad-output/specs/spec-android-chase-portfolio/SPEC.md`
- `_bmad-output/specs/spec-android-chase-portfolio/examples.md`
- `docs/advisor-csv-import.md`
- `apps/android/README.md`
- `docs/cross-platform-parity.md`

## Test evidence

Initial parity and lifecycle red evidence came from parent execution. Precision red evidence came from this worker.

| Check | Result | Evidence |
| --- | --- | --- |
| Windows initial parity run | Red | 28 tests ran. 26 passed. One test exposed zero quantity. One test had an incorrect opened-at expectation. |
| Windows zero-scale case | Red | The old code emitted `quantity: 0` after rounding. |
| Windows unchanged-window case | Red | The initial test expected null. The existing earliest-buy rule correctly returned `2026-08-31`. |
| Windows fixed-point cost case | Red | The old code emitted a positive lot with `avg_cost_cents: 0`. |
| Android fixed-point cost case | Red | The old code emitted a positive lot with `avgCostCents: 0`. |
| Android core import suite | Green | 37 `AdvisorCsvTest` cases passed. |
| Android import suite before lifecycle fixes | Red | 40 tests ran. 38 passed. Blocked read and stale generation cases failed. |
| Android read and UI suites after lifecycle fixes | Green | 40 tests passed under the shared Gradle mutex. |
| Windows parity suite after fixes | Green | 29 tests passed. |

Final Android validation ran after the profile worker released its files.

| Final check | Result | Evidence |
| --- | --- | --- |
| Full Android core suite | Green | `:core:test --rerun` succeeded. Result XML reports 1,609 tests, 0 failures, 0 errors, and 16 skips. |
| Full Android app suite | Green | `:app:testDebugUnitTest --rerun` succeeded. A later sibling run replaced the XML report, so the full count is unavailable. |
| Android debug assembly | Green | `:app:assembleDebug` succeeded. |

Every Gradle command used the named `Local\DiscountScreenerLunaGradle` mutex.

These full checks predate the profile worker's reopened R7 edits. They do not validate those later edits.

Post-R7 validation also rebuilt the debug APK after the profile worker's release.

| Post-R7 check | Result | Evidence |
| --- | --- | --- |
| Profile worker affected suites | Green | 30 `MarketDataRepositoryTest` cases, 2 `ProfileLoadSchedulingTest` cases, and full `DefaultDashboardRepositoryTest` passed. |
| Android debug assembly after R7 | Green | `:app:assembleDebug` succeeded in 4 seconds under the mutex. |
| Debug APK | Built | `G:\dev\repos\discount_screener\apps\android\app\build\outputs\apk\debug\app-debug.apk` |
| APK version | Recorded | `versionName=2026.09.06.0832.feat-earnings-gate-identities.db23aa88-dirty`; `versionCode=20260906`. |

The unchanged-window test now expects the existing earliest-buy `opened_at` rule.

These checks ran from the stated directories.

```text
apps/android\gradlew.bat :app:testDebugUnitTest --tests '*BookCsvInputTest' --tests '*HeldRowPresentationTest' --tests '*ImportBookScreenTest' --tests '*PositionsScreenTest' --rerun
apps/android\gradlew.bat :core:test --tests '*AdvisorCsvTest' --rerun
apps/android\gradlew.bat :core:test --rerun
apps/android\gradlew.bat :app:testDebugUnitTest --rerun
apps/android\gradlew.bat :app:assembleDebug
apps/windows\node --test tests/portfolioCsv.test.ts
```

The Gradle test tasks ran after `--rerun`. Supporting tasks marked `UP-TO-DATE` did not replace the test task.

## Contract note

The shared contract stays `advisor-csv-import/3`.

The cost-basis floor violated existing positive-quantity semantics. Removing it needs no version change.

Android adds `expectedExclusions` and `parseFailures` metadata beside the existing total `ignored` count.

The metadata only improves warning detail. It does not change parse, skip, merge, or persistence decisions.

Windows keeps its existing parsed shape and total ignored count. A future cross-client category contract needs an explicit contract change.

## Documentation changes

These released documents now describe the implemented behavior.

- `_bmad-output/planning-artifacts/prds/prd-android-chase-portfolio-2026-09-05/prd.md`: records read state, safety limits, warnings, small lots, and labels.
- `_bmad-output/specs/spec-android-chase-portfolio/SPEC.md`: records the fixed-point rule, reader behavior, warning details, and triage evidence.
- `_bmad-output/specs/spec-android-chase-portfolio/examples.md`: maps each new outline Case to an automated test.
- `docs/advisor-csv-import.md`: records quantity scale, bounded reads, cancellation, errors, and warning counts.
- `apps/android/README.md`: records the Android reader and Positions labels.
- `docs/cross-platform-parity.md`: records the Android reader and warning metadata extension.

The parent owns the remaining `AGENTS.md`, `project-context.md`, and contract reconciliation.

The contract version stays `/3`. The small-position and fixed-point Cases add coverage without changing contract semantics.

## Gherkin cases

### Scenario Outline: Respect fixed point boundaries during import projection

```gherkin
Scenario Outline: Preserve a positive lot through import projection
  Given the importer receives the Case input
  When it projects the snapshot or trade window
  Then each emitted lot has supported quantity and positive cost cents

  Examples:
    | Case | Automated test |
    | JPM-SMALL-POSITION | Android `a_positive_snapshot_lot_below_one_dollar_stays_in_the_book`; Windows `a positive snapshot lot below one dollar stays in the book` |
    | MERGE-SMALL-UNCHANGED | Android `an_unchanged_trade_window_keeps_a_positive_lot_below_one_dollar`; Windows `an unchanged trade window keeps a positive lot below one dollar` |
    | ZERO-QUANTITY-SCALE | Windows `a quantity below the supported scale does not emit a zero quantity lot` |
    | SUBCENT-COST | Android `a_subcent_cost_does_not_emit_a_zero_cent_lot`; Windows `a sub-cent cost does not emit a zero-cent basis lot` |
  ```

### Scenario Outline: Read a broker file with bounded cancellation

```gherkin
Scenario Outline: Read a broker file with bounded cancellation
  Given the selected URI produces the Case stream
  When the reader runs
  Then it reports the bounded result without blocking the UI

  Examples:
    | Case | Automated test |
    | TOO-LARGE | `reader_rejects_input_above_the_bound` |
    | CANCEL-BEFORE-OPEN | `reader_honors_cancellation_before_open` |
    | CANCEL-BLOCKED-READ | `reader_cancels_a_blocked_document_provider_after_open` |
    | OFF-MAIN | `reader_runs_a_slow_document_provider_off_the_main_thread` |
  ```

### Scenario Outline: Review import changes before Confirm

```gherkin
Scenario Outline: Review import changes before Confirm
  Given the importer creates the Case plan
  When the dialog opens
  Then the warning shows counts and removals before Confirm

  Examples:
    | Case | Automated test |
    | TRADE-COUNTS | `trade_import_warning_shows_all_counts_and_removed_symbols` |
    | TRADE-DIALOG | `a_trade_plan_shows_counts_and_closed_symbols_before_confirm` |
  ```

### Scenario Outline: Label book facts

```gherkin
Scenario Outline: Label book facts
  Given Positions paints the Case lot
  When the row appears
  Then the row labels shares and average cost

  Examples:
    | Case | Automated test |
    | PHYL | `pos_phyl_paints_qty_and_cost` |
    | AMZN | `pos_amzn_paints_qty_and_cost` |
  ```

## Remaining work

- Parent must integrate the report with the shared memlog and specification artifacts.
- Parent must review the Android metadata extension against the shared contract.
- No source blocker remains in this scope.
- No live network, device, or profile test ran in this scope.
