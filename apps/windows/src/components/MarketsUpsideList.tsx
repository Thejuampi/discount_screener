import { fmt } from "../api";
import { useT } from "../i18n";
import type { MarketUpsideRow } from "../marketUpside";
import "./MarketsUpsideList.css";

interface Props {
  rows: readonly MarketUpsideRow[];
  status: "loading" | "ready" | "unavailable";
  selectedSymbol: string | null;
  onSelect: (symbol: string) => void;
  onRetry: () => void;
}

function formatUpside(bps: number): string {
  const sign = bps < 0 ? "-" : bps > 0 ? "+" : "";
  const magnitude = Math.abs(bps);
  const whole = Math.floor(magnitude / 100);
  const fraction = String(magnitude % 100).padStart(2, "0");
  return `${sign}${whole}.${fraction}%`;
}

export function MarketsUpsideList({ rows, status, selectedSymbol, onSelect, onRetry }: Props) {
  const { t } = useT();

  return (
    <section className="markets-upside" data-markets-upside>
      {status === "loading" && <p role="status">{t("markets.upsideLoading")}</p>}
      {status === "unavailable" && (
        <div className="markets-upside__retry">
          <p role="status">{t("markets.upsideUnavailable")}</p>
          <button type="button" className="btn-ghost" onClick={onRetry}>{t("trackedFeed.retry")}</button>
        </div>
      )}
      {status === "ready" && (rows.length === 0 ? (
        <p role="status">{t("markets.upsideEmpty")}</p>
      ) : (
        <div className="markets-upside__scroll" tabIndex={0} role="region" aria-label={t("markets.upside")}>
          <table>
            <thead>
              <tr>
                <th className="market-ordinal" scope="col" title={t("markets.visiblePosition")}>#</th>
                <th scope="col">{t("col.symbol")}</th>
                <th className="markets-upside__amount" scope="col">{t("detail.positionSessionPrice")}</th>
                <th className="markets-upside__amount" scope="col">{t("detail.analystTarget")}</th>
                <th className="markets-upside__amount" scope="col" title={t("markets.upsideHint")}>
                  {t("markets.upside")}
                </th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row, index) => (
                <tr key={row.symbol} data-upside-symbol={row.symbol}>
                  <td className="market-ordinal" data-market-ordinal>{index + 1}</td>
                  <th scope="row">
                    <button
                      type="button"
                      className="markets-upside__ticker"
                      aria-pressed={selectedSymbol === row.symbol}
                      onClick={() => onSelect(row.symbol)}
                    >
                      {row.symbol}
                    </button>
                    {row.companyName && <span className="markets-upside__company">{row.companyName}</span>}
                  </th>
                  <td className="markets-upside__amount">
                    {row.priceCents === null ? (
                      <span className="markets-upside__missing">{t("markets.upsideNoQuote")}</span>
                    ) : fmt.dollars(row.priceCents)}
                  </td>
                  <td className="markets-upside__amount">
                    {row.targetCents === null ? (
                      <span className="markets-upside__missing">{t("markets.upsideNoTarget")}</span>
                    ) : fmt.dollars(row.targetCents)}
                  </td>
                  <td className="markets-upside__amount">
                    {row.upsideBps === null ? (
                      <span className="markets-upside__missing">{t("markets.upsideNoValue")}</span>
                    ) : formatUpside(row.upsideBps)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ))}
    </section>
  );
}
