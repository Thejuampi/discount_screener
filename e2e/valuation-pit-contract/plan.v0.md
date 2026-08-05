I have verified every fact I intend to rely on. Here is the plan.

---

# `plan.v0.md` — Valuation PIT & Contract (E2E session `valuation-pit-contract`)

Branch: `valuation/wave1-integration` · Repo: `G:\dev\repos\discount_screener`
Planner input: `brief.md` (13 binding constraints) + `refine.md` (Q2/Q4–Q8 RESOLVED; Q1 open, default (i))

---

## 1. Summary

### 1.1 Goal

Replace the fabricated return-on-capital path with an honest one, in four independent directions plus a final specification change:

1. make the annual evidence **point-in-time correct** (it currently throws away the `filed` date it already reads);
2. make the **interest-expense equivalence class** hold one statement's concept only;
3. make the growth channel's **arithmetic non-contaminated** (two naked means, one of which sets a published interval width);
4. write the **economic contract, two research charters and the pre-registration** that define what is being measured and how a candidate could ever be promoted;
5. remove **FR-29**'s value-neutral substitution and land an explicit, named unavailable state in its place.

### 1.2 Non-goals

Selecting or promoting any estimator. Building the rolling PIT harness. Wiring `posterior::fuse` to a ROIC channel. The adapter change (NOPAT base + measured ROIC). Rebuilding the growth engine. Touching the legacy engine.

### 1.3 Current-state findings (verified, with evidence)

| # | Finding | Evidence |
| --- | --- | --- |
| F1 | **The new Core is not wired to production.** `valuation_core_adapter::value()` is called from exactly four test assertions (`valuation_core_adapter.rs:1050, :1072, :1089, :1100`) and one `#[ignore]` diagnostic (`valuation_core_measurement.rs:162`). No Tauri command reaches it. | verified by grep |
| F2 | **FR-29 blast radius is six assertions**, all located. `projection.rs:502`, `residual_income.rs:315`, `intrinsic-value.feature:32`, `residual-income.feature:31`, `valuation_core_adapter.rs:1047`, `:1057`. | verified |
| F3 | **`AnnualValue` (`edgar.rs:71-75`) derives only `Debug, Clone`.** No serde. `grep AnnualValue` returns **56 hits in exactly one file** — it never leaves `edgar.rs`. The outbound boundary is `dcf_model::FcfPoint` (`edgar.rs:987`). | verified |
| F4 | The extractor already holds full provenance in `AnnualCandidate` (`edgar.rs:154-161`) and **collapses it to two fields at `edgar.rs:261-265`**. It reads `filed` at `:196` and uses it at `:232`, then discards it. It never reads `accn` at all. | verified |
| F5 | `edgar.rs:495` and `:513-517` **re-derive the year by slicing `end.get(..4)` while holding the real `end`** — the exact defect the brief forbids. `edgar.rs:204-212` slices `end` too, with an `fy` fallback its own comment (`:197-203`) describes as a known trap. | verified |
| F6 | `sec_normalization::SecFact` (`sec_normalization.rs:36`) already carries `qname, taxonomy, value_dollars, start, end, unit, form, accession, filed, consolidated`, and `edgar.rs:570-583` already constructs it. | verified |
| F7 | Both net interest concepts sit at positions **7 and 8** of `interestExpense.qnames`, and `extract_annual_any_with_shape` (`edgar.rs:317-322`) is `by_year.entry(year).or_insert(...)` — later qnames **gap-fill** an accrual series. The two strings appear in exactly **4 files** (contract, generated Rust `:85-86`, generated Kotlin `:96-97`, and nowhere else). | verified |
| F8 | `Refusal::Evidence(AbsenceReason)` (`publication.rs:59`) already exists and is re-exported at `lib.rs:56`. `equity_value` (`projection.rs:373-380`) propagates the firm value's absence reason to publication. **The explicit unavailable state is already representable.** | verified |
| F9 | Cucumber's `the outcome is {word}` (`cucumber.rs:583-591`) compares against exactly `"resolved"` / `"refused"`. There is **no step that asserts an absence reason** in any feature file today. | verified |
| F10 | `robust_mean` (`numerics.rs:179`) returns only the centre. `standardize` refuses `n<3`, non-finite input, and zero middle-spread. `MAX_ABSOLUTE_Z = 3.0` at `:29`. | verified |
| F11 | The averaging call sites in the adapter are exactly: `:280`, `:489`, `:536`, `:745-746`, `:753`, `:758`, `:781`. | verified by grep |
| F12 | `CrossSectionDiagnostics` (`valuation_core_adapter.rs:162-172`) derives `Debug, Clone, Default` — **no serde**. Adding a field is safe. | verified |
| F13 | `docs/` is flat, has no ADR, and `docs/index.md` has a "Maintenance Rules" section requiring it be kept current. | verified |
| F14 | FR-29's prose specification lives at `_bmad-output/planning-artifacts/prds/prd-discount_screener-2026-08-03/prd.md:404-411` and `addendum.md:104-108`. `prd.md` front matter is `status: draft`. | verified |
| F15 | `operating_valuation.rs:223` `let observed = return_on_capital_bps.unwrap_or(cost_of_equity_bps);` inside `terminal_payout_bps` — the legacy FR-29 equivalent, production-live. | verified |

### 1.4 Approach

Five waves. Three run in parallel first (they are file-disjoint), then two (also file-disjoint with each other). **No wave consumes another wave's output.** The only cross-wave coupling is *file locality*, resolved by scheduling, not by dependency — see §6.1.

### 1.5 Key design decisions (all binding; builders do not re-open these)

**D1 — PIT carrier: extend `AnnualValue` in place; reuse `SecFact` unchanged as the fact-identity carrier.**

```rust
pub struct AnnualValue {
    pub year: i32,
    pub value_dollars: i64,
    pub provenance: AnnualProvenance,
}

/// Everything needed to answer: was this knowable at cutoff `t`, from which
/// filing, and under which period interpretation?
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AnnualProvenance {
    /// The period end exactly as filed. `AnnualValue::year` is derived from
    /// this and nothing may re-derive a year by slicing a string again.
    pub end: String,
    /// The date this observation became knowable: the latest `filed` among
    /// `sources`. A derived observation is knowable only once its last input
    /// was published.
    pub known_from: String,
    /// Every fact that contributed, in combination order.
    pub sources: Vec<SecFact>,
}
```

Rationale, in order of the alternatives rejected:
- *Reuse `SecFact` as the annual carrier* — rejected. A `SecFact` is one raw fact; a fiscal-year observation is frequently a **composition** (total debt = current + non-current; FCF = OCF − CapEx; development total = tangible + software). There is no single `filed` for a composition.
- *Invent a third `FiledFact` struct in `edgar.rs`* — rejected on DRY. `SecFact` already has every field, `edgar.rs:548` already imports it and `:570-583` already builds it.
- *Add `fp` and `fy` to `SecFact`* — **rejected deliberately**, and this is a substantive economic call. `fy` is a property of the **filing**, not of the fact's period; `edgar.rs:197-203` already documents that keying by it "discards valid comparative years and creates a broken driver history" (NVDA files FY2025 and FY2026 revenue both carrying `fy=2026`). `fp` is `"FY"` by construction for every fact this extractor admits (`ACCEPTED_FORMS` ∈ {10-K, 10-K/A}, duration 325–380 days, `frame` without `Q`). Retaining `accession` + `form` + `filed` identifies the filing **exactly**, from which `fy`/`fp` are recoverable; retaining `fy` would re-introduce a documented trap. The brief permits this: *"The exact struct may differ, but no layer may discard information required to answer…"* — all three questions are answerable. It also keeps Wave 1 out of `sec_normalization.rs`, preserving wave disjointness.
- **`accession` must now be captured** for annual facts (`entry["accn"]`), which the current annual extractor never reads.
- **Fail-closed:** a fact with no `filed`, or an `end` that will not parse to a year, is **dropped**. It cannot answer "available at cutoff `t`" and an empty string is a fabricated availability date (constraint 5). This removes the `fy` fallback at `edgar.rs:205-209`.
- **Boundary:** `dcf_model::FcfPoint` is **not** extended in this run. It has no PIT consumer (item 6 is out of scope), it is a serde/production type, and changing it would touch published valuations. The PIT-capable public path is `fetch_company_facts` + `extract_driver_annual` → `Vec<AnnualValue>`, which already exists for exactly this reason (`edgar.rs:960-964`). This limitation is **named in the Wave 1 doc**, not left implicit.

**D2 — one primitive, extended once (`numerics.rs`).**

```rust
/// A robust centre together with the width of that centre.
pub struct RobustCentre { /* centre, variance, retained, discarded */ }
impl RobustCentre {
    pub fn centre(&self) -> f64;
    pub fn variance(&self) -> f64;   // sample variance of the RETAINED values / retained.len()
    pub fn retained(&self) -> usize;
    pub fn discarded(&self) -> usize;
}
pub fn robust_centre(sample: &[f64], max_absolute_z: f64) -> Result<RobustCentre, AbsenceReason>;
```
`robust_mean` is **re-expressed as `robust_centre(..).map(|c| c.centre())`** — no second implementation (constraint 6). The variance is the standard error **of the estimator actually used**: computed from the post-exclusion sample, because a scale over the contaminated sample would describe a different estimator than the one that produced the point. `MAX_ABSOLUTE_Z` stays `3.0` and gains a pinning test. Re-exported at `valuation-core/src/lib.rs:53`.

**D3 — the explicit unavailable state is `AbsenceReason::NotReported`; no new variant.**

`intrinsic_value` moves `return_on_capital_bps` into the **existing** `let-else` destructuring alongside `base_cash_flow`, `growth_bps` and `discount_rate_bps` (`projection.rs:206-212`), and the FR-29 comment and `unwrap_or` at `:217-223` are deleted. Same shape at `residual_income.rs:102-111`.

- Why not a new variant: `NotReported` already means "the provider does not carry this field for this issuer" (`evidence.rs:22`), and the three other structurally-required inputs already refuse with exactly it. A fifth spelling of the same fact would be vocabulary growth without meaning (KISS), and `AbsenceReason` is a closed enum that other matches must stay exhaustive over.
- **Surface state:** at the publication boundary this becomes `Refusal::Evidence(AbsenceReason::NotReported)`, `kind() == "evidence"`, `detail() == "not_reported"` — already implemented at `publication.rs:171-178` and reached via `equity_value`'s reason propagation at `projection.rs:373-380`. Nothing new is built; a real, named, already-tested state is *used*.
- **Gherkin rows become:** `intrinsic-value.feature:32` → `| return-absent | 100.00 | 1500 | 300 | 0.20 | ABSENT | 800 | ABSENT | refused |`; `residual-income.feature:31` → `| return-absent | 1000.00 | ABSENT | 300 | 300 | 0.20 | 800 | ABSENT | refused |`. `refused` is the token `cucumber.rs:588` accepts; `ABSENT` is the only legal absence token (`schema.rs:20`). **No new column, no new step, no new outline** — `schema.rs` stays green and `manifest.toml` needs only its `covers` text updated.
- **Why no `reason` column:** no feature file asserts an absence reason today (F9). Adding one would introduce a spec vocabulary across all seven features and require a builder to statically derive the reason for 8 pre-existing refused rows they are not permitted to run cucumber against. The *named* variant is pinned by Rust unit tests instead. This is consistency with the existing spec vocabulary, not a shortcut.
- **Accepted, recorded limitation:** the Core's refusal does not say *which* required input was missing. That is true today for base cash flow, growth and discount rate equally; it is a Core-wide attribution question, not an FR-29 question. Recorded in the ADR as a named limitation.

