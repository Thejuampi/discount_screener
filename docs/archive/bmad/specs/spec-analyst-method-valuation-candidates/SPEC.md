---
id: SPEC-analyst-method-valuation-candidates
companions:
  - valuation-method-policy.md
  - amazon-professional-valuation-playbook.md
  - ../../project-context.md
  - ../../planning-artifacts/valuation-model-family-architecture.md
  - ../../planning-artifacts/valuation-model-change-decision-2026-07-31.md
  - ../../planning-artifacts/architecture/architecture-analyst-method-automation-2026-08-01/ARCHITECTURE-SPINE.md
sources: []
---

> **Canonical contract.** This SPEC and the files in `companions:` are the complete, preservation-validated contract for what to build, test, and validate.

# Analyst-method valuation candidates

## Why

Discount Screener needs to compare businesses through the valuation instruments professional analysts actually use without distorting FCFF until it resembles a price target. Amazon exposes the gap: an honest cash model is depressed by an AI/AWS investment wave, while JPM's December 2027 target capitalizes 2028E GAAP EPS at a forward P/E. Professional-grade use requires more than reproducing that multiplication: the engine must explain the EPS, justify the multiple, test segment economics and CapEx productivity, and expose the expectation range. Juan needs those claims represented explicitly before the approach expands to other large valuation misses.

## Capabilities

- **CAP-1**
  - **intent:** The system can represent a professional analyst valuation method as dated, typed evidence with its forecast metric, fiscal period, multiple, target horizon, source, and provenance.
  - **success:** Complete evidence can be replayed exactly, while missing or incompatible evidence yields a typed refusal instead of a default estimate.

- **CAP-2**
  - **intent:** The system can produce an earnings-multiple target candidate separately from FCFF and ForwardEarningsPower.
  - **success:** Fixed-point computation reproduces the stated method at its target horizon without reading the issuer market price or stated target as calculation inputs.

- **CAP-3**
  - **intent:** Juan can compare analyst-method, FCFF, earnings-power, and analyst-range anchors in Quant Lens without confusing their sources or horizons.
  - **success:** Material disagreement shows the compatible anchors separately and never averages them into one unlabeled expected value.

- **CAP-4**
  - **intent:** The system can extend analyst-method valuation beyond Amazon through versioned method and peer-policy evidence rather than ticker-specific branches.
  - **success:** A second issuer can be added through evidence and shared contract cases without changing arithmetic for Amazon or adding a symbol conditional.

- **CAP-5**
  - **intent:** The system can measure analyst-method fidelity and out-of-sample usefulness separately from market proximity.
  - **success:** Calibration and holdout diagnostics report method-reproduction and comparison error while runtime routing cannot deserialize or enforce validation targets.

- **CAP-6**
  - **intent:** The system refuses earnings-multiple candidates whose evidence is stale, incomplete, circular, or horizon-incompatible.
  - **success:** Detail and Quant Lens expose a typed refusal reason and no synthetic target is published.

- **CAP-7**
  - **intent:** The system can explain forecast EPS through operating drivers and reconcile GAAP EPS with any normalized EPS used in valuation.
  - **success:** Segment revenue and margins reconcile through taxes, non-operating items, and diluted shares to replayable GAAP and normalized EPS with typed uncertainty.

- **CAP-8**
  - **intent:** The system can cross-check Amazon through segment-aware sum-of-the-parts valuation when material component evidence exists.
  - **success:** AWS, advertising, retail, and International economics are valued separately and consolidated through one capital bridge, or material missing component evidence prevents a complete SOTP claim.

- **CAP-9**
  - **intent:** The system can evaluate investment-wave CapEx by the growth and incremental return it is expected to purchase.
  - **success:** CapEx treatment links capacity, revenue, margin, depreciation, and incremental return instead of applying a blanket growth-CapEx add-back.

- **CAP-10**
  - **intent:** Juan can inspect coherent joint EPS/multiple scenarios and reverse-valuations for any market or analyst anchor.
  - **success:** Bear, base, and bull cases pair compatible assumptions and state the EPS or multiple required to justify a selected price or target.

- **CAP-11**
  - **intent:** The system can track forecast dispersion and revisions point-in-time.
  - **success:** Historical changes in EPS, multiple, target, and uncertainty replay only evidence available at each decision date without look-ahead reconstruction.

