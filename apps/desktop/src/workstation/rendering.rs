fn render(
    state: &TerminalState,
    rows: &[CandidateRow],
    selected_index: usize,
    updates_per_second: usize,
    live_mode: bool,
    app: &AppState,
    live_symbols: Option<&LiveSymbolState>,
    screen_renderer: &mut ScreenRenderer,
) -> io::Result<()> {
    let (viewport_width, viewport_height) = terminal_viewport()?;
    let lines = build_screen_lines_for_viewport(
        state,
        rows,
        selected_index,
        updates_per_second,
        live_mode,
        app,
        live_symbols,
        viewport_width,
        viewport_height,
    );

    screen_renderer.render(&lines, viewport_width, viewport_height)
}

#[cfg(test)]
fn build_screen_lines(
    state: &TerminalState,
    rows: &[CandidateRow],
    selected_index: usize,
    updates_per_second: usize,
    live_mode: bool,
    app: &AppState,
    live_symbols: Option<&LiveSymbolState>,
) -> Vec<RenderLine> {
    build_screen_lines_for_viewport(
        state,
        rows,
        selected_index,
        updates_per_second,
        live_mode,
        app,
        live_symbols,
        DEFAULT_VIEWPORT_WIDTH,
        DEFAULT_VIEWPORT_HEIGHT,
    )
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct OpportunityScoreBreakdown {
    fundamentals_score: Option<i32>,
    technical_score: Option<i32>,
    forecast_score: Option<i32>,
    composite_score: i32,
    coverage_count: usize,
    fundamentals_signals: Vec<&'static str>,
    technical_signals: Vec<&'static str>,
    forecast_signals: Vec<&'static str>,
}

fn build_screen_lines_for_viewport(
    state: &TerminalState,
    rows: &[CandidateRow],
    selected_index: usize,
    updates_per_second: usize,
    live_mode: bool,
    app: &AppState,
    live_symbols: Option<&LiveSymbolState>,
    viewport_width: usize,
    viewport_height: usize,
) -> Vec<RenderLine> {
    match &app.overlay_mode {
        OverlayMode::IssueLog => return build_issue_log_lines(app),
        OverlayMode::TickerDetail(symbol) => {
            return match app.detail_tab {
                DetailTab::Snapshot => build_ticker_detail_lines_for_viewport(
                    state,
                    app,
                    symbol,
                    viewport_width,
                    viewport_height,
                ),
                DetailTab::History => build_ticker_history_lines_for_viewport(
                    app,
                    symbol,
                    viewport_width,
                    viewport_height,
                ),
            };
        }
        OverlayMode::None => {}
    }

    let opportunity_rows = matches!(app.primary_view, PrimaryViewMode::Opportunities)
        .then(|| app.visible_opportunity_rows(state));
    let selected_symbol = opportunity_rows
        .as_ref()
        .and_then(|opportunities| opportunities.get(selected_index))
        .map(|row| row.symbol.as_str())
        .or_else(|| rows.get(selected_index).map(|row| row.symbol.as_str()))
        .or(app.selected_symbol.as_deref());
    let selected_detail = selected_symbol.and_then(|symbol| state.detail(symbol));
    let mut lines = Vec::with_capacity(viewport_height);
    let tracked_count = live_symbols.map(|symbols| symbols.count()).unwrap_or(0);
    let source_status = app.live_source_status();
    let health_status = app.issue_center.health_status();
    let active_issue_count = app.issue_center.active_issue_count();

    lines.push(RenderLine {
        color: Some(Color::Yellow),
        text: main_screen_header(viewport_width, live_mode),
    });
    lines.push(RenderLine {
        color: Some(if app.paused {
            Color::Yellow
        } else {
            Color::DarkCyan
        }),
        text: main_screen_status_line(
            viewport_width,
            state,
            app,
            source_status,
            tracked_count,
            updates_per_second,
            live_mode,
        ),
    });
    if live_mode {
        lines.push(RenderLine {
            color: Some(
                app.live_feed_status
                    .as_ref()
                    .map(|status| status.color)
                    .unwrap_or(Color::DarkGrey),
            ),
            text: app
                .live_feed_status
                .as_ref()
                .map(|status| format!("Feed status: {}", status.message))
                .unwrap_or_else(|| {
                    "Feed status: waiting for the first live refresh window...".to_string()
                }),
        });
    }
    if let Some(warm_start_summary) = app.warm_start_summary() {
        lines.push(RenderLine {
            color: Some(if app.stale_symbols.is_empty() {
                Color::DarkGreen
            } else {
                Color::DarkYellow
            }),
            text: warm_start_summary,
        });
    }
    lines.push(RenderLine {
        color: Some(health_status_color(health_status)),
        text: format!(
            "Health: {}  Active issues: {}  Resolved: {}  Press l for issue log",
            health_status_label(health_status),
            active_issue_count,
            app.issue_center.resolved_issue_count(),
        ),
    });
    if let Some(toast) = app.issue_center.toast(Instant::now()) {
        lines.push(RenderLine {
            color: Some(issue_severity_color(toast.severity)),
            text: format!(
                "Popup: [{}][{}] {}. {}",
                issue_severity_label(toast.severity),
                issue_source_label(toast.source),
                toast.title,
                truncate_text(&toast.detail, 88),
            ),
        });
    } else if let Some(issue) = app.issue_center.latest_active_issue() {
        lines.push(RenderLine {
            color: Some(issue_severity_color(issue.severity)),
            text: format!(
                "Issue: [{}][{}] {}. {}",
                issue_severity_label(issue.severity),
                issue_source_label(issue.source),
                issue.title,
                truncate_text(&issue.detail, 88),
            ),
        });
    } else {
        lines.push(RenderLine {
            color: Some(Color::DarkGreen),
            text: "Issue rail: no active operational issues.".to_string(),
        });
    }
    if let Some(live_symbols) = live_symbols {
        lines.push(RenderLine {
            color: Some(Color::DarkGrey),
            text: format!(
                "Tracked symbols: {}",
                live_symbols.read_symbols(format_symbol_list)
            ),
        });
    } else {
        lines.push(RenderLine {
            color: Some(Color::DarkGrey),
            text: "Tracked symbols: replay session".to_string(),
        });
    }
    lines.push(RenderLine {
        color: Some(Color::DarkGrey),
        text: format!(
            "Filter: query='{}' watchlist_only={} input_mode={} selected={}",
            active_filter_query(&app.view_filter, &app.input_mode),
            if app.view_filter.watchlist_only {
                "on"
            } else {
                "off"
            },
            input_mode_label(&app.input_mode),
            selected_symbol.unwrap_or("none"),
        ),
    });
    lines.push(RenderLine {
        color: Some(Color::DarkGrey),
        text: input_prompt(app, live_mode, viewport_width),
    });
    if let Some(opportunity_rows) = opportunity_rows.as_ref() {
        let selected_opportunity = opportunity_rows.get(selected_index);
        let (opportunity_start, opportunity_end) =
            opportunity_window_bounds(opportunity_rows.len(), selected_index, MAX_VISIBLE_ROWS);
        let visible_opportunity_rows = &opportunity_rows[opportunity_start..opportunity_end];
        lines.push(RenderLine {
            color: Some(Color::Cyan),
            text: format!(
                "TOP OPPORTUNITIES  Model: {} [m]",
                app.opportunity_scoring_model.label()
            ),
        });
        lines.push(RenderLine {
            color: None,
            text: format!(
                "  {:>3}  {}  {:<width$} {:>5} {:>4} {:>4} {:>4} {:>8}  {}",
                "Idx",
                "W",
                "Ticker / Company",
                "Score",
                "Fund",
                "Tech",
                "Fcst",
                "Upside",
                "Confidence",
                width = CANDIDATE_COMPANY_COLUMN_WIDTH,
            ),
        });

        for (window_index, row) in visible_opportunity_rows.iter().enumerate() {
            let rank_index = opportunity_start + window_index;
            let marker = if rank_index == selected_index {
                '>'
            } else {
                ' '
            };
            let watched_marker = if row.is_watched { '*' } else { ' ' };
            let symbol_label =
                candidate_company_label(&row.symbol, state.company_name(&row.symbol));
            lines.push(RenderLine {
                color: Some(opportunity_row_color(
                    row,
                    rank_index == selected_index,
                    app.is_symbol_stale(&row.symbol),
                )),
                text: format!(
                    "{} {:>3}  {}  {:<width$} {:>5} {:>4} {:>4} {:>4} {:>8}  {}",
                    marker,
                    rank_index,
                    watched_marker,
                    symbol_label,
                    row.composite_score,
                    format_opportunity_bucket(
                        app.opportunity_scoring_model,
                        row.fundamentals_score
                    ),
                    format_opportunity_bucket(app.opportunity_scoring_model, row.technical_score),
                    format_opportunity_bucket(app.opportunity_scoring_model, row.forecast_score),
                    format_upside_percent(row.market_price_cents, row.intrinsic_value_cents),
                    confidence_label(row.confidence),
                    width = CANDIDATE_COMPANY_COLUMN_WIDTH,
                ),
            });
        }

        lines.push(RenderLine {
            color: None,
            text: String::new(),
        });
        lines.push(RenderLine {
            color: Some(Color::Green),
            text: "DETAIL".to_string(),
        });

        if let Some(selected_opportunity) = selected_opportunity {
            lines.extend(build_opportunity_detail_lines(
                state,
                app,
                selected_opportunity,
            ));
        } else {
            lines.push(RenderLine {
                color: Some(Color::DarkGrey),
                text: "No qualified opportunities match the current filter.".to_string(),
            });
        }
    } else {
        lines.push(RenderLine {
            color: Some(Color::Cyan),
            text: "TOP CANDIDATES".to_string(),
        });
        lines.push(RenderLine {
            color: None,
            text: format!(
                "  {:>3}  {}  {:<width$} {:>10} {:>10} {:>8}  {}",
                "Idx",
                "W",
                "Ticker / Company",
                "Price",
                "Fair",
                "Upside",
                "Confidence",
                width = CANDIDATE_COMPANY_COLUMN_WIDTH,
            ),
        });

        for (index, row) in rows.iter().enumerate() {
            let marker = if index == selected_index { '>' } else { ' ' };
            let watched_marker = if state.is_watched(&row.symbol) {
                '*'
            } else {
                ' '
            };
            let is_unavailable = app.symbol_is_unavailable(state, &row.symbol);
            let has_provider_error = app.symbol_has_provider_error(&row.symbol);
            let price_label = if is_unavailable {
                "n/a".to_string()
            } else {
                format_money(row.market_price_cents)
            };
            let fair_value_label = if is_unavailable {
                "n/a".to_string()
            } else {
                format_money(row.intrinsic_value_cents)
            };
            let upside_label = if is_unavailable {
                "n/a".to_string()
            } else {
                format_upside_percent(row.market_price_cents, row.intrinsic_value_cents)
            };
            let confidence_label = if is_unavailable {
                "unavailable".to_string()
            } else {
                confidence_label(row.confidence).to_string()
            };
            let symbol_label =
                candidate_company_label(&row.symbol, state.company_name(&row.symbol));
            lines.push(RenderLine {
                color: Some(candidate_display_color(
                    row,
                    index == selected_index,
                    app.is_symbol_stale(&row.symbol),
                    has_provider_error,
                )),
                text: format!(
                    "{} {:>3}  {}  {:<width$} {:>10} {:>10} {:>8}  {}",
                    marker,
                    index,
                    watched_marker,
                    symbol_label,
                    price_label,
                    fair_value_label,
                    upside_label,
                    confidence_label,
                    width = CANDIDATE_COMPANY_COLUMN_WIDTH,
                ),
            });
        }

        lines.push(RenderLine {
            color: None,
            text: String::new(),
        });
        lines.push(RenderLine {
            color: Some(Color::Green),
            text: "DETAIL".to_string(),
        });

        if let Some(selected_detail) = selected_detail {
            lines.push(RenderLine {
                color: Some(status_summary_color(
                    selected_detail.qualification,
                    selected_detail.confidence,
                )),
                text: format!(
                    "Symbol: {}  Watched: {}  Qualification: {}  Confidence: {}",
                    format_symbol_with_company(
                        &selected_detail.symbol,
                        state.company_name(&selected_detail.symbol),
                    ),
                    if selected_detail.is_watched {
                        "yes"
                    } else {
                        "no"
                    },
                    qualification_label(selected_detail.qualification),
                    confidence_label(selected_detail.confidence),
                ),
            });
            lines.push(RenderLine {
                color: Some(external_status_color(selected_detail.external_status)),
                text: format!(
                    "Price: {}  Fair value: {}  Upside: {}  External: {}",
                    format_money(selected_detail.market_price_cents),
                    format_money(selected_detail.intrinsic_value_cents),
                    format_upside_percent(
                        selected_detail.market_price_cents,
                        selected_detail.intrinsic_value_cents,
                    ),
                    external_status_label(selected_detail.external_status),
                ),
            });
            lines.push(RenderLine {
                color: Some(Color::DarkGrey),
                text: format!(
                    "Seq: {}  Updates: {}",
                    selected_detail.last_sequence, selected_detail.update_count,
                ),
            });
        } else if let Some(selected_symbol) = selected_symbol {
            if let Some(coverage) = app.symbol_coverage(selected_symbol) {
                lines.push(RenderLine {
                    color: Some(if symbol_coverage_has_error(coverage) {
                        Color::Red
                    } else {
                        Color::Yellow
                    }),
                    text: format!(
                        "Symbol: {}  Watched: {}  Status: unavailable",
                        format_symbol_with_company(
                            selected_symbol,
                            state.company_name(selected_symbol)
                        ),
                        if state.is_watched(selected_symbol) {
                            "yes"
                        } else {
                            "no"
                        },
                    ),
                });
                lines.push(RenderLine {
                    color: Some(Color::DarkGrey),
                    text: format!(
                        "Coverage: core={}  external={}  fundamentals={}",
                        provider_component_state_label(coverage.coverage.core),
                        provider_component_state_label(coverage.coverage.external),
                        provider_component_state_label(coverage.coverage.fundamentals),
                    ),
                });
                for detail_line in wrap_text(
                    &format!("Source error: {}", format_symbol_coverage_summary(coverage)),
                    108,
                ) {
                    lines.push(RenderLine {
                        color: Some(Color::DarkYellow),
                        text: detail_line,
                    });
                }
            } else {
                lines.push(RenderLine {
                    color: None,
                    text: "No active symbols yet.".to_string(),
                });
            }
        } else {
            lines.push(RenderLine {
                color: None,
                text: "No active symbols yet.".to_string(),
            });
        }
    }

    lines.push(RenderLine {
        color: None,
        text: String::new(),
    });
    lines.push(RenderLine {
        color: Some(Color::Blue),
        text: "ALERTS".to_string(),
    });

    for alert in state.alerts_iter().rev().take(MAX_VISIBLE_ALERTS) {
        lines.push(RenderLine {
            color: Some(alert_kind_color(alert.kind)),
            text: format!(
                "{: <6} kind={} seq={}",
                alert.symbol,
                alert_label(alert.kind),
                alert.sequence,
            ),
        });
    }

    lines.push(RenderLine {
        color: None,
        text: String::new(),
    });
    lines.push(RenderLine {
        color: Some(Color::Magenta),
        text: "RECENT TAPE".to_string(),
    });

    for tape_event in state.recent_tape_iter().rev().take(MAX_VISIBLE_TAPE) {
        lines.push(RenderLine {
            color: Some(confidence_color(tape_event.confidence)),
            text: format!(
                "{: <6} upside={} qualified={} confidence={}",
                tape_event.symbol,
                format_upside_percent_from_gap_bps(tape_event.gap_bps),
                if tape_event.is_qualified { "yes" } else { "no" },
                confidence_label(tape_event.confidence),
            ),
        });
    }

    lines
}

fn opportunity_window_bounds(
    total_rows: usize,
    selected_index: usize,
    visible_capacity: usize,
) -> (usize, usize) {
    if total_rows == 0 {
        return (0, 0);
    }

    let visible_capacity = visible_capacity.max(1).min(total_rows);
    let max_start = total_rows.saturating_sub(visible_capacity);
    let start = selected_index
        .saturating_sub(visible_capacity.saturating_sub(1))
        .min(max_start);
    let end = (start + visible_capacity).min(total_rows);
    (start, end)
}

fn build_opportunity_rows(state: &TerminalState, app: &AppState) -> Vec<OpportunityRow> {
    let mut rows = state
        .filtered_rows(state.symbol_count().max(1), &app.view_filter)
        .into_iter()
        .filter(|row| row.is_qualified)
        .filter_map(|row| build_opportunity_row(state, app, row))
        .collect::<Vec<_>>();
    rows.sort_by(|left, right| {
        right
            .composite_score
            .cmp(&left.composite_score)
            .then_with(|| right.coverage_count.cmp(&left.coverage_count))
            .then_with(|| {
                confidence_rank_value(right.confidence)
                    .partial_cmp(&confidence_rank_value(left.confidence))
                    .unwrap_or(Ordering::Equal)
            })
            .then_with(|| right.gap_bps.cmp(&left.gap_bps))
            .then_with(|| left.symbol.cmp(&right.symbol))
    });
    rows
}

fn build_opportunity_row(
    state: &TerminalState,
    app: &AppState,
    candidate: CandidateRow,
) -> Option<OpportunityRow> {
    let detail = state.detail(&candidate.symbol)?;
    let score = score_opportunity_with_model(
        app,
        &detail,
        preferred_opportunity_chart_summary(app, &detail.symbol),
        app.opportunity_scoring_model,
    );

    Some(OpportunityRow {
        symbol: detail.symbol,
        market_price_cents: detail.market_price_cents,
        intrinsic_value_cents: detail.intrinsic_value_cents,
        gap_bps: detail.gap_bps,
        confidence: detail.confidence,
        is_watched: detail.is_watched,
        fundamentals_score: score.fundamentals_score,
        technical_score: score.technical_score,
        forecast_score: score.forecast_score,
        composite_score: score.composite_score,
        coverage_count: score.coverage_count,
        fundamentals_signals: score.fundamentals_signals,
        technical_signals: score.technical_signals,
        forecast_signals: score.forecast_signals,
    })
}

fn score_opportunity_with_model(
    app: &AppState,
    detail: &SymbolDetail,
    summary: Option<&ChartRangeSummary>,
    model: OpportunityScoringModel,
) -> OpportunityScoreBreakdown {
    let (fundamentals_score, fundamentals_signals) = match model {
        OpportunityScoringModel::Legacy => score_opportunity_fundamentals(detail),
        OpportunityScoringModel::Aggressive => aggressive_fundamentals_score(detail),
    };
    let (technical_score, technical_signals) = match model {
        OpportunityScoringModel::Legacy => score_opportunity_technicals(summary),
        OpportunityScoringModel::Aggressive => aggressive_technical_score(summary),
    };
    let (forecast_score, forecast_signals) = match model {
        OpportunityScoringModel::Legacy => score_opportunity_forecasts(app, detail),
        OpportunityScoringModel::Aggressive => aggressive_forecast_score(app, detail),
    };
    let coverage_count = [fundamentals_score, technical_score, forecast_score]
        .into_iter()
        .filter(Option::is_some)
        .count();

    OpportunityScoreBreakdown {
        fundamentals_score,
        technical_score,
        forecast_score,
        composite_score: fundamentals_score.unwrap_or(0)
            + technical_score.unwrap_or(0)
            + forecast_score.unwrap_or(0),
        coverage_count,
        fundamentals_signals,
        technical_signals,
        forecast_signals,
    }
}

fn score_opportunity_fundamentals(detail: &SymbolDetail) -> (Option<i32>, Vec<&'static str>) {
    let Some(fundamentals) = detail.fundamentals.as_ref() else {
        return (None, Vec::new());
    };

    let mut score = 0;
    let mut signals = Vec::new();

    if fundamentals.free_cash_flow_dollars.unwrap_or(0) > 0 {
        score += 1;
        signals.push("FCF+");
    }
    if fundamentals.operating_cash_flow_dollars.unwrap_or(0) > 0 {
        score += 1;
        signals.push("OCF+");
    }
    if fundamentals.return_on_equity_bps.unwrap_or(i32::MIN) >= 1_000 {
        score += 1;
        signals.push("ROE>10");
    }

    let balance_ok = fundamentals
        .debt_to_equity_hundredths
        .map(|value| value <= 100)
        .unwrap_or(false)
        || matches!(
            (
                fundamentals.total_cash_dollars,
                fundamentals.total_debt_dollars,
            ),
            (Some(total_cash_dollars), Some(total_debt_dollars)) if total_cash_dollars >= total_debt_dollars
        );
    if balance_ok {
        score += 1;
        signals.push("Balance");
    }

    if fundamentals.earnings_growth_bps.unwrap_or(0) > 0 {
        score += 1;
        signals.push("Growth+");
    }

    (Some(score), signals)
}

fn preferred_opportunity_chart_summary<'a>(
    app: &'a AppState,
    symbol: &str,
) -> Option<&'a ChartRangeSummary> {
    app.chart_summary(symbol, ChartRange::Year).or_else(|| {
        chart_ranges()
            .iter()
            .filter_map(|range| app.chart_summary(symbol, *range))
            .max_by_key(|summary| summary.candle_count)
    })
}

