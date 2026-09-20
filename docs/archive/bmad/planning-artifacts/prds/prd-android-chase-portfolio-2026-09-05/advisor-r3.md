---
artifact: advisor-r3
spike: spec-android-chase-portfolio
artifact_under_review: SPEC.md + examples.md + prd.md (r3 Positions tab)
prior: advisor-r2.md
admission: reconstituted from advisor-r2 (approve on Import-book spec)
locked_stance: Book-as-identity. Yaml /3. Held+pin. Scores unchanged. No feed hydration. No positionSizeBps mix. Ledger refuse on Android. Import book one writer. Restore log separate. Live make android-run-qa. Never pm clear. Never sp500.
verdict: revise
anticipatory_passes: 4
date: 2026-09-06
---

# Advisor r3 — Android Chase book Positions tab

Docs only. No application source. Reconstituted from spec `advisor-r2.md`. This is r3 for the Positions tab. Not a silent cold start.

Closed A-1..A-18 stay closed. Open P1s S-1/S-2: S-1 is closed on disk (`advisor-csv-import/3` landed). S-2 still applies.

## Verdict

`revise`

Two open P0s. Juan must name a P0 waiver or the spec must absorb the fixes. Build of Positions does not start on this draft.

Sensei r3 also says `revise`. Advisor keeps Sensei S3-01 and S3-03 as P0. Advisor demotes Sensei S3-02 to P1. PIN-NO-INVENT and CAP-6 already forbid a minted Watch/Tracked row.

## Bar check

| Gate | Result |
| --- | --- |
| Correctness Over Delivery Convenience | Fail. Open P0. |
| YAML percent owns the earnings cell | Pass. Four calendar tags. No frozen percent. Yaml `/2` stays counts and AV budget. |
| Positions CSV upserted as trades | Pass. Third caller of the same writer. Restore log stays off lots. |
| Signal written by the engine, read by nothing | Watch. Closeness needs a Core enum and a Positions painter (A-20). |
| Verifying a property no live QA can reach | Partial. PHYL is a `:core` Case. AMZN sits in `qa.txt` per Android README. Four tags on one live day stay test-only (A-27). |
| No feed hydration | Fail. Tap/assemble still allow Detail Yahoo work (A-19). |
| Compose owns no business rule | Fail. Core can still emit a date (A-20). |
| Live QA `qa` / never `pm clear` / never `sp500` | Pass. Constraints name the commands. |
| Remaining P1s | S-2 restart Gherkin. New A-21..A-27. |

## Docs read

- `AGENTS.md` Advisor + specification by example + one home per fact + earnings gate + anti-patterns pointer
- `_bmad-output/project-context.md`
- `docs/operational-anti-patterns.md` (named rows plus LIMIT 1, Compose rules, live QA)
- spec `.memlog.md` and PRD `.memlog.md`
- `prd.md` r3, `SPEC.md` r3, `examples.md` r3
- spec `advisor-r2.md` (and PRD `advisor-r2.md`)
- `shared/contracts/earnings-gate-policy.yaml` (`earnings-gate-policy/2`)
- `shared/contracts/advisor-csv-import-v1.yaml` (`advisor-csv-import/3` on disk)
- `docs/advisor-csv-import.md`
- `docs/cross-platform-parity.md`
- `_bmad-output/specs/spec-earnings-gate-identities/SPEC.md`
- `_bmad-output/specs/spec-earnings-log-by-ticker/SPEC.md` + `reading-surfaces.md`
- Android README operator surfaces / `qa` list (docs, not source)
- spec `sensei-r3.md` (conflict only; Advisor wins)

## Locked stance (still held)

Book-as-identity. Yaml `/3` parse/merge home. Held flag + pin on Earnings / Opps / Watch / Tracked. Scores stay. No feed hydration. Lot quantity does not write `positionSizeBps`. Ledger apply refused on Android. Import book one writer. Restore log never plans a lot write. Live `make android-run-qa` only.

r3 locks that follow standing **Do instead**:

- Retract non-goal New Portfolio tab. Positions is the book home. Every lot paints, including off-feed PHYL.
- Four calendar tags. No frozen percent. No 14-day soon window. Not the Opps earnings-mark sentence.
- Clock is America/New_York, same as Earnings capture today. UTC draft superseded. Not device local.
- Import book third caller. Sort closeness then ticker. Held mark omitted on Positions. Pin-held-first does not run here.
- P&L overlay stays non-goal.

