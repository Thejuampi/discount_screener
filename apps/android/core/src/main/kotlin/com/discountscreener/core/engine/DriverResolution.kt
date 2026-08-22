package com.discountscreener.core.engine

import com.discountscreener.core.model.FundamentalTimeseries
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

internal data class TaxObservation(
    val period: String,
    val bps: Int,
    val source: WaccFieldSource,
)

internal fun taxObservations(timeseries: FundamentalTimeseries): List<TaxObservation> =
    timeseries.marginalTaxRate
        .ifEmpty { timeseries.taxRateForCalcs }
        .mapNotNull { tax ->
            var value = normalizeTaxBps(tax.value) ?: return@mapNotNull null
            TaxObservation(annualKey(tax), value, taxSource(tax.concept))
        }
        .distinctBy { it.period }

internal fun resolveRateInputs(
    timeseries: FundamentalTimeseries,
    reportedTotalDebtDollars: Long?,
    _riskFreeBps: Int,
): Result<ResolvedRateInputs?> = runCatching {
    var published = resolvePublishedCostOfDebt(
        timeseries,
        reportedTotalDebtDollars,
        _riskFreeBps,
    ).getOrThrow()
    if (published == null) return@runCatching null

    var taxByPeriod = taxObservations(timeseries)
    require(taxByPeriod.isNotEmpty()) {
        "fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources"
    }
    var debtPeriods = published.validDebtPeriods
    var selectedTaxSource = listOf(
        WaccFieldSource.TaxReconciliation,
        WaccFieldSource.JurisdictionStatutory,
        WaccFieldSource.DomicileTaxProxy,
        WaccFieldSource.ReportedMarginalTax,
    ).firstOrNull { source ->
        taxByPeriod.any { observation ->
            observation.source == source && debtPeriods.contains(observation.period)
        }
    }
    requireNotNull(selectedTaxSource) {
        "fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources"
    }
    var selectedTax = taxByPeriod.filter { it.source == selectedTaxSource }
    var taxPeriods = debtPeriods.filter { period ->
        selectedTax.any { it.period == period }
    }
    require(taxPeriods.isNotEmpty()) {
        "fcff unavailable: marginal tax is unavailable after filing and jurisdiction sources"
    }
    var quality = if (
        taxPeriods.size >= 3 &&
        selectedTaxSource != WaccFieldSource.DomicileTaxProxy &&
        selectedTaxSource != WaccFieldSource.ReportedMarginalTax
    ) {
        DriverEvidenceQuality.Solid
    } else {
        DriverEvidenceQuality.Provisional
    }
    var reasons = published.reasons.toMutableList()
    reasons.add("marginal_tax_source=${waccSourceToken(selectedTaxSource)}")
    reasons.add("rate_quality=${quality.name.lowercase()}")
    reasons.add("aligned_tax_periods=${taxPeriods.joinToString(",")}")
    reasons.add("period_intersection=common_fiscal_years:${taxPeriods.size}")
    ResolvedRateInputs(
        costOfDebtBps = published.bps,
        costOfDebtSource = published.source,
        marginalTaxBps = selectedTax.last { it.period in taxPeriods }.bps,
        marginalTaxSource = selectedTaxSource,
        quality = quality,
        validDebtPeriods = debtPeriods,
        validTaxPeriods = taxPeriods,
        reasons = reasons,
    )
}

internal fun waccSourceToken(source: WaccFieldSource): String = when (source) {
    WaccFieldSource.MarketYield -> "market_yield"
    WaccFieldSource.RatedOrSyntheticSpread -> "rated_or_synthetic_spread"
    WaccFieldSource.InterestOverAverageDebt -> "interest_over_average_debt"
    WaccFieldSource.YahooAlignedInterestOverDebt -> "yahoo_aligned_interest_over_debt"
    WaccFieldSource.TaxReconciliation -> "tax_reconciliation"
    WaccFieldSource.JurisdictionStatutory -> "jurisdiction_statutory"
    WaccFieldSource.DomicileTaxProxy -> "domicile_tax_proxy"
    WaccFieldSource.ReportedMarginalTax -> "reported_marginal_tax"
    WaccFieldSource.HistoricalEffectiveTax -> "historical_effective_tax"
    else -> source.name.lowercase()
}

internal fun taxSource(concept: String?): WaccFieldSource = when {
    concept?.contains("Reconciliation", ignoreCase = true) == true ->
        WaccFieldSource.TaxReconciliation
    concept?.contains("Statutory", ignoreCase = true) == true ->
        WaccFieldSource.JurisdictionStatutory
    concept?.contains("Domicile", ignoreCase = true) == true ->
        WaccFieldSource.DomicileTaxProxy
    concept?.contains("TaxRateForCalcs", ignoreCase = true) == true ->
        WaccFieldSource.HistoricalEffectiveTax
    concept?.contains("MarginalTax", ignoreCase = true) == true ->
        WaccFieldSource.ReportedMarginalTax
    else -> WaccFieldSource.Unavailable
}

/** Period-end identity. SEC `fy` is the filing year and collides comparatives. */
internal fun annualKey(value: com.discountscreener.core.model.AnnualReportedValue): String =
    value.periodEnd?.takeIf { it.isNotBlank() } ?: value.asOfDate

internal fun normalizeTaxBps(value: Double?): Int? {
    val raw = value?.takeIf { it.isFinite() } ?: return null
    val bps = when {
        abs(raw) <= 1.0 -> raw * 10_000.0
        abs(raw) <= 100.0 -> raw * 100.0
        else -> raw
    }
    return bps.roundToInt().takeIf { it in 0..5_000 }
}
