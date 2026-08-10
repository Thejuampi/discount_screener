//! Fourth V3 scoring bucket: regime fit (−100..+100).

use serde::Serialize;

use crate::engine::{smooth_ramp, CandidateRow, ChartSummary};

use super::scoring_policy::{RegimeScoringPolicy, ScoreSide};

/// Typed cause factor — no user-facing copy lives here.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
pub enum RegimeCauseFactor {
    Quality,
    LowBeta,
    Value,
    OversoldQual,
    Extension,
    Trend,
    Defensive,
    Growth,
    Liquidity,
    GeneralFit,
    Neutral,
}

impl RegimeCauseFactor {
    /// Legacy signal tag (without leading sign).
    pub fn legacy_tag(self) -> &'static str {
        match self {
            Self::Quality => "Quality",
            Self::LowBeta => "LowBeta",
            Self::Value => "Value",
            Self::OversoldQual => "OversoldQual",
            Self::Extension => "Extension",
            Self::Trend => "Trend",
            Self::Defensive => "Defensive",
            Self::Growth => "Growth",
            Self::Liquidity => "Liquidity",
            Self::GeneralFit => "RegimeFit",
            Self::Neutral => "RegimeNeutral",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
pub enum RegimeCauseEffect {
    Support,
    Risk,
    Neutral,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize)]
pub struct RegimeCause {
    pub factor: RegimeCauseFactor,
    pub effect: RegimeCauseEffect,
    /// Internal magnitude for ranking only (not shown to users).
    pub contribution_bps: i32,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[allow(dead_code)] // Unknown reserved for presentation/API completeness
pub enum MarketContextUnavailableReason {
    MarketReadingUnavailable,
    InsufficientAssetData,
    Unknown,
}

#[derive(Clone, Debug, PartialEq)]
pub struct RegimeFitResult {
    pub score: Option<i32>,
    pub causes: Vec<RegimeCause>,
    /// Legacy string signals (`+Quality`) for older clients.
    pub signals: Vec<String>,
    pub unavailable_reason: Option<MarketContextUnavailableReason>,
}

impl RegimeFitResult {
    fn empty_insufficient() -> Self {
        Self {
            score: None,
            causes: vec![],
            signals: vec![],
            unavailable_reason: Some(MarketContextUnavailableReason::InsufficientAssetData),
        }
    }
}

fn cause_from_signed(factor: RegimeCauseFactor, signed: f64, weight: f64) -> RegimeCause {
    let contribution = signed * weight;
    let contribution_bps = (contribution * 10_000.0).round() as i32;
    let effect = if signed > 0.0 {
        RegimeCauseEffect::Support
    } else if signed < 0.0 {
        RegimeCauseEffect::Risk
    } else {
        RegimeCauseEffect::Neutral
    };
    RegimeCause {
        factor,
        effect,
        contribution_bps,
    }
}

fn legacy_signal(cause: &RegimeCause) -> String {
    match cause.effect {
        RegimeCauseEffect::Support => format!("+{}", cause.factor.legacy_tag()),
        RegimeCauseEffect::Risk => format!("−{}", cause.factor.legacy_tag()),
        RegimeCauseEffect::Neutral => cause.factor.legacy_tag().to_string(),
    }
}

/// Score how well this name fits the active regime policy.
pub fn score_regime_fit(
    row: &CandidateRow,
    daily: Option<&ChartSummary>,
    policy: &RegimeScoringPolicy,
) -> RegimeFitResult {
    let features = SymbolFeatures::extract(row, daily);
    if features.coverage < 2 {
        return RegimeFitResult::empty_insufficient();
    }

    // (signed −1..1, weight, factor)
    let mut parts: Vec<(f64, f64, RegimeCauseFactor)> = Vec::new();

    // Quality
    if let Some(q) = features.quality {
        // map 0..1 → −1..+1 around 0.45 neutral
        let s = ((q - 0.45) / 0.45).clamp(-1.0, 1.0);
        parts.push((s, policy.w_quality, RegimeCauseFactor::Quality));
    }

    // Low beta (high beta → negative for long)
    if let Some(lb) = features.low_beta {
        let s = ((lb - 0.5) / 0.5).clamp(-1.0, 1.0);
        parts.push((s, policy.w_low_beta, RegimeCauseFactor::LowBeta));
    }

    // Value
    if let Some(v) = features.value {
        let s = ((v - 0.45) / 0.45).clamp(-1.0, 1.0);
        parts.push((s, policy.w_value, RegimeCauseFactor::Value));
    }

    // Oversold × quality gate
    if let (Some(os), Some(q)) = (features.oversold, features.quality) {
        let gate = if q >= 0.45 {
            1.0
        } else if q >= 0.30 {
            0.4
        } else {
            0.0
        }; // junk oversold gets nothing
        let s = (os * 2.0 - 1.0).clamp(-1.0, 1.0) * gate;
        if gate > 0.0 {
            parts.push((
                s,
                policy.w_oversold_quality,
                RegimeCauseFactor::OversoldQual,
            ));
        }
    }

    // Extension / anti-chase
    // Long: high extension → negative contribution × anti_ext weight
    // Short: high extension → positive (we flip sign for short)
    if let Some(ext) = features.extension {
        let mut s = -((ext - 0.45) / 0.45).clamp(-1.0, 1.0); // high ext → negative for long
        if policy.side == ScoreSide::Short {
            s = -s; // short wants extended
        }
        parts.push((s, policy.w_anti_extension, RegimeCauseFactor::Extension));
    }

    // Trend align
    if let Some(t) = features.trend_align {
        let mut s = ((t - 0.5) / 0.5).clamp(-1.0, 1.0);
        if policy.side == ScoreSide::Short {
            s = -s;
        }
        parts.push((s, policy.w_trend, RegimeCauseFactor::Trend));
    }

    // Defensive / growth sector
    if features.defensive_sector {
        let s = if policy.side == ScoreSide::Short {
            -0.6
        } else {
            0.8
        };
        parts.push((s, policy.w_defensive, RegimeCauseFactor::Defensive));
    }
    if features.growth_sector {
        let s = if policy.side == ScoreSide::Short {
            0.5
        } else {
            0.7
        };
        parts.push((s, policy.w_growth, RegimeCauseFactor::Growth));
    }

    // Liquidity
    if let Some(liq) = features.liquidity {
        let s = ((liq - 0.4) / 0.5).clamp(-1.0, 1.0);
        parts.push((s, policy.w_liquidity, RegimeCauseFactor::Liquidity));
    }

    let mut num = 0.0;
    let mut den = 0.0;
    let mut candidates: Vec<RegimeCause> = Vec::new();
    for (s, w, factor) in &parts {
        if *w <= 0.0 {
            continue;
        }
        num += s * w;
        den += w;
        if s.abs() >= 0.35 && *w >= 0.25 {
            candidates.push(cause_from_signed(*factor, *s, *w));
        }
    }
    if den <= 0.0 {
        return RegimeFitResult::empty_insufficient();
    }

    let raw = (num / den) * policy.strength;
    // Map −1..+1 → −100..+100 with mild compression
    let score = (raw.tanh() * 100.0).round() as i32;
    let score = score.clamp(-100, 100);

    // Rank by absolute contribution; keep the three strongest causes.
    candidates.sort_by(|a, b| {
        b.contribution_bps
            .abs()
            .cmp(&a.contribution_bps.abs())
            .then_with(|| a.factor.legacy_tag().cmp(b.factor.legacy_tag()))
    });
    candidates.truncate(3);

    let causes = if candidates.is_empty() {
        let (factor, effect, bps) = if score >= 15 {
            (
                RegimeCauseFactor::GeneralFit,
                RegimeCauseEffect::Support,
                score * 100,
            )
        } else if score <= -15 {
            (
                RegimeCauseFactor::GeneralFit,
                RegimeCauseEffect::Risk,
                score * 100,
            )
        } else {
            (RegimeCauseFactor::Neutral, RegimeCauseEffect::Neutral, 0)
        };
        vec![RegimeCause {
            factor,
            effect,
            contribution_bps: bps,
        }]
    } else {
        candidates
    };

    let signals: Vec<String> = causes.iter().map(legacy_signal).collect();

    RegimeFitResult {
        score: Some(score),
        causes,
        signals,
        unavailable_reason: None,
    }
}

struct SymbolFeatures {
    quality: Option<f64>,
    low_beta: Option<f64>,
    value: Option<f64>,
    extension: Option<f64>,
    oversold: Option<f64>,
    trend_align: Option<f64>,
    defensive_sector: bool,
    growth_sector: bool,
    liquidity: Option<f64>,
    coverage: usize,
}

impl SymbolFeatures {
    fn extract(row: &CandidateRow, daily: Option<&ChartSummary>) -> Self {
        let mut coverage = 0usize;

        let quality = quality_score(row);
        if quality.is_some() {
            coverage += 1;
        }
        let low_beta = low_beta_score(row.beta_millis);
        if low_beta.is_some() {
            coverage += 1;
        }
        let value = value_score(row);
        if value.is_some() {
            coverage += 1;
        }
        let extension = extension_score(daily);
        if extension.is_some() {
            coverage += 1;
        }
        let oversold = oversold_score(daily);
        if oversold.is_some() {
            coverage += 1;
        }
        let trend_align = trend_score(daily);
        if trend_align.is_some() {
            coverage += 1;
        }
        let (defensive_sector, growth_sector) = sector_flags(row.sector_name.as_deref());
        if defensive_sector || growth_sector {
            coverage += 1;
        }
        let liquidity = liquidity_score(row, daily);
        if liquidity.is_some() {
            coverage += 1;
        }

        Self {
            quality,
            low_beta,
            value,
            extension,
            oversold,
            trend_align,
            defensive_sector,
            growth_sector,
            liquidity,
            coverage,
        }
    }
}

fn quality_score(row: &CandidateRow) -> Option<f64> {
    let mut acc = 0.0;
    let mut n = 0.0;

    match (row.free_cash_flow_dollars, row.market_cap_dollars) {
        (Some(fcf), Some(mc)) if mc > 0 => {
            let y = fcf as f64 / mc as f64;
            acc += ((smooth_ramp(y, -0.02, 0.08) + 1.0) / 2.0).clamp(0.0, 1.0);
            n += 1.0;
        }
        (Some(fcf), _) => {
            acc += if fcf > 0 { 0.7 } else { 0.2 };
            n += 0.7;
        }
        _ => {
            if let Some(ocf) = row.operating_cash_flow_dollars {
                acc += if ocf > 0 { 0.55 } else { 0.25 };
                n += 0.5;
            }
        }
    }

    if let Some(de) = row.debt_to_equity_hundredths {
        // low D/E better; 30 → good, 200 → bad
        let s = ((-smooth_ramp(de as f64, 30.0, 200.0) + 1.0) / 2.0).clamp(0.0, 1.0);
        acc += s;
        n += 1.0;
    } else if let (Some(c), Some(d)) = (row.total_cash_dollars, row.total_debt_dollars) {
        acc += if c >= d { 0.75 } else { 0.35 };
        n += 0.7;
    }

    if let Some(roe) = row.return_on_equity_bps {
        acc += ((smooth_ramp(roe as f64, 0.0, 2000.0) + 1.0) / 2.0).clamp(0.0, 1.0);
        n += 0.8;
    }

    if let (Some(fcf), Some(ocf)) = (row.free_cash_flow_dollars, row.operating_cash_flow_dollars) {
        if ocf > 0 {
            let conv = (fcf as f64 / ocf as f64).clamp(0.0, 1.5) / 1.5;
            acc += conv.clamp(0.0, 1.0);
            n += 0.6;
        }
    }

    if n <= 0.0 {
        None
    } else {
        Some((acc / n).clamp(0.0, 1.0))
    }
}

fn low_beta_score(beta_millis: Option<i32>) -> Option<f64> {
    let b = beta_millis?;
    // beta 0.7 → ~1.0 low-beta score; 1.6 → ~0
    let t = smooth_ramp(b as f64, 700.0, 1600.0); // −1..+1 high beta high
    Some(((-t + 1.0) / 2.0).clamp(0.0, 1.0))
}

fn value_score(row: &CandidateRow) -> Option<f64> {
    let mut vals = Vec::new();
    if let Some(pe) = row.forward_pe_hundredths.filter(|&p| p > 0) {
        // cheap PE → high score (invert ramp)
        vals.push(((-smooth_ramp(pe as f64, 800.0, 3500.0) + 1.0) / 2.0).clamp(0.0, 1.0));
    }
    if let Some(ev) = row.enterprise_to_ebitda_hundredths.filter(|&p| p > 0) {
        vals.push(((-smooth_ramp(ev as f64, 600.0, 2000.0) + 1.0) / 2.0).clamp(0.0, 1.0));
    }
    if let Some(pb) = row.price_to_book_hundredths.filter(|&p| p > 0) {
        vals.push(((-smooth_ramp(pb as f64, 100.0, 500.0) + 1.0) / 2.0).clamp(0.0, 1.0));
    }
    if vals.is_empty() {
        None
    } else {
        Some(vals.iter().sum::<f64>() / vals.len() as f64)
    }
}

fn extension_score(daily: Option<&ChartSummary>) -> Option<f64> {
    let d = daily?;
    let mut acc = 0.0;
    let mut n = 0.0;
    if let Some(p) = d.pos_52w_pct {
        acc += (p / 100.0).clamp(0.0, 1.0);
        n += 1.0;
    }
    if let Some(rsi) = d.rsi {
        acc += ((rsi - 30.0) / 50.0).clamp(0.0, 1.0);
        n += 1.0;
    }
    if let (true, Some(e50)) = (d.latest_close_cents > 0, d.ema50_cents) {
        if e50 > 0 {
            let dist = (d.latest_close_cents - e50) as f64 / e50 as f64;
            acc += ((dist + 0.05) / 0.20).clamp(0.0, 1.0);
            n += 0.8;
        }
    }
    if n <= 0.0 {
        None
    } else {
        Some((acc / n).clamp(0.0, 1.0))
    }
}

fn oversold_score(daily: Option<&ChartSummary>) -> Option<f64> {
    let d = daily?;
    let mut acc = 0.0;
    let mut n = 0.0;
    if let Some(rsi) = d.rsi {
        // RSI 25 → 1.0, RSI 55 → 0
        acc += (1.0 - ((rsi - 25.0) / 30.0)).clamp(0.0, 1.0);
        n += 1.0;
    }
    if let Some(p) = d.pos_52w_pct {
        acc += (1.0 - p / 100.0).clamp(0.0, 1.0);
        n += 1.0;
    }
    if let Some(pb) = d.bb_percent_b {
        acc += (1.0 - pb).clamp(0.0, 1.0);
        n += 0.7;
    }
    if n <= 0.0 {
        None
    } else {
        Some((acc / n).clamp(0.0, 1.0))
    }
}

fn trend_score(daily: Option<&ChartSummary>) -> Option<f64> {
    let d = daily?;
    let price = d.latest_close_cents;
    if price <= 0 {
        return None;
    }
    let mut score: f64 = 0.5;
    let mut used = false;
    if let Some(e20) = d.ema20_cents {
        used = true;
        score += if price > e20 { 0.15 } else { -0.15 };
    }
    if let Some(e50) = d.ema50_cents {
        used = true;
        score += if price > e50 { 0.15 } else { -0.15 };
    }
    if let Some(e200) = d.ema200_cents {
        used = true;
        score += if price > e200 { 0.2 } else { -0.2 };
    }
    if let (Some(e50), Some(e200)) = (d.ema50_cents, d.ema200_cents) {
        score += if e50 > e200 { 0.1 } else { -0.1 };
    }
    if !used {
        None
    } else {
        Some(score.clamp(0.0, 1.0))
    }
}

fn sector_flags(sector: Option<&str>) -> (bool, bool) {
    let s = sector.unwrap_or("").to_lowercase();
    let defensive = s.contains("utilities")
        || s.contains("consumer defensive")
        || s.contains("consumer staples")
        || s.contains("healthcare")
        || s.contains("health care")
        || s.contains("real estate");
    let growth = s.contains("technology")
        || s.contains("communication")
        || s.contains("consumer cyclical")
        || s.contains("consumer discretionary")
        || s.contains("semiconductor");
    (defensive, growth)
}

fn liquidity_score(row: &CandidateRow, daily: Option<&ChartSummary>) -> Option<f64> {
    let mut acc = 0.0;
    let mut n = 0.0;
    if let Some(mc) = row.market_cap_dollars {
        // 2B → mid, 20B+ → high
        let t = smooth_ramp((mc as f64).ln(), (2e9_f64).ln(), (50e9_f64).ln());
        acc += ((t + 1.0) / 2.0).clamp(0.0, 1.0);
        n += 1.0;
    }
    if let Some(vr) = daily.and_then(|d| d.volume_ratio) {
        // `volume_ratio` is latest / median volume — a raw ratio, ~1.0 at the median. The previous
        // form, `(vr - 50.0) / 100.0`, was written against a hundredths convention this field does
        // not use, so it clamped to 0.0 for every realistic input while still charging 0.5 to `n`:
        // the volume half of this feature was in the divisor but never in the sum. Centring on the
        // median restores the neutral the formula was reaching for.
        acc += (vr - 0.5).clamp(0.0, 1.0);
        n += 0.5;
    }
    if n <= 0.0 {
        None
    } else {
        Some((acc / n).clamp(0.0, 1.0))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::engine::{ConfidenceBand, ExternalSignalStatus, QualificationStatus};
    use crate::regime::scoring_policy::{RegimeScoringPolicy, ScoreSide};
    use crate::regime::types::MarketRegime;

    fn row_quality(high: bool) -> CandidateRow {
        CandidateRow {
            symbol: if high { "QUAL".into() } else { "JUNK".into() },
            company_name: None,
            market_price_cents: 10_000,
            previous_close_cents: 10_000,
            next_earnings_epoch: None,
            intrinsic_value_cents: 12_000,
            gap_bps: Some(2000),
            qualification: QualificationStatus::Qualified,
            confidence: ConfidenceBand::High,
            signal_status: ExternalSignalStatus::Supportive,
            analyst_opinion_count: Some(10),
            recommendation_mean_hundredths: Some(200),
            sector_name: Some(if high {
                "Healthcare".into()
            } else {
                "Technology".into()
            }),
            low_fair_value_cents: None,
            high_fair_value_cents: None,
            strong_buy_count: None,
            buy_count: None,
            hold_count: None,
            sell_count: None,
            strong_sell_count: None,
            free_cash_flow_dollars: Some(if high { 5_000_000_000 } else { -500_000_000 }),
            operating_cash_flow_dollars: Some(if high { 6_000_000_000 } else { 100_000_000 }),
            market_cap_dollars: Some(50_000_000_000),
            return_on_equity_bps: Some(if high { 1800 } else { -200 }),
            earnings_growth_bps: Some(500),
            debt_to_equity_hundredths: Some(if high { 40 } else { 250 }),
            total_cash_dollars: Some(if high { 10_000_000_000 } else { 100_000_000 }),
            total_debt_dollars: Some(if high { 2_000_000_000 } else { 20_000_000_000 }),
            forward_pe_hundredths: Some(if high { 1400 } else { 4000 }),
            price_to_book_hundredths: Some(200),
            enterprise_to_ebitda_hundredths: Some(1000),
            beta_millis: Some(if high { 750 } else { 1700 }),
            shares_outstanding: None,
            dcf_value_cents: None,
            insider_net_shares_90d: None,
            insider_buy_count: None,
            insider_sell_count: None,
        }
    }

    fn chart_oversold() -> ChartSummary {
        ChartSummary {
            latest_close_cents: 8_000,
            ema20_cents: Some(9_000),
            ema50_cents: Some(10_000),
            ema200_cents: Some(11_000),
            macd_cents: Some(-10),
            signal_cents: Some(0),
            histogram_cents: Some(-5),
            rsi: Some(28.0),
            rsi_slope: Some(-1.0),
            adx: Some(20.0),
            plus_di: Some(15.0),
            minus_di: Some(30.0),
            bb_upper_cents: Some(11_000),
            bb_middle_cents: Some(9_500),
            bb_lower_cents: Some(8_000),
            bb_percent_b: Some(0.1),
            bb_bandwidth: Some(0.3),
            obv_slope: None,
            // A raw ratio, which is what `compute_volume_ratio` returns: 1.2 = 20% above the
            // median. The old `Some(120.0)` here was a hundredths value the production path can
            // never produce, and feeding it is why the dead volume term in `liquidity_score`
            // survived — the fixture was the only input the broken formula worked on.
            volume_ratio: Some(1.2),
            atr_cents: Some(200),
            high_52w_cents: Some(15_000),
            low_52w_cents: Some(7_000),
            pos_52w_pct: Some(12.0),
        }
    }

    fn chart_extended() -> ChartSummary {
        let mut c = chart_oversold();
        c.latest_close_cents = 14_500;
        c.ema20_cents = Some(13_000);
        c.ema50_cents = Some(12_000);
        c.ema200_cents = Some(10_000);
        c.rsi = Some(78.0);
        c.pos_52w_pct = Some(95.0);
        c.bb_percent_b = Some(0.95);
        c
    }

    fn policy(stance: &str) -> RegimeScoringPolicy {
        let r = MarketRegime {
            action_stance: stance.into(),
            environment_band: "RiskOff".into(),
            primary_regime: "Capitulation".into(),
            global_confidence_bps: 9000,
            prefer_quality: true,
            cnn_fear_greed: Some(12),
            ..MarketRegime::default()
        };
        RegimeScoringPolicy::from_regime(&r, ScoreSide::Long).unwrap()
    }

    #[test]
    fn quality_beats_junk_in_blood_in_streets() {
        let p = policy("BloodInStreets");
        let q = score_regime_fit(&row_quality(true), Some(&chart_oversold()), &p);
        let j = score_regime_fit(&row_quality(false), Some(&chart_oversold()), &p);
        assert!(
            q.score.unwrap() > j.score.unwrap(),
            "q={:?} j={:?}",
            q.score,
            j.score
        );
    }

    #[test]
    fn junk_oversold_not_rewarded_like_quality() {
        let p = policy("Washout");
        let q = score_regime_fit(&row_quality(true), Some(&chart_oversold()), &p);
        let j = score_regime_fit(&row_quality(false), Some(&chart_oversold()), &p);
        // quality oversold should clearly beat junk oversold
        assert!(
            q.score.unwrap() >= j.score.unwrap() + 10,
            "q={:?} j={:?}",
            q.score,
            j.score
        );
    }

    #[test]
    fn extended_negative_in_euphoria() {
        let r = MarketRegime {
            action_stance: "Euphoria".into(),
            environment_band: "RiskOn".into(),
            primary_regime: "LateBull".into(),
            global_confidence_bps: 9000,
            cnn_fear_greed: Some(85),
            ..MarketRegime::default()
        };
        let p = RegimeScoringPolicy::from_regime(&r, ScoreSide::Long).unwrap();
        let ext = score_regime_fit(&row_quality(true), Some(&chart_extended()), &p);
        let calm = score_regime_fit(&row_quality(true), Some(&chart_oversold()), &p);
        assert!(
            ext.score.unwrap() < calm.score.unwrap(),
            "extended should score worse in euphoria: ext={:?} calm={:?}",
            ext.score,
            calm.score
        );
    }

    #[test]
    fn short_likes_extension_in_euphoria() {
        let r = MarketRegime {
            action_stance: "Euphoria".into(),
            environment_band: "RiskOn".into(),
            primary_regime: "LateBull".into(),
            global_confidence_bps: 9000,
            cnn_fear_greed: Some(85),
            ..MarketRegime::default()
        };
        let p = RegimeScoringPolicy::from_regime(&r, ScoreSide::Short).unwrap();
        let ext = score_regime_fit(&row_quality(false), Some(&chart_extended()), &p);
        assert!(
            ext.score.unwrap() > 0,
            "short fit for extended junk in euphoria: {:?}",
            ext.score
        );
    }

    #[test]
    fn causes_sorted_by_abs_contribution_and_capped_at_three() {
        let p = policy("BloodInStreets");
        let result = score_regime_fit(&row_quality(true), Some(&chart_oversold()), &p);
        assert!(result.score.is_some());
        assert!(result.causes.len() <= 3, "causes={:?}", result.causes);
        assert_eq!(result.causes.len(), result.signals.len());
        for window in result.causes.windows(2) {
            assert!(
                window[0].contribution_bps.abs() >= window[1].contribution_bps.abs(),
                "not sorted by |contrib|: {:?}",
                result.causes
            );
        }
        // No raw internal tokens in legacy signals beyond known tags.
        for sig in &result.signals {
            assert!(
                !sig.contains("policy") && !sig.contains("bucket"),
                "signal={sig}"
            );
        }
    }

    #[test]
    fn long_extension_risk_short_extension_support_in_euphoria() {
        let r = MarketRegime {
            action_stance: "Euphoria".into(),
            environment_band: "RiskOn".into(),
            primary_regime: "LateBull".into(),
            global_confidence_bps: 9000,
            cnn_fear_greed: Some(85),
            ..MarketRegime::default()
        };
        let long = RegimeScoringPolicy::from_regime(&r, ScoreSide::Long).unwrap();
        let short = RegimeScoringPolicy::from_regime(&r, ScoreSide::Short).unwrap();
        let long_fit = score_regime_fit(&row_quality(true), Some(&chart_extended()), &long);
        let short_fit = score_regime_fit(&row_quality(false), Some(&chart_extended()), &short);

        let long_ext = long_fit
            .causes
            .iter()
            .find(|c| c.factor == RegimeCauseFactor::Extension);
        let short_ext = short_fit
            .causes
            .iter()
            .find(|c| c.factor == RegimeCauseFactor::Extension);
        if let Some(c) = long_ext {
            assert_eq!(c.effect, RegimeCauseEffect::Risk);
        }
        if let Some(c) = short_ext {
            assert_eq!(c.effect, RegimeCauseEffect::Support);
        }
    }

    #[test]
    fn insufficient_coverage_marks_unavailable_reason() {
        let p = policy("BloodInStreets");
        let sparse = CandidateRow {
            free_cash_flow_dollars: None,
            operating_cash_flow_dollars: None,
            return_on_equity_bps: None,
            debt_to_equity_hundredths: None,
            total_cash_dollars: None,
            total_debt_dollars: None,
            forward_pe_hundredths: None,
            beta_millis: None,
            market_cap_dollars: None,
            sector_name: None,
            ..row_quality(true)
        };
        let result = score_regime_fit(&sparse, None, &p);
        assert_eq!(result.score, None);
        assert!(result.causes.is_empty());
        assert_eq!(
            result.unavailable_reason,
            Some(MarketContextUnavailableReason::InsufficientAssetData)
        );
    }

    /// A no-market-cap row so the volume term is the *only* thing `liquidity_score` reads.
    fn row_without_market_cap() -> CandidateRow {
        CandidateRow {
            market_cap_dollars: None,
            ..row_quality(true)
        }
    }

    fn liquidity_at_volume_ratio(vr: f64) -> f64 {
        let mut chart = chart_oversold();
        chart.volume_ratio = Some(vr);
        liquidity_score(&row_without_market_cap(), Some(&chart))
            .expect("volume ratio alone must yield a liquidity reading")
    }

    /// Turnover has to actually move the score.
    ///
    /// The previous formula, `(vr - 50.0) / 100.0`, clamped to 0.0 for every ratio below 50 —
    /// which is every ratio the production path can produce — while still charging 0.5 to the
    /// denominator. Both of these read 0.0 before the fix, so this assertion is what makes the
    /// volume term's presence observable rather than assumed.
    #[test]
    fn liquidity_volume_term_separates_heavy_from_light_turnover() {
        assert!(
            liquidity_at_volume_ratio(1.4) > liquidity_at_volume_ratio(0.6),
            "heavy turnover {} should score above light turnover {}",
            liquidity_at_volume_ratio(1.4),
            liquidity_at_volume_ratio(0.6)
        );
    }

    /// At the median the term is neutral, which is what `(vr - 50.0) / 100.0` was reaching for on
    /// a hundredths scale and never reached on a raw ratio.
    #[test]
    fn liquidity_volume_term_is_neutral_at_the_median() {
        assert!(
            (liquidity_at_volume_ratio(1.0) - 1.0).abs() < 1e-9,
            "median turnover as the only term should normalize to 1.0, got {}",
            liquidity_at_volume_ratio(1.0)
        );
    }
}

/// Cross-platform agreement for this bucket, driven by
/// `shared/contracts/market-regime-fit-v1.json`.
///
/// Windows computes regime fit in Rust and Android in Kotlin, sharing no code — only mirrored
/// arithmetic. This side is the reference: it asserts that the contract still describes what Rust
/// does, so a Rust change either updates the contract deliberately or fails here. The Android side
/// runs the same cases against the same file, which is what turns "the two agree" into a claim
/// something can falsify.
#[cfg(test)]
mod contract_tests {
    use super::*;
    use crate::engine::{ConfidenceBand, ExternalSignalStatus, QualificationStatus};
    use crate::regime::scoring_policy::ScoreSide;
    use crate::regime::types::MarketRegime;
    use serde::Deserialize;
    use std::path::PathBuf;

    #[derive(Deserialize)]
    struct Contract {
        cases: Vec<Case>,
    }

    #[derive(Deserialize)]
    struct Case {
        name: String,
        regime: RegimeInput,
        symbol: SymbolInput,
        chart: Option<ChartInput>,
        expected_policy: Option<PolicyExpectation>,
        expected_fit: Option<FitExpectation>,
    }

    #[derive(Deserialize)]
    struct RegimeInput {
        primary_regime: String,
        environment_band: String,
        action_stance: String,
        global_confidence_bps: u32,
        prefer_quality: bool,
        breadth_above_ma200_pct: Option<f64>,
        credit_score: Option<i32>,
        cnn_fear_greed: Option<u32>,
    }

    #[derive(Deserialize)]
    struct SymbolInput {
        sector_name: Option<String>,
        market_cap_dollars: Option<u64>,
        free_cash_flow_dollars: Option<i64>,
        operating_cash_flow_dollars: Option<i64>,
        return_on_equity_bps: Option<i32>,
        debt_to_equity_hundredths: Option<i32>,
        total_cash_dollars: Option<i64>,
        total_debt_dollars: Option<i64>,
        forward_pe_hundredths: Option<u32>,
        price_to_book_hundredths: Option<u32>,
        enterprise_to_ebitda_hundredths: Option<i32>,
        beta_millis: Option<i32>,
    }

    #[derive(Deserialize)]
    struct ChartInput {
        latest_close_cents: i64,
        ema20_cents: Option<i64>,
        ema50_cents: Option<i64>,
        ema200_cents: Option<i64>,
        rsi: Option<f64>,
        pos_52w_pct: Option<f64>,
        bb_percent_b: Option<f64>,
        volume_ratio: Option<f64>,
    }

    #[derive(Deserialize, PartialEq, Debug)]
    struct PolicyExpectation {
        w_quality: f64,
        w_low_beta: f64,
        w_value: f64,
        w_oversold_quality: f64,
        w_anti_extension: f64,
        w_trend: f64,
        w_defensive: f64,
        w_growth: f64,
        w_liquidity: f64,
        beta_haircut_mult: f64,
        strength: f64,
    }

    #[derive(Deserialize, PartialEq, Debug)]
    struct FitExpectation {
        score: Option<i32>,
        signals: Vec<String>,
        unavailable_reason: Option<String>,
    }

    fn contract() -> Contract {
        let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
            .join("../../../shared/contracts/market-regime-fit-v1.json");
        let raw = std::fs::read_to_string(&path)
            .unwrap_or_else(|e| panic!("cannot read {}: {e}", path.display()));
        serde_json::from_str(&raw).expect("contract must parse")
    }

    fn row_from(input: &SymbolInput) -> CandidateRow {
        CandidateRow {
            symbol: "CASE".into(),
            company_name: None,
            market_price_cents: 10_000,
            previous_close_cents: 10_000,
            next_earnings_epoch: None,
            intrinsic_value_cents: 12_000,
            gap_bps: Some(2000),
            qualification: QualificationStatus::Qualified,
            confidence: ConfidenceBand::High,
            signal_status: ExternalSignalStatus::Supportive,
            analyst_opinion_count: None,
            recommendation_mean_hundredths: None,
            sector_name: input.sector_name.clone(),
            low_fair_value_cents: None,
            high_fair_value_cents: None,
            strong_buy_count: None,
            buy_count: None,
            hold_count: None,
            sell_count: None,
            strong_sell_count: None,
            free_cash_flow_dollars: input.free_cash_flow_dollars,
            operating_cash_flow_dollars: input.operating_cash_flow_dollars,
            market_cap_dollars: input.market_cap_dollars,
            return_on_equity_bps: input.return_on_equity_bps,
            earnings_growth_bps: None,
            debt_to_equity_hundredths: input.debt_to_equity_hundredths,
            total_cash_dollars: input.total_cash_dollars,
            total_debt_dollars: input.total_debt_dollars,
            forward_pe_hundredths: input.forward_pe_hundredths,
            price_to_book_hundredths: input.price_to_book_hundredths,
            enterprise_to_ebitda_hundredths: input.enterprise_to_ebitda_hundredths,
            beta_millis: input.beta_millis,
            shares_outstanding: None,
            dcf_value_cents: None,
            insider_net_shares_90d: None,
            insider_buy_count: None,
            insider_sell_count: None,
        }
    }

    fn chart_from(input: &ChartInput) -> ChartSummary {
        ChartSummary {
            latest_close_cents: input.latest_close_cents,
            ema20_cents: input.ema20_cents,
            ema50_cents: input.ema50_cents,
            ema200_cents: input.ema200_cents,
            macd_cents: None,
            signal_cents: None,
            histogram_cents: None,
            rsi: input.rsi,
            rsi_slope: None,
            adx: None,
            plus_di: None,
            minus_di: None,
            bb_upper_cents: None,
            bb_middle_cents: None,
            bb_lower_cents: None,
            bb_percent_b: input.bb_percent_b,
            bb_bandwidth: None,
            obv_slope: None,
            volume_ratio: input.volume_ratio,
            atr_cents: None,
            high_52w_cents: None,
            low_52w_cents: None,
            pos_52w_pct: input.pos_52w_pct,
        }
    }

    fn regime_from(input: &RegimeInput) -> MarketRegime {
        MarketRegime {
            primary_regime: input.primary_regime.clone(),
            environment_band: input.environment_band.clone(),
            action_stance: input.action_stance.clone(),
            global_confidence_bps: input.global_confidence_bps,
            prefer_quality: input.prefer_quality,
            breadth_above_ma200_pct: input.breadth_above_ma200_pct,
            credit_score: input.credit_score,
            cnn_fear_greed: input.cnn_fear_greed,
            ..MarketRegime::default()
        }
    }

    fn observed_policy(policy: &RegimeScoringPolicy) -> PolicyExpectation {
        PolicyExpectation {
            w_quality: policy.w_quality,
            w_low_beta: policy.w_low_beta,
            w_value: policy.w_value,
            w_oversold_quality: policy.w_oversold_quality,
            w_anti_extension: policy.w_anti_extension,
            w_trend: policy.w_trend,
            w_defensive: policy.w_defensive,
            w_growth: policy.w_growth,
            w_liquidity: policy.w_liquidity,
            beta_haircut_mult: policy.beta_haircut_mult,
            strength: policy.strength,
        }
    }

    fn observed_fit(fit: &RegimeFitResult) -> FitExpectation {
        FitExpectation {
            score: fit.score,
            signals: fit.signals.clone(),
            unavailable_reason: fit.unavailable_reason.map(|r| format!("{r:?}")),
        }
    }

    /// Every case is checked before anything fails, and each mismatch is reported as the JSON the
    /// contract should carry. A test that stopped at the first difference would need one run per
    /// case to reconcile a deliberate recalibration.
    #[test]
    fn regime_fit_matches_the_shared_contract() {
        let mut mismatches: Vec<String> = Vec::new();

        for case in contract().cases {
            let regime = regime_from(&case.regime);
            let policy = RegimeScoringPolicy::from_regime(&regime, ScoreSide::Long)
                .unwrap_or_else(|| panic!("case {} must yield a policy", case.name));
            let chart = case.chart.as_ref().map(chart_from);
            let fit = score_regime_fit(&row_from(&case.symbol), chart.as_ref(), &policy);

            let policy_seen = observed_policy(&policy);
            let fit_seen = observed_fit(&fit);
            if case.expected_policy.as_ref() != Some(&policy_seen)
                || case.expected_fit.as_ref() != Some(&fit_seen)
            {
                mismatches.push(format!(
                    "{}\n      \"expected_policy\": {},\n      \"expected_fit\": {}",
                    case.name,
                    serde_json::to_string(&serde_json::json!({
                        "w_quality": policy_seen.w_quality,
                        "w_low_beta": policy_seen.w_low_beta,
                        "w_value": policy_seen.w_value,
                        "w_oversold_quality": policy_seen.w_oversold_quality,
                        "w_anti_extension": policy_seen.w_anti_extension,
                        "w_trend": policy_seen.w_trend,
                        "w_defensive": policy_seen.w_defensive,
                        "w_growth": policy_seen.w_growth,
                        "w_liquidity": policy_seen.w_liquidity,
                        "beta_haircut_mult": policy_seen.beta_haircut_mult,
                        "strength": policy_seen.strength,
                    }))
                    .unwrap(),
                    serde_json::to_string(&serde_json::json!({
                        "score": fit_seen.score,
                        "signals": fit_seen.signals,
                        "unavailable_reason": fit_seen.unavailable_reason,
                    }))
                    .unwrap(),
                ));
            }
        }

        assert!(
            mismatches.is_empty(),
            "market-regime-fit-v1.json disagrees with this implementation:\n{}",
            mismatches.join("\n")
        );
    }
}