## Proposed steps vs doc homes

| r3 step | Doc rule or ledger row |
| --- | --- |
| Retract New Portfolio; Positions tab | Spike memlog course-change. Parity Android-only (like Earnings). |
| FR-7 every lot including PHYL | Book-as-identity. CAP-6 absent lot invents no Opps/Watch/Tracked row. |
| FR-8 four calendar tags | Ledger **YAML percent owns the earnings cell**. Identities in the engine. Yaml `/2` stays counts. |
| FR-8 EXCHANGE_ZONE | Earnings-log SPEC: capture `today` is the New York session day. Name that home (A-20, A-21). |
| FR-8 source log then Yahoo then blank | Earnings-log upcoming vs `scoreRow.nextEarningsEpoch`. No invented date. Yahoo must be existing evidence (A-19, A-21). |
| FR-9 reuse Opps strip | Ledger **Signal written by the engine, read by nothing**. Reuse the snapshot. Name the seam (A-25). |
| Import third caller | Ledger **Positions CSV upserted as trades**. One writer. Operator doc still says Earnings and System (A-26). |
| Live `qa` | Ledger `pm clear` / Android QA on `sp500`. SM-3 reachability (A-27). |
| Off-feed tap | Lock says no auto-add. Detail is the hydrate path. **Breaks** no feed hydration until A-19. |
| Core projects closeness | Compose is a passive View. **Breaks** if Core emits a date (A-20). |

## Carry disposition

| ID | r2 | r3 | Status |
| --- | --- | --- | --- |
| A-1..A-18 | closed | No new evidence | **closed** |
| S-1 | P1 yaml keys split | Yaml `/3` has Android owner, SQLite as-of, `androidAfterMergeConfirm`, new Case ids | **closed** |
| S-2 | P1 no Confirm-death / Cancel-death Case | Positions does not add those rows | **open P1** |
| S-4 | P2 PIN-WATCH / PIN-TRACKED | Both Cases sit in `examples.md` | **closed** |
| S-5 | P1 MERGE-REPLAY listed for yaml | Yaml has no MERGE-REPLAY id. Confirm replay stays in `examples.md` | **closed** |
| S-6 | P2 JPM-EMPTY-KEEP | Yaml `/3` still has no that id. CAP-2 still names it | **open P2** |

## Findings

### A-19 — Off-feed assemble and tap still hydrate

- **id:** A-19
- **severity:** P0
- **status:** open
- **class:** product
- **evidence:** Locked stance: no feed hydration. AGENTS.md: do not auto-add lots to the Yahoo feed. History and Yahoo stay on-demand when the user opens Detail. PRD FR-9 / CAP-9: tap off-feed does not add to Yahoo. PRD §9: off-feed tap besides no auto-add waits. CAP-7 paints PHYL from lots. No Case says assemble skips `ensure_symbol_loaded`. No Case says tap skips Detail. Ledger **Cold-start full SP500 for every agent QA** Do instead: `qa` only, one-shot must not grow the feed. Sensei S3-01.
- **proposed fix:** Close §9. Tap off-feed is a no-op: no Detail, no Yahoo, no feed add. Assemble Positions does not enqueue Yahoo or add a feed symbol. Cases POS-NO-HYDRATE and POS-TAP-PHYL. SM-3 Then includes no hydrate. Yahoo `nextEarningsEpoch` is the existing score-row field. It is not a fetch for PHYL.
- **second-order:** Default row tap opens Detail. PHYL joins the feed. Closeness “else Yahoo” fires quoteSummary for every off-feed lot.

### A-20 — Core must emit the closeness tag

- **id:** A-20
- **severity:** P0
- **status:** open
- **class:** architecture
- **evidence:** `project-context.md`: no business rules in Compose. AGENTS.md: Compose screens are passive Views. Type-driven design: invalid states stay unrepresentable. SPEC constraint: Compose does not own the tag. CAP-8 still lets Core emit a date. Memlog: UTC LocalDate failed; EXCHANGE_ZONE superseded it. Device `LocalDate.now()` is the same hole. Earnings-log SPEC: capture `today` is the New York session day. CLOSE-* injects NY today and cannot catch a Compose clock. Sensei S3-03.
- **proposed fix:** Core emits `Today` / `Tomorrow` / `ThisWeek` / `Later` / `None`. Compose paints the token or omits it. Compose does not read a date and does not call `LocalDate.now()`. Case CLOSE-TZ: device zone `Europe/Madrid`, NY date 2026-09-07, report 2026-09-07, tag Today. Cite the earnings-log NY session day as the clock home.
- **second-order:** Tests stay green on injected today. Phone in Madrid paints Tomorrow for a NY Today print.

