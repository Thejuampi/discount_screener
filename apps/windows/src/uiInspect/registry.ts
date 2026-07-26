import type { UiSnapshot, UiSourceDef } from "./types.ts";

export interface RegisteredNode {
  instanceId: string;
  def: UiSourceDef;
  getSnapshot: () => UiSnapshot | null | undefined;
  getVisibleText?: () => string | null | undefined;
}

const byInstance = new Map<string, RegisteredNode>();

export function registerUiNode(node: RegisteredNode): () => void {
  byInstance.set(node.instanceId, node);
  return () => {
    byInstance.delete(node.instanceId);
  };
}

export function lookupUiNode(instanceId: string | null | undefined): RegisteredNode | null {
  if (!instanceId) return null;
  return byInstance.get(instanceId) ?? null;
}

export function findUiNodeFromElement(el: Element | null): RegisteredNode | null {
  let cur: Element | null = el;
  while (cur) {
    if (cur instanceof HTMLElement) {
      const id = cur.dataset.uiInstance;
      const node = lookupUiNode(id);
      if (node) return node;
    }
    cur = cur.parentElement;
  }
  return null;
}

export function findUiElementFromTarget(el: Element | null): HTMLElement | null {
  let cur: Element | null = el;
  while (cur) {
    if (cur instanceof HTMLElement && cur.dataset.uiId) return cur;
    cur = cur.parentElement;
  }
  return null;
}

/** Test helper */
export function clearUiRegistry(): void {
  byInstance.clear();
}

export function registrySize(): number {
  return byInstance.size;
}
