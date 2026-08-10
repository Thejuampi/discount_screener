package com.discountscreener.core.engine

import com.discountscreener.core.model.ChartRangeSummary
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.ExternalSignalStatus
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.QualificationStatus
import com.discountscreener.core.model.SymbolDetail
import com.discountscreener.core.regime.MarketRegime
import com.discountscreener.core.regime.RegimeScoreStatus
import com.discountscreener.core.regime.confidentRegime
import com.discountscreener.core.regime.summary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The fourth bucket entering the V3 composite.
 *
 * Three things are on trial and they are tested at three different seams, because a single
 * end-to-end fixture cannot isolate any of them:
 *
 *  - the **arithmetic** — coverage bonus and beta multiplier — against [OpportunityEngine.compositeScoreFor],
 *    where the inputs are numbers rather than a plausible company;
 *  - the **status precedence** against [OpportunityEngine.resolveRegimeScoreStatus], which is where
 *    a regime score of exactly zero can be pinned without hunting for a fixture that lands on it;
 *  - the **wiring** against [OpportunityEngine.scoreWithModel], end to end.
 *
 * The fit score itself belongs to the regime tests; here it is only an input.
 */
class MarketContextCompositeTest {

    // ── The arithmetic ───────────────────────────────────────────────────────

    /**
     * The guard the whole wave stands on, and the one place a literal is the right assertion: with
     * no fourth bucket and a neutral multiplier the formula must return what it returned before the
     * market dimension existed, and only a number pins that. Derivation, so a future failure says
     * which term moved rather than just "35 became 34":
     *
     *  mean  = (40 + 20 + 30) / 3      = 30.0
     *  bonus = 5.0 × (3 − 1)           = 10.0
     *  base  = 40.0
     *  ramp(1200 millis, 800, 1600)    = 0.0   (the midpoint of the beta ramp)
     *  haircut = ((0.0 + 1) / 2) × 10.0 × 1.0  = 5.0
     */
    @Test
    fun three_buckets_and_a_neutral_multiplier_reproduce_the_original_composite() {
        assertEquals(35, composite(regime = null, coverage = 3, mult = 1.0))
    }

    /**
     * A fourth bucket scoring the same as the mean of the other three still raises the composite,
     * because the coverage bonus goes from `5 × 2` to `5 × 3`. This is the term that moves every
     * V3 name at once, and it moves them whatever the fourth score says.
     */
    @Test
    fun a_fourth_bucket_is_worth_five_points_before_its_score_is_read() {
        assertEquals(
            composite(regime = null, coverage = 3) + 5,
            composite(regime = 30, coverage = 4),
        )
    }

    /** Zero is a score, and it dilutes the mean — the bonus does not paper over a bad fit. */
    @Test
    fun a_fourth_bucket_of_zero_still_counts_toward_coverage() {
        assertNotEquals(
            composite(regime = null, coverage = 3),
            composite(regime = 0, coverage = 4),
        )
    }

    /** The multiplier scales the beta haircut, so a policy asking for caution costs a levered name. */
    @Test
    fun a_policy_multiplier_above_one_deepens_the_beta_haircut() {
        assertTrue(
            composite(regime = 30, coverage = 4, mult = 2.0) <
                composite(regime = 30, coverage = 4, mult = 1.0),
            "a doubled multiplier must not raise the score",
        )
    }

    @Test
    fun a_policy_multiplier_of_zero_removes_the_beta_haircut_entirely() {
        assertEquals(
            composite(regime = 30, coverage = 4, mult = 0.0),
            composite(regime = 30, coverage = 4, mult = 1.0, betaMillis = null),
        )
    }

    /** `beta_haircut_mult.clamp(0.0, 2.5)` — a policy cannot ask for an unbounded penalty. */
    @Test
    fun the_multiplier_is_clamped_before_it_is_applied() {
        assertEquals(
            composite(regime = 30, coverage = 4, mult = 2.5),
            composite(regime = 30, coverage = 4, mult = 99.0),
        )
    }

    /** V2 has no fourth bucket, so the same call must ignore the regime argument entirely. */
    @Test
    fun the_v2_composite_ignores_a_regime_score_it_was_handed() {
        assertEquals(
            composite(model = OpportunityScoringModel.AggressiveV2, regime = null, coverage = 3),
            composite(model = OpportunityScoringModel.AggressiveV2, regime = 100, coverage = 3),
        )
    }

    // ── The status precedence ────────────────────────────────────────────────

