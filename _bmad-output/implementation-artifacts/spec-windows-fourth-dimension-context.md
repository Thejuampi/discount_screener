---
title: 'Expose market context as the fourth Windows scoring dimension'
type: 'feature'
created: '2026-07-23'
status: 'done'
baseline_commit: 'e35371767673b90365aacedb148cc55d34d992d4'
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/project-context.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Windows V3 can include market-regime fit in its composite, but the list and detail surfaces still hide or ambiguously describe that fourth dimension. The current row boolean also collapses disabled, unavailable, and not-applicable states, and Short V3 double-inverts the regime score so favorable short context can reduce the bearish composite.

**Approach:** Make market context an explicit, typed fourth dimension for V3 equities, present its actual effect as `3D base + context adjustment = final`, and use deterministic bilingual copy and degraded states. Preserve classic three-dimensional behavior when context is not included and keep History labeled with its existing V2 semantics.

## Boundaries & Constraints

**Always:** Preserve fixed-point score fields and Long/Short presentation semantics; derive the displayed adjustment from `composite_score - composite_score_base`; treat a raw regime score of zero as included; use +15/-15 for favorable/neutral/adverse presentation; show no more than three explanatory causes; keep Spanish and English equivalent; preserve legacy payload compatibility at the frontend boundary.

**Ask First:** Any change to V3 weights, coverage bonus, decision thresholds, persistence schema, History scoring, provider fetching, or non-Windows clients.

**Never:** Describe regime fit as a market-direction forecast, claim all four dimensions align merely because the final decision is Act, equate raw regime score with final-score impact, show the fourth dimension for V2/ETF/crypto, or hide disabled/unavailable V3 equity states.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|---------------|----------------------------|----------------|
| Included Long | V3 equity, valid policy and symbol coverage | Fourth pip/card, classified score, top causes, and base/adjustment/final composition | Missing causes do not hide the included score |
| Included Short | Short V3 equity with positive short-fit | Positive context score supports rather than penalizes the bearish composite and uses bearish-position copy | Do not double-invert the score |
| Disabled | V3 equity, global toggle off | Fourth slot remains visible and says the score uses three dimensions | Final equals base |
| Unavailable | V3 equity, cold/low-confidence regime or sparse symbol data | Fourth slot remains visible with an explicit unavailable explanation | Final equals base; no fabricated score |
| Not applicable | V2 or ETF/crypto | No fourth slot/card | Technical-only and V2 behavior remain unchanged |
| Legacy payload | New status field absent | Infer Included only from legacy true/score-present data; otherwise choose a conservative model-aware state | Never mislabel V2 as four-dimensional |

</frozen-after-approval>

## Code Map

- `apps/windows/src-tauri/src/commands.rs` -- authoritative row state resolution and Long/Short composite assembly.
- `apps/windows/src-tauri/src/opportunity_v3.rs` and `src-tauri/src/regime/` -- extended composite and regime-fit semantics.
- `apps/windows/src/api.ts` -- typed wire contract with legacy-compatible optional fields.
- `apps/windows/src/regimePresentation.ts` -- pure normalization, classification, impact, cause-selection, and copy-key view model.
- `apps/windows/src/components/OpportunityList.tsx` and `DetailPanel.tsx` -- list/detail rendering from the shared view model.
- `apps/windows/src/i18n.tsx` and `scoringPresentationMessages.ts` -- deterministic ES/EN labels and Long/Short explanations.
- `apps/windows/tests/` -- backend-independent presentation contracts and existing model-aware semantic tests.

## Tasks & Acceptance

**Execution:**
- [x] Add failing Rust tests for the four row states, exact 3D parity outside Included, zero-score inclusion, and Short context orientation; then implement the typed resolver and correct Short assembly.
- [x] Add failing TypeScript tests for legacy normalization, ±15 classification, base-to-final impact, state copy, Long/Short language, and three-cause limit; then implement `regimePresentation.ts`.
- [x] Route the opportunity list and detail summary, rationale, metadata, and fourth card through the shared view model; add 4→2→1 responsive layouts and wrapping metadata.
- [x] Replace jargon-heavy or false-alignment copy, add explicit status labels and the non-forecast explanation, and update Windows scoring documentation.

**Acceptance Criteria:**
- Given any V3 equity, when list and detail render, then the fourth dimension is visible as Included, Disabled, or Unavailable and the displayed final-score adjustment is arithmetically correct.
- Given V2, ETF, or crypto, when the same surfaces render, then market context is NotApplicable and no fourth dimension appears.
- Given positive regime fit under Short V3, when the composite and narrative are produced, then it strengthens the bearish perspective and never becomes long-entry guidance.
- Given a narrow detail viewport, when four-dimensional analysis renders, then cards form 2x2 and then one column without clipping or non-wrapping score metadata.

## Spec Change Log

## Design Notes

Primary visible terminology is **Market context / Contexto de mercado**; **Regime / Régimen** remains a secondary technical term. The score header uses the factual composition `Base V3 (3D) 46 · Context +5 · Final 51`. The card explains that fit compares company quality, valuation, beta, sector, and extension with the active environment; it does not predict market direction.

## Verification

**Commands:**
- `npm test` and `npm run build` from `apps/windows` -- all presentation contracts and TypeScript build pass.
- `cargo fmt --check` and `cargo test` from `apps/windows/src-tauri` -- formatting and full Rust suite pass.
- Focused mutation checks around state resolution, ±15 boundaries, impact direction, and Short inversion -- incorrect variants are killed.

**Manual checks:**
- Inspect MU in Long V3 with context Included, Disabled, and Unavailable; verify ES/EN, wide/2x2/narrow layouts, and matching arithmetic.

## Suggested Review Order

**Authoritative scoring contract**

- Start with the typed four-state resolver and row-level scoring orchestration.
  [`commands.rs:37`](../../apps/windows/src-tauri/src/commands.rs#L37)

- Verify Short context returns through inverse-V3 space without double inversion.
  [`opportunity_v3.rs:636`](../../apps/windows/src-tauri/src/opportunity_v3.rs#L636)

- Confirm startup warming and cache lifetime prevent deterministic unavailable gaps.
  [`App.tsx:103`](../../apps/windows/src/App.tsx#L103)

**Presentation boundary**

- Review legacy normalization, score-impact arithmetic, thresholds, and side-aware causes.
  [`regimePresentation.ts:52`](../../apps/windows/src/regimePresentation.ts#L52)

- Inspect the summary composition and actual context-impact explanation.
  [`DetailPanel.tsx:444`](../../apps/windows/src/components/DetailPanel.tsx#L444)

- Inspect explicit Included, Disabled, and Unavailable fourth-card rendering.
  [`DetailPanel.tsx:563`](../../apps/windows/src/components/DetailPanel.tsx#L563)

- Confirm V3 list rows expose the fourth indicator while V2 remains three-dimensional.
  [`OpportunityList.tsx:164`](../../apps/windows/src/components/OpportunityList.tsx#L164)

**Contracts and verification**

- Check optional wire fields preserve compatibility with older payloads.
  [`api.ts:37`](../../apps/windows/src/api.ts#L37)

- Review boundary, degraded-state, impact, and bilingual Short-cause tests.
  [`regimePresentation.test.ts:13`](../../apps/windows/tests/regimePresentation.test.ts#L13)

- Verify exact Short arithmetic and three-dimensional parity tests.
  [`opportunity_v3.rs:740`](../../apps/windows/src-tauri/src/opportunity_v3.rs#L740)

- Confirm operator documentation distinguishes context fit from market forecasting.
  [`README.md:50`](../../README.md#L50)
