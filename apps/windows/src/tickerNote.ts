type NoteAction = "save" | "delete";
type NoteNotice = "saved" | "deleted";

type ReadyNote = {
  kind: "ready";
  saved: string | null;
  draft: string;
  pending: NoteAction | null;
  error: NoteAction | null;
  notice: NoteNotice | null;
};

export type TickerNoteState = { kind: "loading" | "readFailed" } | ReadyNote;

export const INITIAL_TICKER_NOTE: TickerNoteState = { kind: "loading" };

export function tickerNoteValue(draft: string): string | null {
  // Match the native trim from the first through the last non-whitespace character.
  return draft.match(/\P{White_Space}(?:.*\P{White_Space})?/su)?.[0] ?? null;
}

function readyNote(saved: string | null, notice: NoteNotice | null = null): ReadyNote {
  return { kind: "ready", saved, draft: saved ?? "", pending: null, error: null, notice };
}

export function createTickerNoteEditor({
  read,
  write,
  publish,
}: {
  read: () => Promise<string | null>;
  write: (note: string | null) => Promise<void>;
  publish: (state: TickerNoteState) => void;
}) {
  let state: TickerNoteState = INITIAL_TICKER_NOTE;
  let generation = 0;

  function update(next: TickerNoteState) {
    state = next;
    publish(next);
  }

  async function open() {
    const request = ++generation;
    update(INITIAL_TICKER_NOTE);
    try {
      const saved = await read();
      if (request === generation) update(readyNote(saved));
    } catch {
      if (request === generation) update({ kind: "readFailed" });
    }
  }

  function edit(draft: string) {
    if (state.kind !== "ready" || state.pending) return;
    update({ ...state, draft, error: null, notice: null });
  }

  async function persist(action: NoteAction) {
    if (state.kind !== "ready" || state.pending) return;
    const prior = state;
    const note = action === "delete" ? null : tickerNoteValue(prior.draft);
    if (note === prior.saved) return;

    const request = generation;
    const pending = note === null ? "delete" : "save";
    update({ ...prior, pending, error: null, notice: null });
    try {
      await write(note);
    } catch {
      if (request === generation) update({ ...prior, pending: null, error: pending, notice: null });
      return;
    }
    if (request === generation) update(readyNote(note, note === null ? "deleted" : "saved"));
  }

  return {
    open,
    close: () => { generation += 1; },
    edit,
    save: () => persist("save"),
    remove: () => persist("delete"),
  };
}
