import { singleFlight } from "./singleFlight.ts";

/** A profile change anywhere in a model switch invalidates its opportunity response. */
export function canPublishModelRows(
  initialUniverseGeneration: number,
  requestUniverseGeneration: number,
  currentUniverseGeneration: number,
  universeSwitching: boolean,
): boolean {
  return initialUniverseGeneration === requestUniverseGeneration &&
    requestUniverseGeneration === currentUniverseGeneration &&
    !universeSwitching;
}

/** Keep one poll per data generation, while allowing a new profile to bypass an older poll. */
export function createGenerationRefresh<T>({
  generation,
  canRefresh,
  fetch,
  publish,
}: {
  generation: () => string;
  canRefresh: () => boolean;
  fetch: () => Promise<T>;
  publish: (value: T) => void;
}): () => Promise<void> {
  let current: { generation: string; run: () => Promise<void> } | null = null;
  return () => {
    if (!canRefresh()) return Promise.resolve();
    const requestedGeneration = generation();
    if (current?.generation !== requestedGeneration) {
      const run = singleFlight(async () => {
        const value = await fetch();
        if (requestedGeneration !== generation() || !canRefresh()) return;
        publish(value);
      });
      current = { generation: requestedGeneration, run };
    }
    return current.run();
  };
}
