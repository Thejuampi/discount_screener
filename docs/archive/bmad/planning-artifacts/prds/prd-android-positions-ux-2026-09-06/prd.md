---
title: Android Positions UX
status: final
created: 2026-09-06
updated: 2026-09-06
---

# Android Positions UX

## Purpose and vision

Juan needs to see position exposure and research signals without opening each ticker.
The fixed app header uses space that the position list needs.
This PRD defines a compact Android book view and a header that returns on upward scroll.

## User and task

Juan is the sole user. He imports his book and checks which positions need attention.
He reads the stock total, compares position weights, and opens Detail for evidence.
He scrolls down for more rows and scrolls up to reach search and tabs.

## Terms

| Term | Meaning |
| --- | --- |
| Stock value | The market value of the imported positions with usable quotes |
| Weight | Position market value divided by the complete stock value |
| Unrealized P/L | Market value minus the imported cost basis |
| Review label | A research signal; it does not instruct a trade |
| Header | App title and actions, search, and main tab selector |

## Requirements

### Book and positions

- **FR-1:** Show stock value and unrealized P/L above the position rows. The summary scrolls with the list.
- **FR-2:** Show ticker, position value, and weight on each row. Weight always uses two decimal places.
- **FR-3:** Label the denominator as the stock portfolio. Cash remains outside this book.
- **FR-4:** Show a partial total and quote coverage when quotes are missing. Suppress all weights for incomplete totals.
- **FR-4a:** P/L uses only positions with both market value and valid cost. Its percentage uses those same positions.
  Label partial P/L and its coverage separately. Show unavailable totals when no eligible positions exist.
  Keep stored quotes visible with their stale status. Do not call stored quotes current.
- **FR-5:** Show the existing valuation stance and score with their source or model context. Preserve uncertainty and refusal reasons.
- **FR-6:** Show one review label and one main reason. Keep valuation separate from risk and position size.
- **FR-7:** Default to largest position first. Offer Needs review and Earnings soon as local sort choices.
- **FR-8:** Put shares, average cost, current price, position P/L, and weight in Detail for positions with Detail access.
- **FR-9:** Keep all positions visible. An unavailable analysis must not hide a position or invent a signal.
- **FR-10:** Move the Import book action into the Positions menu after import. Keep the empty-state import action visible.

### Scroll header

- **FR-11:** Move the header out of view on downward content scroll. Reclaim its layout space.
- **FR-12:** Return the full header after a small upward scroll, even far from the list top.
- **FR-13:** Ignore tiny direction changes. Keep search visible while it has focus or an active search state.
- **FR-14:** Show the header on tab change, return from Detail, and a fresh screen entry.
- **FR-15:** Apply the behavior to vertical content across main tabs. Horizontal gestures must not hide the header.
- **FR-16:** Keep Detail navigation visible. Short and empty content must retain access to the header.

## Acceptance bar

Automated examples cover complete and partial books, fractional shares, stale evidence, and each review label.
UI tests cover narrow layouts, large text, sort choices, import access, and Detail position context.
Scroll tests cover downward hide, upward return, focus, tab changes, horizontal gestures, and short content.
Tests use offline inputs. The final debug APK must build and retain the existing application identity.
Device QA remains separate until Juan requests live QA.

## Constraints

Use fixed-point quantities and currency. Do not change valuation engines, score rules, or provider behavior.
Show sparse data explicitly. Keep model uncertainty visible in the compact row.
Use existing cached data. This change must not expand the active feed or start provider work during composition.
Preserve import semantics, stored lots, and the debug signature.
Support 320 dp width and larger text through vertical expansion rather than clipped facts.

## Scope decisions

Use a compact three-line row at normal text size. Allow extra height for accessibility and missing-data reasons.
Use Review opportunity, Monitor, Review position, and Check data as research labels.
Review opportunity means the current research signal merits inspection. It does not propose a larger position.
Monitor means the current Watch signal merits observation. Review position means the current Avoid signal needs inspection.
Check data takes precedence when evidence is missing, stale, disputed, provisional, or otherwise untrusted.
The labels never mean Add, Hold, or Sell. No position-size recommendation enters this change.
For positions without Detail access, show shares and average cost through a local row expansion.

## Documentation deliverables

Update `docs/advisor-csv-import.md`, `docs/cross-platform-parity.md`, and `_bmad-output/project-context.md` with the final behavior.
Update the applicable Positions statements in `AGENTS.md` and the import contract if they describe the old behavior.
Add an operator guide for the header, row fields, data gaps, and research labels. Link it from `docs/index.md`.
Record new operational failure shapes in `docs/operational-anti-patterns.md` if the work discovers them.

## Non-goals

No trade execution, cash import, portfolio target weights, concentration thresholds, new risk score, or Windows changes.
No new market provider or off-feed network fetch. No change to the existing valuation stance policy.

## Assumptions and deferred questions

Android is the target, based on the inspected Positions screen and Juan's accepted discussion.
Juan delegated routine design decisions. This document uses that authority for sort and missing-data behavior.
Cash-inclusive weights and trade decisions remain future work when Juan supplies the required portfolio rules.
