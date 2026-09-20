# Android Agent Guide

This guide extends the root `AGENTS.md` for the Kotlin Android workstation.

## Architecture

- Keep all valuation and scoring rules in `core`.
- Keep `core` pure Kotlin.
- Keep Compose screens passive.
- Let presenters map repository state to UI state.
- Let `domain` own repository contracts and use cases.
- Let `data` own providers, profiles, SQLite, and repository implementations.
- Keep `DefaultDashboardRepository` focused on orchestration.
- Move pure interpretation from the repository to `core`.
- Do not put network, persistence, or financial rules in Compose.

## Delivery

- Use the debug APK for Juan's normal sideload.
- Keep the compatible debug signature.
- Preserve installed data.
- Do not build a custom signed release unless Juan asks.
- Do not uninstall the app or clear app data.

## Development Harness

Use this order during development:

1. `QuantHarness.hardcoded()`
2. `QuantHarness.cached()`
3. `QuantHarness.live()` only to refresh a pack

Set `DS_QUANT_LIVE=true` only for the live refresh.

Do not run device QA until Juan says the product is ready.

## Live QA

- Use `make android-run-qa`.
- Never use `make android-run` for agent QA.
- Keep the universe profile at `qa`.
- Do not clear the on-device SQLite database.
- Confirm the QA profile before the test path.

Follow [the live QA checklist](../../docs/operations/valuation-live-qa.md).

## Screen Replay

Use screen replay for model and presentation experiments.

Capture once on a device:

```text
adb shell touch /sdcard/Android/data/com.discountscreener.android/files/screen-capture/arm
adb pull /sdcard/Android/data/com.discountscreener.android/files/screen-capture/request.json
```

Replay from `apps/android`:

```text
./gradlew :core:replayScreen --args="--request=request.json"
./gradlew :core:replayScreen --args="--request=request.json --format=json --out=after.json"
```

Replay covers projection output. It does not cover providers, caches, or loading.

## Provider Tests

- Pass an `OkHttpClient` through each streaming client constructor.
- Use `offlineHttpClient()` under partial doubles.
- Use `cannedHttpClient()` for streaming response tests.
- Match the complete URL.
- Do not let any automated test reach a live provider.

SEC company facts enter through `SecCompanyFactsSieve`.

Widen the sieve before a reader needs another form, period, or field.

Bump `COMPANY_FACTS_SIEVE_VERSION` after a sieve shape change.

## Earnings And Positions

Read these current documents before related changes:

- [Earnings gate](../../docs/product/earnings-gate.md)
- [Advisor CSV import](../../docs/product/advisor-csv-import.md)
- [Android Positions](../../docs/product/android-positions.md)
- [Dip hunt](../../docs/product/android-plans-dip.md)
- [Cross hunt](../../docs/product/android-plans-cross.md)
- [Leftover hunt](../../docs/product/android-plans-leftover.md)

The policy files own numeric knobs. Kotlin must not copy those values.

The import book has one writer across Earnings, System, and Positions.

Held status uses exact ticker equality. Off-feed row taps remain no-ops.

## Checks

Run `pwsh -File scripts/validate-android.ps1` from the repository root.

The script always runs `:core:test`. It runs app tasks when the SDK exists.

Use `--rerun` after each Gradle test task that must execute again.

Check the final debug APK. Do not require a signed-release certificate check.
