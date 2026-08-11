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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Invariant-style tests pinning AggressiveV3 behaviour.
 *
 * Written as invariants (not golden weight snapshots) so calibration of constants
 * does not force a full rewrite. Each test pins one V3 design contract.
 */
class AggressiveV3ScoringTest {

    @Test
    fun fundamentals_null_without_snapshot() {
        val (score, signals) = OpportunityEngine.aggressiveV3FundamentalsScore(baseDetail(fundamentals = null))
        assertNull(score)
        assertTrue(signals.isEmpty())
    }

    @Test
    fun technicals_null_without_summary() {
        val (score, _) = OpportunityEngine.aggressiveV3TechnicalScore(null)
        assertNull(score)
    }

    @Test
    fun forecast_null_without_valuation_anchor() {
        val (score, _) = OpportunityEngine.aggressiveV3ForecastScore(baseDetail(), analysis = null)
        assertNull(score)
    }

    @Test
    fun single_multiple_cannot_saturate_full_valuation_budget() {
        val onlyPe = OpportunityEngine.aggressiveV3FundamentalsScore(
            baseDetail(
                fundamentals = fundamentals(
                    freeCashFlowDollars = 1_000_000,
                    marketCapDollars = 20_000_000,
                    forwardPeHundredths = 800,
                ),
            ),
        ).first!!
        val allMultiples = OpportunityEngine.aggressiveV3FundamentalsScore(
            baseDetail(
                fundamentals = fundamentals(
                    freeCashFlowDollars = 1_000_000,
                    marketCapDollars = 20_000_000,
                    forwardPeHundredths = 800,
                    enterpriseToEbitdaHundredths = 600,
                    priceToBookHundredths = 100,
                ),
            ),
        ).first!!
        assertTrue(
            allMultiples > onlyPe,
            "three cheap multiples must outscore a single cheap PE; onlyPe=$onlyPe all=$allMultiples",
        )
        assertTrue(onlyPe < 100, "single PE must not saturate fundamentals to +100; onlyPe=$onlyPe")
    }

    @Test
    fun cheap_multiples_score_higher_than_expensive_multiples() {
        val cheap = OpportunityEngine.aggressiveV3FundamentalsScore(
            baseDetail(
                fundamentals = fundamentals(
                    freeCashFlowDollars = 1_000_000,
                    marketCapDollars = 20_000_000,
                    forwardPeHundredths = 900,
                    enterpriseToEbitdaHundredths = 700,
                    priceToBookHundredths = 120,
                ),
            ),
        ).first!!
        val expensive = OpportunityEngine.aggressiveV3FundamentalsScore(
            baseDetail(
                fundamentals = fundamentals(
                    freeCashFlowDollars = 1_000_000,
                    marketCapDollars = 20_000_000,
                    forwardPeHundredths = 3_400,
                    enterpriseToEbitdaHundredths = 1_900,
                    priceToBookHundredths = 480,
                ),
            ),
        ).first!!
        assertTrue(cheap > expensive, "cheap=$cheap expensive=$expensive")
    }

    @Test
    fun cash_conversion_rewards_high_fcf_to_ocf() {
        val highConversion = OpportunityEngine.aggressiveV3FundamentalsScore(
            baseDetail(
                fundamentals = fundamentals(
                    freeCashFlowDollars = 900_000,
                    operatingCashFlowDollars = 1_000_000,
                    marketCapDollars = 20_000_000,
                ),
            ),
        ).first!!
        val lowConversion = OpportunityEngine.aggressiveV3FundamentalsScore(
            baseDetail(
                fundamentals = fundamentals(
                    freeCashFlowDollars = 100_000,
                    operatingCashFlowDollars = 1_000_000,
                    marketCapDollars = 20_000_000,
                ),
            ),
        ).first!!
        assertTrue(highConversion > lowConversion, "high=$highConversion low=$lowConversion")
    }

    @Test
    fun mid_bullish_rsi_beats_overbought_falling_rsi() {
        val midBullish = OpportunityEngine.aggressiveV3TechnicalScore(
            summary(
                latestCloseCents = 10_000,
                ema20Cents = 9_500,
                ema50Cents = 9_000,
                ema200Cents = 8_500,
                latestWilderRsi = 55.0,
                latestRsiSlope = 1.0,
            ),
        ).first!!
        val overboughtFalling = OpportunityEngine.aggressiveV3TechnicalScore(
            summary(
                latestCloseCents = 10_000,
                ema20Cents = 9_500,
                ema50Cents = 9_000,
                ema200Cents = 8_500,
                latestWilderRsi = 82.0,
                latestRsiSlope = -1.5,
            ),
        ).first!!
        assertTrue(
            midBullish > overboughtFalling,
            "midBullish=$midBullish overboughtFalling=$overboughtFalling",
        )
    }

