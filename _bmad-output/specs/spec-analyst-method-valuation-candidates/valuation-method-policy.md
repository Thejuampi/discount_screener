# Valuation method policy

## Model identities

| Candidate | Quantity | Core evidence | Output horizon | Evidence family |
| --- | --- | --- | --- | --- |
| `FcffWacc` | Operating cash intrinsic | Normalized FCFF, growth, WACC, terminal economics | Present | Intrinsic model |
| `ForwardEarningsPower` | Discounted earnings-power proxy | Forward EPS path, growth fade, cost of equity | Present | Analyst-derived model when consensus supplies EPS |
| `ForwardEarningsMultiple` | Market-referenced price target | Forward EPS for fiscal period N and justified P/E at target date | Target date | Analyst-method-derived model |
| Analyst range | External consensus target interval | Provider observations | Provider horizon | Analyst range |

The identities never alias. A lower FCFF during a growth-CapEx wave can coexist with a higher earnings-multiple target without either computation being relabeled or silently tuned.

## Forward earnings multiple contract

Required evidence:

| Field | Rule |
| --- | --- |
| `metric` | Typed metric such as `gaap_diluted_eps`; non-GAAP variants are different metrics. |
| `eps_cents` | Positive fixed-point forecast with fiscal period and currency. |
| `multiple_hundredths` | Positive P/E with an explicit provenance variant. |
| `forecast_period_end` | Fiscal end for the EPS estimate. |
| `target_as_of` | Target horizon plus precision (`exact_date`, `month_label`, `fiscal_period`, or provider horizon); imprecise dates cannot drive day-count transformations. |
| `evidence_observed_at` | Publication or observation date used for freshness and replay. |
| `source_fingerprint` | Versioned source and extraction fingerprint. |
| `multiple_provenance` | `analyst_stated` or `peer_policy_derived`; never an unlabeled scalar. |

Primary output:

```text
target_value_cents = round_half_up(eps_cents × multiple_hundredths / 100)
```

The target value belongs to `target_as_of`. A present-equivalent output, if enabled, is a separately named derived field with its discount rate, day-count policy, and provenance. It cannot replace the target-horizon value.

## Multiple provenance

### Analyst-stated

Preserve analyst, report date, target horizon and precision, forecast metric and period, stated peer set, stated multiple, rationale, and page or section reference when the source artifact is verified. A user transcription without the entitled artifact is `manual_transcription_unverified`: page/section stays absent, source claims remain provisional, and it cannot upgrade evidence strength. The stated price target is validation evidence; it is not an arithmetic input.

### Peer-policy-derived

Require a frozen dated peer set selected independently of the subject target/value, business-model comparability rules, at least five eligible point-in-time peer observations, a robust base statistic, and an explicit premium or discount decomposition. Fewer than five refuses; five to seven permits a robust median as `soft`; eight to eleven permits a robust median initially `soft` subject to dispersion and leave-one-out stability. Regression requires at least twelve peers, at least five observations per fitted coefficient, robust diagnostics, temporal stability, shrinkage, and rolling holdout evidence. The subject issuer's current market price or implied P/E is excluded to prevent circularity. Missing peer coverage or an unsupported premium refuses the candidate.

The decomposition must explain the fundamental drivers of the justified multiple:

| Driver | Expected relation to justified P/E |
| --- | --- |
| Sustainable EPS growth and its duration | Higher supports a higher multiple. |
| Incremental ROIC/ROE above required return | Higher and persistent supports a higher multiple. |
| Reinvestment required to obtain growth | More capital for the same growth supports a lower multiple. |
| Cost of equity and business risk | Higher supports a lower multiple. |
| Earnings quality, cyclicality, and dilution | Lower quality or greater uncertainty supports a lower multiple or refusal. |

Peer premiums are explicit evidence, not residual plugs selected to reproduce a target.

## Amazon golden

User-supplied transcription of a JPM method (arithmetic fixture, not verified-report provenance):

| Field | Value |
| --- | --- |
| Target date | December 2027 |
| Forecast metric | 2028E GAAP EPS |
| EPS | $13.00 |
| Multiple | approximately 28.00x |
| Arithmetic target | $364.00 |
| Stated report target | $365.00 |
| Reconciliation | Approximately $1.00 rounding or use of an unrounded multiple near 28.08x |

The golden proves method fidelity, not that $365 is a present intrinsic value or an acceptance bound for FCFF.

### Sensitivity golden

| EPS / P/E | 22.00x | 28.00x | 34.00x |
| ---: | ---: | ---: | ---: |
| $11.00 | $242.00 | $308.00 | $374.00 |
| $13.00 | $286.00 | $364.00 | $442.00 |
| $15.00 | $330.00 | $420.00 | $510.00 |

Reverse checks for a $365 target:

- at 28.00x, required EPS is approximately $13.04;
- at $11.00 EPS, required P/E is approximately 33.18x;
- at 24.00x, required EPS is approximately $15.21.

The fixed-point contract owns exact half-up rounding at the named division step.

## Routing and presentation

- The first slice retains `ForwardEarningsMultiple` as an additional Quant Lens candidate outside the current intrinsic `OperatingModelRouter` and FCFF cache; it does not automatically become the Detail intrinsic hero or dashboard ranking input.
- Compare only horizon-compatible values. A target-horizon value may be displayed beside present values, but disagreement status requires either an explicit present-equivalent conversion or an honest horizon mismatch label.
- `ForwardEarningsMultiple` and the analyst target from the same report are correlated evidence and count as one family.
- A Yahoo-consensus EPS candidate and Yahoo analyst range are also correlated unless independently sourced evidence supports separate counting.
- FCFF remains visible even when growth CapEx depresses it. The explanation names the cash-versus-earnings-method difference rather than treating disagreement as an engine failure by itself.
- SOTP is a separately identified economic cross-check, not a hidden adjustment to the consolidated P/E candidate.
- Scenario labels require joint EPS and multiple assumptions. Independent sensitivity cells are diagnostics and do not automatically become bear/base/bull cases.
- Any later selection role requires an explicit valuation-architecture amendment; this spec does not silently weaken the existing multiple-free intrinsic-router invariant.

## Expansion gate after Amazon

Before the candidate affects selection or scoring, build a frozen calibration cohort and an unseen holdout containing different business and investment regimes. Report coverage, method-reproduction error, horizon-normalized disagreement, and refusal distribution. Market price and analyst targets remain validation-only fields outside runtime candidate inputs.
