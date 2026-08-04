//! High-signal screener cohort gate (product goal).
//!
//! **Goal:** every pinned visible-screener name earns a solid, scale-coherent
//! forecast. The integration test **recomputes** the cohort (Yahoo + SEC +
//! live US 10Y) and fails until all 26 pass — not a frozen soft snapshot.
//!
//! - Street is an **external diagnostic** (disagreement band), never a clamp target.
//! - Forbidden: ticker special cases, min-WACC-as-truth, price caps as valuation truth.
//! - Contract: `shared/contracts/valuation-high-signal-screener-cohort-v1.json`

#![cfg(test)]

use serde::Deserialize;
use std::collections::BTreeSet;
use std::path::PathBuf;

const CONTRACT: &str = "../../../shared/contracts/valuation-high-signal-screener-cohort-v1.json";
const OBSERVATION_FIXTURE: &str =
    "tests/fixtures/valuation/high_signal_screener_observation_2026-08-02.json";

#[derive(Debug, Deserialize)]
struct ContractFile {
    high_signal_criteria: HighSignalCriteriaDto,
    cohort: CohortDto,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct HighSignalCriteriaDto {
    version: String,
    #[allow(dead_code)]
    must_be_available: bool,
    #[allow(dead_code)]
    must_be_solid_quality: bool,
    #[allow(dead_code)]
    point_estimate_unreliable_forbidden: bool,
    #[allow(dead_code)]
    provisional_rates_forbidden: bool,
    #[allow(dead_code)]
    require_positive_base_cents: bool,
    #[allow(dead_code)]
    require_ordered_scenarios_when_present: bool,
    max_base_over_market_multiple: f64,
    min_base_over_market_multiple: f64,
    min_market_price_cents_for_oom_check: i64,
    financials_require_residual_income: bool,
    max_street_relative_disagreement_bps: i32,
}

#[derive(Debug, Deserialize)]
struct CohortDto {
    symbols: Vec<String>,
}

/// Pinned observation of one published forecast (fixture audit or live recompute).
#[derive(Debug, Clone, Deserialize, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ForecastObservation {
    pub symbol: String,
    #[serde(default)]
    pub market_price_cents: Option<i64>,
    #[serde(default)]
    pub street_target_cents: Option<i64>,
    #[serde(default)]
    pub our_base_cents: Option<i64>,
    #[serde(default)]
    pub bear_cents: Option<i64>,
    #[serde(default)]
    pub bull_cents: Option<i64>,
    #[serde(default)]
    pub model: Option<String>,
    #[serde(default)]
    pub business_class: Option<String>,
    #[serde(default)]
    pub valuation_status: Option<String>,
    #[serde(default)]
    pub valuation_unavailable_reason: Option<String>,
    /// When true, UI must not present the base as trusted point truth.
    #[serde(default)]
    pub point_estimate_unreliable: bool,
    /// Soft WACC / default CoD·tax·beta / provisional market-params path.
    #[serde(default)]
    pub provisional_rates: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub discount_rate_bps: Option<i32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub notes: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HighSignalVerdict {
    pub symbol: String,
    pub high_signal: bool,
    pub failures: Vec<&'static str>,
}

/// Versioned high-signal policy (mirrors shared contract defaults).
#[derive(Debug, Clone)]
pub struct HighSignalCriteria {
    pub max_base_over_market_multiple: f64,
    pub min_base_over_market_multiple: f64,
    pub min_market_price_cents_for_oom_check: i64,
    pub max_street_relative_disagreement_bps: i32,
    pub financials_require_residual_income: bool,
}

impl Default for HighSignalCriteria {
    fn default() -> Self {
        Self {
            max_base_over_market_multiple: 3.0,
            min_base_over_market_multiple: 0.10,
            min_market_price_cents_for_oom_check: 500,
            max_street_relative_disagreement_bps: 3_500,
            financials_require_residual_income: true,
        }
    }
}

impl From<&HighSignalCriteriaDto> for HighSignalCriteria {
    fn from(dto: &HighSignalCriteriaDto) -> Self {
        Self {
            max_base_over_market_multiple: dto.max_base_over_market_multiple,
            min_base_over_market_multiple: dto.min_base_over_market_multiple,
            min_market_price_cents_for_oom_check: dto.min_market_price_cents_for_oom_check,
            max_street_relative_disagreement_bps: dto.max_street_relative_disagreement_bps,
            financials_require_residual_income: dto.financials_require_residual_income,
        }
    }
}

/// Assess one published forecast against the high-signal bar.
pub fn assess_high_signal(
    obs: &ForecastObservation,
    criteria: &HighSignalCriteria,
) -> HighSignalVerdict {
    let mut failures = Vec::new();
    let symbol = obs.symbol.clone();

    let unavailable = obs
        .valuation_status
        .as_deref()
        .is_some_and(|s| s == "unavailable" || s == "not_eligible")
        || obs.valuation_unavailable_reason.is_some();

    let base = obs.our_base_cents.unwrap_or(0);
    let model = obs.model.as_deref().unwrap_or("");
    let model_none = model.is_empty() || model == "none" || model == "—";

    if unavailable || base <= 0 || model_none {
        failures.push("unavailable_or_non_positive_base");
    }

    // Solid quality — soft / provisional / unknown reliability cannot be high-signal.
    if obs.point_estimate_unreliable {
        failures.push("point_estimate_unreliable");
    }
    if obs.provisional_rates {
        failures.push("provisional_rates");
    }

    // Scenario order when both wings present and positive.
    if let (Some(bear), Some(bull)) = (obs.bear_cents, obs.bull_cents) {
        if bear > 0 && bull > 0 && base > 0 && !(bear <= base && base <= bull) {
            failures.push("scenario_order_inverted");
        }
    }

    // Business-class routing.
    let class = obs.business_class.as_deref().unwrap_or("");
    if criteria.financials_require_residual_income
        && class == "financial_services"
        && model != "residual_income_equity"
        && !model_none
        && base > 0
    {
        failures.push("financials_not_residual_income");
    }
    if class == "operating_non_financial" && model == "residual_income_equity" && base > 0 {
        failures.push("operating_on_residual_income");
    }

    // Order-of-magnitude vs market (sanity, not a Street clamp).
    if let Some(mkt) = obs.market_price_cents {
        if mkt >= criteria.min_market_price_cents_for_oom_check && base > 0 {
            let max_base = (mkt as f64 * criteria.max_base_over_market_multiple) as i64;
            let min_base = (mkt as f64 * criteria.min_base_over_market_multiple) as i64;
            if base > max_base {
                failures.push("oom_base_above_market_multiple");
            }
            if base < min_base {
                failures.push("oom_base_below_market_multiple");
            }
        }
    }

    // Street band: diagnostic only, but high-signal primary still requires
    // disagreement within policy (honest Disputed with soft model is not enough).
    if let (Some(street), true) = (obs.street_target_cents, base > 0) {
        if street > 0 {
            let rel = ((base as f64 / street as f64) - 1.0).abs() * 10_000.0;
            let bps = rel.round() as i32;
            if bps > criteria.max_street_relative_disagreement_bps {
                failures.push("street_disagreement_exceeds_high_signal_band");
            }
        }
    }

    HighSignalVerdict {
        symbol,
        high_signal: failures.is_empty(),
        failures,
    }
}

fn contract_path() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join(CONTRACT)
}

