package com.discountscreener.android.data.market

import com.discountscreener.android.data.remote.CnnFearGreedClient
import com.discountscreener.android.data.remote.YahooFinanceClient
import com.discountscreener.android.data.remote.offlineHttpClient
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.regime.CnnFearGreed
import com.discountscreener.core.regime.MARKET_SERIES
import com.discountscreener.core.regime.RegimeScoringPolicy
import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The repository is the only place the market read touches the network, so what is on trial is the
 * traffic it generates and what it does when that traffic fails — not the arithmetic, which
 * `:core`'s own tests cover.
 */
class MarketDataRepositoryTest {
    @Test
    fun a_cold_cache_has_no_reading_and_does_not_fetch_to_find_out() = runTest {
        var yahoo = RecordingYahooClient()

        assertEquals(null to 0, repository(yahoo).cachedRegime() to yahoo.requests.size)
    }

    /** Only a reading a policy accepts is ever handed out, so a non-null return *is* the claim. */
    @Test
    fun a_refresh_produces_a_reading_confident_enough_to_score() = runTest {
        assertNotNull(RegimeScoringPolicy.fromRegime(repository().refreshIfStale(tickers())!!))
    }

    /**
     * Every symbol is fetched at a *daily* interval. `ChartRange.Year` would have given `1y`/`1wk`
     * — fifty-two weekly bars — and a 200-period average over 52 bars is not a smaller number, it
     * is a meaningless one. Asserting the intervals rather than the count is what makes this fail
     * on the mistake that matters.
     */
    @Test
    fun every_series_is_fetched_at_a_daily_interval() = runTest {
        var yahoo = RecordingYahooClient()
        repository(yahoo).refreshIfStale(tickers())

        assertEquals(setOf("1d"), yahoo.requests.map { it.interval }.toSet())
    }

    @Test
    fun the_market_series_and_spy_and_every_tracked_symbol_are_all_fetched() = runTest {
        var yahoo = RecordingYahooClient()
        repository(yahoo).refreshIfStale(tickers())

        assertEquals(
            (MARKET_SERIES.map { it.symbol } + "SPY" + tickers()).toSet(),
            yahoo.requests.map { it.symbol }.toSet(),
        )
    }

    /**
     * SPY's own history has to be long enough to carry a 200-day average, and the assertion is on
     * the average rather than on the range asked for because that is the thing that breaks: a short
     * series yields no `ema200Cents`, `spyAboveMa200` goes null, and the trend and quality pillars
     * both quietly lose an input while the fetch still looks perfectly well-formed.
     */
    @Test
    fun spy_carries_enough_history_for_a_two_hundred_day_average() = runTest {
        assertNotNull(repository().refreshIfStale(tickers())!!.spyMa200Cents)
    }

    /** The VIX percentile is a percentile of its own year, so a quarter of history would mislabel it. */
    @Test
    fun the_vix_series_is_fetched_over_a_year() = runTest {
        var yahoo = RecordingYahooClient()
        repository(yahoo).refreshIfStale(tickers())

        assertEquals("1y", yahoo.requests.single { it.symbol == "^VIX" }.range)
    }

    /**
     * Three separate things can suppress a fetch — freshness, failure backoff, and single flight —
     * and each of the tests below arranges for exactly one of them to be the reason. Two guards
     * covering the same call is how the first version of this file passed while one of them was
     * dead code: each test was measuring the other's guard.
     */
    @Test
    fun a_second_refresh_inside_the_freshness_window_fetches_nothing() = runTest {
        var yahoo = RecordingYahooClient()
        var clock = MutableClock(START_EPOCH)
        var repository = repository(yahoo, clock = clock)
        repository.refreshIfStale(tickers())
        var afterFirst = yahoo.requests.size

        clock.epochSeconds = START_EPOCH + 100L
        repository.refreshIfStale(tickers())

        assertEquals(afterFirst, yahoo.requests.size)
    }

    @Test
    fun invalidate_clears_a_fresh_cache() = runTest {
        var yahoo = RecordingYahooClient()
        var clock = MutableClock(START_EPOCH)
        var repository = repository(yahoo, clock = clock)
        repository.refreshIfStale(tickers())
        var afterFirst = yahoo.requests.size
        clock.epochSeconds = START_EPOCH + 100L
        repository.refreshIfStale(tickers())

        repository.invalidate()
        repository.refreshIfStale(tickers())

        assertEquals(true, yahoo.requests.size > afterFirst)
    }

    @Test
    fun a_refresh_past_the_freshness_window_fetches_again() = runTest {
        var yahoo = RecordingYahooClient()
        var clock = MutableClock(START_EPOCH)
        var repository = repository(yahoo, clock = clock)
        repository.refreshIfStale(tickers())
        var afterFirst = yahoo.requests.size

        clock.epochSeconds = START_EPOCH + 200L
        repository.refreshIfStale(tickers())

        assertTrue("expected a second round beyond $afterFirst", yahoo.requests.size > afterFirst)
    }

