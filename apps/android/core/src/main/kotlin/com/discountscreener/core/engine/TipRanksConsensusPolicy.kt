package com.discountscreener.core.engine

import com.discountscreener.core.model.TipRanksConsensus
import com.discountscreener.core.model.TipRanksForecast
import com.discountscreener.core.model.TipRanksObservation
import com.discountscreener.core.model.ValuationAvailability
import com.discountscreener.core.model.ValuationConfidence
import com.discountscreener.core.model.ValuationCoverage
import com.discountscreener.core.model.ValuationFreshness
import java.math.BigInteger

/** Deterministic consensus eligibility; provider parsing and all network policy stay outside this object. */
object TipRanksConsensusPolicy {
    const val CACHE_FRESH_SECONDS = 86_400L
    const val CACHE_AGING_SECONDS = 604_800L
    const val OPINION_FRESH_SECONDS = 2_592_000L
    const val OPINION_AGING_SECONDS = 7_776_000L
    const val MIN_DECISION_IDENTITIES = 3

    fun cacheFreshness(ageSeconds: Long?): ValuationFreshness = when {
        ageSeconds == null || ageSeconds < 0L -> ValuationFreshness.Unknown
        ageSeconds <= CACHE_FRESH_SECONDS -> ValuationFreshness.Fresh
        ageSeconds <= CACHE_AGING_SECONDS -> ValuationFreshness.Aging
        else -> ValuationFreshness.Stale
    }

    fun opinionFreshness(ageSeconds: Long?): ValuationFreshness = when {
        ageSeconds == null || ageSeconds < 0L -> ValuationFreshness.Unknown
        ageSeconds <= OPINION_FRESH_SECONDS -> ValuationFreshness.Fresh
        ageSeconds <= OPINION_AGING_SECONDS -> ValuationFreshness.Aging
        else -> ValuationFreshness.Stale
    }

    fun derive(forecast: TipRanksForecast, nowEpochSeconds: Long): TipRanksConsensus {
        val cacheFreshness = cacheFreshness(nowEpochSeconds - forecast.fetchedAtEpochSeconds)
        val visible = forecast.observations.filter { it.targetMinorUnits != null && it.targetMinorUnits > 0L }
        val simple = mean(visible.mapNotNull { it.targetMinorUnits })
        if (simple == null) {
            return TipRanksConsensus(cacheFreshness = cacheFreshness, reasons = listOf("no_positive_observations"))
        }

        val eligibleByIdentity = visible
            .filter { observation ->
                observation.analystIdentity?.isNotBlank() == true &&
                    observation.observedAtEpochSeconds != null &&
                    opinionFreshness(nowEpochSeconds - observation.observedAtEpochSeconds) != ValuationFreshness.Stale
            }
            .groupBy { requireNotNull(it.analystIdentity) }
            .mapValues { (_, observations) -> observations.maxBy { requireNotNull(it.observedAtEpochSeconds) } }
            .values
            .sortedBy { requireNotNull(it.analystIdentity) }
        val eligibleCount = eligibleByIdentity.size
        val cacheUsable = cacheFreshness == ValuationFreshness.Fresh || cacheFreshness == ValuationFreshness.Aging
        val decisionEligible = cacheUsable && eligibleCount >= MIN_DECISION_IDENTITIES
        val weighted = weightedMean(eligibleByIdentity)
        val useWeighted = decisionEligible && weighted != null
        return TipRanksConsensus(
            simpleTargetMinorUnits = simple,
            weightedTargetMinorUnits = if (useWeighted) weighted else null,
            visibleObservationCount = visible.size,
            eligibleIdentityCount = eligibleCount,
            cacheFreshness = cacheFreshness,
            availability = if (decisionEligible) ValuationAvailability.Available else ValuationAvailability.ReferenceOnly,
            coverage = when {
                eligibleCount >= MIN_DECISION_IDENTITIES -> ValuationCoverage.Sufficient
                eligibleCount > 0 || visible.isNotEmpty() -> ValuationCoverage.Sparse
                else -> ValuationCoverage.Unknown
            },
            confidence = when {
                !decisionEligible -> ValuationConfidence.Soft
                useWeighted -> ValuationConfidence.Solid
                else -> ValuationConfidence.Soft
            },
            reasons = buildList {
                if (!cacheUsable) add("cache_stale")
                if (eligibleCount < MIN_DECISION_IDENTITIES) add("fewer_than_three_eligible_identities")
                if (decisionEligible && weighted == null) add("weighted_fallback_simple")
            },
        )
    }

    private fun mean(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        return values.map(BigInteger::valueOf).reduce(BigInteger::add)
            .divide(BigInteger.valueOf(values.size.toLong())).longValueExact()
    }

    private fun weightedMean(observations: List<TipRanksObservation>): Long? {
        if (observations.isEmpty() || observations.any { it.weightMillis == null || it.weightMillis <= 0 }) return null
        val totalWeight = observations.fold(BigInteger.ZERO) { sum, item -> sum + BigInteger.valueOf(requireNotNull(item.weightMillis).toLong()) }
        if (totalWeight == BigInteger.ZERO) return null
        val weightedTotal = observations.fold(BigInteger.ZERO) { sum, item ->
            sum + BigInteger.valueOf(requireNotNull(item.targetMinorUnits)) * BigInteger.valueOf(requireNotNull(item.weightMillis).toLong())
        }
        return weightedTotal.add(totalWeight / BigInteger.TWO).divide(totalWeight).longValueExact()
    }
}
