# DCF Source Consistency Architecture

Date: 2026-05-02
Owner: Winston / BMad Architect
Status: Phase 3 repaired planning artifact after review findings
Scope: Canonical data-source and provenance architecture for DCF plus market-data-derived values across Android and desktop

## Mission

Create a canonical data-source and provenance architecture so DCF, DCF-derived fallbacks, index estimates, opportunity scoring, detail screens, and historical/revision views all agree on which provider data was used and why. SEC EDGAR may become the primary DCF timeseries source if it proves reliable, but source promotion must be evidence-gated and visible rather than hidden inside a fallback branch.

This plan uses current repository code as grounding and intentionally does not treat previous planning, sprint, or story artifacts as authoritative.

## Phase 3 Review Repair Coverage

This repaired plan closes the Required/Blocker review findings by adding explicit contracts for source-resolved DCF inputs, provider result states, pure core policy ownership, deterministic tie-breaks, provider-disagreement tests, stale fiscal alignment, Android coordinator concurrency, first-slice serialization compatibility, estimate coverage ownership, startup/fetch bounds, staged desktop policy, cache fingerprint semantics, zero-enabled/all-disabled provider resolution, golden state mapping, and the SEC-primary evidence gate.

## Grounding Observations

- Android has general provenance types for performance-lens evidence in [apps/android/core/src/main/kotlin/com/discountscreener/core/model/Models.kt](../../apps/android/core/src/main/kotlin/com/discountscreener/core/model/Models.kt), but `FundamentalTimeseries`, `DcfAnalysis`, `IndexEstimatesReport`, and `ScenarioEstimate` carry no source/provenance.
- Android app wiring injects `YahooFinanceClient` as primary and `SecEdgarTimeseriesProvider` as secondary in [apps/android/app/src/main/kotlin/com/discountscreener/android/app/DiscountScreenerAppContainer.kt](../../apps/android/app/src/main/kotlin/com/discountscreener/android/app/DiscountScreenerAppContainer.kt).
- Android detail loading fetches DCF inputs directly from Yahoo in [apps/android/app/src/main/kotlin/com/discountscreener/android/data/repository/DefaultDashboardRepository.kt](../../apps/android/app/src/main/kotlin/com/discountscreener/android/data/repository/DefaultDashboardRepository.kt), while background enrichment only calls SEC when Yahoo timeseries has empty free cash flow. Those are different source policies.
- Android `FundamentalTimeseriesProvider` returns a nullable `FundamentalTimeseries` with no diagnostics, source identity, quality result, or rejection reason in [apps/android/app/src/main/kotlin/com/discountscreener/android/data/remote/FundamentalTimeseriesProvider.kt](../../apps/android/app/src/main/kotlin/com/discountscreener/android/data/remote/FundamentalTimeseriesProvider.kt).
- Android `RawCapturePayload` declares snapshot, external, fundamentals, and chart payloads, while `CaptureKind` includes `FundamentalTimeseries`; the raw payload lacks a timeseries variant in [apps/android/app/src/main/kotlin/com/discountscreener/android/data/persistence/SQLiteStateStore.kt](../../apps/android/app/src/main/kotlin/com/discountscreener/android/data/persistence/SQLiteStateStore.kt).
- Android `EstimatesScreen` computes DCF coverage bands locally in Compose in [apps/android/app/src/main/kotlin/com/discountscreener/android/ui/dashboard/EstimatesScreen.kt](../../apps/android/app/src/main/kotlin/com/discountscreener/android/ui/dashboard/EstimatesScreen.kt). That rule belongs upstream.
- Desktop DCF analysis is Yahoo-only through `MarketDataClient::fetch_fundamental_timeseries` and `analysis_loop` in [apps/desktop/src/market_data.rs](../../apps/desktop/src/market_data.rs) and [apps/desktop/src/workstation/runtime.rs](../../apps/desktop/src/workstation/runtime.rs).
- Desktop already persists raw `FundamentalTimeseries` captures in [apps/desktop/src/persistence.rs](../../apps/desktop/src/persistence.rs), but the `FundamentalTimeseries` and `DcfAnalysis` structures in [apps/desktop/src/market_data.rs](../../apps/desktop/src/market_data.rs) and [apps/desktop/src/workstation/app_core.rs](../../apps/desktop/src/workstation/app_core.rs) also lack provenance.
- Desktop `AnalysisInputKey` keys cache entries by fundamentals fields only. A source or timeseries change can be invisible if fundamentals are unchanged.

## Architectural Decisions

### AD-001: Canonical DCF Inputs Are Source-Resolved Objects

DCF must consume a canonical `SourceResolvedDcfInput`, not a bare `FundamentalTimeseries` or loose quote/fundamentals snapshot. The selected object contains:

- the chosen normalized timeseries
- selected provider, source endpoint, capture time, and source state
- candidate quality summary for Yahoo and SEC
- rejection reasons for non-selected candidates
- a source-resolved financial/market snapshot used by DCF, opportunity scoring, index estimates, detail, and history
- source fingerprint used in DCF cache keys and persisted revisions

The financial/market snapshot is a first-class contract, tentatively `SourceResolvedFinancialSnapshot`. Every field that can affect WACC, equity value, per-share value, opportunity discount, or ranking/scoring carries value plus provenance:

