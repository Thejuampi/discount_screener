import 'dart:math' as math;

import 'fair_value_selection.dart';
import 'models.dart';

enum QuantLensPrimaryStatus {
  available,
  provisional,
  partial,
  sparse,
  insufficient,
  unavailable,
}
enum QuantLensReasonCode {
  scaffoldPending,
  missingMarketPrice,
  missingBaseSignal,
  missingHorizonCandles,
  invalidHorizonClose,
  insufficientHorizonSamples,
  historicalBaselineAvailable,
  missingPeerSeries,
  insufficientPeerOverlap,
  missingExpectedValueInputs,
  missingTrendCandles,
  insufficientTrendSamples,
  missingSimilarSetups,
}

enum EvidenceStrengthBand { unavailable, sparse, mixed, provisional, strong }

enum ExpectedValueRangeBand { unavailable, sparse, provisional, available }

enum CorrelationRiskBand { unavailable, low, moderate, high }

enum TrendReliabilityBand { unavailable, weak, mixed, strong }

enum SimilarSetupsBand { unavailable, sparse, provisional, available }

enum QuantLensHorizon { fiveMinutes, oneDay, threeMonths }

enum ExpectedValueRangeSource { dcfBase, analystTarget, intrinsic }

class QuantLensEvidenceStrength {
  const QuantLensEvidenceStrength({
    required this.primaryStatus,
    required this.band,
    this.strengthBps = 0,
    this.supportCount = 0,
    this.conflictCount = 0,
    this.neutralCount = 0,
    this.reasonCodes = const [],
  });
  final QuantLensPrimaryStatus primaryStatus;
  final EvidenceStrengthBand band;
  final int strengthBps;
  final int supportCount;
  final int conflictCount;
  final int neutralCount;
  final List<QuantLensReasonCode> reasonCodes;
}

class QuantLensExpectedValueRange {
  const QuantLensExpectedValueRange({
    required this.primaryStatus,
    required this.band,
    this.lowCents,
    this.midCents,
    this.highCents,
    this.upsideLowBps,
    this.upsideMidBps,
    this.upsideHighBps,
    this.source = ExpectedValueRangeSource.intrinsic,
    this.reasonCodes = const [],
  });
  final QuantLensPrimaryStatus primaryStatus;
  final ExpectedValueRangeBand band;
  final int? lowCents;
  final int? midCents;
  final int? highCents;
  final int? upsideLowBps;
  final int? upsideMidBps;
  final int? upsideHighBps;
  final ExpectedValueRangeSource source;
  final List<QuantLensReasonCode> reasonCodes;
}

class QuantLensCorrelationPair {
  const QuantLensCorrelationPair({
    required this.peerSymbol,
    required this.correlationMillis,
  });
  final String peerSymbol;
  final int correlationMillis;
}

class QuantLensCorrelationRisk {
  const QuantLensCorrelationRisk({
    required this.primaryStatus,
    required this.band,
    this.averageAbsCorrelationMillis = 0,
    this.pairs = const [],
    this.reasonCodes = const [],
  });
  final QuantLensPrimaryStatus primaryStatus;
  final CorrelationRiskBand band;
  final int averageAbsCorrelationMillis;
  final List<QuantLensCorrelationPair> pairs;
  final List<QuantLensReasonCode> reasonCodes;
}

class QuantLensTrendReliability {
  const QuantLensTrendReliability({
    required this.primaryStatus,
    required this.band,
    this.persistenceBps = 0,
    this.sampleCount = 0,
    this.reasonCodes = const [],
  });
  final QuantLensPrimaryStatus primaryStatus;
  final TrendReliabilityBand band;
  final int persistenceBps;
  final int sampleCount;
  final List<QuantLensReasonCode> reasonCodes;
}

