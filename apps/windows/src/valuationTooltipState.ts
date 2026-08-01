export type ValuationTooltipEvent =
  | "focus"
  | "blur"
  | "pointer_enter"
  | "pointer_leave"
  | "toggle"
  | "escape";

export interface ValuationTooltipState {
  open: boolean;
  pinned: boolean;
}

export const CLOSED_VALUATION_TOOLTIP: ValuationTooltipState = {
  open: false,
  pinned: false,
};

export function nextValuationTooltipState(
  state: ValuationTooltipState,
  event: ValuationTooltipEvent,
): ValuationTooltipState {
  if (event === "escape") return CLOSED_VALUATION_TOOLTIP;
  if (event === "toggle") {
    const pinned = !state.pinned;
    return { open: pinned, pinned };
  }
  if (event === "focus" || event === "pointer_enter") {
    return { ...state, open: true };
  }
  return { ...state, open: state.pinned };
}

export function valuationTooltipDescribedBy(
  state: ValuationTooltipState,
  tipId: string,
): string | undefined {
  return state.open ? tipId : undefined;
}
