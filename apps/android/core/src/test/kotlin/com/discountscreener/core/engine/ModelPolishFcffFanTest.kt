package com.discountscreener.core.engine

import com.discountscreener.core.model.AnnualReportedValue
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.FundamentalTimeseries
import kotlin.test.Test
import kotlin.test.assertTrue

class ModelPolishFcffFanTest {
    @Test
    fun fiscal_year_hole_is_not_one_year_of_growth() {
        var analysis = DcfAnalysisEngine.compute(
            fundamentals = FundamentalSnapshot(
                symbol = "HOLE",
                sectorName = "Technology",
                industryName = "Software - Infrastructure",
                marketCapDollars = 20_000_000_000L,
                sharesOutstanding = 100_000_000L,
                betaMillis = 1_000,
                totalDebtDollars = 10_000_000L,
                totalCashDollars = 0L,
            ),
            timeseries = FundamentalTimeseries(
                freeCashFlow = listOf(
                    AnnualReportedValue("2012-01-29", 4_000_000.0),
                    AnnualReportedValue("2022-01-30", 32_000_000.0),
                    AnnualReportedValue("2023-01-29", 36_000_000.0),
                    AnnualReportedValue("2024-01-28", 40_000_000.0),
                ),
                operatingCashFlow = listOf(
                    AnnualReportedValue("2012-01-29", 9_000_000.0),
                    AnnualReportedValue("2022-01-30", 40_000_000.0),
                    AnnualReportedValue("2023-01-29", 45_000_000.0),
                    AnnualReportedValue("2024-01-28", 50_000_000.0),
                ),
                capitalExpenditure = listOf(
                    AnnualReportedValue("2012-01-29", -1_000_000.0),
                    AnnualReportedValue("2022-01-30", -8_000_000.0),
                    AnnualReportedValue("2023-01-29", -9_000_000.0),
                    AnnualReportedValue("2024-01-28", -10_000_000.0),
                ),
                revenue = listOf(
                    AnnualReportedValue("2012-01-29", 10_000_000.0),
                    AnnualReportedValue("2022-01-30", 110_000_000.0),
                    AnnualReportedValue("2023-01-29", 121_000_000.0),
                    AnnualReportedValue("2024-01-28", 133_100_000.0),
                ),
                interestExpense = listOf(
                    AnnualReportedValue("2012-01-29", 1_000_000.0),
                    AnnualReportedValue("2022-01-30", 1_000_000.0),
                    AnnualReportedValue("2023-01-29", 1_000_000.0),
                    AnnualReportedValue("2024-01-28", 1_000_000.0),
                ),
                taxRateForCalcs = listOf(
                    AnnualReportedValue("2012-01-29", 0.21),
                    AnnualReportedValue("2022-01-30", 0.21),
                    AnnualReportedValue("2023-01-29", 0.21),
                    AnnualReportedValue("2024-01-28", 0.21),
                ),
                totalDebt = listOf(
                    AnnualReportedValue("2012-01-29", 10_000_000.0),
                    AnnualReportedValue("2022-01-30", 10_000_000.0),
                    AnnualReportedValue("2023-01-29", 10_000_000.0),
                    AnnualReportedValue("2024-01-28", 10_000_000.0),
                ),
                marginalTaxRate = listOf(
                    AnnualReportedValue("2012-01-29", 0.21, concept = "JurisdictionStatutory"),
                    AnnualReportedValue("2022-01-30", 0.21, concept = "JurisdictionStatutory"),
                    AnnualReportedValue("2023-01-29", 0.21, concept = "JurisdictionStatutory"),
                    AnnualReportedValue("2024-01-28", 0.21, concept = "JurisdictionStatutory"),
                ),
            ),
            marketParams = QuantHarnessCasesRates.bootstrap,
        ).getOrThrow()
        assertTrue(
            (analysis.growthDispersionBps ?: 0) < 5_000,
            "hole treated as one year: dispersion=${analysis.growthDispersionBps}",
        )
    }

