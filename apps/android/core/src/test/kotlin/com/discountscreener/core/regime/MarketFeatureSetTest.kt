package com.discountscreener.core.regime

import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ChartRangeSummary
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.OpportunityScoringModel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What V4's narrower market bucket keeps, and what it must go on doing after each removal.
 *
 * The removals are justified by measured overlap, one term at a time. These tests are the other
 * half of that: the properties that would be silently lost if a removal went one term too far. A
 * market bucket that quietly stopped following trends would look fine on any snapshot taken in a
 * stance that does not weight trend, which is most of them.
 */
class MarketFeatureSetTest {

    /**
     * The regression trap the per-term measurement exposed.
     *
     * Deploy weights trend at 1.0 and anti-extension at 0.2; Euphoria reverses it, 0.2 against 1.0.
     * The same stretched chart is therefore a rally to ride under one stance and a top to avoid
     * under the other, and V4 must keep reading it both ways. Nothing else in the suite would
     * notice V4 losing its trend-following mode, because that loss only shows in a stance no
     * snapshot happened to hold.
     */
    @Test
    fun a_strong_uptrend_scores_higher_under_deploy_than_under_euphoria() {
        var underDeploy = v4Score(deploy())
        var underEuphoria = v4Score(euphoria())

        assertEquals(true, underDeploy > underEuphoria, "$underDeploy is not above $underEuphoria")
    }

    /**
     * One observable, two readings, still in opposition after the narrowing. This is the claim
     * `RegimeFitTermsTest` fixes for V3, asserted again on V4's set because the set is what
     * changed.
     */
    @Test
    fun v4_keeps_a_trend_term_and_an_anti_extension_term_with_opposite_signs() {
        var signs = v4Terms(deploy())
            .filter { it.factor == RegimeCauseFactor.Trend || it.factor == RegimeCauseFactor.Extension }
            .sortedBy { it.factor.name }
            .map { it.factor to (it.signed > 0.0) }

        assertEquals(
            listOf(RegimeCauseFactor.Extension to false, RegimeCauseFactor.Trend to true),
            signs,
        )
    }

    /**
     * The removal itself: V3 scores a standalone quality term, V4 does not.
     *
     * ρ(F, t_quality) = +0.783 on 498 live rows, same sign in every stance — the fundamentals
     * bucket already holds this fact, and a weighted mean of two readings of one fact is that fact
     * counted twice.
     */
    @Test
    fun the_quality_term_is_scored_by_v3_and_not_by_v4() {
        var v3 = regimeFitTerms(qualityFundamentals(), stretchedChart(), deploy(), MarketFeatureSet.Full)
        var v4 = v4Terms(deploy())

        assertEquals(
            listOf(true, false),
            listOf(v3, v4).map { terms -> terms.any { it.factor == RegimeCauseFactor.Quality } },
        )
    }

    /**
     * Quality stops being scored and does not stop being computed, because the oversold term is
     * gated on it: an oversold junk name is not a dip to buy. A removal that deleted the feature
     * rather than the term would take the gate with it.
     */
    @Test
    fun v4_still_gates_the_oversold_term_on_quality() {
        var gated = regimeFitTerms(junkFundamentals(), oversoldChart(), bloodInStreets(), V4)
        var scored = regimeFitTerms(qualityFundamentals(), oversoldChart(), bloodInStreets(), V4)

        assertEquals(
            listOf(false, true),
            listOf(gated, scored).map { terms -> terms.any { it.factor == RegimeCauseFactor.OversoldQual } },
        )
    }

    /**
     * The coverage floor counts the features the model can turn into a term, and nothing else.
     *
     * The fixture holds exactly two features: quality, and a defensive sector. V3 counts both and
     * clears the floor of two. V4 does not score quality, so it counts one and refuses — which is
     * right, because the fourth bucket would otherwise be one sector flag wearing the authority of
     * a market reading.
     */
    @Test
    fun a_symbol_whose_only_features_are_ones_v4_drops_reports_no_fourth_bucket() {
        var v3 = scoreRegimeFit(qualityInADefensiveSector(), null, deploy(), MarketFeatureSet.Full)
        var v4 = scoreRegimeFit(qualityInADefensiveSector(), null, deploy(), V4)

        assertEquals(
            listOf(false, true),
            listOf(v3, v4).map { it.unavailableReason == MarketContextUnavailableReason.InsufficientAssetData },
        )
    }

