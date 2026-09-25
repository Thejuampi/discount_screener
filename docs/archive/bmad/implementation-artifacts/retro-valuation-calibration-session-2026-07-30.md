# Retrospective: Valuation calibration session (not a sprint epic)

**Date:** 2026-07-30  
**Scope:** Conversation arc — T gap vs Street → CapEx/WACC fixes → multi-name breakage (AMZN/CI) → baseline suite → closed-world classifier  
**Format:** Content over ceremony. No sprint IDs, no story points.

## What we were trying to do

1. Explain and shrink DCF vs weighted-analyst gap on **T** with evidence (not clamps).  
2. Keep the model honest (dual anchors, provisional rates).  
3. Eventually: stop one-ticker fixes from destroying the rest of the universe.  
4. Eventually: refuse valuation when business class is unknown (CI healthcare plans).

## What actually happened (timeline of failures)

| Phase | What we shipped / saw | What went wrong (system) |
| --- | --- | --- |
| T CapEx / soft WACC | Multi-tag CapEx, debt-weight guard, provisional uplift | Correct for **one** failure mode; validated almost only on T-class fixtures |
| “Closer to Street” | Base ~$27–30 vs ~$46 band | Success on T **without** a multi-name gate before merge-quality claims |
| AMZN ~$1 / inverted scenarios | CapEx trough + raw growth + soft rates | **Regression not guarded** until after user pain; single-name green was the only bar |
| Multi-name baseline | Top-20 High+≥20% fixture + `valuation_baseline` tests | Built **late**; first green versions used **weak absurd checks** and **quarantine-as-pass** |
| Skeptic pushback | Stronger OOM rules | First response was still quarantine; only later: fix drivers / replace unusable names / 20/20 active |
| CI ~$733 DCF | Managed care on FCFF float | Classifier **fail-open**: unknown/mismatched industry → FCFF by default; **app did not scream** |
| Closed-world classifier | `Unclassified` → refuse valuation | The rule users wanted **from the start**; arrived after absurd numbers shipped to UI |

## Root causes (systems, not people)

### 1. Single-ticker definition of done

- Acceptance was “T looks better vs Street.”  
- SWE practice: **any policy change to shared pure math needs a multi-tenant regression set before calling it done.**  
- Consequence: T-class uplift + FCF averaging changed the whole universe; AMZN/CI paid the cost.

### 2. Fail-open classification and valuation

- `if not financial → FCFF` is a product landmine.  
- Missing or exotic industry (Healthcare Plans) did not fail; it **lied with a high number**.  
- SWE practice: **closed world for routing that changes economics**; unknown → unavailable + reason, never a default model.

### 3. Tests that prove the wrong property

- Early “anti-clamp” and T-band tests did not prove “mega-cap with material FCF is not pennies.”  
- Weak `is_absurd_collapse` (high FCF gates, no order-of-magnitude vs market) let **MU $15 vs $860** look green.  
- SWE practice: assert **invariants that match user-visible failure modes**, not only happy paths and policy constants.

### 4. Quarantine used as success

- Quarantine with reason is honest mid-flight.  
- Treating “suite green with 13 quarantines” as goal-complete **hid residual absurd error**.  
- SWE practice: quarantine is **not** a substitute for either (a) fix, or (b) replace with another pin that still meets the product cohort definition.

### 5. Fixture quality lagged production claims

- Live list used one FCF story; offline pins used SEC OCF−PPE and “Unknown” sector until forced.  
- Selection intrinsic (inflated FCFF) was used as a comparison anchor without checking plausibility.  
- SWE practice: **pin the same drivers the engine will see in prod**, or document intentional proxy (e.g. OCF−D&A) in the fixture.

### 6. Ceremony vs content (process)

- Sprint-status still tracks an older “Valuation Change Visibility” epic.  
- This session’s real epic lived only in chat + ad-hoc artifacts.  
- That made formal retros blind and made “done” fuzzy.  
- Practice: **track the actual change set** (spec + contract + tests), not empty sprint keys.

## What went well (keep)

