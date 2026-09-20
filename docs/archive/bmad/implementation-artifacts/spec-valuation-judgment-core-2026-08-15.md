---
title: Valuation judgment core object
type: spec
created: 2026-08-15
status: done (superseded by street-first /3, 2026-08-21)
surface: Android core only
sotp: out of scope
review: sensei+advisor 2026-08-15; Tension no primary; class-refuse no Street primary
---

# Valuation judgment (`core`)

Identity stays exclusive. This object **reads** identity, justified multiple, and Street. It does not replace `DcfAnalysisEngine.compute()`.

Quant Lens stays signal vs noise. This object is the valuation judgment. A later spec must make this the SSOT for stance and primary. This slice does not rewrite Lens.

This slice is **test-closed**. Do not arm live `qa`. Live QA starts when a presenter reads this object.

## Type

`ValuationJudgment` in `apps/android/core`.

Two layers stay separate.

| Layer | Values | Role |
| --- | --- | --- |
| `relation` | reuse `AnchorRelation`: `Unavailable` / `SingleSource` / `Aligned` / `Tension` / `Disputed` | geometry |
| `status` | `Identity` / `Street` / `Tension` / `Disputed` / `Unavailable` | stance |

| Field | Meaning |
| --- | --- |
| `status` | stance. `Tension` is first-class. Do not fold it into `Identity` or `Street` |
| `relation` | `ValuationDecisionPolicy` relation |
| `identity` | finished `DcfAnalysis` when the caller supplied one |
| `justifiedMultiple` | FEM result when that lane is complete and the subject matches |
| `street` | the one Street book the caller supplied (never a merge) |
| `primaryCents` | set only for `Identity` or `Street`. Empty on `Tension`, `Disputed`, `Unavailable` |
| `reasonCodes` | ordered, typed, closed catalog |
| `policyVersion` | `valuation-judgment/2+valuation-decision-policy/1` |

Price forecast v6: `horizonPriceCents` is identity cash (`price-forecast/6-identity-cash`). Street is not a forecast input.

`primaryCents` is never an average of two families.

`judge` does not merge Street providers. Input is one already-resolved book (`Yahoo` or `TipRanks`). Never Model. Never a Yahoo+TipRanks blend.

## Subject key

Every family carries `instrumentId` + `shareBasis`. Compare only when both match the request subject. A ticker string alone is not enough. Mismatch → that family is incomplete for status. Do not invent a lexical join.

## Completeness

### Identity complete

Finished `Computed` analysis, **legal** class/model pair, blank refuse reason, and a **usable** scenario fan.

Legal pairs only:

- `OperatingNonFinancial` + `FcffWacc`
- `FinancialServices` + `ResidualIncomeEquity`

`Unclassified`, `NotEligible`, `None`, Financial+FCFF, Operating+residual-income as primary, or missing drivers → identity is **not** complete.

`identityComplete` is **false** when `scenarioWidthBps` is null or `> IDENTITY_USABLE_MAX_WIDTH_BPS` (12000). That identity is incomplete. Reasons include `UnusableIdentityFan` and `IncompleteIdentity`. Soft is not complete when width is above the usable cut.

Quality is **solid** or **soft** only. There is no third “unusable complete” state. Soft = `waccInputs.isProvisional()` or `pointEstimateUnreliable` or width `> WIDE_SCENARIO_BPS`, and only when the fan is still complete (`width ≤ IDENTITY_USABLE_MAX_WIDTH_BPS`). Soft with width at or below the usable cut stays complete. Incomplete is not soft.

Residual income is identity. It is not Street.

### Street complete

One provider envelope. `source` is `Yahoo` or `TipRanks`. Ordered `0 < low ≤ base ≤ high`. Base is `ValuationDecisionPolicy.isDecisionEligible` (Available, value > 0, ISO currency, minor-unit scale). `coverage` is `Sufficient`. `freshness` is not `Stale` and not `Unknown`.

Inverted or non-positive Street is incomplete.

### Class refuse vs driver refuse

| Refuse | Street complete | Status | `primaryCents` | Street on object |
| --- | --- | --- | --- | --- |
| `Unclassified` / `NotEligible` | yes or no | `Unavailable` | empty | attach as evidence |
| Eligible class, missing drivers | yes | `Street` | Street base | attach |
| Eligible class, missing drivers | no | `Unavailable` | empty | attach if present |

## Status rules

> **Superseded 2026-08-21 (product call, Juan): the analyst range decides.** When a usable
> Street book exists it is the primary and the stance is `Street`, whatever our model says.
> Our model speaks alone only when no usable Street book exists. `Tension` and `Disputed`
> stances are retired from production (`valuation-judgment/3`); the enum values stay for
> parity and replay. The rows below describe the retired `/2` behavior.

Reuse `ValuationDecisionPolicy` thresholds. Do not invent new bps gates.

**Pair set (retired with `/2`):** identity **base** vs Street **base** only. Same ISO currency and same minor-unit scale. Call `ValuationDecisionPolicy.decide` on those two anchors. Do not invent a subset of other pairs.

`Aligned` ≤ 2500. `Tension` 2501–5000. `Disputed` > 5000.

