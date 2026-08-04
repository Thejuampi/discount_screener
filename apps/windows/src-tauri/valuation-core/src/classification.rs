//! Closed-world business classification.
//!
//! # Failing closed
//!
//! Every issuer resolves to exactly one [`BusinessClass`], and text the taxonomy
//! does not recognize resolves to [`BusinessClass::Unclassified`] — never to the
//! operating model (FR-30). This ordering matters more than it looks: an insurer
//! silently routed through a cash-flow model built on `operating cash flow minus
//! capital expenditure` produces a confident number with no economic content,
//! because an insurer's cash flow is float, not owner earnings. That is the
//! ACGL/CI defect, and the only structural defence against it is that no branch
//! anywhere reaches `OperatingNonFinancial` by default.
//!
//! # Why an industry taxonomy is not a ticker special-case
//!
//! Guardrail §5.2 rejects industry-string branching, and rightly: the retired
//! `through_cycle_business()` matched three industry strings to pick a *growth
//! parameter*, which is a ticker special-case wearing a taxonomy as a disguise —
//! the parameter it selected should have come from the issuer's own evidence.
//!
//! Routing to a *model class* is a different act. A bank has no free cash flow to
//! the firm, in the sense the operating model means it; the residual-income model
//! is not a differently-tuned version of the same model but a different set of
//! accounting identities. Choosing which identities apply is what a taxonomy is
//! for. The line is: text may decide **which model**, and never **what a
//! parameter of that model is**.
//!
//! # Matching on words, not substrings
//!
//! The shipping engine matches raw substrings, which is why its key list has to
//! carry `" etf"` with a leading space and `"crypto "` with a trailing one — hand
//! patches for collisions that were noticed. The ones that were not noticed are
//! still there, and `"bank"` matching inside `"bankruptcy"` is the shape of them.
//!
//! Here both the text and the keys are normalized to space-delimited words and
//! matched with their boundaries, so a key matches a word or a phrase of words
//! and never a fragment of one. `ampersands_become_the_word_and` and
//! `a_keyword_inside_a_longer_word_does_not_classify` are that property.

/// What kind of economics the issuer runs, and therefore which model applies.
///
/// A closed world: there is no `Other`, and the fall-through is `Unclassified`,
/// which refuses.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BusinessClass {
    /// Owner earnings from operations. The cash-flow path model applies.
    OperatingNonFinancial,
    /// Book-equity and float economics. Residual income on book applies, and the
    /// operating cash-flow model never runs (FR-31).
    FinancialServices,
    /// Not a going-concern operating claim at all — funds, trusts, wrappers.
    NotEligible,
    /// The taxonomy does not recognize this issuer. Refuses (FR-30).
    Unclassified,
}

impl BusinessClass {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::OperatingNonFinancial => "operating_non_financial",
            Self::FinancialServices => "financial_services",
            Self::NotEligible => "not_eligible",
            Self::Unclassified => "unclassified",
        }
    }

    /// Whether a valuation model exists for this class at all. The two refusing
    /// classes refuse for different reasons: `NotEligible` is a claim about the
    /// instrument, `Unclassified` a claim about our own coverage.
    pub fn routes_to_a_model(self) -> bool {
        matches!(self, Self::OperatingNonFinancial | Self::FinancialServices)
    }
}

/// What the provider says the security itself is.
///
/// Kept as a type rather than a bool because it is evidence about the
/// instrument, independent of any sector text, and it outranks the text: a fund
/// holding operating companies is still a fund.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Instrument {
    CommonEquity,
    /// A fund, trust, wrapper, or anything else that is not a direct claim on an
    /// operating business.
    NotCommonEquity,
}

/// Resolve the business class from sector and industry text.
///
/// Empty text is not an error and not a default — it simply matches nothing and
/// falls through to `Unclassified`, which refuses.
pub fn classify(sector: &str, industry: &str, instrument: Instrument) -> BusinessClass {
    if instrument == Instrument::NotCommonEquity {
        return BusinessClass::NotEligible;
    }

    let sector = normalize(sector);
    let industry = normalize(industry);
    let combined = format!("{sector}{industry}");

    // Ordered most specific first. Ineligible wrappers before financial float
    // before operating, because a REIT prospectus and a bank prospectus both
    // mention finance, and an ETF holding banks is neither.
    if matches_any(&combined, NOT_ELIGIBLE) {
        return BusinessClass::NotEligible;
    }
    if matches_any(&combined, FINANCIAL_SERVICES) {
        return BusinessClass::FinancialServices;
    }
    if is_operating(&sector, &industry, &combined) {
        return BusinessClass::OperatingNonFinancial;
    }
    BusinessClass::Unclassified
}

