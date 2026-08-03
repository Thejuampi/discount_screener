//! Point-in-time evidence, component valuation, and fail-closed SOTP.
//!
//! This module is intentionally separate from the legacy ticker DCF façade.
//! Providers must first produce `EvidenceObservation`s; family engines consume
//! typed, fixed-point inputs; consolidation is the only place where capital
//! claims are bridged.  A missing material input is a refusal, never a zero.

use serde::{Deserialize, Serialize};
use std::collections::BTreeMap;
use std::fmt;

pub const SPEC_ID: &str = "SPEC-valuation-evidence-sotp";
pub const ENGINE_VERSION: &str = "valuation-evidence-sotp/1";
pub const MODEL_POLICY_VERSION: &str = "evidence-sotp-policy/1";
pub const RESOLVER_POLICY_VERSION: &str = "pit-evidence-resolver/1";

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum SourceRegime {
    DomesticUsGaap,
    Ifrs,
    Unsupported,
}

impl SourceRegime {
    pub fn is_supported(self) -> bool {
        matches!(self, Self::DomesticUsGaap)
    }
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, Ord, PartialEq, PartialOrd, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum EvidenceQuality {
    Solid,
    Provisional,
    Unavailable,
    Rejected,
}

impl EvidenceQuality {
    pub fn from_period_count(periods: usize) -> Self {
        match periods {
            0 => Self::Unavailable,
            1 | 2 => Self::Provisional,
            _ => Self::Solid,
        }
    }
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum EvidenceUnit {
    MoneyCents,
    RateBps,
    QuantityMillis,
    Shares,
    Text,
    Boolean,
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum ExtractionMethod {
    StructuredXbrl,
    FilingTable,
    FilingNarrative,
    CompanyGuidance,
    MacroSeries,
    SecurityMaster,
    ManualReview,
    RetrievalFailure,
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum RetrievalState {
    Retrieved,
    Failed,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct EvidenceObservation {
    pub id: String,
    pub fact_key: String,
    pub economic_period_start: String,
    pub economic_period_end: String,
    pub knowledge_at: String,
    pub publication_at: String,
    pub revision_id: String,
    pub supersedes: Option<String>,
    pub source_vintage: String,
    pub retrieved_at: String,
    pub source_regime: SourceRegime,
    pub unit: EvidenceUnit,
    pub value_cents: Option<i64>,
    pub value_bps: Option<i32>,
    pub value_millis: Option<i64>,
    pub text_value: Option<String>,
    pub currency: Option<String>,
    pub definition: String,
    pub source_location: String,
    pub extraction_method: ExtractionMethod,
    pub quality: EvidenceQuality,
    pub retrieval_state: RetrievalState,
}

impl EvidenceObservation {
    pub fn validate(&self) -> Result<(), ValuationRefusal> {
        let required = [
            (&self.id, "id"),
            (&self.fact_key, "fact_key"),
            (&self.economic_period_start, "economic_period_start"),
            (&self.economic_period_end, "economic_period_end"),
            (&self.knowledge_at, "knowledge_at"),
            (&self.publication_at, "publication_at"),
            (&self.revision_id, "revision_id"),
            (&self.source_vintage, "source_vintage"),
            (&self.retrieved_at, "retrieved_at"),
            (&self.definition, "definition"),
            (&self.source_location, "source_location"),
        ];
        if let Some((_, field)) = required.iter().find(|(value, _)| value.trim().is_empty()) {
            return Err(ValuationRefusal::new(
                ValuationRefusalReasonCode::InvalidEvidence,
                format!("mandatory evidence field is empty: {field}"),
            ));
        }
        if self.economic_period_start > self.economic_period_end {
            return Err(ValuationRefusal::new(
                ValuationRefusalReasonCode::InvalidEvidence,
                format!("economic period is inverted for {}", self.id),
            ));
        }
        let values = [
            self.value_cents.is_some(),
            self.value_bps.is_some(),
            self.value_millis.is_some(),
            self.text_value.is_some(),
        ];
        if values.iter().filter(|present| **present).count() != 1 {
            return Err(ValuationRefusal::new(
                ValuationRefusalReasonCode::InvalidEvidence,
                format!("exactly one fixed-point value is required for {}", self.id),
            ));
        }
        let unit_matches = match self.unit {
            EvidenceUnit::MoneyCents => self.value_cents.is_some() && self.currency.is_some(),
            EvidenceUnit::RateBps => self.value_bps.is_some(),
            EvidenceUnit::QuantityMillis | EvidenceUnit::Shares => self.value_millis.is_some(),
            EvidenceUnit::Text | EvidenceUnit::Boolean => self.text_value.is_some(),
        };
        if !unit_matches {
            return Err(ValuationRefusal::new(
                ValuationRefusalReasonCode::InvalidEvidence,
                format!("value/unit mismatch for {}", self.id),
            ));
        }
        Ok(())
    }

    fn canonical(&self) -> String {
        [
            self.id.clone(),
            self.fact_key.clone(),
            self.economic_period_start.clone(),
            self.economic_period_end.clone(),
            self.knowledge_at.clone(),
            self.publication_at.clone(),
            self.revision_id.clone(),
            self.supersedes.clone().unwrap_or_default(),
            self.source_vintage.clone(),
            self.retrieved_at.clone(),
            format!("{:?}", self.source_regime),
            format!("{:?}", self.unit),
            self.value_cents.map(|v| v.to_string()).unwrap_or_default(),
            self.value_bps.map(|v| v.to_string()).unwrap_or_default(),
            self.value_millis.map(|v| v.to_string()).unwrap_or_default(),
            self.text_value.clone().unwrap_or_default(),
            self.currency.clone().unwrap_or_default(),
            self.definition.clone(),
            self.source_location.clone(),
            format!("{:?}", self.extraction_method),
            format!("{:?}", self.quality),
        ]
        .join("|")
    }
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum EvidenceRejectionCode {
    InvalidEvidence,
    RetrievalFailure,
    NotKnownAtDecision,
    DuplicateEvidence,
}

impl EvidenceRejectionCode {
    fn as_str(self) -> &'static str {
        match self {
            Self::InvalidEvidence => "invalid_evidence",
            Self::RetrievalFailure => "retrieval_failure",
            Self::NotKnownAtDecision => "not_known_at_decision",
            Self::DuplicateEvidence => "duplicate_evidence",
        }
    }
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct EvidenceRejection {
    pub observation_id: String,
    pub code: EvidenceRejectionCode,
    pub detail: String,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PitReplay {
    pub decision_at: String,
    pub selected: Vec<EvidenceObservation>,
    pub rejected: Vec<EvidenceRejection>,
    pub fingerprint: String,
}

/// Replays only evidence available to the decision maker at `decision_at`.
/// Later amendments remain in the input archive but cannot leak into an old
/// valuation.  The selected row is the latest known revision for each fact and
/// economic period; equal-rank conflicting rows are refused.
pub fn replay_point_in_time(
    observations: &[EvidenceObservation],
    decision_at: &str,
) -> Result<PitReplay, ValuationRefusal> {
    if decision_at.trim().is_empty() {
        return Err(ValuationRefusal::new(
            ValuationRefusalReasonCode::InvalidEvidence,
            "decision_at is empty",
        ));
    }

    let mut rejected = Vec::new();
    let mut candidates: BTreeMap<(String, String), Vec<&EvidenceObservation>> = BTreeMap::new();
    for observation in observations {
        if observation.retrieval_state == RetrievalState::Failed
            || observation.extraction_method == ExtractionMethod::RetrievalFailure
        {
            rejected.push(EvidenceRejection {
                observation_id: observation.id.clone(),
                code: EvidenceRejectionCode::RetrievalFailure,
                detail: "retrieval failed; no zero or imputed value is admitted".into(),
            });
            continue;
        }
        if let Err(error) = observation.validate() {
            rejected.push(EvidenceRejection {
                observation_id: observation.id.clone(),
                code: EvidenceRejectionCode::InvalidEvidence,
                detail: error.to_string(),
            });
            continue;
        }
        if observation.knowledge_at.as_str() > decision_at
            || observation.publication_at.as_str() > decision_at
            || observation.retrieved_at.as_str() > decision_at
        {
            rejected.push(EvidenceRejection {
                observation_id: observation.id.clone(),
                code: EvidenceRejectionCode::NotKnownAtDecision,
                detail: "observation was not available at the replay decision time".into(),
            });
            continue;
        }
        candidates
            .entry((
                observation.fact_key.clone(),
                observation.economic_period_end.clone(),
            ))
            .or_default()
            .push(observation);
    }

    let mut selected = Vec::new();
    for rows in candidates.values_mut() {
        rows.sort_by(|left, right| {
            (
                left.knowledge_at.as_str(),
                left.publication_at.as_str(),
                left.revision_id.as_str(),
            )
                .cmp(&(
                    right.knowledge_at.as_str(),
                    right.publication_at.as_str(),
                    right.revision_id.as_str(),
                ))
        });
        if rows.len() >= 2 {
            let last = rows[rows.len() - 1];
            let previous = rows[rows.len() - 2];
            // Parity with Kotlin EvidenceObservation.valueKey(): compare the full
            // optional value payload as one tuple. Requiring every slot to differ
            // (&& cents != && bps != …) fails when unused slots are both None.
            if last.knowledge_at == previous.knowledge_at
                && last.publication_at == previous.publication_at
                && value_payload(last) != value_payload(previous)
            {
                return Err(ValuationRefusal::new(
                    ValuationRefusalReasonCode::DuplicateEvidence,
                    format!(
                        "conflicting evidence at the same publication rank for {}",
                        last.fact_key
                    ),
                ));
            }
        }
        if let Some(row) = rows.last() {
            selected.push((*row).clone());
        }
    }
    selected.sort_by(|left, right| left.id.cmp(&right.id));
    rejected.sort_by(|left, right| left.observation_id.cmp(&right.observation_id));

    let mut canonical = selected
        .iter()
        .map(|row| format!("selected|{}", row.canonical()))
        .collect::<Vec<_>>();
    canonical.extend(rejected.iter().map(|row| {
        format!(
            "rejected|{}|{}|{}",
            row.observation_id,
            row.code.as_str(),
            row.detail
        )
    }));
    canonical.sort();
    let fingerprint = format!("fnv1a64:{:016x}", fnv1a64(canonical.join("\n").as_bytes()));
    Ok(PitReplay {
        decision_at: decision_at.into(),
        selected,
        rejected,
        fingerprint,
    })
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum AssetClass {
    Equity,
    Etf,
    Fund,
    Crypto,
    Reit,
    Unknown,
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, Ord, PartialEq, PartialOrd, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum ComponentFamily {
    OperatingNonFinancial,
    FinancialServices,
    ResourceProducer,
    ContractedInfrastructure,
    RegulatedUtility,
    NotEligible,
    Unclassified,
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum ComponentModel {
    FcffWacc,
    ResidualIncomeEquity,
    ResourceFinite,
    ContractedInfrastructure,
    RegulatedUtility,
    None,
}

impl ComponentFamily {
    pub fn model(self) -> ComponentModel {
        match self {
            Self::OperatingNonFinancial => ComponentModel::FcffWacc,
            Self::FinancialServices => ComponentModel::ResidualIncomeEquity,
            Self::ResourceProducer => ComponentModel::ResourceFinite,
            Self::ContractedInfrastructure => ComponentModel::ContractedInfrastructure,
            Self::RegulatedUtility => ComponentModel::RegulatedUtility,
            Self::NotEligible | Self::Unclassified => ComponentModel::None,
        }
    }
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ClassificationInput {
    pub sector: Option<String>,
    pub industry: Option<String>,
    pub asset_class: AssetClass,
}

pub fn route_component(input: &ClassificationInput) -> ComponentFamily {
    if !matches!(input.asset_class, AssetClass::Equity) {
        return ComponentFamily::NotEligible;
    }
    let sector = normalize(input.sector.as_deref().unwrap_or_default());
    let industry = normalize(input.industry.as_deref().unwrap_or_default());
    let blob = format!("{sector} {industry}");
    if sector.is_empty() && industry.is_empty() {
        return ComponentFamily::Unclassified;
    }
    if contains_any(
        &blob,
        &[
            "financial",
            "insurance",
            "bank",
            "capital markets",
            "asset management",
            "credit services",
            "mortgage finance",
            "reinsurance",
            "broker",
            "healthcare plans",
            "health care plans",
            "managed care",
            "health insurance",
        ],
    ) {
        return ComponentFamily::FinancialServices;
    }
    if contains_any(
        &industry,
        &[
            "regulated electric",
            "regulated gas",
            "regulated water",
            "regulated utility",
            "electric utilities",
            "gas utilities",
            "water utilities",
        ],
    ) || contains_any(&sector, &["utilities"])
    {
        return ComponentFamily::RegulatedUtility;
    }
    if contains_any(
        &industry,
        &[
            "midstream",
            "pipeline",
            "toll road",
            "airport services",
            "marine ports",
            "railroad infrastructure",
            "contracted infrastructure",
        ],
    ) {
        return ComponentFamily::ContractedInfrastructure;
    }
    if contains_any(
        &industry,
        &[
            "oil",
            "gas",
            "coal",
            "gold",
            "silver",
            "copper",
            "mining",
            "metals",
            "exploration",
            "production",
            "resource producer",
        ],
    ) || contains_any(&sector, &["energy", "basic materials"])
    {
        return ComponentFamily::ResourceProducer;
    }
    if contains_any(
        &sector,
        &[
            "technology",
            "industrials",
            "consumer",
            "healthcare",
            "communication",
            "materials",
            "real estate services",
        ],
    ) || contains_any(
        &industry,
        &[
            "software",
            "semiconductor",
            "pharma",
            "biotech",
            "drug",
            "medical device",
            "specialty industrial",
            "retail",
        ],
    ) {
        return ComponentFamily::OperatingNonFinancial;
    }
    ComponentFamily::Unclassified
}

/// Returns a family only when the closed-world classifier found an eligible
/// route.  Callers that want to value a component must use this boundary
/// instead of treating `Unclassified` as a generic FCFF default.
pub fn require_eligible_component(
    input: &ClassificationInput,
) -> Result<ComponentFamily, ValuationRefusal> {
    match route_component(input) {
        ComponentFamily::Unclassified => Err(ValuationRefusal::new(
            ValuationRefusalReasonCode::UnclassifiedSector,
            format!(
                "no valuation family for sector={:?}, industry={:?}",
                input.sector, input.industry
            ),
        )),
        ComponentFamily::NotEligible => Err(ValuationRefusal::new(
            ValuationRefusalReasonCode::NotEligible,
            "asset class is not eligible for intrinsic valuation",
        )),
        family => Ok(family),
    }
}

fn normalize(value: &str) -> String {
    value.trim().to_ascii_lowercase().replace(['_', '-'], " ")
}

fn contains_any(value: &str, terms: &[&str]) -> bool {
    terms.iter().any(|term| value.contains(term))
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum DiscountRateKind {
    Wacc,
    CostOfEquity,
    FamilySpecific,
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum SbcTreatment {
    ExpenseIncluded,
    DilutionProjected,
    Unreconciled,
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum ValuationRefusalReasonCode {
    InvalidEvidence,
    SourceRegimeUnsupported,
    UnclassifiedSector,
    IncompleteSegmentDisclosures,
    UnallocatedOverheadAmbiguity,
    UnresolvedCapitalBridge,
    MissingShares,
    MissingTerminalReinvestmentLink,
    UnreconciledSbcTreatment,
    VolumetricBaseMismatch,
    MissingFiniteResourceHorizon,
    UnhedgedResourceDriver,
    UnsupportedContractExposure,
    MissingContractTerm,
    NonConvergedRblIteration,
    MultipleRblFixedPoints,
    MissingDriverEvidence,
    HistoricalValidationCoverageUnavailable,
    DesktopSurfaceUnsupported,
    NotEligible,
    MissingRequiredDriver,
    DuplicateEvidence,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct ValuationRefusal {
    pub code: ValuationRefusalReasonCode,
    pub detail: String,
}

impl ValuationRefusal {
    pub fn new(code: ValuationRefusalReasonCode, detail: impl Into<String>) -> Self {
        Self {
            code,
            detail: detail.into(),
        }
    }

    pub fn code_str(&self) -> &'static str {
        match self.code {
            ValuationRefusalReasonCode::InvalidEvidence => "invalid_evidence",
            ValuationRefusalReasonCode::SourceRegimeUnsupported => "source_regime_unsupported",
            ValuationRefusalReasonCode::UnclassifiedSector => "unclassified_sector",
            ValuationRefusalReasonCode::IncompleteSegmentDisclosures => {
                "incomplete_segment_disclosures"
            }
            ValuationRefusalReasonCode::UnallocatedOverheadAmbiguity => {
                "unallocated_overhead_ambiguity"
            }
            ValuationRefusalReasonCode::UnresolvedCapitalBridge => "unresolved_capital_bridge",
            ValuationRefusalReasonCode::MissingShares => "missing_shares",
            ValuationRefusalReasonCode::MissingTerminalReinvestmentLink => {
                "missing_terminal_reinvestment_link"
            }
            ValuationRefusalReasonCode::UnreconciledSbcTreatment => "unreconciled_sbc_treatment",
            ValuationRefusalReasonCode::VolumetricBaseMismatch => "volumetric_base_mismatch",
            ValuationRefusalReasonCode::MissingFiniteResourceHorizon => {
                "missing_finite_resource_horizon"
            }
            ValuationRefusalReasonCode::UnhedgedResourceDriver => "unhedged_resource_driver",
            ValuationRefusalReasonCode::UnsupportedContractExposure => {
                "unsupported_contract_exposure"
            }
            ValuationRefusalReasonCode::MissingContractTerm => "missing_contract_term",
            ValuationRefusalReasonCode::NonConvergedRblIteration => "non_converged_rbl_iteration",
            ValuationRefusalReasonCode::MultipleRblFixedPoints => "multiple_rbl_fixed_points",
            ValuationRefusalReasonCode::MissingDriverEvidence => "missing_driver_evidence",
            ValuationRefusalReasonCode::HistoricalValidationCoverageUnavailable => {
                "historical_validation_coverage_unavailable"
            }
            ValuationRefusalReasonCode::DesktopSurfaceUnsupported => "desktop_surface_unsupported",
            ValuationRefusalReasonCode::NotEligible => "not_eligible",
            ValuationRefusalReasonCode::MissingRequiredDriver => "missing_required_driver",
            ValuationRefusalReasonCode::DuplicateEvidence => "duplicate_evidence",
        }
    }
}

impl fmt::Display for ValuationRefusal {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(formatter, "{}: {}", self.code_str(), self.detail)
    }
}

impl std::error::Error for ValuationRefusal {}

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum ConfidenceBand {
    Solid,
    Provisional,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ComponentQuality {
    pub evidence_quality: EvidenceQuality,
    pub confidence: ConfidenceBand,
    pub uncertainty_bps: i32,
    pub sensitivity_bps: i32,
    pub solver_stability_bps: i32,
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum ComponentStatus {
    Publishable,
    Unavailable,
    NotEligible,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ScenarioValues {
    pub bear_cents: i64,
    pub base_cents: i64,
    pub bull_cents: i64,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ComponentValuation {
    pub component_id: String,
    pub family: ComponentFamily,
    pub model: ComponentModel,
    pub status: ComponentStatus,
    pub enterprise_value_cents: i64,
    pub scenarios: ScenarioValues,
    pub discount_rate_bps: i32,
    pub discount_rate_kind: DiscountRateKind,
    pub source_regime: SourceRegime,
    pub evidence_refs: Vec<String>,
    pub quality: ComponentQuality,
    pub reason_codes: Vec<String>,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AnnualFcff {
    pub year: i32,
    pub fcff_cents: i64,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OperatingComponentInput {
    pub component_id: String,
    pub source_regime: SourceRegime,
    pub fcff_by_year: Vec<AnnualFcff>,
    pub wacc_bps: i32,
    pub near_growth_bps: i32,
    pub stable_growth_bps: i32,
    pub terminal_nopat_cents: i64,
    pub terminal_roic_bps: i32,
    pub terminal_reinvestment_bps: i32,
    pub explicit_years: u8,
    pub sbc_treatment: SbcTreatment,
    pub evidence_refs: Vec<String>,
    pub evidence_periods: usize,
    pub scenario_spread_bps: i32,
}

pub fn value_operating_component(
    input: &OperatingComponentInput,
) -> Result<ComponentValuation, ValuationRefusal> {
    require_supported_regime(input.source_regime)?;
    require_evidence(&input.component_id, &input.evidence_refs)?;
    require_period_evidence(input.evidence_periods)?;
    let latest = input
        .fcff_by_year
        .iter()
        .max_by_key(|point| point.year)
        .ok_or_else(|| {
            ValuationRefusal::new(
                ValuationRefusalReasonCode::MissingRequiredDriver,
                "operating FCFF history is empty",
            )
        })?;
    if latest.fcff_cents <= 0 || input.explicit_years == 0 || input.wacc_bps <= 0 {
        return Err(ValuationRefusal::new(
            ValuationRefusalReasonCode::MissingRequiredDriver,
            "operating FCFF, positive WACC, and explicit years are required",
        ));
    }
    if input.stable_growth_bps >= input.wacc_bps {
        return Err(ValuationRefusal::new(
            ValuationRefusalReasonCode::MissingTerminalReinvestmentLink,
            "stable growth must remain strictly below WACC",
        ));
    }
    if input.terminal_roic_bps <= 0
        || input.terminal_nopat_cents <= 0
        || !(0..=10_000).contains(&input.terminal_reinvestment_bps)
        || round_mul_div(
            input.terminal_roic_bps as i128,
            input.terminal_reinvestment_bps as i128,
            10_000,
        )
        .ok()
            != Some(input.stable_growth_bps as i64)
    {
        return Err(ValuationRefusal::new(
            ValuationRefusalReasonCode::MissingTerminalReinvestmentLink,
            "terminal growth must declare consistent ROIC and reinvestment",
        ));
    }
    if input.sbc_treatment == SbcTreatment::Unreconciled {
        return Err(ValuationRefusal::new(
            ValuationRefusalReasonCode::UnreconciledSbcTreatment,
            "SBC must be included as expense or represented once through dilution",
        ));
    }
    let spread = input.scenario_spread_bps.max(0);
    let bear = discounted_operating_value(
        latest.fcff_cents,
        input.near_growth_bps.saturating_sub(spread),
        input,
    )?;
    let base = discounted_operating_value(latest.fcff_cents, input.near_growth_bps, input)?;
    let bull = discounted_operating_value(
        latest.fcff_cents,
        input.near_growth_bps.saturating_add(spread),
        input,
    )?;
    let scenarios = ScenarioValues {
        bear_cents: bear,
        base_cents: base,
        bull_cents: bull,
    };
    Ok(ComponentValuation {
        component_id: input.component_id.clone(),
        family: ComponentFamily::OperatingNonFinancial,
        model: ComponentModel::FcffWacc,
        status: ComponentStatus::Publishable,
        enterprise_value_cents: base,
        scenarios,
        discount_rate_bps: input.wacc_bps,
        discount_rate_kind: DiscountRateKind::Wacc,
        source_regime: input.source_regime,
        evidence_refs: input.evidence_refs.clone(),
        quality: quality_for_component(input.evidence_periods, bear, base, bull),
        reason_codes: vec![
            "model=fcff_wacc".into(),
            "terminal_growth=linked_to_roic_and_reinvestment".into(),
            format!("sbc_treatment={:?}", input.sbc_treatment).to_ascii_lowercase(),
        ],
    })
}

fn discounted_operating_value(
    latest: i64,
    growth_bps: i32,
    input: &OperatingComponentInput,
) -> Result<i64, ValuationRefusal> {
    let mut pv = 0i128;
    let mut cash_flow = latest as i128;
    for year in 1..=input.explicit_years {
        cash_flow = round_mul_div(
            cash_flow,
            (10_000i32.saturating_add(growth_bps)) as i128,
            10_000,
        )? as i128;
        let factor = discount_factor_ppm(input.wacc_bps, year)? as i128;
        pv = pv
            .checked_add(
                round_div_i128(
                    cash_flow.checked_mul(factor).ok_or_else(|| {
                        ValuationRefusal::new(
                            ValuationRefusalReasonCode::MissingRequiredDriver,
                            "FCFF PV overflow",
                        )
                    })?,
                    1_000_000,
                )
                .ok_or_else(|| {
                    ValuationRefusal::new(
                        ValuationRefusalReasonCode::MissingRequiredDriver,
                        "FCFF PV invalid",
                    )
                })? as i128,
            )
            .ok_or_else(|| {
                ValuationRefusal::new(
                    ValuationRefusalReasonCode::MissingRequiredDriver,
                    "FCFF PV overflow",
                )
            })?;
    }
    let terminal_fcff = round_mul_div(
        input.terminal_nopat_cents as i128,
        (10_000 - input.terminal_reinvestment_bps) as i128,
        10_000,
    )? as i128;
    let terminal = terminal_fcff
        .checked_mul(10_000)
        .and_then(|value| value.checked_div((input.wacc_bps - input.stable_growth_bps) as i128))
        .ok_or_else(|| {
            ValuationRefusal::new(
                ValuationRefusalReasonCode::MissingRequiredDriver,
                "terminal value invalid",
            )
        })?;
    let factor = discount_factor_ppm(input.wacc_bps, input.explicit_years)? as i128;
    pv = pv
        .checked_add(
            round_div_i128(
                terminal.checked_mul(factor).ok_or_else(|| {
                    ValuationRefusal::new(
                        ValuationRefusalReasonCode::MissingRequiredDriver,
                        "terminal PV overflow",
                    )
                })?,
                1_000_000,
            )
            .ok_or_else(|| {
                ValuationRefusal::new(
                    ValuationRefusalReasonCode::MissingRequiredDriver,
                    "terminal PV invalid",
                )
            })? as i128,
        )
        .ok_or_else(|| {
            ValuationRefusal::new(
                ValuationRefusalReasonCode::MissingRequiredDriver,
                "terminal PV overflow",
            )
        })?;
    i64::try_from(pv).map_err(|_| {
        ValuationRefusal::new(
            ValuationRefusalReasonCode::MissingRequiredDriver,
            "operating enterprise value exceeds fixed-point range",
        )
    })
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FinancialServicesComponentInput {
    pub component_id: String,
    pub source_regime: SourceRegime,
    pub book_equity_cents: i64,
    pub shares: i64,
    pub roe_bps: i32,
    pub retention_bps: i32,
    pub cost_of_equity_bps: i32,
    pub stable_roe_bps: i32,
    pub explicit_years: u8,
    pub evidence_refs: Vec<String>,
    pub evidence_periods: usize,
    pub scenario_roe_spread_bps: i32,
    pub scenario_rate_spread_bps: i32,
}

pub fn value_financial_services(
    input: &FinancialServicesComponentInput,
) -> Result<ComponentValuation, ValuationRefusal> {
    require_supported_regime(input.source_regime)?;
    require_evidence(&input.component_id, &input.evidence_refs)?;
    require_period_evidence(input.evidence_periods)?;
    if input.book_equity_cents <= 0
        || input.shares <= 0
        || input.explicit_years == 0
        || input.cost_of_equity_bps <= 0
        || !(0..=10_000).contains(&input.retention_bps)
    {
        return Err(ValuationRefusal::new(
            ValuationRefusalReasonCode::MissingRequiredDriver,
            "positive book, shares, cost of equity, retention, and explicit years are required",
        ));
    }
    let spread = input.scenario_roe_spread_bps.max(0);
    let re_spread = input.scenario_rate_spread_bps.max(0);
    let bear = residual_income_value(
        input,
        input.roe_bps.saturating_sub(spread),
        input.cost_of_equity_bps.saturating_add(re_spread),
    )?;
    let base = residual_income_value(input, input.roe_bps, input.cost_of_equity_bps)?;
    let bull_rate = input.cost_of_equity_bps.saturating_sub(re_spread).max(1);
    let bull = residual_income_value(input, input.roe_bps.saturating_add(spread), bull_rate)?;
    Ok(ComponentValuation {
        component_id: input.component_id.clone(),
        family: ComponentFamily::FinancialServices,
        model: ComponentModel::ResidualIncomeEquity,
        status: ComponentStatus::Publishable,
        enterprise_value_cents: base,
        scenarios: ScenarioValues {
            bear_cents: bear,
            base_cents: base,
            bull_cents: bull,
        },
        discount_rate_bps: input.cost_of_equity_bps,
        discount_rate_kind: DiscountRateKind::CostOfEquity,
        source_regime: input.source_regime,
        evidence_refs: input.evidence_refs.clone(),
        quality: quality_for_component(input.evidence_periods, bear, base, bull),
        reason_codes: vec![
            "model=residual_income_equity".into(),
            "primary_cash_flow_definition=not_ocf_minus_ppe_capex".into(),
            "terminal_roe=fades_to_competitive_long_run".into(),
        ],
    })
}

fn residual_income_value(
    input: &FinancialServicesComponentInput,
    initial_roe_bps: i32,
    re_bps: i32,
) -> Result<i64, ValuationRefusal> {
    if re_bps <= 0 {
        return Err(ValuationRefusal::new(
            ValuationRefusalReasonCode::MissingRequiredDriver,
            "cost of equity must be positive",
        ));
    }
    let mut book = input.book_equity_cents as i128;
    let mut pv = 0i128;
    for year in 1..=input.explicit_years {
        let year_i = year as i128;
        let total_years = input.explicit_years as i128;
        let roe = initial_roe_bps as i128
            + (input.stable_roe_bps as i128 - initial_roe_bps as i128) * year_i / total_years;
        let residual = book
            .checked_mul(roe - re_bps as i128)
            .and_then(|value| value.checked_div(10_000))
            .ok_or_else(|| {
                ValuationRefusal::new(
                    ValuationRefusalReasonCode::MissingRequiredDriver,
                    "residual income overflow",
                )
            })?;
        let factor = discount_factor_ppm(re_bps, year)? as i128;
        pv = pv
            .checked_add(
                round_div_i128(
                    residual.checked_mul(factor).ok_or_else(|| {
                        ValuationRefusal::new(
                            ValuationRefusalReasonCode::MissingRequiredDriver,
                            "residual income PV overflow",
                        )
                    })?,
                    1_000_000,
                )
                .ok_or_else(|| {
                    ValuationRefusal::new(
                        ValuationRefusalReasonCode::MissingRequiredDriver,
                        "residual income PV invalid",
                    )
                })? as i128,
            )
            .ok_or_else(|| {
                ValuationRefusal::new(
                    ValuationRefusalReasonCode::MissingRequiredDriver,
                    "residual income PV overflow",
                )
            })?;
        book = book
            .checked_add(
                round_mul_div(book, roe * input.retention_bps as i128, 100_000_000)? as i128,
            )
            .ok_or_else(|| {
                ValuationRefusal::new(
                    ValuationRefusalReasonCode::MissingRequiredDriver,
                    "book value overflow",
                )
            })?;
        if book <= 0 {
            return Err(ValuationRefusal::new(
                ValuationRefusalReasonCode::MissingRequiredDriver,
                "clean-surplus book value became non-positive",
            ));
        }
    }
    let stable_growth = round_mul_div(
        input.stable_roe_bps as i128,
        input.retention_bps as i128,
        10_000,
    )? as i32;
    if input.stable_roe_bps != re_bps && stable_growth >= re_bps {
        return Err(ValuationRefusal::new(
            ValuationRefusalReasonCode::MissingTerminalReinvestmentLink,
            "stable book growth must remain below cost of equity",
        ));
    }
    let terminal = if input.stable_roe_bps == re_bps {
        book
    } else {
        let next_book = round_mul_div(book, (10_000 + stable_growth) as i128, 10_000)? as i128;
        let residual_next =
            round_mul_div(next_book, (input.stable_roe_bps - re_bps) as i128, 10_000)? as i128;
        book.checked_add(
            residual_next
                .checked_mul(10_000)
                .and_then(|value| value.checked_div((re_bps - stable_growth) as i128))
                .ok_or_else(|| {
                    ValuationRefusal::new(
                        ValuationRefusalReasonCode::MissingRequiredDriver,
                        "terminal residual value invalid",
                    )
                })?,
        )
        .ok_or_else(|| {
            ValuationRefusal::new(
                ValuationRefusalReasonCode::MissingRequiredDriver,
                "terminal equity value overflow",
            )
        })?
    };
    let factor = discount_factor_ppm(re_bps, input.explicit_years)? as i128;
    let terminal_pv = round_div_i128(
        terminal.checked_mul(factor).ok_or_else(|| {
            ValuationRefusal::new(
                ValuationRefusalReasonCode::MissingRequiredDriver,
                "terminal PV overflow",
            )
        })?,
        1_000_000,
    )
    .ok_or_else(|| {
        ValuationRefusal::new(
            ValuationRefusalReasonCode::MissingRequiredDriver,
            "terminal PV invalid",
        )
    })? as i128;
    let value = (input.book_equity_cents as i128)
        .checked_add(pv)
        .and_then(|value| value.checked_add(terminal_pv))
        .ok_or_else(|| {
            ValuationRefusal::new(
                ValuationRefusalReasonCode::MissingRequiredDriver,
                "residual income value overflow",
            )
        })?;
    i64::try_from(value).map_err(|_| {
        ValuationRefusal::new(
            ValuationRefusalReasonCode::MissingRequiredDriver,
            "residual income value exceeds fixed-point range",
        )
    })
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum VolumetricBase {
    Gross,
    WorkingInterest,
    NetRevenueInterest,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AnnualQuantity {
    pub year: i32,
    pub value_millis: i64,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct VolumeReconciliation {
    pub from: VolumetricBase,
    pub to: VolumetricBase,
    pub adjustment_bps: i32,
    pub evidence_refs: Vec<String>,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CommodityDriver {
    pub commodity: String,
    pub volume_millis: Vec<AnnualQuantity>,
    pub volumetric_base: VolumetricBase,
    pub base_reconciliation: Option<VolumeReconciliation>,
    pub price_cents_per_unit: i64,
    pub hedge_cents_per_unit: Option<i64>,
    pub hedge_is_unrealized: bool,
    pub cash_cost_cents_per_unit: i64,
    pub sustaining_capex_cents_per_unit: i64,
    pub reserves_millis: i64,
    pub decline_bps: i32,
    pub development_capex_cents_per_year: i64,
    pub finite_horizon_years: Option<u8>,
    pub evidence_refs: Vec<String>,
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum RblStatus {
    Converged,
    NonConverged,
    MultipleFixedPoints,
    Unavailable,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RblDiagnostics {
    pub status: RblStatus,
    pub iterations: u16,
    pub max_delta_cents: i64,
    pub fixed_point_count: u16,
    pub evidence_refs: Vec<String>,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ResourceProducerInput {
    pub component_id: String,
    pub source_regime: SourceRegime,
    pub commodities: Vec<CommodityDriver>,
    pub discount_rate_bps: i32,
    pub requires_rbl: bool,
    pub rbl: Option<RblDiagnostics>,
    pub evidence_periods: usize,
}

pub fn value_resource_producer(
    input: &ResourceProducerInput,
) -> Result<ComponentValuation, ValuationRefusal> {
    require_supported_regime(input.source_regime)?;
    require_period_evidence(input.evidence_periods)?;
    if input.commodities.is_empty() || input.discount_rate_bps <= 0 {
        return Err(ValuationRefusal::new(
            ValuationRefusalReasonCode::MissingRequiredDriver,
            "resource valuation needs commodities and a positive family discount rate",
        ));
    }
    if input.requires_rbl {
        let rbl = input.rbl.as_ref().ok_or_else(|| {
            ValuationRefusal::new(
                ValuationRefusalReasonCode::NonConvergedRblIteration,
                "RBL evidence is required for this resource component",
            )
        })?;
        match rbl.status {
            RblStatus::Converged if rbl.fixed_point_count <= 1 && rbl.iterations > 0 => {}
            RblStatus::Converged => {
                return Err(ValuationRefusal::new(
                    if rbl.fixed_point_count > 1 {
                        ValuationRefusalReasonCode::MultipleRblFixedPoints
                    } else {
                        ValuationRefusalReasonCode::NonConvergedRblIteration
                    },
                    "RBL diagnostics contradict a unique converged solve",
                ))
            }
            RblStatus::MultipleFixedPoints => {
                return Err(ValuationRefusal::new(
                    ValuationRefusalReasonCode::MultipleRblFixedPoints,
                    "RBL solve has multiple fixed points",
                ))
            }
            RblStatus::NonConverged | RblStatus::Unavailable => {
                return Err(ValuationRefusal::new(
                    ValuationRefusalReasonCode::NonConvergedRblIteration,
                    "RBL solve did not converge",
                ))
            }
        }
    }
    let target_base = input.commodities[0].volumetric_base;
    let mut value = 0i128;
    let mut horizon = 0u8;
    for driver in &input.commodities {
        require_evidence(&driver.commodity, &driver.evidence_refs)?;
        let years = driver.finite_horizon_years.ok_or_else(|| {
            ValuationRefusal::new(
                ValuationRefusalReasonCode::MissingFiniteResourceHorizon,
                format!("{} has no finite reserve horizon", driver.commodity),
            )
        })?;
        if years == 0 || driver.reserves_millis <= 0 || driver.volume_millis.is_empty() {
            return Err(ValuationRefusal::new(
                ValuationRefusalReasonCode::MissingFiniteResourceHorizon,
                format!("{} has no usable finite reserves", driver.commodity),
            ));
        }
        if driver.hedge_is_unrealized {
            return Err(ValuationRefusal::new(
                ValuationRefusalReasonCode::UnhedgedResourceDriver,
                format!("{} uses unrealized hedge marks", driver.commodity),
            ));
        }
        if !(0..=10_000).contains(&driver.decline_bps)
            || driver.price_cents_per_unit <= 0
            || driver.cash_cost_cents_per_unit < 0
            || driver.sustaining_capex_cents_per_unit < 0
            || driver.development_capex_cents_per_year < 0
        {
            return Err(ValuationRefusal::new(
                ValuationRefusalReasonCode::MissingRequiredDriver,
                format!(
                    "{} has invalid fixed-point resource drivers",
                    driver.commodity
                ),
            ));
        }
        if driver.volumetric_base != target_base {
            let reconciliation = driver
                .base_reconciliation
                .as_ref()
                .filter(|reconciliation| {
                    reconciliation.from == driver.volumetric_base
                        && reconciliation.to == target_base
                        && !reconciliation.evidence_refs.is_empty()
                })
                .ok_or_else(|| {
                    ValuationRefusal::new(
                        ValuationRefusalReasonCode::VolumetricBaseMismatch,
                        format!(
                            "{} mixes {:?} with {:?} without evidenced reconciliation",
                            driver.commodity, driver.volumetric_base, target_base
                        ),
                    )
                })?;
            if !(0..=20_000).contains(&reconciliation.adjustment_bps) {
                return Err(ValuationRefusal::new(
                    ValuationRefusalReasonCode::VolumetricBaseMismatch,
                    "volume reconciliation adjustment is outside fixed-point bounds",
                ));
            }
        }
        horizon = horizon.max(years);
        let initial = driver
            .volume_millis
            .iter()
            .max_by_key(|point| point.year)
            .map(|point| point.value_millis)
            .unwrap_or(0);
        if initial <= 0 {
            return Err(ValuationRefusal::new(
                ValuationRefusalReasonCode::MissingRequiredDriver,
                format!("{} production volume is non-positive", driver.commodity),
            ));
        }
        let mut reserve_remaining = driver.reserves_millis as i128;
        for year in 0..years {
            if reserve_remaining <= 0 {
                break;
            }
            let decline_factor = pow_ratio(
                10_000i128.saturating_sub(driver.decline_bps.max(0) as i128),
                10_000,
                year as usize,
            )?;
            let mut volume =
                round_mul_div(initial as i128, decline_factor as i128, 1_000_000)? as i128;
            if driver.volumetric_base != target_base {
                let reconciliation = driver
                    .base_reconciliation
                    .as_ref()
                    .expect("validated above");
                volume =
                    round_mul_div(volume, reconciliation.adjustment_bps as i128, 10_000)? as i128;
            }
            volume = volume.min(reserve_remaining).max(0);
            reserve_remaining -= volume;
            let realized_price = driver
                .price_cents_per_unit
                .saturating_add(driver.hedge_cents_per_unit.unwrap_or(0));
            let revenue = round_mul_div(volume, realized_price as i128, 1_000)? as i128;
            let cash_cost =
                round_mul_div(volume, driver.cash_cost_cents_per_unit as i128, 1_000)? as i128;
            let sustaining = round_mul_div(
                volume,
                driver.sustaining_capex_cents_per_unit as i128,
                1_000,
            )? as i128;
            let free_cash_flow = revenue
                .saturating_sub(cash_cost)
                .saturating_sub(sustaining)
                .saturating_sub(driver.development_capex_cents_per_year as i128);
            let factor =
                discount_factor_ppm(input.discount_rate_bps, year.saturating_add(1))? as i128;
            value = value
                .checked_add(
                    round_div_i128(
                        free_cash_flow.checked_mul(factor).ok_or_else(|| {
                            ValuationRefusal::new(
                                ValuationRefusalReasonCode::MissingRequiredDriver,
                                "resource PV overflow",
                            )
                        })?,
                        1_000_000,
                    )
                    .ok_or_else(|| {
                        ValuationRefusal::new(
                            ValuationRefusalReasonCode::MissingRequiredDriver,
                            "resource PV invalid",
                        )
                    })? as i128,
                )
                .ok_or_else(|| {
                    ValuationRefusal::new(
                        ValuationRefusalReasonCode::MissingRequiredDriver,
                        "resource PV overflow",
                    )
                })?;
        }
    }
    let base = i64::try_from(value).map_err(|_| {
        ValuationRefusal::new(
            ValuationRefusalReasonCode::MissingRequiredDriver,
            "resource enterprise value exceeds fixed-point range",
        )
    })?;
    let solver_stability_bps = input
        .rbl
        .as_ref()
        .map(|rbl| {
            round_div_i128(
                i128::from(rbl.max_delta_cents.saturating_abs()) * 10_000,
                i128::from(base.saturating_abs().max(1)),
            )
            .unwrap_or(i64::from(i32::MAX))
            .clamp(0, i64::from(i32::MAX)) as i32
        })
        .unwrap_or(0);
    Ok(ComponentValuation {
        component_id: input.component_id.clone(),
        family: ComponentFamily::ResourceProducer,
        model: ComponentModel::ResourceFinite,
        status: ComponentStatus::Publishable,
        enterprise_value_cents: base,
        scenarios: ScenarioValues {
            bear_cents: base,
            base_cents: base,
            bull_cents: base,
        },
        discount_rate_bps: input.discount_rate_bps,
        discount_rate_kind: DiscountRateKind::FamilySpecific,
        source_regime: input.source_regime,
        evidence_refs: input
            .commodities
            .iter()
            .flat_map(|driver| driver.evidence_refs.clone())
            .collect(),
        quality: ComponentQuality {
            evidence_quality: EvidenceQuality::from_period_count(input.evidence_periods),
            confidence: if input.evidence_periods >= 3 {
                ConfidenceBand::Solid
            } else {
                ConfidenceBand::Provisional
            },
            uncertainty_bps: if input.evidence_periods >= 3 {
                2_500
            } else {
                5_000
            },
            sensitivity_bps: 2_500,
            solver_stability_bps,
        },
        reason_codes: vec![
            "model=resource_finite".into(),
            format!("reserve_horizon_years={horizon}"),
            "hedges=realized_or_contractual_only".into(),
        ],
    })
}

fn pow_ratio(numerator: i128, denominator: i128, exponent: usize) -> Result<i64, ValuationRefusal> {
    let mut result = 1_000_000i128;
    for _ in 0..exponent {
        result = round_mul_div(result, numerator, denominator)? as i128;
    }
    i64::try_from(result).map_err(|_| {
        ValuationRefusal::new(
            ValuationRefusalReasonCode::MissingRequiredDriver,
            "fixed-point ratio overflow",
        )
    })
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum ContractExposureKind {
    TakeOrPay,
    FeeVolumetric,
    PercentOfProceeds,
    Unsupported,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ContractExposure {
    pub name: String,
    pub kind: ContractExposureKind,
    pub base_revenue_cents_per_year: i64,
    pub annual_escalation_bps: i32,
    pub remaining_years: Option<u8>,
    pub volume_millis_per_year: Option<i64>,
    pub fee_cents_per_unit: Option<i64>,
    pub proceeds_cents_per_year: Option<i64>,
    pub percent_of_proceeds_bps: Option<i32>,
    pub evidence_refs: Vec<String>,
    pub material: bool,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ContractedInfrastructureInput {
    pub component_id: String,
    pub source_regime: SourceRegime,
    pub exposures: Vec<ContractExposure>,
    pub operating_cost_cents_per_year: i64,
    pub maintenance_capex_cents_per_year: i64,
    pub discount_rate_bps: i32,
    pub evidence_periods: usize,
}

pub fn value_contracted_infrastructure(
    input: &ContractedInfrastructureInput,
) -> Result<ComponentValuation, ValuationRefusal> {
    require_supported_regime(input.source_regime)?;
    require_period_evidence(input.evidence_periods)?;
    if input.exposures.is_empty() || input.discount_rate_bps <= 0 {
        return Err(ValuationRefusal::new(
            ValuationRefusalReasonCode::MissingRequiredDriver,
            "contracted infrastructure needs exposures and a positive rate",
        ));
    }
    let horizon = input
        .exposures
        .iter()
        .filter_map(|exposure| exposure.remaining_years)
        .max()
        .ok_or_else(|| {
            ValuationRefusal::new(
                ValuationRefusalReasonCode::MissingContractTerm,
                "contract term is missing",
            )
        })?;
    if horizon == 0 {
        return Err(ValuationRefusal::new(
            ValuationRefusalReasonCode::MissingContractTerm,
            "contract term is zero",
        ));
    }
    let mut value = 0i128;
    for year in 0..horizon {
        let mut revenue = 0i128;
        for exposure in &input.exposures {
            let years = exposure.remaining_years.ok_or_else(|| {
                ValuationRefusal::new(
                    ValuationRefusalReasonCode::MissingContractTerm,
                    format!("{} has no expiry horizon", exposure.name),
                )
            })?;
            if year >= years {
                continue;
            }
            require_evidence(&exposure.name, &exposure.evidence_refs)?;
            let base = match exposure.kind {
                ContractExposureKind::TakeOrPay => exposure.base_revenue_cents_per_year,
                ContractExposureKind::FeeVolumetric => {
                    let volume = exposure.volume_millis_per_year.ok_or_else(|| {
                        ValuationRefusal::new(
                            ValuationRefusalReasonCode::UnsupportedContractExposure,
                            format!("{} lacks contracted volume", exposure.name),
                        )
                    })?;
                    let fee = exposure.fee_cents_per_unit.ok_or_else(|| {
                        ValuationRefusal::new(
                            ValuationRefusalReasonCode::UnsupportedContractExposure,
                            format!("{} lacks fee evidence", exposure.name),
                        )
                    })?;
                    round_mul_div(volume as i128, fee as i128, 1_000)?
                }
                ContractExposureKind::PercentOfProceeds => {
                    let proceeds = exposure.proceeds_cents_per_year.ok_or_else(|| {
                        ValuationRefusal::new(
                            ValuationRefusalReasonCode::UnsupportedContractExposure,
                            format!("{} lacks proceeds evidence", exposure.name),
                        )
                    })?;
                    let share = exposure.percent_of_proceeds_bps.ok_or_else(|| {
                        ValuationRefusal::new(
                            ValuationRefusalReasonCode::UnsupportedContractExposure,
                            format!("{} lacks proceeds share", exposure.name),
                        )
                    })?;
                    round_mul_div(proceeds as i128, share as i128, 10_000)?
                }
                ContractExposureKind::Unsupported => {
                    return Err(ValuationRefusal::new(
                        ValuationRefusalReasonCode::UnsupportedContractExposure,
                        format!("{} has unsupported revenue exposure", exposure.name),
                    ))
                }
            };
            let escalation_factor = pow_ratio(
                (10_000 + exposure.annual_escalation_bps.max(-9_999)) as i128,
                10_000,
                year as usize,
            )? as i128;
            revenue =
                revenue.saturating_add(
                    round_mul_div(base as i128, escalation_factor, 1_000_000)? as i128
                );
        }
        let cash_flow = revenue
            .saturating_sub(input.operating_cost_cents_per_year as i128)
            .saturating_sub(input.maintenance_capex_cents_per_year as i128);
        let factor = discount_factor_ppm(input.discount_rate_bps, year.saturating_add(1))? as i128;
        value = value
            .checked_add(
                round_div_i128(
                    cash_flow.checked_mul(factor).ok_or_else(|| {
                        ValuationRefusal::new(
                            ValuationRefusalReasonCode::MissingRequiredDriver,
                            "contract PV overflow",
                        )
                    })?,
                    1_000_000,
                )
                .ok_or_else(|| {
                    ValuationRefusal::new(
                        ValuationRefusalReasonCode::MissingRequiredDriver,
                        "contract PV invalid",
                    )
                })? as i128,
            )
            .ok_or_else(|| {
                ValuationRefusal::new(
                    ValuationRefusalReasonCode::MissingRequiredDriver,
                    "contract PV overflow",
                )
            })?;
    }
    let base = i64::try_from(value).map_err(|_| {
        ValuationRefusal::new(
            ValuationRefusalReasonCode::MissingRequiredDriver,
            "contract enterprise value exceeds fixed-point range",
        )
    })?;
    Ok(ComponentValuation {
        component_id: input.component_id.clone(),
        family: ComponentFamily::ContractedInfrastructure,
        model: ComponentModel::ContractedInfrastructure,
        status: ComponentStatus::Publishable,
        enterprise_value_cents: base,
        scenarios: ScenarioValues {
            bear_cents: base,
            base_cents: base,
            bull_cents: base,
        },
        discount_rate_bps: input.discount_rate_bps,
        discount_rate_kind: DiscountRateKind::FamilySpecific,
        source_regime: input.source_regime,
        evidence_refs: input
            .exposures
            .iter()
            .flat_map(|exposure| exposure.evidence_refs.clone())
            .collect(),
        quality: ComponentQuality {
            evidence_quality: EvidenceQuality::from_period_count(input.evidence_periods),
            confidence: if input.evidence_periods >= 3 {
                ConfidenceBand::Solid
            } else {
                ConfidenceBand::Provisional
            },
            uncertainty_bps: if input.evidence_periods >= 3 {
                2_000
            } else {
                5_000
            },
            sensitivity_bps: 2_000,
            solver_stability_bps: 0,
        },
        reason_codes: vec![
            "model=contracted_infrastructure".into(),
            "revenue=contract-exposure-specific".into(),
            "terminal_value=finite_contract_horizon".into(),
        ],
    })
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RegulatedUtilityInput {
    pub component_id: String,
    pub source_regime: SourceRegime,
    pub rate_base_cents: i64,
    pub allowed_roe_bps: i32,
    pub cost_of_equity_bps: i32,
    pub reinvestment_bps: i32,
    pub explicit_years: u8,
    pub evidence_refs: Vec<String>,
    pub evidence_periods: usize,
}

pub fn value_regulated_utility(
    input: &RegulatedUtilityInput,
) -> Result<ComponentValuation, ValuationRefusal> {
    require_supported_regime(input.source_regime)?;
    require_evidence(&input.component_id, &input.evidence_refs)?;
    require_period_evidence(input.evidence_periods)?;
    if input.rate_base_cents <= 0
        || input.allowed_roe_bps <= 0
        || input.cost_of_equity_bps <= 0
        || input.explicit_years == 0
        || !(0..=10_000).contains(&input.reinvestment_bps)
    {
        return Err(ValuationRefusal::new(
            ValuationRefusalReasonCode::MissingRequiredDriver,
            "rate base, allowed ROE, cost of equity, reinvestment, and horizon are required",
        ));
    }
    let mut value = input.rate_base_cents as i128;
    let excess = (input.allowed_roe_bps - input.cost_of_equity_bps) as i128;
    for year in 1..=input.explicit_years {
        let reinvested = round_mul_div(
            input.rate_base_cents as i128,
            input.reinvestment_bps as i128,
            10_000,
        )? as i128;
        let excess_return = round_mul_div(reinvested, excess, 10_000)? as i128;
        let factor = discount_factor_ppm(input.cost_of_equity_bps, year)? as i128;
        value = value.saturating_add(
            round_div_i128(
                excess_return.checked_mul(factor).ok_or_else(|| {
                    ValuationRefusal::new(
                        ValuationRefusalReasonCode::MissingRequiredDriver,
                        "utility PV overflow",
                    )
                })?,
                1_000_000,
            )
            .ok_or_else(|| {
                ValuationRefusal::new(
                    ValuationRefusalReasonCode::MissingRequiredDriver,
                    "utility PV invalid",
                )
            })? as i128,
        );
    }
    let base = i64::try_from(value).map_err(|_| {
        ValuationRefusal::new(
            ValuationRefusalReasonCode::MissingRequiredDriver,
            "utility enterprise value exceeds fixed-point range",
        )
    })?;
    Ok(ComponentValuation {
        component_id: input.component_id.clone(),
        family: ComponentFamily::RegulatedUtility,
        model: ComponentModel::RegulatedUtility,
        status: ComponentStatus::Publishable,
        enterprise_value_cents: base,
        scenarios: ScenarioValues {
            bear_cents: base,
            base_cents: base,
            bull_cents: base,
        },
        discount_rate_bps: input.cost_of_equity_bps,
        discount_rate_kind: DiscountRateKind::FamilySpecific,
        source_regime: input.source_regime,
        evidence_refs: input.evidence_refs.clone(),
        quality: quality_for_component(input.evidence_periods, base, base, base),
        reason_codes: vec![
            "model=regulated_utility".into(),
            "driver=allowed_roe_and_rate_base".into(),
        ],
    })
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SotpComponent {
    pub component_id: String,
    pub material: bool,
    pub valuation: Option<ComponentValuation>,
    pub refusal: Option<ValuationRefusalWire>,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ValuationRefusalWire {
    pub code: String,
    pub detail: String,
}

impl From<&ValuationRefusal> for ValuationRefusalWire {
    fn from(value: &ValuationRefusal) -> Self {
        Self {
            code: value.code_str().into(),
            detail: value.detail.clone(),
        }
    }
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BridgeEvidence {
    pub amount_cents: i64,
    pub evidence_refs: Vec<String>,
}

impl BridgeEvidence {
    fn validate(&self, name: &str) -> Result<(), ValuationRefusal> {
        require_evidence(name, &self.evidence_refs)
    }
}

#[derive(Clone, Debug, Default, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CapitalBridge {
    pub net_debt: Option<BridgeEvidence>,
    pub non_controlling_interest: Option<BridgeEvidence>,
    pub preferred_claims: Option<BridgeEvidence>,
    pub other_senior_claims: Option<BridgeEvidence>,
    pub separately_valued_investments: Vec<BridgeEvidence>,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CorporateOverhead {
    pub enterprise_value_cents: i64,
    pub material: bool,
    pub evidence_refs: Vec<String>,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SotpInput {
    pub issuer: String,
    pub components: Vec<SotpComponent>,
    pub corporate_overhead: Option<CorporateOverhead>,
    pub bridge: CapitalBridge,
    pub shares: Option<BridgeEvidence>,
    pub source_fingerprint: String,
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum SotpStatus {
    Published,
    CoveredEvOnly,
    Unavailable,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SotpOutput {
    pub status: SotpStatus,
    pub covered_enterprise_value_cents: Option<i64>,
    pub equity_value_cents: Option<i64>,
    pub intrinsic_price_cents: Option<i64>,
    pub valuation_score_eligible: bool,
    pub reason_codes: Vec<String>,
    pub component_quality: EvidenceQuality,
    pub engine_version: String,
    pub model_policy_version: String,
    pub resolver_policy_version: String,
    pub source_fingerprint: String,
}

pub fn consolidate_sotp(input: &SotpInput) -> SotpOutput {
    let mut reasons = Vec::new();
    let mut covered_ev = 0i128;
    let mut all_valued =
        !input.issuer.trim().is_empty() && !input.source_fingerprint.trim().is_empty();
    let mut quality = EvidenceQuality::Solid;
    if input.issuer.trim().is_empty() || input.source_fingerprint.trim().is_empty() {
        reasons.push("invalid_evidence".into());
    }
    for component in &input.components {
        match &component.valuation {
            Some(valuation) => {
                if valuation.component_id != component.component_id
                    || valuation.status != ComponentStatus::Publishable
                    || !valuation
                        .evidence_refs
                        .iter()
                        .any(|reference| !reference.trim().is_empty())
                {
                    all_valued = false;
                    reasons.push("invalid_evidence".into());
                } else {
                    covered_ev =
                        covered_ev.saturating_add(valuation.enterprise_value_cents as i128);
                    if valuation.quality.evidence_quality == EvidenceQuality::Provisional {
                        quality = EvidenceQuality::Provisional;
                    }
                }
            }
            None if component.material => {
                all_valued = false;
                reasons.push(
                    component
                        .refusal
                        .as_ref()
                        .map(|refusal| refusal.code.clone())
                        .unwrap_or_else(|| "incomplete_segment_disclosures".into()),
                );
            }
            None => {
                quality = EvidenceQuality::Provisional;
                reasons.push("immaterial_component_unresolved".into());
            }
        }
    }
    match &input.corporate_overhead {
        Some(overhead) => {
            let evidenced = overhead
                .evidence_refs
                .iter()
                .any(|reference| !reference.trim().is_empty());
            if !evidenced {
                all_valued = false;
                reasons.push("unallocated_overhead_ambiguity".into());
                if !overhead.material {
                    quality = EvidenceQuality::Provisional;
                }
            } else if overhead.enterprise_value_cents > 0 {
                reasons.push("unallocated_overhead_ambiguity".into());
                all_valued = false;
            } else {
                covered_ev = covered_ev.saturating_add(overhead.enterprise_value_cents as i128);
                if !overhead.material {
                    quality = EvidenceQuality::Provisional;
                }
            }
        }
        None => {
            all_valued = false;
            reasons.push("unallocated_overhead_ambiguity".into());
        }
    }
    for investment in &input.bridge.separately_valued_investments {
        if investment.evidence_refs.is_empty() {
            all_valued = false;
            reasons.push("unresolved_capital_bridge".into());
        } else {
            covered_ev = covered_ev.saturating_add(investment.amount_cents as i128);
        }
    }
    let bridge_values = [
        ("net_debt", input.bridge.net_debt.as_ref()),
        (
            "non_controlling_interest",
            input.bridge.non_controlling_interest.as_ref(),
        ),
        ("preferred_claims", input.bridge.preferred_claims.as_ref()),
        (
            "other_senior_claims",
            input.bridge.other_senior_claims.as_ref(),
        ),
    ];
    let mut claims = 0i128;
    for (name, item) in bridge_values {
        match item {
            Some(item) if item.validate(name).is_ok() => {
                claims = claims.saturating_add(item.amount_cents as i128)
            }
            _ => {
                all_valued = false;
                reasons.push("unresolved_capital_bridge".into());
            }
        }
    }
    let covered = i64::try_from(covered_ev).ok();
    let shares = input
        .shares
        .as_ref()
        .filter(|shares| shares.amount_cents > 0 && !shares.evidence_refs.is_empty());
    let (equity_value_cents, intrinsic_price_cents) = if all_valued && shares.is_some() {
        let equity = (covered_ev - claims).max(0);
        let price = round_div_i128(equity, shares.expect("checked").amount_cents as i128);
        (i64::try_from(equity).ok(), price)
    } else {
        if shares.is_none() {
            reasons.push("missing_shares".into());
        }
        (None, None)
    };
    reasons.sort();
    reasons.dedup();
    let status = if equity_value_cents.is_some() {
        SotpStatus::Published
    } else if covered.is_some() {
        SotpStatus::CoveredEvOnly
    } else {
        SotpStatus::Unavailable
    };
    SotpOutput {
        status,
        covered_enterprise_value_cents: covered,
        equity_value_cents,
        intrinsic_price_cents,
        valuation_score_eligible: equity_value_cents.is_some() && reasons.is_empty(),
        reason_codes: reasons,
        component_quality: quality,
        engine_version: ENGINE_VERSION.into(),
        model_policy_version: MODEL_POLICY_VERSION.into(),
        resolver_policy_version: RESOLVER_POLICY_VERSION.into(),
        source_fingerprint: input.source_fingerprint.clone(),
    }
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum CacheDisposition {
    StorePublishable,
    StoreCoveredEvOnly,
    ClearStaleIntrinsic,
}

pub fn cache_disposition(status: SotpStatus) -> CacheDisposition {
    match status {
        SotpStatus::Published => CacheDisposition::StorePublishable,
        SotpStatus::CoveredEvOnly => CacheDisposition::StoreCoveredEvOnly,
        SotpStatus::Unavailable => CacheDisposition::ClearStaleIntrinsic,
    }
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ValuationCacheKey {
    pub issuer: String,
    pub source_fingerprint: String,
    pub driver_fingerprint: String,
    pub engine_version: String,
    pub model_policy_version: String,
    pub resolver_policy_version: String,
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum DisagreementStatus {
    Aligned,
    Tension,
    Disputed,
    Unavailable,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AnalystRange {
    pub low_cents: i64,
    pub base_cents: i64,
    pub high_cents: i64,
    pub horizon_days: u16,
    pub definition: String,
    pub evidence_refs: Vec<String>,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DisagreementResult {
    pub status: DisagreementStatus,
    pub anchor_gap_bps: Option<i32>,
    pub reason_codes: Vec<String>,
}

pub fn compare_external_range(
    intrinsic_base_cents: i64,
    intrinsic_horizon_days: u16,
    intrinsic_definition: &str,
    analyst: &AnalystRange,
) -> DisagreementResult {
    if intrinsic_base_cents <= 0
        || analyst.base_cents <= 0
        || analyst.low_cents > analyst.base_cents
        || analyst.base_cents > analyst.high_cents
        || analyst.horizon_days != intrinsic_horizon_days
        || analyst.definition != intrinsic_definition
        || analyst.evidence_refs.is_empty()
    {
        return DisagreementResult {
            status: DisagreementStatus::Unavailable,
            anchor_gap_bps: None,
            reason_codes: vec!["incompatible_external_anchor".into()],
        };
    }
    let gap = round_div_i128(
        (intrinsic_base_cents - analyst.base_cents).abs() as i128 * 10_000,
        analyst.base_cents as i128,
    )
    .unwrap_or(i32::MAX as i64)
    .min(i32::MAX as i64) as i32;
    let status = if gap <= 2_500 {
        DisagreementStatus::Aligned
    } else if gap <= 5_000 {
        DisagreementStatus::Tension
    } else {
        DisagreementStatus::Disputed
    };
    DisagreementResult {
        status,
        anchor_gap_bps: Some(gap),
        reason_codes: vec!["external_range_diagnostic_only".into()],
    }
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct HistoricalMembership {
    pub symbol: String,
    pub effective_from: String,
    pub effective_to: Option<String>,
    pub knowledge_at: String,
    pub source_location: String,
}

#[derive(Clone, Debug, Default, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct HistoricalValidationCoverage {
    pub membership: Vec<HistoricalMembership>,
    pub delistings: Vec<EvidenceObservation>,
    pub corporate_actions: Vec<EvidenceObservation>,
    pub classifications: Vec<EvidenceObservation>,
    pub component_definitions: Vec<EvidenceObservation>,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DriverForecast {
    pub symbol: String,
    pub driver: String,
    pub decision_at: String,
    pub forecast_millis: i64,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DriverActual {
    pub symbol: String,
    pub driver: String,
    pub economic_period_end: String,
    pub knowledge_at: String,
    pub actual_millis: i64,
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum ValidationStatus {
    Measured,
    Unavailable,
}

#[derive(Clone, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DriverValidationResult {
    pub status: ValidationStatus,
    pub sample_count: u32,
    pub mean_absolute_error_bps: Option<i32>,
    pub used_historical_membership: bool,
    pub market_outcome_diagnostic_bps: Option<i32>,
    pub market_outcome_diagnostic_used_for_primary: bool,
    pub reason_codes: Vec<String>,
}

pub fn validate_driver_forecast(
    coverage: &HistoricalValidationCoverage,
    forecast: &DriverForecast,
    actual: &DriverActual,
) -> DriverValidationResult {
    let decision_date = forecast
        .decision_at
        .get(..10)
        .unwrap_or(forecast.decision_at.as_str());
    let has_membership = coverage.membership.iter().any(|membership| {
        membership.symbol == forecast.symbol
            && membership.effective_from.as_str() <= decision_date
            && membership
                .effective_to
                .as_deref()
                .map(|end| decision_date < end)
                .unwrap_or(true)
            && membership.knowledge_at <= forecast.decision_at
            && !membership.source_location.trim().is_empty()
    });
    if !has_membership
        || forecast.symbol != actual.symbol
        || forecast.driver != actual.driver
        || actual.knowledge_at <= forecast.decision_at
    {
        return DriverValidationResult {
            status: ValidationStatus::Unavailable,
            sample_count: 0,
            mean_absolute_error_bps: None,
            used_historical_membership: has_membership,
            market_outcome_diagnostic_bps: None,
            market_outcome_diagnostic_used_for_primary: false,
            reason_codes: vec!["historical_validation_coverage_unavailable".into()],
        };
    }
    let denominator = actual.actual_millis.unsigned_abs().max(1) as i128;
    let error = round_div_i128(
        (forecast.forecast_millis as i128 - actual.actual_millis as i128).abs() * 10_000,
        denominator,
    )
    .unwrap_or(i32::MAX as i64)
    .min(i32::MAX as i64) as i32;
    DriverValidationResult {
        status: ValidationStatus::Measured,
        sample_count: 1,
        mean_absolute_error_bps: Some(error),
        used_historical_membership: true,
        market_outcome_diagnostic_bps: None,
        market_outcome_diagnostic_used_for_primary: false,
        reason_codes: vec!["primary=reported_driver_accuracy".into()],
    }
}

fn require_supported_regime(regime: SourceRegime) -> Result<(), ValuationRefusal> {
    if regime.is_supported() {
        Ok(())
    } else {
        Err(ValuationRefusal::new(
            ValuationRefusalReasonCode::SourceRegimeUnsupported,
            format!("source regime {:?} has no native normalizer", regime),
        ))
    }
}

fn require_period_evidence(periods: usize) -> Result<(), ValuationRefusal> {
    if periods == 0 {
        return Err(ValuationRefusal::new(
            ValuationRefusalReasonCode::MissingDriverEvidence,
            "at least one dated evidence period is required",
        ));
    }
    Ok(())
}

fn require_evidence(name: &str, refs: &[String]) -> Result<(), ValuationRefusal> {
    if refs.iter().any(|reference| !reference.trim().is_empty()) {
        Ok(())
    } else {
        Err(ValuationRefusal::new(
            ValuationRefusalReasonCode::MissingDriverEvidence,
            format!("{name} has no evidence reference"),
        ))
    }
}

fn quality_for_component(periods: usize, bear: i64, base: i64, bull: i64) -> ComponentQuality {
    let evidence_quality = EvidenceQuality::from_period_count(periods);
    let uncertainty = if base == 0 {
        10_000
    } else {
        ((bull - bear).unsigned_abs().saturating_mul(10_000) / base.unsigned_abs().max(1))
            .min(i64::from(i32::MAX) as u64) as i32
    };
    ComponentQuality {
        evidence_quality,
        confidence: if evidence_quality == EvidenceQuality::Solid {
            ConfidenceBand::Solid
        } else {
            ConfidenceBand::Provisional
        },
        uncertainty_bps: uncertainty,
        sensitivity_bps: uncertainty,
        solver_stability_bps: 0,
    }
}

fn discount_factor_ppm(rate_bps: i32, years: u8) -> Result<i64, ValuationRefusal> {
    if rate_bps <= 0 {
        return Err(ValuationRefusal::new(
            ValuationRefusalReasonCode::MissingRequiredDriver,
            "discount rate must be positive",
        ));
    }
    let mut factor = 1_000_000i128;
    for _ in 0..years {
        factor = round_div_i128(
            factor.checked_mul(10_000).ok_or_else(|| {
                ValuationRefusal::new(
                    ValuationRefusalReasonCode::MissingRequiredDriver,
                    "discount factor overflow",
                )
            })?,
            10_000i128 + rate_bps as i128,
        )
        .ok_or_else(|| {
            ValuationRefusal::new(
                ValuationRefusalReasonCode::MissingRequiredDriver,
                "discount factor invalid",
            )
        })? as i128;
    }
    i64::try_from(factor).map_err(|_| {
        ValuationRefusal::new(
            ValuationRefusalReasonCode::MissingRequiredDriver,
            "discount factor exceeds fixed-point range",
        )
    })
}

fn round_mul_div(a: i128, b: i128, denominator: i128) -> Result<i64, ValuationRefusal> {
    let product = a.checked_mul(b).ok_or_else(|| {
        ValuationRefusal::new(
            ValuationRefusalReasonCode::MissingRequiredDriver,
            "fixed-point multiplication overflow",
        )
    })?;
    round_div_i128(product, denominator).ok_or_else(|| {
        ValuationRefusal::new(
            ValuationRefusalReasonCode::MissingRequiredDriver,
            "fixed-point division invalid",
        )
    })
}

fn round_div_i128(numerator: i128, denominator: i128) -> Option<i64> {
    if denominator <= 0 {
        return None;
    }
    let negative = numerator < 0;
    let absolute = if negative {
        numerator.checked_neg()?
    } else {
        numerator
    };
    let rounded = absolute
        .checked_add(denominator / 2)?
        .checked_div(denominator)?;
    let signed = if negative {
        rounded.checked_neg()?
    } else {
        rounded
    };
    i64::try_from(signed).ok()
}

fn fnv1a64(bytes: &[u8]) -> u64 {
    let mut hash = 0xcbf29ce484222325u64;
    for byte in bytes {
        hash ^= u64::from(*byte);
        hash = hash.wrapping_mul(0x100000001b3);
    }
    hash
}

/// Value identity for equal-rank conflict detection (Kotlin `valueKey()` parity).
fn value_payload(
    row: &EvidenceObservation,
) -> (Option<i64>, Option<i32>, Option<i64>, Option<&str>) {
    (
        row.value_cents,
        row.value_bps,
        row.value_millis,
        row.text_value.as_deref(),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn evidence(id: &str, fact: &str, knowledge: &str, value: i64) -> EvidenceObservation {
        EvidenceObservation {
            id: id.into(),
            fact_key: fact.into(),
            economic_period_start: "2024-01-01".into(),
            economic_period_end: "2024-12-31".into(),
            knowledge_at: knowledge.into(),
            publication_at: knowledge.into(),
            revision_id: id.into(),
            supersedes: None,
            source_vintage: "fixture".into(),
            retrieved_at: knowledge.into(),
            source_regime: SourceRegime::DomesticUsGaap,
            unit: EvidenceUnit::MoneyCents,
            value_cents: Some(value),
            value_bps: None,
            value_millis: None,
            text_value: None,
            currency: Some("USD".into()),
            definition: "fixture fact".into(),
            source_location: "fixture:source".into(),
            extraction_method: ExtractionMethod::StructuredXbrl,
            quality: EvidenceQuality::Solid,
            retrieval_state: RetrievalState::Retrieved,
        }
    }

    fn ref_component(id: &str, ev: i64) -> ComponentValuation {
        ComponentValuation {
            component_id: id.into(),
            family: ComponentFamily::OperatingNonFinancial,
            model: ComponentModel::FcffWacc,
            status: ComponentStatus::Publishable,
            enterprise_value_cents: ev,
            scenarios: ScenarioValues {
                bear_cents: ev,
                base_cents: ev,
                bull_cents: ev,
            },
            discount_rate_bps: 800,
            discount_rate_kind: DiscountRateKind::Wacc,
            source_regime: SourceRegime::DomesticUsGaap,
            evidence_refs: vec!["fixture".into()],
            quality: ComponentQuality {
                evidence_quality: EvidenceQuality::Solid,
                confidence: ConfidenceBand::Solid,
                uncertainty_bps: 0,
                sensitivity_bps: 0,
                solver_stability_bps: 0,
            },
            reason_codes: vec![],
        }
    }

    fn complete_sotp() -> SotpInput {
        SotpInput {
            issuer: "TEST".into(),
            components: vec![
                SotpComponent {
                    component_id: "a".into(),
                    material: true,
                    valuation: Some(ref_component("a", 100_000)),
                    refusal: None,
                },
                SotpComponent {
                    component_id: "b".into(),
                    material: true,
                    valuation: Some(ref_component("b", 50_000)),
                    refusal: None,
                },
            ],
            corporate_overhead: Some(CorporateOverhead {
                enterprise_value_cents: -10_000,
                material: true,
                evidence_refs: vec!["overhead".into()],
            }),
            bridge: CapitalBridge {
                net_debt: Some(BridgeEvidence {
                    amount_cents: 30_000,
                    evidence_refs: vec!["debt".into()],
                }),
                non_controlling_interest: Some(BridgeEvidence {
                    amount_cents: 2_000,
                    evidence_refs: vec!["nci".into()],
                }),
                preferred_claims: Some(BridgeEvidence {
                    amount_cents: 1_000,
                    evidence_refs: vec!["preferred".into()],
                }),
                other_senior_claims: Some(BridgeEvidence {
                    amount_cents: 500,
                    evidence_refs: vec!["senior".into()],
                }),
                separately_valued_investments: vec![BridgeEvidence {
                    amount_cents: 5_000,
                    evidence_refs: vec!["investment".into()],
                }],
            },
            shares: Some(BridgeEvidence {
                amount_cents: 100,
                evidence_refs: vec!["shares".into()],
            }),
            source_fingerprint: "fixture".into(),
        }
    }

    #[test]
    fn point_in_time_replay_excludes_later_revision() {
        let rows = vec![
            evidence("original", "revenue", "2025-02-01T00:00:00Z", 100),
            evidence("amendment", "revenue", "2025-03-01T00:00:00Z", 120),
        ];
        let replay = replay_point_in_time(&rows, "2025-02-28T00:00:00Z").expect("replay");
        assert_eq!(replay.selected[0].id, "original");
        assert_eq!(
            replay.rejected[0].code,
            EvidenceRejectionCode::NotKnownAtDecision
        );
    }

    /// Parity with Kotlin `valueKey()`: same knowledge+publication rank and a
    /// different money payload must refuse even when other value slots are None.
    /// (Rust previously required every optional slot to differ via `&&`, so
    /// `None != None` short-circuited and silently accepted conflicts.)
    #[test]
    fn equal_rank_conflicting_money_observations_refuse() {
        let mut a = evidence("a", "revenue", "2025-02-10T12:00:00Z", 100_000);
        let mut b = evidence("b", "revenue", "2025-02-10T12:00:00Z", 120_000);
        a.revision_id = "r1".into();
        b.revision_id = "r2".into();
        let err = replay_point_in_time(&[a, b], "2025-03-31T23:59:59Z")
            .expect_err("equal-rank conflict must refuse");
        assert_eq!(err.code, ValuationRefusalReasonCode::DuplicateEvidence);
        assert!(
            err.detail
                .contains("conflicting evidence at the same publication rank"),
            "detail={}",
            err.detail
        );
    }

    #[test]
    fn equal_rank_identical_money_selects_last_revision() {
        let mut a = evidence("a", "revenue", "2025-02-10T12:00:00Z", 100_000);
        let mut b = evidence("b", "revenue", "2025-02-10T12:00:00Z", 100_000);
        a.revision_id = "r1".into();
        b.revision_id = "r2".into();
        let replay =
            replay_point_in_time(&[a, b], "2025-03-31T23:59:59Z").expect("identical value");
        assert_eq!(replay.selected.len(), 1);
        assert_eq!(replay.selected[0].id, "b");
    }

    #[test]
    fn classifier_is_closed_world_and_family_specific() {
        assert_eq!(
            route_component(&ClassificationInput {
                sector: Some("Healthcare".into()),
                industry: Some("Healthcare Plans".into()),
                asset_class: AssetClass::Equity
            }),
            ComponentFamily::FinancialServices
        );
        assert_eq!(
            route_component(&ClassificationInput {
                sector: Some("Basic Materials".into()),
                industry: Some("Gold".into()),
                asset_class: AssetClass::Equity
            }),
            ComponentFamily::ResourceProducer
        );
        assert_eq!(
            route_component(&ClassificationInput {
                sector: Some("Unknown".into()),
                industry: Some("Moon Cheese".into()),
                asset_class: AssetClass::Equity
            }),
            ComponentFamily::Unclassified
        );
        assert_eq!(
            route_component(&ClassificationInput {
                sector: None,
                industry: None,
                asset_class: AssetClass::Etf
            }),
            ComponentFamily::NotEligible
        );
        assert_eq!(
            require_eligible_component(&ClassificationInput {
                sector: Some("Unknown".into()),
                industry: Some("Moon Cheese".into()),
                asset_class: AssetClass::Equity
            })
            .unwrap_err()
            .code,
            ValuationRefusalReasonCode::UnclassifiedSector
        );
    }

    #[test]
    fn terminal_growth_requires_roic_and_reinvestment_link() {
        let input = OperatingComponentInput {
            component_id: "op".into(),
            source_regime: SourceRegime::DomesticUsGaap,
            fcff_by_year: vec![AnnualFcff {
                year: 2024,
                fcff_cents: 10_000,
            }],
            wacc_bps: 800,
            near_growth_bps: 500,
            stable_growth_bps: 200,
            terminal_nopat_cents: 15_000,
            terminal_roic_bps: 1_000,
            terminal_reinvestment_bps: 2_000,
            explicit_years: 5,
            sbc_treatment: SbcTreatment::ExpenseIncluded,
            evidence_refs: vec!["fcff".into()],
            evidence_periods: 3,
            scenario_spread_bps: 100,
        };
        assert!(value_operating_component(&input).is_ok());
        let invalid = OperatingComponentInput {
            stable_growth_bps: 300,
            ..input
        };
        assert_eq!(
            value_operating_component(&invalid).unwrap_err().code,
            ValuationRefusalReasonCode::MissingTerminalReinvestmentLink
        );
    }

    #[test]
    fn financial_services_never_uses_fcff() {
        let input = FinancialServicesComponentInput {
            component_id: "ins".into(),
            source_regime: SourceRegime::DomesticUsGaap,
            book_equity_cents: 6_511,
            shares: 100,
            roe_bps: 2_000,
            retention_bps: 7_000,
            cost_of_equity_bps: 900,
            stable_roe_bps: 900,
            explicit_years: 5,
            evidence_refs: vec!["book".into(), "roe".into()],
            evidence_periods: 3,
            scenario_roe_spread_bps: 200,
            scenario_rate_spread_bps: 75,
        };
        let valuation = value_financial_services(&input).expect("residual income");
        assert_eq!(valuation.model, ComponentModel::ResidualIncomeEquity);
        assert_eq!(valuation.discount_rate_kind, DiscountRateKind::CostOfEquity);
        assert!(valuation
            .reason_codes
            .iter()
            .any(|reason| reason.contains("not_ocf_minus_ppe_capex")));
    }

    #[test]
    fn complete_sotp_bridges_once_and_publishes_price() {
        let output = consolidate_sotp(&complete_sotp());
        assert_eq!(output.status, SotpStatus::Published);
        assert_eq!(output.covered_enterprise_value_cents, Some(145_000));
        assert_eq!(output.equity_value_cents, Some(111_500));
        assert_eq!(output.intrinsic_price_cents, Some(1_115));
        assert!(output.valuation_score_eligible);
    }

    #[test]
    fn missing_overhead_evidence_cannot_publish_even_when_immaterial() {
        let mut input = complete_sotp();
        input.corporate_overhead = Some(CorporateOverhead {
            enterprise_value_cents: 0,
            material: false,
            evidence_refs: Vec::new(),
        });
        let output = consolidate_sotp(&input);
        assert_eq!(output.status, SotpStatus::CoveredEvOnly);
        assert_eq!(output.equity_value_cents, None);
        assert!(!output.valuation_score_eligible);
        assert!(output
            .reason_codes
            .contains(&"unallocated_overhead_ambiguity".into()));
    }

    #[test]
    fn unresolved_material_component_only_allows_covered_ev() {
        let mut input = complete_sotp();
        input.components.push(SotpComponent {
            component_id: "missing".into(),
            material: true,
            valuation: None,
            refusal: Some(ValuationRefusalWire {
                code: "incomplete_segment_disclosures".into(),
                detail: "missing".into(),
            }),
        });
        let output = consolidate_sotp(&input);
        assert_eq!(output.status, SotpStatus::CoveredEvOnly);
        assert_eq!(output.equity_value_cents, None);
        assert!(!output.valuation_score_eligible);
    }

    #[test]
    fn net_debt_exceeding_ev_is_zero_equity_not_refusal() {
        let mut input = complete_sotp();
        input.components = vec![SotpComponent {
            component_id: "distressed".into(),
            material: true,
            valuation: Some(ref_component("distressed", 1_000)),
            refusal: None,
        }];
        input.corporate_overhead = Some(CorporateOverhead {
            enterprise_value_cents: 0,
            material: true,
            evidence_refs: vec!["overhead".into()],
        });
        input.bridge.net_debt = Some(BridgeEvidence {
            amount_cents: 2_000,
            evidence_refs: vec!["debt".into()],
        });
        input.bridge.separately_valued_investments.clear();
        input.bridge.non_controlling_interest = Some(BridgeEvidence {
            amount_cents: 0,
            evidence_refs: vec!["nci".into()],
        });
        input.bridge.preferred_claims = Some(BridgeEvidence {
            amount_cents: 0,
            evidence_refs: vec!["preferred".into()],
        });
        input.bridge.other_senior_claims = Some(BridgeEvidence {
            amount_cents: 0,
            evidence_refs: vec!["senior".into()],
        });
        let output = consolidate_sotp(&input);
        assert_eq!(output.equity_value_cents, Some(0));
        assert_eq!(output.intrinsic_price_cents, Some(0));
    }

    #[test]
    fn resource_and_contract_families_require_physical_or_contract_evidence() {
        let resource = ResourceProducerInput {
            component_id: "resource".into(),
            source_regime: SourceRegime::DomesticUsGaap,
            commodities: vec![CommodityDriver {
                commodity: "oil".into(),
                volume_millis: vec![AnnualQuantity {
                    year: 2024,
                    value_millis: 100_000,
                }],
                volumetric_base: VolumetricBase::Gross,
                base_reconciliation: None,
                price_cents_per_unit: 200,
                hedge_cents_per_unit: Some(5),
                hedge_is_unrealized: false,
                cash_cost_cents_per_unit: 50,
                sustaining_capex_cents_per_unit: 20,
                reserves_millis: 300_000,
                decline_bps: 500,
                development_capex_cents_per_year: 100,
                finite_horizon_years: Some(3),
                evidence_refs: vec!["reserve".into(), "volume".into(), "price".into()],
            }],
            discount_rate_bps: 800,
            requires_rbl: false,
            rbl: None,
            evidence_periods: 3,
        };
        let valuation = value_resource_producer(&resource).expect("finite resource valuation");
        assert_eq!(valuation.model, ComponentModel::ResourceFinite);

        let mismatch = ResourceProducerInput {
            commodities: vec![
                resource.commodities[0].clone(),
                CommodityDriver {
                    commodity: "gas".into(),
                    volumetric_base: VolumetricBase::NetRevenueInterest,
                    ..resource.commodities[0].clone()
                },
            ],
            ..resource
        };
        assert_eq!(
            value_resource_producer(&mismatch).unwrap_err().code,
            ValuationRefusalReasonCode::VolumetricBaseMismatch
        );

        let contract = ContractedInfrastructureInput {
            component_id: "pipeline".into(),
            source_regime: SourceRegime::DomesticUsGaap,
            exposures: vec![ContractExposure {
                name: "take-or-pay".into(),
                kind: ContractExposureKind::TakeOrPay,
                base_revenue_cents_per_year: 10_000,
                annual_escalation_bps: 200,
                remaining_years: Some(3),
                volume_millis_per_year: None,
                fee_cents_per_unit: None,
                proceeds_cents_per_year: None,
                percent_of_proceeds_bps: None,
                evidence_refs: vec!["contract".into()],
                material: true,
            }],
            operating_cost_cents_per_year: 1_000,
            maintenance_capex_cents_per_year: 500,
            discount_rate_bps: 800,
            evidence_periods: 3,
        };
        assert_eq!(
            value_contracted_infrastructure(&contract)
                .expect("contracted infrastructure valuation")
                .model,
            ComponentModel::ContractedInfrastructure
        );
    }

    #[test]
    fn validation_never_uses_current_membership_as_historical_proxy() {
        let forecast = DriverForecast {
            symbol: "AAA".into(),
            driver: "production".into(),
            decision_at: "2023-01-01T00:00:00Z".into(),
            forecast_millis: 1_100,
        };
        let actual = DriverActual {
            symbol: "AAA".into(),
            driver: "production".into(),
            economic_period_end: "2023-12-31".into(),
            knowledge_at: "2024-02-01T00:00:00Z".into(),
            actual_millis: 1_000,
        };
        let output =
            validate_driver_forecast(&HistoricalValidationCoverage::default(), &forecast, &actual);
        assert_eq!(output.status, ValidationStatus::Unavailable);
        assert_eq!(output.mean_absolute_error_bps, None);
    }
}
