---
date: 2026-05-03
phase: 3 Plan Repair Round 2
owner: John, Product Manager
status: repaired draft
source: Fresh PRD from selected party-mode top 5 use cases
---

# Android Quant Lens PRD

## 1. Problem

Discount Screener Android already ranks opportunities, shows valuation anchors, tracks freshness/trust notes, renders saved price and analyst-target history, and exposes DCF/index estimate scenarios. The gap is not more raw data. The gap is decision clarity: users see many signals, but they do not yet get a compact, deterministic quant lens that answers five higher-order questions:

1. How much evidence supports this opportunity signal?
2. What scenario-weighted value range exists from the anchors already available?
3. Is this opportunity locally close-to-close return correlated with other tracked/watch/opportunity names?
4. Is the visible trend reliable, or just noisy movement?
5. Which current opportunities look most similar by setup?

The product risk is overclaiming. These lenses must not imply a calibrated probability of investment success, personalized financial advice, or a predictive model trained on historical outcomes. They should convert currently available local evidence into transparent labels, ranges, and comparable setups that help the user decide what deserves manual review.

## 2. Users And Jobs

### Primary user

A self-directed Discount Screener Android operator reviewing profile, watchlist, and opportunity symbols on a phone. The user wants to triage quickly, then drill into detail when a symbol deserves deeper manual work.

### Jobs to be done

- When scanning opportunities, I want to know whether a high rank is backed by fresh, broad, agreeing evidence so I do not chase a sparse signal.
- When opening a symbol, I want to see the plausible upside/downside range from existing valuation anchors so I can judge whether the opportunity is asymmetric enough for review.
- When adding names to a watchlist, I want to know whether several opportunities are moving together so I do not mistake local return overlap for independent exposure.
- When reviewing price, valuation history, or estimates, I want to know whether the trend line has enough fit quality to trust as a summary.
- When I like a setup, I want nearby opportunities from the same current universe so I can compare alternatives without leaving the app.

## 3. Product Goals

- Add a Quant Lens to Android that turns existing app/core data into deterministic, testable labels and summaries.
- Keep all business rules in the pure Kotlin core; app/domain/data may orchestrate inputs, DashboardViewModel may present mapped state, and Compose must remain a passive view.
- Prefer explicit sparse/stale/unavailable states over hidden omissions.
- Use locally available data only for MVP. No new data provider, no new background global history backfill, and no network fetch solely for Quant Lens.
- Make every label explainable by observable inputs: freshness, coverage, signal agreement, sample count, fit quality, or distance.

## 4. Non-Goals

- No investment advice, buy/sell recommendations, or prediction of actual future return.
- No calibrated probability-of-success model unless a future validated calibration model exists.
- No portfolio accounting, position sizing, tax logic, cost basis, or realized P&L.
- No cloud service, account system, or server-side ML.
- No new market data provider in MVP.
- No desktop parity implementation in this PRD; Android is the target surface.
- No automatic refresh of complete chart history for every symbol at startup.
- No hidden model training on user data or historical outcomes.

## 5. Assumptions

- Current Android architecture remains strict functional-core / imperative-shell with MVP semantics.
- Existing core data shapes remain available: SymbolDetail, OpportunityRow, HistoricalCandle, SymbolRevision, DcfAnalysis, IndexEstimatesReport, ChartRangeSummary, confidence/freshness/provenance-style states, and opportunity score buckets.
- Existing Android snapshot surfaces remain the primary carriers: DashboardSnapshot, DashboardUiState, opportunity/tracked rows, detail snapshot/history, and estimates tab.
- Existing DCF scenarios are bear/base/bull; existing analyst anchors can include low, median/mean, weighted, and high fair values.
- Existing local price history can be sparse or absent for many symbols, especially after warm start or before detail/chart data has loaded.
- The user accepts deterministic, transparent labels over statistically stronger but opaque predictions.

## 6. Scope

### In scope

- Core Quant Lens model types and pure engines for:
  - Evidence strength.
  - Expected value range.
  - Correlation risk.
  - Trend reliability.
  - Similar setups.
- Repository/domain orchestration that passes existing local inputs into core engines.
- Dashboard snapshot/UI state fields required to render Quant Lens outputs.
- Android presentation mapping in DashboardViewModel without inventing lens rules.
- Compose rendering on opportunities, tracked/watch rows where compact labels make sense, and detail/estimates surfaces for deeper views.
- Unit-testable behavior for every label threshold, fallback state, and sufficiency rule.

### Out of scope for MVP

- User-configurable scenario weights.
- Persisting Quant Lens outputs as independent facts; outputs are derived from current snapshot/local cache.
- Cross-symbol network fan-out to load missing chart history solely for correlation or neighbors.
- Long explanatory education pages. The UI should show concise labels and drill-in details only where useful.

## 7. Strict Architecture Boundaries

### Functional core

The pure Kotlin core owns all Quant Lens semantics:

- Label thresholds and classification.
- Evidence scoring and data sufficiency rules.
- Scenario-weighted value and variance/range calculations.
- Return-correlation calculations.
- Least-squares slope, R^2, and trend labels.
- Normalized nearest-neighbor distance and comparable selection.

