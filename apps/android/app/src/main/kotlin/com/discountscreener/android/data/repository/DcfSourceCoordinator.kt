package com.discountscreener.android.data.repository

import com.discountscreener.android.data.remote.FundamentalTimeseriesProvider
import com.discountscreener.android.data.remote.YahooFinanceClient
import com.discountscreener.core.engine.DcfSourceSelectionPolicy
import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.DcfSource
import com.discountscreener.core.model.DcfSourceCandidate
import com.discountscreener.core.model.DcfSourceSelection
import com.discountscreener.core.model.FundamentalTimeseries
import kotlinx.coroutines.CancellationException

internal class DcfSourceCoordinator(
    private val yahooClient: YahooFinanceClient,
    private val secondaryTimeseriesProvider: FundamentalTimeseriesProvider? = null,
) {
    suspend fun resolve(
        symbol: String,
        evaluate: (FundamentalTimeseries) -> DcfAnalysis?,
    ): DcfSourceSelection {
        val secCandidate = secondaryTimeseriesProvider?.let { provider ->
            candidate(
                source = DcfSource.SecEdgar,
                timeseries = fetchSecondary(provider, symbol),
                evaluate = evaluate,
            )
        }
        if (secCandidate != null && DcfSourceSelectionPolicy.isDcfUsable(secCandidate)) {
            return DcfSourceSelectionPolicy.select(sec = secCandidate)
        }

        val yahooCandidate = candidate(
            source = DcfSource.YahooFinance,
            timeseries = fetchYahoo(symbol),
            evaluate = evaluate,
        )
        return DcfSourceSelectionPolicy.select(yahoo = yahooCandidate, sec = secCandidate)
    }

    private suspend fun fetchYahoo(symbol: String): FundamentalTimeseries? = try {
        yahooClient.fetchFundamentalTimeseries(symbol)
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        null
    }

    private suspend fun fetchSecondary(
        provider: FundamentalTimeseriesProvider,
        symbol: String,
    ): FundamentalTimeseries? = try {
        provider.fetch(symbol)
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        null
    }

    private fun candidate(
        source: DcfSource,
        timeseries: FundamentalTimeseries?,
        evaluate: (FundamentalTimeseries) -> DcfAnalysis?,
    ): DcfSourceCandidate = DcfSourceCandidate(
        source = source,
        timeseries = timeseries,
        analysis = timeseries?.let { value -> runCatching { evaluate(value) }.getOrNull() },
    )
}