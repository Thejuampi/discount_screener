package com.discountscreener.android.data.market

import com.discountscreener.core.model.HistoricalCandle

/**
 * Where the daily bars behind a market reading are kept, so the retrospective has dated prices.
 *
 * A narrow interface rather than the store itself, and the narrowness is the point:
 * [MarketDataRepository] fetches and computes, it does not know about SQLite, and every test that
 * builds one would otherwise need a database to do it. One method, one direction, no reads.
 */
interface DailyCandleSink {
    /**
     * @return how many stored series were rebased — see the implementation's contract for what a
     *   rebase is and why it must be reported rather than done quietly.
     */
    suspend fun persistBacktestCandles(
        candlesBySymbol: Map<String, List<HistoricalCandle>>,
        capturedAtEpochSeconds: Long,
    ): Int
}
