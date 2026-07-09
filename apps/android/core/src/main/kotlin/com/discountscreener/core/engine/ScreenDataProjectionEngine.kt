package com.discountscreener.core.engine

import com.discountscreener.core.model.CandidateRow
import com.discountscreener.core.model.ChartRangeSummary
import com.discountscreener.core.model.ComputationArea
import com.discountscreener.core.model.ComputationResult
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.DcfSource
import com.discountscreener.core.model.IndexEstimatesReport
import com.discountscreener.core.model.OpportunityScoringModel
import com.discountscreener.core.model.ProviderDecisionReasonCode
import com.discountscreener.core.model.ProviderState
import com.discountscreener.core.model.ProjectedAnalystTargetStatistic
import com.discountscreener.core.model.ProjectedConfidence
import com.discountscreener.core.model.ProjectedDashboardData
import com.discountscreener.core.model.ProjectedDetailData
import com.discountscreener.core.model.ProjectedEstimatesData
import com.discountscreener.core.model.ProjectedFairValueAnchor
import com.discountscreener.core.model.ProjectedFairValueRole
import com.discountscreener.core.model.ProjectedOpportunityDecisionFacts
import com.discountscreener.core.model.ProjectedOpportunityRow
import com.discountscreener.core.model.ProjectedProviderCategory
import com.discountscreener.core.model.ProjectedProviderState
import com.discountscreener.core.model.ProjectedProvenanceState
import com.discountscreener.core.model.ProjectedRowDecision
import com.discountscreener.core.model.ProjectedRowExplanation
import com.discountscreener.core.model.ProjectedRowExplanationKind
import com.discountscreener.core.model.ProjectedRowFreshness
import com.discountscreener.core.model.ProjectedTrackedRow
import com.discountscreener.core.model.ProjectedTrustSignal
import com.discountscreener.core.model.ProjectedTrustSignalKind
import com.discountscreener.core.model.ProjectedValuationAnchor
import com.discountscreener.core.model.ProjectedValuationAnchorKind
import com.discountscreener.core.model.ProjectionSymbolState
import com.discountscreener.core.model.QualificationStatus
import com.discountscreener.core.model.QuantLensFreshnessQualifier
import com.discountscreener.core.model.QuantLensLensId
import com.discountscreener.core.model.QuantLensLensRowState
import com.discountscreener.core.model.QuantLensPrimaryStatus
import com.discountscreener.core.model.QuantLensReasonCode
import com.discountscreener.core.model.QuantLensRowLabel
import com.discountscreener.core.model.QuantLensRowSummary
import com.discountscreener.core.model.ResolverState
import com.discountscreener.core.model.ScreenDataProjectionRequest
import com.discountscreener.core.model.SymbolDetail
import com.discountscreener.core.model.SymbolRangeKey
import com.discountscreener.core.model.captureComputationResult
import com.discountscreener.core.model.lowerForProviderUncertainty
import com.discountscreener.core.model.toProjectedConfidence
import kotlin.math.roundToInt

private const val QUANT_LENS_ROW_UPSIDE_MIN_BPS = -100_000
private const val QUANT_LENS_ROW_UPSIDE_MAX_BPS = 100_000

class ScreenDataProjectionEngine {
    fun project(request: ScreenDataProjectionRequest): ComputationResult<ProjectedDashboardData> = captureComputationResult(
        area = ComputationArea.Projection,
        code = "screen_projection_failed",
        symbol = request.route.selectedSymbol,
        message = { error -> error.message ?: "Screen data projection failed." },
    ) {
        requireValidSymbols(request)

        var trackedRows = request.trackedSymbols.mapIndexed { index, symbol ->
            projectedTrackedRow(symbol, index + 1, request)
        }.filter { row ->
            rowMatchesFilter(
                symbol = row.symbol,
                companyName = row.detail?.companyName,
                request = request,
            )
        }
        var trackedSymbolSet = request.trackedSymbols.toSet()
        var watchlistRows = request.watchlistSymbols
            .filterNot { symbol -> symbol in trackedSymbolSet }
            .sorted()
            .map { symbol -> projectedTrackedRow(symbol, null, request) }
            .filter { row ->
                rowMatchesFilter(
                    symbol = row.symbol,
                    companyName = row.detail?.companyName,
                    request = request,
                )
            }
        var opportunityRows = request.candidateRows.map { row ->
            projectedOpportunityRow(row, request)
        }.filter { row ->
            rowMatchesFilter(
                symbol = row.symbol,
                companyName = row.candidateRow.companyName,
                request = request,
            )
        }
        var selectedDetail = projectedDetail(request)
        var estimatesReport = estimatesReport(request)
        var category = providerCategory(request)

        ProjectedDashboardData(
            trackedRows = trackedRows,
            watchlistRows = watchlistRows,
            opportunityRows = opportunityRows,
            candidateRows = request.candidateRows,
            selectedDetail = selectedDetail,
            estimates = ProjectedEstimatesData(report = estimatesReport),
            providerState = ProjectedProviderState(
                category = category,
                computedAtEpochSeconds = request.nowEpochSeconds,
                issues = request.issues,
                affectedSymbols = affectedSymbols(request),
            ),
        )
    }

