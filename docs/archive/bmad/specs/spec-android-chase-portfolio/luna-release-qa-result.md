# Luna release QA result

Date: 2026-09-06. Worker: `luna_release`. Model: `gpt-5.6-luna`, effort `max`.

The parent owns the production verdict. This report records test and device evidence.

## Scope

The checks use the Android `qa` profile and keep the existing application data.
The session uses offline fixture files for book and trade imports.
The session makes no live S&P 500 or Russell request.
The session does not use `pm clear` or delete application data.

The QA debug APK builds, installs, starts with the QA profile, and keeps the restored book.
The QA artifact receives a launch, Positions, scroll, and import preview and cancel check.
The normal debug APK also builds without the QA flag.
Earlier QA checks cover import confirm, Chase merge, Detail navigation, and refusal paths.

## Build and install identity

| Item | Evidence |
| --- | --- |
| QA build command | `apps/android/gradlew.bat :app:assembleDebug -PdsQaUniverse=true --rerun --console=plain` |
| QA build log | `.agents/workspace/tmp/android-run/assemble-debug-qa-20260906-final.log` |
| QA exported APK | `dist/discount-screener-debug-qa-2026.09.06.0832.feat-earnings-gate-identities.db23aa88-dirty.apk` |
| QA size | 14,284,260 bytes |
| QA SHA-256 | `CABFD604DD29468E008B3C5738F9AF9E981B38BD095B373B6323AD4E782ABBA0` |
| QA BuildConfig | `.agents/workspace/tmp/android-run/BuildConfig-qa-final.txt`; `QA_UNIVERSE=true` |
| Normal build command | `apps/android/gradlew.bat :app:assembleDebug --rerun --console=plain` |
| Normal build log | `.agents/workspace/tmp/android-run/assemble-debug-normal-20260906-final.log` |
| Normal exported APK | `dist/discount-screener-debug-2026.09.06.0832.feat-earnings-gate-identities.db23aa88-dirty.apk` |
| Normal size | 14,283,856 bytes |
| Normal SHA-256 | `C58C5A3869C671CBD0F245BFB0F8437C5F76FC236C915F30A59940F10F6AB397` |
| Normal BuildConfig | `.agents/workspace/tmp/android-run/BuildConfig-normal-final.txt`; `QA_UNIVERSE=false` |
| Version | Both builds use `versionName=2026.09.06.0832.feat-earnings-gate-identities.db23aa88-dirty`; `versionCode=20260906` |
| Package | Both builds use `com.discountscreener.android` |
| Certificate | Both use the Android debug certificate; SHA-256 `3695298fc8cd2070a58e9dbc0b52dd06d0272146ae86c6a9b2bef71f4d33efde` |
| Signature check | Both pass APK Signature Scheme v2; v1, v3, and v4 are false |
| Install | The QA APK install with `adb -s emulator-5554 install -r` returned `Success` |
| Install log | `.agents/workspace/tmp/android-run/install-debug-qa-final-20260906.log` |

The QA artifact uses `-PdsQaUniverse=true`. The screen shows the `QA` chip.
The normal debug artifact uses no `dsQaUniverse` property. The captured BuildConfig shows `QA_UNIVERSE=false`.
The normal debug artifact was not installed or run on a device.
The earlier UI import and Chase checks used the same version name with SHA-256 `81222D961448433B8372B1A14AC044FBA76B8BE2F0E343A2FA42668CF91B8742`.
The final QA install rechecked launch, restored Positions, scroll, and preview and cancel.

The user made custom signed releases optional during this session.
A temporary AVD named `discount_screener_release_smoke_api35` was created and booted as `emulator-5556`.
The AVD used a separate data directory and port.
I confirmed its name, then stopped it.
I did not install an APK on that AVD.
The existing `discount_screener_api35` AVD and its data remain intact.

## Automated checks

