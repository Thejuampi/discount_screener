---
title: Android Chase book
status: final
created: 2026-09-05
updated: 2026-09-05
---

# PRD: Android Chase book

Solo workstation PRD for Juan. Spike lock: Book-as-identity. Brainstorm: `_bmad-output/brainstorming/brainstorm-android-chase-portfolio-2026-09-05/`. Sensei r1 + Advisor r1 absorbed. Advisor wins.

Contract home: `shared/contracts/advisor-csv-import-v1.yaml`. This spike edits that file: add Android `:core` as snapshot+window owner, split as-of by platform, name Android ledger refuse `ledger_apply_unsupported`. Bump `policyVersion` to `advisor-csv-import/3`. Yaml examples stay the parse/merge cases. Retract SPEC-advisor-csv-import non-goal “No Android import in this slice.” Operator doc, contracts README, `project-context.md`, and a `docs/cross-platform-parity.md` row name both surfaces.

Windows import stays. Desktop stays without import. Held and pin stay Android paint.

## 0. Document Purpose

This PRD states why the Android app must own the Chase / J.P. Morgan book, and the acceptance bar for flag and pin. Windows already imports. Android has no lots table. The earnings gate already prints a size identity that is not a lot.

## 1. Vision

Juan opens Earnings on the phone and sees which names he holds. Those names sit first. He loads the same two files he loads on Windows: the J.P. Morgan snapshot, then the Chase 90-day blotter. The phone refuses a blotter with no book. Scores do not change.

## 2. Target User

### 2.1 Jobs To Be Done

- Know which logged earnings names are lots.
- Put owned risk above research names on the lists he already uses.
- Restore the Chase book from the two export files without rebuilding 90 days from zero.

### 2.2 Non-Users (v1)

- Other brokers as a ship target (detect may name them; Android refuses ledger apply).
- A multi-account book.

### 2.3 Key User Journeys

- **UJ-1. Juan restores the book, then reads earnings.** Warm profile `qa`. He taps **Import book** on Earnings (System has the same action, one writer). Restore log stays a second Earnings action and never plans a lot write. He picks `positions*.csv` via SAF, reads the warning (kind, load count, remove count, omitted symbols, as-of), confirms. He imports `transactions*.csv`, reads the merge warning, confirms. He opens Earnings. Held names show Held and sit first in Reporting soon and Already reported. He opens Opportunities. Held names sit first. Score badges and printed rank ordinals stay the pre-pin values.

- **UJ-2. Juan tries the blotter first.** He picks `transactions*.csv` with an empty book. The app refuses with `trades_without_book`. The book stays empty.

- **UJ-3. Juan picks a Coinbase or Schwab file.** Detect names `trades_ledger`. The app refuses with `ledger_apply_unsupported`. Cancel/dismiss writes nothing.

## 3. Glossary

- **Lot** — One open position keyed by one trimmed uppercase ticker: quantity, average cost in cents, optional opened-at.
- **Book** — The set of lots on this device plus **book as-of**.
- **Holdings snapshot** — J.P. Morgan `positions*.csv`. Full book image. Confirm replaces omitted lots.
- **Trades window** — Chase `transactions*.csv`. Last-90-days blotter. Merges onto the book after as-of.
- **Held** — A list or earnings row whose ticker equals a lot ticker after trim and uppercase. Exact equality. Earnings search may stay prefix; Held does not.
- **Pin** — Paint only. Held rows sit above other rows. SQLite tracked order and watchlist membership do not change. A lot absent from a list does not insert a row and does not join the watchlist.
- **Kind** — `holdings_snapshot` | `trades_window` | `trades_ledger`. Named before any write.

## 4. Features

### 4.1 Import the two files

**Description:** Import book is one writer. Earnings and System both call it. Earnings restore log is a different action. Detect kind from headers. Snapshot and window plan a confirm that lives only in memory until Confirm. Ledger detect may succeed; Android refuses apply. Realizes UJ-1, UJ-2, UJ-3.

**Functional Requirements:**

#### FR-1: Name the kind before any write

Juan picks a CSV. The importer names kind and format before it plans a write.

**Consequences:**
- J.P. Morgan positions → `holdings_snapshot` / `J.P. Morgan`.
- Chase transactions → `trades_window` / `Chase`.
- Detect order matches the yaml: Coinbase, Schwab, J.P. Morgan, Chase, generic.
- `trades_ledger` → refuse `ledger_apply_unsupported`. The dialog prints that code. No write.
- Unknown or unreadable file → refuse with a reason. No write.
- A planned confirm that never confirms dies with process death. Disk is unchanged.

#### FR-2: Snapshot confirm replace

A holdings snapshot shows a warning, then Confirm and Cancel.

**Consequences:**
- Warning names snapshot, load count, remove count, omitted symbols, as-of.
- Confirm upserts listed lots and deletes every current lot the file omits.
- Cancel writes nothing.
- Empty keep (zero equity rows after Cash/`QACDS` drop, including cash-only) refuses `empty_keep`. Prior book stays.
- Duplicate ticker in the file aggregates under snapshot rules. The book stores one lot per ticker.
- Quantity `"1,273"` is 1273. Cost is Unit Cost, not Price. Cash and `QACDS` drop.
- Fractional share quantities stay as parsed (AMZN 36.29536 is valid).

#### FR-3: Window confirm merge

A Chase blotter merges onto current lots after book as-of. Source of law: yaml examples plus Windows `planCsvImport` / `mergeTradesOntoLots`.