fn score_opportunity_technicals(
    summary: Option<&ChartRangeSummary>,
) -> (Option<i32>, Vec<&'static str>) {
    let Some(summary) = summary else {
        return (None, Vec::new());
    };
    let Some(latest_close_cents) = summary.latest_close_cents else {
        return (Some(0), Vec::new());
    };

    let mut score = 0;
    let mut signals = Vec::new();

    if summary
        .ema20_cents
        .is_some_and(|ema20_cents| latest_close_cents > ema20_cents)
    {
        score += 1;
        signals.push(">EMA20");
    }
    if summary
        .ema50_cents
        .is_some_and(|ema50_cents| latest_close_cents > ema50_cents)
    {
        score += 1;
        signals.push(">EMA50");
    }
    if summary
        .ema200_cents
        .is_some_and(|ema200_cents| latest_close_cents > ema200_cents)
    {
        score += 1;
        signals.push(">EMA200");
    }
    if matches!(
        (summary.ema20_cents, summary.ema50_cents),
        (Some(ema20_cents), Some(ema50_cents)) if ema20_cents > ema50_cents
    ) {
        score += 1;
        signals.push("EMA20>50");
    }
    if matches!(
        (summary.macd_cents, summary.signal_cents),
        (Some(macd_cents), Some(signal_cents)) if macd_cents > signal_cents
    ) || summary
        .histogram_cents
        .is_some_and(|histogram_cents| histogram_cents > 0)
    {
        score += 1;
        signals.push("MACD+");
    }

    (Some(score), signals)
}

fn score_opportunity_forecasts(
    app: &AppState,
    detail: &SymbolDetail,
) -> (Option<i32>, Vec<&'static str>) {
    let mut available = false;
    let mut score = 0;
    let mut signals = Vec::new();

    if detail.external_status == ExternalSignalStatus::Supportive {
        available = true;
        score += 1;
        signals.push("Supportive");
    }
    if detail.analyst_opinion_count.unwrap_or(0) >= 5 {
        available = true;
        score += 1;
        signals.push("5+Analysts");
    }
    if detail
        .recommendation_mean_hundredths
        .is_some_and(|recommendation_mean_hundredths| recommendation_mean_hundredths <= 200)
    {
        available = true;
        score += 1;
        signals.push("Rec<=2.0");
    }
    if let Some(weighted_external_signal_fair_value_cents) =
        detail.weighted_external_signal_fair_value_cents
    {
        available = true;
        if checked_upside_bps(
            detail.market_price_cents,
            weighted_external_signal_fair_value_cents,
        )
        .unwrap_or(0)
            >= 3_000
        {
            score += 1;
            signals.push("Weighted+");
        }
    }

    if let Some(AnalysisCacheEntry::Ready { analysis, .. }) =
        app.detail_analysis_entry(&detail.symbol)
    {
        available = true;
        match dcf_signal(analysis, detail.market_price_cents) {
            DcfSignal::Opportunity => {
                score += 1;
                signals.push("DCF+");
            }
            DcfSignal::Fair => {}
            DcfSignal::Expensive => {
                score -= 1;
                signals.push("DCF-");
            }
        }
    }

    if available {
        (Some(score), signals)
    } else {
        (None, Vec::new())
    }
}

fn aggressive_fundamentals_score(detail: &SymbolDetail) -> (Option<i32>, Vec<&'static str>) {
    let Some(fundamentals) = detail.fundamentals.as_ref() else {
        return (None, Vec::new());
    };

    let mut score = 0;
    let mut signals = Vec::new();

    if fundamentals.free_cash_flow_dollars.unwrap_or(0) > 0 {
        score += 2;
        signals.push("FCF+2");
    } else {
        score -= 2;
        signals.push("FCF-2");
    }
    if fundamentals.operating_cash_flow_dollars.unwrap_or(0) > 0 {
        score += 1;
        signals.push("OCF+1");
    } else {
        score -= 1;
        signals.push("OCF-1");
    }
    let roe_bps = fundamentals.return_on_equity_bps.unwrap_or(0);
    if roe_bps >= 2_000 {
        score += 2;
        signals.push("ROE20+");
    } else if roe_bps >= 1_000 {
        score += 1;
        signals.push("ROE10+");
    } else if roe_bps < 0 {
        score -= 2;
        signals.push("ROE-");
    }

    let balance_ok = fundamentals
        .debt_to_equity_hundredths
        .map(|value| value <= 100)
        .unwrap_or(false)
        || matches!(
            (
                fundamentals.total_cash_dollars,
                fundamentals.total_debt_dollars,
            ),
            (Some(total_cash_dollars), Some(total_debt_dollars)) if total_cash_dollars >= total_debt_dollars
        );
    if balance_ok {
        score += 2;
        signals.push("Balance+2");
    } else {
        score -= 2;
        signals.push("Balance-2");
    }

    let growth_bps = fundamentals.earnings_growth_bps.unwrap_or(0);
    if growth_bps >= 1_000 {
        score += 2;
        signals.push("Growth10+");
    } else if growth_bps > 0 {
        score += 1;
        signals.push("Growth+");
    } else if growth_bps < 0 {
        score -= 2;
        signals.push("Growth-");
    }

    (Some(score), signals)
}

fn aggressive_technical_score(
    summary: Option<&ChartRangeSummary>,
) -> (Option<i32>, Vec<&'static str>) {
    let Some(summary) = summary else {
        return (None, Vec::new());
    };
    let Some(latest_close_cents) = summary.latest_close_cents else {
        return (Some(0), Vec::new());
    };

    let mut score = 0;
    let mut signals = Vec::new();

    if summary
        .ema20_cents
        .is_some_and(|ema20_cents| latest_close_cents > ema20_cents)
    {
        score += 2;
        signals.push(">EMA20+2");
    } else if summary.ema20_cents.is_some() {
        score -= 2;
        signals.push("<EMA20-2");
    }
    if summary
        .ema50_cents
        .is_some_and(|ema50_cents| latest_close_cents > ema50_cents)
    {
        score += 1;
        signals.push(">EMA50+1");
    }
    if summary
        .ema200_cents
        .is_some_and(|ema200_cents| latest_close_cents > ema200_cents)
    {
        score += 1;
        signals.push(">EMA200+1");
    }
    if matches!(
        (summary.ema20_cents, summary.ema50_cents),
        (Some(ema20_cents), Some(ema50_cents)) if ema20_cents > ema50_cents
    ) {
        score += 1;
        signals.push("EMA20>50");
    }
    if matches!(
        (summary.macd_cents, summary.signal_cents),
        (Some(macd_cents), Some(signal_cents)) if macd_cents > signal_cents
    ) || summary
        .histogram_cents
        .is_some_and(|histogram_cents| histogram_cents > 0)
    {
        score += 1;
        signals.push("MACD+");
    } else if summary.histogram_cents.is_some() || summary.macd_cents.is_some() {
        score -= 2;
        signals.push("MACD-");
    }

    (Some(score), signals)
}

fn aggressive_forecast_score(
    app: &AppState,
    detail: &SymbolDetail,
) -> (Option<i32>, Vec<&'static str>) {
    let mut available = false;
    let mut score = 0;
    let mut signals = Vec::new();

    match detail.external_status {
        ExternalSignalStatus::Supportive => {
            available = true;
            score += 2;
            signals.push("Support+2");
        }
        ExternalSignalStatus::Divergent => {
            available = true;
            score -= 2;
            signals.push("Divergent-2");
        }
        ExternalSignalStatus::Stale | ExternalSignalStatus::Missing => {}
    }

    let analyst_count = detail.analyst_opinion_count.unwrap_or(0);
    if analyst_count >= 10 {
        available = true;
        score += 2;
        signals.push("Analysts10+");
    } else if analyst_count >= 5 {
        available = true;
        score += 1;
        signals.push("Analysts5+");
    }

    if let Some(recommendation_mean_hundredths) = detail.recommendation_mean_hundredths {
        available = true;
        if recommendation_mean_hundredths <= 170 {
            score += 2;
            signals.push("Rec1.7+");
        } else if recommendation_mean_hundredths <= 220 {
            score += 1;
            signals.push("Rec2.2+");
        } else if recommendation_mean_hundredths >= 300 {
            score -= 2;
            signals.push("Rec3.0-");
        }
    }

    if let Some(weighted_external_signal_fair_value_cents) =
        detail.weighted_external_signal_fair_value_cents
    {
        available = true;
        let upside_bps = checked_upside_bps(
            detail.market_price_cents,
            weighted_external_signal_fair_value_cents,
        )
        .unwrap_or(0);
        if upside_bps >= 5_000 {
            score += 3;
            signals.push("Weighted50+");
        } else if upside_bps >= 3_000 {
            score += 2;
            signals.push("Weighted30+");
        } else if upside_bps < 0 {
            score -= 2;
            signals.push("Weighted-");
        }
    }

    if let Some(AnalysisCacheEntry::Ready { analysis, .. }) =
        app.detail_analysis_entry(&detail.symbol)
    {
        available = true;
        let margin_bps = dcf_margin_of_safety_bps(analysis, detail.market_price_cents).unwrap_or(0);
        if margin_bps >= 4_000 {
            score += 4;
            signals.push("DCF40+");
        } else if margin_bps >= 2_000 {
            score += 2;
            signals.push("DCF20+");
        } else if margin_bps < -1_000 {
            score -= 3;
            signals.push("DCF-");
        }
    }

    if available {
        (Some(score), signals)
    } else {
        (None, Vec::new())
    }
}

fn opportunity_row_color(row: &OpportunityRow, is_selected: bool, is_stale: bool) -> Color {
    if is_selected {
        return if row.confidence == ConfidenceBand::High {
            Color::Cyan
        } else {
            Color::DarkCyan
        };
    }
    if is_stale {
        return Color::DarkGrey;
    }

    confidence_color(row.confidence)
}

fn format_opportunity_bucket(model: OpportunityScoringModel, score: Option<i32>) -> String {
    score
        .map(|value| match model {
            OpportunityScoringModel::Legacy => format!("{value}/5"),
            OpportunityScoringModel::Aggressive => value.to_string(),
        })
        .unwrap_or_else(|| "--".to_string())
}

