package com.discountscreener.core.engine

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.text.Normalizer

/**
 * Foundation 0A: EvidenceObservation V2 pure helpers (parity with Rust `valuation_evidence.rs`).
 * Partition keys, replay admission, lineage component counts, SHA-256 canonical fingerprints.
 */

enum class EvidenceLane(val snake: String) {
    ReportedActual("reported_actual"),
    IssuerGuidance("issuer_guidance"),
    ExternalConsensus("external_consensus"),
    InternalForecast("internal_forecast"),
    AnalystStatedMethod("analyst_stated_method"),
}

enum class MetricBasis(val snake: String) {
    ReportedGaap("reported_gaap"),
    AdjustedNormalized("adjusted_normalized"),
    ProviderUnknown("provider_unknown"),
    TranscriptionClaim("transcription_claim"),
}

enum class AccountingRegime(val snake: String) {
    DomesticUsGaap("domestic_us_gaap"),
    Ifrs("ifrs"),
    NotApplicable("not_applicable"),
    Unsupported("unsupported"),
}

enum class DatePrecision(val snake: String) {
    ExactDate("exact_date"),
    MonthLabel("month_label"),
    FiscalPeriod("fiscal_period"),
    ProviderHorizon("provider_horizon"),
}

enum class AvailabilityBasis(val snake: String) {
    PrimaryPublication("primary_publication"),
    ProviderCertifiedVintage("provider_certified_vintage"),
    FirstObservedCapture("first_observed_capture"),
}

enum class ReplayMode(val snake: String) {
    Operational("operational"),
    CertifiedBackfillResearch("certified_backfill_research"),
}

enum class StorageDisposition(val snake: String) {
    MetadataOnly("metadata_only"),
    EncryptedArtifact("encrypted_artifact"),
    Prohibited("prohibited"),
}

enum class EvidenceUnitV2(val snake: String) {
    MoneyCents("money_cents"),
    RateBps("rate_bps"),
    QuantityMillis("quantity_millis"),
    Shares("shares"),
    Text("text"),
    Boolean("boolean"),
    MultipleHundredths("multiple_hundredths"),
}