Core models should use integer fixed-point style where financial values are involved: cents, bps, hundredths, millis. Floating-point may be used inside pure calculations where mathematically necessary, but public model fields should prefer fixed-point outputs when practical.

### Android app/domain/data

The app layer may:

- Gather current SymbolDetail, OpportunityListRow, chart candles, revisions, and estimate history.
- Decide which local data is available without triggering new global data loads.
- Pass inputs to core engines.
- Attach derived Quant Lens outputs to DashboardSnapshot.

The app layer must not define business thresholds, label meanings, or fallback rules.

### DashboardViewModel as Presenter

DashboardViewModel may:

- Route actions.
- Preserve existing tab/detail state.
- Map snapshot Quant Lens outputs into DashboardUiState.

DashboardViewModel must not calculate evidence strength, EV range, correlation risk, trend reliability, or nearest-neighbor distance.

### Compose as passive View

Compose may:

- Render labels, chips, compact summaries, cards, empty states, and drill-in sections.
- Emit actions such as opening detail, changing tabs, or selecting chart ranges.

Compose must not calculate domain labels or reinterpret missing data.

## 8. Top 5 Use Cases

### Quant Bible Rationale Trace

The selected MVP lenses come from Quant Bible concepts, but the PRD deliberately narrows each concept to what Android can compute transparently from current local data.

| Quant Bible concept | Product implication | Chosen MVP lens |
| --- | --- | --- |
| Conditional probability updating | New evidence can change belief, but MVP has no calibrated probability model or outcome-calibrated odds. | Evidence Strength: qualitative support/conflict/sufficiency labels only. |
| Expected value and variance | Weighted averages and dispersion are useful only when the scenario set is explicit. | EV Range: fixed 25/50/25 scenario-weighted range from three positive anchors. |
| Covariance and correlation | Correlation measures whether two numeric series move together, and low correlation does not prove independence. | Correlation Risk: local close-to-close return correlation only, with sample counts and no portfolio claims. |
| LLN, CLT, and confidence intervals | Sample size and precision matter; sparse samples should not be smoothed into confident labels. | All lenses expose Sparse/Insufficient states and minimum sample gates. |
| Least squares | Fit quality matters separately from trend direction. | Trend Reliability: slope plus R^2 labels, with no continuation claim. |
| Nearest neighbors and curse of dimensionality | Nearest neighbors can compare setups, but high-dimensional or historical outcome matching overfits quickly. | Similar Setups: bounded current-universe nearest neighbors across at most five current features. |

Rejected MVP candidates and reasons:

| Candidate | Rejection rationale |
| --- | --- |
| Calibrated probability of success or posterior return odds | Requires validated calibration, outcome labels, and backtesting the app does not have. Would overclaim. |
| Provider/source redundancy as Correlation Risk | Correlation in the MVP must mean numeric close-to-close return correlation. Provider/source redundancy belongs in Evidence Strength reasons or a future non-MVP risk lens. |
| Portfolio beta, hedging, diversification, or position sizing | Requires holdings, weights, benchmarks, and portfolio context outside Android's current decision surface. |
| Historical analog follow-through or median outcome tables | Requires dated analog cohorts and outcome windows. MVP Similar Setups compares only current symbols and makes no outcome claim. |
| High-dimensional ML, PCA, ridge/lasso, or model training | Adds opacity and model governance before user value is validated. The selected lenses stay deterministic and inspectable. |
| Market-making/derivatives strategy surfaces | Outside Discount Screener's equity-opportunity review job and would imply trading advice. |

### Use Case 1: Evidence Strength

Intent: Give each opportunity signal a deterministic evidence/trust label based on freshness, coverage, signal agreement, and data sufficiency.

Evidence Strength is the MVP home for source-family breadth, provider/source redundancy, stale/restored source concerns, and agreement between valuation, DCF, analyst, fundamentals, technical, and forecast inputs. If provider/source concentration matters, it may downgrade or explain Evidence Strength; it must not be counted as Correlation Risk in MVP.

MVP label set:

- Unavailable: required base quote or valuation input is missing.
- Sparse: base signal exists but too few supporting inputs are present.
- Stale: supporting evidence is present but stale/restored beyond freshness rules.
- Mixed: enough evidence exists but valuation, DCF, technical, or analyst signals disagree materially.
- Provisional: enough evidence exists and does not materially conflict, but coverage is incomplete.
- Strong: fresh, sufficient, and broadly agreeing evidence exists.

Inputs:

- Row freshness/state and last-updated timestamps.
- SymbolDetail qualification, confidence, externalStatus, analyst counts, weighted analyst count, recommendation mean, external signal age, and fundamentals presence.
- Opportunity fundamentals/technical/forecast scores and coverageCount.
- DCF analysis availability and DCF source usability when already available from the current snapshot/detail boundary.
- Chart summary availability for technical evidence.

Acceptance criteria:

