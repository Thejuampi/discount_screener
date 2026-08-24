package com.discountscreener.core.engine

import com.discountscreener.core.math.isForeignTo
import com.discountscreener.core.math.medianOf
import com.discountscreener.core.math.robustCentre
import com.discountscreener.core.model.AnnualReportedValue
import com.discountscreener.core.model.BusinessClass
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ChartRangeSummary
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.carriesMarketDimension
import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.DcfSignal
import com.discountscreener.core.model.ExternalSignalStatus
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.FundamentalTimeseries
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.OpportunityRow
import com.discountscreener.core.model.ScoreFactor
import com.discountscreener.core.model.ScoreFactorComparison
import com.discountscreener.core.model.ScoreFactorValueKind
import com.discountscreener.core.model.SymbolDetail
import com.discountscreener.core.model.ViewFilter
import com.discountscreener.core.regime.AssetClassification
import com.discountscreener.core.regime.MarketContextUnavailableReason
import com.discountscreener.core.regime.MarketRegime
import com.discountscreener.core.regime.RegimeCause
import com.discountscreener.core.regime.RegimeFitResult
import com.discountscreener.core.regime.RegimeScoreStatus
import com.discountscreener.core.regime.RegimeScoringPolicy
import com.discountscreener.core.regime.marketFeatureSet
import com.discountscreener.core.regime.scoreRegimeFit
import java.math.BigInteger
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private const val DCF_OPPORTUNITY_THRESHOLD_BPS = 2_000
private const val DCF_EXPENSIVE_THRESHOLD_BPS = -1_000

/**
 * Quant Engine (FCFF / residual income / DCF scenarios) is diagnostic-only for ranking
 * until model quality is trustworthy. Detail / Quant Lens still compute and display it.
 * Flip only after an explicit product decision to re-enable.
 */
private const val RANKING_INCLUDES_QUANT_ENGINE = false

// AggressiveV2 tuning constants. Centralised so future calibration changes one place.
private const val V2_FUND_FCF_YIELD_LOWER = -0.02
private const val V2_FUND_FCF_YIELD_UPPER = 0.08
private const val V2_FUND_FCF_WEIGHT = 25.0
private const val V2_FUND_OCF_FALLBACK_WEIGHT = 10.0
private const val V2_FUND_ROE_LOWER_BPS = 0.0
private const val V2_FUND_ROE_UPPER_BPS = 2_000.0
private const val V2_FUND_ROE_WEIGHT = 20.0
private const val V2_FUND_GROWTH_LOWER_BPS = -500.0
private const val V2_FUND_GROWTH_UPPER_BPS = 1_500.0
private const val V2_FUND_GROWTH_WEIGHT = 15.0
private const val V2_FUND_BALANCE_DE_LOW = 30.0
private const val V2_FUND_BALANCE_DE_HIGH = 200.0
private const val V2_FUND_BALANCE_WEIGHT = 20.0
private const val V2_FUND_PE_LOW = 800.0
private const val V2_FUND_PE_HIGH = 3_500.0
private const val V2_FUND_PE_WEIGHT = 20.0

private const val V2_TECH_TREND_DELTA_BOUND = 0.10
private const val V2_TECH_TREND_20_50_WEIGHT = 24.0
private const val V2_TECH_TREND_50_200_WEIGHT = 21.0
private const val V2_TECH_TREND_PRICE_20_WEIGHT = 15.0
private const val V2_TECH_HISTOGRAM_BOUND = 0.005
private const val V2_TECH_HISTOGRAM_WEIGHT = 25.0
private const val V2_TECH_MACD_DIRECTION_WEIGHT = 15.0

private const val V2_FORECAST_UPSIDE_LOWER_BPS = -2_000.0
private const val V2_FORECAST_UPSIDE_UPPER_BPS = 5_000.0
private const val V2_FORECAST_VALUATION_WEIGHT = 50.0
private const val V2_FORECAST_REC_LOW_HUNDREDTHS = 150.0
private const val V2_FORECAST_REC_HIGH_HUNDREDTHS = 300.0
private const val V2_FORECAST_REC_WEIGHT = 15.0
private const val V2_FORECAST_MIN_ANALYST_OPINIONS = 3
private const val V2_FORECAST_FULL_ANALYST_OPINIONS = 15.0
private const val V2_FORECAST_BREADTH_WEIGHT = 20.0
private const val V2_FORECAST_UNCERTAINTY_BOUND = 0.6
private const val V2_FORECAST_UNCERTAINTY_WEIGHT = 10.0
private const val V2_FORECAST_FRESHNESS_WEIGHT = 5.0
private const val V2_FORECAST_FRESHNESS_HALF_LIFE_SECONDS = 14.0 * 86_400.0
private const val V2_FORECAST_DCF_RELIABILITY = 0.75
private const val V2_FORECAST_MIN_RELIABLE_EVIDENCE_WEIGHT = 25.0

private const val V2_FUNDAMENTALS_FULL_WEIGHT = 100.0
private const val V2_TECHNICALS_FULL_WEIGHT = 100.0
private const val V2_FORECAST_FULL_WEIGHT =
    V2_FORECAST_VALUATION_WEIGHT +
        V2_FORECAST_REC_WEIGHT +
        V2_FORECAST_BREADTH_WEIGHT +
        V2_FORECAST_UNCERTAINTY_WEIGHT +
        V2_FORECAST_FRESHNESS_WEIGHT

private const val V2_COMPOSITE_COVERAGE_BONUS = 5
private const val V2_COMPOSITE_BOUND = 110

// AggressiveV3 tuning constants. Extends V2 evidence math with multi-multiple valuation,
// RSI/volume technicals, recommendation skew, DCF scenario width, and a beta risk haircut.
private const val V3_FUND_FCF_YIELD_LOWER = -0.02
private const val V3_FUND_FCF_YIELD_UPPER = 0.08
private const val V3_FUND_FCF_WEIGHT = 22.0
private const val V3_FUND_OCF_FALLBACK_WEIGHT = 10.0
/** Provisional OCF yield band. Unmeasured OCF/FCF ratios; calibrate before treating as policy. */
private const val V3_FUND_OCF_YIELD_LOWER = 0.00
private const val V3_FUND_OCF_YIELD_UPPER = 0.10
private const val V3_FUND_ROE_LOWER_BPS = 0.0
private const val V3_FUND_ROE_UPPER_BPS = 2_000.0
private const val V3_FUND_ROE_WEIGHT = 16.0
private const val V3_FUND_GROWTH_LOWER_BPS = -500.0
private const val V3_FUND_GROWTH_UPPER_BPS = 1_500.0
private const val V3_FUND_GROWTH_WEIGHT = 12.0
private const val V3_FUND_BALANCE_DE_LOW = 30.0
private const val V3_FUND_BALANCE_DE_HIGH = 200.0
private const val V3_FUND_BALANCE_WEIGHT = 16.0
private const val V3_FUND_VALUATION_WEIGHT = 24.0
private const val V3_FUND_PE_LOW = 800.0
private const val V3_FUND_PE_HIGH = 3_500.0
private const val V3_FUND_EV_EBITDA_LOW = 600.0
private const val V3_FUND_EV_EBITDA_HIGH = 2_000.0
private const val V3_FUND_PB_LOW = 100.0
private const val V3_FUND_PB_HIGH = 500.0

/** Forward P/E, EV/EBITDA and P/B — the divisor that stops one multiple saturating the panel. */
private const val VALUATION_PANEL_MULTIPLE_COUNT = 3.0
private const val V3_FUND_CASH_QUALITY_WEIGHT = 10.0

// V4's sector bands. The three price multiples get a multiplicative ramp around the sector centre
// and return on equity gets an additive one, because return on equity crosses zero and a
// percentage band does not survive that. Both shapes and all four numbers are Windows's
// (`engine.rs:1299-1305`), so the later Rust port has one set of constants to agree with.
private const val V4_FUND_SECTOR_CHEAP_MULT = 0.7
private const val V4_FUND_SECTOR_RICH_MULT = 1.5
private const val V4_FUND_SECTOR_ROE_LOWER_OFFSET_BPS = -500.0
private const val V4_FUND_SECTOR_ROE_UPPER_OFFSET_BPS = 1_500.0
/** Additive FCF-yield band around the sector centre. Yield crosses zero, so this is not a × band. */
private const val V4_FUND_SECTOR_FCF_YIELD_LOWER_OFFSET_BPS = -400.0
private const val V4_FUND_SECTOR_FCF_YIELD_UPPER_OFFSET_BPS = 400.0

/**
 * V4's leverage band, in hundredths of a turn of EBITDA. Zero is net cash; 300 is three turns of
 * net debt.
 *
 * Three turns is the level at which a lender starts writing covenants, and it is a chosen line
 * rather than a measured one. Below zero the company owes nothing on balance and the ramp is
 * already at its top, which is correct: paying down the last of the net debt is not what makes the
 * next dollar of return.
 */
private const val V4_FUND_LEVERAGE_LOW = 0.0
private const val V4_FUND_LEVERAGE_HIGH = 300.0

/**
 * The sector band around the leverage centre, additive for the same reason return on equity's is:
 * net debt crosses zero, and a multiplicative band around a negative centre inverts.
 *
 * Plus or minus one and a half turns. A pipeline at 3.5x is ordinary and a software company at
 * 3.5x is in trouble, and this is the offset that lets one band say both.
 */
private const val V4_FUND_SECTOR_LEVERAGE_LOWER_OFFSET = -150.0
private const val V4_FUND_SECTOR_LEVERAGE_UPPER_OFFSET = 150.0

/**
 * The fallback band, stated in the unit the ingestion actually produces.
 *
 * Yahoo reports `financialData.debtToEquity` as a percent — AMZN comes back as 40.46 for a ratio of
 * 0.4046 — and every ingestion in this repo multiplies it by a hundred again. The stored number is
 * therefore the ratio times ten thousand. V2 and V3 ramp that field against 30 to 200
 * ([V3_FUND_BALANCE_DE_LOW]), so every real ticker saturates and their leverage component scores
 * the same constant for the whole universe.
 *
 * Those two models are frozen and keep the behaviour. V4 reads the field against the band the
 * field is in. Correcting the ingestion instead is not open: Windows's valuation runtime is
 * calibrated to the inflated number (`operating_valuation_runtime.rs:771-778` divides by ten
 * thousand), and rescaling under it would move hold-years and the leverage refusals with no test
 * standing behind either.
 *
 * This is the second choice regardless. It is reached only when EBITDA is missing or non-positive,
 * and it carries its own label so the weaker input is visible in the factor list.
 */
private const val V4_FUND_FALLBACK_DE_LOW = 3_000.0
private const val V4_FUND_FALLBACK_DE_HIGH = 20_000.0

/**
 * Held equal to [V3_FUND_BALANCE_WEIGHT] on purpose. V4's budget is V3's plus the share-count
 * weight ([V4_FUNDAMENTALS_FULL_WEIGHT]); changing the leverage weight alone would silently
 * re-scale every other V4 factor.
 */
private const val V4_FUND_LEVERAGE_WEIGHT = V3_FUND_BALANCE_WEIGHT

/**
 * The one character that says a metric was scored against its sector rather than an absolute band.
 *
 * Two rows in one list scored by different rules, with nothing saying which, is the same defect as
 * a refusal drawn as a mute dash. Windows spends the same character for the same reason
 * (`engine.rs:1303`).
 */
private const val SECTOR_ADJUSTED_MARKER = "§"

private const val V3_TECH_TREND_DELTA_BOUND = 0.10
private const val V3_TECH_TREND_PRICE_20_WEIGHT = 12.0
private const val V3_TECH_TREND_20_50_WEIGHT = 18.0
private const val V3_TECH_TREND_50_200_WEIGHT = 15.0
private const val V3_TECH_HISTOGRAM_BOUND = 0.005
private const val V3_TECH_HISTOGRAM_WEIGHT = 12.0
private const val V3_TECH_MACD_DIRECTION_WEIGHT = 8.0
private const val V3_TECH_RSI_WEIGHT = 25.0
private const val V3_TECH_VOLUME_WEIGHT = 10.0
private const val V3_TECH_RSI_SLOPE_BOUND = 2.0
private const val V3_TECH_VOLUME_RATIO_LOW = 70.0
private const val V3_TECH_VOLUME_RATIO_HIGH = 150.0

private const val V3_FORECAST_UPSIDE_LOWER_BPS = -2_000.0
private const val V3_FORECAST_UPSIDE_UPPER_BPS = 5_000.0
private const val V3_FORECAST_VALUATION_WEIGHT = 42.0
private const val V3_FORECAST_REC_LOW_HUNDREDTHS = 150.0
private const val V3_FORECAST_REC_HIGH_HUNDREDTHS = 300.0
private const val V3_FORECAST_REC_WEIGHT = 12.0
private const val V3_FORECAST_SKEW_WEIGHT = 12.0
private const val V3_FORECAST_MIN_ANALYST_OPINIONS = 3
private const val V3_FORECAST_FULL_ANALYST_OPINIONS = 15.0
private const val V3_FORECAST_BREADTH_WEIGHT = 14.0
internal const val V3_FORECAST_UNCERTAINTY_BOUND = 0.6
private const val V3_FORECAST_ANALYST_UNCERTAINTY_WEIGHT = 8.0
private const val V3_FORECAST_DCF_UNCERTAINTY_WEIGHT = 8.0
internal const val V3_FORECAST_DCF_WIDTH_LOWER = 0.2
private const val V3_FORECAST_DCF_WIDTH_UPPER = 1.0
private const val V3_FORECAST_FRESHNESS_WEIGHT = 4.0
private const val V3_FORECAST_FRESHNESS_HALF_LIFE_SECONDS = 14.0 * 86_400.0
private const val V3_FORECAST_DCF_RELIABILITY = 0.75
private const val V3_FORECAST_MIN_RELIABLE_EVIDENCE_WEIGHT = 25.0

/**
 * V4 forecast keeps V3's Street centre and rating terms. It does not pay points
 * for coverage or freshness — those already scale reliability. The leftover
 * budget goes to target-range disagreement, which V3 underweighted.
 */
private const val V4_FORECAST_VALUATION_WEIGHT = V3_FORECAST_VALUATION_WEIGHT
private const val V4_FORECAST_REC_WEIGHT = V3_FORECAST_REC_WEIGHT
private const val V4_FORECAST_SKEW_WEIGHT = V3_FORECAST_SKEW_WEIGHT
private const val V4_FORECAST_UNCERTAINTY_WEIGHT =
    V3_FORECAST_ANALYST_UNCERTAINTY_WEIGHT + V3_FORECAST_BREADTH_WEIGHT + V3_FORECAST_FRESHNESS_WEIGHT
private const val V4_FORECAST_FULL_WEIGHT =
    V4_FORECAST_VALUATION_WEIGHT +
        V4_FORECAST_REC_WEIGHT +
        V4_FORECAST_SKEW_WEIGHT +
        V4_FORECAST_UNCERTAINTY_WEIGHT
private const val V4_FORECAST_UNCERTAINTY_BOUND = V3_FORECAST_UNCERTAINTY_BOUND
private const val V4_FORECAST_MIN_RELIABLE_EVIDENCE_WEIGHT = V3_FORECAST_MIN_RELIABLE_EVIDENCE_WEIGHT

private const val V3_FUNDAMENTALS_FULL_WEIGHT = 100.0

private const val BASIS_POINTS_PER_UNIT = 10_000.0

private const val V4_FUND_SHARE_COUNT_WEIGHT = 10.0

/** Half the old V3 Growth weight of 12. Pulse and Trend share that budget. */
private const val V4_FUND_TREND_WEIGHT = 6.0
private const val V4_FUND_PULSE_WEIGHT = 6.0
private const val V4_FUND_GROWTH_LOWER_BPS = V3_FUND_GROWTH_LOWER_BPS
private const val V4_FUND_GROWTH_UPPER_BPS = V3_FUND_GROWTH_UPPER_BPS

/** Below this trailing EPS, Yahoo quarter YoY has no usable base. Ten cents is in. */
private const val V4_PULSE_MIN_ABS_EPS_CENTS = 10L

/**
 * The smallest gap between Trend and Pulse that this engine calls a conflict.
 *
 * The old test compared ramp signs, and the shared band's midpoint sits at +5% growth — so +6%
 * revenue against +4% EPS flagged, while two readings of a collapsing business on the same side
 * of the midpoint did not. Ten points of growth is the smallest disagreement that says the two
 * series describe different businesses; anything under it is noise around the middle of the band.
 */
private const val V4_GROWTH_CONFLICT_BPS = 1_000

