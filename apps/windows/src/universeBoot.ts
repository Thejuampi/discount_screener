/**
 * Pure helpers for universe profile restore on boot.
 * Locked launch profile always wins over localStorage.
 */

export type UniverseBootStatus = {
  name: string;
  symbols_total: number;
  symbols_loaded: number;
  profile_locked: boolean;
};

export type UniverseBootAction =
  | { kind: "use_locked"; name: string; startFeedOnly: true }
  | { kind: "apply_saved"; name: string };

/**
 * Decide whether localStorage may drive set_universe_profile.
 * When backend reports locked, never apply a different saved profile.
 */
export function planUniverseBoot(
  backend: UniverseBootStatus,
  savedLocal: string | null,
): UniverseBootAction {
  if (backend.profile_locked) {
    return { kind: "use_locked", name: backend.name, startFeedOnly: true };
  }
  const saved = savedLocal && savedLocal.length > 0 ? savedLocal : "sp500";
  // Never persist alias `test` — callers should only store canonical ids.
  const canonical = saved === "test" ? "qa" : saved;
  // QA is a launch-scoped test profile, never the regular application's saved
  // starting universe. This also repairs sessions where an earlier forced QA
  // launch overwrote localStorage.
  const name = canonical === "qa" ? "sp500" : canonical;
  return { kind: "apply_saved", name };
}

/** Canonical id for persistence (never alias `test`). */
export function canonicalUniverseName(name: string): string {
  return name === "test" ? "qa" : name;
}
