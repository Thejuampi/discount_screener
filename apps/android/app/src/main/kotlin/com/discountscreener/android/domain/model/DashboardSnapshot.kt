package com.discountscreener.android.domain.model

import com.discountscreener.core.engine.checkedUpsideBps
import com.discountscreener.core.model.AlertEvent
import com.discountscreener.core.model.CandidateRow
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.ExternalSignalStatus
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.IssueRecord
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.OutcomeConfidence
import com.discountscreener.core.model.ProjectedDashboardData
import com.discountscreener.core.model.QualificationStatus
import com.discountscreener.core.model.QuantLensReport
import com.discountscreener.core.model.QuantLensRowSummary
import com.discountscreener.core.model.ScoreFactor
import com.discountscreener.core.model.SymbolDetail
import com.discountscreener.core.model.SymbolRevision
import com.discountscreener.core.plan.PlanBoard
import com.discountscreener.core.regime.MarketContextUnavailableReason
import com.discountscreener.core.regime.MarketRegime
import com.discountscreener.core.regime.RegimeCause
import com.discountscreener.core.regime.RegimeScoreStatus

enum class MarketReadStatus {
    Pending,
    Ready,
    Unavailable,
}

enum class DashboardStartupPhase {
    Restoring,
    SwitchingProfile,
    ShowingCached,
    Refreshing,
    Ready,
}

enum class TrackedRowState {
    Loading,
    Cached,
    Live,
    Failed,
}

enum class RowFreshness {
    Loading,
    Restored,
    Updating,
    Updated,
    Stale,
    Issue,
}

/**
 * Whether the decision tag on a row was decided together with the price on that row.
 *
 * A row read back from the database keeps the tag its last refresh gave it. The numbers behind that
 * tag are on file and are the honest thing to show: the row said Act yesterday, and hiding it says
 * nothing at all. It is drawn faded, so the tag never reads as a call on a price the app has not
 * fetched yet. Only [RowFreshness.Updated] means the two were taken in the same pass.
 */
fun decisionTagIsCurrent(freshness: RowFreshness): Boolean = freshness == RowFreshness.Updated

enum class RowExplanationKind {
    PriceMoved,
    TargetChanged,
    RelativeReRank,
    CombinedMove,
    NoBaseline,
    NoMeaningfulChange,
}

enum class RowDecisionState {
    Act,
    Watch,
    Avoid,
}

enum class ChangeDirection {
    Up,
    Down,
}

data class RankMovement(
    val direction: ChangeDirection,
    val places: Int,
    val previousIndex: Int,
    val currentIndex: Int,
)

enum class ValuationChangeTier {
    Significant,
    Major,
}

data class ValuationChange(
    val direction: ChangeDirection,
    val previousFairValueCents: Long,
    val currentFairValueCents: Long,
    val changeBps: Int,
    val tier: ValuationChangeTier,
)

fun rankMovement(previousIndex: Int?, currentIndex: Int): RankMovement? {
    previousIndex ?: return null
    if (previousIndex == currentIndex) return null
    return RankMovement(
        direction = if (currentIndex < previousIndex) ChangeDirection.Up else ChangeDirection.Down,
        places = kotlin.math.abs(currentIndex - previousIndex),
        previousIndex = previousIndex,
        currentIndex = currentIndex,
    )
}

fun significantValuationChange(previousFairValueCents: Long?, currentFairValueCents: Long?): ValuationChange? {
    previousFairValueCents ?: return null
    currentFairValueCents ?: return null
    val changeBps = checkedUpsideBps(previousFairValueCents, currentFairValueCents) ?: return null
    val absoluteChangeBps = kotlin.math.abs(changeBps)
    if (absoluteChangeBps < SIGNIFICANT_VALUATION_MOVE_BPS) {
        return null
    }
    return ValuationChange(
        direction = if (changeBps >= 0) ChangeDirection.Up else ChangeDirection.Down,
        previousFairValueCents = previousFairValueCents,
        currentFairValueCents = currentFairValueCents,
        changeBps = changeBps,
        tier = if (absoluteChangeBps >= MAJOR_VALUATION_MOVE_BPS) {
            ValuationChangeTier.Major
        } else {
            ValuationChangeTier.Significant
        },
    )
}

fun preferredAnalystTargetFairValueCents(detail: SymbolDetail?): Long? =
    detail?.weightedExternalSignalFairValueCents ?: detail?.externalSignalFairValueCents

fun preferredAnalystCoverageCount(detail: SymbolDetail?): Int? =
    detail?.weightedAnalystCount ?: detail?.analystOpinionCount

data class TrackedSymbolRow(
    val symbol: String,
    val marketPriceCents: Long? = null,
    val intrinsicValueCents: Long? = null,
    val gapBps: Int? = null,
    val upsideBps: Int? = gapBps,
    val confidence: ConfidenceBand? = null,
    val qualification: QualificationStatus? = null,
    val isWatched: Boolean = false,
    val state: TrackedRowState = TrackedRowState.Loading,
    val freshness: RowFreshness = RowFreshness.Loading,
    val stale: Boolean = false,
    val providerIssue: String? = null,
    val trustNote: String? = null,
    val freshnessAsOfEpochSeconds: Long? = null,
    val companyName: String? = null,
    val rankMovement: RankMovement? = null,
    val valuationChange: ValuationChange? = null,
    val explanation: RowExplanationKind? = null,
    val decisionState: RowDecisionState? = null,
    val quantLensSummary: QuantLensRowSummary? = null,
    val valuationStanceLabel: String? = null,
)

