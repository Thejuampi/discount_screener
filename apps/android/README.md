# Discount Screener Android

This directory contains the native Android client for Discount Screener.

## Shape

- `core/` is the pure Kotlin engine/model layer and contract-test target.
- `app/` is the Android app with explicit `app`, `domain`, `data`, `presentation`, and `ui` packages.

## Architecture

- `app/` — composition root and Android entrypoints
- `domain/` — repository contracts and use cases
- `data/` — Yahoo client, profile loading, persistence, and repository implementation
- `presentation/` — `DashboardViewModel`, UI state, and actions
- `ui/` — Compose screens, dialogs, and detail/chart components

## Current implementation

- live candidate and opportunity reporting
- symbol detail reporting with EMA/price/MACD charts, bull-bear crossover cues, valuation, consensus, evidence, alerts, and chart range selection
- local warm-start persistence for tracked symbols, watchlist, issues, chart cache, and revision history
- operator surfaces for candidates, opportunities, watchlist, issues, and symbol detail
- startup splash during warm restore plus a one-time disclaimer acceptance gate before entering the app

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

Use `make apk` from the repository root to export an **installable debug APK** to `dist/discount-screener-debug.apk`. The `make android-release` target still runs `assembleRelease`, which produces `app-release-unsigned.apk` unless you add your own release signing configuration.

## Run On Device

Use `make android-run` from the repository root to build, install, and launch the debug app. When both a USB phone and an emulator are available, the script prefers the physical device. If the phone is connected but not authorized for USB debugging, unlock it, accept the prompt, and rerun the command.
