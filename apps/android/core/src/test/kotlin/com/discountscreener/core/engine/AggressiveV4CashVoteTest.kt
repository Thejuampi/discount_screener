package com.discountscreener.core.engine

import com.discountscreener.core.model.AnnualReportedValue
import com.discountscreener.core.model.FundamentalTimeseries
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * One fact family, one vote.
 *
 * The cash-flow vote and the conversion term both read free cash flow. When the yield vote has
 * fired — FCF over EV or equity cap — the conversion ratio re-reads the same dollars and adds
 * nothing this bucket did not already count, so it stays silent. When yield cannot vote, the
 * ratio still says something new about quality of earnings, so it keeps its vote.
 */
class AggressiveV4CashVoteTest {

    /** FCF yield voted; conversion would be the same dollars a second time. */
    @Test
    fun the_yield_vote_retires_the_conversion_vote() {
        var keys = OpportunityEngine.aggressiveV4FundamentalsScore(
            baseDetail(fundamentals = fundamentals(
                marketCapDollars = 200_000_000_000,
                freeCashFlowDollars = 8_000_000_000,
                operatingCashFlowDollars = 11_000_000_000,
            )),
            sectorBenchmarks = null,
        ).factors.map { it.key }

        assertTrue("FCFy" in keys)
        assertFalse("Conv" in keys, "conversion after a yield vote is one fact counted twice: $keys")
    }

    /** No size base means the yield cannot vote; the ratio still adds the quality reading. */
    @Test
    fun conversion_still_speaks_when_yield_cannot_vote() {
        var keys = OpportunityEngine.aggressiveV4FundamentalsScore(
            baseDetail(
                marketPriceCents = 0,
                fundamentals = fundamentals(
                    freeCashFlowDollars = 8_000_000_000,
                    operatingCashFlowDollars = 11_000_000_000,
                ),
            ),
            sectorBenchmarks = null,
        ).factors.map { it.key }

        assertFalse("FCF" in keys, "a sign vote is not a cash-flow score: $keys")
        assertTrue("Conv" in keys)
    }

    /**
     * The defect: any positive FCF took the full cash-flow weight, so one dollar and one trillion
     * printed the same +20. Cash flow is only a score once it is a yield against size.
     */
    @Test
    fun one_dollar_of_fcf_does_not_match_one_trillion_against_the_same_size() {
        var one = cashPoints(freeCashFlowDollars = 1)
        var trillion = cashPoints(freeCashFlowDollars = 1_000_000_000_000)

        assertTrue(trillion > one, "one=$one trillion=$trillion")
    }

    @Test
    fun missing_market_cap_still_scores_yield_from_price_times_shares() {
        var factor = OpportunityEngine.aggressiveV4FundamentalsScore(
            baseDetail(
                marketPriceCents = 10_000,
                fundamentals = fundamentals(
                    freeCashFlowDollars = 8_000_000_000,
                    sharesOutstanding = 1_000_000_000,
                ),
            ),
            sectorBenchmarks = null,
        ).factors.single { it.key == "FCFy" }

        assertEquals(20, factor.bucketPoints)
    }

    @Test
    fun net_debt_does_not_change_the_equity_fcf_yield() {
        var unlevered = cashPoints(
            freeCashFlowDollars = 8_000_000_000,
            marketCapDollars = 100_000_000_000,
            totalCashDollars = 0,
            totalDebtDollars = 0,
        )
        var levered = cashPoints(
            freeCashFlowDollars = 8_000_000_000,
            marketCapDollars = 100_000_000_000,
            totalCashDollars = 0,
            totalDebtDollars = 100_000_000_000,
        )

        assertEquals(unlevered, levered)
    }

    @Test
    fun yield_names_market_cap_even_when_the_firm_is_levered() {
        var why = OpportunityEngine.aggressiveV4FundamentalsScore(
            baseDetail(
                fundamentals = fundamentals(
                    freeCashFlowDollars = 8_000_000_000,
                    marketCapDollars = 100_000_000_000,
                    totalCashDollars = 0,
                    totalDebtDollars = 100_000_000_000,
                ),
            ),
            sectorBenchmarks = null,
        ).factors.single { it.key.startsWith("FCFy") }.comparisons.single().why

        assertEquals("FCF / market cap", why)
    }

