# Sensei r3 — Positions tab

Admission: reconstituted from sensei-r2 (approve on Import-book). This is r3 for the Positions tab. Not a silent cold start.

Verdict: **revise**

Anticipatory pass count: **4**

Bar: any open P0, including predicted P0 classed must-fix-now, forces revise.

---

## Locked stance (do not reopen)

| Lock | Keep |
| --- | --- |
| Book | Identity. Yaml `/3` parse/merge home. Share goldens in yaml. Persist ten-thousandths at SQLite. |
| Held | Flag plus pin on Earnings / Opps / Watch / Tracked. Scores stay. Pin is paint. Pre-pin rank. |
| Feed | No feed hydration. No `positionSizeBps` mix. Ledger apply refused on Android. |
| Import | One writer. Restore log is a second Earnings action. |
| Live QA | `make android-run-qa` only. Never `pm clear`. Never `sp500`. |

r3 adds Positions as the book home. It does not reopen Import-book.

---

## Verdict why

Three P0 holes will ship a wrong Positions tab if build follows the text as written.

1. Off-feed tap and assemble can still reach Detail / Yahoo.
2. Held pin on Watch / Tracked can still mint a PHYL row.
3. Core "projects closeness" can still mean a date that Compose maps with the device clock.

Fix those in CAP-7 / CAP-8 / CAP-9. Then this review can approve.

---

## Findings

| id | severity | status | class | summary |
| --- | --- | --- | --- | --- |
| S3-01 | P0 | open | product | Off-feed tap/assemble still allows Detail and Yahoo work. |
| S3-02 | P0 | open | product | Held pin can mint PHYL on Watch / Tracked. No Case. |
| S3-03 | P0 | open | product | Closeness output is not an enum. Compose can still own the clock. |
| S3-04 | P1 | open | process | Sort ordinal among the four tags has no Gherkin row. |
| S3-05 | P1 | open | product | ISO-week wrap (Fri/Sat → next Mon = Later) has no Case. |
| S3-06 | P1 | open | product | Strip reuse seam is unnamed. A second projector can drift. |
| S3-07 | P1 | open | product | "Scored Opps row" vs Opps view filter. Join identity unnamed. |
| S3-08 | P1 | open | process | Import on a non-empty Positions tab has no Case. Writer id unnamed. |
| S3-09 | P1 | open | product | Settled rule after source pick. Stale Yahoo past date has no Case. |
| S3-10 | P1 | open | product | Fractional qty format is unnamed. |
| S3-11 | P1 | open | process | Doc homes for the tab are unnamed. |
| S3-12 | P1 | open | process | POS-PHYL / POS-AMZN / POS-FLAGS lack Given/Then tables. |
| S3-13 | P1 | open | process | SM-3 does not split PHYL import-only vs scored one-shot. |
| S3-14 | P1 | open | product | Earnings-gate clock helper is unnamed for epoch → NY date. |
| S3-15 | P1 | open | product | Off-feed badge must be absent. A dash still reads as a score. |
| S3-16 | P1 | open | product | Strip equality needs the same snapshot fingerprint. |
| S3-17 | P2 | open | product | Off-feed tap is a no-op. No sheet. |
| S3-18 | P2 | open | product | Tab sits next to Earnings. Left/right order unnamed. |
| S3-19 | P2 | open | product | Restore log stays off Positions. |
| S3-20 | P2 | open | product | Cost paints `avg_cost_cents` with the existing money format. |
| S3-21 | P2 | open | product | Off-feed closeness is log-only. Yahoo path is empty. |
| S3-22 | P2 | open | product | Zero-qty lots unnamed. |
| S3-23 | P2 | open | product | Reused Watchlist / Lens actions share Opps side effects. |
| S3-24 | P2 | open | process | CLOSE table has no +2-day This week row. |

---

## Bar-raising findings

### S3-01 P0 product — off-feed must not enter Detail or Yahoo

FR-9 says tap off-feed does not add to Yahoo. It does not say the tap skips Detail. It does not say assemble skips feed work.

Detail on this product loads Yahoo. Assemble that joins lots to the feed will enqueue PHYL.

