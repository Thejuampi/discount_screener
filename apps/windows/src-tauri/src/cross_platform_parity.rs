//! Cross-platform valuation parity export (Windows side).
//!
//! Writes fixed-input DCF results for the QA-style 20-name baseline cohort plus
//! checklist fixtures (T, AMZN, ACGL) so Android can be compared cent-for-cent.

#![cfg(test)]

use crate::dcf_model::{
    compute, compute_from_fundamentals, FcfPoint, WaccFieldSource, ENGINE_VERSION,
    MODEL_POLICY_VERSION,
};
use crate::engine::FundamentalSnapshot;
use serde::Serialize;
use std::collections::HashMap;
use std::path::PathBuf;

const COHORT_FIXTURE: &str = "tests/fixtures/valuation/baseline_cohort_2026-07-30.json";
const DRIVER_FIXTURE: &str = "tests/fixtures/valuation/baseline_driver_data_2026-07-30.json";

#[derive(Debug, serde::Deserialize)]
struct CohortFile {
    members: Vec<CohortMember>,
}

#[derive(Debug, serde::Deserialize)]
struct CohortMember {
    symbol: String,
    #[serde(default)]
    quarantine: bool,
    inputs: MemberInputs,
}

#[derive(Debug, serde::Deserialize)]
struct MemberInputs {
    market_price_cents: i64,
    shares_outstanding: Option<u64>,
    market_cap_dollars: Option<u64>,
    total_debt_dollars: i64,
    total_cash_dollars: i64,
    beta_millis: i32,
    sector_name: String,
    industry_name: String,
    fcf_annual: Vec<FcfAnnual>,
}

#[derive(Debug, serde::Deserialize)]
struct FcfAnnual {
    year: i32,
    value_dollars: f64,
}

#[derive(Debug, serde::Deserialize)]
struct DriverFile {
    rows: HashMap<String, Vec<DriverAnnual>>,
}

#[derive(Debug, serde::Deserialize)]
struct DriverAnnual {
    year: i32,
    ocf: f64,
    capex: f64,
    revenue: f64,
    interest: f64,
    effective_tax_bps: i32,
    debt: Option<f64>,
    marginal_tax_bps: i32,
}

#[derive(Debug, Serialize)]
struct ParityRow {
    symbol: String,
    case: String,
    ok: bool,
    error: Option<String>,
    engine_version: Option<String>,
    model_policy_version: Option<String>,
    business_class: Option<String>,
    model: Option<String>,
    discount_rate_kind: Option<String>,
    bear_cents: Option<i64>,
    base_cents: Option<i64>,
    bull_cents: Option<i64>,
    wacc_bps: Option<i32>,
    base_growth_bps: Option<i32>,
    net_debt_dollars: Option<i64>,
    provisional_wacc_uplift_bps: Option<i32>,
    latest_fcf_dollars: Option<i64>,
    fcf_run_rate_dollars: Option<i64>,
    fcf_run_rate_normalized: Option<bool>,
    debt_weight_bps: Option<i32>,
    point_estimate_unreliable: Option<bool>,
    scenario_stress: Option<String>,
    wacc_bear_bps: Option<i32>,
    wacc_bull_bps: Option<i32>,
    valuation_driver: Option<String>,
    latest_revenue_dollars: Option<i64>,
    normalized_fcff_dollars: Option<i64>,
    normalized_ocf_margin_bps: Option<i32>,
    normalized_capex_intensity_bps: Option<i32>,
    normalized_after_tax_interest_margin_bps: Option<i32>,
    capex_spike_years: Vec<i32>,
    growth_driver: Option<String>,
    driver_input_fingerprint: Option<String>,
    driver_provenance: Vec<String>,
    wacc_market_cap_source: Option<String>,
    wacc_beta_source: Option<String>,
    wacc_total_debt_source: Option<String>,
    wacc_total_cash_source: Option<String>,
    wacc_cost_of_debt_source: Option<String>,
    wacc_tax_source: Option<String>,
    reason_codes: Vec<String>,
}

