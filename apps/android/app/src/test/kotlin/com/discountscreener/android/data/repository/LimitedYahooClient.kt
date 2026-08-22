package com.discountscreener.android.data.repository

import com.discountscreener.android.data.remote.ProviderComponentState
import com.discountscreener.android.data.remote.ProviderCoverage
import com.discountscreener.android.data.remote.ProviderDiagnostic
import com.discountscreener.android.data.remote.ProviderFetchResult
import com.discountscreener.android.data.remote.YahooFinanceClient
import com.discountscreener.android.data.remote.offlineHttpClient
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ExternalValuationSignal
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.FundamentalTimeseries
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.MarketSnapshot
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicInteger

/**
 * A server with a limit, which is the thing the app has never been measured against.
 *
 * Over [limit] calls at once it answers 429 at once, the way a real one does: refusing is
 * cheap for the server and the client pays for it in a wasted round trip.
 */
internal class LimitedYahooClient(
    private val limit: Int = DEFAULT_LIMIT,
    private val latencyMillis: Long = DEFAULT_LATENCY_MILLIS,
) : YahooFinanceClient(httpClient = offlineHttpClient()) {
    val symbolsDone = AtomicInteger(0)
    val rejected = AtomicInteger(0)
    val peakInFlight = AtomicInteger(0)
    val timeseriesCalls = AtomicInteger(0)
    private val inFlight = AtomicInteger(0)

    override suspend fun fetchSymbol(symbol: String): ProviderFetchResult {
        var accepted = enter()
        try {
            if (!accepted) {
                rejected.incrementAndGet()
                delay(REJECT_MILLIS)
                return rateLimited(symbol)
            }
            delay(latencyMillis)
            return good(symbol)
        } finally {
            inFlight.decrementAndGet()
            if (accepted) {
                symbolsDone.incrementAndGet()
            }
        }
    }

    override suspend fun fetchHistoricalCandles(symbol: String, range: ChartRange): List<HistoricalCandle> {
        var accepted = enter()
        try {
            if (!accepted) {
                rejected.incrementAndGet()
                delay(REJECT_MILLIS)
                return emptyList()
            }
            delay(latencyMillis)
            var close = 10_000L + symbol.length * 100L
            return listOf(
                HistoricalCandle(
                    epochSeconds = 1_699_999_000L,
                    openCents = close - 50,
                    highCents = close + 50,
                    lowCents = close - 75,
                    closeCents = close,
                    volume = 1_000,
                ),
            )
        } finally {
            inFlight.decrementAndGet()
        }
    }

    /** The DCF fallback reaches here. It is a third round trip, and it is not free. */
    override suspend fun fetchFundamentalTimeseries(symbol: String): FundamentalTimeseries {
        timeseriesCalls.incrementAndGet()
        enter()
        try {
            delay(latencyMillis)
            return FundamentalTimeseries()
        } finally {
            inFlight.decrementAndGet()
        }
    }

    private fun enter(): Boolean {
        var now = inFlight.incrementAndGet()
        peakInFlight.updateAndGet { peak -> maxOf(peak, now) }
        return now <= limit
    }

    private fun rateLimited(symbol: String) = ProviderFetchResult(
        symbol = symbol,
        snapshot = null,
        externalSignal = null,
        fundamentals = null,
        coverage = ProviderCoverage(
            core = ProviderComponentState.Missing,
            external = ProviderComponentState.Missing,
            fundamentals = ProviderComponentState.Missing,
        ),
        diagnostics = listOf(
            ProviderDiagnostic(
                component = "quoteSummary",
                kind = "error",
                detail = "HTTP 429 for https://query2.finance.yahoo.com/v10/finance/quoteSummary/$symbol",
                retryable = true,
            ),
        ),
    )

    private fun good(symbol: String): ProviderFetchResult {
        var price = 10_000L + symbol.sumOf { it.code }.toLong()
        var fair = price + 2_500L
        return ProviderFetchResult(
            symbol = symbol,
            snapshot = MarketSnapshot(
                symbol = symbol,
                companyName = "$symbol Holdings",
                profitable = true,
                marketPriceCents = price,
                intrinsicValueCents = fair,
            ),
            companyName = "$symbol Holdings",
            externalSignal = ExternalValuationSignal(symbol = symbol, fairValueCents = fair, ageSeconds = 0),
            fundamentals = FundamentalSnapshot(
                symbol = symbol,
                marketCapDollars = 100_000_000_000L,
                sharesOutstanding = 1_000_000_000L,
                betaMillis = 1_000,
            ),
            coverage = ProviderCoverage(
                core = ProviderComponentState.Fresh,
                external = ProviderComponentState.Fresh,
                fundamentals = ProviderComponentState.Fresh,
            ),
            diagnostics = emptyList(),
        )
    }

    companion object {
        /** What a provider that is not refusing has been seen to take. */
        const val DEFAULT_LIMIT = 16

        /** One round trip on a phone, near enough for a reading about the window. */
        const val DEFAULT_LATENCY_MILLIS = 40L

        /** Refusing is cheap for a server, and the client still pays a round trip for it. */
        private const val REJECT_MILLIS = 5L
    }
}
