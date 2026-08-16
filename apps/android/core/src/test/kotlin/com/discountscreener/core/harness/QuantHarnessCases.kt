package com.discountscreener.core.harness

import com.discountscreener.core.engine.MarketParams
import com.discountscreener.core.engine.RF_SOURCE_YAHOO_TNX
import com.discountscreener.core.model.AnnualReportedValue
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.FundamentalTimeseries

/** Named hardcoded packs. Experiment tests load these; they do not build rows inline. */
object QuantHarnessCases {
    val POLISH_RATES = MarketParams(
        rfBps = 463,
        erpBps = 442,
        provisional = false,
        rfSource = RF_SOURCE_YAHOO_TNX,
    )

    /** Wave-1b SEC residual drivers. Not a price target. */
    val JPM_BANK = HardcodedCase(
        symbol = "JPM",
        fundamentals = FundamentalSnapshot(
            symbol = "JPM",
            sectorName = "Financial Services",
            industryName = "Banks - Diversified",
            marketCapDollars = 1_010_000_000_000L,
            sharesOutstanding = 2_781_500_000L,
            betaMillis = 1_080,
            returnOnEquityBps = 1_615,
            bookValuePerShareCents = 13_030L,
            retentionBps = 7_014,
        ),
        timeseries = FundamentalTimeseries(),
        marketParams = POLISH_RATES,
    )

    /** Wave-1b SEC residual drivers. Not a price target. */
    val CI_PLAN = HardcodedCase(
        symbol = "CI",
        fundamentals = FundamentalSnapshot(
            symbol = "CI",
            sectorName = "Healthcare",
            industryName = "Healthcare Plans",
            marketCapDollars = 76_000_000_000L,
            sharesOutstanding = 268_563_000L,
            betaMillis = 700,
            returnOnEquityBps = 1_452,
            bookValuePerShareCents = 15_532L,
            retentionBps = 7_296,
        ),
        timeseries = FundamentalTimeseries(),
        marketParams = POLISH_RATES,
    )

    /** Windows AMZN 4-year driver fixture. Not a price target. */
    val AMZN_RETAIL = HardcodedCase(
        symbol = "AMZN",
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
                AnnualReportedValue("2022-12-31", 0.21, concept = "JurisdictionStatutory"),
                AnnualReportedValue("2023-12-31", 0.21, concept = "JurisdictionStatutory"),
                AnnualReportedValue("2024-12-31", 0.21, concept = "JurisdictionStatutory"),
                AnnualReportedValue("2025-12-31", 0.21, concept = "JurisdictionStatutory"),
            ),
        ),
        marketParams = POLISH_RATES,
    )

    val PEPITO = HardcodedCase(
        symbol = "PEPITO",
        fundamentals = FundamentalSnapshot(
            symbol = "PEPITO",
            sectorName = "Technology",
            industryName = "Semiconductors",
            marketCapDollars = 1_200_000_000L,
            sharesOutstanding = 100_000_000L,
            totalDebtDollars = 120_000_000L,
            totalCashDollars = 20_000_000L,
            betaMillis = 1_100,
            freeCashFlowDollars = 86_000_000L,
        ),
        timeseries = FundamentalTimeseries(
            freeCashFlow = annual(50_000_000.0, 60_000_000.0, 72_000_000.0, 86_000_000.0),
            dilutedAverageShares = annual(100_000_000.0, 100_000_000.0, 100_000_000.0, 100_000_000.0),
            operatingCashFlow = annual(70_000_000.0, 80_000_000.0, 92_000_000.0, 106_000_000.0),
            capitalExpenditure = annual(-20_000_000.0, -20_000_000.0, -20_000_000.0, -20_000_000.0),
            revenue = annual(200_000_000.0, 210_000_000.0, 220_000_000.0, 230_000_000.0),
            interestExpense = annual(8_000_000.0, 8_000_000.0, 8_000_000.0, 8_000_000.0),
            taxRateForCalcs = annualTax(),
            totalDebt = annual(120_000_000.0, 120_000_000.0, 120_000_000.0, 120_000_000.0),
            marginalTaxRate = annualTax(),
        ),
    )
}

private fun annual(y1: Double, y2: Double, y3: Double, y4: Double): List<AnnualReportedValue> = listOf(
    AnnualReportedValue("2021-12-31", y1),
    AnnualReportedValue("2022-12-31", y2),
    AnnualReportedValue("2023-12-31", y3),
    AnnualReportedValue("2024-12-31", y4),
)

private fun annualTax(): List<AnnualReportedValue> = listOf(
    AnnualReportedValue("2021-12-31", 0.21, concept = "JurisdictionStatutory"),
    AnnualReportedValue("2022-12-31", 0.21, concept = "JurisdictionStatutory"),
    AnnualReportedValue("2023-12-31", 0.21, concept = "JurisdictionStatutory"),
    AnnualReportedValue("2024-12-31", 0.21, concept = "JurisdictionStatutory"),
)
