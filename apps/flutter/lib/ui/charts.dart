import 'dart:math' as math;

import 'package:ds_core/ds_core.dart';
import 'package:flutter/material.dart';

class PriceChart extends StatelessWidget {
  const PriceChart({super.key, required this.data, this.height = 200});

  final ProjectedChartData? data;
  final double height;

  @override
  Widget build(BuildContext context) {
    final analysis = data?.analysis;
    final candles = analysis?.replayWindow.visibleCandles ?? const [];
    if (candles.isEmpty) {
      return SizedBox(
        height: height,
        child: const Card(
          child: Center(child: Text('No chart data yet')),
        ),
      );
    }
    return Card(
      child: SizedBox(
        height: height,
        child: CustomPaint(
          painter: CandleChartPainter(
            candles: candles,
            price: analysis?.price,
            volumeProfile: analysis?.volumeProfile,
            brightness: Theme.of(context).brightness,
          ),
          child: const SizedBox.expand(),
        ),
      ),
    );
  }
}

class VolumeChart extends StatelessWidget {
  const VolumeChart({super.key, required this.data, this.height = 72});

  final ProjectedChartData? data;
  final double height;

  @override
  Widget build(BuildContext context) {
    final candles =
        data?.analysis.replayWindow.visibleCandles ?? const <HistoricalCandle>[];
    final maxVol = data?.analysis.volume?.maxVolume ?? 1;
    if (candles.isEmpty) {
      return SizedBox(height: height, child: const SizedBox.shrink());
    }
    return Card(
      child: SizedBox(
        height: height,
        child: CustomPaint(
          painter: VolumeBarsPainter(candles: candles, maxVolume: maxVol),
          child: const SizedBox.expand(),
        ),
      ),
    );
  }
}

class MacdChart extends StatelessWidget {
  const MacdChart({super.key, required this.data, this.height = 90});

  final ProjectedChartData? data;
  final double height;

  @override
  Widget build(BuildContext context) {
    final macd = data?.analysis.macd;
    if (macd == null) {
      return Card(
        child: SizedBox(
          height: height,
          child: const Center(child: Text('MACD needs ≥26 candles')),
        ),
      );
    }
    return Card(
      child: SizedBox(
        height: height,
        child: CustomPaint(
          painter: MacdPainter(macd: macd),
          child: const SizedBox.expand(),
        ),
      ),
    );
  }
}

class RsiChart extends StatelessWidget {
  const RsiChart({super.key, required this.data, this.height = 90});

  final ProjectedChartData? data;
  final double height;

  @override
  Widget build(BuildContext context) {
    final rsi = data?.analysis.rsi;
    if (rsi == null) {
      return Card(
        child: SizedBox(
          height: height,
          child: const Center(child: Text('RSI unavailable')),
        ),
      );
    }
    return Card(
      child: SizedBox(
        height: height,
        child: CustomPaint(
          painter: RsiPainter(rsi: rsi),
          child: const SizedBox.expand(),
        ),
      ),
    );
  }
}

class CandleChartPainter extends CustomPainter {
  CandleChartPainter({
    required this.candles,
    required this.price,
    required this.volumeProfile,
    required this.brightness,
  });

  final List<HistoricalCandle> candles;
  final ProjectedPriceChartAnalysis? price;
  final ProjectedVolumeProfileAnalysis? volumeProfile;
  final Brightness brightness;

  @override
  void paint(Canvas canvas, Size size) {
    if (candles.isEmpty) return;
    final minV = price?.domain.minValue ??
        candles.map((c) => c.lowCents.toDouble()).reduce(math.min);
    final maxV = price?.domain.maxValue ??
        candles.map((c) => c.highCents.toDouble()).reduce(math.max);
    final span = math.max(1.0, maxV - minV);
    final profileWidth = size.width * 0.12;
    final chartWidth = size.width - profileWidth - 8;
    final slot = chartWidth / candles.length;

    double yOf(double cents) =>
        size.height - ((cents - minV) / span) * size.height;

    // Volume profile (right side).
    if (volumeProfile != null && volumeProfile!.bins.isNotEmpty) {
      final bins = volumeProfile!.bins;
      final maxBin = math.max(1, volumeProfile!.maxBinVolume);
      final binH = size.height / bins.length;
      for (var i = 0; i < bins.length; i++) {
        final bin = bins[i];
        final w = (bin.totalVolume / maxBin) * profileWidth;
        final x = size.width - w;
        final y = i * binH;
        canvas.drawRect(
          Rect.fromLTWH(x, y, w, binH - 1),
          Paint()..color = const Color(0x5564B5F6),
        );
        if (bin.upVolume > 0) {
          final uw = (bin.upVolume / maxBin) * profileWidth;
          canvas.drawRect(
            Rect.fromLTWH(size.width - uw, y, uw, binH - 1),
            Paint()..color = const Color(0x5566BB6A),
          );
        }
      }
    }

    // EMAs
    void drawEma(List<double> series, Color color) {
      if (series.length < 2) return;
      final path = Path();
      for (var i = 0; i < series.length && i < candles.length; i++) {
        final x = i * slot + slot / 2;
        final y = yOf(series[i]);
        if (i == 0) {
          path.moveTo(x, y);
        } else {
          path.lineTo(x, y);
        }
      }
      canvas.drawPath(
        path,
        Paint()
          ..color = color
          ..style = PaintingStyle.stroke
          ..strokeWidth = 1.2,
      );
    }

    if (price != null) {
      drawEma(price!.ema20, const Color(0xFFFFB74D));
      drawEma(price!.ema50, const Color(0xFF64B5F6));
      drawEma(price!.ema200, const Color(0xFFBA68C8));
    }

    // Candles
    for (var i = 0; i < candles.length; i++) {
      final c = candles[i];
      final x = i * slot + slot / 2;
      final up = c.closeCents >= c.openCents;
      final color = up ? const Color(0xFF66BB6A) : const Color(0xFFEF5350);
      final paint = Paint()
        ..color = color
        ..strokeWidth = 1;
      canvas.drawLine(
        Offset(x, yOf(c.highCents.toDouble())),
        Offset(x, yOf(c.lowCents.toDouble())),
        paint,
      );
      final bodyTop = yOf(math.max(c.openCents, c.closeCents).toDouble());
      final bodyBottom = yOf(math.min(c.openCents, c.closeCents).toDouble());
      final bodyH = math.max(1.0, bodyBottom - bodyTop);
      canvas.drawRect(
        Rect.fromCenter(
          center: Offset(x, bodyTop + bodyH / 2),
          width: math.max(2.0, slot * 0.55),
          height: bodyH,
        ),
        Paint()..color = color,
      );
    }
  }

