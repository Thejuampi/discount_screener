package com.discountscreener.core.plan

import com.discountscreener.core.engine.ValuationDecisionPolicy
import com.discountscreener.core.engine.checkedUpsideBps
import com.discountscreener.core.model.AnchorRelation
import com.discountscreener.core.model.BusinessClass
import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.ValuationAnchor
import com.discountscreener.core.model.ValuationAnchorSource
import com.discountscreener.core.model.ValuationAvailability
import com.discountscreener.core.model.ValuationConfidence
import com.discountscreener.core.model.ValuationModel

object LeftoverSignalEngine {
    const val STREET_DOOR_BPS = 500
    const val STRETCH_PRIMARY_MAX = 1.0
    const val STRETCH_OUT = 2.0
    const val RSI_HOT = 55.0
    const val NOW_CAP = 120
    const val LATER_CAP = 80

    fun evaluate(input: DipRowInput): DipSetup {
        var tape = DipSignalEngine.measureTape(input.candles)
        var horizon = DipSignalEngine.measureMacd(input.horizonCandles)
        return classify(input, tape, horizon)
    }

    fun classify(input: DipRowInput, tape: DipTape?, horizon: MacdTape? = null): DipSetup {
        var refuse = ArrayList<String>()
        var tags = ArrayList<String>()
        var reviewGaps = ArrayList<String>()
        if (tape == null) {
            refuse.add("missing_tape")
            refuse.add("rsi_missing")
            refuse.add("macd_unavailable")
        } else {
            if (tape.dipAtrUnits > STRETCH_OUT) refuse.add("dumped")
            if (tape.macdPhase == MacdPhase.Unavailable) refuse.add("macd_unavailable")
        }
        var streetBps = checkedUpsideBps(input.marketPriceCents, input.streetFairValueCents)
        if (streetBps == null) refuse.add("street_missing")
        else if (input.analystCoverageCount != null && input.analystCoverageCount < 1) refuse.add("street_thin")
        else if (streetBps > STREET_DOOR_BPS) refuse.add("leftover_open")
        if (tape?.deathCross == true || input.technicalSignals.any(::isDeathCrossToken)) {
            tags.add("death_cross")
        }
        var valuation = valuationTag(input.dcf, input.streetFairValueCents)
        var rsiFade = tape != null && tape.rsi > RSI_HOT && tape.rsiSlope < 0.0
        var macdFade = tape != null && tape.histogram >= 0.0 && tape.histSlope < 0.0
        var fade = rsiFade || macdFade
        var nearHigh = tape != null && tape.dipAtrUnits <= STRETCH_PRIMARY_MAX
        if (!fade) reviewGaps.add("no_fade")
        if (tape != null && !nearHigh) reviewGaps.add("not_near_high")
        var door = refuse.isEmpty()
        var lane = when {
            door && fade && nearHigh -> DipLane.Now
            door -> DipLane.Almost
            else -> DipLane.Out
        }
        if (lane == DipLane.Now) reviewGaps.clear()
        var yearMacd = tape?.let { MacdHorizonScore.fromTape(it) }
        var horizonScore = MacdHorizonScore.score(yearMacd, horizon, MacdHorizonSense.LeftoverFade)
        var headline = LeftoverCopy.headline(lane, streetBps, LeftoverCopy.reviewGap(reviewGaps))
        var evidence = buildEvidence(
            input.fundamentalsScore,
            tape,
            streetBps,
            rsiFade,
            macdFade,
            valuation,
            tags,
            horizonScore,
        )
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
        universeName: String,
        nowCap: Int = NOW_CAP,
        laterCap: Int = LATER_CAP,
    ): PlanBoard {
        var ordered = setups.sortedWith(LEFTOVER_ORDER)
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

    private val LEFTOVER_ORDER: Comparator<DipSetup> =
        compareBy<DipSetup> { it.streetUpsideBps ?: Int.MAX_VALUE }
            .thenBy { it.dipAtrUnits ?: Double.POSITIVE_INFINITY }
            .thenByDescending { it.macdHorizonScore }
            .thenBy { it.symbol }

    private fun isDeathCrossToken(token: String): Boolean {
        var t = token.trim()
        return t == DipSignalEngine.DEATH_CROSS ||
            t == "50/200" ||
            t.equals("E50/E200", ignoreCase = true) ||
            t.contains("50/200-")
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
        f: Int?,
        tape: DipTape?,
        streetBps: Int?,
        rsiFade: Boolean,
        macdFade: Boolean,
        valuation: ValuationTag,
        tags: List<String>,
        horizonScore: Int,
    ): List<String> {
        var lines = ArrayList<String>()
        lines.add(LeftoverCopy.fLine(f))
        lines.add(LeftoverCopy.stretchLine(tape?.dipAtrUnits))
        lines.add(LeftoverCopy.rsiLine(tape?.rsi, rsiFade))
        lines.add(LeftoverCopy.macdLine(macdFade, tape?.macdPhase ?: MacdPhase.Unavailable))
        LeftoverCopy.horizonLine(horizonScore)?.let { lines.add(it) }
        if (tags.contains("death_cross")) lines.add("Death cross tagged. Still in.")
        lines.add(LeftoverCopy.streetLine(streetBps))
        if (valuation.label != null) {
            lines.add(
                LeftoverCopy.valuationLine(
                    valuation.label,
                    valuation.relation.name,
                    valuation.quality?.name?.lowercase(),
                ),
            )
        }
        return lines.take(7)
    }
}
