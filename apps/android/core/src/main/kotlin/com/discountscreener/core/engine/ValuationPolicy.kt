package com.discountscreener.core.engine

import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

const val VALUATION_POLICY_VERSION = "valuation-policy/1"
const val VALUATION_POLICY_RESOURCE = "/valuation-policy.yaml"

/**
 * Versioned policy book. Numbers live in [shared/contracts/valuation-policy.yaml].
 * Kotlin objects read this book. They do not keep a second copy.
 */
object ValuationPolicy {
    const val VERSION = VALUATION_POLICY_VERSION

    @Volatile
    var current: ValuationPolicyBook = ValuationPolicyBook.loadDefault()
        private set

    fun <T> use(book: ValuationPolicyBook, block: () -> T): T {
        var previous = current
        current = book
        try {
            return block()
        } finally {
            current = previous
        }
    }
}

data class DatedRateRow(
    val asOfDate: String,
    val valueBps: Int,
    val source: String,
)

data class IndustryPathEntry(
    val id: String,
    val industryContains: List<String>,
    val sectorContains: List<String>,
    val targetFcffMarginBps: Int,
)

data class CoverageBucket(
    val minCoverage: Double,
    val spreadBps: Int,
)

data class MarketSection(
    val defaultRfBps: Int,
    val defaultErpBps: Int,
    val stableGrowthRfBufferBps: Int,
    val bootstrapMacroStableGrowthBps: Int,
    val minStableGrowthBps: Int,
    val rfMinYieldBps: Int,
    val rfMaxYieldBps: Int,
)

data class MacroSection(
    val rows: List<DatedRateRow>,
)

data class ErpSection(
    val freshDays: Long,
    val impliedIndex: List<DatedRateRow>,
    val kroll: List<DatedRateRow>,
)

data class DomicileTaxRow(
    val countries: List<String>,
    val statutoryBps: Int,
    val source: String,
)

data class TaxSection(
    val version: String,
    val rows: List<DomicileTaxRow>,
)

data class DcfSection(
    val betaCompanyWeightPct: Long,
    val betaIndustryWeightPct: Long,
    val defaultIndustryBetaMillis: Int,
    val projectionYears: Int,
    val projectionYearsSecular: Int,
    val driverRecentWindow: Int,
    val growthRecentWindow: Int,
    val coeScenarioBandBps: Int,
    val waccScenarioBandBps: Int,
    val waccScenarioBearBandUnreliableBps: Int,
    val waccScenarioBullBandUnreliableBps: Int,
    val provisionalWaccBaseUpliftBps: Int,
    val provisionalUpliftFullDebtWeight: Double,
    val roeBearHaircutBps: Int,
    val roeBullBoostBps: Int,
    val roeBullCapBps: Int,
    val gordonRateEpsilonBps: Int,
    val minEquitySpreadOverRfBps: Int,
    val capexSpikeRatio: Double,
    val capexSpikeMinAbsBps: Int,
    val secularGrowthFadeExponent: Double,
    val scenarioGrowthBandBps: Int,
    val maxNearGrowthDistanceFromStableBps: Int,
    val maxSecularNearGrowthBps: Int,
    val investmentWaveMinGrowthBps: Int,
    val shareCountMinCurrentOverWasBps: Int,
    val shareCountMaxCurrentOverWasBps: Int,
)

data class FcffPathSection(
    val reinvestmentHoldYears: Int,
    val reinvestmentMarginBps: Int,
    val reinvestmentErpWeightPct: Int,
    val reinvestmentRetentionMinBps: Int,
    val reinvestmentMinCapexBps: Int,
    val maxSecularGrowthBps: Int,
    val qualityRoeBps: Int,
    val qualityGrowthMaxBps: Int,
    val qualityWaccGapMinBps: Int,
    val qualityRfPremiumBps: Int,
    val marginFadeBufferBps: Int,
    val secularHoldExcessStepBps: Int,
    val secularHoldMaxYears: Int,
    val fadedMarginHoldCapYears: Int,
    val internetRetailHoldMinYears: Int,
    val discountStoreHoldMinYears: Int,
    val internetContentExtremeStableBps: Int,
    val weakFranchiseExcessRoeBps: Int,
    val persistFracBaseBps: Int,
    val persistFracMinBps: Int,
    val persistFracMaxBps: Int,
    val industryFadeOvershootRatioBps: Int,
    val internetContentExtremeStartBps: Int,
    val pharmaLowRoeBps: Int,
    val pharmaLowRoeMarginCapBps: Int,
    val commodityFloorGrowthBps: Int,
    val cyclicalFadeYears: Int,
    val secularFadeExponentFloor: Double,
    val highTurnoverMarginMaxBps: Int,
    val highTurnoverRoeMinBps: Int,
    val highRoeShrinkGrowthMaxBps: Int,
    val highRoeShrinkBps: Int,
    val highRoeShrinkRfFloorBps: Int,
    val defaultPathErpBps: Int,
    val defaultFadeYears: Int,
    val defaultFadeExponent: Double,
)

data class ResidualPathSection(
    val bankThroughCycleRoeBps: Int,
    val bankThroughCycleMaxLiftBps: Int,
    val bankSpreadBps: Int,
    val bankHighRoeBps: Int,
    val bankHighRoeDiscountAdjustBps: Int,
    val pcSpreadBps: Int,
    val careSpreadBps: Int,
    val careModestRoeMaxBps: Int,
    val careModestSpreadBps: Int,
    val careFadeYears: Int,
    val defaultFadeYears: Int,
)

data class IndustryOperatingPathSection(
    val version: String,
    val entries: List<IndustryPathEntry>,
)

data class SustainingCapexSection(
    val assetRenewalRateBps: Int,
    val minRevenueBps: Int,
)

data class ComponentSection(
    val materialRevenueBps: Int,
    val financialTokens: Set<String>,
    val instrumentTokens: Set<String>,
    val operatingTokens: Set<String>,
    val sliceTokens: Set<String>,
    val structuralLocal: Set<String>,
    val financeArmTokens: Set<String>,
    val legalTokens: Set<String>,
)