- Given a symbol has no market price or no usable intrinsic/fair value, when Quant Lens is computed, then evidence strength is Unavailable and no stronger label is emitted.
- Given a symbol has only the base market/intrinsic snapshot and no analyst, DCF, fundamentals, or chart support, when Quant Lens is computed, then evidence strength is Sparse.
- Given external signal age exceeds the existing max age or the row is restored/stale with no live update, when Quant Lens is computed, then the label is Stale unless the base signal is Unavailable.
- Given DCF says opportunity while analyst target is divergent or forecast/technical buckets are materially negative, when Quant Lens is computed, then the label is Mixed, not Strong.
- Given coverageCount is 3, analyst coverage is at least 3 where analyst evidence is used, DCF is usable when included, and no material disagreement exists, when Quant Lens is computed from fresh inputs, then the label is Strong.
- Given coverageCount is 2 or analyst coverage is below the high-confidence threshold but required base evidence exists, when Quant Lens is computed from fresh non-conflicting inputs, then the label is Provisional.
- Given supporting inputs all come from one provider/source family, when Quant Lens is computed, then Evidence Strength may surface source-family concentration as a Provisional, Sparse, or explanatory reason; Correlation Risk is unchanged by provider/source redundancy.
- Given two symbols have identical inputs, when Quant Lens is computed on separate runs, then evidence strength and explanatory reasons are identical.
- Given the label is rendered in the opportunities list, then the row displays a compact Evidence chip and a detail drill-in shows the contributing factors: freshness, coverage, agreement, and sufficiency.

Anti-overclaiming copy:

- Use "Evidence strong", "Evidence provisional", "Evidence mixed", etc.
- Do not use "70% likely", "high probability winner", "model probability", "posterior", "prior", "likelihood", or "expected to beat" in MVP UI.

### Use Case 2: Expected Value Range

Intent: Show scenario-weighted upside/downside and variance/range using existing DCF or valuation anchors when available.

MVP calculation policy:

- Prefer DCF bear/base/bull scenarios when DcfAnalysis is usable.
- Otherwise use analyst low/primary/high anchors when all three are available and positive.
- Weighted EV requires exactly one selected scenario source with three positive anchors: bear/base/bull for DCF, or low/primary/high for analyst anchors.
- One or two positive anchors are reference-only. They may be shown as sparse context in detail, but they must not produce scenario-weighted fair value, weighted upside, weighted spread, or a full range rail.
- Use fixed MVP scenario weights: bear/low 25%, base/primary 50%, bull/high 25%.
- Calculate scenario-weighted fair value in cents, scenario-weighted upside in bps vs current market price, value range low/high, and weighted variance/standard-deviation-style spread in bps.
- Label the result as "Scenario-weighted", not a guaranteed or calibrated expected return.

Inputs:

- DcfAnalysis bearIntrinsicValueCents, baseIntrinsicValueCents, bullIntrinsicValueCents.
- SymbolDetail marketPriceCents.
- SymbolDetail externalSignalLowFairValueCents, preferred analyst/weighted fair value, externalSignalHighFairValueCents.
- DCF coverage/source status where relevant.

Acceptance criteria:

- Given usable DCF bear/base/bull values and a positive market price, when EV range is computed, then the DCF scenario set is selected and output includes weighted value, weighted upside bps, low value, high value, and spread bps.
- Given no usable DCF but analyst low, primary, and high anchors are positive, when EV range is computed, then the analyst scenario set is selected using the same 25/50/25 weights.
- Given both DCF and analyst scenario sets are available, when EV range is computed, then DCF is the primary MVP source and analyst anchors may be shown as references, not blended silently.
- Given fewer than three positive scenario anchors exist, when EV range is computed, then status is Sparse; available anchors are reference-only and no weighted value, weighted upside, range width, spread, or full range rail is shown.
- Given marketPriceCents is zero or negative, when EV range is computed, then status is Unavailable and no upside bps is shown.
- Given the same scenario values are supplied in any run, when EV range is computed, then weighted value and spread are deterministic to the same cent/bps rounding.
- Given EV range is rendered in detail, then the user can observe source, scenario weights, low/base/high or bear/base/bull values, weighted upside, and range/spread label.
- Given EV range appears on a list row, then only the compact weighted-upside/range label appears when the three-positive-anchor rule is satisfied; detailed scenario math and reference-only sparse anchors stay in detail.

Anti-overclaiming copy:

- Use "Scenario-weighted upside" and "Range width".
- Do not use "expected profit", "forecast certainty", "probability of gain", or "guaranteed return".

### Use Case 3: Correlation Risk

Intent: Warn when the current symbol's local close-to-close return series is highly correlated with other tracked, watchlist, or opportunity symbols using existing local price history.

MVP calculation policy:

- Correlation Risk means local close-to-close return correlation only.
- Use saved or already-loaded HistoricalCandle close prices.
- Compute simple close-to-close return series for each symbol.
- Align by candle epoch where possible. If exact epoch alignment is unavailable, use only same-range candles whose ordered overlap is explicitly sufficient.
- Require at least 30 overlapping return observations for a pair.
- Evaluate the current universe as: tracked symbols plus watchlist plus currently ranked opportunities, deduplicated by symbol.
- Do not fetch missing history solely for correlation in MVP.
- Do not include provider/source redundancy, analyst concentration, sector membership, DCF/fundamental dependency, or source-family overlap in Correlation Risk. Those concerns belong in Evidence Strength reasons or a future non-MVP risk lens.

