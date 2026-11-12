use std::collections::{HashMap, HashSet};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use crate::engine::HistoricalCandle;
use crate::fetcher::{is_crypto, is_enrichment_complete, is_etf, FetchResult, YahooClient};

const MAX_SYMBOLS: usize = 4_096;
const PRICE_TTL: Duration = Duration::from_secs(20 * 60);
const EVIDENCE_TTL: Duration = Duration::from_secs(30 * 60);
const RETRY_DELAY: Duration = Duration::from_secs(60);

struct Entry<T> {
    started: Instant,
    expires: Instant,
    value: Option<T>,
}

struct Cache<T>(HashMap<String, Entry<T>>);

impl<T: Clone> Cache<T> {
    fn recent(&self, symbol: &str, now: Instant) -> Option<&Entry<T>> {
        self.0.get(symbol).filter(|entry| now < entry.expires)
    }

    fn get(&self, symbol: &str, now: Instant) -> Option<T> {
        self.recent(symbol, now)?.value.clone()
    }

    fn store(&mut self, symbol: &str, started: Instant, value: Option<T>, ttl: Duration) {
        let now = Instant::now();
        if self.0.get(symbol).is_some_and(|entry| {
            entry.started > started && (entry.value.is_some() || value.is_none())
        }) || (value.is_none() && self.get(symbol, now).is_some())
        {
            return;
        }
        if self.0.len() >= MAX_SYMBOLS && !self.0.contains_key(symbol) {
            self.0.retain(|_, entry| now < entry.expires);
            if self.0.len() >= MAX_SYMBOLS {
                if let Some(oldest) = self.0.iter().min_by_key(|(_, entry)| entry.expires) {
                    let symbol = oldest.0.clone();
                    self.0.remove(&symbol);
                }
            }
        }
        let expires = now + if value.is_some() { ttl } else { RETRY_DELAY };
        self.0.insert(
            symbol.into(),
            Entry {
                started,
                expires,
                value,
            },
        );
    }
}

impl<T> Default for Cache<T> {
    fn default() -> Self {
        Self(HashMap::new())
    }
}

type Daily = Arc<Vec<HistoricalCandle>>;

/// Session evidence by symbol. Feed generations control the feed projection,
/// while the cache also accepts demand from screens with their own symbol sets.
#[derive(Default)]
pub(crate) struct MarketDataCache {
    prices: Mutex<Cache<i64>>,
    quotes: Mutex<Cache<FetchResult>>,
    daily: Mutex<Cache<Daily>>,
    price_load: Mutex<()>,
    daily_load: Mutex<()>,
    pub(crate) analysis_load: Mutex<()>,
}

pub(crate) fn unique_symbols(symbols: Vec<String>) -> Vec<String> {
    let mut seen = HashSet::new();
    symbols
        .into_iter()
        .map(|s| s.trim().to_ascii_uppercase())
        .filter(|s| !s.is_empty() && seen.insert(s.clone()))
        .collect()
}

pub(crate) fn fetch_daily(client: &YahooClient, symbol: &str) -> Vec<HistoricalCandle> {
    let candles = client.fetch_candles(symbol, "1y", "1d").unwrap_or_default();
    std::thread::sleep(Duration::from_millis(150));
    candles
}

impl MarketDataCache {
    pub(crate) fn publish_quote(&self, started: Instant, quote: FetchResult) {
        let symbol = quote.symbol.clone();
        if let Some(snap) = quote
            .snapshot
            .as_ref()
            .filter(|snap| snap.symbol == symbol && snap.market_price_cents > 0)
        {
            self.prices.lock().unwrap().store(
                &symbol,
                started,
                Some(snap.market_price_cents),
                PRICE_TTL,
            );
            if is_enrichment_complete(&quote, is_crypto(&symbol), is_etf(&symbol)) {
                self.quotes
                    .lock()
                    .unwrap()
                    .store(&symbol, started, Some(quote), EVIDENCE_TTL);
            }
        }
    }

    pub(crate) fn publish_daily(
        &self,
        symbol: &str,
        started: Instant,
        candles: &[HistoricalCandle],
    ) {
        if !candles.is_empty() {
            self.daily.lock().unwrap().store(
                symbol,
                started,
                Some(Arc::new(candles.to_vec())),
                EVIDENCE_TTL,
            );
        }
    }

    pub(crate) fn quote(&self, symbol: &str) -> Option<FetchResult> {
        self.quotes.lock().unwrap().get(symbol, Instant::now())
    }

    pub(crate) fn price(&self, symbol: &str) -> Option<i64> {
        self.prices.lock().unwrap().get(symbol, Instant::now())
    }

