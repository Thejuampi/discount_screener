# SEC Primary Evidence Gate

Date: 2026-05-04
Status: SEC primary remains disabled by default
Owner: Android data / DCF source policy

## Decision

SEC EDGAR is not enabled as a default Android DCF source in this slice. The app default wiring keeps the secondary provider absent, and the pure policy can represent SEC only when a provider is explicitly injected and passes quality gates.

## Gate

SEC primary must not be enabled until a sample artifact records at least five live upstream samples covering:

- large-cap supported US equity
- sparse Yahoo free-cash-flow history
- SEC-supported US equity
- unsupported or non-US symbol
- materially divergent Yahoo/SEC annual free-cash-flow series

Each sample must record provider payload reference or raw-capture id, accepted annual periods, rejected periods, selected provider, rejected-provider reasons, overlap deltas, and reviewer decision.

## Current Evidence

No live SEC-primary promotion evidence is recorded for this slice. This is intentional: the slice implements the source-policy contract and keeps the release default conservative after runtime OOM was observed in Android SEC companyfacts parsing.

## Release Rule

A future change may enable SEC by default only when the five-sample artifact exists, all samples pass review, Android manual QA remains stable, and `scripts/validate-android.ps1` passes.
