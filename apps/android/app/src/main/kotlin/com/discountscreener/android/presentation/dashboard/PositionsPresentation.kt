package com.discountscreener.android.presentation.dashboard

import com.discountscreener.android.domain.model.OpportunityListRow
import com.discountscreener.android.domain.model.RowDecisionState
import com.discountscreener.android.domain.model.RowFreshness
import com.discountscreener.android.domain.model.TrackedSymbolRow
import com.discountscreener.android.domain.model.decisionTagIsCurrent
import com.discountscreener.core.portfolio.Closeness
import com.discountscreener.core.portfolio.Coverage
import com.discountscreener.core.portfolio.PortfolioLot
import com.discountscreener.core.portfolio.PortfolioLotExposure
import com.discountscreener.core.portfolio.PortfolioQuote
import com.discountscreener.core.portfolio.PositionDecision
import com.discountscreener.core.portfolio.PositionEvidence
import com.discountscreener.core.portfolio.PositionLensState
import com.discountscreener.core.portfolio.PositionPrimary
import com.discountscreener.core.portfolio.PositionResearchInput
import com.discountscreener.core.portfolio.PositionTrust
import com.discountscreener.core.portfolio.closeness
import com.discountscreener.core.portfolio.formatLotShares
import com.discountscreener.core.portfolio.nySessionDay
import com.discountscreener.core.portfolio.positionResearch
import com.discountscreener.core.portfolio.projectPortfolioExposure
import com.discountscreener.core.portfolio.summarizePortfolioExposure
import java.time.LocalDate

data class PositionsRow(
    val symbol: String,
    val quantityLabel: String,
    val avgCostCents: Long,
    val closeness: Closeness,
    val opportunity: OpportunityListRow?,
    val inputIndex: Int = 0,
    val quoteCents: Long? = null,
    val quoteIsCurrent: Boolean? = null,
    val marketValueCents: Long? = null,
    val profitLossCents: Long? = null,
    val profitLossBps: Int? = null,
    val weightBps: Int? = null,
    val valueCoverage: Coverage = Coverage.Unavailable,
    val reviewLabel: String = "Check data",
    val researchReason: String = "Missing analysis",
    val valuationSource: String? = null,
    val valuationLabel: String? = null,
    val score: Int? = opportunity?.compositeScore,
    val storedScoreMarker: String? = null,
    val exposure: PortfolioLotExposure? = null,
)

data class PositionsBookSummary(
    val totalValueCents: Long?,
    val valueCoverage: Coverage,
    val valueEligibleLots: Int,
    val totalLots: Int,
    val profitLossCents: Long?,
    val profitLossBps: Int?,
    val profitLossCoverage: Coverage,
    val profitLossEligibleLots: Int,
    val hasNonCurrentQuotes: Boolean,
)

enum class PositionsSort {
    LargestPosition,
    NeedsReview,
    EarningsSoon,
}

fun scoredLots(
    universe: List<OpportunityListRow>,
    opps: List<OpportunityListRow>,
): List<OpportunityListRow> {
    var bySymbol = universe.associateBy { it.symbol.trim().uppercase() }.toMutableMap()
    opps.forEach { row -> bySymbol[row.symbol.trim().uppercase()] = row }
    return bySymbol.values.toList()
}

internal fun portfolioQuotesFromTrackedRows(rows: List<TrackedSymbolRow>): Map<String, PortfolioQuote> =
    rows.mapNotNull { row ->
        val priceCents = row.marketPriceCents?.takeIf { it > 0L } ?: return@mapNotNull null
        row.symbol.trim().uppercase() to PortfolioQuote(
            priceCents = priceCents,
            isCurrent = decisionTagIsCurrent(row.freshness),
        )
    }.toMap()

fun upcomingReportDates(
    gate: EarningsGateUi,
    today: LocalDate,
): Map<String, LocalDate> {
    var dates = nearestReportDates(gate.upcoming, today).toMutableMap()
    nearestReportDates(gate.settled, today).forEach { (symbol, day) ->
        var current = dates[symbol]
        if (current == null || day.isBefore(current)) {
            dates[symbol] = day
        }
    }
    return dates
}

