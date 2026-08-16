package com.discountscreener.android.app

import com.discountscreener.android.data.remote.SecEdgarTimeseriesProvider
import com.discountscreener.android.data.repository.DefaultDashboardRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscountScreenerAppContainerTest {
    @Test
    fun default_secondary_timeseries_provider_is_sec_edgar() {
        assertTrue(defaultSecondaryTimeseriesProvider() is SecEdgarTimeseriesProvider)
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
