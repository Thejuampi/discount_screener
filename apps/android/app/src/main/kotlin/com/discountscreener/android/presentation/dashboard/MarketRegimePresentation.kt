package com.discountscreener.android.presentation.dashboard

import com.discountscreener.android.domain.model.MarketReadStatus
import com.discountscreener.core.regime.MarketRegime
import com.discountscreener.core.regime.RegimePillar
import com.discountscreener.core.regime.RegimeRadarGeometry
import com.discountscreener.core.regime.SIGNAL_HINT_MIN_ABS
import com.discountscreener.core.regime.phaseTitle
import com.discountscreener.core.regime.radarAxisLabel
import com.discountscreener.core.regime.radarRadius
import com.discountscreener.core.regime.stanceTitle
import java.util.Locale
import kotlin.math.abs

data class MarketRegimeChipUi(
    val label: String,
    val value: String,
    val tone: String? = null,
)

data class MarketRegimeRadarAxisUi(
    val id: String,
    val label: String,
    val radius01: Float,
    val weak: Boolean,
)

data class MarketRegimeSignalUi(
    val text: String,
)

data class MarketRegimePillarUi(
    val id: String,
    val name: String,
    val score: Int,
    val confidencePct: String,
    val tone: String,
    val stale: Boolean,
    val interpretation: String,
    val signals: List<MarketRegimeSignalUi>,
)

data class MarketRegimeUi(
    val status: MarketReadStatus,
    val unavailableReason: String? = null,
    val phaseToken: String = "Unknown",
    val phaseLabel: String = "Unknown",
    val exposurePct: Int = 0,
    val stanceLabel: String = "Unknown",
    val newRiskLabel: String = "1.00×",
    val confidencePct: String = "0%",
    val thesis: String = "",
    val reading: String = "",
    val actionBullets: List<String> = emptyList(),
    val environmentScore: Int = 0,
    val sentimentScore: Int = 0,
    val qualityScore: Int = 0,
    val cashBufferPct: Int = 0,
    val preferQuality: Boolean = false,
    val warnings: List<String> = emptyList(),
    val disclaimer: String = RISK_STANCE_DISCLAIMER,
    val chips: List<MarketRegimeChipUi> = emptyList(),
    val radar: List<MarketRegimeRadarAxisUi> = emptyList(),
    val pillars: List<MarketRegimePillarUi> = emptyList(),
)

const val RISK_STANCE_DISCLAIMER =
    "Risk/stance policy — not investment advice. F&G is contrarian (fear→buy bias, greed→reduce)."

fun presentMarketRegime(regime: MarketRegime?, status: MarketReadStatus): MarketRegimeUi {
    if (regime == null || status != MarketReadStatus.Ready) {
        return MarketRegimeUi(
            status = if (regime == null) status else MarketReadStatus.Unavailable,
            unavailableReason = when (status) {
                MarketReadStatus.Pending -> "Loading market regime…"
                MarketReadStatus.Unavailable -> "Market reading is unavailable. Refresh after the feed is live."
                MarketReadStatus.Ready -> null
            },
        )
    }
    var confPct = regime.globalConfidenceBps / 100
    var mult = String.format(Locale.US, "%.2f×", regime.newRiskMultiplierBps / 10_000.0)
    return MarketRegimeUi(
        status = MarketReadStatus.Ready,
        phaseToken = regime.primaryRegime,
        phaseLabel = phaseTitle(regime.primaryRegime),
        exposurePct = regime.suggestedExposurePct,
        stanceLabel = stanceTitle(regime.actionStance),
        newRiskLabel = mult,
        confidencePct = "$confPct%",
        thesis = regime.thesis,
        reading = regime.reading,
        actionBullets = regime.actionBullets,
        environmentScore = regime.environmentScore,
        sentimentScore = regime.sentimentScore,
        qualityScore = regime.qualityScore,
        cashBufferPct = regime.cashBufferPct,
        preferQuality = regime.preferQuality,
        warnings = regime.warnings,
        chips = chipsOf(regime),
        radar = radarOf(regime.pillars),
        pillars = regime.pillars.map(::pillarOf),
    )
}