MVP label set:

- Unavailable: fewer than 3 universe symbols have enough local history.
- Sparse: current symbol has some history but fewer than 2 valid pair correlations.
- Low: no valid pair absolute correlation is >= 0.70.
- Elevated: at least one valid pair absolute correlation is >= 0.70.
- High: at least two valid pairs are >= 0.70, or one valid pair is >= 0.85.

Acceptance criteria:

- Given fewer than 30 overlapping returns for a pair, when correlation risk is computed, then that pair is excluded and listed as insufficient if detail reasons are shown.
- Given fewer than 3 universe symbols have sufficient local history, when correlation risk is computed, then the result is Unavailable.
- Given the current symbol has only one valid pair correlation, when correlation risk is computed, then the result is Sparse unless that single pair is >= 0.85, in which case it is High with a single-pair reason.
- Given one valid pair has absolute correlation 0.72 and no other pair exceeds 0.70, when correlation risk is computed, then label is Elevated and the comparable symbol is shown.
- Given two valid pairs have absolute correlations 0.71 and 0.77, when correlation risk is computed, then label is High and the top correlated symbols are shown.
- Given all valid pairs are below 0.70, when correlation risk is computed, then label is Low.
- Given correlation risk is rendered, then the UI states that it is based on local saved returns and shows the sample count or insufficient-history state.
- Given a symbol is in both watchlist and opportunities, when the universe is assembled, then it is evaluated once.
- Given two symbols share the same provider/source family but do not have sufficient overlapping local returns, when correlation risk is computed, then provider/source similarity does not produce Low, Elevated, or High correlation; the result remains Unavailable or Sparse according to local-history sufficiency.

Anti-overclaiming copy:

- Use "Moves with local peers" and "Local return correlation".
- Do not use "portfolio risk", "hedged", "diversified", or "market beta" unless those are separately modeled.

### Use Case 4: Trend Reliability

Intent: Add least-squares slope and fit-quality labels for price, valuation/history, and estimates where enough samples exist.

MVP trend targets:

- Price trend from HistoricalCandle closeCents for the selected chart range.
- Analyst target trend from SymbolRevision preferred analyst target history.
- Estimate trend from IndexEstimatesReport history scenario impliedUpsideBps.

MVP calculation policy:

- Fit simple least-squares line y = a + b*x over normalized x index/time.
- Output fitted end-to-end movement in bps for the selected window, slope in bps per selected window or per observation, R^2 in bps or hundredths, sample count, and label.
- Materiality is deterministic: compute the fitted line value at the first and last sample, divide the absolute end-to-end fitted movement by the first fitted value, and require at least 200 bps before direction can be meaningful.
- Weak fit means R^2 < 0.40. Up/down direction is shown only when fitted end-to-end movement is at least 200 bps and fit quality is not weak.
- If sample count is below the lens minimum, return Insufficient/Sparse. If movement is below 200 bps, return Flat. If movement is at least 200 bps but R^2 is weak, return Noisy without an up/down direction. Otherwise return Moderate or Reliable with direction according to the fitted movement sign.
- Require minimum samples:
  - Price: at least 20 candles.
  - Analyst target history: at least 3 target observations after flat-span collapse.
  - Estimates: at least 3 saved reports for the selected scenario.
- Fit quality labels:
  - Reliable: R^2 >= 0.70 and fitted end-to-end movement is at least 200 bps.
  - Moderate: R^2 >= 0.40 and fitted end-to-end movement is at least 200 bps.
  - Noisy: R^2 < 0.40 while fitted end-to-end movement is at least 200 bps.
  - Flat: fitted end-to-end movement is below 200 bps.
  - Insufficient: sample count below minimum.

Acceptance criteria:

- Given price history has fewer than 20 candles, when price trend reliability is computed, then status is Insufficient and no Reliable/Moderate/Noisy label is emitted.
- Given price history has 20 or more candles with R^2 >= 0.70 and positive fitted end-to-end movement of at least 200 bps, when computed, then label is Reliable uptrend.
- Given price history has 20 or more candles with R^2 < 0.40 and fitted end-to-end movement of at least 200 bps, when computed, then label is Noisy and no up/down direction is emitted.
- Given fitted end-to-end movement is below 200 bps regardless of R^2, when computed, then label is Flat.
- Given analyst target history contains repeated unchanged spans, when computed, then flat spans are collapsed consistently with existing history semantics before the minimum sample check.
- Given estimate history has at least 3 reports for a scenario, when computed, then the Estimates screen can show trend reliability per scenario without recalculating in Compose.
- Given trend reliability is rendered, then it shows label, sample count, and fit quality, and does not say the trend will continue.
- Given the same ordered sample values are supplied, when computed, then slope, fit quality, and label are deterministic.

Anti-overclaiming copy:

- Use "Reliable fit", "Noisy fit", "Flat", and "Based on saved samples".
- Do not use "trend will continue", "momentum guarantee", or "confirmed breakout".

