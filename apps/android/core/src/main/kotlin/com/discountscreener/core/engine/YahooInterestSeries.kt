package com.discountscreener.core.engine

import com.discountscreener.core.model.AnnualReportedValue
import kotlin.math.abs

/**
 * Yahoo `annualInterestExpense` often lags two fiscal years.
 * Later years may still exist under a labeled sibling type.
 * Fill missing years only. Do not invent zero. Do not overwrite a primary year.
 */
object YahooInterestSeries {
    fun mergeByYear(vararg series: List<AnnualReportedValue>): List<AnnualReportedValue> {
        var byYear = linkedMapOf<String, AnnualReportedValue>()
        for (candidate in series) {
            for (point in candidate) {
                if (isCashPaidCouponConcept(point.concept)) continue
                var key = annualKey(point)
                if (key in byYear) continue
                if (!point.value.isFinite() || abs(point.value) <= 0.0) continue
                byYear[key] = point.copy(value = abs(point.value))
            }
        }
        return byYear.values.sortedBy { it.asOfDate }
    }
}

fun isCashPaidCouponConcept(concept: String?): Boolean {
    var text = concept.orEmpty()
    return text.contains("InterestPaid", ignoreCase = true)
}
