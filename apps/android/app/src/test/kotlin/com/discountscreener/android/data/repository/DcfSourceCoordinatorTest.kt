package com.discountscreener.android.data.repository

import com.discountscreener.android.data.remote.FundamentalTimeseriesProvider
import com.discountscreener.android.data.remote.YahooFinanceClient
import com.discountscreener.core.model.AnnualReportedValue
import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.DcfSource
import com.discountscreener.core.model.FundamentalTimeseries
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DcfSourceCoordinatorTest {
    @Test
    fun resolve_selects_usable_sec_without_fetching_yahoo_timeseries() = runTest {
        var yahoo = CountingYahooFinanceClient()
        var coordinator = DcfSourceCoordinator(
            yahooClient = yahoo,
            secondaryTimeseriesProvider = FixedTimeseriesProvider(usableTimeseries()),
        )

        var selection = coordinator.resolve("AAPL") { analysis() }

        assertEquals(DcfSource.SecEdgar to 0, selection.selectedSource to yahoo.timeseriesFetchCount)
    }

    @Test
    fun resolve_selects_usable_yahoo_when_secondary_provider_is_not_configured() = runTest {
        var yahoo = CountingYahooFinanceClient()
        var coordinator = DcfSourceCoordinator(yahooClient = yahoo)

        var selection = coordinator.resolve("AAPL") { analysis() }

        assertEquals(DcfSource.YahooFinance to 1, selection.selectedSource to yahoo.timeseriesFetchCount)
    }

    @Test
    fun resolve_selects_usable_yahoo_when_secondary_provider_is_unavailable() = runTest {
        var yahoo = CountingYahooFinanceClient()
        var secondary = UnavailableTimeseriesProvider()
        var coordinator = DcfSourceCoordinator(
            yahooClient = yahoo,
            secondaryTimeseriesProvider = secondary,
        )

        var selection = coordinator.resolve("AAPL") { analysis() }

        assertEquals(
            Triple(DcfSource.YahooFinance, 1, 1),
            Triple(selection.selectedSource, yahoo.timeseriesFetchCount, secondary.fetchCount),
        )
    }

    private class CountingYahooFinanceClient : YahooFinanceClient() {
        var timeseriesFetchCount = 0

        override suspend fun fetchFundamentalTimeseries(symbol: String): FundamentalTimeseries {
            timeseriesFetchCount++
            return usableTimeseries()
        }
    }

    private class FixedTimeseriesProvider(
        private val timeseries: FundamentalTimeseries?,
    ) : FundamentalTimeseriesProvider {
        override suspend fun fetch(symbol: String): FundamentalTimeseries? = timeseries
    }

    private class UnavailableTimeseriesProvider : FundamentalTimeseriesProvider {
        var fetchCount = 0

        override suspend fun fetch(symbol: String): FundamentalTimeseries? {
            fetchCount++
            throw IllegalStateException("unavailable")
        }
    }
}

private fun usableTimeseries() = FundamentalTimeseries(
    freeCashFlow = listOf(
        AnnualReportedValue("2021-12-31", 100.0),
        AnnualReportedValue("2022-12-31", 120.0),
        AnnualReportedValue("2023-12-31", 140.0),
    ),
)

private fun analysis() = DcfAnalysis(
    bearIntrinsicValueCents = 8_000L,
    baseIntrinsicValueCents = 10_000L,
    bullIntrinsicValueCents = 12_000L,
    waccBps = 800,
    baseGrowthBps = 500,
    netDebtDollars = 0L,
)