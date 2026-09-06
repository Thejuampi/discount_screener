---
artifact: advisor-r2
spike: spec-android-chase-portfolio
artifact_under_review: SPEC.md + examples.md (r2)
prior: advisor-r1.md
admission: reconstituted
locked_stance: Book-as-identity. Yaml /3 only parse/merge home. Share goldens in yaml. Persist ten-thousandths at SQLite. Held flag + pin. Scores unchanged. No feed hydration. No positionSizeBps mix. Ledger apply refused on Android.
verdict: approve
anticipatory_passes: 4
date: 2026-09-05
---

# Advisor r2 — Android Chase book spec

Docs only. No application source. Reconstituted from spec `advisor-r1.md`.

## Verdict

`approve`

No open P0. A-16 is closed: yaml keeps share goldens; SQLite stores ten-thousandths; `examples.md` no longer copies yaml parse expects. Build may start. Carry S-1, S-2, S-5 into the build memlog.

## Bar check

| Gate | Result |
| --- | --- |
| Correctness Over Delivery Convenience | Pass. No open P0. |
| A-16 unit mix | Pass. Layers named. PERSIST-* converts at SQLite. MERGE-REPLAY qty is shares. |
| A-14 / A-15 / A-18 | Still closed. |
| Yaml on disk still `/2` | Build writes `/3`. Spec lists new Case ids. |
| Remaining P1s | S-1 yaml structural delta. S-2 restart Gherkin. S-5 MERGE-REPLAY home. |

## Docs read

- spec `advisor-r1.md`
- `SPEC.md` r2
- `examples.md` r2
- spec `.memlog.md`
- `shared/contracts/advisor-csv-import-v1.yaml` (still `/2` on disk)
- `AGENTS.md` Advisor + specification by example + one home per fact
- `_bmad-output/project-context.md`
- `docs/operational-anti-patterns.md`

## Carry disposition

| ID | r1 | r2 | Status |
| --- | --- | --- | --- |
| A-14 | closed | CAP-3 Android-only advance. MERGE-REPLAY Confirm-scoped | closed |
| A-15 | closed | Yaml `/3` only parse/merge home. No TS names | closed |
| A-16 | P0 mixed units | Option (b): yaml shares; persist × 10000 at SQLite; table headers name the layer | **closed** |
| A-17 | closed | CAP-4 `unreadable` / `as_of_unparseable`. GENERIC ledger refuse | closed |
| A-18 | closed | HELD-* / PIN-* / CELL-* | closed |
| S-1 | P1 no `/3` delta | Case ids listed. Owner / surface / as-of keys still only in PRD + CAP-4 | **open P1** |
| S-2 | P1 no persist Gherkin | PERSIST-PHYL / PERSIST-AMZN exist. No Confirm-death / Cancel-death rows | **open P1** |
| S-3 | P2 display unit | Success signal: 1273 shares, stored 12730000 | **closed** |
| S-4 | P2 pin lists | PIN-OPP still the only list Case | **open P2** |

## Findings

### S-1 — `/3` structural keys still split

- **id:** S-1
- **severity:** P1
- **status:** open
- **class:** contract-drift
- **evidence:** `examples.md` tells build to add Case ids `JPM-FRAC-AMZN`, `JPM-DUP-TICKER`, `MERGE-BEFORE-ASOF`, `MERGE-REPLAY`, `MERGE-CLOSE-LOT`, `LEDGER-REFUSE`. Yaml on disk still `surface: windows-advisor`, `localStorage` as-of, `owner: apps/windows/src/portfolioCsv.ts`. PRD requires Android `:core` owner, platform as-of, `ledger_apply_unsupported`. CAP-4 names SQLite meta. No one table lists those keys next to the Case ids. AGENTS.md: one home per fact. Edit the yaml.
- **proposed fix:** Build yaml `/3` in one commit: owners (Windows + Android `:core`), as-of homes, Android-only merge advance, refuse codes, new Case ids. Windows tests skip Confirm-only Cases.
- **second-order:** Case ids land and `surface` stays Windows-only. Kotlin grows a header table (A-15).

### S-2 — Restart / cancel still have no Case

- **id:** S-2
- **severity:** P1
- **status:** open
- **class:** verification-gap
- **evidence:** Ledger **Read model resolves current identity with LIMIT 1** Do instead: test the public read after restart. CAP-4 states unique ticker, in-memory confirm, warm start. PERSIST-* proves the scale convert on Confirm write. No row for process death or Cancel.
- **proposed fix:** Two Cases in build tests (or a late examples row): Confirm then death restores lots, as-of, Held; Cancel then death leaves the prior book.
- **second-order:** Unique ticker is a schema line. Held after restart stays unproven.

### S-5 — MERGE-REPLAY listed for yaml and for examples.md

