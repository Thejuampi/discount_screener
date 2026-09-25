---
id: SPEC-valuation-evidence-sotp
status: final
companions:
  - architecture-invariants.md
  - evidence-and-validation.md
  - architecture-diagrams.md
  - ../spec-explicit-driver-data-resolution/SPEC.md
  - ../../planning-artifacts/valuation-model-family-architecture.md
  - ../../project-context.md
sources:
  - C:/Users/Juan/.codex/attachments/38da3f59-5f6c-4c68-90d6-9fd8926ee457/pasted-text.txt
---

> **Canonical contract.** This SPEC and its companions define the complete contract for point-in-time evidence, component valuation, SOTP consolidation, model uncertainty, refusal behavior, and validation.

# Evidence-based SOTP valuation with fail-closed economics

## Why

Generic FCFF projects historical accounting patterns as though they were the economic drivers of every business. This produces untraceable valuation errors, especially after acquisitions, in resource producers, in mixed businesses, and when accounting regimes differ. Discount Screener must publish intrinsic value only when a dated, auditable economic model supports it, while treating analyst ranges as external diagnostics rather than inputs.

## Capabilities

- **CAP-1**
  - **intent:** Preserve structured and unstructured valuation evidence as bitemporal, versioned observations so a computation can use only information known at its decision time.
  - **success:** A point-in-time replay can distinguish an original fact from a later amendment, filing recast, macro revision, guidance revision, classification revision, or retrieval failure.

- **CAP-2**
  - **intent:** Route issuers and their point-in-time economic components to evidence-appropriate valuation families instead of applying one generic FCFF model per ticker.
  - **success:** Each valuation is either composed from eligible components or fails with a typed reason; no issuer is silently routed to generic FCFF because its class, segment, or source regime is unknown.

- **CAP-3**
  - **intent:** Value components as enterprise value and consolidate them through one evidence-backed capital bridge.
  - **success:** A published SOTP price contains no component-level debt/cash allocation, includes an evidenced corporate-overhead component, and refuses publication when a material component or bridge item is unresolved.

- **CAP-4**
  - **intent:** Model resource producers and contracted infrastructure from their economic drivers rather than historical revenue-growth extrapolation.
  - **success:** ResourceProducer projections reconcile commodity-specific volumes, prices, hedges, costs, reserves, decline, and investment; ContractedInfrastructure projections classify contracted revenue exposure; unsupported material drivers refuse the component.

- **CAP-5**
  - **intent:** Enforce economically consistent FCFF, discount-rate, and terminal-value construction for every model family.
  - **success:** SBC is accounted for once, terminal growth declares terminal ROIC and reinvestment, finite-resource models do not use an unsupported perpetuity, and unsupported RBL or rate evidence produces an explicit refusal.

- **CAP-6**
  - **intent:** Expose valuation quality, uncertainty, disagreement, and refusal reasons without turning missing evidence into a number.
  - **success:** Detail, Quant Lens, cache, and scoring distinguish unavailable, covered-EV-only, provisional, and publishable states; analyst consensus changes no model input or output cap.

- **CAP-7**
  - **intent:** Validate economic-driver accuracy and coverage with reproducible point-in-time history before using outcomes for model-quality policy.
  - **success:** Primary validation measures later-reported driver accuracy using historical universe membership, delistings, corporate actions, classifications, and component definitions; market outcomes remain a separately labeled diagnostic.

- **CAP-8**
  - **intent:** Keep the valuation contract portable across Windows, Android, and Desktop without allowing a lagging surface to publish an invalid fallback.
  - **success:** Shared contracts compare public fixed-point values, provenance, reason codes, and policy fingerprints exactly; an unsupported surface refuses rather than running a superseded generic model.

## Constraints

- Economic period, knowledge/publication timestamp, revision lineage, source vintage, retrieval timestamp, units, definition, source location, and extraction quality are mandatory evidence fields.
- The source regime is explicit. Domestic US-GAAP, IFRS, and unsupported regimes do not share silent accounting normalizations.
- A component emits enterprise value only. Net debt, NCI, preferred claims, and separately valued non-consolidated investments are bridged once at the issuer level.
- Disclosed corporate overhead is a negative enterprise-value component. Material unreconciled overhead or an unvalued material component prevents intrinsic-price, gap, and valuation-score publication.
- Resource volumes declare `gross`, `working_interest`, or `net_revenue_interest`; no royalty default may reconcile incompatible bases.
- No terminal growth exists independently of terminal ROIC and reinvestment. Exhaustible resources use a finite reserve horizon unless replacement is evidenced.
- RBL non-convergence, multiple fixed points, or material instability fails closed. Rate/risk policies are evidence and family specific, not hidden constants.
- Confidence aggregates driver materiality within a component and component contribution within the consolidated valuation. Sensitivity and solver stability are quality evidence.
- Analyst targets, market price, posterior market returns, hard intrinsic/price caps, sector haircuts, arbitrary partial-coverage penalties, and default royalty burdens are forbidden valuation inputs.
- Public money and rates use fixed-point units. Ratios exposed by contracts use integer bps/millis, not floating-point fields.

## Non-goals

- Promising zero economic forecast error or forcing a valuation for every listed symbol.
- Treating covered enterprise value as a complete equity valuation.
- Supporting 40-F/IFRS valuation before a native IFRS normalizer resolves interest classification and IFRS 16 lease treatment.
- Replacing financial-services residual income with FCFF or using analyst consensus to calibrate a ticker's intrinsic value.

## Success signal

A historical replay can reproduce exactly what the engine knew on a date, explain every published valuation and refusal with primary evidence, and refuse rather than emit an unsupported DCF. Across applicable models, driver accuracy and uncertainty are measured from dated evidence while consensus remains an independent diagnostic.

## Open Questions

- Which licensed or otherwise verifiable source will provide historical Russell membership, delistings, and corporate-action history for point-in-time validation?
