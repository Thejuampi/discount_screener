import 'dart:math' as math;

import 'models.dart';

const int minAnnualFcfPoints = 3;

class DcfSourceSelectionPolicy {
  static DcfSourceSelection select({
    DcfSourceCandidate? yahoo,
    DcfSourceCandidate? sec,
    DcfSourcePolicyConfig config = const DcfSourcePolicyConfig(),
  }) {
    final candidates = [if (yahoo != null) yahoo, if (sec != null) sec];
    if (candidates.isEmpty) {
      final reasons = [
        _reason(ProviderDecisionReasonCode.providerConfigurationAbsent),
        _reason(ProviderDecisionReasonCode.noEnabledProviders),
      ];
      return DcfSourceSelection(
        resolverState: ResolverState.unavailable,
        refreshDisposition: RefreshDisposition.blockedUntilProviderEnabled,
        reasons: reasons,
        decisionFingerprint: _decisionFingerprint(const [], reasons),
      );
    }

    final qualities = candidates.map(quality).toList();
    final usable = <(DcfSourceCandidate, DcfProviderQuality)>[];
    for (var i = 0; i < candidates.length; i++) {
      if (qualities[i].providerState == ProviderState.live) {
        usable.add((candidates[i], qualities[i]));
      }
    }

    if (usable.length >= 2 &&
        _materiallyDisagree(usable.map((e) => e.$1).toList(), config)) {
      final reasons = [
        ...usable.expand((e) => e.$2.reasons),
        _reason(ProviderDecisionReasonCode.providerDisagreement),
      ];
      return DcfSourceSelection(
        resolverState: ResolverState.providerUncertain,
        providerQualities: qualities,
        reasons: reasons,
        decisionFingerprint: _decisionFingerprint(qualities, reasons),
      );
    }

    usable.sort((a, b) {
      final pa = _priorityIndex(config, a.$1.source);
      final pb = _priorityIndex(config, b.$1.source);
      if (pa != pb) return pa.compareTo(pb);
      final la = a.$2.latestFiscalPeriod ?? '';
      final lb = b.$2.latestFiscalPeriod ?? '';
      final dateCmp = lb.compareTo(la);
      if (dateCmp != 0) return dateCmp;
      final pts = b.$2.acceptedAnnualFcfPoints.compareTo(a.$2.acceptedAnnualFcfPoints);
      if (pts != 0) return pts;
      return dcfSourceWireName(a.$1.source).compareTo(dcfSourceWireName(b.$1.source));
    });

    if (usable.isNotEmpty) {
      return _selected(usable.first.$1, qualities, config);
    }

    final reasons = qualities.expand((q) => q.reasons).toList();
    final terminalOnly = qualities.isNotEmpty &&
        qualities.every(
          (q) =>
              q.providerState == ProviderState.notEligible ||
              q.providerState == ProviderState.unsupportedSymbol,
        );
    return DcfSourceSelection(
      resolverState: terminalOnly ? ResolverState.notEligible : ResolverState.unavailable,
      refreshDisposition: terminalOnly
          ? RefreshDisposition.terminalUntilInputsChange
          : RefreshDisposition.retryableRefresh,
      providerQualities: qualities,
      reasons: reasons,
      decisionFingerprint: _decisionFingerprint(qualities, reasons),
    );
  }

  static bool isDcfUsable(DcfSourceCandidate candidate) =>
      quality(candidate).providerState == ProviderState.live;

  static DcfProviderQuality quality(DcfSourceCandidate candidate) {
    if (candidate.providerState == ProviderState.providerDisabled) {
      final reasons = candidate.reasons.isEmpty
          ? [_reason(ProviderDecisionReasonCode.providerDisabled, candidate.source)]
          : candidate.reasons;
      return DcfProviderQuality(
        source: candidate.source,
        providerState: ProviderState.providerDisabled,
        reasons: reasons,
      );
    }
    final timeseries = candidate.timeseries;
    if (timeseries == null) {
      final reasons = candidate.reasons.isEmpty
          ? [_reason(ProviderDecisionReasonCode.networkUnavailable, candidate.source)]
          : candidate.reasons;
      return DcfProviderQuality(
        source: candidate.source,
        providerState: ProviderState.unavailable,
        reasons: reasons,
      );
    }
    if (candidate.analysis == null) {
      return DcfProviderQuality(
        source: candidate.source,
        providerState: ProviderState.notEligible,
        reasons: [_reason(ProviderDecisionReasonCode.missingMarketCap, candidate.source)],
      );
    }
    final annual = _acceptedAnnualFreeCashFlow(timeseries);
    if (annual.length < minAnnualFcfPoints) {
      return DcfProviderQuality(
        source: candidate.source,
        providerState: ProviderState.notEligible,
        acceptedAnnualFcfPoints: annual.length,
        latestFiscalPeriod: annual.isEmpty ? null : annual.last.asOfDate,
        reasons: [
          _reason(ProviderDecisionReasonCode.insufficientAnnualPeriods, candidate.source),
        ],
      );
    }
    if (annual.last.value <= 0.0) {
      return DcfProviderQuality(
        source: candidate.source,
        providerState: ProviderState.notEligible,
        acceptedAnnualFcfPoints: annual.length,
        latestFiscalPeriod: annual.last.asOfDate,
        reasons: [
          _reason(ProviderDecisionReasonCode.latestFcfNonPositive, candidate.source),
        ],
      );
    }
    return DcfProviderQuality(
      source: candidate.source,
      providerState: ProviderState.live,
      acceptedAnnualFcfPoints: annual.length,
      latestFiscalPeriod: annual.last.asOfDate,
      reasons: candidate.reasons,
    );
  }

