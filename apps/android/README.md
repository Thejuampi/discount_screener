# Discount Screener Android

This directory contains the native Android client for Discount Screener.

## Shape

- `core/` is the pure Kotlin engine/model layer and contract-test target.
- `app/` is the Android app with explicit `app`, `domain`, `data`, `presentation`, and `ui` packages.

## Architecture

- `app/` — composition root and Android entrypoints
- `domain/` — repository contracts and use cases
- `data/` — Yahoo client (JSON `quoteSummary` + chart/timeseries; cookie/crumb session), profile loading, persistence, and repository implementation
- `presentation/` — `DashboardViewModel`, UI state, and actions
- `ui/` — Compose screens, dialogs, and detail/chart components

## Current implementation

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
- DCF WACC is resilient and transparent: missing market cap falls back to price × shares when possible; beta / debt / cash / cost of debt / tax defaults are recorded on each analysis; detail Valuation shows `WACC x.xx%`, marks `· provisional` when assumptions were used, and lists `WACC inputs: …` (for example `beta=default`, `market cap=price×shares`)
- legacy warm-start DCF payloads without `waccInputs` still restore as non-provisional; live refresh may recompute and mark provisional from current fundamentals

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

Use `make android-release` to export an **installable release APK** to `dist/discount-screener-release.apk`. If you provide `DISCOUNT_SCREENER_RELEASE_STORE_FILE`, `DISCOUNT_SCREENER_RELEASE_STORE_PASSWORD`, `DISCOUNT_SCREENER_RELEASE_KEY_ALIAS`, and `DISCOUNT_SCREENER_RELEASE_KEY_PASSWORD` as Gradle properties, environment variables, or `local.properties` entries, the release build uses that keystore. Otherwise it falls back to debug signing so the APK is still valid for local install and device testing.

If you do not already have a release keystore, run `make android-signing-bootstrap`. That script creates one locally and writes the required `DISCOUNT_SCREENER_RELEASE_*` entries into `apps\android\local.properties` so later `make android-release` builds use your own signing identity.

## Run On Device

Use `make android-run` from the repository root to build, install, and launch the debug app. When both a USB phone and an emulator are available, the script prefers the physical device. If the phone is connected but not authorized for USB debugging, unlock it, accept the prompt, and rerun the command.