    private fun rowMatchesFilter(
        symbol: String,
        companyName: String?,
        request: ScreenDataProjectionRequest,
    ): Boolean {
        var filter = request.route.filter
        var query = filter.query.trim()
        var queryMatches = query.isBlank() ||
            symbol.contains(query, ignoreCase = true) ||
            companyName?.contains(query, ignoreCase = true) == true
        var watchlistMatches = !filter.watchlistOnly || symbol in request.watchlistSymbols
        return queryMatches && watchlistMatches
    }

    private fun projectedTrackedRow(
        symbol: String,
        currentRank: Int?,
        request: ScreenDataProjectionRequest,
    ): ProjectedTrackedRow {
        var detail = request.detailsBySymbol[symbol]
        var dcfAnalysis = request.dcfBySymbol[symbol]
        var symbolState = request.symbolStateBySymbol[symbol]
        var statistic = request.analystTargetStatisticBySymbol[symbol]
        var anchor = fairValueAnchor(detail, dcfAnalysis, statistic)
        var freshness = rowFreshness(symbol, detail != null, dcfAnalysis, request)
        var confidence = projectedConfidence(detail?.confidence, dcfAnalysis, symbolState)
        var upsideBps = projectedUpsideBps(detail?.marketPriceCents, anchor.valueCents)
        var blockingIssue = hasBlockingProviderIssue(symbol, detail != null, request)
        var currentTrustSignal = trustSignal(detail, anchor, dcfAnalysis, blockingIssue)
        return ProjectedTrackedRow(
            symbol = symbol,
            detail = detail,
            marketPriceCents = detail?.marketPriceCents,
            fairValueAnchor = anchor,
            upsideBps = upsideBps,
            confidence = confidence,
            freshness = freshness,
            trustSignal = currentTrustSignal,
            decision = rowDecision(
                marketPriceCents = detail?.marketPriceCents,
                anchor = anchor,
                confidence = confidence,
                freshness = freshness,
                qualification = detail?.qualification,
                trustSignal = currentTrustSignal,
            ),
            explanation = rowExplanation(symbol, currentRank, anchor, request),
            quantLensSummary = quantLensRowSummary(symbol, detail, dcfAnalysis),
        )
    }

    private fun projectedOpportunityRow(
        row: CandidateRow,
        request: ScreenDataProjectionRequest,
    ): ProjectedOpportunityRow {
        var detail = request.detailsBySymbol[row.symbol]
        var dcfAnalysis = request.dcfBySymbol[row.symbol]
        var symbolState = request.symbolStateBySymbol[row.symbol]
        var statistic = request.analystTargetStatisticBySymbol[row.symbol]
        var anchor = fairValueAnchor(detail, dcfAnalysis, statistic, row.intrinsicValueCents)
        var freshness = rowFreshness(row.symbol, true, dcfAnalysis, request)
        var confidence = projectedConfidence(detail?.confidence ?: row.confidence, dcfAnalysis, symbolState)
        var upsideBps = projectedUpsideBps(row.marketPriceCents, anchor.valueCents)
        var blockingIssue = hasBlockingProviderIssue(row.symbol, true, request)
        var currentTrustSignal = trustSignal(detail, anchor, dcfAnalysis, blockingIssue)
        var decisionFacts = request.opportunityDecisionFactsBySymbol[row.symbol] ?: ProjectedOpportunityDecisionFacts()
        return ProjectedOpportunityRow(
            symbol = row.symbol,
            candidateRow = row,
            fairValueAnchor = anchor,
            upsideBps = upsideBps,
            confidence = confidence,
            freshness = freshness,
            trustSignal = currentTrustSignal,
            decision = rowDecision(
                marketPriceCents = row.marketPriceCents,
                anchor = anchor,
                confidence = confidence,
                freshness = freshness,
                trustSignal = currentTrustSignal,
                opportunityDecisionFacts = decisionFacts,
                scoringModel = request.route.opportunityScoringModel,
            ),
            quantLensSummary = quantLensRowSummary(row.symbol, detail, dcfAnalysis),
        )
    }

    private fun projectedDetail(request: ScreenDataProjectionRequest): ProjectedDetailData? {
        var symbol = request.route.selectedSymbol ?: return null
        var detail = request.detailsBySymbol[symbol] ?: return null
        var dcfAnalysis = request.dcfBySymbol[symbol]
        var statistic = request.analystTargetStatisticBySymbol[symbol]
        var anchor = fairValueAnchor(detail, dcfAnalysis, statistic)
        var key = SymbolRangeKey(symbol = symbol, range = request.route.selectedRange)
        var candles = request.chartCandles[key].orEmpty()
        return ProjectedDetailData(
            symbol = symbol,
            detail = detail,
            fairValueAnchor = anchor,
            valuationAnchors = valuationAnchors(detail, dcfAnalysis, anchor),
            chart = ChartAnalysis.buildProjectedChartData(
                range = request.route.selectedRange,
                candles = candles,
                capturedAtEpochSeconds = request.nowEpochSeconds,
                replayOffset = request.route.replayOffset,
                volumeProfileBinCount = request.route.volumeProfileBinCount,
                summary = chartSummary(request, symbol),
            ),
            revisions = request.revisionsBySymbol[symbol].orEmpty(),
            alerts = request.alertsBySymbol[symbol].orEmpty(),
        )
    }

    private fun chartSummary(
        request: ScreenDataProjectionRequest,
        symbol: String,
    ): ChartRangeSummary? = request.chartSummariesBySymbol[symbol]?.get(request.route.selectedRange)

