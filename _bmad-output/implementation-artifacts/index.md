# Implementation Artifacts Index

Use this index to find active implementation work, story files, QA outputs, and sprint state.

## Sprint State

- [Sprint Status](sprint-status.yaml) - current epic/story status for Valuation Change Visibility.
- [Company Performance Lens Sprint Status](company-performance-lens-sprint-status.yaml) - feature-specific implementation track for Company Performance Lens.

## Story And Spec Artifacts

- [Dip board spec v1](dip-board-spec-v1.md) - Android Plans Dip hunter. Locked cuts after Sensei + Advisor. Does not change V2/V3/V4.
- [Leftover board spec v1](leftover-board-spec-v1.md) - Android Plans leftover review. Profile universe, Street leftover ≤ 5%, fade latch. Does not change V2/V3/V4.

- [Story 1.3: Explain Cause And Trust State Directly In List Surfaces](1-3-explain-cause-and-trust-state-directly-in-list-surfaces.md)
- [Android Volume Profile Replay Slice](android-volume-profile-replay-slice-2026-04-24.md)
- [Android Phone Back Navigation Spec](spec-android-phone-back-navigation.md)
- [Company Performance Lens Epics](../planning-artifacts/company-performance-lens-epics.md)

## QA Artifacts

- [Test Summary](tests/test-summary.md)

## Active quant / valuation continuity

- **[`apps/windows/src-tauri/valuation-core/`](../../apps/windows/src-tauri/valuation-core/)** — the pure kernel replacing the valuation modules. `tests/features/*.feature` is the contract, not documentation of it; `tests/schema.rs` enforces the table discipline. The Shell's `dcf_model`, `operating_valuation`, and `driver_resolution` now carry deprecation banners naming what each is being replaced for. The old engine keeps shipping until the core carries the behaviour.

- **[Handover — Quant Valuation Engine 2026-08-02](handover-quant-valuation-engine-2026-08-02.md)** — **start here for the next agent** on the quant motor: state, shipped work, CHTR FCFF blocker, P0–P7, gates, module map.
- [Multi-name valuation baseline policy](valuation-multi-name-baseline-policy.md) - `valuation_baseline` merge bar + high-signal cohort + gap-attribution telemetry notes.
- [Deferred Work](deferred-work.md) - includes quant P0/open process items from the handover.
- [AMZN owner-earnings fix notes](fix-amzn-owner-earnings-vs-street-2026-08-01.md) - why policy/15 OE path exists (do not undo blindly while fixing cable CapEx).

## Learning And Retrospective Artifacts

- **[Valuation agent failure modes](valuation-agent-failure-modes.md)** — read before touching the router, the payout policy, or any valuation gate. Concrete ways this work has gone wrong, with the tells.
- [Valuation Automation Learning Ledger](valuation-automation-learning-ledger.md) - living, evidence-linked lessons for analyst-method automation; implementation is still in progress.
- [Valuation Calibration Retrospective](retro-valuation-calibration-session-2026-07-30.md) - multi-name baseline, fail-closed routing, and operational QA lessons.
- [Analyst-Method Automation Pre-1C Retrospective](retro-analyst-method-automation-pre-1c-2026-08-02.md) - autonomous team retro; independent closure protocol and the publication-readiness 1B.3 gate.
- [Quant Method Mathematical Specification and Redesign](quant-method-mathematical-specification-2026-08-03.md) - the shipped valuation math in closed form, the 62 hand-fitted constants it rests on, and a measured-parameter replacement.

## Story Workflow

1. Check `sprint-status.yaml`.
2. Open the next story or spec artifact.
3. Verify acceptance criteria against planning docs.
4. Implement with strict TDD.
5. Run required validation commands.
6. Add live QA notes when the installed app surface changes.
7. Update `sprint-status.yaml`.
8. Run code review and retrospective when appropriate.

## Maintenance

- Add new story/spec files here when they are created.
- Keep statuses in `sprint-status.yaml`; this file is navigation, not state.
- Put generated QA reports under `tests/` and link them from this index.