CAP-7 / CAP-9 must lock all four:

- Assemble Positions does not call `ensure_symbol_loaded` and does not add a feed symbol.
- Tap off-feed does not open Detail.
- Tap off-feed does not enqueue Yahoo or feed work.
- Off-feed row type has ticker, qty, cost only.

New Cases:

| Case | Given | Then |
| --- | --- | --- |
| POS-NO-HYDRATE | Book has PHYL. PHYL is off-feed. Positions assembles. | No Yahoo call. No feed add. No `ensure_symbol_loaded` for PHYL. |
| POS-TAP-PHYL | Juan taps the PHYL row. | No Detail. No Yahoo. No feed add. Row stays as ticker / qty / cost. |

SM-3 already wants PHYL with 1273 shares and no Act. Add the no-Detail / no-hydrate Then.

### S3-02 P0 product — held off-feed stays off Watch and Tracked

r2 still pins held names on Earnings / Opps / Watch / Tracked.

r3 FR-7 says off-feed lots stay out of Opps / Watch / Tracked.

SM-C3 covers Opps only. Pin-held-first still runs on Watch. PHYL is a held lot. Build will mint a Watch row unless a Case forbids it.

CAP-7 success must include all three boards. Pin-held-first does not create a row that the board did not already have.

| Case | Given | Then |
| --- | --- | --- |
| POS-PHYL-BOARDS | Book has PHYL. PHYL is off-feed. | PHYL is absent on Opps, Watch, Tracked. Earnings does not grow a PHYL universe row. Pin-held-first does not mint one. |
| POS-PHYL | Book has PHYL 1273 shares. | Positions shows one PHYL lot. Qty 1273. No Act. No score fields. |

Held pin on scored names stays as r2. This Case only blocks a new row for an off-feed lot.

### S3-03 P0 product — Core emits the closeness enum

The lock says Core projects closeness and Compose paints.

That sentence still allows Core to emit a date. Compose then maps the date with `LocalDate.now()`. That is the device clock. The UTC draft already failed this way.

CAP-8 must lock the output type:

- Core emits `Today`, `Tomorrow`, `ThisWeek`, `Later`, or `None`.
- Compose paints the tag or omits it.
- Compose does not read a date.
- Compose does not call `LocalDate.now()`.
- "Today" is the America/New_York calendar day of now. Same helper as the Earnings gate clock.

New Case:

| Case | Given | Then |
| --- | --- | --- |
| CLOSE-TZ | Device zone `Europe/Madrid`. NY date 2026-09-07. Report 2026-09-07. | Tag is Today. |

Name the Earnings-gate helper in CAP-8 (S3-14). Do not add a second UTC today.

### S3-04 P1 process — closeness ordinal

FR-7 says sort closeness then ticker. Blank last.

POS-SORT only tests tagged vs blank. POS-SORT-TICKER tests two blanks.

List order is the ordinal. Write it. Add a Case so build does not sort tag strings.

Ordinal: Today < Tomorrow < This week < Later < blank. Then ticker ASC.

| Case | Given | Then |
| --- | --- | --- |
| POS-SORT-ORDINAL | AMZN Later. MSFT Today. PHYL blank. | MSFT, AMZN, PHYL. |

### S3-05 P1 product — ISO week wrap

Do not change the ISO-week lock.

From Friday or Saturday, next Monday is a new ISO week. The tag is Later. Tomorrow stays calendar +1, so Sunday → Monday stays Tomorrow.

Add the wrap rows so build does not "fix" Later to This week.

| Case | Today NY | Report | Tag |
| --- | --- | --- | --- |
| CLOSE-FRI-MON | 2026-09-11 Fri | 2026-09-14 | Later |
| CLOSE-SAT-MON | 2026-09-12 Sat | 2026-09-14 | Later |
| CLOSE-SUN-MON | 2026-09-13 Sun | 2026-09-14 | Tomorrow |

CAP-8 success line: Juan on Friday sees Later for a Monday print. That is the ISO-week rule.

### S3-06 P1 product — one strip seam

"Reuse Opps strip and metric tokens" can still mean a second projector that copies fields.

Lock the seam:

