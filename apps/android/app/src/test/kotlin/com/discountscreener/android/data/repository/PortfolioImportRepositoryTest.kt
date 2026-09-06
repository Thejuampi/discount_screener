package com.discountscreener.android.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.discountscreener.android.data.persistence.SQLiteStateStore
import com.discountscreener.android.data.profile.ProfileCatalog
import com.discountscreener.android.data.profile.UniverseCatalog
import com.discountscreener.android.data.remote.ProviderComponentState
import com.discountscreener.android.data.remote.ProviderCoverage
import com.discountscreener.android.data.remote.ProviderFetchResult
import com.discountscreener.android.data.remote.QuoteBatchEntry
import com.discountscreener.android.data.remote.YahooFinanceClient
import com.discountscreener.android.data.remote.offlineHttpClient
import com.discountscreener.core.model.ExternalValuationSignal
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.MarketSnapshot
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.ViewFilter
import com.discountscreener.android.data.earnings.EarningsEventRecorder
import com.discountscreener.core.earnings.EarningsEventLog
import com.discountscreener.core.earnings.EarningsEventRecord
import com.discountscreener.core.earnings.PreReport
import com.discountscreener.core.earnings.ReportTiming
import com.discountscreener.core.earnings.decisionOf
import com.discountscreener.core.portfolio.ImportPlan
import com.discountscreener.core.portfolio.PortfolioLot
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PortfolioImportRepositoryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun plan_does_not_write_lots() = runTest(dispatcher) {
        var store = SQLiteStateStore(context, ioDispatcher = dispatcher)
        try {
            var repository = repository(store)
            repository.planPortfolioCsv(JPM)

            assertEquals(emptyList<PortfolioLot>(), store.loadPortfolioBook().first)
        } finally {
            store.close()
        }
    }

    @Test
    fun confirm_writes_lots_that_survive_reopen() = runTest(dispatcher) {
        var store = SQLiteStateStore(context, ioDispatcher = dispatcher)
        try {
            var repository = repository(store)
            var plan = repository.planPortfolioCsv(JPM)
            repository.confirmPortfolioPlan(plan)
        } finally {
            store.close()
        }
        var again = SQLiteStateStore(context, ioDispatcher = dispatcher)
        try {
            assertEquals(100_000L, again.loadPortfolioBook().first.single { it.symbol == "AMZN" }.quantityTenThousandths)
        } finally {
            again.close()
        }
    }

    @Test
    fun cancel_leaves_the_prior_book() = runTest(dispatcher) {
        var store = SQLiteStateStore(context, ioDispatcher = dispatcher)
        try {
            store.replacePortfolioBook(listOf(PRIOR), "2026-08-01")
            var repository = repository(store)
            repository.planPortfolioCsv(JPM)

            assertEquals(listOf(PRIOR), store.loadPortfolioBook().first)
        } finally {
            store.close()
        }
    }

    @Test
    fun confirm_merge_advances_as_of() = runTest(dispatcher) {
        var store = SQLiteStateStore(context, ioDispatcher = dispatcher)
        try {
            var repository = repository(store)
            repository.confirmPortfolioPlan(repository.planPortfolioCsv(JPM))
            repository.confirmPortfolioPlan(repository.planPortfolioCsv(CHASE_BUY))

            assertEquals("2026-09-01", store.loadPortfolioBook().second)
        } finally {
            store.close()
        }
    }

    @Test
    fun a_second_plan_after_confirm_keeps_qty() = runTest(dispatcher) {
        var store = SQLiteStateStore(context, ioDispatcher = dispatcher)
        try {
            var repository = repository(store)
            repository.confirmPortfolioPlan(repository.planPortfolioCsv(JPM))
            repository.confirmPortfolioPlan(repository.planPortfolioCsv(CHASE_BUY))
            var again = repository.planPortfolioCsv(CHASE_BUY) as ImportPlan.ConfirmTradesMerge

            assertEquals(200_000L, again.positions.single { it.symbol == "AMZN" }.quantityTenThousandths)
        } finally {
            store.close()
        }
    }

    @Test
    fun confirm_without_applied_trades_keeps_as_of() = runTest(dispatcher) {
        var store = SQLiteStateStore(context, ioDispatcher = dispatcher)
        try {
            var repository = repository(store)
            repository.confirmPortfolioPlan(repository.planPortfolioCsv(JPM))
            repository.confirmPortfolioPlan(repository.planPortfolioCsv(CHASE_SAME_DAY))

            assertEquals("2026-08-31", store.loadPortfolioBook().second)
        } finally {
            store.close()
        }
    }

    @Test
    fun earnings_events_mark_held_lots() = runTest(dispatcher) {
        var store = SQLiteStateStore(context, ioDispatcher = dispatcher)
        try {
            var log = EarningsEventLog(File(context.cacheDir, "events.jsonl"))
            var day = Instant.ofEpochSecond(1_700_000_000L).atZone(ZoneOffset.UTC).toLocalDate()
                .plusDays(3).toEpochDay()
            var pre = PreReport(
                symbol = "AMZN",
                reportEpochDay = day,
                timing = ReportTiming.AfterClose,
                priceCents = 10_000L,
            )
            log.append(EarningsEventRecord(pre = pre, decision = decisionOf(pre)))
            var repository = repository(
                store,
                EarningsEventRecorder(
                    log = log,
                    chains = { _, _ -> null },
                    consensus = { null },
                    closes = { emptyList() },
                    nowProvider = { 1_700_000_000L },
                ),
            )
            repository.confirmPortfolioPlan(repository.planPortfolioCsv(JPM))

            assertEquals(true, repository.earningsEvents().upcoming.single().held)
        } finally {
            store.close()
        }
    }

    @Test
    fun confirm_then_snapshot_marks_held_tracked_rows() = runTest(dispatcher) {
        var store = SQLiteStateStore(context, ioDispatcher = dispatcher)
        try {
            var repository = repository(store)
            repository.bootstrap(ViewFilter(), null, ChartRange.Year, OpportunityScoringModel.Legacy)
            repository.confirmPortfolioPlan(repository.planPortfolioCsv(JPM))

            assertEquals(
                true,
                repository.currentSnapshot(
                    ViewFilter(),
                    null,
                    ChartRange.Year,
                    OpportunityScoringModel.Legacy,
                ).trackedRows.single { it.symbol == "AMZN" }.held,
            )
        } finally {
            store.close()
        }
    }

    @Test
    fun bootstrap_pins_held_tracked_rows() = runTest(dispatcher) {
        var store = SQLiteStateStore(context, ioDispatcher = dispatcher)
        try {
            store.replacePortfolioBook(listOf(PRIOR), "2026-08-31")
            var snapshot = repository(store).bootstrap(
                ViewFilter(),
                null,
                ChartRange.Year,
                OpportunityScoringModel.Legacy,
            )

            assertEquals("AMZN", snapshot.trackedRows.first().symbol)
        } finally {
            store.close()
        }
    }

    private fun repository(
        store: SQLiteStateStore,
        recorder: EarningsEventRecorder? = null,
    ) = DefaultDashboardRepository(
        stateStore = store,
        profileCatalog = ProfileCatalog(context.assets),
        yahooClient = QuietYahoo(),
        universeCatalog = UniverseCatalog(context.assets),
        nowProvider = { 1_700_000_000L },
        ioDispatcher = dispatcher,
        defaultProfile = DefaultDashboardRepository.QA_PROFILE,
        earningsEventRecorder = recorder,
    )

    private companion object {
        const val DB_NAME = "discount_screener_state.sqlite3"
        val PRIOR = PortfolioLot("AMZN", 100_000L, 20_000L, "2024-01-15")
        const val JPM =
            "Asset Class,Ticker,Quantity,Unit Cost,As of\nEquity,AMZN,10,200.00,08/31/2026\n"
        const val CHASE_BUY =
            "Trade Date,Type,Ticker,Price USD,Quantity\n09/01/2026,Buy,AMZN,220,10\n"
        const val CHASE_SAME_DAY =
            "Trade Date,Type,Ticker,Price USD,Quantity\n08/31/2026,Buy,AMZN,220,10\n"
    }
}

