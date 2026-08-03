# Current reality check

**Lens:** brownfield/runtime reality  
**Reviewed:** `ARCHITECTURE-SPINE.md`, `AUTOMATION-ROADMAP.md`, current Windows and Android implementations  
**Verdict:** **CHANGES REQUIRED** before implementation starts

The proposed direction is compatible with the product and reuses the right domain seed, but the first build slice currently assumes that the existing PIT observation, SQLite schema, issuer identity, hashing, and Quant Lens integration are closer to production-ready than they are. The P0 items below change the build order; they are not implementation details that can safely be discovered mid-slice.

## P0 — must resolve in the architecture and roadmap

### P0-1 — `EvidenceObservation` v1 is not yet a safe global analyst-evidence ledger

**Claim under review:** AD-1/AD-2 say to extend `evidence_sotp::EvidenceObservation` and make it the canonical persisted ledger.

**Current reality:** the Rust observation has no issuer/security identity, no `source_available_at`, no `ingested_at`, and no `availability_basis` (`evidence_sotp.rs:83-106`). Its accounting-oriented `SourceRegime` only represents US-GAAP, IFRS, or unsupported (`evidence_sotp.rs:19-29`), which cannot truthfully classify broker research, market data, or macro evidence. Replay groups only by `(fact_key, economic_period_end)` (`evidence_sotp.rs:243-277`), so a global ledger would collide across issuers, providers, metric bases, and analyst lanes unless every caller pre-partitions perfectly.

There is also a current Rust/Kotlin semantic mismatch in equal-rank conflict detection: Rust requires every optional value slot to differ (`evidence_sotp.rs:300-304`), so two conflicting money observations with equal `None` values in the other slots are not refused; Kotlin compares a single value key (`EvidenceSotp.kt:39-44`). This must not become persisted truth.

**Required change:** add a pre-slice contract decision for `EvidenceObservationV2` (or an envelope around v1) that defines stable issuer/security ID, evidence lane/provider/correlation ID, accounting basis separately from source kind, complete clocks and availability basis, and the exact resolution partition key. Fix the Rust conflict predicate and add Rust/Kotlin parity cases before creating ledger tables. Preserve v1 deserialization explicitly; do not silently reinterpret old fixtures.

### P0-2 — the roadmap places stable identity after a slice whose run identity already requires it

**Claim under review:** AD-12 makes issuer/security-master and corporate-action vintages part of every run identity, while Slice 1 persists the AMZN run; Slice 2 introduces the PIT security master.

**Current reality:** the live state and persistence are ticker-keyed (`ScreenerState` maps and `snapshots(symbol, captured_at)`), and the current CIK map is a lazy `HashMap<ticker, CIK>` in memory (`state.rs:124-133`). There is no persisted issuer/security alias history or split vintage.

**Required change:** move a **minimal identity substrate** ahead of Slice 1: stable issuer ID/CIK, security ID, effective ticker alias, currency, share/split basis, and an identity-vintage fingerprint for AMZN and the synthetic second issuer. Slice 2 may expand this to full history and automated corporate-action ingestion, but Slice 1 cannot claim AD-12-compliant reproducibility without the minimum.

### P0-3 — SQLite migration and transaction semantics are absent

**Claim under review:** Slice 1 adds append-only observations, revisions, artifacts, and model runs to the existing database.

**Current reality:** `db.rs` executes one `CREATE TABLE IF NOT EXISTS` batch at startup (`db.rs:14-231`, `db.rs:342-349`) and has no `PRAGMA user_version`, migration journal, or schema-version runner. The database is a single `Mutex<Connection>` (`db.rs:291`), and existing provider caches are replaceable rather than append-only. No schema currently stores evidence or model-run JSON.

**Required change:** insert a migration foundation before ledger work: ordered transactional migrations, `user_version`, foreign keys/uniqueness for immutable identities, rollback-on-failure tests against a populated legacy `history.sqlite`, and an atomic write contract that commits raw-artifact metadata, normalized observations, frozen evidence set, and model run together (or records an explicit incomplete job that is never publishable). Define pruning separately; append-only evidence must not inherit monthly cache deletion behavior.

### P0-4 — “canonical serialization + SHA-256” is not executable across Rust/Kotlin yet

**Claim under review:** AD-12 and Slice 1 require exact SHA-256 run/evidence parity.

**Current reality:** the current canonical form is an unescaped pipe-delimited string (`evidence_sotp.rs:162-184`) hashed with FNV-1a (`evidence_sotp.rs:336`, `2283-2291`); a pipe or newline in a definition/source location can make distinct inputs ambiguous. Rust has no SHA-256 dependency in `Cargo.toml`. The documents do not define field order, length encoding, Unicode normalization, null representation, collection ordering, or the versioned bytes shared by Kotlin.

