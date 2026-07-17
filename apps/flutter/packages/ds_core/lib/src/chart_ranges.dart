import 'models.dart';

/// Canonical chart-range display labels from `shared/contracts/chart-ranges.json`.
String chartRangeLabel(ChartRange range) => switch (range) {
      ChartRange.day => 'D',
      ChartRange.week => 'W',
      ChartRange.month => 'M',
      ChartRange.year => '1Y',
      ChartRange.fiveYears => '5Y',
      ChartRange.tenYears => '10Y',
    };

/// Short UI labels used on Android chips (1D / 7D / 1M / …).
String chartRangeChipLabel(ChartRange range) => switch (range) {
      ChartRange.day => '1D',
      ChartRange.week => '7D',
      ChartRange.month => '1M',
      ChartRange.year => '1Y',
      ChartRange.fiveYears => '5Y',
      ChartRange.tenYears => '10Y',
    };
