package com.discountscreener.core.engine

import com.discountscreener.core.math.robustCentre
import com.discountscreener.core.model.BusinessClass
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.SymbolDetail
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * What the symbol's own sector trades at, so a multiple can be read against its industry.
 *
 * Scoring a utility and a chip maker on one absolute P/E band ranks industries before it ranks
 * companies. These centres are what let the fundamentals bucket ask the smaller and more useful
 * question: is this company cheap *for what it is*.
 *
 * A field is null when its sector cannot support the question, and the caller then falls back to
 * the absolute band. Null is the common case for the thin sectors, and it is the correct one.
 */
data class SectorBenchmarks(
    val forwardPeHundredths: Int?,
    val enterpriseToEbitdaHundredths: Int?,
    val priceToBookHundredths: Int?,
    val returnOnEquityBps: Int?,
    val netDebtToEbitdaHundredths: Int?,
    val fcfYieldBps: Int? = null,
)

internal enum class CashSizeKind {
    MarketCap,
    PriceTimesShares,
}

internal data class CashSize(
    val dollars: Long,
    val kind: CashSizeKind,
)

/**
 * Equity cap for an FCF yield. Yahoo FCF is after interest, so the denominator is market cap
 * when the provider sent one, else price × shares. Enterprise value is not used here.
 *
 * [sharesOverride] is a series diluted-average count used only when the snapshot has no
 * sharesOutstanding. Market cap still wins when it is present.
 */
internal fun cashFlowSizeForYield(detail: SymbolDetail, sharesOverride: Long? = null): CashSize? {
    var fundamentals = detail.fundamentals ?: return null
    fundamentals.marketCapDollars?.takeIf { it > 0L }?.let { return CashSize(it, CashSizeKind.MarketCap) }
    var shares = sharesOverride?.takeIf { it > 0L }
        ?: fundamentals.sharesOutstanding?.takeIf { it > 0L }
        ?: return null
    var cap = derivedEquityCap(detail.marketPriceCents, shares) ?: return null
    return CashSize(cap, CashSizeKind.PriceTimesShares)
}

private fun derivedEquityCap(priceCents: Long, shares: Long): Long? {
    if (priceCents <= 0L || shares <= 0L) return null
    var cap = shares.toDouble() * priceCents / 100.0
    if (!cap.isFinite() || cap <= 0.0) return null
    return cap.roundToLong()
}

internal fun cashYieldBps(cashDollars: Long, sizeDollars: Long): Int? {
    if (sizeDollars <= 0L) return null
    var bps = cashDollars.toDouble() / sizeDollars.toDouble() * 10_000.0
    if (!bps.isFinite()) return null
    return bps.coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble()).roundToInt()
}

internal fun trailingFcfYieldBps(detail: SymbolDetail): Int? {
    var fundamentals = detail.fundamentals ?: return null
    if (FinancialClassPolicy.classify(fundamentals) != BusinessClass.OperatingNonFinancial) {
        return null
    }
    var fcf = fundamentals.freeCashFlowDollars ?: return null
    var size = cashFlowSizeForYield(detail) ?: return null
    return cashYieldBps(fcf, size.dollars)
}

/**
 * Net debt against a year of EBITDA, in hundredths of a turn. 176 is 1.76x; a negative number is
 * net cash.
 *
 * This replaces book debt/equity as V4's leverage input, and the reason is that book equity is a
 * poor denominator rather than a hard one to fetch. Buybacks shrink it, so the same balance sheet
 * scores worse after a repurchase than before it. Lease liabilities under ASC 842 inflate the
 * numerator for every retailer that rents its stores. Neither moves what the company can actually
 * service, and EBITDA turns do.
 *
 * All three inputs are dollars, which is the second reason. `debtToEquityHundredths` arrives from
 * Yahoo in a unit the scoring bands never agreed with (see [V4 fallback band][OpportunityEngine]);
 * dollars have no such ambiguity.
 *
 * Null when EBITDA is absent or non-positive, and when either side of net debt is missing. A
 * company with unknown cash is not a company with no cash, and scoring it as though it were would
 * invent leverage it may not carry.
 */
