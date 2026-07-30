//! Multi-name valuation baseline cohort.
//!
//! Selection is pinned offline (see `tests/fixtures/valuation/`). Policy changes that
//! alter FCFF/WACC/routing must keep non-quarantined cohort members inside sanity
//! bands and preserve determinism. Single-ticker greens are not sufficient.

#![cfg(test)]

use crate::dcf_model::{compute, BusinessClass, FcfPoint, ValuationModel, MODEL_POLICY_VERSION};
use crate::engine::FundamentalSnapshot;
use serde::Deserialize;
use std::path::PathBuf;

const COHORT_FIXTURE: &str = "tests/fixtures/valuation/baseline_cohort_2026-07-30.json";

#[derive(Debug, Deserialize)]
struct CohortFile {
    as_of: String,
    members: Vec<CohortMember>,
}

#[derive(Debug, Deserialize)]
struct CohortMember {
    symbol: String,
    #[serde(default)]
    quarantine: bool,
    #[serde(default)]
    quarantine_reason: Option<String>,
    status: String,
    selection: SelectionMeta,
    inputs: MemberInputs,
}

#[derive(Debug, Deserialize)]
struct SelectionMeta {
    gap_bps: i32,
    confidence: String,
    composite_score: i32,
    #[allow(dead_code)]
    snapshot_market_price_cents: i64,
    snapshot_intrinsic_cents: i64,
}

#[derive(Debug, Deserialize)]
struct MemberInputs {
    market_price_cents: i64,
    shares_outstanding: Option<u64>,
    market_cap_dollars: Option<u64>,
    total_debt_dollars: i64,
    total_cash_dollars: i64,
    beta_millis: i32,
    sector_name: String,
    industry_name: String,
    analyst_target_mean_cents: Option<i64>,
    fcf_annual: Vec<FcfAnnual>,
}

#[derive(Debug, Deserialize)]
struct FcfAnnual {
    year: i32,
    value_dollars: f64,
}

fn fixture_path() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join(COHORT_FIXTURE)
}

fn load_cohort() -> CohortFile {
    let path = fixture_path();
    let raw = std::fs::read_to_string(&path)
        .unwrap_or_else(|e| panic!("read cohort fixture {}: {e}", path.display()));
    serde_json::from_str(&raw).expect("parse cohort fixture")
}

fn fund_from(m: &CohortMember) -> FundamentalSnapshot {
    FundamentalSnapshot {
        symbol: m.symbol.clone(),
        sector_name: Some(m.inputs.sector_name.clone()),
        industry_name: Some(m.inputs.industry_name.clone()),
        market_cap_dollars: m.inputs.market_cap_dollars,
        shares_outstanding: m.inputs.shares_outstanding,
        beta_millis: Some(m.inputs.beta_millis),
        total_debt_dollars: Some(m.inputs.total_debt_dollars),
        total_cash_dollars: Some(m.inputs.total_cash_dollars),
        ..Default::default()
    }
}

fn fcf_from(m: &CohortMember) -> Vec<FcfPoint> {
    m.inputs
        .fcf_annual
        .iter()
        .map(|p| FcfPoint::new(p.year, p.value_dollars))
        .collect()
}

/// Order-of-magnitude / penny collapse detectors (sanity, not price clamps).
///
/// A name can trade at a real discount; it must not recompute as ~1/10th–1/50th of
/// market (or of the selection-time model intrinsic) while still having material FCF.
fn is_absurd_collapse(
    market_price_cents: i64,
    base_cents: i64,
    fcf_run_rate: i64,
    shares: u64,
    selection_intrinsic_cents: i64,
) -> bool {
    if base_cents <= 0 {
        return true;
    }
    // Material franchise: enough cash flow + float to make a sub-decile base absurd.
    let material_fcf = fcf_run_rate >= 10_000_000 && shares >= 5_000_000;
    // Penny / sub-dollar equity with mid/large FCF franchise.
    if fcf_run_rate >= 500_000_000 && shares >= 50_000_000 && base_cents < 100 {
        return true;
    }
    // Order-of-magnitude under market: base < 10% of market with material FCF.
    // (Not a clamp-to-price band — only flags catastrophic understatement.)
    if material_fcf && market_price_cents >= 500 && base_cents < market_price_cents / 10 {
        return true;
    }
    // Order-of-magnitude under selection-time model intrinsic (~8×+ collapse).
    // Only when selection itself is not already an inflated premium vs market
    // (selection > 1.5× market often means the prior model overstated FCF).
    let selection_plausible = selection_intrinsic_cents > 0
        && selection_intrinsic_cents <= market_price_cents.saturating_mul(3) / 2;
    if material_fcf
        && selection_plausible
        && selection_intrinsic_cents >= 500
        && base_cents < selection_intrinsic_cents / 8
    {
        return true;
    }
    false
}