**D4 — `residual_income.rs:108` is removed with `projection.rs:223`.** Same fabrication, same new Core, unreachable from the adapter today (financial issuers refuse first at `valuation_core_adapter.rs:352-364`), so removal has zero production consequence and pure specification benefit.

**D5 — `operating_valuation.rs:223` stays.** Legacy, production-live, four router rows, and it feeds a known-failing test. **The ADR records it explicitly** so it is never mistaken for an oversight.

**D6 — Q1 defaults to option (i)**, isolated so a flip is mechanical. See §6.4.

**D7 — FR-29 keeps its identifier and is retitled.** Retaining the id `FR-29` with inverted content makes the record read as a *changed contract*, which is what Decision 2 asks for, and keeps every existing cross-reference (`manifest.toml` `frs`, module docs) resolvable.

### 1.6 Public interface / contract changes

| Surface | Change | Compatibility |
| --- | --- | --- |
| `edgar::AnnualValue` | third field `provenance: AnnualProvenance` | Crate-internal only (F3). No serde, no IPC, no persisted format. |
| `edgar::AnnualProvenance` | new public struct | additive |
| `shared/contracts/sec-driver-normalization.json` | `interestExpense.qnames` loses 2 entries; `fingerprint` → `sec-driver-normalization/9` | contract version bump; both targets regenerated |
| `valuation_core::numerics` | `robust_centre`, `RobustCentre` added; `robust_mean` re-implemented in terms of them | additive; `robust_mean`'s behaviour is unchanged |
| `valuation-core` FR-29 | absent return on capital / return on equity now **refuses** instead of valuing at the neutral line | **intentional breaking spec change**, ADR + PRD + replacement tests |
| `valuation_core_adapter::CrossSectionDiagnostics` | `growth_pooled_discarded: usize` added | additive, `Default`-derived (F12) |

### 1.7 Assumptions

1. SEC companyfacts always supplies `filed` for accepted 10-K facts. Verified for all 21 fact fixtures in `edgar.rs`'s test module. Fail-closed if not.
2. Nothing published moves from Waves 3 and 5 (F1). Anchor deltas are still **measured and reported**.
3. `core_driver_data_deep.json` is stale relative to policy `/8` and is **not** re-captured (brief §2), so the Wave 3/5 offline diagnostics reflect pre-`/8` drivers. Stated in every report.
4. Wave 4 can write every standing rule and index entry from this plan alone, because this plan is decision-complete. It does not read Waves 1–3's diffs.

### 1.8 Risks

| # | Risk | Severity | Mitigation |
| --- | --- | --- | --- |
| R1 | **Wave 2 can move a published valuation.** `valuation_high_signal` / `operating_valuation` recompute against **live** SEC, so removing two qnames can change COF/DAL/CHTR/BKR interest → WACC → intrinsic. | High | Wave 2's exit is orchestrator-run: full `cargo test --lib` + the AGENTS merge bar + a named per-issuer interest-coverage delta. If a **published anchor** (PG/GOOGL/AMZN/MSFT) moves >±5% or changes side of a gate → **stop and ask Juan** (pause trigger (b)). Do **not** re-add the qnames to make it pass — that is preserving an unsupported fallback. |
| R2 | Wave 2 accidentally **repairs** one of the three known-failing tests, changing the failing set to two. | Medium | Report it; do not revert. The constraint is "none repaired by this work" as a *check on weakening*; an honest coverage change is reported to Juan, not hidden. |
| R3 | Wave 1's fail-closed drop of unparseable `end` / missing `filed` silently drops production years. | Medium | Two explicit negative tests + a regression test asserting the extracted **value series is byte-identical** on the existing WDC/separation fixtures. |
| R4 | Wave 3's `standardize` refuses a *nearly*-flat growth history (>half the years identical → MAD 0) where the naked mean published. | Medium | Exactly-flat already refuses today (`sample_variance` → 0 → `measured_or_absent` → absent), so only the near-flat case changes. Wave 3 must **count and report** how many of the 28 cohort issuers change state. |
| R5 | Wave 3 changes the pooled growth centre → changes `persistence` (today `0.1709`) → changes the fade rate for every issuer. | Medium | Required diagnostic: report old vs new `growth_persistence` and `fade_per_year`. Nothing published (F1). |
| R6 | Waves 3 and 5 both edit `valuation_core_adapter.rs`. | Certain | Scheduled into different rounds. §6.1. |
| R7 | Waves 4 and 5 both want `docs/index.md`. | Certain | `docs/index.md` is **owned by Wave 4 alone**, which writes all five entries including the ADR's, by the filename fixed in this plan. |

---

## 2. Waves

### 2.0 File ownership matrix (verify disjointness before scheduling)

| File | W1 | W2 | W3 | W4 | W5 |
| --- | :-: | :-: | :-: | :-: | :-: |
| `apps/windows/src-tauri/src/edgar.rs` | ● | | | | |
| `docs/sec-point-in-time-provenance.md` (new) | ● | | | | |
| `shared/contracts/sec-driver-normalization.json` | | ● | | | |
| `shared/contracts/sec-driver-normalization-fixtures.json` | | ● | | | |
| `apps/windows/src-tauri/src/sec_driver_normalization_policy_generated.rs` | | ● | | | |
| `apps/android/core/src/main/kotlin/com/discountscreener/core/engine/SecDriverNormalizationPolicyGenerated.kt` | | ● | | | |
| `apps/windows/src-tauri/src/sec_normalization.rs` | | ● | | | |
| `shared/contracts/README.md` | | ● | | | |
| `apps/windows/src-tauri/valuation-core/src/numerics.rs` | | | ● | | |
| `apps/windows/src-tauri/valuation-core/src/lib.rs` | | | ● | | |
| `apps/windows/src-tauri/src/valuation_core_adapter.rs` | | | **●** | | **●** |
| `docs/valuation-aggregation-audit.md` (new) | | | ● | | |
| `docs/valuation-economic-contract.md` (new) | | | | ● | |
| `docs/roic-research-charter.md` (new) | | | | ● | |
| `docs/roic-preregistration.md` (new) | | | | ● | |
| `docs/growth-research-charter.md` (new) | | | | ● | |
| `docs/index.md` | | | | ● | |
| `AGENTS.md` | | | | ● | |
| `apps/windows/src-tauri/valuation-core/src/projection.rs` | | | | | ● |
| `apps/windows/src-tauri/valuation-core/src/residual_income.rs` | | | | | ● |
| `.../valuation-core/tests/features/intrinsic-value.feature` | | | | | ● |
| `.../valuation-core/tests/features/residual-income.feature` | | | | | ● |
| `.../valuation-core/tests/features/manifest.toml` | | | | | ● |
| `apps/windows/src-tauri/src/valuation_core_measurement.rs` | | | | | ● |
| `docs/adr-0001-fr-29-removal-and-explicit-unavailable-state.md` (new) | | | | | ● |
| `_bmad-output/.../prd-discount_screener-2026-08-03/prd.md` | | | | | ● |
| `_bmad-output/.../prd-discount_screener-2026-08-03/addendum.md` | | | | | ● |

**The single collision is `valuation_core_adapter.rs` between W3 and W5.** Everything else is disjoint.

**Files no wave may touch:** `src/operating_valuation.rs`, `src/valuation_baseline.rs`, `src/valuation_high_signal.rs`, `src/dcf_model.rs`, `tests/fixtures/valuation/*` (the high-signal observation fixture stays unstaged), `_bmad-output/**/.memlog.md`, `_bmad-output/project-context.md`.

**Recommended schedule (2 rounds, no builder ever shares a file):**

| Round | Waves | Width |
| --- | --- | --- |
| 1 | W1, W2, W3 | 3 |
| 2 | W4, W5 | 2 |

---

### Wave 1 — Point-in-time provenance through every driver path

| Field | Content |
| --- | --- |
| **Wave id** | `wave-1` |
| **Title** | The annual observation stops discarding what it already knows |
| **Scope** | `apps/windows/src-tauri/src/edgar.rs` (only), plus its new doc |
| **Dependencies** | None. Repo facts relied on: `SecFact` at `sec_normalization.rs:36` is read-only for this wave; `AnnualValue` never leaves `edgar.rs` (F3) |
| **Files touched** | `apps/windows/src-tauri/src/edgar.rs`; `docs/sec-point-in-time-provenance.md` (new) |

#### Tasks

**T1.1 — `AnnualProvenance` exists and cannot be built without a filing date.**
Add `AnnualProvenance` and `SecFact`-based `sources` exactly as specified in D1. Two private constructors, one for a single fact and one for a composition; both return `Option` and yield `None` when any source lacks `filed`. `known_from` is the **maximum** `filed` across `sources`.
*Acceptance:* `AnnualProvenance` cannot be constructed with an empty `known_from`; the invariant `known_from == max(sources.filed)` holds by construction and is asserted by a test.

**T1.2 — One fiscal-year parser, used everywhere; three string-slicing sites removed.**
Add a single `fn fiscal_year_of(end: &str) -> Option<i32>`. Replace the inline slices at `edgar.rs:204`, `:495` and `:516` with calls to it, taking `end` from the provenance the caller already holds. Delete the `fy` fallback at `:205-209`.
*Acceptance:* `grep -n 'get(\.\.4)' src/edgar.rs` returns **zero** hits. `entry["fy"]` is no longer read in `annual_candidates_with_shape`.

