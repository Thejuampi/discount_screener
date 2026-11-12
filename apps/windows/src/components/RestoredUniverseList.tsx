import { useMemo } from "react";
import { fmt, type SavedUniverseRow } from "../api";
import { useT } from "../i18n";
import "./RestoredUniverseList.css";

interface Props {
  rows: readonly SavedUniverseRow[];
  selectedSymbol: string | null;
  onSelect: (symbol: string) => void;
}

export function RestoredUniverseList({ rows, selectedSymbol, onSelect }: Props) {
  const { lang, t } = useT();
  const dateFormat = useMemo(
    () => new Intl.DateTimeFormat(lang, { dateStyle: "short", timeStyle: "short" }),
    [lang],
  );

  return (
    <section className="restored-universe" aria-labelledby="restored-universe-heading">
      <h2 id="restored-universe-heading">{t("restored.rows")}</h2>
      <table>
        <thead>
          <tr>
            <th scope="col">{t("col.symbol")}</th>
            <th scope="col">{t("col.company")}</th>
            <th scope="col" className="restored-universe__value">{t("col.price")}</th>
            <th scope="col" className="restored-universe__value">{t("col.target")}</th>
            <th scope="col" className="restored-universe__value">{t("restored.saved")}</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => {
            const savedAt = new Date(row.saved_at_epoch_seconds * 1_000);
            return (
              <tr key={row.symbol} data-row-source="restored">
                <th scope="row">
                  <button
                    type="button"
                    className="restored-universe__ticker"
                    aria-pressed={selectedSymbol === row.symbol}
                    onClick={() => onSelect(row.symbol)}
                  >
                    {row.symbol}
                  </button>
                </th>
                <td>{row.company_name ?? "—"}</td>
                <td className="restored-universe__value">{fmt.dollars(row.market_price_cents)}</td>
                <td className="restored-universe__value">
                  {row.target_price_cents === null ? "—" : fmt.dollars(row.target_price_cents)}
                </td>
                <td className="restored-universe__value">
                  <time dateTime={savedAt.toISOString()}>{dateFormat.format(savedAt)}</time>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </section>
  );
}
