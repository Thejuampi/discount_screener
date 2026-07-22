import 'dart:math' as math;

import 'models.dart';
import 'reporting_engine.dart';

const int dcfOpportunityThresholdBps = 2000;
const int dcfExpensiveThresholdBps = -1000;

const int legacyAvoidBelowScore = 8;
const int legacyActAtOrAboveScore = 10;
const int continuousAvoidBelowScore = 0;
const int continuousActAtOrAboveScore = 30;

// AggressiveV2 tuning
const double _v2FundFcfYieldLower = -0.02;
const double _v2FundFcfYieldUpper = 0.08;
const double _v2FundFcfWeight = 25.0;
const double _v2FundOcfFallbackWeight = 10.0;
const double _v2FundRoeLowerBps = 0.0;
const double _v2FundRoeUpperBps = 2000.0;
const double _v2FundRoeWeight = 20.0;
const double _v2FundGrowthLowerBps = -500.0;
const double _v2FundGrowthUpperBps = 1500.0;
const double _v2FundGrowthWeight = 15.0;
const double _v2FundBalanceDeLow = 30.0;
const double _v2FundBalanceDeHigh = 200.0;
const double _v2FundBalanceWeight = 20.0;
const double _v2FundPeLow = 800.0;
const double _v2FundPeHigh = 3500.0;
const double _v2FundPeWeight = 20.0;

const double _v2TechTrendDeltaBound = 0.10;
const double _v2TechTrend2050Weight = 24.0;
const double _v2TechTrend50200Weight = 21.0;
const double _v2TechTrendPrice20Weight = 15.0;
const double _v2TechHistogramBound = 0.005;
const double _v2TechHistogramWeight = 25.0;
const double _v2TechMacdDirectionWeight = 15.0;

const double _v2ForecastUpsideLowerBps = -2000.0;
const double _v2ForecastUpsideUpperBps = 5000.0;
const double _v2ForecastValuationWeight = 50.0;
const double _v2ForecastRecLowHundredths = 150.0;
const double _v2ForecastRecHighHundredths = 300.0;
const double _v2ForecastRecWeight = 15.0;
const int _v2ForecastMinAnalystOpinions = 3;
const double _v2ForecastFullAnalystOpinions = 15.0;
const double _v2ForecastBreadthWeight = 20.0;
const double _v2ForecastUncertaintyBound = 0.6;
const double _v2ForecastUncertaintyWeight = 10.0;
const double _v2ForecastFreshnessWeight = 5.0;
const double _v2ForecastFreshnessHalfLifeSeconds = 14.0 * 86400.0;
const double _v2ForecastDcfReliability = 0.75;
const double _v2ForecastMinReliableEvidenceWeight = 25.0;
const double _v2FundamentalsFullWeight = 100.0;
const double _v2TechnicalsFullWeight = 100.0;
const double _v2ForecastFullWeight = _v2ForecastValuationWeight +
    _v2ForecastRecWeight +
    _v2ForecastBreadthWeight +
    _v2ForecastUncertaintyWeight +
    _v2ForecastFreshnessWeight;
const int _v2CompositeCoverageBonus = 5;
const int _v2CompositeBound = 110;

// AggressiveV3 extras
const double _v3FundFcfWeight = 22.0;
const double _v3FundOcfFallbackWeight = 10.0;
const double _v3FundRoeWeight = 16.0;
const double _v3FundGrowthWeight = 12.0;
const double _v3FundBalanceWeight = 16.0;
const double _v3FundValuationWeight = 24.0;
const double _v3FundEvEbitdaLow = 600.0;
const double _v3FundEvEbitdaHigh = 2000.0;
const double _v3FundPbLow = 100.0;
const double _v3FundPbHigh = 500.0;
const double _v3FundCashQualityWeight = 10.0;
const double _v3TechTrendPrice20Weight = 12.0;
const double _v3TechTrend2050Weight = 18.0;
const double _v3TechTrend50200Weight = 15.0;
const double _v3TechHistogramWeight = 12.0;
const double _v3TechMacdDirectionWeight = 8.0;
const double _v3TechRsiWeight = 25.0;
const double _v3TechVolumeWeight = 10.0;
const double _v3TechRsiSlopeBound = 2.0;
const double _v3TechVolumeRatioLow = 70.0;
const double _v3TechVolumeRatioHigh = 150.0;
const double _v3ForecastValuationWeight = 42.0;
const double _v3ForecastRecWeight = 12.0;
const double _v3ForecastSkewWeight = 12.0;
const double _v3ForecastBreadthWeight = 14.0;
const double _v3ForecastAnalystUncertaintyWeight = 8.0;
const double _v3ForecastDcfUncertaintyWeight = 8.0;
const double _v3ForecastDcfWidthLower = 0.2;
const double _v3ForecastDcfWidthUpper = 1.0;
const double _v3ForecastFreshnessWeight = 4.0;
const double _v3ForecastFullWeight = _v3ForecastValuationWeight +
    _v3ForecastRecWeight +
    _v3ForecastSkewWeight +
    _v3ForecastBreadthWeight +
    _v3ForecastAnalystUncertaintyWeight +
    _v3ForecastDcfUncertaintyWeight +
    _v3ForecastFreshnessWeight;
const double _v3BetaHaircutMax = 10.0;
const double _v3BetaLowMillis = 800.0;
const double _v3BetaHighMillis = 1600.0;

class OpportunityContext {
  const OpportunityContext({
    this.filter = const ViewFilter(),
    this.chartSummariesBySymbol = const {},
    this.analysesBySymbol = const {},
    this.scoringModel = OpportunityScoringModel.legacy,
  });

  final ViewFilter filter;
  final Map<String, Map<ChartRange, ChartRangeSummary>> chartSummariesBySymbol;
  final Map<String, DcfAnalysis> analysesBySymbol;
  final OpportunityScoringModel scoringModel;
}

class OpportunityScoreBreakdown {
  const OpportunityScoreBreakdown({
    required this.fundamentalsScore,
    required this.technicalScore,
    required this.forecastScore,
    required this.compositeScore,
    required this.coverageCount,
    this.fundamentalsSignals = const [],
    this.technicalSignals = const [],
    this.forecastSignals = const [],
  });

