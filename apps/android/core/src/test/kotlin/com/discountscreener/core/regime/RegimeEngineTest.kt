package com.discountscreener.core.regime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The seam between `:app`'s fetching and `:core`'s arithmetic. The pillars are tested elsewhere;
 * what is on trial here is the assembly — which inputs reach which pillar, and what the reading
 * says when they do not arrive.
 *
 * The refusal is the property that matters, because it is the one a cold start and an offline
 * launch both hit: with nothing fetched, the engine must produce a reading no scoring policy will
 * accept. But a function that refused unconditionally would pass every refusal test here, so
 * [a_complete_reading_is_confident_enough_to_score] runs first among equals — it is what stops the
 * rest of this file from being vacuous.
 */
class RegimeEngineTest {
    @Test
    fun a_complete_reading_is_confident_enough_to_score() {
        assertNotNull(RegimeScoringPolicy.fromRegime(computeMarketRegime(fullBundle(), fullUniverse())))
    }

    /** Cold start and airplane mode land here: an explicit refusal, not a neutral-looking guess. */
    @Test
    fun an_empty_reading_is_refused_by_the_scoring_policy() {
        assertNull(RegimeScoringPolicy.fromRegime(computeMarketRegime(MarketDataBundle(), emptyList())))
    }

    /**
     * The refusal above says *that* the reading is unusable; these say *why*, one line per input
     * that never arrived. Asserting the unmatched fragments rather than a boolean means a failure
     * names the warning that went missing instead of only reporting that one did.
     */
    @Test
    fun an_empty_reading_names_every_input_it_is_missing() {
        val warnings = computeMarketRegime(MarketDataBundle(), emptyList()).warnings
        assertEquals(
            emptyList(),
            MISSING_INPUT_WARNINGS.filterNot { fragment -> warnings.any { fragment in it } },
        )
    }

    /**
     * A universe smaller than the S&P is still scored — it is only flagged. The warning exists
     * because breadth over forty names is a different measurement from breadth over five hundred,
     * not because it is unusable.
     */
    @Test
    fun a_partial_universe_is_flagged_rather_than_refused() {
        assertTrue(
            computeMarketRegime(fullBundle(), fullUniverse().take(40)).warnings.any { "partial universe" in it },
        )
    }

    /**
     * SPY's 200-day average must come from [regimeEmaCents] over the closes, never off the summary
     * `:app` hands in — the two disagree by enough to flip SPY's own "above the 200-day" verdict,
     * which the trend and quality pillars each read as a single boolean.
     *
     * The fixture's summary carries an average far from anything the closes support, so a seam that
     * passed the summary through would report that number instead. Asserting the derived value
     * rather than merely "not the summary's" is what makes this fail on a wrong derivation too.
     */
    @Test
    fun spy_moving_averages_are_derived_from_closes_not_read_off_the_summary() {
        val closes = spyCloses()
        assertEquals(
            regimeEmaCents(closes, 200),
            computeMarketRegime(fullBundle().copy(spySummary = summary(bullish = true)), fullUniverse()).spyMa200Cents,
        )
    }

    /** Under two hundred bars Rust's average refuses, and so must this one. */
    @Test
    fun a_short_spy_history_yields_no_two_hundred_day_average() {
        assertNull(
            computeMarketRegime(fullBundle().copy(spyCloses = spyCloses().take(150)), fullUniverse()).spyMa200Cents,
        )
    }

    /**
     * With no summary at all the engine still builds one from closes, so the trend pillar reads
     * rather than reporting itself missing. This is Rust's `summary_from_closes` fallback, and the
     * warning is how the substitution stays visible instead of passing for a real chart.
     */
    @Test
    fun a_missing_spy_summary_is_synthesized_from_closes() {
        assertTrue(
            computeMarketRegime(fullBundle().copy(spySummary = null), fullUniverse())
                .warnings.any { "synthesized from closes" in it },
        )
    }

    /**
     * Correlation asks how tightly *stocks* move together. An index ETF correlates with the market
     * by construction, so letting one into the sample would answer the question with its own
     * premise.
     */
    @Test
    fun the_correlation_sample_ignores_etfs_and_crypto() {
        assertNull(computeMarketRegime(fullBundle(), etfAndCryptoOnly()).avgCorrMilli)
    }

    @Test
    fun a_stock_universe_produces_a_correlation_reading() {
        assertNotNull(computeMarketRegime(fullBundle(), fullUniverse()).avgCorrMilli)
    }

    /**
     * The VIX percentile is a percentile *of its own year*, so fetching that series over three
     * months would answer a different question under the same field name — the one range in
     * [MARKET_SERIES] that is not interchangeable with its neighbours.
     */
    @Test
    fun the_vix_series_is_requested_over_a_year_not_a_quarter() {
        assertEquals("1y", MARKET_SERIES.single { it.symbol == VIX_SYMBOL }.yahooRange)
    }

    /**
     * SPY reaches the cross-asset pillar as [MarketDataBundle.spyCloses], so listing it as a series
     * to fetch would buy a second copy of a year of dailies for nothing.
     */
    @Test
    fun spy_is_not_fetched_twice() {
        assertEquals(emptyList(), MARKET_SERIES.filter { it.symbol == "SPY" })
    }

