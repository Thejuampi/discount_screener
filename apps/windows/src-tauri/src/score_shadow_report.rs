//! V3.1 shadow evaluation (read-only): for each shadow config, forward returns at N recorded
//! trading days of the official-Act rows it would DEMOTE (shadow Watch) vs the ones it KEEPS.
//! Design: `docs/architecture/decision-rule-v3-1-shadow.md`. Opens `history.sqlite` read-only.
//!
//! Entry = last recorded run of a market day (America/New_York) per (profile, symbol), price =
//! `score_history.price_cents` of that run. Trading-day calendar = the distinct market days that
//! have a recorded run for the profile. Return at h = price on calendar[i + h] / entry − 1; rows
//! whose symbol has no price on that day are not counted (not imputed).

use std::collections::{BTreeMap, BTreeSet, HashMap};
use std::path::Path;

use chrono::NaiveDate;
use rusqlite::{params, Connection, OpenFlags};
use serde::Serialize;

pub const DEFAULT_HORIZONS: [usize; 3] = [5, 20, 60];
/// Below this many observations in either group a horizon is reported as insufficient data.
pub const DEFAULT_MIN_N: usize = 20;

#[derive(Clone, Debug, Serialize, PartialEq)]
pub struct GroupStats {
    pub n: usize,
    pub mean_pct: Option<f64>,
    pub median_pct: Option<f64>,
    pub hit_rate_pct: Option<f64>,
}

#[derive(Clone, Debug, Serialize, PartialEq)]
pub struct HorizonRow {
    pub horizon_days: usize,
    pub demoted: GroupStats,
    pub kept: GroupStats,
    /// kept mean − demoted mean, in percentage points (positive = the demotion helped).
    pub kept_minus_demoted_pp: Option<f64>,
    pub status: String,
}

#[derive(Clone, Debug, Serialize, PartialEq)]
pub struct ConfigReport {
    pub profile: String,
    pub config_id: String,
    pub act_entries: usize,
    pub demoted_entries: usize,
    pub kept_entries: usize,
    pub horizons: Vec<HorizonRow>,
}

#[derive(Clone, Debug, Serialize, PartialEq)]
pub struct ShadowReport {
    pub trading_days: BTreeMap<String, usize>,
    pub min_n: usize,
    pub configs: Vec<ConfigReport>,
}

fn stats(xs: &[f64]) -> GroupStats {
    if xs.is_empty() {
        return GroupStats {
            n: 0,
            mean_pct: None,
            median_pct: None,
            hit_rate_pct: None,
        };
    }
    let mut v = xs.to_vec();
    v.sort_by(|a, b| a.partial_cmp(b).unwrap());
    let n = v.len();
    let med = if n % 2 == 1 {
        v[n / 2]
    } else {
        (v[n / 2 - 1] + v[n / 2]) / 2.0
    };
    GroupStats {
        n,
        mean_pct: Some(100.0 * v.iter().sum::<f64>() / n as f64),
        median_pct: Some(100.0 * med),
        hit_rate_pct: Some(100.0 * v.iter().filter(|x| **x > 0.0).count() as f64 / n as f64),
    }
}

fn day(epoch: i64) -> NaiveDate {
    chrono::DateTime::from_timestamp(epoch, 0)
        .unwrap_or_default()
        .with_timezone(&crate::score_history::MARKET_TZ)
        .date_naive()
}

