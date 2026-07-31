package com.discountscreener.core.engine

import com.discountscreener.core.model.AnnualReportedValue
import com.discountscreener.core.model.BusinessClass
import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.DiscountRateKind
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.FundamentalTimeseries
import com.discountscreener.core.model.ValuationModel
import com.discountscreener.core.model.WaccFieldSource
import com.discountscreener.core.model.WaccInputProvenance
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Valuation model family (parity with Windows `dcf_model.rs`).
 * Financial services → residual income; operating → FCFF with growth fade.
 * See `_bmad-output/planning-artifacts/valuation-model-family-architecture.md`.
 */
const val ENGINE_VERSION = "valuation-model-family/1"
/** Parity with Windows driver-based FCFF policy (closed-world routing preserved). */
const val MODEL_POLICY_VERSION = "business-class-policy/7-explicit-driver-resolution"

private const val DEFAULT_RF_BPS = 430
private const val DEFAULT_ERP_BPS = 450
private const val BETA_COMPANY_WEIGHT = 0.67
private const val BETA_INDUSTRY_WEIGHT = 0.33
private const val DEFAULT_INDUSTRY_BETA_MILLIS = 1_000
private const val PROJECTION_YEARS = 5
/** Driver ratios are regime-sensitive; keep the recent multi-year window. */
private const val DRIVER_RECENT_WINDOW = 5
private const val COE_SCENARIO_BAND_BPS = 75
/** FCFF scenarios stress discount rate when rates are market-sourced. */
private const val WACC_SCENARIO_BAND_BPS = 100
/** After provisional base uplift, bear still stresses rates further (not symmetric). */
private const val WACC_SCENARIO_BEAR_BAND_UNRELIABLE_BPS = 150
/** Bull does not further cheapen a known-soft base WACC. */
private const val WACC_SCENARIO_BULL_BAND_UNRELIABLE_BPS = 0
private const val ROE_BEAR_HAIRCUT_BPS = 300
private const val ROE_BULL_BOOST_BPS = 200
private const val GROWTH_RECENT_WINDOW = 4
/** Dynamic robustification band around stable growth; constrains inputs, not output. */
private const val MAX_NEAR_GROWTH_DISTANCE_FROM_STABLE_BPS = 1_200
private const val STABLE_GROWTH_RF_BUFFER_BPS = 100
/** Long-run nominal economy growth ceiling (bps); g_stable ≤ min(this, rf − buffer). */
private const val MACRO_STABLE_GROWTH_BPS = 300
private const val MIN_STABLE_GROWTH_BPS = 50
private const val GORDON_RATE_EPSILON_BPS = 50
/** Require both a relative and economically material CapEx jump. */
private const val CAPEX_SPIKE_RATIO = 1.40
private const val CAPEX_SPIKE_MIN_ABS_BPS = 500
/** Slower growth fade for statistically persistent expansion regimes. */
private const val SECULAR_GROWTH_FADE_EXPONENT = 1.50
/** Soft-rate debt-weight cap (Windows parity). */

data class MarketParams(
    val rfBps: Int = DEFAULT_RF_BPS,
    val erpBps: Int = DEFAULT_ERP_BPS,
    val provisional: Boolean = true,
) {
    fun stableGrowthBps(): Int =
        minOf(MACRO_STABLE_GROWTH_BPS, rfBps - STABLE_GROWTH_RF_BUFFER_BPS)
            .coerceAtLeast(MIN_STABLE_GROWTH_BPS)
}

private data class ResolvedWacc(
    val waccBps: Int,
    val provisionalWaccUpliftBps: Int = 0,
    val debtWeightBps: Int = 0,
    val inputs: WaccInputProvenance,
    val rateReasons: List<String> = emptyList(),
)

object DcfAnalysisEngine {
    fun classifyBusiness(
        sectorName: String?,
        industryName: String?,
        sectorKey: String? = null,
        industryKey: String? = null,
        assetNotEquity: Boolean = false,
    ): BusinessClass {
        if (assetNotEquity) return BusinessClass.NotEligible
        val sector = listOfNotNull(sectorName, sectorKey).joinToString(" ").lowercase()
        val industry = listOfNotNull(industryName, industryKey).joinToString(" ").lowercase()
        val blob = "$sector $industry"
        // Closed world: not-eligible → financial → operating → unclassified (fail).
        if (isNotEligibleEquityText(blob)) return BusinessClass.NotEligible
        if (isFinancialServicesText(blob)) return BusinessClass.FinancialServices
        if (isOperatingNonFinancialText(sector, industry, blob)) {
            return BusinessClass.OperatingNonFinancial
        }
        return BusinessClass.Unclassified
    }

    /** Windows `classification_unavailable_reason` parity. */
    fun classificationUnavailableReason(businessClass: BusinessClass): String? = when (businessClass) {
        BusinessClass.Unclassified ->
            "business class unclassified: sector/industry missing or not in policy tables — valuation refused (no FCFF fallback)"
        BusinessClass.NotEligible ->
            "valuation not eligible for this asset class (ETF/fund/crypto/REIT shell)"
        else -> null
    }

    fun isCurrentPolicy(analysis: DcfAnalysis): Boolean =
        analysis.engineVersion == ENGINE_VERSION && analysis.modelPolicyVersion == MODEL_POLICY_VERSION

    fun compute(
        fundamentals: FundamentalSnapshot,
        timeseries: FundamentalTimeseries,
        marketPriceCents: Long? = null,
        marketParams: MarketParams = MarketParams(),
        assetNotEquity: Boolean = false,
    ): Result<DcfAnalysis> = runCatching {
        when (
            val class_ = classifyBusiness(
                fundamentals.sectorName,
                fundamentals.industryName,
                fundamentals.sectorKey,
                fundamentals.industryKey,
                assetNotEquity,
            )
        ) {
            BusinessClass.NotEligible ->
                error(classificationUnavailableReason(BusinessClass.NotEligible)!!)
            BusinessClass.Unclassified ->
                error(classificationUnavailableReason(BusinessClass.Unclassified)!!)
            BusinessClass.FinancialServices ->
                residualIncome(fundamentals, marketPriceCents, marketParams)
            BusinessClass.OperatingNonFinancial ->
                fcffWacc(fundamentals, timeseries, marketPriceCents, marketParams)
        }
    }

    private fun containsAny(hay: String, keys: List<String>): Boolean =
        keys.any { hay.contains(it) }

    private fun isNotEligibleEquityText(blob: String): Boolean = containsAny(
        blob,
        listOf(
            "exchange traded", "etf", "closed-end fund", "closed end fund", "mutual fund",
            "money market", "cryptocurrency", "crypto ", "digital currency",
            "reit", "real estate investment trust", "mortgage reit", "equity reit",
        ),
    )

