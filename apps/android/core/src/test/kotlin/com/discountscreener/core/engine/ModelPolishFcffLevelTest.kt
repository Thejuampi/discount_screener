package com.discountscreener.core.engine

import com.discountscreener.core.harness.QuantHarness
import com.discountscreener.core.harness.QuantHarnessCases
import com.discountscreener.core.model.AnnualReportedValue
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.FundamentalTimeseries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModelPolishFcffLevelTest {
    @Test
    fun four_year_amzn_stays_clear_of_the_all_capex_collapse() {
        var data = QuantHarness.hardcoded(QuantHarnessCases.AMZN_RETAIL).load("AMZN")
        var analysis = DcfAnalysisEngine.compute(
            fundamentals = data.fundamentals,
            timeseries = data.timeseries,
            marketParams = requireNotNull(data.marketParams),
        ).getOrThrow()
        assertTrue(
            analysis.baseIntrinsicValueCents >= 14_000L,
            "AMZN 4-year base is ${analysis.baseIntrinsicValueCents}",
        )
    }

    @Test
    fun amzn_ocf_keeps_the_recovery_year_not_the_trough_median() {
        var data = QuantHarness.hardcoded(QuantHarnessCases.AMZN_RETAIL).load("AMZN")
        var analysis = DcfAnalysisEngine.compute(
            fundamentals = data.fundamentals,
            timeseries = data.timeseries,
            marketParams = requireNotNull(data.marketParams),
        ).getOrThrow()
        assertTrue(
            (analysis.normalizedOcfMarginBps ?: 0) >= 1_900,
            "AMZN OCF still hugs the 2022 trough: ${analysis.normalizedOcfMarginBps}",
        )
    }

    @Test
    fun six_year_amzn_stays_clear_of_the_all_capex_collapse() {
        var four = QuantHarness.hardcoded(QuantHarnessCases.AMZN_RETAIL).load("AMZN")
        var sixYear = DcfAnalysisEngine.compute(
            fundamentals = four.fundamentals,
            timeseries = four.timeseries.copy(
                freeCashFlow = listOf(
                    AnnualReportedValue("2020-12-31", 25_924_000_000.0),
                    AnnualReportedValue("2021-12-31", -14_726_000_000.0),
                ) + four.timeseries.freeCashFlow,
                operatingCashFlow = listOf(
                    AnnualReportedValue("2020-12-31", 66_064_000_000.0),
                    AnnualReportedValue("2021-12-31", 46_327_000_000.0),
                ) + four.timeseries.operatingCashFlow,
                capitalExpenditure = listOf(
                    AnnualReportedValue("2020-12-31", -40_140_000_000.0),
                    AnnualReportedValue("2021-12-31", -61_053_000_000.0),
                ) + four.timeseries.capitalExpenditure,
                revenue = listOf(
                    AnnualReportedValue("2020-12-31", 386_064_000_000.0),
                    AnnualReportedValue("2021-12-31", 469_822_000_000.0),
                ) + four.timeseries.revenue,
                interestExpense = listOf(
                    AnnualReportedValue("2020-12-31", 1_647_000_000.0),
                    AnnualReportedValue("2021-12-31", 1_809_000_000.0),
                ) + four.timeseries.interestExpense,
                taxRateForCalcs = listOf(
                    AnnualReportedValue("2020-12-31", 0.1184),
                    AnnualReportedValue("2021-12-31", 0.1256),
                ) + four.timeseries.taxRateForCalcs,
                totalDebt = listOf(
                    AnnualReportedValue("2020-12-31", 180_000_000_000.0),
                    AnnualReportedValue("2021-12-31", 185_000_000_000.0),
                ) + four.timeseries.totalDebt,
                marginalTaxRate = listOf(
                    AnnualReportedValue("2020-12-31", 0.21, concept = "JurisdictionStatutory"),
                    AnnualReportedValue("2021-12-31", 0.21, concept = "JurisdictionStatutory"),
                ) + four.timeseries.marginalTaxRate,
            ),
            marketParams = requireNotNull(four.marketParams),
        ).getOrThrow()
        assertTrue(
            sixYear.baseIntrinsicValueCents >= 10_000L,
            "AMZN 6-year base is ${sixYear.baseIntrinsicValueCents} OCF ${sixYear.normalizedOcfMarginBps}",
        )
    }

    @Test
    fun mature_low_growth_does_not_assume_macro_terminal() {
        var analysis = DcfAnalysisEngine.compute(
            fundamentals = FundamentalSnapshot(
                symbol = "SLOW",
                sectorName = "Communication Services",
                industryName = "Telecom Services",
                marketCapDollars = 20_000_000_000L,
                sharesOutstanding = 1_000_000_000L,
                betaMillis = 1_000,
                totalDebtDollars = 5_000_000_000L,
                totalCashDollars = 1_000_000_000L,
            ),
            timeseries = FundamentalTimeseries(
                freeCashFlow = listOf(
                    AnnualReportedValue("2022-12-31", 3_000_000_000.0),
                    AnnualReportedValue("2023-12-31", 3_030_000_000.0),
                    AnnualReportedValue("2024-12-31", 3_060_000_000.0),
                    AnnualReportedValue("2025-12-31", 3_091_000_000.0),
                ),
                operatingCashFlow = listOf(
                    AnnualReportedValue("2022-12-31", 4_000_000_000.0),
                    AnnualReportedValue("2023-12-31", 4_040_000_000.0),
                    AnnualReportedValue("2024-12-31", 4_080_000_000.0),
                    AnnualReportedValue("2025-12-31", 4_121_000_000.0),
                ),
                capitalExpenditure = listOf(
                    AnnualReportedValue("2022-12-31", -1_000_000_000.0),
                    AnnualReportedValue("2023-12-31", -1_010_000_000.0),
                    AnnualReportedValue("2024-12-31", -1_020_000_000.0),
                    AnnualReportedValue("2025-12-31", -1_030_000_000.0),
                ),
                revenue = listOf(
                    AnnualReportedValue("2022-12-31", 20_000_000_000.0),
                    AnnualReportedValue("2023-12-31", 20_200_000_000.0),
                    AnnualReportedValue("2024-12-31", 20_402_000_000.0),
                    AnnualReportedValue("2025-12-31", 20_606_000_000.0),
                ),
                interestExpense = listOf(
                    AnnualReportedValue("2022-12-31", 200_000_000.0),
                    AnnualReportedValue("2023-12-31", 200_000_000.0),
                    AnnualReportedValue("2024-12-31", 200_000_000.0),
                    AnnualReportedValue("2025-12-31", 200_000_000.0),
                ),
                taxRateForCalcs = listOf(
                    AnnualReportedValue("2022-12-31", 0.21),
                    AnnualReportedValue("2023-12-31", 0.21),
                    AnnualReportedValue("2024-12-31", 0.21),
                    AnnualReportedValue("2025-12-31", 0.21),
                ),
                totalDebt = listOf(
                    AnnualReportedValue("2022-12-31", 5_000_000_000.0),
                    AnnualReportedValue("2023-12-31", 5_000_000_000.0),
                    AnnualReportedValue("2024-12-31", 5_000_000_000.0),
                    AnnualReportedValue("2025-12-31", 5_000_000_000.0),
                ),
                marginalTaxRate = listOf(
                    AnnualReportedValue("2022-12-31", 0.21, concept = "JurisdictionStatutory"),
                    AnnualReportedValue("2023-12-31", 0.21, concept = "JurisdictionStatutory"),
                    AnnualReportedValue("2024-12-31", 0.21, concept = "JurisdictionStatutory"),
                    AnnualReportedValue("2025-12-31", 0.21, concept = "JurisdictionStatutory"),
                ),
            ),
            marketParams = MarketParams(rfBps = 463, erpBps = 442, provisional = false),
        ).getOrThrow()
        assertTrue(
            analysis.stableGrowthBps <= 150,
            "macro terminal on 1% growth: g_stable=${analysis.stableGrowthBps} g_near=${analysis.baseGrowthBps}",
        )
    }

    @Test
    fun cheap_coupon_on_thin_coverage_does_not_set_the_cost_of_debt() {
        var fundamentals = FundamentalSnapshot(
            symbol = "LEV",
            sectorName = "Communication Services",
            industryName = "Telecom Services",
            marketCapDollars = 20_000_000_000L,
            sharesOutstanding = 1_000_000_000L,
            betaMillis = 1_000,
            totalDebtDollars = 20_000_000_000L,
            totalCashDollars = 1_000_000_000L,
        )
        var rates = MarketParams(rfBps = 470, erpBps = 442, provisional = false)
        var baseSeries = FundamentalTimeseries(
            freeCashFlow = listOf(
                AnnualReportedValue("2022-12-31", 3_000_000_000.0),
                AnnualReportedValue("2023-12-31", 3_030_000_000.0),
                AnnualReportedValue("2024-12-31", 3_060_000_000.0),
                AnnualReportedValue("2025-12-31", 3_091_000_000.0),
            ),
            operatingCashFlow = listOf(
                AnnualReportedValue("2022-12-31", 4_000_000_000.0),
                AnnualReportedValue("2023-12-31", 4_040_000_000.0),
                AnnualReportedValue("2024-12-31", 4_080_000_000.0),
                AnnualReportedValue("2025-12-31", 4_121_000_000.0),
            ),
            capitalExpenditure = listOf(
                AnnualReportedValue("2022-12-31", -1_000_000_000.0),
                AnnualReportedValue("2023-12-31", -1_010_000_000.0),
                AnnualReportedValue("2024-12-31", -1_020_000_000.0),
                AnnualReportedValue("2025-12-31", -1_030_000_000.0),
            ),
            revenue = listOf(
                AnnualReportedValue("2022-12-31", 20_000_000_000.0),
                AnnualReportedValue("2023-12-31", 20_200_000_000.0),
                AnnualReportedValue("2024-12-31", 20_402_000_000.0),
                AnnualReportedValue("2025-12-31", 20_606_000_000.0),
            ),
            interestExpense = listOf(
                AnnualReportedValue("2022-12-31", 600_000_000.0),
                AnnualReportedValue("2023-12-31", 600_000_000.0),
                AnnualReportedValue("2024-12-31", 600_000_000.0),
                AnnualReportedValue("2025-12-31", 600_000_000.0),
            ),
            taxRateForCalcs = listOf(
                AnnualReportedValue("2022-12-31", 0.21),
                AnnualReportedValue("2023-12-31", 0.21),
                AnnualReportedValue("2024-12-31", 0.21),
                AnnualReportedValue("2025-12-31", 0.21),
            ),
            totalDebt = listOf(
                AnnualReportedValue("2022-12-31", 20_000_000_000.0),
                AnnualReportedValue("2023-12-31", 20_000_000_000.0),
                AnnualReportedValue("2024-12-31", 20_000_000_000.0),
                AnnualReportedValue("2025-12-31", 20_000_000_000.0),
            ),
            marginalTaxRate = listOf(
                AnnualReportedValue("2022-12-31", 0.21, concept = "JurisdictionStatutory"),
                AnnualReportedValue("2023-12-31", 0.21, concept = "JurisdictionStatutory"),
                AnnualReportedValue("2024-12-31", 0.21, concept = "JurisdictionStatutory"),
                AnnualReportedValue("2025-12-31", 0.21, concept = "JurisdictionStatutory"),
            ),
        )
        var couponOnly = DcfAnalysisEngine.compute(
            fundamentals = fundamentals,
            timeseries = baseSeries,
            marketParams = rates,
        ).getOrThrow()
        var covered = DcfAnalysisEngine.compute(
            fundamentals = fundamentals,
            timeseries = baseSeries.copy(
                pretaxIncome = listOf(
                    AnnualReportedValue("2022-12-31", 600_000_000.0),
                    AnnualReportedValue("2023-12-31", 600_000_000.0),
                    AnnualReportedValue("2024-12-31", 600_000_000.0),
                    AnnualReportedValue("2025-12-31", 600_000_000.0),
                ),
            ),
            marketParams = rates,
        ).getOrThrow()
        assertTrue(
            covered.waccBps > couponOnly.waccBps,
            "coverage still used the 300 bps coupon: covered=${covered.waccBps} coupon=${couponOnly.waccBps}",
        )
    }

    @Test
    fun growing_retailer_uses_owner_earnings_without_a_capex_spike() {
        var revenue = 100_000_000_000.0
        var years = (2022..2025).map { year ->
            var row = Triple(year, revenue, revenue * 0.05)
            revenue *= 1.05
            row
        }
        fun series(scale: Double) = years.map { (year, rev, _) ->
            AnnualReportedValue("$year-01-31", rev * scale)
        }
        var analysis = DcfAnalysisEngine.compute(
            fundamentals = FundamentalSnapshot(
                symbol = "WMT",
                sectorName = "Consumer Defensive",
                industryName = "Discount Stores",
                marketCapDollars = 400_000_000_000L,
                sharesOutstanding = 8_000_000_000L,
                betaMillis = 500,
                totalDebtDollars = 60_000_000_000L,
                totalCashDollars = 10_000_000_000L,
            ),
            timeseries = FundamentalTimeseries(
                freeCashFlow = series(0.03),
                operatingCashFlow = series(0.06),
                capitalExpenditure = series(-0.03),
                revenue = series(1.0),
                interestExpense = series(0.005),
                taxRateForCalcs = years.map { (year, _, _) ->
                    AnnualReportedValue("$year-01-31", 0.21)
                },
                totalDebt = years.map { (year, _, _) ->
                    AnnualReportedValue("$year-01-31", 60_000_000_000.0)
                },
                marginalTaxRate = years.map { (year, _, _) ->
                    AnnualReportedValue("$year-01-31", 0.21, concept = "JurisdictionStatutory")
                },
            ),
            marketParams = MarketParams(rfBps = 463, erpBps = 442, provisional = false),
        ).getOrThrow()
        assertTrue(
            analysis.reasonCodes.any { it.contains("owner_earnings") },
            "5% grower still charged full CapEx: ${analysis.reasonCodes}",
        )
    }

    @Test
    fun latest_year_without_interest_still_sets_starting_revenue() {
        fun row(date: String, value: Double) = AnnualReportedValue(date, value)
        var analysis = DcfAnalysisEngine.compute(
            fundamentals = FundamentalSnapshot(
                symbol = "AAPL",
                sectorName = "Technology",
                industryName = "Consumer Electronics",
                marketCapDollars = 3_000_000_000_000L,
                sharesOutstanding = 15_000_000_000L,
                betaMillis = 1_200,
                totalDebtDollars = 100_000_000_000L,
                totalCashDollars = 50_000_000_000L,
            ),
            timeseries = FundamentalTimeseries(
                freeCashFlow = listOf(
                    row("2022-09-24", 111_443_000_000.0),
                    row("2023-09-30", 99_584_000_000.0),
                    row("2024-09-28", 108_807_000_000.0),
                    row("2025-09-27", 98_767_000_000.0),
                ),
                operatingCashFlow = listOf(
                    row("2022-09-24", 122_151_000_000.0),
                    row("2023-09-30", 110_543_000_000.0),
                    row("2024-09-28", 118_254_000_000.0),
                    row("2025-09-27", 111_482_000_000.0),
                ),
                capitalExpenditure = listOf(
                    row("2022-09-24", -10_708_000_000.0),
                    row("2023-09-30", -10_959_000_000.0),
                    row("2024-09-28", -9_447_000_000.0),
                    row("2025-09-27", -12_715_000_000.0),
                ),
                revenue = listOf(
                    row("2022-09-24", 394_328_000_000.0),
                    row("2023-09-30", 383_285_000_000.0),
                    row("2024-09-28", 391_035_000_000.0),
                    row("2025-09-27", 416_161_000_000.0),
                ),
                interestExpense = listOf(
                    row("2022-09-24", 2_931_000_000.0),
                    row("2023-09-30", 3_933_000_000.0),
                ),
                taxRateForCalcs = listOf(
                    row("2022-09-24", 0.1620),
                    row("2023-09-30", 0.1472),
                    row("2024-09-28", 0.2410),
                    row("2025-09-27", 0.1570),
                ),
                totalDebt = listOf(
                    row("2022-09-24", 120_000_000_000.0),
                    row("2023-09-30", 111_000_000_000.0),
                    row("2024-09-28", 106_000_000_000.0),
                    row("2025-09-27", 98_000_000_000.0),
                ),
                marginalTaxRate = listOf(
                    AnnualReportedValue("2022-09-24", 0.21, concept = "JurisdictionStatutory"),
                    AnnualReportedValue("2023-09-30", 0.21, concept = "JurisdictionStatutory"),
                    AnnualReportedValue("2024-09-28", 0.21, concept = "JurisdictionStatutory"),
                    AnnualReportedValue("2025-09-27", 0.21, concept = "JurisdictionStatutory"),
                ),
            ),
            marketParams = MarketParams(rfBps = 470, erpBps = 442, provisional = false),
        ).getOrThrow()
        assertEquals(416_161_000_000L, analysis.latestRevenueDollars)
    }

    @Test
    fun per_share_uses_current_shares_not_the_year_average() {
        fun row(date: String, value: Double) = AnnualReportedValue(date, value)
        fun pack(currentShares: Long, yearAverageShares: Double) = DcfAnalysisEngine.compute(
            fundamentals = FundamentalSnapshot(
                symbol = "AAPL",
                sectorName = "Technology",
                industryName = "Consumer Electronics",
                marketCapDollars = 3_000_000_000_000L,
                sharesOutstanding = currentShares,
                betaMillis = 1_200,
                totalDebtDollars = 100_000_000_000L,
                totalCashDollars = 50_000_000_000L,
            ),
            timeseries = FundamentalTimeseries(
                freeCashFlow = listOf(
                    row("2022-09-24", 111_443_000_000.0),
                    row("2023-09-30", 99_584_000_000.0),
                    row("2024-09-28", 108_807_000_000.0),
                    row("2025-09-27", 98_767_000_000.0),
                ),
                operatingCashFlow = listOf(
                    row("2022-09-24", 122_151_000_000.0),
                    row("2023-09-30", 110_543_000_000.0),
                    row("2024-09-28", 118_254_000_000.0),
                    row("2025-09-27", 111_482_000_000.0),
                ),
                capitalExpenditure = listOf(
                    row("2022-09-24", -10_708_000_000.0),
                    row("2023-09-30", -10_959_000_000.0),
                    row("2024-09-28", -9_447_000_000.0),
                    row("2025-09-27", -12_715_000_000.0),
                ),
                revenue = listOf(
                    row("2022-09-24", 394_328_000_000.0),
                    row("2023-09-30", 383_285_000_000.0),
                    row("2024-09-28", 391_035_000_000.0),
                    row("2025-09-27", 416_161_000_000.0),
                ),
                dilutedAverageShares = listOf(
                    row("2022-09-24", yearAverageShares),
                    row("2023-09-30", yearAverageShares),
                    row("2024-09-28", yearAverageShares),
                    row("2025-09-27", yearAverageShares),
                ),
                interestExpense = listOf(
                    row("2022-09-24", 2_931_000_000.0),
                    row("2023-09-30", 3_933_000_000.0),
                ),
                taxRateForCalcs = listOf(
                    row("2022-09-24", 0.1620),
                    row("2023-09-30", 0.1472),
                    row("2024-09-28", 0.2410),
                    row("2025-09-27", 0.1570),
                ),
                totalDebt = listOf(
                    row("2022-09-24", 120_000_000_000.0),
                    row("2023-09-30", 111_000_000_000.0),
                    row("2024-09-28", 106_000_000_000.0),
                    row("2025-09-27", 98_000_000_000.0),
                ),
                marginalTaxRate = listOf(
                    AnnualReportedValue("2022-09-24", 0.21, concept = "JurisdictionStatutory"),
                    AnnualReportedValue("2023-09-30", 0.21, concept = "JurisdictionStatutory"),
                    AnnualReportedValue("2024-09-28", 0.21, concept = "JurisdictionStatutory"),
                    AnnualReportedValue("2025-09-27", 0.21, concept = "JurisdictionStatutory"),
                ),
            ),
            marketParams = MarketParams(rfBps = 470, erpBps = 442, provisional = false),
        ).getOrThrow()
        var afterBuybacks = pack(14_594_180_000L, 15_004_697_000.0)
        var yearAverage = pack(15_004_697_000L, 15_004_697_000.0)
        assertTrue(
            afterBuybacks.baseIntrinsicValueCents > yearAverage.baseIntrinsicValueCents,
            "WAS still set the denominator: current=${afterBuybacks.baseIntrinsicValueCents} was=${yearAverage.baseIntrinsicValueCents}",
        )
    }

    @Test
    fun dual_class_quote_does_not_replace_diluted_year_average() {
        fun row(date: String, value: Double) = AnnualReportedValue(date, value)
        fun pack(currentShares: Long, yearAverageShares: Double) = DcfAnalysisEngine.compute(
            fundamentals = FundamentalSnapshot(
                symbol = "GOOGL",
                sectorName = "Communication Services",
                industryName = "Internet Content & Information",
                marketCapDollars = 2_000_000_000_000L,
                sharesOutstanding = currentShares,
                betaMillis = 1_100,
                totalDebtDollars = 25_000_000_000L,
                totalCashDollars = 90_000_000_000L,
            ),
            timeseries = FundamentalTimeseries(
                freeCashFlow = listOf(
                    row("2022-12-31", 60_000_000_000.0),
                    row("2023-12-31", 69_000_000_000.0),
                    row("2024-12-31", 73_000_000_000.0),
                    row("2025-12-31", 73_000_000_000.0),
                ),
                operatingCashFlow = listOf(
                    row("2022-12-31", 91_000_000_000.0),
                    row("2023-12-31", 102_000_000_000.0),
                    row("2024-12-31", 125_000_000_000.0),
                    row("2025-12-31", 133_000_000_000.0),
                ),
                capitalExpenditure = listOf(
                    row("2022-12-31", -31_000_000_000.0),
                    row("2023-12-31", -32_000_000_000.0),
                    row("2024-12-31", -52_000_000_000.0),
                    row("2025-12-31", -56_000_000_000.0),
                ),
                revenue = listOf(
                    row("2022-12-31", 283_000_000_000.0),
                    row("2023-12-31", 307_000_000_000.0),
                    row("2024-12-31", 350_000_000_000.0),
                    row("2025-12-31", 385_000_000_000.0),
                ),
                dilutedAverageShares = listOf(
                    row("2022-12-31", yearAverageShares),
                    row("2023-12-31", yearAverageShares),
                    row("2024-12-31", yearAverageShares),
                    row("2025-12-31", yearAverageShares),
                ),
                interestExpense = listOf(
                    row("2022-12-31", 400_000_000.0),
                    row("2023-12-31", 400_000_000.0),
                    row("2024-12-31", 400_000_000.0),
                    row("2025-12-31", 400_000_000.0),
                ),
                taxRateForCalcs = listOf(
                    row("2022-12-31", 0.16),
                    row("2023-12-31", 0.16),
                    row("2024-12-31", 0.16),
                    row("2025-12-31", 0.16),
                ),
                totalDebt = listOf(
                    row("2022-12-31", 25_000_000_000.0),
                    row("2023-12-31", 25_000_000_000.0),
                    row("2024-12-31", 25_000_000_000.0),
                    row("2025-12-31", 25_000_000_000.0),
                ),
                marginalTaxRate = listOf(
                    AnnualReportedValue("2022-12-31", 0.21, concept = "JurisdictionStatutory"),
                    AnnualReportedValue("2023-12-31", 0.21, concept = "JurisdictionStatutory"),
                    AnnualReportedValue("2024-12-31", 0.21, concept = "JurisdictionStatutory"),
                    AnnualReportedValue("2025-12-31", 0.21, concept = "JurisdictionStatutory"),
                ),
            ),
            marketParams = MarketParams(rfBps = 470, erpBps = 442, provisional = false),
        ).getOrThrow()
        var classAOnly = pack(5_867_155_790L, 12_230_000_000.0)
        var diluted = pack(12_230_000_000L, 12_230_000_000.0)
        assertEquals(diluted.baseIntrinsicValueCents, classAOnly.baseIntrinsicValueCents)
    }
}
