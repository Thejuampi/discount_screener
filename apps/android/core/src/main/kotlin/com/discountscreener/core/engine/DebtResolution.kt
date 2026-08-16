package com.discountscreener.core.engine

import com.discountscreener.core.math.medianOf
import com.discountscreener.core.model.DcfSource
import com.discountscreener.core.model.FundamentalTimeseries
import com.discountscreener.core.model.WaccFieldSource
import kotlin.math.abs
import kotlin.math.roundToInt

const val DEBT_RESOLUTION_VERSION = "debt-resolution/1"

data class DebtYear(
    val period: String,
    val stockDollars: Double?,
    val coupon: CouponYear,
    val coverage: Double? = null,
)

data class PublishedCostOfDebt(
    val bps: Int,
    val source: WaccFieldSource,
    val validDebtPeriods: List<String>,
    val reasons: List<String>,
)

data class ResolvedDebt(
    val years: List<DebtYear>,
    val publishedKd: PublishedCostOfDebt? = null,
) {
    val coupons: List<CouponYear> get() = years.map { it.coupon }
}

fun resolveDebt(
    timeseries: FundamentalTimeseries,
    peers: List<PeerCouponEvidence> = emptyList(),
    reportedTotalDebtDollars: Long? = null,
    riskFreeBps: Int = 0,
): ResolvedDebt {
    var coupons = resolveCoupons(couponInputsFrom(timeseries), peers)
    var pretaxByPeriod = timeseries.pretaxIncome.associateBy(::annualKey)
    var years = coupons.map { coupon ->
        var stock = timeseries.totalDebt.firstOrNull { annualKey(it) == coupon.period }?.value
        var coverage = filedCoverage(coupon, pretaxByPeriod[coupon.period]?.value)
        DebtYear(
            period = coupon.period,
            stockDollars = stock,
            coupon = coupon,
            coverage = coverage,
        )
    }
    var publishedKd = resolvePublishedCostOfDebt(
        timeseries,
        reportedTotalDebtDollars,
        riskFreeBps,
    ).getOrNull()
    return ResolvedDebt(years = years, publishedKd = publishedKd)
}