fn observation_path() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join(OBSERVATION_FIXTURE)
}

fn load_contract() -> ContractFile {
    let raw = std::fs::read_to_string(contract_path())
        .unwrap_or_else(|e| panic!("read high-signal contract: {e}"));
    #[derive(Deserialize)]
    struct Wire {
        #[serde(rename = "highSignalCriteria")]
        high_signal_criteria: HighSignalCriteriaDto,
        cohort: CohortDto,
    }
    let wire: Wire = serde_json::from_str(&raw).expect("parse high-signal contract");
    ContractFile {
        high_signal_criteria: wire.high_signal_criteria,
        cohort: wire.cohort,
    }
}

fn business_class_label(class: crate::dcf_model::BusinessClass) -> String {
    match class {
        crate::dcf_model::BusinessClass::OperatingNonFinancial => "operating_non_financial".into(),
        crate::dcf_model::BusinessClass::FinancialServices => "financial_services".into(),
        crate::dcf_model::BusinessClass::NotEligible => "not_eligible".into(),
        crate::dcf_model::BusinessClass::Unclassified => "unclassified".into(),
    }
}

fn model_label(model: crate::dcf_model::ValuationModel) -> String {
    match model {
        crate::dcf_model::ValuationModel::FcffWacc => "fcff_wacc".into(),
        crate::dcf_model::ValuationModel::ResidualIncomeEquity => "residual_income_equity".into(),
        crate::dcf_model::ValuationModel::None => "none".into(),
    }
}

fn resolve_market_params_for_gate(
    yahoo: &crate::fetcher::YahooClient,
) -> crate::dcf_model::MarketParams {
    if let Some((rf_bps, as_of)) = yahoo.fetch_us_10y_yield_bps() {
        eprintln!("high_signal_market_params live_rf_bps={rf_bps} as_of={as_of}");
        return crate::dcf_model::MarketParams::from_live_risk_free(rf_bps, as_of);
    }
    eprintln!("high_signal_market_params FALLBACK provisional defaults (live rf unavailable)");
    crate::dcf_model::MarketParams::default_usd()
}

fn as_of_epoch_day() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| (d.as_secs() / 86_400) as i64)
        .unwrap_or(0)
}

fn annotate_scale(obs: &mut ForecastObservation) {
    let mut notes = obs.notes.take().unwrap_or_default();
    if !notes.is_empty() {
        notes.push_str("; ");
    }
    if let (Some(base), Some(mkt)) = (obs.our_base_cents, obs.market_price_cents) {
        if mkt > 0 {
            notes.push_str(&format!("vs_mkt×{:.2}", base as f64 / mkt as f64));
        }
    }
    if let (Some(base), Some(st)) = (obs.our_base_cents, obs.street_target_cents) {
        if st > 0 {
            if notes.contains("vs_mkt") {
                notes.push_str("; ");
            }
            notes.push_str(&format!("vs_street×{:.2}", base as f64 / st as f64));
        }
    }
    obs.notes = if notes.is_empty() { None } else { Some(notes) };
}

