//! `vantage-headless` — V3 pricing-engine daemon + thin CLI client.
//!
//!   vantage-headless serve  [--profile sp500] [--semantics android|tauri]
//!                           [--addr 127.0.0.1:7979] [--allow-remote]
//!                           [--data-dir DIR] [--market-context]
//!   vantage-headless rank   [--model v3|v2|short] [--semantics android|tauri]
//!                           [--top N] [--json] [--addr ..]
//!   vantage-headless status [--addr ..]
//!   vantage-headless parity [--model v3|v2|short] [--addr ..]
//!   vantage-headless persist [--addr ..]
//!   vantage-headless rank-snapshot FILE [--model ..] [--top N] [--json]   (offline)
//!
//! Default address: 127.0.0.1:7979 (loopback only unless `--allow-remote`).
//! `--semantics android` (default) ranks Long V3 exactly like the Android app
//! (`apps/android/core`); `tauri` reproduces the desktop Opportunities list.
//! V2 and Short have no Android port here and always use desktop semantics.
//! Market context ("Contexto de mercado") is OFF by default.

use std::collections::HashMap;
use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::PathBuf;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
use std::time::Duration;

use discount_screener_windows_lib::headless::ScoringModel;
use discount_screener_windows_lib::headless::{
    android_to_ranked, canonical_profile, parse_model, rank_snapshot, to_ranked, EngineConfig,
    HeadlessEngine, HistoryWrite, OpportunitySnapshot, RankedRow, Semantics, HISTORY_MODEL,
    SNAPSHOT_FILE,
};
use discount_screener_windows_lib::score_history::{
    default_backup_dir, format_in_tz, lock_data_dir, restore, validate_days, validate_symbol,
    verify_file, RecordOutcome, RunMeta, ScoreHistoryStore,
};

const DEFAULT_ADDR: &str = "127.0.0.1:7979";
const MAX_CONNECTIONS: usize = 16;
const READ_TIMEOUT: Duration = Duration::from_secs(5);

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let code = match run(args) {
        Ok(()) => 0,
        Err(e) => {
            eprintln!("error: {e}");
            1
        }
    };
    std::process::exit(code);
}

struct Opts {
    positional: Vec<String>,
    flags: HashMap<String, String>,
}

fn parse_opts(args: &[String]) -> Opts {
    let mut positional = Vec::new();
    let mut flags = HashMap::new();
    let mut i = 0;
    while i < args.len() {
        let a = &args[i];
        if let Some(name) = a.strip_prefix("--") {
            if let Some((k, v)) = name.split_once('=') {
                flags.insert(k.to_string(), v.to_string());
            } else if matches!(
                name,
                "json"
                    | "market-context"
                    | "help"
                    | "allow-remote"
                    | "changes"
                    | "no-feed"
                    | "verify"
                    | "force"
            ) {
                flags.insert(name.to_string(), "1".into());
            } else if i + 1 < args.len() && !args[i + 1].starts_with("--") {
                flags.insert(name.to_string(), args[i + 1].clone());
                i += 1;
            }
        } else if a == "-h" {
            flags.insert("help".into(), "1".into());
        } else {
            positional.push(a.clone());
        }
        i += 1;
    }
    Opts { positional, flags }
}

fn usage() -> &'static str {
    "usage: vantage-headless <serve|rank|status|parity|persist|rank-snapshot|history> [options]\n\
     common: --addr HOST:PORT   (default 127.0.0.1:7979)\n\
     serve:  --profile sp500|dow|merval|... --semantics android|tauri (feed universe; default android)\n\
     \x20       --data-dir DIR [--market-context] [--allow-remote]\n\
     \x20       (non-loopback --addr is refused unless --allow-remote)\n\
     rank:   --model v3|v2|short --semantics android|tauri (default android) --top N [--json]\n\
     \x20       android = Long V3 as the Android app ranks it (qualified rows, Act/Watch/Avoid)\n\
     parity: --model v3|v2|short   (desktop command path vs headless, same instant)\n\
     rank-snapshot FILE --model .. --top N [--json]   (offline replay)\n\
history TICKER [--days N] [--changes] [--profile P] [--tz IANA] [--json]\n\
     \x20       (score history via GET /history; dates in --tz, default America/New_York)\n\
     history-record-snapshot --data-dir DIR [--history-backup-dir B]   (offline: record the persisted snapshot)\n\
     history-backup --data-dir DIR [--history-backup-dir B] [--verify]   (verified backup now; --verify checks all)\n\
     history-restore FILE --data-dir DIR [--history-backup-dir B]   (append-only merge; refused while serve runs)\n\
serve:  --history-interval-hours H (default 6; 0 disables score history)\n\
     \x20       --history-backup-dir B (default $VANTAGE_HISTORY_BACKUP_DIR, else $XDG_DATA_HOME/vantage/backups\n\
     \x20       or ~/.local/share/vantage/backups) --no-feed (offline)"
}