**T1.3 — Every production construction site carries real provenance.**
All nine production sites (`:262, :325, :381, :437, :472, :497, :532, :649, :1180`) construct `AnnualValue` with provenance:
- `:262` (leaf) — from the winning `AnnualCandidate`, now also carrying `accession` from `entry["accn"]` and `taxonomy`/`unit` from the extraction parameters.
- `:325` (`extract_annual_any_with_shape`) — the winning qname's provenance; gap-filled years keep the filling qname's provenance, which is what makes the merge auditable.
- `:381` (`extract_total_debt`) — composition of the current + non-current sources, or the reported-total source when it overrides.
- `:437` (`extract_annual_percent_any`) — leaf, `unit` is `"pure"`.
- `:472` (`merge_capex_by_year`) — the winning (largest-absolute) series' provenance.
- `:497` (`extract_recurring_development`) — from `evidence.development_total_by_end`; year via `fiscal_year_of(end)`. Where the underlying ledger entries are reachable, `sources` lists them; otherwise the wave extends `NormalizedInvestmentEvidence`'s map only if that can be done **without editing `sec_normalization.rs`** — if it cannot, `sources` for this site is empty and `known_from` is taken from the ledger entries in `evidence.ledger` matching that `end`, and the limitation is written into the doc. *(This is the one site where the builder chooses between two mechanisms; both are acceptable and the choice is recorded in the doc. It is a mechanism choice, not a product decision.)*
- `:532` (`extract_acquisition_investments`) — directly from `entry.fact` (a `SecFact`); the year via `fiscal_year_of(&entry.fact.end)`.
- `:649` / `fcf_history` — composition of the OCF and CapEx sources for that year; `known_from` is the later of the two.
- `:1180` (`fetch_dcf`) — reconstructed from `FcfPoint`, which carries no provenance; this site therefore **cannot** build a valid `AnnualProvenance`. Change `compute_dcf` to take the year/value pairs it actually uses rather than `AnnualValue`, so no fabricated provenance is created. *(Legacy fixed-10% DCF path; the alternative — a synthetic provenance — is a fabricated availability date.)*

*Acceptance:* the crate compiles; no `AnnualProvenance` anywhere is built from a literal or a default.

**T1.4 — Test construction sites use one helper.**
Add a single `#[cfg(test)] fn annual(year: i32, value_dollars: i64, filed: &str) -> AnnualValue` and route all 22 test sites through it. No copy-pasted provenance literals (DRY).
*Acceptance:* `mod tests` contains exactly one place that constructs an `AnnualProvenance`.

**T1.5 — Documentation.** Write `docs/sec-point-in-time-provenance.md`: the three questions PIT must answer; the `known_from` semantics for compositions; **why `fy` and `fp` are deliberately not retained** (the `edgar.rs:197-203` NVDA trap, and that `accession` identifies the filing exactly); the fail-closed rules; and the named boundary — `dcf_model::FcfPoint` remains provenance-free, the PIT-capable API is `fetch_company_facts` + `extract_driver_annual`, and extending `FcfPoint` is item 6's work.

#### Invariants

- I1: For every `AnnualValue`, `provenance.known_from == provenance.sources.iter().filter_map(filed).max()`, and it is non-empty.
- I2: `AnnualValue::year == fiscal_year_of(&provenance.end)`.
- I3: No code path derives a year from anything but a period `end`.
- I4: A fact without a `filed` date, or with an `end` that will not parse, produces **no** `AnnualValue`.
- I5: The **numeric** series produced for every existing test fixture is unchanged.

#### BDD scenarios

| id | type | role / actor | given | when | then | notes |
| --- | --- | --- | --- | --- | --- | --- |
| W1-P01 | positive | extractor | a 10-K fact with `end` 2024-12-31, `filed` 2025-02-14, `accn` 0000320193-25-000008 | `extract_driver_annual` runs | the annual value's `known_from` is 2025-02-14 and its single source names that accession | leaf provenance |
| W1-P02 | positive | extractor | OCF filed 2025-02-14 and CapEx filed 2025-05-01 for the same fiscal year | `fcf_history` runs | the FCF observation's `known_from` is 2025-05-01 | composition |
| W1-P03 | positive | extractor | current debt and non-current debt filed under two concepts for one fiscal year | `extract_total_debt` runs | the observation names **both** source facts | audit trail |
| W1-P04 | positive | extractor | a development total keyed by `end` 2023-09-30 | `extract_recurring_development` runs | the fiscal year is 2023 and the observation carries `end` 2023-09-30 | the `:495` fix |
| W1-P05 | positive | extractor | a rejected-acquisition ledger entry | `extract_acquisition_investments` runs | the observation carries the ledger fact's qname and accession | the `:516` fix |
| W1-N01 | negative | extractor | a 10-K fact with no `filed` field | extraction runs | that fact produces no annual value | fail-closed; absence ≠ empty date |
| W1-N02 | negative | extractor | a fact whose `end` cannot be parsed to a year, but which carries `fy` | extraction runs | that fact produces no annual value | the `fy` fallback is gone |
| W1-E01 | edge | extractor | two facts for the same `end`, one filed 2024-02-01 and one filed 2025-02-01 with a different value | extraction runs | the retained observation's `known_from` is 2025-02-01 | *the refine acceptance criterion*: two facts sharing a year are distinguished by `filed` |
| W1-E02 | edge | extractor | a CapEx hole interpolated between neighbours filed 2023-03-01 and 2025-03-01 | `fcf_history` runs | the interpolated year's `known_from` is 2025-03-01 | an imputed value is knowable no earlier than its last input |
| W1-E03 | edge | extractor | an issuer with no facts at all for a driver | extraction runs | an empty series, not a zero-valued observation | constraint 5 |
| W1-R01 | regression | extractor | the committed WDC separation fixture (`edgar.rs:1196`) | extraction runs | the value series is identical to before this wave | I5 |
| W1-R02 | regression | extractor | a re-filed consolidated fact superseding a segment fact | extraction runs | consolidated still wins over segment, then latest-filed wins | `edgar.rs:229-233` semantics unchanged |

*One assert per test; where two properties are checked, group with `SoftAssertions`-equivalent (a single `assert!` over a collected `Vec` of violations, the pattern already used at `projection.rs:520`).*

#### Automation & evidence

| Level | Command | Class |
| --- | --- | --- |
| unit | `cargo test --lib edgar::` *(from `apps/windows/src-tauri`)* | **FAST — builder** |
| lint | `cargo fmt -- --check` | **FAST — builder** |
| integration | `cargo test --lib` — expect exactly the three pre-existing failures | **DEFERRED — orchestrator** |
| integration | `cargo test --lib dcf_model::` and `cargo test --lib valuation_baseline::` (AGENTS merge bar) | **DEFERRED — orchestrator** |

**Evidence of pass:** the twelve test names above, green, pasted; `grep -c 'get(\.\.4)' src/edgar.rs` = 0; `git diff --name-only` shows exactly the two files in the ownership matrix.

**Failing-set guarantee:** Wave 1 touches none of `operating_valuation.rs`, `valuation_baseline.rs`, `valuation_high_signal.rs`, so it cannot repair or add to the three. Confirmed by `git diff --name-only`; verified by the orchestrator's full run.

**Anchor deltas:** Wave 1 is value-preserving by construction (I5). Expected delta for PG/GOOGL/AMZN/MSFT: **exactly zero**. Any non-zero delta in the orchestrator's merge-bar run is a defect in this wave, not an acceptable change.

**Done when:** I1–I5 hold and are tested; all 31 construction sites carry real provenance or no longer exist; zero year-from-string-slice sites remain; `docs/sec-point-in-time-provenance.md` is committed.

---

### Wave 2 — The interest-expense equivalence class holds one statement's concept only

| Field | Content |
| --- | --- |
| **Wave id** | `wave-2` |
| **Title** | A net concept is not an equivalent of a gross expense |
| **Scope** | The generated policy pipeline, both platforms |
| **Dependencies** | None. Repo facts: fingerprint appears in exactly 5 places (verified); the generator supports `-Check` and `-OutputRoot` |
| **Files touched** | `shared/contracts/sec-driver-normalization.json`; `shared/contracts/sec-driver-normalization-fixtures.json`; `apps/windows/src-tauri/src/sec_driver_normalization_policy_generated.rs`; `apps/android/core/src/main/kotlin/com/discountscreener/core/engine/SecDriverNormalizationPolicyGenerated.kt`; `apps/windows/src-tauri/src/sec_normalization.rs`; `shared/contracts/README.md` |

#### Tasks

**T2.1 — Remove both net concepts from the equivalence set.**
In `shared/contracts/sec-driver-normalization.json`, delete `"InterestIncomeExpenseNet"` and `"InterestIncomeExpenseNonoperatingNet"` from `drivers.interestExpense.qnames`. Resulting list, order preserved:
`["InterestExpenseNonOperating", "InterestExpenseNonoperating", "InterestExpenseDebt", "InterestAndDebtExpense", "InterestExpense", "InterestExpenseOtherLongTermDebt", "FinanceLeaseInterestExpense"]`.
Extend the existing `rationale` string (which already documents the `InterestPaidNet` precedent) with the same definitional argument for the net concepts: a *net* concept nets interest **income** against interest **expense**; for a cash-rich issuer filing net interest income, `pretax + interest` double-adds income that pretax already contains. It is measured as an expense for COF (19 yrs), DAL (15), CHTR (12), BKR (11). It is not an equivalent, and `select_one_equivalent`'s gap-filling was splicing a netted quantity into a gross accrual series year by year.
Bump `fingerprint` to `"sec-driver-normalization/9"`.
*Acceptance:* zero ticker literals appear anywhere in the diff (constraint 1). The JSON parses.

**T2.2 — Regenerate both targets non-destructively, then commit.**
```
pwsh scripts/generate-sec-driver-normalization-policy.ps1 -OutputRoot <scratchpad>
```
diff the scratch output against the committed files, confirm the only changes are the two qnames and the fingerprint, then regenerate in place.
*Acceptance:* `pwsh scripts/generate-sec-driver-normalization-policy.ps1 -Check` exits 0; the generated Rust and Kotlin are byte-identical to a fresh generation.

**T2.3 — Move all three fingerprint assertions.**
`apps/windows/src-tauri/src/sec_normalization.rs:344` and the `policyFingerprint` field in `shared/contracts/sec-driver-normalization-fixtures.json:3` → `sec-driver-normalization/9`. (`sec_normalization.rs:403` asserts equality against `POLICY_FINGERPRINT`, so it follows automatically — confirm, do not duplicate.)
*Acceptance:* `cargo test --lib sec_normalization::` green.

**T2.4 — Pin the equivalence set by name.**
Add one test in `sec_normalization.rs` asserting the exact `INTEREST_EXPENSE.qnames` slice. A single `assert_eq!` against the seven-element literal, so re-adding either net concept — or re-adding `InterestPaidNet` — turns it red with a diff a reader can understand.
*Acceptance:* mutating any one element of the list fails the test.

*Note for the builder:* the end-to-end extraction behaviour ("an issuer filing only a net concept reads absent") needs **no** new test in `edgar.rs`, because `extract_annual_any_with_shape` (`edgar.rs:317-322`) iterates exactly `driver.qnames` and nothing else — a structural guarantee, not a behaviour to re-verify. `edgar.rs` is owned by Wave 1 and must not be touched.

