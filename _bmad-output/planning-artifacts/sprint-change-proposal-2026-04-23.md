# Sprint Change Proposal - Valuation Change Visibility

**Date:** 2026-04-23  
**Project:** Valuation Change Visibility  
**Mode:** Batch  
**Approval basis:** User explicitly requested full-path BMAD delivery with no shortcuts and no incomplete delivery.

## 1. Issue Summary

The Android implementation delivered the movement/history foundation, but verification showed the requirement is only partially implemented. The code currently covers rank movement, weighted-target materiality badges, opportunity parity, persisted revision replay, and basic history summaries, but it does not yet deliver the full explanation layer, degraded-state language, or artifact alignment the user asked for.

### Trigger

- Implementation review after partial Android delivery
- User feedback that many requested items remain unimplemented
- Verified mismatch between BMAD artifacts and shipped behavior

### Evidence

- PRD and readiness artifacts still described v1 as tracked-only while the shipped Android app now defaults to opportunities
- Android UI lacks cause labels, no-baseline/no-meaningful-change states, before/now detail evidence, and explicit detail trust-state modules
- `HistoryMetricGroup` remains unused in the Android presentation layer

## 2. Impact Analysis

### Epic Impact

- **Epic 1** needs correction from tracked-only triage to list-surface triage across tracked and opportunity rows.
- **Epic 2** remains valid, but its scope is now confirmed as the first major missing implementation slice.
- **Epic 3** remains valid, but degraded-state coverage and evidence depth need to be made more explicit.

### Artifact Impact

- **PRD:** must align MVP scope to tracked + opportunities, separate rank contexts, and explicit explanation/degraded-state delivery.
- **UX specification:** must align hierarchy and journey language to opportunities-default triage and preserve the requirement for explanation/trust states.
- **Architecture:** must explicitly state that tracked and opportunity rows both consume the interpreted change model with separate rank contexts.
- **Epics:** must reflect list-surface scope, no-baseline/no-meaningful-change states, opportunity-specific rank baselines, and stronger degraded-state messaging.

### Technical Impact

- No rollback is required.
- Existing repository/history work can be built on directly.
- Remaining work concentrates in Android repository projection, read models, detail UI, and history degraded-state rendering.

## 3. Recommended Approach

**Selected approach:** Hybrid of **Direct Adjustment** and **Artifact Correction**

### Option Review

- **Option 1: Direct Adjustment** — **Viable**
  - Extend the current repository/read-model foundation to deliver the missing explanation and degraded-state layers.
  - Effort: Medium
  - Risk: Medium

- **Option 2: Potential Rollback** — **Not viable**
  - Rolling back opportunity-default and existing history work would destroy useful progress without simplifying the missing explanation layer.
  - Effort: High
  - Risk: High

- **Option 3: PRD MVP Review / Reduce Scope** — **Not viable**
  - The user explicitly rejected shortcuts and wants the full requested delivery.
  - Effort saved would come at the cost of failing the stakeholder request.
  - Risk: High

### Rationale

The foundation already exists and is worth preserving. The problem is not that the architecture is wrong; the problem is that the interpretation/explanation layer was not fully delivered and the artifact chain drifted behind the implementation and stakeholder direction. The best path is to correct the artifacts, then implement the missing stories in order.

## 4. Detailed Change Proposals

### PRD

- Reclassify Android opportunities from post-MVP to MVP scope.
- Add explicit MVP language for:
  - opportunities as default landing surface
  - aggressive scoring as default
  - separate opportunity vs tracked ranking/baseline semantics
  - detail explanation layer and degraded-state language
- Update scope FRs so tracked + opportunities are both in scope.

### UX Specification

- Change hierarchy language from tracked-list-only triage to list-surface triage.
- Update the primary journey to start on opportunities while preserving tracked as a peer surface.
- Preserve cause/provenance/trust requirements as first-class row/detail/history elements.

### Architecture

- Clarify that both tracked and opportunity rows consume the same interpreted semantics.
- Clarify that opportunity rank baselines remain distinct from tracked baselines.
- Preserve repository/read-model responsibility for explanation state.

### Epics / Stories

- Expand Epic 1 from tracked-only to list-surface scope.
- Explicitly require:
  - opportunity-specific rank baselines
  - no-baseline state
  - no-meaningful-change state
  - default-opportunities behavior alignment
- Strengthen Epic 2 acceptance criteria around:
  - before/now evidence
  - detail trust-state module
  - time context
- Strengthen Epic 3 degraded-state criteria around:
  - stale-only history
  - sparse revisions
  - partial refresh
  - missing analyst coverage

## 5. Implementation Handoff

### Scope Classification

**Moderate**

This does not require re-architecture or rollback, but it does require backlog/story re-sequencing and artifact correction before implementation continues.

### Handoff Sequence

1. **Sprint Planning**
   - Produce execution order for the corrected story set.
2. **Story Creation / Validation**
   - First story: detail explanation layer and list explanation parity
   - Second story: degraded-state and evidence completeness
3. **Developer Implementation**
   - Apply repository/read-model/detail/history changes
4. **Code Review + Verification**
   - Confirm requirement-complete delivery against corrected artifacts

### Success Criteria

- Cause labels are visible and trustworthy on list/detail surfaces.
- No-baseline and no-meaningful-change states are explicit.
- Detail explains what changed, by how much, since when, and why.
- History distinguishes sparse, stale-only, missing-coverage, and degraded cases.
- Tracked and opportunity surfaces share vocabulary but keep independent ranking contexts.

## 6. Final Note

This proposal keeps momentum, preserves the good foundation already delivered, and reorients the project toward the full requirement set the user actually wants shipped.
