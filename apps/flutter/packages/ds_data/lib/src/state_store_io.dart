import 'dart:convert';
import 'dart:io';

import 'package:ds_core/ds_core.dart';
import 'package:path/path.dart' as p;

import 'state_store_base.dart';

/// File-backed persistence for report state + candles (multiplatform SQLite sibling).
///
/// Uses JSON documents under the app documents directory so Windows, Android, iOS,
/// and desktop share one implementation without platform-specific SQLite plugins.
class JsonStateStore implements StateStore {
  JsonStateStore(this.rootDirectory);

  final Directory rootDirectory;

  File get _reportFile => File(p.join(rootDirectory.path, 'report_state.json'));
  File get _candlesFile => File(p.join(rootDirectory.path, 'candles.json'));
  File get _prefsFile => File(p.join(rootDirectory.path, 'prefs.json'));

  @override
  Future<void> ensureReady() async {
    if (!await rootDirectory.exists()) {
      await rootDirectory.create(recursive: true);
    }
  }

  @override
  Future<PersistedReportState?> loadReport() async {
    await ensureReady();
    if (!await _reportFile.exists()) return null;
    try {
      final map = jsonDecode(await _reportFile.readAsString())
          as Map<String, dynamic>;
      return _decodeReport(map);
    } catch (_) {
      return null;
    }
  }

  @override
  Future<void> saveReport(PersistedReportState state) async {
    await ensureReady();
    await _reportFile.writeAsString(
      const JsonEncoder.withIndent('  ').convert(_encodeReport(state)),
    );
  }

  @override
  Future<Map<String, Map<ChartRange, List<HistoricalCandle>>>> loadCandles() async {
    await ensureReady();
    if (!await _candlesFile.exists()) return {};
    try {
      final map = jsonDecode(await _candlesFile.readAsString())
          as Map<String, dynamic>;
      final out = <String, Map<ChartRange, List<HistoricalCandle>>>{};
      for (final entry in map.entries) {
        final byRange = <ChartRange, List<HistoricalCandle>>{};
        final ranges = entry.value as Map<String, dynamic>;
        for (final r in ranges.entries) {
          final range = chartRangeFromWire(r.key) ??
              ChartRange.values.where((v) => v.name == r.key).firstOrNull;
          if (range == null) continue;
          final list = (r.value as List)
              .map((c) => _decodeCandle(c as Map<String, dynamic>))
              .toList();
          byRange[range] = list;
        }
        out[entry.key] = byRange;
      }
      return out;
    } catch (_) {
      return {};
    }
  }

  @override
  Future<void> saveCandles(
    Map<String, Map<ChartRange, List<HistoricalCandle>>> candles,
  ) async {
    await ensureReady();
    final map = <String, dynamic>{};
    for (final e in candles.entries) {
      map[e.key] = {
        for (final r in e.value.entries)
          chartRangeWireName(r.key): r.value.map(_encodeCandle).toList(),
      };
    }
    await _candlesFile.writeAsString(
      const JsonEncoder.withIndent('  ').convert(map),
    );
  }

  @override
  Future<Map<String, dynamic>> loadPrefs() async {
    await ensureReady();
    if (!await _prefsFile.exists()) return {};
    try {
      return jsonDecode(await _prefsFile.readAsString()) as Map<String, dynamic>;
    } catch (_) {
      return {};
    }
  }

  @override
  Future<void> savePrefs(Map<String, dynamic> prefs) async {
    await ensureReady();
    await _prefsFile.writeAsString(
      const JsonEncoder.withIndent('  ').convert(prefs),
    );
  }

  Map<String, dynamic> _encodeReport(PersistedReportState state) => {
        'trackedSymbols': state.trackedSymbols,
        'watchlist': state.watchlist,
        'lastPersistedAtEpochSeconds': state.lastPersistedAtEpochSeconds,
        'issues': state.issues
            .map(
              (i) => {
                'key': i.key,
                'title': i.title,
                'detail': i.detail,
                'severity': i.severity,
                'active': i.active,
                'count': i.count,
                'lastSeenEpochSeconds': i.lastSeenEpochSeconds,
              },
            )
            .toList(),
        'symbolStates': state.symbolStates.map(_encodeSymbolState).toList(),
      };

  Map<String, dynamic> _encodeSymbolState(PersistedSymbolState s) => {
        'symbol': s.symbol,
        'lastSequence': s.lastSequence,
        'updateCount': s.updateCount,
        if (s.snapshot != null) 'snapshot': _encodeSnapshot(s.snapshot!),
        if (s.externalSignal != null)
          'externalSignal': _encodeExternal(s.externalSignal!),
        if (s.fundamentals != null)
          'fundamentals': _encodeFundamentals(s.fundamentals!),
        'priceHistory': s.priceHistory
            .map(
              (p) => {
                'sequence': p.sequence,
                'marketPriceCents': p.marketPriceCents,
              },
            )
            .toList(),
        if (s.dcfAnalysis != null) 'dcfAnalysis': _encodeDcf(s.dcfAnalysis!),
      };