  final int? fundamentalsScore;
  final int? technicalScore;
  final int? forecastScore;
  final int compositeScore;
  final int coverageCount;
  final List<String> fundamentalsSignals;
  final List<String> technicalSignals;
  final List<String> forecastSignals;
}

class OpportunityEngine {
  static int avoidBelowScore(OpportunityScoringModel model) => switch (model) {
        OpportunityScoringModel.legacy ||
        OpportunityScoringModel.aggressive =>
          legacyAvoidBelowScore,
        OpportunityScoringModel.aggressiveV2 ||
        OpportunityScoringModel.aggressiveV3 =>
          continuousAvoidBelowScore,
      };

  static int actAtOrAboveScore(OpportunityScoringModel model) => switch (model) {
        OpportunityScoringModel.legacy ||
        OpportunityScoringModel.aggressive =>
          legacyActAtOrAboveScore,
        OpportunityScoringModel.aggressiveV2 ||
        OpportunityScoringModel.aggressiveV3 =>
          continuousActAtOrAboveScore,
      };

  static List<OpportunityRow> buildRows(
    ReportingEngine reportingEngine, {
    OpportunityContext context = const OpportunityContext(),
  }) {
    final limit = reportingEngine.symbolCount().clamp(1, 0x7fffffff);
    final candidates = reportingEngine
        .filteredRows(limit: limit, filter: context.filter)
        .where((c) => c.isQualified);

    final rows = <OpportunityRow>[];
    for (final candidate in candidates) {
      final detail = reportingEngine.detail(candidate.symbol);
      if (detail == null) continue;
      final score = scoreWithModel(
        detail: detail,
        summary: preferredChartSummary(
          context.chartSummariesBySymbol[detail.symbol],
        ),
        analysis: context.analysesBySymbol[detail.symbol],
        model: context.scoringModel,
      );
      rows.add(
        OpportunityRow(
          symbol: detail.symbol,
          marketPriceCents: detail.marketPriceCents,
          intrinsicValueCents: detail.intrinsicValueCents,
          gapBps: detail.gapBps,
          upsideBps: detail.upsideBps,
          confidence: detail.confidence,
          isWatched: detail.isWatched,
          fundamentalsScore: score.fundamentalsScore,
          technicalScore: score.technicalScore,
          forecastScore: score.forecastScore,
          compositeScore: score.compositeScore,
          coverageCount: score.coverageCount,
          fundamentalsSignals: score.fundamentalsSignals,
          technicalSignals: score.technicalSignals,
          forecastSignals: score.forecastSignals,
          companyName: detail.companyName,
        ),
      );
    }

    rows.sort((a, b) {
      final c = b.compositeScore.compareTo(a.compositeScore);
      if (c != 0) return c;
      final cov = b.coverageCount.compareTo(a.coverageCount);
      if (cov != 0) return cov;
      final conf =
          _confidenceRank(b.confidence).compareTo(_confidenceRank(a.confidence));
      if (conf != 0) return conf;
      final up = b.upsideBps.compareTo(a.upsideBps);
      if (up != 0) return up;
      return a.symbol.compareTo(b.symbol);
    });
    return rows;
  }

  static OpportunityScoreBreakdown scoreWithModel({
    required SymbolDetail detail,
    ChartRangeSummary? summary,
    DcfAnalysis? analysis,
    required OpportunityScoringModel model,
  }) {
    final (fundamentalsScore, fundamentalsSignals) = switch (model) {
      OpportunityScoringModel.legacy => scoreFundamentals(detail),
      OpportunityScoringModel.aggressive => scoreFundamentalsAggressive(detail),
      OpportunityScoringModel.aggressiveV2 =>
        aggressiveV2FundamentalsScore(detail),
      OpportunityScoringModel.aggressiveV3 =>
        aggressiveV3FundamentalsScore(detail),
    };
    final (technicalScore, technicalSignals) = switch (model) {
      OpportunityScoringModel.legacy => scoreTechnicals(summary),
      OpportunityScoringModel.aggressive => scoreTechnicalsAggressive(summary),
      OpportunityScoringModel.aggressiveV2 =>
        aggressiveV2TechnicalScore(summary),
      OpportunityScoringModel.aggressiveV3 =>
        aggressiveV3TechnicalScore(summary),
    };
    final (forecastScore, forecastSignals) = switch (model) {
      OpportunityScoringModel.legacy => scoreForecasts(detail, analysis),
      OpportunityScoringModel.aggressive =>
        scoreForecastsAggressive(detail, analysis),
      OpportunityScoringModel.aggressiveV2 =>
        aggressiveV2ForecastScore(detail, analysis),
      OpportunityScoringModel.aggressiveV3 =>
        aggressiveV3ForecastScore(detail, analysis),
    };

    final coverageCount =
        [fundamentalsScore, technicalScore, forecastScore].where((s) => s != null).length;
    var composite = _compositeScoreFor(
      model: model,
      fundamentals: fundamentalsScore,
      technical: technicalScore,
      forecast: forecastScore,
      coverageCount: coverageCount,
    );
    if (model == OpportunityScoringModel.aggressiveV3) {
      composite = _applyBetaHaircut(composite, detail);
    }

    return OpportunityScoreBreakdown(
      fundamentalsScore: fundamentalsScore,
      technicalScore: technicalScore,
      forecastScore: forecastScore,
      compositeScore: composite,
      coverageCount: coverageCount,
      fundamentalsSignals: fundamentalsSignals,
      technicalSignals: technicalSignals,
      forecastSignals: forecastSignals,
    );
  }

  static int _compositeScoreFor({
    required OpportunityScoringModel model,
    required int? fundamentals,
    required int? technical,
    required int? forecast,
    required int coverageCount,
  }) {
    switch (model) {
      case OpportunityScoringModel.legacy:
      case OpportunityScoringModel.aggressive:
        return (fundamentals ?? 0) + (technical ?? 0) + (forecast ?? 0);
      case OpportunityScoringModel.aggressiveV2:
      case OpportunityScoringModel.aggressiveV3:
        if (coverageCount == 0) return 0;
        final sum = (fundamentals ?? 0) + (technical ?? 0) + (forecast ?? 0);
        final mean = sum / coverageCount;
        final bonus = _v2CompositeCoverageBonus * (coverageCount - 1);
        return (mean + bonus).round().clamp(-_v2CompositeBound, _v2CompositeBound);
    }
  }

