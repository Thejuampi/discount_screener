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
    fun bank_far_below_through_cycle_cannot_jump_the_full_floor() {
        var path = ResidualPathPolicy.resolve(
            roe0Bps = 591,
            costOfEquityBps = 927,
            industry = "Banks - Diversified",
            sector = "Financial Services",
        )
        assertTrue(path.startingRoeBps < 1_200)
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
    fun managed_care_mid_teens_roe_is_not_a_wide_franchise() {
        var path = ResidualPathPolicy.resolve(
            roe0Bps = 1_578,
            costOfEquityBps = 806,
            industry = "Healthcare Plans",
            sector = "Healthcare",
        )
        assertEquals(350, path.franchiseSpreadBps)
    }

    @Test
    fun managed_care_high_roe_keeps_the_wide_spread() {
        var path = ResidualPathPolicy.resolve(
            roe0Bps = 2_214,
            costOfEquityBps = 788,
            industry = "Healthcare Plans",
            sector = "Healthcare",
        )
        assertEquals(2_000, path.franchiseSpreadBps)
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
    fun below_wacc_internet_retail_does_not_expand_to_industry() {
        var path = ValuationPathPolicy.resolveFcff(
            regime = "secular_expansion",
            rawGrowthBps = 2_955,
            matureCapBps = 1_570,
            cappedGrowthBps = 1_570,
            currentMarginBps = 1_690,
            discountBps = 1_155,
            roe0Bps = 889,
            retentionBps = 10_000,
            rfBps = 470,
            industry = "Internet Retail",
            sector = "Consumer Cyclical",
            capexIntensityBps = 408,
        )
        assertTrue(path.stableMarginBps <= 1_690)
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
    fun oil_ep_has_an_industry_margin_target() {
        var prior = IndustryOperatingPathPolicy.resolve("Oil & Gas E&P", "Energy")
        assertEquals("oil_ep", prior.id)
    }

    @Test
    fun oil_ep_cyclical_uses_through_cycle_growth_floor() {
        var path = ValuationPathPolicy.resolveFcff(
            regime = "cyclical_or_transition",
            rawGrowthBps = 100,
            matureCapBps = 1_570,
            cappedGrowthBps = 100,
            currentMarginBps = 1_700,
            discountBps = 756,
            roe0Bps = 2_251,
            retentionBps = 6_860,
            rfBps = 470,
            industry = "Oil & Gas E&P",
            sector = "Energy",
        )
        assertTrue(path.usedGrowthBps >= 300)
    }

    @Test
    fun asset_light_low_roe_is_not_a_reinvestment_story() {
        var path = ValuationPathPolicy.resolveFcff(
            regime = "secular_expansion",
            rawGrowthBps = 2_955,
            matureCapBps = 1_570,
            cappedGrowthBps = 1_570,
            currentMarginBps = 1_690,
            discountBps = 1_155,
            roe0Bps = 889,
            retentionBps = 10_000,
            rfBps = 470,
            industry = "Internet Retail",
            sector = "Consumer Cyclical",
            capexIntensityBps = 164,
        )
        assertTrue(path.holdYears < 12)
    }

    @Test
    fun weak_franchise_cannot_floor_growth_at_the_mature_cap() {
        var path = ValuationPathPolicy.resolveFcff(
            regime = "secular_expansion",
            rawGrowthBps = 1_635,
            matureCapBps = 1_570,
            cappedGrowthBps = 1_570,
            currentMarginBps = 2_841,
            discountBps = 957,
            roe0Bps = 1_086,
            retentionBps = 10_000,
            rfBps = 470,
            industry = "Software - Application",
            sector = "Technology",
        )
        assertTrue(path.usedGrowthBps < 1_200)
    }

    @Test
    fun software_industry_target_stays_below_extreme_saas() {
        var prior = IndustryOperatingPathPolicy.resolve(
            "Software - Infrastructure",
            "Technology",
        )
        assertEquals(3_200, prior.targetFcffMarginBps)
    }

    @Test
    fun auto_through_cycle_margin_is_three_percent() {
        var prior = IndustryOperatingPathPolicy.resolve(
            "Auto Manufacturers",
            "Consumer Cyclical",
        )
        assertEquals(300, prior.targetFcffMarginBps)
    }

    @Test
    fun cyclical_auto_fades_to_through_cycle_margin() {
        var path = ValuationPathPolicy.resolveFcff(
            regime = "cyclical_or_transition",
            rawGrowthBps = 300,
            matureCapBps = 1_570,
            cappedGrowthBps = 300,
            currentMarginBps = 500,
            discountBps = 900,
            roe0Bps = 1_200,
            retentionBps = 5_000,
            rfBps = 470,
            industry = "Auto Manufacturers",
            sector = "Consumer Cyclical",
        )
        assertEquals(300, path.stableMarginBps)
    }
}
