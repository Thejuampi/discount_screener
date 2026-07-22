import 'models.dart';

/// Market-cap weighted index scenario forecasts (Android `IndexEstimatesEngine`).
class IndexEstimatesEngine {
  static IndexEstimatesReport compute({
    required List<SymbolDetail> symbols,
    required Map<String, DcfAnalysis> dcfBySymbol,
    required String profileName,
    required int nowEpochSeconds,
  }) {
    var totalMarketCapDollars = 0;
    var weightedCurrentNumerator = 0.0;
    var eligibleSymbols = 0;
    for (final symbol in symbols) {
      final cap = symbol.fundamentals?.marketCapDollars;
      if (cap == null || cap <= 0) continue;
      totalMarketCapDollars += cap;
      weightedCurrentNumerator += symbol.marketPriceCents.toDouble() * cap.toDouble();
      eligibleSymbols++;
    }
    final currentWeightedPriceCents = totalMarketCapDollars > 0
        ? (weightedCurrentNumerator / totalMarketCapDollars).round()
        : 0;

    final scenarios = EstimateScenario.values
        .map(
          (scenario) => _computeScenario(
            scenario,
            symbols,
            dcfBySymbol,
            totalMarketCapDollars,
            currentWeightedPriceCents,
          ),
        )
        .toList();

    return IndexEstimatesReport(
      profileName: profileName,
      currentWeightedPriceCents: currentWeightedPriceCents,
      totalSymbols: eligibleSymbols,
      scenarios: scenarios,
      computedAtEpochSeconds: nowEpochSeconds,
      dcfCoverage: _computeDcfCoverage(symbols, dcfBySymbol),
    );
  }

  static DcfCoverageSummary _computeDcfCoverage(
    List<SymbolDetail> symbols,
    Map<String, DcfAnalysis> dcfBySymbol,
  ) {
    final eligible = symbols
        .where((s) => (s.fundamentals?.marketCapDollars ?? 0) > 0)
        .toList();
    final analyses =
        eligible.map((s) => (s.symbol, dcfBySymbol[s.symbol])).toList();
    final notEligible = analyses
        .where((a) => a.$2?.resolverState == ResolverState.notEligible)
        .length;
    final denominator = eligible.length - notEligible;
    final coveredAnalyses =
        analyses.map((a) => a.$2).whereType<DcfAnalysis>().where(_isLiveCompleteDcf).toList();
    final numerator = coveredAnalyses.length;
    final coverageBps =
        (denominator == 0 || numerator == 0) ? 0 : numerator * 10000 ~/ denominator;
    return DcfCoverageSummary(
      totalEligibleSymbols: denominator,
      coveredSymbols: numerator,
      coverageBps: coverageBps,
      status: _dcfCoverageStatus(denominator, numerator, coverageBps),
      sourceDistribution: DcfSourceDistribution(
        yahooCount: coveredAnalyses
            .where((a) => a.source == DcfSource.yahooFinance)
            .length,
        secCount:
            coveredAnalyses.where((a) => a.source == DcfSource.secEdgar).length,
        restoredCount: analyses
            .where((a) => a.$2?.resolverState == ResolverState.restoredOnly)
            .length,
        uncertainCount: analyses
            .where((a) => a.$2?.resolverState == ResolverState.providerUncertain)
            .length,
        notEligibleCount: notEligible,
        unknownCount: coveredAnalyses
            .where((a) => a.source == null || a.source == DcfSource.unknown)
            .length,
        unavailableCount: analyses
            .where(
              (a) =>
                  a.$2 == null || a.$2!.resolverState == ResolverState.unavailable,
            )
            .length,
      ),
    );
  }

  static bool _isLiveCompleteDcf(DcfAnalysis analysis) =>
      analysis.resolverState == ResolverState.selected &&
      analysis.bearIntrinsicValueCents > 0 &&
      analysis.baseIntrinsicValueCents > 0 &&
      analysis.bullIntrinsicValueCents > 0;

  static DcfCoverageStatus _dcfCoverageStatus(
    int denominator,
    int numerator,
    int coverageBps,
  ) {
    if (denominator == 0 || numerator == 0) return DcfCoverageStatus.unavailable;
    if (coverageBps < 2500) return DcfCoverageStatus.lowConfidence;
    if (coverageBps < 5000) return DcfCoverageStatus.partial;
    if (coverageBps < 9500) return DcfCoverageStatus.provisional;
    return DcfCoverageStatus.ready;
  }

  static ScenarioEstimate _computeScenario(
    EstimateScenario scenario,
    List<SymbolDetail> symbols,
    Map<String, DcfAnalysis> dcfBySymbol,
    int totalMarketCapDollars,
    int currentWeightedPriceCents,
  ) {
    var numerator = 0.0;
    var denominatorCap = 0;
    var coverage = 0;
    for (final symbol in symbols) {
      final cap = symbol.fundamentals?.marketCapDollars;
      if (cap == null || cap <= 0) continue;
      final fairValue = _scenarioFairValue(scenario, symbol, dcfBySymbol);
      if (fairValue == null) continue;
      numerator += fairValue.toDouble() * cap.toDouble();
      denominatorCap += cap;
      coverage++;
    }
    final weightedPrice =
        denominatorCap > 0 ? (numerator / denominatorCap).round() : 0;
    final impliedUpside = (currentWeightedPriceCents <= 0 || coverage == 0)
        ? 0
        : ((weightedPrice / currentWeightedPriceCents - 1.0) * 10000).round();
    return ScenarioEstimate(
      scenario: scenario,
      weightedPriceCents: weightedPrice,
      coverageCount: coverage,
      impliedUpsideBps: impliedUpside,
    );
  }

  static int? _scenarioFairValue(
    EstimateScenario scenario,
    SymbolDetail symbol,
    Map<String, DcfAnalysis> dcfBySymbol,
  ) {
    final dcf = dcfBySymbol[symbol.symbol];
    switch (scenario) {
      case EstimateScenario.bearDcf:
        return dcf != null && _isLiveCompleteDcf(dcf)
            ? dcf.bearIntrinsicValueCents
            : null;
      case EstimateScenario.baseDcf:
        return dcf != null && _isLiveCompleteDcf(dcf)
            ? dcf.baseIntrinsicValueCents
            : null;
      case EstimateScenario.bullDcf:
        return dcf != null && _isLiveCompleteDcf(dcf)
            ? dcf.bullIntrinsicValueCents
            : null;
      case EstimateScenario.analystLow:
        return symbol.externalSignalLowFairValueCents;
      case EstimateScenario.analystHigh:
        return symbol.externalSignalHighFairValueCents;
    }
  }
}