    @Test
    fun rsi_level_ramp_peaks_near_mid_bullish() {
        val at55 = OpportunityEngine.v3RsiLevelRamp(55.0)
        val at30 = OpportunityEngine.v3RsiLevelRamp(30.0)
        val at80 = OpportunityEngine.v3RsiLevelRamp(80.0)
        assertEquals(1.0, at55, 0.001)
        assertEquals(-1.0, at30, 0.001)
        assertTrue(at80 < at55, "overbought must score below mid-bullish; at80=$at80")
        assertTrue(at80 < 0.0, "RSI 80 should not be strongly positive; at80=$at80")
    }

    @Test
    fun volume_confirmation_moves_technical_score() {
        val strongVolume = OpportunityEngine.aggressiveV3TechnicalScore(
            summary(latestCloseCents = 10_000, volumeRatioHundredths = 160),
        ).first!!
        val weakVolume = OpportunityEngine.aggressiveV3TechnicalScore(
            summary(latestCloseCents = 10_000, volumeRatioHundredths = 50),
        ).first!!
        assertTrue(strongVolume > weakVolume, "strong=$strongVolume weak=$weakVolume")
    }

    @Test
    fun bullish_recommendation_skew_raises_forecast() {
        val bullishSkew = OpportunityEngine.aggressiveV3ForecastScore(
            baseDetail(
                weightedExternalSignalFairValueCents = 15_000,
                analystOpinionCount = 12,
                recommendationMeanHundredths = 200,
                strongBuyCount = 8,
                buyCount = 3,
                holdCount = 1,
                sellCount = 0,
                strongSellCount = 0,
            ),
            analysis = null,
        ).first!!
        val bearishSkew = OpportunityEngine.aggressiveV3ForecastScore(
            baseDetail(
                weightedExternalSignalFairValueCents = 15_000,
                analystOpinionCount = 12,
                recommendationMeanHundredths = 200,
                strongBuyCount = 0,
                buyCount = 1,
                holdCount = 1,
                sellCount = 3,
                strongSellCount = 7,
            ),
            analysis = null,
        ).first!!
        assertTrue(bullishSkew > bearishSkew, "bullish=$bullishSkew bearish=$bearishSkew")
    }

    @Test
    fun forecast_score_ignores_quant_engine_analysis() {
        val detail = baseDetail(
            weightedExternalSignalFairValueCents = 15_000,
            analystOpinionCount = 10,
        )
        val without = OpportunityEngine.aggressiveV3ForecastScore(detail, analysis = null)
        val withHuge = OpportunityEngine.aggressiveV3ForecastScore(
            detail,
            analysis = dcf(base = 80_000, bear = 40_000, bull = 160_000),
        )
        val withWide = OpportunityEngine.aggressiveV3ForecastScore(
            detail,
            analysis = dcf(base = 15_000, bear = 5_000, bull = 30_000),
        )
        assertEquals(without.first, withHuge.first)
        assertEquals(without.first, withWide.first)
        assertEquals(without.second, withHuge.second)
        assertEquals(without.second, withWide.second)
        assertNotNull(without.first)
    }

    @Test
    fun high_beta_haircuts_composite_relative_to_low_beta() {
        val fund = fundamentals(
            freeCashFlowDollars = 2_000_000,
            marketCapDollars = 20_000_000,
            returnOnEquityBps = 1_800,
            earningsGrowthBps = 1_200,
            debtToEquityHundredths = 40,
            forwardPeHundredths = 1_000,
        )
        val sum = summary(
            latestCloseCents = 11_000,
            ema20Cents = 10_000,
            ema50Cents = 9_500,
            ema200Cents = 9_000,
            macdCents = 50,
            signalCents = 20,
            histogramCents = 30,
            latestWilderRsi = 55.0,
            latestRsiSlope = 0.5,
            volumeRatioHundredths = 120,
        )
        val analysis = dcf(base = 16_000)
        val lowBeta = OpportunityEngine.scoreWithModel(
            detail = baseDetail(
                weightedExternalSignalFairValueCents = 15_000,
                analystOpinionCount = 12,
                recommendationMeanHundredths = 180,
                fundamentals = fund.copy(betaMillis = 700),
            ),
            summary = sum,
            analysis = analysis,
            model = OpportunityScoringModel.AggressiveV3,
        )
        val highBeta = OpportunityEngine.scoreWithModel(
            detail = baseDetail(
                weightedExternalSignalFairValueCents = 15_000,
                analystOpinionCount = 12,
                recommendationMeanHundredths = 180,
                fundamentals = fund.copy(betaMillis = 1_800),
            ),
            summary = sum,
            analysis = analysis,
            model = OpportunityScoringModel.AggressiveV3,
        )
        assertTrue(
            lowBeta.compositeScore > highBeta.compositeScore,
            "lowBeta=${lowBeta.compositeScore} highBeta=${highBeta.compositeScore}",
        )
        assertEquals(lowBeta.fundamentalsScore, highBeta.fundamentalsScore)
    }

