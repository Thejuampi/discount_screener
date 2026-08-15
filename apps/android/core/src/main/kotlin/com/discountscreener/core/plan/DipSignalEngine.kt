package com.discountscreener.core.plan

import com.discountscreener.core.engine.ChartAnalysis
import com.discountscreener.core.engine.ValuationDecisionPolicy
import com.discountscreener.core.engine.checkedUpsideBps
import com.discountscreener.core.model.AnchorRelation
import com.discountscreener.core.model.BusinessClass
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.ValuationAnchor
import com.discountscreener.core.model.ValuationAnchorSource
import com.discountscreener.core.model.ValuationAvailability
import com.discountscreener.core.model.ValuationConfidence
import com.discountscreener.core.model.ValuationModel
import kotlin.math.abs

object DipSignalEngine {
    const val DIP_ATR_MIN = 1.0
    const val ATR_PERIOD = 14
    const val DIP_RANGE = 20
    const val MACD_N = 3
    const val IMMINENT_HIST_ATR = 0.25
    const val RSI_NOW_LOW = 25.0
    const val RSI_NOW_HIGH = 45.0
    const val RSI_HOT = 55.0
    const val STREET_NOW_BPS = 2_000
    const val STREET_ALMOST_BPS = 1_500
    const val F_FLOOR = 0
    const val NOW_CAP = 120
    const val LATER_CAP = 80
    const val UNIVERSE_OPPORTUNITIES = "opportunities"
    const val DEATH_CROSS = "50/200-"

    fun evaluate(input: DipRowInput): DipSetup {
        var tape = measureTape(input.candles)
        var horizon = measureMacd(input.horizonCandles)
        return classify(input, tape, horizon)
    }

    fun measureTape(candles: List<HistoricalCandle>): DipTape? {
        if (candles.size < DIP_RANGE) return null
        var atr = wilderAtrCents(candles, ATR_PERIOD) ?: return null
        var window = candles.takeLast(DIP_RANGE)
        var high20 = window.maxOf { it.highCents }
        var last = candles.last().closeCents
        if (last <= 0L || high20 <= 0L) return null
        var drawdown = (high20 - last).toDouble()
        var dipUnits = drawdown / atr.toDouble()
        var closes = candles.map { it.closeCents.toDouble() }
        var rsi = ChartAnalysis.rsiAnalysis(candles) ?: return null
        var rsiLevel = rsi.latestWilderRsi ?: return null
        var rsiSlope = rsi.latestSlope ?: return null
        var rsiAccel = rsi.latestAcceleration ?: return null
        var histSeries = ChartAnalysis.macdSeries(closes).histogram
        if (histSeries.size <= MACD_N * 2) return null
        var hist = histSeries.last()
        var histSlope = lookbackDiff(histSeries, MACD_N) ?: return null
        var histAccel = lookbackAccel(histSeries, MACD_N) ?: return null
        var phase = macdPhase(hist, histSlope, histAccel, atr)
        var summary = ChartAnalysis.buildSummary(ChartRange.Year, candles, candles.last().epochSeconds)
        var deathCross = summary.ema50Cents != null &&
            summary.ema200Cents != null &&
            summary.ema50Cents < summary.ema200Cents
        return DipTape(
            atrCents = atr,
            high20dCents = high20,
            lastCloseCents = last,
            dipAtrUnits = dipUnits,
            rsi = rsiLevel,
            rsiSlope = rsiSlope,
            rsiAccel = rsiAccel,
            histogram = hist,
            histSlope = histSlope,
            histAccel = histAccel,
            macdPhase = phase,
            deathCross = deathCross,
        )
    }

