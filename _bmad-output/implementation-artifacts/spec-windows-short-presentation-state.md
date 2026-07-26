---
title: 'Model-aware Windows scoring presentation'
type: 'bugfix'
created: '2026-07-21'
status: 'done'
baseline_commit: '175bbc828a119eef25dd8c0001c0c818a1be0914'
context:
  - '{project-root}/AGENTS.md'
  - '{project-root}/_bmad-output/project-context.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Windows `short_v3` correctly inverts scores, but several visible surfaces still describe an `Act` row as a buying opportunity, treat analyst upside as favorable, and present bullish technical evidence as a reason to enter. This can reverse the intended financial meaning and is a material trust failure.

**Approach:** Introduce an explicit, typed presentation state per scoring model. Long and Short states will own their terminology and directional interpretation methods, while objective market facts remain unchanged; every affected Windows surface will consume the same state instead of composing ad hoc text.

## Boundaries & Constraints

**Always:** Keep backend formulas, bucket inversion, decision thresholds, and wire tokens unchanged. In Short state, `Act` means a candidate to sell short/bet against, never a buy; bullish evidence and analyst upside are risks to the thesis, while bearish evidence and price above analyst target may support it. Keep analyst recommendations explicitly external, preserve missing/stale/conflicting data, support Spanish and English, and include an asymmetric-risk reminder without implying guaranteed profit.

**Ask First:** Any change to scoring weights, decision cutoffs, persistence, provider parsing, brokerage execution, borrow availability integration, or other platforms.

**Never:** Implement global string replacement, infer a short profit target from the analyst target, hide contradictory evidence, relabel Wall Street Buy/Sell opinions as Vantage recommendations, or present Short as a purchase/investment opportunity.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Long actionable | Long model, `Act`, positive gap | Existing buy-oriented thesis and favorable upside presentation remain | Missing values stay explicit |
| Short actionable, supportive target | Short, `Act`, negative gap | Actionable short thesis; price above target is a downside reference supporting the thesis | Never call it a discount or buy |
| Short actionable, contradictory target | Short, `Act`, positive gap | Still identifies the score-based short candidate, but labels analyst upside as contrary risk | Do not turn target into short profit target |
| Short watch/avoid | Short, `Watch` or `Avoid` | “Wait for bearish confirmation” or “Do not short”; no entry/buy wording | Preserve the underlying facts |
| Sparse or low-trust data | Short with null target/buckets or low confidence | State what cannot be validated and what evidence remains; show short-risk warning | No fabricated rationale |
| Technical evidence | Short with bearish/bullish/neutral verdict | Bearish signals support the short; bullish signals are squeeze/thesis risk; neutral means no edge | Insufficient data remains insufficient |
| Crypto/ETF | Short plus non-equity asset | Use technical short wording without equity valuation claims | Missing forecast remains visible |
| Live model switch | Detail open while model changes | List, price comparison, decision card, buckets, and technical action copy rerender together | No stale long narrative under Short |

</frozen-after-approval>

## Code Map

- `apps/windows/src/scoringPresentation.ts` -- typed Long/Short presentation states and pure directional interpretation methods.
- `apps/windows/src/App.tsx` -- owns the selected model and passes it into every scoring-aware surface.
- `apps/windows/src/components/OpportunityList.tsx` -- model-aware target/gap labels, tooltips, and favorable/adverse styling.
- `apps/windows/src/components/DetailPanel.tsx` -- model-aware price comparison, decision header, summary, rationale, bucket framing, DCF tone, and risk copy.
- `apps/windows/src/components/TechnicalAnalysisPanel.tsx` -- objective verdict plus model-aware action hint and supporting/risk signal grouping.
- `apps/windows/src/i18n.tsx` -- complete ES/EN Long and Short vocabulary.
- `apps/windows/tests/scoringPresentation.test.ts` -- policy tests for decisions, target direction, technical direction, sparse data, assets, and forbidden wording.
- `apps/windows/package.json` -- runs all Windows TypeScript tests.
- `README.md` -- documents that Short is a bearish screening perspective, not a buy recommendation.

## Tasks & Acceptance

**Execution:**
- [x] `apps/windows/tests/scoringPresentation.test.ts` -- add failing semantic contract tests before implementation.
- [x] `apps/windows/src/scoringPresentation.ts` -- implement the typed state/policy boundary.
- [x] `apps/windows/src/App.tsx`, `apps/windows/src/components/OpportunityList.tsx`, `apps/windows/src/components/DetailPanel.tsx`, `apps/windows/src/components/TechnicalAnalysisPanel.tsx` -- route every affected presentation through the active state.
- [x] `apps/windows/src/i18n.tsx`, `README.md` -- add deterministic bilingual copy and concise operator guidance.
- [x] `apps/windows/package.json` -- include the new tests in normal verification.

**Acceptance Criteria:**
- Given any Short-mode row or detail state, when visible copy is rendered, then it contains no recommendation to buy, invest long, or enter for upside.
- Given contradictory analyst, technical, confidence, or missing-data evidence, when the Short thesis is explained, then that evidence is surfaced as risk or unavailable evidence rather than silently converted into support.
- Given the same opportunity while switching Long V3 to Short and back, when the UI settles, then all scoring-aware wording and directional colors change coherently without changing raw values.
- Given ES or EN locale, when each decision and edge case renders, then meaning is equivalent and grammatically complete.

