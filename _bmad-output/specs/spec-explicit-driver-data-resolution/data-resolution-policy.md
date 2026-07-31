# Data resolution policy

This companion is the executable-policy reference for `SPEC-explicit-driver-data-resolution`.

## Resolution boundary

Provider shells fetch raw facts. A canonical resolver normalizes and aligns them before either valuation engine sees them. Each candidate fact carries:

| Field | Rule |
| --- | --- |
| Period | Fiscal start, fiscal end, fiscal year, duration days, or instant date |
| Value | Signed numeric value before model-specific sign normalization |
| Units | USD, shares, bps, or source-declared unit |
| Currency | Explicit currency; no silent FX conversion |
| Source | `market`, `sec_edgar`, or `yahoo_finance` |
| Concept | XBRL/Yahoo concept identifier |
| Filing | Filing date/form and restatement identity when available |
| Fingerprint | Canonical hash input including all selected and rejected facts |

Annual duration facts align on the same fiscal year and compatible duration. Debt is an instant fact at fiscal year end. The resolver may use the average of the current and prior fiscal-year debt instants for an annual interest period. It must reject mixed fiscal years, quarter/TTM facts, incompatible units, duplicate unresolved facts, and untraceable currency conversions.

## Operating-company rate resolution

The resolver runs only after classification returns `OperatingNonFinancial`.

### Cost of debt

1. Use an observable yield/spread when the instrument is liquid, current, and representative of the issuer's debt.
2. Use an issuer rating/spread when current and attributable to the issuer. If no rating exists, a synthetic spread may be derived from interest coverage using a versioned spread table and actual issuer facts.
3. Use SEC annual interest expense divided by average aligned debt when the debt facts cover the same fiscal periods. The annual denominator is the average of the current and prior fiscal closing debt; the resolved rate is the median of valid annual observations. A single valid pair is real evidence but `provisional`.
4. Use Yahoo annual interest and debt only when both facts share the same fiscal period and compatible units.
5. Return `unavailable` with all attempted-source reason codes if no valid path remains.

`cost_of_debt_source` must distinguish `MarketYield`, `RatedOrSyntheticSpread` (with rating/synthetic concept retained in source provenance), `InterestOverAverageDebt`, `YahooAlignedInterestOverDebt`, `NotApplicable`, and `Unavailable`. A policy constant may not be emitted as one of these sources.

### Tax inputs

The resolver emits two independent values:

- `historical_effective_tax_bps`: prefer cash-tax/pretax evidence for the same annual period; otherwise use reported tax expense/pretax. Reject non-positive pretax denominators and record one-off or outlier exclusions. This value is for historical FCFF reconstruction only.
- `marginal_tax_for_wacc_bps`: prefer filing tax-reconciliation/statutory evidence; otherwise use a versioned jurisdiction table, blended by disclosed geographic income when available. A parent-domicile proxy is allowed only as `provisional`; missing jurisdiction is `unavailable`.

The marginal tax path is used for the WACC tax shield and terminal economics. The effective historical path is never silently reused as the marginal terminal rate.

## Financial services

Financial services bypass the operating rate resolver. They use residual income with cost of equity, book equity, ROE, and retention/payout inputs. A missing required residual-income driver returns `Unavailable` with a reason such as `missing_book_equity` or `missing_roe`; it never invokes FCFF.

## Quality and downstream state

| Evidence | Quality | Behavior |
| --- | --- | --- |
| 3+ valid aligned periods | `solid` | May provide a solid model anchor if all other inputs are solid |
| 1–2 real aligned periods | `provisional` | May calculate, but point estimate is unreliable and reasons are visible |
| No valid period after all attempts | `unavailable` | No intrinsic, gap, synthetic score, or stale FCFF cache |

Unavailable model output is still a typed result. It carries `business_class`, `model=None`, `resolver_state=Unavailable`, `valuation_unavailable_reason`, attempted sources, rejected reasons, and the resolver fingerprint. Quant Lens may use a complete analyst range independently; it must not manufacture a combined model anchor.

## Exact parity

Both implementations normalize to fixed-point public fields before export. The comparator must assert exact equality for cents, bps, booleans, enums, ordered lists, periods, fingerprints, and reason codes. A one-cent or one-bps difference is a failure; there is no epsilon.
