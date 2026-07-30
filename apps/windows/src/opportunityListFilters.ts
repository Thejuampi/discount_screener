import type { OpportunityRow } from "./api";

/** Client-side threshold filters for the screener opportunity table. */
export interface ListFilterState {
  /** Minimum setup score (-100..+100). */
  minSetupScore: number | null;
  /**
   * Minimum gap / discount to fair value, in percent points (e.g. 15 = 15%).
   * Compared against `gap_bps / 100`. Null gap rows are excluded when active.
   */
  minGapPct: number | null;
  /** Minimum composite score (-100..+100). */
  minCompositeScore: number | null;
}

export const EMPTY_LIST_FILTERS: ListFilterState = {
  minSetupScore: null,
  minGapPct: null,
  minCompositeScore: null,
};

export const LIST_FILTER_STORAGE_KEY = "ds_opportunity_list_filters";

/** Presets for one-click filters (values are applied as mins). */
export const LIST_FILTER_PRESETS = [
  { id: "setup40", minSetupScore: 40, minGapPct: null, minCompositeScore: null },
  { id: "gap15", minSetupScore: null, minGapPct: 15, minCompositeScore: null },
  { id: "gap25", minSetupScore: null, minGapPct: 25, minCompositeScore: null },
  { id: "setup40gap15", minSetupScore: 40, minGapPct: 15, minCompositeScore: null },
] as const;

export type ListFilterPresetId = (typeof LIST_FILTER_PRESETS)[number]["id"];

/** Parse free-text from a header input into a finite number, or null if empty/invalid. */
export function parseFilterNumber(raw: string): number | null {
  var trimmed = raw.trim();
  if (trimmed === "" || trimmed === "-" || trimmed === "." || trimmed === "-.") return null;
  var n = Number(trimmed.replace(",", "."));
  if (!Number.isFinite(n)) return null;
  return n;
}

export function countActiveFilters(filters: ListFilterState): number {
  var n = 0;
  if (filters.minSetupScore != null) n += 1;
  if (filters.minGapPct != null) n += 1;
  if (filters.minCompositeScore != null) n += 1;
  return n;
}

export function hasActiveFilters(filters: ListFilterState): boolean {
  return countActiveFilters(filters) > 0;
}

export function listFiltersEqual(a: ListFilterState, b: ListFilterState): boolean {
  return (
    a.minSetupScore === b.minSetupScore
    && a.minGapPct === b.minGapPct
    && a.minCompositeScore === b.minCompositeScore
  );
}

/** Gap column shows percent; stored field is basis points. */
export function gapBpsFromPct(pct: number): number {
  return Math.round(pct * 100);
}

export function gapPctFromBps(bps: number): number {
  return bps / 100;
}

/**
 * Apply threshold filters. Active mins are inclusive (≥).
 * Rows with null gap are dropped when a gap filter is set.
 */
export function applyListFilters(
  rows: readonly OpportunityRow[],
  filters: ListFilterState,
): OpportunityRow[] {
  if (!hasActiveFilters(filters)) return rows as OpportunityRow[];

  var minGapBps =
    filters.minGapPct != null ? gapBpsFromPct(filters.minGapPct) : null;

  return rows.filter((row) => {
    if (filters.minSetupScore != null && row.setup_score < filters.minSetupScore) {
      return false;
    }
    if (filters.minCompositeScore != null && row.composite_score < filters.minCompositeScore) {
      return false;
    }
    if (minGapBps != null) {
      if (row.gap_bps == null || !Number.isFinite(row.gap_bps)) return false;
      if (row.gap_bps < minGapBps) return false;
    }
    return true;
  });
}

export function loadListFiltersFromStorage(
  storage: Pick<Storage, "getItem"> | null | undefined = typeof localStorage !== "undefined"
    ? localStorage
    : null,
): ListFilterState {
  if (!storage) return { ...EMPTY_LIST_FILTERS };
  try {
    var raw = storage.getItem(LIST_FILTER_STORAGE_KEY);
    if (!raw) return { ...EMPTY_LIST_FILTERS };
    var parsed = JSON.parse(raw) as Partial<ListFilterState>;
    return {
      minSetupScore: asOptionalNumber(parsed.minSetupScore),
      minGapPct: asOptionalNumber(parsed.minGapPct),
      minCompositeScore: asOptionalNumber(parsed.minCompositeScore),
    };
  } catch {
    return { ...EMPTY_LIST_FILTERS };
  }
}

export function saveListFiltersToStorage(
  filters: ListFilterState,
  storage: Pick<Storage, "setItem" | "removeItem"> | null | undefined = typeof localStorage !== "undefined"
    ? localStorage
    : null,
): void {
  if (!storage) return;
  try {
    if (!hasActiveFilters(filters)) {
      storage.removeItem(LIST_FILTER_STORAGE_KEY);
      return;
    }
    storage.setItem(LIST_FILTER_STORAGE_KEY, JSON.stringify(filters));
  } catch {
    // ignore quota / private mode
  }
}

function asOptionalNumber(value: unknown): number | null {
  if (value == null || value === "") return null;
  var n = typeof value === "number" ? value : Number(value);
  return Number.isFinite(n) ? n : null;
}
