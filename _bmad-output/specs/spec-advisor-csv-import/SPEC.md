---
id: SPEC-advisor-csv-import
status: final
policyVersion: advisor-csv-import/1
companions:
  - ../../project-context.md
sources:
  - ../../../shared/contracts/advisor-csv-import-v1.yaml
  - ../../../docs/advisor-csv-import.md
---

> **Canonical contract.** `shared/contracts/advisor-csv-import-v1.yaml` freezes kinds, detect rules, merge math, and the examples table. This SPEC states the product rules. The yaml examples are the cases that must pass.

# Advisor CSV import

## Why

Juan exports two Chase / J.P. Morgan files. `positions*.csv` is the book today. `transactions*.csv` is a 90-day blotter. They say different things.

The generic importer asked for a `side` column and a column named `price`. The snapshot has no side. The blotter names the price `Price USD`. A silent upsert of the snapshot would replace lots that came from trades. A silent aggregate of 90 days from zero would replace the real book with a window net.

## Capabilities

- **CAP-1**
  - **intent:** The importer names the file kind before any write.
  - **success:** A J.P. Morgan positions file is `holdings_snapshot` / format `J.P. Morgan`. A Chase transactions file is `trades_window` / format `Chase`. Coinbase, Schwab, and generic stay `trades_ledger`.

- **CAP-2**
  - **intent:** A holdings snapshot uses unit cost and US thousands.
  - **success:** PHYL quantity `1,273` is 1273. Price is unit cost 35.28, not market 34.61. Side is buy. Cash and QACDS rows drop.

- **CAP-3**
  - **intent:** A holdings snapshot never writes until Juan confirms.
  - **success:** The panel shows a warning that names snapshot, replace, and as-of. Confirm writes listed symbols. Cancel writes nothing. `window.confirm` is not the surface.

- **CAP-4**
  - **intent:** A Chase blotter merges onto the current book.
  - **success:** Trades with `trade_date > book_as_of` change quantity and cost. Trades on or before as-of leave quantity and cost. Empty `opened_at` takes the earliest blotter buy. No current lots, or no as-of, refuses with a reason. The blotter never aggregates from zero.

- **CAP-5**
  - **intent:** Chase Type maps to a closed set.
  - **success:** Buy and Reinvest are buys. Sell is a sell. Quantity uses abs. Dividend, DBS, WDL, DBT, BNK, and Name Change skip. Unknown Type skips.

## Constraints

- Detect order is Coinbase, Schwab, J.P. Morgan, Chase, generic.
- J.P. Morgan and Chase dates are US `MM/DD/YYYY`.
- Generic dates stay Latin `DD/MM/YYYY`.
- Upsert listed symbols only. The file does not delete omitted lots.
- Book as-of lives in `localStorage` key `ds_advisor_book_as_of`.
- A sell that exceeds the lot skips that trade. The importer does not open a short.
- Refuse over invent.

## Non-goals

- No tax lots. Average cost only.
- No second account merge.
- No Android import in this slice.
- No schema change on `portfolio_positions`.
- No change to Coinbase, Schwab, or generic ledger apply.

## Success signal

Juan imports `positions (2).csv`, reads the warning, confirms, then imports `transactions (1).csv`. PHYL stays 1273. Same-day blotter rows do not double the book.

## Assumptions

- The two files share one Self-Directed account.
- The 90-day blotter is a window. It is not the full history.
- The snapshot as-of and the newest blotter date can fall on the same day.
