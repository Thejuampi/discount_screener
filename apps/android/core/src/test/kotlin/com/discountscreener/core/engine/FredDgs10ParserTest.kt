package com.discountscreener.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FredDgs10ParserTest {
    @Test
    fun latest_numeric_row_is_425_bps() {
        var csv = """
            observation_date,DGS10
            2026-08-12,4.22
            2026-08-13,4.25
        """.trimIndent()
        assertEquals(425, FredDgs10Parser.latest(csv).yieldBps)
    }

    @Test
    fun trailing_missing_dot_is_skipped() {
        var csv = """
            observation_date,DGS10
            2026-08-13,4.18
            2026-08-14,.
        """.trimIndent()
        assertEquals(418, FredDgs10Parser.latest(csv).yieldBps)
    }

    @Test
    fun empty_csv_fails_closed() {
        assertFailsWith<IllegalArgumentException> {
            FredDgs10Parser.latest("observation_date,DGS10\n")
        }
    }

    @Test
    fun yield_below_50_bps_fails_closed() {
        assertFailsWith<IllegalArgumentException> {
            FredDgs10Parser.latest("observation_date,DGS10\n2026-08-13,0.40\n")
        }
    }

    @Test
    fun yield_above_20_percent_fails_closed() {
        assertFailsWith<IllegalArgumentException> {
            FredDgs10Parser.latest("observation_date,DGS10\n2026-08-13,21.00\n")
        }
    }
}
