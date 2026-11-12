import type { OpportunityRow, PortfolioPosition, SymbolDetail } from "./api";
import { projectPortfolioExposure, type ExposureCoverage } from "./portfolioExposure.ts";

type SessionRow = Pick<OpportunityRow, "symbol" | "market_price_cents">;
type DetailQuote = Pick<SymbolDetail, "detail_source" | "market_price_cents">;

export type DetailLot = ReturnType<typeof projectPortfolioExposure>["rows"][number] & {
  id: number;
  quantity: number;
  openedAt: string | null;
};

export interface DetailPosition {
  lots: DetailLot[];
  quoteCoverage: ExposureCoverage;
  weightAvailable: boolean;
}

function canonicalSymbol(symbol: string): string {
  return symbol.trim().toUpperCase();
}

export function projectDetailPosition(
  symbol: string,
  positions: readonly PortfolioPosition[],
  sessionRows: readonly SessionRow[],
  detail: DetailQuote | null,
): DetailPosition | null {
  const ticker = canonicalSymbol(symbol);
  if (!positions.some((lot) => canonicalSymbol(lot.symbol) === ticker)) return null;

  const book = positions.map((lot) => ({ ...lot, symbol: canonicalSymbol(lot.symbol) }));
  const quotes = new Map(sessionRows.map((row) => [canonicalSymbol(row.symbol), row]));
  const detailPrice = detail?.detail_source === "session" ? detail.market_price_cents : null;
  const exposure = projectPortfolioExposure(book, quotes, { [ticker]: detailPrice });
  const lots = book.flatMap((lot, index) => lot.symbol === ticker ? [{
    ...exposure.rows[index],
    id: lot.id,
    quantity: lot.quantity,
    openedAt: lot.opened_at,
  }] : []);

  return {
    lots,
    quoteCoverage: exposure.value.coverage,
    weightAvailable: exposure.completeValueCents !== null,
  };
}
