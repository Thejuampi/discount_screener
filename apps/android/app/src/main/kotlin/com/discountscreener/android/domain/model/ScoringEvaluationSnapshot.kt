package com.discountscreener.android.domain.model

import com.discountscreener.core.engine.SectorBenchmarks
import com.discountscreener.core.model.ChartRangeSummary
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.ExternalSignalStatus
import com.discountscreener.core.model.FundamentalTimeseries
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.QualificationStatus
import com.discountscreener.core.model.ScoreFactor
import com.discountscreener.core.model.SymbolDetail
import com.discountscreener.core.regime.MarketContextUnavailableReason
import com.discountscreener.core.regime.MarketRegime
import com.discountscreener.core.regime.RegimeCause
import com.discountscreener.core.regime.RegimeScoreStatus
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One immutable scoring cohort. It links inputs, ranks, formula identities, and unavailable rows. */
@Serializable
data class ScoringEvaluationSnapshot(
    val schemaVersion: Int,
    val snapshotId: String,
    val capturedAtEpochSeconds: Long,
    val observationDateUtc: String,
    val profileName: String,
    val inputFingerprint: String,
    val policyVersion: String,
    val regimeScoringEnabled: Boolean,
    val inputs: ScoringEvaluationInputs,
    val modelResults: List<ScoringEvaluationModelResult>,
) {
    companion object {
        const val CURRENT_SCHEMA_VERSION = 1

        fun create(
            capturedAtEpochSeconds: Long,
            profileName: String,
            policyVersion: String,
            regimeScoringEnabled: Boolean,
            inputs: ScoringEvaluationInputs,
            modelResults: List<ScoringEvaluationModelResult>,
        ): ScoringEvaluationSnapshot {
            val canonicalInputs = inputs.canonical()
            val fingerprint = sha256(
                EVALUATION_JSON.encodeToString(
                    ScoringEvaluationFingerprintBasis(
                        policyVersion = policyVersion,
                        regimeScoringEnabled = regimeScoringEnabled,
                        inputs = canonicalInputs,
                    ),
                ),
            )
            val canonicalResults = modelResults
                .sortedBy { result -> result.model.ordinal }
                .map { result ->
                    result.copy(rows = result.rows.sortedWith(compareBy({ it.universeRank }, { it.symbol })))
                }
            return ScoringEvaluationSnapshot(
                schemaVersion = CURRENT_SCHEMA_VERSION,
                snapshotId = "$capturedAtEpochSeconds-$profileName-${fingerprint.take(16)}",
                capturedAtEpochSeconds = capturedAtEpochSeconds,
                observationDateUtc = Instant.ofEpochSecond(capturedAtEpochSeconds)
                    .atZone(ZoneOffset.UTC)
                    .toLocalDate()
                    .toString(),
                profileName = profileName,
                inputFingerprint = fingerprint,
                policyVersion = policyVersion,
                regimeScoringEnabled = regimeScoringEnabled,
                inputs = canonicalInputs,
                modelResults = canonicalResults,
            )
        }
    }
}

@Serializable
private data class ScoringEvaluationFingerprintBasis(
    val policyVersion: String,
    val regimeScoringEnabled: Boolean,
    val inputs: ScoringEvaluationInputs,
)

@Serializable
data class ScoringEvaluationInputs(
    val universe: List<ScoringEvaluationSymbolInput> = emptyList(),
    val sectorBenchmarks: Map<String, SectorBenchmarks> = emptyMap(),
    val marketRegime: MarketRegime? = null,
    val marketReadAttempted: Boolean = false,
) {
    internal fun canonical(): ScoringEvaluationInputs = copy(
        universe = universe.sortedWith(compareBy({ it.profilePosition }, { it.symbol })),
        sectorBenchmarks = sectorBenchmarks.toSortedMap(),
    )
}

/** Exact normalized values available to every formula in one scoring pass. */
@Serializable
data class ScoringEvaluationSymbolInput(
    val profilePosition: Int,
    val symbol: String,
    val detail: SymbolDetail? = null,
    val weeklySummary: ChartRangeSummary? = null,
    val dailyRegimeSummary: ChartRangeSummary? = null,
    val dcfAnalysis: DcfAnalysis? = null,
    val fundamentalTimeseries: FundamentalTimeseries? = null,
    val freshnessAsOfEpochSeconds: Long? = null,
    val stale: Boolean = false,
    val refreshed: Boolean = false,
    val providerIssue: String? = null,
    val unavailableReason: String? = null,
)

@Serializable
data class ScoringEvaluationModelResult(
    val model: OpportunityScoringModel,
    val formulaVersion: String,
    val rows: List<ScoringEvaluationScore>,
)

/** Exact score and rank emitted for one company. Ranks are one-based. */
@Serializable
data class ScoringEvaluationScore(
    val symbol: String,
    val universeRank: Int,
    val visibleRank: Int? = null,
    val marketPriceCents: Long,
    val intrinsicValueCents: Long? = null,
    val analystTargetCents: Long? = null,
    val gapBps: Int? = null,
    val upsideBps: Int? = null,
    val confidence: ConfidenceBand? = null,
    val qualification: QualificationStatus? = null,
    val externalStatus: ExternalSignalStatus? = null,
    val analystCoverageCount: Int? = null,
    val fundamentalsScore: Int? = null,
    val technicalScore: Int? = null,
    val forecastScore: Int? = null,
    val regimeScore: Int? = null,
    val compositeScore: Int,
    val compositeScoreBase: Int,
    val coverageCount: Int,
    val fundamentalsSignals: List<String> = emptyList(),
    val technicalSignals: List<String> = emptyList(),
    val forecastSignals: List<String> = emptyList(),
    val fundamentalsFactors: List<ScoreFactor> = emptyList(),
    val technicalFactors: List<ScoreFactor> = emptyList(),
    val forecastFactors: List<ScoreFactor> = emptyList(),
    val regimeStatus: RegimeScoreStatus = RegimeScoreStatus.NotApplicable,
    val regimeCauses: List<RegimeCause> = emptyList(),
    val regimeSignals: List<String> = emptyList(),
    val regimeUnavailableReason: MarketContextUnavailableReason? = null,
)

private val EVALUATION_JSON = Json {
    encodeDefaults = true
    explicitNulls = true
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