    private fun estimatesReport(request: ScreenDataProjectionRequest): IndexEstimatesReport {
        var details = request.trackedSymbols.mapNotNull { symbol -> request.detailsBySymbol[symbol] }
        return IndexEstimatesEngine.compute(
            symbols = details,
            dcfBySymbol = request.dcfBySymbol,
            profileName = request.profile.currentProfile,
            nowEpochSeconds = request.nowEpochSeconds,
        )
    }

    private fun projectedConfidence(
        confidence: ConfidenceBand?,
        dcfAnalysis: DcfAnalysis?,
        symbolState: ProjectionSymbolState?,
    ): ProjectedConfidence {
        var projected = confidence?.toProjectedConfidence() ?: ProjectedConfidence.Unavailable
        return if (shouldLowerConfidence(dcfAnalysis, symbolState)) {
            projected.lowerForProviderUncertainty()
        } else {
            projected
        }
    }

    private fun shouldLowerConfidence(
        dcfAnalysis: DcfAnalysis?,
        symbolState: ProjectionSymbolState?,
    ): Boolean = dcfAnalysis?.resolverState == ResolverState.ProviderUncertain ||
        dcfAnalysis?.resolverState == ResolverState.RestoredOnly ||
        dcfAnalysis?.let(::isSourceFreeDcf) == true ||
        symbolState?.stale == true ||
        symbolState?.providerCategory == ProjectedProviderCategory.Stale ||
        symbolState?.providerCategory == ProjectedProviderCategory.ParseUncertain ||
        symbolState?.providerCategory == ProjectedProviderCategory.SourceUnknown ||
        symbolState?.provenanceState == ProjectedProvenanceState.Stale ||
        symbolState?.provenanceState == ProjectedProvenanceState.ParseUncertain ||
        symbolState?.provenanceState == ProjectedProvenanceState.SourceUnknown

    private fun fairValueAnchor(
        detail: SymbolDetail?,
        dcfAnalysis: DcfAnalysis?,
        analystTargetStatistic: ProjectedAnalystTargetStatistic?,
        fallbackIntrinsicValueCents: Long? = null,
    ): ProjectedFairValueAnchor {
        var weightedTarget = detail?.weightedExternalSignalFairValueCents
        if (weightedTarget != null && weightedTarget > 0L) {
            return ProjectedFairValueAnchor.analyst(
                valueCents = weightedTarget,
                role = ProjectedFairValueRole.AnalystWeightedTarget,
                compactLabel = "Analyst weighted",
                sourceLabel = "Weighted target",
            )
        }
        var consensusTarget = detail?.externalSignalFairValueCents
        if (consensusTarget != null && consensusTarget > 0L) {
            return analystConsensusAnchor(consensusTarget, analystTargetStatistic)
        }
        if (dcfAnalysis != null) {
            if (dcfAnalysis.resolverState == ResolverState.ProviderUncertain && dcfAnalysis.baseIntrinsicValueCents <= 0L) {
                return ProjectedFairValueAnchor.unavailable()
            }
            if (dcfAnalysis.baseIntrinsicValueCents > 0L) {
                return ProjectedFairValueAnchor.model(
                    valueCents = dcfAnalysis.baseIntrinsicValueCents,
                    sourceLabel = dcfSourceLabel(dcfAnalysis),
                    role = dcfRole(dcfAnalysis),
                    compactLabel = dcfCompactLabel(dcfAnalysis),
                    provenanceState = dcfProvenanceState(dcfAnalysis),
                    trustReason = dcfTrustReason(dcfAnalysis),
                )
            }
        }
        var intrinsicValue = detail?.intrinsicValueCents ?: fallbackIntrinsicValueCents
        if (intrinsicValue != null && intrinsicValue > 0L) {
            return ProjectedFairValueAnchor.model(
                valueCents = intrinsicValue,
                sourceLabel = "Intrinsic model",
                role = ProjectedFairValueRole.IntrinsicModel,
                compactLabel = "Intrinsic model",
                provenanceState = ProjectedProvenanceState.Live,
            )
        }
        return ProjectedFairValueAnchor.unavailable()
    }

    private fun analystConsensusAnchor(
        valueCents: Long,
        analystTargetStatistic: ProjectedAnalystTargetStatistic?,
    ): ProjectedFairValueAnchor = when (analystTargetStatistic ?: ProjectedAnalystTargetStatistic.Consensus) {
        ProjectedAnalystTargetStatistic.Median -> ProjectedFairValueAnchor.analyst(
            valueCents = valueCents,
            role = ProjectedFairValueRole.AnalystMedianTarget,
            compactLabel = "Analyst median",
            sourceLabel = "Median target",
        )
        ProjectedAnalystTargetStatistic.Mean -> ProjectedFairValueAnchor.analyst(
            valueCents = valueCents,
            role = ProjectedFairValueRole.AnalystMeanTarget,
            compactLabel = "Analyst mean",
            sourceLabel = "Mean target",
        )
        ProjectedAnalystTargetStatistic.Consensus -> ProjectedFairValueAnchor.analyst(
            valueCents = valueCents,
            role = ProjectedFairValueRole.AnalystConsensusTarget,
            compactLabel = "Analyst consensus",
            sourceLabel = "Consensus target",
        )
    }

