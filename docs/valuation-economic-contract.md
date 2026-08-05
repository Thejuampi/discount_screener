# Valuation economic contract

The canonical home of the latent-defect register: what the valuation rebuild knowingly does not fix
yet, named so that each item is never later mistaken for an oversight. Established by binding
decision D7 of the valuation PIT & contract run (`valuation/wave1-integration`), written by Wave 5
(`AD-VM-012` in `_bmad-output/planning-artifacts/valuation-model-family-architecture.md` links here
and summarizes it).

This document is **living**: its ownership belongs to this plan's successor run, and the register
grows as later work finds and defers new items. It does not belong embedded in a point-in-time
decision record that a later wave would otherwise have to keep re-editing.

Wave 4 (Round 4) extends this file with the brief's full economic-contract enumeration — what
NOPAT, invested capital, reinvestment, growth, `g`, `r` and absence mean in this repository, at the
filed-concept level, and what the identity is that ties them together. **No estimator comparison or
target pre-registration is valid until this contract exists** (the brief's own gating language). It
does not repeat product decisions recorded elsewhere; it defines the quantities those decisions are
about.

---

## 1. NOPAT

**NOPAT (net operating profit after tax)** is defined, at the filed-concept level, as:

```text
NOPAT = (PretaxIncome + InterestExpense) x (1 - MarginalTaxRate)
```

Each filed-concept input:

- `PretaxIncome` — `pretax_income_dollars` on `FcfPoint`, resolved through the existing SEC
  equivalence class for pre-tax income.
- `InterestExpense` — `interest_expense_dollars` on `FcfPoint`, resolved through
  `INTEREST_EXPENSE`'s equivalence class (`sec_driver_normalization_policy_generated.rs`), now
  basis-keyed per year after LD-8's closure (§14).
- `MarginalTaxRate` — `marginal_tax_bps` when filed for that year; `STATUTORY_MARGINAL_TAX_BPS`
  when it is not. A policy default standing in for a missing filed rate is provisional and must
  carry that provenance forward, not be read as a measured tax rate.

Interest is added back **before** tax, then the whole sum is taxed at the marginal rate — this is
what "after tax" modifies: the return the business generates before financing decisions, not net
income with interest re-added post-tax. This is the formula the T2.0 probe
(`valuation_probes.rs::probe_return_on_capital_availability`) already computes and reports per
issuer-year; this contract is that formula's specification, not a new one invented for the
document.

**Units:** dollars (filed reporting currency; the pipeline is USD-only per the SEC FCFF driver
normalization boundary — see §8).

---

## 2. Invested capital — two competing definitions, and which one this project uses

There are two standard ways to build up invested capital, and they are supposed to agree (both are
"what capital the business employs") but disagree in practice whenever off-balance-sheet financing,
netting conventions, or non-operating assets differ from the operating footprint:

1. **Operating-asset build-up** — net working capital (excluding cash and short-term debt) plus net
   PP&E plus other identified operating assets (goodwill, operating leases, capitalized
   intangibles, by policy). Built from the asset side of the balance sheet, restricted to operating
   items.
2. **Financing-side build-up** — total debt plus total equity (by convention, sometimes net of
   cash). Built from the liabilities-and-equity side: whatever financed the business, operating or
   not.

**This project uses the financing-side build-up:**

```text
InvestedCapital = StockholdersEquity + TotalDebt
```

**Why.** Both terms are already driver-resolved through existing SEC equivalence classes
(`STOCKHOLDERS_EQUITY`, the debt concepts feeding `total_debt_dollars`) with point-in-time
provenance, so no new equivalence-class work is required to measure it. The operating-asset
build-up would need net-PP&E and working-capital equivalence classes that do not exist yet in this
pipeline — a real build, not a definitional choice. The financing-side quantity also composes
directly with the reinvestment identity in §10: capital *raised* (debt issued, equity retained or
issued) is the same pool of dollars the identity's `ReinvestmentRate` describes as funding growth,
so the two sides of the identity are measured the same way.

**The known limitation, registered rather than smoothed over (LD-4, §14):**
`stockholdersEquity`'s equivalence class currently mixes NCI-inclusive and NCI-exclusive concepts —
one line, two measurement bases, under rule R2 (§13). Any issuer with a material minority interest
therefore has an invested-capital figure whose basis is not yet resolved. §14's LD-4 row is the
audit this must clear before an estimator is pre-registered against filed equity; `docs/roic-target-specification.md`
(T4.5, row 2) records that resolving the NCI basis is a precondition of pinning "invested capital"
at all.

**Units:** dollars (filed reporting currency).

---

## 3. Reinvestment — and organic investment distinguished from it

**Reinvestment**, from the flow statements, is what the identity in §10 means by `Reinvestment`:

```text
Reinvestment = NOPAT - FCFF
```

This is a *realized*, filed-evidence quantity — the T2.0 probe calls it `b = (NOPAT - FCFF) / NOPAT`
— and it is deliberately **not** the same as the change in invested capital, `ΔIC`. `ΔIC / NOPAT`
(the probe's `b_cap`, "capital formation") captures every source of capital growth: retained
earnings, debt raised, shares issued, and businesses bought. Measured on the probe cohort, `b_cap`
exceeded 1.0 for ten of twenty-one issuers — capital was being *formed* faster than NOPAT could
plausibly retain, which is evidence of external financing and acquisition activity, not organic
reinvestment. `b = (NOPAT - FCFF)/NOPAT` isolates the smaller quantity: the portion of NOPAT the
business itself declined to distribute as free cash flow, which is what the growth/return identity
in §10 actually charges against.

**Organic investment** is the subset of `Reinvestment` that funds growth in the *existing* business
through ordinary operating capital expenditure and working-capital build, as opposed to capital
deployed into acquiring a different business (§4) or into non-operating uses. The current pipeline
does not yet separately tag a driver as "organic" versus "acquired" capital at the reinvestment
level; §4 states the one place acquisitions are already excluded (the revenue-growth transition),
and `docs/roic-target-specification.md` (T4.5, row 4) records that this gap is a decision that must
be pinned, not assumed, before any target built on `Reinvestment` is frozen.

**Units:** dollars for the flow quantities; `Reinvestment / NOPAT` is a unitless ratio (equivalently
expressed in bps, matching `g` and `r`'s unit in §6).

---

## 4. Acquisitions and divestitures

**Acquisitions.** Per the SEC FCFF driver normalization boundary (`AGENTS.md` → "SEC FCFF driver
normalization"), business/property acquisition cash is a **rejected evidence class**: it stays
visible in provenance but is never added to FCFF, and material acquisition cash in fiscal year `Y`
contaminates only the revenue-growth transition from `Y-1` to `Y` — that one transition is excluded
from the growth fit rather than the acquisition dollars being folded into any driver. This is
already implemented and already the reason `capex_imputed` and acquisition-normalized provenance
exist on `FcfAnnual` and its callers.

**Divestitures.** No driver in this pipeline currently resolves proceeds from divestitures or
disposal-group activity — there is no `ProceedsFromDivestiture`-class equivalence entry anywhere in
`shared/contracts/sec-driver-normalization.json` or its generated output. Per constraint 5 (absence
never becomes a fabricated zero), this means divestiture proceeds read as **absent**, not as zero:
an issuer that divests a segment has that cash simply missing from every driver that would otherwise
have counted it, rather than counted as $0. This is a real gap, not a decision that divestitures are
economically neutral, and `docs/roic-target-specification.md` (T4.5, row 6) records it as an
exclusion rule the target specification must state explicitly rather than let ride silently into an
estimator.

---

## 5. Capital-consumption treatment

Two different treatments of "capital consumed" coexist in this codebase today, and the contract
states both rather than picking one to be quiet about the other:

1. **Implicit netting (the Core's identity, §10).** `FCFF = OCF - CapEx`, and `OCF` already carries
   depreciation and amortization added back to net income under the indirect method. So
   `Reinvestment = NOPAT - FCFF` (§3) nets D&A's non-cash add-back against gross CapEx: what remains
   is *net* new capital deployed, not gross spend. Capital consumption is never isolated as its own
   line — it is absorbed inside the OCF-to-FCFF bridge before `Reinvestment` is ever computed. This
   is the treatment `g = b x r` in §10 actually measures against, because it is what the filed flow
   statements make measurable without inventing a maintenance-versus-growth split.
2. **Explicit sustaining/growth split (legacy engine, `dcf_model.rs::maintenance_capex_intensity_bps`,
   D5-scoped).** The legacy Shell engine separately estimates a "sustaining" share of CapEx —
   `sustaining = capex * renewal / (renewal + growth)` — as the portion that merely replaces
   consumed capital, and treats the remainder as growth capital. This is a *different* capital-
   consumption model than §10's identity: it is an estimate layered on top of gross CapEx, not a
   quantity read off the flow statements the way NOPAT and FCFF are.

**This contract adopts treatment 1 for the reinvestment identity.** It is what is directly
measurable from filed evidence without an additional estimator, and it is the treatment the T2.0
probe already reports against. Treatment 2 is not wrong, but it is a candidate refinement that would
itself need to clear the pre-registration process in `docs/roic-preregistration.md` before it could
replace treatment 1 — it is not adopted here by default just because it already exists in the legacy
engine (§15, D5).

---

## 6. `g` and `r`

- **`g`** — the instantaneous growth rate of NOPAT (or, in the legacy engine's separate model,
  of FCFF/earnings — see §15 for where the two diverge). Continuous-time, matching `projection.rs`'s
  `g(t) = g_inf + (g_0 - g_inf) * exp(-k t)`.
- **`r`** — the return on invested capital, §1 over §2: `r = NOPAT / InvestedCapital`, taken as a
  robust centre over an issuer's annual history (§10, §12), not a naked mean.

**Units.** At every Core-facing boundary (`Observation<f64>` arguments named `*_bps` in
`projection.rs` and `residual_income.rs`), both `g` and `r` are **basis points** (`1 bps = 0.01%`,
integer-valued at the driver-resolution boundary, `f64`-typed once inside the Core). Internally, the
continuous-time projection divides by `10_000.0` to recover the fractional rate before it enters an
`exp()`. A caller that skips the `/10_000.0` conversion, or that supplies a fraction where bps is
expected, produces a value off by two orders of magnitude with no type-level guard — this is stated
explicitly because it is exactly the kind of unit mismatch a review pass across module boundaries
should check for by inspection, not assume.

---

## 7. Expected timing between investment and return

The economics this Core formalizes assume **zero lag, continuously**: reinvestment charged against
earnings at instant `t` is assumed to begin earning `r` starting at that same instant, not after a
build-out delay. This falls directly out of the closed-form integral in `projection.rs`:
`C(t) = E(t) x (1 - g(t)/r)` is the owner's cash flow at every instant `t`, with no separate lagged
term for capital deployed at `t` reaching productive return at `t + lag`. `V = integral_0^inf C(t)
e^{-w t} dt` integrates this same, undelayed relationship over the whole horizon.

This is a real modeling assumption, not an oversight: a genuinely lagged reinvestment-to-return
relationship (capital deployed this year earning nothing until year `t+2`, say) would require a
different closed form or a discretized horizon, and neither is implemented. Any research candidate
that wants to test a lagged assumption is proposing a different model of `r`'s productive timing,
not a different estimator of the same `r` — that distinction belongs in
`docs/roic-research-charter.md`, not silently inside a pre-registered comparison.

---

## 8. Valid units

| Quantity | Unit | Where enforced |
| --- | --- | --- |
| `NOPAT`, `FCFF`, `Reinvestment`, `InvestedCapital` | dollars, filed reporting currency (USD-only; non-USD/non-consolidated evidence is unavailable, not converted or zeroed — SEC FCFF driver normalization boundary) | `edgar.rs` driver resolution |
| `g`, `r`, `w` (discount rate) | basis points at the Core boundary (`*_bps: Observation<f64>`); converted to a fraction (`/10_000.0`) for the continuous-time integrand | `projection.rs`, `residual_income.rs` |
| `k` (fade rate), `fade_per_year` | per year, continuous | `projection.rs::FadePath` |
| time (`t`, horizon, span) | years, continuous (`f64`) — no discrete hold/fade period (FR-18, FR-19) | `projection.rs` |
| `variance_of_centre` | squared standard error of the centre, in the centre's own squared units — never a raw sample variance | `numerics.rs::RobustCentre` |
| money at the Shell/UI boundary | fixed-point integer cents (`*_cents`) | `AGENTS.md` → Conventions |

A quantity crossing a boundary in the wrong unit (bps read as a fraction, or vice versa) is a
correctness defect of the same class as an absence read as zero — both invent a value the evidence
did not supply.

---

## 9. Valid absence states

`valuation-core::evidence::AbsenceReason` is a closed, six-variant enum. Each variant is a **claim
about why** a quantity is absent, not a category of convenience picked because it was the nearest
match — reusing the wrong variant states an incorrect reason as confidently as reusing the wrong
value states an incorrect number (D3's own argument against reusing `NotReported` for
`EstimatorUnavailable`).

| Variant | `as_str()` | The situation that produces it |
| --- | --- | --- |
| `NotReported` | `not_reported` | The provider (SEC filing, analyst feed) does not carry this field for this issuer at all — a genuine filing gap. |
| `ContaminatedPeriod` | `contaminated_period` | Periods exist but are not usable as evidence of the underlying process: an acquisition-contaminated growth transition (§4), a restated basis. |
| `InsufficientObservations` | `insufficient_observations` | Fewer usable periods survive than the statistic requires (e.g. `robust_centre`'s minimum of three retained observations). |
| `ProviderUnavailable` | `provider_unavailable` | The provider was reachable but declined or failed to supply the field for this issuer — distinct from `NotReported` because the request itself failed rather than the concept never existing in the filing. |
| `OutOfPolicyRange` | `out_of_policy_range` | A value arrived but fell outside the range the policy admits (e.g. a Gordon-identity violation on `g_stable >= r`). |
| `EstimatorUnavailable` | `estimator_unavailable` | A required estimate exists as a *quantity* (return on capital, return on equity), but no validated estimator can supply it for this issuer today. Distinct from every reason above: the provider is not at fault, and nothing is missing from the filing — the gap is in this Core's own evidence chain (D3). |

The rule that ties the table together: **a reason names a cause, and the cause must be the true
one.** `EstimatorUnavailable` exists specifically because `NotReported` would have been a false
statement about return on capital — the filing is complete; the Core simply has no promoted way to
turn it into `r` yet (§0, brief; D3). Every future absence must be routed to the variant whose prose
actually describes what happened, even when a nearer-sounding variant would compile.

---

## 10. The relationship between growth, return and reinvestment

The identity the brief formalizes, and the one this whole contract exists to make measurable:

```text
FCFF = NOPAT - Reinvestment
ReinvestmentRate = g_NOPAT / r
FCFF = NOPAT x (1 - g_NOPAT / r)
```

and the Core's retention charge, FR-28, is the same identity applied continuously to the whole
projection path:

```text
C(t) = E(t) x (1 - g(t) / r)
V = integral_0^inf C(t) e^{-w t} dt
```

**The sequencing fact, stated so no future change re-introduces the error silently:**

- A **NOPAT base alone** — valuing off NOPAT with no reinvestment charge — charges reinvestment
  **zero times**, and **overvalues**: it pays the owner every dollar of operating profit while the
  business is simultaneously plowing part of that profit back into growth capital.
- **ROIC alone on an FCFF base** — applying a return-on-capital-implied growth charge to a base that
  is already post-reinvestment (FCFF) — charges reinvestment **twice**: once implicitly, because
  FCFF already subtracted it, and again explicitly, because the `(1 - g/r)` retention factor
  subtracts it a second time.
- **Both together, base and charge matched** — a NOPAT base with the `(1 - g_NOPAT/r)` retention
  charge — charges reinvestment **exactly once**, which is what §3's `Reinvestment = NOPAT - FCFF`
  and FR-28's `C(t)` both require: the base and the charge must be measured on the same footing, or
  the double- or zero-count above reappears silently. This is the reason the adapter change that
  would pair a NOPAT base with a measured `r` is explicitly out of this run's scope (brief §2) —
  landing one half without the other reintroduces exactly this error.

---

## 11. Financial-company semantics

Ordinary invested-capital definitions (§2) do not apply to `BusinessClass::FinancialServices`
issuers, and this section is the contract's explicit statement of why and what governs them
instead — omitted entirely from v1, and directly load-bearing for COF, which carries the most
affected years of any issuer in this run (Wave 2).

**Why the ordinary definitions break.** A bank's or insurer's balance sheet *is* its inventory:
loans, deposits, float and reserves are the operating business, not financing layered on top of it.
`TotalDebt` for a bank includes customer deposits, which are not "capital raised to fund growth" in
any sense §2's identity assumes — treating them as such would report absurd leverage and an
invested-capital figure with no economic meaning (the codebase's own standing example: a 573x
debt/equity ratio turning a 442% ROE into an arithmetic-noise "0.8% return on capital",
`operating_valuation_runtime.rs`). `NOPAT` (§1) is similarly the wrong lens: "operating profit before
financing costs" presumes financing and operations are separable, which for a bank they are not —
interest income and expense **are** the operating business.

**What governs financial issuers instead.** `BusinessClass::FinancialServices` issuers are valued
through **residual income on book equity** (`residual_income.rs`, `dcf_model.rs:1263-1265`), not
FCFF. The return quantity is **return on equity**, not return on invested capital, and the base is
**book equity**, not the financing-side build-up in §2. `residual_income_value`'s absence contract
mirrors §9 exactly but with its own discriminating reason: a bank refusing on an absent return
surfaces as `evidence/provider_unavailable`, not `evidence/estimator_unavailable` — a deliberate
distinction (D3) so a reader of the refusal reason can tell a bank's provider gap from an operating
issuer's estimator gap without inspecting the business class separately.

**Consequence for §2's invested-capital identity.** `FinancialServices` issuers do not participate
in the `NOPAT`/`InvestedCapital` measurement this contract defines at all — the identity in §10 is
an FCFF-lane identity, and the residual-income lane has its own return quantity (ROE) and its own
base (book equity), never mixed with §1's `NOPAT` or §2's financing-side `InvestedCapital`. A
research candidate or target specification that silently applies §1/§2's definitions to a financial
issuer has misclassified the issuer, not found a new edge case.

**Other issuer classes where ordinary definitions do not apply.** `BusinessClass::NotEligible`
(ETF, fund, crypto shell, REIT, ...) and `BusinessClass::Unclassified` (missing or uncatalogued
sector/industry) carry no invested-capital or NOPAT semantics at all — both are closed-world
refusals (`AGENTS.md` → Valuation Model Family), and neither §1 nor §2 is meaningful for them.

---

## 12. Growth: what is being grown, over what base, and why one fade rate governs both paths

**What is grown.** The identity in §10 is stated in terms of `g_NOPAT` — NOPAT growth — because
that is the quantity the retention charge `C(t) = E(t) x (1 - g(t)/r)` needs: `E(t)` is the earnings
base the owner's cash flow is carved out of, and it must compound at the same growth rate the
retention charge is stated against, or the two terms describe different quantities wearing the same
symbol. `docs/growth-research-charter.md` (T4.3) is where the *measurement* of `g_NOPAT` — as
opposed to the revenue-growth proxy currently fitted — is scoped as its own research programme.

**Over what base.** Growth is measured as the log-growth of the earnings series itself
(`(last.nopat / first.nopat).ln() / span` in the T2.0 probe's own arithmetic), cross-sectionally
pooled and centred with `robust_centre` (§13; D2), never a naked mean of a per-issuer series.

**Why the fade rate governs both the growth path and the spread's erosion.** `FadePath`'s single
parameter `k` (`fade_per_year`) does two jobs in one number: it is the rate at which `g(t)` relaxes
from its current level `g_0` toward the terminal rate `g_inf` (`g(t) = g_inf + (g_0 - g_inf) *
exp(-k t)`), **and**, because the retention charge is `(1 - g(t)/r)`, it is simultaneously the rate
at which the *reinvestment spread* — the gap between the return the business earns and the return it
needs to sustain growth — erodes toward its own steady state. A single persistence parameter
therefore encodes an assumption that a business's growth advantage and its capital-efficiency
advantage fade at the same pace. That is a modeling choice, not a mathematical necessity, and it is
exactly the "one parameter, two jobs" fact `docs/growth-research-charter.md` must carry into any
growth-channel research, because a candidate that improves the growth fit alone can silently move
the spread-erosion assumption too.

---

## 13. R1 and R2 — the two equivalence-class rules

An `select_one_equivalent` list (`sec_driver_normalization_policy_generated.rs`) merges qnames in
declared order and fills gaps only — a wrong-statement or wrong-basis concept placed late in the
list silently splices into a series that looks continuous. Two rules bound what may share a list:

- **R1 (existing) — an equivalence class holds one statement's concept only.** A cash-flow
  disclosure is not an equivalent of an income-statement accrual, even when both describe "interest"
  in English. **Example:** `InterestPaidNet` — a cash-flow-statement *paid* figure — sat inside the
  interest-**expense** (income-statement accrual) qname list and gap-filled an accrual series year
  by year; for at least one issuer it was the *only* source across nine years. Removed; equivalence
  classes now hold one statement's concept only, and an issuer that files none for that statement
  reads absent rather than substituting from another statement.
- **R2 (new) — an equivalence class holds one measurement basis only.** A netted concept enters the
  class only through a **declared sign convention** mapping it onto that basis; absent a declared
  convention it reads absent, not equivalent. **Example:** `InterestIncomeExpenseNet` is filed under
  the *same* qname by issuers on opposite sides of the same economic line — LIN files it negative
  every year 2022-2025 (`-63M, -200M, -256M, -255M`, an exact negation of `InterestExpenseNonoperating`'s
  positive `+63M, +200M, +256M, +255M` in the same years), while BAC files it **positive**,
  `+60,096M` for 2025, because BAC's net position is net interest *income*, not expense. The same
  qname, same class, opposite sign meaning — a class with no declared sign convention cannot resolve
  both correctly by any single rule, which is exactly why the contract vocabulary carries an explicit
  `negatedQnames` / `qname_signs` array rather than inferring sign from the filed value.

**Second-order consequence.** Once R2 exists, it binds every other `select_one_equivalent` list, not
only the one it was written for. `stockholdersEquity` mixes NCI-inclusive and NCI-exclusive
concepts — including versus excluding non-controlling interests is a *different basis on the same
line* — and that audit is §2's LD-4, a named follow-up rather than a closed question.

---

## 14. The latent-defect register

Every trigger below carries either a mechanical detector — a test, a probe, an assertion — or an
explicit human-review checkpoint with an owner. Where neither exists, the row says so plainly,
rather than implying an enforcement that is not there.

**How this register was found incomplete, recorded because the omission is the more instructive
half.** The wave that built this section verified that the register *survived* its edit — LD-8
struck, prior rows intact, nothing invented. It did not verify that the register was *complete*
against the run's own carried-item list, and the acceptance check that was supposed to protect it
measured survival rather than completeness. Five items the run had named, numbered, and explicitly
carried forward — including the one it called its most durable finding — were absent when a reviewer
grepped for them. They are LD-12 through LD-16 below. A register that looks complete is worse than
one with an honest gap, and this register looked complete for exactly one review cycle. Any future
statement of the form "the register is LD-2 through LD-11" is stale by this edit and should be read
as a symptom, not a fact.

| Id | Defect | Why not now | Trigger | Detector |
| --- | --- | --- | --- | --- |
| ~~**LD-1**~~ | ~~blanket `.map(f64::abs)` on `interest_expense_dollars`~~ | **CLOSED BY WAVE 2.** Juan's Q1 ruling pulled it into scope; all three sites removed. | — | `W2-R01` fails if an `.abs()` is ever restored |
| **LD-2** | `resolve_capex_abs` returns a zero CapEx when no series exists (`edgar.rs:604-607`) — a real fabricated zero on the production FCF path. | On the production FCF path; would move published anchors. | Any wave that touches the CapEx-to-FCF bridge. | **Human review checkpoint**, owner below. No mechanical detector exists; stated plainly rather than implied. |
| **LD-3** | `operating_valuation::terminal_payout_bps` substitutes the cost of equity for an absent return on capital (`:223`) — the legacy FR-29. | Decision 2 allows the legacy engine to stay live during module-by-module replacement. | Retirement of the legacy engine, or the first router row whose decision inverts on the substitution. | Wave 5's `the_legacy_engine_still_substitutes_the_cost_of_equity_for_an_absent_return` fails the moment the substitution changes. The *router-inversion* half has **no** detector — human review. |
| **LD-4** | `stockholdersEquity`'s equivalence class mixes NCI-inclusive and NCI-exclusive concepts — one line, two measurement bases, under R2. | Out of this run's scope; changing it moves invested capital for every issuer with a material minority. | R2 is adopted (Wave 2); the audit is due before any invested-capital estimator is pre-registered against filed equity. | The target specification that pins "invested capital" cannot be written without resolving the NCI basis, so writing it forces the audit. |
| **LD-5** | `variance_of_centre` remains a mild understatement: the retained sample is narrower than the population it estimates. | The alternative — a MAD-based scale over the full sample — would describe a *different estimator* than the one that produced the point. The perverse monotone-in-contamination component is fixed (Wave 3); only the residual bias remains. | The first forward channel that fuses against the trailing channel. | **Human review checkpoint** at the point `fuse` gains a second live channel. No mechanical detector. |
| **LD-6** | `AnnualSeries::as_of` resolves single-concept drivers only; composed drivers (total debt, FCF, development) carry provenance but have no cutoff-aware resolution. | The rolling point-in-time harness is out of scope, and composing inside the vintage layer is its own design. | Construction of that harness. | **Mechanical**: `extract_driver_vintages` has no composed-driver caller, so the harness cannot be built without hitting this. |
| **LD-7** | `interest == 0` with `debt > 0` is dropped from the accounting cost-of-debt fit. Either a genuine zero-coupon situation or missing data; the two are not distinguished. | T2.7 ruled on the negative case and declined to re-adjudicate the zero case in the same wave. | Any wave that revisits `resolve_rate_inputs`. | **Human review checkpoint**, owner below. |
| ~~**LD-8**~~ | ~~T2.7's sign rule was one-sided: refusing on `interest < 0.0` caught net-**income** filers but never fired for a net-**expense** filer, whose `InterestIncomeExpenseNet` series is equally net and was fitted as though it were gross interest expense — understating the cost of debt for that issuer with a plausible-looking number.~~ | **CLOSED, at commit `f38fe2c`** (*"fix(cost-of-debt): a netted interest year is not a measurement of gross interest"*). `FcfPoint` now carries per-field concept provenance; `driver_resolution.rs` keys on the filed basis and `winning_qname_is_net_basis` reads `INTEREST_EXPENSE.qname_signs` per year — the precondition the original entry named as unimplementable is now implemented. | — | Regression on `driver_resolution.rs`'s basis-keyed resolution would reopen this; no new detector was added because the closure is structural, not a rule this register enforces going forward. |
| **LD-9** | `posterior::measured_sample_size` (`posterior.rs:119-125`) sums `basis().sample_size()` across channels without regard to variant, adding `SampleVariance{observations}` (periods) to `AnalystDispersion{analysts}` (people) to `Propagated{inputs}`. The fused posterior is then labelled with that sum. | Cosmetic today: nothing consumes `sample_size()` arithmetically — `fuse` weights by `Observation::precision()` = `1.0/variance` only. It becomes load-bearing the moment any consumer reads the fused basis as an `n`. | The first consumer that reads a fused `UncertaintyBasis` for anything but display. | **Mechanical**: any new call site of `UncertaintyBasis::sample_size()` outside `posterior.rs`. |
| **LD-10** | Rust and Android disagree on the sign of net interest from Wave 2b onward. Rust negates at extraction and keeps the sign; `DcfAnalysisEngine.kt:535-541` and `:802` still apply an unconditional `abs()`. A real divergence, introduced deliberately by shipping one platform and not the other. | Windows ships first; Android is ported once the Windows solution is good (scope ruling, 2026-08-04). Porting the behavioural half now would ship a change to a platform whose model is not yet settled. | The Android port begins. | **Mechanical**, two one-line greps, either sufficient: (a) `DcfAnalysisEngine.kt:537` / `:802` still call `abs()` on interest; (b) the parity fixture still contains zero negative-interest rows. |
| **LD-11** | The cross-platform parity suite is an exporter, not a check, and its comparator is wired to nothing. Both `#[test]`s in `cross_platform_parity.rs` write JSON and assert only `path.is_file()` and `members.len() == 20` — no value assertion anywhere. The real comparison, `scripts/compare-windows-android-valuation-parity.ps1`, is invoked by nothing but its own self-test. | Wiring a cross-platform comparator into CI requires an Android build in the loop — precisely what the scope ruling defers. | The Android port begins, or any claim that "parity passes" is made about this suite. | **Mechanical**: `grep` for `compare-windows-android-valuation-parity` outside `scripts/` and the spec docs returns nothing. |
| **LD-12** | **No test in this repository fails when a published intrinsic value changes.** The observation audit dumps a cohort table and asserts nothing about the values in it; the parity suite asserts `path.is_file()` and `members.len() == 20`. A green suite is therefore not evidence that published values held, and every wave in this run that claimed "nothing published moved" proved it by pair-measuring two builds by hand, not by a gate. | Building the gate is a wave of its own: it needs an offline fixture, a blessed baseline, and a decision about how refusals are represented before any value is pinned. Doing it inside a wave that also changes economics would bless the change as the baseline. | Before the next wave that can move a published value — which is every remaining wave on the roadmap. | **None. Stated plainly: there is no detector, and that is the defect.** The nearest thing is `valuation_observation_audit`, which already computes the right numbers and discards them. |
| **LD-13** | `valuation_fixture_capture.rs:138,140` fabricates two fields it has no evidence for — `"effective_tax_bps": point.tax_rate_bps.unwrap_or(0)` and `"marginal_tax_bps": point.marginal_tax_bps.unwrap_or(2_100)` — three lines below a comment reading *"Filling any of them would be inventing the very history this capture exists to stop inventing."* A committed fixture therefore carries a 21% statutory guess and a 0% floor as though both were filed facts. Latent second half: `valuation_baseline.rs:72` declares `interest: f64`, while the emitter can now write `null` for it. | Fixing the emitter first would silently rewrite the committed fixtures, destroying the only record of what the old capture believed. **Sequencing is load-bearing: LD-12's gate must exist and bless current state before this is fixed, so the correction appears as a visible diff rather than as the new baseline.** | Immediately after LD-12 lands. | **Mechanical**: `grep -n "unwrap_or" valuation_fixture_capture.rs` returns these two sites and no others; the reader/writer half is caught by changing `interest` to `Option<f64>`, which fails to compile until every consumer is honest. |
| **LD-14** | COR's gross interest expense is **structurally unmeasurable**: all 18 filed fiscal years resolve through a concept in `negatedQnames`, so rule (D) drops every year and the accounting channel is empty for the issuer. No rule fixes this — the filer never reported the quantity. | This is not a defect to repair but a permanent evidence gap to represent. Repairing it would mean inventing gross interest from a net figure, which is the fabrication the whole run exists to remove. | Any proposal to "restore coverage" for issuers that lost the accounting channel under rule (D). | **Mechanical**: rule (D) itself. COR refuses, with a reason naming the empty fittable set. The risk is not that it breaks — it is that someone reads the refusal as a bug and relaxes rule (D) to clear it. |
| **LD-15** | `resolve_rate_inputs` has **no minimum observation count** on the accounting cost-of-debt fit (`!accounting_common.is_empty()`), and ROL fits 587bps from a single fiscal year. Worse, and separately: `rates[rates.len() / 2]` is the median at `n=3` and the **maximum** at `n=2` — verified by execution, not by reading (a two-period case returns 545bps where a centre is ~523). An issuer dropping `n=3 → n=2` keeps `EvidenceQuality::Solid`, gains no `provisional_wacc_uplift_bps`, and silently switches to an upward-biased cost of debt. This is a naked order statistic on a measured series, the family `robust_centre` exists to refuse. | The two candidate fixes have **different economic results and no test between them**: routing through `robust_centre` makes `n<3` refuse and removes published values from issuers that have them today; grading `n=2` as `Provisional` keeps them and prices the uncertainty instead. That is a Juan-category-(a) fork and is not the deferring party's to settle. | Any wave that revisits `resolve_rate_inputs`, and before any claim that the cost of debt is robustly estimated. | **Partial, and only at the wrong boundary.** `period_count >= 2 → Solid` else `Provisional` (`driver_resolution.rs:280`) makes the `n=2 → n=1` transition value-visible through `provisional_wacc_uplift_bps`. The `n=3 → n=2` transition has **no** detector. A characterization test pinning the 545bps behaviour is written and verified and should land with whichever wave takes this. |
| **LD-16** | `winning_qname_is_net_basis` (`edgar.rs:651-663`) — the sole gate for rule (D), the rule that moved BKR by +7889bps and flipped CHTR's lane — has **zero fast-test coverage**. Its only call site is inside `fetch_fcf_history`, reachable only through a live HTTP client. Second, contained hazard: `.first()` on `provenance.sources` is correct for `select_one_equivalent` single-concept drivers, where the array always has exactly one element, and is **quietly wrong** for a composed driver such as `extract_total_debt`, where several concepts contribute and `.first()` picks an arbitrary one rather than the winner. | The function was written and shipped inside the wave that needed it; adding a non-network test seam for `fetch_fcf_history` is a larger change than the rule itself and would have widened a verified wave. | Before any second caller of `winning_qname_is_net_basis` is added, and before any refactor of `AnnualSeries::merge` or `resolve_one_concept`. | **Mechanical for the hazard half**: `grep -c "winning_qname_is_net_basis"` returning more than 2 means a second caller exists and the `.first()` assumption must be re-proved. **None for the coverage half** — that is the defect. |

**Owner for all open items:** the valuation quant workstream (this plan's successor run).

**Register-wide risk, load-bearing for two waves.** F1 (`valuation_core_adapter::value()` has no
non-test caller) is a point-in-time property carrying both Wave 3's and Wave 5's live-QA posture.
The first wiring of `value()` to production silently invalidates the reasoning behind both waves'
anchor expectations. T5.12 converted F1 from a grep into a compile-enforced proof — gating `value()`
behind `#[cfg(test)]` locally and confirming the crate still builds — and it held.

---

## 15. What the legacy engine still does (D5)

`operating_valuation.rs`'s `terminal_payout_bps` substitutes the cost of equity for an absent return
on capital (`:223`) — the equivalent of the exact `r := w` substitution FR-29 removed from
`valuation-core`. **It is still live in production** and is unaddressed by this run: Decision 2
allows the old engine to remain live in the Shell as a separate legacy module during
module-by-module replacement, and this contract states the consequence plainly so no reader mistakes
FR-29's removal for the substitution being gone everywhere.

Concretely: the substitution feeds four rows of `shared/contracts/operating-valuation-router-v1.json`
(GDDY, WYNN, BSX, ALB) and is one of the three known-failing protected tests
(`operating_valuation::tests::durable_reported_and_holdout_cohorts_recompute_in_normal_gate`). Wave
5's characterization test,
`valuation_core_measurement.rs::the_legacy_engine_still_substitutes_the_cost_of_equity_for_an_absent_return`,
names the live fabrication rather than fixing it, and fails the moment the substitution changes —
this is LD-3 (§14), and every completion statement about this run must say, verbatim: *"FR-29
removed from `valuation-core`; the equivalent substitution remains live in the production path
(`operating_valuation.rs:223`, `terminal_payout_bps`) and is unaddressed by this run."*

---

## 16. Operating protocol

Standing rules that govern how this run — and its successors — communicate about the quantities
defined above, as distinct from the definitions themselves.

**The plus-or-minus 5 percent anchor trigger.** Juan's working protocol (brief §5) pauses the run
and asks him directly when *"an anchor (PG, GOOGL, AMZN, MSFT) moves more than +/-5% or changes side
of a gate."* This is stated here, in the economic contract's own operating-protocol section, rather
than in `docs/roic-preregistration.md`, deliberately: **it is Juan's stated instruction — a
communication trigger — not a derived quantity and not an acceptance criterion.** Nothing in §1
through §15 produces the number 5%; it is not propagated from any error-to-value derivation the way
`docs/roic-preregistration.md`'s materiality threshold is (T4.4, element 4). A non-derived,
non-gating convention sitting among the pre-committed decision rules of a pre-registration would
invite a later reader to treat it as pre-registered, which it is not — it belongs here, and in each
wave's own stated pause triggers, and nowhere that could be mistaken for a gate.

Anchors (PG, GOOGL, AMZN, MSFT) are diagnostics only under this same protocol: they appear in every
report this run produces and in no promotion gate (brief constraint 9).

---

## FR-29 — an absent return refuses, by a named reason

Wave 5 removed the substitution `r := w` (return on capital defaults to the discount rate when
absent) from both the operating (`projection.rs`) and residual-income (`residual_income.rs`) forms.
An absent return now refuses with `AbsenceReason::EstimatorUnavailable`, distinct from
`NotReported` because the gap is in this Core's own evidence chain rather than in the filing. See
`AD-VM-012` for the full decision record.