  static int _applyBetaHaircut(int composite, SymbolDetail detail) {
    final beta = detail.fundamentals?.betaMillis;
    if (beta == null) return composite;
    final ramp = smoothRamp(
      beta.toDouble(),
      _v3BetaLowMillis,
      _v3BetaHighMillis,
    );
    final haircut = ((ramp + 1) / 2) * _v3BetaHaircutMax;
    return (composite - haircut).round().clamp(-_v2CompositeBound, _v2CompositeBound);
  }

  static (int?, List<String>) scoreFundamentals(SymbolDetail detail) {
    final fundamentals = detail.fundamentals;
    if (fundamentals == null) return (null, const []);
    var score = 0;
    final signals = <String>[];
    if ((fundamentals.freeCashFlowDollars ?? 0) > 0) {
      score += 1;
      signals.add('FCF+');
    }
    if ((fundamentals.operatingCashFlowDollars ?? 0) > 0) {
      score += 1;
      signals.add('OCF+');
    }
    if ((fundamentals.returnOnEquityBps ?? -0x80000000) >= 1000) {
      score += 1;
      signals.add('ROE>10');
    }
    final balanceOk = (fundamentals.debtToEquityHundredths != null &&
            fundamentals.debtToEquityHundredths! <= 100) ||
        (fundamentals.totalCashDollars != null &&
            fundamentals.totalDebtDollars != null &&
            fundamentals.totalCashDollars! >= fundamentals.totalDebtDollars!);
    if (balanceOk) {
      score += 1;
      signals.add('Balance');
    }
    if ((fundamentals.earningsGrowthBps ?? 0) > 0) {
      score += 1;
      signals.add('Growth+');
    }
    return (score, signals);
  }

  static (int?, List<String>) scoreFundamentalsAggressive(SymbolDetail detail) {
    final base = scoreFundamentals(detail);
    if (base.$1 == null) return base;
    // Aggressive keeps discrete scale but weights quality harder via extra PE/ROE ramps.
    var score = base.$1!;
    final signals = [...base.$2];
    final pe = detail.fundamentals?.forwardPeHundredths;
    if (pe != null && pe > 0 && pe <= 1500) {
      score += 1;
      signals.add('CheapPE');
    }
    return (score, signals);
  }

  static (int?, List<String>) scoreTechnicals(ChartRangeSummary? summary) {
    if (summary == null) return (null, const []);
    final latestCloseCents = summary.latestCloseCents;
    if (latestCloseCents == null) return (0, const []);
    var score = 0;
    final signals = <String>[];
    if (summary.ema20Cents != null && latestCloseCents > summary.ema20Cents!) {
      score += 1;
      signals.add('>EMA20');
    }
    if (summary.ema50Cents != null && latestCloseCents > summary.ema50Cents!) {
      score += 1;
      signals.add('>EMA50');
    }
    if (summary.ema200Cents != null && latestCloseCents > summary.ema200Cents!) {
      score += 1;
      signals.add('>EMA200');
    }
    if (summary.ema20Cents != null &&
        summary.ema50Cents != null &&
        summary.ema20Cents! > summary.ema50Cents!) {
      score += 1;
      signals.add('EMA20>50');
    }
    if ((summary.macdCents != null &&
            summary.signalCents != null &&
            summary.macdCents! > summary.signalCents!) ||
        (summary.histogramCents != null && summary.histogramCents! > 0)) {
      score += 1;
      signals.add('MACD+');
    }
    return (score, signals);
  }

  static (int?, List<String>) scoreTechnicalsAggressive(
    ChartRangeSummary? summary,
  ) =>
      scoreTechnicals(summary);

  static (int?, List<String>) scoreForecasts(
    SymbolDetail detail,
    DcfAnalysis? analysis,
  ) {
    var available = false;
    var score = 0;
    final signals = <String>[];
    if (detail.externalStatus == ExternalSignalStatus.supportive) {
      available = true;
      score += 1;
      signals.add('Supportive');
    }
    if ((detail.analystOpinionCount ?? 0) >= 5) {
      available = true;
      score += 1;
      signals.add('5+Analysts');
    }
    if (detail.recommendationMeanHundredths != null &&
        detail.recommendationMeanHundredths! <= 200) {
      available = true;
      score += 1;
      signals.add('Rec<=2.0');
    }
    final weighted = detail.weightedExternalSignalFairValueCents;
    if (weighted != null) {
      available = true;
      if ((checkedUpsideBps(detail.marketPriceCents, weighted) ?? 0) >= 3000) {
        score += 1;
        signals.add('Weighted+');
      }
    }
    if (analysis != null) {
      available = true;
      switch (dcfSignal(analysis, detail.marketPriceCents)) {
        case DcfSignal.opportunity:
          score += 1;
          signals.add('DCF+');
        case DcfSignal.expensive:
          score -= 1;
          signals.add('DCF-');
        case DcfSignal.fair:
          break;
      }
    }
    return available ? (score, signals) : (null, const []);
  }

