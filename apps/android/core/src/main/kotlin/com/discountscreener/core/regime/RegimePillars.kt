package com.discountscreener.core.regime

import com.discountscreener.core.model.ChartRangeSummary
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Pillar computations, ported from `regime/pillars.rs`.
 *
 * Each pillar answers with a score in −100..+100 *and* a confidence, and the two are independent:
 * a pillar with no inputs returns a zero score at zero confidence, which the composite drops
 * entirely. That is the difference between "the market is neutral" and "we cannot tell", and it is
 * the property most worth preserving in the port.
 */

/** One `(symbol, summary)` pair from the screener cache, the breadth pillar's raw input. */
data class SymbolChartView(val symbol: String, val summary: ChartRangeSummary)

private fun sig(id: String, label: String, contribution: Int, detail: String? = null) =
    RegimeSignal(id = id, label = label, contribution = contribution, detail = detail)

/** Weighted mean over `(score, weight)` pairs, mirroring `pillars.rs::weighted`. */
private fun weighted(parts: List<Pair<Int, Double>>): Int? = weightedMeanI32(parts)

private fun pct(numerator: Int, denominator: Int): Double? =
    if (denominator == 0) null else numerator.toDouble() / denominator.toDouble() * 100.0

/** Rust's `{v:.0}` — round half away from zero, then print with no decimals. */
private fun fmt0(value: Double): String = roundHalfAwayFromZero(value).toString()

/** Rust's `{v:.1}` / `{v:+.1}` / `{v:.2}`, which round half away from zero rather than half-even. */
private fun fmtN(value: Double, decimals: Int, signed: Boolean = false): String {
    var scale = 1.0
    repeat(decimals) { scale *= 10.0 }
    val scaled = roundHalfAwayFromZero(abs(value) * scale)
    val whole = scaled / pow10(decimals)
    val fraction = scaled % pow10(decimals)
    val sign = when {
        value < 0.0 -> "-"
        signed -> "+"
        else -> ""
    }
    if (decimals == 0) return "$sign$whole"
    return "$sign$whole.${fraction.toString().padStart(decimals, '0')}"
}

private fun pow10(exponent: Int): Int {
    var out = 1
    repeat(exponent) { out *= 10 }
    return out
}

// ── Breadth ──────────────────────────────────────────────────────────────────

/**
 * Breadth across the tracked universe.
 *
 * ETFs and crypto are excluded: breadth counts how many *stocks* participate, and an index ETF
 * would otherwise vote on whether the index is broad.
 */
fun computeBreadth(chartSummaries: List<SymbolChartView>): BreadthSnapshot {
    var above200 = 0
    var have200 = 0
    var above50 = 0
    var have50 = 0
    var rsiCount = 0
    var rsiAbove50 = 0
    var rsiAbove70 = 0
    var rsiBelow30 = 0
    var macdCount = 0
    var macdPositive = 0
    var posCount = 0
    var nearHigh = 0
    var nearLow = 0
    val positions = ArrayList<Double>()

    for ((symbol, summary) in chartSummaries) {
        if (AssetClassification.isCrypto(symbol) || AssetClassification.isEtf(symbol)) continue
        val price = summary.latestCloseCents ?: continue
        if (price <= 0L) continue
        summary.ema200Cents?.let { ma ->
            have200 += 1
            if (price > ma) above200 += 1
        }
        summary.ema50Cents?.let { ma ->
            have50 += 1
            if (price > ma) above50 += 1
        }
        summary.latestWilderRsi?.let { rsi ->
            rsiCount += 1
            if (rsi > 50.0) rsiAbove50 += 1
            if (rsi > 70.0) rsiAbove70 += 1
            if (rsi < 30.0) rsiBelow30 += 1
        }
        summary.histogramCents?.let { histogram ->
            macdCount += 1
            if (histogram > 0L) macdPositive += 1
        }
        summary.pos52wPct?.let { position ->
            posCount += 1
            positions.add(position)
            if (position >= 90.0) nearHigh += 1
            if (position <= 10.0) nearLow += 1
        }
    }

    positions.sort()
    return BreadthSnapshot(
        aboveMa200Pct = pct(above200, have200),
        aboveMa50Pct = pct(above50, have50),
        sample = maxOf(have200, have50),
        pctRsiAbove50 = pct(rsiAbove50, rsiCount),
        pctRsiAbove70 = pct(rsiAbove70, rsiCount),
        pctRsiBelow30 = pct(rsiBelow30, rsiCount),
        pctMacdPositive = pct(macdPositive, macdCount),
        pctNear52wHigh = pct(nearHigh, posCount),
        pctNear52wLow = pct(nearLow, posCount),
        medianPos52w = positions.getOrNull(positions.size / 2),
    )
}

