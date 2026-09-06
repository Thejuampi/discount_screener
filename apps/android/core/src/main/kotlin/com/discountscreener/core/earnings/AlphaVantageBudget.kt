package com.discountscreener.core.earnings

import java.time.Instant
import kotlinx.serialization.Serializable

@Serializable
data class AlphaVantageBudget(
    val dayEpoch: Long = 0L,
    val lastCallEpoch: Long? = null,
    val callsToday: Int = 0,
)

fun admitAlphaVantageCall(
    budget: AlphaVantageBudget,
    nowEpoch: Long,
    dailyLimit: Int = EarningsGatePolicy.current.avDailyLimit,
    perMinute: Int = EarningsGatePolicy.current.avPerMinute,
    count: Int = 1,
): AlphaVantageBudget? {
    if (count < 1) return null
    var day = Instant.ofEpochSecond(nowEpoch).atZone(EXCHANGE_ZONE).toLocalDate().toEpochDay()
    var calls = if (budget.dayEpoch == day) budget.callsToday else 0
    var last = if (budget.dayEpoch == day) budget.lastCallEpoch else null
    if (calls + count > dailyLimit) return null
    var minGap = if (perMinute <= 0) 0L else 60L / perMinute
    if (last != null && nowEpoch - last < minGap) return null
    return AlphaVantageBudget(dayEpoch = day, lastCallEpoch = nowEpoch, callsToday = calls + count)
}