  Map<String, dynamic> _encodeSnapshot(MarketSnapshot s) => {
        'symbol': s.symbol,
        'companyName': s.companyName,
        'profitable': s.profitable,
        'marketPriceCents': s.marketPriceCents,
        'intrinsicValueCents': s.intrinsicValueCents,
      };

  Map<String, dynamic> _encodeExternal(ExternalValuationSignal s) => {
        'symbol': s.symbol,
        'fairValueCents': s.fairValueCents,
        'ageSeconds': s.ageSeconds,
        'lowFairValueCents': s.lowFairValueCents,
        'highFairValueCents': s.highFairValueCents,
        'analystOpinionCount': s.analystOpinionCount,
        'recommendationMeanHundredths': s.recommendationMeanHundredths,
        'strongBuyCount': s.strongBuyCount,
        'buyCount': s.buyCount,
        'holdCount': s.holdCount,
        'sellCount': s.sellCount,
        'strongSellCount': s.strongSellCount,
        'weightedFairValueCents': s.weightedFairValueCents,
        'weightedAnalystCount': s.weightedAnalystCount,
      };

  Map<String, dynamic> _encodeFundamentals(FundamentalSnapshot f) => {
        'symbol': f.symbol,
        'sectorName': f.sectorName,
        'industryName': f.industryName,
        'marketCapDollars': f.marketCapDollars,
        'sharesOutstanding': f.sharesOutstanding,
        'trailingPeHundredths': f.trailingPeHundredths,
        'forwardPeHundredths': f.forwardPeHundredths,
        'priceToBookHundredths': f.priceToBookHundredths,
        'returnOnEquityBps': f.returnOnEquityBps,
        'ebitdaDollars': f.ebitdaDollars,
        'enterpriseValueDollars': f.enterpriseValueDollars,
        'enterpriseToEbitdaHundredths': f.enterpriseToEbitdaHundredths,
        'totalDebtDollars': f.totalDebtDollars,
        'totalCashDollars': f.totalCashDollars,
        'debtToEquityHundredths': f.debtToEquityHundredths,
        'freeCashFlowDollars': f.freeCashFlowDollars,
        'operatingCashFlowDollars': f.operatingCashFlowDollars,
        'betaMillis': f.betaMillis,
        'trailingEpsCents': f.trailingEpsCents,
        'earningsGrowthBps': f.earningsGrowthBps,
      };

  Map<String, dynamic> _encodeDcf(DcfAnalysis d) => {
        'bearIntrinsicValueCents': d.bearIntrinsicValueCents,
        'baseIntrinsicValueCents': d.baseIntrinsicValueCents,
        'bullIntrinsicValueCents': d.bullIntrinsicValueCents,
        'waccBps': d.waccBps,
        'baseGrowthBps': d.baseGrowthBps,
        'netDebtDollars': d.netDebtDollars,
        'source': d.source == null ? null : dcfSourceWireName(d.source!),
        'resolverState': resolverStateWireName(d.resolverState),
      };

  Map<String, dynamic> _encodeCandle(HistoricalCandle c) => {
        'epochSeconds': c.epochSeconds,
        'openCents': c.openCents,
        'highCents': c.highCents,
        'lowCents': c.lowCents,
        'closeCents': c.closeCents,
        'volume': c.volume,
      };

  HistoricalCandle _decodeCandle(Map<String, dynamic> m) => HistoricalCandle(
        epochSeconds: m['epochSeconds'] as int,
        openCents: m['openCents'] as int,
        highCents: m['highCents'] as int,
        lowCents: m['lowCents'] as int,
        closeCents: m['closeCents'] as int,
        volume: m['volume'] as int,
      );

  PersistedReportState _decodeReport(Map<String, dynamic> map) {
    final symbolStates = (map['symbolStates'] as List? ?? const [])
        .map((e) => _decodeSymbolState(e as Map<String, dynamic>))
        .toList();
    final issues = (map['issues'] as List? ?? const [])
        .map(
          (e) {
            final m = e as Map<String, dynamic>;
            return IssueRecord(
              key: m['key'] as String,
              title: m['title'] as String,
              detail: m['detail'] as String,
              severity: m['severity'] as String,
              active: m['active'] as bool? ?? true,
              count: m['count'] as int? ?? 1,
              lastSeenEpochSeconds: m['lastSeenEpochSeconds'] as int? ?? 0,
            );
          },
        )
        .toList();
    return PersistedReportState(
      trackedSymbols:
          (map['trackedSymbols'] as List? ?? const []).cast<String>(),
      watchlist: (map['watchlist'] as List? ?? const []).cast<String>(),
      symbolStates: symbolStates,
      issues: issues,
      lastPersistedAtEpochSeconds: map['lastPersistedAtEpochSeconds'] as int?,
    );
  }

