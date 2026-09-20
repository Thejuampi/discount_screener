---
artifact: advisor-r1
spike: prd-android-chase-portfolio-2026-09-05
artifact_under_review: prd.md
admission: cold_start_waived
locked_stance: Book-as-identity. Port advisor-csv-import/2 snapshot+window to Android. Held flag + pin. Scores unchanged. No feed hydration. No positionSizeBps mix. Ledger apply refused on Android.
verdict: revise
anticipatory_passes: 4
date: 2026-09-05
---

# Advisor r1 — Android Chase book PRD

Docs only. No application source.

## Verdict

`revise`

Two open P0s. Juan must name a P0 waiver or the PRD must absorb the fixes. Spec does not start while this review is `revise`.

## Bar check

| Gate | Result |
| --- | --- |
| Correctness Over Delivery Convenience | Fail. Open P0. |
| Standing docs read | Pass. List below. |
| Spike lock vs PRD | Fail. Unique-ticker / no `LIMIT 1` left the brainstorm and did not land in FR-4. |
| Anti-pattern **Do instead** on CSV kinds | Pass. FR-1, FR-2, FR-3 follow the ledger row. |
| Contract home for Android as-of / owner | Fail. Yaml still names Windows localStorage. |
| Android PRD live QA expectations | Fail. Framework requires them. PRD omits the command. |

## Docs read

- `AGENTS.md` (Advisor, earnings gate, live QA, specification by example, one home per fact)
- `_bmad-output/project-context.md`
- `docs/operational-anti-patterns.md`
- `_bmad-output/planning-artifacts/prds/prd-android-chase-portfolio-2026-09-05/prd.md`
- `_bmad-output/planning-artifacts/prds/prd-android-chase-portfolio-2026-09-05/.memlog.md`
- `_bmad-output/brainstorming/brainstorm-android-chase-portfolio-2026-09-05/.memlog.md`
- `_bmad-output/brainstorming/brainstorm-android-chase-portfolio-2026-09-05/brainstorm-intent.md`
- `docs/advisor-csv-import.md`
- `shared/contracts/advisor-csv-import-v1.yaml` (`advisor-csv-import/2`)
- `_bmad-output/specs/spec-advisor-csv-import/SPEC.md` and its memlog
- `shared/contracts/README.md`
- `shared/contracts/persistence-semantics.md`
- `.grok/rules/bmad.md`
- `.grok/agents/advisor.md`
- `docs/cross-platform-parity.md`
- `docs/index.md`
- `_bmad-output/planning-artifacts/documentation-framework.md`
- `_bmad-output/planning-artifacts/current-functionality-prd.md`
- `_bmad-output/planning-artifacts/prd-pre-earnings-risk-gate-2026-08-27.md`
- `_bmad-output/specs/spec-earnings-gate-identities/SPEC.md` (CAP-5 `positionSizeBps`)
- `apps/android/README.md` (operator surfaces, QA launcher)

## Locked stance (followed)

The PRD keeps the spike lock:

- Book-as-identity.
- Port snapshot + window from `advisor-csv-import/2`.
- Ledger apply refuses on Android.
- Held flag + pin.
- Scores stay.
- No feed hydration.
- Lot quantity does not write `positionSizeBps`.

Those locks stay. The revise is for missing homes and two P0 holes.

## Proposed steps vs doc homes

