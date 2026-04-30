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

## BMad Planning Artifacts

- [BMad Artifact Map](../_bmad-output/README.md) - current BMad documentation set and routing rules.
- [Project Context](../_bmad-output/project-context.md) - lean implementation rules AI agents must read before coding.
- [Current Functionality PRD](../_bmad-output/planning-artifacts/current-functionality-prd.md) - baseline PRD for what exists today.
- [Documentation Framework](../_bmad-output/planning-artifacts/documentation-framework.md) - when to create or update each BMad document type.

## Feature Planning

- [Valuation Change Visibility PRD](../_bmad-output/planning-artifacts/prd.md) - feature PRD for valuation-change visibility.
- [Planning Artifacts Index](../_bmad-output/planning-artifacts/index.md) - local navigation for BMad planning files.
- [Valuation Change Visibility Architecture](../_bmad-output/planning-artifacts/architecture.md) - technical decisions for that feature slice.
- [Valuation Change Visibility UX Spec](../_bmad-output/planning-artifacts/ux-design-specification.md) - Android-first UX strategy and interaction rules.
- [Valuation Change Visibility Epics](../_bmad-output/planning-artifacts/epics.md) - implementable epics and stories.
- [Implementation Readiness Report](../_bmad-output/planning-artifacts/implementation-readiness-report-2026-04-23.md) - readiness assessment across PRD, UX, architecture, and epics.

## Implementation Tracking

- [Sprint Status](../_bmad-output/implementation-artifacts/sprint-status.yaml) - current story and epic status.
- [Implementation Artifacts Index](../_bmad-output/implementation-artifacts/index.md) - local navigation for stories, specs, and QA artifacts.
- [QA Test Summary](../_bmad-output/implementation-artifacts/tests/test-summary.md) - generated/verified QA coverage notes.

## Maintenance Rules

- Keep this index updated when durable docs are added, renamed, or retired.
- Prefer linking existing docs over copying guidance into another file.
- If a user-visible feature is Android-only or desktop-only, link the parity exception from this index or the relevant feature doc.