### Use Case 5: Similar Setups

Intent: Show nearest-neighbor comparable opportunities from the current profile/watch universe using a small normalized feature vector.

MVP calculation policy:

- Similar Setups is current-universe nearest-neighbor comparison only.
- Candidate universe: current profile tracked symbols plus watchlist plus opportunity rows, deduplicated.
- Exclude the target symbol from its own results.
- Do not use historical analog dates, past setup cohorts, future follow-through windows, median outcome claims, or realized return summaries in MVP.
- Use a maximum of five normalized current-only features to avoid high-dimensional nearest-neighbor weakness:
  1. Valuation upside bps, clipped to an agreed range.
  2. Evidence strength ordinal.
  3. Opportunity composite or bucket scores normalized by scoring model.
  4. Trend reliability ordinal/slope where available.
  5. EV range spread or uncertainty width where available.
- Compute weighted Euclidean distance only across normalized shared features available for both symbols: `sqrt(sum(weight_i * delta_i^2) / sum(weight_i))`.
- MVP feature weights are fixed and core-owned: valuation upside 3, evidence strength 3, opportunity composite/bucket score 2, trend reliability/slope 1, and EV spread/uncertainty width 1.
- Require at least 3 shared features for a comparable.
- Require at least 3 qualifying comparable symbols before showing the top-3 list.
- Return exactly the top 3 closest setups when the comparable minimum is met, with distance/rationale and shared feature count.
- Sort by ascending distance, then descending current opportunity composite score when present, then ascending symbol. Missing composite score sorts after present composite score at the same distance.

Acceptance criteria:

- Given fewer than 3 other symbols have at least 3 shared features with the target, when similar setups are computed, then status is Sparse and no top-3 list is shown.
- Given exactly 2 comparable symbols meet the shared-feature rule, when computed, then status is Sparse with count 2 and no partial success list is shown.
- Given 3 or more comparable symbols meet the shared-feature rule, when computed, then exactly the 3 smallest distances are returned, ordered by ascending distance, descending current opportunity composite score, and then ascending symbol for ties.
- Given two symbols have identical normalized feature values, when computed, then their distance is 0 and they sort ahead of non-identical symbols.
- Given a feature is missing for one of two symbols, when computed, then that feature is excluded from their pair distance and the shared-feature count is reduced.
- Given fewer than 3 shared features remain for a pair, when computed, then that pair is excluded.
- Given similar setups are rendered in detail, then each row shows symbol, compact reason text, shared-feature count, and current upside/evidence labels.
- Given similar setups are rendered, then copy says "similar current setups" rather than "expected same outcome".
- Given similar setups are rendered, then rows do not show historical dates, follow-through returns, median outcomes, future outcome windows, or analog cohort aggregates.
- Given the input universe order changes but symbol data is identical, when computed, then the returned top 3 are stable.

Anti-overclaiming copy:

- Use "Similar current setups" and "Closest by current features".
- Do not use "lookalike winners", "same outcome", "historical analog", "follow-through", "median outcome", or "model predicts".

## 9. Cross-Cutting Acceptance Criteria

- All Quant Lens calculations are performed by pure core code and can be unit tested without Android framework dependencies.
- DashboardSnapshot exposes derived Quant Lens outputs or data structures produced upstream of Compose.
- DashboardViewModel does not contain threshold constants for evidence, EV, correlation, trend, or nearest-neighbor labels.
- Compose tests or UI-state tests can verify that each unavailable/sparse/stale/provisional/strong state renders distinct visible text.
- Existing opportunities, tracked, watch, detail, and estimates flows continue to work when Quant Lens outputs are all Unavailable.
- Warm start remains bounded. Quant Lens must not load complete chart history for all symbols during startup.
- Derived labels update when the underlying snapshot updates, scoring model changes, detail chart range changes, or estimates history changes.
- The UI never hides a symbol solely because a Quant Lens output is unavailable.
- No Quant Lens label is persisted as an authoritative historical fact in MVP; persistence remains for source snapshots/history, not derived interpretations.
- Visible UI terminology uses Evidence Strength or Evidence, never posterior, prior, likelihood, calibrated probability, or probability-of-success wording.
- Correlation Risk is always computed and described as local close-to-close return correlation, never provider/source redundancy or portfolio diversification.
- EV Range produces weighted EV only from a selected source with three positive scenario anchors; one or two anchors remain sparse reference-only context.
- Similar Setups compares current-universe nearest neighbors only and never renders historical analog dates, follow-through, median outcomes, or outcome-cohort claims.

## 10. Data Availability Rules

### Canonical status and vocabulary

Core owns primary status, lens-specific band, and data-quality qualifiers separately.

Core primary statuses:

- Unavailable: required base inputs are absent or invalid, such as missing market price for Evidence Strength or EV Range.
- Sparse/Insufficient: required base inputs exist, but sample count, scenario anchors, support features, qualifying comparables, or shared features are below the minimum for a stronger statement. Use Insufficient for trend/sample-count labels where the existing product vocabulary already names insufficient samples; otherwise use sparse.
- Stale: minimum data exists, but freshness rules indicate the currently displayed signal is old, restored beyond freshness rules, or not live enough for an available label.
- Provisional/Partial: minimum data exists and is fresh enough, but optional support is incomplete; UI usually maps this to Provisional or a neutral partial state.
- Available: minimum data exists and the lens can emit its normal band, such as Strong/Mixed, Low/Elevated/High, Reliable/Noisy/Flat, or top current matches.

