import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { describe, it } from "node:test";
import { createDetailLoader, type DetailLoadState } from "../src/detailLoader.ts";

type Detail = { price: number };

function fixture() {
  let savedDetail: Detail | null = { price: 101 };
  let state: DetailLoadState<Detail> | undefined;
  let readFailure = false;
  let refreshFailure = false;
  let chartOnly = false;
  let refreshGate: Promise<void> | undefined;
  let nextRead: Promise<Detail | null> | undefined;
  const calls: string[] = [];
  const loader = createDetailLoader({
    read: async () => {
      calls.push("read");
      if (readFailure) throw new Error("cache unavailable");
      const deferred = nextRead;
      nextRead = undefined;
      return deferred ?? savedDetail;
    },
    refresh: async (range) => {
      calls.push(`refresh:${range}`);
      await refreshGate;
      if (refreshFailure) throw new Error("provider offline");
      if (chartOnly) return { quote_updated: false, chart_updated: true, issues: ["quote_unavailable"] };
      savedDetail = { price: 102 };
      return { quote_updated: true, chart_updated: false, issues: ["chart_unavailable"] };
    },
    publish: (next) => { state = next; },
  });
  return {
    loader, calls,
    get state() { return state; },
    set savedDetail(value: Detail | null) { savedDetail = value; },
    set readFailure(value: boolean) { readFailure = value; },
    set refreshFailure(value: boolean) { refreshFailure = value; },
    set chartOnly(value: boolean) { chartOnly = value; },
    set refreshGate(value: Promise<void>) { refreshGate = value; },
    set nextRead(value: Promise<Detail | null>) { nextRead = value; },
  };
}

describe("symbol detail loader", () => {
  it("opens from cache without issuing a provider request", async () => {
    const f = fixture();
    await f.loader.open();
    assert.deepEqual(f.calls, ["read"]);
    assert.deepEqual(f.state?.detail, { price: 101 });
    assert.equal(f.state?.status, "ready");
  });

  it("reports a cache read failure and allows a later load", async () => {
    const f = fixture();
    f.readFailure = true;
    await f.loader.open();
    assert.deepEqual(f.state?.issues, ["cache_read_failed"]);
    f.readFailure = false;
    await f.loader.refresh("3mo");
    assert.deepEqual(f.state?.detail, { price: 102 });
  });

  it("publishes a refreshed quote and reports a chart failure", async () => {
    const f = fixture();
    await f.loader.open();
    const pending = f.loader.refresh("3mo");
    assert.equal(f.state?.status, "refreshing");
    assert.deepEqual(f.state?.detail, { price: 101 });
    await pending;
    assert.deepEqual(f.calls, ["read", "refresh:3mo", "read"]);
    assert.deepEqual(f.state?.detail, { price: 102 });
    assert.deepEqual(f.state?.issues, ["chart_unavailable"]);
    assert.equal(f.state?.valuationRevision, 1);
    assert.equal(f.state?.chartRevision, 0);
  });

  it("publishes chart-only success without inventing a quote", async () => {
    const f = fixture();
    f.savedDetail = null;
    f.chartOnly = true;
    await f.loader.open();
    await f.loader.refresh("1y");
    assert.equal(f.state?.detail, null);
    assert.equal(f.state?.chartRevision, 1);
    assert.equal(f.state?.valuationRevision, 0);
    assert.deepEqual(f.state?.issues, ["quote_unavailable"]);
  });

  it("retains the prior cache on a failed provider request", async () => {
    const f = fixture();
    await f.loader.open();
    f.refreshFailure = true;
    await f.loader.refresh("1y");
    assert.deepEqual(f.state?.detail, { price: 101 });
    assert.deepEqual(f.state?.issues, ["refresh_failed"]);
  });

  it("ignores a double click and a provider result after navigation", async () => {
    const f = fixture();
    const gate = Promise.withResolvers<void>();
    f.refreshGate = gate.promise;
    await f.loader.open();
    const pending = f.loader.refresh("1y");
    await f.loader.refresh("3mo");
    f.loader.close();
    gate.resolve();
    await pending;
    assert.deepEqual(f.calls, ["read", "refresh:1y"]);
    assert.deepEqual(f.state?.detail, { price: 101 });
  });

  it("ignores an older background read after a manual refresh", async () => {
    const f = fixture();
    await f.loader.open();
    const gate = Promise.withResolvers<Detail>();
    f.nextRead = gate.promise;
    const pending = f.loader.reread();
    await f.loader.refresh("1y");
    gate.resolve({ price: 101 });
    await pending;
    assert.deepEqual(f.state?.detail, { price: 102 });
  });
});

describe("symbol detail production path", () => {
  const detail = readFileSync(new URL("../src/components/DetailPanel.tsx", import.meta.url), "utf8");
  it("calls the explicit detail refresh and gates related provider panels", () => {
    assert.doesNotMatch(detail, /api\.(ensureSymbolLoaded|refreshSymbol)\(/);
    assert.match(detail, /api\.refreshSymbolDetail\(symbol, range\)/);
    assert.match(detail, /relatedLoaded && <ProvenanceBadge/);
    assert.match(detail, /relatedLoaded && <NewsPanel/);
    assert.match(detail, /relatedLoaded && <CryptoCyclePanel/);
  });

  it("requests chart data from a provider only after range selection and a cache miss", () => {
    const chart = readFileSync(new URL("../src/components/CandleChart.tsx", import.meta.url), "utf8");
    assert.match(detail, /useState\(\{ range: "3mo", request: 0, revision: 0 \}\)/);
    assert.match(detail, /onClick=\{\(\) => setChartRange\(\(prior\) => \(\{ range: r, request: prior.request \+ 1/);
    assert.match(chart, /readDetailCandleSeries\(\s*\(\) => api\.getCandles\(symbol, range\),\s*async \(\) => loadRequest && !cancelled \? api\.loadSymbolCandles\(symbol, range\) : null/);
  });
});