// AggressiveV5's two refusals. Both are labels as well as gates: the score journal reads them,
// so the outcome report can later attribute a spread to the refusal instead of to noise.
private const val V5_PULSE_REFUSED_LABEL = "Pulse∅ loss-year"
private const val V5_CLASS_UNKNOWN_LABEL = "Class∅ unknown"
internal const val FCF_REFUSED_FINANCIAL_LABEL = "FCFy∅ financial"
internal const val FCF_REFUSED_UNKNOWN_LABEL = "FCFy∅ unknown"
internal const val FCF_REFUSED_INELIGIBLE_LABEL = "FCFy∅ ineligible"
internal const val OCF_BAND_UNMEASURED_LABEL = "OCFy∅ unmeasured"
internal const val FUND_COVERAGE_GAP_LABEL = "Fund∅ coverage"
private const val COVERAGE_GAP_IDLE_WEIGHT = 0.5

/** Last five annual revenue points give at most four YoY rates. Two rates are the floor. */
private const val V4_TREND_MAX_YEARS = 5
private const val V4_TREND_MIN_TRANSITIONS = 2

/**
 * The share-count band: a three per cent annual move either way is the whole ramp.
 *
 * A chosen band, not a measured one, and it is worth saying so. Three per cent a year is roughly
 * where a buyback stops being housekeeping and starts being a return of capital, and where
 * dilution stops being option grants and starts being the shareholder paying for growth. Nothing
 * in this repository has measured that boundary; the score journal is what could later challenge
 * it, and the band is deliberately narrow enough that most companies land inside the ramp rather
 * than pinned at an end.
 */
private const val V4_FUND_SHARE_COUNT_SHRINK_BPS = -300.0
private const val V4_FUND_SHARE_COUNT_DILUTE_BPS = 300.0

/**
 * V4 inherits V3's term weights and adds the ones V3 does not have.
 *
 * The divisor is the **full** budget, not the weight observed, so a symbol with no share history
 * scores its other terms against a total that includes the share term. That is deliberate: a
 * missing input pulls the bucket toward zero rather than being silently excused, which is what
 * "the term contributes nothing" has to mean if it is not to mean "the term scored zero".
 */
private const val V4_FUNDAMENTALS_FULL_WEIGHT = V3_FUNDAMENTALS_FULL_WEIGHT + V4_FUND_SHARE_COUNT_WEIGHT
private const val V3_TECHNICALS_FULL_WEIGHT = 100.0
private const val V3_FORECAST_FULL_WEIGHT =
    V3_FORECAST_VALUATION_WEIGHT +
        V3_FORECAST_REC_WEIGHT +
        V3_FORECAST_SKEW_WEIGHT +
        V3_FORECAST_BREADTH_WEIGHT +
        V3_FORECAST_ANALYST_UNCERTAINTY_WEIGHT +
        V3_FORECAST_DCF_UNCERTAINTY_WEIGHT +
        V3_FORECAST_FRESHNESS_WEIGHT

private const val V3_COMPOSITE_COVERAGE_BONUS = 5
private const val V3_COMPOSITE_BOUND = 110
private const val V3_BETA_HAIRCUT_MAX = 10.0

/** `beta_haircut_mult.clamp(0.0, 2.5)` in `composite_score_v3_ext`. */
private const val V3_BETA_HAIRCUT_MULT_MAX = 2.5
private const val V3_BETA_LOW_MILLIS = 800.0
private const val V3_BETA_HIGH_MILLIS = 1_600.0

// AggressiveV4 tuning constants. Fundamentals keep V3's term weights except Growth,
// which splits into Trend (revenue 3–5y) and Pulse (quarter EPS YoY). The composite
// pays for agreement, not presence.
private const val V4_COMPOSITE_AGREEMENT_BONUS = 5
private const val V4_COMPOSITE_BOUND = 110

/**
 * The bucket spread at which the agreement bonus reaches zero.
 *
 * Measured, not chosen: the p90 of the mean absolute deviation across the buckets, over the 61
 * **qualified** rows of a live S&P 500 reading on 2026-08-11, taken around the median — the same
 * centre this function computes. Recorded in `lab/data/overlap-spread-median-2026-08-11.txt`.
 *
 * Both halves of that sentence are load-bearing. The cohort's p90 is 29.0, but the cohort is not
 * what this constant grades: the Opportunities list is markedly more divided, and its median row's
 * spread of 22.5 is near the *cohort's* p75. A cohort-fit constant would have paid nothing to about
 * a third of the list while claiming to zero only its most divided tenth.
 */
private const val V4_SPREAD_FULL = 38.5

// Act/Avoid cutoffs. Legacy/Aggressive use the original 0–15-ish point scale; V2/V3 use ±100 means.
private const val LEGACY_AVOID_BELOW_SCORE = 8
private const val LEGACY_ACT_AT_OR_ABOVE_SCORE = 10
private const val CONTINUOUS_AVOID_BELOW_SCORE = 0
private const val CONTINUOUS_ACT_AT_OR_ABOVE_SCORE = 30

data class OpportunityContext(
    val filter: ViewFilter = ViewFilter(),
    val chartSummariesBySymbol: Map<String, Map<ChartRange, ChartRangeSummary>> = emptyMap(),
    val analysesBySymbol: Map<String, DcfAnalysis> = emptyMap(),
    val scoringModel: OpportunityScoringModel = OpportunityScoringModel.Legacy,
    /**
     * Per-symbol summaries built from *daily* bars, for the market dimension alone.
     *
     * [chartSummariesBySymbol] cannot serve here. Its `ChartRange.Year` entry is `1y`/`1wk` — fifty
     * two weekly bars — so a %B computed from it spans twenty weeks rather than twenty days, and a
     * fifty-two-week position is read off fifty-two points. Windows scores the fit on ~252 daily
     * bars. Measuring a different thing under the same name is how two platforms drift while every
     * test on both sides stays green, so the daily series is carried separately and the weekly one
     * keeps driving technicals untouched.
     *
     * Empty until a market read lands; a symbol missing from it scores no fourth bucket rather than
     * falling back to the weekly summary.
     */
    val regimeSummariesBySymbol: Map<String, ChartRangeSummary> = emptyMap(),
    /** Null while the market has not been read yet, or could not be. */
    val marketRegime: MarketRegime? = null,
    /** The user's runtime switch. Off scores every name on the three original buckets. */
    val regimeScoringEnabled: Boolean = true,
    /**
     * Sector levels for V4's fundamentals bucket, keyed by sector name, computed once per snapshot.
     *
     * Empty for every model but V4, and empty for V4 too until a snapshot supplies it: a missing
     * sector means the row falls back to the absolute band and says so, never that it throws.
     */
    val sectorBenchmarks: Map<String, SectorBenchmarks> = emptyMap(),
    /**
     * The annual driver series, for the one term in V4 that needs history rather than a level.
     *
     * Only the share count is read from it here. The rest of the bucket reads
     * [SymbolDetail.fundamentals], which is a snapshot and cannot say which way anything moved.
     */
    val timeseriesBySymbol: Map<String, FundamentalTimeseries> = emptyMap(),
)

/**
 * One bucket's score plus the terms that built it.
 *
 * [first] and [second] keep the older `Pair` call sites compiling: they read the score and the
 * `++` tokens. New call sites read [factors] for the points.
 */
internal data class BucketEvidence(
    val score: Int?,
    val signals: List<String>,
    val factors: List<ScoreFactor> = emptyList(),
) {
    val first: Int? get() = score
    val second: List<String> get() = signals

    companion object {
        fun absent(): BucketEvidence = BucketEvidence(score = null, signals = emptyList())
    }
}

data class OpportunityScoreBreakdown(
    val fundamentalsScore: Int?,
    val technicalScore: Int?,
    val forecastScore: Int?,
    val regimeScore: Int?,
    val compositeScore: Int,
    /** The three-bucket composite, always computed, so the dimension's impact is a subtraction. */
    val compositeScoreBase: Int,
    val coverageCount: Int,
    val fundamentalsSignals: List<String>,
    val technicalSignals: List<String>,
    val forecastSignals: List<String>,
    val fundamentalsFactors: List<ScoreFactor> = emptyList(),
    val technicalFactors: List<ScoreFactor> = emptyList(),
    val forecastFactors: List<ScoreFactor> = emptyList(),
    val regimeStatus: RegimeScoreStatus,
    val regimeCauses: List<RegimeCause>,
    val regimeSignals: List<String>,
    val regimeUnavailableReason: MarketContextUnavailableReason?,
)

/**
 * How far apart V4's buckets were, and what that was worth.
 *
 * Unrounded, because the composite rounds once at the end and a surface that rounded first would
 * report numbers the score was not built from. [agreement] runs 0.0 (as divided as the constant
 * allows) to 1.0 (identical buckets).
 */
data class V4AgreementReading(
    val centre: Double,
    /**
     * The measured disagreement: the mean distance of the buckets from [centre].
     *
     * Carried alongside [agreement] rather than folded into it, because [agreement] is clamped and
     * therefore cannot be read backwards. An agreement of 0.0 says only "at least as divided as
     * `V4_SPREAD_FULL`" — it cannot distinguish a spread of 39 from a spread of 100, and the
     * distance between those two is the whole quantity V4 claims to price. Anything auditing this
     * model, `shared/contracts/opportunity-v4.json` included, needs the number before the clamp.
     */
    val spread: Double,
    val agreement: Double,
    val bonus: Double,
    /**
     * How many buckets reported. One bucket pays no bonus and is not a disagreement — a surface
     * that told the user four buckets disagreed when only one spoke would be inventing a quarrel.
     */
    val bucketCount: Int,
)

object OpportunityEngine {
    /** Composite scores strictly below this mark opportunities as Avoid (when other gates pass). */
    fun avoidBelowScore(model: OpportunityScoringModel): Int = when (model) {
        OpportunityScoringModel.Legacy,
        OpportunityScoringModel.Aggressive,
        -> LEGACY_AVOID_BELOW_SCORE
        OpportunityScoringModel.AggressiveV2,
        OpportunityScoringModel.AggressiveV3,
        OpportunityScoringModel.AggressiveV4,
        OpportunityScoringModel.AggressiveV5,
        -> CONTINUOUS_AVOID_BELOW_SCORE
    }

    /** High-confidence Act requires composite at or above this mark (when other gates pass). */
    fun actAtOrAboveScore(model: OpportunityScoringModel): Int = when (model) {
        OpportunityScoringModel.Legacy,
        OpportunityScoringModel.Aggressive,
        -> LEGACY_ACT_AT_OR_ABOVE_SCORE
        OpportunityScoringModel.AggressiveV2,
        OpportunityScoringModel.AggressiveV3,
        OpportunityScoringModel.AggressiveV4,
        OpportunityScoringModel.AggressiveV5,
        -> CONTINUOUS_ACT_AT_OR_ABOVE_SCORE
    }

    fun buildRows(
        reportingEngine: ReportingEngine,
        context: OpportunityContext = OpportunityContext(),
        /**
         * Score every candidate, not only the ones that clear qualification.
         *
         * The product list is a *selected* population — qualification keeps roughly one symbol in
         * eight. Any statistic taken over that subset is range-restricted, so an offline study that
         * wants the cohort must ask for it. No shipped call site does; the default is the list.
         */
        includeUnqualified: Boolean = false,
    ): List<OpportunityRow> {
        val rows = reportingEngine
            .filteredRows(reportingEngine.symbolCount().coerceAtLeast(1), context.filter)
            .asSequence()
            .filter { includeUnqualified || it.isQualified }
            .mapNotNull { candidate ->
                val detail = reportingEngine.detail(candidate.symbol) ?: return@mapNotNull null
                var analysis = context.analysesBySymbol[detail.symbol]
                val score = scoreWithModel(
                    detail = detail,
                    summary = preferredChartSummary(context.chartSummariesBySymbol[detail.symbol]),
                    analysis = analysis,
                    model = context.scoringModel,
                    regimeSummary = context.regimeSummariesBySymbol[detail.symbol],
                    marketRegime = context.marketRegime,
                    regimeScoringEnabled = context.regimeScoringEnabled,
                    sectorBenchmarks = detail.fundamentals?.sectorName
                        ?.let { context.sectorBenchmarks[it] },
                    timeseries = context.timeseriesBySymbol[detail.symbol],
                )
                // Read from the same two spans the forecast bucket already measures, so a row cannot
                // report a narrow range here while `Unc` penalises a wide one. It is stamped whether
                // or not the model spread is admitted to the score.
                var outcome = outcomeConfidenceFor(
                    streetWidthBps = spanWidthBps(
                        lowCents = detail.externalSignalLowFairValueCents,
                        highCents = detail.externalSignalHighFairValueCents,
                        centreCents = preferredForecastFairValueCents(detail),
                    ),
                    modelWidthBps = analysis?.let { dcf ->
                        spanWidthBps(
                            lowCents = dcf.bearIntrinsicValueCents,
                            highCents = dcf.bullIntrinsicValueCents,
                            centreCents = dcf.baseIntrinsicValueCents,
                        )
                    },
                )
                OpportunityRow(
                    symbol = detail.symbol,
                    marketPriceCents = detail.marketPriceCents,
                    intrinsicValueCents = detail.intrinsicValueCents,
                    gapBps = detail.gapBps,
                    upsideBps = detail.upsideBps,
                    confidence = detail.confidence,
                    isWatched = detail.isWatched,
                    fundamentalsScore = score.fundamentalsScore,
                    technicalScore = score.technicalScore,
                    forecastScore = score.forecastScore,
                    regimeScore = score.regimeScore,
                    compositeScore = score.compositeScore,
                    compositeScoreBase = score.compositeScoreBase,
                    coverageCount = score.coverageCount,
                    fundamentalsSignals = score.fundamentalsSignals,
                    technicalSignals = score.technicalSignals,
                    forecastSignals = score.forecastSignals,
                    fundamentalsFactors = score.fundamentalsFactors,
                    technicalFactors = score.technicalFactors,
                    forecastFactors = score.forecastFactors,
                    regimeStatus = score.regimeStatus,
                    regimeCauses = score.regimeCauses,
                    regimeSignals = score.regimeSignals,
                    regimeUnavailableReason = score.regimeUnavailableReason,
                    companyName = detail.companyName,
                    nextEarningsEpoch = detail.nextEarningsEpoch,
                    outcomeConfidence = outcome.band,
                    outcomeWidthBps = outcome.widthBps,
                )
            }
            .toMutableList()

        rows.sortWith(
            compareByDescending<OpportunityRow> { it.compositeScore }
                .thenByDescending { it.coverageCount }
                .thenByDescending { confidenceRankValue(it.confidence) }
                .thenByDescending { it.upsideBps }
                .thenBy { it.symbol },
        )
        return rows
    }