internal fun resolvePublishedCostOfDebt(
    timeseries: FundamentalTimeseries,
    reportedTotalDebtDollars: Long?,
    riskFreeBps: Int,
): Result<PublishedCostOfDebt?> = runCatching {
    var totalDebt = reportedTotalDebtDollars
        ?: error("fcff unavailable: total debt is missing; missing debt is not zero")
    require(totalDebt >= 0L) { "fcff unavailable: total debt is negative or contradictory" }
    if (totalDebt == 0L) {
        var inconsistent = timeseries.totalDebt.any { debt ->
            debt.value == 0.0 && timeseries.interestExpense.any { interest ->
                annualKey(interest) == annualKey(debt) &&
                    !isCashPaidCouponConcept(interest.concept) &&
                    abs(interest.value) > 0.0
            }
        }
        require(!inconsistent) {
            "fcff unavailable: provider inconsistency, positive interest with zero debt"
        }
        return@runCatching null
    }

    var accounting = timeseries.interestExpense.mapNotNull { interest ->
        if (isCashPaidCouponConcept(interest.concept)) return@mapNotNull null
        var key = annualKey(interest)
        var debt = timeseries.totalDebt.firstOrNull { annualKey(it) == key } ?: return@mapNotNull null
        if (!interest.value.isFinite() || !debt.value.isFinite()) return@mapNotNull null
        var coupon = abs(interest.value)
        if (debt.value > 0.0 && coupon > 0.0) {
            Triple(key, debt.value, coupon)
        } else {
            null
        }
    }.distinctBy { it.first }.sortedBy { it.first }

    var taxByPeriod = taxObservations(timeseries)
    require(taxByPeriod.isNotEmpty()) {
        "fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources"
    }
    var taxPeriodsAll = taxByPeriod.map { it.period }.toSet()
    var currentInstrumentYields = timeseries.marketYieldBps.mapNotNull { point ->
        if (point.concept?.startsWith("IssuerInstrumentYield") != true) return@mapNotNull null
        point.value.takeIf { it.isFinite() && it.roundToInt() in 0..5_000 }?.roundToInt()
    }
    var marketYields = timeseries.marketYieldBps
        .mapNotNull { point ->
            if (point.concept?.startsWith("IssuerInstrumentYield") == true) return@mapNotNull null
            point.value.takeIf { it.isFinite() && it.roundToInt() in 0..5_000 }
                ?.let { annualKey(point) to it.roundToInt() }
        }
        .distinctBy { it.first }
        .sortedBy { it.first }
    var ratedSpreads = timeseries.ratedOrSyntheticSpreadBps
        .mapNotNull { point ->
            point.value.takeIf { it.isFinite() && it.roundToInt() in 0..4_000 }
                ?.let { annualKey(point) to it.roundToInt() }
        }
        .distinctBy { it.first }
        .sortedBy { it.first }
    var marketCommon = marketYields.filter { taxPeriodsAll.contains(it.first) }
    var ratedCommon = ratedSpreads.filter { taxPeriodsAll.contains(it.first) }
    var accountingCommon = accounting.filter { taxPeriodsAll.contains(it.first) }
    var coverageByPeriod = timeseries.interestExpense.mapNotNull { interest ->
        if (isCashPaidCouponConcept(interest.concept)) return@mapNotNull null
        var key = annualKey(interest)
        if (!taxPeriodsAll.contains(key)) return@mapNotNull null
        var pretax = timeseries.pretaxIncome.firstOrNull { annualKey(it) == key }
            ?: return@mapNotNull null
        var coupon = abs(interest.value)
        if (!interest.value.isFinite() || coupon <= 0.0) return@mapNotNull null
        if (!pretax.value.isFinite()) return@mapNotNull null
        key to ((pretax.value + coupon) / coupon)
    }.distinctBy { it.first }.sortedBy { it.first }
    var coverageSpreadBps = coverageByPeriod
        .map { it.second }
        .let { medianOf(it) }
        ?.let { CoverageCreditPolicy.spreadBps(it) }

    var costOfDebtBps: Int
    var costOfDebtSource: WaccFieldSource
    var debtPeriods: List<String>
    when {
        currentInstrumentYields.isNotEmpty() -> {
            costOfDebtBps = currentInstrumentYields.last()
            costOfDebtSource = WaccFieldSource.MarketYield
            debtPeriods = when {
                coverageByPeriod.isNotEmpty() -> coverageByPeriod.map { it.first }
                accountingCommon.isNotEmpty() -> accountingCommon.map { it.first }
                else -> taxByPeriod.map { it.period }.distinct().sorted()
            }
        }
        marketCommon.isNotEmpty() -> {
            costOfDebtBps = marketCommon.last().second
            costOfDebtSource = WaccFieldSource.MarketYield
            debtPeriods = listOf(marketCommon.last().first)
        }
        ratedCommon.isNotEmpty() -> {
            costOfDebtBps = (riskFreeBps + ratedCommon.last().second).coerceAtMost(Int.MAX_VALUE)
            costOfDebtSource = WaccFieldSource.RatedOrSyntheticSpread
            debtPeriods = listOf(ratedCommon.last().first)
        }
        coverageSpreadBps != null -> {
            costOfDebtBps = (riskFreeBps + coverageSpreadBps).coerceAtMost(Int.MAX_VALUE)
            costOfDebtSource = WaccFieldSource.RatedOrSyntheticSpread
            debtPeriods = coverageByPeriod.map { it.first }
        }
        accountingCommon.isNotEmpty() -> {
            var annualRates = accountingCommon.mapIndexed { index, (period, debt, interest) ->
                var priorDebt = accountingCommon
                    .subList(0, index)
                    .asReversed()
                    .firstOrNull { it.first < period }
                    ?.second
                    ?: debt
                var averageDebtForPeriod = (debt + priorDebt) / 2.0
                var rate = (interest / averageDebtForPeriod * 10_000.0).roundToInt()
                Triple(period, rate, averageDebtForPeriod)
            }
            require(annualRates.all { it.second in 1..5_000 }) {
                "fcff unavailable: aligned interest/debt implies invalid cost of debt"
            }
            debtPeriods = annualRates.map { it.first }
            costOfDebtBps = (medianOf(annualRates.map { it.second.toDouble() })
                ?: error("fcff unavailable: no aligned annual cost of debt observations"))
                .roundToInt()
            var yahooAligned = accountingCommon.all { (period, _, _) ->
                timeseries.interestExpense.any {
                    annualKey(it) == period && it.source == DcfSource.YahooFinance
                } && timeseries.totalDebt.any {
                    annualKey(it) == period && it.source == DcfSource.YahooFinance
                }
            }
            costOfDebtSource = if (yahooAligned) {
                WaccFieldSource.YahooAlignedInterestOverDebt
            } else {
                WaccFieldSource.InterestOverAverageDebt
            }
        }
        else -> error("fcff unavailable: no aligned market yield, spread, or SEC interest/debt periods")
    }

    var reasons = buildList {
        add("cost_of_debt_source=${waccSourceToken(costOfDebtSource)}")
        add("cost_of_debt_bps=$costOfDebtBps")
        if (currentInstrumentYields.isNotEmpty()) {
            add("market_yield=current_instrument")
        }
        if (costOfDebtSource == WaccFieldSource.RatedOrSyntheticSpread &&
            ratedCommon.isEmpty() &&
            coverageSpreadBps != null
        ) {
            add("coverage_synthetic=median_spread:$coverageSpreadBps")
        }
        add("aligned_debt_periods=${debtPeriods.joinToString(",")}")
    }
    PublishedCostOfDebt(
        bps = costOfDebtBps,
        source = costOfDebtSource,
        validDebtPeriods = debtPeriods,
        reasons = reasons,
    )
}

private fun filedCoverage(coupon: CouponYear, pretax: Double?): Double? {
    if (coupon.kind != CouponKind.Filed) return null
    var dollars = coupon.dollars?.takeIf { it.isFinite() && it > 0.0 } ?: return null
    var pretaxValue = pretax?.takeIf { it.isFinite() } ?: return null
    return (pretaxValue + dollars) / dollars
}
