package com.discountscreener.core.engine

import com.discountscreener.core.model.AnnualReportedValue
import com.discountscreener.core.model.FundamentalTimeseries
import kotlin.math.abs

/**
 * What one-off charges did to the last year of earnings.
 *
 * Impairment and restructuring are booked inside operating income, so a year with a large
 * write-down reports a profit that the business did not earn or lose. Growth read across that year
 * measures the write-down. This reading says how big the charge was against the year it landed in,
 * and offers the same series with the charge added back.
 *
 * It marks and never scores. Which charges are truly one-off is a judgment no rule here can make:
 * a write-down every third year is the business, and one in a decade is an accident.
 */
data class EarningsContamination(
    /** The latest year's charges in bps of that year's operating income. Null when unmeasurable. */
    val chargeShareBps: Int?,
    /** True when the charge is large enough that the year no longer stands for the business. */
    val latestYearContaminated: Boolean,
    /** Operating income with each year's charges added back, aligned to the GAAP series. */
    val normalizedOperatingIncome: List<AnnualReportedValue>,
)

internal const val NORMALIZED_OPERATING_INCOME_CONCEPT = "operating_income_before_charges"

/** The signal a contaminated year raises. It carries no points; see [EarningsContamination]. */
internal const val EARNINGS_CHARGE_LABEL = "Charges"

/**
 * Reads [FundamentalTimeseries.operatingIncome] against [FundamentalTimeseries.nonRecurringCharges].
 *
 * Only the SEC provider fills those two lines, so a Yahoo-only name reads as unmeasured and never
 * as clean. The threshold is the contract's, not this file's.
 */
fun earningsContamination(timeseries: FundamentalTimeseries?): EarningsContamination {
    var operating = timeseries?.operatingIncome.orEmpty().sortedBy { it.asOfDate }
    var chargeByDate = timeseries?.nonRecurringCharges.orEmpty()
        .associate { it.asOfDate to abs(it.value) }
    var normalized = operating.map { year ->
        year.copy(
            value = year.value + (chargeByDate[year.asOfDate] ?: 0.0),
            concept = NORMALIZED_OPERATING_INCOME_CONCEPT,
        )
    }
    var latest = operating.lastOrNull()
        ?: return EarningsContamination(null, false, normalized)
    var charge = chargeByDate[latest.asOfDate] ?: 0.0
    if (charge <= 0.0) return EarningsContamination(0, false, normalized)
    // A year whose operating income is zero has no scale to measure a charge against. The charge is
    // still the whole of what that year reports, so the year is marked and the size is left unsaid.
    var base = abs(latest.value)
    if (base <= 0.0) return EarningsContamination(null, true, normalized)
    var shareBps = (charge / base * 10_000.0).toInt()
    return EarningsContamination(
        chargeShareBps = shareBps,
        latestYearContaminated = shareBps >= SecDriverNormalizationPolicy.nonRecurringChargeOperatingBps,
        normalizedOperatingIncome = normalized,
    )
}
