//! Pure launch-time universe profile selection (CLI + env).
//!
//! Precedence: app CLI flags  >  `DS_UNIVERSE_PROFILE`  >  none.
//!
//! Accepted CLI forms (all equivalent for forcing a profile):
//! - `--universe NAME` / `--universe=NAME`  (**preferred** with Tauri/Cargo — does not collide)
//! - `--ds-universe NAME` / `--ds-universe=NAME`
//! - `--profile NAME` / `--profile=NAME`  (works on the **app binary**; do **not** pass via
//!   `tauri dev -- --profile …` — Cargo steals `--profile` as a compile profile)
//!
//! Explicit invalid values fail closed (never silent default to sp500).

use crate::profiles::resolve_profile_name;

pub const DS_UNIVERSE_PROFILE_ENV: &str = "DS_UNIVERSE_PROFILE";

/// CLI flag names that force a universe profile (long form without `=`).
const CLI_FLAG_NAMES: &[&str] = &["--universe", "--ds-universe", "--profile"];

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ForcedProfile {
    /// Canonical profile id (`qa`, `sp500`, …).
    pub name: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum LaunchProfileError {
    Invalid { source: &'static str, value: String },
    DuplicateCli,
}

impl std::fmt::Display for LaunchProfileError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            LaunchProfileError::Invalid { source, value } => {
                write!(
                    f,
                    "invalid universe profile from {source}: {value:?} \
                     (known profiles only; refuse silent fallback to sp500)"
                )
            }
            LaunchProfileError::DuplicateCli => {
                write!(
                    f,
                    "duplicate universe CLI flags (--universe / --ds-universe / --profile)"
                )
            }
        }
    }
}

impl std::error::Error for LaunchProfileError {}

/// Parse forced profile from process args (excluding argv0) and optional env value.
///
/// - CLI wins over env when both present and valid.
/// - Empty/whitespace env is treated as unset.
/// - Alias `test` resolves to `qa` via [`resolve_profile_name`].
pub fn parse_forced_profile(
    args: impl IntoIterator<Item = impl AsRef<str>>,
    env_value: Option<&str>,
) -> Result<Option<ForcedProfile>, LaunchProfileError> {
    let cli = parse_cli_profile(args)?;
    if let Some(name) = cli {
        return Ok(Some(ForcedProfile { name }));
    }
    parse_env_profile(env_value).map(|opt| opt.map(|name| ForcedProfile { name }))
}

fn parse_cli_profile(
    args: impl IntoIterator<Item = impl AsRef<str>>,
) -> Result<Option<String>, LaunchProfileError> {
    let mut found: Option<String> = None;
    let mut iter = args.into_iter().map(|a| a.as_ref().to_string()).peekable();

    while let Some(arg) = iter.next() {
        // --flag=value forms
        for name in CLI_FLAG_NAMES {
            let prefix = format!("{name}=");
            if let Some(value) = arg.strip_prefix(&prefix) {
                if found.is_some() {
                    return Err(LaunchProfileError::DuplicateCli);
                }
                found = Some(canonicalize_forced("cli", value)?);
                break;
            }
        }
        if CLI_FLAG_NAMES
            .iter()
            .any(|n| arg.starts_with(&format!("{n}=")))
        {
            continue;
        }

        // --flag value forms
        if CLI_FLAG_NAMES.contains(&arg.as_str()) {
            if found.is_some() {
                return Err(LaunchProfileError::DuplicateCli);
            }
            let Some(value) = iter.next() else {
                return Err(LaunchProfileError::Invalid {
                    source: "cli",
                    value: String::new(),
                });
            };
            found = Some(canonicalize_forced("cli", &value)?);
        }
    }
    Ok(found)
}

fn parse_env_profile(env_value: Option<&str>) -> Result<Option<String>, LaunchProfileError> {
    let Some(raw) = env_value else {
        return Ok(None);
    };
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        return Ok(None);
    }
    Ok(Some(canonicalize_forced("env", trimmed)?))
}

fn canonicalize_forced(source: &'static str, raw: &str) -> Result<String, LaunchProfileError> {
    resolve_profile_name(raw)
        .map(|s| s.to_string())
        .ok_or_else(|| LaunchProfileError::Invalid {
            source,
            value: raw.to_string(),
        })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn profile_space_form() {
        let got = parse_forced_profile(["--profile", "qa"], None)
            .unwrap()
            .unwrap();
        assert_eq!(got.name, "qa");
    }

    #[test]
    fn profile_equals_form() {
        let got = parse_forced_profile(["--profile=qa"], None)
            .unwrap()
            .unwrap();
        assert_eq!(got.name, "qa");
    }

    #[test]
    fn universe_preferred_forms() {
        let a = parse_forced_profile(["--universe", "qa"], None)
            .unwrap()
            .unwrap();
        assert_eq!(a.name, "qa");
        let b = parse_forced_profile(["--universe=dow"], None)
            .unwrap()
            .unwrap();
        assert_eq!(b.name, "dow");
        let c = parse_forced_profile(["--ds-universe", "test"], None)
            .unwrap()
            .unwrap();
        assert_eq!(c.name, "qa");
    }

    #[test]
    fn cli_wins_over_env() {
        let got = parse_forced_profile(["--universe", "dow"], Some("qa"))
            .unwrap()
            .unwrap();
        assert_eq!(got.name, "dow");
    }

    #[test]
    fn env_only() {
        let no_args: [&str; 0] = [];
        let got = parse_forced_profile(no_args, Some("qa")).unwrap().unwrap();
        assert_eq!(got.name, "qa");
    }

    #[test]
    fn empty_env_is_unset() {
        let no_args: [&str; 0] = [];
        assert!(parse_forced_profile(no_args, Some("  ")).unwrap().is_none());
        assert!(parse_forced_profile(no_args, None).unwrap().is_none());
    }

    #[test]
    fn alias_test_canonicalizes_to_qa() {
        let got = parse_forced_profile(["--profile", "test"], None)
            .unwrap()
            .unwrap();
        assert_eq!(got.name, "qa");
        let no_args: [&str; 0] = [];
        let env = parse_forced_profile(no_args, Some("test"))
            .unwrap()
            .unwrap();
        assert_eq!(env.name, "qa");
    }

    #[test]
    fn invalid_cli_fails_closed() {
        let err = parse_forced_profile(["--universe", "qaa"], None).unwrap_err();
        assert!(matches!(
            err,
            LaunchProfileError::Invalid { source: "cli", .. }
        ));
    }

    #[test]
    fn invalid_env_fails_closed() {
        let no_args: [&str; 0] = [];
        let err = parse_forced_profile(no_args, Some("not-a-profile")).unwrap_err();
        assert!(matches!(
            err,
            LaunchProfileError::Invalid { source: "env", .. }
        ));
    }

    #[test]
    fn duplicate_cli_errors() {
        let err = parse_forced_profile(["--universe", "qa", "--profile=dow"], None).unwrap_err();
        assert_eq!(err, LaunchProfileError::DuplicateCli);
    }

    #[test]
    fn missing_cli_value_errors() {
        let err = parse_forced_profile(["--universe"], None).unwrap_err();
        assert!(matches!(err, LaunchProfileError::Invalid { .. }));
    }
}