    private fun valuationAnchors(
        detail: SymbolDetail,
        dcfAnalysis: DcfAnalysis?,
        primaryAnchor: ProjectedFairValueAnchor,
    ): List<ProjectedValuationAnchor> {
        var anchors = mutableListOf<ProjectedValuationAnchor>()
        primaryAnchor.valueCents?.let { valueCents ->
            anchors.add(
                ProjectedValuationAnchor(
                    kind = ProjectedValuationAnchorKind.PrimaryFairValue,
                    valueCents = valueCents,
                    label = primaryAnchor.displayLabel,
                    sourceLabel = primaryAnchor.sourceLabel,
                    provenanceState = primaryAnchor.provenanceState,
                ),
            )
        }
        addAnalystReferenceAnchor(anchors, detail.externalSignalLowFairValueCents, ProjectedValuationAnchorKind.AnalystLowTarget, "Low target")
        addAnalystReferenceAnchor(anchors, detail.externalSignalHighFairValueCents, ProjectedValuationAnchorKind.AnalystHighTarget, "High target")
        addDcfReferenceAnchors(anchors, dcfAnalysis)
        if (detail.intrinsicValueCents > 0L && primaryAnchor.role != ProjectedFairValueRole.IntrinsicModel) {
            anchors.add(
                ProjectedValuationAnchor(
                    kind = ProjectedValuationAnchorKind.IntrinsicModel,
                    valueCents = detail.intrinsicValueCents,
                    label = "Intrinsic model",
                    sourceLabel = "Intrinsic model",
                    provenanceState = ProjectedProvenanceState.Live,
                ),
            )
        }
        return anchors
    }

    private fun addAnalystReferenceAnchor(
        anchors: MutableList<ProjectedValuationAnchor>,
        valueCents: Long?,
        kind: ProjectedValuationAnchorKind,
        label: String,
    ) {
        if (valueCents != null && valueCents > 0L) {
            anchors.add(
                ProjectedValuationAnchor(
                    kind = kind,
                    valueCents = valueCents,
                    label = label,
                    sourceLabel = label,
                    provenanceState = ProjectedProvenanceState.Live,
                ),
            )
        }
    }

    private fun addDcfReferenceAnchors(
        anchors: MutableList<ProjectedValuationAnchor>,
        dcfAnalysis: DcfAnalysis?,
    ) {
        dcfAnalysis ?: return
        addDcfReferenceAnchor(anchors, dcfAnalysis.bearIntrinsicValueCents, ProjectedValuationAnchorKind.DcfBearModel, "DCF bear", dcfAnalysis)
        addDcfReferenceAnchor(anchors, dcfAnalysis.baseIntrinsicValueCents, ProjectedValuationAnchorKind.DcfBaseModel, "DCF base", dcfAnalysis)
        addDcfReferenceAnchor(anchors, dcfAnalysis.bullIntrinsicValueCents, ProjectedValuationAnchorKind.DcfBullModel, "DCF bull", dcfAnalysis)
    }

    private fun addDcfReferenceAnchor(
        anchors: MutableList<ProjectedValuationAnchor>,
        valueCents: Long,
        kind: ProjectedValuationAnchorKind,
        label: String,
        dcfAnalysis: DcfAnalysis,
    ) {
        if (valueCents > 0L) {
            anchors.add(
                ProjectedValuationAnchor(
                    kind = kind,
                    valueCents = valueCents,
                    label = label,
                    sourceLabel = dcfSourceLabel(dcfAnalysis),
                    provenanceState = dcfProvenanceState(dcfAnalysis),
                ),
            )
        }
    }

    private fun dcfRole(analysis: DcfAnalysis): ProjectedFairValueRole = when (analysis.resolverState) {
        ResolverState.ProviderUncertain -> ProjectedFairValueRole.UncertainDcfModel
        ResolverState.RestoredOnly -> if (isSourceFreeDcf(analysis)) {
            ProjectedFairValueRole.SourceFreeModel
        } else {
            ProjectedFairValueRole.RestoredDcfModel
        }
        else -> if (isSourceFreeDcf(analysis)) {
            ProjectedFairValueRole.SourceFreeModel
        } else {
            ProjectedFairValueRole.DcfBaseModel
        }
    }

    private fun dcfCompactLabel(analysis: DcfAnalysis): String = when (analysis.resolverState) {
        ResolverState.RestoredOnly -> "Saved model"
        else -> "DCF model"
    }

    private fun dcfSourceLabel(analysis: DcfAnalysis): String = when (analysis.resolverState) {
        ResolverState.ProviderUncertain -> "DCF base - source uncertain"
        ResolverState.RestoredOnly -> if (isSourceFreeDcf(analysis)) {
            "Source unknown - saved model"
        } else {
            "Saved DCF base"
        }
        else -> if (isSourceFreeDcf(analysis)) {
            "Source unknown - saved model"
        } else {
            when (analysis.source) {
                DcfSource.YahooFinance -> "DCF base - Yahoo Finance"
                DcfSource.SecEdgar -> "DCF base - SEC EDGAR"
                DcfSource.Derived -> "DCF base - derived"
                DcfSource.Restored -> "Saved DCF base"
                DcfSource.Unknown, null -> "Source unknown - saved model"
            }
        }
    }

