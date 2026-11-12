import type { AssetType, OpportunityRow, SavedUniverseRow } from "./api";

export function visibleRestoredUniverseRows(
  saved: readonly SavedUniverseRow[],
  session: readonly Pick<OpportunityRow, "symbol" | "market_price_cents">[],
  filter: string,
  assetFilter: AssetType | "all",
): SavedUniverseRow[] {
  const sessionSymbols = new Set(
    session.filter((row) => row.market_price_cents > 0).map((row) => row.symbol),
  );
  const query = filter.trim().toLowerCase();
  return saved.filter((row) =>
    !sessionSymbols.has(row.symbol) &&
    (assetFilter === "all" || row.asset_type === assetFilter) &&
    (row.symbol.toLowerCase().includes(query) || row.company_name?.toLowerCase().includes(query))
  );
}
