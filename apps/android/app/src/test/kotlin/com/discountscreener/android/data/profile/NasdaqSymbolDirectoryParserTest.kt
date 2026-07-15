package com.discountscreener.android.data.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NasdaqSymbolDirectoryParserTest {
    @Test
    fun parse_nasdaqlisted_skips_etf_and_test_and_maps_to_yahoo() {
        val text = loadFixture("nasdaq_symdir_fixtures/nasdaqlisted_sample.txt")
        val symbols = NasdaqSymbolDirectoryParser.parseNasdaqListed(text)

        assertEquals(listOf("AAPL", "MSFT"), symbols)
        assertFalse(symbols.contains("QQQ"))
        assertFalse(symbols.contains("ZVZZT"))
    }

    @Test
    fun parse_otherlisted_converts_class_share_dots_to_yahoo_hyphens() {
        val text = loadFixture("nasdaq_symdir_fixtures/otherlisted_sample.txt")
        val symbols = NasdaqSymbolDirectoryParser.parseOtherListed(text)

        assertEquals(listOf("BRK-B", "IBM"), symbols)
        assertFalse(symbols.contains("SPY"))
        assertFalse(symbols.contains("ZXIET"))
    }

    @Test
    fun merge_dedupes_and_sorts() {
        val merged = NasdaqSymbolDirectoryParser.mergeYahooSymbols(
            listOf("MSFT", "AAPL"),
            listOf("AAPL", "BRK.B"),
        )
        assertEquals(listOf("AAPL", "BRK-B", "MSFT"), merged)
    }

    @Test
    fun toYahooSymbol_rejects_preferred_dollar_series() {
        assertEquals(null, NasdaqSymbolDirectoryParser.toYahooSymbol("BRK\$B"))
        assertEquals("AAPL", NasdaqSymbolDirectoryParser.toYahooSymbol(" aapl "))
    }

    private fun loadFixture(path: String): String =
        requireNotNull(javaClass.classLoader?.getResourceAsStream(path)) {
            "missing fixture $path"
        }.bufferedReader().use { it.readText() }
}
