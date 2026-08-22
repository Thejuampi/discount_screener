package com.discountscreener.android.data.remote

/**
 * How many requests may be in flight at once, decided by the provider instead of by a constant.
 *
 * The refresh used to ask for four at a time. Four was never measured: it is low enough to be safe
 * against any rate limit and low enough to be slow against every one of them. A provider that would
 * take sixteen still gets four, and a provider that is only good for two still gets four and
 * answers 429.
 *
 * This is the TCP rule. The window opens while the answers are good and closes when one is refused,
 * so the size settles at what the provider actually gives today:
 *
 * - **Slow start.** Below [threshold] every good answer opens the window by one, so it doubles
 *   every round trip and reaches a working size in a few of them.
 * - **Congestion avoidance.** At or above [threshold] it takes a full window of good answers to
 *   open it by one. Growth turns from doubling into a slow climb.
 * - **Multiplicative decrease.** A refusal halves the window and puts [threshold] there. One
 *   refusal counts once: every refusal of the same window is the same news arriving many times.
 *
 * The close lands as the calls in flight return. A permit that is out cannot be taken back, so the
 * shrink is recorded as [debt] and each returning call pays one instead of releasing it. The window
 * is at its new size within one round trip, which is the same delay TCP has.
 *
 * The permits are handed out by a [PriorityGate], so a call the user is waiting on takes the next
 * permit that comes free instead of the last place in a line of twenty-four.
 */
internal class AdaptiveRequestWindow(
    private val maxWindow: Int = DEFAULT_MAX_WINDOW,
    private val initialWindow: Int = DEFAULT_INITIAL_WINDOW,
    initialThreshold: Int = DEFAULT_MAX_WINDOW,
) {
    private val gate = PriorityGate(permits = initialWindow)

    /**
     * One plain lock over the state below, because none of that work suspends.
     *
     * A `Mutex` here read the same and lost permits. `Mutex.withLock` suspends when it is
     * contended, a suspend inside a `finally` throws when the caller was cancelled, and the
     * permit that `finally` was there to give back was dropped instead. Fifty rounds of
     * cancel-under-load took a window of four down to one. A profile switch cancels a round of
     * calls, so the app lost permits every time the user changed profile, and a push-back storm
     * that closes the window to one then left the refresh with nothing at all to run on.
     */
    private val lock = Any()

    /** Permits the window allows right now. Never above [maxWindow], never below one. */
    private var window = initialWindow

    /** Permits held back from [gate], so `window + ballast + debt == maxWindow`. */
    private var ballast = maxWindow - initialWindow

    /** Permits promised to a shrink and not yet taken from a returning call. */
    private var debt = 0

    /** Where doubling stops and the slow climb starts. */
    private var threshold = initialThreshold

    /** Good answers since the window last opened, counted only in congestion avoidance. */
    private var goodInWindow = 0

    /** Every call inside one shrink, so a refused round closes the window once and not per symbol. */
    private var refusalEpoch = 0L

    /** What [maxWindow] is, so a caller can size its own merge to the largest window this can open. */
    val ceiling: Int = maxWindow

    /** The size a reader sees. For benches and logs, never for a decision inside this class. */
    fun size(): Int = synchronized(lock) { window }

    /**
     * Runs [block] inside one permit and reads what came back.
     *
     * [refused] answers from the result whether the provider pushed back. It is a function of the
     * result rather than a thrown error because a rate limit arrives here as a diagnostic on a
     * successful call, which is the shape [isRateLimitDetail] already reads.
     *
     * A call that throws says nothing about the provider — a cancelled load is not a refusal and
     * not a good answer — so it gives its permit back and moves nothing.
     *
     * An [urgent] call is served before every ordinary call waiting for a permit.
     */
    suspend fun <T> withPermit(refused: (T) -> Boolean, urgent: Boolean = false, block: suspend () -> T): T {
        gate.acquire(urgent)
        var epoch = synchronized(lock) { refusalEpoch }
        var outcome = Outcome.Unknown
        try {
            val result = block()
            outcome = if (refused(result)) Outcome.Refused else Outcome.Good
            return result
        } finally {
            release(outcome, epoch)
        }
    }

    private fun release(outcome: Outcome, epoch: Long) {
        var permits = synchronized(lock) {
            when (outcome) {
                Outcome.Refused -> {
                    // Every refusal of one window is the same news. The first closes it; the rest
                    // only give their permit back, or the window would collapse to one on a round
                    // where every symbol was refused at the same moment.
                    if (epoch == refusalEpoch) closeLocked()
                    settleLocked()
                }
                Outcome.Good -> settleLocked() + openLocked()
                Outcome.Unknown -> settleLocked()
            }
        }
        repeat(permits) { gate.release() }
    }

    /**
     * Halves the window. A permit nobody is using is taken back at once; a permit that is out on a
     * call cannot be, so it is booked as [debt] and the call pays it when it returns.
     */
    private fun closeLocked() {
        refusalEpoch += 1
        val closed = maxOf(1, window / 2)
        var toTake = window - closed
        window = closed
        threshold = closed
        goodInWindow = 0
        while (toTake > 0 && gate.tryAcquire()) {
            ballast += 1
            toTake -= 1
        }
        debt += toTake
    }

    /** A returning permit pays a shrink before it goes back to the gate. */
    private fun settleLocked(): Int {
        if (debt == 0) {
            return 1
        }
        debt -= 1
        ballast += 1
        return 0
    }

    /**
     * Slow start opens the window on every good answer; congestion avoidance needs a full window
     * of them. Returns the extra permit the growth releases, on top of the returning one.
     */
    private fun openLocked(): Int {
        if (ballast == 0 || debt > 0) {
            return 0
        }
        if (window < threshold) {
            return grantLocked()
        }
        goodInWindow += 1
        if (goodInWindow < window) {
            return 0
        }
        return grantLocked()
    }

    private fun grantLocked(): Int {
        ballast -= 1
        window += 1
        goodInWindow = 0
        return 1
    }

    private enum class Outcome { Good, Refused, Unknown }

    companion object {
        /**
         * What Yahoo has been seen to take without refusing, plus room to find out it takes more.
         * The window only reaches here if every answer on the way was good.
         */
        const val DEFAULT_MAX_WINDOW = 24

        /** Where a load starts before the provider has said anything. */
        const val DEFAULT_INITIAL_WINDOW = 4
    }
}
