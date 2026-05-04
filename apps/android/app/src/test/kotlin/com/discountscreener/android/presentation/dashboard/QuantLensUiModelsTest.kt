package com.discountscreener.android.presentation.dashboard

import com.discountscreener.core.model.QuantLensLensId
import com.discountscreener.core.model.QuantLensLensRowState
import com.discountscreener.core.model.QuantLensPrimaryStatus
import com.discountscreener.core.model.QuantLensReasonCode
import com.discountscreener.core.model.QuantLensRowLabel
import com.discountscreener.core.model.QuantLensRowSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class QuantLensUiModelsTest {
    @Test
    fun missing_row_summary_maps_to_loading_chip() {
        assertEquals(listOf("Lens loading"), mapRowQuantLensSummary(null).map { it.label })
    }

    @Test
    fun row_summary_chips_follow_priority_order_and_cap() {
        val summary = summary(
            state(QuantLensLensId.SimilarSetups, QuantLensRowLabel.SimilarAvailable),
            state(QuantLensLensId.TrendReliability, QuantLensRowLabel.TrendModerate),
            state(QuantLensLensId.CorrelationRisk, QuantLensRowLabel.CorrHigh),
            state(QuantLensLensId.ExpectedValueRange, QuantLensRowLabel.EvRange, low = 1_000, high = 2_000),
            state(QuantLensLensId.EvidenceStrength, QuantLensRowLabel.EvidenceStrong),
        )

        assertEquals(listOf("Evidence strong", "EV +10%..+20%", "Corr high"), mapRowQuantLensSummary(summary).map { it.label })
    }

    @Test
    fun row_summary_hides_sparse_correlation_even_with_high_label() {
        val summary = summary(
            state(QuantLensLensId.EvidenceStrength, QuantLensRowLabel.EvidenceStrong),
            state(QuantLensLensId.CorrelationRisk, QuantLensRowLabel.CorrHigh, status = QuantLensPrimaryStatus.Sparse),
        )

        assertEquals(listOf("Evidence strong"), mapRowQuantLensSummary(summary).map { it.label })
    }

    @Test
    fun row_summary_uses_structured_label_over_reason_codes() {
        val summary = summary(
            state(
                QuantLensLensId.EvidenceStrength,
                QuantLensRowLabel.EvidenceUnavailable,
                status = QuantLensPrimaryStatus.Unavailable,
                reason = QuantLensReasonCode.CompleteScenarioAnchors,
            ),
        )

        assertEquals("Evidence unavailable", mapRowQuantLensSummary(summary).single().label)
    }

    private fun summary(vararg states: QuantLensLensRowState) = QuantLensRowSummary(
        symbol = "ACME",
        fingerprint = "fingerprint",
        lensStates = states.toList(),
    )

    private fun state(
        lensId: QuantLensLensId,
        label: QuantLensRowLabel,
        status: QuantLensPrimaryStatus = QuantLensPrimaryStatus.Available,
        reason: QuantLensReasonCode = QuantLensReasonCode.ScaffoldPending,
        low: Int? = null,
        high: Int? = null,
    ) = QuantLensLensRowState(
        lensId = lensId,
        primaryStatus = status,
        band = label.name,
        label = label,
        reasonCodes = listOf(reason),
        evLowUpsideBps = low,
        evHighUpsideBps = high,
    )
}