import 'package:ds_core/ds_core.dart';
import 'package:ds_data/ds_data.dart';
import 'package:flutter/foundation.dart';

enum DashboardTab { opportunities, tracked, watch, system, estimates }

enum DetailSubtab { snapshot, lens, history }

enum HistorySubview { graphs, table }

enum HistoryMetricGroup { core, fundamentals, relative, dcf, chart }

enum DetailSourceTab { tracked, opportunities }

class DetailRoute {
  const DetailRoute({
    required this.symbol,
    required this.sourceTab,
    required this.sourceSymbols,
    this.subtab = DetailSubtab.snapshot,
    this.chartRange = ChartRange.year,
    this.historySubview = HistorySubview.graphs,
    this.historyMetricGroup = HistoryMetricGroup.core,
    this.historyTimeWindow = ChartRange.year,
    this.replayOffset = 0,
  });

  final String symbol;
  final DetailSourceTab sourceTab;
  final List<String> sourceSymbols;
  final DetailSubtab subtab;
  final ChartRange chartRange;
  final HistorySubview historySubview;
  final HistoryMetricGroup historyMetricGroup;
  final ChartRange historyTimeWindow;
  final int replayOffset;

  DetailRoute copyWith({
    String? symbol,
    DetailSourceTab? sourceTab,
    List<String>? sourceSymbols,
    DetailSubtab? subtab,
    ChartRange? chartRange,
    HistorySubview? historySubview,
    HistoryMetricGroup? historyMetricGroup,
    ChartRange? historyTimeWindow,
    int? replayOffset,
  }) =>
      DetailRoute(
        symbol: symbol ?? this.symbol,
        sourceTab: sourceTab ?? this.sourceTab,
        sourceSymbols: sourceSymbols ?? this.sourceSymbols,
        subtab: subtab ?? this.subtab,
        chartRange: chartRange ?? this.chartRange,
        historySubview: historySubview ?? this.historySubview,
        historyMetricGroup: historyMetricGroup ?? this.historyMetricGroup,
        historyTimeWindow: historyTimeWindow ?? this.historyTimeWindow,
        replayOffset: replayOffset ?? this.replayOffset,
      );
}

sealed class DashboardAction {
  const DashboardAction();
}

class StartAction extends DashboardAction {
  const StartAction();
}

class RefreshAction extends DashboardAction {
  const RefreshAction();
}

class SelectTabAction extends DashboardAction {
  const SelectTabAction(this.tab);
  final DashboardTab tab;
}

class OpenDetailAction extends DashboardAction {
  const OpenDetailAction(this.symbol);
  final String symbol;
}

class BackFromDetailAction extends DashboardAction {
  const BackFromDetailAction();
}

class PrevTickerAction extends DashboardAction {
  const PrevTickerAction();
}

class NextTickerAction extends DashboardAction {
  const NextTickerAction();
}

class SetDetailSubtabAction extends DashboardAction {
  const SetDetailSubtabAction(this.subtab);
  final DetailSubtab subtab;
}

class SetChartRangeAction extends DashboardAction {
  const SetChartRangeAction(this.range);
  final ChartRange range;
}

class StepReplayBackAction extends DashboardAction {
  const StepReplayBackAction();
}

class StepReplayForwardAction extends DashboardAction {
  const StepReplayForwardAction();
}

class ResetReplayAction extends DashboardAction {
  const ResetReplayAction();
}

class ToggleWatchlistAction extends DashboardAction {
  const ToggleWatchlistAction(this.symbol);
  final String symbol;
}

class AddSymbolsAction extends DashboardAction {
  const AddSymbolsAction(this.rawInput);
  final String rawInput;
}

class SelectProfileAction extends DashboardAction {
  const SelectProfileAction(this.profile);
  final String profile;
}

class SetOpportunityScoringModelAction extends DashboardAction {
  const SetOpportunityScoringModelAction(this.model);
  final OpportunityScoringModel model;
}

class UpdateTickerSearchQueryAction extends DashboardAction {
  const UpdateTickerSearchQueryAction(this.query);
  final String query;
}

class ClearTickerSearchAction extends DashboardAction {
  const ClearTickerSearchAction();
}

