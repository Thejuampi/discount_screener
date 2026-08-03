package com.discountscreener.core.engine

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Shared-contract harness for EvidenceObservation V2 (parity with Rust
 * `valuation_evidence_contract.rs`).
 */
class ValuationEvidenceV2ContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun shared_partition_fixtures_execute_on_android() {
        val contract = load()
        for (f in contract.fixtures.partition) {
            val a = ValuationEvidenceV2.partitionKey(
                f.a.issuerId, f.a.securityId, f.a.evidenceLane, f.a.metricId, f.a.metricBasis,
                f.a.accountingRegime, f.a.economicPeriodStart, f.a.economicPeriodEnd, f.a.unit, f.a.currency,
            )
            val b = ValuationEvidenceV2.partitionKey(
                f.b.issuerId, f.b.securityId, f.b.evidenceLane, f.b.metricId, f.b.metricBasis,
                f.b.accountingRegime, f.b.economicPeriodStart, f.b.economicPeriodEnd, f.b.unit, f.b.currency,
            )
            if (f.expectSamePartition) {
                assertEquals(a, b, f.name)
            } else {
                assertNotEquals(a, b, f.name)
            }
        }
    }

    @Test
    fun shared_replay_admission_fixtures_execute_on_android() {
        val contract = load()
        for (f in contract.fixtures.replayAdmission) {
            val d = ValuationEvidenceV2.admitObservation(
                parseReplay(f.replayMode),
                f.decisionAtUnixMs,
                f.publicationAtUnixMs,
                f.sourceAvailableAtUnixMs,
                f.ingestedAtUnixMs,
                parseAvailability(f.availabilityBasis),
                f.providerVintageId,
            )
            assertEquals(f.expectAdmit, d.admit, f.name)
            f.liveProjectionEligible?.let { assertEquals(it, d.liveProjectionEligible, f.name) }
            f.refusalCode?.let { assertEquals(it, d.refusalCode, f.name) }
        }
    }

    @Test
    fun shared_lineage_fixtures_execute_on_android() {
        val contract = load()
        for (f in contract.fixtures.lineage) {
            assertEquals(
                f.expectedComponentCount,
                ValuationEvidenceV2.lineageComponentCount(f.lineageGroupIds),
                f.name,
            )
        }
    }

    @Test
    fun shared_canonical_fingerprint_fixtures_execute_on_android() {
        val contract = load()
        for (f in contract.fixtures.canonical) {
            when (f.name) {
                "baseline_observation" -> {
                    val obs = toObs(f.observation!!)
                    assertEquals(f.expectedSha256, obs.fingerprintSha256(), f.name)
                }
                "null_security_vs_empty_security_differ" -> {
                    val nullObs = toObs(f.observationNull!!)
                    val emptyObs = toObs(f.observationEmpty!!)
                    assertNotEquals(nullObs.fingerprintSha256(), emptyObs.fingerprintSha256(), f.name)
                }
                else -> error("unhandled canonical fixture: ${f.name}")
            }
        }
    }

    @Test
    fun shared_evidence_set_fingerprint_fixtures_execute_on_android() {
        val contract = load()
        assertTrue(contract.fixtures.evidenceSet.isNotEmpty(), "evidenceSet fixtures required")
        for (f in contract.fixtures.evidenceSet) {
            val a = ValuationEvidenceV2.evidenceSetFingerprint(f.fingerprintsA)
            val b = ValuationEvidenceV2.evidenceSetFingerprint(f.fingerprintsB)
            if (f.expectSame) {
                assertEquals(a, b, f.name)
            } else {
                assertNotEquals(a, b, f.name)
            }
            f.expectedSha256?.let { expected ->
                assertEquals(expected, a, "${f.name} expectedSha256")
                assertEquals(expected, b, "${f.name} expectedSha256 on B")
            }
        }
    }

    private fun load(): Contract =
        json.decodeFromString(Files.readString(findFixture()))

    private fun findFixture(): Path {
        var current: Path? = Paths.get("").toAbsolutePath()
        while (current != null) {
            val candidate = current.resolve("shared/contracts/valuation-evidence-observation-v2.json").normalize()
            if (Files.exists(candidate)) return candidate
            current = current.parent
        }
        error("valuation-evidence-observation-v2.json not found")
    }

    private fun parseReplay(s: String): ReplayMode = when (s) {
        "operational" -> ReplayMode.Operational
        "certified_backfill_research" -> ReplayMode.CertifiedBackfillResearch
        else -> error("unknown replayMode $s")
    }

    private fun parseAvailability(s: String): AvailabilityBasis = when (s) {
        "primary_publication" -> AvailabilityBasis.PrimaryPublication
        "provider_certified_vintage" -> AvailabilityBasis.ProviderCertifiedVintage
        "first_observed_capture" -> AvailabilityBasis.FirstObservedCapture
        else -> error("unknown availabilityBasis $s")
    }

    private fun parseLane(s: String): EvidenceLane = when (s) {
        "reported_actual" -> EvidenceLane.ReportedActual
        "issuer_guidance" -> EvidenceLane.IssuerGuidance
        "external_consensus" -> EvidenceLane.ExternalConsensus
        "internal_forecast" -> EvidenceLane.InternalForecast
        "analyst_stated_method" -> EvidenceLane.AnalystStatedMethod
        else -> error("unknown evidenceLane $s")
    }

    private fun parseBasis(s: String): MetricBasis = when (s) {
        "reported_gaap" -> MetricBasis.ReportedGaap
        "adjusted_normalized" -> MetricBasis.AdjustedNormalized
        "provider_unknown" -> MetricBasis.ProviderUnknown
        "transcription_claim" -> MetricBasis.TranscriptionClaim
        else -> error("unknown metricBasis $s")
    }

    private fun parseRegime(s: String): AccountingRegime = when (s) {
        "domestic_us_gaap" -> AccountingRegime.DomesticUsGaap
        "ifrs" -> AccountingRegime.Ifrs
        "not_applicable" -> AccountingRegime.NotApplicable
        "unsupported" -> AccountingRegime.Unsupported
        else -> error("unknown accountingRegime $s")
    }

    private fun parsePrecision(s: String): DatePrecision = when (s) {
        "exact_date" -> DatePrecision.ExactDate
        "month_label" -> DatePrecision.MonthLabel
        "fiscal_period" -> DatePrecision.FiscalPeriod
        "provider_horizon" -> DatePrecision.ProviderHorizon
        else -> error("unknown datePrecision $s")
    }

    private fun parseUnit(s: String): EvidenceUnitV2 = when (s) {
        "money_cents" -> EvidenceUnitV2.MoneyCents
        "rate_bps" -> EvidenceUnitV2.RateBps
        "quantity_millis" -> EvidenceUnitV2.QuantityMillis
        "shares" -> EvidenceUnitV2.Shares
        "text" -> EvidenceUnitV2.Text
        "boolean" -> EvidenceUnitV2.Boolean
        "multiple_hundredths" -> EvidenceUnitV2.MultipleHundredths
        else -> error("unknown unit $s")
    }

    private fun parseStorage(s: String): StorageDisposition = when (s) {
        "metadata_only" -> StorageDisposition.MetadataOnly
        "encrypted_artifact" -> StorageDisposition.EncryptedArtifact
        "prohibited" -> StorageDisposition.Prohibited
        else -> error("unknown storageDisposition $s")
    }

    private fun toObs(dto: ObsDto): EvidenceObservationV2 = EvidenceObservationV2(
        id = dto.id,
        issuerId = dto.issuerId,
        securityId = dto.securityId,
        evidenceLane = parseLane(dto.evidenceLane),
        providerId = dto.providerId,
        lineageGroupId = dto.lineageGroupId,
        metricId = dto.metricId,
        metricBasis = parseBasis(dto.metricBasis),
        accountingRegime = parseRegime(dto.accountingRegime),
        economicPeriodStart = dto.economicPeriodStart,
        economicPeriodEnd = dto.economicPeriodEnd,
        datePrecision = parsePrecision(dto.datePrecision),
        publicationAtUnixMs = dto.publicationAtUnixMs,
        sourceAvailableAtUnixMs = dto.sourceAvailableAtUnixMs,
        ingestedAtUnixMs = dto.ingestedAtUnixMs,
        availabilityBasis = parseAvailability(dto.availabilityBasis),
        providerVintageId = dto.providerVintageId,
        unit = parseUnit(dto.unit),
        valueCents = dto.valueCents,
        valueBps = dto.valueBps,
        valueMillis = dto.valueMillis,
        textValue = dto.textValue,
        currency = dto.currency,
        definition = dto.definition,
        sourceLocation = dto.sourceLocation,
        extractionMethod = dto.extractionMethod,
        quality = dto.quality,
        retrievalState = dto.retrievalState,
        revisionId = dto.revisionId,
        supersedes = dto.supersedes,
        externalFileReference = dto.externalFileReference,
        storageDisposition = parseStorage(dto.storageDisposition),
    )

    @Serializable
    data class Contract(val schemaVersion: Int, val fingerprintScheme: String, val fixtures: Fixtures)

    @Serializable
    data class Fixtures(
        val partition: List<PartitionFixture>,
        val replayAdmission: List<ReplayFixture>,
        val lineage: List<LineageFixture>,
        val canonical: List<CanonicalFixture>,
        val evidenceSet: List<EvidenceSetFixture> = emptyList(),
    )

    @Serializable
    data class EvidenceSetFixture(
        val name: String,
        val fingerprintsA: List<String>,
        val fingerprintsB: List<String>,
        val expectSame: Boolean,
        val expectedSha256: String? = null,
    )

    @Serializable
    data class PartitionFixture(
        val name: String,
        val a: PartitionFields,
        val b: PartitionFields,
        val expectSamePartition: Boolean,
    )

    @Serializable
    data class PartitionFields(
        val issuerId: String,
        val securityId: String? = null,
        val evidenceLane: String,
        val metricId: String,
        val metricBasis: String,
        val accountingRegime: String,
        val economicPeriodStart: String,
        val economicPeriodEnd: String,
        val unit: String,
        val currency: String? = null,
    )

    @Serializable
    data class ReplayFixture(
        val name: String,
        val replayMode: String,
        val decisionAtUnixMs: Long,
        val publicationAtUnixMs: Long,
        val sourceAvailableAtUnixMs: Long,
        val ingestedAtUnixMs: Long,
        val availabilityBasis: String,
        val providerVintageId: String? = null,
        val expectAdmit: Boolean,
        val liveProjectionEligible: Boolean? = null,
        val refusalCode: String? = null,
    )

    @Serializable
    data class LineageFixture(
        val name: String,
        val lineageGroupIds: List<String>,
        val expectedComponentCount: Int,
    )

    @Serializable
    data class CanonicalFixture(
        val name: String,
        val observation: ObsDto? = null,
        val observationNull: ObsDto? = null,
        val observationEmpty: ObsDto? = null,
        val expectedSha256: String? = null,
    )

    @Serializable
    data class ObsDto(
        val id: String,
        val issuerId: String,
        val securityId: String? = null,
        val evidenceLane: String,
        val providerId: String,
        val lineageGroupId: String,
        val metricId: String,
        val metricBasis: String,
        val accountingRegime: String,
        val economicPeriodStart: String,
        val economicPeriodEnd: String,
        val datePrecision: String,
        val publicationAtUnixMs: Long,
        val sourceAvailableAtUnixMs: Long,
        val ingestedAtUnixMs: Long,
        val availabilityBasis: String,
        val providerVintageId: String? = null,
        val unit: String,
        val valueCents: Long? = null,
        val valueBps: Int? = null,
        val valueMillis: Long? = null,
        val textValue: String? = null,
        val currency: String? = null,
        val definition: String,
        val sourceLocation: String,
        val extractionMethod: String,
        val quality: String,
        val retrievalState: String,
        val revisionId: String,
        val supersedes: String? = null,
        val externalFileReference: String? = null,
        val storageDisposition: String,
    )
}
