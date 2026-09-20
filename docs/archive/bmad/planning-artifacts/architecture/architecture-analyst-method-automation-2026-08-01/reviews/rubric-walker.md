# Rubric walker — analyst-method valuation automation

## Review scope and verdict

Reviewed:

- `ARCHITECTURE-SPINE.md`
- `AUTOMATION-ROADMAP.md`
- driving `SPEC-analyst-method-valuation-candidates` CAP-1 through CAP-11 and companions
- `_bmad-output/project-context.md`
- inherited valuation-family and evidence-SOTP invariants

Purpose/audience read: these documents exist to give the implementation agent and Juan a decision-complete, fail-closed substrate for automating professional-method valuation without training the product to copy market price.

**Verdict: CHANGES REQUIRED.** The core direction is sound and preserves the major project invariants: business-class routing, market-reference isolation, fixed-point parity, bounded demand, correlated-evidence handling, price/target mutation invariance, and QA profile `qa`. Four contract contradictions prevent approval, and several verification decisions remain underspecified.

Priority meaning for this gate:

- **P0:** contradictory or unsafe contract; implementation must not begin against the affected clause.
- **P1:** material omission that must be decision-complete before its slice begins.
- **P2:** clarity, operability, or editorial defect that should be corrected before declaring the package final.

## CAP traceability

| Capability | Coverage | Reviewer result |
| --- | --- | --- |
| CAP-1 typed, dated analyst-method evidence | Partial | Ledger and clocks are strong, but the first golden cannot claim page/section-verified JPM provenance while the source PDF is absent. |
| CAP-2 separate earnings-multiple candidate | Covered | AD-5 through AD-7 and Slice 1 preserve model identity and exact arithmetic. |
| CAP-3 compare anchors without blending | Covered | AD-5, AD-6, and AD-16 preserve horizons, families, and `Disputed`/diagnostic behavior. |
| CAP-4 extend through versioned method/peer policy | Contradicted | Peer admission is `<5 refuse` in AD-8 but `>=3` in the canonical companion policy. |
| CAP-5 fidelity and out-of-sample usefulness | Covered with gate | AD-13 separates primary forecast validation from market diagnostics; promotion thresholds still need an executable contract before Slice 6. |
| CAP-6 refuse stale/incomplete/circular/incompatible evidence | Partial | Fail-closed intent exists, but no canonical freshness, coverage, event-expiry, or reason-code table is assigned to Slice 1/3. |
| CAP-7 operating-driver EPS and GAAP bridge | Partial/deferred | AD-9 defines the bridge, but Slice 5 lacks reconciliation and normalized-EPS acceptance gates. |
| CAP-8 Amazon SOTP cross-check | Contradicted | `covered SOTP` is called an intrinsic candidate although inherited SOTP policy permits only `CoveredEVOnly`, never complete intrinsic price/gap/score. |
| CAP-9 CapEx productivity | Partial | The bridge is correct, but “reduce quality or refuse” leaves material missing links nondeterministic. |
| CAP-10 coherent scenarios and reverse valuation | Partial | AD-10 is correct; the roadmap schedules sensitivity/reverse valuation but not an explicit joint-scenario contract and calibration gate. |
| CAP-11 point-in-time revisions | Covered with gate | Append-only clocks/outcomes are present; certified-backfill selection and retention deletion semantics remain incomplete. |

## Blocking findings

### P0-1 — The capability map does not map the driving SPEC

- **Location:** `ARCHITECTURE-SPINE.md` §Capability → Architecture Map and per-AD `Binds` annotations.
- **Contradiction:** The document renames capabilities rather than tracing their stated intents. Examples: CAP-2 is labeled “semantic normalization” although CAP-2 is the separate earnings-multiple candidate; CAP-8 is labeled “horizon-aware UX” although CAP-8 is SOTP; CAP-10 is labeled “validation/promotion” although CAP-10 is coherent scenarios and reverse valuation. Most `Binds` annotations inherit the same drift.
- **Required correction:** Replace every map row and `Binds` list using the exact CAP intent from the driving SPEC. Add a per-CAP acceptance artifact/test owner. Do not merely retain a header that says the architecture binds all eleven.
- **Consequence:** Readiness can appear complete while entire success clauses are unowned or tested under the wrong capability.