    @Test
    fun flat_growth_wide_margin_fan_stays_thinkable() {
        var analysis = DcfAnalysisEngine.compute(
            fundamentals = FundamentalSnapshot(
                symbol = "FAN",
                sectorName = "Technology",
                industryName = "Software - Infrastructure",
                marketCapDollars = 20_000_000_000L,
                sharesOutstanding = 100_000_000L,
                betaMillis = 1_000,
                totalDebtDollars = 10_000_000L,
                totalCashDollars = 0L,
            ),
            timeseries = FundamentalTimeseries(
                freeCashFlow = annual(5_000_000.0, 32_000_000.0, 36_000_000.0, 40_000_000.0),
                operatingCashFlow = annual(10_000_000.0, 40_000_000.0, 45_000_000.0, 50_000_000.0),
                capitalExpenditure = annual(-5_000_000.0, -8_000_000.0, -9_000_000.0, -10_000_000.0),
                revenue = annual(100_000_000.0, 110_000_000.0, 121_000_000.0, 133_100_000.0),
                interestExpense = annual(1_000_000.0, 1_000_000.0, 1_000_000.0, 1_000_000.0),
                taxRateForCalcs = annual(0.21, 0.21, 0.21, 0.21),
                totalDebt = annual(10_000_000.0, 10_000_000.0, 10_000_000.0, 10_000_000.0),
                marginalTaxRate = listOf(
                    AnnualReportedValue("2021-12-31", 0.21, concept = "JurisdictionStatutory"),
                    AnnualReportedValue("2022-12-31", 0.21, concept = "JurisdictionStatutory"),
                    AnnualReportedValue("2023-12-31", 0.21, concept = "JurisdictionStatutory"),
                    AnnualReportedValue("2024-12-31", 0.21, concept = "JurisdictionStatutory"),
                ),
            ),
            marketParams = QuantHarnessCasesRates.bootstrap,
        ).getOrThrow()
        var width = requireNotNull(
            ValuationDecisionPolicy.scenarioWidthBps(
                analysis.bearIntrinsicValueCents,
                analysis.baseIntrinsicValueCents,
                analysis.bullIntrinsicValueCents,
            ),
        )
        assertTrue(
            width <= ValuationJudgmentPolicy.IDENTITY_USABLE_MAX_WIDTH_BPS,
            "flat-growth fan width is $width bps (bear=${analysis.bearIntrinsicValueCents} base=${analysis.baseIntrinsicValueCents} bull=${analysis.bullIntrinsicValueCents})",
        )
    }