- Positions scored row holds a pointer to the Opps engine row on the same snapshot.
- The Opps strip composable paints that row.
- Positions does not compute `DecisionBadge`, Disc, Upside, Conf, or Lens chips.

POS-FLAGS-AMZN: token-for-token equal to Opps AMZN on that snapshot.

### S3-07 P1 product — scored row means engine row

"When a scored Opps row exists" must mean the engine row for that book identity.

It does not mean the name is visible on the Opps slice.

Join is book identity to the scored engine row. No lexical `LIMIT 1`.

A scored name that is off the Opps window still carries the strip on Positions.

An off-feed name has no engine row. The off-feed shape applies.

### S3-08 P1 process — third caller on a full book

POS-EMPTY covers empty state plus Import book.

FR-7 also puts Import book on this tab when lots exist. Name the same writer as Earnings and System. No parse/merge copy.

| Case | Given | Then |
| --- | --- | --- |
| POS-IMPORT-NONEMPTY | Book has AMZN. Positions is open. | Import book is present. It calls the same writer as Earnings / System. |

### S3-09 P1 product — settled after source pick

Pick the date: log upcoming `reportEpochDay` first, else Yahoo `nextEarningsEpoch` date, else none.

Then map. A chosen date before today NY is `None`. Missing is `None`.

| Case | Log | Yahoo | Tag |
| --- | --- | --- | --- |
| CLOSE-YAHOO-PAST | none | 2026-09-06 | blank |

Do not invent a percent. Do not use a 14-day window. Do not reuse the Opps earnings-mark sentence.

Same-day after the print can stay Today when the log still has upcoming or Yahoo still has today. The log-first rule is the live path.

### S3-10 P1 product — qty format

Qty is `quantity_ten_thousandths / 10000` in shares.

PHYL 12_730_000 → `1273`.

Name fractional paint: show decimals only when the remainder is non-zero. Max four decimal places. No trailing zeros.

### S3-11 P1 process — doc homes

Build must update these homes. Review fails if they stay on the old New Portfolio / percent story.

- `docs/` operator index for the Positions tab
- `_bmad-output/project-context.md` book home
- `Agents.md` tab label and live QA SM-3 / SM-C3
- live QA checklist: `make android-run-qa`, PHYL import-only

### S3-12 P1 process — executable POS rows

CAP-7 names POS-PHYL, POS-AMZN, POS-SORT, POS-SORT-TICKER, POS-EMPTY. CAP-9 names POS-FLAGS-AMZN, POS-FLAGS-PHYL.

Write Given / Then. A Case name is not a test.

Minimum POS-AMZN: on-feed scored lot paints qty, cost, closeness if a date exists, and the Opps strip.

Minimum POS-FLAGS-PHYL: DecisionBadge absent. No Act / Watch / Avoid. No dash badge. Qty and cost present.

### S3-13 P1 process — live QA split

SM-3 hard-codes AMZN. The `qa` feed is a gap sample. AMZN may be absent.

Lock:

- PHYL comes from Import book only. Never one-shot. Never feed add.
- The scored name is a `qa` resident, or a checklist one-shot that is not PHYL.
- If AMZN is absent from `qa`, pick one scored `qa` name and keep the same Then.
- Profile stays `qa`. No `sp500`. No `pm clear`.

### S3-14 P1 product — one clock helper

`reportEpochDay` and Yahoo `nextEarningsEpoch` convert through the Earnings gate EXCHANGE_ZONE helper.

ISO week is the ISO week of that NY calendar date.

Do not convert with UTC `toEpochDay()` as "today".

### S3-15 P1 product — badge unrepresentable on off-feed

Off-feed is a row shape without score fields.

`DecisionBadge` is absent from the type. Do not store null. Do not paint `—`.

POS-FLAGS-PHYL Then: no Act chip, no Watch chip, no Avoid chip, no placeholder chip.

### S3-16 P1 product — same snapshot

"Strip equals Opps for that ticker on the same snapshot" needs a fingerprint.

One assemble input for Opps and Positions scored fields. If Positions assembles later, it rereads the same snapshot id. It does not mix two generations.

---

## Predicted P0s

