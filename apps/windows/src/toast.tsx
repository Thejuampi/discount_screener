import { useEffect, useState } from "react";

export type ToastKind = "success" | "error" | "info";
interface Toast { id: number; msg: string; kind: ToastKind }

// Tiny pub/sub so any module can fire a toast without threading a provider.
let listeners: ((t: Toast[]) => void)[] = [];
let toasts: Toast[] = [];
let nextId = 1;

function emit() { listeners.forEach((l) => l([...toasts])); }

export function toast(msg: string, kind: ToastKind = "info") {
  const id = nextId++;
  toasts = [...toasts, { id, msg, kind }];
  emit();
  setTimeout(() => dismiss(id), 4200);
}
function dismiss(id: number) {
  toasts = toasts.filter((t) => t.id !== id);
  emit();
}

const ICON: Record<ToastKind, string> = { success: "✓", error: "✕", info: "›" };

export function Toaster() {
  const [items, setItems] = useState<Toast[]>([]);
  useEffect(() => {
    const l = (t: Toast[]) => setItems(t);
    listeners.push(l);
    return () => { listeners = listeners.filter((x) => x !== l); };
  }, []);
  if (items.length === 0) return null;
  return (
    <div className="toaster">
      {items.map((t) => (
        <div key={t.id} className={`toast toast-${t.kind}`} onClick={() => dismiss(t.id)}>
          <span className="toast-icon">{ICON[t.kind]}</span>
          <span>{t.msg}</span>
        </div>
      ))}
    </div>
  );
}