- market cap, enterprise-value inputs, total debt, cash and equivalents, beta, diluted shares, current price, currency, and as-of timestamp
- price/chart-derived fallback values when provider fundamentals are missing or stale, including chart close used for market-cap or discount calculations
- explicit fallback chain such as provider fundamentals to quote price times shares to chart close times shares
- `DataProvenance` for each field, not only for the enclosing snapshot

No DCF, score, or estimate path may read raw quote, chart, or fundamentals values after this boundary except to build the source-resolved snapshot. If market cap, shares, beta, debt, cash, price, or a chart fallback is missing, restored-only, stale, or derived, the resolver result must expose that state before the DCF engine runs.

Bare provider results may exist only at external boundaries. Once data crosses into repository/orchestration and core engines, source resolution must already be explicit.

### AD-002: Provenance Is Mandatory For Provider-Derived And Derived Values

Add a reusable provenance model for financial data, separate from UI evidence provenance but compatible with it:

- `DataSource`: `YahooFinance`, `SecEdgar`, `Derived`, `Restored`, `Unknown`
- `ProviderState`: `Live`, `RestoredOnly`, `Stale`, `Unavailable`, `NotEligible`, `UnsupportedSymbol`, `ProviderDisabled`, `ParseUncertain`, `ProviderUncertain`, `Rejected`, `Cancelled`
- `ResolverState`: `Selected`, `Unavailable`, `NotEligible`, `RestoredOnly`, `ProviderUncertain`, `Cancelled`
- `RefreshDisposition`: `RetryableRefresh`, `TerminalUntilInputsChange`, `BlockedUntilProviderEnabled`, `NotApplicable`
- `DataProvenance`: source, captured-at epoch, as-of date or fiscal-period label when available, endpoint/fact family, quality flags, and optional fallback reason
- `DerivedProvenance`: references the input provenances used to compute a derived value
- `ProviderDecisionReason`: stable reason code, human-readable message key, provider, symbol, affected field or fiscal period, optional upstream status, and optional threshold/comparison value

Structured reason codes must be enums, not free text. Required codes include `NetworkUnavailable`, `HttpStatus`, `RateLimited`, `ProviderDisabled`, `ProviderConfigurationAbsent`, `NoEnabledProviders`, `DesktopSecDeferred`, `SymbolUnsupported`, `NonUsIssuerUnsupported`, `FundOrEtfUnsupported`, `MissingCik`, `MissingAnnualFcf`, `LatestFcfNonPositive`, `InsufficientAnnualPeriods`, `MissingMarketCap`, `MissingShares`, `MissingDebtOrCash`, `MissingBeta`, `StaleFiscalPeriod`, `FiscalPeriodMisaligned`, `ProviderDisagreement`, `RestoredWithoutLiveRefresh`, `LegacySourceFreePayload`, `GenerationSuperseded`, and `Cancelled`.

The resolver returns one `DcfSourceResolution` shape for every symbol:

- `Selected` when one provider/fallback passes and produces a live source-resolved DCF input
- `RestoredOnly` when no accepted live result exists but a persisted DCF/provenance record can be shown truthfully
- `Unavailable` when no live or restored result is usable and at least one enabled provider path remains retryable or current-data-unavailable, or when source resolution is blocked because no provider is enabled/configured; all provider/configuration reasons must be present
- `NotEligible` when the symbol is unsupported for DCF across active providers, such as ETF/fund/non-equity or no eligible filing/quote path; unsupported provider reasons must be retained even when another provider succeeds
- `ProviderUncertain` when candidates pass quality but materially disagree beyond policy thresholds
- `Cancelled` only for orchestration outcomes that must not update caches or UI state

Mixed provider states resolve by explicit precedence instead of whichever provider is evaluated last:

1. `Cancelled` and `GenerationSuperseded` are orchestration outcomes and must not write cache, persistence, revision, or UI state.
2. Accepted live candidates are evaluated first. One accepted candidate becomes `Selected`; multiple accepted candidates run the disagreement policy before final selection.
3. `ProviderUncertain` wins over live selection only when two or more accepted candidates materially disagree beyond policy thresholds. The resolver must retain the candidate values and disagreement metrics, but no trusted selected DCF value is counted as covered.
4. If no live candidate is accepted and a restored DCF/provenance candidate exists, return `RestoredOnly`. The result must include `RestoredWithoutLiveRefresh` and every current provider/configuration reason. It also carries a refresh disposition: `RetryableRefresh` when any enabled provider failed for retryable reasons, `TerminalUntilInputsChange` when all enabled provider paths ended in terminal ineligibility/quality reasons, or `BlockedUntilProviderEnabled` when no provider path is enabled/configured. Restored data is visible as historical/restored only and is never relabeled as live.
5. If no restored candidate exists and any enabled provider failed for a retryable reason, return `Unavailable` with retryable severity. Retryable reasons include `NetworkUnavailable`, transient `HttpStatus` families, `RateLimited`, and provider timeouts if added later.
6. Return `NotEligible` only when every enabled, non-disabled provider path ends in terminal symbol ineligibility or terminal DCF-quality failure. `ProviderDisabled` and `DesktopSecDeferred` are provider-stage reasons, not proof that the symbol itself is ineligible.
7. If the effective provider set is empty because provider configuration is absent, return `Unavailable` without restore or `RestoredOnly` with restore. Attach `ProviderConfigurationAbsent` and `NoEnabledProviders`, set refresh disposition to `BlockedUntilProviderEnabled`, and never classify the symbol as `NotEligible` from configuration absence alone.
8. If providers are known but all are disabled, return `Unavailable` without restore or `RestoredOnly` with restore. Attach one `ProviderDisabled` reason per disabled provider plus `NoEnabledProviders`, set refresh disposition to `BlockedUntilProviderEnabled`, and never treat the disabled-provider set as symbol-level terminal ineligibility.