private class QuietYahoo : YahooFinanceClient(httpClient = offlineHttpClient()) {
    override suspend fun fetchQuotes(symbols: List<String>): Map<String, QuoteBatchEntry> =
        symbols.associateWith { symbol ->
            QuoteBatchEntry(
                symbol = symbol,
                companyName = symbol,
                marketPriceCents = 10_000L,
                profitable = true,
                nextEarningsEpoch = null,
            )
        }

    override suspend fun fetchSymbol(symbol: String): ProviderFetchResult {
        var price = 10_000L
        return ProviderFetchResult(
            symbol = symbol,
            snapshot = MarketSnapshot(
                symbol = symbol,
                companyName = symbol,
                profitable = true,
                marketPriceCents = price,
                intrinsicValueCents = 12_500L,
            ),
            companyName = symbol,
            externalSignal = ExternalValuationSignal(
                symbol = symbol,
                fairValueCents = 12_500L,
                ageSeconds = 0,
            ),
            fundamentals = null,
            coverage = ProviderCoverage(
                core = ProviderComponentState.Fresh,
                external = ProviderComponentState.Fresh,
                fundamentals = ProviderComponentState.Missing,
            ),
            diagnostics = emptyList(),
        )
    }

    override suspend fun fetchHistoricalCandles(symbol: String, range: ChartRange): List<HistoricalCandle> =
        listOf(
            HistoricalCandle(
                epochSeconds = 1_699_999_000L,
                openCents = 9_900,
                highCents = 10_100,
                lowCents = 9_800,
                closeCents = 10_000,
                volume = 1_000,
            ),
        )
}
