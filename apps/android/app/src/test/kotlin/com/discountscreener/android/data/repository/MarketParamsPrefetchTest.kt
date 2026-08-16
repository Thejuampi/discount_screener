package com.discountscreener.android.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.discountscreener.android.data.persistence.SQLiteStateStore
import com.discountscreener.android.data.profile.ProfileCatalog
import com.discountscreener.android.data.profile.UniverseCatalog
import com.discountscreener.android.data.remote.YahooFinanceClient
import com.discountscreener.core.engine.MarketParams
import com.discountscreener.core.engine.MarketParamsSource
import com.discountscreener.core.engine.RF_SOURCE_YAHOO_TNX
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MarketParamsPrefetchTest {
    @Test
    fun prefetch_replaces_bootstrap_with_the_observed_source() = runBlocking {
        var source = MarketParamsSource {
            MarketParams.observed(
                rfBps = 432,
                asOfEpochMillis = 1_700_000_000_000L,
                rfSource = RF_SOURCE_YAHOO_TNX,
            )
        }
        var store = SQLiteStateStore(ApplicationProvider.getApplicationContext<Context>())
        try {
            var repository = DefaultDashboardRepository(
                stateStore = store,
                profileCatalog = ProfileCatalog(
                    ApplicationProvider.getApplicationContext<Context>().assets,
                ),
                yahooClient = YahooFinanceClient(),
                universeCatalog = UniverseCatalog(
                    ApplicationProvider.getApplicationContext<Context>().assets,
                ),
                ioDispatcher = Dispatchers.IO,
                defaultProfile = DefaultDashboardRepository.QA_PROFILE,
                marketParamsSource = source,
            )
            delay(500)
            assertEquals(RF_SOURCE_YAHOO_TNX, repository.peekMarketParams().rfSource)
        } finally {
            store.close()
        }
    }
}
