use std::sync::{atomic::Ordering, Arc, Mutex};

use serde::Serialize;

use crate::commands::{build_opportunity_rows, market_data_client_loader};
use crate::engine::{compute_chart_summary, HistoricalCandle, ScreenerState};
use crate::fetcher::FetchResult;
use crate::market_data_cache::{fetch_daily, unique_symbols, MarketDataCache};
use crate::regime::MarketRegime;
use crate::state::AppState;
use crate::tauri_compat::State;

const MAX_LOADS_PER_PASS: usize = 6;

#[derive(Serialize)]
pub struct PortfolioSignal {
    symbol: String,
    sector_name: Option<String>,
    asset_type: &'static str,
    setup_score: i32,
    setup_label: &'static str,
    regime_score: Option<i32>,
}

#[derive(Serialize)]
pub struct PortfolioAnalysis {
    rows: Vec<PortfolioSignal>,
    pending: bool,
}

fn needs_market_data(cache: &MarketDataCache, symbol: &str, can_fetch_quote: bool) -> bool {
    (can_fetch_quote && cache.needs_quote(symbol))
        || (cache.quote(symbol).is_some() && cache.needs_daily(symbol))
}

fn load_market_data(
    cache: &MarketDataCache,
    symbols: &[String],
    can_fetch_quote: impl Fn() -> bool,
    mut quote: impl FnMut(&str) -> Option<FetchResult>,
    mut daily: impl FnMut(&str) -> Vec<HistoricalCandle>,
) -> bool {
    if let Ok(_loading) = cache.analysis_load.try_lock() {
        let missing = symbols
            .iter()
            .filter(|s| needs_market_data(cache, s, can_fetch_quote()));
        for symbol in missing.take(MAX_LOADS_PER_PASS) {
            if can_fetch_quote() {
                cache.demand_quote(symbol, || quote(symbol));
            }
            if cache.quote(symbol).is_some() {
                cache.demand_daily(symbol, || daily(symbol));
            }
        }
    }
    symbols
        .iter()
        .any(|s| needs_market_data(cache, s, can_fetch_quote()))
}

fn project_book(
    cache: &MarketDataCache,
    symbols: &[String],
    model: String,
    apply_regime: bool,
    regime: Option<MarketRegime>,
) -> Vec<PortfolioSignal> {
    let mut book = ScreenerState::new();
    book.scoring_model = model;
    for symbol in symbols {
        let Some(quote) = cache.quote(symbol) else {
            continue;
        };
        let Some(daily) = cache.daily(symbol) else {
            continue;
        };
        let Some(summary) = compute_chart_summary(&daily) else {
            continue;
        };
        let Some(mut snapshot) = quote.snapshot else {
            continue;
        };
        let Some(price_cents) = cache.price(symbol) else {
            continue;
        };
        snapshot.market_price_cents = price_cents;
        book.snapshots.insert(symbol.clone(), snapshot);
        if let Some(signal) = quote.signal {
            book.ingest_signal(signal);
        }
        if let Some(fundamentals) = quote.fundamentals {
            book.ingest_fundamentals(fundamentals);
        }
        book.ingest_chart_summary(symbol.clone(), summary);
        book.ingest_daily_candles(symbol.clone(), daily.as_ref().clone());
    }

    build_opportunity_rows(&Mutex::new(book), apply_regime, regime)
        .into_iter()
        .filter(|row| row.technical_score.is_some())
        .map(|row| PortfolioSignal {
            symbol: row.row.symbol,
            sector_name: row.row.sector_name,
            asset_type: row.asset_type,
            setup_score: row.setup_score,
            setup_label: row.setup_label,
            regime_score: row.regime_score,
        })
        .collect()
}

#[cfg_attr(feature = "gui", tauri::command)]
pub async fn get_portfolio_analysis(
    symbols: Vec<String>,
    state: State<'_, AppState>,
) -> Result<PortfolioAnalysis, String> {
    let symbols = unique_symbols(symbols);
    let cache = Arc::clone(&state.market_data);
    let load_client = market_data_client_loader(&state);
    let model = state.screener.lock().unwrap().scoring_model.clone();
    let apply_regime = state.apply_regime_scoring.load(Ordering::Relaxed);
    let regime = state.regime_cache.get();
    crate::tauri_compat::async_runtime::spawn_blocking(move || {
        let client = load_client().map_err(|error| error.to_string())?;
        let pending = load_market_data(
            &cache,
            &symbols,
            || !client.is_rate_limited(),
            |symbol| client.fetch_symbol_quote(symbol).ok(),
            |symbol| fetch_daily(&client, symbol),
        );
        let rows = project_book(&cache, &symbols, model, apply_regime, regime);
        Ok(PortfolioAnalysis { rows, pending })
    })
    .await
    .map_err(|error| error.to_string())?
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::commands::opportunity_projection_tests::fixture_state;

    #[test]
    fn portfolio_loads_off_feed_quotes_and_reuses_candles_for_risk() {
        let fixture = fixture_state();
        let fixture = fixture.lock().unwrap();
        let quote = FetchResult {
            symbol: "AAPL".into(),
            snapshot: fixture.snapshots.get("AAPL").cloned(),
            signal: fixture.signals.get("AAPL").cloned(),
            fundamentals: fixture.fundamentals.get("AAPL").cloned(),
        };
        let candles = &fixture.daily_candles["AAPL"];
        let cache = MarketDataCache::default();
        let held = vec!["AAPL".into()];
        assert!(!load_market_data(
            &cache,
            &held,
            || true,
            |symbol| {
                assert_eq!(symbol, "AAPL");
                Some(quote.clone())
            },
            |symbol| {
                assert_eq!(symbol, "AAPL");
                candles.clone()
            }
        ));
        let signals = project_book(&cache, &held, "aggressive_v3".into(), false, None);
        assert_eq!(signals.len(), 1);
        assert_eq!(signals[0].symbol, "AAPL");
        assert!(cache
            .demand_daily("AAPL", || panic!("risk did not reuse daily candles"))
            .is_some());
        assert_eq!(
            cache.demand_prices(held, |_| panic!("price demand ignored quote"))["AAPL"],
            20_000
        );
    }

    #[test]
    fn price_without_analysis_evidence_does_not_produce_a_signal() {
        let cache = MarketDataCache::default();
        let symbols = vec!["AAPL".into()];
        cache.demand_prices(symbols.clone(), |_| [("AAPL".into(), 20_000)].into());
        assert!(!load_market_data(
            &cache,
            &symbols,
            || true,
            |_| None,
            |_| panic!("no quote")
        ));
        assert!(project_book(&cache, &symbols, "aggressive_v3".into(), false, None).is_empty());
    }

    #[test]
    fn load_pass_limits_new_symbols_and_leaves_the_rest_pending() {
        let cache = MarketDataCache::default();
        let symbols: Vec<_> = (0..MAX_LOADS_PER_PASS + 1)
            .map(|i| format!("TEST{i}"))
            .collect();
        let mut calls = Vec::new();
        assert!(load_market_data(
            &cache,
            &symbols,
            || true,
            |symbol| {
                calls.push(symbol.to_string());
                None
            },
            |_| panic!("no quote")
        ));
        assert_eq!(calls, &symbols[..MAX_LOADS_PER_PASS]);
        assert!(!load_market_data(
            &cache,
            &symbols,
            || true,
            |symbol| {
                assert_eq!(symbol, symbols.last().unwrap());
                None
            },
            |_| panic!("no quote")
        ));
    }
}