class QuantLensHorizonBaseline {
  const QuantLensHorizonBaseline({
    required this.horizon,
    required this.primaryStatus,
    required this.sampleCount,
    this.medianAbsoluteMoveBps,
    this.p25AbsoluteMoveBps,
    this.p75AbsoluteMoveBps,
    this.reasonCodes = const [],
  });
  final QuantLensHorizon horizon;
  final QuantLensPrimaryStatus primaryStatus;
  final int sampleCount;
  final int? medianAbsoluteMoveBps;
  final int? p25AbsoluteMoveBps;
  final int? p75AbsoluteMoveBps;
  final List<QuantLensReasonCode> reasonCodes;
}

class QuantLensHorizonContext {
  const QuantLensHorizonContext({
    required this.primaryStatus,
    required this.horizons,
    this.reasonCodes = const [],
  });
  final QuantLensPrimaryStatus primaryStatus;
  final List<QuantLensHorizonBaseline> horizons;
  final List<QuantLensReasonCode> reasonCodes;
}

class SimilarSetupMatch {
  const SimilarSetupMatch({
    required this.peerSymbol,
    required this.similarityBps,
    this.forwardMoveBps,
  });
  final String peerSymbol;
  final int similarityBps;
  final int? forwardMoveBps;
}

class QuantLensSimilarSetups {
  const QuantLensSimilarSetups({
    required this.primaryStatus,
    required this.band,
    this.matches = const [],
    this.reasonCodes = const [],
  });
  final QuantLensPrimaryStatus primaryStatus;
  final SimilarSetupsBand band;
  final List<SimilarSetupMatch> matches;
  final List<QuantLensReasonCode> reasonCodes;
}

class QuantLensInput {
  const QuantLensInput({
    required this.detail,
    required this.selectedRange,
    required this.nowEpochSeconds,
    this.dcfAnalysis,
    this.chartSummaries = const {},
    this.selectedCandlesByRange = const {},
    this.opportunityRows = const [],
    this.peerCandlesBySymbol = const {},
    this.inputFingerprint = '',
  });
  final SymbolDetail detail;
  final ChartRange selectedRange;
  final int nowEpochSeconds;
  final DcfAnalysis? dcfAnalysis;
  final Map<ChartRange, ChartRangeSummary> chartSummaries;
  final Map<ChartRange, List<HistoricalCandle>> selectedCandlesByRange;
  final List<OpportunityRow> opportunityRows;
  final Map<String, List<HistoricalCandle>> peerCandlesBySymbol;
  final String inputFingerprint;
}

class QuantLensReport {
  const QuantLensReport({
    required this.symbol,
    required this.selectedRange,
    required this.computedAtEpochSeconds,
    required this.primaryStatus,
    required this.evidenceStrength,
    required this.expectedValueRange,
    required this.correlationRisk,
    required this.trendReliability,
    required this.horizonContext,
    required this.similarSetups,
    this.notices = const [],
    this.inputFingerprint = '',
  });
  final String symbol;
  final ChartRange selectedRange;
  final int computedAtEpochSeconds;
  final QuantLensPrimaryStatus primaryStatus;
  final QuantLensEvidenceStrength evidenceStrength;
  final QuantLensExpectedValueRange expectedValueRange;
  final QuantLensCorrelationRisk correlationRisk;
  final QuantLensTrendReliability trendReliability;
  final QuantLensHorizonContext horizonContext;
  final QuantLensSimilarSetups similarSetups;
  final List<QuantLensReasonCode> notices;
  final String inputFingerprint;
}

/// Quant Lens six-pack analysis (Android `QuantLensEngine` port).
class QuantLensEngine {
  static const int minHorizonWindows = 10;
  static const int minQuantLensBps = -100000;
  static const int maxQuantLensBps = 100000;