fn collapse_reason(
    market_price_cents: i64,
    base_cents: i64,
    fcf_run_rate: i64,
    shares: u64,
    selection_intrinsic_cents: i64,
) -> Option<&'static str> {
    if !is_absurd_collapse(
        market_price_cents,
        base_cents,
        fcf_run_rate,
        shares,
        selection_intrinsic_cents,
    ) {
        return None;
    }
    if base_cents <= 0 {
        return Some("non_positive_base");
    }
    if fcf_run_rate >= 500_000_000 && shares >= 50_000_000 && base_cents < 100 {
        return Some("penny_intrinsic_with_material_fcf");
    }
    if fcf_run_rate >= 10_000_000
        && shares >= 5_000_000
        && market_price_cents >= 500
        && base_cents < market_price_cents / 10
    {
        return Some("order_of_magnitude_under_market");
    }
    let selection_plausible = selection_intrinsic_cents > 0
        && selection_intrinsic_cents <= market_price_cents.saturating_mul(3) / 2;
    if fcf_run_rate >= 10_000_000
        && shares >= 5_000_000
        && selection_plausible
        && selection_intrinsic_cents >= 500
        && base_cents < selection_intrinsic_cents / 8
    {
        return Some("order_of_magnitude_under_selection_intrinsic");
    }
    Some("absurd_collapse")
}

fn evaluate_member(m: &CohortMember) -> Result<EvalRow, String> {
    if m.quarantine {
        return Ok(EvalRow {
            symbol: m.symbol.clone(),
            quarantined: true,
            quarantine_reason: m.quarantine_reason.clone(),
            base_cents: None,
            bear_cents: None,
            bull_cents: None,
            wacc_bps: None,
            fcf_run_rate: None,
            market_cents: m.inputs.market_price_cents,
            selection_intrinsic_cents: m.selection.snapshot_intrinsic_cents,
            anchor_cents: m.inputs.analyst_target_mean_cents,
            selection_gap_bps: m.selection.gap_bps,
            selection_score: m.selection.composite_score,
            absurd_collapse: false,
            collapse_reason: None,
        });
    }
    let fund = fund_from(m);
    let fcf = fcf_from(m);
    let a = compute(
        &fund,
        &fcf,
        Some(m.inputs.market_price_cents),
        "baseline_fixture",
    )
    .map_err(|e| format!("{}: {e}", m.symbol))?;
    let run = a.diagnostics.fcf_run_rate_dollars.unwrap_or(0);
    let shares = m.inputs.shares_outstanding.unwrap_or(0);
    let sel_iv = m.selection.snapshot_intrinsic_cents;
    let reason = collapse_reason(
        m.inputs.market_price_cents,
        a.base_intrinsic_value_cents,
        run,
        shares,
        sel_iv,
    );
    Ok(EvalRow {
        symbol: m.symbol.clone(),
        quarantined: false,
        quarantine_reason: None,
        base_cents: Some(a.base_intrinsic_value_cents),
        bear_cents: Some(a.bear_intrinsic_value_cents),
        bull_cents: Some(a.bull_intrinsic_value_cents),
        wacc_bps: Some(a.wacc_bps),
        fcf_run_rate: Some(run),
        market_cents: m.inputs.market_price_cents,
        selection_intrinsic_cents: sel_iv,
        anchor_cents: m.inputs.analyst_target_mean_cents,
        selection_gap_bps: m.selection.gap_bps,
        selection_score: m.selection.composite_score,
        absurd_collapse: reason.is_some(),
        collapse_reason: reason.map(|s| s.to_string()),
    })
}

#[derive(Debug)]
struct EvalRow {
    symbol: String,
    quarantined: bool,
    quarantine_reason: Option<String>,
    base_cents: Option<i64>,
    bear_cents: Option<i64>,
    bull_cents: Option<i64>,
    wacc_bps: Option<i32>,
    fcf_run_rate: Option<i64>,
    market_cents: i64,
    selection_intrinsic_cents: i64,
    anchor_cents: Option<i64>,
    selection_gap_bps: i32,
    selection_score: i32,
    absurd_collapse: bool,
    collapse_reason: Option<String>,
}

