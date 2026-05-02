package com.discountscreener.core.engine

import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.EstimateScenario
import com.discountscreener.core.model.ExternalSignalStatus
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.QualificationStatus
import com.discountscreener.core.model.SymbolDetail
import kotlin.test.Test
import kotlin.test.assertEquals

class IndexEstimatesEngineTest {

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun symbol(
        ticker: String,
        marketPriceCents: Long,
        marketCapDollars: Long? = null,
        analystLow: Long? = null,
        analystHigh: Long? = null,
    ) = SymbolDetail(
        symbol = ticker,
        profitable = true,
        marketPriceCents = marketPriceCents,
        intrinsicValueCents = marketPriceCents,
        gapBps = 0,
        minimumGapBps = 0,
        qualification = QualificationStatus.Qualified,
        externalStatus = ExternalSignalStatus.Missing,
        externalSignalLowFairValueCents = analystLow,
        externalSignalHighFairValueCents = analystHigh,
        externalSignalMaxAgeSeconds = 86_400L,
        fundamentals = marketCapDollars?.let { FundamentalSnapshot(symbol = ticker, marketCapDollars = it) },
        confidence = ConfidenceBand.High,
        lastSequence = 0,
        updateCount = 0,
        isWatched = false,
    )

    private fun dcf(
        ticker: String,
        bear: Long,
        base: Long,
        bull: Long,
    ) = ticker to DcfAnalysis(
        bearIntrinsicValueCents = bear,
        baseIntrinsicValueCents = base,
        bullIntrinsicValueCents = bull,
        waccBps = 800,
        baseGrowthBps = 500,
        netDebtDollars = 0L,
    )

    // ── tests ────────────────────────────────────────────────────────────────

    @Test
    fun empty_symbol_list_produces_zero_current_price() {
        var result = IndexEstimatesEngine.compute(emptyList(), emptyMap(), "test", 0L)

        assertEquals(0L, result.currentWeightedPriceCents)
    }

    @Test
    fun empty_symbol_list_produces_zero_total_symbols() {
        var result = IndexEstimatesEngine.compute(emptyList(), emptyMap(), "test", 0L)

        assertEquals(0, result.totalSymbols)
    }

    @Test
    fun empty_symbol_list_produces_zero_coverage_for_all_scenarios() {
        var result = IndexEstimatesEngine.compute(emptyList(), emptyMap(), "test", 0L)

        assertEquals(0, result.scenarios.sumOf { it.coverageCount })
    }

    @Test
    fun symbol_missing_market_cap_is_excluded_from_current_weighted_price() {
        var symbols = listOf(symbol("AAPL", marketPriceCents = 20_000L, marketCapDollars = null))

        var result = IndexEstimatesEngine.compute(symbols, emptyMap(), "test", 0L)

        assertEquals(0L, result.currentWeightedPriceCents)
    }

    @Test
    fun symbol_with_zero_market_cap_is_excluded() {
        var symbols = listOf(symbol("AAPL", marketPriceCents = 20_000L, marketCapDollars = 0L))

        var result = IndexEstimatesEngine.compute(symbols, emptyMap(), "test", 0L)

        assertEquals(0L, result.currentWeightedPriceCents)
    }

    @Test
    fun symbol_with_market_cap_but_no_dcf_counts_in_total_but_not_coverage() {
        var symbols = listOf(symbol("AAPL", marketPriceCents = 20_000L, marketCapDollars = 1_000_000L))

        var result = IndexEstimatesEngine.compute(symbols, emptyMap(), "test", 0L)

        assertEquals(1, result.totalSymbols)
    }

    @Test
    fun symbol_with_market_cap_but_no_dcf_has_zero_bear_coverage() {
        var symbols = listOf(symbol("AAPL", marketPriceCents = 20_000L, marketCapDollars = 1_000_000L))

        var result = IndexEstimatesEngine.compute(symbols, emptyMap(), "test", 0L)

        assertEquals(0, result.scenarios.first { it.scenario == EstimateScenario.BearDcf }.coverageCount)
    }

