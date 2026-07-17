import 'dart:math' as math;

import 'chart_projection.dart';
import 'models.dart';

/// Port of Android `ChartAnalysis` — EMA/MACD/RSI, volume profile, replay windows.
class ChartAnalysis {
  static ProjectedChartData buildProjectedChartData({
    required ChartRange range,
    required List<HistoricalCandle> candles,
    required int capturedAtEpochSeconds,
    required int replayOffset,
    required int volumeProfileBinCount,
    ChartRangeSummary? summary,
  }) {
    final chartSummary = summary ??
        (candles.isEmpty
            ? null
            : buildSummary(
                range: range,
                candles: candles,
                capturedAtEpochSeconds: capturedAtEpochSeconds,
              ));
    final replayWindow = buildReplayWindow(candles, replayOffset);
    return ProjectedChartData(
      range: range,
      candles: candles,
      summary: chartSummary,
      analysis: _buildProjectedAnalysis(
        replayWindow: replayWindow,
        summary: chartSummary,
        volumeProfileBinCount: volumeProfileBinCount,
      ),
    );
  }

  static ChartRangeSummary buildSummary({
    required ChartRange range,
    required List<HistoricalCandle> candles,
    required int capturedAtEpochSeconds,
  }) {
    final closes = candles.map((c) => c.closeCents.toDouble()).toList();
    final latest = closes.isEmpty ? null : closes.last.round();
    final ema20 = ema(closes, 20);
    final ema50 = ema(closes, 50);
    final ema200 = ema(closes, 200);
    final macdVals = macd(closes);
    final rsi = rsiAnalysis(candles);
    return ChartRangeSummary(
      range: range,
      capturedAt: capturedAtEpochSeconds,
      candleCount: candles.length,
      latestCloseCents: latest,
      ema20Cents: ema20.isEmpty ? null : ema20.last.round(),
      ema50Cents: ema50.isEmpty ? null : ema50.last.round(),
      ema200Cents: ema200.isEmpty ? null : ema200.last.round(),
      macdCents: macdVals.macd.isEmpty ? null : macdVals.macd.last.round(),
      signalCents: macdVals.signal.isEmpty ? null : macdVals.signal.last.round(),
      histogramCents:
          macdVals.histogram.isEmpty ? null : macdVals.histogram.last.round(),
      latestWilderRsi: rsi?.latestWilderRsi,
      latestRsiSlope: rsi?.latestSlope,
      volumeRatioHundredths: volumeRatioHundredths(candles),
    );
  }

  static int? volumeRatioHundredths(List<HistoricalCandle> candles) {
    if (candles.isEmpty) return null;
    final volumes = candles.map((c) => math.max(0, c.volume)).toList();
    final latest = volumes.last;
    if (volumes.every((v) => v == 0)) return null;
    final sorted = [...volumes]..sort();
    final median = sorted.length.isEven
        ? (sorted[sorted.length ~/ 2 - 1] + sorted[sorted.length ~/ 2]) / 2.0
        : sorted[sorted.length ~/ 2].toDouble();
    if (median <= 0) return null;
    return ((latest / median) * 100.0).round().clamp(0, 10000);
  }

  static ReplayWindow buildReplayWindow(
    List<HistoricalCandle> candles,
    int replayOffset,
  ) {
    if (candles.isEmpty) {
      return const ReplayWindow(
        visibleCandles: [],
        totalCandles: 0,
        replayOffset: 0,
      );
    }
    final clampedOffset = replayOffset.clamp(0, candles.length - 1);
    final visibleEnd = math.max(1, candles.length - clampedOffset);
    return ReplayWindow(
      visibleCandles: candles.sublist(0, visibleEnd),
      totalCandles: candles.length,
      replayOffset: clampedOffset,
    );
  }

  static int stepReplayBack(int replayOffset, int totalCandles) {
    final maxOffset = math.max(0, totalCandles - 1);
    return (replayOffset + 1).clamp(0, maxOffset);
  }

