package com.discountscreener.core.plan

import com.discountscreener.core.engine.ValuationPolicy
import com.discountscreener.core.model.ChartRangeSummary
import com.discountscreener.core.model.HistoricalCandle
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

private val CLUSTER_ATR_FRAC: Double
    get() = ValuationPolicy.current.pricePath.clusterAtrFrac
private val MIN_ZONE_ATR_FRAC: Double
    get() = ValuationPolicy.current.pricePath.minZoneAtrFrac
private val MAX_ZONE_ATR_FRAC: Double
    get() = ValuationPolicy.current.pricePath.maxZoneAtrFrac
private val ATR_BAND_MULT: Double
    get() = ValuationPolicy.current.pricePath.atrBandMult
private val NEAR_ZONE_ATR: Double
    get() = ValuationPolicy.current.pricePath.nearZoneAtr
private val IN_ZONE_EPS_ATR: Double
    get() = ValuationPolicy.current.pricePath.inZoneEpsAtr
val MAX_MOTIVES: Int
    get() = ValuationPolicy.current.pricePath.maxMotives
private val SESSIONS_CAP: Int
    get() = ValuationPolicy.current.pricePath.sessionsCap
private val BOOTSTRAP_PATHS: Int
    get() = ValuationPolicy.current.pricePath.bootstrapPaths
private val BOOTSTRAP_MIN_RETURNS: Int
    get() = ValuationPolicy.current.pricePath.bootstrapMinReturns

private data class Anchor(
    val kind: ZoneComponentKind,
    val priceCents: Long,
    val weightBps: Int,
)

object PricePathEngine {
    fun estimate(input: PricePathInput): PricePathEstimate {
        var price = input.marketPriceCents
        if (price <= 0L) return emptyEstimate()
        var atr = input.daily?.atrCents?.takeIf { it > 0 }
            ?: wilderAtrCents(input.candles)
            ?: roundAway(price.toDouble() * 0.015).coerceAtLeast(1L)
        var sr = if (input.candles.size >= 15) {
            findSupportResistance(input.candles, 5)
        } else {
            SupportResistance()
        }
        var anchors = collectAnchors(input, atr, sr)
        var built = buildZone(price, atr, anchors)
        var mid = built.zone?.let { (it.lowCents + it.highCents) / 2 }
        var motives = buildMotives(input, atr, built.zone, sr)
        var timing = estimateTiming(price, mid, atr, input.candles)
        var invalidation = buildInvalidation(price, atr, sr, timing)
        return PricePathEstimate(
            zone = built.zone,
            zoneConfidence = built.confidence,
            zoneComponents = built.components,
            pathRisks = motives.first,
            pathSupports = motives.second,
            adversePriceCents = (price - roundAway(atr.toDouble() * ATR_BAND_MULT * 1.5)).coerceAtLeast(1L),
            baseZoneMidCents = mid,
            timing = timing,
            invalidation = invalidation,
        )
    }

    fun compact(est: PricePathEstimate): CompactPricePath = CompactPricePath(
        zoneLowCents = est.zone?.lowCents,
        zoneHighCents = est.zone?.highCents,
        zoneConfidence = est.zone?.let { est.zoneConfidence },
        pTouch20d = est.timing.pTouch20d,
        expectedSessions = est.timing.expectedSessionsToZone,
        invalidationCents = est.invalidation.priceCents,
        riskCodes = est.pathRisks.map { it.code }.take(MAX_MOTIVES),
        supportCodes = est.pathSupports.map { it.code }.take(MAX_MOTIVES),
        timingMethod = est.timing.method,
    )

    fun dailyFrom(summary: ChartRangeSummary?, candles: List<HistoricalCandle>): PricePathDaily {
        var bbLower = summary?.let { bollingerLowerCents(candles) }
        return PricePathDaily(
            ema50Cents = summary?.ema50Cents,
            ema200Cents = summary?.ema200Cents,
            rsi = summary?.latestWilderRsi,
            bbLowerCents = bbLower,
            high52wCents = summary?.high52wCents,
            low52wCents = summary?.low52wCents,
            atrCents = wilderAtrCents(candles),
        )
    }
}

