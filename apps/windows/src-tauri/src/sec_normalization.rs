//! Canonical SEC fact boundary for FCFF drivers.
//!
//! The valuation engine consumes the resulting annual drivers, never XBRL
//! aliases.  Acquisition facts remain in the ledger but are deliberately not
//! candidates for recurring-development CapEx.

use std::cmp::Reverse;
use std::collections::BTreeMap;

use crate::sec_driver_normalization_policy_generated as policy;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum InvestmentCategory {
    Development,
    PropertyAcquisition,
    BusinessAcquisition,
    UnclassifiedInvestment,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EvidenceState {
    Selected,
    RejectedAcquisition,
    NoApprovedConcept,
    InvalidUnit,
    MisalignedPeriod,
    RejectedEquivalent,
    AmbiguousOverlap,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SecFact {
    pub qname: String,
    pub taxonomy: String,
    pub value_dollars: i64,
    pub start: Option<String>,
    pub end: String,
    pub unit: String,
    pub form: String,
    pub accession: Option<String>,
    pub filed: Option<String>,
    pub consolidated: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EvidenceLedgerEntry {
    pub fact: SecFact,
    pub category: InvestmentCategory,
    pub state: EvidenceState,
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct NormalizedInvestmentEvidence {
    pub recurring_development: Option<SecFact>,
    /// One selected recurring-development fact per fiscal close.  The legacy
    /// singular field is retained for concise callers and test ergonomics; the
    /// production SEC adapter consumes this complete, per-period series.
    pub recurring_development_by_end: BTreeMap<String, SecFact>,
    pub ledger: Vec<EvidenceLedgerEntry>,
}

pub const POLICY_FINGERPRINT: &str = policy::POLICY_FINGERPRINT;

/// Category mapping is intentionally exact and small.  A new issuer extension
/// requires a reviewed economic mapping; QName spelling is not economic proof.
pub fn investment_category(qname: &str) -> InvestmentCategory {
    if policy::DEVELOPMENT.contains(&qname) {
        InvestmentCategory::Development
    } else if policy::PROPERTY_ACQUISITION.contains(&qname) {
        InvestmentCategory::PropertyAcquisition
    } else if policy::BUSINESS_ACQUISITION.contains(&qname) {
        InvestmentCategory::BusinessAcquisition
    } else {
        InvestmentCategory::UnclassifiedInvestment
    }
}

fn is_annual_duration(fact: &SecFact) -> bool {
    if !policy::ACCEPTED_FORMS.contains(&fact.form.as_str())
        || fact.unit != policy::REQUIRED_UNIT
        || !fact.consolidated
    {
        return false;
    }
    let Some(start) = &fact.start else {
        return false;
    };
    // ISO dates preserve chronological ordering.  325..=380 accepts 52/53
    // week years and fiscal-calendar changes without admitting quarter facts.
    let Ok(days) = chrono::NaiveDate::parse_from_str(&fact.end, "%Y-%m-%d").and_then(|end| {
        chrono::NaiveDate::parse_from_str(start, "%Y-%m-%d").map(|begin| (end - begin).num_days())
    }) else {
        return false;
    };
    (policy::MINIMUM_DURATION_DAYS..=policy::MAXIMUM_DURATION_DAYS).contains(&days)
}

pub fn normalize_investments(
    facts: impl IntoIterator<Item = SecFact>,
) -> NormalizedInvestmentEvidence {
    let mut ledger = Vec::new();
    let mut approved = Vec::new();
    for fact in facts {
        let category = investment_category(&fact.qname);
        let state = match category {
            InvestmentCategory::PropertyAcquisition | InvestmentCategory::BusinessAcquisition => {
                EvidenceState::RejectedAcquisition
            }
            InvestmentCategory::UnclassifiedInvestment => EvidenceState::NoApprovedConcept,
            InvestmentCategory::Development if fact.unit != "USD" => EvidenceState::InvalidUnit,
            InvestmentCategory::Development if !is_annual_duration(&fact) => {
                EvidenceState::MisalignedPeriod
            }
            InvestmentCategory::Development => EvidenceState::Selected,
        };
        let index = ledger.len();
        if state == EvidenceState::Selected {
            approved.push((index, fact.clone()));
        }
        ledger.push(EvidenceLedgerEntry {
            fact,
            category,
            state,
        });
    }

    // Equivalent facts are selected per fiscal close, never summed. Later
    // filing/amendment wins; accession is the deterministic final tie-breaker.
    let mut by_end = BTreeMap::<String, Vec<(usize, SecFact)>>::new();
    for approved_fact in approved {
        by_end
            .entry(approved_fact.1.end.clone())
            .or_default()
            .push(approved_fact);
    }
    let mut recurring_development_by_end = BTreeMap::new();
    for (end, mut equivalents) in by_end {
        equivalents.sort_by_key(|(_, fact)| {
            (
                Reverse(fact.filed.clone().unwrap_or_default()),
                Reverse(fact.accession.clone().unwrap_or_default()),
                fact.qname.clone(),
            )
        });
        let (selected_index, selected) = &equivalents[0];
        let same_precedence_with_different_value =
            equivalents.iter().skip(1).any(|(_, candidate)| {
                candidate.filed == selected.filed
                    && candidate.accession == selected.accession
                    && candidate.value_dollars != selected.value_dollars
            });
        if same_precedence_with_different_value {
            for (index, _) in &equivalents {
                ledger[*index].state = EvidenceState::AmbiguousOverlap;
            }
            continue;
        }
        for (index, _) in equivalents.iter().skip(1) {
            ledger[*index].state = EvidenceState::RejectedEquivalent;
        }
        recurring_development_by_end.insert(end, ledger[*selected_index].fact.clone());
    }
    let recurring_development = recurring_development_by_end.values().next_back().cloned();
    NormalizedInvestmentEvidence {
        recurring_development,
        recurring_development_by_end,
        ledger,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fact(qname: &str, value_dollars: i64) -> SecFact {
        SecFact {
            qname: qname.into(),
            taxonomy: "us-gaap".into(),
            value_dollars,
            start: Some("2024-01-01".into()),
            end: "2024-12-31".into(),
            unit: "USD".into(),
            form: "10-K".into(),
            accession: Some("0001".into()),
            filed: Some("2025-02-20".into()),
            consolidated: true,
        }
    }

    #[test]
    fn crgy_development_is_selected_and_property_acquisition_is_rejected() {
        let normalized = normalize_investments([
            fact(
                "PaymentsToExploreAndDevelopOilAndGasProperties",
                685_700_000,
            ),
            fact("PaymentsToAcquireOilAndGasProperty", 558_600_000),
        ]);
        assert_eq!(
            normalized.recurring_development.unwrap().value_dollars,
            685_700_000
        );
        assert_eq!(
            normalized.ledger[1].category,
            InvestmentCategory::PropertyAcquisition
        );
        assert_eq!(
            normalized.ledger[1].state,
            EvidenceState::RejectedAcquisition
        );
    }

    #[test]
    fn generated_contract_policy_is_the_category_source() {
        assert_eq!(POLICY_FINGERPRINT, "sec-driver-normalization/5");
        assert_eq!(
            investment_category("PaymentsToExploreAndDevelopOilAndGasProperties"),
            InvestmentCategory::Development
        );
        assert_eq!(
            investment_category("PaymentsToAcquireOilAndGasProperty"),
            InvestmentCategory::PropertyAcquisition
        );
        assert_eq!(
            investment_category("PaymentsToAcquireBusinessesAndInterestInAffiliates"),
            InvestmentCategory::BusinessAcquisition
        );
        assert_eq!(
            investment_category("PaymentsToAcquireMiningAssets"),
            InvestmentCategory::PropertyAcquisition
        );
    }

    #[test]
    fn invalid_units_do_not_become_capex() {
        let mut invalid = fact("PaymentsToAcquirePropertyPlantAndEquipment", 12);
        invalid.unit = "shares".into();
        let normalized = normalize_investments([invalid]);
        assert!(normalized.recurring_development.is_none());
        assert_eq!(normalized.ledger[0].state, EvidenceState::InvalidUnit);
    }

    #[test]
    fn instant_or_quarter_like_investment_fact_is_not_recurring_capex() {
        let mut instant = fact("PaymentsToAcquirePropertyPlantAndEquipment", 12);
        instant.start = None;
        let normalized = normalize_investments([instant]);
        assert!(normalized.recurring_development.is_none());
        assert_eq!(normalized.ledger[0].state, EvidenceState::MisalignedPeriod);
    }

    #[test]
    fn amended_filing_wins_equivalent_overlap_without_summing() {
        let original = fact("PaymentsToAcquirePropertyPlantAndEquipment", 100);
        let mut amended = fact("PaymentsToAcquireProductiveAssets", 120);
        amended.form = "10-K/A".into();
        amended.accession = Some("0002".into());
        amended.filed = Some("2025-03-01".into());
        let normalized = normalize_investments([original, amended]);
        assert_eq!(normalized.recurring_development.unwrap().value_dollars, 120);
        assert!(normalized
            .ledger
            .iter()
            .any(|entry| entry.state == EvidenceState::RejectedEquivalent));
    }

    #[test]
    fn frozen_real_sec_fixture_corpus_executes_at_the_normalization_boundary() {
        let path = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../../../shared/contracts/sec-driver-normalization-fixtures.json");
        let fixture: serde_json::Value =
            serde_json::from_str(&std::fs::read_to_string(path).expect("read SEC fixtures"))
                .expect("parse SEC fixtures");
        assert_eq!(fixture["policyFingerprint"], POLICY_FINGERPRINT);
        let cases = fixture["fixtures"].as_array().expect("fixture cases");
        assert!(cases.len() >= 5, "must span at least five SEC issuers");
        for case in cases {
            let facts = case["facts"]
                .as_array()
                .expect("fixture facts")
                .iter()
                .map(|fact| SecFact {
                    qname: fact["qname"].as_str().unwrap().into(),
                    taxonomy: "us-gaap".into(),
                    value_dollars: fact["valueDollars"].as_i64().unwrap(),
                    start: fact["start"].as_str().map(str::to_owned),
                    end: fact["end"].as_str().unwrap().into(),
                    unit: fact["unit"].as_str().unwrap().into(),
                    form: fact["form"].as_str().unwrap().into(),
                    accession: fact["accession"].as_str().map(str::to_owned),
                    filed: fact["filed"].as_str().map(str::to_owned),
                    consolidated: true,
                })
                .collect::<Vec<_>>();
            let normalized = normalize_investments(facts);
            for expected in case["facts"].as_array().unwrap() {
                let qname = expected["qname"].as_str().unwrap();
                let state = normalized
                    .ledger
                    .iter()
                    .find(|entry| entry.fact.qname == qname)
                    .map(|entry| entry.state)
                    .expect("fixture fact in ledger");
                match expected["expected"].as_str().unwrap() {
                    "SelectedDevelopment" => assert_eq!(state, EvidenceState::Selected),
                    "RejectedAcquisition" => assert_eq!(state, EvidenceState::RejectedAcquisition),
                    other => panic!("unknown expected evidence state: {other}"),
                }
            }
        }
    }
}
