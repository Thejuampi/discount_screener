import { useEffect, useRef } from "react";
import { useT } from "../i18n.tsx";
import { toast } from "../toast.tsx";
import { copyUiRef } from "./copy.ts";
import { useUiInspect } from "./context.tsx";
import { findUiElementFromTarget, findUiNodeFromElement } from "./registry.ts";
import type { RegisteredNode } from "./registry.ts";
import type { UiAppContext } from "./types.ts";

/**
 * Right-click an instrumented UI node → copy ds-ui-ref to clipboard.
 * No mode toggle, no extra modifiers. Left-click stays for normal navigation.
 */
export function UiInspectRoot() {
  const { appContext } = useUiInspect();
  const { t } = useT();
  const hoverEl = useRef<HTMLElement | null>(null);
  const appContextRef = useRef(appContext);
  const tRef = useRef(t);
  appContextRef.current = appContext;
  tRef.current = t;

  useEffect(() => {
    const clearHover = () => {
      if (hoverEl.current) {
        hoverEl.current.classList.remove("ui-inspect-hover");
        hoverEl.current = null;
      }
    };

    const doCopy = (node: RegisteredNode) => {
      void copyUiRef(node, appContextRef.current as Partial<UiAppContext>, {
        successMsg: tRef.current("uiInspect.copied", { id: node.def.id }),
        errorMsg: tRef.current("uiInspect.copyFailed", { id: node.def.id }),
        notify: (msg, kind) => toast(msg, kind),
      });
    };

    // Subtle hover so you can see which nodes are copyable
    const onMove = (e: MouseEvent) => {
      const el = findUiElementFromTarget(e.target as Element | null);
      if (el === hoverEl.current) return;
      clearHover();
      if (el) {
        el.classList.add("ui-inspect-hover");
        hoverEl.current = el;
      }
    };

    const onContextMenu = (e: MouseEvent) => {
      const node = findUiNodeFromElement(e.target as Element | null);
      if (!node) return; // not instrumented — allow normal browser/menu behavior
      e.preventDefault();
      e.stopPropagation();
      doCopy(node);
    };

    document.addEventListener("mousemove", onMove, true);
    document.addEventListener("contextmenu", onContextMenu, true);

    return () => {
      document.removeEventListener("mousemove", onMove, true);
      document.removeEventListener("contextmenu", onContextMenu, true);
      clearHover();
    };
  }, []);

  return null;
}