    /**
     * A failed attempt caches nothing, so freshness cannot be what holds the next call back — the
     * backoff is the only thing standing here. Without it every dashboard refresh while offline
     * would spend a full round of failing requests rediscovering that the market is unreachable.
     */
    @Test
    fun an_unreachable_market_is_not_retried_on_every_call() = runTest {
        var yahoo = FailingYahooClient()
        var clock = MutableClock(START_EPOCH)
        var repository = repository(yahoo, clock = clock)
        repository.refreshIfStale(tickers())
        var afterFirst = yahoo.attempts

        clock.epochSeconds = START_EPOCH + 200L
        repository.refreshIfStale(tickers())

        assertEquals(afterFirst, yahoo.attempts)
    }

    /** The backoff is a delay, not a surrender — the market comes back and so must the reading. */
    @Test
    fun an_unreachable_market_is_tried_again_once_the_backoff_expires() = runTest {
        var yahoo = FailingYahooClient()
        var clock = MutableClock(START_EPOCH)
        var repository = repository(yahoo, clock = clock)
        repository.refreshIfStale(tickers())
        var afterFirst = yahoo.attempts

        clock.epochSeconds = START_EPOCH + 400L
        repository.refreshIfStale(tickers())

        assertTrue("expected a second attempt beyond $afterFirst", yahoo.attempts > afterFirst)
    }

    /**
     * Two callers arriving together — a scheduled refresh and a pull-to-refresh, say — must cost
     * one round of requests, not two. Nothing is cached yet when the second arrives, so the flight
     * flag is the only thing that can stop it.
     */
    @Test
    fun two_callers_arriving_together_spend_one_round_of_requests() = runTest {
        var yahoo = RecordingYahooClient()
        var repository = repository(yahoo)

        awaitAll(
            async { repository.refreshIfStale(tickers()) },
            async { repository.refreshIfStale(tickers()) },
        )

        assertEquals(MARKET_SERIES.size + 1 + tickers().size, yahoo.requests.size)
    }

    /**
     * Windows still returns the computed object when it is not scoreable, so the banner can show
     * Unknown / degraded copy. Scoring stays on [cachedRegime], which must stay empty.
     */
    @Test
    fun a_total_network_failure_still_returns_the_computed_reading() = runTest {
        assertNotNull(
            repository(FailingYahooClient(), fearGreed = AbsentFearGreedClient()).refreshIfStale(tickers()),
        )
    }

    @Test
    fun a_total_network_failure_does_not_cache_a_scoreable_reading() = runTest {
        var repository = repository(FailingYahooClient(), fearGreed = AbsentFearGreedClient())
        repository.refreshIfStale(tickers())
        assertNull(repository.cachedRegime())
    }

    /** One symbol failing costs its pillar a sample; the other eighty-nine still get counted. */
    @Test
    fun one_failing_symbol_does_not_cost_the_whole_reading() = runTest {
        var regime = repository(RecordingYahooClient(failFor = setOf("SYM3")))
            .refreshIfStale(tickers())

        assertEquals(tickers().size - 1, regime!!.breadthSample)
    }

    /**
     * Sentiment is the one input with no equity-grade fallback, so its absence has to degrade the
     * reading rather than fail it. Windows substitutes alternative.me here, which is the *crypto*
     * fear and greed index; [an_equity_and_a_crypto_sentiment_reading_are_not_interchangeable] is
     * why this port returns nothing instead.
     */
    @Test
    fun a_missing_sentiment_reading_still_leaves_a_scoreable_market() = runTest {
        assertNotNull(
            RegimeScoringPolicy.fromRegime(
                repository(fearGreed = AbsentFearGreedClient()).refreshIfStale(tickers())!!,
            ),
        )
    }

    /**
     * Measured on 2026-08-09, CNN read 63.7 ("Greed") while alternative.me read 31 ("Fear") — the
     * same day, opposite zones, because they measure different markets. The sentiment zone selects
     * the action stance and the stance selects the whole scoring policy, so substituting one for
     * the other is not a smaller reading, it is a different one.
     *
     * This is the evidence for not porting the fallback, and it is a test rather than a comment so
     * that anyone who adds the fallback back has to argue with a number.
     */
    @Test
    fun an_equity_and_a_crypto_sentiment_reading_are_not_interchangeable() = runTest {
        var equity = repository(fearGreed = FixedFearGreedClient(63.7, "Greed")).refreshIfStale(tickers())
        var crypto = repository(fearGreed = FixedFearGreedClient(31.0, "Fear")).refreshIfStale(tickers())

        assertTrue(
            "expected different stances, both read ${equity!!.actionStance}",
            equity.actionStance != crypto!!.actionStance,
        )
    }

    /**
     * The tracked universe's bars are kept, and only those. SPY and the market series are fetched
     * for the reading itself and are not names anything is scored against, so storing them would
     * grow the table with rows the retrospective has no score to match them to.
     */
    @Test
    fun a_usable_reading_hands_the_tracked_universe_bars_to_the_sink() = runTest {
        var sink = RecordingCandleSink()
        repository(sink = sink).refreshIfStale(tickers())

        assertEquals(tickers().toSet(), sink.stored.keys)
    }