For example, `SecEdgar` returning `NonUsIssuerUnsupported` while `YahooFinance` returns `NetworkUnavailable` resolves to `Unavailable` without restore because Yahoo is the eligible retryable path. The same provider combination resolves to `RestoredOnly` when a restored candidate exists, with `RetryableRefresh` and both provider reasons attached. It resolves to `NotEligible` only when Yahoo also returns a terminal symbol or DCF-quality reason.

`DcfAnalysis` must carry DCF provenance. Its provenance should trace at least:

- selected fundamental timeseries source
- fundamentals snapshot source for market cap, debt, cash, beta, and shares fallback
- price/chart source if a fallback snapshot or market-data-derived value was created
- DCF engine version or source fingerprint for cache invalidation

### AD-003: One Source Policy Per Platform, Shared By Every Screen

Android must replace direct DCF timeseries fetches in detail loading and enrichment with one app-data coordinator, tentatively `DcfSourceCoordinator` or `CanonicalDcfProvider`. Desktop must replace the Yahoo-only analysis client with a source-resolving `FundamentalTimeseriesProvider` abstraction.

All consumers use the same selected result:

- Android tracked rows and opportunity rows via repository `dcfCache`
- Android detail screen via `ensureDetailLoaded`
- Android index estimates via `GetIndexEstimatesUseCase`
- Android revisions/history via `SymbolRevision`
- Desktop detail and background analysis via the analysis worker
- Desktop persisted raw captures and evaluated revisions

No UI screen should fetch timeseries, choose between Yahoo/SEC, recompute coverage confidence, or silently override a DCF cache entry.

The app-data coordinator is an imperative shell only. It may fetch provider candidates, assemble raw inputs, call the pure policy, persist accepted results, and publish state. It must not encode provider priority, quality thresholds, disagreement thresholds, tie-break rules, estimate coverage thresholds, or fallback eligibility.

### AD-004: Source Selection Policy Lives In Pure Core Logic

Provider fetching belongs in Android `data/remote` and desktop market-data/provider modules. On Android, every business rule for DCF source selection, provider priority, provider quality, coverage thresholds/status derivation, fingerprint generation, stale-period alignment, and disagreement handling lives in `apps/android/core` only. Android app `domain`, use cases, repository, presentation, and UI may orchestrate calls, pass inputs, persist outputs, and map already-derived states, but they must not reinterpret those rules.

Desktop source-selection rules live in a pure desktop workstation/core policy module or shared contract-compatible Rust module, not in rendering or terminal event handling.

The pure policy contract should be explicit enough to test without Android, SQLite, network, or terminal runtime dependencies:

```text
resolveDcfSource(symbol, providerCandidates, restoredCandidate, policyConfig, clock) -> DcfSourceResolution
```

`policyConfig` owns provider priority, rollout stage, quality thresholds, disagreement thresholds, stale-period tolerance, fiscal alignment rules, tie-break rules, and estimate coverage thresholds. The coordinator passes this config in but does not interpret it.

The policy should evaluate candidate quality using deterministic rules:

- minimum three annual free-cash-flow points
- latest annual free cash flow is positive for DCF eligibility
- sorted unique annual periods
- current or fallback diluted share count is available
- WACC inputs are sufficient, especially market cap
- no non-finite numeric values
- SEC facts use annual FY / 10-K records and known concepts only
- operating cash flow and capex used to derive SEC free cash flow must be from the same annual fiscal period, same end date or accepted restatement replacement, same currency/unit, and same filing scope
- capex is stale when the latest capex period is older than the operating cash-flow period used for the same FCF point, when its period label cannot be aligned, or when an annual FCF point mixes FY and quarterly/TTM facts
- provider diagnostics remain attached even when another provider succeeds

The policy must return both the selected candidate and non-selected candidate reasons.

When both SEC and Yahoo pass and are near-equivalent, selection is deterministic:

1. apply the versioned `policyConfig.providerPriority` for the active rollout stage
2. if priority is equal, prefer the candidate with the newest accepted fiscal year
3. if still tied, prefer the candidate with more accepted annual FCF points
4. if still tied, select by stable provider id order recorded in policy config

Near-equivalent means the normalized overlapping annual FCF series has no sign mismatch, latest overlapping fiscal year matches, latest-year absolute relative delta is at or below the configured threshold, and median absolute relative delta across overlapping years is at or below the configured threshold. The initial threshold should be 10% until live sample evidence justifies a different value.

Provider disagreement comparison uses normalized annual fiscal-year points only: same currency/unit, annual FY or equivalent provider-year label, deduplicated period, and accepted restatement replacement. For each overlapping fiscal year, compute absolute relative delta as:

```text
absRelativeDeltaBps = abs(secFcf - yahooFcf) * 10_000 / max(abs(secFcf), abs(yahooFcf), policyConfig.nearZeroFcfFloor)
```

The denominator is the larger provider absolute FCF value, floored by `policyConfig.nearZeroFcfFloor` to avoid division by zero and unstable ratios for immaterial values. The floor is policy-owned in `apps/android/core` and mirrored in desktop/shared contract fixtures. Sign comparison uses a normalized sign: values with `abs(value) <= nearZeroFcfFloor` are `Zeroish`; otherwise they are `Positive` or `Negative`. A positive-versus-negative normalized sign mismatch is `ProviderUncertain`. Zeroish-versus-material values are resolved by the delta formula, so an immaterial zero/near-zero difference can remain near-equivalent while a material zero-versus-nonzero difference becomes `ProviderUncertain`.

