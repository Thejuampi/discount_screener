# Sensei r1 — PRD: Android Chase book

| Field | Value |
| --- | --- |
| Artifact | `_bmad-output/planning-artifacts/prds/prd-android-chase-portfolio-2026-09-05` (draft PRD in brief) |
| Role | Sensei |
| Admission | `cold_start_waived`. New thread. No prior `sensei-r*.md`. |
| Source of law | Brief + locked stance. No application source. |
| Spike lock | Book-as-identity. Port Windows Chase/JPM CSV book import to Android. Flag holdings on earnings. Pin holdings first on lists. Scores stay. Feed membership stays. Lot qty does not write earnings `positionSizeBps`. |
| Anticipatory passes | 4 |
| Verdict | **revise** |

Open P0 findings force `revise`. Spec must not start on this draft.

---

## Verdict

**revise**

The lock is sound: book is identity, scores stay, feed stays, earnings cell stays. The draft still leaves three write-path holes that can destroy qty or write the wrong book. It also leaves the Android door unnamed. Fix those in the PRD. Then run Sensei again.

---

## Strengths

- Non-goals cut the usual leaks: no ledger apply, no tax lots, no Portfolio tab, no feed auto-add, no lot dollars in the earnings cell, no V2/V3/V4 score change.
- UJ-2 fails closed: blotter with empty book → `trades_without_book`.
- Snapshot confirm + cancel writes nothing. Empty keep is at least named.
- FR-4 names warm start before Held/pin paint.
- Pin order is per list (upcoming asc, settled desc, other lists keep current sort under held).
- `Unit Cost` vs Price, `1,273` → `1273`, Cash/QACDS drop, no short on oversell.
- Success names PHYL qty, same-day no-double, held-above-earlier-date, composite score equality.

---

## Bar-raising findings

| id | severity | status | class | Finding |
| --- | --- | --- | --- | --- |
| S-R1-P0-01 | P0 | open | product | Blotter apply window is only “same-day”. Snapshot as-of T already holds trades on and before T. A 90-day blotter still contains older rows. Those rows must not change qty/cost. Lock: trade date ≤ book as-of → skip. “Same-day only” lets a T−3 sell shrink PHYL below 1273. |
| S-R1-P0-02 | P0 | open | product | Merge replay is unlocked. User confirms the same `transactions*.csv` twice. As-of stays at snapshot date. Second merge applies the same post-as-of trades again. Qty doubles. Success “same-day blotter does not double” covers snapshot+one blotter, not blotter×2. Lock one: advance as-of, de-dupe by trade fingerprint, or refuse overlapping window. |
| S-R1-P0-03 | P0 | open | product | UJ-1 never names the door. Android has no desktop drop. The PRD must name one home (Earnings, System, or both with one writer) and the file path (SAF picker). Two homes with two writers fork confirm/cancel. Zero named homes means the spec invents the journey. |
| S-R1-P0-04 | P0 | open | product | Kind mixup can replace the book. Detect order lists Coinbase, Schwab, generic plus JPM/Chase. FR-1 names kind before write. It does not lock: wrong kind → refuse, or confirm with an explicit kind the user can cancel. A `positions*.csv` stored as `trades_window` (or the reverse) writes lots that UJ-2 cannot save. Spike lock is the two Chase/JPM files. Other kinds stay refuse-or-named-confirm, never silent write. |
| S-R1-P1-01 | P1 | open | product | Ticker identity is “exact uppercase equality” with no normalize step. JPM/Chase print `BRK.B` / `BRK/B` / `BRKB`. Yahoo may differ. Port the Windows identity function. Name trim, case, and class-share map. Exact-after-uppercase alone will miss Held. |
| S-R1-P1-02 | P1 | open | product | Pin vs rank ordinal. Score badges stay. If a list also prints rank 1..n from visual order, a held mid-score name becomes “rank 1”. Lock: pin is a sort-key overlay. Any printed rank/score ordinal is the pre-pin value. |
| S-R1-P1-03 | P1 | open | process | Success cites PHYL 1273. Non-goal forbids live `sp500` QA. `qa` may not hold PHYL. Lock a fixture/harness Case with a held ticker that is already on the list. Live `qa` only checks pin on intersection. A property the `qa` path cannot reach is a test, or it is unverified. |
| S-R1-P1-04 | P1 | open | process | FR-4 names SQLite lots + as-of. Acceptance has no schema version, migration, or process-death restore test. Existing Android DBs need a bump plan. Warm-start “before paint” needs one test that the first Held/pin frame matches disk. |
| S-R1-P1-05 | P1 | open | product | FR-6 pins Watch and Tracked. UJ-1 names Earnings and Opportunities. Lock the list set in the journey, or drop Watch/Tracked from FR-6. |
| S-R1-P1-06 | P1 | open | process | Standing rule: behaviour is a Gherkin Scenario Outline with ≥2 Cases. The PRD success list is four bullets, not example rows. Promote them to tables the spec can copy. Add the as-of skip Case and the blotter-replay Case. |
| S-R1-P1-07 | P1 | open | product | “Empty keep refuses” does not say what is empty: zero-byte file, zero equity rows after Cash/QACDS drop, or user confirm of an empty replace. Lock the reason code and that the prior book stays. |
| S-R1-P1-08 | P1 | open | product | Non-goal says lot qty does not write `positionSizeBps`. Success does not include a Case: held name, lots present, `positionSizeBps` equals the no-lot cell. Without that row, a later spec can “helpfully” scale the hedge. |
| S-R1-P1-09 | P1 | open | product | FR-4 wants restore before paint. It does not lock the first frame: block paint, or show a loading state with no false non-Held order. A flash of unpinned rows is a lie. |
| S-R1-P1-10 | P1 | open | process | “Port Windows” has no pointer to the Windows import contract, goldens, or parser tests. Spec will re-derive merge rules and drift. Name that home as source of law for kind, as-of, omit list, and qty. |
| S-R1-P1-11 | P1 | open | product | Fractional shares and CUSIP-only blotter rows are unnamed. `1273` is an integer Case. A half-share or CUSIP row must skip, round, or match by a named rule. |
| S-R1-P2-01 | P2 | open | product | UJ-1 shows Held on Earnings. Opportunities only pins. Lock whether Watch/Tracked/Opportunities also print the Held mark. |
| S-R1-P2-02 | P2 | open | product | Omit list is Cash and QACDS only. Money-market / sweep / other cash-like symbols may still enter lots. Point at the Windows omit set. |
| S-R1-P2-03 | P2 | open | product | Leftover and dip boards are unnamed. “Lists” in the lock may include them. Say in or out. |
| S-R1-P2-04 | P2 | open | environment | CSV encoding (UTF-8 BOM, Excel) is unnamed. Port the Windows reader rule. |
| S-R1-P2-05 | P2 | open | product | Second account is a non-goal. A single JPM file that already holds two accounts is unhandled. Lock: one book, refuse multi-account, or take the named account Windows takes. |

