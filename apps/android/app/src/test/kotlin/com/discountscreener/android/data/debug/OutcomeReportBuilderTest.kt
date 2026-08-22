package com.discountscreener.android.data.debug

import com.discountscreener.android.domain.model.ScoreJournalRow
import com.discountscreener.core.model.HistoricalCandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The text the outcome button writes, pinned before it exists.
 *
 * The load-bearing assertion here is the street one: the report may *show* analyst upside as
 * context, but mutating every street number must leave every spread byte-identical. A scoreboard
 * that could move the game would not be a scoreboard.
 */
class OutcomeReportBuilderTest {

    @Test
    fun two_models_journalled_on_the_same_days_get_separate_sections() {
        var text = build(rows = listOf(row(model = "AggressiveV3"), row(model = "AggressiveV4")))

        assertTrue("== AggressiveV3 ==" in text)
        assertTrue("== AggressiveV4 ==" in text)
    }

    /** V4 first: the newest model is the one the reader came for. */
    @Test
    fun the_newest_model_is_reported_first() {
        var text = build(
            rows = listOf(
                row(model = "AggressiveV2"),
                row(model = "AggressiveV4"),
                row(model = "AggressiveV3"),
            ),
        )

        val v4 = text.indexOf("== AggressiveV4 ==")
        val v3 = text.indexOf("== AggressiveV3 ==")
        val v2 = text.indexOf("== AggressiveV2 ==")
        assertTrue(v4 < v3 && v3 < v2)
    }

    /**
     * Mutating every street number changes the diagnostic line and nothing else. This is the
     * "scoreboard, not driver" pin: if a spread ever moved with the street, the optimand would be
     * compromised at the source.
     */
    @Test
    fun mutating_street_values_cannot_move_a_spread() {
        var calm = build(rows = rows(), streetUpsideBpsBySymbol = mapOf("AAPL" to 1_000))
        var euphoric = build(rows = rows(), streetUpsideBpsBySymbol = mapOf("AAPL" to 9_000))

        assertEquals(spreadLines(calm), spreadLines(euphoric))
        assertFalse("the diagnostic line should carry the street value", calm == euphoric)
        assertTrue("[DIAGNOSTIC ONLY]" in calm)
    }

    /** An empty journal is a report about nothing, printed honestly rather than crashed. */
    @Test
    fun an_empty_journal_reports_itself_as_empty() {
        var text = build(rows = emptyList())

        assertTrue("no journal rows" in text)
    }

    /** A bucket that did not report gets no section — absent is absent, not zero. */
    @Test
    fun an_absent_bucket_gets_no_section() {
        var text = build(rows = listOf(row(regime = null)))

        assertFalse("-- regime --" in text)
        assertTrue("-- composite --" in text)
    }

    /** Today's thin history must read `insufficient`, never a small-sample number dressed as signal. */
    @Test
    fun a_horizon_without_enough_observations_reads_insufficient() {
        var text = build(rows = rows(), candles = candles(symbols = listOf("AAPL"), barsPerSymbol = 3))

        assertTrue("insufficient" in text)
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private fun rows(
        model: String = "AggressiveV4",
        symbols: List<String> = listOf("AAPL", "MSFT", "F", "T", "XOM", "JPM", "PG", "CVX", "CAT", "LIN", "NEE", "AMT"),
    ) = symbols.map { symbol -> row(model = model, symbol = symbol) }

    private fun row(
        symbol: String = "AAPL",
        model: String = "AggressiveV4",
        regime: Int? = 18,
    ) = ScoreJournalRow(
        symbol = symbol,
        scoringModel = model,
        scoredAtEpochSeconds = NOW,
        fundamentalsScore = 20,
        technicalScore = 22,
        forecastScore = 19,
        regimeScore = regime,
        compositeScore = 45,
        compositeScoreBase = 40,
        marketPriceCents = 10_000L,
    )

    private fun candles(symbols: List<String>, barsPerSymbol: Int) = symbols.associateWith { symbol ->
        (1..barsPerSymbol).map { bar ->
            HistoricalCandle(
                epochSeconds = NOW + bar * DAY,
                openCents = 10_000L,
                highCents = 10_000L,
                lowCents = 10_000L,
                closeCents = 10_000L + bar,
                volume = 1_000L,
            )
        }
    }

    private fun build(
        rows: List<ScoreJournalRow> = rows(),
        candles: Map<String, List<HistoricalCandle>> = candles(
            symbols = listOf("AAPL", "MSFT", "F", "T", "XOM", "JPM", "PG", "CVX", "CAT", "LIN", "NEE", "AMT"),
            barsPerSymbol = 130,
        ),
        streetUpsideBpsBySymbol: Map<String, Int> = emptyMap(),
    ): String {
        var text = OutcomeReportBuilder.build(
            inputs = OutcomeReportBuilder.Inputs(
                profile = "qa",
                generatedAtEpochSeconds = NOW,
                rows = rows,
                candlesBySymbol = candles,
                streetUpsideBpsBySymbol = streetUpsideBpsBySymbol,
            ),
        )
        return text
    }

    private fun spreadLines(text: String) = text.lines().filter { it.contains("spread") }

    private companion object {
        const val NOW = 1_700_000_000L
        const val DAY = 86_400L
    }
}