Boundary tests must cover exactly-at-threshold as near-equivalent, just-over-threshold as `ProviderUncertain`, missing latest overlap, sign mismatch, fewer than two overlapping years, stale capex alignment failure, exact zero versus exact zero, zero versus exactly threshold times `nearZeroFcfFloor`, zero versus just over threshold times `nearZeroFcfFloor`, and opposite signs inside the near-zero floor.

### AD-005: SEC Primary Is Evidence-Gated

SEC should not become silently primary just because a provider is present. Implement the policy so SEC can be promoted for DCF timeseries when it passes quality gates. The default rollout should be:

1. gather at least five real upstream samples covering large cap, sparse Yahoo FCF, SEC-supported US equity, unsupported or non-US symbol, and materially divergent Yahoo/SEC timeseries
2. run both Yahoo and SEC candidates through the same quality evaluator
3. make SEC selected for DCF only when it is complete and canonical enough for the symbol
4. fall back to Yahoo when SEC is unsupported, incomplete, stale, parse-uncertain, or fails DCF quality
5. mark `ProviderUncertain` when both providers pass but materially disagree beyond a documented threshold

Planning is not blocked by the final SEC-default product choice. The release gate is whether sample evidence meets the SEC-primary threshold.

Before SEC-primary is enabled, create a sample evidence artifact under `_bmad-output/implementation-artifacts/` that records the live sample date, symbols, provider payload references or raw-capture ids, normalized comparison table, quality result, selected result, disagreement deltas, unsupported-symbol behavior, and reviewer decision. SEC-primary cannot be the default unless that artifact shows at least five distinct live upstream samples and all release gates pass.

### AD-006: DCF Cache Keys Include Source Fingerprints

DCF caches must invalidate when the selected timeseries changes, when selected source changes, or when provider quality status changes materially.

The cache contract has two stable hashes:

- `inputFingerprint` affects DCF math and includes selected provider id, resolver state, policy version, DCF engine version, normalized annual FCF values, fiscal labels, units/currency, selected market snapshot fields used by DCF/scoring, fallback chain, and restored/legacy marker
- `decisionFingerprint` affects provenance/revision visibility and additionally includes selected candidate state, material non-selected candidate state, disagreement bucket, and structured reason codes

Fingerprints exclude capture timestamp, wall-clock request id, raw endpoint URL query ordering, HTTP trace text, transient retry count, and free-text diagnostics that do not change structured reason codes. Transient unavailability of a non-selected provider does not invalidate a mathematically identical selected DCF result, but it may change `decisionFingerprint` if the visible resolver state or uncertainty badge changes.

Android should not key implicitly by symbol only. Desktop `AnalysisInputKey` should include the selected timeseries/source fingerprint in addition to fundamentals inputs. A restored DCF analysis should keep its fingerprint and provenance so screens can show restored/stale state until refreshed.

### AD-007: Raw Timeseries Evidence Must Be Persisted With Provenance

Android should add a `RawCapturePayload.FundamentalTimeseries` equivalent and persist selected timeseries captures with provenance. Desktop already has the payload variant but should wrap it with provenance metadata. Rejected provider candidates can be persisted selectively when they explain a source decision, with bounded retention.

Backward-compatible decode/default provenance belongs in the first serializable model slice, not in a late migration cleanup. Any older source-free DCF, timeseries, or revision payload must decode as `RestoredOnly` or `Unknown` with `LegacySourceFreePayload` and `RestoredWithoutLiveRefresh`; it must never be re-labeled as live Yahoo or live SEC by default.

Warm start should restore current evaluated DCF/provenance and selected-source status. It should not load or backfill complete per-symbol timeseries during startup.

### AD-008: Index Estimate Coverage Is An Android Core Result

Move DCF coverage classification out of Compose. Extend `IndexEstimatesReport` with a `DcfCoverageSummary` or equivalent:

- total eligible symbols
- DCF-covered symbols using the canonical symbol-level rule
- coverage ratio in basis points
- status enum such as `Unavailable`, `LowConfidence`, `Partial`, `Provisional`, `Ready`
- selected source distribution, for example SEC count, Yahoo count, restored count, uncertain count, unavailable count, and not-eligible count

The canonical coverage rule is symbol-level, not per-scenario and not max-scenario:

```text
coverageDenominator = count(symbols with resolver state Selected, RestoredOnly, ProviderUncertain, or Unavailable)
coverageNumerator = count(symbols with resolver state Selected and complete bear/base/bull DCF scenario estimates from the same live selected source fingerprint)
coverageBps = if coverageDenominator == 0 then 0 else coverageNumerator * 10_000 / coverageDenominator
```

`RestoredOnly` symbols are included in the denominator and excluded from the numerator; they contribute to `restoredCount`, including restored values whose refresh disposition is `BlockedUntilProviderEnabled`. `ProviderUncertain` symbols are included in the denominator and excluded from the numerator even if candidate scenario values exist; they contribute to `uncertainCount`. `Unavailable` symbols are included in the denominator and excluded from the numerator; retryable, terminal-current-data, and provider-configuration-blocked reasons remain visible in the summary, and `BlockedUntilProviderEnabled` contributes to `unavailableCount`. `NotEligible` symbols are excluded from numerator and denominator and counted separately as `notEligibleCount`. A symbol must not move to `NotEligible` merely because providers are absent or disabled. `Cancelled` and superseded work does not affect the summary. Per-scenario missing values are diagnostic fields only; a symbol is covered only when every required scenario has a DCF estimate produced from the same accepted live source fingerprint.

