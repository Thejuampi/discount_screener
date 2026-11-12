use rusqlite::{params, OptionalExtension};

use super::Db;
use crate::score_history::validate_symbol;

pub(super) const SCHEMA: &str = "
    CREATE TABLE IF NOT EXISTS ticker_notes (
        symbol TEXT PRIMARY KEY NOT NULL,
        note TEXT NOT NULL
    ) WITHOUT ROWID;
";

impl Db {
    pub(crate) fn get_ticker_note(&self, symbol: &str) -> Result<Option<String>, String> {
        let symbol = validate_symbol(symbol)?;
        let connection = self.conn.lock().map_err(|_| "db lock poisoned")?;
        connection
            .query_row(
                "SELECT note FROM ticker_notes WHERE symbol = ?1",
                [symbol.as_str()],
                |row| row.get(0),
            )
            .optional()
            .map_err(|e| format!("read ticker note: {e}"))
    }

    pub(crate) fn set_ticker_note(&self, symbol: &str, note: Option<&str>) -> Result<(), String> {
        let symbol = validate_symbol(symbol)?;
        let note = note.map(str::trim).filter(|text| !text.is_empty());
        let connection = self.conn.lock().map_err(|_| "db lock poisoned")?;
        match note {
            Some(text) => connection.execute(
                "INSERT INTO ticker_notes (symbol, note) VALUES (?1, ?2)
                 ON CONFLICT (symbol) DO UPDATE SET note = excluded.note",
                params![symbol, text],
            ),
            None => connection.execute("DELETE FROM ticker_notes WHERE symbol = ?1", [symbol]),
        }
        .map_err(|e| format!("write ticker note: {e}"))?;
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
                "ds_ticker_notes_{}_{}",
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
    fn a_ticker_without_a_note_returns_none() {
        let db = Db::open_in_memory().unwrap();
        assert_eq!(db.get_ticker_note("AAPL").unwrap(), None);
    }

    #[test]
    fn notes_use_canonical_keys_and_preserve_internal_text() {
        let db = Db::open_in_memory().unwrap();
        let text = "Revisar márgenes: +2%.\n  Revisar después.";

        db.set_ticker_note(" brk.b ", Some(&format!("  {text}\n\t")))
            .unwrap();
        db.set_ticker_note("BRK-B", Some("Another ticker")).unwrap();

        assert_eq!(db.get_ticker_note("BRK.B").unwrap().as_deref(), Some(text));
        assert_eq!(
            db.get_ticker_note("brk-b").unwrap().as_deref(),
            Some("Another ticker")
        );
    }

    #[test]
    fn notes_survive_reopening_after_create_edit_and_delete() {
        let file = TestDb::new();
        file.open()
            .set_ticker_note("MSFT", Some("First note"))
            .unwrap();
        {
            let db = file.open();
            assert_eq!(
                db.get_ticker_note("MSFT").unwrap().as_deref(),
                Some("First note")
            );
            db.set_ticker_note(" msft ", Some("Edited note")).unwrap();
        }
        {
            let db = file.open();
            assert_eq!(
                db.get_ticker_note("MSFT").unwrap().as_deref(),
                Some("Edited note")
            );
            db.set_ticker_note("msft", None).unwrap();
            db.set_ticker_note("MSFT", None).unwrap();
        }
        assert_eq!(file.open().get_ticker_note("MSFT").unwrap(), None);
    }

    #[test]
    fn saving_whitespace_deletes_the_note() {
        let db = Db::open_in_memory().unwrap();
        db.set_ticker_note("AAPL", Some("First note")).unwrap();
        db.set_ticker_note("AAPL", Some(" \n\t")).unwrap();
        assert_eq!(db.get_ticker_note("AAPL").unwrap(), None);
    }

    #[test]
    fn invalid_tickers_cannot_be_read_or_changed() {
        let db = Db::open_in_memory().unwrap();
        db.set_ticker_note("AAPL", Some("Keep this")).unwrap();
        for symbol in ["", "A APL", "AAPL;", "ABCDEFGHIJKLMNOPQ"] {
            assert!(db.get_ticker_note(symbol).is_err());
            assert!(db.set_ticker_note(symbol, Some("Invalid")).is_err());
            assert!(db.set_ticker_note(symbol, None).is_err());
        }
        assert_eq!(
            db.get_ticker_note("AAPL").unwrap().as_deref(),
            Some("Keep this")
        );
    }

    #[test]
    fn existing_database_gains_notes_without_losing_user_data() {
        let file = TestDb::new();
        let previous_version;
        {
            let db = file.open();
            previous_version = db.schema_version().unwrap();
            db.set_watchlist_member("AAPL", true).unwrap();
            db.portfolio_add("AAPL", 1.0, 25_000, None, None).unwrap();
            db.conn
                .lock()
                .unwrap()
                .execute("DROP TABLE ticker_notes", [])
                .unwrap();
        }

        let db = file.open();
        assert_eq!(db.schema_version().unwrap(), previous_version);
        assert_eq!(db.get_watchlist().unwrap(), ["AAPL"]);
        assert_eq!(db.portfolio_list().unwrap()[0].symbol, "AAPL");
        assert_eq!(db.get_ticker_note("AAPL").unwrap(), None);
        db.set_ticker_note("AAPL", Some("New note")).unwrap();
        assert_eq!(
            db.get_ticker_note("AAPL").unwrap().as_deref(),
            Some("New note")
        );
    }
}
