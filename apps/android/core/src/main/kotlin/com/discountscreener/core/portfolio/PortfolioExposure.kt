package com.discountscreener.core.portfolio

import java.math.BigInteger

/** A quote for the supported book currency. [isCurrent] records whether the source confirms it is current. */
data class PortfolioQuote(
    val priceCents: Long,
    val isCurrent: Boolean = true,
    val currencyCode: String = "USD",
)

enum class Coverage {
    Complete,
    Partial,
    Unavailable,
}

data class PortfolioLotExposure(
    val inputIndex: Int,
    val lot: PortfolioLot,
    val symbol: String,
    val quoteCents: Long?,
    val quoteIsCurrent: Boolean?,
    val marketValueCents: Long?,
    val costCents: Long?,
    val profitLossCents: Long?,
    val profitLossBps: Int?,
    val weightBps: Int?,
    val rawMarketValue: BigInteger? = null,
    val rawCost: BigInteger? = null,
) {
}

data class PortfolioBookSummary(
    val totalValueCents: Long?,
    val valueCoverage: Coverage,
    val valueEligibleLots: Int,
    val totalLots: Int,
    val profitLossCents: Long?,
    val profitLossBps: Int?,
    val profitLossCoverage: Coverage,
    val profitLossEligibleLots: Int,
    val hasNonCurrentQuotes: Boolean,
    val rawTotalValue: BigInteger? = null,
)

data class PortfolioExposure(
    val rows: List<PortfolioLotExposure>,
    val summary: PortfolioBookSummary,
)

fun summarizePortfolioExposure(
    rows: List<PortfolioLotExposure>,
    totalLots: Int = rows.size,
): PortfolioBookSummary {
    require(totalLots >= rows.size) { "totalLots cannot be smaller than the supplied rows" }
    val valueRows = rows.filter { it.rawMarketValue != null && it.marketValueCents != null }
    val valueEligible = valueRows.size
    val rawTotal = valueRows.map { it.rawMarketValue!! }.fold(BigInteger.ZERO, BigInteger::add)
        .takeIf { valueEligible > 0 }
    val pairedRows = rows.filter {
        it.rawMarketValue != null && it.marketValueCents != null &&
            it.rawCost != null && it.costCents != null && it.profitLossCents != null
    }
    val pairedCount = pairedRows.size
    val rawProfitLoss = pairedRows.map { it.rawMarketValue!! - it.rawCost!! }
        .fold(BigInteger.ZERO, BigInteger::add).takeIf { pairedCount > 0 }
    val rawCost = pairedRows.map { it.rawCost!! }
        .fold(BigInteger.ZERO, BigInteger::add).takeIf { pairedCount > 0 }
    return PortfolioBookSummary(
        totalValueCents = rawTotal?.divideByQuantityScaleToLong(),
        valueCoverage = coverage(valueEligible, totalLots),
        valueEligibleLots = valueEligible,
        totalLots = totalLots,
        profitLossCents = rawProfitLoss?.divideByQuantityScaleToLong(),
        profitLossBps = if (rawProfitLoss != null && rawCost != null && rawCost.signum() > 0) {
            ratioBps(rawProfitLoss, rawCost)
        } else {
            null
        },
        profitLossCoverage = coverage(pairedCount, totalLots),
        profitLossEligibleLots = pairedCount,
        hasNonCurrentQuotes = rows.any { it.rawMarketValue != null && it.quoteIsCurrent == false },
        rawTotalValue = rawTotal,
    )
}

/**
 * Projects each input lot against a quote map.
 *
 * The map accepts [PortfolioQuote] values. Unsupported quote currencies remain unavailable.
 */
