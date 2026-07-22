/** Coalesce concurrent calls onto one promise, then reopen after it settles. */
export function singleFlight<T>(task: () => Promise<T>): () => Promise<T> {
  let inFlight: Promise<T> | null = null;

  return () => {
    if (inFlight) return inFlight;

    let resolveRequest!: (value: T | PromiseLike<T>) => void;
    let rejectRequest!: (reason?: unknown) => void;
    const request = new Promise<T>((resolve, reject) => {
      resolveRequest = resolve;
      rejectRequest = reject;
    });
    // Publish the placeholder before invoking task so synchronous re-entry also coalesces.
    inFlight = request;
    try {
      task().then(resolveRequest, rejectRequest);
    } catch (error) {
      rejectRequest(error);
    }
    void request.finally(() => {
      if (inFlight === request) inFlight = null;
    }).catch(() => {});
    return request;
  };
}
