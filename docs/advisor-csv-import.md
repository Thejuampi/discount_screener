# Advisor CSV import

Windows Advisor import. Contract: [`shared/contracts/advisor-csv-import-v1.yaml`](../shared/contracts/advisor-csv-import-v1.yaml). Spec: [`_bmad-output/specs/spec-advisor-csv-import/SPEC.md`](../_bmad-output/specs/spec-advisor-csv-import/SPEC.md).

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

The app applies only trades after the snapshot as-of. Trades on that day stay in the snapshot. They do not add size.

## Rules that matter

- The positions file is the full book. Confirm removes lots the file does not name.
- Cost on a J.P. Morgan row is **Unit Cost**, not Price.
- `"1,273"` is 1273 shares.
- Cash and the Chase sweep `QACDS` drop.
- Buy, Sell, and Reinvest form positions. Dividend and cash moves skip.
- Empty lots plus a 90-day blotter refuse. Import the snapshot first.

## Confirm

The panel paints a warning, then Confirm and Cancel. It does not use a blocking `window.confirm`. Cancel writes nothing.