pub fn shadow_report(
    path: &Path,
    profile: Option<&str>,
    horizons: &[usize],
    min_n: usize,
) -> Result<ShadowReport, String> {
    let c = Connection::open_with_flags(path, OpenFlags::SQLITE_OPEN_READ_ONLY)
        .map_err(|e| format!("open {}: {e}", path.display()))?;
    let has_shadow: bool = c
        .query_row(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='score_shadow'",
            [],
            |r| r.get::<_, i64>(0),
        )
        .map_err(|e| e.to_string())?
        > 0;
    if !has_shadow {
        return Err(format!(
            "{}: no score_shadow table (schema < 2)",
            path.display()
        ));
    }
    // (profile, symbol, day) -> (captured_at, price_cents) of the day's last run.
    let mut px: HashMap<(String, String, NaiveDate), (i64, i64)> = HashMap::new();
    let mut cal: BTreeMap<String, BTreeSet<NaiveDate>> = BTreeMap::new();
    {
        let mut st = c
            .prepare(
                "SELECT r.profile, h.symbol, h.captured_at, h.price_cents FROM score_history h \
                 JOIN score_runs r ON r.run_id = h.run_id \
                 WHERE r.model = 'v3' AND (?1 IS NULL OR r.profile = ?1)",
            )
            .map_err(|e| e.to_string())?;
        let rows = st
            .query_map(params![profile], |r| {
                Ok((
                    r.get::<_, String>(0)?,
                    r.get::<_, String>(1)?,
                    r.get::<_, i64>(2)?,
                    r.get::<_, i64>(3)?,
                ))
            })
            .map_err(|e| e.to_string())?;
        for row in rows {
            let (p, s, at, cents) = row.map_err(|e| e.to_string())?;
            let d = day(at);
            cal.entry(p.clone()).or_default().insert(d);
            let e = px.entry((p, s, d)).or_insert((at, cents));
            if at >= e.0 {
                *e = (at, cents);
            }
        }
    }
    // (profile, config, symbol, day) -> (captured_at, official, shadow) of the day's last run.
    let mut ent: HashMap<(String, String, String, NaiveDate), (i64, String, Option<String>)> =
        HashMap::new();
    {
        let mut st = c
            .prepare(
                "SELECT r.profile, s.config_id, h.symbol, h.captured_at, h.decision, s.shadow_decision \
                 FROM score_shadow s JOIN score_history h ON h.run_id = s.run_id AND h.symbol = s.ticker \
                 JOIN score_runs r ON r.run_id = s.run_id \
                 WHERE r.model = 'v3' AND (?1 IS NULL OR r.profile = ?1)",
            )
            .map_err(|e| e.to_string())?;
        let rows = st
            .query_map(params![profile], |r| {
                Ok((
                    r.get::<_, String>(0)?,
                    r.get::<_, String>(1)?,
                    r.get::<_, String>(2)?,
                    r.get::<_, i64>(3)?,
                    r.get::<_, String>(4)?,
                    r.get::<_, Option<String>>(5)?,
                ))
            })
            .map_err(|e| e.to_string())?;
        for row in rows {
            let (p, cfg, s, at, off, sh) = row.map_err(|e| e.to_string())?;
            let k = (p, cfg, s, day(at));
            if ent.get(&k).map_or(true, |e| at >= e.0) {
                ent.insert(k, (at, off, sh));
            }
        }
    }
    let idx: BTreeMap<String, Vec<NaiveDate>> = cal
        .iter()
        .map(|(p, d)| (p.clone(), d.iter().copied().collect()))
        .collect();
    // (profile, config) -> per horizon (demoted returns, kept returns); plus entry counts.
    let mut acc: BTreeMap<(String, String), (usize, usize, Vec<(Vec<f64>, Vec<f64>)>)> =
        BTreeMap::new();
    for ((p, cfg, s, d), (_, off, sh)) in &ent {
        if off != "Act" {
            continue;
        }
        let demoted = sh.as_deref() == Some("Watch");
        let a = acc
            .entry((p.clone(), cfg.clone()))
            .or_insert_with(|| (0, 0, vec![(vec![], vec![]); horizons.len()]));
        if demoted {
            a.0 += 1
        } else {
            a.1 += 1
        }
        let days = &idx[p];
        let i = days.binary_search(d).unwrap();
        let entry = px[&(p.clone(), s.clone(), *d)].1 as f64;
        for (hi, h) in horizons.iter().enumerate() {
            let Some(exit_day) = days.get(i + h) else {
                continue;
            };
            let Some((_, exit)) = px.get(&(p.clone(), s.clone(), *exit_day)) else {
                continue;
            };
            if entry > 0.0 {
                let r = *exit as f64 / entry - 1.0;
                if demoted {
                    a.2[hi].0.push(r)
                } else {
                    a.2[hi].1.push(r)
                }
            }
        }
    }
    let configs = acc
        .into_iter()
        .map(|((p, cfg), (nd, nk, per))| ConfigReport {
            profile: p,
            config_id: cfg,
            act_entries: nd + nk,
            demoted_entries: nd,
            kept_entries: nk,
            horizons: horizons
                .iter()
                .zip(per)
                .map(|(h, (d, k))| {
                    let (ds, ks) = (stats(&d), stats(&k));
                    let ok = ds.n >= min_n && ks.n >= min_n;
                    HorizonRow {
                        horizon_days: *h,
                        kept_minus_demoted_pp: match (ks.mean_pct, ds.mean_pct) {
                            (Some(a), Some(b)) => Some(a - b),
                            _ => None,
                        },
                        status: if ok {
                            "ok".into()
                        } else {
                            format!("insufficient data (need n>={min_n} in both groups)")
                        },
                        demoted: ds,
                        kept: ks,
                    }
                })
                .collect(),
        })
        .collect();
    Ok(ShadowReport {
        trading_days: idx.iter().map(|(p, d)| (p.clone(), d.len())).collect(),
        min_n,
        configs,
    })
}