| Check | Result | Evidence |
| --- | --- | --- |
| Android core suite | Pass: 1,609 tests, 0 failures, 0 errors, 16 skips | `.agents/workspace/tmp/android-run/core-test-20260906-final.log` |
| Refresh cancellation before pass registration, old code | Red: `TimeoutCancellationException` | `.agents/workspace/tmp/android-run/refresh-before-registration-red-20260906-v10.log`; XML `.agents/workspace/tmp/android-run/refresh-before-pass-registration-red-20260906.xml` |
| Refresh cancellation after pass registration, fixed code | Pass | `.agents/workspace/tmp/android-run/refresh-after-registration-debug-20260906.log` records an earlier failed setup; final result is in the focused green log |
| Refresh replacement and cancellation cases, fixed code | Pass: 3 tests | `.agents/workspace/tmp/android-run/refresh-focused-green-20260906-v3.log`; XML `.agents/workspace/tmp/android-run/refresh-focused-green-20260906.xml` |
| Android app suite after refresh fix | Pass: 1,216 tests, 0 failures, 0 errors, 3 skips | `.agents/workspace/tmp/android-run/app-test-debug-full-20260906-after-refresh-fix.log`; XML directory `.agents/workspace/tmp/android-run/full-test-results-after-refresh-fix-20260906-v1` |
| `scripts/validate-android.ps1` | Pass | `.agents/workspace/tmp/android-run/validate-android-20260906-after-refresh-fix.log` |
| Final QA debug assembly | Pass | `.agents/workspace/tmp/android-run/assemble-debug-qa-20260906-final.log` |

The old-code red test waited after the `firstStarted` gate.
The XML points to `RefreshButtonReplacesRefreshTest.kt:124`, which waits for `loadInFlight` to become false.
The old code left that flow true after cancellation before pass registration.
The focused fixed suite waits on the flow and checks zero active passes after cleanup.
The later refresh completes and records a peak of one active pass.

The first full app command received a timeout from the outer test operation.
Its preserved log is `.agents/workspace/tmp/android-run/app-test-debug-full-20260906.log`.
The log later shows the replacement test passing and `BUILD SUCCESSFUL`.
The pre-fix full-suite XML was overwritten before it was copied.
The available evidence does not identify a test watchdog, Gradle failure, or emulator failure.
The timeout source remains unresolved.
The deterministic red and green refresh runs are separate evidence.

A separate refresh probe showed `loadInFlight=false`, then `true`, while `peekRefreshPassesRunning=0`.
The change appeared when `finishRefresh` started enrichment after the refresh pass finished.
This signal remains for parent triage.
The worker does not label it harmless or fixed.

## Device

| Item | Evidence |
| --- | --- |
| Device | `emulator-5554`, AVD `discount_screener_api35`, Android API 35, Android 15 |
| QA profile | `apps/android/app/src/main/assets/profiles/qa.txt`, 20 symbols |
| Final launch XML | `.agents/workspace/tmp/android-run/luna-release-final.xml` |
| Final Positions XML | `.agents/workspace/tmp/android-run/luna-release-final-positions-selected.xml` |
| Final Positions screenshot | `.agents/workspace/tmp/android-run/luna-release-final-positions-selected.png` |
| Final scroll XML | `.agents/workspace/tmp/android-run/luna-release-final-positions-scroll.xml` |
| Final scroll screenshot | `.agents/workspace/tmp/android-run/luna-release-final-positions-scroll.png` |
| Final import plan XML | `.agents/workspace/tmp/android-run/luna-release-final-import-plan.xml` |
| Final import plan screenshot | `.agents/workspace/tmp/android-run/luna-release-final-import-plan.png` |
| Final import cancel XML | `.agents/workspace/tmp/android-run/luna-release-final-import-cancelled.xml` |

The retained database backup is under `.agents/workspace/tmp/android-run/db-backup-before-qa/`.
A read-only query returned 84 `portfolio_lot` rows and `ds_advisor_book_as_of=2026-09-06`.
The final Positions screen shows `Positions 84` after the final debug APK install.

The controlled fixtures are:

- `.agents/workspace/tmp/android-run/qa-jpm-positions.csv`
- `.agents/workspace/tmp/android-run/qa-chase-transactions.csv`
- `.agents/workspace/tmp/android-run/qa-ledger.csv`

## Use-case scenarios

### UC-1 — Launch the final QA debug app

- **Precondition:** The final QA debug APK is built. The emulator has the restored database.
- **Steps:** 1. Install the APK with `adb install -r`. 2. Force-stop the app. 3. Start `MainActivity`.
- **Expected:** The app reaches the screen and shows the QA profile.
- **Actual:** Install returns `Success`. The app reaches `MainActivity` and shows `QA`.
- **Status:** Pass

### UC-2 — Display the retained Positions book

- **Precondition:** The QA app runs after the final debug install.
- **Steps:** 1. Swipe the tab row to Positions. 2. Select `Positions 84`.
- **Expected:** The screen shows all retained lots with share and average cost labels.
- **Actual:** The screen shows `Positions 84`, `AMZN` with 36.2954 shares and average cost `$214.03`.
- **Status:** Pass

