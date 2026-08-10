package com.discountscreener.core.regime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The composite's policy tables — the band ladder, the twenty-five-cell stance matrix, the risk
 * parameters each stance carries, and the cascade that names the regime.
 *
 * These are lookup tables joined to each other by bare string literals, and every one of them has a
 * fallthrough that returns something plausible rather than failing: an unlisted stance gets neutral
 * risk, an unlisted cell gets "Mixed". A typo would not throw, it would quietly neutralise a corner
 * of the policy. So the tests below are mostly about *totality* — that no real input reaches a
 * fallthrough — rather than about restating cell by cell what the tables already say.
 */
class RegimeCompositePolicyTest {

    // -- the band ladder ------------------------------------------------------------------------

    /**
     * Each run's *first* score, not just the run order: a boundary that slips by one point leaves the
     * ladder reading correctly in sequence while misclassifying the score sitting on the edge.
     */
    @Test
    fun the_bands_partition_the_whole_score_line_at_four_fixed_boundaries() {
        var walked = (-100..100).map { it to envBand(it) }
        assertEquals(
            listOf(-100 to "Crisis", -59 to "RiskOff", -19 to "Neutral", 20 to "RiskOn", 60 to "StrongRiskOn"),
            walked.filterIndexed { index, (_, band) -> index == 0 || walked[index - 1].second != band },
        )
    }

    @Test
    fun the_composed_environment_walks_the_same_bands_as_the_pillars_improve() {
        assertEquals(
            BANDS,
            listOf(-80, -40, 0, 40, 80).map { compose(compositeInput(it)).environmentBand },
        )
    }

    // -- the stance matrix ----------------------------------------------------------------------

    @Test
    fun every_cell_of_the_policy_matrix_is_populated() {
        assertEquals(
            emptyList(),
            BANDS.flatMap { band -> ZONES.map { zone -> band to zone } }
                .filter { (band, zone) -> stanceMatrix(band, zone) == "Mixed" },
        )
    }

    @Test
    fun an_unknown_environment_answers_the_same_whatever_the_sentiment() {
        assertEquals(listOf("Unknown"), ZONES.map { stanceMatrix("Unknown", it) }.distinct())
    }

    // -- the sentiment zone the stance is read against --------------------------------------------

    @Test
    fun the_contrarian_score_walks_the_zones_as_fear_gives_way_to_greed() {
        assertEquals(
            listOf("Accumulate", "SelectiveBuy", "Neutral", "HoldTrim", "Reduce"),
            listOf(60, 30, 0, -30, -60).map { compose(compositeInput(0, sentiment = it)).actionStance },
        )
    }

    /** The pillar score is the fallback, not the reading. A measured index must win over it. */
    @Test
    fun a_present_fear_and_greed_reading_overrides_the_pillar_score() {
        assertEquals("Reduce", compose(compositeInput(0, sentiment = 60, cnnFng = 85.0)).actionStance)
    }

    // -- the risk parameters each stance carries --------------------------------------------------

    @Test
    fun every_stance_the_matrix_emits_has_its_own_risk_parameters() {
        var fallthrough = stanceRiskParams("NoSuchStance", UNCLAMPED_CEILING)
        assertEquals(
            emptyList(),
            matrixStances().filter { stanceRiskParams(it, UNCLAMPED_CEILING) == fallthrough },
        )
    }

    /**
     * The multiplier is a fraction of the exposure ceiling, so halving the ceiling must move every
     * stance. A table that ignored its `ceiling` argument would still return sane-looking bps.
     */
    @Test
    fun the_risk_multiplier_scales_with_the_exposure_ceiling() {
        assertEquals(
            emptyList(),
            matrixStances().filter {
                stanceRiskParams(it, UNCLAMPED_CEILING).multiplierBps <= stanceRiskParams(it, 50).multiplierBps
            },
        )
    }

    // -- the crisis override ------------------------------------------------------------------------

    /**
     * The band the composite publishes is not always the band its score maps to: stress, a collapsed
     * breadth and an inverted term structure together declare a crisis out of a score that reads
     * merely risk-off.
     */
    @Test
    fun three_coinciding_stresses_declare_a_crisis_the_score_alone_would_not() {
        assertEquals("Crisis", compose(crisisInput()).environmentBand)
    }

    @Test
    fun the_crisis_override_needs_all_three_and_not_two() {
        assertEquals("RiskOff", compose(crisisInput().copy(vixTermRatio = 0.95)).environmentBand)
    }

    @Test
    fun a_declared_crisis_caps_the_exposure_it_suggests() {
        assertTrue(compose(crisisInput()).suggestedExposurePct <= 35, "crisis exposure is capped at 35%")
    }

    @Test
    fun a_declared_crisis_says_so_in_the_flag_it_publishes() {
        assertTrue(compose(crisisInput()).crisisCapApplied, "the cap is announced, not only applied")
    }

    // -- the quality haircut -------------------------------------------------------------------------

    /**
     * The flag is asserted rather than the exposure it produces, because the flag has exactly one
     * cause. Quality also carries weight in the environment score, so a lower exposure alone would
     * not say whether the haircut ran or the environment simply fell.
     */
    @Test
    fun a_fragile_quality_pillar_marks_the_exposure_down() {
        assertTrue(compose(compositeInput(0, quality = -40)).qualityHaircutApplied, "fragility is priced")
    }

    @Test
    fun the_same_fragility_read_without_confidence_does_not() {
        assertFalse(
            compose(compositeInput(0, quality = -40, qualityConfidenceBps = 2_000)).qualityHaircutApplied,
            "an unconfident pillar does not move exposure",
        )
    }

    // -- no data at all ----------------------------------------------------------------------------

