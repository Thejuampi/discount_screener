import { useEffect, useId, useMemo, useState } from "react";
import { api } from "../api";
import { useT } from "../i18n";
import { createTickerNoteEditor, INITIAL_TICKER_NOTE, tickerNoteValue } from "../tickerNote";
import "./TickerNoteEditor.css";

export function TickerNoteEditor({ symbol }: { symbol: string }) {
  const { t } = useT();
  const headingId = useId();
  const [state, setState] = useState(INITIAL_TICKER_NOTE);
  const editor = useMemo(() => createTickerNoteEditor({
    read: () => api.getTickerNote(symbol),
    write: (note) => api.setTickerNote(symbol, note),
    publish: setState,
  }), [symbol]);

  useEffect(() => {
    void editor.open();
    return editor.close;
  }, [editor]);

  const value = state.kind === "ready" ? tickerNoteValue(state.draft) : null;
  const changed = state.kind === "ready" && value !== state.saved;
  let status = "";
  if (state.kind === "loading") status = t("tickerNote.loading");
  if (state.kind === "ready") {
    if (state.pending) status = t(`tickerNote.pending.${state.pending}`);
    else if (state.notice) status = t(`tickerNote.notice.${state.notice}`);
    else if (changed && !state.error) {
      status = t(value === null ? "tickerNote.blank" : "tickerNote.unsaved");
    }
  }

  return (
    <section className="ticker-note" data-ticker-note aria-labelledby={headingId}>
      <h2 id={headingId}>{t("tickerNote.heading")}</h2>
      {state.kind === "readFailed" && (
        <div className="ticker-note__actions">
          <p className="ticker-note__error" data-ticker-note-error role="alert">
            {t("tickerNote.error.read")}
          </p>
          <button className="btn-ghost" type="button" data-ticker-note-retry onClick={() => void editor.open()}>
            {t("tickerNote.retry")}
          </button>
        </div>
      )}
      {state.kind === "ready" && (
        <>
          <textarea
            className="ticker-note__input"
            data-ticker-note-input
            aria-labelledby={headingId}
            rows={3}
            readOnly={state.pending !== null}
            value={state.draft}
            onChange={(event) => editor.edit(event.currentTarget.value)}
          />
          <div className="ticker-note__actions">
            <button
              className="btn-primary"
              type="button"
              data-ticker-note-save
              disabled={state.pending !== null || !changed}
              onClick={() => void editor.save()}
            >
              {t("tickerNote.save")}
            </button>
            {state.saved !== null && (
              <button
                className="btn-ghost ticker-note__delete"
                type="button"
                data-ticker-note-delete
                disabled={state.pending !== null}
                onClick={() => void editor.remove()}
              >
                {t("tickerNote.delete")}
              </button>
            )}
          </div>
          {state.error && (
            <p className="ticker-note__error" data-ticker-note-error role="alert">
              {t(`tickerNote.error.${state.error}`)}
            </p>
          )}
        </>
      )}
      <p className="ticker-note__status" role="status">{status}</p>
    </section>
  );
}
