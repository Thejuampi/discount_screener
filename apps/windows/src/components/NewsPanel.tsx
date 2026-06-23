import { useEffect, useState } from "react";
import { open as openExternal } from "@tauri-apps/plugin-shell";
import { api } from "../api";
import type { NewsBundle, NewsItem } from "../api";
import { useT } from "../i18n";

interface Props {
  symbol: string;
}

export function NewsPanel({ symbol }: Props) {
  const { t } = useT();
  const [bundle, setBundle] = useState<NewsBundle | null>(null);
  const [loading, setLoading] = useState(true);

  const load = (sym: string) => {
    setLoading(true);
    api.getNews(sym)
      .then((b) => { setBundle(b); setLoading(false); })
      .catch((e) => { console.error("news", e); setLoading(false); });
  };

  useEffect(() => {
    load(symbol);
  }, [symbol]);

  return (
    <div className="info-section news-section">
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}>
        <h3 style={{ margin: 0 }}>{t("news.title")}</h3>
        <button className="glossary-btn" onClick={() => load(symbol)} disabled={loading}>
          ↻ {t("news.refresh")}
        </button>
      </div>

      {loading && <div className="loading-msg" style={{ padding: 12 }}>{t("news.loading")}</div>}

      {!loading && bundle && bundle.items.length === 0 && (
        <div style={{ padding: 12, color: "var(--text-4)", fontSize: 12 }}>
          {t("news.empty")}
        </div>
      )}

      {!loading && bundle && bundle.items.length > 0 && (
        <>
          <SentimentSummary bundle={bundle} />
          <ul className="news-list">
            {bundle.items.slice(0, 8).map((item, i) => (
              <NewsRow key={i} item={item} />
            ))}
          </ul>
          <div className="news-cache-time">
            {t("news.cached")}: {new Date(bundle.fetched_at * 1000).toLocaleTimeString()}
          </div>
        </>
      )}
    </div>
  );
}

function SentimentSummary({ bundle }: { bundle: NewsBundle }) {
  const { t } = useT();
  const s = bundle.aggregate_sentiment;
  const verdict = s > 12 ? "up" : s < -12 ? "down" : "flat";
  const color = verdict === "up" ? "var(--success)" : verdict === "down" ? "var(--danger)" : "var(--text-3)";
  const label = verdict === "up" ? t("news.bullish") : verdict === "down" ? t("news.bearish") : t("news.neutral");
  const expectKey = verdict === "up" ? "news.expected.up" : verdict === "down" ? "news.expected.down" : "news.expected.flat";

  // Score normalized to a 0..100 bar position
  const pos = Math.max(0, Math.min(100, (s + 100) / 2));

  return (
    <div className="news-sentiment-card">
      <div className="news-sentiment-head">
        <div>
          <div className="news-sentiment-label" style={{ color }}>{label}</div>
          <div className="news-sentiment-counts">
            <span className="dot dot-pos" /> {bundle.positive_count} {t("news.positives")}
            <span className="dot dot-neu" /> {bundle.neutral_count} {t("news.neutrals")}
            <span className="dot dot-neg" /> {bundle.negative_count} {t("news.negatives")}
          </div>
        </div>
        <div className="news-sentiment-score" style={{ color }}>
          {s > 0 ? "+" : ""}{s}
        </div>
      </div>

      {/* Gauge bar -100..+100 */}
      <div className="sentiment-gauge">
        <div className="sentiment-track" />
        <div className="sentiment-center" />
        <div className="sentiment-marker" style={{ left: `${pos}%`, background: color, boxShadow: `0 0 8px ${color}` }} />
      </div>

      <p className="news-expected">{t(expectKey)}</p>
    </div>
  );
}

function NewsRow({ item }: { item: NewsItem }) {
  const s = item.sentiment_score;
  const tag = s > 5 ? { color: "var(--success)", label: "▲" }
            : s < -5 ? { color: "var(--danger)", label: "▼" }
            : { color: "var(--text-4)", label: "•" };
  const time = item.published_epoch > 0
    ? formatRelativeTime(item.published_epoch)
    : item.published_at;

  // Strip source from title if present (Yahoo usually appends " - Source")
  const cleanTitle = item.title.replace(/ - [^-]+$/, "").trim();

  const handleOpen = (e: React.MouseEvent) => {
    e.preventDefault();
    openExternal(item.link).catch((err) => {
      console.error("failed to open news link", err);
    });
  };

  return (
    <li className="news-row">
      <span className="news-bias" style={{ color: tag.color }}>{tag.label}</span>
      <div className="news-content">
        <a
          href={item.link}
          onClick={handleOpen}
          className="news-link"
          title={item.title}
        >
          {cleanTitle}
        </a>
        <div className="news-meta">
          {item.source && <span className="news-source">{item.source}</span>}
          {item.source && time && <span className="news-dot">·</span>}
          <span className="news-time">{time}</span>
        </div>
      </div>
    </li>
  );
}

function formatRelativeTime(epochSec: number): string {
  const diff = Date.now() / 1000 - epochSec;
  if (diff < 60) return "just now";
  if (diff < 3600) return `${Math.floor(diff / 60)}m`;
  if (diff < 86400) return `${Math.floor(diff / 3600)}h`;
  if (diff < 604800) return `${Math.floor(diff / 86400)}d`;
  return new Date(epochSec * 1000).toLocaleDateString();
}
