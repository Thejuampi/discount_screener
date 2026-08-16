package com.discountscreener.core.engine

import com.discountscreener.core.harness.QuantHarness
import com.discountscreener.core.harness.QuantHarnessCases
import com.discountscreener.core.harness.QuantLiveClient
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class ModelPolishResidualLevelTest {
    @Test
    fun hardcoded_jpm_residual_premium_is_material() {
        var data = QuantHarness.hardcoded(QuantHarnessCases.JPM_BANK).load("JPM")
        var analysis = DcfAnalysisEngine.compute(
            fundamentals = data.fundamentals,
            timeseries = data.timeseries,
            marketParams = requireNotNull(data.marketParams),
        ).getOrThrow()
        var book = requireNotNull(data.fundamentals.bookValuePerShareCents)
        var premiumBps = ((analysis.baseIntrinsicValueCents - book) * 10_000L) / book
        assertTrue(premiumBps > 2_000L, "JPM premium over book is $premiumBps bps")
    }

    @Test
    fun hardcoded_jpm_keeps_through_cycle_above_two_books() {
        var data = QuantHarness.hardcoded(QuantHarnessCases.JPM_BANK).load("JPM")
        var analysis = DcfAnalysisEngine.compute(
            fundamentals = data.fundamentals,
            timeseries = data.timeseries,
            marketParams = requireNotNull(data.marketParams),
        ).getOrThrow()
        var book = requireNotNull(data.fundamentals.bookValuePerShareCents)
        assertTrue(
            analysis.baseIntrinsicValueCents > book * 2L,
            "JPM ${analysis.baseIntrinsicValueCents} cents vs book $book",
        )
    }

    @Test
    fun hardcoded_jpm_stays_below_three_and_a_fifth_books() {
        var data = QuantHarness.hardcoded(QuantHarnessCases.JPM_BANK).load("JPM")
        var analysis = DcfAnalysisEngine.compute(
            fundamentals = data.fundamentals,
            timeseries = data.timeseries,
            marketParams = requireNotNull(data.marketParams),
        ).getOrThrow()
        var book = requireNotNull(data.fundamentals.bookValuePerShareCents)
        assertTrue(analysis.baseIntrinsicValueCents < book * 16L / 5L)
    }

    @Test
    fun hardcoded_ci_residual_premium_is_material() {
        var data = QuantHarness.hardcoded(QuantHarnessCases.CI_PLAN).load("CI")
        var analysis = DcfAnalysisEngine.compute(
            fundamentals = data.fundamentals,
            timeseries = data.timeseries,
            marketParams = requireNotNull(data.marketParams),
        ).getOrThrow()
        var book = requireNotNull(data.fundamentals.bookValuePerShareCents)
        var premiumBps = ((analysis.baseIntrinsicValueCents - book) * 10_000L) / book
        assertTrue(premiumBps > 2_000L, "CI premium over book is $premiumBps bps")
    }

    @Test
    fun hardcoded_jpm_roe_equal_coe_matches_book() {
        var data = QuantHarness.hardcoded(QuantHarnessCases.JPM_BANK).load("JPM")
        var params = requireNotNull(data.marketParams)
        var coe = DcfAnalysisEngine.resolveCostOfEquity(data.fundamentals, params).costOfEquityBps
        var analysis = DcfAnalysisEngine.compute(
            fundamentals = data.fundamentals.copy(returnOnEquityBps = coe),
            timeseries = data.timeseries,
            marketParams = params,
        ).getOrThrow()
        var book = requireNotNull(data.fundamentals.bookValuePerShareCents)
        assertTrue(abs(analysis.baseIntrinsicValueCents - book) * 100L <= book)
    }

    @Test
    fun longer_fade_raises_jpm_value() {
        var data = QuantHarness.hardcoded(QuantHarnessCases.JPM_BANK).load("JPM")
        var params = requireNotNull(data.marketParams)
        var coe = DcfAnalysisEngine.resolveCostOfEquity(data.fundamentals, params).costOfEquityBps
        var book = requireNotNull(data.fundamentals.bookValuePerShareCents)
        var shares = requireNotNull(data.fundamentals.sharesOutstanding).toDouble()
        var book0 = (book / 100.0) * shares
        var five = ResidualIncomeMath.valuePerShareCents(
            book0 = book0,
            shares = shares,
            roe0Bps = requireNotNull(data.fundamentals.returnOnEquityBps),
            costOfEquityBps = coe,
            retention = requireNotNull(data.fundamentals.retentionBps) / 10_000.0,
            fadeYears = 5,
        )
        var twenty = ResidualIncomeMath.valuePerShareCents(
            book0 = book0,
            shares = shares,
            roe0Bps = requireNotNull(data.fundamentals.returnOnEquityBps),
            costOfEquityBps = coe,
            retention = requireNotNull(data.fundamentals.retentionBps) / 10_000.0,
            fadeYears = 20,
        )
        assertTrue(requireNotNull(twenty) > requireNotNull(five))
    }

    @Test
    fun twenty_year_fade_still_below_two_times_jpm_book() {
        var data = QuantHarness.hardcoded(QuantHarnessCases.JPM_BANK).load("JPM")
        var params = requireNotNull(data.marketParams)
        var coe = DcfAnalysisEngine.resolveCostOfEquity(data.fundamentals, params).costOfEquityBps
        var book = requireNotNull(data.fundamentals.bookValuePerShareCents)
        var shares = requireNotNull(data.fundamentals.sharesOutstanding).toDouble()
        var twenty = ResidualIncomeMath.valuePerShareCents(
            book0 = (book / 100.0) * shares,
            shares = shares,
            roe0Bps = requireNotNull(data.fundamentals.returnOnEquityBps),
            costOfEquityBps = coe,
            retention = requireNotNull(data.fundamentals.retentionBps) / 10_000.0,
            fadeYears = 20,
        )
        assertTrue(requireNotNull(twenty) < book * 2L)
    }

    @Test
    fun persist_through_cycle_beats_fade_to_coe() {
        var data = QuantHarness.hardcoded(QuantHarnessCases.JPM_BANK).load("JPM")
        var params = requireNotNull(data.marketParams)
        var coe = DcfAnalysisEngine.resolveCostOfEquity(data.fundamentals, params).costOfEquityBps
        var book = requireNotNull(data.fundamentals.bookValuePerShareCents)
        var shares = requireNotNull(data.fundamentals.sharesOutstanding).toDouble()
        var book0 = (book / 100.0) * shares
        var roe = requireNotNull(data.fundamentals.returnOnEquityBps)
        var retention = requireNotNull(data.fundamentals.retentionBps) / 10_000.0
        var g = params.stableGrowthBps()
        var fade = ResidualIncomeMath.valuePerShareCents(
            book0 = book0,
            shares = shares,
            roe0Bps = roe,
            costOfEquityBps = coe,
            retention = retention,
            fadeYears = 5,
            longRunRoeBps = coe,
            stableGrowthBps = g,
        )
        var persist = ResidualIncomeMath.valuePerShareCents(
            book0 = book0,
            shares = shares,
            roe0Bps = roe,
            costOfEquityBps = coe,
            retention = retention,
            fadeYears = 5,
            longRunRoeBps = ResidualIncomeMath.longRunRoeBps(roe, coe),
            stableGrowthBps = g,
        )
        assertTrue(requireNotNull(persist) > requireNotNull(fade))
    }

    @Test
    fun cached_jpm_pack_keeps_material_premium() {
        var pack = QuantHarness.hardcoded(QuantHarnessCases.JPM_BANK).load("JPM")
        var dir = java.nio.file.Files.createTempDirectory("model-polish-jpm")
        var cached = QuantHarness.cached(
            client = QuantLiveClient { pack },
            cacheDir = dir,
        )
        cached.load("JPM")
        var hit = cached.load("JPM")
        var analysis = DcfAnalysisEngine.compute(
            fundamentals = hit.fundamentals,
            timeseries = hit.timeseries,
            marketParams = requireNotNull(hit.marketParams),
        ).getOrThrow()
        var book = requireNotNull(hit.fundamentals.bookValuePerShareCents)
        var premiumBps = ((analysis.baseIntrinsicValueCents - book) * 10_000L) / book
        assertTrue(premiumBps > 2_000L, "cached JPM premium is $premiumBps bps")
    }
}
