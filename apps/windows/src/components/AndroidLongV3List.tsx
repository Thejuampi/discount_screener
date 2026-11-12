import { useState } from "react";
import { fmt, type AndroidLongV3Incomplete, type AndroidLongV3Row, type AndroidLongV3Status } from "../api";
import { useT } from "../i18n";
import "./AndroidLongV3List.css";

interface Props {
  status: AndroidLongV3Status | null;
  rows: readonly AndroidLongV3Row[];
  incomplete: readonly AndroidLongV3Incomplete[];
  failed: boolean;
  selectedSymbol: string | null;
  onSelect: (symbol: string) => void;
}

const CONFIDENCE_KEYS = {
  high: "conf.High",
  provisional: "conf.Provisional",
  low: "conf.Low",
} as const;

const INCOMPLETE_PAGE_SIZE = 40;

export function AndroidLongV3List({ status, rows, incomplete, failed, selectedSymbol, onSelect }: Props) {
  const { t } = useT();
  const [showIncomplete, setShowIncomplete] = useState(false);
  const [incompleteLimit, setIncompleteLimit] = useState(INCOMPLETE_PAGE_SIZE);

  const ticker = (symbol: string, company: string | null) => (
    <>
      <button type="button" aria-pressed={selectedSymbol === symbol} onClick={() => onSelect(symbol)}>
        {symbol}
      </button>
      {company && <span className="android-v3__secondary">{company}</span>}
    </>
  );

  return (
    <section className="android-v3" data-android-long-v3 aria-label="Android Long V3">
      {failed && <p role="status" className="android-v3__issue">{t("androidV3.readFailed")}</p>}
      {!status && !failed && <p role="status">{t("androidV3.loading")}</p>}
      {status && (
        <>
          <p>{t("androidV3.evaluated", { count: status.evaluated, total: status.eligible })}</p>
          {rows.length === 0 ? <p>{t("androidV3.empty")}</p> : (
            <div className="android-v3__scroll" tabIndex={0} role="region" aria-label={t("androidV3.ranking")}>
              <table>
                <thead>
                  <tr>
                    <th className="market-ordinal" scope="col" title={t("markets.visiblePosition")}>#</th>
                    <th scope="col">{t("col.symbol")}</th>
                    <th scope="col">{t("col.price")}</th>
                    <th scope="col">{t("androidV3.anchor")}</th>
                    <th scope="col">{t("androidV3.upside")}</th>
                    <th scope="col">{t("col.score")}</th>
                    <th scope="col">{t("androidV3.confidence")}</th>
                    <th scope="col">{t("col.decision")}</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((row, index) => (
                    <tr key={row.symbol} data-android-v3-row={row.symbol}>
                      <td className="market-ordinal" data-market-ordinal>{index + 1}</td>
                      <th scope="row">{ticker(row.symbol, row.company)}</th>
                      <td>{fmt.dollars(row.price_cents)}</td>
                      <td>
                        {row.projection_gap ? (
                          <span className="android-v3__issue" data-android-v3-projection-gap>
                            {t("androidV3.incompleteValuation")}
                          </span>
                        ) : row.anchor_cents === null ? t("androidV3.anchor.unavailable") : (
                          <>
                            {fmt.dollars(row.anchor_cents)}
                            <span className="android-v3__secondary">{t(`androidV3.anchor.${row.anchor}`)}</span>
                          </>
                        )}
                      </td>
                      <td>{fmt.gap(row.upside_bps)}</td>
                      <td>
                        {row.composite_score}
                        <span className="android-v3__secondary">
                          {t("androidV3.coverage", { count: row.coverage })}
                        </span>
                        {row.technical === null && (
                          <span className="android-v3__secondary">{t("androidV3.noWeeklySignal")}</span>
                        )}
                      </td>
                      <td>{t(CONFIDENCE_KEYS[row.confidence])}</td>
                      <td>
                        {row.decision ?? "—"}
                        {row.trust_signal && (
                          <span className="android-v3__secondary">{t(`androidV3.trust.${row.trust_signal}`)}</span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
          {incomplete.length > 0 && (
            <details data-android-v3-incomplete onToggle={(event) => setShowIncomplete(event.currentTarget.open)}>
              <summary>{t("androidV3.incomplete", { count: incomplete.length })}</summary>
              {showIncomplete && (
                <>
                  <ul>
                    {incomplete.slice(0, incompleteLimit).map((row) => (
                      <li key={row.symbol}>
                        <div>{ticker(row.symbol, row.company)}</div>
                        <span>{t(`androidV3.reason.${row.reason}`)}</span>
                      </li>
                    ))}
                  </ul>
                  {incompleteLimit < incomplete.length && (
                    <button type="button" className="btn-ghost" onClick={() => setIncompleteLimit((limit) => limit + INCOMPLETE_PAGE_SIZE)}>
                      {t("androidV3.showMore")}
                    </button>
                  )}
                </>
              )}
            </details>
          )}
        </>
      )}
    </section>
  );
}
