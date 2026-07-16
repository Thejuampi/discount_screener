import { invoke } from "@tauri-apps/api/core";

export type ConfidenceBand = "Low" | "Provisional" | "High";
export type QualificationStatus = "Qualified" | "Unprofitable" | "GapTooSmall";
export type ExternalSignalStatus = "Missing" | "Stale" | "Supportive" | "Divergent";
export type AlertKind = "EnteredQualified" | "ExitedQualified" | "ConfidenceUpgraded";
export type Decision = "Act" | "Watch" | "Avoid";

export interface OpportunityRow {
  symbol: string;
  company_name: string | null;
  market_price_cents: number;
  intrinsic_value_cents: number;
  gap_bps: number;
  qualification: QualificationStatus;
  confidence: ConfidenceBand;
  signal_status: ExternalSignalStatus;
  analyst_opinion_count: number | null;
  recommendation_mean_hundredths: number | null;
  sector_name: string | null;
  // AggressiveV2 scoring
  fundamentals_score: number | null;
  technical_score: number | null;
  forecast_score: number | null;
  composite_score: number;
  decision: Decision;
  fundamentals_signals: string[];
  technical_signals: string[];
  forecast_signals: string[];
  dcf_value_cents: number | null;
  insider_net_shares_90d: number | null;
  insider_buy_count: number | null;
  insider_sell_count: number | null;
  asset_type: "stock" | "crypto" | "etf";
  setup_score: number;       // -100..+100
  setup_label: SetupLabel;
  daily_change_bps: number | null;
  atr_cents: number | null;  // 14-day ATR (volatility) for stop & sizing
  next_earnings_epoch: number | null;
  spark: number[];           // recent daily closes (cents) for inline sparkline
}

export type AssetType = "stock" | "crypto" | "etf";
export type SetupLabel =
  | "StrongBuy" | "Buy" | "Accumulate" | "Watch" | "Hold" | "Avoid" | "StrongAvoid"
  // Crypto-specific labels (override the equity ones for crypto symbols)
  | "StrongAccumulate" | "HoldWait" | "Neutral" | "Caution" | "Distribute";

export interface NewsItem {
  title: string;
  link: string;
  published_at: string;
  published_epoch: number;
  source: string | null;
  sentiment_score: number;
}

export interface NewsBundle {
  items: NewsItem[];
  aggregate_sentiment: number;
  positive_count: number;
  negative_count: number;
  neutral_count: number;
  fetched_at: number;
}

export interface SymbolDetail {
  symbol: string;
  company_name: string | null;
  market_price_cents: number;
  intrinsic_value_cents: number;
  gap_bps: number;
  qualification: QualificationStatus;
  confidence: ConfidenceBand;
  signal_status: ExternalSignalStatus;
  signal_age_seconds: number | null;
  low_fair_value_cents: number | null;
  high_fair_value_cents: number | null;
  analyst_opinion_count: number | null;
  recommendation_mean_hundredths: number | null;
  strong_buy_count: number | null;
  buy_count: number | null;
  hold_count: number | null;
  sell_count: number | null;
  strong_sell_count: number | null;
  fundamentals: {
    sector_name: string | null;
    industry_name: string | null;
    market_cap_dollars: number | null;
    trailing_pe_hundredths: number | null;
    forward_pe_hundredths: number | null;
    return_on_equity_bps: number | null;
    debt_to_equity_hundredths: number | null;
    free_cash_flow_dollars: number | null;
    operating_cash_flow_dollars: number | null;
    beta_millis: number | null;
    trailing_eps_cents: number | null;
    earnings_growth_bps: number | null;
    total_debt_dollars: number | null;
    total_cash_dollars: number | null;
    ebitda_dollars: number | null;
  };
  chart_summary: ChartSummary | null;
  weekly_summary: ChartSummary | null;
  hourly_summary: ChartSummary | null;
  monthly_summary: ChartSummary | null;
  technical_breakdown: TechnicalBreakdown | null;
  dcf_value_cents: number | null;
  insider_net_shares_90d: number | null;
  insider_buy_count: number | null;
  insider_sell_count: number | null;
  next_earnings_epoch: number | null;
  chart_patterns: ChartPattern[];
  fib: FibAnalysis | null;
}

