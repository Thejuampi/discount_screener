package com.discountscreener.android.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.discountscreener.android.presentation.dashboard.DashboardAction
import com.discountscreener.android.presentation.dashboard.DetailRoute
import com.discountscreener.android.presentation.dashboard.DetailSubtab
import com.discountscreener.android.presentation.dashboard.HistoryMetricGroup
import com.discountscreener.android.presentation.dashboard.HistorySubview
import com.discountscreener.core.model.ChartRange
import com.discountscreener.core.model.HistoricalCandle
import com.discountscreener.core.model.SymbolDetail
import com.discountscreener.core.model.SymbolRevision
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
    route: DetailRoute,
    detail: SymbolDetail?,
    charts: Map<ChartRange, List<HistoricalCandle>>,
    history: List<SymbolRevision>,
    alerts: List<String>,
    onAction: (DashboardAction) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = route.symbol,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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

        ScrollableTabRow(
            selectedTabIndex = if (route.subtab == DetailSubtab.Snapshot) 0 else 1,
            edgePadding = 0.dp,
        ) {
            Tab(
                selected = route.subtab == DetailSubtab.Snapshot,
                onClick = { onAction(DashboardAction.SetDetailSubtab(DetailSubtab.Snapshot)) },
                text = { Text("Snapshot") },
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
                    alerts = alerts,
                    onAction = onAction,
                )
                DetailSubtab.History -> HistoryContent(
                    route = route,
                    history = history,
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
    alerts: List<String>,
    onAction: (DashboardAction) -> Unit,
) {
    val priceChartModel = remember(candles) { buildPriceChartModel(candles) }
    val volumeChartModel = remember(candles) { buildVolumeChartModel(candles) }
    val macdChartModel = remember(candles) { buildMacdChartModel(candles) }
    val dateTicks = remember(candles, chartRange) { buildDateAxisTicks(candles, chartRange) }
    val trendSignals = remember(priceChartModel, macdChartModel) {
        buildList {
            addAll(priceChartModel?.trendSignals.orEmpty())
            macdChartModel?.trendSignal?.let(::add)
        }
    }
    val axisWidth = rememberChartAxisWidth(
        priceChartModel?.axisLabels,
        volumeChartModel?.axisLabels,
        macdChartModel?.axisLabels,
    )

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            if (detail == null) {
                Text("Loading detail...", style = MaterialTheme.typography.bodyMedium)
                return@item
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Price ${money(detail.marketPriceCents)}  Fair ${money(detail.intrinsicValueCents)}  Disc ${formatPct(detail.gapBps)}  Upside ${formatPct(detail.upsideBps)}",
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Qual ${detail.qualification.name.lowercase()}  Conf ${detail.confidence.name.lowercase()}  External ${detail.externalStatus.name.lowercase()}",
                    style = MaterialTheme.typography.bodySmall,
                )
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
            TrendSignalsSection(signals = trendSignals)
        }

        item {
            PriceChartSection(
                candles = candles,
                model = priceChartModel,
                axisWidth = axisWidth,
                dateTicks = if (macdChartModel == null && volumeChartModel == null) dateTicks else emptyList(),
            )
        }

        item {
            VolumeChartSection(
                candles = candles,
                model = volumeChartModel,
                axisWidth = axisWidth,
                dateTicks = if (macdChartModel == null) dateTicks else emptyList(),
            )
        }

        item {
            MacdChartSection(
                candles = candles,
                model = macdChartModel,
                axisWidth = axisWidth,
                dateTicks = dateTicks,
            )
        }

        detail?.let { d ->
            item {
                ValuationSection(detail = d)
            }

            item {
                ConsensusSection(detail = d)
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
private fun ValuationSection(detail: SymbolDetail) {
    val anchors = valuationAnchors(detail)
    val stats = valuationAnchorStats(anchors.map(ValuationAnchor::valueCents))
    if (anchors.isEmpty() || stats == null) return
    val baseMarkers = valuationBaseMarkers(stats)
    val detailMarkers = valuationDetailMarkers(detail, stats)
    if (detailMarkers.isEmpty()) return

    Text("Valuation", fontWeight = FontWeight.Bold)
    ValuationBoxPlotChart(
        stats = stats,
        baseMarkers = baseMarkers,
        detailMarkers = detailMarkers,
        modifier = Modifier
            .fillMaxWidth()
            .height(144.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        baseMarkers.forEach { point ->
            ValuationLegendItem(point)
        }
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        detailMarkers.forEach { point ->
            ValuationLegendItem(point)
        }
    }
    Text(
        "The full line is the total price axis for every value shown. The box still marks low, P25, median, P75, and max, while colored markers can sit inside or outside the forecast range when price, weighted value, or upper-percentile estimates move beyond it.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConsensusSection(detail: SymbolDetail) {
    val ratings = consensusBuckets(detail)
    val totalRatings = ratings.sumOf(ConsensusBucket::count)
    if (detail.analystOpinionCount == null && detail.recommendationMeanHundredths == null && totalRatings == 0) return

    val holdColor = MaterialTheme.colorScheme.tertiary

    Text("Consensus", fontWeight = FontWeight.Bold)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        detail.analystOpinionCount?.let {
            AssistChip(onClick = {}, label = { Text("Analysts $it") })
        }
        detail.weightedAnalystCount?.let {
            AssistChip(onClick = {}, label = { Text("Weighted $it") })
        }
        detail.recommendationMeanHundredths?.let {
            AssistChip(onClick = {}, label = { Text("Mean ${"%.2f".format(it / 100.0)}") })
        }
    }

    if (totalRatings == 0) {
        Text(
            "Rating distribution is not available from the current feed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val maxCount = ratings.maxOf(ConsensusBucket::count).coerceAtLeast(1)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ratings.forEach { bucket ->
            val fillColor = when (bucket.label) {
                "Strong Buy", "Buy" -> BullishChartColor
                "Hold" -> holdColor
                else -> BearishChartColor
            }
            ConsensusBarRow(bucket = bucket, maxCount = maxCount, fillColor = fillColor)
        }
    }
}

@Composable
private fun ValuationBoxPlotChart(
    stats: ValuationStats,
    baseMarkers: List<VisualAnchor>,
    detailMarkers: List<VisualAnchor>,
    modifier: Modifier = Modifier,
) {
    val axisColor = MaterialTheme.colorScheme.outline
    val boxColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    val forecastLineColor = axisColor.copy(alpha = 0.9f)
    val fullAxisColor = axisColor.copy(alpha = 0.45f)
    val domain = valuationDomain(stats, baseMarkers + detailMarkers)

    Canvas(modifier = modifier.padding(horizontal = 14.dp, vertical = 18.dp)) {
        fun priceX(value: Long): Float = domain.project(value, size.width)

        val upperLayouts = layoutValuationMarkers(detailMarkers, domain, size.width, minSpacing = 18f)
        val lowerLayouts = layoutValuationMarkers(baseMarkers, domain, size.width, minSpacing = 16f)
        val centerY = size.height / 2f
        val whiskerTop = centerY - 16f
        val whiskerBottom = centerY + 16f
        val boxTop = centerY - 12f
        val boxBottom = centerY + 12f
        val boxHeight = boxBottom - boxTop

        val minX = priceX(stats.min)
        val p25X = priceX(stats.p25)
        val medianX = priceX(stats.median)
        val p75X = priceX(stats.p75)
        val maxX = priceX(stats.max)
        val boxLeft = minOf(p25X, p75X)
        val boxRight = maxOf(p25X, p75X)
        val rawBoxWidth = boxRight - boxLeft
        val boxWidth = rawBoxWidth.coerceAtLeast(6f)
        val boxStartX = if (rawBoxWidth < 6f) {
            (boxLeft - ((6f - rawBoxWidth) / 2f)).coerceIn(0f, (size.width - 6f).coerceAtLeast(0f))
        } else {
            boxLeft
        }

        drawLine(color = fullAxisColor, start = Offset(0f, centerY), end = Offset(size.width, centerY), strokeWidth = 2f)
        drawLine(color = forecastLineColor, start = Offset(minX, centerY), end = Offset(maxX, centerY), strokeWidth = 3f)
        drawLine(color = forecastLineColor, start = Offset(minX, whiskerTop), end = Offset(minX, whiskerBottom), strokeWidth = 3f)
        drawLine(color = forecastLineColor, start = Offset(maxX, whiskerTop), end = Offset(maxX, whiskerBottom), strokeWidth = 3f)
        drawRect(
            color = boxColor,
            topLeft = Offset(boxStartX, boxTop),
            size = Size(boxWidth, boxHeight),
        )
        drawRect(
            color = forecastLineColor,
            topLeft = Offset(boxStartX, boxTop),
            size = Size(boxWidth, boxHeight),
            style = Stroke(width = 2f),
        )
        drawLine(
            color = valuationReferenceColor("Median"),
            start = Offset(medianX, boxTop),
            end = Offset(medianX, boxBottom),
            strokeWidth = 4f,
        )

        lowerLayouts.forEach { layout ->
            val markerBottom = centerY + 22f + (layout.lane * 12f)
            drawLine(
                color = layout.anchor.color,
                start = Offset(layout.x, centerY + 2f),
                end = Offset(layout.x, markerBottom),
                strokeWidth = if (layout.anchor.label == "Median") 3.5f else 3f,
            )
            drawCircle(
                color = layout.anchor.color,
                radius = 4.5f,
                center = Offset(layout.x, markerBottom),
            )
        }

        upperLayouts.forEach { layout ->
            val markerTop = centerY - 22f - (layout.lane * 12f)
            drawLine(
                color = layout.anchor.color,
                start = Offset(layout.x, centerY - 2f),
                end = Offset(layout.x, markerTop),
                strokeWidth = 3f,
            )
            drawCircle(
                color = layout.anchor.color,
                radius = 5f,
                center = Offset(layout.x, markerTop),
            )
        }
    }
}

@Composable
private fun ValuationLegendItem(point: VisualAnchor) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(point.color),
        )
        Text(
            text = "${point.label} ${money(point.valueCents)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConsensusBarRow(
    bucket: ConsensusBucket,
    maxCount: Int,
    fillColor: Color,
) {
    val progress = (bucket.count.toFloat() / maxCount.toFloat()).coerceIn(0f, 1f)
    val trackColor = MaterialTheme.colorScheme.surfaceVariant

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = bucket.label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.widthIn(min = 72.dp),
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(12.dp)
                .background(trackColor),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .background(fillColor),
            )
        }
        Text(
            text = bucket.count.toString(),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.End,
            modifier = Modifier.width(28.dp),
        )
    }
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
    history: List<SymbolRevision>,
    onAction: (DashboardAction) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(
                selected = route.historySubview == HistorySubview.Graphs,
                onClick = { onAction(DashboardAction.SetHistorySubview(HistorySubview.Graphs)) },
                label = { Text("Graphs") },
            )
            FilterChip(
                selected = route.historySubview == HistorySubview.Table,
                onClick = { onAction(DashboardAction.SetHistorySubview(HistorySubview.Table)) },
                label = { Text("Table") },
            )
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HistoryMetricGroup.entries.forEach { group ->
                FilterChip(
                    selected = route.historyMetricGroup == group,
                    onClick = { onAction(DashboardAction.SetHistoryMetricGroup(group)) },
                    label = { Text(group.name, maxLines = 1) },
                )
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ChartRange.entries.forEach { window ->
                FilterChip(
                    selected = route.historyTimeWindow == window,
                    onClick = { onAction(DashboardAction.SetHistoryTimeWindow(window)) },
                    label = { Text(chartRangeLabel(window), maxLines = 1) },
                )
            }
        }

        if (history.isEmpty()) {
            Text("No revision history yet", style = MaterialTheme.typography.bodyMedium)
        } else if (route.historySubview == HistorySubview.Graphs) {
            HistoryGraph(history = history)
        } else {
            HistoryTable(history = history)
        }
    }
}

