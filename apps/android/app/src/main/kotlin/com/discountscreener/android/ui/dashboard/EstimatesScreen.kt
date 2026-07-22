package com.discountscreener.android.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.discountscreener.android.domain.model.DashboardNotice
import com.discountscreener.android.domain.model.DashboardNoticeSeverity
import com.discountscreener.core.model.DcfCoverageStatus
import com.discountscreener.core.model.DcfCoverageSummary
import com.discountscreener.core.model.IndexEstimatesReport
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun EstimatesScreen(
    indexEstimates: IndexEstimatesReport?,
    loading: Boolean,
    estimatesHistory: List<IndexEstimatesReport> = emptyList(),
    notice: DashboardNotice? = null,
) {
    when {
        loading && indexEstimates == null -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        notice != null && indexEstimates == null -> {
            EstimatesNoticeState(notice)
        }
        indexEstimates == null -> {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No estimates available")
            }
        }
        indexEstimates.currentWeightedPriceCents == 0L -> {
            EstimatesNoticeState(
                notice ?: DashboardNotice(
                    "Estimates unavailable",
                    "No price data available yet",
                    DashboardNoticeSeverity.Info,
                ),
            )
        }
        else -> {
            EstimatesContent(indexEstimates, estimatesHistory, notice)
        }
    }
}

@Composable
private fun EstimatesContent(
    report: IndexEstimatesReport,
    estimatesHistory: List<IndexEstimatesReport>,
    notice: DashboardNotice?,
) {
    val hero = remember(report) { buildEstimatesHeroSummary(report) }
    val coverage = report.dcfCoverage

    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        notice?.let { activeNotice ->
            item { NoticeBanner(activeNotice) }
        }
        item { HeroCard(hero, report) }
        when (dcfCoverageBannerKind(coverage)) {
            DcfCoverageBannerKind.Unavailable -> item {
                DcfErrorBanner(coverage.coveredSymbols, coverage.totalEligibleSymbols)
            }
            DcfCoverageBannerKind.LowConfidence -> item {
                DcfLowConfidenceBanner(coverage.coveredSymbols, coverage.totalEligibleSymbols)
            }
            DcfCoverageBannerKind.Partial -> item {
                DcfLowConfidenceBanner(coverage.coveredSymbols, coverage.totalEligibleSymbols)
            }
            DcfCoverageBannerKind.Provisional -> item {
                DcfProvisionalBanner(coverage)
            }
            DcfCoverageBannerKind.None -> Unit
        }
        item {
            ScenarioRangeCard(
                title = "Internal DCF model",
                subtitle = "Market-cap weighted fair value vs price",
                lowLabel = "Bear",
                midLabel = "Base",
                highLabel = "Bull",
                lowBps = hero.bearUpsideBps,
                midBps = hero.baseUpsideBps,
                highBps = hero.bullUpsideBps,
                coverageNote = hero.baseCoverageCount?.let { "$it companies with live DCF" },
            )
        }
        item {
            ScenarioRangeCard(
                title = "Wall Street analysts",
                subtitle = "Yahoo low / high targets, cap-weighted",
                lowLabel = "Low",
                midLabel = null,
                highLabel = "High",
                lowBps = hero.analystLowBps,
                midBps = null,
                highBps = hero.analystHighBps,
                coverageNote = hero.analystCoverageCount?.let { "$it companies with targets" },
            )
        }
        item {
            EstimatesTrendChart(estimatesHistory)
        }
    }
}

@Composable
private fun EstimatesNoticeState(notice: DashboardNotice) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        NoticeBanner(notice)
    }
}

