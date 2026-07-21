//! Append-only feed diagnostics log (pending quote retries, terminal incomplete set).
//!
//! Path: next to `history.sqlite` in the Tauri app data dir (`feed.log`).
//! The status bar only shows a short summary; full ticker lists go here.

use std::fs::OpenOptions;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

pub struct FeedLog {
    path: PathBuf,
    lock: Mutex<()>,
}

impl FeedLog {
    pub fn new(path: PathBuf) -> Self {
        if let Some(parent) = path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        Self {
            path,
            lock: Mutex::new(()),
        }
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    pub fn info(&self, message: &str) {
        self.write("INFO", message);
    }

    pub fn warn(&self, message: &str) {
        self.write("WARN", message);
    }

    /// Full pending set for an incomplete-quote retry round (sorted, deduped).
    pub fn log_pending_retry(&self, round: usize, max_rounds: usize, pending: &[&str]) {
        let ordered = sorted_unique(pending);
        self.warn(&format!(
            "incomplete quotes retry round {round}/{max_rounds}: {} pending: {}",
            ordered.len(),
            ordered.join(", ")
        ));
    }

    /// Symbols still incomplete after the last retry round.
    pub fn log_terminal_incomplete(&self, pending: &[&str]) {
        let ordered = sorted_unique(pending);
        self.warn(&format!(
            "quote enrichment unfinished after retries: {} still incomplete: {}",
            ordered.len(),
            ordered.join(", ")
        ));
    }

    fn write(&self, level: &str, message: &str) {
        let _guard = self.lock.lock().unwrap_or_else(|e| e.into_inner());
        let ts = format_timestamp_utc();
        if let Ok(mut file) = OpenOptions::new()
            .create(true)
            .append(true)
            .open(&self.path)
        {
            let _ = writeln!(file, "{ts} [{level}] {message}");
        }
    }
}

fn sorted_unique<'a>(pending: &[&'a str]) -> Vec<&'a str> {
    let mut ordered: Vec<&str> = pending.to_vec();
    ordered.sort_unstable();
    ordered.dedup();
    ordered
}

fn format_timestamp_utc() -> String {
    let secs = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let (y, mo, d, h, mi, s) = unix_to_utc_parts(secs);
    format!("{y:04}-{mo:02}-{d:02}T{h:02}:{mi:02}:{s:02}Z")
}

fn unix_to_utc_parts(secs: u64) -> (u64, u64, u64, u64, u64, u64) {
    // Civil date from Unix days (Howard Hinnant algorithm).
    let s = secs % 86_400;
    let h = s / 3_600;
    let mi = (s % 3_600) / 60;
    let sec = s % 60;
    let days = (secs / 86_400) as i64;
    let z = days + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = (z - era * 146_097) as u64;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365;
    let y = yoe as i64 + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = if m <= 2 { y + 1 } else { y };
    (y as u64, m, d, h, mi, sec)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    #[test]
    fn log_pending_retry_writes_full_sorted_ticker_list() {
        let dir =
            std::env::temp_dir().join(format!("vantage_feed_log_test_{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        let path = dir.join("feed.log");
        let log = FeedLog::new(path.clone());
        log.log_pending_retry(4, 6, &["MSFT", "AAPL", "ZZZ", "AAPL"]);
        let body = fs::read_to_string(&path).expect("read log");
        assert!(
            body.contains("incomplete quotes retry round 4/6: 3 pending: AAPL, MSFT, ZZZ"),
            "body was: {body}"
        );
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn log_terminal_incomplete_writes_sticky_set() {
        let dir =
            std::env::temp_dir().join(format!("vantage_feed_log_term_{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        let path = dir.join("feed.log");
        let log = FeedLog::new(path.clone());
        log.log_terminal_incomplete(&["BF.B", "BRK.B"]);
        let body = fs::read_to_string(&path).expect("read log");
        assert!(
            body.contains(
                "quote enrichment unfinished after retries: 2 still incomplete: BF.B, BRK.B"
            ),
            "body was: {body}"
        );
        let _ = fs::remove_dir_all(&dir);
    }
}
