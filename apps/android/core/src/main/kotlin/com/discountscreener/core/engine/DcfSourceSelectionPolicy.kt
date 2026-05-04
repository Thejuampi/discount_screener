package com.discountscreener.core.engine

import com.discountscreener.core.model.AnnualReportedValue
import com.discountscreener.core.model.DcfSource
import com.discountscreener.core.model.DcfSourceCandidate
import com.discountscreener.core.model.DcfSourceSelection
import com.discountscreener.core.model.FundamentalTimeseries

object DcfSourceSelectionPolicy {
    fun select(
        yahoo: DcfSourceCandidate? = null,
        sec: DcfSourceCandidate? = null,
    ): DcfSourceSelection = when {
        sec != null && isDcfUsable(sec) -> selected(sec)
        yahoo != null && isDcfUsable(yahoo) -> selected(yahoo)
        else -> DcfSourceSelection()
    }

    fun isDcfUsable(candidate: DcfSourceCandidate): Boolean {
        val timeseries = candidate.timeseries ?: return false
        if (candidate.analysis == null) return false
        val annualFreeCashFlow = acceptedAnnualFreeCashFlow(timeseries)
        if (annualFreeCashFlow.size < MIN_ANNUAL_FCF_POINTS) return false
        return annualFreeCashFlow.last().value > 0.0
    }

    private fun selected(candidate: DcfSourceCandidate): DcfSourceSelection {
        val timeseries = candidate.timeseries ?: return DcfSourceSelection()
        val analysis = candidate.analysis ?: return DcfSourceSelection()
        return DcfSourceSelection(
            selectedSource = candidate.source,
            timeseries = timeseries,
            analysis = analysis.copy(
                source = candidate.source,
                sourceFingerprint = fingerprint(candidate.source, timeseries),
            ),
        )
    }

    private fun acceptedAnnualFreeCashFlow(timeseries: FundamentalTimeseries): List<AnnualReportedValue> =
        timeseries.freeCashFlow
            .filter { point -> point.asOfDate.isNotBlank() && point.value.isFinite() }
            .distinctBy { point -> point.asOfDate }
            .sortedBy { point -> point.asOfDate }

    private fun fingerprint(source: DcfSource, timeseries: FundamentalTimeseries): String = buildString {
        append(source.name)
        append("|fcf=")
        append(seriesFingerprint(acceptedAnnualFreeCashFlow(timeseries)))
        append("|shares=")
        append(seriesFingerprint(timeseries.dilutedAverageShares))
    }

    private fun seriesFingerprint(values: List<AnnualReportedValue>): String = values
        .filter { point -> point.asOfDate.isNotBlank() && point.value.isFinite() }
        .sortedBy { point -> point.asOfDate }
        .joinToString(";") { point -> "${point.asOfDate}:${point.value}" }

    private const val MIN_ANNUAL_FCF_POINTS = 3
}