    private fun isFinancialServicesText(blob: String): Boolean = containsAny(
        blob,
        listOf(
            "financial services", "financials", "financial", "insurance", "insur",
            "bank", "banks", "capital markets", "asset management", "credit services",
            "mortgage finance", "reinsurance", "life insurance",
            "property & casualty", "property and casualty", "property casualty",
            "diversified financial", "financial conglomerate", "savings & loan", "thrift",
            "brokerage", "investment banking", "specialty insurance", "p&c",
            "healthcare plans", "health care plans", "healthcare-plans", "health-care-plans",
            "managed care", "managed-care", "health insurance", "medical insurance",
            "insurance brokers", "insurance-brokers", "credit card", "consumer finance",
            "shell companies",
        ),
    )

    private fun isOperatingNonFinancialText(sector: String, industry: String, blob: String): Boolean {
        val operatingSectors = listOf(
            "technology", "information technology", "industrials", "industrial",
            "consumer cyclical", "consumer defensive", "consumer staples", "consumer discretionary",
            "energy", "utilities", "basic materials", "materials",
            "communication services", "communication", "telecommunications",
        )
        if (containsAny(sector, operatingSectors)) return true
        if (sector.contains("healthcare") || sector.contains("health care")) {
            if (isFinancialServicesText(industry) || isFinancialServicesText(blob)) return false
            if (industry.trim().isEmpty()) return false
            val healthOperating = listOf(
                "drug", "pharma", "biotech", "biotechnology", "device", "devices", "diagnostics",
                "medical instruments", "medical devices", "medical care", "medical distribution",
                "health information", "health care equipment", "healthcare equipment",
                "hospitals", "medical facilities", "tools & diagnostics", "tools and diagnostics",
            )
            return containsAny(industry, healthOperating) || containsAny(blob, healthOperating)
        }
        val operatingIndustry = listOf(
            "software", "semiconductor", "semiconductors", "hardware", "computer",
            "internet content", "internet retail", "it services", "information technology services",
            "electronic", "aerospace", "defense", "airlines", "railroad", "trucking", "logistics",
            "machinery", "construction", "building products", "real estate services",
            "property management", "engineering", "waste management",
            "farming", "agriculture", "auto manufacturers", "auto parts", "automobiles",
            "restaurants", "apparel", "footwear", "lodging", "leisure", "entertainment",
            "packaging", "tobacco", "beverages", "food products", "confectioners",
            "household products", "personal products", "discount stores", "department stores",
            "specialty retail", "oil & gas", "oil and gas", "oil gas", "thermal coal", "uranium",
            "renewable", "solar", "electric utilities", "gas utilities", "water utilities",
            "independent power", "diversified utilities", "chemicals", "specialty chemicals",
            "steel", "aluminum", "copper", "gold", "silver", "other industrial metals",
            "other precious metals", "coking coal", "lumber", "paper", "building materials",
            "telecom", "telecommunications", "media", "publishing", "broadcasting",
            "advertising", "interactive media",
        )
        return containsAny(industry, operatingIndustry) || containsAny(blob, operatingIndustry)
    }

    private fun residualIncome(
        fundamentals: FundamentalSnapshot,
        marketPriceCents: Long?,
        marketParams: MarketParams,
    ): DcfAnalysis {
        val shares = latestShareCount(fundamentals, FundamentalTimeseries())
            ?: error("DCF unavailable: share count is missing.")
        val bvpsCents = resolveBookValuePerShareCents(fundamentals, marketPriceCents)
            ?: error("DCF unavailable: book equity is missing.")
        val book0 = (bvpsCents / 100.0) * shares
        require(book0.isFinite() && book0 > 0.0) { "book equity is not positive" }
        val roe0Bps = fundamentals.returnOnEquityBps
            ?.takeIf { it > 0 && it < 10_000 }
            ?: error("DCF unavailable: return on equity is missing or invalid.")
        val retentionBps = fundamentals.retentionBps
            ?.takeIf { it in 0..10_000 }
            ?: error("DCF unavailable: retention/payout is missing or invalid.")

        val (reBase, betaSource, betaProv) = costOfEquityBps(fundamentals, marketParams)
        val retention = retentionBps / 10_000.0
        val bearRe = reBase + COE_SCENARIO_BAND_BPS
        val bullRe = (reBase - COE_SCENARIO_BAND_BPS).coerceAtLeast(marketParams.rfBps + 50)

        val bear = riScenario(
            book0, shares,
            (roe0Bps - ROE_BEAR_HAIRCUT_BPS).coerceAtLeast(100),
            bearRe,
            retention * 0.9,
        ) ?: error("bear residual income invalid")
        val base = riScenario(book0, shares, roe0Bps, reBase, retention)
            ?: error("base residual income invalid")
        val bull = riScenario(
            book0, shares,
            (roe0Bps + ROE_BULL_BOOST_BPS).coerceAtMost(9_000),
            bullRe,
            retention.coerceAtMost(0.85),
        ) ?: error("bull residual income invalid")

        val waccInputs = WaccInputProvenance(
            beta = betaSource,
            totalDebt = WaccFieldSource.NotApplicable,
            totalCash = WaccFieldSource.NotApplicable,
            costOfDebt = WaccFieldSource.NotApplicable,
            taxRate = WaccFieldSource.NotApplicable,
            waccClamped = betaProv || marketParams.provisional,
        )
        val reasons = buildList {
            add("model=residual_income_equity")
            add("business_class=financial_services")
            add("retention_source=reported:${retentionBps}bps")
            add("terminal_roe_fades_to_cost_of_equity")
            add("scenario_stress=growth_and_discount_rate")
            if (marketParams.provisional) add("market_params=provisional")
            if (waccInputs.pointEstimateUnreliable()) add("point_estimate=unreliable")
        }

        return DcfAnalysis(
            bearIntrinsicValueCents = bear,
            baseIntrinsicValueCents = base,
            bullIntrinsicValueCents = bull,
            waccBps = reBase,
            baseGrowthBps = ((roe0Bps / 10_000.0) * retention * 10_000.0).roundToInt(),
            netDebtDollars = 0L,
            waccInputs = waccInputs,
            engineVersion = ENGINE_VERSION,
            modelPolicyVersion = MODEL_POLICY_VERSION,
            businessClass = BusinessClass.FinancialServices,
            model = ValuationModel.ResidualIncomeEquity,
            discountRateKind = DiscountRateKind.CostOfEquity,
            stableGrowthBps = marketParams.stableGrowthBps()
                .coerceAtMost(reBase - GORDON_RATE_EPSILON_BPS),
            bookValuePerShareCents = bvpsCents,
            roe0Bps = roe0Bps,
            reasonCodes = reasons,
            pointEstimateUnreliable = waccInputs.pointEstimateUnreliable(),
            scenarioStress = "growth_and_discount_rate",
            waccBearBps = bearRe,
            waccBullBps = bullRe,
            valuationDriver = "residual_income",
            growthDriver = "roe_retention",
            driverInputFingerprint = null,
            driverProvenance = listOf(
                "source=provider_timeseries",
                "model=residual_income_equity",
            ),
        )
    }

