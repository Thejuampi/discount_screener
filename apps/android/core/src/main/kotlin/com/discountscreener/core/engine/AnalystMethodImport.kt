package com.discountscreener.core.engine

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Slice 1B / 1B.1 pure import admission (parity with Rust `analyst_method_import.rs`).
 * FEM eps/multiple are derived from referenced observations — never duplicated fields.
 */
object AnalystMethodImport {
    const val IMPORT_SCHEMA_VERSION = 1

    enum class QualityLabel(val wire: String) {
        FixtureTranscription("fixture_transcription"),
        ManualTranscriptionUnverified("manual_transcription_unverified"),
        ;

        fun requiresTranscriptionClaim(): Boolean = true

        companion object {
            fun parse(raw: String): QualityLabel? = entries.firstOrNull { it.wire == raw.trim() }
        }
    }

    data class FemSection(
        val epsObservationId: String,
        val epsShareBasisId: String,
        val multipleObservationId: String,
        val multipleProvenance: String,
        val forecastPeriodEnd: String,
        val targetAsOf: String,
        val datePrecision: String,
        val marketPriceCents: Long? = null,
        val statedTargetCents: Long? = null,
        val peerCount: Int? = null,
    )

    data class AdmissionContext(
        val expectedDecisionAtUnixMs: Long,
        val expectedEpsShareBasisId: String,
    )

    data class Parsed(
        val qualityLabel: QualityLabel,
        val issuerId: String,
        val securityId: String,
        val runId: String,
        val decisionAtUnixMs: Long,
        val epsShareBasisId: String,
        val observations: List<EvidenceObservationV2>,
        val femInput: ForwardEarningsMultiple.Input,
        val epsObservationId: String,
        val multipleObservationId: String,
    )

    fun admit(
        schemaVersion: Int,
        qualityLabelRaw: String,
        issuerId: String,
        securityId: String,
        runId: String,
        decisionAtUnixMs: Long,
        admissionContext: AdmissionContext,
        observations: List<EvidenceObservationV2>,
        fem: FemSection,
    ): Result<Parsed> {
        if (schemaVersion != IMPORT_SCHEMA_VERSION) {
            return Result.failure(IllegalArgumentException("unsupported_import_schema:$schemaVersion"))
        }
        var quality = QualityLabel.parse(qualityLabelRaw)
            ?: return Result.failure(IllegalArgumentException("invalid_quality_label:$qualityLabelRaw"))
        if (issuerId.trim().isEmpty()) {
            return Result.failure(IllegalArgumentException("empty_issuer_id"))
        }
        if (securityId.trim().isEmpty()) {
            return Result.failure(IllegalArgumentException("empty_security_id"))
        }
        if (runId.trim().isEmpty()) {
            return Result.failure(IllegalArgumentException("empty_run_id"))
        }
        if (decisionAtUnixMs <= 0 || admissionContext.expectedDecisionAtUnixMs <= 0) {
            return Result.failure(IllegalArgumentException("invalid_decision_at_unix_ms"))
        }
        if (decisionAtUnixMs != admissionContext.expectedDecisionAtUnixMs) {
            return Result.failure(IllegalArgumentException("decision_at_mismatch"))
        }
        if (fem.epsShareBasisId.trim().isEmpty()) {
            return Result.failure(IllegalArgumentException("missing_eps_share_basis_id"))
        }
        if (admissionContext.expectedEpsShareBasisId.trim().isEmpty()) {
            return Result.failure(IllegalArgumentException("missing_expected_eps_share_basis_id"))
        }
        if (fem.epsShareBasisId != admissionContext.expectedEpsShareBasisId) {
            return Result.failure(IllegalArgumentException("eps_share_basis_mismatch"))
        }
        if (observations.isEmpty()) {
            return Result.failure(IllegalArgumentException("empty_observations"))
        }
        for (obs in observations) {
            obs.validateForPersist()?.let {
                return Result.failure(IllegalArgumentException("observation_invalid:$it"))
            }
            if (obs.issuerId != issuerId) {
                return Result.failure(IllegalArgumentException("observation_issuer_mismatch"))
            }
            if (obs.securityId != securityId) {
                return Result.failure(IllegalArgumentException("observation_security_mismatch"))
            }
            if (quality.requiresTranscriptionClaim() && obs.metricBasis != MetricBasis.TranscriptionClaim) {
                return Result.failure(IllegalArgumentException("unverified_requires_transcription_claim"))
            }
        }
        var femInput = try {
            deriveFemInput(observations, fem, issuerId, securityId)
        } catch (e: IllegalArgumentException) {
            return Result.failure(e)
        } catch (e: IllegalStateException) {
            return Result.failure(IllegalArgumentException(e.message, e))
        }
        return Result.success(
            Parsed(
                qualityLabel = quality,
                issuerId = issuerId,
                securityId = securityId,
                runId = runId,
                decisionAtUnixMs = decisionAtUnixMs,
                epsShareBasisId = fem.epsShareBasisId,
                observations = observations,
                femInput = femInput,
                epsObservationId = fem.epsObservationId,
                multipleObservationId = fem.multipleObservationId,
            ),
        )
    }

