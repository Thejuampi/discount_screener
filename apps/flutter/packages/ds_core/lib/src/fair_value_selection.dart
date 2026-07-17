/// Fair-value anchor selection matching Android `ScreenDataProjectionEngine`.
///
/// Priority (first positive wins):
/// 1. Analyst weighted target
/// 2. Analyst consensus / mean target
/// 3. DCF base intrinsic (rejected when implausible vs market)
/// 4. Existing model intrinsic fallback
class FairValueSelection {
  const FairValueSelection._();

  /// Absolute upside beyond this vs market is treated as unusable for *primary*
  /// fair value (keeps extreme FCF DCFs, e.g. insurers, off Opps/Upside).
  /// 300% = 30_000 bps.
  static const int maxPrimaryAbsUpsideBps = 30000;

  /// Selects the cents value used for list/detail primary fair value & upside.
  static int? selectPrimaryCents({
    int? analystWeightedCents,
    int? analystMeanCents,
    int? dcfBaseCents,
    int? fallbackIntrinsicCents,
    int? marketPriceCents,
  }) {
    if (analystWeightedCents != null && analystWeightedCents > 0) {
      return analystWeightedCents;
    }
    if (analystMeanCents != null && analystMeanCents > 0) {
      return analystMeanCents;
    }
    if (dcfBaseCents != null &&
        dcfBaseCents > 0 &&
        !isImplausibleVsMarket(dcfBaseCents, marketPriceCents)) {
      return dcfBaseCents;
    }
    if (fallbackIntrinsicCents != null &&
        fallbackIntrinsicCents > 0 &&
        !isImplausibleVsMarket(fallbackIntrinsicCents, marketPriceCents)) {
      return fallbackIntrinsicCents;
    }
    // Last resort: market itself (0% upside) so UI never shows fantasy DCFs.
    if (marketPriceCents != null && marketPriceCents > 0) {
      return marketPriceCents;
    }
    if (fallbackIntrinsicCents != null && fallbackIntrinsicCents > 0) {
      return fallbackIntrinsicCents;
    }
    return null;
  }

  /// True when |fair/price - 1| exceeds [maxPrimaryAbsUpsideBps].
  static bool isImplausibleVsMarket(int fairCents, int? marketPriceCents) {
    if (marketPriceCents == null || marketPriceCents <= 0 || fairCents <= 0) {
      return false;
    }
    final absUpsideBps =
        ((fairCents - marketPriceCents).abs() * 10000) ~/ marketPriceCents;
    return absUpsideBps > maxPrimaryAbsUpsideBps;
  }

  /// Label for UI / diagnostics (wire-stable short names).
  static String sourceLabel({
    int? analystWeightedCents,
    int? analystMeanCents,
    int? dcfBaseCents,
    int? fallbackIntrinsicCents,
    int? marketPriceCents,
  }) {
    if (analystWeightedCents != null && analystWeightedCents > 0) {
      return 'Analyst weighted';
    }
    if (analystMeanCents != null && analystMeanCents > 0) {
      return 'Analyst mean';
    }
    if (dcfBaseCents != null &&
        dcfBaseCents > 0 &&
        !isImplausibleVsMarket(dcfBaseCents, marketPriceCents)) {
      return 'DCF base';
    }
    if (fallbackIntrinsicCents != null &&
        fallbackIntrinsicCents > 0 &&
        !isImplausibleVsMarket(fallbackIntrinsicCents, marketPriceCents)) {
      return 'Intrinsic model';
    }
    if (marketPriceCents != null && marketPriceCents > 0) {
      return 'Market price';
    }
    return 'Unavailable';
  }
}
