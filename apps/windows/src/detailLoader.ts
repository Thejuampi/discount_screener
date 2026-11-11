import type { SymbolDetailRefreshResult } from "./api";

export const VALUATION_READ_DELAYS = [1_200, 3_500, 8_000, 15_000, 20_000];

type DetailIssue = SymbolDetailRefreshResult["issues"][number] | "cache_read_failed" | "refresh_failed";

export interface DetailLoadState<T> {
  detail: T | null;
  status: "reading" | "ready" | "refreshing";
  issues: DetailIssue[];
  chartRevision: number;
  valuationRevision: number;
}

export const INITIAL_DETAIL: DetailLoadState<never> = {
  detail: null,
  status: "reading",
  issues: [],
  chartRevision: 0,
  valuationRevision: 0,
};

export function createDetailLoader<T>(port: {
  read: () => Promise<T | null>;
  refresh: (range: string) => Promise<SymbolDetailRefreshResult>;
  publish: (state: DetailLoadState<T>) => void;
}) {
  let state: DetailLoadState<T> = INITIAL_DETAIL;
  let closed = false;
  let revision = 0;
  let readRevision = 0;

  function update(changes: Partial<DetailLoadState<T>>) {
    if (closed) return;
    state = { ...state, ...changes };
    port.publish(state);
  }

  return {
    async open() {
      try {
        update({ detail: await port.read(), status: "ready" });
      } catch {
        update({ status: "ready", issues: ["cache_read_failed"] });
      }
    },

    async refresh(range: string) {
      if (closed || state.status !== "ready") return;
      revision += 1;
      update({ status: "refreshing", issues: [] });

      let result: SymbolDetailRefreshResult;
      try {
        result = await port.refresh(range);
      } catch {
        update({ status: "ready", issues: ["refresh_failed"] });
        return;
      }
      if (closed) return;

      const issues: DetailIssue[] = [...result.issues];
      let detail: T | null = null;
      try {
        detail = await port.read();
      } catch {
        issues.push("cache_read_failed");
      }
      update({
        detail: detail ?? state.detail,
        status: "ready",
        issues,
        chartRevision: state.chartRevision + Number(result.chart_updated),
        valuationRevision: state.valuationRevision + Number(result.quote_updated),
      });
    },

    async reread() {
      if (closed || state.status !== "ready") return;
      const requestRevision = revision;
      const request = ++readRevision;
      try {
        const detail = await port.read();
        if (detail && requestRevision === revision && request === readRevision) update({ detail });
      } catch {
        // A background cache read must leave the last visible snapshot intact.
      }
    },

    close() {
      closed = true;
    },
  };
}