    fun measureMacd(candles: List<HistoricalCandle>): MacdTape? {
        if (candles.size < DIP_RANGE) return null
        var atr = wilderAtrCents(candles, ATR_PERIOD) ?: return null
        var closes = candles.map { it.closeCents.toDouble() }
        var histSeries = ChartAnalysis.macdSeries(closes).histogram
        if (histSeries.size <= MACD_N * 2) return null
        var hist = histSeries.last()
        var histSlope = lookbackDiff(histSeries, MACD_N) ?: return null
        var histAccel = lookbackAccel(histSeries, MACD_N) ?: return null
        var phase = macdPhase(hist, histSlope, histAccel, atr)
        return MacdTape(
            histogram = hist,
            histSlope = histSlope,
            histAccel = histAccel,
            macdPhase = phase,
        )
    }

    fun classify(input: DipRowInput, tape: DipTape?, horizon: MacdTape? = null): DipSetup {
        var refuse = ArrayList<String>()
        var tags = ArrayList<String>()
        var almostGaps = ArrayList<String>()
        if (input.fundamentalsScore == null) refuse.add("missing_f")
        else if (input.fundamentalsScore < F_FLOOR) refuse.add("weak_f")
        if (tape == null) refuse.add("missing_tape")
        if (tape != null && tape.dipAtrUnits < DIP_ATR_MIN) refuse.add("shallow_dip")
        if (tape == null) {
            refuse.add("rsi_missing")
            refuse.add("macd_unavailable")
        } else {
            if (tape.rsi > RSI_HOT) refuse.add("rsi_hot")
            if (isKnife(tape)) refuse.add("knife")
            if (tape.macdPhase == MacdPhase.Unavailable) refuse.add("macd_unavailable")
            if (tape.histogram > 0.0) {
                tags.add("flipped")
                almostGaps.add("macd_flipped")
            } else if (tape.macdPhase == MacdPhase.Distant) {
                almostGaps.add("macd_distant")
            }
            var rsiNow = tape.rsi in RSI_NOW_LOW..RSI_NOW_HIGH
            if (!rsiNow) almostGaps.add("rsi_band")
            if (tape.rsiSlope <= 0.0) almostGaps.add("rsi_not_easing")
        }
        var streetBps = checkedUpsideBps(input.marketPriceCents, input.streetFairValueCents)
        if (streetBps == null) refuse.add("street_missing")
        else if (input.analystCoverageCount != null && input.analystCoverageCount < 1) refuse.add("street_thin")
        else if (streetBps < STREET_ALMOST_BPS) refuse.add("street_low")
        else if (streetBps < STREET_NOW_BPS) almostGaps.add("street_almost")
        if (tape?.deathCross == true || input.technicalSignals.any(::isDeathCrossToken)) {
            tags.add("death_cross")
        }
        var valuation = valuationTag(input.dcf, input.streetFairValueCents)
        var hardPass = refuse.isEmpty()
        var nowMacd = tape != null && tape.histogram <= 0.0 &&
            (tape.macdPhase == MacdPhase.Imminent || tape.macdPhase == MacdPhase.Turning)
        var nowRsi = tape != null &&
            tape.rsi in RSI_NOW_LOW..RSI_NOW_HIGH &&
            tape.rsiSlope > 0.0
        var nowStreet = streetBps != null && streetBps >= STREET_NOW_BPS &&
            (input.analystCoverageCount == null || input.analystCoverageCount >= 1)
        var lane = when {
            hardPass && nowMacd && nowRsi && nowStreet -> DipLane.Now
            hardPass -> DipLane.Almost
            else -> DipLane.Out
        }
        if (lane == DipLane.Now) almostGaps.clear()
        var yearMacd = tape?.let { MacdHorizonScore.fromTape(it) }
        var horizonScore = MacdHorizonScore.score(yearMacd, horizon, MacdHorizonSense.DipTurn)
        var headline = DipCopy.headline(lane, streetBps, DipCopy.almostGap(almostGaps))
        var evidence = buildEvidence(tape, valuation, tags, horizonScore)
        return DipSetup(
            symbol = input.symbol,
            companyName = input.companyName,
            lane = lane,
            refuseReasons = refuse,
            tags = tags,
            headline = headline,
            evidence = evidence,
            dipAtrUnits = tape?.dipAtrUnits,
            rsi = tape?.rsi,
            macdPhase = tape?.macdPhase ?: MacdPhase.Unavailable,
            macdHorizonScore = horizonScore,
            streetUpsideBps = streetBps,
            fundamentalsScore = input.fundamentalsScore,
            valuationRelation = valuation.relation,
            modelQuality = valuation.quality,
            modelLabel = valuation.label,
            marketPriceCents = input.marketPriceCents,
            spark = input.candles.takeLast(30).map { it.closeCents },
        )
    }

