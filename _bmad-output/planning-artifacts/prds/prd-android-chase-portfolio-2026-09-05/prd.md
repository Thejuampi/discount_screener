---
title: Android Chase book
status: final
created: 2026-09-05
updated: 2026-09-06
---

# PRD: Android Chase book

Solo workstation PRD for Juan. Spike lock: Book-as-identity. Brainstorm: `_bmad-output/brainstorming/brainstorm-android-chase-portfolio-2026-09-05/`. Sensei r1 + Advisor r1 absorbed. Advisor wins.

Contract home: `shared/contracts/advisor-csv-import-v1.yaml`. This spike edits that file: add Android `:core` as snapshot+window owner, split as-of by platform, name Android ledger refuse `ledger_apply_unsupported`. Bump `policyVersion` to `advisor-csv-import/3`. Yaml examples stay the parse/merge cases. Retract SPEC-advisor-csv-import non-goal “No Android import in this slice.” Operator doc, contracts README, `project-context.md`, and a `docs/cross-platform-parity.md` row name both surfaces.

Windows import stays. Desktop stays without import. Held and pin stay Android paint.

## 0. Document Purpose

This PRD states why the Android app must own the Chase / J.P. Morgan book, and the acceptance bar for flag and pin. Windows already imports. Android has no lots table. The earnings gate already prints a size identity that is not a lot.

## 1. Vision

Juan opens Earnings on the phone and sees which names he holds. Those names sit first. He opens **Positions** and sees every lot, including names that never join Opps. A small closeness tag marks a report that is near. The same Opps flags sit on a lot that already has a scored row. He loads the same two files he loads on Windows: the J.P. Morgan snapshot, then the Chase 90-day blotter. The phone refuses a blotter with no book. Scores do not change.

## 2. Target User

### 2.1 Jobs To Be Done

- Know which logged earnings names are lots.
- Put owned risk above research names on the lists he already uses.
- Read the full book on one tab, with report closeness and the same Opps flags when a score exists.
- Restore the Chase book from the two export files without rebuilding 90 days from zero.

### 2.2 Non-Users (v1)

- Other brokers as a ship target (detect may name them; Android refuses ledger apply).
- A multi-account book.

### 2.3 Key User Journeys

- **UJ-1. Juan restores the book, then reads earnings.** Warm profile `qa`. He taps **Import book** on Earnings (System has the same action, one writer). Restore log stays a second Earnings action and never plans a lot write. He picks `positions*.csv` via SAF. The app reads it on IO, shows read state, and preserves read errors. The warning shows kind, load count, remove count, omitted symbols, as-of, and ignored-row categories. Juan confirms. He imports `transactions*.csv`, reads the merge warning, and sees applied, skipped, ignored, parse-failure, and removal counts. He confirms. He opens Earnings. Held names show Held and sit first in Reporting soon and Already reported. He opens Opportunities. Held names sit first. Score badges and printed rank ordinals stay the pre-pin values.

- **UJ-2. Juan tries the blotter first.** He picks `transactions*.csv` with an empty book. The app refuses with `trades_without_book`. The book stays empty.

- **UJ-3. Juan picks a Coinbase or Schwab file.** Detect names `trades_ledger`. The app refuses with `ledger_apply_unsupported`. Cancel/dismiss writes nothing.

- **UJ-4. Juan reads the book on Positions.** Warm profile `qa` after Confirm of the snapshot. He opens **Positions**. Every lot is a row, including off-feed PHYL. AMZN shows the same Opps signal strip and F/T/Fc/Disc/Upside/Conf tokens as Opps. A closeness tag is Today, Tomorrow, This week, or Later when an upcoming report date exists. PHYL with no scored row and no report date shows lot qty and cost only. Import book lives on this tab too. Scores stay.

## 3. Glossary

- **Lot** — One open position keyed by one trimmed uppercase ticker: quantity, average cost in cents, optional opened-at.
- **Book** — The set of lots on this device plus **book as-of**.
- **Holdings snapshot** — J.P. Morgan `positions*.csv`. Full book image. Confirm replaces omitted lots.
- **Trades window** — Chase `transactions*.csv`. Last-90-days blotter. Merges onto the book after as-of.
- **Held** — A list or earnings row whose ticker equals a lot ticker after trim and uppercase. Exact equality. Earnings search may stay prefix; Held does not.
- **Pin** — Paint only. Held rows sit above other rows. SQLite tracked order and watchlist membership do not change. A lot absent from a list does not insert a row and does not join the watchlist.
- **Positions** — Dashboard tab that paints every lot. Off-feed lots are rows here. They still do not invent Opps, Watch, or Tracked rows.
- **Closeness** — Small tag on a Positions row for an upcoming report date. Four calendar levels: Today, Tomorrow, This week, Later.
- **Kind** — `holdings_snapshot` | `trades_window` | `trades_ledger`. Named before any write.

## 4. Features

### 4.1 Import the two files

