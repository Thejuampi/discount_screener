---
artifact: advisor-r2
spike: prd-android-chase-portfolio-2026-09-05
artifact_under_review: prd.md (r2)
prior: advisor-r1.md
admission: reconstituted
locked_stance: Book-as-identity. Port advisor-csv-import snapshot+window to Android. Held flag + pin. Scores unchanged. No feed hydration. No positionSizeBps mix. Ledger apply refused on Android.
verdict: approve
anticipatory_passes: 4
date: 2026-09-05
---

# Advisor r2 — Android Chase book PRD

Docs only. No application source. Reconstituted from `advisor-r1.md`.

## Verdict

`approve`

No open P0. A-1 and A-2 are closed on this PRD. Spec may start. Yaml, operator doc, SPEC non-goal, parity, and `project-context.md` stay stale until build writes `/3`.

## Bar check

| Gate | Result |
| --- | --- |
| Correctness Over Delivery Convenience | Pass. No open P0. |
| A-1 contract Android owner in the PRD | Pass. Header requires `/3`, Android `:core` owner, platform as-of, `ledger_apply_unsupported`, SPEC non-goal retract. |
| A-2 unique ticker / no `LIMIT 1` | Pass. FR-4 unique ticker. FR-2 aggregates duplicate rows. |
| CSV kind **Do instead** | Pass. FR-1, FR-2, FR-3. |
| Live QA expectations | Pass. §8. `make android-run-qa`. Never `pm clear`. Never `sp500`. |
| Remaining P1s | Carry into spec. They do not reopen the PRD bar. |

## Docs read this pass

- `advisor-r1.md` (prior package)
- `prd.md` r2
- spike `.memlog.md`
- `AGENTS.md` Advisor + one home per fact + fixed-point
- `_bmad-output/project-context.md` (CSV sentence still Windows-only on disk)
- `docs/operational-anti-patterns.md`
- `shared/contracts/advisor-csv-import-v1.yaml` (still `advisor-csv-import/2` on disk)
- `docs/advisor-csv-import.md` (still Windows-only on disk)
- SPEC-advisor-csv-import non-goal still “No Android import”
- `docs/cross-platform-parity.md` (no Android book row yet)
- `shared/contracts/README.md`

On-disk Windows-only text is expected. The PRD names those files as spike edits. Build writes them. Review does not close while they stay stale.

## Locked stance (still held)

Book-as-identity. Snapshot + window port. Ledger refuse on Android. Held + pin. Scores stay. No feed hydration. Lot quantity does not write `positionSizeBps`.

New r2 locks that follow r1 **Do instead**:

- Unique ticker, no `LIMIT 1` (ledger identity row).
- Import book vs restore log as two Earnings actions.
- Pin is paint. Tracked order and watchlist membership stay.
- `ledger_apply_unsupported` printed.
- Live `qa` only. Off-feed Held is a test when `qa` ∩ book is empty.

## r1 disposition

| ID | r1 | r2 | Status |
| --- | --- | --- | --- |
| A-1 | P0 contract Windows-only | PRD requires yaml `/3`, Android owner, platform as-of, ledger refuse, doc pointers | **closed** |
| A-2 | P0 `LIMIT 1` | FR-4 unique ticker. Public Held read after restart uses it | **closed** |
| A-3 | P1 live QA omitted | §8 + non-goal | **closed** |
| A-4 | P1 two SAF families | UJ-1 / §4.1 two actions | **closed** |
| A-5 | P1 Tracked order | Glossary + FR-6 pin is paint | **closed** |
| A-6 | P1 mute ledger refuse | UJ-3 `ledger_apply_unsupported` | **closed** |
| A-7 | P1 pin owner | Presenter / core helper. Compose does not decide Held | **closed** |
| A-8 | P1 live Held unreachable | SM-2 fixture / unreachable clause | **closed** |
| A-9 | P1 parity unnamed | Header names Windows stay, desktop skip, Held Android paint | **closed** on PRD. Parity file still needs the row at build |
| A-10 | P1 thin degraded | Unknown/unreadable refuse. Confirm dies with process death | **closed**. Residual parse-fail token is A-17 |
| A-11 | P2 Type / close-lot | FR-3 carries both | **closed**. Yaml still needs a close-lot example at `/3` |
| A-12 | P2 Watch name | Assumption: watchlist. Discovery/Plans out | **closed** |
| A-13 | P2 hydrate on restore | FR-4 local SQLite only | **closed** |

