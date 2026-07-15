package com.discountscreener.android.data.profile

import android.content.res.AssetManager
import com.discountscreener.core.engine.DiscoveryUniverseEngine
import java.util.Locale

/**
 * Loads Discovery seed universes from assets/universes/.
 * These are NOT startup profiles and must not appear in ProfileCatalog.
 */
class UniverseCatalog(private val assets: AssetManager) {
    fun availableUniverses(): List<String> =
        assets.list(UNIVERSES_DIR)
            ?.filter { file -> file.endsWith(".txt", ignoreCase = true) }
            ?.mapNotNull { file -> file.substringBeforeLast('.').takeIf(String::isNotBlank) }
            ?.map { normalize(it) }
            ?.sorted()
            .orEmpty()

    fun loadUniverse(name: String): List<String> {
        val normalized = normalize(name)
        val fileName = "$normalized.txt"
        val assetNames = assets.list(UNIVERSES_DIR)
            .orEmpty()
            .filter { it.endsWith(".txt", ignoreCase = true) }
            .toSet()
        if (fileName !in assetNames) {
            return emptyList()
        }
        return assets.open("$UNIVERSES_DIR/$fileName").bufferedReader().useLines { lines ->
            lines
                .mapNotNull { line -> DiscoveryUniverseEngine.normalizeSymbol(line) }
                .distinct()
                .toList()
        }
    }

    private fun normalize(value: String): String =
        value.lowercase(Locale.US).filter { it.isLetterOrDigit() || it == '_' }

    companion object {
        const val DEFAULT_UNIVERSE = "us_total"
        private const val UNIVERSES_DIR = "universes"
    }
}
