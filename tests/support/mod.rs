use std::env;
use std::path::PathBuf;
use std::time::SystemTime;
use std::time::UNIX_EPOCH;

use discount_screener::ExternalValuationSignal;
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
pub fn temp_file_path(stem: &str) -> PathBuf {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("system time should be after epoch")
        .as_nanos();

    env::temp_dir().join(format!("discount_screener_{stem}_{nanos}.log"))
}
