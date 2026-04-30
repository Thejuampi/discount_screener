# BMad Artifact Map

This folder contains planning and implementation artifacts generated through BMad workflows for Discount Screener.

## Required Agent Context

- [Project Context](project-context.md) - critical implementation rules and technology versions. Agents should read this before implementation work.

## Current-State Documentation

- [Current Functionality PRD](planning-artifacts/current-functionality-prd.md) - product baseline for what Discount Screener currently does.
- [Documentation Framework](planning-artifacts/documentation-framework.md) - rules for which BMad documents to create, update, and validate.

## Feature Planning Artifacts

- [Planning Artifacts Index](planning-artifacts/index.md)
- [Product Brief: Valuation Change Visibility](planning-artifacts/product-brief-valuation-change-visibility.md)
- [Product Brief Distillate](planning-artifacts/product-brief-valuation-change-visibility-distillate.md)
- [PRD: Valuation Change Visibility](planning-artifacts/prd.md)
- [Architecture: Valuation Change Visibility](planning-artifacts/architecture.md)
- [UX Design Specification: Valuation Change Visibility](planning-artifacts/ux-design-specification.md)
- [Epic Breakdown: Valuation Change Visibility](planning-artifacts/epics.md)
- [Implementation Readiness Report](planning-artifacts/implementation-readiness-report-2026-04-23.md)
- [Sprint Change Proposal](planning-artifacts/sprint-change-proposal-2026-04-23.md)

## Implementation Artifacts

- [Implementation Artifacts Index](implementation-artifacts/index.md)
- [Sprint Status](implementation-artifacts/sprint-status.yaml)
- Story files under `implementation-artifacts/`
- QA outputs under `implementation-artifacts/tests/`

## Recommended BMad Routing

- **Understand the current project:** start with `docs/index.md`, then `project-context.md`, then `current-functionality-prd.md`.
- **Plan a new feature:** create or update a product brief, then PRD, UX spec if UI-facing, architecture, epics, readiness report, sprint plan.
- **Modify current behavior:** update the current-functionality PRD only if the baseline product behavior changed; otherwise keep changes in feature artifacts.
- **Implement stories:** use sprint status and story files. Update sprint status as stories move through backlog, ready, in-progress, review, and done.
- **Validate UI/app behavior:** include live QA notes when behavior reaches an installed app surface, especially Android via `make android-run`.

## Maintenance Rules

- Do not treat feature PRDs as the canonical current-state document after implementation. Promote shipped behavior into `current-functionality-prd.md`.
- Do not duplicate architecture rules across many docs. Link `project-context.md` for agent implementation rules.
- Keep artifact names stable unless the index files are updated in the same change.
