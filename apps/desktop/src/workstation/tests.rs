mod tests {
    use super::aggregate_historical_candles;
    use super::analysis_input_key;
    use super::analyst_consensus_lines;
    use super::apply_feed_events;
    use super::apply_live_source_status;
    use super::apply_persistence_status;
    use super::build_opportunity_rows;
    use super::build_screen_lines;
    use super::build_screen_lines_for_viewport;
    use super::build_symbol_feed_batch;
    use super::build_ticker_detail_lines;
    use super::build_ticker_detail_lines_for_viewport;
    use super::build_ticker_history_lines_for_viewport;
    use super::candidate_company_label;
    use super::candidate_row_color;
    use super::chart_loop_with_client_factory;
    use super::chart_range_label;
    use super::chart_ranges;
    use super::clip_text_to_width;
    use super::collect_clear_rows;
    use super::collect_dirty_rows;
    use super::compute_dcf_analysis;
    use super::compute_ema_series;
    use super::compute_macd_series;
    use super::compute_sector_relative_score;
    use super::compute_volume_profile;
    use super::compute_volume_profile_from_price_candles;
    use super::confidence_justification_lines;
    use super::dcf_margin_of_safety_bps;
    use super::dcf_signal;
    use super::derive_base_growth_bps;
    use super::detail_analysis_snapshot;
    use super::export_selected_history_bundle;
    use super::feed_loop_with_client_factory;
    use super::filtered_symbol_rows;
    use super::format_bps;
    use super::format_compact_dollars;
    use super::format_money;
    use super::format_symbol_list;
    use super::gap_meter;
    use super::handle_input_event;
    use super::handle_overlay_key;
    use super::health_status_label;
    use super::input_prompt;
    use super::is_provider_throttle_error;
    use super::is_retryable_feed_error;
    use super::load_initial_state;
    use super::market_data::AnnualReportedValue;
    use super::market_data::ChartRange;
    use super::market_data::FundamentalTimeseries;
    use super::market_data::FundamentalTimeseriesSource;
    use super::market_data::HistoricalCandle;
    use super::next_weighted_target_refresh_cursor;
    use super::normalize_frame;
    use super::opportunity_window_bounds;
    use super::parse_runtime_options_from;
    use super::parse_symbols_argument;
    use super::persistence;
    use super::publish_feed_refresh;
    use super::publish_feed_refresh_concurrently;
    use super::publish_input_events;
    use super::qualification_justification_lines;
    use super::reconcile_capture_persistence;
    use super::reconcile_journal_persistence;
    use super::reconcile_sqlite_persistence;
    use super::relative_metric_score;
    use super::render_volume_profile_cells;
    use super::reorder_symbols_by_persisted_ranking;
    use super::robust_composite_percentile;
    use super::score_opportunity_forecasts;
    use super::score_opportunity_fundamentals;
    use super::score_opportunity_technicals;
    use super::score_opportunity_with_model;
    use super::should_handle_key_event;
    use super::should_leave_input_mode_on_backspace;
    use super::should_refresh_weighted_target;
    use super::summarize_chart_range;
    use super::usage_text;
    use super::visible_text;
    use super::AnalysisCacheEntry;
    use super::AnalysisControl;
    use super::AnalysisInputKey;
    use super::AppEvent;
    use super::AppEventPublisher;
    use super::AppState;
    use super::Color;
    use super::DcfAnalysis;
    use super::DcfSignal;
    use super::Event;
    use super::FeedErrorLogger;
    use super::FeedEvent;
    use super::FeedRefreshPlan;
    use super::HistoryMetricGroup;
    use super::HistorySubview;
    use super::HistoryWindow;
    use super::InputMode;
    use super::IssueCenter;
    use super::IssueSeverity;
    use super::IssueSource;
    use super::KeyCode;
    use super::KeyEvent;
    use super::KeyEventKind;
    use super::KeyModifiers;
    use super::LiveSourceStatus;
    use super::LiveSymbolState;
    use super::LoopControl;
    use super::OverlayMode;
    use super::PersistenceStatusEvent;
    use super::PrimaryViewMode;
    use super::RelativeMetricScore;
    use super::RelativeStrengthBand;
    use super::RenderLine;
    use super::RuntimeOptions;
    use super::SymbolCoverageEvent;
    use super::VolumeProfileBin;
    use super::CANDIDATE_COMPANY_COLUMN_WIDTH;
    use super::ISSUE_KEY_JOURNAL_RESTORE;
    use super::ISSUE_KEY_WATCHLIST_RESTORE;
    use super::MAX_VISIBLE_ROWS;
    use crate::detail_layout;
    use crate::unix_timestamp_seconds;
    use crate::ChartCacheKey;
    use crate::ChartRangeSummary;
    use crate::OpportunityScoringModel;
    use crate::PriceCandle;
    use crate::SectorRelativeScore;
    use crate::BACKGROUND_CHART_REQUEST_BUDGET_PER_CYCLE;
    use crate::DETAIL_CHART_AXIS_WIDTH;
    use crate::DETAIL_CHART_ROW_PADDING;
    use crate::DETAIL_VOLUME_PROFILE_WIDTH;
    use discount_screener::checked_gap_bps;
    use discount_screener::CandidateRow;
    use discount_screener::ConfidenceBand;
    use discount_screener::ExternalSignalStatus;
    use discount_screener::ExternalValuationSignal;
    use discount_screener::FundamentalSnapshot;
    use discount_screener::MarketSnapshot;
    use discount_screener::QualificationStatus;
    use discount_screener::SymbolDetail;
    use discount_screener::TerminalState;
    use discount_screener::ViewFilter;
    use rusqlite::Connection;
    use serde::Deserialize;
    use std::collections::HashSet;
    use std::collections::VecDeque;
    use std::env::temp_dir;
    use std::error::Error as _;
    use std::fs;
    use std::io;
    use std::path::PathBuf;
    use std::sync::mpsc;
    use std::sync::Arc;
    use std::sync::Mutex;
    use std::time::Duration;
    use std::time::SystemTime;
    use std::time::UNIX_EPOCH;

    fn candidate(symbol: &str, gap_bps: i32) -> CandidateRow {
        CandidateRow {
            symbol: symbol.to_string(),
            market_price_cents: 8_000,
            intrinsic_value_cents: 10_000,
            gap_bps,
            is_qualified: true,
            confidence: ConfidenceBand::Provisional,
        }
    }

    fn external_signal(
        symbol: &str,
        fair_value_cents: i64,
        weighted_fair_value_cents: Option<i64>,
        analyst_opinion_count: Option<u32>,
        recommendation_mean_hundredths: Option<u16>,
    ) -> ExternalValuationSignal {
        ExternalValuationSignal {
            symbol: symbol.to_string(),
            fair_value_cents,
            age_seconds: 0,
            low_fair_value_cents: None,
            high_fair_value_cents: None,
            analyst_opinion_count,
            recommendation_mean_hundredths,
            strong_buy_count: None,
            buy_count: None,
            hold_count: None,
            sell_count: None,
            strong_sell_count: None,
            weighted_fair_value_cents,
            weighted_analyst_count: analyst_opinion_count,
        }
    }

    fn fundamentals_with(
        symbol: &str,
        free_cash_flow_dollars: Option<i64>,
        operating_cash_flow_dollars: Option<i64>,
        return_on_equity_bps: Option<i32>,
        debt_to_equity_hundredths: Option<i32>,
        total_cash_dollars: Option<i64>,
        total_debt_dollars: Option<i64>,
        earnings_growth_bps: Option<i32>,
    ) -> FundamentalSnapshot {
        FundamentalSnapshot {
            symbol: symbol.to_string(),
            sector_key: Some("tech".to_string()),
            sector_name: Some("Technology".to_string()),
            industry_key: Some("software".to_string()),
            industry_name: Some("Software".to_string()),
            market_cap_dollars: Some(1_000_000_000),
            shares_outstanding: Some(100_000_000),
            trailing_pe_hundredths: None,
            forward_pe_hundredths: None,
            price_to_book_hundredths: None,
            return_on_equity_bps,
            ebitda_dollars: None,
            enterprise_value_dollars: None,
            enterprise_to_ebitda_hundredths: None,
            total_debt_dollars,
            total_cash_dollars,
            debt_to_equity_hundredths,
            free_cash_flow_dollars,
            operating_cash_flow_dollars,
            beta_millis: None,
            trailing_eps_cents: None,
            earnings_growth_bps,
        }
    }

    fn year_summary(
        latest_close_cents: i64,
        ema20_cents: Option<i64>,
        ema50_cents: Option<i64>,
        ema200_cents: Option<i64>,
        macd_cents: Option<i64>,
        signal_cents: Option<i64>,
        histogram_cents: Option<i64>,
    ) -> ChartRangeSummary {
        ChartRangeSummary {
            range: ChartRange::Year,
            captured_at: 1_700_000_000,
            candle_count: 52,
            latest_close_cents: Some(latest_close_cents),
            ema20_cents,
            ema50_cents,
            ema200_cents,
            macd_cents,
            signal_cents,
            histogram_cents,
        }
    }

    fn dcf_analysis_fixture(base_intrinsic_value_cents: i64) -> DcfAnalysis {
        DcfAnalysis {
            bear_intrinsic_value_cents: base_intrinsic_value_cents.saturating_sub(500),
            base_intrinsic_value_cents,
            bull_intrinsic_value_cents: base_intrinsic_value_cents.saturating_add(500),
            wacc_bps: 900,
            base_growth_bps: 300,
            net_debt_dollars: 0,
            selected_source: FundamentalTimeseriesSource::YahooFinance,
            source_policy_stage: "DesktopYahooOnly".to_string(),
            source_fingerprint: "DesktopYahooOnly".to_string(),
            decision_fingerprint: "DesktopYahooOnly|state=Selected|sec=DesktopSecDeferred".to_string(),
        }
    }

    fn opportunities_view_lines_for_viewport(
        state: &TerminalState,
        app: &mut AppState,
        viewport_width: usize,
        viewport_height: usize,
    ) -> Vec<String> {
        let rows = app.visible_rows(state);
        let selected_index = app.sync_base_selected_index(state, &rows);
        normalize_frame(
            &build_screen_lines_for_viewport(
                state,
                &rows,
                selected_index,
                0,
                true,
                app,
                None,
                viewport_width,
                viewport_height,
            ),
            viewport_width,
            viewport_height,
        )
        .iter()
        .map(|line| visible_text(&line.text))
        .collect()
    }

    fn opportunities_view_lines_with_selected_index(
        state: &TerminalState,
        app: &AppState,
        selected_index: usize,
        viewport_width: usize,
        viewport_height: usize,
    ) -> Vec<String> {
        let rows = app.visible_rows(state);
        normalize_frame(
            &build_screen_lines_for_viewport(
                state,
                &rows,
                selected_index,
                0,
                true,
                app,
                None,
                viewport_width,
                viewport_height,
            ),
            viewport_width,
            viewport_height,
        )
        .iter()
        .map(|line| visible_text(&line.text))
        .collect()
    }

    fn seed_ranked_opportunities(state: &mut TerminalState, count: usize) -> Vec<String> {
        let mut symbols = Vec::new();
        for index in 0..count {
            let symbol = format!("OP{index:02}");
            state.ingest_snapshot(MarketSnapshot {
                symbol: symbol.clone(),
                company_name: Some(format!("Opportunity {index}")),
                profitable: true,
                market_price_cents: 1_000,
                intrinsic_value_cents: 5_000 - index as i64 * 100,
            });
            symbols.push(symbol);
        }

        symbols
    }

    fn seed_candidates_and_opportunities(state: &mut TerminalState) {
        seed_ranked_opportunities(state, 30);
        state.ingest_external(external_signal(
            "OP00",
            4_800,
            Some(4_900),
            Some(12),
            Some(140),
        ));
        state.ingest_external(external_signal(
            "OP01",
            4_700,
            Some(4_800),
            Some(10),
            Some(150),
        ));
    }

    fn ranked_main_view_lines_for_viewport(
        viewport_width: usize,
        viewport_height: usize,
    ) -> Vec<String> {
        let mut state = TerminalState::new(2_000, 30, 8);
        let live_symbols = LiveSymbolState::new(
            (0..25)
                .map(|index| format!("H{index:02}"))
                .collect::<Vec<_>>(),
        );

        for index in 0..25 {
            let symbol = format!("H{index:02}");
            state.ingest_snapshot(MarketSnapshot {
                symbol: symbol.clone(),
                company_name: Some(format!("Holding {index} Incorporated")),
                profitable: true,
                market_price_cents: 10_000 + index as i64 * 100,
                intrinsic_value_cents: 15_000 + index as i64 * 100,
            });
            state.ingest_external(ExternalValuationSignal {
                symbol,
                fair_value_cents: 13_000,
                age_seconds: 0,
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
            });
        }

        let app = AppState::default();
        let rows = app.visible_rows(&state);
        normalize_frame(
            &build_screen_lines_for_viewport(
                &state,
                &rows,
                0,
                0,
                true,
                &app,
                Some(&live_symbols),
                viewport_width,
                viewport_height,
            ),
            viewport_width,
            viewport_height,
        )
        .iter()
        .map(|line| visible_text(&line.text))
        .collect()
    }

    fn recv_feed_batch(receiver: &mpsc::Receiver<AppEvent>, label: &str) -> Vec<FeedEvent> {
        loop {
            match receiver
                .recv()
                .unwrap_or_else(|_| panic!("{label} should arrive"))
            {
                AppEvent::FeedBatch(feed_events) => return feed_events,
                AppEvent::FeedStatus(_) => continue,
                _ => panic!("expected {label}"),
            }
        }
    }

    fn recv_with_timeout<T>(receiver: &mpsc::Receiver<T>, message: &str) -> T {
        receiver
            .recv_timeout(Duration::from_millis(250))
            .expect(message)
    }

    fn unique_test_path(label: &str) -> PathBuf {
        let unique_suffix = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .expect("system clock should be after the unix epoch")
            .as_nanos();

        temp_dir().join(format!(
            "discount-screener-{label}-{}-{unique_suffix}",
            std::process::id()
        ))
    }

    fn assert_close(actual: f64, expected: f64, tolerance: f64) {
        assert!(
            (actual - expected).abs() <= tolerance,
            "expected {expected}, got {actual}"
        );
    }

    fn detail() -> SymbolDetail {
        SymbolDetail {
            symbol: "NVDA".to_string(),
            profitable: true,
            market_price_cents: 17_270,
            intrinsic_value_cents: 26_923,
            gap_bps: 3_585,
            minimum_gap_bps: 2_000,
            qualification: QualificationStatus::Qualified,
            external_status: ExternalSignalStatus::Supportive,
            external_signal_fair_value_cents: Some(26_923),
            external_signal_low_fair_value_cents: Some(18_500),
            external_signal_high_fair_value_cents: Some(32_000),
            weighted_external_signal_fair_value_cents: Some(27_850),
            weighted_analyst_count: Some(12),
            external_signal_gap_bps: Some(3_585),
            external_signal_age_seconds: Some(6),
            external_signal_max_age_seconds: 30,
            analyst_opinion_count: Some(42),
            recommendation_mean_hundredths: Some(185),
            strong_buy_count: Some(20),
            buy_count: Some(10),
            hold_count: Some(8),
            sell_count: Some(3),
            strong_sell_count: Some(1),
            fundamentals: None,
            confidence: ConfidenceBand::High,
            last_sequence: 6,
            update_count: 2,
            is_watched: false,
        }
    }

    fn historical_candles() -> Vec<HistoricalCandle> {
        vec![
            HistoricalCandle {
                epoch_seconds: 1,
                open_cents: 10_000,
                high_cents: 11_000,
                low_cents: 9_000,
                close_cents: 9_500,
                volume: 100_000,
            },
            HistoricalCandle {
                epoch_seconds: 2,
                open_cents: 9_500,
                high_cents: 12_000,
                low_cents: 9_400,
                close_cents: 11_500,
                volume: 125_000,
            },
            HistoricalCandle {
                epoch_seconds: 3,
                open_cents: 11_500,
                high_cents: 12_500,
                low_cents: 11_200,
                close_cents: 11_300,
                volume: 150_000,
            },
            HistoricalCandle {
                epoch_seconds: 4,
                open_cents: 11_300,
                high_cents: 13_000,
                low_cents: 10_800,
                close_cents: 12_800,
                volume: 175_000,
            },
        ]
    }

    fn live_feed(symbol: &str) -> super::market_data::ProviderFetchResult {
        super::market_data::ProviderFetchResult {
            symbol: symbol.to_string(),
            snapshot: Some(MarketSnapshot {
                symbol: symbol.to_string(),
                company_name: None,
                profitable: true,
                market_price_cents: 10_000,
                intrinsic_value_cents: 12_500,
            }),
            external_signal: Some(ExternalValuationSignal {
                symbol: symbol.to_string(),
                fair_value_cents: 12_000,
                age_seconds: 0,
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
            }),
            fundamentals: Some(sample_fundamentals(
                symbol,
                "technology",
                "Technology",
                "software",
                "Software",
            )),
            coverage: super::market_data::ProviderCoverage {
                core: super::market_data::ProviderComponentState::Fresh,
                external: super::market_data::ProviderComponentState::Fresh,
                fundamentals: super::market_data::ProviderComponentState::Fresh,
            },
            diagnostics: Vec::new(),
        }
    }

    fn provider_error_coverage_event(symbol: &str, detail: &str) -> SymbolCoverageEvent {
        SymbolCoverageEvent {
            symbol: symbol.to_string(),
            coverage: super::market_data::ProviderCoverage {
                core: super::market_data::ProviderComponentState::Error,
                external: super::market_data::ProviderComponentState::Missing,
                fundamentals: super::market_data::ProviderComponentState::Missing,
            },
            diagnostics: vec![super::market_data::ProviderDiagnostic {
                component: super::market_data::ProviderComponent::QuoteHtml,
                kind: super::market_data::ProviderDiagnosticKind::Error,
                detail: detail.to_string(),
                retryable: false,
            }],
        }
    }

    fn provider_missing_coverage_event(symbol: &str, detail: &str) -> SymbolCoverageEvent {
        SymbolCoverageEvent {
            symbol: symbol.to_string(),
            coverage: super::market_data::ProviderCoverage {
                core: super::market_data::ProviderComponentState::Missing,
                external: super::market_data::ProviderComponentState::Missing,
                fundamentals: super::market_data::ProviderComponentState::Missing,
            },
            diagnostics: vec![super::market_data::ProviderDiagnostic {
                component: super::market_data::ProviderComponent::Core,
                kind: super::market_data::ProviderDiagnosticKind::Missing,
                detail: detail.to_string(),
                retryable: false,
            }],
        }
    }

    fn annual_value(as_of_date: &str, value: f64) -> AnnualReportedValue {
        AnnualReportedValue {
            as_of_date: as_of_date.to_string(),
            value,
        }
    }

    fn sample_fundamentals(
        symbol: &str,
        sector_key: &str,
        sector_name: &str,
        industry_key: &str,
        industry_name: &str,
    ) -> FundamentalSnapshot {
        FundamentalSnapshot {
            symbol: symbol.to_string(),
            sector_key: Some(sector_key.to_string()),
            sector_name: Some(sector_name.to_string()),
            industry_key: Some(industry_key.to_string()),
            industry_name: Some(industry_name.to_string()),
            market_cap_dollars: Some(1_200_000_000),
            shares_outstanding: Some(100_000_000),
            trailing_pe_hundredths: Some(1_500),
            forward_pe_hundredths: Some(1_300),
            price_to_book_hundredths: Some(320),
            return_on_equity_bps: Some(1_900),
            ebitda_dollars: Some(220_000_000),
            enterprise_value_dollars: Some(1_300_000_000),
            enterprise_to_ebitda_hundredths: Some(590),
            total_debt_dollars: Some(120_000_000),
            total_cash_dollars: Some(20_000_000),
            debt_to_equity_hundredths: Some(6_000),
            free_cash_flow_dollars: Some(86_000_000),
            operating_cash_flow_dollars: Some(105_000_000),
            beta_millis: Some(1_100),
            trailing_eps_cents: Some(425),
            earnings_growth_bps: Some(1_500),
        }
    }

    fn sample_dcf_timeseries() -> FundamentalTimeseries {
        FundamentalTimeseries {
            source: FundamentalTimeseriesSource::YahooFinance,
            source_policy_stage: "DesktopYahooOnly".to_string(),
            source_fingerprint: "YahooFinance|fixture".to_string(),
            free_cash_flow: vec![
                annual_value("2021-12-31", 50_000_000.0),
                annual_value("2022-12-31", 60_000_000.0),
                annual_value("2023-12-31", 72_000_000.0),
                annual_value("2024-12-31", 86_000_000.0),
            ],
            operating_cash_flow: vec![
                annual_value("2021-12-31", 66_000_000.0),
                annual_value("2022-12-31", 80_000_000.0),
                annual_value("2023-12-31", 93_000_000.0),
                annual_value("2024-12-31", 105_000_000.0),
            ],
            capital_expenditure: vec![
                annual_value("2021-12-31", -16_000_000.0),
                annual_value("2022-12-31", -20_000_000.0),
                annual_value("2023-12-31", -21_000_000.0),
                annual_value("2024-12-31", -19_000_000.0),
            ],
            diluted_average_shares: vec![
                annual_value("2021-12-31", 100_000_000.0),
                annual_value("2022-12-31", 100_000_000.0),
                annual_value("2023-12-31", 100_000_000.0),
                annual_value("2024-12-31", 100_000_000.0),
            ],
            interest_expense: vec![annual_value("2024-12-31", 8_000_000.0)],
            pretax_income: vec![annual_value("2024-12-31", 120_000_000.0)],
            tax_rate_for_calcs: vec![annual_value("2024-12-31", 0.21)],
            net_income: vec![annual_value("2024-12-31", 97_000_000.0)],
        }
    }

    fn sample_ready_analysis() -> DcfAnalysis {
        DcfAnalysis {
            bear_intrinsic_value_cents: 1_450,
            base_intrinsic_value_cents: 1_800,
            bull_intrinsic_value_cents: 2_250,
            wacc_bps: 850,
            base_growth_bps: 1_350,
            net_debt_dollars: 100_000_000,
            selected_source: FundamentalTimeseriesSource::YahooFinance,
            source_policy_stage: "DesktopYahooOnly".to_string(),
            source_fingerprint: "DesktopYahooOnly".to_string(),
            decision_fingerprint: "DesktopYahooOnly|state=Selected|sec=DesktopSecDeferred".to_string(),
        }
    }

    fn sample_metric_group_status(available: bool) -> persistence::MetricGroupStatus {
        persistence::MetricGroupStatus {
            available,
            stale: false,
        }
    }

    fn sample_relative_score() -> SectorRelativeScore {
        SectorRelativeScore {
            group_kind: "industry".to_string(),
            group_label: "Software".to_string(),
            peer_count: 12,
            composite_percentile: 72,
            composite_band: RelativeStrengthBand::Strong,
            metrics: vec![
                RelativeMetricScore {
                    label: "P/E".to_string(),
                    percentile: 70,
                    band: RelativeStrengthBand::Strong,
                },
                RelativeMetricScore {
                    label: "PEG".to_string(),
                    percentile: 68,
                    band: RelativeStrengthBand::Mixed,
                },
                RelativeMetricScore {
                    label: "ROE".to_string(),
                    percentile: 82,
                    band: RelativeStrengthBand::Strong,
                },
                RelativeMetricScore {
                    label: "Net debt/EBITDA".to_string(),
                    percentile: 55,
                    band: RelativeStrengthBand::Mixed,
                },
                RelativeMetricScore {
                    label: "FCF yield".to_string(),
                    percentile: 77,
                    band: RelativeStrengthBand::Strong,
                },
            ],
        }
    }

    fn sample_chart_summary(range: ChartRange, close_cents: i64) -> ChartRangeSummary {
        ChartRangeSummary {
            range,
            captured_at: 1_700_000_000,
            candle_count: 120,
            latest_close_cents: Some(close_cents),
            ema20_cents: Some(close_cents - 50),
            ema50_cents: Some(close_cents - 100),
            ema200_cents: Some(close_cents - 250),
            macd_cents: Some(45),
            signal_cents: Some(31),
            histogram_cents: Some(14),
        }
    }

    fn sample_history_records(
        symbol: &str,
        include_relative: bool,
    ) -> Vec<persistence::PersistedRevisionRecord> {
        let base_time = unix_timestamp_seconds().saturating_sub(2 * 86_400);
        (0..3)
            .map(|index| {
                let market_price_cents = 18_500 + index as i64 * 75;
                let intrinsic_value_cents = 24_500 + index as i64 * 90;
                let external_fair_value_cents = 23_800 + index as i64 * 80;
                let mut fundamentals =
                    sample_fundamentals(symbol, "technology", "Technology", "software", "Software");
                fundamentals.market_cap_dollars =
                    Some(1_200_000_000_u64 + index as u64 * 50_000_000);
                fundamentals.free_cash_flow_dollars = Some(86_000_000 + index as i64 * 5_000_000);
                fundamentals.operating_cash_flow_dollars =
                    Some(105_000_000 + index as i64 * 6_000_000);
                fundamentals.trailing_pe_hundredths = Some(1_500_u32 + index as u32 * 20);
                fundamentals.earnings_growth_bps = Some(1_500 + index as i32 * 40);

                let mut dcf_analysis = sample_ready_analysis();
                dcf_analysis.base_intrinsic_value_cents += index as i64 * 110;
                dcf_analysis.bear_intrinsic_value_cents += index as i64 * 80;
                dcf_analysis.bull_intrinsic_value_cents += index as i64 * 140;

                persistence::PersistedRevisionRecord {
                    revision_id: index as i64 + 1,
                    symbol: symbol.to_string(),
                    evaluated_at: base_time + index as u64 * 86_400,
                    last_sequence: index + 1,
                    update_count: index + 1,
                    payload: persistence::EvaluatedSymbolState {
                        snapshot: Some(MarketSnapshot {
                            symbol: symbol.to_string(),
                            company_name: Some("Example Corp".to_string()),
                            profitable: true,
                            market_price_cents,
                            intrinsic_value_cents,
                        }),
                        external_signal: Some(ExternalValuationSignal {
                            symbol: symbol.to_string(),
                            fair_value_cents: external_fair_value_cents,
                            age_seconds: 120,
                            low_fair_value_cents: Some(22_000),
                            high_fair_value_cents: Some(26_500),
                            analyst_opinion_count: Some(10 + index as u32),
                            recommendation_mean_hundredths: Some(180),
                            strong_buy_count: Some(5),
                            buy_count: Some(4),
                            hold_count: Some(1),
                            sell_count: Some(0),
                            strong_sell_count: Some(0),
                            weighted_fair_value_cents: Some(24_100 + index as i64 * 75),
                            weighted_analyst_count: Some(8),
                        }),
                        fundamentals: Some(fundamentals),
                        gap_bps: Some(3_200 + index as i32 * 45),
                        qualification: Some(QualificationStatus::Qualified),
                        external_status: Some(ExternalSignalStatus::Supportive),
                        confidence: Some(ConfidenceBand::High),
                        external_gap_bps: Some(2_800 + index as i32 * 30),
                        weighted_gap_bps: Some(2_950 + index as i32 * 25),
                        dcf_analysis: Some(dcf_analysis),
                        dcf_signal: Some(DcfSignal::Opportunity),
                        dcf_margin_of_safety_bps: Some(2_100 + index as i32 * 35),
                        relative_score: include_relative.then(sample_relative_score),
                        chart_summaries: chart_ranges()
                            .iter()
                            .enumerate()
                            .map(|(range_index, range)| {
                                sample_chart_summary(
                                    *range,
                                    market_price_cents + range_index as i64 * 25,
                                )
                            })
                            .collect(),
                        core_status: sample_metric_group_status(true),
                        fundamentals_status: sample_metric_group_status(true),
                        relative_status: sample_metric_group_status(include_relative),
                        dcf_status: sample_metric_group_status(true),
                        chart_status: sample_metric_group_status(true),
                        is_watched: index % 2 == 0,
                    },
                }
            })
            .collect()
    }

    fn persist_sample_symbol_revision(
        persistence_handle: &persistence::PersistenceHandle,
        symbol: &str,
        market_price_cents: i64,
        intrinsic_value_cents: i64,
    ) {
        persistence_handle
            .persist_batch(
                Vec::new(),
                vec![persistence::SymbolRevisionInput {
                    symbol: symbol.to_string(),
                    evaluated_at: 1_700_000_000,
                    last_sequence: 1,
                    update_count: 1,
                    price_history: vec![discount_screener::PriceHistoryPoint {
                        sequence: 1,
                        market_price_cents,
                    }],
                    payload: persistence::EvaluatedSymbolState {
                        snapshot: Some(MarketSnapshot {
                            symbol: symbol.to_string(),
                            company_name: None,
                            profitable: true,
                            market_price_cents,
                            intrinsic_value_cents,
                        }),
                        external_signal: None,
                        fundamentals: None,
                        gap_bps: checked_gap_bps(market_price_cents, intrinsic_value_cents),
                        qualification: Some(QualificationStatus::Qualified),
                        external_status: Some(ExternalSignalStatus::Missing),
                        confidence: Some(ConfidenceBand::Low),
                        external_gap_bps: None,
                        weighted_gap_bps: None,
                        dcf_analysis: None,
                        dcf_signal: None,
                        dcf_margin_of_safety_bps: None,
                        relative_score: None,
                        chart_summaries: Vec::new(),
                        core_status: sample_metric_group_status(true),
                        fundamentals_status: sample_metric_group_status(false),
                        relative_status: sample_metric_group_status(false),
                        dcf_status: sample_metric_group_status(false),
                        chart_status: sample_metric_group_status(false),
                        is_watched: false,
                    },
                }],
            )
            .expect("symbol revision should persist");
    }

    #[derive(Clone)]
    struct FakeFeedClient {
        calls: Arc<Mutex<Vec<(String, bool)>>>,
        results: Arc<Mutex<VecDeque<io::Result<super::market_data::ProviderFetchResult>>>>,
    }

    impl super::LiveFeedClient for FakeFeedClient {
        fn fetch_symbol_with_options(
            &self,
            symbol: &str,
            refresh_weighted_target: bool,
        ) -> io::Result<super::market_data::ProviderFetchResult> {
            self.calls
                .lock()
                .expect("fake client calls should be lockable")
                .push((symbol.to_string(), refresh_weighted_target));
            self.results
                .lock()
                .expect("fake client results should be lockable")
                .pop_front()
                .expect("each fake client fetch should have a queued result")
        }
    }

    #[derive(Clone)]
    struct FakeChartClient {
        calls: Arc<Mutex<Vec<(String, ChartRange)>>>,
        results: Arc<Mutex<VecDeque<io::Result<Vec<HistoricalCandle>>>>>,
    }

    impl super::HistoricalChartClient for FakeChartClient {
        fn fetch_historical_candles(
            &self,
            symbol: &str,
            range: ChartRange,
        ) -> io::Result<Vec<HistoricalCandle>> {
            self.calls
                .lock()
                .expect("fake chart client calls should be lockable")
                .push((symbol.to_string(), range));
            self.results
                .lock()
                .expect("fake chart client results should be lockable")
                .pop_front()
                .expect("each fake chart fetch should have a queued result")
        }
    }

    #[test]
    fn selection_tracks_the_same_symbol_after_reordering() {
        let mut app = AppState::default();
        app.selected_symbol = Some("BETA".to_string());
        let rows = vec![candidate("ALFA", 3_000), candidate("BETA", 2_000)];
        let reordered_rows = vec![candidate("BETA", 2_000), candidate("ALFA", 3_000)];
        app.selected_index(&rows);

        assert_eq!(app.selected_index(&reordered_rows), 0);
    }

    #[test]
    fn render_lines_are_built_as_distinct_rows() {
        let lines = vec![
            RenderLine {
                color: Some(Color::Yellow),
                text: "HEADER".to_string(),
            },
            RenderLine {
                color: None,
                text: "STATUS".to_string(),
            },
            RenderLine {
                color: None,
                text: "FILTER".to_string(),
            },
        ];

        assert_eq!(
            lines.into_iter().map(|line| line.text).collect::<Vec<_>>(),
            vec![
                "HEADER".to_string(),
                "STATUS".to_string(),
                "FILTER".to_string(),
            ]
        );
    }

    #[test]
    fn parse_symbols_argument_deduplicates_and_normalizes_symbols() {
        assert_eq!(
            parse_symbols_argument(" msft, aapl,MSFT ")
                .expect("symbols should parse and deduplicate"),
            vec!["MSFT".to_string(), "AAPL".to_string()]
        );
    }

    #[test]
    fn parse_runtime_options_defaults_to_sp500_profile() {
        let options = parse_runtime_options_from(Vec::<String>::new())
            .expect("empty args should resolve to the default universe");

        assert!(options.symbols.is_empty());
        assert!(options.persist_enabled);
        assert!(options.state_db.is_some());
    }

    #[test]
    fn parse_runtime_options_loads_named_profile_symbols() {
        let options = parse_runtime_options_from(["--profile", "dow-jones"])
            .expect("named profiles should resolve through aliases");

        assert_eq!(
            options.symbols,
            vec![
                "MMM", "AXP", "AMGN", "AMZN", "AAPL", "BA", "CAT", "CVX", "CSCO", "KO", "DIS",
                "GS", "HD", "HON", "IBM", "JNJ", "JPM", "MCD", "MRK", "MSFT", "NVDA", "NKE", "PG",
                "CRM", "SHW", "TRV", "UNH", "VZ", "V", "WMT",
            ]
            .into_iter()
            .map(str::to_string)
            .collect::<Vec<_>>()
        );
    }

    #[test]
    fn compute_dcf_analysis_builds_three_scenarios_and_signal_thresholds() {
        let fundamentals =
            sample_fundamentals("NVDA", "technology", "Technology", "software", "Software");
        let analysis =
            compute_dcf_analysis(&fundamentals, &sample_dcf_timeseries()).expect("DCF should work");

        assert!(analysis.bear_intrinsic_value_cents < analysis.base_intrinsic_value_cents);
        assert!(analysis.base_intrinsic_value_cents < analysis.bull_intrinsic_value_cents);
        assert_eq!(analysis.net_debt_dollars, 100_000_000);
        assert!((500..=1_800).contains(&analysis.wacc_bps));
        assert!(analysis.base_growth_bps >= -1_000);
        assert_eq!(
            dcf_signal(
                &analysis,
                ((analysis.base_intrinsic_value_cents as f64) * 0.75).round() as i64
            ),
            DcfSignal::Opportunity
        );
        assert_eq!(
            dcf_signal(&analysis, analysis.base_intrinsic_value_cents),
            DcfSignal::Fair
        );
        assert_eq!(
            dcf_signal(
                &analysis,
                ((analysis.base_intrinsic_value_cents as f64) * 1.20).round() as i64
            ),
            DcfSignal::Expensive
        );
        assert_eq!(
            dcf_margin_of_safety_bps(&analysis, analysis.base_intrinsic_value_cents),
            Some(0)
        );
    }

    #[test]
    fn compute_dcf_analysis_preserves_net_cash_companies() {
        let mut fundamentals =
            sample_fundamentals("NVDA", "technology", "Technology", "software", "Software");
        fundamentals.total_debt_dollars = Some(100_000_000);
        fundamentals.total_cash_dollars = Some(250_000_000);

        let analysis =
            compute_dcf_analysis(&fundamentals, &sample_dcf_timeseries()).expect("DCF should work");

        assert_eq!(analysis.net_debt_dollars, -150_000_000);
    }

    #[test]
    fn compute_dcf_analysis_carries_desktop_yahoo_only_source_provenance() {
        let fundamentals =
            sample_fundamentals("NVDA", "technology", "Technology", "software", "Software");
        let timeseries = sample_dcf_timeseries();
        let analysis = compute_dcf_analysis(&fundamentals, &timeseries).expect("DCF should work");

        assert_eq!(
            (
                FundamentalTimeseriesSource::YahooFinance,
                "DesktopYahooOnly",
                timeseries.source_fingerprint.as_str(),
                true,
            ),
            (
                analysis.selected_source,
                analysis.source_policy_stage.as_str(),
                analysis.source_fingerprint.as_str(),
                analysis.decision_fingerprint.contains("DesktopSecDeferred"),
            ),
        );
    }

    #[test]
    fn analysis_input_key_includes_desktop_source_policy_fingerprint() {
        let fundamentals =
            sample_fundamentals("NVDA", "technology", "Technology", "software", "Software");

        assert_eq!("DesktopYahooOnly", analysis_input_key(&fundamentals).source_fingerprint);
    }

    #[test]
    fn compute_dcf_analysis_rejects_insufficient_and_non_positive_fcf_history() {
        let fundamentals =
            sample_fundamentals("NVDA", "technology", "Technology", "software", "Software");
        let insufficient = FundamentalTimeseries {
            free_cash_flow: vec![
                annual_value("2023-12-31", 72_000_000.0),
                annual_value("2024-12-31", 86_000_000.0),
            ],
            diluted_average_shares: vec![
                annual_value("2023-12-31", 100_000_000.0),
                annual_value("2024-12-31", 100_000_000.0),
            ],
            ..FundamentalTimeseries::default()
        };
        let non_positive = FundamentalTimeseries {
            free_cash_flow: vec![
                annual_value("2022-12-31", 50_000_000.0),
                annual_value("2023-12-31", 25_000_000.0),
                annual_value("2024-12-31", 0.0),
            ],
            diluted_average_shares: vec![
                annual_value("2022-12-31", 100_000_000.0),
                annual_value("2023-12-31", 100_000_000.0),
                annual_value("2024-12-31", 100_000_000.0),
            ],
            ..FundamentalTimeseries::default()
        };

        assert!(compute_dcf_analysis(&fundamentals, &insufficient)
            .expect_err("two points should fail")
            .to_string()
            .contains("at least 3 annual free cash flow points"));
        assert!(compute_dcf_analysis(&fundamentals, &non_positive)
            .expect_err("non-positive latest FCF should fail")
            .to_string()
            .contains("latest annual free cash flow is not positive"));
    }

    #[test]
    fn derive_base_growth_uses_the_earliest_positive_history_point() {
        let declining_then_partial_recovery = vec![
            ("2021-12-31".to_string(), 10.0),
            ("2022-12-31".to_string(), 5.0),
            ("2023-12-31".to_string(), 6.0),
        ];

        assert_eq!(
            derive_base_growth_bps(&declining_then_partial_recovery),
            Some(-2255)
        );
    }

    #[test]
    fn derive_base_growth_uses_fractional_years_for_stub_periods() {
        let stub_period = vec![
            ("2022-12-28".to_string(), 10.0),
            ("2024-01-05".to_string(), 15.0),
        ];
        let expected_years = 373.0 / 365.2425;
        let expected_cagr_bps =
            (((15.0_f64 / 10.0_f64).powf(1.0 / expected_years) - 1.0) * 10_000.0).round() as i32;

        assert_eq!(
            derive_base_growth_bps(&stub_period),
            Some(expected_cagr_bps)
        );
        assert!(expected_cagr_bps > 4_000);
    }

    #[test]
    fn dcf_margin_of_safety_returns_none_instead_of_saturating() {
        let analysis = DcfAnalysis {
            bear_intrinsic_value_cents: 1,
            base_intrinsic_value_cents: 1,
            bull_intrinsic_value_cents: 1,
            wacc_bps: 1_000,
            base_growth_bps: 500,
            net_debt_dollars: 0,
            selected_source: FundamentalTimeseriesSource::YahooFinance,
            source_policy_stage: "DesktopYahooOnly".to_string(),
            source_fingerprint: "DesktopYahooOnly".to_string(),
            decision_fingerprint: "DesktopYahooOnly|state=Selected|sec=DesktopSecDeferred".to_string(),
        };

        assert_eq!(dcf_margin_of_safety_bps(&analysis, i64::MAX), None);
        assert_eq!(dcf_signal(&analysis, i64::MAX), DcfSignal::Expensive);
    }

    #[test]
    fn sector_relative_score_prefers_industry_peers_when_available() {
        let mut state = TerminalState::new(2_000, 30, 8);
        let subject =
            sample_fundamentals("NVDA", "technology", "Technology", "software", "Software");
        state.ingest_fundamentals(subject.clone());

        for (index, pe, growth, roe, debt, cash, ebitda, fcf) in [
            (
                1,
                2_100,
                900,
                1_500,
                180_000_000,
                10_000_000,
                200_000_000,
                55_000_000,
            ),
            (
                2,
                2_300,
                1_000,
                1_600,
                170_000_000,
                15_000_000,
                210_000_000,
                58_000_000,
            ),
            (
                3,
                2_500,
                1_100,
                1_700,
                160_000_000,
                20_000_000,
                205_000_000,
                60_000_000,
            ),
            (
                4,
                2_700,
                1_200,
                1_800,
                150_000_000,
                20_000_000,
                215_000_000,
                63_000_000,
            ),
            (
                5,
                2_900,
                1_300,
                1_900,
                145_000_000,
                25_000_000,
                220_000_000,
                66_000_000,
            ),
        ] {
            let mut peer = sample_fundamentals(
                &format!("I{index}"),
                "technology",
                "Technology",
                "software",
                "Software",
            );
            peer.trailing_pe_hundredths = Some(pe);
            peer.earnings_growth_bps = Some(growth);
            peer.return_on_equity_bps = Some(roe);
            peer.total_debt_dollars = Some(debt);
            peer.total_cash_dollars = Some(cash);
            peer.ebitda_dollars = Some(ebitda);
            peer.free_cash_flow_dollars = Some(fcf);
            state.ingest_fundamentals(peer);
        }

        for index in 1..=2 {
            let peer = sample_fundamentals(
                &format!("S{index}"),
                "technology",
                "Technology",
                "hardware",
                "Hardware",
            );
            state.ingest_fundamentals(peer);
        }

        let score = compute_sector_relative_score(&state, &subject)
            .expect("industry peers should produce a relative score");

        assert_eq!(score.group_kind, "industry");
        assert_eq!(score.group_label, "Software");
        assert_eq!(score.peer_count, 5);
        assert_eq!(score.composite_band, super::RelativeStrengthBand::Strong);
    }

    #[test]
    fn sector_relative_score_falls_back_to_sector_when_industry_is_too_small() {
        let mut state = TerminalState::new(2_000, 30, 8);
        let subject =
            sample_fundamentals("NVDA", "technology", "Technology", "software", "Software");
        state.ingest_fundamentals(subject.clone());

        for index in 1..=3 {
            let peer = sample_fundamentals(
                &format!("I{index}"),
                "technology",
                "Technology",
                "software",
                "Software",
            );
            state.ingest_fundamentals(peer);
        }
        for index in 1..=2 {
            let mut peer = sample_fundamentals(
                &format!("H{index}"),
                "technology",
                "Technology",
                "hardware",
                "Hardware",
            );
            peer.trailing_pe_hundredths = Some(2_400 + (index as u32 * 100));
            state.ingest_fundamentals(peer);
        }

        let score = compute_sector_relative_score(&state, &subject)
            .expect("sector fallback should produce a relative score");

        assert_eq!(score.group_kind, "sector");
        assert_eq!(score.group_label, "Technology");
        assert_eq!(score.peer_count, 5);
    }

    #[test]
    fn sector_relative_score_treats_a_single_outlier_as_a_small_shift() {
        let subject =
            sample_fundamentals("NVDA", "technology", "Technology", "software", "Software");

        let mut baseline_state = TerminalState::new(2_000, 30, 8);
        baseline_state.ingest_fundamentals(subject.clone());
        for index in 1..=5 {
            let mut peer = subject.clone();
            peer.symbol = format!("B{index}");
            baseline_state.ingest_fundamentals(peer);
        }

        let baseline_score = compute_sector_relative_score(&baseline_state, &subject)
            .expect("baseline peers should produce a relative score");
        assert_eq!(baseline_score.composite_percentile, 50);

        let mut outlier_state = TerminalState::new(2_000, 30, 8);
        outlier_state.ingest_fundamentals(subject.clone());
        for index in 1..=4 {
            let mut peer = subject.clone();
            peer.symbol = format!("N{index}");
            outlier_state.ingest_fundamentals(peer);
        }
        let mut outlier = subject.clone();
        outlier.symbol = "OUTLIER".to_string();
        outlier.trailing_pe_hundredths = Some(100_000);
        outlier.earnings_growth_bps = Some(100);
        outlier.return_on_equity_bps = Some(-500);
        outlier.total_debt_dollars = Some(800_000_000);
        outlier.total_cash_dollars = Some(0);
        outlier.ebitda_dollars = Some(100_000_000);
        outlier.free_cash_flow_dollars = Some(10_000_000);
        outlier_state.ingest_fundamentals(outlier);

        let outlier_score = compute_sector_relative_score(&outlier_state, &subject)
            .expect("outlier peers should still produce a relative score");

        assert_eq!(outlier_score.composite_percentile, 60);
        assert_eq!(outlier_score.composite_band, RelativeStrengthBand::Mixed);
        assert!(
            (outlier_score.composite_percentile as i16
                - baseline_score.composite_percentile as i16)
                .abs()
                <= 10
        );
    }

    #[test]
    fn sector_relative_score_requires_five_external_peers() {
        let mut state = TerminalState::new(2_000, 30, 8);
        let subject =
            sample_fundamentals("NVDA", "technology", "Technology", "software", "Software");
        state.ingest_fundamentals(subject.clone());

        for index in 1..=4 {
            let mut peer = subject.clone();
            peer.symbol = format!("P{index}");
            state.ingest_fundamentals(peer);
        }

        assert!(compute_sector_relative_score(&state, &subject).is_none());
    }

    #[test]
    fn relative_metric_score_uses_midrank_for_ties() {
        let mut subject =
            sample_fundamentals("NVDA", "technology", "Technology", "software", "Software");
        subject.trailing_pe_hundredths = Some(2_000);

        let mut peers = Vec::new();
        for index in 1..=5 {
            let mut peer = subject.clone();
            peer.symbol = format!("T{index}");
            peers.push(peer);
        }

        let score = relative_metric_score(
            "P/E",
            super::fundamental_trailing_pe,
            true,
            &subject,
            &peers,
        )
        .expect("tied peers should still score");

        assert_eq!(score.percentile, 50);
        assert_eq!(score.band, RelativeStrengthBand::Mixed);
    }

    #[test]
    fn robust_composite_percentile_uses_median_for_small_metric_sets() {
        let scores = vec![
            RelativeMetricScore {
                label: "A".to_string(),
                percentile: 10,
                band: RelativeStrengthBand::Weak,
            },
            RelativeMetricScore {
                label: "B".to_string(),
                percentile: 50,
                band: RelativeStrengthBand::Mixed,
            },
            RelativeMetricScore {
                label: "C".to_string(),
                percentile: 90,
                band: RelativeStrengthBand::Strong,
            },
        ];

        assert_eq!(robust_composite_percentile(&scores), Some(50));
    }

    #[test]
    fn robust_composite_percentile_trims_extreme_outliers() {
        let scores = vec![
            RelativeMetricScore {
                label: "A".to_string(),
                percentile: 5,
                band: RelativeStrengthBand::Weak,
            },
            RelativeMetricScore {
                label: "B".to_string(),
                percentile: 45,
                band: RelativeStrengthBand::Mixed,
            },
            RelativeMetricScore {
                label: "C".to_string(),
                percentile: 50,
                band: RelativeStrengthBand::Mixed,
            },
            RelativeMetricScore {
                label: "D".to_string(),
                percentile: 55,
                band: RelativeStrengthBand::Mixed,
            },
            RelativeMetricScore {
                label: "E".to_string(),
                percentile: 95,
                band: RelativeStrengthBand::Strong,
            },
        ];

        assert_eq!(robust_composite_percentile(&scores), Some(50));
    }

    #[test]
    fn queue_detail_analysis_request_enqueues_once_per_fundamental_input() {
        let mut state = TerminalState::new(2_000, 30, 8);
        let fundamentals =
            sample_fundamentals("NVDA", "technology", "Technology", "software", "Software");
        state.ingest_snapshot(MarketSnapshot {
            symbol: "NVDA".to_string(),
            company_name: None,
            profitable: true,
            market_price_cents: 1_200,
            intrinsic_value_cents: 1_800,
        });
        state.ingest_fundamentals(fundamentals.clone());

        let (sender, receiver) = mpsc::channel();
        let mut app = AppState::default();
        app.open_ticker_detail("NVDA");
        app.queue_detail_analysis_request(&state, Some(&sender));

        let first = recv_with_timeout(&receiver, "analysis request should be queued");
        let request_id = match first {
            AnalysisControl::Load {
                symbol,
                request_id,
                fundamentals: payload,
                ..
            } => {
                assert_eq!(symbol, "NVDA");
                assert_eq!(payload, fundamentals);
                request_id
            }
        };

        assert!(matches!(
            app.detail_analysis_entry("NVDA"),
            Some(AnalysisCacheEntry::Loading { request_id: cached_id, .. }) if *cached_id == request_id
        ));

        app.queue_detail_analysis_request(&state, Some(&sender));
        assert!(receiver.try_recv().is_err());
    }

    #[test]
    fn queue_detail_analysis_request_skips_failed_entries_with_matching_input() {
        let mut state = TerminalState::new(2_000, 30, 8);
        let fundamentals =
            sample_fundamentals("NVDA", "technology", "Technology", "software", "Software");
        state.ingest_snapshot(MarketSnapshot {
            symbol: "NVDA".to_string(),
            company_name: None,
            profitable: true,
            market_price_cents: 1_200,
            intrinsic_value_cents: 1_800,
        });
        state.ingest_fundamentals(fundamentals.clone());

        let (sender, receiver) = mpsc::channel();
        let mut app = AppState::default();
        app.open_ticker_detail("NVDA");
        app.analysis_cache.insert(
            "NVDA".to_string(),
            AnalysisCacheEntry::Failed {
                input: analysis_input_key(&fundamentals),
                message: "temporary Yahoo timeout".to_string(),
            },
        );

        app.queue_detail_analysis_request(&state, Some(&sender));

        assert!(receiver.try_recv().is_err());
        assert!(matches!(
            app.detail_analysis_entry("NVDA"),
            Some(AnalysisCacheEntry::Failed { .. })
        ));
    }

    #[test]
    fn queue_detail_analysis_request_retries_failed_when_input_changes() {
        let mut state = TerminalState::new(2_000, 30, 8);
        let fundamentals =
            sample_fundamentals("NVDA", "technology", "Technology", "software", "Software");
        state.ingest_snapshot(MarketSnapshot {
            symbol: "NVDA".to_string(),
            company_name: None,
            profitable: true,
            market_price_cents: 1_200,
            intrinsic_value_cents: 1_800,
        });
        state.ingest_fundamentals(fundamentals.clone());

        let (sender, receiver) = mpsc::channel();
        let mut app = AppState::default();
        app.open_ticker_detail("NVDA");

        let mut stale_fundamentals = fundamentals.clone();
        stale_fundamentals.beta_millis = Some(1_450);
        app.analysis_cache.insert(
            "NVDA".to_string(),
            AnalysisCacheEntry::Failed {
                input: analysis_input_key(&stale_fundamentals),
                message: "temporary Yahoo timeout".to_string(),
            },
        );

        app.queue_detail_analysis_request(&state, Some(&sender));

        let queued =
            recv_with_timeout(&receiver, "failed analysis should retry when inputs change");
        match queued {
            AnalysisControl::Load {
                symbol,
                fundamentals: payload,
                ..
            } => {
                assert_eq!(symbol, "NVDA");
                assert_eq!(payload, fundamentals);
            }
        }
        assert!(matches!(
            app.detail_analysis_entry("NVDA"),
            Some(AnalysisCacheEntry::Loading { .. })
        ));
    }

    #[test]
    fn queue_detail_analysis_request_retries_ready_when_input_changes() {
        let mut state = TerminalState::new(2_000, 30, 8);
        let fundamentals =
            sample_fundamentals("NVDA", "technology", "Technology", "software", "Software");
        state.ingest_snapshot(MarketSnapshot {
            symbol: "NVDA".to_string(),
            company_name: None,
            profitable: true,
            market_price_cents: 1_200,
            intrinsic_value_cents: 1_800,
        });
        state.ingest_fundamentals(fundamentals.clone());

        let (sender, receiver) = mpsc::channel();
        let mut app = AppState::default();
        app.open_ticker_detail("NVDA");

        let mut stale_fundamentals = fundamentals.clone();
        stale_fundamentals.total_cash_dollars = Some(35_000_000);
        app.analysis_cache.insert(
            "NVDA".to_string(),
            AnalysisCacheEntry::Ready {
                input: analysis_input_key(&stale_fundamentals),
                analysis: sample_ready_analysis(),
            },
        );

        app.queue_detail_analysis_request(&state, Some(&sender));

        let queued = recv_with_timeout(&receiver, "stale ready analysis should be refreshed");
        match queued {
            AnalysisControl::Load {
                symbol,
                fundamentals: payload,
                ..
            } => {
                assert_eq!(symbol, "NVDA");
                assert_eq!(payload, fundamentals);
            }
        }
        assert!(matches!(
            app.detail_analysis_entry("NVDA"),
            Some(AnalysisCacheEntry::Loading { .. })
        ));
    }

    #[test]
    fn queue_detail_analysis_request_ignores_market_cap_only_changes() {
        let mut state = TerminalState::new(2_000, 30, 8);
        let fundamentals =
            sample_fundamentals("NVDA", "technology", "Technology", "software", "Software");
        state.ingest_snapshot(MarketSnapshot {
            symbol: "NVDA".to_string(),
            company_name: None,
            profitable: true,
            market_price_cents: 1_200,
            intrinsic_value_cents: 1_800,
        });
        state.ingest_fundamentals(fundamentals.clone());

        let (sender, receiver) = mpsc::channel();
        let mut app = AppState::default();
        app.open_ticker_detail("NVDA");
        app.queue_detail_analysis_request(&state, Some(&sender));
        let _ = recv_with_timeout(&receiver, "initial analysis request should be queued");

        let mut refreshed = fundamentals.clone();
        refreshed.market_cap_dollars = Some(1_350_000_000);
        state.ingest_fundamentals(refreshed);

        app.queue_detail_analysis_request(&state, Some(&sender));
        assert!(receiver.try_recv().is_err());
    }

    #[test]
    fn background_analysis_skips_failed_entries_with_matching_input() {
        let mut state = TerminalState::new(2_000, 30, 8);
        let fundamentals =
            sample_fundamentals("NVDA", "technology", "Technology", "software", "Software");
        state.ingest_snapshot(MarketSnapshot {
            symbol: "NVDA".to_string(),
            company_name: None,
            profitable: true,
            market_price_cents: 1_200,
            intrinsic_value_cents: 1_800,
        });
        state.ingest_fundamentals(fundamentals.clone());

        let (sender, receiver) = mpsc::channel();
        let mut app = AppState::default();
        app.analysis_cache.insert(
            "NVDA".to_string(),
            AnalysisCacheEntry::Failed {
                input: analysis_input_key(&fundamentals),
                message: "DCF unavailable: latest annual free cash flow is not positive."
                    .to_string(),
            },
        );

        app.queue_background_analysis_requests(&state, Some(&sender), &["NVDA".to_string()]);

        assert!(receiver.try_recv().is_err());
        assert!(matches!(
            app.detail_analysis_entry("NVDA"),
            Some(AnalysisCacheEntry::Failed { .. })
        ));
    }

    #[test]
    fn background_analysis_retries_failed_when_input_changes() {
        let mut state = TerminalState::new(2_000, 30, 8);
        let fundamentals =
            sample_fundamentals("NVDA", "technology", "Technology", "software", "Software");
        state.ingest_snapshot(MarketSnapshot {
            symbol: "NVDA".to_string(),
            company_name: None,
            profitable: true,
            market_price_cents: 1_200,
            intrinsic_value_cents: 1_800,
        });
        state.ingest_fundamentals(fundamentals.clone());

        let (sender, receiver) = mpsc::channel();
        let mut app = AppState::default();

        let mut stale_fundamentals = fundamentals.clone();
        stale_fundamentals.total_debt_dollars = Some(999_000_000);
        app.analysis_cache.insert(
            "NVDA".to_string(),
            AnalysisCacheEntry::Failed {
                input: analysis_input_key(&stale_fundamentals),
                message: "transient failure".to_string(),
            },
        );

        app.queue_background_analysis_requests(&state, Some(&sender), &["NVDA".to_string()]);

        let queued = recv_with_timeout(
            &receiver,
            "background should retry when input key has changed",
        );
        match queued {
            AnalysisControl::Load { symbol, .. } => {
                assert_eq!(symbol, "NVDA");
            }
        }
    }

    #[test]
    fn detail_analysis_snapshot_reuses_cached_results_when_only_market_cap_changes() {
        let fresh = sample_fundamentals("NVDA", "technology", "Technology", "software", "Software");
        let mut stale = fresh.clone();
        stale.market_cap_dollars = Some(900_000_000);

        let detail = SymbolDetail {
            symbol: "NVDA".to_string(),
            profitable: true,
            market_price_cents: 1_200,
            intrinsic_value_cents: 1_800,
            gap_bps: 5_000,
            minimum_gap_bps: 2_000,
            qualification: QualificationStatus::Qualified,
            external_status: ExternalSignalStatus::Supportive,
            external_signal_fair_value_cents: None,
            external_signal_low_fair_value_cents: None,
            external_signal_high_fair_value_cents: None,
            weighted_external_signal_fair_value_cents: None,
            weighted_analyst_count: None,
            external_signal_gap_bps: None,
            external_signal_age_seconds: None,
            external_signal_max_age_seconds: 30,
            analyst_opinion_count: None,
            recommendation_mean_hundredths: None,
            strong_buy_count: None,
            buy_count: None,
            hold_count: None,
            sell_count: None,
            strong_sell_count: None,
            fundamentals: Some(fresh),
            confidence: ConfidenceBand::High,
            last_sequence: 1,
            update_count: 1,
            is_watched: false,
        };
        let mut app = AppState::default();
        app.analysis_cache.insert(
            "NVDA".to_string(),
            AnalysisCacheEntry::Ready {
                input: analysis_input_key(&stale),
                analysis: sample_ready_analysis(),
            },
        );

        let snapshot = detail_analysis_snapshot(&app, &detail);

        assert_eq!(snapshot.status, "ready");
        assert!(snapshot.analysis.is_some());
    }

    #[test]
    fn detail_analysis_snapshot_ignores_cached_results_for_changed_analysis_inputs() {
        let fresh = sample_fundamentals("NVDA", "technology", "Technology", "software", "Software");
        let mut stale = fresh.clone();
        stale.beta_millis = Some(1_450);

        let detail = SymbolDetail {
            symbol: "NVDA".to_string(),
            profitable: true,
            market_price_cents: 1_200,
            intrinsic_value_cents: 1_800,
            gap_bps: 5_000,
            minimum_gap_bps: 2_000,
            qualification: QualificationStatus::Qualified,
            external_status: ExternalSignalStatus::Supportive,
            external_signal_fair_value_cents: None,
            external_signal_low_fair_value_cents: None,
            external_signal_high_fair_value_cents: None,
            weighted_external_signal_fair_value_cents: None,
            weighted_analyst_count: None,
            external_signal_gap_bps: None,
            external_signal_age_seconds: None,
            external_signal_max_age_seconds: 30,
            analyst_opinion_count: None,
            recommendation_mean_hundredths: None,
            strong_buy_count: None,
            buy_count: None,
            hold_count: None,
            sell_count: None,
            strong_sell_count: None,
            fundamentals: Some(fresh),
            confidence: ConfidenceBand::High,
            last_sequence: 1,
            update_count: 1,
            is_watched: false,
        };
        let mut app = AppState::default();
        app.analysis_cache.insert(
            "NVDA".to_string(),
            AnalysisCacheEntry::Ready {
                input: analysis_input_key(&stale),
                analysis: sample_ready_analysis(),
            },
        );

        let snapshot = detail_analysis_snapshot(&app, &detail);

        assert_eq!(snapshot.status, "idle");
        assert!(snapshot.analysis.is_none());
    }

    #[test]
    fn ticker_detail_renders_fundamentals_section_with_dcf_and_relative_score() {
        let mut state = TerminalState::new(2_000, 30, 8);
        let subject =
            sample_fundamentals("NVDA", "technology", "Technology", "software", "Software");
        state.ingest_snapshot(MarketSnapshot {
            symbol: "NVDA".to_string(),
            company_name: Some("NVIDIA Corporation".to_string()),
            profitable: true,
            market_price_cents: 1_200,
            intrinsic_value_cents: 1_950,
        });
        state.ingest_external(ExternalValuationSignal {
            symbol: "NVDA".to_string(),
            fair_value_cents: 2_000,
            age_seconds: 0,
            low_fair_value_cents: Some(1_600),
            high_fair_value_cents: Some(2_300),
            analyst_opinion_count: Some(24),
            recommendation_mean_hundredths: Some(180),
            strong_buy_count: Some(10),
            buy_count: Some(8),
            hold_count: Some(6),
            sell_count: Some(0),
            strong_sell_count: Some(0),
            weighted_fair_value_cents: Some(1_980),
            weighted_analyst_count: Some(12),
        });
        state.ingest_fundamentals(subject.clone());

        for (index, pe) in [(1, 2_000), (2, 2_200), (3, 2_400), (4, 2_600), (5, 2_800)] {
            let mut peer = sample_fundamentals(
                &format!("P{index}"),
                "technology",
                "Technology",
                "software",
                "Software",
            );
            peer.trailing_pe_hundredths = Some(pe);
            state.ingest_fundamentals(peer);
        }

        let mut app = AppState::default();
        app.analysis_cache.insert(
            "NVDA".to_string(),
            AnalysisCacheEntry::Ready {
                input: analysis_input_key(&subject),
                analysis: sample_ready_analysis(),
            },
        );

        let lines = build_ticker_detail_lines_for_viewport(&state, &app, "NVDA", 140, 80);
        let visible_lines = lines
            .iter()
            .map(|line| visible_text(&line.text))
            .collect::<Vec<_>>();

        assert!(visible_lines.iter().any(|line| line == "FUNDAMENTALS"));
        assert!(visible_lines
            .iter()
            .any(|line| line.contains("Proprietary DCF value")));
        assert!(visible_lines
            .iter()
            .any(|line| line.contains("DCF bear $14.50  base $18.00  bull $22.50")));
        assert!(visible_lines
            .iter()
            .any(|line| line.contains("Relative vs industry Software peers=5")));
    }

    #[test]
    fn parse_runtime_options_appends_custom_symbols_to_a_profile() {
        let options = parse_runtime_options_from(["--profile", "merval", "--symbols", "AAPL,YPF"])
            .expect("profiles should accept extra custom symbols");

        assert_eq!(options.symbols.last().map(String::as_str), Some("YPF"));
        assert!(options.symbols.iter().any(|symbol| symbol == "AAPL"));
        assert_eq!(
            options
                .symbols
                .iter()
                .filter(|symbol| symbol.as_str() == "YPF")
                .count(),
            1
        );
    }

    #[test]
    fn parse_runtime_options_marks_cli_symbols_as_explicit() {
        let options =
            parse_runtime_options_from(["--symbols", "IMAX,SPHR"]).expect("symbols should parse");

        assert!(options.symbols_explicit);
        assert_eq!(
            options.symbols,
            vec!["IMAX".to_string(), "SPHR".to_string()]
        );
    }

    #[test]
    fn parse_runtime_options_rejects_unknown_profiles() {
        let error = parse_runtime_options_from(["--profile", "unknown-profile"])
            .expect_err("unknown profiles should be rejected");

        assert_eq!(error.kind(), io::ErrorKind::InvalidInput);
        assert!(error
            .to_string()
            .contains("Available profiles: sp500, dow, russell"));
    }

    #[test]
    fn usage_text_mentions_profile_flag_and_profiles() {
        let usage = usage_text();

        assert!(usage.contains("--profile NAME"));
        assert!(usage.contains("starting universe for this session"));
        assert!(usage.contains("sp500"));
        assert!(usage.contains("merval"));
        assert!(usage.contains("nikkei"));
        assert!(usage.contains("europe"));
        assert!(usage.contains("asia"));
    }

    #[test]
    fn format_symbol_list_truncates_long_universes() {
        let symbols = (0..20)
            .map(|index| format!("LONGSYMBOL{index:02}"))
            .collect::<Vec<_>>();

        let label = format_symbol_list(&symbols);

        assert!(label.contains("(+"));
    }

    #[test]
    fn live_symbol_state_adds_only_new_symbols() {
        let state = LiveSymbolState::new(vec!["AAPL".to_string(), "MSFT".to_string()]);

        assert_eq!(
            (
                state.add_symbols(vec!["MSFT".to_string(), "NVDA".to_string()]),
                state.snapshot(),
            ),
            (
                vec!["NVDA".to_string()],
                vec!["AAPL".to_string(), "MSFT".to_string(), "NVDA".to_string()],
            )
        );
    }

    #[test]
    fn app_state_defaults_to_normal_input_mode() {
        let app = AppState::default();

        assert!(matches!(app.input_mode, InputMode::Normal));
    }

    #[test]
    fn ignores_key_release_events_to_avoid_duplicate_input() {
        let press_event =
            KeyEvent::new_with_kind(KeyCode::Char('n'), KeyModifiers::NONE, KeyEventKind::Press);
        let release_event = KeyEvent::new_with_kind(
            KeyCode::Char('n'),
            KeyModifiers::NONE,
            KeyEventKind::Release,
        );

        assert_eq!(
            (
                should_handle_key_event(&press_event),
                should_handle_key_event(&release_event),
            ),
            (true, false)
        );
    }

    #[test]
    fn issue_center_tracks_active_and_resolved_issues() {
        let mut issue_center = IssueCenter::default();
        issue_center.raise(
            "feed-unavailable",
            IssueSource::Feed,
            IssueSeverity::Error,
            "Live source unavailable",
            "Loaded 0 of 8 tracked symbols.",
        );
        issue_center.resolve("feed-unavailable");

        assert_eq!(
            (
                issue_center.active_issue_count(),
                issue_center.resolved_issue_count(),
                health_status_label(issue_center.health_status()),
            ),
            (0, 1, "healthy")
        );
    }

    #[test]
    fn persistence_status_success_clears_prior_issue() {
        let mut issue_center = IssueCenter::default();

        apply_persistence_status(
            &mut issue_center,
            PersistenceStatusEvent {
                operation: "persist-watchlist",
                error: Some("database is locked".to_string()),
            },
        );
        apply_persistence_status(
            &mut issue_center,
            PersistenceStatusEvent {
                operation: "persist-watchlist",
                error: None,
            },
        );

        assert_eq!(issue_center.active_issue_count(), 0);
        assert_eq!(issue_center.resolved_issue_count(), 1);
    }

    #[test]
    fn live_source_status_raises_a_partial_feed_issue() {
        let mut issue_center = IssueCenter::default();

        apply_live_source_status(
            &mut issue_center,
            &LiveSourceStatus {
                tracked_symbols: 8,
                fresh_symbols: 6,
                stale_symbols: 0,
                degraded_symbols: 2,
                unavailable_symbols: 2,
                last_error: Some("provider timeout".to_string()),
            },
        );

        let issue = issue_center.sorted_entries()[0].clone();

        assert_eq!(
            (
                health_status_label(issue_center.health_status()),
                issue.title,
                issue.active,
                issue.detail.contains("Degraded 2"),
                issue.detail.contains("Unavailable 2"),
            ),
            (
                "degraded",
                "Live source partially degraded".to_string(),
                true,
                true,
                true,
            )
        );
    }

    #[test]
    fn live_source_status_resolves_partial_issue_for_a_healthy_window() {
        let mut issue_center = IssueCenter::default();

        apply_live_source_status(
            &mut issue_center,
            &LiveSourceStatus {
                tracked_symbols: 32,
                fresh_symbols: 32,
                stale_symbols: 0,
                degraded_symbols: 0,
                unavailable_symbols: 0,
                last_error: None,
            },
        );

        assert_eq!(issue_center.active_issue_count(), 0);
        assert_eq!(health_status_label(issue_center.health_status()), "healthy");
    }

    #[test]
    fn live_source_status_keeps_partial_issue_for_a_partial_refresh_window() {
        let mut issue_center = IssueCenter::default();

        apply_live_source_status(
            &mut issue_center,
            &LiveSourceStatus {
                tracked_symbols: 503,
                fresh_symbols: 64,
                stale_symbols: 0,
                degraded_symbols: 0,
                unavailable_symbols: 439,
                last_error: None,
            },
        );

        let issue = issue_center.sorted_entries()[0].clone();
        assert_eq!(
            health_status_label(issue_center.health_status()),
            "degraded"
        );
        assert_eq!(issue.title, "Live source partially degraded");
        assert!(issue.detail.contains("Fresh 64"));
    }

    #[test]
    fn feed_refresh_publishes_symbol_updates_before_final_source_status() {
        let (sender, receiver) = mpsc::channel();
        let publisher = AppEventPublisher::new(sender);
        let symbols = vec!["AAPL".to_string(), "MSFT".to_string(), "AMD".to_string()];
        let mut fetch_results = vec![
            Ok(live_feed("AAPL")),
            Ok(super::market_data::ProviderFetchResult {
                symbol: "MSFT".to_string(),
                snapshot: None,
                external_signal: None,
                fundamentals: None,
                coverage: super::market_data::ProviderCoverage {
                    core: super::market_data::ProviderComponentState::Missing,
                    external: super::market_data::ProviderComponentState::Missing,
                    fundamentals: super::market_data::ProviderComponentState::Missing,
                },
                diagnostics: vec![super::market_data::ProviderDiagnostic {
                    component: super::market_data::ProviderComponent::Core,
                    kind: super::market_data::ProviderDiagnosticKind::Missing,
                    detail: "core snapshot is missing target mean price".to_string(),
                    retryable: false,
                }],
            }),
            Ok(live_feed("AMD")),
        ]
        .into_iter();

        assert!(publish_feed_refresh(&publisher, &symbols, None, |_, _| {
            fetch_results
                .next()
                .expect("each symbol should have one fetch result")
        }));

        let first_batch = recv_feed_batch(&receiver, "first feed batch");
        let second_batch = recv_feed_batch(&receiver, "second feed batch");
        let third_batch = recv_feed_batch(&receiver, "third feed batch");
        let final_batch = recv_feed_batch(&receiver, "final feed batch");

        assert_eq!(first_batch.len(), 3);
        assert!(
            matches!(first_batch.first(), Some(FeedEvent::Snapshot(snapshot)) if snapshot.symbol == "AAPL")
        );
        assert!(
            matches!(first_batch.get(1), Some(FeedEvent::External(signal)) if signal.symbol == "AAPL")
        );
        assert!(
            matches!(first_batch.get(2), Some(FeedEvent::Fundamentals(fundamentals)) if fundamentals.symbol == "AAPL")
        );

        assert_eq!(second_batch.len(), 1);
        assert!(
            matches!(second_batch.first(), Some(FeedEvent::Coverage(coverage)) if coverage.symbol == "MSFT")
        );

        assert_eq!(third_batch.len(), 3);
        assert!(
            matches!(third_batch.first(), Some(FeedEvent::Snapshot(snapshot)) if snapshot.symbol == "AMD")
        );
        assert!(
            matches!(third_batch.get(1), Some(FeedEvent::External(signal)) if signal.symbol == "AMD")
        );
        assert!(
            matches!(third_batch.get(2), Some(FeedEvent::Fundamentals(fundamentals)) if fundamentals.symbol == "AMD")
        );

        assert_eq!(final_batch.len(), 1);
        assert!(matches!(
            final_batch.first(),
            Some(FeedEvent::SourceStatus(super::LiveSourceStatus {
                tracked_symbols: 3,
                fresh_symbols: 2,
                stale_symbols: 0,
                degraded_symbols: 1,
                unavailable_symbols: 0,
                last_error: None,
            }))
        ));

        assert!(receiver.try_recv().is_err());
    }

    #[test]
    fn apply_feed_events_keeps_existing_fundamentals_when_refresh_omits_them() {
        let mut state = TerminalState::new(2_000, 30, 8);
        let fundamentals =
            sample_fundamentals("NVDA", "technology", "Technology", "software", "Software");
        state.ingest_snapshot(MarketSnapshot {
            symbol: "NVDA".to_string(),
            company_name: None,
            profitable: true,
            market_price_cents: 1_200,
            intrinsic_value_cents: 1_800,
        });
        state.ingest_fundamentals(fundamentals.clone());

        let refresh_without_fundamentals =
            build_symbol_feed_batch(super::market_data::ProviderFetchResult {
                symbol: "NVDA".to_string(),
                snapshot: Some(MarketSnapshot {
                    symbol: "NVDA".to_string(),
                    company_name: None,
                    profitable: true,
                    market_price_cents: 1_250,
                    intrinsic_value_cents: 1_850,
                }),
                external_signal: None,
                fundamentals: None,
                coverage: super::market_data::ProviderCoverage {
                    core: super::market_data::ProviderComponentState::Fresh,
                    external: super::market_data::ProviderComponentState::Missing,
                    fundamentals: super::market_data::ProviderComponentState::Missing,
                },
                diagnostics: vec![],
            });

        let mut app = AppState::default();
        apply_feed_events(&mut state, &mut app, None, refresh_without_fundamentals);

        assert_eq!(
            state.detail("NVDA").and_then(|detail| detail.fundamentals),
            Some(fundamentals)
        );
    }

    #[test]
    fn weighted_target_refresh_window_rotates_across_symbols() {
        let refreshed = (0..5)
            .filter(|index| should_refresh_weighted_target(*index, 4, 5, 2))
            .collect::<Vec<_>>();

        assert_eq!(
            (refreshed, next_weighted_target_refresh_cursor(4, 5, 2)),
            (vec![0, 4], 1)
        );
    }

    #[test]
    fn weighted_target_refresh_covers_every_symbol_across_windows() {
        let symbol_count = 100usize;
        let refresh_window = 32usize;
        let refresh_budget = 8usize;
        let mut symbol_refresh_cursor = 0usize;
        let mut refreshed_symbols = HashSet::new();

        for _ in 0..25 {
            let window_symbols = (0..refresh_window)
                .map(|offset| (symbol_refresh_cursor + offset) % symbol_count)
                .collect::<Vec<_>>();
            let refreshed_in_window = window_symbols
                .iter()
                .copied()
                .filter(|symbol_index| {
                    should_refresh_weighted_target(
                        *symbol_index,
                        symbol_refresh_cursor,
                        symbol_count,
                        refresh_budget,
                    )
                })
                .collect::<Vec<_>>();

            assert_eq!(refreshed_in_window.len(), refresh_budget);
            refreshed_symbols.extend(refreshed_in_window);
            symbol_refresh_cursor = next_weighted_target_refresh_cursor(
                symbol_refresh_cursor,
                symbol_count,
                refresh_window,
            );
        }

        assert_eq!(refreshed_symbols.len(), symbol_count);
    }

    #[test]
    fn concurrent_refresh_uses_global_indexes_and_window_source_status() {
        let (sender, receiver) = mpsc::channel();
        let publisher = AppEventPublisher::new(sender);
        let refresh_plan = FeedRefreshPlan {
            phase_label: "steady",
            total_tracked_symbols: 40,
            retry_symbols: 0,
            concurrency: 1,
            symbols: vec![
                (32, "AAPL".to_string()),
                (33, "MSFT".to_string()),
                (34, "AMD".to_string()),
            ],
            next_symbol_cursor: 35,
        };
        let window_indexes = Arc::new(Mutex::new(Vec::new()));
        let indexes_for_fetch = Arc::clone(&window_indexes);

        let outcome = publish_feed_refresh_concurrently(
            &publisher,
            &refresh_plan,
            None,
            move |symbol_index, symbol| {
                indexes_for_fetch
                    .lock()
                    .expect("symbol indexes should be lockable")
                    .push((symbol_index, symbol.to_string()));
                Ok(live_feed(symbol))
            },
        );
        drop(publisher);

        let first_batch = recv_feed_batch(&receiver, "first feed batch");
        let second_batch = recv_feed_batch(&receiver, "second feed batch");
        let third_batch = recv_feed_batch(&receiver, "third feed batch");

        assert_eq!(
            window_indexes
                .lock()
                .expect("symbol indexes should be readable")
                .clone(),
            vec![
                (32, "AAPL".to_string()),
                (33, "MSFT".to_string()),
                (34, "AMD".to_string()),
            ]
        );
        assert!(matches!(
            outcome,
            Some(super::FeedRefreshOutcome {
                fresh_symbols: 3,
                degraded_symbols: 0,
                unavailable_symbols: 0,
                throttled_errors: 0,
                ..
            })
        ));
        assert!(matches!(
            first_batch.first(),
            Some(FeedEvent::Snapshot(snapshot)) if snapshot.symbol == "AAPL"
        ));
        assert!(matches!(
            second_batch.first(),
            Some(FeedEvent::Snapshot(snapshot)) if snapshot.symbol == "MSFT"
        ));
        assert!(matches!(
            third_batch.first(),
            Some(FeedEvent::Snapshot(snapshot)) if snapshot.symbol == "AMD"
        ));
        let final_batch = recv_feed_batch(&receiver, "final source status batch");
        assert!(matches!(
            final_batch.first(),
            Some(FeedEvent::SourceStatus(LiveSourceStatus {
                tracked_symbols: 40,
                fresh_symbols: 3,
                stale_symbols: 0,
                degraded_symbols: 0,
                unavailable_symbols: 0,
                last_error: None,
            }))
        ));
    }

    #[test]
    fn retry_classifier_distinguishes_throttle_from_hard_not_found() {
        assert!(is_provider_throttle_error(
            "HTTP status client error (429 Too Many Requests) for url (...)"
        ));
        assert!(is_retryable_feed_error(
            "HTTP status client error (429 Too Many Requests) for url (...)"
        ));
        assert!(!is_retryable_feed_error(
            "HTTP status client error (404 Not Found) for url (...)"
        ));
    }

    #[test]
    fn load_initial_state_recovers_from_invalid_journal_files() {
        let journal_path = unique_test_path("journal");
        fs::write(&journal_path, "not-a-valid-entry\n")
            .expect("test journal fixture should be written");

        let loaded = load_initial_state(&RuntimeOptions {
            journal_file: Some(journal_path.clone()),
            ..RuntimeOptions::default()
        })
        .expect("invalid journal files should fall back to an empty session");

        let _ = fs::remove_file(&journal_path);

        assert_eq!(
            (
                loaded.state.symbol_count(),
                loaded.startup_issues.len(),
                loaded.startup_issues[0].key,
                loaded.startup_issues[0].title,
            ),
            (0, 1, ISSUE_KEY_JOURNAL_RESTORE, "Journal restore failed")
        );
    }

    #[test]
    fn load_initial_state_recovers_from_unreadable_watchlist_paths() {
        let watchlist_path = unique_test_path("watchlist");
        fs::create_dir_all(&watchlist_path)
            .expect("test watchlist fixture directory should be created");

        let loaded = load_initial_state(&RuntimeOptions {
            watchlist_file: Some(watchlist_path.clone()),
            ..RuntimeOptions::default()
        })
        .expect("unreadable watchlist paths should fall back to an empty watchlist");

        let _ = fs::remove_dir(&watchlist_path);

        assert_eq!(
            (
                loaded.state.symbol_count(),
                loaded.startup_issues.len(),
                loaded.startup_issues[0].key,
                loaded.startup_issues[0].title,
            ),
            (
                0,
                1,
                ISSUE_KEY_WATCHLIST_RESTORE,
                "Watchlist restore failed"
            )
        );
    }

    #[test]
    fn load_initial_state_fails_for_invalid_replay_files() {
        let replay_path = unique_test_path("replay");
        fs::write(&replay_path, "not-a-valid-entry\n")
            .expect("test replay fixture should be written");

        let error = load_initial_state(&RuntimeOptions {
            replay_file: Some(replay_path.clone()),
            ..RuntimeOptions::default()
        })
        .err()
        .expect("invalid replay files should stay fatal");

        let _ = fs::remove_file(&replay_path);

        assert_eq!(
            (
                error.kind(),
                error.to_string(),
                error
                    .source()
                    .map(|source| source.to_string().starts_with("invalid journal line 1:")),
            ),
            (
                io::ErrorKind::InvalidData,
                format!("load replay file: {}", replay_path.display()),
                Some(true),
            )
        );
    }

    #[test]
    fn load_initial_state_restores_sqlite_warm_start_state() {
        let state_db = unique_test_path("warm-start.sqlite3");
        let _ = fs::remove_file(&state_db);

        persistence::load_warm_start(&state_db).expect("sqlite schema should initialize");
        let (sender, _receiver) = mpsc::channel();
        let publisher = AppEventPublisher::new(sender);
        let persistence_handle =
            persistence::spawn_worker(state_db.clone(), publisher).expect("worker should start");

        persistence_handle.replace_tracked_symbols(vec!["AAPL".to_string(), "MSFT".to_string()]);
        persistence_handle.replace_watchlist(vec!["AAPL".to_string()]);
        persistence_handle.replace_issues(vec![persistence::PersistedIssueRecord {
            key: "feed-partial".to_string(),
            source: IssueSource::Feed,
            severity: IssueSeverity::Warning,
            title: "Warm-start issue".to_string(),
            detail: "Restored from sqlite".to_string(),
            count: 1,
            first_seen_event: 1,
            last_seen_event: 1,
            active: true,
        }]);
        let snapshot = MarketSnapshot {
            symbol: "AAPL".to_string(),
            company_name: Some("Apple Inc.".to_string()),
            profitable: true,
            market_price_cents: 18_000,
            intrinsic_value_cents: 24_000,
        };
        let external_signal = ExternalValuationSignal {
            symbol: "AAPL".to_string(),
            fair_value_cents: 23_500,
            age_seconds: 60,
            low_fair_value_cents: Some(21_000),
            high_fair_value_cents: Some(26_000),
            analyst_opinion_count: Some(12),
            recommendation_mean_hundredths: Some(180),
            strong_buy_count: Some(6),
            buy_count: Some(4),
            hold_count: Some(2),
            sell_count: Some(0),
            strong_sell_count: Some(0),
            weighted_fair_value_cents: Some(24_200),
            weighted_analyst_count: Some(10),
        };
        let fundamentals = FundamentalSnapshot {
            symbol: "AAPL".to_string(),
            sector_key: Some("technology".to_string()),
            sector_name: Some("Technology".to_string()),
            industry_key: Some("consumer-electronics".to_string()),
            industry_name: Some("Consumer Electronics".to_string()),
            market_cap_dollars: Some(3_000_000_000_000),
            shares_outstanding: Some(15_000_000_000),
            trailing_pe_hundredths: Some(2850),
            forward_pe_hundredths: Some(2600),
            price_to_book_hundredths: Some(4800),
            return_on_equity_bps: Some(12_000),
            ebitda_dollars: Some(140_000_000_000),
            enterprise_value_dollars: Some(2_950_000_000_000),
            enterprise_to_ebitda_hundredths: Some(2_100),
            total_debt_dollars: Some(110_000_000_000),
            total_cash_dollars: Some(70_000_000_000),
            debt_to_equity_hundredths: Some(180),
            free_cash_flow_dollars: Some(100_000_000_000),
            operating_cash_flow_dollars: Some(120_000_000_000),
            beta_millis: Some(1200),
            trailing_eps_cents: Some(630),
            earnings_growth_bps: Some(900),
        };
        persistence_handle
            .persist_batch(
                Vec::new(),
                vec![persistence::SymbolRevisionInput {
                    symbol: "AAPL".to_string(),
                    evaluated_at: 1_700_000_000,
                    last_sequence: 3,
                    update_count: 3,
                    price_history: vec![
                        discount_screener::PriceHistoryPoint {
                            sequence: 1,
                            market_price_cents: 17_500,
                        },
                        discount_screener::PriceHistoryPoint {
                            sequence: 2,
                            market_price_cents: 17_800,
                        },
                        discount_screener::PriceHistoryPoint {
                            sequence: 3,
                            market_price_cents: 18_000,
                        },
                    ],
                    payload: persistence::EvaluatedSymbolState {
                        snapshot: Some(snapshot),
                        external_signal: Some(external_signal),
                        fundamentals: Some(fundamentals),
                        gap_bps: Some(checked_gap_bps(18_000, 24_000).expect("gap should compute")),
                        qualification: Some(QualificationStatus::Qualified),
                        external_status: Some(ExternalSignalStatus::Supportive),
                        confidence: Some(ConfidenceBand::High),
                        external_gap_bps: Some(
                            checked_gap_bps(18_000, 23_500).expect("external gap should compute"),
                        ),
                        weighted_gap_bps: Some(
                            checked_gap_bps(18_000, 24_200).expect("weighted gap should compute"),
                        ),
                        dcf_analysis: None,
                        dcf_signal: None,
                        dcf_margin_of_safety_bps: None,
                        relative_score: None,
                        chart_summaries: Vec::new(),
                        core_status: sample_metric_group_status(true),
                        fundamentals_status: sample_metric_group_status(true),
                        relative_status: sample_metric_group_status(false),
                        dcf_status: sample_metric_group_status(false),
                        chart_status: sample_metric_group_status(false),
                        is_watched: true,
                    },
                }],
            )
            .expect("symbol revision should persist");
        persistence_handle
            .persist_batch(
                vec![persistence::RawCapture {
                    symbol: "AAPL".to_string(),
                    capture_kind: persistence::CaptureKind::ChartCandles,
                    scope_key: Some(chart_range_label(ChartRange::Year).to_string()),
                    captured_at: 1_700_000_000,
                    payload: persistence::RawCapturePayload::Chart {
                        range: ChartRange::Year,
                        candles: historical_candles(),
                    },
                }],
                Vec::new(),
            )
            .expect("chart capture should persist");
        persistence_handle.shutdown(1_700_000_100);

        let loaded = load_initial_state(&RuntimeOptions {
            state_db: Some(state_db.clone()),
            persist_enabled: true,
            ..RuntimeOptions::default()
        })
        .expect("sqlite warm-start should load");

        let _ = fs::remove_file(&state_db);

        assert!(loaded.startup_issues.is_empty());
        assert_eq!(loaded.tracked_symbols, vec!["AAPL".to_string(), "MSFT".to_string()]);
        assert!(loaded.app.show_all_tracked_symbols_in_candidates);
        assert!(loaded.state.detail("AAPL").is_some());
        assert_eq!(
            loaded.state.price_history("AAPL", 10),
            vec![
                discount_screener::PriceHistoryPoint {
                    sequence: 1,
                    market_price_cents: 17_500,
                },
                discount_screener::PriceHistoryPoint {
                    sequence: 2,
                    market_price_cents: 17_800,
                },
                discount_screener::PriceHistoryPoint {
                    sequence: 3,
                    market_price_cents: 18_000,
                },
            ]
        );
        assert!(loaded.app.is_symbol_stale("AAPL"));
        assert_eq!(loaded.app.issue_center.active_issue_count(), 1);
        assert!(matches!(
            loaded.app.detail_chart_entry("AAPL"),
            Some(super::ChartCacheEntry::Ready { candles }) if candles == &historical_candles()
        ));
        assert!(loaded.app.is_chart_stale("AAPL", ChartRange::Year));
        assert!(loaded.app.chart_summary("AAPL", ChartRange::Year).is_some());
    }

    #[test]
    fn load_initial_state_uses_explicit_symbols_for_the_current_session_only() {
        let state_db = unique_test_path("session-symbols.sqlite3");
        let _ = fs::remove_file(&state_db);

        persistence::load_warm_start(&state_db).expect("sqlite schema should initialize");
        let (sender, _receiver) = mpsc::channel();
        let publisher = AppEventPublisher::new(sender);
        let persistence_handle =
            persistence::spawn_worker(state_db.clone(), publisher).expect("worker should start");
        persistence_handle.replace_tracked_symbols(vec!["MSTR".to_string()]);
        persist_sample_symbol_revision(&persistence_handle, "MSTR", 18_000, 24_000);
        persistence_handle.shutdown(1_700_000_100);

        let loaded = load_initial_state(&RuntimeOptions {
            state_db: Some(state_db.clone()),
            persist_enabled: true,
            symbols: vec!["JPM".to_string()],
            symbols_explicit: true,
            ..RuntimeOptions::default()
        })
        .expect("explicit symbols should define the session universe");

        let _ = fs::remove_file(&state_db);

        assert_eq!(loaded.tracked_symbols, vec!["JPM".to_string()]);
        assert!(loaded.state.detail("MSTR").is_none());
        assert!(loaded.app.show_all_tracked_symbols_in_candidates);
    }

    #[test]
    fn no_arg_startup_restores_the_previous_tracked_symbol_set() {
        let state_db = unique_test_path("default-sp500.sqlite3");
        let _ = fs::remove_file(&state_db);

        persistence::load_warm_start(&state_db).expect("sqlite schema should initialize");
        let (sender, _receiver) = mpsc::channel();
        let publisher = AppEventPublisher::new(sender);
        let persistence_handle =
            persistence::spawn_worker(state_db.clone(), publisher).expect("worker should start");
        persistence_handle.replace_tracked_symbols(vec!["MSTR".to_string()]);
        persist_sample_symbol_revision(&persistence_handle, "MSTR", 18_000, 24_000);
        persistence_handle.shutdown(1_700_000_100);

        let reloaded = load_initial_state(&RuntimeOptions {
            state_db: Some(state_db.clone()),
            persist_enabled: true,
            ..RuntimeOptions::default()
        })
        .expect("no-arg startup should restore the default sp500 universe");

        let _ = fs::remove_file(&state_db);

        assert_eq!(reloaded.tracked_symbols, vec!["MSTR".to_string()]);
        assert!(reloaded.app.show_all_tracked_symbols_in_candidates);
        assert!(reloaded.state.detail("MSTR").is_some());
    }

    #[test]
    fn no_arg_startup_can_restore_an_intentionally_empty_tracked_symbol_set() {
        let state_db = unique_test_path("empty-session.sqlite3");
        let _ = fs::remove_file(&state_db);

        persistence::load_warm_start(&state_db).expect("sqlite schema should initialize");
        let (sender, _receiver) = mpsc::channel();
        let publisher = AppEventPublisher::new(sender);
        let persistence_handle =
            persistence::spawn_worker(state_db.clone(), publisher).expect("worker should start");
        persistence_handle.replace_tracked_symbols(Vec::new());
        persistence_handle.shutdown(1_700_000_100);

        let reloaded = load_initial_state(&RuntimeOptions {
            state_db: Some(state_db.clone()),
            persist_enabled: true,
            ..RuntimeOptions::default()
        })
        .expect("no-arg startup should restore the saved tracked universe");

        let _ = fs::remove_file(&state_db);

        assert!(reloaded.tracked_symbols.is_empty());
        assert!(reloaded.app.show_all_tracked_symbols_in_candidates);
    }

    #[test]
    fn load_initial_state_uses_profile_symbols_for_the_current_session_only() {
        let state_db = unique_test_path("session-profile.sqlite3");
        let _ = fs::remove_file(&state_db);

        persistence::load_warm_start(&state_db).expect("sqlite schema should initialize");
        let (sender, _receiver) = mpsc::channel();
        let publisher = AppEventPublisher::new(sender);
        let persistence_handle =
            persistence::spawn_worker(state_db.clone(), publisher).expect("worker should start");
        persistence_handle.replace_tracked_symbols(vec!["MSTR".to_string()]);
        persist_sample_symbol_revision(&persistence_handle, "MSTR", 18_000, 24_000);
        persistence_handle.shutdown(1_700_000_100);

        let mut options =
            parse_runtime_options_from(["--profile", "dow-jones"]).expect("profile should parse");
        options.state_db = Some(state_db.clone());

        let loaded = load_initial_state(&options)
            .expect("profile symbols should define the session universe");

        let _ = fs::remove_file(&state_db);

        assert!(loaded.tracked_symbols.iter().any(|symbol| symbol == "JPM"));
        assert!(!loaded.tracked_symbols.iter().any(|symbol| symbol == "MSTR"));
        assert!(loaded.state.detail("MSTR").is_none());
    }

    #[test]
    fn load_initial_state_migrates_v2_sqlite_without_losing_rows() {
        let state_db = unique_test_path("warm-start-v2.sqlite3");
        let _ = fs::remove_file(&state_db);

        let connection = Connection::open(&state_db).expect("sqlite db should open");
        connection
            .execute_batch(
                "\
                PRAGMA user_version = 2;
                CREATE TABLE meta (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                );
                CREATE TABLE tracked_symbol (
                    position INTEGER NOT NULL,
                    symbol TEXT PRIMARY KEY
                );
                CREATE TABLE watchlist (
                    symbol TEXT PRIMARY KEY
                );
                CREATE TABLE raw_capture (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    symbol TEXT NOT NULL,
                    capture_kind TEXT NOT NULL,
                    scope_key TEXT,
                    captured_at INTEGER NOT NULL,
                    payload_json TEXT NOT NULL
                );
                CREATE INDEX raw_capture_symbol_idx ON raw_capture(symbol, captured_at, id);
                CREATE TABLE raw_latest (
                    symbol TEXT NOT NULL,
                    capture_key TEXT NOT NULL,
                    capture_id INTEGER NOT NULL,
                    PRIMARY KEY(symbol, capture_key)
                );
                CREATE TABLE symbol_revision (
                    revision_id INTEGER PRIMARY KEY AUTOINCREMENT,
                    symbol TEXT NOT NULL,
                    evaluated_at INTEGER NOT NULL,
                    last_sequence INTEGER NOT NULL,
                    update_count INTEGER NOT NULL,
                    payload_json TEXT NOT NULL,
                    snapshot_json TEXT,
                    external_json TEXT,
                    fundamentals_json TEXT
                );
                CREATE INDEX symbol_revision_symbol_idx
                    ON symbol_revision(symbol, evaluated_at, revision_id);
                CREATE TABLE symbol_latest (
                    symbol TEXT PRIMARY KEY,
                    revision_id INTEGER NOT NULL,
                    evaluated_at INTEGER NOT NULL,
                    last_sequence INTEGER NOT NULL,
                    update_count INTEGER NOT NULL,
                    payload_json TEXT NOT NULL,
                    snapshot_json TEXT,
                    external_json TEXT,
                    fundamentals_json TEXT
                );
                CREATE TABLE issue_state (
                    key TEXT PRIMARY KEY,
                    source TEXT NOT NULL,
                    severity TEXT NOT NULL,
                    title TEXT NOT NULL,
                    detail TEXT NOT NULL,
                    issue_count INTEGER NOT NULL,
                    first_seen_event INTEGER NOT NULL,
                    last_seen_event INTEGER NOT NULL,
                    active INTEGER NOT NULL
                );",
            )
            .expect("v2 schema should be created");
        connection
            .execute(
                "INSERT INTO tracked_symbol(position, symbol) VALUES (0, 'AAPL')",
                [],
            )
            .expect("tracked symbol should be inserted");
        connection
            .execute(
                "INSERT INTO symbol_latest(
                    symbol, revision_id, evaluated_at, last_sequence, update_count, payload_json, snapshot_json, external_json, fundamentals_json
                ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)",
                rusqlite::params![
                    "AAPL",
                    1_i64,
                    1_700_000_000_i64,
                    1_i64,
                    1_i64,
                    "{}",
                    serde_json::to_string(&MarketSnapshot {
                        symbol: "AAPL".to_string(),
                        company_name: None,
                        profitable: true,
                        market_price_cents: 18_000,
                        intrinsic_value_cents: 24_000,
                    })
                    .expect("snapshot should serialize"),
                    Option::<String>::None,
                    Option::<String>::None
                ],
            )
            .expect("latest symbol row should be inserted");
        drop(connection);

        let loaded = load_initial_state(&RuntimeOptions {
            state_db: Some(state_db.clone()),
            persist_enabled: true,
            ..RuntimeOptions::default()
        })
        .expect("v2 sqlite state should migrate and load");

        let connection = Connection::open(&state_db).expect("sqlite db should reopen");
        let has_price_history_json = connection
            .prepare("PRAGMA table_info(symbol_latest)")
            .expect("table info should prepare")
            .query_map([], |row| row.get::<_, String>(1))
            .expect("table info should query")
            .filter_map(Result::ok)
            .any(|name| name == "price_history_json");
        let _ = fs::remove_file(&state_db);

        assert!(loaded.state.detail("AAPL").is_some());
        assert!(has_price_history_json);
    }

    #[test]
    fn load_initial_state_resets_bad_sqlite_cache_and_keeps_persistence_enabled() {
        let state_db = unique_test_path("broken-warm-start.sqlite3");
        let _ = fs::remove_file(&state_db);

        persistence::load_warm_start(&state_db).expect("sqlite schema should initialize");
        let connection = Connection::open(&state_db).expect("sqlite db should open");
        connection
            .execute(
                "INSERT INTO symbol_latest(
                    symbol, revision_id, evaluated_at, last_sequence, update_count, payload_json, snapshot_json, external_json, fundamentals_json
                 ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)",
                rusqlite::params![
                    "AAPL",
                    1_i64,
                    1_i64,
                    1_i64,
                    1_i64,
                    "{}",
                    "{not-json",
                    Option::<String>::None,
                    Option::<String>::None
                ],
            )
            .expect("corrupt revision row should be written");

        let loaded = load_initial_state(&RuntimeOptions {
            state_db: Some(state_db.clone()),
            persist_enabled: true,
            ..RuntimeOptions::default()
        })
        .expect("warm-start restore failures should fall back");

        assert_eq!(loaded.startup_issues.len(), 1);
        assert!(loaded.persistence_db_path.is_some());
        assert!(loaded.state.detail("AAPL").is_none());

        let reset = persistence::load_warm_start(&state_db).expect("reset sqlite db should load");
        let _ = fs::remove_file(&state_db);

        assert!(reset.chart_cache.is_empty());
        assert!(reset.symbol_states.is_empty());
        assert!(reset.last_persisted_at.is_none());
    }

    #[test]
    fn reconcile_sqlite_persistence_retries_after_a_busy_write() {
        let state_db = unique_test_path("busy-warm-start.sqlite3");
        let _ = fs::remove_file(&state_db);
        persistence::load_warm_start(&state_db).expect("sqlite schema should initialize");

        let lock_connection = Connection::open(&state_db).expect("sqlite db should open");
        lock_connection
            .execute_batch("BEGIN EXCLUSIVE TRANSACTION;")
            .expect("exclusive lock should be acquired");

        let (sender, _receiver) = mpsc::channel();
        let publisher = AppEventPublisher::new(sender);
        let persistence_handle =
            persistence::spawn_worker(state_db.clone(), publisher).expect("worker should start");

        let mut state = TerminalState::new(2_000, 30, 8);
        state.ingest_snapshot(MarketSnapshot {
            symbol: "NVDA".to_string(),
            company_name: None,
            profitable: true,
            market_price_cents: 17_270,
            intrinsic_value_cents: 26_923,
        });

        let mut last_persisted_sequence = 0;
        let app = AppState::default();
        let first_result = reconcile_sqlite_persistence(
            &state,
            &app,
            Some(&persistence_handle),
            &mut last_persisted_sequence,
        );

        assert!(first_result.is_err());
        assert_eq!(last_persisted_sequence, 0);

        lock_connection
            .execute_batch("COMMIT;")
            .expect("exclusive lock should be released");

        let second_result = reconcile_sqlite_persistence(
            &state,
            &app,
            Some(&persistence_handle),
            &mut last_persisted_sequence,
        );
        assert!(second_result.is_ok());
        persistence_handle.shutdown(1_700_000_000);

        let reloaded = persistence::load_warm_start(&state_db).expect("sqlite db should load");
        let _ = fs::remove_file(&state_db);

        assert_eq!(last_persisted_sequence, state.latest_sequence());
        assert!(reloaded
            .symbol_states
            .iter()
            .any(|record| record.symbol == "NVDA"));
    }

    #[test]
    fn metadata_only_sqlite_updates_do_not_refresh_warm_start_age() {
        let state_db = unique_test_path("metadata-only.sqlite3");
        let _ = fs::remove_file(&state_db);

        persistence::load_warm_start(&state_db).expect("sqlite schema should initialize");
        let (sender, _receiver) = mpsc::channel();
        let publisher = AppEventPublisher::new(sender);
        let persistence_handle =
            persistence::spawn_worker(state_db.clone(), publisher).expect("worker should start");
        persistence_handle.replace_tracked_symbols(vec!["AAPL".to_string()]);
        persistence_handle.replace_watchlist(vec!["AAPL".to_string()]);
        persistence_handle.replace_issues(vec![persistence::PersistedIssueRecord {
            key: "feed-partial".to_string(),
            source: IssueSource::Feed,
            severity: IssueSeverity::Warning,
            title: "Warm-start issue".to_string(),
            detail: "Restored from sqlite".to_string(),
            count: 1,
            first_seen_event: 1,
            last_seen_event: 1,
            active: true,
        }]);
        persistence_handle.shutdown(1_700_000_000);

        let loaded = persistence::load_warm_start(&state_db).expect("sqlite db should load");
        let _ = fs::remove_file(&state_db);

        assert_eq!(loaded.last_persisted_at, None);
        assert_eq!(loaded.tracked_symbols, vec!["AAPL".to_string()]);
        assert_eq!(loaded.watchlist, vec!["AAPL".to_string()]);
        assert_eq!(loaded.issues.len(), 1);
    }

    #[test]
    fn reconcile_capture_persistence_raises_and_resolves_sqlite_issue() {
        let state_db = unique_test_path("capture-persist.sqlite3");
        let _ = fs::remove_file(&state_db);
        persistence::load_warm_start(&state_db).expect("sqlite schema should initialize");

        let lock_connection = Connection::open(&state_db).expect("sqlite db should open");
        lock_connection
            .execute_batch("BEGIN EXCLUSIVE TRANSACTION;")
            .expect("exclusive lock should be acquired");

        let (sender, _receiver) = mpsc::channel();
        let publisher = AppEventPublisher::new(sender);
        let persistence_handle =
            persistence::spawn_worker(state_db.clone(), publisher).expect("worker should start");
        let mut app = AppState::default();
        let capture = persistence::RawCapture {
            symbol: "AAPL".to_string(),
            capture_kind: persistence::CaptureKind::ChartCandles,
            scope_key: Some(chart_range_label(ChartRange::Year).to_string()),
            captured_at: 1_700_000_000,
            payload: persistence::RawCapturePayload::Chart {
                range: ChartRange::Year,
                candles: historical_candles(),
            },
        };

        reconcile_capture_persistence(
            &mut app,
            &persistence_handle,
            "persist chart capture",
            vec![capture.clone()],
            Vec::new(),
        );

        assert_eq!(app.issue_center.active_issue_count(), 1);

        lock_connection
            .execute_batch("COMMIT;")
            .expect("exclusive lock should be released");

        reconcile_capture_persistence(
            &mut app,
            &persistence_handle,
            "persist chart capture",
            vec![capture],
            Vec::new(),
        );

        assert_eq!(app.issue_center.active_issue_count(), 0);
        persistence_handle.shutdown(1_700_000_000);
        let _ = fs::remove_file(&state_db);
    }

    #[test]
    fn publish_input_events_forwards_supported_terminal_events() {
        let (sender, receiver) = mpsc::channel();
        let publisher = AppEventPublisher::new(sender);
        let key_event =
            KeyEvent::new_with_kind(KeyCode::Char('n'), KeyModifiers::NONE, KeyEventKind::Press);
        let mut events = vec![
            Ok(Event::Key(key_event)),
            Ok(Event::Resize(120, 40)),
            Err(io::Error::new(io::ErrorKind::UnexpectedEof, "stop")),
        ]
        .into_iter();

        publish_input_events(publisher, move || {
            events
                .next()
                .expect("test input sequence should contain a terminating event")
        });

        assert!(matches!(
            (
                receiver.recv().expect("key event should arrive"),
                receiver.recv().expect("resize event should arrive"),
                receiver.recv().expect("fatal event should arrive"),
            ),
            (
                AppEvent::Input(forwarded_key),
                AppEvent::Resize,
                AppEvent::Fatal(error),
            ) if forwarded_key == key_event
                && error.kind() == io::ErrorKind::UnexpectedEof
                && error.to_string() == "read terminal input event"
        ));
    }

    #[test]
    fn publish_input_events_surfaces_reader_failures_with_context() {
        let (sender, receiver) = mpsc::channel();
        let publisher = AppEventPublisher::new(sender);

        publish_input_events(publisher, || Err(io::Error::other("input disconnected")));

        assert!(matches!(
            receiver.recv().expect("fatal event should arrive"),
            AppEvent::Fatal(error)
                if error.kind() == io::ErrorKind::Other
                    && error.to_string() == "read terminal input event"
                    && error
                        .source()
                        .map(|source| source.to_string())
                        == Some("input disconnected".to_string())
        ));
    }

    #[test]
    fn feed_refresh_reports_last_error_and_continues_processing_symbols() {
        let (sender, receiver) = mpsc::channel();
        let publisher = AppEventPublisher::new(sender);
        let symbols = vec!["AAPL".to_string(), "MSFT".to_string(), "AMD".to_string()];
        let mut fetch_results = vec![
            Ok(live_feed("AAPL")),
            Err(io::Error::other("provider timeout")),
            Ok(live_feed("AMD")),
        ]
        .into_iter();

        assert!(publish_feed_refresh(&publisher, &symbols, None, |_, _| {
            fetch_results
                .next()
                .expect("each symbol should have one fetch result")
        }));

        let first_batch = recv_feed_batch(&receiver, "first feed batch");
        let second_batch = recv_feed_batch(&receiver, "second feed batch");
        let final_batch = recv_feed_batch(&receiver, "final feed batch");

        assert_eq!(
            (
                first_batch.len(),
                second_batch.len(),
                matches!(
                    final_batch.first(),
                    Some(FeedEvent::SourceStatus(LiveSourceStatus {
                        tracked_symbols: 3,
                        fresh_symbols: 2,
                        stale_symbols: 0,
                        degraded_symbols: 0,
                        unavailable_symbols: 1,
                        last_error: Some(last_error),
                    })) if last_error == "provider timeout"
                ),
            ),
            (3, 3, true)
        );
    }

    #[test]
    fn feed_refresh_appends_symbol_failures_to_feed_error_log() {
        let (sender, receiver) = mpsc::channel();
        let publisher = AppEventPublisher::new(sender);
        let symbols = vec!["AAPL".to_string(), "MSFT".to_string(), "AMD".to_string()];
        let log_path = unique_test_path("feed-errors.log");
        let logger = FeedErrorLogger::new(log_path.clone());
        let mut fetch_results = vec![
            Ok(live_feed("AAPL")),
            Ok(super::market_data::ProviderFetchResult {
                symbol: "MSFT".to_string(),
                snapshot: None,
                external_signal: None,
                fundamentals: None,
                coverage: super::market_data::ProviderCoverage {
                    core: super::market_data::ProviderComponentState::Missing,
                    external: super::market_data::ProviderComponentState::Missing,
                    fundamentals: super::market_data::ProviderComponentState::Missing,
                },
                diagnostics: vec![super::market_data::ProviderDiagnostic {
                    component: super::market_data::ProviderComponent::Core,
                    kind: super::market_data::ProviderDiagnosticKind::Missing,
                    detail: "core snapshot is missing target mean price".to_string(),
                    retryable: false,
                }],
            }),
            Err(io::Error::other("provider timeout")),
        ]
        .into_iter();

        assert!(publish_feed_refresh(
            &publisher,
            &symbols,
            Some(&logger),
            |_, _| {
                fetch_results
                    .next()
                    .expect("each symbol should have one fetch result")
            }
        ));

        drop(publisher);
        let mut state = TerminalState::new(2_000, 30, 8);
        let mut app = AppState::default();
        while let Ok(app_event) = receiver.try_recv() {
            if let AppEvent::FeedBatch(feed_events) = app_event {
                let applied = apply_feed_events(&mut state, &mut app, Some(&logger), feed_events);
                if applied.saw_source_status {
                    super::synthesize_live_source_status(
                        &state,
                        &mut app,
                        Some(&LiveSymbolState::new(symbols.clone())),
                    );
                    super::log_live_source_summary(Some(&logger), app.live_source_status());
                }
            }
        }

        let log_contents =
            fs::read_to_string(&log_path).expect("feed error log should be readable after refresh");
        let _ = fs::remove_file(&log_path);

        assert!(log_contents
            .contains("kind=provider_coverage symbol=MSFT component=core classification=missing"));
        assert!(log_contents.contains("kind=provider_result symbol=AAPL"));
        assert!(log_contents.contains("kind=provider_result symbol=MSFT"));
        assert!(log_contents.contains("kind=provider_error symbol=AMD"));
        assert!(log_contents
            .contains("kind=refresh_summary tracked=3 fresh=1 stale=0 degraded=1 unavailable=2"));
    }

    #[test]
    fn feed_loop_retries_client_initialization_on_the_next_refresh() {
        let (sender, receiver) = mpsc::channel();
        let publisher = AppEventPublisher::new(sender);
        let live_symbols = LiveSymbolState::new(vec!["AAPL".to_string()]);
        let (control_sender, control_receiver) = mpsc::channel();
        let mut build_attempts = 0usize;

        control_sender
            .send(super::FeedControl::RefreshNow)
            .expect("first refresh should queue");
        control_sender
            .send(super::FeedControl::RefreshNow)
            .expect("second refresh should queue");
        drop(control_sender);

        feed_loop_with_client_factory(publisher, control_receiver, live_symbols, None, move || {
            build_attempts += 1;
            if build_attempts == 1 {
                Err(io::Error::other("boot failed"))
            } else {
                Ok(FakeFeedClient {
                    calls: Arc::new(Mutex::new(Vec::new())),
                    results: Arc::new(Mutex::new(VecDeque::from(vec![Ok(live_feed("AAPL"))]))),
                })
            }
        });

        let first_batch = recv_feed_batch(&receiver, "first feed batch");
        let second_batch = recv_feed_batch(&receiver, "second feed batch");
        let third_batch = recv_feed_batch(&receiver, "third feed batch");

        assert!(matches!(
            first_batch.first(),
            Some(FeedEvent::SourceStatus(LiveSourceStatus {
                tracked_symbols: 1,
                fresh_symbols: 0,
                stale_symbols: 0,
                degraded_symbols: 0,
                unavailable_symbols: 1,
                last_error: Some(last_error),
            })) if last_error == "market data client initialization failed: boot failed"
        ));
        assert!(matches!(
            second_batch.first(),
            Some(FeedEvent::Snapshot(snapshot)) if snapshot.symbol == "AAPL"
        ));
        assert!(matches!(
            third_batch.first(),
            Some(FeedEvent::SourceStatus(LiveSourceStatus {
                tracked_symbols: 1,
                fresh_symbols: 1,
                stale_symbols: 0,
                degraded_symbols: 0,
                unavailable_symbols: 0,
                last_error: None,
            }))
        ));
    }

    #[test]
    fn feed_loop_logs_client_initialization_failures() {
        let (sender, receiver) = mpsc::channel();
        let publisher = AppEventPublisher::new(sender);
        let live_symbols = LiveSymbolState::new(vec!["AAPL".to_string()]);
        let (control_sender, control_receiver) = mpsc::channel();
        let log_path = unique_test_path("feed-errors-init.log");

        control_sender
            .send(super::FeedControl::RefreshNow)
            .expect("refresh should queue");
        drop(control_sender);

        feed_loop_with_client_factory(
            publisher,
            control_receiver,
            live_symbols,
            Some(FeedErrorLogger::new(log_path.clone())),
            || -> io::Result<FakeFeedClient> { Err(io::Error::other("boot failed")) },
        );

        while receiver.try_recv().is_ok() {}

        let log_contents = fs::read_to_string(&log_path)
            .expect("feed error log should be readable after init failure");
        let _ = fs::remove_file(&log_path);

        assert!(log_contents.contains("kind=client_init_error tracked=1"));
        assert!(log_contents.contains("detail=\"boot failed\""));
    }

    #[test]
    fn feed_loop_can_be_tested_with_an_injected_client_factory() {
        let (sender, receiver) = mpsc::channel();
        let publisher = AppEventPublisher::new(sender);
        let symbols = (0..10)
            .map(|index| format!("S{index:02}"))
            .collect::<Vec<_>>();
        let live_symbols = LiveSymbolState::new(symbols.clone());
        let (control_sender, control_receiver) = mpsc::channel();
        let calls = Arc::new(Mutex::new(Vec::new()));
        let results = symbols
            .iter()
            .chain(symbols.iter())
            .map(|symbol| Ok(live_feed(symbol)))
            .collect::<VecDeque<_>>();

        control_sender
            .send(super::FeedControl::RefreshNow)
            .expect("first refresh should queue");
        control_sender
            .send(super::FeedControl::RefreshNow)
            .expect("second refresh should queue");
        drop(control_sender);

        let calls_for_factory = Arc::clone(&calls);
        let results = Arc::new(Mutex::new(results));
        feed_loop_with_client_factory(publisher, control_receiver, live_symbols, None, move || {
            Ok(FakeFeedClient {
                calls: Arc::clone(&calls_for_factory),
                results: Arc::clone(&results),
            })
        });

        let source_status_batches = receiver
            .into_iter()
            .filter_map(|app_event| match app_event {
                AppEvent::FeedBatch(feed_events) => Some(feed_events),
                _ => None,
            })
            .filter(|feed_events| matches!(feed_events.first(), Some(FeedEvent::SourceStatus(_))))
            .count();
        let calls = calls
            .lock()
            .expect("fake client calls should be readable")
            .clone();
        let mut called_symbols = calls
            .iter()
            .map(|(symbol, _)| symbol.clone())
            .collect::<Vec<_>>();
        let mut expected_symbols = symbols.clone();
        called_symbols.sort();
        expected_symbols.sort();

        assert_eq!(source_status_batches, 1);
        assert_eq!(calls.len(), 10);
        assert_eq!(
            calls
                .iter()
                .filter(|(_, refresh_weighted_target)| *refresh_weighted_target)
                .count(),
            8
        );
        assert_eq!(called_symbols, expected_symbols);
    }

    #[test]
    fn reconcile_journal_persistence_keeps_sequence_when_append_fails() {
        let journal_path = unique_test_path("journal-dir");
        fs::create_dir_all(&journal_path)
            .expect("test journal fixture directory should be created");
        let mut state = TerminalState::new(2_000, 30, 8);
        let mut issue_center = IssueCenter::default();
        let mut last_persisted_sequence = 0;

        state.ingest_snapshot(MarketSnapshot {
            symbol: "NVDA".to_string(),
            company_name: None,
            profitable: true,
            market_price_cents: 17_270,
            intrinsic_value_cents: 26_923,
        });

        reconcile_journal_persistence(
            &state,
            Some(&journal_path),
            &mut last_persisted_sequence,
            &mut issue_center,
        );

        let _ = fs::remove_dir(&journal_path);
        let issue = issue_center.sorted_entries()[0].clone();

        assert_eq!(
            (
                last_persisted_sequence,
                issue_center.active_issue_count(),
                issue.title,
                issue.detail.starts_with("append journal file:"),
            ),
            (0, 1, "Journal persistence failed".to_string(), true)
        );
    }

    #[test]
    fn reconcile_journal_persistence_resolves_issue_after_successful_retry() {
        let bad_journal_path = unique_test_path("journal-dir");
        let good_journal_path = unique_test_path("journal-file");
        fs::create_dir_all(&bad_journal_path)
            .expect("test journal fixture directory should be created");
        let mut state = TerminalState::new(2_000, 30, 8);
        let mut issue_center = IssueCenter::default();
        let mut last_persisted_sequence = 0;

        state.ingest_snapshot(MarketSnapshot {
            symbol: "NVDA".to_string(),
            company_name: None,
            profitable: true,
            market_price_cents: 17_270,
            intrinsic_value_cents: 26_923,
        });

        reconcile_journal_persistence(
            &state,
            Some(&bad_journal_path),
            &mut last_persisted_sequence,
            &mut issue_center,
        );
        reconcile_journal_persistence(
            &state,
            Some(&good_journal_path),
            &mut last_persisted_sequence,
            &mut issue_center,
        );

        let _ = fs::remove_dir(&bad_journal_path);
        let _ = fs::remove_file(&good_journal_path);

        assert_eq!(
            (
                last_persisted_sequence,
                issue_center.active_issue_count(),
                issue_center.resolved_issue_count(),
            ),
            (1, 0, 1)
        );
    }

    #[test]
    fn normalize_frame_clips_lines_to_the_viewport() {
        let lines = vec![
            RenderLine {
                color: Some(Color::Yellow),
                text: "ABCDEFGHIJ".to_string(),
            },
            RenderLine {
                color: None,
                text: "SECOND".to_string(),
            },
        ];

        assert_eq!(
            normalize_frame(&lines, 5, 1),
            vec![RenderLine {
                color: Some(Color::Yellow),
                text: "ABCDE".to_string(),
            }]
        );
    }

    #[test]
    fn collect_dirty_rows_only_marks_changed_visible_rows() {
        let previous_frame = vec![
            RenderLine {
                color: None,
                text: "same".to_string(),
            },
            RenderLine {
                color: None,
                text: "old".to_string(),
            },
            RenderLine {
                color: None,
                text: "offscreen".to_string(),
            },
        ];
        let next_frame = vec![
            RenderLine {
                color: None,
                text: "same".to_string(),
            },
            RenderLine {
                color: None,
                text: "new".to_string(),
            },
        ];

        assert_eq!(collect_dirty_rows(&previous_frame, &next_frame, 2), vec![1]);
    }

    #[test]
    fn collect_clear_rows_only_clears_visible_stale_rows() {
        assert_eq!(collect_clear_rows(10, 20, 30), (10..20).collect::<Vec<_>>());
        assert_eq!(collect_clear_rows(10, 20, 10), Vec::<usize>::new());
    }

    #[test]
    fn clip_text_to_width_returns_an_empty_string_for_zero_width() {
        assert_eq!(clip_text_to_width("ABC", 0), String::new());
    }

    #[test]
    fn backspace_leaves_input_mode_when_buffer_is_empty() {
        let mut buffer = String::new();

        assert!(should_leave_input_mode_on_backspace(&mut buffer));
    }

    #[test]
    fn backspace_deletes_text_before_leaving_input_mode() {
        let mut buffer = "NV".to_string();

        assert_eq!(
            (should_leave_input_mode_on_backspace(&mut buffer), buffer),
            (false, "N".to_string())
        );
    }

    #[test]
    fn q_exits_from_ticker_detail_overlay() {
        let mut state = TerminalState::new(2_000, 30, 8);
        let mut app = AppState::default();
        app.open_ticker_detail("NVDA");

        let result = handle_input_event(
            KeyEvent::new_with_kind(KeyCode::Char('q'), KeyModifiers::NONE, KeyEventKind::Press),
            &mut state,
            &mut app,
            &[],
            0,
            true,
            None,
            None,
            None,
            None,
            None,
        )
        .expect("q should be handled");

        assert!(matches!(result, LoopControl::Exit));
    }

    #[test]
    fn q_exits_from_issue_log_overlay() {
        let mut state = TerminalState::new(2_000, 30, 8);
        let mut app = AppState::default();
        app.open_issue_log();

        let result = handle_input_event(
            KeyEvent::new_with_kind(KeyCode::Char('q'), KeyModifiers::NONE, KeyEventKind::Press),
            &mut state,
            &mut app,
            &[],
            0,
            true,
            None,
            None,
            None,
            None,
            None,
        )
        .expect("q should be handled");

        assert!(matches!(result, LoopControl::Exit));
    }

    #[test]
    fn q_is_still_treated_as_text_while_filtering() {
        let mut state = TerminalState::new(2_000, 30, 8);
        let mut app = AppState::default();
        app.input_mode = InputMode::FilterSearch(String::new());

        let result = handle_input_event(
            KeyEvent::new_with_kind(KeyCode::Char('q'), KeyModifiers::NONE, KeyEventKind::Press),
            &mut state,
            &mut app,
            &[],
            0,
            true,
            None,
            None,
            None,
            None,
            None,
        )
        .expect("q should be handled");

        assert!(matches!(result, LoopControl::Continue));
        assert!(matches!(app.input_mode, InputMode::FilterSearch(ref buffer) if buffer == "q"));
    }

    #[test]
    fn ticker_detail_overlay_tracks_the_selected_symbol() {
        let mut app = AppState::default();

        app.open_ticker_detail("NVDA");

        assert_eq!(app.detail_symbol(), Some("NVDA"));
    }

    #[test]
    fn ticker_detail_overlay_can_move_between_rows() {
        let mut app = AppState::default();
        let rows = vec![candidate("ALFA", 3_000), candidate("BETA", 2_000)];
        app.open_ticker_detail("ALFA");

        app.move_ticker_detail_selection(&rows, 1);

        assert_eq!(
            (app.detail_symbol(), &app.overlay_mode),
            (Some("BETA"), &OverlayMode::TickerDetail("BETA".to_string()))
        );
    }

    #[test]
    fn queue_detail_chart_request_uses_default_year_range() {
        let mut app = AppState::default();
        let (sender, receiver) = mpsc::channel();
        app.open_ticker_detail("NVDA");

        app.queue_detail_chart_request(Some(&sender));

        assert!(matches!(
            receiver.recv().expect("chart request should be queued"),
            super::ChartControl::Load {
                symbol,
                range: ChartRange::Year,
                request_id: 1,
                ..
            } if symbol == "NVDA"
        ));
    }

    #[test]
    fn queue_detail_chart_request_skips_ready_cache_entries() {
        let mut app = AppState::default();
        let (sender, receiver) = mpsc::channel();
        app.open_ticker_detail("NVDA");
        app.chart_cache.insert(
            super::ChartCacheKey::new("NVDA", ChartRange::Year),
            super::ChartCacheEntry::Ready {
                candles: historical_candles(),
            },
        );

        app.queue_detail_chart_request(Some(&sender));

        assert!(receiver.try_recv().is_err());
    }

    #[test]
    fn queue_detail_chart_request_refreshes_stale_persisted_cache_entries() {
        let mut app = AppState::default();
        let (sender, receiver) = mpsc::channel();
        let stale_symbols = Vec::<String>::new();
        app.load_warm_start(
            &[persistence::PersistedChartRecord {
                symbol: "NVDA".to_string(),
                range: ChartRange::Year,
                candles: historical_candles(),
                fetched_at: 1_700_000_000,
            }],
            Some(1_700_000_000),
            &stale_symbols,
        );
        app.open_ticker_detail("NVDA");

        app.queue_detail_chart_request(Some(&sender));

        assert!(matches!(
            receiver.recv().expect("stale chart should trigger a refresh"),
            super::ChartControl::Load {
                symbol,
                range: ChartRange::Year,
                request_id: 1,
                ..
            } if symbol == "NVDA"
        ));
        assert!(matches!(
            app.detail_chart_entry("NVDA"),
            Some(super::ChartCacheEntry::Loading {
                previous: Some(candles),
                ..
            }) if !candles.is_empty()
        ));
    }

    #[test]
    fn queue_background_chart_requests_skip_cached_ranges_and_limit_batch_size() {
        let mut app = AppState::default();
        let (sender, receiver) = mpsc::channel();
        let symbols = ["SYM0", "SYM1", "SYM2"]
            .into_iter()
            .map(str::to_string)
            .collect::<Vec<_>>();
        for range in chart_ranges() {
            let key = super::ChartCacheKey::new("SYM0", range);
            let candles = historical_candles();
            app.chart_cache.insert(
                key.clone(),
                super::ChartCacheEntry::Ready {
                    candles: candles.clone(),
                },
            );
            app.chart_summary_cache
                .insert(key, summarize_chart_range(range, 1_700_000_000, &candles));
        }

        app.queue_background_chart_requests(Some(&sender), &symbols);

        let mut queued = Vec::new();
        while let Ok(request) = receiver.try_recv() {
            queued.push(request);
        }

        assert_eq!(queued.len(), BACKGROUND_CHART_REQUEST_BUDGET_PER_CYCLE);
        assert!(queued.iter().all(|request| {
            matches!(
                request,
                super::ChartControl::Load {
                    symbol,
                    kind: super::ChartRequestKind::Background,
                    ..
                } if symbol != "SYM0"
            )
        }));
    }

    #[test]
    fn background_chart_refresh_replaces_warm_start_cache_entry() {
        let mut app = AppState::default();
        let stale_symbols = Vec::<String>::new();
        let stale_candles = historical_candles();
        let refreshed_candles = vec![HistoricalCandle {
            epoch_seconds: 42,
            open_cents: 500,
            high_cents: 575,
            low_cents: 490,
            close_cents: 560,
            volume: 10,
        }];
        let key = super::ChartCacheKey::new("NVDA", ChartRange::Year);
        app.load_warm_start(
            &[persistence::PersistedChartRecord {
                symbol: "NVDA".to_string(),
                range: ChartRange::Year,
                candles: stale_candles.clone(),
                fetched_at: 1_700_000_000,
            }],
            Some(1_700_000_000),
            &stale_symbols,
        );
        app.background_chart_requests.insert(key.clone(), 7);

        let persisted = app.apply_chart_data(super::ChartDataEvent {
            symbol: "NVDA".to_string(),
            range: ChartRange::Year,
            request_id: 7,
            kind: super::ChartRequestKind::Background,
            fetched_at: 1_700_000_100,
            result: Ok(refreshed_candles.clone()),
        });

        assert!(matches!(
            persisted,
            Some(persistence::PersistedChartRecord {
                symbol,
                range: ChartRange::Year,
                candles,
                fetched_at: 1_700_000_100,
            }) if symbol == "NVDA" && candles == refreshed_candles
        ));
        assert!(matches!(
            app.chart_cache.get(&key),
            Some(super::ChartCacheEntry::Ready { candles }) if candles == &refreshed_candles
        ));
        assert!(!app.is_chart_stale("NVDA", ChartRange::Year));
    }

    #[test]
    fn stale_chart_response_does_not_replace_newer_request() {
        let mut app = AppState::default();
        let (sender, _receiver) = mpsc::channel();
        app.open_ticker_detail("NVDA");

        app.queue_detail_chart_request(Some(&sender));
        app.chart_cache
            .remove(&super::ChartCacheKey::new("NVDA", ChartRange::Year));
        app.queue_detail_chart_request(Some(&sender));

        let persisted = app.apply_chart_data(super::ChartDataEvent {
            symbol: "NVDA".to_string(),
            range: ChartRange::Year,
            request_id: 1,
            kind: super::ChartRequestKind::Detail,
            fetched_at: 1_700_000_000,
            result: Ok(vec![HistoricalCandle {
                epoch_seconds: 1,
                open_cents: 1,
                high_cents: 2,
                low_cents: 1,
                close_cents: 2,
                volume: 1,
            }]),
        });

        assert!(persisted.is_none());
        assert!(matches!(
            app.detail_chart_entry("NVDA"),
            Some(super::ChartCacheEntry::Loading { request_id: 2, .. })
        ));
        assert!(app.chart_summary("NVDA", ChartRange::Year).is_none());
    }

    #[test]
    fn chart_loop_publishes_history_results() {
        let (sender, receiver) = mpsc::channel();
        let publisher = AppEventPublisher::new(sender);
        let (control_sender, control_receiver) = mpsc::channel();
        let calls = Arc::new(Mutex::new(Vec::new()));

        control_sender
            .send(super::ChartControl::Load {
                symbol: "AMD".to_string(),
                range: ChartRange::Month,
                request_id: 7,
                kind: super::ChartRequestKind::Detail,
            })
            .expect("chart control should accept the request");
        drop(control_sender);

        chart_loop_with_client_factory(publisher, control_receiver, {
            let calls = Arc::clone(&calls);
            move || {
                Ok(FakeChartClient {
                    calls: Arc::clone(&calls),
                    results: Arc::new(Mutex::new(VecDeque::from([Ok(historical_candles())]))),
                })
            }
        });

        assert_eq!(
            calls.lock().expect("calls should be readable").clone(),
            vec![("AMD".to_string(), ChartRange::Month)]
        );
        let chart_event = receiver.recv().expect("chart event should arrive");
        match chart_event {
            AppEvent::ChartData(super::ChartDataEvent {
                symbol,
                range,
                request_id,
                result,
                ..
            }) => {
                assert_eq!(symbol, "AMD");
                assert_eq!(range, ChartRange::Month);
                assert_eq!(request_id, 7);
                assert_eq!(
                    result.expect("chart fetch should succeed").len(),
                    historical_candles().len()
                );
            }
            _ => panic!("expected chart data event"),
        }
    }

    #[test]
    fn history_graph_mode_renders_core_tiles() {
        let mut app = AppState::default();
        app.history_cache
            .insert("AAPL".to_string(), sample_history_records("AAPL", true));

        let lines = build_ticker_history_lines_for_viewport(&app, "AAPL", 140, 28);

        assert!(lines.iter().any(|line| line.text.contains("view=Graphs")));
        assert!(lines.iter().any(|line| line.text.contains("Market price")));
        assert!(lines
            .iter()
            .any(|line| line.text.contains("Intrinsic value")));
    }

    #[test]
    fn history_relative_graph_mode_handles_empty_state() {
        let mut app = AppState::default();
        app.history_view.group = HistoryMetricGroup::Relative;
        app.history_cache
            .insert("AAPL".to_string(), sample_history_records("AAPL", false));

        let lines = build_ticker_history_lines_for_viewport(&app, "AAPL", 140, 24);

        assert!(lines
            .iter()
            .any(|line| line.text.contains("No graph tiles are available")));
    }

    #[test]
    fn history_graph_layout_switches_between_two_and_one_column_modes() {
        let mut app = AppState::default();
        app.history_cache
            .insert("AAPL".to_string(), sample_history_records("AAPL", true));

        let wide_lines = build_ticker_history_lines_for_viewport(&app, "AAPL", 140, 28);
        let narrow_lines = build_ticker_history_lines_for_viewport(&app, "AAPL", 90, 28);

        assert!(wide_lines.iter().any(|line| {
            line.text.contains("Market price") && line.text.contains("Intrinsic value")
        }));
        assert!(!narrow_lines.iter().any(|line| {
            line.text.contains("Market price") && line.text.contains("Intrinsic value")
        }));
    }

    #[test]
    fn history_graph_tile_uses_high_definition_graph_glyphs() {
        let tile = super::HistoryGraphTile {
            label: "Market price".to_string(),
            latest: "$13.00".to_string(),
            previous: "$12.00".to_string(),
            delta: "+1.00".to_string(),
            points: vec![10.0, 11.0, 12.5, 11.5, 13.0, 12.0, 13.5],
            min_label: "$10.00".to_string(),
            max_label: "$13.50".to_string(),
            footer_lines: vec!["Points 7".to_string(), "Metric market_price".to_string()],
        };

        let lines = super::render_history_graph_tile(&tile, 32);
        let plot_rows = &lines[3..7];

        assert!(
            plot_rows
                .iter()
                .flat_map(|row| row.chars())
                .any(|ch| !ch.is_ascii() && !ch.is_whitespace()),
            "expected non-ASCII graph glyphs in plot rows: {plot_rows:?}"
        );
    }

    #[test]
    fn toggle_history_subview_preserves_group_and_window() {
        let mut app = AppState::default();
        app.open_ticker_detail("AAPL");
        app.toggle_detail_tab();
        app.history_view.group = HistoryMetricGroup::Dcf;
        app.history_view.window = HistoryWindow::Year;

        app.toggle_history_subview();

        assert_eq!(app.history_view.subview, HistorySubview::Table);
        assert_eq!(app.history_view.group, HistoryMetricGroup::Dcf);
        assert_eq!(app.history_view.window, HistoryWindow::Year);
    }

    #[test]
    fn export_selected_history_bundle_writes_expected_csv_files() {
        let export_root = unique_test_path("history-export");
        let _ = fs::remove_dir_all(&export_root);

        let mut app = AppState::default();
        app.open_ticker_detail("AAPL");
        app.toggle_detail_tab();
        app.set_history_export_root(export_root.clone());
        app.history_cache
            .insert("AAPL".to_string(), sample_history_records("AAPL", true));

        let metadata =
            export_selected_history_bundle(&mut app, None).expect("history export should succeed");

        let expected_files = [
            "export_metadata.csv",
            "core_wide.csv",
            "fundamentals_wide.csv",
            "relative_wide.csv",
            "dcf_wide.csv",
            "chart_wide.csv",
            "all_tidy.csv",
        ];
        for filename in expected_files {
            assert!(
                metadata.export_dir.join(filename).exists(),
                "{filename} should exist"
            );
        }

        let core_csv =
            fs::read_to_string(metadata.export_dir.join("core_wide.csv")).expect("core csv");
        assert!(core_csv.contains("market_price_usd"));
        assert!(core_csv.contains("intrinsic_value_usd"));
        assert!(core_csv.contains("185.000000"));

        let chart_csv =
            fs::read_to_string(metadata.export_dir.join("chart_wide.csv")).expect("chart csv");
        assert!(chart_csv.contains("d_close_usd"));
        assert!(chart_csv.contains("y10_histogram_usd"));

        let tidy_csv =
            fs::read_to_string(metadata.export_dir.join("all_tidy.csv")).expect("tidy csv");
        assert!(tidy_csv.contains("group_key,metric_key,metric_label,range_key,unit,value"));
        assert!(tidy_csv.contains("core,market_price_usd,Market price,,usd"));
        assert!(tidy_csv.contains("chart,d_close_usd,D Close,d,usd"));

        let _ = fs::remove_dir_all(&export_root);
    }

    #[test]
    fn export_selected_history_bundle_loads_missing_history_from_sqlite() {
        let state_db = unique_test_path("history-export.sqlite3");
        let export_root = unique_test_path("history-export-on-demand");
        let _ = fs::remove_file(&state_db);
        let _ = fs::remove_dir_all(&export_root);

        persistence::load_warm_start(&state_db).expect("sqlite schema should initialize");
        let (sender, _receiver) = mpsc::channel();
        let publisher = AppEventPublisher::new(sender);
        let persistence_handle =
            persistence::spawn_worker(state_db.clone(), publisher).expect("worker should start");
        let record = sample_history_records("AAPL", true)
            .into_iter()
            .next()
            .expect("sample history should exist");
        persistence_handle
            .persist_batch(
                Vec::new(),
                vec![persistence::SymbolRevisionInput {
                    symbol: record.symbol.clone(),
                    evaluated_at: record.evaluated_at,
                    last_sequence: record.last_sequence,
                    update_count: record.update_count,
                    price_history: Vec::new(),
                    payload: record.payload.clone(),
                }],
            )
            .expect("history revision should persist");

        let mut app = AppState::default();
        app.open_ticker_detail("AAPL");
        app.toggle_detail_tab();
        app.set_history_export_root(export_root.clone());

        let metadata = export_selected_history_bundle(&mut app, Some(&persistence_handle))
            .expect("history export should load missing history");

        assert_eq!(metadata.revision_count, 1);
        assert_eq!(app.detail_history("AAPL").len(), 1);
        assert!(metadata.export_dir.join("export_metadata.csv").exists());

        persistence_handle.shutdown(1_700_000_000);
        let _ = fs::remove_file(&state_db);
        let _ = fs::remove_dir_all(&export_root);
    }

    #[test]
    fn qualification_justification_includes_actual_and_threshold_numbers() {
        let lines = qualification_justification_lines(&detail());

        assert_eq!(
            lines,
            vec![
                "Profitability gate: actual=yes  required=yes".to_string(),
                "Internal upside: actual=55.89%  required>=25.00%".to_string(),
                "Internal discount: $96.53 = $269.23 - $172.70".to_string(),
                "Result: qualified because profitable=yes and 55.89% >= 25.00%.".to_string(),
            ]
        );
    }

    #[test]
    fn confidence_justification_includes_external_signal_numbers() {
        let lines = confidence_justification_lines(&detail());

        assert_eq!(
            lines,
            vec![
                "External status: supportive".to_string(),
                "External fair value: $269.23  external upside: 55.89%  support threshold: >=25.00%".to_string(),
                "External signal age: 6s  freshness limit: <=30s".to_string(),
                "Result: high because internal qualification is qualified and external status is supportive.".to_string(),
            ]
        );
    }

    #[test]
    fn formatters_keep_minus_signs_for_small_negative_values() {
        assert_eq!(
            (format_money(-50), format_bps(-50)),
            ("-$0.50".to_string(), "-0.50%".to_string())
        );
    }

    #[test]
    fn compact_dollars_uses_m_b_t_suffixes() {
        assert_eq!(format_compact_dollars(500), "$500");
        assert_eq!(format_compact_dollars(1_500), "$1.5K");
        assert_eq!(format_compact_dollars(86_000_000), "$86.00M");
        assert_eq!(format_compact_dollars(1_500_000_000), "$1.50B");
        assert_eq!(format_compact_dollars(2_500_000_000_000), "$2.50T");
        assert_eq!(format_compact_dollars(-76_326_000), "-$76.33M");
        assert_eq!(format_compact_dollars(-1_200_000_000), "-$1.20B");
    }

    #[test]
    fn candidate_company_label_trims_very_long_names() {
        let label = candidate_company_label(
            "SMCI",
            Some("Super Micro Computer Holdings International and Subsidiaries"),
        );

        assert_eq!(label.chars().count(), CANDIDATE_COMPANY_COLUMN_WIDTH);
        assert!(label.ends_with("..."));
        assert!(label.starts_with("SMCI Super Micro Computer"));
    }

    #[test]
    fn weighted_gap_clamps_large_negative_values() {
        assert_eq!(checked_gap_bps(i64::MAX, 1), Some(i32::MIN));
    }

    #[test]
    fn weighted_gap_rejects_non_positive_targets() {
        assert_eq!(checked_gap_bps(10_000, 0), None);
    }

    #[test]
    fn aggregate_historical_candles_preserves_ohlc_when_compacting_width() {
        let candles = aggregate_historical_candles(
            &[
                HistoricalCandle {
                    epoch_seconds: 1,
                    open_cents: 10_000,
                    high_cents: 11_000,
                    low_cents: 9_000,
                    close_cents: 9_500,
                    volume: 100,
                },
                HistoricalCandle {
                    epoch_seconds: 2,
                    open_cents: 9_500,
                    high_cents: 12_000,
                    low_cents: 9_400,
                    close_cents: 11_500,
                    volume: 200,
                },
                HistoricalCandle {
                    epoch_seconds: 3,
                    open_cents: 11_500,
                    high_cents: 12_500,
                    low_cents: 11_200,
                    close_cents: 11_300,
                    volume: 300,
                },
                HistoricalCandle {
                    epoch_seconds: 4,
                    open_cents: 11_300,
                    high_cents: 13_000,
                    low_cents: 10_800,
                    close_cents: 12_800,
                    volume: 400,
                },
            ],
            2,
        );

        assert_eq!(candles.len(), 2);
        assert_eq!(
            (
                candles[0].open_cents,
                candles[0].high_cents,
                candles[0].low_cents,
                candles[0].close_cents
            ),
            (10_000, 12_000, 9_000, 11_500)
        );
        assert_eq!(
            (
                candles[1].open_cents,
                candles[1].high_cents,
                candles[1].low_cents,
                candles[1].close_cents
            ),
            (11_500, 13_000, 10_800, 12_800)
        );
        assert_eq!((candles[0].volume, candles[1].volume), (300, 700));
        assert!(candles[0].ema_20_cents.is_some());
        assert!(candles[1].macd_cents.is_some());
    }

    #[test]
    fn ema_series_matches_reference_values() {
        let ema_series = compute_ema_series(&[10_000, 20_000, 30_000], 2);

        assert_eq!(ema_series.len(), 3);
        assert_close(ema_series[0].expect("ema should exist"), 10_000.0, 0.001);
        assert_close(ema_series[1].expect("ema should exist"), 16_666.6667, 0.01);
        assert_close(ema_series[2].expect("ema should exist"), 25_555.5556, 0.01);
    }

    #[test]
    fn macd_series_matches_reference_calculation() {
        let closes = [
            10_000, 10_250, 10_500, 10_800, 11_100, 11_400, 11_800, 12_100, 12_500, 12_900, 13_200,
            13_600,
        ];
        let (macd_series, signal_series, histogram_series) = compute_macd_series(&closes);
        let ema_12 = compute_ema_series(&closes, 12);
        let ema_26 = compute_ema_series(&closes, 26);
        let expected_macd = ema_12
            .iter()
            .zip(ema_26.iter())
            .map(|(fast, slow)| fast.zip(*slow).map(|(fast, slow)| fast - slow))
            .collect::<Vec<_>>();

        assert_eq!(macd_series.len(), closes.len());
        assert_eq!(signal_series.len(), closes.len());
        assert_eq!(histogram_series.len(), closes.len());
        assert_close(
            macd_series
                .last()
                .copied()
                .flatten()
                .expect("macd should exist"),
            expected_macd
                .last()
                .copied()
                .flatten()
                .expect("expected macd should exist"),
            0.01,
        );
        let macd_tail = macd_series
            .last()
            .copied()
            .flatten()
            .expect("macd tail should exist");
        let signal_tail = signal_series
            .last()
            .copied()
            .flatten()
            .expect("signal tail should exist");
        assert_close(
            histogram_series
                .last()
                .copied()
                .flatten()
                .expect("histogram should exist"),
            macd_tail - signal_tail,
            0.01,
        );
    }

    #[test]
    fn ticker_detail_prioritizes_chart_and_drops_recent_context_on_short_viewports() {
        let mut state = TerminalState::new(2_000, 30, 16);
        for market_price_cents in [20_500, 20_900, 20_100, 21_300, 20_700, 21_000, 21_500] {
            state.ingest_snapshot(MarketSnapshot {
                symbol: "AMD".to_string(),
                company_name: Some("Advanced Micro Devices, Inc.".to_string()),
                profitable: true,
                market_price_cents,
                intrinsic_value_cents: 28_961,
            });
        }
        state.ingest_external(ExternalValuationSignal {
            symbol: "AMD".to_string(),
            fair_value_cents: 29_050,
            age_seconds: 0,
            low_fair_value_cents: Some(22_000),
            high_fair_value_cents: Some(36_500),
            analyst_opinion_count: Some(46),
            recommendation_mean_hundredths: Some(157),
            strong_buy_count: Some(4),
            buy_count: Some(33),
            hold_count: Some(12),
            sell_count: Some(0),
            strong_sell_count: Some(0),
            weighted_fair_value_cents: Some(22_000),
            weighted_analyst_count: Some(57),
        });

        let mut app = AppState::default();
        app.chart_cache.insert(
            super::ChartCacheKey::new("AMD", ChartRange::Year),
            super::ChartCacheEntry::Ready {
                candles: historical_candles(),
            },
        );
        let lines = build_ticker_detail_lines_for_viewport(&state, &app, "AMD", 96, 24);
        let visible_lines = lines
            .iter()
            .map(|line| visible_text(&line.text))
            .collect::<Vec<_>>();

        assert!(visible_lines
            .iter()
            .any(|line| line.starts_with("PRICE CHART  |  1Y")));
        assert!(visible_lines.iter().any(|line| line == "VALUATION MAP"));
        assert!(visible_lines.iter().any(|line| line == "CONSENSUS"));
        assert!(visible_lines.iter().any(|line| line == "EVIDENCE"));
        assert!(!visible_lines.iter().any(|line| line == "RECENT CONTEXT"));
        assert!(visible_lines
            .iter()
            .any(|line| line.contains("candles + EMA")));
        assert!(visible_lines.iter().any(|line| line.contains("-- VOLUME ")));
        assert!(visible_lines.iter().any(|line| line.contains("-- MACD ")));
        assert!(visible_lines.iter().any(|line| line.contains("EMA20")));
        assert!(
            visible_lines.iter().any(|line| contains_braille(line)),
            "detail chart should contain braille HD glyphs"
        );
        assert!(visible_lines
            .iter()
            .any(|line| line.contains("Ratings: SB")));
        assert!(!visible_lines.iter().any(|line| line == "QUALIFICATION"));
        assert!(!visible_lines.iter().any(|line| line == "CONFIDENCE"));
        assert!(!visible_lines
            .iter()
            .any(|line| line == "RECENT SYMBOL ALERTS"));
    }

    #[test]
    fn visible_rows_include_failed_tracked_symbols_without_snapshots() {
        let state = TerminalState::new(2_000, 30, 8);
        let mut app = AppState::default();
        app.set_tracked_symbols(vec!["IMAX".to_string()]);
        app.apply_symbol_coverage(
            &state,
            provider_error_coverage_event(
                "IMAX",
                "HTTP status client error (404 Not Found) for url (https://finance.yahoo.com/quote/IMAX/)",
            ),
        );

        let rows = app.visible_rows(&state);

        assert_eq!(
            rows.iter()
                .map(|row| row.symbol.as_str())
                .collect::<Vec<_>>(),
            vec!["IMAX"]
        );
    }

    #[test]
    fn explicit_symbol_sessions_show_loaded_tracked_symbols_even_when_confidence_is_low() {
        let mut state = TerminalState::new(2_000, 30, 8);
        state.ingest_snapshot(MarketSnapshot {
            symbol: "IMAX".to_string(),
            company_name: Some("IMAX Corporation".to_string()),
            profitable: true,
            market_price_cents: 10_000,
            intrinsic_value_cents: 11_000,
        });

        let mut app = AppState::default();
        app.set_show_all_tracked_symbols_in_candidates(true);
        app.set_tracked_symbols(vec!["IMAX".to_string()]);

        let rows = app.visible_rows(&state);

        assert_eq!(
            rows.iter()
                .map(|row| (row.symbol.as_str(), row.confidence, row.is_qualified))
                .collect::<Vec<_>>(),
            vec![("IMAX", ConfidenceBand::Low, false)]
        );
    }

    #[test]
    fn symbol_coverage_has_error_requires_an_error_diagnostic() {
        assert!(super::symbol_coverage_has_error(
            &provider_error_coverage_event("IMAX", "404 Not Found",)
        ));
        assert!(!super::symbol_coverage_has_error(
            &provider_missing_coverage_event("IMAX", "core snapshot is missing market price"),
        ));
    }

    #[test]
    fn provider_component_state_labels_remain_stable() {
        assert_eq!(
            super::provider_component_state_label(
                super::market_data::ProviderComponentState::Fresh
            ),
            "fresh"
        );
        assert_eq!(
            super::provider_component_state_label(
                super::market_data::ProviderComponentState::Missing,
            ),
            "missing"
        );
        assert_eq!(
            super::provider_component_state_label(
                super::market_data::ProviderComponentState::Error
            ),
            "error"
        );
    }

    #[test]
    fn main_screen_detail_summary_surfaces_unavailable_symbol_errors() {
        let state = TerminalState::new(2_000, 30, 8);
        let mut app = AppState::default();
        app.set_tracked_symbols(vec!["IMAX".to_string()]);
        app.apply_symbol_coverage(
            &state,
            provider_error_coverage_event(
                "IMAX",
                "HTTP status client error (404 Not Found) for url (https://finance.yahoo.com/quote/IMAX/)",
            ),
        );

        let rows = app.visible_rows(&state);
        let lines = build_screen_lines(&state, &rows, 0, 0, true, &app, None);
        let visible_lines = lines
            .iter()
            .map(|line| visible_text(&line.text))
            .collect::<Vec<_>>();

        assert!(visible_lines
            .iter()
            .any(|line| line.contains("IMAX") && line.contains("unavailable")));
        assert!(visible_lines
            .iter()
            .any(|line| line.contains("quote_html error") && line.contains("404 Not Found")));
    }

    #[test]
    fn ticker_detail_renders_provider_errors_for_unavailable_symbols() {
        let state = TerminalState::new(2_000, 30, 8);
        let mut app = AppState::default();
        app.set_tracked_symbols(vec!["IMAX".to_string()]);
        app.apply_symbol_coverage(
            &state,
            provider_error_coverage_event(
                "IMAX",
                "HTTP status client error (404 Not Found) for url (https://finance.yahoo.com/quote/IMAX/)",
            ),
        );

        let lines = build_ticker_detail_lines_for_viewport(&state, &app, "IMAX", 120, 28);
        let visible_lines = lines
            .iter()
            .map(|line| visible_text(&line.text))
            .collect::<Vec<_>>();

        assert!(visible_lines
            .iter()
            .any(|line| line.contains("IMAX  Position: 1/1")));
        assert!(visible_lines
            .iter()
            .any(|line| line.contains("Status unavailable")));
        assert!(visible_lines
            .iter()
            .any(|line| line.contains("quote_html error") && line.contains("404 Not Found")));
    }

    #[test]
    fn ticker_detail_compresses_volume_before_dropping_price_and_macd() {
        let mut state = TerminalState::new(2_000, 30, 16);
        state.ingest_snapshot(MarketSnapshot {
            symbol: "AMD".to_string(),
            company_name: Some("Advanced Micro Devices, Inc.".to_string()),
            profitable: true,
            market_price_cents: 20_500,
            intrinsic_value_cents: 28_961,
        });
        state.ingest_external(ExternalValuationSignal {
            symbol: "AMD".to_string(),
            fair_value_cents: 29_050,
            age_seconds: 0,
            low_fair_value_cents: Some(22_000),
            high_fair_value_cents: Some(36_500),
            analyst_opinion_count: Some(46),
            recommendation_mean_hundredths: Some(157),
            strong_buy_count: Some(4),
            buy_count: Some(33),
            hold_count: Some(12),
            sell_count: Some(0),
            strong_sell_count: Some(0),
            weighted_fair_value_cents: Some(22_000),
            weighted_analyst_count: Some(57),
        });

        let mut app = AppState::default();
        app.chart_cache.insert(
            super::ChartCacheKey::new("AMD", ChartRange::Year),
            super::ChartCacheEntry::Ready {
                candles: historical_candles(),
            },
        );

        let lines = build_ticker_detail_lines_for_viewport(&state, &app, "AMD", 90, 20);
        let visible_lines = lines
            .iter()
            .map(|line| visible_text(&line.text))
            .collect::<Vec<_>>();

        assert!(visible_lines
            .iter()
            .any(|line| line.contains("Volume compressed:")));
        assert!(visible_lines.iter().any(|line| line.contains("-- MACD ")));
        assert!(visible_lines
            .iter()
            .any(|line| line.contains("candles + EMA")));
    }

    #[test]
    fn ticker_detail_help_and_header_reflect_selected_chart_range() {
        let mut state = TerminalState::new(2_000, 30, 8);
        state.ingest_snapshot(MarketSnapshot {
            symbol: "NVDA".to_string(),
            company_name: None,
            profitable: true,
            market_price_cents: 17_270,
            intrinsic_value_cents: 26_923,
        });
        state.ingest_external(ExternalValuationSignal {
            symbol: "NVDA".to_string(),
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
            weighted_fair_value_cents: Some(27_850),
            weighted_analyst_count: Some(12),
        });

        let mut app = AppState::default();
        app.set_detail_chart_range(ChartRange::FiveYears);
        app.chart_cache.insert(
            super::ChartCacheKey::new("NVDA", ChartRange::FiveYears),
            super::ChartCacheEntry::Ready {
                candles: historical_candles(),
            },
        );

        let lines = build_ticker_detail_lines(&state, &app, "NVDA");

        assert_eq!(chart_range_label(app.detail_chart_range()), "5Y");
        assert!(lines[0].text.contains("1-6 range"));
        assert!(lines[0].text.contains("[/] cycle"));
        assert!(lines.iter().any(|line| line.text.contains("Chart 5Y")));
        assert!(lines
            .iter()
            .any(|line| line.text.starts_with("PRICE CHART  |  5Y")));
    }

    #[test]
    fn target_map_renders_unicode_markers_and_a_separate_legend() {
        let mut state = TerminalState::new(2_000, 30, 8);
        state.ingest_snapshot(MarketSnapshot {
            symbol: "NVDA".to_string(),
            company_name: None,
            profitable: true,
            market_price_cents: 17_270,
            intrinsic_value_cents: 26_923,
        });
        state.ingest_external(ExternalValuationSignal {
            symbol: "NVDA".to_string(),
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
            weighted_fair_value_cents: Some(27_850),
            weighted_analyst_count: Some(12),
        });

        let lines = build_ticker_detail_lines(&state, &AppState::default(), "NVDA");
        let target_map_line = lines
            .iter()
            .find(|line| line.text.starts_with("Target map:"))
            .expect("target map line should exist");
        let marker_legend_line = lines
            .iter()
            .find(|line| line.text.starts_with("Markers:"))
            .expect("marker legend line should exist");

        assert!(target_map_line.text.contains("$185.00 │"));
        assert!(target_map_line.text.contains('◆'));
        assert!(target_map_line.text.contains('▲'));
        assert!(target_map_line.text.contains('■'));
        assert!(target_map_line.text.ends_with("│ $320.00"));
        assert_eq!(
            marker_legend_line.text,
            "Markers: ● price<low  ◆ weighted  ▲ mean  ■ median"
        );
    }

    #[test]
    fn main_screen_shows_all_high_confidence_candidates_when_under_cap() {
        let mut state = TerminalState::new(2_000, 30, 8);

        for index in 0..13 {
            let symbol = format!("H{:02}", index);
            state.ingest_snapshot(MarketSnapshot {
                symbol: symbol.clone(),
                company_name: None,
                profitable: true,
                market_price_cents: 10_000,
                intrinsic_value_cents: 15_000,
            });
            state.ingest_external(ExternalValuationSignal {
                symbol,
                fair_value_cents: 13_000,
                age_seconds: 0,
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
            });
        }

        state.ingest_snapshot(MarketSnapshot {
            symbol: "LOW".to_string(),
            company_name: None,
            profitable: true,
            market_price_cents: 10_000,
            intrinsic_value_cents: 11_000,
        });

        let app = AppState::default();
        let rows = app.visible_rows(&state);

        assert_eq!(
            (
                rows.len(),
                rows.iter()
                    .all(|row| row.confidence == ConfidenceBand::High)
            ),
            (13, true)
        );
    }

    #[test]
    fn main_screen_caps_candidates_to_preserve_layout() {
        let mut state = TerminalState::new(2_000, 30, 8);

        for index in 0..25 {
            let symbol = format!("H{:02}", index);
            state.ingest_snapshot(MarketSnapshot {
                symbol: symbol.clone(),
                company_name: None,
                profitable: true,
                market_price_cents: 10_000,
                intrinsic_value_cents: 15_000,
            });
            state.ingest_external(ExternalValuationSignal {
                symbol,
                fair_value_cents: 13_000,
                age_seconds: 0,
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
            });
        }

        let app = AppState::default();
        let rows = app.visible_rows(&state);

        assert_eq!(rows.len(), MAX_VISIBLE_ROWS);
    }

    #[test]
    fn o_toggles_opportunities_view_from_normal_mode() {
        let mut state = TerminalState::new(2_000, 30, 8);
        let mut app = AppState::default();

        let result = handle_input_event(
            KeyEvent::new_with_kind(KeyCode::Char('o'), KeyModifiers::NONE, KeyEventKind::Press),
            &mut state,
            &mut app,
            &[],
            0,
            true,
            None,
            None,
            None,
            None,
            None,
        )
        .expect("o should be handled");

        assert!(matches!(result, LoopControl::Continue));
        assert_eq!(app.primary_view, PrimaryViewMode::Opportunities);
    }

    #[test]
    fn m_toggles_opportunity_scoring_model_inside_opportunities_view() {
        let mut state = TerminalState::new(2_000, 30, 8);
        let mut app = AppState::default();
        app.primary_view = PrimaryViewMode::Opportunities;

        let result = handle_input_event(
            KeyEvent::new_with_kind(KeyCode::Char('m'), KeyModifiers::NONE, KeyEventKind::Press),
            &mut state,
            &mut app,
            &[],
            0,
            true,
            None,
            None,
            None,
            None,
            None,
        )
        .expect("m should be handled");

        assert!(matches!(result, LoopControl::Continue));
        assert_eq!(
            app.opportunity_scoring_model,
            OpportunityScoringModel::Aggressive
        );
    }

    #[test]
    fn opportunities_view_renders_qualified_rows_outside_main_high_confidence_filter() {
        let mut state = TerminalState::new(2_000, 30, 8);
        let mut app = AppState::default();

        state.ingest_snapshot(MarketSnapshot {
            symbol: "TOP".to_string(),
            company_name: Some("Top Idea".to_string()),
            profitable: true,
            market_price_cents: 1_000,
            intrinsic_value_cents: 4_000,
        });
        state.ingest_fundamentals(fundamentals_with(
            "TOP",
            Some(120_000_000),
            Some(150_000_000),
            Some(2_400),
            Some(40),
            Some(500_000_000),
            Some(100_000_000),
            Some(1_200),
        ));
        app.chart_summary_cache.insert(
            super::ChartCacheKey::new("TOP", ChartRange::Year),
            year_summary(
                4_000,
                Some(3_200),
                Some(2_900),
                None,
                Some(180),
                Some(100),
                Some(80),
            ),
        );

        state.ingest_snapshot(MarketSnapshot {
            symbol: "NEXT".to_string(),
            company_name: Some("Next Idea".to_string()),
            profitable: true,
            market_price_cents: 1_000,
            intrinsic_value_cents: 1_600,
        });
        state.ingest_external(external_signal(
            "NEXT",
            1_400,
            Some(1_500),
            Some(6),
            Some(180),
        ));
        state.ingest_fundamentals(fundamentals_with(
            "NEXT",
            Some(20_000_000),
            Some(30_000_000),
            Some(600),
            Some(180),
            Some(50_000_000),
            Some(200_000_000),
            Some(-300),
        ));
        app.chart_summary_cache.insert(
            super::ChartCacheKey::new("NEXT", ChartRange::Year),
            year_summary(
                1_100,
                Some(1_200),
                Some(1_250),
                None,
                Some(-20),
                Some(10),
                Some(-30),
            ),
        );

        let candidate_rows = app.visible_rows(&state);
        assert_eq!(
            candidate_rows.len(),
            1,
            "main table should still only show high-confidence rows"
        );

        app.primary_view = PrimaryViewMode::Opportunities;
        let lines = opportunities_view_lines_for_viewport(&state, &mut app, 120, 22);

        assert!(lines.iter().any(|line| line.contains("TOP OPPORTUNITIES")));
        assert!(lines.iter().any(|line| line.contains("TOP")));
        assert!(lines.iter().any(|line| line.contains("NEXT")));
    }

    #[test]
    fn build_opportunity_rows_ranks_symbols_by_composite_signals() {
        let mut state = TerminalState::new(2_000, 30, 8);
        let mut app = AppState::default();

        state.ingest_snapshot(MarketSnapshot {
            symbol: "STRONG".to_string(),
            company_name: None,
            profitable: true,
            market_price_cents: 1_000,
            intrinsic_value_cents: 2_500,
        });
        state.ingest_external(external_signal(
            "STRONG",
            2_300,
            Some(2_400),
            Some(12),
            Some(140),
        ));
        let strong_fundamentals = fundamentals_with(
            "STRONG",
            Some(200_000_000),
            Some(250_000_000),
            Some(2_500),
            Some(60),
            Some(800_000_000),
            Some(100_000_000),
            Some(1_500),
        );
        state.ingest_fundamentals(strong_fundamentals.clone());
        app.chart_summary_cache.insert(
            super::ChartCacheKey::new("STRONG", ChartRange::Year),
            year_summary(
                2_450,
                Some(2_100),
                Some(1_900),
                Some(1_700),
                Some(220),
                Some(120),
                Some(100),
            ),
        );
        app.analysis_cache.insert(
            "STRONG".to_string(),
            super::AnalysisCacheEntry::Ready {
                input: analysis_input_key(&strong_fundamentals),
                analysis: dcf_analysis_fixture(2_600),
            },
        );

        state.ingest_snapshot(MarketSnapshot {
            symbol: "WEAK".to_string(),
            company_name: None,
            profitable: true,
            market_price_cents: 1_000,
            intrinsic_value_cents: 2_700,
        });
        state.ingest_external(external_signal(
            "WEAK",
            1_200,
            Some(1_300),
            Some(3),
            Some(260),
        ));
        let weak_fundamentals = fundamentals_with(
            "WEAK",
            Some(-10_000_000),
            Some(-5_000_000),
            Some(200),
            Some(220),
            Some(20_000_000),
            Some(200_000_000),
            Some(-700),
        );
        state.ingest_fundamentals(weak_fundamentals.clone());
        app.chart_summary_cache.insert(
            super::ChartCacheKey::new("WEAK", ChartRange::Year),
            year_summary(
                1_050,
                Some(1_200),
                Some(1_250),
                Some(1_350),
                Some(-50),
                Some(20),
                Some(-70),
            ),
        );
        app.analysis_cache.insert(
            "WEAK".to_string(),
            super::AnalysisCacheEntry::Ready {
                input: analysis_input_key(&weak_fundamentals),
                analysis: dcf_analysis_fixture(900),
            },
        );

        let rows = build_opportunity_rows(&state, &app);
        let ordered_symbols = rows.into_iter().map(|row| row.symbol).collect::<Vec<_>>();

        assert_eq!(
            ordered_symbols[..2],
            ["STRONG".to_string(), "WEAK".to_string()]
        );
    }

    #[test]
    fn score_opportunity_fundamentals_counts_each_positive_signal() {
        let mut selected_detail = detail();
        selected_detail.fundamentals = Some(fundamentals_with(
            "NVDA",
            Some(200_000_000),
            Some(250_000_000),
            Some(2_500),
            Some(40),
            Some(500_000_000),
            Some(100_000_000),
            Some(1_400),
        ));

        assert_eq!(
            score_opportunity_fundamentals(&selected_detail),
            (
                Some(5),
                vec!["FCF+", "OCF+", "ROE>10", "Balance", "Growth+"],
            )
        );
    }

    #[test]
    fn score_opportunity_fundamentals_uses_strict_positive_boundaries() {
        let mut selected_detail = detail();
        selected_detail.fundamentals = Some(fundamentals_with(
            "NVDA",
            Some(0),
            Some(0),
            Some(999),
            Some(40),
            Some(50_000_000),
            Some(100_000_000),
            Some(0),
        ));

        assert_eq!(
            score_opportunity_fundamentals(&selected_detail),
            (Some(1), vec!["Balance"])
        );
    }

    #[test]
    fn score_opportunity_fundamentals_accepts_cash_cover_when_leverage_is_high() {
        let mut selected_detail = detail();
        selected_detail.fundamentals = Some(fundamentals_with(
            "NVDA",
            Some(-1),
            Some(-1),
            Some(500),
            Some(250),
            Some(300_000_000),
            Some(100_000_000),
            Some(-100),
        ));

        assert_eq!(
            score_opportunity_fundamentals(&selected_detail),
            (Some(1), vec!["Balance"])
        );
    }

    #[test]
    fn score_opportunity_technicals_counts_each_confirmation_signal() {
        let summary = year_summary(
            2_450,
            Some(2_100),
            Some(1_900),
            Some(1_700),
            Some(220),
            Some(120),
            Some(100),
        );

        assert_eq!(
            score_opportunity_technicals(Some(&summary)),
            (
                Some(5),
                vec![">EMA20", ">EMA50", ">EMA200", "EMA20>50", "MACD+"],
            )
        );
    }

    #[test]
    fn score_opportunity_technicals_requires_price_to_clear_emas_not_match_them() {
        let summary = year_summary(
            2_000,
            Some(2_000),
            Some(2_000),
            Some(2_000),
            Some(10),
            Some(10),
            Some(0),
        );

        assert_eq!(
            score_opportunity_technicals(Some(&summary)),
            (Some(0), vec![])
        );
    }

    #[test]
    fn score_opportunity_technicals_uses_positive_histogram_without_macd_lines() {
        let summary = year_summary(2_000, None, None, None, None, None, Some(10));

        assert_eq!(
            score_opportunity_technicals(Some(&summary)),
            (Some(1), vec!["MACD+"])
        );
    }

    #[test]
    fn score_opportunity_forecasts_counts_supportive_analyst_weighted_and_dcf_signals() {
        let mut app = AppState::default();
        let selected_detail = detail();
        app.analysis_cache.insert(
            selected_detail.symbol.clone(),
            super::AnalysisCacheEntry::Ready {
                input: AnalysisInputKey {
                    symbol: selected_detail.symbol.clone(),
                    shares_outstanding: None,
                    total_debt_dollars: None,
                    total_cash_dollars: None,
                    beta_millis: None,
                    source_fingerprint: "DesktopYahooOnly".to_string(),
                },
                analysis: dcf_analysis_fixture(32_000),
            },
        );

        assert_eq!(
            score_opportunity_forecasts(&app, &selected_detail),
            (
                Some(5),
                vec!["Supportive", "5+Analysts", "Rec<=2.0", "Weighted+", "DCF+"],
            )
        );
    }

    #[test]
    fn score_opportunity_forecasts_penalizes_expensive_dcf_without_other_support() {
        let mut app = AppState::default();
        let mut selected_detail = detail();
        selected_detail.external_status = ExternalSignalStatus::Missing;
        selected_detail.analyst_opinion_count = None;
        selected_detail.recommendation_mean_hundredths = None;
        selected_detail.weighted_external_signal_fair_value_cents = None;
        app.analysis_cache.insert(
            selected_detail.symbol.clone(),
            super::AnalysisCacheEntry::Ready {
                input: AnalysisInputKey {
                    symbol: selected_detail.symbol.clone(),
                    shares_outstanding: None,
                    total_debt_dollars: None,
                    total_cash_dollars: None,
                    beta_millis: None,
                    source_fingerprint: "DesktopYahooOnly".to_string(),
                },
                analysis: dcf_analysis_fixture(10_000),
            },
        );

        assert_eq!(
            score_opportunity_forecasts(&app, &selected_detail),
            (Some(-1), vec!["DCF-"])
        );
    }

    #[test]
    fn aggressive_model_rewards_high_upside_and_trend_more_than_legacy() {
        let mut app = AppState::default();
        let mut selected_detail = detail();
        selected_detail.fundamentals = Some(fundamentals_with(
            "NVDA",
            Some(200_000_000),
            Some(250_000_000),
            Some(2_500),
            Some(40),
            Some(500_000_000),
            Some(100_000_000),
            Some(1_400),
        ));
        app.chart_summary_cache.insert(
            super::ChartCacheKey::new("NVDA", ChartRange::Year),
            year_summary(
                2_450,
                Some(2_100),
                Some(1_900),
                Some(1_700),
                Some(220),
                Some(120),
                Some(100),
            ),
        );
        app.analysis_cache.insert(
            selected_detail.symbol.clone(),
            super::AnalysisCacheEntry::Ready {
                input: AnalysisInputKey {
                    symbol: selected_detail.symbol.clone(),
                    shares_outstanding: None,
                    total_debt_dollars: None,
                    total_cash_dollars: None,
                    beta_millis: None,
                    source_fingerprint: "DesktopYahooOnly".to_string(),
                },
                analysis: dcf_analysis_fixture(32_000),
            },
        );

        let legacy = score_opportunity_with_model(
            &app,
            &selected_detail,
            Some(
                app.chart_summary("NVDA", ChartRange::Year)
                    .expect("summary should exist"),
            ),
            OpportunityScoringModel::Legacy,
        );
        let aggressive = score_opportunity_with_model(
            &app,
            &selected_detail,
            Some(
                app.chart_summary("NVDA", ChartRange::Year)
                    .expect("summary should exist"),
            ),
            OpportunityScoringModel::Aggressive,
        );

        assert_eq!(legacy.composite_score, 15);
        assert_eq!(aggressive.composite_score, 27);
    }

    #[test]
    fn aggressive_model_penalizes_broken_balance_sheet_and_bearish_setup() {
        let mut app = AppState::default();
        let mut selected_detail = detail();
        selected_detail.external_status = ExternalSignalStatus::Divergent;
        selected_detail.weighted_external_signal_fair_value_cents = Some(15_000);
        selected_detail.analyst_opinion_count = Some(2);
        selected_detail.recommendation_mean_hundredths = Some(340);
        selected_detail.fundamentals = Some(fundamentals_with(
            "NVDA",
            Some(-10),
            Some(-10),
            Some(-500),
            Some(240),
            Some(10_000_000),
            Some(500_000_000),
            Some(-900),
        ));
        app.chart_summary_cache.insert(
            super::ChartCacheKey::new("NVDA", ChartRange::Year),
            year_summary(
                1_000,
                Some(1_100),
                Some(1_200),
                Some(1_300),
                Some(-120),
                Some(10),
                Some(-130),
            ),
        );
        app.analysis_cache.insert(
            selected_detail.symbol.clone(),
            super::AnalysisCacheEntry::Ready {
                input: AnalysisInputKey {
                    symbol: selected_detail.symbol.clone(),
                    shares_outstanding: None,
                    total_debt_dollars: None,
                    total_cash_dollars: None,
                    beta_millis: None,
                    source_fingerprint: "DesktopYahooOnly".to_string(),
                },
                analysis: dcf_analysis_fixture(9_000),
            },
        );

        let aggressive = score_opportunity_with_model(
            &app,
            &selected_detail,
            Some(
                app.chart_summary("NVDA", ChartRange::Year)
                    .expect("summary should exist"),
            ),
            OpportunityScoringModel::Aggressive,
        );

        assert_eq!(aggressive.composite_score, -22);
    }

    #[test]
    fn opportunities_view_shows_active_scoring_model_in_header() {
        let mut state = TerminalState::new(2_000, 30, 8);
        let mut app = AppState::default();
        app.primary_view = PrimaryViewMode::Opportunities;
        app.opportunity_scoring_model = OpportunityScoringModel::Aggressive;

        state.ingest_snapshot(MarketSnapshot {
            symbol: "TOP".to_string(),
            company_name: Some("Top Idea".to_string()),
            profitable: true,
            market_price_cents: 1_000,
            intrinsic_value_cents: 4_000,
        });

        let lines = opportunities_view_lines_for_viewport(&state, &mut app, 120, 22);

        assert!(lines
            .iter()
            .any(|line| line.contains("Model: Aggressive [m]")));
    }

    #[test]
    fn ticker_detail_uses_opportunity_order_for_position_and_navigation() {
        let mut state = TerminalState::new(2_000, 30, 8);
        let mut app = AppState::default();

        state.ingest_snapshot(MarketSnapshot {
            symbol: "TOP".to_string(),
            company_name: Some("Top Idea".to_string()),
            profitable: true,
            market_price_cents: 1_000,
            intrinsic_value_cents: 4_000,
        });
        state.ingest_fundamentals(fundamentals_with(
            "TOP",
            Some(120_000_000),
            Some(150_000_000),
            Some(2_400),
            Some(40),
            Some(500_000_000),
            Some(100_000_000),
            Some(1_200),
        ));
        app.chart_summary_cache.insert(
            super::ChartCacheKey::new("TOP", ChartRange::Year),
            year_summary(
                4_000,
                Some(3_200),
                Some(2_900),
                None,
                Some(180),
                Some(100),
                Some(80),
            ),
        );

        state.ingest_snapshot(MarketSnapshot {
            symbol: "NEXT".to_string(),
            company_name: Some("Next Idea".to_string()),
            profitable: true,
            market_price_cents: 1_000,
            intrinsic_value_cents: 1_600,
        });
        state.ingest_external(external_signal(
            "NEXT",
            1_400,
            Some(1_500),
            Some(6),
            Some(180),
        ));
        state.ingest_fundamentals(fundamentals_with(
            "NEXT",
            Some(20_000_000),
            Some(30_000_000),
            Some(600),
            Some(180),
            Some(50_000_000),
            Some(200_000_000),
            Some(-300),
        ));
        app.chart_summary_cache.insert(
            super::ChartCacheKey::new("NEXT", ChartRange::Year),
            year_summary(
                1_100,
                Some(1_200),
                Some(1_250),
                None,
                Some(-20),
                Some(10),
                Some(-30),
            ),
        );

        app.primary_view = PrimaryViewMode::Opportunities;
        app.open_ticker_detail("TOP");

        let detail_lines = build_ticker_detail_lines_for_viewport(&state, &app, "TOP", 140, 32)
            .into_iter()
            .map(|line| visible_text(&line.text))
            .collect::<Vec<_>>();
        assert!(detail_lines
            .iter()
            .any(|line| line.contains("Position: 1/2")));

        let handled = handle_overlay_key(
            &mut app,
            &mut state,
            &KeyEvent::new_with_kind(KeyCode::Char('j'), KeyModifiers::NONE, KeyEventKind::Press),
            None,
            None,
            None,
        )
        .expect("detail j should be handled");

        assert!(handled);
        assert_eq!(app.detail_symbol(), Some("NEXT"));
    }

    #[test]
    fn move_ticker_detail_selection_for_symbols_uses_the_current_symbol_position() {
        let mut app = AppState::default();
        app.open_ticker_detail("MID");
        let symbols = vec!["LOW", "MID", "HIGH"];

        app.move_ticker_detail_selection_for_symbols(&symbols, 1);

        assert_eq!(app.detail_symbol(), Some("HIGH"));
    }

    #[test]
    fn input_selected_index_preserves_opportunity_selection_when_candidates_are_empty() {
        let mut state = TerminalState::new(2_000, 30, 8);
        let mut app = AppState::default();
        seed_ranked_opportunities(&mut state, 30);
        app.primary_view = PrimaryViewMode::Opportunities;
        app.selected_symbol = Some("OP20".to_string());
        let rows = app.visible_rows(&state);

        assert_eq!(rows.len(), 0);
        assert_eq!(app.input_selected_index(&state, &rows), 20);
        assert_eq!(app.selected_symbol.as_deref(), Some("OP20"));
    }

    #[test]
    fn clear_selection_for_view_clears_the_active_selected_symbol() {
        let mut app = AppState::default();

        app.set_selection("OP01");
        app.clear_selection_for_view(PrimaryViewMode::Candidates);

        assert_eq!(app.selected_symbol, None);
        assert_eq!(app.candidate_selected_symbol, None);
    }

    #[test]
    fn clear_selection_for_view_does_not_clear_the_other_view_selection() {
        let mut app = AppState::default();
        app.primary_view = PrimaryViewMode::Opportunities;
        app.set_selection("OP20");

        app.clear_selection_for_view(PrimaryViewMode::Candidates);

        assert_eq!(app.selected_symbol.as_deref(), Some("OP20"));
        assert_eq!(app.opportunity_selected_symbol.as_deref(), Some("OP20"));
    }

    #[test]
    fn first_entry_into_opportunities_selects_the_first_ranked_symbol() {
        let mut state = TerminalState::new(2_000, 30, 8);
        let mut app = AppState::default();
        seed_candidates_and_opportunities(&mut state);

        app.set_selection("OP01");
        app.toggle_primary_view(&state);

        assert_eq!(app.primary_view, PrimaryViewMode::Opportunities);
        assert_eq!(app.selected_symbol.as_deref(), Some("OP00"));
    }

    #[test]
    fn toggling_between_views_restores_the_last_ticker_selection_per_view() {
        let mut state = TerminalState::new(2_000, 30, 8);
        let mut app = AppState::default();
        seed_candidates_and_opportunities(&mut state);

        app.set_selection("OP01");
        app.toggle_primary_view(&state);
        app.set_selection("OP20");

        app.toggle_primary_view(&state);
        assert_eq!(app.primary_view, PrimaryViewMode::Candidates);
        assert_eq!(app.selected_symbol.as_deref(), Some("OP01"));

        app.toggle_primary_view(&state);
        assert_eq!(app.primary_view, PrimaryViewMode::Opportunities);
        assert_eq!(app.selected_symbol.as_deref(), Some("OP20"));
    }

    #[test]
    fn opportunities_view_navigation_can_move_beyond_the_initial_twenty_rows() {
        let mut state = TerminalState::new(2_000, 30, 8);
        let mut app = AppState::default();
        seed_ranked_opportunities(&mut state, 30);
        app.primary_view = PrimaryViewMode::Opportunities;

        let rows = app.visible_rows(&state);
        for _ in 0..20 {
            let selected_index = app.sync_base_selected_index(&state, &rows);
            handle_input_event(
                KeyEvent::new_with_kind(
                    KeyCode::Char('j'),
                    KeyModifiers::NONE,
                    KeyEventKind::Press,
                ),
                &mut state,
                &mut app,
                &rows,
                selected_index,
                true,
                None,
                None,
                None,
                None,
                None,
            )
            .expect("j should be handled");
        }

        assert_eq!(app.selected_symbol.as_deref(), Some("OP20"));
    }

    #[test]
    fn home_and_end_select_the_first_and_last_opportunity() {
        let mut state = TerminalState::new(2_000, 30, 8);
        let mut app = AppState::default();
        seed_ranked_opportunities(&mut state, 30);
        app.primary_view = PrimaryViewMode::Opportunities;
        app.selected_symbol = Some("OP20".to_string());

        let rows = app.visible_rows(&state);
        let selected_index = app.input_selected_index(&state, &rows);
        handle_input_event(
            KeyEvent::new_with_kind(KeyCode::Home, KeyModifiers::NONE, KeyEventKind::Press),
            &mut state,
            &mut app,
            &rows,
            selected_index,
            true,
            None,
            None,
            None,
            None,
            None,
        )
        .expect("Home should be handled");
        assert_eq!(app.selected_symbol.as_deref(), Some("OP00"));

        let selected_index = app.input_selected_index(&state, &rows);
        handle_input_event(
            KeyEvent::new_with_kind(KeyCode::End, KeyModifiers::NONE, KeyEventKind::Press),
            &mut state,
            &mut app,
            &rows,
            selected_index,
            true,
            None,
            None,
            None,
            None,
            None,
        )
        .expect("End should be handled");
        assert_eq!(app.selected_symbol.as_deref(), Some("OP29"));
    }

    #[test]
    fn page_up_and_page_down_move_by_one_visible_page() {
        let mut state = TerminalState::new(2_000, 30, 8);
        let mut app = AppState::default();
        seed_ranked_opportunities(&mut state, 45);
        app.primary_view = PrimaryViewMode::Opportunities;
        app.selected_symbol = Some("OP00".to_string());

        let rows = app.visible_rows(&state);
        let selected_index = app.input_selected_index(&state, &rows);
        handle_input_event(
            KeyEvent::new_with_kind(KeyCode::PageDown, KeyModifiers::NONE, KeyEventKind::Press),
            &mut state,
            &mut app,
            &rows,
            selected_index,
            true,
            None,
            None,
            None,
            None,
            None,
        )
        .expect("PageDown should be handled");
        assert_eq!(app.selected_symbol.as_deref(), Some("OP20"));

        let selected_index = app.input_selected_index(&state, &rows);
        handle_input_event(
            KeyEvent::new_with_kind(KeyCode::PageUp, KeyModifiers::NONE, KeyEventKind::Press),
            &mut state,
            &mut app,
            &rows,
            selected_index,
            true,
            None,
            None,
            None,
            None,
            None,
        )
        .expect("PageUp should be handled");
        assert_eq!(app.selected_symbol.as_deref(), Some("OP00"));
    }

    #[test]
    fn opportunities_view_renders_scrolled_rows_with_absolute_rank_numbers() {
        let mut state = TerminalState::new(2_000, 30, 8);
        let mut app = AppState::default();
        seed_ranked_opportunities(&mut state, 30);
        app.primary_view = PrimaryViewMode::Opportunities;
        app.selected_symbol = Some("OP20".to_string());

        let visible_lines = opportunities_view_lines_with_selected_index(&state, &app, 20, 120, 40);

        assert!(visible_lines.iter().any(|line| line.contains("OP20")));
        assert!(visible_lines
            .iter()
            .any(|line| line.contains(">  20") && line.contains("OP20")));
        assert!(!visible_lines.iter().any(|line| line.contains("OP00")));
    }

    #[test]
    fn enter_opens_detail_for_a_scrolled_opportunity_selection() {
        let mut state = TerminalState::new(2_000, 30, 8);
        let mut app = AppState::default();
        seed_ranked_opportunities(&mut state, 30);
        app.primary_view = PrimaryViewMode::Opportunities;
        app.selected_symbol = Some("OP20".to_string());

        let rows = app.visible_rows(&state);
        let result = handle_input_event(
            KeyEvent::new_with_kind(KeyCode::Enter, KeyModifiers::NONE, KeyEventKind::Press),
            &mut state,
            &mut app,
            &rows,
            20,
            true,
            None,
            None,
            None,
            None,
            None,
        )
        .expect("Enter should be handled");

        assert!(matches!(result, LoopControl::Continue));
        assert_eq!(app.detail_symbol(), Some("OP20"));
    }

    #[test]
    fn opportunity_window_bounds_keeps_the_first_page_for_early_selection() {
        assert_eq!(opportunity_window_bounds(30, 5, MAX_VISIBLE_ROWS), (0, 20));
    }

    #[test]
    fn opportunity_window_bounds_trails_the_selected_row_after_the_first_page() {
        assert_eq!(opportunity_window_bounds(30, 20, MAX_VISIBLE_ROWS), (1, 21));
        assert_eq!(
            opportunity_window_bounds(30, 29, MAX_VISIBLE_ROWS),
            (10, 30)
        );
    }

    #[test]
    fn main_screen_preserves_selected_detail_on_short_viewports() {
        let visible_lines = ranked_main_view_lines_for_viewport(96, 18);

        assert_eq!(
            (
                visible_lines.iter().any(|line| line == "TOP CANDIDATES"),
                visible_lines.iter().any(|line| line == "DETAIL"),
                visible_lines.iter().any(|line| line.starts_with("Symbol:")),
            ),
            (true, true, true)
        );
    }

    #[test]
    fn main_screen_drops_alerts_and_tape_before_selected_detail() {
        let visible_lines = ranked_main_view_lines_for_viewport(96, 20);

        assert_eq!(
            (
                visible_lines.iter().any(|line| line == "DETAIL"),
                visible_lines.iter().any(|line| line == "ALERTS"),
                visible_lines.iter().any(|line| line == "RECENT TAPE"),
            ),
            (true, false, false)
        );
    }

    #[test]
    fn main_screen_uses_a_compact_header_on_narrow_viewports() {
        let visible_lines = ranked_main_view_lines_for_viewport(96, 20);

        assert_eq!(
            (
                visible_lines
                    .first()
                    .map(|line| line.contains("q quit"))
                    .unwrap_or(false),
                visible_lines
                    .first()
                    .map(|line| line.contains("space pause"))
                    .unwrap_or(false),
            ),
            (true, true)
        );
    }

    #[test]
    fn main_screen_uses_compact_status_and_prompt_on_narrow_viewports() {
        let visible_lines = ranked_main_view_lines_for_viewport(96, 20);

        assert_eq!(
            (
                visible_lines
                    .iter()
                    .any(|line| line.contains("Pending:") && line.contains("Rate:")),
                visible_lines
                    .iter()
                    .any(|line| line.contains("Ctrl+C quit")),
            ),
            (true, true)
        );
    }

    #[test]
    fn main_screen_switches_to_compact_live_strings_before_mid_width_clipping() {
        let visible_lines = ranked_main_view_lines_for_viewport(120, 20);

        assert_eq!(
            (
                visible_lines.first().map(String::as_str),
                visible_lines.get(1).map(String::as_str),
            ),
            (
                Some(
                    "DISCOUNT TERMINAL  |  o view  d detail  / filter  s symbol  space pause  q quit",
                ),
                Some("Mode: live  Feed: running  Tracked: 25  Loaded: 25  Pending: 0  Rate: 0/s"),
            )
        );
    }

    #[test]
    fn narrow_filter_and_symbol_prompts_use_compact_variants() {
        let mut filter_app = AppState::default();
        filter_app.input_mode = InputMode::FilterSearch("NVDA".to_string());
        let mut symbol_app = AppState::default();
        symbol_app.input_mode = InputMode::SymbolSearch("AMD".to_string());

        assert_eq!(
            (
                input_prompt(&filter_app, true, 80),
                input_prompt(&symbol_app, true, 80)
            ),
            (
                "Filter: 'NVDA'  Enter apply  Esc cancel  Backspace edit/back".to_string(),
                "Symbol: 'AMD'  Enter add  Esc cancel  Backspace edit/back".to_string(),
            )
        );
    }

    #[test]
    fn ticker_detail_uses_full_filtered_set_when_main_screen_is_capped() {
        let mut state = TerminalState::new(2_000, 30, 8);

        for index in 0..25 {
            let symbol = format!("H{:02}", index);
            state.ingest_snapshot(MarketSnapshot {
                symbol: symbol.clone(),
                company_name: None,
                profitable: true,
                market_price_cents: 10_000,
                intrinsic_value_cents: 15_000,
            });
            state.ingest_external(ExternalValuationSignal {
                symbol,
                fair_value_cents: 13_000,
                age_seconds: 0,
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
            });
        }

        let filtered_rows = filtered_symbol_rows(&state, &ViewFilter::default());
        let lines = build_ticker_detail_lines(&state, &AppState::default(), "H24");
        let position_line = lines
            .iter()
            .find(|line| line.text.contains("Position:"))
            .expect("ticker detail position line should exist");

        assert_eq!(filtered_rows.len(), 25);
        assert!(position_line.text.contains("Position: 25/25"));
    }

    #[test]
    fn main_screen_detail_summary_formats_true_upside() {
        let mut state = TerminalState::new(2_000, 30, 8);
        state.ingest_snapshot(MarketSnapshot {
            symbol: "NVDA".to_string(),
            company_name: None,
            profitable: true,
            market_price_cents: 17_270,
            intrinsic_value_cents: 26_923,
        });
        state.ingest_external(ExternalValuationSignal {
            symbol: "NVDA".to_string(),
            fair_value_cents: 26_923,
            age_seconds: 0,
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
        });

        let app = AppState::default();
        let rows = app.visible_rows(&state);
        let lines = build_screen_lines(&state, &rows, 0, 0, false, &app, None);
        let detail_line = lines
            .iter()
            .find(|line| line.text.starts_with("Price: "))
            .expect("detail summary line should exist");

        assert!(detail_line.text.contains("Upside: 55.89%"));
    }

    #[test]
    fn candidate_table_header_aligns_with_row_columns() {
        let mut state = TerminalState::new(2_000, 30, 8);
        state.ingest_snapshot(MarketSnapshot {
            symbol: "A".to_string(),
            company_name: Some("Agilent Technologies, Inc.".to_string()),
            profitable: true,
            market_price_cents: 11_234,
            intrinsic_value_cents: 16_329,
        });
        state.ingest_external(ExternalValuationSignal {
            symbol: "A".to_string(),
            fair_value_cents: 16_329,
            age_seconds: 0,
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
        });

        let app = AppState::default();
        let rows = app.visible_rows(&state);
        let lines = build_screen_lines(&state, &rows, 0, 0, false, &app, None);
        let header_line = lines
            .iter()
            .find(|line| line.text.contains("Ticker / Company"))
            .expect("candidate header should exist");
        let row_line = lines
            .iter()
            .find(|line| line.text.starts_with(">   0"))
            .expect("selected candidate row should exist");

        assert_eq!(
            header_line.text.find("Ticker / Company"),
            row_line.text.find("A Agilent Technologies, Inc.")
        );
        assert_eq!(
            header_line.text.find("Confidence"),
            row_line.text.find("high")
        );
    }

    #[test]
    fn gap_meter_uses_block_fill_with_threshold_and_actual_markers() {
        let meter = gap_meter(2_500, 2_000, 12);

        assert!(meter.starts_with('['));
        assert!(meter.ends_with(']'));
        assert!(meter.contains('│'));
        assert!(meter.contains('●') || meter.contains('◆'));
        assert!(meter.contains('█'));
        assert!(meter.contains('░'));
    }

    #[test]
    fn analyst_consensus_lines_include_target_range_and_rating_distribution() {
        let lines = analyst_consensus_lines(&detail());

        assert_eq!(lines.len(), 3);
        assert_eq!(lines[0], "Target range width: $135.00 = $320.00 - $185.00");
        assert_eq!(
            lines[1],
            "Analysts: 42  Recommendation mean: 1.85 (1.00=strong buy, 5.00=strong sell)"
        );
        assert_eq!(
            lines[2],
            "Ratings: strong buy 20  buy 10  hold 8  sell 3  strong sell 1"
        );
    }

    // ── Volume Profile ──────────────────────────────────────────────

    fn make_candle(open: i64, high: i64, low: i64, close: i64, volume: u64) -> PriceCandle {
        PriceCandle {
            open_cents: open,
            high_cents: high,
            low_cents: low,
            close_cents: close,
            volume,
            ema_20_cents: None,
            ema_50_cents: None,
            ema_200_cents: None,
            macd_cents: None,
            signal_cents: None,
            histogram_cents: None,
            point_count: 1,
        }
    }

    fn make_historical_candle(
        epoch_seconds: u64,
        open: i64,
        high: i64,
        low: i64,
        close: i64,
        volume: u64,
    ) -> HistoricalCandle {
        HistoricalCandle {
            epoch_seconds,
            open_cents: open,
            high_cents: high,
            low_cents: low,
            close_cents: close,
            volume,
        }
    }

    #[test]
    fn volume_profile_single_candle_distributes_across_all_bins() {
        let candles = vec![make_historical_candle(1, 1000, 5000, 1000, 5000, 1000)];
        let bins = compute_volume_profile(&candles, 1000, 5000, 4);

        assert_eq!(bins.len(), 4);
        let total: u64 = bins.iter().map(|b| b.up_volume + b.down_volume).sum();
        assert_eq!(total, 1000);
    }

    #[test]
    fn volume_profile_classifies_up_and_down_candles() {
        let up_candle = make_historical_candle(1, 2000, 4000, 2000, 4000, 600);
        let down_candle = make_historical_candle(2, 4000, 4000, 2000, 2000, 400);
        let candles = vec![up_candle, down_candle];
        let bins = compute_volume_profile(&candles, 2000, 4000, 2);

        let total_up: u64 = bins.iter().map(|b| b.up_volume).sum();
        let total_down: u64 = bins.iter().map(|b| b.down_volume).sum();
        assert_eq!(total_up, 600);
        assert_eq!(total_down, 400);
    }

    #[test]
    fn volume_profile_flat_candle_concentrates_in_one_bin() {
        let candles = vec![make_historical_candle(1, 3000, 3000, 3000, 3000, 500)];
        let bins = compute_volume_profile(&candles, 1000, 5000, 4);

        let nonzero_bins: Vec<_> = bins
            .iter()
            .filter(|b| b.up_volume + b.down_volume > 0)
            .collect();
        assert_eq!(nonzero_bins.len(), 1);
        assert_eq!(nonzero_bins[0].up_volume, 500);
    }

    #[test]
    fn volume_profile_empty_candles_returns_empty_bins() {
        let bins = compute_volume_profile(&[], 1000, 5000, 4);

        assert_eq!(bins.len(), 4);
        assert!(bins.iter().all(|b| b.up_volume == 0 && b.down_volume == 0));
    }

    #[test]
    fn render_volume_profile_cells_proportional_bar_width() {
        let bin = VolumeProfileBin {
            up_volume: 80,
            down_volume: 20,
        };
        let cells = render_volume_profile_cells(&bin, 100, 10);

        // 1 separator + 9 bar chars (total volume 100 / max 100 * 9 = 9)
        assert_eq!(cells.len(), 10);
        assert_eq!(cells[0].ch, '│');
    }

    #[test]
    fn render_volume_profile_cells_empty_bin_only_separator() {
        let bin = VolumeProfileBin {
            up_volume: 0,
            down_volume: 0,
        };
        let cells = render_volume_profile_cells(&bin, 100, 10);

        assert_eq!(cells[0].ch, '│');
        assert!(cells[1..].iter().all(|c| c.ch == ' '));
    }

    #[test]
    fn render_volume_profile_cells_up_down_color_split() {
        let bin = VolumeProfileBin {
            up_volume: 60,
            down_volume: 40,
        };
        let cells = render_volume_profile_cells(&bin, 100, 11);

        // 1 separator + 10 bar slots. Total = 100/100 * 10 = 10 bar chars.
        // Up portion: 60/100 * 10 = 6 chars yellow
        // Down portion: 40/100 * 10 = 4 chars cyan
        let bar_cells = &cells[1..];
        let up_count = bar_cells
            .iter()
            .filter(|c| c.color == Some(Color::DarkYellow))
            .count();
        let down_count = bar_cells
            .iter()
            .filter(|c| c.color == Some(Color::DarkCyan))
            .count();
        assert_eq!(up_count, 6);
        assert_eq!(down_count, 4);
    }

    #[test]
    fn layout_enables_volume_profile_for_wide_viewport() {
        let layout = detail_layout(120, 40);

        assert!(layout.show_volume_profile);
    }

    #[test]
    fn layout_disables_volume_profile_for_narrow_viewport() {
        let layout = detail_layout(80, 40);

        assert!(!layout.show_volume_profile);
    }

    #[test]
    fn layout_uses_full_plot_width_for_hd_candle_slots() {
        let wide = detail_layout(120, 40);
        let narrow = detail_layout(80, 40);

        let expected_wide_slots =
            120 - DETAIL_CHART_AXIS_WIDTH - DETAIL_CHART_ROW_PADDING - DETAIL_VOLUME_PROFILE_WIDTH;
        assert_eq!(wide.candle_slots, expected_wide_slots);

        let expected_narrow_slots = 80 - DETAIL_CHART_AXIS_WIDTH - DETAIL_CHART_ROW_PADDING;
        assert_eq!(narrow.candle_slots, expected_narrow_slots);
    }

    #[test]
    fn volume_profile_min_equals_max_returns_zero_bins() {
        let candles = vec![make_historical_candle(1, 3000, 3000, 3000, 3000, 500)];
        let bins = compute_volume_profile(&candles, 3000, 3000, 4);

        assert!(bins.iter().all(|b| b.up_volume == 0 && b.down_volume == 0));
    }

    #[test]
    fn volume_profile_per_bin_distribution_is_correct() {
        // One candle spanning the full range with 10 volume across 4 bins.
        // Each bin should get 10/4 = 2, remainder 2 → first 2 bins get 3.
        let candles = vec![make_historical_candle(1, 1000, 5000, 1000, 5000, 10)];
        let bins = compute_volume_profile(&candles, 1000, 5000, 4);

        // Bins are ordered top-down (row 0 = highest price).
        // Bin indices 0..3 map to price bins 3..0 (high→low).
        let volumes: Vec<u64> = bins.iter().map(|b| b.up_volume).collect();
        // Each bin gets at least 2; first 2 enumeration indices get +1.
        // bin_index 0 → row 3, bin_index 1 → row 2, bin_index 2 → row 1, bin_index 3 → row 0.
        assert_eq!(volumes, vec![2, 2, 3, 3]);
    }

    #[test]
    fn volume_profile_candle_partially_within_range() {
        // Candle high extends beyond max_price_cents — should be clamped.
        let candles = vec![make_historical_candle(1, 2000, 8000, 2000, 4000, 100)];
        let bins = compute_volume_profile(&candles, 2000, 4000, 2);

        let total: u64 = bins.iter().map(|b| b.up_volume + b.down_volume).sum();
        assert_eq!(total, 100);
        // Both bins should have volume (candle spans entire clipped range).
        assert!(bins[0].up_volume > 0);
        assert!(bins[1].up_volume > 0);
    }

    #[test]
    fn volume_profile_uses_raw_candles_instead_of_aggregated_ranges() {
        let raw = vec![
            make_historical_candle(1, 1000, 1000, 1000, 1000, 100),
            make_historical_candle(2, 5000, 5000, 5000, 5000, 100),
        ];
        let aggregated = vec![make_candle(1000, 5000, 1000, 5000, 200)];

        let raw_bins = compute_volume_profile(&raw, 1000, 5000, 4);
        let aggregated_bins = compute_volume_profile_from_price_candles(&aggregated, 1000, 5000, 4);

        let raw_volumes: Vec<u64> = raw_bins
            .iter()
            .map(|b| b.up_volume + b.down_volume)
            .collect();
        let aggregated_volumes: Vec<u64> = aggregated_bins
            .iter()
            .map(|b| b.up_volume + b.down_volume)
            .collect();

        assert_eq!(raw_volumes, vec![100, 0, 0, 100]);
        assert_eq!(aggregated_volumes, vec![50, 50, 50, 50]);
    }

    #[test]
    fn render_volume_profile_cells_partial_fill() {
        // Half-filled bar: 50 out of 100 max → 5 out of 10 available.
        let bin = VolumeProfileBin {
            up_volume: 50,
            down_volume: 0,
        };
        let cells = render_volume_profile_cells(&bin, 100, 11);

        let filled_count = cells[1..].iter().filter(|c| c.ch == '█').count();
        let space_count = cells[1..].iter().filter(|c| c.ch == ' ').count();
        assert_eq!(filled_count, 5);
        assert_eq!(space_count, 5);
    }

    #[test]
    fn render_volume_profile_cells_bar_width_one_only_separator() {
        let bin = VolumeProfileBin {
            up_volume: 100,
            down_volume: 0,
        };
        let cells = render_volume_profile_cells(&bin, 100, 1);

        assert_eq!(cells.len(), 1);
        assert_eq!(cells[0].ch, '│');
    }

    // ── Stale symbol colors ─────────────────────────────────────────

    #[test]
    fn stale_candidate_row_uses_dark_grey_color() {
        let row = CandidateRow {
            symbol: "AAPL".to_string(),
            market_price_cents: 15000,
            intrinsic_value_cents: 20000,
            gap_bps: 2500,
            is_qualified: true,
            confidence: ConfidenceBand::High,
        };

        assert_eq!(candidate_row_color(&row, false, true), Color::DarkGrey);
    }

    #[test]
    fn non_stale_candidate_row_uses_confidence_color() {
        let row = CandidateRow {
            symbol: "AAPL".to_string(),
            market_price_cents: 15000,
            intrinsic_value_cents: 20000,
            gap_bps: 2500,
            is_qualified: true,
            confidence: ConfidenceBand::High,
        };

        assert_eq!(candidate_row_color(&row, false, false), Color::Green);
    }

    #[test]
    fn selected_stale_row_still_uses_selection_color() {
        let row = CandidateRow {
            symbol: "AAPL".to_string(),
            market_price_cents: 15000,
            intrinsic_value_cents: 20000,
            gap_bps: 2500,
            is_qualified: true,
            confidence: ConfidenceBand::High,
        };

        assert_eq!(candidate_row_color(&row, true, true), Color::Cyan);
    }

    // ── Feed loading order by persisted upside ──────────────────────

    #[test]
    fn reorder_symbols_by_persisted_upside_puts_highest_first() {
        let symbols = vec!["LOW".to_string(), "MID".to_string(), "HIGH".to_string()];
        let mut state = TerminalState::new(2_000, 30, 8);
        state.ingest_snapshot(MarketSnapshot {
            symbol: "HIGH".to_string(),
            company_name: None,
            profitable: true,
            market_price_cents: 5000,
            intrinsic_value_cents: 10000,
        });
        state.ingest_snapshot(MarketSnapshot {
            symbol: "MID".to_string(),
            company_name: None,
            profitable: true,
            market_price_cents: 10000,
            intrinsic_value_cents: 15000,
        });
        state.ingest_snapshot(MarketSnapshot {
            symbol: "LOW".to_string(),
            company_name: None,
            profitable: true,
            market_price_cents: 8000,
            intrinsic_value_cents: 10000,
        });

        let reordered = reorder_symbols_by_persisted_ranking(&symbols, &state);

        assert_eq!(reordered, vec!["HIGH", "MID", "LOW"]);
    }

    #[test]
    fn reorder_symbols_puts_unknown_symbols_at_end() {
        let symbols = vec!["NEW".to_string(), "KNOWN".to_string()];
        let mut state = TerminalState::new(2_000, 30, 8);
        state.ingest_snapshot(MarketSnapshot {
            symbol: "KNOWN".to_string(),
            company_name: None,
            profitable: true,
            market_price_cents: 5000,
            intrinsic_value_cents: 10000,
        });

        let reordered = reorder_symbols_by_persisted_ranking(&symbols, &state);

        assert_eq!(reordered, vec!["KNOWN", "NEW"]);
    }

    #[test]
    fn reorder_symbols_preserves_original_order_for_equal_unknowns() {
        let symbols = vec!["ALPHA".to_string(), "BETA".to_string(), "GAMMA".to_string()];
        let state = TerminalState::new(2_000, 30, 8);

        let reordered = reorder_symbols_by_persisted_ranking(&symbols, &state);

        assert_eq!(reordered, vec!["ALPHA", "BETA", "GAMMA"]);
    }

    #[test]
    fn reorder_symbols_empty_input_returns_empty() {
        let symbols: Vec<String> = vec![];
        let state = TerminalState::new(2_000, 30, 8);

        let reordered = reorder_symbols_by_persisted_ranking(&symbols, &state);

        assert!(reordered.is_empty());
    }

    // ── Chart replay (bar-by-bar) ────────────────────────────────

    #[test]
    fn replay_offset_defaults_to_zero() {
        let app = AppState::default();

        assert_eq!(app.replay_offset, 0);
    }

    #[test]
    fn step_replay_back_increments_offset() {
        let mut app = AppState::default();

        assert!(app.step_replay_back(10));
        assert_eq!(app.replay_offset, 1);
    }

    #[test]
    fn step_replay_back_clamps_to_total_minus_one() {
        let mut app = AppState::default();
        app.replay_offset = 4;

        assert!(!app.step_replay_back(5));
        assert_eq!(app.replay_offset, 4);
    }

    #[test]
    fn step_replay_back_is_noop_for_zero_candles() {
        let mut app = AppState::default();

        assert!(!app.step_replay_back(0));
        assert_eq!(app.replay_offset, 0);
    }

    #[test]
    fn step_replay_back_is_noop_for_one_candle() {
        let mut app = AppState::default();

        assert!(!app.step_replay_back(1));
        assert_eq!(app.replay_offset, 0);
    }

    #[test]
    fn step_replay_forward_decrements_offset() {
        let mut app = AppState::default();
        app.replay_offset = 3;

        assert!(app.step_replay_forward());
        assert_eq!(app.replay_offset, 2);
    }

    #[test]
    fn step_replay_forward_is_noop_at_zero() {
        let mut app = AppState::default();

        assert!(!app.step_replay_forward());
        assert_eq!(app.replay_offset, 0);
    }

    #[test]
    fn reset_replay_clears_offset() {
        let mut app = AppState::default();
        app.replay_offset = 5;

        app.reset_replay();

        assert_eq!(app.replay_offset, 0);
    }

    #[test]
    fn set_detail_chart_range_resets_replay() {
        let mut app = AppState::default();
        app.replay_offset = 3;

        app.set_detail_chart_range(ChartRange::Month);

        assert_eq!(app.replay_offset, 0);
    }

    #[test]
    fn cycle_detail_chart_range_resets_replay() {
        let mut app = AppState::default();
        app.set_detail_chart_range(ChartRange::Month);
        app.replay_offset = 3;

        app.cycle_detail_chart_range(1);

        assert_eq!(app.replay_offset, 0);
    }

    #[test]
    fn move_ticker_detail_selection_resets_replay() {
        let mut app = AppState::default();
        app.open_ticker_detail("AAPL");
        app.replay_offset = 5;
        let rows = vec![candidate("AAPL", 3_000), candidate("MSFT", 2_000)];

        app.move_ticker_detail_selection(&rows, 1);

        assert_eq!(app.replay_offset, 0);
    }

    #[test]
    fn open_ticker_detail_resets_replay() {
        let mut app = AppState::default();
        app.replay_offset = 4;

        app.open_ticker_detail("AAPL");

        assert_eq!(app.replay_offset, 0);
    }

    #[test]
    fn visible_candle_count_respects_replay_offset() {
        let app = AppState::default();

        assert_eq!(app.visible_candle_end(100), 100);
    }

    #[test]
    fn visible_candle_count_subtracts_offset() {
        let mut app = AppState::default();
        app.replay_offset = 10;

        assert_eq!(app.visible_candle_end(100), 90);
    }

    #[test]
    fn visible_candle_count_clamps_to_at_least_one() {
        let mut app = AppState::default();
        app.replay_offset = 200;

        assert_eq!(app.visible_candle_end(50), 1);
    }

    #[test]
    fn visible_candle_count_returns_zero_for_empty() {
        let mut app = AppState::default();
        app.replay_offset = 5;

        assert_eq!(app.visible_candle_end(0), 0);
    }

    #[test]
    fn styled_cell_default_bg_is_none() {
        let cell = super::StyledCell {
            ch: ' ',
            color: None,
            bg_color: None,
            priority: 0,
        };
        assert_eq!(cell.bg_color, None);
    }

    #[test]
    fn styled_cell_stores_bg_color() {
        let cell = super::StyledCell {
            ch: '█',
            color: Some(Color::Green),
            bg_color: Some(Color::Red),
            priority: 5,
        };
        assert_eq!(cell.bg_color, Some(Color::Red));
    }

    #[test]
    fn styled_cells_line_encodes_bg_color() {
        let cells = vec![
            super::StyledCell {
                ch: 'A',
                color: Some(Color::Green),
                bg_color: Some(Color::Red),
                priority: 5,
            },
            super::StyledCell {
                ch: 'B',
                color: Some(Color::Green),
                bg_color: Some(Color::Red),
                priority: 5,
            },
        ];
        let line = super::styled_cells_line(&cells);
        let text = &line.text;
        // The encoded text should contain INLINE_STYLE_MARKER followed by two chars (fg + bg)
        let marker = super::INLINE_STYLE_MARKER;
        let marker_positions: Vec<usize> = text
            .char_indices()
            .filter(|(_, ch)| *ch == marker)
            .map(|(i, _)| i)
            .collect();
        // At least one style marker at start, one reset at end
        assert!(
            marker_positions.len() >= 2,
            "expected at least 2 markers (set + reset), got {}",
            marker_positions.len()
        );
        // After first marker: 2 chars (fg code + bg code), then visible text
        let after_first = &text[marker_positions[0] + marker.len_utf8()..];
        let mut chars = after_first.chars();
        let fg_code = chars.next().unwrap();
        let bg_code = chars.next().unwrap();
        assert_eq!(
            super::decode_color_marker(fg_code),
            Some(Color::Green),
            "fg code should decode to Green"
        );
        assert_eq!(
            super::decode_color_marker(bg_code),
            Some(Color::Red),
            "bg code should decode to Red"
        );
    }

    #[test]
    fn styled_cells_line_no_bg_produces_none_bg_marker() {
        let cells = vec![super::StyledCell {
            ch: 'X',
            color: Some(Color::Yellow),
            bg_color: None,
            priority: 1,
        }];
        let line = super::styled_cells_line(&cells);
        let text = &line.text;
        let marker = super::INLINE_STYLE_MARKER;
        let after_first = text
            .find(marker)
            .map(|pos| &text[pos + marker.len_utf8()..])
            .unwrap();
        let mut chars = after_first.chars();
        let fg_code = chars.next().unwrap();
        let bg_code = chars.next().unwrap();
        assert_eq!(super::decode_color_marker(fg_code), Some(Color::Yellow));
        assert_eq!(
            super::decode_color_marker(bg_code),
            None,
            "bg_color: None should encode as None"
        );
    }

    #[test]
    fn visible_text_strips_two_char_markers() {
        // Build a styled line with bg, then visible_text should strip all markers
        let cells = vec![super::StyledCell {
            ch: 'H',
            color: Some(Color::Green),
            bg_color: Some(Color::Red),
            priority: 1,
        }];
        let line = super::styled_cells_line(&cells);
        assert_eq!(visible_text(&line.text), "H");
    }

    #[test]
    fn styled_segments_line_same_color_no_extra_markers() {
        // Two segments with same color+bg → only one marker emitted (not two)
        let line = super::styled_segments_line(vec![
            super::StyledSegment {
                color: Some(Color::Green),
                bg_color: None,
                text: "AB".to_string(),
            },
            super::StyledSegment {
                color: Some(Color::Green),
                bg_color: None,
                text: "CD".to_string(),
            },
        ]);
        let marker = super::INLINE_STYLE_MARKER;
        let marker_count = line.text.chars().filter(|c| *c == marker).count();
        // Should be exactly 2 markers: one "set Green" + one "reset"
        assert_eq!(
            marker_count, 2,
            "same-color segments should share one marker pair"
        );
        assert_eq!(visible_text(&line.text), "ABCD");
    }

    #[test]
    fn styled_segments_line_fg_only_resets() {
        // A single fg-only segment should still have a reset marker at end
        let line = super::styled_segments_line(vec![super::StyledSegment {
            color: Some(Color::Red),
            bg_color: None,
            text: "X".to_string(),
        }]);
        let marker = super::INLINE_STYLE_MARKER;
        let marker_count = line.text.chars().filter(|c| *c == marker).count();
        assert_eq!(marker_count, 2, "should have set + reset markers");
    }

    #[test]
    fn styled_cells_line_bg_change_breaks_segment() {
        // Two cells with same fg but different bg → should produce separate segments
        let cells = vec![
            super::StyledCell {
                ch: 'A',
                color: Some(Color::Green),
                bg_color: Some(Color::Red),
                priority: 1,
            },
            super::StyledCell {
                ch: 'B',
                color: Some(Color::Green),
                bg_color: Some(Color::Blue),
                priority: 1,
            },
        ];
        let line = super::styled_cells_line(&cells);
        let marker = super::INLINE_STYLE_MARKER;
        // Should have 3 markers: set(Green,Red), set(Green,Blue), reset
        let marker_count = line.text.chars().filter(|c| *c == marker).count();
        assert_eq!(marker_count, 3, "bg change should produce a new marker");
        assert_eq!(visible_text(&line.text), "AB");
    }

    #[test]
    fn styled_cells_line_fg_change_breaks_segment() {
        let cells = vec![
            super::StyledCell {
                ch: 'A',
                color: Some(Color::Green),
                bg_color: None,
                priority: 1,
            },
            super::StyledCell {
                ch: 'B',
                color: Some(Color::Red),
                bg_color: None,
                priority: 1,
            },
        ];
        let line = super::styled_cells_line(&cells);
        let marker = super::INLINE_STYLE_MARKER;
        // Should have 3 markers: set(Green), set(Red), reset
        let marker_count = line.text.chars().filter(|c| *c == marker).count();
        assert_eq!(marker_count, 3, "fg change should produce a new marker");
        assert_eq!(visible_text(&line.text), "AB");
    }

    fn contains_braille(text: &str) -> bool {
        text.chars()
            .any(|ch| (0x2801..=0x28ff).contains(&(ch as u32)))
    }

    #[test]
    fn clip_text_to_width_preserves_two_char_markers() {
        let cells = vec![
            super::StyledCell {
                ch: 'A',
                color: Some(Color::Green),
                bg_color: Some(Color::Red),
                priority: 1,
            },
            super::StyledCell {
                ch: 'B',
                color: Some(Color::Green),
                bg_color: Some(Color::Red),
                priority: 1,
            },
            super::StyledCell {
                ch: 'C',
                color: Some(Color::Green),
                bg_color: Some(Color::Red),
                priority: 1,
            },
        ];
        let line = super::styled_cells_line(&cells);
        let clipped = super::clip_text_to_width(&line.text, 2);
        assert_eq!(visible_text(&clipped), "AB");
    }

    #[test]
    fn price_chart_uses_real_candle_characters() {
        // Two candles at different heights to produce wick and body regions.
        let candles = vec![
            super::PriceCandle {
                open_cents: 100,
                high_cents: 200,
                low_cents: 50,
                close_cents: 150,
                volume: 1000,
                ema_20_cents: None,
                ema_50_cents: None,
                ema_200_cents: None,
                macd_cents: None,
                signal_cents: None,
                histogram_cents: None,
                point_count: 1,
            },
            super::PriceCandle {
                open_cents: 120,
                high_cents: 140,
                low_cents: 110,
                close_cents: 130,
                volume: 500,
                ema_20_cents: None,
                ema_50_cents: None,
                ema_200_cents: None,
                macd_cents: None,
                signal_cents: None,
                histogram_cents: None,
                point_count: 1,
            },
        ];
        let layout = super::DetailLayout {
            plot_width: 2,
            candle_slots: 2,
            price_chart_height: 10,
            volume_chart_height: 0,
            macd_chart_height: 0,
            compact_volume: false,
            show_macd: false,
            show_ema_200: false,
            show_overlay_legend: false,
            show_macd_legend: false,
            show_recent_context: false,
            compact_fundamentals: false,
            compact_consensus: false,
            compact_evidence: false,
            show_volume_profile: false,
        };
        let lines = super::render_price_chart_lines(&candles, &[], &layout);
        assert!(!lines.is_empty(), "should produce chart lines");
        let has_braille = lines
            .iter()
            .map(|line| visible_text(&line.text))
            .any(|line| contains_braille(&line));
        assert!(has_braille, "price chart should contain braille HD glyphs");
    }

    #[test]
    fn price_chart_line_count_matches_height() {
        let candles = vec![super::PriceCandle {
            open_cents: 100,
            high_cents: 200,
            low_cents: 50,
            close_cents: 150,
            volume: 0,
            ema_20_cents: None,
            ema_50_cents: None,
            ema_200_cents: None,
            macd_cents: None,
            signal_cents: None,
            histogram_cents: None,
            point_count: 1,
        }];
        let layout = super::DetailLayout {
            plot_width: 1,
            candle_slots: 1,
            price_chart_height: 6,
            volume_chart_height: 0,
            macd_chart_height: 0,
            compact_volume: false,
            show_macd: false,
            show_ema_200: false,
            show_overlay_legend: false,
            show_macd_legend: false,
            show_recent_context: false,
            compact_fundamentals: false,
            compact_consensus: false,
            compact_evidence: false,
            show_volume_profile: false,
        };
        let lines = super::render_price_chart_lines(&candles, &[], &layout);
        assert_eq!(lines.len(), 6);
    }

    #[test]
    fn price_chart_empty_candles_returns_empty() {
        let layout = super::DetailLayout {
            plot_width: 1,
            candle_slots: 1,
            price_chart_height: 6,
            volume_chart_height: 0,
            macd_chart_height: 0,
            compact_volume: false,
            show_macd: false,
            show_ema_200: false,
            show_overlay_legend: false,
            show_macd_legend: false,
            show_recent_context: false,
            compact_fundamentals: false,
            compact_consensus: false,
            compact_evidence: false,
            show_volume_profile: false,
        };
        let lines = super::render_price_chart_lines(&[], &[], &layout);
        assert!(lines.is_empty());
    }

    #[test]
    fn price_chart_contains_separator() {
        let candles = vec![super::PriceCandle {
            open_cents: 100,
            high_cents: 200,
            low_cents: 50,
            close_cents: 150,
            volume: 0,
            ema_20_cents: None,
            ema_50_cents: None,
            ema_200_cents: None,
            macd_cents: None,
            signal_cents: None,
            histogram_cents: None,
            point_count: 1,
        }];
        let layout = super::DetailLayout {
            plot_width: 1,
            candle_slots: 1,
            price_chart_height: 3,
            volume_chart_height: 0,
            macd_chart_height: 0,
            compact_volume: false,
            show_macd: false,
            show_ema_200: false,
            show_overlay_legend: false,
            show_macd_legend: false,
            show_recent_context: false,
            compact_fundamentals: false,
            compact_consensus: false,
            compact_evidence: false,
            show_volume_profile: false,
        };
        let lines = super::render_price_chart_lines(&candles, &[], &layout);
        for line in &lines {
            let text = visible_text(&line.text);
            assert!(text.contains('│'), "expected separator in: {text}");
        }
    }

    #[test]
    fn price_chart_down_candle_renders_hd_braille_body() {
        let candles = vec![super::PriceCandle {
            open_cents: 200,
            high_cents: 250,
            low_cents: 50,
            close_cents: 100,
            volume: 0,
            ema_20_cents: None,
            ema_50_cents: None,
            ema_200_cents: None,
            macd_cents: None,
            signal_cents: None,
            histogram_cents: None,
            point_count: 1,
        }];
        let layout = super::DetailLayout {
            plot_width: 1,
            candle_slots: 1,
            price_chart_height: 10,
            volume_chart_height: 0,
            macd_chart_height: 0,
            compact_volume: false,
            show_macd: false,
            show_ema_200: false,
            show_overlay_legend: false,
            show_macd_legend: false,
            show_recent_context: false,
            compact_fundamentals: false,
            compact_consensus: false,
            compact_evidence: false,
            show_volume_profile: false,
        };
        let lines = super::render_price_chart_lines(&candles, &[], &layout);
        let has_braille = lines
            .iter()
            .map(|line| visible_text(&line.text))
            .any(|line| contains_braille(&line));
        assert!(has_braille, "down candle should render braille HD glyphs");
    }

    // ── ChartCanvas tests ──────────────────────────────────────────

    #[test]
    fn chart_canvas_new_correct_dimensions() {
        let canvas = super::ChartCanvas::new(4, 10, 0.0, 100.0);
        assert_eq!(canvas.pixels.len(), 4 * 2 * 10);
        assert_eq!(canvas.glyphs.len(), 4 * 10);
        assert_eq!(canvas.width, 10);
        assert_eq!(canvas.terminal_height, 4);
    }

    #[test]
    fn chart_canvas_new_zero_dimensions() {
        let canvas = super::ChartCanvas::new(0, 5, 0.0, 100.0);
        assert_eq!(canvas.pixels.len(), 0);
        assert_eq!(canvas.glyphs.len(), 0);
    }

    #[test]
    fn chart_canvas_reset_clears_and_updates_range() {
        let mut canvas = super::ChartCanvas::new(2, 3, 0.0, 100.0);
        canvas.fill_pixel(0, 0, Some(Color::Red), 5);
        canvas.draw_glyph(0, 0, 'X', Some(Color::Green), 5);
        canvas.reset(50.0, 200.0);
        assert_eq!(canvas.min_value, 50.0);
        assert_eq!(canvas.max_value, 200.0);
        assert!(canvas.pixels.iter().all(|p| p.color.is_none()));
        assert!(canvas.glyphs.iter().all(|g| g.is_none()));
    }

    #[test]
    fn chart_canvas_reset_preserves_capacity() {
        let mut canvas = super::ChartCanvas::new(4, 10, 0.0, 100.0);
        let pixel_cap = canvas.pixels.capacity();
        let glyph_cap = canvas.glyphs.capacity();
        canvas.reset(0.0, 50.0);
        assert!(canvas.pixels.capacity() >= pixel_cap);
        assert!(canvas.glyphs.capacity() >= glyph_cap);
    }

    #[test]
    fn chart_canvas_fill_pixel_writes() {
        let mut canvas = super::ChartCanvas::new(2, 3, 0.0, 10.0);
        canvas.fill_pixel(1, 2, Some(Color::Green), 5);
        let idx = 1 * 3 + 2;
        assert_eq!(canvas.pixels[idx].color, Some(Color::Green));
        assert_eq!(canvas.pixels[idx].priority, 5);
    }

    #[test]
    fn chart_canvas_fill_pixel_higher_priority_wins() {
        let mut canvas = super::ChartCanvas::new(2, 3, 0.0, 10.0);
        canvas.fill_pixel(0, 0, Some(Color::Green), 5);
        canvas.fill_pixel(0, 0, Some(Color::Red), 3);
        assert_eq!(canvas.pixels[0].color, Some(Color::Green));
    }

    #[test]
    fn chart_canvas_fill_pixel_equal_priority_overwrites() {
        let mut canvas = super::ChartCanvas::new(1, 1, 0.0, 10.0);
        canvas.fill_pixel(0, 0, Some(Color::Green), 5);
        canvas.fill_pixel(0, 0, Some(Color::Red), 5);
        assert_eq!(canvas.pixels[0].color, Some(Color::Red));
    }

    #[test]
    fn chart_canvas_fill_pixel_oob_is_noop() {
        let mut canvas = super::ChartCanvas::new(2, 3, 0.0, 10.0);
        canvas.fill_pixel(999, 999, Some(Color::Red), 5);
    }

    #[test]
    fn chart_canvas_draw_glyph_writes() {
        let mut canvas = super::ChartCanvas::new(2, 3, 0.0, 10.0);
        canvas.draw_glyph(1, 2, 'X', Some(Color::Cyan), 5);
        let idx = 1 * 3 + 2;
        let glyph = canvas.glyphs[idx].unwrap();
        assert_eq!(glyph.ch, 'X');
        assert_eq!(glyph.color, Some(Color::Cyan));
        assert_eq!(glyph.priority, 5);
    }

    #[test]
    fn chart_canvas_draw_glyph_higher_priority_wins() {
        let mut canvas = super::ChartCanvas::new(1, 1, 0.0, 10.0);
        canvas.draw_glyph(0, 0, 'A', Some(Color::Green), 5);
        canvas.draw_glyph(0, 0, 'B', Some(Color::Red), 3);
        assert_eq!(canvas.glyphs[0].unwrap().ch, 'A');
    }

    #[test]
    fn chart_canvas_draw_glyph_oob_is_noop() {
        let mut canvas = super::ChartCanvas::new(2, 3, 0.0, 10.0);
        canvas.draw_glyph(999, 999, 'X', Some(Color::Red), 5);
    }

    #[test]
    fn chart_canvas_map_to_hires_row_boundaries() {
        let canvas = super::ChartCanvas::new(4, 10, 0.0, 100.0);
        assert_eq!(canvas.map_to_hires_row(100.0), 0);
        assert_eq!(canvas.map_to_hires_row(0.0), 7);
    }

    #[test]
    fn chart_canvas_map_to_terminal_row_boundaries() {
        let canvas = super::ChartCanvas::new(4, 10, 0.0, 100.0);
        assert_eq!(canvas.map_to_terminal_row(100.0), 0);
        assert_eq!(canvas.map_to_terminal_row(0.0), 3);
    }

    #[test]
    fn chart_canvas_plot_pixel_maps_value() {
        let mut canvas = super::ChartCanvas::new(4, 10, 0.0, 100.0);
        canvas.plot_pixel(100.0, 5, Some(Color::Red), 5);
        let expected_row = canvas.map_to_hires_row(100.0);
        let idx = expected_row * 10 + 5;
        assert_eq!(canvas.pixels[idx].color, Some(Color::Red));
    }

    #[test]
    fn chart_canvas_plot_glyph_maps_value() {
        let mut canvas = super::ChartCanvas::new(4, 10, 0.0, 100.0);
        canvas.plot_glyph(100.0, 5, '.', Some(Color::Yellow), 2);
        let expected_row = canvas.map_to_terminal_row(100.0);
        let idx = expected_row * 10 + 5;
        assert_eq!(canvas.glyphs[idx].unwrap().ch, '.');
    }

    #[test]
    fn chart_canvas_fill_vertical_fills_range() {
        let mut canvas = super::ChartCanvas::new(3, 1, 0.0, 10.0);
        canvas.fill_vertical(0, 2, 4, Some(Color::Blue), 3);
        assert_eq!(canvas.pixels[2].color, Some(Color::Blue));
        assert_eq!(canvas.pixels[3].color, Some(Color::Blue));
        assert_eq!(canvas.pixels[4].color, Some(Color::Blue));
        assert!(canvas.pixels[0].color.is_none());
        assert!(canvas.pixels[1].color.is_none());
        assert!(canvas.pixels[5].color.is_none());
    }

    #[test]
    fn chart_canvas_fill_vertical_swapped_bounds() {
        let mut canvas = super::ChartCanvas::new(3, 1, 0.0, 10.0);
        canvas.fill_vertical(0, 4, 2, Some(Color::Blue), 3);
        assert_eq!(canvas.pixels[2].color, Some(Color::Blue));
        assert_eq!(canvas.pixels[3].color, Some(Color::Blue));
        assert_eq!(canvas.pixels[4].color, Some(Color::Blue));
    }

    #[test]
    fn chart_canvas_plot_hline_full_width() {
        let mut canvas = super::ChartCanvas::new(4, 5, 0.0, 100.0);
        canvas.plot_hline(50.0, Some(Color::DarkGrey), 1);
        let expected_row = canvas.map_to_hires_row(50.0);
        for col in 0..5 {
            let idx = expected_row * 5 + col;
            assert_eq!(canvas.pixels[idx].color, Some(Color::DarkGrey));
        }
    }

    #[test]
    fn chart_canvas_collapse_empty() {
        let canvas = super::ChartCanvas::new(2, 3, 0.0, 10.0);
        let cells = canvas.collapse_to_cells();
        assert_eq!(cells.len(), 2);
        assert_eq!(cells[0].len(), 3);
        for row in &cells {
            for cell in row {
                assert_eq!(cell.ch, ' ');
                assert_eq!(cell.color, None);
            }
        }
    }

    #[test]
    fn chart_canvas_collapse_same_color_pair() {
        let mut canvas = super::ChartCanvas::new(1, 1, 0.0, 10.0);
        canvas.fill_pixel(0, 0, Some(Color::Green), 5);
        canvas.fill_pixel(1, 0, Some(Color::Green), 5);
        let cells = canvas.collapse_to_cells();
        let cell = &cells[0][0];
        assert_eq!(cell.ch, '█');
        assert_eq!(cell.color, Some(Color::Green));
        assert_eq!(cell.bg_color, None);
    }

    #[test]
    fn chart_canvas_collapse_different_colors() {
        let mut canvas = super::ChartCanvas::new(1, 1, 0.0, 10.0);
        canvas.fill_pixel(0, 0, Some(Color::Red), 5);
        canvas.fill_pixel(1, 0, Some(Color::Green), 5);
        let cells = canvas.collapse_to_cells();
        let cell = &cells[0][0];
        assert_eq!(cell.ch, '▄');
        assert_eq!(cell.color, Some(Color::Green));
        assert_eq!(cell.bg_color, Some(Color::Red));
    }

    #[test]
    fn chart_canvas_collapse_top_only() {
        let mut canvas = super::ChartCanvas::new(1, 1, 0.0, 10.0);
        canvas.fill_pixel(0, 0, Some(Color::Red), 5);
        let cells = canvas.collapse_to_cells();
        let cell = &cells[0][0];
        assert_eq!(cell.ch, '▀');
        assert_eq!(cell.color, Some(Color::Red));
        assert_eq!(cell.bg_color, None);
    }

    #[test]
    fn chart_canvas_collapse_bottom_only() {
        let mut canvas = super::ChartCanvas::new(1, 1, 0.0, 10.0);
        canvas.fill_pixel(1, 0, Some(Color::Blue), 5);
        let cells = canvas.collapse_to_cells();
        let cell = &cells[0][0];
        assert_eq!(cell.ch, '▄');
        assert_eq!(cell.color, Some(Color::Blue));
        assert_eq!(cell.bg_color, None);
    }

    #[test]
    fn chart_canvas_collapse_glyph_overrides_when_priority_wins() {
        let mut canvas = super::ChartCanvas::new(1, 1, 0.0, 10.0);
        canvas.fill_pixel(0, 0, Some(Color::Green), 3);
        canvas.fill_pixel(1, 0, Some(Color::Green), 3);
        canvas.draw_glyph(0, 0, '█', Some(Color::Red), 5);
        let cells = canvas.collapse_to_cells();
        let cell = &cells[0][0];
        assert_eq!(cell.ch, '█');
        assert_eq!(cell.color, Some(Color::Red));
        assert_eq!(cell.bg_color, Some(Color::Green));
    }

    #[test]
    fn chart_canvas_collapse_pixel_wins_when_glyph_lower_priority() {
        let mut canvas = super::ChartCanvas::new(1, 1, 0.0, 10.0);
        canvas.fill_pixel(0, 0, Some(Color::Red), 5);
        canvas.fill_pixel(1, 0, Some(Color::Green), 5);
        canvas.draw_glyph(0, 0, 'X', Some(Color::Yellow), 1);
        let cells = canvas.collapse_to_cells();
        let cell = &cells[0][0];
        assert_eq!(cell.ch, '▄');
        assert_eq!(cell.color, Some(Color::Green));
        assert_eq!(cell.bg_color, Some(Color::Red));
    }

    #[test]
    fn chart_canvas_collapse_glyph_no_pixels() {
        let mut canvas = super::ChartCanvas::new(1, 1, 0.0, 10.0);
        canvas.draw_glyph(0, 0, 'X', Some(Color::Cyan), 5);
        let cells = canvas.collapse_to_cells();
        let cell = &cells[0][0];
        assert_eq!(cell.ch, 'X');
        assert_eq!(cell.color, Some(Color::Cyan));
        assert_eq!(cell.bg_color, None);
    }

    #[test]
    fn chart_canvas_collapse_zero_height() {
        let canvas = super::ChartCanvas::new(0, 3, 0.0, 10.0);
        let cells = canvas.collapse_to_cells();
        assert!(cells.is_empty());
    }

    #[test]
    fn chart_canvas_with_axis_correct_row_count() {
        let canvas = super::ChartCanvas::new(5, 10, 0.0, 100.0);
        let lines = canvas.with_axis(|_row, _height| "label".to_string());
        assert_eq!(lines.len(), 5);
    }

    #[test]
    fn chart_canvas_with_axis_labels_at_top_mid_bottom() {
        let canvas = super::ChartCanvas::new(5, 2, 0.0, 100.0);
        let lines = canvas.with_axis(|row_index, _height| format!("R{}", row_index));
        let texts: Vec<String> = lines.iter().map(|l| visible_text(&l.text)).collect();
        assert!(texts[0].contains("R0"));
        assert!(texts[2].contains("R2"));
        assert!(texts[4].contains("R4"));
        assert!(!texts[1].contains("R1"));
        assert!(!texts[3].contains("R3"));
    }

    #[test]
    fn chart_canvas_with_axis_contains_separator() {
        let canvas = super::ChartCanvas::new(3, 2, 0.0, 10.0);
        let lines = canvas.with_axis(|_, _| "X".to_string());
        for line in &lines {
            let text = visible_text(&line.text);
            assert!(text.contains('│'));
        }
    }

    #[test]
    fn chart_canvas_deterministic_output() {
        let mut canvas = super::ChartCanvas::new(3, 5, 0.0, 100.0);
        canvas.fill_pixel(0, 0, Some(Color::Red), 5);
        canvas.fill_pixel(3, 2, Some(Color::Green), 3);
        canvas.draw_glyph(1, 4, '.', Some(Color::Yellow), 2);
        let lines_a = canvas.with_axis(|r, _| format!("{}", r));
        let lines_b = canvas.with_axis(|r, _| format!("{}", r));
        assert_eq!(lines_a, lines_b);
    }

    fn make_volume_candle(volume: u64) -> super::PriceCandle {
        super::PriceCandle {
            open_cents: 100,
            high_cents: 100,
            low_cents: 100,
            close_cents: 100,
            volume,
            ema_20_cents: None,
            ema_50_cents: None,
            ema_200_cents: None,
            macd_cents: None,
            signal_cents: None,
            histogram_cents: None,
            point_count: 1,
        }
    }

    #[test]
    fn volume_chart_empty_candles_returns_empty() {
        let lines = super::render_volume_chart_lines(&[], 5);
        assert!(lines.is_empty());
    }

    #[test]
    fn volume_chart_zero_height_returns_empty() {
        let candles = vec![make_volume_candle(1000)];
        let lines = super::render_volume_chart_lines(&candles, 0);
        assert!(lines.is_empty());
    }

    #[test]
    fn volume_chart_line_count_matches_height() {
        let candles = vec![make_volume_candle(1000), make_volume_candle(500)];
        let lines = super::render_volume_chart_lines(&candles, 4);
        assert_eq!(lines.len(), 4);
    }

    #[test]
    fn volume_chart_axis_label_present() {
        let candles = vec![make_volume_candle(2_000_000)];
        let lines = super::render_volume_chart_lines(&candles, 3);
        let top_text = visible_text(&lines[0].text);
        assert!(
            top_text.contains("2.0M"),
            "expected '2.0M' in top axis, got: {top_text}"
        );
    }

    #[test]
    fn volume_chart_contains_separator() {
        let candles = vec![make_volume_candle(1000)];
        let lines = super::render_volume_chart_lines(&candles, 3);
        for line in &lines {
            let text = visible_text(&line.text);
            assert!(text.contains('│'), "expected separator in: {text}");
        }
    }

    #[test]
    fn volume_chart_skips_zero_volume() {
        let candles = vec![make_volume_candle(0), make_volume_candle(1000)];
        let lines = super::render_volume_chart_lines(&candles, 3);
        assert_eq!(lines.len(), 3);
    }

    fn make_macd_candle(
        macd: Option<f64>,
        signal: Option<f64>,
        histogram: Option<f64>,
    ) -> super::PriceCandle {
        super::PriceCandle {
            open_cents: 100,
            high_cents: 100,
            low_cents: 100,
            close_cents: 100,
            volume: 0,
            ema_20_cents: None,
            ema_50_cents: None,
            ema_200_cents: None,
            macd_cents: macd,
            signal_cents: signal,
            histogram_cents: histogram,
            point_count: 1,
        }
    }

    #[test]
    fn macd_chart_empty_candles_returns_empty() {
        let lines = super::render_macd_chart_lines(&[], 5);
        assert!(lines.is_empty());
    }

    #[test]
    fn macd_chart_zero_height_returns_empty() {
        let candles = vec![make_macd_candle(Some(1.0), Some(0.5), Some(0.5))];
        let lines = super::render_macd_chart_lines(&candles, 0);
        assert!(lines.is_empty());
    }

    #[test]
    fn macd_chart_line_count_matches_height() {
        let candles = vec![
            make_macd_candle(Some(2.0), Some(1.0), Some(1.0)),
            make_macd_candle(Some(-1.0), Some(-0.5), Some(-0.5)),
        ];
        let lines = super::render_macd_chart_lines(&candles, 4);
        assert_eq!(lines.len(), 4);
    }

    #[test]
    fn macd_chart_contains_separator() {
        let candles = vec![make_macd_candle(Some(1.0), Some(0.5), Some(0.5))];
        let lines = super::render_macd_chart_lines(&candles, 3);
        for line in &lines {
            let text = visible_text(&line.text);
            assert!(text.contains('│'), "expected separator in: {text}");
        }
    }

    #[test]
    fn macd_chart_axis_labels_present() {
        let candles = vec![
            make_macd_candle(Some(200.0), Some(100.0), Some(100.0)),
            make_macd_candle(Some(-50.0), Some(-25.0), Some(-25.0)),
        ];
        let lines = super::render_macd_chart_lines(&candles, 5);
        let top_text = visible_text(&lines[0].text);
        assert!(
            top_text.contains('$'),
            "expected dollar sign in top axis, got: {top_text}"
        );
    }

    #[test]
    fn macd_chart_handles_all_none_values() {
        let candles = vec![make_macd_candle(None, None, None)];
        let lines = super::render_macd_chart_lines(&candles, 3);
        assert_eq!(lines.len(), 3);
    }

    #[test]
    fn volume_chart_fills_correct_columns() {
        // Single candle: body at columns 0,1 (index=0 → body_column=1, body-1=0).
        // With 1 candle, chart width=2. The bottom row should have non-space chart content.
        let candles = vec![make_volume_candle(1000)];
        let lines = super::render_volume_chart_lines(&candles, 2);
        // The bottom row should contain block characters after the axis separator
        let bottom_text = visible_text(&lines[1].text);
        let after_sep: String = bottom_text
            .chars()
            .skip_while(|c| *c != '│')
            .skip(1)
            .collect();
        assert!(
            contains_braille(&after_sep),
            "bottom row chart area should contain braille HD glyphs, got: {after_sep}"
        );
    }

    #[test]
    fn volume_chart_two_candles_render_two_hd_cells() {
        let candles = vec![make_volume_candle(1000), make_volume_candle(1000)];
        let lines = super::render_volume_chart_lines(&candles, 1);
        let text = visible_text(&lines[0].text);
        let after_sep: String = text.chars().skip_while(|c| *c != '│').skip(1).collect();
        let braille_count = after_sep
            .chars()
            .filter(|ch| contains_braille(&ch.to_string()))
            .count();
        assert!(
            braille_count >= 2,
            "should have at least 2 braille cells for 2 candles, got {braille_count} in: {after_sep}"
        );
    }

    #[test]
    fn macd_chart_histogram_uses_correct_columns() {
        // Positive histogram should fill chart area columns
        let candles = vec![make_macd_candle(Some(100.0), Some(50.0), Some(50.0))];
        let lines = super::render_macd_chart_lines(&candles, 3);
        let has_braille = lines.iter().any(|line| {
            let text = visible_text(&line.text);
            let after_sep: String = text.chars().skip_while(|c| *c != '│').skip(1).collect();
            contains_braille(&after_sep)
        });
        assert!(
            has_braille,
            "MACD histogram should produce braille HD glyphs"
        );
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct ContractFixture {
        snapshots: Vec<ContractMarketSnapshot>,
        external_signals: Vec<ContractExternalSignal>,
        fundamentals: Vec<ContractFundamentalSnapshot>,
        watchlist: Vec<String>,
        query: String,
        chart_summaries: Vec<ContractChartSummary>,
        dcf_analyses: Vec<ContractDcfAnalysis>,
        expected_selected_detail: ContractSelectedDetail,
        expected_candidate_order: Vec<String>,
        expected_opportunity_order: Vec<String>,
        expected_watchlist_only_order: Vec<String>,
        expected_query_order: Vec<String>,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct ContractSelectedDetail {
        symbol: String,
        qualification: QualificationStatus,
        external_status: ExternalSignalStatus,
        confidence: ConfidenceBand,
        gap_bps: i32,
        external_signal_gap_bps: Option<i32>,
        weighted_external_signal_fair_value_cents: Option<i64>,
        weighted_analyst_count: Option<u32>,
        analyst_opinion_count: Option<u32>,
        recommendation_mean_hundredths: Option<u16>,
        is_watched: bool,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct ContractMarketSnapshot {
        symbol: String,
        profitable: bool,
        market_price_cents: i64,
        intrinsic_value_cents: i64,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct ContractExternalSignal {
        symbol: String,
        fair_value_cents: i64,
        age_seconds: u64,
        low_fair_value_cents: Option<i64>,
        high_fair_value_cents: Option<i64>,
        analyst_opinion_count: Option<u32>,
        recommendation_mean_hundredths: Option<u16>,
        weighted_fair_value_cents: Option<i64>,
        weighted_analyst_count: Option<u32>,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct ContractFundamentalSnapshot {
        symbol: String,
        free_cash_flow_dollars: Option<i64>,
        operating_cash_flow_dollars: Option<i64>,
        return_on_equity_bps: Option<i32>,
        debt_to_equity_hundredths: Option<i32>,
        total_cash_dollars: Option<i64>,
        total_debt_dollars: Option<i64>,
        earnings_growth_bps: Option<i32>,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct ContractChartSummary {
        symbol: String,
        range: ChartRange,
        latest_close_cents: i64,
        ema20_cents: Option<i64>,
        ema50_cents: Option<i64>,
        ema200_cents: Option<i64>,
        macd_cents: Option<i64>,
        signal_cents: Option<i64>,
        histogram_cents: Option<i64>,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct ContractDcfAnalysis {
        symbol: String,
        bear_intrinsic_value_cents: i64,
        base_intrinsic_value_cents: i64,
        bull_intrinsic_value_cents: i64,
        wacc_bps: i32,
        base_growth_bps: i32,
        net_debt_dollars: i64,
    }

    fn load_contract_fixture() -> ContractFixture {
        let path = concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../../shared/contracts/portfolio-ranking.json"
        );
        serde_json::from_str(&std::fs::read_to_string(path).expect("read contract fixture"))
            .expect("parse contract fixture")
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct ChartRangesFixture {
        ranges: Vec<ChartRangeExpectation>,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct ChartRangeExpectation {
        id: ChartRange,
        label: String,
    }

    fn load_chart_ranges_fixture() -> ChartRangesFixture {
        let path = concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../../shared/contracts/chart-ranges.json"
        );
        serde_json::from_str(&std::fs::read_to_string(path).expect("read chart range fixture"))
            .expect("parse chart range fixture")
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct DcfSourceSelectionFixture {
        cases: Vec<DcfSourceSelectionCase>,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct DcfSourceSelectionCase {
        name: String,
        expected_resolver_state: String,
    }

    fn load_dcf_source_selection_fixture() -> DcfSourceSelectionFixture {
        let path = concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../../shared/contracts/dcf-source-selection.json"
        );
        serde_json::from_str(&std::fs::read_to_string(path).expect("read DCF source fixture"))
            .expect("parse DCF source fixture")
    }

    #[test]
    fn contract_fixture_dcf_source_selection_is_available_to_desktop() {
        let fixture = load_dcf_source_selection_fixture();

        assert!(fixture.cases.iter().any(|case| {
            case.name == "both_pass_materially_disagree"
                && case.expected_resolver_state == "ProviderUncertain"
        }));
    }

    #[test]
    fn contract_fixture_chart_ranges_match_desktop_behavior() {
        let fixture = load_chart_ranges_fixture();
        let actual_ranges = chart_ranges();
        let actual_pairs = actual_ranges
            .into_iter()
            .map(|range| (range, chart_range_label(range).to_string()))
            .collect::<Vec<_>>();
        let expected_pairs = fixture
            .ranges
            .into_iter()
            .map(|range| (range.id, range.label))
            .collect::<Vec<_>>();
        assert_eq!(expected_pairs, actual_pairs);
    }

    #[test]
    fn contract_fixture_portfolio_ranking_matches_desktop_behavior() {
        let ContractFixture {
            snapshots,
            external_signals,
            fundamentals,
            watchlist,
            query,
            chart_summaries,
            dcf_analyses,
            expected_selected_detail,
            expected_candidate_order,
            expected_opportunity_order,
            expected_watchlist_only_order,
            expected_query_order,
        } = load_contract_fixture();
        let mut state = TerminalState::new(2_000, 30, 8);
        let mut app = AppState::default();

        for snapshot in snapshots {
            state.ingest_snapshot(discount_screener::MarketSnapshot {
                symbol: snapshot.symbol,
                company_name: None,
                profitable: snapshot.profitable,
                market_price_cents: snapshot.market_price_cents,
                intrinsic_value_cents: snapshot.intrinsic_value_cents,
            });
        }
        for external_signal in external_signals {
            state.ingest_external(discount_screener::ExternalValuationSignal {
                symbol: external_signal.symbol,
                fair_value_cents: external_signal.fair_value_cents,
                age_seconds: external_signal.age_seconds,
                low_fair_value_cents: external_signal.low_fair_value_cents,
                high_fair_value_cents: external_signal.high_fair_value_cents,
                analyst_opinion_count: external_signal.analyst_opinion_count,
                recommendation_mean_hundredths: external_signal.recommendation_mean_hundredths,
                strong_buy_count: None,
                buy_count: None,
                hold_count: None,
                sell_count: None,
                strong_sell_count: None,
                weighted_fair_value_cents: external_signal.weighted_fair_value_cents,
                weighted_analyst_count: external_signal.weighted_analyst_count,
            });
        }
        for fundamentals in fundamentals {
            let fundamentals = discount_screener::FundamentalSnapshot {
                symbol: fundamentals.symbol,
                sector_key: None,
                sector_name: None,
                industry_key: None,
                industry_name: None,
                market_cap_dollars: None,
                shares_outstanding: None,
                trailing_pe_hundredths: None,
                forward_pe_hundredths: None,
                price_to_book_hundredths: None,
                return_on_equity_bps: fundamentals.return_on_equity_bps,
                ebitda_dollars: None,
                enterprise_value_dollars: None,
                enterprise_to_ebitda_hundredths: None,
                total_debt_dollars: fundamentals.total_debt_dollars,
                total_cash_dollars: fundamentals.total_cash_dollars,
                debt_to_equity_hundredths: fundamentals.debt_to_equity_hundredths,
                free_cash_flow_dollars: fundamentals.free_cash_flow_dollars,
                operating_cash_flow_dollars: fundamentals.operating_cash_flow_dollars,
                beta_millis: None,
                trailing_eps_cents: None,
                earnings_growth_bps: fundamentals.earnings_growth_bps,
            };
            let analysis_input = analysis_input_key(&fundamentals);
            let symbol = fundamentals.symbol.clone();
            state.ingest_fundamentals(fundamentals);
            if let Some(analysis) = dcf_analyses
                .iter()
                .find(|analysis| analysis.symbol == symbol)
            {
                app.analysis_cache.insert(
                    symbol,
                    AnalysisCacheEntry::Ready {
                        input: analysis_input,
                        analysis: DcfAnalysis {
                            bear_intrinsic_value_cents: analysis.bear_intrinsic_value_cents,
                            base_intrinsic_value_cents: analysis.base_intrinsic_value_cents,
                            bull_intrinsic_value_cents: analysis.bull_intrinsic_value_cents,
                            wacc_bps: analysis.wacc_bps,
                            base_growth_bps: analysis.base_growth_bps,
                            net_debt_dollars: analysis.net_debt_dollars,
                            selected_source: FundamentalTimeseriesSource::YahooFinance,
                            source_policy_stage: "DesktopYahooOnly".to_string(),
                            source_fingerprint: "DesktopYahooOnly".to_string(),
                            decision_fingerprint: "DesktopYahooOnly|state=Selected|sec=DesktopSecDeferred".to_string(),
                        },
                    },
                );
            }
        }
        for symbol in watchlist {
            state.toggle_watchlist(&symbol);
        }
        for summary in chart_summaries {
            app.chart_summary_cache.insert(
                ChartCacheKey::new(&summary.symbol, summary.range),
                ChartRangeSummary {
                    range: summary.range,
                    captured_at: 0,
                    candle_count: 52,
                    latest_close_cents: Some(summary.latest_close_cents),
                    ema20_cents: summary.ema20_cents,
                    ema50_cents: summary.ema50_cents,
                    ema200_cents: summary.ema200_cents,
                    macd_cents: summary.macd_cents,
                    signal_cents: summary.signal_cents,
                    histogram_cents: summary.histogram_cents,
                },
            );
        }

        let candidate_order = filtered_symbol_rows(&state, &ViewFilter::default())
            .into_iter()
            .map(|row| row.symbol)
            .collect::<Vec<_>>();
        assert_eq!(candidate_order, expected_candidate_order);

        let watchlist_only_order = filtered_symbol_rows(
            &state,
            &ViewFilter {
                query: String::new(),
                watchlist_only: true,
            },
        )
        .into_iter()
        .map(|row| row.symbol)
        .collect::<Vec<_>>();
        assert_eq!(watchlist_only_order, expected_watchlist_only_order);

        let query_order = filtered_symbol_rows(
            &state,
            &ViewFilter {
                query,
                watchlist_only: false,
            },
        )
        .into_iter()
        .map(|row| row.symbol)
        .collect::<Vec<_>>();
        assert_eq!(query_order, expected_query_order);

        let opportunity_order = build_opportunity_rows(&state, &app)
            .into_iter()
            .map(|row| row.symbol)
            .collect::<Vec<_>>();
        assert_eq!(opportunity_order, expected_opportunity_order);

        let selected_detail = state
            .detail(&expected_selected_detail.symbol)
            .expect("expected selected detail");
        assert_eq!(
            selected_detail.qualification,
            expected_selected_detail.qualification
        );
        assert_eq!(
            selected_detail.external_status,
            expected_selected_detail.external_status
        );
        assert_eq!(
            selected_detail.confidence,
            expected_selected_detail.confidence
        );
        assert_eq!(selected_detail.gap_bps, expected_selected_detail.gap_bps);
        assert_eq!(
            selected_detail.external_signal_gap_bps,
            expected_selected_detail.external_signal_gap_bps
        );
        assert_eq!(
            selected_detail.weighted_external_signal_fair_value_cents,
            expected_selected_detail.weighted_external_signal_fair_value_cents
        );
        assert_eq!(
            selected_detail.weighted_analyst_count,
            expected_selected_detail.weighted_analyst_count
        );
        assert_eq!(
            selected_detail.analyst_opinion_count,
            expected_selected_detail.analyst_opinion_count
        );
        assert_eq!(
            selected_detail.recommendation_mean_hundredths,
            expected_selected_detail.recommendation_mean_hundredths
        );
        assert_eq!(
            selected_detail.is_watched,
            expected_selected_detail.is_watched
        );
    }
}