    fun deriveFemInput(
        observations: List<EvidenceObservationV2>,
        fem: FemSection,
        issuerId: String,
        securityId: String,
    ): ForwardEarningsMultiple.Input {
        validateHorizonFields(fem)
        if (fem.epsObservationId.trim().isEmpty()) error("missing_eps_observation_id")
        if (fem.multipleObservationId.trim().isEmpty()) error("missing_multiple_observation_id")
        if (fem.epsObservationId == fem.multipleObservationId) {
            error("eps_and_multiple_observation_must_differ")
        }
        var eps = observations.firstOrNull { it.id == fem.epsObservationId }
            ?: error("eps_observation_not_in_set:${fem.epsObservationId}")
        var mult = observations.firstOrNull { it.id == fem.multipleObservationId }
            ?: error("multiple_observation_not_in_set:${fem.multipleObservationId}")
        if (eps.unit != EvidenceUnitV2.MoneyCents) error("eps_observation_unit_mismatch")
        var epsCents = eps.valueCents ?: error("eps_observation_missing_value_cents")
        if (mult.unit != EvidenceUnitV2.MultipleHundredths) error("multiple_observation_unit_mismatch")
        var multipleMillis = mult.valueMillis ?: error("multiple_observation_missing_value_millis")
        if (multipleMillis < Int.MIN_VALUE || multipleMillis > Int.MAX_VALUE) {
            error("multiple_observation_overflow")
        }
        var currency = eps.currency?.takeIf { it.isNotBlank() }
            ?: error("eps_observation_missing_currency")
        mult.currency?.takeIf { it.isNotBlank() }?.let {
            if (it != currency) error("currency_mismatch_between_eps_and_multiple")
        }
        var provenance = when (fem.multipleProvenance) {
            "analyst_stated" -> ForwardEarningsMultiple.MultipleProvenance.AnalystStated
            "peer_policy_derived" -> ForwardEarningsMultiple.MultipleProvenance.PeerPolicyDerived
            else -> error("invalid_multiple_provenance:${fem.multipleProvenance}")
        }
        if (provenance == ForwardEarningsMultiple.MultipleProvenance.AnalystStated) {
            val epsOk = eps.metricId in setOf("gaap_diluted_eps", "diluted_eps", "normalized_diluted_eps")
            if (!epsOk) error("eps_metric_not_earnings:${eps.metricId}")
            if (eps.evidenceLane != EvidenceLane.AnalystStatedMethod) {
                error("eps_lane_not_analyst_stated_method")
            }
            val peOk = mult.metricId in setOf("forward_pe", "pe_forward", "forward_pe_multiple")
            if (!peOk) error("multiple_metric_not_forward_pe:${mult.metricId}")
            if (mult.evidenceLane != EvidenceLane.AnalystStatedMethod) {
                error("multiple_lane_not_analyst_stated_method")
            }
            if (mult.lineageGroupId != eps.lineageGroupId) error("lineage_mismatch_eps_multiple")
            if (mult.metricBasis != eps.metricBasis) error("metric_basis_mismatch_eps_multiple")
        }
        val epsPeriodStart = parseIsoDate(eps.economicPeriodStart, "eps_period_start")
        val epsPeriodEnd = parseIsoDate(eps.economicPeriodEnd, "eps_period_end")
        if (epsPeriodStart.isAfter(epsPeriodEnd)) error("economic_period_start_after_end")
        val multiplePeriodStart = parseIsoDate(mult.economicPeriodStart, "multiple_period_start")
        val multiplePeriodEnd = parseIsoDate(mult.economicPeriodEnd, "multiple_period_end")
        if (multiplePeriodStart.isAfter(multiplePeriodEnd)) error("economic_period_start_after_end")
        if (eps.economicPeriodEnd != fem.forecastPeriodEnd) {
            error("eps_period_mismatch_forecast")
        }
        if (mult.economicPeriodEnd != eps.economicPeriodEnd ||
            mult.economicPeriodStart != eps.economicPeriodStart
        ) {
            error("period_mismatch_eps_multiple")
        }
        var observedAt = maxOf(eps.sourceAvailableAtUnixMs, mult.sourceAvailableAtUnixMs)
        return ForwardEarningsMultiple.Input(
            issuerId = issuerId,
            securityId = securityId,
            metricId = eps.metricId,
            metricBasis = eps.metricBasis.snake,
            epsCents = epsCents,
            multipleHundredths = multipleMillis.toInt(),
            multipleProvenance = provenance,
            forecastPeriodEnd = fem.forecastPeriodEnd,
            targetAsOf = fem.targetAsOf,
            datePrecision = fem.datePrecision,
            currency = currency,
            evidenceObservedAtUnixMs = observedAt,
            marketPriceCents = fem.marketPriceCents,
            statedTargetCents = fem.statedTargetCents,
            peerCount = fem.peerCount,
        )
    }

