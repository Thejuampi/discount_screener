# Discount Screener

Discount Screener is a monorepo for two apps that surface undervalued companies using Yahoo Finance data.

## What’s here

- `apps/desktop` - Rust terminal workstation
- `apps/android` - Android client built with Kotlin, Gradle, and Jetpack Compose
- `shared/contracts` - shared fixtures and golden cases used by both apps

## How to run it

From the repository root:

- Desktop validation: `pwsh ./scripts/validate-desktop.ps1`
- Desktop app: `cargo run --manifest-path apps/desktop/Cargo.toml`
- Desktop smoke check: `cargo run --manifest-path apps/desktop/Cargo.toml -- --smoke`
- Android validation: `pwsh ./scripts/validate-android.ps1`
- Android launch: `pwsh ./scripts/launch-android.ps1`
- Shared contract checks: `pwsh ./scripts/validate-contracts.ps1`

## Requirements

- Desktop: Rust toolchain
- Android: JDK 17+ and an external Android SDK

## More details

- [Desktop README](apps/desktop/README.md)
- [Android README](apps/android/README.md)
- [Shared contracts README](shared/contracts/README.md)

Not investment advice. Data may be delayed or incomplete.
