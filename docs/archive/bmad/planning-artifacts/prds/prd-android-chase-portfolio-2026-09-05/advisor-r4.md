---
artifact: advisor-r4
spike: spec-android-chase-portfolio
artifact_under_review: SPEC.md + examples.md + prd.md (r4 Positions tab after A-19/A-20)
prior: advisor-r3.md
admission: resumed from advisor-r3 (revise)
locked_stance: Book-as-identity. Yaml /3. Held+pin. Scores unchanged. No feed hydration. No positionSizeBps mix. Ledger refuse on Android. Import book one writer. Restore log separate. Live make android-run-qa. Never pm clear. Never sp500.
verdict: approve
anticipatory_passes: 4
date: 2026-09-06
---

# Advisor r4 — Android Chase book Positions tab

Docs only. No application source. Resumed from spec `advisor-r3.md`. Same spike.

Closed A-1..A-18 stay closed. A-19 and A-20 are closed in this text. S-2 remains open P1.

## Verdict

`approve`

No open P0. Positions build may start from this spec, the PRD, and the memlog. Carry S-2 into the build memlog.

## Bar check

| Gate | Result |
| --- | --- |
| Correctness Over Delivery Convenience | Pass. No open P0. |
| A-19 hydrate | Pass. FR-7 assemble skips `ensure_symbol_loaded`. FR-9 tap off-feed is a no-op. POS-NO-HYDRATE, POS-TAP-PHYL. SM-3 no hydrate. |
| A-20 Core enum | Pass. Core emits `Today` / `Tomorrow` / `ThisWeek` / `Later` / `None`. CLOSE-TZ Madrid. Compose does not call `LocalDate.now()`. |
| YAML percent owns the earnings cell | Pass. Four calendar tags. Yaml `/2` stays counts. FR-8 forbids closeness knobs in that file. |
| Positions CSV upserted as trades | Pass. Third caller of one writer. POS-IMPORT-NONEMPTY. |
| Signal written by the engine, read by nothing | Pass on spec. CAP-9 names the Opps strip composable. Closeness enum has a painter. Build still greps the consumer. |
| Verifying a property no live QA can reach | Pass. SM-3: PHYL import-only. Four tags = CLOSE-*. Live cannot hit all four in one session. |
| No feed hydration | Pass. A-19 Cases plus score-row field is not a fetch. |
| Compose owns no business rule | Pass. A-20 enum + CLOSE-TZ. |
| Live QA `qa` / never `pm clear` / never `sp500` | Pass. |
| Remaining P1s | S-2 restart Gherkin. Same carry as r2. |

## Docs read

- spec `advisor-r3.md`
- `prd.md`, `SPEC.md`, `examples.md` as they sit now
- spec `.memlog.md` and PRD `.memlog.md`
- `AGENTS.md` Advisor section
- `_bmad-output/project-context.md`
- `docs/operational-anti-patterns.md`

## Locked stance (still held)

Book-as-identity. Yaml `/3`. Held + pin on Earnings / Opps / Watch / Tracked. Scores stay. No feed hydration. No `positionSizeBps` mix. Ledger refuse on Android. Import book one writer. Restore log separate. Live `make android-run-qa` only.

r4 locks that close r3 **Do instead**:

- Off-feed tap is a no-op. Assemble does not hydrate.
- Core closeness enum. Clock home is the earnings-log New York session day.
- Source is `EarningsGateUi.upcoming` (load once) else existing score-row `nextEarningsEpoch`. Off-feed uses the log only.

## r3 disposition