export interface ChartSummary {
  latest_close_cents: number;
  ema20_cents: number | null;
  ema50_cents: number | null;
  ema200_cents: number | null;
  macd_cents: number | null;
  signal_cents: number | null;
  histogram_cents: number | null;
  rsi: number | null;
  adx: number | null;
  plus_di: number | null;
  minus_di: number | null;
  bb_upper_cents: number | null;
  bb_middle_cents: number | null;
  bb_lower_cents: number | null;
  bb_percent_b: number | null;
  bb_bandwidth: number | null;
  obv_slope: number | null;
  volume_ratio: number | null;
  atr_cents: number | null;
  high_52w_cents: number | null;
  low_52w_cents: number | null;
  pos_52w_pct: number | null;
}

export type TrendState = "Bullish" | "Bearish" | "Neutral" | "Unknown";
export type TfAlignment = "BullStack" | "BearStack" | "Mixed" | "Unknown";
export type PatternBias = "Bullish" | "Bearish" | "Neutral";

export interface DetectedPattern {
  name: string;
  bias: PatternBias;
  bars_ago: number;
}

export interface SupportResistance {
  supports_cents: number[];
  resistances_cents: number[];
}

export type DivergenceKind = "RegularBullish" | "RegularBearish" | "HiddenBullish" | "HiddenBearish";

export interface Divergence {
  kind: DivergenceKind;
  label: string;
  bias: "Bullish" | "Bearish";
  bars_ago: number;
  strength: number;
  price_at_p1: number;
  price_at_p2: number;
  rsi_at_p1: number;
  rsi_at_p2: number;
}

export interface TechnicalBreakdown {
  trend_score: number | null;
  momentum_score: number | null;
  volatility_score: number | null;
  volume_score: number | null;
  pattern_score: number | null;
  alignment: TfAlignment;
  weekly_trend: TrendState;
  daily_trend: TrendState;
  hourly_trend: TrendState;
  patterns: DetectedPattern[];
  levels: SupportResistance;
  divergences: Divergence[];
}

export interface Candle {
  epoch_seconds: number;
  open_cents: number;
  high_cents: number;
  low_cents: number;
  close_cents: number;
  volume: number;
}

export interface AlertEvent {
  symbol: string;
  kind: AlertKind;
  timestamp_seconds: number;
}

export interface FeedStatus {
  running: boolean;
  symbols_loaded: number;
  symbols_total: number;
  last_error: string | null;
}

export interface HistorySnapshot {
  symbol: string;
  captured_at: number;
  market_price_cents: number;
  intrinsic_value_cents: number;
  gap_bps: number;
  decision: Decision;
  composite_score: number;
  fundamentals_score: number | null;
  technical_score: number | null;
  forecast_score: number | null;
  confidence: ConfidenceBand;
}

export interface BacktestEntry {
  symbol: string;
  entry_price_cents: number;
  entry_at: number;
  current_price_cents: number;
  return_bps: number;
}

export interface BacktestResult {
  decision: Decision;
  days_ago: number;
  sample_size: number;
  mean_return_bps: number;
  median_return_bps: number;
  win_rate_pct: number;
  top_winners: BacktestEntry[];
  top_losers: BacktestEntry[];
}

export interface HistoryStatus {
  snapshot_count: number;
}

export interface TickerSearchSuggestion {
  symbol: string;
  company_name: string | null;
  profiles: string[];
  in_current_profile: boolean;
  exchange: string | null;
  is_remote: boolean;
  match_rank: number;
}

export type SearchSubmitOutcome =
  | { kind: "open"; symbol: string }
  | { kind: "pick_match" }
  | { kind: "unavailable" };

