package com.discountscreener.android.data.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The window has to move on what the provider says, and a test that only counts calls would pass
 * against a constant. Each test here reads the size after a described sequence of answers.
 */
class AdaptiveRequestWindowTest {

    @Test
    fun a_new_window_starts_at_the_size_it_was_given() = runBlocking {
        assertEquals(4, window(initial = 4).size())
    }

    /** Slow start: every good answer opens the window by one, so it doubles every round trip. */
    @Test
    fun four_good_answers_double_a_window_of_four() = runBlocking {
        var window = window(initial = 4)

        repeat(4) { window.withPermit(REFUSED_NEVER) { GOOD } }

        assertEquals(8, window.size())
    }

    @Test
    fun the_window_never_opens_past_its_ceiling() = runBlocking {
        var window = window(initial = 4, max = 6)

        repeat(20) { window.withPermit(REFUSED_NEVER) { GOOD } }

        assertEquals(6, window.size())
    }

    /** Multiplicative decrease: one refusal halves it. */
    @Test
    fun a_refusal_halves_the_window() = runBlocking {
        var window = window(initial = 8)

        window.withPermit(REFUSED_ALWAYS) { REFUSAL }

        assertEquals(4, window.size())
    }

    /**
     * A refused round refuses every symbol at once, and all eight are already in flight when the
     * first refusal lands. Halving per symbol would take a window of eight to one on the first bad
     * round, and the load would then crawl for the rest of its life.
     */
    @Test
    fun a_round_where_every_call_is_refused_halves_the_window_once() = runBlocking {
        var window = window(initial = 8)

        coroutineScope {
            (1..8).map {
                async {
                    window.withPermit(REFUSED_ALWAYS) {
                        delay(5)
                        REFUSAL
                    }
                }
            }.awaitAll()
        }

        assertEquals(4, window.size())
    }

    @Test
    fun the_window_never_closes_below_one() = runBlocking {
        var window = window(initial = 4)

        repeat(6) { window.withPermit(REFUSED_ALWAYS) { REFUSAL } }

        assertEquals(1, window.size())
    }

    /**
     * Above the threshold the growth is additive: a full window of good answers buys one permit.
     * The threshold here is the four the refusal left behind.
     */
    @Test
    fun after_a_refusal_it_takes_a_full_window_of_good_answers_to_open_by_one() = runBlocking {
        var window = window(initial = 8)
        window.withPermit(REFUSED_ALWAYS) { REFUSAL }

        repeat(4) { window.withPermit(REFUSED_NEVER) { GOOD } }

        assertEquals(5, window.size())
    }

    /** The size is a promise about calls in flight, not a number a bench prints. */
    @Test
    fun no_more_calls_run_at_once_than_the_window_allows() = runBlocking {
        assertEquals(3, peakInFlight(window(initial = 3, max = 3)))
    }


    /**
     * A cancelled call has to give its permit back. The user cancels a round on every profile
     * switch, and every call in flight is cancelled with it. A permit lost there is lost for the
     * life of the process, and once a push-back storm closes the window to one, one lost permit
     * leaves the refresh with nothing to run on: 393 of 1 937 symbols and no thread doing anything.
     */
    @Test
    fun a_cancelled_round_gives_every_permit_back() = runBlocking {
        var window = window(initial = 4, max = 4)

        repeat(CANCEL_ROUNDS) {
            var round = CoroutineScope(Dispatchers.Default + Job())
            repeat(16) { round.launch { window.withPermit(REFUSED_NEVER) { delay(50); GOOD } } }
            yield()
            round.cancel()
            round.coroutineContext.job.join()
        }

        assertEquals(4, peakInFlight(window))
    }

    /**
     * How many calls the window really runs at once. A permit it lost reads as a smaller peak, and
     * a permit it handed out twice reads as a larger one.
     */
    private suspend fun peakInFlight(window: AdaptiveRequestWindow): Int {
        var inFlight = AtomicInteger(0)
        var peak = AtomicInteger(0)
        withTimeout(PEAK_TIMEOUT_MILLIS) {
            coroutineScope {
                (1..40).map {
                    async(Dispatchers.Default) {
                        window.withPermit(REFUSED_NEVER) {
                            // The count moves outside updateAndGet: that lambda runs again on a
                            // lost race, and a second run would count the same call twice.
                            var current = inFlight.incrementAndGet()
                            peak.updateAndGet { seen -> maxOf(seen, current) }
                            delay(5)
                            inFlight.decrementAndGet()
                            GOOD
                        }
                    }
                }.awaitAll()
            }
        }
        return peak.get()
    }

    private fun window(initial: Int, max: Int = 24) =
        AdaptiveRequestWindow(maxWindow = max, initialWindow = initial)

    private companion object {
        private const val GOOD = "answered"
        private const val REFUSAL = "HTTP 429"
        private val REFUSED_NEVER: (String) -> Boolean = { false }
        private val REFUSED_ALWAYS: (String) -> Boolean = { true }

        /** Rounds of cancel-under-load. One lost permit per round is enough to show up. */
        private const val CANCEL_ROUNDS = 50

        /** A window with no permits left never answers, so the peak read is bounded. */
        private const val PEAK_TIMEOUT_MILLIS = 10_000L
    }
}