**Description:** Import book is one writer. Earnings, System, and Positions all call it. Earnings restore log is a different action. Detect kind from headers. Snapshot and window plan a confirm that lives only in memory until Confirm. Ledger detect may succeed; Android refuses apply. Realizes UJ-1, UJ-2, UJ-3, UJ-4.

**Functional Requirements:**

#### FR-1: Name the kind before any write

Juan picks a CSV. The importer names kind and format before it plans a write.

**Consequences:**
- J.P. Morgan positions → `holdings_snapshot` / `J.P. Morgan`.
- Chase transactions → `trades_window` / `Chase`.
- Detect order matches the yaml: Coinbase, Schwab, J.P. Morgan, Chase, generic.
- `trades_ledger` → refuse `ledger_apply_unsupported`. The dialog prints that code. No write.
- Unknown or unreadable file → refuse with a reason. No write.
- SAF reads run on IO with a four MiB bound. Cancellation stops the read and leaves the book unchanged.
- The UI shows read state and preserves provider errors before parse planning starts.
- A planned confirm that never confirms dies with process death. Disk is unchanged.

#### FR-2: Snapshot confirm replace

A holdings snapshot shows a warning, then Confirm and Cancel.

**Consequences:**
- Warning names snapshot, load count, remove count, omitted symbols, as-of, and ignored-row categories.
- Confirm upserts listed lots and deletes every current lot the file omits.
- Cancel writes nothing.
- Empty keep (zero equity rows after Cash/`QACDS` drop, including cash-only) refuses `empty_keep`. Prior book stays.
- Duplicate ticker in the file aggregates under snapshot rules. The book stores one lot per ticker.
- Quantity `"1,273"` is 1273. Cost is Unit Cost, not Price. Cash and `QACDS` drop.
- Fractional share quantities stay as parsed (AMZN 36.29536 is valid).
- Positive lots within the supported quantity scale stay in the book, even below one dollar of cost basis.
- No total cost-basis floor removes a positive lot. Quantity rounds to four decimals at the Windows boundary.
- Cost stores in cents. A positive lot whose cost rounds to zero cents is not representable and drops.

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
- An unchanged trade window preserves a positive lot below one dollar of cost basis.
- The warning shows applied, skipped, ignored, parse-failure, and removed-symbol details before Confirm.
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

### 4.3 Read the book on Positions

**Description:** Positions is a dashboard tab. It paints every Lot. Off-feed lots are rows here. A scored lot reuses the Opps signal strip and metric tokens. A small Closeness tag marks an upcoming report. Import book is a third caller of the same writer. Realizes UJ-4.

#### FR-7: Positions shows every lot

Juan opens Positions and sees the full Book.

**Consequences:**
- Every lot is a row, including off-feed PHYL.
- Empty book shows an empty state and Import book. No invented row.
- Import book stays on a non-empty tab. Same writer as Earnings and System.
- Qty is shares from `quantity_ten_thousandths / 10000`. Show decimals only when the remainder is non-zero. Max four decimal places. Label it `Shares`. Label `avg_cost_cents` as `Average cost`.
- Sort ordinal: Today < Tomorrow < This week < Later < blank, then ticker ASC.
- The Held mark stays off this tab. Every row is already a lot. Pin-held-first does not run here.
- Off-feed lots stay absent on Opps, Watch, and Tracked. Pin-held-first does not mint those rows. Earnings does not grow a PHYL universe row.
- Assemble Positions does not call `ensure_symbol_loaded` or add a feed symbol.
- The calendar path can request missing or expired lot dates through the shared calendar owner.
- Tab label is Positions. It sits next to Earnings in the dashboard tab bar.
- Build also edits these homes: AGENTS.md Import-book sentence, `project-context.md`, `docs/advisor-csv-import.md`, Android README, `docs/cross-platform-parity.md` Chase-book row. Do not put closeness percents in `earnings-gate-policy.yaml`.

#### FR-8: Closeness is four calendar tags

A Positions row shows one small Closeness tag when an upcoming report date exists.

**Consequences:**
- Core emits `Today` | `Tomorrow` | `ThisWeek` | `Later` | `None`. Compose paints the token or omits it. Compose does not read a date. Compose does not call `LocalDate.now()`.
- Today: report date equals today on the New York session day (earnings-log capture `today`).
- Tomorrow: report date equals that today plus one calendar day.
- This week: report date is after tomorrow and in the same ISO week as that today.
- Later: an upcoming report date exists and is not Today, Tomorrow, or This week.
- Sunday → Monday is Tomorrow. Friday or Saturday → next Monday is Later.
- Source 1: an eligible earnings-log date for that ticker. Load the log once if missing.
- Source 2: the shared Yahoo calendar cache. Its owner applies the existing freshness policy.
- Source 3: existing score-row `nextEarningsEpoch`, converted on the New York session day.
- Expired dates and old empty answers can request fresh evidence through the shared calendar owner.
- A fresh future date or recent empty answer does not repeat the Yahoo request.
- Off-feed lots can use the log and shared calendar. These requests do not add feed symbols.
- Else `None`. A date before New York today is not an eligible upcoming date.
- Settled and missing stay `None`. No invented date.
- Clock home is the earnings-log New York session day. Not device local. Not a second UTC today.
- No frozen percent. No 14-day soon window. Not the Opps earnings-mark sentence. Do not add closeness knobs to `earnings-gate-policy.yaml`.

