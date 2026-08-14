package com.discountscreener.android.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.discountscreener.android.domain.model.MarketReadStatus
import com.discountscreener.android.presentation.dashboard.MarketRegimeChipUi
import com.discountscreener.android.presentation.dashboard.MarketRegimeUi

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MarketRegimeScreen(state: MarketRegimeUi) {
    if (state.status != MarketReadStatus.Ready) {
        EmptyState(
            title = if (state.status == MarketReadStatus.Pending) "Loading market regime" else "Market reading unavailable",
            detail = state.unavailableReason ?: "Refresh after the feed is live.",
        )
        return
    }
    var phaseTint = phaseColor(state.phaseToken)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .clip(RoundedCornerShape(10.dp))
            .background(phaseTint.copy(alpha = 0.08f))
            .border(1.dp, phaseTint.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(phaseTint),
            )
            Text(
                text = "  MARKET REGIME  ",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = state.phaseLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = phaseTint,
            )
        }
        Text(
            text = "Exposure ceiling ${state.exposurePct}%  ·  Stance ${state.stanceLabel}  ·  New risk ${state.newRiskLabel}  ·  Conf ${state.confidencePct}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.chips.forEach { chip -> RegimeChip(chip) }
        }
        if (state.thesis.isNotBlank()) {
            Text(
                text = state.thesis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SectionLabel("Regime radar")
        RegimeRadarChart(axes = state.radar, color = phaseTint)
        Text(
            text = "Center = worse · Edge = better · F&G = contrarian opportunity · Vol = calm",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SectionLabel("Aggregate reading")
        Text(
            text = state.reading.ifBlank { state.thesis },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (state.actionBullets.isNotEmpty()) {
            SectionLabel("What to do")
            state.actionBullets.forEach { bullet ->
                Text(
                    text = "• $bullet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
        }
        Text(
            text = buildString {
                append("E=${state.environmentScore} · S=${state.sentimentScore} · Q=${state.qualityScore}")
                append(" · Cash buffer: ${state.cashBufferPct}%")
                if (state.preferQuality) append(" · prefer quality")
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (state.warnings.isNotEmpty()) {
            Text(
                text = state.warnings.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFFFBBF24),
            )
        }
        Text(
            text = state.disclaimer,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SectionLabel("Pillars (interpretation)")
        state.pillars.forEach { pillar -> RegimePillarCard(pillar) }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun RegimeChip(chip: MarketRegimeChipUi) {
    var color = when (chip.tone) {
        "bullish" -> Color(0xFF22C55E)
        "bearish" -> Color(0xFFF43F5E)
        "caution" -> Color(0xFFFBBF24)
        "opportunity" -> Color(0xFF38BDF8)
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(horizontalAlignment = Alignment.Start) {
        Text(
            text = chip.label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = chip.value,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = color,
        )
    }
}