**T2.5 — Documentation.** Add a section to `shared/contracts/README.md`: **"Equivalence classes hold one statement's concept only."** State the rule (an equivalence class may contain only alternative tags for the *same* line of the *same* statement); the two precedents (`InterestPaidNet` = cash-flow supplemental vs accrual; the two `…IncomeExpense…Net` = netted vs gross); the consequence (an issuer filing none reads **absent**, per Decision 1 that abstention beats an unsupported estimate); and that declared qname order is load-bearing because later entries gap-fill (`edgar.rs:317-322`).

#### Invariants

- I1: The contract fingerprint, both generated targets, the fixtures file and the Rust assertion agree on `sec-driver-normalization/9`.
- I2: The generated files are a pure function of the contract (`-Check` green).
- I3: No branch anywhere in the diff mentions a ticker.
- I4: No qname was *added*, and `InterestPaidNet` remains absent.

#### BDD scenarios

| id | type | role / actor | given | when | then | notes |
| --- | --- | --- | --- | --- | --- | --- |
| W2-P01 | positive | policy reader | the regenerated Rust policy | `INTEREST_EXPENSE.qnames` is read | it equals the seven gross-expense concepts, in declared order | T2.4 |
| W2-P02 | positive | policy reader | the committed policy | `POLICY_FINGERPRINT` is read | it is `sec-driver-normalization/9` | T2.3 |
| W2-P03 | positive | CI | the committed contract and generated files | `validate-contracts.ps1` runs | it exits 0 | dual-lock |
| W2-N01 | negative | policy reader | the equivalence set | `InterestIncomeExpenseNet` is looked up | it is not a member | the fix itself |
| W2-N02 | negative | policy reader | the equivalence set | `InterestPaidNet` is looked up | it is still not a member | prior fix not regressed |
| W2-E01 | edge | extractor (by construction) | an issuer that filed *only* a net interest concept across its whole history | the interest driver resolves | an empty series — absent, not a substituted net figure | Decision 1: abstention over unsupported estimate. Guaranteed structurally by `edgar.rs:317-322` |
| W2-E02 | edge | issuer cohort | COF / DAL / CHTR / BKR | the ROIC coverage probe runs | their interest-expense years drop, and the loss is **reported, not repaired** | coverage is diagnostic only |
| W2-R01 | regression | Android | the regenerated Kotlin policy | `:core:test` runs | green, and its qname list matches Rust's | cross-platform parity |
| W2-R02 | regression | Windows | the whole Shell suite | `cargo test --lib` runs | the failing set is still exactly the three named tests | R1/R2 |

#### Automation & evidence

| Level | Command | Class |
| --- | --- | --- |
| unit | `cargo test --lib sec_normalization::` | **FAST — builder** |
| generation | `pwsh scripts/generate-sec-driver-normalization-policy.ps1 -OutputRoot "G:\dev\caches\tmp\claude\G--dev-repos-discount-screener\a5b7ed32-8a2d-4c54-ba78-5568775587f2\scratchpad\policy"` then diff | **FAST — builder** |
| contracts | `pwsh scripts/validate-contracts.ps1` | **DEFERRED — orchestrator** |
| android | `pwsh scripts/validate-android.ps1` | **DEFERRED — orchestrator** |
| integration | `cargo test --lib` (full) | **DEFERRED — orchestrator** |
| merge bar | `cargo test --lib dcf_model::` + `cargo test --lib valuation_baseline::` | **DEFERRED — orchestrator** |
| diagnostic | `cargo test --lib probe_return_on_capital_availability -- --ignored --nocapture` (network) | **DEFERRED — orchestrator** |

**Evidence of pass:** the exact qname assertion green; the `-OutputRoot` diff showing only the intended lines; the orchestrator's `-Check` and Android outputs; the ROIC-coverage table before and after, per issuer.

**Anchor deltas (mandatory for this wave):** report PG, GOOGL, AMZN, MSFT interest-expense year counts and resulting intrinsic values before and after. **If any anchor moves more than ±5% or changes side of a gate, STOP and ask Juan** (pause trigger (b)). Do not resolve it by re-adding a qname.

**Failing-set guarantee:** the wave touches none of the three failing tests' files, but it *can* change their live inputs (R1/R2). The orchestrator's full run is the check; any change in the failing set is **reported**, never patched.

**Done when:** I1–I4 hold; `-Check` and Android are green; the coverage-loss table and the anchor-delta table are recorded; `shared/contracts/README.md` carries the equivalence-class rule.

---

### Wave 3 — One aggregation primitive, and two call sites that stop contaminating it

| Field | Content |
| --- | --- |
| **Wave id** | `wave-3` |
| **Title** | The growth channel's centre and its precision come from the same retained sample |
| **Scope** | `valuation-core/src/numerics.rs` + the two adapter call sites + a written audit |
| **Dependencies** | None. Repo facts: `robust_mean`/`standardize` exist at `numerics.rs:136,179`; the adapter's averaging sites are exactly the seven at F11 |
| **Files touched** | `apps/windows/src-tauri/valuation-core/src/numerics.rs`; `apps/windows/src-tauri/valuation-core/src/lib.rs`; `apps/windows/src-tauri/src/valuation_core_adapter.rs`; `docs/valuation-aggregation-audit.md` (new) |

#### Tasks

**T3.1 — Extend the one primitive (D2).**
Add `RobustCentre` and `robust_centre` to `numerics.rs`. `variance()` is the sample variance of the **retained** observations divided by the retained count — the standard error of the estimator that was actually used. Re-express `robust_mean` as a one-line delegation. Re-export both at `valuation-core/src/lib.rs:53`.
*Acceptance:* `numerics.rs` contains exactly **one** implementation of trimming and one of the centre; `grep -c 'sum::<f64>()' valuation-core/src/numerics.rs` shows only the retained-mean and the retained-variance.

**T3.2 — Pin `MAX_ABSOLUTE_Z`.**
Add a test asserting `MAX_ABSOLUTE_Z == 3.0`, with a doc comment stating that lowering it is treated exactly like relaxing a test threshold.

**T3.3 — Replace `valuation_core_adapter.rs:280`.**
`mean(&flattened)` → `robust_centre(&pooled, MAX_ABSOLUTE_Z).ok()?`, and record `discarded()` in a new `CrossSectionDiagnostics::growth_pooled_discarded: usize` field (F12: safe, `Default`-derived). Import `robust_centre`, `RobustCentre` and `MAX_ABSOLUTE_Z` — **never fully-qualified** (constraint 10).
*Acceptance:* `fit_growth_path` no longer calls `mean`; the discarded count is visible in diagnostics.

**T3.4 — Replace `valuation_core_adapter.rs:536`.**
```rust
let Ok(centre) = robust_centre(&growth, MAX_ABSOLUTE_Z) else {
    return Observation::absent(AbsenceReason::InsufficientObservations, provenance);
};
let trailing = measured_or_absent(
    centre.centre(),
    centre.variance(),
    UncertaintyBasis::SampleVariance { observations: centre.retained() as u32 },
    provenance,
);
```
Note the deliberate change: the reported `observations` is now the **retained** count, not the raw count, so the basis describes the estimator that produced the number. The `variance / growth.len()` division at `:541` moves inside `RobustCentre::variance()` and is deleted here.
*Acceptance:* neither `mean` nor `sample_variance` is called from `growth_posterior`.

**T3.5 — Audit every remaining averaging site and write the verdicts.**
The verdicts are **decided here**; the builder implements them, it does not re-decide:

