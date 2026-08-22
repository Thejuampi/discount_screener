package com.discountscreener.android.presentation.dashboard

import com.discountscreener.core.model.CorrelationRiskBand
import com.discountscreener.core.model.EvidenceStrengthBand
import com.discountscreener.core.model.ExpectedValueRangeBand
import com.discountscreener.core.model.ExpectedValueRangeSource
import com.discountscreener.core.model.QuantLensEvidenceStrength
import com.discountscreener.core.model.QuantLensExpectedValueRange
import com.discountscreener.core.model.QuantLensFreshnessQualifier
import com.discountscreener.core.model.QuantLensHorizon
import com.discountscreener.core.model.QuantLensHorizonBaseline
import com.discountscreener.core.model.QuantLensLensId
import com.discountscreener.core.model.QuantLensLensRowState
import com.discountscreener.core.model.QuantLensPrimaryStatus
import com.discountscreener.core.model.QuantLensReport
import com.discountscreener.core.model.QuantLensRowLabel
import com.discountscreener.core.model.QuantLensRowSummary
import com.discountscreener.core.model.QuantLensTrendReliability
import com.discountscreener.core.model.SimilarSetupsBand
import com.discountscreener.core.model.TrendReliabilityBand
import kotlin.math.abs

data class EvRangeRailModel(
    val lowUpsideBps: Int,
    val weightedUpsideBps: Int,
    val highUpsideBps: Int,
    val crossesZero: Boolean,
    val isStale: Boolean,
)

/**
 * How a signal *reads* — one axis, from strongly favourable to strongly adverse.
 *
 * This replaces an earlier severity that was derived from [QuantLensPrimaryStatus] alone, which
 * meant it answered "did the lens compute?" while being painted as though it answered "is this
 * good?". A choppy trend and a clear uptrend both came back `Available`, so both rendered
 * favourable. Direction has to come from the measurement itself — the sign of the trend's movement,
 * the split between supporting and conflicting evidence, the sign of the expected upside.
 *
 * [Unknown] is not a sixth direction. It is the honest answer when the lens could not read: it
 * carries no marker and no colour, so a signal that was never measured cannot be mistaken for one
 * measured as neutral.
 */
enum class QuantLensQualifier {
    StrongPositive,
    Positive,
    Neutral,
    Negative,
    StrongNegative,
    Unknown,
}

data class QuantLensChipUi(
    val lensId: QuantLensLensId?,
    val label: String,
    val qualifier: QuantLensQualifier,
)

data class QuantLensSectionUi(
    val lensId: QuantLensLensId,
    val title: String,
    val chip: QuantLensChipUi,
    val primaryLine: String,
    val rows: List<Pair<String, String>> = emptyList(),
    val footerChips: List<String> = emptyList(),
    val evRailModel: EvRangeRailModel? = null,
)

data class QuantLensUiState(
    val headerChips: List<QuantLensChipUi>,
    val sections: List<QuantLensSectionUi>,
)

internal fun mapQuantLensReport(report: QuantLensReport?, marketPriceCents: Long? = null): QuantLensUiState? {
    report ?: return null
    val sections = listOf(
        evSection(report, marketPriceCents),
        evidenceSection(report),

        correlationSection(report),
        trendSection(report),
        horizonContextSection(report),
        similarSection(report),
    )
    return QuantLensUiState(
        headerChips = sections.map { it.chip },
        sections = sections,
    )
}

internal fun mapRowQuantLensSummary(summary: QuantLensRowSummary?, maxChips: Int = 3): List<QuantLensChipUi> {
    summary ?: return listOf(QuantLensChipUi(null, "Lens loading", QuantLensQualifier.Unknown))
    val byLens = summary.lensStates.associateBy { it.lensId }
    val eligible = buildList {
        byLens[QuantLensLensId.EvidenceStrength]?.let { add(it) }
        byLens[QuantLensLensId.ExpectedValueRange]?.takeIf {
            it.primaryStatus == QuantLensPrimaryStatus.Available ||
                it.primaryStatus == QuantLensPrimaryStatus.Provisional ||
                it.primaryStatus == QuantLensPrimaryStatus.Disputed
        }?.let { add(it) }
        byLens[QuantLensLensId.CorrelationRisk]?.takeIf {
            it.primaryStatus == QuantLensPrimaryStatus.Available &&
                (it.label == QuantLensRowLabel.CorrElevated || it.label == QuantLensRowLabel.CorrHigh)
        }?.let { add(it) }
        byLens[QuantLensLensId.TrendReliability]?.takeIf {
            it.primaryStatus == QuantLensPrimaryStatus.Available
        }?.let { add(it) }
        byLens[QuantLensLensId.SimilarSetups]?.takeIf {
            it.primaryStatus == QuantLensPrimaryStatus.Available
        }?.let { add(it) }
    }
    return eligible.take(maxChips).map(::rowChip)
}