### P0-2 — `CoveredEVOnly` is incorrectly promoted to an intrinsic candidate

- **Location:** `ARCHITECTURE-SPINE.md` AD-5; Structural Seed/Capability Map references to covered SOTP; `AUTOMATION-ROADMAP.md` Slice 5.
- **Contradiction:** AD-5 says “FCFF, residual income, and covered SOTP remain intrinsic candidates.” The inherited canonical evidence-SOTP contract says incomplete coverage emits `CoveredEnterpriseValue` only and prohibits intrinsic per-share value, gap, and score.
- **Required correction:** State that complete reconciled SOTP may be intrinsic; incomplete SOTP is `CoveredEVOnly`, a diagnostic with no intrinsic price, gap, ranking, or candidate selection. Add a golden where one material component or capital-bridge item is missing and publication is refused.
- **Consequence:** Quant Lens or scoring could present partial enterprise value as a complete equity valuation.

### P0-3 — The first slice requires provenance that the available evidence cannot supply

- **Location:** `ARCHITECTURE-SPINE.md` §First Shippable Slice items 1–2; `AUTOMATION-ROADMAP.md` Slice 1; driving SPEC §Assumptions.
- **Contradiction:** The architecture requires page/section, rights, clocks, and an `analyst_stated` JPM method, while the SPEC says the original PDF is not attached and only Juan's transcription is available.
- **Required correction:** Until the report is attached and entitlement reviewed, type the golden as `user_attested_transcription` or a synthetic arithmetic fixture, with page/section absent and quality limited accordingly. It may prove `$13.00 × 28.00 = $364.00`, but it may not prove report extraction, JPM provenance, target-date precision, or publication availability. Add a separate refusal/promotion test for converting it to `analyst_stated` after source verification.
- **Consequence:** The ledger would manufacture evidence metadata, undermining CAP-1 at the first demonstration.

### P0-4 — Peer coverage policy has two incompatible minimums

- **Location:** `ARCHITECTURE-SPINE.md` AD-8 and roadmap §Política del múltiplo propio versus `valuation-method-policy.md` §Peer-policy-derived.
- **Contradiction:** Architecture refuses below five peers; the canonical companion requires only three. Both claim to govern the same provenance variant.
- **Required correction:** Amend the companion policy or declare an explicit superseding version. Recommended deterministic rule: `<5 unavailable`, `5–7 soft robust median`, `8–11 robust median subject to diagnostics`, regression only under AD-8's `>=12` and `>=5 observations per fitted coefficient`. Add boundary goldens at 4/5/7/8/11/12 and coefficient-count boundaries.
- **Consequence:** Rust, Kotlin, imports, and validation can accept different candidates from identical evidence.

## Material omissions

### P1-1 — Point-in-time eligibility lacks an executable resolver

- **Location:** `ARCHITECTURE-SPINE.md` AD-2 and Consistency Conventions §Time.
- **Gap:** `availability_basis` is named but not enumerated, and no exact rule derives `knowledge_at` or selects evidence for `decision_at`.
- **Required correction:** Define basis variants and ordering. At minimum: primary-source publication/acceptance, provider-certified vintage, and first-observed capture. First-observed evidence must use `ingested_at` as its earliest eligible date; certified historical vintages may use certified `source_available_at`. Reject conflicting clocks and future economic periods unless the metric is explicitly a forecast. Add timezone/date-boundary goldens.
- **Consequence:** Backfilled current consensus can leak into historical replays despite the stated no-look-ahead rule.

### P1-2 — CAP-6 refusal policy is descriptive, not testable