| PRD step | Doc rule or ledger row |
| --- | --- |
| FR-1 name kind before write | Ledger **Positions CSV upserted as trades**. Yaml `kinds` + `detectionOrder`. |
| FR-2 snapshot warn then confirm replace | Yaml `holdings_snapshot.write`. SPEC CAP-3. Same ledger **Do instead**. |
| FR-3 window merge after as-of; refuse empty book | Yaml `trades_window.merge`. SPEC CAP-4. Same ledger **Do instead**. |
| FR-4 SQLite lots + as-of | Brainstorm lock. **Breaks** yaml `localStorage` key `ds_advisor_book_as_of` until the contract splits platform storage. |
| FR-5 Held on earnings; `positionSizeBps` stays cell identity | Earnings identities SPEC CAP-5. Ledger **YAML percent owns the earnings cell**. |
| FR-6 pin Held first; scores unchanged | Brainstorm lock. `project-context.md` V2/V3/V4 stay. **Conflicts** `persistence-semantics.md` Tracked operator order until the PRD splits paint vs persist. |
| No auto-add to Yahoo feed | AGENTS.md live QA: one-shot load must not grow the feed. Startup stays bounded. |
| Ledger detect + refuse apply | Brainstorm lock. Ledger **Backend refuse, UI mute dash** until the reason code is named. |
| Yaml examples must pass | SPEC-advisor-csv-import: yaml examples are the cases. |
| Live QA on `sp500` is a non-goal | Ledger **Android QA on default sp500**. Incomplete: PRD never names `make android-run-qa`. |

## Matched anti-pattern rows

| Ledger row | Match on this PRD | Status |
| --- | --- | --- |
| Positions CSV upserted as trades | FR-1 / FR-2 / FR-3 use the **Do instead** | mitigated |
| YAML percent owns the earnings cell | FR-5 keeps `positionSizeBps` as full/half/exit | mitigated |
| Read model resolves current identity with `LIMIT 1` | FR-4 stores lots with no unique ticker / fail-closed coordinate | **open P0** (A-2) |
| Backend refuse, UI mute dash | Ledger apply “with a reason”; code unnamed | open P1 (A-6) |
| Required native/live gates relabeled optional | Android surface PRD omits live QA command | open P1 (A-3) |
| Only automated tests | Same hole | open P1 (A-3) |
| `pm clear` / wipe app data for QA | Unnamed. Brainstorm listed it as a worst idea | open P1 (A-3) |
| Cold-start full SP500 / Android QA on default `sp500` | Non-goal names `sp500`. UJ-1 still says `qa` or `sp500` already warm | open P1 (A-3) |
| A property no live QA can reach | Off-feed lots cannot show Held on device `qa` | open P1 (A-8) |
| Signal written by the engine, read by nothing | FR-5 / FR-6 name screen readers | mitigated |
| One client's sieve copied to the other | Copying Windows localStorage as-of onto Android | absorbed by A-1 |
| Do not change V2/V3/V4 scores | SM-C1 | mitigated |

## Findings

### A-1 — Contract still names Windows only

- **id:** A-1
- **severity:** P0
- **status:** open
- **class:** contract-drift
- **evidence:** `shared/contracts/advisor-csv-import-v1.yaml` `surface: windows-advisor`, `owner: apps/windows/src/portfolioCsv.ts`, `holdings_snapshot.write` stores as-of in `localStorage` key `ds_advisor_book_as_of`. SPEC-advisor-csv-import non-goal: “No Android import in this slice.” `docs/advisor-csv-import.md` title: Windows Advisor import. `shared/contracts/README.md` and `_bmad-output/project-context.md` say Windows kinds. AGENTS.md: one home per fact; edit the yaml; do not put a second copy in Kotlin. Brainstorm reverse: same yaml, add Android owner.
- **proposed fix:** PRD acceptance must require a contract edit in the same spike: add Android `:core` as snapshot+window owner; split as-of by platform (Windows `localStorage`, Android SQLite meta); name Android ledger-apply refuse; keep yaml examples as the parse/merge cases. Retract or supersede the SPEC non-goal. Point `docs/advisor-csv-import.md`, contracts README, and project-context at both surfaces. Bump `policyVersion` if the yaml grows a new refuse or storage clause.
- **second-order:** A builder who “ports `/2`” with the yaml frozen will copy detect headers into Kotlin or keep as-of in RAM. Restart then merges the blotter twice.

### A-2 — Lots have no fail-closed ticker coordinate

