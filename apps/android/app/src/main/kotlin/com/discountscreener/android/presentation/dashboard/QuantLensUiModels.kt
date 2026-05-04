package com.discountscreener.android.presentation.dashboard

import com.discountscreener.core.model.CorrelationRiskBand
import com.discountscreener.core.model.EvidenceStrengthBand
import com.discountscreener.core.model.ExpectedValueRangeBand
import com.discountscreener.core.model.QuantLensLensId
import com.discountscreener.core.model.QuantLensLensRowState
import com.discountscreener.core.model.QuantLensPrimaryStatus
import com.discountscreener.core.model.QuantLensReport
import com.discountscreener.core.model.QuantLensRowLabel
import com.discountscreener.core.model.QuantLensRowSummary
import com.discountscreener.core.model.SimilarSetupsBand
import com.discountscreener.core.model.TrendReliabilityBand
import kotlin.math.abs

enum class QuantLensSeverity {
    Supportive,
    Neutral,
    Warning,
    Risk,
    Muted,
}

data class QuantLensChipUi(
    val lensId: QuantLensLensId?,
    val label: String,
    val severity: QuantLensSeverity,
)

data class QuantLensSectionUi(
    val lensId: QuantLensLensId,
    val title: String,
    val chip: QuantLensChipUi,
    val primaryLine: String,
    val rows: List<Pair<String, String>> = emptyList(),
    val footerChips: List<String> = emptyList(),
)

data class QuantLensUiState(
    val headerChips: List<QuantLensChipUi>,
    val sections: List<QuantLensSectionUi>,
)

internal fun mapQuantLensReport(report: QuantLensReport?): QuantLensUiState? {
    report ?: return null
    val sections = listOf(
        evidenceSection(report),
        evSection(report),
        correlationSection(report),
        trendSection(report),
        similarSection(report),
    )
    return QuantLensUiState(
        headerChips = sections.map { it.chip },
        sections = sections,
    )
}

