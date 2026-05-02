package com.discountscreener.android.data.remote

import com.discountscreener.core.model.FundamentalTimeseries

interface FundamentalTimeseriesProvider {
    suspend fun fetch(symbol: String): FundamentalTimeseries?
}
