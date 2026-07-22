import 'models.dart';

enum ProjectedChartStatus { unavailable, summaryOnly, available }

enum ProjectedTechnicalSignalKind {
  ema20Ema50,
  ema50Ema200,
  macdSignal,
  rsiMomentum,
  rsiInflection,
}

enum ProjectedTechnicalSignalBias { bull, bear }

class ProjectedPriceDomain {
  const ProjectedPriceDomain({required this.minValue, required this.maxValue});
  final double minValue;
  final double maxValue;
}

class ProjectedReplayWindow {
  const ProjectedReplayWindow({
    required this.visibleCandles,
    required this.totalCandles,
    required this.replayOffset,
  });
  final List<HistoricalCandle> visibleCandles;
  final int totalCandles;
  final int replayOffset;
  bool get isLive => replayOffset == 0;
  int get visibleCount => visibleCandles.length;
}

class ProjectedVolumeProfileBin {
  const ProjectedVolumeProfileBin({
    required this.upVolume,
    required this.downVolume,
  });
  final int upVolume;
  final int downVolume;
  int get totalVolume => upVolume + downVolume;
}

class ProjectedVolumeProfileAnalysis {
  const ProjectedVolumeProfileAnalysis({
    required this.bins,
    required this.maxBinVolume,
  });
  final List<ProjectedVolumeProfileBin> bins;
  final int maxBinVolume;
}

class ProjectedPriceChartAnalysis {
  const ProjectedPriceChartAnalysis({
    required this.domain,
    this.latestCloseCents,
    this.ema20 = const [],
    this.ema50 = const [],
    this.ema200 = const [],
    this.latestEma20Cents,
    this.latestEma50Cents,
    this.latestEma200Cents,
  });
  final ProjectedPriceDomain domain;
  final int? latestCloseCents;
  final List<double> ema20;
  final List<double> ema50;
  final List<double> ema200;
  final int? latestEma20Cents;
  final int? latestEma50Cents;
  final int? latestEma200Cents;
}

class ProjectedVolumeChartAnalysis {
  const ProjectedVolumeChartAnalysis({required this.maxVolume});
  final int maxVolume;
}

class ProjectedMacdChartAnalysis {
  const ProjectedMacdChartAnalysis({
    required this.macdLine,
    required this.signalLine,
    required this.histogram,
    this.latestMacdCents,
    this.latestSignalCents,
    this.latestHistogramCents,
  });
  final List<double> macdLine;
  final List<double> signalLine;
  final List<double> histogram;
  final int? latestMacdCents;
  final int? latestSignalCents;
  final int? latestHistogramCents;
}

class ProjectedRsiChartAnalysis {
  const ProjectedRsiChartAnalysis({
    required this.wilderRsi,
    required this.signalRsi,
    required this.slope,
    required this.acceleration,
    this.latestWilderRsi,
    this.latestSignalRsi,
    this.latestSlope,
    this.latestAcceleration,
  });
  final List<double> wilderRsi;
  final List<double> signalRsi;
  final List<double> slope;
  final List<double> acceleration;
  final double? latestWilderRsi;
  final double? latestSignalRsi;
  final double? latestSlope;
  final double? latestAcceleration;
}

class ProjectedTechnicalSignal {
  const ProjectedTechnicalSignal({
    required this.kind,
    required this.title,
    required this.meaning,
    required this.bias,
    required this.freshCross,
  });
  final ProjectedTechnicalSignalKind kind;
  final String title;
  final String meaning;
  final ProjectedTechnicalSignalBias bias;
  final bool freshCross;
}

class ProjectedChartAnalysis {
  const ProjectedChartAnalysis({
    required this.status,
    required this.replayWindow,
    this.price,
    this.volume,
    this.volumeProfile,
    this.macd,
    this.rsi,
    this.technicalSignals = const [],
  });
  final ProjectedChartStatus status;
  final ProjectedReplayWindow replayWindow;
  final ProjectedPriceChartAnalysis? price;
  final ProjectedVolumeChartAnalysis? volume;
  final ProjectedVolumeProfileAnalysis? volumeProfile;
  final ProjectedMacdChartAnalysis? macd;
  final ProjectedRsiChartAnalysis? rsi;
  final List<ProjectedTechnicalSignal> technicalSignals;
}

class ProjectedChartData {
  const ProjectedChartData({
    required this.range,
    required this.candles,
    this.summary,
    required this.analysis,
  });
  final ChartRange range;
  final List<HistoricalCandle> candles;
  final ChartRangeSummary? summary;
  final ProjectedChartAnalysis analysis;
}

class ReplayWindow {
  const ReplayWindow({
    required this.visibleCandles,
    required this.totalCandles,
    required this.replayOffset,
  });
  final List<HistoricalCandle> visibleCandles;
  final int totalCandles;
  final int replayOffset;
  int get visibleCount => visibleCandles.length;
  bool get isLive => replayOffset == 0;
}

class PricingCandle {
  const PricingCandle({
    required this.symbol,
    required this.range,
    required this.candle,
  });
  final String symbol;
  final ChartRange range;
  final HistoricalCandle candle;
}