private fun emptyEstimate(): PricePathEstimate = PricePathEstimate(
    invalidation = PathInvalidation(reason = "insufficient_price"),
)

private fun collectAnchors(
    input: PricePathInput,
    atr: Long,
    sr: SupportResistance,
): List<Anchor> {
    var price = input.marketPriceCents
    var anchors = ArrayList<Anchor>()
    for (s in sr.supportsCents) {
        if (s < price) {
            anchors.add(Anchor(ZoneComponentKind.Support, s, 2_800))
        }
    }
    input.daily?.bbLowerCents?.let { bb ->
        if (bb < price) anchors.add(Anchor(ZoneComponentKind.Bb, bb, 1_600))
    }
    input.daily?.ema50Cents?.let { ema ->
        if (ema < price) anchors.add(Anchor(ZoneComponentKind.Ema, ema, 1_400))
    }
    input.daily?.ema200Cents?.let { ema ->
        if (ema < price) anchors.add(Anchor(ZoneComponentKind.Ema, ema, 1_800))
    }
    var atrLvl = price - atr
    if (atrLvl > 0) {
        anchors.add(Anchor(ZoneComponentKind.AtrBand, atrLvl, 900))
    }
    if (input.streetFairValueCents > 0 && input.streetFairValueCents < price) {
        anchors.add(Anchor(ZoneComponentKind.Intrinsic, input.streetFairValueCents, 2_200))
    }
    input.dcfValueCents?.let { dcf ->
        if (dcf > 0 && dcf < price) {
            anchors.add(Anchor(ZoneComponentKind.Dcf, dcf, 1_800))
        }
    }
    input.analystLowCents?.let { low ->
        if (low > 0 && low < price) {
            anchors.add(Anchor(ZoneComponentKind.AnalystLow, low, 1_500))
        }
    }
    var hi = input.daily?.high52wCents
    var lo = input.daily?.low52wCents
    if (hi != null && lo != null && hi > lo) {
        var fib618 = lo + roundAway((hi - lo).toDouble() * 0.382)
        if (fib618 < price) {
            anchors.add(Anchor(ZoneComponentKind.Fib, fib618, 1_200))
        }
    }
    return anchors
}

private data class BuiltZone(
    val zone: PriceZone?,
    val confidence: ZoneConfidence,
    val components: List<ZoneComponent>,
)

private fun buildZone(price: Long, atr: Long, anchors: List<Anchor>): BuiltZone {
    if (anchors.isEmpty()) {
        var mid = price - atr
        var low = mid - roundAway(atr.toDouble() * 0.25)
        var high = mid + roundAway(atr.toDouble() * 0.25)
        if (low <= 0L) {
            return BuiltZone(null, ZoneConfidence.Low, emptyList())
        }
        return BuiltZone(
            PriceZone(low, high.coerceAtLeast(low + 1)),
            ZoneConfidence.Low,
            listOf(ZoneComponent(ZoneComponentKind.AtrBand, (low + high) / 2, 900)),
        )
    }
    var clusterRadius = roundAway(atr.toDouble() * CLUSTER_ATR_FRAC).coerceAtLeast(1L)
    var best = emptyList<Anchor>()
    var bestWeight = 0
    for (seed in anchors) {
        var cluster = anchors.filter { abs(it.priceCents - seed.priceCents) <= clusterRadius }
            .sortedBy { it.priceCents }
        var weight = cluster.sumOf { it.weightBps }
        var better = weight > bestWeight ||
            (weight == bestWeight && clusterMid(cluster)?.let { mid ->
                clusterMid(best)?.let { abs(mid - price) < abs(it - price) } ?: true
            } == true)
        if (better) {
            bestWeight = weight
            best = cluster
        }
    }
    if (best.isEmpty()) return BuiltZone(null, ZoneConfidence.Low, emptyList())
    var low = best.minOf { it.priceCents }
    var high = best.maxOf { it.priceCents }
    var minW = roundAway(atr.toDouble() * MIN_ZONE_ATR_FRAC)
    var maxW = roundAway(atr.toDouble() * MAX_ZONE_ATR_FRAC)
    var width = high - low
    if (width < minW) {
        var pad = (minW - width) / 2
        low -= pad
        high += minW - width - pad
    } else if (width > maxW) {
        var mid = (low + high) / 2
        low = mid - maxW / 2
        high = mid + maxW / 2
    }
    if (low <= 0L) low = 1
    if (high <= low) high = low + 1
    var kinds = best.map { it.kind }.distinct()
    var structureLike = kinds.count { kind ->
        kind == ZoneComponentKind.Support ||
            kind == ZoneComponentKind.Fib ||
            kind == ZoneComponentKind.Ema ||
            kind == ZoneComponentKind.Bb ||
            kind == ZoneComponentKind.Intrinsic ||
            kind == ZoneComponentKind.Dcf
    }
    var confidence = when {
        structureLike >= 3 || kinds.size >= 3 -> ZoneConfidence.High
        structureLike >= 2 || kinds.size >= 2 -> ZoneConfidence.Med
        else -> ZoneConfidence.Low
    }
    return BuiltZone(
        PriceZone(low, high),
        confidence,
        best.map { ZoneComponent(it.kind, it.priceCents, it.weightBps) },
    )
}