| ID | r3 | r4 text | Status |
| --- | --- | --- | --- |
| A-1..A-18 | closed | No new evidence | **closed** |
| A-19 | P0 hydrate | FR-7/FR-9/CAP-7/CAP-9. POS-NO-HYDRATE. POS-TAP-PHYL. SM-3. §9 no-op | **closed** |
| A-20 | P0 Compose clock | FR-8/CAP-8 enum. CLOSE-TZ. NY session day named | **closed** |
| A-21 | P1 source/fetch | FR-8 source 1+2. CLOSE-YAHOO-PAST. Off-feed log only | **closed** |
| A-22 | P1 PHYL boards | POS-PHYL-BOARDS. SM-C3 Opps/Watch/Tracked | **closed** |
| A-23 | P1 ISO wrap | CLOSE-FRI-MON. CLOSE-SAT-MON | **closed** |
| A-24 | P1 ordinal | FR-7 ordinal. POS-SORT-ORDINAL | **closed** |
| A-25 | P1 strip seam | CAP-9 pointer + fingerprint + exact ticker join | **closed** |
| A-26 | P1 doc homes | FR-7 names the build edit list. POS-IMPORT-NONEMPTY | **closed** on spec. Homes on disk stay stale until build |
| A-27 | P1 SM-3 live split | SM-3 PHYL import-only. Scored `qa` resident. CLOSE-* proof | **closed** |
| S-1 | closed r3 | Yaml `/3` on disk | **closed** |
| S-2 | open P1 | No Confirm-death / Cancel-death Case. FR-4 still omits first Positions frame | **open P1** |
| S-6 | open P2 | CAP-2 still names `JPM-EMPTY-KEEP`. Yaml still has no that id | **open P2** |

## Findings

### S-2 — Restart / cancel still have no Case

- **id:** S-2
- **severity:** P1
- **status:** open
- **class:** verification-gap
- **evidence:** Advisor-r2 and r3 S-2. Ledger **Read model LIMIT 1** Do instead: test the public read after restart. CAP-4 names unique ticker, in-memory confirm, warm start. PERSIST-* proves scale convert. FR-4 first-frame rule names Earnings and Opportunities only. Positions r4 adds no Confirm-death, Cancel-death, or first Positions frame row.
- **proposed fix:** Build tests (or a late examples row): Confirm then death restores lots, as-of, Held; Cancel then death leaves the prior book. First Positions frame has lots or stays loading-empty.
- **second-order:** Unique ticker is a schema line. Positions empty-flashes then fills.

### A-32 — CLOSE-YAHOO does not Given a scored row

- **id:** A-32
- **severity:** P2
- **status:** open
- **class:** verification-gap
- **evidence:** CAP-8: off-feed PHYL uses the log only. Source 2 is the existing score-row field. CLOSE-YAHOO Given is only log none + yahoo date. A-19 closed in prose. The Case can still be read as a fetch.
- **proposed fix:** CLOSE-YAHOO Given: scored on-feed lot. CLOSE-LOG may be any lot. POS-PHYL closeness stays None without a log row.
- **second-order:** Builder wires quoteSummary into closeness for PHYL and cites CLOSE-YAHOO.

### A-33 — Examples outline still titles EXCHANGE_ZONE

- **id:** A-33
- **severity:** P2
- **status:** open
- **class:** doc-gap
- **evidence:** FR-8 / CAP-8 clock home is the earnings-log New York session day. `examples.md` outline title still says EXCHANGE_ZONE. One home per fact. Reviewer still checks the Kotlin name.
- **proposed fix:** Rename the outline to New York session day. Keep America/New_York in the Given.
- **second-order:** Builder adds a second zone constant beside capture today.

### A-34 — PRD FR-3 still names Windows functions

- **id:** A-34
- **severity:** P2
- **status:** open
- **class:** doc-gap
- **evidence:** A-15 closed on spec r2: yaml `/3` is the only parse/merge home. PRD FR-3 still says “yaml examples plus Windows `planCsvImport` / `mergeTradesOntoLots`.”
- **proposed fix:** Drop the TypeScript names from FR-3 in the same docs pass as A-26.
- **second-order:** Builder copies `localStorage` as-of from Windows.

### S-6 — JPM-EMPTY-KEEP not on yaml `/3`

- **id:** S-6
- **severity:** P2
- **status:** open
- **class:** contract-drift
- **evidence:** CAP-2 lists yaml Case JPM-EMPTY-KEEP. Yaml `/3` still has no that id. Empty-keep write rules exist. Spec triage log rejects a new id.
- **proposed fix:** Point CAP-2 at the existing write rule, or add the id in the yaml commit.
- **second-order:** Cash-only snapshot wipes the book if the write rule is skipped.