    @Test
    fun wide_growth_span_is_a_band_around_the_median() {
        var analysis = DcfAnalysisEngine.compute(
            fundamentals = FundamentalSnapshot(
                symbol = "NVDA",
                sectorName = "Technology",
                industryName = "Semiconductors",
                marketCapDollars = 20_000_000_000L,
                sharesOutstanding = 100_000_000L,
                betaMillis = 1_000,
                totalDebtDollars = 10_000_000L,
                totalCashDollars = 0L,
            ),
            timeseries = FundamentalTimeseries(
                freeCashFlow = nvdaLikeGrowth(0.32),
                operatingCashFlow = nvdaLikeGrowth(0.40),
                capitalExpenditure = nvdaLikeGrowth(-0.08),
                revenue = nvdaLikeGrowth(1.0),
                interestExpense = listOf(
                    AnnualReportedValue("2021-01-31", 1_000_000.0),
                    AnnualReportedValue("2022-01-30", 1_000_000.0),
                    AnnualReportedValue("2023-01-29", 1_000_000.0),
                    AnnualReportedValue("2024-01-28", 1_000_000.0),
                    AnnualReportedValue("2025-01-26", 1_000_000.0),
                ),
                taxRateForCalcs = listOf(
                    AnnualReportedValue("2021-01-31", 0.21),
                    AnnualReportedValue("2022-01-30", 0.21),
                    AnnualReportedValue("2023-01-29", 0.21),
                    AnnualReportedValue("2024-01-28", 0.21),
                    AnnualReportedValue("2025-01-26", 0.21),
                ),
                totalDebt = listOf(
                    AnnualReportedValue("2021-01-31", 10_000_000.0),
                    AnnualReportedValue("2022-01-30", 10_000_000.0),
                    AnnualReportedValue("2023-01-29", 10_000_000.0),
                    AnnualReportedValue("2024-01-28", 10_000_000.0),
                    AnnualReportedValue("2025-01-26", 10_000_000.0),
                ),
                marginalTaxRate = listOf(
                    AnnualReportedValue("2021-01-31", 0.21, concept = "JurisdictionStatutory"),
                    AnnualReportedValue("2022-01-30", 0.21, concept = "JurisdictionStatutory"),
                    AnnualReportedValue("2023-01-29", 0.21, concept = "JurisdictionStatutory"),
                    AnnualReportedValue("2024-01-28", 0.21, concept = "JurisdictionStatutory"),
                    AnnualReportedValue("2025-01-26", 0.21, concept = "JurisdictionStatutory"),
                ),
            ),
            marketParams = QuantHarnessCasesRates.bootstrap,
        ).getOrThrow()
        var width = requireNotNull(
            ValuationDecisionPolicy.scenarioWidthBps(
                analysis.bearIntrinsicValueCents,
                analysis.baseIntrinsicValueCents,
                analysis.bullIntrinsicValueCents,
            ),
        )
        assertTrue(
            width <= ValuationJudgmentPolicy.IDENTITY_USABLE_MAX_WIDTH_BPS,
            "0%/126% stacked as the fan: width=$width bps (bear=${analysis.bearIntrinsicValueCents} base=${analysis.baseIntrinsicValueCents} bull=${analysis.bullIntrinsicValueCents} g=${analysis.baseGrowthBps} disp=${analysis.growthDispersionBps})",
        )
    }

    @Test
    fun one_soft_year_does_not_make_a_compounder_cyclical() {
        var analysis = DcfAnalysisEngine.compute(
            fundamentals = FundamentalSnapshot(
                symbol = "GROW",
                sectorName = "Technology",
                industryName = "Semiconductors",
                marketCapDollars = 20_000_000_000L,
                sharesOutstanding = 100_000_000L,
                betaMillis = 1_000,
                totalDebtDollars = 10_000_000L,
                totalCashDollars = 0L,
            ),
            timeseries = FundamentalTimeseries(
                freeCashFlow = oneSoftYearCompounder(0.32),
                operatingCashFlow = oneSoftYearCompounder(0.40),
                capitalExpenditure = oneSoftYearCompounder(-0.08),
                revenue = oneSoftYearCompounder(1.0),
                interestExpense = listOf(
                    AnnualReportedValue("2021-01-31", 1_000_000.0),
                    AnnualReportedValue("2022-01-30", 1_000_000.0),
                    AnnualReportedValue("2023-01-29", 1_000_000.0),
                    AnnualReportedValue("2024-01-28", 1_000_000.0),
                    AnnualReportedValue("2025-01-26", 1_000_000.0),
                ),
                taxRateForCalcs = listOf(
                    AnnualReportedValue("2021-01-31", 0.21),
                    AnnualReportedValue("2022-01-30", 0.21),
                    AnnualReportedValue("2023-01-29", 0.21),
                    AnnualReportedValue("2024-01-28", 0.21),
                    AnnualReportedValue("2025-01-26", 0.21),
                ),
                totalDebt = listOf(
                    AnnualReportedValue("2021-01-31", 10_000_000.0),
                    AnnualReportedValue("2022-01-30", 10_000_000.0),
                    AnnualReportedValue("2023-01-29", 10_000_000.0),
                    AnnualReportedValue("2024-01-28", 10_000_000.0),
                    AnnualReportedValue("2025-01-26", 10_000_000.0),
                ),
                marginalTaxRate = listOf(
                    AnnualReportedValue("2021-01-31", 0.21, concept = "JurisdictionStatutory"),
                    AnnualReportedValue("2022-01-30", 0.21, concept = "JurisdictionStatutory"),
                    AnnualReportedValue("2023-01-29", 0.21, concept = "JurisdictionStatutory"),
                    AnnualReportedValue("2024-01-28", 0.21, concept = "JurisdictionStatutory"),
                    AnnualReportedValue("2025-01-26", 0.21, concept = "JurisdictionStatutory"),
                ),
            ),
            marketParams = QuantHarnessCasesRates.bootstrap,
        ).getOrThrow()
        assertTrue(
            analysis.reasonCodes.any { it.contains("regime:secular_expansion") },
            "one soft year made a 50% median grower cyclical: ${analysis.reasonCodes}",
        )
    }

