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
    /// Cash paid to develop or buy software. A disjoint line on the same
    /// statement as tangible development, not an alternative tag for it: an
    /// issuer that invests through software files both, and reading only the
    /// tangible one understates reinvestment by whatever software costs.
    DevelopmentSoftware,
    /// Cash paid for the oil and gas well program. Disjoint from office/plant
    /// PPE on the same statement; not acreage acquisition.
    DevelopmentWells,
    /// Cash paid to buy intangibles (spectrum, licenses, purchased software).
    /// Disjoint from PPE on the same statement; not a business acquisition.
    DevelopmentIntangibles,
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
    /// One selected software-development fact per fiscal close, chosen by the
    /// same equivalence rules within its own component class.
    pub software_development_by_end: BTreeMap<String, SecFact>,
    /// Total recurring development per fiscal close: the tangible component plus
    /// the software component. This — not either component alone — is the CapEx
    /// the FCFF bridge consumes.
    pub development_total_by_end: BTreeMap<String, i64>,
    pub ledger: Vec<EvidenceLedgerEntry>,
}

pub const POLICY_FINGERPRINT: &str = policy::POLICY_FINGERPRINT;

/// Category mapping is intentionally exact and small.  A new issuer extension
/// requires a reviewed economic mapping; QName spelling is not economic proof.
pub fn investment_category(qname: &str) -> InvestmentCategory {
    if policy::DEVELOPMENT.contains(&qname) {
        InvestmentCategory::Development
    } else if policy::DEVELOPMENT_SOFTWARE.contains(&qname) {
        InvestmentCategory::DevelopmentSoftware
    } else if policy::DEVELOPMENT_WELLS.contains(&qname) {
        InvestmentCategory::DevelopmentWells
    } else if policy::DEVELOPMENT_INTANGIBLES.contains(&qname) {
        InvestmentCategory::DevelopmentIntangibles
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

/// Resolve one fact per fiscal close inside a single component class. Equivalent
/// facts are selected between, never summed: later filing/amendment wins, and
/// accession is the deterministic final tie-breaker. Marks the losers in the
/// ledger, and drops a close entirely when two facts of identical precedence
/// disagree on value.
fn select_one_equivalent_per_end(
    approved: Vec<(usize, SecFact)>,
    ledger: &mut [EvidenceLedgerEntry],
) -> BTreeMap<String, SecFact> {
    let mut by_end = BTreeMap::<String, Vec<(usize, SecFact)>>::new();
    for approved_fact in approved {
        by_end
            .entry(approved_fact.1.end.clone())
            .or_default()
            .push(approved_fact);
    }
    let mut selected_by_end = BTreeMap::new();
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
        selected_by_end.insert(end, ledger[*selected_index].fact.clone());
    }
    selected_by_end
}

pub fn normalize_investments(
    facts: impl IntoIterator<Item = SecFact>,
) -> NormalizedInvestmentEvidence {
    let mut ledger = Vec::new();
    let mut approved_tangible = Vec::new();
    let mut approved_software = Vec::new();
    let mut approved_wells = Vec::new();
    let mut approved_intangibles = Vec::new();
    for fact in facts {
        let category = investment_category(&fact.qname);
        let state = match category {
            InvestmentCategory::PropertyAcquisition | InvestmentCategory::BusinessAcquisition => {
                EvidenceState::RejectedAcquisition
            }
            InvestmentCategory::UnclassifiedInvestment => EvidenceState::NoApprovedConcept,
            InvestmentCategory::Development
            | InvestmentCategory::DevelopmentSoftware
            | InvestmentCategory::DevelopmentWells
            | InvestmentCategory::DevelopmentIntangibles
                if fact.unit != "USD" =>
            {
                EvidenceState::InvalidUnit
            }
            InvestmentCategory::Development
            | InvestmentCategory::DevelopmentSoftware
            | InvestmentCategory::DevelopmentWells
            | InvestmentCategory::DevelopmentIntangibles
                if !is_annual_duration(&fact) =>
            {
                EvidenceState::MisalignedPeriod
            }
            InvestmentCategory::Development
            | InvestmentCategory::DevelopmentSoftware
            | InvestmentCategory::DevelopmentWells
            | InvestmentCategory::DevelopmentIntangibles => EvidenceState::Selected,
        };
        let index = ledger.len();
        if state == EvidenceState::Selected {
            match category {
                InvestmentCategory::DevelopmentSoftware => {
                    approved_software.push((index, fact.clone()))
                }
                InvestmentCategory::DevelopmentWells => approved_wells.push((index, fact.clone())),
                InvestmentCategory::DevelopmentIntangibles => {
                    approved_intangibles.push((index, fact.clone()))
                }
                _ => approved_tangible.push((index, fact.clone())),
            }
        }
        ledger.push(EvidenceLedgerEntry {
            fact,
            category,
            state,
        });
    }

    // Each component class resolves its own equivalents first; the classes
    // are then summed, because they are different lines on the same statement.
    let recurring_development_by_end =
        select_one_equivalent_per_end(approved_tangible, &mut ledger);
    let software_development_by_end = select_one_equivalent_per_end(approved_software, &mut ledger);
    let wells_development_by_end = select_one_equivalent_per_end(approved_wells, &mut ledger);
    let intangible_development_by_end =
        select_one_equivalent_per_end(approved_intangibles, &mut ledger);

    let mut development_total_by_end = BTreeMap::<String, i64>::new();
    for end in recurring_development_by_end
        .keys()
        .chain(software_development_by_end.keys())
        .chain(wells_development_by_end.keys())
        .chain(intangible_development_by_end.keys())
    {
        if development_total_by_end.contains_key(end) {
            continue;
        }
        let tangible = recurring_development_by_end.get(end);
        let software = software_development_by_end.get(end);
        let wells = wells_development_by_end.get(end);
        let intangibles = intangible_development_by_end.get(end);
        // `PaymentsToAcquireProductiveAssets` is defined by us-gaap as the cash
        // outflow for PP&E, software and other intangibles — those components
        // are already inside it, so adding them would count that spend twice.
        // `PaymentsToAcquirePropertyPlantAndEquipment` is tangible by
        // definition and carries no such overlap.
        let aggregate_tangible = tangible
            .is_some_and(|fact| policy::DEVELOPMENT_AGGREGATE.contains(&fact.qname.as_str()));
        let explore_is_the_well_program = tangible
            .is_some_and(|fact| fact.qname == "PaymentsToExploreAndDevelopOilAndGasProperties");
        let magnitude = tangible.map_or(0, |fact| fact.value_dollars.abs())
            + software
                .filter(|_| !aggregate_tangible)
                .map_or(0, |fact| fact.value_dollars.abs())
            + wells
                .filter(|_| !explore_is_the_well_program)
                .map_or(0, |fact| fact.value_dollars.abs())
            + intangibles
                .filter(|_| !aggregate_tangible)
                .map_or(0, |fact| fact.value_dollars.abs());
        // Issuers file these outflows with either sign. Preserve whichever the
        // dominant component used so downstream sign handling is unchanged.
        let negative = tangible
            .or(software)
            .or(wells)
            .or(intangibles)
            .is_some_and(|fact| fact.value_dollars < 0);
        development_total_by_end.insert(end.clone(), if negative { -magnitude } else { magnitude });
    }

    let recurring_development = recurring_development_by_end.values().next_back().cloned();
    NormalizedInvestmentEvidence {
        recurring_development,
        recurring_development_by_end,
        software_development_by_end,
        development_total_by_end,
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

    /// Comcast FY2025 as filed: $11.750B of PPE and $2.658B of intangible
    /// purchases (spectrum, licenses). The intangible line is not M&A.
    #[test]
    fn intangible_purchases_are_summed_with_plant() {
        let normalized = normalize_investments([
            fact("PaymentsToAcquirePropertyPlantAndEquipment", 11_750_000_000),
            fact("PaymentsToAcquireIntangibleAssets", 2_658_000_000),
        ]);
        assert_eq!(
            normalized.development_total_by_end["2024-12-31"],
            14_408_000_000
        );
    }

    /// FIS FY2025 as filed: $0.154B of plant and $0.835B of software development.
    /// Reading the plant line alone reports a $10.7B-revenue issuer as
    /// reinvesting 1.4% of sales.
    #[test]
    fn software_development_is_summed_with_plant_not_selected_against_it() {
        let normalized = normalize_investments([
            fact("PaymentsToAcquirePropertyPlantAndEquipment", 154_000_000),
            fact("PaymentsForSoftware", 835_000_000),
        ]);
        assert_eq!(
            normalized.development_total_by_end["2024-12-31"],
            989_000_000
        );
    }

    /// `PaymentsToAcquireProductiveAssets` is defined by us-gaap as the cash
    /// outflow for PP&E, **software** and other intangibles, so the software
    /// component is already inside it. `PaymentsToAcquirePropertyPlantAndEquipment`
    /// is tangible by definition and is not.
    #[test]
    fn software_inside_an_aggregate_capex_line_is_not_added_twice() {
        let normalized = normalize_investments([
            fact("PaymentsToAcquireProductiveAssets", 833_000_000),
            fact("PaymentsForSoftware", 665_000_000),
        ]);
        assert_eq!(
            normalized.development_total_by_end["2024-12-31"],
            833_000_000
        );
    }

    #[test]
    fn software_development_alone_still_reports_capex() {
        let normalized = normalize_investments([fact("PaymentsToDevelopSoftware", 40_000_000)]);
        assert_eq!(
            normalized.development_total_by_end["2024-12-31"],
            40_000_000
        );
    }

    /// Issuers file investing outflows with either sign; the summed total must
    /// keep the convention the issuer used rather than silently flipping it.
    #[test]
    fn summed_development_preserves_the_filed_sign() {
        let normalized = normalize_investments([
            fact("PaymentsToAcquirePropertyPlantAndEquipment", -154_000_000),
            fact("PaymentsForSoftware", -835_000_000),
        ]);
        assert_eq!(
            normalized.development_total_by_end["2024-12-31"],
            -989_000_000
        );
    }

    #[test]
    fn generated_contract_policy_is_the_category_source() {
        assert_eq!(POLICY_FINGERPRINT, "sec-driver-normalization/12");
        assert_eq!(
            investment_category("PaymentsToExploreAndDevelopOilAndGasProperties"),
            InvestmentCategory::Development
        );
        assert_eq!(
            investment_category("PaymentsToAcquireOilAndGasPropertyAndEquipment"),
            InvestmentCategory::DevelopmentWells
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
            // Multi-component issuers additionally pin the summed CapEx, since
            // the per-fact state alone cannot distinguish "both selected" from
            // "both selected and then one of them silently dropped".
            if let Some(total) = case["expectedDevelopmentTotalDollars"].as_i64() {
                let end = case["facts"][0]["end"].as_str().unwrap();
                assert_eq!(
                    normalized.development_total_by_end.get(end),
                    Some(&total),
                    "{}",
                    case["name"].as_str().unwrap()
                );
            }
        }
    }
}
