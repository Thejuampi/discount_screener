package com.discountscreener.core.harness

import com.discountscreener.core.engine.ResidualFromDrivers
import com.discountscreener.core.engine.ValuationJudgmentAssembler
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

class ModelPolishResidualBMeasureTest {
    @Test
    fun unh_b_drivers_residual_premium_is_material() {
        var outcome = residualFromSlim("UNH")
        var book = requireNotNull(outcome.fundamentals.bookValuePerShareCents)
        var premiumBps = ((outcome.analysis.baseIntrinsicValueCents - book) * 10_000L) / book
        assertTrue(premiumBps > 2_000L, "UNH premium over book is $premiumBps bps")
    }

    @Test
    fun jpm_b_drivers_residual_premium_is_material() {
        var outcome = residualFromSlim("JPM")
        var book = requireNotNull(outcome.fundamentals.bookValuePerShareCents)
        var premiumBps = ((outcome.analysis.baseIntrinsicValueCents - book) * 10_000L) / book
        assertTrue(premiumBps > 2_000L, "JPM B premium over book is $premiumBps bps")
    }

    @Test
    fun ci_b_drivers_residual_premium_is_material() {
        var outcome = residualFromSlim("CI")
        var book = requireNotNull(outcome.fundamentals.bookValuePerShareCents)
        var premiumBps = ((outcome.analysis.baseIntrinsicValueCents - book) * 10_000L) / book
        assertTrue(premiumBps > 2_000L, "CI B premium over book is $premiumBps bps")
    }

    @Test
    fun acgl_b_drivers_residual_premium_is_material() {
        var outcome = residualFromSlim("ACGL")
        var book = requireNotNull(outcome.fundamentals.bookValuePerShareCents)
        var premiumBps = ((outcome.analysis.baseIntrinsicValueCents - book) * 10_000L) / book
        assertTrue(premiumBps > 2_000L, "ACGL B premium over book is $premiumBps bps")
    }

    private fun residualFromSlim(symbol: String): ResidualFromDrivers.Outcome {
        var slim = WAVE1B.resolve("sec-slim").resolve("$symbol.json")
        var quote = WAVE1B.resolve("yahoo").resolve("$symbol-quote.json")
        assumeTrue(Files.exists(slim) && Files.exists(quote), "cached slim/quote missing for $symbol")
        var yahoo = parseQuoteSummary(Files.readString(quote), symbol)
        return ResidualFromDrivers.compute(
            yahoo = yahoo,
            secFactsJson = Files.readString(slim),
            secFetchAttempted = true,
            marketPriceCents = yahoo.marketCapDollars?.let { cap ->
                yahoo.sharesOutstanding?.takeIf { it > 0L }?.let { shares ->
                    ((cap.toDouble() / shares.toDouble()) * 100.0).toLong()
                }
            },
            marketParams = QuantHarnessCases.POLISH_RATES,
            instrumentId = symbol,
            shareBasis = ValuationJudgmentAssembler.SHARE_BASIS,
        )
    }

    companion object {
        private val WAVE1B: Path = Path.of(
            "G:/dev/repos/discount_screener/.agents/workspace/tmp/e2e/thinkable-identity-qa/build/wave-1b",
        )
    }
}