  static QuantLensReport analyze(QuantLensInput input) {
    final evidence = _analyzeEvidenceStrength(input);
    final expected = _analyzeExpectedValueRange(input);
    final correlation = _analyzeCorrelationRisk(input);
    final trend = _analyzeTrendReliability(input);
    final horizon = _analyzeHorizonContext(input);
    final similar = _analyzeSimilarSetups(input, evidence, expected, trend);
    return QuantLensReport(
      symbol: input.detail.symbol,
      selectedRange: input.selectedRange,
      computedAtEpochSeconds: input.nowEpochSeconds,
      inputFingerprint: input.inputFingerprint,
      primaryStatus: _combinedStatus([
        evidence.primaryStatus,
        expected.primaryStatus,
        correlation.primaryStatus,
        trend.primaryStatus,
        horizon.primaryStatus,
        similar.primaryStatus,
      ]),
      evidenceStrength: evidence,
      expectedValueRange: expected,
      correlationRisk: correlation,
      trendReliability: trend,
      horizonContext: horizon,
      similarSetups: similar,
      notices: const [QuantLensReasonCode.scaffoldPending],
    );
  }

  static QuantLensEvidenceStrength _analyzeEvidenceStrength(QuantLensInput input) {
    final detail = input.detail;
    if (detail.marketPriceCents <= 0) {
      return const QuantLensEvidenceStrength(
        primaryStatus: QuantLensPrimaryStatus.unavailable,
        band: EvidenceStrengthBand.unavailable,
        reasonCodes: [QuantLensReasonCode.missingMarketPrice],
      );
    }
    if (detail.intrinsicValueCents <= 0 &&
        detail.externalSignalFairValueCents == null &&
        detail.weightedExternalSignalFairValueCents == null) {
      return const QuantLensEvidenceStrength(
        primaryStatus: QuantLensPrimaryStatus.unavailable,
        band: EvidenceStrengthBand.unavailable,
        reasonCodes: [QuantLensReasonCode.missingBaseSignal],
      );
    }

    final support = <Object?>[
      detail.upsideBps >= detail.minimumGapBps ? detail.upsideBps : null,
      detail.externalSignalFairValueCents != null &&
              detail.externalSignalFairValueCents! > detail.marketPriceCents
          ? detail.externalSignalFairValueCents
          : null,
      input.dcfAnalysis != null &&
              input.dcfAnalysis!.baseIntrinsicValueCents > detail.marketPriceCents
          ? input.dcfAnalysis!.baseIntrinsicValueCents
          : null,
      (input.chartSummaries[input.selectedRange]?.candleCount ?? 0) >= 20
          ? input.chartSummaries[input.selectedRange]
          : null,
      input.opportunityRows
          .where((r) => r.symbol == detail.symbol && r.coverageCount >= 2)
          .firstOrNull,
    ].whereType<Object>().length;

    final conflict = <Object?>[
      detail.externalSignalFairValueCents != null &&
              detail.externalSignalFairValueCents! > 0 &&
              detail.externalSignalFairValueCents! < detail.marketPriceCents
          ? detail.externalSignalFairValueCents
          : null,
      input.dcfAnalysis != null &&
              input.dcfAnalysis!.baseIntrinsicValueCents > 0 &&
              input.dcfAnalysis!.baseIntrinsicValueCents < detail.marketPriceCents
          ? input.dcfAnalysis!.baseIntrinsicValueCents
          : null,
    ].whereType<Object>().length;

    final neutral = math.max(0, 5 - support - conflict);
    final primary = support + conflict < 2
        ? QuantLensPrimaryStatus.sparse
        : conflict > 0
            ? QuantLensPrimaryStatus.available
            : support >= 3
                ? QuantLensPrimaryStatus.available
                : QuantLensPrimaryStatus.provisional;
    final band = primary == QuantLensPrimaryStatus.sparse
        ? EvidenceStrengthBand.sparse
        : conflict > 0
            ? EvidenceStrengthBand.mixed
            : support >= 3
                ? EvidenceStrengthBand.strong
                : EvidenceStrengthBand.provisional;

    return QuantLensEvidenceStrength(
      primaryStatus: primary,
      band: band,
      strengthBps: ((support * 2500) - (conflict * 2000)).clamp(0, 10000),
      supportCount: support,
      conflictCount: conflict,
      neutralCount: neutral,
      reasonCodes: const [QuantLensReasonCode.scaffoldPending],
    );
  }

