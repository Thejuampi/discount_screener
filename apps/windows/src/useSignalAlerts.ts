import { useEffect, useRef } from "react";
import {
  isPermissionGranted,
  requestPermission,
  sendNotification,
} from "@tauri-apps/plugin-notification";
import { api } from "./api";
import type { OpportunityRow, SetupLabel } from "./api";
import { useT } from "./i18n";

// Bucket a label into polarity. Alerts fire only when the bucket flips, so we
// don't spam on every minor score wiggle — only when the *stance* changes.
type Bucket = "pos" | "neu" | "neg";
const POS: SetupLabel[] = ["StrongBuy", "Buy", "Accumulate", "StrongAccumulate"];
const NEG: SetupLabel[] = ["Avoid", "StrongAvoid", "Caution", "Distribute"];
function bucketOf(label: SetupLabel): Bucket {
  if (POS.includes(label)) return "pos";
  if (NEG.includes(label)) return "neg";
  return "neu";
}

const STORE_KEY = "ds_alert_labels"; // symbol → last label seen (persisted)

function loadStore(): Record<string, SetupLabel> {
  try { return JSON.parse(localStorage.getItem(STORE_KEY) ?? "{}"); }
  catch { return {}; }
}

/**
 * Desktop-notification alerts: when a *held* position's signal changes polarity
 * (e.g. Buy → Avoid), fire a native notification. Runs while the app is alive,
 * including minimized to tray. No external messaging channel involved.
 */
export function useSignalAlerts(rows: OpportunityRow[]) {
  const { t } = useT();
  const heldRef = useRef<Set<string>>(new Set());
  const lastRef = useRef<Record<string, SetupLabel>>(loadStore());

  // Keep the held-symbols set fresh (portfolio can change between sessions).
  useEffect(() => {
    let cancelled = false;
    const load = () => api.portfolioList()
      .then((p) => { if (!cancelled) heldRef.current = new Set(p.map((x) => x.symbol)); })
      .catch(() => {});
    load();
    const id = setInterval(load, 60_000);
    return () => { cancelled = true; clearInterval(id); };
  }, []);

  useEffect(() => {
    if (rows.length === 0 || heldRef.current.size === 0) return;
    let cancelled = false;

    (async () => {
      let granted = await isPermissionGranted();
      if (!granted) granted = (await requestPermission()) === "granted";

      const store = lastRef.current;
      const changes: { symbol: string; from: SetupLabel; to: SetupLabel }[] = [];

      for (const r of rows) {
        if (!heldRef.current.has(r.symbol)) continue;
        const prev = store[r.symbol];
        if (prev && prev !== r.setup_label && bucketOf(prev) !== bucketOf(r.setup_label)) {
          changes.push({ symbol: r.symbol, from: prev, to: r.setup_label });
        }
        store[r.symbol] = r.setup_label; // always update last-seen
      }

      localStorage.setItem(STORE_KEY, JSON.stringify(store));
      if (cancelled || !granted) return;

      for (const c of changes) {
        sendNotification({
          title: t("alert.signalChange.title", { symbol: c.symbol }),
          body: t("alert.signalChange.body", {
            symbol: c.symbol,
            from: t(`setup.${c.from}`),
            to: t(`setup.${c.to}`),
          }),
        });
      }
    })();

    return () => { cancelled = true; };
  }, [rows, t]);
}
