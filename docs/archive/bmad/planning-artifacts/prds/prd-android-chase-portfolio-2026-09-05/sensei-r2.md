# Sensei r2 — PRD: Android Chase book

| Field | Value |
| --- | --- |
| Artifact | `_bmad-output/planning-artifacts/prds/prd-android-chase-portfolio-2026-09-05/prd.md` (r2) |
| Prior | `sensei-r1.md` |
| Role | Sensei |
| Admission | Reconstituted from r1. Same thread. |
| Source of law | PRD r2 + r1 package. No application source. |
| Advisor locks (do not override) | Unique ticker. Yaml `advisor-csv-import/3` Android owner. `ledger_apply_unsupported`. Pin is paint. Import book one writer + separate restore log. As-of skip is `<=`, not same-day-only. |
| Anticipatory passes | 4 |
| Verdict | **approve** |

Spec may start. Close the open P1 rows in the spec and in yaml `/3`. Do not reopen Advisor locks.

---

## Verdict

**approve**

PRD r2 closed **S-R1-P0-01 .. S-R1-P0-04**. The write path now has an as-of inequality, a replay rule, a named SAF door with one writer, and a ledger refuse. Remaining rows are spec/yaml work. None is a must-fix-now P0.

---

## P0 closure (r1 → r2)

| id | r1 hole | r2 lock | status |
| --- | --- | --- | --- |
| S-R1-P0-01 | Same-day skip only | FR-3: `trade_date > as-of` applies; `trade_date <= as-of` leaves qty/cost. SM-1. Advisor lock. | **closed** |
| S-R1-P0-02 | Blotter×2 doubles qty | FR-3: after a merge that applied ≥1 trade, as-of = `max(prior, max applied trade_date)`. Second confirm sees `<= as-of`. SM-1. | **closed** |
| S-R1-P0-03 | No door / two writers | UJ-1: Import book on Earnings and System, one writer. SAF picker. Restore log is a second action and never plans a lot write. | **closed** |
| S-R1-P0-04 | Coinbase/Schwab/generic silent write | UJ-3 + FR-1: `trades_ledger` → `ledger_apply_unsupported`. Unknown/unreadable refuse. No write. Advisor lock. | **closed** |

---

## Strengths

- Contract home is named. Android `:core` owns snapshot+window. Policy bump is `/3`.
- As-of skip is an inequality. Same-day is one row of that table.
- Replay uses as-of advance, not a second grammar.
- Pin is paint. Printed rank ordinal stays pre-pin. `OpportunityEngine` output stays.
- SM-C2 locks `positionSizeBps` equality. Lot qty cannot feed the cell.
- SM-2 splits PHYL as a `:core` Case from live `qa` pin proof. No feed growth. No `sp500`. No `pm clear`.
- First Earnings/Opportunities frame has the Held set, or those lists stay loading-empty.
- `empty_keep` is zero equity rows after Cash/`QACDS` drop. Prior book stays.
- Held match is trim + uppercase exact. Earnings search prefix does not define Held.
- Plans / Discovery stay out of pin scope.

---

## Bar-raising findings

Closed r1 rows keep their ids. New rows use `S-R2-*`.

| id | severity | status | class | Finding |
| --- | --- | --- | --- | --- |
| S-R1-P0-01 | P0 | closed | product | As-of skip locked as `<=`. |
| S-R1-P0-02 | P0 | closed | product | As-of advances after applied merge. Blotter×2 does not double. |
| S-R1-P0-03 | P0 | closed | product | SAF + Import book one writer. Restore log is separate. |
| S-R1-P0-04 | P0 | closed | product | Ledger kinds refuse `ledger_apply_unsupported`. |
| S-R1-P1-01 | P1 | closed | product | Class-share map is an explicit non-goal. Unique ticker stays. Residual Held miss on `BRK.B` vs `BRK/B` is accepted this spike. |
| S-R1-P1-02 | P1 | closed | product | Printed rank ordinal = pre-pin. |
| S-R1-P1-03 | P1 | closed | process | PHYL is `:core`. Live SM-2 only on `qa` ∩ book. |
| S-R1-P1-04 | P1 | closed | process | Schema bump + confirm/cancel/restore tests named. Spec names the version integer. |
| S-R1-P1-05 | P1 | closed | product | Watchlist and Tracked are in FR-5/FR-6 and MVP. |
| S-R1-P1-06 | P1 | closed | process | Yaml examples are the Gherkin Cases for parse/merge. Spec still writes pin/Held Outlines from SM-2 and SM-C*. |
| S-R1-P1-07 | P1 | closed | product | `empty_keep` defined. Prior book stays. |
| S-R1-P1-08 | P1 | closed | product | SM-C2 `positionSizeBps` equality. |
| S-R1-P1-09 | P1 | closed | product | No unpinned flash on Earnings/Opportunities. |
| S-R1-P1-10 | P1 | closed | process | FR-3 names yaml + Windows `planCsvImport` / `mergeTradesOntoLots`. |
| S-R1-P1-11 | P1 | closed | product | Fractional qty stays as parsed. CUSIP-only rows follow yaml (see S-R2-P2-01). |
| S-R1-P2-01 | P2 | closed | product | Held mark on Opportunity, watchlist, Tracked. |
| S-R1-P2-03 | P2 | closed | product | Dip / Cross / Leftover / Discovery out of pin scope. |
| S-R2-P1-01 | P1 | open | product | Yaml detect order still ends on `generic`. FR-1 refuses unknown. `generic` is a known kind. Spec must map `generic` to refuse (reason code) unless the file is JPM snapshot or Chase window. Do not add a third write path. |
| S-R2-P1-02 | P1 | open | process | Intro says yaml examples stay the parse/merge cases. `/3` must also add Cases for `<= as-of` skip, as-of advance replay, `ledger_apply_unsupported`, `empty_keep`. A comment that Android owns snapshot+window is not a Case. |
| S-R2-P1-03 | P1 | open | product | Assumption is one Self-Directed account. FR-2 duplicate ticker aggregates. Spec must copy the Windows account filter. Do not sum two accounts into one unique ticker. |
| S-R2-P1-04 | P1 | open | process | FR-2 lists snapshot rules. FR-3 points at the Windows planner. Spec uses one `:core` planner for both files. FR-2 is the acceptance bar, not a second grammar. |
| S-R2-P2-01 | P2 | open | product | CUSIP-only blotter rows: follow yaml. If yaml is silent, skip the row. Do not store CUSIP as ticker. |
| S-R2-P2-02 | P2 | open | product | Omit set in FR-2 names Cash and `QACDS`. Source of law is the yaml omit set. Spec copies that set. |
| S-R2-P2-03 | P2 | open | environment | CSV encoding/BOM follows the Windows reader. Unnamed in the PRD. |
| S-R2-P2-04 | P2 | open | product | FR-4 first-frame lock names Earnings and Opportunities. Watchlist/Tracked must not flash unpinned order either, or stay out of first-paint until the Held set is ready. |
| S-R2-P2-05 | P2 | open | product | One in-flight confirm plan. Earnings and System share one writer. Two dialogs must not both Confirm. |
| S-R2-P2-06 | P2 | open | process | Class-share miss and empty `qa` ∩ book stay operational risks. Do not arm `sp500` to see PHYL. |