@Composable
private fun HistoryGraph(history: List<SymbolRevision>) {
    val gapValues = history.map { it.detail.gapBps.toFloat() }
    LineChart(
        values = gapValues,
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        lineColor = MaterialTheme.colorScheme.tertiary,
    )
}

@Composable
private fun HistoryTable(history: List<SymbolRevision>) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(history) { revision ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    java.time.Instant.ofEpochSecond(revision.evaluatedAtEpochSeconds)
                        .atZone(java.time.ZoneId.systemDefault())
                        .toLocalDate()
                        .toString(),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "Disc ${formatPct(revision.detail.gapBps)}  Upside ${formatPct(revision.detail.upsideBps)}  Price ${money(revision.detail.marketPriceCents)}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
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
                OhlcChart(candles = candles, model = model, modifier = chartModifier)
            } else {
                Box(modifier = chartModifier, contentAlignment = Alignment.Center) {
                    Text("No chart data", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
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
    ChartPane(
        axisLabels = model?.axisLabels,
        axisWidth = axisWidth,
        chartHeight = 80.dp,
        bottomTicks = dateTicks,
    ) { chartModifier ->
        MacdChart(candles = candles, model = model, modifier = chartModifier)
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
    if (candles.size < 2) return
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
internal fun LineChart(
    values: List<Float>,
    modifier: Modifier = Modifier,
    lineColor: Color = Color.Unspecified,
) {
    val resolvedLineColor = if (lineColor != Color.Unspecified) lineColor else MaterialTheme.colorScheme.primary
    val axisColor = MaterialTheme.colorScheme.outline
    if (values.size < 2) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("Not enough points")
        }
        return
    }
    val min = values.minOrNull() ?: 0f
    val max = values.maxOrNull() ?: 0f
    val span = (max - min).takeIf { it > 0f } ?: 1f

    Canvas(modifier = modifier.padding(8.dp)) {
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = size.width * index / values.lastIndex.toFloat()
            val y = size.height - ((value - min) / span * size.height)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path = path, color = resolvedLineColor, style = Stroke(width = 2f))
        drawLine(color = axisColor, start = Offset(0f, size.height), end = Offset(size.width, size.height))
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

internal data class ValuationDomain(
    val minValue: Long,
    val maxValue: Long,
) {
    val span: Long = (maxValue - minValue).coerceAtLeast(1L)

    fun project(value: Long, width: Float): Float =
        ((value - minValue).toFloat() / span.toFloat()) * width
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
    "P25" -> Color(0xFF26C6DA)
    "Median" -> Color(0xFFFFD54F)
    "Mean" -> Color(0xFFAB47BC)
    "P75" -> Color(0xFF66BB6A)
    "Weighted" -> Color(0xFF7E57C2)
    "P95" -> Color(0xFFEC407A)
    "P99" -> Color(0xFFFF7043)
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