| id | must-fix-now | prediction | why now |
| --- | --- | --- | --- |
| S3-01 | yes | Tap PHYL opens Detail and hydrates Yahoo. | FR-9 only bans add-to-Yahoo. Detail is the hydrate path. |
| S3-02 | yes | Pin-held-first paints PHYL on Watch. | r2 pin still on. SM-C3 covers Opps only. |
| S3-03 | yes | Compose maps a Core date with device `LocalDate.now()`. | "Projects closeness" allows a date. UTC already failed. |
| S3-06 | no | Positions recomputes Act and disagrees with Opps. | CAP-9 reuse exists. Name the seam (P1). |
| S3-09 | no | Stale Yahoo paints Today on a settled print. | Log-first plus settled-on-chosen-date covers past dates if S3-09 lands. |
| S3-13 | no | Live QA one-shots PHYL to make SM-3 pretty. | Standing no-hydrate lock exists. Write the split (P1). |
| S3-14 | no | Yahoo epoch becomes UTC day and flips the tag. | Clock lock names NY. Name the helper (P1). |
| S3-05 | no | Build treats Fri → Mon as This week. | ISO-week lock is explicit. Add wrap Cases (P1). |

Must-fix-now predicted P0s = S3-01, S3-02, S3-03. Verdict stays **revise**.

---

## Required spec deltas (revise bar)

### CAP-7

Intent stays: Juan reads the full book on one tab, including names that never join Opps.

Add success lines:

- Every lot is a row. PHYL included.
- Empty book shows empty state and Import book.
- Import book stays on a non-empty tab. Same writer as Earnings / System.
- Sort ordinal Today < Tomorrow < This week < Later < blank, then ticker ASC.
- Held mark omitted. Pin-held-first does not run on this tab.
- Off-feed lots stay absent on Opps, Watch, Tracked. Pin does not mint them.
- Assemble does not hydrate feed or Yahoo.
- Tap off-feed does not open Detail and does not enqueue Yahoo.

Cases: POS-PHYL, POS-AMZN, POS-SORT, POS-SORT-TICKER, POS-SORT-ORDINAL, POS-EMPTY, POS-IMPORT-NONEMPTY, POS-NO-HYDRATE, POS-TAP-PHYL, POS-PHYL-BOARDS.

### CAP-8

Intent stays: Juan sees how near the next report is. No frozen percent.

Add success lines:

- Core emits `Today` / `Tomorrow` / `ThisWeek` / `Later` / `None`.
- Compose paints that enum. Compose does not interpret dates.
- Clock is Earnings-gate `EXCHANGE_ZONE` America/New_York.
- Source is log upcoming `reportEpochDay`, else Yahoo date, else `None`.
- Chosen date before today NY is `None`.
- Sunday → Monday is Tomorrow. Friday/Saturday → next Monday is Later.

Cases: CLOSE-TODAY, CLOSE-TOMORROW, CLOSE-WEEK, CLOSE-LATER, CLOSE-SUN-MON, CLOSE-FRI-MON, CLOSE-SAT-MON, CLOSE-SETTLED, CLOSE-MISSING, CLOSE-LOG, CLOSE-YAHOO, CLOSE-NO-DATE, CLOSE-YAHOO-PAST, CLOSE-TZ.

Keep the existing CLOSE table. Today is 2026-09-07 Monday America/New_York unless the Case names another today.

### CAP-9

Intent stays: a scored lot carries the same flags Juan already reads on Opps.

Add success lines:

- Shared Opps strip composable. Same snapshot fingerprint. No second badge brain.
- Join is book identity to the engine row. View filters on Opps do not hide the strip here.
- Off-feed type has no score fields. Badge absent.
- Tap scored lot opens Detail. Tap off-feed does no navigation and no Yahoo.

Cases: POS-FLAGS-AMZN, POS-FLAGS-PHYL, POS-TAP-PHYL.

### SM-3 / SM-C3

- SM-3: PHYL 1273 shares, no Act, no Detail on tap, no hydrate. Scored name matches Opps flags. Closeness tag when a date exists.
- SM-C3: PHYL absent on Opps / Watch / Tracked. Positions does not invent a score.
- PHYL from Import book only.