    @Test
    fun secular_near_growth_keeps_half_the_demonstrated_rate() {
        var analysis = DcfAnalysisEngine.compute(
            fundamentals = FundamentalSnapshot(
                symbol = "NVDA",
                sectorName = "Technology",
                industryName = "Semiconductors",
                marketCapDollars = 20_000_000_000L,
                sharesOutstanding = 100_000_000L,
                betaMillis = 1_000,
                totalDebtDollars = 10_000_000L,
                totalCashDollars = 0L,
            ),
            timeseries = FundamentalTimeseries(
                freeCashFlow = oneSoftYearCompounder(0.32),
                operatingCashFlow = oneSoftYearCompounder(0.40),
                capitalExpenditure = oneSoftYearCompounder(-0.08),
                revenue = oneSoftYearCompounder(1.0),
                interestExpense = listOf(
                    AnnualReportedValue("2021-01-31", 1_000_000.0),
                    AnnualReportedValue("2022-01-30", 1_000_000.0),
                    AnnualReportedValue("2023-01-29", 1_000_000.0),
                    AnnualReportedValue("2024-01-28", 1_000_000.0),
                    AnnualReportedValue("2025-01-26", 1_000_000.0),
                ),
                taxRateForCalcs = listOf(
                    AnnualReportedValue("2021-01-31", 0.21),
                    AnnualReportedValue("2022-01-30", 0.21),
                    AnnualReportedValue("2023-01-29", 0.21),
                    AnnualReportedValue("2024-01-28", 0.21),
                    AnnualReportedValue("2025-01-26", 0.21),
                ),
                totalDebt = listOf(
                    AnnualReportedValue("2021-01-31", 10_000_000.0),
                    AnnualReportedValue("2022-01-30", 10_000_000.0),
                    AnnualReportedValue("2023-01-29", 10_000_000.0),
                    AnnualReportedValue("2024-01-28", 10_000_000.0),
                    AnnualReportedValue("2025-01-26", 10_000_000.0),
                ),
                marginalTaxRate = listOf(
                    AnnualReportedValue("2021-01-31", 0.21, concept = "JurisdictionStatutory"),
                    AnnualReportedValue("2022-01-30", 0.21, concept = "JurisdictionStatutory"),
                    AnnualReportedValue("2023-01-29", 0.21, concept = "JurisdictionStatutory"),
                    AnnualReportedValue("2024-01-28", 0.21, concept = "JurisdictionStatutory"),
                    AnnualReportedValue("2025-01-26", 0.21, concept = "JurisdictionStatutory"),
                ),
            ),
            marketParams = QuantHarnessCasesRates.bootstrap,
        ).getOrThrow()
        assertTrue(
            analysis.baseGrowthBps >= 2_500,
            "secular still Gordon-capped to stable+1200: g=${analysis.baseGrowthBps} stable=${analysis.stableGrowthBps} raw reasons=${analysis.reasonCodes}",
        )
    }