data class EvidenceObservationV2(
    val id: String,
    val issuerId: String,
    val securityId: String?,
    val evidenceLane: EvidenceLane,
    val providerId: String,
    val lineageGroupId: String,
    val metricId: String,
    val metricBasis: MetricBasis,
    val accountingRegime: AccountingRegime,
    val economicPeriodStart: String,
    val economicPeriodEnd: String,
    val datePrecision: DatePrecision,
    val publicationAtUnixMs: Long,
    val sourceAvailableAtUnixMs: Long,
    val ingestedAtUnixMs: Long,
    val availabilityBasis: AvailabilityBasis,
    val providerVintageId: String?,
    val unit: EvidenceUnitV2,
    val valueCents: Long?,
    val valueBps: Int?,
    val valueMillis: Long?,
    val textValue: String?,
    val currency: String?,
    val definition: String,
    val sourceLocation: String,
    val extractionMethod: String,
    val quality: String,
    val retrievalState: String,
    val revisionId: String,
    val supersedes: String?,
    val externalFileReference: String?,
    val storageDisposition: StorageDisposition,
) {
    fun partitionKey(): ValuationEvidenceV2.ResolutionPartitionKey =
        ValuationEvidenceV2.partitionKey(
            issuerId,
            securityId,
            evidenceLane.snake,
            metricId,
            metricBasis.snake,
            accountingRegime.snake,
            economicPeriodStart,
            economicPeriodEnd,
            unit.snake,
            currency,
        )

    fun validateIdentity(): String? {
        if (id.trim().isEmpty()) return "empty_id"
        if (issuerId.trim().isEmpty()) return "empty_issuer_id"
        if (lineageGroupId.trim().isEmpty()) return "empty_lineage_group_id"
        if (metricId.trim().isEmpty()) return "empty_metric_id"
        if (providerId.trim().isEmpty()) return "empty_provider_id"
        var slots = 0
        if (valueCents != null) slots += 1
        if (valueBps != null) slots += 1
        if (valueMillis != null) slots += 1
        if (textValue != null) slots += 1
        if (slots != 1) return "exactly_one_value_required"
        return null
    }

    /** Full admission before ledger persistence (1B-0). */
    fun validateForPersist(): String? {
        validateIdentity()?.let { return it }
        if (storageDisposition == StorageDisposition.Prohibited) return "storage_prohibited"
        validateUnitValueSlot()?.let { return it }
        validateClockOrder(publicationAtUnixMs, sourceAvailableAtUnixMs, ingestedAtUnixMs)?.let { return it }
        if (quality.trim() !in setOf("solid", "soft", "provisional")) return "invalid_quality"
        if (retrievalState.trim() !in setOf("retrieved", "not_retrieved", "partial")) {
            return "invalid_retrieval_state"
        }
        return null
    }

    private fun validateUnitValueSlot(): String? {
        var ok = when (unit) {
            EvidenceUnitV2.MoneyCents ->
                valueCents != null && valueBps == null && valueMillis == null && textValue == null
            EvidenceUnitV2.RateBps ->
                valueBps != null && valueCents == null && valueMillis == null && textValue == null
            EvidenceUnitV2.QuantityMillis, EvidenceUnitV2.Shares, EvidenceUnitV2.MultipleHundredths ->
                valueMillis != null && valueCents == null && valueBps == null && textValue == null
            EvidenceUnitV2.Text, EvidenceUnitV2.Boolean ->
                textValue != null && valueCents == null && valueBps == null && valueMillis == null
        }
        return if (ok) null else "unit_value_slot_mismatch"
    }

    fun fingerprintSha256(): String {
        var digest = MessageDigest.getInstance("SHA-256").digest(encodeCanonical())
        return "sha256:" + digest.joinToString("") { b -> "%02x".format(b) }
    }

    /** Byte-for-byte parity with Rust `encode_observation_canonical`. */
    fun encodeCanonical(): ByteArray {
        var out = ArrayList<Byte>(512)
        fun writeU8(v: Int) {
            out.add(v.toByte())
        }
        fun writeU16(v: Int) {
            out.add(((v ushr 8) and 0xff).toByte())
            out.add((v and 0xff).toByte())
        }
        fun writeU32(v: Int) {
            out.add(((v ushr 24) and 0xff).toByte())
            out.add(((v ushr 16) and 0xff).toByte())
            out.add(((v ushr 8) and 0xff).toByte())
            out.add((v and 0xff).toByte())
        }
        fun writeStr(s: String) {
            var nfc = Normalizer.normalize(s, Normalizer.Form.NFC)
            var bytes = nfc.toByteArray(Charsets.UTF_8)
            writeU8(0x01)
            writeU32(bytes.size)
            for (b in bytes) out.add(b)
        }
        fun writeOptStr(s: String?) {
            if (s == null) {
                writeU8(0x00)
            } else {
                writeStr(s)
            }
        }
        fun writeI64(v: Long) {
            writeU8(0x01)
            var buf = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(v).array()
            for (b in buf) out.add(b)
        }
        fun writeOptI64(v: Long?) {
            if (v == null) {
                writeU8(0x00)
            } else {
                writeI64(v)
            }
        }
        fun writeOptI32(v: Int?) {
            if (v == null) {
                writeU8(0x00)
            } else {
                writeU8(0x01)
                var buf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(v).array()
                for (b in buf) out.add(b)
            }
        }

        writeStr(ValuationEvidenceV2.DOMAIN_OBSERVATION)
        writeU16(1) // scheme version
        writeU8(1) // record kind observation
        writeU16(ValuationEvidenceV2.SCHEMA_VERSION)
        writeStr(ValuationEvidenceV2.FINGERPRINT_SCHEME)
        writeStr(id)
        writeStr(issuerId)
        writeOptStr(securityId)
        writeStr(evidenceLane.snake)
        writeStr(providerId)
        writeStr(lineageGroupId)
        writeStr(metricId)
        writeStr(metricBasis.snake)
        writeStr(accountingRegime.snake)
        writeStr(economicPeriodStart)
        writeStr(economicPeriodEnd)
        writeStr(datePrecision.snake)
        writeI64(publicationAtUnixMs)
        writeI64(sourceAvailableAtUnixMs)
        writeI64(ingestedAtUnixMs)
        writeStr(availabilityBasis.snake)
        writeOptStr(providerVintageId)
        writeStr(unit.snake)
        writeOptI64(valueCents)
        writeOptI32(valueBps)
        writeOptI64(valueMillis)
        writeOptStr(textValue)
        writeOptStr(currency)
        writeStr(definition)
        writeStr(sourceLocation)
        writeStr(extractionMethod)
        writeStr(quality)
        writeStr(retrievalState)
        writeStr(revisionId)
        writeOptStr(supersedes)
        writeOptStr(externalFileReference)
        writeStr(storageDisposition.snake)
        return out.toByteArray()
    }
}

/** Clock order: published → available → ingested (equal allowed). */
fun validateClockOrder(
    publicationAtUnixMs: Long,
    sourceAvailableAtUnixMs: Long,
    ingestedAtUnixMs: Long,
): String? {
    if (publicationAtUnixMs < 0L || sourceAvailableAtUnixMs < 0L || ingestedAtUnixMs < 0L) {
        return "negative_clock"
    }
    if (sourceAvailableAtUnixMs < publicationAtUnixMs) return "clock_order_source_before_publication"
    if (ingestedAtUnixMs < sourceAvailableAtUnixMs) return "clock_order_ingestion_before_source"
    return null
}

