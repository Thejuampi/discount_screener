package com.discountscreener.core.earnings

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

data class OptionChainSnapshot(
    val symbol: String,
    val underlyingPriceCents: Long?,
    val expiries: List<LocalDate>,
    val expiry: LocalDate?,
    val rows: List<ChainRow>,
)

private val lenient = Json { ignoreUnknownKeys = true; isLenient = true }

fun parseOptionChain(body: String): OptionChainSnapshot? {
    var root = runCatching { lenient.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
    var result = root["optionChain"]?.jsonObject?.get("result")?.jsonArray?.firstOrNull()?.jsonObject
        ?: return null
    var symbol = result["underlyingSymbol"]?.jsonPrimitive?.content ?: return null
    var expiries = result["expirationDates"]?.jsonArray.orEmpty()
        .mapNotNull { it.jsonPrimitive.longOrNull?.let(::epochSecondsToDate) }
        .sorted()
    var chain = result["options"]?.jsonArray?.firstOrNull()?.jsonObject
    return OptionChainSnapshot(
        symbol = symbol,
        underlyingPriceCents = result["quote"]?.jsonObject
            ?.get("regularMarketPrice")?.jsonPrimitive?.doubleOrNull
            ?.let(::toCents),
        expiries = expiries,
        expiry = chain?.get("expirationDate")?.jsonPrimitive?.longOrNull?.let(::epochSecondsToDate),
        rows = pairSides(chain?.get("calls")?.jsonArray, chain?.get("puts")?.jsonArray),
    )
}

private fun pairSides(calls: JsonArray?, puts: JsonArray?): List<ChainRow> {
    var putByStrike = quotesByStrike(puts)
    return quotesByStrike(calls)
        .mapNotNull { (strike, call) ->
            putByStrike[strike]?.let { put -> ChainRow(strike = strike, call = call, put = put) }
        }
        .sortedBy { it.strike }
}

private fun quotesByStrike(side: JsonArray?): Map<Double, OptionQuote> = side.orEmpty()
    .mapNotNull { element ->
        var contract = element.jsonObject
        var strike = contract["strike"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
        var bid = contract["bid"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
        var ask = contract["ask"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
        strike to OptionQuote(bid = bid, ask = ask)
    }
    .toMap()

private fun epochSecondsToDate(seconds: Long): LocalDate =
    Instant.ofEpochSecond(seconds).atZone(ZoneOffset.UTC).toLocalDate()

private fun toCents(price: Double): Long? =
    if (price.isFinite() && price > 0.0) Math.round(price * 100.0) else null
