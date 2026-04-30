package com.discountscreener.core.engine

import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ChartRangeSummary
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.ExternalSignalStatus
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.QualificationStatus
import com.discountscreener.core.model.SymbolDetail
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Invariant-style tests pinning AggressiveV2 behaviour.
 *
 * These tests are intentionally written as **invariants** rather than golden snapshots
 * so that future weight calibration does not require rewriting every assertion. Each test
 * pins one of the V2 design contracts called out in `OpportunityEngine.aggressiveV2*Score`.
 */
class AggressiveV2ScoringTest {

    // -----------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------

    private fun baseDetail(
        marketPriceCents: Long = 10_000,
        externalStatus: ExternalSignalStatus = ExternalSignalStatus.Missing,
        weightedExternalSignalFairValueCents: Long? = null,
        externalSignalLowFairValueCents: Long? = null,
        externalSignalHighFairValueCents: Long? = null,
        analystOpinionCount: Int? = null,
        recommendationMeanHundredths: Int? = null,
        externalSignalAgeSeconds: Long? = null,
        fundamentals: FundamentalSnapshot? = null,
    ): SymbolDetail = SymbolDetail(
        symbol = "TEST",
        profitable = true,
        marketPriceCents = marketPriceCents,
        intrinsicValueCents = marketPriceCents * 2,
        gapBps = 5_000,
        upsideBps = 5_000,
        minimumGapBps = 2_500,
        qualification = QualificationStatus.Qualified,
        externalStatus = externalStatus,
        externalSignalFairValueCents = weightedExternalSignalFairValueCents,
        externalSignalLowFairValueCents = externalSignalLowFairValueCents,
        externalSignalHighFairValueCents = externalSignalHighFairValueCents,
        weightedExternalSignalFairValueCents = weightedExternalSignalFairValueCents,
        weightedAnalystCount = analystOpinionCount,
        externalSignalGapBps = null,
        externalSignalAgeSeconds = externalSignalAgeSeconds,
        externalSignalMaxAgeSeconds = 7L * 86_400L,
        analystOpinionCount = analystOpinionCount,
        recommendationMeanHundredths = recommendationMeanHundredths,
        strongBuyCount = null,
        buyCount = null,
        holdCount = null,
        sellCount = null,
        strongSellCount = null,
        fundamentals = fundamentals,
        confidence = ConfidenceBand.High,
        lastSequence = 1,
        updateCount = 1,
        isWatched = false,
        companyName = "Test Inc",
    )

    private fun fundamentals(
        freeCashFlowDollars: Long? = null,
        operatingCashFlowDollars: Long? = null,
        marketCapDollars: Long? = null,
        returnOnEquityBps: Int? = null,
        earningsGrowthBps: Int? = null,
        debtToEquityHundredths: Int? = null,
        totalCashDollars: Long? = null,
        totalDebtDollars: Long? = null,
        forwardPeHundredths: Int? = null,
    ) = FundamentalSnapshot(
        symbol = "TEST",
        marketCapDollars = marketCapDollars,
        freeCashFlowDollars = freeCashFlowDollars,
        operatingCashFlowDollars = operatingCashFlowDollars,
        returnOnEquityBps = returnOnEquityBps,
        debtToEquityHundredths = debtToEquityHundredths,
        totalCashDollars = totalCashDollars,
        totalDebtDollars = totalDebtDollars,
        earningsGrowthBps = earningsGrowthBps,
        forwardPeHundredths = forwardPeHundredths,
    )

    private fun summary(
        latestCloseCents: Long? = 10_000,
        ema20Cents: Long? = null,
        ema50Cents: Long? = null,
        ema200Cents: Long? = null,
        macdCents: Long? = null,
        signalCents: Long? = null,
        histogramCents: Long? = null,
    ) = ChartRangeSummary(
        range = ChartRange.Year,
        capturedAt = 0,
        candleCount = 52,
        latestCloseCents = latestCloseCents,
        ema20Cents = ema20Cents,
        ema50Cents = ema50Cents,
        ema200Cents = ema200Cents,
        macdCents = macdCents,
        signalCents = signalCents,
        histogramCents = histogramCents,
    )

    // -----------------------------------------------------------------------------
    // Contract: missing data is not a penalty (separates "absent" from "bad")
    // -----------------------------------------------------------------------------

