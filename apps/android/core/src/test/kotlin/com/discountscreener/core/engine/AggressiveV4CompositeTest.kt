package com.discountscreener.core.engine

import com.discountscreener.core.model.OpportunityScoringModel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What V4's composite pays for, tested at the arithmetic seam where the inputs are numbers rather
 * than a plausible company.
 *
 * V3 pays a bonus for the number of buckets that reported, whatever they said. V4 pays for how
 * close they are to each other. The two claims are asserted against the two models separately —
 * never in one test — because a mutant that broke both would otherwise look like one failure and
 * prove nothing about either.
 */
class AggressiveV4CompositeTest {

    /**
     * The defect, expressed as arithmetic. Both rows centre on 50 and both have four buckets, so
     * under V3 they would score identically: the coverage bonus cannot tell them apart.
     *
     *  agree     [50, 50, 50, 50]  centre 50  spread  0.0  bonus 5 × 3 × 1.000 = 15.00  → 65
     *  disagree  [20, 50, 50, 80]  centre 50  spread 15.0  bonus 5 × 3 × 0.610 =  9.16  → 59
     */
    @Test
    fun four_buckets_that_agree_score_above_four_that_disagree_around_the_same_centre() {
        var agree = v4(fundamentals = 50, technical = 50, forecast = 50, regime = 50)
        var disagree = v4(fundamentals = 20, technical = 50, forecast = 50, regime = 80)

        assertEquals(listOf(65, 59), listOf(agree, disagree))
    }

    /**
     * A fourth bucket that dissents costs the row, which is the behaviour V3 has backwards.
     *
     *  three  [40, 20, 30]      centre 30.0  spread 6.67  bonus  8.27  → 38
     *  four   [40, 20, 30, 15]  centre 25.0  spread 8.75  bonus 11.59  → 37
     *
     * The dissent pulls the centre down *and* widens the spread, and both work in the same
     * direction. That is the intent: a divided model should not read as a confident one.
     */
    @Test
    fun a_fourth_bucket_below_the_others_lowers_the_v4_score() {
        var three = v4(fundamentals = 40, technical = 20, forecast = 30, regime = null)
        var four = v4(fundamentals = 40, technical = 20, forecast = 30, regime = 15)

        assertEquals(listOf(38, 37), listOf(three, four))
    }

    /**
     * The same four numbers under V3, which **raises** the score for the same dissent. The mean
     * falls by 3.75 and the coverage bonus rises by 5, so turning up is worth more than agreeing.
     *
     *  three  mean 30.00  bonus 10  → 40
     *  four   mean 26.25  bonus 15  → 41
     *
     * Its own test on its own model. V3 is the control for Wave 4a and nothing here edits it.
     */
    @Test
    fun the_same_fourth_bucket_raises_the_v3_score() {
        var three = v3(fundamentals = 40, technical = 20, forecast = 30, regime = null)
        var four = v3(fundamentals = 40, technical = 20, forecast = 30, regime = 15)

        assertEquals(listOf(40, 41), listOf(three, four))
    }

    /**
     * The beta haircut is untouched by any of this: at a multiplier of one and beta at the midpoint
     * of the ramp it is 5.0 points, exactly as in V3. Beta appears in one place in V4 and this is
     * it — the market bucket no longer scores it a second time.
     */
    @Test
    fun the_beta_haircut_is_the_v3_arithmetic_unchanged() {
        var withBeta = v4(
            fundamentals = 50,
            technical = 50,
            forecast = 50,
            regime = 50,
            betaMillis = 1_200,
        )

        assertEquals(60, withBeta)
    }

    /** No bucket reported anything, so there is no centre to pay a bonus around. */
    @Test
    fun a_row_with_no_bucket_at_all_scores_zero() {
        assertEquals(0, v4(fundamentals = null, technical = null, forecast = null, regime = null))
    }

    private fun v4(
        fundamentals: Int?,
        technical: Int?,
        forecast: Int?,
        regime: Int?,
        betaMillis: Int? = null,
    ) = score(OpportunityScoringModel.AggressiveV4, fundamentals, technical, forecast, regime, betaMillis)

    private fun v3(
        fundamentals: Int?,
        technical: Int?,
        forecast: Int?,
        regime: Int?,
        betaMillis: Int? = null,
    ) = score(OpportunityScoringModel.AggressiveV3, fundamentals, technical, forecast, regime, betaMillis)

    private fun score(
        model: OpportunityScoringModel,
        fundamentals: Int?,
        technical: Int?,
        forecast: Int?,
        regime: Int?,
        betaMillis: Int?,
    ) = OpportunityEngine.compositeScoreFor(
        model = model,
        fundamentals = fundamentals,
        technical = technical,
        forecast = forecast,
        regime = regime,
        coverageCount = listOfNotNull(fundamentals, technical, forecast, regime).size,
        betaMillis = betaMillis,
        betaHaircutMult = 1.0,
    )
}