    fun rank(
        setups: List<DipSetup>,
        universeName: String = UNIVERSE_OPPORTUNITIES,
        nowCap: Int = NOW_CAP,
        laterCap: Int = LATER_CAP,
    ): PlanBoard {
        var ordered = setups.sortedWith(DIP_ORDER)
        var now = ordered.filter { it.lane == DipLane.Now }.take(nowCap)
        var almost = ordered.filter { it.lane == DipLane.Almost }
        var later = almost.take(laterCap)
        return PlanBoard(
            universeName = universeName,
            scanned = setups.size,
            nowCount = setups.count { it.lane == DipLane.Now },
            almostCount = almost.size,
            refuseCount = setups.count { it.lane == DipLane.Out },
            now = now,
            later = later,
            offRadarAlmost = (almost.size - later.size).coerceAtLeast(0),
        )
    }

    private val DIP_ORDER: Comparator<DipSetup> =
        compareBy<DipSetup> { phaseRank(it.macdPhase) }
            .thenByDescending { it.macdHorizonScore }
            .thenByDescending { it.streetUpsideBps ?: Int.MIN_VALUE }
            .thenByDescending { it.dipAtrUnits ?: Double.NEGATIVE_INFINITY }
            .thenByDescending { it.fundamentalsScore ?: Int.MIN_VALUE }
            .thenBy { it.symbol }

    private fun phaseRank(phase: MacdPhase): Int = when (phase) {
        MacdPhase.Imminent -> 0
        MacdPhase.Turning -> 1
        MacdPhase.Flipped -> 2
        MacdPhase.Distant -> 3
        MacdPhase.Unavailable -> 4
    }

    private fun isDeathCrossToken(token: String): Boolean {
        var t = token.trim()
        return t == DEATH_CROSS ||
            t == "50/200" ||
            t.equals("E50/E200", ignoreCase = true) ||
            t.contains("50/200-")
    }

    private fun isKnife(tape: DipTape): Boolean =
        tape.rsiSlope < 0.0 && tape.rsiAccel < 0.0 && tape.histSlope < 0.0 && tape.histAccel < 0.0

    private fun macdPhase(hist: Double, slope: Double, accel: Double, atrCents: Long): MacdPhase {
        if (atrCents <= 0L) return MacdPhase.Unavailable
        if (hist > 0.0) return MacdPhase.Flipped
        if (abs(hist) / atrCents.toDouble() < IMMINENT_HIST_ATR) return MacdPhase.Imminent
        if (hist <= 0.0 && slope > 0.0 && accel > 0.0) return MacdPhase.Turning
        return MacdPhase.Distant
    }

    internal fun lookbackDiff(series: List<Double>, n: Int): Double? {
        if (series.size <= n) return null
        return series.last() - series[series.size - 1 - n]
    }

    internal fun lookbackAccel(series: List<Double>, n: Int): Double? {
        if (series.size <= n * 2) return null
        var slope0 = series.last() - series[series.size - 1 - n]
        var slopeN = series[series.size - 1 - n] - series[series.size - 1 - 2 * n]
        return slope0 - slopeN
    }

    private data class ValuationTag(
        val relation: AnchorRelation,
        val quality: DipModelQuality?,
        val label: String?,
    )