    @Test
    fun fundamentals_returns_null_when_no_fundamental_signals_are_available() {
        val (score, signals) = OpportunityEngine.aggressiveV2FundamentalsScore(baseDetail(fundamentals = fundamentals()))
        assertNull(score)
        assertEquals(emptyList(), signals)
    }

    @Test
    fun technicals_returns_null_when_chart_summary_is_missing() {
        val (score, _) = OpportunityEngine.aggressiveV2TechnicalScore(null)
        assertNull(score)
    }

    @Test
    fun forecasts_returns_null_when_no_forecast_signals_are_available() {
        val (score, _) = OpportunityEngine.aggressiveV2ForecastScore(baseDetail(), analysis = null)
        assertNull(score)
    }

    @Test
    fun missing_fcf_does_not_penalise_when_ocf_is_positive() {
        // Regression for the V1 bug where unwrap_or(0) treated missing FCF as cash burn.
        val onlyOcf = OpportunityEngine.aggressiveV2FundamentalsScore(
            baseDetail(fundamentals = fundamentals(operatingCashFlowDollars = 50_000)),
        )
        assertNotNull(onlyOcf.first)
        assertTrue(onlyOcf.first!! > 0, "positive OCF without FCF should be net positive, got ${onlyOcf.first}")
    }

    @Test
    fun single_positive_signal_cannot_saturate_a_fundamentals_bucket() {
        val onlyFcf = OpportunityEngine.aggressiveV2FundamentalsScore(
            baseDetail(fundamentals = fundamentals(freeCashFlowDollars = 8_000_000, marketCapDollars = 100_000_000)),
        ).first!!

        assertEquals(25, onlyFcf)
    }

    // -----------------------------------------------------------------------------
    // Contract: bucket scores stay within [-100, +100]
    // -----------------------------------------------------------------------------

    @Test
    fun fundamentals_bucket_score_is_bounded() {
        val perfect = OpportunityEngine.aggressiveV2FundamentalsScore(
            baseDetail(
                fundamentals = fundamentals(
                    freeCashFlowDollars = 10_000_000_000,
                    marketCapDollars = 50_000_000_000,
                    returnOnEquityBps = 5_000,
                    earningsGrowthBps = 5_000,
                    debtToEquityHundredths = 10,
                    forwardPeHundredths = 500,
                ),
            ),
        ).first!!
        val terrible = OpportunityEngine.aggressiveV2FundamentalsScore(
            baseDetail(
                fundamentals = fundamentals(
                    freeCashFlowDollars = -10_000_000_000,
                    marketCapDollars = 50_000_000_000,
                    returnOnEquityBps = -5_000,
                    earningsGrowthBps = -5_000,
                    debtToEquityHundredths = 1_000,
                    forwardPeHundredths = 10_000,
                ),
            ),
        ).first!!
        assertTrue(perfect in -100..100, "perfect=$perfect")
        assertTrue(terrible in -100..100, "terrible=$terrible")
        assertEquals(100, perfect)
        assertEquals(-100, terrible)
    }

    @Test
    fun technicals_bucket_score_is_bounded() {
        val perfect = OpportunityEngine.aggressiveV2TechnicalScore(
            summary(
                latestCloseCents = 12_000,
                ema20Cents = 10_000, // +20% vs price -> ramp clamped to +1
                ema50Cents = 9_000,
                ema200Cents = 7_000,
                macdCents = 200,
                signalCents = 50,
                histogramCents = 600, // 6% of price -> ramp clamped to +1
            ),
        ).first!!
        val terrible = OpportunityEngine.aggressiveV2TechnicalScore(
            summary(
                latestCloseCents = 7_000,
                ema20Cents = 10_000, // -30% vs price -> ramp clamped to -1
                ema50Cents = 12_000, // 20/50 delta ~= -16.7% -> ramp clamped to -1
                ema200Cents = 15_000, // 50/200 delta = -20% -> ramp clamped to -1
                macdCents = 50,
                signalCents = 200,
                histogramCents = -600,
            ),
        ).first!!
        assertEquals(100, perfect)
        assertEquals(-100, terrible)
    }

    // -----------------------------------------------------------------------------
    // Contract: smooth ramps, no cliff at zero
    // -----------------------------------------------------------------------------

