//! Symbol-universe profiles (index lists), matching desktop / Flutter / Android IDs.
//!
//! Static index baskets compose without DB. The `qa` profile is a **bounded top-ranking
//! sample** (≤20) resolved from latest SQLite snapshots + priority fill — not a full
//! product-surface sample.

use std::collections::HashSet;

use crate::db::{Db, LatestSnapshotRow};
use crate::fetcher::{CRYPTO_SYMBOLS, DEFAULT_LIVE_SYMBOLS, ETF_SYMBOLS};

/// High-priority equities fetched first so the UI shows useful data quickly.
pub const PRIORITY_SYMBOLS: &[&str] = &[
    "AAPL", "MSFT", "NVDA", "AMZN", "GOOGL", "META", "TSLA", "BRK.B", "JPM", "V", "UNH", "LLY",
    "XOM", "MA", "AVGO", "PG", "HD", "COST", "JNJ", "ABBV", "MRK", "WMT", "BAC", "NFLX", "CRM",
    "ORCL", "AMD", "ACN", "TMO", "CSCO",
];

/// Hard cap for persistent QA feed workers / membership.
pub const QA_MAX_SYMBOLS: usize = 20;
/// Minimum upside/discount (gap) for ranked QA inclusion (25%).
pub const QA_MIN_GAP_BPS: i32 = 2500;
/// Reporting-only staleness threshold (7 days). Does not exclude membership in v1.
pub const QA_STALE_REPORT_SECS: i64 = 7 * 86_400;

const DOW_SYMBOLS: &str = include_str!("profile_data/dow.txt");
const RUSSELL_SYMBOLS: &str = include_str!("profile_data/russell.txt");
const MERVAL_SYMBOLS: &str = include_str!("profile_data/merval.txt");
const NIKKEI_SYMBOLS: &str = include_str!("profile_data/nikkei.txt");
const EUROPE_SYMBOLS: &str = include_str!("profile_data/europe.txt");
const ASIA_SYMBOLS: &str = include_str!("profile_data/asia.txt");

pub struct ProfileDefinition {
    pub name: &'static str,
    pub description: &'static str,
}

const PROFILE_DEFINITIONS: [ProfileDefinition; 8] = [
    ProfileDefinition {
        name: "sp500",
        description: "S&P 500 equity universe (Windows also appends ETFs + crypto)",
    },
    ProfileDefinition {
        name: "dow",
        description: "Dow Jones Industrial Average 30-stock basket",
    },
    ProfileDefinition {
        name: "russell",
        description: "Full iShares Russell 2000 holdings universe",
    },
    ProfileDefinition {
        name: "merval",
        description: "S&P Merval local constituent universe",
    },
    ProfileDefinition {
        name: "nikkei",
        description: "Nikkei 225 constituent universe",
    },
    ProfileDefinition {
        name: "europe",
        description: "Full iShares Core MSCI Europe holdings universe",
    },
    ProfileDefinition {
        name: "asia",
        description: "Full iShares AC Asia ex Japan holdings universe",
    },
    ProfileDefinition {
        name: "qa",
        description: "Agent/manual QA: top-ranking SP500 sample ≤20 (score + ≥25% gap)",
    },
];

pub fn profile_definitions() -> &'static [ProfileDefinition] {
    &PROFILE_DEFINITIONS
}

/// Canonical profile name after alias normalization, if known.
pub fn resolve_profile_name(raw_profile: &str) -> Option<&'static str> {
    let normalized = normalize_profile_name(raw_profile);
    match normalized.as_str() {
        "sp500" | "spx" | "sandp500" | "snp500" => Some("sp500"),
        "dow" | "dowjones" | "djia" => Some("dow"),
        "russell" | "russell2000" | "rut" => Some("russell"),
        "merval" | "argentina" => Some("merval"),
        "nikkei" | "nikkei225" | "japan" | "jp" => Some("nikkei"),
        "europe" | "eu" => Some("europe"),
        "asia" | "asiaexjapan" | "asiapacific" | "apac" => Some("asia"),
        "qa" | "test" | "manualqa" => Some("qa"),
        _ => None,
    }
}

