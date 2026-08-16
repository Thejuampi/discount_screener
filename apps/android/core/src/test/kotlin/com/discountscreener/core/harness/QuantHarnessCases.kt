package com.discountscreener.core.harness

import com.discountscreener.core.engine.IssuerInstrumentQuote
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

    /**
     * Live Markets Insider Apple rows sampled 2026-08-16. Coupon is not yield.
     * Preferred USD remaining 4–15y: 446, 460, 482, 483. Median 471.
     */
    val AAPL_INSTRUMENT_QUOTES = listOf(
        IssuerInstrumentQuote(594, "2049-09-11", "USD"),
        IssuerInstrumentQuote(483, "2035-05-12", "USD"),
        IssuerInstrumentQuote(596, "2051-02-08", "USD"),
        IssuerInstrumentQuote(585, "2045-02-09", "USD"),
        IssuerInstrumentQuote(594, "2060-08-20", "USD"),
        IssuerInstrumentQuote(408, "2027-02-09", "USD"),
        IssuerInstrumentQuote(432, "2027-09-12", "USD"),
        IssuerInstrumentQuote(460, "2031-08-05", "USD"),
        IssuerInstrumentQuote(67, "2030-02-25", "CHF"),
        IssuerInstrumentQuote(444, "2029-07-31", "GBP"),
        IssuerInstrumentQuote(446, "2031-02-08", "USD"),
        IssuerInstrumentQuote(482, "2036-02-23", "USD"),
        IssuerInstrumentQuote(433, "2029-08-08", "USD"),
    )

    /** SEC-shaped Apple coupon holes. Filed through 2023-09-30. Debt continues. */
    val AAPL_COUPON_HOLES = HardcodedCase(
        symbol = "AAPL",
        fundamentals = FundamentalSnapshot(
            symbol = "AAPL",
            sectorName = "Technology",
            industryName = "Consumer Electronics",
            marketCapDollars = 3_000_000_000_000L,
            sharesOutstanding = 15_000_000_000L,
            totalDebtDollars = 90_000_000_000L,
            totalCashDollars = 60_000_000_000L,
            betaMillis = 1_200,
        ),
        timeseries = FundamentalTimeseries(
            freeCashFlow = appleDates.mapIndexed { index, date ->
                AnnualReportedValue(date, (80.0 + index * 5.0) * 1_000_000_000.0, periodEnd = date)
            },
            dilutedAverageShares = appleDates.map {
                AnnualReportedValue(it, 15_000_000_000.0, periodEnd = it)
            },
            operatingCashFlow = appleDates.mapIndexed { index, date ->
                AnnualReportedValue(date, (90.0 + index * 5.0) * 1_000_000_000.0, periodEnd = date)
            },
            capitalExpenditure = appleDates.map {
                AnnualReportedValue(it, -10_000_000_000.0, periodEnd = it)
            },
            revenue = appleDates.mapIndexed { index, date ->
                AnnualReportedValue(date, (270.0 + index * 20.0) * 1_000_000_000.0, periodEnd = date)
            },
            interestExpense = appleDates.filter { it <= "2023-09-30" }.map {
                AnnualReportedValue(it, 3_000_000_000.0, periodEnd = it)
            },
            taxRateForCalcs = appleDates.map { AnnualReportedValue(it, 0.16, periodEnd = it) },
            totalDebt = appleDates.map {
                AnnualReportedValue(it, 95_000_000_000.0, periodEnd = it)
            },
            pretaxIncome = appleDates.mapIndexed { index, date ->
                AnnualReportedValue(date, (80.0 + index * 8.0) * 1_000_000_000.0, periodEnd = date)
            },
            marginalTaxRate = appleDates.map {
                AnnualReportedValue(
                    it,
                    0.21,
                    periodEnd = it,
                    concept = "EffectiveIncomeTaxRateReconciliationAtFederalStatutoryIncomeTaxRate",
                )
            },
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

private val appleDates = listOf(
    "2020-09-26",
    "2021-09-25",
    "2022-09-24",
    "2023-09-30",
    "2024-09-28",
    "2025-09-27",
)

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