  static QuantLensExpectedValueRange _analyzeExpectedValueRange(
    QuantLensInput input,
  ) {
    final detail = input.detail;
    final price = detail.marketPriceCents;
    if (price <= 0) {
      return const QuantLensExpectedValueRange(
        primaryStatus: QuantLensPrimaryStatus.unavailable,
        band: ExpectedValueRangeBand.unavailable,
        reasonCodes: [QuantLensReasonCode.missingMarketPrice],
      );
    }

    // Prefer a coherent scenario triple (Android QuantLensEngine): either full
    // DCF bear/base/bull or full analyst low/mid/high — never mix sources so
    // Mid cannot exceed High (QA: ALL Mid $2977 vs High $300).
    final dcfTriple = _positiveSortedTriple(
      input.dcfAnalysis?.bearIntrinsicValueCents,
      input.dcfAnalysis?.baseIntrinsicValueCents,
      input.dcfAnalysis?.bullIntrinsicValueCents,
    );
    final analystMid = detail.weightedExternalSignalFairValueCents ??
        detail.externalSignalFairValueCents;
    final analystTriple = _positiveSortedTriple(
      detail.externalSignalLowFairValueCents,
      analystMid,
      detail.externalSignalHighFairValueCents,
    );

    // Prefer coherent analyst scenario when DCF is implausible vs market
    // (e.g. ALL insurance FCF model >> price) so Lens Mid/High stay consistent
    // with list fair value.
    ExpectedValueRangeSource source;
    List<int> scenario;
    final dcfImplausible = dcfTriple != null &&
        FairValueSelection.isImplausibleVsMarket(dcfTriple[1], price);
    if (analystTriple != null && (dcfTriple == null || dcfImplausible)) {
      source = ExpectedValueRangeSource.analystTarget;
      scenario = analystTriple;
    } else if (dcfTriple != null && !dcfImplausible) {
      source = ExpectedValueRangeSource.dcfBase;
      scenario = dcfTriple;
    } else if (dcfTriple != null) {
      // No analyst triple; still surface DCF scenario (sorted) for transparency.
      source = ExpectedValueRangeSource.dcfBase;
      scenario = dcfTriple;
    } else {
      // Sparse single-anchor fallback for provisional Lens rows.
      final single = FairValueSelection.selectPrimaryCents(
        analystWeightedCents: detail.weightedExternalSignalFairValueCents,
        analystMeanCents: detail.externalSignalFairValueCents,
        dcfBaseCents: input.dcfAnalysis?.baseIntrinsicValueCents,
        fallbackIntrinsicCents: detail.intrinsicValueCents,
      );
      if (single == null) {
        return const QuantLensExpectedValueRange(
          primaryStatus: QuantLensPrimaryStatus.unavailable,
          band: ExpectedValueRangeBand.unavailable,
          reasonCodes: [QuantLensReasonCode.missingExpectedValueInputs],
        );
      }
      return QuantLensExpectedValueRange(
        primaryStatus: QuantLensPrimaryStatus.provisional,
        band: ExpectedValueRangeBand.provisional,
        lowCents: single,
        midCents: single,
        highCents: single,
        upsideLowBps: _boundedUpsideBps(price, single),
        upsideMidBps: _boundedUpsideBps(price, single),
        upsideHighBps: _boundedUpsideBps(price, single),
        source: ExpectedValueRangeSource.intrinsic,
        reasonCodes: const [QuantLensReasonCode.missingExpectedValueInputs],
      );
    }

    final low = scenario[0];
    final mid = scenario[1];
    final high = scenario[2];

    return QuantLensExpectedValueRange(
      primaryStatus: QuantLensPrimaryStatus.available,
      band: ExpectedValueRangeBand.available,
      lowCents: low,
      midCents: mid,
      highCents: high,
      upsideLowBps: _boundedUpsideBps(price, low),
      upsideMidBps: _boundedUpsideBps(price, mid),
      upsideHighBps: _boundedUpsideBps(price, high),
      source: source,
    );
  }

