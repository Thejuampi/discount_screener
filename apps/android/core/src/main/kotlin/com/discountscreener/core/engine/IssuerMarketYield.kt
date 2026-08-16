package com.discountscreener.core.engine

import com.discountscreener.core.math.medianOf
import com.discountscreener.core.model.AnnualReportedValue
import com.discountscreener.core.model.DcfSource
import com.discountscreener.core.model.FundamentalTimeseries
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

const val ISSUER_MARKET_YIELD_VERSION = "issuer-market-yield/2"

val ISSUER_YIELD_MIN_REMAINING_YEARS: Double
    get() = ValuationPolicy.current.issuerYield.minRemainingYears
val ISSUER_YIELD_MAX_REMAINING_YEARS: Double
    get() = ValuationPolicy.current.issuerYield.maxRemainingYears

fun interface IssuerYieldLookup {
    fun lookup(symbol: String, companyName: String?): IssuerYieldPoint?
}

data class IssuerYieldPoint(
    val yieldBps: Int,
    val asOfDate: String? = null,
    val concept: String = "IssuerInstrumentYield",
)

data class IssuerInstrumentQuote(
    val yieldBps: Int,
    val maturityDate: String? = null,
    val currency: String? = null,
)

fun selectIssuerMarketYield(
    quotes: List<IssuerInstrumentQuote>,
    asOfDate: String,
): IssuerYieldPoint? {
    var asOf = runCatching { LocalDate.parse(asOfDate) }.getOrNull() ?: return null
    var outstanding = quotes.mapNotNull { quote ->
        var bounds = ValuationPolicy.current.issuerYield
        if (quote.yieldBps !in bounds.minYieldBps..bounds.maxYieldBps) return@mapNotNull null
        var currency = quote.currency?.trim()?.uppercase()
        if (currency != null && currency != "USD") return@mapNotNull null
        var maturity = quote.maturityDate?.let { raw ->
            runCatching { LocalDate.parse(raw) }.getOrNull()
        }
        if (maturity != null && maturity.isBefore(asOf)) return@mapNotNull null
        var remainingYears = maturity?.let { ChronoUnit.DAYS.between(asOf, it) / 365.25 }
        quote to remainingYears
    }
    if (outstanding.isEmpty()) return null
    var preferred = outstanding.filter { (_, years) ->
        years != null && years in ISSUER_YIELD_MIN_REMAINING_YEARS..ISSUER_YIELD_MAX_REMAINING_YEARS
    }
    var chosen = preferred.ifEmpty { outstanding }
    var centre = medianOf(chosen.map { it.first.yieldBps.toDouble() }) ?: return null
    var concept = if (preferred.isNotEmpty()) {
        "IssuerInstrumentYield:usd_4_15y_median"
    } else {
        "IssuerInstrumentYield:usd_outstanding_median"
    }
    return IssuerYieldPoint(
        yieldBps = centre.roundToInt(),
        concept = concept,
    )
}

fun attachMarketYield(
    timeseries: FundamentalTimeseries,
    point: IssuerYieldPoint,
): FundamentalTimeseries {
    require(point.yieldBps in 0..5_000) {
        "issuer market yield is out of range: ${point.yieldBps}"
    }
    var asOf = point.asOfDate?.takeIf { it.isNotBlank() } ?: latestAlignDate(timeseries)
        ?: return timeseries
    var row = AnnualReportedValue(
        asOfDate = asOf,
        value = point.yieldBps.toDouble(),
        periodEnd = asOf,
        source = DcfSource.Derived,
        concept = point.concept,
        unit = "bps",
    )
    return timeseries.copy(marketYieldBps = listOf(row))
}

private fun latestAlignDate(timeseries: FundamentalTimeseries): String? {
    var tax = timeseries.marginalTaxRate.maxByOrNull(::annualKey)?.let(::annualKey)
    if (tax != null) return tax
    return timeseries.totalDebt.maxByOrNull(::annualKey)?.let(::annualKey)
}