data class OpportunityListRow(
    val symbol: String,
    val marketPriceCents: Long,
    /**
     * Null when the valuation judgment named no primary. The row still exists, still scores, and
     * still shows [valuationStanceLabel]; what it must not do is print a number the judgment
     * refused. [TrackedSymbolRow] has always allowed null here for the same reason.
     *
     * Only rows built from the projection carry that meaning. The legacy path
     * (`opportunityRowsLocked`) copies a non-null intrinsic value straight off `OpportunityRow`, so
     * a row from there is never null and a non-null value from there says nothing about what the
     * judgment decided. Read the stance, not the nullability, if the caller can see both paths.
     */
    val intrinsicValueCents: Long?,
    /**
     * Unix seconds of the next scheduled earnings report. Marks the row and nothing more: no
     * bucket, no composite and no decision reads it, by the decision that this gate only marks.
     */
    val nextEarningsEpoch: Long? = null,
    /**
     * How wide the range of outcomes is, which is a different question from [confidence].
     *
     * [confidence] says whether the data can be trusted. This says how far apart the sources put
     * the answer. A name can be High and Wide at once, and both readings are true.
     */
    val outcomeConfidence: OutcomeConfidence = OutcomeConfidence.Unmeasured,
    /** The span behind [outcomeConfidence], in bps of its own centre. */
    val outcomeWidthBps: Int? = null,
    val gapBps: Int? = null,
    val upsideBps: Int? = gapBps,
    val confidence: ConfidenceBand,
    val qualification: QualificationStatus? = null,
    val externalStatus: ExternalSignalStatus? = null,
    val analystCoverageCount: Int? = null,
    val isWatched: Boolean,
    val freshness: RowFreshness = RowFreshness.Loading,
    val providerIssue: String? = null,
    val trustNote: String? = null,
    val freshnessAsOfEpochSeconds: Long? = null,
    val fundamentalsScore: Int? = null,
    val technicalScore: Int? = null,
    val forecastScore: Int? = null,
    /** The 4th V3 bucket. Null unless [regimeStatus] is [RegimeScoreStatus.Included]. */
    val regimeScore: Int? = null,
    val compositeScore: Int,
    /** What the three original buckets alone score, so the dimension's impact is a subtraction. */
    val compositeScoreBase: Int = compositeScore,
    val coverageCount: Int,
    val fundamentalsSignals: List<String> = emptyList(),
    val technicalSignals: List<String> = emptyList(),
    val forecastSignals: List<String> = emptyList(),
    val fundamentalsFactors: List<ScoreFactor> = emptyList(),
    val technicalFactors: List<ScoreFactor> = emptyList(),
    val forecastFactors: List<ScoreFactor> = emptyList(),
    val regimeStatus: RegimeScoreStatus = RegimeScoreStatus.NotApplicable,
    val regimeCauses: List<RegimeCause> = emptyList(),
    val regimeSignals: List<String> = emptyList(),
    val regimeUnavailableReason: MarketContextUnavailableReason? = null,
    val companyName: String? = null,
    val rankMovement: RankMovement? = null,
    val valuationChange: ValuationChange? = null,
    val explanation: RowExplanationKind? = null,
    val decisionState: RowDecisionState? = null,
    val quantLensSummary: QuantLensRowSummary? = null,
    val valuationStanceLabel: String? = null,
)

data class DashboardSnapshot(
    val availableProfiles: List<String>,
    val currentProfile: String,
    val trackedSymbols: List<String>,
    val trackedRows: List<TrackedSymbolRow>,
    val watchlistSymbols: List<String>,
    val candidateRows: List<CandidateRow>,
    val opportunityRows: List<OpportunityListRow>,
    val opportunityScoringModel: OpportunityScoringModel,
    /** How these rows were scored. A later snapshot with a different flag is stale. */
    val regimeScoringEnabled: Boolean = ScoringPreferences.DEFAULT_REGIME_ENABLED,
    val issues: List<IssueRecord>,
    val selectedDetail: SymbolDetail?,
    /**
     * Score for the open ticker. The ranked list is a cache of qualified names.
     * An ad-hoc search ticker is scored here even when it is not in that list.
     */
    val selectedScoreRow: OpportunityListRow? = null,
    val selectedCharts: Map<ChartRange, List<HistoricalCandle>>,
    val selectedHistory: List<SymbolRevision>,
    val selectedAlerts: List<AlertEvent>,
    val selectedQuantLens: QuantLensReport? = null,
    val detailNotice: DashboardNotice? = null,
    val lastUpdatedAtEpochSeconds: Long?,
    val startupPhase: DashboardStartupPhase,
    val refreshCompletedSymbols: Int,
    val refreshTargetSymbols: Int,
    val statusMessage: String?,
    val estimatesNotice: DashboardNotice? = null,
    val screenData: ProjectedDashboardData = ProjectedDashboardData.empty(),
    val replayBackingCharts: Map<ChartRange, List<HistoricalCandle>> = emptyMap(),
    val marketRegime: MarketRegime? = null,
    val marketReadStatus: MarketReadStatus = MarketReadStatus.Pending,
    val planBoard: PlanBoard = PlanBoard.EMPTY,
    val planBoardProfile: PlanBoard = PlanBoard.EMPTY,
    val leftoverBoard: PlanBoard = PlanBoard.EMPTY,
)

data class TickerSearchSuggestion(
    val symbol: String,
    val companyName: String? = null,
    val profiles: List<String> = emptyList(),
    val inCurrentProfile: Boolean = false,
    val exchange: String? = null,
    val isRemote: Boolean = false,
) {
    init {
        require(symbol.isNotBlank()) { "Ticker search suggestion symbol is required." }
    }
}

private const val SIGNIFICANT_VALUATION_MOVE_BPS = 500
private const val MAJOR_VALUATION_MOVE_BPS = 2_000