fn is_operating(sector: &str, industry: &str, combined: &str) -> bool {
    if matches_any(sector, OPERATING_SECTORS) {
        return true;
    }

    // Healthcare is the one sector that spans both classes, and the split is by
    // industry: a health plan underwrites risk against reserves and is valued on
    // book; a drug manufacturer sells product. Financial keys were already tried
    // above and did not match, so what remains is either an operating health
    // industry or too little information to tell.
    if matches_any(sector, HEALTHCARE_SECTORS) {
        // Sector alone, with no industry, is genuinely ambiguous — and an
        // ambiguous healthcare issuer is exactly the CI case. Refusing is the
        // whole point of failing closed.
        return matches_any(industry, HEALTHCARE_OPERATING);
    }

    matches_any(combined, OPERATING_INDUSTRIES)
}

/// Reduce text to lowercase words delimited by single spaces, wrapped in spaces
/// so that every word and phrase in it has a boundary on both sides.
///
/// `&` becomes the word `and`, so `Oil & Gas` and `Oil and Gas` normalize alike
/// and the taxonomy carries one key instead of two spellings of the same thing.
/// Everything else non-alphanumeric is a delimiter, which is what collapses
/// `Banks—Diversified`, `banks-diversified` and `Banks, Diversified` onto the
/// same words.
fn normalize(text: &str) -> String {
    let mut words: Vec<String> = Vec::new();
    let mut word = String::new();
    for character in text.chars() {
        if character.is_alphanumeric() {
            word.extend(character.to_lowercase());
            continue;
        }
        if !word.is_empty() {
            words.push(std::mem::take(&mut word));
        }
        if character == '&' {
            words.push("and".to_string());
        }
    }
    if !word.is_empty() {
        words.push(word);
    }
    if words.is_empty() {
        return String::new();
    }
    format!(" {} ", words.join(" "))
}

/// Whether any key appears in the normalized text as a whole word or phrase.
fn matches_any(normalized: &str, keys: &[&str]) -> bool {
    keys.iter()
        .any(|key| normalized.contains(normalize(key).as_str()))
}

/// Instruments that are not a direct claim on an operating business. Reached
/// only through text, since the provider's own instrument type is checked first.
const NOT_ELIGIBLE: &[&str] = &[
    "exchange traded fund",
    "exchange traded",
    "etf",
    "closed end fund",
    "mutual fund",
    "money market",
    "cryptocurrency",
    "digital currency",
    "reit",
    "real estate investment trust",
];

/// Float and book-equity economics. Never valued on operating cash flow.
///
/// Bare `health` is deliberately absent: it would capture pharmaceuticals and
/// medical devices, which are operating businesses. The health plans are named
/// explicitly instead.
const FINANCIAL_SERVICES: &[&str] = &[
    "financial services",
    "financials",
    "financial",
    "insurance",
    "reinsurance",
    "bank",
    "banks",
    "capital markets",
    "asset management",
    "credit services",
    "mortgage finance",
    "property & casualty",
    "diversified financial",
    "financial conglomerate",
    "savings & loan",
    "thrift",
    "brokerage",
    "investment banking",
    "healthcare plans",
    "health care plans",
    "managed care",
    "credit card",
    "consumer finance",
    "shell companies",
];

const OPERATING_SECTORS: &[&str] = &[
    "technology",
    "information technology",
    "industrials",
    "industrial",
    "consumer cyclical",
    "consumer defensive",
    "consumer staples",
    "consumer discretionary",
    "energy",
    "utilities",
    "basic materials",
    "materials",
    "communication services",
    "communication",
    "telecommunications",
];

const HEALTHCARE_SECTORS: &[&str] = &["healthcare", "health care"];

const HEALTHCARE_OPERATING: &[&str] = &[
    "drug",
    "drugs",
    "pharma",
    "pharmaceutical",
    "pharmaceuticals",
    "biotech",
    "biotechnology",
    "device",
    "devices",
    "diagnostics",
    "medical instruments",
    "medical devices",
    "medical care",
    "medical distribution",
    "health information",
    "health care equipment",
    "healthcare equipment",
    "hospitals",
    "medical facilities",
    "tools & diagnostics",
];

/// Industry tokens, for when the sector is missing or non-standard.
const OPERATING_INDUSTRIES: &[&str] = &[
    "software",
    "semiconductor",
    "semiconductors",
    "hardware",
    "computer",
    "internet content",
    "internet retail",
    "it services",
    "information technology services",
    "electronic",
    "electronics",
    "aerospace",
    "defense",
    "airlines",
    "railroad",
    "trucking",
    "logistics",
    "machinery",
    "construction",
    "building products",
    "real estate services",
    "property management",
    "engineering",
    "waste management",
    "farming",
    "agriculture",
    "auto manufacturers",
    "auto parts",
    "automobiles",
    "restaurants",
    "apparel",
    "footwear",
    "lodging",
    "leisure",
    "entertainment",
    "packaging",
    "tobacco",
    "beverages",
    "food products",
    "confectioners",
    "household products",
    "personal products",
    "discount stores",
    "department stores",
    "specialty retail",
    "oil & gas",
    "oil gas",
    "thermal coal",
    "uranium",
    "renewable",
    "solar",
    "electric utilities",
    "gas utilities",
    "water utilities",
    "independent power",
    "diversified utilities",
    "chemicals",
    "specialty chemicals",
    "steel",
    "aluminum",
    "copper",
    "gold",
    "silver",
    "other industrial metals",
    "other precious metals",
    "coking coal",
    "lumber",
    "paper",
    "building materials",
    "telecom",
    "telecommunications",
    "media",
    "publishing",
    "broadcasting",
    "advertising",
    "interactive media",
];