  static (int?, List<String>) scoreForecastsAggressive(
    SymbolDetail detail,
    DcfAnalysis? analysis,
  ) {
    var available = false;
    var score = 0;
    final signals = <String>[];
    switch (detail.externalStatus) {
      case ExternalSignalStatus.supportive:
        available = true;
        score += 2;
        signals.add('Supportive+2');
      case ExternalSignalStatus.divergent:
        available = true;
        score -= 2;
        signals.add('Divergent-2');
      case ExternalSignalStatus.stale:
      case ExternalSignalStatus.missing:
        break;
    }
    final analystCount = detail.analystOpinionCount ?? 0;
    if (analystCount >= 10) {
      available = true;
      score += 2;
      signals.add('Analysts10+');
    } else if (analystCount >= 5) {
      available = true;
      score += 1;
      signals.add('Analysts5+');
    }
    final rec = detail.recommendationMeanHundredths;
    if (rec != null) {
      available = true;
      if (rec <= 170) {
        score += 2;
        signals.add('Rec1.7+');
      } else if (rec <= 220) {
        score += 1;
        signals.add('Rec2.2+');
      } else if (rec >= 300) {
        score -= 2;
        signals.add('Rec3.0-');
      }
    }
    final weighted = detail.weightedExternalSignalFairValueCents;
    if (weighted != null) {
      available = true;
      final upside = checkedUpsideBps(detail.marketPriceCents, weighted) ?? 0;
      if (upside >= 5000) {
        score += 3;
        signals.add('Weighted50+');
      } else if (upside >= 3000) {
        score += 2;
        signals.add('Weighted30+');
      } else if (upside < 0) {
        score -= 2;
        signals.add('Weighted-');
      }
    }
    if (analysis != null) {
      available = true;
      final margin = _dcfMarginOfSafetyBps(analysis, detail.marketPriceCents) ?? 0;
      if (margin >= 4000) {
        score += 4;
        signals.add('DCF40+');
      } else if (margin >= 2000) {
        score += 2;
        signals.add('DCF20+');
      } else if (margin <= -1001) {
        score -= 3;
        signals.add('DCF-');
      }
    }
    return available ? (score, signals) : (null, const []);
  }

  static (int?, List<String>) aggressiveV2FundamentalsScore(SymbolDetail detail) {
    final fundamentals = detail.fundamentals;
    if (fundamentals == null) return (null, const []);
    final acc = _EvidenceAccumulator(_v2FundamentalsFullWeight);
    final fcfDollars = fundamentals.freeCashFlowDollars;
    final marketCapDollars = fundamentals.marketCapDollars;
    if (fcfDollars != null && marketCapDollars != null && marketCapDollars > 0) {
      final yieldFraction = fcfDollars / marketCapDollars;
      acc.add(
        _v2FundFcfWeight,
        smoothRamp(yieldFraction, _v2FundFcfYieldLower, _v2FundFcfYieldUpper),
        'FCFy',
      );
    } else if (fcfDollars != null) {
      acc.add(_v2FundFcfWeight, fcfDollars > 0 ? 1.0 : -1.0, 'FCF');
    } else {
      final ocf = fundamentals.operatingCashFlowDollars;
      if (ocf != null) {
        acc.add(_v2FundOcfFallbackWeight, ocf > 0 ? 1.0 : -1.0, 'OCF');
      }
    }
    final roe = fundamentals.returnOnEquityBps;
    if (roe != null) {
      acc.add(
        _v2FundRoeWeight,
        smoothRamp(roe.toDouble(), _v2FundRoeLowerBps, _v2FundRoeUpperBps),
        'ROE',
      );
    }
    final growth = fundamentals.earningsGrowthBps;
    if (growth != null) {
      acc.add(
        _v2FundGrowthWeight,
        smoothRamp(
          growth.toDouble(),
          _v2FundGrowthLowerBps,
          _v2FundGrowthUpperBps,
        ),
        'Growth',
      );
    }
    final de = fundamentals.debtToEquityHundredths;
    if (de != null) {
      acc.add(
        _v2FundBalanceWeight,
        -smoothRamp(de.toDouble(), _v2FundBalanceDeLow, _v2FundBalanceDeHigh),
        'D/E',
      );
    } else {
      final cash = fundamentals.totalCashDollars;
      final debt = fundamentals.totalDebtDollars;
      if (cash != null && debt != null) {
        acc.add(_v2FundBalanceWeight, cash >= debt ? 1.0 : -0.5, 'Bal');
      }
    }
    final pe = fundamentals.forwardPeHundredths;
    if (pe != null && pe > 0) {
      acc.add(
        _v2FundPeWeight,
        -smoothRamp(pe.toDouble(), _v2FundPeLow, _v2FundPeHigh),
        'FwdPE',
      );
    }
    return (acc.normalizedScore(), acc.signals);
  }

  static (int?, List<String>) aggressiveV2TechnicalScore(
    ChartRangeSummary? summary,
  ) {
    if (summary == null) return (null, const []);
    final latest = summary.latestCloseCents;
    if (latest == null) return (null, const []);
    final acc = _EvidenceAccumulator(_v2TechnicalsFullWeight);
    if (summary.ema20Cents != null && summary.ema20Cents! > 0) {
      final delta = (latest - summary.ema20Cents!) / summary.ema20Cents!;
      acc.add(
        _v2TechTrendPrice20Weight,
        smoothRamp(delta, -_v2TechTrendDeltaBound, _v2TechTrendDeltaBound),
        'Px/20',
      );
    }
    if (summary.ema20Cents != null &&
        summary.ema50Cents != null &&
        summary.ema50Cents! > 0) {
      final delta =
          (summary.ema20Cents! - summary.ema50Cents!) / summary.ema50Cents!;
      acc.add(
        _v2TechTrend2050Weight,
        smoothRamp(delta, -_v2TechTrendDeltaBound, _v2TechTrendDeltaBound),
        '20/50',
      );
    }
    if (summary.ema50Cents != null &&
        summary.ema200Cents != null &&
        summary.ema200Cents! > 0) {
      final delta =
          (summary.ema50Cents! - summary.ema200Cents!) / summary.ema200Cents!;
      acc.add(
        _v2TechTrend50200Weight,
        smoothRamp(delta, -_v2TechTrendDeltaBound, _v2TechTrendDeltaBound),
        '50/200',
      );
    }
    if (summary.histogramCents != null && latest > 0) {
      final ratio = summary.histogramCents! / latest;
      acc.add(
        _v2TechHistogramWeight,
        smoothRamp(ratio, -_v2TechHistogramBound, _v2TechHistogramBound),
        'Hist',
      );
    }
    if (summary.macdCents != null && summary.signalCents != null) {
      final direction = summary.macdCents! > summary.signalCents!
          ? 1.0
          : summary.macdCents! < summary.signalCents!
              ? -1.0
              : 0.0;
      acc.add(_v2TechMacdDirectionWeight, direction, 'MACD');
    }
    return (acc.normalizedScore(), acc.signals);
  }

