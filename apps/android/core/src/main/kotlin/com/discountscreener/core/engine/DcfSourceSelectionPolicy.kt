package com.discountscreener.core.engine

import com.discountscreener.core.model.AnnualReportedValue
import com.discountscreener.core.model.DcfSource
import com.discountscreener.core.model.DcfSourceCandidate
import com.discountscreener.core.model.DcfSourcePolicyConfig
import com.discountscreener.core.model.DcfSourceSelection
import com.discountscreener.core.model.DcfProviderQuality
import com.discountscreener.core.model.FundamentalTimeseries
import com.discountscreener.core.model.ProviderDecisionReason
import com.discountscreener.core.model.ProviderDecisionReasonCode
import com.discountscreener.core.model.ProviderState
import com.discountscreener.core.model.RefreshDisposition
import com.discountscreener.core.model.ResolverState
import kotlin.math.abs

object DcfSourceSelectionPolicy {
    fun select(
        yahoo: DcfSourceCandidate? = null,
        sec: DcfSourceCandidate? = null,
        config: DcfSourcePolicyConfig = DcfSourcePolicyConfig(),
    ): DcfSourceSelection {
        var candidates = listOfNotNull(yahoo, sec)
        if (candidates.isEmpty()) {
            var reasons = listOf(
                reason(ProviderDecisionReasonCode.ProviderConfigurationAbsent),
                reason(ProviderDecisionReasonCode.NoEnabledProviders),
            )
            return DcfSourceSelection(
                resolverState = ResolverState.Unavailable,
                refreshDisposition = RefreshDisposition.BlockedUntilProviderEnabled,
                reasons = reasons,
                decisionFingerprint = decisionFingerprint(emptyList(), reasons),
            )
        }

        var qualities = candidates.map(::quality)
        var usable = candidates.zip(qualities).filter { (_, quality) -> quality.providerState == ProviderState.Live }
        if (usable.size >= 2 && materiallyDisagree(usable.map { it.first }, config)) {
            var reasons = usable.flatMap { it.second.reasons } + reason(
                code = ProviderDecisionReasonCode.ProviderDisagreement,
                provider = DcfSource.Unknown,
            )
            return DcfSourceSelection(
                resolverState = ResolverState.ProviderUncertain,
                providerQualities = qualities,
                reasons = reasons,
                decisionFingerprint = decisionFingerprint(qualities, reasons),
            )
        }

        var selected = usable.minWithOrNull(
            compareBy<Pair<DcfSourceCandidate, DcfProviderQuality>> { priorityIndex(config, it.first.source) }
                .thenByDescending { it.second.latestFiscalPeriod ?: "" }
                .thenByDescending { it.second.acceptedAnnualFcfPoints }
                .thenBy { it.first.source.name },
        )?.first
        if (selected != null) {
            return selected(selected, qualities, config)
        }

        var reasons = qualities.flatMap { it.reasons }
        var terminalOnly = qualities.isNotEmpty() && qualities.all { quality ->
            quality.providerState == ProviderState.NotEligible || quality.providerState == ProviderState.UnsupportedSymbol
        }
        return DcfSourceSelection(
            resolverState = if (terminalOnly) ResolverState.NotEligible else ResolverState.Unavailable,
            refreshDisposition = if (terminalOnly) {
                RefreshDisposition.TerminalUntilInputsChange
            } else {
                RefreshDisposition.RetryableRefresh
            },
            providerQualities = qualities,
            reasons = reasons,
            decisionFingerprint = decisionFingerprint(qualities, reasons),
        )
    }

    fun isDcfUsable(candidate: DcfSourceCandidate): Boolean {
        return quality(candidate).providerState == ProviderState.Live
    }

    fun quality(candidate: DcfSourceCandidate): DcfProviderQuality {
        if (candidate.providerState == ProviderState.ProviderDisabled) {
            var reasons = candidate.reasons.ifEmpty {
                listOf(reason(ProviderDecisionReasonCode.ProviderDisabled, candidate.source))
            }
            return DcfProviderQuality(candidate.source, ProviderState.ProviderDisabled, reasons = reasons)
        }
        val timeseries = candidate.timeseries
        if (timeseries == null) {
            var reasons = candidate.reasons.ifEmpty {
                listOf(reason(ProviderDecisionReasonCode.NetworkUnavailable, candidate.source))
            }
            return DcfProviderQuality(candidate.source, ProviderState.Unavailable, reasons = reasons)
        }
        if (candidate.analysis == null) {
            return DcfProviderQuality(
                source = candidate.source,
                providerState = ProviderState.NotEligible,
                reasons = listOf(reason(ProviderDecisionReasonCode.MissingMarketCap, candidate.source)),
            )
        }
        val annualFreeCashFlow = acceptedAnnualFreeCashFlow(timeseries)
        if (annualFreeCashFlow.size < MIN_ANNUAL_FCF_POINTS) {
            return DcfProviderQuality(
                source = candidate.source,
                providerState = ProviderState.NotEligible,
                acceptedAnnualFcfPoints = annualFreeCashFlow.size,
                latestFiscalPeriod = annualFreeCashFlow.lastOrNull()?.asOfDate,
                reasons = listOf(reason(ProviderDecisionReasonCode.InsufficientAnnualPeriods, candidate.source)),
            )
        }
        if (annualFreeCashFlow.last().value <= 0.0) {
            return DcfProviderQuality(
                source = candidate.source,
                providerState = ProviderState.NotEligible,
                acceptedAnnualFcfPoints = annualFreeCashFlow.size,
                latestFiscalPeriod = annualFreeCashFlow.last().asOfDate,
                reasons = listOf(reason(ProviderDecisionReasonCode.LatestFcfNonPositive, candidate.source)),
            )
        }
        return DcfProviderQuality(
            source = candidate.source,
            providerState = ProviderState.Live,
            acceptedAnnualFcfPoints = annualFreeCashFlow.size,
            latestFiscalPeriod = annualFreeCashFlow.last().asOfDate,
            reasons = candidate.reasons,
        )
    }

