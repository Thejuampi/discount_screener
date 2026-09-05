package com.discountscreener.android.app

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.discountscreener.android.data.remote.SecEdgarTimeseriesProvider
import com.discountscreener.android.data.repository.DefaultDashboardRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DiscountScreenerAppContainerTest {
    @Test
    fun default_secondary_timeseries_provider_is_sec_edgar() {
        assertTrue(defaultSecondaryTimeseriesProvider() is SecEdgarTimeseriesProvider)
    }

    /**
     * A background run builds its own container and walks away from it. Whatever that container
     * started has to stop with it, or every run leaves one more forever-loop behind.
     */
    @Test
    fun a_container_that_shut_down_is_running_nothing() {
        var container = DiscountScreenerAppContainer(
            ApplicationProvider.getApplicationContext<Context>(),
        )
        container.shutdown()

        assertFalse(container.runningBackgroundWork())
    }

    @Test
    fun qa_install_selects_the_capped_qa_universe_without_a_data_wipe() {
        assertEquals(DefaultDashboardRepository.QA_PROFILE, startupProfile(qaUniverse = true))
    }

    @Test
    fun regular_install_cold_starts_the_product_universe() {
        assertEquals(
            DefaultDashboardRepository.PRODUCT_DEFAULT_PROFILE,
            startupProfile(qaUniverse = false),
        )
    }
}
