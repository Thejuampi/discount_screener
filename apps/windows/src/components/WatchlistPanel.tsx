import { useId, useMemo } from "react";
import { fmt, type OpportunityRow } from "../api";
import { useT } from "../i18n";
import { usableQuoteCents } from "../portfolioExposure";
import type { WatchlistStatus } from "../watchlist";
import "./WatchlistPanel.css";

interface Props {
  status: WatchlistStatus;
  symbols: readonly string[];
  sessionRows: readonly OpportunityRow[];
  selectedSymbol: string | null;
  onSelect: (symbol: string) => void;
  onRetry: () => void;
}

export function WatchlistPanel({ status, symbols, sessionRows, selectedSymbol, onSelect, onRetry }: Props) {
  const { t } = useT();
  const headingId = useId();
  const quotes = useMemo(() => new Map(sessionRows.map((row) => [row.symbol, row])), [sessionRows]);

  return (
    <section className="watchlist-panel" data-watchlist-view aria-labelledby={headingId}>
      <h2 id={headingId}>{t("view.watchlist")}</h2>
      {status === "loading" && <p role="status">{t("watchlist.loading")}</p>}
      {status === "unavailable" && (
        <div className="watchlist-panel__read-error">
          <p role="status" data-watchlist-error>{t("watchlist.readError")}</p>
          <button type="button" className="btn-ghost" onClick={onRetry}>{t("watchlist.retry")}</button>
        </div>
      )}
      {status === "ready" && (symbols.length === 0 ? (
        <p>{t("watchlist.empty")}</p>
      ) : (
        <table>
          <thead>
            <tr>
              <th scope="col">{t("col.symbol")}</th>
              <th scope="col" className="watchlist-panel__price">{t("detail.positionSessionPrice")}</th>
            </tr>
          </thead>
          <tbody>
            {symbols.map((symbol) => {
              const quote = quotes.get(symbol);
              const price = usableQuoteCents(quote?.market_price_cents);
              return (
                <tr key={symbol}>
                  <th scope="row">
                    <button
                      type="button"
                      className="watchlist-panel__ticker"
                      data-watchlist-symbol={symbol}
                      aria-pressed={selectedSymbol === symbol}
                      onClick={() => onSelect(symbol)}
                    >
                      {symbol}
                    </button>
                    {quote?.company_name && (
                      <span className="watchlist-panel__company">{quote.company_name}</span>
                    )}
                  </th>
                  <td className="watchlist-panel__price">
                    {price === null ? t("watchlist.noSessionPrice") : fmt.dollars(price)}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      ))}
    </section>
  );
}
