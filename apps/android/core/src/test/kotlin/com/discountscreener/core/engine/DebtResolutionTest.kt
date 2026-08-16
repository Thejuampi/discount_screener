package com.discountscreener.core.engine

import com.discountscreener.core.harness.QuantHarness
import com.discountscreener.core.harness.QuantHarnessCases
import kotlin.test.Test
import kotlin.test.assertEquals

class DebtResolutionTest {
    @Test
    fun apple_shape_estimates_do_not_move_published_kd() {
        var pack = QuantHarness.hardcoded(QuantHarnessCases.AAPL_COUPON_HOLES).load("AAPL")
        var kdWithHoles = resolveDebt(
            timeseries = pack.timeseries,
            reportedTotalDebtDollars = pack.fundamentals.totalDebtDollars,
            riskFreeBps = requireNotNull(pack.marketParams).rfBps,
        ).publishedKd?.bps
        var kdFiledOnly = resolveDebt(
            timeseries = pack.timeseries.copy(
                operatingCashFlow = pack.timeseries.operatingCashFlow.filter { it.asOfDate <= "2023-09-30" },
            ),
            reportedTotalDebtDollars = pack.fundamentals.totalDebtDollars,
            riskFreeBps = requireNotNull(pack.marketParams).rfBps,
        ).publishedKd?.bps
        assertEquals(kdFiledOnly, kdWithHoles)
    }

    @Test
    fun published_kd_reasons_include_the_bps() {
        var pack = QuantHarness.hardcoded(QuantHarnessCases.AAPL_COUPON_HOLES).load("AAPL")
        var kd = requireNotNull(
            resolveDebt(
                timeseries = pack.timeseries,
                reportedTotalDebtDollars = pack.fundamentals.totalDebtDollars,
                riskFreeBps = requireNotNull(pack.marketParams).rfBps,
            ).publishedKd,
        )
        assertEquals("cost_of_debt_bps=${kd.bps}", kd.reasons.first { it.startsWith("cost_of_debt_bps=") })
    }
}