export const api = {
  getOpportunities: () => invoke<OpportunityRow[]>("get_opportunities"),
  getSymbolDetail: (symbol: string) => invoke<SymbolDetail | null>("get_symbol_detail", { symbol }),
  getCandles: (symbol: string, range: string) => invoke<Candle[]>("get_candles", { symbol, range }),
  getAlerts: () => invoke<AlertEvent[]>("get_alerts"),
  refreshSymbol: (symbol: string) => invoke<string>("refresh_symbol", { symbol }),
  searchTickers: (query: string, limit?: number) =>
    invoke<TickerSearchSuggestion[]>("search_tickers", { query, limit: limit ?? 8 }),
  resolveTickerSearchSubmit: (query: string, suggestions: TickerSearchSuggestion[]) =>
    invoke<SearchSubmitOutcome>("resolve_ticker_search_submit", { query, suggestions }),
  ensureSymbolLoaded: (symbol: string) => invoke<string>("ensure_symbol_loaded", { symbol }),
  startFeed: () => invoke<void>("start_feed"),
  getFeedStatus: () => invoke<FeedStatus>("get_feed_status"),
  getSymbolHistory: (symbol: string, days: number) =>
    invoke<HistorySnapshot[]>("get_symbol_history", { symbol, days }),
  getBacktest: (decision: Decision, daysAgo: number) =>
    invoke<BacktestResult>("get_backtest", { decision, daysAgo }),
  getHistoryStatus: () => invoke<HistoryStatus>("get_history_status"),
  getAutostartEnabled: () => invoke<boolean>("get_autostart_enabled"),
  setAutostartEnabled: (enabled: boolean) => invoke<void>("set_autostart_enabled", { enabled }),
  quitApp: () => invoke<void>("quit_app"),
  getNews: (symbol: string) => invoke<NewsBundle>("get_news", { symbol }),
  importSchwabPdf: (bytes: number[], filename: string | null) =>
    invoke<SchwabReport>("import_schwab_pdf", { bytes, filename }),
  getSchwabReport: (symbol: string) => invoke<SchwabReport | null>("get_schwab_report", { symbol }),
  countSchwabReports: () => invoke<number>("count_schwab_reports"),
  deleteSchwabReport: (symbol: string) => invoke<void>("delete_schwab_report", { symbol }),

  // Congress Alpha
  getCongressOverview: (days?: number) =>
    invoke<CongressOverview>("get_congress_overview", { days: days ?? 180 }),
  getCongressTradesForSymbol: (symbol: string, limit?: number) =>
    invoke<CongressTrade[]>("get_congress_trades_for_symbol", { symbol, limit: limit ?? 20 }),
  syncCongressHouse: (years: number[], maxPerYear?: number) =>
    invoke<boolean>("sync_congress_house", { years, maxPerYear }),
  getCongressSyncProgress: () =>
    invoke<CongressSyncProgress>("get_congress_sync_progress"),
  computeCongressMetrics: () => invoke<CongressBacktestResult>("compute_congress_metrics"),
  getTopPoliticiansRanked: (sortKey: string, limit?: number) =>
    invoke<PoliticianWithMetrics[]>("get_top_politicians_ranked", { sortKey, limit: limit ?? 50 }),
  getPoliticianDetail: (politicianId: number) =>
    invoke<[PoliticianWithMetrics | null, PoliticianTradeRow[]]>("get_politician_detail", { politicianId }),
  getCryptoMetrics: (symbol: string) =>
    invoke<CryptoMetrics>("get_crypto_metrics", { symbol }),

  // Portfolio / Advisor
  portfolioList: () => invoke<PortfolioPosition[]>("portfolio_list"),
  portfolioAdd: (symbol: string, quantity: number, avgCostCents: number, openedAt: string | null, notes: string | null) =>
    invoke<number>("portfolio_add", { symbol, quantity, avgCostCents, openedAt, notes }),
  portfolioUpdate: (id: number, quantity: number, avgCostCents: number, openedAt: string | null, notes: string | null) =>
    invoke<void>("portfolio_update", { id, quantity, avgCostCents, openedAt, notes }),
  portfolioDelete: (id: number) => invoke<void>("portfolio_delete", { id }),
  portfolioImport: (positions: ImportPosition[]) =>
    invoke<PortfolioImportResult>("portfolio_import", { positions }),
  getQuotePrices: (symbols: string[]) =>
    invoke<Record<string, number>>("get_quote_prices", { symbols }),
  getModelAccuracy: (horizonDays: number) =>
    invoke<AccuracyRow[]>("get_model_accuracy", { horizonDays }),
  getPortfolioRisk: (symbols: string[]) =>
    invoke<PortfolioRiskResponse>("get_portfolio_risk", { symbols }),
  getMarketRegime: () => invoke<MarketRegime>("get_market_regime"),
  getPriceProvenance: (symbol: string) =>
    invoke<PriceProvenance>("get_price_provenance", { symbol }),

  // Schwab connection (OAuth + market data)
  schwabStatus: () => invoke<SchwabStatus>("schwab_status"),
  schwabSetCredentials: (appKey: string, secret: string, callback: string) =>
    invoke<void>("schwab_set_credentials", { appKey, secret, callback }),
  schwabAuthUrl: () => invoke<string>("schwab_auth_url"),
  schwabCompleteAuth: (redirectUrl: string) =>
    invoke<void>("schwab_complete_auth", { redirectUrl }),
  schwabDisconnect: () => invoke<void>("schwab_disconnect"),

  // Email notifications
  emailConfigGet: () => invoke<EmailConfigView>("email_config_get"),
  emailConfigSet: (c: {
    smtpHost: string; smtpPort: number; username: string; password: string | null;
    fromEmail: string; toEmail: string; enabled: boolean;
    dailyDigest: boolean; digestHour: number; instantAlerts: boolean;
  }) => invoke<void>("email_config_set", c),
  emailSend: (subject: string, html: string, text: string) =>
    invoke<void>("email_send", { subject, html, text }),
  emailMarkDigestSent: (date: string) => invoke<void>("email_mark_digest_sent", { date }),

  // Crypto scalping
  getScalpCandles: (product: string, timeframe: string) =>
    invoke<Candle[]>("get_scalp_candles", { product, timeframe }),
  getScalpAnalysis: (product: string, rr: number, feePct: number) =>
    invoke<ScalpAnalysis>("get_scalp_analysis", { product, rr, feePct }),
  scalpWsSubscribe: (product: string) => invoke<void>("scalp_ws_subscribe", { product }),

  // Investment journal
  journalList: () => invoke<JournalEntry[]>("journal_list"),
  journalAdd: (e: {
    symbol: string; action: string; thesis: string | null;
    priceCents: number | null; setupScore: number | null; setupLabel: string | null;
  }) => invoke<number>("journal_add", e),
  journalClose: (id: number, outcome: string | null, exitPriceCents: number | null) =>
    invoke<void>("journal_close", { id, outcome, exitPriceCents }),
  journalDelete: (id: number) => invoke<void>("journal_delete", { id }),
};

