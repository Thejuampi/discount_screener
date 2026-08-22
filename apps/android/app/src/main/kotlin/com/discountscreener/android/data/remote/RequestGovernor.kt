package com.discountscreener.android.data.remote

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/**
 * What one provider is asked, how fast, and what happens when it says no.
 *
 * A permit is one request. The [AdaptiveRequestWindow] is how many may be outstanding; it opens
 * on good answers and closes on refusals, so concurrency follows what the provider can take.
 * Every wait is a suspension. A `Retry-After` stops the whole provider until it passes, because
 * the limit belongs to the provider and not to the one call that happened to hit it.
 *
 * Yahoo sends no `Retry-After`. Measured on a device on 2026-08-18: eight hundred quotes at about
 * fifty calls a second, then 429 with no header on nearly every call. The window closed to one in
 * two hundred milliseconds and stayed there, and a refusal at a window of one comes back in ten
 * milliseconds, so the app sent about sixty-five refused calls a second for as long as the load
 * lasted. A window steers concurrency; it cannot steer rate against a server that answers no at
 * once. So a refusal that arrives with the window already at one is read as a quota, and the
 * provider is held: one second, then double for every refusal that follows a hold, up to
 * [DEFAULT_MAX_REFUSAL_HOLD_MILLIS]; the first good answer resets it. A refusal that arrives with
 * the window still open is pressure, and the window shrinking is the whole answer to it.
 *
 * A call made under [InteractiveRequest] is one the user is waiting on. It takes the next permit
 * before the bulk load's line, and it still waits out a hold: the provider said no to everyone,
 * and asking again inside that window is a wasted call and a longer hold.
 */