    private fun dcfProvenanceState(analysis: DcfAnalysis): ProjectedProvenanceState = when (analysis.resolverState) {
        ResolverState.ProviderUncertain -> ProjectedProvenanceState.ProviderUncertain
        ResolverState.RestoredOnly -> if (isSourceFreeDcf(analysis)) {
            ProjectedProvenanceState.SourceUnknown
        } else {
            ProjectedProvenanceState.Restored
        }
        ResolverState.NotEligible -> ProjectedProvenanceState.NotEligible
        ResolverState.Unavailable -> ProjectedProvenanceState.Unavailable
        ResolverState.Cancelled -> ProjectedProvenanceState.Superseded
        ResolverState.Selected -> if (isSourceFreeDcf(analysis)) {
            ProjectedProvenanceState.SourceUnknown
        } else {
            ProjectedProvenanceState.Live
        }
    }

    private fun dcfTrustReason(analysis: DcfAnalysis): String? = when {
        analysis.resolverState == ResolverState.ProviderUncertain -> "Source uncertain"
        isSourceFreeDcf(analysis) -> "Source unknown"
        else -> null
    }

    private fun trustSignal(
        detail: SymbolDetail?,
        anchor: ProjectedFairValueAnchor,
        analysis: DcfAnalysis?,
        blockingIssue: Boolean,
    ): ProjectedTrustSignal? {
        if (blockingIssue) return null
        if (analysis?.resolverState == ResolverState.ProviderUncertain || anchor.provenanceState == ProjectedProvenanceState.ProviderUncertain) {
            return ProjectedTrustSignal(
                kind = ProjectedTrustSignalKind.SourceUncertain,
                label = "Source uncertain",
            )
        }
        if (anchor.provenanceState == ProjectedProvenanceState.SourceUnknown || analysis?.let(::isSourceFreeDcf) == true) {
            return ProjectedTrustSignal(
                kind = ProjectedTrustSignalKind.SourceUnknown,
                label = "Source unknown",
            )
        }
        if (isModelRole(anchor.role)) {
            return if (!hasValidAnalystTargetFields(detail)) {
                ProjectedTrustSignal(
                    kind = ProjectedTrustSignalKind.NoAnalystTarget,
                    label = "No analyst target",
                )
            } else {
                ProjectedTrustSignal(
                    kind = ProjectedTrustSignalKind.ModelValue,
                    label = "Model value",
                )
            }
        }
        if (isAnalystRole(anchor.role)) {
            var coverageCount = analystCoverageCount(detail, anchor.role)
            return when {
                coverageCount != null && coverageCount in 1..2 -> ProjectedTrustSignal(
                    kind = ProjectedTrustSignalKind.ThinCoverage,
                    label = "Thin coverage",
                )
                coverageCount == null || coverageCount <= 0 -> ProjectedTrustSignal(
                    kind = ProjectedTrustSignalKind.CoverageUnknown,
                    label = "Coverage unknown",
                )
                else -> null
            }
        }
        return null
    }

    private fun rowFreshness(
        symbol: String,
        hasRowData: Boolean,
        dcfAnalysis: DcfAnalysis?,
        request: ScreenDataProjectionRequest,
    ): ProjectedRowFreshness {
        var symbolState = request.symbolStateBySymbol[symbol]
        var category = symbolState?.providerCategory ?: dcfAnalysis?.let(::providerCategory) ?: ProjectedProviderCategory.Live
        var provenanceState = symbolState?.provenanceState ?: dcfAnalysis?.let(::dcfProvenanceState) ?: ProjectedProvenanceState.Live
        if (!hasRowData && hasBlockingProviderIssue(symbol, hasRowData, request)) return ProjectedRowFreshness.Issue
        if (category == ProjectedProviderCategory.Unavailable || provenanceState == ProjectedProvenanceState.Unavailable) {
            return if (hasRowData) ProjectedRowFreshness.Stale else ProjectedRowFreshness.Issue
        }
        if (category == ProjectedProviderCategory.Restored ||
            category == ProjectedProviderCategory.SourceUnknown ||
            provenanceState == ProjectedProvenanceState.Restored ||
            provenanceState == ProjectedProvenanceState.SourceUnknown
        ) {
            return ProjectedRowFreshness.Restored
        }
        if (symbolState?.stale == true ||
            category == ProjectedProviderCategory.Stale ||
            provenanceState == ProjectedProvenanceState.Stale
        ) {
            return ProjectedRowFreshness.Stale
        }
        return if (hasRowData) ProjectedRowFreshness.Updated else ProjectedRowFreshness.Loading
    }

    private fun rowDecision(
        marketPriceCents: Long?,
        anchor: ProjectedFairValueAnchor,
        confidence: ProjectedConfidence,
        freshness: ProjectedRowFreshness,
        qualification: QualificationStatus? = null,
        trustSignal: ProjectedTrustSignal? = null,
        opportunityDecisionFacts: ProjectedOpportunityDecisionFacts? = null,
        scoringModel: OpportunityScoringModel = OpportunityScoringModel.Legacy,
    ): ProjectedRowDecision? {
        if (freshness != ProjectedRowFreshness.Updated) return null
        var upsideBps = projectedUpsideBps(marketPriceCents, anchor.valueCents) ?: return null
        if (opportunityDecisionFacts != null) {
            return opportunityDecision(
                upsideBps = upsideBps,
                confidence = confidence,
                trustSignal = trustSignal,
                facts = opportunityDecisionFacts,
                scoringModel = scoringModel,
            )
        }
        return trackedDecision(upsideBps, qualification, confidence, trustSignal)
    }

