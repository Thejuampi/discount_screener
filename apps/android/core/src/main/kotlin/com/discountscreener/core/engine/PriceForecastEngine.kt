package com.discountscreener.core.engine

import com.discountscreener.core.model.DcfAnalysis

object PriceForecastEngine {
    const val POLICY_VERSION = "price-forecast/6-identity-cash"
    const val HORIZON_DAYS = 90

    fun forecast(
        analysis: DcfAnalysis?,
        sharesOutstanding: Long?,
        lastPriceCents: Long?,
        streetTwelveMonthCents: Long?,
    ): PriceSpeech {
        var last = lastPriceCents?.takeIf { it > 0L }
        var street = streetTwelveMonthCents?.takeIf { it > 0L }
        var identity = analysis?.baseIntrinsicValueCents?.takeIf { it > 0L }
        var reasons = mutableListOf<String>()
        if (last == null) reasons += "price=missing_last"
        if (street == null) reasons += "street_price=unavailable"
        var expected = if (identity != null) {
            reasons += "price_forecast=identity_cash"
            identity
        } else {
            reasons += "price_forecast=unavailable:missing_identity"
            null
        }
        var upside = if (last != null && expected != null) {
            (((expected - last) * 10_000L) / last).toInt()
        } else {
            null
        }
        return PriceSpeech(
            lastPriceCents = last,
            streetTwelveMonthCents = street,
            expectedHorizonPriceCents = expected,
            modelPriceTodayCents = identity,
            horizonDays = HORIZON_DAYS,
            upsideToHorizonBps = upside,
            reasonCodes = reasons,
            policyVersion = POLICY_VERSION,
        )
    }
}