/// Recompute one cohort member through the same path Detail uses:
/// Yahoo fundamentals + SEC FCFF + live market params + operating-valuation router
/// (FCFF / forward-earnings selection) or residual income for financials.
fn recompute_member(
    symbol: &str,
    yahoo: &crate::fetcher::YahooClient,
    edgar_client: &reqwest::blocking::Client,
    cik_map: &std::collections::HashMap<String, u64>,
    market_params: &crate::dcf_model::MarketParams,
) -> ForecastObservation {
    let mut obs = ForecastObservation {
        symbol: symbol.into(),
        market_price_cents: None,
        street_target_cents: None,
        our_base_cents: None,
        bear_cents: None,
        bull_cents: None,
        model: None,
        business_class: None,
        valuation_status: None,
        valuation_unavailable_reason: None,
        point_estimate_unreliable: true,
        provisional_rates: true,
        discount_rate_bps: None,
        notes: None,
    };

    let fetched = match yahoo.fetch_symbol(symbol) {
        Ok(f) => f,
        Err(e) => {
            obs.valuation_unavailable_reason = Some(format!("yahoo:{e}"));
            obs.valuation_status = Some("unavailable".into());
            obs.notes = Some("yahoo_fetch_failed".into());
            return obs;
        }
    };

    if let Some(snap) = &fetched.snapshot {
        obs.market_price_cents = Some(snap.market_price_cents);
        let street = snap.intrinsic_value_cents;
        obs.street_target_cents = if street > 0 { Some(street) } else { None };
    }

    let Some(mut fund) = fetched.fundamentals else {
        obs.valuation_unavailable_reason = Some("missing_fundamentals".into());
        obs.valuation_status = Some("unavailable".into());
        obs.notes = Some("missing_fundamentals".into());
        return obs;
    };

    let Some(&cik) = cik_map.get(symbol) else {
        obs.valuation_unavailable_reason = Some("missing_cik".into());
        obs.valuation_status = Some("unavailable".into());
        obs.notes = Some("missing_cik".into());
        return obs;
    };

    if fund.shares_outstanding.unwrap_or(0) == 0 {
        fund.shares_outstanding =
            crate::edgar::fetch_shares_outstanding(edgar_client, symbol, cik).unwrap_or(None);
    }

    let class = crate::dcf_model::classify_business(
        fund.sector_name.as_deref(),
        fund.industry_name.as_deref(),
        fund.sector_key.as_deref(),
        fund.industry_key.as_deref(),
        false,
    );
    obs.business_class = Some(business_class_label(class));
    let day = as_of_epoch_day();

    match class {
        crate::dcf_model::BusinessClass::FinancialServices => {
            match crate::dcf_model::compute_with_params(
                &fund,
                &[],
                obs.market_price_cents,
                market_params,
                "high_signal_recompute",
                false,
            ) {
                Ok(a) => {
                    obs.our_base_cents = Some(a.base_intrinsic_value_cents);
                    obs.bear_cents = Some(a.bear_intrinsic_value_cents);
                    obs.bull_cents = Some(a.bull_intrinsic_value_cents);
                    obs.model = Some(model_label(a.model));
                    let rates_soft = a.diagnostics.point_estimate_unreliable
                        || a.wacc_inputs.point_estimate_unreliable();
                    obs.point_estimate_unreliable = rates_soft;
                    obs.provisional_rates = rates_soft || market_params.provisional;
                    obs.discount_rate_bps = Some(a.wacc_bps);
                    obs.valuation_status = Some("selected".into());
                    obs.notes = Some(format!(
                        "{}; {}; {}; r={:.2}%",
                        model_label(a.model),
                        business_class_label(a.business_class),
                        if rates_soft {
                            "soft_rates"
                        } else {
                            "solid_rates"
                        },
                        a.wacc_bps as f64 / 100.0
                    ));
                }
                Err(e) => {
                    obs.valuation_unavailable_reason = Some(e.clone());
                    obs.valuation_status = Some("unavailable".into());
                    obs.notes = Some(format!("ri_compute:{e}"));
                }
            }
            annotate_scale(&mut obs);
            return obs;
        }
        crate::dcf_model::BusinessClass::NotEligible
        | crate::dcf_model::BusinessClass::Unclassified => {
            obs.valuation_unavailable_reason = Some(
                crate::dcf_model::classification_unavailable_reason(class)
                    .unwrap_or("class_refused")
                    .into(),
            );
            obs.valuation_status = Some("unavailable".into());
            obs.notes = Some("class_refused".into());
            return obs;
        }
        crate::dcf_model::BusinessClass::OperatingNonFinancial => {}
    }

    let mut fcff_failure: Option<String> = None;
    let mut analysis: Option<crate::dcf_model::DcfAnalysis> = None;
    match crate::edgar::fetch_fcf_history(edgar_client, symbol, cik) {
        Ok(Some(history)) => {
            match crate::dcf_model::compute_with_params(
                &fund,
                &history,
                obs.market_price_cents,
                market_params,
                "high_signal_recompute",
                false,
            ) {
                Ok(a) => analysis = Some(a),
                Err(e) => fcff_failure = Some(format!("fcff_compute:{e}")),
            }
        }
        Ok(None) => fcff_failure = Some("missing_fcf_history".into()),
        Err(e) => fcff_failure = Some(format!("fcf_fetch:{e}")),
    }

    let forward_evidence = yahoo
        .fetch_forward_forecast(symbol, day)
        .map_err(|error| match error {
            crate::fetcher::ForwardForecastFetchError::Provider(reason) => {
                crate::operating_valuation_runtime::ForwardSourceFailure::Provider(reason)
            }
            crate::fetcher::ForwardForecastFetchError::Transport(error)
                if crate::yahoo_session::is_rate_limit_error(&error) =>
            {
                crate::operating_valuation_runtime::ForwardSourceFailure::RateLimited
            }
            crate::fetcher::ForwardForecastFetchError::Transport(_) => {
                crate::operating_valuation_runtime::ForwardSourceFailure::Transport
            }
        });

    let envelope = crate::operating_valuation_runtime::route_runtime_valuation(
        crate::operating_valuation_runtime::RuntimeValuationInput {
            business_class: class,
            fundamentals: &fund,
            fcff_analysis: analysis.as_ref(),
            fcff_failure: fcff_failure.as_deref(),
            forward_evidence,
            market_params,
            as_of_epoch_day: day,
            market_price_cents: obs.market_price_cents,
        },
    );

    use crate::operating_valuation::{ModelQuality, OperatingModel, RouteStatus};
    let decision = &envelope.decision;
    match decision.status {
        RouteStatus::Selected => {
            obs.our_base_cents = decision.selected_value_cents;
            obs.valuation_status = Some("selected".into());
            obs.model = Some(match decision.selected_model {
                Some(OperatingModel::FcffWacc) => "fcff_wacc".into(),
                Some(OperatingModel::ForwardEarningsPower) => "forward_earnings_power".into(),
                None => "none".into(),
            });
            let quality = match decision.selected_model {
                Some(OperatingModel::FcffWacc) => decision.fcff_candidate.quality,
                Some(OperatingModel::ForwardEarningsPower) => decision.forward_candidate.quality,
                None => ModelQuality::Soft,
            };
            let soft = quality != ModelQuality::Solid;
            obs.point_estimate_unreliable = soft;
            obs.provisional_rates = soft || market_params.provisional;
            // Scenarios belong only to the selected FCFF path — never mix FCFF
            // wings with a forward base (false scenario_order_inverted).
            if decision.selected_model == Some(OperatingModel::FcffWacc) {
                if let Some(a) = analysis.as_ref() {
                    obs.bear_cents = Some(a.bear_intrinsic_value_cents);
                    obs.bull_cents = Some(a.bull_intrinsic_value_cents);
                    obs.discount_rate_bps = Some(a.wacc_bps);
                }
            } else if decision.forward_candidate.cost_of_equity_bps > 0 {
                obs.discount_rate_bps = Some(decision.forward_candidate.cost_of_equity_bps);
            }
            obs.notes = Some(format!(
                "{}; operating_non_financial; {}; router={}",
                obs.model.as_deref().unwrap_or("none"),
                if soft { "soft_rates" } else { "solid_rates" },
                envelope.diagnostics.runtime_policy_version
            ));
        }
        RouteStatus::Disputed => {
            // Disputed is still not high-signal — the lanes genuinely disagree, so
            // `point_estimate_unreliable` stays set and the name keeps failing the
            // gate. What changed is which number is reported: the router resolves
            // the dispute to the better-evidenced lane, and this observation now
            // reads that resolution instead of re-deriving its own `fcff.or(fwd)`
            // preference, which silently contradicted the router.
            let fcff = decision.fcff_candidate.intrinsic_value_cents;
            let fwd = decision.forward_candidate.intrinsic_value_cents;
            obs.our_base_cents = decision.selected_value_cents.or(fcff).or(fwd);
            obs.valuation_status = Some("disputed".into());
            obs.model = Some("disputed".into());
            obs.point_estimate_unreliable = true;
            obs.provisional_rates = true;
            obs.valuation_unavailable_reason = Some("disputed_candidates".into());
            if decision.forward_candidate.cost_of_equity_bps > 0 {
                obs.discount_rate_bps = Some(decision.forward_candidate.cost_of_equity_bps);
            }
            obs.notes = Some(format!(
                "disputed; resolved={:?}; fcff={fcff:?}; forward={fwd:?}; diff_bps={:?}",
                decision.selected_model, decision.candidate_difference_bps
            ));
        }
        RouteStatus::Unavailable | RouteStatus::NotEligible => {
            obs.valuation_status = Some(match decision.status {
                RouteStatus::NotEligible => "not_eligible".into(),
                _ => "unavailable".into(),
            });
            obs.valuation_unavailable_reason = Some(
                fcff_failure
                    .clone()
                    .unwrap_or_else(|| format!("route:{:?}", decision.reasons)),
            );
            obs.notes = Some(format!("route_unavailable; {:?}", decision.reasons));
        }
    }
    annotate_scale(&mut obs);
    obs
}