  static (int?, List<String>) aggressiveV2ForecastScore(
    SymbolDetail detail,
    DcfAnalysis? analysis,
  ) {
    final acc = _EvidenceAccumulator(_v2ForecastFullWeight);
    final sufficiency = <String>[];
    var reliableEvidenceWeight = 0.0;
    var hasValuationAnchor = false;

    final targetFairValue = _preferredForecastFairValueCents(detail);
    final targetCount = _targetAnalystCount(detail);
    final recommendationCount = _recommendationAnalystCount(detail);
    final broadest = [targetCount, recommendationCount]
        .whereType<int>()
        .fold<int?>(null, (a, b) => a == null ? b : math.max(a, b));
    final externalFreshness = _freshnessMultiplier(detail);
    final statusReliability = _externalStatusReliability(detail.externalStatus);

    final valuationInputs = <({double ramp, double reliability})>[];
    if (targetFairValue != null) {
      final targetUpside = checkedUpsideBps(detail.marketPriceCents, targetFairValue);
      final reliability = _analystCoverageReliability(targetCount) *
          externalFreshness *
          statusReliability;
      if (targetUpside != null) {
        if (!_hasSufficientAnalystCoverage(targetCount)) {
          sufficiency.add(
            targetCount == null
                ? 'Cov?'
                : 'Cov<$_v2ForecastMinAnalystOpinions',
          );
        } else if (reliability > 0) {
          valuationInputs.add((
            ramp: smoothRamp(
              targetUpside.toDouble(),
              _v2ForecastUpsideLowerBps,
              _v2ForecastUpsideUpperBps,
            ),
            reliability: reliability,
          ));
        }
      }
    }
    if (analysis != null) {
      final margin = _dcfMarginOfSafetyBps(analysis, detail.marketPriceCents);
      if (margin != null) {
        valuationInputs.add((
          ramp: smoothRamp(
            margin.toDouble(),
            _v2ForecastUpsideLowerBps,
            _v2ForecastUpsideUpperBps,
          ),
          reliability: _v2ForecastDcfReliability,
        ));
      }
    }
    if (valuationInputs.isNotEmpty) {
      final reliabilitySum =
          valuationInputs.fold<double>(0, (s, e) => s + e.reliability);
      if (reliabilitySum > 0) {
        final blended = valuationInputs.fold<double>(
              0,
              (s, e) => s + e.ramp * e.reliability,
            ) /
            reliabilitySum;
        final weight =
            _v2ForecastValuationWeight * math.min(1.0, reliabilitySum);
        acc.add(weight, blended, 'Val');
        reliableEvidenceWeight += weight;
        hasValuationAnchor = true;
      }
    }

    final rec = detail.recommendationMeanHundredths;
    if (rec != null) {
      final recReliability = _analystCoverageReliability(recommendationCount) *
          externalFreshness *
          statusReliability;
      if (!_hasSufficientAnalystCoverage(recommendationCount)) {
        sufficiency.add(
          recommendationCount == null
              ? 'RecCov?'
              : 'RecCov<$_v2ForecastMinAnalystOpinions',
        );
      } else if (recReliability > 0) {
        final weight = _v2ForecastRecWeight * recReliability;
        acc.add(
          weight,
          -smoothRamp(
            rec.toDouble(),
            _v2ForecastRecLowHundredths,
            _v2ForecastRecHighHundredths,
          ),
          'Rec',
        );
        reliableEvidenceWeight += weight;
      }
    }

    if (broadest != null) {
      acc.add(_v2ForecastBreadthWeight, _analystBreadthRamp(broadest), 'Cov');
      reliableEvidenceWeight +=
          _v2ForecastBreadthWeight * _analystCoverageReliability(broadest);
    }

    final targetRelNoFresh =
        _analystCoverageReliability(targetCount) * statusReliability;
    final low = detail.externalSignalLowFairValueCents;
    final high = detail.externalSignalHighFairValueCents;
    final centre = targetFairValue;
    if (low != null &&
        high != null &&
        centre != null &&
        centre > 0 &&
        high > low &&
        targetRelNoFresh > 0) {
      final spread = (high - low) / centre;
      final weight = _v2ForecastUncertaintyWeight * targetRelNoFresh;
      acc.add(
        weight,
        -smoothRamp(spread, 0.0, _v2ForecastUncertaintyBound),
        'Unc',
      );
      reliableEvidenceWeight += weight * externalFreshness;
    }

    if (targetFairValue != null && _hasSufficientAnalystCoverage(targetCount)) {
      final weight =
          _v2ForecastFreshnessWeight * _analystCoverageReliability(targetCount);
      acc.add(weight, _freshnessRamp(externalFreshness), 'Fresh');
      reliableEvidenceWeight += weight * externalFreshness;
    }

    final signals = {...acc.signals, ...sufficiency}.toList();
    if (!hasValuationAnchor ||
        reliableEvidenceWeight < _v2ForecastMinReliableEvidenceWeight) {
      return (null, signals);
    }
    final raw = acc.normalizedScore();
    if (raw == null) return (null, signals);
    return (raw.clamp(-100, 100), signals);
  }

