package com.discountscreener.core.engine

import com.discountscreener.core.model.AnnualReportedValue
import com.discountscreener.core.model.DcfSource
import kotlin.test.Test
import kotlin.test.assertEquals

class YahooInterestSeriesTest {
    @Test
    fun later_years_come_from_the_fallback_when_primary_is_stale() {
        var primary = listOf(
            point("2021-09-30", 2_645_000_000.0, "annualInterestExpense"),
            point("2022-09-30", 2_931_000_000.0, "annualInterestExpense"),
            point("2023-09-30", 3_933_000_000.0, "annualInterestExpense"),
        )
        var fallback = listOf(
            point("2024-09-30", 4_000_000_000.0, "annualInterestExpenseNonOperating"),
            point("2025-09-30", 3_500_000_000.0, "annualInterestExpenseNonOperating"),
        )

        var merged = YahooInterestSeries.mergeByYear(primary, fallback)

        assertEquals(
            listOf("2021-09-30", "2022-09-30", "2023-09-30", "2024-09-30", "2025-09-30"),
            merged.map { it.asOfDate },
        )
    }

    @Test
    fun a_primary_year_is_kept_when_the_fallback_also_has_that_year() {
        var primary = listOf(point("2023-09-30", 3_933_000_000.0, "annualInterestExpense"))
        var fallback = listOf(point("2023-09-30", 1.0, "annualInterestExpenseNonOperating"))

        var merged = YahooInterestSeries.mergeByYear(primary, fallback)

        assertEquals(3_933_000_000.0, merged.single().value)
    }

    @Test
    fun a_zero_fallback_does_not_invent_an_interest_year() {
        var primary = listOf(point("2023-09-30", 3_933_000_000.0, "annualInterestExpense"))
        var fallback = listOf(point("2024-09-30", 0.0, "annualInterestPaid"))

        var merged = YahooInterestSeries.mergeByYear(primary, fallback)

        assertEquals(listOf("2023-09-30"), merged.map { it.asOfDate })
    }

    @Test
    fun interest_paid_is_not_a_filed_coupon() {
        var merged = YahooInterestSeries.mergeByYear(
            listOf(point("2023-09-30", 3_933_000_000.0, "annualInterestExpense")),
            listOf(point("2024-09-30", 4_000_000_000.0, "annualInterestPaid")),
        )
        assertEquals(listOf("2023-09-30"), merged.map { it.asOfDate })
    }
}

private fun point(asOfDate: String, value: Double, concept: String) = AnnualReportedValue(
    asOfDate = asOfDate,
    value = value,
    source = DcfSource.YahooFinance,
    concept = concept,
)
