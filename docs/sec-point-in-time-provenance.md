# SEC Point-in-Time Provenance

How the EDGAR extractor answers *where did this number come from, and when could
anyone have known it*. Everything here is implemented in
`apps/windows/src-tauri/src/edgar.rs` and pinned by unit tests in that file's
`mod tests`.

## The three questions

Every annual value the extractor produces must be able to answer:

1. **Which filing does this come from?** — `AnnualProvenance::sources`, a list of
   `SecFact`s, each carrying its qname, form, period end, filing date and
   accession number.
2. **When did it become knowable?** — `AnnualProvenance::known_from`, the latest
   `filed` date among those sources. A value composed from two filings is
   knowable only once its later input was filed.
3. **What would a reader have believed on date D?** — `AnnualSeries::as_of(D)`,
   which resolves the retained vintages using only observations filed strictly
   before `D`.

Question 3 is the one that needs vintages retained. Questions 1 and 2 can be
answered by a value that has already collapsed its history; question 3 cannot.

## Vintages versus `known_from` — why both exist

`known_from` answers *when did we first know what we now believe*.
`as_of` answers *what did we believe on date D*. These are different questions
and neither derives from the other.

A restated year makes the difference concrete. Suppose FY2023 revenue is filed at
12.318B in the 2023 10-K and refiled at 6.255B in the 2025 10-K after a
separation. The current belief is 6.255B with `known_from` 2025-08-14. But a
reader on 2024-06-01 believed 12.318B — a fact `known_from` alone cannot express,
because 12.318B is not the current belief and has no place in a collapsed series.

So `AnnualSeries` holds every admitted `AnnualObservation` unresolved, and
resolution happens on read:

| call | admits | answers |
| --- | --- | --- |
| `AnnualSeries::latest()` | every observation | what is believed now |
| `AnnualSeries::as_of(D)` | observations with `filed < D` | what was believed on `D` |

`latest()` is defined as `as_of` with no upper bound, so there is exactly one
resolution implementation and the point-in-time path cannot drift from the
production path.

## The cutoff is strictly before, not on

`as_of(D)` admits an observation only when `filed < D`.

A filing made on `D` was not knowable at the start of `D`. An inclusive bound
(`filed <= D`) leaks one day of hindsight, and it leaks it at *every* cutoff a
backtest takes — a systematic bias in favour of the strategy, not a rounding
artifact. The boundary is pinned from both sides by
`as_of_admits_only_the_filing_strictly_before_the_cutoff` and
`as_of_excludes_an_observation_filed_exactly_on_the_cutoff`.

## Resolution order

Within one concept, for one period end:

1. consolidated beats segment (a segment fact is a decomposition, not a total);
2. then the later `filed` wins;
3. then the later accession wins.

Then one observation per fiscal year survives: where two period ends fall in the
same calendar year, the later `end` wins.

Across the equivalent concepts of one driver, resolution runs per concept in
declared order and the merged series takes the first concept's value for a year
and fills only the gaps from later ones. A gap-filled year keeps the **filling**
concept's provenance, which is what makes the merge auditable.

Material restatement is computed per concept and unioned, never after the
cross-concept merge: a year restated under a concept that lost the merge is still
a year whose two filings describe different reporting entities.

## `mixed_vintage` — compositions are marked, not forbidden

Total debt is the sum of its current and non-current components. Free cash flow
is operating cash flow minus capital expenditure. Recurring development is
tangible plus capitalized software. Each composes facts that may have arrived in
different filings.

Refusing mixed-vintage compositions would refuse most of the useful evidence, so
they are marked instead. `AnnualProvenance::mixed_vintage` is true whenever the
contributing sources do not share one filing date, and `known_from` is the latest
of them. The flag makes the mixing visible; it never invents agreement.

## Fiscal-year semantics — and its named limitation

**`AnnualValue::year` is the calendar year in which the period *ends*, and is
derived from the filed period end and from nothing else.**

`IsoDate::year()` is the only fiscal-year derivation in the module. There is no
year taken from a string slice of a date and no year taken from a filing date.

### Why `fy` and `fp` are deliberately not retained

Companyfacts entries carry the issuer's own `fy`/`fp` designation, and it does
not mean what a reader expects. NVDA's FY2026 10-K reports both the year ended
2025-01-26 and the year ended 2026-01-25 with `"fy": 2026` — the comparative
carries the *current filing's* label. A series keyed on `fy` collapses two
distinct fiscal years into one and silently drops one of them.

Period end is unambiguous, and the accession number already identifies the filing
exactly, so nothing is lost by not retaining the issuer's label.

### Limitation: fiscal-year-end changes

An issuer that moves its fiscal close can file two annual periods ending in the
same calendar year. The later `end` wins that year and the earlier one is
dropped, so such an issuer loses one observation. This is the status quo, it is
now pinned by `a_fiscal_year_end_change_keeps_the_later_period_end`, and it is a
known limitation rather than a decision: representing both would require the
series to be keyed by period end rather than by year, which changes every
consumer of `AnnualValue`.