fn run(args: Vec<String>) -> Result<(), String> {
    let o = parse_opts(&args);
    let cmd = o.positional.first().map(String::as_str).unwrap_or("help");
    if o.flags.contains_key("help") || cmd == "help" {
        println!("{}", usage());
        return Ok(());
    }
    let addr = o
        .flags
        .get("addr")
        .cloned()
        .unwrap_or_else(|| DEFAULT_ADDR.into());
    let top: Option<usize> = match o.flags.get("top") {
        Some(t) => Some(t.parse().map_err(|_| format!("bad --top {t}"))?),
        None => None,
    };
    let model = o.flags.get("model").cloned().unwrap_or_else(|| "v3".into());
    let json = o.flags.contains_key("json");
    let semantics = o
        .flags
        .get("semantics")
        .cloned()
        .unwrap_or_else(|| "android".into());
    match cmd {
        "serve" => serve(&o, &addr),
        "rank" => {
            parse_model(&model)?;
            Semantics::parse(&semantics)?;
            let mut path = format!("/ranking?model={model}&semantics={semantics}");
            if let Some(n) = top {
                path.push_str(&format!("&top={n}"));
            }
            let body = http_req(&addr, "GET", &path)?;
            if json {
                println!("{body}");
            } else {
                let v: serde_json::Value =
                    serde_json::from_str(&body).map_err(|e| e.to_string())?;
                let rows: Vec<RankedRow> =
                    serde_json::from_value(v["rows"].clone()).map_err(|e| e.to_string())?;
                println!(
                    "{} · {} · semantics={} · ranked={} · source={} · context={}",
                    v["profile"].as_str().unwrap_or("?"),
                    v["model"].as_str().unwrap_or("?"),
                    v["semantics"].as_str().unwrap_or("?"),
                    v["ranked_total"].as_u64().unwrap_or(0),
                    v["source"].as_str().unwrap_or("?"),
                    if v["market_context"].as_bool() == Some(true) {
                        "on"
                    } else {
                        "off"
                    }
                );
                print!("{}", render_table(&rows));
            }
            Ok(())
        }
        "status" | "parity" | "persist" => {
            let (method, path) = match cmd {
                "parity" => ("GET", format!("/parity?model={model}")),
                "persist" => ("POST", "/persist".into()),
                _ => ("GET", "/status".into()),
            };
            println!("{}", http_req(&addr, method, &path)?);
            Ok(())
        }
        "history" => {
            let sym = o
                .positional
                .get(1)
                .ok_or("history needs TICKER (vantage-headless history ADSK)")?;
            let sym = validate_symbol(sym)?;
            let tz: chrono_tz::Tz = o
                .flags
                .get("tz")
                .map(String::as_str)
                .unwrap_or(DEFAULT_CLI_TZ)
                .parse()
                .map_err(|_| format!("bad --tz (expected an IANA zone like {DEFAULT_CLI_TZ})"))?;
            let mut path = format!("/history?symbol={}", pct(&sym));
            if let Some(d) = o.flags.get("days") {
                path.push_str(&format!("&days={}", pct(d)));
            }
            if let Some(p) = o.flags.get("profile") {
                path.push_str(&format!("&profile={}", pct(p)));
            }
            let changes = o.flags.contains_key("changes");
            if changes {
                path.push_str("&changes=1");
            }
            let body = http_req(&addr, "GET", &path)?;
            let mut v: serde_json::Value =
                serde_json::from_str(&body).map_err(|e| e.to_string())?;
            if json {
                add_local_times(&mut v, tz);
                println!("{}", serde_json::to_string_pretty(&v).unwrap());
            } else {
                print!("{}", render_history(&v, changes, tz));
            }
            Ok(())
        }
        "history-record-snapshot" => {
            let data_dir = data_dir_flag(&o)?;
            let _lock = lock_data_dir(&data_dir)?;
            let snap = OpportunitySnapshot::load(&data_dir.join(SNAPSHOT_FILE))?;
            let u = discount_screener_windows_lib::headless::android_universe(&snap.profile);
            let rows = android_to_ranked(
                &discount_screener_windows_lib::headless::rank_android_v3(&snap, u.as_deref()),
            );
            let store = ScoreHistoryStore::open(
                &data_dir.join("history.sqlite"),
                Some(&backup_dir_flag(&o)),
            )?;
            if let Some(b) = &store.migration_backup {
                eprintln!("pre-migration backup: {}", b.display());
            }
            let meta = RunMeta {
                captured_at: snap.captured_at_epoch,
                profile: canonical_profile(&snap.profile),
                model: HISTORY_MODEL.into(),
                semantics: "android".into(),
                market_context: false,
                binary_version: env!("CARGO_PKG_VERSION").to_string(),
                trigger: "snapshot".into(),
                symbols_total: u.as_ref().map_or(0, Vec::len),
                incomplete_symbols: vec![],
            };
            match store.record_run(&meta, &rows)? {
                RecordOutcome::Recorded { rows, skipped } => println!(
                    "recorded run {} ({rows} rows, {} skipped for a non-finite price)",
                    meta.run_id(),
                    skipped.len()
                ),
                RecordOutcome::Duplicate => {
                    println!("run {} already recorded: no-op", meta.run_id())
                }
            }
            Ok(())
        }
        "history-backup" => {
            let data_dir = data_dir_flag(&o)?;
            let dir = backup_dir_flag(&o);
            let store = ScoreHistoryStore::open(&data_dir.join("history.sqlite"), Some(&dir))?;
            let dest = dir.join(format!("history.manual-{}.sqlite.gz", stamp_now()));
            let rep = store.export_to(&dest)?;
            score_history::rotate_pre_backups(&dir)?;
            println!(
                "backup {} integrity={} score_runs={} score_history={}",
                rep.path.display(),
                rep.integrity,
                rep.score_runs,
                rep.score_history
            );
            println!("  {} bytes (score tables only, gzip)", rep.bytes);
            if o.flags.contains_key("verify") {
                let mut files: Vec<PathBuf> = std::fs::read_dir(&dir)
                    .map_err(|e| format!("read {}: {e}", dir.display()))?
                    .flatten()
                    .map(|e| e.path())
                    .filter(|p| {
                        let n = p.to_string_lossy();
                        n.ends_with(".sqlite.gz") || n.ends_with(".sqlite")
                    })
                    .collect();
                files.sort();
                let mut bad = 0;
                for f in files {
                    match verify_file(&f, false) {
                        Ok(r) => println!(
                            "  ok  {} score_runs={} score_history={}",
                            f.display(),
                            r.score_runs,
                            r.score_history
                        ),
                        Err(e) => {
                            bad += 1;
                            println!("  BAD {e}");
                        }
                    }
                }
                if bad > 0 {
                    return Err(format!("{bad} backup(s) failed verification"));
                }
            }
            Ok(())
        }
        "history-restore" => {
            let file = o.positional.get(1).ok_or("history-restore needs FILE")?;
            let data_dir = data_dir_flag(&o)?;
            let r = restore(&PathBuf::from(file), &data_dir, &backup_dir_flag(&o))?;
            if let Some(p) = &r.pre_restore_backup {
                println!("score tables backed up first to {}", p.display());
            }
            let live = data_dir.join("history.sqlite");
            let v = verify_file(&live, true)?;
            println!(
                "merged {file} into {} (append-only): +{} runs, +{} rows; now score_runs={} score_history={}",
                live.display(),
                r.runs_added,
                r.rows_added,
                v.score_runs,
                v.score_history
            );
            Ok(())
        }
        "rank-snapshot" => {
            let file = o.positional.get(1).ok_or("rank-snapshot needs FILE")?;
            let snap = OpportunitySnapshot::load(&PathBuf::from(file))?;
            let model = parse_model(&model)?;
            let rows = match (Semantics::parse(&semantics)?, model) {
                (Semantics::Android, ScoringModel::AggressiveV3) => {
                    let u =
                        discount_screener_windows_lib::headless::android_universe(&snap.profile);
                    android_to_ranked(&discount_screener_windows_lib::headless::rank_android_v3(
                        &snap,
                        u.as_deref(),
                    ))
                }
                _ => to_ranked(&rank_snapshot(&snap, model, None), model),
            };
            let rows: Vec<RankedRow> = rows.into_iter().take(top.unwrap_or(usize::MAX)).collect();
            if json {
                println!("{}", serde_json::to_string_pretty(&rows).unwrap());
            } else {
                print!("{}", render_table(&rows));
            }
            Ok(())
        }
        other => Err(format!("unknown command '{other}'\n{}", usage())),
    }
}

