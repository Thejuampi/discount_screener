//! `vantage-headless` — V3 pricing-engine daemon + thin CLI client.
//!
//!   vantage-headless serve  [--profile sp500] [--addr 127.0.0.1:7979]
//!                           [--data-dir DIR] [--market-context]
//!   vantage-headless rank   [--model v3|v2|short] [--top N] [--json] [--addr ..]
//!   vantage-headless status [--addr ..]
//!   vantage-headless parity [--model v3|v2|short] [--addr ..]
//!   vantage-headless persist [--addr ..]
//!   vantage-headless rank-snapshot FILE [--model ..] [--top N] [--json]   (offline)
//!
//! Market context ("Contexto de mercado") is OFF by default.

use std::collections::HashMap;
use std::io::{BufRead, BufReader, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Duration;

use discount_screener_windows_lib::headless::{
    parse_model, rank_snapshot, to_ranked, EngineConfig, HeadlessEngine, OpportunitySnapshot,
    RankedRow,
};

const DEFAULT_ADDR: &str = "127.0.0.1:7979";

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
            } else if matches!(name, "json" | "market-context" | "help") {
                flags.insert(name.to_string(), "1".into());
            } else if i + 1 < args.len() {
                flags.insert(name.to_string(), args[i + 1].clone());
                i += 1;
            }
        } else {
            positional.push(a.clone());
        }
        i += 1;
    }
    Opts { positional, flags }
}

fn usage() -> &'static str {
    "usage: vantage-headless <serve|rank|status|parity|persist|rank-snapshot> [options]\n\
     serve:  --profile sp500|dow|merval|... --addr HOST:PORT --data-dir DIR [--market-context]\n\
     rank:   --model v3|v2|short --top N [--json]\n\
     parity: --model v3|v2|short   (desktop command path vs headless, same instant)\n\
     rank-snapshot FILE --model .. --top N [--json]   (offline replay)"
}

fn run(args: Vec<String>) -> Result<(), String> {
    let o = parse_opts(&args);
    let cmd = o.positional.first().map(String::as_str).unwrap_or("help");
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
    match cmd {
        "serve" => serve(&o, &addr),
        "rank" => {
            parse_model(&model)?;
            let mut path = format!("/ranking?model={model}");
            if let Some(n) = top {
                path.push_str(&format!("&top={n}"));
            }
            let body = http_get(&addr, &path)?;
            if json {
                println!("{body}");
            } else {
                let v: serde_json::Value =
                    serde_json::from_str(&body).map_err(|e| e.to_string())?;
                let rows: Vec<RankedRow> =
                    serde_json::from_value(v["rows"].clone()).map_err(|e| e.to_string())?;
                println!(
                    "{} · {} · source={} · context={}",
                    v["profile"].as_str().unwrap_or("?"),
                    v["model"].as_str().unwrap_or("?"),
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
            let path = match cmd {
                "parity" => format!("/parity?model={model}"),
                "persist" => "/persist".into(),
                _ => "/status".into(),
            };
            println!("{}", http_get(&addr, &path)?);
            Ok(())
        }
        "rank-snapshot" => {
            let file = o.positional.get(1).ok_or("rank-snapshot needs FILE")?;
            let snap = OpportunitySnapshot::load(&PathBuf::from(file))?;
            let rows = to_ranked(&rank_snapshot(&snap, parse_model(&model)?, None));
            let rows: Vec<RankedRow> = rows.into_iter().take(top.unwrap_or(usize::MAX)).collect();
            if json {
                println!("{}", serde_json::to_string_pretty(&rows).unwrap());
            } else {
                print!("{}", render_table(&rows));
            }
            Ok(())
        }
        _ => {
            println!("{}", usage());
            Ok(())
        }
    }
}

fn signed(v: Option<i32>) -> String {
    v.map(|n| format!("{n:+}")).unwrap_or_else(|| "—".into())
}

fn render_table(rows: &[RankedRow]) -> String {
    let mut s = format!(
        "{:>4} {:<8} {:>5} {:<12} {:>10} {:>10} {:>8} {:>16} {:<24}\n",
        "#", "SYMBOL", "SCORE", "SETUP", "PRICE", "TARGET", "GAP%", "F·T·C·R", "SECTOR"
    );
    for r in rows {
        let ftcr = format!(
            "{}·{}·{}·{}",
            signed(r.fundamentals),
            signed(r.technical),
            signed(r.forecast),
            signed(r.regime)
        );
        s.push_str(&format!(
            "{:>4} {:<8} {:>+5} {:<12} {:>10.2} {:>10} {:>8} {:>16} {:<24}\n",
            r.rank,
            r.symbol,
            r.composite_score,
            r.setup_label,
            r.price,
            r.target
                .map(|t| format!("{t:.2}"))
                .unwrap_or_else(|| "—".into()),
            r.gap_pct
                .map(|g| format!("{g:+.1}"))
                .unwrap_or_else(|| "—".into()),
            ftcr,
            r.sector.clone().unwrap_or_default()
        ));
    }
    s
}

// ── tiny HTTP (std only, loopback) ────────────────────────────────────────────

fn http_get(addr: &str, path: &str) -> Result<String, String> {
    let mut s = TcpStream::connect(addr)
        .map_err(|e| format!("connect {addr}: {e} (is `vantage-headless serve` running?)"))?;
    s.set_read_timeout(Some(Duration::from_secs(120))).ok();
    write!(s, "GET {path} HTTP/1.0\r\nHost: {addr}\r\n\r\n").map_err(|e| e.to_string())?;
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
    let engine = HeadlessEngine::start(EngineConfig {
        data_dir: data_dir.clone(),
        profile,
        market_context,
    })?;
    let listener = TcpListener::bind(addr).map_err(|e| format!("bind {addr}: {e}"))?;
    eprintln!(
        "vantage-headless: profile={} context={} data={} listening on http://{addr}",
        engine.profile(),
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
        });
    }
    for stream in listener.incoming().flatten() {
        let engine = Arc::clone(&engine);
        std::thread::spawn(move || handle(stream, &engine));
    }
    Ok(())
}