    @Test
    fun a_scored_dimension_is_included() {
        assertEquals(RegimeScoreStatus.Included, status(regimeScore = 40))
    }

    /**
     * A name that fits the regime neither well nor badly scores zero, and zero is a score. Treating
     * it as missing would cost the name its coverage bonus for the crime of being average.
     * `commands.rs:1978-2010` pins the same case on the Rust side.
     */
    @Test
    fun a_dimension_scoring_exactly_zero_is_included_not_missing() {
        assertEquals(RegimeScoreStatus.Included, status(regimeScore = 0))
    }

    @Test
    fun the_switch_being_off_is_reported_as_disabled() {
        assertEquals(RegimeScoreStatus.Disabled, status(toggleEnabled = false, regimeScore = 40))
    }

    @Test
    fun a_market_with_no_usable_policy_is_reported_as_unavailable() {
        assertEquals(RegimeScoreStatus.Unavailable, status(policyAvailable = false))
    }

    /** The market is readable but this asset is not measurable — still Unavailable, not Disabled. */
    @Test
    fun a_name_the_policy_cannot_score_is_reported_as_unavailable() {
        assertEquals(RegimeScoreStatus.Unavailable, status(regimeScore = null))
    }

    @Test
    fun a_model_or_asset_that_never_carries_the_dimension_is_not_applicable() {
        assertEquals(RegimeScoreStatus.NotApplicable, status(applicable = false, regimeScore = 40))
    }

    /**
     * Precedence, not just reachability: telling someone looking at V2 that they switched the
     * dimension off would be a lie about their own settings.
     */
    @Test
    fun not_applicable_outranks_disabled_when_both_would_be_true() {
        assertEquals(
            RegimeScoreStatus.NotApplicable,
            status(applicable = false, toggleEnabled = false, regimeScore = 40),
        )
    }

    // ── Which rows the dimension applies to ──────────────────────────────────

    @Test
    fun the_dimension_applies_to_a_stock_under_v3() {
        assertTrue(OpportunityEngine.regimeDimensionApplies(OpportunityScoringModel.AggressiveV3, "AAPL"))
    }

    @Test
    fun the_dimension_does_not_apply_under_v2() {
        assertTrue(!OpportunityEngine.regimeDimensionApplies(OpportunityScoringModel.AggressiveV2, "AAPL"))
    }

    /** The fit features are built for operating companies; an index fund has nothing to measure. */
    @Test
    fun the_dimension_does_not_apply_to_an_etf() {
        assertTrue(!OpportunityEngine.regimeDimensionApplies(OpportunityScoringModel.AggressiveV3, "SPY"))
    }

    @Test
    fun the_dimension_does_not_apply_to_a_coin() {
        assertTrue(!OpportunityEngine.regimeDimensionApplies(OpportunityScoringModel.AggressiveV3, "BTC-USD"))
    }

    // ── The wiring ───────────────────────────────────────────────────────────

    /** Every call site that predates the market dimension passes no regime, and must not move. */
    @Test
    fun a_row_with_no_market_reading_scores_exactly_its_base() {
        var scored = score()

        assertEquals(scored.compositeScoreBase, scored.compositeScore)
    }

    @Test
    fun a_readable_market_puts_a_fourth_bucket_on_the_row() {
        assertNotNull(score(regime = confidentRegime()).regimeScore)
    }

    @Test
    fun the_fourth_bucket_raises_the_coverage_count_to_four() {
        assertEquals(4, score(regime = confidentRegime()).coverageCount)
    }

    /** The base is kept beside the final so the dimension's impact is a subtraction, not a guess. */
    @Test
    fun the_base_composite_is_what_the_three_buckets_alone_produce() {
        assertEquals(score().compositeScore, score(regime = confidentRegime()).compositeScoreBase)
    }

    @Test
    fun a_readable_market_actually_moves_the_score() {
        var included = score(regime = confidentRegime())

        assertNotEquals(included.compositeScoreBase, included.compositeScore)
    }

    /** Off means off: not a fourth bucket quietly weighted to zero, which would still bonus. */
    @Test
    fun the_switch_being_off_scores_the_three_buckets_alone() {
        var off = score(regime = confidentRegime(), enabled = false)

        assertEquals(off.compositeScoreBase, off.compositeScore)
    }

    @Test
    fun a_disabled_dimension_carries_no_score() {
        assertNull(score(regime = confidentRegime(), enabled = false).regimeScore)
    }

