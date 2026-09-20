# Flutter Agent Guide

This guide extends the root `AGENTS.md` for the Flutter client.

## Architecture

- Keep widgets passive.
- Keep presentation state in `DashboardController`.
- Keep portable rules in `packages/ds_core`.
- Keep Yahoo and persistence in `packages/ds_data`.
- Keep platform startup code thin.
- Preserve adaptive phone and master-detail layouts.
- Do not add a second rule when a shared contract owns the behavior.

## Parity

- Read contracts from `shared/contracts`.
- Keep fixture interpretation aligned with the other clients.
- Keep unavailable and disputed states explicit.
- Do not let extreme model values replace analyst-first list fair value.
- Treat Flutter as a supported client, not a prototype.

Read these documents before related work:

- [Valuation architecture](../../docs/architecture/valuation-model-family.md)
- [Cross-platform parity](../../docs/architecture/cross-platform-parity.md)
- [Current functionality](../../docs/product/current-functionality.md)

## Checks

Run this command from the repository root:

`pwsh -File scripts/validate-flutter.ps1`

The script runs package tests, `flutter analyze`, and application tests.

Use `make flutter-build-windows` for a Windows build.

Use `make flutter-build-android` for an Android debug APK.
