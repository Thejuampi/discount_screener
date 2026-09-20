# Final code review

Round 1 covers the 174,410-byte task diff against the saved pre-build workspace.
Three independent reviewers checked adversarial issues, edge cases, and test gaps.
Every reviewer finished before triage started.

## Findings and decisions

| ID | Lens | Finding | Verdict | Evidence and route |
| --- | --- | --- | --- | --- |
| B1 | Blind | Tracked prices without scores never reach Positions | medium | TrackedSymbolRow has marketPriceCents. Both ViewModel callers omit quote overrides. Patch the existing projection calls. |
| B2 | Blind | Research freshness cannot prove quote age | medium | The warm-price path updates prices while retaining research freshness. Patch disclosure to say age unconfirmed where provenance cannot prove age. |
| B3 | Blind | An overflowed lot suppresses the eligible subtotal | medium | rawTotal includes every raw product, while coverage counts only public values. Patch the sum to use eligible rows. |
| B4 | Blind | Aggregate overflow has no refusal reason | medium | Complete input coverage can accompany an unavailable public total. Keep input coverage and add the overflow reason. |
| B5 | Blind | Invalid quotes retain Current status | low | A zero quote has no price but retains isCurrent. Clear the status when the quote fails validation. |
| B6 | Blind | Priced and stored rows lack narrow-layout coverage | medium | Existing overflow assertions cover only the reason on an unpriced row. Add priced cases and flexible valuation/score layout. |
| B7 | Blind | Detail and tab navigation reset sort selection | medium | Conditional composition discards the local saved state. Retain that state through navigation and test both returns. |
| B8 | Blind | Part of the import menu row does nothing | low | An empty outer click handler surrounds the real button. Remove that outer item and keep one full-width control. |
| B9 | Blind | Missing analysis claims stored evidence exists | medium | The freshness branch combines Missing and Stored. Give Missing its own reason and test a quote without analysis. |
| B10 | Blind | The header ignores a short parent's height limit | medium | The custom layout measures with infinite height. Bound its viewport and allow its controls to scroll when necessary. |
| E1 | Edge | Overflowed rows suppress the eligible subtotal | medium | Same confirmed defect as B3. Keep this independent finding and apply the same patch. |
| G1 | Test gap | Provider-error projection lacks a regression test | medium | Tests never supply providerIssue; layout tests replace the prepared reason. Add error and blank-error presenter tests. |
| F1 | Full suite | Empty import and a position ticker fall below the initial viewport | medium | ImportBookScreenTest fails two display checks. Hide the summary for an empty book and compact the normal phone summary. |

Every finding routes to a bounded patch. No finding requires a new provider, policy, or public product surface.
The parent supplies the concrete corrections. Luna retains code ownership.

## Patch groups

- Data: B1, B3, B5, B9, E1, G1.
- Header: B10.
- UI and disclosure: B2, B4, B6, B7, B8, F1.

## Closure

All findings closed after the bounded patches and independent readback.
The data readback caught one remaining B9 branch. Luna then checked missing analysis before quote freshness.
The independent reviewer checked that correction and its new test.

The layout diagnosis found legacy Robolectric text metrics. Both layout classes now use native graphics.
All four native layout tests passed. The reviewer checked the final layout and test assertions.
Empty Positions shows Import book without scroll. A populated ticker remains reachable through the list scroll.

The header reviewer closed B10, including short viewport controls, focus release, and scroll direction.
The UI reviewer closed B2, B4, B6, B7, B8, and F1.
The data reviewer closed B1, B3, B5, B9, E1, and G1.
No finding remains deferred.

Final checks passed: 2904 tests passed, 19 skipped, zero failures, and all 59 acceptance cases passed.
The debug APK package and signature checks passed. Phone QA and mutation tests did not run.
The documentation now describes the delivered behavior.