### A-21 — Source is existing evidence. Load the log once.

- **id:** A-21
- **severity:** P1
- **status:** open
- **class:** product
- **evidence:** CAP-8: log upcoming `reportEpochDay`, else Yahoo `nextEarningsEpoch`, else blank. Earnings-log `reading-surfaces.md`: tab laziness loads the gate on Earnings. `nextEarningsEpoch` lives on the score row. Settled/missing stay blank. CLOSE-SETTLED is a past date. No Case for Yahoo past (Sensei S3-09). No Case that Positions loads the same `earningsGate` presentation.
- **proposed fix:** Source 1: `EarningsGateUi.upcoming` for that ticker (load the log once if missing, same path as Earnings). Source 2: existing score-row `nextEarningsEpoch` converted on the NY session day. Else `None`. Chosen date before NY today is `None`. Case CLOSE-YAHOO-PAST. Off-feed PHYL uses the log only.
- **second-order:** Positions opens first. Log never loads. CLOSE-LOG is dead. Scored names take stale Yahoo. Off-feed stays blank or fetches (A-19).

### A-22 — PHYL absent on Watch and Tracked needs a Case

- **id:** A-22
- **severity:** P1
- **status:** open
- **class:** verification-gap
- **evidence:** CAP-6: absent lot invents no row. PIN-NO-INVENT: held PHYL, rows MSFT, order MSFT. FR-6 / FR-7: off-feed stays out of Opps, Watch, Tracked. SM-C3 names Opps only. PIN-WATCH / PIN-TRACKED have no PHYL-absent row. Sensei S3-02 scored this P0. The product rule already exists. The table does not name Watch/Tracked.
- **proposed fix:** Case POS-PHYL-BOARDS: PHYL absent on Opps, Watch, Tracked. Earnings does not grow a PHYL universe row. Pin-held-first does not mint one. SM-C3 lists all three boards.
- **second-order:** Builder treats Positions as a lot enumerator and pins PHYL onto Watch.

### A-23 — ISO week wrap has no falsifying Case

- **id:** A-23
- **severity:** P1
- **status:** open
- **class:** verification-gap
- **evidence:** CAP-8: This week is after tomorrow and the same ISO week. CLOSE-WEEK is Monday→Thursday. CLOSE-LATER is Monday→next Monday. CLOSE-SUN-MON is Tomorrow by +1. A Sunday-start week still passes every current row. AGENTS.md specification by example: each row is one automated test. Sensei S3-05.
- **proposed fix:** CLOSE-FRI-MON: NY 2026-09-11, report 2026-09-14, Later. CLOSE-SAT-MON: NY 2026-09-12, report 2026-09-14, Later. CAP-8 success: Friday sees Later for a Monday print.
- **second-order:** Builder tags Friday→Monday as This week. Juan sees the wrong bucket.

### A-24 — Closeness sort ordinal is unnamed

- **id:** A-24
- **severity:** P1
- **status:** open
- **class:** verification-gap
- **evidence:** FR-7: sort is closeness then ticker. Blank last. Same tag sorts by ticker. POS-SORT is tagged vs blank. POS-SORT-TICKER is two blanks. No row orders Today vs Later. Sensei S3-04.
- **proposed fix:** Ordinal Today < Tomorrow < This week < Later < blank, then ticker ASC. Case POS-SORT-ORDINAL: MSFT Today, AMZN Later, PHYL blank → MSFT, AMZN, PHYL.
- **second-order:** Builder sorts tag strings. Later sits above Today.

### A-25 — Strip reuse seam is unnamed

