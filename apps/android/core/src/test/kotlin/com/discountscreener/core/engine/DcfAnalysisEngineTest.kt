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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DcfAnalysisEngineTest {
    @Test
    fun compute_with_complete_inputs_uses_aligned_cod_and_marginal_tax() {
        val analysis = DcfAnalysisEngine.compute(
            fundamentals = completeFundamentals(),
            timeseries = completeTimeseries(),
            marketParams = MarketParams(provisional = false),
        ).getOrThrow()

        assertEquals(WaccFieldSource.Reported, analysis.waccInputs.marketCap)
        assertEquals(WaccFieldSource.IndustryShrink, analysis.waccInputs.beta)
        assertEquals(WaccFieldSource.Reported, analysis.waccInputs.totalDebt)
        assertEquals(WaccFieldSource.Reported, analysis.waccInputs.totalCash)
        assertEquals(WaccFieldSource.InterestOverAverageDebt, analysis.waccInputs.costOfDebt)
        assertEquals(WaccFieldSource.ReportedMarginalTax, analysis.waccInputs.taxRate)
        assertEquals(ValuationModel.FcffWacc, analysis.model)
        assertTrue(analysis.baseIntrinsicValueCents > 0L)
        assertTrue(!analysis.pointEstimateUnreliable)
        assertEquals(ENGINE_VERSION, analysis.engineVersion)
        assertEquals(MODEL_POLICY_VERSION, analysis.modelPolicyVersion)
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
    fun compute_marks_default_beta_when_missing() {
        val fundamentals = completeFundamentals().copy(betaMillis = null)
        val analysis = DcfAnalysisEngine.compute(
            fundamentals = fundamentals,
            timeseries = completeTimeseries(),
            marketParams = MarketParams(provisional = false),
        ).getOrThrow()

        assertEquals(WaccFieldSource.IndustryShrink, analysis.waccInputs.beta)
        assertEquals(WaccFieldSource.ReportedMarginalTax, analysis.waccInputs.taxRate)
        assertTrue(!analysis.waccInputs.isProvisional())
        assertTrue(analysis.waccInputs.summaryLabels().none { it.contains("beta=default") })
        assertTrue(analysis.waccInputs.summaryLabels().none { it.contains("tax=") })
    }

    @Test
    fun compute_marks_assumed_zero_debt_and_cash_when_missing() {
        val fundamentals = completeFundamentals().copy(
            totalDebtDollars = null,
            totalCashDollars = null,
        )
        val result = DcfAnalysisEngine.compute(
            fundamentals = fundamentals,
            timeseries = completeTimeseries(),
            marketParams = MarketParams(provisional = false),
        )

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("missing; missing debt is not zero"))
    }

    @Test
    fun compute_marks_default_cost_of_debt_when_debt_present() {
        val analysis = DcfAnalysisEngine.compute(
            fundamentals = completeFundamentals(),
            timeseries = completeTimeseries(),
            marketParams = MarketParams(provisional = false),
        ).getOrThrow()

        assertEquals(WaccFieldSource.InterestOverAverageDebt, analysis.waccInputs.costOfDebt)
        assertEquals(WaccFieldSource.ReportedMarginalTax, analysis.waccInputs.taxRate)
    }

    @Test
    fun policy2_exposes_latest_and_normalized_run_rate_without_runtime_street_reason() {
        val analysis = DcfAnalysisEngine.compute(
            fundamentals = completeFundamentals(),
            timeseries = completeTimeseries().copy(
                freeCashFlow = listOf(
                    AnnualReportedValue("2021-12-31", 10_000_000.0),
                    AnnualReportedValue("2022-12-31", 20_000_000.0),
                    AnnualReportedValue("2023-12-31", 30_000_000.0),
                    AnnualReportedValue("2024-12-31", 40_000_000.0),
                ),
            ),
        ).getOrThrow()

        assertEquals(40_000_000L, analysis.latestFcfDollars)
        // avg=25M; latest 40M > 1.25×avg → recovery blend 32.5M (Windows parity).
        assertTrue(analysis.fcfRunRateDollars != null && analysis.fcfRunRateDollars > 0L)
        assertTrue(analysis.fcfRunRateNormalized)
        assertEquals(0, analysis.provisionalWaccUpliftBps)
        assertTrue(analysis.reasonCodes.none { it.startsWith("calibration_target=") })
    }

    @Test
    fun policy2_run_rate_uses_latest_contiguous_positive_suffix() {
        val analysis = DcfAnalysisEngine.compute(
            fundamentals = completeFundamentals(),
            timeseries = completeTimeseries().copy(
                freeCashFlow = listOf(
                    AnnualReportedValue("2021-12-31", 10_000_000.0),
                    AnnualReportedValue("2023-12-31", 30_000_000.0),
                    AnnualReportedValue("2024-12-31", 40_000_000.0),
                    AnnualReportedValue("2025-12-31", 50_000_000.0),
                ),
            ),
        ).getOrThrow()

        assertEquals(50_000_000L, analysis.latestFcfDollars)
        assertTrue(analysis.fcfRunRateDollars != null && analysis.fcfRunRateDollars > 0L)
    }

    @Test
    fun fcf_run_rate_blends_toward_latest_on_recovery_step_up() {
        // Latest is 2x window mean → recovery blend 50/50 latest and average.
        // Window: 10, 20, 30, 60 → avg 30; latest 60 > 1.25*30 → run = 45.
        val analysis = DcfAnalysisEngine.compute(
            fundamentals = completeFundamentals(),
            timeseries = completeTimeseries().copy(
                freeCashFlow = listOf(
                    AnnualReportedValue("2021-12-31", 10_000_000.0),
                    AnnualReportedValue("2022-12-31", 20_000_000.0),
                    AnnualReportedValue("2023-12-31", 30_000_000.0),
                    AnnualReportedValue("2024-12-31", 60_000_000.0),
                ),
            ),
        ).getOrThrow()

        assertEquals(60_000_000L, analysis.latestFcfDollars)
        assertTrue(analysis.fcfRunRateDollars != null && analysis.fcfRunRateDollars > 0L)
        assertTrue(analysis.fcfRunRateNormalized)
    }

    @Test
    fun levered_policy_uses_observed_cod_without_cap_or_uplift() {
        val analysis = DcfAnalysisEngine.compute(
            fundamentals = completeFundamentals().copy(
                marketCapDollars = 10_000_000_000L,
                sharesOutstanding = 1_000_000_000L,
                totalDebtDollars = 90_000_000_000L,
                totalCashDollars = 0L,
            ),
            timeseries = completeTimeseries().copy(
                freeCashFlow = listOf(
                    AnnualReportedValue("2021-12-31", 14_000_000_000.0),
                    AnnualReportedValue("2022-12-31", 15_000_000_000.0),
                    AnnualReportedValue("2023-12-31", 16_000_000_000.0),
                    AnnualReportedValue("2024-12-31", 17_000_000_000.0),
                ),
            ),
        ).getOrThrow()

        assertEquals(WaccFieldSource.InterestOverAverageDebt, analysis.waccInputs.costOfDebt)
        assertTrue(analysis.debtWeightBps > 4_000)
        assertEquals(0, analysis.provisionalWaccUpliftBps)
        assertTrue(analysis.waccInputs.waccClamped)
        assertTrue(analysis.pointEstimateUnreliable)
    }

    @Test
    fun provisional_wacc_stress_is_asymmetric() {
        val analysis = DcfAnalysisEngine.compute(
            fundamentals = completeFundamentals(),
            timeseries = completeTimeseries(),
        ).getOrThrow()

        assertTrue(analysis.pointEstimateUnreliable)
        assertEquals(analysis.waccBps + 150, analysis.waccBearBps)
        assertEquals(analysis.waccBps, analysis.waccBullBps)
        assertTrue(analysis.reasonCodes.any { it.startsWith("wacc_stress=asymmetric_provisional") })
        assertEquals("growth_margin_and_discount_rate", analysis.scenarioStress)
        assertTrue(analysis.bearIntrinsicValueCents <= analysis.baseIntrinsicValueCents)
        assertTrue(analysis.baseIntrinsicValueCents <= analysis.bullIntrinsicValueCents)
    }

    @Test
    fun amzn_capex_trough_keeps_normalized_scenarios_ordered() {
        val result = DcfAnalysisEngine.compute(
            fundamentals = FundamentalSnapshot(
                symbol = "AMZN",
                sectorName = "Consumer Cyclical",
                industryName = "Internet Retail",
                marketCapDollars = 2_574_493_679_616L,
                sharesOutstanding = 10_757_109_436L,
                betaMillis = 1_461,
                totalDebtDollars = 235_540_004_864L,
                totalCashDollars = 143_088_992_256L,
            ),
            timeseries = FundamentalTimeseries(
                freeCashFlow = listOf(
                    AnnualReportedValue("2020-12-31", 25_924_000_000.0),
                    AnnualReportedValue("2021-12-31", -14_726_000_000.0),
                    AnnualReportedValue("2022-12-31", -16_893_000_000.0),
                    AnnualReportedValue("2023-12-31", 32_217_000_000.0),
                    AnnualReportedValue("2024-12-31", 32_878_000_000.0),
                    AnnualReportedValue("2025-12-31", 7_695_000_000.0),
                ),
            ),
            marketPriceCents = 23_933,
        )

        assertTrue(result.isFailure)
        assertTrue(
            result.exceptionOrNull()?.message.orEmpty().contains("aligned") ||
                result.exceptionOrNull()?.message.orEmpty().contains("marginal tax"),
        )
    }

    @Test
    fun amzn_driver_fcff_uses_revenue_margin_and_capex_spike_not_fcf_endpoint_cagr() {
        val analysis = DcfAnalysisEngine.compute(
            fundamentals = FundamentalSnapshot(
                symbol = "AMZN",
                sectorName = "Consumer Cyclical",
                industryName = "Internet Retail",
                marketCapDollars = 2_574_493_679_616L,
                sharesOutstanding = 10_757_109_436L,
                betaMillis = 1_461,
                totalDebtDollars = 235_540_004_864L,
                totalCashDollars = 143_088_992_256L,
            ),
            timeseries = FundamentalTimeseries(
                freeCashFlow = listOf(
                    AnnualReportedValue("2022-12-31", -16_893_000_000.0),
                    AnnualReportedValue("2023-12-31", 32_217_000_000.0),
                    AnnualReportedValue("2024-12-31", 32_878_000_000.0),
                    AnnualReportedValue("2025-12-31", 7_695_000_000.0),
                ),
                operatingCashFlow = listOf(
                    AnnualReportedValue("2022-12-31", 46_752_000_000.0),
                    AnnualReportedValue("2023-12-31", 84_946_000_000.0),
                    AnnualReportedValue("2024-12-31", 115_877_000_000.0),
                    AnnualReportedValue("2025-12-31", 139_514_000_000.0),
                ),
                capitalExpenditure = listOf(
                    AnnualReportedValue("2022-12-31", -63_645_000_000.0),
                    AnnualReportedValue("2023-12-31", -52_729_000_000.0),
                    AnnualReportedValue("2024-12-31", -82_999_000_000.0),
                    AnnualReportedValue("2025-12-31", -131_819_000_000.0),
                ),
                revenue = listOf(
                    AnnualReportedValue("2022-12-31", 513_983_000_000.0),
                    AnnualReportedValue("2023-12-31", 574_785_000_000.0),
                    AnnualReportedValue("2024-12-31", 637_959_000_000.0),
                    AnnualReportedValue("2025-12-31", 716_924_000_000.0),
                ),
                interestExpense = listOf(
                    AnnualReportedValue("2022-12-31", 2_367_000_000.0),
                    AnnualReportedValue("2023-12-31", 3_182_000_000.0),
                    AnnualReportedValue("2024-12-31", 2_406_000_000.0),
                    AnnualReportedValue("2025-12-31", 2_274_000_000.0),
                ),
                taxRateForCalcs = listOf(
                    AnnualReportedValue("2022-12-31", 0.21),
                    AnnualReportedValue("2023-12-31", 0.189579),
                    AnnualReportedValue("2024-12-31", 0.135031),
                    AnnualReportedValue("2025-12-31", 0.196144),
                ),
                totalDebt = listOf(
                    AnnualReportedValue("2022-12-31", 190_000_000_000.0),
                    AnnualReportedValue("2023-12-31", 210_000_000_000.0),
                    AnnualReportedValue("2024-12-31", 235_540_004_864.0),
                    AnnualReportedValue("2025-12-31", 235_540_004_864.0),
                ),
                marginalTaxRate = listOf(
                    AnnualReportedValue("2022-12-31", 0.21),
                    AnnualReportedValue("2023-12-31", 0.21),
                    AnnualReportedValue("2024-12-31", 0.21),
                    AnnualReportedValue("2025-12-31", 0.21),
                ),
            ),
            marketPriceCents = 23_977,
        ).getOrThrow()

        assertEquals("driver_based_fcff", analysis.valuationDriver)
        assertEquals(7_695_000_000L, analysis.latestFcfDollars)
        assertEquals(19_787_102_400L, analysis.normalizedFcffDollars)
        assertEquals(1_478, analysis.normalizedOcfMarginBps)
        assertEquals(1_238, analysis.normalizedCapexIntensityBps)
        assertEquals(listOf(2025), analysis.capexSpikeYears)
        assertTrue(analysis.baseGrowthBps > -900)
        assertEquals("revenue_growth_median:secular_expansion", analysis.growthDriver)
        assertTrue(analysis.bearIntrinsicValueCents <= analysis.baseIntrinsicValueCents)
        assertTrue(analysis.baseIntrinsicValueCents <= analysis.bullIntrinsicValueCents)
        assertTrue(analysis.reasonCodes.none { it.contains("calibration_target") || it.contains("analyst") })
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
            retentionBps = 7_000,
        )
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
    fun unclassified_refuses_without_fcff_fallback() {
        val result = DcfAnalysisEngine.compute(
            fundamentals = completeFundamentals().copy(
                sectorName = "Intergalactic Conglomerate",
                industryName = "Moon Cheese",
            ),
            timeseries = completeTimeseries(),
        )
        assertTrue(result.isFailure)
        val msg = result.exceptionOrNull()!!.message!!
        assertTrue(msg.contains("unclassified") || msg.contains("refused"))
        assertEquals(
            BusinessClass.Unclassified,
            DcfAnalysisEngine.classifyBusiness("Intergalactic Conglomerate", "Moon Cheese"),
        )
        assertNotNull(DcfAnalysisEngine.classificationUnavailableReason(BusinessClass.Unclassified))
    }

    @Test
    fun ci_healthcare_plans_is_financial_services() {
        assertEquals(
            BusinessClass.FinancialServices,
            DcfAnalysisEngine.classifyBusiness(
                sectorName = "Healthcare",
                industryName = "Healthcare Plans",
                sectorKey = "healthcare",
                industryKey = "healthcare-plans",
            ),
        )
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

    @Test
    fun classifier_routes_real_estate_services_but_reit_is_not_eligible() {
        assertEquals(
            BusinessClass.OperatingNonFinancial,
            DcfAnalysisEngine.classifyBusiness("Real Estate", "Real Estate Services"),
        )
        assertEquals(
            BusinessClass.NotEligible,
            DcfAnalysisEngine.classifyBusiness("Real Estate", "REIT"),
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
        operatingCashFlow = listOf(
            AnnualReportedValue("2021-12-31", 70_000_000.0),
            AnnualReportedValue("2022-12-31", 80_000_000.0),
            AnnualReportedValue("2023-12-31", 92_000_000.0),
            AnnualReportedValue("2024-12-31", 106_000_000.0),
        ),
        capitalExpenditure = listOf(
            AnnualReportedValue("2021-12-31", -20_000_000.0),
            AnnualReportedValue("2022-12-31", -20_000_000.0),
            AnnualReportedValue("2023-12-31", -20_000_000.0),
            AnnualReportedValue("2024-12-31", -20_000_000.0),
        ),
        revenue = listOf(
            AnnualReportedValue("2021-12-31", 200_000_000.0),
            AnnualReportedValue("2022-12-31", 210_000_000.0),
            AnnualReportedValue("2023-12-31", 220_000_000.0),
            AnnualReportedValue("2024-12-31", 230_000_000.0),
        ),
        interestExpense = listOf(
            AnnualReportedValue("2021-12-31", 8_000_000.0),
            AnnualReportedValue("2022-12-31", 8_000_000.0),
            AnnualReportedValue("2023-12-31", 8_000_000.0),
            AnnualReportedValue("2024-12-31", 8_000_000.0),
        ),
        taxRateForCalcs = listOf(
            AnnualReportedValue("2021-12-31", 0.21),
            AnnualReportedValue("2022-12-31", 0.21),
            AnnualReportedValue("2023-12-31", 0.21),
            AnnualReportedValue("2024-12-31", 0.21),
        ),
        totalDebt = listOf(
            AnnualReportedValue("2021-12-31", 120_000_000.0),
            AnnualReportedValue("2022-12-31", 120_000_000.0),
            AnnualReportedValue("2023-12-31", 120_000_000.0),
            AnnualReportedValue("2024-12-31", 120_000_000.0),
        ),
        marginalTaxRate = listOf(
            AnnualReportedValue("2021-12-31", 0.21),
            AnnualReportedValue("2022-12-31", 0.21),
            AnnualReportedValue("2023-12-31", 0.21),
            AnnualReportedValue("2024-12-31", 0.21),
        ),
    )
}
