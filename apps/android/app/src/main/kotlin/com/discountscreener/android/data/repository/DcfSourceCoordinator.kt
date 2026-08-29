package com.discountscreener.android.data.repository

import com.discountscreener.android.data.remote.FundamentalTimeseriesProvider
import com.discountscreener.android.data.remote.YahooFinanceClient
import com.discountscreener.core.engine.DcfSourceSelectionPolicy
import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.DcfSource
import com.discountscreener.core.model.DcfSourceCandidate
import com.discountscreener.core.model.DcfSourceSelection
import com.discountscreener.core.model.FundamentalTimeseries
import com.discountscreener.core.model.ProviderDecisionReason
import com.discountscreener.core.model.ProviderDecisionReasonCode
import kotlinx.coroutines.CancellationException

/**
 * What one resolve chose, and every timeseries a provider sent for it, usable or not.
 *
 * [fetched] is the provider's answer as it came, keyed by who sent it. It is kept on file even
 * when the engine could not value it: the data is a day's worth of data whatever the verdict, and
 * the next resolve inside the day judges the file's copy instead of asking again. Measured on a
 * device on 2026-08-19: 354 of 497 S&P 500 rows were "valuation unavailable" and, with nothing on
 * file, each of them cost one Yahoo timeseries call at every launch.
 */
internal data class DcfResolution(
    val selection: DcfSourceSelection,
    val fetched: Map<DcfSource, FundamentalTimeseries>,
)

internal class DcfSourceCoordinator(
    private val yahooClient: YahooFinanceClient,
    private val secondaryTimeseriesProvider: FundamentalTimeseriesProvider? = null,
) {
    /**
     * The best usable timeseries for one symbol, the analysis it produces, and what was fetched.
     *
     * [allowSecondary] is what keeps a screen of five hundred rows loadable. The secondary provider
     * is SEC EDGAR, and one symbol there costs a whole companyfacts file: about 4 MB, of which the
     * sieve keeps about a thirtieth. Paid once per symbol on a bulk load, that file is most of the
     * load.
     * So the list resolves from Yahoo, and SEC is asked for only when a symbol is opened.
     */
    suspend fun resolve(
        symbol: String,
        allowSecondary: Boolean = true,
        evaluate: (FundamentalTimeseries) -> DcfAnalysis?,
    ): DcfResolution {
        val fetched = linkedMapOf<DcfSource, FundamentalTimeseries>()
        val secCandidate = secondaryTimeseriesProvider?.takeIf { allowSecondary }?.let { provider ->
            candidate(
                source = DcfSource.SecEdgar,
                timeseries = fetchSecondary(provider, symbol)?.also { fetched[DcfSource.SecEdgar] = it },
                evaluate = evaluate,
            )
        }
        if (secCandidate != null && DcfSourceSelectionPolicy.isDcfUsable(secCandidate)) {
            return DcfResolution(DcfSourceSelectionPolicy.select(sec = secCandidate), fetched)
        }

        val yahooCandidate = candidate(
            source = DcfSource.YahooFinance,
            timeseries = fetchYahoo(symbol)?.also { fetched[DcfSource.YahooFinance] = it },
            evaluate = evaluate,
        )
        return DcfResolution(DcfSourceSelectionPolicy.select(yahoo = yahooCandidate, sec = secCandidate), fetched)
    }

    /**
     * The selection a timeseries already on file produces, judged as it was the day it was fetched:
     * same source, same policy, no provider call. What changed is the market around it, and that
     * is in [evaluate].
     */
    fun resolveFromFile(
        source: DcfSource,
        timeseries: FundamentalTimeseries,
        evaluate: (FundamentalTimeseries) -> DcfAnalysis?,
    ): DcfSourceSelection {
        val fileCandidate = candidate(source, timeseries, evaluate)
        return when (source) {
            DcfSource.SecEdgar -> DcfSourceSelectionPolicy.select(sec = fileCandidate)
            else -> DcfSourceSelectionPolicy.select(yahoo = fileCandidate)
        }
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
    ): DcfSourceCandidate {
        val evaluation = timeseries?.let { value -> runCatching { evaluate(value) } }
        val analysis = evaluation?.getOrNull()
        val reasons = if (evaluation?.isFailure == true) {
            listOf(
                ProviderDecisionReason(
                    code = ProviderDecisionReasonCode.MissingDriverEvidence,
                    provider = source,
                    upstreamStatus = evaluation.exceptionOrNull()?.message,
                ),
            )
        } else {
            emptyList()
        }
        return DcfSourceCandidate(
            source = source,
            timeseries = timeseries,
            analysis = analysis,
            reasons = reasons,
        )
    }
}