## Spec Change Log

## Design Notes

The presentation state is a strategy object selected by model ID. `aggressive_v2` and `aggressive_v3` share the Long strategy; `short_v3` uses the Short strategy. Components render returned presentation values and objective facts, rather than branching on model-specific prose themselves. Tests include forbidden-term assertions for Short output and golden examples for supportive versus contradictory analyst targets.

## Verification

**Commands:**
- `npm test` from `apps/windows` -- all presentation and existing unit tests pass.
- `npm run build` from `apps/windows` -- TypeScript and Vite build pass.
- `npm run lint` from `apps/windows` -- no new lint errors.
- `cargo test` from `apps/windows/src-tauri` -- Windows backend remains green.
- `npm exec tauri dev` from `apps/windows` -- live Yahoo QA confirms Long/Short switching, detail semantics, sparse rows, crypto/ETF, both locales, rapid switching, and no crashes.

**Recorded evidence (2026-07-21):**

- TDD RED: the first `node --test tests/scoringPresentation.test.ts` run failed because the presentation policy module did not exist; implementation followed only after the semantic contracts were locked.
- Mutation checks killed three incorrect implementations: Long-style DCF coloring under Short, analyst-upside marked favorable under Short, and positive fundamentals framed as support for a short. The source was restored and the suite returned green after each kill.
- Live Tauri/WebView2 QA used real upstream rows for VSH (supportive downside), NOVT and TSLA (contradictory analyst upside), UEC (missing target), NTLA (Watch), NBTB (Avoid), DOGE-USD (crypto), and TLT (ETF), in Spanish and English.
- Rapid Short → Long V2 → Short → Long V3 switching produced no observed model/banner/row mismatch; the final committed snapshot was Long V3. Switching back to Short atomically restored bearish labels and inverted raw scores.
- Live crypto and ETF details rendered only technical evidence. DOGE's bullish cycle score appeared as rebound/squeeze risk against the short, with no entry/buy language or equity valuation sections.
- Screenshots: `.agents/workspace/tmp/windows-short-presentation/short-vsh-support-es.png`, `.agents/workspace/tmp/windows-short-presentation/short-tsla-risk-en-final2.png`, and `.agents/workspace/tmp/windows-short-presentation/short-tsla-risk-es-final.png`.
- `npm run lint` remains non-zero because of the pre-existing repository baseline documented in `deferred-work.md`; focused lint for the new policy/tests and modified App/list/technical/alert files is clean.

## Suggested Review Order

**Presentation strategy**

- Start with the typed policy boundary shared by every scoring-aware surface.
  [`scoringPresentation.ts:49`](../../apps/windows/src/scoringPresentation.ts#L49)

- Inspect Short's directional decisions, evidence framing, targets, and technical interpretation.
  [`scoringPresentation.ts:231`](../../apps/windows/src/scoringPresentation.ts#L231)

- Review deterministic Spanish and English vocabulary, including crypto and risk copy.
  [`scoringPresentationMessages.ts:3`](../../apps/windows/src/scoringPresentationMessages.ts#L3)

**Atomic model/data flow**

- Verify rapid changes serialize backend model selection and atomically commit matching rows.
  [`App.tsx:159`](../../apps/windows/src/App.tsx#L159)

- Confirm native alerts isolate state by model and translate setup tokens correctly.
  [`useSignalAlerts.ts:35`](../../apps/windows/src/useSignalAlerts.ts#L35)

- Confirm long-oriented email digests cannot consume inverted Short rows.
  [`useEmailNotifications.ts:27`](../../apps/windows/src/useEmailNotifications.ts#L27)

**Screener and detail semantics**

- Review model-aware list labels, visible direction, colors, sparse states, and badges.
  [`OpportunityList.tsx:93`](../../apps/windows/src/components/OpportunityList.tsx#L93)

- Inspect decision cards, technical-only gating, and asymmetric Short risk disclosure.
  [`DetailPanel.tsx:432`](../../apps/windows/src/components/DetailPanel.tsx#L432)

- Trace fact-level support/risk framing for fundamentals, technicals, analysts, and insiders.
  [`DetailPanel.tsx:567`](../../apps/windows/src/components/DetailPanel.tsx#L567)

- Verify bullish and bearish technical evidence swaps support/risk groups by model.
  [`TechnicalAnalysisPanel.tsx:418`](../../apps/windows/src/components/TechnicalAnalysisPanel.tsx#L418)

- Review crypto cycle evidence rewritten as rebound risk or bearish support.
  [`CryptoCyclePanel.tsx:32`](../../apps/windows/src/components/CryptoCyclePanel.tsx#L32)

**Secondary surfaces**

- Confirm Dashboard never relabels Short candidates as purchases.
  [`DashboardPanel.tsx:28`](../../apps/windows/src/components/DashboardPanel.tsx#L28)

- Confirm Portfolio pauses buy sizing/actions while preserving Short risk visibility.
  [`AdvisorPanel.tsx:322`](../../apps/windows/src/components/AdvisorPanel.tsx#L322)

**Verification and operator guidance**

- Review semantic contracts for decisions, edges, assets, translations, and forbidden wording.
  [`scoringPresentation.test.ts:49`](../../apps/windows/tests/scoringPresentation.test.ts#L49)

- Read the operator-facing distinction between Long and Short methodology.
  [`README.md:43`](../../README.md#L43)
