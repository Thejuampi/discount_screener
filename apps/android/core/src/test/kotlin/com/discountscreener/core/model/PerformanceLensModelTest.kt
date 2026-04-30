package com.discountscreener.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PerformanceLensModelTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun serializes_degraded_states_explicitly() {
        val lens = PerformanceLens(
            trajectory = TrendSignal.InsufficientData,
            confidence = ConfidenceLevel.Unavailable,
            provenance = Provenance(state = ProvenanceState.ProviderUncertain),
            sections = listOf(
                PerformanceScorecardSection(
                    kind = ScorecardSectionKind.Growth,
                    evidence = listOf(
                        PerformanceMetricEvidence(
                            id = "growth.revenue",
                            label = "Revenue growth",
                            status = EvidenceStatus.Unavailable,
                            direction = EvidenceDirection.Unavailable,
                            provenance = Provenance(state = ProvenanceState.Unavailable),
                        ),
                    ),
                ),
            ),
            riskFlags = listOf(
                RiskFlag(
                    id = "provider.parse",
                    severity = RiskSeverity.Warning,
                    title = "Provider uncertainty",
                    evidenceIds = listOf("growth.revenue"),
                ),
            ),
            decisionReadiness = DecisionReadiness.TooSparseToJudge,
        )

        val encoded = json.encodeToString(lens)

        assertContains(encoded, """"trajectory":"InsufficientData"""")
        assertContains(encoded, """"confidence":"Unavailable"""")
        assertContains(encoded, """"state":"ProviderUncertain"""")
        assertContains(encoded, """"status":"Unavailable"""")
        assertContains(encoded, """"decisionReadiness":"TooSparseToJudge"""")
    }

    @Test
    fun rejects_confident_trajectory_without_available_evidence() {
        val failure = assertFailsWith<IllegalArgumentException> {
            PerformanceLens(
                trajectory = TrendSignal.Improving,
                confidence = ConfidenceLevel.High,
                provenance = Provenance(source = "Yahoo", asOfEpochSeconds = 1_775_000_000, state = ProvenanceState.Live),
                sections = listOf(
                    PerformanceScorecardSection(
                        kind = ScorecardSectionKind.Profitability,
                        evidence = listOf(
                            PerformanceMetricEvidence(
                                id = "profitability.roe",
                                label = "Return on equity",
                                status = EvidenceStatus.Sparse,
                                direction = EvidenceDirection.Positive,
                                provenance = Provenance(state = ProvenanceState.Unavailable),
                            ),
                        ),
                    ),
                ),
                riskFlags = emptyList(),
                decisionReadiness = DecisionReadiness.ReadyForReview,
            )
        }

        assertEquals("High confidence requires at least one available evidence row.", failure.message)
    }

    @Test
    fun rejects_readiness_without_provenance() {
        val failure = assertFailsWith<IllegalArgumentException> {
            PerformanceLens(
                trajectory = TrendSignal.Mixed,
                confidence = ConfidenceLevel.Medium,
                provenance = Provenance(state = ProvenanceState.Unavailable),
                sections = listOf(availableSection()),
                riskFlags = emptyList(),
                decisionReadiness = DecisionReadiness.NeedsManualThesisCheck,
            )
        }

        assertEquals("Decision readiness requires available provenance.", failure.message)
    }

    @Test
    fun preserves_fixed_point_financial_fields() {
        val evidence = PerformanceMetricEvidence(
            id = "valuation.discount",
            label = "Discount to fair value",
            status = EvidenceStatus.Available,
            direction = EvidenceDirection.Positive,
            valueCents = 12_345,
            valueBps = 2_500,
            valueHundredths = 1_234,
            valueMillis = 987,
            provenance = Provenance(source = "Yahoo", asOfEpochSeconds = 1_775_000_000, state = ProvenanceState.Live),
        )

        assertEquals(12_345, evidence.valueCents)
        assertEquals(2_500, evidence.valueBps)
        assertEquals(1_234, evidence.valueHundredths)
        assertEquals(987, evidence.valueMillis)
    }

    private fun availableSection() = PerformanceScorecardSection(
        kind = ScorecardSectionKind.Profitability,
        evidence = listOf(
            PerformanceMetricEvidence(
                id = "profitability.roe",
                label = "Return on equity",
                status = EvidenceStatus.Available,
                direction = EvidenceDirection.Positive,
                valueBps = 1_500,
                provenance = Provenance(source = "Yahoo", asOfEpochSeconds = 1_775_000_000, state = ProvenanceState.Live),
            ),
        ),
    )
}
