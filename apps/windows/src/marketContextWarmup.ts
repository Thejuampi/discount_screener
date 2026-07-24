export async function warmMarketContext(
  getMarketRegime: () => Promise<unknown>,
): Promise<void> {
  try {
    await getMarketRegime();
  } catch {
    // Best effort: opportunity rows expose Unavailable until a later refresh succeeds.
  }
}
