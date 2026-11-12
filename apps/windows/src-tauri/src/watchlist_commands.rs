use crate::state::AppState;
use tauri::State;

#[tauri::command]
pub fn get_watchlist(state: State<AppState>) -> Result<Vec<String>, String> {
    state.db.get_watchlist()
}

#[tauri::command]
pub fn set_watchlist_member(
    symbol: String,
    included: bool,
    state: State<AppState>,
) -> Result<(), String> {
    state.db.set_watchlist_member(&symbol, included)
}