    /**
     * The defaults reproduce the three-bucket score exactly: no market reading means no policy,
     * which means [RegimeScoreStatus.Unavailable], a null fourth bucket and a beta multiplier of
     * one. Every call site that predates the market dimension therefore keeps its old answer.
     */
    fun scoreWithModel(
        detail: SymbolDetail,
        summary: ChartRangeSummary?,
        analysis: DcfAnalysis?,
        model: OpportunityScoringModel,
        /** The daily-bar summary — see [OpportunityContext.regimeSummariesBySymbol]. */
        regimeSummary: ChartRangeSummary? = null,
        marketRegime: MarketRegime? = null,
        regimeScoringEnabled: Boolean = true,
        /**
         * The levels for this symbol's own sector, or null when it has none.
         *
         * Null is the honest default rather than a convenience: a call site that does not supply
         * benchmarks gets the absolute band and the plain label, which is exactly what it computed
         * before V4 existed.
         */
        sectorBenchmarks: SectorBenchmarks? = null,
        /** This symbol's annual driver series, for V4's share-count term. */
        timeseries: FundamentalTimeseries? = null,
    ): OpportunityScoreBreakdown {
        var fundamentals = when (model) {
            OpportunityScoringModel.Legacy -> scoreFundamentals(detail).toEvidence()
            OpportunityScoringModel.Aggressive -> aggressiveFundamentalsScore(detail).toEvidence()
            OpportunityScoringModel.AggressiveV2 -> aggressiveV2FundamentalsScore(detail)
            OpportunityScoringModel.AggressiveV3 -> aggressiveV3FundamentalsScore(detail)
            OpportunityScoringModel.AggressiveV4 -> aggressiveV4FundamentalsScore(detail, sectorBenchmarks, timeseries)
            OpportunityScoringModel.AggressiveV5 -> aggressiveV5FundamentalsScore(detail, sectorBenchmarks, timeseries)
        }
        var technical = when (model) {
            OpportunityScoringModel.Legacy -> scoreTechnicals(summary).toEvidence()
            OpportunityScoringModel.Aggressive -> aggressiveTechnicalScore(summary).toEvidence()
            OpportunityScoringModel.AggressiveV2 -> aggressiveV2TechnicalScore(summary)
            OpportunityScoringModel.AggressiveV3,
            OpportunityScoringModel.AggressiveV4,
            OpportunityScoringModel.AggressiveV5,
            -> aggressiveV3TechnicalScore(summary)
        }
        var forecast = when (model) {
            OpportunityScoringModel.Legacy -> scoreForecasts(detail, analysis).toEvidence()
            OpportunityScoringModel.Aggressive -> aggressiveForecastScore(detail, analysis).toEvidence()
            OpportunityScoringModel.AggressiveV2 -> aggressiveV2ForecastScore(detail, analysis)
            OpportunityScoringModel.AggressiveV3 -> aggressiveV3ForecastScore(detail, analysis)
            // V5 inherits V4's street forecast term untouched; its deltas live in fundamentals.
            OpportunityScoringModel.AggressiveV4,
            OpportunityScoringModel.AggressiveV5,
            -> aggressiveV4ForecastScore(detail, analysis)
        }
        var fundamentalsScore = fundamentals.score
        var fundamentalsSignals = fundamentals.signals
        var technicalScore = technical.score
        var technicalSignals = technical.signals
        var forecastScore = forecast.score
        var forecastSignals = forecast.signals
        // The fit is computed only where it can count — a V2 screen must not pay for a bucket it
        // will never carry.
        val applicable = regimeDimensionApplies(model, detail.symbol)
        val policy = if (applicable && regimeScoringEnabled) {
            marketRegime?.let(RegimeScoringPolicy::fromRegime)
        } else {
            null
        }
        val fit = when {
            !applicable || !regimeScoringEnabled -> RegimeFitResult()
            policy == null -> RegimeFitResult(
                unavailableReason = MarketContextUnavailableReason.MarketReadingUnavailable,
            )
            else -> scoreRegimeFit(detail.fundamentals, regimeSummary, policy, model.marketFeatureSet())
        }
        val status = resolveRegimeScoreStatus(applicable, regimeScoringEnabled, policy != null, fit.score)
        val included = status == RegimeScoreStatus.Included
        val regimeScore = fit.score.takeIf { included }
        val betaHaircutMult = if (included) policy?.betaHaircutMult ?: 1.0 else 1.0

        val baseCoverage = listOf(fundamentalsScore, technicalScore, forecastScore).count { it != null }
        val coverageCount = baseCoverage + if (regimeScore != null) 1 else 0
        val compositeBase = compositeScoreFor(
            model = model,
            fundamentals = fundamentalsScore,
            technical = technicalScore,
            forecast = forecastScore,
            regime = null,
            coverageCount = baseCoverage,
            betaMillis = detail.fundamentals?.betaMillis,
            betaHaircutMult = 1.0,
        )
        val composite = if (included) {
            compositeScoreFor(
                model = model,
                fundamentals = fundamentalsScore,
                technical = technicalScore,
                forecast = forecastScore,
                regime = regimeScore,
                coverageCount = coverageCount,
                betaMillis = detail.fundamentals?.betaMillis,
                betaHaircutMult = betaHaircutMult,
            )
        } else {
            compositeBase
        }

        return OpportunityScoreBreakdown(
            fundamentalsScore = fundamentalsScore,
            technicalScore = technicalScore,
            forecastScore = forecastScore,
            regimeScore = regimeScore,
            compositeScore = composite,
            compositeScoreBase = compositeBase,
            coverageCount = coverageCount,
            fundamentalsSignals = fundamentalsSignals,
            technicalSignals = technicalSignals,
            forecastSignals = forecastSignals,
            fundamentalsFactors = fundamentals.factors,
            technicalFactors = technical.factors,
            forecastFactors = forecast.factors,
            regimeStatus = status,
            regimeCauses = if (included) fit.causes else emptyList(),
            regimeSignals = if (included) fit.signals else emptyList(),
            regimeUnavailableReason = fit.unavailableReason.takeIf { status == RegimeScoreStatus.Unavailable },
        )
    }

    /**
     * Whether the market dimension is a thing this row could carry at all. V2, Legacy and
     * Aggressive have no fourth bucket, and the fit features are built for operating companies, so
     * an ETF or a coin has nothing to measure.
     */
    internal fun regimeDimensionApplies(model: OpportunityScoringModel, symbol: String): Boolean =
        model.carriesMarketDimension() && AssetClassification.assetType(symbol) == "stock"

    /**
     * `commands.rs::resolve_regime_score_status`.
     *
     * Order is the whole content of this function. [RegimeScoreStatus.NotApplicable] outranks
     * [RegimeScoreStatus.Disabled] because telling someone looking at V2 that they switched the
     * dimension off would be a lie about their own settings. And a [regimeScore] of zero is a
     * score: a name that fits the regime neither well nor badly is Included, earning the coverage
     * bonus, rather than falling through to Unavailable and earning nothing.
     */
    internal fun resolveRegimeScoreStatus(
        applicable: Boolean,
        toggleEnabled: Boolean,
        policyAvailable: Boolean,
        regimeScore: Int?,
    ): RegimeScoreStatus = when {
        !applicable -> RegimeScoreStatus.NotApplicable
        !toggleEnabled -> RegimeScoreStatus.Disabled
        !policyAvailable || regimeScore == null -> RegimeScoreStatus.Unavailable
        else -> RegimeScoreStatus.Included
    }

    /**
     * `composite_score_v3_ext`, with the other three models kept on the same seam.
     *
     * Takes [betaMillis] rather than the whole detail so the arithmetic can be exercised on its own
     * terms: the coverage bonus and the beta multiplier are what a fourth bucket changes for every
     * V3 name at once, and a test that had to build a plausible company around them would be
     * measuring the fixture as much as the formula.
     */
    internal fun compositeScoreFor(
        model: OpportunityScoringModel,
        fundamentals: Int?,
        technical: Int?,
        forecast: Int?,
        regime: Int?,
        coverageCount: Int,
        betaMillis: Int?,
        betaHaircutMult: Double,
    ): Int = when (model) {
        OpportunityScoringModel.Legacy,
        OpportunityScoringModel.Aggressive,
        -> (fundamentals ?: 0) + (technical ?: 0) + (forecast ?: 0)

        OpportunityScoringModel.AggressiveV2 -> {
            if (coverageCount == 0) {
                0
            } else {
                val sum = (fundamentals ?: 0) + (technical ?: 0) + (forecast ?: 0)
                val mean = sum.toDouble() / coverageCount.toDouble()
                val bonus = V2_COMPOSITE_COVERAGE_BONUS * (coverageCount - 1)
                (mean + bonus).roundToInt().coerceIn(-V2_COMPOSITE_BOUND, V2_COMPOSITE_BOUND)
            }
        }

        OpportunityScoringModel.AggressiveV3 -> {
            if (coverageCount == 0) {
                0
            } else {
                val sum = (fundamentals ?: 0) + (technical ?: 0) + (forecast ?: 0) + (regime ?: 0)
                val mean = sum.toDouble() / coverageCount.toDouble()
                val bonus = V3_COMPOSITE_COVERAGE_BONUS * (coverageCount - 1)
                val base = (mean + bonus).coerceIn(-V3_COMPOSITE_BOUND.toDouble(), V3_COMPOSITE_BOUND.toDouble())
                val haircut = v3BetaRiskHaircut(betaMillis) * betaHaircutMult.coerceIn(0.0, V3_BETA_HAIRCUT_MULT_MAX)
                (base - haircut).roundToInt().coerceIn(-V3_COMPOSITE_BOUND, V3_COMPOSITE_BOUND)
            }
        }

        /**
         * V4 pays for agreement, not for presence.
         *
         * V3's bonus rises with the number of buckets that reported, whatever they said. A real row
         * showed what that costs: SNDK's market bucket came in at 43, *below* the mean of the other
         * three, and the composite still rose seven points because the bonus went from +10 to +15.
         * A bucket that disagrees with the rest must not raise the score for turning up.
         *
         * So the bonus is scaled by how close the buckets are to each other, and the centre is the
         * median rather than the mean — `present` holds two to four values, which is too few to name
         * an outlier in, and `sum / n` is not a centre this project computes.
         */
        OpportunityScoringModel.AggressiveV4,
        OpportunityScoringModel.AggressiveV5,
        -> {
            var reading = v4AgreementReading(fundamentals, technical, forecast, regime)
            if (reading == null) {
                0
            } else {
                var base = (reading.centre + reading.bonus)
                    .coerceIn(-V4_COMPOSITE_BOUND.toDouble(), V4_COMPOSITE_BOUND.toDouble())
                val haircut = v3BetaRiskHaircut(betaMillis) * betaHaircutMult.coerceIn(0.0, V3_BETA_HAIRCUT_MULT_MAX)
                (base - haircut).roundToInt().coerceIn(-V4_COMPOSITE_BOUND, V4_COMPOSITE_BOUND)
            }
        }
    }

    /**
     * What V4's composite paid for agreement, for a surface that wants to show it.
     *
     * The composite calls this too, so the detail panel cannot report an agreement the score was
     * not built from. Null when no bucket reported, which is the same condition under which the
     * composite scores zero.
     */
    fun v4AgreementReading(
        fundamentals: Int?,
        technical: Int?,
        forecast: Int?,
        regime: Int?,
    ): V4AgreementReading? {
        var present = listOfNotNull(fundamentals, technical, forecast, regime).map { it.toDouble() }
        var centre = medianOf(present) ?: return null
        var spread = meanAbsoluteDeviation(present, centre)
        var agreement = 1.0 - (spread / V4_SPREAD_FULL).coerceIn(0.0, 1.0)
        return V4AgreementReading(
            centre = centre,
            spread = spread,
            agreement = agreement,
            bonus = V4_COMPOSITE_AGREEMENT_BONUS * (present.size - 1) * agreement,
            bucketCount = present.size,
        )
    }

    /**
     * How far the buckets sit from their centre, on average.
     *
     * A mean, and the one place in this file where a mean is the right statistic: the quantity being
     * measured *is* the disagreement, so trimming the dissenting bucket would discard exactly what
     * V4 is trying to price. It is also the quantity `V4_SPREAD_FULL` was fitted to.
     */
    private fun meanAbsoluteDeviation(values: List<Double>, centre: Double): Double =
        values.sumOf { abs(it - centre) } / values.size

    private fun v3BetaRiskHaircut(betaMillis: Int?): Double {
        // Missing beta is not a penalty. High beta (→1.6+) haircuts up to V3_BETA_HAIRCUT_MAX.
        val beta = betaMillis ?: return 0.0
        val ramp = smoothRamp(beta.toDouble(), V3_BETA_LOW_MILLIS, V3_BETA_HIGH_MILLIS)
        return ((ramp + 1.0) / 2.0) * V3_BETA_HAIRCUT_MAX
    }

    fun scoreFundamentals(detail: SymbolDetail): Pair<Int?, List<String>> {
        val fundamentals = detail.fundamentals ?: return null to emptyList()
        var score = 0
        val signals = mutableListOf<String>()

        if ((fundamentals.freeCashFlowDollars ?: 0) > 0) {
            score += 1
            signals += "FCF+"
        }
        if ((fundamentals.operatingCashFlowDollars ?: 0) > 0) {
            score += 1
            signals += "OCF+"
        }
        if ((fundamentals.returnOnEquityBps ?: Int.MIN_VALUE) >= 1_000) {
            score += 1
            signals += "ROE>10"
        }
        val balanceOk = (fundamentals.debtToEquityHundredths?.let { it <= 100 } ?: false) ||
            (
                fundamentals.totalCashDollars != null &&
                    fundamentals.totalDebtDollars != null &&
                    fundamentals.totalCashDollars >= fundamentals.totalDebtDollars
                )
        if (balanceOk) {
            score += 1
            signals += "Balance"
        }
        if ((fundamentals.earningsGrowthBps ?: 0) > 0) {
            score += 1
            signals += "Growth+"
        }
        return score to signals
    }

    fun scoreTechnicals(summary: ChartRangeSummary?): Pair<Int?, List<String>> {
        summary ?: return null to emptyList()
        val latestCloseCents = summary.latestCloseCents ?: return 0 to emptyList()
        var score = 0
        val signals = mutableListOf<String>()

        if (summary.ema20Cents?.let { latestCloseCents > it } == true) {
            score += 1
            signals += ">EMA20"
        }
        if (summary.ema50Cents?.let { latestCloseCents > it } == true) {
            score += 1
            signals += ">EMA50"
        }
        if (summary.ema200Cents?.let { latestCloseCents > it } == true) {
            score += 1
            signals += ">EMA200"
        }
        if (summary.ema20Cents != null && summary.ema50Cents != null && summary.ema20Cents > summary.ema50Cents) {
            score += 1
            signals += "EMA20>50"
        }
        if (
            (summary.macdCents != null && summary.signalCents != null && summary.macdCents > summary.signalCents) ||
            (summary.histogramCents?.let { it > 0 } == true)
        ) {
            score += 1
            signals += "MACD+"
        }
        return score to signals
    }

    fun scoreForecasts(detail: SymbolDetail, analysis: DcfAnalysis?): Pair<Int?, List<String>> {
        var available = false
        var score = 0
        val signals = mutableListOf<String>()

        if (detail.externalStatus == ExternalSignalStatus.Supportive) {
            available = true
            score += 1
            signals += "Supportive"
        }
        if ((detail.analystOpinionCount ?: 0) >= 5) {
            available = true
            score += 1
            signals += "5+Analysts"
        }
        if (detail.recommendationMeanHundredths?.let { it <= 200 } == true) {
            available = true
            score += 1
            signals += "Rec<=2.0"
        }
        detail.weightedExternalSignalFairValueCents?.let { weightedFairValue ->
            available = true
            if ((checkedUpsideBps(detail.marketPriceCents, weightedFairValue) ?: 0) >= 3_000) {
                score += 1
                signals += "Weighted+"
            }
        }
        if (RANKING_INCLUDES_QUANT_ENGINE && analysis != null) {
            available = true
            when (dcfSignal(analysis, detail.marketPriceCents)) {
                DcfSignal.Opportunity -> {
                    score += 1
                    signals += "DCF+"
                }
                DcfSignal.Expensive -> {
                    score -= 1
                    signals += "DCF-"
                }
                DcfSignal.Fair -> Unit
            }
        }
        return if (available) score to signals else null to emptyList()
    }

    private fun aggressiveFundamentalsScore(detail: SymbolDetail): Pair<Int?, List<String>> {
        val fundamentals = detail.fundamentals ?: return null to emptyList()
        var score = 0
        val signals = mutableListOf<String>()

        if ((fundamentals.freeCashFlowDollars ?: 0) > 0) {
            score += 2
            signals += "FCF+2"
        } else {
            score -= 2
            signals += "FCF-2"
        }
        if ((fundamentals.operatingCashFlowDollars ?: 0) > 0) {
            score += 1
            signals += "OCF+1"
        } else {
            score -= 1
            signals += "OCF-1"
        }

        val roeBps = fundamentals.returnOnEquityBps ?: 0
        when {
            roeBps >= 2_000 -> {
                score += 2
                signals += "ROE20+"
            }
            roeBps >= 1_000 -> {
                score += 1
                signals += "ROE10+"
            }
            roeBps < 0 -> {
                score -= 2
                signals += "ROE-"
            }
        }

        val balanceOk = (fundamentals.debtToEquityHundredths?.let { it <= 100 } ?: false) ||
            (
                fundamentals.totalCashDollars != null &&
                    fundamentals.totalDebtDollars != null &&
                    fundamentals.totalCashDollars >= fundamentals.totalDebtDollars
                )
        if (balanceOk) {
            score += 2
            signals += "Balance+2"
        } else {
            score -= 2
            signals += "Balance-2"
        }

        val growthBps = fundamentals.earningsGrowthBps ?: 0
        when {
            growthBps >= 1_000 -> {
                score += 2
                signals += "Growth10+"
            }
            growthBps > 0 -> {
                score += 1
                signals += "Growth+"
            }
            growthBps < 0 -> {
                score -= 2
                signals += "Growth-"
            }
        }

        return score to signals
    }

    private fun aggressiveTechnicalScore(summary: ChartRangeSummary?): Pair<Int?, List<String>> {
        summary ?: return null to emptyList()
        val latestCloseCents = summary.latestCloseCents ?: return 0 to emptyList()
        var score = 0
        val signals = mutableListOf<String>()

        if (summary.ema20Cents?.let { latestCloseCents > it } == true) {
            score += 2
            signals += ">EMA20+2"
        } else if (summary.ema20Cents != null) {
            score -= 2
            signals += "<EMA20-2"
        }
        if (summary.ema50Cents?.let { latestCloseCents > it } == true) {
            score += 1
            signals += ">EMA50+1"
        }
        if (summary.ema200Cents?.let { latestCloseCents > it } == true) {
            score += 1
            signals += ">EMA200+1"
        }
        if (summary.ema20Cents != null && summary.ema50Cents != null && summary.ema20Cents > summary.ema50Cents) {
            score += 1
            signals += "EMA20>50"
        }
        if (
            (summary.macdCents != null && summary.signalCents != null && summary.macdCents > summary.signalCents) ||
            (summary.histogramCents?.let { it > 0 } == true)
        ) {
            score += 1
            signals += "MACD+"
        } else if (summary.histogramCents != null || summary.macdCents != null) {
            score -= 2
            signals += "MACD-"
        }

        return score to signals
    }