internal fun netDebtToEbitdaOf(fundamentals: FundamentalSnapshot): Int? {
    var ebitda = fundamentals.ebitdaDollars?.takeIf { it > 0L } ?: return null
    var debt = fundamentals.totalDebtDollars ?: return null
    var cash = fundamentals.totalCashDollars ?: return null
    var turns = (debt - cash).toDouble() / ebitda.toDouble() * 100.0
    if (!turns.isFinite()) return null
    return turns.coerceIn(-MAX_LEVERAGE_HUNDREDTHS, MAX_LEVERAGE_HUNDREDTHS).roundToInt()
}

/**
 * A thousand turns either way. The clamp exists so a near-zero EBITDA cannot overflow the Int, and
 * it sits far outside any band that scores, so it never decides a score itself.
 */
private const val MAX_LEVERAGE_HUNDREDTHS = 100_000.0

/**
 * The population floor. Below five members the centre is an anecdote about whoever happened to be
 * in the universe, and a benchmark that moves when one symbol is added is not a benchmark.
 *
 * Five is a choice, not a derivation, and it is recorded as one. Windows's `compute_sector_
 * benchmarks` claims three in its comment and enforces none in its code — it will return a centre
 * from a single symbol. This is deliberately stricter than the twin, not a copy of it.
 *
 * It is also the outer of two gates, and the two ask different questions. This one asks whether
 * the sector is big enough to have a level at all. [robustCentre]'s own floor then asks whether
 * enough of that sector survived the trim to still speak. A sector of five whose trim removes
 * three fails the second gate after passing the first, and that is right.
 *
 * **The neighbouring case is allowed and is worth naming, because reasoning through one boundary
 * and staying silent on the one beside it is an incomplete argument.** A sector of five whose trim
 * removes *two* leaves three survivors, clears both gates, and sets the band for every member of
 * that sector from three observations. That is the intended floor rather than an oversight: three
 * is the fewest from which [robustCentre] will speak at all, and the alternative — refusing the
 * sector — sends the whole sector to the absolute band on the strength of two contaminated members.
 * It is still the thinnest band this code will produce, and a later reading with real sector
 * populations behind it may well raise [MIN_SECTOR_MEMBERS] rather than leave the case reachable.
 */
private val MIN_SECTOR_MEMBERS: Int
    get() = ValuationPolicy.current.sectorBenchmarks.minSectorMembers

/**
 * One entry per sector named in [details]. A symbol with no sector name belongs to no sector and
 * is in no entry; it is not a sector of its own and it does not throw.
 */
fun computeSectorBenchmarks(details: Collection<SymbolDetail>): Map<String, SectorBenchmarks> {
    var bySector = details
        .filter { !it.fundamentals?.sectorName.isNullOrBlank() }
        .groupBy { it.fundamentals!!.sectorName.orEmpty() }
    return bySector.mapValues { (_, members) ->
        var fundamentals = members.map { it.fundamentals!! }
        SectorBenchmarks(
            // A multiple at or below zero is a loss or a negative book, not a price level, and it
            // would pull the sector's level toward a number no buyer could pay.
            forwardPeHundredths = centreOfPrices(fundamentals.map { it.forwardPeHundredths }),
            enterpriseToEbitdaHundredths = centreOfPrices(fundamentals.map { it.enterpriseToEbitdaHundredths }),
            priceToBookHundredths = centreOfPrices(fundamentals.map { it.priceToBookHundredths }),
            // Return on equity legitimately crosses zero, so nothing is filtered out of it. A
            // sector of loss makers has a negative level and that level is the truth about it.
            returnOnEquityBps = centreOf(fundamentals.map { it.returnOnEquityBps }),
            // Leverage crosses zero for the same kind of reason: a company holding more cash than
            // debt has negative net debt, and that is a fact about it rather than a bad reading.
            netDebtToEbitdaHundredths = centreOf(fundamentals.map { netDebtToEbitdaOf(it) }),
            fcfYieldBps = centreOf(members.map { trailingFcfYieldBps(it) }),
        )
    }
}

private fun centreOfPrices(values: List<Int?>): Int? = centreOf(values.map { it?.takeIf { v -> v > 0 } })

private fun centreOf(values: List<Int?>): Int? {
    var present = values.filterNotNull()
    if (present.size < MIN_SECTOR_MEMBERS) return null
    return robustCentre(present.map { it.toDouble() })?.roundToInt()
}