## Constraints

- A future price target and a present intrinsic value are different quantities; target horizon and any present-value conversion are explicit.
- Issuer market price, stated price target, and the issuer-implied forward P/E cannot manufacture that issuer's candidate. They remain validation evidence only.
- An analyst-stated multiple is analyst-correlated evidence. An internally derived multiple requires dated peers, peer eligibility, robust aggregation, and an explicit growth/quality premium policy.
- Money, EPS, multiples, dates, rates, comparisons, and fingerprints remain fixed-point and versioned. Missing evidence refuses rather than defaults.
- Financial services remain on residual income; NotEligible and Unclassified remain unavailable. Earnings multiples are not a universal fallback.
- The existing ForwardEarningsPower candidate remains distinct because it discounts an earnings path and terminal value instead of applying a target-horizon P/E.
- A professional-grade estimate justifies both forecast EPS and the valuation multiple; correct multiplication alone is not sufficient model quality.
- Amazon's consolidated P/E requires a segment/SOTP cross-check because AWS, advertising, retail, and International have materially different margins, growth, capital intensity, and risk.
- GAAP and normalized EPS coexist with a line-item reconciliation. Litigation, severance, impairments, non-operating gains, taxes, stock-based compensation, and diluted shares cannot be silently removed or double-counted.
- Growth CapEx is not added back solely because it is labeled growth; incremental operating evidence must support the treatment.
- Scenario outputs pair economically coherent EPS and multiple assumptions and report sensitivity. A point estimate without its distribution is insufficient for selection policy.
- Professional validation targets forecast and method fidelity, uncertainty calibration, and point-in-time holdout performance—not closeness to current market price.
- ForwardEarningsMultiple remains a parallel market-reference candidate outside the current intrinsic OperatingModelRouter and FCFF cache until an explicit architecture amendment authorizes any selection role.
- A SOTP that carves advertising out of North America or International subtracts the same economics from those segment views and reconciles to consolidated totals; otherwise advertising remains embedded to prevent double counting.

## Non-goals

- Forcing FCFF to equal JPM, Street consensus, or current market price.
- Replacing ForwardEarningsPower, residual income, or the analyst range with one universal P/E model.
- Making an analyst-derived candidate independently eligible for Strong evidence or dashboard ranking in the first slice.
- Shipping generic analyst-PDF extraction before the typed candidate and Amazon contract are proven.
- Treating SOTP as complete when a material component or issuer-level capital bridge is unsupported.
- Improving apparent precision by hiding dilution, stock-based compensation, leases, or investment-wave capital requirements.

## Success signal

Given the supplied Amazon JPM evidence, the engine emits a December 2027 earnings-multiple target of $364.00 from 2028E GAAP EPS of $13.00 and a 28.00x multiple, explains the approximately $1 report-rounding difference from the stated $365 target, reproduces the contracted sensitivity and reverse-valuation checks, and shows the result beside—not blended into—FCFF, ForwardEarningsPower, and any provisional SOTP evidence. No candidate is promoted without visible EPS, multiple, horizon, CapEx, dilution, and uncertainty provenance.

## Assumptions

- The user's transcription is sufficient for an arithmetic `fixture_transcription` golden only; without the original entitled report, production evidence remains `manual_transcription_unverified` and cannot claim verified JPM/GAAP/page provenance.
- The first implementation slice is a pure candidate and shared Amazon contract before automated analyst-report ingestion or scoring eligibility.

## Resolved architecture decisions

- Slice 1 uses a typed manual transcription path; `analyst_stated` verification and `peer_policy_derived` remain separate provenance variants.
- Quant Lens initially displays the target-horizon value with precision; present-equivalent is deferred until live dated CoE and a transformation policy exist.
- SOTP is a separate cross-check. Only a complete reconciled SOTP may be intrinsic; incomplete coverage emits `CoveredEVOnly` without per-share value, ranking, or selection.
- The market-reference lane remains parallel to the multiple-free intrinsic router and is diagnostic-only in Slice 1.
- Public SEC/macro and self-captured snapshots form the provisional backbone. A commercial PIT source remains an adapter-level choice pending budget/licensing authority; Visible Alpha is the first Amazon-focused trial recommendation, with FactSet/LSEG alternatives.
