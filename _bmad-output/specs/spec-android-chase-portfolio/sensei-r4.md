# Sensei r4 — Positions tab

Admission: resumed from sensei-r3 (revise). Advisor wins conflicts. This is r4 after P0 absorption. Not a cold start.

Verdict: **approve**

Anticipatory pass count: **4**

Bar: remaining holes are P1/P2 only. Same bar as r2.

---

## Locked stance (do not reopen)

| Lock | Keep |
| --- | --- |
| Book | Identity. Yaml `/3` parse/merge home. Share goldens in yaml. Persist ten-thousandths at SQLite. |
| Held | Flag plus pin on Earnings / Opps / Watch / Tracked. Scores stay. Pin is paint. Pre-pin rank. |
| Feed | No feed hydration. No `positionSizeBps` mix. Ledger apply refused on Android. |
| Import | One writer. Restore log is a second Earnings action. |
| Live QA | `make android-run-qa` only. Never `pm clear`. Never `sp500`. |
| S3-02 | Advisor demotes to P1 A-22. PIN-NO-INVENT plus POS-PHYL-BOARDS. Do not raise it to P0 again. |

r4 does not reopen Import-book. It does not reopen the four NY tags.

---

## Absorption (r3 P0s)

| r3 id | Advisor | r4 status |
| --- | --- | --- |
| S3-01 | P0 A-19 | **closed** — assemble does not `ensure_symbol_loaded`, does not add a feed symbol, does not enqueue Yahoo. Tap off-feed is a no-op. Cases POS-NO-HYDRATE, POS-TAP-PHYL. |
| S3-02 | P1 A-22 | **closed as P1** — PIN-NO-INVENT plus POS-PHYL-BOARDS. Sensei does not reopen. |
| S3-03 | P0 A-20 | **closed** — Core emits `Today` / `Tomorrow` / `ThisWeek` / `Later` / `None`. Compose paints the token or omits `None`. Compose does not read a date. Compose does not call `LocalDate.now()`. Clock home is earnings-log New York session day. Case CLOSE-TZ. |

P1 Cases absorbed: POS-PHYL-BOARDS, POS-SORT-ORDINAL, POS-IMPORT-NONEMPTY, CLOSE-FRI-MON, CLOSE-SAT-MON, CLOSE-YAHOO-PAST, POS-FLAGS-AMZN, POS-FLAGS-PHYL, SM-3 split, SM-C3 boards, source 1/2, qty format, named doc homes.

---

## Findings

| id | severity | status | class | summary |
| --- | --- | --- | --- | --- |
| S3-01 | P0 | closed | product | A-19. Off-feed assemble/tap cannot reach Yahoo or Detail. |
| S3-02 | P1 | closed | product | A-22. Advisor demotion stands. |
| S3-03 | P0 | closed | product | A-20. Core enum. Compose has no date clock. |
| S4-01 | P1 | open | process | CLOSE-TZ asserts Core token. Add a paint + default-TZ assertion. |
| S4-02 | P1 | open | product | One clock name: NY session day = EXCHANGE_ZONE calendar day. |
| S4-03 | P1 | open | product | Positions Compose model carries the enum only. No closeness date field. |
| S4-04 | P1 | open | product | Source 1 is disk/memory log. Assemble makes zero provider calls. |
| S4-05 | P1 | open | product | Snapshot fingerprint fields are unnamed. |
| S4-06 | P1 | open | product | Join is exact ticker. Keep share-class identity. No `LIMIT 1`. |
| S4-07 | P1 | open | product | Missing engine row uses the off-feed shape. Do not score-fetch. |
| S4-08 | P2 | open | process | Same writer. Function id still unnamed. |
| S4-09 | P2 | open | product | Two lots, one ticker. Confirm yaml `/3` is one row per ticker. |
| S4-10 | P2 | open | product | Same-day stale Yahoo can still paint Today after the print. |
| S4-11 | P2 | open | product | Cost paints `avg_cost_cents` with the existing money format. |
| S4-12 | P2 | open | process | CLOSE table has no +2-day This week row. |
| S4-13 | P2 | open | product | Tab sits next to Earnings. Left/right unnamed. |
| S4-14 | P2 | open | product | Watchlist / Lens on the reused strip share Opps stores. |

