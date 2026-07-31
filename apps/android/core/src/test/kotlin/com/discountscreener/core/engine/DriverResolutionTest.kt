package com.discountscreener.core.engine

import com.discountscreener.core.model.AnnualReportedValue
import com.discountscreener.core.model.DcfSource
import com.discountscreener.core.model.FundamentalTimeseries
import com.discountscreener.core.model.WaccFieldSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DriverResolutionTest {
    @Test
    fun market_yield_precedes_accounting_and_requires_tax_period_alignment() {
        val timeseries = financingTimeseries().copy(
            marketYieldBps = listOf(AnnualReportedValue("2023-12-31", 700.0)),
        )

        val resolved = resolveRateInputs(timeseries, 120L, 430).getOrThrow()!!

        assertEquals(700, resolved.costOfDebtBps)
        assertEquals(WaccFieldSource.MarketYield, resolved.costOfDebtSource)
        assertEquals(listOf("2023"), resolved.validDebtPeriods)
        assertEquals(DriverEvidenceQuality.Provisional, resolved.quality)
    }

    @Test
    fun rated_spread_is_added_to_risk_free_before_accounting_fallback() {
        val timeseries = financingTimeseries().copy(
            ratedOrSyntheticSpreadBps = listOf(AnnualReportedValue("2023-12-31", 250.0)),
        )

        val resolved = resolveRateInputs(timeseries, 120L, 430).getOrThrow()!!

        assertEquals(680, resolved.costOfDebtBps)
        assertEquals(WaccFieldSource.RatedOrSyntheticSpread, resolved.costOfDebtSource)
    }

    @Test
    fun yahoo_aligned_accounting_source_is_not_labeled_as_sec() {
        val yahoo = financingTimeseries().copy(
            interestExpense = financingTimeseries().interestExpense.map { it.copy(source = DcfSource.YahooFinance) },
            totalDebt = financingTimeseries().totalDebt.map { it.copy(source = DcfSource.YahooFinance) },
        )

        val resolved = resolveRateInputs(yahoo, 120L, 430).getOrThrow()!!

        assertEquals(WaccFieldSource.YahooAlignedInterestOverDebt, resolved.costOfDebtSource)
        assertTrue(resolved.reasons.any { it == "cost_of_debt_source=yahoo_aligned_interest_over_debt" })
    }

    @Test
    fun explicit_zero_debt_is_not_applicable_but_positive_interest_is_contradictory() {
        val zeroDebt = FundamentalTimeseries(
            interestExpense = listOf(AnnualReportedValue("2023-12-31", 0.0)),
            totalDebt = listOf(AnnualReportedValue("2023-12-31", 0.0)),
        )
        assertEquals(null, resolveRateInputs(zeroDebt, 0L, 430).getOrThrow())

        val contradictory = zeroDebt.copy(
            interestExpense = listOf(AnnualReportedValue("2023-12-31", 1.0)),
        )
        val error = assertFailsWith<IllegalArgumentException> {
            resolveRateInputs(contradictory, 0L, 430).getOrThrow()
        }
        assertTrue(error.message.orEmpty().contains("provider inconsistency"))
    }

    private fun financingTimeseries() = FundamentalTimeseries(
        interestExpense = listOf(
            AnnualReportedValue("2021-12-31", 5.0),
            AnnualReportedValue("2022-12-31", 5.5),
            AnnualReportedValue("2023-12-31", 6.0),
        ),
        totalDebt = listOf(
            AnnualReportedValue("2021-12-31", 100.0),
            AnnualReportedValue("2022-12-31", 110.0),
            AnnualReportedValue("2023-12-31", 120.0),
        ),
        marginalTaxRate = listOf(
            AnnualReportedValue("2021-12-31", 0.21, concept = "JurisdictionStatutory"),
            AnnualReportedValue("2022-12-31", 0.20, concept = "JurisdictionStatutory"),
            AnnualReportedValue("2023-12-31", 0.19, concept = "JurisdictionStatutory"),
        ),
    )
}
