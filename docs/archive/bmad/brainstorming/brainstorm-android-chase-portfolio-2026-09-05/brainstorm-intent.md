# Intent: Android Chase book

Locked spike: **Book-as-identity**.

Juan needs the phone to know which names are lots. Then earnings can flag them. Then lists can show them first.

## Keep

- Port `advisor-csv-import/2` detect, J.P. Morgan snapshot, Chase window, plan, and merge into Android `:core`.
- Persist lots and book as-of in SQLite. A restart restores the book.
- One SAF picker. Warn, then Confirm. Cancel writes nothing.
- A Chase blotter with no book, or no as-of, refuses.
- Empty keep refuses. It does not wipe the book.
- Flag Held on earnings cards. Pin held first on earnings (then report date) and on Opportunities, Watch, and Tracked (then current order). Scores stay.
- Exact uppercase ticker match. Skip cash and `QACDS`.

## Drop this spike

- Coinbase / Schwab / generic ledger apply
- Tax lots, second account
- Auto-add lots to the Yahoo feed
- Lot quantity into `positionSizeBps`
- V2 / V3 / V4 score change
- A new Portfolio tab
- Windows UI change
- Live `sp500` QA

## Why this wins

Flag and pin without lots is decoration. A Chase 90-day file without the snapshot is the Windows anti-pattern. Scores must not buy the pin.
