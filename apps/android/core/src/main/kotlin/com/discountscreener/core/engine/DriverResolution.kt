package com.discountscreener.core.engine

import com.discountscreener.core.model.FundamentalTimeseries
import com.discountscreener.core.model.DcfSource
import com.discountscreener.core.model.WaccFieldSource
import kotlin.math.abs
import kotlin.math.roundToInt

/** Exact semantic peer of Windows `driver_resolution.rs`. */
internal enum class DriverEvidenceQuality { Solid, Provisional }

internal data class ResolvedRateInputs(
    val costOfDebtBps: Int,
    val costOfDebtSource: WaccFieldSource,
    val marginalTaxBps: Int,
    val marginalTaxSource: WaccFieldSource,
    val quality: DriverEvidenceQuality,
    val validDebtPeriods: List<String>,
    val validTaxPeriods: List<String>,
    val reasons: List<String>,
)

internal fun resolveRateInputs(
    timeseries: FundamentalTimeseries,
    reportedTotalDebtDollars: Long?,
    _riskFreeBps: Int,
): Result<ResolvedRateInputs?> = runCatching {
    val totalDebt = reportedTotalDebtDollars
        ?: error("fcff unavailable: total debt is missing; missing debt is not zero")
    require(totalDebt >= 0L) { "fcff unavailable: total debt is negative or contradictory" }
    if (totalDebt == 0L) {
        val inconsistent = timeseries.totalDebt.any { debt ->
            debt.value == 0.0 && timeseries.interestExpense.any { interest ->
                annualKey(interest) == annualKey(debt) && abs(interest.value) > 0.0
            }
        }
        require(!inconsistent) {
            "fcff unavailable: provider inconsistency, positive interest with zero debt"
        }
        return@runCatching null
    }

    val accounting = timeseries.interestExpense.mapNotNull { interest ->
        val key = annualKey(interest)
        val debt = timeseries.totalDebt.firstOrNull { annualKey(it) == key } ?: return@mapNotNull null
        if (!interest.value.isFinite() || !debt.value.isFinite()) return@mapNotNull null
        if (debt.value > 0.0 && interest.value > 0.0) {
            Triple(key, debt.value, abs(interest.value))
        } else {
            null
        }
    }.distinctBy { it.first }.sortedBy { it.first }

    val taxByPeriod = timeseries.marginalTaxRate
        .mapNotNull { tax ->
            val value = normalizeTaxBps(tax.value) ?: return@mapNotNull null
            Triple(annualKey(tax), value, taxSource(tax.concept))
        }
        .distinctBy { it.first }
    require(taxByPeriod.isNotEmpty()) {
        "fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources"
    }
    val taxPeriodsAll = taxByPeriod.map { it.first }.toSet()
    val marketYields = timeseries.marketYieldBps
        .mapNotNull { point ->
            point.value.takeIf { it.isFinite() && it.roundToInt() in 0..5_000 }
                ?.let { annualKey(point) to it.roundToInt() }
        }
        .distinctBy { it.first }
        .sortedBy { it.first }
    val ratedSpreads = timeseries.ratedOrSyntheticSpreadBps
        .mapNotNull { point ->
            point.value.takeIf { it.isFinite() && it.roundToInt() in 0..4_000 }
                ?.let { annualKey(point) to it.roundToInt() }
        }
        .distinctBy { it.first }
        .sortedBy { it.first }
    val marketCommon = marketYields.filter { taxPeriodsAll.contains(it.first) }
    val ratedCommon = ratedSpreads.filter { taxPeriodsAll.contains(it.first) }
    val accountingCommon = accounting.filter { taxPeriodsAll.contains(it.first) }

    val costOfDebtBps: Int
    val costOfDebtSource: WaccFieldSource
    val debtPeriods: List<String>
    val averageDebt: Double
    when {
        marketCommon.isNotEmpty() -> {
            costOfDebtBps = marketCommon.last().second
            costOfDebtSource = WaccFieldSource.MarketYield
            debtPeriods = listOf(marketCommon.last().first)
            averageDebt = totalDebt.toDouble()
        }
        ratedCommon.isNotEmpty() -> {
            costOfDebtBps = (_riskFreeBps + ratedCommon.last().second).coerceAtMost(Int.MAX_VALUE)
            costOfDebtSource = WaccFieldSource.RatedOrSyntheticSpread
            debtPeriods = listOf(ratedCommon.last().first)
            averageDebt = totalDebt.toDouble()
        }
        accountingCommon.isNotEmpty() -> {
            // Resolve one annual rate per fiscal period. Summing several
            // years of interest and dividing by one average debt would
            // multiply the cost of debt by the number of years. Each annual
            // observation instead uses the average of the current and prior
            // fiscal closing debt, then the median annual rate is selected.
            val annualRates = accountingCommon.mapIndexed { index, (period, debt, interest) ->
                val priorDebt = accountingCommon
                    .subList(0, index)
                    .asReversed()
                    .firstOrNull { it.first < period }
                    ?.second
                    ?: debt
                val averageDebtForPeriod = (debt + priorDebt) / 2.0
                val rate = (interest / averageDebtForPeriod * 10_000.0).roundToInt()
                Triple(period, rate, averageDebtForPeriod)
            }
            require(annualRates.all { it.second in 1..5_000 }) {
                "fcff unavailable: aligned interest/debt implies invalid cost of debt"
            }
            debtPeriods = annualRates.map { it.first }
            averageDebt = annualRates.sumOf { it.third } / annualRates.size
            costOfDebtBps = annualRates.map { it.second }.sorted()[annualRates.size / 2]
            val yahooAligned = accountingCommon.all { (period, _, _) ->
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

    val selectedTaxSource = listOf(
        WaccFieldSource.TaxReconciliation,
        WaccFieldSource.JurisdictionStatutory,
        WaccFieldSource.DomicileTaxProxy,
    ).firstOrNull { source ->
        taxByPeriod.any { (period, _, candidate) ->
            candidate == source && debtPeriods.contains(period)
        }
    }
    requireNotNull(selectedTaxSource) {
        "fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources"
    }
    val selectedTax = taxByPeriod.filter { (_, _, source) -> source == selectedTaxSource }
    val taxPeriods = debtPeriods.filter { period ->
        selectedTax.any { it.first == period }
    }
    require(taxPeriods.isNotEmpty()) {
        "fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources"
    }
    val quality = if (taxPeriods.size >= 3 && selectedTaxSource != WaccFieldSource.DomicileTaxProxy) {
        DriverEvidenceQuality.Solid
    } else {
        DriverEvidenceQuality.Provisional
    }
    buildList {
        add("cost_of_debt_source=${costOfDebtSource.toSourceToken()}")
        add("marginal_tax_source=${selectedTaxSource.toSourceToken()}")
        add("rate_quality=${quality.name.lowercase()}")
        add("aligned_debt_periods=${debtPeriods.joinToString(",")}")
        add("aligned_tax_periods=${taxPeriods.joinToString(",")}")
        add("period_intersection=common_fiscal_years:${taxPeriods.size}")
    }.let { reasons ->
        ResolvedRateInputs(
            costOfDebtBps = costOfDebtBps,
            costOfDebtSource = costOfDebtSource,
            marginalTaxBps = selectedTax.last { it.first in taxPeriods }.second,
            marginalTaxSource = selectedTaxSource,
            quality = quality,
            validDebtPeriods = debtPeriods,
            validTaxPeriods = taxPeriods,
            reasons = reasons,
        )
    }
}

private fun WaccFieldSource.toSourceToken(): String = when (this) {
    WaccFieldSource.MarketYield -> "market_yield"
    WaccFieldSource.RatedOrSyntheticSpread -> "rated_or_synthetic_spread"
    WaccFieldSource.InterestOverAverageDebt -> "interest_over_average_debt"
    WaccFieldSource.YahooAlignedInterestOverDebt -> "yahoo_aligned_interest_over_debt"
    WaccFieldSource.TaxReconciliation -> "tax_reconciliation"
    WaccFieldSource.JurisdictionStatutory -> "jurisdiction_statutory"
    WaccFieldSource.DomicileTaxProxy -> "domicile_tax_proxy"
    WaccFieldSource.ReportedMarginalTax -> "reported_marginal_tax"
    else -> name.lowercase()
}

private fun taxSource(concept: String?): WaccFieldSource = when {
    concept?.contains("Reconciliation", ignoreCase = true) == true ->
        WaccFieldSource.TaxReconciliation
    concept?.contains("Statutory", ignoreCase = true) == true ->
        WaccFieldSource.JurisdictionStatutory
    concept?.contains("Domicile", ignoreCase = true) == true ->
        WaccFieldSource.DomicileTaxProxy
    else -> WaccFieldSource.Unavailable
}

internal fun annualKey(value: com.discountscreener.core.model.AnnualReportedValue): String =
    value.fiscalYear?.toString() ?: value.periodEnd ?: value.asOfDate

internal fun normalizeTaxBps(value: Double?): Int? {
    val raw = value?.takeIf { it.isFinite() } ?: return null
    val bps = when {
        abs(raw) <= 1.0 -> raw * 10_000.0
        abs(raw) <= 100.0 -> raw * 100.0
        else -> raw
    }
    return bps.roundToInt().takeIf { it in 0..5_000 }
}
