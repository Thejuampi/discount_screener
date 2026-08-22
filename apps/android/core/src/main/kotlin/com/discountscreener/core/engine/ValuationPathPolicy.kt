package com.discountscreener.core.engine

/**
 * Feature-conditional projection path from our drivers.
 * Street is not an input. Street is only a later scoreboard.
 */
object ValuationPathPolicy {
    const val VERSION = "valuation-path/4-no-expand-without-franchise"
    val REINVESTMENT_HOLD_YEARS: Int
        get() = ValuationPolicy.current.fcffPath.reinvestmentHoldYears
    val REINVESTMENT_MARGIN_BPS: Int
        get() = ValuationPolicy.current.fcffPath.reinvestmentMarginBps
    val REINVESTMENT_ERP_WEIGHT_PCT: Int
        get() = ValuationPolicy.current.fcffPath.reinvestmentErpWeightPct
    val REINVESTMENT_RETENTION_MIN_BPS: Int
        get() = ValuationPolicy.current.fcffPath.reinvestmentRetentionMinBps
    val MAX_SECULAR_GROWTH_BPS: Int
        get() = ValuationPolicy.current.fcffPath.maxSecularGrowthBps
    val QUALITY_ROE_BPS: Int
        get() = ValuationPolicy.current.fcffPath.qualityRoeBps
    val QUALITY_GROWTH_MAX_BPS: Int
        get() = ValuationPolicy.current.fcffPath.qualityGrowthMaxBps
    val QUALITY_WACC_GAP_MIN_BPS: Int
        get() = ValuationPolicy.current.fcffPath.qualityWaccGapMinBps
    val QUALITY_RF_PREMIUM_BPS: Int
        get() = ValuationPolicy.current.fcffPath.qualityRfPremiumBps
    val MARGIN_FADE_BUFFER_BPS: Int
        get() = ValuationPolicy.current.fcffPath.marginFadeBufferBps
    val SECULAR_HOLD_EXCESS_STEP_BPS: Int
        get() = ValuationPolicy.current.fcffPath.secularHoldExcessStepBps
    val SECULAR_HOLD_MAX_YEARS: Int
        get() = ValuationPolicy.current.fcffPath.secularHoldMaxYears
    val FADED_MARGIN_HOLD_CAP_YEARS: Int
        get() = ValuationPolicy.current.fcffPath.fadedMarginHoldCapYears
    val INTERNET_RETAIL_HOLD_MIN_YEARS: Int
        get() = ValuationPolicy.current.fcffPath.internetRetailHoldMinYears
    val DISCOUNT_STORE_HOLD_MIN_YEARS: Int
        get() = ValuationPolicy.current.fcffPath.discountStoreHoldMinYears
    val INTERNET_CONTENT_EXTREME_STABLE_BPS: Int
        get() = ValuationPolicy.current.fcffPath.internetContentExtremeStableBps
    val WEAK_FRANCHISE_EXCESS_ROE_BPS: Int
        get() = ValuationPolicy.current.fcffPath.weakFranchiseExcessRoeBps
    val REINVESTMENT_MIN_CAPEX_BPS: Int
        get() = ValuationPolicy.current.fcffPath.reinvestmentMinCapexBps

    data class FcffPath(
        val holdYears: Int,
        val fadeYears: Int,
        val usedGrowthBps: Int,
        val startMarginBps: Int,
        val stableMarginBps: Int,
        val discountBps: Int,
        val fadeExponent: Double,
        val reasons: List<String>,
    )

