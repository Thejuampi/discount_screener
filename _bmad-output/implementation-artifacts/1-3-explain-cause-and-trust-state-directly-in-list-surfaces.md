# Story 1.3: Explain cause and trust state directly in list surfaces

Status: done

<!-- Note: Validation is optional. Run validate-create-story for quality check before dev-story. -->

## Story

As an analyst,  
I want tracked and opportunity list surfaces to tell me why a symbol moved and how trustworthy that explanation is,  
so that I can decide whether to drill in or wait for better data.

## Acceptance Criteria

1. Given a tracked or opportunity symbol changed materially for a dominant reason, when the row is rendered, then it shows one cause label from the approved vocabulary and the label matches the underlying change assessment.  
2. Given more than one material driver changed or attribution is weak, when the row is rendered, then the row shows `Combined move` or an equivalent degraded explanation and does not overstate precision.  
3. Given the row is restored, stale, partially refreshed, or missing analyst coverage, when the row is rendered, then the provenance or degraded-state indicator explains the limitation and unavailable explanation components are suppressed rather than fabricated.  
4. Given a symbol is comparable but did not move meaningfully, when the row is rendered, then the UI can express `No meaningful change` or a similarly quiet neutral state and avoids implying significance that is not present.  
5. Given a symbol has no comparable prior baseline, when change metadata is built, then the row exposes `No baseline` and does not emit a misleading movement explanation.  
6. Given both tracked and opportunity rows are rendered for the same semantic state, when the UI shows cause/trust semantics, then the vocabulary remains consistent while tracked and opportunity rank contexts remain separate.  
7. Given provenance is shown on a row, when the user scans quickly, then the row also includes quiet time context so the user can tell **since when** the trust state applies.

## Tasks / Subtasks

- [x] Extend the app-layer read models with explicit list-surface explanation semantics. (AC: 1, 2, 3, 4, 5, 6, 7)
  - [x] Add canonical row-level explanation models for cause attribution, baseline availability, and quiet/no-meaningful-change states in `apps/android/app/src/main/kotlin/com/discountscreener/android/domain/model/DashboardSnapshot.kt`.
  - [x] Keep the vocabulary deterministic and aligned with the approved labels from UX/architecture: `Price moved`, `Target changed`, `Relative re-rank`, `Combined move`, `No baseline`, `No meaningful change`.
  - [x] Do not let Compose derive attribution semantics ad hoc from raw values.
- [x] Compute cause/trust semantics in the repository projection layer. (AC: 1, 2, 3, 4, 5, 6)
  - [x] Extend `DefaultDashboardRepository` to build row-level explanation metadata for both tracked and opportunity rows.
  - [x] Reuse the existing baseline maps and movement helpers; do not duplicate rank-baseline logic.
  - [x] Preserve separate tracked vs opportunity ranking semantics and aggressive/legacy opportunity model separation.
  - [x] Add baseline-aware degraded states for: no baseline, partial refresh, stale-only, missing analyst coverage, and weak attribution.
- [x] Add list-surface UI for cause, provenance, and quiet time context. (AC: 1, 2, 3, 4, 7)
  - [x] Update `DashboardLists.kt` so tracked rows and opportunity rows both render cause/trust labels without overwhelming the row.
  - [x] Keep the row order aligned with UX: identity, primary change, rank movement, cause, provenance + time, supporting metrics.
  - [x] Ensure `No meaningful change` is visually quiet and does not compete with significant movement badges.
  - [x] Ensure provenance/status styling does not compete with valuation direction styling.
- [x] Add tests that lock the story behavior down. (AC: 1, 2, 3, 4, 5, 6, 7)
  - [x] Add pure/repository-level tests for attribution selection, no-baseline handling, no-meaningful-change handling, and separate tracked/opportunity rank contexts.
  - [x] Add UI/helper tests for badge/copy ordering and quiet degraded-state behavior where practical.
  - [x] Cover at least: dominant target change, dominant price move, relative re-rank, combined move, missing baseline, and partially refreshed/stale states.
- [x] Update status/docs after implementation. (AC: 6, 7)
  - [x] Update `_bmad-output/implementation-artifacts/sprint-status.yaml` status for this story during execution.
  - [x] Update Android-facing documentation only if user-visible chip/copy behavior changes materially.

## Dev Notes

- This story is the missing **list explanation layer**. The movement/freshness foundation exists, but cause labels, no-baseline, no-meaningful-change, and relative-time trust context are still missing.
- Keep semantics upstream in the repository/read-model layer. The architecture explicitly says Compose must render already-interpreted state rather than infer materiality, attribution, or freshness on its own.
- The next detail story (2.1 / 2.2) should reuse the explanation semantics established here. Design this story to avoid rework in detail.
- Do not collapse tracked and opportunity ranking contexts. Opportunity rows already use model-sensitive baseline maps and must stay separate from tracked order.