  /// Returns sorted [low, mid, high] when all three are positive, else null.
  static List<int>? _positiveSortedTriple(int? a, int? b, int? c) {
    if (a == null || b == null || c == null) return null;
    if (a <= 0 || b <= 0 || c <= 0) return null;
    final sorted = [a, b, c]..sort();
    return sorted;
  }

  static int? _boundedUpsideBps(int priceCents, int fairCents) {
    if (priceCents <= 0 || fairCents <= 0) return null;
    return ((fairCents - priceCents) * 10000 ~/ priceCents)
        .clamp(minQuantLensBps, maxQuantLensBps);
  }

  static QuantLensCorrelationRisk _analyzeCorrelationRisk(QuantLensInput input) {
    final peers = input.peerCandlesBySymbol;
    if (peers.isEmpty) {
      return const QuantLensCorrelationRisk(
        primaryStatus: QuantLensPrimaryStatus.unavailable,
        band: CorrelationRiskBand.unavailable,
        reasonCodes: [QuantLensReasonCode.missingPeerSeries],
      );
    }
    final self =
        input.selectedCandlesByRange[input.selectedRange] ?? const [];
    if (self.length < 10) {
      return const QuantLensCorrelationRisk(
        primaryStatus: QuantLensPrimaryStatus.insufficient,
        band: CorrelationRiskBand.unavailable,
        reasonCodes: [QuantLensReasonCode.insufficientPeerOverlap],
      );
    }
    final pairs = <QuantLensCorrelationPair>[];
    for (final entry in peers.entries) {
      final corr = _pearsonOnCloses(self, entry.value);
      if (corr == null) continue;
      pairs.add(
        QuantLensCorrelationPair(
          peerSymbol: entry.key,
          correlationMillis: (corr * 1000).round(),
        ),
      );
    }
    if (pairs.isEmpty) {
      return const QuantLensCorrelationRisk(
        primaryStatus: QuantLensPrimaryStatus.insufficient,
        band: CorrelationRiskBand.unavailable,
        reasonCodes: [QuantLensReasonCode.insufficientPeerOverlap],
      );
    }
    final avgAbs = pairs
            .map((p) => p.correlationMillis.abs())
            .reduce((a, b) => a + b) ~/
        pairs.length;
    final band = avgAbs >= 700
        ? CorrelationRiskBand.high
        : avgAbs >= 400
            ? CorrelationRiskBand.moderate
            : CorrelationRiskBand.low;
    return QuantLensCorrelationRisk(
      primaryStatus: QuantLensPrimaryStatus.available,
      band: band,
      averageAbsCorrelationMillis: avgAbs,
      pairs: pairs,
    );
  }

  static QuantLensTrendReliability _analyzeTrendReliability(
    QuantLensInput input,
  ) {
    final candles =
        input.selectedCandlesByRange[input.selectedRange] ?? const [];
    if (candles.isEmpty) {
      return const QuantLensTrendReliability(
        primaryStatus: QuantLensPrimaryStatus.unavailable,
        band: TrendReliabilityBand.unavailable,
        reasonCodes: [QuantLensReasonCode.missingTrendCandles],
      );
    }
    if (candles.length < 20) {
      return QuantLensTrendReliability(
        primaryStatus: QuantLensPrimaryStatus.insufficient,
        band: TrendReliabilityBand.weak,
        sampleCount: candles.length,
        reasonCodes: const [QuantLensReasonCode.insufficientTrendSamples],
      );
    }
    var sameSign = 0;
    var total = 0;
    for (var i = 1; i < candles.length; i++) {
      final prev = candles[i - 1].closeCents - (i >= 2 ? candles[i - 2].closeCents : candles[i - 1].closeCents);
      final cur = candles[i].closeCents - candles[i - 1].closeCents;
      if (prev == 0 || cur == 0) continue;
      total++;
      if (prev.sign == cur.sign) sameSign++;
    }
    if (total < 10) {
      return QuantLensTrendReliability(
        primaryStatus: QuantLensPrimaryStatus.insufficient,
        band: TrendReliabilityBand.weak,
        sampleCount: total,
        reasonCodes: const [QuantLensReasonCode.insufficientTrendSamples],
      );
    }
    final persistenceBps = (sameSign * 10000 ~/ total);
    final band = persistenceBps >= 6000
        ? TrendReliabilityBand.strong
        : persistenceBps >= 4500
            ? TrendReliabilityBand.mixed
            : TrendReliabilityBand.weak;
    return QuantLensTrendReliability(
      primaryStatus: QuantLensPrimaryStatus.available,
      band: band,
      persistenceBps: persistenceBps,
      sampleCount: total,
    );
  }

