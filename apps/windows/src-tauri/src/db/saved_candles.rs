use rusqlite::{params, OptionalExtension};

use super::Db;
use crate::engine::HistoricalCandle;

pub(crate) const MAX_SAVED_CANDLE_SERIES: usize = 128;
pub(crate) const MAX_SAVED_CANDLE_BARS: usize = 2_048;
const MAX_SAVED_CANDLE_BYTES: usize = 256 * 1024;
const SAVED_CANDLE_VERSION: i64 = 1;

pub(crate) const SCHEMA: &str = r#"
CREATE TABLE IF NOT EXISTS detail_candle_cache (
    profile             TEXT    NOT NULL,
    symbol              TEXT    NOT NULL,
    range               TEXT    NOT NULL,
    format_version      INTEGER NOT NULL,
    saved_at_epoch_ms   INTEGER NOT NULL,
    payload_json        TEXT    NOT NULL,
    PRIMARY KEY (profile, symbol, range)
);
"#;

pub(crate) struct SavedDetailCandles {
    pub(crate) candles: Vec<HistoricalCandle>,
    pub(crate) saved_at_epoch_seconds: i64,
}

impl Db {
    pub(crate) fn load_detail_candles(
        &self,
        profile: &str,
        symbol: &str,
        range: &str,
    ) -> Result<Option<SavedDetailCandles>, String> {
        let row: Option<(i64, i64, Option<String>)> = {
            let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
            conn.query_row(
                "SELECT format_version, saved_at_epoch_ms,
                        CASE WHEN length(CAST(payload_json AS BLOB)) <= ?4 THEN payload_json END
                 FROM detail_candle_cache WHERE profile = ?1 AND symbol = ?2 AND range = ?3",
                params![profile, symbol, range, MAX_SAVED_CANDLE_BYTES],
                |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
            )
            .optional()
            .map_err(|error| format!("read saved candles: {error}"))?
        };
        let Some((version, saved_at_epoch_ms, payload)) = row else {
            return Ok(None);
        };
        if version != SAVED_CANDLE_VERSION {
            return Err("unsupported saved candle format".into());
        }
        if saved_at_epoch_ms <= 0 {
            return Err("invalid saved candle timestamp".into());
        }
        let payload = payload.ok_or("saved candles exceed their size limit")?;
        let candles: Vec<HistoricalCandle> =
            serde_json::from_str(&payload).map_err(|_| "invalid saved candle payload")?;
        validate_candles(&candles)?;
        Ok(Some(SavedDetailCandles {
            candles,
            saved_at_epoch_seconds: saved_at_epoch_ms / 1_000,
        }))
    }

    pub(crate) fn save_detail_candles(
        &self,
        profile: &str,
        symbol: &str,
        range: &str,
        candles: &[HistoricalCandle],
    ) -> Result<(), String> {
        validate_candles(candles)?;
        let payload = serde_json::to_string(candles)
            .map_err(|error| format!("encode saved candles: {error}"))?;
        if payload.len() > MAX_SAVED_CANDLE_BYTES {
            return Err("saved candles exceed their size limit".into());
        }
        let saved_at_epoch_ms = chrono::Utc::now().timestamp_millis();
        let mut conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        let tx = conn
            .transaction()
            .map_err(|error| format!("begin saved candle write: {error}"))?;
        let changed = tx
            .execute(
                "INSERT INTO detail_candle_cache
               (profile, symbol, range, format_version, saved_at_epoch_ms, payload_json)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6)
             ON CONFLICT(profile, symbol, range) DO UPDATE SET
               format_version = excluded.format_version,
               saved_at_epoch_ms = excluded.saved_at_epoch_ms,
               payload_json = excluded.payload_json
             WHERE detail_candle_cache.format_version <= excluded.format_version
               AND (detail_candle_cache.format_version <> excluded.format_version
                 OR detail_candle_cache.payload_json <> excluded.payload_json)",
                params![
                    profile,
                    symbol,
                    range,
                    SAVED_CANDLE_VERSION,
                    saved_at_epoch_ms,
                    payload
                ],
            )
            .map_err(|error| format!("write saved candles: {error}"))?;
        if changed != 0 {
            tx.execute(
                "DELETE FROM detail_candle_cache WHERE rowid IN (
                     SELECT rowid FROM detail_candle_cache
                     ORDER BY saved_at_epoch_ms DESC, rowid DESC
                     LIMIT -1 OFFSET ?1
                 )",
                params![MAX_SAVED_CANDLE_SERIES],
            )
            .map_err(|error| format!("prune saved candles: {error}"))?;
        }
        tx.commit()
            .map_err(|error| format!("commit saved candles: {error}"))
    }
}

fn validate_candles(candles: &[HistoricalCandle]) -> Result<(), String> {
    if candles.is_empty() || candles.len() > MAX_SAVED_CANDLE_BARS {
        return Err("invalid saved candle count".into());
    }
    if candles
        .iter()
        .any(|candle| candle.epoch_seconds <= 0 || candle.close_cents <= 0)
        || candles
            .windows(2)
            .any(|pair| pair[0].epoch_seconds >= pair[1].epoch_seconds)
    {
        return Err("invalid saved candle timestamps or prices".into());
    }
    Ok(())
}
