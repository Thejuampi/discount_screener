package com.discountscreener.core.engine

import com.discountscreener.core.harness.QuantHarness
import com.discountscreener.core.harness.QuantHarnessCases
import com.discountscreener.core.harness.QuantLiveClient
import com.discountscreener.core.harness.YahooQuantLiveClient
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DebtEngineHarnessTest {
    @Test
    fun hardcoded_aapl_holes_estimate_at_medium() {
        var pack = QuantHarness.hardcoded(QuantHarnessCases.AAPL_COUPON_HOLES).load("AAPL")
        var hole = resolveDebt(pack.timeseries).coupons.single { it.period == "2024-09-28" }
        assertEquals(CouponConfidence.Medium, hole.confidence)
    }

    @Test
    fun hardcoded_aapl_holes_do_not_move_kd() {
        var pack = QuantHarness.hardcoded(QuantHarnessCases.AAPL_COUPON_HOLES).load("AAPL")
        var withHoles = resolveDebt(
            timeseries = pack.timeseries,
            reportedTotalDebtDollars = pack.fundamentals.totalDebtDollars,
            riskFreeBps = requireNotNull(pack.marketParams).rfBps,
        ).publishedKd?.bps
        var filedOnly = resolveDebt(
            timeseries = pack.timeseries.copy(
                operatingCashFlow = pack.timeseries.operatingCashFlow.filter { it.asOfDate <= "2023-09-30" },
            ),
            reportedTotalDebtDollars = pack.fundamentals.totalDebtDollars,
            riskFreeBps = requireNotNull(pack.marketParams).rfBps,
        ).publishedKd?.bps
        assertEquals(filedOnly, withHoles)
    }

    @Test
    fun hardcoded_aapl_identity_stamps_debt_resolution() {
        var pack = QuantHarness.hardcoded(QuantHarnessCases.AAPL_COUPON_HOLES).load("AAPL")
        var analysis = DcfAnalysisEngine.compute(
            fundamentals = pack.fundamentals,
            timeseries = pack.timeseries,
            marketParams = requireNotNull(pack.marketParams),
        ).getOrThrow()
        assertEquals(true, analysis.reasonCodes.contains("debt=$DEBT_RESOLUTION_VERSION"))
    }

    @Test
    fun cached_aapl_pack_keeps_the_estimate() {
        var pack = QuantHarness.hardcoded(QuantHarnessCases.AAPL_COUPON_HOLES).load("AAPL")
        var source = QuantHarness.cached(
            client = QuantLiveClient { pack },
            cacheDir = Files.createTempDirectory("debt-engine-aapl"),
        )
        source.load("AAPL")
        var hit = source.load("AAPL")
        var hole = resolveDebt(hit.timeseries).coupons.single { it.period == "2024-09-28" }
        assertEquals(CouponKind.Estimated, hole.kind)
    }

    @Test
    fun hardcoded_market_yield_sets_published_kd() {
        var pack = QuantHarness.hardcoded(QuantHarnessCases.AAPL_COUPON_HOLES).load("AAPL")
        var kd = resolveDebt(
            timeseries = attachMarketYield(pack.timeseries, IssuerYieldPoint(700)),
            reportedTotalDebtDollars = pack.fundamentals.totalDebtDollars,
            riskFreeBps = requireNotNull(pack.marketParams).rfBps,
        ).publishedKd
        assertEquals(700, kd?.bps)
    }

    @Test
    fun hardcoded_identity_names_market_yield_source() {
        var pack = QuantHarness.hardcoded(QuantHarnessCases.AAPL_COUPON_HOLES).load("AAPL")
        var analysis = DcfAnalysisEngine.compute(
            fundamentals = pack.fundamentals,
            timeseries = pack.timeseries,
            marketParams = requireNotNull(pack.marketParams),
            issuerYield = IssuerYieldPoint(700),
        ).getOrThrow()
        assertEquals(true, analysis.reasonCodes.contains("cost_of_debt_source=market_yield"))
    }

    @Test
    fun hardcoded_selected_apple_quotes_set_kd() {
        var pack = QuantHarness.hardcoded(QuantHarnessCases.AAPL_COUPON_HOLES).load("AAPL")
        var point = requireNotNull(
            selectIssuerMarketYield(QuantHarnessCases.AAPL_INSTRUMENT_QUOTES, "2026-08-16"),
        )
        var kd = resolveDebt(
            timeseries = attachMarketYield(pack.timeseries, point),
            reportedTotalDebtDollars = pack.fundamentals.totalDebtDollars,
            riskFreeBps = requireNotNull(pack.marketParams).rfBps,
        ).publishedKd
        assertEquals(471, kd?.bps)
    }

    @Test
    fun cached_selected_yield_pack_keeps_kd() {
        var pack = QuantHarness.hardcoded(QuantHarnessCases.AAPL_COUPON_HOLES).load("AAPL")
        var point = requireNotNull(
            selectIssuerMarketYield(QuantHarnessCases.AAPL_INSTRUMENT_QUOTES, "2026-08-16"),
        )
        var withYield = pack.copy(timeseries = attachMarketYield(pack.timeseries, point))
        var source = QuantHarness.cached(
            client = QuantLiveClient { withYield },
            cacheDir = Files.createTempDirectory("debt-engine-selected-yield"),
        )
        source.load("AAPL")
        var hit = source.load("AAPL")
        var kd = resolveDebt(
            timeseries = hit.timeseries,
            reportedTotalDebtDollars = hit.fundamentals.totalDebtDollars,
            riskFreeBps = requireNotNull(hit.marketParams).rfBps,
        ).publishedKd
        assertEquals(471, kd?.bps)
    }

    @Test
    fun cached_market_yield_pack_keeps_kd() {
        var pack = QuantHarness.hardcoded(QuantHarnessCases.AAPL_COUPON_HOLES).load("AAPL")
        var withYield = pack.copy(
            timeseries = attachMarketYield(pack.timeseries, IssuerYieldPoint(700)),
        )
        var source = QuantHarness.cached(
            client = QuantLiveClient { withYield },
            cacheDir = Files.createTempDirectory("debt-engine-yield"),
        )
        source.load("AAPL")
        var hit = source.load("AAPL")
        var kd = resolveDebt(
            timeseries = hit.timeseries,
            reportedTotalDebtDollars = hit.fundamentals.totalDebtDollars,
            riskFreeBps = requireNotNull(hit.marketParams).rfBps,
        ).publishedKd
        assertEquals(700, kd?.bps)
    }

    @Test
    fun cached_aapl_second_load_is_a_hit() {
        var pack = QuantHarness.hardcoded(QuantHarnessCases.AAPL_COUPON_HOLES).load("AAPL")
        var source = QuantHarness.cached(
            client = QuantLiveClient { pack },
            cacheDir = Files.createTempDirectory("debt-engine-aapl-hit"),
        )
        source.load("AAPL")
        assertEquals(true, source.load("AAPL").cacheHit)
    }

    @Test
    @Tag("live")
    @EnabledIfEnvironmentVariable(named = "DS_QUANT_LIVE", matches = "true")
    fun live_aapl_refresh_does_not_file_interest_paid() {
        var pack = QuantHarness.live(YahooQuantLiveClient()).load("AAPL")
        assertEquals(
            true,
            pack.timeseries.interestExpense.none { isCashPaidCouponConcept(it.concept) },
        )
    }
}