class SubmitTickerSearchAction extends DashboardAction {
  const SubmitTickerSearchAction();
}

class AcceptDisclaimerAction extends DashboardAction {
  const AcceptDisclaimerAction();
}

class DashboardUiState {
  const DashboardUiState({
    this.loading = true,
    this.refreshing = false,
    this.disclaimerAccepted = false,
    this.splashMinimumElapsed = false,
    this.currentTab = DashboardTab.opportunities,
    this.availableProfiles = const [
      'sp500',
      'dow',
      'russell',
      'merval',
      'nikkei',
      'europe',
      'asia',
    ],
    this.currentProfile = 'sp500',
    this.tickerSearchQuery = '',
    this.trackedRows = const [],
    this.watchlistSymbols = const [],
    this.opportunityRows = const [],
    this.opportunityScoringModel = OpportunityScoringModel.aggressiveV2,
    this.issues = const [],
    this.detailRoute,
    this.detail,
    this.projectedChart,
    this.quantLens,
    this.estimates,
    this.statusMessage,
    this.usingDemoData = false,
  });

  final bool loading;
  final bool refreshing;
  final bool disclaimerAccepted;
  final bool splashMinimumElapsed;
  final DashboardTab currentTab;
  final List<String> availableProfiles;
  final String currentProfile;
  final String tickerSearchQuery;
  final List<CandidateRow> trackedRows;
  final List<String> watchlistSymbols;
  final List<OpportunityRow> opportunityRows;
  final OpportunityScoringModel opportunityScoringModel;
  final List<IssueRecord> issues;
  final DetailRoute? detailRoute;
  final SymbolDetail? detail;
  final ProjectedChartData? projectedChart;
  final QuantLensReport? quantLens;
  final IndexEstimatesReport? estimates;
  final String? statusMessage;
  final bool usingDemoData;

  DashboardUiState copyWith({
    bool? loading,
    bool? refreshing,
    bool? disclaimerAccepted,
    bool? splashMinimumElapsed,
    DashboardTab? currentTab,
    List<String>? availableProfiles,
    String? currentProfile,
    String? tickerSearchQuery,
    List<CandidateRow>? trackedRows,
    List<String>? watchlistSymbols,
    List<OpportunityRow>? opportunityRows,
    OpportunityScoringModel? opportunityScoringModel,
    List<IssueRecord>? issues,
    DetailRoute? detailRoute,
    SymbolDetail? detail,
    ProjectedChartData? projectedChart,
    QuantLensReport? quantLens,
    IndexEstimatesReport? estimates,
    String? statusMessage,
    bool? usingDemoData,
    bool clearDetail = false,
  }) =>
      DashboardUiState(
        loading: loading ?? this.loading,
        refreshing: refreshing ?? this.refreshing,
        disclaimerAccepted: disclaimerAccepted ?? this.disclaimerAccepted,
        splashMinimumElapsed:
            splashMinimumElapsed ?? this.splashMinimumElapsed,
        currentTab: currentTab ?? this.currentTab,
        availableProfiles: availableProfiles ?? this.availableProfiles,
        currentProfile: currentProfile ?? this.currentProfile,
        tickerSearchQuery: tickerSearchQuery ?? this.tickerSearchQuery,
        trackedRows: trackedRows ?? this.trackedRows,
        watchlistSymbols: watchlistSymbols ?? this.watchlistSymbols,
        opportunityRows: opportunityRows ?? this.opportunityRows,
        opportunityScoringModel:
            opportunityScoringModel ?? this.opportunityScoringModel,
        issues: issues ?? this.issues,
        detailRoute: clearDetail ? null : (detailRoute ?? this.detailRoute),
        detail: clearDetail ? null : (detail ?? this.detail),
        projectedChart:
            clearDetail ? null : (projectedChart ?? this.projectedChart),
        quantLens: clearDetail ? null : (quantLens ?? this.quantLens),
        estimates: estimates ?? this.estimates,
        statusMessage: statusMessage ?? this.statusMessage,
        usingDemoData: usingDemoData ?? this.usingDemoData,
      );
}