  static DcfSourceSelection _selected(
    DcfSourceCandidate candidate,
    List<DcfProviderQuality> qualities,
    DcfSourcePolicyConfig config,
  ) {
    final timeseries = candidate.timeseries;
    final analysis = candidate.analysis;
    if (timeseries == null || analysis == null) {
      return const DcfSourceSelection();
    }
    final inputFingerprint = _fingerprint(candidate.source, timeseries);
    final selectedReasons = qualities.expand((q) => q.reasons).toList();
    return DcfSourceSelection(
      selectedSource: candidate.source,
      timeseries: timeseries,
      analysis: analysis.copyWith(
        source: candidate.source,
        sourceFingerprint: inputFingerprint,
        resolverState: ResolverState.selected,
        decisionFingerprint: _decisionFingerprint(qualities, selectedReasons),
        provenance: analysis.provenance.copyWith(
          source: candidate.source,
          providerState: ProviderState.live,
          clearFallback: true,
        ),
        providerReasons: selectedReasons,
      ),
      resolverState: ResolverState.selected,
      providerQualities: qualities,
      reasons: selectedReasons,
      inputFingerprint: inputFingerprint,
      decisionFingerprint: _decisionFingerprint(
        qualities,
        [...selectedReasons, _priorityReason(candidate.source, config)],
      ),
    );
  }

  static List<AnnualReportedValue> _acceptedAnnualFreeCashFlow(
    FundamentalTimeseries timeseries,
  ) {
    final filtered = timeseries.freeCashFlow
        .where((p) => p.asOfDate.trim().isNotEmpty && p.value.isFinite)
        .toList();
    final byDate = <String, AnnualReportedValue>{};
    for (final p in filtered) {
      byDate[p.asOfDate] = p;
    }
    final list = byDate.values.toList()
      ..sort((a, b) => a.asOfDate.compareTo(b.asOfDate));
    return list;
  }

  static String _fingerprint(DcfSource source, FundamentalTimeseries timeseries) {
    final fcf = _seriesFingerprint(_acceptedAnnualFreeCashFlow(timeseries));
    final shares = _seriesFingerprint(timeseries.dilutedAverageShares);
    return '${dcfSourceWireName(source)}|fcf=$fcf|shares=$shares';
  }

  static String _decisionFingerprint(
    List<DcfProviderQuality> qualities,
    List<ProviderDecisionReason> reasons,
  ) {
    final qParts = [...qualities]
      ..sort((a, b) => dcfSourceWireName(a.source).compareTo(dcfSourceWireName(b.source)));
    final qualitiesStr = qParts.map((q) {
      final codes = q.reasons.map((r) => reasonCodeWireName(r.code)).toList()..sort();
      return [
        dcfSourceWireName(q.source),
        _providerStateWire(q.providerState),
        q.acceptedAnnualFcfPoints,
        q.latestFiscalPeriod ?? '',
        codes.join(','),
      ].join(':');
    }).join(';');
    final reasonParts = reasons
        .map((r) => '${dcfSourceWireName(r.provider)}:${reasonCodeWireName(r.code)}')
        .toList()
      ..sort();
    return 'qualities=$qualitiesStr|reasons=${reasonParts.join(',')}';
  }

  static bool _materiallyDisagree(
    List<DcfSourceCandidate> candidates,
    DcfSourcePolicyConfig config,
  ) {
    final usable = candidates.where(isDcfUsable).toList();
    if (usable.length < 2) return false;
    final firstTs = usable[0].timeseries;
    final secondTs = usable[1].timeseries;
    if (firstTs == null || secondTs == null) return false;
    final first = {
      for (final p in _acceptedAnnualFreeCashFlow(firstTs)) p.asOfDate: p,
    };
    final second = {
      for (final p in _acceptedAnnualFreeCashFlow(secondTs)) p.asOfDate: p,
    };
    final overlap = first.keys.where(second.containsKey).toList()..sort();
    if (overlap.length < 2) return true;
    final latest = overlap.last;
    final latestDelta =
        _relativeDeltaBps(first[latest]!.value, second[latest]!.value, config);
    final deltas = overlap
        .map((p) => _relativeDeltaBps(first[p]!.value, second[p]!.value, config))
        .toList()
      ..sort();
    final medianDelta = deltas[deltas.length ~/ 2];
    final signMismatch = overlap.any((p) {
      return _normalizedSign(first[p]!.value, config) *
              _normalizedSign(second[p]!.value, config) <
          0;
    });
    return signMismatch ||
        latestDelta > config.disagreementThresholdBps ||
        medianDelta > config.disagreementThresholdBps;
  }