#[test]
fn baseline_cohort_fixture_exists_and_has_twenty_selection_slots() {
    let path = fixture_path();
    assert!(path.is_file(), "missing {}", path.display());
    let cohort = load_cohort();
    assert_eq!(
        cohort.members.len(),
        20,
        "cohort must pin 20 selection slots (quarantine allowed)"
    );
    assert!(!cohort.as_of.is_empty());
    let high_ok = cohort.members.iter().all(|m| {
        m.selection.confidence.eq_ignore_ascii_case("High") && m.selection.gap_bps >= 2000
    });
    assert!(
        high_ok,
        "every member must be High + gap_bps>=2000 from selection"
    );
    let active = cohort.members.iter().filter(|m| !m.quarantine).count();
    assert_eq!(
        active, 20,
        "all 20 cohort slots must be non-quarantined (replace unusable names offline)"
    );
}

#[test]
fn baseline_cohort_determinism_double_run() {
    let cohort = load_cohort();
    let active: Vec<_> = cohort.members.iter().filter(|m| !m.quarantine).collect();
    assert!(
        !active.is_empty(),
        "need at least one non-quarantined cohort member"
    );
    for m in active {
        let fund = fund_from(m);
        let fcf = fcf_from(m);
        let a = compute(
            &fund,
            &fcf,
            Some(m.inputs.market_price_cents),
            "baseline_fixture",
        )
        .unwrap_or_else(|e| panic!("{} first: {e}", m.symbol));
        let b = compute(
            &fund,
            &fcf,
            Some(m.inputs.market_price_cents),
            "baseline_fixture",
        )
        .unwrap_or_else(|e| panic!("{} second: {e}", m.symbol));
        assert_eq!(
            a.base_intrinsic_value_cents, b.base_intrinsic_value_cents,
            "{} base not deterministic",
            m.symbol
        );
        assert_eq!(a.bear_intrinsic_value_cents, b.bear_intrinsic_value_cents);
        assert_eq!(a.bull_intrinsic_value_cents, b.bull_intrinsic_value_cents);
        assert_eq!(a.wacc_bps, b.wacc_bps);
        assert_eq!(a.model_policy_version, MODEL_POLICY_VERSION);
    }
}

#[test]
fn baseline_cohort_sanity_and_no_silent_quarantine_skips() {
    let cohort = load_cohort();
    let mut rows = Vec::new();
    let mut failures = Vec::new();

    for m in &cohort.members {
        match evaluate_member(m) {
            Ok(row) => {
                if !row.quarantined {
                    let base = row.base_cents.unwrap();
                    let bear = row.bear_cents.unwrap();
                    let bull = row.bull_cents.unwrap();
                    if !(bear <= base && base <= bull) {
                        failures.push(format!(
                            "{} scenario order bear={bear} base={base} bull={bull}",
                            row.symbol
                        ));
                    }
                    if row.absurd_collapse {
                        failures.push(format!(
                            "{} absurd collapse ({}) base=${:.2} mkt=${:.2} sel_iv=${:.2} fcf={}",
                            row.symbol,
                            row.collapse_reason.as_deref().unwrap_or("?"),
                            base as f64 / 100.0,
                            row.market_cents as f64 / 100.0,
                            row.selection_intrinsic_cents as f64 / 100.0,
                            row.fcf_run_rate.unwrap_or(0)
                        ));
                    }
                    if base <= 0 {
                        failures.push(format!("{} non-positive base", row.symbol));
                    }
                } else {
                    assert!(
                        row.quarantine_reason.is_some(),
                        "{} quarantined without reason",
                        row.symbol
                    );
                }
                rows.push(row);
            }
            Err(e) => failures.push(e),
        }
    }

    // Emit report lines for manual/scratch capture when run with --nocapture.
    eprintln!("cohort_error_report as_of={}", cohort.as_of);
    for r in &rows {
        if r.quarantined {
            eprintln!(
                "QUARANTINE {} reason={} selection_gap_bps={} score={}",
                r.symbol,
                r.quarantine_reason.as_deref().unwrap_or("?"),
                r.selection_gap_bps,
                r.selection_score
            );
            continue;
        }
        let base = r.base_cents.unwrap();
        let gap_to_anchor = r
            .anchor_cents
            .map(|a| base - a)
            .map(|g| g.to_string())
            .unwrap_or_else(|| "anchor_missing".into());
        let gap_to_sel = base - r.selection_intrinsic_cents;
        eprintln!(
            "OK {} base_cents={} mkt_cents={} sel_iv_cents={} wacc={} fcf_run={} anchor_gap={} vs_sel={} selection_gap_bps={} absurd={} reason={}",
            r.symbol,
            base,
            r.market_cents,
            r.selection_intrinsic_cents,
            r.wacc_bps.unwrap_or(0),
            r.fcf_run_rate.unwrap_or(0),
            gap_to_anchor,
            gap_to_sel,
            r.selection_gap_bps,
            r.absurd_collapse,
            r.collapse_reason.as_deref().unwrap_or("-")
        );
    }

    assert!(
        failures.is_empty(),
        "baseline cohort sanity failures:\n{}",
        failures.join("\n")
    );
}