/// Presenter mirroring Android `DashboardViewModel` action surface.
class DashboardController extends ChangeNotifier {
  DashboardController({
    DashboardRepository? repository,
    this.liveFetchSymbolLimit = 25,
    this.preferDemoOnEmptyNetwork = true,
    this.backgroundContinueProfile = false,
  }) : _repository = repository;

  DashboardRepository? _repository;
  final int liveFetchSymbolLimit;
  final bool preferDemoOnEmptyNetwork;

  /// After the initial capped batch, keep fetching the rest of the profile.
  final bool backgroundContinueProfile;
  bool _backgroundLoadRunning = false;

  DashboardUiState _state = const DashboardUiState();
  DashboardUiState get state => _state;

  ReportingEngine get _engine =>
      _repository?.engine ?? (_localEngine ??= ReportingEngine());
  ReportingEngine? _localEngine;

  Map<String, Map<ChartRange, ChartRangeSummary>> get _chartSummaries =>
      _repository?.chartSummaries ?? (_localCharts ??= {});
  Map<String, Map<ChartRange, ChartRangeSummary>>? _localCharts;

  Map<String, DcfAnalysis> get _analyses =>
      _repository?.analyses ?? (_localAnalyses ??= {});
  Map<String, DcfAnalysis>? _localAnalyses;

  void attachRepository(DashboardRepository repository) {
    _repository = repository;
  }

  void dispatch(DashboardAction action) {
    switch (action) {
      case StartAction():
        if (_repository == null) {
          _seedDemoUniverse();
          _state = _state.copyWith(
            loading: false,
            usingDemoData: true,
            statusMessage: 'Demo data loaded',
          );
          _resnapshot();
        } else {
          _startLive();
        }
      case RefreshAction():
        _refresh();
      case SelectTabAction(:final tab):
        _state = _state.copyWith(currentTab: tab);
        if (tab == DashboardTab.estimates) {
          _state = _state.copyWith(
            estimates: _repository?.estimatesReport() ?? _demoEstimates(),
          );
        }
        notifyListeners();
      case OpenDetailAction(:final symbol):
        _openDetail(symbol);
      case BackFromDetailAction():
        _state = _state.copyWith(clearDetail: true);
        notifyListeners();
      case PrevTickerAction():
        _navigateTicker(-1);
      case NextTickerAction():
        _navigateTicker(1);
      case SetDetailSubtabAction(:final subtab):
        final route = _state.detailRoute;
        if (route == null) return;
        _state = _state.copyWith(detailRoute: route.copyWith(subtab: subtab));
        _refreshDetailSideData();
        notifyListeners();
      case SetChartRangeAction(:final range):
        final route = _state.detailRoute;
        if (route == null) return;
        _state = _state.copyWith(
          detailRoute: route.copyWith(chartRange: range, replayOffset: 0),
        );
        _loadChartForRoute();
      case StepReplayBackAction():
        final route = _state.detailRoute;
        if (route == null) return;
        final total =
            _state.projectedChart?.analysis.replayWindow.totalCandles ?? 0;
        final next = ChartAnalysis.stepReplayBack(route.replayOffset, total);
        _state = _state.copyWith(
          detailRoute: route.copyWith(replayOffset: next),
        );
        _refreshDetailSideData();
        notifyListeners();
      case StepReplayForwardAction():
        final route = _state.detailRoute;
        if (route == null) return;
        final next = ChartAnalysis.stepReplayForward(route.replayOffset);
        _state = _state.copyWith(
          detailRoute: route.copyWith(replayOffset: next),
        );
        _refreshDetailSideData();
        notifyListeners();
      case ResetReplayAction():
        final route = _state.detailRoute;
        if (route == null) return;
        _state = _state.copyWith(
          detailRoute: route.copyWith(replayOffset: 0),
        );
        _refreshDetailSideData();
        notifyListeners();
      case ToggleWatchlistAction(:final symbol):
        _engine.toggleWatchlist(symbol);
        _resnapshot();
        _repository?.persist();
      case AddSymbolsAction(:final rawInput):
        _addSymbols(rawInput);
      case SelectProfileAction(:final profile):
        _selectProfile(profile);
      case SetOpportunityScoringModelAction(:final model):
        _state = _state.copyWith(opportunityScoringModel: model);
        if (_repository != null) _repository!.scoringModel = model;
        _resnapshot();
      case UpdateTickerSearchQueryAction(:final query):
        _state = _state.copyWith(tickerSearchQuery: query.toUpperCase());
        notifyListeners();
      case ClearTickerSearchAction():
        _state = _state.copyWith(tickerSearchQuery: '');
        notifyListeners();
      case SubmitTickerSearchAction():
        final q = _state.tickerSearchQuery.trim();
        if (q.isEmpty) return;
        _openDetail(q);
      case AcceptDisclaimerAction():
        _state = _state.copyWith(disclaimerAccepted: true);
        notifyListeners();
    }
  }