### UC-2a — Scroll a larger Positions book

- **Precondition:** Positions shows the restored 84 lots.
- **Steps:** 1. Swipe the Positions list upward. 2. Inspect rows below the first viewport.
- **Expected:** Lower rows remain readable and the tab count stays 84.
- **Actual:** Rows `BR`, `BSX`, `CAT`, `CBRE`, `CI`, `CMCL`, `COF`, `COR`, and `CRGY` render. `Positions 84` stays visible.
- **Status:** Pass

### UC-3 — Review a snapshot import

- **Precondition:** Positions shows the restored book. The JPM fixture is in Downloads.
- **Steps:** 1. Tap `Import book`. 2. Select `qa-jpm-positions.csv`. 3. Read the plan.
- **Expected:** The plan shows lots, removals, as-of, and ignored row counts before write.
- **Actual:** The plan shows 2 lots, 82 removals, as-of `2026-08-31`, and 4 ignored rows.
- **Status:** Pass

### UC-3a — Cancel a snapshot import

- **Precondition:** The JPM snapshot plan is open.
- **Steps:** 1. Tap `Cancel`. 2. Return to Positions.
- **Expected:** The existing book remains unchanged.
- **Actual:** Positions remains at 84 lots.
- **Status:** Pass

### UC-3b — Confirm a snapshot import

- **Precondition:** The JPM snapshot plan is open.
- **Steps:** 1. Tap `Confirm`. 2. Return to Positions. 3. Inspect AMZN and PHYL.
- **Expected:** The book contains the two fixture lots with the displayed shares and costs.
- **Actual:** Positions shows 2 lots. AMZN shows 36.2954 shares at `$214.03`. PHYL shows 1,273 shares at `$35.28`.
- **Status:** Pass

### UC-4 — Tap an off-feed lot

- **Precondition:** The snapshot book shows PHYL, which has no scored feed row.
- **Steps:** 1. Tap PHYL. 2. Inspect the screen and book count.
- **Expected:** The tap does nothing and does not add a feed symbol.
- **Actual:** No Detail screen opens. Positions remains at 2 lots.
- **Status:** Pass

### UC-5 — Open scored Detail and return

- **Precondition:** The snapshot book shows scored AMZN.
- **Steps:** 1. Tap AMZN. 2. Inspect Detail. 3. Tap `Back`.
- **Expected:** Scored Detail opens and Back returns to Positions.
- **Actual:** Detail shows AMZN and `Score 31`. Back returns to Positions.
- **Status:** Pass

### UC-6 — Review a Chase trade import

- **Precondition:** The snapshot book shows AMZN and PHYL. The Chase fixture is in Downloads.
- **Steps:** 1. Open the import action. 2. Select `qa-chase-transactions.csv`. 3. Read the plan.
- **Expected:** The plan shows applied, skipped, ignored, closed, and next as-of values.
- **Actual:** The plan shows 2 applied, 1 skipped, 2 ignored, no closed lots, and next as-of `2026-09-01`.
- **Status:** Pass

### UC-6a — Cancel a Chase import

- **Precondition:** The Chase trade plan is open.
- **Steps:** 1. Tap `Cancel`. 2. Inspect Positions.
- **Expected:** The current book remains unchanged.
- **Actual:** AMZN remains 36.2954 shares and PHYL remains 1,273 shares.
- **Status:** Pass

### UC-6b — Confirm a Chase import

- **Precondition:** The Chase trade plan is open.
- **Steps:** 1. Tap `Confirm`. 2. Inspect Positions.
- **Expected:** The trade window changes the supported lots and keeps the next as-of.
- **Actual:** AMZN becomes 37.2954 shares at `$214.19`. PHYL becomes 1,173 shares.
- **Status:** Pass

### UC-7 — Refuse an unsupported ledger import

- **Precondition:** The ledger fixture is in Downloads.
- **Steps:** 1. Select `qa-ledger.csv`. 2. Read the import result.
- **Expected:** The app refuses the unsupported ledger format with a reason.
- **Actual:** The screen shows `Import refused` and `ledger_apply_unsupported`.
- **Status:** Pass

### UC-8 — Navigate to the Earnings tab