  static QuantLensHorizonContext _analyzeHorizonContext(QuantLensInput input) {
    final specs = [
      (QuantLensHorizon.fiveMinutes, ChartRange.day, 1),
      (QuantLensHorizon.oneDay, ChartRange.month, 1),
      (QuantLensHorizon.threeMonths, ChartRange.fiveYears, 3),
    ];
    final horizons = specs
        .map((s) => _analyzeHorizonBaseline(input, s.$1, s.$2, s.$3))
        .toList();
    final primary = horizons.every(
      (h) => h.primaryStatus == QuantLensPrimaryStatus.available,
    )
        ? QuantLensPrimaryStatus.available
        : horizons.any((h) => h.primaryStatus == QuantLensPrimaryStatus.available)
            ? QuantLensPrimaryStatus.partial
            : horizons.any(
                (h) => h.primaryStatus == QuantLensPrimaryStatus.insufficient,
              )
                ? QuantLensPrimaryStatus.insufficient
                : QuantLensPrimaryStatus.unavailable;
    return QuantLensHorizonContext(
      primaryStatus: primary,
      horizons: horizons,
      reasonCodes: horizons.expand((h) => h.reasonCodes).toSet().toList(),
    );
  }

  static QuantLensHorizonBaseline _analyzeHorizonBaseline(
    QuantLensInput input,
    QuantLensHorizon horizon,
    ChartRange sourceRange,
    int lagCandles,
  ) {
    final sourceCandles = input.selectedCandlesByRange[sourceRange];
    if (sourceCandles == null || sourceCandles.isEmpty) {
      return QuantLensHorizonBaseline(
        horizon: horizon,
        primaryStatus: QuantLensPrimaryStatus.unavailable,
        sampleCount: 0,
        reasonCodes: const [QuantLensReasonCode.missingHorizonCandles],
      );
    }
    final valid = sourceCandles.where((c) => c.closeCents > 0).toList();
    if (valid.isEmpty) {
      return QuantLensHorizonBaseline(
        horizon: horizon,
        primaryStatus: QuantLensPrimaryStatus.unavailable,
        sampleCount: 0,
        reasonCodes: const [QuantLensReasonCode.invalidHorizonClose],
      );
    }
    final moves = <int>[];
    for (var i = lagCandles; i < valid.length; i++) {
      final base = valid[i - lagCandles].closeCents;
      final cur = valid[i].closeCents;
      if (base <= 0) continue;
      moves.add((((cur - base).abs() * 10000) ~/ base));
    }
    if (moves.length < minHorizonWindows) {
      return QuantLensHorizonBaseline(
        horizon: horizon,
        primaryStatus: QuantLensPrimaryStatus.insufficient,
        sampleCount: moves.length,
        reasonCodes: const [QuantLensReasonCode.insufficientHorizonSamples],
      );
    }
    final sorted = [...moves]..sort();
    return QuantLensHorizonBaseline(
      horizon: horizon,
      primaryStatus: QuantLensPrimaryStatus.available,
      sampleCount: sorted.length,
      medianAbsoluteMoveBps: _nearestRank(sorted, 0.50),
      p25AbsoluteMoveBps: _nearestRank(sorted, 0.25),
      p75AbsoluteMoveBps: _nearestRank(sorted, 0.75),
      reasonCodes: const [QuantLensReasonCode.historicalBaselineAvailable],
    );
  }

