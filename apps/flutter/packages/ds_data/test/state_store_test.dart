import 'dart:io';

import 'package:ds_core/ds_core.dart';
import 'package:ds_data/ds_data.dart';
import 'package:ds_data/state_store_io.dart';
import 'package:test/test.dart';

void main() {
  test('JsonStateStore round-trips report and candles', () async {
    final dir = await Directory.systemTemp.createTemp('ds_store_');
    addTearDown(() => dir.delete(recursive: true));
    final store = JsonStateStore(dir);

    final report = PersistedReportState(
      trackedSymbols: const ['AAPL'],
      watchlist: const ['AAPL'],
      symbolStates: [
        PersistedSymbolState(
          symbol: 'AAPL',
          lastSequence: 1,
          updateCount: 1,
          snapshot: const MarketSnapshot(
            symbol: 'AAPL',
            companyName: 'Apple',
            profitable: true,
            marketPriceCents: 18000,
            intrinsicValueCents: 22000,
          ),
        ),
      ],
      lastPersistedAtEpochSeconds: 42,
    );
    await store.saveReport(report);
    final loaded = await store.loadReport();
    expect(loaded, isNotNull);
    expect(loaded!.trackedSymbols, ['AAPL']);
    expect(loaded.symbolStates.single.snapshot?.marketPriceCents, 18000);

    final candles = {
      'AAPL': {
        ChartRange.year: [
          const HistoricalCandle(
            epochSeconds: 100,
            openCents: 1,
            highCents: 2,
            lowCents: 1,
            closeCents: 2,
            volume: 9,
          ),
        ],
      },
    };
    await store.saveCandles(candles);
    final loadedCandles = await store.loadCandles();
    expect(loadedCandles['AAPL']![ChartRange.year]!.single.closeCents, 2);
  });

  test('ProfileCatalog parses and searches', () {
    final map = ProfileCatalog.parseProfileText(
      'demo',
      '# comment\nAAPL\nmsft\n\n',
    );
    final catalog = ProfileCatalog(profiles: map);
    expect(catalog.loadProfile('demo'), ['AAPL', 'MSFT']);
    final hits = catalog.searchTickers('AA');
    expect(hits.any((h) => h.symbol == 'AAPL'), isTrue);
  });
}