data class IssuerYieldSection(
    val minRemainingYears: Double,
    val maxRemainingYears: Double,
    val minYieldBps: Int,
    val maxYieldBps: Int,
)

data class ResidualIncomeSection(
    val franchisePersistSpreadBps: Int,
    val gordonEpsilonBps: Int,
    val recentRoeYears: Int,
)

data class StreetImpliedSection(
    val alignedApeBps: Int,
    val discountModestBps: Int,
    val discountStretchBps: Int,
    val marginModestBps: Int,
    val marginStretchBps: Int,
    val growthModestBps: Int,
    val growthStretchBps: Int,
    val roeModestBps: Int,
    val roeStretchBps: Int,
)

data class CoverageCreditSection(
    val defaultSpreadBps: Int,
    val buckets: List<CoverageBucket>,
)

data class ValuationDecisionSection(
    val alignedMaxBps: Int,
    val tensionMaxBps: Int,
    val wideScenarioBps: Int,
)

data class JudgmentSection(
    val identityUsableMaxWidthBps: Int,
)

data class OperatingSection(
    val disputedDifferenceBps: Long,
    val hardMaxProjectionYears: Int,
    val minTerminalRoicSpreadBps: Int,
)

data class SourceContinuitySection(
    val defaultScaleRatioThreshold: Long,
    val defaultMaterialityFloorDollars: Long,
    val defaultMinConfidentSeriesLength: Int,
)

data class SecNormalizationSection(
    val minimumDurationDays: Int,
    val maximumDurationDays: Int,
    val materialAcquisitionRevenueBps: Int,
)

data class EstimatesHistorySection(
    val minBaseMoveBps: Int,
    val minCoverageGain: Int,
    val maxDailyPoints: Int,
)

data class DcfSourceSection(
    val minAnnualFcfPoints: Int,
)

data class QuantLensSection(
    val minHorizonWindows: Int,
    val rowUpsideMinBps: Int,
    val rowUpsideMaxBps: Int,
)

data class SectorBenchmarksSection(
    val minSectorMembers: Int,
)

data class ScoreSection(
    val strong: Int,
    val good: Int,
    val highConfidenceAnalystCount: Int,
)

data class RobustCentreSection(
    val madToDeviation: Double,
    val maxAbsoluteZ: Double,
    val minObservations: Int,
)

data class DipSection(
    val dipAtrMin: Double,
    val atrPeriod: Int,
    val dipRange: Int,
    val macdN: Int,
    val imminentHistAtr: Double,
    val rsiNowLow: Double,
    val rsiNowHigh: Double,
    val rsiHot: Double,
    val streetNowBps: Int,
    val streetAlmostBps: Int,
    val fFloor: Int,
    val nowCap: Int,
    val laterCap: Int,
)

data class LeftoverSection(
    val streetDoorBps: Int,
    val stretchPrimaryMax: Double,
    val stretchOut: Double,
    val rsiHot: Double,
    val nowCap: Int,
    val laterCap: Int,
)

data class CrossSection(
    val flippedBarsMax: Int,
    val streetNowBps: Int,
    val streetAlmostBps: Int,
    val fFloor: Int,
    val rsiHot: Double,
    val nowCap: Int,
    val laterCap: Int,
)

data class MacdHorizonSection(
    val align: Int,
    val flat: Int,
    val drag: Int,
)

data class PricePathSection(
    val clusterAtrFrac: Double,
    val minZoneAtrFrac: Double,
    val maxZoneAtrFrac: Double,
    val atrBandMult: Double,
    val nearZoneAtr: Double,
    val inZoneEpsAtr: Double,
    val maxMotives: Int,
    val sessionsCap: Int,
    val bootstrapPaths: Int,
    val bootstrapMinReturns: Int,
)

data class OpportunityV2Section(
    val fundFcfYieldLower: Double,
    val fundFcfYieldUpper: Double,
    val fundFcfWeight: Double,
    val fundOcfFallbackWeight: Double,
    val fundRoeLowerBps: Double,
    val fundRoeUpperBps: Double,
    val fundRoeWeight: Double,
    val fundGrowthLowerBps: Double,
    val fundGrowthUpperBps: Double,
    val fundGrowthWeight: Double,
    val fundBalanceDeLow: Double,
    val fundBalanceDeHigh: Double,
    val fundBalanceWeight: Double,
    val fundPeLow: Double,
    val fundPeHigh: Double,
    val fundPeWeight: Double,
    val techTrendDeltaBound: Double,
    val techTrend2050Weight: Double,
    val techTrend50200Weight: Double,
    val techTrendPrice20Weight: Double,
    val techHistogramBound: Double,
    val techHistogramWeight: Double,
    val techMacdDirectionWeight: Double,
    val forecastUpsideLowerBps: Double,
    val forecastUpsideUpperBps: Double,
    val forecastValuationWeight: Double,
    val forecastRecLowHundredths: Double,
    val forecastRecHighHundredths: Double,
    val forecastRecWeight: Double,
    val forecastMinAnalystOpinions: Int,
    val forecastFullAnalystOpinions: Double,
    val forecastBreadthWeight: Double,
    val forecastUncertaintyBound: Double,
    val forecastUncertaintyWeight: Double,
    val forecastFreshnessWeight: Double,
    val forecastFreshnessHalfLifeSeconds: Double,
    val forecastDcfReliability: Double,
    val forecastMinReliableEvidenceWeight: Double,
    val fundamentalsFullWeight: Double,
    val technicalsFullWeight: Double,
    val compositeCoverageBonus: Int,
    val compositeBound: Int,
)