internal class RequestGovernor(
    private val window: AdaptiveRequestWindow = AdaptiveRequestWindow(),
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val baseBackoffMillis: Long = DEFAULT_BASE_BACKOFF_MILLIS,
    private val maxBackoffMillis: Long = DEFAULT_MAX_BACKOFF_MILLIS,
    private val maxCooldownMillis: Long = DEFAULT_MAX_COOLDOWN_MILLIS,
    private val refusalHoldMillis: Long = DEFAULT_REFUSAL_HOLD_MILLIS,
    private val maxRefusalHoldMillis: Long = DEFAULT_MAX_REFUSAL_HOLD_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { millis -> delay(millis) },
    private val jitter: (Long) -> Long = { bound -> if (bound <= 1L) 0L else Random.nextLong(bound) },
    /**
     * Told of every refusal: the provider's `Retry-After` in millis, or null when it sent none,
     * then the window size after the refusal and the provider hold in millis.
     */
    private val onPushBack: suspend (retryAfterMillis: Long?, windowSize: Int, cooldownMillis: Long) -> Unit =
        { _, _, _ -> },
) {
    /** What one attempt at a request came back as. */
    sealed interface Attempt<out T> {
        /** The provider answered. */
        data class Ok<T>(val value: T) : Attempt<T>

        /**
         * The provider refused. [retryAfterMillis] is its own instruction when it sent one, and
         * that instruction holds the whole provider back, not just this call.
         */
        data class PushBack(val retryAfterMillis: Long?, val error: Throwable) : Attempt<Nothing>

        /** The call failed for a reason the provider did not choose. [retryable] decides a retry. */
        data class Failed(val retryable: Boolean, val error: Throwable) : Attempt<Nothing>
    }

    private val mutex = Mutex()
    private var cooldownUntil = 0L

    /** The next hold a refusal at a closed window earns. Doubles per hold, resets on a good answer. */
    private var nextRefusalHold = refusalHoldMillis

    /** The largest number of requests this may have in flight, for sizing a caller's fan-out. */
    val ceiling: Int = window.ceiling

    /** The window size right now. For logs and probes, never for a decision inside this class. */
    suspend fun windowSize(): Int = window.size()

    /** How long the provider still wants to be left alone, in milliseconds. Zero when it is ready. */
    suspend fun cooldownRemaining(): Long = mutex.withLock { maxOf(0L, cooldownUntil - clock()) }

    /**
     * Runs [attempt] until it answers, the provider stops being worth asking, or the attempts run
     * out. Each try takes its own permit and reports its own outcome, so the window learns from
     * every request instead of from the last of four.
     */
    suspend fun <T> request(attempt: suspend () -> Attempt<T>): T {
        var tries = 0
        var lastError: Throwable? = null
        val urgent = coroutineContext[InteractiveRequest.Key] != null
        while (tries < maxAttempts) {
            tries += 1
            awaitCooldown()
            val outcome = window.withPermit({ result: Attempt<T> -> result is Attempt.PushBack }, urgent) {
                awaitCooldown()
                attempt()
            }
            when (outcome) {
                is Attempt.Ok -> {
                    resetRefusalHold()
                    return outcome.value
                }
                is Attempt.PushBack -> {
                    lastError = outcome.error
                    val windowSize = window.size()
                    when {
                        outcome.retryAfterMillis != null -> holdProvider(outcome.retryAfterMillis)
                        windowSize <= 1 -> holdForRefusal()
                    }
                    onPushBack(outcome.retryAfterMillis, windowSize, cooldownRemaining())
                }
                is Attempt.Failed -> {
                    lastError = outcome.error
                    if (!outcome.retryable) throw outcome.error
                }
            }
            if (tries < maxAttempts) {
                sleep(backoffFor(tries))
            }
        }
        throw lastError ?: IllegalStateException("request gave no outcome")
    }

    /**
     * The provider asked for quiet, so nothing starts until it is over. The cap keeps one absurd
     * header from parking the load for an hour; a longer wait than the cap ends the round instead,
     * which the caller can report.
     */
    private suspend fun holdProvider(retryAfterMillis: Long) {
        val until = clock() + retryAfterMillis.coerceIn(0L, maxCooldownMillis)
        mutex.withLock {
            if (until > cooldownUntil) {
                cooldownUntil = until
            }
        }
    }

    /**
     * The window has nothing left to give and the provider still says no: a quota, so the load
     * stops asking. Refusals that arrive while a hold is on were sent before it and teach nothing;
     * only a refusal after a hold has passed doubles the next one.
     */
    private suspend fun holdForRefusal() {
        val now = clock()
        mutex.withLock {
            if (now < cooldownUntil) return
            cooldownUntil = now + nextRefusalHold
            nextRefusalHold = (nextRefusalHold * 2).coerceAtMost(maxRefusalHoldMillis)
        }
    }

    private suspend fun resetRefusalHold() {
        mutex.withLock { nextRefusalHold = refusalHoldMillis }
    }

    private suspend fun awaitCooldown() {
        while (true) {
            val remaining = cooldownRemaining()
            if (remaining <= 0L) return
            sleep(remaining)
        }
    }

    /** Full jitter, so a round of refused calls does not come back as one round of refused calls. */
    private fun backoffFor(tries: Int): Long {
        val ceilingMillis = (baseBackoffMillis shl (tries - 1).coerceAtMost(MAX_SHIFT))
            .coerceAtMost(maxBackoffMillis)
        return jitter(ceilingMillis)
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 4
        const val DEFAULT_BASE_BACKOFF_MILLIS = 400L

        /** A phone screen that waits longer than this reads as broken, not as busy. */
        const val DEFAULT_MAX_BACKOFF_MILLIS = 8_000L

        /** The longest a provider may hold the whole load back on its own say-so. */
        const val DEFAULT_MAX_COOLDOWN_MILLIS = 30_000L

        /** The first hold after a refusal at a closed window, with no `Retry-After` to go by. */
        const val DEFAULT_REFUSAL_HOLD_MILLIS = 1_000L

        /**
         * The longest self-imposed hold. One probe every eight seconds costs nothing while the
         * provider is closed, and the reopening is seen within eight seconds of it.
         */
        const val DEFAULT_MAX_REFUSAL_HOLD_MILLIS = 8_000L
        private const val MAX_SHIFT = 4
    }
}