fn recompute_cohort(
    symbols: &[String],
) -> (crate::dcf_model::MarketParams, Vec<ForecastObservation>) {
    let yahoo = crate::fetcher::YahooClient::new().expect("Yahoo client");
    let edgar_client = crate::edgar::edgar_client();
    let cik_map = crate::edgar::fetch_cik_map(&edgar_client).expect("SEC CIK map");
    let market_params = resolve_market_params_for_gate(&yahoo);
    let members: Vec<_> = symbols
        .iter()
        .map(|symbol| recompute_member(symbol, &yahoo, &edgar_client, &cik_map, &market_params))
        .collect();
    (market_params, members)
}

fn write_observation_audit(members: &[ForecastObservation], capture_id: &str) {
    let path = observation_path();
    let body = serde_json::json!({
        "contract": "valuation-high-signal-screener-cohort-v1",
        "captureId": capture_id,
        "capturedAt": chrono_like_now(),
        "feedProfile": "sp500-cohort-recompute",
        "source": "Windows recompute: Yahoo fundamentals + SEC FCFF drivers + live US 10Y when available. High-signal goal gate — not a soft freeze.",
        "members": members,
    });
    if let Err(e) = std::fs::write(
        &path,
        serde_json::to_string_pretty(&body).expect("serialize observation"),
    ) {
        eprintln!(
            "warn: could not write observation audit {}: {e}",
            path.display()
        );
    } else {
        eprintln!("wrote high-signal observation audit → {}", path.display());
    }
}