---

## Predicted P0s

None classified must-fix-now.

| id | Watch | Why it is not must-fix-now |
| --- | --- | --- |
| P0-pred-ticker | `BRK.B` vs `BRK/B` Held miss | Explicit non-goal. Unique ticker lock. |
| P0-pred-first-frame | Unpinned flash | FR-4 locks Earnings/Opportunities. Residual is watchlist P2. |
| P0-pred-qa-phyl | Agent arms `sp500` | Non-goal + SM-2 test proof. Operational, not a PRD hole. |
| P0-pred-cell | Lot into `positionSizeBps` | SM-C2. |
| P0-pred-generic | `generic` writes a book | Confirm names kind. Spec refuse is P1, not a silent write. |
| P0-pred-account | Two accounts aggregate | Assumption + Windows planner as source of law. Spec copies filter (S-R2-P1-03). |

---

## Anticipatory loop (4 passes)

### Pass 1 — Did r2 close P0-01..04?

- Inequality `<=` replaces same-day. **P0-01 closed.**
- As-of advance after applied trades. **P0-02 closed.**
- SAF + one writer + restore log split. **P0-03 closed.**
- Ledger refuse + unknown refuse. **P0-04 closed.**
- Advisor locks match the closed rows. Do not reopen.

### Pass 2 — r1 P1/P2 against r2 text

- Rank ordinal, PHYL fixture, schema tests, watchlist scope, `empty_keep`, cell equality, first frame, Windows pointer, fractional qty, Held mark, Plans out of scope: **closed**.
- Class-share: closed-by-decision.
- Carry: generic kind, yaml Case set, account filter, one planner, omit set, BOM, watchlist first frame.

### Pass 3 — New r2 holes that could become P0 in spec

- “Yaml examples stay parse/merge cases” can drop Android replay from `/3`. **S-R2-P1-02.** Spec adds those rows.
- FR-2 as a second grammar vs one planner. **S-R2-P1-04.**
- Unique ticker + aggregate + two accounts. **S-R2-P1-03.** Copy Windows. Do not invent a second account key (Advisor unique ticker).
- `generic` known-kind write. **S-R2-P1-01.** Refuse.

None of these is silent qty change on the named two-file happy path. Not P0.

### Pass 4 — Production after approve

- Restore log remains read-only. Spec keeps it off the writer.
- Opened-at from pre-as-of buys does not change qty/cost. Allowed.
- Name Change skip leaves the old ticker. Fits exact identity.
- Live QA stays `make android-run-qa`. PHYL stays a unit Case.

No new P0. Verdict stays **approve**.

---

## Lesson candidates

1. An as-of merge PRD names the inequality and the replay rule in the same FR, or the second confirm invents qty.
2. Android import needs a door (SAF) and one writer in the PRD. Two screens may call it. Restore log stays off that writer.
3. Detector order is a refuse list for out-of-spike brokers. Confirm-with-kind is the backup, not a third book format.
4. Unique ticker plus aggregate needs the Windows account filter, or two accounts become one lot.

---

## Open risks

- Held miss on class-share tickers until a later spike.
- `qa` ∩ book empty → live SM-2 Not run; `:core` remains the proof.
- Yaml `/3` ships without Android replay rows if spec reads “stay the parse/merge cases” as freeze.
- Live JPM file with a second account, if the planner filter is dropped.
- Watchlist first paint if spec only wires FR-4 to Earnings.

---

## Spec must take (do not reopen PRD)

1. Yaml `/3` Cases: `<= as-of` skip, as-of advance replay, `ledger_apply_unsupported`, `empty_keep`.
2. One `:core` planner. FR-2/FR-3 are acceptance, not two parsers.
3. `generic` → refuse on Android.
4. Copy Windows Self-Directed account filter. Unique ticker stays.
5. Gherkin Outlines for Held pin (upcoming and settled), first-frame, SM-C1, SM-C2.
6. Schema version integer + restore test.
7. Yaml omit set. Skip CUSIP-as-ticker. BOM via Windows reader.
8. One in-flight confirm. Watchlist/Tracked first paint matches FR-4 or waits.

---

## Anticipatory pass count

**4**

---

## Verdict

**approve**
