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

    private fun normalize(value: String): String =
        value.lowercase(Locale.US).filter(Char::isLetterOrDigit)

    companion object {
        private val FALLBACK_PROFILES = mapOf(
            "dow" to listOf("AAPL", "AMZN", "AXP", "BA", "CAT", "CRM", "CSCO", "DIS", "GS", "HD", "HON", "IBM", "JNJ", "JPM", "KO", "MCD", "MMM", "MRK", "MSFT", "NKE", "NVDA", "PG", "SHW", "TRV", "UNH", "V", "VZ", "WMT"),
        )
    }
}