fn build_opportunity_detail_lines(
    state: &TerminalState,
    app: &AppState,
    row: &OpportunityRow,
) -> Vec<RenderLine> {
    vec![
        RenderLine {
            color: Some(status_summary_color(
                QualificationStatus::Qualified,
                row.confidence,
            )),
            text: format!(
                "Symbol: {}  Watched: {}  Opportunity score: {}  Model: {}  Coverage: {}/3  Confidence: {}",
                format_symbol_with_company(&row.symbol, state.company_name(&row.symbol)),
                if row.is_watched { "yes" } else { "no" },
                row.composite_score,
                app.opportunity_scoring_model.label(),
                row.coverage_count,
                confidence_label(row.confidence),
            ),
        },
        RenderLine {
            color: Some(Color::DarkGrey),
            text: format!(
                "Price: {}  Fair value: {}  Upside: {}  Fundamentals {}  Technicals {}  Forecasts {}",
                format_money(row.market_price_cents),
                format_money(row.intrinsic_value_cents),
                format_upside_percent(row.market_price_cents, row.intrinsic_value_cents),
                format_opportunity_bucket(app.opportunity_scoring_model, row.fundamentals_score),
                format_opportunity_bucket(app.opportunity_scoring_model, row.technical_score),
                format_opportunity_bucket(app.opportunity_scoring_model, row.forecast_score),
            ),
        },
        RenderLine {
            color: Some(Color::Green),
            text: format!(
                "Fundamentals: {}",
                if row.fundamentals_signals.is_empty() {
                    "no supporting signals".to_string()
                } else {
                    row.fundamentals_signals.join(", ")
                }
            ),
        },
        RenderLine {
            color: Some(Color::Blue),
            text: format!(
                "Technicals: {}",
                if row.technical_signals.is_empty() {
                    "no technical confirmation yet".to_string()
                } else {
                    row.technical_signals.join(", ")
                }
            ),
        },
        RenderLine {
            color: Some(Color::Magenta),
            text: format!(
                "Forecasts: {}",
                if row.forecast_signals.is_empty() {
                    "no analyst or DCF confirmation yet".to_string()
                } else {
                    row.forecast_signals.join(", ")
                }
            ),
        },
    ]
}

fn build_issue_log_lines(app: &AppState) -> Vec<RenderLine> {
    let issues = app.issue_center.sorted_entries();
    let selected_index = app.issue_log_selected.min(issues.len().saturating_sub(1));
    let start_index = selected_index.saturating_sub(MAX_VISIBLE_ISSUES / 2);
    let end_index = (start_index + MAX_VISIBLE_ISSUES).min(issues.len());
    let mut lines = Vec::new();

    lines.push(RenderLine {
        color: Some(Color::Yellow),
        text:
            "ISSUE LOG  |  j/k move  |  c clear resolved  |  Backspace or l close  |  q quit  |  Ctrl+C quit"
                .to_string(),
    });
    lines.push(RenderLine {
        color: Some(health_status_color(app.issue_center.health_status())),
        text: format!(
            "Health: {}  Active: {}  Total: {}  Resolved: {}",
            health_status_label(app.issue_center.health_status()),
            app.issue_center.active_issue_count(),
            app.issue_center.issue_count(),
            app.issue_center.resolved_issue_count(),
        ),
    });
    lines.push(RenderLine {
        color: None,
        text: "Idx  State     Sev      Source       Count  Title".to_string(),
    });

    if issues.is_empty() {
        lines.push(RenderLine {
            color: Some(Color::DarkGrey),
            text: "No operational issues recorded in this session.".to_string(),
        });
        return lines;
    }

    for (offset, issue) in issues[start_index..end_index].iter().enumerate() {
        let issue_index = start_index + offset;
        let marker = if issue_index == selected_index {
            '>'
        } else {
            ' '
        };
        lines.push(RenderLine {
            color: Some(issue_severity_color(issue.severity)),
            text: format!(
                "{} {:>2}  {:<8} {:<8} {:<12} {:>5}  {}",
                marker,
                issue_index,
                if issue.active { "active" } else { "resolved" },
                issue_severity_label(issue.severity),
                issue_source_label(issue.source),
                issue.count,
                truncate_text(&issue.title, 48),
            ),
        });
    }

    let selected_issue = &issues[selected_index];
    lines.push(RenderLine {
        color: None,
        text: String::new(),
    });
    lines.push(RenderLine {
        color: Some(issue_severity_color(selected_issue.severity)),
        text: "DETAIL".to_string(),
    });
    lines.push(RenderLine {
        color: None,
        text: format!(
            "Title: {}  Source: {}  Severity: {}  State: {}",
            selected_issue.title,
            issue_source_label(selected_issue.source),
            issue_severity_label(selected_issue.severity),
            if selected_issue.active {
                "active"
            } else {
                "resolved"
            },
        ),
    });
    lines.push(RenderLine {
        color: None,
        text: format!(
            "Occurrences: {}  First seen: #{}  Last seen: #{}",
            selected_issue.count, selected_issue.first_seen_event, selected_issue.last_seen_event,
        ),
    });
    for detail_line in wrap_text(&selected_issue.detail, 108) {
        lines.push(RenderLine {
            color: None,
            text: format!("Detail: {detail_line}"),
        });
    }

    lines
}

#[cfg(test)]
fn build_ticker_detail_lines(
    state: &TerminalState,
    app: &AppState,
    symbol: &str,
) -> Vec<RenderLine> {
    build_ticker_detail_lines_for_viewport(
        state,
        app,
        symbol,
        DEFAULT_VIEWPORT_WIDTH,
        DEFAULT_VIEWPORT_HEIGHT,
    )
}