  static int stepReplayForward(int replayOffset) =>
      math.max(0, replayOffset - 1);

  static List<ProjectedVolumeProfileBin> computeVolumeProfile({
    required List<HistoricalCandle> candles,
    required int minPriceCents,
    required int maxPriceCents,
    required int binCount,
  }) {
    final count = math.max(0, binCount);
    final up = List<int>.filled(count, 0);
    final down = List<int>.filled(count, 0);
    if (count == 0 || minPriceCents >= maxPriceCents) {
      return List.generate(
        count,
        (i) => ProjectedVolumeProfileBin(upVolume: up[i], downVolume: down[i]),
      );
    }
    final range = (maxPriceCents - minPriceCents).toDouble();
    final lastBin = count - 1;
    for (final candle in candles) {
      final low = math.max(candle.lowCents, minPriceCents);
      final high = math.min(candle.highCents, maxPriceCents);
      if (low > high) continue;
      final lowBin =
          (((low - minPriceCents) / range) * lastBin).round().clamp(0, lastBin);
      final highBin =
          (((high - minPriceCents) / range) * lastBin).round().clamp(0, lastBin);
      final span = math.max(1, highBin - lowBin + 1);
      final volume = math.max(0, candle.volume);
      final perBin = volume ~/ span;
      final remainder = volume % span;
      final isUp = candle.closeCents >= candle.openCents;
      var index = 0;
      for (var priceBin = lowBin; priceBin <= highBin; priceBin++) {
        final row = lastBin - priceBin;
        final binVolume = perBin + (index < remainder ? 1 : 0);
        if (isUp) {
          up[row] += binVolume;
        } else {
          down[row] += binVolume;
        }
        index++;
      }
    }
    return List.generate(
      count,
      (i) => ProjectedVolumeProfileBin(upVolume: up[i], downVolume: down[i]),
    );
  }

  static ProjectedVolumeProfileAnalysis buildVolumeProfileAnalysis({
    required List<HistoricalCandle> candles,
    required int minPriceCents,
    required int maxPriceCents,
    required int binCount,
  }) {
    final bins = computeVolumeProfile(
      candles: candles,
      minPriceCents: minPriceCents,
      maxPriceCents: maxPriceCents,
      binCount: binCount,
    );
    final maxVol =
        bins.isEmpty ? 0 : bins.map((b) => b.totalVolume).reduce(math.max);
    return ProjectedVolumeProfileAnalysis(bins: bins, maxBinVolume: maxVol);
  }

  static List<double> ema(List<double> values, int period) {
    if (values.isEmpty) return const [];
    final multiplier = 2.0 / (period + 1);
    final output = <double>[];
    var previous = values.first;
    for (var i = 0; i < values.length; i++) {
      previous =
          i == 0 ? values[i] : ((values[i] - previous) * multiplier) + previous;
      output.add(previous);
    }
    return output;
  }

  static ({List<double> macd, List<double> signal, List<double> histogram}) macd(
    List<double> values,
  ) {
    if (values.isEmpty) {
      return (macd: const [], signal: const [], histogram: const []);
    }
    final fast = ema(values, 12);
    final slow = ema(values, 26);
    final macdLine = <double>[
      for (var i = 0; i < fast.length; i++) fast[i] - slow[i],
    ];
    final signalLine = ema(macdLine, 9);
    final histogram = <double>[
      for (var i = 0; i < macdLine.length; i++) macdLine[i] - signalLine[i],
    ];
    return (macd: macdLine, signal: signalLine, histogram: histogram);
  }

