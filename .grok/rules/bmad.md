# BMAD Method (Grok Build)

This project has **BMAD Method** installed. A **requirement** runs the closed cycle **PRD → spec → build → review → repeat**. Every step, every time. The menu below is for work that is not a requirement.

This file is the BMAD **process** source. Product rules in `AGENTS.md`, `_bmad-output/project-context.md`, and `shared/contracts` **outrank** generic BMAD templates.

You do **not** need the full catalog. Read existing artifacts first and reuse them; a PRD that already covers the slice is the PRD step, done.

The cycle is four skills, not a ceremony. Brief, UX, architecture, epics, readiness, and sprint stay optional and situational.

## The cycle (requirements)

1. **PRD** - `/bmad-prd`. The WHY and the acceptance bar. Reuse the existing artifact when one covers the slice; write a new one when none does.
2. **Spec** - `/bmad-spec`. The WHAT, locked: contracts, edge cases, the examples that must pass.
3. **Build** - `/bmad-quick-dev`. TDD, and the docs the change makes untrue, in the same pass.
4. **Review** - `/bmad-code-review`. Adversarial read of the diff against the spec.
5. **Repeat** - next slice, or `/bmad-correct-course` when reality diverged.

**Review does not close while docs are stale.** Docs and review were both missed once because this file called them optional; they are not.

A bugfix, rename, or spike still ships direct with TDD. That exemption is for a fix, never for a requirement.

## Lanes by work size

| Work size | Default lane | Use |
| --- | --- | --- |
| Bugfix, rename, small tweak, spike, “just ship it” | **Direct** | Implement with TDD; skip BMAD unless Juan invokes a skill |
| Any requirement or feature, however small | **Cycle** | `bmad-prd` → `bmad-spec` → `bmad-quick-dev` → `bmad-code-review` |
| Large / cross-platform / domain-hard (valuation, ranking, Quant Lens) | **Cycle + architecture** | The cycle, with `bmad-architecture` between PRD and spec |
| Idea still unproven | **Forge / recon** | `bmad-forge-idea` or `bmad-deep-recon` |
| Lost in brownfield process state | **Help** | `bmad-help` once — not a tour |

## Full-scope new feature

A complete feature from zero (new model, new screen/flow, multi-platform slice):

1. Read existing `_bmad-output/` + `AGENTS.md` + `project-context.md` (reuse; do not rewrite).
2. Optional forge/recon if the idea is still soft.
3. `bmad-prd` → `bmad-ux` if UI → `bmad-architecture` → epics/stories → `bmad-check-implementation-readiness`.
4. Then implement (`bmad-quick-dev` when useful). No sprint machinery unless Juan asks.
5. If reality diverges: `bmad-correct-course` and update artifacts.
6. New standing domain rules go into `project-context.md` / contracts / `AGENTS.md`, not only chat.

Stop and implement only when product decisions, architecture invariants, executable scope, and verification gates exist (or Juan waives planning). Lean docs are enough.

## Highest-value skills

| Priority | Skill | Role |
| --- | --- | --- |
| 1 | Existing artifacts + `project-context.md` | Source of truth; read before writing |
| 2 | `bmad-quick-dev` | Cycle step 3. Implement loop, docs included |
| 3 | `bmad-spec` | Cycle step 2. Locks the WHAT |
| 4 | `bmad-help` | Router when lost (once) |
| 5 | `bmad-review` / `bmad-code-review` | Cycle step 4. Adversarial read of the diff against the spec |

`bmad-prd` is cycle step 1. Architecture, epics, readiness, forge, recon, and party mode are situational. Sprint planning is excluded unless Juan asks.

## Standing rules

- A requirement runs the full cycle. Lanes size the work below a requirement, never around the cycle.
- Write a new PRD / architecture only when no existing artifact covers the slice.
- Use **fresh sessions** for heavy skills; implement from artifacts, not from a long chat.
- Keep BMAD outputs under `_bmad-output/`. Keep BMAD commits separate from product changes.
- Do not hand-edit `.grok/skills` / `.agents/skills` copies; customize via `_bmad/custom` or project rules.
- Party mode only for contested product or architecture decisions.

## Quick start

- `/bmad-help` — orientation / next step
- Personas only when multi-perspective is useful: `/bmad-agent-pm` (John), `/bmad-agent-architect` (Winston), `/bmad-agent-dev` (Amelia), `/bmad-agent-analyst` (Mary), `/bmad-agent-ux-designer` (Sally), `/bmad-agent-tech-writer` (Paige)
- Common: `/bmad-quick-dev`, `/bmad-spec`, `/bmad-prd`, `/bmad-architecture`, `/bmad-code-review`, `/bmad-party-mode`

## Paths

| Path | Role |
| --- | --- |
| `_bmad/` | Installed modules + config (v6.10+) |
| `_bmad-output/` | Planning + implementation artifacts |
| `.grok/skills/` | BMAD skills for Grok Build |
| `.agents/skills/` | Same skills for Codex / Cursor / shared agents |
