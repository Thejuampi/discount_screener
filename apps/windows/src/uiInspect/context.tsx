import {
  createContext,
  useContext,
  useMemo,
  type ReactNode,
} from "react";
import type { UiAppContext } from "./types.ts";

export interface UiInspectContextValue {
  appContext: Partial<UiAppContext>;
}

const UiInspectContext = createContext<UiInspectContextValue | null>(null);

/** Provides app runtime context for clipboard payloads (view, model, etc.). */
export function UiInspectProvider({
  children,
  appContext,
}: {
  children: ReactNode;
  appContext: Partial<UiAppContext>;
}) {
  const value = useMemo(() => ({ appContext }), [appContext]);
  return (
    <UiInspectContext.Provider value={value}>{children}</UiInspectContext.Provider>
  );
}

export function useUiInspect(): UiInspectContextValue {
  const ctx = useContext(UiInspectContext);
  if (!ctx) return { appContext: {} };
  return ctx;
}
