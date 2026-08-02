package com.discountscreener.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnalystMethodImportTest {
    private val admissionContext = AnalystMethodImport.AdmissionContext(
        expectedDecisionAtUnixMs = 1_753_920_000_000L,
        expectedEpsShareBasisId = "share_basis:amzn-us:post-split-2022",
    )

    @Test
    fun derives_fem_from_observations_and_computes_three_sixty_four() {
        val parsed = AnalystMethodImport.admit(
            schemaVersion = 1,
            qualityLabelRaw = "fixture_transcription",
            issuerId = "issuer:0001018724",
            securityId = "sec:amzn-us",
            runId = "run:fixture:amzn-fem-1",
            decisionAtUnixMs = admissionContext.expectedDecisionAtUnixMs,
            admissionContext = admissionContext,
            observations = listOf(epsObs(), peObs()),
            fem = femSection(),
        ).getOrThrow()
        assertEquals(1300L, parsed.femInput.epsCents)
        assertEquals(2800, parsed.femInput.multipleHundredths)
        when (val r = ForwardEarningsMultiple.compute(parsed.femInput)) {
            is ForwardEarningsMultiple.Result.AvailableResult ->
                assertEquals(36_400L, r.value.targetValueCents)
            is ForwardEarningsMultiple.Result.Unavailable ->
                error("expected available: ${r.reasonCode}")
        }
    }

    @Test
    fun observation_value_drives_fem_not_duplicate_fields() {
        val parsed = AnalystMethodImport.admit(
            schemaVersion = 1,
            qualityLabelRaw = "fixture_transcription",
            issuerId = "issuer:0001018724",
            securityId = "sec:amzn-us",
            runId = "run:1",
            decisionAtUnixMs = admissionContext.expectedDecisionAtUnixMs,
            admissionContext = admissionContext,
            observations = listOf(epsObs().copy(valueCents = 1400L), peObs()),
            fem = femSection(),
        ).getOrThrow()
        assertEquals(1400L, parsed.femInput.epsCents)
        when (val r = ForwardEarningsMultiple.compute(parsed.femInput)) {
            is ForwardEarningsMultiple.Result.AvailableResult ->
                assertEquals(39_200L, r.value.targetValueCents)
            else -> error("expected available")
        }
    }

    @Test
    fun missing_eps_observation_refuses() {
        val result = AnalystMethodImport.admit(
            schemaVersion = 1,
            qualityLabelRaw = "fixture_transcription",
            issuerId = "issuer:0001018724",
            securityId = "sec:amzn-us",
            runId = "run:1",
            decisionAtUnixMs = admissionContext.expectedDecisionAtUnixMs,
            admissionContext = admissionContext,
            observations = listOf(epsObs(), peObs()),
            fem = femSection().copy(epsObservationId = "obs:missing"),
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("eps_observation_not_in_set") == true)
    }

    @Test
    fun unverified_with_reported_gaap_refuses() {
        val result = AnalystMethodImport.admit(
            schemaVersion = 1,
            qualityLabelRaw = "manual_transcription_unverified",
            issuerId = "issuer:0001018724",
            securityId = "sec:amzn-us",
            runId = "run:1",
            decisionAtUnixMs = admissionContext.expectedDecisionAtUnixMs,
            admissionContext = admissionContext,
            observations = listOf(
                epsObs().copy(metricBasis = MetricBasis.ReportedGaap),
                peObs(),
            ),
            fem = femSection(),
        )
        assertTrue(result.isFailure)
        assertEquals(
            "unverified_requires_transcription_claim",
            result.exceptionOrNull()?.message,
        )
    }

    @Test
    fun pit_operational_look_ahead_refuses() {
        val parsed = AnalystMethodImport.admit(
            schemaVersion = 1,
            qualityLabelRaw = "fixture_transcription",
            issuerId = "issuer:0001018724",
            securityId = "sec:amzn-us",
            runId = "run:1",
            decisionAtUnixMs = admissionContext.expectedDecisionAtUnixMs,
            admissionContext = admissionContext,
            observations = listOf(epsObs(), peObs()),
            fem = femSection(),
        ).getOrThrow()
        val code = AnalystMethodImport.admitForDecision(
            parsed.observations,
            ReplayMode.Operational,
            100L,
        )
        assertTrue(code?.startsWith("look_ahead_refused") == true)
    }

    @Test
    fun non_eps_money_observation_refuses() {
        val result = AnalystMethodImport.admit(
            schemaVersion = 1,
            qualityLabelRaw = "fixture_transcription",
            issuerId = "issuer:0001018724",
            securityId = "sec:amzn-us",
            runId = "run:1",
            decisionAtUnixMs = admissionContext.expectedDecisionAtUnixMs,
            admissionContext = admissionContext,
            observations = listOf(epsObs().copy(metricId = "revenue"), peObs()),
            fem = femSection(),
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("eps_metric_not_earnings") == true)
    }

    @Test
    fun lineage_mismatch_refuses() {
        val result = AnalystMethodImport.admit(
            schemaVersion = 1,
            qualityLabelRaw = "fixture_transcription",
            issuerId = "issuer:0001018724",
            securityId = "sec:amzn-us",
            runId = "run:1",
            decisionAtUnixMs = admissionContext.expectedDecisionAtUnixMs,
            admissionContext = admissionContext,
            observations = listOf(epsObs(), peObs().copy(lineageGroupId = "lineage:other")),
            fem = femSection(),
        )
        assertTrue(result.isFailure)
        assertEquals("lineage_mismatch_eps_multiple", result.exceptionOrNull()?.message)
    }

    private fun femSection() = AnalystMethodImport.FemSection(
        epsObservationId = "obs:fixture:eps:1",
        epsShareBasisId = "share_basis:amzn-us:post-split-2022",
        multipleObservationId = "obs:fixture:pe:1",
        multipleProvenance = "analyst_stated",
        forecastPeriodEnd = "2028-12-31",
        targetAsOf = "2027-12",
        datePrecision = "month_label",
        marketPriceCents = 20_000L,
        statedTargetCents = 36_500L,
    )

    private fun epsObs() = EvidenceObservationV2(
        id = "obs:fixture:eps:1",
        issuerId = "issuer:0001018724",
        securityId = "sec:amzn-us",
        evidenceLane = EvidenceLane.AnalystStatedMethod,
        providerId = "manual_import",
        lineageGroupId = "lineage:jpm-amzn-2026-07-31",
        metricId = "gaap_diluted_eps",
        metricBasis = MetricBasis.TranscriptionClaim,
        accountingRegime = AccountingRegime.DomesticUsGaap,
        economicPeriodStart = "2028-01-01",
        economicPeriodEnd = "2028-12-31",
        datePrecision = DatePrecision.FiscalPeriod,
        publicationAtUnixMs = 1_753_920_000_000L,
        sourceAvailableAtUnixMs = 1_753_920_000_000L,
        ingestedAtUnixMs = 1_753_920_000_000L,
        availabilityBasis = AvailabilityBasis.PrimaryPublication,
        providerVintageId = null,
        unit = EvidenceUnitV2.MoneyCents,
        valueCents = 1300L,
        valueBps = null,
        valueMillis = null,
        textValue = null,
        currency = "USD",
        definition = "FY2028E GAAP diluted EPS claim (unverified transcription)",
        sourceLocation = "manual:transcription",
        extractionMethod = "fixture_transcription",
        quality = "provisional",
        retrievalState = "retrieved",
        revisionId = "r1",
        supersedes = null,
        externalFileReference = null,
        storageDisposition = StorageDisposition.MetadataOnly,
    )

    private fun peObs() = EvidenceObservationV2(
        id = "obs:fixture:pe:1",
        issuerId = "issuer:0001018724",
        securityId = "sec:amzn-us",
        evidenceLane = EvidenceLane.AnalystStatedMethod,
        providerId = "manual_import",
        lineageGroupId = "lineage:jpm-amzn-2026-07-31",
        metricId = "forward_pe",
        metricBasis = MetricBasis.TranscriptionClaim,
        accountingRegime = AccountingRegime.NotApplicable,
        economicPeriodStart = "2028-01-01",
        economicPeriodEnd = "2028-12-31",
        datePrecision = DatePrecision.MonthLabel,
        publicationAtUnixMs = 1_753_920_000_000L,
        sourceAvailableAtUnixMs = 1_753_920_000_000L,
        ingestedAtUnixMs = 1_753_920_000_000L,
        availabilityBasis = AvailabilityBasis.PrimaryPublication,
        providerVintageId = null,
        unit = EvidenceUnitV2.MultipleHundredths,
        valueCents = null,
        valueBps = null,
        valueMillis = 2800L,
        textValue = null,
        currency = "USD",
        definition = "Forward P/E claim 28.00x (unverified transcription)",
        sourceLocation = "manual:transcription",
        extractionMethod = "fixture_transcription",
        quality = "provisional",
        retrievalState = "retrieved",
        revisionId = "r1",
        supersedes = null,
        externalFileReference = null,
        storageDisposition = StorageDisposition.MetadataOnly,
    )
}