#[cfg(test)]
mod tests {
    use super::*;

    fn class_of(sector: &str, industry: &str) -> BusinessClass {
        classify(sector, industry, Instrument::CommonEquity)
    }

    /// The defence against the ACGL/CI defect, stated as a property rather than
    /// as a list of names: nothing reaches the operating model without being
    /// recognized as operating.
    #[test]
    fn unrecognized_text_never_defaults_to_the_operating_model() {
        assert_eq!(
            class_of("Widget Wrangling", "Bespoke Widgetry"),
            BusinessClass::Unclassified
        );
    }

    #[test]
    fn missing_text_refuses_rather_than_defaulting() {
        assert_eq!(class_of("", ""), BusinessClass::Unclassified);
    }

    #[test]
    fn a_health_plan_is_financial_services() {
        assert_eq!(
            class_of("Healthcare", "Healthcare Plans"),
            BusinessClass::FinancialServices
        );
    }

    #[test]
    fn a_drug_manufacturer_is_operating() {
        assert_eq!(
            class_of("Healthcare", "Drug Manufacturers - General"),
            BusinessClass::OperatingNonFinancial
        );
    }

    /// A pharmaceutical retailer would be missed by a key list carrying only the
    /// stem `pharma`, because word matching does not match fragments. The list
    /// carries the whole word instead of the matcher being loosened.
    #[test]
    fn a_pharmaceutical_retailer_is_operating() {
        assert_eq!(
            class_of("Healthcare", "Pharmaceutical Retailers"),
            BusinessClass::OperatingNonFinancial
        );
    }

    /// The CI case. A bare healthcare sector could be a health plan or a device
    /// maker, and guessing is what the closed world exists to prevent.
    #[test]
    fn a_healthcare_sector_with_no_industry_is_ambiguous_and_refuses() {
        assert_eq!(class_of("Healthcare", ""), BusinessClass::Unclassified);
    }

    #[test]
    fn an_insurer_is_financial_services() {
        assert_eq!(
            class_of("Financial Services", "Insurance - Property & Casualty"),
            BusinessClass::FinancialServices
        );
    }

    /// The class of defect a substring matcher produces silently. `bank` is a key
    /// and `bankruptcy` starts with it, so substring matching would route a
    /// restructuring advisor through the residual-income model on book.
    #[test]
    fn a_keyword_inside_a_longer_word_does_not_classify() {
        assert_eq!(
            class_of("Consulting", "Bankruptcy Advisory"),
            BusinessClass::Unclassified
        );
    }

    /// One key, both spellings — which is why the taxonomy does not need to
    /// carry `oil and gas` beside `oil & gas`.
    #[test]
    fn ampersands_become_the_word_and() {
        assert_eq!(
            class_of("Energy", "Oil and Gas E&P"),
            class_of("Energy", "Oil & Gas E&P")
        );
    }

    #[test]
    fn provider_slugs_and_display_names_classify_alike() {
        assert_eq!(
            class_of("consumer-cyclical", "auto-manufacturers"),
            class_of("Consumer Cyclical", "Auto Manufacturers")
        );
    }

    /// The instrument outranks the text: this fund's sector says technology.
    #[test]
    fn a_fund_is_never_eligible_whatever_it_holds() {
        assert_eq!(
            classify("Technology", "Software", Instrument::NotCommonEquity),
            BusinessClass::NotEligible
        );
    }

    #[test]
    fn a_trust_wrapper_is_not_eligible_from_its_text_alone() {
        assert_eq!(
            class_of("Real Estate", "REIT - Diversified"),
            BusinessClass::NotEligible
        );
    }

    #[test]
    fn only_the_two_valued_classes_route_to_a_model() {
        let routed: Vec<&str> = [
            BusinessClass::OperatingNonFinancial,
            BusinessClass::FinancialServices,
            BusinessClass::NotEligible,
            BusinessClass::Unclassified,
        ]
        .into_iter()
        .filter(|class| class.routes_to_a_model())
        .map(BusinessClass::as_str)
        .collect();
        assert_eq!(routed, ["operating_non_financial", "financial_services"]);
    }
}
