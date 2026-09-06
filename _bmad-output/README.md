# BMad Artifact Map

This folder contains planning and implementation artifacts generated through BMad workflows for Discount Screener.

## Required Agent Context

- [Project Context](project-context.md) - critical implementation rules and technology versions. Agents should read this before implementation work.
- [Agents.md](../Agents.md) - standing agent rules
- [Operational anti-patterns](../docs/operational-anti-patterns.md) - failure-mode ledger
- **[Handover — Honest path and Street stretch 2026-08-16](implementation-artifacts/handover-honest-path-street-stretch-2026-08-16.md)** - **next agent on identity cash / holdout / Street stretch starts here** (PR #39).
- [Handover — Quant Valuation Engine 2026-08-02](implementation-artifacts/handover-quant-valuation-engine-2026-08-02.md) - older Windows motor brief (waterfall, CHTR, high-signal). Separate workstream.

## Current-State Documentation

- [Current Functionality PRD](planning-artifacts/current-functionality-prd.md) - product baseline for what Discount Screener currently does.
- [Documentation Framework](planning-artifacts/documentation-framework.md) - rules for which BMad documents to create, update, and validate.

## Feature Planning Artifacts

- [Planning Artifacts Index](planning-artifacts/index.md)
- [Valuation Model Family Architecture](planning-artifacts/valuation-model-family-architecture.md) - FCFF vs residual income; dynamic \(r_f\)/ERP/growth; forbidden hard caps
- [Product Brief: Valuation Change Visibility](planning-artifacts/product-brief-valuation-change-visibility.md)
- [Product Brief Distillate](planning-artifacts/product-brief-valuation-change-visibility-distillate.md)
- [PRD: Valuation Change Visibility](planning-artifacts/prd.md)
- [Architecture: Valuation Change Visibility](planning-artifacts/architecture.md)
- [UX Design Specification: Valuation Change Visibility](planning-artifacts/ux-design-specification.md)
- [Epic Breakdown: Valuation Change Visibility](planning-artifacts/epics.md)
- [Implementation Readiness Report](planning-artifacts/implementation-readiness-report-2026-04-23.md)
- [Sprint Change Proposal](planning-artifacts/sprint-change-proposal-2026-04-23.md)
- [PRD: Pre-Earnings Risk Gate](planning-artifacts/prd-pre-earnings-risk-gate-2026-08-27.md) - Android earnings tab. Open work in `implementation-artifacts/deferred-work.md`.
- [SPEC: Reading the earnings log by ticker](specs/spec-earnings-log-by-ticker/SPEC.md) - Android; ticker search on the Earnings tab, earnings section in a ticker's detail
- [SPEC: Advisor CSV import](specs/spec-advisor-csv-import/SPEC.md) - Windows; holdings snapshot vs Chase 90-day blotter; warn then confirm

## Implementation Artifacts

- [Implementation Artifacts Index](implementation-artifacts/index.md)
- [Sprint Status](implementation-artifacts/sprint-status.yaml)
- Story files under `implementation-artifacts/`
- QA outputs under `implementation-artifacts/tests/`

## Recommended BMad Routing

- **Understand the current project:** `docs/index.md` → `AGENTS.md` → `project-context.md` → `current-functionality-prd.md`.
- **Plan a new feature:** open a spike (more than one idea, lock one). PRD → `/sensei-advisor` → spec → `/sensei-advisor`. Add UX if the UI changes. Add architecture if structure or boundaries change. Keep the spike memlog. Juan is a one-person team: do not create epics, user stories, or sprint status.
- **Modify current behavior:** update the current-functionality PRD only if the baseline product behavior changed; otherwise keep changes in feature artifacts.
- **Implement:** `bmad-build` from the spec and the memlog. Then `/bmad-review`.
- **Validate UI/app behavior:** include live QA notes when behavior reaches an installed app surface, especially Android via `make android-run-qa`.

## Maintenance Rules

- Do not treat feature PRDs as the canonical current-state document after implementation. Promote shipped behavior into `current-functionality-prd.md` as one line plus a link.
- One home per fact. Do not copy `AGENTS.md` into this folder.
- Keep artifact names stable unless the index files are updated in the same change.
