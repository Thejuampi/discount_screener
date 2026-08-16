package com.discountscreener.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class YahooTnxParserTest {
    @Test
    fun regular_market_price_is_425_bps() {
        var json = """{"chart":{"result":[{"meta":{"regularMarketPrice":4.25,"regularMarketTime":1700000000}}]}}"""
        assertEquals(425, YahooTnxParser.parse(json).yieldBps)
    }

    @Test
    fun last_close_is_used_when_price_is_missing() {
        var json = """{"chart":{"result":[{"meta":{},"indicators":{"quote":[{"close":[4.10,4.18,null]}]}}]}}"""
        assertEquals(418, YahooTnxParser.parse(json).yieldBps)
    }

    @Test
    fun empty_chart_fails_closed() {
        assertFailsWith<IllegalArgumentException> {
            YahooTnxParser.parse("""{"chart":{"result":[]}}""")
        }
    }
}
