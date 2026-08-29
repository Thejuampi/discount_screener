# Reading surfaces

Companion to `SPEC.md`. Holds the per-surface detail the kernel cites by name: what exists today, where each capability attaches, and the calls already made.

All paths are under `apps/android/app/src/main/kotlin/com/discountscreener/android`.

## What exists today

| Piece | Where | Shape |
|---|---|---|
| `presentEarningsGate` | `presentation/dashboard/EarningsGatePresentation.kt:46` | `(events, damagedLines, today, lastCaptureEpochSeconds, nowEpochSeconds) -> EarningsGateUi` |
| `EarningsGateUi` | same file, `:15` | `upcoming: List<EarningsEventRowUi>`, `settled: List<EarningsEventRowUi>`, `damagedLines: Int`, `lastCapture: String?`, derived `isEmpty` |
| `EarningsEventRowUi` | same file, `:24` | carries `symbol` plus 18 already-formatted strings |
| `rowOf` | same file, `:83` | one record to one row |
| `EarningsGateScreen` | `ui/dashboard/EarningsGateScreen.kt:36` | `(state, loading, pendingBackup, notice, onAction)` |
| `EarningsEventCard` | same file, `:191` | `private`, one card per row |
| `earningsEvents()` | `data/repository/DefaultDashboardRepository.kt:584` | reads the log, presents, returns empty `EarningsGateUi()` on absence or failure |
| `GetEarningsEventsUseCase` | `domain/usecase/GetEarningsEventsUseCase.kt:7` | the tab's loader |
| `loadEarningsGate()` | `presentation/dashboard/DashboardViewModel.kt:546` | cancels the prior job, flips `earningsGateLoading`, assigns `earningsGate` |
| tab laziness | `DashboardViewModel.kt:541` | `if (tab == DashboardTab.Earnings) loadEarningsGate()` |
| detail overlay | `ui/DiscountScreenerApp.kt:65` | `state.detailRoute` switches `DashboardScreen` for `DetailScreen` |
| `DetailRoute` | `DashboardViewModel.kt:138` | carries `symbol` and `subtab: DetailSubtab` |
| `DetailSubtab` | `DashboardViewModel.kt:113` | `Snapshot, Score, Lens, History` |
| `DetailScreen` | `ui/dashboard/DetailScreen.kt:128` | ~16 flattened params, no single state object |
| local-state text field pattern | `ui/dashboard/SymbolNoteField`, `DetailScreen.kt:470` | `OutlinedTextField` with a local `draft`, no ViewModel state |
| `TickerSearchBar` | `ui/dashboard/TickerSearchBar.kt:48` | symbol lookup with network-backed suggestions |

`EarningsGateUi` is a flat global list with no per-symbol grouping. `EarningsEventRowUi.symbol` is present, so both capabilities filter the already-presented rows.

## CAP-1 to CAP-3 — the search field on the Earnings tab

The field sits above the list, inside `EarningsGateScreen`, and holds its query in local composable state — the `SymbolNoteField` pattern, not `TickerSearchBar`. `TickerSearchBar` fetches suggestions over the network; wiring it here would break CAP-6. Local state also settles the "not persisted across restarts" assumption in `SPEC.md` for free, and keeps `loadEarningsGate()` from firing on every keystroke.

Filtering applies to `state.upcoming` and `state.settled`. It does not touch `damagedLines` or `lastCapture`, which describe the whole read and render unconditionally (CAP-3). This is why the filter cannot be pushed down into `presentEarningsGate` or the repository: a filtered read would produce a filtered damaged-line count, and a damaged line for another symbol would go unreported.

Three states the screen must tell apart, because two of them look identical if the code is careless:

1. **Log empty.** The existing fresh-install empty state, with the back-up and restore buttons.
2. **Log non-empty, filter matches nothing.** A message naming the term (CAP-2). The health lines and the buttons stay.
3. **Log non-empty, filter matches.** The normal list, narrowed.

Matching: `symbol.startsWith(query, ignoreCase = true)` after trimming the query. Empty query means no filtering. No `Regex` — the repo bans it in Android main sources.

The existing test tags (`earningsGateList`, `earningsGateLastCapture`, `earningsGateNotice`, `earningsGateBackUp`, `earningsGateRestore`) stay. New tags are needed for the field and the no-match message.

## CAP-4 and CAP-5 — the earnings section in the ticker detail

**Placement.** A section inside the `Snapshot` subtab, not a fifth `DetailSubtab`. The request was a section. A fifth tab would also charge every reader a tap to discover that most tickers have no event.

**Rendering.** `EarningsEventCard` widens from `private` to `internal` and serves both screens. No second formatting path: `SPEC.md` forbids it, and the bps-to-percent translation only stays honest while it lives in one place.

**Selection.** From the loaded `EarningsGateUi`, take rows whose `symbol` equals `DetailRoute.symbol`: the first of `upcoming` (already sorted nearest-first) and the first of `settled` (already sorted most-recent-first). Zero matches renders nothing at all — no header, no empty box.

**Where the data comes from.** `loadEarningsGate()` is not cached; each call re-reads the whole JSONL and re-presents it. Opening a detail must not call it per open. The detail reads the `earningsGate` already in `DashboardUiState`, and the gate loads once — on tab open as today, and once when a detail opens against an empty gate. `DetailScreen` takes ~16 flattened params already, so the section arrives as one more param rather than as a new state object.

**Symbol mismatch.** `DashboardViewModel.clearMismatchedDetail` (`:926`) already guards stale detail payloads against `DetailRoute.symbol`. The earnings rows are filtered by that same symbol at render time, so a stale gate can show an out-of-date event but never another ticker's event.

## Test surfaces

- `:app` Robolectric + Compose rules, JUnit 4, `org.junit.Assert.*`. Existing suites to extend: the 34 presentation tests and 14 screen tests named in PRD §13.
- No live network in tests. Fixtures only.
- One assert per test, or `SoftAssertions` when two or more are needed.
- Gradle runs from `apps/android`, one suite at a time; `build.gradle.kts:47` caps each test task at three minutes.

## Verification bar

`Verde no es entregado.` The work is done when `:core` and `:app` suites are green, `lintRelease` still reports zero errors, and both surfaces have been exercised on the API 35 emulator against a seeded log — the search narrowing a real list, and a real ticker's detail showing its event.
