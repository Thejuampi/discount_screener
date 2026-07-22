import 'chart_projection.dart';
import 'models.dart';

/// Dedupes pricing candles by semantic period (Android `PricingHistoryMerge`).
class PricingHistoryMerge {
  static List<PricingCandle> merge(
    List<PricingCandle> existing,
    List<PricingCandle> incoming,
  ) {
    final candlesByKey = <String, PricingCandle>{};
    for (final candle in existing) {
      candlesByKey[_key(candle)] = candle;
    }
    for (final candle in incoming) {
      candlesByKey[_key(candle)] = candle;
    }
    final values = candlesByKey.values.toList()
      ..sort((a, b) {
        final s = a.symbol.compareTo(b.symbol);
        if (s != 0) return s;
        final r = a.range.index.compareTo(b.range.index);
        if (r != 0) return r;
        return a.candle.epochSeconds.compareTo(b.candle.epochSeconds);
      });
    return values;
  }

  static String _key(PricingCandle candle) =>
      '${candle.symbol}|${candle.range.name}|${_semanticPeriod(candle)}';

  static String _semanticPeriod(PricingCandle candle) {
    if (candle.range == ChartRange.day || candle.range == ChartRange.week) {
      return 'epoch:${candle.candle.epochSeconds}';
    }
    final date = DateTime.fromMillisecondsSinceEpoch(
      candle.candle.epochSeconds * 1000,
      isUtc: true,
    );
    switch (candle.range) {
      case ChartRange.month:
        return 'day:${date.toIso8601String().substring(0, 10)}';
      case ChartRange.year:
        // ISO week: Monday-based week of year.
        final thursday = date.add(Duration(days: 3 - ((date.weekday + 6) % 7)));
        final firstThursday = DateTime.utc(thursday.year, 1, 4);
        final week1Monday = firstThursday.subtract(
          Duration(days: (firstThursday.weekday + 6) % 7),
        );
        final week =
            1 + thursday.difference(week1Monday).inDays ~/ 7;
        return 'week:${thursday.year}-$week';
      case ChartRange.fiveYears:
      case ChartRange.tenYears:
        return 'month:${date.year}-${date.month}';
      case ChartRange.day:
      case ChartRange.week:
        return 'epoch:${candle.candle.epochSeconds}';
    }
  }
}
