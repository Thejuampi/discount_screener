package com.discountscreener.android.ui.dashboard

import com.discountscreener.core.model.OutcomeConfidence
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The caveat is printed under the two readings that can be mistaken for good news, and under
 * neither of the other two. Each of the four is asserted on its own, so dropping one state cannot
 * hide behind another.
 */
class OutcomeConfidenceUiTest {

    @Test
    fun a_wide_reading_says_how_far_apart_the_sources_are() {
        assertEquals(
            "Outcome range · Wide · sources span 90% of the centre",
            outcomeConfidenceUi(OutcomeConfidence.Wide, widthBps = 9_000).label,
        )
    }

    @Test
    fun a_narrow_reading_says_how_close_the_sources_are() {
        assertEquals(
            "Outcome range · Narrow · sources span 18% of the centre",
            outcomeConfidenceUi(OutcomeConfidence.Narrow, widthBps = 1_800).label,
        )
    }

    @Test
    fun an_unmeasured_reading_says_so_instead_of_printing_a_number() {
        assertEquals(
            "Outcome range · not measured",
            outcomeConfidenceUi(OutcomeConfidence.Unmeasured, widthBps = null).label,
        )
    }

    @Test
    fun a_narrow_reading_carries_the_caveat() {
        assertEquals(true, outcomeConfidenceUi(OutcomeConfidence.Narrow, widthBps = 1_800).showCaveat)
    }

    @Test
    fun an_unmeasured_reading_carries_the_caveat() {
        assertEquals(true, outcomeConfidenceUi(OutcomeConfidence.Unmeasured, widthBps = null).showCaveat)
    }

    /** Wide already warns for itself. Repeating the caveat there costs a line and adds nothing. */
    @Test
    fun a_wide_reading_carries_no_caveat() {
        assertEquals(false, outcomeConfidenceUi(OutcomeConfidence.Wide, widthBps = 9_000).showCaveat)
    }

    @Test
    fun a_moderate_reading_carries_no_caveat() {
        assertEquals(false, outcomeConfidenceUi(OutcomeConfidence.Moderate, widthBps = 4_000).showCaveat)
    }
}