#[derive(Clone, Debug)]
struct HistoryMetricRow {
    label: String,
    latest: String,
    previous: String,
    delta: String,
    sparkline: String,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum HistoryUnit {
    Usd,
    Percent,
    Count,
    Ratio,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum ChartMetricKind {
    Close,
    Ema20,
    Ema50,
    Ema200,
    Macd,
    Signal,
    Histogram,
}

#[derive(Clone, Debug)]
struct HistoryMetricDef {
    key: &'static str,
    label: String,
    range_key: Option<&'static str>,
    unit: HistoryUnit,
    extractor: HistoryValueExtractor,
}

#[derive(Clone, Copy, Debug)]
enum HistoryValueExtractor {
    MarketPrice,
    IntrinsicValue,
    GapPct,
    ExternalGapPct,
    WeightedGapPct,
    AnalystCount,
    ConfidenceRank,
    QualificationRank,
    ExternalStatusRank,
    WatchedCount,
    ProfitableCount,
    MarketCapUsd,
    SharesOutstanding,
    TrailingPe,
    ForwardPe,
    PriceToBook,
    ReturnOnEquityPct,
    EbitdaUsd,
    EnterpriseValueUsd,
    EnterpriseToEbitda,
    TotalDebtUsd,
    TotalCashUsd,
    DebtToEquity,
    FreeCashFlowUsd,
    OperatingCashFlowUsd,
    Beta,
    TrailingEpsUsd,
    EarningsGrowthPct,
    RelativeCompositePercentile,
    RelativePeerCount,
    RelativeMetricPercentile(&'static str),
    DcfBearIntrinsicUsd,
    DcfBaseIntrinsicUsd,
    DcfBullIntrinsicUsd,
    DcfWaccPct,
    DcfBaseGrowthPct,
    DcfNetDebtUsd,
    DcfMarginSafetyPct,
    DcfSignalRank,
    ChartMetric(ChartRange, ChartMetricKind),
}

#[derive(Clone, Debug)]
struct HistorySeriesPoint {
    evaluated_at: u64,
    revision_id: i64,
    value: f64,
    available: bool,
    stale: bool,
}

#[derive(Clone, Debug)]
struct HistorySeries {
    group: HistoryMetricGroup,
    metric_key: &'static str,
    label: String,
    range_key: Option<&'static str>,
    unit: HistoryUnit,
    points: Vec<HistorySeriesPoint>,
}

#[derive(Clone, Debug)]
struct HistoryGraphTile {
    label: String,
    latest: String,
    previous: String,
    delta: String,
    points: Vec<f64>,
    min_label: String,
    max_label: String,
    footer_lines: Vec<String>,
}

#[derive(Clone, Debug)]
struct CsvExportMetadata {
    symbol: String,
    exported_at: u64,
    revision_count: usize,
    export_dir: PathBuf,
}

fn build_ticker_history_lines_for_viewport(
    app: &AppState,
    symbol: &str,
    viewport_width: usize,
    viewport_height: usize,
) -> Vec<RenderLine> {
    let history = app.detail_history(symbol);
    let mut lines = Vec::new();
    lines.push(RenderLine {
        color: Some(Color::Yellow),
        text: format!(
            "{}  |  History tab  |  view={}  |  group={}  |  window={}  |  h snapshot  g toggle  e export  1-5 group  [/ ] window  j/k nav  n/p symbol",
            symbol,
            history_subview_label(app.history_view.subview),
            history_group_label(app.history_view.group),
            history_window_label(app.history_view.window),
        ),
    });
    lines.push(RenderLine {
        color: Some(Color::DarkGrey),
        text: format!(
            "Canonical revisions loaded: {}  |  viewport {}x{}",
            history.len(),
            viewport_width,
            viewport_height,
        ),
    });

    lines.extend(match app.history_view.subview {
        HistorySubview::Graphs => build_ticker_history_graph_lines(
            history,
            app.history_view.group,
            app.history_view.window,
            app.history_view.scroll,
            viewport_width,
            viewport_height.saturating_sub(lines.len()),
        ),
        HistorySubview::Table => build_ticker_history_table_lines(
            history,
            app.history_view.group,
            app.history_view.window,
            app.history_view.scroll,
            viewport_height.saturating_sub(lines.len()),
        ),
    });
    lines
}

fn build_ticker_history_graph_lines(
    history: &[persistence::PersistedRevisionRecord],
    group: HistoryMetricGroup,
    window: HistoryWindow,
    scroll: usize,
    viewport_width: usize,
    viewport_height: usize,
) -> Vec<RenderLine> {
    let filtered = filter_history_window(history, window);
    let tiles = history_graph_tiles(&filtered, group);
    if tiles.is_empty() {
        return vec![RenderLine {
            color: Some(Color::DarkGrey),
            text: "No graph tiles are available for the selected group and window yet.".to_string(),
        }];
    }

    let columns = if viewport_width >= 120 && viewport_height >= 18 {
        2
    } else {
        1
    };
    let tile_height = 9usize;
    let gap = 3usize;
    let page_rows = (viewport_height.max(tile_height) / tile_height).max(1);
    let total_tile_rows = tiles.len().div_ceil(columns);
    let start_row = scroll.min(total_tile_rows.saturating_sub(page_rows));
    let visible_start = start_row * columns;
    let visible_end = ((start_row + page_rows) * columns).min(tiles.len());
    let tile_width = if columns == 1 {
        viewport_width.max(40)
    } else {
        viewport_width
            .saturating_sub(gap * (columns - 1))
            .checked_div(columns)
            .unwrap_or(viewport_width.max(40))
            .max(40)
    };

    let mut lines = vec![RenderLine {
        color: Some(Color::DarkGrey),
        text: format!(
            "Graphs {}-{}/{}  |  layout={}col  |  j/k page tile rows",
            visible_start.saturating_add(1).min(tiles.len()),
            visible_end,
            tiles.len(),
            columns
        ),
    }];

    let mut rendered_tiles = tiles[visible_start..visible_end]
        .iter()
        .map(|tile| render_history_graph_tile(tile, tile_width))
        .collect::<Vec<_>>();
    while rendered_tiles.len() % columns != 0 {
        rendered_tiles.push(blank_tile_lines(tile_width, tile_height));
    }

    for chunk in rendered_tiles.chunks(columns) {
        for line_index in 0..tile_height {
            lines.push(RenderLine {
                color: Some(Color::DarkGrey),
                text: chunk
                    .iter()
                    .map(|tile| tile.get(line_index).cloned().unwrap_or_default())
                    .collect::<Vec<_>>()
                    .join("   "),
            });
        }
    }

    lines
}

fn build_ticker_history_table_lines(
    history: &[persistence::PersistedRevisionRecord],
    group: HistoryMetricGroup,
    window: HistoryWindow,
    scroll: usize,
    viewport_height: usize,
) -> Vec<RenderLine> {
    let rows = history_rows(history, group, window);
    let visible_capacity = viewport_height.saturating_sub(2).max(1);
    let start = scroll.min(rows.len().saturating_sub(visible_capacity));
    let end = (start + visible_capacity).min(rows.len());

    let mut lines = Vec::new();
    if rows.is_empty() {
        lines.push(RenderLine {
            color: Some(Color::DarkGrey),
            text: "No history rows are available for the selected group and window yet."
                .to_string(),
        });
        return lines;
    }

    lines.push(RenderLine {
        color: Some(Color::DarkGrey),
        text: format!(
            "Table rows {}-{}/{}  |  Metric                          Latest            Prev              Delta             Trend",
            start.saturating_add(1).min(rows.len()),
            end,
            rows.len(),
        ),
    });
    for row in &rows[start..end] {
        lines.push(RenderLine {
            color: Some(Color::DarkGrey),
            text: format!(
                "{:<30} {:<17} {:<17} {:<17} {}",
                clip_plain_text(&row.label, 30),
                clip_plain_text(&row.latest, 17),
                clip_plain_text(&row.previous, 17),
                clip_plain_text(&row.delta, 17),
                row.sparkline
            ),
        });
    }

    lines
}

fn build_ticker_detail_lines_for_viewport(
    state: &TerminalState,
    app: &AppState,
    symbol: &str,
    viewport_width: usize,
    viewport_height: usize,
) -> Vec<RenderLine> {
    let detail_symbols = app.active_detail_symbols(state);
    let symbol_index = detail_symbols
        .iter()
        .position(|candidate_symbol| candidate_symbol == symbol)
        .map(|index| index + 1)
        .unwrap_or(1);
    let symbol_count = detail_symbols.len().max(1);
    let layout = detail_layout(viewport_width, viewport_height);
    let mut lines = Vec::with_capacity(viewport_height);

    lines.push(RenderLine {
        color: Some(Color::Yellow),
        text: "TICKER DETAIL  |  j/k next ticker  |  1-6 range  |  [/] cycle  |  \u{2190}/\u{2192} replay  |  w watch  |  l logs  |  Backspace or d or Enter close  |  q quit  |  Ctrl+C quit".to_string(),
    });

    let Some(detail) = state.detail(symbol) else {
        if let Some(coverage) = app.symbol_coverage(symbol) {
            lines.push(RenderLine {
                color: Some(if symbol_coverage_has_error(coverage) {
                    Color::Red
                } else {
                    Color::Yellow
                }),
                text: format!(
                    "{}  Position: {}/{}  Watched: {}  Status unavailable",
                    format_symbol_with_company(symbol, state.company_name(symbol)),
                    symbol_index,
                    symbol_count,
                    if state.is_watched(symbol) {
                        "yes"
                    } else {
                        "no"
                    },
                ),
            });
            lines.push(RenderLine {
                color: Some(Color::DarkGrey),
                text: format!(
                    "Coverage: core={}  external={}  fundamentals={}",
                    provider_component_state_label(coverage.coverage.core),
                    provider_component_state_label(coverage.coverage.external),
                    provider_component_state_label(coverage.coverage.fundamentals),
                ),
            });
            for detail_line in wrap_text(
                &format!(
                    "Provider diagnostics: {}",
                    format_symbol_coverage_summary(coverage)
                ),
                108,
            ) {
                lines.push(RenderLine {
                    color: Some(Color::DarkYellow),
                    text: detail_line,
                });
            }
            lines.push(RenderLine {
                color: Some(Color::DarkGrey),
                text: "No price snapshot was published for this ticker, so valuation and chart sections are unavailable."
                    .to_string(),
            });
            lines.push(RenderLine {
                color: Some(Color::DarkGrey),
                text: "Use j/k to inspect other tracked symbols or close the detail screen with Backspace."
                    .to_string(),
            });
            return lines;
        }

        lines.push(RenderLine {
            color: Some(Color::Red),
            text: format!("{symbol} is not active in the current session."),
        });
        lines.push(RenderLine {
            color: Some(Color::DarkGrey),
            text: "Close the detail screen with Backspace or select another ticker from the main table."
                .to_string(),
        });
        return lines;
    };

    let discount_cents = detail.intrinsic_value_cents - detail.market_price_cents;
    let actual_upside_bps =
        checked_upside_bps(detail.market_price_cents, detail.intrinsic_value_cents).unwrap_or(0);
    let minimum_upside_bps = upside_bps_from_gap_bps(detail.minimum_gap_bps).unwrap_or(0);
    let weighted_upside_bps =
        detail
            .weighted_external_signal_fair_value_cents
            .and_then(|weighted_target_cents| {
                checked_upside_bps(detail.market_price_cents, weighted_target_cents)
            });
    let chart_snapshot = detail_chart_snapshot(app, symbol);
    let analysis_snapshot = detail_analysis_snapshot(app, &detail);
    let relative_score = detail
        .fundamentals
        .as_ref()
        .and_then(|fundamentals| compute_sector_relative_score(state, fundamentals));
    let visible_end = app.visible_candle_end(chart_snapshot.candles.len());
    let visible_candles = &chart_snapshot.candles[..visible_end];
    let aggregated_candles = aggregate_historical_candles(visible_candles, layout.candle_slots);

    lines.push(RenderLine {
        color: Some(if app.is_symbol_stale(symbol) {
            Color::DarkGrey
        } else {
            Color::Cyan
        }),
        text: format!(
            "{}  Position: {}/{}  Watched: {}  Chart {}",
            format_symbol_with_company(&detail.symbol, state.company_name(&detail.symbol)),
            symbol_index,
            symbol_count,
            if detail.is_watched { "yes" } else { "no" },
            chart_range_label(app.detail_chart_range()),
        ),
    });
    lines.push(RenderLine {
        color: Some(status_summary_color(
            detail.qualification,
            detail.confidence,
        )),
        text: format!(
            "Price {}  Mean {} ({})  Median {} ({})  Weighted {}",
            format_money(detail.market_price_cents),
            format_money(detail.intrinsic_value_cents),
            format_upside_percent(detail.market_price_cents, detail.intrinsic_value_cents),
            format_optional_money(detail.external_signal_fair_value_cents),
            detail
                .external_signal_fair_value_cents
                .map(|fair_value_cents| format_upside_percent(
                    detail.market_price_cents,
                    fair_value_cents
                ))
                .unwrap_or_else(|| "n/a".to_string()),
            detail
                .weighted_external_signal_fair_value_cents
                .map(|weighted_target_cents| {
                    format!(
                        "{} ({})",
                        format_money(weighted_target_cents),
                        format_upside_percent(detail.market_price_cents, weighted_target_cents)
                    )
                })
                .unwrap_or_else(|| "n/a".to_string()),
        ),
    });
    lines.push(RenderLine {
        color: Some(external_status_color(detail.external_status)),
        text: format!(
            "Qualification {}  Confidence {}  External {}  Threshold {}  Discount {}",
            qualification_label(detail.qualification),
            confidence_label(detail.confidence),
            external_status_label(detail.external_status),
            format_upside_percent_from_gap_bps(detail.minimum_gap_bps),
            format_money(discount_cents),
        ),
    });
    lines.push(RenderLine {
        color: Some(Color::DarkGrey),
        text: format!(
            "Updates {}  Sequence #{}  Filter query='{}' watchlist_only={}",
            detail.update_count,
            detail.last_sequence,
            app.view_filter.query,
            if app.view_filter.watchlist_only {
                "on"
            } else {
                "off"
            },
        ),
    });
    if app.is_symbol_stale(symbol) {
        lines.push(RenderLine {
            color: Some(Color::DarkYellow),
            text: "Data source: warm-start cache from SQLite. Live Yahoo refresh has not replaced this symbol yet.".to_string(),
        });
    }
    if let Some(coverage) = app.symbol_coverage(symbol) {
        let summary = format_symbol_coverage_summary(coverage);
        if !summary.is_empty() {
            lines.push(RenderLine {
                color: Some(Color::DarkYellow),
                text: format!("Live coverage: {summary}"),
            });
        }
    }
    lines.push(RenderLine {
        color: None,
        text: String::new(),
    });
    lines.push(RenderLine {
        color: Some(Color::Yellow),
        text: format!(
            "PRICE CHART  |  {}  |  {} candle(s){}  |  {}  |  ←/→ replay",
            chart_range_label(app.detail_chart_range()),
            visible_candles.len(),
            if app.replay_offset > 0 {
                format!(" / {}", chart_snapshot.candles.len())
            } else {
                String::new()
            },
            chart_snapshot.status
        ),
    });
    if aggregated_candles.is_empty() {
        lines.push(RenderLine {
            color: Some(chart_snapshot.color),
            text: chart_snapshot.note.unwrap_or_else(|| {
                "Yahoo chart returned no OHLC candles for this range.".to_string()
            }),
        });
    } else {
        lines.extend(build_chart_stack_lines(
            &aggregated_candles,
            visible_candles,
            &layout,
        ));
        lines.push(RenderLine {
            color: Some(Color::DarkGrey),
            text: format!(
                "Showing {} / {} candles  |  ~{} source candle(s) per slot  |  Visible price range {} to {}  |  Volume max {}",
                aggregated_candles.len(),
                visible_candles.len(),
                chart_bucket_size(visible_candles.len(), aggregated_candles.len()),
                format_money(
                    aggregated_candles
                        .iter()
                        .map(|candle| candle.low_cents)
                        .min()
                        .unwrap_or(detail.market_price_cents)
                ),
                format_money(
                    aggregated_candles
                        .iter()
                        .map(|candle| candle.high_cents)
                        .max()
                        .unwrap_or(detail.market_price_cents)
                ),
                format_compact_quantity(
                    aggregated_candles
                        .iter()
                        .map(|candle| candle.volume)
                        .max()
                        .unwrap_or(0)
                ),
            ),
        });
        if !layout.show_ema_200 || !layout.show_macd {
            let mut reductions = Vec::new();
            if !layout.show_ema_200 {
                reductions.push("EMA200 hidden");
            }
            if !layout.show_macd {
                reductions.push("MACD hidden");
            }
            lines.push(RenderLine {
                color: Some(Color::DarkGrey),
                text: format!("Viewport reduction: {}", reductions.join("  |  ")),
            });
        }
        if let Some(note) = chart_snapshot.note {
            lines.push(RenderLine {
                color: Some(chart_snapshot.color),
                text: note,
            });
        }
    }
    lines.push(RenderLine {
        color: None,
        text: String::new(),
    });
    lines.push(RenderLine {
        color: Some(Color::Yellow),
        text: "VALUATION MAP".to_string(),
    });
    lines.push(RenderLine {
        color: Some(gap_color(actual_upside_bps, minimum_upside_bps)),
        text: format!(
            "Mean upside {} vs threshold {}  {}",
            format_upside_percent(detail.market_price_cents, detail.intrinsic_value_cents),
            format_upside_percent_from_gap_bps(detail.minimum_gap_bps),
            gap_meter(actual_upside_bps, minimum_upside_bps, GAP_METER_WIDTH),
        ),
    });
    if let Some(weighted_upside_bps) = weighted_upside_bps {
        lines.push(RenderLine {
            color: Some(gap_color(weighted_upside_bps, minimum_upside_bps)),
            text: format!(
                "Weighted upside {} from {} scored firms",
                format_bps(weighted_upside_bps),
                format_optional_count(detail.weighted_analyst_count),
            ),
        });
    }
    if let Some(target_range_line) = target_range_line(&detail) {
        lines.push(RenderLine {
            color: Some(Color::DarkCyan),
            text: target_range_line,
        });
    }
    if let Some(target_marker_legend_line) = target_marker_legend_line(&detail) {
        lines.push(RenderLine {
            color: Some(Color::DarkGrey),
            text: target_marker_legend_line,
        });
    }
    lines.push(RenderLine {
        color: Some(Color::DarkGrey),
        text: format!(
            "Range width {}  Analysts {}  Recommendation mean {}",
            detail
                .external_signal_high_fair_value_cents
                .zip(detail.external_signal_low_fair_value_cents)
                .map(|(high, low)| format_money(high.saturating_sub(low)))
                .unwrap_or_else(|| "n/a".to_string()),
            format_optional_count(detail.analyst_opinion_count),
            detail
                .recommendation_mean_hundredths
                .map(format_recommendation_mean)
                .unwrap_or_else(|| "n/a".to_string()),
        ),
    });
    lines.push(RenderLine {
        color: None,
        text: String::new(),
    });
    lines.push(RenderLine {
        color: Some(Color::Yellow),
        text: "FUNDAMENTALS".to_string(),
    });
    for line in build_fundamentals_lines(
        &detail,
        &analysis_snapshot,
        relative_score.as_ref(),
        layout.compact_fundamentals,
    ) {
        lines.push(line);
    }
    lines.push(RenderLine {
        color: None,
        text: String::new(),
    });
    lines.push(RenderLine {
        color: Some(Color::Yellow),
        text: "CONSENSUS".to_string(),
    });
    if layout.compact_consensus {
        lines.push(RenderLine {
            color: Some(Color::DarkGrey),
            text: compact_consensus_line(&detail),
        });
    } else {
        lines.extend(build_consensus_graph_lines(&detail));
    }
    lines.push(RenderLine {
        color: None,
        text: String::new(),
    });
    lines.push(RenderLine {
        color: Some(Color::Yellow),
        text: "EVIDENCE".to_string(),
    });
    let evidence_lines = if layout.compact_evidence {
        compact_evidence_lines(&detail)
            .into_iter()
            .take(2)
            .collect::<Vec<_>>()
    } else {
        compact_evidence_lines(&detail)
    };
    for evidence_line in evidence_lines {
        lines.push(RenderLine {
            color: Some(evidence_line_color(&detail, &evidence_line)),
            text: evidence_line,
        });
    }
    if layout.show_recent_context {
        lines.push(RenderLine {
            color: None,
            text: String::new(),
        });
        lines.push(RenderLine {
            color: Some(Color::Yellow),
            text: "RECENT CONTEXT".to_string(),
        });
        lines.push(RenderLine {
            color: Some(Color::Magenta),
            text: summarize_recent_alerts(state.alerts_for_symbol(symbol)),
        });
        lines.push(RenderLine {
            color: Some(Color::Cyan),
            text: summarize_recent_tape(state.recent_tape_for_symbol(symbol)),
        });
    }

    lines
}

struct DetailLayout {
    plot_width: usize,
    candle_slots: usize,
    price_chart_height: usize,
    volume_chart_height: usize,
    macd_chart_height: usize,
    compact_volume: bool,
    show_macd: bool,
    show_ema_200: bool,
    show_overlay_legend: bool,
    show_macd_legend: bool,
    show_recent_context: bool,
    compact_fundamentals: bool,
    compact_consensus: bool,
    compact_evidence: bool,
    show_volume_profile: bool,
}

struct DetailChartSnapshot<'a> {
    candles: &'a [HistoricalCandle],
    status: String,
    note: Option<String>,
    color: Color,
}

fn detail_layout(viewport_width: usize, viewport_height: usize) -> DetailLayout {
    let show_volume_profile = viewport_width >= 100;
    let profile_width = if show_volume_profile {
        DETAIL_VOLUME_PROFILE_WIDTH
    } else {
        0
    };
    let plot_width = viewport_width
        .saturating_sub(DETAIL_CHART_AXIS_WIDTH + DETAIL_CHART_ROW_PADDING + profile_width)
        .max(DETAIL_MIN_VISIBLE_CANDLES);
    let candle_slots = plot_width;
    let mut show_recent_context = viewport_height >= 30 && viewport_width >= 88;
    let mut compact_fundamentals = viewport_height < 36 || viewport_width < 104;
    let mut compact_consensus = viewport_height < 34 || viewport_width < 96;
    let mut compact_evidence = viewport_height < 28 || viewport_width < 90;
    let preferred_chart_stack_height = (viewport_height.saturating_mul(11) / 20)
        .max(DETAIL_MIN_CHART_HEIGHT + DETAIL_MIN_VOLUME_HEIGHT + DETAIL_MIN_MACD_HEIGHT);

    let mut non_chart_lines = 22usize
        + if show_recent_context { 4 } else { 0 }
        + if compact_fundamentals {
            2
        } else if viewport_height < 72 {
            9
        } else {
            5
        }
        + if compact_consensus { 1 } else { 6 }
        + if compact_evidence { 2 } else { 3 };

    if non_chart_lines + preferred_chart_stack_height > viewport_height && show_recent_context {
        show_recent_context = false;
        non_chart_lines = non_chart_lines.saturating_sub(4);
    }
    if non_chart_lines + preferred_chart_stack_height > viewport_height && !compact_fundamentals {
        compact_fundamentals = true;
        non_chart_lines = non_chart_lines.saturating_sub(3);
    }
    if non_chart_lines + preferred_chart_stack_height > viewport_height && !compact_evidence {
        compact_evidence = true;
        non_chart_lines = non_chart_lines.saturating_sub(1);
    }
    if non_chart_lines + preferred_chart_stack_height > viewport_height && !compact_consensus {
        compact_consensus = true;
        non_chart_lines = non_chart_lines.saturating_sub(5);
    }

    let chart_stack_height = viewport_height.saturating_sub(non_chart_lines).max(8);
    let (price_chart_height, volume_chart_height, macd_chart_height, compact_volume) =
        split_chart_stack_height(chart_stack_height);
    let show_macd = if compact_volume {
        viewport_height >= 18 && viewport_width >= 72 && macd_chart_height >= 3
    } else {
        macd_chart_height >= DETAIL_MIN_MACD_HEIGHT
    };
    let show_ema_200 = chart_stack_height >= 12 && viewport_width >= 76;
    let show_overlay_legend = viewport_width >= 88 && chart_stack_height >= 12;
    let show_macd_legend = show_macd && viewport_width >= 94 && chart_stack_height >= 13;

    DetailLayout {
        plot_width,
        candle_slots,
        price_chart_height,
        volume_chart_height,
        macd_chart_height,
        compact_volume,
        show_macd,
        show_ema_200,
        show_overlay_legend,
        show_macd_legend,
        show_recent_context,
        compact_fundamentals,
        compact_consensus,
        compact_evidence,
        show_volume_profile,
    }
}

fn split_chart_stack_height(total_height: usize) -> (usize, usize, usize, bool) {
    let minimum_full_stack_height =
        DETAIL_MIN_CHART_HEIGHT + DETAIL_MIN_VOLUME_HEIGHT + DETAIL_MIN_MACD_HEIGHT;
    if total_height >= minimum_full_stack_height {
        let mut price_height =
            ((total_height.saturating_mul(60) + 99) / 100).max(DETAIL_MIN_CHART_HEIGHT);
        let mut volume_height =
            ((total_height.saturating_mul(15) + 99) / 100).max(DETAIL_MIN_VOLUME_HEIGHT);
        let mut macd_height = total_height.saturating_sub(price_height + volume_height);

        if macd_height < DETAIL_MIN_MACD_HEIGHT {
            let deficit = DETAIL_MIN_MACD_HEIGHT - macd_height;
            let price_cut = price_height
                .saturating_sub(DETAIL_MIN_CHART_HEIGHT)
                .min(deficit);
            price_height = price_height.saturating_sub(price_cut);
            macd_height += price_cut;

            let remaining_deficit = DETAIL_MIN_MACD_HEIGHT.saturating_sub(macd_height);
            let volume_cut = volume_height
                .saturating_sub(DETAIL_MIN_VOLUME_HEIGHT)
                .min(remaining_deficit);
            volume_height = volume_height.saturating_sub(volume_cut);
            macd_height += volume_cut;
        }

        return (price_height, volume_height, macd_height, false);
    }

    let compact_volume_height = 1usize;
    let available_for_price_and_macd = total_height.saturating_sub(compact_volume_height).max(7);
    let macd_height = (available_for_price_and_macd / 3)
        .max(3)
        .min(available_for_price_and_macd.saturating_sub(4));
    let price_height = available_for_price_and_macd
        .saturating_sub(macd_height)
        .max(4);

    (price_height, compact_volume_height, macd_height, true)
}

fn detail_chart_snapshot<'a>(app: &'a AppState, symbol: &str) -> DetailChartSnapshot<'a> {
    match app.detail_chart_entry(symbol) {
        Some(ChartCacheEntry::Loading {
            previous: Some(candles),
            ..
        }) => DetailChartSnapshot {
            candles,
            status: "refreshing".to_string(),
            note: Some(
                "Refreshing Yahoo OHLC history. Showing cached candles meanwhile.".to_string(),
            ),
            color: Color::DarkYellow,
        },
        Some(ChartCacheEntry::Loading { previous: None, .. }) => DetailChartSnapshot {
            candles: &[],
            status: "loading".to_string(),
            note: Some("Loading Yahoo OHLC history for the selected range...".to_string()),
            color: Color::DarkGrey,
        },
        Some(ChartCacheEntry::Ready { candles }) => DetailChartSnapshot {
            candles,
            status: if app.is_chart_stale(symbol, app.detail_chart_range()) {
                format!("cached ({})", candles.len())
            } else {
                format!("ready ({})", candles.len())
            },
            note: app
                .is_chart_stale(symbol, app.detail_chart_range())
                .then_some(
                    "Showing persisted candles from SQLite while Yahoo refreshes this range."
                        .to_string(),
                ),
            color: if app.is_chart_stale(symbol, app.detail_chart_range()) {
                Color::DarkYellow
            } else {
                Color::DarkGrey
            },
        },
        Some(ChartCacheEntry::Failed {
            message,
            previous: Some(candles),
        }) => DetailChartSnapshot {
            candles,
            status: "stale".to_string(),
            note: Some(format!(
                "Yahoo history refresh failed: {message}. Showing cached candles."
            )),
            color: Color::DarkYellow,
        },
        Some(ChartCacheEntry::Failed {
            message,
            previous: None,
        }) => DetailChartSnapshot {
            candles: &[],
            status: "error".to_string(),
            note: Some(format!("Yahoo history unavailable: {message}")),
            color: Color::Red,
        },
        None => DetailChartSnapshot {
            candles: &[],
            status: "idle".to_string(),
            note: Some("Historical price chart has not been loaded yet.".to_string()),
            color: Color::DarkGrey,
        },
    }
}