    @Test
    fun extreme_recent_growth_stays_within_stable_band() {
        var analysis = DcfAnalysisEngine.compute(
            fundamentals = FundamentalSnapshot(
                symbol = "NVDA",
                sectorName = "Technology",
                industryName = "Semiconductors",
                marketCapDollars = 20_000_000_000L,
                sharesOutstanding = 100_000_000L,
                betaMillis = 1_000,
                totalDebtDollars = 10_000_000L,
                totalCashDollars = 0L,
            ),
            timeseries = FundamentalTimeseries(
                freeCashFlow = nvdaLikeGrowth(0.32),
                operatingCashFlow = nvdaLikeGrowth(0.40),
                capitalExpenditure = nvdaLikeGrowth(-0.08),
                revenue = nvdaLikeGrowth(1.0),
                interestExpense = listOf(
                    AnnualReportedValue("2021-01-31", 1_000_000.0),
                    AnnualReportedValue("2022-01-30", 1_000_000.0),
                    AnnualReportedValue("2023-01-29", 1_000_000.0),
                    AnnualReportedValue("2024-01-28", 1_000_000.0),
                    AnnualReportedValue("2025-01-26", 1_000_000.0),
                ),
                taxRateForCalcs = listOf(
                    AnnualReportedValue("2021-01-31", 0.21),
                    AnnualReportedValue("2022-01-30", 0.21),
                    AnnualReportedValue("2023-01-29", 0.21),
                    AnnualReportedValue("2024-01-28", 0.21),
                    AnnualReportedValue("2025-01-26", 0.21),
                ),
                totalDebt = listOf(
                    AnnualReportedValue("2021-01-31", 10_000_000.0),
                    AnnualReportedValue("2022-01-30", 10_000_000.0),
                    AnnualReportedValue("2023-01-29", 10_000_000.0),
                    AnnualReportedValue("2024-01-28", 10_000_000.0),
                    AnnualReportedValue("2025-01-26", 10_000_000.0),
                ),
                marginalTaxRate = listOf(
                    AnnualReportedValue("2021-01-31", 0.21, concept = "JurisdictionStatutory"),
                    AnnualReportedValue("2022-01-30", 0.21, concept = "JurisdictionStatutory"),
                    AnnualReportedValue("2023-01-29", 0.21, concept = "JurisdictionStatutory"),
                    AnnualReportedValue("2024-01-28", 0.21, concept = "JurisdictionStatutory"),
                    AnnualReportedValue("2025-01-26", 0.21, concept = "JurisdictionStatutory"),
                ),
            ),
            marketParams = QuantHarnessCasesRates.bootstrap,
        ).getOrThrow()
        assertTrue(
            analysis.baseGrowthBps <= analysis.stableGrowthBps + 1_200,
            "near-term growth ${analysis.baseGrowthBps} exceeds stable ${analysis.stableGrowthBps} + 1200",
        )
    }
}

private object QuantHarnessCasesRates {
    val bootstrap = MarketParams()
}

private fun annual(y1: Double, y2: Double, y3: Double, y4: Double): List<AnnualReportedValue> = listOf(
    AnnualReportedValue("2021-12-31", y1),
    AnnualReportedValue("2022-12-31", y2),
    AnnualReportedValue("2023-12-31", y3),
    AnnualReportedValue("2024-12-31", y4),
)

/** Adjacent-year growth -2%, +20%, +80%, +120%. Median stays high. */
private fun oneSoftYearCompounder(scale: Double): List<AnnualReportedValue> = listOf(
    AnnualReportedValue("2021-01-31", 100_000_000.0 * scale),
    AnnualReportedValue("2022-01-30", 98_000_000.0 * scale),
    AnnualReportedValue("2023-01-29", 117_600_000.0 * scale),
    AnnualReportedValue("2024-01-28", 211_680_000.0 * scale),
    AnnualReportedValue("2025-01-26", 465_696_000.0 * scale),
)

/** Adjacent-year growth 0%, 0%, 126%, 126%. Flat margin when scaled. */
private fun nvdaLikeGrowth(scale: Double): List<AnnualReportedValue> = listOf(
    AnnualReportedValue("2021-01-31", 100_000_000.0 * scale),
    AnnualReportedValue("2022-01-30", 100_000_000.0 * scale),
    AnnualReportedValue("2023-01-29", 100_000_000.0 * scale),
    AnnualReportedValue("2024-01-28", 226_000_000.0 * scale),
    AnnualReportedValue("2025-01-26", 510_760_000.0 * scale),
)
