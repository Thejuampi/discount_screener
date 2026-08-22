package com.discountscreener.android.data.repository

import com.discountscreener.android.data.remote.FundamentalTimeseriesProvider
import com.discountscreener.android.data.remote.ResidualCompanyFactsProvider
import com.discountscreener.core.model.FundamentalTimeseries
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Stands for `SecEdgarTimeseriesProvider`, and writes down every symbol that would pay for a file.
 *
 * One companyfacts file is about 4 MB, downloaded, written to flash, read back and sieved. The real
 * provider serves two interfaces, and a counter that watched only one could not see the other: the
 * residual-income chain for financial services asks through [ResidualCompanyFactsProvider], and a
 * whole bulk load can pay for banks and insurers while a timeseries-only counter reports zero.
 *
 * It answers nothing, so what it reports is the number of files the load would have downloaded.
 */
class CountingSecProvider(
    private val latencyMillis: Long = DEFAULT_LATENCY_MILLIS,
) : FundamentalTimeseriesProvider, ResidualCompanyFactsProvider {
    val calls = AtomicInteger(0)
    val symbols = ConcurrentHashMap.newKeySet<String>()

    override suspend fun fetch(symbol: String): FundamentalTimeseries? {
        record(symbol)
        return null
    }

    override suspend fun fetchSievedCompanyFacts(symbol: String): String? {
        record(symbol)
        return null
    }

    private suspend fun record(symbol: String) {
        calls.incrementAndGet()
        symbols.add(symbol)
        if (latencyMillis > 0) {
            delay(latencyMillis)
        }
    }

    companion object {
        const val DEFAULT_LATENCY_MILLIS = 200L
    }
}