fn detail_analysis_snapshot<'a>(
    app: &'a AppState,
    detail: &'a SymbolDetail,
) -> DetailAnalysisSnapshot<'a> {
    let Some(fundamentals) = detail.fundamentals.as_ref() else {
        return DetailAnalysisSnapshot {
            analysis: None,
            status: "unavailable".to_string(),
            note: Some(
                "Yahoo quote fundamentals are not available for this ticker yet.".to_string(),
            ),
            color: Color::DarkGrey,
        };
    };
    let analysis_input = analysis_input_key(fundamentals);

    match app.detail_analysis_entry(&detail.symbol) {
        Some(AnalysisCacheEntry::Loading { input, .. }) if *input == analysis_input => {
            DetailAnalysisSnapshot {
                analysis: None,
                status: "loading".to_string(),
                note: Some("Loading annual Yahoo cash flow history for DCF...".to_string()),
                color: Color::DarkGrey,
            }
        }
        Some(AnalysisCacheEntry::Ready { input, analysis }) if *input == analysis_input => {
            DetailAnalysisSnapshot {
                analysis: Some(analysis),
                status: "ready".to_string(),
                note: None,
                color: Color::DarkGrey,
            }
        }
        Some(AnalysisCacheEntry::Failed { input, message }) if *input == analysis_input => {
            DetailAnalysisSnapshot {
                analysis: None,
                status: "error".to_string(),
                note: Some(message.clone()),
                color: Color::DarkYellow,
            }
        }
        Some(_) | None => DetailAnalysisSnapshot {
            analysis: None,
            status: "idle".to_string(),
            note: Some("DCF analysis has not been requested yet.".to_string()),
            color: Color::DarkGrey,
        },
    }
}

fn build_fundamentals_lines(
    detail: &SymbolDetail,
    analysis_snapshot: &DetailAnalysisSnapshot<'_>,
    relative_score: Option<&SectorRelativeScore>,
    compact: bool,
) -> Vec<RenderLine> {
    let Some(fundamentals) = detail.fundamentals.as_ref() else {
        return vec![RenderLine {
            color: Some(Color::DarkGrey),
            text: "Yahoo quote fundamentals are not available for this ticker.".to_string(),
        }];
    };

    let mut lines = Vec::new();
    let debt_ebitda = fundamental_debt_or_net_debt_to_ebitda(fundamentals);
    let compact_line = format!(
        "DCF {}  P/E {}  PEG {}  ROE {}  {} {}  FCF yield {}",
        analysis_snapshot
            .analysis
            .map(|analysis| format!(
                "{} ({})",
                dcf_signal_label(dcf_signal(analysis, detail.market_price_cents)),
                dcf_margin_of_safety_bps(analysis, detail.market_price_cents)
                    .map(format_bps)
                    .unwrap_or_else(|| "n/a".to_string())
            ))
            .unwrap_or_else(|| analysis_snapshot.status.clone()),
        format_optional_decimal(fundamental_trailing_pe(fundamentals), 2),
        format_optional_decimal(fundamental_peg(fundamentals), 2),
        format_optional_percent(fundamental_roe(fundamentals)),
        debt_ebitda.map(|(label, _)| label).unwrap_or("Debt/EBITDA"),
        format_optional_decimal(debt_ebitda.map(|(_, value)| value), 2),
        format_optional_percent_ratio(fundamental_fcf_yield(fundamentals)),
    );

    lines.push(RenderLine {
        color: Some(match analysis_snapshot.analysis {
            Some(analysis) => dcf_signal_color(dcf_signal(analysis, detail.market_price_cents)),
            None => analysis_snapshot.color,
        }),
        text: compact_line,
    });

    if compact {
        if let Some(note) = analysis_snapshot.note.as_ref() {
            lines.push(RenderLine {
                color: Some(analysis_snapshot.color),
                text: format!("DCF note: {note}"),
            });
        }
        return lines;
    }

    lines.push(RenderLine {
        color: Some(Color::DarkGrey),
        text: format!(
            "Yahoo analyst fair value {} remains external. Proprietary DCF value {} drives the DCF signal.",
            format_money(detail.intrinsic_value_cents),
            analysis_snapshot
                .analysis
                .map(|analysis| format_money(analysis.base_intrinsic_value_cents))
                .unwrap_or_else(|| "n/a".to_string()),
        ),
    });

    if let Some(analysis) = analysis_snapshot.analysis {
        lines.push(RenderLine {
            color: Some(dcf_signal_color(dcf_signal(
                analysis,
                detail.market_price_cents,
            ))),
            text: format!(
                "DCF bear {}  base {}  bull {}  signal {}  margin {}",
                format_money(analysis.bear_intrinsic_value_cents),
                format_money(analysis.base_intrinsic_value_cents),
                format_money(analysis.bull_intrinsic_value_cents),
                dcf_signal_label(dcf_signal(analysis, detail.market_price_cents)),
                dcf_margin_of_safety_bps(analysis, detail.market_price_cents)
                    .map(format_bps)
                    .unwrap_or_else(|| "n/a".to_string()),
            ),
        });
        lines.push(RenderLine {
            color: Some(Color::DarkGrey),
            text: format!(
                "WACC {}  FCF/share CAGR {}  Net debt {}",
                format_bps(analysis.wacc_bps),
                format_bps(analysis.base_growth_bps),
                format_compact_dollars(analysis.net_debt_dollars),
            ),
        });
    } else if let Some(note) = analysis_snapshot.note.as_ref() {
        lines.push(RenderLine {
            color: Some(analysis_snapshot.color),
            text: format!("DCF {}: {}", analysis_snapshot.status, note),
        });
    }

    lines.push(RenderLine {
        color: Some(Color::DarkGrey),
        text: format!(
            "P/E {}  Forward P/E {}  PEG {}  P/B {}",
            format_optional_decimal(fundamental_trailing_pe(fundamentals), 2),
            format_optional_decimal(fundamental_forward_pe(fundamentals), 2),
            format_optional_decimal(fundamental_peg(fundamentals), 2),
            format_optional_decimal(fundamental_price_to_book(fundamentals), 2),
        ),
    });
    lines.push(RenderLine {
        color: Some(Color::DarkGrey),
        text: format!(
            "ROE {}  Debt/Equity {}  EV/EBITDA {}  Beta {}",
            format_optional_percent(fundamental_roe(fundamentals)),
            format_optional_decimal(fundamental_debt_to_equity(fundamentals), 2),
            format_optional_decimal(fundamental_ev_to_ebitda(fundamentals), 2),
            format_optional_decimal(fundamental_beta(fundamentals), 2),
        ),
    });
    lines.push(RenderLine {
        color: Some(Color::DarkGrey),
        text: format!(
            "{} {}  FCF yield {}  OCF {}  FCF {}",
            debt_ebitda.map(|(label, _)| label).unwrap_or("Debt/EBITDA"),
            format_optional_decimal(debt_ebitda.map(|(_, value)| value), 2),
            format_optional_percent_ratio(fundamental_fcf_yield(fundamentals)),
            fundamentals
                .operating_cash_flow_dollars
                .map(format_compact_dollars)
                .unwrap_or_else(|| "n/a".to_string()),
            fundamentals
                .free_cash_flow_dollars
                .map(format_compact_dollars)
                .unwrap_or_else(|| "n/a".to_string()),
        ),
    });

    match relative_score {
        Some(relative_score) => {
            lines.push(RenderLine {
                color: Some(relative_strength_color(relative_score.composite_band)),
                text: format!(
            "Relative vs {} {} peers={} percentile={} ({})",
                    relative_score.group_kind,
                    relative_score.group_label,
                    relative_score.peer_count,
                    relative_score.composite_percentile,
                    relative_strength_label(relative_score.composite_band),
                ),
            });
            lines.push(RenderLine {
                color: Some(Color::DarkGrey),
                text: relative_score
                    .metrics
                    .iter()
                    .map(|metric| {
                        format!(
                            "{} pctl {} ({})",
                            metric.label,
                            metric.percentile,
                            relative_strength_label(metric.band)
                        )
                    })
                    .collect::<Vec<_>>()
                    .join("  |  "),
            });
        }
        None => lines.push(RenderLine {
            color: Some(Color::DarkGrey),
            text: "Relative percentile scoring unavailable: need at least 5 tracked peers sharing industry or sector with enough ratio coverage.".to_string(),
        }),
    }

    lines
}

fn aggregate_historical_candles(
    candles: &[HistoricalCandle],
    max_candles: usize,
) -> Vec<PriceCandle> {
    if candles.is_empty() || max_candles == 0 {
        return Vec::new();
    }

    let close_series = candles
        .iter()
        .map(|candle| candle.close_cents)
        .collect::<Vec<_>>();
    let ema_20_series = compute_ema_series(&close_series, 20);
    let ema_50_series = compute_ema_series(&close_series, 50);
    let ema_200_series = compute_ema_series(&close_series, 200);
    let (macd_series, signal_series, histogram_series) = compute_macd_series(&close_series);
    let bucket_size = chart_bucket_size(candles.len(), max_candles);
    candles
        .chunks(bucket_size)
        .enumerate()
        .map(|(bucket_index, bucket)| PriceCandle {
            open_cents: bucket.first().map(|candle| candle.open_cents).unwrap_or(0),
            high_cents: bucket
                .iter()
                .map(|candle| candle.high_cents)
                .max()
                .unwrap_or(0),
            low_cents: bucket
                .iter()
                .map(|candle| candle.low_cents)
                .min()
                .unwrap_or(0),
            close_cents: bucket.last().map(|candle| candle.close_cents).unwrap_or(0),
            volume: bucket.iter().map(|candle| candle.volume).sum(),
            ema_20_cents: last_bucket_value(
                &ema_20_series,
                bucket_index,
                bucket.len(),
                bucket_size,
            ),
            ema_50_cents: last_bucket_value(
                &ema_50_series,
                bucket_index,
                bucket.len(),
                bucket_size,
            ),
            ema_200_cents: last_bucket_value(
                &ema_200_series,
                bucket_index,
                bucket.len(),
                bucket_size,
            ),
            macd_cents: last_bucket_value(&macd_series, bucket_index, bucket.len(), bucket_size),
            signal_cents: last_bucket_value(
                &signal_series,
                bucket_index,
                bucket.len(),
                bucket_size,
            ),
            histogram_cents: last_bucket_value(
                &histogram_series,
                bucket_index,
                bucket.len(),
                bucket_size,
            ),
            point_count: bucket.len(),
        })
        .collect()
}

fn last_bucket_value(
    values: &[Option<f64>],
    bucket_index: usize,
    bucket_len: usize,
    bucket_size: usize,
) -> Option<f64> {
    let end_index = bucket_index
        .saturating_mul(bucket_size)
        .saturating_add(bucket_len.saturating_sub(1));
    values.get(end_index).copied().flatten()
}

fn compute_ema_series(closes_cents: &[i64], period: usize) -> Vec<Option<f64>> {
    if closes_cents.is_empty() || period == 0 {
        return Vec::new();
    }

    let alpha = 2.0 / (period as f64 + 1.0);
    let mut ema = closes_cents[0] as f64;
    let mut series = Vec::with_capacity(closes_cents.len());

    for close_cents in closes_cents {
        ema = *close_cents as f64 * alpha + ema * (1.0 - alpha);
        series.push(Some(ema));
    }

    series
}

fn compute_macd_series(
    closes_cents: &[i64],
) -> (Vec<Option<f64>>, Vec<Option<f64>>, Vec<Option<f64>>) {
    if closes_cents.is_empty() {
        return (Vec::new(), Vec::new(), Vec::new());
    }

    let ema_12 = compute_ema_series(closes_cents, 12);
    let ema_26 = compute_ema_series(closes_cents, 26);
    let macd_series = ema_12
        .iter()
        .zip(ema_26.iter())
        .map(|(fast, slow)| fast.zip(*slow).map(|(fast, slow)| fast - slow))
        .collect::<Vec<_>>();
    let signal_seed = macd_series
        .iter()
        .map(|value| value.unwrap_or(0.0).round() as i64)
        .collect::<Vec<_>>();
    let signal_series = compute_ema_series(&signal_seed, 9);
    let histogram_series = macd_series
        .iter()
        .zip(signal_series.iter())
        .map(|(macd, signal)| macd.zip(*signal).map(|(macd, signal)| macd - signal))
        .collect::<Vec<_>>();

    (macd_series, signal_series, histogram_series)
}