  static (int?, List<String>) aggressiveV3FundamentalsScore(SymbolDetail detail) {
    final fundamentals = detail.fundamentals;
    if (fundamentals == null) return (null, const []);
    final acc = _EvidenceAccumulator(100.0);
    final fcfDollars = fundamentals.freeCashFlowDollars;
    final marketCapDollars = fundamentals.marketCapDollars;
    if (fcfDollars != null && marketCapDollars != null && marketCapDollars > 0) {
      final yieldFraction = fcfDollars / marketCapDollars;
      acc.add(
        _v3FundFcfWeight,
        smoothRamp(yieldFraction, _v2FundFcfYieldLower, _v2FundFcfYieldUpper),
        'FCFy',
      );
    } else if (fcfDollars != null) {
      acc.add(_v3FundFcfWeight, fcfDollars > 0 ? 1.0 : -1.0, 'FCF');
    } else {
      final ocf = fundamentals.operatingCashFlowDollars;
      if (ocf != null) {
        acc.add(_v3FundOcfFallbackWeight, ocf > 0 ? 1.0 : -1.0, 'OCF');
      }
    }
    if (fundamentals.returnOnEquityBps != null) {
      acc.add(
        _v3FundRoeWeight,
        smoothRamp(
          fundamentals.returnOnEquityBps!.toDouble(),
          _v2FundRoeLowerBps,
          _v2FundRoeUpperBps,
        ),
        'ROE',
      );
    }
    if (fundamentals.earningsGrowthBps != null) {
      acc.add(
        _v3FundGrowthWeight,
        smoothRamp(
          fundamentals.earningsGrowthBps!.toDouble(),
          _v2FundGrowthLowerBps,
          _v2FundGrowthUpperBps,
        ),
        'Growth',
      );
    }
    final de = fundamentals.debtToEquityHundredths;
    if (de != null) {
      acc.add(
        _v3FundBalanceWeight,
        -smoothRamp(de.toDouble(), _v2FundBalanceDeLow, _v2FundBalanceDeHigh),
        'D/E',
      );
    } else if (fundamentals.totalCashDollars != null &&
        fundamentals.totalDebtDollars != null) {
      acc.add(
        _v3FundBalanceWeight,
        fundamentals.totalCashDollars! >= fundamentals.totalDebtDollars!
            ? 1.0
            : -0.5,
        'Bal',
      );
    }
    final valuationRamps = <double>[];
    final pe = fundamentals.forwardPeHundredths;
    if (pe != null && pe > 0) {
      valuationRamps
          .add(-smoothRamp(pe.toDouble(), _v2FundPeLow, _v2FundPeHigh));
    }
    final ev = fundamentals.enterpriseToEbitdaHundredths;
    if (ev != null && ev > 0) {
      valuationRamps
          .add(-smoothRamp(ev.toDouble(), _v3FundEvEbitdaLow, _v3FundEvEbitdaHigh));
    }
    final pb = fundamentals.priceToBookHundredths;
    if (pb != null && pb > 0) {
      valuationRamps.add(-smoothRamp(pb.toDouble(), _v3FundPbLow, _v3FundPbHigh));
    }
    if (valuationRamps.isNotEmpty) {
      final blended =
          valuationRamps.reduce((a, b) => a + b) / valuationRamps.length;
      final coverageFraction = valuationRamps.length / 3.0;
      acc.add(_v3FundValuationWeight * coverageFraction, blended, 'Mult');
    }
    final ocf = fundamentals.operatingCashFlowDollars;
    if (fcfDollars != null && ocf != null && ocf > 0) {
      acc.add(
        _v3FundCashQualityWeight,
        smoothRamp(fcfDollars / ocf, 0.0, 1.0),
        'Conv',
      );
    }
    return (acc.normalizedScore(), acc.signals);
  }

  static (int?, List<String>) aggressiveV3TechnicalScore(
    ChartRangeSummary? summary,
  ) {
    if (summary == null) return (null, const []);
    final latest = summary.latestCloseCents;
    if (latest == null) return (null, const []);
    final acc = _EvidenceAccumulator(100.0);
    if (summary.ema20Cents != null && summary.ema20Cents! > 0) {
      final delta = (latest - summary.ema20Cents!) / summary.ema20Cents!;
      acc.add(
        _v3TechTrendPrice20Weight,
        smoothRamp(delta, -_v2TechTrendDeltaBound, _v2TechTrendDeltaBound),
        'Px/20',
      );
    }
    if (summary.ema20Cents != null &&
        summary.ema50Cents != null &&
        summary.ema50Cents! > 0) {
      final delta =
          (summary.ema20Cents! - summary.ema50Cents!) / summary.ema50Cents!;
      acc.add(
        _v3TechTrend2050Weight,
        smoothRamp(delta, -_v2TechTrendDeltaBound, _v2TechTrendDeltaBound),
        '20/50',
      );
    }
    if (summary.ema50Cents != null &&
        summary.ema200Cents != null &&
        summary.ema200Cents! > 0) {
      final delta =
          (summary.ema50Cents! - summary.ema200Cents!) / summary.ema200Cents!;
      acc.add(
        _v3TechTrend50200Weight,
        smoothRamp(delta, -_v2TechTrendDeltaBound, _v2TechTrendDeltaBound),
        '50/200',
      );
    }
    if (summary.histogramCents != null && latest > 0) {
      acc.add(
        _v3TechHistogramWeight,
        smoothRamp(
          summary.histogramCents! / latest,
          -_v2TechHistogramBound,
          _v2TechHistogramBound,
        ),
        'Hist',
      );
    }
    if (summary.macdCents != null && summary.signalCents != null) {
      final direction = summary.macdCents! > summary.signalCents!
          ? 1.0
          : summary.macdCents! < summary.signalCents!
              ? -1.0
              : 0.0;
      acc.add(_v3TechMacdDirectionWeight, direction, 'MACD');
    }
    final rsi = summary.latestWilderRsi;
    if (rsi != null) {
      final levelRamp = v3RsiLevelRamp(rsi);
      final slopeRamp = summary.latestRsiSlope == null
          ? 0.0
          : smoothRamp(
              summary.latestRsiSlope!,
              -_v3TechRsiSlopeBound,
              _v3TechRsiSlopeBound,
            );
      acc.add(
        _v3TechRsiWeight,
        (0.65 * levelRamp + 0.35 * slopeRamp).clamp(-1.0, 1.0),
        'RSI',
      );
    }
    final vol = summary.volumeRatioHundredths;
    if (vol != null) {
      acc.add(
        _v3TechVolumeWeight,
        smoothRamp(
          vol.toDouble(),
          _v3TechVolumeRatioLow,
          _v3TechVolumeRatioHigh,
        ),
        'Vol',
      );
    }
    return (acc.normalizedScore(), acc.signals);
  }

  static double v3RsiLevelRamp(double rsi) {
    if (rsi <= 30.0) return -1.0;
    if (rsi <= 55.0) return smoothRamp(rsi, 30.0, 55.0);
    if (rsi <= 80.0) {
      final t = (rsi - 55.0) / (80.0 - 55.0);
      return (1.0 - (t * 1.5)).clamp(-1.0, 1.0);
    }
    return -0.5;
  }