    private fun selected(
        candidate: DcfSourceCandidate,
        qualities: List<DcfProviderQuality>,
        config: DcfSourcePolicyConfig,
    ): DcfSourceSelection {
        val timeseries = candidate.timeseries ?: return DcfSourceSelection()
        val analysis = candidate.analysis ?: return DcfSourceSelection()
        var inputFingerprint = fingerprint(candidate.source, timeseries)
        var selectedReasons = qualities.flatMap { it.reasons }
        return DcfSourceSelection(
            selectedSource = candidate.source,
            timeseries = timeseries,
            analysis = analysis.copy(
                source = candidate.source,
                sourceFingerprint = inputFingerprint,
                resolverState = ResolverState.Selected,
                decisionFingerprint = decisionFingerprint(qualities, selectedReasons),
                provenance = analysis.provenance.copy(
                    source = candidate.source,
                    providerState = ProviderState.Live,
                    fallbackReason = null,
                ),
                providerReasons = selectedReasons,
            ),
            resolverState = ResolverState.Selected,
            providerQualities = qualities,
            reasons = selectedReasons,
            inputFingerprint = inputFingerprint,
            decisionFingerprint = decisionFingerprint(qualities, selectedReasons + priorityReason(candidate.source, config)),
        )
    }

    private fun acceptedAnnualFreeCashFlow(timeseries: FundamentalTimeseries): List<AnnualReportedValue> =
        timeseries.freeCashFlow
            .filter { point -> point.asOfDate.isNotBlank() && point.value.isFinite() }
            .distinctBy { point -> point.asOfDate }
            .sortedBy { point -> point.asOfDate }

    private fun fingerprint(source: DcfSource, timeseries: FundamentalTimeseries): String = buildString {
        append(source.name)
        append("|fcf=")
        append(seriesFingerprint(acceptedAnnualFreeCashFlow(timeseries)))
        append("|shares=")
        append(seriesFingerprint(timeseries.dilutedAverageShares))
    }

    private fun decisionFingerprint(
        qualities: List<DcfProviderQuality>,
        reasons: List<ProviderDecisionReason>,
    ): String = buildString {
        append("qualities=")
        append(
            qualities.sortedBy { it.source.name }.joinToString(";") { quality ->
                listOf(
                    quality.source.name,
                    quality.providerState.name,
                    quality.acceptedAnnualFcfPoints,
                    quality.latestFiscalPeriod.orEmpty(),
                    quality.reasons.map { it.code.name }.sorted().joinToString(","),
                ).joinToString(":")
            },
        )
        append("|reasons=")
        append(reasons.map { "${it.provider.name}:${it.code.name}" }.sorted().joinToString(","))
    }

    private fun materiallyDisagree(
        candidates: List<DcfSourceCandidate>,
        config: DcfSourcePolicyConfig,
    ): Boolean {
        var usable = candidates.filter { isDcfUsable(it) }
        if (usable.size < 2) return false
        var first = acceptedAnnualFreeCashFlow(usable[0].timeseries ?: return false).associateBy { it.asOfDate }
        var second = acceptedAnnualFreeCashFlow(usable[1].timeseries ?: return false).associateBy { it.asOfDate }
        var overlap = first.keys.intersect(second.keys).sorted()
        if (overlap.size < 2) return true
        var latest = overlap.last()
        var latestDelta = relativeDeltaBps(first.getValue(latest).value, second.getValue(latest).value, config)
        var medianDelta = overlap.map { period ->
            relativeDeltaBps(first.getValue(period).value, second.getValue(period).value, config)
        }.sorted().let { deltas -> deltas[deltas.size / 2] }
        var signMismatch = overlap.any { period ->
            normalizedSign(first.getValue(period).value, config) * normalizedSign(second.getValue(period).value, config) < 0
        }
        return signMismatch || latestDelta > config.disagreementThresholdBps || medianDelta > config.disagreementThresholdBps
    }

    private fun relativeDeltaBps(
        left: Double,
        right: Double,
        config: DcfSourcePolicyConfig,
    ): Int {
        var denominator = maxOf(abs(left), abs(right), config.nearZeroFcfFloor)
        return ((abs(left - right) * 10_000.0) / denominator).toInt()
    }

    private fun normalizedSign(value: Double, config: DcfSourcePolicyConfig): Int = when {
        abs(value) <= config.nearZeroFcfFloor -> 0
        value > 0.0 -> 1
        else -> -1
    }

    private fun priorityIndex(config: DcfSourcePolicyConfig, source: DcfSource): Int {
        var index = config.providerPriority.indexOf(source)
        return if (index >= 0) index else Int.MAX_VALUE
    }

    private fun reason(
        code: ProviderDecisionReasonCode,
        provider: DcfSource = DcfSource.Unknown,
    ) = ProviderDecisionReason(code = code, provider = provider)

    private fun priorityReason(
        source: DcfSource,
        config: DcfSourcePolicyConfig,
    ) = ProviderDecisionReason(
        code = ProviderDecisionReasonCode.ProviderDisagreement,
        provider = source,
        thresholdBps = config.disagreementThresholdBps,
    )

    private fun seriesFingerprint(values: List<AnnualReportedValue>): String = values
        .filter { point -> point.asOfDate.isNotBlank() && point.value.isFinite() }
        .sortedBy { point -> point.asOfDate }
        .joinToString(";") { point -> "${point.asOfDate}:${point.value}" }

    private const val MIN_ANNUAL_FCF_POINTS = 3
}