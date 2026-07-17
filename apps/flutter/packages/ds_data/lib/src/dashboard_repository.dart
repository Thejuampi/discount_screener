import 'dart:async';

import 'package:ds_core/ds_core.dart';

import 'profile_catalog.dart';
import 'state_store.dart';
import 'yahoo_client.dart';

/// Coordinates Yahoo fetch, DCF, charts, and persistence for the Flutter shell.
class DashboardRepository {
  DashboardRepository({
    required this.profileCatalog,
    required this.store,
    YahooFinanceClient? yahoo,
    this.maxConcurrentFetches = 4,
    this.liveMode = true,
  }) : yahoo = yahoo ?? YahooFinanceClient();

  final ProfileCatalog profileCatalog;
  final StateStore store;
  final YahooFinanceClient yahoo;
  final int maxConcurrentFetches;
  final bool liveMode;

  final ReportingEngine engine = ReportingEngine(
    // External signals from Yahoo quoteSummary are treated as fresh at ingest.
    externalSignalMaxAgeSeconds: 7 * 24 * 3600,
  );

  final Map<String, DcfAnalysis> analyses = {};
  final Map<String, Map<ChartRange, ChartRangeSummary>> chartSummaries = {};
  final Map<String, Map<ChartRange, List<HistoricalCandle>>> candles = {};
  final List<IssueRecord> issues = [];

  String currentProfile = 'sp500';
  OpportunityScoringModel scoringModel = OpportunityScoringModel.aggressiveV2;

  Future<void> bootstrap() async {
    await store.ensureReady();
    final prefs = await store.loadPrefs();
    currentProfile = prefs['profile'] as String? ?? currentProfile;
    final modelName = prefs['scoringModel'] as String?;
    if (modelName != null) {
      scoringModel = OpportunityScoringModel.values.firstWhere(
        (m) => m.name == modelName,
        orElse: () => OpportunityScoringModel.aggressiveV2,
      );
    }
    final restored = await store.loadReport();
    if (restored != null) {
      engine.restore(restored);
      for (final s in restored.symbolStates) {
        if (s.dcfAnalysis != null) analyses[s.symbol] = s.dcfAnalysis!;
      }
      issues
        ..clear()
        ..addAll(restored.issues);
      // Rewrite stale extreme-DCF intrinsics using analyst-first policy.
      reprojectPrimaryFairValues();
      await persist();
    }
    final savedCandles = await store.loadCandles();
    candles
      ..clear()
      ..addAll(savedCandles);
    for (final entry in candles.entries) {
      final byRange = <ChartRange, ChartRangeSummary>{};
      for (final r in entry.value.entries) {
        if (r.value.isEmpty) continue;
        byRange[r.key] = ChartAnalysis.buildSummary(
          range: r.key,
          candles: r.value,
          capturedAtEpochSeconds:
              DateTime.now().millisecondsSinceEpoch ~/ 1000,
        );
      }
      if (byRange.isNotEmpty) chartSummaries[entry.key] = byRange;
    }
  }

  int profileSymbolCount() =>
      profileCatalog.loadProfile(currentProfile).length;

  /// Profile symbols not yet present in the engine (failed or not fetched).
  List<String> missingProfileSymbols() {
    final tracked = engine.trackedSymbols().toSet();
    return profileCatalog
        .loadProfile(currentProfile)
        .where((s) => !tracked.contains(s))
        .toList();
  }

  Future<void> loadProfileAndRefresh({
    String? profile,
    void Function(String message)? onProgress,
    int? symbolLimit,
    bool skipAlreadyTracked = false,
  }) async {
    if (profile != null) currentProfile = profile;
    final symbols = profileCatalog.loadProfile(currentProfile);
    if (symbols.isEmpty) {
      onProgress?.call('Profile $currentProfile is empty; using demo seed');
      return;
    }
    var batch = symbolLimit == null
        ? List<String>.from(symbols)
        : symbols.take(symbolLimit).toList();
    if (skipAlreadyTracked) {
      final tracked = engine.trackedSymbols().toSet();
      batch = batch.where((s) => !tracked.contains(s)).toList();
    }
    if (batch.isEmpty) {
      onProgress?.call(
        'All ${symbols.length} profile symbols already loaded '
        '(${engine.symbolCount()} in engine)',
      );
      return;
    }
    onProgress?.call(
      'Fetching ${batch.length} of ${symbols.length} symbols '
      '(${engine.symbolCount()} already tracked)…',
    );
    if (!liveMode) return;

    var completed = 0;
    final queue = [...batch];
    Future<void> worker() async {
      while (queue.isNotEmpty) {
        final symbol = queue.removeAt(0);
        try {
          await _refreshSymbol(symbol);
        } catch (e) {
          _recordIssue(symbol, e.toString());
        }
        completed++;
        if (completed % 10 == 0 || completed == batch.length) {
          onProgress?.call(
            'Fetched $completed / ${batch.length} · '
            'engine ${engine.symbolCount()} / ${symbols.length}',
          );
        }
      }
    }

    await Future.wait(
      List.generate(maxConcurrentFetches, (_) => worker()),
    );
    await persist();
    final missing = missingProfileSymbols();
    if (missing.isEmpty) {
      onProgress?.call(
        'Refresh complete (${engine.symbolCount()} / ${symbols.length} symbols)',
      );
    } else {
      onProgress?.call(
        'Refresh complete (${engine.symbolCount()} / ${symbols.length}; '
        'missing ${missing.length}: ${missing.take(5).join(', ')})',
      );
    }
  }