  void markSplashElapsed() {
    _state = _state.copyWith(splashMinimumElapsed: true);
    notifyListeners();
  }

  Future<void> _startLive() async {
    _state = _state.copyWith(loading: true, statusMessage: 'Starting…');
    notifyListeners();
    try {
      await _repository!.bootstrap();
      final profiles = _repository!.profileCatalog.availableProfiles();
      _state = _state.copyWith(
        availableProfiles:
            profiles.isEmpty ? _state.availableProfiles : profiles,
        currentProfile: _repository!.currentProfile,
        opportunityScoringModel: _repository!.scoringModel,
      );
      if (_engine.symbolCount() == 0) {
        await _repository!.loadProfileAndRefresh(
          symbolLimit: liveFetchSymbolLimit,
          onProgress: (m) {
            _state = _state.copyWith(statusMessage: m);
            notifyListeners();
          },
        );
      }
      if (_engine.symbolCount() == 0 && preferDemoOnEmptyNetwork) {
        _seedDemoUniverse();
        _state = _state.copyWith(
          usingDemoData: true,
          statusMessage: 'Demo data (network empty or offline)',
        );
      } else {
        _state = _state.copyWith(
          usingDemoData: false,
          statusMessage: _coverageStatusMessage(),
        );
      }
    } catch (e) {
      _seedDemoUniverse();
      _state = _state.copyWith(
        usingDemoData: true,
        statusMessage: 'Startup fallback to demo: $e',
      );
    }
    _state = _state.copyWith(loading: false);
    _resnapshot();
    _scheduleBackgroundProfileContinue();
  }

  void _scheduleBackgroundProfileContinue() {
    if (!backgroundContinueProfile) return;
    if (_repository == null || _state.usingDemoData) return;
    if (_backgroundLoadRunning) return;
    _backgroundLoadRunning = true;
    // Fire-and-forget: expand beyond the initial symbol limit + retry gaps.
    () async {
      try {
        await _repository!.loadProfileAndRefresh(
          skipAlreadyTracked: true,
          onProgress: (m) {
            _state = _state.copyWith(statusMessage: m);
            notifyListeners();
          },
        );
        // Second pass for symbols that failed on first attempt (rate limits).
        await _repository!.loadProfileAndRefresh(
          skipAlreadyTracked: true,
          onProgress: (m) {
            _state = _state.copyWith(statusMessage: 'Retry: $m');
            notifyListeners();
          },
        );
        _state = _state.copyWith(statusMessage: _coverageStatusMessage());
        _resnapshot();
      } catch (e) {
        _state = _state.copyWith(statusMessage: 'Background load: $e');
        notifyListeners();
      } finally {
        _backgroundLoadRunning = false;
      }
    }();
  }

  Future<void> _refresh() async {
    _state = _state.copyWith(
      refreshing: true,
      statusMessage: 'Refreshing full profile…',
    );
    notifyListeners();
    try {
      if (_repository != null && !_state.usingDemoData) {
        // User-visible Refresh always covers the whole profile (not the
        // startup batch cap of liveFetchSymbolLimit).
        await _repository!.loadProfileAndRefresh(
          onProgress: (m) {
            _state = _state.copyWith(statusMessage: m);
            notifyListeners();
          },
        );
        // Retry symbols that still failed to enter the engine (Yahoo 401/404).
        await _repository!.loadProfileAndRefresh(
          skipAlreadyTracked: true,
          onProgress: (m) {
            _state = _state.copyWith(statusMessage: 'Retry: $m');
            notifyListeners();
          },
        );
        _state = _state.copyWith(statusMessage: _coverageStatusMessage());
      }
    } catch (e) {
      _state = _state.copyWith(statusMessage: 'Refresh error: $e');
    }
    _resnapshot();
    _state = _state.copyWith(refreshing: false);
    notifyListeners();
  }