- **id:** A-25
- **severity:** P1
- **status:** open
- **class:** product
- **evidence:** Ledger **Signal written by the engine, read by nothing**. CAP-9 lists the strip. POS-FLAGS-AMZN Then is a compressed “same strip”. Positions can grow a second projector. Join “scored Opps row” can mean the visible Opps slice. Ledger **Read model LIMIT 1**: join is exact ticker equality, same as Held. Sensei S3-06, S3-07, S3-16.
- **proposed fix:** Positions scored row points at the Opps engine row on the same snapshot fingerprint. The Opps strip composable paints that row. Positions does not compute Act/Disc/Upside/Conf/Lens. View filters on Opps do not hide the strip here. POS-FLAGS-AMZN: token-for-token equal on that snapshot.
- **second-order:** Positions Act disagrees with Opps. Or a filtered-off Opps name loses flags on Positions.

### A-26 — Positions doc homes are unnamed

- **id:** A-26
- **severity:** P1
- **status:** open
- **class:** doc-gap
- **evidence:** AGENTS.md one home per fact. Review does not close while docs are stale. On disk: AGENTS.md, `project-context.md`, `docs/advisor-csv-import.md`, and Android README still say Import book lives on Earnings and System. Parity Chase-book row names Held/pin, not the Positions tab. PRD header edit list still names `/3` import files. Sensei S3-11. POS-EMPTY covers Import on empty. FR-7 also puts Import on a full book (Sensei S3-08).
- **proposed fix:** PRD lists build edits: AGENTS.md Import-book sentence, `project-context.md`, `docs/advisor-csv-import.md`, Android README, parity Chase-book row. Case POS-IMPORT-NONEMPTY: same writer as Earnings/System. Do not put closeness percents in `earnings-gate-policy.yaml`.
- **second-order:** Build ships the tab. Standing docs still say two callers and no book home.

### A-27 — SM-3 live path does not split PHYL from scored flags

- **id:** A-27
- **severity:** P1
- **status:** open
- **class:** verification-gap
- **evidence:** Ledger **Verifying a property no live QA can reach**. SM-2 already has the unreachable clause. SM-3 names AMZN and “a closeness tag is one of” four tags. Android README `qa.txt` sample includes AMZN today. Four buckets on one live day stay unreachable. Off-feed PHYL is import-only. One-shot of PHYL would grow the feed and break SM-C3. Sensei S3-13.
- **proposed fix:** SM-3: PHYL from Import book only. Never one-shot. Never feed add. Scored Then uses a `qa` resident; if AMZN is absent, pick one scored `qa` name. Four closeness tags: CLOSE-* is the proof. Say live cannot hit all four in one session. Profile stays `qa`.
- **second-order:** Live QA one-shots PHYL to paint flags. SM-C3 dies. Or someone switches to `sp500`.

### S-2 — Restart / cancel still have no Case

- **id:** S-2
- **severity:** P1
- **status:** open
- **class:** verification-gap
- **evidence:** Advisor-r2 S-2. Ledger **Read model LIMIT 1** Do instead: test the public read after restart. PERSIST-* proves scale convert. Positions r3 does not add Confirm-death or Cancel-death rows.
- **proposed fix:** Same as r2: Confirm then death restores lots, as-of, Held; Cancel then death leaves the prior book. First Positions frame has lots or stays loading-empty, same as Earnings Held.
- **second-order:** Unique ticker is a schema line. Positions empty-flashes then fills.

## Matched anti-pattern rows

| Ledger row | r3 | Status |
| --- | --- | --- |
| YAML percent owns the earnings cell | FR-8 four tags. Yaml `/2` unchanged | mitigated |
| Positions CSV upserted as trades | Third caller of one writer | mitigated. Writer id still unnamed (A-26) |
| Signal written by the engine, read by nothing | CAP-9 reuses Opps fields. Closeness painter unnamed until A-20 | watch |
| Verifying a property no live QA can reach | PHYL `:core`. SM-3 missing SM-2-style split | **P1 A-27**. Not P0 because tests exist |
| Cold-start / auto-add / feed growth | Tap/assemble hydrate hole | **P0 A-19** |
| `pm clear` / Android QA on `sp500` | Named forbidden | mitigated |
| Read model `LIMIT 1` | Unique ticker. Join to Opps unnamed | watch A-25 |
| Backend refuse, UI mute dash | Off-feed dash badge can look like a score | watch A-19 type |
| Presenter tests cover dead code | CLOSE-LOG if Positions never loads the log | watch A-21 |

