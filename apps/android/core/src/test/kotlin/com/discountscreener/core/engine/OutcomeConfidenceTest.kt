package com.discountscreener.core.engine

import com.discountscreener.core.model.ExternalValuationSignal
import com.discountscreener.core.model.MarketSnapshot
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.OutcomeConfidence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Both knees are probed on their two sides, because a band that only ever reports one value would
 * pass every positive case on its own.
 */
class OutcomeConfidenceTest {

    @Test
    fun a_span_is_measured_against_its_own_centre() {
        assertEquals(4_000, spanWidthBps(lowCents = 8_000, highCents = 12_000, centreCents = 10_000))
    }

    /** A centre of zero has no scale to measure a span against. */
    @Test
    fun a_centre_of_zero_measures_no_span() {
        assertNull(spanWidthBps(lowCents = 8_000, highCents = 12_000, centreCents = 0))
    }

    /** Bounds out of order are a parse failure. Reporting them as a narrow range would hide it. */
    @Test
    fun bounds_that_do_not_straddle_the_centre_in_order_measure_no_span() {
        assertNull(spanWidthBps(lowCents = 12_000, highCents = 8_000, centreCents = 10_000))
    }

    @Test
    fun a_missing_bound_measures_no_span() {
        assertNull(spanWidthBps(lowCents = null, highCents = 12_000, centreCents = 10_000))
    }

    @Test
    fun no_source_at_all_reads_as_unmeasured_and_not_as_narrow() {
        assertEquals(
            OutcomeConfidence.Unmeasured,
            outcomeConfidenceFor(streetWidthBps = null, modelWidthBps = null).band,
        )
    }

    @Test
    fun an_unmeasured_reading_carries_no_width() {
        assertNull(outcomeConfidenceFor(streetWidthBps = null, modelWidthBps = null).widthBps)
    }

    @Test
    fun just_under_the_narrow_knee_reads_narrow() {
        assertEquals(
            OutcomeConfidence.Narrow,
            outcomeConfidenceFor(OUTCOME_NARROW_MAX_BPS - 1, modelWidthBps = null).band,
        )
    }

    @Test
    fun the_narrow_knee_itself_is_already_moderate() {
        assertEquals(
            OutcomeConfidence.Moderate,
            outcomeConfidenceFor(OUTCOME_NARROW_MAX_BPS, modelWidthBps = null).band,
        )
    }

    @Test
    fun just_under_the_wide_knee_is_still_moderate() {
        assertEquals(
            OutcomeConfidence.Moderate,
            outcomeConfidenceFor(OUTCOME_WIDE_MIN_BPS - 1, modelWidthBps = null).band,
        )
    }

    @Test
    fun the_wide_knee_itself_is_already_wide() {
        assertEquals(
            OutcomeConfidence.Wide,
            outcomeConfidenceFor(OUTCOME_WIDE_MIN_BPS, modelWidthBps = null).band,
        )
    }

    /**
     * The whole point of reading two sources. Averaging 10% and 90% would print Moderate and tell
     * the reader the opposite of what one of the two models is saying.
     */
    @Test
    fun a_calm_street_does_not_cancel_a_wide_model() {
        assertEquals(
            OutcomeConfidence.Wide,
            outcomeConfidenceFor(streetWidthBps = 1_000, modelWidthBps = 9_000).band,
        )
    }

    @Test
    fun the_reported_width_is_the_widest_of_the_two() {
        assertEquals(9_000, outcomeConfidenceFor(streetWidthBps = 1_000, modelWidthBps = 9_000).widthBps)
    }

    /** One source missing must not read as agreement. The other one still decides alone. */
    @Test
    fun a_missing_model_leaves_the_street_reading_intact() {
        assertEquals(
            OutcomeConfidence.Wide,
            outcomeConfidenceFor(streetWidthBps = 9_000, modelWidthBps = null).band,
        )
    }

    @Test
    fun a_source_that_gave_no_span_says_so_in_its_cause() {
        assertEquals(
            "not measured",
            outcomeConfidenceFor(streetWidthBps = 1_000, modelWidthBps = null)
                .causes.single { it.name == "Model scenarios" }.value,
        )
    }

    @Test
    fun a_source_that_gave_a_span_reports_it_as_a_percentage_of_centre() {
        assertEquals(
            "40% of centre",
            outcomeConfidenceFor(streetWidthBps = 4_000, modelWidthBps = null)
                .causes.single { it.name == "Street targets" }.value,
        )
    }

    /**
     * The knees are derived from the scoring ramps, so restating that derivation here would be a
     * test that cannot fail. These pin the two numbers instead: retuning a ramp turns them red and
     * makes the reading's move a decision rather than a side effect.
     */
    @Test
    fun the_narrow_knee_stands_at_twenty_percent_of_centre() {
        assertEquals(2_000, OUTCOME_NARROW_MAX_BPS)
    }

    @Test
    fun the_wide_knee_stands_at_sixty_percent_of_centre() {
        assertEquals(6_000, OUTCOME_WIDE_MIN_BPS)
    }

    /**
     * The reading has to reach the row. A pure function nothing calls would keep every test above
     * green while the screen showed Unmeasured for every name.
     */
    @Test
    fun a_row_carries_the_band_read_from_its_own_target_range() {
        assertEquals(OutcomeConfidence.Wide, rowFor(lowCents = 5_000, highCents = 14_000).outcomeConfidence)
    }

    @Test
    fun a_row_carries_the_width_behind_its_band() {
        assertEquals(9_000, rowFor(lowCents = 5_000, highCents = 14_000).outcomeWidthBps)
    }

    /** Its companion: a narrow book must be able to come out narrow, or the stamp says nothing. */
    @Test
    fun a_row_with_a_tight_target_range_carries_the_narrow_band() {
        assertEquals(OutcomeConfidence.Narrow, rowFor(lowCents = 9_500, highCents = 10_500).outcomeConfidence)
    }

    @Test
    fun a_row_with_no_target_range_carries_no_band() {
        assertEquals(OutcomeConfidence.Unmeasured, rowFor(lowCents = null, highCents = null).outcomeConfidence)
    }

    /** Street centre 10 000 cents, so a span reads directly as a percentage of it. */
    private fun rowFor(lowCents: Long?, highCents: Long?) = OpportunityEngine.buildRows(
        ReportingEngine().apply {
            ingestSnapshot(
                MarketSnapshot(symbol = "SUT", profitable = true, marketPriceCents = 8_000, intrinsicValueCents = 10_000),
            )
            ingestExternal(
                ExternalValuationSignal(
                    symbol = "SUT",
                    fairValueCents = 10_000,
                    ageSeconds = 0,
                    lowFairValueCents = lowCents,
                    highFairValueCents = highCents,
                    analystOpinionCount = 10,
                ),
            )
        },
        OpportunityContext(scoringModel = OpportunityScoringModel.AggressiveV4),
    ).single()
}
