# Sensei r2 — SPEC-android-chase-portfolio

| Field | Value |
| --- | --- |
| Artifact | `_bmad-output/specs/spec-android-chase-portfolio/SPEC.md` + `examples.md` (r2) |
| Prior | `sensei-r1.md` (revise) |
| Role | Sensei |
| Admission | Reconstituted from spec sensei-r1. Same spike. |
| Source of law | Spec r2, examples r2, memlog, PRD, spec sensei-r1. No application source. |
| Advisor locks (do not override) | Unique ticker. Yaml `/3` Android owner. `ledger_apply_unsupported`. Pin is paint. Import book one writer + separate restore log. As-of skip is `<=`. Yaml share qty. Ten-thousandths at SQLite only. |
| Anticipatory passes | 4 |
| Verdict | **approve** |

Build may start. Close the open P1 rows in yaml `/3` and in the first tests. Do not reopen Advisor locks.

---

## Verdict

**approve**

Spec r2 closed **S-SPEC-R1-P0-01 .. P0-03**. Yaml is the only parse/merge home. Plan qty stays shares. SQLite stores ten-thousandths. MERGE-REPLAY confirms first, then plans again against advanced as-of. GENERIC refuses with `ledger_apply_unsupported`. Remaining rows are P1/P2. None is a must-fix-now P0.

---

## P0 closure (r1 → r2)

| id | r1 hole | r2 lock | status |
| --- | --- | --- | --- |
| S-SPEC-R1-P0-01 | `examples.md` copied yaml expects | Canonical line + examples header: parse/merge goldens live only in yaml `/3`. `examples.md` holds refuse, persist scale, Confirm replay, Held, pin, cell. | **closed** |
| S-SPEC-R1-P0-02 | MERGE-REPLAY `When` was plan | Confirm first. As-of `2026-09-01`. Second **plan** of the same blotter keeps 20 shares. MERGE-REPLAY-NO-APPLY does not move as-of. | **closed** |
| S-SPEC-R1-P0-03 | Plan golden in ten-thousandths | Yaml: PHYL `1273` shares. Persist Cases: `12730000` at SQLite. Success signal names both. | **closed** |

---

## Strengths

- One home per parse fact. Persist scale is an Android-only companion, like as-of advance.
- Replay is Confirm-scoped. Second plan reads advanced as-of. Qty stays 20 shares.
- GENERIC sits with Coinbase and Schwab. Book unchanged.
- CAP-2: yaml `1273` shares; Confirm persists `12730000`. Duplicate rows follow yaml; do not sum two accounts.
- `pinHeldFirst` in core. PIN-OPP keeps score 40 and printed rank 2.
- HELD-PREFIX still kills `startsWith`.
- Live path unchanged. Off-feed PHYL stays a `:core` Case.

---

## Bar-raising findings

Closed r1 P0s keep their ids. Open rows below are build/spec follow-through.

| id | severity | status | class | Finding |
| --- | --- | --- | --- | --- |
| S-SPEC-R1-P0-01 | P0 | closed | process | Yaml-only parse/merge home. |
| S-SPEC-R1-P0-02 | P0 | closed | product | Replay Confirm-scoped. |
| S-SPEC-R1-P0-03 | P0 | closed | product | Share qty in yaml. Ten-thousandths at SQLite. |
| S-SPEC-R1-P1-01 | P1 | closed | product | GENERIC refuse Case exists. |
| S-SPEC-R1-P1-02 | P1 | closed | process | examples.md names yaml `/3` Case ids to add: `JPM-FRAC-AMZN`, `JPM-DUP-TICKER`, `MERGE-BEFORE-ASOF`, `MERGE-CLOSE-LOT`, `LEDGER-REFUSE`. |
| S-SPEC-R1-P1-03 | P1 | closed | product | CAP-2: yaml snapshot rules; do not sum two accounts. Unique ticker stays. |
| S-SPEC-R2-P1-01 | P1 | open | process | examples.md also tells build to add `MERGE-REPLAY` to yaml `/3`. Replay is Confirm-scoped and must stay in `examples.md`. A yaml plan row named MERGE-REPLAY would revive P0-02. |
| S-SPEC-R2-P1-02 | P1 | open | process | MERGE-REPLAY-NO-APPLY shares a Given that is MERGE-POST-ASOF-BUY. That Given applies a trade. Split the outline or give NO-APPLY its own Given. |
| S-SPEC-R1-P1-04 | P1 | open | process | No schema version integer. No confirm / cancel / process-death restore Cases. |
| S-SPEC-R1-P1-05 | P1 | open | process | No first-frame Gherkin row. CAP-4 text still stands. |
| S-SPEC-R1-P1-06 | P1 | open | product | CELL outline still CheapNormalRisk `10000` only. Add half/exit, or “all cell fields except Held/pin equal no-lot”. |
| S-SPEC-R1-P1-07 | P1 | open | process | `unreadable` and `as_of_unparseable` still have no Cases. |
| S-SPEC-R1-P1-08 | P1 | open | product | PIN-WATCH and PIN-TRACKED still missing. `pinHeldFirst` needs a caller test per list. |
| S-SPEC-R1-P2-01 | P2 | open | product | PIN-EARN-SET does not prove settled date desc. |
| S-SPEC-R1-P2-02 | P2 | open | product | CUSIP-only row: follow yaml; else skip. |
| S-SPEC-R1-P2-03 | P2 | open | product | Omit set is yaml `JPM-SKIP-CASH`, not a Kotlin table. |
| S-SPEC-R1-P2-04 | P2 | open | environment | BOM/encoding follows yaml reader. |
| S-SPEC-R1-P2-05 | P2 | open | product | Watchlist/Tracked first paint still unnamed. |
| S-SPEC-R1-P2-06 | P2 | open | product | One in-flight confirm plan still unnamed. |
| S-SPEC-R1-P2-07 | P2 | open | process | Cancel Case still missing. |
| S-SPEC-R1-P2-08 | P2 | open | process | Restore log off-writer still has no Case. |
| S-SPEC-R2-P2-01 | P2 | open | process | GENERIC `format` cell is `genérico`. Match the yaml generic detector token. |

