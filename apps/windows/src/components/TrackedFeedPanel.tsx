import { useId, useState, type FormEvent } from "react";
import type { TrackedFeedStatus } from "../api";
import { useT } from "../i18n";
import { trackedFeedSymbol } from "../trackedFeed";
import "./TrackedFeedPanel.css";

interface Props {
  profile: string;
  feed: TrackedFeedStatus | null;
  unavailable: boolean;
  pending: boolean;
  writeFailed: boolean;
  onSetMember: (symbol: string, included: boolean) => void;
  onRetry: () => void;
}

export function TrackedFeedPanel({ profile, feed, unavailable, pending, writeFailed, onSetMember, onRetry }: Props) {
  const { t } = useT();
  const inputId = useId();
  const errorId = useId();
  const [raw, setRaw] = useState("");
  const [invalid, setInvalid] = useState(false);
  const symbol = trackedFeedSymbol(raw);
  const included = symbol !== null && (feed?.symbols.includes(symbol) ?? false);

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (pending || !feed?.editable) return;
    if (!symbol) {
      setInvalid(true);
      return;
    }
    onSetMember(symbol, !included);
  }

  return (
    <details className="tracked-feed" data-tracked-feed>
      <summary>
        {t("trackedFeed.title")}
        <span className="tracked-feed__scope">
          {profile.toUpperCase()}{feed ? ` · ${feed.symbols.length}` : ""}
        </span>
      </summary>
      {unavailable ? (
        <div className="tracked-feed__form">
          <p className="tracked-feed__error" role="alert" data-tracked-feed-error>{t("trackedFeed.readError")}</p>
          <button type="button" className="btn-ghost" disabled={pending} onClick={onRetry}>{t("trackedFeed.retry")}</button>
        </div>
      ) : feed === null ? (
        <p role="status">{t("trackedFeed.loading")}</p>
      ) : !feed.editable ? (
        <p>{t("trackedFeed.protected")}</p>
      ) : (
        <form className="tracked-feed__form" onSubmit={submit}>
          <label htmlFor={inputId}>{t("trackedFeed.symbol")}</label>
          <input
            id={inputId}
            data-tracked-feed-input
            value={raw}
            autoComplete="off"
            spellCheck={false}
            disabled={pending}
            aria-invalid={invalid}
            aria-describedby={invalid ? errorId : undefined}
            onChange={(event) => {
              setRaw(event.currentTarget.value);
              setInvalid(false);
            }}
          />
          <button
            type="submit"
            className="btn-ghost"
            data-tracked-feed-submit
            disabled={pending || raw.trim() === ""}
          >
            {t(pending ? "trackedFeed.applying" : included ? "trackedFeed.remove" : "trackedFeed.add")}
          </button>
          {invalid && (
            <p id={errorId} className="tracked-feed__error" role="alert" data-tracked-feed-error>
              {t("trackedFeed.invalid")}
            </p>
          )}
          {writeFailed && (
            <p className="tracked-feed__error" role="alert" data-tracked-feed-error>
              {t("trackedFeed.writeError")}
            </p>
          )}
        </form>
      )}
    </details>
  );
}