  static ProjectedRsiChartAnalysis? rsiAnalysis(List<HistoricalCandle> candles) {
    if (candles.length < 2) return null;
    final closes = candles.map((c) => c.closeCents.toDouble()).toList();
    final volumes =
        candles.map((c) => math.max(0, c.volume).toDouble()).toList();
    final wilder = rsiWilder(closes, period: 14);
    final volumeWeighted =
        rsiWeightedByRelativeVolume(closes, volumes, period: 14);
    final signal = <double>[
      for (var i = 0; i < wilder.length; i++)
        ((volumeWeighted[i] * 0.65) + (wilder[i] * 0.35)).clamp(0.0, 100.0),
    ];
    final smoothedSignal =
        ema(signal, 5).map((v) => v.clamp(0.0, 100.0)).toList();
    final slope = derivative(smoothedSignal);
    final acceleration = derivative(slope);
    return ProjectedRsiChartAnalysis(
      wilderRsi: wilder,
      signalRsi: smoothedSignal,
      slope: slope,
      acceleration: acceleration,
      latestWilderRsi: wilder.isEmpty ? null : wilder.last,
      latestSignalRsi: smoothedSignal.isEmpty ? null : smoothedSignal.last,
      latestSlope: slope.isEmpty ? null : slope.last,
      latestAcceleration: acceleration.isEmpty ? null : acceleration.last,
    );
  }

  static List<double> rsiWilder(List<double> values, {required int period}) {
    if (values.isEmpty) return const [];
    if (values.length == 1) return const [50.0];
    final deltas = <double>[0.0];
    for (var i = 1; i < values.length; i++) {
      deltas.add(values[i] - values[i - 1]);
    }
    final gains = deltas.map((d) => math.max(d, 0.0)).toList();
    final losses = deltas.map((d) => math.max(-d, 0.0)).toList();
    final avgGains = wilderAverage(gains, period);
    final avgLosses = wilderAverage(losses, period);
    return [
      for (var i = 0; i < avgGains.length; i++)
        _rsiFromAverages(avgGains[i], avgLosses[i]),
    ];
  }

  static List<double> rsiWeightedByRelativeVolume(
    List<double> values,
    List<double> volumes, {
    required int period,
  }) {
    if (values.isEmpty) return const [];
    final relativeVolume = <double>[];
    for (var index = 0; index < volumes.length; index++) {
      final from = math.max(0, index - period + 1);
      final window = volumes.sublist(from, index + 1);
      final baseline = window.isEmpty
          ? 1.0
          : window.reduce((a, b) => a + b) / window.length;
      final base = baseline > 0 ? baseline : 1.0;
      relativeVolume.add((volumes[index] / base).clamp(0.5, 2.0));
    }
    final deltas = <double>[0.0];
    for (var i = 1; i < values.length; i++) {
      deltas.add(values[i] - values[i - 1]);
    }
    final weightedGains = <double>[
      for (var i = 0; i < deltas.length; i++)
        math.max(deltas[i], 0.0) * relativeVolume[i],
    ];
    final weightedLosses = <double>[
      for (var i = 0; i < deltas.length; i++)
        math.max(-deltas[i], 0.0) * relativeVolume[i],
    ];
    final avgGains = wilderAverage(weightedGains, period);
    final avgLosses = wilderAverage(weightedLosses, period);
    return [
      for (var i = 0; i < avgGains.length; i++)
        _rsiFromAverages(avgGains[i], avgLosses[i]),
    ];
  }

  static double _rsiFromAverages(double avgGain, double avgLoss) {
    if (avgGain == 0.0 && avgLoss == 0.0) return 50.0;
    if (avgLoss == 0.0) return 100.0;
    final rs = avgGain / avgLoss;
    return 100.0 - (100.0 / (1.0 + rs));
  }

  static List<double> wilderAverage(List<double> values, int period) {
    if (values.isEmpty) return const [];
    final output = <double>[];
    var previous = values.first;
    for (var index = 0; index < values.length; index++) {
      final value = values[index];
      if (index == 0) {
        previous = value;
      } else if (index < period) {
        previous = ((previous * index) + value) / (index + 1);
      } else {
        previous = ((previous * (period - 1)) + value) / period;
      }
      output.add(previous);
    }
    return output;
  }