    private fun aggressiveForecastScore(detail: SymbolDetail, analysis: DcfAnalysis?): Pair<Int?, List<String>> {
        var available = false
        var score = 0
        val signals = mutableListOf<String>()

        when (detail.externalStatus) {
            ExternalSignalStatus.Supportive -> {
                available = true
                score += 2
                signals += "Support+2"
            }
            ExternalSignalStatus.Divergent -> {
                available = true
                score -= 2
                signals += "Divergent-2"
            }
            ExternalSignalStatus.Stale, ExternalSignalStatus.Missing -> Unit
        }

        val analystCount = detail.analystOpinionCount ?: 0
        when {
            analystCount >= 10 -> {
                available = true
                score += 2
                signals += "Analysts10+"
            }
            analystCount >= 5 -> {
                available = true
                score += 1
                signals += "Analysts5+"
            }
        }

        detail.recommendationMeanHundredths?.let { recommendation ->
            available = true
            when {
                recommendation <= 170 -> {
                    score += 2
                    signals += "Rec1.7+"
                }
                recommendation <= 220 -> {
                    score += 1
                    signals += "Rec2.2+"
                }
                recommendation >= 300 -> {
                    score -= 2
                    signals += "Rec3.0-"
                }
            }
        }

        detail.weightedExternalSignalFairValueCents?.let { weightedFairValue ->
            available = true
            when (val upsideBps = checkedUpsideBps(detail.marketPriceCents, weightedFairValue) ?: 0) {
                in 5_000..Int.MAX_VALUE -> {
                    score += 3
                    signals += "Weighted50+"
                }
                in 3_000..4_999 -> {
                    score += 2
                    signals += "Weighted30+"
                }
                in Int.MIN_VALUE..-1 -> {
                    score -= 2
                    signals += "Weighted-"
                }
            }
        }

        if (RANKING_INCLUDES_QUANT_ENGINE && analysis != null) {
            available = true
            when (val marginBps = dcfMarginOfSafetyBps(analysis, detail.marketPriceCents) ?: 0) {
                in 4_000..Int.MAX_VALUE -> {
                    score += 4
                    signals += "DCF40+"
                }
                in 2_000..3_999 -> {
                    score += 2
                    signals += "DCF20+"
                }
                in Int.MIN_VALUE..-1_001 -> {
                    score -= 3
                    signals += "DCF-"
                }
            }
        }

        return if (available) score to signals else null to emptyList()
    }

    fun dcfSignal(analysis: DcfAnalysis, marketPriceCents: Long): DcfSignal = when {
        (dcfMarginOfSafetyBps(analysis, marketPriceCents) ?: Int.MIN_VALUE) >= DCF_OPPORTUNITY_THRESHOLD_BPS -> DcfSignal.Opportunity
        (dcfMarginOfSafetyBps(analysis, marketPriceCents) ?: Int.MIN_VALUE) < DCF_EXPENSIVE_THRESHOLD_BPS -> DcfSignal.Expensive
        else -> DcfSignal.Fair
    }

    // ----------------------------------------------------------------------------------
    // AggressiveV2 scoring model.
    //
    // Design contract:
    //  * Each bucket sub-score is normalised to [-100, +100] against the bucket's full
    //    evidence budget. Missing data does not become a negative signal, but sparse buckets
    //    also cannot saturate to 100 from a single positive datapoint.
    //  * Each sub-signal returns a smooth ramp in [-1, +1] between an explicit lower and
    //    upper bound, so there are no cliff transitions at thresholds (e.g. FCF $0).
    //  * Correlated signals are collapsed into one weighted contribution to avoid the
    //    triple-counting that V1 had with EMAs and the analyst-rating + weighted-upside
    //    + DCF margin trio.
    //  * Composite uses the model-aware coverage-weighted mean (see compositeScoreFor),
    //    so a symbol with one rich bucket cannot leapfrog a symbol with three sound
    //    buckets purely on raw bucket-scale headroom.
    // ----------------------------------------------------------------------------------

    internal fun aggressiveV2FundamentalsScore(detail: SymbolDetail): BucketEvidence {
        val fundamentals = detail.fundamentals ?: return BucketEvidence.absent()
        val acc = EvidenceAccumulator(V2_FUNDAMENTALS_FULL_WEIGHT)

        // Free cash flow yield (FCF / market cap). Falls back to OCF positivity only when
        // FCF is missing, so cash-flow gets one vote per symbol (no FCF/OCF double count).
        val fcfDollars = fundamentals.freeCashFlowDollars
        val marketCapDollars = fundamentals.marketCapDollars
        when {
            fcfDollars != null && marketCapDollars != null && marketCapDollars > 0L -> {
                val yieldFraction = fcfDollars.toDouble() / marketCapDollars.toDouble()
                acc.add(V2_FUND_FCF_WEIGHT, smoothRamp(yieldFraction, V2_FUND_FCF_YIELD_LOWER, V2_FUND_FCF_YIELD_UPPER), "FCFy")
            }
            fcfDollars != null -> {
                // No market cap -> grade FCF on sign alone.
                acc.add(V2_FUND_FCF_WEIGHT, if (fcfDollars > 0L) 1.0 else -1.0, "FCF")
            }
            else -> {
                val ocfDollars = fundamentals.operatingCashFlowDollars
                if (ocfDollars != null) {
                    acc.add(V2_FUND_OCF_FALLBACK_WEIGHT, if (ocfDollars > 0L) 1.0 else -1.0, "OCF")
                }
            }
        }

        fundamentals.returnOnEquityBps?.let { roeBps ->
            acc.add(V2_FUND_ROE_WEIGHT, smoothRamp(roeBps.toDouble(), V2_FUND_ROE_LOWER_BPS, V2_FUND_ROE_UPPER_BPS), "ROE")
        }

        fundamentals.earningsGrowthBps?.let { growthBps ->
            acc.add(
                V2_FUND_GROWTH_WEIGHT,
                smoothRamp(growthBps.toDouble(), V2_FUND_GROWTH_LOWER_BPS, V2_FUND_GROWTH_UPPER_BPS),
                "Growth",
                growthBps,
            )
        }

        // Balance: prefer D/E, fall back to cash >= debt.
        val deHundredths = fundamentals.debtToEquityHundredths
        if (deHundredths != null) {
            // Lower D/E is better -> negate the ramp.
            acc.add(V2_FUND_BALANCE_WEIGHT, -smoothRamp(deHundredths.toDouble(), V2_FUND_BALANCE_DE_LOW, V2_FUND_BALANCE_DE_HIGH), "D/E")
        } else {
            val cash = fundamentals.totalCashDollars
            val debt = fundamentals.totalDebtDollars
            if (cash != null && debt != null) {
                acc.add(V2_FUND_BALANCE_WEIGHT, if (cash >= debt) 1.0 else -0.5, "Bal")
            }
        }

        // Forward P/E (cheaper is better). Skip when negative or zero (loss-making, already
        // captured upstream by FCF / ROE and would otherwise mislead the ramp).
        fundamentals.forwardPeHundredths?.takeIf { it > 0 }?.let { peHundredths ->
            acc.add(V2_FUND_PE_WEIGHT, -smoothRamp(peHundredths.toDouble(), V2_FUND_PE_LOW, V2_FUND_PE_HIGH), "FwdPE")
        }

        return acc.toEvidence()
    }

    internal fun aggressiveV2TechnicalScore(summary: ChartRangeSummary?): BucketEvidence {
        summary ?: return BucketEvidence.absent()
        val latestCloseCents = summary.latestCloseCents ?: return BucketEvidence.absent()
        val acc = EvidenceAccumulator(V2_TECHNICALS_FULL_WEIGHT)

        // Trend stack: three correlated EMA deltas, but each carries a sub-weight that
        // sums to 60. A clean uptrend can score the full +60, but partial alignment
        // contributes proportionally instead of ticking three independent boolean checkboxes.
        summary.ema20Cents?.takeIf { it > 0 }?.let { ema20 ->
            val delta = (latestCloseCents - ema20).toDouble() / ema20.toDouble()
            acc.add(V2_TECH_TREND_PRICE_20_WEIGHT, smoothRamp(delta, -V2_TECH_TREND_DELTA_BOUND, V2_TECH_TREND_DELTA_BOUND), "Px/20")
        }
        if (summary.ema20Cents != null && summary.ema50Cents != null && summary.ema50Cents > 0) {
            val delta = (summary.ema20Cents - summary.ema50Cents).toDouble() / summary.ema50Cents.toDouble()
            acc.add(V2_TECH_TREND_20_50_WEIGHT, smoothRamp(delta, -V2_TECH_TREND_DELTA_BOUND, V2_TECH_TREND_DELTA_BOUND), "20/50")
        }
        if (summary.ema50Cents != null && summary.ema200Cents != null && summary.ema200Cents > 0) {
            val delta = (summary.ema50Cents - summary.ema200Cents).toDouble() / summary.ema200Cents.toDouble()
            acc.add(V2_TECH_TREND_50_200_WEIGHT, smoothRamp(delta, -V2_TECH_TREND_DELTA_BOUND, V2_TECH_TREND_DELTA_BOUND), "50/200")
        }

        // Histogram momentum (continuous, magnitude-aware).
        if (summary.histogramCents != null && latestCloseCents > 0) {
            val ratio = summary.histogramCents.toDouble() / latestCloseCents.toDouble()
            acc.add(V2_TECH_HISTOGRAM_WEIGHT, smoothRamp(ratio, -V2_TECH_HISTOGRAM_BOUND, V2_TECH_HISTOGRAM_BOUND), "Hist")
        }

        // MACD direction confirmation: only consumed when both lines are present, otherwise
        // histogram already covered the momentum side.
        if (summary.macdCents != null && summary.signalCents != null) {
            val direction = when {
                summary.macdCents > summary.signalCents -> 1.0
                summary.macdCents < summary.signalCents -> -1.0
                else -> 0.0
            }
            acc.add(V2_TECH_MACD_DIRECTION_WEIGHT, direction, "MACD")
        }

        return acc.toEvidence()
    }

    internal fun aggressiveV2ForecastScore(detail: SymbolDetail, analysis: DcfAnalysis?): BucketEvidence {
        val acc = EvidenceAccumulator(V2_FORECAST_FULL_WEIGHT)
        val sufficiencySignals = mutableListOf<String>()
        var reliableEvidenceWeight = 0.0
        var hasValuationAnchor = false

        val targetFairValue = preferredForecastFairValueCents(detail)
        val targetCount = targetAnalystCount(detail)
        val recommendationCount = recommendationAnalystCount(detail)
        val broadestAnalystCount = listOfNotNull(targetCount, recommendationCount).maxOrNull()
        val externalFreshness = freshnessMultiplier(detail)
        val externalStatusReliability = externalStatusReliability(detail.externalStatus)

        val valuationInputs = mutableListOf<WeightedForecastRamp>()
        targetFairValue?.let { fairValue ->
            val targetUpsideBps = checkedUpsideBps(detail.marketPriceCents, fairValue)
            val targetReliability = analystCoverageReliability(targetCount) * externalFreshness * externalStatusReliability
            when {
                targetUpsideBps == null -> Unit
                !hasSufficientAnalystCoverage(targetCount) -> {
                    sufficiencySignals += if (targetCount == null) "Cov?" else "Cov<${V2_FORECAST_MIN_ANALYST_OPINIONS}"
                }
                targetReliability > 0.0 -> {
                    valuationInputs += WeightedForecastRamp(
                        ramp = smoothRamp(targetUpsideBps.toDouble(), V2_FORECAST_UPSIDE_LOWER_BPS, V2_FORECAST_UPSIDE_UPPER_BPS),
                        reliability = targetReliability,
                    )
                }
            }
        }

        if (RANKING_INCLUDES_QUANT_ENGINE) {
            analysis?.let { dcf ->
                dcfMarginOfSafetyBps(dcf, detail.marketPriceCents)?.let { marginBps ->
                    valuationInputs += WeightedForecastRamp(
                        ramp = smoothRamp(marginBps.toDouble(), V2_FORECAST_UPSIDE_LOWER_BPS, V2_FORECAST_UPSIDE_UPPER_BPS),
                        reliability = V2_FORECAST_DCF_RELIABILITY,
                    )
                }
            }
        }

        if (valuationInputs.isNotEmpty()) {
            val reliabilitySum = valuationInputs.sumOf { it.reliability }
            if (reliabilitySum > 0.0) {
                val blendedRamp = valuationInputs.sumOf { it.ramp * it.reliability } / reliabilitySum
                val weight = V2_FORECAST_VALUATION_WEIGHT * reliabilitySum.coerceAtMost(1.0)
                acc.add(weight, blendedRamp, "Val")
                reliableEvidenceWeight += weight
                hasValuationAnchor = true
            }
        }

        detail.recommendationMeanHundredths?.let { rec ->
            val recReliability = analystCoverageReliability(recommendationCount) * externalFreshness * externalStatusReliability
            if (!hasSufficientAnalystCoverage(recommendationCount)) {
                sufficiencySignals += if (recommendationCount == null) "RecCov?" else "RecCov<${V2_FORECAST_MIN_ANALYST_OPINIONS}"
            } else if (recReliability > 0.0) {
                val weight = V2_FORECAST_REC_WEIGHT * recReliability
                acc.add(weight, -smoothRamp(rec.toDouble(), V2_FORECAST_REC_LOW_HUNDREDTHS, V2_FORECAST_REC_HIGH_HUNDREDTHS), "Rec")
                reliableEvidenceWeight += weight
            }
        }

        broadestAnalystCount?.let { count ->
            acc.add(V2_FORECAST_BREADTH_WEIGHT, analystBreadthRamp(count), "Cov")
            reliableEvidenceWeight += V2_FORECAST_BREADTH_WEIGHT * analystCoverageReliability(count)
        }

        val targetReliabilityWithoutFreshness = analystCoverageReliability(targetCount) * externalStatusReliability
        val low = detail.externalSignalLowFairValueCents
        val high = detail.externalSignalHighFairValueCents
        val centre = targetFairValue
        if (low != null && high != null && centre != null && centre > 0L && high > low && targetReliabilityWithoutFreshness > 0.0) {
            val spreadFraction = (high - low).toDouble() / centre.toDouble()
            val weight = V2_FORECAST_UNCERTAINTY_WEIGHT * targetReliabilityWithoutFreshness
            acc.add(weight, -smoothRamp(spreadFraction, 0.0, V2_FORECAST_UNCERTAINTY_BOUND), "Unc")
            reliableEvidenceWeight += weight * externalFreshness
        }

        if (targetFairValue != null && hasSufficientAnalystCoverage(targetCount)) {
            val weight = V2_FORECAST_FRESHNESS_WEIGHT * analystCoverageReliability(targetCount)
            acc.add(weight, freshnessRamp(externalFreshness), "Fresh")
            reliableEvidenceWeight += weight * externalFreshness
        }

        if (!hasValuationAnchor || reliableEvidenceWeight < V2_FORECAST_MIN_RELIABLE_EVIDENCE_WEIGHT) {
            return acc.toEvidence(extraSignals = sufficiencySignals, scoreOverride = null)
        }

        var raw = acc.normalizedScore() ?: return acc.toEvidence(extraSignals = sufficiencySignals, scoreOverride = null)
        return acc.toEvidence(extraSignals = sufficiencySignals, scoreOverride = raw.coerceIn(-100, 100))
    }

    // ----------------------------------------------------------------------------------
    // AggressiveV3 scoring model.
    //
    // Design contract (extends V2):
    //  * Same EvidenceAccumulator + smoothRamp math and [-100, +100] bucket normalization.
    //  * Fundamentals: multi-multiple valuation blend (FwdPE / EV/EBITDA / P/B) + optional
    //    FCF/OCF cash quality; still one cash-flow vote (FCF yield or OCF fallback).
    //  * Technicals: V2-style trend/MACD budget plus RSI regime (mid-bullish preferred,
    //    overbought chase discouraged) and optional volume confirmation.
    //  * Forecast: V2 reliability/freshness gates plus recommendation distribution skew
    //    and DCF bear–bull scenario width as uncertainty.
    //  * Composite: coverage-weighted mean + bonus, then beta risk haircut (missing beta = 0).
    // ----------------------------------------------------------------------------------