- **id:** S-5
- **severity:** P1
- **status:** open
- **class:** doc-gap
- **evidence:** `examples.md`: yaml MERGE-* stay plan-scoped; replay is Confirm-scoped. Same file: build must add `MERGE-REPLAY` to yaml `/3`. CAP-3: yaml `/3` names the Android-only advance; Confirm Cases are MERGE-REPLAY and MERGE-REPLAY-NO-APPLY. One home per fact.
- **proposed fix:** Yaml `/3` gets the Android-only as-of sentence plus plan Case MERGE-REPLAY-NO-APPLY if needed. Confirm replay MERGE-REPLAY stays only in `examples.md`. Do not put a Confirm-scoped expect in the share-unit yaml table.
- **second-order:** Windows loads MERGE-REPLAY as a plan golden and fails, or Android skips the Confirm test and second-import doubles.

### S-4 — Pin tables omit watchlist and Tracked

- **id:** S-4
- **severity:** P2
- **status:** open
- **class:** verification-gap
- **evidence:** CAP-6 names three lists. PIN-* has Opportunities only. Advisor-r1 S-4 proposed PIN-OPP as template or extra Cases. Spec r2 did not add either sentence.
- **proposed fix:** One line in CAP-6: PIN-OPP is the list template. Tracked SQLite order is a persist assert under S-2.
- **second-order:** Tracked pin writes order (PRD A-5).

### S-6 — JPM-EMPTY-KEEP not on the build Case list

- **id:** S-6
- **severity:** P2
- **status:** open
- **class:** contract-drift
- **evidence:** CAP-2 lists yaml Case JPM-EMPTY-KEEP. `examples.md` build list omits it. Yaml `/2` has empty-keep write rules and no example id of that name.
- **proposed fix:** Add `JPM-EMPTY-KEEP` to the yaml `/3` example list, or point CAP-2 at the existing write rule without a new id.
- **second-order:** Cash-only snapshot wipes the book (yaml empty_keep row).

## Matched anti-pattern rows

| Ledger row | r2 spec | Status |
| --- | --- | --- |
| Positions CSV upserted as trades | CAP-1–CAP-3 | mitigated |
| YAML percent owns the earnings cell | CELL-* | mitigated |
| Read model `LIMIT 1` | unique ticker in CAP-4. Restart Case still S-2 | watch |
| One client's sieve copied | A-15 closed. S-1 / S-5 are the remaining fork | watch |
| Backend refuse, UI mute dash | refuse codes + GENERIC | mitigated |
| Live QA `sp500` / `pm clear` | constraints | mitigated |

## Predicted P0s (if build ignores carry)

1. Yaml `/3` Case MERGE-REPLAY as plan-scoped shares; Confirm path untested (S-5).
2. Restart Held via `LIMIT 1` (S-2).
3. Detect headers copied into Kotlin because yaml `surface` stays Windows (S-1).

None of these are open on this spec as P0.

## Lesson candidates

- Closing a unit P0 needs a named convert layer in the table, not a second copy of yaml numbers.
- Confirm-scoped Cases do not belong in the yaml parse/merge table.

## Doc gaps (build still owes)

| Home | Work |
| --- | --- |
| yaml `/3` | Owners, as-of split, Android-only advance, new Case ids, empty_keep example |
| SPEC-advisor-csv-import | Retract “No Android import” |
| `docs/advisor-csv-import.md` | Both surfaces; Android persist scale; as-of advance |
| `project-context.md` / parity / contracts README | PRD list |
| Restart tests | S-2 |

## Regression traps

- PHYL painted as 12730000 shares (convert only at SQLite; paint shares).
- Float quantity on disk.
- MERGE-REPLAY in yaml as a Windows plan golden.
- Prefix Held match.
- `positionSizeBps` from lot qty.
- Pin buys printed rank.
- Restore log writes lots.
- `pm clear` / `sp500`.
- Empty keep wipe.
- Same-day blotter doubles PHYL.

## Anticipatory loop

Pass count: **4**.

1. **A-16.** Option (b) from r1 proposed fix is in the SPEC header, CAP-2, persist table, and success signal. Closed.
2. **r1 P1s.** S-1 Case ids listed; structural yaml keys still split. S-2 persist scale exists; restart/cancel missing. Not P0.
3. **New contradiction.** MERGE-REPLAY on both the yaml add-list and Confirm-only examples (S-5). P1.
4. **Second-order.** Approve spec. Build that copies the add-list blindly forks Windows. S-5 is the instruction: Confirm replay stays in `examples.md`.

## Source flags for a later Reviewer

- Persist convert `round_half_up(shares × 10000)` vs Windows float shares.
- `pinHeldFirst` in `:core`.
- Restart Held after Confirm (S-2).

## Next

Build from this spec, the PRD, and the memlog. Absorb S-1, S-2, S-5. Do not reopen A-16 without new data.
