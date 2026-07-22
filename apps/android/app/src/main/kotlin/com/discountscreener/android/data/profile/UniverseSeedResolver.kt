package com.discountscreener.android.data.profile

import com.discountscreener.android.data.remote.NasdaqTraderSymbolDirectoryClient
import java.io.IOException

data class UniverseSeedResult(
    val symbols: List<String>,
    val source: UniverseSeedSource,
    val detail: String,
)

enum class UniverseSeedSource {
    RemoteNasdaqTrader,
    BundledAsset,
}

/**
 * Resolves Discovery seed symbols: prefer live NASDAQ Trader directory for
 * [UniverseCatalog.DEFAULT_UNIVERSE], fall back to bundled asset.
 */
class UniverseSeedResolver(
    private val universeCatalog: UniverseCatalog,
    private val remoteDirectoryClient: NasdaqTraderSymbolDirectoryClient? = null,
) {
    suspend fun resolve(universeName: String): UniverseSeedResult {
        val normalized = normalizeUniverseName(universeName)
        if (normalized == UniverseCatalog.DEFAULT_UNIVERSE && remoteDirectoryClient != null) {
            try {
                val remote = remoteDirectoryClient.fetchUsEquitySymbols()
                if (remote.isNotEmpty()) {
                    return UniverseSeedResult(
                        symbols = remote,
                        source = UniverseSeedSource.RemoteNasdaqTrader,
                        detail = "nasdaqtrader.com SymDir (${remote.size} equities, non-ETF)",
                    )
                }
            } catch (_: IOException) {
                // Fall through to bundled asset.
            } catch (_: IllegalArgumentException) {
                // Unexpected parse shape — use bundled seed.
            }
        }
        val bundled = universeCatalog.loadUniverse(universeName)
        return UniverseSeedResult(
            symbols = bundled,
            source = UniverseSeedSource.BundledAsset,
            detail = "bundled assets/universes/$normalized.txt (${bundled.size} symbols)",
        )
    }

    private fun normalizeUniverseName(value: String): String =
        value.lowercase().filter { it.isLetterOrDigit() || it == '_' }
}
