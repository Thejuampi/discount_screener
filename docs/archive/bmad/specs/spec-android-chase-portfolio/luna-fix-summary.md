# Luna fix handoff

Date: 2026-09-06. All implementation workers used `gpt-5.6-luna` with effort `max`.

## Result

The workers completed the source fixes below and built the debug APK.
Device acceptance remains open. This handoff does not claim a measured live Russell speed improvement.

| Review item | Status |
| --- | --- |
| R1: premature market requests | Fixed; profile scheduling tests pass |
| R2: expired calendar cache | Fixed; the calendar owner applies freshness |
| R3: old dates hide future dates | Fixed; tests use an explicit New York session day |
| R4: small positions disappear | Fixed on Android and Windows |
| R5: CSV blocks the UI | Fixed with bounded IO reads, cancellation, and read state |
| R6: incomplete import warning | Fixed with counts, exclusions, parse failures, and removals |
| R7: full candle retention | Fixed with isolated disk stages and cleanup |
| R8: sustained request-rate policy | Pending; the profile report defines the next experiment |
| R9: typed load and provider-wait status | Pending; requires a defined UI and state contract |
| R10: book facts lack labels | Shares and Average cost labels fixed; broader compact-row design remains pending |

Final review caught three new spool defects. The profile worker reproduced and fixed each defect.
These defects concerned creation failure, truncated data, and concurrent session cleanup.
The spool validates all chunks before sink writes. Sink commits remain per chunk.

## Evidence

| Check | Result |
| --- | --- |
| Focused Android import core | 37 passed |
| Focused Android import app | 40 passed |
| Windows CSV | 29 passed |
| Calendar | Recorder, presenter, and ViewModel checks passed |
| Full Android core | 1,609 tests; zero failures/errors; 16 skipped |
| Full Android app | Passed before the final R7 review fixes |
| Post-review market suite | 30 passed |
| Post-review profile scheduling | 2 passed |
| Post-review repository suite | Full DefaultDashboardRepositoryTest passed |
| Post-review market status and rank | Passed |
| Post-R7 debug APK | Build passed |

Workers used one named mutex to serialize Gradle.
The final R7 changes received affected-suite checks. The workers did not repeat unrelated full suites solely for counts.

APK: `apps/android/app/build/outputs/apk/debug/app-debug.apk`.
Version name: `2026.09.06.0832.feat-earnings-gate-identities.db23aa88-dirty`.
Version code: `20260906`.

No worker installed this APK or ran live profile QA.

## Reports

- [Book import](luna-book-import-result.md)
- [Calendar](luna-calendar-result.md)
- [Profile load](luna-profile-load-result.md)
- [Ownership](luna-fix-ownership.md)

## Remaining acceptance

Run device acceptance under profile QA when the product is ready.
Do not treat the existing screenshots as evidence for this new APK.
Measure sustained-rate changes separately for S&P 500 and Russell through the offline experiment before live tests.
Keep the current S&P 500 budget and shared provider holds.

## Release check follow-up

Juan authorizes device QA and release artifact preparation after this handoff.
The parent owns design, acceptance criteria, and the release decision. Luna implements and checks defined work.
The first full app run reports a timeout. Focused passes do not explain its cause.
Parent source review finds an unbalanced load count if cancellation precedes refresh pass registration.
The parent defines the fix and deterministic cancellation cases in the spike memlog.
Release acceptance remains open until the worker returns regression and final-artifact evidence.
The timeout and source defect may share a cause. Current evidence does not establish that link.