#[derive(Debug, Serialize)]
struct ParityExport {
    surface: String,
    profile_scope: String,
    engine_version: String,
    model_policy_version: String,
    rows: Vec<ParityRow>,
}

fn repo_tmp_export_path() -> PathBuf {
    // apps/windows/src-tauri -> repo root
    let manifest = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    let root = manifest
        .parent()
        .and_then(|p| p.parent())
        .and_then(|p| p.parent())
        .expect("repo root");
    let dir = root.join(".agents/workspace/tmp");
    std::fs::create_dir_all(&dir).expect("create tmp");
    dir.join("parity-windows-qa.json")
}

fn load_cohort() -> CohortFile {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join(COHORT_FIXTURE);
    let raw = std::fs::read_to_string(&path).expect("read cohort");
    serde_json::from_str(&raw).expect("parse cohort")
}

fn load_driver_data() -> DriverFile {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join(DRIVER_FIXTURE);
    let raw = std::fs::read_to_string(&path).expect("read driver fixture");
    serde_json::from_str(&raw).expect("parse driver fixture")
}

fn row_from_analysis(symbol: &str, case: &str, a: &crate::dcf_model::DcfAnalysis) -> ParityRow {
    ParityRow {
        symbol: symbol.into(),
        case: case.into(),
        ok: true,
        error: None,
        engine_version: Some(a.engine_version.clone()),
        model_policy_version: Some(a.model_policy_version.clone()),
        business_class: Some(format!("{:?}", a.business_class)),
        model: Some(format!("{:?}", a.model)),
        discount_rate_kind: Some(format!("{:?}", a.discount_rate_kind)),
        bear_cents: Some(a.bear_intrinsic_value_cents),
        base_cents: Some(a.base_intrinsic_value_cents),
        bull_cents: Some(a.bull_intrinsic_value_cents),
        wacc_bps: Some(a.wacc_bps),
        base_growth_bps: Some(a.base_growth_bps),
        net_debt_dollars: Some(a.net_debt_dollars),
        provisional_wacc_uplift_bps: a.diagnostics.provisional_wacc_uplift_bps,
        latest_fcf_dollars: a.diagnostics.latest_fcf_dollars,
        fcf_run_rate_dollars: a.diagnostics.fcf_run_rate_dollars,
        fcf_run_rate_normalized: Some(a.diagnostics.fcf_run_rate_normalized),
        debt_weight_bps: a.diagnostics.debt_weight_bps,
        point_estimate_unreliable: Some(a.diagnostics.point_estimate_unreliable),
        scenario_stress: Some(a.diagnostics.scenario_stress.clone()),
        wacc_bear_bps: a.diagnostics.wacc_bear_bps,
        wacc_bull_bps: a.diagnostics.wacc_bull_bps,
        valuation_driver: Some(a.diagnostics.valuation_driver.clone()),
        latest_revenue_dollars: a.diagnostics.latest_revenue_dollars,
        normalized_fcff_dollars: a.diagnostics.normalized_fcff_dollars,
        normalized_ocf_margin_bps: a.diagnostics.normalized_ocf_margin_bps,
        normalized_capex_intensity_bps: a.diagnostics.normalized_capex_intensity_bps,
        normalized_after_tax_interest_margin_bps: a
            .diagnostics
            .normalized_after_tax_interest_margin_bps,
        capex_spike_years: a.diagnostics.capex_spike_years.clone(),
        growth_driver: Some(a.diagnostics.growth_driver.clone()),
        driver_input_fingerprint: a.diagnostics.driver_input_fingerprint.clone(),
        driver_provenance: a.diagnostics.driver_provenance.clone(),
        wacc_market_cap_source: Some(format!("{:?}", a.wacc_inputs.market_cap)),
        wacc_beta_source: Some(format!("{:?}", a.wacc_inputs.beta)),
        wacc_total_debt_source: Some(format!("{:?}", a.wacc_inputs.total_debt)),
        wacc_total_cash_source: Some(format!("{:?}", a.wacc_inputs.total_cash)),
        wacc_cost_of_debt_source: Some(format!("{:?}", a.wacc_inputs.cost_of_debt)),
        wacc_tax_source: Some(format!("{:?}", a.wacc_inputs.tax_rate)),
        reason_codes: a.reason_codes.clone(),
    }
}

