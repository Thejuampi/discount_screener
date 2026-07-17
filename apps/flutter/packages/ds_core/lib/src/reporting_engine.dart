import 'models.dart';

const int maxPriceHistoryPerSymbol = 240;

class ReportingEngine {
  ReportingEngine({
    this.minGapBps = 2000,
    this.externalSignalMaxAgeSeconds = 30,
    this.tapeCapacity = 8,
  });

  final int minGapBps;
  final int externalSignalMaxAgeSeconds;
  final int tapeCapacity;

  final Map<String, _SymbolState> _symbols = {};
  final Set<String> _watchlist = {};
  final List<TapeEvent> _recentTape = [];
  final List<AlertEvent> _recentAlerts = [];

  int totalEvents = 0;
  int latestSequence = 0;

  int symbolCount() => _symbols.length;

  List<String> trackedSymbols() => _symbols.keys.toList();

  void ingestSnapshot(MarketSnapshot snapshot) {
    if (snapshot.marketPriceCents <= 0 || snapshot.intrinsicValueCents <= 0) {
      return;
    }
    final previousDetail = detail(snapshot.symbol);
    final sequence = _nextSequence();
    final state = _symbols.putIfAbsent(snapshot.symbol, _SymbolState.new);
    state.snapshot = snapshot;
    state.lastSequence = sequence;
    state.updateCount += 1;
    while (state.priceHistory.length >= maxPriceHistoryPerSymbol) {
      state.priceHistory.removeAt(0);
    }
    state.priceHistory.add(
      PriceHistoryPoint(sequence: sequence, marketPriceCents: snapshot.marketPriceCents),
    );
    totalEvents += 1;
    _pushTape(snapshot.symbol);
    _pushAlerts(snapshot.symbol, previousDetail);
  }

  void ingestExternal(ExternalValuationSignal signal) {
    if (signal.fairValueCents <= 0) return;
    final sanitized = sanitizeExternalSignal(signal);
    final previousDetail = detail(sanitized.symbol);
    final sequence = _nextSequence();
    final state = _symbols.putIfAbsent(sanitized.symbol, _SymbolState.new);
    state.externalSignal = sanitized;
    state.lastSequence = sequence;
    state.updateCount += 1;
    totalEvents += 1;
    _pushTape(sanitized.symbol);
    _pushAlerts(sanitized.symbol, previousDetail);
  }

  void ingestFundamentals(FundamentalSnapshot fundamentals) {
    if (!fundamentals.hasAnyValues()) return;
    final sequence = _nextSequence();
    final state = _symbols.putIfAbsent(fundamentals.symbol, _SymbolState.new);
    state.fundamentals = fundamentals;
    state.lastSequence = sequence;
    state.updateCount += 1;
    totalEvents += 1;
  }

  CandidateRow? candidate(String symbol) {
    final state = _symbols[symbol];
    return state == null ? null : _buildCandidate(state);
  }

  SymbolDetail? detail(String symbol) {
    final state = _symbols[symbol];
    return state == null ? null : _buildDetail(state);
  }

  List<CandidateRow> topRows(int limit) => _sortedRows().take(limit).toList();

  List<CandidateRow> filteredRows({
    int limit = 0x7fffffff,
    ViewFilter filter = const ViewFilter(),
  }) {
    final query = filter.query.trim();
    final rows = <CandidateRow>[];
    for (final entry in _symbols.entries) {
      final symbol = entry.key;
      final queryMatches =
          query.isEmpty || symbol.toUpperCase().contains(query.toUpperCase());
      final watchlistMatches = !filter.watchlistOnly || _watchlist.contains(symbol);
      if (!queryMatches || !watchlistMatches) continue;
      final row = _buildCandidate(entry.value);
      if (row != null) rows.add(row);
    }
    _sortRows(rows);
    if (rows.length > limit) return rows.sublist(0, limit);
    return rows;
  }

  List<PriceHistoryPoint> priceHistory(String symbol, {int limit = maxPriceHistoryPerSymbol}) {
    final history = _symbols[symbol]?.priceHistory ?? const [];
    if (history.length <= limit) return List.of(history);
    return history.sublist(history.length - limit);
  }

  List<String> watchlistSymbols() => (_watchlist.toList()..sort());

  void replaceWatchlist(List<String> symbols) {
    _watchlist
      ..clear()
      ..addAll(symbols);
  }

