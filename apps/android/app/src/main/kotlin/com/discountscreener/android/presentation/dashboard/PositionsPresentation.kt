package com.discountscreener.android.presentation.dashboard

import com.discountscreener.android.domain.model.OpportunityListRow
import com.discountscreener.core.portfolio.Closeness
import com.discountscreener.core.portfolio.PortfolioLot
import com.discountscreener.core.portfolio.closeness
import com.discountscreener.core.portfolio.formatLotShares
import com.discountscreener.core.portfolio.nySessionDay
import java.time.LocalDate

data class PositionsRow(
    val symbol: String,
    val quantityLabel: String,
    val avgCostCents: Long,
    val closeness: Closeness,
    val opportunity: OpportunityListRow?,
)

fun scoredLots(
    universe: List<OpportunityListRow>,
    opps: List<OpportunityListRow>,
): List<OpportunityListRow> {
    var bySymbol = universe.associateBy { it.symbol.trim().uppercase() }.toMutableMap()
    opps.forEach { row -> bySymbol[row.symbol.trim().uppercase()] = row }
    return bySymbol.values.toList()
}

fun upcomingReportDates(gate: EarningsGateUi): Map<String, LocalDate> =
    (gate.settled + gate.upcoming).mapNotNull { row ->
        var day = row.reportEpochDay ?: return@mapNotNull null
        row.symbol.trim().uppercase() to LocalDate.ofEpochDay(day)
    }.toMap()

fun projectPositions(
    lots: List<PortfolioLot>,
    scored: List<OpportunityListRow>,
    upcomingReport: Map<String, LocalDate>,
    today: LocalDate,
): List<PositionsRow> {
    var scoredBySymbol = scored.associateBy { it.symbol.trim().uppercase() }
    return lots.map { lot ->
        var symbol = lot.symbol.trim().uppercase()
        var opportunity = scoredBySymbol[symbol]
        var report = upcomingReport[symbol]
            ?: opportunity?.nextEarningsEpoch?.let(::nySessionDay)
        PositionsRow(
            symbol = symbol,
            quantityLabel = formatLotShares(lot.quantityTenThousandths),
            avgCostCents = lot.avgCostCents,
            closeness = closeness(today, report),
            opportunity = opportunity,
        )
    }.sortedWith(compareBy({ it.closeness }, { it.symbol }))
}