    internal fun aggressiveV3FundamentalsScore(detail: SymbolDetail): BucketEvidence {
        val fundamentals = detail.fundamentals ?: return BucketEvidence.absent()
        val acc = EvidenceAccumulator(V3_FUNDAMENTALS_FULL_WEIGHT)
        applyClassExemptions(acc, fundamentals)

        var yieldVoted = addCashFlowVote(acc, detail)
        addReturnOnEquity(acc, fundamentals, sectorReturnOnEquityBps = null)
        addEarningsGrowth(acc, fundamentals)
        addBalanceSheet(acc, fundamentals)

        // Multi-multiple valuation panel: blend available positive multiples so cheaper → +1.
        // Weight scales with how many multiples are present (1/3 … 1) so a single PE cannot
        // saturate the full valuation budget.
        val valuationRamps = mutableListOf<Double>()
        var valuationComparisons = mutableListOf<ScoreFactorComparison>()
        fundamentals.forwardPeHundredths?.takeIf { it > 0 }?.let { pe ->
            valuationRamps += -smoothRamp(pe.toDouble(), V3_FUND_PE_LOW, V3_FUND_PE_HIGH)
            valuationComparisons += absoluteMultipleComparison(pe, V3_FUND_PE_LOW, V3_FUND_PE_HIGH, "P/E")
        }
        fundamentals.enterpriseToEbitdaHundredths?.takeIf { it > 0 }?.let { evEbitda ->
            valuationRamps += -smoothRamp(evEbitda.toDouble(), V3_FUND_EV_EBITDA_LOW, V3_FUND_EV_EBITDA_HIGH)
            valuationComparisons += absoluteMultipleComparison(
                evEbitda,
                V3_FUND_EV_EBITDA_LOW,
                V3_FUND_EV_EBITDA_HIGH,
                "EV/EBITDA",
            )
        }
        fundamentals.priceToBookHundredths?.takeIf { it > 0 }?.let { pb ->
            valuationRamps += -smoothRamp(pb.toDouble(), V3_FUND_PB_LOW, V3_FUND_PB_HIGH)
            valuationComparisons += absoluteMultipleComparison(pb, V3_FUND_PB_LOW, V3_FUND_PB_HIGH, "P/B")
        }
        if (valuationRamps.isNotEmpty()) {
            val blended = valuationRamps.sum() / valuationRamps.size.toDouble()
            val coverageFraction = valuationRamps.size.toDouble() / VALUATION_PANEL_MULTIPLE_COUNT
            acc.add(V3_FUND_VALUATION_WEIGHT * coverageFraction, blended, "Mult", comparisons = valuationComparisons)
        }

        addCashConversion(acc, fundamentals, yieldVoted = yieldVoted)
        acc.flagCoverageGap()

        return acc.toEvidence()
    }

    /**
     * V4's fundamentals bucket: V3's terms, with the valuation panel and return on equity read
     * against the symbol's own sector when that sector has earned a benchmark.
     *
     * The defect this fixes is that V3 ranks a utility and a chip maker on one P/E band, so it
     * ranks industries before it ranks companies. A sector below the five-member floor has no
     * benchmark and the row falls back to V3's absolute band — visibly, via
     * [SECTOR_ADJUSTED_MARKER], because a list that scores two rows by two rules and says which is
     * honest, and one that stays quiet about it is not.
     */
    internal fun aggressiveV4FundamentalsScore(
        detail: SymbolDetail,
        sectorBenchmarks: SectorBenchmarks?,
        timeseries: FundamentalTimeseries? = null,
    ): BucketEvidence {
        var fundamentals = detail.fundamentals ?: return BucketEvidence.absent()
        var acc = EvidenceAccumulator(V4_FUNDAMENTALS_FULL_WEIGHT)
        applyClassExemptions(acc, fundamentals, leverageVotes = ::v4LeverageVotes)

        var yieldVoted = addCashFlowVote(acc, detail, timeseries, sectorBenchmarks)
        addReturnOnEquity(acc, fundamentals, sectorBenchmarks?.returnOnEquityBps)
        addV4Growth(acc, fundamentals, timeseries)
        addV4BalanceSheet(acc, fundamentals, sectorBenchmarks?.netDebtToEbitdaHundredths)
        addSectorRelativeMultiples(acc, fundamentals, sectorBenchmarks)
        addCashConversion(acc, fundamentals, yieldVoted = yieldVoted)
        addShareCountChange(acc, timeseries)
        addCyclePeak(acc, fundamentals, timeseries)
        acc.flagCoverageGap()

        return acc.toEvidence()
    }

    /**
     * V5's fundamentals bucket: V4's terms with its two documented defects refused.
     *
     * Growth runs [addV5Growth] — same trend/pulse split, but a pulse whose latest filed annual
     * year is a loss is refused rather than scored against stale profit-year pairs. The balance
     * sheet runs [addV5BalanceSheet] — the industrial leverage vote only fires for a row the
     * class policy actually classifies as operating; an unclassified row skips it and flags why.
     * Every other term is V4's own, which the parity test pins.
     */
    internal fun aggressiveV5FundamentalsScore(
        detail: SymbolDetail,
        sectorBenchmarks: SectorBenchmarks?,
        timeseries: FundamentalTimeseries? = null,
    ): BucketEvidence {
        var fundamentals = detail.fundamentals ?: return BucketEvidence.absent()
        var acc = EvidenceAccumulator(V4_FUNDAMENTALS_FULL_WEIGHT)
        applyClassExemptions(acc, fundamentals, leverageVotes = ::v5LeverageVotes)

        var yieldVoted = addCashFlowVote(acc, detail, timeseries, sectorBenchmarks)
        addReturnOnEquity(acc, fundamentals, sectorBenchmarks?.returnOnEquityBps)
        addV5Growth(acc, fundamentals, timeseries)
        addV5BalanceSheet(
            acc,
            fundamentals,
            sectorBenchmarks?.netDebtToEbitdaHundredths,
        )
        addSectorRelativeMultiples(acc, fundamentals, sectorBenchmarks)
        addCashConversion(acc, fundamentals, yieldVoted = yieldVoted)
        addShareCountChange(acc, timeseries)
        addCyclePeak(acc, fundamentals, timeseries)
        acc.flagCoverageGap()

        return acc.toEvidence()
    }

    /**
     * V4's growth split plus one refusal.
     *
     * `pulseGrowthBps` already refuses tiny EPS and rates foreign to the annual net-income
     * series — but that series loses its loss years to [positiveLevelTransitions], so a
     * perpetual loss-maker can clear the foreign check against nothing or against stale profit
     * pairs. V5 asks the extra question first: does the latest filed year itself show profit?
     * A no refuses the pulse and names the reason in the signals.
     */
    private fun addV5Growth(
        acc: EvidenceAccumulator,
        fundamentals: FundamentalSnapshot,
        timeseries: FundamentalTimeseries?,
    ) {
        var trendBps = trendGrowthBps(timeseries)
        var pulseBps = pulseGrowthBps(fundamentals, timeseries)
        if (pulseBps != null && !latestAnnualNetIncomePositive(timeseries)) {
            pulseBps = null
            acc.flag(V5_PULSE_REFUSED_LABEL)
        }
        var trendRamp = trendBps?.let { smoothRamp(it.toDouble(), V4_FUND_GROWTH_LOWER_BPS, V4_FUND_GROWTH_UPPER_BPS) }
        var pulseRamp = pulseBps?.let { smoothRamp(it.toDouble(), V4_FUND_GROWTH_LOWER_BPS, V4_FUND_GROWTH_UPPER_BPS) }
        if (trendRamp != null && trendBps != null) {
            acc.add(
                V4_FUND_TREND_WEIGHT,
                trendRamp,
                "Trend",
                trendBps,
                comparisons = listOf(growthBandComparison(trendBps)),
            )
        }
        if (pulseRamp != null && pulseBps != null) {
            acc.add(
                V4_FUND_PULSE_WEIGHT,
                pulseRamp,
                "Pulse",
                pulseBps,
                comparisons = listOf(growthBandComparison(pulseBps)),
            )
        }
        if (trendBps != null && pulseBps != null && abs(trendBps - pulseBps) >= V4_GROWTH_CONFLICT_BPS) {
            acc.flag("Pulse≠Trend")
        }
        if (earningsContamination(timeseries).latestYearContaminated) {
            acc.flag(EARNINGS_CHARGE_LABEL)
        }
    }

    /** The latest filed annual year must itself be profitable to corroborate a quarter rate. */
    private fun latestAnnualNetIncomePositive(timeseries: FundamentalTimeseries?): Boolean {
        var latest = timeseries?.netIncome?.maxByOrNull { it.asOfDate } ?: return false
        return latest.value.isFinite() && latest.value > 0.0
    }

    /**
     * V5's leverage gate: fail closed on an unknown class.
     *
     * Financial services skip exactly as V4 does — deposits and float are the wrong input. An
     * operating row takes the same three-input vote. Anything the class policy cannot place gets
     * no vote at all plus a flag, because industrial bands applied to an unknown balance sheet
     * are a guess wearing a number.
     */
    private fun addV5BalanceSheet(
        acc: EvidenceAccumulator,
        fundamentals: FundamentalSnapshot,
        sectorNetDebtToEbitdaHundredths: Int?,
    ) {
        var businessClass = FinancialClassPolicy.classify(fundamentals)
        when {
            v5LeverageVotes(businessClass) ->
                addIndustrialLeverageVote(acc, fundamentals, sectorNetDebtToEbitdaHundredths)
            businessClass == BusinessClass.FinancialServices -> return
            else -> acc.flag(V5_CLASS_UNKNOWN_LABEL)
        }
    }

    /**
     * Marks a name whose latest earnings sit at the top of the only history there is, in an
     * industry the beta policy already calls through-cycle.
     *
     * It subtracts and never adds. A margin at the bottom of its window is not evidence of a
     * trough — over five points it is indistinguishable from a business getting worse — and paying
     * a name for that would be the same extrapolation error in the other direction.
     *
     * See [cyclePeakReading] for why this is a penalty and not a weighted term.
     */
    private fun addCyclePeak(
        acc: EvidenceAccumulator,
        fundamentals: FundamentalSnapshot,
        timeseries: FundamentalTimeseries?,
    ) {
        var reading = cyclePeakReading(fundamentals, timeseries, V4_TREND_MAX_YEARS)
        if (reading.penaltyPoints <= 0) return
        acc.penalize(reading.penaltyPoints, CYCLE_PEAK_LABEL, reading.marginPercentileBps)
    }

    /**
     * What the share count did over the most recent annual pair.
     *
     * The series is downloaded by both providers and, until now, only its last point was ever read
     * — the count itself, never the change. A count that shrinks is the company buying its own
     * shares and it scores positive; a count that grows is the shareholder being diluted and it
     * scores negative.
     *
     * A single point is not a change and contributes nothing rather than zero: the pair is what
     * carries the fact, and one point cannot say which way it moved. Both providers sort ascending
     * by `asOfDate`, so the last two entries are the most recent pair — and the pair must be two
     * adjacent fiscal years. A series with a missing year would otherwise print a multi-year move
     * as one annual rate, and a stale pair would report a change that ended years ago as if it
     * were current; the adjacency gate refuses both rather than misdating them.
     */
    private fun addShareCountChange(acc: EvidenceAccumulator, timeseries: FundamentalTimeseries?) {
        var series = timeseries?.dilutedAverageShares
            ?.filter { it.value.isFinite() && it.value > 0.0 }
            ?.sortedBy { it.asOfDate }
            ?: return
        if (series.size < 2) return
        var previous = series[series.size - 2]
        var latest = series[series.size - 1]
        if (!areConsecutiveFiscalYears(previous, latest)) return
        var changeBps = (latest.value - previous.value) / previous.value * BASIS_POINTS_PER_UNIT
        var changeBpsInt = changeBps.roundToInt()
        acc.add(
            V4_FUND_SHARE_COUNT_WEIGHT,
            -smoothRamp(changeBps, V4_FUND_SHARE_COUNT_SHRINK_BPS, V4_FUND_SHARE_COUNT_DILUTE_BPS),
            "Shares",
            changeBpsInt,
            comparisons = listOf(
                absoluteBandComparison(
                    changeBpsInt,
                    ScoreFactorValueKind.Percent,
                    V4_FUND_SHARE_COUNT_SHRINK_BPS,
                    V4_FUND_SHARE_COUNT_DILUTE_BPS,
                ),
            ),
        )
    }

    /**
     * The valuation panel, each multiple scored against its sector centre where there is one.
     *
     * Two things differ from V3's panel beyond the sector, and both are deliberate. The blend is a
     * median, not `sum / n`: three ramps of which one is pinned at ±1 by a saturating multiple
     * should not drag the panel, and a mean lets it. And the panel carries one label for three
     * metrics, marked when the sector supplied **at least one** centre — a sector can clear the
     * five-member floor on P/E and miss it on EV/EBITDA, and the marker's claim is "the sector was
     * read here", not "every metric was".
     */
    private fun addSectorRelativeMultiples(
        acc: EvidenceAccumulator,
        fundamentals: FundamentalSnapshot,
        sectorBenchmarks: SectorBenchmarks?,
    ) {
        var ramps = mutableListOf<Double>()
        var comparisons = mutableListOf<ScoreFactorComparison>()
        var sectorAdjusted = false
        fun scoreMultiple(
            observed: Int?,
            sectorCentre: Int?,
            absoluteLow: Double,
            absoluteHigh: Double,
            metric: String,
        ) {
            var value = observed?.takeIf { it > 0 } ?: return
            var centre = sectorCentre?.takeIf { it > 0 }
            if (centre != null) sectorAdjusted = true
            var low = centre?.let { it * V4_FUND_SECTOR_CHEAP_MULT } ?: absoluteLow
            var high = centre?.let { it * V4_FUND_SECTOR_RICH_MULT } ?: absoluteHigh
            ramps += -smoothRamp(value.toDouble(), low, high)
            comparisons += if (centre != null) {
                ScoreFactorComparison(value, ScoreFactorValueKind.Multiple, metric, centre)
            } else {
                absoluteMultipleComparison(value, absoluteLow, absoluteHigh, metric)
            }
        }
        scoreMultiple(
            fundamentals.forwardPeHundredths,
            sectorBenchmarks?.forwardPeHundredths,
            V3_FUND_PE_LOW,
            V3_FUND_PE_HIGH,
            "P/E",
        )
        scoreMultiple(
            fundamentals.enterpriseToEbitdaHundredths,
            sectorBenchmarks?.enterpriseToEbitdaHundredths,
            V3_FUND_EV_EBITDA_LOW,
            V3_FUND_EV_EBITDA_HIGH,
            "EV/EBITDA",
        )
        scoreMultiple(
            fundamentals.priceToBookHundredths,
            sectorBenchmarks?.priceToBookHundredths,
            V3_FUND_PB_LOW,
            V3_FUND_PB_HIGH,
            "P/B",
        )
        var blended = medianOf(ramps) ?: return
        var coverageFraction = ramps.size.toDouble() / VALUATION_PANEL_MULTIPLE_COUNT
        var label = if (sectorAdjusted) "Mult$SECTOR_ADJUSTED_MARKER" else "Mult"
        acc.add(V3_FUND_VALUATION_WEIGHT * coverageFraction, blended, label, comparisons = comparisons)
    }

    private fun absoluteMultipleComparison(
        observed: Int,
        absoluteLow: Double,
        absoluteHigh: Double,
        metric: String,
    ) = absoluteBandComparison(
        observed,
        ScoreFactorValueKind.Multiple,
        absoluteLow,
        absoluteHigh,
        metric = metric,
    )

    private fun absoluteBandComparison(
        observed: Int,
        kind: ScoreFactorValueKind,
        absoluteLow: Double,
        absoluteHigh: Double,
        why: String? = null,
        metric: String? = null,
    ) = ScoreFactorComparison(
        observed = observed,
        kind = kind,
        metric = metric,
        referenceLow = absoluteLow.roundToInt(),
        referenceHigh = absoluteHigh.roundToInt(),
        why = why,
    )

    // ----------------------------------------------------------------------------------
    // The fundamentals terms V3 and V4 share.
    //
    // Moved out of V3's body unchanged, so that V4 reuses them instead of holding a second copy
    // that can drift. V4's own terms are deliberately absent from this block: the multiple panel
    // centres a different way and reads the sector, and the share-count change does not exist in
    // V3 at all. Those are the terms that differ, so those are the terms that are written twice.
    // ----------------------------------------------------------------------------------