---

## Predicted P0s

None classified must-fix-now.

| id | Watch | Why it is not must-fix-now |
| --- | --- | --- |
| P0-pred-yaml-replay | Build puts MERGE-REPLAY in yaml as a plan golden | CAP-3 already names it a Confirm Case. S-SPEC-R2-P1-01 is the gate. |
| P0-pred-account | Two accounts aggregate | CAP-2 + yaml snapshot rules. Assumption: one Self-Directed account. |
| P0-pred-cell | Half/exit scales by lot | P1-06. CheapNormalRisk is locked. |
| P0-pred-watch | Watchlist never calls `pinHeldFirst` | P1-08. CAP-6 names the callers. |
| P0-pred-first-frame | Unpinned flash | CAP-4 rule present. Case is P1-05. |

---

## Anticipatory loop (4 passes)

### Pass 1 — Did r2 close P0-01..03?

- examples.md no longer copies yaml parse expects. Persist table points at yaml Case ids. **P0-01 closed.**
- MERGE-REPLAY: Confirm, as-of 2026-09-01, second plan qty 20. **P0-02 closed.**
- 1273 shares in yaml / success signal. 12730000 at SQLite only. **P0-03 closed.**
- GENERIC refuse Case. **P1-01 closed.**

### Pass 2 — r1 P1 leftovers

- Schema version, first-frame Case, CELL half/exit, unreadable rows, PIN-WATCH/TRACKED: still open. Not P0.
- Account filter named as yaml rule + do not sum two accounts. **P1-03 closed.**
- Yaml Case id list exists. **P1-02 closed**, with **P1-01 r2** (do not yaml MERGE-REPLAY).

### Pass 3 — New r2 holes

- MERGE-REPLAY in the yaml-add list. **S-SPEC-R2-P1-01.**
- NO-APPLY row cannot use POST-ASOF-BUY Given. **S-SPEC-R2-P1-02.**
- GENERIC format token `genérico`. **P2.**
- Second step of replay is plan, not second Confirm. CAP-3 chose that. Planned qty 20 plus “Confirm would write the same lots” proves the gate. Not P0.

### Pass 4 — What build ships if we approve now

- Happy path SM-1 is specified: snapshot yaml shares, persist scale, `<= as-of` in yaml, Confirm replay.
- Build can still yaml-copy MERGE-REPLAY as plan. First tests must keep replay in app/SQLite (P1-01 r2).
- Watchlist/Tracked/cell-half/schema bump can slip. Those are P1 test gaps, not missing product locks.

No new P0. Verdict stays **approve**.

---

## Lesson candidates

1. Persist scale belongs in a companion Case that names the yaml share input. It does not belong in the yaml expect.
2. Confirm replay is two Whens: Confirm (as-of moves), then plan (qty stuck). One plan When cannot do both.
3. A “add these ids to yaml” list must not include Confirm-only Cases.

---

## Open risks

- Class-share Held miss (PRD non-goal).
- `qa` ∩ book empty; live pin Not run.
- Yaml `/3` edit omits `MERGE-BEFORE-ASOF` or puts MERGE-REPLAY in the plan table.
- Two Import book dialogs Confirm one in-memory plan.

---

## Build must take (do not reopen SPEC product locks)

1. Yaml `/3`: `JPM-FRAC-AMZN`, `JPM-DUP-TICKER`, `MERGE-BEFORE-ASOF`, `MERGE-CLOSE-LOT`, ledger refuse goldens. **Do not** add MERGE-REPLAY as a plan row.
2. Schema version integer. Confirm / cancel / restore tests.
3. First-frame test. PIN-WATCH, PIN-TRACKED (or one test per presenter caller).
4. CELL: half or exit, or all cell fields except Held/pin equal no-lot.
5. Cases: `unreadable`, `as_of_unparseable`. Split MERGE-REPLAY-NO-APPLY Given.
6. One in-flight confirm. Yaml omit set. CUSIP skip if yaml silent. BOM via yaml reader.

---

## Anticipatory pass count

**4**

---

## Verdict

**approve**