fn render_history(v: &serde_json::Value, changes: bool, tz: chrono_tz::Tz) -> String {
    let sym = v["symbol"].as_str().unwrap_or("?");
    let when = |x: &serde_json::Value| format_in_tz(x.as_i64().unwrap_or(0), tz);
    let n = |x: &serde_json::Value| {
        x.as_i64()
            .map(|n| format!("{n:+}"))
            .unwrap_or_else(|| "—".into())
    };
    let mut out = String::new();
    let profile = v["profile"].as_str().unwrap_or("");
    if changes {
        let list = v["changes"].as_array().cloned().unwrap_or_default();
        out.push_str(&format!(
            "{sym} · {profile} · decision changes: {} (times {tz})\n",
            list.len()
        ));
        for c in &list {
            out.push_str(&format!(
                "{} → {}  {} → {}  composite {}  F {}  T {}  Fc {}\n",
                when(&c["from_at"]),
                when(&c["to_at"]),
                c["from"].as_str().unwrap_or(""),
                c["to"].as_str().unwrap_or(""),
                n(&c["composite_delta"]),
                n(&c["fundamentals_delta"]),
                n(&c["technical_delta"]),
                n(&c["forecast_delta"]),
            ));
        }
        return out;
    }
    let pts = v["points"].as_array().cloned().unwrap_or_default();
    out.push_str(&format!(
        "{sym} · {profile} · runs: {} (times {tz})\n",
        pts.len()
    ));
    out.push_str("date                  rules trigger  decision  comp     F     T    Fc  conf         price   target\n");
    for p in &pts {
        let cents = |x: &serde_json::Value| {
            x.as_i64()
                .map(|c| format!("{:.2}", c as f64 / 100.0))
                .unwrap_or_else(|| "—".into())
        };
        let rules = format!(
            "{}{}",
            p["rule_version"].as_str().unwrap_or(""),
            if p["rule_change"].as_bool() == Some(true) {
                "*"
            } else {
                ""
            }
        );
        out.push_str(&format!(
            "{:<21} {:<5} {:<8} {:<8} {:>5} {:>5} {:>5} {:>5}  {:<11} {:>7} {:>8}\n",
            when(&p["captured_at"]),
            rules,
            p["trigger"].as_str().unwrap_or(""),
            p["decision"].as_str().unwrap_or(""),
            n(&p["composite"]),
            n(&p["fundamentals"]),
            n(&p["technical"]),
            n(&p["forecast"]),
            p["confidence"].as_str().unwrap_or("—"),
            cents(&p["price_cents"]),
            cents(&p["target_cents"]),
        ));
    }
    out
}

const DEFAULT_CLI_TZ: &str = "America/New_York";

