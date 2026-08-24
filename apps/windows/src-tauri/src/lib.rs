mod analyst_forecasts;
pub mod analyst_method_import;
pub mod analyst_method_service;
mod chart_patterns;
mod commands;
mod congress;
mod congress_scoring;
#[cfg(test)]
mod cross_platform_parity;
mod crypto_cycle;
mod crypto_md;
mod db;
mod dcf_model;
mod driver_resolution;
mod edgar;
mod email;
mod engine;
pub mod evidence_sotp;
#[cfg(test)]
mod evidence_sotp_contract;
mod feed_log;
mod fetcher;
mod fibonacci;
pub mod forward_earnings_multiple;
#[cfg(test)]
mod forward_earnings_multiple_contract;
mod index_estimates;
pub mod issuer_identity;
mod launch_profile;
mod news;
pub mod operating_valuation;
pub mod operating_valuation_runtime;
mod opportunity_v3;
mod price_path;
mod profiles;
mod quant_lens;
mod quote_summary;
mod regime;
mod risk;
mod scalp_ws;
mod scalping;
mod schwab;
mod schwab_api;
mod sec_driver_normalization_policy_generated;
mod sec_normalization;
mod smc;
pub mod source_continuity;
mod state;
mod stooq;
mod ticker_search;
#[cfg(test)]
mod valuation_baseline;
pub mod valuation_core_adapter;
#[cfg(test)]
mod valuation_core_measurement;
#[cfg(test)]
mod valuation_decision_contract;
mod valuation_divergence;
pub mod valuation_dossier_view;
pub mod valuation_evidence;
#[cfg(test)]
mod valuation_evidence_contract;
#[cfg(test)]
mod valuation_fixture_capture;
pub mod valuation_gap_attribution;
#[cfg(test)]
mod valuation_high_signal;
#[cfg(test)]
mod valuation_probes;
mod yahoo_session;

use state::AppState;
use tauri::{
    menu::{Menu, MenuItem},
    tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent},
    Manager, WindowEvent,
};
use tauri_plugin_autostart::{MacosLauncher, ManagerExt};

#[cfg(all(debug_assertions, target_os = "windows"))]
fn enable_local_webview_debugging() {
    const ENV: &str = "WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS";
    let existing = std::env::var(ENV).unwrap_or_default();
    if existing.contains("--remote-debugging-port") {
        return;
    }
    let args = format!(
        "{} --remote-debugging-address=127.0.0.1 --remote-debugging-port=9222",
        existing.trim()
    );
    std::env::set_var(ENV, args.trim());
    eprintln!("discount_screener: local WebView debug endpoint on 127.0.0.1:9222");
}