**Required change:** define the canonical byte contract in a shared fixture before coding—prefer a length-prefixed field encoding or an explicitly adopted canonical-JSON standard—plus `fingerprint_scheme`, schema version, ordered-set rules, and mutation cases for delimiters/Unicode/nulls. Specify dual-read/write behavior: old FNV values remain historical labels; only evidence that can be replayed from raw v1 inputs may receive a newly computed SHA-256 identity.

### P0-5 — first-slice proprietary-artifact handling conflicts with deferred authority

**Claim under review:** Slice 1 imports page/section, entitlement, rights, and SHA-256; AD-15 requires retention/encryption metadata, while vendor/caching/encryption authority is deferred.

**Current reality:** there is no encrypted artifact vault or general research credential/rights service. The only relevant secure path is the TipRanks API key in Windows Credential Manager; raw report storage is not implemented.

**Required change:** state that Slice 1 stores only user-confirmed typed facts, report metadata, and an external-file hash/reference; it does **not** copy broker PDF text or bytes into app storage until retention/encryption rights are authorized. If raw bytes are required for acceptance, vendor/legal authority becomes a prerequisite rather than a deferred item.

## P1 — required for the named slice before it is called complete

### P1-1 — the structural seed omits the actual integration surfaces

`ForwardEarningsMultiple` cannot reach Quant Lens by adding only a pure module and editing `quant_lens.rs`. Current Quant Lens consumes `SymbolDetail.operating_valuation`, which is populated from in-memory `ScreenerState` envelopes; `api.ts`, `engine.rs`, `commands.rs`, TypeScript presentation types, and UI refresh behavior are part of the path.

**Required change:** add an explicit read model and command boundary, for example `ValuationDossierView`/`get_valuation_dossier`, or extend `SymbolDetail` additively. Name the owning Rust DTO, Tauri command, TypeScript API type, UI presenter, cache-only read behavior, and the event/poll mechanism that makes a completed background run visible. A persisted market-reference run must not be inserted into `dcf_values`, `selected_valuation_values`, or the legacy scalar `snapshots.intrinsic_value_cents`.

### P1-2 — coordinator migration needs an adapter over the existing worker, not a parallel path

Current Detail valuation uses a `HashSet` single-flight plus `thread::Builder` per symbol (`commands.rs:653-696`), performs SEC/Yahoo acquisition and normalization inside `compute_demand_valuation_once`, and publishes directly into `ScreenerState` (`commands.rs:769-991`). Many other long-lived feed threads already exist.

**Required change:** roadmap Slice 2 must replace this path incrementally: extract the existing demand computation behind coordinator ports first, preserve one authoritative producer, then introduce bounded execution/cancellation. Do not leave the old Detail worker and the new dossier coordinator racing to publish different valuation state.

### P1-3 — provider budgets must be provider-specific and shared across all callers

Yahoo already has a session-wide 429 cooldown, while TipRanks has a 50-call monthly ledger and explicit-load semantics (`analyst_forecasts.rs:12-19`, `769-784`). SEC uses a placeholder `contact@example.com` user agent (`edgar.rs:17`) and has no shared request gate around concurrent company-facts demand workers; only one Form 4 loop sleeps locally.

**Required change:** specify separate policies: keep TipRanks outside automatic dossier spending; reuse the Yahoo session/cooldown rather than layering retries; configure a real SEC contact identity and a process-wide limiter below the SEC fair-access ceiling; and require any licensed adapter to expose quota, retry, entitlement, and cost semantics. A generic coordinator “budget” is not sufficient.

### P1-4 — multiperiod forecast capability is correctly deferred, but the Slice 1 UI must not imply automation

The live Yahoo parser selects only `+1y` (`quote_summary.rs:78`) and the current forward policy refuses horizons beyond 730 days (`operating_valuation_runtime.rs:452`). It cannot source JPM 2028E evidence. TipRanks supplies targets/opinions rather than the EPS bridge.

**Required change:** Slice 1 copy and state must say `manual analyst method`, not “automated professional estimate.” No fallback from missing manual 2028E evidence to Yahoo `+1y` is permitted. Slice 3 owns the provider-period mapping and must add contract cases for fiscal-year changes, stale periods, and GAAP/adjusted ambiguity.

### P1-5 — live market parameters remain provisional until Slice 2 and must stay out of Slice 1 comparisons

