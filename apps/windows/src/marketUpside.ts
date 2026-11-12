import type { OpportunityRow } from "./api";
import { usableQuoteCents } from "./portfolioExposure.ts";

type SessionRow = Pick<OpportunityRow, "symbol" | "company_name" | "market_price_cents" | "intrinsic_value_cents">;

export interface MarketUpsideRow {
  symbol: string;
  companyName: string | null;
  priceCents: number | null;
  targetCents: number | null;
  upsideBps: number | null;
}

export function marketUpsideBps(priceCents: number | null, targetCents: number | null): number | null {
  const price = usableQuoteCents(priceCents);
  const target = usableQuoteCents(targetCents);
  if (price === null || target === null) return null;

  const basisPoints = Number((BigInt(target) - BigInt(price)) * 10_000n / BigInt(price));
  return Number.isSafeInteger(basisPoints) ? basisPoints : null;
}

function compareUpside(a: MarketUpsideRow, b: MarketUpsideRow): number {
  if (a.upsideBps !== b.upsideBps) {
    if (a.upsideBps === null) return 1;
    if (b.upsideBps === null) return -1;
    return b.upsideBps - a.upsideBps;
  }
  const priceOrder = Number(a.priceCents === null) - Number(b.priceCents === null);
  if (priceOrder !== 0) return priceOrder;
  return a.symbol < b.symbol ? -1 : a.symbol > b.symbol ? 1 : 0;
}

export function projectMarketUpside(
  symbols: readonly string[],
  sessionRows: readonly SessionRow[],
  filter: string,
): MarketUpsideRow[] {
  const bySymbol = new Map(sessionRows.map((row) => [row.symbol, row]));
  const query = filter.trim().toLowerCase();
  return symbols.flatMap((symbol) => {
    const row = bySymbol.get(symbol);
    const companyName = row?.company_name ?? null;
    if (query && !symbol.toLowerCase().includes(query) && !companyName?.toLowerCase().includes(query)) return [];

    const priceCents = usableQuoteCents(row?.market_price_cents);
    const targetCents = usableQuoteCents(row?.intrinsic_value_cents);
    return [{ symbol, companyName, priceCents, targetCents, upsideBps: marketUpsideBps(priceCents, targetCents) }];
  }).sort(compareUpside);
}
