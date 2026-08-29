# Discount Screener Documentation Index

This index is the starting point for humans and AI agents trying to understand the current project state. It points to durable product, architecture, operations, and BMad planning artifacts without duplicating their content.

## Product Overview

- [Repository README](../README.md) - monorepo overview, app entrypoints, validation commands, and requirements.
- [Desktop README](../apps/desktop/README.md) - Rust terminal workstation features, controls, persistence, and verification.
- [Android README](../apps/android/README.md) - Android module map, current implementation, build, release, and run-on-device flow.
- [Shared Contracts README](../shared/contracts/README.md) - language-neutral fixtures and behavior contracts.

## Operator Documentation

- [Aggressive V4 — what has been measured](aggressive-v4-evidence.md) - bucket overlap, the agreement constant and its population, and the forward-return retrospective including its null result.
- [Compose test hang, 2026-08-11](diagnostics/2026-08-11-compose-test-hang/README.md) - open, cause unknown: the Android suite spun 27 minutes in `waitForIdle`. Thread dumps kept, escalation bar set at one recurrence.
- [The cohort gate charged the model a year it never claimed, 2026-08-29](diagnostics/2026-08-29-cohort-gate-anchor/README.md) - the durable cohort gate compared a value today with a Street target twelve months out. Bringing the target back at each name's own cost of equity cut the reported mean error from 15.27% to 11.51% and took the one-sided miss out of the holdout, with no driver and no threshold moved. Still red: WYNN, AMZN, MU and CEG carry what is left. The hold-years rule was the obvious suspect and the sweep cleared it - seven of eight high-growth names are best with no hold at all.
- [Profile switch and first load slow, 2026-08-18](diagnostics/2026-08-18-profile-switch-slow/README.md) - fixed: the switch joined an in-flight Yahoo call before publishing, 1 185 ms to 26 ms. The first load asked for four calls at once against a provider that would take sixteen; the window is now adaptive, and the load keeps running in the background. Updated 2026-08-29: the sieve keeps a fact, not a concept - 595 K chars kept fell to 128 K, and 61 ms to 25 ms, on Apple's real 3.8 M char file. The issuer client streams the same sieve, and its six SEC endpoints now answer from a test double.
- [Cross-Platform Parity](cross-platform-parity.md) - default rule for user-visible parity between desktop and Android. Android Plans Dip hunter and leftover review are explicit exceptions.
- [Dip board spec v1](../_bmad-output/implementation-artifacts/dip-board-spec-v1.md) - Android Plans filter (F, ATR dip, RSI, MACD, Street 20%). Does not change V2/V3/V4 scores.
- [Leftover board spec v1](../_bmad-output/implementation-artifacts/leftover-board-spec-v1.md) - Android Plans leftover review (profile universe, Street leftover ≤ 5%, tape fade). Does not change V2/V3/V4 scores.
- [Desktop Quick Start](../apps/desktop/docs/QUICK_START.md) - first-run desktop workflow.
- [Desktop Screen Guide](../apps/desktop/docs/SCREENS.md) - terminal UI layout and behavior.
- [Desktop User Manual](../apps/desktop/docs/USER_MANUAL.md) - keyboard controls and operational behavior.
- [Desktop History and Time-Series Manual](../apps/desktop/docs/HISTORY_TIME_SERIES.md) - persistence, time-series queries, and exports.
- [Windows Dashboard 2.0 — Manual Regression Spec](windows-dashboard-2.0-manual-regression.md) - Classic vs 2.0 screen-by-screen functional spec and manual regression cases.

## BMad Planning Artifacts

- [BMad Artifact Map](../_bmad-output/README.md) - current BMad documentation set and routing rules.
- [Project Context](../_bmad-output/project-context.md) - lean implementation rules AI agents must read before coding.
- [Current Functionality PRD](../_bmad-output/planning-artifacts/current-functionality-prd.md) - baseline PRD for what exists today.
- [Documentation Framework](../_bmad-output/planning-artifacts/documentation-framework.md) - when to create or update each BMad document type.

## Feature Planning