    private fun valuationTag(analysis: DcfAnalysis?, streetCents: Long): ValuationTag {
        var modelCents = eligibleDcfAnchorCents(analysis)
        var modelAnchor = modelCents?.let { cents ->
            ValuationAnchor(
                source = ValuationAnchorSource.Model,
                valueMinorUnits = cents,
                currencyCode = "USD",
                minorUnitScale = 2,
                availability = ValuationAvailability.Available,
                confidence = modelConfidence(analysis),
            )
        }
        var streetAnchor = if (streetCents > 0L) {
            ValuationAnchor(
                source = ValuationAnchorSource.Yahoo,
                valueMinorUnits = streetCents,
                currencyCode = "USD",
                minorUnitScale = 2,
                availability = ValuationAvailability.Available,
                confidence = ValuationConfidence.Solid,
            )
        } else {
            null
        }
        var anchors = listOfNotNull(modelAnchor, streetAnchor)
        var decision = ValuationDecisionPolicy.decide(anchors)
        var label = modelLabel(analysis, modelCents)
        var quality = when {
            label == null -> null
            analysis == null -> null
            analysis.pointEstimateUnreliable -> DipModelQuality.Soft
            !orderedScenarios(analysis) -> DipModelQuality.Soft
            wideScenarios(analysis) -> DipModelQuality.Soft
            else -> DipModelQuality.Solid
        }
        return ValuationTag(decision.relation, quality, label)
    }

    private fun modelConfidence(analysis: DcfAnalysis?): ValuationConfidence {
        if (analysis == null) return ValuationConfidence.Unknown
        if (analysis.pointEstimateUnreliable || !orderedScenarios(analysis) || wideScenarios(analysis)) {
            return ValuationConfidence.Soft
        }
        return ValuationConfidence.Solid
    }

    private fun orderedScenarios(analysis: DcfAnalysis): Boolean =
        analysis.bearIntrinsicValueCents > 0L &&
            analysis.baseIntrinsicValueCents > 0L &&
            analysis.bullIntrinsicValueCents > 0L &&
            analysis.bearIntrinsicValueCents <= analysis.baseIntrinsicValueCents &&
            analysis.baseIntrinsicValueCents <= analysis.bullIntrinsicValueCents

    private fun wideScenarios(analysis: DcfAnalysis): Boolean {
        var width = ValuationDecisionPolicy.scenarioWidthBps(
            analysis.bearIntrinsicValueCents,
            analysis.baseIntrinsicValueCents,
            analysis.bullIntrinsicValueCents,
        ) ?: return true
        return width > ValuationDecisionPolicy.WIDE_SCENARIO_BPS
    }

    private fun modelLabel(analysis: DcfAnalysis?, modelCents: Long?): String? {
        if (analysis == null || modelCents == null) return null
        return when (analysis.model) {
            ValuationModel.ResidualIncomeEquity -> "Residual income"
            ValuationModel.FcffWacc ->
                if (analysis.businessClass == BusinessClass.OperatingNonFinancial) "FCFF DCF" else null
            ValuationModel.None -> null
        }
    }

    private fun buildEvidence(
        tape: DipTape?,
        valuation: ValuationTag,
        tags: List<String>,
        horizonScore: Int,
    ): List<String> {
        var lines = ArrayList<String>()
        lines.add(DipCopy.dipLine(tape?.dipAtrUnits))
        lines.add(DipCopy.rsiLine(tape?.rsi, tape != null && tape.rsiSlope > 0.0))
        lines.add(DipCopy.macdLine(tape?.macdPhase ?: MacdPhase.Unavailable))
        DipCopy.horizonLine(horizonScore)?.let { lines.add(it) }
        if (tags.contains("death_cross")) lines.add("Death cross tagged. Still in.")
        if (valuation.label != null) {
            lines.add(
                DipCopy.valuationLine(
                    valuation.label,
                    valuation.relation.name,
                    valuation.quality?.name?.lowercase(),
                ),
            )
        }
        return lines
    }
}
