package com.discountscreener.android.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.discountscreener.android.presentation.dashboard.MarketRegimePillarUi
import kotlin.math.abs
import kotlin.math.min

@Composable
fun RegimePillarCard(pillar: MarketRegimePillarUi) {
    var invert = pillar.id == "volatility"
    var color = scoreColor(pillar.score, invert)
    var pct = min(100, abs(pillar.score))
    var positive = pillar.score >= 0
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(toneColor(pillar.tone)),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = pillar.name + if (pillar.stale) " (stale)" else "",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = (if (pillar.score > 0) "+" else "") + "${pillar.score}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = color,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "conf ${pillar.confidencePct}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.22f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                if (!positive) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(pct / 100f)
                            .height(6.dp)
                            .background(color.copy(alpha = 0.9f)),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(6.dp)
                    .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
            )
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (positive) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(pct / 100f)
                            .height(6.dp)
                            .background(color.copy(alpha = 0.9f)),
                    )
                }
            }
        }
        if (pillar.interpretation.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = pillar.interpretation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        pillar.signals.forEach { signal ->
            Text(
                text = "• ${signal.text}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, start = 8.dp),
            )
        }
    }
}

internal fun scoreColor(score: Int, invert: Boolean): Color {
    var s = if (invert) -score else score
    return when {
        s >= 35 -> Color(0xFF22C55E)
        s <= -35 -> Color(0xFFF43F5E)
        s >= 10 -> Color(0xFF4ADE80)
        s <= -10 -> Color(0xFFFB7185)
        else -> Color(0xFFF59E0B)
    }
}

internal fun toneColor(tone: String): Color = when (tone) {
    "bullish" -> Color(0xFF22C55E)
    "opportunity" -> Color(0xFF38BDF8)
    "caution" -> Color(0xFFFBBF24)
    "bearish" -> Color(0xFFF43F5E)
    else -> Color(0xFF94A3B8)
}

internal fun phaseColor(phase: String): Color = when (phase) {
    "StrongBull" -> Color(0xFF22C55E)
    "Bull" -> Color(0xFF4ADE80)
    "LateBull" -> Color(0xFFF59E0B)
    "Range" -> Color(0xFF94A3B8)
    "Correction" -> Color(0xFFFB923C)
    "Bear" -> Color(0xFFF43F5E)
    "Capitulation" -> Color(0xFFE11D48)
    "Snapback" -> Color(0xFF38BDF8)
    else -> Color(0xFF94A3B8)
}
