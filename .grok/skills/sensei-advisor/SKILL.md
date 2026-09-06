---
name: sensei-advisor
description: Run Sensei and Advisor together on the current plan or proposal, then lock next steps. Advisor wins conflicts. Use when the user asks for a Sensei and Advisor discussion, a Sensei+Advisor review, /sensei-advisor, or wants both bar-raisers before a build.
argument-hint: "[plan or question]"
---

# /sensei-advisor

Launch **Sensei** and **Advisor** in parallel. Synthesize after both packages land. **Advisor wins** every conflict.

## Spawn

1. Spawn `sensei` (`subagent_type: sensei`). If the harness has no named Sensei type, load `agents/sensei.md` from the playbook repo or ask Juan which checkout to use.
2. Spawn `advisor` (`subagent_type: advisor`). If the harness has no named Advisor type, spawn `general-purpose` with `capability_mode: read-only` and the Advisor playbook. Advisor reads docs only. Do not open application source.
3. Give each the same brief: current plan or question, locked stance, mandatory docs list (Advisor), the spike memlog path, and that this is a new thread unless a prior `sensei-r*.md` / `advisor-r*.md` exists for this decision. Advisor's mandatory docs always include `AGENTS.md`, `_bmad-output/project-context.md`, `docs/operational-anti-patterns.md`, and the spike memlog (`{spec-folder}/.memlog.md`, or the PRD memlog if the spec folder does not exist yet).
4. Admission: `resumed` when the same thread can continue. Else `reconstituted` from prior packages. Else `cold_start_waived`. Do not silent cold-start.
5. Wait for both packages. Do not implement while they run.

## Synthesize

Present a short discussion to Juan.

1. Shared verdicts first.
2. Conflicts next. State Advisor's lock as the decision.
3. Slice 1 IN / OUT.
4. Named waivers if Advisor left a P0 that only Juan can open.

Append the synthesis to the spike memlog:

```
uv run {project-root}/_bmad/scripts/memlog.py append --workspace {dir} --type event --text "sensei-advisor verdict={approve|revise} on {prd|spec}"
```

A review that does not append to the memlog did not happen. Do not write `plan.v0.md` or start a builder unless Juan asks. Cycle rule: `revise` blocks the next step until Juan waives a P0.

## Playbooks

- Sensei: no repo reads. Anticipatory passes ≥ 3. Verdict `approve` | `revise`.
- Advisor: docs only. Follow `AGENTS.md` Advisor section. Open P0 ⇒ `revise`.
- Findings: `id`, `severity`, `status`, `class` per `docs/findings.md` in the playbook repo.

Product rules in this repo's `AGENTS.md` and `_bmad-output/project-context.md` outrank generic playbook examples.
