//! On-demand Android Long V3 chart evidence for the Markets ranking.

use std::collections::{HashMap, HashSet};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use crate::android_chart::{build_summary, AndroidChartSummary};
use crate::commands::create_feed_yahoo_client;
use crate::engine::HistoricalCandle;
use crate::state::AppState;
use crate::yahoo_session::is_rate_limit_error;

const BATCH_SIZE: usize = 4;
const REQUEST_PAUSE: Duration = Duration::from_millis(120);
const REFRESH_AFTER: Duration = Duration::from_secs(30 * 60);
const FAILURE_RETRY: Duration = Duration::from_secs(5 * 60);
const RATE_LIMIT_RETRY: Duration = Duration::from_secs(90);

struct YearEntry {
    summary: Option<AndroidChartSummary>,
    next_attempt: Instant,
}

#[derive(Default)]
pub(crate) struct YearCharts {
    generation: u64,
    entries: HashMap<String, YearEntry>,
    in_flight: bool,
    cooldown_until: Option<Instant>,
}

impl YearCharts {
    fn summaries(&self, generation: u64, now: Instant) -> HashMap<String, AndroidChartSummary> {
        if generation != self.generation {
            return HashMap::new();
        }
        self.entries
            .iter()
            .filter(|(_, entry)| now < entry.next_attempt)
            .filter_map(|(symbol, entry)| Some((symbol.clone(), entry.summary.clone()?)))
            .collect()
    }

    fn next_symbols(&self, candidates: &[String], now: Instant) -> Vec<String> {
        let unattempted = candidates
            .iter()
            .filter(|symbol| !self.entries.contains_key(*symbol));
        let due = candidates.iter().filter(|symbol| {
            self.entries
                .get(*symbol)
                .is_some_and(|entry| now >= entry.next_attempt)
        });
        let mut seen = HashSet::new();
        unattempted
            .chain(due)
            .filter(|symbol| seen.insert(symbol.as_str()))
            .take(BATCH_SIZE)
            .cloned()
            .collect()
    }
}

enum FetchOutcome {
    Candles(Vec<HistoricalCandle>),
    Unavailable,
    RateLimited,
    Deferred,
}

/// The lease stays held until an in-flight fetch from an older generation returns.
struct YearBatch {
    cache: Arc<Mutex<YearCharts>>,
    active: Arc<AtomicU64>,
    generation: u64,
    symbols: Vec<String>,
}

impl YearBatch {
    fn claim(
        cache: &Arc<Mutex<YearCharts>>,
        active: &Arc<AtomicU64>,
        generation: u64,
        candidates: &[String],
        now: Instant,
    ) -> Option<Self> {
        let mut year = cache.lock().unwrap();
        if active.load(Ordering::SeqCst) != generation
            || year.in_flight
            || year.cooldown_until.is_some_and(|until| now < until)
        {
            return None;
        }
        if year.generation != generation {
            year.generation = generation;
            year.entries.clear();
        }
        let symbols = year.next_symbols(candidates, now);
        if symbols.is_empty() {
            return None;
        }
        year.in_flight = true;
        Some(Self {
            cache: Arc::clone(cache),
            active: Arc::clone(active),
            generation,
            symbols,
        })
    }

    fn defer(&self, delay: Duration) {
        self.cache.lock().unwrap().cooldown_until = Some(Instant::now() + delay);
    }

    fn run(self, pace: Duration, mut fetch: impl FnMut(&str, &str, &str) -> FetchOutcome) {
        for (index, symbol) in self.symbols.iter().enumerate() {
            if index > 0 && !pace.is_zero() {
                std::thread::sleep(pace);
            }
            if self.active.load(Ordering::SeqCst) != self.generation {
                break;
            }
            let (summary, rate_limited) = match fetch(symbol, "1y", "1wk") {
                FetchOutcome::RateLimited => (None, true),
                FetchOutcome::Deferred => break,
                FetchOutcome::Candles(candles) if !candles.is_empty() => {
                    (Some(build_summary(&candles)), false)
                }
                FetchOutcome::Candles(_) | FetchOutcome::Unavailable => (None, false),
            };
            let delay = if summary.is_some() {
                REFRESH_AFTER
            } else {
                FAILURE_RETRY
            };
            let mut year = self.cache.lock().unwrap();
            let now = Instant::now();
            if rate_limited {
                year.cooldown_until = Some(now + RATE_LIMIT_RETRY);
            }
            if self.active.load(Ordering::SeqCst) != self.generation {
                break;
            }
            year.entries.insert(
                symbol.clone(),
                YearEntry {
                    summary,
                    next_attempt: now + delay,
                },
            );
            if rate_limited {
                break;
            }
        }
    }
}