`apps/android/core` owns the threshold constants and status derivation: `Unavailable` when denominator or numerator is zero, `LowConfidence` for `0 < coverageBps < 2500`, `Partial` for `2500 <= coverageBps < 5000`, `Provisional` for `5000 <= coverageBps < 9500`, and `Ready` for `coverageBps >= 9500`. Presenters may map a core status to copy, color, order, and visibility only. Compose renders a provided banner model or status enum and must not calculate coverage ratios, thresholds, or confidence bands.

### AD-009: Android Coordinator Work Is Bounded, Deduped, And Generation-Safe

The Android repository coordinator must make source resolution demand-driven and observable:

- `MaxStartupDcfFetches = 0`; warm start restores latest evaluated DCF/provenance only and performs no provider calls before first render
- restore is bounded to tracked symbols and the current/latest evaluated DCF state, not all raw timeseries, all chart candles, or historical captures
- `MaxConcurrentDcfSymbols = 4` unless performance tests justify another value
- `MaxProviderCallsPerResolution = enabledProviders.count`; Yahoo and SEC are each fetched at most once per resolution attempt, with retry policy outside the source-selection policy and recorded as provider diagnostics
- provider work is keyed by symbol plus source input generation; duplicate requests join the existing in-flight work instead of starting another fetch
- generation increments when the symbol set/profile changes, a manual refresh supersedes old work, source policy version changes, or market snapshot inputs affecting DCF/scoring change
- every provider response carries the generation token it was started with; under `stateMutex`, stale responses are rejected when generation, tracked-symbol membership, or accepted fingerprint no longer matches current state
- cancellation is requested when a symbol leaves the tracked set, a refresh supersedes the generation, the app scope is cleared, or the coordinator shuts down; cancelled responses do not write caches, persistence, or revisions

Repository tests must prove detail loading, enrichment, refresh, and estimates all join or reuse the same in-flight resolution and that stale responses cannot overwrite newer source decisions.

### AD-010: Desktop May Stage Yahoo-Only, But The Policy Must Be Explicit

If desktop SEC parity is deferred, desktop can ship a staged Yahoo-only resolver policy only when it is visible and contract-compatible:

- desktop still returns `DcfSourceResolution`, `SourceResolvedFinancialSnapshot`, provenance, and fingerprints
- Yahoo selection is marked as policy stage `DesktopYahooOnly` or equivalent
- SEC candidate state is `ProviderDisabled` with reason code `DesktopSecDeferred`, not silently absent. `DesktopSecDeferred` is not `NotEligible` and must not count as symbol-level terminal ineligibility.
- desktop contract fixtures still cover SEC-primary semantics even if the desktop implementation maps them to deferred state for the stage
- the staged policy must have a removal/upgrade story before claiming cross-platform SEC parity

## File Ownership Boundaries

### Android Core

Owns pure models and rules:

- `DataSource`, `ProviderState`, `DataProvenance`, selected timeseries model, DCF provenance, source-selection result
- `SourceResolvedFinancialSnapshot`, `DcfSourceResolution`, `ProviderDecisionReason`, and serializable legacy decode defaults
- DCF candidate quality evaluator and source-selection policy
- policy config for provider priority, quality, disagreement, stale-period alignment, tie-breaks, estimate coverage thresholds, and rollout stage
- DCF coverage summary for index estimates; threshold ownership and status derivation stay in `apps/android/core`, never app `domain`, presenters, or Compose
- source-aware DCF cache key/fingerprint helper with inclusion/exclusion tests

Keep business rules in [apps/android/core](../../apps/android/core).

### Android Data / Remote

Owns network clients and raw provider parsing:

- Yahoo provider returns a provider result with provenance and diagnostics, not a bare timeseries
- SEC provider returns the same provider result shape with CIK, concept, form, period, and parse diagnostics
- no DCF source selection inside `YahooFinanceClient` or `SecEdgarTimeseriesProvider`

### Android Data / Repository

Owns orchestration and state:

- one DCF source coordinator shared by refresh, enrichment, detail, index estimates, and revision creation
- in-flight dedupe, cancellation, generation tokens, stale-response rejection, and provider-fetch limits
- all cache writes under `stateMutex`
- raw capture persistence for selected timeseries and source decisions
- no detail-only Yahoo DCF path

### Android Domain / Presentation / UI

Owns app orchestration and view-state mapping only:

- `GetIndexEstimatesUseCase` passes source-aware DCF data to `apps/android/core` and returns the resulting core coverage summary
- app `domain` and use cases coordinate repository/core calls, but do not own source-selection policy, provider quality rules, coverage thresholds/status derivation, or fingerprint rules
- presenter maps coverage/source statuses to UI state without deriving thresholds or source policy
- Compose renders state and emits actions only

### Desktop Market Data / Providers

Owns external fetches:

- keep Yahoo quote/chart/timeseries fetches in the Yahoo boundary
- add SEC provider behind a provider trait if SEC is implemented for desktop parity
- if SEC is deferred, expose `DesktopYahooOnly` with SEC disabled/deferred reasons rather than hiding provider absence
- do not put source selection inside rendering or terminal event handling

