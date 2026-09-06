# Sensei r1 — SPEC-android-chase-portfolio

| Field | Value |
| --- | --- |
| Artifact | `_bmad-output/specs/spec-android-chase-portfolio/SPEC.md` + `examples.md` |
| Prior | PRD r2 approved (`prd.md` final) + `planning-artifacts/prds/prd-android-chase-portfolio-2026-09-05/sensei-r2.md` |
| Role | Sensei |
| Admission | Reconstituted from PRD sensei-r2. Same spike. New artifact = spec. |
| Source of law | Spec, examples, memlog, PRD, sensei-r2. No application source. |
| Advisor locks (do not override) | Unique ticker. Yaml `advisor-csv-import/3` Android owner. `ledger_apply_unsupported`. Pin is paint. Import book one writer + separate restore log. As-of skip is `<=`. |
| Anticipatory passes | 4 |
| Verdict | **revise** |

Build must not start. Two P0 holes break SM-1 as written.

---

## Verdict

**revise**

Capabilities match the PRD lock: kind, replace, `<= as-of` merge, as-of advance, Held exact, pin paint, cell identity, one writer. `examples.md` still puts parse expects in a second home and puts MERGE-REPLAY under **plan**. Confirm is what advances as-of. Yaml `/3` cannot share 12_730_000 ten-thousandths with a Windows peer that still goldens 1273 shares.

---

## Strengths

- CAP-1..6 map FR-1..6. Non-goals match the PRD.
- `<= as-of` has two Cases: MERGE-SAME-DAY and MERGE-BEFORE-ASOF.
- MERGE-REPLAY names the Android as-of advance (wrong Given/When; the expect is the right rule).
- `generic` assumption refuses via `ledger_apply_unsupported` (S-R2-P1-01 intent).
- One `:core` planner. App owns SAF, Dialog, SQLite. No second Kotlin detect table.
- HELD-PREFIX kills `startsWith`. PIN-OPP keeps score 40 and printed rank 2.
- CELL-NO-LOT and CELL-HELD-LOT lock `positionSizeBps` 10000 on one cell.
- PIN-NO-INVENT does not invent a PHYL row.
- Quantity is integer ten-thousandths on disk. No `Double` lot qty. No `LIMIT 1`.
- Live path stays `make android-run-qa`. PHYL off-feed is a `:core` Case.

---

## Bar-raising findings

