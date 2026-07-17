import 'package:ds_core/ds_core.dart';
import 'package:test/test.dart';

void main() {
  group('ChartAnalysis', () {
    test('buildSummary produces EMA and MACD for rising series', () {
      final candles = List.generate(60, (i) {
        final close = 10000 + i * 50;
        return HistoricalCandle(
          epochSeconds: 1700000000 + i * 86400,
          openCents: close - 10,
          highCents: close + 20,
          lowCents: close - 20,
          closeCents: close,
          volume: 1000 + i * 10,
        );
      });
      final summary = ChartAnalysis.buildSummary(
        range: ChartRange.year,
        candles: candles,
        capturedAtEpochSeconds: 1700000000,
      );
      expect(summary.candleCount, 60);
      expect(summary.latestCloseCents, greaterThan(10000));
      expect(summary.ema20Cents, isNotNull);
      expect(summary.macdCents, isNotNull);
      expect(summary.latestWilderRsi, isNotNull);
    });

    test('replay window clamps offset', () {
      final candles = List.generate(
        10,
        (i) => HistoricalCandle(
          epochSeconds: i,
          openCents: 100,
          highCents: 110,
          lowCents: 90,
          closeCents: 105,
          volume: 1,
        ),
      );
      final window = ChartAnalysis.buildReplayWindow(candles, 3);
      expect(window.replayOffset, 3);
      expect(window.visibleCount, 7);
      expect(ChartAnalysis.stepReplayBack(0, 10), 1);
      expect(ChartAnalysis.stepReplayForward(2), 1);
    });
  });

  group('IndexEstimatesEngine', () {
    test('weights by market cap', () {
      final a = _detail('A', price: 10000, fair: 12000, cap: 100);
      final b = _detail('B', price: 20000, fair: 20000, cap: 300);
      final report = IndexEstimatesEngine.compute(
        symbols: [a, b],
        dcfBySymbol: {
          'A': DcfAnalysis(
            bearIntrinsicValueCents: 9000,
            baseIntrinsicValueCents: 15000,
            bullIntrinsicValueCents: 18000,
            waccBps: 900,
            baseGrowthBps: 500,
            netDebtDollars: 0,
            source: DcfSource.yahooFinance,
          ),
        },
        profileName: 'demo',
        nowEpochSeconds: 1,
      );
      expect(report.totalSymbols, 2);
      expect(report.currentWeightedPriceCents, greaterThan(0));
      final base =
          report.scenarios.firstWhere((s) => s.scenario == EstimateScenario.baseDcf);
      expect(base.coverageCount, 1);
    });
  });

  group('PricingHistoryMerge', () {
    test('dedupes by semantic period for year range', () {
      final a = PricingCandle(
        symbol: 'X',
        range: ChartRange.year,
        candle: const HistoricalCandle(
          epochSeconds: 1704067200, // 2024-01-01
          openCents: 1,
          highCents: 1,
          lowCents: 1,
          closeCents: 1,
          volume: 1,
        ),
      );
      final b = PricingCandle(
        symbol: 'X',
        range: ChartRange.year,
        candle: const HistoricalCandle(
          epochSeconds: 1704153600, // same ISO week
          openCents: 2,
          highCents: 2,
          lowCents: 2,
          closeCents: 2,
          volume: 2,
        ),
      );
      final merged = PricingHistoryMerge.merge([a], [b]);
      expect(merged.length, 1);
      expect(merged.single.candle.closeCents, 2);
    });
  });

  group('DcfAnalysisEngine', () {
    test('computes positive base intrinsic from growing FCF', () {
      final fundamentals = const FundamentalSnapshot(
        symbol: 'TEST',
        marketCapDollars: 1000000000,
        sharesOutstanding: 10000000,
        totalDebtDollars: 100000000,
        totalCashDollars: 50000000,
        betaMillis: 1100,
      );
      final timeseries = FundamentalTimeseries(
        freeCashFlow: [
          const AnnualReportedValue('2021-12-31', 80000000),
          const AnnualReportedValue('2022-12-31', 90000000),
          const AnnualReportedValue('2023-12-31', 100000000),
        ],
        dilutedAverageShares: [
          const AnnualReportedValue('2021-12-31', 10000000),
          const AnnualReportedValue('2022-12-31', 10000000),
          const AnnualReportedValue('2023-12-31', 10000000),
        ],
      );
      final dcf = DcfAnalysisEngine.compute(
        fundamentals: fundamentals,
        timeseries: timeseries,
        marketPriceCents: 10000,
      );
      expect(dcf.baseIntrinsicValueCents, greaterThan(0));
      expect(dcf.bullIntrinsicValueCents, greaterThan(dcf.bearIntrinsicValueCents));
      expect(dcf.waccBps, inInclusiveRange(500, 1800));
    });
  });

  group('OpportunityEngine V2', () {
    test('continuous scores land in ±100 for strong demo', () {
      final engine = ReportingEngine(externalSignalMaxAgeSeconds: 999999);
      engine.ingestSnapshot(
        const MarketSnapshot(
          symbol: 'S',
          profitable: true,
          marketPriceCents: 1000,
          intrinsicValueCents: 2500,
        ),
      );
      engine.ingestExternal(
        const ExternalValuationSignal(
          symbol: 'S',
          fairValueCents: 2300,
          ageSeconds: 0,
          lowFairValueCents: 2000,
          highFairValueCents: 2600,
          analystOpinionCount: 12,
          recommendationMeanHundredths: 150,
          weightedFairValueCents: 2400,
          weightedAnalystCount: 10,
        ),
      );
      engine.ingestFundamentals(
        const FundamentalSnapshot(
          symbol: 'S',
          freeCashFlowDollars: 200000000,
          operatingCashFlowDollars: 250000000,
          returnOnEquityBps: 2500,
          debtToEquityHundredths: 60,
          totalCashDollars: 800000000,
          totalDebtDollars: 100000000,
          earningsGrowthBps: 1500,
          marketCapDollars: 2000000000,
          forwardPeHundredths: 1200,
        ),
      );
      final detail = engine.detail('S')!;
      final score = OpportunityEngine.scoreWithModel(
        detail: detail,
        summary: const ChartRangeSummary(
          range: ChartRange.year,
          capturedAt: 0,
          candleCount: 52,
          latestCloseCents: 1000,
          ema20Cents: 950,
          ema50Cents: 900,
          ema200Cents: 850,
          macdCents: 20,
          signalCents: 10,
          histogramCents: 10,
        ),
        analysis: DcfAnalysis(
          bearIntrinsicValueCents: 2000,
          baseIntrinsicValueCents: 3000,
          bullIntrinsicValueCents: 4000,
          waccBps: 900,
          baseGrowthBps: 1000,
          netDebtDollars: 0,
          source: DcfSource.yahooFinance,
        ),
        model: OpportunityScoringModel.aggressiveV2,
      );
      expect(score.fundamentalsScore, isNotNull);
      expect(score.compositeScore, inInclusiveRange(-110, 110));
      expect(score.coverageCount, greaterThan(0));
    });
  });

  group('QuantLensEngine', () {
    test('reports evidence and expected value when inputs present', () {
      final detail = _detail('QL', price: 10000, fair: 15000, cap: 1e9.round());
      final report = QuantLensEngine.analyze(
        QuantLensInput(
          detail: detail,
          selectedRange: ChartRange.year,
          nowEpochSeconds: 1,
          dcfAnalysis: DcfAnalysis(
            bearIntrinsicValueCents: 12000,
            baseIntrinsicValueCents: 16000,
            bullIntrinsicValueCents: 20000,
            waccBps: 900,
            baseGrowthBps: 800,
            netDebtDollars: 0,
            source: DcfSource.yahooFinance,
          ),
          chartSummaries: {
            ChartRange.year: const ChartRangeSummary(
              range: ChartRange.year,
              capturedAt: 0,
              candleCount: 40,
              latestCloseCents: 10000,
            ),
          },
        ),
      );
      expect(report.symbol, 'QL');
      expect(
        report.evidenceStrength.primaryStatus,
        isNot(QuantLensPrimaryStatus.unavailable),
      );
      expect(report.expectedValueRange.midCents, isNotNull);
    });

    test('EV range keeps low <= mid <= high for DCF scenario', () {
      final detail = _detail('ALL', price: 25161, fair: 28000, cap: 1e9.round());
      final report = QuantLensEngine.analyze(
        QuantLensInput(
          detail: detail,
          selectedRange: ChartRange.year,
          nowEpochSeconds: 1,
          // Extreme DCF vs modest analyst — must not mix High analyst with Mid DCF.
          dcfAnalysis: DcfAnalysis(
            bearIntrinsicValueCents: 250000,
            baseIntrinsicValueCents: 297738,
            bullIntrinsicValueCents: 350000,
            waccBps: 900,
            baseGrowthBps: 800,
            netDebtDollars: 0,
            source: DcfSource.yahooFinance,
          ),
        ),
      );
      final ev = report.expectedValueRange;
      expect(ev.lowCents, isNotNull);
      expect(ev.midCents, isNotNull);
      expect(ev.highCents, isNotNull);
      expect(ev.lowCents! <= ev.midCents!, isTrue);
      expect(ev.midCents! <= ev.highCents!, isTrue);
      // Coherent DCF triple (sorted), not analyst high $300.
      expect(ev.highCents, 350000);
      expect(ev.source, ExpectedValueRangeSource.dcfBase);
    });
  });

  group('FairValueSelection', () {
    test('prefers analyst weighted over extreme DCF', () {
      final cents = FairValueSelection.selectPrimaryCents(
        analystWeightedCents: 28000,
        analystMeanCents: 27000,
        dcfBaseCents: 297738,
        fallbackIntrinsicCents: 25161,
        marketPriceCents: 25161,
      );
      expect(cents, 28000);
      expect(
        FairValueSelection.sourceLabel(
          analystWeightedCents: 28000,
          dcfBaseCents: 297738,
          marketPriceCents: 25161,
        ),
        'Analyst weighted',
      );
    });

    test('falls back to DCF when no analyst target and DCF is plausible', () {
      final cents = FairValueSelection.selectPrimaryCents(
        dcfBaseCents: 40000,
        fallbackIntrinsicCents: 25161,
        marketPriceCents: 25161,
      );
      expect(cents, 40000);
    });

    test('rejects extreme DCF without analyst and uses market', () {
      // ALL-like: price ~$251, DCF ~$2977 (1083% upside) with no analyst.
      final cents = FairValueSelection.selectPrimaryCents(
        dcfBaseCents: 297738,
        fallbackIntrinsicCents: 297738,
        marketPriceCents: 25161,
      );
      expect(cents, 25161);
    });
  });

  group('ReportingEngine fair display', () {
    test('ALL-like after reprojected analyst primary has sane upside', () {
      final engine = ReportingEngine(externalSignalMaxAgeSeconds: 999999);
      // After FairValueSelection at ingest: analyst weighted, not extreme DCF.
      engine.ingestSnapshot(
        const MarketSnapshot(
          symbol: 'ALL',
          companyName: 'The Allstate Corporation',
          profitable: true,
          marketPriceCents: 25161,
          intrinsicValueCents: 28000,
        ),
      );
      engine.ingestExternal(
        const ExternalValuationSignal(
          symbol: 'ALL',
          fairValueCents: 27000,
          ageSeconds: 0,
          lowFairValueCents: 24000,
          highFairValueCents: 30000,
          weightedFairValueCents: 28000,
          analystOpinionCount: 12,
        ),
      );
      final detail = engine.detail('ALL')!;
      expect(detail.intrinsicValueCents, 28000);
      expect(detail.upsideBps, lessThan(30000));
      expect(detail.upsideBps, greaterThan(0));
      // Selection policy: extreme DCF never wins when analyst exists.
      final primary = FairValueSelection.selectPrimaryCents(
        analystWeightedCents: 28000,
        analystMeanCents: 27000,
        dcfBaseCents: 297738,
        marketPriceCents: 25161,
      );
      expect(primary, 28000);
    });
  });

  group('QuantLens ALL-like', () {
    test('prefers analyst EV scenario when DCF is implausible', () {
      final engine = ReportingEngine(externalSignalMaxAgeSeconds: 999999);
      engine.ingestSnapshot(
        const MarketSnapshot(
          symbol: 'ALL',
          profitable: true,
          marketPriceCents: 25161,
          intrinsicValueCents: 28000,
        ),
      );
      engine.ingestExternal(
        const ExternalValuationSignal(
          symbol: 'ALL',
          fairValueCents: 27000,
          ageSeconds: 0,
          lowFairValueCents: 24000,
          highFairValueCents: 30000,
          weightedFairValueCents: 28000,
        ),
      );
      final detail = engine.detail('ALL')!;
      final report = QuantLensEngine.analyze(
        QuantLensInput(
          detail: detail,
          selectedRange: ChartRange.year,
          nowEpochSeconds: 1,
          dcfAnalysis: DcfAnalysis(
            bearIntrinsicValueCents: 250000,
            baseIntrinsicValueCents: 297738,
            bullIntrinsicValueCents: 350000,
            waccBps: 900,
            baseGrowthBps: 800,
            netDebtDollars: 0,
            source: DcfSource.yahooFinance,
          ),
        ),
      );
      final ev = report.expectedValueRange;
      expect(ev.source, ExpectedValueRangeSource.analystTarget);
      expect(ev.lowCents! <= ev.midCents!, isTrue);
      expect(ev.midCents! <= ev.highCents!, isTrue);
      expect(ev.highCents, lessThanOrEqualTo(30000));
      expect(ev.midCents, lessThanOrEqualTo(30000));
    });
  });
}

SymbolDetail _detail(
  String symbol, {
  required int price,
  required int fair,
  required int cap,
}) {
  final engine = ReportingEngine(externalSignalMaxAgeSeconds: 999999);
  engine.ingestSnapshot(
    MarketSnapshot(
      symbol: symbol,
      profitable: true,
      marketPriceCents: price,
      intrinsicValueCents: fair,
    ),
  );
  engine.ingestFundamentals(
    FundamentalSnapshot(
      symbol: symbol,
      marketCapDollars: cap,
    ),
  );
  return engine.detail(symbol)!;
}