- **Precondition:** The QA app runs with the restored book.
- **Steps:** 1. Swipe the tab row. 2. Tap `Earnings`. 3. Inspect the list.
- **Expected:** The Earnings tab opens and paints its current entries.
- **Actual:** The tab opens and existing entries render. The imported AMZN does not create a new visible event.
- **Status:** Pass

### UC-9 — Show a Later report date

- **Precondition:** A scored AMZN row has future earnings evidence.
- **Steps:** 1. Open AMZN Detail. 2. Read the earnings timing and date.
- **Expected:** The row shows the correct future closeness label and date.
- **Actual:** Detail shows `Earnings in 53 days` and `Later · 2026-10-29`.
- **Status:** Pass

### UC-10 — Restore the original book

- **Precondition:** The controlled snapshot and Chase checks have completed.
- **Steps:** 1. Force-stop the app. 2. Restore the saved SQLite files. 3. Relaunch the app. 4. Open Positions.
- **Expected:** The original 84 lots and original as-of return.
- **Actual:** Positions shows 84 lots, including NVDA, AMZN, and GOOGL. The backup query shows as-of `2026-09-06`.
- **Status:** Pass

### UC-11 — Keep the original book after the final debug install

- **Precondition:** The original book is restored. The final debug APK is available.
- **Steps:** 1. Install the final APK with `-r`. 2. Force-stop and relaunch the app. 3. Open Positions.
- **Expected:** Install and restart preserve the database and QA profile.
- **Actual:** Install returns `Success`. The final XML shows `QA`, `Positions 84`, and the restored rows.
- **Status:** Pass

### UC-12 — Run the custom signed release smoke

- **Precondition:** The user makes custom signed releases optional.
- **Steps:** 1. Do not install the custom signed APK. 2. Stop the temporary release AVD.
- **Expected:** The signed APK starts and passes the defined smoke.
- **Actual:** The temporary AVD was created and booted, then stopped. No signed APK was installed there.
- **Status:** Not run

### UC-13 — Exercise every device date category

- **Precondition:** The device session has no controlled fixture for each date category.
- **Steps:** 1. Do not change the device clock. 2. Do not fabricate provider dates.
- **Expected:** Today, Tomorrow, and This week each receive a device path.
- **Actual:** The session only exercises Later. Automated date tests cover the other categories.
- **Status:** Not run

### UC-14 — Measure the R8 sustained request rate

- **Precondition:** R8 requires an offline profile experiment before live load.
- **Steps:** 1. Do not load S&P 500 or Russell live data. 2. Record the experiment as pending.
- **Expected:** The experiment measures calls, rows per second, holds, and disk time.
- **Actual:** No R8 experiment runs in this session.
- **Status:** Not run

### UC-15 — Exercise R9 typed load and wait status

- **Precondition:** R9 remains a pending requirement.
- **Steps:** 1. Do not invent a new status contract. 2. Record the device path as pending.
- **Expected:** The UI shows the defined typed load and provider wait states.
- **Actual:** No R9 device path runs.
- **Status:** Not run

### UC-16 — Load a non-QA live profile

- **Precondition:** The QA rule allows the `qa` profile only.
- **Steps:** 1. Keep the app on `qa`. 2. Avoid S&P 500 and Russell live loads.
- **Expected:** The session does not exceed the authorized universe.
- **Actual:** No non-QA live profile load runs.
- **Status:** Not run

### UC-17 — Cancel a device file read

- **Precondition:** The session has no separate large or blocked device document provider.
- **Steps:** 1. Do not create a new device reader fixture. 2. Use the automated reader tests instead.
- **Expected:** A device cancel action stops a blocked reader.
- **Actual:** The device path does not run. Automated app tests cover bounded read cancellation.
- **Status:** Not run

## Totals

| Status | Count |
| --- | ---: |
| Pass | 16 |
| Fail | 0 |
| Not run | 6 |

Not run use cases:

- UC-12 — Run the custom signed release smoke
- UC-13 — Exercise every device date category
- UC-14 — Measure the R8 sustained request rate
- UC-15 — Exercise R9 typed load and wait status
- UC-16 — Load a non-QA live profile
- UC-17 — Cancel a device file read

## Handoff

The QA debug APK and normal debug APK have factual build evidence.
The QA debug APK has the defined device evidence.
The parent must decide production readiness against the approved scope.
R8 and R9 remain pending requirements.
The first full-suite timeout source remains unresolved.
The custom signed release path is optional and does not block this debug sideload report.