    private fun validateHorizonFields(fem: FemSection) {
        val forecastPeriodEnd = parseIsoDate(fem.forecastPeriodEnd, "forecast_period_end")
        when (fem.datePrecision) {
            "month_label" -> {
                val target = parseYearMonth(fem.targetAsOf)
                if (target.isAfter(YearMonth.from(forecastPeriodEnd))) {
                    error("target_as_of_after_forecast_period_end")
                }
            }
            "exact_date", "fiscal_period" -> {
                val target = parseIsoDate(fem.targetAsOf, "target_as_of")
                if (target.isAfter(forecastPeriodEnd)) {
                    error("target_as_of_after_forecast_period_end")
                }
            }
            "provider_horizon" -> {
                if (fem.targetAsOf.trim().isEmpty()) error("empty_target_as_of")
            }
            else -> error("invalid_date_precision:${fem.datePrecision}")
        }
    }

    private fun parseIsoDate(raw: String, field: String): LocalDate {
        if (raw.length != 10 || raw[4] != '-' || raw[7] != '-') error("invalid_iso_date:$field")
        val parsed = try {
            LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE)
        } catch (_: DateTimeParseException) {
            error("invalid_iso_date:$field")
        }
        if (parsed.year < 1900) error("invalid_iso_date:$field")
        return parsed
    }

    private fun parseYearMonth(raw: String): YearMonth {
        if (raw.length != 7 || raw[4] != '-') error("invalid_target_as_of_month_label")
        val parsed = try {
            YearMonth.parse(raw, DateTimeFormatter.ofPattern("uuuu-MM"))
        } catch (_: DateTimeParseException) {
            error("invalid_target_as_of_month_label")
        }
        if (parsed.year < 1900) error("invalid_target_as_of_month_label")
        return parsed
    }

    fun admitForDecision(
        observations: List<EvidenceObservationV2>,
        mode: ReplayMode,
        decisionAtUnixMs: Long,
    ): String? {
        for (obs in observations) {
            var d = ValuationEvidenceV2.admitObservation(
                mode,
                decisionAtUnixMs,
                obs.publicationAtUnixMs,
                obs.sourceAvailableAtUnixMs,
                obs.ingestedAtUnixMs,
                obs.availabilityBasis,
                obs.providerVintageId,
            )
            if (!d.admit) {
                return "look_ahead_refused:${d.refusalCode ?: "unknown"}"
            }
            if (mode == ReplayMode.Operational && !d.liveProjectionEligible) {
                return "operational_not_live_projection_eligible"
            }
        }
        return null
    }
}