  @override
  bool shouldRepaint(covariant CandleChartPainter oldDelegate) =>
      oldDelegate.candles != candles ||
      oldDelegate.price != price ||
      oldDelegate.volumeProfile != volumeProfile;
}

class VolumeBarsPainter extends CustomPainter {
  VolumeBarsPainter({required this.candles, required this.maxVolume});

  final List<HistoricalCandle> candles;
  final int maxVolume;

  @override
  void paint(Canvas canvas, Size size) {
    if (candles.isEmpty) return;
    final slot = size.width / candles.length;
    final maxV = math.max(1, maxVolume);
    for (var i = 0; i < candles.length; i++) {
      final c = candles[i];
      final h = (c.volume / maxV) * size.height;
      final up = c.closeCents >= c.openCents;
      canvas.drawRect(
        Rect.fromLTWH(i * slot + 1, size.height - h, math.max(1, slot - 2), h),
        Paint()
          ..color = up
              ? const Color(0x8866BB6A)
              : const Color(0x88EF5350),
      );
    }
  }

  @override
  bool shouldRepaint(covariant VolumeBarsPainter oldDelegate) =>
      oldDelegate.candles != candles || oldDelegate.maxVolume != maxVolume;
}

class MacdPainter extends CustomPainter {
  MacdPainter({required this.macd});
  final ProjectedMacdChartAnalysis macd;

  @override
  void paint(Canvas canvas, Size size) {
    final values = [
      ...macd.macdLine,
      ...macd.signalLine,
      ...macd.histogram,
    ];
    if (values.isEmpty) return;
    final minV = values.reduce(math.min);
    final maxV = values.reduce(math.max);
    final span = math.max(1.0, maxV - minV);
    double yOf(double v) => size.height - ((v - minV) / span) * size.height;
    final n = macd.macdLine.length;
    if (n == 0) return;
    final slot = size.width / n;

    // Histogram
    for (var i = 0; i < macd.histogram.length; i++) {
      final h = macd.histogram[i];
      final zeroY = yOf(0);
      final y = yOf(h);
      canvas.drawRect(
        Rect.fromLTRB(
          i * slot + 1,
          math.min(zeroY, y),
          i * slot + slot - 1,
          math.max(zeroY, y),
        ),
        Paint()
          ..color = h >= 0
              ? const Color(0x8866BB6A)
              : const Color(0x88EF5350),
      );
    }

    Path linePath(List<double> series) {
      final path = Path();
      for (var i = 0; i < series.length; i++) {
        final x = i * slot + slot / 2;
        final y = yOf(series[i]);
        if (i == 0) {
          path.moveTo(x, y);
        } else {
          path.lineTo(x, y);
        }
      }
      return path;
    }

    canvas.drawPath(
      linePath(macd.macdLine),
      Paint()
        ..color = const Color(0xFF42A5F5)
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1.2,
    );
    canvas.drawPath(
      linePath(macd.signalLine),
      Paint()
        ..color = const Color(0xFFFFA726)
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1.2,
    );
  }

  @override
  bool shouldRepaint(covariant MacdPainter oldDelegate) =>
      oldDelegate.macd != macd;
}

class RsiPainter extends CustomPainter {
  RsiPainter({required this.rsi});
  final ProjectedRsiChartAnalysis rsi;

  @override
  void paint(Canvas canvas, Size size) {
    final series = rsi.signalRsi.isNotEmpty ? rsi.signalRsi : rsi.wilderRsi;
    if (series.isEmpty) return;
    double yOf(double v) => size.height - (v / 100.0) * size.height;
    // Bands 30/70
    final bandPaint = Paint()
      ..color = const Color(0x33888888)
      ..strokeWidth = 1;
    canvas.drawLine(Offset(0, yOf(70)), Offset(size.width, yOf(70)), bandPaint);
    canvas.drawLine(Offset(0, yOf(30)), Offset(size.width, yOf(30)), bandPaint);
    final slot = size.width / series.length;
    final path = Path();
    for (var i = 0; i < series.length; i++) {
      final x = i * slot + slot / 2;
      final y = yOf(series[i].clamp(0.0, 100.0));
      if (i == 0) {
        path.moveTo(x, y);
      } else {
        path.lineTo(x, y);
      }
    }
    canvas.drawPath(
      path,
      Paint()
        ..color = const Color(0xFFAB47BC)
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1.4,
    );
  }

  @override
  bool shouldRepaint(covariant RsiPainter oldDelegate) => oldDelegate.rsi != rsi;
}