data class OpportunityV3Section(
    val fundFcfYieldLower: Double,
    val fundFcfYieldUpper: Double,
    val fundFcfWeight: Double,
    val fundOcfFallbackWeight: Double,
    val fundRoeLowerBps: Double,
    val fundRoeUpperBps: Double,
    val fundRoeWeight: Double,
    val fundGrowthLowerBps: Double,
    val fundGrowthUpperBps: Double,
    val fundGrowthWeight: Double,
    val fundBalanceDeLow: Double,
    val fundBalanceDeHigh: Double,
    val fundBalanceWeight: Double,
    val fundValuationWeight: Double,
    val fundPeLow: Double,
    val fundPeHigh: Double,
    val fundEvEbitdaLow: Double,
    val fundEvEbitdaHigh: Double,
    val fundPbLow: Double,
    val fundPbHigh: Double,
    val fundCashQualityWeight: Double,
    val techTrendDeltaBound: Double,
    val techTrendPrice20Weight: Double,
    val techTrend2050Weight: Double,
    val techTrend50200Weight: Double,
    val techHistogramBound: Double,
    val techHistogramWeight: Double,
    val techMacdDirectionWeight: Double,
    val techRsiWeight: Double,
    val techVolumeWeight: Double,
    val techRsiSlopeBound: Double,
    val techVolumeRatioLow: Double,
    val techVolumeRatioHigh: Double,
    val forecastUpsideLowerBps: Double,
    val forecastUpsideUpperBps: Double,
    val forecastValuationWeight: Double,
    val forecastRecLowHundredths: Double,
    val forecastRecHighHundredths: Double,
    val forecastRecWeight: Double,
    val forecastSkewWeight: Double,
    val forecastMinAnalystOpinions: Int,
    val forecastFullAnalystOpinions: Double,
    val forecastBreadthWeight: Double,
    val forecastAnalystUncertaintyWeight: Double,
    val forecastDcfUncertaintyWeight: Double,
    val forecastUncertaintyBound: Double,
    val forecastDcfWidthLower: Double,
    val forecastDcfWidthUpper: Double,
    val forecastFreshnessWeight: Double,
    val forecastFreshnessHalfLifeSeconds: Double,
    val forecastDcfReliability: Double,
    val forecastMinReliableEvidenceWeight: Double,
    val fundamentalsFullWeight: Double,
    val technicalsFullWeight: Double,
    val compositeCoverageBonus: Int,
    val compositeBound: Int,
    val betaHaircutMax: Double,
    val betaHaircutMultMax: Double,
    val betaLowMillis: Double,
    val betaHighMillis: Double,
)

data class OpportunityV4Section(
    val fundSectorCheapMult: Double,
    val fundSectorRichMult: Double,
    val fundSectorRoeLowerOffsetBps: Double,
    val fundSectorRoeUpperOffsetBps: Double,
    val fundShareCountWeight: Double,
    val fundTrendWeight: Double,
    val fundPulseWeight: Double,
    val pulseMinAbsEpsCents: Long,
    val trendMaxYears: Int,
    val trendMinTransitions: Int,
    val fundShareCountShrinkBps: Double,
    val fundShareCountDiluteBps: Double,
    val compositeAgreementBonus: Int,
    val compositeBound: Int,
    val spreadFull: Double,
)

data class OpportunitySection(
    val rankingIncludesQuantEngine: Boolean,
    val dcfOpportunityThresholdBps: Int,
    val dcfExpensiveThresholdBps: Int,
    val basisPointsPerUnit: Double,
    val sectorAdjustedMarker: String,
    val valuationPanelMultipleCount: Double,
    val legacyAvoidBelowScore: Int,
    val legacyActAtOrAboveScore: Int,
    val continuousAvoidBelowScore: Int,
    val continuousActAtOrAboveScore: Int,
    val v2: OpportunityV2Section,
    val v3: OpportunityV3Section,
    val v4: OpportunityV4Section,
)

