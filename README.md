# Discount Screener

Discount Screener is a monorepo for two apps that rank undervalued public companies using Yahoo Finance data. Each company is evaluated through DCF analysis, analyst consensus, and a confidence-weighted forecast scoring model. Stocks are triaged into Act / Watch / Avoid buckets so you can focus on the most actionable candidates first.

## What's here

- `apps/desktop` — Rust terminal workstation with candlestick charts, MACD, EMA overlays, volume profile, and DCF analysis
- `apps/android` — Android client built with Kotlin, Gradle, and Jetpack Compose
- `shared/contracts` — shared fixtures and golden cases used by both apps

## Android screenshots

<table>
  <tr>
    <td align="center"><img src="docs/screenshots/android/opportunities.png" width="220" alt="Opportunities tab"/></td>
    <td align="center"><img src="docs/screenshots/android/candidates.png" width="220" alt="Upside candidates tab"/></td>
    <td align="center"><img src="docs/screenshots/android/detail.png" width="220" alt="Ticker detail — chart"/></td>
  </tr>
  <tr>
    <td align="center">Opportunities — ranked triage</td>
    <td align="center">Upside candidates — scored list</td>
    <td align="center">Ticker detail — candlestick &amp; signals</td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/android/detail-valuation.png" width="220" alt="Ticker detail — valuation map"/></td>
    <td align="center"><img src="docs/screenshots/android/history.png" width="220" alt="History tab"/></td>
    <td></td>
  </tr>
  <tr>
    <td align="center">Valuation map &amp; fundamentals</td>
    <td align="center">Saved price history</td>
    <td></td>
  </tr>
</table>

## Desktop screenshots

| Candidates list | Ticker detail |
|---|---|
| ![Candidates list](apps/desktop/docs/screenshots/main.png) | ![Ticker detail](apps/desktop/docs/screenshots/ticker-details.png) |

## Commands

All commands run from the repository root via `make`.

### Desktop

| Command | Purpose |
|---|---|
| `make run` | Run the terminal workstation |
| `make desktop-test` | Run unit tests |
| `make desktop-smoke` | Non-interactive smoke check (no live network needed) |
| `make desktop-release` | Build optimised release binary |

### Android

| Command | Purpose |
|---|---|
| `make android-run` | Deploy and launch on a connected device or emulator |
| `make android-test` | Run unit tests |
| `make apk` | Build debug APK → `dist/discount-screener-debug.apk` |
| `make android-release` | Build signed release APK → `dist/discount-screener-release.apk` |
| `make android-signing-bootstrap` | First-time release-signing setup |

### Cross-platform

| Command | Purpose |
|---|---|
| `make contracts-test` | Validate shared fixture contracts across both apps |

## Requirements

- **Desktop:** Rust toolchain (`rustup` + `cargo`)
- **Android:** JDK 17+, an Android SDK — set `ANDROID_HOME` or add `sdk.dir=<path>` to `apps/android/local.properties`

## Documentation

- [Desktop README](apps/desktop/README.md)
- [Android README](apps/android/README.md)
- [Shared contracts](shared/contracts/README.md)
- [Quick Start](docs/QUICK_START.md)
- [User Manual](docs/USER_MANUAL.md)

---

Not investment advice. Data may be delayed or incomplete.