internal fun mapRowQuantLensSummary(summary: QuantLensRowSummary?, maxChips: Int = 3): List<QuantLensChipUi> {
    summary ?: return listOf(QuantLensChipUi(null, "Lens loading", QuantLensSeverity.Muted))
    val byLens = summary.lensStates.associateBy { it.lensId }
    val eligible = buildList {
        byLens[QuantLensLensId.EvidenceStrength]?.let { add(it) }
        byLens[QuantLensLensId.ExpectedValueRange]?.takeIf {
            it.primaryStatus == QuantLensPrimaryStatus.Available
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
        EvidenceStrengthBand.Strong -> "Evidence strong"
        EvidenceStrengthBand.Provisional -> "Evidence provisional"
        EvidenceStrengthBand.Mixed -> "Evidence mixed"
        EvidenceStrengthBand.Sparse -> "Evidence sparse"
        EvidenceStrengthBand.Unavailable -> "Evidence unavailable"
    }
    return QuantLensSectionUi(
        lensId = QuantLensLensId.EvidenceStrength,
        title = "Evidence strength",
        chip = QuantLensChipUi(QuantLensLensId.EvidenceStrength, label, severityFor(section.primaryStatus, mixed = section.band == EvidenceStrengthBand.Mixed)),
        primaryLine = "Supports ${section.supportCount} · Conflicts ${section.conflictCount} · Neutral ${section.neutralCount}",
        footerChips = listOf("Current inputs"),
    )
}

private fun evSection(report: QuantLensReport): QuantLensSectionUi {
    val section = report.expectedValueRange
    val label = when (section.band) {
        ExpectedValueRangeBand.ScenarioWeighted -> evRangeLabel(section.lowFairValueCents, section.highFairValueCents, report)
        ExpectedValueRangeBand.ReferenceOnly,
        ExpectedValueRangeBand.Sparse,
        -> "EV sparse"
        ExpectedValueRangeBand.Unavailable -> "EV unavailable"
    }
    val source = section.source?.name ?: "Reference only"
    return QuantLensSectionUi(
        lensId = QuantLensLensId.ExpectedValueRange,
        title = "Expected value range",
        chip = QuantLensChipUi(QuantLensLensId.ExpectedValueRange, label, severityFor(section.primaryStatus)),
        primaryLine = section.weightedUpsideBps?.let { "Scenario-weighted upside ${signedPercent(it)}" }
            ?: "Reference only",
        rows = listOfNotNull(
            section.lowFairValueCents?.let { "Low" to money(it) },
            section.weightedFairValueCents?.let { "Weighted" to money(it) },
            section.highFairValueCents?.let { "High" to money(it) },
            section.spreadBps?.let { "Range width" to signedPercent(it) },
        ),
        footerChips = listOf(source, if (section.band == ExpectedValueRangeBand.ScenarioWeighted) "3 anchors" else "Sparse"),
    )
}

private fun correlationSection(report: QuantLensReport): QuantLensSectionUi {
    val section = report.correlationRisk
    val label = when (section.band) {
        CorrelationRiskBand.Low -> "Corr low"
        CorrelationRiskBand.Elevated -> "Corr elevated"
        CorrelationRiskBand.High -> "Corr high"
        CorrelationRiskBand.Sparse -> "Corr sparse"
        CorrelationRiskBand.Unavailable -> "Corr unavailable"
    }
    return QuantLensSectionUi(
        lensId = QuantLensLensId.CorrelationRisk,
        title = "Correlation risk",
        chip = QuantLensChipUi(QuantLensLensId.CorrelationRisk, label, severityFor(section.primaryStatus, risk = section.band == CorrelationRiskBand.High)),
        primaryLine = if (section.topPairs.isEmpty()) "Insufficient local history" else "Local return correlation · ${section.validPairCount} peers",
        rows = section.topPairs.take(3).map {
            it.symbol to "${signedCorrelation(it.correlationBps)} · ${it.overlapCount} returns"
        },
        footerChips = listOf("Local saved returns"),
    )
}

private fun trendSection(report: QuantLensReport): QuantLensSectionUi {
    val section = report.trendReliability
    val label = when (section.band) {
        TrendReliabilityBand.Reliable -> "Trend reliable"
        TrendReliabilityBand.Moderate -> "Trend moderate"
        TrendReliabilityBand.Noisy -> "Trend noisy"
        TrendReliabilityBand.Flat -> "Trend flat"
        TrendReliabilityBand.Insufficient -> "Trend insufficient"
        TrendReliabilityBand.Unavailable -> "Trend unavailable"
    }
    return QuantLensSectionUi(
        lensId = QuantLensLensId.TrendReliability,
        title = "Trend reliability",
        chip = QuantLensChipUi(QuantLensLensId.TrendReliability, label, severityFor(section.primaryStatus)),
        primaryLine = section.rSquaredBps?.let { "R^2 ${decimalBps(it)} · ${section.sampleCount} samples" }
            ?: "${section.sampleCount} samples",
        rows = listOfNotNull(
            section.movementBps?.let { "Movement" to signedPercent(it) },
            section.rSquaredBps?.let { "Fit quality" to decimalBps(it) },
        ),
        footerChips = listOf(report.selectedRange.name),
    )
}

private fun similarSection(report: QuantLensReport): QuantLensSectionUi {
    val section = report.similarSetups
    val label = when (section.band) {
        SimilarSetupsBand.Available -> "Similar 3"
        SimilarSetupsBand.Sparse -> "Similar sparse"
        SimilarSetupsBand.Unavailable -> "Similar unavailable"
    }
    return QuantLensSectionUi(
        lensId = QuantLensLensId.SimilarSetups,
        title = "Similar setups",
        chip = QuantLensChipUi(QuantLensLensId.SimilarSetups, label, severityFor(section.primaryStatus)),
        primaryLine = if (section.matches.isEmpty()) {
            "${section.qualifyingComparableCount} qualifying comparables"
        } else {
            "3 current setups · closest by features"
        },
        rows = section.matches.take(3).map {
            it.symbol to "${similarityLabel(it.similarityBps)} · ${it.sharedFeatureCount} shared"
        },
        footerChips = listOf("Current universe", "3 shared min"),
    )
}

private fun rowChip(state: QuantLensLensRowState): QuantLensChipUi =
    QuantLensChipUi(
        lensId = state.lensId,
        label = when (state.label) {
            QuantLensRowLabel.EvidenceStrong -> "Evidence strong"
            QuantLensRowLabel.EvidenceProvisional -> "Evidence provisional"
            QuantLensRowLabel.EvidenceMixed -> "Evidence mixed"
            QuantLensRowLabel.EvidenceSparse -> "Evidence sparse"
            QuantLensRowLabel.EvidenceUnavailable -> "Evidence unavailable"
            QuantLensRowLabel.EvRange -> evRowRange(state)
            QuantLensRowLabel.EvSparse -> "EV sparse"
            QuantLensRowLabel.EvUnavailable -> "EV unavailable"
            QuantLensRowLabel.CorrLow -> "Corr low"
            QuantLensRowLabel.CorrElevated -> "Corr elevated"
            QuantLensRowLabel.CorrHigh -> "Corr high"
            QuantLensRowLabel.CorrSparse -> "Corr sparse"
            QuantLensRowLabel.CorrUnavailable -> "Corr unavailable"
            QuantLensRowLabel.TrendReliable -> "Trend reliable"
            QuantLensRowLabel.TrendModerate -> "Trend moderate"
            QuantLensRowLabel.TrendNoisy -> "Trend noisy"
            QuantLensRowLabel.TrendFlat -> "Trend flat"
            QuantLensRowLabel.TrendSparse -> "Trend sparse"
            QuantLensRowLabel.TrendUnavailable -> "Trend unavailable"
            QuantLensRowLabel.SimilarAvailable -> "Similar 3"
            QuantLensRowLabel.SimilarSparse -> "Similar sparse"
            QuantLensRowLabel.SimilarUnavailable -> "Similar unavailable"
            null -> "Lens unavailable"
        },
        severity = severityFor(state.primaryStatus),
    )

private fun evRowRange(state: QuantLensLensRowState): String {
    val low = state.evLowUpsideBps
    val high = state.evHighUpsideBps
    return if (low != null && high != null) "EV ${signedPercent(low)}..${signedPercent(high)}" else "EV range"
}

private fun evRangeLabel(lowCents: Long?, highCents: Long?, report: QuantLensReport): String {
    val price = report.expectedValueRange.weightedFairValueCents ?: return "EV range"
    val low = lowCents?.let { checkedPercent(report = report, value = it) }
    val high = highCents?.let { checkedPercent(report = report, value = it) }
    return if (low != null && high != null) "EV ${signedPercent(low)}..${signedPercent(high)}" else "EV ${money(price)}"
}

private fun checkedPercent(report: QuantLensReport, value: Long): Int? {
    val weighted = report.expectedValueRange.weightedFairValueCents ?: return null
    if (weighted <= 0L) return null
    return (((value - weighted).toDouble() / weighted.toDouble()) * 10_000.0).toInt()
}

private fun severityFor(
    status: QuantLensPrimaryStatus,
    mixed: Boolean = false,
    risk: Boolean = false,
): QuantLensSeverity = when {
    risk -> QuantLensSeverity.Risk
    mixed -> QuantLensSeverity.Warning
    status == QuantLensPrimaryStatus.Available -> QuantLensSeverity.Supportive
    status == QuantLensPrimaryStatus.Provisional || status == QuantLensPrimaryStatus.Partial -> QuantLensSeverity.Neutral
    status == QuantLensPrimaryStatus.Sparse || status == QuantLensPrimaryStatus.Insufficient -> QuantLensSeverity.Muted
    else -> QuantLensSeverity.Muted
}

private fun money(cents: Long): String = "$" + "%,.2f".format(cents / 100.0)

private fun signedPercent(bps: Int): String {
    val sign = if (bps >= 0) "+" else "-"
    return "$sign${abs(bps) / 100}%"
}

private fun decimalBps(bps: Int): String = "%.2f".format(bps / 10_000.0)

private fun signedCorrelation(bps: Int): String = "%.2f".format(bps / 10_000.0)

private fun similarityLabel(bps: Int): String = when {
    bps >= 8_500 -> "Close"
    bps >= 7_000 -> "Near"
    else -> "Loose"
}
