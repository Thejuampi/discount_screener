use super::Db;
use crate::score_history::validate_symbol;

pub(super) const SCHEMA: &str = "
    CREATE TABLE IF NOT EXISTS watchlist (
        symbol TEXT PRIMARY KEY NOT NULL
    ) WITHOUT ROWID;
";

impl Db {
    pub(crate) fn get_watchlist(&self) -> Result<Vec<String>, String> {
        let connection = self.conn.lock().map_err(|_| "db lock poisoned")?;
        let mut statement = connection
            .prepare_cached("SELECT symbol FROM watchlist ORDER BY symbol ASC")
            .map_err(|e| format!("prepare watchlist: {e}"))?;
        let rows = statement
            .query_map([], |row| row.get(0))
            .map_err(|e| format!("query watchlist: {e}"))?;
        rows.collect::<rusqlite::Result<_>>()
            .map_err(|e| format!("read watchlist: {e}"))
    }

    pub(crate) fn set_watchlist_member(&self, symbol: &str, included: bool) -> Result<(), String> {
        let symbol = validate_symbol(symbol)?;
        let connection = self.conn.lock().map_err(|_| "db lock poisoned")?;
        let sql = if included {
            "INSERT INTO watchlist (symbol) VALUES (?1) ON CONFLICT (symbol) DO NOTHING"
        } else {
            "DELETE FROM watchlist WHERE symbol = ?1"
        };
        connection
            .execute(sql, [symbol.as_str()])
            .map_err(|e| format!("write watchlist: {e}"))?;
        Ok(())
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
                "ds_watchlist_{}_{}",
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
    fn empty_database_has_no_members() {
        let db = Db::open_in_memory().unwrap();
        assert!(db.get_watchlist().unwrap().is_empty());
    }

    #[test]
    fn membership_is_normalized_idempotent_and_ordered() {
        let db = Db::open_in_memory().unwrap();
        for symbol in [" eurusd=x ", "brk.b", "^gspc", "BRK.B", "BRK-B", "aapl"] {
            db.set_watchlist_member(symbol, true).unwrap();
        }

        assert_eq!(
            db.get_watchlist().unwrap(),
            ["AAPL", "BRK-B", "BRK.B", "EURUSD=X", "^GSPC"]
        );
    }

    #[test]
    fn invalid_symbols_leave_membership_unchanged() {
        let db = Db::open_in_memory().unwrap();
        db.set_watchlist_member("AAPL", true).unwrap();

        for symbol in [" ", "A APL", "AAPL;", "ABCDEFGHIJKLMNOPQ"] {
            assert!(db.set_watchlist_member(symbol, true).is_err());
            assert!(db.set_watchlist_member(symbol, false).is_err());
        }
        assert_eq!(db.get_watchlist().unwrap(), ["AAPL"]);
    }

    #[test]
    fn additions_and_removals_survive_reopening() {
        let file = TestDb::new();
        {
            let db = file.open();
            db.set_watchlist_member("AAPL", true).unwrap();
            db.set_watchlist_member("MSFT", true).unwrap();
        }
        {
            let db = file.open();
            assert_eq!(db.get_watchlist().unwrap(), ["AAPL", "MSFT"]);
            db.set_watchlist_member(" msft ", false).unwrap();
            db.set_watchlist_member("MSFT", false).unwrap();
        }
        assert_eq!(file.open().get_watchlist().unwrap(), ["AAPL"]);
    }

    #[test]
    fn database_without_watchlist_opens_and_preserves_existing_data() {
        let file = TestDb::new();
        let previous_version;
        {
            let db = file.open();
            previous_version = db.schema_version().unwrap();
            db.portfolio_add("AAPL", 1.0, 25_000, None, None).unwrap();
            db.conn
                .lock()
                .unwrap()
                .execute("DROP TABLE watchlist", [])
                .unwrap();
        }

        let db = file.open();
        assert_eq!(db.schema_version().unwrap(), previous_version);
        assert_eq!(db.portfolio_list().unwrap()[0].symbol, "AAPL");
        assert!(db.get_watchlist().unwrap().is_empty());
        db.set_watchlist_member("MSFT", true).unwrap();
        assert_eq!(db.get_watchlist().unwrap(), ["MSFT"]);
    }
}