  bool toggleWatchlist(String symbol) {
    if (_watchlist.remove(symbol)) return false;
    _watchlist.add(symbol);
    return true;
  }

  bool isWatched(String symbol) => _watchlist.contains(symbol);

  List<TapeEvent> recentTape() => List.of(_recentTape);

  List<AlertEvent> alerts() => List.of(_recentAlerts);

  void restore(PersistedReportState state) {
    totalEvents = 0;
    latestSequence = 0;
    _symbols.clear();
    _recentTape.clear();
    _recentAlerts.clear();
    _watchlist.clear();

    for (final persisted in state.symbolStates) {
      latestSequence = latestSequence > persisted.lastSequence
          ? latestSequence
          : persisted.lastSequence;
      totalEvents += persisted.updateCount;
      final history = persisted.priceHistory.take(maxPriceHistoryPerSymbol).toList();
      _symbols[persisted.symbol] = _SymbolState()
        ..snapshot = persisted.snapshot
        ..externalSignal =
            persisted.externalSignal == null ? null : sanitizeExternalSignal(persisted.externalSignal!)
        ..fundamentals = persisted.fundamentals
        ..lastSequence = persisted.lastSequence
        ..updateCount = persisted.updateCount
        ..priceHistory.addAll(history);
    }
    _watchlist.addAll(state.watchlist);
  }

  List<PersistedSymbolState> persistedState() {
    final list = _symbols.entries
        .map(
          (e) => PersistedSymbolState(
            symbol: e.key,
            snapshot: e.value.snapshot,
            externalSignal: e.value.externalSignal,
            fundamentals: e.value.fundamentals,
            lastSequence: e.value.lastSequence,
            updateCount: e.value.updateCount,
            priceHistory: List.of(e.value.priceHistory),
          ),
        )
        .toList()
      ..sort((a, b) => a.symbol.compareTo(b.symbol));
    return list;
  }

  CandidateRow? _buildCandidate(_SymbolState state) {
    final d = _buildDetail(state);
    if (d == null) return null;
    return CandidateRow(
      symbol: d.symbol,
      marketPriceCents: d.marketPriceCents,
      intrinsicValueCents: d.intrinsicValueCents,
      gapBps: d.gapBps,
      upsideBps: d.upsideBps,
      isQualified: d.qualification == QualificationStatus.qualified,
      confidence: d.confidence,
      companyName: d.companyName,
    );
  }

  SymbolDetail? _buildDetail(_SymbolState state) => buildSymbolDetail(
        snapshot: state.snapshot,
        externalSignal: state.externalSignal,
        fundamentals: state.fundamentals,
        minGapBps: minGapBps,
        externalSignalMaxAgeSeconds: externalSignalMaxAgeSeconds,
        lastSequence: state.lastSequence,
        updateCount: state.updateCount,
        isWatched: state.snapshot != null && _watchlist.contains(state.snapshot!.symbol),
      );

  List<CandidateRow> _sortedRows() {
    final rows = <CandidateRow>[];
    for (final state in _symbols.values) {
      final row = _buildCandidate(state);
      if (row != null) rows.add(row);
    }
    _sortRows(rows);
    return rows;
  }

  void _sortRows(List<CandidateRow> rows) {
    rows.sort((a, b) {
      final q = (b.isQualified ? 1 : 0).compareTo(a.isQualified ? 1 : 0);
      if (q != 0) return q;
      final u = b.upsideBps.compareTo(a.upsideBps);
      if (u != 0) return u;
      final c = _confidenceRank(b.confidence).compareTo(_confidenceRank(a.confidence));
      if (c != 0) return c;
      return a.symbol.compareTo(b.symbol);
    });
  }

  int _confidenceRank(ConfidenceBand confidence) => switch (confidence) {
        ConfidenceBand.low => 0,
        ConfidenceBand.provisional => 1,
        ConfidenceBand.high => 2,
      };

  int _nextSequence() {
    latestSequence += 1;
    return latestSequence;
  }

  void _pushTape(String symbol) {
    if (tapeCapacity <= 0) return;
    final c = candidate(symbol);
    if (c == null) return;
    while (_recentTape.length >= tapeCapacity) {
      _recentTape.removeAt(0);
    }
    _recentTape.add(
      TapeEvent(
        symbol: c.symbol,
        gapBps: c.gapBps,
        isQualified: c.isQualified,
        confidence: c.confidence,
      ),
    );
  }