private fun clusterMid(cluster: List<Anchor>): Long? {
    if (cluster.isEmpty()) return null
    return cluster.sumOf { it.priceCents } / cluster.size
}

private fun buildMotives(
    input: PricePathInput,
    atr: Long,
    zone: PriceZone?,
    sr: SupportResistance,
): Pair<List<PathMotive>, List<PathMotive>> {
    var price = input.marketPriceCents
    var risks = ArrayList<PathMotive>()
    var supports = ArrayList<PathMotive>()
    var atrF = atr.toDouble()
    if (zone != null) {
        var inZone = price >= zone.lowCents - roundAway(atrF * IN_ZONE_EPS_ATR) &&
            price <= zone.highCents + roundAway(atrF * IN_ZONE_EPS_ATR)
        var mid = (zone.lowCents + zone.highCents) / 2
        var dAtr = abs(price - mid).toDouble() / atrF
        if (inZone) {
            supports.add(
                PathMotive(
                    PathMotiveCode.InZone,
                    MotiveSeverity.High,
                    "in zone $${"%.2f".format(mid / 100.0)}",
                ),
            )
        } else if (dAtr <= NEAR_ZONE_ATR) {
            supports.add(
                PathMotive(
                    PathMotiveCode.NearZone,
                    MotiveSeverity.Med,
                    "${"%.1f".format(dAtr)} ATR to zone",
                ),
            )
        } else {
            risks.add(
                PathMotive(
                    PathMotiveCode.FarFromSupport,
                    if (dAtr >= 1.5) MotiveSeverity.High else MotiveSeverity.Med,
                    "-${"%.1f".format(dAtr)} ATR vs zone",
                ),
            )
        }
    }
    input.daily?.rsi?.let { rsi ->
        if (rsi >= 65.0) {
            risks.add(
                PathMotive(
                    PathMotiveCode.RsiRich,
                    if (rsi >= 75.0) MotiveSeverity.High else MotiveSeverity.Med,
                    "RSI ${rsi.toInt()}",
                ),
            )
        } else if (rsi <= 40.0) {
            supports.add(PathMotive(PathMotiveCode.RsiWashed, MotiveSeverity.Med, "RSI ${rsi.toInt()}"))
        }
    }
    input.daily?.ema50Cents?.let { ema50 ->
        var ext = (price - ema50).toDouble() / atrF
        if (ext >= 1.2) {
            risks.add(
                PathMotive(
                    PathMotiveCode.Extension,
                    if (ext >= 2.0) MotiveSeverity.High else MotiveSeverity.Med,
                    "+${"%.1f".format(ext)} ATR vs EMA50",
                ),
            )
        }
    }
    if (input.streetFairValueCents > 0 && price > input.streetFairValueCents) {
        var prem = (price - input.streetFairValueCents).toDouble() / price.toDouble() * 100.0
        risks.add(
            PathMotive(
                PathMotiveCode.AboveValue,
                if (prem >= 10.0) MotiveSeverity.High else MotiveSeverity.Med,
                "+${prem.toInt()}% vs fair",
            ),
        )
    } else if (input.streetFairValueCents > 0 && price < input.streetFairValueCents) {
        var disc = (input.streetFairValueCents - price).toDouble() / price.toDouble() * 100.0
        supports.add(PathMotive(PathMotiveCode.BelowValue, MotiveSeverity.Med, "-${disc.toInt()}% vs fair"))
    } else if (input.gapBps != null && input.gapBps < -800) {
        supports.add(
            PathMotive(
                PathMotiveCode.BelowValue,
                MotiveSeverity.Med,
                "gap ${"%.1f".format(input.gapBps / 100.0)}%",
            ),
        )
    }
    if (input.regimeRisk) {
        risks.add(PathMotive(PathMotiveCode.RegimeRisk, MotiveSeverity.Med, "market context"))
    }
    input.nextEarningsEpoch?.let { ee ->
        var days = (ee - input.nowEpoch).toDouble() / 86_400.0
        if (days in 0.0..14.0) {
            risks.add(
                PathMotive(
                    PathMotiveCode.EarningsSoon,
                    if (days < 5.0) MotiveSeverity.High else MotiveSeverity.Med,
                    "earnings ${max(0.0, days).toInt()}d",
                ),
            )
        }
    }
    var forecast = input.forecastScore
    var tech = input.technicalScore
    if (forecast != null && tech != null) {
        if (forecast >= 20 && tech <= -10) {
            risks.add(
                PathMotive(PathMotiveCode.TrendAgainst, MotiveSeverity.Med, "tech $tech vs forecast $forecast"),
            )
        } else if (forecast <= -25) {
            risks.add(PathMotive(PathMotiveCode.WeakForecast, MotiveSeverity.Med, "forecast $forecast"))
        }
    }
    if (risks.none { it.code == PathMotiveCode.FarFromSupport }) {
        sr.supportsCents.firstOrNull()?.let { s ->
            var d = (price - s).toDouble() / atrF
            if (d >= 1.5) {
                risks.add(
                    PathMotive(
                        PathMotiveCode.FarFromSupport,
                        MotiveSeverity.Med,
                        "-${"%.1f".format(d)} ATR vs support",
                    ),
                )
            }
        }
    }
    return risks.take(MAX_MOTIVES) to supports.take(MAX_MOTIVES)
}

