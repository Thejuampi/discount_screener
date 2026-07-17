import 'dart:convert';
import 'dart:io';

import 'package:ds_core/ds_core.dart';
import 'package:test/test.dart';

void main() {
  test('chart_range_fixture_matches_core_surface_contract', () {
    final fixture = _loadJson('chart-ranges.json');
    final expected = (fixture['ranges'] as List)
        .map((r) {
          final m = r as Map;
          return (m['id'] as String, m['label'] as String);
        })
        .toList();
    final actual = ChartRange.values
        .map((r) => (chartRangeWireName(r), chartRangeLabel(r)))
        .toList();
    expect(actual, expected);
  });

  test('dcf_source_selection_fixture_matches_core_policy_contract', () {
    final fixture = _loadJson('dcf-source-selection.json');
    final cases = fixture['cases'] as List;
    for (final raw in cases) {
      final c = raw as Map<String, dynamic>;
      final selection = DcfSourceSelectionPolicy.select(
        yahoo: _candidate(DcfSource.yahooFinance, c['yahoo'] as String),
        sec: _candidate(DcfSource.secEdgar, c['sec'] as String),
      );
      expect(
        resolverStateWireName(selection.resolverState),
        c['expectedResolverState'],
        reason: c['name'],
      );
      final expectedSource = c['expectedSelectedSource'];
      if (expectedSource == null) {
        expect(selection.selectedSource, isNull, reason: c['name'] as String);
      } else {
        expect(
          dcfSourceWireName(selection.selectedSource!),
          expectedSource,
          reason: c['name'] as String,
        );
      }
      final codes = selection.reasons.map((r) => reasonCodeWireName(r.code)).toList();
      expect(codes, c['expectedReasonCodes'], reason: c['name'] as String);
    }
  });

  test('portfolio_ranking_fixture_matches_core_behavior', () {
    final fixture = _loadJson('portfolio-ranking.json');
    final engine = ReportingEngine();

    for (final s in fixture['snapshots'] as List) {
      final m = s as Map<String, dynamic>;
      engine.ingestSnapshot(
        MarketSnapshot(
          symbol: m['symbol'] as String,
          profitable: m['profitable'] as bool,
          marketPriceCents: m['marketPriceCents'] as int,
          intrinsicValueCents: m['intrinsicValueCents'] as int,
        ),
      );
    }
    for (final s in fixture['externalSignals'] as List) {
      final m = s as Map<String, dynamic>;
      engine.ingestExternal(
        ExternalValuationSignal(
          symbol: m['symbol'] as String,
          fairValueCents: m['fairValueCents'] as int,
          ageSeconds: m['ageSeconds'] as int,
          lowFairValueCents: m['lowFairValueCents'] as int?,
          highFairValueCents: m['highFairValueCents'] as int?,
          analystOpinionCount: m['analystOpinionCount'] as int?,
          recommendationMeanHundredths: m['recommendationMeanHundredths'] as int?,
          weightedFairValueCents: m['weightedFairValueCents'] as int?,
          weightedAnalystCount: m['weightedAnalystCount'] as int?,
        ),
      );
    }
    for (final s in fixture['fundamentals'] as List) {
      final m = s as Map<String, dynamic>;
      engine.ingestFundamentals(
        FundamentalSnapshot(
          symbol: m['symbol'] as String,
          freeCashFlowDollars: m['freeCashFlowDollars'] as int?,
          operatingCashFlowDollars: m['operatingCashFlowDollars'] as int?,
          returnOnEquityBps: m['returnOnEquityBps'] as int?,
          debtToEquityHundredths: m['debtToEquityHundredths'] as int?,
          totalCashDollars: m['totalCashDollars'] as int?,
          totalDebtDollars: m['totalDebtDollars'] as int?,
          earningsGrowthBps: m['earningsGrowthBps'] as int?,
        ),
      );
    }
    for (final symbol in fixture['watchlist'] as List) {
      engine.toggleWatchlist(symbol as String);
    }

    List<String> highOnly(List<CandidateRow> rows) => rows
        .where((r) => r.confidence == ConfidenceBand.high)
        .map((r) => r.symbol)
        .toList();

    expect(
      highOnly(engine.filteredRows(limit: 10)),
      List<String>.from(fixture['expectedCandidateOrder'] as List),
    );
    expect(
      highOnly(
        engine.filteredRows(
          limit: 10,
          filter: const ViewFilter(watchlistOnly: true),
        ),
      ),
      List<String>.from(fixture['expectedWatchlistOnlyOrder'] as List),
    );
    expect(
      highOnly(
        engine.filteredRows(
          limit: 10,
          filter: ViewFilter(query: fixture['query'] as String),
        ),
      ),
      List<String>.from(fixture['expectedQueryOrder'] as List),
    );

    final chartSummaries = <String, Map<ChartRange, ChartRangeSummary>>{};
    for (final raw in fixture['chartSummaries'] as List) {
      final m = raw as Map<String, dynamic>;
      final symbol = m['symbol'] as String;
      final range = chartRangeFromWire(m['range'] as String)!;
      chartSummaries.putIfAbsent(symbol, () => {});
      chartSummaries[symbol]![range] = ChartRangeSummary(
        range: range,
        capturedAt: 0,
        candleCount: 1,
        latestCloseCents: m['latestCloseCents'] as int?,
        ema20Cents: m['ema20Cents'] as int?,
        ema50Cents: m['ema50Cents'] as int?,
        ema200Cents: m['ema200Cents'] as int?,
        macdCents: m['macdCents'] as int?,
        signalCents: m['signalCents'] as int?,
        histogramCents: m['histogramCents'] as int?,
      );
    }

    final dcfAnalyses = <String, DcfAnalysis>{};
    for (final raw in fixture['dcfAnalyses'] as List) {
      final m = raw as Map<String, dynamic>;
      dcfAnalyses[m['symbol'] as String] = DcfAnalysis(
        bearIntrinsicValueCents: m['bearIntrinsicValueCents'] as int,
        baseIntrinsicValueCents: m['baseIntrinsicValueCents'] as int,
        bullIntrinsicValueCents: m['bullIntrinsicValueCents'] as int,
        waccBps: m['waccBps'] as int,
        baseGrowthBps: m['baseGrowthBps'] as int,
        netDebtDollars: m['netDebtDollars'] as int,
      );
    }

    final opportunityOrder = OpportunityEngine.buildRows(
      engine,
      context: OpportunityContext(
        chartSummariesBySymbol: chartSummaries,
        analysesBySymbol: dcfAnalyses,
      ),
    ).map((r) => r.symbol).toList();
    expect(
      opportunityOrder,
      List<String>.from(fixture['expectedOpportunityOrder'] as List),
    );

    final expected = fixture['expectedSelectedDetail'] as Map<String, dynamic>;
    final detail = engine.detail(expected['symbol'] as String)!;
    expect(qualificationWireName(detail.qualification), expected['qualification']);
    expect(externalStatusWireName(detail.externalStatus), expected['externalStatus']);
    expect(confidenceWireName(detail.confidence), expected['confidence']);
    expect(detail.gapBps, expected['gapBps']);
    expect(detail.externalSignalGapBps, expected['externalSignalGapBps']);
    expect(
      detail.weightedExternalSignalFairValueCents,
      expected['weightedExternalSignalFairValueCents'],
    );
    expect(detail.weightedAnalystCount, expected['weightedAnalystCount']);
    expect(detail.analystOpinionCount, expected['analystOpinionCount']);
    expect(
      detail.recommendationMeanHundredths,
      expected['recommendationMeanHundredths'],
    );
    expect(detail.isWatched, expected['isWatched']);
  });
}

