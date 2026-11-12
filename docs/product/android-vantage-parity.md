# Android And Vantage Capability Inventory

The initial comparison found 22 differences on November 11, 2026.
Vantage now covers Watchlist, ticker notes, tracked symbols, and Estimates. The current inventory has 18 differences.

- 7 Android capabilities or interactions are absent.
- 11 capabilities have a partial equivalent or different behavior.

The comparison includes the Windows Detail, Advisor, and feed refresh changes in `codex/vantage-android-parity`.
Each row counts once. Modes and rules within a row do not add to the count.
The comparison uses the normal entry points in each app.
Headless equivalents appear as evidence. They do not count as Windows interface features.

## Absent In Vantage

| Capability | Android behavior and evidence |
| --- | --- |
| Plans | Dip, Cross, and Leftover run in one Plans tab. Vantage has a different Dashboard 2.0 workflow. [Parity guide](../architecture/cross-platform-parity.md#exceptions) |
| Earnings gate | Android groups pre-earnings risk, the calendar, held symbols, and the event record. Detail has Simple and Options modes. [Earnings gate](earnings-gate.md) |
| Discovery | Android maintains and scores a separate broad U.S. equity list on request. [Android guide](../../apps/android/README.md#current-implementation) |
| Chart replay | Detail moves through prior candles. Its volume profile uses the visible replay window. Vantage has a volume profile in Scalping. [Android guide](../../apps/android/README.md#current-implementation) |
| Score evaluation | Android captures daily V1–V5 comparisons. Its debug build runs outcome reports from System. Vantage has backtests and separate Headless history. [Evaluation snapshots](../architecture/scoring-evaluation-snapshots.md) |
| First-run acceptance | Android asks for one-time acceptance of its disclaimer before opening the dashboard. [Android entry point](../../apps/android/app/src/main/kotlin/com/discountscreener/android/ui/DiscountScreenerApp.kt) |
| Scroll header | The Android header hides on downward scroll and returns on upward scroll. This interaction serves the phone layout. [Android Positions](android-positions.md#use-the-dashboard-header) |

## Partial Equivalents And Different Behavior

| Capability | Difference |
| --- | --- |
| Long V3 ranking | Vantage offers Android Long V3 in Markets with Headless scoring. The mode uses Vantage feed equities and marks unavailable model references. [Parity guide](../architecture/cross-platform-parity.md#android-long-v3-in-vantage) |
| Model selector | Android adds Legacy, V1, V4, and V5. Android defaults to Aggressive V2. Vantage defaults to Long V3. [Android guide](../../apps/android/README.md#current-implementation) and [Vantage entry point](../../apps/windows/src/App.tsx) |
| Opportunity lists | Both clients provide separate Opportunities and Upside lists. Vantage uses session analyst targets for Upside. Android opens on Opportunities. [Parity guide](../architecture/cross-platform-parity.md#opportunities-and-upside) |
| Changes and history | Android explains price, target, and rank changes. Detail shows saved prices and analyst target revisions. Vantage shows a score timeline. [Android guide](../../apps/android/README.md#current-implementation) and [Vantage history](../../apps/windows/src/components/HistoryChart.tsx) |
| Detail charts | Android adds MACD and RSI plots and a ten-year range. Vantage Detail shows technical values and offers up to five years. [Android Detail](../../apps/android/app/src/main/kotlin/com/discountscreener/android/ui/dashboard/DetailScreen.kt) and [Vantage Detail](../../apps/windows/src/components/DetailPanel.tsx) |
| Restore after restart | Both apps restore saved rows and ticker data. Vantage restores saved chart ranges on demand. Android also retains full ticker price history. [Parity guide](../architecture/cross-platform-parity.md#ticker-detail) |
| Manual universe refresh | Vantage runs its scheduled quote and daily-chart pass on request. Android runs full enrichment and can retry a failed restore. [Current functionality](current-functionality.md#android-app) and [Windows feed refresh](../operations/windows-feed-refresh.md) |
| System | Vantage shows pass progress and feed and system errors. Android also shows provider diagnostics, storage counts, and maintenance actions. [Android System](../../apps/android/app/src/main/kotlin/com/discountscreener/android/ui/dashboard/DashboardScreen.kt) and [Vantage status](../../apps/windows/src/components/StatusBar.tsx) |
| Positions | Both clients show per-lot facts in Detail. Vantage marks Held in Detail. Android also marks and pins held symbols in lists and sorts Positions by review or earnings. [Android Positions](android-positions.md) and [Vantage behavior](current-functionality.md#windows-app) |
| Chase book date | Android advances the book date after it applies trades. Vantage keeps the snapshot date. The import contract specifies this difference. [Advisor CSV import](advisor-csv-import.md) |
| Valuation policy | Windows still lacks `persist_frac`, `coupon-resolution/1`, and `debt-resolution/1`. Android also uses different rate sources and a captive-finance SOTP route. [Deferred work](deferred-work.md#valuation-engine), [Android guide](../../apps/android/README.md#current-implementation), and [Android valuation engine](../../apps/android/core/src/main/kotlin/com/discountscreener/core/engine/DcfAnalysisEngine.kt) |

## Existing Equivalents

- Both interfaces show market regime, radar, and pillar detail. Android uses a separate tab. Vantage uses an expandable panel.
- Both interfaces save a Watchlist and change membership from Detail. [Parity guide](../architecture/cross-platform-parity.md#watchlist)
- Both interfaces save a personal note per ticker from Detail. [Parity guide](../architecture/cross-platform-parity.md#ticker-detail)
- Both interfaces can add tickers to the active feed. Vantage also supports persistent exclusions. [Parity guide](../architecture/cross-platform-parity.md#feed-membership)
- Both interfaces withhold the index DCF projection and show analyst price gaps with target coverage. [Parity guide](../architecture/cross-platform-parity.md#index-estimates)
- The port adds cache-first Detail, manual ticker updates, and separate quote and P/L coverage to Vantage.
- The [V3.1 evaluation](../architecture/decision-rule-v3-1.md) keeps V3 as the official decision rule.

Use the [parity guide](../architecture/cross-platform-parity.md) to review intentional platform differences before selecting a port.
