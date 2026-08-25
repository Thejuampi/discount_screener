package com.discountscreener.android.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.discountscreener.android.presentation.dashboard.DashboardAction
import com.discountscreener.android.presentation.dashboard.PlanBoardUi
import com.discountscreener.android.presentation.dashboard.PlanCardUi
import com.discountscreener.android.presentation.dashboard.PlanDipUniverse
import com.discountscreener.android.presentation.dashboard.PlanHunt
import com.discountscreener.core.plan.DipLane

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlansScreen(
    hunt: PlanHunt,
    dipUniverse: PlanDipUniverse,
    dipBoard: PlanBoardUi,
    leftoverBoard: PlanBoardUi,
    crossBoard: PlanBoardUi,
    onAction: (DashboardAction) -> Unit,
) {
    var board = when (hunt) {
        PlanHunt.Dip -> dipBoard
        PlanHunt.Cross -> crossBoard
        PlanHunt.Leftover -> leftoverBoard
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TabRow(
            selectedTabIndex = hunt.ordinal,
            modifier = Modifier.fillMaxWidth(),
        ) {
            PlanHunt.entries.forEach { option ->
                Tab(
                    selected = hunt == option,
                    onClick = { onAction(DashboardAction.SelectPlanHunt(option)) },
                    text = { Text(huntTabLabel(option)) },
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = board.huntLabel,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = board.countsLine,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (hunt == PlanHunt.Dip || hunt == PlanHunt.Cross) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Full profile",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Switch(
                        checked = dipUniverse == PlanDipUniverse.Profile,
                        onCheckedChange = { on ->
                            var universe = if (on) PlanDipUniverse.Profile else PlanDipUniverse.Opportunities
                            onAction(DashboardAction.SelectPlanDipUniverse(universe))
                        },
                    )
                }
            }
            board.universeLine?.let { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            board.offRadarLine?.let { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(
            text = board.nowTitle,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
        if (board.emptyNow) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(board.emptyNowTitle, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    board.emptyNowDetail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            board.now.forEach { card ->
                PlanCard(card) { onAction(DashboardAction.OpenDetail(card.symbol)) }
            }
        }
        if (board.later.isNotEmpty()) {
            Text(
                text = board.laterTitle,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
            )
            board.later.forEach { card ->
                PlanCard(card) { onAction(DashboardAction.OpenDetail(card.symbol)) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlanCard(card: PlanCardUi, onOpen: () -> Unit) {
    var tone = laneColor(card.lane)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .border(1.dp, tone.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .clickable(onClick = onOpen)
            .padding(start = 4.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(card.symbol, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                LaneBadge(card.laneLabel, tone)
                if (card.deathCross) {
                    LaneBadge("50/200-", Color(0xFF64748B))
                }
            }
            Text(card.headline, style = MaterialTheme.typography.bodyMedium)
            if (card.spark.size > 1) {
                PlanSpark(card.spark, tone)
            }
            card.evidence.forEach { line ->
                Text(
                    text = "• $line",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(card.priceLabel, style = MaterialTheme.typography.bodySmall)
                Text(card.streetLabel, style = MaterialTheme.typography.bodySmall)
                Text(card.fLabel, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun LaneBadge(label: String, tone: Color) {
    Text(
        text = label.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(tone)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun PlanSpark(values: List<Long>, tone: Color) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp),
    ) {
        var min = values.minOrNull()?.toFloat() ?: return@Canvas
        var max = values.maxOrNull()?.toFloat() ?: return@Canvas
        var span = (max - min).takeIf { it > 0f } ?: 1f
        var step = size.width / (values.size - 1).coerceAtLeast(1)
        var path = Path()
        values.forEachIndexed { index, value ->
            var x = index * step
            var y = size.height - ((value - min) / span) * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, tone, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        var last = values.last()
        var lastX = (values.size - 1) * step
        var lastY = size.height - ((last - min) / span) * size.height
        drawCircle(tone, radius = 3.dp.toPx(), center = Offset(lastX, lastY))
    }
}

private fun huntTabLabel(hunt: PlanHunt): String = when (hunt) {
    PlanHunt.Dip -> "Dip"
    PlanHunt.Cross -> "Cross"
    PlanHunt.Leftover -> "Leftover"
}

private fun laneColor(lane: DipLane): Color = when (lane) {
    DipLane.Now -> Color(0xFF22C55E)
    DipLane.Almost -> Color(0xFFF59E0B)
    DipLane.Out -> Color(0xFF94A3B8)
}
