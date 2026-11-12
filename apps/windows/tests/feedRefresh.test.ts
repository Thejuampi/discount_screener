import assert from "node:assert/strict";
import test from "node:test";

import { createFeedRefreshRequest, isFeedRefreshBusy, type FeedRefreshRequestState } from "../src/feedRefresh.ts";

function pendingRequest() {
  let resolve!: () => void;
  let reject!: (reason: Error) => void;
  const promise = new Promise<void>((done, fail) => { resolve = done; reject = fail; });
  return { promise, resolve, reject };
}

test("two clicks while a request is pending send one request for the active profile", async () => {
  const reply = pendingRequest();
  const profiles: string[] = [];
  const states: FeedRefreshRequestState[] = [];
  const refresh = createFeedRefreshRequest({
    generation: () => 1,
    request: (profile) => { profiles.push(profile); return reply.promise; },
    onStateChange: (state) => states.push(state),
  });

  const first = refresh("sp500");
  await refresh("sp500");
  assert.deepEqual(profiles, ["sp500"]);
  assert.deepEqual(states, ["requesting"]);

  reply.resolve();
  await first;
  assert.deepEqual(states, ["requesting", "idle"]);
});

test("a late rejection from a previous profile leaves the new request pending", async () => {
  let generation = 1;
  const oldReply = pendingRequest();
  const newReply = pendingRequest();
  const profiles: string[] = [];
  const states: FeedRefreshRequestState[] = [];
  const refresh = createFeedRefreshRequest({
    generation: () => generation,
    request: (profile) => {
      profiles.push(profile);
      return profile === "sp500" ? oldReply.promise : newReply.promise;
    },
    onStateChange: (state) => states.push(state),
  });

  const oldRequest = refresh("sp500");
  generation = 2;
  const newRequest = refresh("qa");
  oldReply.reject(new Error("profile changed"));
  await oldRequest;
  await refresh("qa");
  assert.deepEqual(profiles, ["sp500", "qa"]);
  assert.deepEqual(states, ["requesting", "requesting"]);

  newReply.resolve();
  await newRequest;
  assert.deepEqual(states, ["requesting", "requesting", "idle"]);
});

test("a rejected request permits a retry for the same profile", async () => {
  let calls = 0;
  const states: FeedRefreshRequestState[] = [];
  const refresh = createFeedRefreshRequest({
    generation: () => 1,
    request: async () => {
      calls += 1;
      if (calls === 1) throw new Error("feed unavailable");
    },
    onStateChange: (state) => states.push(state),
  });

  await refresh("qa");
  await refresh("qa");
  assert.deepEqual(states, ["requesting", "rejected", "requesting", "idle"]);
});

test("the active pass phases block requests without changing available row coverage", () => {
  assert.equal(isFeedRefreshBusy(undefined), false);
  assert.equal(isFeedRefreshBusy("idle"), false);
  for (const phase of ["startup", "retrying", "queued", "refreshing"] as const) {
    assert.equal(isFeedRefreshBusy(phase), true);
  }
});