fn err_row(symbol: &str, case: &str, err: String) -> ParityRow {
    ParityRow {
        symbol: symbol.into(),
        case: case.into(),
        ok: false,
        error: Some(err),
        engine_version: None,
        model_policy_version: None,
        business_class: None,
        model: None,
        discount_rate_kind: None,
        bear_cents: None,
        base_cents: None,
        bull_cents: None,
        wacc_bps: None,
        base_growth_bps: None,
        net_debt_dollars: None,
        provisional_wacc_uplift_bps: None,
        latest_fcf_dollars: None,
        fcf_run_rate_dollars: None,
        fcf_run_rate_normalized: None,
        debt_weight_bps: None,
        point_estimate_unreliable: None,
        scenario_stress: None,
        wacc_bear_bps: None,
        wacc_bull_bps: None,
        valuation_driver: None,
        latest_revenue_dollars: None,
        normalized_fcff_dollars: None,
        normalized_ocf_margin_bps: None,
        normalized_capex_intensity_bps: None,
        normalized_after_tax_interest_margin_bps: None,
        capex_spike_years: vec![],
        growth_driver: None,
        driver_input_fingerprint: None,
        driver_provenance: vec![],
        wacc_market_cap_source: None,
        wacc_beta_source: None,
        wacc_total_debt_source: None,
        wacc_total_cash_source: None,
        wacc_cost_of_debt_source: None,
        wacc_tax_source: None,
        reason_codes: vec![],
    }
}

#[test]
fn export_qa_cohort_parity_snapshot_for_android() {
    let cohort = load_cohort();
    let driver_data = load_driver_data();
    let mut rows = Vec::new();

    for m in &cohort.members {
        if m.quarantine {
            continue;
        }
        let fund = FundamentalSnapshot {
            symbol: m.symbol.clone(),
            sector_name: Some(m.inputs.sector_name.clone()),
            industry_name: Some(m.inputs.industry_name.clone()),
            market_cap_dollars: m.inputs.market_cap_dollars,
            shares_outstanding: m.inputs.shares_outstanding,
            beta_millis: Some(m.inputs.beta_millis),
            total_debt_dollars: Some(m.inputs.total_debt_dollars),
            total_cash_dollars: Some(m.inputs.total_cash_dollars),
            ..Default::default()
        };
        let driver_rows = driver_data.rows.get(&m.symbol);
        let fcf: Vec<FcfPoint> = m
            .inputs
            .fcf_annual
            .iter()
            .map(|p| {
                let mut point = FcfPoint::new(p.year, p.value_dollars);
                if let Some(row) =
                    driver_rows.and_then(|rows| rows.iter().find(|r| r.year == p.year))
                {
                    point = point
                        .with_operating_drivers(
                            row.ocf,
                            -row.capex,
                            row.revenue,
                            Some(row.interest),
                            Some(row.effective_tax_bps),
                        )
                        .with_rate_resolution_inputs(
                            row.debt,
                            Some(row.marginal_tax_bps),
                            None,
                            None,
                        )
                        .with_marginal_tax_source(WaccFieldSource::JurisdictionStatutory);
                }
                point
            })
            .collect();
        match compute(
            &fund,
            &fcf,
            Some(m.inputs.market_price_cents),
            "provider_timeseries",
        ) {
            Ok(a) => rows.push(row_from_analysis(&m.symbol, "qa_baseline_cohort", &a)),
            Err(e) => rows.push(err_row(&m.symbol, "qa_baseline_cohort", e)),
        }
    }

    // Checklist / contract pins (same numbers as shared/contracts valuation-model-family.json).
    rows.push(compute_t());
    rows.push(compute_amzn());
    rows.push(compute_acgl());
    // MPWR is not a member of baseline_cohort_2026-07-30.json, so the cohort-driven
    // loop above cannot exercise the negated interest-sign convention; see
    // compute_mpwr's doc comment for why this row exists instead.
    rows.push(compute_mpwr());

    let export = ParityExport {
        surface: "windows".into(),
        profile_scope: "qa_baseline_cohort_20_plus_checklist_T_AMZN_ACGL".into(),
        engine_version: ENGINE_VERSION.into(),
        model_policy_version: MODEL_POLICY_VERSION.into(),
        rows,
    };

    let path = repo_tmp_export_path();
    let json = serde_json::to_string_pretty(&export).expect("serialize");
    std::fs::write(&path, json).expect("write parity export");
    assert!(path.is_file(), "export missing at {}", path.display());
    eprintln!("wrote Windows QA parity snapshot: {}", path.display());
}