## Predicted P0s

| If build ignores | Why it is P0 later |
| --- | --- |
| A-19 | Tap PHYL opens Detail. Yahoo starts. Feed grows. |
| A-20 | Compose `LocalDate.now()` maps a Core date. Madrid ≠ NY. |
| A-21 Yahoo fetch | Closeness hydrates off-feed lots. Same as A-19. |
| A-22 | Watch shows PHYL. CAP-6 already forbids it; missing Case lets it ship. |
| A-27 | Live one-shot PHYL or `sp500` to paint SM-3. |

Must-fix-now on this spec: A-19, A-20.

## Lesson candidates

- “Does not add to Yahoo” is not the hydrate lock. Name assemble, Detail tap, and score-row field as three sentences.
- A clock lock needs a device-zone Case and a Core enum. A zone name in prose loses to `LocalDate.now()`.
- A repeated anti-pattern on a new metric (SM-3) needs the same unreachable clause SM-2 already has.
- Sensei can over-score a missing Case as P0 when the product rule already sits in CAP-6. Advisor keeps P0 for standing-doc breaks.

## Doc gaps

| Home | Work |
| --- | --- |
| CAP-8 / earnings-log SPEC | Name capture `today` = New York session day. `EXCHANGE_ZONE` is not in earnings-gate docs. Reviewer checks the Kotlin name. |
| AGENTS.md Import book sentence | Three callers: Earnings, System, Positions. |
| `docs/advisor-csv-import.md` | Same third caller. |
| `project-context.md` | Positions is the book home. Closeness is calendar identity. |
| Android README | Positions tab next to Earnings. Import on that tab. |
| `docs/cross-platform-parity.md` Chase-book row | Positions tab Android-only. |
| `earnings-gate-policy.yaml` | Do not add closeness percents or a day window. |
| Restart tests | S-2 |
| yaml `JPM-EMPTY-KEEP` | S-6 |

These stay scheduled except the two P0 spec holes. Stale operator docs do not block after A-19/A-20 if the PRD names the edit list (A-26).

## Regression traps

- Tap PHYL opens Detail and hydrates Yahoo.
- Assemble calls `ensure_symbol_loaded` for every lot.
- Compose `LocalDate.now()` or a second UTC today.
- Closeness percent or 14-day window in yaml `/2`.
- Friday→Monday tagged This week.
- Tag-string sort (Later before Today).
- Held mark or Act dash on PHYL.
- Pin mints PHYL on Watch/Tracked.
- Positions recomputes Act.
- Restore log on Positions writes lots.
- Import book copied instead of third caller.
- `pm clear` / `sp500` / one-shot PHYL.
- `positionSizeBps` from lot qty.
- Scores or printed rank buy the pin.
- PHYL painted as 12730000 shares.

## Anticipatory loop

Pass count: **4**.

1. **FR/CAP vs standing docs.** Calendar tags follow YAML-percent **Do instead**. NY clock has a home in earnings-log SPEC. Hydration and Compose clock do not. A-19, A-20.
2. **Ledger sweep.** CSV kinds, live `qa`, `positionSizeBps`, scores, one writer hold. Signal-reader and live-reachability are partial. Feed hydration is a repeated anti-pattern without full **Do instead**.
3. **r2 carry + Sensei conflict.** A-1..A-18 stay closed. S-1/S-4/S-5 closed. S-2 stays. Sensei S3-02 demoted: PIN-NO-INVENT already encodes no minted row. S3-01 and S3-03 stay P0.
4. **Second-order.** Approve now and builder wires Opps row tap onto PHYL, or Core emits a date. Madrid clock and feed growth ship. Verdict `revise`.

## Source flags for a later Reviewer

- Does Kotlin already name `EXCHANGE_ZONE`, or only “New York session day”? Do not open the file in this review.
- Does opening Detail always fetch Yahoo for a ticker absent from the feed?
- Does `DashboardTab.Earnings` laziness skip the log when Positions opens first?
- `qa.txt` membership vs SM-3 AMZN (README lists AMZN today).

## Next

Revise SPEC.md, `examples.md`, and PRD r3 for A-19 and A-20. Absorb A-21..A-27 as P1 Cases. Do not reopen A-1..A-18. Do not start Positions build while this review is `revise`.
