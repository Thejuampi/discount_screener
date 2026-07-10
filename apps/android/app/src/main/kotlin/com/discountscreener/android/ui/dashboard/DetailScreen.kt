package com.discountscreener.android.ui.dashboard

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.focusable
import com.discountscreener.android.domain.model.DashboardNotice
import com.discountscreener.android.domain.model.DashboardNoticeSeverity
import com.discountscreener.android.domain.model.TickerSearchSuggestion
import com.discountscreener.android.domain.model.ChangeDirection
import com.discountscreener.android.presentation.dashboard.DashboardAction
import com.discountscreener.android.presentation.dashboard.DetailRoute
import com.discountscreener.android.presentation.dashboard.DetailSubtab
import com.discountscreener.android.presentation.dashboard.EvRangeRailModel
import com.discountscreener.android.presentation.dashboard.HistorySubview
import com.discountscreener.android.presentation.dashboard.QuantLensChipUi
import com.discountscreener.android.presentation.dashboard.QuantLensSectionUi
import com.discountscreener.android.presentation.dashboard.QuantLensSeverity
import com.discountscreener.android.presentation.dashboard.QuantLensUiState
import com.discountscreener.android.domain.model.ValuationChange
import com.discountscreener.android.domain.model.ValuationChangeTier
import com.discountscreener.android.domain.model.preferredAnalystCoverageCount
import com.discountscreener.android.domain.model.preferredAnalystTargetFairValueCents
import com.discountscreener.android.domain.model.significantValuationChange
import com.discountscreener.core.engine.ReplayWindow
import com.discountscreener.core.engine.checkedUpsideBps
import com.discountscreener.core.engine.macdHistogramDerivatives
import com.discountscreener.core.engine.macdReadingScale
import com.discountscreener.core.engine.plainMacdReading
import com.discountscreener.core.engine.plainRsiReading
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.ProjectedDetailData
import com.discountscreener.core.model.ProjectedValuationAnchor
import com.discountscreener.core.model.ProjectedValuationAnchorKind
import com.discountscreener.core.model.QuantLensLensId
import com.discountscreener.core.model.SymbolDetail
import com.discountscreener.core.model.SymbolRevision
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
    route: DetailRoute,
    detail: SymbolDetail?,
    charts: Map<ChartRange, List<HistoricalCandle>>,
    history: List<SymbolRevision>,
    alerts: List<String>,
    quantLens: QuantLensUiState? = null,
    detailNotice: DashboardNotice? = null,
    tickerSearchQuery: String = "",
    tickerSearchSuggestions: List<TickerSearchSuggestion> = emptyList(),
    tickerSearchExpanded: Boolean = false,
    tickerSearchNotice: DashboardNotice? = null,
    projectedDetail: ProjectedDetailData? = null,
    onAction: (DashboardAction) -> Unit,
) {
    val tickerSearchActive = tickerSearchExpanded ||
        tickerSearchQuery.isNotBlank() ||
        tickerSearchSuggestions.isNotEmpty() ||
        tickerSearchNotice != null

    BackHandler(enabled = tickerSearchActive) {
        onAction(DashboardAction.ClearTickerSearch)
    }

    BackHandler(enabled = !tickerSearchActive) {
        onAction(DashboardAction.BackFromDetail)
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(route.symbol, route.subtab) {
        focusRequester.requestFocus()
    }
    var routeProjectedDetail = projectedDetail?.takeIf { projection -> projection.symbol == route.symbol }
    var titleDetail = detail ?: routeProjectedDetail?.detail

    Column(
        modifier = Modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                val replayAction = replayActionForKey(event.key) ?: return@onPreviewKeyEvent false
                if (route.subtab == DetailSubtab.Snapshot && event.type == KeyEventType.KeyDown) {
                    onAction(replayAction)
                }
                route.subtab == DetailSubtab.Snapshot
            },
    ) {
        TopAppBar(
            title = {
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        text = titleDetail?.companyName ?: route.symbol,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!titleDetail?.companyName.isNullOrBlank()) {
                        Text(
                            text = route.symbol,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            navigationIcon = {
                TextButton(onClick = { onAction(DashboardAction.BackFromDetail) }) { Text("Back") }
            },
            actions = {
                TextButton(
                    onClick = { onAction(DashboardAction.PrevTicker) },
                    enabled = route.sourceSymbols.indexOf(route.symbol) > 0,
                ) { Text("Prev") }
                TextButton(
                    onClick = { onAction(DashboardAction.NextTicker) },
                    enabled = route.sourceSymbols.indexOf(route.symbol) < route.sourceSymbols.lastIndex,
                ) { Text("Next") }
                TextButton(onClick = { onAction(DashboardAction.ToggleWatchlist(route.symbol)) }) {
                    Text(if (detail?.isWatched == true) "Unwatch" else "Watch")
                }
            },
        )

        TickerSearchBar(
            query = tickerSearchQuery,
            suggestions = tickerSearchSuggestions,
            expanded = tickerSearchExpanded,
            notice = tickerSearchNotice,
            label = "Search ticker",
            placeholder = "Ticker or company",
            onQueryChange = { onAction(DashboardAction.UpdateTickerSearchQuery(it.uppercase())) },
            onExpandedChange = { onAction(DashboardAction.SetTickerSearchExpanded(it)) },
            onSubmit = { onAction(DashboardAction.SubmitTickerSearch) },
            onSelect = { onAction(DashboardAction.SelectTickerSuggestion(it)) },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )

        ScrollableTabRow(
            selectedTabIndex = when (route.subtab) {
                DetailSubtab.Snapshot -> 0
                DetailSubtab.Lens -> 1
                DetailSubtab.History -> 2
            },
            edgePadding = 0.dp,
        ) {
            Tab(
                selected = route.subtab == DetailSubtab.Snapshot,
                onClick = { onAction(DashboardAction.SetDetailSubtab(DetailSubtab.Snapshot)) },
                text = { Text("Snapshot") },
            )
            Tab(
                selected = route.subtab == DetailSubtab.Lens,
                onClick = { onAction(DashboardAction.SetDetailSubtab(DetailSubtab.Lens)) },
                text = { Text("Lens") },
            )
            Tab(
                selected = route.subtab == DetailSubtab.History,
                onClick = { onAction(DashboardAction.SetDetailSubtab(DetailSubtab.History)) },
                text = { Text("History") },
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            when (route.subtab) {
                DetailSubtab.Snapshot -> SnapshotContent(
                    detail = detail,
                    chartRange = route.chartRange,
                    candles = charts[route.chartRange].orEmpty(),
                    replayOffset = route.replayOffset,
                    alerts = alerts,
                    quantLens = quantLens,
                    projectedDetail = routeProjectedDetail,
                    detailNotice = detailNotice,
                    onAction = onAction,
                )
                DetailSubtab.Lens -> QuantLensContent(
                    quantLens = quantLens,
                    notice = detailNotice,
                    route = route,
                    onAction = onAction,
                )
                DetailSubtab.History -> HistoryContent(
                    route = route,
                    detail = detail,
                    history = history,
                    charts = charts,
                    onAction = onAction,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SnapshotContent(
    detail: SymbolDetail?,
    chartRange: ChartRange,
    candles: List<HistoricalCandle>,
    replayOffset: Int,
    alerts: List<String>,
    quantLens: QuantLensUiState?,
    projectedDetail: ProjectedDetailData?,
    detailNotice: DashboardNotice? = null,
    onAction: (DashboardAction) -> Unit,
) {
    var chartModelCache = remember(candles, chartRange, projectedDetail?.chart) {
        mutableMapOf<Int, SnapshotChartModels>()
    }
    var chartModels = remember(candles, chartRange, replayOffset, projectedDetail?.chart) {
        chartModelCache.getOrPut(replayOffset) {
            buildSnapshotChartModels(
                chartRange = chartRange,
                candles = candles,
                replayOffset = replayOffset,
                projectedChart = projectedDetail?.chart,
            )
        }
    }
    var replayWindow = chartModels.replayWindow
    var visibleCandles = chartModels.visibleCandles
    var priceChartModel = chartModels.priceChartModel
    var volumeChartModel = chartModels.volumeChartModel
    var macdChartModel = chartModels.macdChartModel
    var rsiChartModel = chartModels.rsiChartModel
    var volumeProfileModel = chartModels.volumeProfileModel
    var dateTicks = chartModels.dateTicks
    var trendSignals = chartModels.trendSignals
    var displayedDetail = detail ?: projectedDetail?.detail
    var axisWidth = rememberChartAxisWidth(
        priceChartModel?.axisLabels,
        volumeChartModel?.axisLabels,
        macdChartModel?.axisLabels,
    )

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            var currentDetail = displayedDetail
            if (currentDetail == null) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (detailNotice != null) {
                        Text(
                            text = detailNotice.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = detailNotice.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "No chart data",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text("Loading detail...", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                return@item
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Price ${money(currentDetail.marketPriceCents)}  Fair ${money(currentDetail.intrinsicValueCents)}  Disc ${formatPct(currentDetail.gapBps)}  Upside ${formatPct(currentDetail.upsideBps)}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Qual ${currentDetail.qualification.name.lowercase()}  Conf ${currentDetail.confidence.name.lowercase()}  External ${currentDetail.externalStatus.name.lowercase()}",
                    style = MaterialTheme.typography.bodySmall,
                )
                QuantLensMiniStrip(quantLens?.headerChips.orEmpty(), onAction)
            }
        }

        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChartRange.entries.forEach { range ->
                    FilterChip(
                        selected = chartRange == range,
                        onClick = { onAction(DashboardAction.SetChartRange(range)) },
                        label = { Text(chartRangeLabel(range), maxLines = 1) },
                    )
                }
            }
        }

        item {
            ChartReplayControls(
                replayWindow = replayWindow,
                maxVolume = volumeChartModel?.maxVolume ?: 0L,
                onAction = onAction,
            )
        }

        item {
            TrendSignalsSection(signals = trendSignals)
        }

        item {
            PriceChartSection(
                candles = visibleCandles,
                model = priceChartModel,
                volumeProfileModel = volumeProfileModel,
                axisWidth = axisWidth,
                dateTicks = if (macdChartModel == null && volumeChartModel == null) dateTicks else emptyList(),
            )
        }

        item {
            VolumeChartSection(
                candles = visibleCandles,
                model = volumeChartModel,
                axisWidth = axisWidth,
                dateTicks = if (macdChartModel == null) dateTicks else emptyList(),
            )
        }

        item {
            MacdChartSection(
                candles = visibleCandles,
                model = macdChartModel,
                axisWidth = axisWidth,
                dateTicks = dateTicks,
            )
        }

        item {
            RsiChartSection(
                model = rsiChartModel,
                dateTicks = dateTicks,
            )
        }

        displayedDetail?.let { d ->
            item {
                ValuationSection(detail = d, projectedDetail = projectedDetail)
            }

            d.fundamentals?.let { fundamentals ->
                item {
                    Text("Fundamentals", fontWeight = FontWeight.Bold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        fundamentals.sectorName?.let {
                            FundamentalInfoChip(
                                label = it,
                                explanation = "This tells you the big part of the economy the company belongs to, like technology, finance, or healthcare. Buffett lens: stay inside your circle of competence and prefer sectors you can understand over time.",
                            )
                        }
                        fundamentals.industryName?.let {
                            FundamentalInfoChip(
                                label = it,
                                explanation = "This is the company’s more specific business niche inside its sector, like semiconductors inside technology. Buffett lens: the more predictable and understandable the business model, the easier it is to judge long-term value.",
                            )
                        }
                        fundamentals.returnOnEquityBps?.let {
                            FundamentalInfoChip(
                                label = "ROE ${it / 100.0}%",
                                explanation = "ROE shows how well the company turns investor money into profit. In simple terms: higher usually means the business is using its capital more effectively. Buffett lens: sustained ROE around 15% or higher is often seen as a strong sign, especially when it is not boosted by too much debt.",
                            )
                        }
                        fundamentals.freeCashFlowDollars?.let {
                            FundamentalInfoChip(
                                label = "FCF ${compactFinancialNumber(it)}",
                                explanation = "Free cash flow is the cash left after the company pays its normal bills and keeps the business running. More free cash flow usually gives the company more flexibility. Buffett lens: he tends to like businesses that reliably produce positive, growing cash over many years.",
                            )
                        }
                        fundamentals.trailingPeHundredths?.let {
                            FundamentalInfoChip(
                                label = "P/E ${it / 100.0}",
                                explanation = "P/E tells you how expensive the stock is compared with the company’s recent profit. A high P/E can mean investors expect faster growth. Buffett lens: there is no magic cutoff, but he generally prefers paying a reasonable price for durable earnings instead of overpaying for excitement.",
                            )
                        }
                        fundamentals.priceToBookHundredths?.let {
                            FundamentalInfoChip(
                                label = "P/B ${it / 100.0}",
                                explanation = "P/B compares the share price with the company’s accounting net worth. It can help you see whether the stock looks expensive or cheap relative to its assets. Buffett lens: book value can still matter for financial or asset-heavy firms, but he cares more about buying below intrinsic value than chasing a single P/B number.",
                            )
                        }
                    }
                }
            }

            if (alerts.isNotEmpty()) {
                item {
                    Text("Recent Alerts", fontWeight = FontWeight.Bold)
                    alerts.forEach { Text("  $it", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuantLensMiniStrip(
    chips: List<QuantLensChipUi>,
    onAction: (DashboardAction) -> Unit,
) {
    val visibleChips = if (chips.isEmpty()) {
        listOf(QuantLensChipUi(null, "Lens loading", QuantLensSeverity.Muted))
    } else {
        chips.take(5)
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        visibleChips.forEach { chip ->
            AssistChip(
                onClick = { onAction(DashboardAction.SetDetailSubtab(DetailSubtab.Lens)) },
                label = { Text(chip.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuantLensContent(
    quantLens: QuantLensUiState?,
    notice: DashboardNotice?,
    route: DetailRoute,
    onAction: (DashboardAction) -> Unit,
) {
    if (quantLens == null && notice == null) {
        Text("Loading Quant Lens...", style = MaterialTheme.typography.bodyMedium)
        return
    }

    LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Quant Lens", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                QuantLensHeaderStrip(quantLens?.headerChips.orEmpty())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ChartRange.entries.forEach { range ->
                        FilterChip(
                            selected = route.chartRange == range,
                            onClick = { onAction(DashboardAction.SetChartRange(range)) },
                            label = { Text(chartRangeLabel(range), maxLines = 1) },
                        )
                    }
                }
            }
        }
        notice?.let { activeNotice ->
            item { InlineNoticeCard(activeNotice) }
        }
        items(quantLens?.sections.orEmpty(), key = { it.lensId.name }) { section ->
            QuantLensSection(section, onAction)
        }
    }
}

@Composable
private fun InlineNoticeCard(notice: DashboardNotice) {
    val containerColor = when (notice.severity) {
        DashboardNoticeSeverity.Info -> MaterialTheme.colorScheme.surfaceVariant
        DashboardNoticeSeverity.Warning -> MaterialTheme.colorScheme.tertiaryContainer
        DashboardNoticeSeverity.Error -> MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = when (notice.severity) {
        DashboardNoticeSeverity.Info -> MaterialTheme.colorScheme.onSurfaceVariant
        DashboardNoticeSeverity.Warning -> MaterialTheme.colorScheme.onTertiaryContainer
        DashboardNoticeSeverity.Error -> MaterialTheme.colorScheme.onErrorContainer
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = containerColor),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = notice.title,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
            )
            Text(
                text = notice.message,
                style = MaterialTheme.typography.bodySmall,
                color = contentColor,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuantLensHeaderStrip(chips: List<QuantLensChipUi>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        chips.forEach { chip ->
            QuantLensChipText(chip)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuantLensSection(section: QuantLensSectionUi, onAction: (DashboardAction) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                section.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            QuantLensChipText(section.chip)
        }
        Text(section.primaryLine, style = MaterialTheme.typography.bodyMedium)
        section.evRailModel?.let { rail ->
            EvRangeRail(model = rail, modifier = Modifier.fillMaxWidth().height(56.dp))
        }
        section.rows.forEach { (label, value) ->
            val rowModifier = if (section.lensId == QuantLensLensId.SimilarSetups) {
                Modifier
                    .fillMaxWidth()
                    .clickable { onAction(DashboardAction.OpenDetail(label)) }
            } else {
                Modifier.fillMaxWidth()
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = rowModifier) {
                Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(value, fontWeight = FontWeight.Medium)
            }
        }
        if (section.footerChips.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                section.footerChips.forEach { chip ->
                    HistoryMetaChip(chip)
                }
            }
        }
    }
}

@Composable
private fun QuantLensChipText(chip: QuantLensChipUi) {
    val colors = quantLensColors(chip.severity)
    Text(
        text = chip.label,
        style = MaterialTheme.typography.labelMedium,
        color = colors.first,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(colors.second)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
private fun quantLensColors(severity: QuantLensSeverity): Pair<Color, Color> = when (severity) {
    QuantLensSeverity.Supportive -> BullishChartColor to BullishChartColor.copy(alpha = 0.14f)
    QuantLensSeverity.Neutral -> MaterialTheme.colorScheme.tertiary to MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f)
    QuantLensSeverity.Warning -> Color(0xFF8A6E00) to Color(0xFF8A6E00).copy(alpha = 0.14f)
    QuantLensSeverity.Risk -> BearishChartColor to BearishChartColor.copy(alpha = 0.14f)
    QuantLensSeverity.Muted -> MaterialTheme.colorScheme.outline to MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
}

@Composable
private fun EvRangeRail(model: EvRangeRailModel, modifier: Modifier = Modifier) {
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    val bearColor = BearishChartColor
    val bullColor = BullishChartColor
    val baseColor = MaterialTheme.colorScheme.primary
    val muteAlpha = 0.4f

    Canvas(modifier = modifier) {
        val padding = 24.dp.toPx()
        val availableWidth = size.width - 2 * padding
        val midY = size.height / 2f
        val markerRadius = 6.dp.toPx()
        val clampedMin = minOf(model.lowUpsideBps, -200).toFloat()
        val clampedMax = maxOf(model.highUpsideBps, 200).toFloat()
        val span = clampedMax - clampedMin
        fun xFor(bps: Int) = padding + ((bps - clampedMin) / span) * availableWidth

        val xLow = xFor(model.lowUpsideBps)
        val xWeighted = xFor(model.weightedUpsideBps)
        val xHigh = xFor(model.highUpsideBps)
        val trackAlpha = if (model.isStale) muteAlpha else 1f

        // track
        drawLine(
            color = trackColor.copy(alpha = trackAlpha),
            start = androidx.compose.ui.geometry.Offset(xLow, midY),
            end = androidx.compose.ui.geometry.Offset(xHigh, midY),
            strokeWidth = 1.5.dp.toPx(),
        )

        // zero line when crosses zero
        if (model.crossesZero) {
            val xZero = xFor(0)
            drawLine(
                color = trackColor.copy(alpha = trackAlpha),
                start = androidx.compose.ui.geometry.Offset(xZero, midY - 10.dp.toPx()),
                end = androidx.compose.ui.geometry.Offset(xZero, midY + 10.dp.toPx()),
                strokeWidth = 1.dp.toPx(),
            )
        }

        val markerAlpha = if (model.isStale) muteAlpha else 1f

        // bear hollow circle
        drawCircle(
            color = bearColor.copy(alpha = markerAlpha),
            radius = markerRadius,
            center = androidx.compose.ui.geometry.Offset(xLow, midY),
            style = Stroke(width = 1.5.dp.toPx()),
        )

        // bull hollow circle
        drawCircle(
            color = bullColor.copy(alpha = markerAlpha),
            radius = markerRadius,
            center = androidx.compose.ui.geometry.Offset(xHigh, midY),
            style = Stroke(width = 1.5.dp.toPx()),
        )

        // weighted filled diamond
        val d = markerRadius * 0.85f
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(xWeighted, midY - d)
            lineTo(xWeighted + d, midY)
            lineTo(xWeighted, midY + d)
            lineTo(xWeighted - d, midY)
            close()
        }
        drawPath(path = path, color = baseColor.copy(alpha = markerAlpha))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ValuationSection(
    detail: SymbolDetail,
    projectedDetail: ProjectedDetailData? = null,
) {
    var model = valuationRangeModel(detail, projectedDetail)

    Text("Valuation", fontWeight = FontWeight.Bold)
    ValuationHeadline(model = model)
    ValuationRangeChart(
        model = model,
        modifier = Modifier
            .fillMaxWidth()
            .height(132.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
    if (model.referenceValues.isNotEmpty()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            model.referenceValues.forEach { reference ->
                Text(
                    text = "${reference.label} ${compactMoney(reference.valueCents)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    WaccAssumptionsSection(projectedDetail = projectedDetail)
    AnalystConcentrationSection(detail = detail)
}

@Composable
private fun WaccAssumptionsSection(projectedDetail: ProjectedDetailData?) {
    val waccBps = projectedDetail?.waccBps ?: return
    val provisional = projectedDetail.waccProvisional
    val labels = projectedDetail.waccAssumptionLabels
    val waccPercent = waccBps / 100.0
    Text(
        text = buildString {
            append("WACC ${"%.2f".format(java.util.Locale.US, waccPercent)}%")
            if (provisional) append(" · provisional")
        },
        style = MaterialTheme.typography.labelMedium,
        color = if (provisional) {
            MaterialTheme.colorScheme.tertiary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
    if (labels.isNotEmpty()) {
        Text(
            text = "WACC inputs: ${labels.joinToString("; ")}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ValuationHeadline(model: ValuationRangeModel) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        ValuationMetric(
            label = "Price",
            value = money(model.priceMarker.valueCents),
            color = model.priceMarker.color,
        )
        ValuationMetric(
            label = model.fairValueMarker.label,
            value = money(model.fairValueMarker.valueCents),
            color = model.fairValueMarker.color,
        )
        model.upsideBps?.let { upside ->
            val color = if (upside >= 0) BullishChartColor else BearishChartColor
            Text(
                text = "${signedPercentLabel(upside)} vs price",
                style = MaterialTheme.typography.labelMedium,
                color = color,
                modifier = Modifier
                    .background(color.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
        Text(
            text = model.fairValueSourceLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(999.dp))
                .padding(horizontal = 8.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun ValuationMetric(
    label: String,
    value: String,
    color: Color,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color),
        )
        Text(
            text = "$label $value",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ValuationRangeChart(
    model: ValuationRangeModel,
    modifier: Modifier = Modifier,
) {
    val axisColor = MaterialTheme.colorScheme.outline
    val bandColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)

    BoxWithConstraints(modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        val markerWidth = 104.dp
        fun markerOffset(value: Long): androidx.compose.ui.unit.Dp {
            val raw = (maxWidth * model.domain.fraction(value)) - (markerWidth / 2f)
            val maxOffset = (maxWidth - markerWidth).coerceAtLeast(0.dp)
            return raw.coerceIn(0.dp, maxOffset)
        }

        Canvas(modifier = Modifier.fillMaxSize()) {
            fun priceX(value: Long): Float = model.domain.project(value, size.width)
            val centerY = size.height * 0.55f
            drawLine(
                color = axisColor.copy(alpha = 0.62f),
                start = Offset(0f, centerY),
                end = Offset(size.width, centerY),
                strokeWidth = 2f,
            )
            model.targetBandCents?.let { band ->
                val left = priceX(band.first)
                val right = priceX(band.second)
                drawRect(
                    color = bandColor,
                    topLeft = Offset(minOf(left, right), centerY - 8f),
                    size = Size(abs(right - left).coerceAtLeast(3f), 16f),
                )
            }
            listOf(model.priceMarker, model.fairValueMarker).forEach { marker ->
                var x = priceX(marker.valueCents)
                var isFairValue = marker == model.fairValueMarker
                drawLine(
                    color = marker.color,
                    start = Offset(x, centerY - 22f),
                    end = Offset(x, centerY + 22f),
                    strokeWidth = if (isFairValue) 4f else 3f,
                )
                drawCircle(color = marker.color, radius = 6f, center = Offset(x, centerY))
            }
        }
        ValuationMarkerLabel(
            marker = model.priceMarker,
            modifier = Modifier
                .offset(x = markerOffset(model.priceMarker.valueCents), y = 4.dp)
                .width(markerWidth)
                .align(Alignment.TopStart),
        )
        ValuationMarkerLabel(
            marker = model.fairValueMarker,
            modifier = Modifier
                .offset(x = markerOffset(model.fairValueMarker.valueCents), y = 86.dp)
                .width(markerWidth)
                .align(Alignment.TopStart),
        )
        ValuationAxisLabelRow(
            labels = model.axisLabels,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

@Composable
private fun ValuationAxisLabelRow(
    labels: ValuationAxisLabels,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = labels.minLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
        Text(
            text = labels.rangeLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Text(
            text = labels.maxLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
    }
}

@Composable
private fun ValuationMarkerLabel(
    marker: VisualAnchor,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .background(marker.color.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 3.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "${marker.label} ${compactMoney(marker.valueCents)}",
                style = MaterialTheme.typography.labelSmall,
                color = marker.color,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AnalystConcentrationSection(detail: SymbolDetail) {
    val segments = consensusSegments(detail)

    Text("Analyst concentration", fontWeight = FontWeight.SemiBold)
    if (segments.isEmpty()) {
        Text(
            text = "Rating distribution unavailable",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    StackedConsensusBar(segments = segments)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        segments.forEach { segment ->
            ConsensusSegmentRow(segment = segment)
        }
    }
}

@Composable
private fun StackedConsensusBar(segments: List<ConsensusSegment>) {
    val visibleSegments = segments.filter { it.count > 0 }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(16.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        visibleSegments.forEach { segment ->
            Box(
                modifier = Modifier
                    .weight(segment.count.toFloat())
                    .fillMaxHeight()
                    .background(consensusSegmentColor(segment.label)),
            )
        }
    }
}

@Composable
private fun ConsensusSegmentRow(segment: ConsensusSegment) {
    val color = consensusSegmentColor(segment.label)
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color),
        )
        Text(
            text = segment.label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(88.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(8.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(segment.shareBps / 10_000f)
                    .fillMaxHeight()
                    .background(color.copy(alpha = 0.72f)),
            )
        }
        Text(
            text = "${segment.count}  ${formatPct(segment.shareBps)}",
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.End,
            modifier = Modifier.width(64.dp),
            maxLines = 1,
        )
    }
}

@Composable
private fun consensusSegmentColor(label: String): Color = when (label) {
    "Strong Buy", "Buy" -> BullishChartColor
    "Hold" -> MaterialTheme.colorScheme.tertiary
    else -> BearishChartColor
}

@Composable
private fun FundamentalInfoChip(
    label: String,
    explanation: String,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        AssistChip(
            onClick = { expanded = true },
            label = { Text(label) },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            Text(
                text = explanation,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .widthIn(max = 260.dp)
                    .padding(12.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistoryContent(
    route: DetailRoute,
    detail: SymbolDetail?,
    history: List<SymbolRevision>,
    charts: Map<ChartRange, List<HistoricalCandle>>,
    onAction: (DashboardAction) -> Unit,
) {
    val historyCandles = remember(charts, route.historyTimeWindow) {
        charts[route.historyTimeWindow].orEmpty()
    }
    val filteredHistory = remember(history, route.historyTimeWindow) {
        filterHistoryWindow(history, route.historyTimeWindow)
    }
    val valuationHistory = remember(filteredHistory) {
        filteredHistory.filter { preferredAnalystTargetFairValueCents(it.detail) != null }
    }
    val targetEpisodes = remember(valuationHistory) { analystTargetEpisodes(valuationHistory) }
    val changeEvents = remember(targetEpisodes) { analystTargetChangeEvents(targetEpisodes) }
    val historyOverview = remember(valuationHistory, targetEpisodes) {
        analystTargetHistoryOverview(valuationHistory, targetEpisodes)
    }
    val trendSummary = remember(valuationHistory) { valuationTrendSummary(valuationHistory) }
    val historyStatus = remember(detail, history, valuationHistory) {
        historyStatusMessage(detail, history, valuationHistory)
    }
    val priceHistoryStatus = remember(historyCandles, route.historyTimeWindow) {
        priceHistoryStatusMessage(historyCandles, route.historyTimeWindow)
    }
    val showTrendSummary = changeEvents.size >= 2
    val showChartToggle = changeEvents.size >= 2
    val showChangeLog = changeEvents.isNotEmpty()
    val reversedChangeEvents = remember(changeEvents) { changeEvents.asReversed() }

    // Single outer scroll for the whole History tab — no nested LazyColumns.
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ChartRange.entries.forEach { window ->
                    FilterChip(
                        selected = route.historyTimeWindow == window,
                        onClick = { onAction(DashboardAction.SetHistoryTimeWindow(window)) },
                        label = { Text(chartRangeLabel(window), maxLines = 1) },
                    )
                }
            }
        }

        item {
            SavedPriceHistoryCard(
                range = route.historyTimeWindow,
                candles = historyCandles,
                status = priceHistoryStatus,
            )
        }

        historyStatus?.let { status ->
            item { HistoryStatusCard(status) }
        }

        historyOverview?.let { overview ->
            item { HistoryOverviewCard(overview = overview) }
        }

        if (showTrendSummary) {
            item { TrendSummaryCard(summary = trendSummary) }
        }

        if (showChartToggle) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = route.historySubview == HistorySubview.Graphs,
                        onClick = { onAction(DashboardAction.SetHistorySubview(HistorySubview.Graphs)) },
                        label = { Text("Chart") },
                    )
                    FilterChip(
                        selected = route.historySubview == HistorySubview.Table,
                        onClick = { onAction(DashboardAction.SetHistorySubview(HistorySubview.Table)) },
                        label = { Text("Changes") },
                    )
                }
            }
        }

        if (showChangeLog) {
            val showGraph = route.historySubview == HistorySubview.Graphs && showChartToggle
            if (showGraph) {
                item {
                    HistoryGraph(episodes = targetEpisodes, summary = trendSummary)
                }
            } else {
                items(
                    items = reversedChangeEvents,
                    key = { event ->
                        "${event.current.endRevision.evaluatedAtEpochSeconds}-${event.current.targetFairValueCents}"
                    },
                ) { event ->
                    HistoryChangeEventRow(event = event, summary = trendSummary)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SavedPriceHistoryCard(
    range: ChartRange,
    candles: List<HistoricalCandle>,
    status: HistoryStatusMessage,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = status.title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = status.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = chartRangeLabel(range),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (candles.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HistoryMetaChip("${candles.size} candles")
                HistoryMetaChip("Latest ${money(candles.last().closeCents)}")
                HistoryMetaChip("${candleDateLabel(candles.first())} -> ${candleDateLabel(candles.last())}")
            }
            ResponsiveHistoryLineChart(
                values = candles.map { it.closeCents.toFloat() },
                lineColor = status.color,
                lowLabel = money(candles.minOf { it.closeCents }),
                highLabel = money(candles.maxOf { it.closeCents }),
            )
        }
    }
}

@Composable
private fun HistoryGraph(
    episodes: List<AnalystTargetEpisode>,
    summary: ValuationTrendSummary,
) {
    val targetValues = episodes.map { it.targetFairValueCents.toFloat() }
    val lowCents = episodes.minOfOrNull { it.targetFairValueCents } ?: 0L
    val highCents = episodes.maxOfOrNull { it.targetFairValueCents } ?: 0L
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "Analyst target path",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "${episodes.size} target levels · ${money(lowCents)} – ${money(highCents)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ResponsiveHistoryLineChart(
            values = targetValues,
            lineColor = summary.color,
            lowLabel = money(lowCents),
            highLabel = money(highCents),
        )
    }
}

@Composable
private fun ResponsiveHistoryLineChart(
    values: List<Float>,
    lineColor: Color,
    lowLabel: String,
    highLabel: String,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val chartHeight = (maxWidth * 0.42f).coerceIn(148.dp, 240.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeight)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column(
                modifier = Modifier
                    .widthIn(min = 40.dp, max = 64.dp)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.End,
            ) {
                Text(
                    text = highLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    text = lowLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            LineChart(
                values = values,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                lineColor = lineColor,
            )
        }
    }
}

@Composable
private fun HistoryChangeEventRow(
    event: AnalystTargetChangeEvent,
    summary: ValuationTrendSummary,
) {
    val revision = event.current.endRevision
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                java.time.Instant.ofEpochSecond(revision.evaluatedAtEpochSeconds)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDate()
                    .toString(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                money(event.current.targetFairValueCents),
                style = MaterialTheme.typography.labelMedium,
                color = summary.color,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "${money(event.previous.targetFairValueCents)} -> ${money(event.current.targetFairValueCents)}  ${signedPercentLabel(event.changeBps)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val color = valuationChangeColorForBps(event.changeBps, event.significantChange)
            Text(
                text = event.significantChange?.let { valuationChangeLabel(it) } ?: signedPercentLabel(event.changeBps),
                style = MaterialTheme.typography.bodySmall,
                color = color,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun TrendSummaryCard(summary: ValuationTrendSummary) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(summary.color.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = summary.title,
            color = summary.color,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = summary.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HistoryStatusCard(message: HistoryStatusMessage) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(message.color.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = message.title,
            color = message.color,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = message.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HistoryOverviewCard(overview: AnalystTargetHistoryOverview) {
    val accentColor = when (overview.state) {
        AnalystTargetHistoryState.Changed ->
            if ((overview.netChangeBps ?: 0) >= 0) BullishChartColor else BearishChartColor
        AnalystTargetHistoryState.Flat,
        AnalystTargetHistoryState.OneSnapshot -> MaterialTheme.colorScheme.primary
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(accentColor.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = overview.title,
            color = accentColor,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = overview.detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HistoryMetaChip("Target ${money(overview.latestTargetCents)}")
            overview.latestUpsideBps?.let { HistoryMetaChip("Upside ${signedPercentLabel(it)}") }
            HistoryMetaChip("${overview.sourceLabel} source")
            overview.coverageCount?.let { HistoryMetaChip("$it analysts") }
            HistoryMetaChip(
                when (overview.state) {
                    AnalystTargetHistoryState.OneSnapshot -> "1 snapshot"
                    AnalystTargetHistoryState.Flat -> "${overview.observationCount} observations"
                    AnalystTargetHistoryState.Changed -> pluralizedLabel(overview.changeCount, "revision")
                },
            )
        }
    }
}

@Composable
private fun HistoryMetaChip(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun LatestMeaningfulChangeCard(
    latestMeaningfulChange: LatestMeaningfulRevisionChange?,
    latestRevision: SymbolRevision,
) {
    val weightedTarget = preferredAnalystTargetFairValueCents(latestRevision.detail) ?: return
    val coverage = preferredAnalystCoverageCount(latestRevision.detail)
    val title: String
    val detail: String
    val color: Color
    if (latestMeaningfulChange != null) {
        val change = latestMeaningfulChange.change
        val directionText = when (change.direction) {
            ChangeDirection.Up -> "raised"
            ChangeDirection.Down -> "cut"
        }
        val tierText = if (change.tier == ValuationChangeTier.Major) "major" else "meaningful"
        title = "Latest $tierText target change"
        detail = buildString {
            append("Analysts $directionText the analyst target ")
            append(valuationChangeLabel(change))
            append(" on ${revisionDateLabel(latestMeaningfulChange.current)}")
            append(". ${money(preferredAnalystTargetFairValueCents(latestMeaningfulChange.previous.detail) ?: 0L)} -> ${money(weightedTarget)}")
            coverage?.let { append(" from $it analysts") }
            append(".")
        }
        color = valuationChangeColor(change)
    } else {
        title = "No meaningful target change in this window"
        detail = buildString {
            append("Latest analyst target is ${money(weightedTarget)}")
            coverage?.let { append(" from $it analysts") }
            append(". Changes stayed inside the 5% significance threshold.")
        }
        color = MaterialTheme.colorScheme.outline
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = title,
            color = color,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SignificantRevisionSection(changes: List<RevisionValuationChange>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = "Recent significant revisions",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            changes.takeLast(4).asReversed().forEach { change ->
                val color = valuationChangeColor(change.change)
                Text(
                    text = "${revisionDateLabel(change.revision)} ${valuationChangeLabel(change.change)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = color,
                    modifier = Modifier
                        .background(color.copy(alpha = if (change.change.tier == ValuationChangeTier.Major) 0.22f else 0.12f), RoundedCornerShape(999.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

internal data class ValuationTrendSummary(
    val title: String,
    val detail: String,
    val color: Color,
)

internal enum class AnalystTargetHistoryState {
    OneSnapshot,
    Flat,
    Changed,
}

internal data class AnalystTargetEpisode(
    val startRevision: SymbolRevision,
    val endRevision: SymbolRevision,
    val targetFairValueCents: Long,
    val observationCount: Int,
)

internal data class AnalystTargetChangeEvent(
    val previous: AnalystTargetEpisode,
    val current: AnalystTargetEpisode,
    val changeBps: Int,
    val significantChange: ValuationChange?,
)

internal data class AnalystTargetHistoryOverview(
    val state: AnalystTargetHistoryState,
    val title: String,
    val detail: String,
    val latestTargetCents: Long,
    val latestUpsideBps: Int?,
    val sourceLabel: String,
    val coverageCount: Int?,
    val observationCount: Int,
    val changeCount: Int,
    val netChangeBps: Int?,
)

internal data class RevisionValuationChange(
    val revision: SymbolRevision,
    val change: ValuationChange,
)

internal data class LatestMeaningfulRevisionChange(
    val previous: SymbolRevision,
    val current: SymbolRevision,
    val change: ValuationChange,
)

internal data class HistoryStatusMessage(
    val title: String,
    val detail: String,
    val color: Color,
)

internal fun filterHistoryWindow(
    history: List<SymbolRevision>,
    window: ChartRange,
): List<SymbolRevision> {
    if (history.isEmpty()) return emptyList()
    val latestEpoch = history.maxOf { it.evaluatedAtEpochSeconds }
    val cutoff = latestEpoch - historyWindowSeconds(window)
    return history.filter { it.evaluatedAtEpochSeconds >= cutoff }.ifEmpty { history }
}

internal fun historyWindowSeconds(window: ChartRange): Long = when (window) {
    ChartRange.Day -> 86_400L
    ChartRange.Week -> 7 * 86_400L
    ChartRange.Month -> 30 * 86_400L
    ChartRange.Year -> 365 * 86_400L
    ChartRange.FiveYears -> 5 * 365 * 86_400L
    ChartRange.TenYears -> 10 * 365 * 86_400L
}

internal fun valuationTrendSummary(history: List<SymbolRevision>): ValuationTrendSummary {
    val fairValues = history.mapNotNull { preferredAnalystTargetFairValueCents(it.detail) }
    if (fairValues.size < 2) {
        return ValuationTrendSummary(
            title = "Building analyst target history",
            detail = "Need at least two target revisions to compare direction.",
            color = Color(0xFF9E9E9E),
        )
    }
    val stepChanges = fairValues.zipWithNext { previous, current ->
        checkedUpsideBps(previous, current)?.takeIf { it != 0 }
    }.filterNotNull()
    if (stepChanges.isEmpty()) {
        return ValuationTrendSummary(
            title = "Analyst targets are flat",
            detail = "Recent revisions are not materially changing the analyst target.",
            color = Color(0xFF9E9E9E),
        )
    }

    val positiveSteps = stepChanges.count { it > 0 }
    val negativeSteps = stepChanges.count { it < 0 }
    val averageStepBps = stepChanges.average().toInt()
    val overallDirection = when {
        positiveSteps > 0 && negativeSteps == 0 -> ChangeDirection.Up
        negativeSteps > 0 && positiveSteps == 0 -> ChangeDirection.Down
        positiveSteps > negativeSteps -> ChangeDirection.Up
        negativeSteps > positiveSteps -> ChangeDirection.Down
        else -> null
    }
    val title = when (overallDirection) {
        ChangeDirection.Up ->
            if (negativeSteps == 0) "Analysts steadily raising targets" else "Analyst targets drifting higher"
        ChangeDirection.Down ->
            if (positiveSteps == 0) "Analysts steadily cutting targets" else "Analyst targets drifting lower"
        null -> "Analyst target revisions are mixed"
    }
    val detail = when (overallDirection) {
        ChangeDirection.Up ->
            "Average revision +${"%.1f".format(kotlin.math.abs(averageStepBps) / 100.0)}% per update. First ${money(fairValues.first())} -> latest ${money(fairValues.last())}."
        ChangeDirection.Down ->
            "Average revision -${"%.1f".format(kotlin.math.abs(averageStepBps) / 100.0)}% per update. First ${money(fairValues.first())} -> latest ${money(fairValues.last())}."
        null ->
            "Targets are whipsawing between upgrades and cuts. Latest analyst target is ${money(fairValues.last())}."
    }
    val color = when (overallDirection) {
        ChangeDirection.Up -> BullishChartColor
        ChangeDirection.Down -> BearishChartColor
        null -> Color(0xFFFFB300)
    }
    return ValuationTrendSummary(title = title, detail = detail, color = color)
}

internal fun analystTargetEpisodes(history: List<SymbolRevision>): List<AnalystTargetEpisode> {
    if (history.isEmpty()) return emptyList()
    val episodes = mutableListOf<AnalystTargetEpisode>()
    var startRevision = history.first()
    var endRevision = history.first()
    var target = preferredAnalystTargetFairValueCents(startRevision.detail) ?: return emptyList()
    var observations = 1

    history.drop(1).forEach { revision ->
        val revisionTarget = preferredAnalystTargetFairValueCents(revision.detail) ?: return@forEach
        if (revisionTarget == target) {
            endRevision = revision
            observations += 1
        } else {
            episodes += AnalystTargetEpisode(
                startRevision = startRevision,
                endRevision = endRevision,
                targetFairValueCents = target,
                observationCount = observations,
            )
            startRevision = revision
            endRevision = revision
            target = revisionTarget
            observations = 1
        }
    }

    episodes += AnalystTargetEpisode(
        startRevision = startRevision,
        endRevision = endRevision,
        targetFairValueCents = target,
        observationCount = observations,
    )
    return episodes
}

internal fun analystTargetChangeEvents(episodes: List<AnalystTargetEpisode>): List<AnalystTargetChangeEvent> = episodes
    .zipWithNext { previous, current ->
        val changeBps = checkedUpsideBps(previous.targetFairValueCents, current.targetFairValueCents) ?: 0
        AnalystTargetChangeEvent(
            previous = previous,
            current = current,
            changeBps = changeBps,
            significantChange = significantValuationChange(
                previousFairValueCents = previous.targetFairValueCents,
                currentFairValueCents = current.targetFairValueCents,
            ),
        )
    }

internal fun analystTargetHistoryOverview(
    valuationHistory: List<SymbolRevision>,
    episodes: List<AnalystTargetEpisode>,
): AnalystTargetHistoryOverview? {
    if (valuationHistory.isEmpty() || episodes.isEmpty()) return null
    val latestRevision = valuationHistory.last()
    val latestTarget = preferredAnalystTargetFairValueCents(latestRevision.detail) ?: return null
    val latestPrice = latestRevision.detail.marketPriceCents
    val latestUpsideBps = checkedUpsideBps(latestPrice, latestTarget)
    val firstRevision = valuationHistory.first()
    val firstTarget = preferredAnalystTargetFairValueCents(firstRevision.detail) ?: latestTarget
    val netChangeBps = checkedUpsideBps(firstTarget, latestTarget)
    val sourceLabel = analystTargetSourceLabel(latestRevision.detail)
    val coverage = preferredAnalystCoverageCount(latestRevision.detail)
    val observationCount = valuationHistory.size
    val changeCount = (episodes.size - 1).coerceAtLeast(0)
    val windowDays = epochSpanDays(
        firstRevision.evaluatedAtEpochSeconds,
        latestRevision.evaluatedAtEpochSeconds,
    )
    val stableDays = epochSpanDays(
        episodes.last().startRevision.evaluatedAtEpochSeconds,
        latestRevision.evaluatedAtEpochSeconds,
    )
    val priceMoveBps = checkedUpsideBps(firstRevision.detail.marketPriceCents, latestPrice)
    val state = when {
        observationCount == 1 -> AnalystTargetHistoryState.OneSnapshot
        episodes.size == 1 -> AnalystTargetHistoryState.Flat
        else -> AnalystTargetHistoryState.Changed
    }
    val title = when (state) {
        AnalystTargetHistoryState.OneSnapshot -> "First analyst-target snapshot"
        AnalystTargetHistoryState.Flat -> "No analyst target changes in this range"
        AnalystTargetHistoryState.Changed -> "Analyst target ${signedPercentLabel(netChangeBps ?: 0)} in this range"
    }
    val detail = when (state) {
        AnalystTargetHistoryState.OneSnapshot -> buildString {
            append("Latest analyst target is ${money(latestTarget)} vs price ${money(latestPrice)}")
            latestUpsideBps?.let { append(" (${signedPercentLabel(it)} upside)") }
            append(". Captured on ${revisionDateLabel(latestRevision)} from the $sourceLabel source.")
        }
        AnalystTargetHistoryState.Flat -> buildString {
            append("Analysts held ${money(latestTarget)} across $observationCount observations")
            if (windowDays > 0) {
                append(" over $windowDays days")
            }
            append(".")
            priceMoveBps?.takeIf { kotlin.math.abs(it) >= 100 }?.let {
                append(" Price moved ${signedPercentLabel(it)} while targets stayed flat")
                if (stableDays > 0) {
                    append(" for $stableDays days")
                }
                append(".")
            }
        }
        AnalystTargetHistoryState.Changed -> buildString {
            append("Latest analyst target is ${money(latestTarget)} vs price ${money(latestPrice)}")
            latestUpsideBps?.let { append(" (${signedPercentLabel(it)} upside)") }
            append(". ${pluralizedLabel(changeCount, "revision")} across $observationCount observations")
            if (windowDays > 0) {
                append(" over $windowDays days")
            }
            append(". Last changed on ${revisionDateLabel(episodes.last().startRevision)}.")
        }
    }
    return AnalystTargetHistoryOverview(
        state = state,
        title = title,
        detail = detail,
        latestTargetCents = latestTarget,
        latestUpsideBps = latestUpsideBps,
        sourceLabel = sourceLabel,
        coverageCount = coverage,
        observationCount = observationCount,
        changeCount = changeCount,
        netChangeBps = netChangeBps,
    )
}

internal fun significantRevisionChanges(history: List<SymbolRevision>): List<RevisionValuationChange> = history
    .zipWithNext { previous, current ->
        significantValuationChange(
            previousFairValueCents = preferredAnalystTargetFairValueCents(previous.detail),
            currentFairValueCents = preferredAnalystTargetFairValueCents(current.detail),
        )?.let { change -> RevisionValuationChange(revision = current, change = change) }
    }
    .mapNotNull { it }

internal fun latestMeaningfulRevisionChange(
    history: List<SymbolRevision>,
): LatestMeaningfulRevisionChange? = history
    .zipWithNext { previous, current ->
        significantValuationChange(
            previousFairValueCents = preferredAnalystTargetFairValueCents(previous.detail),
            currentFairValueCents = preferredAnalystTargetFairValueCents(current.detail),
        )?.let { change ->
            LatestMeaningfulRevisionChange(previous = previous, current = current, change = change)
        }
    }
    .lastOrNull()

internal fun historyStatusMessage(
    detail: SymbolDetail?,
    history: List<SymbolRevision>,
    valuationHistory: List<SymbolRevision>,
): HistoryStatusMessage? {
    val neutralColor = Color(0xFF9E9E9E)
    if (history.isEmpty()) {
        val currentWeightedTarget = preferredAnalystTargetFairValueCents(detail)
        return if (currentWeightedTarget != null) {
            HistoryStatusMessage(
                title = "No saved analyst-target history yet",
                detail = buildString {
                    append("Current analyst target is ${money(currentWeightedTarget)}")
                    preferredAnalystCoverageCount(detail)?.let { append(" from $it analysts") }
                    append(", but there are no earlier saved revisions to compare yet.")
                },
                color = neutralColor,
            )
        } else {
            HistoryStatusMessage(
                title = "Waiting for analyst target history",
                detail = "Yahoo has not produced an analyst target for this ticker yet.",
                color = neutralColor,
            )
        }
    }
    if (valuationHistory.isEmpty()) {
        return HistoryStatusMessage(
            title = "No analyst-target history in this window",
            detail = if (preferredAnalystTargetFairValueCents(detail) != null) {
                "Saved revisions exist, but none of them include an analyst target in the selected time window yet."
            } else {
                "Saved revisions exist, but Yahoo did not supply an analyst target for them."
            },
            color = neutralColor,
        )
    }
    if (valuationHistory.size == 1) {
        val onlyRevision = valuationHistory.single()
        return HistoryStatusMessage(
            title = "Only one analyst-target snapshot",
            detail = buildString {
                append("Current analyst target is ${money(preferredAnalystTargetFairValueCents(onlyRevision.detail) ?: 0L)}")
                preferredAnalystCoverageCount(onlyRevision.detail)?.let { append(" from $it analysts") }
                append(". Need one more saved revision to show direction and trend.")
            },
            color = neutralColor,
        )
    }
    return null
}

internal fun priceHistoryStatusMessage(
    candles: List<HistoricalCandle>,
    range: ChartRange,
): HistoryStatusMessage {
    val neutralColor = Color(0xFF9E9E9E)
    if (candles.isEmpty()) {
        return HistoryStatusMessage(
            title = "No saved price history yet",
            detail = "Saved candles will appear here after this ticker has refreshed ${chartRangeLabel(range)} chart data.",
            color = neutralColor,
        )
    }
    return HistoryStatusMessage(
        title = "Saved price history",
        detail = buildString {
            append("${candles.size} saved candles")
            append(" from ${candleDateLabel(candles.first())}")
            append(" to ${candleDateLabel(candles.last())}.")
        },
        color = Color(0xFF2E7D32),
    )
}

internal fun candleDateLabel(candle: HistoricalCandle): String =
    java.time.Instant.ofEpochSecond(candle.epochSeconds)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDate()
        .toString()

internal fun revisionChangeComparedToPrevious(
    history: List<SymbolRevision>,
    revision: SymbolRevision,
): ValuationChange? {
    val index = history.indexOfFirst { it.evaluatedAtEpochSeconds == revision.evaluatedAtEpochSeconds }
    if (index <= 0) return null
    return significantValuationChange(
        previousFairValueCents = preferredAnalystTargetFairValueCents(history[index - 1].detail),
        currentFairValueCents = preferredAnalystTargetFairValueCents(revision.detail),
    )
}

internal fun revisionDateLabel(revision: SymbolRevision): String =
    java.time.Instant.ofEpochSecond(revision.evaluatedAtEpochSeconds)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDate()
        .toString()

internal fun signedPercentLabel(changeBps: Int): String {
    val sign = if (changeBps > 0) "+" else ""
    return "$sign${formatPct(changeBps)}"
}

internal fun analystTargetSourceLabel(detail: SymbolDetail): String = when {
    detail.weightedExternalSignalFairValueCents != null -> "Weighted"
    detail.externalSignalFairValueCents != null -> "Median"
    else -> "Unknown"
}

internal fun epochSpanDays(startEpochSeconds: Long, endEpochSeconds: Long): Long =
    ((endEpochSeconds - startEpochSeconds).coerceAtLeast(0L)) / 86_400L

internal fun pluralizedLabel(count: Int, singular: String): String =
    if (count == 1) "1 $singular" else "$count ${singular}s"

private fun valuationChangeColor(change: ValuationChange): Color = when (change.direction) {
    ChangeDirection.Up -> if (change.tier == ValuationChangeTier.Major) BullishChartColor else Color(0xFF2E7D32)
    ChangeDirection.Down -> if (change.tier == ValuationChangeTier.Major) BearishChartColor else Color(0xFFC62828)
}

private fun valuationChangeColorForBps(changeBps: Int, change: ValuationChange?): Color {
    change?.let { return valuationChangeColor(it) }
    return if (changeBps >= 0) Color(0xFF2E7D32) else Color(0xFFC62828)
}

@Composable
private fun TrendSignalsSection(signals: List<TrendSignal>) {
    if (signals.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        signals.forEach { signal ->
            val color = when (signal.bias) {
                TrendSignalBias.Bull -> BullishChartColor
                TrendSignalBias.Bear -> BearishChartColor
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(color.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(color, RoundedCornerShape(4.dp)),
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = signal.title,
                        color = color,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = signal.meaning,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun PriceChartSection(
    candles: List<HistoricalCandle>,
    model: PriceChartModel?,
    volumeProfileModel: VolumeProfileModel?,
    axisWidth: Dp,
    dateTicks: List<ChartDateTick>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        PriceOverlayLegend(model = model)
        ChartPane(
            axisLabels = model?.axisLabels,
            axisWidth = axisWidth,
            chartHeight = 200.dp,
            bottomTicks = dateTicks,
        ) { chartModifier ->
            if (model != null) {
                Row(modifier = chartModifier) {
                    OhlcChart(
                        candles = candles,
                        model = model,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                    VolumeProfileChart(
                        model = volumeProfileModel,
                        modifier = Modifier
                            .width(56.dp)
                            .fillMaxHeight(),
                    )
                }
            } else {
                Box(modifier = chartModifier, contentAlignment = Alignment.Center) {
                    Text("No chart data", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChartReplayControls(
    replayWindow: ReplayWindow,
    maxVolume: Long,
    onAction: (DashboardAction) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(
                onClick = { onAction(DashboardAction.StepReplayBack) },
                enabled = replayWindow.totalCandles > 1 && replayWindow.replayOffset < replayWindow.totalCandles - 1,
            ) { Text("Back") }
            TextButton(
                onClick = { onAction(DashboardAction.StepReplayForward) },
                enabled = replayWindow.replayOffset > 0,
            ) { Text("Forward") }
            TextButton(
                onClick = { onAction(DashboardAction.ResetReplay) },
                enabled = replayWindow.replayOffset > 0,
            ) { Text("Live") }
        }
        Text(
            text = replayStatusText(
                visibleCount = replayWindow.visibleCount,
                totalCount = replayWindow.totalCandles,
                replayOffset = replayWindow.replayOffset,
                maxVolume = maxVolume,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VolumeChartSection(
    candles: List<HistoricalCandle>,
    model: VolumeChartModel?,
    axisWidth: Dp,
    dateTicks: List<ChartDateTick>,
) {
    ChartPane(
        axisLabels = model?.axisLabels,
        axisWidth = axisWidth,
        chartHeight = 60.dp,
        bottomTicks = dateTicks,
    ) { chartModifier ->
        VolumeChart(candles = candles, model = model, modifier = chartModifier)
    }
}

@Composable
private fun MacdChartSection(
    candles: List<HistoricalCandle>,
    model: MacdChartModel?,
    axisWidth: Dp,
    dateTicks: List<ChartDateTick>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("MACD", fontWeight = FontWeight.Bold)
        Text(
            text = macdSummaryText(model),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = macdAnalysisText(model),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        model?.trendSignal?.let { signal ->
            TrendSignalsSection(signals = listOf(signal))
        }
        ChartPane(
            axisLabels = model?.axisLabels,
            axisWidth = axisWidth,
            chartHeight = 80.dp,
            bottomTicks = dateTicks,
        ) { chartModifier ->
            MacdChart(candles = candles, model = model, modifier = chartModifier)
        }
    }
}

@Composable
private fun RsiChartSection(
    model: RsiChartModel?,
    dateTicks: List<ChartDateTick>,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("RSI", fontWeight = FontWeight.Bold)
        Text(
            text = rsiSummaryText(model),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = plainRsiReading(
                level = model?.latestSignalRsi,
                slope = model?.latestSlope,
                acceleration = model?.latestAcceleration,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        model?.trendSignals?.takeIf(List<TrendSignal>::isNotEmpty)?.let { signals ->
            TrendSignalsSection(signals = signals)
        }
        ChartPane(
            axisLabels = ChartAxisLabels(top = "100", middle = "50", bottom = "0"),
            axisWidth = 36.dp,
            chartHeight = 100.dp,
            bottomTicks = dateTicks,
        ) { chartModifier ->
            RsiChart(model = model, modifier = chartModifier)
        }
    }
}

@Composable
private fun ChartPane(
    axisLabels: ChartAxisLabels?,
    axisWidth: Dp,
    chartHeight: androidx.compose.ui.unit.Dp,
    bottomTicks: List<ChartDateTick>,
    content: @Composable (Modifier) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChartAxisColumn(
                axisLabels = axisLabels,
                modifier = Modifier
                    .width(axisWidth)
                    .height(chartHeight),
            )
            content(
                Modifier
                    .fillMaxWidth()
                    .height(chartHeight)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
        if (bottomTicks.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Spacer(modifier = Modifier.width(axisWidth))
                ChartDateAxis(
                    ticks = bottomTicks,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun rememberChartAxisWidth(vararg axisLabels: ChartAxisLabels?): Dp {
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val textStyle = MaterialTheme.typography.labelSmall
    return remember(*axisLabels, density, textMeasurer, textStyle) {
        val widestLabelWidth = chartAxisLabelTexts(*axisLabels)
            .maxOfOrNull { label ->
                textMeasurer.measure(
                    text = AnnotatedString(label),
                    style = textStyle,
                ).size.width
            }
            ?: 0
        with(density) {
            maxOf(MinChartAxisWidth, widestLabelWidth.toDp() + ChartAxisPadding)
        }
    }
}

private fun rsiSummaryText(model: RsiChartModel?): String {
    model ?: return "RSI needs at least 2 candles."
    val classic = model.latestWilderRsi?.let { String.format("%.1f", it) } ?: "n/a"
    val signal = model.latestSignalRsi?.let { String.format("%.1f", it) } ?: "n/a"
    val slope = model.latestSlope?.let { String.format("%.2f", it) } ?: "n/a"
    val acceleration = model.latestAcceleration?.let { String.format("%.2f", it) } ?: "n/a"
    return "Wilder $classic  Signal $signal  Slope $slope  Accel $acceleration"
}

private fun macdSummaryText(model: MacdChartModel?): String {
    model ?: return "MACD needs at least 26 candles."
    val (slope, _) = macdHistogramDerivatives(model.histogram)
    val macd = model.macdLine.lastOrNull()?.let { compactMoney(it.roundToLong()) } ?: "n/a"
    val signal = model.signalLine.lastOrNull()?.let { compactMoney(it.roundToLong()) } ?: "n/a"
    val hist = model.histogram.lastOrNull()?.let { compactMoney(it.roundToLong()) } ?: "n/a"
    val slopeText = slope?.let { compactMoney(it.roundToLong()) } ?: "n/a"
    return "MACD $macd  Signal $signal  Hist $hist  Slope $slopeText"
}

private fun macdAnalysisText(model: MacdChartModel?): String {
    model ?: return plainMacdReading(null, null, null, null, null)
    val (slope, accel) = macdHistogramDerivatives(model.histogram)
    return plainMacdReading(
        macd = model.macdLine.lastOrNull(),
        histogram = model.histogram.lastOrNull(),
        histogramSlope = slope,
        histogramAccel = accel,
        scale = macdReadingScale(model.macdLine, model.signalLine, model.histogram),
    )
}

internal fun chartAxisLabelTexts(vararg axisLabels: ChartAxisLabels?): List<String> = axisLabels
    .flatMap { labels ->
        if (labels == null) {
            emptyList()
        } else {
            listOf(labels.top, labels.middle, labels.bottom)
        }
    }
    .filter(String::isNotBlank)

@Composable
private fun ChartAxisColumn(
    axisLabels: ChartAxisLabels?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.End,
    ) {
        listOf(
            axisLabels?.top.orEmpty(),
            axisLabels?.middle.orEmpty(),
            axisLabels?.bottom.orEmpty(),
        ).forEach { label ->
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth(),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ChartDateAxis(
    ticks: List<ChartDateTick>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        ticks.forEach { tick ->
            Text(
                text = tick.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PriceOverlayLegend(model: PriceChartModel?) {
    if (model == null) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        model.latestEma20Cents?.let { value ->
            Text(
                text = "E20 ${compactMoney(value)}",
                color = Ema20ChartColor,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        model.latestEma50Cents?.let { value ->
            Text(
                text = "E50 ${compactMoney(value)}",
                color = Ema50ChartColor,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        model.latestEma200Cents?.let { value ->
            Text(
                text = "E200 ${compactMoney(value)}",
                color = Ema200ChartColor,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
internal fun OhlcChart(
    candles: List<HistoricalCandle>,
    model: PriceChartModel,
    modifier: Modifier = Modifier,
) {
    if (candles.isEmpty()) return
    val closes = candles.map { it.closeCents.toFloat() }
    val opens = candles.map { it.openCents.toFloat() }
    val highs = candles.map { it.highCents.toFloat() }
    val lows = candles.map { it.lowCents.toFloat() }
    val wicks = closes.zip(opens).map { (close, open) -> close >= open }

    Canvas(modifier = modifier.padding(4.dp)) {
        val slotWidth = chartSlotWidth(candles.size, size.width)
        val bodyWidth = maxOf(3f, slotWidth * 0.65f)
        candles.forEachIndexed { index, _ ->
            val x = chartCenterX(index, candles.size, size.width)
            val isGreen = wicks[index]
            val color = if (isGreen) BullishChartColor else BearishChartColor
            val yHigh = size.height - ((highs[index] - model.minValue) / model.span * size.height)
            val yLow = size.height - ((lows[index] - model.minValue) / model.span * size.height)
            val yOpen = size.height - ((opens[index] - model.minValue) / model.span * size.height)
            val yClose = size.height - ((closes[index] - model.minValue) / model.span * size.height)

            drawLine(color = color, start = Offset(x, yHigh), end = Offset(x, yLow), strokeWidth = 1f)
            val bodyTop = minOf(yOpen, yClose)
            val bodyBottom = maxOf(yOpen, yClose)
            val rawBodyHeight = bodyBottom - bodyTop
            val bodyHeight = maxOf(rawBodyHeight, 2f)
            val bodyY = if (rawBodyHeight >= 2f) bodyTop else (bodyTop - 1f).coerceAtLeast(0f)
            drawRect(
                color = color,
                topLeft = Offset(x - bodyWidth / 2, bodyY),
                size = Size(bodyWidth, bodyHeight),
            )
        }

        drawEmaPath(
            values = model.ema20,
            pointCount = candles.size,
            width = size.width,
            height = size.height,
            minValue = model.minValue,
            span = model.span,
            color = Ema20ChartColor,
            strokeWidth = 2.5f,
        )
        drawEmaPath(
            values = model.ema50,
            pointCount = candles.size,
            width = size.width,
            height = size.height,
            minValue = model.minValue,
            span = model.span,
            color = Ema50ChartColor,
            strokeWidth = 2f,
        )
        drawEmaPath(
            values = model.ema200,
            pointCount = candles.size,
            width = size.width,
            height = size.height,
            minValue = model.minValue,
            span = model.span,
            color = Ema200ChartColor,
            strokeWidth = 1.5f,
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawEmaPath(
    values: List<Double>,
    pointCount: Int,
    width: Float,
    height: Float,
    minValue: Float,
    span: Float,
    color: Color,
    strokeWidth: Float,
) {
    if (values.size < 2 || pointCount < 2) return
    val path = Path()
    values.forEachIndexed { index, value ->
        val x = chartCenterX(index, pointCount, width)
        val y = height - (((value.toFloat() - minValue) / span) * height)
        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = strokeWidth),
    )
}

@Composable
internal fun VolumeChart(
    candles: List<HistoricalCandle>,
    model: VolumeChartModel?,
    modifier: Modifier = Modifier,
) {
    if (candles.isEmpty() || model == null) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("Volume", style = MaterialTheme.typography.labelSmall)
        }
        return
    }
    val volumes = candles.map { it.volume.toFloat() }
    val maxVol = model.maxVolume.toFloat()

    Canvas(modifier = modifier.padding(4.dp)) {
        val slotWidth = chartSlotWidth(candles.size, size.width)
        val barWidth = maxOf(3f, slotWidth * 0.65f)
        candles.forEachIndexed { index, candle ->
            val x = chartCenterX(index, candles.size, size.width)
            val barHeight = (volumes[index] / maxVol) * size.height
            val color = if (candle.closeCents >= candle.openCents) BullishChartColor else BearishChartColor
            drawRect(
                color = color,
                topLeft = Offset(x - barWidth / 2, size.height - barHeight),
                size = Size(barWidth, barHeight.coerceAtLeast(1f)),
            )
        }
    }
}

@Composable
internal fun VolumeProfileChart(
    model: VolumeProfileModel?,
    modifier: Modifier = Modifier,
) {
    val axisColor = MaterialTheme.colorScheme.outline
    if (model == null || model.bins.isEmpty()) {
        Box(modifier = modifier)
        return
    }

    Canvas(modifier = modifier.padding(vertical = 4.dp, horizontal = 3.dp)) {
        drawLine(
            color = axisColor,
            start = Offset(0f, 0f),
            end = Offset(0f, size.height),
            strokeWidth = 1f,
        )
        if (model.maxBinVolume <= 0L) return@Canvas

        val availableWidth = (size.width - 2f).coerceAtLeast(1f)
        val rowHeight = size.height / model.bins.size.toFloat()
        model.bins.forEachIndexed { index, bin ->
            val total = bin.totalVolume
            if (total <= 0L) return@forEachIndexed

            val filledWidth = ((total.toFloat() / model.maxBinVolume.toFloat()) * availableWidth)
                .coerceIn(1f, availableWidth)
            val upWidth = ((bin.upVolume.toFloat() / total.toFloat()) * filledWidth)
                .coerceIn(0f, filledWidth)
            val downWidth = filledWidth - upWidth
            val top = index * rowHeight
            val barTop = top + (rowHeight * 0.18f)
            val barHeight = (rowHeight * 0.64f).coerceAtLeast(1f)

            if (upWidth > 0f) {
                drawRect(
                    color = BullishChartColor,
                    topLeft = Offset(2f, barTop),
                    size = Size(upWidth, barHeight),
                )
            }
            if (downWidth > 0f) {
                drawRect(
                    color = BearishChartColor,
                    topLeft = Offset(2f + upWidth, barTop),
                    size = Size(downWidth, barHeight),
                )
            }
        }
    }
}

@Composable
internal fun MacdChart(
    candles: List<HistoricalCandle>,
    model: MacdChartModel?,
    modifier: Modifier = Modifier,
) {
    if (model == null) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("MACD (need 26+ candles)", style = MaterialTheme.typography.labelSmall)
        }
        return
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val signalColor = MaterialTheme.colorScheme.tertiary
    val axisColor = MaterialTheme.colorScheme.outline
    Canvas(modifier = modifier.padding(4.dp)) {
        val zeroY = model.scale.project(0.0, size.height)
        val slotWidth = chartSlotWidth(model.histogram.size, size.width)
        val histogramBarWidth = maxOf(3f, slotWidth * 0.45f)
        val macdPath = Path()
        model.macdLine.forEachIndexed { index, value ->
            val x = chartCenterX(index, model.macdLine.size, size.width)
            val y = model.scale.project(value, size.height)
            if (index == 0) macdPath.moveTo(x, y) else macdPath.lineTo(x, y)
        }
        drawPath(path = macdPath, color = primaryColor, style = Stroke(width = 3f))

        val signalPath = Path()
        model.signalLine.forEachIndexed { index, value ->
            val x = chartCenterX(index, model.signalLine.size, size.width)
            val y = model.scale.project(value, size.height)
            if (index == 0) signalPath.moveTo(x, y) else signalPath.lineTo(x, y)
        }
        drawPath(path = signalPath, color = signalColor, style = Stroke(width = 2f))

        model.histogram.forEachIndexed { index, value ->
            val x = chartCenterX(index, model.histogram.size, size.width)
            val color = if (value >= 0) BullishChartColor else BearishChartColor
            val valueY = model.scale.project(value, size.height)
            drawRect(
                color = color,
                topLeft = Offset(x - histogramBarWidth / 2, minOf(zeroY, valueY)),
                size = Size(histogramBarWidth, maxOf(kotlin.math.abs(zeroY - valueY), 1f)),
            )
        }

        drawLine(
            color = axisColor,
            start = Offset(0f, zeroY),
            end = Offset(size.width, zeroY),
            strokeWidth = 0.5f,
        )
    }
}

@Composable
internal fun RsiChart(
    model: RsiChartModel?,
    modifier: Modifier = Modifier,
) {
    if (model == null || model.signalRsi.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("RSI unavailable", style = MaterialTheme.typography.labelSmall)
        }
        return
    }

    val classicColor = MaterialTheme.colorScheme.primary
    val signalColor = MaterialTheme.colorScheme.tertiary
    val axisColor = MaterialTheme.colorScheme.outline
    Canvas(modifier = modifier.padding(4.dp)) {
        val projectY: (Double) -> Float = { value ->
            size.height - (((value.toFloat().coerceIn(0f, 100f)) / 100f) * size.height)
        }

        listOf(30.0, 50.0, 70.0).forEach { level ->
            val y = projectY(level)
            drawLine(
                color = axisColor,
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = if (level == 50.0) 1f else 0.5f,
            )
        }

        val classicPath = Path()
        model.wilderRsi.forEachIndexed { index, value ->
            val x = chartCenterX(index, model.wilderRsi.size, size.width)
            val y = projectY(value)
            if (index == 0) classicPath.moveTo(x, y) else classicPath.lineTo(x, y)
        }
        drawPath(path = classicPath, color = classicColor, style = Stroke(width = 2f))

        val signalPath = Path()
        model.signalRsi.forEachIndexed { index, value ->
            val x = chartCenterX(index, model.signalRsi.size, size.width)
            val y = projectY(value)
            if (index == 0) signalPath.moveTo(x, y) else signalPath.lineTo(x, y)
        }
        drawPath(path = signalPath, color = signalColor, style = Stroke(width = 3f))
    }
}

@Composable
internal fun LineChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    lineColor: Color = Color.Unspecified,
) {
    val resolvedLineColor = if (lineColor != Color.Unspecified) lineColor else MaterialTheme.colorScheme.primary
    val axisColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
    if (values.size < 2) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("Not enough points", style = MaterialTheme.typography.bodySmall)
        }
        return
    }
    val min = values.minOrNull() ?: 0f
    val max = values.maxOrNull() ?: 0f
    val span = (max - min).takeIf { it > 0f } ?: 1f

    Canvas(modifier = modifier.padding(horizontal = 4.dp, vertical = 6.dp)) {
        // Light mid/high guides so tall charts read better on phones.
        val midY = size.height * 0.5f
        drawLine(color = gridColor, start = Offset(0f, midY), end = Offset(size.width, midY), strokeWidth = 1f)
        drawLine(color = gridColor, start = Offset(0f, 0f), end = Offset(size.width, 0f), strokeWidth = 1f)

        val path = Path()
        values.forEachIndexed { index, value ->
            val x = size.width * index / values.lastIndex.toFloat()
            val y = size.height - ((value - min) / span * size.height)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path = path, color = resolvedLineColor, style = Stroke(width = 3f))
        drawLine(
            color = axisColor,
            start = Offset(0f, size.height),
            end = Offset(size.width, size.height),
            strokeWidth = 1.5f,
        )
    }
}

internal data class MacdChartScale(
    val minValue: Float,
    val span: Float,
) {
    val maxValue: Float = minValue + span
    fun project(value: Double, height: Float): Float = height - (((value.toFloat() - minValue) / span) * height)
}

private val MinChartAxisWidth = 36.dp
private val ChartAxisPadding = 6.dp
internal val BullishChartColor = Color(0xFF00FF00)
internal val BearishChartColor = Color(0xFFFF0000)
internal val Ema20ChartColor = Color(0xFFFFFF00)
internal val Ema50ChartColor = Color(0xFF00FFFF)
internal val Ema200ChartColor = Color(0xFF757575)

internal data class ValuationAnchor(
    val label: String,
    val valueCents: Long,
)

internal data class VisualAnchor(
    val label: String,
    val valueCents: Long,
    val color: Color,
)

internal data class PrimaryFairValue(
    val sourceLabel: String,
    val valueCents: Long,
)

internal data class ValuationRangeModel(
    val priceMarker: VisualAnchor,
    val fairValueMarker: VisualAnchor,
    val fairValueSourceLabel: String,
    val upsideBps: Int?,
    val targetBandCents: Pair<Long, Long>?,
    val referenceValues: List<ValuationAnchor>,
    val axisLabels: ValuationAxisLabels,
    val domain: ValuationDomain,
)

internal data class ValuationAxisLabels(
    val minValueCents: Long,
    val maxValueCents: Long,
    val minLabel: String,
    val rangeLabel: String,
    val maxLabel: String,
)

internal data class ValuationDomain(
    val minValue: Long,
    val maxValue: Long,
) {
    val span: Long = (maxValue - minValue).coerceAtLeast(1L)

    fun fraction(value: Long): Float =
        ((value - minValue).toFloat() / span.toFloat()).coerceIn(0f, 1f)

    fun project(value: Long, width: Float): Float =
        fraction(value) * width
}

internal data class ValuationMarkerLayout(
    val anchor: VisualAnchor,
    val x: Float,
    val lane: Int,
)

internal data class ConsensusBucket(
    val label: String,
    val count: Int,
)

internal data class ConsensusSegment(
    val label: String,
    val count: Int,
    val shareBps: Int,
)

internal data class ValuationStats(
    val average: Long,
    val median: Long,
    val p25: Long,
    val p75: Long,
    val min: Long,
    val max: Long,
    val p95: Long,
    val p99: Long,
)

internal fun chartRangeLabel(range: ChartRange): String = when (range) {
    ChartRange.Day -> "1D"
    ChartRange.Week -> "7D"
    ChartRange.Month -> "1M"
    ChartRange.Year -> "1Y"
    ChartRange.FiveYears -> "5Y"
    ChartRange.TenYears -> "10Y"
}

internal fun replayActionForKey(key: Key): DashboardAction? = when (key) {
    Key.DirectionLeft,
    Key.VolumeDown,
    -> DashboardAction.StepReplayBack

    Key.DirectionRight,
    Key.VolumeUp,
    -> DashboardAction.StepReplayForward

    else -> null
}

internal fun primaryFairValue(detail: SymbolDetail): PrimaryFairValue = detail.weightedExternalSignalFairValueCents?.let {
    PrimaryFairValue(
        sourceLabel = "Weighted",
        valueCents = it,
    )
} ?: detail.externalSignalFairValueCents?.let {
    PrimaryFairValue(
        sourceLabel = "Median",
        valueCents = it,
    )
} ?: PrimaryFairValue(
    sourceLabel = "Mean",
    valueCents = detail.intrinsicValueCents,
)

internal fun valuationRangeModel(
    detail: SymbolDetail,
    projectedDetail: ProjectedDetailData? = null,
): ValuationRangeModel {
    var projectedModel = projectedValuationRangeModel(detail, projectedDetail)
    if (projectedModel != null) return projectedModel
    var fairValue = primaryFairValue(detail)
    var targetBand = analystTargetBand(detail)
    var references = valuationReferenceValues(detail, fairValue)
    var priceMarker = VisualAnchor("Price", detail.marketPriceCents, valuationReferenceColor("Price"))
    var fairValueMarker = VisualAnchor(
        label = "Fair value",
        valueCents = fairValue.valueCents,
        color = valuationReferenceColor(fairValue.sourceLabel),
    )
    return ValuationRangeModel(
        priceMarker = priceMarker,
        fairValueMarker = fairValueMarker,
        fairValueSourceLabel = fairValue.sourceLabel,
        upsideBps = checkedUpsideBps(detail.marketPriceCents, fairValue.valueCents),
        targetBandCents = targetBand,
        referenceValues = references,
        axisLabels = valuationAxisLabels(
            priceMarker = priceMarker,
            fairValueMarker = fairValueMarker,
            targetBand = targetBand,
            references = references,
        ),
        domain = valuationRangeDomain(
            priceMarker = priceMarker,
            fairValueMarker = fairValueMarker,
            targetBand = targetBand,
            references = references,
        ),
    )
}

private fun projectedValuationRangeModel(
    detail: SymbolDetail,
    projectedDetail: ProjectedDetailData?,
): ValuationRangeModel? {
    projectedDetail ?: return null
    if (projectedDetail.symbol != detail.symbol) return null
    var fairValueAnchor = projectedDetail.fairValueAnchor
    var fairValueCents = fairValueAnchor.valueCents ?: return null
    if (fairValueCents <= 0L) return null
    var references = projectedValuationReferenceValues(projectedDetail.valuationAnchors)
    var targetBand = projectedAnalystTargetBand(projectedDetail.valuationAnchors)
    var priceMarker = VisualAnchor("Price", detail.marketPriceCents, valuationReferenceColor("Price"))
    var fairValueMarker = VisualAnchor(
        label = fairValueAnchor.displayLabel,
        valueCents = fairValueCents,
        color = valuationReferenceColor(fairValueAnchor.sourceLabel),
    )
    return ValuationRangeModel(
        priceMarker = priceMarker,
        fairValueMarker = fairValueMarker,
        fairValueSourceLabel = fairValueAnchor.sourceLabel,
        upsideBps = checkedUpsideBps(detail.marketPriceCents, fairValueCents),
        targetBandCents = targetBand,
        referenceValues = references,
        axisLabels = valuationAxisLabels(
            priceMarker = priceMarker,
            fairValueMarker = fairValueMarker,
            targetBand = targetBand,
            references = references,
        ),
        domain = valuationRangeDomain(
            priceMarker = priceMarker,
            fairValueMarker = fairValueMarker,
            targetBand = targetBand,
            references = references,
        ),
    )
}

private fun projectedValuationReferenceValues(anchors: List<ProjectedValuationAnchor>): List<ValuationAnchor> = anchors
    .filter { anchor -> anchor.kind != ProjectedValuationAnchorKind.PrimaryFairValue }
    .map { anchor -> ValuationAnchor(anchor.label, anchor.valueCents) }
    .distinctBy(ValuationAnchor::label)

private fun projectedAnalystTargetBand(anchors: List<ProjectedValuationAnchor>): Pair<Long, Long>? {
    var low = anchors.firstOrNull { anchor -> anchor.kind == ProjectedValuationAnchorKind.AnalystLowTarget }?.valueCents
    var high = anchors.firstOrNull { anchor -> anchor.kind == ProjectedValuationAnchorKind.AnalystHighTarget }?.valueCents
    if (low == null || high == null || low <= 0L || high <= 0L) return null
    return minOf(low, high) to maxOf(low, high)
}

private fun analystTargetBand(detail: SymbolDetail): Pair<Long, Long>? {
    val low = detail.externalSignalLowFairValueCents ?: return null
    val high = detail.externalSignalHighFairValueCents ?: return null
    if (low <= 0L || high <= 0L) return null
    return minOf(low, high) to maxOf(low, high)
}

private fun valuationReferenceValues(
    detail: SymbolDetail,
    primary: PrimaryFairValue,
): List<ValuationAnchor> = buildList {
    detail.externalSignalLowFairValueCents?.takeIf { it > 0L }?.let { add(ValuationAnchor("Low", it)) }
    detail.externalSignalFairValueCents?.takeIf { it > 0L && primary.sourceLabel != "Median" }?.let {
        add(ValuationAnchor("Median", it))
    }
    detail.intrinsicValueCents.takeIf { it > 0L && primary.sourceLabel != "Mean" }?.let {
        add(ValuationAnchor("Mean", it))
    }
    detail.externalSignalHighFairValueCents?.takeIf { it > 0L }?.let { add(ValuationAnchor("High", it)) }
}.distinctBy { it.label }

private fun valuationAxisLabels(
    priceMarker: VisualAnchor,
    fairValueMarker: VisualAnchor,
    targetBand: Pair<Long, Long>?,
    references: List<ValuationAnchor>,
): ValuationAxisLabels {
    val minValue = targetBand?.first ?: (listOf(priceMarker.valueCents, fairValueMarker.valueCents) + references.map(ValuationAnchor::valueCents)).min()
    val maxValue = targetBand?.second ?: (listOf(priceMarker.valueCents, fairValueMarker.valueCents) + references.map(ValuationAnchor::valueCents)).max()
    return ValuationAxisLabels(
        minValueCents = minValue,
        maxValueCents = maxValue,
        minLabel = "Min ${compactMoney(minValue)}",
        rangeLabel = "Range ${compactMoney(minValue)}-${compactMoney(maxValue)}",
        maxLabel = "Max ${compactMoney(maxValue)}",
    )
}

private fun valuationRangeDomain(
    priceMarker: VisualAnchor,
    fairValueMarker: VisualAnchor,
    targetBand: Pair<Long, Long>?,
    references: List<ValuationAnchor>,
): ValuationDomain {
    val values = buildList {
        add(priceMarker.valueCents)
        add(fairValueMarker.valueCents)
        targetBand?.let {
            add(it.first)
            add(it.second)
        }
        addAll(references.map(ValuationAnchor::valueCents))
    }
    val rawMin = values.minOrNull() ?: 0L
    val rawMax = values.maxOrNull() ?: rawMin
    val rawSpan = rawMax - rawMin
    val padding = if (rawSpan == 0L) {
        (abs(rawMin) / 20L).coerceAtLeast(1L)
    } else {
        ((rawSpan * 10L) / 100L).coerceAtLeast(1L)
    }
    return ValuationDomain(minValue = rawMin - padding, maxValue = rawMax + padding)
}

internal fun valuationAnchors(detail: SymbolDetail): List<ValuationAnchor> = buildList {
    detail.externalSignalLowFairValueCents?.let { add(ValuationAnchor("Low", it)) }
    add(ValuationAnchor("Mean", detail.intrinsicValueCents))
    detail.externalSignalFairValueCents?.let { add(ValuationAnchor("Median", it)) }
    detail.weightedExternalSignalFairValueCents?.let { add(ValuationAnchor("Weighted", it)) }
    detail.externalSignalHighFairValueCents?.let { add(ValuationAnchor("High", it)) }
}

internal fun valuationBaseMarkers(stats: ValuationStats): List<VisualAnchor> = listOf(
    VisualAnchor("Low", stats.min, valuationReferenceColor("Low")),
    VisualAnchor("P25", stats.p25, valuationReferenceColor("P25")),
    VisualAnchor("Median", stats.median, valuationReferenceColor("Median")),
    VisualAnchor("P75", stats.p75, valuationReferenceColor("P75")),
    VisualAnchor("Max", stats.max, valuationReferenceColor("Max")),
)

internal fun valuationDetailMarkers(detail: SymbolDetail, stats: ValuationStats): List<VisualAnchor> = buildList {
    add(VisualAnchor("Price", detail.marketPriceCents, valuationReferenceColor("Price")))
    add(VisualAnchor("Mean", detail.intrinsicValueCents, valuationReferenceColor("Mean")))
    detail.weightedExternalSignalFairValueCents?.let {
        add(VisualAnchor("Weighted", it, valuationReferenceColor("Weighted")))
    }
    add(VisualAnchor("P95", stats.p95, valuationReferenceColor("P95")))
    add(VisualAnchor("P99", stats.p99, valuationReferenceColor("P99")))
}

internal fun valuationReferenceColor(label: String): Color = when (label) {
    "Price" -> Color(0xFFFFB300)
    "Low" -> Color(0xFF29B6F6)
    "Low target" -> Color(0xFF29B6F6)
    "P25" -> Color(0xFF26C6DA)
    "Median" -> Color(0xFFFFD54F)
    "Mean" -> Color(0xFFAB47BC)
    "P75" -> Color(0xFF66BB6A)
    "Weighted" -> Color(0xFF7E57C2)
    "Weighted target" -> Color(0xFF7E57C2)
    "Analyst fair value" -> Color(0xFF7E57C2)
    "Model fair value" -> Color(0xFFAB47BC)
    "DCF bear" -> Color(0xFF29B6F6)
    "DCF base" -> Color(0xFFAB47BC)
    "DCF bull" -> Color(0xFF66BB6A)
    "Intrinsic model" -> Color(0xFFAB47BC)
    "P95" -> Color(0xFFEC407A)
    "P99" -> Color(0xFFFF7043)
    "High target" -> Color(0xFFEF5350)
    "Max" -> Color(0xFFEF5350)
    else -> Color(0xFFD1C4E9)
}

internal fun valuationDomain(stats: ValuationStats, markers: List<VisualAnchor>): ValuationDomain {
    val values = buildList {
        add(stats.min)
        add(stats.p25)
        add(stats.median)
        add(stats.p75)
        add(stats.max)
        addAll(markers.map(VisualAnchor::valueCents))
    }
    val rawMin = values.minOrNull() ?: 0L
    val rawMax = values.maxOrNull() ?: 0L
    val rawSpan = rawMax - rawMin
    val padding = if (rawSpan == 0L) {
        (abs(rawMin) / 20L).coerceAtLeast(1L)
    } else {
        ((rawSpan * 8L) / 100L).coerceAtLeast(1L)
    }
    return ValuationDomain(
        minValue = rawMin - padding,
        maxValue = rawMax + padding,
    )
}

internal fun layoutValuationMarkers(
    markers: List<VisualAnchor>,
    domain: ValuationDomain,
    width: Float,
    minSpacing: Float,
): List<ValuationMarkerLayout> {
    if (markers.isEmpty()) return emptyList()
    val lastXByLane = mutableListOf<Float>()
    return markers
        .sortedWith(compareBy<VisualAnchor> { domain.project(it.valueCents, width) }.thenBy { it.label })
        .map { anchor ->
            val x = domain.project(anchor.valueCents, width)
            val lane = lastXByLane.indexOfFirst { x - it >= minSpacing }.takeIf { it >= 0 } ?: lastXByLane.size
            if (lane == lastXByLane.size) {
                lastXByLane += x
            } else {
                lastXByLane[lane] = x
            }
            ValuationMarkerLayout(anchor = anchor, x = x, lane = lane)
        }
}

internal fun valuationAnchorStats(values: List<Long>): ValuationStats? {
    if (values.isEmpty()) return null
    val sorted = values.sorted()
    return ValuationStats(
        average = values.average().roundToLong(),
        median = percentile(sorted, 0.5),
        p25 = percentile(sorted, 0.25),
        p75 = percentile(sorted, 0.75),
        min = sorted.first(),
        max = sorted.last(),
        p95 = percentile(sorted, 0.95),
        p99 = percentile(sorted, 0.99),
    )
}

internal fun consensusBuckets(detail: SymbolDetail): List<ConsensusBucket> = listOf(
    ConsensusBucket("Strong Buy", detail.strongBuyCount ?: 0),
    ConsensusBucket("Buy", detail.buyCount ?: 0),
    ConsensusBucket("Hold", detail.holdCount ?: 0),
    ConsensusBucket("Sell", detail.sellCount ?: 0),
    ConsensusBucket("Strong Sell", detail.strongSellCount ?: 0),
)

internal fun consensusSegments(detail: SymbolDetail): List<ConsensusSegment> {
    val buckets = consensusBuckets(detail)
    val total = buckets.sumOf(ConsensusBucket::count)
    if (total <= 0) return emptyList()
    return buckets.map { bucket ->
        ConsensusSegment(
            label = bucket.label,
            count = bucket.count,
            shareBps = ((bucket.count * 10_000.0) / total).roundToInt(),
        )
    }
}

internal fun hasConsensusConcentration(detail: SymbolDetail): Boolean =
    consensusSegments(detail).isNotEmpty()

internal fun chartSlotWidth(pointCount: Int, width: Float): Float = if (pointCount > 0) width / pointCount else width

internal fun chartCenterX(index: Int, pointCount: Int, width: Float): Float {
    val slotWidth = chartSlotWidth(pointCount, width)
    return (slotWidth * index) + (slotWidth / 2f)
}

internal fun macdChartScale(
    macdLine: List<Double>,
    signalLine: List<Double>,
    histogram: List<Double>,
): MacdChartScale {
    val allValues = buildList {
        add(0.0)
        addAll(macdLine)
        addAll(signalLine)
        addAll(histogram)
    }
    val min = allValues.minOrNull()?.toFloat() ?: 0f
    val max = allValues.maxOrNull()?.toFloat() ?: 0f
    val span = (max - min).takeIf { it > 0f } ?: 1f
    return MacdChartScale(minValue = min, span = span)
}

private fun percentile(sortedValues: List<Long>, fraction: Double): Long {
    if (sortedValues.isEmpty()) return 0L
    if (sortedValues.size == 1) return sortedValues.first()
    val position = (sortedValues.lastIndex) * fraction.coerceIn(0.0, 1.0)
    val lowerIndex = floor(position).toInt()
    val upperIndex = ceil(position).toInt()
    if (lowerIndex == upperIndex) return sortedValues[lowerIndex]
    val lowerValue = sortedValues[lowerIndex].toDouble()
    val upperValue = sortedValues[upperIndex].toDouble()
    val interpolated = lowerValue + ((upperValue - lowerValue) * (position - lowerIndex))
    return interpolated.roundToLong()
}
