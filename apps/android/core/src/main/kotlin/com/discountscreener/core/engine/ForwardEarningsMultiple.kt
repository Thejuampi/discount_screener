package com.discountscreener.core.engine

import java.math.BigInteger

/**
 * Forward earnings × multiple pure engine (Slice 1A / 1B-0 overflow parity).
 * Market-reference lane only. Subject market price and stated target never enter the math.
 * Intermediate arithmetic uses BigInteger to match Rust `i128` half-up division.
 */
object ForwardEarningsMultiple {
    const val ENGINE_ID = "forward_earnings_multiple/1"
    const val METHOD_POLICY_VERSION = "fem-policy-v1"

    enum class MultipleProvenance(val snake: String) {
        AnalystStated("analyst_stated"),
        PeerPolicyDerived("peer_policy_derived"),
    }

    data class Input(
        val issuerId: String,
        val securityId: String?,
        val metricId: String,
        val metricBasis: String,
        val epsCents: Long,
        val multipleHundredths: Int,
        val multipleProvenance: MultipleProvenance,
        val forecastPeriodEnd: String,
        val targetAsOf: String,
        val datePrecision: String,
        val currency: String,
        val evidenceObservedAtUnixMs: Long,
        val marketPriceCents: Long? = null,
        val statedTargetCents: Long? = null,
        val peerCount: Int? = null,
    )

    data class Available(
        val targetValueCents: Long,
        val epsCents: Long,
        val multipleHundredths: Int,
        val engineId: String,
        val methodPolicyVersion: String,
        val multipleProvenance: MultipleProvenance,
        val quality: String,
        val forecastPeriodEnd: String,
        val targetAsOf: String,
        val datePrecision: String,
        val currency: String,
    )

    sealed class Result {
        data class AvailableResult(val value: Available) : Result()
        data class Unavailable(val reasonCode: String) : Result()
    }

    fun compute(input: Input): Result {
        if (input.issuerId.trim().isEmpty()) return Result.Unavailable("empty_issuer_id")
        if (input.metricId.trim().isEmpty()) return Result.Unavailable("missing_metric_id")
        if (input.currency.trim().isEmpty()) return Result.Unavailable("missing_currency")
        if (input.forecastPeriodEnd.trim().isEmpty()) return Result.Unavailable("missing_forecast_period_end")
        if (input.targetAsOf.trim().isEmpty()) return Result.Unavailable("missing_target_as_of")
        if (input.datePrecision.trim().isEmpty()) return Result.Unavailable("missing_date_precision")
        if (input.epsCents <= 0L) return Result.Unavailable("non_positive_eps")
        if (input.multipleHundredths <= 0) return Result.Unavailable("non_positive_multiple")
        when (input.multipleProvenance) {
            MultipleProvenance.AnalystStated -> Unit
            MultipleProvenance.PeerPolicyDerived -> {
                var peers = input.peerCount ?: 0
                if (peers == 0) return Result.Unavailable("unsupported_provenance")
                return Result.Unavailable("peer_policy_not_implemented")
            }
        }

        // marketPriceCents / statedTargetCents intentionally unused.
        // BigInteger intermediate matches Rust i128 path (avoids Long multiply/add overflow).
        var product = BigInteger.valueOf(input.epsCents)
            .multiply(BigInteger.valueOf(input.multipleHundredths.toLong()))
        var targetBi = divRoundHalfUp(product, BigInteger.valueOf(100L))
            ?: return Result.Unavailable("overflow")
        if (targetBi < BigInteger.valueOf(Long.MIN_VALUE) || targetBi > BigInteger.valueOf(Long.MAX_VALUE)) {
            return Result.Unavailable("overflow")
        }
        var target = targetBi.longValueExact()

        return Result.AvailableResult(
            Available(
                targetValueCents = target,
                epsCents = input.epsCents,
                multipleHundredths = input.multipleHundredths,
                engineId = ENGINE_ID,
                methodPolicyVersion = METHOD_POLICY_VERSION,
                multipleProvenance = input.multipleProvenance,
                quality = "provisional",
                forecastPeriodEnd = input.forecastPeriodEnd,
                targetAsOf = input.targetAsOf,
                datePrecision = input.datePrecision,
                currency = input.currency,
            ),
        )
    }

    /** Half-up for signed dividends (parity with Rust `div_round_half_up_i128`). */
    private fun divRoundHalfUp(numerator: BigInteger, denominator: BigInteger): BigInteger? {
        if (denominator == BigInteger.ZERO) return null
        var half = denominator.divide(BigInteger.valueOf(2L))
        return if (numerator.signum() >= 0) {
            numerator.add(half).divide(denominator)
        } else {
            numerator.subtract(half).divide(denominator)
        }
    }
}