    /**
     * One cash-flow vote: FCF as a yield against firm size.
     *
     * Size is equity cap. The numerator is the robust centre of recent annual FCF when the series
     * can speak, else the TTM print. A sector-adjusted vote uses TTM on both sides. Financial
     * services, unclassified, and not-eligible names do not take this vote.
     */
    private fun addCashFlowVote(
        acc: EvidenceAccumulator,
        detail: SymbolDetail,
        timeseries: FundamentalTimeseries? = null,
        sectorBenchmarks: SectorBenchmarks? = null,
    ): Boolean {
        var fundamentals = detail.fundamentals ?: return false
        var refused = industrialFcfRefusalLabel(fundamentals)
        if (refused != null) {
            var seriesFcf = timeseries?.freeCashFlow?.any { row -> row.value.isFinite() } == true
            var seriesOcf = timeseries?.operatingCashFlow?.any { row -> row.value.isFinite() } == true
            if (fundamentals.freeCashFlowDollars != null ||
                fundamentals.operatingCashFlowDollars != null ||
                seriesFcf ||
                seriesOcf
            ) {
                acc.flag(refused)
            }
            return false
        }
        var size = sizeForCashVote(detail, timeseries)
        var sectorYieldBps = sectorBenchmarks?.fcfYieldBps
        var fcfDollars = if (sectorYieldBps != null) {
            fundamentals.freeCashFlowDollars
        } else {
            fcfDollarsForYield(timeseries, fundamentals.freeCashFlowDollars)
        }
        when {
            fcfDollars != null && size != null -> {
                addFcfYield(acc, fcfDollars, size, sectorYieldBps)
                return fcfDollars == fundamentals.freeCashFlowDollars
            }
            fcfDollars != null -> return false
            else -> {
                var ocfDollars = fundamentals.operatingCashFlowDollars
                if (ocfDollars != null && size != null) {
                    addCashYield(
                        acc,
                        ocfDollars,
                        size,
                        V3_FUND_OCF_FALLBACK_WEIGHT,
                        "OCF",
                        sectorYieldBps = null,
                        yieldLower = V3_FUND_OCF_YIELD_LOWER,
                        yieldUpper = V3_FUND_OCF_YIELD_UPPER,
                    )
                    acc.flag(OCF_BAND_UNMEASURED_LABEL)
                }
            }
        }
        return false
    }

    private fun industrialFcfRefusalLabel(fundamentals: FundamentalSnapshot): String? =
        when (FinancialClassPolicy.classify(fundamentals)) {
            BusinessClass.FinancialServices -> FCF_REFUSED_FINANCIAL_LABEL
            BusinessClass.Unclassified -> FCF_REFUSED_UNKNOWN_LABEL
            BusinessClass.NotEligible -> FCF_REFUSED_INELIGIBLE_LABEL
            BusinessClass.OperatingNonFinancial -> null
        }

    /**
     * [leverageVotes] is null for a model that carries no leverage term, and V3 is that model.
     *
     * It used to default to `{ false }`, which reads as "no class votes leverage" and took
     * [V4_FUND_LEVERAGE_WEIGHT] off V3's budget on every call. V3 still voted its balance sheet at
     * that weight, so the term was scored out of a budget it had been removed from: every V3 term
     * paid 100/84 of its share and the level rose across the board.
     */
    private fun applyClassExemptions(
        acc: EvidenceAccumulator,
        fundamentals: FundamentalSnapshot,
        leverageVotes: ((BusinessClass) -> Boolean)? = null,
    ) {
        var businessClass = FinancialClassPolicy.classify(fundamentals)
        if (businessClass != BusinessClass.OperatingNonFinancial) {
            acc.exemptClassTerm(V3_FUND_FCF_WEIGHT)
            acc.exemptClassTerm(V3_FUND_CASH_QUALITY_WEIGHT)
        }
        if (leverageVotes != null && !leverageVotes(businessClass)) {
            acc.exemptClassTerm(V4_FUND_LEVERAGE_WEIGHT)
        }
    }

    /** The one place that decides which classes vote the leverage term, for V4's balance sheet. */
    private fun v4LeverageVotes(businessClass: BusinessClass): Boolean =
        businessClass != BusinessClass.FinancialServices

    /** The one place that decides which classes vote the leverage term, for V5's balance sheet. */
    private fun v5LeverageVotes(businessClass: BusinessClass): Boolean =
        businessClass == BusinessClass.OperatingNonFinancial

    private fun addFcfYield(
        acc: EvidenceAccumulator,
        fcfDollars: Long,
        size: CashSize,
        sectorYieldBps: Int?,
    ) {
        addCashYield(
            acc,
            fcfDollars,
            size,
            V3_FUND_FCF_WEIGHT,
            if (sectorYieldBps != null) "FCFy$SECTOR_ADJUSTED_MARKER" else "FCFy",
            sectorYieldBps,
            V3_FUND_FCF_YIELD_LOWER,
            V3_FUND_FCF_YIELD_UPPER,
        )
    }

    private fun addCashYield(
        acc: EvidenceAccumulator,
        cashDollars: Long,
        size: CashSize,
        weight: Double,
        label: String,
        sectorYieldBps: Int?,
        yieldLower: Double,
        yieldUpper: Double,
    ) {
        if (size.dollars <= 0L) return
        var yieldBps = cashYieldBps(cashDollars, size.dollars) ?: return
        var yieldFraction = yieldBps / BASIS_POINTS_PER_UNIT
        var why = cashYieldWhy(label, size.kind)
        var ramp = if (sectorYieldBps != null) {
            var centre = sectorYieldBps.toDouble()
            smoothRamp(
                yieldBps.toDouble(),
                centre + V4_FUND_SECTOR_FCF_YIELD_LOWER_OFFSET_BPS,
                centre + V4_FUND_SECTOR_FCF_YIELD_UPPER_OFFSET_BPS,
            )
        } else {
            smoothRamp(yieldFraction, yieldLower, yieldUpper)
        }
        var comparison = if (sectorYieldBps != null) {
            ScoreFactorComparison(yieldBps, ScoreFactorValueKind.Percent, reference = sectorYieldBps, why = why)
        } else {
            absoluteBandComparison(
                yieldBps,
                ScoreFactorValueKind.Percent,
                yieldLower * BASIS_POINTS_PER_UNIT,
                yieldUpper * BASIS_POINTS_PER_UNIT,
                why = why,
            )
        }
        acc.add(weight, ramp, label, yieldBps, comparisons = listOf(comparison))
    }

    private fun cashYieldWhy(cashLabel: String, kind: CashSizeKind): String {
        var numerator = if (cashLabel.startsWith("OCF")) "OCF" else "FCF"
        return when (kind) {
            CashSizeKind.MarketCap -> "$numerator / market cap"
            CashSizeKind.PriceTimesShares -> "$numerator / price × shares"
        }
    }

    private fun fcfDollarsForYield(timeseries: FundamentalTimeseries?, trailing: Long?): Long? {
        var series = timeseries?.freeCashFlow
            ?.filter { row -> row.value.isFinite() }
            ?.sortedBy { row -> row.asOfDate }
            ?.takeLast(V4_TREND_MAX_YEARS)
            ?.map { row -> row.value }
            .orEmpty()
        if (series.size >= 5) {
            robustCentre(series)?.let { return it.roundToLong() }
            return medianOf(series)!!.roundToLong()
        }
        if (series.size >= 2) return medianOf(series)?.roundToLong()
        if (series.size == 1) return series[0].roundToLong()
        return trailing
    }

    private fun sizeForCashVote(detail: SymbolDetail, timeseries: FundamentalTimeseries?): CashSize? {
        var fundamentals = detail.fundamentals ?: return null
        if (fundamentals.sharesOutstanding != null && fundamentals.sharesOutstanding > 0L) {
            return cashFlowSizeForYield(detail)
        }
        var fromSeries = timeseries?.dilutedAverageShares
            ?.filter { row -> row.value.isFinite() && row.value > 0.0 }
            ?.maxByOrNull { row -> row.asOfDate }
            ?.value
            ?.roundToLong()
            ?.takeIf { it > 0L }
        return cashFlowSizeForYield(detail, sharesOverride = fromSeries)
    }

    /**
     * Return on equity, against the sector's level when there is one and against an absolute band
     * when there is not.
     *
     * The band is **additive**, and that is not an inconsistency with the multiple panel's
     * multiplicative ramp — it is the reason the two are written apart. A percentage-of-centre band
     * collapses as the centre nears zero and inverts once it crosses, and return on equity crosses
     * zero on ordinary companies. Windows draws the same distinction at `engine.rs:1299-1305`.
     *
     * A sector-adjusted term is labelled `ROE§`, so a list holding both rules says which one scored
     * each row. The marker is Windows's convention, kept identical here on purpose.
     */
    private fun addReturnOnEquity(
        acc: EvidenceAccumulator,
        fundamentals: FundamentalSnapshot,
        sectorReturnOnEquityBps: Int?,
    ) {
        var roeBps = fundamentals.returnOnEquityBps ?: return
        if (sectorReturnOnEquityBps == null) {
            acc.add(
                V3_FUND_ROE_WEIGHT,
                smoothRamp(roeBps.toDouble(), V3_FUND_ROE_LOWER_BPS, V3_FUND_ROE_UPPER_BPS),
                "ROE",
                comparisons = listOf(
                    absoluteBandComparison(
                        roeBps,
                        ScoreFactorValueKind.Percent,
                        V3_FUND_ROE_LOWER_BPS,
                        V3_FUND_ROE_UPPER_BPS,
                    ),
                ),
            )
            return
        }
        var centre = sectorReturnOnEquityBps.toDouble()
        var ramp = smoothRamp(
            roeBps.toDouble(),
            centre + V4_FUND_SECTOR_ROE_LOWER_OFFSET_BPS,
            centre + V4_FUND_SECTOR_ROE_UPPER_OFFSET_BPS,
        )
        acc.add(
            V3_FUND_ROE_WEIGHT,
            ramp,
            "ROE$SECTOR_ADJUSTED_MARKER",
            comparisons = listOf(
                ScoreFactorComparison(roeBps, ScoreFactorValueKind.Percent, reference = sectorReturnOnEquityBps),
            ),
        )
    }

    private fun addEarningsGrowth(acc: EvidenceAccumulator, fundamentals: FundamentalSnapshot) {
        fundamentals.earningsGrowthBps?.let { growthBps ->
            acc.add(
                V3_FUND_GROWTH_WEIGHT,
                smoothRamp(growthBps.toDouble(), V3_FUND_GROWTH_LOWER_BPS, V3_FUND_GROWTH_UPPER_BPS),
                "Growth",
                growthBps,
                comparisons = listOf(
                    absoluteBandComparison(
                        growthBps,
                        ScoreFactorValueKind.Percent,
                        V3_FUND_GROWTH_LOWER_BPS,
                        V3_FUND_GROWTH_UPPER_BPS,
                    ),
                ),
            )
        }
    }

    /**
     * V4 growth is two facts that used to share one label.
     *
     * Pulse is Yahoo quarter EPS YoY. Trend is the median of the last two to four
     * annual revenue YoY rates. Each takes half of V3's Growth weight, so a name
     * like MELI (revenue up, EPS down) lands near zero instead of saturating.
     *
     * Pulse is refused when trailing EPS is under ten cents, or when the Yahoo
     * rate is foreign to the annual net-income series. Trend needs two clean
     * revenue transitions. This path does not read DCF output.
     */
    private fun addV4Growth(
        acc: EvidenceAccumulator,
        fundamentals: FundamentalSnapshot,
        timeseries: FundamentalTimeseries?,
    ) {
        var trendBps = trendGrowthBps(timeseries)
        var pulseBps = pulseGrowthBps(fundamentals, timeseries)
        var trendRamp = trendBps?.let { smoothRamp(it.toDouble(), V4_FUND_GROWTH_LOWER_BPS, V4_FUND_GROWTH_UPPER_BPS) }
        var pulseRamp = pulseBps?.let { smoothRamp(it.toDouble(), V4_FUND_GROWTH_LOWER_BPS, V4_FUND_GROWTH_UPPER_BPS) }
        if (trendRamp != null && trendBps != null) {
            acc.add(
                V4_FUND_TREND_WEIGHT,
                trendRamp,
                "Trend",
                trendBps,
                comparisons = listOf(growthBandComparison(trendBps)),
            )
        }
        if (pulseRamp != null && pulseBps != null) {
            acc.add(
                V4_FUND_PULSE_WEIGHT,
                pulseRamp,
                "Pulse",
                pulseBps,
                comparisons = listOf(growthBandComparison(pulseBps)),
            )
        }
        if (trendBps != null && pulseBps != null && abs(trendBps - pulseBps) >= V4_GROWTH_CONFLICT_BPS) {
            acc.flag("Pulse≠Trend")
        }
        // Both readings cross the last filed year, so a write-down inside it moves them both. The
        // mark costs nothing: it says the growth above was read over a year that is not the
        // business, and leaves the reader to weigh it.
        if (earningsContamination(timeseries).latestYearContaminated) {
            acc.flag(EARNINGS_CHARGE_LABEL)
        }
    }

    private fun pulseGrowthBps(
        fundamentals: FundamentalSnapshot,
        timeseries: FundamentalTimeseries?,
    ): Int? {
        var growthBps = fundamentals.earningsGrowthBps ?: return null
        var epsCents = fundamentals.trailingEpsCents ?: return null
        if (abs(epsCents) < V4_PULSE_MIN_ABS_EPS_CENTS) return null
        var annualRates = recentGrowthRatesBps(timeseries?.netIncome.orEmpty())
        if (isForeignTo(growthBps.toDouble(), annualRates.map { it.toDouble() })) return null
        return growthBps
    }

    private fun trendGrowthBps(timeseries: FundamentalTimeseries?): Int? {
        var rates = recentGrowthRatesBps(timeseries?.revenue.orEmpty())
        if (rates.size < V4_TREND_MIN_TRANSITIONS) return null
        return medianOf(rates.map { it.toDouble() })?.roundToInt()
    }

    /**
     * Annual YoY rates in bps, one per adjacent pair of positive-level fiscal years.
     *
     * The population rules live in [positiveLevelTransitions]: a pair that skips a year is not an
     * annual rate, a negative base inverts the ratio's sign, and a loss-to-profit crossing prints
     * nonsense. Revenue and net income take the same path — revenue arrives positive-filtered
     * anyway, and net income needs the sign rule more than it needs the old negative-base rates.
     */
    private fun recentGrowthRatesBps(series: List<AnnualReportedValue>): List<Int> =
        positiveLevelTransitions(series, maxYears = V4_TREND_MAX_YEARS)
            .map { (previous, latest) ->
                ((latest / previous - 1.0) * BASIS_POINTS_PER_UNIT)
                    .takeIf { it.isFinite() }
                    ?.roundToInt()
            }
            .filterNotNull()

    /**
     * V4's leverage vote: net debt against a year of EBITDA, read against the sector when the
     * sector can support a centre.
     *
     * V3 keeps [addBalanceSheet] and its book debt/equity. The split is the point — V4 is the model
     * still in beta, and the two must be able to disagree about leverage without one dragging the
     * other.
     *
     * A financial-services row takes no vote at all. Its debt line is deposits or float — the raw
     * material, not borrowed money — and EBITDA is not a capacity measure for it, so every input
     * this term can read is the wrong input. See [FinancialClassPolicy].
     *
     * Three inputs in order of strength, each labelled so the list says which one spoke:
     * `ND/EBITDA` from dollars, `D/E` from the mis-scaled ratio, `Bal` from a bare cash-versus-debt
     * comparison. A symbol with none of them adds nothing, which pulls the bucket toward zero
     * rather than excusing it.
     */
    private fun addV4BalanceSheet(
        acc: EvidenceAccumulator,
        fundamentals: FundamentalSnapshot,
        sectorNetDebtToEbitdaHundredths: Int?,
    ) {
        if (!v4LeverageVotes(FinancialClassPolicy.classify(fundamentals))) return
        addIndustrialLeverageVote(acc, fundamentals, sectorNetDebtToEbitdaHundredths)
    }