Canonical degradation precedence for primary status:

1. Unavailable.
2. Sparse/Insufficient.
3. Stale.
4. Provisional/Partial.
5. Available.

Combination rules:

- Stale + Sparse/Insufficient: primary status is Sparse/Insufficient. Preserve the stale/restored freshness as a secondary qualifier or freshness chip; do not show a stronger stale label that hides the insufficiency.
- Restored + Provisional/Partial: primary status is Provisional/Partial when the restored data is still within freshness rules. Add a restored/saved qualifier. If freshness rules are exceeded, primary status becomes Stale unless base inputs are unavailable or sparse/insufficient.
- Unavailable + Stale: primary status is Unavailable. Stale may appear only as a reason, because invalid/missing base inputs cannot produce a stale usable lens.
- Mixed/conflicting evidence is a lens band, not a degradation status. If evidence is both Mixed and Sparse/Insufficient, Sparse/Insufficient wins as primary status and conflict detail remains a reason.

UI vocabulary must map core status into concise labels without exposing implementation terms:

| Core concept | Detail/list UI vocabulary |
| --- | --- |
| Evidence Strength Available/Provisional/Sparse/Stale/Unavailable | `Evidence strong`, `Evidence provisional`, `Evidence mixed`, `Evidence sparse`, `Evidence stale`, `Evidence unavailable` |
| Expected Value Range Available/Sparse/Stale/Unavailable | `EV +8..+24%`, `EV mixed`, `EV sparse`, `EV stale`, `EV unavailable` |
| Correlation Risk Available/Sparse/Stale/Unavailable | `Corr low`, `Corr elevated`, `Corr high`, `Corr sparse`, `Corr stale`, `Corr unavailable`; detail label says `Local return correlation` |
| Trend Reliability Available/Sparse/Insufficient/Stale/Unavailable | `Trend reliable`, `Trend moderate`, `Trend noisy`, `Trend flat`, `Trend sparse`, `Trend insufficient`, `Trend stale`, `Trend unavailable` |
| Similar Setups Available/Sparse/Stale/Unavailable | `Similar current`, `Similar 3`, `Similar sparse`, `Similar sparse (2)`, `Similar stale`, `Similar unavailable` |

Visible UI must not use posterior, prior, likelihood, calibrated probability, win probability, expected winner, or probability-of-success vocabulary. Detail views should show the reason for sparse/stale/unavailable states; list rows may show compact absent states only when they help triage.

### Per-lens sufficiency matrix

| Lens | Required minimum | Sparse/unavailable trigger | MVP fallback |
| --- | --- | --- | --- |
| Evidence Strength | Positive market price plus usable valuation signal | No supporting analyst/DCF/fundamental/chart evidence | Show Sparse or Unavailable reason |
| Expected Value Range | Three positive scenario anchors from one selected source and positive market price | Fewer than three anchors, invalid market price | Prefer DCF; fallback to analyst low/primary/high only when all three anchors are positive. One or two anchors are reference-only Sparse context |
| Correlation Risk | At least 30 overlapping returns per pair and enough universe symbols | Too few symbols or valid pairs | Show local-history insufficiency |
| Trend Reliability | Price 20 candles, target 3 collapsed observations, estimates 3 reports | Sample count below target | Show Insufficient |
| Similar Setups | At least 3 shared normalized features with at least 3 qualifying comparable symbols | Too few comparable symbols or shared features | Show Sparse; exactly 2 qualifying comparables may show `Similar sparse (2)` without a top-3 list |

### Freshness rules

- External analyst/target evidence honors existing externalSignalAgeSeconds and max-age behavior.
- Restored data may support a label only if the label explicitly says restored/stale or if a live refresh has updated the symbol.
- Chart/history-derived lenses must expose the sample window used.
- Estimate-derived lenses must expose computedAt or saved-report count.

## 11. MVP UI Boundaries

### Bounded row-summary contract

Dashboard row chips are summary views, not full Quant Lens reports. The canonical row-summary field is `lensStates`; row chips may render only from `lensStates` values already present on the row's current snapshot/row-summary model, or from an already-computed in-memory selected-detail report for the same symbol whose fingerprint still matches the current row inputs. Row rendering must never trigger provider fetches, DCF computation, SQLite history loads, chart history loads, timeseries loads, or cross-symbol fan-out.

Whole-row behavior:

- If no row `lensStates` summary is present yet, show `Lens loading` rather than blank space.
- If `lensStates` omits a lens because required base fields are unavailable on the row, show that lens as unavailable or sparse according to the canonical status rules when detail renders it.
- If a selected-detail report is reused on a row, the row must use the report as a cached display input only; Compose must not cause recomputation.
- Row chips are passive and must not expose drill-in actions separate from the existing row tap behavior.

