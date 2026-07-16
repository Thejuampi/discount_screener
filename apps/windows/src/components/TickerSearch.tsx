import { useCallback, useEffect, useRef, useState } from "react";
import { api } from "../api";
import type { TickerSearchSuggestion } from "../api";
import { useT } from "../i18n";

const DEBOUNCE_MS = 300;

interface Props {
  onOpenSymbol: (symbol: string) => void;
  /** Optional: keep list filter in sync with the typed query (substring among loaded rows). */
  onQueryChange?: (query: string) => void;
}

export function TickerSearch({ onOpenSymbol, onQueryChange }: Props) {
  const { t } = useT();
  const [query, setQuery] = useState("");
  const [suggestions, setSuggestions] = useState<TickerSearchSuggestion[]>([]);
  const [expanded, setExpanded] = useState(false);
  const [loading, setLoading] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [activeIdx, setActiveIdx] = useState(0);
  const genRef = useRef(0);
  const wrapRef = useRef<HTMLDivElement>(null);

  const openSymbol = useCallback(
    async (symbol: string) => {
      setExpanded(false);
      setNotice(null);
      setQuery("");
      onQueryChange?.("");
      setSuggestions([]);
      onOpenSymbol(symbol);
      try {
        await api.ensureSymbolLoaded(symbol);
      } catch (e) {
        console.error("ensureSymbolLoaded failed", e);
      }
    },
    [onOpenSymbol, onQueryChange],
  );

  useEffect(() => {
    onQueryChange?.(query);
    if (!query.trim()) {
      setSuggestions([]);
      setExpanded(false);
      setLoading(false);
      setNotice(null);
      return;
    }

    const gen = ++genRef.current;
    setLoading(true);
    setNotice(null);
    const timer = window.setTimeout(async () => {
      try {
        const results = await api.searchTickers(query.trim());
        if (gen !== genRef.current) return;
        setSuggestions(results);
        setExpanded(results.length > 0 || query.trim().length >= 2);
        setActiveIdx(0);
      } catch (e) {
        if (gen !== genRef.current) return;
        console.error("searchTickers failed", e);
        setSuggestions([]);
      } finally {
        if (gen === genRef.current) setLoading(false);
      }
    }, DEBOUNCE_MS);

    return () => window.clearTimeout(timer);
  }, [query, onQueryChange]);

  useEffect(() => {
    const onDoc = (e: MouseEvent) => {
      if (!wrapRef.current?.contains(e.target as Node)) {
        setExpanded(false);
      }
    };
    document.addEventListener("mousedown", onDoc);
    return () => document.removeEventListener("mousedown", onDoc);
  }, []);

  const submit = async () => {
    const q = query.trim();
    if (!q) return;
    try {
      let list = suggestions;
      // Re-search if suggestions are empty but query is non-blank (stale debounce).
      if (list.length === 0 && q.length >= 1) {
        setLoading(true);
        list = await api.searchTickers(q);
        setSuggestions(list);
        setLoading(false);
      }
      const outcome = await api.resolveTickerSearchSubmit(q, list);
      if (outcome.kind === "open") {
        await openSymbol(outcome.symbol);
      } else if (outcome.kind === "pick_match") {
        setExpanded(true);
        setNotice(t("search.pickMatch"));
      } else {
        setNotice(t("search.unavailable"));
      }
    } catch (e) {
      console.error("submit search failed", e);
      setNotice(t("search.unavailable"));
    }
  };

  const onKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "ArrowDown") {
      e.preventDefault();
      if (suggestions.length === 0) return;
      setExpanded(true);
      setActiveIdx((i) => (i + 1) % suggestions.length);
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      if (suggestions.length === 0) return;
      setExpanded(true);
      setActiveIdx((i) => (i - 1 + suggestions.length) % suggestions.length);
    } else if (e.key === "Enter") {
      e.preventDefault();
      if (expanded && suggestions[activeIdx]) {
        void openSymbol(suggestions[activeIdx].symbol);
      } else {
        void submit();
      }
    } else if (e.key === "Escape") {
      setExpanded(false);
      setNotice(null);
    }
  };

  const supportLine = (s: TickerSearchSuggestion) => {
    if (s.profiles.length > 0) return s.profiles.join(" · ");
    if (s.exchange) return s.exchange;
    if (s.is_remote) return t("search.outsideUniverse");
    return "";
  };

  const displayName = (s: TickerSearchSuggestion) => {
    const name = s.company_name?.trim();
    if (!name || name.toLowerCase() === "null") return s.symbol;
    return `${s.symbol} — ${name}`;
  };

  return (
    <div className="ticker-search" ref={wrapRef}>
      <div className="search-wrap">
        <span className="search-icon" aria-hidden>
          ⌕
        </span>
        <input
          className="search"
          placeholder={t("search.placeholder")}
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onFocus={() => {
            if (suggestions.length > 0 || query.trim().length >= 2) setExpanded(true);
          }}
          onKeyDown={onKeyDown}
          aria-autocomplete="list"
          aria-expanded={expanded}
          role="combobox"
        />
        {loading && <span className="search-spinner" aria-label={t("search.loading")} />}
        <button type="button" className="search-open-btn" onClick={() => void submit()}>
          {t("search.open")}
        </button>
      </div>

      {notice && <div className="search-notice">{notice}</div>}

      {expanded && suggestions.length > 0 && (
        <ul className="search-dropdown" role="listbox">
          {suggestions.map((s, i) => (
            <li key={s.symbol}>
              <button
                type="button"
                className={`search-suggestion ${i === activeIdx ? "active" : ""}`}
                role="option"
                aria-selected={i === activeIdx}
                onMouseEnter={() => setActiveIdx(i)}
                onClick={() => void openSymbol(s.symbol)}
              >
                <span className="search-suggestion-main">
                  {displayName(s)}
                  {s.in_current_profile && (
                    <span className="search-badge">{t("search.currentUniverse")}</span>
                  )}
                  {s.is_remote && !s.in_current_profile && (
                    <span className="search-badge remote">{t("search.remote")}</span>
                  )}
                </span>
                <span className="search-suggestion-sub">{supportLine(s)}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