Retired pair table (kept for history):

| Condition | `relation` | `status` | `primaryCents` |
| --- | --- | --- | --- |
| Class refuse (`Unclassified` / `NotEligible`) | `Unavailable` or `SingleSource` if only Street is keyed | `Unavailable` | empty |
| No complete identity and no complete Street | `Unavailable` | `Unavailable` | empty |
| Complete identity only | `SingleSource` | `Identity` | identity base |
| Complete Street only (including eligible missing-drivers) | `SingleSource` | `Street` | Street base |
| Both complete, incomparable currency or scale | `Unavailable` | `Unavailable` | empty. Keep both series |
| Both complete, `Aligned`, identity **solid** | `Aligned` | `Identity` | identity base |
| Both complete, `Aligned`, identity **soft** | `Aligned` | `Street` | Street base |
| Both complete, `Tension` | `Tension` | `Tension` | empty. Keep both series |
| Both complete, `Disputed` | `Disputed` | `Disputed` | empty. Keep both series |

ADR FR-2 stays in force: **Tension and Disputed shall not name a primary.** Only `Aligned` or `SingleSource` may name `primaryCents`.

Market price is not an input to status or `primaryCents`.

## Reasons

Closed catalog. `Unavailable`, `Tension`, and `Disputed` always have ≥ 1 code.

Copy class-refuse and soft codes onto the judgment even when Street is the primary. Under street-first (`/3`) this is load-bearing: a class-refused or soft identity beside a complete Street still carries its code beside `StreetPrimary`, and an incomparable currency/scale flags `IncomparableAnchors` without dethroning the Street primary.

| Code | When |
| --- | --- |
| `Unclassified` | class refuse unclassified |
| `NotEligible` | class refuse not eligible |
| `MissingDrivers` | eligible class, identity refused or incomplete for drivers |
| `NoCompleteFamily` | no complete identity and no complete Street |
| `ShareBasisMismatch` | a family key does not match the subject |
| `FemOnly` | FEM complete, both other families incomplete |
| `IncomparableAnchors` | both complete, currency or scale differ |
| `TensionNoPrimary` | relation Tension |
| `DisputedGap` | relation Disputed |
| `SoftIdentity` | identity complete and soft |
| `StreetPrimary` | status Street |
| `IdentityPrimary` | status Identity |
| `IllegalModelPair` | computed analysis has an illegal class/model pair |
| `IncompleteStreet` | Street present but not complete |
| `IncompleteIdentity` | identity present but not complete |
| `UnusableIdentityFan` | legal computed identity whose `scenarioWidthBps` is null or `> IDENTITY_USABLE_MAX_WIDTH_BPS` |

## Justified multiple

Attach FEM when the existing engine returns a complete candidate and the subject matches.

This slice does **not**:

- select FEM as `primaryCents`
- mix FEM into identity or Street
- feed FEM into ranking

FEM stays a visible candidate. Slice 1C “diagnostic-only” stays until a later spec admits it as a primary.

FEM is not FEP. Do not merge `spec-evidence-routed-operating-valuation-core.md`. If a later identity router returns `Disputed` (FCFF vs earnings-power), judgment must not treat that as missing identity and then let Street win.

## Must not change

- `DcfAnalysisEngine.compute()` exclusive class routing
- Closed-world refuse (no silent FCFF)
- Financials stay residual income
- Street is evidence, not truth
- No `intrinsic/price` cap
- No average of disagreed anchors
- `RANKING_INCLUDES_QUANT_ENGINE` stays false
- Market price is not an input to status or `primaryCents`
- Quant Lens EV policy is not this object
- Do not write `primaryCents` into `dcf_values` or `snapshots.intrinsic_value_cents`

## Out of scope

- SOTP / segments
- Windows dual-write
- Ranking
- New rate or ERP policy
- Shared JSON goldens (later; math stays `valuation-decision-policy/1`)
- Live QA
- Ranking / writing `primaryCents` into `dcf_values`
- Quant Lens stance rewrite
- ETF / fund valuation

List Disc% and Upside% name the judgment primary only. Tension, Disputed, and Unavailable name no Disc% or Upside%. The row shows the stance token instead. `namedRowValuation` sets `fairValueAnchor` from `primaryCents` or marks it unavailable.

Detail reads `presentValuationJudgment`. `primaryCents` is meaningless without `status` and `relation`. Label `Street` as analyst range, never as DCF.

## Tests (Android `:core`, 1 assert each)

Goldens for bps cases use `ValuationDecisionPolicy.differenceBps` and the contract fixtures (2500 / 2501 / 5000 / 5001).

