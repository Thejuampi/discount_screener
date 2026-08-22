package com.discountscreener.android.data.repository

import com.discountscreener.android.data.remote.ProviderComponentState
import com.discountscreener.android.data.remote.ProviderCoverage
import com.discountscreener.android.data.remote.ProviderFetchResult
import com.discountscreener.android.data.remote.YahooFinanceClient
import com.discountscreener.android.data.remote.offlineHttpClient
import com.discountscreener.core.model.AnnualReportedValue
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ExternalValuationSignal
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.FundamentalTimeseries
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.MarketSnapshot

/**
 * Answers every call from memory. No socket is opened, so no reading over it is a network time.
 *
 * [candlesPerChart] sizes the chart. One candle keeps a bench about the switch light; a year of
 * daily candles is what the shipped list carries per symbol, and what a bench about the snapshot
 * has to pay for.
 */
internal open class OfflineYahooClient(
    private val candlesPerChart: Int = 1,
) : YahooFinanceClient(httpClient = offlineHttpClient()) {
    override suspend fun fetchSymbol(symbol: String): ProviderFetchResult {
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

    override suspend fun fetchHistoricalCandles(symbol: String, range: ChartRange): List<HistoricalCandle> {
        var close = 10_000L + symbol.length * 100L
        return List(candlesPerChart) { index ->
            var drift = ((index * 7 + symbol.length) % 11 - 5) * 10L
            HistoricalCandle(
                epochSeconds = 1_699_999_000L - (candlesPerChart - 1 - index) * DAY_SECONDS,
                openCents = close + drift - 50,
                highCents = close + drift + 50,
                lowCents = close + drift - 75,
                closeCents = close + drift,
                volume = 1_000,
            )
        }
    }

    override suspend fun fetchFundamentalTimeseries(symbol: String): FundamentalTimeseries = FundamentalTimeseries(
        freeCashFlow = listOf(
            AnnualReportedValue("2020-01-01", 10_000_000_000.0),
            AnnualReportedValue("2021-01-01", 12_000_000_000.0),
            AnnualReportedValue("2022-01-01", 14_000_000_000.0),
            AnnualReportedValue("2023-01-01", 16_000_000_000.0),
        ),
        dilutedAverageShares = listOf(
            AnnualReportedValue("2020-01-01", 1_100_000_000.0),
            AnnualReportedValue("2021-01-01", 1_050_000_000.0),
            AnnualReportedValue("2022-01-01", 1_000_000_000.0),
            AnnualReportedValue("2023-01-01", 950_000_000.0),
        ),
    )

    private companion object {
        const val DAY_SECONDS = 86_400L
    }
}
