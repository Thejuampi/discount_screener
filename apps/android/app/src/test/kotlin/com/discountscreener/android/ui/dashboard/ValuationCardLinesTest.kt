package com.discountscreener.android.ui.dashboard

import com.discountscreener.android.presentation.dashboard.presentValuationJudgment
import com.discountscreener.core.engine.ValuationJudgmentPolicy
import com.discountscreener.core.engine.ValuationJudgmentReason
import com.discountscreener.core.engine.ValuationJudgmentStatus
import com.discountscreener.core.model.AnchorRelation
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.ExternalSignalStatus
import com.discountscreener.core.model.HonestyKnob
import com.discountscreener.core.model.HonestyTaggedKnob
import com.discountscreener.core.model.ImpliedStretch
import com.discountscreener.core.model.ProjectedValuationJudgment
import com.discountscreener.core.model.QualificationStatus
import com.discountscreener.core.model.StreetImpliedView
import com.discountscreener.core.model.SymbolDetail
import com.discountscreener.core.model.ValuationHonesty
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The valuation card once printed the price and both series once per bent input, because the
 * blocks sat inside the knob loop and no test can read a composable. The card content is a list
 * now, so these tests hold the shape the reader sees.
 *
 * The knob count is the axis that hid the bug: with one bent input the repeat is invisible. Every
 * case here runs over the whole matrix.
 */
class ValuationCardLinesTest {

    @Test
    fun no_line_repeats_on_any_card() {
        var repeats = cards().mapValues { (_, lines) ->
            lines.map { it.text }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.sorted()
        }.filterValues { it.isNotEmpty() }

        assertEquals(emptyMap<String, List<String>>(), repeats)
    }

    @Test
    fun the_price_prints_once_on_any_card() {
        var prices = cards().mapValues { (_, lines) ->
            lines.count { it.role == ValuationLineRole.Price }
        }

        assertEquals(cards().keys.associateWith { 1 }, prices)
    }

    @Test
    fun no_series_prints_twice_on_any_card() {
        var extras = cards().mapValues { (_, lines) ->
            var texts = lines.filter { it.role == ValuationLineRole.Series }.map { it.text }
            texts.size - texts.distinct().size
        }

        assertEquals(cards().keys.associateWith { 0 }, extras)
    }

    @Test
    fun one_knob_note_prints_per_bent_input() {
        var notes = cards().mapValues { (_, lines) ->
            lines.count { it.role == ValuationLineRole.KnobNote }
        }

        assertEquals(
            mapOf("no knob" to 0, "one knob" to 1, "three knobs" to 3, "no street" to 0),
            notes,
        )
    }

    @Test
    fun three_bent_inputs_read_as_three_notes_over_one_series_block() {
        var lines = valuationCardLines(detail, presentValuationJudgment(judgment(KNOBS)))

        assertEquals(
            listOf(
                STABLE_MARGIN_NOTE,
                NEAR_TERM_GROWTH_NOTE,
                DISCOUNT_RATE_NOTE,
                "FCFF DCF $193 / $426 / $955",
                "Analyst range $47.0 / $60.0 / $71.5",
            ),
            lines
                .filter {
                    it.role == ValuationLineRole.KnobNote || it.role == ValuationLineRole.Series
                }
                .map { it.text },
        )
    }

    private fun cards(): Map<String, List<ValuationCardLine>> = mapOf(
        "no knob" to card(judgment(emptyList())),
        "one knob" to card(judgment(KNOBS.take(1))),
        "three knobs" to card(judgment(KNOBS)),
        "no street" to card(withoutStreet()),
    )

    private fun card(judgment: ProjectedValuationJudgment): List<ValuationCardLine> =
        valuationCardLines(detail, presentValuationJudgment(judgment))

    private fun withoutStreet() = judgment(emptyList()).copy(
        status = ValuationJudgmentStatus.Identity,
        relation = AnchorRelation.SingleSource,
        reasonCodes = listOf(ValuationJudgmentReason.IncompleteStreet),
        streetLowCents = null,
        streetBaseCents = null,
        streetHighCents = null,
        streetImplied = null,
    )

    private fun judgment(knobs: List<HonestyTaggedKnob>) = ProjectedValuationJudgment(
        status = ValuationJudgmentStatus.Disputed,
        relation = AnchorRelation.Disputed,
        primaryCents = 6_000L,
        reasonCodes = listOf(ValuationJudgmentReason.DisputedGap),
        policyVersion = ValuationJudgmentPolicy.POLICY_VERSION,
        identityModelLabel = "FCFF DCF",
        identityBearCents = 19_300L,
        identityBaseCents = 42_633L,
        identityBullCents = 95_500L,
        streetLowCents = 4_700L,
        streetBaseCents = 6_000L,
        streetHighCents = 7_150L,
        lastPriceCents = 4_424L,
        honestyMode = ValuationHonesty.NonHonest,
        streetImplied = StreetImpliedView(
            streetBaseCents = 6_000L,
            honestBaseCents = 42_633L,
            impliedBaseCents = 6_000L,
            winningKnob = HonestyKnob.DiscountRate,
            winningHonestBps = 675,
            winningImpliedBps = 1_665,
            winningDeltaBps = 990,
            winningStretch = ImpliedStretch.Absurd,
            aligned = false,
            knobs = knobs,
            policyVersion = "street-implied-honesty/3",
        ),
    )

    private val detail = SymbolDetail(
        symbol = "LVS",
        profitable = true,
        marketPriceCents = 4_424L,
        intrinsicValueCents = 6_000L,
        gapBps = 3_562,
        minimumGapBps = 1_000,
        qualification = QualificationStatus.Qualified,
        externalStatus = ExternalSignalStatus.Supportive,
        externalSignalMaxAgeSeconds = 86_400,
        confidence = ConfidenceBand.High,
        lastSequence = 1,
        updateCount = 1,
        isWatched = false,
    )

    private companion object {
        const val STABLE_MARGIN_NOTE =
            "stable FCFF margin 2648 bps honest. Street needs 466 bps (delta -2182, Absurd). " +
                "This input is not honest."
        const val NEAR_TERM_GROWTH_NOTE =
            "near-term growth 1207 bps honest. Street needs -409 bps (delta -1616, Absurd). " +
                "This input is not honest."
        const val DISCOUNT_RATE_NOTE =
            "discount rate 675 bps honest. Street needs 1665 bps (delta 990, Absurd). " +
                "This input is not honest."

        val KNOBS = listOf(
            HonestyTaggedKnob(
                knob = HonestyKnob.StableMargin,
                honesty = ValuationHonesty.NonHonest,
                honestBps = 2_648,
                impliedBps = 466,
                impliedCents = 6_000L,
                reachable = true,
                note = STABLE_MARGIN_NOTE,
            ),
            HonestyTaggedKnob(
                knob = HonestyKnob.NearTermGrowth,
                honesty = ValuationHonesty.NonHonest,
                honestBps = 1_207,
                impliedBps = -409,
                impliedCents = 6_000L,
                reachable = true,
                note = NEAR_TERM_GROWTH_NOTE,
            ),
            HonestyTaggedKnob(
                knob = HonestyKnob.DiscountRate,
                honesty = ValuationHonesty.NonHonest,
                honestBps = 675,
                impliedBps = 1_665,
                impliedCents = 6_000L,
                reachable = true,
                note = DISCOUNT_RATE_NOTE,
            ),
        )
    }
}
