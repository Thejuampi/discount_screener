# Input reconciliation — PRD §4.7 vs SPEC-earnings-log-by-ticker vs shipped code

Date: 2026-08-29. Branch `feat/android-pre-earnings-risk-gate`.

Sources compared:

- **A** `_bmad-output/planning-artifacts/prd-pre-earnings-risk-gate-2026-08-27.md` §4.7 (`:103`) and the wiring table `### Lectura por ticker (§4.7)` (`:337`).
- **B** `_bmad-output/specs/spec-earnings-log-by-ticker/SPEC.md` and `reading-surfaces.md`.
- **C** `apps/android` sources and tests named in the request.

---

## 1. Claims the code does not honour

### 1.1 The damaged-line count disappears when the log holds no readable event

PRD §4.7: *"La línea de última captura y el conteo de líneas dañadas quedan visibles siempre."*
SPEC CAP-3 (`SPEC.md:33`): *"Their values are read from the whole log, never from the filtered subset."*
`reading-surfaces.md:34`: *"a filtered read would produce a filtered damaged-line count, and a damaged line for another symbol would go unreported."*

Code: `EarningsGateUi.isEmpty` only looks at the two row lists.

- `EarningsGatePresentation.kt:21` — `val isEmpty: Boolean get() = upcoming.isEmpty() && settled.isEmpty()`
- `EarningsGateScreen.kt:53` — `if (state.isEmpty) { ... EmptyState(title = "No earnings events logged yet" ...) ; return }`
- The damaged-line renderer sits after that return, at `EarningsGateScreen.kt:117-124`.

So a log whose every line is unreadable renders the fresh-install empty state and reports **zero** damaged lines. That is the exact false assertion CAP-2 and CAP-3 were written to prevent, reached by a different door than the filter. No test covers it: `EarningsGateScreenTest.kt:101` (`a_damaged_log_line_is_reported_and_never_hidden`) seeds `gate(day = 3, damaged = 2)`, always with a readable row present.

Same hole for `lastCapture`: the empty state does append it (`EarningsGateScreen.kt:65-66`), so that half survives; the damaged count does not.

### 1.2 `openDetail` does not load the log "una sola vez"

PRD table row `DashboardViewModel.openDetail` (`:349`): *"Carga la bitácora una sola vez si todavía está vacía."*
SPEC constraint (`SPEC.md:53`): *"`loadEarningsGate()` is uncached and re-reads the whole log, so it must not fire on every detail open."*

Code, `DashboardViewModel.kt:859-861`:

```
if (_state.value.earningsGate.isEmpty && !_state.value.earningsGateLoading) {
    loadEarningsGate()
}
```

The guard is emptiness, not "already tried". With a genuinely empty log — the common case for most of the universe, and the whole state of a fresh install — the gate stays empty after every load, so **every** detail open re-reads and re-presents the whole JSONL. The docs describe a once-per-session load; the code delivers once-per-open until the log yields a row.

### 1.3 The PRD quotes an empty-state string that does not exist

PRD §4.7: *"ese texto dice \"no hay reportes en la bitácora\""* — presented in quotes as the rendered text.

Actual string, `EarningsGateScreen.kt:63`: `No earnings events logged yet`. SPEC CAP-2 (`SPEC.md:29`) quotes it correctly. The PRD is Spanish throughout, so this reads as a gloss, but it is typographically a quotation of a user-facing string and it matches nothing in the source.

### 1.4 Stale line references in `reading-surfaces.md`

The "What exists today" table cites file:line anchors that have drifted:

| Cited | Actual |
|---|---|
| `presentEarningsGate` `:46` | `EarningsGatePresentation.kt:60` |
| `rowOf` `:83` | `EarningsGatePresentation.kt:97` |
| `EarningsGateScreen` `:36` | `EarningsGateScreen.kt:41` |
| `EarningsEventCard` `:191` | `EarningsGateScreen.kt:219` |
| `DetailScreen` `:128` | `DetailScreen.kt:133` |
| `clearMismatchedDetail` `:926` | `DashboardViewModel.kt:929` |