fun breadthPillar(breadth: BreadthSnapshot): PillarResult {
    val signals = ArrayList<RegimeSignal>()
    val parts = ArrayList<Pair<Int, Double>>()

    breadth.aboveMa200Pct?.let { value ->
        val score = roundHalfAwayFromZero(linmap(value, 25.0, 75.0, -90.0, 90.0))
        parts.add(score to 0.35)
        signals.add(sig("breadth_ma200", "% above 200-day MA", score, "${fmt0(value)}% (n=${breadth.sample})"))
    }
    breadth.aboveMa50Pct?.let { value ->
        val score = roundHalfAwayFromZero(linmap(value, 25.0, 75.0, -90.0, 90.0))
        parts.add(score to 0.25)
        signals.add(sig("breadth_ma50", "% above 50-day MA", score, "${fmt0(value)}%"))
    }
    breadth.pctRsiAbove50?.let { value ->
        val score = roundHalfAwayFromZero(linmap(value, 30.0, 70.0, -70.0, 70.0))
        parts.add(score to 0.15)
        signals.add(sig("breadth_rsi", "% with RSI>50", score, "${fmt0(value)}%"))
    }
    breadth.pctMacdPositive?.let { value ->
        val score = roundHalfAwayFromZero(linmap(value, 30.0, 70.0, -60.0, 60.0))
        parts.add(score to 0.10)
        signals.add(sig("breadth_macd", "% MACD hist positive", score, "${fmt0(value)}%"))
    }
    val high = breadth.pctNear52wHigh
    val low = breadth.pctNear52wLow
    if (high != null && low != null) {
        val score = roundHalfAwayFromZero(linmap(high - low, -20.0, 20.0, -80.0, 80.0))
        parts.add(score to 0.15)
        signals.add(sig("breadth_52w", "52w high/low breadth", score, "hi ${fmt0(high)}% / lo ${fmt0(low)}%"))
    }

    return PillarResult(
        score = clampI32(weighted(parts) ?: 0, -100, 100),
        confidenceBps = breadthConfidence(breadth.sample, parts.size),
        signals = signals,
        stale = false,
    )
}

private fun breadthConfidence(sample: Int, signalCount: Int): Int {
    if (sample == 0 || signalCount == 0) return 0
    val sampleFactor = when {
        sample >= 200 -> 1.0
        sample >= 80 -> 0.85
        sample >= 40 -> 0.65
        else -> 0.40
    }
    val signalFactor = clamp(signalCount.toDouble() / 5.0, 0.3, 1.0)
    return roundHalfAwayFromZero(sampleFactor * signalFactor * 10_000.0)
}

// ── Trend (SPY) ──────────────────────────────────────────────────────────────

