import 'package:ds_core/ds_core.dart';

String moneyFromCents(int? cents) {
  if (cents == null) return '—';
  final dollars = cents / 100.0;
  if (dollars.abs() >= 1000) {
    return '\$${(dollars / 1000).toStringAsFixed(2)}K';
  }
  return '\$${dollars.toStringAsFixed(2)}';
}

String compactDollars(int? dollars) {
  if (dollars == null) return '—';
  final abs = dollars.abs();
  final sign = dollars < 0 ? '-' : '';
  if (abs >= 1000000000000) {
    return '$sign${(abs / 1000000000000).toStringAsFixed(2)}T';
  }
  if (abs >= 1000000000) {
    return '$sign${(abs / 1000000000).toStringAsFixed(2)}B';
  }
  if (abs >= 1000000) {
    return '$sign${(abs / 1000000).toStringAsFixed(2)}M';
  }
  if (abs >= 1000) {
    return '$sign${(abs / 1000).toStringAsFixed(1)}K';
  }
  return '$sign$abs';
}

String pctFromBps(int? bps) {
  if (bps == null) return '—';
  final sign = bps > 0 ? '+' : '';
  return '$sign${(bps / 100.0).toStringAsFixed(1)}%';
}

String ratioFromHundredths(int? hundredths) {
  if (hundredths == null) return '—';
  return (hundredths / 100.0).toStringAsFixed(2);
}

String confidenceLabel(String wire) => wire;

String scoringModelLabel(String name) {
  switch (name) {
    case 'legacy':
      return 'Legacy';
    case 'aggressive':
      return 'Aggressive';
    case 'aggressiveV2':
      return 'Aggressive V2';
    case 'aggressiveV3':
      return 'Aggressive V3';
    default:
      return name;
  }
}

/// Matches Android `formatOpportunityBucket` continuous vs legacy display.
String formatOpportunityBucket(int? score, OpportunityScoringModel model) {
  if (score == null) return '—';
  final continuous = model == OpportunityScoringModel.aggressiveV2 ||
      model == OpportunityScoringModel.aggressiveV3;
  return continuous ? '$score' : '$score';
}