  static (int?, List<String>) aggressiveV3ForecastScore(
    SymbolDetail detail,
    DcfAnalysis? analysis,
  ) {
    // Reuse V2 valuation gates and layer V3 extras.
    final base = aggressiveV2ForecastScore(detail, analysis);
    final acc = _EvidenceAccumulator(_v3ForecastFullWeight);
    // Re-run with V3 weights by blending base score signals with skew + DCF width.
    final sufficiency = <String>[];
    var reliableEvidenceWeight = 0.0;
    var hasValuationAnchor = false;

    final targetFairValue = _preferredForecastFairValueCents(detail);
    final targetCount = _targetAnalystCount(detail);
    final recommendationCount = _recommendationAnalystCount(detail);
    final externalFreshness = _freshnessMultiplier(detail);
    final statusReliability = _externalStatusReliability(detail.externalStatus);

    final valuationInputs = <({double ramp, double reliability})>[];
    if (targetFairValue != null) {
      final targetUpside =
          checkedUpsideBps(detail.marketPriceCents, targetFairValue);
      final reliability = _analystCoverageReliability(targetCount) *
          externalFreshness *
          statusReliability;
      if (targetUpside != null &&
          _hasSufficientAnalystCoverage(targetCount) &&
          reliability > 0) {
        valuationInputs.add((
          ramp: smoothRamp(
            targetUpside.toDouble(),
            _v2ForecastUpsideLowerBps,
            _v2ForecastUpsideUpperBps,
          ),
          reliability: reliability,
        ));
      } else if (targetUpside != null &&
          !_hasSufficientAnalystCoverage(targetCount)) {
        sufficiency.add(
          targetCount == null ? 'Cov?' : 'Cov<$_v2ForecastMinAnalystOpinions',
        );
      }
    }
    if (analysis != null) {
      final margin = _dcfMarginOfSafetyBps(analysis, detail.marketPriceCents);
      if (margin != null) {
        valuationInputs.add((
          ramp: smoothRamp(
            margin.toDouble(),
            _v2ForecastUpsideLowerBps,
            _v2ForecastUpsideUpperBps,
          ),
          reliability: _v2ForecastDcfReliability,
        ));
      }
    }
    if (valuationInputs.isNotEmpty) {
      final reliabilitySum =
          valuationInputs.fold<double>(0, (s, e) => s + e.reliability);
      if (reliabilitySum > 0) {
        final blended = valuationInputs.fold<double>(
              0,
              (s, e) => s + e.ramp * e.reliability,
            ) /
            reliabilitySum;
        final weight =
            _v3ForecastValuationWeight * math.min(1.0, reliabilitySum);
        acc.add(weight, blended, 'Val');
        reliableEvidenceWeight += weight;
        hasValuationAnchor = true;
      }
    }

    final rec = detail.recommendationMeanHundredths;
    if (rec != null &&
        _hasSufficientAnalystCoverage(recommendationCount)) {
      final recReliability = _analystCoverageReliability(recommendationCount) *
          externalFreshness *
          statusReliability;
      if (recReliability > 0) {
        final weight = _v3ForecastRecWeight * recReliability;
        acc.add(
          weight,
          -smoothRamp(
            rec.toDouble(),
            _v2ForecastRecLowHundredths,
            _v2ForecastRecHighHundredths,
          ),
          'Rec',
        );
        reliableEvidenceWeight += weight;
      }
    }

    final skew = _v3RecommendationSkew(detail);
    if (skew != null &&
        _hasSufficientAnalystCoverage(recommendationCount)) {
      final skewReliability = _analystCoverageReliability(recommendationCount) *
          externalFreshness *
          statusReliability;
      if (skewReliability > 0) {
        final weight = _v3ForecastSkewWeight * skewReliability;
        acc.add(weight, skew, 'Skew');
        reliableEvidenceWeight += weight;
      }
    }

    final broadest = [
      if (targetCount != null) targetCount,
      if (recommendationCount != null) recommendationCount,
    ];
    if (broadest.isNotEmpty) {
      final count = broadest.reduce(math.max);
      acc.add(_v3ForecastBreadthWeight, _analystBreadthRamp(count), 'Cov');
      reliableEvidenceWeight +=
          _v3ForecastBreadthWeight * _analystCoverageReliability(count);
    }

    final targetRelNoFresh =
        _analystCoverageReliability(targetCount) * statusReliability;
    final low = detail.externalSignalLowFairValueCents;
    final high = detail.externalSignalHighFairValueCents;
    if (low != null &&
        high != null &&
        targetFairValue != null &&
        targetFairValue > 0 &&
        high > low &&
        targetRelNoFresh > 0) {
      final spread = (high - low) / targetFairValue;
      final weight = _v3ForecastAnalystUncertaintyWeight * targetRelNoFresh;
      acc.add(
        weight,
        -smoothRamp(spread, 0.0, _v2ForecastUncertaintyBound),
        'Unc',
      );
      reliableEvidenceWeight += weight * externalFreshness;
    }

    if (analysis != null) {
      final baseIv = analysis.baseIntrinsicValueCents;
      final bear = analysis.bearIntrinsicValueCents;
      final bull = analysis.bullIntrinsicValueCents;
      if (baseIv > 0 && bull >= baseIv && baseIv >= bear && bull > bear) {
        final width = (bull - bear) / baseIv;
        acc.add(
          _v3ForecastDcfUncertaintyWeight,
          -smoothRamp(width, _v3ForecastDcfWidthLower, _v3ForecastDcfWidthUpper),
          'DcfUnc',
        );
        reliableEvidenceWeight += _v3ForecastDcfUncertaintyWeight;
      }
    }

    if (targetFairValue != null && _hasSufficientAnalystCoverage(targetCount)) {
      final weight =
          _v3ForecastFreshnessWeight * _analystCoverageReliability(targetCount);
      acc.add(weight, _freshnessRamp(externalFreshness), 'Fresh');
      reliableEvidenceWeight += weight * externalFreshness;
    }

    final signals = {...acc.signals, ...sufficiency, ...base.$2}.toList();
    if (!hasValuationAnchor ||
        reliableEvidenceWeight < _v2ForecastMinReliableEvidenceWeight) {
      return (null, signals);
    }
    final raw = acc.normalizedScore();
    if (raw == null) return (null, signals);
    return (raw.clamp(-100, 100), signals);
  }

