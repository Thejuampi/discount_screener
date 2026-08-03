package com.discountscreener.core.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ForwardEarningsMultipleTest {
    @Test
    fun fixture_transcription_thirteen_times_twenty_eight_is_three_sixty_four() {
        when (val r = ForwardEarningsMultiple.compute(fixture())) {
            is ForwardEarningsMultiple.Result.AvailableResult ->
                assertEquals(36_400L, r.value.targetValueCents)
            is ForwardEarningsMultiple.Result.Unavailable ->
                error("expected available, got ${r.reasonCode}")
        }
    }

    @Test
    fun synthetic_issuer_same_arithmetic() {
        val r = ForwardEarningsMultiple.compute(
            fixture().copy(issuerId = "issuer:0000999999", securityId = "sec:syn-us"),
        )
        assertTrue(r is ForwardEarningsMultiple.Result.AvailableResult)
        assertEquals(36_400L, (r as ForwardEarningsMultiple.Result.AvailableResult).value.targetValueCents)
    }

    @Test
    fun market_price_and_stated_target_do_not_affect_result() {
        val a = ForwardEarningsMultiple.compute(
            fixture().copy(marketPriceCents = 1L, statedTargetCents = 99_999L),
        )
        val b = ForwardEarningsMultiple.compute(
            fixture().copy(marketPriceCents = 9_999_999L, statedTargetCents = 1L),
        )
        assertEquals(a, b)
    }

    @Test
    fun zero_eps_refuses() {
        when (val r = ForwardEarningsMultiple.compute(fixture().copy(epsCents = 0L))) {
            is ForwardEarningsMultiple.Result.Unavailable ->
                assertEquals("non_positive_eps", r.reasonCode)
            else -> error("expected refuse")
        }
    }

    @Test
    fun non_positive_multiple_refuses() {
        when (val r = ForwardEarningsMultiple.compute(fixture().copy(multipleHundredths = 0))) {
            is ForwardEarningsMultiple.Result.Unavailable ->
                assertEquals("non_positive_multiple", r.reasonCode)
            else -> error("expected refuse")
        }
    }

    @Test
    fun missing_metric_refuses() {
        when (val r = ForwardEarningsMultiple.compute(fixture().copy(metricId = "  "))) {
            is ForwardEarningsMultiple.Result.Unavailable ->
                assertEquals("missing_metric_id", r.reasonCode)
            else -> error("expected refuse")
        }
    }

    @Test
    fun peer_policy_with_zero_peers_refuses() {
        when (
            val r = ForwardEarningsMultiple.compute(
                fixture().copy(
                    multipleProvenance = ForwardEarningsMultiple.MultipleProvenance.PeerPolicyDerived,
                    peerCount = 0,
                ),
            )
        ) {
            is ForwardEarningsMultiple.Result.Unavailable ->
                assertEquals("unsupported_provenance", r.reasonCode)
            else -> error("expected refuse")
        }
    }

    @Test
    fun half_up_rounding_example() {
        when (
            val r = ForwardEarningsMultiple.compute(
                fixture().copy(epsCents = 150L, multipleHundredths = 333),
            )
        ) {
            is ForwardEarningsMultiple.Result.AvailableResult ->
                assertEquals(500L, r.value.targetValueCents)
            else -> error("expected available")
        }
    }

    @Test
    fun extreme_i64_max_times_one_hundred_uses_wide_intermediate() {
        when (
            val r = ForwardEarningsMultiple.compute(
                fixture().copy(epsCents = Long.MAX_VALUE, multipleHundredths = 100),
            )
        ) {
            is ForwardEarningsMultiple.Result.AvailableResult ->
                assertEquals(Long.MAX_VALUE, r.value.targetValueCents)
            else -> error("expected available via BigInteger intermediate")
        }
    }

    @Test
    fun extreme_overflow_result_refuses() {
        when (
            val r = ForwardEarningsMultiple.compute(
                fixture().copy(epsCents = Long.MAX_VALUE, multipleHundredths = 200),
            )
        ) {
            is ForwardEarningsMultiple.Result.Unavailable ->
                assertEquals("overflow", r.reasonCode)
            else -> error("expected overflow refuse")
        }
    }

    private fun fixture(): ForwardEarningsMultiple.Input =
        ForwardEarningsMultiple.Input(
            issuerId = "issuer:0001018724",
            securityId = "sec:amzn-us",
            metricId = "gaap_diluted_eps",
            metricBasis = "reported_gaap",
            epsCents = 1300L,
            multipleHundredths = 2800,
            multipleProvenance = ForwardEarningsMultiple.MultipleProvenance.AnalystStated,
            forecastPeriodEnd = "2028-12-31",
            targetAsOf = "2027-12",
            datePrecision = "month_label",
            currency = "USD",
            evidenceObservedAtUnixMs = 1_753_920_000_000L,
            marketPriceCents = 20_000L,
            statedTargetCents = 36_500L,
            peerCount = null,
        )
}
