package com.discountscreener.core.harness

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.test.Test
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * One-shot assembler for the V4 street-scoreboard fixtures.
 *
 * Never runs in CI: it is gated behind `-Dv4.capture=live` because most of its work is copying a
 * cached capture and the rest is network.
 *
 * Sources, in order:
 *
 * 1. the wave-1b capture under `.agents/workspace/tmp` — twenty symbols pulled from Yahoo in one
 *    session, quote summary plus ten annual series each;
 * 2. a live fetch through [HttpYahooTransport] for anything the cache lacks, which is how the
 *    sector gaps were filled (banks C and L beyond BAC/JPM/V/ACGL, autos F, and the sectors the
 *    cache has none of: industrials, materials, real estate, utilities).
 *
 * Everything lands in `core/src/test/resources/yahoo-scoreboard/`, so the scoreboard runs offline
 * forever after and every name in a study shares one capture vintage per source session.
 */
class V4ScoreboardFixtureCaptureTest {

    @Test
    fun assemble_the_scoreboard_fixtures() {
        assumeTrue(System.getProperty("v4.capture") == "live", "run with -Dv4.capture=live to (re)build fixtures")

        var transport = HttpYahooTransport()
        for (symbol in SYMBOLS) {
            var wire = wire(symbol)
            var quoteTarget = QUOTES.resolve("$wire.json")
            var tsTarget = TIMESERIES.resolve("$wire.json")
            if (Files.exists(quoteTarget) && Files.exists(tsTarget)) continue

            var cachedQuote = WAVE1B.resolve("yahoo").resolve("$symbol-quote.json")
            if (Files.exists(cachedQuote)) {
                Files.createDirectories(quoteTarget.parent)
                Files.createDirectories(tsTarget.parent)
                Files.copy(cachedQuote, quoteTarget, StandardCopyOption.REPLACE_EXISTING)
                var cachedTs = WAVE1B.resolve("yahoo").resolve("$symbol-ts.json")
                if (Files.exists(cachedTs)) {
                    Files.copy(cachedTs, tsTarget, StandardCopyOption.REPLACE_EXISTING)
                    println("$symbol <- cache")
                    continue
                }
            }

            Files.createDirectories(quoteTarget.parent)
            Files.createDirectories(tsTarget.parent)
            Files.writeString(quoteTarget, transport.quoteSummary(symbol))
            Files.writeString(tsTarget, transport.timeseries(symbol))
            println("$symbol <- live")
        }
    }

    private fun wire(symbol: String) = symbol.trim().uppercase().replace('.', '-')

    companion object {
        private val SYMBOLS = V4_SCOREBOARD_SYMBOLS

        private val RESOURCES: Path = Path.of("src/test/resources")
        private val QUOTES = RESOURCES.resolve("yahoo-scoreboard/quoteSummary")
        private val TIMESERIES = RESOURCES.resolve("yahoo-scoreboard/timeseries")

        private val WAVE1B: Path = Path.of(
            "G:/dev/repos/discount_screener/.agents/workspace/tmp/e2e/thinkable-identity-qa/build/wave-1b",
        )
    }
}
