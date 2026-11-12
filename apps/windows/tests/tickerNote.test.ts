import assert from "node:assert/strict";
import test from "node:test";
import { createTickerNoteEditor, INITIAL_TICKER_NOTE, tickerNoteValue, type TickerNoteState } from "../src/tickerNote.ts";

function editor(read: () => Promise<string | null>, write: (note: string | null) => Promise<void>) {
  let state: TickerNoteState = INITIAL_TICKER_NOTE;
  const model = createTickerNoteEditor({ read, write, publish: (next) => { state = next; } });
  return {
    ...model,
    state: () => state,
    ready: () => {
      assert.equal(state.kind, "ready");
      return state;
    },
  };
}

test("restores a note and saves an edited multiline draft with outer whitespace removed", async () => {
  const writes: (string | null)[] = [];
  const note = editor(async () => "Previous note", async (value) => { writes.push(value); });
  await note.open();
  assert.equal(note.ready().draft, "Previous note");
  await note.save();
  assert.deepEqual(writes, []);
  note.edit("  Próximo balance\n  Confirmar margen.  ");
  await note.save();
  assert.deepEqual(writes, ["Próximo balance\n  Confirmar margen."]);
  assert.equal(note.ready().draft, "Próximo balance\n  Confirmar margen.");
  assert.equal(note.ready().notice, "saved");
});

test("uses the same Unicode boundary whitespace as native note storage", () => {
  assert.equal(tickerNoteValue("\u0085Texto\u0085"), "Texto");
  assert.equal(tickerNoteValue("\u0085"), null);
  assert.equal(tickerNoteValue("\uFEFFTexto\uFEFF"), "\uFEFFTexto\uFEFF");
});

test("explicit delete and a blank save both remove an existing note", async () => {
  for (const action of ["remove", "save"] as const) {
    const writes: (string | null)[] = [];
    const note = editor(async () => "Previous note", async (value) => { writes.push(value); });
    await note.open();
    note.edit(" \n ");
    await note[action]();
    assert.deepEqual(writes, [null]);
    assert.equal(note.ready().saved, null);
    assert.equal(note.ready().draft, "");
    assert.equal(note.ready().notice, "deleted");
  }
});

test("a failed save preserves the draft and the prior saved value for retry", async () => {
  let fail = true;
  const note = editor(async () => "Saved", async () => {
    if (fail) throw new Error("write failed");
  });
  await note.open();
  note.edit("Draft");
  await note.save();
  assert.equal(note.ready().error, "save");
  assert.equal(note.ready().draft, "Draft");
  assert.equal(note.ready().saved, "Saved");
  fail = false;
  await note.save();
  assert.equal(note.ready().saved, "Draft");
  assert.equal(note.ready().error, null);
});

test("an unread note cannot be overwritten and retry restores the saved value", async () => {
  let fail = true;
  let writes = 0;
  const note = editor(async () => {
    if (fail) throw new Error("read failed");
    return "Saved";
  }, async () => { writes += 1; });
  await note.open();
  assert.equal(note.state().kind, "readFailed");
  note.edit("Overwrite");
  await note.save();
  await note.remove();
  assert.equal(writes, 0);
  fail = false;
  await note.open();
  assert.equal(note.ready().draft, "Saved");
});

test("disables concurrent actions and preserves the draft after a failed delete", async () => {
  let rejectWrite!: () => void;
  let writes = 0;
  const write = new Promise<void>((_, reject) => { rejectWrite = reject; });
  const note = editor(async () => "Saved", () => {
    writes += 1;
    return write;
  });
  await note.open();
  note.edit("Draft");
  const remove = note.remove();
  assert.equal(note.ready().pending, "delete");
  note.edit("Unexpected");
  await note.save();
  assert.equal(writes, 1);
  rejectWrite();
  await remove;
  assert.equal(note.ready().error, "delete");
  assert.equal(note.ready().draft, "Draft");
  assert.equal(note.ready().saved, "Saved");
});

test("ignores a read that finishes after its editor closes", async () => {
  let resolveRead!: (value: string) => void;
  const read = new Promise<string>((resolve) => { resolveRead = resolve; });
  const note = editor(() => read, async () => {});
  const opening = note.open();
  note.close();
  resolveRead("Old note");
  await opening;
  assert.equal(note.state().kind, "loading");
});
