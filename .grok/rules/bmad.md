# BMAD Method (Grok Build)

This project has **BMAD Method** installed. Use it as a **menu**, not a mandatory pipeline.

This file is the BMAD **process** source. Product rules in `AGENTS.md`, `_bmad-output/project-context.md`, and `shared/contracts` **outrank** generic BMAD templates.

You do **not** need the full catalog. Prefer: read existing artifacts → pick a lane → a few skills (`quick-dev`, `spec`, `help`, review). Ceremony is not progress.

**Default:** do not start a full ceremony (brief → PRD → UX → architecture → epics → readiness → sprint) unless Juan asks or the change is large enough. Implement against `AGENTS.md` + `project-context.md`.

## Lanes by work size

| Work size | Default lane | Use |
| --- | --- | --- |
| Bugfix, rename, small tweak, spike, “just ship it” | **Direct** | Implement with TDD; skip BMAD unless Juan invokes a skill |
| Clear feature / refactor with bounded scope | **Express** | `bmad-quick-dev` (optional `bmad-code-review` after) |
| Ambiguous intent, multi-surface contract, or “lock the WHAT” | **Spec** | `bmad-spec` → then implement |
| Large / cross-platform / domain-hard (valuation, ranking, Quant Lens) | **Planning** | decision/spec + `bmad-prd` and/or `bmad-architecture` → implement with TDD |
| Idea still unproven | **Forge / recon** | `bmad-forge-idea` or `bmad-deep-recon` |
| Lost in brownfield process state | **Help** | `bmad-help` once — not a tour |

## Full-scope new feature (planning lane only)

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
| 2 | `bmad-quick-dev` | Default structured implement loop |
| 3 | `bmad-spec` | Lock WHAT when intent is muddy |
| 4 | `bmad-help` | Router when lost (once) |
| 5 | `bmad-review` / `bmad-code-review` | Adversarial check on non-trivial diffs |

PRD, architecture, epics, readiness, forge, recon, and party mode are situational. Sprint planning is excluded unless Juan asks.

## Standing rules

- Match the **lane** to work size. Juan did not ask ⇒ no ceremony.
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