object ValuationEvidenceV2 {
    const val FINGERPRINT_SCHEME = "sha256_canonical_v1"
    const val SCHEMA_VERSION: Int = 2
    const val DOMAIN_OBSERVATION = "ds.valuation.evidence_observation.v2"
    const val DOMAIN_EVIDENCE_SET = "ds.valuation.evidence_set.v2"

    data class AdmissionDecision(
        val admit: Boolean,
        val liveProjectionEligible: Boolean,
        val refusalCode: String? = null,
    )

    /** Canonical evidence-set fingerprint (parity with Rust `evidence_set_fingerprint`). */
    fun evidenceSetFingerprint(observationFingerprints: List<String>): String {
        var fps = observationFingerprints.map { it.trim() }.filter { it.isNotEmpty() }.sorted().distinct()
        var out = ArrayList<Byte>(256)
        fun writeU8(v: Int) { out.add(v.toByte()) }
        fun writeU16(v: Int) {
            out.add(((v ushr 8) and 0xff).toByte())
            out.add((v and 0xff).toByte())
        }
        fun writeU32(v: Int) {
            out.add(((v ushr 24) and 0xff).toByte())
            out.add(((v ushr 16) and 0xff).toByte())
            out.add(((v ushr 8) and 0xff).toByte())
            out.add((v and 0xff).toByte())
        }
        fun writeStr(s: String) {
            var nfc = Normalizer.normalize(s, Normalizer.Form.NFC)
            var bytes = nfc.toByteArray(Charsets.UTF_8)
            writeU8(0x01)
            writeU32(bytes.size)
            for (b in bytes) out.add(b)
        }
        writeStr(DOMAIN_EVIDENCE_SET)
        writeU16(1)
        writeU16(SCHEMA_VERSION)
        writeStr(FINGERPRINT_SCHEME)
        writeU32(fps.size)
        for (fp in fps) writeStr(fp)
        var digest = MessageDigest.getInstance("SHA-256").digest(out.toByteArray())
        return "sha256:" + digest.joinToString("") { b -> "%02x".format(b) }
    }

    fun admitObservation(
        mode: ReplayMode,
        decisionAtUnixMs: Long,
        publicationAtUnixMs: Long,
        sourceAvailableAtUnixMs: Long,
        ingestedAtUnixMs: Long,
        availabilityBasis: AvailabilityBasis,
        providerVintageId: String?,
    ): AdmissionDecision {
        if (publicationAtUnixMs > decisionAtUnixMs) {
            return AdmissionDecision(false, false, "look_ahead_publication")
        }
        if (sourceAvailableAtUnixMs > decisionAtUnixMs) {
            return AdmissionDecision(false, false, "look_ahead_source_available")
        }
        return when (mode) {
            ReplayMode.Operational -> {
                if (ingestedAtUnixMs > decisionAtUnixMs) {
                    AdmissionDecision(false, false, "look_ahead_ingestion")
                } else {
                    AdmissionDecision(true, true, null)
                }
            }
            ReplayMode.CertifiedBackfillResearch -> {
                if (ingestedAtUnixMs <= decisionAtUnixMs) {
                    AdmissionDecision(true, false, null)
                } else {
                    var vintageOk = availabilityBasis == AvailabilityBasis.ProviderCertifiedVintage &&
                        !providerVintageId.isNullOrBlank()
                    if (vintageOk) {
                        AdmissionDecision(true, false, null)
                    } else {
                        AdmissionDecision(false, false, "missing_provider_vintage")
                    }
                }
            }
        }
    }

    fun lineageComponentCount(lineageGroupIds: List<String>): Int =
        lineageGroupIds.map { it.trim() }.filter { it.isNotEmpty() }.toSortedSet().size

    data class ResolutionPartitionKey(
        val issuerId: String,
        val securityId: String?,
        val evidenceLane: String,
        val metricId: String,
        val metricBasis: String,
        val accountingRegime: String,
        val economicPeriodStart: String,
        val economicPeriodEnd: String,
        val unit: String,
        val currency: String?,
    )

    fun partitionKey(
        issuerId: String,
        securityId: String?,
        evidenceLane: String,
        metricId: String,
        metricBasis: String,
        accountingRegime: String,
        economicPeriodStart: String,
        economicPeriodEnd: String,
        unit: String,
        currency: String?,
    ): ResolutionPartitionKey = ResolutionPartitionKey(
        issuerId,
        securityId,
        evidenceLane,
        metricId,
        metricBasis,
        accountingRegime,
        economicPeriodStart,
        economicPeriodEnd,
        unit,
        currency,
    )
}