export interface JournalEntry {
  id: number;
  symbol: string;
  action: string;
  thesis: string | null;
  price_cents: number | null;
  setup_score: number | null;
  setup_label: string | null;
  created_at: number;
  outcome: string | null;
  exit_price_cents: number | null;
  closed_at: number | null;
}

// ── Crypto scalping ─────────────────────────────────────────────────────────

export interface TimeframeAnalysis {
  tf: string;
  close_cents: number;
  trend: "Bull" | "Bear" | "Neutral";
  ema9: number | null;
  ema21: number | null;
  ema50: number | null;
  ema200: number | null;
  supertrend_dir: number; // 1 up, -1 down, 0 n/a
  rsi: number | null;
  stoch_rsi: number | null;
  macd_hist_cents: number | null;
  vwap_cents: number | null;
  atr_cents: number | null;
  structure: "HH-HL" | "LH-LL" | "Mixed";
}

export interface ScalpScore {
  trend: number;
  momentum: number;
  volume: number;
  structure: number;
  risk: number;
  total: number;
  label: "No-Trade" | "Weak" | "Strong" | "High-Conviction";
  bias: "Long" | "Short" | "Neutral";
}

export interface ScalpSignal {
  side: "LONG" | "SHORT" | "NONE";
  entry_low_cents: number;
  entry_high_cents: number;
  stop_cents: number;
  tp1_cents: number;
  tp2_cents: number;
  risk_reward: number;
  confidence: number;
  fee_pct: number;
  round_trip_fee_pct: number;
  tp1_gross_pct: number;
  tp1_net_pct: number;
  tp2_net_pct: number;
  fee_viable: boolean;
  reasons: string[];
}