## Findings (open or new)

### A-14 — Merge as-of advance is missing from the `/3` edit list

- **id:** A-14
- **severity:** P1
- **status:** open
- **class:** contract-drift
- **evidence:** FR-3: after Confirm of a merge that applied at least one trade, Android as-of becomes `max(prior, max applied trade_date)`. Memlog Sensei replay. Yaml `/2` stores as-of from the snapshot only (`ds_advisor_book_as_of`). PRD header lists `/3` edits as owner, platform as-of home, and `ledger_apply_unsupported`. It does not list this advance rule. AGENTS.md: shared behavior lives in the contract. One home per fact.
- **proposed fix:** Spec adds the advance rule to yaml `/3` with a Case: second confirm of the same blotter does not double qty. Windows peer must follow the same math or the yaml must name an Android-only exception (do not silent-fork).
- **second-order:** Spec follows the header list only. Android advances as-of. Windows does not. Same two files, two books.

### A-15 — Windows functions named as source of law

- **id:** A-15
- **severity:** P1
- **status:** open
- **class:** doc-gap
- **evidence:** FR-3: “Source of law: yaml examples plus Windows `planCsvImport` / `mergeTradesOntoLots`.” Yaml is the canonical contract (SPEC-advisor-csv-import). AGENTS.md: edit the yaml; do not put a second copy in Kotlin. Ledger **One client's sieve copied to the other**.
- **proposed fix:** Spec names yaml `/3` examples as the only parse/merge home. Windows remains a peer implementation. Drop the TypeScript names from the product source-of-law sentence.
- **second-order:** Builder copies localStorage as-of and `window.confirm` paths from Windows instead of SQLite + Compose Dialog.

### A-16 — Quantity “as parsed” has no fixed scale

- **id:** A-16
- **severity:** P1
- **status:** open
- **class:** architecture
- **evidence:** FR-2: fractional share quantities stay as parsed (AMZN 36.29536). AGENTS.md / `project-context.md`: `*_cents` stay integers. Yaml `JPM-THOUSANDS` is integer 1273. No fractional golden.
- **proposed fix:** Spec locks quantity as integer scale (for example millionths) or another non-float encoding. Add a yaml `/3` Case for a fractional J.P. Morgan quantity. Avg cost stays `avg_cost_cents`.
- **second-order:** A `Double` quantity makes PHYL 1273 and AMZN 36.29536 incomparable and breaks merge blends.

### A-17 — Unknown-kind and as-of parse tokens unnamed

- **id:** A-17
- **severity:** P2
- **status:** open
- **class:** product
- **evidence:** FR-1 unknown or unreadable file refuses “with a reason.” Window missing as-of is `missing_book_as_of`. Snapshot as-of parse fail has no code. Ledger **Backend refuse, UI mute dash**.
- **proposed fix:** Spec names `unknown_kind` / `unreadable` / `as_of_unparseable`. Each writes nothing.
- **second-order:** A bad as-of that still upserts lots leaves merge on `missing_book_as_of` forever, or invents a date.

### A-18 — Held / pin still need Gherkin in the spec

- **id:** A-18
- **severity:** P2
- **status:** open
- **class:** verification-gap
- **evidence:** AGENTS.md specification by example: Scenario Outline, ≥2 Cases. PRD 6.1 applies that to yaml examples. §8 names presenter pin tests. No Held/pin outline yet.
- **proposed fix:** Spec adds Outlines: Held exact match vs prefix; pin then date; pin does not change score or printed rank; absent ticker invents no row.
- **second-order:** Presenter tests assert order only. Prefix `A` → `AMZN` stays untested.