Allowed row chip inputs:

| Chip | May show from already-present row fields | Must stay loading/unavailable/sparse when |
| --- | --- | --- |
| Evidence | Row market price, usable row valuation/intrinsic signal, row freshness/restored state, coverageCount, confidence/qualification, externalStatus, score buckets, and already-present row analyst/fundamental/chart summary signals. DCF/source usability may be used only if it is already attached to the row summary or cached selected-detail report. | Base price/valuation is missing, support fields are absent, or using DCF/source detail would require a fetch or computation. |
| EV | Compact scenario-weighted range only when the row summary or cached report already contains positive market price plus three positive DCF or analyst anchors from one selected source. | Fewer than three positive anchors are present, DCF would need to be computed, or missing anchors would need provider/detail fan-out. One or two anchors remain detail reference-only Sparse context. |
| Corr | Cached local close-to-close return-correlation label, sample count, and top symbols only when already computed from local history for the same universe/range. | Local pair returns are not already available, universe/range fingerprint changed, or computing would load chart/history data. |
| Trend | Already-present row chart summary or cached selected-detail trend result for the selected/primary range, including sample count/fit label when available. | Raw candles, saved history, revisions, or estimates would need to be loaded to compute the label. |
| Similar | Already-materialized current-row feature vectors or a cached selected-detail nearest-neighbor result from the current universe. | Comparable detail, history, DCF, provider fields, historical analog dates, or outcome windows would need to be loaded. |

Row chips may be less complete than the detail Lens. The list must prefer `Lens loading`, `EV sparse`, `Corr unavailable`, `Trend sparse`, or `Similar unavailable` over doing hidden work.

### Opportunities list

Show compact chips selected from `lensStates` by capped priority, not by rendering every lens in a fixed all-lens order:

- If no row `lensStates` summary exists, show `Lens loading`.
- If row `lensStates` exists, select chips in this priority order: Evidence first; then EV only when eligible; Corr only when Elevated or High; Trend only when available; Similar only when eligible.
- Apply row-width caps after eligibility and priority selection: show at most 3 chips at row width >= 360dp and at most 2 chips below 360dp.
- Evidence always has first row visibility, including degraded Evidence states, because it is the primary trust and sufficiency signal.
- EV is row-eligible only when the three-positive-anchor rule is satisfied from bounded row or cached-report inputs; EV sparse/stale/unavailable states and reference-only anchors stay detail-only.
- Corr is row-eligible only for Elevated or High local-return correlation, all from cached local-return inputs only. A high-risk Corr chip may remain row-visible when the underlying band is Elevated or High and the displayed chip carries a degraded qualifier; Low, sparse, stale, unavailable, and non-risk Corr states stay detail-only.
- Trend is row-eligible only when selected/primary trend reliability is available from bounded row or cached-report inputs; Trend sparse/stale/unavailable stays detail-only.
- Similar is row-eligible only when at least 3 qualifying comparable candidates exist with current-row features or cached report data; sparse/unavailable Similar stays detail-only and is never shown as a row chip.
- Do not backfill unused chip slots with lower-priority unavailable, sparse, stale, or otherwise ineligible lenses just to fill width.

The list must remain scannable. Detailed reasons belong in detail.

### Detail snapshot

Add a Quant Lens section with five compact subsections:

- Evidence strength with factor breakdown.
- Scenario-weighted range with source and weights.
- Correlation risk with top correlated symbols and sample counts.
- Trend reliability for selected chart range.
- Similar current setups top 3.

### Detail history

Show trend reliability for analyst target history where enough saved revisions exist. Preserve current history status behavior for no history, one snapshot, flat history, and no analyst target.

### Estimates tab

Show trend reliability for scenario-history lines only when at least 3 saved reports exist. Preserve existing DCF coverage banners.

## 12. Anti-Overclaiming Wording Standard

Allowed language:

- "Evidence strength" and "Evidence".
- "Evidence strong/provisional/mixed/sparse/stale".
- "Scenario-weighted range".
- "Local return correlation".
- "Reliable fit" or "Noisy fit".
- "Similar current setups".
- "Closest by current features".
- "Based on saved samples".
- "Insufficient local history".

Forbidden MVP language:

- "Probability of success".
- "posterior", "prior", "likelihood", or calibrated-probability wording in visible UI.
- "Expected profit".
- "Guaranteed return".
- "Prediction".
- "Buy", "sell", or "hold" as Quant Lens conclusions.
- "Diversified portfolio".
- "Model is confident the stock will...".
- "Historical analog", "follow-through", "median outcome", or dated analog cohorts for Similar Setups.
- "Provider correlation" or "source correlation" for Correlation Risk.

Required qualifiers:

- EV range must name the scenario source: DCF or analyst anchors.
- EV range must not show scenario-weighted value, weighted upside, or full range when fewer than three positive anchors are present.
- Correlation must say local saved returns when rendered in detail.
- Trend reliability must show sample count or insufficient state.
- Similar setups must say current features and avoid outcome claims.
- Similar setups must not show a top-3 list unless at least 3 qualifying comparable symbols exist; exactly 2 comparable symbols stays sparse with the count.

