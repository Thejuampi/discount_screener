# BMAD Method (Grok Build)

This project has **BMAD Method 6.12** installed. Juan is a one-person team.

Work is a **spike**. A spike explores more than one idea for the task, logs each idea, and locks the best one. Then the cycle runs on that lock.

A **requirement** runs this cycle. Every step, every time:

**PRD → Sensei+Advisor → spec → Sensei+Advisor → build → bmad-review**

This file is the BMAD **process** source. Product rules in `AGENTS.md`, `_bmad-output/project-context.md`, and `shared/contracts` **outrank** generic BMAD templates.

You do **not** need the full catalog. Read existing artifacts first and reuse them; a PRD that already covers this spike is the PRD step, done.

UX and architecture stay optional. Do not create epics, user stories, or sprint status.

Deprecated v6 shims are not installed. Use the current skill names.

## Spike

Open at least two ideas that can do the task. Log each idea in the memlog. Lock one. The lock is the only idea the cycle builds.

| Rule | Do |
| --- | --- |
| Explore | Name the options. Cost, chance of working, and how close they sit to the last failed path. |
| Lock | One `decision` line in the memlog. The cycle follows that line. |
| Resume | Read the memlog. Do not re-open a lock without new data. |

Forge and recon are tools inside a spike when the idea is still soft.

## The cycle (requirements)

| Step | Skill | Leaves behind |
| --- | --- | --- |
| 1. PRD | `/bmad-prd` | The WHY and the acceptance bar. Init the spike memlog. Log the options. Lock one. |
| 2. PRD review | `/sensei-advisor` | Sensei and Advisor on the PRD. Advisor wins. Append the verdict to the memlog. |
| 3. Spec | `/bmad-spec` | The WHAT, locked. Read the PRD and the memlog first. |
| 4. Spec review | `/sensei-advisor` | Sensei and Advisor on the spec. Advisor wins. Append the verdict to the memlog. |
| 5. Build | `/bmad-build` | The code, TDD, and the docs the change makes untrue. Read the memlog first. |
| 6. Review | `/bmad-review` | Adversarial read of the diff against the spec. Append findings to the memlog. |

Do not start spec while the PRD review is `revise`. Do not start build while the spec review is `revise`. Juan may name a P0 waiver.

**Review does not close while docs are stale.** A user-visible change that leaves `docs/`, `AGENTS.md`, `project-context.md`, or a contract describing the old behavior is half built.

A bugfix, rename, or small tweak still ships direct with TDD. That exemption is for a fix, never for a requirement.

## Memlog (mandatory)

The memlog is the working memory of the spike. Artifacts are distilled from it. Chat is not the record.

| Rule | Do |
| --- | --- |
| Home | `{spec-folder}/.memlog.md` once the spec folder exists. Until then, next to the PRD. |
| Tool | `uv run {project-root}/_bmad/scripts/memlog.py` |
| Init | At PRD start: `init --workspace {dir} --field topic="…" --field goal="…"` |
| Append | One line per fact. Types: `decision`, `constraint`, `assumption`, `question`, `direction`, `insight`, `event` |
| Resume | Read the memlog before you invent. Prefer it over re-reading the source it came from. |
| Shape | Append-only. Never edit, reorder, or delete a line. |

Log every idea you explore, every lock, every constraint, assumption, open question, review verdict, and course change. A review that does not append to the memlog did not happen.

```
uv run {project-root}/_bmad/scripts/memlog.py append --workspace {dir} --type decision --text "…"
```

## Lanes by work size

| Work size | Default lane | Use |
| --- | --- | --- |
| Bugfix, rename, small tweak, “just ship it” | **Direct** | Implement with TDD; skip BMAD unless Juan invokes a skill |
| Any requirement or feature, however small | **Spike + cycle** | Explore options, lock one, then the six-step cycle |
| Large / cross-platform / domain-hard (valuation, ranking, Quant Lens) | **Spike + cycle + architecture** | The cycle, with `bmad-architecture` after the PRD review and before spec |
| Idea still unproven | **Forge / recon** | `bmad-forge-idea` or `bmad-deep-recon`. Those skills write a memlog too. |
| Lost in brownfield process state | **Help** | `bmad-help` once — not a tour |

## Full-scope new feature

A complete feature from zero (new model, new screen/flow, multi-platform change):

1. Read existing `_bmad-output/` + `AGENTS.md` + `project-context.md` (reuse; do not rewrite).
2. Open the spike. Forge/recon if the idea is still soft. Keep that memlog.
3. `bmad-prd` → `/sensei-advisor` → `bmad-ux` if UI → `bmad-architecture` when structure changes → `bmad-spec` → `/sensei-advisor` → `bmad-build` → `/bmad-review`.
4. If reality diverges: `bmad-correct-course`, append to the memlog, and update artifacts.
5. New standing domain rules go into `project-context.md` / contracts / `AGENTS.md`, not only chat.

Stop and implement only when product decisions, architecture invariants, executable scope, and verification gates exist (or Juan waives planning). Lean docs are enough.

## Highest-value skills

| Priority | Skill | Role |
| --- | --- | --- |
| 1 | Existing artifacts + `project-context.md` + the spike memlog | Source of truth; read before writing |
| 2 | `/sensei-advisor` | Cycle gates after PRD and after spec. Advisor wins. |
| 3 | `bmad-spec` | Cycle step 3. Locks the WHAT |
| 4 | `bmad-build` | Cycle step 5. Implement loop, docs included |
| 5 | `bmad-review` | Cycle step 6. Review the built change |

`bmad-prd` is cycle step 1. Architecture, forge, recon, brainstorm, and party mode are situational.

## Standing rules

- A requirement is a spike, then the full cycle. Lanes size the work below a requirement, never around the cycle.
- Write a new PRD / architecture only when no existing artifact covers this spike.
- Use **fresh sessions** for heavy skills; implement from artifacts and the memlog, not from a long chat.
- Keep BMAD outputs under `_bmad-output/`. Keep BMAD commits separate from product changes.
- Do not hand-edit `.agents/skills` copies; customize via `_bmad/custom` or project rules.
- After a BMAD installer update, drop epic, story, sprint, QA, retro, PRFAQ, brief, build-auto, walkthrough, and project-context from `.agents/skills` if they return.
- Party mode only for contested product or architecture decisions.

## Quick start

- `/bmad-help` — orientation / next step
- Cycle: `/bmad-prd` → `/sensei-advisor` → `/bmad-spec` → `/sensei-advisor` → `/bmad-build` → `/bmad-review`
- Personas only when multi-perspective is useful: `/bmad-agent-pm` (John), `/bmad-agent-architect` (Winston), `/bmad-agent-dev` (Amelia), `/bmad-agent-analyst` (Mary), `/bmad-agent-ux-designer` (Sally)
- Also: `/bmad-brainstorming`, `/bmad-party-mode`, `/bmad-architecture`

## Paths

| Path | Role |
| --- | --- |
| `_bmad/` | Installed modules + config (v6.12) |
| `_bmad/scripts/memlog.py` | Append-only spike memory |
| `_bmad-output/` | Planning + implementation artifacts |
| `{spec-folder}/.memlog.md` | Spike memlog (home once spec exists) |
| `.agents/skills/` | BMAD skills (Grok, Cursor, Codex) |
| `.grok/skills/` | Project skills (`sensei-advisor`) |