  static List<double> derivative(List<double> values) {
    if (values.isEmpty) return const [];
    final output = List<double>.filled(values.length, 0.0);
    for (var index = 1; index < values.length; index++) {
      output[index] = values[index] - values[index - 1];
    }
    return ema(output, 3);
  }

  static ProjectedChartAnalysis _buildProjectedAnalysis({
    required ReplayWindow replayWindow,
    required ChartRangeSummary? summary,
    required int volumeProfileBinCount,
  }) {
    final visible = replayWindow.visibleCandles;
    final projectedReplay = ProjectedReplayWindow(
      visibleCandles: visible,
      totalCandles: replayWindow.totalCandles,
      replayOffset: replayWindow.replayOffset,
    );
    if (visible.isEmpty) {
      return ProjectedChartAnalysis(
        status: summary == null
            ? ProjectedChartStatus.unavailable
            : ProjectedChartStatus.summaryOnly,
        replayWindow: projectedReplay,
      );
    }

    final closes = visible.map((c) => c.closeCents.toDouble()).toList();
    final ema20 = ema(closes, 20);
    final ema50 = ema(closes, 50);
    final ema200 = ema(closes, 200);
    final price = _priceAnalysis(visible, ema20, ema50, ema200);
    final macdA = _macdAnalysis(visible, closes);
    final rsi = rsiAnalysis(visible);
    final maxVol = visible.map((c) => c.volume).fold<int>(1, math.max);
    final volume = ProjectedVolumeChartAnalysis(maxVolume: math.max(1, maxVol));
    final volumeProfile = buildVolumeProfileAnalysis(
      candles: visible,
      minPriceCents: price.domain.minValue.round(),
      maxPriceCents: price.domain.maxValue.round(),
      binCount: volumeProfileBinCount,
    );
    return ProjectedChartAnalysis(
      status: ProjectedChartStatus.available,
      replayWindow: projectedReplay,
      price: price,
      volume: volume,
      volumeProfile: volumeProfile,
      macd: macdA,
      rsi: rsi,
      technicalSignals: _technicalSignals(ema20, ema50, ema200, macdA, rsi),
    );
  }

  static ProjectedPriceChartAnalysis _priceAnalysis(
    List<HistoricalCandle> candles,
    List<double> ema20,
    List<double> ema50,
    List<double> ema200,
  ) {
    final allValues = <double>[];
    for (final c in candles) {
      allValues
        ..add(c.lowCents.toDouble())
        ..add(c.highCents.toDouble())
        ..add(c.openCents.toDouble())
        ..add(c.closeCents.toDouble());
    }
    allValues
      ..addAll(ema20)
      ..addAll(ema50)
      ..addAll(ema200);
    final minV = allValues.isEmpty ? 0.0 : allValues.reduce(math.min);
    final maxV = allValues.isEmpty ? 0.0 : allValues.reduce(math.max);
    return ProjectedPriceChartAnalysis(
      domain: _paddedPriceDomain(minV, maxV),
      latestCloseCents: candles.isEmpty ? null : candles.last.closeCents,
      ema20: ema20,
      ema50: ema50,
      ema200: ema200,
      latestEma20Cents: ema20.isEmpty ? null : ema20.last.round(),
      latestEma50Cents: ema50.isEmpty ? null : ema50.last.round(),
      latestEma200Cents: ema200.isEmpty ? null : ema200.last.round(),
    );
  }

  static ProjectedMacdChartAnalysis? _macdAnalysis(
    List<HistoricalCandle> candles,
    List<double> closes,
  ) {
    if (candles.length < 26) return null;
    final m = macd(closes);
    return ProjectedMacdChartAnalysis(
      macdLine: m.macd,
      signalLine: m.signal,
      histogram: m.histogram,
      latestMacdCents: m.macd.isEmpty ? null : m.macd.last.round(),
      latestSignalCents: m.signal.isEmpty ? null : m.signal.last.round(),
      latestHistogramCents:
          m.histogram.isEmpty ? null : m.histogram.last.round(),
    );
  }