/// `--json`: keep UTC fields, add `captured_at_local` and the zone.
fn add_local_times(v: &mut serde_json::Value, tz: chrono_tz::Tz) {
    v["tz"] = tz.name().into();
    if let Some(pts) = v["points"].as_array_mut() {
        for p in pts {
            let at = p["captured_at"].as_i64().unwrap_or(0);
            p["captured_at_local"] = format_in_tz(at, tz).into();
        }
    }
    if let Some(cs) = v["changes"].as_array_mut() {
        for c in cs {
            for (k, l) in [("from_at", "from_local"), ("to_at", "to_local")] {
                let at = c[k].as_i64().unwrap_or(0);
                c[l] = format_in_tz(at, tz).into();
            }
        }
    }
}

/// Percent-encodes everything outside the unreserved set (RFC 3986).
fn pct(s: &str) -> String {
    s.bytes()
        .map(|b| match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'.' | b'_' | b'~' => {
                (b as char).to_string()
            }
            _ => format!("%{b:02X}"),
        })
        .collect()
}

/// Strict percent-decoding on bytes: `%` must be followed by two hex digits and the
/// result must be UTF-8; anything else is an error (-> 400), never a panic.
fn pct_decode(s: &str) -> Result<String, String> {
    fn hex(c: u8) -> Option<u8> {
        match c {
            b'0'..=b'9' => Some(c - b'0'),
            b'a'..=b'f' => Some(c - b'a' + 10),
            b'A'..=b'F' => Some(c - b'A' + 10),
            _ => None,
        }
    }
    let b = s.as_bytes();
    let mut out = Vec::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        match b[i] {
            b'%' => {
                let pair = b
                    .get(i + 1)
                    .copied()
                    .and_then(hex)
                    .zip(b.get(i + 2).copied().and_then(hex));
                let (h, l) = pair.ok_or_else(|| "bad percent-encoding in query".to_string())?;
                out.push(h << 4 | l);
                i += 3;
            }
            b'+' => {
                out.push(b' ');
                i += 1;
            }
            c => {
                out.push(c);
                i += 1;
            }
        }
    }
    String::from_utf8(out).map_err(|_| "query is not valid UTF-8".to_string())
}

fn data_dir_flag(o: &Opts) -> Result<PathBuf, String> {
    Ok(o.flags
        .get("data-dir")
        .map(PathBuf::from)
        .unwrap_or_else(default_data_dir))
}

fn backup_dir_flag(o: &Opts) -> PathBuf {
    o.flags
        .get("history-backup-dir")
        .map(PathBuf::from)
        .unwrap_or_else(default_backup_dir)
}

fn stamp_now() -> String {
    chrono::Utc::now().format("%Y%m%dT%H%M%S%.3fZ").to_string()
}

fn signed(v: Option<i32>) -> String {
    v.map(|n| format!("{n:+}")).unwrap_or_else(|| "—".into())
}

fn render_table(rows: &[RankedRow]) -> String {
    let opt = |v: Option<f64>, f: &dyn Fn(f64) -> String| v.map(f).unwrap_or_else(|| "—".into());
    let cells: Vec<[String; 10]> = rows
        .iter()
        .map(|r| {
            [
                r.rank.to_string(),
                r.symbol.clone(),
                format!("{:+}", r.composite_score),
                r.setup_label.clone(),
                r.confidence.clone().unwrap_or_else(|| "—".into()),
                format!("{:.2}", r.price),
                opt(r.target, &|t| format!("{t:.2}")),
                opt(r.gap_pct, &|g| format!("{g:+.1}")),
                format!(
                    "{}·{}·{}·{}",
                    signed(r.fundamentals),
                    signed(r.technical),
                    signed(r.forecast),
                    signed(r.regime)
                ),
                r.sector.clone().unwrap_or_default(),
            ]
        })
        .collect();
    let head = [
        "#",
        "SYMBOL",
        "SCORE",
        "SETUP",
        "CONF",
        "PRICE",
        "TARGET",
        "GAP%",
        "F·T·C·R",
        "SECTOR",
    ];
    // Column widths from the data (chars, not bytes: "—" and "·" are multibyte).
    let mut w: Vec<usize> = head.iter().map(|h| h.chars().count()).collect();
    for row in &cells {
        for (i, c) in row.iter().enumerate() {
            w[i] = w[i].max(c.chars().count());
        }
    }
    let left = [
        false, true, false, true, true, false, false, false, false, true,
    ];
    let line = |vals: Vec<&str>| {
        let mut out = String::new();
        for (i, v) in vals.iter().enumerate() {
            let pad = w[i] - v.chars().count();
            if i > 0 {
                out.push(' ');
            }
            if left[i] {
                out.push_str(v);
                if i + 1 < vals.len() {
                    out.push_str(&" ".repeat(pad));
                }
            } else {
                out.push_str(&" ".repeat(pad));
                out.push_str(v);
            }
        }
        out.push('\n');
        out
    };
    let mut s = line(head.to_vec());
    for row in &cells {
        s.push_str(&line(row.iter().map(String::as_str).collect()));
    }
    s
}

// ── tiny HTTP (std only, loopback) ────────────────────────────────────────────