data class ValuationPolicyBook(
    val version: String,
    val market: MarketSection,
    val macro: MacroSection,
    val erp: ErpSection,
    val tax: TaxSection,
    val dcf: DcfSection,
    val fcffPath: FcffPathSection,
    val residualPath: ResidualPathSection,
    val industryOperatingPath: IndustryOperatingPathSection,
    val sustainingCapex: SustainingCapexSection,
    val component: ComponentSection,
    val issuerYield: IssuerYieldSection,
    val residualIncome: ResidualIncomeSection,
    val streetImplied: StreetImpliedSection,
    val coverageCredit: CoverageCreditSection,
    val valuationDecision: ValuationDecisionSection,
    val judgment: JudgmentSection,
    val operating: OperatingSection,
    val sourceContinuity: SourceContinuitySection,
    val secNormalization: SecNormalizationSection,
    val estimatesHistory: EstimatesHistorySection,
    val dcfSource: DcfSourceSection,
    val quantLens: QuantLensSection,
    val sectorBenchmarks: SectorBenchmarksSection,
    val score: ScoreSection,
    val robustCentre: RobustCentreSection,
    val dip: DipSection,
    val leftover: LeftoverSection,
    val cross: CrossSection,
    val macdHorizon: MacdHorizonSection,
    val pricePath: PricePathSection,
    val opportunity: OpportunitySection,
) {
    fun withAutoTarget(bps: Int): ValuationPolicyBook {
        var entries = industryOperatingPath.entries.map { entry ->
            if (entry.id == "auto") entry.copy(targetFcffMarginBps = bps) else entry
        }
        return copy(industryOperatingPath = industryOperatingPath.copy(entries = entries))
    }

    fun withSustainingRenewal(bps: Int): ValuationPolicyBook =
        copy(sustainingCapex = sustainingCapex.copy(assetRenewalRateBps = bps))

    fun withCoverageDefault(bps: Int): ValuationPolicyBook =
        copy(coverageCredit = coverageCredit.copy(defaultSpreadBps = bps))

    fun withCrossFlippedBars(max: Int): ValuationPolicyBook =
        copy(cross = cross.copy(flippedBarsMax = max))

    companion object {
        fun loadDefault(): ValuationPolicyBook {
            var stream = ValuationPolicyBook::class.java.getResourceAsStream(VALUATION_POLICY_RESOURCE)
                ?: error("valuation-policy.yaml is missing from the classpath")
            return stream.bufferedReader().use { parse(it.readText()) }
        }

        fun parse(text: String): ValuationPolicyBook {
            var yaml = Yaml(SafeConstructor(LoaderOptions()))
            @Suppress("UNCHECKED_CAST")
            var raw = yaml.load<Map<String, Any>>(text)
                ?: error("valuation-policy.yaml is empty")
            return from(YamlMap(raw, "policy"))
        }

        private fun from(root: YamlMap): ValuationPolicyBook {
            var version = root.string("version")
            if (version != VALUATION_POLICY_VERSION) {
                error("valuation-policy version $version is not $VALUATION_POLICY_VERSION")
            }
            var market = root.child("market")
            var erp = root.child("erp")
            var tax = root.child("tax")
            var dcf = root.child("dcf")
            var fcff = root.child("fcff_path")
            var residual = root.child("residual_path")
            var industry = root.child("industry_operating_path")
            var sustaining = root.child("sustaining_capex")
            var component = root.child("component")
            var issuerYieldMap = root.child("issuer_yield")
            var residualIncome = root.child("residual_income")
            var street = root.child("street_implied")
            var coverage = root.child("coverage_credit")
            var decision = root.child("valuation_decision")
            var judgment = root.child("judgment")
            var operating = root.child("operating")
            var continuity = root.child("source_continuity")
            var sec = root.child("sec_normalization")
            var estimates = root.child("estimates_history")
            var dcfSource = root.child("dcf_source")
            var quant = root.child("quant_lens")
            var sector = root.child("sector_benchmarks")
            var score = root.child("score")
            var robust = root.child("robust_centre")
            var dip = root.child("dip")
            var leftover = root.child("leftover")
            var cross = root.child("cross")
            var macd = root.child("macd_horizon")
            var pricePath = root.child("price_path")
            var opportunity = root.child("opportunity")
            var v2 = opportunity.child("v2")
            var v3 = opportunity.child("v3")
            var v4 = opportunity.child("v4")
            return ValuationPolicyBook(
                version = version,
                market = MarketSection(
                    defaultRfBps = market.int("default_rf_bps"),
                    defaultErpBps = market.int("default_erp_bps"),
                    stableGrowthRfBufferBps = market.int("stable_growth_rf_buffer_bps"),
                    bootstrapMacroStableGrowthBps = market.int("bootstrap_macro_stable_growth_bps"),
                    minStableGrowthBps = market.int("min_stable_growth_bps"),
                    rfMinYieldBps = market.int("rf_min_yield_bps"),
                    rfMaxYieldBps = market.int("rf_max_yield_bps"),
                ),
                macro = MacroSection(
                    rows = root.child("macro").mapList("rows").map { row ->
                        DatedRateRow(
                            asOfDate = row.string("as_of_date"),
                            valueBps = row.int("nominal_growth_ceiling_bps"),
                            source = row.string("source"),
                        )
                    },
                ),
                erp = ErpSection(
                    freshDays = erp.long("fresh_days"),
                    impliedIndex = erp.mapList("implied_index").map { datedErp(it) },
                    kroll = erp.mapList("kroll").map { datedErp(it) },
                ),
                tax = TaxSection(
                    version = tax.string("version"),
                    rows = tax.mapList("rows").map { row ->
                        DomicileTaxRow(
                            countries = row.stringList("countries"),
                            statutoryBps = row.int("statutory_bps"),
                            source = row.string("source"),
                        )
                    },
                ),
                dcf = DcfSection(
                    betaCompanyWeightPct = dcf.long("beta_company_weight_pct"),
                    betaIndustryWeightPct = dcf.long("beta_industry_weight_pct"),
                    defaultIndustryBetaMillis = dcf.int("default_industry_beta_millis"),
                    projectionYears = dcf.int("projection_years"),
                    projectionYearsSecular = dcf.int("projection_years_secular"),
                    driverRecentWindow = dcf.int("driver_recent_window"),
                    growthRecentWindow = dcf.int("growth_recent_window"),
                    coeScenarioBandBps = dcf.int("coe_scenario_band_bps"),
                    waccScenarioBandBps = dcf.int("wacc_scenario_band_bps"),
                    waccScenarioBearBandUnreliableBps = dcf.int("wacc_scenario_bear_band_unreliable_bps"),
                    waccScenarioBullBandUnreliableBps = dcf.int("wacc_scenario_bull_band_unreliable_bps"),
                    provisionalWaccBaseUpliftBps = dcf.int("provisional_wacc_base_uplift_bps"),
                    provisionalUpliftFullDebtWeight = dcf.double("provisional_uplift_full_debt_weight"),
                    roeBearHaircutBps = dcf.int("roe_bear_haircut_bps"),
                    roeBullBoostBps = dcf.int("roe_bull_boost_bps"),
                    roeBullCapBps = dcf.int("roe_bull_cap_bps"),
                    gordonRateEpsilonBps = dcf.int("gordon_rate_epsilon_bps"),
                    minEquitySpreadOverRfBps = dcf.int("min_equity_spread_over_rf_bps"),
                    capexSpikeRatio = dcf.double("capex_spike_ratio"),
                    capexSpikeMinAbsBps = dcf.int("capex_spike_min_abs_bps"),
                    secularGrowthFadeExponent = dcf.double("secular_growth_fade_exponent"),
                    scenarioGrowthBandBps = dcf.int("scenario_growth_band_bps"),
                    maxNearGrowthDistanceFromStableBps = dcf.int("max_near_growth_distance_from_stable_bps"),
                    maxSecularNearGrowthBps = dcf.int("max_secular_near_growth_bps"),
                    investmentWaveMinGrowthBps = dcf.int("investment_wave_min_growth_bps"),
                    shareCountMinCurrentOverWasBps = dcf.int("share_count_min_current_over_was_bps"),
                    shareCountMaxCurrentOverWasBps = dcf.int("share_count_max_current_over_was_bps"),
                ),
                fcffPath = FcffPathSection(
                    reinvestmentHoldYears = fcff.int("reinvestment_hold_years"),
                    reinvestmentMarginBps = fcff.int("reinvestment_margin_bps"),
                    reinvestmentErpWeightPct = fcff.int("reinvestment_erp_weight_pct"),
                    reinvestmentRetentionMinBps = fcff.int("reinvestment_retention_min_bps"),
                    reinvestmentMinCapexBps = fcff.int("reinvestment_min_capex_bps"),
                    maxSecularGrowthBps = fcff.int("max_secular_growth_bps"),
                    qualityRoeBps = fcff.int("quality_roe_bps"),
                    qualityGrowthMaxBps = fcff.int("quality_growth_max_bps"),
                    qualityWaccGapMinBps = fcff.int("quality_wacc_gap_min_bps"),
                    qualityRfPremiumBps = fcff.int("quality_rf_premium_bps"),
                    marginFadeBufferBps = fcff.int("margin_fade_buffer_bps"),
                    secularHoldExcessStepBps = fcff.int("secular_hold_excess_step_bps"),
                    secularHoldMaxYears = fcff.int("secular_hold_max_years"),
                    fadedMarginHoldCapYears = fcff.int("faded_margin_hold_cap_years"),
                    internetRetailHoldMinYears = fcff.int("internet_retail_hold_min_years"),
                    discountStoreHoldMinYears = fcff.int("discount_store_hold_min_years"),
                    internetContentExtremeStableBps = fcff.int("internet_content_extreme_stable_bps"),
                    weakFranchiseExcessRoeBps = fcff.int("weak_franchise_excess_roe_bps"),
                    persistFracBaseBps = fcff.int("persist_frac_base_bps"),
                    persistFracMinBps = fcff.int("persist_frac_min_bps"),
                    persistFracMaxBps = fcff.int("persist_frac_max_bps"),
                    industryFadeOvershootRatioBps = fcff.int("industry_fade_overshoot_ratio_bps"),
                    internetContentExtremeStartBps = fcff.int("internet_content_extreme_start_bps"),
                    pharmaLowRoeBps = fcff.int("pharma_low_roe_bps"),
                    pharmaLowRoeMarginCapBps = fcff.int("pharma_low_roe_margin_cap_bps"),
                    commodityFloorGrowthBps = fcff.int("commodity_floor_growth_bps"),
                    cyclicalFadeYears = fcff.int("cyclical_fade_years"),
                    secularFadeExponentFloor = fcff.double("secular_fade_exponent_floor"),
                    highTurnoverMarginMaxBps = fcff.int("high_turnover_margin_max_bps"),
                    highTurnoverRoeMinBps = fcff.int("high_turnover_roe_min_bps"),
                    highRoeShrinkGrowthMaxBps = fcff.int("high_roe_shrink_growth_max_bps"),
                    highRoeShrinkBps = fcff.int("high_roe_shrink_bps"),
                    highRoeShrinkRfFloorBps = fcff.int("high_roe_shrink_rf_floor_bps"),
                    defaultPathErpBps = fcff.int("default_path_erp_bps"),
                    defaultFadeYears = fcff.int("default_fade_years"),
                    defaultFadeExponent = fcff.double("default_fade_exponent"),
                ),
                residualPath = ResidualPathSection(
                    bankThroughCycleRoeBps = residual.int("bank_through_cycle_roe_bps"),
                    bankThroughCycleMaxLiftBps = residual.int("bank_through_cycle_max_lift_bps"),
                    bankSpreadBps = residual.int("bank_spread_bps"),
                    bankHighRoeBps = residual.int("bank_high_roe_bps"),
                    bankHighRoeDiscountAdjustBps = residual.int("bank_high_roe_discount_adjust_bps"),
                    pcSpreadBps = residual.int("pc_spread_bps"),
                    careSpreadBps = residual.int("care_spread_bps"),
                    careModestRoeMaxBps = residual.int("care_modest_roe_max_bps"),
                    careModestSpreadBps = residual.int("care_modest_spread_bps"),
                    careFadeYears = residual.int("care_fade_years"),
                    defaultFadeYears = residual.int("default_fade_years"),
                ),
                industryOperatingPath = IndustryOperatingPathSection(
                    version = industry.string("version"),
                    entries = industry.mapList("entries").map { entry ->
                        IndustryPathEntry(
                            id = entry.string("id"),
                            industryContains = entry.stringList("industry_contains"),
                            sectorContains = entry.optionalStringList("sector_contains"),
                            targetFcffMarginBps = entry.int("target_fcff_margin_bps"),
                        )
                    },
                ),
                sustainingCapex = SustainingCapexSection(
                    assetRenewalRateBps = sustaining.int("asset_renewal_rate_bps"),
                    minRevenueBps = sustaining.int("min_revenue_bps"),
                ),
                component = ComponentSection(
                    materialRevenueBps = component.int("material_revenue_bps"),
                    financialTokens = component.stringList("financial_tokens").toSet(),
                    instrumentTokens = component.stringList("instrument_tokens").toSet(),
                    operatingTokens = component.stringList("operating_tokens").toSet(),
                    sliceTokens = component.stringList("slice_tokens").toSet(),
                    structuralLocal = component.stringList("structural_local").toSet(),
                    financeArmTokens = component.stringList("finance_arm_tokens").toSet(),
                    legalTokens = component.stringList("legal_tokens").toSet(),
                ),
                issuerYield = IssuerYieldSection(
                    minRemainingYears = issuerYieldMap.double("min_remaining_years"),
                    maxRemainingYears = issuerYieldMap.double("max_remaining_years"),
                    minYieldBps = issuerYieldMap.int("min_yield_bps"),
                    maxYieldBps = issuerYieldMap.int("max_yield_bps"),
                ),
                residualIncome = ResidualIncomeSection(
                    franchisePersistSpreadBps = residualIncome.int("franchise_persist_spread_bps"),
                    gordonEpsilonBps = residualIncome.int("gordon_epsilon_bps"),
                    recentRoeYears = residualIncome.int("recent_roe_years"),
                ),
                streetImplied = StreetImpliedSection(
                    alignedApeBps = street.int("aligned_ape_bps"),
                    discountModestBps = street.int("discount_modest_bps"),
                    discountStretchBps = street.int("discount_stretch_bps"),
                    marginModestBps = street.int("margin_modest_bps"),
                    marginStretchBps = street.int("margin_stretch_bps"),
                    growthModestBps = street.int("growth_modest_bps"),
                    growthStretchBps = street.int("growth_stretch_bps"),
                    roeModestBps = street.int("roe_modest_bps"),
                    roeStretchBps = street.int("roe_stretch_bps"),
                ),
                coverageCredit = CoverageCreditSection(
                    defaultSpreadBps = coverage.int("default_spread_bps"),
                    buckets = coverage.mapList("buckets").map { bucket ->
                        CoverageBucket(
                            minCoverage = bucket.double("min_coverage"),
                            spreadBps = bucket.int("spread_bps"),
                        )
                    },
                ),
                valuationDecision = ValuationDecisionSection(
                    alignedMaxBps = decision.int("aligned_max_bps"),
                    tensionMaxBps = decision.int("tension_max_bps"),
                    wideScenarioBps = decision.int("wide_scenario_bps"),
                ),
                judgment = JudgmentSection(
                    identityUsableMaxWidthBps = judgment.int("identity_usable_max_width_bps"),
                ),
                operating = OperatingSection(
                    disputedDifferenceBps = operating.long("disputed_difference_bps"),
                    hardMaxProjectionYears = operating.int("hard_max_projection_years"),
                    minTerminalRoicSpreadBps = operating.int("min_terminal_roic_spread_bps"),
                ),
                sourceContinuity = SourceContinuitySection(
                    defaultScaleRatioThreshold = continuity.long("default_scale_ratio_threshold"),
                    defaultMaterialityFloorDollars = continuity.long("default_materiality_floor_dollars"),
                    defaultMinConfidentSeriesLength = continuity.int("default_min_confident_series_length"),
                ),
                secNormalization = SecNormalizationSection(
                    minimumDurationDays = sec.int("minimum_duration_days"),
                    maximumDurationDays = sec.int("maximum_duration_days"),
                    materialAcquisitionRevenueBps = sec.int("material_acquisition_revenue_bps"),
                ),
                estimatesHistory = EstimatesHistorySection(
                    minBaseMoveBps = estimates.int("min_base_move_bps"),
                    minCoverageGain = estimates.int("min_coverage_gain"),
                    maxDailyPoints = estimates.int("max_daily_points"),
                ),
                dcfSource = DcfSourceSection(
                    minAnnualFcfPoints = dcfSource.int("min_annual_fcf_points"),
                ),
                quantLens = QuantLensSection(
                    minHorizonWindows = quant.int("min_horizon_windows"),
                    rowUpsideMinBps = quant.int("row_upside_min_bps"),
                    rowUpsideMaxBps = quant.int("row_upside_max_bps"),
                ),
                sectorBenchmarks = SectorBenchmarksSection(
                    minSectorMembers = sector.int("min_sector_members"),
                ),
                score = ScoreSection(
                    strong = score.int("strong"),
                    good = score.int("good"),
                    highConfidenceAnalystCount = score.int("high_confidence_analyst_count"),
                ),
                robustCentre = RobustCentreSection(
                    madToDeviation = robust.double("mad_to_deviation"),
                    maxAbsoluteZ = robust.double("max_absolute_z"),
                    minObservations = robust.int("min_observations"),
                ),
                dip = DipSection(
                    dipAtrMin = dip.double("dip_atr_min"),
                    atrPeriod = dip.int("atr_period"),
                    dipRange = dip.int("dip_range"),
                    macdN = dip.int("macd_n"),
                    imminentHistAtr = dip.double("imminent_hist_atr"),
                    rsiNowLow = dip.double("rsi_now_low"),
                    rsiNowHigh = dip.double("rsi_now_high"),
                    rsiHot = dip.double("rsi_hot"),
                    streetNowBps = dip.int("street_now_bps"),
                    streetAlmostBps = dip.int("street_almost_bps"),
                    fFloor = dip.int("f_floor"),
                    nowCap = dip.int("now_cap"),
                    laterCap = dip.int("later_cap"),
                ),
                leftover = LeftoverSection(
                    streetDoorBps = leftover.int("street_door_bps"),
                    stretchPrimaryMax = leftover.double("stretch_primary_max"),
                    stretchOut = leftover.double("stretch_out"),
                    rsiHot = leftover.double("rsi_hot"),
                    nowCap = leftover.int("now_cap"),
                    laterCap = leftover.int("later_cap"),
                ),
                cross = CrossSection(
                    flippedBarsMax = cross.int("flipped_bars_max"),
                    streetNowBps = cross.int("street_now_bps"),
                    streetAlmostBps = cross.int("street_almost_bps"),
                    fFloor = cross.int("f_floor"),
                    rsiHot = cross.double("rsi_hot"),
                    nowCap = cross.int("now_cap"),
                    laterCap = cross.int("later_cap"),
                ),
                macdHorizon = MacdHorizonSection(
                    align = macd.int("align"),
                    flat = macd.int("flat"),
                    drag = macd.int("drag"),
                ),
                pricePath = PricePathSection(
                    clusterAtrFrac = pricePath.double("cluster_atr_frac"),
                    minZoneAtrFrac = pricePath.double("min_zone_atr_frac"),
                    maxZoneAtrFrac = pricePath.double("max_zone_atr_frac"),
                    atrBandMult = pricePath.double("atr_band_mult"),
                    nearZoneAtr = pricePath.double("near_zone_atr"),
                    inZoneEpsAtr = pricePath.double("in_zone_eps_atr"),
                    maxMotives = pricePath.int("max_motives"),
                    sessionsCap = pricePath.int("sessions_cap"),
                    bootstrapPaths = pricePath.int("bootstrap_paths"),
                    bootstrapMinReturns = pricePath.int("bootstrap_min_returns"),
                ),
                opportunity = OpportunitySection(
                    rankingIncludesQuantEngine = opportunity.bool("ranking_includes_quant_engine"),
                    dcfOpportunityThresholdBps = opportunity.int("dcf_opportunity_threshold_bps"),
                    dcfExpensiveThresholdBps = opportunity.int("dcf_expensive_threshold_bps"),
                    basisPointsPerUnit = opportunity.double("basis_points_per_unit"),
                    sectorAdjustedMarker = opportunity.string("sector_adjusted_marker"),
                    valuationPanelMultipleCount = opportunity.double("valuation_panel_multiple_count"),
                    legacyAvoidBelowScore = opportunity.int("legacy_avoid_below_score"),
                    legacyActAtOrAboveScore = opportunity.int("legacy_act_at_or_above_score"),
                    continuousAvoidBelowScore = opportunity.int("continuous_avoid_below_score"),
                    continuousActAtOrAboveScore = opportunity.int("continuous_act_at_or_above_score"),
                    v2 = parseV2(v2),
                    v3 = parseV3(v3),
                    v4 = OpportunityV4Section(
                        fundSectorCheapMult = v4.double("fund_sector_cheap_mult"),
                        fundSectorRichMult = v4.double("fund_sector_rich_mult"),
                        fundSectorRoeLowerOffsetBps = v4.double("fund_sector_roe_lower_offset_bps"),
                        fundSectorRoeUpperOffsetBps = v4.double("fund_sector_roe_upper_offset_bps"),
                        fundShareCountWeight = v4.double("fund_share_count_weight"),
                        fundTrendWeight = v4.double("fund_trend_weight"),
                        fundPulseWeight = v4.double("fund_pulse_weight"),
                        pulseMinAbsEpsCents = v4.long("pulse_min_abs_eps_cents"),
                        trendMaxYears = v4.int("trend_max_years"),
                        trendMinTransitions = v4.int("trend_min_transitions"),
                        fundShareCountShrinkBps = v4.double("fund_share_count_shrink_bps"),
                        fundShareCountDiluteBps = v4.double("fund_share_count_dilute_bps"),
                        compositeAgreementBonus = v4.int("composite_agreement_bonus"),
                        compositeBound = v4.int("composite_bound"),
                        spreadFull = v4.double("spread_full"),
                    ),
                ),
            )
        }

        private fun datedErp(row: YamlMap): DatedRateRow = DatedRateRow(
            asOfDate = row.string("as_of_date"),
            valueBps = row.int("erp_bps"),
            source = row.string("source"),
        )

        private fun parseV2(v2: YamlMap): OpportunityV2Section = OpportunityV2Section(
            fundFcfYieldLower = v2.double("fund_fcf_yield_lower"),
            fundFcfYieldUpper = v2.double("fund_fcf_yield_upper"),
            fundFcfWeight = v2.double("fund_fcf_weight"),
            fundOcfFallbackWeight = v2.double("fund_ocf_fallback_weight"),
            fundRoeLowerBps = v2.double("fund_roe_lower_bps"),
            fundRoeUpperBps = v2.double("fund_roe_upper_bps"),
            fundRoeWeight = v2.double("fund_roe_weight"),
            fundGrowthLowerBps = v2.double("fund_growth_lower_bps"),
            fundGrowthUpperBps = v2.double("fund_growth_upper_bps"),
            fundGrowthWeight = v2.double("fund_growth_weight"),
            fundBalanceDeLow = v2.double("fund_balance_de_low"),
            fundBalanceDeHigh = v2.double("fund_balance_de_high"),
            fundBalanceWeight = v2.double("fund_balance_weight"),
            fundPeLow = v2.double("fund_pe_low"),
            fundPeHigh = v2.double("fund_pe_high"),
            fundPeWeight = v2.double("fund_pe_weight"),
            techTrendDeltaBound = v2.double("tech_trend_delta_bound"),
            techTrend2050Weight = v2.double("tech_trend_20_50_weight"),
            techTrend50200Weight = v2.double("tech_trend_50_200_weight"),
            techTrendPrice20Weight = v2.double("tech_trend_price_20_weight"),
            techHistogramBound = v2.double("tech_histogram_bound"),
            techHistogramWeight = v2.double("tech_histogram_weight"),
            techMacdDirectionWeight = v2.double("tech_macd_direction_weight"),
            forecastUpsideLowerBps = v2.double("forecast_upside_lower_bps"),
            forecastUpsideUpperBps = v2.double("forecast_upside_upper_bps"),
            forecastValuationWeight = v2.double("forecast_valuation_weight"),
            forecastRecLowHundredths = v2.double("forecast_rec_low_hundredths"),
            forecastRecHighHundredths = v2.double("forecast_rec_high_hundredths"),
            forecastRecWeight = v2.double("forecast_rec_weight"),
            forecastMinAnalystOpinions = v2.int("forecast_min_analyst_opinions"),
            forecastFullAnalystOpinions = v2.double("forecast_full_analyst_opinions"),
            forecastBreadthWeight = v2.double("forecast_breadth_weight"),
            forecastUncertaintyBound = v2.double("forecast_uncertainty_bound"),
            forecastUncertaintyWeight = v2.double("forecast_uncertainty_weight"),
            forecastFreshnessWeight = v2.double("forecast_freshness_weight"),
            forecastFreshnessHalfLifeSeconds = v2.double("forecast_freshness_half_life_seconds"),
            forecastDcfReliability = v2.double("forecast_dcf_reliability"),
            forecastMinReliableEvidenceWeight = v2.double("forecast_min_reliable_evidence_weight"),
            fundamentalsFullWeight = v2.double("fundamentals_full_weight"),
            technicalsFullWeight = v2.double("technicals_full_weight"),
            compositeCoverageBonus = v2.int("composite_coverage_bonus"),
            compositeBound = v2.int("composite_bound"),
        )

        private fun parseV3(v3: YamlMap): OpportunityV3Section = OpportunityV3Section(
            fundFcfYieldLower = v3.double("fund_fcf_yield_lower"),
            fundFcfYieldUpper = v3.double("fund_fcf_yield_upper"),
            fundFcfWeight = v3.double("fund_fcf_weight"),
            fundOcfFallbackWeight = v3.double("fund_ocf_fallback_weight"),
            fundRoeLowerBps = v3.double("fund_roe_lower_bps"),
            fundRoeUpperBps = v3.double("fund_roe_upper_bps"),
            fundRoeWeight = v3.double("fund_roe_weight"),
            fundGrowthLowerBps = v3.double("fund_growth_lower_bps"),
            fundGrowthUpperBps = v3.double("fund_growth_upper_bps"),
            fundGrowthWeight = v3.double("fund_growth_weight"),
            fundBalanceDeLow = v3.double("fund_balance_de_low"),
            fundBalanceDeHigh = v3.double("fund_balance_de_high"),
            fundBalanceWeight = v3.double("fund_balance_weight"),
            fundValuationWeight = v3.double("fund_valuation_weight"),
            fundPeLow = v3.double("fund_pe_low"),
            fundPeHigh = v3.double("fund_pe_high"),
            fundEvEbitdaLow = v3.double("fund_ev_ebitda_low"),
            fundEvEbitdaHigh = v3.double("fund_ev_ebitda_high"),
            fundPbLow = v3.double("fund_pb_low"),
            fundPbHigh = v3.double("fund_pb_high"),
            fundCashQualityWeight = v3.double("fund_cash_quality_weight"),
            techTrendDeltaBound = v3.double("tech_trend_delta_bound"),
            techTrendPrice20Weight = v3.double("tech_trend_price_20_weight"),
            techTrend2050Weight = v3.double("tech_trend_20_50_weight"),
            techTrend50200Weight = v3.double("tech_trend_50_200_weight"),
            techHistogramBound = v3.double("tech_histogram_bound"),
            techHistogramWeight = v3.double("tech_histogram_weight"),
            techMacdDirectionWeight = v3.double("tech_macd_direction_weight"),
            techRsiWeight = v3.double("tech_rsi_weight"),
            techVolumeWeight = v3.double("tech_volume_weight"),
            techRsiSlopeBound = v3.double("tech_rsi_slope_bound"),
            techVolumeRatioLow = v3.double("tech_volume_ratio_low"),
            techVolumeRatioHigh = v3.double("tech_volume_ratio_high"),
            forecastUpsideLowerBps = v3.double("forecast_upside_lower_bps"),
            forecastUpsideUpperBps = v3.double("forecast_upside_upper_bps"),
            forecastValuationWeight = v3.double("forecast_valuation_weight"),
            forecastRecLowHundredths = v3.double("forecast_rec_low_hundredths"),
            forecastRecHighHundredths = v3.double("forecast_rec_high_hundredths"),
            forecastRecWeight = v3.double("forecast_rec_weight"),
            forecastSkewWeight = v3.double("forecast_skew_weight"),
            forecastMinAnalystOpinions = v3.int("forecast_min_analyst_opinions"),
            forecastFullAnalystOpinions = v3.double("forecast_full_analyst_opinions"),
            forecastBreadthWeight = v3.double("forecast_breadth_weight"),
            forecastAnalystUncertaintyWeight = v3.double("forecast_analyst_uncertainty_weight"),
            forecastDcfUncertaintyWeight = v3.double("forecast_dcf_uncertainty_weight"),
            forecastUncertaintyBound = v3.double("forecast_uncertainty_bound"),
            forecastDcfWidthLower = v3.double("forecast_dcf_width_lower"),
            forecastDcfWidthUpper = v3.double("forecast_dcf_width_upper"),
            forecastFreshnessWeight = v3.double("forecast_freshness_weight"),
            forecastFreshnessHalfLifeSeconds = v3.double("forecast_freshness_half_life_seconds"),
            forecastDcfReliability = v3.double("forecast_dcf_reliability"),
            forecastMinReliableEvidenceWeight = v3.double("forecast_min_reliable_evidence_weight"),
            fundamentalsFullWeight = v3.double("fundamentals_full_weight"),
            technicalsFullWeight = v3.double("technicals_full_weight"),
            compositeCoverageBonus = v3.int("composite_coverage_bonus"),
            compositeBound = v3.int("composite_bound"),
            betaHaircutMax = v3.double("beta_haircut_max"),
            betaHaircutMultMax = v3.double("beta_haircut_mult_max"),
            betaLowMillis = v3.double("beta_low_millis"),
            betaHighMillis = v3.double("beta_high_millis"),
        )
    }
}