---

## Bar-raising findings (open)

### S4-01 P1 process — CLOSE-TZ paint

CLOSE-TZ Given is device `Europe/Madrid`. Then is Core emits Today.

That Then sits in Core. Compose can still ignore the enum if a later model grows a date.

Build adds one paint Case:

| Case | Given | Then |
| --- | --- | --- |
| POS-PAINT-TZ | JVM/device default zone `Europe/Madrid`. Core token Today. | Compose paints Today. Compose does not call `LocalDate.now()`. |

CLOSE-TZ sets the default zone in the Core test too. A Given that never sets the zone is dead.

### S4-02 P1 product — one clock name

r3 named EXCHANGE_ZONE America/New_York (Earnings gate clock).

r4 names earnings-log New York session day.

Those are one helper. CAP-8 says so in one sentence. CLOSE-* dates are that calendar day. ISO week is the ISO week of that day.

Do not add a second session-day clock beside the CLOSE table.

### S4-03 P1 product — enum-only UI model

A-20 says Compose does not read a date.

Put that in the row type. The Positions UI row has `Today` / `Tomorrow` / `ThisWeek` / `Later` / `None`. It has no report date field for closeness.

A debug date on the model will become the next `LocalDate.now()`.

### S4-04 P1 product — source 1 is local

Source 1: `EarningsGateUi.upcoming`. Load log once if missing.

Source 2: existing score-row `nextEarningsEpoch`. Not a fetch.

Off-feed PHYL uses the log only.

A-19 already bans Yahoo enqueue. Name the rest: load once means read memory or disk. Positions assemble does not call a provider to fill upcoming for a lot. An empty log yields `None` this frame.

### S4-05 P1 product — fingerprint fields

POS-FLAGS-AMZN needs the same snapshot fingerprint.

Name the fields at build: engine revision, policy version, quote stamp. Opps and Positions scored strips share that id. Positions does not mix two generations.

### S4-06 P1 product — exact ticker join

Advisor locked join exact ticker. Keep it.

The ticker is the book lot identity (share class as stored). No lexical `LIMIT 1`. No display-name join.

A scored name off the Opps view still carries the strip. POS-FLAGS-AMZN already says view filters do not hide the strip.

### S4-07 P1 product — no score-fetch to complete a strip

A scored Opps row exists, or it does not.

No engine row → off-feed shape (ticker, qty, cost). Even when the ticker sits in the universe profile and is not loaded this session.

Assemble does not score-fetch to finish Act / Disc / Upside. A-19 covers feed add. This line covers the score path.

---

## Predicted P0s

| id | must-fix-now | prediction | why it stays P1 |
| --- | --- | --- | --- |
| S4-01 | no | Compose still maps a date. | Spec forbids Compose reading a date. Add paint Case at build. |
| S4-02 | no | Session day ≠ CLOSE calendar day. | One helper. CLOSE-TZ and CLOSE-* are the calendar. |
| S4-04 | no | `upcoming` fetches when the log is missing. | A-19 bans Yahoo enqueue. PHYL uses the log only. |
| S4-07 | no | Assemble scores book names that lack an Opps row. | Missing row already means off-feed shape. Write it at build. |
| S3-02 | no | Pin mints PHYL on Watch. | Advisor A-22. PIN-NO-INVENT plus POS-PHYL-BOARDS. |

No must-fix-now predicted P0. Verdict stays **approve**.

---

## Anticipatory passes

### Pass 1 — A-19 / A-20 vs the r3 P0 text

A-19 names assemble, tap, Yahoo, feed add, Detail. Cases POS-NO-HYDRATE and POS-TAP-PHYL match S3-01.

A-20 names the five Core tokens, Compose omit `None`, no `LocalDate.now()`, CLOSE-TZ. Matches S3-03.

Hole: CLOSE-TZ Then is Core-only. Paint Case is P1 S4-01. Not a new P0.

### Pass 2 — r2 collisions

