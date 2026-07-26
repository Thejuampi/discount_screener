import { buildUiRefPayload } from "./buildPayload.ts";
import type { RegisteredNode } from "./registry.ts";
import type { UiAppContext } from "./types.ts";

export type WriteTextFn = (text: string) => Promise<void>;
export type NotifyFn = (msg: string, kind: "success" | "error" | "info") => void;

async function defaultWriteText(text: string): Promise<void> {
  if (typeof navigator !== "undefined" && navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(text);
      return;
    } catch {
      // fall through
    }
  }
  if (typeof document === "undefined") {
    throw new Error("clipboard write failed: no document");
  }
  const ta = document.createElement("textarea");
  ta.value = text;
  ta.style.position = "fixed";
  ta.style.left = "-9999px";
  document.body.appendChild(ta);
  ta.select();
  const ok = document.execCommand("copy");
  document.body.removeChild(ta);
  if (!ok) throw new Error("clipboard write failed");
}

export async function copyUiRef(
  node: RegisteredNode,
  appContext: Partial<UiAppContext> | null | undefined,
  opts?: {
    writeText?: WriteTextFn;
    /** Optional notifier (toast). Not imported here so Node tests stay pure. */
    notify?: NotifyFn;
    successMsg?: string;
    errorMsg?: string;
  },
): Promise<boolean> {
  const writeText = opts?.writeText ?? defaultWriteText;
  const notify = opts?.notify;
  const snap = node.getSnapshot?.() ?? null;
  const visible = node.getVisibleText?.() ?? null;
  const text = buildUiRefPayload({
    def: node.def,
    snapshot: snap,
    appContext,
    visibleText: visible,
  });
  try {
    await writeText(text);
    notify?.(opts?.successMsg ?? `Copied UI ref · ${node.def.id}`, "success");
    return true;
  } catch (e) {
    console.error(e);
    notify?.(opts?.errorMsg ?? `Failed to copy UI ref · ${node.def.id}`, "error");
    return false;
  }
}