## Fail-closed admission

`AnnualObservation::from_fact` refuses a fact — producing **no** annual value —
when any of three fields is missing or unparseable:

| field | why refusing is right |
| --- | --- |
| `end` | the fiscal year is derived from it; an unparseable end has no year, and the issuer's `fy` label is not a substitute |
| `filed` | an absent filing date is not "knowable since the beginning of time"; an empty or defaulted date is a fabricated availability |
| `accn` | the accession decides the precedence tie-break; defaulting it is the same fabricated-identity defect in a third field |

`IsoDate::parse` is strict: four-digit year, two-digit month and day, no trailing
text, and a day the calendar actually has. `2024`, `2024-13-01`, `2024-1-01` and
the empty string are all refused.

Refusing evidence has a cost, and the cost is measured rather than assumed:
`probe_facts_without_a_filing_date` in `valuation_probes.rs` counts, per issuer
and per driver, the accepted 10-K facts that carry no filing date, no parseable
period end, or no accession, and prints every refused fact individually so that
any moved anchor can be attributed to named facts rather than to a count.

### Measured cost (T1.7)

**Measured 2026-08-04**, orchestrator run of
`cargo test --lib probe_facts_without_a_filing_date -- --ignored --nocapture`.
17 issuers: the four anchors, the Wave 2 issuers (including MPWR), and an
oldest-filing-history slice of the high-signal cohort.

| | total |
|---|---|
| accepted 10-K facts | **8504** |
| no `filed` (refused) | **0** |
| unparseable `end` (refused) | **0** |
| no `accn` (refused) | **0** |
| **disagreeing period-ends** | **305** |

Earliest filing dates run from 2010-01-29 (AMZN) to 2018-02-23 (BKR). Per-issuer
refusals are zero in every column for every issuer, so there is no refused-fact
list to record.

**Fail-closed extraction costs this sample nothing.** Columns 1, 2 and 4 are what
the fail-close refuses, and each would be a driver-year an issuer loses. All three
are zero across 8504 facts. This is the measured answer to the concern that
requiring `end`, `filed` and `accn` would silently thin the series: on this sample
it thins nothing. It is a *measurement on 17 issuers*, not a proof for the whole
universe — an issuer with sparser provenance would still lose years, and the probe
exists so that cost is observed rather than assumed.

**Column 3 is the one that justifies retaining vintages at all, and it is not
zero: 305.** Live SEC data really does file the same `(concept, period_end)` more
than once at *different values* — CHTR alone excepted, at 0. So `as_of` and
`latest` can and do disagree on real data, and the point-in-time API is exercised
by evidence rather than merely unused pending item 6. A zero here would have meant
Wave 1 built a mechanism nothing can distinguish from `latest`.

## The boundary: what is point-in-time capable and what is not

**PIT-capable:** `edgar::fetch_company_facts` followed by
`edgar::extract_driver_vintages`, then `AnnualSeries::as_of(D)`.

**Not PIT-capable:** `dcf_model::FcfPoint` and everything downstream of it.
`FcfPoint` carries no provenance, so a value that has passed through it cannot be
read as of a date. This is a deliberate boundary, not an oversight: extending
`FcfPoint` to carry provenance changes the Core's data contract and is separate
work.

`edgar::fetch_fcf_history` therefore returns present-belief values only.

### LD-6: composed drivers have no `as_of`

`AnnualSeries::as_of` is single-concept. A composed driver — total debt, free
cash flow, recurring development — carries provenance and `known_from`, but has
no cutoff-aware resolution, because one `AnnualObservation` holds one fact and a
composition is not representable in it.

The practical consequence: asking "what was this issuer's total debt on
2024-06-01" is not answerable by this module today. Asking "what was its filed
long-term debt on 2024-06-01" is. Composing at a cutoff requires resolving each
component `as_of` the cutoff and then composing the results, which is a change to
the composition sites rather than to `AnnualSeries`.

## Mechanism note: where `extract_recurring_development` gets its sources

`normalize_investments` (in `sec_normalization.rs`) publishes the selected
tangible and software facts per period end, so `extract_recurring_development`
lists the facts that actually contributed to the magnitude rather than falling
back to a ledger scan.

`PaymentsToAcquireProductiveAssets` is defined by us-gaap as the cash outflow for
PP&E, software *and* other intangibles, so the normalizer leaves the software
fact out of the total when the tangible component is that aggregate. This reader
applies the same rule from the same generated `DEVELOPMENT_AGGREGATE` constant,
because a fact that did not contribute to the magnitude is not a source of it and
would give the wrong `known_from` if it were listed.

That leaves a coupling: two files decide contribution from one constant. Having
`normalize_investments` publish its own contributing sources would remove it, and
`sec_normalization.rs` is owned by another wave.