    private fun trackedDecision(
        upsideBps: Int,
        qualification: QualificationStatus?,
        confidence: ProjectedConfidence,
        trustSignal: ProjectedTrustSignal?,
    ): ProjectedRowDecision? = when {
        qualification == QualificationStatus.Unprofitable -> ProjectedRowDecision.Avoid
        upsideBps <= 0 -> ProjectedRowDecision.Avoid
        trustSignal != null -> ProjectedRowDecision.Watch
        qualification == QualificationStatus.Qualified && confidence == ProjectedConfidence.High -> ProjectedRowDecision.Act
        else -> ProjectedRowDecision.Watch
    }

    private fun opportunityDecision(
        upsideBps: Int,
        confidence: ProjectedConfidence,
        trustSignal: ProjectedTrustSignal?,
        facts: ProjectedOpportunityDecisionFacts,
        scoringModel: OpportunityScoringModel,
    ): ProjectedRowDecision? {
        var compositeScore = facts.compositeScore
        val avoidBelow = OpportunityEngine.avoidBelowScore(scoringModel)
        val actAtOrAbove = OpportunityEngine.actAtOrAboveScore(scoringModel)
        return when {
            confidence == ProjectedConfidence.Unavailable -> null
            confidence == ProjectedConfidence.Low -> ProjectedRowDecision.Avoid
            upsideBps <= 0 -> ProjectedRowDecision.Avoid
            compositeScore != null && compositeScore < avoidBelow -> ProjectedRowDecision.Avoid
            trustSignal != null -> ProjectedRowDecision.Watch
            confidence == ProjectedConfidence.High && (compositeScore ?: actAtOrAbove) >= actAtOrAbove -> ProjectedRowDecision.Act
            else -> ProjectedRowDecision.Watch
        }
    }

    private fun rowExplanation(
        symbol: String,
        currentRank: Int?,
        anchor: ProjectedFairValueAnchor,
        request: ScreenDataProjectionRequest,
    ): ProjectedRowExplanation? {
        var previousFairValue = request.baselines.previousFairValueCentsBySymbol[symbol]
        var previousRank = request.baselines.previousRankBySymbol[symbol]
        if (previousFairValue == null && previousRank == null) return null
        var targetChanged = previousFairValue != null && anchor.valueCents != null && previousFairValue != anchor.valueCents
        var rankChanged = previousRank != null && currentRank != null && previousRank != currentRank
        var kind = when {
            targetChanged && rankChanged -> ProjectedRowExplanationKind.CombinedMove
            targetChanged -> ProjectedRowExplanationKind.TargetChanged
            rankChanged -> ProjectedRowExplanationKind.RelativeReRank
            else -> ProjectedRowExplanationKind.NoMeaningfulChange
        }
        return ProjectedRowExplanation(kind = kind)
    }

    private fun quantLensRowSummary(
        symbol: String,
        detail: SymbolDetail?,
        dcfAnalysis: DcfAnalysis?,
    ): QuantLensRowSummary? {
        detail ?: return null
        var evState = expectedValueRangeState(detail, dcfAnalysis) ?: return null
        return QuantLensRowSummary(
            symbol = symbol,
            fingerprint = quantLensFingerprint(symbol, dcfAnalysis),
            lensStates = listOf(evState),
        )
    }

    private fun expectedValueRangeState(
        detail: SymbolDetail,
        dcfAnalysis: DcfAnalysis?,
    ): QuantLensLensRowState? {
        if (dcfAnalysis != null && isLiveCompleteDcf(dcfAnalysis)) {
            return QuantLensLensRowState(
                lensId = QuantLensLensId.ExpectedValueRange,
                primaryStatus = QuantLensPrimaryStatus.Available,
                band = "ScenarioWeighted",
                label = QuantLensRowLabel.EvRange,
                reasonCodes = listOf(QuantLensReasonCode.CompleteScenarioAnchors),
                evLowUpsideBps = projectedUpsideBps(detail.marketPriceCents, dcfAnalysis.bearIntrinsicValueCents),
                evHighUpsideBps = projectedUpsideBps(detail.marketPriceCents, dcfAnalysis.bullIntrinsicValueCents),
            )
        }
        if (dcfAnalysis?.resolverState == ResolverState.ProviderUncertain) {
            return QuantLensLensRowState(
                lensId = QuantLensLensId.ExpectedValueRange,
                primaryStatus = QuantLensPrimaryStatus.Unavailable,
                band = "Unavailable",
                label = QuantLensRowLabel.EvUnavailable,
                freshnessQualifier = QuantLensFreshnessQualifier.ProviderUncertain,
                reasonCodes = listOf(QuantLensReasonCode.MissingScenarioAnchors),
            )
        }
        if (detail.externalSignalLowFairValueCents != null && detail.externalSignalHighFairValueCents != null) {
            return QuantLensLensRowState(
                lensId = QuantLensLensId.ExpectedValueRange,
                primaryStatus = QuantLensPrimaryStatus.Partial,
                band = "ReferenceOnly",
                label = QuantLensRowLabel.EvRange,
                reasonCodes = listOf(QuantLensReasonCode.MissingScenarioAnchors),
                evLowUpsideBps = projectedUpsideBps(detail.marketPriceCents, detail.externalSignalLowFairValueCents),
                evHighUpsideBps = projectedUpsideBps(detail.marketPriceCents, detail.externalSignalHighFairValueCents),
            )
        }
        return null
    }