  PersistedSymbolState _decodeSymbolState(Map<String, dynamic> m) {
    final snap = m['snapshot'] as Map<String, dynamic>?;
    final ext = m['externalSignal'] as Map<String, dynamic>?;
    final fund = m['fundamentals'] as Map<String, dynamic>?;
    final dcf = m['dcfAnalysis'] as Map<String, dynamic>?;
    final history = (m['priceHistory'] as List? ?? const [])
        .map(
          (e) => PriceHistoryPoint(
            sequence: (e as Map)['sequence'] as int,
            marketPriceCents: e['marketPriceCents'] as int,
          ),
        )
        .toList();
    return PersistedSymbolState(
      symbol: m['symbol'] as String,
      lastSequence: m['lastSequence'] as int? ?? 0,
      updateCount: m['updateCount'] as int? ?? 0,
      priceHistory: history,
      snapshot: snap == null
          ? null
          : MarketSnapshot(
              symbol: snap['symbol'] as String,
              companyName: snap['companyName'] as String?,
              profitable: snap['profitable'] as bool? ?? true,
              marketPriceCents: snap['marketPriceCents'] as int,
              intrinsicValueCents: snap['intrinsicValueCents'] as int,
            ),
      externalSignal: ext == null
          ? null
          : ExternalValuationSignal(
              symbol: ext['symbol'] as String,
              fairValueCents: ext['fairValueCents'] as int,
              ageSeconds: ext['ageSeconds'] as int? ?? 0,
              lowFairValueCents: ext['lowFairValueCents'] as int?,
              highFairValueCents: ext['highFairValueCents'] as int?,
              analystOpinionCount: ext['analystOpinionCount'] as int?,
              recommendationMeanHundredths:
                  ext['recommendationMeanHundredths'] as int?,
              strongBuyCount: ext['strongBuyCount'] as int?,
              buyCount: ext['buyCount'] as int?,
              holdCount: ext['holdCount'] as int?,
              sellCount: ext['sellCount'] as int?,
              strongSellCount: ext['strongSellCount'] as int?,
              weightedFairValueCents: ext['weightedFairValueCents'] as int?,
              weightedAnalystCount: ext['weightedAnalystCount'] as int?,
            ),
      fundamentals: fund == null
          ? null
          : FundamentalSnapshot(
              symbol: fund['symbol'] as String,
              sectorName: fund['sectorName'] as String?,
              industryName: fund['industryName'] as String?,
              marketCapDollars: fund['marketCapDollars'] as int?,
              sharesOutstanding: fund['sharesOutstanding'] as int?,
              trailingPeHundredths: fund['trailingPeHundredths'] as int?,
              forwardPeHundredths: fund['forwardPeHundredths'] as int?,
              priceToBookHundredths: fund['priceToBookHundredths'] as int?,
              returnOnEquityBps: fund['returnOnEquityBps'] as int?,
              ebitdaDollars: fund['ebitdaDollars'] as int?,
              enterpriseValueDollars: fund['enterpriseValueDollars'] as int?,
              enterpriseToEbitdaHundredths:
                  fund['enterpriseToEbitdaHundredths'] as int?,
              totalDebtDollars: fund['totalDebtDollars'] as int?,
              totalCashDollars: fund['totalCashDollars'] as int?,
              debtToEquityHundredths: fund['debtToEquityHundredths'] as int?,
              freeCashFlowDollars: fund['freeCashFlowDollars'] as int?,
              operatingCashFlowDollars: fund['operatingCashFlowDollars'] as int?,
              betaMillis: fund['betaMillis'] as int?,
              trailingEpsCents: fund['trailingEpsCents'] as int?,
              earningsGrowthBps: fund['earningsGrowthBps'] as int?,
            ),
      dcfAnalysis: dcf == null
          ? null
          : DcfAnalysis(
              bearIntrinsicValueCents: dcf['bearIntrinsicValueCents'] as int,
              baseIntrinsicValueCents: dcf['baseIntrinsicValueCents'] as int,
              bullIntrinsicValueCents: dcf['bullIntrinsicValueCents'] as int,
              waccBps: dcf['waccBps'] as int,
              baseGrowthBps: dcf['baseGrowthBps'] as int,
              netDebtDollars: dcf['netDebtDollars'] as int,
              source: _dcfSourceFromWire(dcf['source'] as String?),
              resolverState: _resolverFromWire(dcf['resolverState'] as String?),
            ),
    );
  }

  DcfSource? _dcfSourceFromWire(String? s) => switch (s) {
        'YahooFinance' => DcfSource.yahooFinance,
        'SecEdgar' => DcfSource.secEdgar,
        'Derived' => DcfSource.derived,
        'Restored' => DcfSource.restored,
        'Unknown' => DcfSource.unknown,
        _ => null,
      };

  ResolverState? _resolverFromWire(String? s) => switch (s) {
        'Selected' => ResolverState.selected,
        'Unavailable' => ResolverState.unavailable,
        'NotEligible' => ResolverState.notEligible,
        'RestoredOnly' => ResolverState.restoredOnly,
        'ProviderUncertain' => ResolverState.providerUncertain,
        'Cancelled' => ResolverState.cancelled,
        _ => null,
      };
}
