package com.discountscreener.core.engine

import java.util.Locale

object TickerSearchRank {
    const val EXACT_TICKER_CURRENT = 0
    const val EXACT_TICKER_OTHER = 1
    const val EXACT_TICKER_REMOTE = 2
    const val PREFIX_TICKER_CURRENT = 3
    const val PREFIX_TICKER_OTHER = 4
    const val CONTAINS_TICKER = 5
    const val NAME_EXACT = 6
    const val NAME_WORD_START = 7
    const val NAME_CONTAINS = 8
    const val REMOTE_FUZZY = 9
}

data class TickerSearchCandidate(
    val symbol: String,
    val companyName: String? = null,
    val profiles: List<String> = emptyList(),
    val inCurrentProfile: Boolean = false,
    val exchange: String? = null,
    val matchRank: Int,
    val isRemote: Boolean = false,
)

data class TickerSearchResult(
    val symbol: String,
    val companyName: String? = null,
    val profiles: List<String> = emptyList(),
    val inCurrentProfile: Boolean = false,
    val exchange: String? = null,
    val isRemote: Boolean = false,
    val matchRank: Int,
)

object TickerSearchEngine {
    fun remapProfileMatchRank(profileRank: Int): Int = when (profileRank) {
        0 -> TickerSearchRank.EXACT_TICKER_CURRENT
        3 -> TickerSearchRank.EXACT_TICKER_OTHER
        1 -> TickerSearchRank.PREFIX_TICKER_CURRENT
        4 -> TickerSearchRank.PREFIX_TICKER_OTHER
        2, 5 -> TickerSearchRank.CONTAINS_TICKER
        else -> profileRank
    }

    fun remoteMatchRank(symbol: String, query: String): Int =
        if (symbol.equals(query.trim(), ignoreCase = true)) {
            TickerSearchRank.EXACT_TICKER_REMOTE
        } else {
            TickerSearchRank.REMOTE_FUZZY
        }

    fun companyNameMatchRank(query: String, companyName: String): Int? {
        val normalizedQuery = normalizeNameQuery(query)
        if (normalizedQuery.isBlank()) return null
        val normalizedName = normalizeNameQuery(companyName)
        if (normalizedName.isBlank()) return null
        return when {
            normalizedName == normalizedQuery -> TickerSearchRank.NAME_EXACT
            normalizedName.split(Regex("\\s+")).any { word -> word.startsWith(normalizedQuery) } ->
                TickerSearchRank.NAME_WORD_START
            normalizedQuery in normalizedName -> TickerSearchRank.NAME_CONTAINS
            else -> null
        }
    }

    fun mergeAndRank(candidates: List<TickerSearchCandidate>, limit: Int): List<TickerSearchResult> =
        candidates
            .groupBy { it.symbol.uppercase(Locale.US) }
            .values
            .mapNotNull { group -> group.minWithOrNull(candidateComparator()) }
            .sortedWith(candidateComparator())
            .take(limit)
            .map { candidate ->
                TickerSearchResult(
                    symbol = candidate.symbol,
                    companyName = candidate.companyName,
                    profiles = candidate.profiles,
                    inCurrentProfile = candidate.inCurrentProfile,
                    exchange = candidate.exchange,
                    isRemote = candidate.isRemote,
                    matchRank = candidate.matchRank,
                )
            }

    fun shouldTriggerRemoteSearch(query: String, localResults: List<TickerSearchResult>): Boolean {
        val trimmed = query.trim()
        if (trimmed.length < 2) return false
        if (trimmed.contains(Regex("\\s"))) return true
        return localResults.isEmpty()
    }

    fun isTickerToken(query: String): Boolean =
        query.trim().matches(Regex("^[A-Za-z0-9.\\-]+$"))

    fun shouldDirectOpenTickerOnSubmit(query: String, suggestionSymbols: List<String>): Boolean {
        val trimmed = query.trim()
        if (trimmed.isBlank() || !isTickerToken(trimmed)) return false
        if ('.' in trimmed || '-' in trimmed) return true

        val upper = trimmed.uppercase(Locale.US)
        val typedAsTicker = trimmed == upper && trimmed.all { it.isLetterOrDigit() }

        if (suggestionSymbols.isNotEmpty()) {
            val exactMatches = suggestionSymbols.filter { it.equals(upper, ignoreCase = true) }
            if (suggestionSymbols.size > 1) {
                return exactMatches.size == 1 && typedAsTicker
            }
            return exactMatches.size == 1
        }

        return typedAsTicker && trimmed.length <= 6
    }

    fun isHighConfidenceMatch(query: String, result: TickerSearchResult): Boolean {
        val trimmed = query.trim()
        if (result.symbol.equals(trimmed, ignoreCase = true) &&
            result.matchRank <= TickerSearchRank.EXACT_TICKER_REMOTE
        ) {
            return true
        }
        val companyName = result.companyName ?: return false
        return companyNameMatchRank(trimmed, companyName) == TickerSearchRank.NAME_EXACT
    }

    fun normalizeSearchQueryKey(query: String): String =
        query.trim().lowercase(Locale.US)

    private fun normalizeNameQuery(value: String): String =
        value.trim().lowercase(Locale.US).replace(Regex("\\s+"), " ")

    private fun candidateComparator(): Comparator<TickerSearchCandidate> =
        compareBy<TickerSearchCandidate> { it.matchRank }
            .thenBy { if (it.isRemote) 1 else 0 }
            .thenByDescending { it.inCurrentProfile }
            .thenBy { it.symbol.length }
            .thenBy { it.symbol }

}