fn compute_t() -> ParityRow {
    let fund = FundamentalSnapshot {
        symbol: "T".into(),
        sector_name: Some("Communication Services".into()),
        industry_name: Some("Telecom Services".into()),
        market_cap_dollars: Some(146_748_915_712),
        shares_outstanding: Some(6_948_338_835),
        beta_millis: Some(422),
        total_debt_dollars: Some(159_750_995_968),
        total_cash_dollars: Some(11_964_000_256),
        ..Default::default()
    };
    let fcf = vec![
        FcfPoint::new(2021, 26_420_000_000.0),
        FcfPoint::new(2022, 12_397_000_000.0)
            .with_operating_drivers(
                32_023_000_000.0,
                -19_626_000_000.0,
                120_741_000_000.0,
                Some(6_108_000_000.0),
                Some(2_100),
            )
            .with_rate_resolution_inputs(Some(154_679_000_000.0), Some(2_100), None, None),
        FcfPoint::new(2023, 20_460_000_000.0)
            .with_operating_drivers(
                38_314_000_000.0,
                -17_853_000_000.0,
                122_428_000_000.0,
                Some(6_704_000_000.0),
                Some(2_130),
            )
            .with_rate_resolution_inputs(Some(154_899_000_000.0), Some(2_100), None, None),
        FcfPoint::new(2024, 18_510_000_000.0)
            .with_operating_drivers(
                38_771_000_000.0,
                -20_263_000_000.0,
                122_336_000_000.0,
                Some(6_759_000_000.0),
                Some(2_660),
            )
            .with_rate_resolution_inputs(Some(140_923_000_000.0), Some(2_100), None, None),
        FcfPoint::new(2025, 19_440_000_000.0)
            .with_operating_drivers(
                40_284_000_000.0,
                -20_842_000_000.0,
                125_648_000_000.0,
                Some(6_804_000_000.0),
                Some(1_340),
            )
            .with_rate_resolution_inputs(Some(155_043_000_000.0), Some(2_100), None, None),
    ];
    match compute(&fund, &fcf, Some(2_112), "provider_timeseries") {
        Ok(a) => row_from_analysis("T", "checklist_t_contract", &a),
        Err(e) => err_row("T", "checklist_t_contract", e),
    }
}