private fun evidenceSection(report: QuantLensReport): QuantLensSectionUi {
    val section = report.evidenceStrength
    val label = when (section.band) {
        EvidenceStrengthBand.Strong -> "Strong signals"
        EvidenceStrengthBand.Provisional -> "Provisional signals"
        EvidenceStrengthBand.Mixed -> "Mixed signals"
        EvidenceStrengthBand.Sparse -> "Few signals"
        EvidenceStrengthBand.Unavailable -> "No signals"
    }
    return QuantLensSectionUi(
        lensId = QuantLensLensId.EvidenceStrength,
        title = "Signal quality",
        chip = QuantLensChipUi(QuantLensLensId.EvidenceStrength, label, evidenceQualifier(section)),
        primaryLine = "${section.supportCount} bullish · ${section.conflictCount} bearish · ${section.neutralCount} neutral",
        footerChips = listOf("Based on today's data"),
    )
}

private fun evSection(report: QuantLensReport, marketPriceCents: Long?): QuantLensSectionUi {
    val section = report.expectedValueRange
    val price = marketPriceCents?.takeIf { it > 0L }
    val lowBps = price?.let { section.lowFairValueCents?.let { low -> upsideBps(low, it) } }
    val weightedBps = section.weightedUpsideBps
    val highBps = price?.let { section.highFairValueCents?.let { high -> upsideBps(high, it) } }
    val isStale = section.freshnessQualifier == QuantLensFreshnessQualifier.Stale ||
        section.freshnessQualifier == QuantLensFreshnessQualifier.Restored
    val crossesZero = lowBps != null && highBps != null && lowBps < 0 && highBps > 0
    val label = when (section.band) {
        ExpectedValueRangeBand.ScenarioWeighted ->
            if (crossesZero) "Mixed up/down"
            else if (lowBps != null && highBps != null) "${compactPct(lowBps)}..${compactPct(highBps)}% upside"
            else "Upside estimate"
        ExpectedValueRangeBand.Tension -> "Tension"
        ExpectedValueRangeBand.Disputed -> "Disputed"
        ExpectedValueRangeBand.ReferenceOnly,
        ExpectedValueRangeBand.Sparse,
        -> "Estimate limited"
        ExpectedValueRangeBand.Unavailable -> "No estimate"
    }
    val anchorCount = listOfNotNull(section.lowFairValueCents, section.weightedFairValueCents, section.highFairValueCents).size
    val primaryLine = when (section.band) {
        ExpectedValueRangeBand.ScenarioWeighted ->
            if (crossesZero) "Price could go up or down · ${signedPercent(lowBps ?: 0)} to ${signedPercent(highBps ?: 0)}"
            else if (lowBps != null && weightedBps != null && highBps != null)
                "Estimated upside ${signedPercent(lowBps)} to ${signedPercent(highBps)} · Best guess ${signedPercent(weightedBps)}"
            else weightedBps?.let { "Estimated upside ${signedPercent(it)}" } ?: "Price references only"
        ExpectedValueRangeBand.Tension -> disputedPrimaryLine(section, price, "sources are in tension")
        ExpectedValueRangeBand.Disputed -> disputedPrimaryLine(section, price, "sources disagree")
        ExpectedValueRangeBand.ReferenceOnly ->
            "$anchorCount price reference${if (anchorCount == 1) "" else "s"} · Indicative only"
        ExpectedValueRangeBand.Sparse ->
            "$anchorCount price reference${if (anchorCount == 1) "" else "s"} · Limited data"
        ExpectedValueRangeBand.Unavailable -> "No price estimate available"
    }
    val rows = when (section.band) {
        ExpectedValueRangeBand.ScenarioWeighted -> listOfNotNull(
            lowBps?.let { "Pessimistic" to signedPercent(it) },
            weightedBps?.let { "Expected" to signedPercent(it) },
            highBps?.let { "Optimistic" to signedPercent(it) },
        )
        ExpectedValueRangeBand.Tension,
        ExpectedValueRangeBand.Disputed,
        -> disputedRows(section, price)
        ExpectedValueRangeBand.ReferenceOnly -> listOfNotNull(
            section.lowFairValueCents?.let { "Low" to money(it) },
            section.highFairValueCents?.let { "High" to money(it) },
        )
        else -> emptyList()
    }
    val sourceChip = when (section.source) {
        ExpectedValueRangeSource.Dcf -> "Cashflow model"
        ExpectedValueRangeSource.Analyst -> "Analyst consensus"
        null -> null
    }
    val anchorChip = when (section.band) {
        ExpectedValueRangeBand.ScenarioWeighted -> "3 scenarios"
        ExpectedValueRangeBand.Tension,
        ExpectedValueRangeBand.Disputed,
        -> "No single EV"
        ExpectedValueRangeBand.ReferenceOnly -> "$anchorCount price point${if (anchorCount == 1) "" else "s"}"
        else -> null
    }
    val freshnessChip = when (section.freshnessQualifier) {
        QuantLensFreshnessQualifier.Stale -> freshnessAgo(report.computedAtEpochSeconds)
        QuantLensFreshnessQualifier.Restored -> "Based on saved data"
        QuantLensFreshnessQualifier.ProviderUncertain -> "Source uncertain"
        QuantLensFreshnessQualifier.Fresh, null -> when (section.band) {
            ExpectedValueRangeBand.ScenarioWeighted -> "Up to date"
            ExpectedValueRangeBand.Tension -> "Sources in tension"
            ExpectedValueRangeBand.Disputed -> "Sources disagree"
            else -> null
        }
    }
    val footerChips = listOfNotNull(sourceChip, anchorChip, freshnessChip)
    val evRailModel = if (
        section.band == ExpectedValueRangeBand.ScenarioWeighted &&
        lowBps != null && weightedBps != null && highBps != null &&
        highBps > lowBps
    ) {
        EvRangeRailModel(
            lowUpsideBps = lowBps,
            weightedUpsideBps = weightedBps,
            highUpsideBps = highBps,
            crossesZero = crossesZero,
            isStale = isStale,
        )
    } else null
    return QuantLensSectionUi(
        lensId = QuantLensLensId.ExpectedValueRange,
        title = "Valuation decision",
        chip = QuantLensChipUi(QuantLensLensId.ExpectedValueRange, label, evQualifier(section, crossesZero)),
        primaryLine = primaryLine,
        rows = rows,
        footerChips = footerChips,
        evRailModel = evRailModel,
    )
}

