# Architecture and execution

## Core boundary

Create `core/portfolio/PortfolioExposure.kt` and `PositionResearch.kt` under the existing Kotlin package root.
Do not edit the import parser or valuation engines.

Use `PortfolioLot` plus a symbol-indexed quote map. Keep quotes independent of app model classes.
Return ordered lot results and aggregate coverage. Preserve each input lot and use its input index as a stable key.
Use `BigInteger` for quantity times unit price, aggregate sums, and ratio numerators.
Quantity scale is 10,000. Currency scale is cents. Round half up at public display-value boundaries.
Aggregate raw products before rounding totals. Calculate weights from raw products, before cent rounding.
Only eligible public lot values contribute to a partial total.
Complete input coverage can still produce aggregate overflow. Show that refusal reason and suppress weights.
Return a weight in basis points. Render 1250 as `12.50%`.
Never discard a small positive quantity because its displayed value rounds to zero.

| Input condition | Result |
| --- | --- |
| Positive quantity and positive quote | Eligible market value |
| Missing, zero, or negative quote | Value unavailable; reduce quote coverage |
| Nonpositive quantity | Preserve row; value and P/L unavailable |
| Nonnegative cost and eligible value | Eligible P/L |
| Negative cost | Cost and P/L unavailable; market value can remain |
| Zero cost | P/L dollars available; P/L percent unavailable |
| No eligible quotes | Total unavailable; never present zero as a measured total |
| Incomplete value or nonpositive raw total | Every weight unavailable |
| Public result exceeds Long or Int | Return unavailable; never wrap or crash; total overflow suppresses all weights |

P/L numerator and cost denominator use the same eligible lots. Return separate P/L coverage.
An overflowed public market value makes that lot's P/L unavailable, even when the raw difference fits.
Core summary accepts the complete input-lot count when a caller lacks some exposure records.
The supported book uses the existing dollar convention. No FX conversion or cross-currency aggregation feature enters this scope.
The source layer remains responsible for the existing supported quote convention.

Research takes typed decision, current-evidence, trust, and primary-anchor state.
Map Updated to current research. Other research states do not prove the age of the latest quote.
Show quote age as Unconfirmed when the available evidence cannot confirm it as current.
An explicit stored quote overrides an Updated score row for research freshness.
Missing stance, primary, quote, decision, low confidence, trust warning, or provider issue means Check data.
The exact existing `Model value` trust label is informational. It does not imply missing or provisional evidence.
Blank trust notes are absent. Other nonblank trust notes remain warnings.
Consume explicit Provisional or Disputed status from matching Quant Lens evidence or expected-value sections when present.
Those statuses require Check data even with a High confidence score row and no trust note.
Tension, Disputed, Unavailable, and provisional evidence also mean Check data.
Only usable current evidence permits Act → Review opportunity, Watch → Monitor, Avoid → Review position.
Do not infer a primary from the legacy intrinsic field when the stance is absent.
Compare Identity primary with price to show Model: Undervalued, Overvalued, or At value.
Compare Analyst range primary with price to show Analyst: Above price, Below price, or At price.
Do not rename the score or recalculate it. Show the selected score model beside the score.
Known non-current scores can display with an explicit stored marker.

## Presentation boundary

Extend `presentation/dashboard/PositionsPresentation.kt` with prepared book and row fields.
Keep existing `projectPositions` callers compatible. Add a pure presenter for the summary if needed.
Both ViewModel projection paths pass existing tracked quotes independently of scores. They keep the scored-price fallback.
This uses available snapshot data and starts no provider work.
Avoid a second mutable summary that can drift from `positionsRows`.
Use the same projected rows for the summary, sorts, and Detail position block.
The row retains its original opportunity for existing Detail routing.

Reason precedence: provider or missing quote, non-current evidence, trust warning, missing/disputed valuation, then upcoming earnings or decision.
Display known refusal or trust text. Do not replace known reasons with a bare dash.
Normal rows use three lines. Extra warning text can expand vertically; never clip the only refusal reason.
Local expansion exposes shares, average cost, current quote, P/L, weight, and quote status.
Offer an explicit local facts control on every row. The primary row tap keeps its current Detail semantics.
For off-feed positions, the local control works without a DashboardAction or network call.

| Sort | Order |
| --- | --- |
| Largest position | Market value descending, unavailable last, symbol ascending, input index |
| Needs review | Check data, Review position, Monitor, Review opportunity; then largest-position order |
| Earnings soon | Existing Closeness order; then largest-position order |

