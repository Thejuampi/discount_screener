# Yahoo quoteSummary fixtures

Copied from Android live samples (`apps/android/app/src/test/resources/yahoo/quoteSummary`).

Captured live from `query1.finance.yahoo.com/v10/finance/quoteSummary/{symbol}`.

- Date: 2026-07-09
- Modules: `price,financialData,defaultKeyStatistics,assetProfile,recommendationTrend`
- Auth: cookie bootstrap via `finance.yahoo.com` + crumb via `query2.../v1/test/getcrumb`
- Symbols: AAPL, L, T, C, F, BRK-B

Windows also requests `calendarEvents` at runtime for earnings dates; older fixtures without
that module still parse cleanly (earnings field stays empty).

## Financial residual-income driver fixtures (P4)

Minimal shapes that pin the **COF-class** payout placement (empty/missing
`financialData.payoutRatio`, present `summaryDetail.payoutRatio`) plus reported
book and ROE. Keep Windows and Android copies byte-identical.

| Fixture | Class pattern | retention from summaryDetail |
| --- | --- | --- |
| `JPM-retention.json` | bank / banks-diversified | yes |
| `ACGL-retention.json` | insurer / insurance-property-casualty | yes |
| `COF-retention.json` | consumer finance / credit-services | yes |

Field precedence (parser + Android client):

1. **Payout → retention:** `financialData.payoutRatio` then `summaryDetail.payoutRatio`
2. **Book / BVPS:** `defaultKeyStatistics.bookValue` (or `bookValuePerShare`), else price / P/B
3. **ROE:** `financialData.returnOnEquity` only (reported)

Do not invent or hand-edit full live payloads for list parser tests; re-sample from
live when fields drift. The `*-retention.json` fixtures are intentionally minimal
driver pins for residual-income admission.
