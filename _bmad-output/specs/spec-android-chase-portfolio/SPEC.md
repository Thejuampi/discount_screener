---
id: SPEC-android-chase-portfolio
status: draft
baseline_commit: 9da7dd34b4cb0991616e7ccc28635bf8e849af40
review_loop_iteration: 2
policyVersion: advisor-csv-import/3
companions:
  - examples.md
  - ../../project-context.md
  - ../../../shared/contracts/advisor-csv-import-v1.yaml
  - ../../planning-artifacts/prds/prd-android-chase-portfolio-2026-09-05/prd.md
sources:
  - ../../brainstorming/brainstorm-android-chase-portfolio-2026-09-05/brainstorm-intent.md
---

> **Canonical contract.** Yaml `/3` is the only parse/merge home. Windows is a peer. Share quantity stays in yaml. Android SQLite converts to integer ten-thousandths. Held, pin, persist scale, Confirm replay, Positions, and closeness live in `examples.md`.

# Android Chase book

## Why

Juan’s Chase book now lives on the phone as lots. Earnings and Opps pin Held names. Off-feed lots stay invisible. Positions is the book home: every lot, report closeness, and the same Opps flags when a score exists.

## Capabilities

- **CAP-1**
  - **intent:** The importer names the file kind before any write.
  - **success:** J.P. Morgan positions → `holdings_snapshot`. Chase transactions → `trades_window`. Coinbase, Schwab, and generic → `trades_ledger` then refuse `ledger_apply_unsupported`. Detect order matches the yaml. Yaml Cases: JPM-KIND, CHASE-KIND. Android refuse Cases: COINBASE, SCHWAB, GENERIC.

- **CAP-2**
  - **intent:** A holdings snapshot is the full book and never writes until Confirm.
  - **success:** Warning names kind, load count, remove count, omitted symbols, as-of. Confirm upserts listed tickers and deletes omitted lots. Cancel writes nothing. Empty keep after Cash/`QACDS` drop refuses `empty_keep` and keeps the prior book. One lot per trimmed uppercase ticker. Duplicate file rows aggregate under yaml snapshot rules; do not sum two accounts. `"1,273"` is 1273 shares in yaml. Confirm persists `12730000` ten-thousandths. Unit Cost, not Price. Yaml Cases: JPM-THOUSANDS, JPM-UNIT-COST, JPM-SKIP-CASH, JPM-ASOF, JPM-FRAC-AMZN, JPM-REMOVE-OMITTED, JPM-EMPTY-KEEP, JPM-DUP-TICKER. Persist Cases: PERSIST-PHYL, PERSIST-AMZN.

- **CAP-3**
  - **intent:** A Chase blotter merges onto the current book after as-of.
  - **success:** Empty lots refuse `trades_without_book`. Missing as-of refuses `missing_book_as_of`. `trade_date > as-of` changes qty and cost. `trade_date <= as-of` leaves qty and cost. Empty opened-at takes the earliest blotter buy. Oversell skips. Closed lots go to `remove`. Buy/Reinvest buy, Sell sell, abs qty, skip Dividend/DBS/WDL/DBT/BNK/Name Change/unknown. Never aggregate from zero. Plan goldens stay yaml MERGE-* in share units. After Confirm of a merge that applied ≥1 trade, Android as-of becomes `max(prior as-of, max applied trade_date)`. A second plan of the same file then keeps qty. Yaml `/3` names that advance as Android-only. Windows snapshot as-of stays. Confirm Cases: MERGE-REPLAY, MERGE-REPLAY-NO-APPLY.

- **CAP-4**
  - **intent:** The book survives process death as one lot per ticker.
  - **success:** SQLite unique ticker. Quantity is integer ten-thousandths. Cost is `avg_cost_cents`. As-of is SQLite meta, not `localStorage`. Warm start reads lots and as-of only. First Earnings/Opportunities paint already has the Held set, or those lists stay loading. No `LIMIT 1`. Unreadable file refuses `unreadable`. Unparseable snapshot as-of refuses `as_of_unparseable`. Confirm plans live in memory only.

- **CAP-5**
  - **intent:** Juan can see which painted rows are lots.
  - **success:** Held is exact ticker equality after trim and uppercase. Prefix `A` does not hold `AMZN`. Earnings, Opportunities, watchlist, and Tracked show the mark. `positionSizeBps` equals the no-lot cell. Cases: HELD-*, CELL-*.

