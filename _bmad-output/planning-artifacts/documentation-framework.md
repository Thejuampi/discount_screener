---
status: complete
workflowType: documentation-framework
project_name: Discount Screener
user_name: Juan
date: 2026-04-25
sources:
  - ../project-context.md
  - current-functionality-prd.md
  - prd.md
  - architecture.md
  - ux-design-specification.md
  - epics.md
---

# BMad Documentation Framework

## Purpose

This framework defines which documents exist, when to create or update them, and how agents should move through BMad planning and implementation for Discount Screener.

## Documentation Layers

### 1. Project Knowledge

Canonical navigation and durable project docs.

- `docs/index.md` - human and agent entrypoint.
- `README.md` - repository overview.
- `apps/desktop/README.md` - desktop product and operation.
- `apps/android/README.md` - Android product and operation.
- `shared/contracts/README.md` - cross-platform contract overview.
- `docs/cross-platform-parity.md` - parity policy.

Update this layer when shipped behavior or durable project structure changes.

### 2. Agent Rules

Lean rules that agents must load before implementing.

- `_bmad-output/project-context.md`

Update this layer when technology versions, architecture boundaries, validation gates, or high-risk implementation rules change.

### 3. Current Product Baseline

The product as it exists today.

- `_bmad-output/planning-artifacts/current-functionality-prd.md`

Use this as the baseline before writing future PRDs. Update it after a feature ships and becomes part of the normal product.

### 4. Feature Planning

Documents for proposed or in-progress capabilities.

- Product brief or PRFAQ.
- PRD.
- UX design specification when user experience changes.
- Architecture decision document when technical structure or boundaries change.
- Epics and stories.
- Implementation readiness report.

Existing active feature set:

- Valuation Change Visibility PRD: `planning-artifacts/prd.md`
- Architecture: `planning-artifacts/architecture.md`
- UX: `planning-artifacts/ux-design-specification.md`
- Epics: `planning-artifacts/epics.md`

### 5. Implementation Tracking

Documents used once implementation starts.

- Sprint status: `_bmad-output/implementation-artifacts/sprint-status.yaml`
- Story files: `_bmad-output/implementation-artifacts/*.md`
- QA outputs: `_bmad-output/implementation-artifacts/tests/`
- Retrospectives after epic completion.

## Workflow Routing

### Understand Current State

Use this path when the question is "what does the app currently do?"

1. `docs/index.md`
2. `_bmad-output/project-context.md`
3. `_bmad-output/planning-artifacts/current-functionality-prd.md`
4. relevant app README or shared contract

### Plan A New Feature

Use this path when the user wants new functionality.

1. Create product brief or PRFAQ if the product concept is not already crisp.
2. Create PRD.
3. Create UX spec if any user-facing workflow, screen, or copy changes.
4. Create architecture if persistence, external boundaries, cross-platform contracts, startup, performance, or core/app layering changes.
5. Create epics and stories.
6. Check implementation readiness.
7. Run sprint planning.
8. Create/dev/review stories.
9. Run QA automation or live QA as appropriate.
10. Run retrospective when an epic completes.
11. Promote shipped behavior into `current-functionality-prd.md` and relevant README/docs.

### Modify Existing Behavior

Use this path for changes to shipped functionality.

1. Check `current-functionality-prd.md`.
2. Decide if the change is a small implementation correction or a product change.
3. For product changes, create or edit the relevant PRD/UX/architecture docs.
4. For implementation corrections, document the acceptance criteria in the story/spec artifact.
5. Update the current-functionality PRD only after the behavior is shipped.

### Android App Work

Required documentation checks:

- Read `project-context.md`.
- Read `apps/android/README.md`.
- If UI-facing, check the active UX spec or create one.
- If persistence/startup/history changes, check architecture and add explicit startup/performance acceptance criteria.
- Include live QA notes from `make android-run` when behavior reaches the installed app surface.

### Desktop Work

Required documentation checks:

- Read `project-context.md`.
- Read `apps/desktop/README.md`.
- Check `docs/cross-platform-parity.md`.
- If behavior changes are user-visible, update desktop operator docs and evaluate Android parity.

### Cross-Platform Work

Required documentation checks:

- Read `shared/contracts/README.md`.
- Update or add shared contract fixtures when behavior should match across clients.
- Document platform exceptions explicitly.

## PRD Rules

- A feature PRD describes proposed change. It is not the canonical description of the product forever.
- The current-functionality PRD describes shipped baseline behavior.
- Do not create duplicate PRDs for the same feature. Edit the existing PRD or create a dated change proposal when scope changes.
- PRDs must include degraded states when data can be missing, stale, sparse, partial, or restored.
- PRDs for external-provider behavior must specify live-sample evidence requirements.
- PRDs for Android app-surface behavior must include live QA expectations.

## Architecture Rules

- Create architecture docs when a change touches boundaries, persistence, startup, performance, provider integration, shared contracts, or cross-platform semantics.
- Architecture docs must say where business rules live and where they must not live.
- Startup and warm-restore behavior must be explicit for persistence-heavy changes.
- Expensive work must be classified as startup, refresh, on-demand, or background.

## UX Rules

- Create UX specs when list surfaces, detail screens, history screens, navigation, copy, trust states, or operator workflows change.
- UX specs must define empty/degraded states, not just happy paths.
- Finance UI should remain dense, calm, and evidence-first.
- UI docs should avoid describing implementation unless it affects user behavior.

## Epics, Stories, And Sprint Rules

- Epics should be organized by user value, not by technical layer.
- Stories must carry enough context for implementation without reloading every planning artifact.
- Story acceptance criteria must include tests and live QA where applicable.
- Sprint status is the source of truth for story state.
- Retrospectives should capture lessons when an epic completes or when live QA exposes a systemic miss.

## QA Documentation Rules

- QA docs should name exact commands run and the surface verified.
- For Android live QA, record device/emulator, launch command, observed screen, and blocker or pass condition.
- For mutation checks, record whether automatic or manual mutation was used and what test failed as expected.
- For provider work, record sample symbols/pages and capture location under `.agents/workspace/tmp`.

## Maintenance Checklist

Before closing a feature:

- Current behavior updated in `current-functionality-prd.md` if shipped.
- User-facing docs updated or linked.
- `docs/index.md` updated for new durable docs.
- `project-context.md` updated if a new implementation rule emerged.
- Sprint status/story status updated.
- QA notes captured when app behavior was verified live.
