package com.discountscreener.android.app

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Turns "a load is running" into "the platform must not freeze this process".
 *
 * The repository reports the load and knows nothing about Android services; the service knows
 * nothing about loads. This is the one place that joins them, so neither has to learn the other.
 */
internal class ForegroundLoadKeeper(
    private val context: Context,
    private val start: (Context) -> Unit = RefreshForegroundService::start,
    private val stop: (Context) -> Unit = RefreshForegroundService::stop,
) {
    fun keep(loadInFlight: Flow<Boolean>, scope: CoroutineScope) {
        scope.launch {
            loadInFlight.distinctUntilChanged().collect { running ->
                if (running) start(context) else stop(context)
            }
        }
    }
}
