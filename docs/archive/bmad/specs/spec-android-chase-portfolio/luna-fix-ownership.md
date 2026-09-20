# Luna fix ownership

Date: 2026-09-06. All workers use `gpt-5.6-luna` with effort `max`.
The agent service accepted two workers. It rejected a third due to the thread limit.

| Wave | Worker | Exclusive ownership |
| --- | --- | --- |
| 1 | `luna_book_import` | AdvisorCsv and its tests; ImportBook; DashboardLists; HeldRowPresentation; their dedicated tests; new BookCsvInput helpers |
| After import checks | `luna_book_import` | Chase PRD, SPEC, examples, operator import doc, Android README, and the existing parity row |
| 1 | `luna_calendar` | DashboardViewModel; PositionsPresentation; EarningsEventRecorder; GetEarningsEventsUseCase; their dedicated tests; FakeDashboardRepository |
| 2 | `luna_calendar`, after its calendar report | DefaultDashboardRepository; MarketDataRepository; DailyCandleSink; SQLiteStateStore and app container if needed; dedicated profile and market tests |
| All | Parent | Memlog, contracts, operational ledger, AGENTS, project context, final review, and ownership transfers |

Workers must request ownership before edits outside their list.
Workers must preserve previous user edits and the partial first-worker changes.
Each worker owns its complete edit and test loop, as Juan requested.
Workers serialize Gradle through the named Windows mutex `Local\DiscountScreenerLunaGradle`.
Each worker releases that mutex in `finally`. Node tests can run independently.
The parent will not interrupt a worker because it stays silent.
No worker can start live provider tests or live profile loads.
Each worker writes a separate result document.

Wave 2 starts after a worker completes Wave 1 and releases its files.
Any later presenter or UI change needs an explicit serial ownership transfer.

## Release checks and role correction

Juan authorizes device QA and release artifact preparation on 2026-09-06.
The release worker uses `gpt-5.6-luna` with effort `max`.
This authorization permits the `qa` profile only. App data must remain intact.

The parent owns product design, architecture, acceptance criteria, scope decisions, and the release verdict.
Luna workers implement defined changes, run defined checks, and report evidence and defects.
Workers must not design R8 or R9, choose scope deferrals, or approve production release.
The release worker finishes device checks, restores the original book, and records artifact and test evidence.
The parent reviews that evidence against the existing spec and records the release decision.
