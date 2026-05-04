package com.discountscreener.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class QuantLensModelTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun serializes_scaffolded_degraded_states_explicitly() {
        val report = scaffoldReport()

        val encoded = json.encodeToString(report)

        assertContains(encoded, """"primaryStatus":"Sparse"""")
        assertContains(encoded, """"evidenceStrength"""")
        assertContains(encoded, """"expectedValueRange"""")
        assertContains(encoded, """"correlationRisk"""")
        assertContains(encoded, """"trendReliability"""")
        assertContains(encoded, """"horizonContext"""")
        assertContains(encoded, """"similarSetups"""")
        assertContains(encoded, """"reasonCodes":["ScaffoldPending"]""")
    }

    @Test
    fun rejects_blank_report_symbol() {
        val failure = assertFailsWith<IllegalArgumentException> {
            scaffoldReport(symbol = " ")
        }

        assertEquals("Quant Lens report symbol is required.", failure.message)
    }

    @Test
    fun rejects_available_section_without_reason_codes() {
        val failure = assertFailsWith<IllegalArgumentException> {
            QuantLensEvidenceStrength(
                primaryStatus = QuantLensPrimaryStatus.Available,
                band = EvidenceStrengthBand.Strong,
                strengthBps = 8_000,
                reasonCodes = emptyList(),
            )
        }

        assertEquals("Available Quant Lens sections require at least one reason code.", failure.message)
    }

    @Test
    fun rejects_out_of_bounds_fixed_point_bps_fields() {
        val failure = assertFailsWith<IllegalArgumentException> {
            SimilarSetupMatch(
                symbol = "MSFT",
                similarityBps = 10_001,
                distanceBps = 125,
                sharedFeatureCount = 3,
            )
        }

        assertEquals("similarityBps must be between 0 and 10000.", failure.message)
    }

    @Test
    fun rejects_negative_horizon_baseline_sample_count() {
        val failure = assertFailsWith<IllegalArgumentException> {
            QuantLensHorizonBaseline(
                horizon = QuantLensHorizon.FiveMinutes,
                primaryStatus = QuantLensPrimaryStatus.Insufficient,
                sourceRange = ChartRange.Day,
                lagCandles = 1,
                sampleCount = -1,
                reasonCodes = listOf(QuantLensReasonCode.InsufficientHorizonSamples),
            )
        }

        assertEquals("sampleCount cannot be negative.", failure.message)
    }

    @Test
    fun rejects_negative_absolute_horizon_move_bps() {
        val failure = assertFailsWith<IllegalArgumentException> {
            QuantLensHorizonBaseline(
                horizon = QuantLensHorizon.FiveMinutes,
                primaryStatus = QuantLensPrimaryStatus.Available,
                sourceRange = ChartRange.Day,
                lagCandles = 1,
                sampleCount = 10,
                medianAbsoluteMoveBps = -1,
                reasonCodes = listOf(QuantLensReasonCode.HistoricalBaselineAvailable),
            )
        }

        assertEquals("medianAbsoluteMoveBps must be between 0 and 100000.", failure.message)
    }

    @Test
    fun preserves_fixed_point_public_fields() {
        val ev = QuantLensExpectedValueRange(
            primaryStatus = QuantLensPrimaryStatus.Available,
            band = ExpectedValueRangeBand.ScenarioWeighted,
            source = ExpectedValueRangeSource.Dcf,
            weightedFairValueCents = 12_500,
            weightedUpsideBps = 2_500,
            lowFairValueCents = 10_000,
            highFairValueCents = 15_000,
            spreadBps = 5_000,
            reasonCodes = listOf(QuantLensReasonCode.CompleteScenarioAnchors),
        )

        assertEquals(12_500, ev.weightedFairValueCents)
        assertEquals(2_500, ev.weightedUpsideBps)
        assertEquals(10_000, ev.lowFairValueCents)
        assertEquals(15_000, ev.highFairValueCents)
        assertEquals(5_000, ev.spreadBps)
    }

    private fun scaffoldReport(symbol: String = "ACME") = QuantLensReport(
        symbol = symbol,
        selectedRange = ChartRange.Month,
        computedAtEpochSeconds = 1_777_000_000,
        modelVersion = QuantLensModelVersion.CURRENT,
        inputFingerprint = "fixture",
        primaryStatus = QuantLensPrimaryStatus.Sparse,
        evidenceStrength = QuantLensEvidenceStrength(
            primaryStatus = QuantLensPrimaryStatus.Sparse,
            band = EvidenceStrengthBand.Sparse,
            reasonCodes = listOf(QuantLensReasonCode.ScaffoldPending),
        ),
        expectedValueRange = QuantLensExpectedValueRange(
            primaryStatus = QuantLensPrimaryStatus.Sparse,
            band = ExpectedValueRangeBand.Sparse,
            reasonCodes = listOf(QuantLensReasonCode.ScaffoldPending),
        ),
        correlationRisk = QuantLensCorrelationRisk(
            primaryStatus = QuantLensPrimaryStatus.Unavailable,
            band = CorrelationRiskBand.Unavailable,
            reasonCodes = listOf(QuantLensReasonCode.ScaffoldPending),
        ),
        trendReliability = QuantLensTrendReliability(
            primaryStatus = QuantLensPrimaryStatus.Insufficient,
            band = TrendReliabilityBand.Insufficient,
            reasonCodes = listOf(QuantLensReasonCode.ScaffoldPending),
        ),
        horizonContext = QuantLensHorizonContext(
            primaryStatus = QuantLensPrimaryStatus.Unavailable,
            horizons = listOf(
                QuantLensHorizonBaseline(
                    horizon = QuantLensHorizon.FiveMinutes,
                    primaryStatus = QuantLensPrimaryStatus.Unavailable,
                    sourceRange = ChartRange.Day,
                    lagCandles = 1,
                    sampleCount = 0,
                    reasonCodes = listOf(QuantLensReasonCode.MissingHorizonCandles),
                ),
            ),
            reasonCodes = listOf(QuantLensReasonCode.MissingHorizonCandles),
        ),
        similarSetups = QuantLensSimilarSetups(
            primaryStatus = QuantLensPrimaryStatus.Sparse,
            band = SimilarSetupsBand.Sparse,
            reasonCodes = listOf(QuantLensReasonCode.ScaffoldPending),
        ),
    )
}