pub fn profile_symbols(raw_profile: &str) -> Option<Vec<String>> {
    let canonical = resolve_profile_name(raw_profile)?;
    let symbols = match canonical {
        // Keep Windows DEFAULT_LIVE_SYMBOLS as the S&P equity source of truth.
        "sp500" => DEFAULT_LIVE_SYMBOLS
            .iter()
            .map(|s| (*s).to_string())
            .collect(),
        "dow" => load_symbols(DOW_SYMBOLS),
        "russell" => load_symbols(RUSSELL_SYMBOLS),
        "merval" => load_symbols(MERVAL_SYMBOLS),
        "nikkei" => load_symbols(NIKKEI_SYMBOLS),
        "europe" => load_symbols(EUROPE_SYMBOLS),
        "asia" => load_symbols(ASIA_SYMBOLS),
        // Dynamic — use resolve_profile_membership.
        "qa" => return None,
        _ => return None,
    };
    Some(symbols)
}

fn load_symbols(raw_symbols: &str) -> Vec<String> {
    raw_symbols
        .lines()
        .map(str::trim)
        .filter(|symbol| !symbol.is_empty() && !symbol.starts_with('#'))
        .map(|s| s.to_ascii_uppercase())
        .collect()
}

fn normalize_profile_name(raw_profile: &str) -> String {
    raw_profile
        .chars()
        .filter(|character| character.is_ascii_alphanumeric())
        .map(|character| character.to_ascii_lowercase())
        .collect()
}

/// S&P 500 equity membership set (Windows DEFAULT_LIVE_SYMBOLS).
pub fn sp500_equity_set() -> HashSet<String> {
    DEFAULT_LIVE_SYMBOLS
        .iter()
        .map(|s| (*s).to_string())
        .collect()
}

/// How membership was produced (for logs / status).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MembershipSource {
    Static,
    QaRanked,
    QaPriorityFill,
    QaDbErrorFallback,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ResolvedProfile {
    pub name: String,
    pub symbols: Vec<String>,
    pub source: MembershipSource,
    pub ranked_count: usize,
    pub fill_count: usize,
    pub stale_snapshots: bool,
    pub min_captured_at: Option<i64>,
    pub max_captured_at: Option<i64>,
    pub db_error: Option<String>,
}

impl ResolvedProfile {
    /// Canonical set for idempotency (order-independent).
    pub fn symbol_set(&self) -> HashSet<String> {
        self.symbols.iter().cloned().collect()
    }
}

/// Single resolution path for startup and `apply_universe_profile`.
pub fn resolve_profile_membership(raw_profile: &str, db: &Db) -> Result<ResolvedProfile, String> {
    let canonical = resolve_profile_name(raw_profile)
        .ok_or_else(|| format!("unknown universe profile: {raw_profile}"))?
        .to_string();

    if canonical == "qa" {
        return resolve_qa_membership(db);
    }

    let (name, symbols) = compose_universe(&canonical)?;
    Ok(ResolvedProfile {
        name,
        symbols,
        source: MembershipSource::Static,
        ranked_count: 0,
        fill_count: 0,
        stale_snapshots: false,
        min_captured_at: None,
        max_captured_at: None,
        db_error: None,
    })
}