  static int _relativeDeltaBps(double left, double right, DcfSourcePolicyConfig config) {
    final denominator = math.max(
      math.max(left.abs(), right.abs()),
      config.nearZeroFcfFloor,
    );
    return ((left - right).abs() * 10000.0 / denominator).toInt();
  }

  static int _normalizedSign(double value, DcfSourcePolicyConfig config) {
    if (value.abs() <= config.nearZeroFcfFloor) return 0;
    return value > 0 ? 1 : -1;
  }

  static int _priorityIndex(DcfSourcePolicyConfig config, DcfSource source) {
    final index = config.providerPriority.indexOf(source);
    return index >= 0 ? index : 0x7fffffff;
  }

  static ProviderDecisionReason _reason(
    ProviderDecisionReasonCode code, [
    DcfSource provider = DcfSource.unknown,
  ]) =>
      ProviderDecisionReason(code: code, provider: provider);

  static ProviderDecisionReason _priorityReason(
    DcfSource source,
    DcfSourcePolicyConfig config,
  ) =>
      ProviderDecisionReason(
        code: ProviderDecisionReasonCode.providerDisagreement,
        provider: source,
        thresholdBps: config.disagreementThresholdBps,
      );

  static String _seriesFingerprint(List<AnnualReportedValue> values) {
    final filtered = values
        .where((p) => p.asOfDate.trim().isNotEmpty && p.value.isFinite)
        .toList()
      ..sort((a, b) => a.asOfDate.compareTo(b.asOfDate));
    return filtered.map((p) => '${p.asOfDate}:${p.value}').join(';');
  }

  static String _providerStateWire(ProviderState state) => switch (state) {
        ProviderState.live => 'Live',
        ProviderState.restoredOnly => 'RestoredOnly',
        ProviderState.stale => 'Stale',
        ProviderState.unavailable => 'Unavailable',
        ProviderState.notEligible => 'NotEligible',
        ProviderState.unsupportedSymbol => 'UnsupportedSymbol',
        ProviderState.providerDisabled => 'ProviderDisabled',
        ProviderState.parseUncertain => 'ParseUncertain',
        ProviderState.providerUncertain => 'ProviderUncertain',
        ProviderState.rejected => 'Rejected',
        ProviderState.cancelled => 'Cancelled',
      };
}

/// Wire names for reason codes must match Kotlin PascalCase enum names.
String reasonCodeWireName(ProviderDecisionReasonCode code) => switch (code) {
      ProviderDecisionReasonCode.networkUnavailable => 'NetworkUnavailable',
      ProviderDecisionReasonCode.httpStatus => 'HttpStatus',
      ProviderDecisionReasonCode.rateLimited => 'RateLimited',
      ProviderDecisionReasonCode.providerDisabled => 'ProviderDisabled',
      ProviderDecisionReasonCode.providerConfigurationAbsent => 'ProviderConfigurationAbsent',
      ProviderDecisionReasonCode.noEnabledProviders => 'NoEnabledProviders',
      ProviderDecisionReasonCode.desktopSecDeferred => 'DesktopSecDeferred',
      ProviderDecisionReasonCode.symbolUnsupported => 'SymbolUnsupported',
      ProviderDecisionReasonCode.nonUsIssuerUnsupported => 'NonUsIssuerUnsupported',
      ProviderDecisionReasonCode.fundOrEtfUnsupported => 'FundOrEtfUnsupported',
      ProviderDecisionReasonCode.missingCik => 'MissingCik',
      ProviderDecisionReasonCode.missingAnnualFcf => 'MissingAnnualFcf',
      ProviderDecisionReasonCode.latestFcfNonPositive => 'LatestFcfNonPositive',
      ProviderDecisionReasonCode.insufficientAnnualPeriods => 'InsufficientAnnualPeriods',
      ProviderDecisionReasonCode.missingMarketCap => 'MissingMarketCap',
      ProviderDecisionReasonCode.missingShares => 'MissingShares',
      ProviderDecisionReasonCode.missingDebtOrCash => 'MissingDebtOrCash',
      ProviderDecisionReasonCode.missingBeta => 'MissingBeta',
      ProviderDecisionReasonCode.staleFiscalPeriod => 'StaleFiscalPeriod',
      ProviderDecisionReasonCode.fiscalPeriodMisaligned => 'FiscalPeriodMisaligned',
      ProviderDecisionReasonCode.providerDisagreement => 'ProviderDisagreement',
      ProviderDecisionReasonCode.restoredWithoutLiveRefresh => 'RestoredWithoutLiveRefresh',
      ProviderDecisionReasonCode.legacySourceFreePayload => 'LegacySourceFreePayload',
      ProviderDecisionReasonCode.generationSuperseded => 'GenerationSuperseded',
      ProviderDecisionReasonCode.cancelled => 'Cancelled',
    };
