package com.discountscreener.core.plan

import com.discountscreener.core.engine.ChartAnalysis
import com.discountscreener.core.engine.ValuationDecisionPolicy
import com.discountscreener.core.engine.ValuationPolicy
import com.discountscreener.core.engine.checkedUpsideBps
import com.discountscreener.core.model.AnchorRelation
import com.discountscreener.core.model.BusinessClass
import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.ValuationAnchor
import com.discountscreener.core.model.ValuationAnchorSource
import com.discountscreener.core.model.ValuationAvailability
import com.discountscreener.core.model.ValuationConfidence
import com.discountscreener.core.model.ValuationModel

object CrossSignalEngine {
    val FLIPPED_BARS_MAX: Int
        get() = ValuationPolicy.current.cross.flippedBarsMax
    val STREET_NOW_BPS: Int
        get() = ValuationPolicy.current.cross.streetNowBps
    val STREET_ALMOST_BPS: Int
        get() = ValuationPolicy.current.cross.streetAlmostBps
    val F_FLOOR: Int
        get() = ValuationPolicy.current.cross.fFloor
    val RSI_HOT: Double
        get() = ValuationPolicy.current.cross.rsiHot
    val NOW_CAP: Int
        get() = ValuationPolicy.current.cross.nowCap
    val LATER_CAP: Int
        get() = ValuationPolicy.current.cross.laterCap

    fun barsSinceGoldenCross(histogram: List<Double>): Int? {
        if (histogram.isEmpty()) return null
        if (histogram.last() <= 0.0) return null
        var i = histogram.lastIndex
        while (i >= 0 && histogram[i] > 0.0) i--
        if (i < 0) return histogram.size
        return histogram.lastIndex - i - 1
    }

    fun evaluate(input: DipRowInput): DipSetup {
        var tape = DipSignalEngine.measureTape(input.candles)
        var horizon = DipSignalEngine.measureMacd(input.horizonCandles)
        var bars = if (input.candles.isEmpty()) {
            null
        } else {
            var hist = ChartAnalysis.macdSeries(input.candles.map { it.closeCents.toDouble() }).histogram
            barsSinceGoldenCross(hist)
        }
        return classify(input, tape, bars, horizon)
    }

    fun classify(
        input: DipRowInput,
        tape: DipTape?,
        barsSinceCross: Int?,
        horizon: MacdTape? = null,
    ): DipSetup {
        var refuse = ArrayList<String>()
        var tags = ArrayList<String>()
        var almostGaps = ArrayList<String>()
        if (input.fundamentalsScore == null) refuse.add("missing_f")
        else if (input.fundamentalsScore < F_FLOOR) refuse.add("weak_f")
        if (tape == null) {
            refuse.add("missing_tape")
            refuse.add("rsi_missing")
            refuse.add("macd_unavailable")
        } else {
            if (tape.rsi > RSI_HOT) refuse.add("rsi_hot")
            if (isKnife(tape)) refuse.add("knife")
            if (tape.macdPhase == MacdPhase.Unavailable) refuse.add("macd_unavailable")
            if (tape.histogram <= 0.0) {
                refuse.add("macd_not_flipped")
            } else {
                tags.add("flipped")
                if (tape.histSlope <= 0.0) refuse.add("macd_fading")
                if (barsSinceCross == null || barsSinceCross > FLIPPED_BARS_MAX) {
                    refuse.add("macd_stale")
                }
            }
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
        var fresh = barsSinceCross != null && barsSinceCross in 0..FLIPPED_BARS_MAX
        var expanding = tape != null && tape.histogram > 0.0 && tape.histSlope > 0.0
        var nowStreet = streetBps != null && streetBps >= STREET_NOW_BPS &&
            (input.analystCoverageCount == null || input.analystCoverageCount >= 1)
        var lane = when {
            hardPass && fresh && expanding && nowStreet -> DipLane.Now
            hardPass && fresh && expanding -> DipLane.Almost
            else -> DipLane.Out
        }
        if (lane == DipLane.Now) almostGaps.clear()
        var yearMacd = tape?.let { MacdHorizonScore.fromTape(it) }
        var horizonScore = MacdHorizonScore.score(yearMacd, horizon, MacdHorizonSense.CrossFresh)
        var headline = CrossCopy.headline(lane, streetBps, CrossCopy.almostGap(almostGaps))
        var evidence = buildEvidence(tape, barsSinceCross, valuation, tags, horizonScore)
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
            barsSinceCross = barsSinceCross,
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
        universeName: String = DipSignalEngine.UNIVERSE_OPPORTUNITIES,
        nowCap: Int = NOW_CAP,
        laterCap: Int = LATER_CAP,
    ): PlanBoard {
        var ordered = setups.sortedWith(CROSS_ORDER)
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

    private val CROSS_ORDER: Comparator<DipSetup> =
        compareBy<DipSetup> { it.barsSinceCross ?: Int.MAX_VALUE }
            .thenByDescending { it.macdHorizonScore }
            .thenByDescending { it.streetUpsideBps ?: Int.MIN_VALUE }
            .thenByDescending { it.fundamentalsScore ?: Int.MIN_VALUE }
            .thenBy { it.symbol }

    private fun isDeathCrossToken(token: String): Boolean {
        var t = token.trim()
        return t == DipSignalEngine.DEATH_CROSS ||
            t == "50/200" ||
            t.equals("E50/E200", ignoreCase = true) ||
            t.contains("50/200-")
    }

    private fun isKnife(tape: DipTape): Boolean =
        tape.rsiSlope < 0.0 && tape.rsiAccel < 0.0 && tape.histSlope < 0.0 && tape.histAccel < 0.0

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
            ValuationModel.ComponentSum -> "Factory plus lender"
            ValuationModel.FcffWacc ->
                if (analysis.businessClass == BusinessClass.OperatingNonFinancial) "FCFF DCF" else null
            ValuationModel.None -> null
        }
    }

    private fun buildEvidence(
        tape: DipTape?,
        barsSinceCross: Int?,
        valuation: ValuationTag,
        tags: List<String>,
        horizonScore: Int,
    ): List<String> {
        var lines = ArrayList<String>()
        lines.add(CrossCopy.macdLine(barsSinceCross, tape?.macdPhase ?: MacdPhase.Unavailable))
        lines.add(CrossCopy.rsiLine(tape?.rsi))
        CrossCopy.horizonLine(horizonScore)?.let { lines.add(it) }
        if (tags.contains("death_cross")) lines.add("Death cross tagged. Still in.")
        if (valuation.label != null) {
            lines.add(
                CrossCopy.valuationLine(
                    valuation.label,
                    valuation.relation.name,
                    valuation.quality?.name?.lowercase(),
                ),
            )
        }
        return lines
    }
}