private fun estimateTiming(
    price: Long,
    zoneMid: Long?,
    atr: Long,
    candles: List<HistoricalCandle>,
): PathTiming {
    if (zoneMid == null) return PathTiming()
    if (price <= zoneMid) {
        return PathTiming(0, 95, 98, 99, TimingMethod.Hybrid)
    }
    var distance = abs(price - zoneMid).toDouble()
    var atrF = atr.coerceAtLeast(1L).toDouble()
    var units = distance / atrF
    var atrSessions = ((2.2 * units * units) + 1.0).let { v ->
        roundAway(v).toInt().coerceIn(1, SESSIONS_CAP)
    }
    var returns = logReturns(candles)
    if (returns.size < BOOTSTRAP_MIN_RETURNS) {
        var prior = atrPriorProbs(atrSessions)
        return PathTiming(atrSessions, prior.first, prior.second, prior.third, TimingMethod.AtrDistance)
    }
    var boot = bootstrapTouchProbs(returns, price, zoneMid, BOOTSTRAP_PATHS)
    var prior = atrPriorProbs(atrSessions)
    fun blend(emp: Int, p: Int): Int = ((emp * 65) + (p * 35)) / 100
    var expected = (boot.fourth ?: atrSessions).coerceIn(0, SESSIONS_CAP)
    return PathTiming(
        expected,
        blend(boot.first, prior.first).coerceIn(0, 100),
        blend(boot.second, prior.second).coerceIn(0, 100),
        blend(boot.third, prior.third).coerceIn(0, 100),
        TimingMethod.Hybrid,
    )
}

