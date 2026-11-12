import type { FeedRefreshPhase } from "./api";

export type FeedRefreshRequestState = "idle" | "requesting" | "rejected";

export function isFeedRefreshBusy(phase: FeedRefreshPhase | undefined): boolean {
  return phase !== undefined && phase !== "idle";
}

export function createFeedRefreshRequest({
  generation,
  request,
  onStateChange,
}: {
  generation: () => number;
  request: (profile: string) => Promise<void>;
  onStateChange: (state: FeedRefreshRequestState) => void;
}): (profile: string) => Promise<void> {
  let pendingGeneration: number | null = null;
  return async (profile) => {
    const requestedGeneration = generation();
    if (pendingGeneration === requestedGeneration) return;
    pendingGeneration = requestedGeneration;
    onStateChange("requesting");

    let outcome: FeedRefreshRequestState = "idle";
    try {
      await request(profile);
    } catch {
      outcome = "rejected";
    }

    if (pendingGeneration === requestedGeneration) pendingGeneration = null;
    if (generation() === requestedGeneration) onStateChange(outcome);
  };
}