    private fun riScenario(
        book0: Double,
        shares: Double,
        roe0Bps: Int,
        reBps: Int,
        retention: Double,
    ): Long? {
        if (book0 <= 0.0 || shares <= 0.0 || reBps <= 0) return null
        val re = reBps / 10_000.0
        val roe0 = roe0Bps / 10_000.0
        val roeStable = re
        var book = book0
        var pvRi = 0.0
        for (t in 1..PROJECTION_YEARS) {
            val w = t.toDouble() / PROJECTION_YEARS
            val roeT = roe0 * (1.0 - w) + roeStable * w
            val excess = (roeT - re) * book
            pvRi += excess / (1.0 + re).pow(t)
            book *= 1.0 + roeT * retention
            if (!book.isFinite() || book <= 0.0) return null
        }
        val equity = book0 + pvRi
        if (!equity.isFinite() || equity <= 0.0) return null
        return ((equity / shares) * 100.0).roundToLong()
    }

    private fun resolveBookValuePerShareCents(
        fundamentals: FundamentalSnapshot,
        marketPriceCents: Long?,
    ): Long? {
        fundamentals.bookValuePerShareCents?.takeIf { it > 0 }?.let { return it }
        val price = marketPriceCents?.takeIf { it > 0 }?.div(100.0) ?: return null
        val pb = fundamentals.priceToBookHundredths?.takeIf { it > 0 }?.div(100.0) ?: return null
        if (pb <= 0.0) return null
        val bvps = price / pb
        if (!bvps.isFinite() || bvps <= 0.0) return null
        return (bvps * 100.0).roundToLong()
    }

    private fun fcffWacc(
        fundamentals: FundamentalSnapshot,
        timeseries: FundamentalTimeseries,
        marketPriceCents: Long?,
        marketParams: MarketParams,
    ): DcfAnalysis {
        require(timeseries.freeCashFlow.size >= 3) {
            "need at least 3 annual free cash flow points"
        }
        val currentShares = latestShareCount(fundamentals, timeseries)
            ?: error("share count is missing")
        val resolvedWacc = deriveWacc(fundamentals, timeseries, marketPriceCents, marketParams)
        val netDebtDollars = (fundamentals.totalDebtDollars ?: 0L) - (fundamentals.totalCashDollars ?: 0L)
        val drivers = driverModelInputs(timeseries)
            ?: error("fcff unavailable: at least three aligned annual OCF, CapEx, revenue, interest, and effective-tax driver rows are required")
        return fcffDriverWacc(
            fundamentals = fundamentals,
            timeseries = timeseries,
            currentShares = currentShares,
            netDebtDollars = netDebtDollars,
            resolvedWacc = resolvedWacc,
            marketParams = marketParams,
            drivers = drivers,
        )

        /* The legacy FCF-level fallback is intentionally removed. */
        /*
        val (runRate, fcfNormalized) = fcfRunRateDollars(timeseries)
            ?: error("insufficient positive free cash flow for run-rate")
        val rawGNear = recentFcfGrowthBps(timeseries)
            ?: error("insufficient positive free cash flow history for growth")
        val gStableBase = marketParams.stableGrowthBps()
            .coerceAtMost(resolvedWacc.waccBps - GORDON_RATE_EPSILON_BPS)
            .coerceAtLeast(MIN_STABLE_GROWTH_BPS)
        val gNear = rawGNear.coerceIn(
            gStableBase - MAX_NEAR_GROWTH_DISTANCE_FROM_STABLE_BPS,
            gStableBase + MAX_NEAR_GROWTH_DISTANCE_FROM_STABLE_BPS,
        )

        // Scenario paths: fade growth AND stress WACC.
        // Provisional path: base already includes debt-scaled WACC uplift (see deriveWacc).
        //   bear: +additional band from that base
        //   bull: +0 bps on WACC (do not cheapen further a known-soft base; growth still varies)
        val ratesUnreliable = resolvedWacc.inputs.pointEstimateUnreliable()
        val (bearBand, bullBand) = if (ratesUnreliable) {
            WACC_SCENARIO_BEAR_BAND_UNRELIABLE_BPS to WACC_SCENARIO_BULL_BAND_UNRELIABLE_BPS
        } else {
            WACC_SCENARIO_BAND_BPS to WACC_SCENARIO_BAND_BPS
        }
        val bearNear = (gNear - 400).coerceAtLeast(-1_200)
        val bullNear = (gNear + 400).coerceAtMost(2_400)
        val bearWacc = resolvedWacc.waccBps + bearBand
        val bullWacc = (resolvedWacc.waccBps - bullBand)
            .coerceAtLeast(marketParams.rfBps + 50)
            .coerceAtLeast(gStableBase + GORDON_RATE_EPSILON_BPS)
        val bearGStable = gStableBase
            .coerceAtMost(bearWacc - GORDON_RATE_EPSILON_BPS)
            .coerceAtLeast(MIN_STABLE_GROWTH_BPS)
        val bullGStable = gStableBase
            .coerceAtMost(bullWacc - GORDON_RATE_EPSILON_BPS)
            .coerceAtLeast(MIN_STABLE_GROWTH_BPS)

        val bear = discountedFcffFade(runRate, currentShares, netDebtDollars, bearNear, bearGStable, bearWacc)
            ?: error("bear scenario invalid")
        val base = discountedFcffFade(runRate, currentShares, netDebtDollars, gNear, gStableBase, resolvedWacc.waccBps)
            ?: error("base scenario invalid")
        val bull = discountedFcffFade(runRate, currentShares, netDebtDollars, bullNear, bullGStable, bullWacc)
            ?: error("bull scenario invalid")

        val scenarioStress = if (ratesUnreliable) {
            "growth_and_discount_rate_asymmetric_provisional"
        } else {
            "growth_and_discount_rate"
        }
        val reasons = buildList {
            add("model=fcff_wacc")
            add("business_class=operating_non_financial")
            add("growth=recent_window_fade_to_stable")
            add("scenario_stress=growth_and_discount_rate")
            if (fcfNormalized) add("fcf_run_rate=recent_window_average")
            else add("fcf_run_rate=latest_positive")
            if (gNear != rawGNear) {
                add("growth=recent_window_robustified:raw=$rawGNear:used=$gNear")
            }
            if (marketParams.provisional) add("market_params=provisional")
            if (ratesUnreliable) {
                add("point_estimate=unreliable")
                add("wacc_stress=asymmetric_provisional_bear+${bearBand}_bull=base_no_further_cheapening")
            }
            if (resolvedWacc.provisionalWaccUpliftBps > 0) {
                add("wacc=provisional_base_uplift:${resolvedWacc.provisionalWaccUpliftBps}")
            }
            if (listOf(bear, base, bull).any { it == 0L }) {
                add("equity_value_floor=limited_liability")
            }
        }

        return DcfAnalysis(
            bearIntrinsicValueCents = bear,
            baseIntrinsicValueCents = base,
            bullIntrinsicValueCents = bull,
            waccBps = resolvedWacc.waccBps,
            baseGrowthBps = gNear,
            netDebtDollars = netDebtDollars,
            waccInputs = resolvedWacc.inputs,
            engineVersion = ENGINE_VERSION,
            modelPolicyVersion = MODEL_POLICY_VERSION,
            businessClass = BusinessClass.OperatingNonFinancial,
            model = ValuationModel.FcffWacc,
            discountRateKind = DiscountRateKind.Wacc,
            stableGrowthBps = gStableBase,
            bookValuePerShareCents = fundamentals.bookValuePerShareCents,
            roe0Bps = fundamentals.returnOnEquityBps,
            reasonCodes = reasons,
            latestFcfDollars = timeseries.freeCashFlow.lastOrNull()?.value?.roundToLong(),
            fcfRunRateDollars = runRate.roundToLong(),
            fcfRunRateNormalized = fcfNormalized,
            provisionalWaccUpliftBps = resolvedWacc.provisionalWaccUpliftBps,
            debtWeightBps = resolvedWacc.debtWeightBps,
            pointEstimateUnreliable = ratesUnreliable,
            scenarioStress = scenarioStress,
            waccBearBps = bearWacc,
            waccBullBps = bullWacc,
            valuationDriver = "fcf_history_fade",
            growthDriver = "fcf_endpoint_robustified",
            driverInputFingerprint = driverInputFingerprint(timeseries),
            driverProvenance = listOf(
                "source=provider_timeseries",
                "fallback=fcf_history_fade",
            ),
        )
        */
    }

