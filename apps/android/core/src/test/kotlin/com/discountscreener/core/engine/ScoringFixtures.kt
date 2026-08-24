package com.discountscreener.core.engine

import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ChartRangeSummary
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.ExternalSignalStatus
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.QualificationStatus
import com.discountscreener.core.model.SymbolDetail

/**
 * The inputs every scoring test builds, in one place.
 *
 * `AggressiveV2ScoringTest` and `AggressiveV3ScoringTest` each carried their own private copies of
 * these, the V2 set being a strict subset of the V3 set — the fields V2 omitted are the ones whose
 * declared default is already null, so the two produced identical objects and only looked
 * different. They live here as top-level functions in the same package, so no call site changed
 * when they moved.
 */
internal fun baseDetail(
    marketPriceCents: Long = 10_000,
    externalStatus: ExternalSignalStatus = ExternalSignalStatus.Supportive,
    externalSignalFairValueCents: Long? = null,
    weightedExternalSignalFairValueCents: Long? = null,
    externalSignalLowFairValueCents: Long? = null,
    externalSignalHighFairValueCents: Long? = null,
    analystOpinionCount: Int? = null,
    recommendationMeanHundredths: Int? = null,
    externalSignalAgeSeconds: Long? = null,
    strongBuyCount: Int? = null,
    buyCount: Int? = null,
    holdCount: Int? = null,
    sellCount: Int? = null,
    strongSellCount: Int? = null,
    fundamentals: FundamentalSnapshot? = null,
): SymbolDetail = SymbolDetail(
    symbol = "TEST",
    profitable = true,
    marketPriceCents = marketPriceCents,
    intrinsicValueCents = marketPriceCents * 2,
    gapBps = 5_000,
    upsideBps = 5_000,
    minimumGapBps = 2_500,
    qualification = QualificationStatus.Qualified,
    externalStatus = externalStatus,
    externalSignalFairValueCents = externalSignalFairValueCents ?: weightedExternalSignalFairValueCents,
    externalSignalLowFairValueCents = externalSignalLowFairValueCents,
    externalSignalHighFairValueCents = externalSignalHighFairValueCents,
    weightedExternalSignalFairValueCents = weightedExternalSignalFairValueCents,
    weightedAnalystCount = analystOpinionCount,
    externalSignalGapBps = null,
    externalSignalAgeSeconds = externalSignalAgeSeconds,
    externalSignalMaxAgeSeconds = 7L * 86_400L,
    analystOpinionCount = analystOpinionCount,
    recommendationMeanHundredths = recommendationMeanHundredths,
    strongBuyCount = strongBuyCount,
    buyCount = buyCount,
    holdCount = holdCount,
    sellCount = sellCount,
    strongSellCount = strongSellCount,
    fundamentals = fundamentals,
    confidence = ConfidenceBand.High,
    lastSequence = 1,
    updateCount = 1,
    isWatched = false,
    companyName = "Test Inc",
)

internal fun fundamentals(
    symbol: String = "TEST",
    freeCashFlowDollars: Long? = null,
    operatingCashFlowDollars: Long? = null,
    marketCapDollars: Long? = null,
    sharesOutstanding: Long? = null,
    returnOnEquityBps: Int? = null,
    earningsGrowthBps: Int? = null,
    trailingEpsCents: Long? = null,
    debtToEquityHundredths: Int? = null,
    totalCashDollars: Long? = null,
    totalDebtDollars: Long? = null,
    ebitdaDollars: Long? = null,
    enterpriseValueDollars: Long? = null,
    sectorName: String? = "Technology",
    sectorKey: String? = null,
    industryName: String? = null,
    industryKey: String? = null,
    forwardPeHundredths: Int? = null,
    enterpriseToEbitdaHundredths: Int? = null,
    priceToBookHundredths: Int? = null,
    betaMillis: Int? = null,
) = FundamentalSnapshot(
    symbol = symbol,
    sectorName = sectorName,
    sectorKey = sectorKey,
    industryName = industryName,
    industryKey = industryKey,
    marketCapDollars = marketCapDollars,
    sharesOutstanding = sharesOutstanding,
    freeCashFlowDollars = freeCashFlowDollars,
    operatingCashFlowDollars = operatingCashFlowDollars,
    returnOnEquityBps = returnOnEquityBps,
    debtToEquityHundredths = debtToEquityHundredths,
    totalCashDollars = totalCashDollars,
    totalDebtDollars = totalDebtDollars,
    ebitdaDollars = ebitdaDollars,
    enterpriseValueDollars = enterpriseValueDollars,
    earningsGrowthBps = earningsGrowthBps,
    trailingEpsCents = trailingEpsCents,
    forwardPeHundredths = forwardPeHundredths,
    enterpriseToEbitdaHundredths = enterpriseToEbitdaHundredths,
    priceToBookHundredths = priceToBookHundredths,
    betaMillis = betaMillis,
)

internal fun summary(
    latestCloseCents: Long? = 10_000,
    ema20Cents: Long? = null,
    ema50Cents: Long? = null,
    ema200Cents: Long? = null,
    macdCents: Long? = null,
    signalCents: Long? = null,
    histogramCents: Long? = null,
    latestWilderRsi: Double? = null,
    latestRsiSlope: Double? = null,
    volumeRatioHundredths: Int? = null,
) = ChartRangeSummary(
    range = ChartRange.Year,
    capturedAt = 0,
    candleCount = 52,
    latestCloseCents = latestCloseCents,
    ema20Cents = ema20Cents,
    ema50Cents = ema50Cents,
    ema200Cents = ema200Cents,
    macdCents = macdCents,
    signalCents = signalCents,
    histogramCents = histogramCents,
    latestWilderRsi = latestWilderRsi,
    latestRsiSlope = latestRsiSlope,
    volumeRatioHundredths = volumeRatioHundredths,
)

internal fun dcf(
    base: Long = 15_000,
    bear: Long = 10_000,
    bull: Long = 20_000,
) = DcfAnalysis(
    bearIntrinsicValueCents = bear,
    baseIntrinsicValueCents = base,
    bullIntrinsicValueCents = bull,
    waccBps = 900,
    baseGrowthBps = 500,
    netDebtDollars = 0,
)
