type Observer = Pick<ResizeObserver, "observe" | "disconnect">;

/** Keep one resize observer for the current chart effect. */
export function observeChartWidth(
  target: Element,
  onResize: () => void,
  createObserver: (callback: () => void) => Observer = (callback) => new ResizeObserver(callback),
): () => void {
  const observer = createObserver(onResize);
  observer.observe(target);
  return () => observer.disconnect();
}
