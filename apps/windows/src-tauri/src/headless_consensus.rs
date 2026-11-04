//! Bounded, source-specific analyst observations. This does not select a model valuation.
use serde_json::{json, Value};
use std::collections::HashMap;
use std::sync::Mutex;
use std::time::{Duration, Instant};
const REQUEST_TTL: Duration = Duration::from_secs(90);
const MAX_ENTRIES: usize = 128;
#[derive(Default)]
pub(super) struct ConsensusCache {
    entries: Mutex<HashMap<String, (u64, Instant, Result<Value, String>)>>,
    inflight: Mutex<()>,
}
impl ConsensusCache {
    pub(super) fn read(
        &self,
        symbol: &str,
        generation: u64,
        network: bool,
        now: Instant,
        fetch: impl FnOnce() -> Result<Value, String>,
    ) -> Result<Value, String> {
        if !network {
            return Err("provider_disabled: --no-feed".into());
        }
        let _worker = self.inflight.try_lock().map_err(|_| "consensus_busy")?;
        {
            let mut entries = self.entries.lock().unwrap();
            entries.retain(|_, (g, at, _)| {
                *g == generation && now.saturating_duration_since(*at) < REQUEST_TTL
            });
            if let Some((_, _, value)) = entries.get(symbol) {
                return value.clone();
            }
            if entries.len() >= MAX_ENTRIES {
                return Err("consensus_capacity".into());
            }
        }
        let value = fetch();
        self.entries
            .lock()
            .unwrap()
            .insert(symbol.into(), (generation, now, value.clone()));
        value
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn requests_share_result_until_ninety_seconds() {
        let cache = ConsensusCache::default();
        let now = Instant::now();
        assert_eq!(
            cache
                .read("ADSK", 1, true, now, || Ok(json!({"target":30754})))
                .unwrap()["target"],
            30754
        );
        assert_eq!(
            cache
                .read("ADSK", 1, true, now + Duration::from_secs(89), || panic!(
                    "duplicate fetch"
                ))
                .unwrap()["target"],
            30754
        );
        assert_eq!(
            cache
                .read("ADSK", 1, true, now + REQUEST_TTL, || Ok(
                    json!({"target":31000})
                ))
                .unwrap()["target"],
            31000
        );
    }
    #[test]
    fn offline_never_fetches_even_with_cached_data() {
        let cache = ConsensusCache::default();
        let now = Instant::now();
        let _ = cache.read("ADSK", 1, true, now, || Ok(json!({})));
        assert!(cache
            .read("ADSK", 1, false, now, || panic!("offline network"))
            .unwrap_err()
            .contains("disabled"));
    }
    #[test]
    fn errors_are_deduplicated_and_generation_discards_cache() {
        let cache = ConsensusCache::default();
        let now = Instant::now();
        assert_eq!(
            cache
                .read("ADSK", 1, true, now, || Err("rate_limited".into()))
                .unwrap_err(),
            "rate_limited"
        );
        assert_eq!(
            cache
                .read("ADSK", 1, true, now, || panic!("retry flood"))
                .unwrap_err(),
            "rate_limited"
        );
        assert!(cache.read("ADSK", 2, true, now, || Ok(json!({}))).is_ok());
    }
    #[test]
    fn capacity_and_concurrency_are_bounded() {
        let cache = ConsensusCache::default();
        let now = Instant::now();
        for i in 0..MAX_ENTRIES {
            cache
                .read(&format!("S{i}"), 1, true, now, || Ok(json!({})))
                .unwrap();
        }
        assert!(cache
            .read("ADSK", 1, true, now, || panic!("over capacity"))
            .unwrap_err()
            .contains("capacity"));
        let other = ConsensusCache::default();
        let _guard = other.inflight.lock().unwrap();
        assert!(other
            .read("ADSK", 1, true, now, || panic!("concurrent"))
            .unwrap_err()
            .contains("busy"));
    }
}

pub(super) fn observation(result: crate::fetcher::FetchResult, received: i64) -> Value {
    let target = result
        .snapshot
        .as_ref()
        .map(|s| s.intrinsic_value_cents)
        .filter(|n| *n > 0);
    let price = result
        .snapshot
        .as_ref()
        .map(|s| s.market_price_cents)
        .filter(|n| *n > 0);
    let signal = result.signal.as_ref();
    json!({
        "symbol": result.symbol,
        "source": "yahoo_quote_summary",
        "received_at_epoch": received,
        "provider_time_epoch": null,
        "currency": null,
        "price_scale": "provider_unit_x100_no_fx",
        "freshness": "receipt_cache_only_not_market_freshness",
        "consensus_status": if target.is_some() { "observed" } else { "unavailable" },
        "market_price_cents": price,
        "analyst_target_mean_cents": target,
        "analyst_target_low_cents": signal.and_then(|s| s.low_fair_value_cents),
        "analyst_target_high_cents": signal.and_then(|s| s.high_fair_value_cents),
        "analyst_opinion_count": signal.and_then(|s| s.analyst_opinion_count),
        "analyst_count_basis": "provider_count_or_recommendation_tally",
        "recommendation_mean_hundredths": signal.and_then(|s| s.recommendation_mean_hundredths),
        "strong_buy_count": signal.and_then(|s| s.strong_buy_count),
        "buy_count": signal.and_then(|s| s.buy_count),
        "hold_count": signal.and_then(|s| s.hold_count),
        "sell_count": signal.and_then(|s| s.sell_count),
        "strong_sell_count": signal.and_then(|s| s.strong_sell_count),
        "valuation_status": "not_evaluated",
        "selected_model_value_cents": null
    })
}
#[cfg(test)]
mod observation_tests {
    #[test]
    fn source_observation_preserves_consensus_and_distinguishes_receipt_time() {
        let raw = serde_json::json!({"quoteSummary":{"result":[{"price":{"symbol":"ADSK","regularMarketPrice":{"raw":272.68}},"financialData":{"currentPrice":{"raw":272.68},"targetMeanPrice":{"raw":307.54},"targetMedianPrice":{"raw":302.50},"targetLowPrice":{"raw":215.0},"targetHighPrice":{"raw":375.0},"numberOfAnalystOpinions":{"raw":34},"recommendationMean":{"raw":1.56}}}]}});
        let result = crate::quote_summary::parse_quote_summary(&raw, "ADSK");
        let value = super::observation(result, 123);
        assert_eq!(value["analyst_target_mean_cents"], 30754);
        assert_eq!(value["analyst_opinion_count"], 34);
        assert_eq!(value["recommendation_mean_hundredths"], 156);
        assert_eq!(value["received_at_epoch"], 123);
        assert_eq!(value["provider_time_epoch"], serde_json::Value::Null);
        assert_eq!(value["selected_model_value_cents"], serde_json::Value::Null);
    }
    #[test]
    fn missing_target_is_not_zero_or_a_model_fallback() {
        let result = crate::quote_summary::parse_quote_summary(&serde_json::json!({}), "ADSK");
        let value = super::observation(result, 123);
        assert_eq!(value["analyst_target_mean_cents"], serde_json::Value::Null);
        assert_eq!(value["consensus_status"], "unavailable");
    }
}