## Matched anti-pattern rows

| Ledger row | r4 | Status |
| --- | --- | --- |
| YAML percent owns the earnings cell | FR-8 tags. No yaml knobs | mitigated |
| Positions CSV upserted as trades | One writer, three callers | mitigated |
| Signal written by the engine, read by nothing | Strip composable + closeness enum named | mitigated on spec. Build greps |
| Verifying a property no live QA can reach | SM-3 split | mitigated |
| Cold-start / auto-add / feed growth | POS-NO-HYDRATE POS-TAP-PHYL | mitigated |
| `pm clear` / Android QA on `sp500` | Named forbidden | mitigated |
| Read model `LIMIT 1` | Unique ticker. Restart Case still S-2 | watch |
| No business rules in Compose | Core enum. CLOSE-TZ | mitigated |

## Predicted P0s (if build ignores carry)

1. Assemble or Detail tap hydrates PHYL (A-19 regression).
2. Compose `LocalDate.now()` maps a date (A-20 regression).
3. Restart Held via `LIMIT 1` (S-2).
4. Live one-shot PHYL or `sp500` to paint SM-3.

None of these are open on this spec as P0.

## Lesson candidates

- Closing a hydrate P0 needs assemble, tap, and score-row field as three sentences plus Cases.
- Closing a clock P0 needs a Core enum and a device-zone Case. A zone name in prose is not enough.
- SM-2 unreachable clause is the template. Copy it onto the next live metric (SM-3).

## Doc gaps (build still owes)

| Home | Work |
| --- | --- |
| AGENTS.md Import-book sentence | Three callers: Earnings, System, Positions |
| `project-context.md` | Positions is the book home. Closeness is a Core enum |
| `docs/advisor-csv-import.md` | Third caller |
| Android README | Positions tab next to Earnings |
| `docs/cross-platform-parity.md` Chase-book row | Positions tab Android-only |
| `earnings-gate-policy.yaml` | Do not add closeness percents |
| Restart tests | S-2 |
| yaml `JPM-EMPTY-KEEP` | S-6 or drop the CAP-2 id |
| PRD FR-3 | Drop Windows function names (A-34) |
| `examples.md` CLOSE title | New York session day (A-33) |

A-26 scheduled these. They are not a spec revise.

## Regression traps

- Tap PHYL opens Detail.
- Assemble calls `ensure_symbol_loaded`.
- Compose `LocalDate.now()` or a second UTC today.
- Closeness percent or 14-day window in yaml `/2`.
- Friday→Monday tagged This week.
- Tag-string sort.
- Held mark or Act dash on PHYL.
- Pin mints PHYL on Watch/Tracked.
- Positions recomputes Act.
- Restore log writes lots.
- `pm clear` / `sp500` / one-shot PHYL.
- `positionSizeBps` from lot qty.
- Scores or printed rank buy the pin.
- PHYL painted as 12730000 shares.

## Anticipatory loop

Pass count: **4**.

1. **A-19 / A-20 close.** FR-7/FR-9/CAP-7/CAP-8/CAP-9 and the named Cases match r3 proposed fixes. Closed.
2. **A-21..A-27 sweep.** Source, boards, ISO wrap, ordinal, strip seam, doc list, SM-3 split all sit in PRD + SPEC + examples. Closed.
3. **New contradiction.** CLOSE-YAHOO Given omits scored-row. Outline title still says EXCHANGE_ZONE. FR-3 still names Windows functions. P2. No ledger **Do instead** miss that forces revise.
4. **Second-order.** Approve. Build that skips POS-NO-HYDRATE or CLOSE-TZ reopens A-19/A-20 in review. S-2 stays a build test.

## Source flags for a later Reviewer

- Kotlin clock name vs earnings-log New York session day (A-33).
- Production assemble must skip `ensure_symbol_loaded` for every lot, not only PHYL in a unit test.
- Positions must load `earningsGate` once if missing. CLOSE-LOG injects the log.
- Restart Held after Confirm (S-2).

## Next

Build from this spec, the PRD, and the memlog. Absorb S-2. Do not reopen A-19 or A-20 without new data.