### Desktop Workstation/Core

Owns orchestration and pure DCF decisions:

- analysis worker calls a canonical provider/resolver rather than Yahoo directly
- `AnalysisInputKey` includes source fingerprint
- `DcfAnalysis` and persisted evaluated state carry provenance
- source-resolved financial snapshot feeds DCF/scoring rather than ad hoc quote/fundamental reads
- rendering consumes prepared source/provenance state only

### Shared Contracts

Owns cross-platform invariants:

- JSON fixtures for source selection outcomes
- golden state mapping for `Selected`, `Unavailable`, `NotEligible`, `RestoredOnly`, `ProviderUncertain`, cancellation, and stale-response cases
- compact presenter golden mapping for resolver states and reason families to banner/status/severity/visibility/coverage inclusion
- DCF analysis with provenance serialization
- index estimate coverage summary behavior
- SEC-primary sample evidence artifact schema and release-gate checklist
- persistence semantics for raw timeseries captures and restored DCF provenance

## Implementation Slices

### Slice 1: Serializable Provenance Model And Source Policy Contract

- Add source/provenance, source-resolved financial snapshot, selected-timeseries, resolver-state, reason-code, and DCF provenance models.
- Add backward-compatible decode/default behavior for legacy source-free DCF, timeseries, and revision payloads as `RestoredOnly` / `Unknown` with structured reasons.
- Add pure source-quality evaluator, resolver-state mapping, deterministic tie-break, provider-disagreement boundary, stale fiscal alignment, estimate coverage threshold, and fingerprint tests.
- Define source fingerprint inclusion/exclusion semantics.
- Keep provider behavior unchanged while the new model is introduced.

Acceptance focus: no new DCF analysis can be represented as source-free, and old source-free payloads decode truthfully without pretending to be live Yahoo or live SEC.

### Slice 2: Android Canonical DCF Coordinator

- Introduce one app-data DCF coordinator used by enrichment, detail load, refresh fallback, and estimates.
- Replace direct `yahooClient.fetchFundamentalTimeseries` calls in `ensureDetailLoaded` and `enrichSymbol`.
- Add in-flight dedupe, generation tokens, cancellation, stale-response rejection, and bounded provider fetches.
- Ensure timeseries and DCF cache updates happen under repository state locking after generation/fingerprint validation.
- Keep work demand-driven and enforce zero provider fetches before first warm-start render.

Acceptance focus: opening detail never changes a symbol to a Yahoo-only DCF result when enrichment selected SEC or another canonical source.

### Slice 3: Android Persistence And Revision Provenance

- Add Android raw timeseries payload support.
- Persist selected timeseries with source metadata.
- Store DCF provenance in symbol revisions and warm-start state.
- Restore stale/restored DCF source state without startup backfill.

Acceptance focus: persisted history can explain which source produced a saved DCF value, and warm start remains bounded to current evaluated state.

### Slice 4: SEC Quality Gate And Live Sampling

- Expand SEC provider diagnostics and quality metadata.
- Gather at least five live upstream samples and record them in the required sample evidence artifact before enabling SEC-primary behavior.
- Add tests for SEC selected, Yahoo fallback, unsupported SEC, unsupported symbol, both providers unavailable, sparse Yahoo, stale capex/fiscal mismatch, deterministic near-equivalent tie-break, and provider disagreement thresholds.
- Decide default source priority from sample results.

Acceptance focus: SEC may be primary only through an explicit, tested quality gate.

### Slice 5: Index Estimates Coverage Ownership

- Extend `IndexEstimatesReport` with DCF coverage summary and source distribution.
- Move threshold logic out of Compose, presenter policy, and app use cases into `apps/android/core`.
- Update presenter/UI tests so the screen renders provided status.

Acceptance focus: coverage warnings are consistent wherever estimates are displayed or persisted.

### Slice 6: Desktop Parity

- Add source-aware timeseries provider abstraction around desktop analysis.
- Add SEC provider or an explicit `DesktopYahooOnly` staged policy if SEC rollout is Android-first.
- Add provenance to desktop `FundamentalTimeseries`, `DcfAnalysis`, raw capture, and evaluated revision payloads.
- Include source fingerprint in desktop analysis cache key.

Acceptance focus: desktop no longer has a hidden Yahoo-only DCF source policy; deferred SEC parity is visible in resolver state and contract fixtures.

### Slice 7: Cross-Platform Contracts And Golden Fixtures

- Add shared contract fixtures for source selection, DCF provenance, and index coverage summaries.
- Update Android and desktop contract tests.
- Add golden fixtures for provider states, legacy decode defaults, fingerprint stability, and SEC-primary evidence-gate eligibility.
- Keep migration tests aligned with Slice 1 decode behavior instead of deferring legacy defaults to this slice.

Acceptance focus: older data loads safely, new data explains source truthfully, and both clients agree on source-selection semantics.

## Acceptance Criteria Mapping