fn summarize_chart_range(
    range: ChartRange,
    captured_at: u64,
    candles: &[HistoricalCandle],
) -> ChartRangeSummary {
    let closes = candles
        .iter()
        .map(|candle| candle.close_cents)
        .collect::<Vec<_>>();
    let ema20 = compute_ema_series(&closes, 20);
    let ema50 = compute_ema_series(&closes, 50);
    let ema200 = compute_ema_series(&closes, 200);
    let (macd, signal, histogram) = compute_macd_series(&closes);
    ChartRangeSummary {
        range,
        captured_at,
        candle_count: candles.len(),
        latest_close_cents: closes.last().copied(),
        ema20_cents: ema20
            .last()
            .and_then(|value| value.map(|value| value.round() as i64)),
        ema50_cents: ema50
            .last()
            .and_then(|value| value.map(|value| value.round() as i64)),
        ema200_cents: ema200
            .last()
            .and_then(|value| value.map(|value| value.round() as i64)),
        macd_cents: macd
            .last()
            .and_then(|value| value.map(|value| value.round() as i64)),
        signal_cents: signal
            .last()
            .and_then(|value| value.map(|value| value.round() as i64)),
        histogram_cents: histogram
            .last()
            .and_then(|value| value.map(|value| value.round() as i64)),
    }
}

fn chart_bucket_size(point_count: usize, max_candles: usize) -> usize {
    if point_count == 0 || max_candles == 0 {
        return 1;
    }

    point_count.div_ceil(max_candles.max(1))
}

fn compute_volume_profile(
    candles: &[HistoricalCandle],
    min_price_cents: i64,
    max_price_cents: i64,
    num_bins: usize,
) -> Vec<VolumeProfileBin> {
    let price_candles = candles
        .iter()
        .map(|candle| PriceCandle {
            open_cents: candle.open_cents,
            high_cents: candle.high_cents,
            low_cents: candle.low_cents,
            close_cents: candle.close_cents,
            volume: candle.volume,
            ema_20_cents: None,
            ema_50_cents: None,
            ema_200_cents: None,
            macd_cents: None,
            signal_cents: None,
            histogram_cents: None,
            point_count: 1,
        })
        .collect::<Vec<_>>();
    compute_volume_profile_from_price_candles(
        &price_candles,
        min_price_cents,
        max_price_cents,
        num_bins,
    )
}

fn compute_volume_profile_from_price_candles(
    candles: &[PriceCandle],
    min_price_cents: i64,
    max_price_cents: i64,
    num_bins: usize,
) -> Vec<VolumeProfileBin> {
    let mut bins = vec![
        VolumeProfileBin {
            up_volume: 0,
            down_volume: 0,
        };
        num_bins
    ];
    if num_bins == 0 || min_price_cents >= max_price_cents {
        return bins;
    }
    let range = (max_price_cents - min_price_cents) as f64;
    for candle in candles {
        let low = candle.low_cents.max(min_price_cents);
        let high = candle.high_cents.min(max_price_cents);
        let low_bin =
            ((low - min_price_cents) as f64 / range * (num_bins - 1) as f64).round() as usize;
        let high_bin =
            ((high - min_price_cents) as f64 / range * (num_bins - 1) as f64).round() as usize;
        let low_bin = low_bin.min(num_bins - 1);
        let high_bin = high_bin.min(num_bins - 1);
        let span = (high_bin - low_bin + 1) as u64;
        let per_bin = candle.volume / span;
        let remainder = candle.volume % span;
        let is_up = candle.close_cents >= candle.open_cents;
        for (i, bin_index) in (low_bin..=high_bin).enumerate() {
            let row = num_bins - 1 - bin_index;
            let vol = per_bin + if (i as u64) < remainder { 1 } else { 0 };
            if is_up {
                bins[row].up_volume += vol;
            } else {
                bins[row].down_volume += vol;
            }
        }
    }
    bins
}

fn render_volume_profile_cells(
    bin: &VolumeProfileBin,
    max_bin_volume: u64,
    bar_width: usize,
) -> Vec<StyledCell> {
    let mut cells = Vec::with_capacity(bar_width);
    cells.push(StyledCell {
        ch: '│',
        color: Some(Color::DarkGrey),
        bg_color: None,
        priority: 255,
    });
    let available = bar_width.saturating_sub(1);
    if max_bin_volume == 0 || available == 0 {
        for _ in 0..available {
            cells.push(StyledCell {
                ch: ' ',
                color: None,
                bg_color: None,
                priority: 0,
            });
        }
        return cells;
    }
    let total = bin.up_volume + bin.down_volume;
    let mut filled = ((total as f64 / max_bin_volume as f64) * available as f64).round() as usize;
    filled = filled.min(available);
    let up_chars = if total > 0 {
        ((bin.up_volume as f64 / total as f64) * filled as f64).round() as usize
    } else {
        0
    };
    let down_chars = filled.saturating_sub(up_chars);
    for _ in 0..up_chars {
        cells.push(StyledCell {
            ch: '█',
            color: Some(Color::DarkYellow),
            bg_color: None,
            priority: 10,
        });
    }
    for _ in 0..down_chars {
        cells.push(StyledCell {
            ch: '█',
            color: Some(Color::DarkCyan),
            bg_color: None,
            priority: 10,
        });
    }
    for _ in 0..(available - filled) {
        cells.push(StyledCell {
            ch: ' ',
            color: None,
            bg_color: None,
            priority: 0,
        });
    }
    cells
}

fn build_chart_stack_lines(
    candles: &[PriceCandle],
    profile_source: &[HistoricalCandle],
    layout: &DetailLayout,
) -> Vec<RenderLine> {
    let chart_width = layout.plot_width;
    let profile_width = if layout.show_volume_profile {
        DETAIL_VOLUME_PROFILE_WIDTH
    } else {
        0
    };
    let separator_width = DETAIL_CHART_AXIS_WIDTH + 2 + chart_width + profile_width;
    let mut lines = vec![pane_header_line(
        "PRICE",
        "candles + EMA",
        Some(Color::Yellow),
        Some(Color::DarkGrey),
    )];
    lines.extend(render_price_chart_lines(candles, profile_source, layout));
    if layout.show_overlay_legend {
        lines.push(price_legend_line(layout.show_ema_200));
    }

    lines.push(pane_separator_line("VOLUME", separator_width));
    if layout.compact_volume {
        lines.push(RenderLine {
            color: Some(Color::DarkGrey),
            text: render_compact_volume_line(candles),
        });
    } else {
        lines.extend(render_volume_chart_lines_with_width(
            candles,
            layout.volume_chart_height,
            layout.plot_width,
        ));
    }

    if layout.show_macd {
        lines.push(pane_separator_line("MACD", separator_width));
        lines.extend(render_macd_chart_lines_with_width(
            candles,
            layout.macd_chart_height,
            layout.plot_width,
        ));
        if layout.show_macd_legend {
            lines.push(macd_legend_line());
        }
    }

    lines
}

fn chart_column_positions(point_count: usize, plot_width: usize) -> Vec<usize> {
    if point_count == 0 || plot_width == 0 {
        return Vec::new();
    }
    if point_count == 1 {
        return vec![plot_width.saturating_sub(1) / 2];
    }

    let last_column = plot_width.saturating_sub(1);
    (0..point_count)
        .map(|index| index * last_column / (point_count - 1))
        .collect()
}

fn render_price_chart_lines(
    candles: &[PriceCandle],
    profile_source: &[HistoricalCandle],
    layout: &DetailLayout,
) -> Vec<RenderLine> {
    if candles.is_empty() || layout.price_chart_height == 0 {
        return Vec::new();
    }

    let mut min_price_cents = candles
        .iter()
        .map(|candle| candle.low_cents)
        .min()
        .unwrap_or(0);
    let mut max_price_cents = candles
        .iter()
        .map(|candle| candle.high_cents)
        .max()
        .unwrap_or(0);
    for ema_value in candles.iter().flat_map(|candle| {
        [
            candle.ema_20_cents,
            candle.ema_50_cents,
            if layout.show_ema_200 {
                candle.ema_200_cents
            } else {
                None
            },
        ]
    }) {
        if let Some(ema_value) = ema_value {
            min_price_cents = min_price_cents.min(ema_value.round() as i64);
            max_price_cents = max_price_cents.max(ema_value.round() as i64);
        }
    }
    let (min_price_cents, max_price_cents) = padded_i64_range(min_price_cents, max_price_cents);
    let plot_columns = chart_column_positions(candles.len(), layout.plot_width);
    let mut canvas = tui_graphs::BrailleCanvas::new(
        layout.price_chart_height,
        layout.plot_width,
        min_price_cents as f64,
        max_price_cents as f64,
    );

    for (column, candle) in plot_columns.iter().copied().zip(candles.iter()) {
        let high_row = canvas.map_to_subrow(candle.high_cents as f64);
        let low_row = canvas.map_to_subrow(candle.low_cents as f64);
        let open_row = canvas.map_to_subrow(candle.open_cents as f64);
        let close_row = canvas.map_to_subrow(candle.close_cents as f64);

        let candle_color = if candle.close_cents > candle.open_cents {
            Some(Color::Green)
        } else if candle.close_cents < candle.open_cents {
            Some(Color::Red)
        } else {
            Some(Color::Grey)
        };
        canvas.fill_vertical_half(column, 0, high_row, low_row, candle_color, 1);
        canvas.fill_vertical_full(column, open_row, close_row, candle_color, 2);

        if let Some(ema_value) = candle.ema_20_cents {
            let row = canvas.map_to_subrow(ema_value);
            canvas.fill_dot(row, column, 1, Some(Color::Yellow), 5);
        }
        if let Some(ema_value) = candle.ema_50_cents {
            let row = canvas.map_to_subrow(ema_value);
            canvas.fill_dot(row, column, 1, Some(Color::Cyan), 4);
        }
        if layout.show_ema_200 {
            if let Some(ema_value) = candle.ema_200_cents {
                let row = canvas.map_to_subrow(ema_value);
                canvas.fill_dot(row, column, 1, Some(Color::DarkGrey), 3);
            }
        }
    }

    let cells = canvas.collapse_to_cells();
    if layout.show_volume_profile {
        let profile = compute_volume_profile(
            profile_source,
            min_price_cents,
            max_price_cents,
            layout.price_chart_height,
        );
        let max_bin_volume = profile
            .iter()
            .map(|b| b.up_volume + b.down_volume)
            .max()
            .unwrap_or(0);
        render_i64_axis_pane_with_profile(
            cells,
            min_price_cents,
            max_price_cents,
            &profile,
            max_bin_volume,
        )
    } else {
        render_i64_axis_pane(cells, min_price_cents, max_price_cents)
    }
}

#[cfg(test)]
fn render_volume_chart_lines(candles: &[PriceCandle], chart_height: usize) -> Vec<RenderLine> {
    render_volume_chart_lines_with_width(candles, chart_height, candles.len().max(1))
}

fn render_volume_chart_lines_with_width(
    candles: &[PriceCandle],
    chart_height: usize,
    plot_width: usize,
) -> Vec<RenderLine> {
    if candles.is_empty() || chart_height == 0 {
        return Vec::new();
    }

    let chart_width = plot_width.max(1);
    let plot_columns = chart_column_positions(candles.len(), chart_width);
    let max_volume = candles
        .iter()
        .map(|candle| candle.volume)
        .max()
        .unwrap_or(0)
        .max(1);
    let mut canvas =
        tui_graphs::BrailleCanvas::new(chart_height, chart_width, 0.0, max_volume as f64);

    for (column, candle) in plot_columns.iter().copied().zip(candles.iter()) {
        if candle.volume == 0 {
            continue;
        }

        let top_row = canvas.map_to_subrow(candle.volume as f64);
        let bot_row = canvas.map_to_subrow(0.0);
        canvas.fill_vertical_full(column, top_row, bot_row, Some(Color::DarkBlue), 3);
    }

    render_axis_pane(canvas.collapse_to_cells(), |row_index, h| {
        format_compact_quantity(value_for_row_u64(row_index, 0, max_volume, h))
    })
}

fn render_compact_volume_line(candles: &[PriceCandle]) -> String {
    let total_volume = candles.iter().map(|candle| candle.volume).sum::<u64>();
    let max_volume = candles
        .iter()
        .map(|candle| candle.volume)
        .max()
        .unwrap_or(0);
    format!(
        "Volume compressed: total {}  max {}  viewport too short for a separate pane",
        format_compact_quantity(total_volume),
        format_compact_quantity(max_volume),
    )
}

#[cfg(test)]
fn render_macd_chart_lines(candles: &[PriceCandle], chart_height: usize) -> Vec<RenderLine> {
    render_macd_chart_lines_with_width(candles, chart_height, candles.len().max(1))
}

fn render_macd_chart_lines_with_width(
    candles: &[PriceCandle],
    chart_height: usize,
    plot_width: usize,
) -> Vec<RenderLine> {
    if candles.is_empty() || chart_height == 0 {
        return Vec::new();
    }

    let mut min_value = 0.0f64;
    let mut max_value = 0.0f64;
    for indicator_value in candles.iter().flat_map(|candle| {
        [
            candle.macd_cents,
            candle.signal_cents,
            candle.histogram_cents,
        ]
    }) {
        if let Some(indicator_value) = indicator_value {
            min_value = min_value.min(indicator_value);
            max_value = max_value.max(indicator_value);
        }
    }
    let (min_value, max_value) = padded_f64_range(min_value, max_value);
    let chart_width = plot_width.max(1);
    let plot_columns = chart_column_positions(candles.len(), chart_width);
    let mut canvas =
        tui_graphs::BrailleCanvas::new(chart_height, chart_width, min_value, max_value);

    canvas.plot_hline(0.0, Some(Color::DarkGrey), 0);

    for (column, candle) in plot_columns.iter().copied().zip(candles.iter()) {
        if let Some(histogram_value) = candle.histogram_cents {
            let zero_row = canvas.map_to_subrow(0.0);
            let histogram_row = canvas.map_to_subrow(histogram_value);
            let histogram_color = if histogram_value >= 0.0 {
                Some(Color::DarkGreen)
            } else {
                Some(Color::DarkRed)
            };
            canvas.fill_vertical_full(column, zero_row, histogram_row, histogram_color, 1);
        }

        if let Some(macd_value) = candle.macd_cents {
            let row = canvas.map_to_subrow(macd_value);
            canvas.fill_dot(row, column, 0, Some(Color::Cyan), 3);
        }

        if let Some(signal_value) = candle.signal_cents {
            let row = canvas.map_to_subrow(signal_value);
            canvas.fill_dot(row, column, 1, Some(Color::Yellow), 4);
        }
    }

    render_axis_pane(canvas.collapse_to_cells(), |row_index, h| {
        format_money(value_for_row_f64(row_index, min_value, max_value, h).round() as i64)
    })
}

fn compact_consensus_line(detail: &SymbolDetail) -> String {
    format!(
        "Analysts {}  Recommendation mean {}  Ratings: SB {}  B {}  H {}  S {}  SS {}",
        format_optional_count(detail.analyst_opinion_count),
        detail
            .recommendation_mean_hundredths
            .map(format_recommendation_mean)
            .unwrap_or_else(|| "n/a".to_string()),
        detail.strong_buy_count.unwrap_or(0),
        detail.buy_count.unwrap_or(0),
        detail.hold_count.unwrap_or(0),
        detail.sell_count.unwrap_or(0),
        detail.strong_sell_count.unwrap_or(0),
    )
}

