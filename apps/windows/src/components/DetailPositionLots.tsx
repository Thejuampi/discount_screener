import { useId } from "react";
import { fmt } from "../api";
import type { DetailPosition } from "../detailPosition";
import { useT } from "../i18n";
import "./DetailPositionLots.css";

const signed = (value: number) => value > 0 ? "+" : value < 0 ? "−" : "";

export function DetailPositionLots({ position }: { position: DetailPosition }) {
  const { t } = useT();
  const headingId = useId();
  const { lots, quoteCoverage, weightAvailable } = position;

  return (
    <section className="detail-position-lots">
      <h2 id={headingId}>{t("detail.positionLots")}</h2>
      <div className="detail-position-lots__scroll" role="region" aria-labelledby={headingId} tabIndex={0}>
        <table>
          <thead>
            <tr>
              <th scope="col">{t("detail.positionLot")}</th>
              <th scope="col">{t("advisor.col.qty")}</th>
              <th scope="col">{t("detail.positionAverageCost")}</th>
              <th scope="col">{t("detail.positionSessionPrice")}</th>
              <th scope="col">{t("detail.positionUnrealizedPnl")}</th>
              <th scope="col">{t("advisor.col.weight")}</th>
            </tr>
          </thead>
          <tbody>
            {lots.map((lot, index) => (
              <tr key={lot.id} data-position-lot={lot.id}>
                <th scope="row">
                  {t("detail.positionLotNumber", { number: index + 1 })}
                  {lot.openedAt && (
                    <time className="detail-position-lots__secondary" dateTime={lot.openedAt}>{lot.openedAt}</time>
                  )}
                </th>
                <td>{lot.quantity}</td>
                <td>{lot.unitCostCents == null ? t("detail.positionNoCost") : fmt.dollars(lot.unitCostCents)}</td>
                <td>{lot.priceCents == null ? "—" : fmt.dollars(lot.priceCents)}</td>
                <td>
                  {lot.pnlOutOfRange
                    ? <span className="detail-position-lots__secondary">{t("detail.positionOutOfRange")}</span>
                    : lot.pnlCents == null ? "—" : `${signed(lot.pnlCents)}${fmt.dollars(Math.abs(lot.pnlCents))}`}
                  {lot.pnlPct != null && (
                    <span className="detail-position-lots__secondary">
                      ({signed(lot.pnlPct)}{Math.abs(lot.pnlPct).toFixed(2)}%)
                    </span>
                  )}
                </td>
                <td>{lot.weightPct == null ? "—" : `${lot.weightPct.toFixed(2)}%`}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {lots.some((lot) => lot.priceCents == null) && (
        <p>{t("detail.positionNoSessionPrice")}</p>
      )}
      {lots.some((lot) => lot.pnlCents != null && lot.pnlPct == null) && (
        <p>{t("detail.positionPnlPercentUnavailable")}</p>
      )}
      {!weightAvailable && (
        <p>
          {t("detail.positionWeightUnavailable")}{" "}
          {t("advisor.coverage.value", { eligible: quoteCoverage.eligibleLots, total: quoteCoverage.totalLots })}
          {quoteCoverage.rangeExcludedLots > 0 && (
            <> · {t("advisor.coverage.rangeExcluded", { count: quoteCoverage.rangeExcludedLots })}</>
          )}
          {quoteCoverage.kind === "outOfRange" && <> · {t("advisor.coverage.outOfRange")}</>}
        </p>
      )}
    </section>
  );
}