## Matched anti-pattern rows

| Ledger row | r2 | Status |
| --- | --- | --- |
| Positions CSV upserted as trades | FR-1–FR-3 | mitigated |
| YAML percent owns the earnings cell | FR-5 / SM-C2 | mitigated |
| Read model `LIMIT 1` | FR-4 unique ticker | mitigated |
| Backend refuse, UI mute dash | `ledger_apply_unsupported` printed | mitigated. Residual A-17 |
| Required native/live gates optional | §8 | mitigated |
| Only automated tests | three layers + later live `qa` | mitigated |
| `pm clear` | named forbidden | mitigated |
| Android QA on `sp500` | named forbidden | mitigated |
| Property no live QA can reach | SM-2 unreachable clause | mitigated |
| One client's sieve copied | A-15 still a spec trap | watch |

## Predicted P0s (if spec ignores A-14 / A-15)

1. As-of advance lives only in Kotlin. Windows second-confirm doubles, or Android diverges (A-14).
2. Detect headers copied from Windows TypeScript, yaml `/3` unused (A-15).
3. Float quantity corrupts merge cost (A-16) — likely P1 in review, P0 if PHYL 1273 drifts.

None of these are open on this PRD. Spec must carry them.

## Lesson candidates

- Closing a contract P0 on the PRD is a named edit list. New merge math that appears later must join that list.
- Two sources of law (yaml + peer implementation) recreate the copy-the-other-client row.

## Doc gaps (build still owes)

| Home on disk | Still true until `/3` lands |
| --- | --- |
| yaml `surface: windows-advisor` | Android owner missing |
| yaml as-of `localStorage` | Android SQLite meta missing |
| SPEC non-goal no Android import | Must retract |
| `docs/advisor-csv-import.md` | Windows-only |
| `project-context.md` CSV sentence | Windows-only |
| `docs/cross-platform-parity.md` | No Android book / Held row |
| yaml examples | No close-lot, no as-of advance, no fractional qty |

These are scheduled by the PRD. They are not a PRD revise.

## Regression traps (unchanged)

- `positionSizeBps` overwritten by lot qty.
- Scores or printed rank buy the pin.
- Confirm writes before the tap.
- Empty keep wipes the book.
- Blotter from zero.
- Same-day or second-confirm doubles PHYL.
- Unit Cost vs Price.
- `"1,273"` as 1.273.
- QACDS on earnings.
- Prefix Held match.
- Feed growth.
- `pm clear` / `sp500` QA.
- Pin writes watchlist or tracked order.
- Plans / Discovery pin.

## Anticipatory loop

Pass count: **4**.

1. **P0 close.** Header `/3` list and FR-4 unique ticker match r1 proposed fixes. Yaml on disk still `/2`. That is build work. A-1/A-2 closed.
2. **r1 P1 sweep.** Live QA, two SAF actions, pin vs persist, ledger code, pin owner, unreachable Held, parity sentence, degraded confirm, Type/close-lot, watchlist name, local restore all sit in r2.
3. **New r2 math.** As-of advance, Windows function names, fractional quantity have thin or split homes. Score P1 for spec. No ledger **Do instead** miss that forces revise.
4. **Second-order.** Approve the PRD. Spec that copies the header `/3` list and skips FR-3 as-of advance forks the book. A-14 stays open as spec constraint.

## Source flags for a later Reviewer

- Android lots table claim (PRD: none today).
- Whether Windows already advances as-of after merge (A-14). Do not treat TypeScript as the yaml.
- Watchlist composable vs PRD “watchlist”.

## Next

Spec from this PRD and the memlog. Absorb A-14, A-15, A-16. Do not reopen A-1/A-2 without new data.
