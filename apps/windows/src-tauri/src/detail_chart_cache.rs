use std::collections::VecDeque;
use std::time::Instant;

use crate::engine::HistoricalCandle;

const MAX_DETAIL_CHARTS: usize = 64;

#[derive(Default)]
pub(crate) struct DetailChartCache {
    entries: VecDeque<DetailChart>,
}

struct DetailChart {
    symbol: String,
    range: &'static str,
    started: Instant,
    candles: Vec<HistoricalCandle>,
}

impl DetailChartCache {
    pub(crate) fn get(&self, symbol: &str, range: &str) -> Option<&[HistoricalCandle]> {
        self.entries
            .iter()
            .find(|entry| entry.symbol == symbol && entry.range == range)
            .map(|entry| entry.candles.as_slice())
    }

    pub(crate) fn insert(
        &mut self,
        symbol: &str,
        range: &'static str,
        started: Instant,
        candles: Vec<HistoricalCandle>,
    ) -> bool {
        if candles.is_empty() {
            return false;
        }
        let previous = self
            .entries
            .iter()
            .position(|entry| entry.symbol == symbol && entry.range == range);
        if let Some(index) = previous {
            if self.entries[index].started > started {
                return false;
            }
            self.entries.remove(index);
        } else if self.entries.len() == MAX_DETAIL_CHARTS {
            self.entries.pop_front();
        }
        self.entries.push_back(DetailChart {
            symbol: symbol.to_string(),
            range,
            started,
            candles,
        });
        true
    }
}
