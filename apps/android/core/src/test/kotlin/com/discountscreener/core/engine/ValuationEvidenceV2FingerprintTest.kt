package com.discountscreener.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Dual-lock: Kotlin SHA-256 must match Rust / shared contract goldens byte-for-byte.
 */
class ValuationEvidenceV2FingerprintTest {
    @Test
    fun baseline_observation_matches_contract_sha256() {
        val fp = sampleBaseline().fingerprintSha256()
        assertEquals(
            "sha256:18ad8a23cbc8e036a39fecee1d2ef42171ef14257325a44668064e3eddd0f8b1",
            fp,
        )
    }

    @Test
    fun value_cents_mutation_changes_fingerprint() {
        val a = sampleBaseline()
        val b = sampleBaseline().copy(valueCents = 1301L)
        assertNotEquals(a.fingerprintSha256(), b.fingerprintSha256())
    }

    @Test
    fun nfc_decomposed_and_composed_definitions_match() {
        val decomposed = sampleBaseline().copy(definition = "cafe\u0301")
        val composed = sampleBaseline().copy(definition = "caf\u00e9")
        assertEquals(decomposed.fingerprintSha256(), composed.fingerprintSha256())
    }

    @Test
    fun null_security_differs_from_empty_security() {
        val nullSec = sampleBaseline().copy(securityId = null)
        val emptySec = sampleBaseline().copy(securityId = "")
        assertNotEquals(nullSec.fingerprintSha256(), emptySec.fingerprintSha256())
    }

    private fun sampleBaseline(): EvidenceObservationV2 =
        EvidenceObservationV2(
            id = "obs:fixture:1",
            issuerId = "issuer:0001018724",
            securityId = "sec:amzn-us",
            evidenceLane = EvidenceLane.AnalystStatedMethod,
            providerId = "manual_import",
            lineageGroupId = "lineage:jpm-amzn-2026-07-31",
            metricId = "diluted_eps",
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
            extractionMethod = "manual_entry",
            quality = "provisional",
            retrievalState = "retrieved",
            revisionId = "r1",
            supersedes = null,
            externalFileReference = null,
            storageDisposition = StorageDisposition.MetadataOnly,
        )
}
