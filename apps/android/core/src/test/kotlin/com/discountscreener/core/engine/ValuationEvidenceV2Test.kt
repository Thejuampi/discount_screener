package com.discountscreener.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ValuationEvidenceV2Test {
    @Test
    fun operational_ingested_after_decision_refuses() {
        val d = ValuationEvidenceV2.admitObservation(
            ReplayMode.Operational,
            1_743_465_600_000L,
            1_743_465_600_000L,
            1_743_465_600_000L,
            1_743_552_000_000L,
            AvailabilityBasis.PrimaryPublication,
            null,
        )
        assertEquals(false, d.admit)
        assertEquals("look_ahead_ingestion", d.refusalCode)
    }

    @Test
    fun certified_backfill_with_vintage_admits_not_live() {
        val d = ValuationEvidenceV2.admitObservation(
            ReplayMode.CertifiedBackfillResearch,
            1_743_465_600_000L,
            1_743_465_600_000L,
            1_743_465_600_000L,
            1_743_552_000_000L,
            AvailabilityBasis.ProviderCertifiedVintage,
            "vendor:vintage:2025-04-01",
        )
        assertTrue(d.admit)
        assertEquals(false, d.liveProjectionEligible)
    }

    @Test
    fun certified_without_vintage_refuses() {
        val d = ValuationEvidenceV2.admitObservation(
            ReplayMode.CertifiedBackfillResearch,
            1_743_465_600_000L,
            1_743_465_600_000L,
            1_743_465_600_000L,
            1_743_552_000_000L,
            AvailabilityBasis.FirstObservedCapture,
            null,
        )
        assertEquals(false, d.admit)
        assertEquals("missing_provider_vintage", d.refusalCode)
    }

    @Test
    fun lineage_component_count_counts_unique_groups() {
        assertEquals(
            1,
            ValuationEvidenceV2.lineageComponentCount(
                listOf("lineage:jpm-amzn-2026-07-31", "lineage:jpm-amzn-2026-07-31"),
            ),
        )
    }

    @Test
    fun lineage_component_count_two_disjoint_groups() {
        assertEquals(2, ValuationEvidenceV2.lineageComponentCount(listOf("lineage:a", "lineage:b")))
    }

    @Test
    fun partition_key_differs_by_metric_basis() {
        val a = ValuationEvidenceV2.partitionKey(
            "issuer:0001018724",
            "sec:amzn-us",
            "analyst_stated_method",
            "diluted_eps",
            "reported_gaap",
            "domestic_us_gaap",
            "2028-01-01",
            "2028-12-31",
            "money_cents",
            "USD",
        )
        val b = ValuationEvidenceV2.partitionKey(
            "issuer:0001018724",
            "sec:amzn-us",
            "analyst_stated_method",
            "diluted_eps",
            "adjusted_normalized",
            "domestic_us_gaap",
            "2028-01-01",
            "2028-12-31",
            "money_cents",
            "USD",
        )
        assertNotEquals(a, b)
    }

    @Test
    fun partition_key_same_when_only_provider_would_differ() {
        // Provider is intentionally excluded from partition key (AD-3).
        val a = ValuationEvidenceV2.partitionKey(
            "issuer:x", null, "external_consensus", "diluted_eps", "provider_unknown",
            "not_applicable", "2026-01-01", "2026-12-31", "money_cents", "USD",
        )
        val b = ValuationEvidenceV2.partitionKey(
            "issuer:x", null, "external_consensus", "diluted_eps", "provider_unknown",
            "not_applicable", "2026-01-01", "2026-12-31", "money_cents", "USD",
        )
        assertEquals(a, b)
    }

    @Test
    fun evidence_set_fingerprint_order_independent_and_dual_locked() {
        val ab = ValuationEvidenceV2.evidenceSetFingerprint(listOf("sha256:bbb", "sha256:aaa"))
        val ba = ValuationEvidenceV2.evidenceSetFingerprint(listOf("sha256:aaa", "sha256:bbb"))
        assertEquals(ab, ba)
        assertEquals(
            "sha256:0e2e803826b99c6b8ea7ab08302fc1ddb6705b70ec2b0c6d008289ad388872de",
            ab,
        )
    }

    @Test
    fun storage_prohibited_refuses_persist() {
        val obs = EvidenceObservationV2(
            id = "o",
            issuerId = "issuer:x",
            securityId = null,
            evidenceLane = EvidenceLane.ReportedActual,
            providerId = "p",
            lineageGroupId = "g",
            metricId = "revenue",
            metricBasis = MetricBasis.ReportedGaap,
            accountingRegime = AccountingRegime.DomesticUsGaap,
            economicPeriodStart = "2024-01-01",
            economicPeriodEnd = "2024-12-31",
            datePrecision = DatePrecision.FiscalPeriod,
            publicationAtUnixMs = 1L,
            sourceAvailableAtUnixMs = 1L,
            ingestedAtUnixMs = 1L,
            availabilityBasis = AvailabilityBasis.PrimaryPublication,
            providerVintageId = null,
            unit = EvidenceUnitV2.MoneyCents,
            valueCents = 1L,
            valueBps = null,
            valueMillis = null,
            textValue = null,
            currency = "USD",
            definition = "d",
            sourceLocation = "s",
            extractionMethod = "manual_entry",
            quality = "solid",
            retrievalState = "retrieved",
            revisionId = "r",
            supersedes = null,
            externalFileReference = null,
            storageDisposition = StorageDisposition.Prohibited,
        )
        assertEquals("storage_prohibited", obs.validateForPersist())
    }
}
