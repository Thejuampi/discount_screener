package com.discountscreener.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Five bands on the −100…+100 bucket line. Same cuts as `score_band` in the regime interpreter.
 *
 * Each boundary is asserted from both sides. A single `>= 50` test stays green if the cut moves
 * up, and a single `< 50` test stays green if it moves down.
 */
class ScoreReadingTest {

    @Test
    fun fifty_is_the_first_strong_score() {
        assertEquals(ScoreReading.Strong, scoreReading(50))
    }

    @Test
    fun forty_nine_is_still_good() {
        assertEquals(ScoreReading.Good, scoreReading(49))
    }

    @Test
    fun fifteen_is_the_first_good_score() {
        assertEquals(ScoreReading.Good, scoreReading(15))
    }

    @Test
    fun fourteen_is_still_neutral() {
        assertEquals(ScoreReading.Neutral, scoreReading(14))
    }

    @Test
    fun minus_fourteen_is_still_neutral() {
        assertEquals(ScoreReading.Neutral, scoreReading(-14))
    }

    @Test
    fun minus_fifteen_is_the_first_weak_score() {
        assertEquals(ScoreReading.Weak, scoreReading(-15))
    }

    @Test
    fun minus_forty_nine_is_still_weak() {
        assertEquals(ScoreReading.Weak, scoreReading(-49))
    }

    @Test
    fun minus_fifty_is_the_first_poor_score() {
        assertEquals(ScoreReading.Poor, scoreReading(-50))
    }
}