Map<String, dynamic> _loadJson(String fileName) {
  final path = _findFixturePath(fileName);
  return jsonDecode(File(path).readAsStringSync()) as Map<String, dynamic>;
}

String _findFixturePath(String fileName) {
  var current = Directory.current;
  for (var i = 0; i < 8; i++) {
    final candidate = File('${current.path}/shared/contracts/$fileName');
    if (candidate.existsSync()) return candidate.path;
    final parent = current.parent;
    if (parent.path == current.path) break;
    current = parent;
  }
  // Also try relative from package dir: ../../../../shared/contracts
  final fromPackage = File(
    '${Directory.current.path}/../../../../shared/contracts/$fileName',
  );
  if (fromPackage.existsSync()) return fromPackage.absolute.path;
  throw StateError(
    'shared contract fixture not found: $fileName from ${Directory.current.path}',
  );
}

DcfSourceCandidate? _candidate(DcfSource source, String state) {
  switch (state) {
    case 'absent':
      return null;
    case 'unavailable':
      return DcfSourceCandidate(source: source);
    case 'unsupported':
      return DcfSourceCandidate(
        source: source,
        timeseries: _unsupportedTimeseries(),
        analysis: _analysis(),
      );
    case 'usable':
      return DcfSourceCandidate(
        source: source,
        timeseries: _usableTimeseries(),
        analysis: _analysis(),
      );
    case 'divergent':
      return DcfSourceCandidate(
        source: source,
        timeseries: _divergentTimeseries(),
        analysis: _analysis(),
      );
    default:
      throw StateError('unknown DCF source fixture state $state');
  }
}

FundamentalTimeseries _usableTimeseries() => const FundamentalTimeseries(
      freeCashFlow: [
        AnnualReportedValue('2021-12-31', 100.0),
        AnnualReportedValue('2022-12-31', 120.0),
        AnnualReportedValue('2023-12-31', 140.0),
      ],
    );

FundamentalTimeseries _divergentTimeseries() => const FundamentalTimeseries(
      freeCashFlow: [
        AnnualReportedValue('2021-12-31', 100.0),
        AnnualReportedValue('2022-12-31', 120.0),
        AnnualReportedValue('2023-12-31', 180.0),
      ],
    );

FundamentalTimeseries _unsupportedTimeseries() => const FundamentalTimeseries(
      freeCashFlow: [AnnualReportedValue('2023-12-31', 140.0)],
    );

DcfAnalysis _analysis() => DcfAnalysis(
      bearIntrinsicValueCents: 8000,
      baseIntrinsicValueCents: 10000,
      bullIntrinsicValueCents: 12000,
      waccBps: 800,
      baseGrowthBps: 500,
      netDebtDollars: 0,
      source: DcfSource.yahooFinance,
      sourceFingerprint: 'fixture',
      resolverState: ResolverState.selected,
    );