    fun resolveFcff(
        regime: String,
        rawGrowthBps: Int,
        matureCapBps: Int,
        cappedGrowthBps: Int,
        currentMarginBps: Int,
        discountBps: Int,
        roe0Bps: Int?,
        retentionBps: Int?,
        rfBps: Int,
        erpBps: Int = ValuationPolicy.current.fcffPath.defaultPathErpBps,
        industry: String?,
        sector: String?,
        fadeYearsDefault: Int = ValuationPolicy.current.fcffPath.defaultFadeYears,
        fadeExponentDefault: Double = ValuationPolicy.current.fcffPath.defaultFadeExponent,
        capexIntensityBps: Int? = null,
    ): FcffPath {
        var reasons = mutableListOf<String>()
        var excessRoe = if (roe0Bps != null) roe0Bps - discountBps else 0
        var capexSupportsReinvestment = capexIntensityBps == null ||
            capexIntensityBps >= REINVESTMENT_MIN_CAPEX_BPS
        var reinvestment = regime == "secular_expansion" &&
            roe0Bps != null &&
            roe0Bps < discountBps &&
            (retentionBps ?: 0) >= REINVESTMENT_RETENTION_MIN_BPS &&
            capexSupportsReinvestment
        var path = ValuationPolicy.current.fcffPath
        var persistFracBps = (path.persistFracBaseBps + excessRoe / 2)
            .coerceIn(path.persistFracMinBps, path.persistFracMaxBps)
        var persistGrowth = ((rawGrowthBps.toLong() * persistFracBps) / 10_000L).toInt()
        var weakFranchise = excessRoe >= 0 && excessRoe < WEAK_FRANCHISE_EXCESS_ROE_BPS
        var usedGrowth = if (regime == "secular_expansion") {
            if (weakFranchise) {
                minOf(rawGrowthBps, persistGrowth, matureCapBps, MAX_SECULAR_GROWTH_BPS)
            } else {
                minOf(rawGrowthBps, maxOf(matureCapBps, persistGrowth), MAX_SECULAR_GROWTH_BPS)
            }
        } else {
            cappedGrowthBps
        }
        if (usedGrowth != rawGrowthBps && regime == "secular_expansion") {
            var tag = if (weakFranchise) "weak_franchise_persist" else "persist_frac"
            reasons += "growth=$tag:${persistFracBps}:raw=$rawGrowthBps:used=$usedGrowth"
        }
        var industryPrior = IndustryOperatingPathPolicy.resolve(industry, sector)
        var startMargin = currentMarginBps
        var stableMargin = currentMarginBps
        var fadingMargin = false
        if (reinvestment) {
            startMargin = maxOf(currentMarginBps, REINVESTMENT_MARGIN_BPS)
            stableMargin = startMargin
            reasons += "margin=reinvestment_target:$stableMargin"
        } else if (industryPrior.matched &&
            currentMarginBps * 100L > industryPrior.targetFcffMarginBps.toLong() *
                path.industryFadeOvershootRatioBps / 100L
        ) {
            stableMargin = if (currentMarginBps >= path.internetContentExtremeStartBps &&
                industryPrior.id == "internet_content"
            ) {
                minOf(industryPrior.targetFcffMarginBps, INTERNET_CONTENT_EXTREME_STABLE_BPS)
            } else {
                industryPrior.targetFcffMarginBps
            }
            fadingMargin = true
            reasons += "margin=fade_to_industry:${industryPrior.id}:$stableMargin"
        }
        if (industryPrior.id == "pharma" && (roe0Bps ?: 0) < path.pharmaLowRoeBps) {
            stableMargin = minOf(stableMargin, path.pharmaLowRoeMarginCapBps)
            fadingMargin = true
            reasons += "margin=low_roe_pharma:$stableMargin"
        }
        if ((industryPrior.id == "internet_retail" || industryPrior.id == "internet_content") &&
            industryPrior.matched &&
            currentMarginBps < industryPrior.targetFcffMarginBps &&
            excessRoe > 0
        ) {
            stableMargin = industryPrior.targetFcffMarginBps
            reasons += "margin=expand_to_industry:${industryPrior.id}:$stableMargin"
        }
        if ((industryPrior.id == "oil_integrated" || industryPrior.id == "oil_ep") &&
            regime == "cyclical_or_transition"
        ) {
            usedGrowth = maxOf(usedGrowth, path.commodityFloorGrowthBps)
            stableMargin = maxOf(stableMargin, industryPrior.targetFcffMarginBps)
            reasons += "path=through_cycle_commodity:g=$usedGrowth"
        }
        if (industryPrior.id == "auto" && regime == "cyclical_or_transition") {
            if (startMargin > industryPrior.targetFcffMarginBps) {
                stableMargin = industryPrior.targetFcffMarginBps
                fadingMargin = true
                reasons += "path=through_cycle_auto:$stableMargin"
            }
        }
        var holdYears = when {
            reinvestment -> {
                reasons += "path=reinvestment_story:hold=$REINVESTMENT_HOLD_YEARS"
                REINVESTMENT_HOLD_YEARS
            }
            regime == "secular_expansion" && excessRoe > 0 -> {
                var years = (excessRoe / SECULAR_HOLD_EXCESS_STEP_BPS).coerceIn(0, SECULAR_HOLD_MAX_YEARS)
                if (industryPrior.id == "internet_retail") {
                    years = maxOf(years, INTERNET_RETAIL_HOLD_MIN_YEARS)
                }
                if (industryPrior.id == "discount_store") {
                    years = maxOf(years, DISCOUNT_STORE_HOLD_MIN_YEARS)
                }
                if (fadingMargin) years = minOf(years, FADED_MARGIN_HOLD_CAP_YEARS)
                if (years > 0) reasons += "path=cap_hold:$years"
                years
            }
            else -> 0
        }
        var fadeYears = if (regime == "cyclical_or_transition") {
            path.cyclicalFadeYears
        } else {
            fadeYearsDefault
        }
        var fadeExponent = if (regime == "secular_expansion" || reinvestment) {
            maxOf(fadeExponentDefault, path.secularFadeExponentFloor)
        } else {
            fadeExponentDefault
        }
        var usedDiscount = discountBps
        if (reinvestment) {
            usedDiscount = rfBps + (erpBps * REINVESTMENT_ERP_WEIGHT_PCT) / 100
            reasons += "discount=reinvestment_target_beta:$usedDiscount"
        }
        var qualityEligible = !reinvestment &&
            (roe0Bps ?: 0) >= QUALITY_ROE_BPS &&
            usedGrowth <= QUALITY_GROWTH_MAX_BPS &&
            discountBps - (rfBps + QUALITY_RF_PREMIUM_BPS) >= QUALITY_WACC_GAP_MIN_BPS
        if (qualityEligible) {
            usedDiscount = rfBps + QUALITY_RF_PREMIUM_BPS
            reasons += "discount=quality_compounder:$usedDiscount"
        }
        var highTurnover = currentMarginBps < path.highTurnoverMarginMaxBps &&
            (roe0Bps ?: 0) >= path.highTurnoverRoeMinBps &&
            regime == "secular_expansion"
        if (highTurnover) {
            usedDiscount = rfBps + QUALITY_RF_PREMIUM_BPS
            reasons += "discount=high_turnover_compounder:$usedDiscount"
        }
        if (!qualityEligible && !reinvestment && !highTurnover &&
            (roe0Bps ?: 0) >= QUALITY_ROE_BPS &&
            usedGrowth <= path.highRoeShrinkGrowthMaxBps
        ) {
            usedDiscount = (discountBps - path.highRoeShrinkBps)
                .coerceAtLeast(rfBps + path.highRoeShrinkRfFloorBps)
            reasons += "discount=high_roe_shrink:$usedDiscount"
        }
        return FcffPath(
            holdYears = holdYears,
            fadeYears = fadeYears,
            usedGrowthBps = usedGrowth,
            startMarginBps = startMargin,
            stableMarginBps = stableMargin,
            discountBps = usedDiscount,
            fadeExponent = fadeExponent,
            reasons = reasons,
        )
    }
}