internal class YamlMap(private val raw: Map<*, *>, private val path: String) {
    fun child(key: String): YamlMap {
        var value = raw[key] ?: error("missing key: $path.$key")
        if (value !is Map<*, *>) error("$path.$key is not a map")
        return YamlMap(value, "$path.$key")
    }

    fun int(key: String): Int = yamlInt(need(key), "$path.$key")

    fun long(key: String): Long = yamlLong(need(key), "$path.$key")

    fun double(key: String): Double = yamlDouble(need(key), "$path.$key")

    fun bool(key: String): Boolean {
        var value = need(key)
        if (value !is Boolean) error("$path.$key expected bool")
        return value
    }

    fun string(key: String): String {
        var value = need(key)
        if (value !is String) error("$path.$key expected string")
        return value
    }

    fun stringList(key: String): List<String> = yamlStringList(need(key), "$path.$key")

    fun optionalStringList(key: String): List<String> {
        var value = raw[key] ?: return emptyList()
        return yamlStringList(value, "$path.$key")
    }

    fun mapList(key: String): List<YamlMap> {
        var value = need(key)
        if (value !is List<*>) error("$path.$key expected list")
        return value.mapIndexed { index, item ->
            if (item !is Map<*, *>) error("$path.$key[$index] is not a map")
            YamlMap(item, "$path.$key[$index]")
        }
    }

    private fun need(key: String): Any = raw[key] ?: error("missing key: $path.$key")
}

private fun yamlInt(value: Any, path: String): Int = when (value) {
    is Int -> value
    is Long -> value.toInt()
    is Double -> {
        if (value % 1.0 != 0.0) error("$path is not a whole number: $value")
        value.toInt()
    }
    else -> error("$path expected int, got ${value::class.simpleName}")
}

private fun yamlLong(value: Any, path: String): Long = when (value) {
    is Int -> value.toLong()
    is Long -> value
    is Double -> {
        if (value % 1.0 != 0.0) error("$path is not a whole number: $value")
        value.toLong()
    }
    else -> error("$path expected long, got ${value::class.simpleName}")
}

private fun yamlDouble(value: Any, path: String): Double = when (value) {
    is Double -> value
    is Int -> value.toDouble()
    is Long -> value.toDouble()
    else -> error("$path expected number, got ${value::class.simpleName}")
}

private fun yamlStringList(value: Any, path: String): List<String> {
    if (value !is List<*>) error("$path expected list")
    return value.mapIndexed { index, item ->
        if (item !is String) error("$path[$index] expected string")
        item
    }
}