### Project Structure Notes

- Business semantics belong in:
  - `apps/android/app/src/main/kotlin/com/discountscreener/android/domain/model/DashboardSnapshot.kt`
  - `apps/android/app/src/main/kotlin/com/discountscreener/android/data/repository/DefaultDashboardRepository.kt`
- Row rendering belongs in:
  - `apps/android/app/src/main/kotlin/com/discountscreener/android/ui/dashboard/DashboardLists.kt`
- Avoid pushing Android-specific badge rendering semantics into `apps/android/core` opportunistically unless the logic is genuinely portable and kept UI-agnostic.

### Relevant Existing Code

- `DashboardSnapshot.kt`
  - already contains `RowFreshness`, `RankMovement`, `ValuationChange`, `TrackedSymbolRow`, and `OpportunityListRow`
  - current gap: no explicit cause-attribution model, no no-baseline model, no no-meaningful-change model, no row-level time context
- `DefaultDashboardRepository.kt`
  - already captures tracked and opportunity baselines separately
  - already computes row freshness via `rowFreshnessFor(...)`
  - current gap: rows expose movement and freshness, but not *why* the move happened
- `DashboardLists.kt`
  - currently renders freshness, watch, rank, and valuation-change badges
  - current gap: no cause label, no quiet neutral state, no provenance time context

### Testing Notes

- Prefer focused helper tests for attribution and degraded-state selection rather than broad fragile UI assertions.
- Reuse the existing Android validation path: `scripts/validate-android.ps1`.
- Existing repository tests already cover:
  - baseline rank/valuation movement
  - historical replay from persisted revisions
  - freshness mapping helper
- Extend those patterns instead of inventing a new test style.

### Risks / Guardrails

- **Risk:** inventing causal labels from weak signals.  
  **Guardrail:** default to `Combined move` or degraded/no-baseline states instead of false precision.
- **Risk:** inconsistent tracked vs opportunity semantics.  
  **Guardrail:** centralize attribution selection in shared app-layer helpers.
- **Risk:** overloading rows with too many equal-weight chips.  
  **Guardrail:** preserve UX order and quiet styling for neutral/degraded states.
- **Risk:** using timestamps or freshness text in a noisy way.  
  **Guardrail:** provenance time context must be visually quiet and subordinate to the primary change cue.

### References

- Epic 1 / Story 1.3 acceptance criteria and scope [Source: `_bmad-output/planning-artifacts/epics.md`, Story 1.3]
- Change attribution / degraded-state requirements [Source: `_bmad-output/planning-artifacts/prd.md`, FR13-FR16, FR28-FR32]
- Approved label vocabulary and row hierarchy [Source: `_bmad-output/planning-artifacts/ux-design-specification.md`, Badge and Chip Language; Screen-Level Hierarchy]
- Canonical architecture value objects and repository projection rule [Source: `_bmad-output/planning-artifacts/architecture.md`, Canonical Value Objects; API and Communication Patterns]
- Current implementation seams [Source: `apps/android/app/src/main/kotlin/com/discountscreener/android/domain/model/DashboardSnapshot.kt`; `apps/android/app/src/main/kotlin/com/discountscreener/android/data/repository/DefaultDashboardRepository.kt`; `apps/android/app/src/main/kotlin/com/discountscreener/android/ui/dashboard/DashboardLists.kt`]

## Dev Agent Record

### Agent Model Used

GPT-5.4

### Debug Log References

- `_bmad-output/planning-artifacts/sprint-change-proposal-2026-04-23.md`
- `_bmad-output/implementation-artifacts/sprint-status.yaml`

### Completion Notes List

- Added canonical list-surface explanation semantics (`RowExplanationKind`) plus quiet trust/timestamp context to tracked and opportunity rows.
- Captured baseline market price alongside baseline rank and weighted target so the repository can distinguish price-led, target-led, combined, no-baseline, and quiet/no-meaningful-change states.
- Reworked dashboard row badges so opportunities and tracked lists now share cause, freshness, trust, and relative-time vocabulary while keeping separate ranking contexts.
- Added focused repository and UI helper tests, and confirmed they fail under manual mutations before restoring the correct implementation.

### File List

- `apps/android/app/src/main/kotlin/com/discountscreener/android/domain/model/DashboardSnapshot.kt`
- `apps/android/app/src/main/kotlin/com/discountscreener/android/data/repository/DefaultDashboardRepository.kt`
- `apps/android/app/src/main/kotlin/com/discountscreener/android/ui/dashboard/DashboardLists.kt`
- `apps/android/app/src/test/kotlin/com/discountscreener/android/data/repository/DefaultDashboardRepositoryTest.kt`
- `apps/android/app/src/test/kotlin/com/discountscreener/android/ui/dashboard/DashboardListsTest.kt`
- `apps/android/README.md`