| Requirement | Acceptance Criteria |
| --- | --- |
| Canonical data source architecture | All DCF-producing paths call one canonical source resolver per platform; no detail/enrichment split policy remains. |
| DCF and market-data-derived values consistent across screens | Same source-aware DCF analysis is used by tracked rows, opportunities, detail, index estimates, revisions, and history. Detail load cannot recompute a different Yahoo-only DCF. |
| SEC may be primary if proven good | SEC selection is behind deterministic quality gates and at least five real upstream samples; fallback and uncertainty reasons are retained. |
| DCF/FundamentalTimeseries provenance | `FundamentalTimeseries`, selected timeseries, `DcfAnalysis`, raw captures, and revisions include source/provenance or restored/unknown status. |
| Compose coverage warnings are local today | DCF coverage summary/status moves to `apps/android/core` output; Compose renders state only. |
| Desktop Yahoo-only design | Desktop analysis worker uses source-resolving provider abstraction; source fingerprint participates in cache invalidation. |
| Source-resolved market snapshot | Market cap, debt, cash, beta, shares, price, and chart-derived fallbacks are represented as provenanced inputs before DCF/scoring. |
| Resolver state clarity | `Unavailable`, `NotEligible`, `RestoredOnly`, provider-disabled, unsupported-symbol, and uncertain states have structured reasons for selected and rejected candidates. |
| Android core policy ownership | Provider priority, quality thresholds, tie-breaks, disagreement, stale-period alignment, fingerprint rules, and coverage thresholds/status derivation are pure `apps/android/core` policy; app domain/use cases and coordinators orchestrate/map only. |
| Android concurrency safety | Repository coordinator dedupes in-flight work, cancels superseded work, uses generation tokens, and rejects stale responses under lock. |
| Startup/fetch bounds | Warm start performs zero DCF provider fetches before first render; resolution concurrency and provider calls are capped and tested. |
| Cache semantics | `inputFingerprint` and `decisionFingerprint` have explicit include/exclude fields and stable golden tests. |
| SEC-primary release gate | SEC-primary requires a sample evidence artifact with at least five live upstream samples and passing comparison results. |
| Related design issues fixed | Android raw timeseries payload gap is closed, repository cache writes are centralized under locking, source diagnostics are retained, and source-free restored data is explicitly marked. |

## Test Strategy

- Android core unit tests for source quality, source selection, provider-state mapping, deterministic tie-breaks, disagreement boundary thresholds including zero/near-zero FCF, stale capex/fiscal alignment, DCF cache fingerprinting, and index coverage status thresholds.
- Android repository tests for enrichment/detail consistency, SEC fallback, raw timeseries capture, warm-start DCF provenance, estimates source distribution, in-flight dedupe, cancellation, generation-token rejection, stale-response rejection, zero startup DCF fetches, and provider fetch caps.
- Android UI/presenter tests proving `EstimatesScreen` does not own threshold decisions.
- Desktop unit tests for source-aware analysis cache invalidation, explicit `DesktopYahooOnly` staged policy, and raw capture provenance.
- Shared contract tests for source-selection fixtures, serialized DCF provenance, fingerprint stability, and golden state mapping.
- Provider sampling tests based on live upstream samples, with captured fixtures only after real samples are observed and recorded in the SEC-primary evidence artifact.

Golden state matrix required before implementation stories are marked ready:

| Scenario | Expected resolver state | Required assertions |
| --- | --- | --- |
| SEC passes, Yahoo unavailable | `Selected` / SEC live | Yahoo unavailable reason retained; SEC input fingerprint stable. |
| Yahoo passes, SEC unsupported | `Selected` / Yahoo live | SEC `UnsupportedSymbol` or `NotEligible` reason retained. |
| Both providers unavailable, no restore | `Unavailable` | Both provider reasons present; no DCF cache write. |
| Both providers unavailable, restored exists | `RestoredOnly` | Legacy/restored reasons present; no live source implied. |
| SEC `NonUsIssuerUnsupported`, Yahoo `NetworkUnavailable`, no restore | `Unavailable` | Yahoo retryable path controls state; SEC terminal provider reason retained; denominator included, numerator excluded. |
| SEC `NonUsIssuerUnsupported`, Yahoo `NetworkUnavailable`, restored exists | `RestoredOnly` | `RetryableRefresh`, `RestoredWithoutLiveRefresh`, and both provider reasons present; restored value visible but not live. |
| SEC `NonUsIssuerUnsupported`, Yahoo terminal unsupported or terminal DCF-quality failure | `NotEligible` | All enabled non-disabled provider paths are terminal; excluded from coverage denominator and numerator. |
| Desktop SEC deferred, Yahoo selected | `Selected` / Yahoo live | SEC candidate is `ProviderDisabled` with `DesktopSecDeferred`; not symbol-level `NotEligible`; coverage follows Yahoo completeness. |
| Desktop SEC deferred, Yahoo `NetworkUnavailable`, no restore | `Unavailable` | Yahoo retryable path controls state; `DesktopSecDeferred` retained but does not make the symbol terminal. |
| No enabled providers because provider configuration is absent, no restore | `Unavailable` | `ProviderConfigurationAbsent`, `NoEnabledProviders`, and `BlockedUntilProviderEnabled` present; not symbol-level `NotEligible`; denominator yes, numerator no, `unavailableCount` increments. |
| No enabled providers because provider configuration is absent, restored exists | `RestoredOnly` | `ProviderConfigurationAbsent`, `NoEnabledProviders`, `BlockedUntilProviderEnabled`, and `RestoredWithoutLiveRefresh` present; restored value visible but not live; denominator yes, numerator no, `restoredCount` increments. |
| All known providers disabled, no restore | `Unavailable` | Each provider has `ProviderDisabled`, aggregate `NoEnabledProviders`, and `BlockedUntilProviderEnabled`; not symbol-level `NotEligible`; denominator yes, numerator no, `unavailableCount` increments. |
| All known providers disabled, restored exists | `RestoredOnly` | Each provider has `ProviderDisabled`, aggregate `NoEnabledProviders`, `BlockedUntilProviderEnabled`, and `RestoredWithoutLiveRefresh`; restored value visible but not live; denominator yes, numerator no, `restoredCount` increments. |
| ETF/fund/non-equity unsupported | `NotEligible` | Symbol unsupported reason maps to coverage exclusion by Android core policy. |
| Both pass and near-equivalent | `Selected` | Deterministic tie-break follows policy priority and boundary threshold. |
| Both pass and materially disagree | `ProviderUncertain` | Disagreement basis and deltas are persisted and surfaced. |
| Both pass, exact zero versus exact zero on non-latest overlap | `Selected` if other gates pass | Delta is zero; no division-by-zero or sign-mismatch failure. |
| Both pass, zero versus exactly threshold times `nearZeroFcfFloor` | `Selected` if other gates pass | Boundary is near-equivalent because delta is exactly at threshold. |
| Both pass, zero versus just over threshold times `nearZeroFcfFloor` | `ProviderUncertain` | Boundary is material disagreement with persisted delta/reason. |
| Both pass, opposite signs inside `nearZeroFcfFloor` | `Selected` if other gates pass | Both values normalize to `Zeroish`; delta formula, not raw sign, controls the outcome. |
| SEC stale capex/fiscal mismatch | Yahoo selected or `Unavailable` | SEC rejection reason is `FiscalPeriodMisaligned` or `StaleFiscalPeriod`. |
| Legacy source-free payload decode | `RestoredOnly` / `Unknown` | Decode defaults use structured reasons and never mark live. |
| Superseded Android response | current state unchanged | Generation mismatch prevents cache, persistence, and revision writes. |