fun reportDatesForLots(
    gate: EarningsGateUi,
    calendarEpochs: Map<String, Long?>,
    today: LocalDate,
): Map<String, LocalDate> {
    var merged = LinkedHashMap<String, LocalDate>()
    upcomingReportDates(gate, today).forEach { (symbol, day) ->
        merged[symbol] = day
    }
    calendarEpochs.forEach { (symbol, epoch) ->
        var day = epoch?.let(::nySessionDay)?.takeIf { !it.isBefore(today) } ?: return@forEach
        merged.putIfAbsent(symbol.trim().uppercase(), day)
    }
    return merged
}

private fun nearestReportDates(
    rows: List<EarningsEventRowUi>,
    today: LocalDate,
): Map<String, LocalDate> {
    var dates = LinkedHashMap<String, LocalDate>()
    rows.forEach { row ->
        var day = row.reportEpochDay?.let(LocalDate::ofEpochDay) ?: return@forEach
        if (day.isBefore(today)) return@forEach
        var symbol = row.symbol.trim().uppercase()
        var current = dates[symbol]
        if (current == null || day.isBefore(current)) {
            dates[symbol] = day
        }
    }
    return dates
}

fun projectPositions(
    lots: List<PortfolioLot>,
    scored: List<OpportunityListRow>,
    upcomingReport: Map<String, LocalDate>,
    today: LocalDate,
    quotes: Map<String, PortfolioQuote> = emptyMap(),
): List<PositionsRow> {
    var scoredBySymbol = scored.associateBy { it.symbol.trim().uppercase() }
    val defaultQuotes = scoredBySymbol.mapValues { (_, opportunity) ->
        PortfolioQuote(
            priceCents = opportunity.marketPriceCents,
            isCurrent = opportunity.freshness == RowFreshness.Updated,
        )
    }.toMutableMap().apply { putAll(quotes) }
    val exposure = projectPortfolioExposure(lots, defaultQuotes)
    return exposure.rows.map { exposed ->
        val lot = exposed.lot
        val inputIndex = exposed.inputIndex
        var symbol = exposed.symbol
        var opportunity = scoredBySymbol[symbol]
        var report = upcomingReport[symbol]?.takeIf { !it.isBefore(today) }
            ?: opportunity?.nextEarningsEpoch?.let(::nySessionDay)?.takeIf { !it.isBefore(today) }
        var research = positionResearch(
            PositionResearchInput(
                decision = opportunity?.decisionState?.toPositionDecision(),
                evidence = when {
                    opportunity == null -> PositionEvidence.Missing
                    exposed.quoteIsCurrent == false -> PositionEvidence.Stored
                    else -> opportunity?.freshness?.toPositionEvidence() ?: PositionEvidence.Missing
                },
                trust = when {
                    opportunity?.confidence?.name == "Low" -> PositionTrust.LowConfidence
                    opportunity?.trustNote == "Model value" -> PositionTrust.ModelValue
                    opportunity?.trustNote?.isNotBlank() == true -> PositionTrust.Note(requireNotNull(opportunity.trustNote))
                    opportunity == null -> PositionTrust.Missing
                    else -> PositionTrust.Trusted
                },
                primary = primaryFor(opportunity),
                marketPriceCents = exposed.quoteCents,
                providerIssue = opportunity?.providerIssue,
                lensState = opportunity?.quantLensSummary?.positionLensState(symbol) ?: PositionLensState.None,
                score = opportunity?.compositeScore,
            ),
        )
        PositionsRow(
            symbol = symbol,
            quantityLabel = formatLotShares(lot.quantityTenThousandths),
            avgCostCents = lot.avgCostCents,
            closeness = closeness(today, report),
            opportunity = opportunity,
            inputIndex = inputIndex,
            quoteCents = exposed.quoteCents,
            quoteIsCurrent = exposed.quoteIsCurrent,
            marketValueCents = exposed.marketValueCents,
            profitLossCents = exposed.profitLossCents,
            profitLossBps = exposed.profitLossBps,
            weightBps = exposed.weightBps,
            valueCoverage = exposure.summary.valueCoverage,
            reviewLabel = research.opportunity.displayLabel,
            researchReason = research.reason,
            valuationSource = research.valuationSource,
            valuationLabel = research.valuationLabel,
            score = research.score,
            storedScoreMarker = research.storedMarker,
            exposure = exposed,
        )
    }.sortedWith(positionComparator(PositionsSort.EarningsSoon))
}

