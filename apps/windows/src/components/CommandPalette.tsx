import { useEffect, useRef, useState } from "react";
import type { OpportunityRow } from "../api";
import { useT } from "../i18n";

type ViewMode = "screener" | "congress" | "advisor" | "scalping";

interface Action {
  id: string;
  icon: string;
  label: string;
  sub?: string;
  kind: "nav" | "action" | "symbol";
  run: () => void;
}

interface Props {
  rows: OpportunityRow[];
  onNavigate: (v: ViewMode) => void;
  onOpenSymbol: (symbol: string) => void;
  onOpenSettings: () => void;
  onToggleTheme: () => void;
}

/** ⌘K / Ctrl+K command palette — jump to any section or symbol from the keyboard. */
export function CommandPalette({ rows, onNavigate, onOpenSymbol, onOpenSettings, onToggleTheme }: Props) {
  const { t } = useT();
  const [open, setOpen] = useState(false);
  const [q, setQ] = useState("");
  const [sel, setSel] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);
  const activeRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        setOpen((o) => !o);
      } else if (e.key === "Escape") {
        setOpen(false);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  useEffect(() => {
    if (open) { setQ(""); setSel(0); setTimeout(() => inputRef.current?.focus(), 30); }
  }, [open]);

  useEffect(() => { activeRef.current?.scrollIntoView({ block: "nearest" }); }, [sel]);

  const nav: Action[] = [
    { id: "nav-screener", icon: "📈", label: t("view.screener"), kind: "nav", run: () => onNavigate("screener") },
    { id: "nav-scalping", icon: "⚡", label: t("view.scalping"), kind: "nav", run: () => onNavigate("scalping") },
    { id: "nav-congress", icon: "🏛", label: t("view.congress"), kind: "nav", run: () => onNavigate("congress") },
    { id: "nav-advisor", icon: "🧭", label: t("view.advisor"), kind: "nav", run: () => onNavigate("advisor") },
    { id: "act-settings", icon: "⚙", label: t("cmd.settings"), kind: "action", run: onOpenSettings },
    { id: "act-theme", icon: "🌓", label: t("cmd.theme"), kind: "action", run: onToggleTheme },
  ];

  const ql = q.trim().toLowerCase();
  const navMatches = ql.length === 0 ? nav : nav.filter((a) => a.label.toLowerCase().includes(ql));
  const symMatches: Action[] = ql.length === 0 ? [] : rows
    .filter((r) => r.symbol.toLowerCase().includes(ql) || (r.company_name ?? "").toLowerCase().includes(ql))
    .slice(0, 8)
    .map((r) => ({
      id: "sym-" + r.symbol, icon: "›", label: r.symbol, sub: r.company_name ?? undefined,
      kind: "symbol", run: () => onOpenSymbol(r.symbol),
    }));
  const results = [...navMatches, ...symMatches];

  const exec = (i: number) => {
    const r = results[i];
    if (r) { r.run(); setOpen(false); }
  };

  if (!open) return null;
  const selIdx = Math.min(sel, Math.max(0, results.length - 1));

  return (
    <div className="cmdk-overlay" onMouseDown={() => setOpen(false)}>
      <div className="cmdk" onMouseDown={(e) => e.stopPropagation()}>
        <div className="cmdk-search">
          <span className="cmdk-search-icon">⌕</span>
          <input
            ref={inputRef}
            className="cmdk-input"
            placeholder={t("cmd.placeholder")}
            value={q}
            onChange={(e) => { setQ(e.target.value); setSel(0); }}
            onKeyDown={(e) => {
              if (e.key === "ArrowDown") { e.preventDefault(); setSel((s) => Math.min(s + 1, results.length - 1)); }
              else if (e.key === "ArrowUp") { e.preventDefault(); setSel((s) => Math.max(s - 1, 0)); }
              else if (e.key === "Enter") { e.preventDefault(); exec(selIdx); }
            }}
          />
          <span className="cmdk-kbd">esc</span>
        </div>
        <div className="cmdk-list">
          {results.length === 0 ? (
            <div className="cmdk-empty">{t("cmd.noResults")}</div>
          ) : results.map((r, i) => (
            <div
              key={r.id}
              ref={i === selIdx ? activeRef : undefined}
              className={`cmdk-item ${i === selIdx ? "active" : ""}`}
              onMouseEnter={() => setSel(i)}
              onClick={() => exec(i)}
            >
              <span className="cmdk-icon">{r.icon}</span>
              <span className="cmdk-label">{r.label}</span>
              {r.sub && <span className="cmdk-sub">{r.sub}</span>}
              <span className="cmdk-tag">{r.kind === "symbol" ? t("cmd.open") : t("cmd.go")}</span>
            </div>
          ))}
        </div>
        <div className="cmdk-foot">
          <span><b>↑↓</b> {t("cmd.nav")}</span>
          <span><b>↵</b> {t("cmd.select")}</span>
          <span><b>⌘K</b> {t("cmd.toggle")}</span>
        </div>
      </div>
    </div>
  );
}
