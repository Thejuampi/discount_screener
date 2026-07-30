# BMAD Method (Grok Build)

This project has **BMAD Method** installed. Use it as a **menu**, not a mandatory pipeline.

**Canonical policy:** `AGENTS.md` → section **BMAD Method (when to use — not always)** (lanes, full-scope path, highest-value skills, **common misuses**). Product rules there and in `_bmad-output/project-context.md` outrank generic BMAD templates.

You do **not** need the full BMAD catalog. Prefer: read artifacts → lane table → few skills (`quick-dev`, `spec`, `help`, review). Ceremony is not progress.

## Default lanes (short)

| Work | Default |
| --- | --- |
| Small fix / spike | Direct implement — skip ceremony |
| Bounded feature | `/bmad-quick-dev` |
| Lock the WHAT | `/bmad-spec` then implement |
| Large / full-scope feature | Planning order in AGENTS.md, then quick-dev per story |
| Unsure | `/bmad-help` once |

Do **not** open full PRD → architecture → epics → sprint for one-line fixes. Do **not** start party mode by default. Do **not** rewrite existing planning docs when extend/correct-course is enough.

## Quick start

- `/bmad-help` — orientation / next step
- Personas (only when multi-perspective is useful): `/bmad-agent-pm` (John), `/bmad-agent-architect` (Winston), `/bmad-agent-dev` (Amelia), `/bmad-agent-analyst` (Mary), `/bmad-agent-ux-designer` (Sally), `/bmad-agent-tech-writer` (Paige)
- Common: `/bmad-quick-dev`, `/bmad-spec`, `/bmad-prd`, `/bmad-architecture`, `/bmad-sprint-status`, `/bmad-code-review`, `/bmad-party-mode`

## Paths

| Path | Role |
| --- | --- |
| `_bmad/` | Installed modules + config (v6.10+) |
| `_bmad-output/` | Planning + implementation artifacts |
| `.grok/skills/` | BMAD skills for Grok Build (native) |
| `.agents/skills/` | Same skills for Codex / Cursor / shared agents layout |