/** Builds the book summary from retained raw row products. */
fun presentPositionsSummary(rows: List<PositionsRow>): PositionsBookSummary {
    val summary = summarizePortfolioExposure(
        rows = rows.mapNotNull { it.exposure },
        totalLots = rows.size,
    )
    return PositionsBookSummary(
        totalValueCents = summary.totalValueCents,
        valueCoverage = summary.valueCoverage,
        valueEligibleLots = summary.valueEligibleLots,
        totalLots = rows.size,
        profitLossCents = summary.profitLossCents,
        profitLossBps = summary.profitLossBps,
        profitLossCoverage = summary.profitLossCoverage,
        profitLossEligibleLots = summary.profitLossEligibleLots,
        hasNonCurrentQuotes = summary.hasNonCurrentQuotes,
    )
}

fun sortPositionRows(rows: List<PositionsRow>, sort: PositionsSort): List<PositionsRow> =
    rows.sortedWith(positionComparator(sort))

private fun positionComparator(sort: PositionsSort): Comparator<PositionsRow> = compareBy<PositionsRow> {
    when (sort) {
        PositionsSort.LargestPosition -> 0
        PositionsSort.NeedsReview -> reviewRank(it.reviewLabel)
        PositionsSort.EarningsSoon -> it.closeness.ordinal
    }
}.thenComparator { a, b ->
    when {
        a.marketValueCents == null && b.marketValueCents != null -> 1
        a.marketValueCents != null && b.marketValueCents == null -> -1
        a.marketValueCents != null && b.marketValueCents != null -> b.marketValueCents.compareTo(a.marketValueCents)
        else -> 0
    }
}.thenBy { it.symbol }.thenBy { it.inputIndex }

private fun reviewRank(label: String): Int = when (label) {
    "Check data" -> 0
    "Review position" -> 1
    "Monitor" -> 2
    "Review opportunity" -> 3
    else -> 0
}

private fun primaryFor(opportunity: OpportunityListRow?): PositionPrimary {
    opportunity ?: return PositionPrimary.Missing
    val value = opportunity.intrinsicValueCents?.takeIf { it > 0L }
    return when (opportunity.valuationStanceLabel) {
        "Analyst range" ->
            value?.let(PositionPrimary::Analyst) ?: PositionPrimary.Missing
        "Identity" ->
            value?.let(PositionPrimary::Model) ?: PositionPrimary.Missing
        "Tension" -> PositionPrimary.Tension
        "Disputed" -> PositionPrimary.Disputed
        "Unavailable" -> PositionPrimary.Unavailable
        else -> PositionPrimary.Missing
    }
}

private fun RowDecisionState.toPositionDecision(): PositionDecision = when (this) {
    RowDecisionState.Act -> PositionDecision.Act
    RowDecisionState.Watch -> PositionDecision.Watch
    RowDecisionState.Avoid -> PositionDecision.Avoid
}

private fun RowFreshness.toPositionEvidence(): PositionEvidence =
    if (this == RowFreshness.Updated) PositionEvidence.Current else PositionEvidence.Stored

private fun com.discountscreener.core.model.QuantLensRowSummary.positionLensState(symbol: String): PositionLensState {
    if (!this.symbol.equals(symbol, ignoreCase = true)) return PositionLensState.None
    val states = lensStates.filter {
        it.lensId == com.discountscreener.core.model.QuantLensLensId.EvidenceStrength ||
            it.lensId == com.discountscreener.core.model.QuantLensLensId.ExpectedValueRange
    }
    return when {
        states.any { it.primaryStatus == com.discountscreener.core.model.QuantLensPrimaryStatus.Disputed } ->
            PositionLensState.Disputed
        states.any { it.primaryStatus == com.discountscreener.core.model.QuantLensPrimaryStatus.Provisional } ->
            PositionLensState.Provisional
        else -> PositionLensState.None
    }
}

private val com.discountscreener.core.portfolio.PositionOpportunity.displayLabel: String
    get() = when (this) {
        com.discountscreener.core.portfolio.PositionOpportunity.ReviewOpportunity -> "Review opportunity"
        com.discountscreener.core.portfolio.PositionOpportunity.Monitor -> "Monitor"
        com.discountscreener.core.portfolio.PositionOpportunity.ReviewPosition -> "Review position"
        com.discountscreener.core.portfolio.PositionOpportunity.CheckData -> "Check data"
    }
