//! Market-cap weighted index scenario estimates (Android IndexEstimatesEngine port).

use serde::Serialize;

use crate::dcf_model::DcfAnalysis;
use crate::engine::CandidateRow;

#[derive(Debug, Clone, Copy, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum EstimateScenario {
    BearDcf,
    BaseDcf,
    BullDcf,
    AnalystLow,
    AnalystHigh,
}

#[derive(Debug, Clone, Serialize)]
pub struct ScenarioEstimate {
    pub scenario: EstimateScenario,
    pub weighted_price_cents: i64,
    pub coverage_count: usize,
    pub implied_upside_bps: i32,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum DcfCoverageStatus {
    Unavailable,
    LowConfidence,
    Partial,
    Provisional,
    Ready,
}

#[derive(Debug, Clone, Serialize)]
pub struct DcfCoverageSummary {
    pub total_eligible_symbols: usize,
    pub covered_symbols: usize,
    pub coverage_bps: i32,
    pub status: DcfCoverageStatus,
}

#[derive(Debug, Clone, Serialize)]
pub struct IndexEstimatesReport {
    pub profile_name: String,
    pub current_weighted_price_cents: i64,
    pub total_symbols: usize,
    pub scenarios: Vec<ScenarioEstimate>,
    pub computed_at_epoch_seconds: i64,
    pub dcf_coverage: DcfCoverageSummary,
}

pub fn compute(
    rows: &[CandidateRow],
    dcf_by_symbol: &std::collections::HashMap<String, DcfAnalysis>,
    profile_name: &str,
    now_epoch_seconds: i64,
) -> IndexEstimatesReport {
    let mut total_cap = 0u64;
    let mut weighted_current = 0.0;
    let mut eligible = 0usize;
    for row in rows {
        let Some(cap) = row.market_cap_dollars.filter(|&c| c > 0) else {
            continue;
        };
        total_cap += cap;
        weighted_current += row.market_price_cents as f64 * cap as f64;
        eligible += 1;
    }
    let current_weighted = if total_cap > 0 {
        (weighted_current / total_cap as f64) as i64
    } else {
        0
    };

    let scenarios = [
        EstimateScenario::BearDcf,
        EstimateScenario::BaseDcf,
        EstimateScenario::BullDcf,
        EstimateScenario::AnalystLow,
        EstimateScenario::AnalystHigh,
    ]
    .into_iter()
    .map(|s| compute_scenario(s, rows, dcf_by_symbol, total_cap, current_weighted))
    .collect();

    IndexEstimatesReport {
        profile_name: profile_name.to_string(),
        current_weighted_price_cents: current_weighted,
        total_symbols: eligible,
        scenarios,
        computed_at_epoch_seconds: now_epoch_seconds,
        dcf_coverage: compute_coverage(rows, dcf_by_symbol),
    }
}

fn is_live_complete(a: &DcfAnalysis) -> bool {
    a.bear_intrinsic_value_cents > 0
        && a.base_intrinsic_value_cents > 0
        && a.bull_intrinsic_value_cents > 0
}

fn compute_coverage(
    rows: &[CandidateRow],
    dcf_by_symbol: &std::collections::HashMap<String, DcfAnalysis>,
) -> DcfCoverageSummary {
    let eligible: Vec<_> = rows
        .iter()
        .filter(|r| r.market_cap_dollars.unwrap_or(0) > 0)
        .collect();
    let denom = eligible.len();
    let covered = eligible
        .iter()
        .filter(|r| dcf_by_symbol.get(&r.symbol).is_some_and(is_live_complete))
        .count();
    let coverage_bps = if denom == 0 || covered == 0 {
        0
    } else {
        (covered * 10_000 / denom) as i32
    };
    let status = match (denom, covered, coverage_bps) {
        (0, _, _) | (_, 0, _) => DcfCoverageStatus::Unavailable,
        (_, _, b) if b < 2_500 => DcfCoverageStatus::LowConfidence,
        (_, _, b) if b < 5_000 => DcfCoverageStatus::Partial,
        (_, _, b) if b < 9_500 => DcfCoverageStatus::Provisional,
        _ => DcfCoverageStatus::Ready,
    };
    DcfCoverageSummary {
        total_eligible_symbols: denom,
        covered_symbols: covered,
        coverage_bps,
        status,
    }
}

fn compute_scenario(
    scenario: EstimateScenario,
    rows: &[CandidateRow],
    dcf_by_symbol: &std::collections::HashMap<String, DcfAnalysis>,
    _total_cap: u64,
    current_weighted: i64,
) -> ScenarioEstimate {
    let mut numerator = 0.0;
    let mut denom_cap = 0u64;
    let mut coverage = 0usize;
    for row in rows {
        let Some(cap) = row.market_cap_dollars.filter(|&c| c > 0) else {
            continue;
        };
        let Some(fv) = scenario_fair_value(scenario, row, dcf_by_symbol) else {
            continue;
        };
        numerator += fv as f64 * cap as f64;
        denom_cap += cap;
        coverage += 1;
    }
    let weighted = if denom_cap > 0 {
        (numerator / denom_cap as f64) as i64
    } else {
        0
    };
    let upside = if current_weighted <= 0 || coverage == 0 {
        0
    } else {
        ((weighted as f64 / current_weighted as f64 - 1.0) * 10_000.0).round() as i32
    };
    ScenarioEstimate {
        scenario,
        weighted_price_cents: weighted,
        coverage_count: coverage,
        implied_upside_bps: upside,
    }
}

fn scenario_fair_value(
    scenario: EstimateScenario,
    row: &CandidateRow,
    dcf_by_symbol: &std::collections::HashMap<String, DcfAnalysis>,
) -> Option<i64> {
    match scenario {
        EstimateScenario::BearDcf => dcf_by_symbol
            .get(&row.symbol)
            .filter(|a| is_live_complete(a))
            .map(|a| a.bear_intrinsic_value_cents),
        EstimateScenario::BaseDcf => dcf_by_symbol
            .get(&row.symbol)
            .filter(|a| is_live_complete(a))
            .map(|a| a.base_intrinsic_value_cents),
        EstimateScenario::BullDcf => dcf_by_symbol
            .get(&row.symbol)
            .filter(|a| is_live_complete(a))
            .map(|a| a.bull_intrinsic_value_cents),
        EstimateScenario::AnalystLow => row.low_fair_value_cents,
        EstimateScenario::AnalystHigh => row.high_fair_value_cents,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::dcf_model::{DcfAnalysis, WaccFieldSource, WaccInputProvenance};
    use crate::engine::{ConfidenceBand, ExternalSignalStatus, QualificationStatus};

    fn row(sym: &str, price: i64, cap: u64, low: i64, high: i64) -> CandidateRow {
        CandidateRow {
            symbol: sym.into(),
            company_name: None,
            market_price_cents: price,
            previous_close_cents: price,
            next_earnings_epoch: None,
            intrinsic_value_cents: (low + high) / 2,
            gap_bps: 0,
            qualification: QualificationStatus::Qualified,
            confidence: ConfidenceBand::High,
            signal_status: ExternalSignalStatus::Supportive,
            analyst_opinion_count: Some(10),
            recommendation_mean_hundredths: Some(200),
            sector_name: None,
            low_fair_value_cents: Some(low),
            high_fair_value_cents: Some(high),
            strong_buy_count: None,
            buy_count: None,
            hold_count: None,
            sell_count: None,
            strong_sell_count: None,
            free_cash_flow_dollars: None,
            operating_cash_flow_dollars: None,
            market_cap_dollars: Some(cap),
            return_on_equity_bps: None,
            earnings_growth_bps: None,
            debt_to_equity_hundredths: None,
            total_cash_dollars: None,
            total_debt_dollars: None,
            forward_pe_hundredths: None,
            price_to_book_hundredths: None,
            enterprise_to_ebitda_hundredths: None,
            beta_millis: None,
            shares_outstanding: Some(1_000_000),
            dcf_value_cents: None,
            insider_net_shares_90d: None,
            insider_buy_count: None,
            insider_sell_count: None,
        }
    }

    fn dcf(bear: i64, base: i64, bull: i64) -> DcfAnalysis {
        DcfAnalysis {
            bear_intrinsic_value_cents: bear,
            base_intrinsic_value_cents: base,
            bull_intrinsic_value_cents: bull,
            wacc_bps: 900,
            base_growth_bps: 500,
            net_debt_dollars: 0,
            wacc_inputs: WaccInputProvenance {
                market_cap: WaccFieldSource::Reported,
                beta: WaccFieldSource::Reported,
                total_debt: WaccFieldSource::Reported,
                total_cash: WaccFieldSource::Reported,
                cost_of_debt: WaccFieldSource::Default,
                tax_rate: WaccFieldSource::Default,
                wacc_clamped: false,
            },
            source: "sec_edgar".into(),
        }
    }

    #[test]
    fn index_estimates_weights_by_market_cap() {
        let rows = vec![
            row("A", 10_000, 100, 12_000, 14_000),
            row("B", 20_000, 300, 18_000, 22_000),
        ];
        let mut map = std::collections::HashMap::new();
        map.insert("A".into(), dcf(11_000, 13_000, 15_000));
        map.insert("B".into(), dcf(19_000, 21_000, 23_000));
        let report = compute(&rows, &map, "universe", 0);
        assert_eq!(report.total_symbols, 2);
        assert!(report.scenarios.iter().any(|s| s.coverage_count == 2));
        assert_eq!(report.dcf_coverage.covered_symbols, 2);
    }
}
