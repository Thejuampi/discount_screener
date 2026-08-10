package com.discountscreener.android.ui.dashboard

import androidx.compose.ui.graphics.Color
import com.discountscreener.android.presentation.dashboard.QuantLensChipUi
import com.discountscreener.android.presentation.dashboard.QuantLensQualifier
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two halves of how a signal reads: the glyph, and the colour behind it.
 *
 * Colour is asserted as relationships rather than as hex values. What a user can be failed by is two
 * directions sharing a colour, a palette that does not change with the theme, or an unread lens
 * borrowing a colour that means something — not the particular green.
 */
class SignalChipTest {
    /**
     * Every qualifier and its marker in one map, rather than a test per case. The failure this
     * guards is not a wrong glyph on a case someone thought about; it is a new qualifier added
     * later that quietly falls through to the same marker as an existing one.
     */
    @Test
    fun the_markers_escalate_in_both_directions_and_the_middle_is_bare() {
        assertEquals(
            mapOf(
                QuantLensQualifier.StrongPositive to "++",
                QuantLensQualifier.Positive to "+",
                QuantLensQualifier.Neutral to "",
                QuantLensQualifier.Negative to "−",
                QuantLensQualifier.StrongNegative to "−−",
                QuantLensQualifier.Unknown to "",
            ),
            QuantLensQualifier.values().associateWith(::signalQualifierMark),
        )
    }

    @Test
    fun the_marker_leads_the_label() {
        assertEquals("++ Strong signals", signalChipLabel(chip(QuantLensQualifier.StrongPositive)))
    }

    /** No `·` placeholder: a lens that found nothing decisive must not look like a reading. */
    @Test
    fun a_signal_with_no_direction_shows_the_label_alone() {
        assertEquals(
            listOf("Strong signals", "Strong signals"),
            listOf(QuantLensQualifier.Neutral, QuantLensQualifier.Unknown).map { signalChipLabel(chip(it)) },
        )
    }

    /**
     * Totality over every pair, not a hand-picked collision.
     *
     * The two positives share green on purpose — the second plus is what separates them. Any other
     * pair sharing a colour means two directions are one to whoever reads by colour first, which is
     * most people most of the time.
     */
    @Test
    fun the_two_positives_are_the_only_directions_that_share_a_colour() {
        assertEquals(
            listOf(QuantLensQualifier.StrongPositive to QuantLensQualifier.Positive),
            DIRECTIONS.flatMap { a -> DIRECTIONS.map { b -> a to b } }
                .filter { (a, b) -> a.ordinal < b.ordinal && signalChipContentColor(a, dark = false) == signalChipContentColor(b, dark = false) },
        )
    }

    /**
     * Small coloured text on a dark ground needs a different colour from the same text on a light
     * one. A palette that forgot a theme would leave one direction unreadable rather than wrong,
     * which is why this asserts every direction changed, not that some did.
     */
    @Test
    fun every_direction_is_repainted_for_the_dark_theme() {
        assertEquals(
            emptyList<QuantLensQualifier>(),
            DIRECTIONS.filter { signalChipContentColor(it, dark = false) == signalChipContentColor(it, dark = true) },
        )
    }

    /**
     * An unread lens has no colour of its own in either theme — it defers to the theme's outline,
     * so it can never be confused with a direction.
     */
    @Test
    fun a_lens_that_could_not_read_has_no_colour_of_its_own() {
        assertEquals(
            listOf<Color?>(null, null),
            listOf(false, true).map { signalChipContentColor(QuantLensQualifier.Unknown, it) },
        )
    }

    private fun chip(qualifier: QuantLensQualifier) =
        QuantLensChipUi(lensId = null, label = "Strong signals", qualifier = qualifier)

    private companion object {
        /** Everything except [QuantLensQualifier.Unknown], which is an absence rather than a reading. */
        val DIRECTIONS = QuantLensQualifier.values().filter { it != QuantLensQualifier.Unknown }
    }
}