    /**
     * Every pillar silent. The weights are a ratio against their own total, so this is the input that
     * divides by zero — it must publish zeros rather than a fabricated split or a NaN.
     */
    @Test
    fun pillars_with_no_confidence_publish_no_weights() {
        var composed = compose(compositeInput(0, confidenceBps = 0))
        assertEquals(
            listOf(0, 0, 0, 0, 0),
            listOf(
                composed.weightTrendBps,
                composed.weightBreadthBps,
                composed.weightVolBps,
                composed.weightCrossBps,
                composed.weightQualityBps,
            ),
        )
    }

    // -- the cascade that names the regime -----------------------------------------------------------

    /**
     * One row per precedence claim, not one row per regime: each pair is chosen so that an earlier
     * branch *would* also have matched, or so that a threshold sits one step away.
     */
    @Test
    fun the_regime_cascade_resolves_each_contested_case_to_the_earlier_branch() {
        assertEquals(
            listOf(
                "crisis in extreme fear=Capitulation",
                "crisis under stress alone=Capitulation",
                "crisis with neither=Bear",
                "a deep drawdown is bear, not a correction=Bear",
                "a shallower one is a correction=Correction",
                "recovering out of fear is a snapback=Snapback",
                "the same tape still stressed is not=Range",
                "extreme greed makes a strong bull late=LateBull",
                "so does narrow breadth=LateBull",
                "broad and unexcited stays strong=StrongBull",
                "risk-on on broad breadth is a plain bull=Bull",
                "a neutral environment is a range=Range",
                "an unknown environment stays unknown=Unknown",
            ),
            listOf(
                "crisis in extreme fear" to classify("Crisis", zone = "ExtremeFear", score = -70, stress = 40),
                "crisis under stress alone" to classify("Crisis", zone = "Greed", score = -70, stress = 75),
                "crisis with neither" to classify("Crisis", zone = "Greed", score = -70, stress = 40),
                "a deep drawdown is bear, not a correction" to
                    classify("RiskOff", score = -50, drawdown = 25.0),
                "a shallower one is a correction" to classify("RiskOff", score = -50, drawdown = 10.0),
                "recovering out of fear is a snapback" to
                    classify("Neutral", score = 10, drawdown = 15.0, stress = 50, sentiment = 40),
                "the same tape still stressed is not" to
                    classify("Neutral", score = 10, drawdown = 15.0, stress = 60, sentiment = 40),
                "extreme greed makes a strong bull late" to
                    classify("StrongRiskOn", zone = "ExtremeGreed", score = 70, breadth = 60.0),
                "so does narrow breadth" to classify("RiskOn", score = 45, breadth = 35.0),
                "broad and unexcited stays strong" to classify("StrongRiskOn", score = 70, breadth = 60.0),
                "risk-on on broad breadth is a plain bull" to classify("RiskOn", score = 45, breadth = 60.0),
                "a neutral environment is a range" to classify("Neutral", score = 5),
                "an unknown environment stays unknown" to classify("Unknown"),
            ).map { (claim, regime) -> "$claim=$regime" },
        )
    }
}

private val BANDS = listOf("Crisis", "RiskOff", "Neutral", "RiskOn", "StrongRiskOn")
private val ZONES = listOf("ExtremeFear", "Fear", "Neutral", "Greed", "ExtremeGreed")

/**
 * At a hundred-percent ceiling the risk multiplier's base is 1.0, which puts every stance's product
 * inside the 2500..12500 clamp. A lower ceiling would flatten the weakest stances onto the floor and
 * make them indistinguishable from each other — and from the fallthrough.
 */
private const val UNCLAMPED_CEILING = 100

private fun matrixStances(): List<String> =
    BANDS.flatMap { band -> ZONES.map { zone -> stanceMatrix(band, zone) } }.distinct()

private fun pillar(score: Int, confidenceBps: Int) =
    PillarResult(score = score, confidenceBps = confidenceBps)

/**
 * Pillars tuned so the composed environment score *is* [environment]: the volatility pillar reads
 * stress and is negated before weighting, so it takes the opposite sign, and the remaining four
 * agree. A fixture where the pillars disagreed would leave a failure unable to say whether the band
 * or the weighting was wrong.
 */
private fun compositeInput(
    environment: Int,
    stress: Int = -environment,
    sentiment: Int = 0,
    quality: Int = environment,
    confidenceBps: Int = 10_000,
    qualityConfidenceBps: Int = confidenceBps,
    breadthMa200Pct: Double? = null,
    vixTermRatio: Double? = null,
    cnnFng: Double? = null,
) = CompositeInput(
    trend = pillar(environment, confidenceBps),
    breadth = pillar(environment, confidenceBps),
    volatility = pillar(stress, confidenceBps),
    sentiment = pillar(sentiment, confidenceBps),
    crossAsset = pillar(environment, confidenceBps),
    quality = pillar(quality, qualityConfidenceBps),
    breadthMa200Pct = breadthMa200Pct,
    vixTermRatio = vixTermRatio,
    cnnFng = cnnFng,
)

/** A score that maps to RiskOff, carrying all three of the conditions that override it to Crisis. */
private fun crisisInput() =
    compositeInput(environment = -30, stress = 75, breadthMa200Pct = 25.0, vixTermRatio = 1.10)

private fun classify(
    env: String,
    zone: String = "Neutral",
    score: Int = 0,
    drawdown: Double? = null,
    breadth: Double? = null,
    stress: Int = 0,
    sentiment: Int = 0,
) = classifyPrimaryRegime(
    env = env,
    sentimentZone = zone,
    environmentScore = score,
    drawdown = drawdown,
    breadth200 = breadth,
    stress = stress,
    sentiment = sentiment,
)
