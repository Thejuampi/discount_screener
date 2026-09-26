/** Reuse chart data while presentation controls redraw the same symbol and range. */
export class CandleRequestCache<T> {
  private readonly entries = new Map<string, { request: Promise<T>; expiresAt: number }>();
  private readonly ttlMs: number;
  private readonly maxEntries: number;
  private readonly now: () => number;
  private readonly cacheValue: (value: T) => boolean;

  constructor(options: {
    ttlMs?: number;
    maxEntries?: number;
    now?: () => number;
    cacheValue?: (value: T) => boolean;
  } = {}) {
    this.ttlMs = options.ttlMs ?? 60_000;
    this.maxEntries = options.maxEntries ?? 16;
    this.now = options.now ?? Date.now;
    this.cacheValue = options.cacheValue ?? (() => true);
  }

  load(symbol: string, range: string, fetch: () => Promise<T>): Promise<T> {
    const key = `${symbol.trim().toUpperCase()}\0${range}`;
    const cached = this.entries.get(key);
    if (cached && cached.expiresAt > this.now()) return cached.request;
    this.entries.delete(key);

    let request: Promise<T>;
    try {
      request = Promise.resolve(fetch());
    } catch (error) {
      request = Promise.reject(error);
    }
    const entry = { request, expiresAt: Number.POSITIVE_INFINITY };
    this.entries.set(key, entry);
    while (this.entries.size > this.maxEntries) {
      const oldest = this.entries.keys().next().value;
      if (oldest === undefined) break;
      this.entries.delete(oldest);
    }

    void request.then(
      (value) => {
        if (this.entries.get(key) !== entry) return;
        if (this.cacheValue(value)) entry.expiresAt = this.now() + this.ttlMs;
        else this.entries.delete(key);
      },
      () => {
        if (this.entries.get(key) === entry) this.entries.delete(key);
      },
    );
    return request;
  }
}
