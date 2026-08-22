package com.discountscreener.android.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred

/**
 * A semaphore with two lines: whoever is urgent gets the next permit before anyone who is not.
 *
 * `kotlinx.coroutines.sync.Semaphore` is one line, first come first served. Under a bulk load of
 * five hundred symbols that line is twenty-four deep at all times, so a ticker the user has just
 * opened waited its turn behind the whole fan-out: measured at 13.5 s against a limited server,
 * for two calls. The user is looking at that ticker; the bulk load is looked at by nobody. Here
 * the open takes the next permit that comes free, and the load loses one round trip on one slot.
 *
 * Every state change is under one plain lock, because none of it suspends. A waiter parks on a
 * [CompletableDeferred]; a permit is handed to it by completing that. A waiter that is cancelled
 * while it holds a place in line gives the place back, and one that is cancelled in the moment
 * its permit was handed over passes the permit on, so a permit is never lost to a cancel.
 */
internal class PriorityGate(permits: Int) {
    private val lock = Any()
    private var free = permits
    private val urgentLine = ArrayDeque<CompletableDeferred<Unit>>()
    private val ordinaryLine = ArrayDeque<CompletableDeferred<Unit>>()

    /** Waits for a permit. An [urgent] caller is served before every ordinary caller in line. */
    suspend fun acquire(urgent: Boolean = false) {
        var ticket = synchronized(lock) {
            if (free > 0) {
                free -= 1
                return
            }
            CompletableDeferred<Unit>().also { ticket ->
                (if (urgent) urgentLine else ordinaryLine).addLast(ticket)
            }
        }
        try {
            ticket.await()
        } catch (error: CancellationException) {
            var permitWasHanded = synchronized(lock) {
                !(urgentLine.remove(ticket) || ordinaryLine.remove(ticket))
            }
            if (permitWasHanded) release()
            throw error
        }
    }

    /** Takes a permit only if one is free right now. */
    fun tryAcquire(): Boolean = synchronized(lock) {
        if (free == 0) return false
        free -= 1
        true
    }

    /** Hands the permit to the first urgent waiter, else the first ordinary one, else keeps it. */
    fun release() {
        var next = synchronized(lock) {
            urgentLine.removeFirstOrNull()
                ?: ordinaryLine.removeFirstOrNull()
                ?: run {
                    free += 1
                    null
                }
        }
        next?.complete(Unit)
    }
}