private fun correlationSection(report: QuantLensReport): QuantLensSectionUi {
    val section = report.correlationRisk
    val label = when (section.band) {
        CorrelationRiskBand.Low -> "Moves independently"
        CorrelationRiskBand.Elevated -> "Some overlap"
        CorrelationRiskBand.High -> "Moves together"
        CorrelationRiskBand.Sparse -> "Not enough data"
        CorrelationRiskBand.Unavailable -> "No data"
    }
    return QuantLensSectionUi(
        lensId = QuantLensLensId.CorrelationRisk,
        title = "Market overlap",
        chip = QuantLensChipUi(QuantLensLensId.CorrelationRisk, label, correlationQualifier(section.band)),
        primaryLine = if (section.topPairs.isEmpty()) "Not enough price history" else "How closely it moves with ${section.validPairCount} peers",
        rows = section.topPairs.take(3).map {
            it.symbol to "r=${signedCorrelation(it.correlationBps)}, ${it.overlapCount} days"
        },
        footerChips = listOf("From saved price history"),
    )
}

private fun trendSection(report: QuantLensReport): QuantLensSectionUi {
    val section = report.trendReliability
    val label = when (section.band) {
        TrendReliabilityBand.Reliable -> "Clear trend"
        TrendReliabilityBand.Moderate -> "Moderate trend"
        TrendReliabilityBand.Noisy -> "Choppy"
        TrendReliabilityBand.Flat -> "No trend"
        TrendReliabilityBand.Insufficient -> "Too little data"
        TrendReliabilityBand.Unavailable -> "No data"
    }
    return QuantLensSectionUi(
        lensId = QuantLensLensId.TrendReliability,
        title = "Price trend",
        chip = QuantLensChipUi(QuantLensLensId.TrendReliability, label, trendQualifier(section)),
        primaryLine = section.rSquaredBps?.let { "Trend fits ${decimalBps(it)} of price moves · ${section.sampleCount} data points" }
            ?: "${section.sampleCount} data points",
        rows = listOfNotNull(
            section.movementBps?.let { "Avg move" to signedPercent(it) },
            section.rSquaredBps?.let { "Trend fit" to decimalBps(it) },
        ),
        footerChips = listOf(report.selectedRange.name),
    )
}

