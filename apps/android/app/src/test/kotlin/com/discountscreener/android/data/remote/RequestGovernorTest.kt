package com.discountscreener.android.data.remote

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * What the governor does with the three answers a provider can give.
 *
 * Every wait is recorded rather than taken, so a reading here is about the decision and never about
 * the machine that ran the test.
 */
class RequestGovernorTest {

    private val waits = mutableListOf<Long>()
    private var now = 0L

    @Test
    fun a_good_answer_is_returned_without_a_wait() = runTest {
        val body = governor().request { RequestGovernor.Attempt.Ok("ok") }

        assertEquals("ok", body)
    }

    @Test
    fun a_failure_the_provider_did_not_choose_is_retried_until_it_answers() = runTest {
        val tries = AtomicInteger(0)

        val body = governor().request {
            if (tries.incrementAndGet() < 3) {
                RequestGovernor.Attempt.Failed(retryable = true, error = IOException("HTTP 503"))
            } else {
                RequestGovernor.Attempt.Ok("ok")
            }
        }

        assertEquals(3, tries.get())
        assertEquals("ok", body)
    }

    /** A 404 is an answer. Asking for it four times is four times the load for the same answer. */
    @Test
    fun a_failure_that_will_not_change_is_thrown_at_once() = runTest {
        val tries = AtomicInteger(0)

        runCatching {
            governor().request<String> {
                tries.incrementAndGet()
                RequestGovernor.Attempt.Failed(retryable = false, error = IOException("HTTP 404"))
            }
        }

        assertEquals(1, tries.get())
    }

    @Test
    fun the_attempts_run_out_and_the_last_error_is_what_the_caller_sees() = runTest {
        val error = runCatching {
            governor().request<String> {
                RequestGovernor.Attempt.Failed(retryable = true, error = IOException("HTTP 503"))
            }
        }.exceptionOrNull()

        assertEquals("HTTP 503", error?.message)
    }

    /** A 503 is this call's bad luck, so the wait before the next try grows but stays bounded. */
    @Test
    fun a_retryable_failure_backs_off_with_a_growing_bound() = runTest {
        runCatching {
            governor().request<String> {
                RequestGovernor.Attempt.Failed(retryable = true, error = IOException("HTTP 503"))
            }
        }

        assertEquals(listOf(399L, 799L, 1_599L), waits)
    }

    /**
     * The whole point of the class. `Retry-After` is Yahoo saying how long to leave it alone, and
     * the app used to parse it and drop it, so a second call went out while the first was still
     * being refused.
     */
    @Test
    fun a_retry_after_holds_back_a_call_that_had_not_started() = runTest {
        val governor = governor()

        coroutineScope {
            val refused = async {
                runCatching {
                    governor.request<String> {
                        RequestGovernor.Attempt.PushBack(
                            retryAfterMillis = 5_000L,
                            error = IOException("HTTP 429"),
                        )
                    }
                }
            }
            refused.await()
        }

        assertEquals(5_000L, governor.cooldownRemaining())
    }

    /** A header nobody should honour. Thirty seconds of quiet is a pause; an hour is a hang. */
    @Test
    fun an_absurd_retry_after_is_capped() = runTest {
        val governor = governor()

        runCatching {
            governor.request<String> {
                RequestGovernor.Attempt.PushBack(
                    retryAfterMillis = 3_600_000L,
                    error = IOException("HTTP 429"),
                )
            }
        }

        assertEquals(RequestGovernor.DEFAULT_MAX_COOLDOWN_MILLIS, governor.cooldownRemaining())
    }

    /**
     * Yahoo sends no `Retry-After`. A refusal that arrives with the window already at one is a
     * quota, and the only answer left is to stop asking for a while.
     */
    @Test
    fun a_refusal_at_a_closed_window_holds_the_provider() = runTest {
        val governor = governor(closedWindow(), maxAttempts = 1)

        runCatching { governor.request<String> { refusedWithoutRetryAfter() } }

        assertEquals(RequestGovernor.DEFAULT_REFUSAL_HOLD_MILLIS, governor.cooldownRemaining())
    }

    /** A refusal while the window is still open is pressure; the window shrinking answers it. */
    @Test
    fun a_refusal_at_an_open_window_holds_nothing() = runTest {
        val governor = governor(maxAttempts = 1)

        runCatching { governor.request<String> { refusedWithoutRetryAfter() } }

        assertEquals(0L, governor.cooldownRemaining())
    }

    /** The provider was left alone for the hold and still says no, so the next hold is longer. */
    @Test
    fun a_refusal_after_a_hold_has_passed_doubles_the_next_hold() = runTest {
        val governor = governor(closedWindow(), maxAttempts = 1)

        runCatching { governor.request<String> { refusedWithoutRetryAfter() } }
        now += RequestGovernor.DEFAULT_REFUSAL_HOLD_MILLIS
        runCatching { governor.request<String> { refusedWithoutRetryAfter() } }

        assertEquals(RequestGovernor.DEFAULT_REFUSAL_HOLD_MILLIS * 2, governor.cooldownRemaining())
    }

    /** One good answer says the quota is over, and the ladder starts again from the bottom. */
    @Test
    fun a_good_answer_resets_the_hold_ladder() = runTest {
        val governor = governor(closedWindow(), maxAttempts = 1)

        runCatching { governor.request<String> { refusedWithoutRetryAfter() } }
        now += RequestGovernor.DEFAULT_REFUSAL_HOLD_MILLIS
        governor.request<String> { RequestGovernor.Attempt.Ok("ok") }
        runCatching { governor.request<String> { refusedWithoutRetryAfter() } }

        assertEquals(RequestGovernor.DEFAULT_REFUSAL_HOLD_MILLIS, governor.cooldownRemaining())
    }

    /**
     * One permit is one request. The window opens on good answers, so a run of them must leave it
     * wider than it started, which is what says the controller sees each call and not each symbol.
     */
    @Test
    fun the_window_learns_from_every_request_and_not_from_the_last_of_four() = runTest {
        val window = AdaptiveRequestWindow()
        val governor = governor(window)
        val started = window.size()

        repeat(started + 1) { governor.request<String> { RequestGovernor.Attempt.Ok("ok") } }

        assertEquals(started + started + 1, window.size())
    }

    /**
     * The waits are recorded and the clock is moved by hand, so a wait never really happens. Time
     * only moves when a wait is asked for, which is what makes the cooldown readings exact. The
     * jitter always draws the top of its bound, so the backoff readings are exact too.
     */
    private fun governor(
        window: AdaptiveRequestWindow = AdaptiveRequestWindow(),
        maxAttempts: Int = RequestGovernor.DEFAULT_MAX_ATTEMPTS,
    ) = RequestGovernor(
        window = window,
        maxAttempts = maxAttempts,
        clock = { now },
        sleep = { millis ->
            waits += millis
            now += millis
        },
        jitter = { bound -> bound - 1 },
    )

    /** A window with nothing left to give: one permit, and one is as low as it goes. */
    private fun closedWindow() = AdaptiveRequestWindow(maxWindow = 1, initialWindow = 1)

    private fun refusedWithoutRetryAfter() =
        RequestGovernor.Attempt.PushBack(retryAfterMillis = null, error = IOException("HTTP 429"))
}
