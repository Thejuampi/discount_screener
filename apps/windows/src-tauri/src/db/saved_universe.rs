use std::collections::HashSet;

use rusqlite::params;
use serde::Serialize;

use super::Db;

// The Russell 2000 plus supplemental assets is the largest supported universe.
const MAX_ROWS_PER_PROFILE: i64 = 4096;

pub(crate) const SCHEMA: &str = "
    CREATE TABLE IF NOT EXISTS saved_universe_rows (
        profile TEXT NOT NULL,
        symbol TEXT NOT NULL,
        company_name TEXT NOT NULL,
        market_price_cents INTEGER NOT NULL CHECK (market_price_cents > 0),
        target_price_cents INTEGER CHECK (target_price_cents > 0),
        asset_type TEXT NOT NULL CHECK (asset_type IN ('stock', 'etf', 'crypto')),
        saved_at_epoch_seconds INTEGER NOT NULL CHECK (saved_at_epoch_seconds > 0),
        PRIMARY KEY (profile, symbol)
    ) WITHOUT ROWID;
    CREATE INDEX IF NOT EXISTS saved_universe_rows_recent
    ON saved_universe_rows(profile, saved_at_epoch_seconds DESC, symbol);
";

/// Display projection. The screener engine never consumes these rows.
#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct SavedUniverseRow {
    pub(crate) symbol: String,
    pub(crate) company_name: String,
    pub(crate) market_price_cents: i64,
    pub(crate) target_price_cents: Option<i64>,
    pub(crate) asset_type: String,
    pub(crate) saved_at_epoch_seconds: i64,
}

impl Db {
    pub(crate) fn save_universe_rows(
        &self,
        profile: &str,
        rows: &[SavedUniverseRow],
    ) -> rusqlite::Result<()> {
        if rows.is_empty() {
            return Ok(());
        }
        let mut connection = self.conn.lock().unwrap();
        let tx = connection.transaction()?;
        {
            let mut upsert = tx.prepare_cached(
                "INSERT INTO saved_universe_rows (
                    profile, symbol, company_name, market_price_cents, target_price_cents,
                    asset_type, saved_at_epoch_seconds
                ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)
                ON CONFLICT (profile, symbol) DO UPDATE SET
                    company_name = excluded.company_name,
                    market_price_cents = excluded.market_price_cents,
                    target_price_cents = excluded.target_price_cents,
                    asset_type = excluded.asset_type,
                    saved_at_epoch_seconds = excluded.saved_at_epoch_seconds",
            )?;
            for row in rows {
                upsert.execute(params![
                    profile,
                    row.symbol,
                    row.company_name,
                    row.market_price_cents,
                    row.target_price_cents,
                    row.asset_type,
                    row.saved_at_epoch_seconds,
                ])?;
            }
        }
        tx.execute(
            "DELETE FROM saved_universe_rows WHERE profile = ?1 AND symbol IN (
                SELECT symbol FROM saved_universe_rows WHERE profile = ?1
                ORDER BY saved_at_epoch_seconds DESC, symbol
                LIMIT -1 OFFSET ?2
            )",
            params![profile, MAX_ROWS_PER_PROFILE],
        )?;
        tx.commit()
    }

    pub(crate) fn load_saved_universe_rows(
        &self,
        profile: &str,
        active_symbols: &[String],
    ) -> rusqlite::Result<Vec<SavedUniverseRow>> {
        let active: HashSet<_> = active_symbols.iter().map(String::as_str).collect();
        let connection = self.conn.lock().unwrap();
        let mut statement = connection.prepare_cached(
            "SELECT symbol, company_name, market_price_cents, target_price_cents,
                asset_type, saved_at_epoch_seconds
             FROM saved_universe_rows WHERE profile = ?1 ORDER BY symbol",
        )?;
        let mut rows = statement.query([profile])?;
        let mut saved = Vec::new();
        while let Some(row) = rows.next()? {
            let symbol: String = row.get(0)?;
            if active.contains(symbol.as_str()) {
                saved.push(SavedUniverseRow {
                    symbol,
                    company_name: row.get(1)?,
                    market_price_cents: row.get(2)?,
                    target_price_cents: row.get(3)?,
                    asset_type: row.get(4)?,
                    saved_at_epoch_seconds: row.get(5)?,
                });
            }
        }
        Ok(saved)
    }
}