    pub(crate) fn daily(&self, symbol: &str) -> Option<Daily> {
        self.daily.lock().unwrap().get(symbol, Instant::now())
    }

    pub(crate) fn needs_quote(&self, symbol: &str) -> bool {
        self.quotes
            .lock()
            .unwrap()
            .recent(symbol, Instant::now())
            .is_none()
    }

    pub(crate) fn needs_daily(&self, symbol: &str) -> bool {
        self.daily
            .lock()
            .unwrap()
            .recent(symbol, Instant::now())
            .is_none()
    }

    pub(crate) fn demand_quote(&self, symbol: &str, fetch: impl FnOnce() -> Option<FetchResult>) {
        if self.needs_quote(symbol) {
            let started = Instant::now();
            if let Some(quote) = fetch() {
                self.publish_quote(started, quote);
            }
            let mut quotes = self.quotes.lock().unwrap();
            if quotes.get(symbol, Instant::now()).is_none() {
                quotes.store(symbol, started, None, EVIDENCE_TTL);
            }
        }
    }

    pub(crate) fn demand_daily(
        &self,
        symbol: &str,
        fetch: impl FnOnce() -> Vec<HistoricalCandle>,
    ) -> Option<Daily> {
        if let Some(recent) = self.daily.lock().unwrap().recent(symbol, Instant::now()) {
            return recent.value.clone();
        }
        let _loading = self.daily_load.lock().unwrap();
        if self.needs_daily(symbol) {
            let started = Instant::now();
            let candles = fetch();
            let value = (!candles.is_empty()).then(|| Arc::new(candles));
            self.daily
                .lock()
                .unwrap()
                .store(symbol, started, value, EVIDENCE_TTL);
        }
        self.daily(symbol)
    }

    pub(crate) fn demand_prices(
        &self,
        symbols: Vec<String>,
        fetch: impl FnOnce(Vec<String>) -> HashMap<String, i64>,
    ) -> HashMap<String, i64> {
        let _loading = self.price_load.lock().unwrap();
        let started = Instant::now();
        let missing: Vec<_> = {
            let cache = self.prices.lock().unwrap();
            symbols
                .iter()
                .filter(|s| cache.recent(s, started).is_none())
                .cloned()
                .collect()
        };
        if !missing.is_empty() {
            let fetched = fetch(missing.clone());
            let mut cache = self.prices.lock().unwrap();
            for symbol in missing {
                let value = fetched.get(&symbol).copied().filter(|price| *price > 0);
                cache.store(&symbol, started, value, PRICE_TTL);
            }
        }
        let cache = self.prices.lock().unwrap();
        let now = Instant::now();
        symbols
            .into_iter()
            .filter_map(|s| cache.get(&s, now).map(|p| (s, p)))
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn missing_price_retries_after_cooldown_and_success_is_reused() {
        let cache = MarketDataCache::default();
        let symbols = unique_symbols(vec![" tsm ".into(), "TSM".into(), "".into()]);
        let missing = cache.demand_prices(symbols.clone(), |symbols| {
            assert_eq!(symbols, ["TSM"]);
            HashMap::from([("TSM".into(), 0)])
        });
        assert!(missing.is_empty());
        assert!(cache
            .demand_prices(symbols.clone(), |_| panic!("retried before cooldown"))
            .is_empty());
        cache
            .prices
            .lock()
            .unwrap()
            .0
            .get_mut("TSM")
            .unwrap()
            .expires = Instant::now();
        assert_eq!(
            cache.demand_prices(symbols.clone(), |_| HashMap::from([("TSM".into(), 15_000)]))
                ["TSM"],
            15_000
        );
        assert_eq!(
            cache.demand_prices(symbols, |_| panic!("ignored cached demand price"))["TSM"],
            15_000
        );
    }

    #[test]
    fn slow_demand_cannot_replace_newer_feed_price() {
        let cache = MarketDataCache::default();
        let prices = cache.demand_prices(vec!["TSM".into()], |_| {
            let quote = FetchResult {
                symbol: "TSM".into(),
                snapshot: Some(crate::engine::MarketSnapshot {
                    symbol: "TSM".into(),
                    company_name: None,
                    profitable: true,
                    market_price_cents: 15_000,
                    intrinsic_value_cents: 0,
                    previous_close_cents: 0,
                    next_earnings_epoch: None,
                }),
                signal: None,
                fundamentals: None,
            };
            cache.publish_quote(Instant::now() + Duration::from_millis(1), quote);
            HashMap::from([("TSM".into(), 14_000)])
        });
        assert_eq!(prices["TSM"], 15_000);
    }
}