- **id:** A-2
- **severity:** P0
- **status:** open
- **class:** anti-pattern
- **evidence:** Ledger **Read model resolves “current” identity with `LIMIT 1`**. Do instead: authoritative current coordinate or fail closed; test the public read after restart. Brainstorm MUST: unique symbol, exact uppercase match, no `LIMIT 1` lexical identity. PRD FR-4: “SQLite holds lots and as-of.” FR-5 exact match is display only. `persistence-semantics.md`: watchlist is a set without duplicates.
- **proposed fix:** FR-4 names one current lot per trimmed uppercase ticker. Duplicate ticker in the file aggregates under snapshot rules or refuses. SQLite unique on that ticker. Public Held/pin read after process death uses that coordinate. No lexical `LIMIT 1`.
- **second-order:** Two AMZN rows make Held true and quantity arbitrary. Same-day PHYL 1273 then looks like a merge bug.

### A-3 — Android PRD omits live QA expectations

- **id:** A-3
- **severity:** P1
- **status:** open
- **class:** verification-gap
- **evidence:** `_bmad-output/planning-artifacts/documentation-framework.md` PRD rules: Android app-surface PRDs must include live QA expectations. AGENTS.md: `make android-run-qa`; never `pm clear`; never switch to `sp500`. Ledger rows: **Only automated tests**, **Required native/live gates relabeled optional**, **`pm clear` / wipe app data**, **Android QA on default `sp500`**. UJ-1 says profile `qa` or `sp500` already warm.
- **proposed fix:** Add a verification bar: `:core` yaml examples; SQLite/repository restart restore; presenter pin tests; later live `make android-run-qa` when Juan says the product is ready. Name `pm clear` as forbidden. Split operator `sp500` use from agent QA. UJ-1 uses a warm `qa` profile for the agent path.
- **second-order:** A later builder will hydrate PHYL on `sp500` or wipe SQLite to “prove” persist.

### A-4 — Two SAF families on Earnings stay unnamed

- **id:** A-4
- **severity:** P1
- **status:** open
- **class:** product
- **evidence:** Earnings log already leaves and enters by SAF (`prd-pre-earnings-risk-gate-2026-08-27` memlog; review-rubric). This PRD §4.1 “One SAF picker.” Brainstorm SCAMPER: combine OpenDocument restore with kind-detect import. Lock is Import book as one action on Earnings and System. Ledger **Positions CSV upserted as trades**: name the kind before any write.
- **proposed fix:** PRD names two Earnings actions: restore the earnings log, import the book. Kind-detect applies inside book CSV only. System calls the same book action. A JSONL restore never plans a lot write.
- **second-order:** One picker that feeds both will restore a CSV into the log or parse a log as a blotter.

### A-5 — Tracked pin fights persisted operator order

- **id:** A-5
- **severity:** P1
- **status:** open
- **class:** contract-drift
- **evidence:** `shared/contracts/persistence-semantics.md`: Tracked symbols preserve operator-selected order. Restore must not promote an unwatched symbol into the watchlist. FR-6 pins Held first on Tracked and Watch, then the current sort.
- **proposed fix:** Pin is paint. SQLite tracked order stays. Watchlist membership stays a set. A lot ticker absent from Tracked or Watchlist does not insert a row and does not join the watchlist.
- **second-order:** A writer who “fixes” pin by rewriting tracked order will scramble the operator book on the next restore.

### A-6 — Ledger refuse has no named reason the card can print

- **id:** A-6
- **severity:** P1
- **status:** open
- **class:** anti-pattern
- **evidence:** Ledger **Backend refuse, UI mute dash**. FR-1: “Ledger detect may succeed; Android refuses apply with a reason.” Window reasons exist (`trades_without_book`, `missing_book_as_of`, `empty_keep`). Ledger apply has no code. AGENTS.md: sparse states stay explicit.
- **proposed fix:** Name `ledger_apply_unsupported` (or the yaml token the contract adds in A-1). Dialog prints it. Cancel/dismiss writes nothing.
- **second-order:** Detect success plus empty copy looks like a hang.