  Future<void> _selectProfile(String profile) async {
    _state = _state.copyWith(
      currentProfile: profile,
      clearDetail: true,
      statusMessage: 'Loading profile $profile…',
      refreshing: true,
    );
    notifyListeners();
    if (_repository != null) {
      try {
        // Fast first paint, then background continues the rest of the profile.
        await _repository!.loadProfileAndRefresh(
          profile: profile,
          symbolLimit: liveFetchSymbolLimit,
          onProgress: (m) {
            _state = _state.copyWith(statusMessage: m);
            notifyListeners();
          },
        );
        _state = _state.copyWith(usingDemoData: _engine.symbolCount() == 0);
      } catch (e) {
        _state = _state.copyWith(statusMessage: 'Profile error: $e');
      }
    } else {
      _state = _state.copyWith(
        statusMessage: 'Profile set to $profile (demo universe)',
      );
    }
    _state = _state.copyWith(refreshing: false);
    _resnapshot();
    _scheduleBackgroundProfileContinue();
  }

  String _coverageStatusMessage() {
    final loaded = _engine.symbolCount();
    final profileSize = _repository?.profileSymbolCount() ?? loaded;
    final missing = _repository?.missingProfileSymbols() ?? const <String>[];
    if (missing.isEmpty) {
      return 'Loaded $loaded / $profileSize symbols';
    }
    final sample = missing.take(6).join(', ');
    final more = missing.length > 6 ? '…' : '';
    return 'Loaded $loaded / $profileSize · missing ${missing.length}: $sample$more';
  }

  Future<void> _openDetail(String symbol) async {
    final sourceTab = _state.currentTab == DashboardTab.opportunities
        ? DetailSourceTab.opportunities
        : DetailSourceTab.tracked;
    final sourceSymbols = sourceTab == DetailSourceTab.opportunities
        ? _state.opportunityRows.map((r) => r.symbol).toList()
        : _visibleTracked().map((r) => r.symbol).toList();
    final symbols = sourceSymbols.contains(symbol)
        ? sourceSymbols
        : [symbol, ...sourceSymbols];
    _state = _state.copyWith(
      detailRoute: DetailRoute(
        symbol: symbol,
        sourceTab: sourceTab,
        sourceSymbols: symbols,
      ),
      detail: _engine.detail(symbol),
    );
    notifyListeners();
    if (_repository != null && _engine.detail(symbol) == null) {
      try {
        await _repository!.refreshSymbol(symbol);
      } catch (_) {}
    }
    await _loadChartForRoute();
  }

  Future<void> _navigateTicker(int direction) async {
    final route = _state.detailRoute;
    if (route == null) return;
    final idx = route.sourceSymbols.indexOf(route.symbol);
    if (idx < 0) return;
    final next = idx + direction;
    if (next < 0 || next >= route.sourceSymbols.length) return;
    final symbol = route.sourceSymbols[next];
    _state = _state.copyWith(
      detailRoute: route.copyWith(symbol: symbol, replayOffset: 0),
      detail: _engine.detail(symbol),
    );
    notifyListeners();
    await _loadChartForRoute();
  }

  Future<void> _loadChartForRoute() async {
    final route = _state.detailRoute;
    if (route == null) return;
    if (_repository != null) {
      try {
        await _repository!.ensureChart(route.symbol, route.chartRange);
      } catch (_) {}
    }
    _refreshDetailSideData();
    notifyListeners();
  }

