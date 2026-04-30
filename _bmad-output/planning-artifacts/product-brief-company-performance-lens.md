---
title: "Product Brief: Company Performance Lens"
status: "complete"
created: "2026-04-25T19:05:45-04:00"
updated: "2026-04-25T19:05:45-04:00"
inputs:
  - "_bmad-output/brainstorming/brainstorming-session-2026-04-25-190150.md"
  - "_bmad-output/project-context.md"
  - "_bmad-output/planning-artifacts/current-functionality-prd.md"
  - "_bmad-output/planning-artifacts/product-brief-valuation-change-visibility.md"
  - "Targeted web research on finance UX, retail-investor AI adoption, and data transparency"
---

# Product Brief: Company Performance Lens

## Executive Summary

Discount Screener is already strong at finding companies that appear undervalued against public fair-value signals. The next product gap is more qualitative and more important for conviction: **is this business actually performing well enough to deserve a deeper investment review?** Today, users can inspect valuation, analyst targets, charts, consensus, evidence, and history, but they still have to synthesize business quality, trajectory, data confidence, and decision readiness manually across scattered signals.

Company Performance Lens turns company fundamentals into a dense, evidence-first interpretation layer. It helps self-directed analysts quickly understand whether a company is improving, stable, deteriorating, mixed, volatile, or too sparse to judge. The lens does not hide missing data behind a generic score. It shows what is known, what changed, how fresh the evidence is, and whether the available data is strong enough to support an investment review.

The goal is not to produce buy/sell advice. The goal is to make Discount Screener better at answering the question that follows undervaluation: **does the business performance support the opportunity, contradict it, or require more evidence before the user should trust it?**

## The Problem

Undervaluation alone is a weak decision surface. A stock can screen cheaply because price fell faster than fundamentals, because analysts have not updated stale assumptions, because margins are compressing, because cash conversion is poor, or because the available data is incomplete. Without an explicit performance lens, the user must manually compare growth, profitability, cash generation, debt, valuation, price movement, coverage, and historical change before deciding whether a name deserves attention.

That manual synthesis creates three problems. First, weak businesses can look deceptively attractive when the app emphasizes upside more clearly than operating quality. Second, genuinely improving companies may be under-prioritized if their performance momentum is buried in detail. Third, sparse or stale data can be mistaken for a clean signal unless the UI treats confidence as part of the answer.

The cost is slower triage and lower trust. A returning analyst should not have to ask, "Is this opportunity real, or is the app just missing the context that would disqualify it?"

## The Solution

Company Performance Lens adds a business-health interpretation layer across list and detail surfaces. At the list level, symbols gain compact performance cues that separate likely healthy opportunities from deteriorating, conflicted, or insufficient-data names. At the detail level, the app explains performance through structured sections such as growth, profitability, cash conversion, balance-sheet resilience, valuation reasonableness, capital allocation, sentiment, and confidence.

The experience should be organized around user questions rather than raw metric categories:

- **Can the company grow?**
- **Can it convert profit into cash?**
- **Can it survive stress?**
- **Is the valuation reasonable relative to quality?**
- **What changed since the last review?**
- **Is the evidence fresh and complete enough to trust?**

The lens should use deterministic narrative explanations generated from structured signals, not free-form AI claims. Examples include: "Revenue growth improved while margins compressed," "DCF upside is positive but debt risk is elevated," or "No performance direction available because cash-flow history is unavailable." Every conclusion must be backed by visible evidence, timestamps, and degraded-state labels.

## What Makes This Different

Most finance apps either show raw fundamentals without interpretation or collapse business quality into a single opaque rating. Company Performance Lens takes a different path: **structured interpretation with explicit confidence**. It makes the reasoning inspectable and keeps the user in control.

The differentiator is the combination of four product behaviors:

- **Performance trajectory:** improving, stable, deteriorating, mixed, volatile, or insufficient data.
- **Contradiction detection:** flags when valuation, price, fundamentals, debt, cash conversion, or sentiment tell conflicting stories.
- **Decision readiness:** a clear status such as ready for review, needs updated data, needs peer context, needs manual thesis check, or too sparse to judge.
- **Data confidence:** freshness, completeness, source, timestamp, missing fields, and provider uncertainty are first-class parts of the output.

This fits the broader market direction toward finance tools that provide guided decision support, but it avoids the trust problem of overconfident AI summaries by grounding every message in deterministic rules and visible data provenance.

## Who This Serves

**Primary user:** self-directed analysts and investors who screen many public companies and need to decide which names deserve deeper review. Success means they can scan opportunities and immediately distinguish "cheap and improving" from "cheap but deteriorating" or "cheap but not enough evidence."

**Secondary user:** power users maintaining a watchlist who want to know whether business performance is strengthening, weakening, or becoming harder to interpret since the last review.

## Success Criteria

The first version is successful when:

- users can classify a symbol's business performance direction in under 5 seconds
- undervalued names with weak, deteriorating, or low-confidence fundamentals are visibly differentiated from healthier opportunities
- detail screens show the evidence behind each performance conclusion without forcing users to inspect every raw metric
- sparse, stale, unavailable, no-baseline, and no-analyst-target states are explicit rather than smoothed over
- contradiction flags help users notice thesis risks such as DCF upside with debt stress, revenue growth with margin compression, or price strength against weakening fundamentals
- decision-readiness labels reduce unnecessary drill-ins by telling users which symbols are ready for review versus which need updated data or manual thesis checks
- shared business rules can be exercised through deterministic tests and, where cross-platform parity matters, shared contract fixtures

## Scope

### In for the first version

- performance trajectory summary for selected or tracked symbols
- structured performance scorecard covering growth, profitability, cash conversion, balance sheet, valuation, sentiment, and confidence where data exists
- data-confidence layer with freshness, completeness, source, timestamp, missing fields, and provider uncertainty
- contradiction and anomaly flags that connect conflicting signals to visible evidence
- decision-readiness status that tells the user whether the symbol is reviewable, stale, sparse, or needs manual thesis work
- detail-level evidence rows using a compact `Metric | Latest | Direction | Evidence` pattern
- deterministic narrative snippets generated from typed signals
- Android-first UI planning, with portable business rules kept in core/shared layers where practical

### Out for the first version

- buy, sell, hold, or portfolio-allocation advice
- free-form AI-generated investment theses
- automatic trade execution, alerts, or notifications outside existing app surfaces
- broad peer benchmarking if reliable sector/profile peer data is not available
- user-configurable scoring weights unless v1 evidence shows fixed rules are too rigid
- hiding or imputing unavailable Yahoo/provider data to make a cleaner-looking score

## Non-Goals and Risks

Company Performance Lens must not become a decorative score that looks precise but cannot be trusted. The main risk is false confidence: combining sparse fundamentals, stale provider fields, or mismatched time periods into an authoritative-sounding conclusion. The product should prefer "insufficient data" or "mixed evidence" over forced certainty.

The second risk is scope creep. Peer context, thesis checklists, DCF scenario deltas, and expectation-vs-reality panels are all valuable, but v1 should prove the core interpretation model first: trajectory, evidence, contradiction flags, confidence, and decision readiness.

The third risk is architectural leakage. Performance interpretation belongs in deterministic domain logic, not Compose views or ad hoc UI formatting. Provider uncertainty must be handled at boundaries, and expensive history or analysis work should remain demand-driven.

## Vision

If this succeeds, Discount Screener evolves from a discount finder into a conviction triage workstation. Users will not just ask, "What looks undervalued?" They will ask, "Which undervalued companies are performing well enough, changing fast enough, and supported by fresh enough evidence to deserve my time?"

Over time, Company Performance Lens can become the shared business-health layer behind Android, desktop, and contract fixtures. It can connect valuation change visibility, DCF scenario interpretation, thesis risk tracking, peer context, and historical performance review into one consistent decision-support model: transparent, deterministic, evidence-first, and honest about uncertainty.