Anti-overclaiming copy guards:

- Evidence labels describe support and sufficiency of current inputs, not the probability that an investment thesis succeeds.
- Scenario weights are deterministic display weights, not calibrated market probabilities.
- Local return correlation is exposure context only; it is not portfolio risk, diversification, beta, or hedging advice.
- Trend reliability grades fit quality over saved samples; it does not claim the trend will persist.
- Similar Setups are current comparable symbols; they do not imply repeated historical outcomes.

## 13. Product Decisions Resolved Internally

- MVP uses fixed 25/50/25 scenario weights instead of a configurable scenario-weight editor.
- DCF scenarios are primary for EV range when usable; analyst anchors are fallback, not silently blended.
- Weighted EV requires three positive scenario anchors from one selected source. One or two anchors are sparse reference-only context.
- Correlation risk uses local saved/loaded candle history only, means close-to-close return correlation only, and does not trigger broad background chart loading.
- Provider/source redundancy is handled as Evidence Strength rationale or deferred to a non-MVP risk lens, not Correlation Risk.
- Similar setups uses at most five features and requires at least three shared features to avoid high-dimensional nearest-neighbor noise.
- Similar setups distance is current-only weighted Euclidean over normalized shared features, with at least three shared features per candidate, canonical core-owned feature weights of valuation upside 3, evidence strength 3, opportunity composite/bucket score 2, trend reliability/slope 1, and EV spread/uncertainty width 1, and deterministic ordering by ascending distance, descending current opportunity composite score when present, then ascending symbol.
- Similar setups requires at least three qualifying comparable symbols to show the top-3 list; fewer than three is Sparse, and exactly two may show a sparse count but no partial list.
- Similar setups compares current-universe nearest neighbors only; historical analog dates, follow-through, and median outcome claims are out of MVP.
- Quant Lens outputs are derived at runtime from source data and are not persisted as independent facts in MVP.
- Quant Bible conditional-probability ideas inform the Evidence Strength rationale, but product, core, presenter, and UI vocabulary use Evidence Strength/Evidence rather than probability-model terminology.

## 14. Release Risks

- Overclaiming risk: Users may read scenario-weighted values as predictions. Mitigation: strict wording, visible source labels, no probability-of-success copy.
- Sparse-data risk: Many symbols may show Unavailable/Sparse after warm start. Mitigation: explicit reasons and no hidden ranking removal.
- Performance risk: Correlation and nearest-neighbor calculations can become expensive across large profiles. Mitigation: pure bounded engines, deduped universe, local data only, and no startup-wide history backfill.
- UI density risk: Adding chips to already dense list rows can reduce scan speed. Mitigation: compact list labels and detailed drill-in only.
- Architecture drift risk: Presenter or Compose may accidentally gain business thresholds. Mitigation: core-only thresholds and tests around UI state inputs.
- False diversification risk: Correlation warnings based on local returns may be mistaken for portfolio risk. Mitigation: label as local return correlation and avoid portfolio language.
- DCF/analyst source risk: DCF and analyst anchors can be unavailable, stale, provider-uncertain, or incomplete. Mitigation: existing coverage/freshness/source-family states feed Evidence Strength and EV status, and EV requires three positive anchors before showing weighted outputs.
- Provider/source redundancy risk: Multiple visible signals may come from the same provider or source family. Mitigation: surface this as Evidence Strength rationale or defer it to a future risk lens; do not overload Correlation Risk.
- Nearest-neighbor instability risk: Small universe changes may reorder similar setups. Mitigation: deterministic tie-breaking, shared-feature counts, current-universe-only comparison, and no outcome claims.

## 15. Success Metrics

MVP is successful when:

- Every opportunity/detail symbol can show a deterministic Quant Lens state without crashing, even when all lens outputs are unavailable.
- Users can distinguish Strong, Provisional, Mixed, Sparse, Stale, and Unavailable evidence states from visible Android UI state.
- Detail view exposes scenario source, weights, and range when EV range is available.
- EV range never shows weighted EV from one or two anchors; sparse anchors remain reference-only.
- Correlation risk never appears without sample count or local-history qualifier.
- Correlation risk never includes provider/source redundancy or portfolio-risk wording.
- Trend reliability never appears without sample count and fit quality.
- Similar setups never show fewer than the configured shared-feature minimum.
- Similar setups never shows historical analog dates, follow-through returns, median outcomes, or outcome cohort claims.
- The Android implementation can be validated primarily through core unit tests plus app/presenter/UI tests for rendering states.

## 16. Implementation Notes For Later Story Breakdown

Candidate story slices:

1. Core Quant Lens model and evidence-strength engine.
2. Core EV range engine and detail UI integration.
3. Core correlation-risk engine with repository-provided local history.
4. Core trend-reliability engine for price, target history, and estimates.
5. Core similar-setups engine and detail UI integration.
6. Presenter/UI wiring and anti-overclaiming copy review.

Each slice should start with failing core tests, then app/presenter tests where Android state mapping changes, then Compose tests or screenshot/manual QA where rendering changes reach visible UI.
