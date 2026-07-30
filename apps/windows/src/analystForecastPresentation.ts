import type { ForecastObservation } from "./api";
import type { Lang } from "./i18n";

export type ForecastSortKey =
  | "analyst"
  | "issued"
  | "rating"
  | "stars"
  | "rank"
  | "weight"
  | "target"
  | "atPublication"
  | "horizon";

export type SortDir = "asc" | "desc";

export const DEFAULT_FORECAST_SORT_KEY: ForecastSortKey = "weight";

export function formatProviderDate(epochSeconds: number, lang: Lang): string {
  return new Intl.DateTimeFormat(lang === "es" ? "es-AR" : "en-US", {
    year: "numeric",
    month: "numeric",
    day: "numeric",
    timeZone: "UTC",
  }).format(new Date(epochSeconds * 1000));
}

/** First click on a column uses this direction (rank/name lower-first). */
export function defaultSortDir(key: ForecastSortKey): SortDir {
  if (key === "rank" || key === "analyst" || key === "rating") return "asc";
  return "desc";
}

export function nextForecastSort(
  currentKey: ForecastSortKey,
  currentDir: SortDir,
  nextKey: ForecastSortKey,
): { key: ForecastSortKey; dir: SortDir } {
  if (currentKey === nextKey) {
    return { key: currentKey, dir: currentDir === "asc" ? "desc" : "asc" };
  }
  return { key: nextKey, dir: defaultSortDir(nextKey) };
}

export function observationDisplayName(item: ForecastObservation): string {
  return item.analyst ?? item.firm ?? "";
}

/**
 * Sort analyst rows for the TipRanks table.
 * Default product order is weight desc (score/peso). Null numeric values always sort last.
 */
export function sortForecastObservations(
  rows: ForecastObservation[],
  key: ForecastSortKey,
  dir: SortDir,
): ForecastObservation[] {
  var copy = rows.slice();
  copy.sort((a, b) => {
    var primary = compareByKey(a, b, key, dir);
    if (primary !== 0) return primary;
    // Tie-break: higher weight first, then name.
    var weight = compareNullableNumber(
      a.weight_hundredths,
      b.weight_hundredths,
      "desc",
    );
    if (weight !== 0) return weight;
    return observationDisplayName(a).localeCompare(
      observationDisplayName(b),
      undefined,
      { sensitivity: "base" },
    );
  });
  return copy;
}

function compareByKey(
  a: ForecastObservation,
  b: ForecastObservation,
  key: ForecastSortKey,
  dir: SortDir,
): number {
  switch (key) {
    case "analyst":
      return compareString(observationDisplayName(a), observationDisplayName(b), dir);
    case "issued":
      return compareNumber(a.issued_at_epoch, b.issued_at_epoch, dir);
    case "rating":
      return compareString(a.rating ?? "", b.rating ?? "", dir);
    case "stars":
      return compareNullableNumber(a.stars_hundredths, b.stars_hundredths, dir);
    case "rank":
      return compareNullableNumber(a.rank, b.rank, dir);
    case "weight":
      return compareNullableNumber(a.weight_hundredths, b.weight_hundredths, dir);
    case "target":
      return compareNumber(a.target_cents, b.target_cents, dir);
    case "atPublication":
      return compareNullableNumber(
        a.price_when_posted_cents,
        b.price_when_posted_cents,
        dir,
      );
    case "horizon":
      return compareNumber(a.horizon_epoch, b.horizon_epoch, dir);
    default:
      return 0;
  }
}

function compareNumber(a: number, b: number, dir: SortDir): number {
  var cmp = a < b ? -1 : a > b ? 1 : 0;
  return dir === "asc" ? cmp : -cmp;
}

/** Nulls always last, independent of direction. */
function compareNullableNumber(
  a: number | null | undefined,
  b: number | null | undefined,
  dir: SortDir,
): number {
  if (a == null && b == null) return 0;
  if (a == null) return 1;
  if (b == null) return -1;
  return compareNumber(a, b, dir);
}

function compareString(a: string, b: string, dir: SortDir): number {
  if (!a && !b) return 0;
  if (!a) return 1;
  if (!b) return -1;
  var cmp = a.localeCompare(b, undefined, { sensitivity: "base" });
  return dir === "asc" ? cmp : -cmp;
}