private fun similarSection(report: QuantLensReport): QuantLensSectionUi {
    val section = report.similarSetups
    val label = when (section.band) {
        SimilarSetupsBand.Available -> "3 matches found"
        SimilarSetupsBand.Sparse -> "Few matches"
        SimilarSetupsBand.Unavailable -> "No matches"
    }
    return QuantLensSectionUi(
        lensId = QuantLensLensId.SimilarSetups,
        title = "Similar patterns",
        chip = QuantLensChipUi(QuantLensLensId.SimilarSetups, label, availabilityQualifier(section.primaryStatus)),
        primaryLine = if (section.matches.isEmpty()) {
            "${section.qualifyingComparableCount} stocks analyzed, none close enough"
        } else {
            "3 stocks with a similar current setup"
        },
        rows = section.matches.take(3).map {
            it.symbol to "${similarityLabel(it.similarityBps)} · ${it.sharedFeatureCount} shared traits"
        },
        footerChips = listOf("All tracked stocks", "Min 3 shared traits"),
    )
}

private fun horizonContextSection(report: QuantLensReport): QuantLensSectionUi {
    val section = report.horizonContext
    val label = when (section.primaryStatus) {
        QuantLensPrimaryStatus.Available -> "History ready"
        QuantLensPrimaryStatus.Partial -> "Partial history"
        QuantLensPrimaryStatus.Insufficient -> "Little history"
        else -> "No history"
    }
    return QuantLensSectionUi(
        lensId = QuantLensLensId.HorizonContext,
        title = "Typical moves",
        chip = QuantLensChipUi(QuantLensLensId.HorizonContext, label, availabilityQualifier(section.primaryStatus)),
        primaryLine = "How much this stock usually moves",
        rows = section.horizons.map { baseline -> horizonRowWithLabel(baseline) },
        footerChips = listOf("Based on price history", "Not a forecast"),
    )
}

private fun horizonRowWithLabel(baseline: QuantLensHorizonBaseline): Pair<String, String> =
    horizonLabel(baseline.horizon) to horizonRow(baseline)

private fun rowChip(state: QuantLensLensRowState): QuantLensChipUi =
    QuantLensChipUi(
        lensId = state.lensId,
        label = when (state.label) {
            QuantLensRowLabel.EvidenceStrong -> "Strong signals"
            QuantLensRowLabel.EvidenceProvisional -> "Provisional signals"
            QuantLensRowLabel.EvidenceMixed -> "Mixed signals"
            QuantLensRowLabel.EvidenceSparse -> "Few signals"
            QuantLensRowLabel.EvidenceUnavailable -> "No signals"
            QuantLensRowLabel.EvRange -> evRowRange(state)
            QuantLensRowLabel.EvTension -> "Tension · model vs analyst"
            QuantLensRowLabel.EvDisputed -> "Disputed · model vs analyst"
            QuantLensRowLabel.EvSparse -> "Estimate limited"
            QuantLensRowLabel.EvUnavailable -> "No estimate"
            QuantLensRowLabel.CorrLow -> "Moves independently"
            QuantLensRowLabel.CorrElevated -> "Some overlap"
            QuantLensRowLabel.CorrHigh -> "Moves together"
            QuantLensRowLabel.CorrSparse -> "Not enough data"
            QuantLensRowLabel.CorrUnavailable -> "No data"
            QuantLensRowLabel.TrendReliable -> "Clear trend"
            QuantLensRowLabel.TrendModerate -> "Moderate trend"
            QuantLensRowLabel.TrendNoisy -> "Choppy"
            QuantLensRowLabel.TrendFlat -> "No trend"
            QuantLensRowLabel.TrendSparse -> "Too little data"
            QuantLensRowLabel.TrendUnavailable -> "No data"
            QuantLensRowLabel.SimilarAvailable -> "3 matches found"
            QuantLensRowLabel.SimilarSparse -> "Few matches"
            QuantLensRowLabel.SimilarUnavailable -> "No matches"
            null -> "Not available"
        },
        qualifier = rowQualifier(state),
    )

