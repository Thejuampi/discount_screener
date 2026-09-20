# Architecture invariants

## Evidence and source regimes

`EvidenceObservation` is bitemporal: it records economic period, knowledge date, filing/publication date, amendment or revision lineage, source vintage, retrieval time, unit, definition, provenance location, extraction method, and quality. The same contract applies to XBRL facts, filing tables, exhibits, guidance, reserve reports, hedge disclosures, macro series, classifications, segment definitions, and universe membership.

US-GAAP and IFRS are separate source regimes. A 40-F or IFRS issuer is `SourceRegimeUnsupported` until a native IFRS normalizer handles interest classification and IFRS 16 leases. It never falls through to US-GAAP normalization.

## Component routing and consolidation

`ValuationComponent` is a point-in-time component with a valuation family, source evidence, quality, and enterprise value. Segment/ASC 280/IFRS 8 definitions and recasts are versioned by knowledge date. A component never emits equity value.

```text
sum(EV of all valued components)
+ separately valued non-consolidated investments
- net debt, NCI, preferred and other senior claims
= consolidated equity value
```

Corporate/unallocated overhead is a separately evidenced negative-EV consolidation component. It is never distributed among segments without issuer evidence. If any material component, overhead, or capital-bridge claim is unresolved, only `CoveredEnterpriseValue` may be displayed; intrinsic price, gap, and valuation score are unavailable.

## Families

| Family | Economic basis | Rate discipline |
|---|---|---|
| OperatingNonFinancial | Operating FCFF | Evidence-backed WACC |
| FinancialServices | Residual income / excess return | Cost of equity only |
| ResourceProducer | Commodity-specific production, reserves, decline, costs, hedges, development | Family-specific capital structure; RBL convergence required when applicable |
| ContractedInfrastructure | Take-or-pay, fee/volumetric, or percent-of-proceeds contracts | Infrastructure/contract risk evidence, not a generic operating fallback |
| RegulatedUtility | Regulated return and rate-base economics | Regulatory allowed-ROE evidence |
| NotEligible / Unclassified | No intrinsic model | Refusal |

## FCFF and terminal economics

SBC is recognized once: either as economic compensation expense or through share/dilution projection. It is never deducted from FCFF and again reflected through perpetual dilution. CapEx, NWC, and SBC require distinct evidence and are not inferred from each other.

Explicit periods model total investment and the growth it purchases. Every non-extractive terminal value obeys:

\[
FCFF_{T+1}=NOPAT_{T+1}\left(1-\frac{g_{stable}}{ROIC_{terminal}}\right)
\]

ResourceProducer components use reserve categories, commodity mix, decline, development costs, and finite lives. Perpetuity is prohibited without evidence of reserve replacement. BOE is not a value measure; oil, gas, and NGL are projected and priced separately. Hedges use realized or contractually committed cash settlements, never unrealized GAAP mark-to-market as FCFF.

## RBL and physical reconciliation

RBL capital structure is solved by an explicit policy with convergence and stability diagnostics. Non-convergence, multiple fixed points, or unstable material sensitivity yields no component valuation. Gross, working-interest, and net-revenue-interest observations may be combined only through an evidenced WI and royalty reconciliation; a default royalty burden is forbidden.

## Refusal contract

Reason codes are structured, fixed-point where numeric, and carry evidence references. The minimum set includes:

```rust
pub enum ValuationRefusalReason {
    UnclassifiedSector { sector: String, industry: String },
    IncompleteSegmentDisclosures { missing_segment: String },
    UnallocatedOverheadAmbiguity { overhead_ratio_bps: i32 },
    VolumetricBaseMismatch { expected: VolumetricBase, observed: Option<VolumetricBase> },
    MissingTerminalReinvestmentLink,
    SourceRegimeUnsupported { regime: String },
    NonConvergedRblIteration,
    UnreconciledSbcTreatment,
}
```

Net debt exceeding enterprise value is not a refusal. It is a valid distressed capital-structure result and may yield zero equity value.