  static ProjectedPriceDomain _paddedPriceDomain(double min, double max) {
    if (min == max) {
      final padding = math.max(1.0, min.abs() * 0.05);
      return ProjectedPriceDomain(minValue: min - padding, maxValue: max + padding);
    }
    final padding = math.max(1.0, (max - min) * 0.05);
    return ProjectedPriceDomain(minValue: min - padding, maxValue: max + padding);
  }

  static List<ProjectedTechnicalSignal> _technicalSignals(
    List<double> ema20,
    List<double> ema50,
    List<double> ema200,
    ProjectedMacdChartAnalysis? macdA,
    ProjectedRsiChartAnalysis? rsi,
  ) {
    final out = <ProjectedTechnicalSignal>[];
    final s1 = _buildTechnicalSignal(
      ema20,
      ema50,
      ProjectedTechnicalSignalKind.ema20Ema50,
    );
    if (s1 != null) out.add(s1);
    final s2 = _buildTechnicalSignal(
      ema50,
      ema200,
      ProjectedTechnicalSignalKind.ema50Ema200,
    );
    if (s2 != null) out.add(s2);
    if (macdA != null) {
      final s3 = _buildTechnicalSignal(
        macdA.macdLine,
        macdA.signalLine,
        ProjectedTechnicalSignalKind.macdSignal,
      );
      if (s3 != null) out.add(s3);
    }
    if (rsi != null) {
      final m = _buildRsiMomentumSignal(rsi);
      if (m != null) out.add(m);
      final i = _buildRsiInflectionSignal(rsi);
      if (i != null) out.add(i);
    }
    return out;
  }

  static ProjectedTechnicalSignal? _buildTechnicalSignal(
    List<double> fast,
    List<double> slow,
    ProjectedTechnicalSignalKind kind,
  ) {
    final latestIndex = math.min(fast.length, slow.length) - 1;
    if (latestIndex < 1) return null;
    final latestDiff = fast[latestIndex] - slow[latestIndex];
    if (latestDiff == 0.0) return null;
    final previousDiff = fast[latestIndex - 1] - slow[latestIndex - 1];
    final bullish = latestDiff > 0.0;
    final freshCross =
        (bullish && previousDiff <= 0.0) || (!bullish && previousDiff >= 0.0);
    final bias = bullish
        ? ProjectedTechnicalSignalBias.bull
        : ProjectedTechnicalSignalBias.bear;
    return ProjectedTechnicalSignal(
      kind: kind,
      title: _technicalSignalTitle(kind, bias, freshCross),
      meaning: _technicalSignalMeaning(kind, bias, freshCross),
      bias: bias,
      freshCross: freshCross,
    );
  }

  static ProjectedTechnicalSignal? _buildRsiMomentumSignal(
    ProjectedRsiChartAnalysis rsi,
  ) {
    final latest = rsi.latestSignalRsi;
    final slope = rsi.latestSlope;
    if (latest == null || slope == null) return null;
    if (latest == 50.0 && slope == 0.0) return null;
    final bias = (latest >= 50.0 && slope >= 0.0)
        ? ProjectedTechnicalSignalBias.bull
        : ProjectedTechnicalSignalBias.bear;
    final priorSlope =
        rsi.slope.length > 1 ? rsi.slope[rsi.slope.length - 2] : 0.0;
    final freshCross =
        (slope >= 0.0 && priorSlope < 0.0) || (slope <= 0.0 && priorSlope > 0.0);
    return ProjectedTechnicalSignal(
      kind: ProjectedTechnicalSignalKind.rsiMomentum,
      title: _technicalSignalTitle(
        ProjectedTechnicalSignalKind.rsiMomentum,
        bias,
        freshCross,
      ),
      meaning: _technicalSignalMeaning(
        ProjectedTechnicalSignalKind.rsiMomentum,
        bias,
        freshCross,
      ),
      bias: bias,
      freshCross: freshCross,
    );
  }

