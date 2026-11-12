export type WatchlistStatus = "loading" | "ready" | "unavailable";
export type WatchlistWriteStatus = "saving" | "failed";

export interface WatchlistState {
  status: WatchlistStatus;
  symbols: readonly string[];
  writes: ReadonlyMap<string, WatchlistWriteStatus>;
}

export const INITIAL_WATCHLIST: WatchlistState = {
  status: "loading",
  symbols: [],
  writes: new Map(),
};

export function createWatchlist({
  read,
  write,
  publish,
}: {
  read: () => Promise<string[]>;
  write: (symbol: string, included: boolean) => Promise<void>;
  publish: (state: WatchlistState) => void;
}) {
  let state = INITIAL_WATCHLIST;
  let readGeneration = 0;

  function update(next: Partial<WatchlistState>) {
    state = { ...state, ...next };
    publish(state);
  }

  async function load() {
    if (state.status === "ready") return;
    const generation = ++readGeneration;
    update({ status: "loading" });
    try {
      const symbols = await read();
      if (generation === readGeneration) update({ status: "ready", symbols });
    } catch {
      if (generation === readGeneration) update({ status: "unavailable" });
    }
  }

  async function toggle(raw: string) {
    const symbol = raw.trim().toUpperCase();
    if (!symbol || state.status !== "ready" || state.writes.get(symbol) === "saving") return;

    const included = !state.symbols.includes(symbol);
    update({ writes: new Map(state.writes).set(symbol, "saving") });
    try {
      await write(symbol, included);
    } catch {
      update({ writes: new Map(state.writes).set(symbol, "failed") });
      return;
    }

    const writes = new Map(state.writes);
    writes.delete(symbol);
    const symbols = included
      ? [...state.symbols, symbol].sort()
      : state.symbols.filter((item) => item !== symbol);
    update({ writes, symbols });
  }

  return {
    load,
    toggle,
    cancelRead: () => { readGeneration += 1; },
  };
}
