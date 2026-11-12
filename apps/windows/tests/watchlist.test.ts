import assert from "node:assert/strict";
import test from "node:test";
import { createWatchlist, INITIAL_WATCHLIST } from "../src/watchlist.ts";

function deferred() {
  let resolve!: () => void;
  let reject!: () => void;
  const promise = new Promise<void>((ok, fail) => {
    resolve = ok;
    reject = fail;
  });
  return { promise, resolve, reject };
}

function watchlist(read: () => Promise<string[]>, write: (symbol: string, included: boolean) => Promise<void>) {
  let state = INITIAL_WATCHLIST;
  const controller = createWatchlist({ read, write, publish: (next) => { state = next; } });
  return { ...controller, state: () => state };
}

test("loads saved membership and commits adds and removes in ticker order", async () => {
  const writes: [string, boolean][] = [];
  const list = watchlist(async () => ["MSFT"], async (...write) => { writes.push(write); });
  await list.load();
  assert.deepEqual(list.state().symbols, ["MSFT"]);
  await list.toggle(" aapl ");
  assert.deepEqual(list.state().symbols, ["AAPL", "MSFT"]);
  await list.toggle("msft");
  assert.deepEqual(list.state().symbols, ["AAPL"]);
  assert.deepEqual(writes, [["AAPL", true], ["MSFT", false]]);
});

test("deduplicates a pending toggle and keeps membership unchanged on write failure", async () => {
  const failure = deferred();
  let writes = 0;
  const list = watchlist(async () => ["AAPL"], () => {
    writes += 1;
    return writes === 1 ? failure.promise : Promise.resolve();
  });
  await list.load();
  const remove = list.toggle("AAPL");
  assert.equal(list.state().writes.get("AAPL"), "saving");
  await list.toggle("AAPL");
  assert.equal(writes, 1);
  assert.deepEqual(list.state().symbols, ["AAPL"]);
  failure.reject();
  await remove;
  assert.deepEqual(list.state().symbols, ["AAPL"]);
  assert.equal(list.state().writes.get("AAPL"), "failed");
  await list.toggle("AAPL");
  assert.deepEqual(list.state().symbols, []);
  assert.equal(list.state().writes.has("AAPL"), false);
});

test("merges writes for different tickers that finish out of order", async () => {
  const slow = deferred();
  const list = watchlist(async () => [], (symbol) => symbol === "AAPL" ? slow.promise : Promise.resolve());
  await list.load();
  const addApple = list.toggle("AAPL");
  await list.toggle("MSFT");
  assert.deepEqual(list.state().symbols, ["MSFT"]);
  slow.resolve();
  await addApple;
  assert.deepEqual(list.state().symbols, ["AAPL", "MSFT"]);
});

test("blocks writes until a failed read has recovered", async () => {
  let fail = true;
  let writes = 0;
  const list = watchlist(async () => {
    if (fail) throw new Error("database unavailable");
    return ["MSFT"];
  }, async () => { writes += 1; });
  await list.load();
  assert.equal(list.state().status, "unavailable");
  await list.toggle("MSFT");
  assert.equal(writes, 0);
  fail = false;
  await list.load();
  assert.equal(list.state().status, "ready");
  assert.deepEqual(list.state().symbols, ["MSFT"]);
});

test("ignores an old load after a new load has completed", async () => {
  const slow = deferred();
  let reads = 0;
  const list = watchlist(async () => {
    reads += 1;
    if (reads === 2) return ["MSFT"];
    await slow.promise;
    return ["AAPL"];
  }, async () => {});
  const first = list.load();
  list.cancelRead();
  await list.load();
  slow.resolve();
  await first;
  assert.deepEqual(list.state().symbols, ["MSFT"]);
});
