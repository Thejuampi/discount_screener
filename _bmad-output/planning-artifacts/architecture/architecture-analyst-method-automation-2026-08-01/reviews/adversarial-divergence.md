# Adversarial divergence review

**Reviewed:** `ARCHITECTURE-SPINE.md` and `AUTOMATION-ROADMAP.md`  
**Lens:** circularity, point-in-time integrity, metric/horizon identity, evidence correlation, first-slice safety, platform parity, QA, and scope control  
**Verdict:** **changes required**

The economic direction is sound: the market-reference lane is isolated from intrinsic valuation, the subject price is forbidden as a manufacturing input, and promotion is deferred to point-in-time holdouts. The architecture is not yet implementation-safe, however. Three boundary gaps can publish a temporally or semantically false candidate even when the arithmetic is correct.

## P0 — blocking

### P0-1 — Certified backfill can leak into a live result

AD-2 permits evidence ingested after a historical decision date when a licensed provider certifies its vintage, but neither the candidate nor the run has a required replay-mode type or an eligibility rule. A research backfill could therefore share the same latest projection, cache key, and Quant Lens path as evidence actually captured by the workstation at the time. `availability_basis` alone is descriptive metadata, not an isolation boundary.

**Required correction:** introduce `ReplayMode = Operational | CertifiedBackfillResearch` as a required run and fingerprint field. `Operational` admits evidence only when `publication_at`, `source_available_at`, and `ingested_at` are all no later than `decision_at`. `CertifiedBackfillResearch` may admit a later ingestion only with a provider vintage identifier and certification provenance, but its outputs must be ineligible for the current/live projection, ranking, `Strong`, alerts, and production cache. Store all clocks as validated UTC integer instants (plus explicit date-only precision where appropriate), not lexicographically compared free-form strings. Add a mutation test proving that changing only `ingested_at` across the decision boundary changes operational replay from admitted to refused.

### P0-2 — The first slice can overstate the authority of the JPM evidence

The available source is a user transcription; the original report is not attached. The first-slice language nevertheless requires page/section, `2028E GAAP diluted EPS`, rights, and JPM provenance, then presents a JPM market-reference candidate. A typed record does not make an unverified transcription a verified primary artifact. Fabricated page/section or an authoritative `analyst_stated` label would turn good arithmetic into false provenance.

**Required correction:** separate the shared arithmetic golden from production evidence admission. The golden may encode the user-supplied values explicitly as `fixture_transcription`. A production import without the entitled source artifact must use `manual_transcription_unverified`, omit page/section rather than invent it, retain the metric as a sourced claim, and render “User-transcribed JPM method — source not verified” with provisional quality. It must be ineligible for any evidence-strength upgrade. Importing the actual entitled report later creates a new observation/revision; it never rewrites the transcription. Add a test proving that missing source verification cannot serialize as verified JPM research.

### P0-3 — Slice 1 lacks an atomic current-run/invalidation boundary

The roadmap defers `ValuationDossierCoordinator` to Slice 2, while Slice 1 already imports evidence, computes, persists a run, and projects it into Quant Lens. No owner is specified for freezing the evidence set, committing the run, selecting the current projection, or invalidating an older candidate after a conflicting revision, rejected import, split, policy bump, or metric/horizon refusal. An append-only ledger by itself does not prevent the UI from retaining the last successful `$364` candidate.

**Required correction:** either move a minimal coordinator into Slice 1 or define an equivalent synchronous application service. It must: validate import; freeze exact observation IDs; compute; atomically persist the run plus its evidence-set fingerprint; and update the current projection only when every identity/version/horizon condition matches. A later incompatible or refused revision must append a refusal/invalidation event and make the current projection unavailable without deleting history. Add SQLite migration, crash/retry idempotence, policy-bump, split-vintage, rejected-revision, restart, and stale-UI tests.

## P1 — must resolve before implementation completion

### P1-1 — Horizon coordinate is descriptive, not yet executable

AD-6 names valuation date, target date, fiscal period, share basis, and corporate-action vintage, but it does not define precision or compatibility. The roadmap writes `target_date=2027-12`; treating that month as December 1 or December 31 changes discounting and annualized return. “FY2028” also cannot be joined safely without an issuer fiscal-calendar vintage.

**Required correction:** define typed coordinates: `evidence_observed_at`, `candidate_computed_at`, `target_as_of` with `DatePrecision`, `forecast_period_end`, `fiscal_calendar_vintage`, `metric_basis`, `currency`, `per_share_basis`, and `corporate_action_vintage`. Slice 1 performs no present-equivalent or annualized-return calculation from month-only dates. Equality/comparison APIs must return `HorizonMismatch` unless a versioned transformation explicitly resolves every coordinate.