export interface SmcZone {
  kind: "BullishFVG" | "BearishFVG" | "BullishOB" | "BearishOB";
  low_cents: number;
  high_cents: number;
  at_epoch: number;
}

export interface SmcEvent {
  kind: "BOS" | "CHoCH" | "LiquiditySweep";
  direction: "Bullish" | "Bearish";
  price_cents: number;
  at_epoch: number;
}

export interface VolumeProfile {
  poc_cents: number;
  vah_cents: number;
  val_cents: number;
}

export interface SmcAnalysis {
  tf: string;
  structure: "Bullish" | "Bearish" | "Range";
  events: SmcEvent[];
  fvgs: SmcZone[];
  order_blocks: SmcZone[];
  squeeze: boolean;
  expansion: boolean;
  volume_profile: VolumeProfile | null;
}

export interface PatternPoint { epoch: number; price_cents: number }
export interface PatternLine { points: PatternPoint[] }

export interface ChartPattern {
  kind: string;
  direction: "Bullish" | "Bearish" | "Neutral";
  confidence: number;
  key_level_cents: number;
  target_cents: number;
  label_es: string;
  label_en: string;
  forming: boolean;
  timeframe: string;
  explanation_es: string;
  explanation_en: string;
  lines: PatternLine[];
}

export interface FibLevel {
  ratio: number;
  price_cents: number;
  kind: "anchor" | "retracement" | "extension";
}

export interface FibAnalysis {
  direction: "Up" | "Down";
  timeframe: string;
  swing_high_cents: number;
  swing_low_cents: number;
  swing_high_epoch: number;
  swing_low_epoch: number;
  levels: FibLevel[];
}

export interface ScalpTick {
  product: string;
  price_cents: number;
  bid_cents: number;
  ask_cents: number;
}

export interface ScalpAnalysis {
  product: string;
  timeframes: TimeframeAnalysis[];
  score: ScalpScore;
  signal: ScalpSignal;
  smc: SmcAnalysis;
  patterns: ChartPattern[];
  fib: FibAnalysis | null;
  generated_at: number;
}

// ── Email notifications ─────────────────────────────────────────────────────

export interface EmailConfigView {
  smtp_host: string | null;
  smtp_port: number | null;
  username: string | null;
  from_email: string | null;
  to_email: string | null;
  has_password: boolean;
  enabled: boolean;
  daily_digest: boolean;
  digest_hour: number;
  instant_alerts: boolean;
  last_digest_date: string | null;
}

// ── Schwab connection ───────────────────────────────────────────────────────

export interface SchwabStatus {
  configured: boolean;
  connected: boolean;
  needs_reauth: boolean;
  access_valid_until: number | null;
  refresh_valid_until: number | null;
  callback: string | null;
}

// ── Data provenance ─────────────────────────────────────────────────────────

