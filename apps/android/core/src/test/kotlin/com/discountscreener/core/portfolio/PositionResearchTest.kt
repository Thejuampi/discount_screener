package com.discountscreener.core.portfolio

import kotlin.test.Test
import kotlin.test.assertEquals

class PositionResearchTest {
    @Test
    fun only_current_usable_evidence_gets_opportunity_labels() {
        assertEquals(
            PositionOpportunity.ReviewOpportunity,
            positionResearch(
                PositionResearchInput(
                    decision = PositionDecision.Act,
                    evidence = PositionEvidence.Current,
                    trust = PositionTrust.Trusted,
                    primary = PositionPrimary.Model(12_000L),
                    marketPriceCents = 10_000L,
                ),
            ).opportunity,
        )
        assertEquals(
            PositionOpportunity.CheckData,
            positionResearch(
                PositionResearchInput(
                    decision = PositionDecision.Watch,
                    evidence = PositionEvidence.Stored,
                    trust = PositionTrust.Trusted,
                    primary = PositionPrimary.Model(12_000L),
                    marketPriceCents = 10_000L,
                ),
            ).opportunity,
        )
    }

    @Test
    fun missing_stance_does_not_infer_from_legacy_intrinsic() {
        val result = positionResearch(
            PositionResearchInput(
                decision = null,
                evidence = PositionEvidence.Current,
                trust = PositionTrust.Trusted,
                primary = PositionPrimary.Missing,
                marketPriceCents = 10_000L,
            ),
        )

        assertEquals(PositionOpportunity.CheckData, result.opportunity)
        assertEquals("Missing valuation", result.reason)
    }

    @Test
    fun watch_maps_to_monitor() {
        val base = PositionResearchInput(
            decision = PositionDecision.Watch,
            evidence = PositionEvidence.Current,
            trust = PositionTrust.Trusted,
            primary = PositionPrimary.Analyst(10_000L),
            marketPriceCents = 10_000L,
        )
        assertEquals(PositionOpportunity.Monitor, positionResearch(base).opportunity)
    }

    @Test
    fun avoid_maps_to_review_position() {
        val base = PositionResearchInput(
            decision = PositionDecision.Avoid,
            evidence = PositionEvidence.Current,
            trust = PositionTrust.Trusted,
            primary = PositionPrimary.Analyst(10_000L),
            marketPriceCents = 10_000L,
        )
        assertEquals(
            PositionOpportunity.ReviewPosition,
            positionResearch(base).opportunity,
        )
    }

    @Test
    fun disputed_evidence_requires_check_data() {
        val base = PositionResearchInput(
            decision = PositionDecision.Act,
            evidence = PositionEvidence.Current,
            trust = PositionTrust.Trusted,
            primary = PositionPrimary.Disputed,
            marketPriceCents = 10_000L,
        )
        assertEquals(PositionOpportunity.CheckData, positionResearch(base).opportunity)
    }

    @Test
    fun provisional_evidence_requires_check_data() {
        val base = PositionResearchInput(
            decision = PositionDecision.Act,
            evidence = PositionEvidence.Current,
            trust = PositionTrust.LowConfidence,
            primary = PositionPrimary.Model(12_000L),
            marketPriceCents = 10_000L,
        )
        assertEquals(
            PositionOpportunity.CheckData,
            positionResearch(base).opportunity,
        )
    }

    @Test
    fun blank_trust_note_is_absent() {
        val result = positionResearch(
            PositionResearchInput(
                decision = PositionDecision.Act,
                evidence = PositionEvidence.Current,
                trust = PositionTrust.Note(""),
                primary = PositionPrimary.Model(12_000L),
                marketPriceCents = 10_000L,
            ),
        )

        assertEquals(PositionOpportunity.ReviewOpportunity, result.opportunity)
    }

    @Test
    fun whitespace_trust_note_is_absent() {
        val result = positionResearch(
            PositionResearchInput(
                decision = PositionDecision.Act,
                evidence = PositionEvidence.Current,
                trust = PositionTrust.Note(" \t"),
                primary = PositionPrimary.Model(12_000L),
                marketPriceCents = 10_000L,
            ),
        )

        assertEquals(PositionOpportunity.ReviewOpportunity, result.opportunity)
    }

    @Test
    fun usable_quote_without_analysis_reports_missing_analysis() {
        val result = positionResearch(
            PositionResearchInput(
                decision = PositionDecision.Act,
                evidence = PositionEvidence.Missing,
                trust = PositionTrust.Missing,
                primary = PositionPrimary.Missing,
                marketPriceCents = 10_000L,
            ),
        )

        assertEquals(PositionOpportunity.CheckData, result.opportunity)
        assertEquals("Missing analysis", result.reason)
    }
}