### P1-2 — Correlation IDs do not yet guarantee deduplication

AD-4 says correlation IDs group observations, and AD-16 says one report counts as one family, but it does not state how derived candidates inherit lineage or how the evidence-strength projector reduces overlapping groups. The stated target, stated EPS, stated P/E, imported method candidate, and a TipRanks observation of that same JPM target can still enter through different adapters.

**Required correction:** make `lineage_group_id` mandatory for admitted research observations and propagate the union of lineage groups into every derived candidate. Evidence strength counts connected lineage groups, not rows or adapters. If identity resolution cannot prove whether two observations are independent, choose the correlated/soft interpretation. Add an end-to-end test where the same JPM call arrives through manual import and TipRanks and still contributes exactly one family.

### P1-3 — Peer circularity protections need executable inputs

AD-8 excludes the subject and aligns peers, but the future policy still lacks explicit fields for peer price timestamp, EPS vintage, FX timestamp, membership vintage, and the target/premium independence boundary. A peer set chosen because it reproduces the desired 28x remains circular even if AMZN itself is excluded.

**Required correction:** require a frozen, policy-generated peer-membership artifact decided without subject target/value; require exact PIT price, EPS, FX, fiscal-period, metric, corporate-action, and eligibility fingerprints per peer. The subject target and reverse valuation are forbidden from peer selection, weights, winsorization, premium fitting, and quality classification. Preserve the current thresholds, but `8–11` remains soft until the stated validation gate is concretely passed; no regression code or DTO belongs in Slice 1.

### P1-4 — Rights metadata is not entitlement

AD-15 risks implying that recording rights and retention fields authorizes storage. It does not. Content-addressed raw research can still violate a vendor agreement, and a hash may itself be restricted derived data under some licenses.

**Required correction:** add a fail-closed `StorageDisposition = MetadataOnly | EncryptedArtifact | Prohibited` resolved from an externally approved entitlement policy before bytes are written. Slice 1 stores only the user-entered structured transcription and its local provenance unless explicit authority for the report exists. Visible Alpha, FactSet, LSEG, generic PDF extraction, and vendor raw caching remain absent from code and schema behavior until budget and contractual authority are recorded.

### P1-5 — Cross-platform and persistence gates are incomplete

The first slice requires Rust/Kotlin arithmetic parity but lists only Windows live QA. It does not state the Android core command, desktop behavior, schema migration/warm-start verification, or a DOM-scoped assertion for the new Quant Lens lane. Existing valuation tests alone cannot catch a stale persisted market-reference candidate or a UI that labels it intrinsic.

**Required correction:** require shared-contract tests in Rust and Android `core`, `scripts/validate-android.ps1`, SQLite migration plus reopen/replay tests, and a Windows native E2E assertion scoped to the new Quant Lens market-reference element. Live Windows QA uses one long-lived `qa` process. Android has no live UI requirement until wired, but its core parity gate is mandatory. Desktop must explicitly remain unsupported for this lane and must not deserialize it as FCFF or analyst consensus. Add a stale-revision live check that removes the old candidate and shows the refusal reason.

## P2 — scope and clarity

### P2-1 — JSON and CSV in Slice 1 double the ingestion surface

Two import formats create two parsers, error semantics, canonicalization paths, and mutation matrices before the evidence model is proven.

**Correction:** ship canonical JSON only in Slice 1. Add CSV later as a converter into the same JSON schema if a real workflow demands it.

### P2-2 — Do not build the full dossier state machine for one manual import

AD-11 is appropriate for later provider orchestration, but deadlines, budgets, cancellation, circuit breakers, raw artifact storage, outcomes, and deep fetch planning are not needed to compute one admitted manual method.

**Correction:** Slice 1 implements the minimal atomic import/compute/project service required by P0-3. The general cancellable `ValuationDossierCoordinator` remains Slice 2 and grows only when network adapters exist.

### P2-3 — SHA-256 migration must not expand into a legacy rewrite

AD-12 correctly keeps FNV read-only, but “dual read” can become a broad migration project touching unrelated FCFF caches.

**Correction:** new analyst-method ledger and runs write only the new versioned SHA-256 identity. Existing FNV-backed records remain untouched and cannot satisfy the new candidate. No legacy rehash or provenance reconstruction belongs in this feature.

### P2-4 — SEC capability is overstated in the stack table

SEC company facts reliably supplies approved entity-level standard facts; segment/KPI data, issuer extensions, and guidance often require filing-table or narrative extraction and reviewed mappings. Listing SEC as the primary source for all “segments” and “approved guidance evidence” can be read as automatic availability.