  void _refreshDetailSideData() {
    final route = _state.detailRoute;
    if (route == null) return;
    ProjectedChartData? chart;
    QuantLensReport? lens;
    if (_repository != null) {
      chart = _repository!.projectedChart(
        route.symbol,
        route.chartRange,
        replayOffset: route.replayOffset,
      );
      lens = _repository!.quantLens(route.symbol, route.chartRange);
    } else {
      // Demo synthetic chart from summaries.
      chart = _demoChart(route);
    }
    _state = _state.copyWith(
      detail: _engine.detail(route.symbol),
      projectedChart: chart,
      quantLens: lens,
    );
  }

  Future<void> _addSymbols(String rawInput) async {
    final symbols = rawInput
        .split(RegExp(r'[\s,]+'))
        .map((s) => s.trim().toUpperCase())
        .where((s) => s.isNotEmpty)
        .toList();
    for (final symbol in symbols) {
      if (_repository != null) {
        try {
          await _repository!.refreshSymbol(symbol);
        } catch (_) {
          if (_engine.detail(symbol) == null) {
            _engine.ingestSnapshot(
              MarketSnapshot(
                symbol: symbol,
                companyName: symbol,
                profitable: true,
                marketPriceCents: 10000,
                intrinsicValueCents: 15000,
              ),
            );
          }
        }
      } else if (_engine.detail(symbol) == null) {
        _engine.ingestSnapshot(
          MarketSnapshot(
            symbol: symbol,
            companyName: symbol,
            profitable: true,
            marketPriceCents: 10000,
            intrinsicValueCents: 15000,
          ),
        );
      }
    }
    _resnapshot();
  }

  List<CandidateRow> _visibleTracked() {
    if (_state.currentTab == DashboardTab.watch) {
      return _state.trackedRows
          .where((r) => _state.watchlistSymbols.contains(r.symbol))
          .toList();
    }
    return _state.trackedRows;
  }

  void _resnapshot() {
    final rows = _engine.filteredRows();
    final opps = OpportunityEngine.buildRows(
      _engine,
      context: OpportunityContext(
        chartSummariesBySymbol: _chartSummaries,
        analysesBySymbol: _analyses,
        scoringModel: _state.opportunityScoringModel,
      ),
    );
    _state = _state.copyWith(
      trackedRows: rows,
      watchlistSymbols: _engine.watchlistSymbols(),
      opportunityRows: opps,
      issues: _repository?.issues ?? _state.issues,
      estimates: _repository?.estimatesReport() ?? _demoEstimates(),
      detail: _state.detailRoute == null
          ? null
          : _engine.detail(_state.detailRoute!.symbol),
    );
    if (_state.detailRoute != null) {
      _refreshDetailSideData();
    }
    notifyListeners();
  }

  IndexEstimatesReport _demoEstimates() {
    final details = _engine
        .trackedSymbols()
        .map(_engine.detail)
        .whereType<SymbolDetail>()
        .toList();
    return IndexEstimatesEngine.compute(
      symbols: details,
      dcfBySymbol: _analyses,
      profileName: _state.currentProfile,
      nowEpochSeconds: DateTime.now().millisecondsSinceEpoch ~/ 1000,
    );
  }

  ProjectedChartData? _demoChart(DetailRoute route) {
    final summary = _chartSummaries[route.symbol]?[route.chartRange];
    final price = summary?.latestCloseCents ??
        _engine.detail(route.symbol)?.marketPriceCents;
    if (price == null) return null;
    // Synthetic candles for demo painters.
    final candles = <HistoricalCandle>[];
    var p = price.toDouble();
    final now = DateTime.now().millisecondsSinceEpoch ~/ 1000;
    for (var i = 0; i < 52; i++) {
      final open = p;
      p = p * (1 + ((i % 5) - 2) * 0.01);
      final close = p;
      candles.add(
        HistoricalCandle(
          epochSeconds: now - (52 - i) * 86400 * 7,
          openCents: open.round(),
          highCents: (mathMax(open, close) * 1.02).round(),
          lowCents: (mathMin(open, close) * 0.98).round(),
          closeCents: close.round(),
          volume: 1000000 + i * 10000,
        ),
      );
    }
    return ChartAnalysis.buildProjectedChartData(
      range: route.chartRange,
      candles: candles,
      capturedAtEpochSeconds: now,
      replayOffset: route.replayOffset,
      volumeProfileBinCount: 24,
      summary: summary,
    );
  }

