---
id: SPEC-earnings-log-by-ticker
status: final
companions:
  - reading-surfaces.md
  - ../../project-context.md
sources:
  - ../../planning-artifacts/prd-pre-earnings-risk-gate-2026-08-27.md
---

> **Canonical contract.** This SPEC and the files in `companions:` are the complete contract for reading the earnings log by ticker. The PRD is traceability only.

# Reading the earnings log by ticker

## Why

The pre-earnings risk gate writes one record per report and offers exactly one way to read it: a single list on the Earnings tab, ordered by date. That list was written when the log held five events. It now grows with the whole universe, and it fails the reader in two places at once. Someone who wants one symbol has to scroll past every other. Someone already looking at a ticker's detail — the screen where the buy-or-trim decision actually happens — gets no sign that the log holds a priced event for that exact ticker.

Both failures cost the same thing: a captured event that nobody reads is worth nothing. This is a personal single-user Android app with no store distribution, so the only reader who matters is the one holding the phone.

## Capabilities

- **CAP-1**
  - **intent:** From the Earnings tab, the reader narrows the event list to one ticker by typing part of its symbol.
  - **success:** With events for AVGO, AMD, and LVS in the log, typing `av` leaves the AVGO cards in both sections and removes the others; clearing the field restores every card. Matching ignores letter case and matches from the start of the symbol.

- **CAP-2**
  - **intent:** A search that matches nothing tells the reader it matched nothing, and names what was searched.
  - **success:** With a non-empty log, typing `zzzz` shows a message naming `zzzz` and does not show the fresh-install empty state ("No earnings events logged yet"), which would lie about the log.

- **CAP-3**
  - **intent:** The reader can still see whether the module is running and whether the log is intact, whatever the filter is doing.
  - **success:** The last-capture line and the damaged-lines count render with a filter active, including a filter that matches nothing. Their values are read from the whole log, never from the filtered subset. A log holding only damaged lines shows the count instead of the fresh-install empty state.

- **CAP-4**
  - **intent:** Opening a ticker's detail shows that ticker's earnings event when the log holds one.
  - **success:** With an AVGO event in the log, the AVGO detail screen renders an earnings section carrying the same fields the tab's card carries. With a symbol the log has never seen, the detail renders one line naming why: beyond the capture window, inside it and still unpriced, or without a usable report date. The line waits until the log has been read; a reason asserted before that could be false. The line never repeats the date the score header already carries.

- **CAP-5**
  - **intent:** When the log holds several events for the open ticker, the detail shows the two that carry a decision.
  - **success:** Given four AVGO events, the detail renders the nearest one still to report and the most recent one already settled. A ticker with only past events renders one section; a ticker with only future events renders one section.

- **CAP-6**
  - **intent:** Reading the log never costs a network request.
  - **success:** Opening a detail issues zero calls to the option chain, the calendar, EDGAR, or the quote endpoints. Typing in the search field does the same. Neither writes to the log.

## Constraints

- Both surfaces read only. Neither triggers a chain fetch, a capture, a settlement, or a log write. `EarningsCaptureWorker` owns the schedule; a reading surface that spent a request could burn the one open-market pass that day, and an option chain is never republished.
- One event card serves both screens. The translation of bps into percentages, ratios, and position sizes lives once, in `EarningsGatePresentation`.
- The filter applies to presented rows. `EarningsGateUi.damagedLines` and `lastCapture` are properties of the whole read and must not be recomputed from a filtered list.
- Symbol matching uses plain string operations. No `Regex` in Android main sources.
- The detail earnings section renders from the `EarningsGateUi` already in dashboard state. `loadEarningsGate()` must not fire on every detail open.
- The earnings section lives inside the detail's `Snapshot` subtab. No fifth `DetailSubtab`.
- New composables carry test tags so the Compose tests can find them without depending on rendered text.

## Non-goals

- No search by company name, sector, decision cell, or risk level. Ticker only.
- No search history, no recent-searches list, no autocomplete dropdown.
- No editing, deleting, or re-capturing an event from either surface.
- No new decision logic. The cards show what the gate already decided; neither surface recomputes a ratio or a matrix cell.
- No change to the log format, the capture schedule, or the backup file.
- No second copy of the log for the detail screen.

## Success signal

On a phone holding a full universe, Juan opens AVGO's detail two days before its report and sees the priced move, the risk ratio, and the recommended size without leaving the screen he was already on. On the Earnings tab he types three letters and the list becomes the one ticker he asked about, with the capture line still telling him the module ran an hour ago.

## Assumptions

- The detail screen already knows the open ticker's symbol and can reach the dashboard state that carries the presented log.
- Sorting inside the detail follows the tab: `EarningsGateUi.upcoming` is already nearest-first and `settled` already most-recent-first, so the detail takes the first of each.
- The search field holds its query in local composable state, so it resets to empty on restart and never triggers a reload.
- The detail earnings section renders expanded. It only appears when the log holds an event for the open ticker, so it is never noise.

## Review Findings (2026-09-05)

Automatic `bmad-code-review` of PR #50. Patched in this slice:

- Capture skip retries a stale (wide) quote. An unpriced report still retries.
- Capture `today` is the New York session day.
- Same-day expiry covers a before-open report. After-close still needs the next expiry.
- Detail absence line waits until the log has been read. It also renders when the score row is missing.
- A failed earnings read writes `earningsGateNotice`.
- An 8-K/A with item 2.02 counts as a results filing.
- Zero after-close is dropped from the quiet-day series and from settlement.
- The chain underlying is the forward when it is positive.
- A halted price (0) is not cheap against fair value.

Deferred items sit in `_bmad-output/implementation-artifacts/deferred-work.md` under 2026-09-05.