export interface PriceProvenance {
  symbol: string;
  schwab_cents: number | null;
  yahoo_cents: number | null;
  stooq_cents: number | null;
  consensus_cents: number | null;
  spread_bps: number | null;
  agree: boolean;
  sources_ok: number;
}

// ── Market regime types ─────────────────────────────────────────────────────

export type RegimeKind = "RiskOn" | "Neutral" | "RiskOff" | "Unknown";

export interface MarketRegime {
  regime: RegimeKind;
  suggested_exposure_pct: number;
  vix: number | null;
  vix_state: "Calm" | "Normal" | "Elevated" | "Fear" | "Unknown";
  breadth_above_ma200_pct: number | null;
  breadth_above_ma50_pct: number | null;
  breadth_sample: number;
  spy_above_ma200: boolean | null;
  spy_price_cents: number | null;
  spy_ma200_cents: number | null;
  notes_es: string[];
  notes_en: string[];
}

// ── Risk engine types ───────────────────────────────────────────────────────

export interface SymbolRisk {
  symbol: string;
  last_close_cents: number;
  atr_cents: number | null;
  atr_pct_bps: number | null; // ATR as bps of price (daily volatility proxy)
}

export interface CorrPair {
  a: string;
  b: string;
  corr_milli: number; // Pearson × 1000, -1000..1000
}

export interface PortfolioRiskResponse {
  per_symbol: SymbolRisk[];
  correlation: CorrPair[];
  high_corr_pairs: CorrPair[]; // |corr| >= 0.70
  lookback_days: number;
}

export interface ImportPosition {
  symbol: string;
  quantity: number;
  avg_cost_cents: number;
  opened_at: string | null;
}

export interface PortfolioImportResult {
  created: number;
  updated: number;
  skipped: number;
}

export interface PortfolioPosition {
  id: number;
  symbol: string;
  quantity: number;
  avg_cost_cents: number;
  opened_at: string | null;
  notes: string | null;
}

export interface AccuracyRow {
  bucket_type: "decision" | "score";
  bucket: string;
  samples: number;
  avg_return_bps: number;
  win_rate_pct: number;
}

export interface FearGreed {
  value: number;
  classification: string;
  fetched_at_epoch: number;
}

export type CyclePhase =
  | "PostHalvingExpansion" | "BullRun" | "BearMarket"
  | "AccumulationZone" | "PreHalvingAccumulation";

export interface CryptoMetrics {
  symbol: string;
  days_since_last_halving: number;
  days_until_next_halving_est: number;
  phase: CyclePhase;
  phase_label_es: string;
  phase_label_en: string;
  ath_price_cents: number;
  current_price_cents: number;
  drawdown_from_ath_pct: number;
  drawdown_zone: string;
  fear_greed: FearGreed | null;
  technical_component: number | null;
  accumulation_component: number;
  halving_component: number;
  sentiment_component: number;
  crypto_score: number;
  crypto_label: SetupLabel;
  explanation_es: string;
  explanation_en: string;
}

// ── Congress Alpha types ────────────────────────────────────────────────────

export interface CongressTickerRow {
  symbol: string;
  buy_count: number;
  sell_count: number;
  unique_politicians: number;
  last_disclosure_date: string | null;
  total_amount_min: number;
  total_amount_max: number;
}

export interface PoliticianActivityRow {
  politician_id: number;
  full_name: string;
  chamber: string;
  state: string | null;
  district: string | null;
  trade_count: number;
  buy_count: number;
  sell_count: number;
  last_disclosure_date: string | null;
}

export interface CongressOverview {
  politician_count: number;
  trade_count: number;
  top_tickers: CongressTickerRow[];
  top_politicians: PoliticianActivityRow[];
}

export interface CongressTrade {
  trade_id: number;
  politician_name: string;
  chamber: string;
  state: string | null;
  district: string | null;
  owner: string | null;
  asset_name: string;
  symbol: string | null;
  asset_type: string | null;
  transaction_type: string;
  transaction_date: string | null;
  disclosure_date: string | null;
  amount_range_min: number | null;
  amount_range_max: number | null;
}

