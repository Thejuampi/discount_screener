---
title: "Story 1.1: Represent Performance Lens States Without Invalid Or Hidden Data States"
status: "done"
created: "2026-04-26T00:00:00-04:00"
updated: "2026-04-26T00:00:00-04:00"
sourceStory: "_bmad-output/planning-artifacts/company-performance-lens-epics.md#story-11-represent-performance-lens-states-without-invalid-or-hidden-data-states"
---

# Story 1.1: Represent Performance Lens States Without Invalid Or Hidden Data States

## Scope

Implemented the Android core domain model for the Company Performance Lens state surface. This slice is model-only and does not add UI rendering, persistence, provider fetching, or DCF computation.

## Completed Tasks

- Added serializable domain types for trajectory, confidence, provenance, evidence rows, scorecard sections, risk flags, decision readiness, and the top-level `PerformanceLens`.
- Preserved fixed-point financial field conventions with `valueCents`, `valueBps`, `valueHundredths`, and `valueMillis`.
- Added constructor validation for invalid combinations:
  - available evidence requires available provenance
  - unavailable evidence must use unavailable direction
  - high confidence requires at least one available evidence row
  - decision readiness requires available provenance except `TooSparseToJudge`
  - non-info risk flags require evidence references
- Added serialization and validation tests for degraded states and invalid-state rejection.

## Code Map

- `apps/android/core/src/main/kotlin/com/discountscreener/core/model/Models.kt`
- `apps/android/core/src/test/kotlin/com/discountscreener/core/model/PerformanceLensModelTest.kt`

## Verification

- `./gradlew :core:test --tests com.discountscreener.core.model.PerformanceLensModelTest` passed.
- `./gradlew :core:test` passed.
- Manual mutation: changed the high-confidence validation condition so the invalid-state test no longer threw; `rejects_confident_trajectory_without_available_evidence` failed as expected. The mutation was reverted and `./gradlew :core:test` passed again.

## Notes

The formal BMad blocker before implementation was that no story file had been created or validated. This implementation proceeded from the approved Epic 1 Story 1.1 scope after the readiness-assessment fixes were applied to the epics document.
