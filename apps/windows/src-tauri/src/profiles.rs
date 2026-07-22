//! Symbol-universe profiles (index lists), matching desktop / Flutter / Android IDs.

use crate::fetcher::{CRYPTO_SYMBOLS, DEFAULT_LIVE_SYMBOLS, ETF_SYMBOLS};

/// High-priority equities fetched first so the UI shows useful data quickly.
pub const PRIORITY_SYMBOLS: &[&str] = &[
    "AAPL", "MSFT", "NVDA", "AMZN", "GOOGL", "META", "TSLA", "BRK.B", "JPM", "V", "UNH", "LLY",
    "XOM", "MA", "AVGO", "PG", "HD", "COST", "JNJ", "ABBV", "MRK", "WMT", "BAC", "NFLX", "CRM",
    "ORCL", "AMD", "ACN", "TMO", "CSCO",
];

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

const PROFILE_DEFINITIONS: [ProfileDefinition; 7] = [
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

/// Build the live feed universe for a profile.
///
/// - `sp500`: priority equities → ETFs → crypto → remaining S&P equities (Windows default).
/// - other profiles: profile symbols only.
pub fn compose_universe(raw_profile: &str) -> Result<(String, Vec<String>), String> {
    let canonical = resolve_profile_name(raw_profile)
        .ok_or_else(|| format!("unknown universe profile: {raw_profile}"))?
        .to_string();
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
    use super::{compose_universe, profile_definitions, profile_symbols, resolve_profile_name};
    use crate::fetcher::{CRYPTO_SYMBOLS, ETF_SYMBOLS};

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
            vec!["sp500", "dow", "russell", "merval", "nikkei", "europe", "asia"]
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
}
