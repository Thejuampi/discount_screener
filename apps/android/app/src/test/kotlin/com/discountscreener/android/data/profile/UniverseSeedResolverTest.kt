package com.discountscreener.android.data.profile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.discountscreener.android.data.remote.NasdaqTraderSymbolDirectoryClient
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UniverseSeedResolverTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun us_total_uses_remote_when_available() = runTest {
        val resolver = UniverseSeedResolver(
            universeCatalog = UniverseCatalog(context.assets),
            remoteDirectoryClient = object : NasdaqTraderSymbolDirectoryClient() {
                override suspend fun fetchUsEquitySymbols(): List<String> = listOf("ZZZZ", "AAPL")
            },
        )

        val result = resolver.resolve("us_total")
        assertEquals(UniverseSeedSource.RemoteNasdaqTrader, result.source)
        assertEquals(listOf("ZZZZ", "AAPL"), result.symbols)
    }

    @Test
    fun us_total_falls_back_to_bundled_asset_when_remote_fails() = runTest {
        val resolver = UniverseSeedResolver(
            universeCatalog = UniverseCatalog(context.assets),
            remoteDirectoryClient = object : NasdaqTraderSymbolDirectoryClient() {
                override suspend fun fetchUsEquitySymbols(): List<String> {
                    throw IOException("offline")
                }
            },
        )

        val result = resolver.resolve("us_total")
        assertEquals(UniverseSeedSource.BundledAsset, result.source)
        assertTrue(result.symbols.size > 1_000)
        assertTrue(result.symbols.contains("AAPL"))
    }
}