    /** The volatility axis is the inverted one: stress plots toward the centre, calm at the edge. */
    @Test
    fun the_volatility_axis_plots_inverted_against_every_other() {
        assertEquals(
            listOf(25, 75),
            listOf(radarRadius("volatility", 50), radarRadius("trend", 50)),
        )
    }

    /**
     * Sentiment is read contrarian, so fear is an opportunity and greed a caution — it never speaks
     * in the bullish/bearish register the other pillars use. Both ends are asserted together
     * because a tone table that returned "opportunity" for everything would satisfy either alone.
     */
    @Test
    fun sentiment_speaks_in_the_contrarian_register() {
        assertEquals(
            listOf("opportunity", "caution"),
            listOf(pillarTone("sentiment", 60), pillarTone("sentiment", -60)),
        )
    }

    /** Stress is hostile, so a high volatility score is the bearish one. */
    @Test
    fun a_high_volatility_score_reads_bearish_not_bullish() {
        assertEquals("bearish", pillarTone("volatility", 60))
    }

    /** A thinning tape warns about a rally before it calls against one. */
    @Test
    fun breadth_cautions_one_band_before_it_turns_bearish() {
        assertEquals(
            listOf("caution", "bearish"),
            listOf(pillarTone("breadth", -30), pillarTone("breadth", -60)),
        )
    }

    /**
     * The prose layer is not ported, and the reading says so by leaving it empty rather than
     * filling it with something that reads like a conclusion. If an Android surface ever renders
     * it, `interpret.rs` is the file to port — this test is what will notice.
     */
    @Test
    fun no_pillar_claims_an_interpretation_the_port_does_not_have() {
        assertEquals(
            emptyList(),
            computeMarketRegime(fullBundle(), fullUniverse()).pillars.filter { it.interpretation.isNotEmpty() },
        )
    }

    /**
     * Notes are a digest, not a dump: the two leading signals of each pillar, and only those that
     * carry a detail worth reading. The fixture gives one pillar three detailed signals and one
     * undetailed signal ahead of a detailed one, so both halves of that rule have to hold —
     * asserting the exact list means a cap of three, or a silent reordering, fails here.
     */
    @Test
    fun notes_take_the_two_leading_detailed_signals_of_each_pillar() {
        assertEquals(
            listOf("A: first", "B: second", "D: fourth"),
            notesFrom(
                listOf(
                    pillarWith(signal("A", "first"), signal("B", "second"), signal("C", "third")),
                    pillarWith(signal("nothing to add", detail = null), signal("D", "fourth")),
                ),
            ),
        )
    }

    @Test
    fun the_reading_is_stamped_with_the_time_its_data_was_captured() {
        assertEquals(
            1_700_000_000L,
            computeMarketRegime(fullBundle().copy(asOfEpochSeconds = 1_700_000_000L), fullUniverse()).asOfEpoch,
        )
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    /** A year of SPY dailies drifting up, long enough for every average and the sixty-day slope. */
    private fun spyCloses() = (0 until 260).map { 400.0 + (it * 0.5) }

    /** Every series [MARKET_SERIES] asks for, so the fixture fetches exactly what `:app` will. */
    private fun fullBundle() = MarketDataBundle(
        spyCloses = spyCloses(),
        spySummary = summary(bullish = true).copy(latestCloseCents = 52_950L),
        closesBySymbol = MARKET_SERIES.associate { request ->
            request.symbol to when (request.symbol) {
                VIX_SYMBOL -> decliningVix()
                VIX3M_SYMBOL -> (0 until 60).map { 20.0 }
                else -> rising()
            }
        },
        cnnFearGreed = fearGreed(55.0),
    )

    private fun fullUniverse() = (0 until 90).map { index ->
        SymbolDailyView(
            symbol = "SYM$index.BA",
            summary = summary(bullish = index % 3 != 0),
            closes = (0 until 80).map { bar -> 100.0 + (bar * 0.4) + ((bar + index) % 7) },
        )
    }

    private fun etfAndCryptoOnly() = listOf("SPY", "QQQ", "IWM", "BTC-USD", "ETH-USD").map { symbol ->
        SymbolDailyView(symbol, summary(bullish = true), (0 until 80).map { 100.0 + it })
    }

    private fun signal(label: String, detail: String?) =
        RegimeSignal(id = label, label = label, contribution = 0, detail = detail)

    private fun pillarWith(vararg signals: RegimeSignal) = RegimePillar(
        id = "fixture",
        name = "Fixture",
        score = 0,
        confidenceBps = 0,
        weightUsedBps = 0,
        signals = signals.toList(),
        stale = false,
        interpretation = "",
        tone = "neutral",
        radarRadius = 50,
    )

    private companion object {
        /** One fragment per input the engine has to say it never received. */
        val MISSING_INPUT_WARNINGS = listOf(
            "breadth sample",
            "VIX unavailable",
            "SPY trend unavailable",
            "Fear & Greed unavailable",
            "cross-asset data sparse",
            "global confidence low",
        )
    }
}