### A-7 — Pin owner is unspecified

- **id:** A-7
- **severity:** P1
- **status:** open
- **class:** architecture
- **evidence:** `project-context.md`: Compose screens are passive Views. Presenters map state. Valuation and scoring rules stay in `core`. Do not change V2/V3/V4 scores. FR-6 changes list order. Brainstorm: same held set; do not hire a new engine.
- **proposed fix:** Spec locks pin as a presenter (or core projection) sort over an unchanged score snapshot. `OpportunityEngine` output stays. Compose does not decide Held.
- **second-order:** Pin inside the scoring engine will shift tests that read list ordinal and still keep SM-C1 green.

### A-8 — Off-feed Held is unreachable on live `qa`

- **id:** A-8
- **severity:** P1
- **status:** open
- **class:** verification-gap
- **evidence:** Ledger **A property no live QA can reach is verified by test**. Non-goal: no auto-add to the feed. SM-2 needs an earnings row for a lot. `qa` is ≤20 symbols. PHYL may sit off that list.
- **proposed fix:** Prove PHYL 1273 and same-day merge in `:core` against yaml examples. Prove Held/pin with a fixture ticker that `qa` already shows, or say live SM-2 is unreachable and the test is the proof. Do not grow the feed to chase Held.
- **second-order:** Live QA on `sp500` to see PHYL repeats the universe thrash row.

### A-9 — Parity exception is unnamed

- **id:** A-9
- **severity:** P1
- **status:** open
- **class:** doc-gap
- **evidence:** `docs/cross-platform-parity.md`: 1:1 default. One-platform work must name the exception. Windows already imports. Desktop has no book. Earnings is Android-only. PRD non-goal: Windows UI change. Desktop is silent. Documentation framework: name platform exceptions.
- **proposed fix:** PRD plus later parity row: Android gains snapshot+window import, Held, and pin. Windows import stays. Desktop stays without import this spike. Held/pin stay Android list/earnings paint.
- **second-order:** A later agent will port Held onto Windows Advisor or demand a desktop CSV picker from the default parity rule.

### A-10 — Degraded import states are thin

- **id:** A-10
- **severity:** P1
- **status:** open
- **class:** doc-gap
- **evidence:** Documentation framework: PRDs must include degraded states when data can be missing, stale, sparse, partial, or restored. PRD names empty keep, blotter without book, missing as-of, Cancel. It omits unknown kind, unreadable file, as-of parse fail, duplicate ticker (A-2), and restart with a planned confirm that never wrote.
- **proposed fix:** Add those refuses with reason codes. Unknown kind writes nothing. A planned confirm lives only in memory until Confirm.
- **second-order:** Parse failure that still upserts a partial keep set wipes omitted lots.

### A-11 — Window merge close-lot / Type map sit only in the Windows SPEC

- **id:** A-11
- **severity:** P2
- **status:** open
- **class:** product
- **evidence:** SPEC-advisor-csv-import CAP-5 (Type map) and review finding: a window that closes a lot lists that symbol in `remove`. Yaml examples table has no close-lot row. PRD FR-3 omits both. PRD 6.1: yaml examples must pass.
- **proposed fix:** Spec carries CAP-5 and close-lot remove. Add a yaml example if `/2` is the home, so Android cannot skip a Windows patch.
- **second-order:** Android keeps a zero-qty lot and pins Held on a name Juan sold after as-of.

### A-12 — Watch vs watchlist name

- **id:** A-12
- **severity:** P2
- **status:** open
- **class:** doc-gap
- **evidence:** PRD FR-6 “Watch”. `current-functionality-prd.md` and Android README: watchlist. Discovery is a fourth list and is unnamed as out of scope.
- **proposed fix:** Say watchlist surface. Name Discovery and Plans boards as out of pin scope (Plans already out in 6.2).
- **second-order:** A builder pins Discovery because it is “a list”.