    @Test
    fun single_symbol_weighted_price_equals_fair_value() {
        var symbols = listOf(symbol("AAPL", marketPriceCents = 10_000L, marketCapDollars = 1_000_000L))
        var dcfMap = mapOf(dcf("AAPL", bear = 8_000L, base = 12_000L, bull = 16_000L))

        var result = IndexEstimatesEngine.compute(symbols, dcfMap, "test", 0L)

        assertEquals(8_000L, result.scenarios.first { it.scenario == EstimateScenario.BearDcf }.weightedPriceCents)
    }

    @Test
    fun two_symbols_weighted_average_is_market_cap_weighted() {
        // cap1=1, price1=10000; cap2=3, price2=20000 → weighted = (10000*1 + 20000*3)/(1+3) = 70000/4 = 17500
        var symbols = listOf(
            symbol("A", marketPriceCents = 10_000L, marketCapDollars = 1L),
            symbol("B", marketPriceCents = 20_000L, marketCapDollars = 3L),
        )

        var result = IndexEstimatesEngine.compute(symbols, emptyMap(), "test", 0L)

        assertEquals(17_500L, result.currentWeightedPriceCents)
    }

    @Test
    fun implied_upside_is_positive_when_fair_value_exceeds_current() {
        // current weighted = 10000. base dcf = 11000 → upside = (11000/10000 - 1)*10000 = 1000 bps
        var symbols = listOf(symbol("AAPL", marketPriceCents = 10_000L, marketCapDollars = 1L))
        var dcfMap = mapOf(dcf("AAPL", bear = 9_000L, base = 11_000L, bull = 15_000L))

        var result = IndexEstimatesEngine.compute(symbols, dcfMap, "test", 0L)

        assertEquals(1_000, result.scenarios.first { it.scenario == EstimateScenario.BaseDcf }.impliedUpsideBps)
    }

    @Test
    fun implied_upside_is_negative_when_fair_value_is_below_current() {
        // current weighted = 10000. bear dcf = 8000 → upside = (8000/10000 - 1)*10000 = -2000 bps
        var symbols = listOf(symbol("AAPL", marketPriceCents = 10_000L, marketCapDollars = 1L))
        var dcfMap = mapOf(dcf("AAPL", bear = 8_000L, base = 10_000L, bull = 12_000L))

        var result = IndexEstimatesEngine.compute(symbols, dcfMap, "test", 0L)

        assertEquals(-2_000, result.scenarios.first { it.scenario == EstimateScenario.BearDcf }.impliedUpsideBps)
    }

    @Test
    fun all_five_scenarios_produce_independent_coverage_counts_dcf() {
        var symbols = listOf(
            symbol("AAPL", marketPriceCents = 10_000L, marketCapDollars = 2L, analystLow = 9_000L, analystHigh = 13_000L),
            symbol("GOOG", marketPriceCents = 5_000L, marketCapDollars = 1L, analystLow = null, analystHigh = null),
        )
        var dcfMap = mapOf(
            dcf("AAPL", bear = 8_000L, base = 11_000L, bull = 14_000L),
            dcf("GOOG", bear = 4_500L, base = 5_500L, bull = 6_500L),
        )

        var result = IndexEstimatesEngine.compute(symbols, dcfMap, "test", 0L)

        assertEquals(2, result.scenarios.first { it.scenario == EstimateScenario.BearDcf }.coverageCount)
    }

    @Test
    fun analyst_low_coverage_is_independent_of_dcf_coverage() {
        var symbols = listOf(
            symbol("AAPL", marketPriceCents = 10_000L, marketCapDollars = 2L, analystLow = 9_000L, analystHigh = 13_000L),
            symbol("GOOG", marketPriceCents = 5_000L, marketCapDollars = 1L, analystLow = null, analystHigh = null),
        )
        var dcfMap = mapOf(
            dcf("AAPL", bear = 8_000L, base = 11_000L, bull = 14_000L),
            dcf("GOOG", bear = 4_500L, base = 5_500L, bull = 6_500L),
        )

        var result = IndexEstimatesEngine.compute(symbols, dcfMap, "test", 0L)

        assertEquals(1, result.scenarios.first { it.scenario == EstimateScenario.AnalystLow }.coverageCount)
    }

