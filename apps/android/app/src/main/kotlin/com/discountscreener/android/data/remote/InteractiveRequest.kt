package com.discountscreener.android.data.remote

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Marks a coroutine as work the user is waiting on, so every provider serves it before the bulk
 * load.
 *
 * A ticker the user opens is two or three round trips against a load of fifteen hundred, and the
 * user is looking at it. Carried in the coroutine context rather than as a parameter, because the
 * calls that need it are the same `fetchSymbol` and `fetchHistoricalCandles` the load makes, and
 * the fakes that stand in for the client in tests override those signatures.
 *
 * `withContext(InteractiveRequest) { ... }` marks; [RequestGovernor] reads `coroutineContext[InteractiveRequest.Key]`.
 */
internal object InteractiveRequest : AbstractCoroutineContextElement(Key) {
    /** Its own key. An object cannot be its key: it is null while its constructor runs. */
    object Key : CoroutineContext.Key<InteractiveRequest>
}