  Future<void> refreshSymbol(String symbol) async {
    await _refreshSymbol(symbol);
    await persist();
  }

  Future<void> ensureChart(
    String symbol,
    ChartRange range, {
    bool force = false,
  }) async {
    final existing = candles[symbol]?[range];
    if (!force && existing != null && existing.isNotEmpty) return;
    if (!liveMode) return;
    final fetched = await yahoo.fetchHistoricalCandles(symbol, range);
    if (fetched.isEmpty) return;
    final prior = (candles[symbol]?[range] ?? const [])
        .map((c) => PricingCandle(symbol: symbol, range: range, candle: c))
        .toList();
    final incoming =
        fetched.map((c) => PricingCandle(symbol: symbol, range: range, candle: c)).toList();
    final merged = PricingHistoryMerge.merge(prior, incoming)
        .map((p) => p.candle)
        .toList();
    candles.putIfAbsent(symbol, () => {})[range] = merged;
    final summary = ChartAnalysis.buildSummary(
      range: range,
      candles: merged,
      capturedAtEpochSeconds: DateTime.now().millisecondsSinceEpoch ~/ 1000,
    );
    chartSummaries.putIfAbsent(symbol, () => {})[range] = summary;
  }

  ProjectedChartData? projectedChart(
    String symbol,
    ChartRange range, {
    int replayOffset = 0,
    int volumeProfileBinCount = 24,
  }) {
    final list = candles[symbol]?[range];
    if (list == null || list.isEmpty) return null;
    return ChartAnalysis.buildProjectedChartData(
      range: range,
      candles: list,
      capturedAtEpochSeconds: DateTime.now().millisecondsSinceEpoch ~/ 1000,
      replayOffset: replayOffset,
      volumeProfileBinCount: volumeProfileBinCount,
      summary: chartSummaries[symbol]?[range],
    );
  }

  IndexEstimatesReport estimatesReport() {
    final details = engine
        .trackedSymbols()
        .map(engine.detail)
        .whereType<SymbolDetail>()
        .toList();
    return IndexEstimatesEngine.compute(
      symbols: details,
      dcfBySymbol: analyses,
      profileName: currentProfile,
      nowEpochSeconds: DateTime.now().millisecondsSinceEpoch ~/ 1000,
    );
  }

  QuantLensReport? quantLens(String symbol, ChartRange range) {
    final detail = engine.detail(symbol);
    if (detail == null) return null;
    final oppRows = OpportunityEngine.buildRows(
      engine,
      context: OpportunityContext(
        chartSummariesBySymbol: chartSummaries,
        analysesBySymbol: analyses,
        scoringModel: scoringModel,
      ),
    );
    return QuantLensEngine.analyze(
      QuantLensInput(
        detail: detail,
        selectedRange: range,
        nowEpochSeconds: DateTime.now().millisecondsSinceEpoch ~/ 1000,
        dcfAnalysis: analyses[symbol],
        chartSummaries: chartSummaries[symbol] ?? const {},
        selectedCandlesByRange: candles[symbol] ?? const {},
        opportunityRows: oppRows,
        peerCandlesBySymbol: {
          for (final e in candles.entries)
            if (e.key != symbol && e.value[range] != null)
              e.key: e.value[range]!,
        },
      ),
    );
  }

  Future<void> persist() async {
    final states = engine.persistedState().map((s) {
      final dcf = analyses[s.symbol];
      if (dcf == null) return s;
      return PersistedSymbolState(
        symbol: s.symbol,
        snapshot: s.snapshot,
        externalSignal: s.externalSignal,
        fundamentals: s.fundamentals,
        lastSequence: s.lastSequence,
        updateCount: s.updateCount,
        priceHistory: s.priceHistory,
        dcfAnalysis: dcf,
      );
    }).toList();
    await store.saveReport(
      PersistedReportState(
        trackedSymbols: engine.trackedSymbols(),
        watchlist: engine.watchlistSymbols(),
        symbolStates: states,
        issues: List.of(issues),
        lastPersistedAtEpochSeconds:
            DateTime.now().millisecondsSinceEpoch ~/ 1000,
      ),
    );
    await store.saveCandles(candles);
    await store.savePrefs({
      'profile': currentProfile,
      'scoringModel': scoringModel.name,
    });
  }

