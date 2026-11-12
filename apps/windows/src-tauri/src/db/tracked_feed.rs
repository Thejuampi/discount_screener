use rusqlite::params;

use super::Db;

pub(super) const SCHEMA: &str = "
    CREATE TABLE IF NOT EXISTS tracked_feed_overrides (
        edit_id INTEGER PRIMARY KEY,
        profile TEXT NOT NULL,
        symbol TEXT NOT NULL,
        included INTEGER NOT NULL CHECK (included IN (0, 1)),
        UNIQUE (profile, symbol)
    );
";

impl Db {
    pub(crate) fn tracked_feed_overrides(
        &self,
        profile: &str,
    ) -> Result<Vec<(String, bool)>, String> {
        let conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        let mut statement = conn
            .prepare_cached(
                "SELECT symbol, included FROM tracked_feed_overrides
                 WHERE profile = ?1 ORDER BY edit_id",
            )
            .map_err(|e| format!("prepare tracked feed overrides: {e}"))?;
        let rows = statement
            .query_map([profile], |row| Ok((row.get(0)?, row.get(1)?)))
            .map_err(|e| format!("query tracked feed overrides: {e}"))?;
        rows.collect::<rusqlite::Result<_>>()
            .map_err(|e| format!("read tracked feed overrides: {e}"))
    }

    pub(crate) fn set_tracked_feed_override(
        &self,
        profile: &str,
        symbol: &str,
        included: bool,
    ) -> Result<(), String> {
        let mut conn = self.conn.lock().map_err(|_| "db lock poisoned")?;
        let transaction = conn
            .transaction()
            .map_err(|e| format!("start tracked feed override write: {e}"))?;
        // A changed preference moves to the end. A repeated write keeps its position.
        transaction
            .execute(
                "DELETE FROM tracked_feed_overrides
                 WHERE profile = ?1 AND symbol = ?2 AND included <> ?3",
                params![profile, symbol, included],
            )
            .map_err(|e| format!("replace tracked feed override: {e}"))?;
        transaction
            .execute(
                "INSERT INTO tracked_feed_overrides (profile, symbol, included)
                 VALUES (?1, ?2, ?3) ON CONFLICT (profile, symbol) DO NOTHING",
                params![profile, symbol, included],
            )
            .map_err(|e| format!("write tracked feed override: {e}"))?;
        transaction
            .commit()
            .map_err(|e| format!("commit tracked feed override: {e}"))
    }
}

#[cfg(test)]
mod tests {
    use std::path::PathBuf;
    use std::time::{SystemTime, UNIX_EPOCH};

    use crate::db::Db;

    struct TestDb(PathBuf);

    impl TestDb {
        fn new() -> Self {
            let timestamp = SystemTime::now().duration_since(UNIX_EPOCH).unwrap();
            Self(std::env::temp_dir().join(format!(
                "ds_tracked_feed_{}_{}",
                std::process::id(),
                timestamp.as_nanos()
            )))
        }

        fn open(&self) -> Db {
            Db::open(self.0.join("history.sqlite")).unwrap()
        }
    }

    impl Drop for TestDb {
        fn drop(&mut self) {
            for name in ["history.sqlite", "history.sqlite-wal", "history.sqlite-shm"] {
                let _ = std::fs::remove_file(self.0.join(name));
            }
            let _ = std::fs::remove_dir(&self.0);
        }
    }

    #[test]
    fn new_profile_has_no_overrides() {
        let db = Db::open_in_memory().unwrap();
        assert!(db.tracked_feed_overrides("sp500").unwrap().is_empty());
    }

    #[test]
    fn overrides_survive_reopening_and_keep_profiles_independent() {
        let file = TestDb::new();
        {
            let db = file.open();
            db.set_tracked_feed_override("sp500", "TSM", true).unwrap();
            db.set_tracked_feed_override("sp500", "AAPL", false)
                .unwrap();
            db.set_tracked_feed_override("dow", "AAPL", true).unwrap();
        }
        let db = file.open();
        assert_eq!(
            db.tracked_feed_overrides("sp500").unwrap(),
            [("TSM".into(), true), ("AAPL".into(), false)]
        );
        assert_eq!(
            db.tracked_feed_overrides("dow").unwrap(),
            [("AAPL".into(), true)]
        );
        assert!(db.tracked_feed_overrides("russell").unwrap().is_empty());
    }

    #[test]
    fn duplicate_writes_preserve_order_and_readds_append() {
        let file = TestDb::new();
        {
            let db = file.open();
            for (symbol, included) in [("TSM", true), ("ASML", true), ("TSM", true)] {
                db.set_tracked_feed_override("sp500", symbol, included)
                    .unwrap();
            }
            assert_eq!(
                db.tracked_feed_overrides("sp500").unwrap(),
                [("TSM".into(), true), ("ASML".into(), true)]
            );
            db.set_tracked_feed_override("sp500", "TSM", false).unwrap();
            db.set_tracked_feed_override("sp500", "TSM", true).unwrap();
        }
        assert_eq!(
            file.open().tracked_feed_overrides("sp500").unwrap(),
            [("ASML".into(), true), ("TSM".into(), true)]
        );
    }

    #[test]
    fn failed_replacement_keeps_the_previous_override() {
        let db = Db::open_in_memory().unwrap();
        db.set_tracked_feed_override("sp500", "AAPL", true).unwrap();
        db.conn
            .lock()
            .unwrap()
            .execute_batch(
                "CREATE TRIGGER reject_exclusion BEFORE INSERT ON tracked_feed_overrides
                 WHEN NEW.included = 0 BEGIN SELECT RAISE(ABORT, 'rejected'); END;",
            )
            .unwrap();
        assert!(db
            .set_tracked_feed_override("sp500", "AAPL", false)
            .is_err());
        assert_eq!(
            db.tracked_feed_overrides("sp500").unwrap(),
            [("AAPL".into(), true)]
        );
    }

    #[test]
    fn existing_database_opens_without_losing_user_data() {
        let file = TestDb::new();
        let previous_version;
        {
            let db = file.open();
            previous_version = db.schema_version().unwrap();
            db.set_watchlist_member("AAPL", true).unwrap();
            db.set_ticker_note("MSFT", Some("Prior note")).unwrap();
            db.conn
                .lock()
                .unwrap()
                .execute("DROP TABLE tracked_feed_overrides", [])
                .unwrap();
        }
        let db = file.open();
        assert_eq!(db.schema_version().unwrap(), previous_version);
        assert_eq!(db.get_watchlist().unwrap(), ["AAPL"]);
        assert_eq!(
            db.get_ticker_note("MSFT").unwrap().as_deref(),
            Some("Prior note")
        );
        assert!(db.tracked_feed_overrides("sp500").unwrap().is_empty());
        db.set_tracked_feed_override("sp500", "TSM", true).unwrap();
    }
}