fn default_data_dir() -> PathBuf {
    std::env::var_os("XDG_STATE_HOME")
        .map(PathBuf::from)
        .or_else(|| std::env::var_os("HOME").map(|h| PathBuf::from(h).join(".local/state")))
        .unwrap_or_else(|| PathBuf::from("."))
        .join("vantage-headless")
}

fn handle(mut stream: TcpStream, engine: &HeadlessEngine) {
    let mut line = String::new();
    if BufReader::new(&stream).read_line(&mut line).is_err() {
        return;
    }
    let target = line.split_whitespace().nth(1).unwrap_or("/").to_string();
    let (path, query) = target.split_once('?').unwrap_or((target.as_str(), ""));
    let q: HashMap<&str, &str> = query
        .split('&')
        .filter_map(|kv| kv.split_once('='))
        .collect();
    let result: Result<serde_json::Value, String> = (|| match path {
        "/status" => Ok(serde_json::to_value(engine.status()).unwrap()),
        "/persist" => Ok(serde_json::json!({ "persisted_rows": engine.persist()? })),
        "/ranking" => {
            let model = parse_model(q.get("model").copied().unwrap_or("v3"))?;
            let top: usize = q
                .get("top")
                .and_then(|t| t.parse().ok())
                .unwrap_or(usize::MAX);
            let (rows, source) = engine.ranking(model);
            let rows: Vec<RankedRow> = to_ranked(&rows).into_iter().take(top).collect();
            Ok(serde_json::json!({
                "profile": engine.profile(),
                "model": model.as_str(),
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
    let (status, body) = match result {
        Ok(v) => ("200 OK", serde_json::to_string(&v).unwrap()),
        Err(e) => ("400 Bad Request", e),
    };
    let _ = write!(
        stream,
        "HTTP/1.0 {status}\r\nContent-Type: application/json\r\nContent-Length: {}\r\n\r\n{body}",
        body.len()
    );
}