    private data class DriverPoint(
        val year: Int,
        val revenueDollars: Double,
        val fcffMarginBps: Int,
        val ocfMarginBps: Int,
        val capexIntensityBps: Int,
        val afterTaxInterestMarginBps: Int,
        val revenueGrowthBps: Int?,
        val capexSpike: Boolean,
    )

    private data class DriverModelInputs(
        val latestRevenueDollars: Double,
        val normalizedFcffDollars: Double,
        val baseGrowthBps: Int,
        val bearGrowthBps: Int,
        val bullGrowthBps: Int,
        val baseFcffMarginBps: Int,
        val bearFcffMarginBps: Int,
        val bullFcffMarginBps: Int,
        val normalizedOcfMarginBps: Int,
        val normalizedCapexIntensityBps: Int,
        val normalizedAfterTaxInterestMarginBps: Int,
        val capexSpikeYears: List<Int>,
        val driverRegime: String,
        val growthDispersionBps: Int,
        val growthFadeExponent: Double,
        val taxDefaulted: Boolean,
    )

    /**
     * Build FCFF from operating drivers rather than combining a normalized
     * cash-flow level with the last reported FCF CAGR. This keeps a CapEx-cycle
     * recovery such as AMZN's internally consistent and auditable.
     */
    private fun driverModelInputs(timeseries: FundamentalTimeseries): DriverModelInputs? {
        val capexByPeriod = timeseries.capitalExpenditure.associateBy(::annualKey)
        val revenueByPeriod = timeseries.revenue.associateBy(::annualKey)
        val interestByPeriod = timeseries.interestExpense.associateBy(::annualKey)
        val taxByPeriod = timeseries.taxRateForCalcs.associateBy(::annualKey)

        val raw = timeseries.operatingCashFlow
            .asSequence()
            .sortedBy(::annualKey)
            .mapNotNull { operating ->
                val period = annualKey(operating)
                val capex = capexByPeriod[period]?.value ?: return@mapNotNull null
                val revenue = revenueByPeriod[period]?.value ?: return@mapNotNull null
                if (!operating.value.isFinite() || !capex.isFinite() || !revenue.isFinite()) {
                    return@mapNotNull null
                }
                if (revenue <= 0.0) {
                    return@mapNotNull null
                }
                val interest = interestByPeriod[period]?.value
                    ?.takeIf { it.isFinite() }
                    ?.let { kotlin.math.abs(it) }
                    ?: return@mapNotNull null
                val tax = normalizedTaxBps(taxByPeriod[period]?.value)
                    ?: return@mapNotNull null
                val fcff = operating.value + interest * (1.0 - tax / 10_000.0) - kotlin.math.abs(capex)
                val fcffMargin = (fcff / revenue * 10_000.0).roundToInt()
                if (!fcff.isFinite()) return@mapNotNull null
                val afterTaxInterestMargin = (
                    interest * (1.0 - tax / 10_000.0) / revenue * 10_000.0
                    ).roundToInt()
                DriverRow(
                    date = period,
                    year = operating.fiscalYear ?: parseYmd(operating.asOfDate)?.year
                        ?: return@mapNotNull null,
                    revenueDollars = revenue,
                    fcffMarginBps = fcffMargin,
                    ocfMarginBps = (operating.value / revenue * 10_000.0).roundToInt(),
                    capexIntensityBps = (kotlin.math.abs(capex) / revenue * 10_000.0).roundToInt(),
                    afterTaxInterestMarginBps = afterTaxInterestMargin,
                    taxMissing = false,
                )
            }
            .toList()
        if (raw.size < 3) return null

        var previousWasSpike = false
        val driverPoints = buildList {
            raw.forEachIndexed { index, row ->
                val priorIntensities = raw.take(index).map { it.capexIntensityBps }
                val priorMedian = medianBps(priorIntensities)
                val spike = priorIntensities.size >= 3 &&
                    !previousWasSpike &&
                    row.capexIntensityBps > priorMedian * CAPEX_SPIKE_RATIO &&
                    row.capexIntensityBps >= priorMedian + CAPEX_SPIKE_MIN_ABS_BPS
                val growth = if (index == 0) {
                    null
                } else {
                    val previousRevenue = raw[index - 1].revenueDollars
                    ((row.revenueDollars / previousRevenue - 1.0) * 10_000.0)
                        .takeIf { it.isFinite() }
                        ?.roundToInt()
                }
                add(
                    DriverPoint(
                        year = row.year,
                        revenueDollars = row.revenueDollars,
                        fcffMarginBps = row.fcffMarginBps,
                        ocfMarginBps = row.ocfMarginBps,
                        capexIntensityBps = row.capexIntensityBps,
                        afterTaxInterestMarginBps = row.afterTaxInterestMarginBps,
                        revenueGrowthBps = growth,
                        capexSpike = spike,
                    ),
                )
                previousWasSpike = spike
            }
        }

        val recentStart = (driverPoints.size - DRIVER_RECENT_WINDOW).coerceAtLeast(0)
        val recentPoints = driverPoints.takeLast(DRIVER_RECENT_WINDOW)
        val recentBaseline = recentPoints.filterNot { it.capexSpike }
            .let { if (it.size >= 2) it else recentPoints }
        val priorStart = (recentStart - DRIVER_RECENT_WINDOW).coerceAtLeast(0)
        val priorPoints = driverPoints.subList(priorStart, recentStart)
        val priorBaseline = priorPoints.filterNot { it.capexSpike }
        var recentGrowths = recentPoints.mapNotNull { it.revenueGrowthBps }
            .let { if (it.size >= 2) it else driverPoints.mapNotNull { point -> point.revenueGrowthBps } }
        if (recentBaseline.size < 2 || recentGrowths.size < 2) return null

        val priorGrowths = priorPoints.mapNotNull { it.revenueGrowthBps }
        val regime = classifyDriverRegime(recentGrowths, priorGrowths)
        val useCycleBlend = regime == DriverRegime.CyclicalOrTransition &&
            priorBaseline.size >= 2 && priorGrowths.size >= 2
        val scenarioPoints = if (useCycleBlend) recentBaseline + priorBaseline else recentBaseline
        val margins = scenarioPoints.map { it.fcffMarginBps }
        val recentOcfMargins = recentBaseline.map { it.ocfMarginBps }
        val recentCapexIntensities = recentBaseline.map { it.capexIntensityBps }
        val recentInterestMargins = recentBaseline.map { it.afterTaxInterestMarginBps }
        val scenarioGrowths = if (useCycleBlend) recentGrowths + priorGrowths else recentGrowths

        val recentOcfMargin = medianBps(recentOcfMargins)
        val recentCapexIntensity = medianBps(recentCapexIntensities)
        val recentInterestMargin = medianBps(recentInterestMargins)
        val (normalizedOcfMargin, normalizedCapexIntensity, normalizedInterestMargin) = if (useCycleBlend) {
            val priorOcf = medianBps(priorBaseline.map { it.ocfMarginBps })
            val priorCapex = medianBps(priorBaseline.map { it.capexIntensityBps })
            val priorInterest = medianBps(priorBaseline.map { it.afterTaxInterestMarginBps })
            Triple(
                blendRecentPrior(recentOcfMargin, priorOcf),
                blendRecentPrior(recentCapexIntensity, priorCapex),
                blendRecentPrior(recentInterestMargin, priorInterest),
            )
        } else {
            Triple(recentOcfMargin, recentCapexIntensity, recentInterestMargin)
        }
        val baseMargin = normalizedOcfMargin + normalizedInterestMargin - normalizedCapexIntensity
        val bearMargin = quantileBps(margins, 0.25).coerceAtMost(baseMargin)
        val bullMargin = quantileBps(margins, 0.75).coerceAtLeast(baseMargin)
        val bearGrowth = quantileBps(scenarioGrowths, 0.25)
        val bullGrowth = quantileBps(scenarioGrowths, 0.75)
        val baseGrowth = if (useCycleBlend) {
            blendRecentPrior(medianBps(recentGrowths), medianBps(priorGrowths))
                .coerceIn(bearGrowth, bullGrowth)
        } else {
            medianBps(recentGrowths)
        }
        val growthDispersion = quantileBps(recentGrowths, 0.75) -
            quantileBps(recentGrowths, 0.25)
        val latestRevenue = driverPoints.lastOrNull()?.revenueDollars ?: return null
        val normalizedFcff = latestRevenue * baseMargin / 10_000.0
        if (!normalizedFcff.isFinite()) return null

        return DriverModelInputs(
            latestRevenueDollars = latestRevenue,
            normalizedFcffDollars = normalizedFcff,
            baseGrowthBps = baseGrowth,
            bearGrowthBps = bearGrowth,
            bullGrowthBps = bullGrowth,
            baseFcffMarginBps = baseMargin,
            bearFcffMarginBps = bearMargin,
            bullFcffMarginBps = bullMargin,
            normalizedOcfMarginBps = normalizedOcfMargin,
            normalizedCapexIntensityBps = normalizedCapexIntensity,
            normalizedAfterTaxInterestMarginBps = normalizedInterestMargin,
            capexSpikeYears = driverPoints.filter { it.capexSpike }.map { it.year },
            driverRegime = regime.asString(),
            growthDispersionBps = growthDispersion,
            growthFadeExponent = growthFadeExponent(regime),
            taxDefaulted = raw.any { it.taxMissing },
        )
    }