**Correction:** distinguish `SEC structured entity facts` from `filing artifact candidates`. The latter enter only through AD-14 admission and may remain unavailable. This matters before Slice 5 so missing segment evidence cannot silently inherit Tier-A authority.

## Approval gate

Approval requires all P0 corrections to be incorporated into the spine and roadmap, with P1 decisions made executable in the shared schema and acceptance matrix. P2 items may remain documented deferrals. Until then the architecture is economically coherent but unsafe to implement because it can still publish a look-ahead, stale, or over-authoritative market-reference candidate.

## Re-review 2026-08-01

**Verdict:** **APPROVE**

The corrected spine and roadmap close the unsafe boundaries identified above. This approval is for implementation readiness of the architecture; it is not evidence that the contracts, migrations, UI, or live behavior already satisfy the gates.

### P0 status

| Finding | Status | Re-review evidence |
| --- | --- | --- |
| P0-1 operational/backfill leakage | **Resolved** | AD-2 now requires fingerprinted `Operational` versus `CertifiedBackfillResearch` modes. Operational admission checks publication, source availability, and ingestion against `decision_at`; certified backfill is explicitly barred from live projection, production cache, ranking, alerts, and `Strong`. UTC integer clocks, date precision, and a clock-boundary golden are required in Foundation 0A. |
| P0-2 unverified JPM authority | **Resolved** | The arithmetic golden is now `fixture_transcription`. Production remains `manual_transcription_unverified`, omits invented page/section, treats JPM/GAAP/December-2027 as transcription claims, renders source-not-verified, remains provisional, and cannot upgrade evidence strength. A verified artifact becomes an appended revision instead of rewriting history. |
| P0-3 atomic projection/invalidation | **Resolved** | AD-11 assigns Slice 1 to a minimal synchronous `AnalystMethodApplicationService`. Foundation 0B and Slice 1B require one transaction over frozen observation IDs, evidence fingerprint, run, and current projection/invalidation. Refused revisions, splits, and policy changes append invalidation and clear the current projection. Crash/retry, reopen, restart, and stale-projection gates are named. |

### P1 status

| Finding | Status | Re-review evidence |
| --- | --- | --- |
| P1-1 executable horizon coordinate | **Resolved** | AD-6 defines timestamp roles, `DatePrecision`, fiscal-calendar and corporate-action vintages, metric/currency/share basis, and forbids day-count, annualized return, or present-equivalent from a month label. Slice 1 also disables compatible-horizon disagreement scoring. |
| P1-2 correlation deduplication | **Resolved** | `lineage_group_id` is mandatory, propagated transitively, and reduced as connected lineage groups. Unknown independence defaults to correlated/soft. The acceptance map requires import-plus-TipRanks deduplication. |
| P1-3 peer circularity | **Resolved and deferred** | AD-8 now requires a frozen membership artifact selected without subject target/value and fingerprints every PIT peer coordinate. Thresholds remain conservative and all peer-derived work is outside Slice 1. Regression remains gated by sample-per-coefficient and rolling holdout. |
| P1-4 rights versus entitlement | **Resolved** | AD-15 adds fail-closed `StorageDisposition`, makes Slice 1 metadata-only, copies no proprietary bytes/text, and defers the vault and commercial adapters until external authority exists. The future vault has atomic-write, verification, purge, and unreplayable-run semantics. |
| P1-5 platform/persistence/QA gates | **Resolved** | The first-slice gates now include Rust/Android core parity, `scripts/validate-android.ps1`, transactional SQLite migration rollback/reopen, restart and stale clearing, scoped Windows native E2E, one long-lived `qa` live process, and explicit Desktop unsupported/fail-closed behavior. |

### Scope re-check

- Slice 1 uses canonical JSON only; CSV is a future converter.
- The full network dossier coordinator remains Slice 2; Slice 1 implements only the atomic application service required to publish safely.
- New SHA-256 identities do not trigger an FNV rewrite.
- SEC structured entity facts are separated from filing/segment/guidance admission candidates.
- Peer-derived valuation, regression, present-equivalent, SOTP, ranking, `Strong`, PDF extraction, raw-artifact vault, and commercial providers remain deferred.

One schema clarification should be enforced during Foundation 0A without reopening architecture: `external_file_reference` must be nullable for `manual_transcription_unverified`; absence must never be replaced with a fabricated path or hash. The observation can remain admissible only at provisional, source-unverified quality. This is already implied by the corrected first-slice rules and should receive an exact golden.

No remaining P0 or P1 architectural divergence blocks implementation. Approval remains conditional on the implementation passing every required gate before the lane is exposed as complete.
