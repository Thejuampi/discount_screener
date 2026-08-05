# Discount Screener Documentation Index

This index is the starting point for humans and AI agents trying to understand the current project state. It points to durable product, architecture, operations, and BMad planning artifacts without duplicating their content.

## Product Overview

- [Repository README](../README.md) - monorepo overview, app entrypoints, validation commands, and requirements.
- [Desktop README](../apps/desktop/README.md) - Rust terminal workstation features, controls, persistence, and verification.
- [Android README](../apps/android/README.md) - Android module map, current implementation, build, release, and run-on-device flow.
- [Shared Contracts README](../shared/contracts/README.md) - language-neutral fixtures and behavior contracts.

## Operator Documentation

- [Cross-Platform Parity](cross-platform-parity.md) - default rule for user-visible parity between desktop and Android.
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
- [Valuation Model Family Contract](../shared/contracts/valuation-model-family.json) - classifier / model-selection goldens.
- [Evidence/SOTP Contract](../shared/contracts/valuation-evidence-sotp.json) - point-in-time evidence, component families, SOTP bridge, refusal, and validation goldens.
- [Evidence/SOTP Implementation Notes](../_bmad-output/implementation-artifacts/spec-valuation-evidence-sotp-implementation.md) - executable-slice ownership, refusal boundaries, and provider/QA boundaries.
- [Valuation live QA checklist](valuation-live-qa-checklist.md) - **Windows live QA = profile `qa` only** (`npm run tauri:dev:qa`); checklist T/AMZN/CI/bank/industrial + merge-bar tests.
- [Multi-name valuation baseline policy](../_bmad-output/implementation-artifacts/valuation-multi-name-baseline-policy.md) - `valuation_baseline` merge bar; quarantine ≠ success.
- [Handover — Quant Valuation Engine (2026-08-02)](../_bmad-output/implementation-artifacts/handover-quant-valuation-engine-2026-08-02.md) - continuity brief for the next agent on the quant/valuation motor (waterfall, CHTR FCFF blocker, P0+).
- [Gap attribution contract](../shared/contracts/valuation-gap-attribution-v1.json) - Shapley policy-delta telemetry; Street diagnostic only.
- [High-signal screener cohort contract](../shared/contracts/valuation-high-signal-screener-cohort-v1.json) - 26-name recompute goal gate.
- [Agent Guidelines](../Agents.md) - monorepo architecture, valuation conventions, Quant Lens SNR rules, numerical conclusion protocol.
- [Valuation Change Visibility PRD](../_bmad-output/planning-artifacts/prd.md) - feature PRD for valuation-change visibility.
- [Planning Artifacts Index](../_bmad-output/planning-artifacts/index.md) - local navigation for BMad planning files.
- [Valuation Change Visibility Architecture](../_bmad-output/planning-artifacts/architecture.md) - technical decisions for that feature slice.
- [Valuation Change Visibility UX Spec](../_bmad-output/planning-artifacts/ux-design-specification.md) - Android-first UX strategy and interaction rules.
- [Valuation Change Visibility Epics](../_bmad-output/planning-artifacts/epics.md) - implementable epics and stories.
- [Implementation Readiness Report](../_bmad-output/planning-artifacts/implementation-readiness-report-2026-04-23.md) - readiness assessment across PRD, UX, architecture, and epics.

## Valuation PIT, Economic Contract & ROIC Research

Deliverables of the `valuation-pit-contract` E2E run (`valuation/wave1-integration`). The economic
contract is the gating artifact: no estimator comparison or target pre-registration is valid until
it exists.

- [SEC Point-in-Time Provenance](sec-point-in-time-provenance.md) - `filed`/`end`/`accn` retained per fact; `AnnualObservation`/`AnnualSeries`/`extract_driver_vintages`; what "known at cutoff `t`" means.
- [Valuation Aggregation Audit](valuation-aggregation-audit.md) - `robust_centre`/`robust_mean`, the `variance_of_centre` fix, growth-pair exclusion, every averaging site in `valuation_core_adapter.rs` and its disposition.
- [Valuation Economic Contract](valuation-economic-contract.md) - NOPAT, invested capital, reinvestment, `g`/`r`, absence states, the growth/return/reinvestment identity, financial-company semantics, R1/R2, the latent-defect register, what the legacy engine still does.
- [ROIC Research Charter](roic-research-charter.md) - the target quantity, the cross-section, the point-in-time discipline, and the named failure modes (survivorship, restatement leakage, the "what did we know then" trap, near-zero denominators).
- [Growth Research Charter](growth-research-charter.md) - the persistence parameter, the two unapproved candidate directions, and the inherited pooled-centre/`variance_of_centre` cautions.
- [ROIC Target Specification](roic-target-specification.md) - all seventeen pinned decisions completing `ΔNOPAT / ΔIC` as a target, written before any candidate result.
- [ROIC Pre-registration](roic-preregistration.md) - the one primary endpoint, the paired comparison against `prior_only`, the issuer-clustered bootstrap, the derived materiality threshold, the veto set, and the freeze protocol.

## Architecture Decisions

ADRs for this project live inline in the owning planning artifact, indexed here rather than under a
separate `docs/adr/` directory (this tree has never had one).

- [AD-VM-012 — FR-29 removal and the explicit unavailable-state behaviour](../_bmad-output/planning-artifacts/valuation-model-family-architecture.md) - an absent return on capital now refuses (`AbsenceReason::EstimatorUnavailable`) instead of valuing at the neutral line; the legacy engine's equivalent substitution remains live and is tracked as LD-3.

## Implementation Tracking

- [Sprint Status](../_bmad-output/implementation-artifacts/sprint-status.yaml) - current story and epic status.
- [Implementation Artifacts Index](../_bmad-output/implementation-artifacts/index.md) - local navigation for stories, specs, and QA artifacts.
- [QA Test Summary](../_bmad-output/implementation-artifacts/tests/test-summary.md) - generated/verified QA coverage notes.

## Maintenance Rules

- Keep this index updated when durable docs are added, renamed, or retired.
- Prefer linking existing docs over copying guidance into another file.
- If a user-visible feature is Android-only or desktop-only, link the parity exception from this index or the relevant feature doc.
- Architecture Decision Records (ADRs) are indexed under `## Architecture Decisions` above, inline in their owning planning artifact — not in a separate `docs/adr/` directory.
