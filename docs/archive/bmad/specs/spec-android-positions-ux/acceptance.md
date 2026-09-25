# Android Positions UX acceptance

Status: Complete for the authorized offline scope. Phone checks remain not run.

## Scope and ownership

The parent set the design, architecture, boundaries, and documentation.
Luna implemented the Core, presenter, header, and Positions UI in separate waves.
The review compares files with the saved pre-build workspace, including earlier uncommitted work.
It excludes unrelated changes that existed before this task.

## Automated checks

| Check | Result |
| --- | --- |
| Core exposure and research | 26 focused tests passed after the review patches |
| Positions presenter and ViewModel | 150 focused tests passed after the review patches |
| Header gestures and state | 23 focused tests passed, including a short viewport with expanded search |
| Existing Back navigation | 4 focused tests passed |
| Positions and Detail UI | 98 focused tests passed, including native text metrics, route returns, and import |
| Full Core suite | 1635 total: 1619 passed, 16 skipped, no failures or errors |
| Full app suite | 1288 total: 1285 passed, 3 skipped, no failures or errors |
| Acceptance cases | All 59 separate tests passed; see case-checks.md |
| Repository validation script | Passed; it reused the successful final task outputs |
| Final code review | All findings closed after independent patch readback |
| Debug APK identity | Correct package and debug signer; signer matches the pre-build artifact |
| Mutation tests | Not run |

The final command used one `--rerun` after each test task.
It ran `:core:test --rerun :app:testDebugUnitTest --rerun :app:assembleDebug` successfully.
The report counts come from the final XML files. The validation script did not rerun those tests.

## Artifact and workspace

APK: `apps/android/app/build/outputs/apk/debug/app-debug.apk`.
Package: `com.discountscreener.android`.
APK SHA-256: `36df9a5de4b0eb1c0f68bd233472c55e33f3e301e4df6aeb39829de065b9f65b`.
Signer SHA-256: `3695298fc8cd2070a58e9dbc0b52dd06d0272146ae86c6a9b2bef71f4d33efde`.
The signer check compares local artifacts. It makes no claim about the phone's installed certificate.

No commit or push ran. Existing uncommitted files contain dependencies shared with this change.
The task diff uses the saved workspace baseline, so the review excludes earlier work.

## Phone checks

No phone or emulator session ran. No install, provider refresh, or data reset ran.

| ID | Use case | Precondition | Steps | Expected | Actual | Status |
| --- | --- | --- | --- | --- | --- | --- |
| UC-1 | Read the stock book | A compatible debug app holds an imported book | 1. Open Positions. 2. Read the summary and rows. | Values, weights, coverage, and research labels remain readable. | No device session. | Not run |
| UC-2 | Recover the header | A long Positions list is open | 1. Scroll down. 2. Scroll up away from the top. | The header hides and returns without a list reset. | No device session. | Not run |
| UC-3 | Inspect position facts | The book includes scored and off-feed lots | 1. Open local facts. 2. Open a scored Detail. | Local facts work without provider work. Detail retains every matching lot. | No device session. | Not run |
| UC-4 | Import another book | Positions contains existing lots | 1. Open the menu. 2. Select Import book. | The existing file selection and confirmation flow opens. | No device session. | Not run |
| UC-5 | Use large text | The phone uses larger text or touch exploration | 1. Open Positions. 2. Read rows. 3. Use search and Back. | Facts remain readable. Search exits. Touch exploration keeps the header visible. | No device session. | Not run |

Phone totals: 0 Pass, 0 Fail, 5 Not run.
Not run: Read the stock book; Recover the header; Inspect position facts; Import another book; Use large text.