    /**
     * Holding a year of daily bars for every tracked name until the last fetch lands is what
     * filled two gigabytes on the emulator after the 501/501 spinner dropped. Persist each
     * chunk and drop it.
     */
    @Test
    fun a_universe_of_many_names_persists_bars_in_more_than_one_chunk() = runTest {
        var sink = RecordingCandleSink()
        repository(sink = sink).refreshIfStale(tickers())

        assertEquals(true, sink.callCount > 1)
    }

    /**
     * A round that failed hard enough to be unusable is a round whose bars are as likely partial,
     * and a partial year written into the retrospective is worse than a missing day.
     */
    @Test
    fun a_reading_no_policy_can_score_stores_no_bars() = runTest {
        var sink = RecordingCandleSink()
        repository(yahoo = FailingYahooClient(), sink = sink).refreshIfStale(tickers())

        assertEquals(0, sink.callCount)
    }

    // ── Fixtures ─────────────────────────────────────────────────────────────

    private fun repository(
        yahoo: YahooFinanceClient = RecordingYahooClient(),
        fearGreed: CnnFearGreedClient = FixedFearGreedClient(55.0, "Neutral"),
        clock: MutableClock = MutableClock(START_EPOCH),
        sink: DailyCandleSink? = null,
    ) = MarketDataRepository(
        yahooClient = yahoo,
        fearGreedClient = fearGreed,
        nowEpochSeconds = { clock.epochSeconds },
        dailyCandleSink = sink,
    )

    private fun tickers() = (0 until 90).map { "SYM$it" }

    private class MutableClock(var epochSeconds: Long)

    private class RecordingCandleSink : DailyCandleSink {
        var callCount = 0
        var stored: Map<String, List<HistoricalCandle>> = emptyMap()

        override suspend fun persistBacktestCandles(
            candlesBySymbol: Map<String, List<HistoricalCandle>>,
            capturedAtEpochSeconds: Long,
        ): Int {
            callCount += 1
            stored = stored + candlesBySymbol
            return 0
        }
    }

    private data class CandleRequest(val symbol: String, val range: String, val interval: String)

    /**
     * A rising series per symbol, offset by the symbol's hash so the correlation sample sees names
     * that move together but not identically — a universe of identical series would make the
     * correlation pillar read a perfect 1.0 and stop resembling a market.
     *
     * The [delay] costs nothing under virtual time and makes a request a thing with a duration, so
     * a second caller can arrive while the first is still in flight.
     *
     * **The range token decides how many bars come back**, as it does at the real endpoint. A fake
     * that returns a year of history no matter what is asked for cannot fail on a shortened range,
     * and a test written against it would assert the request while measuring nothing — which is
     * exactly how [spy_carries_enough_history_for_a_two_hundred_day_average] first passed against a
     * mutation that broke it.
     */
    private open class RecordingYahooClient(
        private val failFor: Set<String> = emptySet(),
    ) : YahooFinanceClient(httpClient = offlineHttpClient()) {
        val requests = mutableListOf<CandleRequest>()

        override suspend fun fetchCandles(
            symbol: String,
            rangeToken: String,
            interval: String,
        ): List<HistoricalCandle> {
            synchronized(requests) { requests += CandleRequest(symbol, rangeToken, interval) }
            delay(1L)
            if (symbol in failFor) throw IOException("fixture: $symbol unreachable")
            var wobble = symbol.hashCode() % 5
            return (0 until tradingDaysIn(rangeToken)).map { bar ->
                var cents = 10_000L + (bar * 20L) + ((bar + wobble) % 7) * 15L
                HistoricalCandle(
                    epochSeconds = bar.toLong() * 86_400L,
                    openCents = cents,
                    highCents = cents + 50L,
                    lowCents = cents - 50L,
                    closeCents = cents,
                    volume = 1_000L + bar,
                )
            }
        }
    }

    private class FailingYahooClient : YahooFinanceClient(httpClient = offlineHttpClient()) {
        var attempts = 0

        override suspend fun fetchCandles(
            symbol: String,
            rangeToken: String,
            interval: String,
        ): List<HistoricalCandle> {
            synchronized(this) { attempts += 1 }
            throw IOException("fixture: network down")
        }
    }

    private class FixedFearGreedClient(
        private val score: Double,
        private val rating: String,
    ) : CnnFearGreedClient(httpClient = offlineHttpClient()) {
        override suspend fun fetch(today: LocalDate) = CnnFearGreed(score = score, rating = rating)
    }

    private class AbsentFearGreedClient : CnnFearGreedClient(httpClient = offlineHttpClient()) {
        override suspend fun fetch(today: LocalDate): CnnFearGreed? = null
    }

    private companion object {
        const val START_EPOCH = 1_700_000_000L

        /** Unknown tokens fail rather than default, so a typo cannot quietly become a year. */
        fun tradingDaysIn(rangeToken: String): Int = when (rangeToken) {
            "1mo" -> 21
            "3mo" -> 63
            "6mo" -> 126
            "1y" -> 260
            else -> error("fixture does not model the range '$rangeToken'")
        }
    }
}
