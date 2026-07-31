package com.discountscreener.core.model

import kotlinx.serialization.Serializable

/** Normalized, provider-neutral TipRanks observation. Raw parsing stays in the app/data boundary. */
@Serializable
data class TipRanksObservation(
    val analystIdentity: String? = null,
    val targetMinorUnits: Long? = null,
    val observedAtEpochSeconds: Long? = null,
    /** Optional non-negative provider weight in thousandths. */
    val weightMillis: Int? = null,
    val analystName: String? = null,
    val rating: String? = null,
)

@Serializable
data class TipRanksForecast(
    val symbol: String,
    val currencyCode: String,
    val minorUnitScale: Int,
    val observations: List<TipRanksObservation>,
    val fetchedAtEpochSeconds: Long,
    val providerState: TipRanksProviderState = TipRanksProviderState.Ready,
)

@Serializable
enum class TipRanksProviderState { Ready, NoKey, QuotaExhausted, RateLimited, Unavailable, InvalidPayload }

@Serializable
data class TipRanksConsensus(
    val simpleTargetMinorUnits: Long? = null,
    val weightedTargetMinorUnits: Long? = null,
    val visibleObservationCount: Int = 0,
    val eligibleIdentityCount: Int = 0,
    val cacheFreshness: ValuationFreshness = ValuationFreshness.Unknown,
    val availability: ValuationAvailability = ValuationAvailability.Unavailable,
    val coverage: ValuationCoverage = ValuationCoverage.Unknown,
    val confidence: ValuationConfidence = ValuationConfidence.Unknown,
    val reasons: List<String> = emptyList(),
) {
    val decisionTargetMinorUnits: Long? get() = weightedTargetMinorUnits ?: simpleTargetMinorUnits
}

/** Cache-first reads never dispatch network requests; refresh is an explicit user action. */
interface TipRanksCachedReader {
    suspend fun readCached(symbol: String): TipRanksForecast?
}

interface TipRanksExplicitLoader {
    /** May dispatch exactly one forecast request after the caller confirms an explicit action. */
    suspend fun loadOrRefresh(symbol: String): Result<TipRanksForecast>
}