fn pane_header_line(
    title: &str,
    subtitle: &str,
    title_color: Option<Color>,
    subtitle_color: Option<Color>,
) -> RenderLine {
    styled_segments_line(vec![
        StyledSegment {
            color: title_color,
            bg_color: None,
            text: format!("{title:<6}"),
        },
        StyledSegment {
            color: subtitle_color,
            bg_color: None,
            text: format!(" {subtitle}"),
        },
    ])
}

fn pane_separator_line(label: &str, width: usize) -> RenderLine {
    let prefix = format!("-- {label} ");
    let rule_width = width.saturating_sub(prefix.chars().count()).max(4);
    styled_segments_line(vec![
        StyledSegment {
            color: Some(Color::DarkGrey),
            bg_color: None,
            text: prefix,
        },
        StyledSegment {
            color: Some(Color::DarkGrey),
            bg_color: None,
            text: "-".repeat(rule_width),
        },
    ])
}

fn price_legend_line(show_ema_200: bool) -> RenderLine {
    let mut segments = vec![
        StyledSegment {
            color: Some(Color::Green),
            bg_color: None,
            text: "█ up".to_string(),
        },
        StyledSegment {
            color: Some(Color::DarkGrey),
            bg_color: None,
            text: "  ".to_string(),
        },
        StyledSegment {
            color: Some(Color::Red),
            bg_color: None,
            text: "▓ down".to_string(),
        },
        StyledSegment {
            color: Some(Color::DarkGrey),
            bg_color: None,
            text: "  ".to_string(),
        },
        StyledSegment {
            color: Some(Color::Grey),
            bg_color: None,
            text: "─ flat".to_string(),
        },
        StyledSegment {
            color: Some(Color::DarkGrey),
            bg_color: None,
            text: "  ".to_string(),
        },
        StyledSegment {
            color: Some(Color::Yellow),
            bg_color: None,
            text: ". EMA20".to_string(),
        },
        StyledSegment {
            color: Some(Color::DarkGrey),
            bg_color: None,
            text: "  ".to_string(),
        },
        StyledSegment {
            color: Some(Color::Cyan),
            bg_color: None,
            text: "x EMA50".to_string(),
        },
    ];
    if show_ema_200 {
        segments.push(StyledSegment {
            color: Some(Color::DarkGrey),
            bg_color: None,
            text: "  ".to_string(),
        });
        segments.push(StyledSegment {
            color: Some(Color::DarkGrey),
            bg_color: None,
            text: "o EMA200".to_string(),
        });
    }
    styled_segments_line(segments)
}

fn macd_legend_line() -> RenderLine {
    styled_segments_line(vec![
        StyledSegment {
            color: Some(Color::Cyan),
            bg_color: None,
            text: "+ MACD".to_string(),
        },
        StyledSegment {
            color: Some(Color::DarkGrey),
            bg_color: None,
            text: "  ".to_string(),
        },
        StyledSegment {
            color: Some(Color::Yellow),
            bg_color: None,
            text: "= signal".to_string(),
        },
        StyledSegment {
            color: Some(Color::DarkGrey),
            bg_color: None,
            text: "  ".to_string(),
        },
        StyledSegment {
            color: Some(Color::DarkGreen),
            bg_color: None,
            text: "█ hist+".to_string(),
        },
        StyledSegment {
            color: Some(Color::DarkGrey),
            bg_color: None,
            text: "  ".to_string(),
        },
        StyledSegment {
            color: Some(Color::DarkRed),
            bg_color: None,
            text: "▓ hist-".to_string(),
        },
    ])
}

#[cfg(test)]
#[derive(Clone, Debug, PartialEq)]
struct HiResPixel {
    color: Option<Color>,
    priority: u8,
}

#[cfg(test)]
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct GlyphCell {
    ch: char,
    color: Option<Color>,
    priority: u8,
}

#[cfg(test)]
struct ChartCanvas {
    pixels: Vec<HiResPixel>,
    glyphs: Vec<Option<GlyphCell>>,
    width: usize,
    terminal_height: usize,
    min_value: f64,
    max_value: f64,
}

#[cfg(test)]
impl ChartCanvas {
    fn new(terminal_height: usize, width: usize, min_value: f64, max_value: f64) -> Self {
        let hires_len = terminal_height * 2 * width;
        let glyph_len = terminal_height * width;
        Self {
            pixels: vec![
                HiResPixel {
                    color: None,
                    priority: 0
                };
                hires_len
            ],
            glyphs: vec![None; glyph_len],
            width,
            terminal_height,
            min_value,
            max_value,
        }
    }

    fn reset(&mut self, min_value: f64, max_value: f64) {
        self.pixels.fill(HiResPixel {
            color: None,
            priority: 0,
        });
        self.glyphs.fill(None);
        self.min_value = min_value;
        self.max_value = max_value;
    }

    fn fill_pixel(&mut self, hires_row: usize, col: usize, color: Option<Color>, priority: u8) {
        let idx = hires_row * self.width + col;
        let Some(pixel) = self.pixels.get_mut(idx) else {
            return;
        };
        if pixel.priority > priority && pixel.color.is_some() {
            return;
        }
        *pixel = HiResPixel { color, priority };
    }

    fn draw_glyph(
        &mut self,
        terminal_row: usize,
        col: usize,
        ch: char,
        color: Option<Color>,
        priority: u8,
    ) {
        let idx = terminal_row * self.width + col;
        let Some(slot) = self.glyphs.get_mut(idx) else {
            return;
        };
        if let Some(existing) = slot {
            if existing.priority > priority {
                return;
            }
        }
        *slot = Some(GlyphCell {
            ch,
            color,
            priority,
        });
    }

    fn map_to_hires_row(&self, value: f64) -> usize {
        map_numeric_to_row(
            value,
            self.min_value,
            self.max_value,
            self.terminal_height * 2,
        )
    }

    fn map_to_terminal_row(&self, value: f64) -> usize {
        map_numeric_to_row(value, self.min_value, self.max_value, self.terminal_height)
    }

    fn plot_pixel(&mut self, value: f64, col: usize, color: Option<Color>, priority: u8) {
        let row = self.map_to_hires_row(value);
        self.fill_pixel(row, col, color, priority);
    }

    fn plot_glyph(&mut self, value: f64, col: usize, ch: char, color: Option<Color>, priority: u8) {
        let row = self.map_to_terminal_row(value);
        self.draw_glyph(row, col, ch, color, priority);
    }

    fn fill_vertical(
        &mut self,
        col: usize,
        hires_row_lo: usize,
        hires_row_hi: usize,
        color: Option<Color>,
        priority: u8,
    ) {
        let lo = hires_row_lo.min(hires_row_hi);
        let hi = hires_row_lo.max(hires_row_hi);
        for row in lo..=hi {
            self.fill_pixel(row, col, color, priority);
        }
    }

    fn plot_hline(&mut self, value: f64, color: Option<Color>, priority: u8) {
        let row = self.map_to_hires_row(value);
        for col in 0..self.width {
            self.fill_pixel(row, col, color, priority);
        }
    }

    fn collapse_to_cells(&self) -> Vec<Vec<StyledCell>> {
        let mut result = Vec::with_capacity(self.terminal_height);
        for trow in 0..self.terminal_height {
            let mut row_cells = Vec::with_capacity(self.width);
            for col in 0..self.width {
                let top_idx = trow * 2 * self.width + col;
                let bot_idx = (trow * 2 + 1) * self.width + col;
                let top = &self.pixels[top_idx];
                let bot = &self.pixels[bot_idx];
                let glyph_idx = trow * self.width + col;
                let glyph = &self.glyphs[glyph_idx];

                let cell = if let Some(g) = glyph {
                    let max_pixel_pri = top.priority.max(bot.priority);
                    if g.priority >= max_pixel_pri || (top.color.is_none() && bot.color.is_none()) {
                        let bg = match (top.color, bot.color) {
                            (Some(tc), Some(bc)) if tc == bc => Some(tc),
                            (Some(tc), _) => Some(tc),
                            (_, Some(bc)) => Some(bc),
                            _ => None,
                        };
                        StyledCell {
                            ch: g.ch,
                            color: g.color,
                            bg_color: bg,
                            priority: g.priority,
                        }
                    } else {
                        Self::pixel_pair_to_cell(top, bot)
                    }
                } else {
                    Self::pixel_pair_to_cell(top, bot)
                };
                row_cells.push(cell);
            }
            result.push(row_cells);
        }
        result
    }

    fn pixel_pair_to_cell(top: &HiResPixel, bot: &HiResPixel) -> StyledCell {
        match (top.color, bot.color) {
            (None, None) => StyledCell {
                ch: ' ',
                color: None,
                bg_color: None,
                priority: 0,
            },
            (Some(tc), Some(bc)) if tc == bc => StyledCell {
                ch: '█',
                color: Some(tc),
                bg_color: None,
                priority: top.priority.max(bot.priority),
            },
            (Some(tc), Some(bc)) => StyledCell {
                ch: '▄',
                color: Some(bc),
                bg_color: Some(tc),
                priority: top.priority.max(bot.priority),
            },
            (Some(tc), None) => StyledCell {
                ch: '▀',
                color: Some(tc),
                bg_color: None,
                priority: top.priority,
            },
            (None, Some(bc)) => StyledCell {
                ch: '▄',
                color: Some(bc),
                bg_color: None,
                priority: bot.priority,
            },
        }
    }

    fn with_axis(&self, mut label_for_row: impl FnMut(usize, usize) -> String) -> Vec<RenderLine> {
        let cells = self.collapse_to_cells();
        let chart_height = cells.len();
        let mid_row = chart_height / 2;
        cells
            .into_iter()
            .enumerate()
            .map(|(row_index, row)| {
                let axis_label =
                    if row_index == 0 || row_index == mid_row || row_index + 1 == chart_height {
                        format!("{:>10}", label_for_row(row_index, chart_height))
                    } else {
                        " ".repeat(10)
                    };
                let mut axis_cells: Vec<StyledCell> = axis_label
                    .chars()
                    .map(|ch| StyledCell {
                        ch,
                        color: Some(Color::DarkGrey),
                        bg_color: None,
                        priority: 255,
                    })
                    .collect();
                axis_cells.push(StyledCell {
                    ch: ' ',
                    color: Some(Color::DarkGrey),
                    bg_color: None,
                    priority: 255,
                });
                axis_cells.push(StyledCell {
                    ch: '│',
                    color: Some(Color::DarkGrey),
                    bg_color: None,
                    priority: 255,
                });
                axis_cells.extend(row);
                styled_cells_line(&axis_cells)
            })
            .collect()
    }
}

fn render_i64_axis_pane(
    canvas: Vec<Vec<StyledCell>>,
    min_value: i64,
    max_value: i64,
) -> Vec<RenderLine> {
    render_axis_pane(canvas, |row_index, chart_height| {
        format_money(value_for_row_i64(
            row_index,
            min_value,
            max_value,
            chart_height,
        ))
    })
}

fn render_i64_axis_pane_with_profile(
    canvas: Vec<Vec<StyledCell>>,
    min_value: i64,
    max_value: i64,
    profile: &[VolumeProfileBin],
    max_bin_volume: u64,
) -> Vec<RenderLine> {
    let chart_height = canvas.len();
    let mid_row = chart_height / 2;
    canvas
        .into_iter()
        .enumerate()
        .map(|(row_index, row)| {
            let axis_label =
                if row_index == 0 || row_index == mid_row || row_index + 1 == chart_height {
                    format!(
                        "{:>10}",
                        format_money(value_for_row_i64(
                            row_index,
                            min_value,
                            max_value,
                            chart_height,
                        ))
                    )
                } else {
                    " ".repeat(10)
                };
            let mut cells = axis_label
                .chars()
                .map(|ch| StyledCell {
                    ch,
                    color: Some(Color::DarkGrey),
                    bg_color: None,
                    priority: 255,
                })
                .collect::<Vec<_>>();
            cells.push(StyledCell {
                ch: ' ',
                color: Some(Color::DarkGrey),
                bg_color: None,
                priority: 255,
            });
            cells.push(StyledCell {
                ch: '│',
                color: Some(Color::DarkGrey),
                bg_color: None,
                priority: 255,
            });
            cells.extend(row);
            if let Some(bin) = profile.get(row_index) {
                cells.extend(render_volume_profile_cells(
                    bin,
                    max_bin_volume,
                    DETAIL_VOLUME_PROFILE_WIDTH,
                ));
            }
            styled_cells_line(&cells)
        })
        .collect()
}

fn render_axis_pane(
    canvas: Vec<Vec<StyledCell>>,
    mut label_for_row: impl FnMut(usize, usize) -> String,
) -> Vec<RenderLine> {
    let chart_height = canvas.len();
    let mid_row = chart_height / 2;
    canvas
        .into_iter()
        .enumerate()
        .map(|(row_index, row)| {
            let axis_label =
                if row_index == 0 || row_index == mid_row || row_index + 1 == chart_height {
                    format!("{:>10}", label_for_row(row_index, chart_height))
                } else {
                    " ".repeat(10)
                };
            let mut cells = axis_label
                .chars()
                .map(|ch| StyledCell {
                    ch,
                    color: Some(Color::DarkGrey),
                    bg_color: None,
                    priority: 255,
                })
                .collect::<Vec<_>>();
            cells.push(StyledCell {
                ch: ' ',
                color: Some(Color::DarkGrey),
                bg_color: None,
                priority: 255,
            });
            cells.push(StyledCell {
                ch: '│',
                color: Some(Color::DarkGrey),
                bg_color: None,
                priority: 255,
            });
            cells.extend(row);
            styled_cells_line(&cells)
        })
        .collect()
}

fn map_numeric_to_row(value: f64, min_value: f64, max_value: f64, chart_height: usize) -> usize {
    if chart_height <= 1 || min_value >= max_value {
        return 0;
    }

    let normalized = (value.clamp(min_value, max_value) - min_value) / (max_value - min_value);
    let scaled = ((chart_height - 1) as f64 * (1.0 - normalized)).round() as usize;
    scaled.min(chart_height - 1)
}

fn padded_i64_range(min_value: i64, max_value: i64) -> (i64, i64) {
    if min_value != max_value {
        return (min_value, max_value);
    }

    let pad = (min_value.abs() / 50).max(100);
    (min_value.saturating_sub(pad), max_value.saturating_add(pad))
}

fn padded_f64_range(min_value: f64, max_value: f64) -> (f64, f64) {
    if (max_value - min_value).abs() > f64::EPSILON {
        return (min_value, max_value);
    }

    let pad = (min_value.abs() / 50.0).max(100.0);
    (min_value - pad, max_value + pad)
}

fn value_for_row_i64(row_index: usize, min_value: i64, max_value: i64, chart_height: usize) -> i64 {
    if chart_height <= 1 {
        return max_value;
    }

    let ratio = 1.0 - row_index as f64 / (chart_height - 1) as f64;
    min_value + ((max_value - min_value) as f64 * ratio).round() as i64
}

fn value_for_row_u64(row_index: usize, min_value: u64, max_value: u64, chart_height: usize) -> u64 {
    if chart_height <= 1 {
        return max_value;
    }

    let ratio = 1.0 - row_index as f64 / (chart_height - 1) as f64;
    min_value + ((max_value - min_value) as f64 * ratio).round() as u64
}

fn value_for_row_f64(row_index: usize, min_value: f64, max_value: f64, chart_height: usize) -> f64 {
    if chart_height <= 1 {
        return max_value;
    }

    let ratio = 1.0 - row_index as f64 / (chart_height - 1) as f64;
    min_value + (max_value - min_value) * ratio
}