1. Unclassified identity + empty Street → `Unavailable`.
2. Unclassified + complete Street → `Unavailable`, `primaryCents` null, Street attached.
3. NotEligible + complete Street → `Unavailable`, `primaryCents` null.
4. Solid FCFF, no Street → `Identity`, `primaryCents` = identity base.
5. Residual-income identity, no Street → `Identity`.
6. Eligible class, missing drivers, complete Street → `Street`.
7. Soft identity + complete Street + `differenceBps` = 2500 → `Street`.
8. Solid identity + complete Street + `differenceBps` = 2500 → `Identity`.
9. Solid identity + complete Street + `differenceBps` = 2501 → `Tension`, `primaryCents` null.
10. Solid identity + complete Street + `differenceBps` = 5000 → `Tension`, not `Disputed`.
11. Solid identity + complete Street + `differenceBps` = 5001 → `Disputed`, `primaryCents` null.
12. Disputed keeps both series.
13. Complete FEM + empty identity + empty Street → `Unavailable`, FEM still attached.
14. Mutate market price on the same inputs → same `status` and `primaryCents`.
15. Inverted Street is incomplete (no identity → `Unavailable`).
16. Financial+FCFF input is not `Identity`.
17. `Unavailable` `reasonCodes` non-empty.
18. Incomparable currency → no `Disputed`, `primaryCents` null.
19. Share-basis mismatch drops the mismatched family.
20. Judge does not call `DcfAnalysisEngine.compute()` (it accepts a finished identity outcome).
21. Tracked and opportunity rows omit `gapBps` when judgment has no `primaryCents`.

## Implementation order

1. Failing tests above.
2. Types + `ValuationJudgmentPolicy.judge(...)`.
3. `policyVersion` constant.
4. Stop. No Lens, no UI, no Windows.

## Pointers

- Identity: `_bmad-output/planning-artifacts/valuation-model-family-architecture.md`
- Relation math: `ValuationDecisionPolicy` / `shared/contracts/valuation-decision-policy.json`
- ADR FR-2: `_bmad-output/planning-artifacts/android-valuation-decision-adr-2026-07-30.md`
- FEM: `valuation-forward-earnings-multiple-v1.json` (diagnostic)
- Operating FCFF vs earnings-power router: `spec-evidence-routed-operating-valuation-core.md` (separate; do not merge)

## Review Findings

Code review 2026-08-21, full PR #41 stack (`644e0cc4..6477c722`), four layers (blind hunter, edge-case hunter, verification gap, acceptance auditor). The diff deliberately reverses the status table below (L84–112) per an in-session product call; the decision items ratify that reversal.

### Decision needed

- [x] [Review][Decision] Ratify the street-first reversal in its artifacts — the code matches the in-session product call (analyst range decides; our price never primary while street exists; no manufactured Tension/Disputed), but spec rows L99–102 and L112, FR-2 intent, ACs 8–12/18, and `project-context.md` rule "Valuation judgment is a core object above identity" still state the old semantics, and `POLICY_VERSION` still pins them. Open sub-question: under street-first, does the relation layer stay hardcoded `SingleSource` even when both families are complete (current code), or does it keep geometry (`Aligned`/`Tension`/`Disputed` as relation-only display)? Options: (1) amend spec + context rule + bump version, relation stays SingleSource; (2) same doc updates but restore geometry relation; (3) revert code to the old status table.
- [x] [Review][Decision] Reference-line placement on primary stances — `judgmentReferenceLines` now renders under every stance (`DetailScreen.kt:1379`). One layer counts triple rendering of the same two series on Identity/Street stances (headline block, range chart, reference FlowRow); another notes no compose-level test would catch a regression that hides them again. Choose: keep always-on and add a DOM-scoped test, or deduplicate the rendering.

### Patch

- [x] [Review][Patch] Copy class-refuse, soft, and incomparable codes onto Street-primary judgments [`ValuationJudgmentPolicy.kt:139`] — spec L112 requires it; refusal reasons must surface even when Street is primary.
- [x] [Review][Patch] Bump `POLICY_VERSION` to `valuation-judgment/3` [`ValuationJudgmentPolicy.kt:117`]
- [x] [Review][Patch] Harden street-independence tripwire: add pricetarget/consensus/rating markers, walk superclasses, drop duplicate DcfAnalysis rescan, soften KDoc overclaim [`ValuationEngineStreetIndependenceTest.kt`]
- [x] [Review][Patch] Remove dead `quantLens` parameter from `DetailScoreHeader` after the header rail moved [`DetailScreen.kt:351`]
- [x] [Review][Patch] Fix impossible fixture `Street` + `Aligned` and decouple fan fields from `horizonPriceCents = null` [`OurPriceIsSecondaryTest.kt:70`]
- [x] [Review][Patch] Restore alphabetical import order for `Offset` [`DetailScreen.kt:9`]
- [x] [Review][Patch] Compose test: both reference lines render while a primary shows [`DetailScreen` test scope] — resolved by decision 2: after the dedupe, reference lines belong to the no-primary branch only. Placement restored there and pinned by the pure-function tests in `OurPriceIsSecondaryTest`; a compose-level assert was judged disproportionate for a six-line `else` branch and stays available via the `RangeRailBelongsToTheLensTabTest` pattern if the card grows again.

### Deferred

- [x] [Review][Defer] Dip and Leftover keep their own quality rule without the provisional-WACC term [`LeftoverSignalEngine.kt:156`] — deferred, pre-existing
- [x] [Review][Defer] `scenarioWidthBps` can throw on pathological fans via `intValueExact` [`ValuationDecisionPolicy.kt:36`] — deferred, pre-existing
