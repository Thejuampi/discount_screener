package com.discountscreener.core.engine

import com.discountscreener.core.model.AnnualReportedValue
import com.discountscreener.core.model.BusinessClass
import com.discountscreener.core.model.DiscountRateKind
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.FundamentalTimeseries
import com.discountscreener.core.model.ValuationHonesty
import com.discountscreener.core.model.ValuationModel
import com.discountscreener.core.model.WaccFieldSource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DcfAnalysisEngineTest {
    @Test
    fun mixed_filing_uses_component_sum() {
        var years = listOf("2023-12-31", "2024-12-31", "2025-12-31")
        var analysis = DcfAnalysisEngine.compute(
            fundamentals = completeFundamentals().copy(
                symbol = "OEM",
                sectorName = "Consumer Cyclical",
                industryName = "Auto Manufacturers",
                marketCapDollars = 50_000_000_000L,
                sharesOutstanding = 1_000_000_000L,
                totalDebtDollars = 16_000_000_000L,
                totalCashDollars = 8_000_000_000L,
            ),
            timeseries = completeTimeseries().copy(
                taxRateForCalcs = years.map { AnnualReportedValue(it, 0.21, concept = "JurisdictionStatutory") },
                marginalTaxRate = years.map { AnnualReportedValue(it, 0.21, concept = "JurisdictionStatutory") },
            ),
            marketParams = MarketParams(provisional = false),
            components = IssuerComponentSet(
                operating = OperatingComponentDrivers(
                    revenue = years.mapIndexed { i, d -> AnnualReportedValue(d, (100.0 + i * 10) * 1_000_000_000.0) },
                    ebit = years.mapIndexed { i, d -> AnnualReportedValue(d, (15.0 + i) * 1_000_000_000.0) },
                    capex = years.map { AnnualReportedValue(it, 8_000_000_000.0) },
                    interest = years.map { AnnualReportedValue(it, 700_000_000.0) },
                    debt = years.map { AnnualReportedValue(it, 16_000_000_000.0) },
                    cash = years.map { AnnualReportedValue(it, 8_000_000_000.0) },
                    da = years.map { AnnualReportedValue(it, 5_000_000_000.0) },
                ),
                financial = FinancialComponentDrivers(
                    bookEquity = listOf(AnnualReportedValue("2025-12-31", 15_000_000_000.0)),
                    netIncome = listOf(AnnualReportedValue("2025-12-31", 2_000_000_000.0)),
                    dividends = listOf(AnnualReportedValue("2025-12-31", 500_000_000.0)),
                    source = "subsidiary_companyfacts",
                ),
                provenance = listOf("component_sotp=$COMPONENT_SOTP_VERSION"),
            ),
        ).getOrThrow()
        assertEquals(ValuationModel.ComponentSum, analysis.model)
    }

    @Test
    fun mixed_filing_stamps_nopat_plus_depreciation() {
        var years = listOf("2023-12-31", "2024-12-31", "2025-12-31")
        var analysis = DcfAnalysisEngine.compute(
            fundamentals = completeFundamentals().copy(
                symbol = "OEM",
                sectorName = "Consumer Cyclical",
                industryName = "Auto Manufacturers",
                marketCapDollars = 50_000_000_000L,
                sharesOutstanding = 1_000_000_000L,
                totalDebtDollars = 16_000_000_000L,
                totalCashDollars = 8_000_000_000L,
            ),
            timeseries = completeTimeseries().copy(
                taxRateForCalcs = years.map { AnnualReportedValue(it, 0.21, concept = "JurisdictionStatutory") },
                marginalTaxRate = years.map { AnnualReportedValue(it, 0.21, concept = "JurisdictionStatutory") },
            ),
            marketParams = MarketParams(provisional = false),
            components = mixedComponents(years),
        ).getOrThrow()
        assertEquals(true, analysis.reasonCodes.contains("operating_fcff=nopat_plus_da_minus_sustaining_capex"))
    }

    @Test
    fun factory_nonpositive_margin_keeps_the_lender() {
        var years = listOf("2023-12-31", "2024-12-31", "2025-12-31")
        var analysis = DcfAnalysisEngine.compute(
            fundamentals = completeFundamentals().copy(
                symbol = "OEM",
                sectorName = "Consumer Cyclical",
                industryName = "Auto Manufacturers",
                marketCapDollars = 50_000_000_000L,
                sharesOutstanding = 1_000_000_000L,
            ),
            timeseries = completeTimeseries().copy(
                taxRateForCalcs = years.map { AnnualReportedValue(it, 0.21, concept = "JurisdictionStatutory") },
                marginalTaxRate = years.map { AnnualReportedValue(it, 0.21, concept = "JurisdictionStatutory") },
            ),
            marketParams = MarketParams(provisional = false),
            components = IssuerComponentSet(
                operating = OperatingComponentDrivers(
                    revenue = years.map { AnnualReportedValue(it, 100_000_000_000.0) },
                    ebit = years.map { AnnualReportedValue(it, 1_000_000_000.0) },
                    capex = years.map { AnnualReportedValue(it, 20_000_000_000.0) },
                    interest = years.map { AnnualReportedValue(it, 700_000_000.0) },
                    debt = years.map { AnnualReportedValue(it, 16_000_000_000.0) },
                    da = years.map { AnnualReportedValue(it, 100_000_000.0) },
                ),
                financial = FinancialComponentDrivers(
                    bookEquity = listOf(AnnualReportedValue("2025-12-31", 15_000_000_000.0)),
                    netIncome = listOf(AnnualReportedValue("2025-12-31", 2_000_000_000.0)),
                    dividends = listOf(AnnualReportedValue("2025-12-31", 500_000_000.0)),
                    source = "subsidiary_companyfacts",
                ),
                provenance = listOf("component_sotp=$COMPONENT_SOTP_VERSION"),
            ),
        ).getOrThrow()
        assertEquals(ValuationModel.ComponentSum, analysis.model)
    }

    @Test
    fun factory_nonpositive_margin_stamps_the_factory_hole() {
        var years = listOf("2023-12-31", "2024-12-31", "2025-12-31")
        var analysis = DcfAnalysisEngine.compute(
            fundamentals = completeFundamentals().copy(
                symbol = "OEM",
                sectorName = "Consumer Cyclical",
                industryName = "Auto Manufacturers",
                marketCapDollars = 50_000_000_000L,
                sharesOutstanding = 1_000_000_000L,
            ),
            timeseries = completeTimeseries().copy(
                taxRateForCalcs = years.map { AnnualReportedValue(it, 0.21, concept = "JurisdictionStatutory") },
                marginalTaxRate = years.map { AnnualReportedValue(it, 0.21, concept = "JurisdictionStatutory") },
            ),
            marketParams = MarketParams(provisional = false),
            components = IssuerComponentSet(
                operating = OperatingComponentDrivers(
                    revenue = years.map { AnnualReportedValue(it, 100_000_000_000.0) },
                    ebit = years.map { AnnualReportedValue(it, 1_000_000_000.0) },
                    capex = years.map { AnnualReportedValue(it, 20_000_000_000.0) },
                    interest = years.map { AnnualReportedValue(it, 700_000_000.0) },
                    debt = years.map { AnnualReportedValue(it, 16_000_000_000.0) },
                    da = years.map { AnnualReportedValue(it, 100_000_000.0) },
                ),
                financial = FinancialComponentDrivers(
                    bookEquity = listOf(AnnualReportedValue("2025-12-31", 15_000_000_000.0)),
                    netIncome = listOf(AnnualReportedValue("2025-12-31", 2_000_000_000.0)),
                    dividends = listOf(AnnualReportedValue("2025-12-31", 500_000_000.0)),
                    source = "subsidiary_companyfacts",
                ),
                provenance = listOf("component_sotp=$COMPONENT_SOTP_VERSION"),
            ),
        ).getOrThrow()
        assertEquals(true, analysis.reasonCodes.contains("factory_fcff=non_positive_margin"))
    }

    @Test
    fun lender_retention_bps_do_not_need_a_dividend_row() {
        var years = listOf("2023-12-31", "2024-12-31", "2025-12-31")
        var analysis = DcfAnalysisEngine.compute(
            fundamentals = completeFundamentals().copy(
                symbol = "OEM",
                sectorName = "Consumer Cyclical",
                industryName = "Auto Manufacturers",
                marketCapDollars = 50_000_000_000L,
                sharesOutstanding = 1_000_000_000L,
            ),
            timeseries = completeTimeseries().copy(
                taxRateForCalcs = years.map { AnnualReportedValue(it, 0.21, concept = "JurisdictionStatutory") },
                marginalTaxRate = years.map { AnnualReportedValue(it, 0.21, concept = "JurisdictionStatutory") },
            ),
            marketParams = MarketParams(provisional = false),
            components = mixedComponents(years).copy(
                financial = FinancialComponentDrivers(
                    bookEquity = listOf(AnnualReportedValue("2025-12-31", 15_000_000_000.0)),
                    netIncome = listOf(AnnualReportedValue("2025-12-31", 2_000_000_000.0)),
                    source = "subsidiary_companyfacts",
                    retentionBps = 7_500,
                ),
            ),
        ).getOrThrow()
        assertEquals(ValuationModel.ComponentSum, analysis.model)
    }

    @Test
    fun gm_2025_shape_uses_component_sum() {
        var analysis = DcfAnalysisEngine.compute(
            fundamentals = completeFundamentals().copy(
                symbol = "OEM",
                sectorName = "Consumer Cyclical",
                industryName = "Auto Manufacturers",
                marketCapDollars = 50_000_000_000L,
                sharesOutstanding = 960_000_000L,
                totalDebtDollars = 16_247_000_000L,
                totalCashDollars = 20_945_000_000L,
            ),
            timeseries = gm2025Timeseries(),
            marketParams = MarketParams(provisional = false),
            components = gm2025Components(),
        ).getOrThrow()
        assertEquals(ValuationModel.ComponentSum, analysis.model)
    }

    @Test
    fun gm_2025_shape_prints_a_positive_base() {
        var analysis = DcfAnalysisEngine.compute(
            fundamentals = completeFundamentals().copy(
                symbol = "OEM",
                sectorName = "Consumer Cyclical",
                industryName = "Auto Manufacturers",
                marketCapDollars = 50_000_000_000L,
                sharesOutstanding = 960_000_000L,
                totalDebtDollars = 16_247_000_000L,
                totalCashDollars = 20_945_000_000L,
            ),
            timeseries = gm2025Timeseries(),
            marketParams = MarketParams(provisional = false),
            components = gm2025Components(),
        ).getOrThrow()
        assertEquals(true, analysis.baseIntrinsicValueCents > 0L)
    }

    @Test
    fun gm_2025_shape_stays_below_the_mixed_cash_print() {
        var analysis = DcfAnalysisEngine.compute(
            fundamentals = completeFundamentals().copy(
                symbol = "OEM",
                sectorName = "Consumer Cyclical",
                industryName = "Auto Manufacturers",
                marketCapDollars = 50_000_000_000L,
                sharesOutstanding = 960_000_000L,
                totalDebtDollars = 16_247_000_000L,
                totalCashDollars = 20_945_000_000L,
            ),
            timeseries = gm2025Timeseries(),
            marketParams = MarketParams(provisional = false),
            components = gm2025Components(),
        ).getOrThrow()
        assertEquals(true, analysis.baseIntrinsicValueCents < 46_392L)
    }

    @Test
    fun gm_2025_shape_stamps_through_cycle_auto() {
        var analysis = DcfAnalysisEngine.compute(
            fundamentals = completeFundamentals().copy(
                symbol = "OEM",
                sectorName = "Consumer Cyclical",
                industryName = "Auto Manufacturers",
                marketCapDollars = 50_000_000_000L,
                sharesOutstanding = 960_000_000L,
                totalDebtDollars = 16_247_000_000L,
                totalCashDollars = 20_945_000_000L,
            ),
            timeseries = gm2025Timeseries(),
            marketParams = MarketParams(provisional = false),
            components = gm2025Components(),
        ).getOrThrow()
        assertEquals(true, analysis.reasonCodes.any { it.startsWith("path=through_cycle_auto:") })
    }

    @Test
    fun gm_2025_shape_sits_below_the_prior_identity() {
        var analysis = DcfAnalysisEngine.compute(
            fundamentals = completeFundamentals().copy(
                symbol = "OEM",
                sectorName = "Consumer Cyclical",
                industryName = "Auto Manufacturers",
                marketCapDollars = 50_000_000_000L,
                sharesOutstanding = 960_000_000L,
                totalDebtDollars = 16_247_000_000L,
                totalCashDollars = 20_945_000_000L,
            ),
            timeseries = gm2025Timeseries(),
            marketParams = MarketParams(provisional = false),
            components = gm2025Components(),
        ).getOrThrow()
        assertEquals(true, analysis.baseIntrinsicValueCents < 17_122L)
    }

    @Test
    fun mixed_filing_without_lender_book_stays_unavailable() {
        var result = DcfAnalysisEngine.compute(
            fundamentals = completeFundamentals().copy(
                sectorName = "Consumer Cyclical",
                industryName = "Auto Manufacturers",
            ),
            timeseries = completeTimeseries(),
            marketParams = MarketParams(provisional = false),
            components = IssuerComponentSet(
                operating = OperatingComponentDrivers(
                    revenue = listOf(AnnualReportedValue("2025-12-31", 100.0)),
                    ebit = listOf(AnnualReportedValue("2025-12-31", 10.0)),
                    capex = listOf(AnnualReportedValue("2025-12-31", 5.0)),
                    interest = emptyList(),
                    debt = emptyList(),
                ),
                financial = null,
                provenance = emptyList(),
                financeArmMaterial = true,
            ),
        )
        assertTrue(result.isFailure)
    }

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
        assertEquals(WaccFieldSource.JurisdictionStatutory, analysis.waccInputs.taxRate)
        assertEquals(ValuationModel.FcffWacc, analysis.model)
        assertTrue(analysis.baseIntrinsicValueCents > 0L)
        assertTrue(!analysis.pointEstimateUnreliable)
        assertEquals(ENGINE_VERSION, analysis.engineVersion)
        assertEquals(MODEL_POLICY_VERSION, analysis.modelPolicyVersion)
    }

    @Test
    fun decelerating_double_digit_growth_stays_secular() {
        var analysis = DcfAnalysisEngine.compute(
            fundamentals = completeFundamentals().copy(
                symbol = "DASH",
                sectorName = "Consumer Cyclical",
                industryName = "Internet Retail",
                returnOnEquityBps = 889,
                retentionBps = 10_000,
            ),
            timeseries = deceleratingInternetRetailTimeseries(),
            marketParams = MarketParams(provisional = false),
        ).getOrThrow()
        assertEquals("secular_expansion", analysis.driverRegime)
    }

    @Test
    fun driver_based_fcff_is_typed_honest() {
        var analysis = DcfAnalysisEngine.compute(
            fundamentals = completeFundamentals(),
            timeseries = completeTimeseries(),
            marketParams = MarketParams(provisional = false),
        ).getOrThrow()
        assertEquals(ValuationHonesty.Honest, analysis.honesty)
    }

    @Test
    fun missing_interest_without_period_debt_still_forms_fcff() {
        var years = listOf("2022-12-31", "2023-12-31", "2024-12-31", "2025-12-31")
        var analysis = DcfAnalysisEngine.compute(
            fundamentals = completeFundamentals().copy(
                sectorName = "Consumer Cyclical",
                industryName = "Internet Retail",
                totalDebtDollars = 0L,
            ),
            timeseries = completeTimeseries().copy(
                freeCashFlow = years.mapIndexed { index, date ->
                    AnnualReportedValue(date, (50 + index * 10) * 1_000_000.0)
                },
                operatingCashFlow = years.mapIndexed { index, date ->
                    AnnualReportedValue(date, (70 + index * 10) * 1_000_000.0)
                },
                capitalExpenditure = years.map { AnnualReportedValue(it, -20_000_000.0) },
                revenue = years.mapIndexed { index, date ->
                    AnnualReportedValue(date, (200 + index * 20) * 1_000_000.0)
                },
                interestExpense = listOf(AnnualReportedValue("2022-12-31", 2_000_000.0)),
                taxRateForCalcs = years.map {
                    AnnualReportedValue(it, 0.21, concept = "JurisdictionStatutory")
                },
                totalDebt = emptyList(),
                dilutedAverageShares = years.map { AnnualReportedValue(it, 100_000_000.0) },
                pretaxIncome = years.mapIndexed { index, date ->
                    AnnualReportedValue(date, (40 + index * 10) * 1_000_000.0)
                },
                marginalTaxRate = years.map {
                    AnnualReportedValue(it, 0.21, concept = "JurisdictionStatutory")
                },
            ),
            marketParams = MarketParams(provisional = false),
        ).getOrThrow()
        assertTrue(analysis.baseIntrinsicValueCents > 0L)
    }

    @Test
    fun filing_fy_collision_with_recent_interest_gap_still_forms_fcff() {
        var dates = listOf(
            "2020-09-26",
            "2021-09-25",
            "2022-09-24",
            "2023-09-30",
            "2024-09-28",
            "2025-09-27",
        )
        var analysis = DcfAnalysisEngine.compute(
            fundamentals = completeFundamentals().copy(
                symbol = "AAPL",
                sectorName = "Technology",
                industryName = "Consumer Electronics",
                totalDebtDollars = 90_000_000_000L,
                totalCashDollars = 60_000_000_000L,
                marketCapDollars = 3_000_000_000_000L,
                sharesOutstanding = 15_000_000_000L,
            ),
            timeseries = appleLikeTimeseries(dates),
            marketParams = MarketParams(provisional = false),
        ).getOrThrow()
        assertTrue(analysis.baseIntrinsicValueCents > 0L)
    }

    @Test
    fun formed_identity_names_estimated_interest_years() {
        var dates = listOf(
            "2020-09-26",
            "2021-09-25",
            "2022-09-24",
            "2023-09-30",
            "2024-09-28",
            "2025-09-27",
        )
        var analysis = DcfAnalysisEngine.compute(
            fundamentals = completeFundamentals().copy(
                symbol = "AAPL",
                sectorName = "Technology",
                industryName = "Consumer Electronics",
                totalDebtDollars = 90_000_000_000L,
                totalCashDollars = 60_000_000_000L,
                marketCapDollars = 3_000_000_000_000L,
                sharesOutstanding = 15_000_000_000L,
            ),
            timeseries = appleLikeTimeseries(dates),
            marketParams = MarketParams(provisional = false),
        ).getOrThrow()
        assertTrue(
            analysis.reasonCodes.any {
                it.startsWith("interest=estimated:own_effective_rate:medium:") &&
                    it.contains("2024-09-28") &&
                    it.contains("2025-09-27")
            },
        )
    }

    @Test
    fun formed_identity_stamps_debt_resolution() {
        var dates = listOf(
            "2020-09-26",
            "2021-09-25",
            "2022-09-24",
            "2023-09-30",
            "2024-09-28",
            "2025-09-27",
        )
        var analysis = DcfAnalysisEngine.compute(
            fundamentals = completeFundamentals().copy(
                symbol = "AAPL",
                sectorName = "Technology",
                industryName = "Consumer Electronics",
                totalDebtDollars = 90_000_000_000L,
                totalCashDollars = 60_000_000_000L,
                marketCapDollars = 3_000_000_000_000L,
                sharesOutstanding = 15_000_000_000L,
            ),
            timeseries = appleLikeTimeseries(dates),
            marketParams = MarketParams(provisional = false),
        ).getOrThrow()
        assertEquals(true, analysis.reasonCodes.contains("debt=$DEBT_RESOLUTION_VERSION"))
    }

    @Test
    fun formed_identity_stamps_issuer_yield_policy() {
        var dates = listOf("2021-09-25", "2022-09-24", "2023-09-30")
        var analysis = DcfAnalysisEngine.compute(
            fundamentals = completeFundamentals().copy(
                sectorName = "Technology",
                industryName = "Consumer Electronics",
                totalDebtDollars = 90_000_000_000L,
                totalCashDollars = 60_000_000_000L,
                marketCapDollars = 3_000_000_000_000L,
                sharesOutstanding = 15_000_000_000L,
            ),
            timeseries = appleLikeTimeseries(dates),
            marketParams = MarketParams(provisional = false),
        ).getOrThrow()
        assertEquals(true, analysis.reasonCodes.contains("issuer_yield=$ISSUER_MARKET_YIELD_VERSION"))
    }

    @Test
    fun net_debt_that_wipes_equity_refuses() {
        var result = DcfAnalysisEngine.compute(
            fundamentals = completeFundamentals().copy(
                marketCapDollars = 100_000_000_000L,
                totalDebtDollars = 500_000_000_000L,
                totalCashDollars = 0L,
            ),
            timeseries = completeTimeseries(),
            marketParams = MarketParams(provisional = false),
        )
        assertTrue(
            result.exceptionOrNull()?.message.orEmpty().contains("equity wiped"),
        )
    }

    @Test
    fun material_acquisition_uses_zero_near_term_growth_not_a_refusal() {
        val analysis = DcfAnalysisEngine.compute(
            fundamentals = completeFundamentals(),
            timeseries = completeTimeseries().copy(
                acquisitionInvestment = listOf(
                    AnnualReportedValue("2024-12-31", 23_000_000.0),
                ),
            ),
            marketParams = MarketParams(provisional = false),
        ).getOrThrow()

        assertEquals(0, analysis.baseGrowthBps)
        assertEquals("acquisition_normalized", analysis.driverRegime)
    }

    @Test
    fun historical_acquisition_excludes_only_its_growth_transition() {
        val analysis = DcfAnalysisEngine.compute(
            fundamentals = completeFundamentals(),
            timeseries = completeTimeseries().copy(
                revenue = listOf(
                    AnnualReportedValue("2021-12-31", 200_000_000.0),
                    AnnualReportedValue("2022-12-31", 220_000_000.0),
                    AnnualReportedValue("2023-12-31", 242_000_000.0),
                    AnnualReportedValue("2024-12-31", 266_200_000.0),
                ),
                acquisitionInvestment = listOf(
                    AnnualReportedValue("2022-12-31", 30_000_000.0),
                ),
            ),
            marketParams = MarketParams(provisional = false),
        ).getOrThrow()

        assertTrue(analysis.baseGrowthBps > 0)
        assertTrue(analysis.driverRegime != "acquisition_normalized")
        assertTrue(
            analysis.reasonCodes.any {
                it == "growth=acquisition_contaminated_years_excluded:2022"
            },
        )
    }

    @Test
    fun mu_cycle_uses_median_aligned_fcff_margin_and_retains_negative_year() {
        val years = listOf("2023-08-31", "2024-08-31", "2025-08-31")
        val analysis = DcfAnalysisEngine.compute(
            fundamentals = FundamentalSnapshot(
                symbol = "MU",
                sectorName = "Technology",
                industryName = "Semiconductors",
                marketCapDollars = 95_917_795_000L,
                sharesOutstanding = 1_129_393_151L,
                betaMillis = 1_200,
                totalDebtDollars = 14_577_000_000L,
                totalCashDollars = 12_000_000_000L,
            ),
            timeseries = FundamentalTimeseries(
                freeCashFlow = listOf(
                    AnnualReportedValue(years[0], -6_117_000_000.0),
                    AnnualReportedValue(years[1], 121_000_000.0),
                    AnnualReportedValue(years[2], 1_668_000_000.0),
                ),
                operatingCashFlow = listOf(
                    AnnualReportedValue(years[0], 1_559_000_000.0),
                    AnnualReportedValue(years[1], 8_507_000_000.0),
                    AnnualReportedValue(years[2], 17_525_000_000.0),
                ),
                capitalExpenditure = listOf(
                    AnnualReportedValue(years[0], -7_676_000_000.0),
                    AnnualReportedValue(years[1], -8_386_000_000.0),
                    AnnualReportedValue(years[2], -15_857_000_000.0),
                ),
                revenue = listOf(
                    AnnualReportedValue(years[0], 15_540_000_000.0),
                    AnnualReportedValue(years[1], 25_111_000_000.0),
                    AnnualReportedValue(years[2], 37_378_000_000.0),
                ),
                interestExpense = listOf(
                    AnnualReportedValue(years[0], 388_000_000.0),
                    AnnualReportedValue(years[1], 562_000_000.0),
                    AnnualReportedValue(years[2], 477_000_000.0),
                ),
                taxRateForCalcs = listOf(
                    AnnualReportedValue(years[0], 0.0313),
                    AnnualReportedValue(years[1], 0.35),
                    AnnualReportedValue(years[2], 0.1164),
                ),
                totalDebt = listOf(
                    AnnualReportedValue(years[0], 13_330_000_000.0),
                    AnnualReportedValue(years[1], 13_397_000_000.0),
                    AnnualReportedValue(years[2], 14_577_000_000.0),
                ),
                marginalTaxRate = years.map {
                    AnnualReportedValue(it, 0.21, concept = "JurisdictionStatutory")
                },
            ),
            marketPriceCents = 83_398,
        ).getOrThrow()

        assertTrue(analysis.baseIntrinsicValueCents > 0L)
        assertTrue((analysis.normalizedFcffDollars ?: 0L) > 0L)
        assertTrue((analysis.latestFcfDollars ?: 0L) > 0L)
        assertTrue(
            analysis.reasonCodes.any {
                it.startsWith("fcff_margin=median_nonneg_aligned_annual:") ||
                    it.startsWith("fcff_margin=owner_earnings_ocf_minus_maintenance:")
            },
        )
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
        assertEquals(WaccFieldSource.JurisdictionStatutory, analysis.waccInputs.taxRate)
        assertTrue(!analysis.waccInputs.isProvisional())
        assertTrue(analysis.waccInputs.summaryLabels().none { it.contains("beta=default") })
        assertTrue(analysis.waccInputs.summaryLabels().none { it.contains("tax=") })
    }

    @Test
    fun dvn_class_through_cycle_industry_prior_raises_coe_above_pure_trailing() {
        val fundamentals = FundamentalSnapshot(
            symbol = "DVN",
            sectorName = "Energy",
            industryName = "Oil & Gas E&P",
            sectorKey = "energy",
            industryKey = "oil-gas-e-p",
            betaMillis = 430,
        )
        val params = MarketParams(rfBps = 430, erpBps = 450, provisional = false)
        val resolved = DcfAnalysisEngine.resolveCostOfEquity(fundamentals, params)
        val pure = DcfAnalysisEngine.pureTrailingCostOfEquityBps(430, params)

        assertEquals(1_500, resolved.industryBetaMillis)
        assertTrue(resolved.throughCyclePrior)
        assertEquals("oil_gas_ep", resolved.industryBetaEntryId)
        assertEquals(INDUSTRY_BETA_POLICY_VERSION, resolved.industryBetaPolicyVersion)
        assertEquals(782, resolved.costOfEquityBps)
        assertEquals(624, pure)
        assertTrue(resolved.costOfEquityBps > pure)
        assertTrue(resolved.sourceFingerprint.contains(INDUSTRY_BETA_POLICY_VERSION))
        assertTrue(resolved.sourceFingerprint.contains("through_cycle=true"))
        assertTrue(!resolved.sourceFingerprint.contains("price"))
        assertTrue(!resolved.sourceFingerprint.contains("target"))
    }

    @Test
    fun software_control_industry_prior_stable_within_policy() {
        val prior = resolveIndustryBetaPrior(
            sectorName = "Technology",
            industryName = "Software - Infrastructure",
            sectorKey = "technology",
            industryKey = "software-infrastructure",
        )
        assertEquals(1_200, prior.betaMillis)
        assertTrue(!prior.throughCycle)
        assertTrue(!prior.provisional)
        assertEquals("software_technology", prior.entryId)

        val fundamentals = FundamentalSnapshot(
            symbol = "SOFT",
            sectorName = "Technology",
            industryName = "Software - Infrastructure",
            sectorKey = "technology",
            industryKey = "software-infrastructure",
            betaMillis = 1_000,
        )
        val resolved = DcfAnalysisEngine.resolveCostOfEquity(
            fundamentals,
            MarketParams(rfBps = 430, erpBps = 450, provisional = false),
        )
        assertEquals(910, resolved.costOfEquityBps)
        assertTrue(!resolved.throughCyclePrior)
    }

    @Test
    fun unmapped_industry_prior_is_provisional_default() {
        val prior = resolveIndustryBetaPrior(
            sectorName = "Unknown Sector XYZ",
            industryName = "Unknown Industry XYZ",
        )
        assertEquals(1_000, prior.betaMillis)
        assertTrue(prior.provisional)
        assertEquals("default", prior.entryId)

        val fundamentals = FundamentalSnapshot(
            symbol = "UNK",
            sectorName = "Unknown Sector XYZ",
            industryName = "Unknown Industry XYZ",
            betaMillis = 800,
        )
        val resolved = DcfAnalysisEngine.resolveCostOfEquity(
            fundamentals,
            MarketParams(rfBps = 430, erpBps = 450, provisional = false),
        )
        assertEquals(820, resolved.costOfEquityBps)
        assertTrue(resolved.provisional)
    }

    @Test
    fun industry_beta_policy_entries_match_shared_contract() {
        val contractPath = resolveSharedContract("industry-beta-policy-v1.json")
        val text = Files.readString(contractPath)
        assertTrue(text.contains("\"policyVersion\": \"$INDUSTRY_BETA_POLICY_VERSION\""))
        for ((id, beta, throughCycle) in industryBetaPolicyEntrySnapshots()) {
            assertTrue(text.contains("\"id\": \"$id\""), "missing entry $id")
            assertTrue(
                text.contains("\"betaMillis\": $beta") || text.contains("\"betaMillis\":$beta"),
                "beta pin for $id",
            )
            if (throughCycle) {
                assertTrue(text.contains("\"throughCycle\": true") || text.contains("\"throughCycle\":true"))
            }
        }
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
        assertEquals(WaccFieldSource.JurisdictionStatutory, analysis.waccInputs.taxRate)
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
        assertTrue(analysis.provisionalWaccUpliftBps > 0)
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
    fun levered_provisional_policy_uses_observed_cod_with_debt_scaled_uplift() {
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
                operatingCashFlow = listOf(
                    AnnualReportedValue("2021-12-31", 18_000_000_000.0),
                    AnnualReportedValue("2022-12-31", 19_000_000_000.0),
                    AnnualReportedValue("2023-12-31", 20_000_000_000.0),
                    AnnualReportedValue("2024-12-31", 21_000_000_000.0),
                ),
                capitalExpenditure = listOf(
                    AnnualReportedValue("2021-12-31", -4_000_000_000.0),
                    AnnualReportedValue("2022-12-31", -4_000_000_000.0),
                    AnnualReportedValue("2023-12-31", -4_000_000_000.0),
                    AnnualReportedValue("2024-12-31", -4_000_000_000.0),
                ),
                revenue = listOf(
                    AnnualReportedValue("2021-12-31", 80_000_000_000.0),
                    AnnualReportedValue("2022-12-31", 84_000_000_000.0),
                    AnnualReportedValue("2023-12-31", 88_000_000_000.0),
                    AnnualReportedValue("2024-12-31", 92_000_000_000.0),
                ),
                interestExpense = listOf(
                    AnnualReportedValue("2021-12-31", 4_000_000_000.0),
                    AnnualReportedValue("2022-12-31", 4_000_000_000.0),
                    AnnualReportedValue("2023-12-31", 4_000_000_000.0),
                    AnnualReportedValue("2024-12-31", 4_000_000_000.0),
                ),
                totalDebt = listOf(
                    AnnualReportedValue("2021-12-31", 90_000_000_000.0),
                    AnnualReportedValue("2022-12-31", 90_000_000_000.0),
                    AnnualReportedValue("2023-12-31", 90_000_000_000.0),
                    AnnualReportedValue("2024-12-31", 90_000_000_000.0),
                ),
            ),
        ).getOrThrow()

        assertEquals(WaccFieldSource.InterestOverAverageDebt, analysis.waccInputs.costOfDebt)
        assertTrue(analysis.debtWeightBps > 4_000)
        assertEquals(175, analysis.provisionalWaccUpliftBps)
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
                    AnnualReportedValue("2018-12-31", 17_296_000_000.0),
                    AnnualReportedValue("2019-12-31", 21_653_000_000.0),
                    AnnualReportedValue("2020-12-31", 25_924_000_000.0),
                    AnnualReportedValue("2021-12-31", -14_726_000_000.0),
                    AnnualReportedValue("2022-12-31", -16_893_000_000.0),
                    AnnualReportedValue("2023-12-31", 32_217_000_000.0),
                    AnnualReportedValue("2024-12-31", 32_878_000_000.0),
                    AnnualReportedValue("2025-12-31", 7_695_000_000.0),
                ),
                operatingCashFlow = listOf(
                    AnnualReportedValue("2018-12-31", 30_723_000_000.0),
                    AnnualReportedValue("2019-12-31", 38_514_000_000.0),
                    AnnualReportedValue("2020-12-31", 66_064_000_000.0),
                    AnnualReportedValue("2021-12-31", 46_327_000_000.0),
                    AnnualReportedValue("2022-12-31", 46_752_000_000.0),
                    AnnualReportedValue("2023-12-31", 84_946_000_000.0),
                    AnnualReportedValue("2024-12-31", 115_877_000_000.0),
                    AnnualReportedValue("2025-12-31", 139_514_000_000.0),
                ),
                capitalExpenditure = listOf(
                    AnnualReportedValue("2018-12-31", -13_427_000_000.0),
                    AnnualReportedValue("2019-12-31", -16_861_000_000.0),
                    AnnualReportedValue("2020-12-31", -40_140_000_000.0),
                    AnnualReportedValue("2021-12-31", -61_053_000_000.0),
                    AnnualReportedValue("2022-12-31", -63_645_000_000.0),
                    AnnualReportedValue("2023-12-31", -52_729_000_000.0),
                    AnnualReportedValue("2024-12-31", -82_999_000_000.0),
                    AnnualReportedValue("2025-12-31", -131_819_000_000.0),
                ),
                revenue = listOf(
                    AnnualReportedValue("2018-12-31", 232_887_000_000.0),
                    AnnualReportedValue("2019-12-31", 280_522_000_000.0),
                    AnnualReportedValue("2020-12-31", 386_064_000_000.0),
                    AnnualReportedValue("2021-12-31", 469_822_000_000.0),
                    AnnualReportedValue("2022-12-31", 513_983_000_000.0),
                    AnnualReportedValue("2023-12-31", 574_785_000_000.0),
                    AnnualReportedValue("2024-12-31", 637_959_000_000.0),
                    AnnualReportedValue("2025-12-31", 716_924_000_000.0),
                ),
                interestExpense = listOf(
                    AnnualReportedValue("2018-12-31", 1_417_000_000.0),
                    AnnualReportedValue("2019-12-31", 1_600_000_000.0),
                    AnnualReportedValue("2020-12-31", 1_647_000_000.0),
                    AnnualReportedValue("2021-12-31", 1_809_000_000.0),
                    AnnualReportedValue("2022-12-31", 2_367_000_000.0),
                    AnnualReportedValue("2023-12-31", 3_182_000_000.0),
                    AnnualReportedValue("2024-12-31", 2_406_000_000.0),
                    AnnualReportedValue("2025-12-31", 2_274_000_000.0),
                ),
                taxRateForCalcs = listOf(
                    AnnualReportedValue("2018-12-31", 0.21),
                    AnnualReportedValue("2019-12-31", 0.21),
                    AnnualReportedValue("2020-12-31", 0.21),
                    AnnualReportedValue("2021-12-31", 0.21),
                    AnnualReportedValue("2022-12-31", 0.21),
                    AnnualReportedValue("2023-12-31", 0.189579),
                    AnnualReportedValue("2024-12-31", 0.135031),
                    AnnualReportedValue("2025-12-31", 0.196144),
                ),
                totalDebt = listOf(
                    AnnualReportedValue("2018-12-31", 150_000_000_000.0),
                    AnnualReportedValue("2019-12-31", 160_000_000_000.0),
                    AnnualReportedValue("2020-12-31", 170_000_000_000.0),
                    AnnualReportedValue("2021-12-31", 180_000_000_000.0),
                    AnnualReportedValue("2022-12-31", 190_000_000_000.0),
                    AnnualReportedValue("2023-12-31", 210_000_000_000.0),
                    AnnualReportedValue("2024-12-31", 235_540_004_864.0),
                    AnnualReportedValue("2025-12-31", 235_540_004_864.0),
                ),
                marginalTaxRate = listOf(
                    AnnualReportedValue("2018-12-31", 0.21, concept = "JurisdictionStatutory"),
                    AnnualReportedValue("2019-12-31", 0.21, concept = "JurisdictionStatutory"),
                    AnnualReportedValue("2020-12-31", 0.21, concept = "JurisdictionStatutory"),
                    AnnualReportedValue("2021-12-31", 0.21, concept = "JurisdictionStatutory"),
                    AnnualReportedValue("2022-12-31", 0.21, concept = "JurisdictionStatutory"),
                    AnnualReportedValue("2023-12-31", 0.21, concept = "JurisdictionStatutory"),
                    AnnualReportedValue("2024-12-31", 0.21, concept = "JurisdictionStatutory"),
                    AnnualReportedValue("2025-12-31", 0.21, concept = "JurisdictionStatutory"),
                ),
            ),
            marketPriceCents = 23_977,
        ).getOrThrow()

        assertEquals("driver_based_fcff", analysis.valuationDriver)
        assertEquals(7_695_000_000L, analysis.latestFcfDollars)
        assertTrue(
            (analysis.normalizedFcffDollars ?: 0L) >= 60_000_000_000L,
            "AMZN owner-earnings run-rate floor",
        )
        assertTrue(
            analysis.baseIntrinsicValueCents >= 10_000L,
            "AMZN base must clear $100",
        )
        assertEquals(1_946, analysis.normalizedOcfMarginBps)
        assertEquals(1_238, analysis.normalizedCapexIntensityBps)
        assertEquals(true, analysis.capexSpikeYears.contains(2025))
        assertTrue(analysis.baseGrowthBps > -900)
        assertEquals("revenue_growth_median:secular_expansion", analysis.growthDriver)
        assertTrue(analysis.bearIntrinsicValueCents <= analysis.baseIntrinsicValueCents)
        assertTrue(analysis.baseIntrinsicValueCents <= analysis.bullIntrinsicValueCents)
        assertTrue(analysis.reasonCodes.none { it.contains("calibration_target") || it.contains("analyst") })
    }

    @Test
    fun first_cash_ramp_uses_ocf_centre_not_latest_year() {
        var analysis = DcfAnalysisEngine.compute(
            fundamentals = completeFundamentals().copy(
                symbol = "PLT",
                sectorName = "Technology",
                industryName = "Software - Application",
                marketCapDollars = 155_000_000_000L,
                sharesOutstanding = 2_040_000_000L,
                totalDebtDollars = 14_700_000_000L,
                totalCashDollars = 5_400_000_000L,
                returnOnEquityBps = 3_716,
                bookValuePerShareCents = 1_215,
                retentionBps = 10_000,
            ),
            timeseries = firstCashRampTimeseries(),
            marketParams = MarketParams(provisional = false),
            marketPriceCents = 7_595,
        ).getOrThrow()
        assertEquals(true, analysis.reasonCodes.contains("ocf=centre_without_prior_franchise"))
    }

    @Test
    fun first_cash_ramp_keeps_the_recent_ocf_centre() {
        var analysis = DcfAnalysisEngine.compute(
            fundamentals = completeFundamentals().copy(
                symbol = "PLT",
                sectorName = "Technology",
                industryName = "Software - Application",
                marketCapDollars = 155_000_000_000L,
                sharesOutstanding = 2_040_000_000L,
                totalDebtDollars = 14_700_000_000L,
                totalCashDollars = 5_400_000_000L,
                returnOnEquityBps = 3_716,
                bookValuePerShareCents = 1_215,
                retentionBps = 10_000,
            ),
            timeseries = firstCashRampTimeseries(),
            marketParams = MarketParams(provisional = false),
            marketPriceCents = 7_595,
        ).getOrThrow()
        assertEquals(true, (analysis.normalizedOcfMarginBps ?: 0) < 1_500)
    }

    @Test
    fun latest_positive_fcff_year_keeps_identity_when_window_median_is_negative() {
        var analysis = DcfAnalysisEngine.compute(
            fundamentals = nandCycleFundamentals(),
            timeseries = nandCycleTimeseries(latestOcfDollars = 11_671_000_000.0),
            marketParams = MarketParams(provisional = false),
        ).getOrThrow()
        assertTrue(analysis.baseIntrinsicValueCents > 0L)
    }

    @Test
    fun latest_positive_fcff_year_stamps_latest_positive_run_rate() {
        var analysis = DcfAnalysisEngine.compute(
            fundamentals = nandCycleFundamentals(),
            timeseries = nandCycleTimeseries(latestOcfDollars = 11_671_000_000.0),
            marketParams = MarketParams(provisional = false),
        ).getOrThrow()
        assertEquals(
            true,
            analysis.reasonCodes.any { it.startsWith("fcff_margin=latest_positive_aligned_annual:") },
        )
    }

    @Test
    fun us_domicile_lets_yahoo_form_fcff_when_filed_tax_is_absent() {
        var analysis = DcfAnalysisEngine.compute(
            fundamentals = completeFundamentals().copy(country = "United States"),
            timeseries = completeTimeseries().copy(
                taxRateForCalcs = completeTimeseries().taxRateForCalcs.map { point ->
                    point.copy(concept = "TaxRateForCalcs")
                },
                marginalTaxRate = emptyList(),
            ),
            marketParams = MarketParams(provisional = false),
        ).getOrThrow()
        assertEquals(
            true,
            analysis.reasonCodes.any { it.startsWith("marginal_tax_source=domicile_tax_proxy") },
        )
    }

    @Test
    fun net_cash_skips_coupon_and_forms_fcff() {
        var analysis = DcfAnalysisEngine.compute(
            fundamentals = completeFundamentals().copy(
                country = "United States",
                totalDebtDollars = 100_000_000L,
                totalCashDollars = 250_000_000L,
            ),
            timeseries = completeTimeseries().copy(
                interestExpense = emptyList(),
                totalDebt = emptyList(),
                taxRateForCalcs = completeTimeseries().taxRateForCalcs.map { point ->
                    point.copy(concept = "TaxRateForCalcs")
                },
                marginalTaxRate = emptyList(),
            ),
            marketParams = MarketParams(provisional = false),
        ).getOrThrow()
        assertEquals(true, analysis.reasonCodes.contains("cost_of_debt=not_applicable_cash_covers_debt"))
    }

    @Test
    fun paccar_shape_refuses_as_mixed_issuer_not_missing_coupon() {
        var xml = javaClass.classLoader!!.getResource("xbrl/pcar-financial-services.xml")!!.readText()
        var components = IssuerComponentAssembler.fromParentFacts(
            facts = XbrlDimensionalFacts.parse(xml),
            finance = null,
        )
        var result = DcfAnalysisEngine.compute(
            fundamentals = completeFundamentals().copy(
                symbol = "PCAR",
                sectorName = "Industrials",
                industryName = "Farm & Heavy Construction Machinery",
            ),
            timeseries = completeTimeseries().copy(interestExpense = emptyList()),
            marketParams = MarketParams(provisional = false),
            components = components,
        )
        assertEquals(
            "fcff unavailable: lender book missing on a mixed issuer",
            result.exceptionOrNull()?.message,
        )
    }

    @Test
    fun software_credit_karma_shape_stays_on_fcff() {
        var xml = javaClass.classLoader!!.getResource("xbrl/pcar-financial-services.xml")!!.readText()
        var components = IssuerComponentAssembler.fromParentFacts(
            facts = XbrlDimensionalFacts.parse(xml),
            finance = null,
        )
        var analysis = DcfAnalysisEngine.compute(
            fundamentals = completeFundamentals().copy(
                symbol = "INTU",
                sectorName = "Technology",
                industryName = "Software - Application",
            ),
            timeseries = completeTimeseries(),
            marketParams = MarketParams(provisional = false),
            components = components,
        ).getOrThrow()
        assertEquals(ValuationModel.FcffWacc, analysis.model)
    }

    @Test
    fun levered_empty_interest_forms_fcff_with_yield_and_peer_coupons() {
        var analysis = DcfAnalysisEngine.compute(
            fundamentals = completeFundamentals(),
            timeseries = completeTimeseries().copy(interestExpense = emptyList()),
            marketParams = MarketParams(provisional = false),
            peerCoupons = listOf(
                PeerCouponEvidence("P1", 5.0, 100.0),
                PeerCouponEvidence("P2", 6.0, 100.0),
                PeerCouponEvidence("P3", 4.0, 100.0),
            ),
            issuerYield = IssuerYieldPoint(700, concept = "IssuerInstrumentYield:usd_4_15y_median"),
        ).getOrThrow()
        assertEquals(ValuationModel.FcffWacc, analysis.model)
    }

    @Test
    fun net_debt_without_interest_still_refuses() {
        var result = DcfAnalysisEngine.compute(
            fundamentals = completeFundamentals().copy(
                country = "United States",
                totalDebtDollars = 200_000_000L,
                totalCashDollars = 50_000_000L,
            ),
            timeseries = completeTimeseries().copy(
                interestExpense = emptyList(),
                taxRateForCalcs = completeTimeseries().taxRateForCalcs.map { point ->
                    point.copy(concept = "TaxRateForCalcs")
                },
                marginalTaxRate = emptyList(),
            ),
            marketParams = MarketParams(provisional = false),
        )
        assertEquals(true, result.isFailure)
    }

    @Test
    fun missing_country_still_refuses_without_filed_tax() {
        var result = DcfAnalysisEngine.compute(
            fundamentals = completeFundamentals(),
            timeseries = completeTimeseries().copy(
                taxRateForCalcs = completeTimeseries().taxRateForCalcs.map { point ->
                    point.copy(concept = "TaxRateForCalcs")
                },
                marginalTaxRate = emptyList(),
            ),
            marketParams = MarketParams(provisional = false),
        )
        assertEquals(true, result.isFailure)
    }

    @Test
    fun all_negative_aligned_fcff_still_refuses() {
        var result = DcfAnalysisEngine.compute(
            fundamentals = nandCycleFundamentals(),
            timeseries = nandCycleTimeseries(latestOcfDollars = -500_000_000.0),
            marketParams = MarketParams(provisional = false),
        )
        assertEquals(
            true,
            result.exceptionOrNull()?.message.orEmpty().startsWith("non_positive_normalized_fcff"),
        )
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
    fun financials_without_retention_do_not_silent_fcff_fallback() {
        val fund = FundamentalSnapshot(
            symbol = "JPM",
            sectorName = "Financial Services",
            industryName = "Banks - Diversified",
            sharesOutstanding = 2_861_450_000L,
            returnOnEquityBps = 1_620,
            bookValuePerShareCents = 11_540,
            marketCapDollars = 568_000_000_000L,
            retentionBps = null,
        )
        val result = DcfAnalysisEngine.compute(
            fundamentals = fund,
            timeseries = completeTimeseries(),
            marketPriceCents = 19_850,
        )
        assertTrue(result.isFailure)
        val msg = result.exceptionOrNull()!!.message!!
        assertTrue(
            msg.contains("retention") || msg.contains("payout"),
            "expected retention refuse, got: $msg",
        )
    }

    @Test
    fun jpm_like_bank_fixture_drivers_resolve_residual_income() {
        val fund = FundamentalSnapshot(
            symbol = "JPM",
            sectorName = "Financial Services",
            industryName = "Banks - Diversified",
            sharesOutstanding = 2_861_450_000L,
            returnOnEquityBps = 1_620,
            bookValuePerShareCents = 11_540,
            betaMillis = 1_080,
            retentionBps = 7_200,
            marketCapDollars = 568_000_000_000L,
        )
        val analysis = DcfAnalysisEngine.compute(
            fundamentals = fund,
            timeseries = completeTimeseries(),
            marketPriceCents = 19_850,
            marketParams = MarketParams(provisional = false),
        ).getOrThrow()
        assertEquals(BusinessClass.FinancialServices, analysis.businessClass)
        assertEquals(ValuationModel.ResidualIncomeEquity, analysis.model)
        assertTrue(analysis.baseIntrinsicValueCents > 0)
        assertTrue(analysis.reasonCodes.any { it.contains("retention_source=reported:7200bps") })
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
    fun compute_records_the_market_params_fingerprint() {
        var params = MarketParams.observed(rfBps = 425, asOfEpochMillis = 1_786_752_000_000L)
        var analysis = DcfAnalysisEngine.compute(
            fundamentals = completeFundamentals(),
            timeseries = completeTimeseries(),
            marketParams = params,
        ).getOrThrow()
        assertTrue(analysis.reasonCodes.contains(params.fingerprint()))
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

    private fun resolveSharedContract(fileName: String): Path {
        var current = Path.of("").toAbsolutePath()
        repeat(8) {
            val candidate = current.resolve("shared/contracts/$fileName")
            if (Files.exists(candidate)) return candidate
            current = current.parent ?: return@repeat
        }
        error("shared contract $fileName not found")
    }

    private fun gm2025Timeseries() = completeTimeseries().copy(
        dilutedAverageShares = listOf(
            AnnualReportedValue("2023-12-31", 960_000_000.0),
            AnnualReportedValue("2024-12-31", 960_000_000.0),
            AnnualReportedValue("2025-12-31", 960_000_000.0),
        ),
        taxRateForCalcs = listOf(
            AnnualReportedValue("2023-12-31", 0.21, concept = "JurisdictionStatutory"),
            AnnualReportedValue("2024-12-31", 0.21, concept = "JurisdictionStatutory"),
            AnnualReportedValue("2025-12-31", 0.21, concept = "JurisdictionStatutory"),
        ),
        marginalTaxRate = listOf(
            AnnualReportedValue("2023-12-31", 0.21, concept = "JurisdictionStatutory"),
            AnnualReportedValue("2024-12-31", 0.21, concept = "JurisdictionStatutory"),
            AnnualReportedValue("2025-12-31", 0.21, concept = "JurisdictionStatutory"),
        ),
    )

    private fun gm2025Components() = IssuerComponentSet(
        operating = OperatingComponentDrivers(
            revenue = listOf(
                AnnualReportedValue("2023-12-31", 157_495_000_000.0),
                AnnualReportedValue("2024-12-31", 171_657_000_000.0),
                AnnualReportedValue("2025-12-31", 167_745_000_000.0),
            ),
            ebit = listOf(
                AnnualReportedValue("2023-12-31", 10_821_000_000.0),
                AnnualReportedValue("2024-12-31", 13_130_000_000.0),
                AnnualReportedValue("2025-12-31", 10_916_000_000.0),
            ),
            capex = listOf(
                AnnualReportedValue("2023-12-31", 10_733_000_000.0),
                AnnualReportedValue("2024-12-31", 10_687_000_000.0),
                AnnualReportedValue("2025-12-31", 9_155_000_000.0),
            ),
            interest = listOf(
                AnnualReportedValue("2023-12-31", 911_000_000.0),
                AnnualReportedValue("2024-12-31", 846_000_000.0),
                AnnualReportedValue("2025-12-31", 727_000_000.0),
            ),
            debt = listOf(
                AnnualReportedValue("2024-12-31", 15_467_000_000.0),
                AnnualReportedValue("2025-12-31", 16_247_000_000.0),
            ),
            da = listOf(
                AnnualReportedValue("2023-12-31", 6_773_000_000.0),
                AnnualReportedValue("2024-12-31", 6_493_000_000.0),
                AnnualReportedValue("2025-12-31", 6_960_000_000.0),
            ),
        ),
        financial = FinancialComponentDrivers(
            bookEquity = listOf(AnnualReportedValue("2025-12-31", 15_813_000_000.0)),
            netIncome = listOf(AnnualReportedValue("2025-12-31", 2_058_000_000.0)),
            dividends = listOf(AnnualReportedValue("2025-12-31", 1_599_000_000.0)),
            source = "subsidiary_companyfacts:0000804269",
            cash = listOf(AnnualReportedValue("2025-12-31", 5_826_000_000.0)),
        ),
        provenance = listOf("component_sotp=$COMPONENT_SOTP_VERSION", "finance_arm=material"),
        financeArmMaterial = true,
    )

    private fun mixedComponents(years: List<String>) = IssuerComponentSet(
        operating = OperatingComponentDrivers(
            revenue = years.mapIndexed { i, d -> AnnualReportedValue(d, (100.0 + i * 10) * 1_000_000_000.0) },
            ebit = years.mapIndexed { i, d -> AnnualReportedValue(d, (15.0 + i) * 1_000_000_000.0) },
            capex = years.map { AnnualReportedValue(it, 8_000_000_000.0) },
            interest = years.map { AnnualReportedValue(it, 700_000_000.0) },
            debt = years.map { AnnualReportedValue(it, 16_000_000_000.0) },
            cash = years.map { AnnualReportedValue(it, 8_000_000_000.0) },
            da = years.map { AnnualReportedValue(it, 5_000_000_000.0) },
        ),
        financial = FinancialComponentDrivers(
            bookEquity = listOf(AnnualReportedValue("2025-12-31", 15_000_000_000.0)),
            netIncome = listOf(AnnualReportedValue("2025-12-31", 2_000_000_000.0)),
            dividends = listOf(AnnualReportedValue("2025-12-31", 500_000_000.0)),
            source = "subsidiary_companyfacts",
        ),
        provenance = listOf("component_sotp=$COMPONENT_SOTP_VERSION"),
    )

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

    private fun deceleratingInternetRetailTimeseries(): FundamentalTimeseries {
        var years = listOf(
            "2017-12-31",
            "2018-12-31",
            "2019-12-31",
            "2020-12-31",
            "2021-12-31",
            "2022-12-31",
            "2023-12-31",
            "2024-12-31",
        )
        var revenues = listOf(
            100_000_000.0,
            145_000_000.0,
            210_000_000.0,
            305_000_000.0,
            442_000_000.0,
            575_000_000.0,
            748_000_000.0,
            972_000_000.0,
        )
        return FundamentalTimeseries(
            freeCashFlow = years.mapIndexed { index, date ->
                AnnualReportedValue(date, revenues[index] * 0.16)
            },
            dilutedAverageShares = years.map { AnnualReportedValue(it, 100_000_000.0) },
            operatingCashFlow = years.mapIndexed { index, date ->
                AnnualReportedValue(date, revenues[index] * 0.18)
            },
            capitalExpenditure = years.mapIndexed { index, date ->
                AnnualReportedValue(date, -revenues[index] * 0.016)
            },
            revenue = years.mapIndexed { index, date ->
                AnnualReportedValue(date, revenues[index])
            },
            interestExpense = years.map { AnnualReportedValue(it, 2_000_000.0) },
            taxRateForCalcs = years.map {
                AnnualReportedValue(it, 0.21, concept = "JurisdictionStatutory")
            },
            totalDebt = years.map { AnnualReportedValue(it, 50_000_000.0) },
            pretaxIncome = years.mapIndexed { index, date ->
                AnnualReportedValue(date, revenues[index] * 0.08)
            },
            marginalTaxRate = years.map {
                AnnualReportedValue(it, 0.21, concept = "JurisdictionStatutory")
            },
        )
    }

    private fun appleLikeTimeseries(dates: List<String>): FundamentalTimeseries {
        fun point(
            date: String,
            value: Double,
            concept: String? = null,
        ): AnnualReportedValue {
            var filingFy = if (date >= "2023-09-30") 2025 else date.take(4).toInt()
            return AnnualReportedValue(
                asOfDate = date,
                value = value,
                periodEnd = date,
                fiscalYear = filingFy,
                concept = concept,
            )
        }
        return FundamentalTimeseries(
            freeCashFlow = dates.mapIndexed { index, date ->
                point(date, (80.0 + index * 5.0) * 1_000_000_000.0)
            },
            dilutedAverageShares = dates.map { point(it, 15_000_000_000.0) },
            operatingCashFlow = dates.mapIndexed { index, date ->
                point(date, (90.0 + index * 5.0) * 1_000_000_000.0)
            },
            capitalExpenditure = dates.map { point(it, -10_000_000_000.0) },
            revenue = dates.mapIndexed { index, date ->
                point(date, (270.0 + index * 20.0) * 1_000_000_000.0)
            },
            interestExpense = dates.filter { it <= "2023-09-30" }.map { point(it, 3_000_000_000.0) },
            taxRateForCalcs = dates.map { point(it, 0.16) },
            totalDebt = dates.map { point(it, 95_000_000_000.0) },
            pretaxIncome = dates.mapIndexed { index, date ->
                point(date, (80.0 + index * 8.0) * 1_000_000_000.0)
            },
            marginalTaxRate = dates.map {
                point(it, 0.21, concept = "EffectiveIncomeTaxRateReconciliationAtFederalStatutoryIncomeTaxRate")
            },
        )
    }

    private fun firstCashRampTimeseries(): FundamentalTimeseries {
        var rows = listOf(
            Triple("2018-12-31", 10_433_000_000.0, -1_541_000_000.0) to
                Triple(558_000_000.0, -2_099_000_000.0, 7_491_000_000.0),
            Triple("2019-12-31", 13_000_000_000.0, -4_321_000_000.0) to
                Triple(588_000_000.0, -4_909_000_000.0, 5_791_000_000.0),
            Triple("2020-12-31", 11_139_000_000.0, -2_745_000_000.0) to
                Triple(616_000_000.0, -3_361_000_000.0, 7_914_000_000.0),
            Triple("2021-12-31", 17_455_000_000.0, -445_000_000.0) to
                Triple(298_000_000.0, -743_000_000.0, 9_388_000_000.0),
            Triple("2022-12-31", 31_877_000_000.0, 642_000_000.0) to
                Triple(252_000_000.0, 390_000_000.0, 9_361_000_000.0),
            Triple("2023-12-31", 37_281_000_000.0, 3_585_000_000.0) to
                Triple(223_000_000.0, 3_362_000_000.0, 9_560_000_000.0),
            Triple("2024-12-31", 43_978_000_000.0, 7_137_000_000.0) to
                Triple(242_000_000.0, 6_895_000_000.0, 9_575_000_000.0),
            Triple("2025-12-31", 52_017_000_000.0, 10_099_000_000.0) to
                Triple(336_000_000.0, 9_763_000_000.0, 10_600_000_000.0),
        )
        return FundamentalTimeseries(
            freeCashFlow = rows.map { AnnualReportedValue(it.first.first, it.second.second) },
            operatingCashFlow = rows.map { AnnualReportedValue(it.first.first, it.first.third) },
            capitalExpenditure = rows.map { AnnualReportedValue(it.first.first, -it.second.first) },
            revenue = rows.map { AnnualReportedValue(it.first.first, it.first.second) },
            interestExpense = rows.map { AnnualReportedValue(it.first.first, 500_000_000.0) },
            taxRateForCalcs = rows.map {
                AnnualReportedValue(it.first.first, 0.21, concept = "JurisdictionStatutory")
            },
            totalDebt = rows.map { AnnualReportedValue(it.first.first, it.second.third) },
            marginalTaxRate = rows.map {
                AnnualReportedValue(it.first.first, 0.21, concept = "JurisdictionStatutory")
            },
        )
    }

    private fun nandCycleFundamentals() = completeFundamentals().copy(
        symbol = "SNDK",
        sectorName = "Technology",
        industryName = "Computer Hardware",
        sectorKey = "technology",
        industryKey = "computer-hardware",
        marketCapDollars = 217_000_000_000L,
        sharesOutstanding = 146_419_001L,
        totalDebtDollars = 201_000_000L,
        totalCashDollars = 4_762_000_000L,
        betaMillis = 1_400,
    )

    private fun nandCycleTimeseries(latestOcfDollars: Double): FundamentalTimeseries {
        var years = listOf("2023-06-30", "2024-06-28", "2025-06-27", "2026-07-03")
        var revenue = listOf(6_086_000_000.0, 6_663_000_000.0, 7_355_000_000.0, 20_248_000_000.0)
        var ocf = listOf(-713_000_000.0, -309_000_000.0, 84_000_000.0, latestOcfDollars)
        var capex = listOf(-219_000_000.0, -166_000_000.0, -204_000_000.0, -177_000_000.0)
        var interest = listOf(31_000_000.0, 40_000_000.0, 63_000_000.0, 73_000_000.0)
        var debt = listOf(0.0, 0.0, 1_849_000_000.0, 0.0)
        var shares = listOf(145_000_000.0, 145_000_000.0, 145_000_000.0, 155_000_000.0)
        fun points(values: List<Double>) = years.mapIndexed { i, date ->
            AnnualReportedValue(date, values[i])
        }
        return FundamentalTimeseries(
            freeCashFlow = years.mapIndexed { i, date ->
                AnnualReportedValue(date, ocf[i] + capex[i])
            },
            operatingCashFlow = points(ocf),
            capitalExpenditure = points(capex),
            revenue = points(revenue),
            interestExpense = points(interest),
            taxRateForCalcs = years.map {
                AnnualReportedValue(it, 0.21, concept = "JurisdictionStatutory")
            },
            totalDebt = points(debt),
            dilutedAverageShares = points(shares),
            pretaxIncome = years.mapIndexed { i, date ->
                AnnualReportedValue(date, ocf[i])
            },
            marginalTaxRate = years.map {
                AnnualReportedValue(it, 0.21, concept = "JurisdictionStatutory")
            },
        )
    }

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
            AnnualReportedValue("2021-12-31", 0.21, concept = "JurisdictionStatutory"),
            AnnualReportedValue("2022-12-31", 0.21, concept = "JurisdictionStatutory"),
            AnnualReportedValue("2023-12-31", 0.21, concept = "JurisdictionStatutory"),
            AnnualReportedValue("2024-12-31", 0.21, concept = "JurisdictionStatutory"),
        ),
        totalDebt = listOf(
            AnnualReportedValue("2021-12-31", 120_000_000.0),
            AnnualReportedValue("2022-12-31", 120_000_000.0),
            AnnualReportedValue("2023-12-31", 120_000_000.0),
            AnnualReportedValue("2024-12-31", 120_000_000.0),
        ),
        marginalTaxRate = listOf(
            AnnualReportedValue("2021-12-31", 0.21, concept = "JurisdictionStatutory"),
            AnnualReportedValue("2022-12-31", 0.21, concept = "JurisdictionStatutory"),
            AnnualReportedValue("2023-12-31", 0.21, concept = "JurisdictionStatutory"),
            AnnualReportedValue("2024-12-31", 0.21, concept = "JurisdictionStatutory"),
        ),
    )
}