fn format_compact_quantity(value: u64) -> String {
    match value {
        1_000_000_000.. => format!("{:.1}B", value as f64 / 1_000_000_000.0),
        1_000_000.. => format!("{:.1}M", value as f64 / 1_000_000.0),
        1_000.. => format!("{:.1}K", value as f64 / 1_000.0),
        _ => value.to_string(),
    }
}

fn format_compact_dollars(value_dollars: i64) -> String {
    let sign = if value_dollars < 0 { "-" } else { "" };
    let absolute = value_dollars.unsigned_abs();
    match absolute {
        1_000_000_000_000.. => format!("{sign}${:.2}T", absolute as f64 / 1_000_000_000_000.0),
        1_000_000_000.. => format!("{sign}${:.2}B", absolute as f64 / 1_000_000_000.0),
        1_000_000.. => format!("{sign}${:.2}M", absolute as f64 / 1_000_000.0),
        1_000.. => format!("{sign}${:.1}K", absolute as f64 / 1_000.0),
        _ => format!("{sign}${absolute}"),
    }
}

fn build_consensus_graph_lines(detail: &SymbolDetail) -> Vec<RenderLine> {
    let rating_rows = vec![
        (
            "Strong buy",
            detail.strong_buy_count.unwrap_or(0),
            Color::Green,
        ),
        ("Buy", detail.buy_count.unwrap_or(0), Color::Green),
        ("Hold", detail.hold_count.unwrap_or(0), Color::Yellow),
        ("Sell", detail.sell_count.unwrap_or(0), Color::Red),
        (
            "Strong sell",
            detail.strong_sell_count.unwrap_or(0),
            Color::Red,
        ),
    ];
    let total_ratings = rating_rows.iter().map(|(_, count, _)| *count).sum::<u32>();

    if total_ratings == 0 {
        return vec![RenderLine {
            color: Some(Color::DarkGrey),
            text: "No rating distribution is available in the current feed.".to_string(),
        }];
    }

    let mut lines = vec![RenderLine {
        color: Some(Color::DarkGrey),
        text: format!(
            "Analysts {}  Recommendation mean {}  Weighted firms {}",
            format_optional_count(detail.analyst_opinion_count),
            detail
                .recommendation_mean_hundredths
                .map(format_recommendation_mean)
                .unwrap_or_else(|| "n/a".to_string()),
            format_optional_count(detail.weighted_analyst_count),
        ),
    }];

    let max_rating_count = rating_rows
        .iter()
        .map(|(_, count, _)| *count)
        .max()
        .unwrap_or(0);
    for (label, count, color) in rating_rows {
        lines.push(RenderLine {
            color: Some(color),
            text: format!(
                "{:<11} [{}] {:>3}",
                label,
                horizontal_bar(count, max_rating_count, DETAIL_CONSENSUS_BAR_WIDTH),
                count,
            ),
        });
    }

    lines
}

fn horizontal_bar(value: u32, max_value: u32, width: usize) -> String {
    if width == 0 {
        return String::new();
    }
    if max_value == 0 {
        return "░".repeat(width);
    }

    let filled = ((value as usize * width) + max_value as usize - 1) / max_value as usize;
    format!(
        "{}{}",
        "█".repeat(filled.min(width)),
        "░".repeat(width.saturating_sub(filled.min(width))),
    )
}

fn compact_evidence_lines(detail: &SymbolDetail) -> Vec<String> {
    let mut lines = vec![format!(
        "Internal: profitable={}  upside {} vs threshold {}  discount {}",
        yes_no(detail.profitable),
        format_upside_percent(detail.market_price_cents, detail.intrinsic_value_cents),
        format_upside_percent_from_gap_bps(detail.minimum_gap_bps),
        format_money(detail.intrinsic_value_cents - detail.market_price_cents),
    )];

    lines.push(format!(
        "External: median {}  weighted {}  status {}  age {}",
        detail
            .external_signal_fair_value_cents
            .map(|fair_value_cents| {
                format!(
                    "{} ({})",
                    format_money(fair_value_cents),
                    format_upside_percent(detail.market_price_cents, fair_value_cents)
                )
            })
            .unwrap_or_else(|| "n/a".to_string()),
        detail
            .weighted_external_signal_fair_value_cents
            .map(|fair_value_cents| {
                format!(
                    "{} ({})",
                    format_money(fair_value_cents),
                    format_upside_percent(detail.market_price_cents, fair_value_cents)
                )
            })
            .unwrap_or_else(|| "n/a".to_string()),
        external_status_label(detail.external_status),
        detail
            .external_signal_age_seconds
            .map(|age_seconds| format!("{age_seconds}s"))
            .unwrap_or_else(|| "n/a".to_string()),
    ));

    lines.push(format!(
        "Result: {} / {}  target range {} to {}",
        qualification_label(detail.qualification),
        confidence_label(detail.confidence),
        format_optional_money(detail.external_signal_low_fair_value_cents),
        format_optional_money(detail.external_signal_high_fair_value_cents),
    ));

    lines
}

fn evidence_line_color(detail: &SymbolDetail, line_text: &str) -> Color {
    if line_text.starts_with("Internal:") {
        gap_color(
            checked_upside_bps(detail.market_price_cents, detail.intrinsic_value_cents)
                .unwrap_or(0),
            upside_bps_from_gap_bps(detail.minimum_gap_bps).unwrap_or(0),
        )
    } else if line_text.starts_with("External:") {
        external_status_color(detail.external_status)
    } else {
        status_summary_color(detail.qualification, detail.confidence)
    }
}

fn summarize_recent_alerts<'a>(alerts: impl DoubleEndedIterator<Item = &'a AlertEvent>) -> String {
    let recent = alerts
        .rev()
        .take(DETAIL_RECENT_SUMMARY_COUNT)
        .map(|alert| format!("{} #{}", alert_label(alert.kind), alert.sequence))
        .collect::<Vec<_>>();

    if recent.is_empty() {
        return "Alerts: none in the current session.".to_string();
    }

    format!("Alerts: {}", recent.join("  |  "))
}

fn summarize_recent_tape<'a>(tape: impl DoubleEndedIterator<Item = &'a TapeEvent>) -> String {
    let recent = tape
        .rev()
        .take(DETAIL_RECENT_SUMMARY_COUNT)
        .map(|event| {
            format!(
                "{} {} {}",
                format_upside_percent_from_gap_bps(event.gap_bps),
                if event.is_qualified {
                    "qualified"
                } else {
                    "watch"
                },
                confidence_label(event.confidence),
            )
        })
        .collect::<Vec<_>>();

    if recent.is_empty() {
        return "Tape: no recent qualifying state changes yet.".to_string();
    }

    format!("Tape: {}", recent.join("  |  "))
}

fn symbol_matches_view_filter(
    state: &TerminalState,
    symbol: &str,
    view_filter: &ViewFilter,
) -> bool {
    let query = view_filter.query.trim();
    let query_matches = query.is_empty()
        || symbol
            .to_ascii_uppercase()
            .contains(&query.to_ascii_uppercase());
    let watchlist_matches = !view_filter.watchlist_only || state.is_watched(symbol);
    query_matches && watchlist_matches
}

fn symbol_coverage_has_error(coverage: &SymbolCoverageEvent) -> bool {
    coverage
        .diagnostics
        .iter()
        .any(|diagnostic| diagnostic.kind == market_data::ProviderDiagnosticKind::Error)
}

fn unavailable_candidate_row(symbol: &str) -> CandidateRow {
    CandidateRow {
        symbol: symbol.to_string(),
        market_price_cents: 0,
        intrinsic_value_cents: 0,
        gap_bps: i32::MIN,
        is_qualified: false,
        confidence: ConfidenceBand::Low,
    }
}

fn filtered_symbol_rows(state: &TerminalState, view_filter: &ViewFilter) -> Vec<CandidateRow> {
    state
        .filtered_rows(state.symbol_count().max(1), view_filter)
        .into_iter()
        .filter(|row| row.confidence > ConfidenceBand::Provisional)
        .collect()
}

#[cfg(test)]
fn analyst_consensus_lines(detail: &SymbolDetail) -> Vec<String> {
    let mut lines = Vec::new();

    if let (Some(low_target_cents), Some(high_target_cents)) = (
        detail.external_signal_low_fair_value_cents,
        detail.external_signal_high_fair_value_cents,
    ) {
        lines.push(format!(
            "Target range width: {} = {} - {}",
            format_money(high_target_cents - low_target_cents),
            format_money(high_target_cents),
            format_money(low_target_cents),
        ));
    } else {
        lines.push(format!(
            "Targets: mean {}  median {}",
            format_money(detail.intrinsic_value_cents),
            format_optional_money(detail.external_signal_fair_value_cents),
        ));
    }

    lines.push(format!(
        "Analysts: {}  Recommendation mean: {} (1.00=strong buy, 5.00=strong sell)",
        format_optional_count(detail.analyst_opinion_count),
        format_optional_recommendation_mean(detail.recommendation_mean_hundredths),
    ));

    if let (
        Some(strong_buy_count),
        Some(buy_count),
        Some(hold_count),
        Some(sell_count),
        Some(strong_sell_count),
    ) = (
        detail.strong_buy_count,
        detail.buy_count,
        detail.hold_count,
        detail.sell_count,
        detail.strong_sell_count,
    ) {
        lines.push(format!(
            "Ratings: strong buy {}  buy {}  hold {}  sell {}  strong sell {}",
            strong_buy_count, buy_count, hold_count, sell_count, strong_sell_count,
        ));
    } else {
        lines.push("Ratings: recommendation breakdown not available from provider.".to_string());
    }

    lines
}

fn normalize_frame_into(
    lines: &[RenderLine],
    viewport_width: usize,
    viewport_height: usize,
    output: &mut Vec<RenderLine>,
) {
    output.clear();
    output.reserve(viewport_height.saturating_sub(output.capacity()));
    for line in lines.iter().take(viewport_height) {
        output.push(RenderLine {
            color: line.color,
            text: clip_text_to_width(&line.text, viewport_width),
        });
    }
}

#[cfg(test)]
fn normalize_frame(
    lines: &[RenderLine],
    viewport_width: usize,
    viewport_height: usize,
) -> Vec<RenderLine> {
    let mut output = Vec::with_capacity(viewport_height);
    normalize_frame_into(lines, viewport_width, viewport_height, &mut output);
    output
}

fn encode_color_marker(color: Option<Color>) -> char {
    match color {
        None => '0',
        Some(Color::Blue) => '1',
        Some(Color::Cyan) => '2',
        Some(Color::DarkCyan) => '3',
        Some(Color::DarkGrey) => '4',
        Some(Color::Green) => '5',
        Some(Color::DarkGreen) => '6',
        Some(Color::Yellow) => '7',
        Some(Color::DarkYellow) => '8',
        Some(Color::Red) => '9',
        Some(Color::DarkRed) => 'a',
        Some(Color::Magenta) => 'b',
        Some(Color::DarkMagenta) => 'c',
        Some(Color::Grey) => 'd',
        Some(Color::White) => 'e',
        Some(Color::Reset) => 'f',
        Some(Color::Black) => 'g',
        Some(Color::DarkBlue) => 'h',
        _ => '0',
    }
}

fn decode_color_marker(code: char) -> Option<Color> {
    match code {
        '0' => None,
        '1' => Some(Color::Blue),
        '2' => Some(Color::Cyan),
        '3' => Some(Color::DarkCyan),
        '4' => Some(Color::DarkGrey),
        '5' => Some(Color::Green),
        '6' => Some(Color::DarkGreen),
        '7' => Some(Color::Yellow),
        '8' => Some(Color::DarkYellow),
        '9' => Some(Color::Red),
        'a' => Some(Color::DarkRed),
        'b' => Some(Color::Magenta),
        'c' => Some(Color::DarkMagenta),
        'd' => Some(Color::Grey),
        'e' => Some(Color::White),
        'f' => Some(Color::Reset),
        'g' => Some(Color::Black),
        'h' => Some(Color::DarkBlue),
        _ => None,
    }
}

fn styled_segments_line(segments: Vec<StyledSegment>) -> RenderLine {
    let mut text = String::new();
    let mut active_color = None;
    let mut active_bg = None;

    for segment in segments {
        if segment.text.is_empty() {
            continue;
        }
        if segment.color != active_color || segment.bg_color != active_bg {
            text.push(INLINE_STYLE_MARKER);
            text.push(encode_color_marker(segment.color));
            text.push(encode_color_marker(segment.bg_color));
            active_color = segment.color;
            active_bg = segment.bg_color;
        }
        text.push_str(&segment.text);
    }

    if active_color.is_some() || active_bg.is_some() {
        text.push(INLINE_STYLE_MARKER);
        text.push(encode_color_marker(None));
        text.push(encode_color_marker(None));
    }

    RenderLine { color: None, text }
}

fn styled_cells_line(cells: &[StyledCell]) -> RenderLine {
    let mut segments = Vec::new();
    let mut current_color = None;
    let mut current_bg = None;
    let mut current_text = String::new();

    for cell in cells {
        if (cell.color != current_color || cell.bg_color != current_bg) && !current_text.is_empty()
        {
            segments.push(StyledSegment {
                color: current_color,
                bg_color: current_bg,
                text: std::mem::take(&mut current_text),
            });
        }
        current_color = cell.color;
        current_bg = cell.bg_color;
        current_text.push(cell.ch);
    }

    if !current_text.is_empty() {
        segments.push(StyledSegment {
            color: current_color,
            bg_color: current_bg,
            text: current_text,
        });
    }

    styled_segments_line(segments)
}

#[cfg(test)]
fn collect_dirty_rows(
    previous_frame: &[RenderLine],
    next_frame: &[RenderLine],
    viewport_height: usize,
) -> Vec<usize> {
    let mut dirty_rows = Vec::new();
    collect_dirty_rows_into(previous_frame, next_frame, viewport_height, &mut dirty_rows);
    dirty_rows
}

#[cfg(test)]
fn collect_dirty_rows_into(
    previous_frame: &[RenderLine],
    next_frame: &[RenderLine],
    viewport_height: usize,
    output: &mut Vec<usize>,
) {
    output.clear();
    let visible_rows = previous_frame
        .len()
        .max(next_frame.len())
        .min(viewport_height);
    for row_index in 0..visible_rows {
        if previous_frame.get(row_index) != next_frame.get(row_index) {
            output.push(row_index);
        }
    }
}

#[cfg(test)]
fn collect_clear_rows(
    next_frame_len: usize,
    last_painted_rows: usize,
    viewport_height: usize,
) -> Vec<usize> {
    let clear_end = last_painted_rows.min(viewport_height);
    (next_frame_len..clear_end).collect()
}

fn clip_text_to_width(text: &str, viewport_width: usize) -> String {
    if viewport_width == 0 {
        return String::new();
    }

    let mut clipped = String::new();
    let mut visible_chars = 0usize;
    let mut chars = text.chars();

    while let Some(ch) = chars.next() {
        if ch == INLINE_STYLE_MARKER {
            if let Some(fg_code) = chars.next() {
                clipped.push(ch);
                clipped.push(fg_code);
                if let Some(bg_code) = chars.next() {
                    clipped.push(bg_code);
                }
            }
            continue;
        }

        if visible_chars == viewport_width {
            break;
        }

        clipped.push(ch);
        visible_chars += 1;
    }

    clipped
}

#[cfg(test)]
fn visible_text(text: &str) -> String {
    let mut visible = String::new();
    let mut chars = text.chars();

    while let Some(ch) = chars.next() {
        if ch == INLINE_STYLE_MARKER {
            let _ = chars.next();
            let _ = chars.next();
            continue;
        }
        visible.push(ch);
    }

    visible
}
