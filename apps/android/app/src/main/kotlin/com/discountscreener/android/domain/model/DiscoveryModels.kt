package com.discountscreener.android.domain.model

import com.discountscreener.core.engine.DiscoveryScoreRow
import com.discountscreener.core.engine.OpportunityEngine
import com.discountscreener.core.model.OpportunityScoringModel

data class DiscoveryConfig(
    val minScore: Int = OpportunityEngine.actAtOrAboveScore(DEFAULT_SCORING_MODEL),
    val scoringModel: OpportunityScoringModel = DEFAULT_SCORING_MODEL,
    val universeName: String = DEFAULT_UNIVERSE_NAME,
) {
    companion object {
        val DEFAULT_SCORING_MODEL: OpportunityScoringModel = OpportunityScoringModel.AggressiveV2
        const val DEFAULT_UNIVERSE_NAME: String = "us_total"
    }
}

enum class DiscoveryJobKind(val storageValue: String) {
    Recreate("recreate"),
    Refresh("refresh"),
    ;

    companion object {
        fun fromStorage(value: String): DiscoveryJobKind =
            entries.firstOrNull { it.storageValue == value } ?: Refresh
    }
}

enum class DiscoveryJobStatus(val storageValue: String) {
    Running("running"),
    Completed("completed"),
    Cancelled("cancelled"),
    Failed("failed"),
    ;

    companion object {
        fun fromStorage(value: String): DiscoveryJobStatus =
            entries.firstOrNull { it.storageValue == value } ?: Failed
    }
}

data class DiscoveryJobRecord(
    val jobId: Long,
    val kind: DiscoveryJobKind,
    val status: DiscoveryJobStatus,
    val startedAtEpochSeconds: Long,
    val finishedAtEpochSeconds: Long?,
    val totalSymbols: Int,
    val completedSymbols: Int,
    val errorSummary: String?,
)

data class DiscoverySnapshot(
    val config: DiscoveryConfig = DiscoveryConfig(),
    val membershipCount: Int = 0,
    val job: DiscoveryJobRecord? = null,
    val scores: List<DiscoveryScoreRow> = emptyList(),
    /** Qualified rows at or above [config.minScore] (full count, not just the page). */
    val resultCount: Int = 0,
    /** All persisted score rows (qualified or not). Used to distinguish “never scored” vs high filter. */
    val scoredSymbolCount: Int = 0,
    val lastScoredAtEpochSeconds: Long? = null,
    /** Human-readable last membership source, e.g. "Live NASDAQ". */
    val lastSourceHint: String? = null,
)

/** Parses recreate job summary fields `added=N` / `removed=N`. */
fun parseDiscoveryMembershipDelta(errorSummary: String?): Pair<Int, Int>? {
    if (errorSummary.isNullOrBlank()) return null
    var added = numberAfter(errorSummary, "added=")
    var removed = numberAfter(errorSummary, "removed=")
    if (added == null || removed == null) return null
    return added to removed
}

private fun numberAfter(text: String, marker: String): Int? {
    var start = text.indexOf(marker)
    while (start >= 0) {
        var digitsStart = start + marker.length
        var end = digitsStart
        while (end < text.length && text[end] in '0'..'9') end++
        if (end > digitsStart) return text.substring(digitsStart, end).toIntOrNull()
        start = text.indexOf(marker, start + 1)
    }
    return null
}
