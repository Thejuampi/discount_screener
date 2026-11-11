# Android And Vantage Capability Inventory

On November 11, 2026, the comparison found 22 differences between Android and the Vantage Windows interface.

- 9 Android capabilities or interactions are absent.
- 13 capabilities have a partial equivalent or different behavior.

The baseline is `d028bdc0` with the Detail and Advisor changes in `codex/vantage-android-parity`.
Each row counts once. Modes and rules within a row do not add to the count.
The comparison uses the normal entry points in each app.
Headless equivalents appear as evidence. They do not count as Windows interface features.

## Absent In Vantage

| Capability | Android behavior and evidence |
| --- | --- |
| Plans | Dip, Cross, and Leftover run in one Plans tab. Vantage has a different Dashboard 2.0 workflow. [Parity guide](../architecture/cross-platform-parity.md#exceptions) |
| Earnings gate | Android groups pre-earnings risk, the calendar, held symbols, and the event record. Detail has Simple and Options modes. [Earnings gate](earnings-gate.md) |
| Discovery | Android maintains and scores a separate broad U.S. equity list on request. [Android guide](../../apps/android/README.md#current-implementation) |
| Watchlist | Android saves a watchlist. Detail adds or removes the current ticker. [Dashboard](../../apps/android/app/src/main/kotlin/com/discountscreener/android/ui/dashboard/DashboardScreen.kt) and [Detail](../../apps/android/app/src/main/kotlin/com/discountscreener/android/ui/dashboard/DetailScreen.kt) |
| Chart replay | Detail moves through prior candles. Its volume profile uses the visible replay window. Vantage has a volume profile in Scalping. [Android guide](../../apps/android/README.md#current-implementation) |
| Ticker notes | Android saves a personal note for a ticker. Vantage Advisor notes belong to a lot or journal entry. [Detail](../../apps/android/app/src/main/kotlin/com/discountscreener/android/ui/dashboard/DetailScreen.kt) |
| Score evaluation | Android captures daily V1–V5 comparisons. Its debug build runs outcome reports from System. Vantage has backtests and separate Headless history. [Evaluation snapshots](../architecture/scoring-evaluation-snapshots.md) |
| First-run acceptance | Android asks for one-time acceptance of its disclaimer before opening the dashboard. [Android entry point](../../apps/android/app/src/main/kotlin/com/discountscreener/android/ui/DiscountScreenerApp.kt) |
| Scroll header | The Android header hides on downward scroll and returns on upward scroll. This interaction serves the phone layout. [Android Positions](android-positions.md#use-the-dashboard-header) |

## Partial Equivalents And Different Behavior

| Capability | Difference |
| --- | --- |
| Tracked symbols | Android adds tickers to the saved tracked list. Vantage can open and load a ticker for the current session. [Android dashboard](../../apps/android/app/src/main/kotlin/com/discountscreener/android/ui/dashboard/DashboardScreen.kt) and [Vantage commands](../../apps/windows/src-tauri/src/commands.rs) |
| Long V3 ranking | Android uses its own eligibility, score, confidence, decisions, and tie-breaks. Headless ports these rules. The Vantage interface uses its own rules. [Headless guide](../../apps/windows/src-tauri/HEADLESS.md#android-semantics-default-for-long-v3) |
| Model selector | Android adds Legacy, V1, V4, and V5. Android defaults to Aggressive V2. Vantage defaults to Long V3. [Android guide](../../apps/android/README.md#current-implementation) and [Vantage entry point](../../apps/windows/src/App.tsx) |
| Opportunity lists | Android opens on Opportunities and provides a separate Upside view. Ordinals follow the visible order. [Current functionality](current-functionality.md#android-app) |
| Changes and history | Android explains price, target, and rank changes. Detail shows saved prices and analyst target revisions. Vantage shows a score timeline. [Android guide](../../apps/android/README.md#current-implementation) and [Vantage history](../../apps/windows/src/components/HistoryChart.tsx) |
| Detail charts | Android adds MACD and RSI plots and a ten-year range. Vantage Detail shows technical values and offers up to five years. [Android Detail](../../apps/android/app/src/main/kotlin/com/discountscreener/android/ui/dashboard/DetailScreen.kt) and [Vantage Detail](../../apps/windows/src/components/DetailPanel.tsx) |
| Restore after restart | Android restores saved rows, Detail, and chart data from SQLite. Vantage Detail now reads the session cache. [Parity guide](../architecture/cross-platform-parity.md#ticker-detail) |
| Manual universe refresh | Android runs the full refresh path and can retry a failed restore. Vantage's toolbar reloads rows and status from the backend. [Current functionality](current-functionality.md#android-app) and [Vantage entry point](../../apps/windows/src/App.tsx) |
| System | Android shows provider status, active errors, enrichment progress, storage counts, and maintenance actions. Vantage shows feed status and errors. [Android System](../../apps/android/app/src/main/kotlin/com/discountscreener/android/ui/dashboard/DashboardScreen.kt) and [Vantage status](../../apps/windows/src/components/StatusBar.tsx) |
| Positions | Android marks and pins held symbols. It shows per-lot facts in Detail and sorts by review or earnings. Vantage shares coverage rules. [Android Positions](android-positions.md) |
| Chase book date | Android advances the book date after it applies trades. Vantage keeps the snapshot date. The import contract specifies this difference. [Advisor CSV import](advisor-csv-import.md) |
| Valuation policy | Windows still lacks `persist_frac`, `coupon-resolution/1`, and `debt-resolution/1`. Android also uses different rate sources and a captive-finance SOTP route. [Deferred work](deferred-work.md#valuation-engine), [Android guide](../../apps/android/README.md#current-implementation), and [Android valuation engine](../../apps/android/core/src/main/kotlin/com/discountscreener/core/engine/DcfAnalysisEngine.kt) |
| Estimates | Android withholds the index DCF projection. It requires target coverage for analyst ranges and distinguishes price gaps from timed returns. [Current functionality](current-functionality.md#android-app) and [Vantage Estimates](../../apps/windows/src/components/EstimatesPanel.tsx) |

## Existing Equivalents

- Both interfaces show market regime, radar, and pillar detail. Android uses a separate tab. Vantage uses an expandable panel.
- The port adds cache-first Detail, manual ticker updates, and separate quote and P/L coverage to Vantage.
- The [V3.1 evaluation](../architecture/decision-rule-v3-1.md) keeps V3 as the official decision rule.

Use the [parity guide](../architecture/cross-platform-parity.md) to review intentional platform differences before selecting a port.