The current demand path injects `MarketParams::default_usd()` in three branches (`commands.rs:627`, `730`, `969`). This does not block raw `$13 × 28`, but it would make a present-equivalent or horizon-normalized disagreement provisional.

**Required change:** preserve the Slice 1 deferral of present-equivalent and prohibit compatible-horizon disagreement scoring that secretly calls the default CoE. Slice 2 must wire Treasury/FRED/ERP snapshots into the same evidence ledger before enabling that comparison.

### P1-6 — “watchlist” has no current durable owner

The target flow repeatedly names selected or watched issuers, but current state has active universe membership, portfolio positions, and the currently opened symbol—not a persisted valuation watchlist with refresh/budget semantics.

**Required change:** define v1 watched scope concretely (recommended: explicit dossier pins, separate from feed universe and portfolio). Persist membership/effective dates and hard-cap background refresh. Do not interpret the `qa` or `sp500` profile as permission to build deep dossiers.

### P1-7 — raw artifact storage needs failure and lifecycle rules

“Content-addressed local app data” is named in the stack but has no atomic-write, orphan cleanup, backup, corruption, disk-cap, or deletion-on-license-expiry behavior. SQLite WAL does not make an external file and its metadata one transaction.

**Required change:** define temp-write → fsync/close → atomic rename → metadata commit, hash verification on read, recoverable orphan scan, per-provider retention/deletion, and a storage cap. A missing/corrupt raw artifact must make the run unreplayable rather than silently using normalized rows as if full provenance survived.

## P2 — cleanup and precision

### P2-1 — roadmap primary-document links are malformed

The FRED observation and vintage links in the data table include an extra `/series/` segment. The current official paths are `.../fred/series_observations.html` and `.../fred/series_vintagedates.html`. The Treasury XML link should also be verified against the current published feed URL before it becomes adapter configuration.

### P2-2 — the roadmap should name existing seams it intends to preserve

The documents correctly mention `quote_summary.rs`, `edgar.rs`, `sec_normalization.rs`, and `quant_lens.rs`, but omit `operating_valuation_runtime.rs`, `operating_valuation.rs`, `state.rs`, `analyst_forecasts.rs`, `yahoo_session.rs`, and `detailValuationPresentation.ts`, all of which own current semantics that the new path must not duplicate.

**Required change:** add a brownfield ownership table: reuse, wrap, replace, or leave unchanged for each seam. This will keep implementation from creating a second rate limiter, forecast DTO, cache policy, or Detail presentation path.

## Build-order correction

Use this order instead of starting directly with the current Slice 1 list:

1. **Foundation 0A:** fix Rust/Kotlin PIT conflict parity; define `EvidenceObservationV2`, resolution keys, availability modes, and canonical SHA-256 bytes in shared contracts.
2. **Foundation 0B:** add transactional SQLite migrations and the minimal issuer/security/corporate-action identity needed by run keys.
3. **Slice 1A:** pure `ForwardEarningsMultiple` arithmetic/refusals plus AMZN and second-issuer goldens.
4. **Slice 1B:** typed manual import with metadata-only proprietary artifact handling; persist evidence/run atomically.
5. **Slice 1C:** additive dossier read model through Tauri/TypeScript/Quant Lens; diagnostic only; Windows QA under locked `qa`.
6. **Slice 2:** extract and replace the current demand worker with the bounded coordinator; add public SEC/macro adapters, real provider gates, and live market parameters.
7. Continue with roadmap Slices 3–6 after their evidence and authority gates are satisfied.

## Approval gate

Change the verdict to **APPROVE** when the spine/roadmap explicitly resolves P0-1 through P0-5 and adopts the corrected dependency order. P1 items may remain implementation work only if each is assigned to its owning slice with a named module/read model and verification gate.

## Re-review 2026-08-01

**Artifacts re-reviewed:** corrected `ARCHITECTURE-SPINE.md` and `AUTOMATION-ROADMAP.md`  
**Verdict:** **APPROVE** for implementation in the documented dependency order

This approval is for architecture readiness, not a claim that the new substrate already exists in the brownfield. The corrected artifacts now distinguish current code from planned code and make the necessary foundations explicit prerequisites.

### P0 status

