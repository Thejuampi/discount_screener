use crate::state::AppState;
use tauri::State;

#[tauri::command]
pub fn get_ticker_note(symbol: String, state: State<AppState>) -> Result<Option<String>, String> {
    state.db.get_ticker_note(&symbol)
}

#[tauri::command]
pub fn set_ticker_note(
    symbol: String,
    note: Option<String>,
    state: State<AppState>,
) -> Result<(), String> {
    state.db.set_ticker_note(&symbol, note.as_deref())
}