- **Evidence-first on T:** reverse-DCF, CapEx multi-tag, SEC facts — not “clamp to $29.”  
- **Project law held:** no intrinsic/price hard caps as valuation truth.  
- **Eventually correct architecture moves:** multi-name baseline module, isolation tests (T + cohort), ACGL/CI residual-income guards, closed-world `Unclassified`.  
- **Skeptic / verification loop** forced OOM rules and 20/20 non-quarantine when quarantine was abused.  
- **Cross-platform intent:** Android classifier/policy bumped with Windows (still needs vigilance on desktop deferral).

## Lessons (portable)

1. **Shared pure functions are multi-tenant.** One-name green is never sufficient for `dcf_model` / classifier / WACC policy.  
2. **Default routes must be safe.** Prefer refuse over wrong model. Wrong high numbers are worse than empty.  
3. **Write the regression that would have failed AMZN and CI *before* shipping T.** Or immediately after the first T green, before UI QA.  
4. **Sanity = order-of-magnitude + business class + determinism**, not “tests pass.”  
5. **Quarantine is a ticket, not a trophy.** Cohort goals need N active greens or an explicit reduced-N product decision.  
6. **UI must surface refusal.** Backend error is incomplete if Detail still shows a stale $733 or a silent dash with no reason.  
7. **Policy version bumps are cache invalidation contracts.** If policy changes, stale FCFF must die on sight (partially addressed in `ensure_model_routed_valuation`).

## Action items (owned, verifiable)

| # | Action | Owner | Success criteria |
| --- | --- | --- | --- |
| A1 | **Multi-name baseline is merge bar** | **Done** — `Agents.md` Build section + policy + project-context |
| A2 | **Closed-world classifier** fail-closed | **Done** — policy/3 + tests (unclassified refuse) |
| A3 | **Detail DCF refusal reason** | **Done** — `valuation_unavailable_reason` + i18n slot copy |
| A4 | **Quarantine ≠ success** | **Done** — baseline policy requires 0 quarantines for 20-slot fixture |
| A5 | **CI + AMZN permanent fixtures** | **Done** — `baseline_ci_*`, `baseline_megacap_amzn_*`, contracts |
| A6 | **Desktop fail-closed** | **Done** — closed-world refuse in `app_core`; full WACC/FCF parity still deferred (documented) |
| A7 | **Live QA checklist** | **Done** — `docs/valuation-live-qa-checklist.md` |

## Technical debt still open (honest)

- Live Yahoo blocked (401) during pin → SEC proxies; analyst anchors often missing in offline report.  
- Desktop FCFF path still deferred vs Windows/Android policy.  
- AMZN base still can sit low vs market under soft rates + trough growth (anti-penny guarded; full economic calibration not “Street-matched”).  
- Keyword tables will always lag the market; long-term: SIC/NAICS or provider taxonomy map, still **fail closed** when unmapped.  
- Sprint-status file is stale relative to real valuation work — either update tracking or stop pretending it drives process.

## Success assessment (this session)

| Goal | Verdict |
| --- | --- |
| T gap explained and reduced without clamps | **Met** (evidence + uplift + CapEx) |
| One-name fix cannot silently break cohort | **Met late** (baseline exists; should have been first) |
| App never silently wrong-models float businesses | **Met late** (closed-world + CI; UI reason still thin) |
| SWE rigor (tests/baselines first) | **Partially met** — recovered after user pressure, not by default |

**Overall:** Product outcomes improved. Process was **reactive**. The durable win is institutionalizing multi-name baseline + fail-closed classification so the next “fix T” cannot ship without the cohort screaming.

## Next work (prep, not a fake epic number)

1. Wire **unclassified / not eligible** reasons into Detail DCF slot (i18n).  
2. Keep baseline green while expanding operating/financial tables from real Yahoo sector dumps (with tests).  
3. Optional: maintenance CapEx (OCF−D&A) as **explicit policy** for high-reinvestment names, not only a MU fixture hack.  
4. Human QA pass: T, AMZN, CI, UNH, JPM, AAPL after rebuild.

---

*Generated as content-first retrospective for the valuation calibration conversation. Not tied to sprint-status epic IDs.*
