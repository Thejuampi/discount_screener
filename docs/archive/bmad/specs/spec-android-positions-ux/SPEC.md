---
id: SPEC-android-positions-ux
status: done
route: dispatch
baseline_commit: db23aa885f875ca795eb5372c60c3ac3ca1dec73
companions:
  - architecture.md
  - examples.md
  - ../../project-context.md
sources:
  - ../../planning-artifacts/prds/prd-android-positions-ux-2026-09-06/prd.md
---

# Android Positions UX

## Why

Juan needs position exposure and research signals in limited phone space.
The current fixed header and shared opportunity badges reduce that space.
The book must show useful totals without hiding incomplete data.

## Capabilities

- **CAP-1**
  - **intent:** Juan can read stock value, unrealized P/L, position values, and stock portfolio weights.
  - **success:** Complete books show weights with two decimals. Partial books show coverage and unavailable weights.
- **CAP-2**
  - **intent:** Juan can compare compact research signals and choose a useful sort order.
  - **success:** Rows show valuation source, existing score, review label, and a reason. Every lot remains visible.
- **CAP-3**
  - **intent:** Juan can inspect shares, cost, price, P/L, and weight without crowding the list.
  - **success:** Detail shows the selected position. Off-feed rows reveal local facts without opening Detail or starting work.
- **CAP-4**
  - **intent:** Juan can reclaim header space and recover search and tabs from any scroll position.
  - **success:** Downward content scroll hides the complete header. Upward scroll returns it without resetting the list.
- **CAP-5**
  - **intent:** Juan can import his book and read every field on a narrow screen.
  - **success:** The populated Positions menu retains import. Empty Positions retains its visible import action.
    Layout tests cover 320 dp width and increased font scale.

## Constraints

- Use Core for portfolio arithmetic and research eligibility. Compose renders prepared state.
- Keep integer currency and quantity scales. Use exact intermediate arithmetic and explicit unavailable values.
- P/L uses market value and cost from the same eligible lots. Its coverage can differ from quote coverage.
- Weights require every lot to have usable value and a positive total. Cash stays outside the denominator.
- Stored quotes can contribute with explicit non-current disclosure. They cannot support a current research label.
- Preserve current valuation stances and score rules. Never silently label an analyst anchor as a model valuation.
- Review opportunity means inspect the research. No label sets a trade or a position size.
- Reuse the earnings calendar and Closeness owners. Sort and header changes start no provider work.
- Preserve current import, storage, feed membership, and off-feed Detail behavior.
- Reclaim measured header space. Keep list state and row identity stable through header motion.
- Pin the header for search focus, active search, and touch exploration. Reveal it on tab change and screen entry.
- Keep Detail navigation fixed. Ignore horizontal gestures and programmatic content movement.
- Complete the tests and documentation in the companions. Device QA remains Not run until Juan requests it.

## Non-goals

- No cash import, currency conversion, target weights, trade instructions, new provider, or Windows change.
- No valuation engine, classifier, ranking policy, or earnings gate change.
- No device install, data wipe, signed release APK, or live provider test.

## Success signal

Offline examples reproduce correct exposure, honest missing-data states, compact rows, and header recovery.
The complete Android suites execute and the final debug APK builds with the existing application identity.
The final report separates automated acceptance from device paths that did not run.

## Assumptions

Android is the requested surface. Juan delegated routine design decisions to the parent agent.
The existing supported book and dollar quote convention remain in use; no new currency support enters this change.

## Review Triage Log

| Finding | Verdict | Evidence and route |
| --- | --- | --- |
| B1 | medium | Existing tracked prices never reach the new quote input. Patch both projection calls. |
| B2 | medium | Warm prices can coexist with retained research. Show unconfirmed quote age when age is unknown. |
| B3 | medium | The raw sum includes overflowed public values. Sum the eligible rows. |
| B4 | medium | Complete input coverage can coexist with aggregate overflow. Add the explicit total refusal reason. |
| B5 | low | Invalid prices retain their current flag. Clear invalid quote status. |
| B6 | medium | Priced and stored rows lack full narrow-text checks. Add those checks and flexible score layout. |
| B7 | medium | Conditional composition resets local sort state. Retain it through Detail and tab navigation. |
| B8 | low | The outer import menu item has an empty click handler. Remove the dead wrapper. |
| B9 | medium | Missing evidence maps to Stored evidence. Give missing analysis its own reason. |
| B10 | medium | Infinite header measurement ignores the parent height limit. Bound the viewport and retain access to controls. |
| E1 | medium | Independent confirmation of B3. Apply the same eligible-row sum patch. |
| G1 | medium | Provider-error input never enters a relevant test. Add error and blank-error projection tests. |
| F1 | medium | Two full-suite display checks fail on the initial viewport. Remove empty totals and compact the phone summary. |

All findings route to bounded patches. See final-review.md for the evidence and final checks.