fun trendPillar(spy: ChartRangeSummary?, spyCloses: List<Double>): PillarResult {
    if (spy == null) {
        return PillarResult(
            score = 0,
            confidenceBps = 0,
            signals = listOf(sig("trend_missing", "SPY data missing", 0)),
            stale = true,
        )
    }

    val signals = ArrayList<RegimeSignal>()
    val parts = ArrayList<Pair<Int, Double>>()
    val price = spy.latestCloseCents ?: 0L
    val ema50 = spy.ema50Cents
    val ema200 = spy.ema200Cents

    if (price > 0L) {
        if (ema200 != null) {
            val above = price > ema200
            val score = if (above) 55 else -55
            parts.add(score to 0.30)
            signals.add(sig("spy_ma200", "SPY vs 200-day MA", score, if (above) "above" else "below"))
        }
        if (ema50 != null && ema200 != null) {
            val score = if (ema50 > ema200) 40 else -40
            parts.add(score to 0.20)
            signals.add(sig("ema_stack", "EMA50 vs EMA200", score))
        }
        if (ema50 != null) {
            val score = if (price > ema50) 30 else -30
            parts.add(score to 0.15)
            signals.add(sig("spy_ma50", "SPY vs 50-day MA", score))
        }
        if (ema200 != null && ema200 > 0L) {
            val distance = (price - ema200).toDouble() / ema200.toDouble() * 100.0
            val score = when {
                distance > 12.0 -> -25
                distance > 6.0 -> -10
                distance < -15.0 -> 25
                distance < -8.0 -> 10
                else -> 0
            }
            if (score != 0) {
                parts.add(score to 0.10)
                signals.add(sig("dist_ma200", "Distance to MA200", score, "${fmtN(distance, 1)}%"))
            }
        }
    }

    spy.adx?.let { adx ->
        val direction = if ((spy.plusDi ?: 0.0) > (spy.minusDi ?: 0.0)) 1.0 else -1.0
        val score = roundHalfAwayFromZero(direction * linmap(adx, 15.0, 40.0, 0.0, 1.0) * 70.0)
        parts.add(score to 0.15)
        signals.add(sig("adx", "ADX trend strength", score, "ADX ${fmt0(adx)}"))
    }

    if (spyCloses.size >= 65) {
        val start = spyCloses[spyCloses.size - 61]
        val end = spyCloses.last()
        if (start > 0.0) {
            val logReturn = ln(end / start) * 100.0
            val score = roundHalfAwayFromZero(linmap(logReturn, -12.0, 12.0, -80.0, 80.0))
            parts.add(score to 0.10)
            signals.add(sig("slope_60d", "60d log slope", score, "${fmtN(logReturn, 1)}% log"))
        }
    }

    val confidence = if (parts.isEmpty()) 0 else minOf(5000 + (parts.size * 800), 9500)
    return PillarResult(
        score = clampI32(weighted(parts) ?: 0, -100, 100),
        confidenceBps = confidence,
        signals = signals,
        stale = false,
    )
}

// ── Volatility / stress ──────────────────────────────────────────────────────

fun volSnapshot(vixCloses: List<Double>, vix3mCloses: List<Double>, spyCloses: List<Double>): VolSnapshot {
    val vix = vixCloses.lastOrNull()?.takeIf { it > 0.0 }
    val vix3m = vix3mCloses.lastOrNull()?.takeIf { it > 0.0 }
    val term = if (vix != null && vix3m != null && vix3m > 0.0) vix / vix3m else null

    val sorted = vixCloses.filter { it > 0.0 }.sorted()
    val percentile = vix?.let { percentileOf(sorted, it) }

    val returns = simpleReturns(spyCloses)
    val window = if (returns.size > 20) returns.takeLast(20) else returns
    val realized = realizedVolPct(window)

    var stress = 0.0
    val signals = ArrayList<RegimeSignal>()
    var confidenceParts = 0

    if (percentile != null) {
        val value = stressFromVixPercentile(percentile)
        stress += value * 0.55
        confidenceParts += 1
        signals.add(sig("vix_pctl", "VIX 1y percentile", roundHalfAwayFromZero(value), "p${fmt0(percentile)}"))
    } else if (vix != null) {
        val value = stressFromVixLevel(vix)
        stress += value * 0.55
        confidenceParts += 1
        signals.add(sig("vix_level", "VIX level", roundHalfAwayFromZero(value), fmtN(vix, 1)))
    }

    if (term != null) {
        val value = termStructureStress(term)
        stress += value
        confidenceParts += 1
        signals.add(sig("vix_term", "VIX term structure", roundHalfAwayFromZero(value), "ratio ${fmtN(term, 2)}"))
    }

    if (vixCloses.size >= 6) {
        val now = vixCloses.last()
        val previous = vixCloses[vixCloses.size - 6]
        if (previous > 0.0) {
            val delta = now - previous
            val value = linmap(delta, -8.0, 12.0, -30.0, 40.0)
            stress += value * 0.25
            confidenceParts += 1
            signals.add(sig("vix_vel", "5d VIX change", roundHalfAwayFromZero(value), fmtN(delta, 1, signed = true)))
        }
    }

    if (realized != null) {
        val value = linmap(realized, 10.0, 40.0, -20.0, 50.0)
        stress += value * 0.20
        confidenceParts += 1
        signals.add(sig("realized_vol", "SPY realized vol", roundHalfAwayFromZero(value), "${fmtN(realized, 1)}% ann."))
    }

    val confidence = when (confidenceParts) {
        0 -> 0
        1 -> 4500
        2 -> 6500
        3 -> 8000
        else -> 9000
    }

    return VolSnapshot(
        vix = vix,
        vixPercentile1y = percentile,
        vixTermRatio = term,
        vixState = vix?.let { vixState(it) } ?: "Unknown",
        realizedVolPct = realized,
        stressScore = clampI32(roundHalfAwayFromZero(stress), -100, 100),
        confidenceBps = confidence,
        signals = signals,
    )
}

