# Discount Screener Android

This directory contains the native Android client for Discount Screener.

## Shape

- `core/` is the pure Kotlin engine/model layer and contract-test target.
- `app/` is the Android app with explicit `app`, `domain`, `data`, `presentation`, and `ui` packages.

## Architecture

- `app/` — composition root and Android entrypoints
- `domain/` — repository contracts and use cases
- `data/` — Yahoo client (JSON `quoteSummary` + chart/timeseries; cookie/crumb session), profile loading, Discovery universe seed loading, persistence, and repository implementation
- `presentation/` — `DashboardViewModel`, UI state, and actions
- `ui/` — Compose screens, dialogs, and detail/chart components

## Current implementation

- **Discovery tab** (manual): broad US equity universe separate from startup profiles. **List-first UI** (same density language as Opps/Upside): compact toolbar + ranked rows with Act/Watch/Avoid, score badge, and F/T/Fc/Upside metrics; filters expand on demand. Guided empty states: **Create US list** → **Score list**. **Update list** prefers the live [NASDAQ Trader symbol directory](https://www.nasdaqtrader.com/dynamic/SymDir/nasdaqlisted.txt) (~7k non-ETF equities; falls back to `assets/universes/us_total.txt`) and merges membership in SQLite (add/remove/keep; no quote scrape). **Score list** fetches only the minimum quote + 1Y chart data needed for `OpportunityEngine`, shows thin progress, keeps partial results, and never changes membership. Confirm dialogs guard list update, long scoring, and clear. Startup only **reads** Discovery state from the DB — it never auto-recreates or auto-refreshes. Min score and scoring model are persisted in SQLite meta. Offline asset refresh: `pwsh ./scripts/refresh-us-total-universe.ps1`.
- live candidate and opportunity reporting
- symbol detail reporting with EMA/price/MACD charts, bull-bear crossover cues, valuation, consensus, evidence, alerts, chart range selection, and phone-native system back support to return to the dashboard
- symbol detail chart replay with back/forward/live controls plus a right-side volume profile that bins visible replay-window volume by price and up/down candle direction
- opportunities as the default landing surface with **Aggressive V2** scoring selected by default; Aggressive V3 (multi-multiple + RSI + conviction + beta haircut), Aggressive V1, and Legacy remain available on demand
- restore-to-live movement badges plus analyst target revision cues on both tracked and opportunity rows, with a state-driven history detail experience that collapses flat analyst-target spans, summarizes the latest net move, and shows change-only evidence when the range is sparse
- tracked and opportunity rows now explain whether a meaningful move came from price, analyst target changes, relative re-ranking, or a combined move, and they surface quiet trust states such as No baseline, No meaningful change, freshness, saved/live timing, and No analyst target when Yahoo coverage is incomplete
- tracked and opportunity rows also surface a repository-computed `Act`, `Watch`, or `Avoid` triage chip when live data supports a direct decision, so the list can answer the first decision question before the user drills into detail
- local warm-start persistence for tracked symbols, watchlist, issues, chart cache, and revision history
- operator surfaces for candidates, opportunities, watchlist, issues, and symbol detail
- opportunities can switch in-place among Legacy, Aggressive, Aggressive V2, and Aggressive V3 ranking models from the opportunities tab
- Aggressive V3 keeps V2's continuous evidence math and adds blended valuation multiples (forward PE / EV/EBITDA / P/B), RSI regime + volume confirmation on chart summaries, analyst recommendation skew, DCF scenario-width uncertainty, and a beta risk haircut on the composite; Act/Avoid cutoffs are model-aware (±100 scale for V2/V3)
- startup splash during warm restore plus a one-time disclaimer acceptance gate before entering the app
- Valuation is a **model family** (`DcfAnalysisEngine`): operating firms use FCFF+WACC; financial services use residual income (book + ROE fade, cost of equity). Do not treat OCF−CapEx as free cash flow for insurers/banks.
- Discount rates and growth use dynamic market/policy inputs (risk-free, ERP, industry beta shrink, recent-window growth fade to \(g_{stable}\)). Hard `MIN_WACC` / price-multiple caps are not valuation truth; defaults are provisional when used.
- WACC/CoE provenance remains transparent: missing market cap may fall back to price × shares; beta / debt / cash / cost of debt / tax sources are recorded; detail Valuation shows rate kind (`WACC` vs \(r_e\)), marks provisional inputs, and lists caveats (for example `tax=default`, `market cap=price×shares`). Industry beta shrink is intentional estimation, not provisional noise.
- legacy warm-start DCF payloads without `waccInputs` still restore; live refresh recomputes with current fundamentals and model routing
- Agent conventions: root `Agents.md`; design: `_bmad-output/planning-artifacts/valuation-model-family-architecture.md`; contracts: `shared/contracts/valuation-model-family.json`

## Prerequisites

- JDK 17+
- External Android SDK with a recent platform installed

The repository no longer vendors an SDK under `apps/android`. Set `ANDROID_HOME` or provide a local `local.properties` with `sdk.dir=...` for app builds.

## Build

```bash
./gradlew :core:test
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

When the Android SDK is not available, `:core:test` remains the portable verification path for the reporting engine.

Use `make apk` from the repository root to export an **installable debug APK** to `dist/discount-screener-debug.apk`.

Use `make android-release` to export an **installable release APK** to `dist/discount-screener-release.apk`. Provide `DISCOUNT_SCREENER_RELEASE_STORE_FILE`, `DISCOUNT_SCREENER_RELEASE_STORE_PASSWORD`, `DISCOUNT_SCREENER_RELEASE_KEY_ALIAS`, and `DISCOUNT_SCREENER_RELEASE_KEY_PASSWORD` as Gradle properties, environment variables, or `local.properties` entries, and the release build uses that keystore.

If you do not already have a release keystore, run `make android-signing-bootstrap`. That script creates one locally and writes the required `DISCOUNT_SCREENER_RELEASE_*` entries into `apps\android\local.properties` so later `make android-release` builds use your own signing identity.

**With no keystore configured the release build now fails rather than falling back to the debug key.** It used to substitute that key in silence and still export the file as a release APK, which is not a release: the Android debug key is on every machine that has ever built an app, so anybody can sign a forged update to an APK carrying it. For a purely local sideload the fallback is still available, but it has to be asked for — `./gradlew :app:assembleRelease -PallowDebugSignedRelease=true` — and it logs a warning next to the artifact. Do not distribute that build.

## Run On Device

Use `make android-run` from the repository root to build, install, and launch the regular app. It cold-starts the product profile (`sp500`) and keeps existing app data. When both a USB phone and an emulator are available, the script prefers the physical device. If the phone is connected but not authorized for USB debugging, unlock it, accept the prompt, and rerun the command.

### Live / agent QA = profile `qa` only

**QA se hace con profile `qa`.** Launch it with `make android-run-qa`. That install carries the QA flag (`-PdsQaUniverse=true` → `BuildConfig.QA_UNIVERSE`), so the app cold-starts on `assets/profiles/qa.txt` (≤20 symbols: T, AMZN, CI, JPM/ACGL, AAPL, …), and it clears app data first so a prior `sp500` warm-start cannot thrash 500+ Yahoo requests. Do **not** use plain `make android-run` for QA, and do **not** switch the UI to `sp500` for agent or valuation QA unless a human explicitly orders it. Release builds ignore the flag and always default to the full product profile.
