# Android Chase book — examples

Parse and merge goldens live only in `shared/contracts/advisor-csv-import-v1.yaml` `/3`. This file holds Android paint, persist scale, and confirm-scoped replay. Do not copy yaml expect numbers here.

**Share vs persist.** Yaml Cases keep share quantity (PHYL `1273`, AMZN buy `10`). Android SQLite stores `quantity_ten_thousandths = round_half_up(shares × 10000)`. Avg cost stays cents. Tests convert at the SQLite boundary.

Yaml `/3` holds `JPM-FRAC-AMZN`, `JPM-DUP-TICKER`, `MERGE-BEFORE-ASOF`, `MERGE-CLOSE-LOT`, `LEDGER-REFUSE`. `MERGE-REPLAY` stays Confirm-scoped in this file.

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