### A-13 — Restore lots must stay local

- **id:** A-13
- **severity:** P2
- **status:** open
- **class:** architecture
- **evidence:** `project-context.md`: startup stays bounded. Complete pricing is on-demand. FR-4 restores lots before Earnings and lists paint Held. Non-goal already forbids feed hydration.
- **proposed fix:** Warm restore reads SQLite lots and as-of only. It does not call Yahoo, SEC, or earnings capture.
- **second-order:** “Paint Held first” becomes a blocking hydrate of every lot ticker.

## Predicted P0s (if this PRD ships as written)

1. Kotlin detect-header literals drift from yaml (A-1).
2. Duplicate ticker rows + `LIMIT 1` pick the wrong quantity after restart (A-2).
3. Agent live QA on `sp500` or `pm clear` to prove persist (A-3, A-8).
4. Earnings restore SAF consumes a positions CSV (A-4).

## Lesson candidates

- A Windows-surface yaml is not an Android port until owner, as-of home, and non-goals move in the same file.
- Display pin and persisted list order are two facts. One sentence cannot own both.
- Two SAF document families on one screen need two actions, even when both live on Earnings.

## Doc gaps

| Home today | Gap |
| --- | --- |
| `advisor-csv-import-v1.yaml` | Android owner, platform as-of, ledger refuse on Android |
| `docs/advisor-csv-import.md` | Still Windows-only |
| SPEC-advisor-csv-import | Non-goal “No Android import” |
| `project-context.md` | Windows-only CSV sentence |
| `shared/contracts/README.md` | Windows-only blurb |
| `docs/cross-platform-parity.md` | No Android book / Held exception |
| `persistence-semantics.md` | Tracked order vs Held pin |
| This PRD | Live QA command, unique ticker, two SAF actions, degraded kinds |
| Held / pin | No Gherkin examples yet (spec must add Scenario Outlines, ≥2 Cases) |

## Regression traps

- `positionSizeBps` full/half/exit overwritten by lot quantity.
- Composite / V2 / V3 / V4 badges change to buy the pin.
- Snapshot confirm deletes omitted lots before Confirm.
- Empty keep wipes the phone book.
- Blotter aggregates from zero.
- Same-day Chase rows double PHYL 1273.
- Cost reads Price / Price USD on the J.P. Morgan file.
- `"1,273"` becomes 1.273.
- QACDS or US DOLLAR appear as Held earnings rows.
- Prefix match (`A` holds `AMZN`). Earnings search stays prefix; Held stays exact.
- Feed membership grows to host lots.
- `pm clear` after import.
- Live QA profile `sp500`.
- Pin writes watchlist membership.
- Plans / Discovery gain the pin by accident.

## Anticipatory loop

Pass count: **4**.

1. **Homes.** Yaml, SPEC, operator doc, project-context, and parity still describe Windows import only. Android as-of has no contract sentence. → A-1, A-9.
2. **Ledger.** CSV kind **Do instead** is in FR-1–FR-3. `LIMIT 1`, mute refuse, live QA, `pm clear`, and unreachable Held are not. → A-2, A-3, A-6, A-8.
3. **Surfaces.** Earnings already has SAF restore. Tracked has persisted order. Compose must not own Held. Restore must not hydrate. → A-4, A-5, A-7, A-13.
4. **Second-order.** Fix A-1 without unique ticker and the blotter still lies. Fix pin without paint/persist split and Tracked order dies. Prove SM-2 only on device and the feed grows. → keep A-2 P0; keep A-5/A-8 P1.

## Source flags for a later Reviewer

Do not open these in Advisor. Reviewer checks them in code after spec:

- Windows tests vs yaml examples for close-lot `remove` (A-11).
- Whether Android already has a lots table (PRD claims none).
- Whether Watch in the PRD maps to the watchlist composable.

## Next

Revise `prd.md` for A-1 and A-2 at least. Re-run Sensei+Advisor. Do not start spec on this draft.
