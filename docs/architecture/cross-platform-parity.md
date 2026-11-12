# Cross-Platform Parity

## Position Exposure

Android shows stock exposure, research labels, local position facts, and a dashboard header that returns on upward scroll.
Android Positions and Windows Advisor withhold weights when quote coverage is incomplete.
P/L uses lots with both a market value and a cost basis. The views keep separate quote and P/L coverage.
Both clients show each matching lot in ticker Detail and calculate weight against the complete book.
Windows Detail reads the Advisor book locally and uses session quotes for market fields.
See [Android Positions](../product/android-positions.md) for the field and interaction contract.

### Windows Portfolio Market Data

The Windows session cache indexes prices, ticker summaries, and one-year daily candles by ticker.
Accepted feed and Detail results fill the cache. Advisor requests missing evidence for the book's unique symbols.
Advisor uses daily candles for risk, setup, and price fallback. The cache combines concurrent candle demand.
Portfolio setup uses the selected Vantage model and market regime with available ticker summaries and daily candles.
The legacy model compares sectors within the book. Optional forecast and technical evidence can differ from Markets.
The cache expires evidence and delays retries after failed requests. The view loads missing setup inputs in bounded passes.
The active profile defines the scheduled feed. Portfolio demand has no path into Markets ranking or alerts.
Restored disk data keeps its separate display path.

## Ticker Detail

Android and Windows open Detail with available data and request ticker updates on demand.
Both clients save one local note per ticker from Detail. Vantage also offers a Delete note action.
Both apps restore saved rows for the active universe at startup.
Windows reads saved ticker Detail and candles on demand. Candles are keyed by profile, ticker, and chart range.
Windows keeps restored data separate from the state that supplies ranking and alerts.
Android also persists full ticker price history and revision history.

Discount Screener has four clients: Rust desktop, Windows Tauri, native Android, and Flutter.

## Watchlist

Both apps change Watchlist membership from Detail and persist it locally.
Vantage keeps one list across universe profiles and shows saved tickers without a session quote.

## Feed Membership

Android and Vantage can add tickers to the active feed.
Vantage saves additions and exclusions per universe. It applies them to the profile catalog at startup and on profile changes.
Vantage preserves the catalog order and appends added tickers in the order entered.

## Android Long V3 In Vantage

Vantage offers Android Long V3 as an opt-in ranking in Markets.
It ranks the active Vantage feed's equities with the Headless Android rules.
The projection uses session inputs and loads exact one-year weekly candles on demand.
The view marks incomplete valuations when the model reference cannot be computed.
The [Headless guide](../../apps/windows/src-tauri/HEADLESS.md#android-semantics-default-for-long-v3) describes the shared Kotlin and Rust parity fixture.

## Opportunities And Upside

Both apps have separate Opportunities and Upside lists.
Vantage Upside projects the active feed with session quotes and analyst targets independently of the scoring model.
It orders rows by price-relative upside and places unavailable values last.
Vantage numbers both lists after filters and sorting.

## Index Estimates

Both apps withhold the index DCF projection and show coverage diagnostics.
Each analyst endpoint uses its own companies with positive targets, session prices, and market caps.
The gap is the market-cap-weighted average of each covered company's target relative to its price.
Both endpoints need coverage to show a range. A covered endpoint can show a zero gap.
Timed return remains unavailable when the report has no target date.

## Default Rule

Shared financial semantics must stay in parity across clients.

- Use `shared/contracts` for shared ranking and valuation behavior.
- Check all four clients when a shared contract changes.
- Keep UI styling and platform integration local.
- Document each product-capability difference as an exception.

## Exceptions

- **Android Plans tab** — Android-only. Windows keeps Dashboard 2.0 Act / Scale / Wait. See [Dip](../product/android-plans-dip.md), [Cross](../product/android-plans-cross.md), and [Leftover](../product/android-plans-leftover.md).

- **Android Earnings tab** — Android-only. See [the current earnings behavior](../product/earnings-gate.md).

- **Android Chase book** — Android-only paint of Held, pin, and Positions. See [Advisor CSV import](../product/advisor-csv-import.md).

- **SEC companyfacts read (field set, not capability)** — Both clients now sieve the 4 MB body on
  the stream. The field sets differ, and they must. Android keeps `fp` and cuts everything that is
  not an annual consolidated 10-K row. The desktop keeps `frame`, `fy` and `accn`, and keeps
  quarters and `10-Q`/`8-K` rows, because `annual_candidates_with_shape`,
  `extract_normalized_investment_evidence` and `extract_current_shares` read them. Port one field
  set to the other client and the shares count and the investment evidence go wrong, silently.
  Each side has its own tests: `SecCompanyFactsSieveParityTest` on Android,
  `edgar::sieve_parity_tests` on the desktop.

  Both clients also read the document once per issuer. Android caches the sieved copy on disk for a
  day; the desktop holds it in memory for six hours, capped at 64 issuers
  (`edgar::shared_company_facts`). Before that, a screen pulled the same 4 MB twice for every
  issuer: once for the shares count, once for the driver history.

One-platform changes require explicit scope or a documented platform constraint.

- Call out the exception clearly in the task or pull request.
- Update the relevant docs so the exception is obvious to future editors.
- Keep shared behavior in shared contracts or other platform-neutral code when practical.

## Review Check

Before finishing a feature, check that:

- shared contracts produce the same meaning on each participating client, or
- the document names the platform exception.

See also [decision rule V3.1 (shadow mode)](decision-rule-v3-1.md): shadow-only S_adj grid, official output stays V3.
