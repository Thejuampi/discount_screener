# Amazon professional valuation playbook

## Decision stack

Amazon requires several instruments because each answers a different question:

| Instrument | Professional use | Publication rule |
| --- | --- | --- |
| Segment driver forecast | Explain how revenue, margin, taxes, and dilution produce EPS. | Required provenance for an internally modeled EPS; external consensus remains explicitly external. |
| Forward EPS × justified P/E | Produce a market-referenced price target at a stated future date. | Publish target-horizon value; never call it present FCFF intrinsic. |
| SOTP | Test whether businesses with different economics justify the consolidated claim. | Complete only when material components and the issuer capital bridge are supported. |
| FCFF | Test cash conversion, reinvestment burden, and present operating value. | Remains visible during investment waves; no forced convergence to the P/E target. |
| Reverse valuation | State the expectations required by a market or analyst anchor. | Diagnostic only; the anchor never becomes a model input. |

No layer is averaged into a universal fair value when definitions, horizons, or evidence families differ.

## EPS bridge

The professional forecast reconciles:

```text
segment revenue
× segment operating margin
= segment operating income
− corporate/unallocated cost
= consolidated operating income
± interest and non-operating items
− cash and normalized taxes
= GAAP or normalized net income
÷ diluted weighted-average shares
= EPS
```

For Amazon the minimum economic views are AWS, North America retail/platform, International retail/platform, and advertising. Advertising is disclosed as a sales category rather than a standalone reportable segment, so an advertising profit estimate is provisional unless stronger evidence resolves its cost allocation. If advertising is carved out, the same revenue and profit are removed from the affected North America/International views and the reconstruction must reconcile to consolidated totals; otherwise advertising remains embedded as a diagnostic rather than a separately summed component.

Every normalized EPS includes a bridge to GAAP. Litigation, severance, impairments, investment gains/losses, tax items, and accounting-estimate changes remain visible. Stock-based compensation is counted exactly once: removing expense requires preserving dilution or an equivalent economic charge. Share-count projection includes grants, vesting, repurchases, and other issuance evidence.

## SOTP cross-check

Each component receives a method appropriate to its economics:

| Component | Candidate drivers | Cross-checks |
| --- | --- | --- |
| AWS | Revenue growth, operating margin, infrastructure utilization, incremental capital return | EV/EBIT, EV/revenue with margin normalization, cash economics |
| Advertising | Ad sales growth, incremental margin, traffic/monetization durability | Comparable platform multiples; provisional margin sensitivity |
| North America | 1P/3P mix, fulfillment productivity, advertising allocation, normalized retail margin | EBIT/FCF and mature platform peers |
| International | Country/mix growth, margin convergence, FX, reinvestment | EBIT/FCF under explicit convergence scenarios |
| Corporate and capital bridge | Unallocated cost, cash/investments, debt, leases, other claims, diluted shares | One issuer-level bridge; no component debt duplication |

Material missing component profitability yields `CoveredEnterpriseValue` or a provisional diagnostic, not a complete per-share intrinsic.

Component revenue, operating profit, corporate cost, and capital claims reconcile to consolidated evidence before SOTP publication. No economic contribution may appear in more than one component.

## CapEx productivity bridge

Amazon's investment wave cannot be handled by either subtracting total CapEx forever or adding back everything labeled growth. The model links:

```text
cash CapEx + financed/leased equipment
→ usable infrastructure capacity
→ utilization and service volume
→ incremental revenue
→ incremental operating margin
→ depreciation and lease burden
→ incremental ROIC versus required return
```

Maintenance and growth classifications are outputs of evidence, not ticker constants. A growth treatment requires a plausible timing lag and measurable incremental economics. Unsupported allocation remains in cash outflow or makes the adjusted view provisional.

## Multiple justification

The 28x Amazon base must be decomposable into a dated comparable base plus explicit premiums or discounts for sustainable growth, incremental return, business mix, risk, capital intensity, earnings quality, and dilution. Comparable selection follows economic exposure rather than a broad sector label. The subject issuer's current implied multiple is excluded from its own derivation.

The engine preserves two distinct provenance variants:

- `analyst_stated`: reproduce the professional report's own EPS, multiple, horizon, peers, and rationale;
- `peer_policy_derived`: calculate a versioned internal multiple from approved point-in-time peer evidence.

The two variants may disagree and remain separately labeled.

## Scenarios and reverse valuation

Bear/base/bull cases are coherent joint states, not independent endpoint combinations:

- Bear: weaker segment execution, lower EPS, higher uncertainty, and multiple compression.
- Base: evidenced execution with a justified mid-cycle multiple.
- Bull: stronger EPS only with evidence that competitive advantage and incremental returns persist long enough to support the higher multiple.

The contracted Amazon sensitivity matrix lives in `valuation-method-policy.md`. Reverse valuation additionally solves the EPS, multiple, or operating drivers required by a selected price. This converts a target into falsifiable expectations without using that target to manufacture the estimate.

## Horizon discipline

A December 2027 target using 2028E EPS is a target-date forward-multiple claim. Quant Lens displays the target date and forecast fiscal period. A present-equivalent value, if enabled, is separately discounted with cost-of-equity and day-count provenance. Present FCFF, target-date P/E, and analyst ranges are not compared as identical quantities without horizon normalization.

## Professional validation

Primary validation is point-in-time and uses only evidence observable on the earlier decision date:

- EPS forecast error and segment-driver error;
- multiple-policy stability and peer coverage;
- scenario calibration and realized interval coverage;
- revision and dispersion history;
- refusal and provisional-state distribution;
- method reproduction for analyst-stated cases;
- calibration-cohort versus unseen-holdout performance.

Market price and analyst targets are secondary diagnostics. Runtime selection never reads validation anchors, and acceptance never requires value near market.

## Amazon evidence baseline

Amazon's 2025 Form 10-K reports:

- three reportable segments: North America, International, and AWS;
- AWS operating income of $45.6B versus $80.0B consolidated operating income;
- advertising-services sales of $68.6B;
- operating cash flow of $139.5B, cash capital expenditures of $128.3B, and reported free cash flow of $11.2B;
- capital expenditure primarily for technology infrastructure supporting AWS growth, with further increase expected in 2026;
- stock-based compensation expense of approximately $19.5B plus material unrecognized compensation;
- finance leases, server/networking assets, severance, litigation, impairments, and non-operating items material enough to require explicit bridges.

These facts justify a segment, reinvestment, earnings-quality, and dilution model. They do not by themselves justify a specific EPS or multiple.

## References

- [Amazon 2025 Form 10-K](https://www.sec.gov/Archives/edgar/data/1018724/000101872426000004/amzn-20251231.htm)
- [CFA Institute — Market-Based Valuation: Price and Enterprise Value Multiples](https://www.cfainstitute.org/insights/professional-learning/refresher-readings/2026/market-based-valuation-price-enterprise-value-multiples)
- [CFA Institute — Discounted Dividend Valuation](https://www.cfainstitute.org/insights/professional-learning/refresher-readings/2026/discounted-dividend-valuation)
- [CFA Institute — Equity Valuation: Applications and Processes](https://www.cfainstitute.org/insights/professional-learning/refresher-readings/2026/equity-valuation-applications-and-processes)