  int mathMax(double a, double b) => a > b ? a.round() : b.round();
  int mathMin(double a, double b) => a < b ? a.round() : b.round();

  void _seedDemoUniverse() {
    final samples = <(
      String,
      String,
      int,
      int,
      int,
      int,
      int,
    )>[
      ('STRONG', 'Strong Co', 1000, 2500, 2300, 2400, 12),
      ('WEAK', 'Weak Co', 1000, 2700, 1200, 1200, 3),
      ('AAPL', 'Apple Inc.', 18000, 22000, 21000, 21500, 40),
      ('MSFT', 'Microsoft', 42000, 50000, 48000, 49000, 35),
      ('BRK-B', 'Berkshire', 41000, 52000, 50000, 50500, 20),
    ];

    for (final s in samples) {
      _engine.ingestSnapshot(
        MarketSnapshot(
          symbol: s.$1,
          companyName: s.$2,
          profitable: true,
          marketPriceCents: s.$3,
          intrinsicValueCents: s.$4,
        ),
      );
      _engine.ingestExternal(
        ExternalValuationSignal(
          symbol: s.$1,
          fairValueCents: s.$5,
          ageSeconds: 0,
          lowFairValueCents: (s.$5 * 0.9).round(),
          highFairValueCents: (s.$5 * 1.1).round(),
          analystOpinionCount: s.$7,
          recommendationMeanHundredths: s.$1 == 'WEAK' ? 260 : 140,
          weightedFairValueCents: s.$6,
          weightedAnalystCount: s.$7 > 5 ? s.$7 - 3 : s.$7,
        ),
      );
      _engine.ingestFundamentals(
        FundamentalSnapshot(
          symbol: s.$1,
          freeCashFlowDollars: s.$1 == 'WEAK' ? -10000000 : 200000000,
          operatingCashFlowDollars: s.$1 == 'WEAK' ? -5000000 : 250000000,
          returnOnEquityBps: s.$1 == 'WEAK' ? 200 : 2500,
          debtToEquityHundredths: s.$1 == 'WEAK' ? 220 : 60,
          totalCashDollars: s.$1 == 'WEAK' ? 20000000 : 800000000,
          totalDebtDollars: s.$1 == 'WEAK' ? 200000000 : 100000000,
          earningsGrowthBps: s.$1 == 'WEAK' ? -700 : 1500,
          marketCapDollars: s.$1 == 'WEAK' ? 500000000 : 2000000000,
          sectorName: 'Demo',
          industryName: 'Sample',
        ),
      );
      _chartSummaries[s.$1] = {
        ChartRange.year: ChartRangeSummary(
          range: ChartRange.year,
          capturedAt: 0,
          candleCount: 52,
          latestCloseCents: s.$3,
          ema20Cents: (s.$3 * 0.95).round(),
          ema50Cents: (s.$3 * 0.9).round(),
          ema200Cents: (s.$3 * 0.85).round(),
          macdCents: s.$1 == 'WEAK' ? -50 : 220,
          signalCents: s.$1 == 'WEAK' ? 20 : 120,
          histogramCents: s.$1 == 'WEAK' ? -70 : 100,
          latestWilderRsi: s.$1 == 'WEAK' ? 32 : 58,
          latestRsiSlope: s.$1 == 'WEAK' ? -0.5 : 0.4,
          volumeRatioHundredths: 110,
        ),
      };
      if (s.$1 != 'WEAK') {
        _analyses[s.$1] = DcfAnalysis(
          bearIntrinsicValueCents: (s.$4 * 0.8).round(),
          baseIntrinsicValueCents: (s.$4 * 1.2).round(),
          bullIntrinsicValueCents: (s.$4 * 1.4).round(),
          waccBps: 900,
          baseGrowthBps: 1200,
          netDebtDollars: 0,
          source: DcfSource.yahooFinance,
        );
      }
    }
    _engine.toggleWatchlist('STRONG');
    _engine.toggleWatchlist('AAPL');
  }

  @override
  void dispose() {
    _repository?.dispose();
    super.dispose();
  }
}
