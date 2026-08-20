package com.discountscreener.android.ui.dashboard

import com.discountscreener.android.presentation.dashboard.EvRangeRailModel
import com.discountscreener.android.presentation.dashboard.QuantLensChipUi
import com.discountscreener.android.presentation.dashboard.QuantLensQualifier
import com.discountscreener.android.presentation.dashboard.QuantLensSectionUi
import com.discountscreener.android.presentation.dashboard.QuantLensUiState
import com.discountscreener.core.model.QuantLensLensId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The header shows the Lens tab's own rail and builds nothing of its own. What can go wrong is the
 * lookup: picking the wrong lens, or inventing a rail for a section that refused to draw one.
 */
class HeadlineEvRailTest {

    @Test
    fun the_header_reads_the_expected_value_lens() {
        assertEquals(RAIL, headlineEvRail(stateOf(section(QuantLensLensId.ExpectedValueRange, RAIL))))
    }

    /** Another lens carrying a rail must not be mistaken for the valuation one. */
    @Test
    fun a_rail_on_another_lens_is_not_the_headline_rail() {
        assertNull(headlineEvRail(stateOf(section(QuantLensLensId.SimilarSetups, RAIL))))
    }

    /**
     * The section leaves this null unless all three points exist and the range is scenario
     * weighted. The header has to keep that refusal instead of drawing a partial bar.
     */
    @Test
    fun a_valuation_lens_that_drew_no_rail_gives_the_header_none() {
        assertNull(headlineEvRail(stateOf(section(QuantLensLensId.ExpectedValueRange, rail = null))))
    }

    @Test
    fun no_lens_report_at_all_gives_the_header_no_rail() {
        assertNull(headlineEvRail(quantLens = null))
    }

    private fun stateOf(vararg sections: QuantLensSectionUi) =
        QuantLensUiState(headerChips = emptyList(), sections = sections.toList())

    private fun section(lensId: QuantLensLensId, rail: EvRangeRailModel?) = QuantLensSectionUi(
        lensId = lensId,
        title = "Test",
        chip = QuantLensChipUi(lensId, "Test", QuantLensQualifier.Neutral),
        primaryLine = "Test",
        evRailModel = rail,
    )

    private companion object {
        val RAIL = EvRangeRailModel(
            lowUpsideBps = -2_000,
            weightedUpsideBps = 1_500,
            highUpsideBps = 6_000,
            crossesZero = true,
            isStale = false,
        )
    }
}