    /**
     * One sale year is not the business. Four ordinary years and one spike: the centre is the
     * ordinary years, not the TTM print that would saturate the band.
     */
    @Test
    fun four_years_use_the_true_median_of_the_middle_pair() {
        var observed = OpportunityEngine.aggressiveV4FundamentalsScore(
            baseDetail(
                fundamentals = fundamentals(
                    freeCashFlowDollars = 1_000_000_000,
                    marketCapDollars = 100_000_000_000,
                ),
            ),
            sectorBenchmarks = null,
            timeseries = FundamentalTimeseries(
                freeCashFlow = listOf(2.0, 8.0, 10.0, 12.0).mapIndexed { index, value ->
                    AnnualReportedValue("202${index}-12-31", value * 1_000_000_000)
                },
            ),
        ).factors.single { it.key.startsWith("FCFy") }.comparisons.single().observed

        assertEquals(900, observed)
    }

    @Test
    fun a_spike_year_does_not_set_the_yield() {
        var points = OpportunityEngine.aggressiveV4FundamentalsScore(
            baseDetail(
                fundamentals = fundamentals(
                    freeCashFlowDollars = 40_000_000_000,
                    marketCapDollars = 100_000_000_000,
                ),
            ),
            sectorBenchmarks = null,
            timeseries = FundamentalTimeseries(
                freeCashFlow = listOf(4.0, 4.0, 4.0, 4.0, 40.0).mapIndexed { index, value ->
                    AnnualReportedValue("202${index}-12-31", value * 1_000_000_000)
                },
            ),
        ).factors.single { it.key.startsWith("FCFy") }.bucketPoints

        assertEquals(4, points)
    }

    @Test
    fun financial_services_do_not_take_cash_conversion() {
        var keys = OpportunityEngine.aggressiveV4FundamentalsScore(
            baseDetail(
                fundamentals = fundamentals(
                    sectorName = "Financial Services",
                    industryName = "Banks — Diversified",
                    freeCashFlowDollars = 8_000_000_000,
                    operatingCashFlowDollars = 11_000_000_000,
                    marketCapDollars = 100_000_000_000,
                ),
            ),
            sectorBenchmarks = null,
        ).factors.map { it.key }

        assertFalse("Conv" in keys)
    }

    @Test
    fun one_dollar_of_ocf_does_not_match_one_trillion_against_the_same_size() {
        var one = ocfPoints(1)
        var trillion = ocfPoints(1_000_000_000_000)

        assertTrue(trillion > one, "one=$one trillion=$trillion")
    }

    @Test
    fun reported_enterprise_value_does_not_set_the_fcf_size() {
        var why = OpportunityEngine.aggressiveV4FundamentalsScore(
            baseDetail(
                fundamentals = fundamentals(
                    freeCashFlowDollars = 8_000_000_000,
                    marketCapDollars = 100_000_000_000,
                    enterpriseValueDollars = 200_000_000_000,
                ),
            ),
            sectorBenchmarks = null,
        ).factors.single { it.key.startsWith("FCFy") }.comparisons.single().why

        assertEquals("FCF / market cap", why)
    }

    @Test
    fun zero_net_debt_does_not_call_equity_cap_an_enterprise_value() {
        var why = OpportunityEngine.aggressiveV4FundamentalsScore(
            baseDetail(
                fundamentals = fundamentals(
                    freeCashFlowDollars = 8_000_000_000,
                    marketCapDollars = 100_000_000_000,
                    totalCashDollars = 0,
                    totalDebtDollars = 0,
                ),
            ),
            sectorBenchmarks = null,
        ).factors.single { it.key.startsWith("FCFy") }.comparisons.single().why

        assertEquals("FCF / market cap", why)
    }

    @Test
    fun a_bank_with_only_series_fcf_still_names_the_skip() {
        var keys = OpportunityEngine.aggressiveV4FundamentalsScore(
            baseDetail(
                fundamentals = fundamentals(
                    sectorName = "Financial Services",
                    industryName = "Banks — Diversified",
                    marketCapDollars = 100_000_000_000,
                ),
            ),
            sectorBenchmarks = null,
            timeseries = FundamentalTimeseries(
                freeCashFlow = listOf(AnnualReportedValue("2024-12-31", 8_000_000_000.0)),
            ),
        ).factors.map { it.key }

        assertEquals(listOf("FCFy∅ financial"), keys.filter { it.startsWith("FCFy") })
    }

