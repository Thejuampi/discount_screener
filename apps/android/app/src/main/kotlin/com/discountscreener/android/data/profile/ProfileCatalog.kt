package com.discountscreener.android.data.profile

import android.content.res.AssetManager
import java.util.Locale

class ProfileCatalog(private val assets: AssetManager) {
    fun availableProfiles(): List<String> =
        assets.list("profiles")
            ?.mapNotNull { file -> file.substringBeforeLast('.').takeIf(String::isNotBlank) }
            ?.sorted()
            ?.ifEmpty { FALLBACK_PROFILES.keys.toList().sorted() }
            ?: FALLBACK_PROFILES.keys.toList().sorted()

    fun loadProfile(name: String): List<String> {
        val normalized = normalize(name)
        val fileName = "${normalized}.txt"
        val assetNames = assets.list("profiles").orEmpty().toSet()
        if (fileName in assetNames) {
            return assets.open("profiles/$fileName").bufferedReader().useLines { lines ->
                lines.map(String::trim).filter(String::isNotBlank).toList()
            }
        }
        return FALLBACK_PROFILES[normalized].orEmpty()
    }

    fun searchTickers(query: String, currentProfile: String, limit: Int = 8): List<ProfileTickerSuggestion> {
        val trimmedQuery = query.trim().uppercase(Locale.US)
        if (trimmedQuery.isBlank()) return emptyList()
        val normalizedCurrentProfile = normalize(currentProfile)
        return tickerUniverse()
            .asSequence()
            .map { (symbol, profiles) ->
                val inCurrentProfile = normalizedCurrentProfile in profiles
                val matchRank = matchRank(symbol, trimmedQuery, inCurrentProfile) ?: return@map null
                ProfileTickerSuggestion(
                    symbol = symbol,
                    profiles = profiles.sorted(),
                    inCurrentProfile = inCurrentProfile,
                    matchRank = matchRank,
                )
            }
            .filterNotNull()
            .sortedWith(
                compareBy<ProfileTickerSuggestion> { it.matchRank }
                    .thenByDescending { it.inCurrentProfile }
                    .thenBy { it.symbol.length }
                    .thenBy { it.symbol },
            )
            .take(limit)
            .toList()
    }

    fun profileMembership(symbol: String): List<String> =
        tickerUniverse()[symbol.uppercase(Locale.US)].orEmpty().sorted()

    private fun tickerUniverse(): Map<String, Set<String>> = buildMap {
        availableProfiles().forEach { profile ->
            loadProfile(profile).forEach { symbol ->
                put(symbol.uppercase(Locale.US), get(symbol.uppercase(Locale.US)).orEmpty() + normalize(profile))
            }
        }
    }

    private fun matchRank(symbol: String, query: String, inCurrentProfile: Boolean): Int? = when {
        symbol == query -> if (inCurrentProfile) 0 else 3
        symbol.startsWith(query) -> if (inCurrentProfile) 1 else 4
        query in symbol -> if (inCurrentProfile) 2 else 5
        else -> null
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.US).filter(Char::isLetterOrDigit)

    companion object {
        private val FALLBACK_PROFILES = mapOf(
            "dow" to listOf("AAPL", "AMZN", "AXP", "BA", "CAT", "CRM", "CSCO", "DIS", "GS", "HD", "HON", "IBM", "JNJ", "JPM", "KO", "MCD", "MMM", "MRK", "MSFT", "NKE", "NVDA", "PG", "SHW", "TRV", "UNH", "V", "VZ", "WMT"),
        )
    }
}

data class ProfileTickerSuggestion(
    val symbol: String,
    val profiles: List<String>,
    val inCurrentProfile: Boolean,
    val matchRank: Int,
)
