# Implementation Artifacts Index

Use this index to find active implementation work, story files, QA outputs, and sprint state.

## Sprint State

- [Sprint Status](sprint-status.yaml) - current epic/story status for Valuation Change Visibility.
- [Company Performance Lens Sprint Status](company-performance-lens-sprint-status.yaml) - feature-specific implementation track for Company Performance Lens.

## Story And Spec Artifacts

- [Story 1.3: Explain Cause And Trust State Directly In List Surfaces](1-3-explain-cause-and-trust-state-directly-in-list-surfaces.md)
- [Android Volume Profile Replay Slice](android-volume-profile-replay-slice-2026-04-24.md)
- [Android Phone Back Navigation Spec](spec-android-phone-back-navigation.md)
- [Company Performance Lens Epics](../planning-artifacts/company-performance-lens-epics.md)

## QA Artifacts

- [Test Summary](tests/test-summary.md)

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
