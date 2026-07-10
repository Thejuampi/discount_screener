package com.discountscreener.core.engine

import com.discountscreener.core.model.CandidateRow
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.ComputationArea
import com.discountscreener.core.model.ComputationResult
import com.discountscreener.core.model.ConfidenceBand
import com.discountscreener.core.model.DcfAnalysis
import com.discountscreener.core.model.DcfCoverageStatus
import com.discountscreener.core.model.DcfSource
import com.discountscreener.core.model.EstimateScenario
import com.discountscreener.core.model.ExternalSignalStatus
import com.discountscreener.core.model.FundamentalSnapshot
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.ProjectedChartStatus
import com.discountscreener.core.model.ProjectedConfidence
import com.discountscreener.core.model.ProjectedAnalystTargetStatistic
import com.discountscreener.core.model.ProjectedDashboardData
import com.discountscreener.core.model.ProjectedFairValueAnchor
import com.discountscreener.core.model.ProjectedFairValueLabels
import com.discountscreener.core.model.ProjectedFairValueRole
import com.discountscreener.core.model.ProjectedOpportunityDecisionFacts
import com.discountscreener.core.model.ProjectedProviderCategory
import com.discountscreener.core.model.ProjectedProvenanceState
import com.discountscreener.core.model.ProjectedRowDecision
import com.discountscreener.core.model.ProjectedRowFreshness
import com.discountscreener.core.model.ProjectedTrustSignalKind
import com.discountscreener.core.model.ProjectionProfileFacts
import com.discountscreener.core.model.ProjectionRoute
import com.discountscreener.core.model.ProjectionSymbolState
import com.discountscreener.core.model.QualificationStatus
import com.discountscreener.core.model.QuantLensLensId
import com.discountscreener.core.model.ResolverState
import com.discountscreener.core.model.ScreenDataProjectionRequest
import com.discountscreener.core.model.SymbolDetail
import com.discountscreener.core.model.SymbolRangeKey
import com.discountscreener.core.model.ViewFilter
import com.discountscreener.core.model.WaccFieldSource
import com.discountscreener.core.model.WaccInputProvenance
import com.discountscreener.core.model.lowerForProviderUncertainty
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScreenDataProjectionEngineTest {
    @Test
    fun tracked_row_keeps_discount_and_upside_distinct_for_price_10_fair_12() {
        // $10 → $12: upside 20%, discount ≈ 16.67%
        val result = projectSingleSymbol(
            symbol = "ACME",
            detail = detail("ACME", marketPriceCents = 1_000L, intrinsicValueCents = 1_200L, confidence = ConfidenceBand.High),
        )
        val row = result.trackedRows.single()
        assertEquals(1_666, row.gapBps)
        assertEquals(2_000, row.upsideBps)
    }

    @Test
    fun empty_projection_is_deterministic_for_identical_requests() {
        var request = emptyRequest(nowEpochSeconds = 1_777_000_123L)
        var engine = ScreenDataProjectionEngine()

        assertEquals(engine.project(request), engine.project(request))
    }

    @Test
    fun empty_projection_uses_request_time_for_estimates() {
        var request = emptyRequest(nowEpochSeconds = 42L)

        var result = ScreenDataProjectionEngine().project(request).requireSuccess()

        assertEquals(42L, result.estimates.report.computedAtEpochSeconds)
    }

    @Test
    fun projection_preserves_candidate_rows_without_recomputing_them() {
        var candidate = candidateRow()
        var request = emptyRequest(candidateRows = listOf(candidate))

        var result = ScreenDataProjectionEngine().project(request).requireSuccess()

        assertEquals(listOf(candidate), result.candidateRows)
    }

    @Test
    fun projection_applies_route_filter_to_visible_rows() {
        var result = ScreenDataProjectionEngine().project(
            ScreenDataProjectionRequest(
                route = ProjectionRoute(
                    filter = ViewFilter(query = "MSFT", watchlistOnly = true),
                ),
                trackedSymbols = listOf("AAPL", "MSFT"),
                watchlistSymbols = setOf("MSFT", "WATCH"),
                detailsBySymbol = mapOf(
                    "AAPL" to detail("AAPL", 10_000L, 12_000L, ConfidenceBand.High),
                    "MSFT" to detail("MSFT", 10_000L, 13_000L, ConfidenceBand.High),
                ),
                candidateRows = listOf(
                    candidateRow().copy(symbol = "AAPL", companyName = "Apple Inc"),
                    candidateRow().copy(symbol = "MSFT", companyName = "Microsoft Corporation"),
                ),
            ),
        ).requireSuccess()

        assertEquals(
            FilteredProjectionSymbols(
                tracked = listOf("MSFT"),
                watchlist = emptyList(),
                opportunities = listOf("MSFT"),
            ),
            FilteredProjectionSymbols(
                tracked = result.trackedRows.map { it.symbol },
                watchlist = result.watchlistRows.map { it.symbol },
                opportunities = result.opportunityRows.map { it.symbol },
            ),
        )
    }

    @Test
    fun model_fallback_anchor_uses_model_fair_value_label() {
        var anchor = ProjectedFairValueAnchor.model(valueCents = 12_000L, sourceLabel = "DCF model")

        assertEquals(ProjectedFairValueLabels.MODEL_FAIR_VALUE, anchor.displayLabel)
    }

    @Test
    fun model_fallback_anchor_cannot_populate_analyst_history() {
        var anchor = ProjectedFairValueAnchor.model(valueCents = 12_000L, sourceLabel = "DCF model")

        assertEquals(false, anchor.canPopulateAnalystHistory)
    }

    @Test
    fun provider_uncertain_lowers_high_confidence_to_provisional() {
        assertEquals(ProjectedConfidence.Provisional, ProjectedConfidence.High.lowerForProviderUncertainty())
    }

    @Test
    fun provider_uncertain_lowers_provisional_confidence_to_low() {
        assertEquals(ProjectedConfidence.Low, ProjectedConfidence.Provisional.lowerForProviderUncertainty())
    }

    @Test
    fun provider_uncertain_keeps_low_confidence_low() {
        assertEquals(ProjectedConfidence.Low, ProjectedConfidence.Low.lowerForProviderUncertainty())
    }

    @Test
    fun provider_uncertain_keeps_unavailable_confidence_unavailable() {
        assertEquals(ProjectedConfidence.Unavailable, ProjectedConfidence.Unavailable.lowerForProviderUncertainty())
    }

    @Test
    fun fx_live_analyst_projects_weighted_anchor_row_detail_lens_and_provider_semantics() {
        var result = projectSingleSymbol(
            symbol = "W13L",
            detail = detail(
                symbol = "W13L",
                marketPriceCents = 10_000L,
                intrinsicValueCents = 12_000L,
                confidence = ConfidenceBand.High,
                weightedExternalSignalFairValueCents = 12_500L,
                weightedAnalystCount = 12,
                externalSignalLowFairValueCents = 11_000L,
                externalSignalHighFairValueCents = 14_000L,
                analystOpinionCount = 12,
            ),
            dcfAnalysis = dcf(
                source = DcfSource.YahooFinance,
                resolverState = ResolverState.Selected,
                bearIntrinsicValueCents = 11_000L,
                baseIntrinsicValueCents = 12_500L,
                bullIntrinsicValueCents = 16_000L,
            ),
            symbolState = liveState("W13L"),
        )

        assertEquals(fxLiveAnalystExpectation(), projectionExpectation(result))
    }

    @Test
    fun fx_live_model_only_projects_model_label_no_analyst_target_and_watch_decision() {
        var result = projectSingleSymbol(
            symbol = "W13M",
            detail = detail(
                symbol = "W13M",
                marketPriceCents = 10_000L,
                intrinsicValueCents = 13_000L,
                confidence = ConfidenceBand.Provisional,
            ),
            dcfAnalysis = dcf(
                source = DcfSource.YahooFinance,
                resolverState = ResolverState.Selected,
                bearIntrinsicValueCents = 11_500L,
                baseIntrinsicValueCents = 13_000L,
                bullIntrinsicValueCents = 15_000L,
            ),
            symbolState = liveState("W13M"),
        )

        assertEquals(fxLiveModelOnlyExpectation(), projectionExpectation(result))
    }

    @Test
    fun quant_lens_row_summary_clamps_extreme_positive_ev_upside_to_contract_bounds() {
        var result = projectSingleSymbol(
            symbol = "W13X",
            detail = detail(
                symbol = "W13X",
                marketPriceCents = 1L,
                intrinsicValueCents = 12_000_000L,
                confidence = ConfidenceBand.Provisional,
            ),
            dcfAnalysis = dcf(
                source = DcfSource.YahooFinance,
                resolverState = ResolverState.Selected,
                bearIntrinsicValueCents = 1L,
                baseIntrinsicValueCents = 12_000_000L,
                bullIntrinsicValueCents = 12_000_000L,
            ),
            symbolState = liveState("W13X"),
        )

        assertEquals(100_000, projectionExpectation(result).evHighUpsideBps)
    }

    @Test
    fun tracked_unprofitable_rows_project_avoid_decision() {
        var result = projectSingleSymbol(
            symbol = "W13P",
            detail = detail(
                symbol = "W13P",
                marketPriceCents = 10_000L,
                intrinsicValueCents = 12_500L,
                confidence = ConfidenceBand.High,
                weightedExternalSignalFairValueCents = 12_500L,
                weightedAnalystCount = 12,
            ).copy(
                profitable = false,
                qualification = QualificationStatus.Unprofitable,
            ),
        )

        assertEquals(ProjectedRowDecision.Avoid, result.trackedRows.single().decision)
    }

    @Test
    fun tracked_model_only_cautioned_rows_project_watch_decision() {
        var result = projectSingleSymbol(
            symbol = "W13N",
            detail = detail(
                symbol = "W13N",
                marketPriceCents = 10_000L,
                intrinsicValueCents = 13_000L,
                confidence = ConfidenceBand.High,
            ),
            dcfAnalysis = dcf(
                source = DcfSource.YahooFinance,
                resolverState = ResolverState.Selected,
                bearIntrinsicValueCents = 11_500L,
                baseIntrinsicValueCents = 13_000L,
                bullIntrinsicValueCents = 15_000L,
            ),
            symbolState = liveState("W13N"),
        )

        assertEquals(ProjectedRowDecision.Watch, result.trackedRows.single().decision)
    }

    @Test
    fun opportunity_low_confidence_rows_project_avoid_decision() {
        var candidate = candidateRow().copy(confidence = ConfidenceBand.Low)
        var result = ScreenDataProjectionEngine().project(
            emptyRequest(
                candidateRows = listOf(candidate),
                opportunityDecisionFactsBySymbol = mapOf(
                    candidate.symbol to ProjectedOpportunityDecisionFacts(compositeScore = 12),
                ),
            ),
        ).requireSuccess()

        assertEquals(ProjectedRowDecision.Avoid, result.opportunityRows.single().decision)
    }

    @Test
    fun opportunity_low_composite_rows_project_avoid_decision() {
        var candidate = candidateRow().copy(confidence = ConfidenceBand.High)
        var result = ScreenDataProjectionEngine().project(
            emptyRequest(
                candidateRows = listOf(candidate),
                opportunityDecisionFactsBySymbol = mapOf(
                    candidate.symbol to ProjectedOpportunityDecisionFacts(compositeScore = 7),
                ),
            ),
        ).requireSuccess()

        assertEquals(ProjectedRowDecision.Avoid, result.opportunityRows.single().decision)
    }

    @Test
    fun fx_provider_uncertain_lowers_confidence_without_forcing_avoid() {
        var result = projectSingleSymbol(
            symbol = "W13U",
            detail = detail(
                symbol = "W13U",
                marketPriceCents = 10_000L,
                intrinsicValueCents = 13_000L,
                confidence = ConfidenceBand.High,
            ),
            dcfAnalysis = dcf(
                source = null,
                resolverState = ResolverState.ProviderUncertain,
                bearIntrinsicValueCents = 0L,
                baseIntrinsicValueCents = 13_000L,
                bullIntrinsicValueCents = 0L,
            ),
            symbolState = ProjectionSymbolState(
                symbol = "W13U",
                providerCategory = ProjectedProviderCategory.ProviderUncertain,
                provenanceState = ProjectedProvenanceState.ProviderUncertain,
            ),
        )

        assertEquals(fxProviderUncertainExpectation(), projectionExpectation(result))
    }

    @Test
    fun fx_source_free_legacy_projects_source_unknown_saved_model_without_live_coverage() {
        var result = projectSingleSymbol(
            symbol = "W13S",
            detail = detail(
                symbol = "W13S",
                marketPriceCents = 10_000L,
                intrinsicValueCents = 12_000L,
                confidence = ConfidenceBand.High,
            ),
            dcfAnalysis = dcf(
                source = null,
                resolverState = ResolverState.RestoredOnly,
                bearIntrinsicValueCents = 10_000L,
                baseIntrinsicValueCents = 12_000L,
                bullIntrinsicValueCents = 14_000L,
            ),
            symbolState = ProjectionSymbolState(
                symbol = "W13S",
                providerCategory = ProjectedProviderCategory.SourceUnknown,
                provenanceState = ProjectedProvenanceState.SourceUnknown,
            ),
        )

        assertEquals(fxSourceFreeLegacyExpectation(), projectionExpectation(result))
    }

    @Test
    fun median_analyst_statistic_projects_median_target_labels() {
        var result = projectSingleSymbol(
            symbol = "W13A",
            detail = detail(
                symbol = "W13A",
                marketPriceCents = 10_000L,
                intrinsicValueCents = 12_000L,
                confidence = ConfidenceBand.High,
                externalSignalFairValueCents = 12_300L,
                analystOpinionCount = 8,
            ),
            analystTargetStatistic = ProjectedAnalystTargetStatistic.Median,
        )

        assertEquals("Analyst median|Median target", labelPair(result))
    }

    @Test
    fun mean_analyst_statistic_projects_mean_target_labels() {
        var result = projectSingleSymbol(
            symbol = "W13A",
            detail = detail(
                symbol = "W13A",
                marketPriceCents = 10_000L,
                intrinsicValueCents = 12_000L,
                confidence = ConfidenceBand.High,
                externalSignalFairValueCents = 12_300L,
                analystOpinionCount = 8,
            ),
            analystTargetStatistic = ProjectedAnalystTargetStatistic.Mean,
        )

        assertEquals("Analyst mean|Mean target", labelPair(result))
    }

    @Test
    fun absent_analyst_statistic_projects_consensus_target_labels() {
        var result = projectSingleSymbol(
            symbol = "W13A",
            detail = detail(
                symbol = "W13A",
                marketPriceCents = 10_000L,
                intrinsicValueCents = 12_000L,
                confidence = ConfidenceBand.High,
                externalSignalFairValueCents = 12_300L,
                analystOpinionCount = 8,
            ),
        )

        assertEquals("Analyst consensus|Consensus target", labelPair(result))
    }

    @Test
    fun selected_detail_chart_projects_core_chart_analysis() {
        var symbol = "W13C"
        var candles = (1..6).map { index ->
            candle(index, closeCents = 10_000L + (index * 100L), volume = index * 100L)
        }
        var result = ScreenDataProjectionEngine().project(
            ScreenDataProjectionRequest(
                profile = ProjectionProfileFacts(currentProfile = "test"),
                route = ProjectionRoute(
                    selectedSymbol = symbol,
                    selectedRange = ChartRange.Month,
                    replayOffset = 2,
                    volumeProfileBinCount = 3,
                ),
                nowEpochSeconds = 42L,
                trackedSymbols = listOf(symbol),
                detailsBySymbol = mapOf(
                    symbol to detail(
                        symbol = symbol,
                        marketPriceCents = 10_000L,
                        intrinsicValueCents = 12_000L,
                        confidence = ConfidenceBand.High,
                    ),
                ),
                chartCandles = mapOf(SymbolRangeKey(symbol, ChartRange.Month) to candles),
            ),
        ).requireSuccess()

        assertEquals(
            ProjectedDetailChartExpectation(
                status = ProjectedChartStatus.Available,
                totalCandles = 6,
                visibleCandles = 4,
                replayOffset = 2,
                latestEma20Cents = 10_154L,
                volumeMax = 400L,
                volumeProfileBins = 3,
            ),
            result.selectedDetail!!.chart.analysis.let { analysis ->
                ProjectedDetailChartExpectation(
                    status = analysis.status,
                    totalCandles = analysis.replayWindow.totalCandles,
                    visibleCandles = analysis.replayWindow.visibleCount,
                    replayOffset = analysis.replayWindow.replayOffset,
                    latestEma20Cents = analysis.price?.latestEma20Cents,
                    volumeMax = analysis.volume?.maxVolume,
                    volumeProfileBins = analysis.volumeProfile?.bins.orEmpty().size,
                )
            },
        )
    }

    private fun emptyRequest(
        nowEpochSeconds: Long = 0L,
        candidateRows: List<CandidateRow> = emptyList(),
        opportunityDecisionFactsBySymbol: Map<String, ProjectedOpportunityDecisionFacts> = emptyMap(),
    ) = ScreenDataProjectionRequest(
        profile = ProjectionProfileFacts(currentProfile = "test"),
        route = ProjectionRoute(selectedRange = ChartRange.Month),
        nowEpochSeconds = nowEpochSeconds,
        candidateRows = candidateRows,
        opportunityDecisionFactsBySymbol = opportunityDecisionFactsBySymbol,
    )

    private fun candidateRow() = CandidateRow(
        symbol = "ACME",
        marketPriceCents = 10_000L,
        intrinsicValueCents = 12_000L,
        gapBps = 1_666,
        upsideBps = 2_000,
        isQualified = true,
        confidence = ConfidenceBand.High,
    )

    private fun projectSingleSymbol(
        symbol: String,
        detail: SymbolDetail,
        dcfAnalysis: DcfAnalysis? = null,
        symbolState: ProjectionSymbolState = liveState(symbol),
        analystTargetStatistic: ProjectedAnalystTargetStatistic? = null,
    ) = ScreenDataProjectionEngine().project(
        ScreenDataProjectionRequest(
            profile = ProjectionProfileFacts(currentProfile = "test"),
            route = ProjectionRoute(selectedSymbol = symbol, selectedRange = ChartRange.Month),
            nowEpochSeconds = 42L,
            trackedSymbols = listOf(symbol),
            detailsBySymbol = mapOf(symbol to detail),
            dcfBySymbol = listOfNotNull(dcfAnalysis?.let { symbol to it }).toMap(),
            symbolStateBySymbol = mapOf(symbol to symbolState),
            analystTargetStatisticBySymbol = analystTargetStatistic?.let { mapOf(symbol to it) }.orEmpty(),
        ),
    ).requireSuccess()

    @Test
    fun selected_detail_projects_wacc_assumption_labels() {
        val result = projectSingleSymbol(
            symbol = "WACC1",
            detail = detail(
                symbol = "WACC1",
                marketPriceCents = 10_000L,
                intrinsicValueCents = 12_000L,
                confidence = ConfidenceBand.High,
            ),
            dcfAnalysis = dcf(
                source = DcfSource.YahooFinance,
                resolverState = ResolverState.Selected,
                bearIntrinsicValueCents = 9_000L,
                baseIntrinsicValueCents = 12_000L,
                bullIntrinsicValueCents = 15_000L,
            ).copy(
                waccBps = 850,
                waccInputs = WaccInputProvenance(
                    marketCap = WaccFieldSource.DerivedPriceTimesShares,
                    beta = WaccFieldSource.Default,
                    taxRate = WaccFieldSource.Default,
                ),
            ),
        )

        val selected = result.selectedDetail
        assertEquals(850, selected?.waccBps)
        assertEquals(true, selected?.waccProvisional)
        assertTrue(selected?.waccAssumptionLabels?.contains("beta=default") == true)
        assertTrue(selected?.waccAssumptionLabels?.contains("market cap=price×shares") == true)
    }

    @Test
    fun projection_returns_typed_error_for_invalid_request_symbols() {
        val result = ScreenDataProjectionEngine().project(
            ScreenDataProjectionRequest(
                trackedSymbols = listOf(""),
            ),
        )

        assertEquals(
            ComputationResult.Error::class,
            result::class,
        )
        result as ComputationResult.Error
        assertEquals(ComputationArea.Projection, result.failure.area)
        assertEquals("screen_projection_failed", result.failure.code)
        assertEquals(true, result.failure.recoverable)
    }

    private fun projectionExpectation(result: ProjectedDashboardData): FixtureProjectionExpectation {
        var row = result.trackedRows.single()
        var anchor = row.fairValueAnchor
        var detailAnchor = result.selectedDetail?.fairValueAnchor
        var evState = row.quantLensSummary?.lensStates?.singleOrNull { it.lensId == QuantLensLensId.ExpectedValueRange }
        var baseDcf = result.estimates.report.scenarios.first { it.scenario == EstimateScenario.BaseDcf }
        var coverage = result.estimates.report.dcfCoverage
        return FixtureProjectionExpectation(
            symbol = row.symbol,
            marketPriceCents = row.marketPriceCents,
            fairValueCents = anchor.valueCents,
            upsideBps = row.upsideBps,
            displayLabel = anchor.displayLabel,
            compactLabel = anchor.compactLabel,
            sourceLabel = anchor.sourceLabel,
            role = anchor.role,
            provenanceState = anchor.provenanceState,
            confidence = row.confidence,
            freshness = row.freshness,
            trustKind = row.trustSignal?.kind,
            decision = row.decision,
            canPopulateAnalystHistory = anchor.canPopulateAnalystHistory,
            detailFairValueCents = detailAnchor?.valueCents,
            detailSourceLabel = detailAnchor?.sourceLabel,
            evLowUpsideBps = evState?.evLowUpsideBps,
            evHighUpsideBps = evState?.evHighUpsideBps,
            providerCategory = result.providerState.category,
            providerStatusCopy = result.providerState.statusCopy,
            dcfCoverageStatus = coverage.status,
            dcfYahooCount = coverage.sourceDistribution.yahooCount,
            dcfRestoredCount = coverage.sourceDistribution.restoredCount,
            dcfUncertainCount = coverage.sourceDistribution.uncertainCount,
            baseDcfWeightedPriceCents = baseDcf.weightedPriceCents,
        )
    }

    private fun labelPair(result: ProjectedDashboardData): String {
        var anchor = result.trackedRows.single().fairValueAnchor
        return "${anchor.compactLabel}|${anchor.sourceLabel}"
    }

    private fun fxLiveAnalystExpectation() = FixtureProjectionExpectation(
        symbol = "W13L",
        marketPriceCents = 10_000L,
        fairValueCents = 12_500L,
        upsideBps = 2_500,
        displayLabel = ProjectedFairValueLabels.ANALYST_FAIR_VALUE,
        compactLabel = "Analyst weighted",
        sourceLabel = "Weighted target",
        role = ProjectedFairValueRole.AnalystWeightedTarget,
        provenanceState = ProjectedProvenanceState.Live,
        confidence = ProjectedConfidence.High,
        freshness = ProjectedRowFreshness.Updated,
        trustKind = null,
        decision = ProjectedRowDecision.Act,
        canPopulateAnalystHistory = true,
        detailFairValueCents = 12_500L,
        detailSourceLabel = "Weighted target",
        evLowUpsideBps = 1_000,
        evHighUpsideBps = 6_000,
        providerCategory = ProjectedProviderCategory.Live,
        providerStatusCopy = "Live provider data",
        dcfCoverageStatus = DcfCoverageStatus.Ready,
        dcfYahooCount = 1,
        dcfRestoredCount = 0,
        dcfUncertainCount = 0,
        baseDcfWeightedPriceCents = 12_500L,
    )

    private fun fxLiveModelOnlyExpectation() = FixtureProjectionExpectation(
        symbol = "W13M",
        marketPriceCents = 10_000L,
        fairValueCents = 13_000L,
        upsideBps = 3_000,
        displayLabel = ProjectedFairValueLabels.MODEL_FAIR_VALUE,
        compactLabel = "DCF model",
        sourceLabel = "DCF base - Yahoo Finance",
        role = ProjectedFairValueRole.DcfBaseModel,
        provenanceState = ProjectedProvenanceState.Live,
        confidence = ProjectedConfidence.Provisional,
        freshness = ProjectedRowFreshness.Updated,
        trustKind = ProjectedTrustSignalKind.NoAnalystTarget,
        decision = ProjectedRowDecision.Watch,
        canPopulateAnalystHistory = false,
        detailFairValueCents = 13_000L,
        detailSourceLabel = "DCF base - Yahoo Finance",
        evLowUpsideBps = 1_500,
        evHighUpsideBps = 5_000,
        providerCategory = ProjectedProviderCategory.Live,
        providerStatusCopy = "Live provider data",
        dcfCoverageStatus = DcfCoverageStatus.Ready,
        dcfYahooCount = 1,
        dcfRestoredCount = 0,
        dcfUncertainCount = 0,
        baseDcfWeightedPriceCents = 13_000L,
    )

    private fun fxProviderUncertainExpectation() = FixtureProjectionExpectation(
        symbol = "W13U",
        marketPriceCents = 10_000L,
        fairValueCents = 13_000L,
        upsideBps = 3_000,
        displayLabel = ProjectedFairValueLabels.MODEL_FAIR_VALUE,
        compactLabel = "DCF model",
        sourceLabel = "DCF base - source uncertain",
        role = ProjectedFairValueRole.UncertainDcfModel,
        provenanceState = ProjectedProvenanceState.ProviderUncertain,
        confidence = ProjectedConfidence.Provisional,
        freshness = ProjectedRowFreshness.Updated,
        trustKind = ProjectedTrustSignalKind.SourceUncertain,
        decision = ProjectedRowDecision.Watch,
        canPopulateAnalystHistory = false,
        detailFairValueCents = 13_000L,
        detailSourceLabel = "DCF base - source uncertain",
        evLowUpsideBps = null,
        evHighUpsideBps = null,
        providerCategory = ProjectedProviderCategory.ProviderUncertain,
        providerStatusCopy = "Sources disagree; confidence lowered",
        dcfCoverageStatus = DcfCoverageStatus.Unavailable,
        dcfYahooCount = 0,
        dcfRestoredCount = 0,
        dcfUncertainCount = 1,
        baseDcfWeightedPriceCents = 0L,
    )

    private fun fxSourceFreeLegacyExpectation() = FixtureProjectionExpectation(
        symbol = "W13S",
        marketPriceCents = 10_000L,
        fairValueCents = 12_000L,
        upsideBps = 2_000,
        displayLabel = ProjectedFairValueLabels.MODEL_FAIR_VALUE,
        compactLabel = "Saved model",
        sourceLabel = "Source unknown - saved model",
        role = ProjectedFairValueRole.SourceFreeModel,
        provenanceState = ProjectedProvenanceState.SourceUnknown,
        confidence = ProjectedConfidence.Provisional,
        freshness = ProjectedRowFreshness.Restored,
        trustKind = ProjectedTrustSignalKind.SourceUnknown,
        decision = null,
        canPopulateAnalystHistory = false,
        detailFairValueCents = 12_000L,
        detailSourceLabel = "Source unknown - saved model",
        evLowUpsideBps = null,
        evHighUpsideBps = null,
        providerCategory = ProjectedProviderCategory.SourceUnknown,
        providerStatusCopy = "Saved value has no provider source",
        dcfCoverageStatus = DcfCoverageStatus.Unavailable,
        dcfYahooCount = 0,
        dcfRestoredCount = 1,
        dcfUncertainCount = 0,
        baseDcfWeightedPriceCents = 0L,
    )

    private fun liveState(symbol: String) = ProjectionSymbolState(
        symbol = symbol,
        providerCategory = ProjectedProviderCategory.Live,
        provenanceState = ProjectedProvenanceState.Live,
    )

    private fun detail(
        symbol: String,
        marketPriceCents: Long,
        intrinsicValueCents: Long,
        confidence: ConfidenceBand,
        externalSignalFairValueCents: Long? = null,
        externalSignalLowFairValueCents: Long? = null,
        externalSignalHighFairValueCents: Long? = null,
        weightedExternalSignalFairValueCents: Long? = null,
        weightedAnalystCount: Int? = null,
        analystOpinionCount: Int? = null,
    ) = SymbolDetail(
        symbol = symbol,
        profitable = true,
        marketPriceCents = marketPriceCents,
        intrinsicValueCents = intrinsicValueCents,
        gapBps = discountBps(marketPriceCents, intrinsicValueCents),
        upsideBps = upsideBps(marketPriceCents, intrinsicValueCents),
        minimumGapBps = 2_000,
        qualification = QualificationStatus.Qualified,
        externalStatus = ExternalSignalStatus.Supportive,
        externalSignalFairValueCents = externalSignalFairValueCents,
        externalSignalLowFairValueCents = externalSignalLowFairValueCents,
        externalSignalHighFairValueCents = externalSignalHighFairValueCents,
        weightedExternalSignalFairValueCents = weightedExternalSignalFairValueCents,
        weightedAnalystCount = weightedAnalystCount,
        externalSignalGapBps = externalSignalFairValueCents?.let { discountBps(marketPriceCents, it) },
        externalSignalAgeSeconds = 0L,
        externalSignalMaxAgeSeconds = 86_400L,
        analystOpinionCount = analystOpinionCount,
        fundamentals = FundamentalSnapshot(symbol = symbol, marketCapDollars = 1_000L),
        confidence = confidence,
        lastSequence = 1,
        updateCount = 1,
        isWatched = false,
    )

    private fun dcf(
        source: DcfSource?,
        resolverState: ResolverState,
        bearIntrinsicValueCents: Long,
        baseIntrinsicValueCents: Long,
        bullIntrinsicValueCents: Long,
    ) = DcfAnalysis(
        bearIntrinsicValueCents = bearIntrinsicValueCents,
        baseIntrinsicValueCents = baseIntrinsicValueCents,
        bullIntrinsicValueCents = bullIntrinsicValueCents,
        waccBps = 900,
        baseGrowthBps = 300,
        netDebtDollars = 0L,
        source = source,
        resolverState = resolverState,
    )

    private fun upsideBps(marketPriceCents: Long, fairValueCents: Long): Int =
        (((fairValueCents.toDouble() / marketPriceCents.toDouble()) - 1.0) * 10_000.0).toInt()

    private fun discountBps(marketPriceCents: Long, fairValueCents: Long): Int =
        (((fairValueCents.toDouble() - marketPriceCents.toDouble()) / fairValueCents.toDouble()) * 10_000.0).toInt()

    private fun candle(
        epoch: Int,
        closeCents: Long,
        volume: Long,
    ) = HistoricalCandle(
        epochSeconds = epoch.toLong(),
        openCents = closeCents - 100L,
        highCents = closeCents + 100L,
        lowCents = closeCents - 200L,
        closeCents = closeCents,
        volume = volume,
    )

    private data class FixtureProjectionExpectation(
        val symbol: String,
        val marketPriceCents: Long?,
        val fairValueCents: Long?,
        val upsideBps: Int?,
        val displayLabel: String,
        val compactLabel: String,
        val sourceLabel: String,
        val role: ProjectedFairValueRole,
        val provenanceState: ProjectedProvenanceState,
        val confidence: ProjectedConfidence,
        val freshness: ProjectedRowFreshness,
        val trustKind: ProjectedTrustSignalKind?,
        val decision: ProjectedRowDecision?,
        val canPopulateAnalystHistory: Boolean,
        val detailFairValueCents: Long?,
        val detailSourceLabel: String?,
        val evLowUpsideBps: Int?,
        val evHighUpsideBps: Int?,
        val providerCategory: ProjectedProviderCategory,
        val providerStatusCopy: String,
        val dcfCoverageStatus: DcfCoverageStatus,
        val dcfYahooCount: Int,
        val dcfRestoredCount: Int,
        val dcfUncertainCount: Int,
        val baseDcfWeightedPriceCents: Long,
    )

    private data class ProjectedDetailChartExpectation(
        val status: ProjectedChartStatus,
        val totalCandles: Int,
        val visibleCandles: Int,
        val replayOffset: Int,
        val latestEma20Cents: Long?,
        val volumeMax: Long?,
        val volumeProfileBins: Int,
    )

    private data class FilteredProjectionSymbols(
        val tracked: List<String>,
        val watchlist: List<String>,
        val opportunities: List<String>,
    )

    private fun ComputationResult<ProjectedDashboardData>.requireSuccess(): ProjectedDashboardData = when (this) {
        is ComputationResult.Success -> value
        is ComputationResult.Error -> throw AssertionError("Expected projection success, got $failure")
    }
}
