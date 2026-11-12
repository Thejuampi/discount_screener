import assert from "node:assert/strict";
import { describe, it } from "node:test";
import { detailSnapshotPresentation } from "../src/detailSnapshotPresentation.ts";

const savedAt = 1_782_000_000;
const savedStock = {
  detail_source: "restored",
  saved_at_epoch_seconds: savedAt,
  asset_type: "stock",
} as const;
const liveRow = { asset_type: "stock", market_price_cents: 31_400, composite_score: 70 } as const;

describe("detail snapshot presentation", () => {
  it("uses the saved classification and save time without a ranked row", () => {
    const view = detailSnapshotPresentation(savedStock, null);
    assert.equal(view.restored, true);
    assert.equal(view.assetType, "stock");
    assert.equal(view.savedAt?.getTime(), savedAt * 1000);
    assert.equal(view.liveRow, null);
    assert.equal(view.shouldReadSession, false);
  });

  it("requests a session read before displaying a newly arrived row", () => {
    const view = detailSnapshotPresentation(savedStock, liveRow);
    assert.equal(view.shouldReadSession, true);
    assert.equal(view.liveRow, null);
  });

  it("does not fill a missing saved classification from the live row", () => {
    const view = detailSnapshotPresentation({ ...savedStock, asset_type: null }, liveRow);
    assert.equal(view.assetType, null);
    assert.equal(view.liveRow, null);
  });

  it("ignores an invalid save time and a non-positive live quote", () => {
    const view = detailSnapshotPresentation(
      { ...savedStock, saved_at_epoch_seconds: Number.POSITIVE_INFINITY },
      { ...liveRow, market_price_cents: 0 },
    );
    assert.equal(view.savedAt, null);
    assert.equal(view.shouldReadSession, false);
  });

  it("uses the live row once the session supplies Detail", () => {
    const view = detailSnapshotPresentation({ ...savedStock, detail_source: "session" }, liveRow);
    assert.equal(view.liveRow, liveRow);
    assert.equal(view.restored, false);
    assert.equal(view.savedAt, null);
    assert.equal(view.shouldReadSession, false);
  });
});
