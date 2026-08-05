# plan.v0 — sales-to-capital branch (`r10`)

## 0. Ground check (STEP 0, every wave, before anything else)

Per R-12.1. Any failure is an immediate stop, not an improvisation.

| check | expected |
|---|---|
| `git -C <worktree> log --oneline -1` | `21d48b3 probe(valuation): print the annual series behind each centre; choose no window` |
| `git -C <worktree> branch --show-current` | `r10` |
| `git -C <worktree> status --short` | at most `M apps/windows/src-tauri/tests/fixtures/valuation/high_signal_screener_observation_2026-08-02.json` — never staged (constraint 8) |
| `grep -n POLICY_FINGERPRINT apps/windows/src-tauri/src/sec_driver_normalization_policy_generated.rs` | `sec-driver-normalization/9` |
| files present | `src/valuation_probes.rs`, `src/valuation_core_measurement.rs`, `valuation-core/tests/features/manifest.toml` |
| `cargo test --lib` (from `apps/windows/src-tauri`) | `566 passed / 4 failed / 26 ignored`, failures exactly: `cross_platform_parity::export_random20_sp500_parity_snapshot`, `operating_valuation::tests::durable_reported_and_holdout_cohorts_recompute_in_normal_gate`, `valuation_baseline::baseline_megacap_amzn_class_not_penny_intrinsic`, `valuation_high_signal::high_signal_screener_cohort_all_members_pass` |

`.agents/` and `_bmad-output/` are untracked and absent from worktrees — read plan and rulings from the main checkout / artifacts worktree paths.

---

## 1. Summary

### 1.1 The verification you ordered — result: **the claim is substantially TRUE, and it is materially incomplete in a way that changes the plan**

I read `projection.rs::intrinsic_value`, the adapter, the feature table and the golden fixture. Findings, each with the evidence that would falsify it:

**(a) FR-29 is gone and `r` is hardcoded absent. Confirmed.**
`valuation_core_adapter.rs:681-686`:
```rust
fn return_on_capital(&self, frame: &MarketFrame) -> Observation<f64> {
    Observation::absent(AbsenceReason::ProviderUnavailable, self.provenance("invested_capital", frame))
}
```
`projection.rs:224-226` refuses `EstimatorUnavailable` on an absent return. No substitution anywhere.

**(b) The Core has no production consumer at all.** `grep -rn "valuation_core_adapter::" src/` returns exactly two files: `valuation_core_measurement.rs` (the gate and the diagnostics) and `valuation_probes.rs` (`after_tax_fcff`, `least_squares`). Nothing user-facing values through the Core. So **no published cents can move in any of these waves until the estimator lands** — before or after a base change. That half of the claim is unconditionally true and stronger than stated.

**(c) The claim is wrong about *uniformity*, and this is the part that changes the plan.** The gate pins **refusal reason** as a first-class golden value, and the twenty pinned issuers do **not** all carry the same one:

| | count | issuers |
|---|---|---|
| `evidence / estimator_unavailable` | **18** | VRRM T ADMA INOD VICR AMSC AMZN AAPL IDCC FIGS CALX MSFT MIR ROCK HURN VRT INVA APP |
| `evidence / not_reported` | **2** | **MH, BWMN** |

