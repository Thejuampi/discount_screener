---
title: "Product Brief Distillate: Company Performance Lens"
type: llm-distillate
source: "product-brief-company-performance-lens.md"
created: "2026-04-25T19:05:45-04:00"
purpose: "Token-efficient context for downstream PRD creation"
---

# Product Brief Distillate: Company Performance Lens

## Core Intent

- Company Performance Lens helps Discount Screener users answer the post-screening question: "Is this undervalued company actually performing well enough to deserve deeper review?"
- The feature should improve perception of company performance quality, trajectory, confidence, and decision relevance without hiding sparse, stale, unavailable, no-baseline, or no-analyst-target states.
- The lens must be deterministic and evidence-first; it should not create free-form AI investment advice or opaque health scores.

## Target Users And Jobs

- Primary user: self-directed analyst or investor reviewing many public companies and triaging undervalued opportunities.
- Core job: distinguish "cheap and improving" from "cheap but deteriorating," "cheap but mixed," and "cheap but too sparse/stale to trust."
- Returning-user job: understand what changed since the last review and whether business performance strengthens, weakens, or complicates the thesis.

## MVP Scope Signals

- Include a performance trajectory state: improving, stable, deteriorating, mixed, volatile, or insufficient data.
- Include a performance scorecard decomposed into growth, profitability, cash conversion, balance-sheet resilience, valuation reasonableness, capital allocation, sentiment, and confidence where data exists.
- Include a data confidence layer with freshness, completeness, source, timestamp, missing fields, and provider/parse uncertainty.
- Include contradiction/anomaly flags such as price up while fundamentals weaken, DCF upside with debt risk, revenue growth with margin compression, positive upside with low coverage, or valuation improvement with stale fundamentals.
- Include a decision-readiness status such as ready for review, needs updated data, needs peer comparison, needs manual thesis check, or too sparse to judge.
- Include detail evidence rows using a compact `Metric | Latest | Direction | Evidence` pattern.
- Include deterministic narrative explanations generated from typed signals, not free-form AI text.

## Out Of Scope For MVP

- No buy/sell/hold advice or portfolio allocation recommendation.
- No free-form AI-generated investment thesis.
- No automatic trade execution.
- No hiding, imputing, or smoothing unavailable provider data to make the score look cleaner.
- No broad peer benchmarking unless reliable sector/profile peer data is available.
- No user-configurable score weights unless v1 testing shows fixed rules are too rigid.
- No alerting/notification system outside existing app surfaces.

## Product Rules To Preserve

- Prefer "insufficient data" or "mixed evidence" over forced certainty when evidence is weak.
- Every visible conclusion should expose supporting evidence, timestamps, source/provenance, and degraded-state context.
- Performance interpretation should be organized around user questions: can it grow, convert profit to cash, survive stress, remain reasonably priced, and what changed?
- Score-like outputs must be decomposable; users should be able to inspect why a symbol was labeled improving, deteriorating, mixed, or not reviewable.
- Confidence is part of the answer, not a footnote.

## Architecture And Implementation Context

- Android is likely the first user-facing shell, but portable business rules should live in `apps/android/core` and shared contracts where cross-platform parity matters.
- Compose screens must remain passive views; performance interpretation must not be invented in UI code.
- Desktop reusable logic belongs in `apps/desktop/src/lib.rs` or the owning module, not terminal orchestration.
- Yahoo/provider behavior is unstable live input; parser/provider changes require at least 5 real upstream samples.
- Expensive work such as history loading, DCF analysis, and provider fetches should stay demand-driven and not expand warm-start cost.
- Fixed-point financial value conventions should be preserved for cents, basis points, hundredths, and millis fields.

## Related Existing Product Context

- Discount Screener currently helps users identify profitable companies trading below public fair-value signals from Yahoo Finance data.
- Current surfaces include ranked candidates, opportunities, watchlist, system/issues, detail charts, valuation, consensus, evidence, alerts, and history.
- Current Android functionality already includes restore-to-live movement badges, analyst-target revision cues, cause/trust labels, and valuation history summaries.
- Valuation Change Visibility is an active planning feature; Company Performance Lens should complement it by assessing business-health support for the opportunity, not replacing valuation-change interpretation.

## Competitive And Market Context

- Finance UX trends increasingly emphasize reliability, clarity, subtle decision support, and transparency about data use.
- Retail investors are increasingly using AI-style tools for investment decision support, which raises the bar for explainability and trust.
- The product should use this market direction without adopting overconfident AI summaries; the trust advantage is deterministic, inspectable interpretation.
- Data transparency and provenance are especially relevant because investment tools can lose user trust when stale or incomplete data drives confident-looking conclusions.

## Open Questions For PRD

- Which metrics are mandatory for the first useful performance trajectory, and which are optional evidence boosters?
- Should v1 ship only in symbol detail, or should list-level performance cues be part of the initial slice?
- What exact thresholds classify improving, stable, deteriorating, mixed, and volatile?
- What freshness windows define fresh, stale, and unavailable for each provider component?
- How should the lens behave when fundamentals and DCF analysis disagree?
- What peer context is reliable enough for v1: loaded-symbol peers, startup profile peers, sector peers, or no peer comparison yet?
- How much of the model should become shared contract behavior immediately versus Android-core-only behavior first?

## Review Insights Integrated

- Skeptic lens: avoid a single opaque score; make degraded states and evidence visible to prevent false confidence.
- Opportunity lens: position the feature as conviction triage, not generic fundamentals display.
- Architecture/adoption lens: keep rules deterministic and portable; avoid adding expensive startup work or UI-owned business logic.
