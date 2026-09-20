---
artifact: advisor-r1
spike: spec-android-chase-portfolio
artifact_under_review: SPEC.md + examples.md
admission: reconstituted from advisor-r2
locked_stance: Book-as-identity. Yaml /3 only parse/merge home. Held flag + pin. Scores unchanged. No feed hydration. No positionSizeBps mix. Ledger apply refused on Android.
verdict: revise
anticipatory_passes: 4
date: 2026-09-05
---

# Advisor r1 — Android Chase book spec

Docs only. No application source. Reconstituted from PRD `advisor-r2.md`.

## Verdict

`revise`

One open P0: quantity goldens mix share counts and ten-thousandths in the same spec. Build does not start on this draft.

## Bar check

| Gate | Result |
| --- | --- |
| Correctness Over Delivery Convenience | Fail. Open P0. |
| A-14 as-of advance in yaml `/3` | Pass. CAP-3 + MERGE-REPLAY. Named Android-only exception. |
| A-15 yaml only merge home | Pass. Header + constraints. No TypeScript source of law. |
| A-16 integer quantity | Fail. Scale is named. Tables still use 10/20 shares beside 12_730_000. |
| A-18 Gherkin Held/pin | Pass. HELD-*, PIN-*, CELL-* each have ≥2 Cases. |
| Yaml on disk still `/2` | Expected until build. Spec must lock `/3` units first. |

## Docs read

- `AGENTS.md` (Advisor, specification by example, fixed-point, one home per fact)
- `_bmad-output/project-context.md`
- `docs/operational-anti-patterns.md`
- `SPEC.md` + `examples.md` + spec `.memlog.md`
- PRD r2 (status final) + PRD `advisor-r2.md`
- `shared/contracts/advisor-csv-import-v1.yaml` (`advisor-csv-import/2` on disk)
- `docs/advisor-csv-import.md`

## Carry from advisor-r2

| ID | Ask | Spec | Status |
| --- | --- | --- | --- |
| A-14 | As-of advance in yaml `/3`, or a named Android-only exception | CAP-3. MERGE-REPLAY. Windows snapshot as-of stays | **closed** |
| A-15 | Yaml `/3` is the only parse/merge home | Header. Constraint: no second Kotlin header table | **closed** |
| A-16 | Integer quantity scale + yaml fractional Case | Ten-thousandths named. JPM-FRAC-AMZN added. Merge/Held tables still in shares | **reopened P0** |
| A-17 | `unreadable` / `as_of_unparseable` | CAP-4. Generic CSV → `ledger_apply_unsupported` | **closed** |
| A-18 | Held/pin Scenario Outlines ≥2 Cases | HELD-EXACT/PREFIX/CASE/ABSENT. PIN-EARN-UP/SET/OPP/NO-INVENT. CELL-NO-LOT/HELD-LOT | **closed** |

## Findings

### A-16 — Quantity goldens mix two units

- **id:** A-16
- **severity:** P0
- **status:** open
- **class:** architecture
- **evidence:** CAP-2: `"1,273"` → 12_730_000 ten-thousandths. CAP-4: disk quantity is integer ten-thousandths. `examples.md` JPM-THOUSANDS = 12730000. JPM-FRAC-AMZN = 362954. Same file MERGE-SAME-DAY / MERGE-POST-ASOF-BUY / MERGE-REPLAY expect qty 10 and qty 20. CELL-HELD-LOT is AMZN 10. Yaml `/2` JPM-THOUSANDS expect `quantity: 1273`. AGENTS.md: specification by example — each row is one automated test. One home per fact. Fixed-point stays integer. Advisor-r2 A-16 **Do instead**: lock one integer scale in the goldens.
- **proposed fix:** Pick one unit for every quantity expect. Either (a) all goldens in ten-thousandths (PHYL 12_730_000, AMZN merge 100_000 → 200_000) and yaml `/3` uses that field, or (b) yaml `/3` keeps share goldens (1273, 10, 20) and Android multiplies by 10_000 only at SQLite. Name the layer in the table header. Do not leave both 12730000 and 10 as `qty` in one spec.
- **second-order:** A MERGE-POST-ASOF-BUY test that asserts 20 against a ten-thousandths store passes on 20 leftover shares after a 100_000 buy, or stores 20 as 20 ten-thousandths (0.002 shares). PHYL then prints 12.7 million shares.

### S-1 — `/3` delta is not a closed edit list

- **id:** S-1
- **severity:** P1
- **status:** open
- **class:** contract-drift
- **evidence:** Spec `policyVersion: advisor-csv-import/3`. Yaml on disk is `/2` with `surface: windows-advisor`, `localStorage` as-of, `quantity: 1273`. PRD requires Android owner, platform as-of, `ledger_apply_unsupported`, SPEC-advisor-csv-import non-goal retract. CAP-3 adds Android-only as-of advance. examples.md adds JPM-FRAC-AMZN, MERGE-BEFORE-ASOF, MERGE-REPLAY, MERGE-CLOSE-LOT. No SPEC section lists the yaml keys and example ids to write.
- **proposed fix:** Add a `/3` delta table: owners, as-of homes, refuse codes, Android-only MERGE-REPLAY, quantity field name, new example ids. Windows tests that load every example skip MERGE-REPLAY or assert snapshot as-of unchanged.
- **second-order:** Builder copies examples.md into Kotlin and leaves yaml at `/2`. Detect headers become a second policy table (A-15 regression).

