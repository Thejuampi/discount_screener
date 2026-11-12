export function filterAndroidLongV3<T extends { symbol: string; company: string | null }>(
  rows: readonly T[],
  filter: string,
): readonly T[] {
  const query = filter.trim().toLowerCase();
  if (!query) return rows;
  return rows.filter((row) =>
    row.symbol.toLowerCase().includes(query) || row.company?.toLowerCase().includes(query)
  );
}
