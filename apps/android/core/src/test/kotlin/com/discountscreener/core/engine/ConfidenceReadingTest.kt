package com.discountscreener.core.engine

import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.ExternalSignalStatus
import com.discountscreener.core.model.QualificationStatus
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Confidence is Qualification plus External. Each cause is asserted on its own so a deleted
 * branch cannot hide behind a green neighbour.
 */
class ConfidenceReadingTest {

    @Test
    fun unprofitable_is_low() {
        assertEquals(ConfidenceBand.Low, band(QualificationStatus.Unprofitable))
    }

    @Test
    fun a_gap_that_is_too_small_is_low() {
        assertEquals(ConfidenceBand.Low, band(QualificationStatus.GapTooSmall))
    }

    @Test
    fun qualified_with_no_analyst_signal_is_provisional() {
        assertEquals(ConfidenceBand.Provisional, band(external = ExternalSignalStatus.Missing))
    }

    @Test
    fun a_stale_analyst_signal_is_low() {
        assertEquals(ConfidenceBand.Low, band(external = ExternalSignalStatus.Stale))
    }

    @Test
    fun a_divergent_analyst_signal_is_low() {
        assertEquals(ConfidenceBand.Low, band(external = ExternalSignalStatus.Divergent))
    }

    @Test
    fun three_supportive_analysts_are_high() {
        assertEquals(
            ConfidenceBand.High,
            band(external = ExternalSignalStatus.Supportive, analystCount = HIGH_CONFIDENCE_ANALYST_COUNT),
        )
    }

    @Test
    fun two_supportive_analysts_stay_provisional() {
        assertEquals(
            ConfidenceBand.Provisional,
            band(external = ExternalSignalStatus.Supportive, analystCount = HIGH_CONFIDENCE_ANALYST_COUNT - 1),
        )
    }

    @Test
    fun unprofitable_names_only_qualification() {
        assertEquals(
            listOf(ConfidenceCause("Qualification", "Unprofitable")),
            explain(QualificationStatus.Unprofitable).causes,
        )
    }

    @Test
    fun a_small_gap_names_only_qualification() {
        assertEquals(
            listOf(ConfidenceCause("Qualification", "Gap too small")),
            explain(QualificationStatus.GapTooSmall).causes,
        )
    }

    @Test
    fun a_missing_signal_names_both_fields() {
        assertEquals(
            listOf(
                ConfidenceCause("Qualification", "Qualified"),
                ConfidenceCause("External", "Missing"),
            ),
            explain(external = ExternalSignalStatus.Missing).causes,
        )
    }

    @Test
    fun thin_coverage_states_the_cut() {
        assertEquals(
            "Supportive · 2 < 3",
            explain(external = ExternalSignalStatus.Supportive, analystCount = 2)
                .causes
                .single { it.name == "External" }
                .value,
        )
    }

    @Test
    fun the_external_help_names_the_street_median() {
        assertEquals(true, EXTERNAL_STATUS_HELP.startsWith("Street median target versus price."))
    }

    @Test
    fun high_coverage_states_the_cut() {
        assertEquals(
            "Supportive · 5 ≥ 3",
            explain(external = ExternalSignalStatus.Supportive, analystCount = 5)
                .causes
                .single { it.name == "External" }
                .value,
        )
    }

    private fun band(
        qualification: QualificationStatus = QualificationStatus.Qualified,
        external: ExternalSignalStatus = ExternalSignalStatus.Missing,
        analystCount: Int? = null,
    ) = confidenceFor(qualification, external, analystCount)

    private fun explain(
        qualification: QualificationStatus = QualificationStatus.Qualified,
        external: ExternalSignalStatus = ExternalSignalStatus.Missing,
        analystCount: Int? = null,
    ) = explainConfidence(qualification, external, analystCount)
}