fn resolve_qa_membership(db: &Db) -> Result<ResolvedProfile, String> {
    let sp500 = sp500_equity_set();
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs() as i64)
        .unwrap_or(0);

    match db.latest_snapshot_per_symbol() {
        Ok(latest) => {
            let ranked = rank_qa_candidates(
                &latest,
                &sp500,
                QA_MIN_GAP_BPS,
                QA_MAX_SYMBOLS,
                now,
                QA_STALE_REPORT_SECS,
            );
            let (symbols, ranked_count, fill_count) =
                fill_qa_to_limit(ranked.symbols, PRIORITY_SYMBOLS, &sp500, QA_MAX_SYMBOLS);
            if symbols.len() > QA_MAX_SYMBOLS {
                return Err(format!(
                    "qa membership exceeded hard cap: {} > {QA_MAX_SYMBOLS}",
                    symbols.len()
                ));
            }
            let source = if fill_count > 0 {
                MembershipSource::QaPriorityFill
            } else {
                MembershipSource::QaRanked
            };
            Ok(ResolvedProfile {
                name: "qa".into(),
                symbols,
                source,
                ranked_count,
                fill_count,
                stale_snapshots: ranked.stale_snapshots,
                min_captured_at: ranked.min_captured_at,
                max_captured_at: ranked.max_captured_at,
                db_error: None,
            })
        }
        Err(e) => {
            // DB error ≠ thin DB: priority-only ≤20 with error flag; never full SP500.
            let (symbols, ranked_count, fill_count) =
                fill_qa_to_limit(Vec::new(), PRIORITY_SYMBOLS, &sp500, QA_MAX_SYMBOLS);
            Ok(ResolvedProfile {
                name: "qa".into(),
                symbols,
                source: MembershipSource::QaDbErrorFallback,
                ranked_count,
                fill_count,
                stale_snapshots: false,
                min_captured_at: None,
                max_captured_at: None,
                db_error: Some(e),
            })
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct QaRankResult {
    pub symbols: Vec<String>,
    pub stale_snapshots: bool,
    pub min_captured_at: Option<i64>,
    pub max_captured_at: Option<i64>,
}

/// Filter **after** latest-per-symbol: SP500 ∩ gap ≥ min ∩ non-null score/gap,
/// order by score DESC, gap DESC, symbol ASC, limit N.
///
/// Staleness is reporting-only (v1): never excludes a name.
pub fn rank_qa_candidates(
    latest: &[LatestSnapshotRow],
    sp500: &HashSet<String>,
    min_gap_bps: i32,
    limit: usize,
    now_secs: i64,
    stale_report_secs: i64,
) -> QaRankResult {
    let mut rows: Vec<&LatestSnapshotRow> = latest
        .iter()
        .filter(|r| sp500.contains(&r.symbol))
        .filter(|r| r.gap_bps >= min_gap_bps)
        .collect();

    rows.sort_by(|a, b| {
        b.composite_score
            .cmp(&a.composite_score)
            .then_with(|| b.gap_bps.cmp(&a.gap_bps))
            .then_with(|| a.symbol.cmp(&b.symbol))
    });
    rows.truncate(limit);

    let mut min_at = None;
    let mut max_at = None;
    let mut stale = false;
    for r in &rows {
        min_at = Some(min_at.map_or(r.captured_at, |m: i64| m.min(r.captured_at)));
        max_at = Some(max_at.map_or(r.captured_at, |m: i64| m.max(r.captured_at)));
        if now_secs.saturating_sub(r.captured_at) > stale_report_secs {
            stale = true;
        }
    }

    QaRankResult {
        symbols: rows.iter().map(|r| r.symbol.clone()).collect(),
        stale_snapshots: stale,
        min_captured_at: min_at,
        max_captured_at: max_at,
    }
}

/// Append SP500 priority symbols until `limit`, no duplicates.
/// Returns (symbols, ranked_count, fill_count).
pub fn fill_qa_to_limit(
    ranked: Vec<String>,
    priority: &[&str],
    sp500: &HashSet<String>,
    limit: usize,
) -> (Vec<String>, usize, usize) {
    let ranked_count = ranked.len().min(limit);
    let mut out = Vec::with_capacity(limit);
    let mut seen = HashSet::new();
    for s in ranked.into_iter().take(limit) {
        if seen.insert(s.clone()) {
            out.push(s);
        }
    }
    let mut fill_count = 0;
    for p in priority {
        if out.len() >= limit {
            break;
        }
        let sym = (*p).to_string();
        if !sp500.contains(&sym) {
            continue;
        }
        if seen.insert(sym.clone()) {
            out.push(sym);
            fill_count += 1;
        }
    }
    (out, ranked_count, fill_count)
}

/// Build the live feed universe for a **static** profile.
///
/// - `sp500`: priority equities → ETFs → crypto → remaining S&P equities (Windows default).
/// - other static profiles: profile symbols only.
/// - `qa` is **not** supported here — use [`resolve_profile_membership`].
pub fn compose_universe(raw_profile: &str) -> Result<(String, Vec<String>), String> {
    let canonical = resolve_profile_name(raw_profile)
        .ok_or_else(|| format!("unknown universe profile: {raw_profile}"))?
        .to_string();
    if canonical == "qa" {
        return Err("qa profile requires resolve_profile_membership (DB-backed sample)".into());
    }
    let base = profile_symbols(&canonical)
        .ok_or_else(|| format!("failed to load universe profile: {canonical}"))?;

    if canonical != "sp500" {
        return Ok((canonical, base));
    }

    let mut symbols = Vec::with_capacity(base.len() + ETF_SYMBOLS.len() + CRYPTO_SYMBOLS.len());
    let mut seen = std::collections::HashSet::new();

    for s in PRIORITY_SYMBOLS
        .iter()
        .chain(ETF_SYMBOLS.iter())
        .chain(CRYPTO_SYMBOLS.iter())
    {
        if seen.insert((*s).to_string()) {
            symbols.push((*s).to_string());
        }
    }
    for s in base {
        if seen.insert(s.clone()) {
            symbols.push(s);
        }
    }
    Ok((canonical, symbols))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::fetcher::{CRYPTO_SYMBOLS, ETF_SYMBOLS};

    fn row(symbol: &str, gap: i32, score: i32, at: i64) -> LatestSnapshotRow {
        LatestSnapshotRow {
            symbol: symbol.into(),
            captured_at: at,
            market_price_cents: 100,
            intrinsic_value_cents: 120,
            gap_bps: gap,
            decision: "Act".into(),
            composite_score: score,
            fundamentals_score: Some(1),
            technical_score: Some(1),
            forecast_score: Some(1),
            confidence: "High".into(),
        }
    }

    #[test]
    fn resolves_named_profiles_and_aliases() {
        let sp500 = profile_symbols("S&P 500").expect("sp500 alias should resolve");
        let dow = profile_symbols("dow-jones").expect("dow alias should resolve");
        let russell = profile_symbols("russell2000").expect("russell alias should resolve");
        let merval = profile_symbols("Argentina").expect("merval alias should resolve");
        let nikkei = profile_symbols("Japan").expect("nikkei alias should resolve");
        let europe = profile_symbols("EU").expect("europe alias should resolve");
        let asia = profile_symbols("Asia ex Japan").expect("asia alias should resolve");

        assert!(sp500.len() >= 500 && sp500.iter().any(|s| s == "AAPL"));
        assert_eq!(dow.len(), 30);
        assert!(dow.iter().any(|s| s == "NVDA" || s == "AAPL"));
        assert!(russell.len() > 1000 && russell.iter().any(|s| s == "BE"));
        assert!(merval.len() >= 20 && merval.iter().any(|s| s == "YPFD.BA"));
        assert_eq!(nikkei.len(), 225);
        assert!(nikkei.iter().any(|s| s == "7203.T"));
        assert!(europe.len() > 500 && europe.iter().any(|s| s == "ASML.AS"));
        assert!(asia.len() > 500 && asia.iter().any(|s| s == "2330.TW"));
    }

    #[test]
    fn exposes_expected_canonical_profile_names() {
        assert_eq!(
            profile_definitions()
                .iter()
                .map(|p| p.name)
                .collect::<Vec<_>>(),
            vec!["sp500", "dow", "russell", "merval", "nikkei", "europe", "asia", "qa"]
        );
    }

    #[test]
    fn unknown_profile_returns_none() {
        assert!(profile_symbols("not-a-real-index").is_none());
        assert!(resolve_profile_name("xyzzy").is_none());
    }

    #[test]
    fn resolve_returns_canonical_ids() {
        assert_eq!(resolve_profile_name("DJIA"), Some("dow"));
        assert_eq!(resolve_profile_name("spx"), Some("sp500"));
        assert_eq!(resolve_profile_name("test"), Some("qa"));
        assert_eq!(resolve_profile_name("qa"), Some("qa"));
    }

    #[test]
    fn compose_sp500_includes_etf_and_crypto() {
        let (name, symbols) = compose_universe("sp500").expect("sp500");
        assert_eq!(name, "sp500");
        assert!(symbols.iter().any(|s| s == "AAPL"));
        assert!(symbols.iter().any(|s| ETF_SYMBOLS.contains(&s.as_str())));
        assert!(symbols.iter().any(|s| CRYPTO_SYMBOLS.contains(&s.as_str())));
        assert!(symbols.len() > 500);
    }

    #[test]
    fn compose_dow_is_profile_only() {
        let (name, symbols) = compose_universe("dow").expect("dow");
        assert_eq!(name, "dow");
        assert_eq!(symbols.len(), 30);
        assert!(!symbols.iter().any(|s| CRYPTO_SYMBOLS.contains(&s.as_str())));
    }

    #[test]
    fn compose_qa_is_rejected() {
        assert!(compose_universe("qa").is_err());
    }

    #[test]
    fn rank_uses_latest_semantics_via_prefiltered_rows() {
        // Caller supplies only latest rows; older qualifying CI is not present.
        let sp500 = sp500_equity_set();
        let latest = vec![
            row("CI", 100, 10, 200), // latest: does not qualify
            row("AAPL", 3000, 80, 200),
            row("MSFT", 4000, 90, 200),
        ];
        let ranked = rank_qa_candidates(&latest, &sp500, 2500, 20, 1000, QA_STALE_REPORT_SECS);
        assert!(!ranked.symbols.iter().any(|s| s == "CI"));
        assert_eq!(ranked.symbols, vec!["MSFT".to_string(), "AAPL".to_string()]);
    }

    #[test]
    fn rank_stable_tie_break_and_null_free() {
        let sp500 = sp500_equity_set();
        let latest = vec![
            row("ZZZ", 5000, 50, 100), // not SP500 if ZZZ not in set
            row("AAPL", 3000, 70, 100),
            row("MSFT", 4000, 70, 100), // same score, higher gap → first
            row("JPM", 2500, 70, 100), // same score, lower gap → after MSFT/AAPL by gap then symbol
        ];
        // Ensure ZZZ not in set
        assert!(!sp500.contains("ZZZ"));
        let ranked = rank_qa_candidates(&latest, &sp500, 2500, 20, 1000, QA_STALE_REPORT_SECS);
        assert_eq!(
            ranked.symbols,
            vec!["MSFT".to_string(), "AAPL".to_string(), "JPM".to_string()]
        );
    }

    #[test]
    fn rank_stale_flag_does_not_exclude() {
        let sp500 = sp500_equity_set();
        let now = 1_000_000_i64;
        let old = now - QA_STALE_REPORT_SECS - 1;
        let latest = vec![row("AAPL", 5000, 99, old)];
        let ranked = rank_qa_candidates(&latest, &sp500, 2500, 20, now, QA_STALE_REPORT_SECS);
        assert_eq!(ranked.symbols, vec!["AAPL".to_string()]);
        assert!(ranked.stale_snapshots);
    }

    #[test]
    fn fill_priority_no_dups_and_cap() {
        let sp500 = sp500_equity_set();
        let ranked = vec!["AAPL".to_string(), "MSFT".to_string()];
        let (out, ranked_count, fill_count) = fill_qa_to_limit(ranked, PRIORITY_SYMBOLS, &sp500, 5);
        assert_eq!(ranked_count, 2);
        assert_eq!(out.len(), 5);
        assert_eq!(fill_count, 3);
        assert_eq!(out[0], "AAPL");
        assert_eq!(out[1], "MSFT");
        // No duplicate AAPL from priority
        assert_eq!(out.iter().filter(|s| *s == "AAPL").count(), 1);
    }

    #[test]
    fn resolve_qa_from_db_respects_cap() {
        let db = Db::open_in_memory().unwrap();
        let mut inserts = Vec::new();
        for (i, sym) in DEFAULT_LIVE_SYMBOLS.iter().take(30).enumerate() {
            inserts.push(crate::db::SnapshotInsert {
                symbol: sym,
                captured_at: 1_000 + i as i64,
                market_price_cents: 100,
                intrinsic_value_cents: 200,
                gap_bps: 3000 + i as i32,
                decision: "Act",
                composite_score: 100 - i as i32,
                fundamentals_score: Some(1),
                technical_score: Some(1),
                forecast_score: Some(1),
                confidence: "High",
            });
        }
        db.insert_snapshots(&inserts).unwrap();
        let resolved = resolve_profile_membership("qa", &db).unwrap();
        assert_eq!(resolved.name, "qa");
        assert!(resolved.symbols.len() <= QA_MAX_SYMBOLS);
        assert_eq!(resolved.symbols.len(), QA_MAX_SYMBOLS);
        assert_eq!(resolved.ranked_count, QA_MAX_SYMBOLS);
        assert_eq!(resolved.fill_count, 0);
        // Highest score first among the 30 inserts: score = 100 - i → first symbol wins
        assert_eq!(resolved.symbols[0], DEFAULT_LIVE_SYMBOLS[0]);
    }

    #[test]
    fn resolve_qa_thin_db_priority_fill() {
        let db = Db::open_in_memory().unwrap();
        let resolved = resolve_profile_membership("qa", &db).unwrap();
        assert_eq!(resolved.name, "qa");
        assert_eq!(resolved.symbols.len(), QA_MAX_SYMBOLS);
        assert_eq!(resolved.ranked_count, 0);
        assert_eq!(resolved.fill_count, QA_MAX_SYMBOLS);
        assert_eq!(resolved.source, MembershipSource::QaPriorityFill);
        assert!(!resolved
            .symbols
            .iter()
            .any(|s| ETF_SYMBOLS.contains(&s.as_str())));
        assert!(!resolved
            .symbols
            .iter()
            .any(|s| CRYPTO_SYMBOLS.contains(&s.as_str())));
    }

    #[test]
    fn symbol_set_ignores_order_for_identity() {
        let a = ResolvedProfile {
            name: "qa".into(),
            symbols: vec!["AAPL".into(), "MSFT".into()],
            source: MembershipSource::QaRanked,
            ranked_count: 2,
            fill_count: 0,
            stale_snapshots: false,
            min_captured_at: None,
            max_captured_at: None,
            db_error: None,
        };
        let b = ResolvedProfile {
            name: "qa".into(),
            symbols: vec!["MSFT".into(), "AAPL".into()],
            ..a.clone()
        };
        assert_eq!(a.symbol_set(), b.symbol_set());
    }
}