    @Test
    fun missing_beta_does_not_penalize_composite() {
        val fund = fundamentals(
            freeCashFlowDollars = 2_000_000,
            marketCapDollars = 20_000_000,
            returnOnEquityBps = 1_500,
        )
        val withoutBeta = OpportunityEngine.scoreWithModel(
            detail = baseDetail(fundamentals = fund),
            summary = null,
            analysis = null,
            model = OpportunityScoringModel.AggressiveV3,
        )
        val lowBeta = OpportunityEngine.scoreWithModel(
            detail = baseDetail(fundamentals = fund.copy(betaMillis = 700)),
            summary = null,
            analysis = null,
            model = OpportunityScoringModel.AggressiveV3,
        )
        assertEquals(withoutBeta.compositeScore, lowBeta.compositeScore)
    }

    @Test
    fun three_bucket_coverage_outranks_single_bucket_with_same_forecast() {
        val forecastDetail = baseDetail(
            weightedExternalSignalFairValueCents = 15_000,
            analystOpinionCount = 12,
            recommendationMeanHundredths = 180,
            strongBuyCount = 6,
            buyCount = 4,
            holdCount = 2,
        )
        val analysis = dcf(base = 16_000)
        val a = OpportunityEngine.scoreWithModel(
            detail = forecastDetail,
            summary = null,
            analysis = analysis,
            model = OpportunityScoringModel.AggressiveV3,
        )
        val b = OpportunityEngine.scoreWithModel(
            detail = forecastDetail.copy(
                fundamentals = fundamentals(
                    freeCashFlowDollars = 2_000_000,
                    marketCapDollars = 20_000_000,
                    returnOnEquityBps = 1_500,
                    debtToEquityHundredths = 50,
                ),
            ),
            summary = summary(
                latestCloseCents = 11_000,
                ema20Cents = 10_000,
                ema50Cents = 9_500,
                ema200Cents = 9_000,
                macdCents = 40,
                signalCents = 10,
                histogramCents = 20,
                latestWilderRsi = 55.0,
                latestRsiSlope = 0.4,
            ),
            analysis = analysis,
            model = OpportunityScoringModel.AggressiveV3,
        )
        assertEquals(1, a.coverageCount)
        assertEquals(3, b.coverageCount)
        assertTrue(
            b.compositeScore > a.compositeScore,
            "three-bucket must outrank one-bucket; a=${a.compositeScore} b=${b.compositeScore}",
        )
    }

    @Test
    fun continuous_model_thresholds_differ_from_legacy() {
        assertEquals(8, OpportunityEngine.avoidBelowScore(OpportunityScoringModel.Legacy))
        assertEquals(10, OpportunityEngine.actAtOrAboveScore(OpportunityScoringModel.Legacy))
        assertEquals(0, OpportunityEngine.avoidBelowScore(OpportunityScoringModel.AggressiveV2))
        assertEquals(30, OpportunityEngine.actAtOrAboveScore(OpportunityScoringModel.AggressiveV2))
        assertEquals(0, OpportunityEngine.avoidBelowScore(OpportunityScoringModel.AggressiveV3))
        assertEquals(30, OpportunityEngine.actAtOrAboveScore(OpportunityScoringModel.AggressiveV3))
    }

    @Test
    fun bucket_scores_stay_within_bounds() {
        val perfect = OpportunityEngine.scoreWithModel(
            detail = baseDetail(
                weightedExternalSignalFairValueCents = 20_000,
                analystOpinionCount = 20,
                recommendationMeanHundredths = 150,
                strongBuyCount = 15,
                buyCount = 5,
                holdCount = 0,
                sellCount = 0,
                strongSellCount = 0,
                fundamentals = fundamentals(
                    freeCashFlowDollars = 5_000_000,
                    operatingCashFlowDollars = 5_000_000,
                    marketCapDollars = 20_000_000,
                    returnOnEquityBps = 2_500,
                    earningsGrowthBps = 2_000,
                    debtToEquityHundredths = 20,
                    forwardPeHundredths = 800,
                    enterpriseToEbitdaHundredths = 600,
                    priceToBookHundredths = 100,
                    betaMillis = 700,
                ),
            ),
            summary = summary(
                latestCloseCents = 12_000,
                ema20Cents = 10_000,
                ema50Cents = 9_000,
                ema200Cents = 8_000,
                macdCents = 100,
                signalCents = 20,
                histogramCents = 80,
                latestWilderRsi = 55.0,
                latestRsiSlope = 2.0,
                volumeRatioHundredths = 180,
            ),
            analysis = dcf(base = 25_000, bear = 22_000, bull = 28_000),
            model = OpportunityScoringModel.AggressiveV3,
        )
        assertNotNull(perfect.fundamentalsScore)
        assertNotNull(perfect.technicalScore)
        assertNotNull(perfect.forecastScore)
        assertTrue(perfect.fundamentalsScore!! in -100..100)
        assertTrue(perfect.technicalScore!! in -100..100)
        assertTrue(perfect.forecastScore!! in -100..100)
        assertTrue(perfect.compositeScore in -110..110)
    }
}