fun volPillar(snapshot: VolSnapshot): PillarResult = PillarResult(
    score = snapshot.stressScore,
    confidenceBps = snapshot.confidenceBps,
    signals = snapshot.signals,
    stale = snapshot.vix == null,
)

// ── Sentiment ────────────────────────────────────────────────────────────────

fun sentimentPillar(cnn: CnnFearGreed?, breadth: BreadthSnapshot): PillarResult {
    val signals = ArrayList<RegimeSignal>()
    val parts = ArrayList<Pair<Int, Double>>()

    if (cnn != null) {
        val score = fngToContrarianScore(cnn.score)
        parts.add(score to 0.70)
        val zone = fngZone(cnn.score)
        signals.add(sig("cnn_fng", "CNN Fear & Greed", score, "${fmt0(cnn.score)} · ${fngLabelFromZone(zone)}"))
        cnn.previousClose?.let { previous ->
            val delta = cnn.score - previous
            val velocity = clampI32(roundHalfAwayFromZero(-delta * 3.0), -20, 20)
            parts.add(velocity to 0.10)
            signals.add(sig("fng_delta", "F&G vs prev close", velocity, fmtN(delta, 1, signed = true)))
        }
    }

    val overbought = breadth.pctRsiAbove70
    val oversold = breadth.pctRsiBelow30
    if (overbought != null && oversold != null) {
        val net = oversold - overbought
        val score = roundHalfAwayFromZero(linmap(net, -25.0, 25.0, -70.0, 70.0))
        parts.add(score to 0.20)
        signals.add(
            sig("internal_rsi", "Universe RSI extremes", score, ">70:${fmt0(overbought)}% <30:${fmt0(oversold)}%"),
        )
    } else {
        breadth.medianPos52w?.let { median ->
            val score = roundHalfAwayFromZero(linmap(median, 30.0, 80.0, 50.0, -50.0))
            parts.add(score to 0.20)
            signals.add(sig("internal_52w", "Median 52w position", score, fmt0(median)))
        }
    }

    val confidence = when {
        cnn != null && parts.size >= 2 -> 9200
        cnn != null -> 8000
        parts.isNotEmpty() -> 4500
        else -> 0
    }

    return PillarResult(
        score = clampI32(weighted(parts) ?: 0, -100, 100),
        confidenceBps = confidence,
        signals = signals,
        stale = cnn == null,
    )
}

// ── Cross-asset ──────────────────────────────────────────────────────────────

fun crossAssetPillar(closes: Map<String, List<Double>>): PillarResult {
    val snapshot = crossAssetSnapshot(closes)
    val score = weighted(
        listOf(
            (snapshot.creditScore ?: 0) to if (snapshot.creditScore != null) 0.35 else 0.0,
            (snapshot.equityBondScore ?: 0) to if (snapshot.equityBondScore != null) 0.25 else 0.0,
            (snapshot.leadershipScore ?: 0) to if (snapshot.leadershipScore != null) 0.25 else 0.0,
            (snapshot.smallLargeScore ?: 0) to if (snapshot.smallLargeScore != null) 0.15 else 0.0,
        ),
    ) ?: 0
    return PillarResult(
        score = score,
        confidenceBps = snapshot.confidenceBps,
        signals = snapshot.signals,
        stale = snapshot.confidenceBps < 2000,
    )
}