fun projectPortfolioExposure(
    lots: List<PortfolioLot>,
    quotes: Map<String, PortfolioQuote>,
): PortfolioExposure {
    val normalizedQuotes = quotes.mapKeys { it.key.trim().uppercase() }
    val rows = lots.mapIndexed { index, lot ->
        val symbol = lot.symbol.trim().uppercase()
        val quote = normalizedQuotes[symbol]?.takeIf { it.currencyCode.equals("USD", ignoreCase = true) }
        val quantityValid = lot.quantityTenThousandths > 0L
        val quoteValid = quote != null && quote.priceCents > 0L
        val rawMarket = if (quantityValid && quoteValid) {
            BigInteger.valueOf(lot.quantityTenThousandths)
                .multiply(BigInteger.valueOf(requireNotNull(quote).priceCents))
        } else {
            null
        }
        val rawCost = if (quantityValid && lot.avgCostCents >= 0L) {
            BigInteger.valueOf(lot.quantityTenThousandths)
                .multiply(BigInteger.valueOf(lot.avgCostCents))
        } else {
            null
        }
        val marketCents = rawMarket?.divideByQuantityScaleToLong()
        val costCents = rawCost?.divideByQuantityScaleToLong()
        val rawProfitLoss = if (rawMarket != null && rawCost != null) rawMarket - rawCost else null
        val profitLossCents = if (marketCents != null && costCents != null) {
            rawProfitLoss?.divideByQuantityScaleToLong()
        } else {
            null
        }
        val profitLossBps = if (
            marketCents != null && costCents != null &&
            requireNotNull(rawCost).signum() > 0
        ) {
            ratioBps(requireNotNull(rawProfitLoss), requireNotNull(rawCost))
        } else {
            null
        }
        PortfolioLotExposure(
            inputIndex = index,
            lot = lot,
            symbol = symbol,
            quoteCents = quote?.priceCents?.takeIf { quoteValid },
            quoteIsCurrent = quote?.takeIf { quoteValid }?.isCurrent,
            marketValueCents = marketCents,
            costCents = costCents,
            profitLossCents = profitLossCents,
            profitLossBps = profitLossBps,
            weightBps = null,
            rawMarketValue = rawMarket,
            rawCost = rawCost,
        )
    }

    val summary = summarizePortfolioExposure(rows)
    val weightsAllowed = summary.valueCoverage == Coverage.Complete &&
        summary.rawTotalValue != null && summary.rawTotalValue.signum() > 0 &&
        summary.totalValueCents != null
    val weightedRows = if (weightsAllowed) {
        rows.map { row ->
            val raw = row.rawMarketValue
            row.copy(weightBps = raw?.let { ratioBps(it, requireNotNull(summary.rawTotalValue)) })
        }
    } else {
        rows
    }
    return PortfolioExposure(weightedRows, summary)
}

private const val QUANTITY_SCALE: Long = 10_000L

private fun coverage(eligible: Int, total: Int): Coverage = when {
    eligible == 0 -> Coverage.Unavailable
    eligible == total -> Coverage.Complete
    else -> Coverage.Partial
}

private fun BigInteger.divideByQuantityScaleToLong(): Long? {
    val quotient = divideAndRoundHalfUp(this, BigInteger.valueOf(QUANTITY_SCALE))
    return quotient.toLongExactOrNull()
}

private fun BigInteger.toLongExactOrNull(): Long? = try {
    longValueExact()
} catch (_: ArithmeticException) {
    null
}

private fun divideAndRoundHalfUp(value: BigInteger, divisor: BigInteger): BigInteger {
    val result = value.divideAndRemainder(divisor)
    val remainder = result[1].abs()
    if (remainder.shiftLeft(1) < divisor.abs()) return result[0]
    return if (value.signum() >= 0) result[0] + BigInteger.ONE else result[0] - BigInteger.ONE
}

private fun ratioBps(numerator: BigInteger, denominator: BigInteger): Int? {
    if (denominator.signum() <= 0) return 0
    val scaled = numerator.multiply(BigInteger.valueOf(10_000L))
    val rounded = divideAndRoundHalfUp(scaled, denominator)
    return try {
        rounded.intValueExact()
    } catch (_: ArithmeticException) {
        null
    }
}