fn compute_amzn() -> ParityRow {
    let fund = FundamentalSnapshot {
        symbol: "AMZN".into(),
        sector_name: Some("Consumer Cyclical".into()),
        industry_name: Some("Internet Retail".into()),
        market_cap_dollars: Some(2_574_493_679_616),
        shares_outstanding: Some(10_757_109_436),
        beta_millis: Some(1_461),
        total_debt_dollars: Some(235_540_004_864),
        total_cash_dollars: Some(143_088_992_256),
        ..Default::default()
    };
    let fcf = vec![
        FcfPoint::new(2020, 25_924_000_000.0),
        FcfPoint::new(2021, -14_726_000_000.0),
        FcfPoint::new(2022, -16_893_000_000.0)
            .with_operating_drivers(
                46_752_000_000.0,
                63_645_000_000.0,
                513_983_000_000.0,
                Some(2_367_000_000.0),
                Some(2_100),
            )
            .with_rate_resolution_inputs(Some(178_500_000_000.0), Some(2_100), None, None),
        FcfPoint::new(2023, 32_217_000_000.0)
            .with_operating_drivers(
                84_946_000_000.0,
                52_729_000_000.0,
                574_785_000_000.0,
                Some(3_182_000_000.0),
                Some(1_896),
            )
            .with_rate_resolution_inputs(Some(177_500_000_000.0), Some(2_100), None, None),
        FcfPoint::new(2024, 32_878_000_000.0)
            .with_operating_drivers(
                115_877_000_000.0,
                82_999_000_000.0,
                637_959_000_000.0,
                Some(2_406_000_000.0),
                Some(1_350),
            )
            .with_rate_resolution_inputs(Some(178_000_000_000.0), Some(2_100), None, None),
        FcfPoint::new(2025, 7_695_000_000.0)
            .with_operating_drivers(
                139_514_000_000.0,
                131_819_000_000.0,
                716_924_000_000.0,
                Some(2_274_000_000.0),
                Some(1_961),
            )
            .with_rate_resolution_inputs(Some(235_540_000_000.0), Some(2_100), None, None),
    ];
    match compute(&fund, &fcf, Some(23_933), "provider_timeseries") {
        Ok(a) => row_from_analysis("AMZN", "checklist_amzn_contract", &a),
        Err(e) => err_row("AMZN", "checklist_amzn_contract", e),
    }
}

fn compute_acgl() -> ParityRow {
    let fund = FundamentalSnapshot {
        symbol: "ACGL".into(),
        sector_name: Some("Financial Services".into()),
        industry_name: Some("Insurance - Property & Casualty".into()),
        market_cap_dollars: Some(36_000_000_000),
        shares_outstanding: Some(349_390_000),
        beta_millis: Some(292),
        return_on_equity_bps: Some(2_000),
        book_value_per_share_cents: Some(6_511),
        price_to_book_hundredths: Some(159),
        retention_bps: Some(7_000),
        ..Default::default()
    };
    match compute_from_fundamentals(&fund, Some(10_336), "provider_timeseries") {
        Ok(a) => row_from_analysis("ACGL", "checklist_acgl_ri", &a),
        Err(e) => err_row("ACGL", "checklist_acgl_ri", e),
    }
}