    @Test
    fun analyst_high_coverage_is_independent_of_dcf_coverage() {
        var symbols = listOf(
            symbol("AAPL", marketPriceCents = 10_000L, marketCapDollars = 2L, analystLow = 9_000L, analystHigh = 13_000L),
            symbol("GOOG", marketPriceCents = 5_000L, marketCapDollars = 1L, analystLow = null, analystHigh = null),
        )
        var dcfMap = mapOf(
            dcf("AAPL", bear = 8_000L, base = 11_000L, bull = 14_000L),
            dcf("GOOG", bear = 4_500L, base = 5_500L, bull = 6_500L),
        )

        var result = IndexEstimatesEngine.compute(symbols, dcfMap, "test", 0L)

        assertEquals(1, result.scenarios.first { it.scenario == EstimateScenario.AnalystHigh }.coverageCount)
    }

    @Test
    fun implied_upside_is_zero_when_current_weighted_price_is_zero() {
        // symbol has no market cap → currentWeightedPriceCents = 0
        var symbols = listOf(symbol("AAPL", marketPriceCents = 10_000L, marketCapDollars = null))
        var dcfMap = mapOf(dcf("AAPL", bear = 8_000L, base = 12_000L, bull = 16_000L))

        var result = IndexEstimatesEngine.compute(symbols, dcfMap, "test", 0L)

        assertEquals(0, result.scenarios.first { it.scenario == EstimateScenario.BaseDcf }.impliedUpsideBps)
    }

    @Test
    fun two_symbol_weighted_base_dcf_is_market_cap_weighted() {
        // base dcf: A=10000(cap=1), B=20000(cap=3) → (10000*1 + 20000*3)/4 = 17500
        var symbols = listOf(
            symbol("A", marketPriceCents = 5_000L, marketCapDollars = 1L),
            symbol("B", marketPriceCents = 5_000L, marketCapDollars = 3L),
        )
        var dcfMap = mapOf(
            dcf("A", bear = 5_000L, base = 10_000L, bull = 15_000L),
            dcf("B", bear = 5_000L, base = 20_000L, bull = 25_000L),
        )

        var result = IndexEstimatesEngine.compute(symbols, dcfMap, "test", 0L)

        assertEquals(17_500L, result.scenarios.first { it.scenario == EstimateScenario.BaseDcf }.weightedPriceCents)
    }

    @Test
    fun profile_name_is_passed_through() {
        var result = IndexEstimatesEngine.compute(emptyList(), emptyMap(), "sp500", 1_700_000_000L)

        assertEquals("sp500", result.profileName)
    }

    @Test
    fun timestamp_is_passed_through() {
        var result = IndexEstimatesEngine.compute(emptyList(), emptyMap(), "sp500", 1_700_000_000L)

        assertEquals(1_700_000_000L, result.computedAtEpochSeconds)
    }

    @Test
    fun total_symbols_counts_only_cap_eligible_symbols() {
        var symbols = listOf(
            symbol("A", marketPriceCents = 10_000L, marketCapDollars = 1_000_000L),
            symbol("B", marketPriceCents = 5_000L, marketCapDollars = 500_000L),
            symbol("C", marketPriceCents = 2_000L, marketCapDollars = null),
        )

        var result = IndexEstimatesEngine.compute(symbols, emptyMap(), "test", 0L)

        assertEquals(2, result.totalSymbols)
    }

    @Test
    fun total_symbols_is_zero_when_no_symbol_has_market_cap() {
        var symbols = listOf(
            symbol("A", marketPriceCents = 10_000L, marketCapDollars = null),
            symbol("B", marketPriceCents = 5_000L, marketCapDollars = null),
        )

        var result = IndexEstimatesEngine.compute(symbols, emptyMap(), "test", 0L)

        assertEquals(0, result.totalSymbols)
    }
}
