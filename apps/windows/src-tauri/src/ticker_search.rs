//! Pure ticker / company-name search ranking (port of Android TickerSearchEngine).
//! Network I/O stays in `fetcher` / `commands`.

use serde::{Deserialize, Serialize};
use serde_json::Value;

/// Lower is better.
pub mod rank {
    pub const EXACT_TICKER_CURRENT: i32 = 0;
    pub const EXACT_TICKER_OTHER: i32 = 1;
    pub const EXACT_TICKER_REMOTE: i32 = 2;
    pub const PREFIX_TICKER_CURRENT: i32 = 3;
    pub const PREFIX_TICKER_OTHER: i32 = 4;
    pub const CONTAINS_TICKER: i32 = 5;
    pub const NAME_EXACT: i32 = 6;
    pub const NAME_WORD_START: i32 = 7;
    pub const NAME_CONTAINS: i32 = 8;
    pub const REMOTE_FUZZY: i32 = 9;
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TickerSearchCandidate {
    pub symbol: String,
    pub company_name: Option<String>,
    pub profiles: Vec<String>,
    pub in_current_profile: bool,
    pub exchange: Option<String>,
    pub match_rank: i32,
    pub is_remote: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct TickerSearchResult {
    pub symbol: String,
    pub company_name: Option<String>,
    pub profiles: Vec<String>,
    pub in_current_profile: bool,
    pub exchange: Option<String>,
    pub is_remote: bool,
    pub match_rank: i32,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct YahooSearchQuote {
    pub symbol: String,
    pub company_name: String,
    pub exchange: Option<String>,
    pub quote_type: String,
}

/// Map ProfileCatalog-style ranks (0–5) onto engine ranks.
#[allow(dead_code)] // Android parity; multi-profile ranking not used on Windows yet.
pub fn remap_profile_match_rank(profile_rank: i32) -> i32 {
    match profile_rank {
        0 => rank::EXACT_TICKER_CURRENT,
        3 => rank::EXACT_TICKER_OTHER,
        1 => rank::PREFIX_TICKER_CURRENT,
        4 => rank::PREFIX_TICKER_OTHER,
        2 | 5 => rank::CONTAINS_TICKER,
        other => other,
    }
}

pub fn remote_match_rank(symbol: &str, query: &str) -> i32 {
    if symbol.eq_ignore_ascii_case(query.trim()) {
        rank::EXACT_TICKER_REMOTE
    } else {
        rank::REMOTE_FUZZY
    }
}

pub fn company_name_match_rank(query: &str, company_name: &str) -> Option<i32> {
    let normalized_query = normalize_name_query(query);
    if normalized_query.is_empty() {
        return None;
    }
    let normalized_name = normalize_name_query(company_name);
    if normalized_name.is_empty() {
        return None;
    }
    if normalized_name == normalized_query {
        return Some(rank::NAME_EXACT);
    }
    if normalized_name
        .split_whitespace()
        .any(|word| word.starts_with(&normalized_query))
    {
        return Some(rank::NAME_WORD_START);
    }
    if normalized_name.contains(&normalized_query) {
        return Some(rank::NAME_CONTAINS);
    }
    None
}

pub fn merge_and_rank(candidates: &[TickerSearchCandidate], limit: usize) -> Vec<TickerSearchResult> {
    use std::collections::HashMap;

    let mut best_by_symbol: HashMap<String, TickerSearchCandidate> = HashMap::new();
    for candidate in candidates {
        let key = candidate.symbol.to_uppercase();
        match best_by_symbol.get(&key) {
            Some(existing) if candidate_cmp(existing, candidate) != std::cmp::Ordering::Greater => {}
            _ => {
                best_by_symbol.insert(key, candidate.clone());
            }
        }
    }

    let mut ranked: Vec<TickerSearchCandidate> = best_by_symbol.into_values().collect();
    ranked.sort_by(candidate_cmp);
    ranked
        .into_iter()
        .take(limit)
        .map(|c| TickerSearchResult {
            symbol: c.symbol,
            company_name: c.company_name,
            profiles: c.profiles,
            in_current_profile: c.in_current_profile,
            exchange: c.exchange,
            is_remote: c.is_remote,
            match_rank: c.match_rank,
        })
        .collect()
}

pub fn should_trigger_remote_search(query: &str, local_results: &[TickerSearchResult]) -> bool {
    let trimmed = query.trim();
    if trimmed.len() < 2 {
        return false;
    }
    if trimmed.contains(char::is_whitespace) {
        return true;
    }
    local_results.is_empty()
}

pub fn is_ticker_token(query: &str) -> bool {
    let trimmed = query.trim();
    !trimmed.is_empty()
        && trimmed
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '-')
}

pub fn should_direct_open_ticker_on_submit(query: &str, suggestion_symbols: &[String]) -> bool {
    let trimmed = query.trim();
    if trimmed.is_empty() || !is_ticker_token(trimmed) {
        return false;
    }
    if trimmed.contains('.') || trimmed.contains('-') {
        return true;
    }

    let upper = trimmed.to_uppercase();
    let typed_as_ticker =
        trimmed == upper && trimmed.chars().all(|c| c.is_ascii_alphanumeric());

    if !suggestion_symbols.is_empty() {
        let exact_matches: Vec<_> = suggestion_symbols
            .iter()
            .filter(|s| s.eq_ignore_ascii_case(&upper))
            .collect();
        if suggestion_symbols.len() > 1 {
            return exact_matches.len() == 1 && typed_as_ticker;
        }
        return exact_matches.len() == 1;
    }

    typed_as_ticker && trimmed.len() <= 6
}

#[allow(dead_code)] // stricter core helper; submit path uses is_high_confidence_suggestion
pub fn is_high_confidence_match(query: &str, result: &TickerSearchResult) -> bool {
    let trimmed = query.trim();
    if result.symbol.eq_ignore_ascii_case(trimmed)
        && result.match_rank <= rank::EXACT_TICKER_REMOTE
    {
        return true;
    }
    match &result.company_name {
        Some(name) => company_name_match_rank(trimmed, name) == Some(rank::NAME_EXACT),
        None => false,
    }
}

/// ViewModel-style high confidence: exact symbol (ignore case) or exact company name.
pub fn is_high_confidence_suggestion(query: &str, result: &TickerSearchResult) -> bool {
    let trimmed = query.trim();
    if result.symbol.eq_ignore_ascii_case(trimmed) {
        return true;
    }
    match &result.company_name {
        Some(name) => company_name_match_rank(trimmed, name) == Some(rank::NAME_EXACT),
        None => false,
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum SearchSubmitOutcome {
    Open { symbol: String },
    PickMatch,
    Unavailable,
}

/// Mirror Android submitTickerSearch decision tree.
pub fn resolve_search_submit(
    query: &str,
    suggestions: &[TickerSearchResult],
) -> SearchSubmitOutcome {
    let symbols: Vec<String> = suggestions.iter().map(|s| s.symbol.clone()).collect();
    if should_direct_open_ticker_on_submit(query, &symbols) {
        let upper = query.trim().to_uppercase();
        return SearchSubmitOutcome::Open { symbol: upper };
    }
    let high: Vec<&TickerSearchResult> = suggestions
        .iter()
        .filter(|s| is_high_confidence_suggestion(query, s))
        .collect();
    if high.len() == 1 {
        return SearchSubmitOutcome::Open {
            symbol: high[0].symbol.clone(),
        };
    }
    if suggestions.is_empty() {
        SearchSubmitOutcome::Unavailable
    } else {
        SearchSubmitOutcome::PickMatch
    }
}

pub fn normalize_search_query_key(query: &str) -> String {
    query.trim().to_lowercase()
}

pub fn is_usable_company_name(name: Option<&str>) -> bool {
    match name {
        Some(n) => {
            let normalized = n.trim();
            !normalized.is_empty() && !normalized.eq_ignore_ascii_case("null")
        }
        None => false,
    }
}

/// Rank a symbol against a ticker query for local universe membership.
/// Returns None when the symbol does not match.
pub fn local_symbol_match_rank(query: &str, symbol: &str) -> Option<i32> {
    let q = query.trim().to_uppercase();
    if q.is_empty() {
        return None;
    }
    let s = symbol.to_uppercase();
    if s == q {
        return Some(rank::EXACT_TICKER_CURRENT);
    }
    if s.starts_with(&q) {
        return Some(rank::PREFIX_TICKER_CURRENT);
    }
    if s.contains(&q) {
        return Some(rank::CONTAINS_TICKER);
    }
    None
}

/// Build local candidates from a fixed universe + optional company names.
pub fn local_universe_candidates(
    query: &str,
    symbols: &[&str],
    company_names: &std::collections::HashMap<String, String>,
) -> Vec<TickerSearchCandidate> {
    let mut out = Vec::new();
    let q = query.trim();
    if q.is_empty() {
        return out;
    }

    for &symbol in symbols {
        if let Some(match_rank) = local_symbol_match_rank(q, symbol) {
            let key = symbol.to_uppercase();
            out.push(TickerSearchCandidate {
                symbol: symbol.to_string(),
                company_name: company_names.get(&key).cloned(),
                profiles: vec!["universe".to_string()],
                in_current_profile: true,
                exchange: None,
                match_rank,
                is_remote: false,
            });
        } else if let Some(name) = company_names.get(&symbol.to_uppercase()) {
            if let Some(match_rank) = company_name_match_rank(q, name) {
                out.push(TickerSearchCandidate {
                    symbol: symbol.to_string(),
                    company_name: Some(name.clone()),
                    profiles: vec!["universe".to_string()],
                    in_current_profile: true,
                    exchange: None,
                    match_rank,
                    is_remote: false,
                });
            }
        }
    }
    out
}

pub fn remote_candidates(query: &str, quotes: &[YahooSearchQuote]) -> Vec<TickerSearchCandidate> {
    quotes
        .iter()
        .map(|q| TickerSearchCandidate {
            symbol: q.symbol.clone(),
            company_name: Some(q.company_name.clone()).filter(|n| is_usable_company_name(Some(n))),
            profiles: vec![],
            in_current_profile: false,
            exchange: q.exchange.clone(),
            match_rank: remote_match_rank(&q.symbol, query),
            is_remote: true,
        })
        .collect()
}

const TRADABLE_SEARCH_QUOTE_TYPES: &[&str] = &["EQUITY", "ETF"];

/// Parse Yahoo `v1/finance/search` JSON body.
pub fn parse_search_quotes(root: &Value) -> Vec<YahooSearchQuote> {
    let Some(quotes) = root.get("quotes").and_then(|v| v.as_array()) else {
        return Vec::new();
    };
    quotes
        .iter()
        .filter_map(|element| {
            let quote = element.as_object()?;
            let quote_type = json_str(quote, "quoteType")?;
            if !TRADABLE_SEARCH_QUOTE_TYPES.contains(&quote_type.as_str()) {
                return None;
            }
            let symbol = json_str(quote, "symbol")?.trim().to_string();
            if symbol.is_empty() {
                return None;
            }
            let company_name = ["longname", "longName", "shortname", "shortName"]
                .iter()
                .find_map(|k| {
                    let v = json_str(quote, k)?;
                    if is_usable_company_name(Some(&v)) {
                        Some(v)
                    } else {
                        None
                    }
                })
                .unwrap_or_else(|| symbol.clone());
            let exchange = json_str(quote, "exchDisp").or_else(|| json_str(quote, "exchange"));
            Some(YahooSearchQuote {
                symbol,
                company_name,
                exchange,
                quote_type,
            })
        })
        .collect()
}

fn json_str(obj: &serde_json::Map<String, Value>, key: &str) -> Option<String> {
    obj.get(key)
        .and_then(|v| v.as_str())
        .map(|s| s.to_string())
}

fn normalize_name_query(value: &str) -> String {
    value
        .trim()
        .to_lowercase()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
}

fn candidate_cmp(a: &TickerSearchCandidate, b: &TickerSearchCandidate) -> std::cmp::Ordering {
    a.match_rank
        .cmp(&b.match_rank)
        .then_with(|| {
            let ar = if a.is_remote { 1 } else { 0 };
            let br = if b.is_remote { 1 } else { 0 };
            ar.cmp(&br)
        })
        .then_with(|| b.in_current_profile.cmp(&a.in_current_profile))
        .then_with(|| a.symbol.len().cmp(&b.symbol.len()))
        .then_with(|| a.symbol.cmp(&b.symbol))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    fn profile_candidate(
        symbol: &str,
        profiles: &[&str],
        in_current_profile: bool,
        match_rank: i32,
    ) -> TickerSearchCandidate {
        TickerSearchCandidate {
            symbol: symbol.to_string(),
            company_name: None,
            profiles: profiles.iter().map(|s| s.to_string()).collect(),
            in_current_profile,
            exchange: None,
            match_rank,
            is_remote: false,
        }
    }

    fn name_candidate(symbol: &str, company_name: &str, match_rank: i32) -> TickerSearchCandidate {
        TickerSearchCandidate {
            symbol: symbol.to_string(),
            company_name: Some(company_name.to_string()),
            profiles: vec![],
            in_current_profile: false,
            exchange: None,
            match_rank,
            is_remote: false,
        }
    }

    fn remote_candidate(symbol: &str, company_name: &str, match_rank: i32) -> TickerSearchCandidate {
        TickerSearchCandidate {
            symbol: symbol.to_string(),
            company_name: Some(company_name.to_string()),
            profiles: vec![],
            in_current_profile: false,
            exchange: Some("NASDAQ".to_string()),
            match_rank,
            is_remote: true,
        }
    }

    #[test]
    fn exact_ticker_beats_company_name_match() {
        let ranked = merge_and_rank(
            &[
                name_candidate("META", "Meta Platforms, Inc.", rank::NAME_EXACT),
                profile_candidate("META", &["sp500"], true, rank::EXACT_TICKER_CURRENT),
            ],
            8,
        );
        assert_eq!(ranked.first().unwrap().symbol, "META");
        assert_eq!(ranked.first().unwrap().match_rank, rank::EXACT_TICKER_CURRENT);
    }

    #[test]
    fn meli_exact_remote_ranks_above_fuzzy_remote_matches() {
        let ranked = merge_and_rank(
            &[
                remote_candidate("MELI.BA", "MercadoLibre, Inc.", rank::REMOTE_FUZZY),
                remote_candidate("MELI", "MercadoLibre, Inc.", rank::EXACT_TICKER_REMOTE),
            ],
            8,
        );
        assert_eq!(ranked.first().unwrap().symbol, "MELI");
        assert_eq!(ranked.first().unwrap().match_rank, rank::EXACT_TICKER_REMOTE);
    }

    #[test]
    fn dedup_prefers_local_profile_row_over_remote_duplicate() {
        let ranked = merge_and_rank(
            &[
                remote_candidate("MSFT", "Microsoft Corporation", rank::EXACT_TICKER_REMOTE),
                profile_candidate(
                    "MSFT",
                    &["dow", "sp500"],
                    true,
                    rank::EXACT_TICKER_CURRENT,
                ),
            ],
            8,
        );
        assert_eq!(ranked.len(), 1);
        assert_eq!(ranked[0].symbol, "MSFT");
        assert!(!ranked[0].is_remote);
        assert_eq!(ranked[0].profiles, vec!["dow".to_string(), "sp500".to_string()]);
    }

    #[test]
    fn should_trigger_remote_for_whitespace_name_query() {
        let local = vec![TickerSearchResult {
            symbol: "MERC.CN".to_string(),
            company_name: Some("Mercado Minerals Ltd.".to_string()),
            profiles: vec![],
            in_current_profile: false,
            exchange: None,
            is_remote: true,
            match_rank: rank::REMOTE_FUZZY,
        }];
        assert!(should_trigger_remote_search("mercado libre", &local));
    }

    #[test]
    fn should_not_trigger_remote_for_single_character_query() {
        assert!(!should_trigger_remote_search("L", &[]));
    }

    #[test]
    fn should_not_trigger_remote_when_local_has_prefix_matches() {
        let local = vec![TickerSearchResult {
            symbol: "MSFT".to_string(),
            company_name: None,
            profiles: vec!["sp500".to_string()],
            in_current_profile: true,
            exchange: None,
            is_remote: false,
            match_rank: rank::PREFIX_TICKER_CURRENT,
        }];
        assert!(!should_trigger_remote_search("MS", &local));
    }

    #[test]
    fn company_name_exact_match_is_high_confidence() {
        let result = TickerSearchResult {
            symbol: "MELI".to_string(),
            company_name: Some("MercadoLibre, Inc.".to_string()),
            profiles: vec![],
            in_current_profile: false,
            exchange: None,
            is_remote: true,
            match_rank: rank::REMOTE_FUZZY,
        };
        assert!(is_high_confidence_match("MercadoLibre, Inc.", &result));
    }

    #[test]
    fn ticker_token_detection_allows_dots_and_hyphens() {
        assert!(is_ticker_token("BRK.B"));
        assert!(is_ticker_token("BRK-B"));
        assert!(!is_ticker_token("mercado libre"));
    }

    #[test]
    fn lowercase_mercado_with_multiple_suggestions_does_not_direct_open() {
        let symbols = vec![
            "MELI".to_string(),
            "MELI.BA".to_string(),
            "MERC.CN".to_string(),
        ];
        assert!(!should_direct_open_ticker_on_submit("mercado", &symbols));
    }

    #[test]
    fn uppercase_meli_with_multiple_suggestions_direct_opens_exact_symbol() {
        let symbols = vec!["MELI".to_string(), "MELI.BA".to_string()];
        assert!(should_direct_open_ticker_on_submit("MELI", &symbols));
    }

    #[test]
    fn uppercase_meli_without_suggestions_direct_opens() {
        assert!(should_direct_open_ticker_on_submit("MELI", &[]));
    }

    #[test]
    fn resolve_submit_pick_match_for_ambiguous_name() {
        let suggestions = vec![
            TickerSearchResult {
                symbol: "MELI".to_string(),
                company_name: Some("MercadoLibre, Inc.".to_string()),
                profiles: vec![],
                in_current_profile: false,
                exchange: Some("NASDAQ".to_string()),
                is_remote: true,
                match_rank: rank::REMOTE_FUZZY,
            },
            TickerSearchResult {
                symbol: "MELI.BA".to_string(),
                company_name: Some("MercadoLibre, Inc.".to_string()),
                profiles: vec![],
                in_current_profile: false,
                exchange: Some("Buenos Aires".to_string()),
                is_remote: true,
                match_rank: rank::REMOTE_FUZZY,
            },
        ];
        assert_eq!(
            resolve_search_submit("mercado", &suggestions),
            SearchSubmitOutcome::PickMatch
        );
    }

    #[test]
    fn resolve_submit_opens_uppercase_ticker() {
        assert_eq!(
            resolve_search_submit("MELI", &[]),
            SearchSubmitOutcome::Open {
                symbol: "MELI".to_string()
            }
        );
    }

    #[test]
    fn local_universe_prefix_ms_finds_msft() {
        let names = std::collections::HashMap::new();
        let candidates = local_universe_candidates("MS", &["AAPL", "MSFT", "MSTR", "NVDA"], &names);
        let ranked = merge_and_rank(&candidates, 8);
        let symbols: Vec<_> = ranked.iter().map(|r| r.symbol.as_str()).collect();
        assert!(symbols.contains(&"MSFT"));
        assert!(symbols.contains(&"MSTR"));
        assert!(!should_trigger_remote_search("MS", &ranked));
    }

    #[test]
    fn is_usable_company_name_rejects_null_string() {
        assert!(!is_usable_company_name(None));
        assert!(!is_usable_company_name(Some("null")));
        assert!(!is_usable_company_name(Some("  NULL  ")));
        assert!(is_usable_company_name(Some("ACCO Brands Corporation")));
    }

    fn fixture_path(name: &str) -> PathBuf {
        PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("tests/fixtures/yahoo/search")
            .join(name)
    }

    fn load_fixture(name: &str) -> Value {
        let path = fixture_path(name);
        let raw = std::fs::read_to_string(&path)
            .unwrap_or_else(|e| panic!("missing fixture {}: {}", path.display(), e));
        serde_json::from_str(&raw).expect("fixture json")
    }

    #[test]
    fn parse_mercado_fixture_returns_meli() {
        let root = load_fixture("mercado.json");
        let quotes = parse_search_quotes(&root);
        assert!(!quotes.is_empty());
        assert_eq!(quotes[0].symbol, "MELI");
        assert_eq!(quotes[0].company_name, "MercadoLibre, Inc.");
        assert!(quotes.iter().all(|q| q.quote_type == "EQUITY" || q.quote_type == "ETF"));
    }

    #[test]
    fn parse_live_fixtures_have_tradable_quotes() {
        // ≥5 distinct live Yahoo samples (plus mercado from Android sampling).
        for name in [
            "ms.json",
            "meli.json",
            "mercado.json",
            "microsoft_corporation.json",
            "aapl.json",
            "spy.json",
        ] {
            let root = load_fixture(name);
            let quotes = parse_search_quotes(&root);
            assert!(
                !quotes.is_empty(),
                "fixture {name} should yield at least one EQUITY/ETF quote"
            );
            for q in &quotes {
                assert!(!q.symbol.is_empty());
                assert!(q.quote_type == "EQUITY" || q.quote_type == "ETF");
            }
        }
    }

    #[test]
    fn parse_microsoft_corporation_fixture_returns_msft() {
        let root = load_fixture("microsoft_corporation.json");
        let quotes = parse_search_quotes(&root);
        assert_eq!(quotes[0].symbol, "MSFT");
    }
}