/// Debug-only flag set by native e2e runners (`test:e2e:native:*`).
/// Must never be true during normal `cargo test` / `npm test`.
fn is_native_e2e() -> bool {
    cfg!(debug_assertions) && std::env::var("DS_NATIVE_E2E").as_deref() == Ok("1")
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    #[cfg(all(debug_assertions, target_os = "windows"))]
    enable_local_webview_debugging();

    let native_e2e = is_native_e2e();

    // Single-instance MUST be first for normal launches: a second process focuses
    // the existing window. Native e2e must skip this — otherwise an already-running
    // operator instance is focused (looks like "the test opened the real app") and
    // the isolated e2e process never becomes the WebView under test.
    let builder = tauri::Builder::default();
    let builder = if native_e2e {
        builder
    } else {
        builder.plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| {
            show_main_window(app);
        }))
    };

    builder
        .plugin(tauri_plugin_shell::init())
        .plugin(tauri_plugin_notification::init())
        .plugin(tauri_plugin_autostart::init(
            MacosLauncher::LaunchAgent,
            Some(vec!["--minimized"]), // pass this flag when started by autostart
        ))
        .setup(move |app| {
            // ── State / DB ────────────────────────────────────────────────────
            let platform_app_data_dir = app.path().app_data_dir().expect("resolve app data dir");
            // Windows KnownFolder resolution can ignore child-process APPDATA overrides.
            // Native E2E therefore gets an explicit, debug-only isolated data root so
            // fixture ledger writes can never reuse the operator's real history.sqlite.
            let app_data_dir = if native_e2e {
                std::env::var_os("DS_NATIVE_E2E_DATA_DIR")
                    .filter(|value| !value.is_empty())
                    .map(std::path::PathBuf::from)
                    .unwrap_or(platform_app_data_dir)
            } else {
                platform_app_data_dir
            };
            let db_path = app_data_dir.join("history.sqlite");

            // Launch profile: --profile / --profile=NAME wins over DS_UNIVERSE_PROFILE.
            // Invalid explicit values fail closed (never silent sp500).
            let forced = match launch_profile::parse_forced_profile(
                std::env::args().skip(1),
                std::env::var(launch_profile::DS_UNIVERSE_PROFILE_ENV)
                    .ok()
                    .as_deref(),
            ) {
                Ok(v) => v,
                Err(e) => {
                    eprintln!("discount_screener: {e}");
                    return Err(e.to_string().into());
                }
            };
            let app_state = AppState::new_with_forced_profile(db_path, forced)
                .map_err(|e| -> Box<dyn std::error::Error> { e.into() })?;
            if app_state.is_profile_locked() {
                let name = app_state.active_profile.lock().unwrap().clone();
                let n = app_state.active_symbols.lock().unwrap().len();
                eprintln!("discount_screener: launch profile locked to {name} ({n} symbols)");
            }
            app.manage(app_state);

            // Keep market-context (V3 4th bucket) warm independent of UI banner mount.
            regime::spawn_regime_worker(&*app.state::<AppState>());

            // ── Real-time scalping WebSocket (background thread) ───────────────
            let scalp_rx = app.state::<AppState>().scalp_ws_tx.subscribe();
            scalp_ws::spawn(app.handle().clone(), scalp_rx);

            // ── Autostart policy ──────────────────────────────────────────────
            // Debug / native-e2e MUST never install a Run-key to target\debug\*.exe.
            // That poisoned Windows login + made every rebuild/session look like
            // "tests keep launching the real app". Heal any existing debug entry.
            // Release may still opt-in on first launch; Settings can always toggle.
            let autostart = app.autolaunch();
            if native_e2e || cfg!(debug_assertions) {
                if let Ok(true) = autostart.is_enabled() {
                    let _ = autostart.disable();
                    eprintln!(
                        "discount_screener: disabled Windows autostart (debug/e2e must not register target\\\\debug)"
                    );
                }
            } else if let Ok(false) = autostart.is_enabled() {
                let _ = autostart.enable();
            }

            // ── Tray icon ─────────────────────────────────────────────────────
            let show_item = MenuItem::with_id(app, "show", "Mostrar Vantage", true, None::<&str>)?;
            let hide_item = MenuItem::with_id(app, "hide", "Ocultar ventana", true, None::<&str>)?;
            let quit_item = MenuItem::with_id(app, "quit", "Salir", true, None::<&str>)?;
            let tray_menu = Menu::with_items(app, &[&show_item, &hide_item, &quit_item])?;

            let _tray = TrayIconBuilder::with_id("main-tray")
                .tooltip("Vantage")
                .icon(app.default_window_icon().unwrap().clone())
                .menu(&tray_menu)
                .show_menu_on_left_click(false)
                .on_menu_event(|app, event| match event.id.as_ref() {
                    "show" => show_main_window(app),
                    "hide" => {
                        if let Some(w) = app.get_webview_window("main") {
                            let _ = w.hide();
                        }
                    }
                    "quit" => app.exit(0),
                    _ => {}
                })
                .on_tray_icon_event(|tray, event| {
                    // Left-click on the tray icon → toggle window visibility
                    if let TrayIconEvent::Click {
                        button: MouseButton::Left,
                        button_state: MouseButtonState::Up,
                        ..
                    } = event
                    {
                        let app = tray.app_handle();
                        if let Some(w) = app.get_webview_window("main") {
                            match w.is_visible() {
                                Ok(true) => {
                                    let _ = w.hide();
                                }
                                _ => show_main_window(app),
                            }
                        }
                    }
                })
                .build(app)?;

            // Hide immediately for tray/autostart launches and for native e2e
            // (must not leave a visible product window during integration runs).
            let args: Vec<String> = std::env::args().collect();
            let hide_window =
                native_e2e || args.iter().any(|a| a == "--minimized");
            if hide_window {
                if let Some(w) = app.get_webview_window("main") {
                    let _ = w.hide();
                }
            }

            Ok(())
        })
        // Intercept the close button → hide to tray instead of quitting
        .on_window_event(|window, event| {
            if let WindowEvent::CloseRequested { api, .. } = event {
                if window.label() == "main" {
                    api.prevent_close();
                    let _ = window.hide();
                }
            }
        })
        .invoke_handler(tauri::generate_handler![
            commands::get_app_version,
            commands::get_opportunities,
            commands::get_symbol_detail,
            commands::debug_seed_cof_native_e2e,
            commands::get_analyst_forecasts,
            commands::load_analyst_forecasts,
            commands::tipranks_settings_status,
            commands::tipranks_save_key,
            commands::tipranks_delete_key,
            commands::tipranks_test_key,
            commands::get_candles,
            commands::get_alerts,
            commands::refresh_symbol,
            commands::search_tickers,
            commands::resolve_ticker_search_submit,
            commands::ensure_symbol_loaded,
            commands::get_scoring_model,
            commands::set_scoring_model,
            commands::get_index_estimates,
            commands::get_quant_lens,
            commands::get_valuation_dossier,
            commands::debug_seed_amzn_analyst_method_e2e,
            commands::run_qa_valuation_divergence_audit,
            commands::list_universe_profiles,
            commands::get_universe_profile,
            commands::set_universe_profile,
            commands::start_feed,
            commands::get_feed_status,
            commands::get_symbol_history,
            commands::get_backtest,
            commands::get_history_status,
            commands::get_autostart_enabled,
            commands::set_autostart_enabled,
            commands::quit_app,
            commands::get_news,
            commands::import_schwab_pdf,
            commands::get_schwab_report,
            commands::count_schwab_reports,
            commands::delete_schwab_report,
            commands::get_congress_overview,
            commands::get_congress_trades_for_symbol,
            commands::sync_congress_house,
            commands::get_congress_sync_progress,
            commands::get_crypto_metrics,
            commands::portfolio_list,
            commands::portfolio_add,
            commands::portfolio_update,
            commands::portfolio_delete,
            commands::portfolio_import,
            commands::get_quote_prices,
            commands::get_model_accuracy,
            commands::compute_congress_metrics,
            commands::get_top_politicians_ranked,
            commands::get_politician_detail,
            risk::get_portfolio_risk,
            regime::get_market_regime,
            commands::get_regime_scoring_enabled,
            commands::set_regime_scoring_enabled,
            commands::journal_list,
            commands::journal_add,
            commands::journal_close,
            commands::journal_delete,
            commands::get_price_provenance,
            commands::schwab_set_credentials,
            commands::schwab_auth_url,
            commands::schwab_complete_auth,
            commands::schwab_disconnect,
            commands::schwab_status,
            commands::email_config_get,
            commands::email_config_set,
            commands::email_send,
            commands::email_mark_digest_sent,
            commands::get_scalp_candles,
            commands::get_scalp_analysis,
            commands::scalp_ws_subscribe,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}

fn show_main_window(app: &tauri::AppHandle) {
    if let Some(w) = app.get_webview_window("main") {
        let _ = w.show();
        let _ = w.unminimize();
        let _ = w.set_focus();
    }
}