#### FR-9: Positions reuses Opps flags when a score exists

A lot that already has a scored Opportunities row shows the same flags as Opps.

**Consequences:**
- Positions scored row points at the Opps engine row on the same snapshot fingerprint. Join is exact ticker equality, same as Held. Opps view filters do not hide the strip here.
- The Opps strip composable paints that row. Positions does not compute Act, Disc, Upside, Conf, or Lens.
- Signal strip: DecisionBadge Act/Watch/Avoid, valuation-change, rank-movement, explanation, freshness+time, trust note, Watchlist, Lens chips (max 3).
- Metric tokens: F, T, Fc, Market when included, Disc, Upside, Conf. `providerIssue` when present.
- Token-for-token equal to Opps for that ticker on that snapshot.
- Off-feed type has ticker, qty, and cost only. No score fields. No Act/Watch/Avoid chip. No dash placeholder.
- Scores stay. Positions does not recompute V2/V3/V4.
- Tap a scored lot opens Detail. Tap an off-feed lot is a no-op: no Detail, no Yahoo, no feed add.

## 5. Non-Goals (Explicit)

- Coinbase / Schwab / generic ledger apply on Android.
- Tax lots. Average cost only.
- Second account merge. One Self-Directed book.
- Auto-add lots to the Yahoo feed or earnings capture.
- Lot dollars into hedge or `positionSizeBps`.
- V2 / V3 / V4 score change.
- P&L or market-value overlay on Positions.
- Windows UI change. Desktop import.
- Class-share ticker map (`BRK.B` vs `BRK/B`). Exact ticker only this spike.
- Live QA on `sp500`. Agent path is `make android-run-qa`. Never `pm clear`.

## 6. MVP Scope

### 6.1 In Scope

- Contract `/3` plus Android `:core` parse, plan, merge. Yaml examples must pass as Gherkin Cases.
- SQLite lots + as-of. Unique ticker. Schema bump.
- Import book on Earnings, System, and Positions. Confirm Dialog. Restore log stays separate.
- Held flag and pin on Earnings, Opportunities, watchlist, Tracked.
- Positions tab. Every lot. Closeness four tags. Opps flags reuse when a scored row exists.

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
- **SM-3**: After the same snapshot, Positions shows PHYL with 1273 shares, no Act badge, no Detail on tap, no hydrate. PHYL comes from Import book only. Never one-shot. Never feed add. A scored `qa` resident (AMZN if present, else one scored `qa` name) shows the same Opps strip and tokens. A closeness tag is one of Today, Tomorrow, This week, Later when an upcoming report date exists, and blank when settled or missing. Four tags: CLOSE-* is the proof. Live cannot hit all four in one session. Profile stays `qa`. Validates FR-7, FR-8, FR-9.

**Counter-metrics (do not optimize)**
- **SM-C1**: Composite score of a Held name equals the score before pin. Printed rank ordinal equals pre-pin rank. Counterbalances FR-6.
- **SM-C2**: Held name with lots present: `positionSizeBps` equals the no-lot cell.
- **SM-C3**: Off-feed PHYL does not appear on Opps, Watch, or Tracked. Positions does not invent a score for PHYL. Counterbalances FR-7, FR-9.

## 8. Verification

- `:core` yaml examples, as-of skip, blotter replay, unique ticker, small positive lots, `empty_keep`, `ledger_apply_unsupported`.
- Windows parity tests cover small positive lots and rounded zero quantities.
- Book input tests cover the four MiB bound, cancellation before open, blocked-read cancellation, and IO dispatch.
- Import warning tests cover counts, parse failures, expected exclusions, removed symbols, and labels.
- SQLite/repository: confirm, cancel, restart restore of lots and as-of.
- Presenter pin/flag tests. Compose does not own Held.
- Positions: every-lot Cases, closeness calendar Cases, Opps-flag reuse vs off-feed blank. Compose does not own Closeness. POS-NO-HYDRATE, POS-TAP-PHYL, CLOSE-TZ required.
- Live: when Juan says the product is ready, `make android-run-qa` only. Never `pm clear`. Never switch UI to `sp500`. PHYL from Import book only.

## 9. Open Questions

None that block this slice. Ledger apply waits for a later spike. Off-feed tap is a no-op.

## 10. Assumptions Index

- The two files share one Self-Directed account.
- The 90-day blotter is a window.
- Import book on Earnings, System, and Positions is one writer.
- Watch in this PRD is the watchlist surface.
- Positions sits next to Earnings in the tab bar.
