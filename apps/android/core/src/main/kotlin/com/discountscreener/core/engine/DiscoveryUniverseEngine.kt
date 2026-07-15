package com.discountscreener.core.engine

import com.discountscreener.core.model.OpportunityScoringModel
import java.util.Locale

/**
 * Pure helpers for Discovery membership merge and ranked score filtering.
 * I/O and network stay in the app shell.
 */
object DiscoveryUniverseEngine {
    fun mergeMembership(
        seed: Collection<String>,
        existing: Collection<String>,
    ): DiscoveryMembershipMerge {
        val seedSet = seed.mapNotNull(::normalizeSymbol).toSet()
        val existingSet = existing.mapNotNull(::normalizeSymbol).toSet()
        return DiscoveryMembershipMerge(
            toRemove = existingSet - seedSet,
            toAdd = seedSet - existingSet,
            toKeep = existingSet intersect seedSet,
        )
    }

    fun filterAndRank(
        rows: Collection<DiscoveryScoreRow>,
        minScore: Int,
    ): List<DiscoveryScoreRow> =
        rows
            .asSequence()
            .filter { it.isQualified && it.compositeScore >= minScore }
            .sortedWith(
                compareByDescending<DiscoveryScoreRow> { it.compositeScore }
                    .thenByDescending { it.coverageCount }
                    .thenBy { it.symbol },
            )
            .toList()

    /**
     * Same Act / Watch / Avoid cutoffs as OpportunityEngine for the chosen scoring model.
     * Discovery does not have live freshness/trust context, so triage is score-only.
     */
    fun triage(
        compositeScore: Int,
        scoringModel: OpportunityScoringModel,
    ): DiscoveryTriage =
        when {
            compositeScore >= OpportunityEngine.actAtOrAboveScore(scoringModel) -> DiscoveryTriage.Act
            compositeScore < OpportunityEngine.avoidBelowScore(scoringModel) -> DiscoveryTriage.Avoid
            else -> DiscoveryTriage.Watch
        }

    fun normalizeSymbol(raw: String): String? {
        val normalized = raw.trim().uppercase(Locale.US)
        return normalized.takeIf { it.isNotEmpty() }
    }
}

enum class DiscoveryTriage {
    Act,
    Watch,
    Avoid,
}

data class DiscoveryMembershipMerge(
    val toRemove: Set<String>,
    val toAdd: Set<String>,
    val toKeep: Set<String>,
)

/**
 * Slim durable score row for Discovery (no scrape payloads).
 */
data class DiscoveryScoreRow(
    val symbol: String,
    val companyName: String?,
    val compositeScore: Int,
    val fundamentalsScore: Int?,
    val technicalScore: Int?,
    val forecastScore: Int?,
    val coverageCount: Int,
    val marketPriceCents: Long?,
    val upsideBps: Int?,
    val gapBps: Int?,
    val confidence: String?,
    val isQualified: Boolean,
    val scoringModel: String,
    val scoredAtEpochSeconds: Long,
    val lastError: String?,
)