/**
 * The same reading as the detail sections, from a state that carries less of the measurement.
 *
 * [QuantLensLensRowState] keeps a band and a label but not the numbers behind them — no signed trend
 * movement, no support-versus-conflict split. Where direction cannot be derived it is [Neutral], not
 * favourable: colouring a row green because its lens merely *ran* is the defect this replaces, and
 * it would be no less wrong here for being harder to see.
 */
private fun rowQualifier(state: QuantLensLensRowState): QuantLensQualifier = when (state.label) {
    QuantLensRowLabel.EvRange -> rowRangeQualifier(state)
    QuantLensRowLabel.EvTension,
    QuantLensRowLabel.EvDisputed,
    -> QuantLensQualifier.Negative
    QuantLensRowLabel.CorrLow -> QuantLensQualifier.Positive
    QuantLensRowLabel.CorrElevated -> QuantLensQualifier.Negative
    QuantLensRowLabel.CorrHigh -> QuantLensQualifier.StrongNegative
    QuantLensRowLabel.EvidenceStrong,
    QuantLensRowLabel.EvidenceProvisional,
    QuantLensRowLabel.EvidenceMixed,
    QuantLensRowLabel.TrendReliable,
    QuantLensRowLabel.TrendModerate,
    QuantLensRowLabel.TrendNoisy,
    QuantLensRowLabel.TrendFlat,
    QuantLensRowLabel.SimilarAvailable,
    -> QuantLensQualifier.Neutral
    QuantLensRowLabel.EvidenceSparse,
    QuantLensRowLabel.EvidenceUnavailable,
    QuantLensRowLabel.EvSparse,
    QuantLensRowLabel.EvUnavailable,
    QuantLensRowLabel.CorrSparse,
    QuantLensRowLabel.CorrUnavailable,
    QuantLensRowLabel.TrendSparse,
    QuantLensRowLabel.TrendUnavailable,
    QuantLensRowLabel.SimilarSparse,
    QuantLensRowLabel.SimilarUnavailable,
    null,
    -> QuantLensQualifier.Unknown
}

/**
 * Judged from the end of the range nearer zero — the pessimistic bound when both are positive, the
 * optimistic one when both are negative. A midpoint would let one extravagant scenario carry a
 * range whose likely half says something duller.
 */
private fun rowRangeQualifier(state: QuantLensLensRowState): QuantLensQualifier {
    val low = state.evLowUpsideBps ?: return QuantLensQualifier.Neutral
    val high = state.evHighUpsideBps ?: return QuantLensQualifier.Neutral
    if (low < 0 && high > 0) return QuantLensQualifier.Neutral
    return upsideQualifier(if (low >= 0) low else high)
}

private fun evRowRange(state: QuantLensLensRowState): String {
    val low = state.evLowUpsideBps
    val high = state.evHighUpsideBps
    return if (low != null && high != null) "${signedPercent(low)}..${signedPercent(high)} upside" else "Upside estimate"
}

private fun disputedPrimaryLine(
    section: QuantLensExpectedValueRange,
    marketPriceCents: Long?,
    relationLabel: String,
): String {
    val modelBase = section.modelBaseFairValueCents
    val analystBase = section.analystBaseFairValueCents
    if (modelBase == null || analystBase == null) return "Model and analyst $relationLabel · no single estimate"
    val modelUpside = marketPriceCents?.let { upsideBps(modelBase, it) }
    val analystUpside = marketPriceCents?.let { upsideBps(analystBase, it) }
    return "Forecast is the analyst range ${money(analystBase)} (${analystUpside?.let(::signedPercent) ?: "n/a"}). Identity model ${money(modelBase)} (${modelUpside?.let(::signedPercent) ?: "n/a"}) is a check · $relationLabel · no single estimate"
}