Correct where checked: `EarningsGateUi:15`, `EarningsEventRowUi:24`, `loadEarningsGate` `DashboardViewModel.kt:546`. `EarningsEventCard` is `internal` as promised (`EarningsGateScreen.kt:219`), and `CAPTURE_WINDOW_DAYS = 10L` does live in `:core` (`core/.../earnings/PreReportBuilder.kt:11`), read by both the recorder (`EarningsEventRecorder.kt:41`) and the screen (`EarningsMarkUi.kt:74`), so the PRD's claim there holds.

---

## 2. Load-bearing behaviour no document records

### 2.1 A report date already in the past reads as "no report date yet"

`EarningsMarkUi.kt:69-73`:

```
var epoch = nextEarningsEpoch ?: return NO_DATE_YET
...
if (days < 0L) return NO_DATE_YET
```

`NO_DATE_YET` = `"Earnings gate: no report date yet, so nothing to price."` (`EarningsMarkUi.kt:80`).

Two different states collapse into one sentence: *no date at all* and *a date that has passed with nothing logged*. The second is a lie in the reader's terms — a date exists. The choice is deliberate and tested (`DetailEarningsSectionTest.kt:101`, `a_report_date_that_already_passed_reads_as_no_date_at_all`), and it mirrors `earningsMark`'s own `days < 0L -> null` at `EarningsMarkUi.kt:49`, which drops stale dates from the score header. But PRD §4.7 names exactly three reasons and SPEC CAP-4 (`SPEC.md:37`) names the same three; neither lists this fourth path or the reasoning behind it. A future reader hitting the string on a ticker that reported last week cannot recover the intent from the code alone.

### 2.2 The absence line renders while the gate is still loading, and from a possibly-null `scoreRow`

`DetailScreen.kt:867-877` chooses the absence line on `earningsEvents.isEmpty()` alone. There is no loading guard, and `DiscountScreenerApp.kt:87` feeds it `state.earningsGate.eventsFor(detailRoute.symbol)` unconditionally. Since `openDetail` only *starts* the load (§1.2), the first frame of a detail opened against an unloaded gate asserts a reason — usually "inside the window, still unpriced" or "no report date yet" — for a ticker whose event is about to arrive. The line then flips once the state lands.

The Earnings tab handles the same race explicitly (`EarningsGateScreen.kt:49-52`, "Reading the earnings log"); the detail does not. Neither document mentions the asymmetry or says the flicker is accepted.

Second input: the reason is computed from `scoreRow?.nextEarningsEpoch` (`DetailScreen.kt:870-873`). A null `scoreRow` — detail still loading, or a symbol with no score row — yields `NO_DATE_YET`, so "no report date yet" also covers "we have not read the score row". Undocumented.

### 2.3 Time is read inside the composable

`DetailScreen.kt:872` — `System.currentTimeMillis() / 1_000L`, called during composition with no state hoisting and no injected clock. The rest of the module passes `nowEpochSeconds` in (`presentEarningsGate`, `earningsMark`). It works and is testable through `earningsGateAbsence` directly, but it is the one clock read in this feature that a test cannot pin, and no document records the exception.

### 2.4 Case-insensitive symbol matching in `eventsFor`

`EarningsGatePresentation.kt:56-57` uses `equals(symbol, ignoreCase = true)`; `DetailScreen.kt:316` filters again with the same rule. `reading-surfaces.md:52` says only "rows whose `symbol` equals `DetailRoute.symbol`". Tested (`EarningsGatePresentationTest.kt:321`), undocumented. Minor, listed for completeness.

### 2.5 The filter row and the health lines share one `LazyColumn`

