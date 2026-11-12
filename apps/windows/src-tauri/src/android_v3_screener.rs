use std::sync::atomic::Ordering;

use serde::Serialize;

use crate::android_v3_charts::{read_year_charts, request_year_charts};
use crate::headless::{
    evaluate_android_v3_screener, rank_evaluations, AndroidRow, OpportunitySnapshot,
};
use crate::state::AppState;
use crate::tauri_compat::{async_runtime, State};

#[derive(Serialize)]
pub struct AndroidLongV3 {
    profile: String,
    pub(crate) rows: Vec<AndroidRow>,
    incomplete: Vec<IncompleteRow>,
    evaluated: usize,
    eligible: usize,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
enum IncompleteReason {
    NotLoaded,
    InvalidPrice,
    NoIntrinsicValue,
    ScoreError,
}

#[derive(Serialize)]
struct IncompleteRow {
    symbol: String,
    company: Option<String>,
    reason: IncompleteReason,
}

fn eligible_equities(symbols: &[String]) -> Vec<String> {
    symbols
        .iter()
        .filter(|symbol| crate::fetcher::asset_type(symbol) == "stock")
        .cloned()
        .collect()
}

fn capture_live_ranking(
    state: &AppState,
    expected_profile: &str,
) -> Result<(u64, Vec<String>, OpportunitySnapshot), String> {
    let _profile_guard = state
        .profile_apply_gate
        .lock()
        .map_err(|_| "Feed state is unavailable")?;
    let profile = state
        .active_profile
        .lock()
        .map_err(|_| "Feed state is unavailable")?
        .clone();
    if crate::profiles::resolve_profile_name(expected_profile) != Some(profile.as_str()) {
        return Err("Universe profile changed".into());
    }
    let generation = state.feed_generation.load(Ordering::SeqCst);
    let symbols = state
        .active_symbols
        .lock()
        .map_err(|_| "Feed state is unavailable")?
        .clone();
    let universe = eligible_equities(&symbols);
    let year = read_year_charts(state, generation);
    let screener = state
        .screener
        .lock()
        .map_err(|_| "Feed state is unavailable")?;
    let snapshot =
        OpportunitySnapshot::capture_android_equities(&screener, &profile, &universe, &year);
    Ok((generation, universe, snapshot))
}

pub(crate) fn rank_snapshot_for_screener(
    snapshot: &OpportunitySnapshot,
    universe: &[String],
) -> AndroidLongV3 {
    let evaluations = evaluate_android_v3_screener(snapshot, universe);
    let evaluated = evaluations
        .iter()
        .filter(|evaluation| evaluation.snapshot_index.is_some())
        .count();
    let rows = rank_evaluations(&evaluations);
    let mut incomplete: Vec<_> = evaluations
        .into_iter()
        .filter_map(|evaluation| {
            let reason = match evaluation.reason.as_str() {
                "not_loaded" => IncompleteReason::NotLoaded,
                "invalid_price" => IncompleteReason::InvalidPrice,
                "no_intrinsic_value" => IncompleteReason::NoIntrinsicValue,
                reason if reason.starts_with("score_error:") => IncompleteReason::ScoreError,
                _ => return None,
            };
            Some(IncompleteRow {
                symbol: evaluation.symbol,
                company: evaluation.row.and_then(|row| row.company),
                reason,
            })
        })
        .collect();
    incomplete.sort_by(|a, b| a.symbol.cmp(&b.symbol));
    AndroidLongV3 {
        profile: snapshot.profile.clone(),
        rows,
        incomplete,
        evaluated,
        eligible: universe.len(),
    }
}

#[tauri::command]
pub async fn get_android_long_v3(
    profile: String,
    state: State<'_, AppState>,
) -> Result<AndroidLongV3, String> {
    let (generation, universe, snapshot) = capture_live_ranking(&state, &profile)?;
    let result =
        async_runtime::spawn_blocking(move || rank_snapshot_for_screener(&snapshot, &universe))
            .await
            .map_err(|error| error.to_string())?;
    if state.feed_generation.load(Ordering::SeqCst) != generation {
        return Err("Universe profile changed".into());
    }
    request_year_charts(
        &state,
        generation,
        result.rows.iter().map(|row| row.symbol.clone()).collect(),
    );
    Ok(result)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::engine::{FundamentalSnapshot, MarketSnapshot, ScreenerState};

    fn quote(symbol: &str, price: i64, intrinsic: i64, profitable: bool) -> MarketSnapshot {
        MarketSnapshot {
            symbol: symbol.into(),
            company_name: Some(format!("{symbol} Inc.")),
            profitable,
            market_price_cents: price,
            intrinsic_value_cents: intrinsic,
            previous_close_cents: price,
            next_earnings_epoch: None,
        }
    }

    #[test]
    fn shows_ranked_and_incomplete_rows_without_promoting_disqualified_quotes() {
        let mut screener = ScreenerState::new();
        for (symbol, price, intrinsic, profitable) in [
            ("CUSTOM", 1_000, 2_000, true),
            ("NOANCHOR", 1_000, 0, true),
            ("BADPRICE", 0, 2_000, true),
            ("SMALLGAP", 1_900, 2_000, true),
            ("UNPROFIT", 1_000, 2_000, false),
            ("SPY", 1_000, 2_000, true),
            ("BTC-USD", 1_000, 2_000, true),
            ("ADHOC", 1_000, 2_000, true),
        ] {
            screener.ingest_snapshot(quote(symbol, price, intrinsic, profitable));
        }
        screener.ingest_fundamentals(FundamentalSnapshot {
            symbol: "CUSTOM".into(),
            sector_name: Some("Technology".into()),
            industry_name: Some("Software - Application".into()),
            ..Default::default()
        });
        let universe = eligible_equities(
            &[
                "CUSTOM", "NOANCHOR", "BADPRICE", "SMALLGAP", "UNPROFIT", "UNLOADED", "SPY",
                "BTC-USD",
            ]
            .map(str::to_owned),
        );
        let snapshot = OpportunitySnapshot::capture_android_equities(
            &screener,
            "sp500",
            &universe,
            &Default::default(),
        );
        let ranked = rank_snapshot_for_screener(&snapshot, &universe);
        assert_eq!(ranked.profile, "sp500");
        assert_eq!(ranked.eligible, 6);
        assert_eq!(ranked.evaluated, 5);
        assert_eq!(ranked.rows.len(), 1);
        assert_eq!(ranked.rows[0].symbol, "CUSTOM");
        assert_eq!(ranked.rows[0].technical, None);
        assert_eq!(
            ranked.rows[0].projection_gap.as_deref(),
            Some("identity_value_not_computed_headless")
        );
        assert_eq!(ranked.rows[0].anchor_cents, None);
        assert_eq!(ranked.rows[0].upside_bps, None);
        assert!(ranked.rows[0].shadow.is_none());
        assert_eq!(
            ranked
                .incomplete
                .iter()
                .map(|row| (row.symbol.as_str(), row.reason))
                .collect::<Vec<_>>(),
            vec![
                ("BADPRICE", IncompleteReason::InvalidPrice),
                ("NOANCHOR", IncompleteReason::NoIntrinsicValue),
                ("UNLOADED", IncompleteReason::NotLoaded),
            ],
        );
    }

    #[test]
    fn capture_leaves_native_model_and_valuation_cache_untouched() {
        let mut screener = ScreenerState::new();
        screener.scoring_model = "short_v3".into();
        screener.ingest_snapshot(quote("UNCLASSIFIED", 1_000, 2_000, true));
        screener.ingest_fundamentals(FundamentalSnapshot {
            symbol: "UNCLASSIFIED".into(),
            ..Default::default()
        });
        screener.dcf_values.insert("UNCLASSIFIED".into(), 99_000);
        let universe = ["UNCLASSIFIED".to_string()];
        let snapshot = OpportunitySnapshot::capture_android_equities(
            &screener,
            "sp500",
            &universe,
            &Default::default(),
        );
        let result = rank_snapshot_for_screener(&snapshot, &universe);
        assert_eq!(result.rows[0].symbol, "UNCLASSIFIED");
        assert_eq!(result.rows[0].anchor_cents, None);
        assert_eq!(screener.scoring_model, "short_v3");
        assert_eq!(screener.dcf_values.get("UNCLASSIFIED"), Some(&99_000));
    }
}