private fun disputedRows(
    section: QuantLensExpectedValueRange,
    marketPriceCents: Long?,
): List<Pair<String, String>> {
    val modelLow = section.modelLowFairValueCents
    val modelHigh = section.modelHighFairValueCents
    val analystLow = section.analystLowFairValueCents
    val analystHigh = section.analystHighFairValueCents
    return listOfNotNull(
        section.analystBaseFairValueCents?.let { base ->
            "Analyst base" to "${money(base)} · ${marketPriceCents?.let { upsideBps(base, it)?.let(::signedPercent) } ?: "n/a"}"
        },
        section.modelBaseFairValueCents?.let { base ->
            "Identity model" to "${money(base)} · ${marketPriceCents?.let { upsideBps(base, it)?.let(::signedPercent) } ?: "n/a"}"
        },
        if (analystLow != null && analystHigh != null) {
            "Analyst range" to "${money(analystLow)}–${money(analystHigh)}"
        } else null,
        if (modelLow != null && modelHigh != null) {
            "Identity range" to "${money(modelLow)}–${money(modelHigh)}"
        } else null,
    )
}

private fun horizonLabel(horizon: QuantLensHorizon): String = when (horizon) {
    QuantLensHorizon.FiveMinutes -> "5m"
    QuantLensHorizon.OneDay -> "1D"
    QuantLensHorizon.ThreeMonths -> "3M"
}

private fun horizonRow(baseline: QuantLensHorizonBaseline): String = when (baseline.primaryStatus) {
    QuantLensPrimaryStatus.Available -> {
        val median = baseline.medianAbsoluteMoveBps
        val p25 = baseline.p25AbsoluteMoveBps
        val p75 = baseline.p75AbsoluteMoveBps
        if (median != null && p25 != null && p75 != null) {
            "±${absolutePercent(median)} typical · ${absolutePercent(p25)}–${absolutePercent(p75)} usual range"
        } else {
            "${baseline.sampleCount} samples"
        }
    }
    QuantLensPrimaryStatus.Insufficient -> "Need 10 data points · ${baseline.sourceRange.name} chart"
        QuantLensPrimaryStatus.Unavailable -> "No data · ${baseline.sourceRange.name} chart"
        else -> "${baseline.primaryStatus.name} · ${baseline.sourceRange.name} chart"
}

private fun upsideBps(fairValueCents: Long, marketPriceCents: Long): Int? {
    if (marketPriceCents <= 0L) return null
    return (((fairValueCents - marketPriceCents).toDouble() / marketPriceCents.toDouble()) * 10_000.0).toInt()
}

private fun compactPct(bps: Int): String {
    val sign = if (bps >= 0) "+" else "-"
    return "$sign${abs(bps) / 100}"
}

private fun freshnessAgo(epochSeconds: Long): String {
    val nowSeconds = System.currentTimeMillis() / 1000L
    val delta = nowSeconds - epochSeconds
    return when {
        delta < 3600 -> "saved ${delta / 60}m ago"
        delta < 86400 -> "saved ${delta / 3600}h ago"
        else -> "saved ${delta / 86400}d ago"
    }
}

/**
 * Twenty percent. The screener's whole premise is a gap between price and worth worth acting on, and
 * a few percent of modelled upside is inside the noise of the model that produced it. Below this a
 * positive expectation is still positive, it is simply not emphatic.
 */
private const val MATERIAL_UPSIDE_BPS = 2_000

/**
 * Direction from the split between supporting and conflicting evidence; emphasis from the band.
 *
 * A net of zero is genuinely neutral rather than weakly anything, and so is an explicitly mixed
 * band — the label already says "Mixed signals", and a marker would contradict it.
 */
private fun evidenceQualifier(section: QuantLensEvidenceStrength): QuantLensQualifier {
    val net = section.supportCount - section.conflictCount
    return when (section.band) {
        EvidenceStrengthBand.Sparse,
        EvidenceStrengthBand.Unavailable,
        -> QuantLensQualifier.Unknown
        EvidenceStrengthBand.Mixed -> QuantLensQualifier.Neutral
        EvidenceStrengthBand.Strong -> directional(net, emphatic = true)
        EvidenceStrengthBand.Provisional -> directional(net, emphatic = false)
    }
}