fn http_req(addr: &str, method: &str, path: &str) -> Result<String, String> {
    let mut s = TcpStream::connect(addr)
        .map_err(|e| format!("connect {addr}: {e} (is `vantage-headless serve` running?)"))?;
    s.set_read_timeout(Some(Duration::from_secs(120))).ok();
    write!(
        s,
        "{method} {path} HTTP/1.0\r\nHost: {addr}\r\nContent-Length: 0\r\n\r\n"
    )
    .map_err(|e| e.to_string())?;
    let mut buf = String::new();
    s.read_to_string(&mut buf).map_err(|e| e.to_string())?;
    let (head, body) = buf.split_once("\r\n\r\n").ok_or("malformed response")?;
    if !head.starts_with("HTTP/1.0 200") {
        return Err(body.to_string());
    }
    Ok(body.to_string())
}

fn serve(o: &Opts, addr: &str) -> Result<(), String> {
    let profile = o
        .flags
        .get("profile")
        .cloned()
        .unwrap_or_else(|| "sp500".into());
    let data_dir = o
        .flags
        .get("data-dir")
        .map(PathBuf::from)
        .unwrap_or_else(default_data_dir);
    let market_context = o.flags.contains_key("market-context");
    let allow_remote = o.flags.contains_key("allow-remote");
    let history_hours: i64 = match o.flags.get("history-interval-hours") {
        Some(h) => h
            .parse()
            .ok()
            .filter(|n| *n >= 0)
            .ok_or_else(|| format!("bad --history-interval-hours {h}"))?,
        None => 6,
    };
    check_bind_addr(addr, allow_remote)?;
    // Held for the life of the process: a second serve or a restore on this data-dir refuses.
    let _lock = discount_screener_windows_lib::score_history::lock_data_dir(&data_dir)?;
    // Feed universe: android (default) loads exactly Android's profile list;
    // tauri loads the desktop composition (+ ETFs + crypto).
    let android_feed = Semantics::parse(
        o.flags
            .get("semantics")
            .map(String::as_str)
            .unwrap_or("android"),
    )? == Semantics::Android;
    let engine = HeadlessEngine::start(EngineConfig {
        data_dir: data_dir.clone(),
        profile,
        market_context,
        fetch_year_charts: android_feed,
        android_universe: android_feed,
        history_interval_secs: history_hours * 3600,
        history_backup_dir: (history_hours > 0).then(|| backup_dir_flag(o)),
        no_feed: o.flags.contains_key("no-feed"),
    })?;
    if o.flags.contains_key("no-feed") {
        eprintln!("vantage-headless: --no-feed (offline: no quote feed, Year charts or regime)");
    }
    let listener = TcpListener::bind(addr).map_err(|e| format!("bind {addr}: {e}"))?;
    eprintln!(
        "vantage-headless: profile={} semantics={} context={} data={} listening on http://{addr}",
        engine.profile(),
        if android_feed { "android" } else { "tauri" },
        if market_context { "on" } else { "off" },
        data_dir.display()
    );
    {
        let engine = Arc::clone(&engine);
        std::thread::spawn(move || loop {
            std::thread::sleep(Duration::from_secs(60));
            if let Err(e) = engine.persist() {
                eprintln!("persist: {e}");
            }
            match engine.record_history_if_due(now_epoch()) {
                Ok(HistoryWrite::Recorded {
                    trigger,
                    rows,
                    skipped,
                }) => eprintln!(
                    "score history: recorded {trigger} run ({rows} rows, {} skipped)",
                    skipped.len()
                ),
                Ok(HistoryWrite::Duplicate(id)) => {
                    eprintln!("score history: run {id} already stored (no-op)")
                }
                Ok(HistoryWrite::Skipped(_)) => {}
                Err(e) => eprintln!("score history: {e}"),
            }
            match engine.backup_history_if_due(now_epoch()) {
                Ok(Some(r)) => eprintln!(
                    "score history: daily backup {} ok ({} runs, {} rows)",
                    r.path.display(),
                    r.score_runs,
                    r.score_history
                ),
                Ok(None) => {}
                Err(e) => eprintln!("score history backup: {e}"),
            }
        });
    }
    let active = Arc::new(AtomicUsize::new(0));
    for mut stream in listener.incoming().flatten() {
        let _ = stream.set_read_timeout(Some(READ_TIMEOUT));
        let _ = stream.set_write_timeout(Some(READ_TIMEOUT));
        if active.fetch_add(1, Ordering::SeqCst) >= MAX_CONNECTIONS {
            active.fetch_sub(1, Ordering::SeqCst);
            let _ = respond(
                &mut stream,
                "503 Service Unavailable",
                "too many connections",
            );
            continue;
        }
        let engine = Arc::clone(&engine);
        let active = Arc::clone(&active);
        std::thread::spawn(move || {
            handle(stream, &engine, allow_remote);
            active.fetch_sub(1, Ordering::SeqCst);
        });
    }
    Ok(())
}

/// The daemon has no auth: refuse to listen beyond loopback unless asked to.
fn check_bind_addr(addr: &str, allow_remote: bool) -> Result<(), String> {
    use std::net::ToSocketAddrs;
    let resolved: Vec<_> = addr
        .to_socket_addrs()
        .map_err(|e| format!("bad --addr {addr}: {e}"))?
        .collect();
    if resolved.is_empty() {
        return Err(format!("bad --addr {addr}"));
    }
    if !allow_remote && resolved.iter().any(|a| !a.ip().is_loopback()) {
        return Err(format!(
            "refusing non-loopback --addr {addr} (no auth); pass --allow-remote to override"
        ));
    }
    Ok(())
}

fn respond(stream: &mut TcpStream, status: &str, body: &str) -> std::io::Result<()> {
    write!(
        stream,
        "HTTP/1.0 {status}\r\nContent-Type: application/json\r\nContent-Length: {}\r\n\r\n{body}",
        body.len()
    )
}