### S-2 — CAP-4 persist has no Gherkin table

- **id:** S-2
- **severity:** P1
- **status:** open
- **class:** verification-gap
- **evidence:** AGENTS.md specification by example. Ledger **Read model resolves current identity with LIMIT 1** Do instead: test the public read after restart. CAP-4 states unique ticker, cancel in memory, warm start. `examples.md` has no restart / cancel / confirm-write Cases.
- **proposed fix:** Outline with ≥2 Cases: Confirm then process death restores lots and as-of and Held; Cancel then death leaves the prior book.
- **second-order:** Unique ticker is a schema comment. Held after restart stays unproven.

### S-3 — Display unit for PHYL is unnamed

- **id:** S-3
- **severity:** P2
- **status:** open
- **class:** product
- **evidence:** Success signal: PHYL stays 12_730_000 ten-thousandths. Operator yaml/doc still say `"1,273"` is 1273 shares. UI that prints the disk integer shows 12.7 million shares.
- **proposed fix:** Disk is ten-thousandths. Paint is shares (1273.0000). Spec one sentence.
- **second-order:** Follows A-16. Close A-16 first.

### S-4 — Pin tables omit watchlist and Tracked

- **id:** S-4
- **severity:** P2
- **status:** open
- **class:** verification-gap
- **evidence:** CAP-6 names Opportunities, watchlist, Tracked. PIN-* only earnings and Opportunities. PIN-NO-INVENT covers absent ticker once.
- **proposed fix:** Add one watchlist Case and one Tracked Case, or state PIN-OPP is the list template and Tracked persist order is a CAP-4 restart Case.
- **second-order:** Tracked pin rewrites SQLite order (PRD A-5) with no row to fail.

## Matched anti-pattern rows

| Ledger row | Spec | Status |
| --- | --- | --- |
| Positions CSV upserted as trades | CAP-1–CAP-3 | mitigated |
| YAML percent owns the earnings cell | CELL-* | mitigated |
| Read model `LIMIT 1` | CAP-4 unique ticker. Restart Case missing | watch S-2 |
| One client's sieve copied | A-15 closed. S-1 is the remaining fork | watch S-1 |
| Backend refuse, UI mute dash | refuse codes named | mitigated |
| Live QA `sp500` / `pm clear` | constraints | mitigated |

## Predicted P0s (if this spec ships)

1. Merge tests assert qty 20 against ten-thousandths storage (A-16).
2. Yaml stays `/2`. Kotlin grows a header table (S-1 / A-15).
3. Restart Held uses `LIMIT 1` (S-2).

## Lesson candidates

- Naming an integer scale does not close A-16. Every Examples quantity column must use that scale or name a convert layer.
- Android-only yaml Cases need an explicit Windows skip, or the shared file is a silent fork.

## Doc gaps

| Home | Gap until build, after this revise |
| --- | --- |
| yaml `/3` | Not written. Spec must list the delta (S-1) |
| yaml quantity field | Shares vs ten-thousandths (A-16) |
| SPEC-advisor-csv-import non-goal | Still “No Android import” |
| `docs/advisor-csv-import.md` | Windows-only; no ten-thousandths; no Android as-of advance |
| `examples.md` persist | No restart table (S-2) |

## Regression traps

- PHYL painted as 12_730_000 shares.
- MERGE-REPLAY applied on Windows and skipped on Android, or the reverse.
- Float quantity on disk despite CAP-4.
- Prefix Held match.
- `positionSizeBps` from lot qty.
- Pin buys printed rank.
- Restore log writes lots.
- `pm clear` / `sp500` live QA.
- Empty keep wipe.
- Same-day blotter doubles PHYL.

## Anticipatory loop

Pass count: **4**.

1. **Asked IDs.** A-14/A-15/A-18 closed in SPEC text and tables. A-16 scale exists; goldens contradict it.
2. **Examples vs CAP.** Snapshot tables use ten-thousandths. Merge/cell tables use 10 and 20. Same word `qty`. P0.
3. **Yaml `/2` vs spec `/3`.** JPM-THOUSANDS 1273 vs 12730000. Without a delta list, build invents a third copy (S-1).
4. **Second-order.** Fix A-16 with option (b) share goldens + persist × 10_000, and JPM-FRAC-AMZN still needs a yaml `/3` Case. Fix with option (a) and every MERGE-* row must move to 100_000-scale. Do not mix.

## Source flags for a later Reviewer

- Windows quantity type (int vs float) vs Android ten-thousandths.
- Whether Windows already advances as-of (spec says it does not).
- `pinHeldFirst` lives in `:core` as named.

## Next

Revise `examples.md` quantity columns and name the yaml `/3` field. Keep A-14 Android-only exception. Re-run Sensei+Advisor. Do not start build.