    @Test
    fun visa_credit_services_still_takes_an_industrial_fcf_yield() {
        var keys = OpportunityEngine.aggressiveV4FundamentalsScore(
            baseDetail(
                fundamentals = fundamentals(
                    symbol = "V",
                    sectorName = "Financial Services",
                    industryName = "Credit Services",
                    sectorKey = "financial-services",
                    industryKey = "credit-services",
                    freeCashFlowDollars = 8_000_000_000,
                    marketCapDollars = 100_000_000_000,
                ),
            ),
            sectorBenchmarks = null,
        ).factors.map { it.key }

        assertEquals(listOf("FCFy"), keys.filter { it.startsWith("FCFy") })
    }

    @Test
    fun missing_fundamentals_slots_flag_coverage() {
        var keys = OpportunityEngine.aggressiveV4FundamentalsScore(
            baseDetail(
                fundamentals = fundamentals(returnOnEquityBps = 2_000),
            ),
            sectorBenchmarks = null,
        ).factors.map { it.key }

        assertTrue("Fund∅ coverage" in keys)
    }

    @Test
    fun a_bank_s_roe_is_not_shrunk_by_the_cash_slot_it_cannot_have() {
        var bank = OpportunityEngine.aggressiveV4FundamentalsScore(
            baseDetail(
                fundamentals = fundamentals(
                    sectorName = "Financial Services",
                    industryName = "Banks — Diversified",
                    returnOnEquityBps = 2_000,
                ),
            ),
            sectorBenchmarks = null,
        ).first!!
        var industrial = OpportunityEngine.aggressiveV4FundamentalsScore(
            baseDetail(
                fundamentals = fundamentals(
                    sectorName = "Technology",
                    industryName = "Software — Infrastructure",
                    returnOnEquityBps = 2_000,
                ),
            ),
            sectorBenchmarks = null,
        ).first!!

        assertTrue(bank > industrial, "bank=$bank industrial=$industrial")
    }

    @Test
    fun financial_services_do_not_take_an_industrial_fcf_yield() {
        var keys = OpportunityEngine.aggressiveV4FundamentalsScore(
            baseDetail(
                fundamentals = fundamentals(
                    sectorName = "Financial Services",
                    industryName = "Banks — Diversified",
                    freeCashFlowDollars = 8_000_000_000,
                    marketCapDollars = 100_000_000_000,
                ),
            ),
            sectorBenchmarks = null,
        ).factors.map { it.key }

        assertEquals(listOf("FCFy∅ financial"), keys.filter { it.startsWith("FCFy") })
    }

    @Test
    fun a_sector_adjusted_yield_uses_ttm_not_the_annual_centre() {
        var observed = OpportunityEngine.aggressiveV4FundamentalsScore(
            baseDetail(
                fundamentals = fundamentals(
                    freeCashFlowDollars = 40_000_000_000,
                    marketCapDollars = 100_000_000_000,
                ),
            ),
            sectorBenchmarks = SectorBenchmarks(
                forwardPeHundredths = null,
                enterpriseToEbitdaHundredths = null,
                priceToBookHundredths = null,
                returnOnEquityBps = null,
                netDebtToEbitdaHundredths = null,
                fcfYieldBps = 100,
            ),
            timeseries = FundamentalTimeseries(
                freeCashFlow = listOf(4.0, 4.0, 4.0, 4.0, 40.0).mapIndexed { index, value ->
                    AnnualReportedValue("202${index}-12-31", value * 1_000_000_000)
                },
            ),
        ).factors.single { it.key == "FCFy§" }.comparisons.single().observed

        assertEquals(4_000, observed)
    }

    @Test
    fun unclassified_does_not_take_an_industrial_fcf_yield() {
        var keys = OpportunityEngine.aggressiveV4FundamentalsScore(
            baseDetail(
                fundamentals = fundamentals(
                    sectorName = null,
                    freeCashFlowDollars = 8_000_000_000,
                    marketCapDollars = 100_000_000_000,
                ),
            ),
            sectorBenchmarks = null,
        ).factors.map { it.key }

        assertEquals(listOf("FCFy∅ unknown"), keys.filter { it.startsWith("FCFy") })
    }