  static QuantLensSimilarSetups _analyzeSimilarSetups(
    QuantLensInput input,
    QuantLensEvidenceStrength evidence,
    QuantLensExpectedValueRange expected,
    QuantLensTrendReliability trend,
  ) {
    if (evidence.primaryStatus == QuantLensPrimaryStatus.unavailable ||
        expected.midCents == null) {
      return const QuantLensSimilarSetups(
        primaryStatus: QuantLensPrimaryStatus.unavailable,
        band: SimilarSetupsBand.unavailable,
        reasonCodes: [QuantLensReasonCode.missingSimilarSetups],
      );
    }
    final peers = input.opportunityRows
        .where((r) => r.symbol != input.detail.symbol)
        .take(5)
        .map(
          (r) => SimilarSetupMatch(
            peerSymbol: r.symbol,
            similarityBps:
                (10000 - (r.compositeScore - evidence.strengthBps ~/ 100).abs() * 100)
                    .clamp(0, 10000),
            forwardMoveBps: r.upsideBps,
          ),
        )
        .toList();
    if (peers.isEmpty) {
      return QuantLensSimilarSetups(
        primaryStatus: QuantLensPrimaryStatus.sparse,
        band: SimilarSetupsBand.sparse,
        reasonCodes: const [QuantLensReasonCode.missingSimilarSetups],
      );
    }
    return QuantLensSimilarSetups(
      primaryStatus: QuantLensPrimaryStatus.provisional,
      band: SimilarSetupsBand.provisional,
      matches: peers,
    );
  }

  static int _nearestRank(List<int> sorted, double percentile) {
    if (sorted.isEmpty) return 0;
    final idx =
        ((sorted.length - 1) * percentile).round().clamp(0, sorted.length - 1);
    return sorted[idx];
  }

  static double? _pearsonOnCloses(
    List<HistoricalCandle> a,
    List<HistoricalCandle> b,
  ) {
    final n = a.length < b.length ? a.length : b.length;
    if (n < 10) return null;
    final xs = <double>[];
    final ys = <double>[];
    final start = n > 60 ? n - 60 : 0;
    for (var i = start; i < n; i++) {
      if (a[i].closeCents <= 0 || b[i].closeCents <= 0) continue;
      xs.add(a[i].closeCents.toDouble());
      ys.add(b[i].closeCents.toDouble());
    }
    if (xs.length < 10) return null;
    final meanX = xs.reduce((p, c) => p + c) / xs.length;
    final meanY = ys.reduce((p, c) => p + c) / ys.length;
    var num = 0.0;
    var denX = 0.0;
    var denY = 0.0;
    for (var i = 0; i < xs.length; i++) {
      final dx = xs[i] - meanX;
      final dy = ys[i] - meanY;
      num += dx * dy;
      denX += dx * dx;
      denY += dy * dy;
    }
    final den = math.sqrt(denX * denY);
    if (den == 0) return null;
    return (num / den).clamp(-1.0, 1.0);
  }

  static QuantLensPrimaryStatus _combinedStatus(
    List<QuantLensPrimaryStatus> statuses,
  ) {
    if (statuses.every((s) => s == QuantLensPrimaryStatus.available)) {
      return QuantLensPrimaryStatus.available;
    }
    if (statuses.any((s) => s == QuantLensPrimaryStatus.available) ||
        statuses.any((s) => s == QuantLensPrimaryStatus.provisional)) {
      return QuantLensPrimaryStatus.partial;
    }
    if (statuses.any((s) => s == QuantLensPrimaryStatus.sparse) ||
        statuses.any((s) => s == QuantLensPrimaryStatus.insufficient)) {
      return QuantLensPrimaryStatus.sparse;
    }
    return QuantLensPrimaryStatus.unavailable;
  }
}