/// MPWR (Monolithic Power Systems, CIK 1280452) files
/// `InterestIncomeExpenseNonoperatingNet` POSITIVE every year: it carries no
/// debt (no `TOTAL_DEBT`/`CURRENT_DEBT`/`NON_CURRENT_DEBT` concept is filed at
/// all) and holds substantial cash, so the line is net interest INCOME, not
/// expense. Real filed values, USD, from SEC XBRL `companyconcept`:
///   FY2022 +14,369,000  FY2023 +23,363,000  FY2024 +27,093,000  FY2025 +29,151,000
/// Under the negated-sign convention added in sec-driver-normalization/9, the
/// `interestExpense` driver correctly resolves this concept to a NEGATIVE
/// dollar amount below (net income, not net expense) -- that is what is passed
/// into `with_operating_drivers` here.
///
/// KNOWN HAZARD -- W2a is deliberately inert (J6 stays FALSE at exit):
/// `FcfPoint::with_operating_drivers` still un-negates via `.map(f64::abs)`
/// (dcf_model.rs:907), so even this correctly-signed negative value is
/// silently flipped back to positive before it reaches `compute()`. This row
/// exists to make that hazard observable in the exported parity snapshot; the
/// direct, asserting pin for the same hazard is
/// `mpwr_negative_interest_income_is_still_unnegated_by_with_operating_drivers`
/// below. Neither is fixed until a later wave removes dcf_model.rs's three
/// `.abs()` sites.
///
/// FY2024 `IncomeTaxExpenseBenefit` is filed as -1,213,788,000 (10-K filed
/// 2025-03-03) and restated to -1,019,146,000 (10-K filed 2026-02-27) -- both a
/// large NEGATIVE tax expense (a tax benefit exceeding pretax income of
/// 572,912,000), unlike every other year. Neither filing explains the swing,
/// and using it naively would fabricate an effective tax rate that is not a
/// defensible reading of the evidence. Rather than invent a number, FY2024's
/// `tax_rate_bps` is left `None`; its interest and other operating drivers are
/// still supplied so the sign hazard remains observable for that year too.
fn compute_mpwr() -> ParityRow {
    let fund = FundamentalSnapshot {
        symbol: "MPWR".into(),
        sector_name: Some("Technology".into()),
        industry_name: Some("Semiconductors".into()),
        market_cap_dollars: Some(65_550_022_380),
        shares_outstanding: Some(48_309_000),
        beta_millis: Some(1_694),
        total_debt_dollars: Some(0),
        total_cash_dollars: Some(1_099_302_000),
        ..Default::default()
    };
    let fcf = vec![
        FcfPoint::new(2022, 187_831_000.0)
            .with_operating_drivers(
                246_674_000.0,
                -58_843_000.0,
                1_794_148_000.0,
                Some(-14_369_000.0),
                Some(1_662),
            )
            .with_rate_resolution_inputs(Some(0.0), Some(2_100), None, None),
        FcfPoint::new(2023, 580_635_000.0)
            .with_operating_drivers(
                638_213_000.0,
                -57_578_000.0,
                1_821_072_000.0,
                Some(-23_363_000.0),
                Some(1_551),
            )
            .with_rate_resolution_inputs(Some(0.0), Some(2_100), None, None),
        FcfPoint::new(2024, 642_292_000.0)
            .with_operating_drivers(
                788_410_000.0,
                -146_118_000.0,
                2_207_100_000.0,
                Some(-27_093_000.0),
                None,
            )
            .with_rate_resolution_inputs(Some(0.0), Some(2_100), None, None),
        FcfPoint::new(2025, 666_189_000.0)
            .with_operating_drivers(
                838_202_000.0,
                -172_013_000.0,
                2_790_459_000.0,
                Some(-29_151_000.0),
                Some(1_889),
            )
            .with_rate_resolution_inputs(Some(0.0), Some(2_100), None, None),
    ];
    match compute(&fund, &fcf, Some(133_503), "provider_timeseries") {
        Ok(a) => row_from_analysis("MPWR", "checklist_mpwr_negated_interest_sign", &a),
        Err(e) => err_row("MPWR", "checklist_mpwr_negated_interest_sign", e),
    }
}

/// Direct, asserting pin for the same hazard `compute_mpwr` documents: even a
/// correctly-signed negative (net interest income) value is silently
/// un-negated by `FcfPoint::with_operating_drivers` before it can reach
/// `compute()`. W2a computes the sign correctly (see `edgar.rs`'s
/// `concept_observations`/`concept_vintages`) but deliberately leaves
/// dcf_model.rs's `.abs()` sites standing, so this assertion is expected to
/// keep passing until a later wave removes them.
#[test]
fn mpwr_negative_interest_income_is_still_unnegated_by_with_operating_drivers() {
    let point = FcfPoint::new(2025, 666_189_000.0).with_operating_drivers(
        838_202_000.0,
        -172_013_000.0,
        2_790_459_000.0,
        Some(-29_151_000.0),
        Some(1_889),
    );
    assert_eq!(
        point.interest_expense_dollars,
        Some(29_151_000.0),
        "known hazard (W2a is deliberately inert): dcf_model.rs:907's `.map(f64::abs)` \
         un-negates the correctly-signed net interest INCOME back to a positive \
         'expense' before it reaches compute(); this is not fixed until a later wave \
         removes dcf_model.rs's three `.abs()` sites"
    );
}

