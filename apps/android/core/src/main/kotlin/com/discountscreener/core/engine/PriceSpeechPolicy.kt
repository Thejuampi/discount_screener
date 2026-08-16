package com.discountscreener.core.engine

import com.discountscreener.core.model.DcfAnalysis

data class PriceSpeech(
    val lastPriceCents: Long?,
    val streetTwelveMonthCents: Long?,
    val expectedHorizonPriceCents: Long?,
    val modelPriceTodayCents: Long?,
    val horizonDays: Int,
    val upsideToHorizonBps: Int?,
    val reasonCodes: List<String>,
    val policyVersion: String,
)

object PriceSpeechPolicy {
    const val POLICY_VERSION = PriceForecastEngine.POLICY_VERSION
    const val HORIZON_DAYS = PriceForecastEngine.HORIZON_DAYS

    fun speak(
        lastPriceCents: Long?,
        streetTwelveMonthCents: Long?,
        analysis: DcfAnalysis?,
        sharesOutstanding: Long?,
    ): PriceSpeech = PriceForecastEngine.forecast(
        analysis = analysis,
        sharesOutstanding = sharesOutstanding,
        lastPriceCents = lastPriceCents,
        streetTwelveMonthCents = streetTwelveMonthCents,
    )
}