fun crossAssetSnapshot(closes: Map<String, List<Double>>): CrossAssetSnapshot {
    val signals = ArrayList<RegimeSignal>()
    var count = 0

    fun pair(a: String, b: String, lookback: Int): Double? {
        val seriesA = closes[a] ?: return null
        val seriesB = closes[b] ?: return null
        return relativeReturnPct(seriesA, seriesB, lookback)
    }

    val credit = pair("HYG", "IEF", 20) ?: pair("JNK", "TLT", 20)
    val creditScore = credit?.let { relReturnToScore(it, 4.0) }
    if (credit != null && creditScore != null) {
        count += 1
        signals.add(sig("credit_hy_ie", "HY vs IE credit", creditScore, "${fmtN(credit, 1, signed = true)}% 20d"))
    }

    val equityBond = pair("SPY", "TLT", 20) ?: pair("SPY", "IEF", 20)
    val equityBondScore = equityBond?.let { relReturnToScore(it, 5.0) }
    if (equityBond != null && equityBondScore != null) {
        count += 1
        signals.add(sig("eq_bond", "Equity vs bonds", equityBondScore, "${fmtN(equityBond, 1, signed = true)}% 20d"))
    }

    val growth = avgLastReturn(closes, listOf("XLK", "XLY"), 20)
    val defensive = avgLastReturn(closes, listOf("XLP", "XLU", "XLV"), 20)
    var leadershipScore: Int? = null
    if (growth != null && defensive != null) {
        val relative = growth - defensive
        val score = relReturnToScore(relative, 4.0)
        leadershipScore = score
        count += 1
        signals.add(sig("leadership", "Growth vs defensive", score, "${fmtN(relative, 1, signed = true)}% 20d"))
    }

    val small = pair("IWM", "SPY", 20)
    val smallLargeScore = small?.let { relReturnToScore(it, 4.0) }
    if (small != null && smallLargeScore != null) {
        count += 1
        signals.add(sig("small_large", "IWM vs SPY", smallLargeScore, "${fmtN(small, 1, signed = true)}% 20d"))
    }

    val confidence = when (count) {
        0 -> 0
        1 -> 4000
        2 -> 6000
        3 -> 7800
        else -> 9000
    }

    return CrossAssetSnapshot(
        creditScore = creditScore,
        equityBondScore = equityBondScore,
        leadershipScore = leadershipScore,
        smallLargeScore = smallLargeScore,
        signals = signals,
        confidenceBps = confidence,
    )
}

/**
 * Mean simple return across [symbols] over [lookback] bars.
 *
 * A plain mean over at most three sector ETFs, matching `pillars.rs::avg_last_return`. Kept as a
 * mean rather than a robust centre because the two platforms have to agree to the integer, and
 * because with n ≤ 3 a trimmed estimator has nothing to trim.
 */
private fun avgLastReturn(closes: Map<String, List<Double>>, symbols: List<String>, lookback: Int): Double? {
    var accumulator = 0.0
    var count = 0
    for (symbol in symbols) {
        val series = closes[symbol] ?: continue
        if (series.size <= lookback) continue
        val start = series[series.size - 1 - lookback]
        val end = series.last()
        if (start > 0.0) {
            accumulator += (end - start) / start * 100.0
            count += 1
        }
    }
    return if (count == 0) null else accumulator / count.toDouble()
}

// ── Quality / fragility ──────────────────────────────────────────────────────