fn default_data_dir() -> PathBuf {
    std::env::var_os("XDG_STATE_HOME")
        .map(PathBuf::from)
        .or_else(|| std::env::var_os("HOME").map(|h| PathBuf::from(h).join(".local/state")))
        .unwrap_or_else(|| PathBuf::from("."))
        .join("vantage-headless")
}

/// Request head as read from the socket.
struct Head {
    method: String,
    target: String,
    host: Option<String>,
}

/// Read the request line and headers under ONE deadline for the whole head
/// (a slow client trickling bytes cannot hold a slot past `READ_TIMEOUT`).
fn read_head(mut stream: &TcpStream) -> Result<Head, &'static str> {
    let deadline = std::time::Instant::now() + READ_TIMEOUT;
    let mut buf = Vec::with_capacity(512);
    let mut chunk = [0u8; 512];
    while !buf.windows(4).any(|w| w == b"\r\n\r\n") && !buf.windows(2).any(|w| w == b"\n\n") {
        let left = deadline
            .checked_duration_since(std::time::Instant::now())
            .filter(|d| !d.is_zero())
            .ok_or("timeout")?;
        stream.set_read_timeout(Some(left)).map_err(|_| "io")?;
        match stream.read(&mut chunk) {
            Ok(0) => break,
            Ok(n) => buf.extend_from_slice(&chunk[..n]),
            Err(_) => return Err("timeout"),
        }
        if buf.len() > 16 * 1024 {
            return Err("request head too large");
        }
    }
    let text = String::from_utf8_lossy(&buf);
    let mut lines = text.lines();
    let first = lines
        .next()
        .filter(|l| !l.is_empty())
        .ok_or("empty request")?;
    let mut parts = first.split_whitespace();
    let method = parts.next().unwrap_or("").to_string();
    let target = parts.next().unwrap_or("/").to_string();
    let host = lines.take_while(|l| !l.is_empty()).find_map(|l| {
        let (k, v) = l.split_once(':')?;
        k.trim()
            .eq_ignore_ascii_case("host")
            .then(|| v.trim().to_string())
    });
    Ok(Head {
        method,
        target,
        host,
    })
}

/// DNS-rebinding guard: on a loopback daemon only loopback Host names are accepted.
fn host_allowed(host: Option<&str>, allow_remote: bool) -> bool {
    if allow_remote {
        return true;
    }
    let Some(h) = host else { return false };
    let name = if let Some(rest) = h.strip_prefix('[') {
        rest.split(']').next().unwrap_or("")
    } else {
        h.rsplit_once(':').map(|(n, _)| n).unwrap_or(h)
    };
    matches!(
        name.to_ascii_lowercase().as_str(),
        "localhost" | "127.0.0.1" | "::1"
    )
}

fn handle(mut stream: TcpStream, engine: &HeadlessEngine, allow_remote: bool) {
    let head = match read_head(&stream) {
        Ok(h) => h,
        Err("timeout") => {
            let _ = respond(&mut stream, "408 Request Timeout", "timeout");
            return;
        }
        Err(e) => {
            let _ = respond(&mut stream, "400 Bad Request", e);
            return;
        }
    };
    if !host_allowed(head.host.as_deref(), allow_remote) {
        let _ = respond(
            &mut stream,
            "421 Misdirected Request",
            "Host must be a loopback name",
        );
        return;
    }
    let (status, body) = route(engine, &head.method, &head.target);
    let _ = respond(&mut stream, status, &body);
}

fn route(engine: &HeadlessEngine, method: &str, target: &str) -> (&'static str, String) {
    let (path, query) = target.split_once('?').unwrap_or((target, ""));
    let q: HashMap<&str, &str> = query
        .split('&')
        .filter_map(|kv| kv.split_once('='))
        .collect();
    let expected = if path == "/persist" { "POST" } else { "GET" };
    if path == "/history" && method == "GET" {
        return to_http(history_endpoint(&q, |sym, days, changes, profile| {
            engine.history_json(sym, days, changes, now_epoch(), profile)
        }));
    }
    if method != expected {
        return (
            "405 Method Not Allowed",
            format!("{path} requires {expected}"),
        );
    }
    let result: Result<serde_json::Value, String> = (|| match path {
        "/status" => Ok(serde_json::to_value(engine.status()).unwrap()),
        "/persist" => Ok(serde_json::json!({ "persisted_rows": engine.persist()? })),
        "/ranking" => {
            let model = parse_model(q.get("model").copied().unwrap_or("v3"))?;
            let semantics = Semantics::parse(q.get("semantics").copied().unwrap_or("android"))?;
            let top: usize = match q.get("top") {
                Some(t) => t
                    .parse()
                    .map_err(|_| format!("bad top '{t}' (expected a positive integer)"))?,
                None => usize::MAX,
            };
            let (all, source, used) = match (semantics, model) {
                (Semantics::Android, ScoringModel::AggressiveV3) => {
                    let (rows, source) = engine.android_ranking();
                    (android_to_ranked(&rows), source, "android")
                }
                _ => {
                    let (rows, source) = engine.ranking(model);
                    (to_ranked(&rows, model), source, "tauri")
                }
            };
            let total = all.len();
            let rows: Vec<RankedRow> = all.into_iter().take(top).collect();
            Ok(serde_json::json!({
                "profile": engine.profile(),
                "model": model.as_str(),
                "semantics": used,
                "ranked_total": total,
                "market_context": engine.status().market_context,
                "source": source,
                "rows": rows,
            }))
        }
        "/parity" => {
            let model = parse_model(q.get("model").copied().unwrap_or("v3"))?;
            Ok(serde_json::to_value(engine.parity(model)?).unwrap())
        }
        _ => Err(format!("not found: {path}")),
    })();
    to_http(result)
}

