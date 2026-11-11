type ExposureLot = {
  symbol: string;
  quantity: number;
  avg_cost_cents: number | null;
};

export type ExposureCoverage = {
  kind: "complete" | "partial" | "unavailable" | "outOfRange";
  eligibleLots: number;
  totalLots: number;
  rangeExcludedLots: number;
};

type LotAmount =
  | { kind: "available"; cents: number }
  | { kind: "unavailable" | "outOfRange"; cents: null };

export function usableQuoteCents(price: number | null | undefined): number | null {
  return price != null && Number.isSafeInteger(price) && price > 0 ? price : null;
}

function roundedLotAmount(quantity: number, unitCents: number | null): LotAmount {
  if (!Number.isFinite(quantity) || quantity <= 0 || unitCents == null || !Number.isInteger(unitCents) || unitCents < 0) {
    return { kind: "unavailable", cents: null };
  }
  const cents = Math.round(quantity * unitCents);
  return Number.isSafeInteger(unitCents) && Number.isSafeInteger(cents)
    ? { kind: "available", cents }
    : { kind: "outOfRange", cents: null };
}

function summarizeAmount(sum: bigint, eligibleLots: number, totalLots: number, rangeExcludedLots: number) {
  const cents = Number(sum);
  let kind: ExposureCoverage["kind"];
  if (eligibleLots === 0) {
    kind = "unavailable";
  } else if (!Number.isSafeInteger(cents)) {
    kind = "outOfRange";
  } else {
    kind = eligibleLots === totalLots ? "complete" : "partial";
  }
  return {
    cents: kind === "complete" || kind === "partial" ? cents : null,
    coverage: { kind, eligibleLots, totalLots, rangeExcludedLots },
  };
}

export function projectPortfolioExposure(
  positions: readonly ExposureLot[],
  screenerRows: ReadonlyMap<string, { market_price_cents: number | null }>,
  extraPrices: Readonly<Record<string, number | null>>,
) {
  // Sum rounded cents exactly, including large gains and losses that offset each other.
  let totalValue = 0n;
  let totalPnl = 0n;
  let pairedCost = 0n;
  let valueLots = 0;
  let pairedLots = 0;
  let valueRangeExcluded = 0;
  let pairedRangeExcluded = 0;
  const exposureRows = positions.map((lot) => {
    const priceCents = usableQuoteCents(screenerRows.get(lot.symbol)?.market_price_cents)
      ?? usableQuoteCents(extraPrices[lot.symbol]);
    const value = roundedLotAmount(lot.quantity, priceCents);
    const unitCost = lot.avg_cost_cents;
    const unitCostCents = unitCost != null && unitCost >= 0 && Number.isSafeInteger(unitCost) ? unitCost : null;
    const cost = roundedLotAmount(lot.quantity, unitCost);
    const valueCents = value.cents;
    const costCents = cost.cents;
    const valueOutOfRange = value.kind === "outOfRange";
    const pnlOutOfRange = value.kind !== "unavailable" && cost.kind !== "unavailable"
      && (valueOutOfRange || cost.kind === "outOfRange");
    if (valueOutOfRange) valueRangeExcluded += 1;
    if (pnlOutOfRange) pairedRangeExcluded += 1;
    if (valueCents != null) {
      totalValue += BigInt(valueCents);
      valueLots += 1;
    }
    let pnlCents: number | null = null;
    let pnlPct: number | null = null;
    if (valueCents != null && costCents != null) {
      pnlCents = valueCents - costCents;
      pnlPct = costCents > 0 ? (pnlCents / costCents) * 100 : null;
      totalPnl += BigInt(pnlCents);
      pairedCost += BigInt(costCents);
      pairedLots += 1;
    }
    return { priceCents, unitCostCents, valueCents, valueOutOfRange, pnlCents, pnlPct, pnlOutOfRange };
  });

  const value = summarizeAmount(totalValue, valueLots, positions.length, valueRangeExcluded);
  const pnl = summarizeAmount(totalPnl, pairedLots, positions.length, pairedRangeExcluded);
  const completeValueCents = value.coverage.kind === "complete" && value.cents != null && value.cents > 0
    ? value.cents
    : null;

  return {
    value,
    pnl: { ...pnl, pct: pnl.cents != null && pairedCost > 0n ? (pnl.cents / Number(pairedCost)) * 100 : null },
    completeValueCents,
    rows: exposureRows.map((row) => ({
      ...row,
      weightPct: row.valueCents != null && completeValueCents != null
        ? (row.valueCents / completeValueCents) * 100
        : null,
    })),
  };
}