object ResidualPathPolicy {
    const val VERSION = "residual-path/4-care-quality-roe"
    val BANK_THROUGH_CYCLE_ROE_BPS: Int
        get() = ValuationPolicy.current.residualPath.bankThroughCycleRoeBps
    val BANK_THROUGH_CYCLE_MAX_LIFT_BPS: Int
        get() = ValuationPolicy.current.residualPath.bankThroughCycleMaxLiftBps
    val BANK_SPREAD_BPS: Int
        get() = ValuationPolicy.current.residualPath.bankSpreadBps
    val PC_SPREAD_BPS: Int
        get() = ValuationPolicy.current.residualPath.pcSpreadBps
    val CARE_SPREAD_BPS: Int
        get() = ValuationPolicy.current.residualPath.careSpreadBps
    val CARE_MODEST_ROE_MAX_BPS: Int
        get() = ValuationPolicy.current.residualPath.careModestRoeMaxBps
    val CARE_MODEST_SPREAD_BPS: Int
        get() = ValuationPolicy.current.residualPath.careModestSpreadBps
    val CARE_FADE_YEARS: Int
        get() = ValuationPolicy.current.residualPath.careFadeYears
    val DEFAULT_FADE_YEARS: Int
        get() = ValuationPolicy.current.residualPath.defaultFadeYears

    data class ResidualPath(
        val startingRoeBps: Int,
        val franchiseSpreadBps: Int,
        val fadeYears: Int,
        val discountAdjustBps: Int = 0,
        val reasons: List<String>,
    )

    fun resolve(
        roe0Bps: Int,
        costOfEquityBps: Int,
        industry: String?,
        sector: String?,
    ): ResidualPath {
        var text = "${industry.orEmpty()} ${sector.orEmpty()}".lowercase()
        var reasons = mutableListOf<String>()
        var starting = roe0Bps
        var spread = FRANCHISE_PERSIST_SPREAD_BPS
        var fade = DEFAULT_FADE_YEARS
        var discountAdjust = 0
        when {
            text.contains("bank") -> {
                if (roe0Bps < BANK_THROUGH_CYCLE_ROE_BPS && roe0Bps < costOfEquityBps) {
                    starting = minOf(
                        BANK_THROUGH_CYCLE_ROE_BPS,
                        roe0Bps + BANK_THROUGH_CYCLE_MAX_LIFT_BPS,
                    )
                    reasons += "roe=bank_through_cycle:$starting"
                }
                spread = BANK_SPREAD_BPS
                reasons += "spread=bank:$spread"
                if (roe0Bps >= ValuationPolicy.current.residualPath.bankHighRoeBps) {
                    discountAdjust = ValuationPolicy.current.residualPath.bankHighRoeDiscountAdjustBps
                    reasons += "discount=high_roe_bank:$discountAdjust"
                }
            }
            text.contains("insurance") || text.contains("insur") -> {
                spread = PC_SPREAD_BPS
                reasons += "spread=underwriting:$spread"
            }
            text.contains("healthcare plan") || text.contains("health care plan") ||
                text.contains("managed care") -> {
                spread = if (roe0Bps < CARE_MODEST_ROE_MAX_BPS) {
                    CARE_MODEST_SPREAD_BPS
                } else {
                    CARE_SPREAD_BPS
                }
                fade = CARE_FADE_YEARS
                reasons += if (roe0Bps < CARE_MODEST_ROE_MAX_BPS) {
                    "spread=managed_care_modest:$spread"
                } else {
                    "spread=managed_care:$spread"
                }
                reasons += "fade=managed_care:$fade"
            }
        }
        return ResidualPath(starting, spread, fade, discountAdjust, reasons)
    }
}