    private fun quantLensFingerprint(
        symbol: String,
        dcfAnalysis: DcfAnalysis?,
    ): String = listOf(
        symbol,
        dcfAnalysis?.resolverState?.name.orEmpty(),
        dcfAnalysis?.source?.name.orEmpty(),
        dcfAnalysis?.decisionFingerprint.orEmpty(),
    ).joinToString(separator = ":")

    private fun projectedUpsideBps(
        marketPriceCents: Long?,
        fairValueCents: Long?,
    ): Int? {
        if (marketPriceCents == null || fairValueCents == null) return null
        if (marketPriceCents <= 0L || fairValueCents <= 0L) return null
        return ((fairValueCents.toDouble() / marketPriceCents.toDouble() - 1.0) * 10_000.0)
            .roundToInt()
            .coerceIn(QUANT_LENS_ROW_UPSIDE_MIN_BPS, QUANT_LENS_ROW_UPSIDE_MAX_BPS)
    }

    private fun providerCategory(request: ScreenDataProjectionRequest): ProjectedProviderCategory {
        var categories = mutableListOf<ProjectedProviderCategory>()
        categories.addAll(request.symbolStateBySymbol.values.map { state -> state.providerCategory })
        categories.addAll(request.dcfBySymbol.values.map { analysis -> providerCategory(analysis) })
        if (request.issues.any { issue -> issue.active }) {
            categories.add(ProjectedProviderCategory.Unavailable)
        }
        return if (categories.isEmpty() && request.trackedSymbols.isEmpty() && request.detailsBySymbol.isEmpty()) {
            ProjectedProviderCategory.Unavailable
        } else {
            highestPriorityCategory(categories)
        }
    }

    private fun providerCategory(analysis: DcfAnalysis): ProjectedProviderCategory {
        if (analysis.providerReasons.any { reason -> isDisabledReason(reason.code) }) return ProjectedProviderCategory.Disabled
        if (analysis.providerReasons.any { reason -> isSupersededReason(reason.code) }) return ProjectedProviderCategory.Superseded
        if (analysis.resolverState == ResolverState.ProviderUncertain) return ProjectedProviderCategory.ProviderUncertain
        if (analysis.provenance.providerState == ProviderState.ParseUncertain) return ProjectedProviderCategory.ParseUncertain
        if (analysis.provenance.providerState == ProviderState.ProviderDisabled) return ProjectedProviderCategory.Disabled
        if (isSourceFreeDcf(analysis)) return ProjectedProviderCategory.SourceUnknown
        return when (analysis.resolverState) {
            ResolverState.Selected -> providerCategory(analysis.provenance.providerState)
            ResolverState.Unavailable -> ProjectedProviderCategory.Unavailable
            ResolverState.NotEligible -> ProjectedProviderCategory.NotEligible
            ResolverState.RestoredOnly -> ProjectedProviderCategory.Restored
            ResolverState.ProviderUncertain -> ProjectedProviderCategory.ProviderUncertain
            ResolverState.Cancelled -> ProjectedProviderCategory.Superseded
        }
    }

    private fun providerCategory(providerState: ProviderState): ProjectedProviderCategory = when (providerState) {
        ProviderState.Live -> ProjectedProviderCategory.Live
        ProviderState.RestoredOnly -> ProjectedProviderCategory.Restored
        ProviderState.Stale -> ProjectedProviderCategory.Stale
        ProviderState.Unavailable,
        ProviderState.Rejected -> ProjectedProviderCategory.Unavailable
        ProviderState.NotEligible,
        ProviderState.UnsupportedSymbol -> ProjectedProviderCategory.NotEligible
        ProviderState.ProviderDisabled -> ProjectedProviderCategory.Disabled
        ProviderState.ParseUncertain -> ProjectedProviderCategory.ParseUncertain
        ProviderState.ProviderUncertain -> ProjectedProviderCategory.ProviderUncertain
        ProviderState.Cancelled -> ProjectedProviderCategory.Superseded
    }

    private fun highestPriorityCategory(categories: List<ProjectedProviderCategory>): ProjectedProviderCategory {
        var category = categories.minByOrNull { candidate -> providerCategoryPriority(candidate) }
        return category ?: ProjectedProviderCategory.Live
    }

    private fun providerCategoryPriority(category: ProjectedProviderCategory): Int = when (category) {
        ProjectedProviderCategory.ProviderUncertain -> 0
        ProjectedProviderCategory.ParseUncertain -> 1
        ProjectedProviderCategory.SourceUnknown -> 2
        ProjectedProviderCategory.Unavailable -> 3
        ProjectedProviderCategory.Disabled -> 4
        ProjectedProviderCategory.NotEligible -> 5
        ProjectedProviderCategory.Stale -> 6
        ProjectedProviderCategory.Restored -> 7
        ProjectedProviderCategory.Superseded -> 8
        ProjectedProviderCategory.Live -> 9
    }