  void _pushAlerts(String symbol, SymbolDetail? previousDetail) {
    final currentDetail = detail(symbol);
    if (currentDetail == null) return;
    final previousQualified = previousDetail?.qualification == QualificationStatus.qualified;
    final currentQualified = currentDetail.qualification == QualificationStatus.qualified;

    if (!previousQualified && currentQualified) {
      _pushAlert(symbol, AlertKind.enteredQualified, currentDetail.lastSequence);
    } else if (previousQualified && !currentQualified) {
      _pushAlert(symbol, AlertKind.exitedQualified, currentDetail.lastSequence);
    } else if (previousQualified &&
        currentQualified &&
        previousDetail?.confidence != ConfidenceBand.high &&
        currentDetail.confidence == ConfidenceBand.high) {
      _pushAlert(symbol, AlertKind.confidenceUpgraded, currentDetail.lastSequence);
    }
  }

  void _pushAlert(String symbol, AlertKind kind, int sequence) {
    if (tapeCapacity <= 0) return;
    while (_recentAlerts.length >= tapeCapacity) {
      _recentAlerts.removeAt(0);
    }
    _recentAlerts.add(AlertEvent(symbol: symbol, kind: kind, sequence: sequence));
  }
}

class _SymbolState {
  MarketSnapshot? snapshot;
  ExternalValuationSignal? externalSignal;
  FundamentalSnapshot? fundamentals;
  int lastSequence = 0;
  int updateCount = 0;
  final List<PriceHistoryPoint> priceHistory = [];
}

int? checkedGapBps(int marketPriceCents, int fairValueCents) {
  if (fairValueCents <= 0) return null;
  final scaled = ((BigInt.from(fairValueCents) - BigInt.from(marketPriceCents)) *
          BigInt.from(10000)) ~/
      BigInt.from(fairValueCents);
  return scaled.toInt().clamp(-0x80000000, 0x7fffffff);
}

int? checkedUpsideBps(int marketPriceCents, int fairValueCents) {
  if (marketPriceCents <= 0 || fairValueCents <= 0) return null;
  final scaled = ((BigInt.from(fairValueCents) - BigInt.from(marketPriceCents)) *
          BigInt.from(10000)) ~/
      BigInt.from(marketPriceCents);
  return scaled.toInt().clamp(-0x80000000, 0x7fffffff);
}

int? clampedWeightedFairValue(ExternalValuationSignal? signal) {
  if (signal == null) return null;
  var weighted = signal.weightedFairValueCents;
  if (weighted == null) return null;
  if (signal.lowFairValueCents != null && signal.highFairValueCents != null) {
    final low = signal.lowFairValueCents! < signal.highFairValueCents!
        ? signal.lowFairValueCents!
        : signal.highFairValueCents!;
    final high = signal.lowFairValueCents! > signal.highFairValueCents!
        ? signal.lowFairValueCents!
        : signal.highFairValueCents!;
    weighted = weighted.clamp(low, high);
  }
  return weighted > 0 ? weighted : null;
}

ExternalValuationSignal sanitizeExternalSignal(ExternalValuationSignal signal) {
  final weighted = clampedWeightedFairValue(signal);
  if (weighted == null) {
    return signal.copyWith(clearWeighted: true);
  }
  return signal.copyWith(weightedFairValueCents: weighted);
}

