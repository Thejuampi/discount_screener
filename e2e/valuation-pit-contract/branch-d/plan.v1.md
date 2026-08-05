# plan.v1 — sales-to-capital branch (`r10`)

Supersedes `plan.v0.md` (`433ffd4`). Written by the Orchestrator, not re-delegated to the planner.

**What changed from v0, and why.** Both plan reviews landed (Sensei `revise`, four P0s; Advisor
`approve`, three P1s) and they contradicted each other on a fact. Rounds 12 and 13 then returned. The
revisions are ruled at **R-49.4** (what is absorbed, what is rejected, and why) and **R-50**. In one
line each:

| # | change | source |
|---|---|---|
| 1 | `growth_posterior` has **no production consumer** — the trimming defect is **scheduled, not live**. v0 asserted the opposite in §3 and W3-P02. | R-49.1 |
| 2 | **W1 is split** into W1a (the supply audits W4/W5 stand on) and W1b (SBC + docs, no dependents). | Sensei P0-D |
| 3 | **W4 does not proceed as v0 scoped it.** 26 of 33 audited fixture tax rows carry a rate SEC never filed. W4 now has an entry gate and two declared branches. | R-50.6, R-50.8 |
| 4 | **W5's self-contradictory dependency is resolved**: the cash qname candidate set is registered as policy in W1a *before* its coverage is read. | Sensei P0-E |
| 5 | **The cash-composition overlap lattice is declared pair by pair** before any qname enters. Double-counted cash moves value one way only — **upward**. | Sensei P0-F |
| 6 | **W2 gains a second `r` row**, making R-49.3's unfaded-return finding a committed, checkable pair rather than a number in a ruling. | R-49.3, R-49.4 |
| 7 | **The golden file splits pinned from provisional.** Premise corrected: the defect is scheduled, so a value minted under a named open defect says so in the file. | Sensei P0-G, corrected |
| 8 | **T4.1's diff proof moves from a hand-run Python script into the `#[ignore]`d Rust test**, and the enrichment **fails closed**. | Advisor P1-1, P1-3 |
| 9 | **W6 gains H4 (the capital definition) and H5 (`r`'s fade)**, and H1 collapses into H4. | R-49.3, R-50.2 |

**Rejected, with the reason, so nobody re-proposes them silently:** Sensei's `Money`/`Rate`/`Ratio`
unit vocabulary — right argument, wrong moment; registered with a trigger in §6. Sensei's four-issuer
realized-reinvestment oracle for W6 — R-41.4 measured realized `b` negative for 14 of 21, and
restricting to the quarter where it behaves is `feedback_scope_you_cannot_get_wrong` inverted.

---

## 0. Ground check (STEP 0, every wave, before anything else)

Per R-12.1 and `feedback_verify_the_base_a_wave_stands_on`. Any failure is an immediate stop, not an
improvisation. **Read the dependency row first and check the base commit before dispatching.**

| check | expected |
|---|---|
| `git -C <worktree> log --oneline -1` | the tip of `r10` named in the dispatch brief — **`66ff5e7`** at the time of writing |
| `git -C <worktree> branch --show-current` | `r10` |
| `git -C <worktree> status --short` | at most `M apps/windows/src-tauri/tests/fixtures/valuation/high_signal_screener_observation_2026-08-02.json` — **never staged** |
| `grep -n POLICY_FINGERPRINT …/sec_driver_normalization_policy_generated.rs` | `sec-driver-normalization/9` |
| files present | `src/valuation_probes.rs`, `src/valuation_core_measurement.rs`, `valuation-core/tests/features/manifest.toml` |
| `cargo test --lib` (from `apps/windows/src-tauri`) | **`566 passed / 4 failed / 29 ignored`** at `66ff5e7`; failures exactly: `cross_platform_parity::export_random20_sp500_parity_snapshot`, `operating_valuation::tests::durable_reported_and_holdout_cohorts_recompute_in_normal_gate`, `valuation_baseline::baseline_megacap_amzn_class_not_penny_intrinsic`, `valuation_high_signal::high_signal_screener_cohort_all_members_pass` |

**Round 14 is in flight** and adds one `#[ignore]`d probe, so the ignored count becomes `30` when it
lands. **Baseline against your own tree and quote which commit you measured** (R-9.3). If a count
differs from what this plan predicts, **report the discrepancy rather than matching the number** —
Rounds 12 and 13 each caught the Orchestrator wrong about a count, and both were right to.

`.agents/` and `_bmad-output/` are untracked and absent from worktrees — read plan and rulings from
the main checkout / artifacts worktree paths.

---

## 1. Summary

### 1.1 State of the base, verified

**(a) FR-29 is gone and `r` is hardcoded absent. Confirmed.** `valuation_core_adapter.rs:681-686`:

```rust
fn return_on_capital(&self, frame: &MarketFrame) -> Observation<f64> {
    Observation::absent(AbsenceReason::ProviderUnavailable, self.provenance("invested_capital", frame))
}
```

`projection.rs:224-226` refuses `EstimatorUnavailable` on an absent return. No substitution anywhere.

**(b) The Core has no production consumer at all.** `grep -rn "valuation_core_adapter::" src/` returns
exactly two files: `valuation_core_measurement.rs` (the gate and the diagnostics) and
`valuation_probes.rs`. Nothing user-facing values through the Core, so **no published cent can move in
any wave before W6.**

**This governs `growth_posterior` too, and v0 got that wrong.** v0's §3 and W3-P02 asserted that
`growth_posterior` runs a robust centre along time *and already reaches a published value*. Advisor
grepped it: **one caller, inside the adapter, plus its own unit tests.** That is the same population
(b) establishes two paragraphs above, so v0 contradicted itself. R-46.2's defect at that site is
**scheduled, not live** — and it is scheduled for W6, the same wave that mints the first goldens.
That is a *better* statement of the risk than the one Sensei was given and it makes "sweep and report,
fix nothing" more clearly right, not less. (R-49.1.)

**(c) The twenty pinned issuers do not all refuse for the same reason.**

| | count | issuers |
|---|---|---|
| `evidence / estimator_unavailable` | **18** | VRRM T ADMA INOD VICR AMSC AMZN AAPL IDCC FIGS CALX MSFT MIR ROCK HURN VRT INVA APP |
| `evidence / not_reported` | **2** | **MH, BWMN** |

Refusal ordering in `projection.rs:206-226`: base/growth/discount absent → `NotReported`; base
non-finite → `OutOfPolicyRange`; **then** return absent → `EstimatorUnavailable`. So the 18 are the
**falsifiable population**; MH (3 annuals → 2 growth transitions; `standardize` refuses `n < 3`) and
BWMN refuse *upstream* of the return check and **cannot be moved by a base change**. Their two green
rows are **vacuous evidence** — the R-8.4 masked-pin shape, stated in advance so nobody reads
20-of-20 as twenty independent confirmations.

**(d) The base change is not a pure code change.** `IssuerAnnual`, `DriverAnnual` and
`core_driver_data_deep.json` carry **no pretax income**, so `NOPAT = (pretax + interest)(1 − t)` is
not derivable from the committed corpus. W4 needs a **surgical, diff-proven** fixture enrichment; a
naive re-capture rewrites the whole corpus and flips refusal reasons.

**(e) And the corpus it would be enriched *from* is not faithful. This is new in v1 and it is why W4
changes shape.** Round 13 audited the 33 pre-2018 rows carrying `marginal_tax_bps = 2100` in years the
US statutory rate was 35%:

| classification | audited (n=33) | control (n=10, fixture says 3500) |
|---|---|---|
| genuinely filed at the fixture's rate | **0** | **10** |
| filed at something else (all −0.34, a loss-year line, correctly refused by the resolver) | 7 | 0 |
| **nothing filed at all** | **26** | 0 |
| not measured | 0 | 0 |

Eight of the twelve audited issuers appear in the control **at a different year** and filed there — so
"this issuer does not file it" is dead *within issuer*. And the Orchestrator then measured, without
the network: **all 274 rows carry a non-null `marginal_tax_bps`, and all 274 a non-null
`effective_tax_bps`.** Production resolves `<none>` on at least 33. **Absence is not representable in
these columns at all**, which puts the 146 post-2018 rows under the same suspicion — they are the half
where the fill cannot be caught. R-46.3's own words: *a default which is usually right is worse than an
absence precisely because it is usually right.* (R-50.5 – R-50.7.)

Round 14 audits the whole fixture and returns **P19**: the coverage cost of faithful absence. W4's
scope branches on it (§W4).

**(f) Verified as already landed, so not re-planned:** the generator `-Check` wiring exists
(`scripts/validate-contracts.ps1:6`) and the generator emits both targets from a loop over the
contract. Inherited-plan §1 is done.

### 1.2 Goal and non-goals

**Goal.** Put the branch in a state where the estimator is the *only* remaining unknown: the `E` slot
holds NOPAT **from a corpus that can say "absent"**, the operating-capital denominator is measurable
from a real contract driver, the evidence holes are named, and the economic decisions that are Juan's
are registered as decisions rather than absorbed by a default.

**Non-goals (explicit).** Choosing a window. Choosing a capital definition. Choosing `prod` vs `roic`.
Choosing a fade for `r`. Choosing a bounds check (R-44.5: the bound is the arithmetic guard that
already exists). Publishing any value. Substituting, repairing, back-filling or defaulting any tax
rate. AMZN policy/16, Android parity, the ROIC fixture, LD-2..LD-11, LD-14, LD-16, the two R-35.5
findings. Merging `worktree-agent-a19c1b1e4036e2768` or `measure-guard-rules`. Fixing any of the four
known failures.

### 1.3 Approach and key design decisions

1. **The Core does not change its economics.** `C(t) = E(t)(1 − g/r) ≡ E(t)(1 − b(t))`, and
   `NOPAT/Capital ≡ (Sales/Capital)×(NOPAT/Sales)` year by year. `intrinsic_value` is untouched except
   for documentation and the guard-pinning rows in W2.
2. **"Unrepresentable as FCFF" is enforced at the adapter, not in the Core.** A newtype in
   `valuation-core` would move a public boundary `residual_income` also publishes through, and the
   wrong input can only be *constructed* in the adapter. **Alternative recorded, not taken:** a
   Core-level `OperatingEarnings` newtype — stronger, and if a reviewer prefers it the cost is the
   Core's signature, `cucumber.rs`'s `when_intrinsic_value`, and `residual_income`'s shared boundary.
3. **Every fixture write is surgical, diff-proven *in a test*, and fails closed.** Never a re-capture.
   A proof a human pastes into a terminal once is indistinguishable six months later from a claim
   nobody checked (Advisor P1-1).
4. **A fabricated value is never repaired by substituting a better one.** The repair for an absent
   filing is **absence**. R-41.3 and R-46.3 are not re-opened, in either direction.
5. **The `|z| > 3` finding (R-46.2) is swept and reported, never fixed here**, and `MAX_ABSOLUTE_Z`
   does not move.
6. **New behaviour lands as rows in the existing `intrinsic-value.feature` Examples table.** FR-44: a
   new outline is an exception, not the norm. §5 states what was looked at and why the table absorbs it.

### 1.4 Public interface / contract changes

| surface | change | wave |
|---|---|---|
| `intrinsic-value.feature` | **4** rows added to the existing table | W2 |
| `IssuerAnnual`, `DriverAnnual` | gain `pretax_income` / `pretax: Option<f64>` | W4 |
| `IssuerEvidence::base_cash_flow` | provenance `"free_cash_flow"` → `"nopat"`; computes NOPAT | W4 |
| `core_driver_data_deep.json` | gains `"pretax"` per row; **and, under branch (a), gains `null` in the tax columns where nothing was filed** | W4 |
| `published_value_regression_gate_cohort.json` | rows gain a `provisional` marker | W4 |
| `shared/contracts/sec-driver-normalization.json` | new `operatingCash` composition; fingerprint `/9` → `/10`; conditionally `marginalTaxReference` qnames | W5 |
| both generated policy files | regenerated | W5 |

### 1.5 Assumptions and risks

- **A1 (unverified — W1a must prove).** Pretax income is filed for ≥3 of the years already in the deep
  fixture for each of the 18 sensitive issuers. If false for any, W4's inertness fails and the wave
  stops for Juan rather than re-blessing.
- **A2 (unverified — W1a must prove).** A cash / short-term-investments equivalence class with real
  filed coverage exists for this universe. R-45.2 measured only 4 of 28 filing the aggregate concept.
- **A3 (derived, low risk).** BWMN's `not_reported` originates in growth or discount, not the base
  (6 annuals ≥ `MIN_ANNUAL_OBSERVATIONS = 3`). **The design does not depend on it** — either way BWMN
  cannot leave `not_reported` under W4.
- **A4 (inferred from v0, and it must be confirmed rather than trusted — R-40.1).** `DriverAnnual`'s
  tax fields are already `Option`-shaped and `issuer_annual` already drops the year with `?`, so
  making the corpus say "absent" is a **fixture** repair and not a type repair. v0's T4.2 wrote
  `row.pretax?` as *mirroring* `marginal_tax_bps?`, which is where this comes from. **W4 confirms it
  by reading the type before writing anything.** If it is wrong, W4 gains a type change and says so.
- **R1.** A future reader treats W4's green gate as 20 confirmations. Mitigated by naming the
  falsifiable population as **18** in the pre-registration and in the wave report.
- **R2.** The anchors are only half-visible to the gate: **AMZN and MSFT are pinned; PG and GOOGL are
  not.** `PROBE_COHORT` is a different 28-name population; overlap with the pinned 20 is
  `{T, MSFT, AMZN}`. Every wave report must name which cohort each number belongs to.
- **R3 (new).** A reader takes the 146 post-2018 `2100` rows as sound because 21% is the statutory rate
  then. That is the population where the check cannot fail
  (`feedback_verify_what_an_instrument_measures`). Mitigated by Round 14 auditing the whole fixture
  rather than the falsifiable half.

---

## 2. Waves

```
W1a (supply audits)          []   ─┬─────────────► W4 (NOPAT base; scope gated on Round 14 P19) ─┐
W2  (refusal + r domain)     []   ─┘                                                             ├─► W6
W3  (time-axis sweep)        []                                                                  │   BLOCKED
W1a ──────────────────────────────────────────► W5 (cash composition)                           ─┘
W1a ──► W1b (SBC + docs; file ownership only)
```

Independent roots: **W1a, W2, W3** (cap 3, exactly filled). W1b, W4, W5 are serial. W6 is serial *and*
blocked on decisions that are Juan's.

---

### Wave 1a — Measure the evidence W4 and W5 stand on

| field | value |
|---|---|
| **id** | `wave-1a` |
| **depends_on** | `[]` |
| **Continuity** | new chain |
| **Scope** | `apps/windows/src-tauri/src/valuation_probes.rs` |
| **Value posture** | **Value-neutral by construction.** Probes are `#[ignore]`d; no production path is touched. |

**Why a wave and not a task inside W4.** `feedback_measure_a_mechanism_before_building_against_it`:
a cause derived by reading is a hypothesis. W4's whole inertness argument rests on A1, which nobody
has measured on the *pinned* cohort. R-40.1 cost this effort a near-miss on exactly this shape.

**Why split from W1b.** v0's W1 carried six tasks with no shared exit criterion, and T1.5/T1.6 have no
dependents at all — so a failure in a documentation relabel could block the wave W4 is waiting on.
Everything here has a dependent; nothing here is a doc edit.

#### Registered before any coverage number is read (this closes v0's W5 contradiction)

v0's T1.3 listed six cash qnames and called them *"a starting set for measurement, not a contract
proposal"*, while W5 then took the contract's qname list **from** T1.3's coverage. Both cannot be true:
if coverage selects the set, the set is a policy and R-41.5 requires it written down first.

**So it is written down first, here, before the probe runs:**

- **The candidate set** is exactly: `CashCashEquivalentsAndShortTermInvestments` (aggregate),
  `CashAndCashEquivalentsAtCarryingValue`, `ShortTermInvestments`, `MarketableSecuritiesCurrent`,
  `AvailableForSaleSecuritiesDebtSecuritiesCurrent`, `OtherShortTermInvestments` (parts).
- **The admission rule** is: *a candidate with zero filed years across both cohorts does not enter the
  contract.* Zero. Not "low". No other coverage threshold exists and none may be introduced.
- **No candidate may be added to the set after coverage is read.** If the measurement suggests a
  seventh qname, that is a finding for a later registration, not an edit to this list.
- **The containment lattice is declared in §W5 below**, also before the numbers.

#### Tasks

**T1a.1 — Pretax supply audit for the pinned twenty.** New `#[ignore]`d
`probe_pinned_cohort_pretax_supply`. For each of the 20 symbols in `core_driver_data_deep.json`: fetch
`fetch_fcf_history`, and **for each year already present in the fixture**, report whether
`pretax_income_dollars` is `Some`. Print per issuer: `years_in_fixture`, `years_with_pretax`,
`years_lost`, and the lost years by name.
*Done when:* the table prints for all 20 and the report states, per issuer, `years_with_pretax >= 3`
yes/no. **Chooses nothing, changes nothing.**

**T1a.2 — Write W4's pre-registration, before W4 runs.** A per-issuer table of the 20 predicted golden
`core` outcomes after W4, derived from T1a.1:
- 18 predicted `refused(evidence/estimator_unavailable)` — **the falsifiable population**;
- MH, BWMN predicted `refused(evidence/not_reported)` — **stated as vacuous**, they cannot falsify
  anything;
- any issuer T1a.1 shows dropping below 3 pretax years is named **now**, with its predicted flip, and
  W4 **stops for Juan** rather than re-blessing.
*Done when:* the table exists in the wave report with a date and the commit it was written against,
and **it can be falsified in both directions** — an unpredicted flip is a stop, and a predicted flip
that does not occur is *also* a stop.

**T1a.3 — Coverage of the registered cash candidate set.** Per issuer across `PROBE_COHORT` **and**
the pinned 20, print which of the six registered candidates resolve and for how many years.
*Done when:* a per-qname year-count table exists. Apply the admission rule as registered above; do not
invent a second one.

**T1a.4 — AMZN's marginal-rate hole (R-46.3).** For AMZN 2014–2022, report which of
`marginalTaxReference`'s five qnames appear in `companyfacts` at all, and whether any *other*
statutory-rate concept appears. Print the raw qnames found.
*Done when:* the answer is exactly one of three, stated plainly: **(i)** a qname exists and is not in
the class → W5 adds it; **(ii)** a qname exists but in an unusable unit or shape → W5 records why, no
change; **(iii)** nothing is filed → **AMZN honestly refuses until the data exists**, registered as a
latent defect with trigger and detector. **No fabrication and no statutory substitution under any of
the three.**
*Note, from R-50.6:* five of AMZN's fixture rows were being papered over by the very fill Round 13
found, so this hole is larger than R-46.3 measured. Report against the fixture's rows, not against
R-46.3's count.

#### Invariants

- I1a.1 No production file is modified. `git status` shows only `valuation_probes.rs` and the
  always-unstaged high-signal fixture.
- I1a.2 No probe writes to `core_driver_data_deep.json` or `published_value_regression_gate_cohort.json`.
- I1a.3 Every summary statistic goes through `robust_centre` / `robust_mean` (no threshold argument).
  No `sum/n`, no `sorted[len/2]`. Counts and percentages are fine.
- I1a.4 No number measured here is used to choose a window, an N, a weight, a trim, a capital
  definition or an estimator.
- I1a.5 No ticker appears in a conditional. Fixture-derived and table-driven symbol lists are data.

#### BDD scenarios

| id | type | actor | given | when | then | notes |
|---|---|---|---|---|---|---|
| W1a-P01 | positive | probe | the pinned 20 and a reachable EDGAR | T1a.1 runs | a per-issuer pretax coverage table prints, 20 rows | the wave's deliverable |
| W1a-P02 | positive | probe | T1a.1's output | T1a.2 is written | 20 predicted outcomes, 18 marked falsifiable, 2 marked vacuous | pre-registration |
| W1a-N01 | negative | probe | EDGAR unreachable or ≥1 fetch fails | T1a.1 runs | the wave reports **"not measured"** for those issuers and W4 does not proceed | a partial audit is not an audit |
| W1a-N02 | negative | probe | an issuer-year with no filed pretax | T1a.1 runs | counted as lost, never imputed, never carried forward | absence is not a zero |
| W1a-E01 | edge | probe | AMZN 2014–2022 | T1a.4 runs | one of the three named answers, with the raw qnames printed | no fourth answer exists |
| W1a-E02 | edge | probe | a registered candidate with zero filed years | T1a.3 runs | reported at zero and excluded by the **pre-registered** rule | prevents an aspirational contract |
| W1a-R01 | regression | probe | a seventh qname suggested by the numbers | T1a.3 is read | it does **not** enter the candidate set in this wave | the set was registered before the numbers |

**Commands:**
```
cd apps/windows/src-tauri
cargo test --lib probe_pinned_cohort_pretax_supply -- --ignored --nocapture
cargo test --lib probe_sales_to_capital_conditioning -- --ignored --nocapture
cargo test --lib
rustfmt src/valuation_probes.rs
```
**Expected counts:** one new `#[ignore]`d probe over the tree you measured at wave start.
**Evidence of pass:** the three tables; the pre-registration with its date and base commit;
`git status` showing only the intended paths.
**Done when:** T1a.2's pre-registration exists and is falsifiable in both directions, and T1a.4
returns exactly one of its three answers.

---

### Wave 1b — The SBC relabel and the stale documentation

| field | value |
|---|---|
| **id** | `wave-1b` |
| **depends_on** | `[wave-1a]` — **file ownership only** (`valuation_probes.rs`), not logic. Declared rather than claimed independent. |
| **Continuity** | new chain |
| **Scope** | `apps/windows/src-tauri/src/valuation_probes.rs` (legend text), `docs/valuation-economic-contract.md` |
| **Value posture** | **Value-neutral.** Legends and prose. |

**T1b.1 — Relabel the SBC column (R-44.4).** Rename the printed `sbc/NOP` column from something framed
as a *correction* to a descriptive magnitude, and print alongside it, in the probe's own output, the
reason it is **not** a correction: SBC is an in-kind expense, correctly expensed, and adding it back to
reinvestment is the one treatment R-43.3 names as wrong. Also remove `sbc` from the
`resid = b + sbc + dtax` framing, or relabel `resid` to say what it is — a descriptive decomposition,
not a corrected `b`.
*Done when:* no output line invites a reader to apply the correction, and the legend says so in a
sentence.

**T1b.2 — Documentation.** `docs/valuation-economic-contract.md`:
- **§1** currently says the marginal rate falls back to `STATUTORY_MARGINAL_TAX_BPS` when unfiled.
  **Stale** — R-41.3 deleted the fallback. Correct it: an issuer-year with no filed marginal rate has
  no NOPAT and is dropped.
- **§10** states "a NOPAT base alone … charges reinvestment zero times, and **overvalues**". True of
  the general model, and written when FR-29 substituted `r := w`. Add the correction: **in this Core,
  since FR-29's deletion, an absent `r` refuses before the base is used**, so a NOPAT base with `r`
  absent publishes nothing at all. Cite `projection.rs:224-226` and the refusal ordering. This is the
  sentence a future reader would otherwise use to argue W4 is unsafe alone.
- **§14** register: add the SBC-framing entry as **closed by T1b.1** with the commit; add **LD-18**,
  the fabricated tax column, with R-50's counts, Round 14's answer, trigger and detector.

| id | type | actor | given | when | then |
|---|---|---|---|---|---|
| W1b-R01 | regression | reader | the probe output | SBC is read | no line frames `sbc/NOP` as a correction to `b` |
| W1b-R02 | regression | reader | `docs/…contract.md` §1 | it is read | no statutory-fallback sentence remains |
| W1b-R03 | regression | reader | `docs/…contract.md` §10 | it is read | the overvaluation sentence carries its correction and the call site |

**Done when:** all three regression rows hold and §14 carries LD-18.

---

### Wave 2 — Pin the refusal ordering W4 rests on, and `r`'s domain

| field | value |
|---|---|
| **id** | `wave-2` |
| **depends_on** | `[]` |
| **Continuity** | new chain |
| **Scope** | `valuation-core/tests/features/intrinsic-value.feature`, `…/features/manifest.toml`, `valuation-core/src/projection.rs` (doc comments only) |
| **Value posture** | **Value-neutral.** Four rows describing behaviour that is already true; a red row means the Core does not do what this plan says it does. |

**Why first, and why alone.** W4's inertness argument is *"an absent base refuses `not_reported`, which
is a different reason from `estimator_unavailable`, and the base check comes first."* **No row
currently pins that precedence** — `base-cash-flow-absent` has `roc = 2500`, `return-absent` has
`base = 100.00`, and no row has both absent. Landing the pin before the wave that leans on it is
R-30.5: build the gate, then move under it.

#### Rows to add — existing `Scenario Outline: Intrinsic Value from a continuously fading growth path`

| case | base | g0 | g_inf | fade | roc | wacc | value | outcome | reason |
|---|---|---|---|---|---|---|---|---|---|
| `base-and-return-both-absent` | `ABSENT` | `1500` | `300` | `0.20` | `ABSENT` | `800` | `ABSENT` | `refused` | `not_reported` |
| `return-on-capital-negative` | `100.00` | `1500` | `300` | `0.20` | `-500` | `800` | `ABSENT` | `refused` | `out_of_policy_range` |
| `high-return-flat-path` | `100.00` | `300` | `300` | `0.20` | `8150` | `800` | `1926.38` | `resolved` | `ABSENT` |
| **`reverted-return-flat-path`** | `100.00` | `300` | `300` | `0.20` | **`800`** | `800` | **`1250.00`** | `resolved` | `ABSENT` |

**The last two are a pair, and the pair is the point (R-49.3, new in v1).** `unit_value` computes
`terminal_payout = 1.0 - terminal / return_on_capital` from a **single scalar `r` that never fades**,
while growth fades from `g0` to `terminal` along the path. So the measured return is asserted for every
year to infinity, and it lands in the terminal payout — where a no-horizon integrand puts most of the
value. These two rows are the same issuer under a peak return and under a return reverted to its cost
of capital: **1926.38 against 1250.00, a 54% span, in one direction only.** Round 12 then measured
`roic` reverting at `phi` 0.37–0.56 on our own cohort, so the model as written is contradicted by our
own data as well as by Fama & French.

Committing the pair moves that from a number in a ruling to a value a reader can check, and **it does
not choose a fade** — H5 stays Juan's. If a fade is ever introduced, `reverted-return-flat-path` is the
row that documents what changed.

Both values are derived from the closed form the table's own `flat-path` row uses: with `g0 = g_inf`,
`V = base × (1 − g_inf/r)/(w − g_inf)`.
- `100 × (1 − 300/8150)/0.05 = 1926.3803…`
- `100 × (1 − 300/800)/0.05 = 100 × 0.625/0.05 = 1250.00`

The same formula reproduces the committed `flat-path` row exactly
(`100 × (1 − 300/1200)/0.05 = 1500.00`), which is why they are stated here rather than deferred.
**If either row comes out different, the builder reports the discrepancy rather than adjusting the
expected value** — a mismatch means the flat-path identity does not hold where this plan claims it
does, which is worth more than the row.

**Why rows and not a new outline (FR-44).** Every column already exists, every quantity already means
the same thing, and all four are the same behaviour — the integral and its guards — at inputs the table
has not reached. `residual-income.feature` was rejected (different quantities, per its own manifest
entry); `valuation-posterior.feature` starts from a firm value. **No `manifest.toml` `[[outline]]`
entry is created** — Advisor verified `every_outline_is_justified_in_the_manifest` keys on outline
names, not rows. The existing entry's `covers` string gains two clauses: *"…the precedence of an absent
base over an absent return, and the domain of the return on capital across the range where the terminal
payout is most sensitive to it."*

#### Invariants
- I2.1 `MAX_ABSOLUTE_Z` is not touched; no threshold in `numerics.rs` moves.
- I2.2 No existing row is edited, reordered or removed.
- I2.3 `tests/schema.rs`'s six checks stay green with **no** manifest `[[outline]]` added.
- I2.4 No step definition changes: `given_base_cash_flow` / `given_return_on_capital` already route
  every cell through `observed(…)`, which handles `ABSENT`.

#### BDD scenarios — the rows are the tests; these are the mutations that make them load-bearing

| id | type | given | when | then | isolating mutation that turns it red |
|---|---|---|---|---|---|
| W2-P01 | positive | `high-return-flat-path` | the row runs | `1926.38` within 1 cent | insert `let return_on_capital = return_on_capital.min(2_500.0);` in `unit_value` — an R-44.5-style "reasonable bounds" clamp. **Only this row moves**; every other row is ≤ 2500 |
| W2-P02 | positive | `reverted-return-flat-path` | the row runs | `1250.00` within 1 cent | multiply `terminal` by 0.5 inside `terminal_payout` only — this row moves to 1312.50, `high-return-flat-path` moves by 0.23, `flat-path` moves. **Not isolated** — see note below |
| W2-N01 | negative | `base-and-return-both-absent` | the row runs | `refused / not_reported` | move the `return_on_capital_bps.value()` `let else` in `projection.rs` **above** the base/growth/discount `let else` — the row reads `estimator_unavailable`. Only this row can see it |
| W2-N02 | negative | `return-on-capital-negative` | the row runs | `refused / out_of_policy_range` | change `if return_on_capital <= 0.0` to `if return_on_capital == 0.0` — `return-on-capital-zero` stays green, this row resolves. Isolated |
| W2-R01 | regression | the manifest | `cargo test -p valuation-core --test schema` | green with no new `[[outline]]` | adding a new outline without a manifest entry fails `every_outline_is_justified_in_the_manifest` |
| W2-E01 | edge | the table | `schema.rs` runs | rectangular, unique cases, `ABSENT` only | writing `-` or `n/a` in any new cell fails `absence_is_spelled_only_with_the_reserved_token` |

**On W2-P02's mutation, honestly.** `feedback_isolate_the_mutation`: a mutation that moves several rows
proves nothing about any single assertion. The `reverted-return-flat-path` row sits at `r == wacc`,
where no arithmetic branch is unique to it, so **there is no mutation this repo's shape admits that
turns only this row red.** That is stated rather than papered over with a mutation that moves three
rows and a claim of isolation. Its value is as the *committed half of a pair* — it makes the 54% span
checkable — not as an independently mutation-verified assertion. **The builder must report it that
way and must not invent an isolating mutation to fill the cell.**

**Every other mutation is applied and reverted one at a time (R-8.4). A combined mutation proves
nothing.** The report says "isolated" for each, or says why not.

**Commands:**
```
cd apps/windows/src-tauri
cargo test -p valuation-core                 # cucumber, harness = false
cargo test -p valuation-core --test schema
cargo test -p valuation-core --lib
cargo test --lib
```
**Expected counts:** `cargo test --lib` unchanged — cucumber rows are not `#[test]`s in the shell
crate. `valuation-core`'s cucumber scenario count rises by exactly **4** from the count measured at
wave start (R-9.3: baseline your own tree, and quote it).
**Documentation deliverable:** the `# Rows worth reading as a set:` comment block in
`intrinsic-value.feature` gains a paragraph per row. `base-and-return-both-absent` must say **why** it
exists — it is the precedence W4's value-neutrality argument depends on. The last two must be
documented **as a pair**, with the span and R-49.3's registration named.
**Done when:** four rows green, the isolated mutations recorded (and W2-P02's honest non-isolation
recorded as such), `covers` updated, no new outline.

---

### Wave 3 — Sweep for robust centres taken along time rather than across a population

| field | value |
|---|---|
| **id** | `wave-3` |
| **depends_on** | `[]` |
| **Continuity** | new chain |
| **Scope** | `docs/valuation-aggregation-audit.md`, `docs/valuation-economic-contract.md` §14. **Read-only over source.** |
| **Value posture** | **Value-neutral.** No source file changes. |

R-46.2: `standardize` trims a **cross-section** — draws from one population, where a point four
deviations out is a bad observation. A **time series** is not that. When an issuer's economics change,
the recent regime is a minority of the series and reads as outlying, so the rule **deletes the present
and keeps the past** and reports a confident centre for a firm that has moved. `PG gross roic` trims
2022–2025; `COF gross roic` trims 2024–2025; `OMC oper`'s n=12 ends in 2020.

Measured rate at which the latest observation is trimmed out, reproduced identically on two independent
runs (R-48.6, R-50.10): **12.7%** (`s2c/gross`), 11.2% (`roic/gross`), 7.9% (`roic/oper`), 5.1%
(`s2c/oper`). One window in eight at the top end.

**Sweep and report only. Do not fix. Do not change `MAX_ABSOLUTE_Z`.**

#### Tasks

**T3.1 — Enumerate every call site.** `robust_centre`, `robust_mean`, `standardize`, and — because
R-46.2's lesson is about *any* order statistic on an axis — naked medians and `sorted[len/2]`. Starting
set found while planning, **to be confirmed and extended, not trusted**:

| site | axis | note |
|---|---|---|
| `valuation_core_adapter.rs:660` `growth_posterior` | **time, per issuer** | **v1 correction:** one caller, inside the adapter, plus its own tests. **No production consumer.** The R-46.2 defect here is **scheduled, not live**, and it is scheduled for W6 |
| `valuation_core_adapter.rs:335` pooled growth centre | mixed — pooled across issuers *and* years | classify honestly; it is not obviously either |
| `driver_resolution.rs:244` `rates[rates.len() / 2]` | **time** | LD-15's naked order statistic; already registered, **not fixed here** |
| `valuation_probes.rs` per-issuer centres | time | diagnostic only |
| `valuation_probes.rs` cross-sectional centres, incl. Round 13's `M(t)` | cross-section | the correct use; report it so the doc shows the contrast |

**T3.2 — For each site, record four facts:** what population the sample is drawn from; whether the
axis is time or cross-section; **whether the result reaches a published value today, and if not, which
wave would arm it**; and — where the axis is time — whether a *count* of retained observations is being
read as evidence that the centre is current.

**T3.3 — The retained-count warning, generalised.** Write down once, in
`docs/valuation-aggregation-audit.md`: **a count of retained years is not evidence that a centre is
current.** Then grep this plan's own acceptance criteria, `valuation_probes.rs`'s legends, and
`docs/roic-*.md` for any criterion leaning on a year count, and list them.

**T3.4 — Register the latent defect.** New row in `docs/valuation-economic-contract.md` §14, in LD-6's
shape: *trigger* — **wave-6, the wave that first wires the Core to production and mints the first
goldens**; *detector* — name it, or say plainly that none exists. If the honest answer for the
`growth_posterior` site is *"the detector is that the centre and the latest kept year are both printed
and nobody compares them"*, say that.

#### Invariants
- I3.1 Zero source files change. `git diff --stat` shows only `docs/`.
- I3.2 `MAX_ABSOLUTE_Z` is `3.0` before and after; `standardize`'s `n < 3` refusal intact.
- I3.3 No fix is proposed for a site nobody has looked at. Sites are **classified**, not remedied.

#### BDD scenarios

| id | type | given | when | then | notes |
|---|---|---|---|---|---|
| W3-P01 | positive | the codebase | the sweep runs | every `robust_*` / `standardize` / naked-median site is listed with its axis and whether it reaches a published value | completeness is the deliverable |
| **W3-P02** | positive | `growth_posterior` | it is classified | recorded as **time axis, no production consumer, armed by wave-6** | **v1 correction** — v0 recorded "reaches published value", which Advisor's grep disproves (R-49.1) |
| W3-N01 | negative | a site whose axis is genuinely ambiguous (`:335`) | it is classified | recorded as **ambiguous**, not forced into a bucket | R-40.1 |
| W3-N02 | negative | a site with a known defect (LD-15) | it is found | cross-referenced to its register entry and **not fixed** | scope |
| W3-E01 | edge | a site inside `#[cfg(test)]` | it is found | listed and marked diagnostic-only | the contrast is part of the finding |
| W3-R01 | regression | `numerics.rs` | after the wave | `MAX_ABSOLUTE_Z == 3.0`, the `n < 3` refusal intact | no ground gained by weakening |

**Commands:**
```
rg -n "robust_centre|robust_mean|standardize\(" apps/windows/src-tauri/src apps/windows/src-tauri/valuation-core/src
rg -n "len\(\) / 2\]|\.sort" apps/windows/src-tauri/src
cd apps/windows/src-tauri && cargo test --lib
```
**Done when:** every site is classified, the retained-count warning is written once and
cross-referenced, W3-P02 records the corrected classification, and no source file changed.

---

### Wave 4 — The base: NOPAT into the slot FR-28 requires it for

| field | value |
|---|---|
| **id** | `wave-4` |
| **depends_on** | `[wave-1a, wave-2]` **and Round 14's P19** |
| **Continuity** | `same_session` preferred with wave-1a; `new_session` acceptable if the pre-registration is read in full |
| **Value posture** | **Value-neutral, provably.** Zero published cents exist before or after. The falsifiable claim is that **all 18 `estimator_unavailable` rows keep their reason**; MH and BWMN are vacuous. Proof: `published_value_regression_gate` green **and** `published_value_regression_gate_cohort.json` absent from `git status`. |

#### Entry gates — two, and both are stops rather than judgement calls

**Gate 1 (from v0).** If wave-1a's T1a.1 shows any of the 18 dropping below 3 pretax years, **this wave
stops and returns to Juan.** Re-blessing to absorb a reason change is forbidden (R-38.5) unless the flip
was named per issuer in wave-1a's pre-registration, written before the numbers.

**Gate 2 (new in v1, and it is why this wave is not v0's wave).** R-49.2 registered, before Round 13
ran, that the count of *"the fixture holds a number where the source has nothing"* decides whether this
wave proceeds as scoped. **It came back 26.** So **v0's W4 does not proceed.** The corpus this wave
would compute NOPAT from carries a marginal tax rate SEC never filed on at least 26 rows spanning 12 of
the 20 pinned issuers — and, because all 274 rows carry a non-null rate while production resolves
`<none>` on at least 33, **the column cannot express absence at all**, so the other 146 are unproven
rather than sound.

**Round 14's P19 chooses between two branches. Both are written here; neither is chosen by a builder.**

**Branch (a) — make the corpus able to say "absent".** Regenerate both tax columns from the pipeline,
letting an unfiled rate be `null`. Rows whose rate is not traceable to a filed fact drop out of NOPAT
by the existing `?`. Same shape as R-46.3's *supply, not fabrication*, and it makes the fixture
faithful — a precondition for **every** wave that reads it, not only this one.
*Costs* coverage on the gate cohort, and P19 is exactly that cost.

**Branch (b) — carry per-row provenance and refuse on an untraceable rate.** Requires a column the
fixture does not have, so it is (a) plus a field.

**The decision rule, registered before P19 is read:** if branch (a) leaves **every one of the 18
falsifiable issuers with ≥3 usable years**, take (a) — it is strictly simpler and strictly more
faithful. If it does not, **stop and return to Juan** with the per-issuer cost, because the choice is
then between a narrower cohort and a heavier fixture, and that is an economic call rather than an
engineering one. **Under no branch is a rate substituted, defaulted, back-filled or repaired.**

#### Files

| path | change |
|---|---|
| `src/valuation_baseline.rs` | `DriverAnnual` gains `pub(crate) pretax: Option<f64>` with `#[serde(default)]` so `baseline_driver_data_2026-07-30.json` still parses. **Confirm A4 first:** read whether the tax fields are already `Option`. If they are not, this wave gains a type change and says so. |
| `src/valuation_fixture_capture.rs` | `deep_driver_year_row` emits `"pretax": point.pretax_income_dollars` (explicit null, never a default); new `#[ignore]`d `enrich_the_deep_driver_fixture_with_pretax_income` |
| `tests/fixtures/valuation/core_driver_data_deep.json` | one key added per row; **every existing key byte-identical**; no row added or removed. **Under branch (a), the tax columns additionally gain `null` where nothing was filed — and that is a second, separately diff-proven change, not a side effect of the first.** |
| `tests/fixtures/valuation/published_value_regression_gate_cohort.json` | rows gain a `provisional` marker (see below) |
| `src/valuation_core_adapter.rs` | `IssuerAnnual` gains `pub pretax_income: f64`; `fn free_cash_flow` → `fn nopat` on the base path; `base_cash_flow` provenance `"free_cash_flow"` → `"nopat"`; `after_tax_fcff` retained for the probe but no longer reachable from `base_cash_flow` |
| `src/valuation_core_measurement.rs` | `issuer_annual` gains `pretax_income: row.pretax?` |
| `valuation-core/src/projection.rs` | doc comment only |
| `docs/valuation-economic-contract.md` | §1, §3, §10, §14 |

#### Tasks

**T4.1 — Surgical fixture enrichment, proven in a test and failing closed.**
`enrich_the_deep_driver_fixture_with_pretax_income`, `#[ignore = "reaches the SEC network and rewrites
a fixture"]`: read the existing fixture; for each `(symbol, year)` **already present**, fetch and
insert `"pretax"`; write back. It must not add or remove a symbol, add or remove a year, or alter any
existing key.

Two changes from v0, both from Advisor's review:

1. **The diff proof lives in the `#[ignore]`d test, not in a Python snippet a human pastes once.**
   Before writing, the test holds the parsed prior document; after building the new one, it asserts —
   in Rust, as the single assert over a collected mismatch list this repo already uses — that the
   symbol set is identical, each symbol's year list is identical **in order**, and every key except
   `pretax` is byte-equal. It writes **only if** that holds. A proof a human runs once is
   indistinguishable six months later from a claim nobody checked.
2. **It fails closed.** If any fetch errors, or EDGAR is unreachable for any issuer in the corpus, the
   run **aborts and writes nothing**. It must never write `null` for a *fetch failure*, because that
   `null` is indistinguishable from a genuine absence — which is precisely the defect Round 13 found,
   arriving by a new route.

`source` is updated to record the enrichment, and that is the only other permitted change.
*Also record:* per issuer, `years_with_pretax` — **compared to wave-1a's prediction. Any difference is
a stop.**

**T4.2 — Carry pretax through the types.** `DriverAnnual.pretax: Option<f64>`;
`IssuerAnnual.pretax_income: f64`; `issuer_annual` uses `?`. **No `Default`, no `unwrap_or(0.0)`, no
fabricated zero.**

**T4.3 — The base becomes NOPAT.**
```
nopat(year) = (pretax_income + interest_expense) * (1 - marginal_tax_bps/10_000)
```
Identical to `docs/valuation-economic-contract.md` §1 and to `valuation_probes.rs`, so the workspace
has one definition of NOPAT and not two. Interest enters **signed**: a net-interest-income filer's
negative interest correctly removes income `pretax` already contains. `base_cash_flow` fits its trend
over `nopat()` instead of `free_cash_flow()`; `LEVEL_WINDOW_YEARS = 5`, `MIN_ANNUAL_OBSERVATIONS = 3`,
the residual-variance width and `UncertaintyBasis` are unchanged. **No constant moves.**

**T4.4 — Make FCFF unrepresentable in the slot (R-30.1).** `base_cash_flow` no longer calls
`after_tax_fcff` even transitively; provenance source is `"nopat"`; a named test asserts the numeric
distinction (T4.7).

**T4.5 — Execute the branch P19 selects**, per the registered decision rule above. Under (a), the tax
regeneration is its **own** diff-proven step with its own test: the only permitted change is a value
becoming `null`, and **no value may change from one number to another**. A number-to-number change
means the pipeline disagrees with the fixture about a rate that *was* filed, which is a different and
larger finding — **report it and stop.**

**T4.6 — Split the golden into pinned and provisional.** Every row minted or re-blessed while a named
open defect is registered against its inputs carries a `provisional` marker naming the defect (LD-18
for the tax column; R-46.2's site for the growth centre once W6 arms it). The premise Sensei gave for
this was that values are being minted from something already shipping wrong — they are not (§1.1(b)).
The correct premise is that **the wave that mints is the wave that arms**, and a value minted under a
named open defect should say so in the file rather than becoming indistinguishable from a validated
one the moment it is written.

**T4.7 — Tests.**

| test | file | asserts | isolating mutation |
|---|---|---|---|
| `the_base_is_earnings_before_reinvestment_not_free_cash_flow` | `valuation_core_adapter.rs` | on one hand-built issuer with known `pretax/interest/tax/ocf/capex`, the base level equals the NOPAT trend level and **differs** from the FCFF trend level by the known reinvestment | revert `base_cash_flow` to call `free_cash_flow()` |
| `the_base_slot_names_the_quantity_it_carries` | `valuation_core_adapter.rs` | `base_cash_flow(&frame).provenance()` source is `"nopat"` | restore `"free_cash_flow"` |
| `issuer_annual_drops_a_year_with_no_filed_pretax_income` | `valuation_core_measurement.rs` | a `DriverAnnual` with `pretax: None` yields `None` | `row.pretax.unwrap_or(0.0)` |
| `deep_driver_year_row_never_fabricates_pretax_income` | `valuation_fixture_capture.rs` | the emitted row's `pretax` is JSON `null` when the point has none | `unwrap_or(0.0)` in the emitter |
| `the_enrichment_writes_nothing_when_a_fetch_fails` | `valuation_fixture_capture.rs` | on a simulated fetch failure, the file on disk is byte-identical | make the failure path write the partial document |
| `published_value_regression_gate` | existing | 20 pinned outcomes unchanged | any of the above, or an unmeasured pretax hole |

**One assert per test.** Where two facts are needed (NOPAT level *and* not-FCFF level), express it as
one assert over a collected mismatch list — the pattern `published_value_regression_gate` already uses.

**Fixture-parse hazard, registered.** `DriverAnnual.interest` is `f64`, not `Option<f64>`, while the
emitter can write `null` (LD-13's residual). The current corpus has zero interest nulls, so this is
latent — the enrichment must not introduce one. Detector: the parse panics loudly. Do **not** "fix" it
by widening the type in this wave.

#### Invariants

- I4.1 `published_value_regression_gate` green **and** `git status` does not list
  `published_value_regression_gate_cohort.json`. **This pair is the value-neutrality proof;
  transcribed numbers are not.**
- I4.2 The four known failures, by name, unchanged in state.
- I4.3 No threshold, constant or refusal path relaxed. `MIN_ANNUAL_OBSERVATIONS`,
  `LEVEL_WINDOW_YEARS`, `MAX_ABSOLUTE_Z` unchanged.
- I4.4 `valuation-core/Cargo.toml` `[dependencies]` still empty (FR-1); street unreachable from the
  value function (FR-35).
- I4.5 No ticker appears in any conditional anywhere.
- I4.6 **No tax rate is substituted, defaulted, back-filled or repaired, under either branch.**
- I4.7 `high_signal_screener_observation_2026-08-02.json` unstaged.

#### BDD scenarios

| id | type | actor | given | when | then | notes |
|---|---|---|---|---|---|---|
| W4-P01 | positive | adapter | an issuer with pretax, interest and a filed marginal rate for ≥3 years | the base is resolved | it is the NOPAT trend level | the wave's purpose |
| W4-P02 | positive | gate | the enriched fixture | `cargo test --lib` | all 18 keep `estimator_unavailable` | falsifiable population |
| W4-N01 | negative | adapter | a year with no filed pretax | evidence is assembled | the year is dropped, never zero-filled | absence ≠ zero |
| W4-N02 | negative | adapter | an issuer left with <3 usable years | the base is resolved | `Observation::absent(InsufficientObservations)` → gate reads `not_reported` → **stop** unless pre-registered | named in advance |
| W4-N03 | negative | emitter | an `FcfPoint` with no pretax | the row is rendered | `"pretax": null` | LD-13's rule extended |
| W4-N04 | negative | enrichment | a fetch fails mid-run | the writer runs | **nothing is written**; the file is byte-identical | a `null` from a fetch error is indistinguishable from a genuine absence |
| W4-E01 | edge | adapter | a net-interest-**income** filer (negative signed interest) | NOPAT is computed | interest is *subtracted*, not `.abs()`-ed back in | R-7.3 / LD-1 |
| W4-E02 | edge | adapter | an issuer whose NOPAT is negative in some years | the base is resolved | whatever the fit says; **no floor, no clamp** | a floor is a fabricated measurement |
| W4-E03 | edge | gate | MH and BWMN | the gate runs | still `not_reported`; the report states this is **vacuous**, not confirmation | R-8.4 masked pin, pre-empted |
| W4-R01 | regression | fixture | the enriched corpus | the **in-test** diff proof runs | only `"pretax"` keys added; `source` the only other change | prevents a re-capture masquerading as an enrichment |
| **W4-R02** | regression | corpus | branch (a) has run | the tax diff proof runs | **every change is a value becoming `null`; no value changes from one number to another** | **v1 inversion.** v0 pinned "still 179 at 2100" as a thing that must not move — which pinned the defect in place. The invariant is now that the repair is *only ever* an erasure |
| W4-R03 | regression | golden | a row is minted under a named open defect | the gate is blessed | the row carries its `provisional` marker and names the defect | T4.6 |

**Commands:**
```
cd apps/windows/src-tauri
cargo test --lib enrich_the_deep_driver_fixture_with_pretax_income -- --ignored --nocapture
cargo test --lib                              # the diff proof is now a test, not a paste
cargo test --lib core_versus_current_engine_on_the_pinned_cohort -- --ignored --nocapture
cargo test -p valuation-core
rustfmt src/valuation_core_adapter.rs src/valuation_core_measurement.rs src/valuation_baseline.rs src/valuation_fixture_capture.rs
git add <each path explicitly>                # never git add -A
```
**Documentation deliverable:** `docs/valuation-economic-contract.md` — §1 (NOPAT is the Core's base,
with the call site), §3 (`Reinvestment = NOPAT − FCFF`, both terms now on the same footing), §10 (the
base half has landed, the charge half has not, and the model is inert between **because `r` is
absent**), §14 (**LD-18**: the fabricated tax column, with Round 13's and Round 14's counts, the branch
taken, its trigger — wave-6 — and its detector).
**Done when:** gate green, golden's pinned rows untouched, all 20 outcomes match wave-1a's prediction
exactly, both diff proofs green as tests, and the branch taken is recorded with P19's number.

---

### Wave 5 — The cash driver as a composition

| field | value |
|---|---|
| **id** | `wave-5` |
| **depends_on** | `[wave-1a]` |
| **Continuity** | `same_session` preferred with wave-1a; `new_session` acceptable if both tables are read in full |
| **Scope** | contract, generator output (both platforms), `edgar.rs`, `dcf_model.rs`, probe, fixtures, docs |
| **Value posture** | **Value-neutral.** The new driver has no consumer until W6. Expect two new dead-code warnings — **do not `#[allow(dead_code)]` them** (R-11.3: the warning is the honest record that the API is built and unwired). |

**Why this is not the W2a hazard (R-10.3).** W2a was unsafe alone because it shipped a contract
*declaring a convention the code did not honour*. Here the contract gains a driver **and** `edgar.rs`
implements it in the same wave. Contract, generated files, fingerprint and extraction agree from the
first commit.

**Why the dependency is now honest.** v0 said the candidate qname list was *"not a contract
proposal"* and then had W5 take the contract's list from its coverage. §W1a resolves it: **the
candidate set and the admission rule are registered before the coverage is read**, so T1a.3 measures a
pre-registered set rather than selecting one. The dependency on wave-1a is real and logical, not
merely file ownership.

#### The containment lattice — declared here, before any number

Sensei's P0-F, and it is right: *"aggregate supersedes parts"* is undefined without a declared
containment relation on **every** pair, and the failure is asymmetric. Double-counting cash nets too
much off invested capital → capital reads low → return reads high → the retention charge `1 − g/r`
reads small → **value reads high. One direction only.** So the lattice is stated, not inferred:

| concept | role | contains |
|---|---|---|
| `CashCashEquivalentsAndShortTermInvestments` | **aggregate** | `CashAndCashEquivalentsAtCarryingValue` + `ShortTermInvestments` |
| `CashAndCashEquivalentsAtCarryingValue` | part | — |
| `ShortTermInvestments` | part | `MarketableSecuritiesCurrent`, `AvailableForSaleSecuritiesDebtSecuritiesCurrent`, `OtherShortTermInvestments` |
| `MarketableSecuritiesCurrent` | part | — |
| `AvailableForSaleSecuritiesDebtSecuritiesCurrent` | part | — |
| `OtherShortTermInvestments` | part | — |

**Rules, in order, and they are the contract:**
1. If the aggregate is filed for a year, it **supersedes** everything below and carries its own
   provenance. Nothing is added to it.
2. Otherwise `CashAndCashEquivalentsAtCarryingValue` + **at most one** of the `ShortTermInvestments`
   tier are summed — the tier is a **select-one-equivalent within the sum**, because its members
   overlap each other and summing two of them double-counts.
3. A year filing neither tier is **absent**. Never zero, never carried forward.
4. **If a year files two members of the `ShortTermInvestments` tier with different values, that year is
   absent and is reported by name.** Picking the larger is a choice with a known direction, and the
   direction is upward.

#### Tasks

**T5.1 — Contract: `operatingCash` as a composition, not a selection.** R-45.2: `select_one_equivalent`
**selects**, so a securities qname would *replace* cash rather than add to it. The shape is
`extract_total_debt`'s (`edgar.rs:705-744`). The qname lists are the **registered candidate set minus
those T1a.3 measured at zero coverage** — nothing else enters.

**T5.2 — Conditionally, the marginal-rate qnames (R-46.3).** Only if T1a.4 returned answer (i). Then
`marginalTaxReference` gains the measured qnames in the **same** fingerprint bump. If (ii) or (iii),
the contract is untouched on this point and T5.6 registers AMZN's refusal instead. **Under no answer is
a statutory rate substituted.**

**T5.3 — Fingerprint `/9` → `/10`, once**, for whatever T5.1 and T5.2 together contain. Two waves each
bumping to `/10` is a collision; that is why the two changes share this wave.

**T5.4 — Regenerate both targets**, then `-Check` must be clean. The Kotlin file gains the constants
inertly (R-3.1/R-3.2: the contract and generator do **not** narrow to Rust; Android reads by named
field). No Kotlin behaviour change, no Gradle run. Android stays out of scope — Windows first.

**T5.5 — `extract_operating_cash` in `edgar.rs`**, modelled on `extract_total_debt`, implementing the
lattice above exactly, with composed `AnnualProvenance`. **Do not use `.first()` on
`provenance.sources` for this composed driver** — LD-16 names that as quietly wrong for compositions;
take the max `end`, as `extract_total_debt` does.

**T5.6 — Point the probe at the real driver** and re-run `probe_sales_to_capital_conditioning`.
**Report the change in per-issuer year coverage against R-45.2's numbers** (COF 16/16 dropped, SLB
14→3, OMC 17→12, PG 14→10, MSFT 16→15, DVN 10→9) — a composition should recover years a selection
lost, and the count is the evidence that it did. **Adopt no estimator, choose no window, choose no
capital definition.**

**T5.7 — AMZN, honestly.** Whichever of T1a.4's three answers holds, write it down with a trigger and a
detector. If (iii), **AMZN refuses until the data exists**, that is a §14 register row, and W6's
pre-registration predicts an AMZN refusal rather than a value.

#### Invariants
- I5.1 `-Check` clean: committed generated output equals generated output, **both targets**.
- I5.2 Gate green; golden untouched; the four known failures unchanged.
- I5.3 No `#[allow(dead_code)]` added.
- I5.4 A year with neither cash tier filed is **absent** — never zero, never carried forward.
- I5.5 No qname enters the contract without measured filed coverage from wave-1a, and none enters that
  was not in the registered candidate set.
- I5.6 No pair in the lattice is left undeclared. An undeclared overlap is a stop, not a default.

#### BDD scenarios

| id | type | given | when | then | isolating mutation |
|---|---|---|---|---|---|
| W5-P01 | positive | a year filing cash **and** one short-term-investments concept | `extract_operating_cash` runs | the two **sum** | replace the sum with last-wins selection |
| W5-P02 | positive | a year filing the aggregate **and** both parts | it runs | the aggregate supersedes; provenance is the aggregate's | delete the aggregate-override branch |
| W5-P03 | positive | the contract at `/10` | `-Check` runs | clean, both targets | edit the generated Rust by hand |
| W5-N01 | negative | a year filing neither tier | it runs | the year is **absent** | `unwrap_or(0.0)` |
| W5-N02 | negative | a fact in a non-USD unit | it runs | rejected at the normalization boundary | widen the unit check |
| **W5-N03** | negative | a year filing **two** `ShortTermInvestments`-tier members with different values | it runs | the year is **absent** and is named in the report | take the larger — the direction of that error is upward, always |
| W5-E01 | edge | a composed year | provenance is built | `end` is the max over contributors, not `.first()` | switch to `.first()` — LD-16's hazard, live for a second composed driver |
| W5-E02 | edge | COF, a bank filing neither concept | the probe runs | 16 of 16 years dropped, printed, **no substitution** | any fallback |
| W5-R01 | regression | fingerprint | `cargo test --lib` | the `sec_normalization` assertion and the fixture `policyFingerprint` both read `/10` | bump one and not the other |
| W5-R02 | regression | gate | `cargo test --lib` | 20 pinned outcomes unchanged | wiring the driver into the value path in this wave |

**Commands:**
```
pwsh -File scripts/generate-sec-driver-normalization-policy.ps1
pwsh -File scripts/generate-sec-driver-normalization-policy.ps1 -Check
cd apps/windows/src-tauri && cargo test --lib
cargo test --lib probe_sales_to_capital_conditioning -- --ignored --nocapture
rustfmt src/edgar.rs src/dcf_model.rs src/sec_normalization.rs src/valuation_probes.rs
```
`scripts/validate-contracts.ps1` also pushes into `apps/desktop` and `apps/android`; run the `-Check`
step directly if the Gradle leg is unavailable.
**Documentation deliverable:** `docs/valuation-economic-contract.md` §2 — invested capital becomes
`StockholdersEquity + TotalDebt − OperatingCash`, **with the lattice reproduced** and R-45.2's
under-netting note closed with its error direction restated; §14 — LD-16's `.first()` hazard now has a
second call site; plus T5.7's AMZN row.
**Done when:** `-Check` clean, gate green, golden untouched, per-issuer year coverage reported against
R-45.2, every lattice pair declared, and AMZN's answer written down with a trigger and a detector.

---

### Wave 6 — The estimator. **BLOCKED. Shape only.**

| field | value |
|---|---|
| **id** | `wave-6` |
| **depends_on** | `[wave-4, wave-5]` **and five decisions that are Juan's** (H1–H5). **Not satisfiable today** — that is the honest state, not an omission. |
| **Value posture** | **Value-MOVING, on purpose. The first wave in this effort that publishes anything.** All 20 pinned issuers change state. Requires a per-issuer pre-registration written **before** the wave; the gate red for exactly the registered issuers with the registered outcomes; and an explicit `bless_published_value_regression_gate_cohort` run by a person. **Any unregistered mover is a stop.** |

#### Registered holes — the Orchestrator fills these, not the planner and not a builder

**H5 — `r`'s fade. NEW, and upstream of everything else here (R-49.3).**
`unit_value` computes `terminal_payout = 1.0 - terminal / return_on_capital` from a **single scalar `r`
that never fades**, while growth fades along the path. Whatever return is measured is asserted for
every year to infinity, and it lands where a no-horizon integrand puts most of the value. The span is
**1926.38 against 1250.00 — 54%, one direction only** (W2's committed pair), and Round 12 measured
`roic` reverting at `phi` 0.37–0.56 on our own cohort. A fade rate is a **policy constant with no filed
value**, and R-44.5 governs: nothing tighter than the existing arithmetic guards goes in without Juan
registering it as policy with the number written down first. **It is upstream of the window** — the
window picks a point on a series; this decides whether that point is asserted for one year or for all
of them.

**H4 — The capital definition, `gross` vs `oper`. NEW, and it is what the window collapsed into
(R-50.2).** Round 13's five-way common set splits **entirely by capital definition**: under `oper`,
`E5` is strictly lowest at all three horizons for **both** series (six of six, `roic/oper` h2 better by
31%); under `gross`, `E2` takes the two largest margins. R-45.1 named `oper` the deciding definition at
`9919449`, three rounds before these numbers existed — so it is a live decision with a documented
prior, **not** a coin flip discovered today. But P12's win condition requires both definitions, and
narrowing to the panel where the answer is clean *after seeing which panel that is* is
`feedback_scope_you_cannot_get_wrong` with a citation attached. **The Orchestrator does not resolve it
by invoking R-45.1.**

**H1 — The window. Two options survive; the other two are dead by measurement, and H1 no longer stands
alone.**

| option | status |
|---|---|
| (i) whole filed history (`E1`) | **OUT.** Loses twelve of twelve to `E2` — three horizons × two capital definitions × two series, 58–82% worse at one year (R-48.1) |
| (iii) whole history, refuse when the latest year was trimmed (`E4`) | **OUT as an estimator.** By construction it *is* `E1` where it does not refuse, so it inherits all twelve losses and pays 40–60% coverage for them. Survives only as a diagnostic (R-48.1) |
| (ii) the latest usable year (`E2`) | **live** — wins under `gross` |
| (new) cross-sectional partial adjustment (`E5`) | **live** — wins six of six under `oper`; `psi` fitted, never chosen, and on `roic/gross` it reproduces Fama & French's 0.62 to within 0.35 SE (R-50.3) |

`E2` and `E5` have **identical coverage** on every panel, so R-47.5's P9 coverage tiebreak is empty
before it can be used. **H1 is answered by H4.**

**No trailing-N, no half-life, no recency weight is proposed, and none may be introduced by a
builder.** Any N chosen now would be chosen after seeing which issuers it flatters — R-41.5's post-hoc
threshold with a new name.

**H2 — `prod` vs `roic`.** `robust_centre(Sales/Capital) × robust_centre(NOPAT/Sales)` versus
`robust_centre(NOPAT/Capital)`. Equal year by year (DuPont); they differ only in which years each
centre trimmed. `gap = prod − roic` reaches **0.202 (AMZN, oper)**, **0.091 (MSFT, oper)**, 0.061 (PG)
— three of the four anchors — against return levels of 0.1–0.3. **No test decides this.** Rounds 12
and 13 ran the two series separately precisely so they could not settle it in passing (R-45.3).

**H3 — AMZN's supply.** Resolved by W5's T5.7 into one of three answers. If refusal, W6's
pre-registration predicts an AMZN refusal and an anchor is dark. **A fact to be stated, not a reason to
fabricate a rate** — and R-50.6 shows five of AMZN's rows were being papered over by the fill Round 13
found, so the hole is larger than R-46.3 measured.

#### Known fragility, to be carried with whatever is chosen

- **The persistence coefficients rest on MSFT.** Leave-one-issuer-out on `roic/oper`: `phi` 0.5609
  all-issuer, LOO range 0.2608–0.7192, and the 0.2608 is **MSFT alone** (next lowest 0.5470). `psi` on
  the same panel: MSFT alone at 0.7442 against an 0.8650–0.9121 body. MSFT is also the issuer R-46.1
  graded as cleanly supporting the window hypothesis and R-45.4 flagged for an 0.815 return. It keeps
  being the observation that moves things (R-50.4).
- **`M(t)` is itself unstable.** The `roic/oper` cross-sectional centre runs **0.0444 in 2019 and
  0.2375 in 2022**. Anchor stability was never registered as a property of the criterion.
- **Ratio error is not value error** (R-48.5). The retention charge is `1 − g/r`; as `r` → 0 the charge
  diverges. An estimator can carry the lowest mean absolute error on `r` and still produce a
  catastrophic value where it lands near zero — `HPE`'s 0.007 against a centre of 0.045. Detector: W6's
  pre-registration must predict the **per-issuer value**, so a divergent one appears by name in a
  failing gate rather than in a screen.

#### Shape, so the decisions land on something concrete

- `IssuerAnnual` gains `stockholders_equity: f64` and `operating_cash: f64` (both dropping the year on
  absence, as pretax does).
- `IssuerEvidence::return_on_capital` stops being hardcoded absent and returns a measured
  `Observation<f64>` in **bps**, from the chosen estimator and definition, with `variance_of_centre()`
  as the width and `UncertaintyBasis::SampleVariance { observations: retained() }`. **Never `variance`
  over the untrimmed sample.**
- **The bound is the guard that already exists** (R-44.5): the Core refuses `r <= 0` and refuses
  terminal growth at or above the discount rate. Nothing tighter without Juan registering it as policy
  with the number written down first.
- `every_operating_issuer_in_the_pinned_cohort_refuses_for_an_absent_return_on_capital` is **DELETED,
  not weakened** — its own doc comment orders this. A version that filters out newly-measurable issuers
  is a test rewritten to keep passing.
- `a_complete_issuer_still_refuses_for_an_absent_return_on_capital` and
  `an_absent_return_on_capital_refuses_rather_than_being_valued_at_the_neutral_line` are **re-pointed at
  an issuer whose evidence genuinely lacks the terms**, not deleted — FR-29's refusal must stay
  reachable and tested.
- **This is the wave that arms R-46.2's `growth_posterior` site and LD-18's tax column.** Both are
  registered with W6 as their trigger, and both must appear in the pre-registration.

#### BDD rows, when it lands

The value-path behaviour is covered by the existing table plus W2's four rows, which pre-pin `r` at
both ends (`return-on-capital-negative`, `high-return-flat-path` at 8150, `reverted-return-flat-path` at
800). **Assessment: W6 needs no further Core rows** — but that assessment is made without H1/H2/H4/H5's
answers, and if H5 introduces a fade it is wrong: a fade is a new parameter and the outline gains a
column, which is an FR-44 cost this plan has not priced. **Flagged, not resolved.**

#### Invariants (whichever way H1–H5 go)

- I6.1 Street is not a clamp, an optimand, or an acceptance criterion. No min-WACC-as-truth, no price
  cap, no output clamp, no sector FCF haircut.
- I6.2 No ticker special-case.
- I6.3 No threshold moved to make a change pass; `MAX_ABSOLUTE_Z` is `3.0`.
- I6.4 Every centre through `robust_centre` / `robust_mean` (no threshold argument).
- I6.5 The chosen estimator, capital definition, form and fade are **stated before any issuer is
  scored**, with the source they come from.
- I6.6 A gate re-bless requires a per-issuer written registration **before** the wave, plus a person
  running the `#[ignore]`d writer.
- I6.7 Every row minted while a named open defect is registered against its inputs carries its
  `provisional` marker (W4's T4.6).

#### Sketch scenario table (completed once H1–H5 resolve)

| id | type | given | when | then |
|---|---|---|---|---|
| W6-P01 | positive | an issuer with ≥3 usable capital-years | `return_on_capital` is resolved | a measured Observation in bps, width = `variance_of_centre`, sample size = `retained` |
| W6-P02 | positive | the pinned 20 | the gate runs | exactly the registered issuers move, to the registered outcomes |
| W6-N01 | negative | an issuer with <3 usable capital-years | resolved | absent → the Core refuses `estimator_unavailable` |
| W6-N02 | negative | a centre that comes out ≤ 0 | valued | `out_of_policy_range`, no floor, no clamp — pinned by W2's `return-on-capital-negative` |
| W6-E01 | edge | AMZN under H3's answer | valued | the registered outcome, refusal included |
| W6-E02 | edge | MSFT at a high measured `r` under H1/H4 | valued | the terminal payout W2's pair pins; **no clamp** |
| W6-E03 | edge | an issuer whose `r` lands near zero | valued | the pre-registration predicted its value by name (R-48.5's detector) |
| W6-R01 | regression | FR-29 | an issuer genuinely lacking the terms | still refuses `estimator_unavailable` |
| W6-R02 | regression | the golden | after re-bless | every changed row appears in the pre-registration written before the wave, and carries its `provisional` marker if a defect is open against its inputs |

---

## 3. Cross-cutting

**Rollout.** Nothing user-visible ships in W1a–W5: the Core has no production consumer. W6 is the first
wave that could publish, and it is gated on Juan.

**Provenance.** Every new evidence term (`pretax`, `operating_cash`, `stockholders_equity`) enters as
`Option`, drops its year on absence, and travels with `AnnualProvenance`. `Observation<T>` remains a
sum type over `Measured`/`Absent` — **no `Default`, no `unwrap_or(0.0)`, anywhere.**

**Cohort discipline (R-8.2 / R-10.1 / R-13.1 — three prior failures of the same shape).** Three
populations are in play and must never be conflated in any report:

| population | n | contains |
|---|---|---|
| pinned gate cohort | 20 | AAPL AMZN MSFT T + 16 small/mid caps. **AMZN and MSFT are the only anchors in it** |
| `PROBE_COHORT` | 28 | all four anchors; overlap with the pinned 20 is `{T, MSFT, AMZN}` |
| high-signal screener gate cohort | 26 | a third population; not touched by this branch |

**PG and GOOGL are invisible to the gate.** Any claim about anchor behaviour must name which cohort
produced it.

**Predicted P0s, absorbed or named.**
1. *(absorbed into W1a)* Pretax supply on the pinned 20 is unmeasured; without it W4's inertness is a
   hope.
2. *(absorbed into W4)* A naive fixture re-capture would rewrite the corpus and flip refusal reasons.
   The surgical enrichment plus the in-test diff proof plus fail-closed is the mitigation.
3. *(**v1 correction**)* v0 named `growth_posterior` as *"already reaching a published value"* — it does
   not (§1.1(b), R-49.1). The defect there is **scheduled for W6**, which is also the wave that mints
   the first goldens. W3 classifies it; nobody has planned a fix and this plan does not propose one.
4. *(**v1, promoted from named to blocking**)* LD-18: at least 26 fixture tax rows carry a rate SEC
   never filed, and the column cannot express absence, so the other 146 are unproven. **W4's entry gate
   2.**
5. *(named)* If H5 introduces a fade for `r`, the outline gains a column and the FR-44 cost is unpriced
   here.
6. *(named)* The persistence coefficients rest substantially on MSFT (R-50.4).

**Out of scope, restated:** AMZN policy/16; Android parity (Windows first; the generator still emits
both targets); the ROIC fixture; LD-2..LD-11, LD-14, LD-16; the two R-35.5 findings;
`worktree-agent-a19c1b1e4036e2768`; `measure-guard-rules`. **None of the four known failures is to be
fixed.**

---

## 4. Where I am uncertain — holes to fill, not guesses

1. **A4 is inferred, not read.** Whether `DriverAnnual`'s tax fields are already `Option`-shaped comes
   from v0's `row.pretax?` being written as *mirroring* `marginal_tax_bps?`. **W4 confirms it by reading
   the type before writing anything.** If wrong, W4 gains a type change and says so — this is exactly
   the R-40.1 shape and it is flagged rather than assumed.
2. **BWMN's `not_reported` cause is not directly confirmed.** Derived that it cannot be the base and
   cannot leave `not_reported` under W4 regardless. Settle with
   `core_versus_current_engine_on_the_pinned_cohort` if you want it nailed down. **The design does not
   depend on the answer.**
3. **W2-P02 has no isolating mutation** (§W2). Stated rather than filled with a mutation that moves
   three rows and a claim of isolation.
4. **The cash lattice's containment claims are read from the concept names**, not measured. If T1a.3
   shows a year filing both the aggregate and a part with values that contradict rule 1, that is a
   finding about the lattice and a stop, not a case to resolve by preference.
5. **Whether the `E`-slot type discipline belongs in the Core or the adapter.** The adapter was chosen
   and the alternative's cost is stated (§1.3). A reviewer who reads R-30.1's *"a distinct type"*
   literally will want the Core newtype; that is defensible and it is Juan's call, not a builder's.
6. **Cucumber scenario counts** are not stated as absolutes. Every wave baselines its own tree (R-9.3)
   and quotes which commit.
7. **Round 14's P19 is not in hand at the time of writing.** W4's branch is therefore written as a
   registered decision rule rather than a choice. That is the honest state; filling it in with a guess
   would be the defect this plan spends W4 repairing.

---

## 5. Registered, with a trigger, but not in this plan

**Unit vocabulary across the Core's public surface** (`Money`, `Rate`, `Ratio`, so `g/r` cannot silently
become `r/g`). Sensei proposed it and the argument is correct — it is a better type investment than the
`E` newtype. It is also a rewrite of a public boundary in the middle of a branch that has not published
a single value, and it protects against a transposition no test has ever caught happening.
**Trigger: the first addition of a fifth rate-shaped input to the Core's public surface.** (R-49.4.)

**Rejected, with the reason, so it is not silently re-proposed:** Sensei's four-issuer realized-
reinvestment oracle for W6's pre-registration. The idea is right in shape, but R-41.4 established that
realized `b` is negative for **14 of 21** issuers on this cohort, and a reference that is not
sign-identified on three quarters of the population is not made sound by restricting it to the quarter
where it happens to behave. If those four are to be an oracle, the case must be made on why they are a
legitimate population rather than the ones that survived. (R-49.4.)

---

## TL;DR

**v1 changes three things v0 got wrong and adds two decisions v0 did not know existed.** `growth_posterior`
has **no production consumer** — v0 contradicted itself and the trimming defect is **scheduled, not
live**. **W1 splits** so a doc relabel cannot block the wave W4 waits on. And **W4 does not proceed as
v0 scoped it**: Round 13 found **0 of 33** audited fixture tax rows genuinely filed and **26 filed
nothing at all**, against a **10/10 clean control** — and since all 274 rows carry a non-null rate while
production resolves `<none>` on at least 33, **the column cannot express absence**, so the 146 that look
right are unproven. W4 now has an entry gate and two declared branches, chosen by Round 14's P19 under a
rule registered before the number. **W2 gains a fourth row** so R-49.3's unfaded-`r` finding becomes a
committed pair — **1926.38 against 1250.00, 54%, one direction**. **W5's dependency is made honest** (the
qname set is registered before its coverage is read) and its **containment lattice is declared pair by
pair**, because double-counted cash moves value **upward, always**. **W6 gains H4** — the window
collapsed into `gross` vs `oper`, since `E5` sweeps six of six under `oper` and `E2` takes `gross`,
with identical coverage so the registered tiebreak is empty — and **H5**, `r`'s fade, which is upstream
of all of it. Options (i) and (iii) are **dead by measurement**, twelve of twelve. Seven honest holes
listed at the end rather than guessed.
