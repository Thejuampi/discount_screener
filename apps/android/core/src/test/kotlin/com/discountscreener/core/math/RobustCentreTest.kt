package com.discountscreener.core.math

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * What the centre of a sample is, and — more useful to a caller — when there is no such thing.
 *
 * The refusals are pinned as hard as the answers. A caller that meets a null for the first time in
 * production has been told nothing by this file; a caller that reads these tests knows before it
 * writes its own line whether the sample it holds can be asked the question at all.
 */
class RobustCentreTest {

    /**
     * The whole point of the exercise. One member of another population is in this sample, and the
     * plain mean of it is 100.4 — a level no member of the bulk is anywhere near. The trim is what
     * stops that number being reported as the population's level.
     */
    @Test
    fun one_contaminated_observation_does_not_become_the_level() {
        var sample = listOf(9.0, 9.0, 10.0, 10.0, 10.0, 11.0, 11.0, 12.0, 12.0, 910.0)

        assertEquals(10.444, robustCentre(sample) ?: 0.0, 0.001)
    }

    /**
     * Two observations carry a centre and a spread between them and cannot supply both. So no
     * outlier can be named here, and a function that trims must say it has nothing to say.
     *
     * Wave 2's composite reads two to four bucket scores. This is the case it walks into, and it
     * is pinned so that it is a decision rather than a surprise.
     */
    @Test
    fun two_observations_have_no_robust_centre() {
        assertNull(robustCentre(listOf(1.0, 2.0)))
    }

    /**
     * Three observations are enough to ask, and not enough to survive the answer: removing the one
     * that does not belong leaves two, and two is below the floor again. The untrimmed mean of 40.7
     * is available and is not returned, which is the property that matters.
     */
    @Test
    fun three_observations_that_lose_one_to_the_trim_have_no_robust_centre() {
        assertNull(robustCentre(listOf(10.0, 12.0, 100.0)))
    }

    /**
     * Most of this sample is one value, so the middle has no width. Every score is then zero or
     * infinite, and the honest report is that the sample has no scale — not that the fourth value
     * is an outlier because the other three happen to agree exactly.
     *
     * Four bucket scores with three of them equal is an ordinary row, not a contrived one.
     */
    @Test
    fun a_sample_with_no_width_through_its_middle_has_no_robust_centre() {
        assertNull(robustCentre(listOf(40.0, 40.0, 40.0, -20.0)))
    }

    /** One non-finite value would poison every score in the sample, so the sample is refused. */
    @Test
    fun a_non_finite_observation_refuses_the_whole_sample() {
        assertNull(robustCentre(listOf(10.0, 11.0, 12.0, Double.NaN)))
    }

    /**
     * `sorted[size / 2]` returns 3.0 here, which is the upper of the middle pair and not the
     * middle. That off-by-one is the defect this helper was written to retire from the tree.
     */
    @Test
    fun the_median_of_an_even_sample_is_the_middle_pair_and_not_one_side_of_it() {
        assertEquals(2.5, medianOf(listOf(4.0, 1.0, 3.0, 2.0)))
    }

    /** Nothing has no middle, and zero is a value a sample can really centre on. */
    @Test
    fun an_empty_sample_has_no_median() {
        assertNull(medianOf(emptyList()))
    }
}