/// Isolation: T-class levered soft path (single-name calibration stress) must not
/// be the only green — active cohort members still evaluate without collapse.
#[test]
fn baseline_isolation_t_class_stress_with_cohort() {
    // T-class levered inputs (same spirit as dcf_model T tests).
    let t_fund = FundamentalSnapshot {
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
    let t_fcf = vec![
        FcfPoint::new(2023, 20_460_000_000.0),
        FcfPoint::new(2024, 18_510_000_000.0),
        FcfPoint::new(2025, 19_440_000_000.0),
    ];
    let t = compute(&t_fund, &t_fcf, Some(2_112), "isolation").expect("T");
    assert!(
        t.diagnostics.provisional_wacc_uplift_bps.unwrap_or(0) > 0,
        "T stress path must still apply provisional uplift"
    );
    let t_base = t.base_intrinsic_value_cents as f64 / 100.0;
    assert!(
        (15.0..45.0).contains(&t_base),
        "T isolation base out of band: {t_base}"
    );

    let cohort = load_cohort();
    let mut cohort_ok = 0usize;
    for m in cohort.members.iter().filter(|m| !m.quarantine) {
        let a = compute(
            &fund_from(m),
            &fcf_from(m),
            Some(m.inputs.market_price_cents),
            "isolation",
        )
        .unwrap_or_else(|e| panic!("cohort {} broke under T isolation context: {e}", m.symbol));
        let run = a.diagnostics.fcf_run_rate_dollars.unwrap_or(0);
        let shares = m.inputs.shares_outstanding.unwrap_or(0);
        assert!(
            !is_absurd_collapse(
                m.inputs.market_price_cents,
                a.base_intrinsic_value_cents,
                run,
                shares,
                m.selection.snapshot_intrinsic_cents,
            ),
            "{} collapsed while T-class path is green",
            m.symbol
        );
        cohort_ok += 1;
    }
    assert!(
        cohort_ok >= 5,
        "isolation requires several active cohort names"
    );
}

/// Mega-cap CapEx-trough style case (AMZN contract inputs) must not invert or collapse.
#[test]
fn baseline_megacap_amzn_class_not_penny_intrinsic() {
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
    // Contiguous positive suffix after CapEx-trough years (matches contract fixture spirit).
    let fcf = vec![
        FcfPoint::new(2020, 25_924_000_000.0),
        FcfPoint::new(2021, -14_726_000_000.0),
        FcfPoint::new(2022, -16_893_000_000.0),
        FcfPoint::new(2023, 32_217_000_000.0),
        FcfPoint::new(2024, 32_878_000_000.0),
        FcfPoint::new(2025, 7_695_000_000.0),
    ];
    let a = compute(&fund, &fcf, Some(23_933), "amzn_baseline").expect("AMZN");
    assert_eq!(a.model, ValuationModel::FcffWacc);
    assert!(
        a.bear_intrinsic_value_cents <= a.base_intrinsic_value_cents
            && a.base_intrinsic_value_cents <= a.bull_intrinsic_value_cents,
        "AMZN scenarios inverted"
    );
    let base = a.base_intrinsic_value_cents;
    let run = a.diagnostics.fcf_run_rate_dollars.unwrap_or(0);
    eprintln!(
        "amzn_baseline base_cents={} run_rate={} wacc={} growth={}",
        base, run, a.wacc_bps, a.base_growth_bps
    );
    assert!(
        base >= 500,
        "AMZN-class mega-cap must not collapse to penny intrinsic, base_cents={base}"
    );
    // Multi-ten-billion normalized FCF: never a $1 mirage (user-reported failure mode).
    // Full AMZN economic calibration is tracked separately; this is the anti-collapse floor.
    assert!(
        run >= 10_000_000_000,
        "AMZN fixture must keep multi-ten-B normalized FCF run-rate, got {run}"
    );
    assert!(
        base >= 1_000,
        "AMZN-class with multi-ten-B FCF must not price under $10, base_cents={base}"
    );
}

/// MU-class regression: large FCF franchise must not recompute as ~1/50 of market.
#[test]
fn baseline_mu_class_order_of_magnitude_is_detected() {
    // Same shape as fixture MU row (SEC OCF−CapEx pin) — helper must flag collapse.
    let market = 86_085;
    let base = 1_557; // ~$15.57
    let fcf_run = 894_500_000;
    let shares = 1_142_000_000;
    let sel_iv = 150_738; // snapshot intrinsic ~$1507
    assert!(
        is_absurd_collapse(market, base, fcf_run, shares, sel_iv),
        "MU-class understatement must be classified as absurd collapse"
    );
    assert_eq!(
        collapse_reason(market, base, fcf_run, shares, sel_iv),
        Some("order_of_magnitude_under_market")
    );
}

/// Permanent CI (managed care) fixture — must never FCFF-primary again.
#[test]
fn baseline_ci_managed_care_not_fcff_primary() {
    let fund = FundamentalSnapshot {
        symbol: "CI".into(),
        sector_name: Some("Healthcare".into()),
        industry_name: Some("Healthcare Plans".into()),
        sector_key: Some("healthcare".into()),
        industry_key: Some("healthcare-plans".into()),
        market_cap_dollars: Some(80_000_000_000),
        shares_outstanding: Some(270_000_000),
        beta_millis: Some(600),
        return_on_equity_bps: Some(1_800),
        book_value_per_share_cents: Some(15_000),
        price_to_book_hundredths: Some(193),
        ..Default::default()
    };
    let fake_float = vec![
        FcfPoint::new(2022, 8_000_000_000.0),
        FcfPoint::new(2023, 9_000_000_000.0),
        FcfPoint::new(2024, 10_000_000_000.0),
        FcfPoint::new(2025, 11_000_000_000.0),
    ];
    let a = compute(&fund, &fake_float, Some(28_969), "ci_baseline").expect("RI");
    assert_eq!(a.model, ValuationModel::ResidualIncomeEquity);
    assert_eq!(a.business_class, BusinessClass::FinancialServices);
    let base = a.base_intrinsic_value_cents as f64 / 100.0;
    assert!(base < 400.0, "CI must not emit FCFF mirage, base=${base}");
}

#[test]
fn baseline_financials_safety_acgl_not_fcff_primary() {
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
        ..Default::default()
    };
    let fake_float = vec![
        FcfPoint::new(2022, 3_800_000_000.0),
        FcfPoint::new(2023, 5_700_000_000.0),
        FcfPoint::new(2024, 6_600_000_000.0),
        FcfPoint::new(2025, 6_172_000_000.0),
    ];
    let a = compute(&fund, &fake_float, Some(10_336), "acgl").expect("RI");
    assert_eq!(a.business_class, BusinessClass::FinancialServices);
    assert_eq!(a.model, ValuationModel::ResidualIncomeEquity);
    assert!(a.base_intrinsic_value_cents as f64 / 100.0 < 400.0);
}

#[test]
fn baseline_quarantine_entries_are_explicit_not_green() {
    let cohort = load_cohort();
    let q: Vec<_> = cohort.members.iter().filter(|m| m.quarantine).collect();
    // Prefer zero quarantines (replace unusable names). Any remaining must be labeled.
    for m in &q {
        assert_eq!(m.selection.confidence, "High");
        assert!(m.selection.gap_bps >= 2000);
        assert!(
            m.quarantine_reason
                .as_ref()
                .map(|r| !r.is_empty())
                .unwrap_or(false),
            "{} missing quarantine_reason",
            m.symbol
        );
        // Must not silently run compute and ignore failure: status marks insufficiency.
        assert_ne!(m.status, "ok");
    }
    assert!(
        q.is_empty(),
        "expected 0 quarantines in pinned top-20; got {} — replace with usable High+20% names",
        q.len()
    );
}
