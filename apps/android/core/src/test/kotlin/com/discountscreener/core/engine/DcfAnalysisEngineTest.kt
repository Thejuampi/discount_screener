package com.discountscreener.core.engine

import com.discountscreener.core.model.AnnualReportedValue
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.FundamentalTimeseries
import com.discountscreener.core.model.WaccFieldSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DcfAnalysisEngineTest {
    @Test
    fun compute_with_complete_inputs_marks_wacc_fields_reported() {
        val analysis = DcfAnalysisEngine.compute(
            fundamentals = completeFundamentals(),
            timeseries = completeTimeseries(),
        ).getOrThrow()

        assertEquals(WaccFieldSource.Reported, analysis.waccInputs.marketCap)
        assertEquals(WaccFieldSource.Reported, analysis.waccInputs.beta)
        assertEquals(WaccFieldSource.Reported, analysis.waccInputs.totalDebt)
        assertEquals(WaccFieldSource.Reported, analysis.waccInputs.totalCash)
        assertEquals(WaccFieldSource.InterestOverDebt, analysis.waccInputs.costOfDebt)
        assertEquals(WaccFieldSource.Reported, analysis.waccInputs.taxRate)
        assertFalse(analysis.waccInputs.isProvisional())
        assertTrue(analysis.waccBps in 500..1_800)
    }

    @Test
    fun compute_derives_market_cap_from_price_times_shares_when_missing() {
        val fundamentals = completeFundamentals().copy(marketCapDollars = null)
        val analysis = DcfAnalysisEngine.compute(
            fundamentals = fundamentals,
            timeseries = completeTimeseries(),
            marketPriceCents = 1_200, // $12.00
        ).getOrThrow()

        assertEquals(WaccFieldSource.DerivedPriceTimesShares, analysis.waccInputs.marketCap)
        assertTrue(analysis.waccInputs.isProvisional())
        assertTrue(analysis.waccInputs.summaryLabels().any { it.contains("market cap") })
        assertTrue(analysis.baseIntrinsicValueCents > 0L)
    }

    @Test
    fun compute_fails_when_market_cap_and_price_shares_fallback_unavailable() {
        val fundamentals = completeFundamentals().copy(
            marketCapDollars = null,
            sharesOutstanding = null,
        )
        val timeseries = completeTimeseries().copy(dilutedAverageShares = emptyList())
        val result = DcfAnalysisEngine.compute(
            fundamentals = fundamentals,
            timeseries = timeseries,
            marketPriceCents = 1_200,
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("share count") == true
            || result.exceptionOrNull()?.message?.contains("market cap") == true)
    }

    @Test
    fun compute_marks_default_beta_and_tax_when_missing() {
        val fundamentals = completeFundamentals().copy(betaMillis = null)
        val timeseries = completeTimeseries().copy(taxRateForCalcs = emptyList())
        val analysis = DcfAnalysisEngine.compute(
            fundamentals = fundamentals,
            timeseries = timeseries,
        ).getOrThrow()

        assertEquals(WaccFieldSource.Default, analysis.waccInputs.beta)
        assertEquals(WaccFieldSource.Default, analysis.waccInputs.taxRate)
        assertTrue(analysis.waccInputs.isProvisional())
        assertTrue(analysis.waccInputs.summaryLabels().contains("beta=default"))
        assertTrue(analysis.waccInputs.summaryLabels().contains("tax=default"))
    }

    @Test
    fun compute_marks_assumed_zero_debt_and_cash_when_missing() {
        val fundamentals = completeFundamentals().copy(
            totalDebtDollars = null,
            totalCashDollars = null,
        )
        val analysis = DcfAnalysisEngine.compute(
            fundamentals = fundamentals,
            timeseries = completeTimeseries(),
        ).getOrThrow()

        assertEquals(WaccFieldSource.AssumedZero, analysis.waccInputs.totalDebt)
        assertEquals(WaccFieldSource.AssumedZero, analysis.waccInputs.totalCash)
        // No debt weight => cost of debt default is not surfaced as provisional noise.
        assertEquals(WaccFieldSource.Reported, analysis.waccInputs.costOfDebt)
        assertTrue(analysis.waccInputs.summaryLabels().contains("debt=assumed 0"))
        assertTrue(analysis.waccInputs.summaryLabels().contains("cash=assumed 0"))
    }

    @Test
    fun compute_marks_default_cost_of_debt_when_debt_present_without_interest() {
        val timeseries = completeTimeseries().copy(interestExpense = emptyList())
        val analysis = DcfAnalysisEngine.compute(
            fundamentals = completeFundamentals(),
            timeseries = timeseries,
        ).getOrThrow()

        assertEquals(WaccFieldSource.Default, analysis.waccInputs.costOfDebt)
        assertTrue(analysis.waccInputs.summaryLabels().contains("cost of debt=default"))
    }

    @Test
    fun compute_without_market_cap_or_price_fails_clearly() {
        val fundamentals = completeFundamentals().copy(marketCapDollars = null)
        val result = DcfAnalysisEngine.compute(
            fundamentals = fundamentals,
            timeseries = completeTimeseries(),
            marketPriceCents = null,
        )

        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull()?.message)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("market cap"))
    }

    @Test
    fun compute_marks_wacc_clamped_when_raw_wacc_outside_bounds() {
        // Very high beta pushes unclamped cost of equity above MAX_WACC (18%).
        val fundamentals = completeFundamentals().copy(
            betaMillis = 10_000, // beta 10.0
            totalDebtDollars = 0L,
            totalCashDollars = 0L,
        )
        val analysis = DcfAnalysisEngine.compute(
            fundamentals = fundamentals,
            timeseries = completeTimeseries().copy(interestExpense = emptyList()),
        ).getOrThrow()

        assertEquals(1_800, analysis.waccBps)
        assertTrue(analysis.waccInputs.waccClamped)
        assertTrue(analysis.waccInputs.summaryLabels().contains("wacc=clamped"))
    }

    private fun completeFundamentals() = FundamentalSnapshot(
        symbol = "NVDA",
        marketCapDollars = 1_200_000_000L,
        sharesOutstanding = 100_000_000L,
        totalDebtDollars = 120_000_000L,
        totalCashDollars = 20_000_000L,
        betaMillis = 1_100,
        freeCashFlowDollars = 86_000_000L,
    )

    private fun completeTimeseries() = FundamentalTimeseries(
        freeCashFlow = listOf(
            AnnualReportedValue("2021-12-31", 50_000_000.0),
            AnnualReportedValue("2022-12-31", 60_000_000.0),
            AnnualReportedValue("2023-12-31", 72_000_000.0),
            AnnualReportedValue("2024-12-31", 86_000_000.0),
        ),
        dilutedAverageShares = listOf(
            AnnualReportedValue("2021-12-31", 100_000_000.0),
            AnnualReportedValue("2022-12-31", 100_000_000.0),
            AnnualReportedValue("2023-12-31", 100_000_000.0),
            AnnualReportedValue("2024-12-31", 100_000_000.0),
        ),
        interestExpense = listOf(AnnualReportedValue("2024-12-31", 8_000_000.0)),
        taxRateForCalcs = listOf(AnnualReportedValue("2024-12-31", 0.21)),
    )
}
