import 'package:ds_core/ds_core.dart';

/// Persistence boundary for report state, candles, and prefs.
abstract class StateStore {
  Future<void> ensureReady();

  Future<PersistedReportState?> loadReport();

  Future<void> saveReport(PersistedReportState state);

  Future<Map<String, Map<ChartRange, List<HistoricalCandle>>>> loadCandles();

  Future<void> saveCandles(
    Map<String, Map<ChartRange, List<HistoricalCandle>>> candles,
  );

  Future<Map<String, dynamic>> loadPrefs();

  Future<void> savePrefs(Map<String, dynamic> prefs);
}

/// In-memory store (web / tests / offline smoke without filesystem).
class MemoryStateStore implements StateStore {
  PersistedReportState? _report;
  Map<String, Map<ChartRange, List<HistoricalCandle>>> _candles = {};
  Map<String, dynamic> _prefs = {};

  @override
  Future<void> ensureReady() async {}

  @override
  Future<PersistedReportState?> loadReport() async => _report;

  @override
  Future<void> saveReport(PersistedReportState state) async {
    _report = state;
  }

  @override
  Future<Map<String, Map<ChartRange, List<HistoricalCandle>>>>
      loadCandles() async =>
          {
            for (final e in _candles.entries)
              e.key: {
                for (final r in e.value.entries) r.key: List.of(r.value),
              },
          };

  @override
  Future<void> saveCandles(
    Map<String, Map<ChartRange, List<HistoricalCandle>>> candles,
  ) async {
    _candles = {
      for (final e in candles.entries)
        e.key: {
          for (final r in e.value.entries) r.key: List.of(r.value),
        },
    };
  }

  @override
  Future<Map<String, dynamic>> loadPrefs() async => Map.of(_prefs);

  @override
  Future<void> savePrefs(Map<String, dynamic> prefs) async {
    _prefs = Map.of(prefs);
  }
}