Held pin still on Watch / Tracked. POS-PHYL-BOARDS plus PIN-NO-INVENT. Advisor owns that close.

Import third caller: POS-IMPORT-NONEMPTY, same writer. Writer id is P2.

Live QA: PHYL import-only, never one-shot, never feed add. Scored name is a `qa` resident. CLOSE-* owns the four tags. Honest. Matches `make android-run-qa`.

No feed hydration: A-19 plus source 2 not a fetch plus PHYL log-only. S4-04 / S4-07 tighten the local-log and no-score-fetch lines.

### Pass 3 — runtime after absorption

ISO wrap: CLOSE-FRI-MON and CLOSE-SAT-MON lock Later. Sunday → Monday stays Tomorrow. Do not reopen.

Sort: POS-SORT-ORDINAL locks the ordinal. Blank last.

Strip: Opps composable, token-for-token, no second badge brain, badge absent on PHYL. Fingerprint fields unnamed (S4-05). Join exact ticker (S4-06).

Clock inside Core: NY session day must be the CLOSE calendar helper (S4-02). Compose model must not carry a date (S4-03).

### Pass 4 — live QA, types, docs

SM-3 admits live cannot hit all four tags. CLOSE-* is the proof. That meets the standing rule: a property live cannot reach is verified by test.

Qty: decimals only when remainder non-zero, max four. Closed from S3-10.

Doc homes named. No closeness percents in `earnings-gate-policy.yaml`. Closed from S3-11.

Off-feed type: DecisionBadge absent, no dash chip. Closed from S3-15.

Four passes. No open P0. Stop.

---

## Lesson candidates

1. Advisor demotion holds when a standing Case already forbids the mint. POS-PHYL-BOARDS is the extra board proof. Do not re-raise it.
2. Core enum closes the clock only when Compose has no date field. A Core-only CLOSE-TZ is half the proof.
3. Live QA that says it cannot hit all four tags is honest. Tests own the rest.
4. "Load log once if missing" still needs "zero provider calls" or assemble grows a fetch.

---

## Strengths

- A-19 and A-20 close the two P0s with Cases, not slogans.
- Tap off-feed is a no-op. Assemble does not `ensure_symbol_loaded`.
- Core emits the five tokens. Compose omits `None`. Device zone has CLOSE-TZ.
- ISO wrap is locked: Fri/Sat → next Monday is Later.
- Sort ordinal has POS-SORT-ORDINAL.
- Strip reuse names the Opps composable, snapshot equality, and no second Act brain.
- PHYL badge is absent. A dash is forbidden.
- Source 2 is an existing score-row field, not a fetch. Off-feed uses the log only.
- SM-3 splits PHYL import-only vs scored `qa` resident. Live does not fake all four tags.
- SM-C3 plus POS-PHYL-BOARDS keep PHYL off Opps / Watch / Tracked.
- Import stays one writer on a full book. Restore log stays on Earnings.
- Doc homes are named. Gate YAML stays free of closeness percents.
- P&L stays a non-goal. Scores stay. No New Portfolio tab.

---

## Open risks (do not block)

| risk | class | note |
| --- | --- | --- |
| Paint vs Core | process | S4-01. Build adds POS-PAINT-TZ. |
| Clock alias | product | S4-02. One helper for session day and CLOSE dates. |
| Fingerprint drift | product | S4-05. Name the id fields in the spec table at build. |
| `qa` vs AMZN | environment | SM-3 already binds to a scored `qa` name when AMZN is absent. |
| Friday Later | product | CLOSE-FRI-MON is the ISO-week rule. Juan sees Later. That is accepted. |
| Same-day stale Yahoo | product | S4-10. Log-first is the live path. |
| Two lots | product | S4-09. yaml `/3` likely one row per ticker. |

---

## Build may proceed

Spec review is **approve**. Build reads this file plus the absorbed CAP-7 / CAP-8 / CAP-9 Cases.

Carry S4-01 through S4-07 as build notes. They do not reopen planning.

Do not reopen book-as-identity, yaml `/3`, ten-thousandths, held pin on scored boards, no `positionSizeBps`, live QA `qa`, A-22, or the four NY tags.
