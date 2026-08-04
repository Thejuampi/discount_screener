# valuation-core

The pure valuation kernel. No I/O, no clock, no network, no market price.

## The contract is the tables

`tests/features/*.feature` is the specification, not documentation of one.
Behaviour is added by **adding a row to an existing `Examples` table**. Creating a
new `Scenario Outline` is the exception and costs an entry in
`tests/features/manifest.toml` stating what no existing table covers.

That rule is enforced, not merely stated. `tests/schema.rs` fails the build on:

| rule | what it rejects |
|---|---|
| outlines only | a bare `Scenario:` anywhere in a feature file |
| leading `case` column | an `Examples` table keyed on anything else |
| rectangular rows | a row whose cell count differs from the header |
| one spelling of absence | `-`, `n/a`, `null`, `none`, `TBD`, `?`, empty — only `ABSENT` passes |
| unique cases | two rows sharing a `case` identifier |
| justified outlines | an outline with no manifest entry, or an entry naming no outline |

Each of those six was verified by injecting the violation and confirming the suite
goes red. An enforcement test that has never failed is not enforcement.

The cucumber runner sets `fail_on_skipped()`: a row whose step has no definition
fails the build rather than reporting green with the row quietly skipped.

## Running

```
cargo test -p valuation-core          # unit + outlines + schema
cargo test -p valuation-core --test cucumber   # the contract alone
```

## Why absence is a type

`Observation::Absent` carries a reason and has no numeric reading. There is no
`unwrap_or(0.0)` and no `Default`. In the previous engine a contaminated growth
history read as "will never grow again" and an unavailable forecast read as
"forecasts nothing"; both were the same type error. Here an absent channel has
precision exactly zero, so it contributes nothing to a fusion and the posterior
falls back to whatever else is present — with no special case anywhere in the
fusion code.

## Status

Under construction, and **not yet wired into the running application**. The Shell
still values with the deprecated modules. Landed so far:

- `evidence` — `Observation`, `Uncertainty`, `Provenance`, `AbsenceReason`
- `posterior` — inverse-variance fusion, largest-remainder weight reporting
- `capital` — credit-curve cost of debt and WACC, with delta-method propagation
- `projection` — the discounted cash-flow path in closed form, with the
  retention charge and no explicit horizon
- `classification` — closed-world business class, matched on whole words
- `residual_income` — book plus its spread over the cost of equity, for lenders
- `numerics` — the fading-path series both models sum, and its conditioning bound
- `attribution` — which input a valuation's width is charged to
- `publication` — per-share percentiles and the fixed-point boundary

That is the whole kernel. What remains is the Shell adapter: building the
evidence bundle from provider data, fitting the credit curve and the fade rate
cross-sectionally, and re-measuring the cohort.

### There is no Disputed

The old engine refused to publish when its two lanes disagreed by more than
5000 bps. In all nine issuers it refused this way, the forward lane was the
closer one — so the disagreement was information about *width*, and suppressing
the publication threw away the estimate and the uncertainty together.

`Refusal` has exactly two variants, **Eligibility** and **Evidence**, and no
threshold on interval width, channel disagreement or distance from any price can
construct either. That is FR-32 enforced by the type rather than intended by a
comment.

### A bank does not get a worse cash-flow model, it gets a different one

Free cash flow to the firm subtracts capital expenditure and discounts at a
weighted average cost of capital. Neither half means anything for a lender: debt
is the raw material its assets are made of, not the financing of them, so there
is no enterprise value to bridge and no leverage the credit curve could read.
Running the operating model on a bank does not produce a worse number, it
produces a number about a different quantity.

`residual_income` values those issuers as book plus the present value of what
they earn above their cost of equity. The spread fades at the same rate book
growth does, because a competitive advantage eroding is one event rather than
two, and fitting it twice would invent a parameter the cross-section cannot
estimate.

### There is no terminal value module, on purpose

Terminal value is not a separate calculation here. Growth relaxes continuously
toward the terminal rate and value is the integral over all future time, so the
"terminal value" is the limit of the same integrand — which is why `projection`
has a closed form and no horizon appears anywhere in it. The seam between an
explicit projection and a bolted-on terminal value was where the old engine's
`derive_hold_years` / `derive_fade_years` branches lived, and removing the seam
removes them rather than reimplementing them.

### Growth persistence was cut, not deferred

The PRD specified an Ornstein-Uhlenbeck fade with a per-issuer mean-reversion
speed `kappa = -ln(rho_1)`. A probe over 28 names measured `rho_1` on realized
annual revenue growth: **median 0.003**, 14 of 28 at or below zero, median
`se(rho_1)` 0.25. It is not estimable per issuer, and where it is positive the
implied half-life is ~0.47 years against a shipping engine that fades over 5–10.

`rho_1` was computed on deviations from each firm's *own* mean growth, so this
does not say firms lack persistent growth differences. It says those deviations
are noise, and the persistent estimable quantity is the **mean growth level** —
whose standard error is measurable, and which is exactly what the growth
posterior already fuses. The OU kernel, `kappa`, and the half-life are removed
from the design rather than postponed.

Re-run the probes with:

```
cargo test --lib probe_analyst_dispersion_availability -- --ignored --nocapture
cargo test --lib probe_growth_persistence_rho1        -- --ignored --nocapture
```

## Honest caveats

Inverse-variance fusion is minimum-variance only for *unbiased* channels. A
trailing channel is systematically stale; an analyst channel is systematically
optimistic. Tight analyst dispersion may also signal herding rather than
knowledge, in which case this estimator weights correlated bias *up*. Neither is
resolved by the arithmetic, which is why calibration is gated on residual
structure against realized outcomes rather than on the formula being pretty.