    /** The three-input leverage vote V4 and V5's operating rows share. */
    private fun addIndustrialLeverageVote(
        acc: EvidenceAccumulator,
        fundamentals: FundamentalSnapshot,
        sectorNetDebtToEbitdaHundredths: Int?,
    ) {
        var leverage = netDebtToEbitdaOf(fundamentals)
        if (leverage != null) {
            var centre = sectorNetDebtToEbitdaHundredths?.toDouble()
            var lower = centre?.plus(V4_FUND_SECTOR_LEVERAGE_LOWER_OFFSET) ?: V4_FUND_LEVERAGE_LOW
            var upper = centre?.plus(V4_FUND_SECTOR_LEVERAGE_UPPER_OFFSET) ?: V4_FUND_LEVERAGE_HIGH
            var label = if (centre != null) "ND/EBITDA$SECTOR_ADJUSTED_MARKER" else "ND/EBITDA"
            var comparisons = if (sectorNetDebtToEbitdaHundredths != null) {
                listOf(
                    ScoreFactorComparison(
                        leverage,
                        ScoreFactorValueKind.Multiple,
                        reference = sectorNetDebtToEbitdaHundredths,
                    ),
                )
            } else {
                listOf(
                    absoluteBandComparison(
                        leverage,
                        ScoreFactorValueKind.Multiple,
                        V4_FUND_LEVERAGE_LOW,
                        V4_FUND_LEVERAGE_HIGH,
                    ),
                )
            }
            // Negated: more net debt is the worse reading, and the ramp climbs with its input.
            acc.add(
                V4_FUND_LEVERAGE_WEIGHT,
                -smoothRamp(leverage.toDouble(), lower, upper),
                label,
                comparisons = comparisons,
            )
            return
        }
        var deHundredths = fundamentals.debtToEquityHundredths
        if (deHundredths != null) {
            var ramp = -smoothRamp(deHundredths.toDouble(), V4_FUND_FALLBACK_DE_LOW, V4_FUND_FALLBACK_DE_HIGH)
            acc.add(
                V4_FUND_LEVERAGE_WEIGHT,
                ramp,
                "D/E",
                comparisons = listOf(
                    absoluteBandComparison(
                        deHundredths,
                        ScoreFactorValueKind.Multiple,
                        V4_FUND_FALLBACK_DE_LOW,
                        V4_FUND_FALLBACK_DE_HIGH,
                    ),
                ),
            )
            return
        }
        var cash = fundamentals.totalCashDollars
        var debt = fundamentals.totalDebtDollars
        if (cash != null && debt != null) {
            acc.add(
                V4_FUND_LEVERAGE_WEIGHT,
                if (cash >= debt) 1.0 else -0.5,
                "Bal",
                comparisons = listOf(
                    ScoreFactorComparison(
                        0,
                        ScoreFactorValueKind.Dollars,
                        why = "cash vs debt",
                        observedDollars = cash,
                        referenceDollars = debt,
                    ),
                ),
            )
        }
    }

    private fun addBalanceSheet(acc: EvidenceAccumulator, fundamentals: FundamentalSnapshot) {
        val deHundredths = fundamentals.debtToEquityHundredths
        if (deHundredths != null) {
            acc.add(
                V3_FUND_BALANCE_WEIGHT,
                -smoothRamp(deHundredths.toDouble(), V3_FUND_BALANCE_DE_LOW, V3_FUND_BALANCE_DE_HIGH),
                "D/E",
                comparisons = listOf(
                    absoluteBandComparison(
                        deHundredths,
                        ScoreFactorValueKind.Multiple,
                        V3_FUND_BALANCE_DE_LOW,
                        V3_FUND_BALANCE_DE_HIGH,
                    ),
                ),
            )
        } else {
            val cash = fundamentals.totalCashDollars
            val debt = fundamentals.totalDebtDollars
            if (cash != null && debt != null) {
                acc.add(
                    V3_FUND_BALANCE_WEIGHT,
                    if (cash >= debt) 1.0 else -0.5,
                    "Bal",
                    comparisons = listOf(
                        ScoreFactorComparison(
                            0,
                            ScoreFactorValueKind.Dollars,
                            why = "cash vs debt",
                            observedDollars = cash,
                            referenceDollars = debt,
                        ),
                    ),
                )
            }
        }
    }