    private fun affectedSymbols(request: ScreenDataProjectionRequest): List<String> {
        var affected = mutableSetOf<String>()
        request.symbolStateBySymbol.forEach { entry ->
            if (entry.value.providerCategory != ProjectedProviderCategory.Live) {
                affected.add(entry.key)
            }
        }
        request.dcfBySymbol.forEach { entry ->
            if (providerCategory(entry.value) != ProjectedProviderCategory.Live) {
                affected.add(entry.key)
            }
        }
        return affected.sorted()
    }

    private fun hasBlockingProviderIssue(
        symbol: String,
        hasRowData: Boolean,
        request: ScreenDataProjectionRequest,
    ): Boolean {
        var state = request.symbolStateBySymbol[symbol]
        return !hasRowData && (
            state?.providerCategory == ProjectedProviderCategory.Unavailable ||
                request.issues.any { issue -> issue.active }
            )
    }

    private fun isSourceFreeDcf(analysis: DcfAnalysis): Boolean =
        analysis.source == null ||
            analysis.source == DcfSource.Unknown ||
            analysis.providerReasons.any { reason -> reason.code == ProviderDecisionReasonCode.LegacySourceFreePayload } ||
            analysis.provenance.fallbackReason == ProviderDecisionReasonCode.LegacySourceFreePayload

    private fun isDisabledReason(code: ProviderDecisionReasonCode): Boolean = when (code) {
        ProviderDecisionReasonCode.ProviderDisabled,
        ProviderDecisionReasonCode.ProviderConfigurationAbsent,
        ProviderDecisionReasonCode.NoEnabledProviders,
        ProviderDecisionReasonCode.DesktopSecDeferred -> true
        else -> false
    }

    private fun isSupersededReason(code: ProviderDecisionReasonCode): Boolean = when (code) {
        ProviderDecisionReasonCode.GenerationSuperseded,
        ProviderDecisionReasonCode.Cancelled -> true
        else -> false
    }

    private fun isLiveCompleteDcf(analysis: DcfAnalysis): Boolean =
        analysis.resolverState == ResolverState.Selected &&
            analysis.bearIntrinsicValueCents > 0L &&
            analysis.baseIntrinsicValueCents > 0L &&
            analysis.bullIntrinsicValueCents > 0L

    private fun hasValidAnalystTargetFields(detail: SymbolDetail?): Boolean =
        detail?.weightedExternalSignalFairValueCents?.let { value -> value > 0L } == true ||
            detail?.externalSignalFairValueCents?.let { value -> value > 0L } == true ||
            detail?.externalSignalLowFairValueCents?.let { value -> value > 0L } == true ||
            detail?.externalSignalHighFairValueCents?.let { value -> value > 0L } == true

    private fun analystCoverageCount(
        detail: SymbolDetail?,
        role: ProjectedFairValueRole,
    ): Int? = when (role) {
        ProjectedFairValueRole.AnalystWeightedTarget -> detail?.weightedAnalystCount ?: detail?.analystOpinionCount
        ProjectedFairValueRole.AnalystMedianTarget,
        ProjectedFairValueRole.AnalystMeanTarget,
        ProjectedFairValueRole.AnalystConsensusTarget,
        ProjectedFairValueRole.AnalystLowTarget,
        ProjectedFairValueRole.AnalystHighTarget -> detail?.analystOpinionCount
        else -> null
    }

    private fun isAnalystRole(role: ProjectedFairValueRole): Boolean = when (role) {
        ProjectedFairValueRole.AnalystWeightedTarget,
        ProjectedFairValueRole.AnalystMedianTarget,
        ProjectedFairValueRole.AnalystMeanTarget,
        ProjectedFairValueRole.AnalystConsensusTarget,
        ProjectedFairValueRole.AnalystLowTarget,
        ProjectedFairValueRole.AnalystHighTarget -> true
        else -> false
    }

    private fun isModelRole(role: ProjectedFairValueRole): Boolean = when (role) {
        ProjectedFairValueRole.DcfBaseModel,
        ProjectedFairValueRole.UncertainDcfModel,
        ProjectedFairValueRole.RestoredDcfModel,
        ProjectedFairValueRole.SourceFreeModel,
        ProjectedFairValueRole.IntrinsicModel -> true
        else -> false
    }

    private fun requireValidSymbols(request: ScreenDataProjectionRequest) {
        require(request.trackedSymbols.none { it.isBlank() }) { "Tracked symbols cannot contain blanks." }
        require(request.watchlistSymbols.none { it.isBlank() }) { "Watchlist symbols cannot contain blanks." }
        require(request.detailsBySymbol.keys.none { it.isBlank() }) { "Detail map symbols cannot contain blanks." }
        require(request.candidateRows.none { it.symbol.isBlank() }) { "Candidate row symbols cannot contain blanks." }
        require(request.chartSummariesBySymbol.keys.none { it.isBlank() }) { "Chart summary symbols cannot contain blanks." }
        require(request.dcfBySymbol.keys.none { it.isBlank() }) { "DCF map symbols cannot contain blanks." }
        require(request.analystTargetStatisticBySymbol.keys.none { it.isBlank() }) { "Analyst target statistic symbols cannot contain blanks." }
        require(request.revisionsBySymbol.keys.none { it.isBlank() }) { "Revision map symbols cannot contain blanks." }
        require(request.alertsBySymbol.keys.none { it.isBlank() }) { "Alert map symbols cannot contain blanks." }
    }
}
