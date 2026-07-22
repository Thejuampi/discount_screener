mod chart_patterns;
mod commands;
mod congress;
mod congress_scoring;
mod crypto_cycle;
mod crypto_md;
mod db;
mod dcf_model;
mod edgar;
mod email;
mod engine;
mod feed_log;
mod fetcher;
mod fibonacci;
mod index_estimates;
mod news;
mod opportunity_v3;
mod profiles;
mod quant_lens;
mod quote_summary;
mod regime;
mod risk;
mod scalp_ws;
mod scalping;
mod schwab;
mod schwab_api;
mod smc;
mod state;
mod stooq;
mod ticker_search;
mod yahoo_session;

use state::AppState;
use tauri::{
    menu::{Menu, MenuItem},
    tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent},
    Manager, WindowEvent,
};
use tauri_plugin_autostart::{MacosLauncher, ManagerExt};

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        // Single-instance MUST be the first plugin: if another instance is already
        // running, this callback fires in that instance and we focus its window
        // instead of creating a duplicate process.
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| {
            show_main_window(app);
        }))
        .plugin(tauri_plugin_shell::init())
        .plugin(tauri_plugin_notification::init())
        .plugin(tauri_plugin_autostart::init(
            MacosLauncher::LaunchAgent,
            Some(vec!["--minimized"]), // pass this flag when started by autostart
        ))
        .setup(|app| {
            // ── State / DB ────────────────────────────────────────────────────
            let app_data_dir = app.path().app_data_dir().expect("resolve app data dir");
            let db_path = app_data_dir.join("history.sqlite");
            let app_state = AppState::new(db_path);
            app.manage(app_state);

            // ── Real-time scalping WebSocket (background thread) ───────────────
            let scalp_rx = app.state::<AppState>().scalp_ws_tx.subscribe();
            scalp_ws::spawn(app.handle().clone(), scalp_rx);

            // ── Enable autostart on first launch (idempotent) ─────────────────
            let autostart = app.autolaunch();
            if let Ok(false) = autostart.is_enabled() {
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

            // ── If launched with --minimized, hide the window immediately ─────
            let args: Vec<String> = std::env::args().collect();
            if args.iter().any(|a| a == "--minimized") {
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
            commands::get_opportunities,
            commands::get_symbol_detail,
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