    /** Cash conversion quality when both FCF and OCF are present (does not re-score OCF sign). */
    private fun addCashConversion(
        acc: EvidenceAccumulator,
        fundamentals: FundamentalSnapshot,
        yieldVoted: Boolean = false,
    ) {
        // One fact family, one vote. `yieldVoted` is true only when the yield vote itself read
        // trailing FCF dollars — the same number Conv re-reads below. A yield vote sourced from a
        // multi-year series is a different number, so it does not silence Conv.
        if (yieldVoted) {
            acc.silenceDesignTerm(V3_FUND_CASH_QUALITY_WEIGHT)
            return
        }
        if (industrialFcfRefusalLabel(fundamentals) != null) return
        val fcfDollars = fundamentals.freeCashFlowDollars
        val ocfForQuality = fundamentals.operatingCashFlowDollars
        if (fcfDollars != null && ocfForQuality != null && ocfForQuality > 0L) {
            val conversion = fcfDollars.toDouble() / ocfForQuality.toDouble()
            if (!conversion.isFinite()) return
            var conversionHundredths = (conversion * 100.0)
                .coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble())
                .roundToInt()
            acc.add(
                V3_FUND_CASH_QUALITY_WEIGHT,
                smoothRamp(conversion, 0.0, 1.0),
                "Conv",
                comparisons = listOf(
                    absoluteBandComparison(
                        conversionHundredths,
                        ScoreFactorValueKind.Multiple,
                        0.0,
                        100.0,
                        why = "FCF / OCF",
                    ),
                ),
            )
        }
    }

    internal fun aggressiveV3TechnicalScore(summary: ChartRangeSummary?): BucketEvidence {
        summary ?: return BucketEvidence.absent()
        val latestCloseCents = summary.latestCloseCents ?: return BucketEvidence.absent()
        val acc = EvidenceAccumulator(V3_TECHNICALS_FULL_WEIGHT)

        summary.ema20Cents?.takeIf { it > 0 }?.let { ema20 ->
            val delta = (latestCloseCents - ema20).toDouble() / ema20.toDouble()
            acc.add(V3_TECH_TREND_PRICE_20_WEIGHT, smoothRamp(delta, -V3_TECH_TREND_DELTA_BOUND, V3_TECH_TREND_DELTA_BOUND), "Px/20")
        }
        if (summary.ema20Cents != null && summary.ema50Cents != null && summary.ema50Cents > 0) {
            val delta = (summary.ema20Cents - summary.ema50Cents).toDouble() / summary.ema50Cents.toDouble()
            acc.add(V3_TECH_TREND_20_50_WEIGHT, smoothRamp(delta, -V3_TECH_TREND_DELTA_BOUND, V3_TECH_TREND_DELTA_BOUND), "20/50")
        }
        if (summary.ema50Cents != null && summary.ema200Cents != null && summary.ema200Cents > 0) {
            val delta = (summary.ema50Cents - summary.ema200Cents).toDouble() / summary.ema200Cents.toDouble()
            acc.add(V3_TECH_TREND_50_200_WEIGHT, smoothRamp(delta, -V3_TECH_TREND_DELTA_BOUND, V3_TECH_TREND_DELTA_BOUND), "50/200")
        }

        if (summary.histogramCents != null && latestCloseCents > 0) {
            val ratio = summary.histogramCents.toDouble() / latestCloseCents.toDouble()
            acc.add(V3_TECH_HISTOGRAM_WEIGHT, smoothRamp(ratio, -V3_TECH_HISTOGRAM_BOUND, V3_TECH_HISTOGRAM_BOUND), "Hist")
        }

        if (summary.macdCents != null && summary.signalCents != null) {
            val direction = when {
                summary.macdCents > summary.signalCents -> 1.0
                summary.macdCents < summary.signalCents -> -1.0
                else -> 0.0
            }
            acc.add(V3_TECH_MACD_DIRECTION_WEIGHT, direction, "MACD")
        }

        summary.latestWilderRsi?.let { rsi ->
            val levelRamp = v3RsiLevelRamp(rsi)
            val slopeRamp = summary.latestRsiSlope?.let { slope ->
                smoothRamp(slope, -V3_TECH_RSI_SLOPE_BOUND, V3_TECH_RSI_SLOPE_BOUND)
            } ?: 0.0
            val combined = (0.65 * levelRamp) + (0.35 * slopeRamp)
            acc.add(V3_TECH_RSI_WEIGHT, combined.coerceIn(-1.0, 1.0), "RSI")
        }

        summary.volumeRatioHundredths?.let { volumeHundredths ->
            acc.add(
                V3_TECH_VOLUME_WEIGHT,
                smoothRamp(volumeHundredths.toDouble(), V3_TECH_VOLUME_RATIO_LOW, V3_TECH_VOLUME_RATIO_HIGH),
                "Vol",
            )
        }

        return acc.toEvidence()
    }

    /**
     * RSI level preference: reward mid-bullish zone (~45–65), discourage overbought chase.
     * 30 → −1, 55 → +1, 80 → −0.5, above 80 stays −0.5.
     */
    internal fun v3RsiLevelRamp(rsi: Double): Double = when {
        rsi <= 30.0 -> -1.0
        rsi <= 55.0 -> smoothRamp(rsi, 30.0, 55.0)
        rsi <= 80.0 -> {
            val t = (rsi - 55.0) / (80.0 - 55.0)
            (1.0 - (t * 1.5)).coerceIn(-1.0, 1.0)
        }
        else -> -0.5
    }

    internal fun aggressiveV3ForecastScore(detail: SymbolDetail, analysis: DcfAnalysis?): BucketEvidence {
        val acc = EvidenceAccumulator(V3_FORECAST_FULL_WEIGHT)
        val sufficiencySignals = mutableListOf<String>()
        var reliableEvidenceWeight = 0.0
        var hasValuationAnchor = false

        val targetFairValue = preferredForecastFairValueCents(detail)
        val targetCount = targetAnalystCount(detail)
        val recommendationCount = recommendationAnalystCount(detail)
        val broadestAnalystCount = listOfNotNull(targetCount, recommendationCount).maxOrNull()
        val externalFreshness = v3FreshnessMultiplier(detail)
        val externalStatusReliability = externalStatusReliability(detail.externalStatus)

        val valuationInputs = mutableListOf<WeightedForecastRamp>()
        targetFairValue?.let { fairValue ->
            val targetUpsideBps = checkedUpsideBps(detail.marketPriceCents, fairValue)
            val targetReliability = v3AnalystCoverageReliability(targetCount) * externalFreshness * externalStatusReliability
            when {
                targetUpsideBps == null -> Unit
                !v3HasSufficientAnalystCoverage(targetCount) -> {
                    sufficiencySignals += if (targetCount == null) "Cov?" else "Cov<${V3_FORECAST_MIN_ANALYST_OPINIONS}"
                }
                targetReliability > 0.0 -> {
                    valuationInputs += WeightedForecastRamp(
                        ramp = smoothRamp(targetUpsideBps.toDouble(), V3_FORECAST_UPSIDE_LOWER_BPS, V3_FORECAST_UPSIDE_UPPER_BPS),
                        reliability = targetReliability,
                    )
                }
            }
        }

        if (RANKING_INCLUDES_QUANT_ENGINE) {
            analysis?.let { dcf ->
                dcfMarginOfSafetyBps(dcf, detail.marketPriceCents)?.let { marginBps ->
                    valuationInputs += WeightedForecastRamp(
                        ramp = smoothRamp(marginBps.toDouble(), V3_FORECAST_UPSIDE_LOWER_BPS, V3_FORECAST_UPSIDE_UPPER_BPS),
                        reliability = V3_FORECAST_DCF_RELIABILITY,
                    )
                }
            }
        }

        if (valuationInputs.isNotEmpty()) {
            val reliabilitySum = valuationInputs.sumOf { it.reliability }
            if (reliabilitySum > 0.0) {
                val blendedRamp = valuationInputs.sumOf { it.ramp * it.reliability } / reliabilitySum
                val weight = V3_FORECAST_VALUATION_WEIGHT * reliabilitySum.coerceAtMost(1.0)
                acc.add(weight, blendedRamp, "Val")
                reliableEvidenceWeight += weight
                hasValuationAnchor = true
            }
        }

        detail.recommendationMeanHundredths?.let { rec ->
            val recReliability = v3AnalystCoverageReliability(recommendationCount) * externalFreshness * externalStatusReliability
            if (!v3HasSufficientAnalystCoverage(recommendationCount)) {
                sufficiencySignals += if (recommendationCount == null) "RecCov?" else "RecCov<${V3_FORECAST_MIN_ANALYST_OPINIONS}"
            } else if (recReliability > 0.0) {
                val weight = V3_FORECAST_REC_WEIGHT * recReliability
                acc.add(weight, -smoothRamp(rec.toDouble(), V3_FORECAST_REC_LOW_HUNDREDTHS, V3_FORECAST_REC_HIGH_HUNDREDTHS), "Rec")
                reliableEvidenceWeight += weight
            }
        }

        // Recommendation distribution skew: distinct from mean rating.
        val skew = v3RecommendationSkew(detail)
        if (skew != null) {
            val skewReliability = v3AnalystCoverageReliability(recommendationCount) * externalFreshness * externalStatusReliability
            if (v3HasSufficientAnalystCoverage(recommendationCount) && skewReliability > 0.0) {
                val weight = V3_FORECAST_SKEW_WEIGHT * skewReliability
                acc.add(weight, skew, "Skew")
                reliableEvidenceWeight += weight
            }
        }

        broadestAnalystCount?.let { count ->
            acc.add(V3_FORECAST_BREADTH_WEIGHT, v3AnalystBreadthRamp(count), "Cov")
            reliableEvidenceWeight += V3_FORECAST_BREADTH_WEIGHT * v3AnalystCoverageReliability(count)
        }

        val targetReliabilityWithoutFreshness = v3AnalystCoverageReliability(targetCount) * externalStatusReliability
        val low = detail.externalSignalLowFairValueCents
        val high = detail.externalSignalHighFairValueCents
        val centre = targetFairValue
        if (low != null && high != null && centre != null && centre > 0L && high > low && targetReliabilityWithoutFreshness > 0.0) {
            val spreadFraction = (high - low).toDouble() / centre.toDouble()
            val weight = V3_FORECAST_ANALYST_UNCERTAINTY_WEIGHT * targetReliabilityWithoutFreshness
            acc.add(weight, -smoothRamp(spreadFraction, 0.0, V3_FORECAST_UNCERTAINTY_BOUND), "Unc")
            reliableEvidenceWeight += weight * externalFreshness
        }

        if (RANKING_INCLUDES_QUANT_ENGINE) {
            analysis?.let { dcf ->
                val base = dcf.baseIntrinsicValueCents
                val bear = dcf.bearIntrinsicValueCents
                val bull = dcf.bullIntrinsicValueCents
                if (base > 0L && bull >= base && base >= bear && bull > bear) {
                    val widthFraction = (bull - bear).toDouble() / base.toDouble()
                    acc.add(
                        V3_FORECAST_DCF_UNCERTAINTY_WEIGHT,
                        -smoothRamp(widthFraction, V3_FORECAST_DCF_WIDTH_LOWER, V3_FORECAST_DCF_WIDTH_UPPER),
                        "DcfUnc",
                    )
                    reliableEvidenceWeight += V3_FORECAST_DCF_UNCERTAINTY_WEIGHT
                }
            }
        }

        if (targetFairValue != null && v3HasSufficientAnalystCoverage(targetCount)) {
            val weight = V3_FORECAST_FRESHNESS_WEIGHT * v3AnalystCoverageReliability(targetCount)
            acc.add(weight, freshnessRamp(externalFreshness), "Fresh")
            reliableEvidenceWeight += weight * externalFreshness
        }

        if (!hasValuationAnchor || reliableEvidenceWeight < V3_FORECAST_MIN_RELIABLE_EVIDENCE_WEIGHT) {
            return acc.toEvidence(extraSignals = sufficiencySignals, scoreOverride = null)
        }

        var raw = acc.normalizedScore() ?: return acc.toEvidence(extraSignals = sufficiencySignals, scoreOverride = null)
        return acc.toEvidence(extraSignals = sufficiencySignals, scoreOverride = raw.coerceIn(-100, 100))
    }

    /**
     * Street-only forecast for V4. Does not read DCF output.
     *
     * Coverage and freshness stay as reliability multipliers. They do not add
     * points. Target-range width takes that budget, so a 50% book cannot print Good
     * on headcount alone.
     */
    internal fun aggressiveV4ForecastScore(detail: SymbolDetail, analysis: DcfAnalysis?): BucketEvidence {
        val acc = EvidenceAccumulator(V4_FORECAST_FULL_WEIGHT)
        val sufficiencySignals = mutableListOf<String>()
        var reliableEvidenceWeight = 0.0
        var hasValuationAnchor = false

        val targetFairValue = preferredForecastFairValueCents(detail)
        val targetCount = targetAnalystCount(detail)
        val recommendationCount = recommendationAnalystCount(detail)
        val externalFreshness = v3FreshnessMultiplier(detail)
        val externalStatusReliability = externalStatusReliability(detail.externalStatus)

        val valuationInputs = mutableListOf<WeightedForecastRamp>()
        var upsideBps: Int? = null
        targetFairValue?.let { fairValue ->
            val targetUpsideBps = checkedUpsideBps(detail.marketPriceCents, fairValue)
            upsideBps = targetUpsideBps
            val targetReliability = v3AnalystCoverageReliability(targetCount) * externalFreshness * externalStatusReliability
            when {
                targetUpsideBps == null -> Unit
                !v3HasSufficientAnalystCoverage(targetCount) -> {
                    sufficiencySignals += if (targetCount == null) "Cov?" else "Cov<${V3_FORECAST_MIN_ANALYST_OPINIONS}"
                }
                targetReliability > 0.0 -> {
                    valuationInputs += WeightedForecastRamp(
                        ramp = smoothRamp(targetUpsideBps.toDouble(), V3_FORECAST_UPSIDE_LOWER_BPS, V3_FORECAST_UPSIDE_UPPER_BPS),
                        reliability = targetReliability,
                    )
                }
            }
        }

        if (valuationInputs.isNotEmpty()) {
            val reliabilitySum = valuationInputs.sumOf { it.reliability }
            if (reliabilitySum > 0.0) {
                val blendedRamp = valuationInputs.sumOf { it.ramp * it.reliability } / reliabilitySum
                val weight = V4_FORECAST_VALUATION_WEIGHT * reliabilitySum.coerceAtMost(1.0)
                acc.add(weight, blendedRamp, "Val", upsideBps)
                reliableEvidenceWeight += weight
                hasValuationAnchor = true
            }
        }

        detail.recommendationMeanHundredths?.let { rec ->
            val recReliability = v3AnalystCoverageReliability(recommendationCount) * externalFreshness * externalStatusReliability
            if (!v3HasSufficientAnalystCoverage(recommendationCount)) {
                sufficiencySignals += if (recommendationCount == null) "RecCov?" else "RecCov<${V3_FORECAST_MIN_ANALYST_OPINIONS}"
            } else if (recReliability > 0.0) {
                val weight = V4_FORECAST_REC_WEIGHT * recReliability
                acc.add(
                    weight,
                    -smoothRamp(rec.toDouble(), V3_FORECAST_REC_LOW_HUNDREDTHS, V3_FORECAST_REC_HIGH_HUNDREDTHS),
                    "Rec",
                    rec,
                )
                reliableEvidenceWeight += weight
            }
        }

        val skew = v4RecommendationSkew(detail)
        if (skew != null) {
            val skewReliability = v3AnalystCoverageReliability(recommendationCount) * externalFreshness * externalStatusReliability
            if (v3HasSufficientAnalystCoverage(recommendationCount) && skewReliability > 0.0) {
                val weight = V4_FORECAST_SKEW_WEIGHT * skewReliability
                acc.add(weight, skew, "Skew")
                reliableEvidenceWeight += weight
            }
        }

        val targetReliabilityWithoutFreshness = v3AnalystCoverageReliability(targetCount) * externalStatusReliability
        val low = detail.externalSignalLowFairValueCents
        val high = detail.externalSignalHighFairValueCents
        val centre = targetFairValue
        if (low != null && high != null && centre != null && centre > 0L && high > low && targetReliabilityWithoutFreshness > 0.0) {
            val spreadFraction = (high - low).toDouble() / centre.toDouble()
            val weight = V4_FORECAST_UNCERTAINTY_WEIGHT * targetReliabilityWithoutFreshness
            val spreadBps = ((high - low) * 10_000L / centre).toInt()
            acc.add(weight, -smoothRamp(spreadFraction, 0.0, V4_FORECAST_UNCERTAINTY_BOUND), "Unc", spreadBps)
            reliableEvidenceWeight += weight * externalFreshness
        }

        if (!hasValuationAnchor || reliableEvidenceWeight < V4_FORECAST_MIN_RELIABLE_EVIDENCE_WEIGHT) {
            return acc.toEvidence(extraSignals = sufficiencySignals, scoreOverride = null)
        }

        var raw = acc.normalizedScore() ?: return acc.toEvidence(extraSignals = sufficiencySignals, scoreOverride = null)
        return acc.toEvidence(extraSignals = sufficiencySignals, scoreOverride = raw.coerceIn(-100, 100))
    }

    private fun v3RecommendationSkew(detail: SymbolDetail): Double? {
        val strongBuy = detail.strongBuyCount ?: 0
        val buy = detail.buyCount ?: 0
        val hold = detail.holdCount ?: 0
        val sell = detail.sellCount ?: 0
        val strongSell = detail.strongSellCount ?: 0
        val total = strongBuy + buy + hold + sell + strongSell
        if (total <= 0) return null
        val bullish = (strongBuy + buy).toDouble()
        val bearish = (sell + strongSell).toDouble()
        return ((bullish - bearish) / total.toDouble()).coerceIn(-1.0, 1.0)
    }

    /**
     * V3's net bull-bear, minus a tail-conflict haircut.
     *
     * Two Strong Sells against three Strong Buys is not the same book as fourteen
     * Buys and two Sells. The net can match; the tails do not.
     */
    private fun v4RecommendationSkew(detail: SymbolDetail): Double? {
        var net = v3RecommendationSkew(detail) ?: return null
        var strongBuy = detail.strongBuyCount ?: 0
        var strongSell = detail.strongSellCount ?: 0
        var total = (detail.strongBuyCount ?: 0) + (detail.buyCount ?: 0) +
            (detail.holdCount ?: 0) + (detail.sellCount ?: 0) + (detail.strongSellCount ?: 0)
        if (total <= 0) return net
        var conflict = 2.0 * min(strongBuy, strongSell).toDouble() / total.toDouble()
        return (net - conflict).coerceIn(-1.0, 1.0)
    }

    private fun v3HasSufficientAnalystCoverage(count: Int?): Boolean =
        count != null && count >= V3_FORECAST_MIN_ANALYST_OPINIONS

    private fun v3AnalystCoverageReliability(count: Int?): Double {
        if (!v3HasSufficientAnalystCoverage(count)) return 0.0
        val progress = ((count!!.toDouble() - V3_FORECAST_MIN_ANALYST_OPINIONS.toDouble()) /
            (V3_FORECAST_FULL_ANALYST_OPINIONS - V3_FORECAST_MIN_ANALYST_OPINIONS.toDouble()))
            .coerceIn(0.0, 1.0)
        return 0.35 + (0.65 * progress)
    }

    private fun v3AnalystBreadthRamp(count: Int): Double {
        if (count < V3_FORECAST_MIN_ANALYST_OPINIONS) return -1.0
        val progress = ((count.toDouble() - V3_FORECAST_MIN_ANALYST_OPINIONS.toDouble()) /
            (V3_FORECAST_FULL_ANALYST_OPINIONS - V3_FORECAST_MIN_ANALYST_OPINIONS.toDouble()))
            .coerceIn(0.0, 1.0)
        return (-0.5 + (1.5 * progress)).coerceIn(-1.0, 1.0)
    }

    private fun v3FreshnessMultiplier(detail: SymbolDetail): Double {
        val age = detail.externalSignalAgeSeconds ?: return 1.0
        if (age <= 0L) return 1.0
        return exp(-age.toDouble() / V3_FORECAST_FRESHNESS_HALF_LIFE_SECONDS)
    }

    private data class WeightedForecastRamp(
        val ramp: Double,
        val reliability: Double,
    )

    private fun preferredForecastFairValueCents(detail: SymbolDetail): Long? =
        detail.weightedExternalSignalFairValueCents ?: detail.externalSignalFairValueCents

    private fun targetAnalystCount(detail: SymbolDetail): Int? = when {
        detail.weightedExternalSignalFairValueCents != null -> detail.weightedAnalystCount ?: detail.analystOpinionCount
        detail.externalSignalFairValueCents != null -> detail.analystOpinionCount
        else -> null
    }

    private fun recommendationAnalystCount(detail: SymbolDetail): Int? {
        val trendCount = listOfNotNull(
            detail.strongBuyCount,
            detail.buyCount,
            detail.holdCount,
            detail.sellCount,
            detail.strongSellCount,
        ).sum().takeIf { it > 0 }
        return listOfNotNull(detail.analystOpinionCount, trendCount).maxOrNull()
    }

    private fun hasSufficientAnalystCoverage(count: Int?): Boolean =
        count != null && count >= V2_FORECAST_MIN_ANALYST_OPINIONS

    private fun analystCoverageReliability(count: Int?): Double {
        if (!hasSufficientAnalystCoverage(count)) return 0.0
        val progress = ((count!!.toDouble() - V2_FORECAST_MIN_ANALYST_OPINIONS.toDouble()) /
            (V2_FORECAST_FULL_ANALYST_OPINIONS - V2_FORECAST_MIN_ANALYST_OPINIONS.toDouble()))
            .coerceIn(0.0, 1.0)
        return 0.35 + (0.65 * progress)
    }

    private fun analystBreadthRamp(count: Int): Double {
        if (count < V2_FORECAST_MIN_ANALYST_OPINIONS) return -1.0
        val progress = ((count.toDouble() - V2_FORECAST_MIN_ANALYST_OPINIONS.toDouble()) /
            (V2_FORECAST_FULL_ANALYST_OPINIONS - V2_FORECAST_MIN_ANALYST_OPINIONS.toDouble()))
            .coerceIn(0.0, 1.0)
        return (-0.5 + (1.5 * progress)).coerceIn(-1.0, 1.0)
    }

    private fun externalStatusReliability(status: ExternalSignalStatus): Double = when (status) {
        ExternalSignalStatus.Supportive,
        ExternalSignalStatus.Divergent,
        -> 1.0
        ExternalSignalStatus.Stale -> 0.25
        ExternalSignalStatus.Missing -> 0.0
    }

    private fun freshnessRamp(multiplier: Double): Double = (2.0 * multiplier - 1.0).coerceIn(-1.0, 1.0)

    private fun freshnessMultiplier(detail: SymbolDetail): Double {
        val age = detail.externalSignalAgeSeconds ?: return 1.0
        if (age <= 0L) return 1.0
        return exp(-age.toDouble() / V2_FORECAST_FRESHNESS_HALF_LIFE_SECONDS)
    }

    /**
     * Smooth piecewise-linear ramp in [-1, +1].
     * Returns -1 at or below [lower], +1 at or above [upper], linear interpolation between.
     */
    internal fun smoothRamp(observed: Double, lower: Double, upper: Double): Double {
        require(upper > lower) { "smoothRamp requires upper ($upper) > lower ($lower)" }
        if (observed <= lower) return -1.0
        if (observed >= upper) return 1.0
        return 2.0 * (observed - lower) / (upper - lower) - 1.0
    }

    private fun growthBandComparison(observedBps: Int) = absoluteBandComparison(
        observedBps,
        ScoreFactorValueKind.Percent,
        V4_FUND_GROWTH_LOWER_BPS,
        V4_FUND_GROWTH_UPPER_BPS,
    )

    /**
     * Weighted sum over the bucket budget. [exemptClassTerm] may change the budget after
     * [add]; factor points are computed at [toEvidence], not at add time.
     */
    private class EvidenceAccumulator(private var normalizationWeight: Double) {
        private data class PendingFactor(
            val weight: Double,
            val clamped: Double,
            val label: String,
            val token: String,
            val inputBps: Int?,
            val comparisons: List<ScoreFactorComparison>,
        )

        private var weightedSum = 0.0
        private var evidenceWeight = 0.0
        private var designSilentWeight = 0.0
        private var penaltyPoints = 0
        val signals = mutableListOf<String>()
        private val slots = mutableListOf<Slot>()

        private sealed class Slot {
            data class Term(val factor: PendingFactor) : Slot()
            data class Fixed(val factor: ScoreFactor) : Slot()
        }

        init {
            require(normalizationWeight > 0.0) { "EvidenceAccumulator normalizationWeight must be positive" }
        }

        fun add(
            weight: Double,
            ramp: Double,
            label: String? = null,
            inputBps: Int? = null,
            comparisons: List<ScoreFactorComparison> = emptyList(),
        ) {
            require(weight > 0.0) { "EvidenceAccumulator weight must be positive" }
            var clamped = ramp.coerceIn(-1.0, 1.0)
            weightedSum += weight * clamped
            evidenceWeight += weight
            if (label != null) {
                var token = "$label${signalSuffix(clamped)}"
                signals += token
                slots += Slot.Term(PendingFactor(weight, clamped, label, token, inputBps, comparisons))
            }
        }

        /**
         * A subtraction in the bucket's own hundred points, applied after the terms are normalized.
         *
         * It is not a term and carries no weight in the divisor. A term's weight is charged to
         * every symbol whether or not the term fires, which is right for an input a symbol could
         * have had and wrong for one it could not: a software company has no commodity cycle, and
         * scaling its whole bucket down for a reading that can never apply to it would be a defect,
         * not a caution. A penalty only reaches the symbols it was measured on.
         */
        fun penalize(points: Int, label: String, inputBps: Int? = null) {
            require(points > 0) { "EvidenceAccumulator penalty must be positive" }
            penaltyPoints += points
            signals += "$label-"
            slots += Slot.Fixed(ScoreFactor(key = label, token = "$label-", bucketPoints = -points, inputBps = inputBps))
        }

        fun flag(label: String) {
            signals += label
            slots += Slot.Fixed(ScoreFactor(key = label, token = label, bucketPoints = 0))
        }

        fun exemptClassTerm(weight: Double) {
            require(weight > 0.0) { "EvidenceAccumulator exempt weight must be positive" }
            normalizationWeight = (normalizationWeight - weight).coerceAtLeast(1.0)
        }

        fun silenceDesignTerm(weight: Double) {
            require(weight > 0.0) { "EvidenceAccumulator silence weight must be positive" }
            designSilentWeight += weight
        }

        fun flagCoverageGap() {
            var idle = normalizationWeight - evidenceWeight - designSilentWeight
            if (idle > COVERAGE_GAP_IDLE_WEIGHT) flag(FUND_COVERAGE_GAP_LABEL)
        }

        /** A penalty alone never creates a score: with no term measured the bucket is still absent. */
        fun normalizedScore(): Int? {
            if (evidenceWeight == 0.0) return null
            var normalized = (weightedSum / normalizationWeight) * 100.0 - penaltyPoints
            return normalized.coerceIn(-100.0, 100.0).roundToInt()
        }

        fun toEvidence(
            extraSignals: List<String> = emptyList(),
            scoreOverride: Int? = normalizedScore(),
        ): BucketEvidence {
            var scored = slots.map { slot ->
                when (slot) {
                    is Slot.Term -> ScoreFactor(
                        key = slot.factor.label,
                        token = slot.factor.token,
                        bucketPoints = ((slot.factor.weight * slot.factor.clamped) / normalizationWeight * 100.0)
                            .roundToInt(),
                        inputBps = slot.factor.inputBps,
                        comparisons = slot.factor.comparisons,
                    )
                    is Slot.Fixed -> slot.factor
                }
            }
            var extras = extraSignals.map { signal ->
                ScoreFactor(key = signal, token = signal, bucketPoints = 0)
            }
            return BucketEvidence(
                score = scoreOverride,
                signals = (signals + extraSignals).distinct(),
                factors = scored + extras,
            )
        }

        private fun signalSuffix(r: Double): String = when {
            r >= 0.5 -> "++"
            r > 0.0 -> "+"
            r >= -0.5 -> "-"
            else -> "--"
        }
    }

    private fun Pair<Int?, List<String>>.toEvidence(): BucketEvidence = BucketEvidence(
        score = first,
        signals = second,
        factors = second.map { token -> ScoreFactor(key = token, token = token, bucketPoints = 0) },
    )


    private fun dcfMarginOfSafetyBps(analysis: DcfAnalysis, marketPriceCents: Long): Int? {
        if (analysis.baseIntrinsicValueCents <= 0 || marketPriceCents <= 0) {
            return null
        }
        val intrinsic = BigInteger.valueOf(analysis.baseIntrinsicValueCents)
        val market = BigInteger.valueOf(marketPriceCents)
        val scaled = ((intrinsic - market) * BigInteger.valueOf(10_000L)) / intrinsic
        return scaled.coerceIn(BigInteger.valueOf(Int.MIN_VALUE.toLong()), BigInteger.valueOf(Int.MAX_VALUE.toLong())).toInt()
    }

    private fun preferredChartSummary(summaries: Map<ChartRange, ChartRangeSummary>?): ChartRangeSummary? {
        summaries ?: return null
        return summaries[ChartRange.Year] ?: summaries.values.maxByOrNull { it.candleCount }
    }

    private fun confidenceRankValue(confidence: ConfidenceBand): Double = when (confidence) {
        ConfidenceBand.Low -> 0.0
        ConfidenceBand.Provisional -> 1.0
        ConfidenceBand.High -> 2.0
    }

    private fun roundedDivision(numerator: java.math.BigInteger, denominator: java.math.BigInteger): java.math.BigInteger {
        if (denominator == java.math.BigInteger.ZERO) return java.math.BigInteger.ZERO
        val quotient = numerator / denominator
        val remainder = numerator % denominator
        val doubled = remainder.abs() * BigInteger.valueOf(2L)
        return if (doubled >= denominator.abs()) {
            quotient + if (numerator.signum() == denominator.signum()) java.math.BigInteger.ONE else java.math.BigInteger.ONE.negate()
        } else {
            quotient
        }
    }
}