    @Test
    fun a_reit_does_not_take_an_industrial_fcf_yield() {
        var keys = OpportunityEngine.aggressiveV4FundamentalsScore(
            baseDetail(
                fundamentals = fundamentals(
                    sectorName = "Real Estate",
                    industryName = "REIT — Residential",
                    freeCashFlowDollars = 8_000_000_000,
                    marketCapDollars = 100_000_000_000,
                ),
            ),
            sectorBenchmarks = null,
        ).factors.map { it.key }

        assertEquals(listOf("FCFy∅ ineligible"), keys.filter { it.startsWith("FCFy") })
    }

    @Test
    fun negative_cash_does_not_construct_an_enterprise_value() {
        var why = OpportunityEngine.aggressiveV4FundamentalsScore(
            baseDetail(
                fundamentals = fundamentals(
                    freeCashFlowDollars = 8_000_000_000,
                    marketCapDollars = 100_000_000_000,
                    totalCashDollars = -1_000_000_000,
                    totalDebtDollars = 1_000_000_000,
                ),
            ),
            sectorBenchmarks = null,
        ).factors.single { it.key.startsWith("FCFy") }.comparisons.single().why

        assertEquals("FCF / market cap", why)
    }

    @Test
    fun conversion_does_not_overflow_hundredths() {
        var observed = OpportunityEngine.aggressiveV4FundamentalsScore(
            baseDetail(
                marketPriceCents = 0,
                fundamentals = fundamentals(
                    freeCashFlowDollars = Long.MAX_VALUE,
                    operatingCashFlowDollars = 1,
                ),
            ),
            sectorBenchmarks = null,
        ).factors.single { it.key == "Conv" }.comparisons.single().observed

        assertEquals(Int.MAX_VALUE, observed)
    }

    @Test
    fun sector_adjusted_yield_pins_stock_and_sector_bps() {
        var comparison = OpportunityEngine.aggressiveV4FundamentalsScore(
            baseDetail(
                fundamentals = fundamentals(
                    freeCashFlowDollars = 4_000_000_000,
                    marketCapDollars = 100_000_000_000,
                ),
            ),
            sectorBenchmarks = SectorBenchmarks(
                forwardPeHundredths = null,
                enterpriseToEbitdaHundredths = null,
                priceToBookHundredths = null,
                returnOnEquityBps = null,
                netDebtToEbitdaHundredths = null,
                fcfYieldBps = 100,
            ),
        ).factors.single { it.key == "FCFy§" }.comparisons.single()

        assertEquals(listOf(400, 100, "FCF / market cap"), listOf(comparison.observed, comparison.reference, comparison.why))
    }

    @Test
    fun a_tiny_size_does_not_overflow_yield_bps() {
        var inputBps = OpportunityEngine.aggressiveV4FundamentalsScore(
            baseDetail(
                fundamentals = fundamentals(
                    freeCashFlowDollars = Long.MAX_VALUE,
                    marketCapDollars = 1,
                ),
            ),
            sectorBenchmarks = null,
        ).factors.single { it.key.startsWith("FCFy") }.inputBps

        assertEquals(Int.MAX_VALUE, inputBps)
    }

    @Test
    fun yield_against_ev_names_ocf_the_denominator() {
        var why = OpportunityEngine.aggressiveV4FundamentalsScore(
            baseDetail(
                fundamentals = fundamentals(
                    operatingCashFlowDollars = 8_000_000_000,
                    marketCapDollars = 100_000_000_000,
                    totalCashDollars = 0,
                    totalDebtDollars = 100_000_000_000,
                ),
            ),
            sectorBenchmarks = null,
        ).factors.single { it.key == "OCF" }.comparisons.single().why

        assertEquals("OCF / market cap", why)
    }

    @Test
    fun ocf_without_a_size_base_does_not_vote() {
        var keys = OpportunityEngine.aggressiveV4FundamentalsScore(
            baseDetail(
                marketPriceCents = 0,
                fundamentals = fundamentals(operatingCashFlowDollars = 1_200_000_000),
            ),
            sectorBenchmarks = null,
        ).factors.map { it.key }

        assertFalse("OCF" in keys)
    }

    @Test
    fun a_bank_with_only_series_ocf_still_names_the_skip() {
        var keys = OpportunityEngine.aggressiveV4FundamentalsScore(
            baseDetail(
                fundamentals = fundamentals(
                    sectorName = "Financial Services",
                    industryName = "Banks — Diversified",
                    marketCapDollars = 100_000_000_000,
                ),
            ),
            sectorBenchmarks = null,
            timeseries = FundamentalTimeseries(
                operatingCashFlow = listOf(AnnualReportedValue("2024-12-31", 8_000_000_000.0)),
            ),
        ).factors.map { it.key }

        assertEquals(listOf("FCFy∅ financial"), keys.filter { it.startsWith("FCFy") })
    }