fun qualityPillar(
    breadth: BreadthSnapshot,
    spyAboveMa200: Boolean?,
    avgCorrMilli: Int?,
    stress: Int,
    cross: CrossAssetSnapshot,
): PillarResult {
    val signals = ArrayList<RegimeSignal>()
    val parts = ArrayList<Pair<Int, Double>>()

    val breadth200 = breadth.aboveMa200Pct
    if (spyAboveMa200 == true && breadth200 != null) {
        if (breadth200 < 45.0) {
            val score = roundHalfAwayFromZero(linmap(breadth200, 25.0, 45.0, -80.0, -20.0))
            parts.add(score to 0.30)
            signals.add(sig("narrow_rally", "Narrow rally", score, "SPY↑ breadth ${fmt0(breadth200)}%"))
        } else if (breadth200 > 55.0) {
            parts.add(40 to 0.20)
            signals.add(sig("broad_participation", "Broad participation", 40, "${fmt0(breadth200)}%"))
        }
    }

    avgCorrMilli?.let { milli ->
        val correlation = milli.toDouble() / 1000.0
        val score = roundHalfAwayFromZero(linmap(correlation, 0.20, 0.80, 50.0, -70.0))
        parts.add(score to 0.25)
        signals.add(sig("avg_corr", "Avg pairwise corr", score, fmtN(correlation, 2)))
    }

    cross.creditScore?.let { creditScore ->
        parts.add(creditScore to 0.20)
        signals.add(sig("credit_quality", "Credit quality", creditScore))
    }

    if (stress > 50 && breadth200 != null && breadth200 < 40.0) {
        parts.add(-50 to 0.15)
        signals.add(sig("stress_breadth", "Stress + weak breadth", -50))
    }

    val high = breadth.pctNear52wHigh
    val low = breadth.pctNear52wLow
    if (high != null && low != null) {
        val both = high + low
        val score = when {
            both < 8.0 -> -25
            both > 25.0 -> 20
            else -> 0
        }
        if (score != 0) {
            parts.add(score to 0.10)
            signals.add(sig("dispersion", "52w dispersion", score, "ext ${fmt0(both)}%"))
        }
    }

    val confidence = if (parts.isEmpty()) 0 else minOf(4000 + (parts.size * 1000), 8800)
    return PillarResult(
        score = clampI32(weighted(parts) ?: 0, -100, 100),
        confidenceBps = confidence,
        signals = signals,
        stale = false,
    )
}

// ── SPY drawdown ─────────────────────────────────────────────────────────────

fun spyDrawdownFromAthPct(closes: List<Double>): Double? {
    if (closes.isEmpty()) return null
    val ath = closes.max()
    if (ath <= 0.0) return null
    return clamp((ath - closes.last()) / ath * 100.0, 0.0, 100.0)
}

// ── Correlation ──────────────────────────────────────────────────────────────

/** Average pairwise Pearson correlation across [series], in thousandths. */
fun avgPairwiseCorrMilli(series: List<List<Double>>, lookback: Int): Int? {
    if (series.size < 3) return null
    val returns = series.mapNotNull { closes ->
        if (closes.size < lookback + 1) return@mapNotNull null
        val slice = closes.takeLast(lookback + 1)
        simpleReturns(slice).takeIf { it.size >= 30 }
    }
    if (returns.size < 3) return null

    var sum = 0.0
    var count = 0
    for (i in returns.indices) {
        for (j in (i + 1) until returns.size) {
            pearson(returns[i], returns[j])?.let { correlation ->
                sum += correlation
                count += 1
            }
        }
    }
    if (count == 0) return null
    return roundHalfAwayFromZero(sum / count.toDouble() * 1000.0)
}

private fun pearson(xs: List<Double>, ys: List<Double>): Double? {
    val n = minOf(xs.size, ys.size)
    if (n < 30) return null
    val x = xs.takeLast(n)
    val y = ys.takeLast(n)
    val meanX = x.sum() / n.toDouble()
    val meanY = y.sum() / n.toDouble()
    var covariance = 0.0
    var varianceX = 0.0
    var varianceY = 0.0
    for (index in 0 until n) {
        val dx = x[index] - meanX
        val dy = y[index] - meanY
        covariance += dx * dy
        varianceX += dx * dx
        varianceY += dy * dy
    }
    if (varianceX <= 0.0 || varianceY <= 0.0) return null
    return covariance / (sqrt(varianceX) * sqrt(varianceY))
}
