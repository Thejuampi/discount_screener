# Android Chase book — examples

Parse and merge goldens live only in `shared/contracts/advisor-csv-import-v1.yaml` `/3`. This file holds Android paint, persist scale, and confirm-scoped replay. Do not copy yaml expect numbers here.

**Share vs persist.** Yaml Cases keep share quantity (PHYL `1273`, AMZN buy `10`). Android SQLite stores `quantity_ten_thousandths = round_half_up(shares × 10000)`. Avg cost stays cents. Tests convert at the SQLite boundary.

Yaml `/3` holds `JPM-FRAC-AMZN`, `JPM-DUP-TICKER`, `MERGE-BEFORE-ASOF`, `MERGE-CLOSE-LOT`, `LEDGER-REFUSE`. `MERGE-REPLAY` stays Confirm-scoped in this file.

## Import safety and warning detail

The shared contract keeps total `ignored`. Android adds warning metadata for expected exclusions and parse failures.

```gherkin
Scenario Outline: import projection respects fixed point boundaries
  Given the importer receives Case <case>
  When it projects the snapshot or unchanged trade window
  Then each emitted lot has supported quantity and positive cost cents

  Examples:
    | Case                  | Automated test |
    | JPM-SMALL-POSITION    | Android `a_positive_snapshot_lot_below_one_dollar_stays_in_the_book`; Windows `a positive snapshot lot below one dollar stays in the book` |
    | MERGE-SMALL-UNCHANGED | Android `an_unchanged_trade_window_keeps_a_positive_lot_below_one_dollar`; Windows `an unchanged trade window keeps a positive lot below one dollar` |
    | WINDOWS-SCALE-ROUND   | Windows `a quantity below the supported scale does not emit a zero quantity lot` |
    | SUBCENT-COST          | Android `a_subcent_cost_does_not_emit_a_zero_cent_lot`; Windows `a sub-cent cost does not emit a zero-cent basis lot` |
```

```gherkin
Scenario Outline: selected book input stays bounded and cancellable
  Given the selected URI produces Case <case>
  When the reader starts
  Then the reader handles the provider without a main-thread block

  Examples:
    | Case                | Automated test |
    | BOOK-TOO-LARGE      | `reader_rejects_input_above_the_bound` |
    | BOOK-CANCEL-BEFORE  | `reader_honors_cancellation_before_open` |
    | BOOK-CANCEL-BLOCKED | `reader_cancels_a_blocked_document_provider_after_open` |
    | BOOK-OFF-MAIN       | `reader_runs_a_slow_document_provider_off_the_main_thread` |
```

```gherkin
Scenario Outline: import warnings show the planned change before Confirm
  Given the importer creates Case <case>
  When the dialog opens
  Then the warning shows counts and removed symbols

  Examples:
    | Case                  | Automated test |
    | WARNING-TRADE-ROWS    | `trade_import_warning_shows_all_counts_and_removed_symbols` |
    | WARNING-TRADE-DIALOG  | `a_trade_plan_shows_counts_and_closed_symbols_before_confirm` |
```

```gherkin
Scenario Outline: Positions labels book facts
  Given Positions paints Case <case>
  When the row appears
  Then the row labels shares and average cost

  Examples:
    | Case           | Automated test |
    | POS-LABEL-PHYL | `pos_phyl_paints_qty_and_cost` |
    | POS-LABEL-AMZN | `pos_amzn_paints_qty_and_cost` |
```

## Kind (Android refuse)

Yaml already names JPM-KIND and CHASE-KIND. Android adds refuse for ledger.

```gherkin
Scenario Outline: a ledger file does not write lots on Android
  Given a CSV whose headers match <format>
  When the importer detects the file
  Then kind is trades_ledger
  And action is refuse ledger_apply_unsupported
  And the book is unchanged

  Examples:
    | Case     | format   |
    | COINBASE | Coinbase |
    | SCHWAB   | Schwab   |
    | GENERIC  | genérico |
```

## Persist scale

```gherkin
Scenario Outline: SQLite stores share quantity as ten-thousandths
  Given yaml Case <yamlCase> parsed to <shares> shares
  When Confirm writes the lot
  Then quantity_ten_thousandths is <stored>

  Examples:
    | Case          | yamlCase        | shares   | stored     |
    | PERSIST-PHYL  | JPM-THOUSANDS   | 1273     | 12730000   |
    | PERSIST-AMZN  | JPM-FRAC-AMZN   | 36.29536 | 362954     |
```

## Confirm replay