| Original finding | Status | Re-review evidence |
| --- | --- | --- |
| P0-1 — unsafe v1 global ledger | **Resolved in architecture** | AD-1/AD-2 now preserve v1, require a distinct `EvidenceObservationV2`, define stable identity, lanes, lineage, clocks, replay modes, resolution keys, and require the Rust/Kotlin equal-rank conflict fix plus parity goldens in Foundation 0A before persistence. |
| P0-2 — identity ordered too late | **Resolved in build order** | Minimal issuer/security/effective-ticker/currency/share-split identity and identity vintage moved to Foundation 0B before the first persisted run; full history remains correctly deferred to Slice 2. |
| P0-3 — no migration/atomic ledger substrate | **Resolved in build order** | Foundation 0B now requires transactional ordered migrations, `user_version`, FK/uniqueness, populated-legacy rollback and reopen tests, plus one atomic observation/evidence/run/projection-or-invalidation transaction. |
| P0-4 — canonical SHA-256 bytes unspecified | **Resolved in contract** | AD-12 and Foundation 0A define domain/version separation, length-prefixed UTF-8, explicit nulls, big-endian integers, NFC, sorted-set rules, raw attachment hashing, replay mode, and cross-platform mutation goldens. Existing FNV remains untouched and cannot satisfy the new lane. |
| P0-5 — proprietary storage before authority | **Resolved in scope** | AD-15 fixes Slice 1 to `MetadataOnly`: structured user-entered facts and optional external reference/hash, with no copied proprietary text or bytes. The encrypted vault is explicitly future/authorized work with lifecycle and unreplayable-rights semantics. |

**P0 result:** 5/5 closed at the architecture level. No P0 remains that requires another planning pass.

### P1 status

| Original finding | Status | Re-review evidence |
| --- | --- | --- |
| P1-1 — missing integration surfaces | **Assigned and gated** | Structural Seed and Slice 1C now name `valuation_dossier_view.rs`, `state.rs`, `commands.rs`, `engine.rs`, `api.ts`, `detailValuationPresentation.ts`, `QuantLensPanel.tsx`, a dedicated Tauri/read path, refresh event/poll, restart tests, scoped native E2E, and prohibition on legacy intrinsic scalar writes. |
| P1-2 — parallel coordinator risk | **Assigned to Slice 2** | Brownfield table and Slice 2 require wrapping/extracting the existing Detail worker, progressive replacement, and one authoritative producer; the new coordinator is not introduced beside the old writer. |
| P1-3 — provider-specific budgets | **Assigned to Slice 2** | Yahoo reuses its current session/cooldown, TipRanks remains explicit-load and outside automatic spend, SEC gets a configured contact and process-wide limiter, and paid adapters must expose quota/retry/cost/entitlement. |
| P1-4 — current providers cannot source FY2028 | **Correctly bounded** | Slice 1B/1C is explicitly `manual analyst method`/`manual_transcription_unverified`; Yahoo `+1y` cannot satisfy FY2028 or GAAP. Fiscal-period provider mapping and ambiguity cases remain in Slice 3. |
| P1-5 — provisional default market params | **Correctly bounded** | AD-16 and Slice 1 exclusions prohibit compatible-horizon disagreement and present-equivalent; Slice 2 owns live Treasury/FRED/ERP evidence before either may activate. |
| P1-6 — no durable watchlist owner | **Resolved as a product boundary** | The roadmap defines a persisted, dated, hard-capped `dossier pin`, separate from universe, portfolio, and `qa`; full-universe membership grants no deep-fetch authority. |
| P1-7 — raw artifact lifecycle | **Resolved by deferral and future contract** | Slice 1 stores no raw artifacts. AD-15 and the roadmap define atomic write/rename/metadata commit, verification, orphan recovery, caps, purge tombstones, and `unreplayable_due_to_rights` before a future vault can ship. |

**P1 result:** all seven items are either resolved by first-slice scope or assigned to a named owning slice, module seam, and verification gate. They remain implementation obligations and should be checked at each slice exit.

### P2 status

- **Links:** corrected to the current FRED observation/vintage paths and Treasury XML feed.
- **Brownfield seams:** the roadmap now has an explicit reuse/replace table covering `evidence_sotp`, Yahoo session/parser, TipRanks, SEC normalization, operating valuation runtime, Detail worker, DB, state/engine, Quant Lens, TypeScript presentation, and Desktop refusal behavior.

### Approval conditions carried into implementation

1. Do not collapse Foundation 0A/0B into feature coding; their parity and migration gates must pass before Slice 1 persistence.
2. Treat the current Rust conflict bug as a required behavior fix with a failing cross-platform golden, not as a documentation-only correction.
3. Keep `manual_transcription_unverified` provisional and metadata-only until an authorized artifact or licensed feed creates a new append-only revision.
4. Do not publish through legacy FCFF/selected-intrinsic maps or allow the Slice 2 coordinator and current Detail worker to write concurrently.
5. Continue using one long-lived Windows `qa` process for live gates; architecture approval does not waive the repository's valuation merge bar.