pub fn render(r: &ShadowReport) -> String {
    let f = |x: Option<f64>| x.map_or("-".to_string(), |v| format!("{v:+.2}"));
    let mut s = format!(
        "trading days recorded: {:?}  (min n per group: {})\n",
        r.trading_days, r.min_n
    );
    if r.configs.is_empty() {
        s.push_str("insufficient data: no official-Act rows with shadow values recorded yet\n");
    }
    for c in &r.configs {
        s.push_str(&format!(
            "\n{} {}  Act entries={} demoted={} kept={}\n  {:>4} {:>6} {:>9} {:>6} {:>9} {:>9}  status\n",
            c.profile, c.config_id, c.act_entries, c.demoted_entries, c.kept_entries,
            "h", "n_dem", "dem_mean%", "n_kept", "kept_mean%", "kept-dem"
        ));
        for h in &c.horizons {
            s.push_str(&format!(
                "  {:>4} {:>6} {:>9} {:>6} {:>9} {:>9}  {}\n",
                h.horizon_days,
                h.demoted.n,
                f(h.demoted.mean_pct),
                h.kept.n,
                f(h.kept.mean_pct),
                f(h.kept_minus_demoted_pp),
                h.status
            ));
        }
    }
    s
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::score_history::{RunMeta, ScoreHistoryStore};
    use chrono::Datelike;

    fn row(sym: &str, price: f64, shadow: &str) -> crate::headless::RankedRow {
        let mut g = crate::opportunity_v31::shadow_grid(Some("Act"), 40, Some(0), Some(0.0));
        for v in &mut g {
            v.shadow_decision = Some(shadow.to_string());
        }
        crate::headless::RankedRow {
            rank: 1,
            symbol: sym.into(),
            company: None,
            sector: None,
            composite_score: 40,
            setup_score: 40,
            setup_label: "Act".into(),
            decision: "Act".into(),
            confidence: Some("high".into()),
            price,
            target: None,
            gap_pct: None,
            upside_pct: None,
            fundamentals: None,
            technical: None,
            forecast: None,
            regime: None,
            trust_signal: None,
            projection_gap: None,
            shadow: Some(g),
        }
    }

    #[test]
    fn demoted_vs_kept_forward_returns_and_insufficient_data() {
        let dir = std::env::temp_dir().join(format!("shrep-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let p = dir.join("history.sqlite");
        let s = ScoreHistoryStore::open(&p, None).unwrap();
        // 7 weekdays from Mon 2026-10-05 21:00 UTC; DEM falls 1%/day, KEEP rises 1%/day.
        let base = 1_791_234_000; // 2026-10-05T21:00:00Z
        let mut k = 0;
        for d in 0..9 {
            let at = base + d * 86_400;
            let wd = chrono::DateTime::from_timestamp(at, 0).unwrap().weekday();
            if matches!(wd, chrono::Weekday::Sat | chrono::Weekday::Sun) {
                continue;
            }
            let meta = RunMeta {
                captured_at: at,
                profile: "sp500".into(),
                model: "v3".into(),
                semantics: "android".into(),
                market_context: false,
                binary_version: "t".into(),
                trigger: "close".into(),
                symbols_total: 2,
                incomplete_symbols: vec![],
            };
            let f = k as f64;
            s.record_run(
                &meta,
                &[
                    row("DEM", 100.0 * (1.0 - 0.01 * f), "Watch"),
                    row("KEEP", 100.0 * (1.0 + 0.01 * f), "Act"),
                ],
            )
            .unwrap();
            k += 1;
        }
        drop(s);
        let r = shadow_report(&p, None, &[5, 20], 2).unwrap();
        assert_eq!(r.trading_days["sp500"], 7);
        assert_eq!(r.configs.len(), 6);
        let c = &r.configs[0];
        assert_eq!(
            (c.act_entries, c.demoted_entries, c.kept_entries),
            (14, 7, 7)
        );
        let h5 = &c.horizons[0];
        assert_eq!((h5.demoted.n, h5.kept.n, h5.status.as_str()), (2, 2, "ok"));
        assert!(h5.demoted.mean_pct.unwrap() < 0.0 && h5.kept.mean_pct.unwrap() > 0.0);
        assert!(h5.kept_minus_demoted_pp.unwrap() > 0.0);
        let h20 = &c.horizons[1];
        assert_eq!(h20.demoted.n, 0);
        assert!(h20.status.starts_with("insufficient data"));
        let _ = std::fs::remove_dir_all(&dir);
    }
}