fn chrono_like_now() -> String {
    let secs = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    format!("unix:{secs}")
}

/// Synthetic solid observation — proves the bar is reachable without ticker hacks.
fn solid_example(symbol: &str) -> ForecastObservation {
    ForecastObservation {
        symbol: symbol.into(),
        market_price_cents: Some(10_000),
        street_target_cents: Some(12_000),
        our_base_cents: Some(11_500),
        bear_cents: Some(9_000),
        bull_cents: Some(14_000),
        model: Some("fcff_wacc".into()),
        business_class: Some("operating_non_financial".into()),
        valuation_status: Some("selected".into()),
        valuation_unavailable_reason: None,
        point_estimate_unreliable: false,
        provisional_rates: false,
        discount_rate_bps: Some(900),
        notes: None,
    }
}

#[test]
fn high_signal_criteria_accept_solid_synthetic() {
    let v = assess_high_signal(&solid_example("SYN"), &HighSignalCriteria::default());
    assert!(v.high_signal, "solid synthetic must pass: {:?}", v.failures);
}

#[test]
fn high_signal_criteria_reject_soft_and_oom() {
    let criteria = HighSignalCriteria::default();
    let soft = ForecastObservation {
        point_estimate_unreliable: true,
        provisional_rates: true,
        our_base_cents: Some(11_000),
        market_price_cents: Some(10_000),
        street_target_cents: Some(12_000),
        model: Some("fcff_wacc".into()),
        business_class: Some("operating_non_financial".into()),
        valuation_status: Some("selected".into()),
        ..solid_example("SOFT")
    };
    let soft_v = assess_high_signal(&soft, &criteria);
    assert!(!soft_v.high_signal);
    assert!(soft_v.failures.contains(&"point_estimate_unreliable"));
    assert!(soft_v.failures.contains(&"provisional_rates"));

    let oom = ForecastObservation {
        our_base_cents: Some(50_000), // 5× market
        market_price_cents: Some(10_000),
        street_target_cents: Some(12_000),
        point_estimate_unreliable: false,
        provisional_rates: false,
        model: Some("fcff_wacc".into()),
        business_class: Some("operating_non_financial".into()),
        valuation_status: Some("selected".into()),
        bear_cents: Some(40_000),
        bull_cents: Some(60_000),
        symbol: "OOM".into(),
        valuation_unavailable_reason: None,
        discount_rate_bps: Some(900),
        notes: None,
    };
    let oom_v = assess_high_signal(&oom, &criteria);
    assert!(!oom_v.high_signal);
    assert!(oom_v.failures.contains(&"oom_base_above_market_multiple"));
}

#[test]
fn high_signal_contract_cohort_is_exactly_twenty_six_pinned_symbols() {
    let contract = load_contract();
    assert_eq!(contract.cohort.symbols.len(), 26);
    assert_eq!(
        contract.high_signal_criteria.version,
        "high-signal-criteria/1"
    );
    let expected: BTreeSet<_> = [
        "DVN", "FIS", "AVY", "SW", "COF", "MPWR", "APH", "EME", "CHTR", "BKR", "INTU", "TER",
        "AVGO", "EPAM", "T", "GEHC", "DAL", "WDC", "GOOGL", "GOOG", "HPE", "CRM", "SLB", "EXE",
        "OMC", "PTC",
    ]
    .into_iter()
    .map(str::to_string)
    .collect();
    let actual: BTreeSet<_> = contract.cohort.symbols.iter().cloned().collect();
    assert_eq!(actual, expected);
}

/// **Goal integration gate.**
///
/// Recomputes every pinned screener cohort member with live market inputs and
/// fails until each is high-signal under the shared contract. Soft provisional
/// defaults are not the goal state — live US 10Y + SEC drivers must earn solid
/// quality; scale and Street band must hold without clamps.
///
/// Writes `high_signal_screener_observation_*.json` as an audit pin after each run.
#[test]
fn high_signal_screener_cohort_all_members_pass() {
    let contract = load_contract();
    let criteria = HighSignalCriteria::from(&contract.high_signal_criteria);
    let (market_params, members) = recompute_cohort(&contract.cohort.symbols);

    write_observation_audit(
        &members,
        &format!(
            "recompute-{}",
            if market_params.provisional {
                "provisional-fallback"
            } else {
                "live-rf"
            }
        ),
    );

    assert!(
        !market_params.provisional,
        "high-signal cohort requires live US 10Y market params (got provisional bootstrap). \
         Fix Yahoo ^TNX fetch or network before claiming solid quality."
    );

    let mut failures = Vec::new();
    let mut pass = 0usize;
    for symbol in &contract.cohort.symbols {
        let obs = members
            .iter()
            .find(|m| &m.symbol == symbol)
            .unwrap_or_else(|| panic!("missing recompute for {symbol}"));
        let verdict = assess_high_signal(obs, &criteria);
        if verdict.high_signal {
            pass += 1;
            eprintln!(
                "HIGH_SIGNAL_OK {} base={:?} street={:?} model={:?} notes={:?}",
                symbol, obs.our_base_cents, obs.street_target_cents, obs.model, obs.notes
            );
        } else {
            failures.push(format!(
                "{symbol}: {} | model={:?} class={:?} base={:?} mkt={:?} street={:?} unreliable={} provisional={} notes={:?}",
                verdict.failures.join(","),
                obs.model,
                obs.business_class,
                obs.our_base_cents,
                obs.market_price_cents,
                obs.street_target_cents,
                obs.point_estimate_unreliable,
                obs.provisional_rates,
                obs.notes
            ));
            eprintln!("HIGH_SIGNAL_FAIL {symbol}: {}", verdict.failures.join(","));
        }
    }

    eprintln!(
        "high_signal_cohort pass={}/{} criteria={} rf_bps={} provisional={}",
        pass,
        contract.cohort.symbols.len(),
        contract.high_signal_criteria.version,
        market_params.rf_bps,
        market_params.provisional
    );

    assert!(
        failures.is_empty(),
        "High-signal screener cohort NOT met ({pass}/{}). \
         Goal: solid quality, scale-coherent, correct class, Street disagreement ≤ {} bps. \
         Do not clamp to price/Street. Failures:\n{}",
        contract.cohort.symbols.len(),
        criteria.max_street_relative_disagreement_bps,
        failures.join("\n")
    );
}

