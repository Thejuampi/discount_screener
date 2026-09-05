# Discount Screener Documentation Index

Start here. This file only points. Edit the home, not this list.

## Product

- [Repository README](../README.md) — layout, commands
- [Desktop README](../apps/desktop/README.md) — terminal workstation
- [Android README](../apps/android/README.md) — Android client
- [Windows README](../apps/windows/README.md) — Tauri workstation
- [Shared contracts](../shared/contracts/README.md) — goldens and policy YAML
- [Current functionality PRD](../_bmad-output/planning-artifacts/current-functionality-prd.md) — what ships today
- [Cross-platform parity](cross-platform-parity.md) — default 1:1; named exceptions

## Operator

- [Desktop quick start](../apps/desktop/docs/QUICK_START.md)
- [Desktop screens](../apps/desktop/docs/SCREENS.md)
- [Desktop user manual](../apps/desktop/docs/USER_MANUAL.md)
- [Desktop history](../apps/desktop/docs/HISTORY_TIME_SERIES.md)
- [Windows Dashboard 2.0 regression](windows-dashboard-2.0-manual-regression.md)
- [Advisor CSV import](advisor-csv-import.md) — holdings vs Chase blotter
- [Valuation live QA](valuation-live-qa-checklist.md) — profile `qa` only
- [Aggressive V4 evidence](aggressive-v4-evidence.md)
- [Dip board spec](../_bmad-output/implementation-artifacts/dip-board-spec-v1.md)
- [Leftover board spec](../_bmad-output/implementation-artifacts/leftover-board-spec-v1.md)

### Diagnostics

- [Compose test hang](diagnostics/2026-08-11-compose-test-hang/README.md)
- [SP500 missing drivers](diagnostics/sp500-missing-drivers.md)
- [Cohort gate year](diagnostics/2026-08-29-cohort-gate-anchor/README.md)
- [Profile switch slow](diagnostics/2026-08-18-profile-switch-slow/README.md)
- [Live QA, Windows SEC sieve](../_bmad-output/implementation-artifacts/live-qa-windows-sec-sieve-2026-08-29.md)

## Agent

- [AGENTS.md](../Agents.md) — standing rules. Advisor section is the docs gate.
- [Project context](../_bmad-output/project-context.md) — traps AGENTS does not already say
- [Operational anti-patterns](operational-anti-patterns.md) — failure ledger
- [Analyst-method lifecycle](analyst-method-lifecycle.md)
- [Documentation framework](../_bmad-output/planning-artifacts/documentation-framework.md) — when to write which doc
- [BMad artifact map](../_bmad-output/README.md)
- [Planning index](../_bmad-output/planning-artifacts/index.md)
- [Implementation index](../_bmad-output/implementation-artifacts/index.md)

## Feature

- [Valuation model family](../_bmad-output/planning-artifacts/valuation-model-family-architecture.md)
- [Valuation model family contract](../shared/contracts/valuation-model-family.json)
- [Aggressive V4 contract](../shared/contracts/opportunity-v4.json)
- [Evidence/SOTP contract](../shared/contracts/valuation-evidence-sotp.json)
- [Evidence/SOTP notes](../_bmad-output/implementation-artifacts/spec-valuation-evidence-sotp-implementation.md)
- [Pre-earnings risk gate PRD](../_bmad-output/planning-artifacts/prd-pre-earnings-risk-gate-2026-08-27.md)
- [Earnings log by ticker](../_bmad-output/specs/spec-earnings-log-by-ticker/SPEC.md)
- [Valuation change visibility PRD](../_bmad-output/planning-artifacts/prd.md)
- [Valuation change visibility architecture](../_bmad-output/planning-artifacts/architecture.md)
- [Valuation change visibility UX](../_bmad-output/planning-artifacts/ux-design-specification.md)
- [Valuation change visibility epics](../_bmad-output/planning-artifacts/epics.md)
- [Implementation readiness](../_bmad-output/planning-artifacts/implementation-readiness-report-2026-04-23.md)
- [Handover — honest path](../_bmad-output/implementation-artifacts/handover-honest-path-street-stretch-2026-08-16.md)
- [Handover — quant engine](../_bmad-output/implementation-artifacts/handover-quant-valuation-engine-2026-08-02.md)
- [Gap attribution contract](../shared/contracts/valuation-gap-attribution-v1.json)
- [High-signal cohort contract](../shared/contracts/valuation-high-signal-screener-cohort-v1.json)
- [Multi-name baseline policy](../_bmad-output/implementation-artifacts/valuation-multi-name-baseline-policy.md)
- [Deferred work](../_bmad-output/implementation-artifacts/deferred-work.md)
- [Sprint status](../_bmad-output/implementation-artifacts/sprint-status.yaml)
- [QA universe stance](../_bmad-output/implementation-artifacts/qa-universe-stance-table-2026-08-15.md)
- [QA test summary](../_bmad-output/implementation-artifacts/tests/test-summary.md)

## Maintenance

- One home per fact. Edit the home. Pointers stay pointers.
- A third copy is a defect.
- Add, rename, or retire a durable doc here in the same change.
- Platform-only features: name the exception in [parity](cross-platform-parity.md).