    @Test
    fun an_included_dimension_explains_itself_with_ranked_causes() {
        assertTrue(score(regime = confidentRegime()).regimeCauses.isNotEmpty())
    }

    /** A reason belongs to Unavailable alone; the other three states say what they are by name. */
    @Test
    fun a_disabled_dimension_offers_no_unavailability_reason() {
        assertNull(score(regime = confidentRegime(), enabled = false).regimeUnavailableReason)
    }

    @Test
    fun an_unreadable_market_says_so_rather_than_going_silent() {
        assertNotNull(score().regimeUnavailableReason)
    }

    /**
     * The fit is fitted to *daily* bars, and these two tests are a pair: the first says the daily
     * summary reaches it, the second says the weekly one does not. Either alone would pass on a
     * wiring that reads both.
     *
     * It matters because the two summaries have the same type and the same field names while
     * measuring different spans — a %B over twenty weeks rather than twenty days, a position read
     * off fifty-two points rather than two hundred and fifty. Reading the wrong one would not throw
     * or return null; it would return a plausible number that disagrees with Windows.
     */
    @Test
    fun the_fit_moves_with_the_daily_summary() {
        assertNotEquals(
            score(regime = confidentRegime(), regimeSummary = summary(bullish = false)).regimeScore,
            score(regime = confidentRegime(), regimeSummary = summary(bullish = true)).regimeScore,
        )
    }

    @Test
    fun the_fit_does_not_move_with_the_weekly_summary() {
        var daily = summary(bullish = true)

        assertEquals(
            score(regime = confidentRegime(), summary = summary(bullish = false), regimeSummary = daily).regimeScore,
            score(regime = confidentRegime(), summary = summary(bullish = true), regimeSummary = daily).regimeScore,
        )
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private fun composite(
        model: OpportunityScoringModel = OpportunityScoringModel.AggressiveV3,
        regime: Int?,
        coverage: Int,
        mult: Double = 1.0,
        betaMillis: Int? = 1_200,
    ) = OpportunityEngine.compositeScoreFor(
        model = model,
        fundamentals = 40,
        technical = 20,
        forecast = 30,
        regime = regime,
        coverageCount = coverage,
        betaMillis = betaMillis,
        betaHaircutMult = mult,
    )

    private fun status(
        applicable: Boolean = true,
        toggleEnabled: Boolean = true,
        policyAvailable: Boolean = true,
        regimeScore: Int? = null,
    ) = OpportunityEngine.resolveRegimeScoreStatus(applicable, toggleEnabled, policyAvailable, regimeScore)

    private fun score(
        symbol: String = "TEST",
        model: OpportunityScoringModel = OpportunityScoringModel.AggressiveV3,
        regime: MarketRegime? = null,
        enabled: Boolean = true,
        fundamentals: FundamentalSnapshot? = fundamentals(),
        summary: ChartRangeSummary? = summary(bullish = true),
        regimeSummary: ChartRangeSummary? = summary,
    ) = OpportunityEngine.scoreWithModel(
        detail = detail(symbol, fundamentals),
        summary = summary,
        analysis = null,
        model = model,
        regimeSummary = regimeSummary,
        marketRegime = regime,
        regimeScoringEnabled = enabled,
    )

    private fun detail(symbol: String, fundamentals: FundamentalSnapshot?) = SymbolDetail(
        symbol = symbol,
        profitable = true,
        marketPriceCents = 10_000L,
        intrinsicValueCents = 14_000L,
        gapBps = 4_000,
        minimumGapBps = 1_500,
        qualification = QualificationStatus.Qualified,
        externalStatus = ExternalSignalStatus.Supportive,
        externalSignalFairValueCents = 13_000L,
        externalSignalMaxAgeSeconds = 86_400L,
        analystOpinionCount = 20,
        recommendationMeanHundredths = 180,
        fundamentals = fundamentals,
        confidence = ConfidenceBand.High,
        lastSequence = 1,
        updateCount = 1,
        isWatched = false,
    )

    private fun fundamentals() = FundamentalSnapshot(
        symbol = "TEST",
        marketCapDollars = 1_000_000_000L,
        freeCashFlowDollars = 60_000_000L,
        operatingCashFlowDollars = 80_000_000L,
        totalCashDollars = 200_000_000L,
        totalDebtDollars = 100_000_000L,
        debtToEquityHundredths = 40,
        returnOnEquityBps = 1_800,
        betaMillis = 900,
        forwardPeHundredths = 1_400,
        priceToBookHundredths = 250,
        sectorName = "Technology",
    )
}
