package com.discountscreener.android.app

import org.junit.Assert.assertNull
import org.junit.Test

class DiscountScreenerAppContainerTest {
    @Test
    fun default_secondary_timeseries_provider_is_disabled() {
        assertNull(defaultSecondaryTimeseriesProvider())
    }
}