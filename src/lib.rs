use std::collections::HashMap;
use std::collections::HashSet;
use std::collections::VecDeque;
use std::fs;
use std::io;
use std::io::ErrorKind;
use std::path::Path;

const ANALYST_TARGET_ACCURACY_WEIGHT: f32 = 0.65;
const ANALYST_DIRECTION_ACCURACY_WEIGHT: f32 = 0.35;
const ANALYST_ERROR_SCALE_BPS: f32 = 1_000.0;
const ANALYST_RECENCY_DECAY_DAYS: f32 = 180.0;
const ANALYST_SAMPLE_SIZE_REGULARIZATION: f32 = 3.0;
const MAX_PRICE_HISTORY_PER_SYMBOL: usize = 240;

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub enum ConfidenceBand {
    Low,
    Provisional,
    High,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum QualificationStatus {
    Qualified,
    Unprofitable,
    GapTooSmall,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ExternalSignalStatus {
    Missing,
    Stale,
    Supportive,
    Divergent,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AlertKind {
    EnteredQualified,
    ExitedQualified,
    ConfidenceUpgraded,
}

#[derive(Clone, Debug, PartialEq, Eq, Default)]
pub struct ViewFilter {
    pub query: String,
    pub watchlist_only: bool,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MarketSnapshot {
    pub symbol: String,
    pub company_name: Option<String>,
    pub profitable: bool,
    pub market_price_cents: i64,
    pub intrinsic_value_cents: i64,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ExternalValuationSignal {
    pub symbol: String,
    pub fair_value_cents: i64,
    pub age_seconds: u64,
    pub low_fair_value_cents: Option<i64>,
    pub high_fair_value_cents: Option<i64>,
    pub analyst_opinion_count: Option<u32>,
    pub recommendation_mean_hundredths: Option<u16>,
    pub strong_buy_count: Option<u32>,
    pub buy_count: Option<u32>,
    pub hold_count: Option<u32>,
    pub sell_count: Option<u32>,
    pub strong_sell_count: Option<u32>,
    pub weighted_fair_value_cents: Option<i64>,
    pub weighted_analyst_count: Option<u32>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FundamentalSnapshot {
    pub symbol: String,
    pub sector_key: Option<String>,
    pub sector_name: Option<String>,
    pub industry_key: Option<String>,
    pub industry_name: Option<String>,
    pub market_cap_dollars: Option<u64>,
    pub shares_outstanding: Option<u64>,
    pub trailing_pe_hundredths: Option<u32>,
    pub forward_pe_hundredths: Option<u32>,
    pub price_to_book_hundredths: Option<u32>,
    pub return_on_equity_bps: Option<i32>,
    pub ebitda_dollars: Option<i64>,
    pub enterprise_value_dollars: Option<i64>,
    pub enterprise_to_ebitda_hundredths: Option<i32>,
    pub total_debt_dollars: Option<i64>,
    pub total_cash_dollars: Option<i64>,
    pub debt_to_equity_hundredths: Option<i32>,
    pub free_cash_flow_dollars: Option<i64>,
    pub operating_cash_flow_dollars: Option<i64>,
    pub beta_millis: Option<i32>,
    pub trailing_eps_cents: Option<i64>,
    pub earnings_growth_bps: Option<i32>,
}

impl FundamentalSnapshot {
    pub fn has_any_values(&self) -> bool {
        self.sector_key.is_some()
            || self.sector_name.is_some()
            || self.industry_key.is_some()
            || self.industry_name.is_some()
            || self.market_cap_dollars.is_some()
            || self.shares_outstanding.is_some()
            || self.trailing_pe_hundredths.is_some()
            || self.forward_pe_hundredths.is_some()
            || self.price_to_book_hundredths.is_some()
            || self.return_on_equity_bps.is_some()
            || self.ebitda_dollars.is_some()
            || self.enterprise_value_dollars.is_some()
            || self.enterprise_to_ebitda_hundredths.is_some()
            || self.total_debt_dollars.is_some()
            || self.total_cash_dollars.is_some()
            || self.debt_to_equity_hundredths.is_some()
            || self.free_cash_flow_dollars.is_some()
            || self.operating_cash_flow_dollars.is_some()
            || self.beta_millis.is_some()
            || self.trailing_eps_cents.is_some()
            || self.earnings_growth_bps.is_some()
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct AnalystOutcomeSample {
    pub target_error_bps: u32,
    pub direction_correct: bool,
    pub age_days: u32,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct AnalystScore {
    pub value: f32,
    pub sample_count: u32,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AnalystScoreScope {
    Company,
    Global,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct ScopedAnalystScore {
    pub scope: AnalystScoreScope,
    pub analyst_score: AnalystScore,
}

pub fn build_analyst_score(samples: &[AnalystOutcomeSample]) -> Option<AnalystScore> {
    if samples.is_empty() {
        return None;
    }

    let mut weighted_total = 0.0f32;
    let mut total_weight = 0.0f32;

    for sample in samples {
        let recency_weight = analyst_recency_weight(sample.age_days);
        let sample_score = analyst_sample_score(sample);
        weighted_total += sample_score * recency_weight;
        total_weight += recency_weight;
    }

    if total_weight <= 0.0 {
        return None;
    }

    let weighted_average = weighted_total / total_weight;
    let sample_size_factor = analyst_sample_size_factor(samples.len() as u32);

    Some(AnalystScore {
        value: (weighted_average * sample_size_factor).clamp(0.0, 1.0),
        sample_count: samples.len() as u32,
    })
}

pub fn select_analyst_score(
    company_score: Option<AnalystScore>,
    global_score: Option<AnalystScore>,
    min_company_samples: u32,
) -> Option<ScopedAnalystScore> {
    if let Some(company_score) = company_score {
        if company_score.sample_count >= min_company_samples {
            return Some(ScopedAnalystScore {
                scope: AnalystScoreScope::Company,
                analyst_score: company_score,
            });
        }
    }

    if let Some(global_score) = global_score {
        return Some(ScopedAnalystScore {
            scope: AnalystScoreScope::Global,
            analyst_score: global_score,
        });
    }

    company_score.map(|company_score| ScopedAnalystScore {
        scope: AnalystScoreScope::Company,
        analyst_score: company_score,
    })
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CandidateRow {
    pub symbol: String,
    pub market_price_cents: i64,
    pub intrinsic_value_cents: i64,
    pub gap_bps: i32,
    pub is_qualified: bool,
    pub confidence: ConfidenceBand,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TapeEvent {
    pub symbol: String,
    pub gap_bps: i32,
    pub is_qualified: bool,
    pub confidence: ConfidenceBand,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PriceHistoryPoint {
    pub sequence: usize,
    pub market_price_cents: i64,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SymbolDetail {
    pub symbol: String,
    pub profitable: bool,
    pub market_price_cents: i64,
    pub intrinsic_value_cents: i64,
    pub gap_bps: i32,
    pub minimum_gap_bps: i32,
    pub qualification: QualificationStatus,
    pub external_status: ExternalSignalStatus,
    pub external_signal_fair_value_cents: Option<i64>,
    pub external_signal_low_fair_value_cents: Option<i64>,
    pub external_signal_high_fair_value_cents: Option<i64>,
    pub weighted_external_signal_fair_value_cents: Option<i64>,
    pub weighted_analyst_count: Option<u32>,
    pub external_signal_gap_bps: Option<i32>,
    pub external_signal_age_seconds: Option<u64>,
    pub external_signal_max_age_seconds: u64,
    pub analyst_opinion_count: Option<u32>,
    pub recommendation_mean_hundredths: Option<u16>,
    pub strong_buy_count: Option<u32>,
    pub buy_count: Option<u32>,
    pub hold_count: Option<u32>,
    pub sell_count: Option<u32>,
    pub strong_sell_count: Option<u32>,
    pub fundamentals: Option<FundamentalSnapshot>,
    pub confidence: ConfidenceBand,
    pub last_sequence: usize,
    pub update_count: usize,
    pub is_watched: bool,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AlertEvent {
    pub symbol: String,
    pub kind: AlertKind,
    pub sequence: usize,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum JournalPayload {
    Snapshot(MarketSnapshot),
    External(ExternalValuationSignal),
    Fundamentals(FundamentalSnapshot),
    FundamentalsCleared(String),
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct JournalEntry {
    pub sequence: usize,
    pub payload: JournalPayload,
}

#[derive(Default)]
struct SymbolState {
    snapshot: Option<MarketSnapshot>,
    external_signal: Option<ExternalValuationSignal>,
    fundamentals: Option<FundamentalSnapshot>,
    last_sequence: usize,
    update_count: usize,
    price_history: VecDeque<PriceHistoryPoint>,
}

pub struct TerminalState {
    min_gap_bps: i32,
    external_signal_max_age_seconds: u64,
    tape_capacity: usize,
    total_events: usize,
    sequence: usize,
    symbols: HashMap<String, SymbolState>,
    watchlist: HashSet<String>,
    recent_tape: VecDeque<TapeEvent>,
    recent_alerts: VecDeque<AlertEvent>,
    journal: Vec<JournalEntry>,
}

impl TerminalState {
    pub fn new(
        min_gap_bps: i32,
        external_signal_max_age_seconds: u64,
        tape_capacity: usize,
    ) -> Self {
        Self {
            min_gap_bps,
            external_signal_max_age_seconds,
            tape_capacity,
            total_events: 0,
            sequence: 0,
            symbols: HashMap::new(),
            watchlist: HashSet::new(),
            recent_tape: VecDeque::with_capacity(tape_capacity),
            recent_alerts: VecDeque::with_capacity(tape_capacity.max(1)),
            journal: Vec::new(),
        }
    }

    pub fn ingest_snapshot(&mut self, snapshot: MarketSnapshot) {
        if snapshot.market_price_cents <= 0 || snapshot.intrinsic_value_cents <= 0 {
            return;
        }

        let previous_detail = self.detail(&snapshot.symbol);
        let sequence = self.next_sequence();
        let state = self.symbols.entry(snapshot.symbol.clone()).or_default();
        state.snapshot = Some(snapshot.clone());
        state.last_sequence = sequence;
        state.update_count += 1;
        if state.price_history.len() == MAX_PRICE_HISTORY_PER_SYMBOL {
            state.price_history.pop_front();
        }
        state.price_history.push_back(PriceHistoryPoint {
            sequence,
            market_price_cents: snapshot.market_price_cents,
        });
        self.total_events += 1;
        self.push_tape(&snapshot.symbol);
        self.push_alerts(&snapshot.symbol, previous_detail);
        self.journal.push(JournalEntry {
            sequence,
            payload: JournalPayload::Snapshot(snapshot),
        });
    }

    pub fn ingest_external(&mut self, signal: ExternalValuationSignal) {
        let mut signal = signal;
        if signal.fair_value_cents <= 0 {
            return;
        }

        sanitize_external_signal(&mut signal);

        let previous_detail = self.detail(&signal.symbol);
        let sequence = self.next_sequence();
        let state = self.symbols.entry(signal.symbol.clone()).or_default();
        state.external_signal = Some(signal.clone());
        state.last_sequence = sequence;
        state.update_count += 1;
        self.total_events += 1;
        self.push_tape(&signal.symbol);
        self.push_alerts(&signal.symbol, previous_detail);
        self.journal.push(JournalEntry {
            sequence,
            payload: JournalPayload::External(signal),
        });
    }

    pub fn ingest_fundamentals(&mut self, fundamentals: FundamentalSnapshot) {
        if !fundamentals.has_any_values() {
            return;
        }

        let sequence = self.next_sequence();
        let state = self.symbols.entry(fundamentals.symbol.clone()).or_default();
        state.fundamentals = Some(fundamentals.clone());
        state.last_sequence = sequence;
        state.update_count += 1;
        self.total_events += 1;
        self.journal.push(JournalEntry {
            sequence,
            payload: JournalPayload::Fundamentals(fundamentals),
        });
    }

    pub fn clear_fundamentals(&mut self, symbol: &str) {
        let should_clear = self
            .symbols
            .get(symbol)
            .and_then(|state| state.fundamentals.as_ref())
            .is_some();
        if !should_clear {
            return;
        }

        let sequence = self.next_sequence();
        let state = self
            .symbols
            .get_mut(symbol)
            .expect("existing symbol state should still be present");
        state.fundamentals = None;
        state.last_sequence = sequence;
        state.update_count += 1;
        self.total_events += 1;
        self.journal.push(JournalEntry {
            sequence,
            payload: JournalPayload::FundamentalsCleared(symbol.to_string()),
        });
    }

    pub fn candidate(&self, symbol: &str) -> Option<CandidateRow> {
        self.symbols
            .get(symbol)
            .and_then(|state| self.build_candidate(state))
    }

    pub fn detail(&self, symbol: &str) -> Option<SymbolDetail> {
        self.symbols
            .get(symbol)
            .and_then(|state| self.build_detail(state))
    }

    pub fn company_name(&self, symbol: &str) -> Option<&str> {
        self.symbols
            .get(symbol)
            .and_then(|state| state.snapshot.as_ref())
            .and_then(|snapshot| snapshot.company_name.as_deref())
    }

    pub fn fundamentals_iter(&self) -> impl Iterator<Item = (&str, &FundamentalSnapshot)> + '_ {
        self.symbols.iter().filter_map(|(symbol, state)| {
            state
                .fundamentals
                .as_ref()
                .map(|fundamentals| (symbol.as_str(), fundamentals))
        })
    }

    pub fn price_history(&self, symbol: &str, limit: usize) -> Vec<PriceHistoryPoint> {
        let Some(state) = self.symbols.get(symbol) else {
            return Vec::new();
        };

        let skip = state.price_history.len().saturating_sub(limit);
        state.price_history.iter().skip(skip).cloned().collect()
    }

    pub fn top_rows(&self, limit: usize) -> Vec<CandidateRow> {
        let mut rows = self.sorted_rows();
        rows.truncate(limit);
        rows
    }

    pub fn filtered_rows(&self, limit: usize, filter: &ViewFilter) -> Vec<CandidateRow> {
        let query = filter.query.trim();
        let mut rows = self
            .symbols
            .iter()
            .filter(|(symbol, _)| {
                let query_matches =
                    query.is_empty() || ascii_contains_ignore_case(symbol.as_str(), query);
                let watchlist_matches = !filter.watchlist_only || self.watchlist.contains(*symbol);
                query_matches && watchlist_matches
            })
            .filter_map(|(_, state)| self.build_candidate(state))
            .collect::<Vec<_>>();
        self.sort_rows(&mut rows);
        rows.truncate(limit);
        rows
    }

    pub fn recent_tape_iter(&self) -> impl DoubleEndedIterator<Item = &TapeEvent> + '_ {
        self.recent_tape.iter()
    }

    pub fn recent_tape(&self) -> Vec<TapeEvent> {
        self.recent_tape_iter().cloned().collect()
    }

    pub fn recent_tape_for_symbol<'a>(
        &'a self,
        symbol: &'a str,
    ) -> impl DoubleEndedIterator<Item = &'a TapeEvent> + 'a {
        self.recent_tape
            .iter()
            .filter(move |event| event.symbol == symbol)
    }

    pub fn alerts_iter(&self) -> impl DoubleEndedIterator<Item = &AlertEvent> + '_ {
        self.recent_alerts.iter()
    }

    pub fn alerts(&self) -> Vec<AlertEvent> {
        self.alerts_iter().cloned().collect()
    }

    pub fn alerts_for_symbol<'a>(
        &'a self,
        symbol: &'a str,
    ) -> impl DoubleEndedIterator<Item = &'a AlertEvent> + 'a {
        self.recent_alerts
            .iter()
            .filter(move |alert| alert.symbol == symbol)
    }

    pub fn journal(&self) -> Vec<JournalEntry> {
        self.journal.clone()
    }

    pub fn journal_since(&self, sequence: usize) -> Vec<JournalEntry> {
        self.journal
            .iter()
            .filter(|entry| entry.sequence > sequence)
            .cloned()
            .collect()
    }

    pub fn save_journal_file<P: AsRef<Path>>(&self, path: P) -> io::Result<()> {
        let mut output = String::new();
        for entry in &self.journal {
            output.push_str(&encode_journal_entry(entry));
            output.push('\n');
        }
        fs::write(path, output)
    }

    pub fn append_journal_file<P: AsRef<Path>>(
        path: P,
        entries: &[JournalEntry],
    ) -> io::Result<()> {
        if entries.is_empty() {
            return Ok(());
        }

        let mut output = String::new();
        for entry in entries {
            output.push_str(&encode_journal_entry(entry));
            output.push('\n');
        }

        use std::io::Write;
        let mut file = fs::OpenOptions::new()
            .create(true)
            .append(true)
            .open(path)?;
        file.write_all(output.as_bytes())
    }

    pub fn save_watchlist_file<P: AsRef<Path>>(&self, path: P) -> io::Result<()> {
        let mut symbols = self.watchlist.iter().cloned().collect::<Vec<_>>();
        symbols.sort();
        fs::write(path, symbols.join("\n"))
    }

    pub fn load_watchlist_file<P: AsRef<Path>>(&mut self, path: P) -> io::Result<()> {
        let file_content = fs::read_to_string(path)?;
        self.watchlist.clear();
        for line in file_content.lines() {
            let symbol = line.trim();
            if symbol.is_empty() {
                continue;
            }
            self.watchlist.insert(symbol.to_string());
        }
        Ok(())
    }

    pub fn total_events(&self) -> usize {
        self.total_events
    }

    pub fn symbol_count(&self) -> usize {
        self.symbols.len()
    }

    pub fn latest_sequence(&self) -> usize {
        self.sequence
    }

    pub fn toggle_watchlist(&mut self, symbol: &str) -> bool {
        if self.watchlist.contains(symbol) {
            self.watchlist.remove(symbol);
            return false;
        }

        self.watchlist.insert(symbol.to_string());
        true
    }

    pub fn is_watched(&self, symbol: &str) -> bool {
        self.watchlist.contains(symbol)
    }

    pub fn replay(
        min_gap_bps: i32,
        external_signal_max_age_seconds: u64,
        tape_capacity: usize,
        journal: &[JournalEntry],
    ) -> Self {
        let mut state = Self::new(min_gap_bps, external_signal_max_age_seconds, tape_capacity);
        for entry in journal {
            match &entry.payload {
                JournalPayload::Snapshot(snapshot) => state.ingest_snapshot(snapshot.clone()),
                JournalPayload::External(signal) => state.ingest_external(signal.clone()),
                JournalPayload::Fundamentals(fundamentals) => {
                    state.ingest_fundamentals(fundamentals.clone())
                }
                JournalPayload::FundamentalsCleared(symbol) => state.clear_fundamentals(symbol),
            }
        }
        state
    }

    pub fn replay_file<P: AsRef<Path>>(
        min_gap_bps: i32,
        external_signal_max_age_seconds: u64,
        tape_capacity: usize,
        path: P,
    ) -> io::Result<Self> {
        let journal = read_journal_file(path)?;
        Ok(Self::replay(
            min_gap_bps,
            external_signal_max_age_seconds,
            tape_capacity,
            &journal,
        ))
    }

    fn push_tape(&mut self, symbol: &str) {
        if self.tape_capacity == 0 {
            return;
        }

        let Some(candidate) = self.candidate(symbol) else {
            return;
        };

        if self.recent_tape.len() == self.tape_capacity {
            self.recent_tape.pop_front();
        }

        self.recent_tape.push_back(TapeEvent {
            symbol: candidate.symbol,
            gap_bps: candidate.gap_bps,
            is_qualified: candidate.is_qualified,
            confidence: candidate.confidence,
        });
    }

    fn push_alerts(&mut self, symbol: &str, previous_detail: Option<SymbolDetail>) {
        let Some(current_detail) = self.detail(symbol) else {
            return;
        };

        let previous_qualified = previous_detail
            .as_ref()
            .map(|detail| detail.qualification == QualificationStatus::Qualified)
            .unwrap_or(false);
        let current_qualified = current_detail.qualification == QualificationStatus::Qualified;

        if !previous_qualified && current_qualified {
            self.push_alert(
                symbol.to_string(),
                AlertKind::EnteredQualified,
                current_detail.last_sequence,
            );
            return;
        }

        if previous_qualified && !current_qualified {
            self.push_alert(
                symbol.to_string(),
                AlertKind::ExitedQualified,
                current_detail.last_sequence,
            );
            return;
        }

        if previous_qualified
            && current_qualified
            && previous_detail
                .as_ref()
                .map(|detail| detail.confidence != ConfidenceBand::High)
                .unwrap_or(false)
            && current_detail.confidence == ConfidenceBand::High
        {
            self.push_alert(
                symbol.to_string(),
                AlertKind::ConfidenceUpgraded,
                current_detail.last_sequence,
            );
        }
    }

    fn push_alert(&mut self, symbol: String, kind: AlertKind, sequence: usize) {
        if self.tape_capacity == 0 {
            return;
        }

        if self.recent_alerts.len() == self.tape_capacity {
            self.recent_alerts.pop_front();
        }

        self.recent_alerts.push_back(AlertEvent {
            symbol,
            kind,
            sequence,
        });
    }

    fn build_candidate(&self, state: &SymbolState) -> Option<CandidateRow> {
        let detail = self.build_detail(state)?;

        Some(CandidateRow {
            symbol: detail.symbol,
            market_price_cents: detail.market_price_cents,
            intrinsic_value_cents: detail.intrinsic_value_cents,
            gap_bps: detail.gap_bps,
            is_qualified: detail.qualification == QualificationStatus::Qualified,
            confidence: detail.confidence,
        })
    }

    fn build_detail(&self, state: &SymbolState) -> Option<SymbolDetail> {
        let snapshot = state.snapshot.as_ref()?;
        let internal_gap_bps = gap_bps(snapshot.market_price_cents, snapshot.intrinsic_value_cents);
        let qualification = self.qualification_for(snapshot, internal_gap_bps);
        let external_status = self.external_status_for(snapshot, state.external_signal.as_ref());
        let confidence = self.confidence_for(qualification, external_status);
        let external_signal_fair_value_cents = state
            .external_signal
            .as_ref()
            .map(|signal| signal.fair_value_cents);
        let external_signal_low_fair_value_cents = state
            .external_signal
            .as_ref()
            .and_then(|signal| signal.low_fair_value_cents);
        let external_signal_high_fair_value_cents = state
            .external_signal
            .as_ref()
            .and_then(|signal| signal.high_fair_value_cents);
        let external_signal_gap_bps = state
            .external_signal
            .as_ref()
            .map(|signal| gap_bps(snapshot.market_price_cents, signal.fair_value_cents));
        let weighted_external_signal_fair_value_cents = state
            .external_signal
            .as_ref()
            .and_then(clamped_weighted_fair_value);
        let weighted_analyst_count = state
            .external_signal
            .as_ref()
            .and_then(|signal| signal.weighted_analyst_count);
        let external_signal_age_seconds = state
            .external_signal
            .as_ref()
            .map(|signal| signal.age_seconds);
        let analyst_opinion_count = state
            .external_signal
            .as_ref()
            .and_then(|signal| signal.analyst_opinion_count);
        let recommendation_mean_hundredths = state
            .external_signal
            .as_ref()
            .and_then(|signal| signal.recommendation_mean_hundredths);
        let strong_buy_count = state
            .external_signal
            .as_ref()
            .and_then(|signal| signal.strong_buy_count);
        let buy_count = state
            .external_signal
            .as_ref()
            .and_then(|signal| signal.buy_count);
        let hold_count = state
            .external_signal
            .as_ref()
            .and_then(|signal| signal.hold_count);
        let sell_count = state
            .external_signal
            .as_ref()
            .and_then(|signal| signal.sell_count);
        let strong_sell_count = state
            .external_signal
            .as_ref()
            .and_then(|signal| signal.strong_sell_count);

        Some(SymbolDetail {
            symbol: snapshot.symbol.clone(),
            profitable: snapshot.profitable,
            market_price_cents: snapshot.market_price_cents,
            intrinsic_value_cents: snapshot.intrinsic_value_cents,
            gap_bps: internal_gap_bps,
            minimum_gap_bps: self.min_gap_bps,
            qualification,
            external_status,
            external_signal_fair_value_cents,
            external_signal_low_fair_value_cents,
            external_signal_high_fair_value_cents,
            weighted_external_signal_fair_value_cents,
            weighted_analyst_count,
            external_signal_gap_bps,
            external_signal_age_seconds,
            external_signal_max_age_seconds: self.external_signal_max_age_seconds,
            analyst_opinion_count,
            recommendation_mean_hundredths,
            strong_buy_count,
            buy_count,
            hold_count,
            sell_count,
            strong_sell_count,
            fundamentals: state.fundamentals.clone(),
            confidence,
            last_sequence: state.last_sequence,
            update_count: state.update_count,
            is_watched: self.watchlist.contains(&snapshot.symbol),
        })
    }

    fn qualification_for(&self, snapshot: &MarketSnapshot, gap_bps: i32) -> QualificationStatus {
        if !snapshot.profitable {
            return QualificationStatus::Unprofitable;
        }

        if gap_bps >= self.min_gap_bps {
            return QualificationStatus::Qualified;
        }

        QualificationStatus::GapTooSmall
    }

    fn external_status_for(
        &self,
        snapshot: &MarketSnapshot,
        external_signal: Option<&ExternalValuationSignal>,
    ) -> ExternalSignalStatus {
        let Some(external_signal) = external_signal else {
            return ExternalSignalStatus::Missing;
        };

        if external_signal.symbol != snapshot.symbol {
            return ExternalSignalStatus::Divergent;
        }

        if external_signal.age_seconds > self.external_signal_max_age_seconds {
            return ExternalSignalStatus::Stale;
        }

        if gap_bps(
            snapshot.market_price_cents,
            external_signal.fair_value_cents,
        ) >= self.min_gap_bps
        {
            return ExternalSignalStatus::Supportive;
        }

        ExternalSignalStatus::Divergent
    }

    fn confidence_for(
        &self,
        qualification: QualificationStatus,
        external_status: ExternalSignalStatus,
    ) -> ConfidenceBand {
        if qualification != QualificationStatus::Qualified {
            return ConfidenceBand::Low;
        }

        match external_status {
            ExternalSignalStatus::Missing => ConfidenceBand::Provisional,
            ExternalSignalStatus::Supportive => ConfidenceBand::High,
            ExternalSignalStatus::Stale | ExternalSignalStatus::Divergent => ConfidenceBand::Low,
        }
    }

    fn confidence_rank(&self, confidence: ConfidenceBand) -> u8 {
        match confidence {
            ConfidenceBand::Low => 0,
            ConfidenceBand::Provisional => 1,
            ConfidenceBand::High => 2,
        }
    }

    fn sorted_rows(&self) -> Vec<CandidateRow> {
        let mut rows = self
            .symbols
            .values()
            .filter_map(|state| self.build_candidate(state))
            .collect::<Vec<_>>();

        self.sort_rows(&mut rows);

        rows
    }

    fn sort_rows(&self, rows: &mut [CandidateRow]) {
        rows.sort_by(|left, right| {
            right
                .is_qualified
                .cmp(&left.is_qualified)
                .then_with(|| right.gap_bps.cmp(&left.gap_bps))
                .then_with(|| {
                    self.confidence_rank(right.confidence)
                        .cmp(&self.confidence_rank(left.confidence))
                })
                .then_with(|| left.symbol.cmp(&right.symbol))
        });
    }

    fn next_sequence(&mut self) -> usize {
        self.sequence += 1;
        self.sequence
    }
}

fn ascii_contains_ignore_case(haystack: &str, needle: &str) -> bool {
    if needle.is_empty() {
        return true;
    }

    let needle = needle.as_bytes();
    haystack
        .as_bytes()
        .windows(needle.len())
        .any(|window| window.eq_ignore_ascii_case(needle))
}

fn read_journal_file<P: AsRef<Path>>(path: P) -> io::Result<Vec<JournalEntry>> {
    let file_content = fs::read_to_string(path)?;
    let ends_with_newline = file_content.ends_with('\n');
    let lines = file_content.lines().collect::<Vec<_>>();
    let mut journal = Vec::new();

    for (index, line) in lines.iter().enumerate() {
        if line.trim().is_empty() {
            continue;
        }

        let entry = match decode_journal_entry(line) {
            Ok(entry) => entry,
            Err(_) if !ends_with_newline && index + 1 == lines.len() => break,
            Err(message) => {
                return Err(io::Error::new(
                    ErrorKind::InvalidData,
                    format!("invalid journal line {}: {}", index + 1, message),
                ));
            }
        };
        journal.push(entry);
    }

    Ok(journal)
}

fn encode_journal_entry(entry: &JournalEntry) -> String {
    match &entry.payload {
        JournalPayload::Snapshot(snapshot) => format!(
            "S|{}|{}|{}|{}|{}{}",
            entry.sequence,
            snapshot.symbol,
            if snapshot.profitable { 1 } else { 0 },
            snapshot.market_price_cents,
            snapshot.intrinsic_value_cents,
            snapshot
                .company_name
                .as_deref()
                .map(|company_name| format!("|{}", company_name))
                .unwrap_or_default(),
        ),
        JournalPayload::External(signal) => {
            let has_extended_fields = signal.low_fair_value_cents.is_some()
                || signal.high_fair_value_cents.is_some()
                || signal.analyst_opinion_count.is_some()
                || signal.recommendation_mean_hundredths.is_some()
                || signal.strong_buy_count.is_some()
                || signal.buy_count.is_some()
                || signal.hold_count.is_some()
                || signal.sell_count.is_some()
                || signal.strong_sell_count.is_some()
                || signal.weighted_fair_value_cents.is_some()
                || signal.weighted_analyst_count.is_some();

            let has_weighted_fields = signal.weighted_fair_value_cents.is_some()
                || signal.weighted_analyst_count.is_some();

            if !has_extended_fields {
                return format!(
                    "E|{}|{}|{}|{}",
                    entry.sequence, signal.symbol, signal.fair_value_cents, signal.age_seconds,
                );
            }

            if !has_weighted_fields {
                return format!(
                    "E|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}",
                    entry.sequence,
                    signal.symbol,
                    signal.fair_value_cents,
                    signal.age_seconds,
                    optional_number_field(signal.low_fair_value_cents),
                    optional_number_field(signal.high_fair_value_cents),
                    optional_number_field(signal.analyst_opinion_count),
                    optional_number_field(signal.recommendation_mean_hundredths),
                    optional_number_field(signal.strong_buy_count),
                    optional_number_field(signal.buy_count),
                    optional_number_field(signal.hold_count),
                    optional_number_field(signal.sell_count),
                    optional_number_field(signal.strong_sell_count),
                );
            }

            format!(
                "E|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}",
                entry.sequence,
                signal.symbol,
                signal.fair_value_cents,
                signal.age_seconds,
                optional_number_field(signal.low_fair_value_cents),
                optional_number_field(signal.high_fair_value_cents),
                optional_number_field(signal.analyst_opinion_count),
                optional_number_field(signal.recommendation_mean_hundredths),
                optional_number_field(signal.strong_buy_count),
                optional_number_field(signal.buy_count),
                optional_number_field(signal.hold_count),
                optional_number_field(signal.sell_count),
                optional_number_field(signal.strong_sell_count),
                optional_number_field(signal.weighted_fair_value_cents),
                optional_number_field(signal.weighted_analyst_count),
            )
        }
        JournalPayload::Fundamentals(fundamentals) => format!(
            "F|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}|{}",
            entry.sequence,
            fundamentals.symbol,
            optional_string_field(fundamentals.sector_key.as_deref()),
            optional_string_field(fundamentals.sector_name.as_deref()),
            optional_string_field(fundamentals.industry_key.as_deref()),
            optional_string_field(fundamentals.industry_name.as_deref()),
            optional_number_field(fundamentals.market_cap_dollars),
            optional_number_field(fundamentals.shares_outstanding),
            optional_number_field(fundamentals.trailing_pe_hundredths),
            optional_number_field(fundamentals.forward_pe_hundredths),
            optional_number_field(fundamentals.price_to_book_hundredths),
            optional_number_field(fundamentals.return_on_equity_bps),
            optional_number_field(fundamentals.ebitda_dollars),
            optional_number_field(fundamentals.enterprise_value_dollars),
            optional_number_field(fundamentals.enterprise_to_ebitda_hundredths),
            optional_number_field(fundamentals.total_debt_dollars),
            optional_number_field(fundamentals.total_cash_dollars),
            optional_number_field(fundamentals.debt_to_equity_hundredths),
            optional_number_field(fundamentals.free_cash_flow_dollars),
            optional_number_field(fundamentals.operating_cash_flow_dollars),
            optional_number_field(fundamentals.beta_millis),
            optional_number_field(fundamentals.trailing_eps_cents),
            optional_number_field(fundamentals.earnings_growth_bps)
        ),
        JournalPayload::FundamentalsCleared(symbol) => {
            format!("FC|{}|{}", entry.sequence, symbol)
        }
    }
}

fn decode_journal_entry(line: &str) -> Result<JournalEntry, String> {
    let parts = line.split('|').collect::<Vec<_>>();
    let Some(kind) = parts.first() else {
        return Err("missing event kind".to_string());
    };

    match *kind {
        "S" => decode_snapshot_entry(&parts),
        "E" => decode_external_entry(&parts),
        "F" => decode_fundamentals_entry(&parts),
        "FC" => decode_fundamentals_cleared_entry(&parts),
        _ => Err("unknown event kind".to_string()),
    }
}

fn decode_snapshot_entry(parts: &[&str]) -> Result<JournalEntry, String> {
    if parts.len() != 6 && parts.len() != 7 {
        return Err("snapshot entry should have 6 or 7 fields".to_string());
    }

    let market_price_cents = require_positive_i64(
        parse_number(parts[4], "snapshot market_price_cents")?,
        "snapshot market_price_cents",
    )?;
    let intrinsic_value_cents = require_positive_i64(
        parse_number(parts[5], "snapshot intrinsic_value_cents")?,
        "snapshot intrinsic_value_cents",
    )?;
    let company_name = if parts.len() == 7 && !parts[6].trim().is_empty() {
        Some(parts[6].to_string())
    } else {
        None
    };

    Ok(JournalEntry {
        sequence: parse_number(parts[1], "snapshot sequence")?,
        payload: JournalPayload::Snapshot(MarketSnapshot {
            symbol: parts[2].to_string(),
            company_name,
            profitable: parse_bool_flag(parts[3])?,
            market_price_cents,
            intrinsic_value_cents,
        }),
    })
}

fn decode_external_entry(parts: &[&str]) -> Result<JournalEntry, String> {
    if parts.len() != 5 && parts.len() != 14 && parts.len() != 16 {
        return Err("external entry should have 5, 14, or 16 fields".to_string());
    }

    let fair_value_cents = require_positive_i64(
        parse_number(parts[3], "external fair_value_cents")?,
        "external fair_value_cents",
    )?;
    let mut weighted_fair_value_cents =
        parse_optional_number(parts.get(14), "external weighted_fair_value_cents")?;
    let mut weighted_analyst_count =
        parse_optional_number(parts.get(15), "external weighted_analyst_count")?;

    if matches!(weighted_fair_value_cents, Some(value) if value <= 0) {
        weighted_fair_value_cents = None;
        weighted_analyst_count = None;
    }

    Ok(JournalEntry {
        sequence: parse_number(parts[1], "external sequence")?,
        payload: JournalPayload::External(ExternalValuationSignal {
            symbol: parts[2].to_string(),
            fair_value_cents,
            age_seconds: parse_number(parts[4], "external age_seconds")?,
            low_fair_value_cents: parse_optional_number(
                parts.get(5),
                "external low_fair_value_cents",
            )?,
            high_fair_value_cents: parse_optional_number(
                parts.get(6),
                "external high_fair_value_cents",
            )?,
            analyst_opinion_count: parse_optional_number(
                parts.get(7),
                "external analyst_opinion_count",
            )?,
            recommendation_mean_hundredths: parse_optional_number(
                parts.get(8),
                "external recommendation_mean_hundredths",
            )?,
            strong_buy_count: parse_optional_number(parts.get(9), "external strong_buy_count")?,
            buy_count: parse_optional_number(parts.get(10), "external buy_count")?,
            hold_count: parse_optional_number(parts.get(11), "external hold_count")?,
            sell_count: parse_optional_number(parts.get(12), "external sell_count")?,
            strong_sell_count: parse_optional_number(parts.get(13), "external strong_sell_count")?,
            weighted_fair_value_cents,
            weighted_analyst_count,
        }),
    })
}

fn decode_fundamentals_entry(parts: &[&str]) -> Result<JournalEntry, String> {
    if parts.len() != 24 {
        return Err("fundamentals entry should have 24 fields".to_string());
    }

    Ok(JournalEntry {
        sequence: parse_number(parts[1], "fundamentals sequence")?,
        payload: JournalPayload::Fundamentals(FundamentalSnapshot {
            symbol: parts[2].to_string(),
            sector_key: parse_optional_string(parts.get(3)),
            sector_name: parse_optional_string(parts.get(4)),
            industry_key: parse_optional_string(parts.get(5)),
            industry_name: parse_optional_string(parts.get(6)),
            market_cap_dollars: parse_optional_number(parts.get(7), "fundamentals market_cap")?,
            shares_outstanding: parse_optional_number(parts.get(8), "fundamentals shares")?,
            trailing_pe_hundredths: parse_optional_number(
                parts.get(9),
                "fundamentals trailing_pe",
            )?,
            forward_pe_hundredths: parse_optional_number(parts.get(10), "fundamentals forward_pe")?,
            price_to_book_hundredths: parse_optional_number(
                parts.get(11),
                "fundamentals price_to_book",
            )?,
            return_on_equity_bps: parse_optional_number(
                parts.get(12),
                "fundamentals return_on_equity",
            )?,
            ebitda_dollars: parse_optional_number(parts.get(13), "fundamentals ebitda")?,
            enterprise_value_dollars: parse_optional_number(
                parts.get(14),
                "fundamentals enterprise_value",
            )?,
            enterprise_to_ebitda_hundredths: parse_optional_number(
                parts.get(15),
                "fundamentals enterprise_to_ebitda",
            )?,
            total_debt_dollars: parse_optional_number(parts.get(16), "fundamentals total_debt")?,
            total_cash_dollars: parse_optional_number(parts.get(17), "fundamentals total_cash")?,
            debt_to_equity_hundredths: parse_optional_number(
                parts.get(18),
                "fundamentals debt_to_equity",
            )?,
            free_cash_flow_dollars: parse_optional_number(
                parts.get(19),
                "fundamentals free_cash_flow",
            )?,
            operating_cash_flow_dollars: parse_optional_number(
                parts.get(20),
                "fundamentals operating_cash_flow",
            )?,
            beta_millis: parse_optional_number(parts.get(21), "fundamentals beta")?,
            trailing_eps_cents: parse_optional_number(parts.get(22), "fundamentals trailing_eps")?,
            earnings_growth_bps: parse_optional_number(
                parts.get(23),
                "fundamentals earnings_growth",
            )?,
        }),
    })
}

fn decode_fundamentals_cleared_entry(parts: &[&str]) -> Result<JournalEntry, String> {
    if parts.len() != 3 {
        return Err("fundamentals clear entry should have 3 fields".to_string());
    }

    Ok(JournalEntry {
        sequence: parse_number(parts[1], "fundamentals clear sequence")?,
        payload: JournalPayload::FundamentalsCleared(parts[2].to_string()),
    })
}

fn optional_number_field<T>(value: Option<T>) -> String
where
    T: ToString,
{
    value.map(|value| value.to_string()).unwrap_or_default()
}

fn optional_string_field(value: Option<&str>) -> String {
    value.unwrap_or_default().to_string()
}

fn parse_optional_number<T>(raw: Option<&&str>, field_name: &str) -> Result<Option<T>, String>
where
    T: std::str::FromStr,
{
    let Some(raw) = raw else {
        return Ok(None);
    };

    if raw.is_empty() {
        return Ok(None);
    }

    raw.parse::<T>()
        .map(Some)
        .map_err(|_| format!("invalid {}", field_name))
}

fn parse_optional_string(raw: Option<&&str>) -> Option<String> {
    raw.map(|value| value.trim())
        .filter(|value| !value.is_empty())
        .map(str::to_string)
}

fn parse_number<T>(raw: &str, field_name: &str) -> Result<T, String>
where
    T: std::str::FromStr,
{
    raw.parse::<T>()
        .map_err(|_| format!("invalid {}", field_name))
}

fn parse_bool_flag(raw: &str) -> Result<bool, String> {
    match raw {
        "1" => Ok(true),
        "0" => Ok(false),
        _ => Err("invalid profitable flag".to_string()),
    }
}

fn require_positive_i64(value: i64, field_name: &str) -> Result<i64, String> {
    if value > 0 {
        return Ok(value);
    }

    Err(format!("{field_name} must be positive"))
}

fn clamped_weighted_fair_value(signal: &ExternalValuationSignal) -> Option<i64> {
    let mut weighted_fair_value_cents = signal.weighted_fair_value_cents?;

    if let (Some(low_fair_value_cents), Some(high_fair_value_cents)) =
        (signal.low_fair_value_cents, signal.high_fair_value_cents)
    {
        weighted_fair_value_cents = weighted_fair_value_cents.clamp(
            low_fair_value_cents.min(high_fair_value_cents),
            low_fair_value_cents.max(high_fair_value_cents),
        );
    }

    (weighted_fair_value_cents > 0).then_some(weighted_fair_value_cents)
}

pub(crate) fn sanitize_external_signal(signal: &mut ExternalValuationSignal) {
    let Some(weighted_fair_value_cents) = clamped_weighted_fair_value(signal) else {
        signal.weighted_fair_value_cents = None;
        signal.weighted_analyst_count = None;
        return;
    };

    signal.weighted_fair_value_cents = Some(weighted_fair_value_cents);
}

pub fn checked_gap_bps(market_price_cents: i64, fair_value_cents: i64) -> Option<i32> {
    if fair_value_cents <= 0 {
        return None;
    }

    let scaled_gap_bps = ((fair_value_cents as i128 - market_price_cents as i128) * 10_000)
        / fair_value_cents as i128;

    Some(scaled_gap_bps.clamp(i32::MIN as i128, i32::MAX as i128) as i32)
}

fn gap_bps(market_price_cents: i64, fair_value_cents: i64) -> i32 {
    checked_gap_bps(market_price_cents, fair_value_cents).unwrap_or(0)
}

fn analyst_sample_score(sample: &AnalystOutcomeSample) -> f32 {
    let target_accuracy_score =
        1.0 / (1.0 + sample.target_error_bps as f32 / ANALYST_ERROR_SCALE_BPS);
    let direction_score = if sample.direction_correct { 1.0 } else { 0.0 };

    target_accuracy_score * ANALYST_TARGET_ACCURACY_WEIGHT
        + direction_score * ANALYST_DIRECTION_ACCURACY_WEIGHT
}

fn analyst_recency_weight(age_days: u32) -> f32 {
    1.0 / (1.0 + age_days as f32 / ANALYST_RECENCY_DECAY_DAYS)
}

fn analyst_sample_size_factor(sample_count: u32) -> f32 {
    let sample_count = sample_count as f32;
    sample_count / (sample_count + ANALYST_SAMPLE_SIZE_REGULARIZATION)
}

#[cfg(test)]
mod tests {
    use super::AnalystOutcomeSample;
    use super::AnalystScore;
    use super::AnalystScoreScope;
    use super::ExternalValuationSignal;
    use super::MarketSnapshot;
    use super::SymbolState;
    use super::TerminalState;
    use super::build_analyst_score;
    use super::gap_bps;
    use super::select_analyst_score;
    use std::collections::VecDeque;

    fn analyst_score(value: f32, sample_count: u32) -> AnalystScore {
        AnalystScore {
            value,
            sample_count,
        }
    }

    #[test]
    fn returns_none_without_samples() {
        assert_eq!(build_analyst_score(&[]), None);
    }

    #[test]
    fn rewards_recent_accurate_directionally_correct_samples() {
        let strong_score = build_analyst_score(&[
            AnalystOutcomeSample {
                target_error_bps: 150,
                direction_correct: true,
                age_days: 7,
            },
            AnalystOutcomeSample {
                target_error_bps: 200,
                direction_correct: true,
                age_days: 21,
            },
            AnalystOutcomeSample {
                target_error_bps: 100,
                direction_correct: true,
                age_days: 45,
            },
        ])
        .expect("samples should produce a score");
        let weak_score = build_analyst_score(&[
            AnalystOutcomeSample {
                target_error_bps: 2_500,
                direction_correct: false,
                age_days: 7,
            },
            AnalystOutcomeSample {
                target_error_bps: 3_000,
                direction_correct: false,
                age_days: 21,
            },
            AnalystOutcomeSample {
                target_error_bps: 2_000,
                direction_correct: false,
                age_days: 45,
            },
        ])
        .expect("samples should produce a score");

        assert!(strong_score.value > weak_score.value);
    }

    #[test]
    fn gap_bps_clamps_large_negative_values() {
        assert_eq!(gap_bps(i64::MAX, 1), i32::MIN);
    }

    #[test]
    fn penalizes_small_sample_sizes_even_when_accuracy_is_good() {
        let single_lucky_call = build_analyst_score(&[AnalystOutcomeSample {
            target_error_bps: 50,
            direction_correct: true,
            age_days: 3,
        }])
        .expect("samples should produce a score");
        let repeated_accuracy = build_analyst_score(&[
            AnalystOutcomeSample {
                target_error_bps: 50,
                direction_correct: true,
                age_days: 3,
            },
            AnalystOutcomeSample {
                target_error_bps: 60,
                direction_correct: true,
                age_days: 10,
            },
            AnalystOutcomeSample {
                target_error_bps: 55,
                direction_correct: true,
                age_days: 20,
            },
            AnalystOutcomeSample {
                target_error_bps: 65,
                direction_correct: true,
                age_days: 35,
            },
        ])
        .expect("samples should produce a score");

        assert!(repeated_accuracy.value > single_lucky_call.value);
    }

    #[test]
    fn prefers_company_score_when_it_has_enough_samples() {
        let selected = select_analyst_score(
            Some(analyst_score(0.76, 6)),
            Some(analyst_score(0.58, 20)),
            5,
        )
        .expect("one score should be selected");

        assert_eq!(
            (selected.scope, selected.analyst_score),
            (
                AnalystScoreScope::Company,
                AnalystScore {
                    value: 0.76,
                    sample_count: 6,
                }
            )
        );
    }

    #[test]
    fn falls_back_to_global_score_when_company_history_is_too_thin() {
        let selected = select_analyst_score(
            Some(analyst_score(0.81, 2)),
            Some(analyst_score(0.61, 18)),
            5,
        )
        .expect("one score should be selected");

        assert_eq!(
            (selected.scope, selected.analyst_score),
            (
                AnalystScoreScope::Global,
                AnalystScore {
                    value: 0.61,
                    sample_count: 18,
                }
            )
        );
    }

    #[test]
    fn clamps_weighted_target_to_reported_forecast_band() {
        let mut state = TerminalState::new(2_000, 30, 8);

        state.symbols.insert(
            "SMCI".to_string(),
            SymbolState {
                snapshot: Some(MarketSnapshot {
                    symbol: "SMCI".to_string(),
                    company_name: None,
                    profitable: true,
                    market_price_cents: 17_270,
                    intrinsic_value_cents: 26_923,
                }),
                external_signal: Some(ExternalValuationSignal {
                    symbol: "SMCI".to_string(),
                    fair_value_cents: 26_923,
                    age_seconds: 6,
                    low_fair_value_cents: Some(18_500),
                    high_fair_value_cents: Some(32_000),
                    analyst_opinion_count: Some(42),
                    recommendation_mean_hundredths: Some(185),
                    strong_buy_count: Some(20),
                    buy_count: Some(10),
                    hold_count: Some(8),
                    sell_count: Some(3),
                    strong_sell_count: Some(1),
                    weighted_fair_value_cents: Some(40_000),
                    weighted_analyst_count: Some(12),
                }),
                fundamentals: None,
                last_sequence: 1,
                update_count: 1,
                price_history: VecDeque::new(),
            },
        );

        state.symbols.insert(
            "LOW".to_string(),
            SymbolState {
                snapshot: Some(MarketSnapshot {
                    symbol: "LOW".to_string(),
                    company_name: None,
                    profitable: true,
                    market_price_cents: 17_270,
                    intrinsic_value_cents: 26_923,
                }),
                external_signal: Some(ExternalValuationSignal {
                    symbol: "LOW".to_string(),
                    fair_value_cents: 26_923,
                    age_seconds: 6,
                    low_fair_value_cents: Some(18_500),
                    high_fair_value_cents: Some(32_000),
                    analyst_opinion_count: Some(42),
                    recommendation_mean_hundredths: Some(185),
                    strong_buy_count: Some(20),
                    buy_count: Some(10),
                    hold_count: Some(8),
                    sell_count: Some(3),
                    strong_sell_count: Some(1),
                    weighted_fair_value_cents: Some(10_000),
                    weighted_analyst_count: Some(12),
                }),
                fundamentals: None,
                last_sequence: 1,
                update_count: 1,
                price_history: VecDeque::new(),
            },
        );

        assert_eq!(
            (
                state.detail("SMCI").map(|detail| (
                    detail.weighted_external_signal_fair_value_cents,
                    detail.weighted_analyst_count,
                )),
                state.detail("LOW").map(|detail| (
                    detail.weighted_external_signal_fair_value_cents,
                    detail.weighted_analyst_count,
                )),
            ),
            (
                Some((Some(32_000), Some(12))),
                Some((Some(18_500), Some(12))),
            )
        );
    }
}