    private enum class DriverRegime {
        SecularExpansion,
        StableOperating,
        CyclicalOrTransition,
        ;

        fun asString(): String = when (this) {
            SecularExpansion -> "secular_expansion"
            StableOperating -> "stable_operating"
            CyclicalOrTransition -> "cyclical_or_transition"
        }
    }

    private fun classifyDriverRegime(recentGrowths: List<Int>, priorGrowths: List<Int>): DriverRegime {
        val recentMedian = medianBps(recentGrowths)
        val positiveShare = recentGrowths.count { it > 0 } * 10_000 / recentGrowths.size
        val dispersion = quantileBps(recentGrowths, 0.75) - quantileBps(recentGrowths, 0.25)
        val priorMedian = medianBps(priorGrowths)
        return when {
            recentMedian >= 500 && positiveShare >= 7_500 &&
                (priorGrowths.isEmpty() || recentMedian >= priorMedian) &&
                (dispersion <= 4_000 || (positiveShare == 10_000 && recentMedian >= 1_000 && dispersion <= 8_000)) ->
                DriverRegime.SecularExpansion
            dispersion >= 2_000 || positiveShare <= 5_000 -> DriverRegime.CyclicalOrTransition
            else -> DriverRegime.StableOperating
        }
    }

    private fun growthFadeExponent(regime: DriverRegime): Double = when (regime) {
        DriverRegime.SecularExpansion -> SECULAR_GROWTH_FADE_EXPONENT
        DriverRegime.StableOperating, DriverRegime.CyclicalOrTransition -> 1.0
    }

    private fun blendRecentPrior(recent: Int, prior: Int): Int =
        ((recent.toLong() * 6L + prior.toLong() * 4L) / 10L).toInt()

    private fun driverInputFingerprint(timeseries: FundamentalTimeseries): String {
        val operatingByPeriod = timeseries.operatingCashFlow.associateBy(::annualKey)
        val capexByPeriod = timeseries.capitalExpenditure.associateBy(::annualKey)
        val revenueByPeriod = timeseries.revenue.associateBy(::annualKey)
        val interestByPeriod = timeseries.interestExpense.associateBy(::annualKey)
        val taxByPeriod = timeseries.taxRateForCalcs.associateBy(::annualKey)
        val debtByPeriod = timeseries.totalDebt.associateBy(::annualKey)
        val marginalTaxByPeriod = timeseries.marginalTaxRate.associateBy(::annualKey)
        val marketYieldByPeriod = timeseries.marketYieldBps.associateBy(::annualKey)
        val ratedSpreadByPeriod = timeseries.ratedOrSyntheticSpreadBps.associateBy(::annualKey)
        fun dollars(value: Double?): String = value
            ?.takeIf { it.isFinite() }
            ?.roundToLong()
            ?.toString()
            ?: "-"
        return timeseries.freeCashFlow
            .sortedBy(::annualKey)
            .joinToString("|") { point ->
                val period = annualKey(point)
                val year = period.substringBefore("-")
                val capex = capexByPeriod[period]?.value?.let { kotlin.math.abs(it) }
                val interest = interestByPeriod[period]?.value?.let { kotlin.math.abs(it) }
                val tax = normalizedTaxBps(taxByPeriod[period]?.value)
                listOf(
                    year,
                    dollars(point.value),
                    dollars(operatingByPeriod[period]?.value),
                    dollars(capex),
                    dollars(revenueByPeriod[period]?.value),
                    dollars(interest),
                    tax?.toString() ?: "-",
                    dollars(debtByPeriod[period]?.value),
                    normalizeTaxBps(marginalTaxByPeriod[period]?.value)?.toString() ?: "-",
                    marginalTaxSourceToken(marginalTaxByPeriod[period]?.concept),
                    marketYieldByPeriod[period]?.value?.roundToInt()?.toString() ?: "-",
                    ratedSpreadByPeriod[period]?.value?.roundToInt()?.toString() ?: "-",
                ).joinToString(":")
            }
    }