export interface CongressSyncResult {
  year: number;
  ptr_count: number;
  processed: number;
  skipped: number;
  failed: number;
  trades_imported: number;
  errors_sample: string[];
}

export interface CongressSyncProgress {
  running: boolean;
  current_year: number;
  current_step: string;
  processed: number;
  total: number;
  trades_imported: number;
  years_completed: number[];
  total_imported_session: number;
  last_error: string | null;
}

export interface CongressBacktestResult {
  symbols_processed: number;
  trades_with_outcomes: number;
  politicians_updated: number;
  errors_sample: string[];
}

export interface PoliticianWithMetrics {
  politician_id: number;
  full_name: string;
  chamber: string;
  state: string | null;
  district: string | null;
  total_trades: number;
  purchase_count: number;
  sale_count: number;
  avg_return_30d_bps: number | null;
  avg_return_90d_bps: number | null;
  avg_return_180d_bps: number | null;
  win_rate_30d_pct: number | null;
  win_rate_90d_pct: number | null;
  win_rate_180d_pct: number | null;
  avg_alpha_90d_bps: number | null;
  avg_alpha_180d_bps: number | null;
  estimated_total_gain_cents: number;
  confidence_score: number;
  qualifying_trades: number;
}

export interface PoliticianTradeRow {
  trade_id: number;
  symbol: string | null;
  asset_name: string;
  owner: string | null;
  transaction_type: string;
  transaction_date: string | null;
  disclosure_date: string | null;
  amount_range_min: number | null;
  amount_range_max: number | null;
  return_30d_bps: number | null;
  return_90d_bps: number | null;
  return_180d_bps: number | null;
  estimated_gain_cents: number | null;
}

export interface SchwabReport {
  symbol: string;
  company_name: string | null;
  exchange: string | null;
  rating: "A" | "B" | "C" | "D" | "F";
  rating_label: string;
  percentile: number | null;
  previous_rating: string | null;
  report_date: string | null;
  data_as_of: string | null;
  price_at_report_cents: number | null;
  market_cap_billions: number | null;
  beta: number | null;
  sector: string | null;
  industry: string | null;
  price_volatility: string | null;
  growth_grade: string | null;
  quality_grade: string | null;
  sentiment_grade: string | null;
  stability_grade: string | null;
  valuation_grade: string | null;
  eps_forecast_y1: number | null;
  eps_forecast_y2: number | null;
  eps_growth_5yr_pct: number | null;
  esg_rating: string | null;
  cfra_stars: number | null;
  morningstar_stars: number | null;
  source_filename: string | null;
  imported_at_epoch: number;
}

// Formatting helpers
export const fmt = {
  dollars: (cents: number) => `$${(cents / 100).toFixed(2)}`,
  gap: (bps: number) => bps <= -2_000_000_000 ? "—" : `${(bps / 100).toFixed(1)}%`,
  pe: (hundredths: number | null) => hundredths ? `${(hundredths / 100).toFixed(1)}x` : "—",
  roe: (bps: number | null) => bps ? `${(bps / 100).toFixed(1)}%` : "—",
  billions: (dollars: number | null) => {
    if (!dollars) return "—";
    if (Math.abs(dollars) >= 1e9) return `$${(dollars / 1e9).toFixed(1)}B`;
    if (Math.abs(dollars) >= 1e6) return `$${(dollars / 1e6).toFixed(0)}M`;
    return `$${dollars.toFixed(0)}`;
  },
  recMean: (hundredths: number | null) => {
    if (!hundredths) return "—";
    const v = hundredths / 100;
    if (v <= 1.5) return "Strong Buy";
    if (v <= 2.5) return "Buy";
    if (v <= 3.5) return "Hold";
    if (v <= 4.5) return "Sell";
    return "Strong Sell";
  },
};