    @Test
    fun fcf_one_dollar_difference_does_not_swing_score_by_more_than_one_point() {
        val tinyPositive = OpportunityEngine.aggressiveV2FundamentalsScore(
            baseDetail(fundamentals = fundamentals(freeCashFlowDollars = 1, marketCapDollars = 1_000_000_000)),
        ).first!!
        val tinyNegative = OpportunityEngine.aggressiveV2FundamentalsScore(
            baseDetail(fundamentals = fundamentals(freeCashFlowDollars = -1, marketCapDollars = 1_000_000_000)),
        ).first!!
        // V1 swung this case by 4 points (+2 -> -2). V2 must not.
        assertTrue(
            abs(tinyPositive - tinyNegative) <= 1,
            "smooth ramp must not produce a cliff at FCF=0; tinyPositive=$tinyPositive tinyNegative=$tinyNegative",
        )
    }

    @Test
    fun growth_just_below_ten_percent_is_close_to_just_above_ten_percent() {
        val just_below = OpportunityEngine.aggressiveV2FundamentalsScore(
            baseDetail(fundamentals = fundamentals(earningsGrowthBps = 999)),
        ).first!!
        val just_above = OpportunityEngine.aggressiveV2FundamentalsScore(
            baseDetail(fundamentals = fundamentals(earningsGrowthBps = 1_001)),
        ).first!!
        assertTrue(abs(just_below - just_above) <= 2, "no cliff at 10% growth boundary; below=$just_below above=$just_above")
    }

    // -----------------------------------------------------------------------------
    // Contract: trend signals are not triple-counted
    // -----------------------------------------------------------------------------

    @Test
    fun strong_uptrend_does_not_exceed_trend_weight_budget() {
        // A trend so strong that every EMA delta saturates at +1 must still respect the
        // [-100, +100] bucket cap and not blow up via additive double-counting.
        val score = OpportunityEngine.aggressiveV2TechnicalScore(
            summary(
                latestCloseCents = 20_000,
                ema20Cents = 10_000,
                ema50Cents = 5_000,
                ema200Cents = 2_500,
                macdCents = 1_000,
                signalCents = 100,
                histogramCents = 2_000,
            ),
        ).first!!
        assertEquals(100, score)
    }

    @Test
    fun pure_trend_only_score_is_below_full_score_when_macd_data_absent() {
        val trendOnly = OpportunityEngine.aggressiveV2TechnicalScore(
            summary(
                latestCloseCents = 12_000,
                ema20Cents = 10_000,
                ema50Cents = 9_000,
                ema200Cents = 7_000,
            ),
        ).first!!
        // Trend stack alone (price/EMA20 + EMA20/50 + EMA50/200) cannot reach +100, since
        // momentum/MACD weights are reserved for those signals, so a fully saturated trend
        // stack stops at the 60-point trend budget.
        assertEquals(60, trendOnly)
    }

    // -----------------------------------------------------------------------------
    // Contract: weighted upside and DCF margin are not double counted
    // -----------------------------------------------------------------------------

    @Test
    fun forecast_uses_max_of_weighted_and_dcf_upside_not_their_sum() {
        // Both signals carry moderate (not saturated) upside. If the implementation summed
        // them, the combined upside would saturate the ramp and the score would jump.
        // Picking max(weighted, dcf) keeps the score equal to the weighted-only case.
        val analysisModerate = DcfAnalysis(
            bearIntrinsicValueCents = 12_500,
            baseIntrinsicValueCents = 13_000, // ~23% upside vs 10000 -> ramp ~+0.43
            bullIntrinsicValueCents = 13_500,
            waccBps = 900,
            baseGrowthBps = 1_200,
            netDebtDollars = 0,
        )
        val detail = baseDetail(
            marketPriceCents = 10_000,
            weightedExternalSignalFairValueCents = 13_000, // ~30% upside, also moderate
        )

        val onlyWeighted = OpportunityEngine.aggressiveV2ForecastScore(detail, analysis = null).first!!
        val both = OpportunityEngine.aggressiveV2ForecastScore(detail, analysisModerate).first!!

        assertEquals(onlyWeighted, both, "weighted+DCF must not be additively combined; equal valuation gap should not be credited twice")
    }

    // -----------------------------------------------------------------------------
    // Contract: previously unused signals (Forward P/E) influence the score
    // -----------------------------------------------------------------------------

