package com.discountscreener.android.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.discountscreener.android.data.persistence.SQLiteStateStore
import com.discountscreener.android.data.profile.ProfileCatalog
import com.discountscreener.android.data.profile.UniverseCatalog
import com.discountscreener.android.data.remote.FundamentalTimeseriesProvider
import com.discountscreener.android.data.remote.ProviderComponentState
import com.discountscreener.android.data.remote.ProviderCoverage
import com.discountscreener.android.data.remote.ProviderFetchResult
import com.discountscreener.android.data.remote.QuoteBatchEntry
import com.discountscreener.android.data.remote.ResidualCompanyFactsProvider
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.FundamentalTimeseries
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.MarketSnapshot
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.ViewFilter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ResidualChainGenerationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val filter = ViewFilter()
    private val model = OpportunityScoringModel.Legacy

    @Before
    fun setUp() {
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun old_financial_detail_cannot_mark_sec_attempted_after_profile_switch() = runBlocking {
        val store = SQLiteStateStore(context, databaseFileName = DB_NAME)
        val secondary = HeldResidualProvider()
        val repository = DefaultDashboardRepository(
            stateStore = store,
            profileCatalog = ProfileCatalog(context.assets),
            yahooClient = BankYahoo(),
            universeCatalog = UniverseCatalog(context.assets),
            secondaryTimeseriesProvider = secondary,
            defaultProfile = "qa",
        )
        try {
            repository.bootstrap(filter, null, ChartRange.Year, model)
            val oldDetail = async { repository.ensureDetailLoaded("JPM", filter, ChartRange.Year, model) }
            withTimeout(5_000) { secondary.started.await() }

            repository.selectProfile("merval", filter, ChartRange.Year, model)
            secondary.release.complete(Unit)
            withTimeout(5_000) { oldDetail.await() }

            val field = DefaultDashboardRepository::class.java.getDeclaredField("residualChainRan")
            field.isAccessible = true
            assertTrue((field.get(repository) as Set<*>).isEmpty())
        } finally {
            secondary.release.complete(Unit)
            repository.clearAllData()
            store.close()
        }
    }

    private class HeldResidualProvider : FundamentalTimeseriesProvider, ResidualCompanyFactsProvider {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        override suspend fun fetch(symbol: String): FundamentalTimeseries? = null

        override suspend fun fetchSievedCompanyFacts(symbol: String): String? {
            started.complete(Unit)
            release.await()
            return null
        }
    }

    private class BankYahoo : OfflineYahooClient() {
        override suspend fun fetchQuotes(symbols: List<String>): Map<String, QuoteBatchEntry> = emptyMap()

        override suspend fun fetchSymbol(symbol: String): ProviderFetchResult = ProviderFetchResult(
            symbol = symbol,
            snapshot = MarketSnapshot(
                symbol = symbol,
                companyName = "$symbol Bank",
                profitable = true,
                marketPriceCents = 10_000L,
                intrinsicValueCents = 12_000L,
            ),
            externalSignal = null,
            fundamentals = FundamentalSnapshot(
                symbol = symbol,
                sectorName = "Financial Services",
                industryName = "Banks - Diversified",
                marketCapDollars = 400_000_000_000L,
                totalDebtDollars = 300_000_000_000L,
                totalCashDollars = 500_000_000_000L,
                sharesOutstanding = 2_900_000_000L,
                betaMillis = 1_100,
            ),
            coverage = ProviderCoverage(
                core = ProviderComponentState.Fresh,
                external = ProviderComponentState.Missing,
                fundamentals = ProviderComponentState.Fresh,
            ),
            diagnostics = emptyList(),
        )

        override suspend fun fetchHistoricalCandles(symbol: String, range: ChartRange): List<HistoricalCandle> =
            emptyList()
    }

    private companion object {
        const val DB_NAME = "residual_chain_generation.sqlite3"
    }
}