/// Return-on-capital capture for the durable router validation cohort.
///
/// `operating-valuation-router-v1.json` freezes EPS, growth and cost of equity
/// per name but carried no return on capital, so the durable rows fell to the
/// evidence-free default and charged a 30%-return compounder the same retention
/// as a 5%-return levered network. Prints the values needed to make those rows
/// exercise the production path.
///
/// Run: `cargo test --lib durable_cohort_return_on_capital_capture -- --ignored --nocapture`
#[test]
#[ignore = "network: Yahoo fundamentals capture for contract maintenance"]
fn durable_cohort_return_on_capital_capture() {
    let yahoo = crate::fetcher::YahooClient::new().expect("Yahoo client");
    for symbol in [
        "DVN", "GDDY", "WYNN", "SNDK", "BR", "BSX", "AMZN", "AVGO", "HPE", "MU", "ORCL", "AAPL",
        "CPRT", "CEG", "ALB", "T", "MSFT", "NVDA", "JNJ", "XOM", "V", "WMT", "GOOGL", "META", "HD",
        "PG", "MRK",
    ] {
        let roic = yahoo
            .fetch_symbol(symbol)
            .ok()
            .and_then(|fetched| fetched.fundamentals)
            .map(|fund| {
                (
                    fund.return_on_equity_bps,
                    fund.debt_to_equity_hundredths,
                    crate::operating_valuation_runtime::return_on_capital_bps(&fund, None),
                )
            });
        eprintln!("ROIC_CAPTURE {symbol} {roic:?}");
    }
}