    @Test
    fun a_bank_with_only_ocf_still_names_the_skip() {
        var keys = OpportunityEngine.aggressiveV4FundamentalsScore(
            baseDetail(
                fundamentals = fundamentals(
                    sectorName = "Financial Services",
                    industryName = "Banks — Diversified",
                    operatingCashFlowDollars = 8_000_000_000,
                    marketCapDollars = 100_000_000_000,
                ),
            ),
            sectorBenchmarks = null,
        ).factors.map { it.key }

        assertEquals(listOf("FCFy∅ financial"), keys.filter { it.startsWith("FCFy") })
    }

    @Test
    fun a_sector_yield_centre_moves_the_same_fcf() {
        var absolute = cashPoints(
            freeCashFlowDollars = 4_000_000_000,
            marketCapDollars = 100_000_000_000,
        )
        var vsCheapSector = OpportunityEngine.aggressiveV4FundamentalsScore(
            baseDetail(
                fundamentals = fundamentals(
                    freeCashFlowDollars = 4_000_000_000,
                    marketCapDollars = 100_000_000_000,
                ),
            ),
            sectorBenchmarks = SectorBenchmarks(
                forwardPeHundredths = null,
                enterpriseToEbitdaHundredths = null,
                priceToBookHundredths = null,
                returnOnEquityBps = null,
                netDebtToEbitdaHundredths = null,
                fcfYieldBps = 100,
            ),
        ).factors.single { it.key == "FCFy§" }.bucketPoints

        assertTrue(vsCheapSector > absolute, "absolute=$absolute vsCheap=$vsCheapSector")
    }

    @Test
    fun fcf_without_a_size_base_does_not_vote() {
        var keys = OpportunityEngine.aggressiveV4FundamentalsScore(
            baseDetail(
                marketPriceCents = 0,
                fundamentals = fundamentals(freeCashFlowDollars = 1_200_000_000),
            ),
            sectorBenchmarks = null,
        ).factors.map { it.key }

        assertEquals(emptyList(), keys.filter { it == "FCF" || it.startsWith("FCFy") })
    }

    @Test
    fun series_shares_caption_names_price_times_shares() {
        var why = OpportunityEngine.aggressiveV4FundamentalsScore(
            baseDetail(
                marketPriceCents = 10_000,
                fundamentals = fundamentals(
                    freeCashFlowDollars = 8_000_000_000,
                    marketCapDollars = null,
                    sharesOutstanding = null,
                ),
            ),
            sectorBenchmarks = null,
            timeseries = FundamentalTimeseries(
                dilutedAverageShares = listOf(AnnualReportedValue("2024-12-31", 1_000_000_000.0)),
            ),
        ).factors.single { it.key.startsWith("FCFy") }.comparisons.single().why

        assertEquals("FCF / price × shares", why)
    }

    private fun cashPoints(
        freeCashFlowDollars: Long,
        marketCapDollars: Long? = null,
        sharesOutstanding: Long? = 1_000_000_000,
        totalCashDollars: Long? = null,
        totalDebtDollars: Long? = null,
    ) = OpportunityEngine.aggressiveV4FundamentalsScore(
        baseDetail(
            marketPriceCents = 10_000,
            fundamentals = fundamentals(
                freeCashFlowDollars = freeCashFlowDollars,
                marketCapDollars = marketCapDollars,
                sharesOutstanding = sharesOutstanding.takeIf { marketCapDollars == null },
                totalCashDollars = totalCashDollars,
                totalDebtDollars = totalDebtDollars,
            ),
        ),
        sectorBenchmarks = null,
    ).factors.single { it.key.startsWith("FCFy") }.bucketPoints

    private fun ocfPoints(operatingCashFlowDollars: Long) = OpportunityEngine.aggressiveV4FundamentalsScore(
        baseDetail(
            marketPriceCents = 10_000,
            fundamentals = fundamentals(
                operatingCashFlowDollars = operatingCashFlowDollars,
                sharesOutstanding = 1_000_000_000,
            ),
        ),
        sectorBenchmarks = null,
    ).factors.single { it.key == "OCF" }.bucketPoints
}