| id | severity | status | class | Finding |
| --- | --- | --- | --- | --- |
| S-SPEC-R1-P0-01 | P0 | open | process | Two parse/merge homes. SPEC says yaml `/3` is the only parse/merge home. `examples.md` repeats JPM-* and MERGE-* with its own expect column, including ten-thousandths. One fact, two goldens. Build will pick one and drift. Parse/merge expects stay in yaml only. `examples.md` keeps pin, Held, cell, refuse-on-Android, and **pointers** to yaml Case ids. |
| S-SPEC-R1-P0-02 | P0 | open | product | MERGE-REPLAY `When` is “plans a merge”. CAP-3 advances as-of **after Confirm**. A plan-only test never moves as-of. Second plan of the same window still looks post-as-of and doubles qty. Split the Case: plan output vs Confirm persist. SM-1 is Confirm-scoped. |
| S-SPEC-R1-P0-03 | P0 | open | product | Planner qty unit vs Windows peer. PRD success is PHYL **1273** shares. Spec plan expect is **12_730_000** ten-thousandths. Memlog locks ten-thousandths on **disk**. Yaml `/3` is a Windows peer. Shared plan goldens cannot change unit. Lock: yaml Cases stay share quantity; SQLite (app) converts to integer ten-thousandths; `/3` names that persist scale as Android-only, same pattern as as-of advance. |
| S-SPEC-R1-P1-01 | P1 | open | product | Kind outline has no GENERIC row. Assumption maps `generic` → `trades_ledger`. If yaml `generic` is not ledger, that detect is a lie. Add Case GENERIC: refuse, no write. Keep the reason code. Do not claim a false format. |
| S-SPEC-R1-P1-02 | P1 | open | process | S-R2-P1-02 is half done. MERGE-BEFORE-ASOF, MERGE-REPLAY, empty_keep, ledger refuse exist in `examples.md`. They are not yet mandated as **yaml `/3` rows**. Spec must say: those Case ids land in `advisor-csv-import-v1.yaml` `/3`, not only in `examples.md`. |
| S-SPEC-R1-P1-03 | P1 | open | product | Account filter is still an assumption. JPM-DUP-TICKER aggregates. Spec dropped Windows `planCsvImport` names (memlog). Yaml must keep the Self-Directed filter. Spec constraint: copy yaml account filter; do not sum two accounts into one unique ticker. No second key (Advisor unique ticker). |
| S-SPEC-R1-P1-04 | P1 | open | process | No schema version integer. CAP-4 names the table shape. PRD and sensei-r2 asked for the bump version plus confirm / cancel / process-death restore Cases. Add them. |
| S-SPEC-R1-P1-05 | P1 | open | process | No first-frame Case. CAP-4 text is the rule. Sensei-r2 item 5 asked for a Gherkin row: first Earnings/Opportunities paint has Held set, or loading-empty. No unpinned flash. |
| S-SPEC-R1-P1-06 | P1 | open | product | CELL outline locks only CheapNormalRisk `positionSizeBps` 10000. PRD cell identity is full / half / exit. Add at least one half or exit Case, or one rule: every cell field except Held/pin equals the no-lot cell. |
| S-SPEC-R1-P1-07 | P1 | open | process | CAP-4 names `unreadable` and `as_of_unparseable`. examples.md has no rows. Add two Cases to the kind/refuse outline. |
| S-SPEC-R1-P1-08 | P1 | open | product | CAP-5/6 name watchlist and Tracked. examples name PIN-OPP only for “current sort”. A helper with no caller test is unwired. Add PIN-WATCH and PIN-TRACKED, or one Case per presenter caller. |
| S-SPEC-R1-P2-01 | P2 | open | product | PIN-EARN-SET does not prove settled date desc. AMZN is first because it is Held. Add a Case with two non-held dates or two held dates. |
| S-SPEC-R1-P2-02 | P2 | open | product | CUSIP-only blotter row still unnamed. Follow yaml. If yaml is silent, skip. Do not store CUSIP as ticker. |
| S-SPEC-R1-P2-03 | P2 | open | product | Omit set: say yaml omit set. JPM-SKIP-CASH is one pointer, not a Kotlin Cash+`QACDS` table. |
| S-SPEC-R1-P2-04 | P2 | open | environment | BOM/encoding follows the yaml/Windows reader. Unnamed. |
| S-SPEC-R1-P2-05 | P2 | open | product | Watchlist/Tracked first paint is still only Earnings/Opportunities in CAP-4. Same FR-4 hole. |
| S-SPEC-R1-P2-06 | P2 | open | product | One in-flight confirm plan. Two screens, one writer. Unnamed in SPEC. |
| S-SPEC-R1-P2-07 | P2 | open | process | Cancel writes nothing has CAP text and no Case. JPM-DUP-TICKER does not name the aggregated qty. |
| S-SPEC-R1-P2-08 | P2 | open | process | Restore log never plans a lot write: constraint only, no Case. |

### r2 spec-must-take scorecard

| r2 item | Spec result |
| --- | --- |
| Yaml `/3` Cases for skip, replay, ledger, empty_keep | In `examples.md`. Not locked as yaml rows. **P1-02.** Replay When is wrong. **P0-02.** |
| One `:core` planner | **closed** in Constraints. |
| `generic` refuse | Assumption only. **P1-01.** |
| Windows/yaml account filter | Assumption only. **P1-03.** |
| Pin/Held/first-frame/SM-C1/SM-C2 Outlines | Pin, Held, SM-C1, SM-C2 present. First-frame missing. **P1-05.** Settled sort weak. **P2-01.** |
| Schema version + restore test | **P1-04.** |
| Yaml omit set, CUSIP skip, BOM | **P2-02..04.** |
| One in-flight confirm; watchlist first paint | **P2-05..06.** |

