use std::env;
use std::path::PathBuf;
use std::time::SystemTime;
use std::time::UNIX_EPOCH;

use discount_screener::ExternalValuationSignal;
use discount_screener::FundamentalSnapshot;
use discount_screener::MarketSnapshot;

pub fn market_snapshot(
    symbol: &str,
    profitable: bool,
    market_price_cents: i64,
    intrinsic_value_cents: i64,
) -> MarketSnapshot {
    MarketSnapshot {
        symbol: symbol.to_string(),
        company_name: None,
        profitable,
        market_price_cents,
        intrinsic_value_cents,
    }
}

pub fn external_signal(
    symbol: &str,
    fair_value_cents: i64,
    age_seconds: u64,
) -> ExternalValuationSignal {
    ExternalValuationSignal {
        symbol: symbol.to_string(),
        fair_value_cents,
        age_seconds,
        low_fair_value_cents: None,
        high_fair_value_cents: None,
        analyst_opinion_count: None,
        recommendation_mean_hundredths: None,
        strong_buy_count: None,
        buy_count: None,
        hold_count: None,
        sell_count: None,
        strong_sell_count: None,
        weighted_fair_value_cents: None,
        weighted_analyst_count: None,
    }
}

#[allow(dead_code)]
pub fn fundamental_snapshot(symbol: &str) -> FundamentalSnapshot {
    FundamentalSnapshot {
        symbol: symbol.to_string(),
        sector_key: Some("technology".to_string()),
        sector_name: Some("Technology".to_string()),
        industry_key: Some("software".to_string()),
        industry_name: Some("Software".to_string()),
        market_cap_dollars: Some(500_000_000_000),
        shares_outstanding: Some(10_000_000_000),
        trailing_pe_hundredths: Some(2_500),
        forward_pe_hundredths: Some(2_000),
        price_to_book_hundredths: Some(500),
        return_on_equity_bps: Some(2_200),
        ebitda_dollars: Some(80_000_000_000),
        enterprise_value_dollars: Some(520_000_000_000),
        enterprise_to_ebitda_hundredths: Some(650),
        total_debt_dollars: Some(60_000_000_000),
        total_cash_dollars: Some(20_000_000_000),
        debt_to_equity_hundredths: Some(80_00),
        free_cash_flow_dollars: Some(25_000_000_000),
        operating_cash_flow_dollars: Some(32_000_000_000),
        beta_millis: Some(1_100),
        trailing_eps_cents: Some(800),
        earnings_growth_bps: Some(1_500),
    }
}

#[allow(dead_code)]
pub fn temp_file_path(stem: &str) -> PathBuf {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("system time should be after epoch")
        .as_nanos();

    env::temp_dir().join(format!("discount_screener_{stem}_{nanos}.log"))
}