impl Drop for YearBatch {
    fn drop(&mut self) {
        self.cache.lock().unwrap().in_flight = false;
    }
}

pub(crate) fn read_year_charts(
    state: &AppState,
    generation: u64,
) -> HashMap<String, AndroidChartSummary> {
    let year = state.android_v3_year_charts.lock().unwrap();
    if state.feed_generation.load(Ordering::SeqCst) != generation {
        return HashMap::new();
    }
    year.summaries(generation, Instant::now())
}

pub(crate) fn request_year_charts(state: &AppState, generation: u64, symbols: Vec<String>) {
    let Some(batch) = YearBatch::claim(
        &state.android_v3_year_charts,
        &state.feed_generation,
        generation,
        &symbols,
        Instant::now(),
    ) else {
        return;
    };
    let Ok(client) = create_feed_yahoo_client(state) else {
        batch.defer(FAILURE_RETRY);
        return;
    };
    if client.is_rate_limited() {
        return;
    }
    let _ = std::thread::Builder::new()
        .name("android-v3-year-charts".into())
        .spawn(move || {
            batch.run(REQUEST_PAUSE, |symbol, range, interval| {
                if client.is_rate_limited() {
                    return FetchOutcome::Deferred;
                }
                match client.fetch_candles(symbol, range, interval) {
                    Ok(candles) => FetchOutcome::Candles(candles),
                    Err(error) if is_rate_limit_error(&error) => FetchOutcome::RateLimited,
                    Err(_) => FetchOutcome::Unavailable,
                }
            });
        });
}

#[cfg(test)]
mod tests {
    use super::*;

    fn symbols(values: &[&str]) -> Vec<String> {
        values.iter().map(|value| value.to_string()).collect()
    }

    fn candles() -> Vec<HistoricalCandle> {
        vec![HistoricalCandle {
            epoch_seconds: 1,
            open_cents: 100,
            high_cents: 125,
            low_cents: 95,
            close_cents: 120,
            volume: 1000,
        }]
    }

    #[test]
    fn requests_exact_year_weekly_candles_with_one_bounded_batch() {
        let cache = Arc::new(Mutex::new(YearCharts::default()));
        let active = Arc::new(AtomicU64::new(2));
        let now = Instant::now();
        let candidates = symbols(&["A", "B", "A", "C", "D", "E", "F"]);
        let batch = YearBatch::claim(&cache, &active, 2, &candidates, now).unwrap();
        assert!(YearBatch::claim(&cache, &active, 2, &candidates, now).is_none());

        let mut requested = Vec::new();
        batch.run(Duration::ZERO, |symbol, range, interval| {
            assert_eq!((range, interval), ("1y", "1wk"));
            requested.push(symbol.to_string());
            FetchOutcome::Candles(candles())
        });

        assert_eq!(requested, ["A", "B", "C", "D"]);
        let summaries = cache.lock().unwrap().summaries(2, Instant::now());
        assert_eq!(summaries.len(), 4);
        assert_eq!(summaries["A"], build_summary(&candles()));
        let next = YearBatch::claim(&cache, &active, 2, &candidates, Instant::now()).unwrap();
        assert_eq!(next.symbols, ["E", "F"]);
    }

    #[test]
    fn changed_generation_discards_old_candles_and_waits_for_inflight_batch() {
        let cache = Arc::new(Mutex::new(YearCharts::default()));
        let active = Arc::new(AtomicU64::new(2));
        let candidates = symbols(&["A", "B"]);
        let batch = YearBatch::claim(&cache, &active, 2, &candidates, Instant::now()).unwrap();
        let mut calls = 0;
        batch.run(Duration::ZERO, |_, _, _| {
            calls += 1;
            active.store(3, Ordering::SeqCst);
            assert!(YearBatch::claim(&cache, &active, 3, &candidates, Instant::now()).is_none());
            FetchOutcome::Candles(candles())
        });

        assert_eq!(calls, 1);
        let now = Instant::now();
        assert!(cache.lock().unwrap().summaries(3, now).is_empty());
        assert!(cache.lock().unwrap().summaries(2, now).is_empty());
        assert!(YearBatch::claim(&cache, &active, 2, &candidates, now).is_none());
        assert!(YearBatch::claim(&cache, &active, 3, &candidates, now).is_some());
    }

