package com.discountscreener.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ValuationPathPolicyTest {
    @Test
    fun reinvestment_story_holds_growth_and_expands_margin() {
        var path = ValuationPathPolicy.resolveFcff(
            regime = "secular_expansion",
            rawGrowthBps = 987,
            matureCapBps = 1_570,
            cappedGrowthBps = 987,
            currentMarginBps = 1_108,
            discountBps = 1_171,
            roe0Bps = 467,
            retentionBps = 10_000,
            rfBps = 470,
            industry = "Auto Manufacturers",
            sector = "Consumer Cyclical",
        )
        assertTrue(path.holdYears >= 12)
    }

    @Test
    fun high_excess_roe_keeps_more_than_half_demonstrated_growth() {
        var path = ValuationPathPolicy.resolveFcff(
            regime = "secular_expansion",
            rawGrowthBps = 5_000,
            matureCapBps = 1_570,
            cappedGrowthBps = 2_500,
            currentMarginBps = 4_510,
            discountBps = 1_316,
            roe0Bps = 11_429,
            retentionBps = 9_939,
            rfBps = 470,
            industry = "Semiconductors",
            sector = "Technology",
        )
        assertTrue(path.holdYears >= 6)
    }

    @Test
    fun internet_content_fades_extreme_margin_toward_industry() {
        var path = ValuationPathPolicy.resolveFcff(
            regime = "secular_expansion",
            rawGrowthBps = 2_200,
            matureCapBps = 1_570,
            cappedGrowthBps = 1_570,
            currentMarginBps = 5_055,
            discountBps = 961,
            roe0Bps = 2_985,
            retentionBps = 9_209,
            rfBps = 470,
            industry = "Internet Content & Information",
            sector = "Communication Services",
        )
        assertTrue(path.stableMarginBps < 4_000)
    }

    @Test
    fun quality_compounder_uses_a_tighter_discount() {
        var path = ValuationPathPolicy.resolveFcff(
            regime = "stable_operating",
            rawGrowthBps = 422,
            matureCapBps = 1_570,
            cappedGrowthBps = 422,
            currentMarginBps = 2_829,
            discountBps = 964,
            roe0Bps = 14_875,
            retentionBps = 8_796,
            rfBps = 470,
            industry = "Consumer Electronics",
            sector = "Technology",
        )
        assertTrue(path.discountBps < 800)
    }

    @Test
    fun bank_below_through_cycle_starts_at_the_floor() {
        var path = ResidualPathPolicy.resolve(
            roe0Bps = 933,
            costOfEquityBps = 947,
            industry = "Banks - Diversified",
            sector = "Financial Services",
        )
        assertEquals(1_300, path.startingRoeBps)
    }

    @Test
    fun property_casualty_keeps_a_tight_franchise_spread() {
        var path = ResidualPathPolicy.resolve(
            roe0Bps = 2_211,
            costOfEquityBps = 687,
            industry = "Insurance - Diversified",
            sector = "Financial Services",
        )
        assertEquals(120, path.franchiseSpreadBps)
    }

    @Test
    fun managed_care_low_teens_roe_uses_a_tighter_spread() {
        var path = ResidualPathPolicy.resolve(
            roe0Bps = 1_156,
            costOfEquityBps = 696,
            industry = "Healthcare Plans",
            sector = "Healthcare",
        )
        assertEquals(350, path.franchiseSpreadBps)
    }

    @Test
    fun internet_retail_keeps_a_six_year_hold() {
        var path = ValuationPathPolicy.resolveFcff(
            regime = "secular_expansion",
            rawGrowthBps = 1_141,
            matureCapBps = 1_570,
            cappedGrowthBps = 1_141,
            currentMarginBps = 1_332,
            discountBps = 1_033,
            roe0Bps = 3_056,
            retentionBps = 10_000,
            rfBps = 470,
            industry = "Internet Retail",
            sector = "Consumer Cyclical",
        )
        assertTrue(path.holdYears >= 6)
    }

    @Test
    fun internet_content_below_target_expands_margin() {
        var path = ValuationPathPolicy.resolveFcff(
            regime = "secular_expansion",
            rawGrowthBps = 1_182,
            matureCapBps = 1_570,
            cappedGrowthBps = 1_182,
            currentMarginBps = 3_589,
            discountBps = 968,
            roe0Bps = 4_868,
            retentionBps = 9_574,
            rfBps = 470,
            industry = "Internet Content & Information",
            sector = "Communication Services",
        )
        assertTrue(path.stableMarginBps >= 3_700)
    }

    @Test
    fun software_industry_target_stays_below_extreme_saas() {
        var prior = IndustryOperatingPathPolicy.resolve(
            "Software - Infrastructure",
            "Technology",
        )
        assertEquals(3_200, prior.targetFcffMarginBps)
    }
}
