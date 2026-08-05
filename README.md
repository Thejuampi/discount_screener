# E2E orchestration artifacts — `valuation-pit-contract`

**Documentation only. This branch is an orphan and contains no source code. It must never be merged
into a code branch.**

It exists because the working copies of these files live under `.agents/workspace/`, which
`.gitignore:28` excludes. That is the right rule for a scratch workspace and it is not changed here —
this branch preserves a *copy* so the record of an effort survives the machine it was produced on.

## What is here

| path | what it is |
|---|---|
| `e2e/valuation-pit-contract/plan.v0.md` … `plan.v6.md` | the plan across seven revisions |
| `e2e/valuation-pit-contract/plan-review/ORCHESTRATOR-RULINGS.md` | every ruling, R-1 … R-24 |
| `e2e/valuation-pit-contract/build/` | wave reports and the raw measurement output behind them |
| `e2e/valuation-pit-contract/review/`, `refine.md`, `brief.md`, `retro-notes.md` | the rest of the pipeline record |

## Why the raw output is kept

`build/*-raw.txt` are the unedited probe runs the reports summarise. They are the only thing that
makes a report checkable rather than merely readable, and every number in this effort's rulings is
meant to be traceable to one of them. Keeping the summary and discarding the evidence would reproduce
the exact defect this effort spent itself finding — a record that reads like a measurement and cannot
be verified as one.

## What this record is about

Making the valuation model coherent with street **without clamping to street**: the model's numbers
are wrong in ways that have causes, and the work is to find and fix the causes. Street price is a
diagnostic throughout — never a clamp target, never an optimand, never an acceptance criterion.