- **Location:** AD-7, AD-11, AD-16; roadmap data table and Slices 1/3.
- **Gap:** There is no canonical policy for forecast age, analyst coverage, dispersion, post-earnings/guidance invalidation, metric ambiguity, horizon mismatch, split mismatch, or source conflict; “soft,” “provisional,” and “unavailable” can vary by platform.
- **Required correction:** Assign a versioned refusal/quality contract with exact reason codes, event-expiry rules, canonical ordering, and Rust/Kotlin goldens before the relevant adapter ships. Preserve the existing rule that missing/unknown metric cannot satisfy GAAP.
- **Consequence:** The app may publish stale or incompatible EPS on one surface and refuse it on another.

### P1-3 — CapEx incompleteness has a discretionary outcome

- **Location:** `ARCHITECTURE-SPINE.md` AD-9; roadmap §CapEx de AI/AWS.
- **Gap:** “Missing links reduce quality or refuse” does not specify which links are material enough to prohibit a growth-CapEx claim.
- **Required correction:** Define a typed reconciliation state. Unsupported allocation stays in total cash outflow; a publishable CapEx-productivity adjustment requires cash plus financed CapEx, depreciation/lease burden, timing lag, and incremental revenue/margin/ROIC evidence. Missing a required material link refuses the adjusted claim; diagnostics may remain provisional without modifying FCFF.
- **Consequence:** Implementations can add back the same unsupported CapEx under different quality labels.

### P1-4 — SOTP/EPS reconciliation gates are absent from Slice 5

- **Location:** roadmap Slice 5 and architecture AD-9.
- **Gap:** The slice lists components but not the invariants needed to prove no advertising double count, GAAP/normalized reconciliation, SBC treatment, corporate overhead, or a single issuer capital bridge.
- **Required correction:** Add acceptance gates for consolidated revenue/operating-income reconciliation, advertising embedded-or-carved-out exclusivity, SBC exactly once, diluted-share roll-forward, corporate overhead as a negative component, and one debt/cash/NCI/preferred/lease bridge. A missing material component must produce `CoveredEVOnly`.
- **Consequence:** A numerically plausible Amazon SOTP can double-count advertising or omit senior claims.

### P1-5 — Joint scenarios are not scheduled as an executable deliverable

- **Location:** AD-10 versus roadmap Slice 4 and Slice 6.
- **Gap:** The roadmap schedules sensitivity and reverse valuation but does not assign a shared contract for coherent joint bear/base/bull assumptions, ordering, probabilities/weights if any, or interval calibration.
- **Required correction:** Add a scenario-policy artifact and cross-platform goldens before CAP-10 is considered covered. Sensitivity grids remain diagnostics; scenario states must jointly change drivers, EPS, CoE/risk, and multiple and preserve bear <= base <= bull.
- **Consequence:** Independent sensitivity endpoints may be relabeled as scenarios, overstating precision.

### P1-6 — Validation metrics have ambiguous targets and one unstable metric

- **Location:** roadmap §Métricas de promoción.
- **Gap:** “multiple prediction error” does not name the non-circular outcome, and EPS MAPE is undefined or explosive around zero/negative EPS. Promotion has no frozen cohort/minimum sample, baseline, tolerance, or no-regression rule.
- **Required correction:** Define targets per model identity: analyst-method reproduction, forecast EPS/driver outcomes, and peer-multiple stability separately; keep subsequent price/return secondary. Replace unconditional EPS MAPE with MAE plus a scale-safe metric and an explicit negative/near-zero EPS policy. Freeze promotion datasets and thresholds before challenger evaluation.
- **Consequence:** A challenger can win by optimizing an undefined or price-correlated objective.

### P1-7 — Coordinator failure and crash states are incomplete

- **Location:** AD-11.
- **Gap:** The state machine has `computed | refused` terminal states while also promising cancellation, deadlines, budgets, circuit breakers, and idempotent persistence. It does not specify cancelled, timed-out, budget-exhausted, provider-partial, or crash-after-freeze recovery.
- **Required correction:** Type terminal/interrupted states, define which are retryable, persist the frozen evidence-set identity before compute, and make run publication transactional/idempotent. A partial fetch must never publish a complete candidate.
- **Consequence:** Interrupted builds may strand locks, duplicate paid calls, or publish partial dossiers.