Yaml MERGE-* Cases stay plan-scoped (qty after one merge function). Replay is Confirm-scoped.

```gherkin
Scenario Outline: as-of advances only after Confirm
  Given yaml Case MERGE-POST-ASOF-BUY on AMZN 10 shares at 20000 cents as-of 2026-08-31
  When Juan confirms the merge
  Then lots show 20 shares and cost 21000
  And Android book as-of is 2026-09-01
  When Juan plans the same blotter again
  Then action is confirm_trades_merge
  And planned qty stays 20 shares
  And Confirm would write the same lots

  Examples:
    | Case         | first_confirm_as_of | second_plan_qty_shares |
    | MERGE-REPLAY | 2026-09-01          | 20                     |
    | MERGE-REPLAY-NO-APPLY | prior as-of when applied = 0 | unchanged |
```

MERGE-REPLAY-NO-APPLY: every blotter row has `trade_date <= as-of`. Confirm does not move as-of.

## Held

```gherkin
Scenario Outline: Held is exact ticker equality
  Given lots <lots>
  And a row ticker <row>
  When the presenter marks Held
  Then held is <held>

  Examples:
    | Case        | lots | row  | held  |
    | HELD-EXACT  | AMZN | AMZN | true  |
    | HELD-PREFIX | A    | AMZN | false |
    | HELD-CASE   | amzn | AMZN | true  |
    | HELD-ABSENT | AMZN | MSFT | false |
```

## Pin

Scores and printed ranks in the table are pre-pin values. They must equal post-pin values.

```gherkin
Scenario Outline: pin is paint
  Given rows <rows> with scores <scores> and printed ranks <ranks>
  And held set <held>
  When pinHeldFirst runs
  Then visible order is <order>
  And scores stay <scores>
  And printed ranks stay <ranks>

  Examples:
    | Case          | rows              | scores | ranks | held | order      |
    | PIN-EARN-UP   | MSFT d1, AMZN d3  | n/a    | n/a   | AMZN | AMZN, MSFT |
    | PIN-EARN-SET  | AMZN d1, MSFT d3  | n/a    | n/a   | AMZN | AMZN, MSFT |
    | PIN-OPP       | MSFT 80, AMZN 40  | 80,40  | 1,2   | AMZN | AMZN, MSFT |
    | PIN-WATCH     | MSFT, GOOG, AMZN  | n/a    | n/a   | AMZN | AMZN, MSFT |
    | PIN-TRACKED   | MSFT, AMZN        | n/a    | n/a   | AMZN | AMZN, MSFT |
    | PIN-NO-INVENT | MSFT              | 80     | 1     | PHYL | MSFT       |
```

## Cell identity

```gherkin
Scenario Outline: lot quantity does not write positionSizeBps
  Given a CheapNormalRisk cell with no trail cut
  And lots <lots>
  When the card paints
  Then positionSizeBps is 10000

  Examples:
    | Case          | lots    |
    | CELL-NO-LOT   | (none)  |
    | CELL-HELD-LOT | AMZN 10 shares |
```

## Positions

Qty in this table is share units. Persist stays ten-thousandths.

```gherkin
Scenario Outline: Positions paints every lot
  Given lots <lots>
  And scored Opps tickers <scored>
  And closeness <closeness>
  When Juan opens Positions
  Then visible order is <order>
  And each row qty is shares from ten-thousandths
  And Held mark is absent
  And Import book is offered

  Examples:
    | Case            | lots                    | scored | closeness                    | order            |
    | POS-PHYL        | PHYL 1273               | (none) | (blank)                      | PHYL             |
    | POS-AMZN        | AMZN 36.2954            | AMZN   | (any or blank)               | AMZN             |
    | POS-SORT        | PHYL 1273, AMZN 36.2954 | AMZN   | AMZN Today, PHYL blank       | AMZN, PHYL       |
    | POS-SORT-TICKER | PHYL 1273, AMZN 36.2954 | AMZN   | both blank                   | AMZN, PHYL       |
    | POS-SORT-ORDINAL| PHYL 1273, AMZN 36.2954, MSFT 10 | AMZN, MSFT | MSFT Today, AMZN Later, PHYL blank | MSFT, AMZN, PHYL |
    | POS-EMPTY       | (none)                  | (none) | (none)                       | empty state      |
```

POS-EMPTY shows Import book and invents no row. POS-AMZN paints qty, cost, closeness if a date exists, and the Opps strip.

