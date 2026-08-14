package com.discountscreener.android.ui.dashboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.discountscreener.android.presentation.dashboard.MarketRegimeRadarAxisUi
import com.discountscreener.core.regime.RegimeRadarGeometry
import android.graphics.Paint
import android.graphics.Typeface

@Composable
fun RegimeRadarChart(
    axes: List<MarketRegimeRadarAxisUi>,
    color: Color,
    modifier: Modifier = Modifier,
) {
    var labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    var ringColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)
    var axisColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
    var description = axes.joinToString(separator = ", ") { "${it.label} ${(it.radius01 * 100).toInt()}" }
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .wrapContentWidth(Alignment.CenterHorizontally)
            .widthIn(max = 240.dp)
            .aspectRatio(1f)
            .semantics { contentDescription = "Regime radar: $description" },
    ) {
        var cx = size.width / 2f
        var cy = size.height / 2f
        var maxR = size.minDimension * 0.34f
        var count = axes.size.coerceAtLeast(1)
        var rings = listOf(0.25f, 0.5f, 0.75f, 1f)
        var labelPaint = Paint().apply {
            this.color = labelColor.toArgb()
            textAlign = Paint.Align.CENTER
            textSize = 9.sp.toPx()
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            isAntiAlias = true
        }
        var labelBaselineShift = (labelPaint.fontMetrics.ascent + labelPaint.fontMetrics.descent) / 2f
        rings.forEach { ring ->
            var ringPath = Path()
            axes.indices.forEach { index ->
                var p = RegimeRadarGeometry.polarPoint(index, count, ring.toDouble(), cx.toDouble(), cy.toDouble(), maxR.toDouble())
                if (index == 0) ringPath.moveTo(p.x.toFloat(), p.y.toFloat()) else ringPath.lineTo(p.x.toFloat(), p.y.toFloat())
            }
            ringPath.close()
            drawPath(ringPath, color = ringColor, style = Stroke(width = 1f))
        }
        axes.forEachIndexed { index, axis ->
            var edge = RegimeRadarGeometry.polarPoint(index, count, 1.0, cx.toDouble(), cy.toDouble(), maxR.toDouble())
            var labelPt = RegimeRadarGeometry.polarPoint(index, count, 1.18, cx.toDouble(), cy.toDouble(), maxR.toDouble())
            drawLine(
                color = if (axis.weak) axisColor.copy(alpha = 0.35f) else axisColor,
                start = Offset(cx, cy),
                end = Offset(edge.x.toFloat(), edge.y.toFloat()),
                strokeWidth = 1.5f,
            )
            drawContext.canvas.nativeCanvas.drawText(
                axis.label,
                labelPt.x.toFloat(),
                labelPt.y.toFloat() - labelBaselineShift,
                labelPaint,
            )
        }
        var poly = Path()
        axes.forEachIndexed { index, axis ->
            var p = RegimeRadarGeometry.polarPoint(
                index,
                count,
                axis.radius01.toDouble().coerceIn(0.0, 1.0),
                cx.toDouble(),
                cy.toDouble(),
                maxR.toDouble(),
            )
            if (index == 0) poly.moveTo(p.x.toFloat(), p.y.toFloat()) else poly.lineTo(p.x.toFloat(), p.y.toFloat())
        }
        poly.close()
        drawPath(poly, color = color.copy(alpha = 0.22f))
        drawPath(poly, color = color, style = Stroke(width = 4f))
        axes.forEachIndexed { index, axis ->
            var p = RegimeRadarGeometry.polarPoint(
                index,
                count,
                axis.radius01.toDouble().coerceIn(0.0, 1.0),
                cx.toDouble(),
                cy.toDouble(),
                maxR.toDouble(),
            )
            drawCircle(
                color = color,
                radius = 8f,
                center = Offset(p.x.toFloat(), p.y.toFloat()),
                alpha = if (axis.weak) 0.45f else 1f,
            )
        }
    }
}