  static double? _v3RecommendationSkew(SymbolDetail detail) {
    final strongBuy = detail.strongBuyCount ?? 0;
    final buy = detail.buyCount ?? 0;
    final hold = detail.holdCount ?? 0;
    final sell = detail.sellCount ?? 0;
    final strongSell = detail.strongSellCount ?? 0;
    final total = strongBuy + buy + hold + sell + strongSell;
    if (total <= 0) return null;
    final bullish = (strongBuy + buy).toDouble();
    final bearish = (sell + strongSell).toDouble();
    return ((bullish - bearish) / total).clamp(-1.0, 1.0);
  }

  static int? _preferredForecastFairValueCents(SymbolDetail detail) =>
      detail.weightedExternalSignalFairValueCents ??
      detail.externalSignalFairValueCents;

  static int? _targetAnalystCount(SymbolDetail detail) {
    if (detail.weightedExternalSignalFairValueCents != null) {
      return detail.weightedAnalystCount ?? detail.analystOpinionCount;
    }
    if (detail.externalSignalFairValueCents != null) {
      return detail.analystOpinionCount;
    }
    return null;
  }

  static int? _recommendationAnalystCount(SymbolDetail detail) {
    final trend = [
      detail.strongBuyCount,
      detail.buyCount,
      detail.holdCount,
      detail.sellCount,
      detail.strongSellCount,
    ].whereType<int>().fold<int>(0, (a, b) => a + b);
    final trendOrNull = trend > 0 ? trend : null;
    final counts = [
      if (detail.analystOpinionCount != null) detail.analystOpinionCount!,
      if (trendOrNull != null) trendOrNull,
    ];
    if (counts.isEmpty) return null;
    return counts.reduce(math.max);
  }

  static bool _hasSufficientAnalystCoverage(int? count) =>
      count != null && count >= _v2ForecastMinAnalystOpinions;

  static double _analystCoverageReliability(int? count) {
    if (!_hasSufficientAnalystCoverage(count)) return 0.0;
    final progress = ((count! - _v2ForecastMinAnalystOpinions) /
            (_v2ForecastFullAnalystOpinions - _v2ForecastMinAnalystOpinions))
        .clamp(0.0, 1.0);
    return 0.35 + (0.65 * progress);
  }

  static double _analystBreadthRamp(int count) {
    if (count < _v2ForecastMinAnalystOpinions) return -1.0;
    final progress = ((count - _v2ForecastMinAnalystOpinions) /
            (_v2ForecastFullAnalystOpinions - _v2ForecastMinAnalystOpinions))
        .clamp(0.0, 1.0);
    return (-0.5 + (1.5 * progress)).clamp(-1.0, 1.0);
  }

  static double _externalStatusReliability(ExternalSignalStatus status) =>
      switch (status) {
        ExternalSignalStatus.supportive ||
        ExternalSignalStatus.divergent =>
          1.0,
        ExternalSignalStatus.stale => 0.25,
        ExternalSignalStatus.missing => 0.0,
      };

  static double _freshnessRamp(double multiplier) =>
      (2.0 * multiplier - 1.0).clamp(-1.0, 1.0);

  static double _freshnessMultiplier(SymbolDetail detail) {
    final age = detail.externalSignalAgeSeconds;
    if (age == null || age <= 0) return 1.0;
    return math.exp(-age / _v2ForecastFreshnessHalfLifeSeconds);
  }

  static double smoothRamp(double observed, double lower, double upper) {
    if (upper <= lower) {
      throw ArgumentError('smoothRamp requires upper > lower');
    }
    if (observed <= lower) return -1.0;
    if (observed >= upper) return 1.0;
    return 2.0 * (observed - lower) / (upper - lower) - 1.0;
  }

  static int? _dcfMarginOfSafetyBps(DcfAnalysis analysis, int marketPriceCents) {
    if (analysis.baseIntrinsicValueCents <= 0 || marketPriceCents <= 0) {
      return null;
    }
    final scaled = ((BigInt.from(analysis.baseIntrinsicValueCents) -
                BigInt.from(marketPriceCents)) *
            BigInt.from(10000)) ~/
        BigInt.from(analysis.baseIntrinsicValueCents);
    return scaled.toInt().clamp(-0x80000000, 0x7fffffff);
  }

  static ChartRangeSummary? preferredChartSummary(
    Map<ChartRange, ChartRangeSummary>? byRange,
  ) {
    if (byRange == null || byRange.isEmpty) return null;
    return byRange[ChartRange.year] ?? byRange.values.first;
  }

  static DcfSignal dcfSignal(DcfAnalysis analysis, int marketPriceCents) {
    final mos =
        checkedGapBps(marketPriceCents, analysis.baseIntrinsicValueCents) ?? 0;
    if (mos >= dcfOpportunityThresholdBps) return DcfSignal.opportunity;
    if (mos <= dcfExpensiveThresholdBps) return DcfSignal.expensive;
    return DcfSignal.fair;
  }

  static int _confidenceRank(ConfidenceBand confidence) => switch (confidence) {
        ConfidenceBand.low => 0,
        ConfidenceBand.provisional => 1,
        ConfidenceBand.high => 2,
      };
}

class _EvidenceAccumulator {
  _EvidenceAccumulator(this.normalizationWeight)
      : assert(normalizationWeight > 0);

  final double normalizationWeight;
  double weightedSum = 0;
  double evidenceWeight = 0;
  final List<String> signals = [];

  void add(double weight, double ramp, String? label) {
    if (weight <= 0) return;
    final clamped = ramp.clamp(-1.0, 1.0);
    weightedSum += weight * clamped;
    evidenceWeight += weight;
    if (label != null) signals.add('$label${_suffix(clamped)}');
  }

  int? normalizedScore() {
    if (evidenceWeight == 0) return null;
    final normalized = (weightedSum / normalizationWeight) * 100.0;
    return normalized.clamp(-100.0, 100.0).round();
  }

  String _suffix(double r) {
    if (r >= 0.5) return '++';
    if (r > 0.0) return '+';
    if (r >= -0.5) return '-';
    return '--';
  }
}