```gherkin
Scenario Outline: Positions does not hydrate off-feed lots
  Given lots PHYL 1273
  And PHYL is off-feed
  When <action>
  Then <then>

  Examples:
    | Case            | action                         | then                                              |
    | POS-NO-HYDRATE  | Positions assembles            | no feed add; no ensure_symbol_loaded; calendar cache ok |
    | CLOSE-CALENDAR  | lot BSX; calendarAsks 2026-09-14; no score row | Later |
    | CLOSE-BOOK-NOT-PROFILE | lot CAT; qa feed omits CAT; calendar cache has a date | closeness from that date |
    | POS-TAP-PHYL    | Juan taps the PHYL row         | no Detail; no Yahoo; no feed add; qty and cost stay |
    | POS-PHYL-BOARDS | Opps Watch Tracked Earnings paint | PHYL absent on all three boards; Earnings does not grow a PHYL universe row; pin does not mint |
    | POS-IMPORT-NONEMPTY | book has AMZN; Positions is open | Import book present; same writer as Earnings and System |
```

```gherkin
Scenario Outline: Positions closeness is a New York session-day calendar tag
  Given today on the earnings-log New York session day is <today>
  And the lot has upcoming report date <report>
  When Positions paints the row
  Then closeness is <tag>

  Examples:
    | Case           | today      | report     | tag        |
    | CLOSE-TODAY    | 2026-09-07 | 2026-09-07 | Today      |
    | CLOSE-TOMORROW | 2026-09-07 | 2026-09-08 | Tomorrow   |
    | CLOSE-WEEK     | 2026-09-07 | 2026-09-10 | This week  |
    | CLOSE-LATER    | 2026-09-07 | 2026-09-14 | Later      |
    | CLOSE-SUN-MON  | 2026-09-13 | 2026-09-14 | Tomorrow   |
    | CLOSE-FRI-MON  | 2026-09-11 | 2026-09-14 | Later      |
    | CLOSE-SAT-MON  | 2026-09-12 | 2026-09-14 | Later      |
    | CLOSE-SETTLED  | 2026-09-07 | 2026-09-06 | None       |
    | CLOSE-MISSING  | 2026-09-07 | (none)     | None       |
```

2026-09-07 is Monday. 2026-09-10 is Thursday of that ISO week. 2026-09-13 is Sunday. 2026-09-14 is Monday.

```gherkin
Scenario Outline: closeness prefers the earnings log
  Given today in America/New_York is 2026-09-07
  And earnings log upcoming reportEpochDay is <log>
  And Yahoo nextEarningsEpoch date is <yahoo>
  When Positions paints the row
  Then closeness is <tag>

  Examples:
    | Case          | log        | yahoo      | tag      |
    | CLOSE-LOG         | 2026-09-07 | 2026-09-14 | Today    |
    | CLOSE-YAHOO       | (none)     | 2026-09-08 | Tomorrow |
    | CLOSE-NO-DATE     | (none)     | (none)     | None     |
    | CLOSE-YAHOO-PAST  | (none)     | 2026-09-06 | None     |
```

CLOSE-YAHOO Given includes a scored Opps row that already holds `nextEarningsEpoch`. CLOSE-CALENDAR is a lot with no score row whose date lives in `calendarAsks`. Positions may ask Yahoo calendar for a lot missing from that cache. That ask does not add a feed symbol.

```gherkin
Scenario Outline: Core emits closeness; Compose does not own the clock
  Given device zone <zone>
  And New York session day is 2026-09-07
  And report date is 2026-09-07
  When Core projects closeness
  Then Core emits Today
  And Compose paints Today
  And Compose does not call LocalDate.now

  Examples:
    | Case         | zone          |
    | CLOSE-TZ     | Europe/Madrid |
    | CLOSE-TZ-UTC | UTC           |
```

Test mapping: `PositionsPresentationTest` covers both New York session-day cases.

Core emits `Today` / `Tomorrow` / `ThisWeek` / `Later` / `None`. Compose paints the token or omits `None`.

```gherkin
Scenario Outline: Positions reuses Opps flags only when a scored row exists
  Given lot <ticker>
  And Opps engine row for <ticker> is <opp>
  And snapshot fingerprint is the same
  When Positions paints
  Then flags are <flags>

  Examples:
    | Case           | ticker | opp    | flags                                                                 |
    | POS-FLAGS-AMZN | AMZN   | scored | token-for-token equal to Opps strip and F/T/Fc/Disc/Upside/Conf/Lens |
    | POS-FLAGS-PHYL | PHYL   | absent | ticker qty cost only; DecisionBadge absent; no dash chip              |
```
