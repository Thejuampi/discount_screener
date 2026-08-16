package com.discountscreener.core.engine

/**
 * Feature-conditional projection path from our drivers.
 * Street is not an input. Street is only a later scoreboard.
 */
object ValuationPathPolicy {
    const val VERSION = "valuation-path/2-cap-industry-margin"
    const val REINVESTMENT_HOLD_YEARS = 18
    const val REINVESTMENT_MARGIN_BPS = 2_800
    const val REINVESTMENT_ERP_WEIGHT_PCT = 80
    const val REINVESTMENT_RETENTION_MIN_BPS = 9_000
    const val MAX_SECULAR_GROWTH_BPS = 2_500
    const val QUALITY_ROE_BPS = 8_000
    const val QUALITY_GROWTH_MAX_BPS = 800
    const val QUALITY_WACC_GAP_MIN_BPS = 250
    const val QUALITY_RF_PREMIUM_BPS = 150
    const val MARGIN_FADE_BUFFER_BPS = 1_200
    const val SECULAR_HOLD_EXCESS_STEP_BPS = 650
    const val SECULAR_HOLD_MAX_YEARS = 8
    const val FADED_MARGIN_HOLD_CAP_YEARS = 2
    const val INTERNET_RETAIL_HOLD_MIN_YEARS = 6
    const val DISCOUNT_STORE_HOLD_MIN_YEARS = 5
    const val INTERNET_CONTENT_EXTREME_STABLE_BPS = 1_950

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
        erpBps: Int = 442,
        industry: String?,
        sector: String?,
        fadeYearsDefault: Int = 10,
        fadeExponentDefault: Double = 1.0,
    ): FcffPath {
        var reasons = mutableListOf<String>()
        var excessRoe = if (roe0Bps != null) roe0Bps - discountBps else 0
        var reinvestment = regime == "secular_expansion" &&
            roe0Bps != null &&
            roe0Bps < discountBps &&
            (retentionBps ?: 0) >= REINVESTMENT_RETENTION_MIN_BPS
        var persistFracBps = (5_000 + excessRoe / 2).coerceIn(4_500, 8_500)
        var persistGrowth = ((rawGrowthBps.toLong() * persistFracBps) / 10_000L).toInt()
        var usedGrowth = if (regime == "secular_expansion") {
            minOf(rawGrowthBps, maxOf(matureCapBps, persistGrowth), MAX_SECULAR_GROWTH_BPS)
        } else {
            cappedGrowthBps
        }
        if (usedGrowth != rawGrowthBps && regime == "secular_expansion") {
            reasons += "growth=persist_frac:${persistFracBps}:raw=$rawGrowthBps:used=$usedGrowth"
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
            currentMarginBps * 100L > industryPrior.targetFcffMarginBps * 120L
        ) {
            stableMargin = if (currentMarginBps >= 4_800 && industryPrior.id == "internet_content") {
                minOf(industryPrior.targetFcffMarginBps, INTERNET_CONTENT_EXTREME_STABLE_BPS)
            } else {
                industryPrior.targetFcffMarginBps
            }
            fadingMargin = true
            reasons += "margin=fade_to_industry:${industryPrior.id}:$stableMargin"
        }
        if (industryPrior.id == "pharma" && (roe0Bps ?: 0) < 800) {
            stableMargin = minOf(stableMargin, 1_400)
            fadingMargin = true
            reasons += "margin=low_roe_pharma:$stableMargin"
        }
        if ((industryPrior.id == "internet_retail" || industryPrior.id == "internet_content") &&
            industryPrior.matched &&
            currentMarginBps < industryPrior.targetFcffMarginBps
        ) {
            stableMargin = industryPrior.targetFcffMarginBps
            reasons += "margin=expand_to_industry:${industryPrior.id}:$stableMargin"
        }
        if (industryPrior.id == "oil_integrated" && regime == "cyclical_or_transition") {
            usedGrowth = maxOf(usedGrowth, 300)
            stableMargin = maxOf(stableMargin, industryPrior.targetFcffMarginBps)
            reasons += "path=through_cycle_commodity:g=$usedGrowth"
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
        var fadeYears = if (regime == "cyclical_or_transition") 5 else fadeYearsDefault
        var fadeExponent = if (regime == "secular_expansion" || reinvestment) {
            maxOf(fadeExponentDefault, 1.50)
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
        var highTurnover = currentMarginBps < 800 &&
            (roe0Bps ?: 0) >= 2_000 &&
            regime == "secular_expansion"
        if (highTurnover) {
            usedDiscount = rfBps + QUALITY_RF_PREMIUM_BPS
            reasons += "discount=high_turnover_compounder:$usedDiscount"
        }
        if (!qualityEligible && !reinvestment && !highTurnover &&
            (roe0Bps ?: 0) >= QUALITY_ROE_BPS &&
            usedGrowth <= 1_500
        ) {
            usedDiscount = (discountBps - 80).coerceAtLeast(rfBps + 200)
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
    const val VERSION = "residual-path/2-industry-franchise"
    const val BANK_THROUGH_CYCLE_ROE_BPS = 1_300
    const val BANK_SPREAD_BPS = 600
    const val PC_SPREAD_BPS = 120
    const val CARE_SPREAD_BPS = 2_000
    const val CARE_MODEST_ROE_MAX_BPS = 1_400
    const val CARE_MODEST_SPREAD_BPS = 350
    const val CARE_FADE_YEARS = 1
    const val DEFAULT_FADE_YEARS = 5

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
                    starting = BANK_THROUGH_CYCLE_ROE_BPS
                    reasons += "roe=bank_through_cycle:$starting"
                }
                spread = BANK_SPREAD_BPS
                reasons += "spread=bank:$spread"
                if (roe0Bps >= 1_400) {
                    discountAdjust = -70
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
