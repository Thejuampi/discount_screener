use crate::market_data::default_live_symbols;

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
        description: "Built-in 503-symbol S&P 500 universe",
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

pub fn profile_symbols(raw_profile: &str) -> Option<Vec<String>> {
    let normalized = normalize_profile_name(raw_profile);

    let symbols = match normalized.as_str() {
        "sp500" | "spx" | "sandp500" | "snp500" => default_live_symbols(),
        "dow" | "dowjones" | "djia" => load_symbols(DOW_SYMBOLS),
        "russell" | "russell2000" | "rut" => load_symbols(RUSSELL_SYMBOLS),
        "merval" | "argentina" => load_symbols(MERVAL_SYMBOLS),
        "nikkei" | "nikkei225" | "japan" | "jp" => load_symbols(NIKKEI_SYMBOLS),
        "europe" | "eu" => load_symbols(EUROPE_SYMBOLS),
        "asia" | "asiaexjapan" | "asiapacific" | "apac" => load_symbols(ASIA_SYMBOLS),
        _ => return None,
    };

    Some(symbols)
}

fn load_symbols(raw_symbols: &str) -> Vec<String> {
    raw_symbols
        .lines()
        .map(str::trim)
        .filter(|symbol| !symbol.is_empty())
        .map(str::to_string)
        .collect()
}

fn normalize_profile_name(raw_profile: &str) -> String {
    raw_profile
        .chars()
        .filter(|character| character.is_ascii_alphanumeric())
        .map(|character| character.to_ascii_lowercase())
        .collect()
}

#[cfg(test)]
mod tests {
    use super::profile_definitions;
    use super::profile_symbols;

    #[test]
    fn resolves_named_profiles_and_aliases() {
        let sp500 = profile_symbols("S&P 500").expect("sp500 alias should resolve");
        let dow = profile_symbols("dow-jones").expect("dow alias should resolve");
        let russell = profile_symbols("russell2000").expect("russell alias should resolve");
        let merval = profile_symbols("Argentina").expect("merval alias should resolve");
        let nikkei = profile_symbols("Japan").expect("nikkei alias should resolve");
        let europe = profile_symbols("EU").expect("europe alias should resolve");
        let asia = profile_symbols("Asia ex Japan").expect("asia alias should resolve");

        assert!(
            sp500.len() == 503
                && sp500.iter().any(|symbol| symbol == "AAPL")
                && dow.len() == 30
                && dow.iter().any(|symbol| symbol == "NVDA")
                && russell.len() == 1937
                && russell.iter().any(|symbol| symbol == "BE")
                && merval.len() == 23
                && merval.iter().any(|symbol| symbol == "YPFD.BA")
                && nikkei.len() == 225
                && nikkei.iter().any(|symbol| symbol == "7203.T")
                && europe.len() == 1011
                && europe.iter().any(|symbol| symbol == "ASML.AS")
                && asia.len() == 900
                && asia.iter().any(|symbol| symbol == "2330.TW")
        );
    }

    #[test]
    fn exposes_expected_canonical_profile_names() {
        assert_eq!(
            profile_definitions()
                .iter()
                .map(|profile| profile.name)
                .collect::<Vec<_>>(),
            vec![
                "sp500", "dow", "russell", "merval", "nikkei", "europe", "asia"
            ]
        );
    }
}