    /** The model decides the set, and V4 is the only model that narrows it. */
    @Test
    fun only_v4_reads_the_narrower_set() {
        assertEquals(
            listOf(MarketFeatureSet.Full, MarketFeatureSet.Full, MarketFeatureSet.Full, MarketFeatureSet.Full, V4),
            OpportunityScoringModel.entries.map { it.marketFeatureSet() },
        )
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private fun v4Terms(policy: RegimeScoringPolicy) =
        regimeFitTerms(qualityFundamentals(), stretchedChart(), policy, V4)

    private fun v4Score(policy: RegimeScoringPolicy) = requireNotNull(
        scoreRegimeFit(qualityFundamentals(), stretchedChart(), policy, V4).score,
    ) { "the fixture must score, or the comparison is between two refusals" }

    private fun policyFor(stance: String, regime: String, band: String) = requireNotNull(
        RegimeScoringPolicy.fromRegime(
            MarketRegime(
                primaryRegime = regime,
                environmentBand = band,
                actionStance = stance,
                globalConfidenceBps = 8_000,
            ),
        ),
    )

    /** Trend at full weight, anti-extension near zero — the stance that leans into a rally. */
    private fun deploy() = policyFor("Deploy", "Bull", "RiskOn")

    /** The mirror image: anti-extension at full weight, trend near zero. */
    private fun euphoria() = policyFor("Euphoria", "Bull", "RiskOn")

    /** The one stance that pays full weight for an oversold quality name. */
    private fun bloodInStreets() = policyFor("BloodInStreets", "Bear", "RiskOff")

    private fun qualityFundamentals() = FundamentalSnapshot(
        symbol = "GOOD",
        marketCapDollars = 10_000_000_000L,
        returnOnEquityBps = 2_000,
        debtToEquityHundredths = 30,
        freeCashFlowDollars = 800_000_000L,
        operatingCashFlowDollars = 1_000_000_000L,
        betaMillis = 700,
    )

    /**
     * Two features and no more: quality, from the return-on-equity and leverage legs, and a
     * defensive sector. No beta, no market cap, no multiples, and no chart — each of those would
     * add a feature and stop the fixture from sitting on the floor.
     */
    private fun qualityInADefensiveSector() = FundamentalSnapshot(
        symbol = "GOOD",
        sectorName = "Utilities",
        returnOnEquityBps = 2_000,
        debtToEquityHundredths = 30,
    )

    /** Loss making and heavily indebted: below the gate that lets an oversold name score. */
    private fun junkFundamentals() = FundamentalSnapshot(
        symbol = "JUNK",
        marketCapDollars = 10_000_000_000L,
        returnOnEquityBps = -3_000,
        debtToEquityHundredths = 600,
        freeCashFlowDollars = -400_000_000L,
        operatingCashFlowDollars = -200_000_000L,
        betaMillis = 2_400,
    )

    /** Price above every average, near the top of its year, and hot on RSI. */
    private fun stretchedChart() = ChartRangeSummary(
        range = ChartRange.Year,
        capturedAt = 0L,
        candleCount = 260,
        latestCloseCents = 12_000L,
        ema20Cents = 11_000L,
        ema50Cents = 10_000L,
        ema200Cents = 8_000L,
        latestWilderRsi = 78.0,
        volumeRatioHundredths = 100,
        pos52wPct = 96.0,
        bbPercentB = 1.0,
    )

    /** The other end of the same dial: beaten down, cold on RSI, at the bottom of its year. */
    private fun oversoldChart() = ChartRangeSummary(
        range = ChartRange.Year,
        capturedAt = 0L,
        candleCount = 260,
        latestCloseCents = 6_000L,
        ema20Cents = 7_000L,
        ema50Cents = 8_500L,
        ema200Cents = 10_000L,
        latestWilderRsi = 21.0,
        volumeRatioHundredths = 100,
        pos52wPct = 4.0,
        bbPercentB = 0.0,
    )

    private companion object {
        val V4 = MarketFeatureSet.NonOverlapping
    }
}
