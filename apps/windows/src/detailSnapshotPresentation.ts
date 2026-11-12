import type { OpportunityRow, SymbolDetail } from "./api";

type DetailSnapshot = Pick<SymbolDetail, "detail_source" | "saved_at_epoch_seconds" | "asset_type">;
type QuoteRow = Pick<OpportunityRow, "asset_type" | "market_price_cents">;

export function detailSnapshotPresentation<Row extends QuoteRow>(
  detail: DetailSnapshot | null,
  row: Row | null,
) {
  if (detail?.detail_source !== "restored") {
    return {
      restored: false,
      liveRow: row,
      assetType: row?.asset_type ?? null,
      savedAt: null,
      shouldReadSession: false,
    };
  }

  const savedAt = detail.saved_at_epoch_seconds == null
    ? null
    : new Date(detail.saved_at_epoch_seconds * 1000);

  return {
    restored: true,
    liveRow: null,
    assetType: detail.asset_type ?? null,
    savedAt: savedAt && Number.isFinite(savedAt.getTime()) ? savedAt : null,
    shouldReadSession: row != null && row.market_price_cents > 0,
  };
}
