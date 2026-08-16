package com.discountscreener.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CouponResolutionTest {
    @Test
    fun filed_coupon_stays_filed() {
        var years = resolveCoupons(
            listOf(year("2023-09-30", filed = 3_000.0, debt = 100_000.0)),
        )
        assertEquals(CouponKind.Filed, years.single().kind)
    }

    @Test
    fun no_period_debt_is_zero() {
        var years = resolveCoupons(
            listOf(year("2023-09-30", filed = null, debt = 0.0)),
        )
        assertEquals(CouponKind.Zero, years.single().kind)
    }

    @Test
    fun dense_own_series_one_hole_is_high() {
        var hole = resolveCoupons(denseOwnSeries(holes = 1)).single { it.period == "2025-09-27" }
        assertEquals(CouponConfidence.High, hole.confidence)
    }

    @Test
    fun apple_shape_two_trailing_holes_is_medium() {
        var hole = resolveCoupons(appleShape()).single { it.period == "2024-09-28" }
        assertEquals(CouponConfidence.Medium, hole.confidence)
    }

    @Test
    fun own_rate_scales_with_this_year_debt() {
        var hole = resolveCoupons(
            listOf(
                year("2023-09-30", filed = 4_000.0, debt = 100_000.0),
                year("2024-09-28", filed = null, debt = 90_000.0),
            ),
        ).single { it.period == "2024-09-28" }
        assertEquals(3_600.0, hole.dollars)
    }

    @Test
    fun no_own_points_without_peers_is_absent() {
        var hole = resolveCoupons(
            listOf(year("2024-09-28", filed = null, debt = 90_000.0)),
        ).single()
        assertEquals(CouponKind.Absent, hole.kind)
    }

    @Test
    fun no_own_points_with_three_peers_uses_peer_median() {
        var hole = resolveCoupons(
            years = listOf(year("2024-09-28", filed = null, debt = 100_000.0)),
            peers = listOf(
                PeerCouponEvidence("AAA", 3_000.0, 100_000.0),
                PeerCouponEvidence("BBB", 4_000.0, 100_000.0),
                PeerCouponEvidence("CCC", 5_000.0, 100_000.0),
            ),
        ).single()
        assertEquals(4_000.0, hole.dollars)
    }

    @Test
    fun filed_on_the_same_period_replaces_an_estimate() {
        var filled = resolveCoupons(
            listOf(
                year("2023-09-30", filed = 4_000.0, debt = 100_000.0),
                year("2024-09-28", filed = 3_500.0, debt = 90_000.0),
            ),
        ).single { it.period == "2024-09-28" }
        assertEquals(CouponKind.Filed, filled.kind)
    }

    @Test
    fun one_own_point_is_low_confidence() {
        var hole = resolveCoupons(
            listOf(
                year("2023-09-30", filed = 4_000.0, debt = 100_000.0),
                year("2024-09-28", filed = null, debt = 90_000.0),
            ),
        ).single { it.period == "2024-09-28" }
        assertEquals(CouponConfidence.Low, hole.confidence)
    }

    @Test
    fun similar_issuers_keep_the_same_industry() {
        var peers = similarIssuerCoupons(
            subjectSector = "Technology",
            subjectIndustry = "Consumer Electronics",
            others = listOf(
                IssuerCouponSample("MSFT", "Technology", "Software", 1.0, 10.0),
                IssuerCouponSample("HPQ", "Technology", "Consumer Electronics", 2.0, 20.0),
            ),
        )
        assertEquals(listOf("HPQ"), peers.map { it.symbol })
    }

    @Test
    fun similar_issuers_fall_back_to_sector() {
        var peers = similarIssuerCoupons(
            subjectSector = "Technology",
            subjectIndustry = "Consumer Electronics",
            others = listOf(
                IssuerCouponSample("MSFT", "Technology", "Software", 1.0, 10.0),
            ),
        )
        assertEquals(listOf("MSFT"), peers.map { it.symbol })
    }

    @Test
    fun last_filed_coupon_ignores_a_later_hole() {
        var sample = lastFiledIssuerSample(
            symbol = "MSFT",
            sectorName = "Technology",
            industryName = "Software",
            years = listOf(
                year("2023-06-30", filed = 2_000.0, debt = 50_000.0),
                year("2024-06-30", filed = null, debt = 48_000.0),
            ),
        )
        assertEquals(2_000.0, sample?.couponDollars)
    }

    @Test
    fun last_filed_coupon_is_null_when_every_year_is_a_hole() {
        var sample = lastFiledIssuerSample(
            symbol = "NEW",
            sectorName = "Technology",
            industryName = "Software",
            years = listOf(year("2024-12-31", filed = null, debt = 10_000.0)),
        )
        assertNull(sample)
    }

    private fun year(period: String, filed: Double?, debt: Double?): CouponYearInput =
        CouponYearInput(period = period, filedCouponDollars = filed, debtDollars = debt)

    private fun denseOwnSeries(holes: Int): List<CouponYearInput> {
        var filed = listOf(
            year("2020-09-26", 3_000.0, 100_000.0),
            year("2021-09-25", 3_000.0, 100_000.0),
            year("2022-09-24", 3_000.0, 100_000.0),
            year("2023-09-30", 3_000.0, 100_000.0),
            year("2024-09-28", 3_000.0, 100_000.0),
        )
        var missing = listOf(year("2025-09-27", null, 100_000.0))
        return if (holes == 1) filed + missing else filed
    }

    private fun appleShape(): List<CouponYearInput> = listOf(
        year("2020-09-26", 3_000.0, 95_000.0),
        year("2021-09-25", 3_000.0, 95_000.0),
        year("2022-09-24", 3_000.0, 95_000.0),
        year("2023-09-30", 3_000.0, 95_000.0),
        year("2024-09-28", null, 95_000.0),
        year("2025-09-27", null, 95_000.0),
    )
}