(measured from `tests/fixtures/valuation/published_value_regression_gate_cohort.json`; matches R-38.5's "eighteen pinned refusal reasons" exactly, which is corroboration from a different route.)

Refusal ordering in `projection.rs:206-226` is: base/growth/discount absent → `NotReported`; base non-finite → `OutOfPolicyRange`; **then** return absent → `EstimatorUnavailable`. So:

- The 18 are the **falsifiable population**: if NOPAT fails to resolve where FCFF resolves, they flip `estimator_unavailable → not_reported` and the gate goes red.
- MH (3 annuals → 2 growth transitions; `standardize` refuses `n < 3` at `numerics.rs:137`) and BWMN are **structurally insensitive**: they already refuse upstream of the return check, and a base change cannot move them off `not_reported`. **Their two green rows are therefore vacuous evidence for this wave** — the R-8.4 masked-pin shape, stated in advance so nobody counts 20-of-20 as 20 independent confirmations.

**(d) The base change is not a pure code change, and this is the finding that most changes the sequencing.** `IssuerAnnual` (`valuation_core_adapter.rs:129-137`) carries `year, operating_cash_flow, capital_expenditure, revenue, interest_expense, debt, marginal_tax_bps` — **no pretax income**. `DriverAnnual` (`valuation_baseline.rs:67-78`) is the same. The gate's offline corpus `core_driver_data_deep.json` has row keys `capex debt effective_tax_bps interest marginal_tax_bps ocf revenue year` — **no pretax**. `NOPAT = (pretax + interest) × (1 − t)` is not derivable from anything in the file.

So Wave A requires a **network-bound enrichment of a committed fixture**, and a naive re-run of `capture_the_deep_driver_fixture` would rewrite the whole corpus — including the 179 of 274 issuer-years currently reading `marginal_tax_bps == 2100` (LD-17, re-measured: 274 years, 179 at 2100, 24 at `effective_tax_bps == 0`, zero nulls). Those years would become `null`, be dropped by `issuer_annual`, and almost certainly flip refusal reasons. **The enrichment must be surgical — add one key, touch nothing else — and that property must be proven by diff, not asserted.**

**(e) Verified as already landed, so it is not re-planned:** the generator `-Check` wiring exists (`scripts/validate-contracts.ps1:6` invokes `generate-sec-driver-normalization-policy.ps1 -Check` first), and the generator emits both targets from a loop over the contract (`scripts/generate-sec-driver-normalization-policy.ps1:156-157`). Inherited-plan §1 is done.

**Conclusion on sequencing.** The base change **can** land alone and cannot move a published cent — but it is *not* free, and its inertness is conditional on pretax supply for 18 named issuers. That condition is measurable **before** the wave, offline-checkable **after** it, and is exactly R-38.5's acceptance criterion. So: measure first (W1), pin the ordering the argument rests on (W2), then land the base (W4).

### 1.2 Goal and non-goals

**Goal.** Put the branch in a state where the estimator is the *only* remaining unknown: the `E` slot holds NOPAT, the operating-capital denominator is measurable from a real contract driver, the evidence holes are named, and the two economic decisions that are Juan's are registered as decisions rather than absorbed by a default.

**Non-goals (explicit).** Choosing a window. Choosing `prod` vs `roic`. Choosing a bounds check (R-44.5: the bound is the arithmetic guard that already exists). Publishing any value. AMZN policy/16, Android parity, the ROIC fixture, LD-2..LD-11, LD-14, LD-16, LD-17-as-a-corpus-repair, the two R-35.5 findings. Merging `worktree-agent-a19c1b1e4036e2768` or `measure-guard-rules`. Fixing any of the four known failures.

### 1.3 Approach and key design decisions

1. **The Core does not change its economics.** R-44.1: `C(t) = E(t)(1 − g/r) ≡ E(t)(1 − b(t))`, and `NOPAT/Capital ≡ (Sales/Capital)×(NOPAT/Sales)` year by year. `intrinsic_value` is untouched except for documentation and two guard-pinning rows.
2. **"Unrepresentable as FCFF" is enforced at the adapter, not in the Core.** Decision, with the alternative recorded: a newtype in `valuation-core` would change a public API that `residual_income` publishes through, and the wrong input can only be *constructed* in the adapter. So the adapter gets the type discipline (`fn nopat()` replaces `fn free_cash_flow()` on the base path; provenance source string becomes `"nopat"`), plus a named runtime test. **Alternative rejected:** a Core-level `OperatingEarnings` newtype — stronger, but it moves a purity-constrained public boundary for a defect that lives one layer up. If a reviewer prefers it, the cost is the Core's signature, `cucumber.rs`'s `when_intrinsic_value`, and `residual_income`'s shared `Valuation` boundary.
3. **Every fixture write is surgical and diff-proven.** Never a re-capture.
4. **The `|z| > 3` finding (R-46.2) is swept and reported, never fixed here**, and `MAX_ABSOLUTE_Z` does not move.
5. **New behaviour lands as rows in the existing `intrinsic-value.feature` Examples table.** No new outline is proposed; §5 states what I looked at and why the table absorbs it.

### 1.4 Public interface / contract changes

| surface | change | wave |
|---|---|---|
| `IssuerAnnual`, `DriverAnnual` | gain `pretax_income: Option<f64>` | W4 |
| `IssuerEvidence::base_cash_flow` | provenance `"free_cash_flow"` → `"nopat"`; computes NOPAT | W4 |
| `core_driver_data_deep.json` | gains `"pretax"` per row (null when unfiled) | W4 |
| `shared/contracts/sec-driver-normalization.json` | new `operatingCash` driver (composition); fingerprint `/9` → `/10`; conditionally `marginalTaxReference` gains qnames | W5 |
| both generated policy files | regenerated | W5 |
| `sec-driver-normalization-fixtures.json` | `policyFingerprint` `/10` | W5 |
| `intrinsic-value.feature` | 3 rows added to the existing table | W2 |

### 1.5 Assumptions and risks

- **A1 (unverified — W1 must prove).** Pretax income is filed for ≥3 of the years already in the deep fixture for each of the 18 sensitive issuers. If false for any, W4's inertness fails and the wave stops for Juan rather than re-blessing.
- **A2 (unverified — W1 must prove).** A cash/short-term-investments equivalence class with real filed coverage exists for this universe. R-45.2 measured only 4 of 28 filing the aggregate concept.
- **A3 (derived, low risk).** BWMN's `not_reported` originates in growth or discount, not the base (6 annuals ≥ `MIN_ANNUAL_OBSERVATIONS = 3`). Not directly confirmed; settle with `cargo test --lib core_versus_current_engine_on_the_pinned_cohort -- --ignored --nocapture`. **Design does not depend on it** — either way BWMN cannot leave `not_reported` under W4.
- **R1.** A future reader treats W4's 20-of-20 green gate as 20 confirmations. Mitigated by naming the falsifiable population as 18 in the pre-registration and in the wave report.
- **R2.** The anchors are only half-visible to the gate: **AMZN and MSFT are pinned; PG and GOOGL are not** (`PROBE_COHORT` at `valuation_probes.rs:105-109` is a different 28-name population; overlap with the pinned 20 is `{T, MSFT, AMZN}`). Quoting the gate as the blast radius would be the R-8.2 / R-10.1 error a fourth time. Every wave report must name which cohort each number belongs to.

---

## 2. Waves

Dependency graph:

```
W1 (probe/evidence audit)   []          ─┬─► W4 (NOPAT base)   ─┐
W2 (refusal-ordering rows)  []          ─┘                      ├─► W6 (estimator, BLOCKED)
W3 (time-axis sweep)        []                                  │
W1 ───────────────────────────────────► W5 (cash driver)       ─┘
```

Independent roots: W1, W2, W3 (cap 3, exactly filled). W4 and W5 are serial. W6 is serial *and* blocked on decisions that are Juan's.

---

### Wave 1 — Measure the evidence the next three waves stand on

| field | value |
|---|---|
| **id** | `wave-1` |
| **depends_on** | `[]` |
| **Continuity** | new chain |
| **Scope** | `apps/windows/src-tauri/src/valuation_probes.rs`, `docs/valuation-economic-contract.md` |
| **Value posture** | **Value-neutral by construction.** Probes are `#[ignore]`d; no production path is touched. |

**Why this is a wave and not a task inside W4.** R-45.4 / R-41.6: a mechanism gets measured before it gets built against. W4's entire inertness argument rests on A1, which nobody has measured on the *pinned* cohort. R-40.1 cost this effort a near-miss on exactly this shape.

#### Tasks

**T1.1 — Pretax supply audit for the pinned twenty.**
New `#[ignore]`d probe `probe_pinned_cohort_pretax_supply`. For each of the 20 symbols in `core_driver_data_deep.json`: fetch `fetch_fcf_history`, and for **each year already present in the fixture**, report whether `pretax_income_dollars` is `Some`. Print per issuer: `years_in_fixture`, `years_with_pretax`, `years_lost`, and the list of lost years.
*Done when:* the table is printed for all 20, and the report states, per issuer, `years_with_pretax >= 3` yes/no. **Chooses nothing, changes nothing.**

**T1.2 — Write the W4 pre-registration, before W4 runs.**
A per-issuer table of the 20 predicted golden `core` outcomes after W4, derived from T1.1:
- 18 predicted `refused(evidence/estimator_unavailable)` — **the falsifiable population**;
- MH, BWMN predicted `refused(evidence/not_reported)` — **stated as vacuous**, they cannot falsify anything;
- any issuer T1.1 shows dropping below 3 pretax years is named **now**, with its predicted flip, and W4 **stops for Juan** rather than re-blessing.
*Done when:* the table exists in the wave report with a date and the commit it was written against, and it can be falsified in both directions (an unpredicted flip is a stop; a predicted flip that does not occur is also a stop).

**T1.3 — Cash / short-term-investments qname coverage.**
Extend the existing probe-local reporting to print, per issuer across `PROBE_COHORT` **and** the pinned 20, which of these resolve and for how many years: `CashCashEquivalentsAndShortTermInvestments`, `CashAndCashEquivalentsAtCarryingValue`, `ShortTermInvestments`, `MarketableSecuritiesCurrent`, `AvailableForSaleSecuritiesDebtSecuritiesCurrent`, `OtherShortTermInvestments`.
*Done when:* a per-qname year-count table exists. **The candidate list above is a starting set for measurement, not a contract proposal.** W5 adopts what the evidence supports; a qname with zero coverage does not enter the contract.

**T1.4 — AMZN's marginal-rate hole (R-46.3).**
For AMZN 2014–2022, report which of `marginalTaxReference`'s five qnames appear in `companyfacts` at all, and whether any *other* statutory-rate concept appears. Print the raw qnames found in the tax section for those years.
*Done when:* the answer is one of exactly three, stated plainly: (i) a qname exists and is not in the class → W5 adds it; (ii) a qname exists but in an unusable unit/shape → W5 records why, no change; (iii) nothing is filed → **AMZN honestly refuses until the data exists**, and that is registered as a latent defect with trigger and detector. **No fabrication, no statutory substitution, under any of the three.** R-46.3 and R-41.3 are not re-opened.

**T1.5 — Relabel the SBC column (R-44.4).**
`valuation_probes.rs`: rename the printed column from `sbc/NOP` framed as a correction, to a descriptive magnitude, and print alongside it, in the probe's own output, the reason it is **not** a correction — SBC is an in-kind expense, correctly expensed, and adding it back to reinvestment is the one treatment R-43.3 names as wrong. Also remove `sbc` from the `resid = b + sbc + dtax` framing or relabel `resid` to say what it is (a descriptive decomposition, not a corrected `b`).
*Done when:* no output line invites a reader to apply the correction, and the legend says so in a sentence.

**T1.6 — Documentation.**
`docs/valuation-economic-contract.md`:
- **§1** currently says the marginal rate falls back to `STATUTORY_MARGINAL_TAX_BPS` when unfiled. **That is stale** — R-41.3 deleted the fallback. Correct it to: an issuer-year with no filed marginal rate has no NOPAT and is dropped.
- **§10** states "a NOPAT base alone … charges reinvestment zero times, and **overvalues**". Add a correction note: that is true of the general model and was written when FR-29 substituted `r := w`; **in this Core, since FR-29's deletion, an absent `r` refuses before the base is used**, so a NOPAT base with `r` absent publishes nothing at all. Cite `projection.rs:224-226` and the refusal ordering. This is the sentence a future reader would otherwise use to argue W4 is unsafe alone.
- **§14** register: add the SBC-framing entry as **closed by T1.5** with the commit.

#### Invariants

- I1.1 No production file is modified. `git status` shows only `valuation_probes.rs`, docs, and the always-unstaged high-signal fixture.
- I1.2 No probe writes to `core_driver_data_deep.json` or `published_value_regression_gate_cohort.json`.
- I1.3 Every summary statistic printed goes through `robust_centre` / `robust_mean` (no threshold argument). No `sum/n`, no `sorted[len/2]`.
- I1.4 No number measured in this wave is used to choose a window, an N, a weight, a trim, or an estimator.

#### BDD scenarios

| id | type | actor | given | when | then | notes |
|---|---|---|---|---|---|---|
| W1-P01 | positive | probe | the pinned 20 and a reachable EDGAR | T1.1 runs | a per-issuer pretax coverage table prints, 20 rows | the wave's deliverable |
| W1-P02 | positive | probe | T1.1's output | T1.2 is written | 20 predicted outcomes, 18 marked falsifiable, 2 marked vacuous | pre-registration |
| W1-N01 | negative | probe | EDGAR unreachable or ≥1 fetch fails | T1.1 runs | the wave reports **"not measured"** for those issuers and W4 does not proceed | R-38.2's null condition, restated; a partial audit is not an audit |
| W1-N02 | negative | probe | an issuer-year with no filed pretax | T1.1 runs | it is counted as lost, never imputed, never carried forward | absence is not a zero |
| W1-E01 | edge | probe | AMZN 2014–2022 | T1.4 runs | one of the three named answers, with the raw qnames printed | no fourth answer exists |
| W1-E02 | edge | probe | a candidate cash qname with zero filed years | T1.3 runs | it is reported at zero and excluded from W5's proposal | prevents an aspirational contract |
| W1-R01 | regression | reader | the probe output | SBC is read | no line frames `sbc/NOP` as a correction to `b` | R-44.4 |
| W1-R02 | regression | reader | `docs/…contract.md` §1 | it is read | no statutory-fallback sentence remains | R-41.3 |

**Automation:** manual/diagnostic (network `#[ignore]`), plus the default suite as a no-regression check.
**Commands:**
```
cd apps/windows/src-tauri
cargo test --lib probe_pinned_cohort_pretax_supply -- --ignored --nocapture
cargo test --lib probe_sales_to_capital_conditioning -- --ignored --nocapture
cargo test --lib
rustfmt src/valuation_probes.rs
```
**Expected counts:** `566 passed / 4 failed / 27 ignored` (one new `#[ignore]`d probe; the four failures by name, unchanged).
**Evidence of pass:** the three tables; the pre-registration with its date and base commit; `git status` showing only the intended paths plus the unstaged high-signal fixture.
**Documentation deliverable:** `docs/valuation-economic-contract.md` §1, §10, §14.
**Done when:** T1.2's pre-registration exists and can be falsified in both directions, and T1.4 returns exactly one of its three answers.

---

### Wave 2 — Pin the refusal ordering W4's argument rests on

| field | value |
|---|---|
| **id** | `wave-2` |
| **depends_on** | `[]` |
| **Continuity** | new chain |
| **Scope** | `apps/windows/src-tauri/valuation-core/tests/features/intrinsic-value.feature`, `…/features/manifest.toml`, `valuation-core/src/projection.rs` (doc comments only) |
| **Value posture** | **Value-neutral.** Three rows describing behaviour that is already true; a red row here means the Core does not do what the plan says it does. |

**Why first, and why alone.** W4's inertness argument is *"an absent base refuses `not_reported`, which is a different reason from `estimator_unavailable`, and the base check comes first."* No row currently pins that precedence: `base-cash-flow-absent` has `roc = 2500`, and `return-absent` has `base = 100.00`. **No row has both absent.** Landing the pin before the wave that leans on it is the R-30.5 sequencing rule (build the gate, then move under it).

#### Rows to add — `intrinsic-value.feature`, existing `Scenario Outline: Intrinsic Value from a continuously fading growth path`

| case | base | g0 | g_inf | fade | roc | wacc | value | outcome | reason |
|---|---|---|---|---|---|---|---|---|---|
| `base-and-return-both-absent` | `ABSENT` | `1500` | `300` | `0.20` | `ABSENT` | `800` | `ABSENT` | `refused` | `not_reported` |
| `return-on-capital-negative` | `100.00` | `1500` | `300` | `0.20` | `-500` | `800` | `ABSENT` | `refused` | `out_of_policy_range` |
| `high-return-flat-path` | `100.00` | `300` | `300` | `0.20` | `8150` | `800` | `1926.38` | `resolved` | `ABSENT` |

Column values are complete; the table is 10 columns and every cell above is supplied, so `every_examples_row_is_rectangular` and `absence_is_spelled_only_with_the_reserved_token` both hold. No step definition changes: `given_base_cash_flow` / `given_return_on_capital` route every cell through `observed(…)`, which already handles `ABSENT` (`cucumber.rs:240-266`).

`1926.38` is derived from the closed form the table's own `flat-path` row uses: with `g0 = g_inf`, `V = base × (1 − g_inf/r)/(w − g_inf)` = `100 × (1 − 300/8150)/0.05 = 1926.3803…`. The same formula reproduces the committed `flat-path` row exactly (`100 × (1 − 300/1200)/0.05 = 1500.00`), which is why it is safe to state here rather than defer to the builder.

**Why these three and not a new outline.** I looked at `intrinsic-value.feature`'s table and it absorbs all three: every column already exists, every quantity already means the same thing, and all three are the same behaviour — the integral and its guards — at inputs the table has not reached. `residual-income.feature` was rejected (different quantities, per its own manifest entry), and `valuation-posterior.feature` starts from a firm value. **No `manifest.toml` `[[outline]]` entry is created.** The existing entry's `covers` string gains one clause: *"…and the precedence of an absent base over an absent return."*

#### Invariants

- I2.1 `MAX_ABSOLUTE_Z` is not touched; no threshold in `numerics.rs` moves.
- I2.2 No existing row is edited, reordered or removed.
- I2.3 `tests/schema.rs`'s six checks stay green with no manifest `[[outline]]` added.

#### BDD scenarios (the rows are the tests; these are the mutations that make them load-bearing)

| id | type | given | when | then | isolating mutation that turns it red |
|---|---|---|---|---|---|
| W2-P01 | positive | `high-return-flat-path` | the row runs | `1926.38` within 1 cent | insert `let return_on_capital = return_on_capital.min(2_500.0);` in `unit_value` — an R-44.5-style "reasonable bounds" clamp. **Only this row moves**; `flat-path`, `high-return-compounder` and the rest are all ≤ 2500 |
| W2-N01 | negative | `base-and-return-both-absent` | the row runs | `refused / not_reported` | move the `return_on_capital_bps.value()` `let else` in `projection.rs` **above** the base/growth/discount `let else` — the row reads `estimator_unavailable`. Only this row can see it |
| W2-N02 | negative | `return-on-capital-negative` | the row runs | `refused / out_of_policy_range` | change `if return_on_capital <= 0.0` to `if return_on_capital == 0.0` — `return-on-capital-zero` stays green, this row resolves. Isolated |
| W2-R01 | regression | the manifest | `cargo test -p valuation-core --test schema` | green with no new `[[outline]]` | adding a new outline without a manifest entry fails `every_outline_is_justified_in_the_manifest` |
| W2-E01 | edge | the table | `schema.rs` runs | rectangular, unique cases, `ABSENT` only | writing `-` or `n/a` in any new cell fails `absence_is_spelled_only_with_the_reserved_token` |

**Mutations must be applied and reverted one at a time (R-8.4). A combined mutation proves nothing.** The report must say "isolated" for each.

**Commands:**
```
cd apps/windows/src-tauri
cargo test -p valuation-core                 # cucumber, harness = false
cargo test -p valuation-core --test schema
cargo test -p valuation-core --lib
cargo test --lib
```
**Expected counts:** `cargo test --lib` unchanged at **566 / 4 / 26** — cucumber rows are not `#[test]`s in the shell crate. `valuation-core`'s cucumber scenario count rises by exactly 3 from the count measured at wave start (R-9.3: baseline your own tree).
**Evidence of pass:** the three rows green; each of W2-P01/N01/N02 shown red under its own isolated mutation and green again after revert; `schema.rs` green.
**Documentation deliverable:** the `# Rows worth reading as a set:` comment block in `intrinsic-value.feature` gains a paragraph for each row — in particular, `base-and-return-both-absent` must say **why** it exists: it is the precedence the NOPAT base change's value-neutrality argument depends on.
**Done when:** three rows green, three isolated mutations recorded, `covers` updated, no new outline.

---

### Wave 3 — Sweep for robust centres taken along time rather than across a population

| field | value |
|---|---|
| **id** | `wave-3` |
| **depends_on** | `[]` |
| **Continuity** | new chain |
| **Scope** | `docs/valuation-aggregation-audit.md`, `docs/valuation-economic-contract.md` §14. **Read-only over source.** |
| **Value posture** | **Value-neutral.** No source file changes. |

R-46.2: `standardize` trims a cross-section; run along a time axis it deletes the present and keeps the past. `PG gross roic` trims 2022–2025; `COF gross roic` trims 2024–2025; `OMC oper`'s n=12 ends in 2020.

**Sweep and report only. Do not fix. Do not change `MAX_ABSOLUTE_Z`.**

#### Tasks

**T3.1 — Enumerate every call site.** `robust_centre`, `robust_mean`, `standardize`, and — because R-46.2's lesson is about *any* order statistic on an axis — naked medians and `sorted[len/2]`. Starting set found while planning, to be confirmed and extended, not trusted:

| site | axis | note |
|---|---|---|
| `valuation_core_adapter.rs:660` `growth_posterior` | **time, per issuer** — `robust_centre(&self.annual_revenue_growth())` | this is the trailing growth channel that supplies `g0` to every published value. The R-46.2 defect is **already live here.** |
| `valuation_core_adapter.rs:335` pooled growth centre | mixed — pooled across issuers *and* years | classify honestly; it is not obviously either |
| `driver_resolution.rs:244` `rates[rates.len() / 2]` | **time** | LD-15's naked order statistic; already registered, **not fixed here** |
| `valuation_probes.rs:570-573, 772-775, 1495` | time, per issuer | diagnostic only |
| `valuation_probes.rs:1624-1712, 2162` | cross-section | the correct use; report it as such so the doc shows the contrast |

**T3.2 — For each site, record four facts:** what population the sample is drawn from; whether the axis is time or cross-section; whether the result reaches a published value; and — where the axis is time — whether a *count* of retained observations is currently being read as evidence that the centre is current.

**T3.3 — The retained-count warning, generalised.** Write down, once, in `docs/valuation-aggregation-audit.md`: **a count of retained years is not evidence that a centre is current.** Then grep this plan's own acceptance criteria, `valuation_probes.rs`'s legends, and `docs/roic-*.md` for any criterion that leans on a year count, and list them.

**T3.4 — Register the latent defect.** New row in `docs/valuation-economic-contract.md` §14, in LD-6's shape: *trigger* — any wave that promotes a time-axis robust centre to a published value, **starting with wave-6**; *detector* — name it or say plainly that none exists. If the honest answer for the `growth_posterior` site is "the detector is that the centre and the latest kept year are both printed and nobody compares them", say that.

#### Invariants
- I3.1 Zero source files change. `git diff --stat` shows only `docs/`.
- I3.2 `MAX_ABSOLUTE_Z` is `3.0` before and after.
- I3.3 No fix is proposed for a site nobody has looked at. Sites are *classified*, not remedied.

#### BDD scenarios

| id | type | given | when | then | notes |
|---|---|---|---|---|---|
| W3-P01 | positive | the codebase | the sweep runs | every `robust_*` / `standardize` / naked-median site is listed with its axis and whether it reaches a published value | completeness is the deliverable |
| W3-P02 | positive | `growth_posterior` | it is classified | it is recorded as **time axis, reaches published value** | the sweep's most consequential row |
| W3-N01 | negative | a site whose axis is genuinely ambiguous (`:335`) | it is classified | it is recorded as **ambiguous**, not forced into a bucket | R-40.1: do not assert a mechanism from reading |
| W3-N02 | negative | a site with a known defect (LD-15) | it is found | it is cross-referenced to its existing register entry and **not fixed** | scope |
| W3-E01 | edge | a site inside `#[cfg(test)]` | it is found | it is listed and marked diagnostic-only | the contrast is part of the finding |
| W3-R01 | regression | `numerics.rs` | after the wave | `MAX_ABSOLUTE_Z == 3.0`, `standardize`'s `n < 3` refusal intact | no ground gained by weakening |

**Automation:** manual survey + `cargo test --lib` as a no-regression check.
**Commands:**
```
rg -n "robust_centre|robust_mean|standardize\(" apps/windows/src-tauri/src apps/windows/src-tauri/valuation-core/src
rg -n "len\(\) / 2\]|\.sort" apps/windows/src-tauri/src
cd apps/windows/src-tauri && cargo test --lib
```
**Expected counts:** **566 / 4 / 26**, unchanged.
**Evidence of pass:** the classified site table; the §14 row with trigger and detector (or an honest "none"); `git diff --stat` showing `docs/` only.
**Documentation deliverable:** `docs/valuation-aggregation-audit.md` (new section), `docs/valuation-economic-contract.md` §14.
**Done when:** every site is classified, the retained-count warning is written once and cross-referenced, and no source file changed.

---

### Wave 4 — The base: NOPAT into the slot FR-28 requires it for

| field | value |
|---|---|
| **id** | `wave-4` |
| **depends_on** | `[wave-1, wave-2]` |
| **Continuity** | `same_session` preferred with wave-1 (it consumes wave-1's pre-registration and its measurement judgement); acceptable as `new_session` if the pre-registration is read in full |
| **Scope** | see files table |
| **Value posture** | **Value-neutral, provably.** Zero published cents exist before or after (the Core has no production consumer). The falsifiable claim is that **all 18 `estimator_unavailable` rows keep their reason**; MH and BWMN are vacuous. Proof: `published_value_regression_gate` green **and** `published_value_regression_gate_cohort.json` absent from `git status`. |

**Gate on entry.** If wave-1's T1.1 shows any of the 18 dropping below 3 pretax years, **this wave stops and returns to Juan.** Re-blessing to absorb a reason change is forbidden (R-38.5) unless the flip was named per issuer in wave-1's pre-registration, written before the numbers.

#### Files

| path | change |
|---|---|
| `src/valuation_baseline.rs` | `DriverAnnual` gains `pub(crate) pretax: Option<f64>` with `#[serde(default)]` so `baseline_driver_data_2026-07-30.json` still parses |
| `src/valuation_fixture_capture.rs` | `deep_driver_year_row` emits `"pretax": point.pretax_income_dollars` (explicit null, never a default); **new `#[ignore]`d `enrich_the_deep_driver_fixture_with_pretax_income`** |
| `tests/fixtures/valuation/core_driver_data_deep.json` | one key added per row; **every existing key byte-identical**; no row added or removed |
| `src/valuation_core_adapter.rs` | `IssuerAnnual` gains `pub pretax_income: f64`; `fn free_cash_flow` → `fn nopat` on the base path; `base_cash_flow` provenance `"free_cash_flow"` → `"nopat"`; `after_tax_fcff` retained for the probe but no longer reachable from `base_cash_flow` |
| `src/valuation_core_measurement.rs` | `issuer_annual` gains `pretax_income: row.pretax?` (drops the year on absence, mirroring `marginal_tax_bps?`) |
| `valuation-core/src/projection.rs` | doc comment only: `base_cash_flow` is earnings **before** growth reinvestment (NOPAT), citing FR-28 and R-30.1 |
| `docs/valuation-economic-contract.md` | §1, §3, §10, §14 |

#### Tasks

**T4.1 — Surgical fixture enrichment.**
`enrich_the_deep_driver_fixture_with_pretax_income`, `#[ignore = "reaches the SEC network and rewrites a fixture"]`: read the existing fixture; for each `(symbol, year)` **already present**, fetch and insert `"pretax"`; write back. It must **not** add or remove a symbol, add or remove a year, or alter any existing key.
*Acceptance, and it is a proof rather than a claim:* after the run,
```
python - <<'PY'
import json,subprocess
new=json.loads(open('tests/fixtures/valuation/core_driver_data_deep.json').read())
old=json.loads(subprocess.run(['git','show','HEAD:apps/windows/src-tauri/tests/fixtures/valuation/core_driver_data_deep.json'],capture_output=True,text=True).stdout)
for s in new['rows']:
    assert s in old['rows']
    assert [y['year'] for y in new['rows'][s]]==[y['year'] for y in old['rows'][s]], s
    for a,b in zip(new['rows'][s],old['rows'][s]):
        assert {k:v for k,v in a.items() if k!='pretax'}==b, (s,a['year'])
assert set(new['rows'])==set(old['rows'])
print('only pretax added:', sum(1 for s in new['rows'] for y in new['rows'][s]))
PY
```
must print and exit 0. `source` string updated to record the enrichment, and that is the only other permitted change.
*Also record:* per issuer, `years_with_pretax` — and **compare it to wave-1's prediction. Any difference is a stop.**

**T4.2 — Carry pretax through the types.** `DriverAnnual.pretax: Option<f64>`; `IssuerAnnual.pretax_income: f64`; `issuer_annual` uses `?`. **No `Default`, no `unwrap_or(0.0)`, no fabricated zero.**

**T4.3 — The base becomes NOPAT.**
```
nopat(year) = (pretax_income + interest_expense) * (1 - marginal_tax_bps/10_000)
```
Identical to `docs/valuation-economic-contract.md` §1 and to `valuation_probes.rs:339`, so the workspace has one definition of NOPAT, not two. Interest enters **signed** (post-W2b convention); a net-interest-income filer's negative interest correctly removes income `pretax` already contains.
`base_cash_flow` fits its trend line over `nopat()` instead of `free_cash_flow()`; the `LEVEL_WINDOW_YEARS = 5` window, `MIN_ANNUAL_OBSERVATIONS = 3` floor, residual-variance width and `UncertaintyBasis` are unchanged. **No constant moves.**

**T4.4 — Make FCFF unrepresentable in the slot (R-30.1).** `base_cash_flow` no longer calls `after_tax_fcff` even transitively; its provenance source is `"nopat"`; and a named test asserts the numeric distinction (T4.6). The alternative — a Core newtype — is recorded in §1.3 with its cost and is **not** taken.

**T4.5 — Prove inertness against the pre-registration.** Run the gate; run `core_versus_current_engine_on_the_pinned_cohort` and diff the 20 outcomes against wave-1's table. Report the 18 as the falsifiable set and MH/BWMN as vacuous, by name.

**T4.6 — Tests.**

| test | file | asserts | isolating mutation that turns it red |
|---|---|---|---|
| `the_base_is_earnings_before_reinvestment_not_free_cash_flow` | `valuation_core_adapter.rs` | on one hand-built issuer with known `pretax/interest/tax/ocf/capex`, the base level equals the NOPAT trend level and **differs** from the FCFF trend level by the known reinvestment | revert `base_cash_flow` to call `free_cash_flow()` |
| `the_base_slot_names_the_quantity_it_carries` | `valuation_core_adapter.rs` | `base_cash_flow(&frame).provenance()` source is `"nopat"` | restore `"free_cash_flow"` |
| `issuer_annual_drops_a_year_with_no_filed_pretax_income` | `valuation_core_measurement.rs` | a `DriverAnnual` with `pretax: None` yields `None` | `row.pretax.unwrap_or(0.0)` |
| `deep_driver_year_row_never_fabricates_pretax_income` | `valuation_fixture_capture.rs` | the emitted row's `pretax` is JSON `null` when the point has none | `unwrap_or(0.0)` in the emitter |
| `published_value_regression_gate` | existing | 20 pinned outcomes unchanged | any of the above, or an unmeasured pretax hole |

One assert per test. The reinvestment-difference test needs two facts (NOPAT level *and* not-FCFF level) — express it as one assert over a collected mismatch list, the pattern `published_value_regression_gate` and `every_operating_issuer_in_the_pinned_cohort_refuses…` already use.

**Fixture-parse hazard, registered.** `DriverAnnual.interest` is `f64`, not `Option<f64>`, while the emitter can write `null` (LD-13's residual). The current corpus has zero interest nulls, so this is latent — but the enrichment must not introduce one. Detector: the parse panics loudly. Do **not** "fix" it by widening the type in this wave.

#### Invariants

- I4.1 `published_value_regression_gate` green **and** `git status` does not list `published_value_regression_gate_cohort.json`. This pair is the value-neutrality proof; transcribed numbers are not.
- I4.2 The four known failures, by name, unchanged in state.
- I4.3 No threshold, no constant, no refusal path relaxed. `MIN_ANNUAL_OBSERVATIONS`, `LEVEL_WINDOW_YEARS`, `MAX_ABSOLUTE_Z` unchanged.
- I4.4 `valuation-core/Cargo.toml` `[dependencies]` still empty (FR-1); street unreachable from the value function (FR-35).
- I4.5 No ticker appears in any conditional anywhere.
- I4.6 `high_signal_screener_observation_2026-08-02.json` unstaged.

#### BDD scenarios

| id | type | actor | given | when | then | notes |
|---|---|---|---|---|---|---|
| W4-P01 | positive | adapter | an issuer with pretax, interest and a filed marginal rate for ≥3 years | the base is resolved | it is the NOPAT trend level | the wave's purpose |
| W4-P02 | positive | gate | the enriched fixture | `cargo test --lib` | all 18 keep `estimator_unavailable` | falsifiable population |
| W4-N01 | negative | adapter | a year with no filed pretax | evidence is assembled | the year is dropped, never zero-filled | absence ≠ zero |
| W4-N02 | negative | adapter | an issuer left with <3 usable years | the base is resolved | `Observation::absent(InsufficientObservations)` → gate reads `not_reported` → **stop** unless pre-registered | the failure mode named in advance |
| W4-N03 | negative | emitter | an `FcfPoint` with no pretax | the row is rendered | `"pretax": null` | LD-13's rule extended |
| W4-E01 | edge | adapter | a net-interest-**income** filer (negative signed interest) | NOPAT is computed | interest is *subtracted*, not `.abs()`-ed back in | R-7.3 / LD-1; an `.abs()` here would silently re-create the defect Wave 2b removed |
| W4-E02 | edge | adapter | an issuer whose NOPAT is negative in some years | the base is resolved | the level is whatever the fit says; **no floor, no clamp** | a floor is a fabricated measurement |
| W4-E03 | edge | gate | MH and BWMN | the gate runs | still `not_reported`; the report states this is **vacuous**, not confirmation | R-8.4 masked pin, pre-empted |
| W4-R01 | regression | fixture | the enriched corpus | the diff check runs | only `"pretax"` keys added; `source` is the only other change | prevents a re-capture masquerading as an enrichment |
| W4-R02 | regression | corpus | the enriched corpus | it is audited | still 274 issuer-years, 179 at `marginal_tax_bps == 2100` | LD-17 is out of scope and must not be silently "improved" |

**Automation:** unit + the whole-cohort gate; the enrichment is manual/network.
**Commands:**
```
cd apps/windows/src-tauri
cargo test --lib enrich_the_deep_driver_fixture_with_pretax_income -- --ignored --nocapture
python <the T4.1 diff proof>
cargo test --lib
cargo test --lib core_versus_current_engine_on_the_pinned_cohort -- --ignored --nocapture
cargo test -p valuation-core
rustfmt src/valuation_core_adapter.rs src/valuation_core_measurement.rs src/valuation_baseline.rs src/valuation_fixture_capture.rs
git add <each path explicitly>          # never git add -A
```
**Expected counts:** `566 + 4 = 570 passed / 4 failed / 27 ignored` (four new `#[test]`s; one new `#[ignore]`d writer). Adjust against the tree you measured at wave start (R-9.3), and quote which tree.
**Evidence of pass:** the diff proof output; the 20-row before/after table against wave-1's prediction; gate green with the golden fixture absent from `git status`; each of the four new tests shown red under its own **isolated** mutation.
**Documentation deliverable:** `docs/valuation-economic-contract.md` — §1 (NOPAT is now the Core's base, with the call site), §3 (`Reinvestment = NOPAT − FCFF` now has both terms on the same footing in the adapter), §10 (the sequencing paragraph updated: the base half has landed; the charge half has not; the model is inert in between **because `r` is absent**, and the register says what re-arms it), §14 (LD-17's status restated: the fabricated marginal rates are now inputs to a computed-but-unpublished NOPAT, and become load-bearing the day wave-6 publishes — **trigger** = wave-6, **detector** = the gate must be pair-measured, not re-blessed).
**Done when:** gate green, golden untouched, all 20 outcomes match wave-1's prediction exactly, and the diff proof shows only `pretax` added.

---

### Wave 5 — The cash driver as a composition, and the marginal-rate supply hole

| field | value |
|---|---|
| **id** | `wave-5` |
| **depends_on** | `[wave-1]` |
| **Continuity** | `same_session` preferred with wave-1 (consumes T1.3 and T1.4 measurements); `new_session` acceptable if both tables are read in full |
| **Scope** | contract, generator output (both platforms), `edgar.rs`, `dcf_model.rs`, probe, fixtures, docs |
| **Value posture** | **Value-neutral.** The new driver has no consumer until wave-6. Expect two new dead-code warnings — **do not `#[allow(dead_code)]` them** (R-11.3: the warning is the honest record that the API is built and unwired). Proof: gate green, golden untouched. |

**Why this is *not* the W2a hazard (R-10.3).** W2a was unsafe alone because it shipped a contract *declaring a convention the code did not honour*. Here the contract gains a driver **and** `edgar.rs` implements it in the same wave. The generated files, the fingerprint and the extraction agree from the first commit. Nothing is armed.

#### Tasks

**T5.1 — Contract: `operatingCash` as a composition, not a selection.**
R-45.2: `select_one_equivalent` **selects**, so a securities qname would *replace* cash rather than add to it. The shape is `extract_total_debt`'s (`edgar.rs:705-744`): **parts sum; a reported aggregate supersedes the sum of its parts and carries its own provenance.**

```
cashComponents      : operation "sum_disjoint_components"   (parts: cash, short-term investments)
cashAndSecurities   : operation "select_one_equivalent"     (the aggregate concept)
```
The exact qname lists come from **wave-1 T1.3's measured coverage**. A qname with zero filed years does not enter the contract. `CashCashEquivalentsAndShortTermInvestments` is the aggregate; `CashAndCashEquivalentsAtCarryingValue` is a part. **The ordering rule is fixed here, before the numbers: aggregate supersedes parts; parts sum; a year with neither is absent, never zero.**

**T5.2 — Conditionally, the marginal-rate qnames (R-46.3).** Only if wave-1 T1.4 returned answer (i). Then `marginalTaxReference` gains the measured qnames in the **same** fingerprint bump. If T1.4 returned (ii) or (iii), the contract is untouched on this point and T5.7 registers AMZN's refusal instead.

**T5.3 — Fingerprint `/9` → `/10`, once, for whatever T5.1 and T5.2 together contain.** Two waves each bumping to `/10` is a collision; that is why the two changes share this wave.

**T5.4 — Regenerate both targets.**
```
pwsh -File scripts/generate-sec-driver-normalization-policy.ps1
pwsh -File scripts/generate-sec-driver-normalization-policy.ps1 -Check    # must be clean
```
The Kotlin file gains the constants inertly (R-3.1/R-3.2: the contract and the generator do **not** narrow to Rust; Android reads by named field). No Kotlin behaviour change, no Gradle run.

**T5.5 — `extract_operating_cash` in `edgar.rs`**, modelled on `extract_total_debt`, with composed `AnnualProvenance`. Wire onto `FcfPoint` via a `with_…` setter alongside `with_return_on_capital_inputs`. **Do not use `.first()` on `provenance.sources` for this composed driver** — LD-16 names that as quietly wrong for compositions; take the max `end` as `extract_total_debt` does.

**T5.6 — Point the probe at the real driver.** Replace the probe-local `CASH_AND_MARKETABLE_SECURITIES` (`valuation_probes.rs:426-435`) with the generated `policy::` constants, and re-run `probe_sales_to_capital_conditioning`. **Report the change in per-issuer year coverage against R-45.2's numbers** (COF 16/16 dropped, SLB 14→3, OMC 17→12, PG 14→10, MSFT 16→15, DVN 10→9) — a composition should recover years a selection lost, and the count is the evidence that it did. **Adopt no estimator, choose no window.**

**T5.7 — AMZN, honestly.** Whichever of T1.4's three answers holds, write it down with a trigger and a detector. If the answer is (iii) — nothing filed — then **AMZN refuses until the data exists**, that is a §14 register row, and wave-6's pre-registration must predict an AMZN refusal rather than a value. Under no answer is a statutory rate substituted (R-41.3, R-46.3).

#### Files

| path | change |
|---|---|
| `shared/contracts/sec-driver-normalization.json` | new driver(s); fingerprint `/10`; conditional `marginalTaxReference` qnames |
| `shared/contracts/sec-driver-normalization-fixtures.json` | `policyFingerprint` → `sec-driver-normalization/10` |
| `apps/windows/src-tauri/src/sec_driver_normalization_policy_generated.rs` | regenerated |
| `apps/android/core/src/main/kotlin/com/discountscreener/core/engine/SecDriverNormalizationPolicyGenerated.kt` | regenerated, inert |
| `apps/windows/src-tauri/src/sec_normalization.rs:346` | assertion → `/10` |
| `apps/windows/src-tauri/src/edgar.rs` | `extract_operating_cash` |
| `apps/windows/src-tauri/src/dcf_model.rs` | `FcfPoint` field + setter |
| `apps/windows/src-tauri/src/valuation_probes.rs` | probe-local constant removed |
| `docs/valuation-economic-contract.md` | §2, §14 |

#### Invariants
- I5.1 `-Check` clean: committed generated output equals generated output, both targets.
- I5.2 Gate green; golden untouched; the four known failures unchanged.
- I5.3 No `#[allow(dead_code)]` added.
- I5.4 A year with neither cash concept filed is **absent** — never zero, never carried forward.
- I5.5 No qname enters the contract without measured filed coverage from wave-1.

#### BDD scenarios

| id | type | given | when | then | isolating mutation |
|---|---|---|---|---|---|
| W5-P01 | positive | a year filing cash **and** short-term investments separately | `extract_operating_cash` runs | the two **sum** | replace the sum with last-wins selection |
| W5-P02 | positive | a year filing the aggregate concept **and** both parts | it runs | the aggregate supersedes; provenance is the aggregate's | delete the aggregate-override loop |
| W5-P03 | positive | the contract at `/10` | `-Check` runs | clean, both targets | edit the generated Rust by hand |
| W5-N01 | negative | a year filing neither concept | it runs | the year is **absent** | `unwrap_or(0.0)` |
| W5-N02 | negative | a fact in a non-USD unit | it runs | rejected at the normalization boundary | widen the unit check |
| W5-E01 | edge | a composed year | provenance is built | `end` is the max over contributors, not `.first()` | switch to `.first()` — LD-16's hazard, now live for a second composed driver |
| W5-E02 | edge | COF, a bank filing neither concept | the probe runs | 16 of 16 years dropped, printed, **no substitution** | any fallback |
| W5-R01 | regression | fingerprint | `cargo test --lib` | `sec_normalization` assertion and the fixture `policyFingerprint` both read `/10` | bump one and not the other |
| W5-R02 | regression | gate | `cargo test --lib` | 20 pinned outcomes unchanged | wiring the driver into the value path in this wave |

**Commands:**
```
pwsh -File scripts/generate-sec-driver-normalization-policy.ps1
pwsh -File scripts/generate-sec-driver-normalization-policy.ps1 -Check
cd apps/windows/src-tauri && cargo test --lib
cargo test --lib probe_sales_to_capital_conditioning -- --ignored --nocapture
rustfmt src/edgar.rs src/dcf_model.rs src/sec_normalization.rs src/valuation_probes.rs
```
`scripts/validate-contracts.ps1` also pushes into `apps/desktop` and `apps/android`; run the `-Check` step directly rather than the whole script if the Gradle leg is not available. Android remains out of scope (R-3).
**Expected counts:** `566 + 5 ≈ 571 passed / 4 failed / 26 ignored` (five new `#[test]`s; no new `#[ignore]`d test unless T5.7 adds a probe). Baseline against your own tree.
**Evidence of pass:** `-Check` clean; both generated files in the diff; the year-coverage comparison against R-45.2's six numbers; gate green with the golden absent from `git status`.
**Documentation deliverable:** `docs/valuation-economic-contract.md` §2 — the project's invested capital becomes `StockholdersEquity + TotalDebt − OperatingCash`, with R-45.2's under-netting note now closed and its error direction restated; §14 — the composed-driver `.first()` hazard (LD-16) now has a second call site, so its detector `grep -c "winning_qname_is_net_basis"` reasoning must be restated for `extract_operating_cash`; plus T5.7's AMZN row.
**Done when:** `-Check` clean, gate green, golden untouched, per-issuer year coverage reported against R-45.2, and AMZN's answer written down with a trigger and a detector.

---

### Wave 6 — The estimator. **BLOCKED. Shape only.**

| field | value |
|---|---|
| **id** | `wave-6` |
| **depends_on** | `[wave-4, wave-5]` **and three unresolved decisions that are Juan's** (H1, H2, H3 below). The `depends_on` is therefore **not satisfiable today**, and that is the honest state, not an omission. |
| **Continuity** | `same_session` with wave-4 if dispatched (it edits the same adapter surface) |
| **Value posture** | **Value-MOVING, on purpose, and it is the first wave in this effort that publishes anything.** All 20 pinned issuers change state. Requires: a per-issuer pre-registration written **before** the wave; the gate red for exactly the registered issuers with the registered outcomes; and an explicit `bless_published_value_regression_gate_cohort` run by a person. Any unregistered mover is a **stop**. |

#### Registered holes — the orchestrator fills these, not the planner and not a builder

**H1 — The window. Three parameter-free options, from R-46.4. Not chosen here.**

| option | what it is | fails when | evidence |
|---|---|---|---|
| (i) whole filed history | what Rounds 10/11 measured | the issuer's economics moved — MSFT reads 0.815 against a current 0.341 — and R-46.2's trimming can delete the present (PG trims 2022–2025; COF trims 2024–2025) | R-45.4, R-46.1, R-46.2 |
| (ii) the latest usable year | literally the source's *"at its current level"* | single-year noise — HPE's latest return is 0.007 against a centre of 0.045; the `return-below-terminal` row's behaviour on one bad filing | R-46.4 |
| (iii) whole history, **refuse when the most recent filed year was trimmed out** | a refusal rule, not an estimator; uses only the `\|z\| > 3` that already exists; composes with (i) | costs coverage; does nothing for MSFT, whose sixteen years were all retained | R-46.4 |

**No trailing-N, no half-life, no recency weight is proposed, and none may be introduced by a builder.** Any N chosen now would be chosen after seeing which issuers it flatters — R-41.5's post-hoc threshold with a new name. Note also that (iii) is a **`numerics.rs`-level behaviour with no existing Examples table**; if it is chosen, its BDD home is an open question and the manifest cost must be paid or unit tests used — flag, not resolved.

**H2 — `prod` vs `roic`.** `robust_centre(Sales/Capital) × robust_centre(NOPAT/Sales)` versus `robust_centre(NOPAT/Capital)`. Equal year by year (DuPont); they differ only in which years each centre trimmed. `gap = prod − roic` reaches **0.202 (AMZN, oper)**, **0.091 (MSFT, oper)**, 0.061 (PG) — three of the four anchors — against return levels of 0.1–0.3. **No test decides this. It is an economic choice.** (R-45.3.)

**H3 — AMZN's supply.** Resolved by wave-5 T5.7 into one of three answers. If the answer is refusal, wave-6's pre-registration predicts an AMZN refusal, and an anchor is dark. That is a fact to be stated, not a reason to fabricate a rate.

#### Shape, so the decisions land on something concrete

- `IssuerAnnual` gains `stockholders_equity: f64` and `operating_cash: f64` (both dropping the year on absence, as pretax does).
- `IssuerEvidence::return_on_capital` stops being hardcoded absent and returns a measured `Observation<f64>` in **bps**, from `robust_centre` over the chosen form and window, with `variance_of_centre()` as the width and `UncertaintyBasis::SampleVariance { observations: retained() }`. Never `variance` over the untrimmed sample (Wave 3's rule at `valuation_core_adapter.rs:645-651`).
- **The bound is the guard that already exists** (R-44.5): the Core refuses `r <= 0` and refuses terminal growth at or above the discount rate. Nothing tighter without Juan registering it as a policy with the number written down first.
- `every_operating_issuer_in_the_pinned_cohort_refuses_for_an_absent_return_on_capital` is **DELETED, not weakened** — its own doc comment orders this (`valuation_core_adapter.rs:1252-1255`). A version that filters out newly-measurable issuers is a test rewritten to keep passing.
- `a_complete_issuer_still_refuses_for_an_absent_return_on_capital` and `an_absent_return_on_capital_refuses_rather_than_being_valued_at_the_neutral_line` must be **re-pointed at an issuer whose evidence genuinely lacks the terms**, not deleted — FR-29's refusal must stay reachable and tested.

#### BDD rows, when it lands

The value-path behaviour is already covered by the existing table (R-44: the integrand does not change) plus wave-2's three rows. What wave-6 makes newly *reachable* is a measured `r` outside the table's current range, which wave-2 pre-pinned at both ends (`return-on-capital-negative`, `high-return-flat-path` at 8150 bps). **My assessment is that wave-6 needs no further Core rows** — but that assessment is made without the H1/H2 answers, and if (iii) is chosen it is wrong. Flagged, not resolved.

#### Invariants (whichever way H1/H2 go)

- I6.1 Street is not a clamp, an optimand, or an acceptance criterion. No min-WACC-as-truth, no price cap, no output clamp, no sector FCF haircut.
- I6.2 No ticker special-case.
- I6.3 No threshold moved to make a change pass; `MAX_ABSOLUTE_Z` is `3.0`.
- I6.4 Every centre through `robust_centre` / `robust_mean` (no threshold argument).
- I6.5 The chosen window and form are **stated before any issuer is scored**, with the source they come from.
- I6.6 A gate re-bless requires a per-issuer written registration **before** the wave, plus a person running the `#[ignore]`d writer.

#### Sketch scenario table (to be completed once H1/H2 resolve)

| id | type | given | when | then |
|---|---|---|---|---|
| W6-P01 | positive | an issuer with ≥3 usable capital-years | `return_on_capital` is resolved | a measured Observation in bps, width = `variance_of_centre`, sample size = `retained` |
| W6-P02 | positive | the pinned 20 | the gate runs | exactly the registered issuers move, to the registered outcomes |
| W6-N01 | negative | an issuer with <3 usable capital-years | resolved | absent → the Core refuses `estimator_unavailable` |
| W6-N02 | negative | a centre that comes out ≤ 0 | valued | `out_of_policy_range`, no floor, no clamp — pinned by wave-2's `return-on-capital-negative` |
| W6-E01 | edge | AMZN under H3's answer | valued | the registered outcome, refusal included |
| W6-E02 | edge | MSFT at ~8150 bps under (i) | valued | the retention charge keeps ~88% — pinned by wave-2's `high-return-flat-path`; **no clamp** |
| W6-R01 | regression | FR-29 | an issuer genuinely lacking the terms | still refuses `estimator_unavailable` |
| W6-R02 | regression | the golden | after re-bless | every changed row appears in the pre-registration written before the wave |

---

## 3. Cross-cutting

**Rollout.** Nothing user-visible ships in waves 1–5: the Core has no production consumer. Wave 6 is the first wave that could publish, and it is gated on Juan.

**Provenance.** Every new evidence term (`pretax`, `operating_cash`, `stockholders_equity`) enters as `Option`, drops its year on absence, and travels with `AnnualProvenance`. `Observation<T>` remains a sum type over `Measured`/`Absent` — no `Default`, no `unwrap_or(0.0)`, anywhere.

**Cohort discipline (R-8.2 / R-10.1 / R-13.1, three prior failures of the same shape).** Three populations are in play and must never be conflated in any report:

| population | n | contains |
|---|---|---|
| pinned gate cohort (`published_value_regression_gate_cohort.json`) | 20 | AAPL AMZN MSFT T + 16 small/mid caps. **AMZN and MSFT are the only anchors in it.** |
| `PROBE_COHORT` (`valuation_probes.rs:105`) | 28 | all four anchors; overlap with the pinned 20 is `{T, MSFT, AMZN}` |
| high-signal screener gate cohort | 26 | a third population; not touched by this branch |

**PG and GOOGL are invisible to the gate.** Any claim about anchor behaviour must name which cohort produced it.

**Predicted P0s, absorbed or named.**
1. *(absorbed into wave-1)* Pretax supply on the pinned 20 is unmeasured; without it wave-4's inertness is a hope.
2. *(absorbed into wave-4)* A naive fixture re-capture would rewrite 179 fabricated marginal rates to nulls and flip refusal reasons. The surgical enrichment plus the diff proof is the mitigation.
3. *(named, not absorbed)* `growth_posterior` already runs `robust_centre` along a time axis and already reaches a published value. R-46.2 applies to it **today**, independently of this branch. Wave-3 classifies it; **nobody has planned a fix**, and this plan does not propose one.
4. *(named)* LD-17's fabricated marginal rates become load-bearing for a published value the day wave-6 lands. Wave-4's §14 edit gives it a trigger and a detector; the corpus repair remains out of scope.
5. *(named)* If H1 resolves to option (iii), it is a `numerics.rs` behaviour with no Examples table, and the FR-44 cost is unpriced in this plan.

**Out of scope, restated:** AMZN policy/16; Android parity (Windows first; the generator still emits both targets); the ROIC fixture; LD-2..LD-11, LD-14, LD-16, LD-17-as-corpus-repair; the two R-35.5 findings; `worktree-agent-a19c1b1e4036e2768`; `measure-guard-rules`. None of the four known failures is to be fixed.

---

## 4. Where I am uncertain — holes for you to fill, not guesses

1. **BWMN's `not_reported` cause is not directly confirmed.** Derived that it cannot be the base (6 annuals ≥ 3), and derived that it cannot leave `not_reported` under wave-4 regardless. Settle with `cargo test --lib core_versus_current_engine_on_the_pinned_cohort -- --ignored --nocapture` if you want it nailed down. The design does not depend on the answer.
2. **The cash equivalence class's exact qnames are unmeasured.** I listed six candidates as a *measurement* target, not a proposal. If wave-1 T1.3 shows the parts are not filed separately by this universe, wave-5's composition may recover fewer years than hoped — and R-45.2's under-netting residual persists. That would be a finding, not a failure.
3. **Whether the `E`-slot type discipline belongs in the Core or the adapter.** I chose the adapter and stated the cost of the alternative. A reviewer who reads R-30.1's *"a distinct type"* literally will want the Core newtype; that is a defensible reading and it is your call, not a builder's.
4. **Wave-2's `high-return-flat-path` value `1926.38`** is derived from the closed form and cross-checks exactly against the committed `flat-path` row. It has not been executed. If the row comes out different, the builder must report the discrepancy rather than adjust the expected value — a mismatch would mean the flat-path identity does not hold where I claimed it does, which is worth more than the row.
5. **Cucumber scenario counts** are not stated as absolutes because I did not run `cargo test -p valuation-core`. Every wave baselines its own tree (R-9.3) and quotes which row it is in.
6. **The `depends_on` for wave-5 on wave-1 is partly file ownership** (`valuation_probes.rs`) rather than pure logic. If you are willing to accept a rebase, wave-5 could start from wave-1's measurements alone and run concurrently. I declared the edge rather than claiming an independence I would have had to qualify.

---

## TL;DR

Verified the sequencing claim on `r10`: **true in cents, incomplete in reasons.** All 20 pinned issuers refuse, but 18 at `estimator_unavailable` and **2 (MH, BWMN) at `not_reported`** — and the Core has no production consumer at all, so no published cent can move before the estimator. The real cost of the base change is not code: `IssuerAnnual`, `DriverAnnual` and `core_driver_data_deep.json` carry **no pretax income**, so NOPAT needs a **surgical, diff-proven** fixture enrichment; a naive re-capture would rewrite 179 fabricated marginal rates and flip refusal reasons. Six waves: **W1** measure supply (pretax, cash qnames, AMZN's rate hole) + SBC relabel + doc corrections, **W2** pin the refusal ordering as three rows in the existing `intrinsic-value.feature` table (no new outline), **W3** sweep for time-axis robust centres (report only — `growth_posterior` already has R-46.2's defect and already publishes), **W4** the NOPAT base, **W5** the cash composition + fingerprint `/9→/10`, **W6** the estimator — **blocked** on three decisions that are Juan's (window ×3 options, `prod` vs `roic`, AMZN's supply). Six honest holes listed at the end rather than guessed.
