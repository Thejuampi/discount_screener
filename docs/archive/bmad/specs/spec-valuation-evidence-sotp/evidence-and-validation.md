# Evidence, quality, and validation

## Evidence lanes

| Lane | Examples | Admission rule |
|---|---|---|
| Structured filing facts | XBRL standard and issuer extensions | Canonical semantic mapping, context, units, periods, and filing vintage validate. |
| Filing documents | MD&A, footnotes, Inline XBRL tables, exhibits, reserve reports | Extraction includes document, section/page, method, reviewer state, period, knowledge date, and source vintage. |
| Company forward disclosures | 10-Q/10-K guidance, earnings releases, presentations | Exact horizon, definitions, publication timestamp, revision lineage, and expiry are preserved. |
| External macro | EIA price/production scenarios and other approved public series | Dataset release/vintage and as-of availability are preserved; revised history never overwrites a historical replay. |
| Security master | CIK/ticker mapping, splits, mergers, delistings, historical index membership | Point-in-time effective range and source entitlement are required. |

## Quality aggregation

Driver quality incorporates provenance, freshness, extraction/reconciliation state, sensitivity, and materiality. Component quality aggregates its driver quality. Consolidated quality then aggregates component quality weighted by evidenced component contribution, while unresolved material components prohibit publication rather than receiving a penalty.

RBL convergence diagnostics and sensitivity to evidence-backed scenarios are quality inputs. Thresholds are calibrated from point-in-time validation by family and market regime; no universal magic threshold controls publication.

## External disagreement

Analyst ranges are external evidence only. `Disputed` compares compatible horizon, definition, and interval uncertainty of the intrinsic model and analyst range. It does not modify cash flows, rates, terminal value, scenarios, or score inputs. Wide model uncertainty is reported as uncertainty, not manufactured disagreement.

## Validation

Primary backtests measure ex-ante driver forecasts against subsequently reported production, realized prices, costs, CapEx, reserve changes, and other economic results. They use only evidence and classifications available at the earlier knowledge date.

Secondary diagnostics compare intrinsic valuation with later market outcomes and analyst ranges. They are explicitly separate because market rerating and consensus changes are not proof of driver-model error.

Any Russell analysis requires historical membership, delistings, ticker/CIK history, corporate actions, family classifications, and component structures. Current constituents may not be substituted for historical membership.

## Required operator metrics

- classified, unclassified, not eligible, source-regime unsupported, and valuation-unavailable rates by universe and family;
- eligible, provisional, solid, covered-EV-only, and refused rates with reason-code distribution;
- source freshness, revision, extraction, and reconciliation coverage;
- point-in-time driver accuracy and uncertainty calibration by family and regime;
- model-versus-consensus disagreement as a diagnostic, not a model target.
