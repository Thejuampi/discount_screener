export function trackedFeedSymbol(raw: string): string | null {
  const symbol = raw.trim();
  return /^[A-Za-z0-9.^=-]{1,16}$/.test(symbol) ? symbol.toUpperCase() : null;
}