SymbolDetail? buildSymbolDetail({
  MarketSnapshot? snapshot,
  ExternalValuationSignal? externalSignal,
  FundamentalSnapshot? fundamentals,
  int minGapBps = 2000,
  int externalSignalMaxAgeSeconds = 30,
  int lastSequence = 0,
  int updateCount = 0,
  bool isWatched = false,
}) {
  if (snapshot == null) return null;
  final sanitizedExternal =
      externalSignal == null ? null : sanitizeExternalSignal(externalSignal);
  // Model intrinsic drives gap/upside/qualification (shared contract). UI
  // fair value is reprojected at ingest via FairValueSelection (analyst-first).
  final internalGapBps =
      checkedGapBps(snapshot.marketPriceCents, snapshot.intrinsicValueCents) ?? 0;
  final internalUpsideBps =
      checkedUpsideBps(snapshot.marketPriceCents, snapshot.intrinsicValueCents) ?? 0;
  final qualification = _qualificationFor(snapshot, internalGapBps, minGapBps);
  final externalStatus = _externalStatusFor(
    snapshot,
    sanitizedExternal,
    minGapBps,
    externalSignalMaxAgeSeconds,
  );
  final confidence = _confidenceFor(qualification, externalStatus, sanitizedExternal);
  final weightedFairValue = clampedWeightedFairValue(sanitizedExternal);

  return SymbolDetail(
    symbol: snapshot.symbol,
    profitable: snapshot.profitable,
    marketPriceCents: snapshot.marketPriceCents,
    intrinsicValueCents: snapshot.intrinsicValueCents,
    gapBps: internalGapBps,
    upsideBps: internalUpsideBps,
    minimumGapBps: minGapBps,
    qualification: qualification,
    externalStatus: externalStatus,
    externalSignalFairValueCents: sanitizedExternal?.fairValueCents,
    externalSignalLowFairValueCents: sanitizedExternal?.lowFairValueCents,
    externalSignalHighFairValueCents: sanitizedExternal?.highFairValueCents,
    weightedExternalSignalFairValueCents: weightedFairValue,
    weightedAnalystCount: weightedFairValue != null ? sanitizedExternal?.weightedAnalystCount : null,
    externalSignalGapBps: sanitizedExternal == null
        ? null
        : checkedGapBps(snapshot.marketPriceCents, sanitizedExternal.fairValueCents),
    externalSignalAgeSeconds: sanitizedExternal?.ageSeconds,
    externalSignalMaxAgeSeconds: externalSignalMaxAgeSeconds,
    analystOpinionCount: sanitizedExternal?.analystOpinionCount,
    recommendationMeanHundredths: sanitizedExternal?.recommendationMeanHundredths,
    strongBuyCount: sanitizedExternal?.strongBuyCount,
    buyCount: sanitizedExternal?.buyCount,
    holdCount: sanitizedExternal?.holdCount,
    sellCount: sanitizedExternal?.sellCount,
    strongSellCount: sanitizedExternal?.strongSellCount,
    fundamentals: fundamentals,
    confidence: confidence,
    lastSequence: lastSequence,
    updateCount: updateCount,
    isWatched: isWatched,
    companyName: snapshot.companyName,
  );
}

QualificationStatus _qualificationFor(
  MarketSnapshot snapshot,
  int gapBps,
  int minGapBps,
) {
  if (!snapshot.profitable) return QualificationStatus.unprofitable;
  if (gapBps >= minGapBps) return QualificationStatus.qualified;
  return QualificationStatus.gapTooSmall;
}

ExternalSignalStatus _externalStatusFor(
  MarketSnapshot snapshot,
  ExternalValuationSignal? externalSignal,
  int minGapBps,
  int externalSignalMaxAgeSeconds,
) {
  if (externalSignal == null) return ExternalSignalStatus.missing;
  if (externalSignal.symbol != snapshot.symbol) return ExternalSignalStatus.divergent;
  if (externalSignal.ageSeconds > externalSignalMaxAgeSeconds) {
    return ExternalSignalStatus.stale;
  }
  final gap =
      checkedGapBps(snapshot.marketPriceCents, externalSignal.fairValueCents) ?? -0x80000000;
  return gap >= minGapBps ? ExternalSignalStatus.supportive : ExternalSignalStatus.divergent;
}

ConfidenceBand _confidenceFor(
  QualificationStatus qualification,
  ExternalSignalStatus externalStatus,
  ExternalValuationSignal? externalSignal,
) {
  if (qualification != QualificationStatus.qualified) return ConfidenceBand.low;
  switch (externalStatus) {
    case ExternalSignalStatus.missing:
      return ConfidenceBand.provisional;
    case ExternalSignalStatus.supportive:
      return _hasHighConfidenceAnalystCoverage(externalSignal)
          ? ConfidenceBand.high
          : ConfidenceBand.provisional;
    case ExternalSignalStatus.stale:
    case ExternalSignalStatus.divergent:
      return ConfidenceBand.low;
  }
}

bool _hasHighConfidenceAnalystCoverage(ExternalValuationSignal? signal) {
  final count = signal?.weightedAnalystCount ?? signal?.analystOpinionCount;
  return count != null && count >= 3;
}