    @Test
    fun cheap_forward_pe_increases_fundamentals_score() {
        val withoutPe = OpportunityEngine.aggressiveV2FundamentalsScore(
            baseDetail(
                fundamentals = fundamentals(
                    freeCashFlowDollars = 1_000_000,
                    marketCapDollars = 100_000_000,
                    returnOnEquityBps = 1_500,
                ),
            ),
        ).first!!
        val withCheapPe = OpportunityEngine.aggressiveV2FundamentalsScore(
            baseDetail(
                fundamentals = fundamentals(
                    freeCashFlowDollars = 1_000_000,
                    marketCapDollars = 100_000_000,
                    returnOnEquityBps = 1_500,
                    forwardPeHundredths = 900,
                ),
            ),
        ).first!!
        assertTrue(withCheapPe > withoutPe, "cheap forward P/E should improve fundamentals score; without=$withoutPe with=$withCheapPe")
    }

    @Test
    fun expensive_forward_pe_decreases_fundamentals_score() {
        val withoutPe = OpportunityEngine.aggressiveV2FundamentalsScore(
            baseDetail(
                fundamentals = fundamentals(
                    freeCashFlowDollars = 1_000_000,
                    marketCapDollars = 100_000_000,
                    returnOnEquityBps = 1_500,
                ),
            ),
        ).first!!
        val withExpensivePe = OpportunityEngine.aggressiveV2FundamentalsScore(
            baseDetail(
                fundamentals = fundamentals(
                    freeCashFlowDollars = 1_000_000,
                    marketCapDollars = 100_000_000,
                    returnOnEquityBps = 1_500,
                    forwardPeHundredths = 8_000,
                ),
            ),
        ).first!!
        assertTrue(withExpensivePe < withoutPe, "expensive forward P/E should reduce fundamentals score; without=$withoutPe with=$withExpensivePe")
    }

    // -----------------------------------------------------------------------------
    // Contract: stale forecasts decay
    // -----------------------------------------------------------------------------

    @Test
    fun stale_forecast_bucket_decays_below_a_fresh_one() {
        val analysis = DcfAnalysis(
            bearIntrinsicValueCents = 15_000,
            baseIntrinsicValueCents = 18_000,
            bullIntrinsicValueCents = 21_000,
            waccBps = 900,
            baseGrowthBps = 1_200,
            netDebtDollars = 0,
        )
        val fresh = OpportunityEngine.aggressiveV2ForecastScore(
            baseDetail(
                weightedExternalSignalFairValueCents = 18_000,
                analystOpinionCount = 12,
                recommendationMeanHundredths = 160,
                externalSignalAgeSeconds = 0,
            ),
            analysis,
        ).first!!
        val stale = OpportunityEngine.aggressiveV2ForecastScore(
            baseDetail(
                weightedExternalSignalFairValueCents = 18_000,
                analystOpinionCount = 12,
                recommendationMeanHundredths = 160,
                externalSignalAgeSeconds = 60L * 86_400L, // 60 days
            ),
            analysis,
        ).first!!
        assertTrue(fresh > 0)
        assertTrue(stale < fresh, "stale forecast bucket should be smaller than fresh; fresh=$fresh stale=$stale")
    }

    // -----------------------------------------------------------------------------
    // Contract: composite is coverage-weighted mean + small bonus, not raw sum
    // -----------------------------------------------------------------------------

    @Test
    fun composite_is_coverage_weighted_mean_plus_bonus_for_v2() {
        val detail = baseDetail(
            weightedExternalSignalFairValueCents = 18_000,
            analystOpinionCount = 12,
            recommendationMeanHundredths = 160,
            fundamentals = fundamentals(
                freeCashFlowDollars = 1_000_000,
                marketCapDollars = 100_000_000,
                returnOnEquityBps = 1_500,
                earningsGrowthBps = 1_000,
                debtToEquityHundredths = 50,
                forwardPeHundredths = 1_500,
            ),
        )
        val analysis = DcfAnalysis(
            bearIntrinsicValueCents = 15_000,
            baseIntrinsicValueCents = 18_000,
            bullIntrinsicValueCents = 21_000,
            waccBps = 900,
            baseGrowthBps = 1_200,
            netDebtDollars = 0,
        )
        val sum = summary(
            latestCloseCents = 11_000,
            ema20Cents = 10_500,
            ema50Cents = 9_500,
            ema200Cents = 8_500,
            macdCents = 100,
            signalCents = 50,
            histogramCents = 50,
        )

        val breakdown = OpportunityEngine.scoreWithModel(detail, sum, analysis, OpportunityScoringModel.AggressiveV2)
        assertEquals(3, breakdown.coverageCount)

        val expectedMean = ((breakdown.fundamentalsScore!! + breakdown.technicalScore!! + breakdown.forecastScore!!).toDouble() / 3.0)
        // Coverage bonus = 5 * (3-1) = 10
        val expected = (expectedMean + 10).let { if (it >= 0) (it + 0.5).toInt() else (it - 0.5).toInt() }
        assertEquals(expected, breakdown.compositeScore)
    }

