# Android Positions

Positions shows the imported stock book. Cash and sweep balances remain outside this view.

## Read the book

| Field | Meaning |
| --- | --- |
| Stock value | Quantity times the available quote, summed across eligible lots |
| Unrealized P/L | Market value minus cost basis for the same eligible lots |
| Stock portfolio weight | Position value divided by complete stock value, with two decimal places |
| Score | The existing score, with the selected score model |
| Valuation | The existing primary source compared with price, or its uncertainty state |

The app uses available tracked quotes even when research is missing.
It shows quote status Unconfirmed when the available evidence cannot confirm the quote as current.
Research freshness stays separate. An old score carries the Stored marker.
A partial stock total shows quote coverage. Every weight stays unavailable until the denominator is complete.
P/L has separate coverage. Missing cost can prevent P/L while market value remains available.
P/L percent uses the cost basis of the same eligible lots. Zero cost has no P/L percentage.
An unavailable total means the app lacks usable inputs. It does not mean the book has no value.
If a total exceeds the supported range, the app shows that reason and keeps its input coverage.

The default sort shows the largest position first. The menu also offers Needs review and Earnings soon.
Needs review puts Check data first, followed by Review position, Monitor, and Review opportunity.
Earnings soon uses the existing New York session-day categories. Missing dates come last.
Every sort retains every lot. Ties use value, symbol, and the lot's input order.
The selected sort remains when you return from Detail or another tab.

## Read the research labels

| Label | Meaning |
| --- | --- |
| Review opportunity | Current usable research has an Act signal |
| Monitor | Current usable research has a Watch signal |
| Review position | Current usable research has an Avoid signal |
| Check data | Evidence is absent, non-current, disputed, provisional, or otherwise untrusted |

These labels do not set a trade or position size.
Model valuation and analyst comparisons keep separate source labels.
Tension, Disputed, and Unavailable remain visible. A price comparison is not a risk score.
The row shows a reason. Detail retains the full valuation evidence and score explanation.

## Inspect a position

Open a scored row to use Detail. Its Snapshot keeps the earnings card first and shows the position facts afterward.
The position block uses the same book snapshot as Positions, including its denominator and quote status.
If more than one lot matches the ticker, Detail shows each lot.

Use the local facts control to show shares, average cost, price, P/L, and weight.
This control also works for an off-feed position. It does not open Detail or start a provider request.

Open the Positions menu to import another book. Empty Positions keeps a direct Import book action.
Empty Positions omits the totals and sort menu.
The import confirmation and storage rules stay in [Advisor CSV import](advisor-csv-import.md).

## Use the dashboard header

Scroll content down to hide the title, search, and tabs. Their layout space becomes available to the content.
Scroll up to restore the complete header from any list position.
Small movements below eight dp do not change its state. Horizontal gestures do not hide it.

Search focus, active search, dialogs, and touch exploration keep the header visible.
Use Back to leave search and release its focus.
A tab change or return from Detail shows it again. Detail keeps its own navigation visible.
Short content retains the header. After content shrinks, scroll toward earlier items to restore a hidden header.
Continued downward scroll at the list bottom keeps the header hidden.
On a short viewport, scroll within the header to reach its controls.

## Scope

This behavior applies to Android. It does not add cash, currency conversion, providers, or trade instructions.
The app keeps the existing supported dollar book convention.
The debug APK is the normal personal delivery artifact.