- [Valuation Model Family Architecture](../_bmad-output/planning-artifacts/valuation-model-family-architecture.md) - FCFF vs residual income by business class; dynamic market params; no hard output caps.
- [Aggressive V4 Contract](../shared/contracts/opportunity-v4.json) - agreement-bonus and sector-relative goldens for the Android-only V4 model; hand-derived, never regenerated from Kotlin.
- [Valuation Model Family Contract](../shared/contracts/valuation-model-family.json) - classifier / model-selection goldens.
- [Evidence/SOTP Contract](../shared/contracts/valuation-evidence-sotp.json) - point-in-time evidence, component families, SOTP bridge, refusal, and validation goldens.
- [Evidence/SOTP Implementation Notes](../_bmad-output/implementation-artifacts/spec-valuation-evidence-sotp-implementation.md) - executable-slice ownership, refusal boundaries, and provider/QA boundaries.
- [Operational anti-patterns](operational-anti-patterns.md) - failure-mode ledger that already bit this repo; keep it out of always-on `AGENTS.md` context.
- [Analyst-method lifecycle](analyst-method-lifecycle.md) - proof obligations for evidence-ledger / analyst-import / model-run closure.
- [Valuation live QA checklist](valuation-live-qa-checklist.md) - **Windows live QA = profile `qa` only** (`npm run tauri:dev:qa`); checklist T/AMZN/CI/bank/industrial + merge-bar tests.
- [Multi-name valuation baseline policy](../_bmad-output/implementation-artifacts/valuation-multi-name-baseline-policy.md) - `valuation_baseline` merge bar; quarantine ≠ success.
- [Handover — Honest path and Street stretch (2026-08-16)](../_bmad-output/implementation-artifacts/handover-honest-path-street-stretch-2026-08-16.md) - **current** continuity for identity cash, holdout measure, and Street-implied stretch (PR #39).
- [Handover — Quant Valuation Engine (2026-08-02)](../_bmad-output/implementation-artifacts/handover-quant-valuation-engine-2026-08-02.md) - older Windows motor brief (waterfall, CHTR, high-signal). Separate workstream.
- [Gap attribution contract](../shared/contracts/valuation-gap-attribution-v1.json) - Shapley policy-delta telemetry; Street diagnostic only.
- [High-signal screener cohort contract](../shared/contracts/valuation-high-signal-screener-cohort-v1.json) - 26-name recompute goal gate.
- [Agent Guidelines](../Agents.md) - monorepo architecture, valuation conventions, Quant Lens SNR rules, numerical conclusion protocol.
- [Valuation Change Visibility PRD](../_bmad-output/planning-artifacts/prd.md) - feature PRD for valuation-change visibility.
- [Pre-Earnings Risk Gate PRD](../_bmad-output/planning-artifacts/prd-pre-earnings-risk-gate-2026-08-27.md) - Android-only. Prices the implied move against the ticker's own history of abnormal post-report returns; logs every earnings event because option chains are never republished.
- [Reading the earnings log by ticker SPEC](../_bmad-output/specs/spec-earnings-log-by-ticker/SPEC.md) - Android-only. Ticker search on the Earnings tab, earnings section in a ticker's detail.
- [Planning Artifacts Index](../_bmad-output/planning-artifacts/index.md) - local navigation for BMad planning files.
- [Valuation Change Visibility Architecture](../_bmad-output/planning-artifacts/architecture.md) - technical decisions for that feature slice.
- [Valuation Change Visibility UX Spec](../_bmad-output/planning-artifacts/ux-design-specification.md) - Android-first UX strategy and interaction rules.
- [Valuation Change Visibility Epics](../_bmad-output/planning-artifacts/epics.md) - implementable epics and stories.
- [Implementation Readiness Report](../_bmad-output/planning-artifacts/implementation-readiness-report-2026-04-23.md) - readiness assessment across PRD, UX, architecture, and epics.

## Implementation Tracking

- [Sprint Status](../_bmad-output/implementation-artifacts/sprint-status.yaml) - current story and epic status.
- [Implementation Artifacts Index](../_bmad-output/implementation-artifacts/index.md) - local navigation for stories, specs, and QA artifacts.
- [QA universe stance table 2026-08-15](../_bmad-output/implementation-artifacts/qa-universe-stance-table-2026-08-15.md) - Wave 1 measure of Android `qa` identity vs Street. Predicted Wave-2 stance. No policy change.
- [QA Test Summary](../_bmad-output/implementation-artifacts/tests/test-summary.md) - generated/verified QA coverage notes.

## Maintenance Rules

- Keep this index updated when durable docs are added, renamed, or retired.
- Prefer linking existing docs over copying guidance into another file.
- If a user-visible feature is Android-only or desktop-only, link the parity exception from this index or the relevant feature doc.
