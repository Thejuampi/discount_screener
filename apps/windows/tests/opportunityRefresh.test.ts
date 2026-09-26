import assert from "node:assert/strict";
import test from "node:test";

import { canPublishModelRows, createGenerationRefresh } from "../src/opportunityRefresh.ts";

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}

test("profile switch drops old rows and starts a new refresh before the old poll settles", async () => {
  let generation = "model-1:profile-1";
  let canRefresh = true;
  const first = deferred<string>();
  const second = deferred<string>();
  const pending = [first, second];
  const published: string[] = [];
  let fetchCount = 0;
  const refresh = createGenerationRefresh({
    generation: () => generation,
    canRefresh: () => canRefresh,
    fetch: () => pending[fetchCount++].promise,
    publish: (rows) => published.push(rows),
  });

  const oldPoll = refresh();
  assert.strictEqual(refresh(), oldPoll);
  assert.equal(fetchCount, 1);

  generation = "model-1:profile-2";
  canRefresh = false;
  await refresh();
  assert.equal(fetchCount, 1);

  canRefresh = true;
  const newPoll = refresh();
  assert.notStrictEqual(newPoll, oldPoll);
  assert.equal(fetchCount, 2);

  first.resolve("old-profile rows");
  await oldPoll;
  assert.deepEqual(published, []);

  second.resolve("new-profile rows");
  await newPoll;
  assert.deepEqual(published, ["new-profile rows"]);
});

test("model switch blocks publication and a new generation can refresh immediately", async () => {
  let generation = "model-1:profile-1";
  let canRefresh = true;
  const first = deferred<number>();
  const published: number[] = [];
  let fetchCount = 0;
  const refresh = createGenerationRefresh({
    generation: () => generation,
    canRefresh: () => canRefresh,
    fetch: () => {
      fetchCount += 1;
      return fetchCount === 1 ? first.promise : Promise.resolve(2);
    },
    publish: (value) => published.push(value),
  });

  const oldPoll = refresh();
  canRefresh = false;
  generation = "model-2:profile-1";
  await refresh();
  first.resolve(1);
  await oldPoll;
  assert.deepEqual(published, []);

  canRefresh = true;
  await refresh();
  assert.equal(fetchCount, 2);
  assert.deepEqual(published, [2]);
});

test("model switch rejects rows when the universe changed before its request started", () => {
  const initialUniverseGeneration = 1;
  const requestUniverseGeneration = 2;
  const currentUniverseGeneration = 2;
  assert.equal(
    canPublishModelRows(
      initialUniverseGeneration,
      requestUniverseGeneration,
      currentUniverseGeneration,
      false,
    ),
    false,
  );
  assert.equal(canPublishModelRows(2, 2, 2, false), true);
  assert.equal(canPublishModelRows(2, 2, 2, true), false);
});
