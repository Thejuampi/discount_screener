# Discount Screener — Flutter

Sibling multiplatform client for **Android, iOS/iPad, and desktop (Windows/macOS/Linux)**.

This app does **not** replace:

- `apps/desktop` — Rust terminal workstation
- `apps/android` — native Kotlin/Compose client

## Layout

```text
apps/flutter/
  lib/                 # Flutter UI + presentation (MVP shell)
  packages/ds_core/    # Pure Dart domain (port of apps/android/core)
  packages/ds_data/    # Yahoo client, JSON state store, DashboardRepository
  assets/profiles/     # Same startup universes as Android
```

## Requirements

- Flutter stable (tested with Flutter 3.44 / Dart 3.12)
- For Android: Android SDK (`ANDROID_HOME` / `sdk.dir`)
- For iOS/iPad: macOS + Xcode (min **iOS 15** for iPad 2021 baseline)
- For Windows desktop: Visual Studio with **Desktop development with C++** workload

### Windows without Developer Mode

Flutter’s tool normally creates plugin **symlinks** (needs Developer Mode or an elevated shell).
This repo ships a **junction workaround** that does **not** need Developer Mode:

```powershell
# From repository root — builds a release .exe
pwsh scripts/build-flutter-windows.ps1

# Build and launch
pwsh scripts/build-flutter-windows.ps1 -Run
```

Output:

```text
apps/flutter/build/windows/x64/runner/Release/discount_screener.exe
```

Run that **folder** (not a lone copied `.exe`) — `flutter_windows.dll`, `data\`, and plugin DLLs must sit next to the binary.

If `flutter pub get` rewrites plugin deps and the next build fails with “symlink support”, re-run the script (it recreates junctions).

Install Flutter if needed, then ensure `flutter` is on `PATH`.

## Commands (Makefile — preferred)

From **repository root**:

| Command | What it does |
|---|---|
| `make flutter-test` | Full validation (`scripts/validate-flutter.ps1`) |
| `make flutter-devices` | `flutter devices` + `flutter emulators` |
| `make flutter-build-windows` | Release `.exe` via junction script (no Dev Mode) |
| `make flutter-run-windows` | Build Release **and launch** the desktop app |
| `make flutter-build-android` | Debug APK → `dist/discount-screener-flutter-debug.apk` |
| `make flutter-run-android` | Hot-reload run on a connected emulator/device |
| `make flutter-run-ios` | macOS only — iPhone simulator/device |
| `make flutter-run-ipad` | macOS only — same target family; pick iPad id if many |

### Launch each platform

**Windows (supported on this host, $0):**
```bash
make flutter-run-windows
# or build only:
make flutter-build-windows
# exe: apps/flutter/build/windows/x64/runner/Release/discount_screener.exe
# run from that folder (DLLs + data\ must sit next to the exe)
```

**Android (supported, $0):**
```bash
# once: start emulator (example AVD id)
flutter emulators --launch discount_screener_api35
# wait until `adb devices` shows emulator-XXXX device

make flutter-run-android
# or install APK:
make flutter-build-android
adb install -r dist/discount-screener-flutter-debug.apk
adb shell am start -n com.discountscreener.discount_screener/.MainActivity
```

**iPhone / iPad (free only with a free/borrowed Mac + Xcode):**
```bash
# on macOS:
make flutter-run-ios
# or:
cd apps/flutter && flutter devices && flutter run -d <ipad_or_iphone_id>
```
On Windows these targets fail by design — no free official iOS toolchain.

### Scripts / low-level

```bash
pwsh scripts/validate-flutter.ps1
pwsh scripts/build-flutter-windows.ps1        # build only
pwsh scripts/build-flutter-windows.ps1 -Run   # build + launch
cd apps/flutter && flutter analyze && flutter test
```

First launch fetches an initial batch of **~25** symbols from the selected profile via Yahoo (concurrency 3), then continues the rest of the profile in the background. Offline or empty network falls back to a seeded demo universe so the UI remains usable.

Primary list fair value prefers **analyst targets** over raw DCF (Android parity), so extreme FCF models (e.g. insurers) no longer dominate Opps/Upside.

## Status

| Layer | Status |
|---|---|
| `ds_core` models + ReportingEngine | Done |
| OpportunityEngine Legacy / Aggressive / **V2 / V3** continuous scoring | Done |
| ChartAnalysis (EMA/MACD/RSI/volume profile/replay) | Done |
| DcfAnalysisEngine + DcfSourceSelectionPolicy | Done |
| IndexEstimatesEngine | Done |
| PricingHistoryMerge | Done |
| QuantLensEngine (six-lens pack) | Done |
| Shared contract fixtures | Passing |
| Yahoo quoteSummary + chart + FCF timeseries | Done (`ds_data`) |
| JSON persistence (report + candles under app documents) | Done |
| CustomPainter price / volume / MACD / RSI charts | Done |
| Adaptive UI (phone push, ≥840 master–detail) | Done |
| Fair-value selection (analyst → DCF) | Done |
| Quant Lens coherent EV scenarios (low≤mid≤high) | Done |
| Web-safe memory store (no `dart:io` crash) | Done |
| Progressive profile load | Done |

## Architecture

Strict MVP (same as Android):

- **Views** — passive Flutter widgets under `lib/ui/`
- **Presenter** — `DashboardController` mirrors `DashboardAction` / `DashboardUiState`
- **Core** — pure scoring, ranking, DCF, charts, Quant Lens in `packages/ds_core`
- **Data** — `DashboardRepository` coordinates Yahoo, DCF, charts, and `JsonStateStore`

Adaptive layouts:

- Phone: full-screen list → push detail
- Width ≥ 840: master–detail split (tablet / iPad landscape / desktop)

Default opportunity model: **Aggressive V2**.

## Disclaimer

Educational only. Not investment advice. Data may be delayed or incomplete.
