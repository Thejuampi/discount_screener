package com.discountscreener.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ValuationPolicyTest {
    @Test
    fun industry_path_follows_the_active_book() {
        var patched = ValuationPolicy.current.withAutoTarget(777)
        ValuationPolicy.use(patched) {
            assertEquals(
                777,
                IndustryOperatingPathPolicy.resolve("Auto Manufacturers", null).targetFcffMarginBps,
            )
        }
    }

    @Test
    fun sustaining_renewal_follows_the_active_book() {
        var patched = ValuationPolicy.current.withSustainingRenewal(2_222)
        ValuationPolicy.use(patched) {
            assertEquals(2_222, SustainingCapex.ASSET_RENEWAL_RATE_BPS)
        }
    }

    @Test
    fun coverage_spread_follows_the_active_book() {
        var patched = ValuationPolicy.current.withCoverageDefault(9_999)
        ValuationPolicy.use(patched) {
            assertEquals(9_999, CoverageCreditPolicy.spreadBps(0.1))
        }
    }

    @Test
    fun missing_required_key_fails_closed() {
        assertFailsWith<IllegalStateException> {
            ValuationPolicyBook.parse("version: x\n")
        }
    }

    @Test
    fun cross_flipped_bars_follows_the_book() {
        var patched = ValuationPolicy.current.withCrossFlippedBars(8)
        ValuationPolicy.use(patched) {
            assertEquals(8, ValuationPolicy.current.cross.flippedBarsMax)
        }
    }

    @Test
    fun missing_cross_section_fails_closed() {
        var yaml = ValuationPolicyBook::class.java
            .getResource("/valuation-policy.yaml")!!
            .readText()
            .replace(Regex("(?s)\ncross:.*?(?=\n[a-z_]+:)"), "\n")
        assertFailsWith<IllegalStateException> {
            ValuationPolicyBook.parse(yaml)
        }
    }
}