---

## Predicted P0s

Must-fix-now (also in the table):

1. **S-SPEC-R1-P0-01** — examples.md vs yaml parse goldens.
2. **S-SPEC-R1-P0-02** — replay tested at plan, as-of advances at Confirm.
3. **S-SPEC-R1-P0-03** — ten-thousandths on the shared plan vs Windows share qty.

Watch, not must-fix-now:

| id | Watch |
| --- | --- |
| P0-pred-account | Two accounts aggregate on unique ticker if yaml filter is dropped. |
| P0-pred-cell | Half/exit cell still scales by lot. |
| P0-pred-watch | Watchlist/Tracked never call `pinHeldFirst`. |
| P0-pred-generic | `generic` writes if assumption is dropped at build. |

---

## Anticipatory loop (4 passes)

### Pass 1 — PRD FR vs CAP

- FR-1..6 have CAP-1..6. Kind, replace, merge inequality, persist, Held, pin: present.
- One writer + restore log: constraint. SAF: app.
- Schema version integer: missing. **P1-04.**
- First-frame Case: missing. **P1-05.**
- Account filter: not copied. **P1-03.**
- `generic` Case: missing. **P1-01.**

### Pass 2 — examples vs SM-1 and yaml home

- MERGE-BEFORE-ASOF closes PRD P0-01 as a row.
- MERGE-REPLAY expect closes PRD P0-02 as a sentence. **When is plan. P0-02.**
- JPM-THOUSANDS 12_730_000 vs PRD 1273 vs Windows peer. **P0-03.**
- Duplicate parse expects. **P0-01.**
- CELL one identity. **P1-06.**
- No unreadable rows. **P1-07.**

### Pass 3 — r2 leftovers and Advisor locks

- Do not reopen unique ticker, pin paint, `<=`, ledger refuse, one writer.
- S-R2-P1-04 one planner: closed.
- S-R2-P1-01/02/03: still open (generic Case, yaml rows, account filter).
- Watchlist caller tests. **P1-08.**

### Pass 4 — What build ships if we approve now

- Plan-only MERGE-REPLAY stays green while Confirm doubles qty.
- Yaml `/2` 1273 vs Android 12_730_000. Windows peer breaks or Android writes a second scale into yaml.
- Watchlist paints unpinned; helper exists in core.
- CheapNormalRisk 10000 stays; half cell moves.

Those three P0s stay open. Verdict stays **revise**.

---

## Lesson candidates

1. Replay is a Confirm/persist Case. Plan goldens cannot prove as-of advance.
2. Shared yaml goldens keep one quantity unit. Persist scale is an Android-only clause, like as-of advance.
3. `examples.md` is the pin/Held/cell companion. Parse/merge expects have one home: yaml.
4. A list named in CAP-6 needs a Case on that caller, or the helper is dead.

---

## Open risks

- Class-share Held miss (PRD non-goal).
- `qa` ∩ book empty; live SM-2 Not run.
- Yaml `/3` edit in build still omits Android-only as-of clause.
- Two Import book dialogs Confirm the same in-memory plan.

---

## Required spec edits before build

1. Move parse/merge expects to yaml `/3`. `examples.md` points at Case ids.
2. MERGE-REPLAY: Given first Confirm, Then as-of advanced; When second Confirm, Then qty unchanged.
3. Yaml plan qty stays shares (1273). Ten-thousandths is SQLite persist, Android-only, named in `/3`.
4. GENERIC refuse Case. `unreadable` and `as_of_unparseable` Cases.
5. Constraint: yaml account filter. Unique ticker stays.
6. Schema version integer. Confirm / cancel / restore Cases.
7. First-frame Case. PIN-WATCH and PIN-TRACKED (or one Case per caller).
8. CELL: one half or exit row, or “all cell fields except Held/pin equal no-lot”.

---

## Anticipatory pass count

**4**

---

## Verdict

**revise**
