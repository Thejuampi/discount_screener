package com.discountscreener.android.data.remote

import okhttp3.Interceptor
import okhttp3.OkHttpClient

/**
 * An [OkHttpClient] that cannot reach the network, for test doubles built on the real clients.
 *
 * **The hole this closes.** [YahooFinanceClient] takes its `httpClient` as a defaulted constructor
 * parameter, so `object : YahooFinanceClient() { override fun fetchCandles(...) }` builds a *real*
 * client in the superclass constructor and overrides exactly one of five network methods. The other
 * four — `fetchSymbol`, `searchSymbols`, `fetchFundamentalTimeseries`, and any added later — stay
 * pointed at Yahoo. A unit test that reaches one of them makes a live request from the build: slow,
 * dependent on somebody else's uptime, rate-limited, and different on every machine.
 *
 * Nothing about the double says which methods it stubbed. The next method added to the client is
 * unstubbed in every existing double by default, and no test turns red to say so.
 *
 * **Why it throws an [AssertionError] and not an [java.io.IOException].** The clients catch
 * `IOException` in nine places and bare `Exception` in two more, and turn it into a diagnostic or a
 * null. A guard thrown as an exception would be caught by the code it is guarding, the test would
 * pass, and the leak it exists to report would be reported as "provider unavailable" — a guard that
 * cannot fail is not a guard. `AssertionError` is an `Error`, so it passes through every one of
 * those catches and lands on the test.
 *
 * The message names the URL, because "some test hit the network" is not actionable and
 * "GET https://query2.finance.yahoo.com/v10/finance/quoteSummary/AAPL" is.
 */
internal fun offlineHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .addInterceptor(
        Interceptor { chain ->
            val request = chain.request()
            throw AssertionError(
                "A unit test reached the network: ${request.method} ${request.url}\n" +
                    "  The test double built on this client did not override the method that made " +
                    "this call.\n" +
                    "  Override it and return a fixture, or stub the call at a higher seam.",
            )
        },
    )
    .build()