| Site | Verdict | Reason |
| --- | --- | --- |
| `:280` pooled growth mean | **Fix** (T3.3) | a centre of a measured cross-sectional series |
| `:536` trailing growth mean + variance | **Fix** (T3.4) | supplies both the point estimate and the precision that sets the published interval width |
| `:489` residual scatter on `n-2` df | **Keep** | this is a regression's residual variance, not a centre; robustifying it would change the meaning of the standard error attached to a least-squares fit |
| `:745-746` `mean` (the helper) | **Keep** | it survives only for `:781` |
| `:753`, `:758` `sample_variance` | **Keep** | it survives only for `:335` |
| `:781` `least_squares` centering | **Keep** | OLS is *defined* by centering on the arithmetic mean; substituting a robust centre yields an estimator that is neither OLS nor a documented robust regression |
| `:335` `fit_beta_dispersion` | **Keep** | it is a *dispersion used as a width*, not a centre, on a bounded quantity (beta). Contamination widens it, and erring wide is the safe direction for an unknown precision (the function's own doc already argues this) |
| `numerics.rs:190` retained mean inside `robust_mean` | **Keep** | that *is* the trimmed-mean estimator |

Each "Keep" gains a one-sentence rustdoc note at its site saying why it is not the forbidden pattern, so the next reader does not re-litigate it.

**T3.6 — Measure and report the numeric consequences.**
Run the offline diagnostic and record, before and after: `growth_persistence` (today `0.1709`), `fade_per_year`, `growth_pooled_discarded`, and the per-issuer Core median for **PG, GOOGL, AMZN, MSFT**. Also count how many of the 28 cohort issuers change between measured and absent for the trailing growth channel (R4).

**T3.7 — Documentation.** Write `docs/valuation-aggregation-audit.md`: the T3.5 table with its reasoning, the `robust_centre` contract (why the precision must come from the retained sample — a MAD scale over the contaminated sample would describe a different estimator than the one that produced the point), the `MAX_ABSOLUTE_Z` non-negotiability, and the measured before/after numbers from T3.6.

#### Invariants

- I1: There is exactly one trimming implementation in the workspace.
- I2: `robust_mean(s, z) == robust_centre(s, z).centre()` for every sample where either resolves.
- I3: `MAX_ABSOLUTE_Z == 3.0`.
- I4: Trimming refuses rather than falling back to the untrimmed mean (existing `numerics.rs:187-189` behaviour preserved).
- I5: No `sum / n` over a measured series remains in `valuation_core_adapter.rs` except the sites the T3.5 table marks **Keep**, each with its note.

#### BDD scenarios

| id | type | role / actor | given | when | then | notes |
| --- | --- | --- | --- | --- | --- | --- |
| W3-P01 | positive | numerics | the `CONTAMINATED` sample (`numerics.rs:297`) | `robust_centre` runs | the centre is `94/9`, matching `robust_mean` | I2 |
| W3-P02 | positive | numerics | the same sample | `robust_centre` runs | it reports 9 retained and 1 discarded | AGENTS: report how many were discarded |
| W3-P03 | positive | numerics | the same sample | `robust_centre` runs | `variance()` equals the sample variance of the nine clean values divided by nine | the precision describes the estimator used |
| W3-P04 | positive | adapter | a cohort with one absurd revenue-growth year | `fit_cross_section` runs | `growth_pooled_discarded` is at least 1 | T3.3 visible |
| W3-P05 | positive | adapter | an issuer with one contaminated growth year | `growth_posterior` resolves | the uncertainty's `observations` is the retained count, not the raw count | T3.4 |
| W3-N01 | negative | numerics | a sample that trims below three retained observations | `robust_centre` runs | `Err(InsufficientObservations)` — never the untrimmed mean | I4 |
| W3-N02 | negative | numerics | a sample containing `NaN` | `robust_centre` runs | `Err(NotReported)` | poison does not propagate |
| W3-N03 | negative | adapter | an issuer whose growth history has no middle spread | `growth_posterior` resolves | `Observation::Absent`, not a number | matches today's behaviour for exactly-flat; report the near-flat delta (R4) |
| W3-E01 | edge | numerics | `CONTAMINATED` | both variances are computed | the retained variance is orders of magnitude below the full-sample variance | the `:536` defect, as a number |
| W3-E02 | edge | numerics | a two-observation sample | `robust_centre` runs | `Err(InsufficientObservations)` | a centre and a spread are two quantities |
| W3-R01 | regression | numerics | every existing `numerics.rs` test | `cargo test -p valuation-core --lib numerics::` | all green, none edited | `robust_mean` behaviour unchanged |
| W3-R02 | regression | numerics | the constant | it is read | it is `3.0` | I3 / T3.2 |
| W3-R03 | regression | adapter | the pinned cohort | `fit_cross_section` runs | a growth path is still fitted (`the_growth_path_is_fitted_across_the_cohort_not_per_issuer` stays green) | the fix must not destroy the fit |

#### Automation & evidence

| Level | Command | Class |
| --- | --- | --- |
| unit | `cargo test -p valuation-core --lib numerics::` | **FAST — builder (<10s, dependency-free crate)** |
| unit | `cargo test -p valuation-core --lib` | **FAST — builder** |
| unit | `cargo test --lib valuation_core_adapter::` | **FAST — builder (module-scoped; Shell link dominates)** |
| diagnostic | `cargo test --lib core_versus_current_engine_on_the_pinned_cohort -- --ignored --nocapture` (offline, fixture-driven) | **FAST — builder** |
| lint | `cargo fmt -- --check` | **FAST — builder** |
| integration | `cargo test -p valuation-core` (includes cucumber) | **DEFERRED — orchestrator** |
| integration | `cargo test --lib` (full) | **DEFERRED — orchestrator** |

**Evidence of pass:** the thirteen test names green; the T3.6 before/after table; `docs/valuation-aggregation-audit.md` committed.

**Anchor deltas:** required and reported from T3.6. Expected: the Core column moves for PG/GOOGL/AMZN/MSFT (the fade rate changes), and **nothing published moves** because the Core has no production caller (F1). If the diagnostic shows a *published* anchor moving, the wave **stops and asks** — on current evidence that path does not exist.

**Failing-set guarantee:** Wave 3 touches none of the three failing tests' files, and the adapter has no production caller. `git diff --name-only` confirms.

**Done when:** I1–I5 hold and are tested; both naked means are gone; the audit doc is committed with real measured numbers.

---

### Wave 4 — The economic contract, the charters, the pre-registration, and the standing rules

| Field | Content |
| --- | --- |
| **Wave id** | `wave-4` |
| **Title** | Write the contract that everything else is judged against |
| **Scope** | `docs/` and `AGENTS.md`. **No source file.** |
| **Dependencies** | None. Every decision this wave records is fixed in §1.5, §2/W1–W3 and §6.4 of this plan; the builder writes from the plan, not from other waves' diffs |
| **Files touched** | `docs/valuation-economic-contract.md` (new); `docs/roic-research-charter.md` (new); `docs/roic-preregistration.md` (new); `docs/growth-research-charter.md` (new); `docs/index.md`; `AGENTS.md` |

> **This wave is deliberately one wave, not four.** The brief's work order says the economic contract **gates** the pre-registration. That is a genuine ordering constraint: the pre-registration's materiality threshold is *derived* from how return-on-capital estimation error propagates through `ReinvestmentRate = g/r` into `C(t) = E(t)(1 − g/r)`, which the contract defines. Splitting them into two waves would either fake independence or leave the threshold ungrounded. One builder writes all four documents **in the order T4.1 → T4.2/T4.3 → T4.4**, sequentially, inside this wave. That is honest ordering *within* a wave, not a cross-wave dependency.

#### Tasks

**T4.1 — `docs/valuation-economic-contract.md` (gating artifact; write first).**
Formally define, each with units and each with its valid absence states: NOPAT; invested capital; reinvestment; organic investment; acquisitions; divestitures; capital-consumption treatment; `g`; `r`; expected timing between investment and return; the relationship between growth, return and reinvestment.
Anchor identities, stated and derived, not asserted: `FCFF = NOPAT − Reinvestment`; `ReinvestmentRate = g_NOPAT / r`; `FCFF = NOPAT × (1 − g_NOPAT/r)`; the Core's retention charge `C(t) = E(t)(1 − g(t)/r)`, `V = ∫C(t)e^{−wt}dt` (FR-28). Include the established sequencing fact: **NOPAT base alone → reinvestment charged zero times → overvalues; ROIC alone with an FCFF base → charged twice; both together → charged exactly once.**
**Financial issuers:** document the deferral **that already exists** — `valuation_core_adapter.rs:352-364` classifies a `BusinessClass::FinancialServices` issuer correctly and then returns `Observation::absent(ProviderUnavailable, "book_value")`, pinned by `a_bank_refuses_on_evidence_rather_than_being_valued_on_cash_flow`. Name the absence state. **Do not invent a bank invested-capital formula** — that would be widening the contract to keep publishing, the mirror image of Juan's closing instruction.
Record the two known upstream defects this contract must eventually resolve but which this run does not touch: `resolve_capex_abs` returns `0` CapEx when no series exists (`edgar.rs:604-607`), and `dcf_model::FcfPoint` carries no provenance.
*Acceptance:* every term the brief enumerates has a definition, a unit, and an absence state.

**T4.2 — `docs/roic-research-charter.md`.**
What must be established before any return-on-capital estimator can be promoted. Must contain, as named findings:
- **The OLS levels-slope estimator is permanently deleted** — spurious regression (Granger–Newbold) on trending, autocorrelated level series over 10–19 observations; its conventional standard error is unreliable. Negative for FIS, DAL, WDC, OMC, PG. **Deleted including as a refusal signal and as a derived quality flag.**
- **The `fuse` audit, as a written finding, nothing wired.** Quote `posterior.rs:26-33` in the authors' own words rather than paraphrasing: the minimum-variance property holds only for *unbiased* channels, and low variance can signal herding/correlated bias that the estimator would then weight *up*. State that reusing `fuse` for the ROIC channel is out of scope until this is settled.
- **Finding Q1-(ii): a value-conditional (sign-based) admission rule for the net interest concepts.** Not currently expressible — the contract has no `sign` field, `rejection` is a static category→state lookup, `precedence` ranks but never rejects, and the nearest precedent (`suppressSoftwareWhenTangibleQnameIn`) is a **qname-membership** predicate, not a **value** predicate. The generator emits exactly four driver fields (`RustOperator`, generator `:89-98`) and silently drops extras — which is already observable in that `rationale` never reaches the generated Rust. Implementing (ii) would need new contract vocabulary, a `DriverOperator` change, and generator changes on both platforms, and would be the driver language's first value-conditional rule. **Name the probe that would decide it:** compare net against gross for the issuers that file both.
- Measured facts to carry forward: ROIC coverage 25/28 issuers with ≥3 complete years; MPWR 0 (no debt tag ever filed); EPAM 0 after the `InterestPaidNet` removal; median NOPAT/FCFF ≈ 0.85×; `b = (NOPAT−FCFF)/NOPAT` negative for 13 of 25.
- **Coverage is reported, never a gate, and never a veto** (Decision 1).

**T4.3 — `docs/growth-research-charter.md`.**
Both candidate directions documented, **neither approved** (Decision 3): (1) estimate and validate NOPAT-growth persistence directly; (2) project revenue and margins separately and derive NOPAT growth through an explicit margin bridge. State the units problem plainly: the `0.1709` persistence everything rests on is fitted on **revenue** at `valuation_core_adapter.rs:270-313`, pooled cross-sectionally, through the origin, and cannot be reused as though it were NOPAT growth. Specify the evidence that would select between the two directions. Note that Wave 3 changed the pooled centre, so the number itself will move, and the charter must cite the post-Wave-3 value or state that it is pending.

**T4.4 — `docs/roic-preregistration.md` (write after T4.1).**
- **Exactly one primary endpoint:** cross-sectional median absolute error between predicted and realized incremental return over the three-year horizon, on the **common issuer-cutoff set**.
- **Exactly one promotion rule:** `improvement = MAE(prior_only) − MAE(candidate)`; promote only when improvement exceeds a pre-registered minimum economically meaningful threshold **and** the pre-registered cluster-bootstrap confidence interval for the improvement remains above zero. Comparison against `prior_only` is **paired**; uncertainty is estimated by resampling **clustered at the issuer level**.
- **A concrete numeral for the threshold, together with the derivation that produced it, in the same commit** (Q5 RESOLVED), plus the numeral's sensitivity to its own inputs. The propagation is analytic — through `ReinvestmentRate = g/r` into `C(t) = E(t)(1 − g/r)` — and needs no harness. A pre-registration with a pending threshold is not a pre-registration. The threshold is derived **before** observing which candidate wins and is never chosen from results.
- **Secondary diagnostics may veto a candidate that passed the primary endpoint; they may never promote one that failed it.** Veto set: material signed bias; materially miscalibrated intervals; unacceptable tail failures; temporal instability; evidence leakage; dependence on a small number of issuers; failure in economically important cohorts. **Coverage is excluded from the veto set.**
- **Target specification — resolve every enumerated sub-decision in writing:** exact three-year windows; beginning / ending / average capital; lag treatment; organic vs acquired capital; acquisitions; divestitures; impairments; restructurings; currency effects; restatements; `ΔIC = 0`; small denominators; negative invested capital; negative changes in invested capital; negative NOPAT; issuer-class exclusions; all data-quality exclusion rules. Open with a **"Decisions with material economic leverage"** section naming the 3–5 whose alternatives move the target most, each with the rejected alternative and why (Q6 RESOLVED).
- State the standing rule: **any subsequent change to the target or the exclusions is a NEW experiment requiring a new untouched holdout.**

**T4.5 — `docs/index.md`: all five new entries.**
Under **Feature Planning**, add links to `valuation-economic-contract.md`, `roic-research-charter.md`, `roic-preregistration.md`, `growth-research-charter.md`, `valuation-aggregation-audit.md`, `sec-point-in-time-provenance.md`, and — by this exact filename — `adr-0001-fr-29-removal-and-explicit-unavailable-state.md`. Add a two-line note establishing the flat-`docs/` ADR convention (`docs/adr-NNNN-<slug>.md`; no `docs/adr/` directory), since this is the repository's first ADR.
*Acceptance:* every link resolves after Round 2 completes. **Wave 4 is the sole owner of `docs/index.md`.**

**T4.6 — `AGENTS.md`: the standing rules from Waves 1, 2 and 5.**
Three new rows in **Anti-patterns that already bit us**, written from this plan:

| Anti-pattern | What happened | Do instead |
| --- | --- | --- |
| Annual observation keyed by calendar year alone | The extractor read `filed` and threw it away, and two sites re-derived the year by slicing `end` while holding the real `end`. Nothing downstream could answer "was this knowable at cutoff `t`" | Carry `AnnualProvenance { end, known_from, sources }`; a composite is knowable only once its **last** input was filed; a fact with no filing date is not point-in-time evidence |
| Net concept gap-filling a gross-expense series | `InterestIncomeExpenseNet` resolved as an expense for COF (19 yrs), DAL (15), CHTR (12), BKR (11); for a cash-rich issuer `pretax + interest` double-adds income pretax already contains | Equivalence classes hold one statement's concept only, and gross and net are not equivalents. An issuer filing none reads **absent** |
| A required input substituted by a value-neutral stand-in | FR-29 substituted `r := w` for every issuer, collapsing the valuation to `E_0/w` and crediting growth nothing, universally | A structurally required input that is absent **refuses**: `Refusal::Evidence(NotReported)`. The Core does not publish a number it cannot justify |

Also extend the **Aggregation — no naked averages** section with `robust_centre` (the centre **and** the width of that centre, both from the retained sample) alongside `robust_mean` / `standardize`.

#### Invariants

- I1: No source file is modified by this wave.
- I2: Exactly one primary endpoint and one promotion rule exist in `roic-preregistration.md`.
- I3: The materiality threshold is a concrete number with its derivation in the same commit.
- I4: Coverage appears in no gate and no veto.
- I5: No estimator is selected, ranked as preferred, or described as the likely winner in any of the four documents.
- I6: Every link added to `docs/index.md` resolves at the end of Round 2.

#### BDD scenarios

| id | type | role / actor | given | when | then | notes |
| --- | --- | --- | --- | --- | --- | --- |
| W4-P01 | positive | reviewer | `valuation-economic-contract.md` | each brief-enumerated term is looked up | each has a definition, a unit and an absence state | T4.1 |
| W4-P02 | positive | reviewer | `roic-preregistration.md` | the promotion rule is read | it names one endpoint, a paired comparison against `prior_only`, and issuer-clustered resampling | T4.4 |
| W4-P03 | positive | reviewer | `roic-preregistration.md` | the materiality threshold is read | it is a number, with its derivation and its sensitivity beside it | I3 |
| W4-P04 | positive | reviewer | `valuation-economic-contract.md` | the financial-issuer section is read | it documents the deferral already implemented at `valuation_core_adapter.rs:352-364` and names its absence state | Q4 |
| W4-N01 | negative | reviewer | all four documents | searched for a promoted estimator | none is selected, preferred or predicted | I5 / closing instruction |
| W4-N02 | negative | reviewer | `roic-preregistration.md` | the veto set is read | coverage is not in it, and no veto can promote a candidate that failed the primary endpoint | Decision 1 |
| W4-N03 | negative | reviewer | `roic-research-charter.md` | searched for the OLS levels-slope estimator | it appears only as permanently deleted, including as a refusal signal | brief §0 |
| W4-E01 | edge | reviewer | `roic-research-charter.md` | the `fuse` section is read | `posterior.rs:26-33` is **quoted**, and nothing is wired | assumption 4 |
| W4-E02 | edge | reviewer | `growth-research-charter.md` | the two directions are read | neither is approved, and the revenue-vs-NOPAT units problem is stated | Decision 3 |
| W4-R01 | regression | reader | `docs/index.md` | every added link is followed | all seven resolve | I6 |
| W4-R02 | regression | agent | `AGENTS.md` | the aggregation section is read | `MAX_ABSOLUTE_Z = 3.0` is still stated as non-negotiable and no second implementation is sanctioned | constraint 6 |

#### Automation & evidence

| Level | Command | Class |
| --- | --- | --- |
| manual | reviewer checklist against W4-P01…W4-R02 | **FAST — builder** |
| link check | resolve every path added to `docs/index.md` | **FAST — builder** (note: the ADR link resolves only once Wave 5 lands) |
| integration | `cargo test --lib` — must be **unchanged**, since no source file moved | **DEFERRED — orchestrator** |

**Evidence of pass:** the four documents committed; the checklist answered row by row with a quotation from the document for each; `git diff --name-only` shows only the six files in the ownership matrix.

**Anchor deltas:** none — this wave changes no number. State that explicitly rather than omitting it.

**Failing-set guarantee:** no source file is touched, so the failing set is structurally unchanged.

**Done when:** I1–I6 hold; all four artifacts exist, are indexed, and answer every checklist row.

---

### Wave 5 — FR-29 removal and the explicit unavailable state (atomic)

| Field | Content |
| --- | --- |
| **Wave id** | `wave-5` |
| **Title** | The new Core goes dark rather than retain the substitution |
| **Scope** | The two Core valuation functions, their outlines, the manifest, the two adapter tests, the ADR, the PRD |
| **Dependencies** | None. Repo facts: `Refusal::Evidence(AbsenceReason)` already exists (F8); `refused` is a legal `outcome` token (F9); the blast radius is the six assertions of F2 |
| **Files touched** | `valuation-core/src/projection.rs`; `valuation-core/src/residual_income.rs`; `valuation-core/tests/features/intrinsic-value.feature`; `.../residual-income.feature`; `.../manifest.toml`; `src/valuation_core_adapter.rs`; `src/valuation_core_measurement.rs`; `docs/adr-0001-fr-29-removal-and-explicit-unavailable-state.md` (new); `_bmad-output/.../prd.md`; `_bmad-output/.../addendum.md` |

> **This wave lands in a single commit.** Spec change + rationale + explicit unavailable-state contract + replacement tests + ADR, together (Decision 2).

#### Tasks

**T5.1 — Remove the substitution in `projection.rs`.**
Delete the FR-29 comment and the `unwrap_or` at `:217-223`; move `return_on_capital_bps.value()` into the existing four-way `let-else` at `:206-212`. Rewrite the surrounding rustdoc to state the new contract: a return on capital is a **structurally required input**, and its absence is a refusal, not a value.
*Acceptance:* `grep -n 'unwrap_or' valuation-core/src/projection.rs` shows no reference to `discount`.

**T5.2 — Remove the sibling in `residual_income.rs`.**
Same shape at `:102-111`, folding `return_on_equity_bps` into the `let-else` at `:88-94`.

**T5.3 — Convert the two Core unit tests (never delete).**

| Old | New | Old contract | New contract |
| --- | --- | --- | --- |
| `projection.rs:502` `an_absent_return_on_capital_is_value_neutral_rather_than_floored` | `an_absent_return_on_capital_refuses_rather_than_valuing_at_the_neutral_line` | absent ROC ⇒ value `E_0/w` | absent ROC ⇒ `Observation::Absent(NotReported)` |
| `residual_income.rs:315` `an_absent_return_on_equity_values_the_issuer_at_book` | `an_absent_return_on_equity_refuses_rather_than_valuing_the_issuer_at_book` | absent ROE ⇒ value = book | absent ROE ⇒ `Observation::Absent(NotReported)` |

Each asserts `absence_reason()` (one assert). Note both call sites use the `value_of` helper that `.expect()`s a resolved value; the new tests call `intrinsic_value` / `residual_income_value` directly.

**T5.4 — Convert the two Gherkin rows and their rationale (never delete).**

| File:line | Old row | New row | Old contract | New contract |
| --- | --- | --- | --- | --- |
| `intrinsic-value.feature:32` | `return-absent … ABSENT … 1250.00 resolved` | `return-absent … ABSENT … ABSENT refused` | absent return valued at the neutral line | absent return refuses |
| `residual-income.feature:31` | `return-absent … ABSENT … 1000.00 resolved` | `return-absent … ABSENT … ABSENT refused` | absent return valued at book | absent return refuses |

Rewrite the "Rows worth reading as a set" comment blocks (`intrinsic-value.feature:59-65`, `residual-income.feature:54-60`) so the `value-neutral-return` and `return-absent` rows are described as the **contrast** they now are: *an observed* return equal to the discount rate still collapses to `E_0/w` (unchanged, and that identity is the point), while an *absent* return publishes nothing at all — because "we do not know whether this growth creates value" is not a valuation.
*Acceptance:* `cargo test -p valuation-core --test schema` green — no new column, no new outline, `ABSENT` still the only absence token, tables still rectangular.

**T5.5 — Update `manifest.toml`.**
For `intrinsic-value-from-fading-path` and `residual-income-on-book`, rewrite `covers` so "value-neutral and absent returns" becomes "the value-neutral identity for an *observed* return, and refusal for an absent one". `frs` keeps `FR-29` (D7). `why_new` is untouched and stays non-empty.

**T5.6 — Convert the two adapter tests (never delete).**

| Old | New | Old contract | New contract |
| --- | --- | --- | --- |
| `:1047` `a_complete_issuer_publishes_a_posterior` | `a_complete_issuer_without_a_return_on_capital_refuses_on_evidence` | evidence with no gaps publishes | evidence with no measured return on capital refuses: `Refusal::Evidence(AbsenceReason::NotReported)` |
| `:1057` `an_absent_return_on_capital_values_at_the_neutral_line` | `an_absent_return_on_capital_publishes_no_median_at_all` | published median equals `cash_flow/wacc` bridged to per-share | `median_cents(&value(..)) == None` |

Add `use valuation_core::publication::Refusal;` **inside `mod tests`** — never a fully-qualified path (constraint 10). The `:1047` test is a *transitive* dependant: it breaks because ROC is absent for every issuer, so it must be converted, not deleted.
**Must stay green, unmodified:** `a_bank_refuses_on_evidence_rather_than_being_valued_on_cash_flow` (`:1082`) and `an_issuer_with_too_little_history_refuses_rather_than_extrapolating` (`:1094`). The bank test is the discriminating regression: a financial issuer must still refuse for **book value** (`ProviderUnavailable`), not for the new ROC path.

**T5.7 — Update the two module docs that assert the old behaviour.**
`valuation_core_adapter.rs:22-30` ("FR-29 makes growth value-neutral… every number this module produces today sits at that neutral line") and `valuation_core_measurement.rs:31-38` ("Read the ratios with the neutral line in mind"). Both now state that the Core returns an explicit unavailable state for every issuer until a validated return-on-capital estimator is promoted, and that the diagnostic table's Core column is therefore expected to be all-refusals.

**T5.8 — The ADR: `docs/adr-0001-fr-29-removal-and-explicit-unavailable-state.md`.**
Must contain:
- Context: `return_on_capital` is hard-coded `Observation::absent` for every issuer (`valuation_core_adapter.rs:557`); FR-29 then substituted `r := w`, collapsing `C(t) = E(t)(1 − g/r)` to `E_0/w`, crediting growth nothing, universally.
- Decision: remove the substitution; return `Refusal::Evidence(AbsenceReason::NotReported)`.
- **Why `NotReported` and not a new `AbsenceReason` variant** (D3).
- **The operational consequence, stated plainly:** the new Core publishes **nothing** for any operating issuer until a validated estimator is promoted. This is an intentional product decision, not accidental loss of coverage (Decision 2).
- **The old→new mapping table** for all six converted assertions (T5.3, T5.4, T5.6), each with its old contract and new contract.
- **Site 3, knowingly retained:** `operating_valuation.rs:223` `terminal_payout_bps` is the legacy engine's FR-29 equivalent, is production-live, affects four rows of `shared/contracts/operating-valuation-router-v1.json` (GDDY, WYNN, BSX, ALB) and three tests including one of the three known-failing ones. It stays, per Decision 2's allowance that "the old engine may remain live in the Shell as a separate legacy module during module-by-module replacement." **Recorded here so it is never later mistaken for an oversight.**
- The named, accepted limitation: the Core's refusal does not identify *which* required input was missing; that is true equally for base cash flow, growth and discount rate and is a Core-wide attribution question, not an FR-29 question.
- The ADR filename convention note is in `docs/index.md` (Wave 4, T4.5).

**T5.9 — The prose specification follows the executable one.**
Rewrite `prd.md:404-411` (FR-29) and the FR-29 sentence in `addendum.md:104-108` to state the new contract, retaining the identifier `FR-29` and retitling it *"Missing return evidence refuses rather than substituting a value-neutral return"*. Keep the *other* consequence bullets that are still true: an **observed** low return is used as observed and is never floored; observed-equal-to-cost-of-capital still collapses to earnings over the discount rate.
**Do not** touch `status: draft` and **do not** run the PRD Finalize workflow (brief §2). Leaving `prd.md` asserting the opposite of the code would be exactly the "new rule lives only in chat" failure `AGENTS.md` forbids.

**T5.10 — Measure and report.**
Run the offline diagnostic; report the Core column for **PG, GOOGL, AMZN, MSFT** before and after (expected: a value before, a refusal after) alongside the old engine's column, which does not move. State explicitly that nothing published changes because `value()` has no production caller (F1).

#### Invariants

- I1: No structurally required input is ever substituted by another input, in either Core valuation function.
- I2: The refusal is a named state: `Refusal::Evidence(AbsenceReason::NotReported)`, `kind() == "evidence"`, `detail() == "not_reported"`.
- I3: All six affected assertions are **converted**, none deleted, none relaxed, each mapped old→new in the ADR.
- I4: An **observed** return equal to the discount rate still values at `E_0/w`, and an observed return below terminal growth is still not floored — the identity FR-28 exists for is untouched.
- I5: `schema.rs`'s six rules still pass; no new outline, no new column, no new absence token.
- I6: `operating_valuation.rs` is not modified.
- I7: The spec change, the rationale, the replacement tests and the ADR are in one commit.

#### BDD scenarios

| id | type | role / actor | given | when | then | notes |
| --- | --- | --- | --- | --- | --- | --- |
| W5-P01 | positive | Core | an absent return on capital and every other input measured | `intrinsic_value` resolves | the valuation is `Absent(NotReported)` | T5.3 |
| W5-P02 | positive | Core | an absent return on equity and every other input measured | `residual_income_value` resolves | the valuation is `Absent(NotReported)` | T5.3 |
| W5-P03 | positive | adapter | the pinned cohort, complete evidence, no invested capital | `value()` runs | the posterior is `Refused(Evidence(NotReported))` | T5.6, I2 |
| W5-P04 | positive | adapter | the same issuer | `median_cents` is read | it is `None` | T5.6 |
| W5-N01 | negative | Gherkin | `intrinsic-value.feature` row `return-absent` | the outline runs | value `ABSENT`, outcome `refused` | T5.4 |
| W5-N02 | negative | Gherkin | `residual-income.feature` row `return-absent` | the outline runs | value `ABSENT`, outcome `refused` | T5.4 |
| W5-N03 | negative | reviewer | both Core functions | searched for `unwrap_or` on a rate | none substitutes one input for another | I1 |
| W5-E01 | edge | Core | an **observed** return exactly equal to the discount rate | `intrinsic_value` resolves | still `1250.00` — the identity is unchanged | I4; `value-neutral-return` row untouched |
| W5-E02 | edge | Core | an observed return **below** terminal growth | `intrinsic_value` resolves | still negative, still not floored | I4; `return-below-terminal` row untouched |
| W5-E03 | edge | Core | an observed return on equity below the cost of equity | `residual_income_value` resolves | still below book | I4 |
| W5-R01 | regression | adapter | a Financial Services issuer | `value()` runs | it still refuses for **book value** (`ProviderUnavailable`), not for the return path | `a_bank_refuses…` unmodified and green |
| W5-R02 | regression | adapter | an issuer with two years of history | `value()` runs | still not published | `an_issuer_with_too_little_history…` unmodified and green |
| W5-R03 | regression | schema | the two edited feature files | `cargo test -p valuation-core --test schema` | all six rules pass | I5 |
| W5-R04 | regression | legacy | `operating_valuation::terminal_payout_bps` | the legacy suite runs | unchanged; the known-failing test is neither repaired nor worsened | I6 / D5 |
| W5-R05 | regression | manifest | `manifest.toml` | `every_outline_is_justified_in_the_manifest` runs | green; `why_new` still non-empty for both edited entries | T5.5 |

#### Automation & evidence

| Level | Command | Class |
| --- | --- | --- |
| unit | `cargo test -p valuation-core --lib` | **FAST — builder (<10s)** |
| schema | `cargo test -p valuation-core --test schema` | **FAST — builder** |
| unit | `cargo test --lib valuation_core_adapter::` | **FAST — builder (module-scoped)** |
| diagnostic | `cargo test --lib core_versus_current_engine_on_the_pinned_cohort -- --ignored --nocapture` (offline) | **FAST — builder** |
| lint | `cargo fmt -- --check` | **FAST — builder** |
| cucumber | `cargo test -p valuation-core` (runs `fail_on_skipped`) | **DEFERRED — orchestrator** |
| integration | `cargo test --lib` (full) | **DEFERRED — orchestrator** |
| merge bar | `cargo test --lib dcf_model::` + `cargo test --lib valuation_baseline::` | **DEFERRED — orchestrator** |

**Evidence of pass:** the fifteen scenarios green; the ADR's six-row old→new mapping table; the T5.10 anchor table; a single commit containing spec + rationale + tests + ADR.

**Anchor deltas:** required and reported from T5.10. Expected: the Core column becomes a refusal for PG, GOOGL, AMZN, MSFT; **the published value for all four is unchanged**, because it comes from `dcf_model` / `operating_valuation`, which this wave does not touch (F1, D5). If the diagnostic shows a *published* anchor moving, the wave **stops and asks**.

**Failing-set guarantee:** Wave 5 touches neither `operating_valuation.rs` nor `valuation_baseline.rs` nor `valuation_high_signal.rs` (I6), and the adapter has no production caller. `git diff --name-only` confirms; the orchestrator's full run verifies the set is still exactly the three.

**Done when:** I1–I7 hold; the ADR, the PRD edit, the converted tests and the spec change are one commit; the diagnostic table is recorded.

---

## 3. Task quality

Every task above names an outcome and an acceptance criterion that a builder can check without asking a product question. The design questions a builder might otherwise have to answer — which PIT carrier, which `AbsenceReason`, what the Gherkin cells become, whether to add a reason column, which averaging sites to keep, whether `fy`/`fp` are retained, whether `operating_valuation.rs:223` moves, whether the PRD may be edited — are all decided in §1.5 and §2 with their reasoning attached. The one genuine mechanism choice left open (Wave 1 T3, site `:497`) has two acceptable answers, both stated, with the requirement that whichever is taken is recorded in the wave's doc.

---

## 4. Testing methodology — summary

Per-wave invariants, BDD tables and commands are in §2. Cross-cutting rules:

- **One assert per test.** Where two properties must hold, use the collected-violations pattern already in the codebase (`projection.rs:512-523`, `valuation_core_adapter.rs:1024-1037`) — a single `assert!` over a `Vec` of offenders whose message names them. Never two bare `assert!`s.
- **Never a fully-qualified name.** Import at the top of the module, or inside `mod tests` for test-only types (`Refusal`, `robust_centre`).
- **Builders may run:** module-scoped `cargo test --lib <module>::`, the whole `valuation-core` **lib** target, `--test schema`, `cargo fmt -- --check`, the generator with `-OutputRoot`, and the **offline** `core_versus_current_engine_on_the_pinned_cohort` diagnostic. The `valuation-core` crate has an empty dependency list, so its lib and schema targets meet the <10s bar; the Shell crate's link step dominates its module-scoped runs, which are still builder-runnable.
- **Builders may not run:** full `cargo test`, cucumber (`cargo test -p valuation-core` without a target filter), `validate-contracts.ps1`, `validate-android.ps1`, or any network probe.
- **The three known-failing tests** — `operating_valuation::tests::durable_reported_and_holdout_cohorts_recompute_in_normal_gate`, `valuation_baseline::baseline_megacap_amzn_class_not_penny_intrinsic`, `valuation_high_signal::high_signal_screener_cohort_all_members_pass` — are protected structurally: **no wave's file set contains their files**, and each wave verifies this with `git diff --name-only`. The orchestrator's full run verifies the count. Wave 2 is the only wave that can change their *inputs* (live SEC), and it must report any change rather than patch it. Baseline to hold: Shell `cargo test --lib` = 518 passing, 22 ignored, those 3 failing; Core = 89 + 7 passing.
- **Running the high-signal test rewrites** `apps/windows/src-tauri/tests/fixtures/valuation/high_signal_screener_observation_2026-08-02.json`. It **stays unstaged** (constraint 8). Never `git add -A` (constraint 7).

---

## 5. Documentation — all deliverables, with paths

| Wave | Path | Kind |
| --- | --- | --- |
| W1 | `docs/sec-point-in-time-provenance.md` | new operator/architecture doc |
| W1 | `apps/windows/src-tauri/src/edgar.rs` module rustdoc | in-code contract |
| W2 | `shared/contracts/sec-driver-normalization.json` → `interestExpense.rationale` | contract rationale (the established convention; the `InterestPaidNet` precedent lives here) |
| W2 | `shared/contracts/README.md` — *"Equivalence classes hold one statement's concept only"* | contract doc |
| W3 | `docs/valuation-aggregation-audit.md` | new audit doc with measured before/after |
| W3 | rustdoc notes at each **Keep** site in `valuation_core_adapter.rs` | in-code rationale |
| W4 | `docs/valuation-economic-contract.md` | **gating artifact** |
| W4 | `docs/roic-research-charter.md` | research charter |
| W4 | `docs/roic-preregistration.md` | pre-registration |
| W4 | `docs/growth-research-charter.md` | research charter |
| W4 | `docs/index.md` | index — **sole owner**, adds all seven links + the ADR filename convention |
| W4 | `AGENTS.md` | three anti-pattern rows + the `robust_centre` aggregation rule |
| W5 | `docs/adr-0001-fr-29-removal-and-explicit-unavailable-state.md` | **ADR**, first in the repo |
| W5 | `_bmad-output/.../prd.md` FR-29 + `addendum.md` B.5 | prose specification follows the executable one |
| W5 | feature-file rationale comments + `manifest.toml` `covers` | executable specification's own rationale |
| W5 | `valuation_core_adapter.rs` and `valuation_core_measurement.rs` module docs | in-code contract |

None of these is optional. A wave is not done without its documentation.

---

## 6. Cross-cutting

### 6.1 Scheduling — the two real file conflicts

1. **`valuation_core_adapter.rs` is wanted by W3 and W5.** They are logically independent — W5 does not need W3's `robust_centre`, and W3 does not need W5's refusal — but two builders cannot edit one file in one worktree. Resolved by rounds: **W3 in Round 1, W5 in Round 2.** Merging them into one wave was rejected because W5 must land **atomically** as a spec change and W3 is unrelated arithmetic; a combined commit would make the FR-29 record harder to read, which is the opposite of Decision 2's purpose.
2. **`docs/index.md` would be wanted by W1, W3, W4 and W5** — four builders, one file, guaranteed conflict. Resolved by **single ownership**: Wave 4 writes all seven links, including `docs/sec-point-in-time-provenance.md`, `docs/valuation-aggregation-audit.md` and `docs/adr-0001-fr-29-removal-and-explicit-unavailable-state.md`, using the filenames fixed in this plan. The other three waves create their files and do not index them. **The orchestrator must verify at the end of Round 2 that all seven links resolve** — that is the residual risk this arrangement creates, and it is cheap to check.

`AGENTS.md` was resolved the same way: sole owner Wave 4.

### 6.2 Rollout / migration

- No persisted format changes. `AnnualValue` has no serde and never leaves `edgar.rs` (F3); `CrossSectionDiagnostics` has no serde (F12); `AbsenceReason` and `Refusal` are pre-existing.
- The **one** versioned migration is `sec-driver-normalization/8 → /9`, which must land as a set: contract, generated Rust, generated Kotlin, fixtures file, and the Rust assertion. Cache keys and revisions that key on the policy fingerprint invalidate on their own, which is the intended behaviour.
- No UI change, no IPC change, no live QA required — none of these waves alters a user-visible number on a path the app calls, with the sole exception of Wave 2's live SEC extraction, which is covered by the merge bar and the anchor-delta report.

### 6.3 Observability and provenance

- Wave 1's whole purpose is provenance: after it, any consumer can answer *was this knowable at cutoff `t`, from which filing, under which period interpretation* without reconstructing anything from `year`.
- Wave 3 makes trimming observable: `discarded()` is reported alongside every robust centre, and `growth_pooled_discarded` reaches `CrossSectionDiagnostics`, satisfying the AGENTS rule that trimming must report how many observations it dropped.
- Wave 5 makes refusal observable: `Refusal::Evidence(NotReported)` with `detail() == "not_reported"` is a named state a UI can surface, rather than a silent neutral-line number.
- Every wave that changes a number reports **PG, GOOGL, AMZN, MSFT** before and after as a diagnostic. Anchors are never a calibration target (constraint 9). AAPL is deliberately excluded.

### 6.4 The one open branch — Q1, isolated

The plan proceeds on **option (i)**: remove both net concepts, following the `InterestPaidNet` precedent. Reversing to option (ii)-pending, or to no-change, costs exactly:

1. re-add the two strings to `drivers.interestExpense.qnames` at positions 7 and 8 in `shared/contracts/sec-driver-normalization.json`;
2. edit the `rationale` string beside them;
3. re-run `scripts/generate-sec-driver-normalization-policy.ps1`;
4. move the fingerprint (to `/10`, since `/9` will have shipped) in the same five places.

No other wave reads the interest qname list. **Nothing else in the run depends on this branch.**

Option (ii) itself — value-conditional admission — is recorded as a **named, unimplemented finding** in `docs/roic-research-charter.md` (Wave 4, T4.2) together with the reason it is not expressible today (no `sign` field; `rejection` is a static category lookup; `precedence` ranks but never rejects; the generator emits four driver fields and drops the rest) and the probe that would decide it. If Juan answers before Round 1 starts, Wave 2's T2.1 is the only task that changes.

### 6.5 Explicitly out of scope

- Work-order items **6** (rolling PIT harness), **7** (candidates, benchmarks, ablations), **9** (integration).
- Item **8** beyond writing `docs/growth-research-charter.md`. Neither growth direction is approved.
- **Selecting or promoting any return-on-capital estimator.** Book ROIC, `prior_only` and shrinkage remain research candidates only.
- Wiring `posterior::fuse` to the ROIC channel. The audit is a written finding, nothing is wired.
- The adapter change (NOPAT base + measured ROIC landing together).
- **`operating_valuation.rs:223`** — legacy, production-live, knowingly retained, **recorded in the ADR** (D5).
- Extending `dcf_model::FcfPoint` with provenance (D1 boundary; item 6 is its consumer and is out of scope).
- Fixing `resolve_capex_abs`'s zero-CapEx fallback (`edgar.rs:604-607`) — a real fabricated zero, **named in the economic contract** as a defect this run does not touch because it is on the production FCF path and would move published anchors.
- AMZN policy/16; Android parity work beyond regenerating the policy file; the ROIC fixture; `_bmad-output/project-context.md`; re-capturing `core_driver_data_deep.json` (stale relative to `/8`, deliberately parked).
- Running the PRD Finalize workflow. `prd.md` stays `status: draft`.
- Repairing, relaxing, or extending the three known-failing tests.

---

## Absolute paths referenced

`G:\dev\repos\discount_screener\.agents\workspace\tmp\e2e\valuation-pit-contract\brief.md`
`G:\dev\repos\discount_screener\.agents\workspace\tmp\e2e\valuation-pit-contract\refine.md`
`G:\dev\repos\discount_screener\AGENTS.md`
`G:\dev\repos\discount_screener\docs\index.md`
`G:\dev\repos\discount_screener\shared\contracts\sec-driver-normalization.json`
`G:\dev\repos\discount_screener\shared\contracts\sec-driver-normalization-fixtures.json`
`G:\dev\repos\discount_screener\shared\contracts\README.md`
`G:\dev\repos\discount_screener\scripts\generate-sec-driver-normalization-policy.ps1`
`G:\dev\repos\discount_screener\scripts\validate-contracts.ps1`
`G:\dev\repos\discount_screener\apps\windows\src-tauri\src\edgar.rs`
`G:\dev\repos\discount_screener\apps\windows\src-tauri\src\sec_normalization.rs`
`G:\dev\repos\discount_screener\apps\windows\src-tauri\src\sec_driver_normalization_policy_generated.rs`
`G:\dev\repos\discount_screener\apps\windows\src-tauri\src\valuation_core_adapter.rs`
`G:\dev\repos\discount_screener\apps\windows\src-tauri\src\valuation_core_measurement.rs`
`G:\dev\repos\discount_screener\apps\windows\src-tauri\src\operating_valuation.rs`
`G:\dev\repos\discount_screener\apps\windows\src-tauri\valuation-core\src\numerics.rs`
`G:\dev\repos\discount_screener\apps\windows\src-tauri\valuation-core\src\evidence.rs`
`G:\dev\repos\discount_screener\apps\windows\src-tauri\valuation-core\src\projection.rs`
`G:\dev\repos\discount_screener\apps\windows\src-tauri\valuation-core\src\residual_income.rs`
`G:\dev\repos\discount_screener\apps\windows\src-tauri\valuation-core\src\publication.rs`
`G:\dev\repos\discount_screener\apps\windows\src-tauri\valuation-core\src\lib.rs`
`G:\dev\repos\discount_screener\apps\windows\src-tauri\valuation-core\tests\features\intrinsic-value.feature`
`G:\dev\repos\discount_screener\apps\windows\src-tauri\valuation-core\tests\features\residual-income.feature`
`G:\dev\repos\discount_screener\apps\windows\src-tauri\valuation-core\tests\features\manifest.toml`
`G:\dev\repos\discount_screener\apps\windows\src-tauri\valuation-core\tests\schema.rs`
`G:\dev\repos\discount_screener\apps\windows\src-tauri\valuation-core\tests\cucumber.rs`
`G:\dev\repos\discount_screener\apps\android\core\src\main\kotlin\com\discountscreener\core\engine\SecDriverNormalizationPolicyGenerated.kt`
`G:\dev\repos\discount_screener\_bmad-output\planning-artifacts\prds\prd-discount_screener-2026-08-03\prd.md`
`G:\dev\repos\discount_screener\_bmad-output\planning-artifacts\prds\prd-discount_screener-2026-08-03\addendum.md`

---

**TL;DR** — Five waves, zero cross-wave dependencies, two rounds: Round 1 runs W1 (PIT provenance in `edgar.rs`), W2 (interest equivalence class + fingerprint `/9`), W3 (`robust_centre` + the two naked means) fully parallel and file-disjoint; Round 2 runs W4 (four docs + `index.md` + `AGENTS.md`) and W5 (FR-29 removal, atomic, with the ADR). Two real file conflicts exist and I resolved them by scheduling and single ownership, not by faking independence: **W3 and W5 both want `valuation_core_adapter.rs`** (different rounds), and **four waves would want `docs/index.md`** (Wave 4 owns it alone and writes all seven links, including the ADR's, by the filename fixed in the plan — the orchestrator must verify the links resolve at the end of Round 2). Key decided calls: extend `AnnualValue` with `AnnualProvenance { end, known_from, sources: Vec<SecFact> }`, reusing `SecFact` and deliberately **not** retaining `fy`/`fp` (documented trap; `accession` identifies the filing exactly); the FR-29 unavailable state is the existing `AbsenceReason::NotReported` surfacing as `Refusal::Evidence(NotReported)`, with the Gherkin rows becoming `value = ABSENT` / `outcome = refused` and **no new column or outline**; `docs/adr-0001-fr-29-removal-and-explicit-unavailable-state.md` records both the six converted assertions and the knowingly retained legacy site `operating_valuation.rs:223`. Wave 2 is the only wave that can move a published anchor — its gate is the orchestrator's full suite plus the merge bar, and a >±5% anchor move stops the run.
agentId: a43d61214fee83984 (use SendMessage with to: 'a43d61214fee83984', summary: '<5-10 word recap>' to continue this agent)
<usage>subagent_tokens: 219575
tool_uses: 55
duration_ms: 1125799</usage>