//! Transparent multi-scenario DCF with WACC provenance (Android DcfAnalysisEngine port).
//! Uses annual FCF history (e.g. from EDGAR) + live fundamentals for CAPM WACC.

use serde::{Deserialize, Serialize};

use crate::engine::FundamentalSnapshot;

const RISK_FREE_RATE_BPS: i32 = 400;
const EQUITY_RISK_PREMIUM_BPS: i32 = 500;
const DEFAULT_TAX_RATE_BPS: i32 = 2_100;
const DEFAULT_COST_OF_DEBT_BPS: i32 = 550;
const MIN_WACC_BPS: i32 = 500;
const MAX_WACC_BPS: i32 = 1_800;
const DCF_PROJECTION_YEARS: i32 = 5;
const BASE_GROWTH_MIN_BPS: i32 = -1_000;
const BASE_GROWTH_MAX_BPS: i32 = 1_800;
const SCENARIO_GROWTH_SPREAD_BPS: i32 = 400;
const BEAR_GROWTH_MIN_BPS: i32 = -1_200;
const BEAR_GROWTH_MAX_BPS: i32 = 1_400;
const BULL_GROWTH_MIN_BPS: i32 = -400;
const BULL_GROWTH_MAX_BPS: i32 = 2_400;
const BEAR_TERMINAL_GROWTH_BPS: i32 = 200;
const BASE_TERMINAL_GROWTH_BPS: i32 = 250;
const BULL_TERMINAL_GROWTH_BPS: i32 = 300;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum WaccFieldSource {
    Reported,
    Default,
    DerivedPriceTimesShares,
    AssumedZero,
    InterestOverDebt,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct WaccInputProvenance {
    pub market_cap: WaccFieldSource,
    pub beta: WaccFieldSource,
    pub total_debt: WaccFieldSource,
    pub total_cash: WaccFieldSource,
    pub cost_of_debt: WaccFieldSource,
    pub tax_rate: WaccFieldSource,
    pub wacc_clamped: bool,
}

impl WaccInputProvenance {
    /// Human-readable WACC input caveats (used by tests and future UI badges).
    #[allow(dead_code)]
    pub fn summary_labels(&self) -> Vec<String> {
        let mut labels = Vec::new();
        if self.market_cap == WaccFieldSource::DerivedPriceTimesShares {
            labels.push("market cap=price×shares".into());
        }
        if self.beta == WaccFieldSource::Default {
            labels.push("beta=default".into());
        }
        if self.total_debt == WaccFieldSource::AssumedZero {
            labels.push("debt=assumed 0".into());
        }
        if self.total_cash == WaccFieldSource::AssumedZero {
            labels.push("cash=assumed 0".into());
        }
        if self.cost_of_debt == WaccFieldSource::Default {
            labels.push("cost of debt=default".into());
        }
        if self.tax_rate == WaccFieldSource::Default {
            labels.push("tax=default".into());
        }
        if self.wacc_clamped {
            labels.push("wacc=clamped".into());
        }
        labels
    }

    /// True when any WACC input used a default/derived/assumed value.
    #[allow(dead_code)]
    pub fn is_provisional(&self) -> bool {
        !self.summary_labels().is_empty()
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DcfAnalysis {
    pub bear_intrinsic_value_cents: i64,
    pub base_intrinsic_value_cents: i64,
    pub bull_intrinsic_value_cents: i64,
    pub wacc_bps: i32,
    pub base_growth_bps: i32,
    pub net_debt_dollars: i64,
    pub wacc_inputs: WaccInputProvenance,
    /// "sec_edgar" | "yahoo" | "unknown"
    pub source: String,
}

/// Annual FCF point (dollars).
#[derive(Debug, Clone)]
pub struct FcfPoint {
    pub year: i32,
    pub value_dollars: f64,
}

/// Compute bear/base/bull DCF with transparent WACC.
pub fn compute(
    fundamentals: &FundamentalSnapshot,
    fcf_history: &[FcfPoint],
    market_price_cents: Option<i64>,
    source: &str,
) -> Result<DcfAnalysis, String> {
    if fcf_history.len() < 3 {
        return Err("need at least 3 annual free cash flow points".into());
    }
    let latest = fcf_history
        .last()
        .and_then(|p| (p.value_dollars > 0.0).then_some(p.value_dollars))
        .ok_or_else(|| "latest annual free cash flow is not positive".to_string())?;
    let shares = fundamentals
        .shares_outstanding
        .filter(|&s| s > 0)
        .map(|s| s as f64)
        .ok_or_else(|| "share count is missing".to_string())?;

    let fcf_ps: Vec<(i32, f64)> = fcf_history
        .iter()
        .filter_map(|p| {
            if shares <= 0.0 {
                None
            } else {
                Some((p.year, p.value_dollars / shares))
            }
        })
        .collect();
    let raw_base_growth = derive_base_growth_bps(&fcf_ps)
        .ok_or_else(|| "insufficient positive free cash flow per share history".to_string())?;
    let resolved = derive_wacc(fundamentals, market_price_cents)?;
    let net_debt =
        fundamentals.total_debt_dollars.unwrap_or(0) - fundamentals.total_cash_dollars.unwrap_or(0);

    let bear_g = (raw_base_growth - SCENARIO_GROWTH_SPREAD_BPS)
        .clamp(BEAR_GROWTH_MIN_BPS, BEAR_GROWTH_MAX_BPS);
    let base_g = raw_base_growth.clamp(BASE_GROWTH_MIN_BPS, BASE_GROWTH_MAX_BPS);
    let bull_g =
        (base_g + SCENARIO_GROWTH_SPREAD_BPS).clamp(BULL_GROWTH_MIN_BPS, BULL_GROWTH_MAX_BPS);

    let bear = discounted_per_share(
        latest,
        shares,
        net_debt,
        bear_g,
        clamp_terminal(BEAR_TERMINAL_GROWTH_BPS, resolved.wacc_bps),
        resolved.wacc_bps,
    )
    .ok_or_else(|| "bear scenario invalid".to_string())?;
    let base = discounted_per_share(
        latest,
        shares,
        net_debt,
        base_g,
        clamp_terminal(BASE_TERMINAL_GROWTH_BPS, resolved.wacc_bps),
        resolved.wacc_bps,
    )
    .ok_or_else(|| "base scenario invalid".to_string())?;
    let bull = discounted_per_share(
        latest,
        shares,
        net_debt,
        bull_g,
        clamp_terminal(BULL_TERMINAL_GROWTH_BPS, resolved.wacc_bps),
        resolved.wacc_bps,
    )
    .ok_or_else(|| "bull scenario invalid".to_string())?;

    Ok(DcfAnalysis {
        bear_intrinsic_value_cents: bear,
        base_intrinsic_value_cents: base,
        bull_intrinsic_value_cents: bull,
        wacc_bps: resolved.wacc_bps,
        base_growth_bps: base_g,
        net_debt_dollars: net_debt,
        wacc_inputs: resolved.inputs,
        source: source.to_string(),
    })
}

struct ResolvedWacc {
    wacc_bps: i32,
    inputs: WaccInputProvenance,
}

fn derive_base_growth_bps(points: &[(i32, f64)]) -> Option<i32> {
    let latest_i = points.iter().rposition(|(_, v)| *v > 0.0)?;
    let first_i = points.iter().position(|(_, v)| *v > 0.0)?;
    let latest = points[latest_i];
    let first = points[first_i];
    let years = (latest.0 - first.0).max(1) as f64;
    if first.1 <= 0.0 {
        return None;
    }
    let cagr = (latest.1 / first.1).powf(1.0 / years) - 1.0;
    if !cagr.is_finite() {
        return None;
    }
    Some((cagr * 10_000.0).round() as i32)
}

fn resolve_market_cap(
    fundamentals: &FundamentalSnapshot,
    market_price_cents: Option<i64>,
) -> Option<(f64, WaccFieldSource)> {
    if let Some(cap) = fundamentals.market_cap_dollars.filter(|&c| c > 0) {
        return Some((cap as f64, WaccFieldSource::Reported));
    }
    let shares = fundamentals.shares_outstanding.filter(|&s| s > 0)? as f64;
    let price = market_price_cents.filter(|&p| p > 0)? as f64 / 100.0;
    let derived = price * shares;
    if !derived.is_finite() || derived <= 0.0 {
        return None;
    }
    Some((derived, WaccFieldSource::DerivedPriceTimesShares))
}

fn derive_wacc(
    fundamentals: &FundamentalSnapshot,
    market_price_cents: Option<i64>,
) -> Result<ResolvedWacc, String> {
    let (market_cap, market_cap_source) = resolve_market_cap(fundamentals, market_price_cents)
        .ok_or_else(|| "market cap is missing".to_string())?;
    let beta_source = if fundamentals.beta_millis.is_some() {
        WaccFieldSource::Reported
    } else {
        WaccFieldSource::Default
    };
    let beta = fundamentals.beta_millis.unwrap_or(1_000) as f64 / 1_000.0;
    let cost_of_equity_bps =
        RISK_FREE_RATE_BPS + (beta * EQUITY_RISK_PREMIUM_BPS as f64).round() as i32;

    let total_debt_source = if fundamentals.total_debt_dollars.is_some() {
        WaccFieldSource::Reported
    } else {
        WaccFieldSource::AssumedZero
    };
    let total_cash_source = if fundamentals.total_cash_dollars.is_some() {
        WaccFieldSource::Reported
    } else {
        WaccFieldSource::AssumedZero
    };
    let total_debt = fundamentals.total_debt_dollars.unwrap_or(0).max(0) as f64;
    let total_cash = fundamentals.total_cash_dollars.unwrap_or(0).max(0) as f64;
    let net_debt = (total_debt - total_cash).max(0.0);
    let base = market_cap + net_debt;
    let equity_w = if base > 0.0 { market_cap / base } else { 1.0 };
    let debt_w = if base > 0.0 { net_debt / base } else { 0.0 };

    let (cost_of_debt_bps, cost_of_debt_source) = if total_debt > 0.0 {
        // No interest timeseries on Windows yet → default.
        (DEFAULT_COST_OF_DEBT_BPS, WaccFieldSource::Default)
    } else {
        (DEFAULT_COST_OF_DEBT_BPS, WaccFieldSource::Reported)
    };

    let tax_rate_bps = DEFAULT_TAX_RATE_BPS.clamp(0, 3_500);
    let tax_rate_source = WaccFieldSource::Default;
    let after_tax_debt =
        (cost_of_debt_bps as f64 * (1.0 - tax_rate_bps as f64 / 10_000.0)).round() as i32;
    let weighted = (equity_w * cost_of_equity_bps as f64) + (debt_w * after_tax_debt as f64);
    let unclamped = weighted.round() as i32;
    let wacc_bps = unclamped.clamp(MIN_WACC_BPS, MAX_WACC_BPS);

    Ok(ResolvedWacc {
        wacc_bps,
        inputs: WaccInputProvenance {
            market_cap: market_cap_source,
            beta: beta_source,
            total_debt: total_debt_source,
            total_cash: total_cash_source,
            cost_of_debt: cost_of_debt_source,
            tax_rate: tax_rate_source,
            wacc_clamped: unclamped != wacc_bps,
        },
    })
}

fn clamp_terminal(terminal_bps: i32, wacc_bps: i32) -> i32 {
    terminal_bps.min(wacc_bps - 50).max(50)
}

fn discounted_per_share(
    latest_fcf: f64,
    shares: f64,
    net_debt: i64,
    growth_bps: i32,
    terminal_growth_bps: i32,
    wacc_bps: i32,
) -> Option<i64> {
    if latest_fcf <= 0.0 || shares <= 0.0 || terminal_growth_bps >= wacc_bps {
        return None;
    }
    let growth = growth_bps as f64 / 10_000.0;
    let terminal_growth = terminal_growth_bps as f64 / 10_000.0;
    let wacc = wacc_bps as f64 / 10_000.0;
    let mut projected = latest_fcf;
    let mut pv = 0.0;
    for year in 1..=DCF_PROJECTION_YEARS {
        projected *= 1.0 + growth;
        pv += projected / (1.0 + wacc).powi(year);
    }
    let terminal_cf = projected * (1.0 + terminal_growth);
    let terminal_value = terminal_cf / (wacc - terminal_growth);
    let enterprise = pv + terminal_value / (1.0 + wacc).powi(DCF_PROJECTION_YEARS);
    let equity = enterprise - net_debt as f64;
    if !equity.is_finite() || equity <= 0.0 {
        return None;
    }
    Some(((equity / shares) * 100.0).round() as i64)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample_fund() -> FundamentalSnapshot {
        FundamentalSnapshot {
            symbol: "TEST".into(),
            market_cap_dollars: Some(1_000_000_000),
            shares_outstanding: Some(100_000_000),
            beta_millis: Some(1_100),
            total_debt_dollars: Some(100_000_000),
            total_cash_dollars: Some(50_000_000),
            ..Default::default()
        }
    }

    #[test]
    fn compute_produces_ordered_scenarios() {
        let fcf = vec![
            FcfPoint {
                year: 2021,
                value_dollars: 80_000_000.0,
            },
            FcfPoint {
                year: 2022,
                value_dollars: 90_000_000.0,
            },
            FcfPoint {
                year: 2023,
                value_dollars: 100_000_000.0,
            },
            FcfPoint {
                year: 2024,
                value_dollars: 110_000_000.0,
            },
        ];
        let a = compute(&sample_fund(), &fcf, Some(1_000), "sec_edgar").expect("dcf");
        assert!(a.bear_intrinsic_value_cents > 0);
        assert!(a.base_intrinsic_value_cents >= a.bear_intrinsic_value_cents);
        assert!(a.bull_intrinsic_value_cents >= a.base_intrinsic_value_cents);
        assert!(a.wacc_bps >= MIN_WACC_BPS && a.wacc_bps <= MAX_WACC_BPS);
    }

    #[test]
    fn provisional_when_beta_default() {
        let mut f = sample_fund();
        f.beta_millis = None;
        let fcf = vec![
            FcfPoint {
                year: 2022,
                value_dollars: 90_000_000.0,
            },
            FcfPoint {
                year: 2023,
                value_dollars: 100_000_000.0,
            },
            FcfPoint {
                year: 2024,
                value_dollars: 110_000_000.0,
            },
        ];
        let a = compute(&f, &fcf, Some(1_000), "sec_edgar").unwrap();
        assert!(a.wacc_inputs.is_provisional());
        assert!(a
            .wacc_inputs
            .summary_labels()
            .iter()
            .any(|l| l.contains("beta")));
    }
}