Compact presenter golden mapping required before implementation stories are marked ready:

| Resolver state / reason family | Banner or status model | Severity | Visibility | Coverage inclusion |
| --- | --- | --- | --- | --- |
| `Selected` live, no material retained warning | `DcfLive` or no banner | None | Detail provenance and estimates distribution only | Denominator yes; numerator yes when all required scenarios are complete from the same live fingerprint. |
| `Selected` live with retained non-selected unsupported/disabled reason | `DcfLiveWithSourceNote` | Info | Detail provenance, source drawer, estimates distribution | Denominator yes; numerator yes when selected live scenarios are complete. |
| `RestoredOnly` with `RetryableRefresh` | `DcfRestoredAwaitingRefresh` | Warning | Tracked row status, detail banner, estimates banner, history provenance | Denominator yes; numerator no; `restoredCount` increments. |
| `RestoredOnly` with `TerminalUntilInputsChange`, `BlockedUntilProviderEnabled`, or legacy decode | `DcfRestoredOnly` | Warning | Detail banner, history provenance, estimates banner when included in report universe | Denominator yes; numerator no; `restoredCount` increments. |
| `Unavailable` retryable, such as `NetworkUnavailable`, retryable `HttpStatus`, or `RateLimited` | `DcfTemporarilyUnavailable` | Warning | Tracked row status, detail banner, estimates banner | Denominator yes; numerator no; `unavailableCount` increments. |
| `Unavailable` current-data failure, such as missing market cap or insufficient annual periods where symbol eligibility is not terminal | `DcfUnavailable` | Warning | Detail banner and estimates banner | Denominator yes; numerator no; `unavailableCount` increments. |
| `Unavailable` provider-configuration-blocked, such as `ProviderConfigurationAbsent`, `NoEnabledProviders`, or only `ProviderDisabled` reasons | `DcfUnavailable` | Warning | Detail source status and estimates banner | Denominator yes; numerator no; `unavailableCount` increments. |
| `NotEligible` terminal symbol/source ineligibility, such as fund/ETF/non-equity or all enabled paths terminal | `DcfNotEligible` | Info | Detail/source status; hide noisy list banners unless selected | Denominator no; numerator no; `notEligibleCount` increments. |
| `ProviderUncertain` disagreement or parse uncertainty after candidates otherwise pass | `DcfSourceUncertain` | Error | Tracked row status, detail banner, estimates banner, history provenance | Denominator yes; numerator no; `uncertainCount` increments. |
| `Cancelled` or `GenerationSuperseded` | no user-visible state update | None | Hidden; current visible state remains unchanged | No denominator, numerator, or distribution change. |

No builds or tests were run for this planning task.

## Plan Risks

- SEC coverage is US-centric. Non-US symbols, ADRs, funds, and some profile symbols may need Yahoo fallback for the foreseeable future.
- SEC XBRL concepts vary by filer. Operating cash flow, capex, shares, fiscal year, restatements, and units require careful diagnostics and concept aliases.
- Provider disagreement can materially change DCF values. The plan needs an uncertainty state, not silent selection.
- Provenance added to persisted payloads requires backward-compatible decoding and clear restored/unknown semantics.
- Source-aware DCF may increase network work. Keep fetches demand-driven, bounded, cached, and never startup-wide.
- Cross-platform duplication could drift. Shared contract fixtures are the guardrail unless a shared library is introduced later.
- Enabling SEC-primary is a product/release decision after evidence gathering, but it does not block implementing the architecture.

## Product Decision Status

No blocking product decision was found for planning. The only release decision is the SEC-primary threshold after live sample evidence. The architecture can be built with a policy gate that defaults conservatively and makes the source decision visible.
