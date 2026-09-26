# Deferred Work

This file lists known open work. It does not list completed review findings.

## Android Positions And Import

- Add restart cases after Import confirmation and cancellation.
- Decide whether Clear All should remove imported lots and the book date.
- Move SAF CSV reading from the UI thread to IO.

Read [Advisor CSV import](advisor-csv-import.md) and [Android Positions](android-positions.md).

## Android Earnings

- Keep Alpha Vantage revenue surprise unused until Juan requests it.
- Preserve the estimate vintage when a future SUE model needs point-in-time estimates.
- Keep SUE outside the event cell until Juan requests that change.
- Collect 8–12 quarters of option-chain history before a paper-trading backtest.
- Add sector-calibrated bands only after that backtest.

Read [the Earnings gate](earnings-gate.md).

## Android Plans

- Remove the copied valuation-quality path from `CrossSignalEngine`.
- Replace the `presentCard` hunt booleans with a typed hunt state.
- Decide whether provisional WACC makes Dip and Leftover model quality soft.
- Handle an extreme scenario width without `ArithmeticException`.

Read the [Dip](android-plans-dip.md), [Cross](android-plans-cross.md), and [Leftover](android-plans-leftover.md) rules.

## Valuation Engine

- Resolve [the Android valuation integrity audit](https://github.com/Thejuampi/discount_screener/issues/61) before merging its worktree.
- Fix maintenance CapEx for CHTR-class network businesses without ticker rules.
- Keep the multi-name baseline green during that change.
- Recheck CHTR EPS against FCFF after the maintenance-CapEx fix.
- Define a continuous cost-of-equity risk function.
- Define a CapEx cycle regime for names such as MPWR and WDC.
- Close the high-signal cohort without clamps or quarantine.
- Harden model-policy version governance.
- Add a typed diagnostic flag to `QuantLensSection`.

Windows still lacks several Android valuation policies. Juan deferred that port.

The deferred set includes `persist_frac`, `coupon-resolution/1`, and `debt-resolution/1`.

Do not start that port without Juan's request.

## Windows Data And UI

- Distinguish transient ticker-search failure from a successful empty result.
- Apply the Yahoo share-class mapping to chart endpoints.
- Honor the requested volume-ratio lookback and ignore zero-volume rows.
- Clean the existing frontend lint baseline separately from product changes.
- Move synchronous market-regime refresh away from the Tauri command thread.
- Add a single-flight guard for concurrent regime cache misses.
- Keep a data-free regime `Unknown`, not `Neutral`.
- Audit Short-side regime signs and effective-feature coverage.
- Derive trend from SPY closes when no cached summary exists.
- Add responsive behavior to the three-column regime banner.
- Make the Detail operational plan clearer than raw score copy.

## Ranking And Presentation

- Define corroboration for quarterly EPS pulse on thin loss histories.
- Fail closed when a financial issuer lacks a usable sector key.
- Align Windows FCF scoring with the current Android decision.
- Prevent mixed sector P/E and absolute P/B from saying `vs sector`.
- Smooth the `$999,999` to `$1M` display boundary.
- Bound long-score comparison text on narrow phones.
- Do not use average diluted shares as outstanding shares.
- Add FCF cases to `shared/contracts/opportunity-v4.json`.
- Calibrate the provisional OCF-to-FCF band with real samples.

## Historical Context

The former BMad output remains under [the documentation archive](../archive/README.md).

Use archived handovers only for investigation history. Current contracts and documents control behavior.
