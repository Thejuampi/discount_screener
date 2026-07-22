# Yahoo quoteSummary fixtures

Copied from Android live samples (`apps/android/app/src/test/resources/yahoo/quoteSummary`).

Captured live from `query1.finance.yahoo.com/v10/finance/quoteSummary/{symbol}`.

- Date: 2026-07-09
- Modules: `price,financialData,defaultKeyStatistics,assetProfile,recommendationTrend`
- Auth: cookie bootstrap via `finance.yahoo.com` + crumb via `query2.../v1/test/getcrumb`
- Symbols: AAPL, L, T, C, F, BRK-B

Windows also requests `calendarEvents` at runtime for earnings dates; older fixtures without
that module still parse cleanly (earnings field stays empty).

Do not invent or hand-edit payloads for parser tests; re-sample from live when fields drift.
