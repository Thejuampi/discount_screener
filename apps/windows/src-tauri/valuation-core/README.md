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

Not yet built: growth persistence, projection, cost of capital, terminal value,
routing, publication. The cost-of-capital module is the one that needs a
cross-sectional fit over a universe — the defect the old engine cannot express.

## Honest caveats

Inverse-variance fusion is minimum-variance only for *unbiased* channels. A
trailing channel is systematically stale; an analyst channel is systematically
optimistic. Tight analyst dispersion may also signal herding rather than
knowledge, in which case this estimator weights correlated bias *up*. Neither is
resolved by the arithmetic, which is why calibration is gated on residual
structure against realized outcomes rather than on the formula being pretty.