    #[test]
    fn prioritizes_unattempted_symbols_and_retries_after_backoff() {
        let cache = Arc::new(Mutex::new(YearCharts::default()));
        let active = Arc::new(AtomicU64::new(2));
        let candidates = symbols(&["A", "B", "C", "D", "E"]);
        let first = YearBatch::claim(&cache, &active, 2, &candidates, Instant::now()).unwrap();
        first.run(Duration::ZERO, |symbol, _, _| match symbol {
            "A" => FetchOutcome::Unavailable,
            "B" => FetchOutcome::Candles(Vec::new()),
            _ => FetchOutcome::Candles(candles()),
        });

        let now = Instant::now();
        assert_eq!(cache.lock().unwrap().summaries(2, now).len(), 2);
        let next = YearBatch::claim(&cache, &active, 2, &candidates, now).unwrap();
        assert_eq!(next.symbols, ["E"]);
        drop(next);

        let retry = YearBatch::claim(&cache, &active, 2, &candidates, now + FAILURE_RETRY).unwrap();
        assert_eq!(retry.symbols, ["E", "A", "B"]);
    }

    #[test]
    fn requests_expired_summaries_again() {
        let cache = Arc::new(Mutex::new(YearCharts::default()));
        let active = Arc::new(AtomicU64::new(2));
        let candidates = symbols(&["A"]);
        let first = YearBatch::claim(&cache, &active, 2, &candidates, Instant::now()).unwrap();
        first.run(Duration::ZERO, |_, _, _| FetchOutcome::Candles(candles()));
        let now = Instant::now();
        assert!(YearBatch::claim(&cache, &active, 2, &candidates, now).is_none());
        let expired = now + REFRESH_AFTER;
        assert!(cache.lock().unwrap().summaries(2, expired).is_empty());
        assert!(YearBatch::claim(&cache, &active, 2, &candidates, expired).is_some());
    }

    #[test]
    fn rate_limit_stops_batch_and_defers_other_profiles() {
        let cache = Arc::new(Mutex::new(YearCharts::default()));
        let active = Arc::new(AtomicU64::new(2));
        let candidates = symbols(&["A", "B"]);
        let batch = YearBatch::claim(&cache, &active, 2, &candidates, Instant::now()).unwrap();
        let mut calls = 0;
        batch.run(Duration::ZERO, |_, _, _| {
            calls += 1;
            FetchOutcome::RateLimited
        });
        assert_eq!(calls, 1);

        let now = Instant::now();
        assert!(YearBatch::claim(&cache, &active, 2, &candidates, now).is_none());
        let retry =
            YearBatch::claim(&cache, &active, 2, &candidates, now + RATE_LIMIT_RETRY).unwrap();
        assert_eq!(retry.symbols, ["B"]);
        drop(retry);
        active.store(3, Ordering::SeqCst);
        assert!(YearBatch::claim(&cache, &active, 3, &candidates, now).is_none());
        assert!(
            YearBatch::claim(&cache, &active, 3, &candidates, now + RATE_LIMIT_RETRY).is_some()
        );
    }

    #[test]
    fn shared_feed_cooldown_does_not_backoff_unattempted_symbols() {
        let cache = Arc::new(Mutex::new(YearCharts::default()));
        let active = Arc::new(AtomicU64::new(2));
        let candidates = symbols(&["A", "B"]);
        let batch = YearBatch::claim(&cache, &active, 2, &candidates, Instant::now()).unwrap();
        let mut calls = 0;
        batch.run(Duration::ZERO, |_, _, _| {
            calls += 1;
            FetchOutcome::Deferred
        });
        assert_eq!(calls, 1);

        let next = YearBatch::claim(&cache, &active, 2, &candidates, Instant::now()).unwrap();
        assert_eq!(next.symbols, ["A", "B"]);
    }
}