### P1-8 — Append-only replay conflicts with licensed retention deletion

- **Location:** AD-1, AD-12, AD-15 and Stack §Content-addressed local app data.
- **Gap:** Rights/retention metadata exists, but no purge/tombstone protocol explains how an entitled raw artifact is deleted while immutable observations/runs remain.
- **Required correction:** Define retention enforcement, encrypted-blob deletion, permissible derived-data retention, tombstones, and the resulting replay state (`unreplayable_due_to_rights` rather than silently reproducible). Hashes must not be treated as permission to retain restricted derived content.
- **Consequence:** The local ledger may violate provider terms or falsely claim a run is replayable.

### P1-9 — SHA-256 identity lacks a canonical byte contract

- **Location:** AD-12.
- **Gap:** “Canonical serialization” is not named or versioned; field ordering, Unicode normalization, absent versus null, numeric encoding, and attachment byte identity remain implementation choices.
- **Required correction:** Specify canonical bytes and hash-domain separation for raw artifact, normalized evidence set, and run identity. Add cross-language Unicode/null/order mutation fixtures and checked-arithmetic overflow refusals.
- **Consequence:** Rust and Kotlin can hash semantically identical evidence differently and invalidate caches inconsistently.

### P1-10 — Target horizon invents day precision

- **Location:** AD-6; roadmap labels `target_date=2027-12` while other clauses require a date.
- **Gap:** The source claim is “December 2027,” not necessarily a specific day. A date-only field can silently fabricate December 31 and later change present-equivalent discounting.
- **Required correction:** Type horizon precision (`exact_date`, `month_end_label`, `fiscal_period`, provider horizon) and require day-count transformations to refuse unless a policy resolves the imprecision visibly.
- **Consequence:** Two implementations can discount the same analyst target over different horizons.

## Editorial and operational findings

### P2-1 — Two FRED documentation links are malformed

- **Location:** roadmap §Datos necesarios y cómo obtenerlos.
- **Fix:** Use `https://fred.stlouisfed.org/docs/api/fred/series_observations.html` and `https://fred.stlouisfed.org/docs/api/fred/series_vintagedates.html`.
- **Consequence:** Implementers land on 404s when validating the PIT source.

### P2-2 — `status: final` is premature

- **Location:** `ARCHITECTURE-SPINE.md` frontmatter.
- **Fix:** Mark `changes-required` or `review` until P0 findings are resolved and the CAP map is re-walked.
- **Consequence:** Downstream agents may treat contradictory policy as approved architecture.

### P2-3 — Preserve the two-document structure; reduce duplicated policy prose only after contract repair

- **Lens:** structure/prose.
- **Assessment:** The explanation model is appropriate: a 2,267-word architecture reference plus a 1,798-word operator roadmap. The roadmap repeats some invariants, but those repetitions help Juan understand why the delivery order exists. No large cut is recommended. After P0/P1 repair, consolidate only duplicated peer thresholds and failure-state wording into links to one canonical policy (estimated reduction: 80–120 words, about 2–3% combined). Preserve the executive diagnosis, flow diagram, Amazon worked example, and delivery slices.

## Approval gate

Approval requires:

1. Resolve P0-1 through P0-4 in the architecture and canonical companions.
2. Re-run CAP-1..11 traceability using the exact SPEC intents and success clauses.
3. Make P1-1, P1-2, P1-7, P1-9, and P1-10 executable before Slice 1 persistence/import work.
4. Make P1-3 through P1-6 executable before Slices 4–6.
5. Make P1-8 executable before storing any licensed raw artifact.
6. Keep the existing mandatory gates: subject-price/target mutation invariance, exact Rust/Kotlin parity, valuation baselines, Quant Lens correlation checks, and live QA only under profile `qa`.

Once those conditions are met, the architecture can be approved without changing its central design paradigm.

