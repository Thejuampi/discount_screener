# Yahoo quoteSummary fixtures

Captured live from `query1.finance.yahoo.com/v10/finance/quoteSummary/{symbol}`.

- Date: 2026-07-09
- Modules: `price,financialData,defaultKeyStatistics,assetProfile,recommendationTrend`
- Auth: cookie bootstrap via `finance.yahoo.com` + crumb via `query2.../v1/test/getcrumb`
- Symbols: AAPL, L, T, C, F, BRK-B

Do not invent or hand-edit payloads for parser tests; re-sample from live when fields drift.
