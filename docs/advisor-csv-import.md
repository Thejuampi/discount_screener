# Advisor CSV import

Windows Advisor and Android Import book. Contract: [`shared/contracts/advisor-csv-import-v1.yaml`](../shared/contracts/advisor-csv-import-v1.yaml) (`advisor-csv-import/3`). Specs: [`_bmad-output/specs/spec-advisor-csv-import/SPEC.md`](../_bmad-output/specs/spec-advisor-csv-import/SPEC.md), [`_bmad-output/specs/spec-android-chase-portfolio/SPEC.md`](../_bmad-output/specs/spec-android-chase-portfolio/SPEC.md).

## Files Juan exports

| File | Kind | What it says |
| --- | --- | --- |
| `positions*.csv` (J.P. Morgan) | Holdings snapshot | Full book image. Quantity and unit cost as of one date. Lots the file omits are removed. |
| `transactions*.csv` (Chase) | Trades window | Buys, sells, and reinvests in the last 90 days |
| Schwab / Coinbase / generic | Trades ledger | Full history that can rebuild open lots |

A snapshot is the book. A 90-day blotter is a window. Do not swap them.

## How to load Chase

1. Import `positions*.csv`.
2. Read the warning. It names the load count and the lots it will remove.
3. Confirm.
4. Import `transactions*.csv`.
5. Read the merge warning. Confirm.

The Android reader runs on IO and stops above four MiB. It shows `Reading book…` during the read.

Cancel stops an active read. A provider error stays visible and creates no import action.

The app applies only trades after the snapshot as-of. Trades on that day stay in the snapshot. They do not add size.

Android Confirm of a merge that applied at least one trade moves book as-of to `max(prior, max applied trade_date)`. Windows snapshot as-of stays.

Android stores share quantity as integer ten-thousandths. PHYL 1273 shares is `12730000`. Cost stays cents. Book as-of lives in SQLite meta `ds_advisor_book_as_of`.

## Rules that matter

- The positions file is the full book. Confirm removes lots the file does not name.
- Cost on a J.P. Morgan row is **Unit Cost**, not Price.
- `"1,273"` is 1273 shares.
- A positive lot within the supported quantity scale stays in the book below one dollar of cost basis.
- The importer has no total cost-basis floor. Windows drops quantities that round to zero shares.
- Cost uses cents. A cost that rounds to zero cents does not emit an invalid lot.
- Cash and the Chase sweep `QACDS` drop.
- Buy, Sell, and Reinvest form positions. Dividend and cash moves skip.
- Android warnings show expected exclusions and parse failures beside the total ignored count.
- Empty lots plus a 90-day blotter refuse. Import the snapshot first.
- Android refuses Coinbase, Schwab, and generic ledger apply with `ledger_apply_unsupported`.
- Android Import book lives on Earnings, System, and Positions. Populated Positions keeps it in the menu.
- Restore log is a separate Earnings action. It never plans a lot write.
- Positions shows every lot with stock exposure and research labels. See [Android Positions](android-positions-ux.md).
- Closeness uses the existing Core enum. **Later** means the next report is after this ISO week.
- Dates come from the earnings log, then the shared Yahoo calendar cache, then a scored row.
- Dates do not depend on the active universe profile. The calendar owner decides freshness.
- Off-feed row taps do not open Detail. The local facts control shows shares and cost without provider work.
- PHYL comes from Import book only. Live QA uses `make android-run-qa` after Juan authorizes that stage.

## Confirm

The panel paints counts and removals before Confirm. It shows the omitted symbols and the next as-of.

The panel also shows applied, skipped, ignored, expected-exclusion, and parse-failure counts.

It does not use a blocking `window.confirm`. Cancel writes nothing.