private fun chipsOf(regime: MarketRegime): List<MarketRegimeChipUi> {
    var chips = ArrayList<MarketRegimeChipUi>()
    var vix = regime.vix
    if (vix != null) {
        var pctl = regime.vixPercentile1y?.let { String.format(Locale.US, " p%.0f", it) }.orEmpty()
        var term = when {
            regime.vixTermRatio == null -> ""
            regime.vixTermRatio!! > 1.0 -> " ⌄"
            else -> " ⌃"
        }
        chips.add(
            MarketRegimeChipUi(
                label = "VIX",
                value = String.format(Locale.US, "%.1f%s%s", vix, pctl, term),
            ),
        )
    }
    var fng = regime.cnnFearGreed
    if (fng != null) {
        var label = regime.cnnFearGreedLabel?.let { " $it" }.orEmpty()
        chips.add(
            MarketRegimeChipUi(
                label = "Fear & Greed",
                value = "$fng$label",
                tone = fearGreedTone(fng),
            ),
        )
    }
    var ma200 = regime.breadthAboveMa200Pct
    if (ma200 != null) {
        var ma50 = regime.breadthAboveMa50Pct?.let { String.format(Locale.US, " / %.0f%%", it) }.orEmpty()
        chips.add(
            MarketRegimeChipUi(
                label = "Breadth",
                value = String.format(Locale.US, "%.0f%%%s", ma200, ma50),
            ),
        )
    }
    var spyUp = regime.spyAboveMa200
    if (spyUp != null) {
        chips.add(
            MarketRegimeChipUi(
                label = "S&P 500",
                value = if (spyUp) "▲ uptrend" else "▼ downtrend",
                tone = if (spyUp) "bullish" else "bearish",
            ),
        )
    }
    var dd = regime.spyDrawdownFromAthPct
    if (dd != null && dd > 1.0) {
        chips.add(
            MarketRegimeChipUi(
                label = "DD from ATH",
                value = String.format(Locale.US, "−%.1f%%", dd),
                tone = "caution",
            ),
        )
    }
    return chips
}

private fun radarOf(pillars: List<RegimePillar>): List<MarketRegimeRadarAxisUi> {
    var byId = pillars.associateBy { it.id }
    return RegimeRadarGeometry.AXIS_ORDER.map { id ->
        var pillar = byId[id]
        var radius = radarRadius(id, pillar?.score ?: 0).coerceIn(0, 100) / 100f
        var weak = pillar == null || pillar.stale || pillar.confidenceBps < 2000
        MarketRegimeRadarAxisUi(
            id = id,
            label = radarAxisLabel(id),
            radius01 = radius,
            weak = weak,
        )
    }
}

private fun pillarOf(pillar: RegimePillar): MarketRegimePillarUi {
    var signals = pillar.signals
        .filter { abs(it.contribution) >= SIGNAL_HINT_MIN_ABS }
        .take(3)
        .map { signal ->
            var parts = ArrayList<String>()
            parts.add(signal.label)
            signal.detail?.let { parts.add("($it)") }
            signal.hint?.let { parts.add("— $it") }
            MarketRegimeSignalUi(parts.joinToString(" "))
        }
    return MarketRegimePillarUi(
        id = pillar.id,
        name = pillar.name,
        score = pillar.score,
        confidencePct = "${pillar.confidenceBps / 100}%",
        tone = pillar.tone,
        stale = pillar.stale,
        interpretation = pillar.interpretation,
        signals = signals,
    )
}

private fun fearGreedTone(score: Int): String = when {
    score <= 24 -> "opportunity"
    score <= 44 -> "opportunity"
    score <= 55 -> null
    score <= 75 -> "caution"
    else -> "bearish"
} ?: "neutral"
