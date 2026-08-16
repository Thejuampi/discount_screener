package com.discountscreener.core.engine

import com.discountscreener.core.harness.QuantHarness
import com.discountscreener.core.harness.QuantHarnessCases
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class IssuerMarketYieldTest {
    @Test
    fun attach_writes_bps_on_the_latest_tax_period() {
        var pack = QuantHarness.hardcoded(QuantHarnessCases.AAPL_COUPON_HOLES).load("AAPL")
        var attached = attachMarketYield(pack.timeseries, IssuerYieldPoint(yieldBps = 520))
        assertEquals(520.0, attached.marketYieldBps.single().value)
    }

    @Test
    fun attach_refuses_a_yield_outside_policy_range() {
        var pack = QuantHarness.hardcoded(QuantHarnessCases.AAPL_COUPON_HOLES).load("AAPL")
        assertFailsWith<IllegalArgumentException> {
            attachMarketYield(pack.timeseries, IssuerYieldPoint(yieldBps = 6_000))
        }
    }

    @Test
    fun select_takes_median_of_usd_four_to_fifteen_year_yields() {
        var point = selectIssuerMarketYield(
            QuantHarnessCases.AAPL_INSTRUMENT_QUOTES,
            asOfDate = "2026-08-16",
        )
        assertEquals(471, point?.yieldBps)
    }

    @Test
    fun select_names_the_preferred_band() {
        var point = selectIssuerMarketYield(
            QuantHarnessCases.AAPL_INSTRUMENT_QUOTES,
            asOfDate = "2026-08-16",
        )
        assertEquals("IssuerInstrumentYield:usd_4_15y_median", point?.concept)
    }

    @Test
    fun select_leaves_align_date_empty() {
        var point = selectIssuerMarketYield(
            QuantHarnessCases.AAPL_INSTRUMENT_QUOTES,
            asOfDate = "2026-08-16",
        )
        assertEquals(null, point?.asOfDate)
    }

    @Test
    fun select_drops_a_foreign_currency_quote() {
        var point = selectIssuerMarketYield(
            listOf(IssuerInstrumentQuote(yieldBps = 67, maturityDate = "2030-02-25", currency = "CHF")),
            asOfDate = "2026-08-16",
        )
        assertNull(point)
    }

    @Test
    fun select_falls_back_to_outstanding_usd_when_the_band_is_empty() {
        var point = selectIssuerMarketYield(
            listOf(IssuerInstrumentQuote(yieldBps = 594, maturityDate = "2049-09-11", currency = "USD")),
            asOfDate = "2026-08-16",
        )
        assertEquals("IssuerInstrumentYield:usd_outstanding_median", point?.concept)
    }

    @Test
    fun select_returns_empty_when_every_quote_is_matured() {
        var point = selectIssuerMarketYield(
            listOf(IssuerInstrumentQuote(yieldBps = 400, maturityDate = "2026-08-04", currency = "USD")),
            asOfDate = "2026-08-16",
        )
        assertNull(point)
    }
}
