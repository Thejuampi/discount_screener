package com.discountscreener.android.data.remote

/** SEC companyfacts slim enough for residual-income drivers. */
interface ResidualCompanyFactsProvider {
    suspend fun fetchSievedCompanyFacts(symbol: String): String?
}
