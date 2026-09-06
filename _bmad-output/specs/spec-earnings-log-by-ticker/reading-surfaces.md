# Reading surfaces

Companion to `SPEC.md`. Holds the per-surface detail `SPEC.md` cites by name: what exists today, where each capability attaches, and the calls already made.

All paths are under `apps/android/app/src/main/kotlin/com/discountscreener/android`.

## What exists today

| Piece | Where | Shape |
|---|---|---|
| `presentEarningsGate` | `presentation/dashboard/EarningsGatePresentation.kt:60` | `(events, damagedLines, today, lastCaptureEpochSeconds, nowEpochSeconds) -> EarningsGateUi` |
| `EarningsGateUi` | same file, `:15` | `upcoming: List<EarningsEventRowUi>`, `settled: List<EarningsEventRowUi>`, `damagedLines: Int`, `lastCapture: String?`, derived `isEmpty` |
| `EarningsEventRowUi` | same file, `:24` | carries `symbol` plus 18 already-formatted strings |
| `EarningsGateScreen` | `ui/dashboard/EarningsGateScreen.kt:41` | `(state, loading, pendingBackup, notice, onAction)` |
| `EarningsEventCard` | same file, `:219` | one card per row; `internal`, so both screens draw it |
| `earningsMark` | `ui/dashboard/EarningsMarkUi.kt:40` | the report date as one sentence; null once the date has passed |
| `earningsGateAbsence` | same file, `:64` | why a ticker carries no event, in one line |
| `loadEarningsGate()` | `presentation/dashboard/DashboardViewModel.kt:547` | cancels the prior job, flips `earningsGateLoading`, assigns `earningsGate` |
| tab laziness | `DashboardViewModel.kt:542` | `if (tab == DashboardTab.Earnings) loadEarningsGate()` |
| `DetailRoute` | `DashboardViewModel.kt:138` | carries `symbol` and `subtab: DetailSubtab` |
| `DetailSubtab` | `DashboardViewModel.kt:113` | `Snapshot, Score, Lens, History` |
| `DetailScreen` | `ui/dashboard/DetailScreen.kt:133` | ~16 flattened params, no single state object |
| local-state text field pattern | `ui/dashboard/SymbolNoteField`, `DetailScreen.kt:479` | `OutlinedTextField` with a local `draft`, no ViewModel state |
| `TickerSearchBar` | `ui/dashboard/TickerSearchBar.kt:29` | symbol lookup with network-backed suggestions |

`EarningsGateUi` holds two flat global lists with no per-symbol grouping. `EarningsEventRowUi.symbol` is present, so both capabilities filter the already-presented rows.

## CAP-1 to CAP-3 — the search field on the Earnings tab

The field sits above the list, inside `EarningsGateScreen`, and holds its query in local composable state — the `SymbolNoteField` pattern. `TickerSearchBar` fetches suggestions over the network; wiring it here would break CAP-6. Local state also settles the "resets to empty on restart" assumption in `SPEC.md` for free, and keeps `loadEarningsGate()` from firing on every keystroke.

Filtering applies to `state.upcoming` and `state.settled`. It does not touch `damagedLines` or `lastCapture`, which describe the whole read and render unconditionally (CAP-3). This is why the filter cannot be pushed down into `presentEarningsGate` or the repository: a filtered read would produce a filtered damaged-line count, and a damaged line for another symbol would go unreported.

Three states the screen must tell apart, because two of them look identical if the code is careless:

1. **Log empty and intact.** The existing fresh-install empty state, with the back-up and restore buttons.
2. **Log empty of readable events but holding damaged lines.** The list, carrying the damaged count. `state.isEmpty` alone would send this to the empty state and hide the only sign the file is hurt.
3. **Log non-empty, filter matches nothing.** A message naming the term (CAP-2). The health lines and the buttons stay.
4. **Log non-empty, filter matches.** The normal list, narrowed.

Matching: `symbol.startsWith(query, ignoreCase = true)` after trimming the query. Empty query means no filtering. No `Regex` — the repo bans it in Android main sources.

The existing test tags (`earningsGateList`, `earningsGateLastCapture`, `earningsGateNotice`, `earningsGateBackUp`, `earningsGateRestore`) stay. New tags are needed for the field and the no-match message.

The back-up and restore buttons live at the tail of the same list and stay reachable under every filter. They are the log's only escape from the phone: a release build is not debuggable, and a lost signing key forces an uninstall that takes the log with it. PRD §7.1 carries the decision.

## CAP-4 and CAP-5 — the earnings section in the ticker detail

**Placement.** A section inside the `Snapshot` subtab. The request was a section. A fifth tab would charge the reader a tap to discover that most tickers have no event.

**Rendering.** `EarningsEventCard` serves both screens. No second formatting path: `SPEC.md` forbids it, and the bps-to-percent translation only stays honest while it lives in one place.

**Selection.** From the loaded `EarningsGateUi`, take rows whose `symbol` equals `DetailRoute.symbol`: the first of `upcoming` (already sorted nearest-first) and the first of `settled` (already sorted most-recent-first).

**Zero matches.** No card, and one line naming why, read from `scoreRow.nextEarningsEpoch`:

| Days to the report | Line |
|---|---|
| beyond `CAPTURE_WINDOW_DAYS` | `Earnings gate: the chain is priced inside 10 days of the report.` |
| within the window | `Earnings gate: inside the window, still unpriced. A pass has to land with the market open.` |
| no date, or a date already past | `Earnings gate: no report date yet, so nothing to price.` |

A date the calendar left behind reads the same as no date. Yahoo drops a report date after it files and before it publishes the next one, so a stale date says nothing the reader can act on.

The line waits for `earningsLoading` to clear. Naming a reason while the log is still being read can name the wrong one.

Only the middle line points at something that can fail. The other two are the gate working.

The line never repeats the date: `DetailScoreHeader` already prints `earningsMark`, which carries it. That is why `earningsGateAbsence` lives beside `earningsMark` — both turn the same epoch into a sentence, and splitting them would let the two disagree.

`CAPTURE_WINDOW_DAYS` moved out of `EarningsEventRecorder`'s private companion into `:core` (`PreReportBuilder.kt`). The screen and the recorder have to name the same window or the explanation lies about what the gate is waiting for.

**Where the data comes from.** `loadEarningsGate()` is not cached; each call re-reads the whole JSONL and re-presents it. Opening a detail must not call it per open. The detail reads the `earningsGate` already in `DashboardUiState`. The gate loads on tab open, as today, and once more the first time a detail opens before any tab did. `DashboardViewModel` tracks that with `earningsGateLoaded`, cleared again if the read fails. `DetailScreen` takes ~16 flattened params already, so the section arrives as one more param.

**Symbol mismatch.** `DashboardViewModel.clearMismatchedDetail` (`:932`) already guards stale detail payloads against `DetailRoute.symbol`. The earnings rows are filtered by that same symbol at render time, so a stale gate can show an out-of-date event but never another ticker's event.

## Test surfaces

- `:app` Robolectric + Compose rules, JUnit 4, `org.junit.Assert.*`. Existing suites to extend: `EarningsGatePresentationTest` and `EarningsGateScreenTest`.
- No live network in tests. Fixtures only.
- One assert per test, or `SoftAssertions` when two or more are needed.
- Gradle runs from `apps/android`, one suite at a time; `build.gradle.kts:47` caps each test task at three minutes.

## Verification bar

`Verde no es entregado.` The work is done when `:core` and `:app` suites are green, `lintRelease` still reports zero errors, and both surfaces have been exercised on the API 35 emulator against a seeded log — the search narrowing a real list, and a real ticker's detail showing its event.