---

## Predicted P0s

Must-fix-now (also in the table):

1. **S-R1-P0-01** — trades dated before as-of change qty.
2. **S-R1-P0-02** — second blotter confirm doubles qty.
3. **S-R1-P0-03** — no named import door / SAF path.
4. **S-R1-P0-04** — wrong kind writes lots.

Predicted, not yet P0 in this draft (watch in spec):

| id | Why it becomes P0 |
| --- | --- |
| P0-pred-ticker | Class-share / Yahoo vs JPM symbol miss → Held absent on real book. |
| P0-pred-first-frame | Async restore paints non-Held, then jumps. |
| P0-pred-qa-phyl | Agent arms `sp500` live QA to see PHYL. Standing anti-pattern. |
| P0-pred-cell | Lot notional leaks into earnings hedge because no equality Case. |

None of the four predicted rows is must-fix-now in the PRD text. The four open P0s already force `revise`.

---

## Anticipatory loop (4 passes)

### Pass 1 — Confirm, kind, pin, identity, restart, qa, cell, schema, Gherkin, door

- Confirm UI is present (warn load/remove/omitted/as-of). Door still missing → **P0-03**.
- Kind is named. Mixup write still allowed → **P0-04**.
- Pin vs score is stated. Printed rank ordinal is not → **P1-02**.
- Exact uppercase is too thin → **P1-01**.
- Warm start is named. First frame is not locked → **P1-09**.
- PHYL success vs `qa` universe → **P1-03**.
- Cell identity in non-goals, missing success Case → **P1-08**.
- SQLite without schema/restore test → **P1-04**.
- Success bullets are not Gherkin tables → **P1-06**.
- Two homes vs one still open → **P0-03** / **P1-05**.

### Pass 2 — Merge math after snapshot+blotter

- Same-day skip does not cover date < as-of → **P0-01**.
- Blotter replay / as-of advance / fingerprint absent → **P0-02**.
- “Never aggregate blotter from zero” is good and stays.
- Oversell skip / no short stays.
- Empty keep is ambiguous → **P1-07**.

### Pass 3 — Environment and port source

- SAF / scoped storage unnamed (folded into **P0-03**).
- Windows goldens unnamed → **P1-10**.
- BOM/encoding → **P2-04**.
- Omit set may be wider than Cash+QACDS → **P2-02**.

### Pass 4 — What production hits after merge

- Fractional shares and CUSIP rows → **P1-11**.
- Multi-account in one file vs non-goal → **P2-05**.
- Leftover/dip lists → **P2-03**.
- Held badge scope vs pin-only screens → **P2-01**.

No pass cleared the four P0s. Stop. Verdict stays `revise`.

---

## Lesson candidates

1. Book-as-identity has three axes: lot qty, list sort, earnings cell. Lock each axis with one Case, or the third axis absorbs the lot.
2. Snapshot + window is an as-of inequality plus a replay rule. “Same-day” is one row of that table, not the rule.
3. Android import is a door (one writer, SAF). A desktop port that skips the door has no UJ.
4. Detector order is a refuse list until the spike names those brokers as in-scope.

---

## Open risks

- JPM/Chase symbol vs Yahoo symbol after the identity function is named.
- `qa` ∩ book may be empty in live QA; pin proof then lives only in fixtures.
- Schema bump on installs that already have unrelated SQLite tables.
- Coinbase/Schwab remaining in detect order after the PRD refuses those writes.
- First-frame restore vs Compose first draw if the spec allows a placeholder list.

---

## Required PRD edits before spec

1. Trade date ≤ book as-of → skip qty/cost (not only same-day).
2. Lock blotter replay: as-of advance, fingerprint, or refuse overlap.
3. Name one import home and SAF (or equivalent) as the file door. One writer.
4. Wrong kind: refuse, or confirm with cancel. No silent write. Out-of-spike brokers refuse.
5. Point at the Windows import contract/goldens as merge source of law.
6. Add example rows: as-of skip, blotter×2, `positionSizeBps` equality, restore-before-paint, held pin vs earlier date (upcoming and settled).
7. Name the ticker identity function (Windows port).
8. Lock printed rank/score ordinal = pre-pin. Pin is sort overlay.
9. Lock empty-snapshot reason code and keep-prior-book.
10. Name schema version + restore test in success.

---

## Anticipatory pass count

**4**

---

## Verdict

**revise**