    @Test
    fun composite_full_coverage_outranks_one_bucket_when_per_bucket_score_is_equal() {
        // The cleanest way to pin the coverage-bonus invariant is to compare two symbols
        // whose populated buckets all score the *same* number. Three buckets at +50 each
        // must produce a higher composite than one bucket at +50.
        // We construct this directly by stubbing identical bucket scores via the public
        // composite formula path through scoreWithModel.

        // Symbol A: a single forecast bucket. We tune inputs so its forecast bucket lands
        // around +50 and the other two buckets are absent.
        val detailA = baseDetail(
            weightedExternalSignalFairValueCents = 13_000, // ~+30% upside -> ramp ~+0.71
            analystOpinionCount = 9,
            recommendationMeanHundredths = 200,
        )
        // Symbol B: identical forecast inputs PLUS modest fundamentals + modest technicals.
        val detailB = detailA.copy(
            fundamentals = fundamentals(
                freeCashFlowDollars = 2_000_000,
                marketCapDollars = 100_000_000,
                returnOnEquityBps = 1_000,
                earningsGrowthBps = 500,
                debtToEquityHundredths = 100,
                forwardPeHundredths = 2_000,
            ),
        )
        val sumB = summary(
            latestCloseCents = 10_300,
            ema20Cents = 10_100,
            ema50Cents = 9_800,
            ema200Cents = 9_400,
            macdCents = 30,
            signalCents = 10,
            histogramCents = 12,
        )

        val a = OpportunityEngine.scoreWithModel(detailA, null, null, OpportunityScoringModel.AggressiveV2)
        val b = OpportunityEngine.scoreWithModel(detailB, sumB, null, OpportunityScoringModel.AggressiveV2)

        assertEquals(1, a.coverageCount)
        assertEquals(3, b.coverageCount)
        assertEquals(a.forecastScore, b.forecastScore, "forecast bucket inputs were intentionally identical")
        // The +10 coverage bonus on B (3 buckets) plus equal-or-positive fundamentals/tech
        // means B's composite must be strictly greater than A's.
        assertTrue(
            b.compositeScore > a.compositeScore,
            "three-bucket symbol must outrank one-bucket symbol with identical forecast bucket; a=${a.compositeScore} b=${b.compositeScore}",
        )
    }

    // -----------------------------------------------------------------------------
    // Contract: smoothRamp helper itself
    // -----------------------------------------------------------------------------

    @Test
    fun smooth_ramp_returns_minus_one_at_or_below_lower_bound() {
        assertEquals(-1.0, OpportunityEngine.smoothRamp(observed = -100.0, lower = 0.0, upper = 10.0))
        assertEquals(-1.0, OpportunityEngine.smoothRamp(observed = 0.0, lower = 0.0, upper = 10.0))
    }

    @Test
    fun smooth_ramp_returns_plus_one_at_or_above_upper_bound() {
        assertEquals(1.0, OpportunityEngine.smoothRamp(observed = 10.0, lower = 0.0, upper = 10.0))
        assertEquals(1.0, OpportunityEngine.smoothRamp(observed = 100.0, lower = 0.0, upper = 10.0))
    }

    @Test
    fun smooth_ramp_interpolates_linearly_between_bounds() {
        assertEquals(0.0, OpportunityEngine.smoothRamp(observed = 5.0, lower = 0.0, upper = 10.0))
        assertEquals(0.5, OpportunityEngine.smoothRamp(observed = 7.5, lower = 0.0, upper = 10.0))
        assertEquals(-0.5, OpportunityEngine.smoothRamp(observed = 2.5, lower = 0.0, upper = 10.0))
    }
}
