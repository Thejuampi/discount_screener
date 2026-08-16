package com.discountscreener.core.harness

import com.discountscreener.core.engine.RF_SOURCE_FRED_DGS10
import kotlin.test.Test
import kotlin.test.assertEquals

class FredDgs10LiveClientTest {
    @Test
    fun fixture_csv_sets_fred_source() {
        var client = FredDgs10LiveClient(
            transport = FixtureFredCsvTransport("observation_date,DGS10\n2026-08-13,4.25\n"),
        )
        assertEquals(RF_SOURCE_FRED_DGS10, client.fetch().marketParams.rfSource)
    }

    @Test
    fun fixture_csv_reads_425_bps() {
        var client = FredDgs10LiveClient(
            transport = FixtureFredCsvTransport("observation_date,DGS10\n2026-08-13,4.25\n"),
        )
        assertEquals(425, client.fetch().marketParams.rfBps)
    }
}