- **CAP-6**
  - **intent:** Owned names sit first without buying the pin with scores.
  - **success:** Core helper `pinHeldFirst` sorts. Compose does not decide Held. Upcoming: Held then date asc. Settled: Held then date desc. Opportunities/watchlist/Tracked: Held then current sort. Scores unchanged. Printed rank is pre-pin. Tracked SQLite order stays. Watchlist membership stays a set. Absent lot invents no row. Discovery and Plans out. Cases: PIN-*.

- **CAP-7**
  - **intent:** Juan reads the full book on one tab, including names that never join Opps.
  - **success:** Positions paints every lot. Off-feed PHYL is a row with shares from ten-thousandths / 10000 (decimals only when remainder non-zero, max four) and `avg_cost_cents`. Empty book shows empty state plus Import book. Import book stays on a non-empty tab. Same writer as Earnings and System. Sort ordinal Today < Tomorrow < This week < Later < blank, then ticker ASC. Held mark omitted. Pin-held-first does not run. Off-feed lots stay absent on Opps, Watch, Tracked. Pin does not mint them. Assemble does not call `ensure_symbol_loaded` and does not add a feed symbol. Tap off-feed does not open Detail and does not enqueue Yahoo. Cases: POS-PHYL, POS-AMZN, POS-SORT, POS-SORT-TICKER, POS-SORT-ORDINAL, POS-EMPTY, POS-IMPORT-NONEMPTY, POS-NO-HYDRATE, POS-TAP-PHYL, POS-PHYL-BOARDS.

- **CAP-8**
  - **intent:** Juan sees how near the next report is without a frozen percent.
  - **success:** Core emits `Today` / `Tomorrow` / `ThisWeek` / `Later` / `None`. Compose paints that enum. Compose does not read a date and does not call `LocalDate.now()`. Clock is the earnings-log New York session day. Today = that date. Tomorrow = today+1. This week = after tomorrow, same ISO week. Later = upcoming, other. Sunday→Monday is Tomorrow. Friday or Saturday → next Monday is Later. Source 1: `EarningsGateUi.upcoming` for that ticker (load the log once if missing). Source 2: existing score-row `nextEarningsEpoch` on the NY session day (not a fetch). Else `None`. Chosen date before NY today is `None`. Off-feed PHYL uses the log only. No invented date. Not the Opps earnings-mark sentence. Cases: CLOSE-TODAY, CLOSE-TOMORROW, CLOSE-WEEK, CLOSE-LATER, CLOSE-SUN-MON, CLOSE-FRI-MON, CLOSE-SAT-MON, CLOSE-SETTLED, CLOSE-MISSING, CLOSE-LOG, CLOSE-YAHOO, CLOSE-NO-DATE, CLOSE-YAHOO-PAST, CLOSE-TZ.

- **CAP-9**
  - **intent:** A scored lot on Positions carries the same flags Juan already reads on Opps.
  - **success:** Positions scored row points at the Opps engine row on the same snapshot fingerprint. Join is exact ticker equality. Opps view filters do not hide the strip here. The Opps strip composable paints that row. Positions does not compute Act, Disc, Upside, Conf, or Lens. Token-for-token equal on that snapshot. Off-feed type has ticker, qty, cost only. No score fields. No dash badge. Scores stay. Tap scored lot opens Detail. Tap off-feed is a no-op: no Detail, no Yahoo, no feed add. Cases: POS-FLAGS-AMZN, POS-FLAGS-PHYL, POS-TAP-PHYL.

## Constraints

- Yaml `/3` examples are the parse/merge goldens. Do not copy detect headers as a second Kotlin policy table.
- Android `:core` owns parse, plan, merge, Held set, pin sort, Positions projection, and closeness. App owns SAF, Dialog, SQLite, Compose paint.
- Import book is one writer. Earnings, System, and Positions call it. Restore log never plans a lot write.
- Pin is paint. Positions does not run pin-held-first.
- Closeness is a Core enum on the earnings-log New York session day. No frozen percent. No 14-day soon window. Compose paints the token. Compose does not own the clock.
- Off-feed lots are a row shape without score fields. Positions does not invent Act/Watch/Avoid. Assemble and off-feed tap do not hydrate Yahoo or grow the feed.
- Yahoo `nextEarningsEpoch` is the existing score-row field. It is not a fetch for PHYL.
- Do not put closeness percents in `earnings-gate-policy.yaml`.
- Live path is `make android-run-qa`. Never `pm clear`. Never `sp500`. Off-feed PHYL is a `:core` Case.

## Non-goals