  Future<void> _refreshSymbol(String symbol) async {
    final result = await yahoo.fetchSymbol(symbol);
    if (result.error != null && result.snapshot == null) {
      _recordIssue(symbol, result.error!);
      return;
    }
    _clearIssue(symbol);
    if (result.snapshot != null) engine.ingestSnapshot(result.snapshot!);
    if (result.externalSignal != null) {
      engine.ingestExternal(result.externalSignal!);
    }
    if (result.fundamentals != null) {
      engine.ingestFundamentals(result.fundamentals!);
    }
    int? dcfBaseCents;
    try {
      final timeseries = await yahoo.fetchFundamentalTimeseries(symbol);
      if (timeseries.freeCashFlow.length >= 3 && result.fundamentals != null) {
        final dcf = DcfAnalysisEngine.compute(
          fundamentals: result.fundamentals!,
          timeseries: timeseries,
          marketPriceCents: result.snapshot?.marketPriceCents,
        );
        analyses[symbol] = dcf;
        if (dcf.baseIntrinsicValueCents > 0) {
          dcfBaseCents = dcf.baseIntrinsicValueCents;
        }
      }
    } catch (_) {
      // DCF optional; keep quote data.
    }

    // Primary fair value: analyst first, then plausible DCF (Android parity).
    // Always rewrite snapshot so stale DCF intrinsics cannot stick on disk.
    if (result.snapshot != null) {
      final ext = result.externalSignal;
      final primary = FairValueSelection.selectPrimaryCents(
        analystWeightedCents: ext?.weightedFairValueCents,
        analystMeanCents: ext?.fairValueCents,
        dcfBaseCents: dcfBaseCents,
        fallbackIntrinsicCents: result.snapshot!.intrinsicValueCents,
        marketPriceCents: result.snapshot!.marketPriceCents,
      );
      if (primary != null) {
        engine.ingestSnapshot(
          MarketSnapshot(
            symbol: symbol,
            companyName: result.snapshot!.companyName,
            profitable: result.snapshot!.profitable,
            marketPriceCents: result.snapshot!.marketPriceCents,
            intrinsicValueCents: primary,
          ),
        );
      }
    }
    try {
      await ensureChart(symbol, ChartRange.year);
    } catch (_) {}
  }

  void _recordIssue(String symbol, String detail) {
    final key = 'yahoo:$symbol';
    final now = DateTime.now().millisecondsSinceEpoch ~/ 1000;
    final existing = issues.indexWhere((i) => i.key == key);
    if (existing >= 0) {
      final prev = issues[existing];
      issues[existing] = IssueRecord(
        key: key,
        title: 'Yahoo fetch failed',
        detail: detail,
        severity: 'warning',
        active: true,
        count: prev.count + 1,
        lastSeenEpochSeconds: now,
      );
    } else {
      issues.add(
        IssueRecord(
          key: key,
          title: 'Yahoo fetch failed',
          detail: '$symbol: $detail',
          severity: 'warning',
          active: true,
          count: 1,
          lastSeenEpochSeconds: now,
        ),
      );
    }
  }

  /// Re-apply analyst-first fair values for every tracked symbol (post-restore).
  ///
  /// Writes cleaned values back into engine snapshots so persistence no longer
  /// carries extreme DCF intrinsics from older builds.
  void reprojectPrimaryFairValues() {
    for (final symbol in engine.trackedSymbols()) {
      final detail = engine.detail(symbol);
      if (detail == null) continue;
      final dcf = analyses[symbol];
      final primary = FairValueSelection.selectPrimaryCents(
        analystWeightedCents: detail.weightedExternalSignalFairValueCents,
        analystMeanCents: detail.externalSignalFairValueCents,
        dcfBaseCents: dcf?.baseIntrinsicValueCents,
        marketPriceCents: detail.marketPriceCents,
      );
      if (primary == null) continue;
      engine.ingestSnapshot(
        MarketSnapshot(
          symbol: symbol,
          companyName: detail.companyName,
          profitable: detail.profitable,
          marketPriceCents: detail.marketPriceCents,
          intrinsicValueCents: primary,
        ),
      );
    }
  }

  /// Clears a Yahoo issue after a successful symbol refresh.
  void _clearIssue(String symbol) {
    final key = 'yahoo:$symbol';
    final idx = issues.indexWhere((i) => i.key == key);
    if (idx < 0) return;
    final prev = issues[idx];
    issues[idx] = IssueRecord(
      key: prev.key,
      title: prev.title,
      detail: prev.detail,
      severity: prev.severity,
      active: false,
      count: prev.count,
      lastSeenEpochSeconds: prev.lastSeenEpochSeconds,
    );
  }

  void dispose() => yahoo.close();
}