  static ProjectedTechnicalSignal? _buildRsiInflectionSignal(
    ProjectedRsiChartAnalysis rsi,
  ) {
    final latest = rsi.latestAcceleration;
    if (latest == null || latest == 0.0) return null;
    final prior = rsi.acceleration.length > 1
        ? rsi.acceleration[rsi.acceleration.length - 2]
        : 0.0;
    final bias = latest > 0.0
        ? ProjectedTechnicalSignalBias.bull
        : ProjectedTechnicalSignalBias.bear;
    final freshCross =
        (latest > 0.0 && prior <= 0.0) || (latest < 0.0 && prior >= 0.0);
    return ProjectedTechnicalSignal(
      kind: ProjectedTechnicalSignalKind.rsiInflection,
      title: _technicalSignalTitle(
        ProjectedTechnicalSignalKind.rsiInflection,
        bias,
        freshCross,
      ),
      meaning: _technicalSignalMeaning(
        ProjectedTechnicalSignalKind.rsiInflection,
        bias,
        freshCross,
      ),
      bias: bias,
      freshCross: freshCross,
    );
  }

  static String _technicalSignalTitle(
    ProjectedTechnicalSignalKind kind,
    ProjectedTechnicalSignalBias bias,
    bool freshCross,
  ) {
    final label = switch (kind) {
      ProjectedTechnicalSignalKind.ema20Ema50 => 'E20/E50',
      ProjectedTechnicalSignalKind.ema50Ema200 => 'E50/E200',
      ProjectedTechnicalSignalKind.macdSignal => 'MACD',
      ProjectedTechnicalSignalKind.rsiMomentum => 'RSI',
      ProjectedTechnicalSignalKind.rsiInflection => 'RSI inflect',
    };
    if (freshCross && bias == ProjectedTechnicalSignalBias.bull) {
      return 'Bull cross $label';
    }
    if (freshCross) return 'Bear cross $label';
    if (bias == ProjectedTechnicalSignalBias.bull) return 'Bull $label';
    return 'Bear $label';
  }

  static String _technicalSignalMeaning(
    ProjectedTechnicalSignalKind kind,
    ProjectedTechnicalSignalBias bias,
    bool freshCross,
  ) {
    final bull = bias == ProjectedTechnicalSignalBias.bull;
    return switch (kind) {
      ProjectedTechnicalSignalKind.ema20Ema50 => freshCross
          ? (bull
              ? 'Short trend just crossed above medium trend'
              : 'Short trend just crossed below medium trend')
          : (bull
              ? 'Short trend is above medium trend'
              : 'Short trend is below medium trend'),
      ProjectedTechnicalSignalKind.ema50Ema200 => freshCross
          ? (bull
              ? 'Medium trend just crossed above long trend'
              : 'Medium trend just crossed below long trend')
          : (bull
              ? 'Medium trend is above long trend'
              : 'Medium trend is below long trend'),
      ProjectedTechnicalSignalKind.macdSignal => freshCross
          ? (bull
              ? 'Momentum just crossed above the signal line'
              : 'Momentum just crossed below the signal line')
          : (bull
              ? 'Momentum is above the signal line'
              : 'Momentum is below the signal line'),
      ProjectedTechnicalSignalKind.rsiMomentum => freshCross
          ? (bull
              ? 'RSI momentum just turned bullish'
              : 'RSI momentum just turned bearish')
          : (bull
              ? 'RSI momentum remains bullish'
              : 'RSI momentum remains bearish'),
      ProjectedTechnicalSignalKind.rsiInflection => freshCross
          ? (bull
              ? 'RSI acceleration just turned upward'
              : 'RSI acceleration just turned downward')
          : (bull
              ? 'RSI acceleration is improving'
              : 'RSI acceleration is deteriorating'),
    };
  }
}