/// **Forward-lane decomposition audit.**
///
/// `project_forward_value` capitalizes the full analyst EPS every year and in
/// the Gordon terminal while granting growth. Nothing in the forward lane
/// charges retention, so growth is free — the same "growth not earned" defect
/// the FCFF lane had before policy /16, but on the lane the router actually
/// selects for most operating names.
///
/// Prints, per cohort name, the raw forward inputs and what the value would be
/// if growth had to be funded out of earnings at the issuer's return on
/// capital. Reports only — never asserts a Street band.
///
/// Run: `cargo test --lib forward_lane_retention_decomposition_audit -- --ignored --nocapture`
#[test]
#[ignore = "network: Yahoo + SEC + live rates; diagnostic review only"]
fn forward_lane_retention_decomposition_audit() {
    let contract = load_contract();
    let yahoo = crate::fetcher::YahooClient::new().expect("Yahoo client");
    let edgar_client = crate::edgar::edgar_client();
    let cik_map = crate::edgar::fetch_cik_map(&edgar_client).expect("SEC CIK map");
    let market_params = resolve_market_params_for_gate(&yahoo);
    let day = as_of_epoch_day();

    eprintln!(
        "{:<6} {:>7} {:>7} {:>6} {:>6} {:>4} {:>4} {:>5} {:>5} {:>6} {:>7} {:>6} {:>6} {:>6} {:>6} {:>7} {:>7} {:>8} {:>8}",
        "sym",
        "mkt",
        "street",
        "eps",
        "g0",
        "hold",
        "fade",
        "r",
        "g_inf",
        "roe",
        "d/e",
        "roic",
        "b_co",
        "b_ind",
        "b_shr",
        "revG",
        "epsG",
        "A_now",
        "E_flterm"
    );

    // The four anchors are not gate members, but every growth-policy change has
    // to be read against them before it counts as validated. PG holds the seat
    // AAPL used to: the one anchor with `owner_earnings = false` and an engine
    // level equal to the reported one, so it controls for the owner-earnings
    // adjustment the other three all receive. AAPL itself is excluded — a 10%
    // single-session drawdown makes its street target a poor validation target,
    // and calibrating against that volatility is exactly backwards.
    let anchors = ["PG", "GOOGL", "AMZN", "MSFT"].map(String::from);
    let audited: Vec<&String> = contract
        .cohort
        .symbols
        .iter()
        .chain(anchors.iter().filter(|anchor| {
            !contract
                .cohort
                .symbols
                .iter()
                .any(|member| member == *anchor)
        }))
        .collect();

    for symbol in audited {
        let Ok(fetched) = yahoo.fetch_symbol(symbol) else {
            eprintln!("{symbol}: yahoo fail");
            continue;
        };
        let market = fetched.snapshot.as_ref().map(|s| s.market_price_cents);
        let street = fetched
            .snapshot
            .as_ref()
            .map(|s| s.intrinsic_value_cents)
            .filter(|v| *v > 0);
        let Some(mut fund) = fetched.fundamentals else {
            eprintln!("{symbol}: no fundamentals");
            continue;
        };
        let Some(&cik) = cik_map.get(symbol) else {
            eprintln!("{symbol}: no cik");
            continue;
        };
        if fund.shares_outstanding.unwrap_or(0) == 0 {
            fund.shares_outstanding =
                crate::edgar::fetch_shares_outstanding(&edgar_client, symbol, cik).unwrap_or(None);
        }
        let class = crate::dcf_model::classify_business(
            fund.sector_name.as_deref(),
            fund.industry_name.as_deref(),
            fund.sector_key.as_deref(),
            fund.industry_key.as_deref(),
            false,
        );
        if class != crate::dcf_model::BusinessClass::OperatingNonFinancial {
            eprintln!("{symbol}: class {class:?} — forward lane not primary");
            continue;
        }

        let analysis = match crate::edgar::fetch_fcf_history(&edgar_client, symbol, cik) {
            Ok(Some(history)) => crate::dcf_model::compute_with_params(
                &fund,
                &history,
                market,
                &market_params,
                "forward_decomposition_audit",
                false,
            )
            .ok(),
            _ => None,
        };
        let fetched_forecast = yahoo.fetch_forward_forecast(symbol, day);
        let raw_growth = fetched_forecast
            .as_ref()
            .ok()
            .map(|evidence| (evidence.revenue_growth_bps, evidence.earnings_growth_bps));
        let forward_evidence = fetched_forecast.map_err(|error| match error {
            crate::fetcher::ForwardForecastFetchError::Provider(reason) => {
                crate::operating_valuation_runtime::ForwardSourceFailure::Provider(reason)
            }
            crate::fetcher::ForwardForecastFetchError::Transport(error)
                if crate::yahoo_session::is_rate_limit_error(&error) =>
            {
                crate::operating_valuation_runtime::ForwardSourceFailure::RateLimited
            }
            crate::fetcher::ForwardForecastFetchError::Transport(_) => {
                crate::operating_valuation_runtime::ForwardSourceFailure::Transport
            }
        });
        let envelope = crate::operating_valuation_runtime::route_runtime_valuation(
            crate::operating_valuation_runtime::RuntimeValuationInput {
                business_class: class,
                fundamentals: &fund,
                fcff_analysis: analysis.as_ref(),
                fcff_failure: None,
                forward_evidence,
                market_params: &market_params,
                as_of_epoch_day: day,
                market_price_cents: market,
            },
        );
        let candidate = &envelope.decision.forward_candidate;
        let provenance = &candidate.provenance;
        let Some(eps) = provenance.forecast.eps_mean_cents else {
            eprintln!("{symbol}: no forward EPS ({:?})", candidate.refusals);
            continue;
        };
        let Some(stable) = candidate.stable_growth_bps else {
            eprintln!("{symbol}: no stable growth");
            continue;
        };
        let rate = provenance.cost_of_equity.cost_of_equity_bps;
        let near_growth = provenance.forecast.near_growth_bps;
        let hold = provenance.policy.hold_years;
        let fade = provenance.policy.fade_years;
        let roe = fund.return_on_equity_bps;
        let leverage = fund.debt_to_equity_hundredths;
        // Unlever ROE to a return on total capital: ROIC ~= ROE / (1 + D/E).
        // Crude, but it is the only return-on-capital signal in the snapshot and
        // it separates a 28%-ROE-on-thin-equity levered issuer from a genuine
        // high-return compounder.
        let roic = roe.map(|roe| {
            let gearing = 1.0 + leverage.unwrap_or(0).max(0) as f64 / 10_000.0;
            roe as f64 / gearing
        });

        let now = project_probe(eps as f64, near_growth, rate, stable, hold, fade, None);
        // Retired policy, kept as the audit's "before" column: returns below the
        // cost of equity were charged *as if* the issuer merely earned its cost of
        // capital. That floor is gone from the engine — it flattened every
        // sub-cost-of-capital issuer onto one payout.
        let neutral = roic.map(|value| value.max(rate as f64));
        let floored_term = project_probe(
            eps as f64,
            near_growth,
            rate,
            stable,
            hold,
            fade,
            neutral.map(|value| (value, false)),
        );
        // Beta provenance: is the shrunk beta driven by the ticker's own regression
        // or by the industry prior? Back out the shrunk beta from the resolved CoE
        // (`r = rf + beta * erp`) so the blend is visible against both endpoints.
        let shrunk_beta_millis =
            (rate - market_params.rf_bps) as f64 * 1_000.0 / market_params.erp_bps as f64;
        // Through-cycle return on capital candidate: the FCFF lane already computed a
        // multi-year normalized FCFF, so a median-based ROIC needs no new plumbing.
        let diagnostics = analysis.as_ref().map(|a| &a.diagnostics);
        let normalized_fcff = diagnostics.and_then(|d| d.normalized_fcff_dollars);
        let book_equity = fund
            .book_value_per_share_cents
            .zip(fund.shares_outstanding)
            .map(|(bvps, shares)| bvps as f64 / 100.0 * shares as f64);
        let invested_capital =
            book_equity.map(|equity| equity + fund.total_debt_dollars.unwrap_or(0) as f64);
        let tc_roic = normalized_fcff
            .zip(invested_capital)
            .filter(|(_, capital)| *capital > 0.0)
            .map(|(fcff, capital)| fcff as f64 * 10_000.0 / capital);
        eprintln!(
            "  TC {:<6} norm_fcff={:>15?} bvps={:>9?} shares={:>14?} debt={:>15?} ic={:>18.0} tc_roic={:>8.0} roe_roic={:>7.0} kd_at={:?}",
            symbol,
            normalized_fcff,
            fund.book_value_per_share_cents,
            fund.shares_outstanding,
            fund.total_debt_dollars,
            invested_capital.unwrap_or(0.0),
            tc_roic.unwrap_or(0.0),
            roic.unwrap_or(0.0),
            diagnostics.and_then(|d| d.after_tax_cost_of_debt_bps)
        );
        let (raw_revenue_growth, raw_earnings_growth) = raw_growth.unwrap_or((None, None));
        let raw_revenue_growth = raw_revenue_growth.unwrap_or(0);
        let raw_earnings_growth = raw_earnings_growth.unwrap_or(0);
        // Growth blend dry-run: what the retired flat cap produced, what the
        // own-trend blend produces, and the forward value each implies. Same
        // eps/rate/hold/fade in both columns, so the delta is the rate alone.
        let own_growth = crate::operating_valuation_runtime::own_growth_bps(analysis.as_ref());
        let growth = crate::operating_valuation_runtime::resolve_near_growth(
            &fund,
            raw_revenue_growth,
            raw_earnings_growth,
            own_growth,
        );
        let value_legacy = project_probe(
            eps as f64,
            growth.legacy_capped_bps,
            rate,
            stable,
            hold,
            fade,
            None,
        );
        // Explicitly projects `resolved_bps`, not the production rate: production
        // still reads the flat cap, so reusing its value would print the same
        // number in both columns and hide the whole comparison.
        let value_blend = project_probe(
            eps as f64,
            growth.resolved_bps,
            rate,
            stable,
            hold,
            fade,
            None,
        );
        eprintln!(
            "G0 {:<6} consensus={:>6} own={:>7?} dev={:>6} w_cons={:>5} g0_old={:>6} g0_new={:>6} $old={:>9.2} $new={:>9.2} delta={:>7.1}%",
            symbol,
            growth.consensus_bps,
            growth.own_bps,
            growth.deviation_bps,
            growth.consensus_weight_bps,
            growth.legacy_capped_bps,
            growth.resolved_bps,
            value_legacy / 100.0,
            value_blend / 100.0,
            if value_legacy > 0.0 {
                (value_blend / value_legacy - 1.0) * 100.0
            } else {
                0.0
            },
        );
        // Production resolution, exactly as the router sees it.
        let production_roic =
            crate::operating_valuation_runtime::return_on_capital_bps(&fund, analysis.as_ref());
        let payout = crate::operating_valuation::terminal_payout_bps(production_roic, rate, stable);
        eprintln!(
            "ROW {:<6} {:>7} {:>7} {:>6} {:>6} {:>4} {:>4} {:>5} {:>6} {:>7} {:>6.0} {:>7.0} {:>7?} {:>6} {:>6} {:>6} {:>6.0} {:>7} {:>7} {:>8.0} {:>8.0}",
            symbol,
            market.unwrap_or(0),
            street.unwrap_or(0),
            eps,
            near_growth,
            hold,
            fade,
            rate,
            roe.unwrap_or(0),
            leverage.unwrap_or(0),
            roic.unwrap_or(0.0),
            tc_roic.unwrap_or(0.0),
            production_roic,
            payout,
            candidate.intrinsic_value_cents.unwrap_or(0),
            fund.beta_millis.unwrap_or(0),
            shrunk_beta_millis,
            raw_revenue_growth,
            raw_earnings_growth,
            now,
            floored_term
        );
    }
}