**Consequences:**
- Empty lots → refuse `trades_without_book`.
- Lots exist and as-of is missing → refuse `missing_book_as_of`.
- `trade_date > book_as_of` changes quantity and cost.
- `trade_date <= book_as_of` leaves quantity and cost. Same-day is one row of that inequality, not a special rule.
- Empty opened-at takes the earliest blotter buy, including pre-as-of buys.
- Sell beyond qty skips that trade. No short.
- A window that closes a lot lists that symbol in `remove`. Confirm deletes it.
- Buy, Reinvest → buy. Sell → sell. Quantity uses abs. Dividend, DBS, WDL, DBT, BNK, Name Change, and unknown Type skip.
- The blotter never aggregates from zero.
- After Confirm of a merge that applied at least one trade, Android book as-of becomes `max(prior as-of, max applied trade_date)`. A second confirm of the same file then sees those rows as `<= as-of` and does not double qty.

#### FR-4: Durable book

Lots and book as-of survive process death.

**Consequences:**
- SQLite schema bump. Table `portfolio_lot`: unique ticker (trimmed uppercase), quantity, avg_cost_cents, opened_at. Meta key for book as-of. No `LIMIT 1` lexical identity.
- Public Held/pin read after restart uses that unique ticker.
- Warm start reads lots and as-of only. No Yahoo, SEC, or earnings capture.
- First frame that paints Earnings or Opportunities already has the Held set, or those lists stay in their loading empty state until the set is ready. No flash of unpinned order.
- Persistence tests cover confirm write, cancel no-write, and process-death restore.

### 4.2 Connect the book to lists

**Description:** Held is a flag and a pin. It is not a score. Presenter (or a core projection helper) sorts an unchanged score snapshot. Compose does not decide Held. `OpportunityEngine` output stays. Realizes UJ-1.

#### FR-5: Flag Held

An earnings card for a Held ticker shows Held next to the ticker. Opportunity, watchlist, and Tracked rows show the same mark.

**Consequences:**
- Match is exact uppercase ticker equality after trim.
- `positionSizeBps` stays the cell identity (full / half / exit). Lot quantity does not write it. A held name with lots present keeps the same `positionSizeBps` as the no-lot cell.

#### FR-6: Pin is paint

Held rows sit first on screen. Persist stays.

**Consequences:**
- Earnings upcoming: Held first, then report date ascending.
- Earnings settled: Held first, then report date descending.
- Opportunities, watchlist, Tracked: Held first, then the current sort.
- Composite and bucket scores do not change.
- Printed rank ordinal is the pre-pin score rank. Pin is a sort overlay.
- Tracked SQLite order stays. Watchlist membership stays a set.
- A lot whose ticker is absent from the list stays in SQLite and does not invent a row.
- Discovery, Plans (Dip / Cross / Leftover) are out of pin scope.

## 5. Non-Goals (Explicit)

- Coinbase / Schwab / generic ledger apply on Android.
- Tax lots. Average cost only.
- Second account merge. One Self-Directed book.
- Auto-add lots to the Yahoo feed or earnings capture.
- Lot dollars into hedge or `positionSizeBps`.
- V2 / V3 / V4 score change.
- New Portfolio tab.
- Windows UI change. Desktop import.
- Class-share ticker map (`BRK.B` vs `BRK/B`). Exact ticker only this spike.
- Live QA on `sp500`. Agent path is `make android-run-qa`. Never `pm clear`.

## 6. MVP Scope

### 6.1 In Scope

- Contract `/3` plus Android `:core` parse, plan, merge. Yaml examples must pass as Gherkin Cases.
- SQLite lots + as-of. Unique ticker. Schema bump.
- Import book on Earnings and System. Confirm Dialog. Restore log stays separate.
- Held flag and pin on Earnings, Opportunities, watchlist, Tracked.

### 6.2 Out of Scope for MVP

- Ledger apply.
- Feed hydration of lots.
- Plans and Discovery pin.
- P&L overlay.

## 7. Success Metrics

**Primary**
- **SM-1**: Snapshot then blotter. PHYL stays 1273. A blotter row with `trade_date <= as-of` does not change qty. A second confirm of the same blotter does not double qty. Validates FR-2, FR-3.

**Secondary**
- **SM-2**: Fixture ticker that already sits on the earnings list: after import it shows Held and sits above a non-lot with an earlier date in the same section. PHYL 1273 is a `:core` Case. If `qa` ∩ book is empty, live SM-2 is unreachable and the test is the proof. Do not grow the feed. Validates FR-5, FR-6.

**Counter-metrics (do not optimize)**
- **SM-C1**: Composite score of a Held name equals the score before pin. Printed rank ordinal equals pre-pin rank. Counterbalances FR-6.
- **SM-C2**: Held name with lots present: `positionSizeBps` equals the no-lot cell.

## 8. Verification

- `:core` yaml examples, as-of skip, blotter replay, unique ticker, `empty_keep`, `ledger_apply_unsupported`.
- SQLite/repository: confirm, cancel, restart restore of lots and as-of.
- Presenter pin/flag tests. Compose does not own Held.
- Live: when Juan says the product is ready, `make android-run-qa` only. Never `pm clear`. Never switch UI to `sp500`.

## 9. Open Questions

None that block the spike. Ledger apply waits for a later spike.

## 10. Assumptions Index

- The two files share one Self-Directed account.
- The 90-day blotter is a window.
- Import book on Earnings and System is one writer.
- Watch in this PRD is the watchlist surface.