Create `ui/dashboard/PositionsContent.kt`. Move the private PositionsContent from DashboardScreen into this file.
Keep `PositionsList` available to existing tests; replace its badge-heavy body with the prepared compact rows.
Put summary and sort/menu controls inside the Positions LazyColumn so they scroll away.
The menu reuses ImportBookButton and its SAF action. Empty state retains a direct import button.
Empty books omit the summary and sort menu. Normal phone totals use compact side-by-side blocks.
Retain the local sort through Detail and tab navigation with saved composition state.
Keep the entire import control clickable; do not surround it with an empty click handler.
Add an optional list of position rows to DetailScreen and SnapshotContent.
Keep the earnings card first. Put the position block after that card.
DiscountScreenerApp selects every lot with the exact route symbol from prepared rows.
Detail shows each matching lot with its own facts and stable input index. Never select the first matching lot.
Use an internal stateless route composable if needed to mount the real dashboard-to-Detail boundary in tests.
DiscountScreenerApp retains startup and ViewModel collection. The route composable receives state, session day, and the action callback.
It owns the existing Dashboard/Detail choice and passes every exact-symbol lot to Detail.
Route tests use projectPositions fixtures and check the shared book weights after a row tap.
Do not combine a fresh Detail quote with the older book denominator.

## Header boundary

Create `ui/dashboard/ReturningDashboardHeader.kt` with a small testable scroll state.
Wrap title/actions, search, tab selector, and current header status strips in one measured group.
Use a nested-scroll connection around content only. Search suggestions and tab horizontal scroll sit outside it.
Observe consumed vertical user motion on downward scroll. Short content cannot hide the header.
If content becomes short after collapse, an upward scroll attempt restores the full header.
An unconsumed negative delta at the list bottom keeps the header hidden.
An unconsumed positive delta can reveal it. Positive means a finger swipe down, toward earlier list items.
Accumulate 8 dp of same-direction motion before reacting; direction changes reset the accumulator.
After the threshold, downward motion progressively hides the group to its measured height.
After 8 dp upward user motion, return the full group. Unconsumed upward motion can recover a hidden group.
Ignore programmatic or fling side-effect events and horizontal-dominant motion.
Return zero consumed offset: the child retains its list state and scroll behavior.
Use a clipped layout with reduced height, not translation with an empty reserved header gap.
Respect the parent height limit. A short header viewport permits internal scroll to reach its controls.
Keep child measurement stable while hidden. Layout size changes must not feed the scroll state.
Pin for search focus, existing active-search state, dialogs, and touch exploration.
Add an optional search-focus callback to TickerSearchBar. Empty focused search also pins the header.
Dashboard Back clears search focus. If search is active, it also dispatches the existing ClearTickerSearch action.
This releases a blank focused field without leaving the dashboard.
Reset on selected tab, and on dashboard composition entry after Detail return.
Do not attach this behavior to DetailScreen.

## Waves and ownership

| Wave | depends_on | Owner | Files |
| --- | --- | --- | --- |
| A | [] | Luna domain | New Core exposure/research files; PositionsPresentation; domain/presenter tests |
| B | [A] | Luna header | ReturningDashboardHeader; DashboardScreen header only; TickerSearchBar focus; header tests |
| C | [A, B] | Luna UI | PositionsContent; PositionsList; remove old PositionsContent; DetailScreen; DiscountScreenerApp; UI tests |
| D | [C] | Parent | Integration checks, documentation, independent review, final debug artifact checks |

No worker edits another wave's files. The parent owns decisions and changes to this contract.
Existing unrelated edits remain in place. The pre-build snapshot records the baseline for the final scoped diff.
The parent may assign bounded review fixes back to Luna.

## Tests and deliverables

Each Examples row names one automated test. Write the failing test before behavior changes.
Use offline fixtures. No test reaches a live provider. Use the existing harness or pure portfolio inputs.
Run focused Core and Robolectric suites after each wave. Serialize Gradle runs across workers.
Run `scripts/validate-android.ps1` after integration and check native process exit codes and reports.
Run `:core:test --rerun :app:testDebugUnitTest --rerun :app:assembleDebug` for final execution proof.
Use one `--rerun` per test task. Inspect XML for actual failures and task output for execution.
Check the APK package and debug signature. Do not install, uninstall, wipe, or build signed release artifacts.

Update `docs/advisor-csv-import.md`, `docs/cross-platform-parity.md`, `_bmad-output/project-context.md`, and relevant `AGENTS.md` statements.
Check `shared/contracts/advisor-csv-import-v1.yaml` for stale presentation rules; leave import semantics unchanged.
Create `docs/android-positions-ux.md` and link it from `docs/index.md`.
Update Android README if its Positions guidance becomes stale.
Run bmad-review against the scoped diff and append the findings and their disposition to the memlog.
Report device scroll, keyboard, and Detail use cases as Not run until Juan authorizes live QA.
