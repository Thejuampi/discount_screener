# Planning Artifacts Index

Use this index to choose the right planning document without loading the full BMad output folder.

## Current Product Baseline

- [Current Functionality PRD](current-functionality-prd.md) - shipped product baseline across desktop, Android, persistence, provider, and shared-contract behavior.
- [Documentation Framework](documentation-framework.md) - rules for creating, updating, and routing BMad documentation.

## Active Feature: Valuation Change Visibility

- [Product Brief](product-brief-valuation-change-visibility.md) - original concept and opportunity framing.
- [Product Brief Distillate](product-brief-valuation-change-visibility-distillate.md) - compressed context for downstream workflows.
- [PRD](prd.md) - product requirements for valuation-change visibility.
- [UX Design Specification](ux-design-specification.md) - Android-first interaction and visual behavior.
- [Architecture](architecture.md) - technical decisions and boundaries.
- [Epics and Stories](epics.md) - implementable user-value breakdown.
- [Implementation Readiness Report](implementation-readiness-report-2026-04-23.md) - readiness and risk assessment.
- [Sprint Change Proposal](sprint-change-proposal-2026-04-23.md) - course-correction artifact.

## Proposed Feature: Company Performance Lens

- [Product Brief](product-brief-company-performance-lens.md) - opportunity framing for business-performance quality, trajectory, confidence, and decision readiness.
- [Product Brief Distillate](product-brief-company-performance-lens-distillate.md) - compressed context for downstream PRD creation.
- [PRD](company-performance-lens-prd.md) - product requirements for trajectory, confidence, risk flags, decision readiness, and cross-platform semantics.
- [UX Design Specification](company-performance-lens-ux-design-specification.md) - terminal and Android presentation patterns for list and detail surfaces.
- [PRD Validation Report](company-performance-lens-prd-validation-report.md) - validation findings and architecture watchpoints.
- [Architecture](company-performance-lens-architecture.md) - domain model, ownership boundaries, startup constraints, and testing strategy.
- [Epics and Stories](company-performance-lens-epics.md) - implementable breakdown for domain semantics, detail surfaces, list summaries, and hardening.
- [Implementation Readiness Report](company-performance-lens-implementation-readiness-report.md) - readiness decision and traceability review.
- [Implementation Readiness Assessment](company-performance-lens-implementation-readiness-assessment-2026-04-25.md) - rigorous readiness review; currently marks epics as needing story-quality fixes before development.

## Reading Order

For new feature planning:

1. `current-functionality-prd.md`
2. `documentation-framework.md`
3. product brief or PRFAQ
4. PRD
5. UX spec if user-facing
6. architecture if boundaries or persistence change
7. epics
8. readiness report

For implementation:

1. `../project-context.md`
2. `documentation-framework.md`
3. relevant PRD/UX/architecture sections
4. `../implementation-artifacts/index.md`

## Maintenance

- Add every durable planning artifact here.
- Keep feature-specific docs grouped by feature.
- Do not rename generic `prd.md`, `architecture.md`, or `epics.md` without updating every index and sprint reference.
