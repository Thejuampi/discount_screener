# Yahoo batch quote fixture

Captured live from `query1.finance.yahoo.com/v7/finance/quote?symbols=AAPL,BRK-B,BF.B,SATS`.

- Date: 2026-08-18
- Auth: the same cookie + crumb as quoteSummary (`v1/test/getcrumb`); without them the endpoint
  answers `401 {"finance":{"error":{"code":"Unauthorized"}}}`.
- Symbols go out under the Yahoo spelling (`BRK-B`, see `yahooRequestSymbol`); `BF.B` asked as-is
  comes back as an empty shell without a price, kept here as the shape to leave out.
- `SATS` is a live equity that quoteSummary answers and this endpoint does not: it is absent from
  `result`, so a caller must fall back to the per-symbol path for whatever is missing.
- Numbers are bare (`"regularMarketPrice": 310.03`), unlike quoteSummary's `{raw, fmt}` pairs.