- Ledger apply.
- Tax lots. Second account. Class-share ticker map.
- Auto-add lots to the Yahoo feed.
- Lot dollars into hedge or `positionSizeBps`.
- V2/V3/V4 score change.
- P&L or market-value overlay on Positions.
- Windows UI. Desktop import.
- Plans / Discovery pin.

## Success signal

Juan confirms snapshot then blotter. PHYL stays 1273 shares (persisted 12730000 ten-thousandths). A `<= as-of` row does not change qty. A second confirm of the same blotter does not double qty. A held earnings fixture ticker shows Held and sits first. Composite score and printed rank stay the pre-pin values. Positions shows PHYL with 1273 shares, no Act badge, no Detail on tap, no hydrate. PHYL comes from Import book only. A scored `qa` resident shows the same Opps strip and tokens. Closeness is a Core enum. Four tags: CLOSE-* is the proof.

## Assumptions

- One Self-Directed account in the file.
- The 90-day blotter is a window.
- Positions sits next to Earnings in the tab bar.
- `generic` CSV detects as `trades_ledger` and refuses `ledger_apply_unsupported` (Case GENERIC).

## Review Triage Log

| Finding | Verdict | Route | Evidence |
| --- | --- | --- | --- |
| Yaml missing `JPM-EMPTY-KEEP` id | low | reject | Empty-keep is tested (`a_cash_only_holdings_snapshot_refuses`). Fix would edit the spec/yaml ids. |
| New yaml rows lack Windows tests | low | reject | This slice owns Android. Windows stays a peer. |
| `MERGE-OPENED-AT` had no Android test | high | patch | Added `an_empty_opened_at_takes_the_earliest_blotter_buy`. |
| PIN-WATCH / PIN-TRACKED missing from examples | low | patch | Added the Cases. Presenter tests already existed. |
| Watch pin runs in Compose | low | reject | `pinWatchedRows` uses core `pinHeldFirst` on rows that already carry `held`. |
| `importBookNotice` had no reader | medium | patch | Earnings uses `importBookNotice ?: earningsGateNotice`. System prints the notice. |
| Confirm does not rebuild snapshot | false | reject | `confirmPortfolioPlan` calls `emitUpdate()`. The ViewModel collector rebuilds. |
| Clear All leaves lots | low | defer | Same spare as `symbol_note`. The book is user data. |
| Unused `FakeDashboardRepository` | low | reject | Direct deletion is optional. Recording fake already covers ViewModel. |
| System Import / dialog / Held marks untested | medium | patch | `ImportBookScreenTest` plus populated Earnings import test. |
| Store last-row-wins vs snapshot aggregate | false | reject | Confirm writes already-aggregated lots. Store unique is a second line. |
| Earnings identity changes in this diff | false | reject | Other slice on the same branch. |
| Yaml `JPM-FRAC-AMZN` 36.2954 vs persist 362954 | false | reject | Four-decimal shares equal ten-thousandths. Persist tests 362954. |
| Confirm of Refuse reported Book updated | medium | patch | ViewModel returns on `ImportPlan.Refuse`. Test `confirm_of_a_refuse_plan_writes_nothing`. |
| `earningsEvents` Held untested after Confirm | high | patch | `earnings_events_mark_held_lots` now reads `upcoming.single().held`. |
| Pin tests never read `.held` | high | patch | `pin_opp_marks_the_held_row` and `pin_tracked_marks_the_held_row`. |
| Earnings import only on empty log | medium | patch | `a_populated_log_still_offers_import_book`. |
| `unreadable` / `as_of_unparseable` untested | high | patch | `a_blank_file_refuses_as_unreadable`, `a_holdings_snapshot_without_as_of_refuses`. |
| Empty as-of string skipped `missing_book_as_of` | high | patch | Planner uses `isNotBlank()`. Test `an_empty_book_as_of_string_refuses`. |
| Cancel during in-flight confirm still writes | medium | patch | `importConfirmJob` cancels on Cancel. |
| Confirm success cleared a newer plan | medium | patch | Confirm returns if `importBookPlan !== plan`. |
| Warm-start failure zeroed RAM lots | medium | patch | Failure path reloads `loadPortfolioBook()` into bootstrap. |
| Concurrent plan vs confirm | medium | patch | Confirm holds `stateMutex` around SQLite and RAM. |
| OpenDocument reads CSV on the main thread | low | defer | Chase files are small. A Uri-to-IO hop is new surface. |
| Null decision paints High risk | false | reject | Earnings-gate identities slice. |
