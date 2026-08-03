package com.discountscreener.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceContinuityTest {
    private fun policy() = SourceContinuityPolicy()

    /** SNDK-class: short stale SEC cash vs multi-billion Yahoo cash. */
    private fun sndkEvidence() = SourceContinuityEvidence(
        latestSecFiscalYear = 2022,
        secSeriesLength = 3,
        lastSecOcfDollars = 84_000_000L,
        lastSecFcfDollars = -120_000_000L,
        yahooOcfDollars = 4_640_000_000L,
        yahooFcfDollars = 2_260_000_000L,
        asOfEpochDay = 20_665L,
    )

    /** Continuous control: multi-year SEC aligned with Yahoo cash (AAPL/T-class). */
    private fun continuousEvidence() = SourceContinuityEvidence(
        latestSecFiscalYear = 2025,
        secSeriesLength = 5,
        lastSecOcfDollars = 118_000_000_000L,
        lastSecFcfDollars = 99_000_000_000L,
        yahooOcfDollars = 118_300_000_000L,
        yahooFcfDollars = 98_800_000_000L,
        secCik = 320_193L,
        yahooCik = 320_193L,
        asOfEpochDay = 20_665L,
    )

    @Test
    fun sndk_class_is_discontinuous_with_scale_reasons() {
        val decision = evaluateSourceContinuity(sndkEvidence(), policy())
        assertEquals(ContinuityStatus.Discontinuous, decision.status)
        assertTrue(ContinuityReason.ScaleMismatchOcf in decision.reasons)
        assertTrue(ContinuityReason.ScaleMismatchFcf in decision.reasons)
        assertTrue(ContinuityReason.ScaleSignConflict in decision.reasons)
        assertTrue(ContinuityReason.SecSeriesShort in decision.reasons)
        assertTrue(ContinuityReason.SecFiscalLagSupporting in decision.reasons)
        assertTrue(emitsSourceDiscontinuity(decision))
        assertTrue(decision.fingerprint.contains(CONTINUITY_POLICY_VERSION))
        assertTrue(decision.fingerprint.contains("discontinuous"))
        assertEquals(CONTINUITY_POLICY_VERSION, decision.policyVersion)
    }

    @Test
    fun continuous_control_does_not_force_discontinuity_from_calendar_alone() {
        val decision = evaluateSourceContinuity(continuousEvidence(), policy())
        assertEquals(ContinuityStatus.Continuous, decision.status)
        assertTrue(ContinuityReason.AlignedScale in decision.reasons)
        assertFalse(ContinuityReason.ScaleMismatchOcf in decision.reasons)
        assertFalse(emitsSourceDiscontinuity(decision))
    }

    @Test
    fun calendar_lag_without_scale_mismatch_is_not_discontinuous() {
        val decision = evaluateSourceContinuity(
            continuousEvidence().copy(latestSecFiscalYear = 2024),
            policy(),
        )
        assertEquals(ContinuityStatus.Continuous, decision.status)
        assertTrue(ContinuityReason.SecFiscalLagSupporting in decision.reasons)
        assertFalse(emitsSourceDiscontinuity(decision))
    }

    @Test
    fun missing_yahoo_cash_is_insufficient_evidence_not_invented_continuity() {
        val decision = evaluateSourceContinuity(
            continuousEvidence().copy(yahooOcfDollars = null, yahooFcfDollars = null),
            policy(),
        )
        assertEquals(ContinuityStatus.InsufficientEvidence, decision.status)
        assertTrue(ContinuityReason.YahooCashMissing in decision.reasons)
        assertFalse(emitsSourceDiscontinuity(decision))
    }

    @Test
    fun absent_sec_series_is_insufficient_evidence() {
        val decision = evaluateSourceContinuity(
            SourceContinuityEvidence(
                latestSecFiscalYear = null,
                secSeriesLength = 0,
                yahooOcfDollars = 1_000_000_000L,
                yahooFcfDollars = 500_000_000L,
                asOfEpochDay = 20_665L,
            ),
            policy(),
        )
        assertEquals(ContinuityStatus.InsufficientEvidence, decision.status)
        assertTrue(ContinuityReason.SecSeriesAbsent in decision.reasons)
    }

    @Test
    fun entity_cik_mismatch_is_discontinuous() {
        val decision = evaluateSourceContinuity(
            continuousEvidence().copy(yahooCik = 999_999L),
            policy(),
        )
        assertEquals(ContinuityStatus.Discontinuous, decision.status)
        assertTrue(ContinuityReason.EntityCikMismatch in decision.reasons)
        assertTrue(emitsSourceDiscontinuity(decision))
    }

    @Test
    fun fingerprint_includes_policy_version_and_is_deterministic() {
        val a = evaluateSourceContinuity(sndkEvidence(), policy())
        val b = evaluateSourceContinuity(sndkEvidence(), policy())
        assertEquals(a.fingerprint, b.fingerprint)
        assertTrue(a.fingerprint.contains("policy=source-continuity/1"))
        assertTrue(a.fingerprint.contains("status=discontinuous"))
    }
}