/**
 * A range that straddles zero is neutral however wide it is — the sign of the midpoint would be an
 * accident of where the scenarios happened to land. Tension and dispute are adverse rather than
 * unknown: the lens read the sources and found them irreconcilable, which is itself information.
 */
private fun evQualifier(section: QuantLensExpectedValueRange, crossesZero: Boolean): QuantLensQualifier =
    when (section.band) {
        ExpectedValueRangeBand.Tension,
        ExpectedValueRangeBand.Disputed,
        -> QuantLensQualifier.Negative
        ExpectedValueRangeBand.ReferenceOnly,
        ExpectedValueRangeBand.Sparse,
        ExpectedValueRangeBand.Unavailable,
        -> QuantLensQualifier.Unknown
        ExpectedValueRangeBand.ScenarioWeighted ->
            if (crossesZero) QuantLensQualifier.Neutral else upsideQualifier(section.weightedUpsideBps)
    }

/** Moving on its own is a diversification benefit; moving with everything is the concentration risk. */
private fun correlationQualifier(band: CorrelationRiskBand): QuantLensQualifier = when (band) {
    CorrelationRiskBand.Low -> QuantLensQualifier.Positive
    CorrelationRiskBand.Elevated -> QuantLensQualifier.Negative
    CorrelationRiskBand.High -> QuantLensQualifier.StrongNegative
    CorrelationRiskBand.Sparse,
    CorrelationRiskBand.Unavailable,
    -> QuantLensQualifier.Unknown
}

/**
 * The band says how well a trend fits; only the movement says which way it points. A reliable
 * downtrend is a reliably bad thing, and reading the band alone would have painted it favourable.
 */
private fun trendQualifier(section: QuantLensTrendReliability): QuantLensQualifier = when (section.band) {
    TrendReliabilityBand.Reliable -> directional(section.movementBps ?: 0, emphatic = true)
    TrendReliabilityBand.Moderate -> directional(section.movementBps ?: 0, emphatic = false)
    TrendReliabilityBand.Noisy,
    TrendReliabilityBand.Flat,
    -> QuantLensQualifier.Neutral
    TrendReliabilityBand.Insufficient,
    TrendReliabilityBand.Unavailable,
    -> QuantLensQualifier.Unknown
}

/** For lenses that report only whether they had enough history to say anything at all. */
private fun availabilityQualifier(status: QuantLensPrimaryStatus): QuantLensQualifier = when (status) {
    QuantLensPrimaryStatus.Available,
    QuantLensPrimaryStatus.Partial,
    QuantLensPrimaryStatus.Provisional,
    -> QuantLensQualifier.Neutral
    else -> QuantLensQualifier.Unknown
}

private fun directional(value: Int, emphatic: Boolean): QuantLensQualifier = when {
    value > 0 -> if (emphatic) QuantLensQualifier.StrongPositive else QuantLensQualifier.Positive
    value < 0 -> if (emphatic) QuantLensQualifier.StrongNegative else QuantLensQualifier.Negative
    else -> QuantLensQualifier.Neutral
}

private fun upsideQualifier(bps: Int?): QuantLensQualifier = when {
    bps == null -> QuantLensQualifier.Neutral
    bps >= MATERIAL_UPSIDE_BPS -> QuantLensQualifier.StrongPositive
    bps > 0 -> QuantLensQualifier.Positive
    bps <= -MATERIAL_UPSIDE_BPS -> QuantLensQualifier.StrongNegative
    bps < 0 -> QuantLensQualifier.Negative
    else -> QuantLensQualifier.Neutral
}

private fun money(cents: Long): String = "$" + "%,.2f".format(cents / 100.0)

private fun signedPercent(bps: Int): String {
    val sign = if (bps >= 0) "+" else "-"
    return "$sign${abs(bps) / 100}%"
}

private fun absolutePercent(bps: Int): String = "%.2f%%".format(bps / 100.0)

private fun decimalBps(bps: Int): String = "%.2f".format(bps / 10_000.0)

private fun signedCorrelation(bps: Int): String = "%.2f".format(bps / 10_000.0)

private fun similarityLabel(bps: Int): String = when {
    bps >= 8_500 -> "Close"
    bps >= 7_000 -> "Near"
    else -> "Loose"
}
