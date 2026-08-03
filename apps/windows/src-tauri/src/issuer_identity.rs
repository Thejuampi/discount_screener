//! Stable issuer/security identity (Foundation 0B pure model).
//!
//! No network, no EDGAR live fetch. Fixtures are generic (no `if AMZN` branches).

use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256};

pub const IDENTITY_FINGERPRINT_SCHEME: &str = "sha256_identity_v2";
pub const DOMAIN_IDENTITY: &str = "ds.valuation.issuer_identity.v2";

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct IssuerIdentity {
    pub issuer_id: String,
    pub cik: String,
    pub legal_name: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SecurityIdentity {
    pub security_id: String,
    pub issuer_id: String,
    pub currency: String,
    pub share_class_label: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct TickerAlias {
    pub security_id: String,
    pub ticker: String,
    pub effective_from: String,
    pub identity_vintage: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ShareBasisVintage {
    pub basis_id: String,
    pub security_id: String,
    pub vintage_fingerprint: String,
    pub description: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct IdentityBundle {
    pub issuer: IssuerIdentity,
    pub security: SecurityIdentity,
    pub ticker_alias: TickerAlias,
    pub share_basis: ShareBasisVintage,
}

impl IssuerIdentity {
    pub fn validate(&self) -> Result<(), &'static str> {
        if self.issuer_id.trim().is_empty() {
            return Err("empty_issuer_id");
        }
        if self.cik.trim().is_empty() {
            return Err("empty_cik");
        }
        Ok(())
    }
}

impl SecurityIdentity {
    pub fn validate(&self) -> Result<(), &'static str> {
        if self.security_id.trim().is_empty() {
            return Err("empty_security_id");
        }
        if self.issuer_id.trim().is_empty() {
            return Err("empty_issuer_id");
        }
        if self.currency.trim().is_empty() {
            return Err("empty_currency");
        }
        Ok(())
    }
}

/// Domain-tagged identity vintage fingerprint for run keys.
pub fn identity_vintage_fingerprint(bundle: &IdentityBundle) -> String {
    let mut out = Vec::new();
    write_str(&mut out, DOMAIN_IDENTITY);
    write_str(&mut out, IDENTITY_FINGERPRINT_SCHEME);
    write_str(&mut out, &bundle.issuer.issuer_id);
    write_str(&mut out, &bundle.issuer.cik);
    write_str(&mut out, &bundle.security.security_id);
    write_str(&mut out, &bundle.security.currency);
    write_str(&mut out, &bundle.ticker_alias.ticker);
    write_str(&mut out, &bundle.ticker_alias.effective_from);
    write_str(&mut out, &bundle.ticker_alias.identity_vintage);
    write_str(&mut out, &bundle.share_basis.basis_id);
    write_str(&mut out, &bundle.share_basis.vintage_fingerprint);
    format!("sha256:{}", hex_lower(&Sha256::digest(&out)))
}

fn write_str(out: &mut Vec<u8>, s: &str) {
    let bytes = s.as_bytes();
    out.push(0x01);
    out.extend_from_slice(&(bytes.len() as u32).to_be_bytes());
    out.extend_from_slice(bytes);
}

fn hex_lower(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// Fixture: AMZN-shaped issuer (generic fields; no special-case code path).
pub fn fixture_amzn_shaped() -> IdentityBundle {
    IdentityBundle {
        issuer: IssuerIdentity {
            issuer_id: "issuer:0001018724".into(),
            cik: "0001018724".into(),
            legal_name: Some("Amazon.com, Inc.".into()),
        },
        security: SecurityIdentity {
            security_id: "sec:amzn-us".into(),
            issuer_id: "issuer:0001018724".into(),
            currency: "USD".into(),
            share_class_label: Some("Common".into()),
        },
        ticker_alias: TickerAlias {
            security_id: "sec:amzn-us".into(),
            ticker: "AMZN".into(),
            effective_from: "1997-05-15".into(),
            identity_vintage: "identity:v1:amzn-us".into(),
        },
        share_basis: ShareBasisVintage {
            basis_id: "share_basis:amzn-us:post-split-2022".into(),
            security_id: "sec:amzn-us".into(),
            vintage_fingerprint: "split:2022-06-06:20-for-1".into(),
            description: "Post 20-for-1 split basis".into(),
        },
    }
}

/// Synthetic second issuer for dual-identity and generic arithmetic proofs.
pub fn fixture_synthetic() -> IdentityBundle {
    IdentityBundle {
        issuer: IssuerIdentity {
            issuer_id: "issuer:0000999999".into(),
            cik: "0000999999".into(),
            legal_name: Some("Synthetic Holdings Corp.".into()),
        },
        security: SecurityIdentity {
            security_id: "sec:syn-us".into(),
            issuer_id: "issuer:0000999999".into(),
            currency: "USD".into(),
            share_class_label: Some("Common".into()),
        },
        ticker_alias: TickerAlias {
            security_id: "sec:syn-us".into(),
            ticker: "SYNX".into(),
            effective_from: "2020-01-01".into(),
            identity_vintage: "identity:v1:syn-us".into(),
        },
        share_basis: ShareBasisVintage {
            basis_id: "share_basis:syn-us:ipo".into(),
            security_id: "sec:syn-us".into(),
            vintage_fingerprint: "ipo:2020-01-01".into(),
            description: "IPO share basis".into(),
        },
    }
}

/// Resolve security by effective ticker within a fixture set.
pub fn resolve_by_ticker<'a>(
    bundles: &'a [IdentityBundle],
    ticker: &str,
) -> Option<&'a IdentityBundle> {
    let t = ticker.trim().to_ascii_uppercase();
    bundles
        .iter()
        .find(|b| b.ticker_alias.ticker.eq_ignore_ascii_case(&t))
}

/// Resolve by stable issuer_id.
pub fn resolve_by_issuer_id<'a>(
    bundles: &'a [IdentityBundle],
    issuer_id: &str,
) -> Option<&'a IdentityBundle> {
    bundles.iter().find(|b| b.issuer.issuer_id == issuer_id)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn blank_issuer_id_refuses() {
        let mut i = fixture_amzn_shaped().issuer;
        i.issuer_id = "  ".into();
        assert_eq!(i.validate(), Err("empty_issuer_id"));
    }

    #[test]
    fn blank_cik_refuses() {
        let mut i = fixture_amzn_shaped().issuer;
        i.cik = "".into();
        assert_eq!(i.validate(), Err("empty_cik"));
    }

    #[test]
    fn resolve_amzn_shaped_by_ticker() {
        let set = [fixture_amzn_shaped(), fixture_synthetic()];
        let found = resolve_by_ticker(&set, "amzn").expect("found");
        assert_eq!(found.issuer.cik, "0001018724");
    }

    #[test]
    fn resolve_synthetic_by_issuer_id() {
        let set = [fixture_amzn_shaped(), fixture_synthetic()];
        let found = resolve_by_issuer_id(&set, "issuer:0000999999").expect("found");
        assert_eq!(found.ticker_alias.ticker, "SYNX");
    }

    #[test]
    fn two_issuers_have_distinct_identity_fingerprints() {
        let a = identity_vintage_fingerprint(&fixture_amzn_shaped());
        let b = identity_vintage_fingerprint(&fixture_synthetic());
        assert_ne!(a, b);
        assert!(a.starts_with("sha256:"));
        assert_eq!(a.len(), 7 + 64);
    }

    #[test]
    fn identity_fingerprint_stable() {
        let a = identity_vintage_fingerprint(&fixture_amzn_shaped());
        let b = identity_vintage_fingerprint(&fixture_amzn_shaped());
        assert_eq!(a, b);
    }

    #[test]
    fn ticker_effective_from_is_part_of_identity_vintage() {
        let a = fixture_amzn_shaped();
        let mut b = a.clone();
        b.ticker_alias.effective_from = "2026-01-01".into();
        assert_ne!(
            identity_vintage_fingerprint(&a),
            identity_vintage_fingerprint(&b)
        );
    }
}