## Re-review 2026-08-01

Re-read the corrected `ARCHITECTURE-SPINE.md`, `AUTOMATION-ROADMAP.md`, driving SPEC, and `valuation-method-policy.md` against the original gate.

| Finding | Status | Corrected contract / gate |
| --- | --- | --- |
| P0-1 CAP map drift | Resolved | The capability map and AD `Binds` now use the exact CAP-1..11 intents and name acceptance artifacts/goldens. |
| P0-2 covered SOTP promoted as intrinsic | Resolved | AD-5 and Slice 5 restrict intrinsic status to complete reconciled SOTP; incomplete coverage is `CoveredEVOnly` without per-share value, gap, ranking, or selection. |
| P0-3 unverifiable JPM provenance | Resolved | SPEC assumptions, AD-15, and Slices 1A/1B separate `fixture_transcription` and `manual_transcription_unverified`, omit invented page data, preserve month precision, and store no proprietary bytes. |
| P0-4 peer minimum conflict | Resolved | Architecture, roadmap, and method policy agree on `<5` refusal, 5–7 soft median, 8–11 diagnostic-gated median, and regression at `>=12` plus five observations per coefficient. |
| P1-1 PIT resolver missing | Resolved | AD-2 defines availability bases, operational versus certified-backfill replay, UTC clock eligibility, fingerprints, and live-path isolation. |
| P1-2 refusal policy not testable | Resolved as prerequisite gate | The roadmap requires a versioned admission/refusal contract with exact ordered reason codes and cross-platform cases before each adapter; CAP-6 owns its goldens. |
| P1-3 discretionary CapEx outcome | Resolved | AD-9 defines `Unsupported`, `DiagnosticProvisional`, and `Reconciled`; only the last may adjust a claim, and the others leave cash outflow unchanged. |
| P1-4 SOTP/EPS reconciliation gates absent | Resolved | Slice 5 now gates consolidated reconciliation, advertising exclusivity, SBC, diluted shares, overhead, and the single issuer capital bridge. |
| P1-5 joint scenarios unscheduled | Resolved | Slice 4 now requires a shared dependency-aware scenario policy, exact ordering, reverse goldens, and calibration gates. |
| P1-6 ambiguous validation metrics | Resolved as Slice 6 release gate | EPS uses scale-safe error with an explicit non-positive policy; cohorts, sample, baseline, tolerances, and no-regression rules freeze before evaluation, while target/return remain secondary diagnostics. The Slice 6 contract must name the comparison outcome for each multiple-family metric before the first challenger run. |
| P1-7 coordinator terminal/crash states missing | Resolved | AD-11 separates the atomic Slice 1 service from the Slice 2 coordinator, types interrupted states, and permits publication only from a fully frozen/committed run. |
| P1-8 retention conflicts with append-only replay | Resolved | AD-15 makes Slice 1 metadata-only and defines authorization, atomic vault writes, purge tombstones, and `unreplayable_due_to_rights` for any future artifact vault. |
| P1-9 SHA-256 canonical bytes missing | Resolved | AD-12 and Foundation 0A specify domain separation, lengths, nulls, big-endian integers, NFC, set ordering, raw-byte hashes, and parity/mutation goldens. |
| P1-10 target horizon invents a day | Resolved | AD-6 and the method policy type date precision; the Amazon claim remains `month_label` and cannot drive day-count, annualized-return, or present-equivalent calculations. |

No new blocker was found. The corrected package also closes the brownfield seams that were previously implicit: V1 evidence is preserved rather than reinterpreted, Rust/Kotlin equal-rank conflict parity is a Foundation 0A gate, migrations and current-projection invalidation are transactional, the new lane cannot write legacy intrinsic scalars, and Desktop fails closed for the unsupported lane.

**Final verdict: APPROVE.** Approval applies to the architecture and ordered roadmap. It does not waive the named pre-slice contracts or the project's TDD, exact parity, valuation baseline, correlated-evidence, and profile-`qa` live gates.