private fun atrPriorProbs(expected: Int): Triple<Int, Int, Int> {
    var e = expected.coerceAtLeast(1).toDouble()
    fun p(n: Double): Int {
        var touch = (1.0 - exp(-(n / e))) * 100.0
        return roundAway(touch).toInt().coerceIn(2, 98)
    }
    var p5 = p(5.0)
    var p20 = max(p(20.0), p5)
    var p60 = max(p(60.0), p20)
    return Triple(p5, p20, p60)
}

private fun logReturns(candles: List<HistoricalCandle>): List<Double> {
    var out = ArrayList<Double>()
    var i = 1
    while (i < candles.size) {
        var a = candles[i - 1].closeCents.toDouble()
        var b = candles[i].closeCents.toDouble()
        if (a > 0.0 && b > 0.0) out.add(ln(b / a))
        i += 1
    }
    return out
}

private data class BootResult(val first: Int, val second: Int, val third: Int, val fourth: Int?)

private fun bootstrapTouchProbs(
    returns: List<Double>,
    price: Long,
    target: Long,
    paths: Int,
): BootResult {
    var n = returns.size
    var seed = 0xC0FFEEUL xor (price.toULong() * 0x9E3779B97F4A7C15UL)
    fun next(): ULong {
        seed = seed * 6364136223846793005UL + 1UL
        return seed
    }
    var hit5 = 0
    var hit20 = 0
    var hit60 = 0
    var firstHits = ArrayList<Int>()
    repeat(paths) {
        var px = price.toDouble()
        var first: Int? = null
        var day = 1
        while (day <= 60) {
            var idx = (next() % n.toULong()).toInt()
            px *= exp(returns[idx])
            if (px <= target.toDouble()) {
                if (first == null) first = day
                if (day <= 5) hit5 += 1
                if (day <= 20) hit20 += 1
                hit60 += 1
                break
            }
            day += 1
        }
        first?.let { firstHits.add(it) }
    }
    fun pct(h: Int): Int = roundAway((h.toDouble() / paths.toDouble()) * 100.0).toInt()
    firstHits.sort()
    var median = if (firstHits.isEmpty()) null else firstHits[firstHits.size / 2]
    return BootResult(pct(hit5), pct(hit20), pct(hit60), median)
}

private fun buildInvalidation(
    price: Long,
    atr: Long,
    sr: SupportResistance,
    timing: PathTiming,
): PathInvalidation {
    var sessionBudget = timing.expectedSessionsToZone?.let { (it * 2).coerceIn(20, SESSIONS_CAP) } ?: 60
    var r = sr.resistancesCents.firstOrNull()
    var priceLvl = (r ?: (price + 2 * atr)).coerceAtLeast(price + atr)
    return PathInvalidation(priceLvl, sessionBudget, "break_or_time")
}

internal fun wilderAtrCents(candles: List<HistoricalCandle>, period: Int = 14): Long? {
    if (candles.size < period + 1) return null
    var trueRange = DoubleArray(candles.size)
    var i = 1
    while (i < candles.size) {
        var high = candles[i].highCents.toDouble()
        var low = candles[i].lowCents.toDouble()
        var prevClose = candles[i - 1].closeCents.toDouble()
        trueRange[i] = max(high - low, max(abs(high - prevClose), abs(low - prevClose)))
        i += 1
    }
    var atr = (1..period).sumOf { trueRange[it] } / period
    i = period + 1
    while (i < candles.size) {
        atr = (atr * (period - 1) + trueRange[i]) / period
        i += 1
    }
    if (atr <= 0.0) return null
    return roundAway(atr).coerceAtLeast(1L)
}

internal fun bollingerLowerCents(candles: List<HistoricalCandle>, period: Int = 20, k: Double = 2.0): Long? {
    if (candles.size < period) return null
    var window = candles.takeLast(period).map { it.closeCents.toDouble() }
    var mean = window.average()
    var variance = window.map { v -> (v - mean) * (v - mean) }.average()
    var lower = mean - k * sqrt(variance)
    return roundAway(lower)
}

internal fun roundAway(value: Double): Long {
    if (value.isNaN() || value.isInfinite()) return 0L
    return if (value >= 0.0) kotlin.math.floor(value + 0.5).toLong()
    else kotlin.math.ceil(value - 0.5).toLong()
}