// ── Random-20 SP500 live pins (inputs from SEC+Yahoo offline fixture) ─────────

#[derive(Debug, serde::Deserialize)]
struct Random20File {
    members: Vec<Random20Member>,
}

#[derive(Debug, serde::Deserialize)]
struct Random20Member {
    symbol: String,
    sector_name: String,
    industry_name: String,
    market_price_cents: i64,
    shares_outstanding: Option<u64>,
    market_cap_dollars: Option<u64>,
    beta_millis: i32,
    total_debt_dollars: i64,
    total_cash_dollars: i64,
    book_value_per_share_cents: Option<i64>,
    return_on_equity_bps: Option<i32>,
    fcf_annual: Vec<FcfAnnual>,
}

fn random20_path() -> PathBuf {
    let manifest = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    let root = manifest
        .parent()
        .and_then(|p| p.parent())
        .and_then(|p| p.parent())
        .expect("repo root");
    root.join(".agents/workspace/tmp/random20-inputs.json")
}

fn random20_export_path() -> PathBuf {
    let manifest = PathBuf::from(env!("CARGO_MANIFEST_DIR"));
    let root = manifest
        .parent()
        .and_then(|p| p.parent())
        .and_then(|p| p.parent())
        .expect("repo root");
    let dir = root.join(".agents/workspace/tmp");
    std::fs::create_dir_all(&dir).expect("create tmp");
    dir.join("parity-windows-random20.json")
}

#[test]
fn export_random20_sp500_parity_snapshot() {
    let path = random20_path();
    assert!(
        path.is_file(),
        "missing random20 inputs at {} — run .agents/workspace/tmp/build_random20_inputs.py first",
        path.display()
    );
    let raw = std::fs::read_to_string(&path).expect("read random20");
    let file: Random20File = serde_json::from_str(&raw).expect("parse random20");
    assert_eq!(file.members.len(), 20, "expected 20 random symbols");

    let mut rows = Vec::new();
    for m in &file.members {
        let fund = FundamentalSnapshot {
            symbol: m.symbol.clone(),
            sector_name: Some(m.sector_name.clone()),
            industry_name: Some(m.industry_name.clone()),
            market_cap_dollars: m.market_cap_dollars,
            shares_outstanding: m.shares_outstanding,
            beta_millis: Some(m.beta_millis),
            total_debt_dollars: Some(m.total_debt_dollars),
            total_cash_dollars: Some(m.total_cash_dollars),
            book_value_per_share_cents: m.book_value_per_share_cents,
            return_on_equity_bps: m.return_on_equity_bps,
            ..Default::default()
        };
        let fcf: Vec<FcfPoint> = m
            .fcf_annual
            .iter()
            .map(|p| FcfPoint::new(p.year, p.value_dollars))
            .collect();
        let row = if fcf.is_empty() {
            match compute_from_fundamentals(&fund, Some(m.market_price_cents), "random20") {
                Ok(a) => row_from_analysis(&m.symbol, "random20_sp500", &a),
                Err(e) => err_row(&m.symbol, "random20_sp500", e),
            }
        } else {
            match compute(&fund, &fcf, Some(m.market_price_cents), "random20") {
                Ok(a) => row_from_analysis(&m.symbol, "random20_sp500", &a),
                Err(e) => err_row(&m.symbol, "random20_sp500", e),
            }
        };
        rows.push(row);
    }

    let export = ParityExport {
        surface: "windows".into(),
        profile_scope: "random20_sp500_seed_20260730".into(),
        engine_version: ENGINE_VERSION.into(),
        model_policy_version: MODEL_POLICY_VERSION.into(),
        rows,
    };
    let out = random20_export_path();
    std::fs::write(&out, serde_json::to_string_pretty(&export).unwrap()).unwrap();
    eprintln!("wrote Windows random20 parity snapshot: {}", out.display());
}