### Non-goals (keep)

- No New Portfolio tab.
- No P&L overlay.
- No auto-add to feed.
- No `positionSizeBps` mix.
- No restore log on Positions.
- Scores stay on the Opps engine.

---

## Anticipatory passes

### Pass 1 — FR / CAP vs Gherkin

CLOSE table covers today, tomorrow, this week, later, Sunday wrap, settled, missing, log vs Yahoo.

Gaps: tag ordinal, ISO wrap Fri/Sat, device zone, stale Yahoo past date, POS Given/Then, Import on a full book.

Outcome: S3-03, S3-04, S3-05, S3-08, S3-09, S3-12.

### Pass 2 — r2 collisions

Held pin still runs on Watch / Tracked. Off-feed PHYL is held. SM-C3 only names Opps.

Import is a third caller. Writer id unnamed. Copy-paste parse is the failure.

No feed hydration is a standing lock. Assemble and Detail tap still leak.

Outcome: S3-01, S3-02, S3-08, S3-13.

### Pass 3 — runtime failure modes

Device `LocalDate.now()` after the UTC miss.

Strip re-derived on Positions. Act appears on PHYL as a dash or a default.

Tap PHYL opens Detail. Yahoo starts.

ISO-week Later on Friday looks wrong unless the Case locks it.

Outcome: S3-01, S3-03, S3-05, S3-06, S3-15.

### Pass 4 — live QA and types

`qa` may omit AMZN. One-shot on PHYL would destroy SM-C3.

Off-feed as nullable scores invites invented Act.

Core date vs Core enum is the clock bug in a new suit.

Doc homes still describe a New Portfolio tab if unnamed.

Outcome: S3-03, S3-10, S3-11, S3-13, S3-15, S3-16.

Four passes. P0s remain open. Stop.

---

## Lesson candidates

1. A clock lock needs a device-zone Case. A zone name in prose loses to `LocalDate.now()`.
2. A held flag plus an off-feed lot needs a Case on every board that still pins.
3. Reuse is one seam. "Same flags" as a sentence allows a second projector.
4. A third caller names the writer id. Empty-state Import is not the third caller.
5. "Does not add to Yahoo" is not the same sentence as "does not open Detail".

---

## Strengths

- Retract of New Portfolio. The book has one home: Positions next to Earnings.
- Every lot paints, including off-feed PHYL. Empty state is explicit.
- Four calendar tags replace a frozen percent and a 14-day window.
- EXCHANGE_ZONE named. UTC draft superseded. Sunday → Monday pinned as Tomorrow.
- Source order is log then Yahoo then blank. No invented date.
- Off-feed row shape without score fields. Right type direction.
- Import stays one writer. Restore log stays on Earnings.
- Strip reuse is the right product move when a scored Opps row exists.
- P&L stays a non-goal. Scores stay. No auto-add to feed.
- CLOSE table already has ten date/source rows. POS-SORT blank last is right.
- SM-C3 keeps PHYL off Opps. Core projects, Compose paints.

---

## Open risks (after the P0 fixes)

| risk | class | note |
| --- | --- | --- |
| ISO week vs Juan's "week" | product | Friday → Monday print shows Later. CAP-8 must say that out loud. |
| Same-day stale Yahoo | product | After the print, Today can remain if log upcoming is gone and Yahoo still has today. |
| Two lots, one ticker | product | FR-7 says every lot. Sort then needs a stable third key. Confirm the book is one row per ticker from yaml `/3`. |
| `qa` vs AMZN | environment | Scored Then must bind to a `qa` name when AMZN is absent. |
| Strip actions | product | Watchlist / Lens on the reused strip mutate the same stores as Opps. That is reuse. Confirm it. |
| Fractional shares | product | Format in S3-10. Chase can print a remainder. |

---

## Sensei ask of Advisor

Advisor wins. If Advisor keeps S3-01, S3-02, and S3-03 as P0, spec stays on revise until those Cases land.

Do not reopen book-as-identity, yaml `/3`, ten-thousandths, held pin on scored boards, no `positionSizeBps`, live QA `qa`, or the four NY tags.