@Composable
private fun NoticeBanner(notice: DashboardNotice) {
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
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodySmall,
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

internal enum class DcfCoverageBannerKind {
    None,
    Unavailable,
    LowConfidence,
    Partial,
    Provisional,
}

internal fun dcfCoverageBannerKind(summary: DcfCoverageSummary): DcfCoverageBannerKind = when (summary.status) {
    DcfCoverageStatus.Unavailable -> DcfCoverageBannerKind.Unavailable
    DcfCoverageStatus.LowConfidence -> DcfCoverageBannerKind.LowConfidence
    DcfCoverageStatus.Partial -> DcfCoverageBannerKind.Partial
    DcfCoverageStatus.Provisional -> DcfCoverageBannerKind.Provisional
    DcfCoverageStatus.Ready -> DcfCoverageBannerKind.None
}

@Composable
private fun DcfProvisionalBanner(coverage: DcfCoverageSummary) {
    val notEligible = coverage.sourceDistribution.notEligibleCount
    val detail = buildString {
        append("Still building live DCF coverage: ${coverage.coveredSymbols} / ${coverage.totalEligibleSymbols} eligible")
        if (notEligible > 0) {
            append(" · $notEligible not eligible for FCF DCF (banks, negative FCF, etc.)")
        }
    }
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "\u26A0",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DcfLowConfidenceBanner(coverageCount: Int, totalSymbols: Int) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "\u26A0",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Low confidence — only $coverageCount / $totalSymbols eligible companies have live DCF",
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    text = "Results may be skewed toward the names already enriched",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}

@Composable
private fun DcfErrorBanner(coverageCount: Int, totalSymbols: Int) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "\u26A0",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "Not enough DCF data ($coverageCount / $totalSymbols eligible)",
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    text = "Estimates require at least 25% live DCF coverage",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun HeroCard(hero: EstimatesHeroSummary, report: IndexEstimatesReport) {
    val upsideColor = upsideColor(hero.baseUpsideBps)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${hero.profileLabel} · ${report.totalSymbols} symbols",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                )
                CoverageChip(hero)
            }
            Text(
                text = formatSignedPctBps(hero.baseUpsideBps),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.headlineMedium,
                color = upsideColor,
            )
            Text(
                text = verdictSentence(hero.verdict, hero.baseUpsideBps),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = "Cap-weighted implied upside vs today’s prices · Updated ${formatRelativeTime(report.computedAtEpochSeconds)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (hero.bearUpsideBps != null && hero.bullUpsideBps != null) {
                RangeBar(
                    lowBps = hero.bearUpsideBps,
                    midBps = hero.baseUpsideBps,
                    highBps = hero.bullUpsideBps,
                    lowTint = Color(0xFFEF5350),
                    midTint = Color(0xFFFFCA28),
                    highTint = Color(0xFF66BB6A),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = "Bear ${formatSignedPctBps(hero.bearUpsideBps)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Bull ${formatSignedPctBps(hero.bullUpsideBps)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CoverageChip(hero: EstimatesHeroSummary) {
    val (bg, fg) = when (hero.coverageStatus) {
        DcfCoverageStatus.Ready ->
            MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        DcfCoverageStatus.Provisional ->
            MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer
        DcfCoverageStatus.Partial,
        DcfCoverageStatus.LowConfidence ->
            MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer
        DcfCoverageStatus.Unavailable ->
            MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = hero.coverageLabel,
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(bg)
                .padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = fg,
        )
        Text(
            text = hero.coverageDetail,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ScenarioRangeCard(
    title: String,
    subtitle: String,
    lowLabel: String,
    midLabel: String?,
    highLabel: String,
    lowBps: Int?,
    midBps: Int?,
    highBps: Int?,
    coverageNote: String?,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (lowBps == null && highBps == null && midBps == null) {
                Text(
                    text = "No data yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    UpsideStat(lowLabel, lowBps)
                    if (midLabel != null) {
                        UpsideStat(midLabel, midBps, emphasize = true)
                    }
                    UpsideStat(highLabel, highBps)
                }
                if (lowBps != null && highBps != null) {
                    RangeBar(
                        lowBps = lowBps,
                        midBps = midBps,
                        highBps = highBps,
                        lowTint = Color(0xFF26C6DA),
                        midTint = Color(0xFFFFCA28),
                        highTint = Color(0xFF42A5F5),
                    )
                }
            }
            coverageNote?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun UpsideStat(label: String, bps: Int?, emphasize: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = formatSignedPctBps(bps),
            fontWeight = if (emphasize) FontWeight.Bold else FontWeight.SemiBold,
            style = if (emphasize) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            color = upsideColor(bps),
        )
    }
}

@Composable
private fun RangeBar(
    lowBps: Int,
    midBps: Int?,
    highBps: Int,
    lowTint: Color,
    midTint: Color,
    highTint: Color,
) {
    val trackColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    val zeroColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(12.dp),
    ) {
        val min = minOf(lowBps, midBps ?: lowBps, highBps, 0).toFloat()
        val max = maxOf(lowBps, midBps ?: highBps, highBps, 0).toFloat()
        val span = (max - min).coerceAtLeast(1f)
        fun xOf(value: Int): Float = ((value - min) / span) * size.width

        // Background track
        drawRoundRect(
            color = trackColor,
            cornerRadius = CornerRadius(size.height / 2f, size.height / 2f),
        )
        val left = minOf(xOf(lowBps), xOf(highBps))
        val right = maxOf(xOf(lowBps), xOf(highBps))
        drawRoundRect(
            brush = Brush.horizontalGradient(
                colors = listOf(lowTint.copy(alpha = 0.65f), highTint.copy(alpha = 0.65f)),
                startX = left,
                endX = right,
            ),
            topLeft = Offset(left, 0f),
            size = Size((right - left).coerceAtLeast(4.dp.toPx()), size.height),
            cornerRadius = CornerRadius(size.height / 2f, size.height / 2f),
        )
        if (min < 0f && max > 0f) {
            val zeroX = xOf(0)
            drawLine(
                color = zeroColor,
                start = Offset(zeroX, 0f),
                end = Offset(zeroX, size.height),
                strokeWidth = 2.dp.toPx(),
            )
        }
        midBps?.let { mid ->
            val midX = xOf(mid)
            drawCircle(
                color = midTint,
                radius = size.height / 2f,
                center = Offset(midX, size.height / 2f),
            )
        }
    }
}

@Composable
private fun upsideColor(bps: Int?): Color {
    if (bps == null) return MaterialTheme.colorScheme.onSurfaceVariant
    return if (bps >= 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
}

private fun formatRelativeTime(epochSeconds: Long): String {
    val nowSeconds = System.currentTimeMillis() / 1_000
    val diffSeconds = nowSeconds - epochSeconds
    return when {
        diffSeconds < 10 -> "just now"
        diffSeconds < 60 -> "${diffSeconds}s ago"
        diffSeconds < 3600 -> "${diffSeconds / 60}m ago"
        diffSeconds < 86400 -> "${diffSeconds / 3600}h ago"
        else -> DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            .format(Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).toLocalTime())
    }
}

@Composable
private fun EstimatesTrendChart(history: List<IndexEstimatesReport>) {
    val model = remember(history) { EstimatesTrendChartModel.from(history) }
    if (model == null) {
        if (history.size < 2) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "Implied upside over time",
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "Trend appears after the next refresh saves a second snapshot.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        return
    }

    val baseColor = Color(0xFFFFCA28)
    val bandColor = Color(0xFF66BB6A).copy(alpha = 0.22f)
    val axisColor = Color.Gray.copy(alpha = 0.45f)
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val startLabel = remember(model.minEpoch) { formatChartDate(model.minEpoch) }
    val endLabel = remember(model.maxEpoch) { formatChartDate(model.maxEpoch) }
    val changeLabel = remember(model.baseChangePts) {
        val pts = model.baseChangePts
        val signed = if (pts >= 0f) "+%.1f pp".format(pts) else "%.1f pp".format(pts)
        "Base DCF changed $signed over this window"
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Implied upside over time",
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "Base DCF line with Bear–Bull band. Analyst targets stay in the cards above.",
                style = MaterialTheme.typography.bodySmall,
                color = onSurfaceVariant,
            )
            Text(
                text = changeLabel,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = if (model.baseChangePts >= 0f) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                LatestUpsideStat(label = "Base", valuePct = model.latestBaseUpsidePct, emphasize = true)
                LatestUpsideStat(label = "Bear", valuePct = model.latestBearUpsidePct)
                LatestUpsideStat(label = "Bull", valuePct = model.latestBullUpsidePct)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(176.dp),
            ) {
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxWidth(0.14f)
                        .height(160.dp)
                        .padding(end = 4.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    model.yTickLabels.forEach { tick ->
                        Text(
                            text = formatUpsideTick(tick),
                            style = MaterialTheme.typography.labelSmall,
                            color = onSurfaceVariant,
                        )
                    }
                }
                Canvas(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxWidth(0.86f)
                        .height(160.dp),
                ) {
                    val w = size.width
                    val h = size.height
                    val range = (model.drawMaxUpside - model.drawMinUpside).coerceAtLeast(1f)

                    fun xOf(epoch: Long): Float =
                        ((epoch - model.minEpoch).toFloat() / model.epochSpan) * w

                    fun yOf(upside: Float): Float =
                        h - ((upside - model.drawMinUpside) / range) * h

                    model.yTickLabels.forEach { tick ->
                        val y = yOf(tick)
                        drawLine(
                            color = axisColor.copy(alpha = 0.25f),
                            start = Offset(0f, y),
                            end = Offset(w, y),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }

                    if (model.drawMinUpside <= 0f && model.drawMaxUpside >= 0f) {
                        val zeroY = yOf(0f)
                        drawLine(
                            color = axisColor,
                            start = Offset(0f, zeroY),
                            end = Offset(w, zeroY),
                            strokeWidth = 1.5.dp.toPx(),
                        )
                    }

                    val hasBand = model.points.any { it.bearUpsidePct != null && it.bullUpsidePct != null }
                    if (hasBand) {
                        val bandPath = Path()
                        model.points.forEachIndexed { index, point ->
                            val bull = point.bullUpsidePct ?: point.baseUpsidePct
                            val x = xOf(point.epochSeconds)
                            val y = yOf(bull)
                            if (index == 0) bandPath.moveTo(x, y) else bandPath.lineTo(x, y)
                        }
                        for (index in model.points.indices.reversed()) {
                            val point = model.points[index]
                            val bear = point.bearUpsidePct ?: point.baseUpsidePct
                            bandPath.lineTo(xOf(point.epochSeconds), yOf(bear))
                        }
                        bandPath.close()
                        drawPath(bandPath, color = bandColor)
                    }

                    val basePath = Path()
                    model.points.forEachIndexed { index, point ->
                        val x = xOf(point.epochSeconds)
                        val y = yOf(point.baseUpsidePct)
                        if (index == 0) basePath.moveTo(x, y) else basePath.lineTo(x, y)
                    }
                    drawPath(basePath, color = baseColor, style = Stroke(width = 3.dp.toPx()))

                    val last = model.points.last()
                    drawCircle(
                        color = baseColor,
                        radius = 4.dp.toPx(),
                        center = Offset(xOf(last.epochSeconds), yOf(last.baseUpsidePct)),
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 36.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(startLabel, style = MaterialTheme.typography.labelSmall, color = onSurfaceVariant)
                Text(endLabel, style = MaterialTheme.typography.labelSmall, color = onSurfaceVariant)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(modifier = Modifier.size(width = 14.dp, height = 3.dp).background(baseColor))
                    Text("Base DCF", style = MaterialTheme.typography.labelSmall, color = onSurfaceVariant)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Box(modifier = Modifier.size(10.dp).background(bandColor.copy(alpha = 0.7f)))
                    Text("Bear–Bull range", style = MaterialTheme.typography.labelSmall, color = onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun LatestUpsideStat(
    label: String,
    valuePct: Float?,
    emphasize: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = valuePct?.let { formatSignedPctBps((it * 100).toInt()) } ?: "—",
            fontWeight = if (emphasize) FontWeight.Bold else FontWeight.SemiBold,
            style = if (emphasize) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            color = when {
                valuePct == null -> MaterialTheme.colorScheme.onSurfaceVariant
                valuePct >= 0f -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.error
            },
        )
    }
}

private fun formatUpsideTick(pct: Float): String =
    if (pct >= 0f) "+%.0f%%".format(pct) else "%.0f%%".format(pct)

private fun formatChartDate(epochSeconds: Long): String =
    DateTimeFormatter.ofPattern("MMM d")
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochSecond(epochSeconds))
