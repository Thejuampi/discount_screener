package com.discountscreener.android.data.profile

import com.discountscreener.core.engine.DiscoveryUniverseEngine
import java.util.Locale

/**
 * Parses NASDAQ Trader Symbol Directory pipe-delimited files
 * (nasdaqlisted.txt / otherlisted.txt).
 *
 * Source docs: https://www.nasdaqtrader.com/trader.aspx?id=symboldirdefs
 * Live HTTPS mirrors:
 * - https://www.nasdaqtrader.com/dynamic/SymDir/nasdaqlisted.txt
 * - https://www.nasdaqtrader.com/dynamic/SymDir/otherlisted.txt
 */
object NasdaqSymbolDirectoryParser {
    fun parseNasdaqListed(text: String): List<String> =
        parse(
            text = text,
            symbolColumn = "Symbol",
            etfColumn = "ETF",
            testIssueColumn = "Test Issue",
        )

    fun parseOtherListed(text: String): List<String> =
        parse(
            text = text,
            symbolColumn = "ACT Symbol",
            etfColumn = "ETF",
            testIssueColumn = "Test Issue",
        )

    fun mergeYahooSymbols(vararg lists: Collection<String>): List<String> =
        lists
            .asSequence()
            .flatten()
            .mapNotNull(::toYahooSymbol)
            .toSortedSet()
            .toList()

    /**
     * NASDAQ uses class-share dots (BRK.B); Yahoo quote symbols use hyphens (BRK-B).
     */
    fun toYahooSymbol(raw: String): String? {
        val trimmed = raw.trim().uppercase(Locale.US)
        if (trimmed.isEmpty() || '$' in trimmed) return null
        val yahooForm = trimmed.replace('.', '-')
        return DiscoveryUniverseEngine.normalizeSymbol(yahooForm)
    }

    private fun parse(
        text: String,
        symbolColumn: String,
        etfColumn: String,
        testIssueColumn: String,
    ): List<String> {
        val lines = text.lineSequence().map(String::trim).filter { it.isNotEmpty() }.toList()
        if (lines.isEmpty()) return emptyList()
        val header = lines.first().split('|').map { it.trim() }
        val symbolIndex = header.indexOf(symbolColumn)
        val etfIndex = header.indexOf(etfColumn)
        val testIndex = header.indexOf(testIssueColumn)
        if (symbolIndex < 0 || etfIndex < 0 || testIndex < 0) {
            throw IllegalArgumentException(
                "unexpected symbol directory header columns: ${header.joinToString("|")}",
            )
        }
        val out = ArrayList<String>(lines.size)
        for (index in 1 until lines.size) {
            val line = lines[index]
            if (line.startsWith("File Creation Time", ignoreCase = true)) continue
            val parts = line.split('|')
            if (parts.size <= maxOf(symbolIndex, etfIndex, testIndex)) continue
            if (parts[etfIndex].trim().equals("Y", ignoreCase = true)) continue
            if (parts[testIndex].trim().equals("Y", ignoreCase = true)) continue
            val symbol = toYahooSymbol(parts[symbolIndex]) ?: continue
            out += symbol
        }
        return out
    }
}