fn to_http(result: Result<serde_json::Value, String>) -> (&'static str, String) {
    match result {
        Ok(v) => ("200 OK", serde_json::to_string(&v).unwrap()),
        Err(e) if e.starts_with("not found") => ("404 Not Found", e),
        Err(e) => ("400 Bad Request", e),
    }
}

fn now_epoch() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0)
}

/// `GET /history?symbol=ADSK[&days=N][&changes=1][&profile=P]` (values percent-decoded).
fn history_endpoint(
    q: &HashMap<&str, &str>,
    query: impl FnOnce(&str, Option<i64>, bool, Option<&str>) -> Result<serde_json::Value, String>,
) -> Result<serde_json::Value, String> {
    let symbol = q
        .get("symbol")
        .ok_or_else(|| "history needs symbol=TICKER".to_string())
        .and_then(|s| pct_decode(s))?;
    let symbol = validate_symbol(&symbol)?;
    let days = match q.get("days") {
        Some(d) => {
            Some(validate_days(d.parse::<i64>().map_err(|_| {
                format!("bad days '{d}' (expected 1..=36500)")
            })?)?)
        }
        None => None,
    };
    let changes = matches!(q.get("changes").copied(), Some("1" | "true"));
    let profile = q.get("profile").map(|p| pct_decode(p)).transpose()?;
    query(&symbol, days, changes, profile.as_deref())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bind_refuses_non_loopback_without_flag() {
        assert!(check_bind_addr("127.0.0.1:7979", false).is_ok());
        assert!(check_bind_addr("[::1]:7979", false).is_ok());
        assert!(check_bind_addr("0.0.0.0:7979", false).is_err());
        assert!(check_bind_addr("0.0.0.0:7979", true).is_ok());
    }

    #[test]
    fn host_header_must_be_loopback() {
        assert!(host_allowed(Some("127.0.0.1:7979"), false));
        assert!(host_allowed(Some("localhost"), false));
        assert!(host_allowed(Some("[::1]:7979"), false));
        assert!(!host_allowed(Some("evil.example:7979"), false));
        assert!(!host_allowed(None, false));
        assert!(host_allowed(Some("evil.example"), true));
    }

    #[test]
    fn whole_head_shares_one_deadline() {
        use std::net::TcpListener;
        let l = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = l.local_addr().unwrap();
        let t = std::thread::spawn(move || {
            let mut c = TcpStream::connect(addr).unwrap();
            // Trickle a header byte well inside each per-read timeout, never finishing.
            for _ in 0..40 {
                if c.write_all(b"X").is_err() {
                    break;
                }
                std::thread::sleep(Duration::from_millis(200));
            }
        });
        let (s, _) = l.accept().unwrap();
        let start = std::time::Instant::now();
        assert!(read_head(&s).is_err());
        assert!(start.elapsed() < READ_TIMEOUT + Duration::from_secs(2));
        drop(s);
        t.join().unwrap();
    }

    #[test]
    fn history_endpoint_serves_points_and_changes() {
        use discount_screener_windows_lib::headless::history_json;
        let store = ScoreHistoryStore::open_in_memory().unwrap();
        let mk = |d: &str, c: i32| RankedRow {
            rank: 1,
            symbol: "ADSK".into(),
            company: None,
            sector: None,
            composite_score: c,
            setup_score: c,
            setup_label: d.into(),
            decision: d.into(),
            confidence: Some("high".into()),
            price: 250.0,
            target: Some(320.0),
            gap_pct: None,
            upside_pct: Some(28.0),
            fundamentals: Some(60),
            technical: Some(c - 20),
            forecast: Some(30),
            regime: None,
            trust_signal: None,
            projection_gap: None,
        };
        // 2026-11-01 05:20Z (01:20 EDT) and one hour later (01:20 EST): DST change.
        for (at, d, c, p) in [
            (1_793_510_400, "Act", 41, "sp500"),
            (1793514000, "Watch", 27, "sp500"),
            (1793514100, "Act", 50, "nasdaq"),
            (1793517600, "Avoid", -4, "sp500"),
        ] {
            let meta = RunMeta {
                captured_at: at,
                profile: p.into(),
                model: "v3".into(),
                semantics: "android".into(),
                market_context: false,
                binary_version: "t".into(),
                trigger: "interval".into(),
                symbols_total: 1,
                incomplete_symbols: vec![],
            };
            store.record_run(&meta, &[mk(d, c)]).unwrap();
        }
        let now = 1_793_600_000;
        let q: HashMap<&str, &str> = [("symbol", "adsk"), ("changes", "1")].into_iter().collect();
        let (status, body) = to_http(history_endpoint(&q, |s, d, c, p| {
            history_json(&store, s, d, c, now, p.unwrap_or("sp500"))
        }));
        assert_eq!(status, "200 OK");
        let v: serde_json::Value = serde_json::from_str(&body).unwrap();
        assert_eq!(v["symbol"], "ADSK");
        assert_eq!(v["profile"], "sp500");
        assert_eq!(
            v["points"].as_array().unwrap().len(),
            3,
            "nasdaq row excluded"
        );
        assert_eq!(v["points"][0]["captured_at_utc"], "2026-11-01T05:20:00Z");
        assert_eq!(v["changes"].as_array().unwrap().len(), 2);
        assert_eq!(v["changes"][1]["to"], "Avoid");
        let et: chrono_tz::Tz = "America/New_York".parse().unwrap();
        let text = render_history(&v, false, et);
        assert!(text.contains("2026-11-01 01:20 EDT"), "{text}");
        assert!(text.contains("2026-11-01 01:20 EST"), "{text}");
        assert!(text.contains("times America/New_York"), "{text}");
        let ch = render_history(&v, true, et);
        assert!(ch.contains("Watch → Avoid"), "{ch}");
        // --tz override.
        let mad: chrono_tz::Tz = "Europe/Madrid".parse().unwrap();
        let text = render_history(&v, false, mad);
        assert!(text.contains("2026-11-01 06:20 CET"), "{text}");
        let mut j = v.clone();
        add_local_times(&mut j, mad);
        assert_eq!(j["tz"], "Europe/Madrid");
        assert_eq!(j["points"][0]["captured_at_utc"], "2026-11-01T05:20:00Z");
        assert_eq!(j["points"][0]["captured_at_local"], "2026-11-01 06:20 CET");
        // profile= selects the other partition.
        let q: HashMap<&str, &str> = [("symbol", "ADSK"), ("profile", "nasdaq")]
            .into_iter()
            .collect();
        let (_, body) = to_http(history_endpoint(&q, |s, d, c, p| {
            history_json(&store, s, d, c, now, p.unwrap())
        }));
        let v: serde_json::Value = serde_json::from_str(&body).unwrap();
        assert_eq!(v["points"].as_array().unwrap().len(), 1);
        assert!(v.get("changes").is_none());

        for bad in [
            vec![("symbol", "ADSK"), ("days", "0")],
            vec![],
            vec![("symbol", "A%20B")],
            vec![("symbol", "AAAAAAAAAAAAAAAAA")],
            vec![("symbol", "%aé")],
            vec![("symbol", "é")],
            vec![("symbol", "%C3%A9")],
            vec![("symbol", "%")],
            vec![("symbol", "AB%4")],
            vec![("symbol", "%G1")],
            vec![("symbol", "%FF")],
            vec![("symbol", "ADSK"), ("profile", "%é")],
            vec![("symbol", "ADSK"), ("days", "36501")],
            vec![("symbol", "ADSK"), ("days", "99999999999999999999")],
        ] {
            let q: HashMap<&str, &str> = bad.into_iter().collect();
            assert_eq!(
                to_http(history_endpoint(&q, |_, _, _, _| unreachable!())).0,
                "400 Bad Request"
            );
        }
        let q: HashMap<&str, &str> = [("symbol", "%5EGSPC")].into_iter().collect();
        let (_, body) = to_http(history_endpoint(&q, |s, _, _, _| Ok(serde_json::json!(s))));
        assert_eq!(body.trim(), "\"^GSPC\"");
        assert_eq!(pct("^GSPC"), "%5EGSPC");
        assert_eq!(
            pct_decode("AB%41").unwrap(),
            "ABA",
            "trailing escape decoded"
        );
        assert_eq!(pct_decode("s%C3%A9+x").unwrap(), "sé x");
        assert!(pct_decode("%aé").is_err());
        assert_eq!(pct("EURUSD=X"), "EURUSD%3DX");
    }

    #[test]
    fn boolean_flags_do_not_swallow_the_next_flag() {
        let o = parse_opts(&[
            "history".into(),
            "SSRM".into(),
            "--changes".into(),
            "--addr".into(),
            "127.0.0.1:1".into(),
        ]);
        assert!(o.flags.contains_key("changes"));
        assert_eq!(o.flags.get("addr").unwrap(), "127.0.0.1:1");
        let o = parse_opts(&["history".into(), "--changes".into(), "SSRM".into()]);
        assert_eq!(
            o.positional,
            vec!["history".to_string(), "SSRM".to_string()]
        );
    }

    #[test]
    fn help_is_local() {
        // No daemon is listening; these must not try to connect.
        assert!(run(vec!["rank".into(), "--help".into()]).is_ok());
        assert!(run(vec!["--help".into()]).is_ok());
        assert!(run(vec!["help".into()]).is_ok());
    }

    #[test]
    fn table_aligns_long_symbols() {
        let mk = |sym: &str| RankedRow {
            rank: 1,
            symbol: sym.into(),
            company: None,
            sector: None,
            composite_score: 5,
            setup_score: 5,
            setup_label: "Watch".into(),
            decision: "Watch".into(),
            confidence: None,
            price: 1.0,
            target: None,
            gap_pct: None,
            upside_pct: None,
            fundamentals: None,
            technical: None,
            forecast: None,
            regime: None,
            trust_signal: None,
            projection_gap: None,
        };
        let t = render_table(&[mk("APT21794-USD"), mk("A")]);
        let pos: Vec<usize> = t
            .lines()
            .map(|l| l.chars().collect::<Vec<_>>())
            .map(|c| {
                let s: String = c.iter().collect();
                s.find("+5")
                    .map(|b| s[..b].chars().count())
                    .unwrap_or(usize::MAX)
            })
            .skip(1)
            .collect();
        assert_eq!(pos[0], pos[1], "{t}");
    }
}