`EarningsGateScreen.kt:76-90` puts the search field inside the list as its first item, so it scrolls away with the content, and `lastCapture` renders **below** it. PRD §4.7 says *"Campo de texto arriba de la lista"*, which reads as a fixed header above a scrolling list. The observable behaviour differs from the plain reading of the PRD sentence. Low stakes; noted because the PRD sentence is the only placement record.

---

## 3. PRD vs SPEC contradictions

### 3.1 `reading-surfaces.md` still describes the pre-change behaviour — the one real contradiction

The recent change gives the detail a one-line reason when the log holds no event for the ticker. Both CAP-4 and PRD §4.7 were updated. The companion was not.

`reading-surfaces.md:52`, in **CAP-4 and CAP-5 — Selection**:

> **Selection.** From the loaded `EarningsGateUi`, take rows whose `symbol` equals `DetailRoute.symbol`: the first of `upcoming` ... and the first of `settled` ... **Zero matches renders nothing at all — no header, no empty box.**

Against:

- `SPEC.md:37` (CAP-4): *"With a symbol the log has never seen, the detail renders one line naming why: beyond the capture window, inside it and still unpriced, or without a report date at all. The line never repeats the date the score header already carries."*
- PRD §4.7: *"Sin evento, una sola línea dice por qué, y nunca repite la fecha que el encabezado ya trae"* + the same three reasons.
- Code: `DetailScreen.kt:867-877`, the `else` branch renders the line under `DETAIL_EARNINGS_ABSENT`.

`reading-surfaces.md` is listed in `companions:` and the SPEC header (`SPEC.md:11`) declares SPEC **plus companions** the complete contract. So the canonical contract currently contradicts itself, and the half that is wrong is the half a reader consults for per-surface detail. `reading-surfaces.md:48` compounds it: the placement rationale still argues a fifth tab "would charge every reader a tap to discover that most tickers have no event" — with the absence line shipped, the section now says something on every ticker, so the argument's premise no longer holds even though its conclusion still does.

Fix: replace the last sentence of `reading-surfaces.md:52` with the one-line-reason behaviour, name `earningsGateAbsence` and `DETAIL_EARNINGS_ABSENT`, and record the past-date collapse from §2.1.

### 3.2 PRD §4.7 and SPEC CAP-4 agree with each other

Checked clause by clause. Three reasons, same order, same content: beyond the capture window / inside it and unpriced / no report date. Both carry the "never repeats the date the header already shows" rule, and the code honours it — none of the three strings in `EarningsMarkUi.kt:75-80` interpolates a date. The PRD adds one line the SPEC does not (*"Esta es la única de las tres que señala algo que puede fallar"*), which is commentary and not a conflicting requirement.

No other PRD/SPEC conflict found. CAP-1 prefix + case-insensitive matching matches PRD §4.7 and `EarningsGatePresentation.kt:46-53`. CAP-2's no-match message matches `EarningsGateScreen.kt:101` (`No logged report matches "…".`). CAP-6's read-only rule holds: neither surface issues a network call; the only side effect is the local file re-read in §1.2.

---

## Ranked fix list

1. `reading-surfaces.md:52` — rewrite "renders nothing at all" to the shipped one-line reason. Contract self-contradiction.
2. `EarningsGateScreen.kt:53` / `EarningsGatePresentation.kt:21` — surface `damagedLines` on the empty-log path, or fold it into `isEmpty`. Add the missing test.
3. `DashboardViewModel.kt:859` — track "already loaded" instead of "still empty", or accept and document the repeat read; then align PRD `:349` and `SPEC.md:53`.
4. Record the past-date → `NO_DATE_YET` collapse (`EarningsMarkUi.kt:73`) as a fourth case in PRD §4.7 and CAP-4.
5. Decide and record what the detail shows while `earningsGateLoading` is true (`DetailScreen.kt:867`).
6. PRD §4.7 — drop the invented quote *"no hay reportes en la bitácora"*, or quote the real string. Refresh the stale anchors in the `reading-surfaces.md` table.