    private fun marginalTaxSourceToken(concept: String?): String = when {
        concept?.contains("Reconciliation", ignoreCase = true) == true -> "tax_reconciliation"
        concept?.contains("Statutory", ignoreCase = true) == true -> "jurisdiction_statutory"
        concept?.contains("Domicile", ignoreCase = true) == true -> "domicile_tax_proxy"
        concept != null -> "reported_marginal_tax"
        else -> "-"
    }

    private data class DriverRow(
        val date: String,
        val year: Int,
        val revenueDollars: Double,
        val fcffMarginBps: Int,
        val ocfMarginBps: Int,
        val capexIntensityBps: Int,
        val afterTaxInterestMarginBps: Int,
        val taxMissing: Boolean,
    )

    private fun normalizedTaxBps(value: Double?): Int? {
        val raw = value?.takeIf { it.isFinite() } ?: return null
        val bps = when {
            kotlin.math.abs(raw) <= 1.0 -> raw * 10_000.0
            kotlin.math.abs(raw) <= 100.0 -> raw * 100.0
            else -> raw
        }
    return bps.coerceIn(0.0, 5_000.0).roundToInt()
    }

    private fun medianBps(values: List<Int>): Int {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            ((sorted[middle - 1].toLong() + sorted[middle].toLong()) / 2L).toInt()
        } else {
            sorted[middle]
        }
    }

    private fun quantileBps(values: List<Int>, quantile: Double): Int {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        val index = (((sorted.size - 1) * quantile).roundToInt())
            .coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private fun discountedDriverFcff(
        latestRevenueDollars: Double,
        fcffMarginBps: Int,
        stableFcffMarginBps: Int,
        revenueGrowthBps: Int,
        currentShares: Double,
        netDebtDollars: Long,
        gStableBps: Int,
        waccBps: Int,
        growthFadeExponent: Double,
    ): Long? {
        if (latestRevenueDollars <= 0.0 || currentShares <= 0.0 || stableFcffMarginBps <= 0 ||
            revenueGrowthBps <= -10_000 || gStableBps >= waccBps
        ) return null
        val wacc = waccBps / 10_000.0
        val nearGrowth = revenueGrowthBps / 10_000.0
        val stableGrowth = gStableBps / 10_000.0
        val margin = fcffMarginBps / 10_000.0
        var revenue = latestRevenueDollars
        var presentValue = 0.0
        for (year in 1..PROJECTION_YEARS) {
            val fade = (year.toDouble() / PROJECTION_YEARS).pow(growthFadeExponent)
            val growth = nearGrowth * (1.0 - fade) + stableGrowth * fade
            revenue *= 1.0 + growth
            // Bear/bull margins are near-term stresses. Fade them to the
            // normalized base margin instead of making a temporary CapEx
            // regime perpetual in the terminal value.
            val marginT = margin * (1.0 - fade) +
                (stableFcffMarginBps / 10_000.0) * fade
            val fcff = revenue * marginT
            if (!fcff.isFinite()) return null
            presentValue += fcff / (1.0 + wacc).pow(year)
        }
        val terminalMargin = stableFcffMarginBps / 10_000.0
        val terminalFcff = revenue * (1.0 + stableGrowth) * terminalMargin
        val terminalValue = terminalFcff / (wacc - stableGrowth)
        val enterpriseValue = presentValue + terminalValue / (1.0 + wacc).pow(PROJECTION_YEARS)
        val equityValue = enterpriseValue - netDebtDollars
        if (!equityValue.isFinite()) return null
        // Common equity is bounded below by zero. Keep a zero bear case when
        // net debt consumes enterprise value; this is capital-structure
        // economics, not a price/analyst cap.
        return ((equityValue.coerceAtLeast(0.0) / currentShares) * 100.0).roundToLong()
    }

    private fun fcffDriverWacc(
        fundamentals: FundamentalSnapshot,
        timeseries: FundamentalTimeseries,
        currentShares: Double,
        netDebtDollars: Long,
        resolvedWacc: ResolvedWacc,
        marketParams: MarketParams,
        drivers: DriverModelInputs,
    ): DcfAnalysis {
        if (drivers.baseFcffMarginBps <= 0) {
            error("driver-normalized FCFF is not positive after recent-history and CapEx-regime normalization")
        }
        val stableGrowthBase = marketParams.stableGrowthBps()
            .coerceAtMost(resolvedWacc.waccBps - GORDON_RATE_EPSILON_BPS)
            .coerceAtLeast(MIN_STABLE_GROWTH_BPS)
        val ratesUnreliable = resolvedWacc.inputs.pointEstimateUnreliable()
        val (bearBand, bullBand) = if (ratesUnreliable) {
            WACC_SCENARIO_BEAR_BAND_UNRELIABLE_BPS to WACC_SCENARIO_BULL_BAND_UNRELIABLE_BPS
        } else {
            WACC_SCENARIO_BAND_BPS to WACC_SCENARIO_BAND_BPS
        }
        val bearWacc = resolvedWacc.waccBps + bearBand
        val bullWacc = (resolvedWacc.waccBps - bullBand)
            .coerceAtLeast(marketParams.rfBps + 50)
            .coerceAtLeast(stableGrowthBase + GORDON_RATE_EPSILON_BPS)
        val bearStableGrowth = stableGrowthBase
            .coerceAtMost(bearWacc - GORDON_RATE_EPSILON_BPS)
            .coerceAtLeast(MIN_STABLE_GROWTH_BPS)
        val bullStableGrowth = stableGrowthBase
            .coerceAtMost(bullWacc - GORDON_RATE_EPSILON_BPS)
            .coerceAtLeast(MIN_STABLE_GROWTH_BPS)

        val bear = discountedDriverFcff(
            drivers.latestRevenueDollars, drivers.bearFcffMarginBps,
            drivers.baseFcffMarginBps, drivers.bearGrowthBps,
            currentShares, netDebtDollars, bearStableGrowth, bearWacc,
            drivers.growthFadeExponent,
        ) ?: error("bear driver scenario invalid")
        val base = discountedDriverFcff(
            drivers.latestRevenueDollars, drivers.baseFcffMarginBps,
            drivers.baseFcffMarginBps, drivers.baseGrowthBps,
            currentShares, netDebtDollars, stableGrowthBase, resolvedWacc.waccBps,
            drivers.growthFadeExponent,
        ) ?: error("base driver scenario invalid")
        val bull = discountedDriverFcff(
            drivers.latestRevenueDollars, drivers.bullFcffMarginBps,
            drivers.baseFcffMarginBps, drivers.bullGrowthBps,
            currentShares, netDebtDollars, bullStableGrowth, bullWacc,
            drivers.growthFadeExponent,
        ) ?: error("bull driver scenario invalid")
        check(bear <= base && base <= bull) {
            "driver scenarios not ordered after driver transition"
        }

        val latestFcf = timeseries.freeCashFlow.maxByOrNull { it.asOfDate }?.value?.roundToLong()
        val reasons = buildList {
            add("model=fcff_wacc")
            add("business_class=operating_non_financial")
            add("valuation_driver=driver_based_fcff")
            add("fcff=ocf_plus_after_tax_interest_minus_capex")
            add("growth=recent_driver_median:regime=${drivers.driverRegime}")
            add("growth_fade=regime:${drivers.driverRegime}_exponent:${"%.2f".format(java.util.Locale.US, drivers.growthFadeExponent)}")
            add(
                "fcff_bridge=ocf_margin:${drivers.normalizedOcfMarginBps}+" +
                    "after_tax_interest_margin:${drivers.normalizedAfterTaxInterestMarginBps}-" +
                    "capex_intensity:${drivers.normalizedCapexIntensityBps}",
            )
            add("scenario_stress=growth_margin_and_discount_rate")
            if (drivers.capexSpikeYears.isNotEmpty()) {
                add("capex=investment_spike_years:${drivers.capexSpikeYears.joinToString(",")}")
            }
            addAll(resolvedWacc.rateReasons)
            if (marketParams.provisional) add("market_params=provisional")
            if (ratesUnreliable) {
                add("point_estimate=unreliable")
                add("wacc_stress=asymmetric_provisional_bear+${bearBand}_bull=base_no_further_cheapening")
            }
            if (resolvedWacc.provisionalWaccUpliftBps > 0) {
                add("wacc=provisional_base_uplift:${resolvedWacc.provisionalWaccUpliftBps}")
            }
        }
        return DcfAnalysis(
            bearIntrinsicValueCents = bear,
            baseIntrinsicValueCents = base,
            bullIntrinsicValueCents = bull,
            waccBps = resolvedWacc.waccBps,
            baseGrowthBps = drivers.baseGrowthBps,
            netDebtDollars = netDebtDollars,
            waccInputs = resolvedWacc.inputs,
            engineVersion = ENGINE_VERSION,
            modelPolicyVersion = MODEL_POLICY_VERSION,
            businessClass = BusinessClass.OperatingNonFinancial,
            model = ValuationModel.FcffWacc,
            discountRateKind = DiscountRateKind.Wacc,
            stableGrowthBps = stableGrowthBase,
            bookValuePerShareCents = fundamentals.bookValuePerShareCents,
            roe0Bps = fundamentals.returnOnEquityBps,
            reasonCodes = reasons,
            latestFcfDollars = latestFcf,
            fcfRunRateDollars = drivers.normalizedFcffDollars.roundToLong(),
            fcfRunRateNormalized = true,
            provisionalWaccUpliftBps = resolvedWacc.provisionalWaccUpliftBps,
            debtWeightBps = resolvedWacc.debtWeightBps,
            pointEstimateUnreliable = ratesUnreliable,
            scenarioStress = "growth_margin_and_discount_rate",
            waccBearBps = bearWacc,
            waccBullBps = bullWacc,
            valuationDriver = "driver_based_fcff",
            latestRevenueDollars = drivers.latestRevenueDollars.roundToLong(),
            normalizedFcffDollars = drivers.normalizedFcffDollars.roundToLong(),
            normalizedOcfMarginBps = drivers.normalizedOcfMarginBps,
            normalizedCapexIntensityBps = drivers.normalizedCapexIntensityBps,
            normalizedAfterTaxInterestMarginBps = drivers.normalizedAfterTaxInterestMarginBps,
            capexSpikeYears = drivers.capexSpikeYears,
            driverRegime = drivers.driverRegime,
            growthDispersionBps = drivers.growthDispersionBps,
            growthDriver = "revenue_growth_median:${drivers.driverRegime}",
            driverInputFingerprint = driverInputFingerprint(timeseries),
            driverProvenance = listOf(
                "source=provider_timeseries",
                "annual_aligned=ocf,capex,revenue,interest,debt,effective_tax,marginal_tax",
                "fcff=ocf_plus_after_tax_interest_minus_capex",
            ),
        )
    }

    private fun recentPositiveFcfWindow(timeseries: FundamentalTimeseries): List<AnnualReportedValue> {
        val suffix = mutableListOf<AnnualReportedValue>()
        var expectedYear: Int? = null
        for (point in timeseries.freeCashFlow.asReversed()) {
            val year = parseYmd(point.asOfDate)?.year ?: break
            if (point.value <= 0.0 || (expectedYear != null && year != expectedYear)) break
            suffix += point
            if (suffix.size == GROWTH_RECENT_WINDOW) break
            expectedYear = year - 1
        }
        return suffix.asReversed()
    }

    /**
     * FCFF run-rate from the recent contiguous positive window (Windows parity).
     * Default: equal-weight window average. When latest > 125% of mean (recovery
     * step-up), blend 50/50 latest and average.
     */
    private fun fcfRunRateDollars(timeseries: FundamentalTimeseries): Pair<Double, Boolean>? {
        val window = recentPositiveFcfWindow(timeseries)
        if (window.isEmpty()) return null
        if (window.size == 1) return window.first().value to false
        val avg = window.map { it.value }.average()
        if (!avg.isFinite() || avg <= 0.0) return null
        val latest = window.last().value
        val run = if (latest > avg * 1.25) {
            0.5 * latest + 0.5 * avg
        } else {
            avg
        }
        if (!run.isFinite() || run <= 0.0) return null
        return run to true
    }

    private fun recentFcfGrowthBps(timeseries: FundamentalTimeseries): Int? {
        val window = recentPositiveFcfWindow(timeseries)
        if (window.size < 2) return null
        val first = window.first()
        val last = window.last()
        // Windows parity: integer fiscal-year span, not calendar-day fraction.
        val firstYear = parseYmd(first.asOfDate)?.year ?: return null
        val lastYear = parseYmd(last.asOfDate)?.year ?: return null
        val years = (lastYear - firstYear).coerceAtLeast(1).toDouble()
        if (first.value <= 0.0) return null
        val cagr = (last.value / first.value).pow(1.0 / years) - 1.0
        return if (cagr.isFinite()) (cagr * 10_000.0).roundToInt() else null
    }

    private fun discountedFcffFade(
        latestFcfDollars: Double,
        currentShares: Double,
        netDebtDollars: Long,
        gNearBps: Int,
        gStableBps: Int,
        waccBps: Int,
    ): Long? {
        if (latestFcfDollars <= 0.0 || currentShares <= 0.0 || gStableBps >= waccBps) return null
        val wacc = waccBps / 10_000.0
        val gNear = gNearBps / 10_000.0
        val gStable = gStableBps / 10_000.0
        var projected = latestFcfDollars
        var presentValue = 0.0
        for (year in 1..PROJECTION_YEARS) {
            val w = year.toDouble() / PROJECTION_YEARS
            val g = gNear * (1.0 - w) + gStable * w
            projected *= 1.0 + g
            presentValue += projected / (1.0 + wacc).pow(year)
        }
        val terminalCashFlow = projected * (1.0 + gStable)
        val terminalValue = terminalCashFlow / (wacc - gStable)
        val enterpriseValue = presentValue + terminalValue / (1.0 + wacc).pow(PROJECTION_YEARS)
        val equityValue = enterpriseValue - netDebtDollars
        if (!equityValue.isFinite()) return null
        return ((equityValue.coerceAtLeast(0.0) / currentShares) * 100.0).roundToLong()
    }

    private fun latestShareCount(
        fundamentals: FundamentalSnapshot,
        timeseries: FundamentalTimeseries,
    ): Double? = timeseries.dilutedAverageShares.lastOrNull()?.value?.takeIf { it > 0.0 }
        ?: fundamentals.sharesOutstanding?.toDouble()

    private fun elapsedYearsBetween(start: String, end: String): Double? {
        val startDate = parseYmd(start) ?: return null
        val endDate = parseYmd(end) ?: return null
        val elapsedDays = endDate.toEpochDay() - startDate.toEpochDay()
        return if (elapsedDays > 0) elapsedDays / 365.2425 else null
    }

    private fun parseYmd(value: String): java.time.LocalDate? =
        runCatching { java.time.LocalDate.parse(value) }.getOrNull()

    private fun industryBetaMillis(fundamentals: FundamentalSnapshot): Int {
        val blob = listOfNotNull(
            fundamentals.sectorName,
            fundamentals.industryName,
            fundamentals.sectorKey,
        ).joinToString(" ").lowercase()
        return when {
            blob.contains("utilit") -> 600
            blob.contains("consumer staples") || blob.contains("consumer defensive") -> 700
            blob.contains("health") || blob.contains("pharma") -> 900
            blob.contains("technolog") || blob.contains("software") || blob.contains("semiconductor") -> 1_200
            blob.contains("energy") -> 1_100
            blob.contains("financial") || blob.contains("insurance") || blob.contains("bank") -> 900
            blob.contains("real estate") || blob.contains("reit") -> 850
            else -> DEFAULT_INDUSTRY_BETA_MILLIS
        }
    }

    private fun costOfEquityBps(
        fundamentals: FundamentalSnapshot,
        marketParams: MarketParams,
    ): Triple<Int, WaccFieldSource, Boolean> {
        val industry = industryBetaMillis(fundamentals) / 1_000.0
        val (raw, source, provisional) = when (val b = fundamentals.betaMillis) {
            null -> Triple(industry, WaccFieldSource.IndustryShrink, false)
            else -> if (b > 0) {
                val company = b / 1_000.0
                val shrunk = BETA_COMPANY_WEIGHT * company + BETA_INDUSTRY_WEIGHT * industry
                Triple(shrunk, WaccFieldSource.IndustryShrink, false)
            } else {
                Triple(industry, WaccFieldSource.IndustryShrink, false)
            }
        }
        val re = marketParams.rfBps + (raw * marketParams.erpBps).roundToInt()
        return Triple(
            re.coerceAtLeast(marketParams.rfBps + 50),
            source,
            provisional || marketParams.provisional,
        )
    }

    private fun resolveMarketCapDollars(
        fundamentals: FundamentalSnapshot,
        timeseries: FundamentalTimeseries,
        marketPriceCents: Long?,
    ): Pair<Double, WaccFieldSource>? {
        fundamentals.marketCapDollars?.takeIf { it > 0L }?.let { reported ->
            return reported.toDouble() to WaccFieldSource.Reported
        }
        val shares = latestShareCount(fundamentals, timeseries) ?: return null
        val priceCents = marketPriceCents?.takeIf { it > 0L } ?: return null
        val derived = (priceCents / 100.0) * shares
        if (!derived.isFinite() || derived <= 0.0) return null
        return derived to WaccFieldSource.DerivedPriceTimesShares
    }

    /** Windows `derive_wacc` parity: explicit evidence only; no CoD/tax defaults. */
    private fun deriveWacc(
        fundamentals: FundamentalSnapshot,
        timeseries: FundamentalTimeseries,
        marketPriceCents: Long?,
        marketParams: MarketParams,
    ): ResolvedWacc {
        val resolvedRates = resolveRateInputs(
            timeseries = timeseries,
            reportedTotalDebtDollars = fundamentals.totalDebtDollars,
            _riskFreeBps = marketParams.rfBps,
        ).getOrElse { error(it.message ?: "fcff rate inputs unavailable") }
        val (marketCap, marketCapSource) = resolveMarketCapDollars(fundamentals, timeseries, marketPriceCents)
            ?: error("market cap is missing")
        val (costOfEquityBps, betaSource, betaProv) = costOfEquityBps(fundamentals, marketParams)

        val totalDebtSource =
            if (fundamentals.totalDebtDollars != null) WaccFieldSource.Reported else WaccFieldSource.Unavailable
        val totalCashSource =
            if (fundamentals.totalCashDollars != null) WaccFieldSource.Reported else WaccFieldSource.AssumedZero
        val totalDebt = fundamentals.totalDebtDollars
            ?.takeIf { it >= 0L }
            ?.toDouble()
            ?: error("fcff unavailable: total debt is missing")
        val totalCash = (fundamentals.totalCashDollars ?: 0L).coerceAtLeast(0).toDouble()
        val netDebt = (totalDebt - totalCash).coerceAtLeast(0.0)
        val debtWeightBase = marketCap + netDebt
        var equityWeight = if (debtWeightBase > 0.0) marketCap / debtWeightBase else 1.0
        var debtWeight = if (debtWeightBase > 0.0) netDebt / debtWeightBase else 0.0

        val costOfDebtSource = resolvedRates?.costOfDebtSource ?: WaccFieldSource.NotApplicable
        val costOfDebtBps = resolvedRates?.costOfDebtBps ?: 0
        val taxRateSource = resolvedRates?.marginalTaxSource ?: WaccFieldSource.NotApplicable
        val taxRateBps = resolvedRates?.marginalTaxBps ?: 0
        val afterTaxCostOfDebtBps = (costOfDebtBps * (1.0 - taxRateBps / 10_000.0)).roundToInt()
        val softWaccBps =
            ((equityWeight * costOfEquityBps) + (debtWeight * afterTaxCostOfDebtBps)).roundToInt()
        val provisionalUplift = 0
        val waccBps = softWaccBps

        return ResolvedWacc(
            waccBps = waccBps,
            provisionalWaccUpliftBps = provisionalUplift,
            debtWeightBps = (debtWeight * 10_000.0).roundToInt(),
            inputs = WaccInputProvenance(
                marketCap = marketCapSource,
                beta = betaSource,
                totalDebt = totalDebtSource,
                totalCash = totalCashSource,
                costOfDebt = costOfDebtSource,
                taxRate = taxRateSource,
                waccClamped = betaProv || marketParams.provisional ||
                    resolvedRates?.quality == DriverEvidenceQuality.Provisional,
            ),
            rateReasons = resolvedRates?.reasons.orEmpty().let { reasons ->
                if (reasons.isEmpty()) {
                    listOf(
                        "cost_of_debt=not_applicable_explicit_zero_debt",
                        "marginal_tax=not_applicable_no_debt_tax_shield",
                    )
                } else {
                    reasons
                }
            },
        )
    }
}