/// Floating-point replica of `project_forward_value` with an optional retention
/// charge. `retention = Some((roic_bps, every_year))`: when `every_year` the
/// charge applies to each projected year, otherwise only to the perpetuity.
/// Payout floors at zero — growth above the return on capital funds itself out
/// of nothing.
fn project_probe(
    eps: f64,
    near_growth_bps: i32,
    rate_bps: i32,
    stable_bps: i32,
    hold: i32,
    fade: i32,
    retention: Option<(f64, bool)>,
) -> f64 {
    let rate = rate_bps as f64 / 10_000.0;
    let payout = |growth_bps: f64, apply: bool| -> f64 {
        match retention {
            Some((roic_bps, _)) if apply && roic_bps > 0.0 => {
                (1.0 - growth_bps / roic_bps).clamp(0.0, 1.0)
            }
            _ => 1.0,
        }
    };
    let explicit = retention.is_some_and(|(_, every_year)| every_year);
    let mut discounted = eps / (1.0 + rate);
    let mut present_value = discounted * payout(near_growth_bps as f64, explicit);
    for _ in 0..hold {
        discounted = discounted * (1.0 + near_growth_bps as f64 / 10_000.0) / (1.0 + rate);
        present_value += discounted * payout(near_growth_bps as f64, explicit);
    }
    for step in 1..=fade {
        let delta = (stable_bps - near_growth_bps) as f64 * step as f64 / fade as f64;
        let growth = near_growth_bps as f64 + delta;
        discounted = discounted * (1.0 + growth / 10_000.0) / (1.0 + rate);
        present_value += discounted * payout(growth, explicit);
    }
    let stable = stable_bps as f64 / 10_000.0;
    let terminal = discounted * payout(stable_bps as f64, retention.is_some()) * (1.0 + stable)
        / (rate - stable);
    present_value + terminal
}
