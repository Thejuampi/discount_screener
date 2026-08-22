package com.discountscreener.android.data.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [Call.execute], with the socket call ended when the coroutine is cancelled.
 *
 * `execute()` blocks its thread until the answer or the timeout, and cancelling the coroutine
 * around it changes nothing: the permit it holds, the connection and the bytes still to come are
 * all held to the end of the call. On a profile switch that is twenty-four calls the user no longer
 * wants, each holding a permit the new profile is waiting for, for as long as the network takes.
 * Twenty seconds at the call timeout.
 *
 * `enqueue()` would be cancellable on its own, and is not used on purpose: it goes through OkHttp's
 * `Dispatcher`, whose five-per-host limit would sit under the window and make every reading of it
 * a reading of a queue. See `HttpDispatcherCeilingProbeTest`.
 *
 * The call runs on the calling thread, inside the coroutine. The cancel handler is registered
 * first, so a cancel that arrives while the socket is open reaches [Call.cancel] and `execute()`
 * returns with an error, which is then dropped because the coroutine is already cancelled.
 */
internal suspend fun Call.executeCancellable(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    var response = try {
        execute()
    } catch (error: IOException) {
        continuation.resumeWithException(error)
        return@suspendCancellableCoroutine
    }
    continuation.resume(response) { response.close() }
}

/**
 * [Response.body]'s `.string()`, with the body read ended when the coroutine is cancelled.
 *
 * The read runs on a dedicated thread because okio's blocking [okio.Source.read] has no
 * suspension point of its own: a coroutine cancellation can't reach it just by marking the job
 * cancelled. Closing the response body is what does — okio's slow reader sees the source close
 * and unblocks, instead of running to its own read timeout or worse.
 */
internal suspend fun Call.readTextCancellable(response: Response): String =
    suspendCancellableCoroutine { continuation ->
        val reader = Thread({
            try {
                val text = response.body?.string().orEmpty()
                if (continuation.isActive) {
                    continuation.resume(text)
                }
            } catch (error: IOException) {
                if (!continuation.isActive) {
                    return@Thread
                }
                if (isCanceled() || canceledIo(error)) {
                    continuation.cancel(CancellationException("call cancelled", error))
                } else {
                    continuation.resumeWithException(error)
                }
            }
        }, "yahoo-body-reader")
        continuation.invokeOnCancellation {
            cancel()
            response.close()
        }
        reader.start()
    }
