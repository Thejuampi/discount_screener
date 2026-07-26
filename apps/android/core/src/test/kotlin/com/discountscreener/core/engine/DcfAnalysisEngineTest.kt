package com.discountscreener.core.engine

import com.discountscreener.core.model.AnnualReportedValue
import com.discountscreener.core.model.BusinessClass
import com.discountscreener.core.model.DiscountRateKind
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.FundamentalTimeseries
import com.discountscreener.core.model.ValuationModel
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
            marketParams = MarketParams(provisional = false),
        ).getOrThrow()

        assertEquals(WaccFieldSource.Reported, analysis.waccInputs.marketCap)
        assertEquals(WaccFieldSource.IndustryShrink, analysis.waccInputs.beta)
        assertEquals(WaccFieldSource.Reported, analysis.waccInputs.totalDebt)
        assertEquals(WaccFieldSource.Reported, analysis.waccInputs.totalCash)
        assertEquals(WaccFieldSource.InterestOverDebt, analysis.waccInputs.costOfDebt)
        assertEquals(WaccFieldSource.Reported, analysis.waccInputs.taxRate)
        assertEquals(ValuationModel.FcffWacc, analysis.model)
        assertTrue(analysis.baseIntrinsicValueCents > 0L)
    }

    @Test
    fun compute_derives_market_cap_from_price_times_shares_when_missing() {
        val fundamentals = completeFundamentals().copy(marketCapDollars = null)
        val analysis = DcfAnalysisEngine.compute(
            fundamentals = fundamentals,
            timeseries = completeTimeseries(),
            marketPriceCents = 1_200, // $12.00
            marketParams = MarketParams(provisional = false),
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
        assertTrue(
            result.exceptionOrNull()?.message?.contains("share count") == true ||
                result.exceptionOrNull()?.message?.contains("market cap") == true,
        )
    }

    @Test
    fun compute_marks_default_beta_and_tax_when_missing() {
        val fundamentals = completeFundamentals().copy(betaMillis = null)
        val timeseries = completeTimeseries().copy(taxRateForCalcs = emptyList())
        val analysis = DcfAnalysisEngine.compute(
            fundamentals = fundamentals,
            timeseries = timeseries,
            marketParams = MarketParams(provisional = false),
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
            marketParams = MarketParams(provisional = false),
        ).getOrThrow()

        assertEquals(WaccFieldSource.AssumedZero, analysis.waccInputs.totalDebt)
        assertEquals(WaccFieldSource.AssumedZero, analysis.waccInputs.totalCash)
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
            marketParams = MarketParams(provisional = false),
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
    fun acgl_like_insurance_uses_residual_income_not_fcff() {
        val fund = FundamentalSnapshot(
            symbol = "ACGL",
            sectorName = "Financial Services",
            industryName = "Insurance - Property & Casualty",
            marketCapDollars = 36_000_000_000L,
            sharesOutstanding = 349_390_000L,
            betaMillis = 292,
            returnOnEquityBps = 2_000,
            bookValuePerShareCents = 6_511,
            priceToBookHundredths = 159,
        )
        // Float-like OCF series that previously produced ~$875 FCFF.
        val timeseries = FundamentalTimeseries(
            freeCashFlow = listOf(
                AnnualReportedValue("2022-12-31", 3_800_000_000.0),
                AnnualReportedValue("2023-12-31", 5_700_000_000.0),
                AnnualReportedValue("2024-12-31", 6_600_000_000.0),
                AnnualReportedValue("2025-12-31", 6_172_000_000.0),
            ),
        )
        val analysis = DcfAnalysisEngine.compute(
            fundamentals = fund,
            timeseries = timeseries,
            marketPriceCents = 10_336,
            marketParams = MarketParams(provisional = false),
        ).getOrThrow()

        assertEquals(BusinessClass.FinancialServices, analysis.businessClass)
        assertEquals(ValuationModel.ResidualIncomeEquity, analysis.model)
        assertEquals(DiscountRateKind.CostOfEquity, analysis.discountRateKind)
        val baseDollars = analysis.baseIntrinsicValueCents / 100.0
        assertTrue(baseDollars < 400.0, "RI base $baseDollars still absurdly high")
        assertTrue(baseDollars > 65.0)
        assertTrue(analysis.reasonCodes.any { it.contains("residual_income") })
    }

    @Test
    fun financials_without_book_do_not_silent_fcff_fallback() {
        val fund = FundamentalSnapshot(
            symbol = "BANK",
            sectorName = "Financial Services",
            industryName = "Banks - Diversified",
            sharesOutstanding = 100_000_000L,
            returnOnEquityBps = 1_200,
            marketCapDollars = 10_000_000_000L,
        )
        val result = DcfAnalysisEngine.compute(
            fundamentals = fund,
            timeseries = completeTimeseries(),
            marketPriceCents = 10_000,
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("book"))
    }

    @Test
    fun higher_rf_lowers_operating_fcff_value() {
        val low = DcfAnalysisEngine.compute(
            fundamentals = completeFundamentals(),
            timeseries = completeTimeseries(),
            marketParams = MarketParams(rfBps = 300, provisional = false),
        ).getOrThrow()
        val high = DcfAnalysisEngine.compute(
            fundamentals = completeFundamentals(),
            timeseries = completeTimeseries(),
            marketParams = MarketParams(rfBps = 600, provisional = false),
        ).getOrThrow()
        assertTrue(high.baseIntrinsicValueCents < low.baseIntrinsicValueCents)
    }

    @Test
    fun classifier_routes_insurance_and_tech() {
        assertEquals(
            BusinessClass.FinancialServices,
            DcfAnalysisEngine.classifyBusiness("Financial Services", "Insurance - Property & Casualty"),
        )
        assertEquals(
            BusinessClass.OperatingNonFinancial,
            DcfAnalysisEngine.classifyBusiness("Technology", "Software"),
        )
        assertEquals(
            BusinessClass.NotEligible,
            DcfAnalysisEngine.classifyBusiness(null, null, assetNotEquity = true),
        )
    }

    private fun completeFundamentals() = FundamentalSnapshot(
        symbol = "NVDA",
        sectorName = "Technology",
        industryName = "Semiconductors",
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
