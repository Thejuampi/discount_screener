# Implementation Plan — Post-PR #27 Valuation / Quant Continuity

**Baseline:** `main` @ `923df76` — evidence-routed operating valuation on Windows (pure + runtime + Detail/Quant Lens), residual income + COF payout fix, shared router contract, Android **pure** `OperatingValuation` / `EvidenceSotp` only (no Android app runtime).

**Authority:** `AGENTS.md`, `_bmad-output/project-context.md`, `valuation-model-family-architecture.md` (AD-VM-011), `valuation-model-change-decision-2026-07-31.md`, `spec-productionize-evidence-routed-operating-valuation.md` (status done; Ask First for Android runtime — **now approved**).

**Date:** 2026-08-01

---

## 1. Goals & non-goals

### Goals

| ID | Goal |
| --- | --- |
| **G0** | Capture a live Windows `qa` baseline so later engine work has a known regression surface (T/AAPL FCFF; AMZN/ORCL/MU CapEx/distortion; CI/JPM/ACGL/COF residual income in Detail; unclassified refuse). |
| **G1** | Ship Android **app/runtime** parity for evidence-routed operating valuation: demand-only `earningsTrend`, selected/disputed/unavailable, fingerprints, Detail + Quant Lens projection, cent-for-cent with Windows pure/runtime contracts. |
| **G2** | Replace crude “latest SEC year < current_year − 1” discontinuity with a **dynamic source-continuity gate** (SEC fiscal/entity vs current Yahoo fundamentals) so SNDK-class stale SEC cannot silently drive FCFF; enable forward candidacy or typed unavailable. |
| **G3** | Replace ad-hoc industry beta string table / PoC commodity floors with **versioned through-cycle sector·industry beta policy** + CoE provenance (DVN-class). |
| **G4** | Generalize financial residual-income **driver surface** (payout/retention, book, ROE) across bank/insurer/broker fixtures so providers already returning data do not yield false unavailable. |
| **G5** | Expand shared contracts + `valuation_baseline` for distortion routes; **0 quarantine** on active 20-slot cohort; offline analyst diagnostics only in PoC scripts. |

### Non-goals

- Intrinsic/price hard caps; blend-to-Street/target as model input; proximity acceptance tests as runtime truth.
- Replacing FCFF universally with forward EPS (rejected holdout).
- Desktop terminal parity for evidence router (explicitly deferred unless Juan reopens).
- Persisting full operating routes in SQLite (still Ask First / out of this program unless needed for warm-start).
- Changing dispute threshold (5000 bps) or forecast coverage/freshness policy without a separate decision.
- Full-universe `earningsTrend` fetch; FCFF-on-float for financials; inventing bear/bull for earnings-power.
- Measuring success by PoC MAE bands as production clamps.
- Multi-user / onboarding / BMAD full ceremony for these slices (Express/Direct + durable specs only).

---

## 2. Dependency graph

```text
P0 Live QA capture (ops)
 │
 ├──► P2 Source-continuity gate ──────────────┐
 │         │                                  │
 │         ▼                                  │
 │    P5 Distortion fixtures / baseline  ◄────┼── also needs stable P3 CoE if goldens include rates
 │                                            │
 ├──► P3 Through-cycle beta policy ───────────┘
 │         │
 │         └──► CoE inputs used by forward candidate (Windows + Android pure)
 │
 ├──► P4 Financial driver surface (mostly independent of router)
 │
 └──► P1 Android runtime
           depends on: frozen pure contract (exists), Windows runtime patterns (exists),
           should absorb P2 continuity + P3 beta once those land (or re-port if early-wired)
```

| From | To | Why |
| --- | --- | --- |
| P0 | all engine tracks | Regression oracle before numbers move |
| Pure contract (done) | P1, P5 | Router arithmetic already shared |
| Windows runtime (done) | P1, P2, P5 | Pattern + orchestration authority |
| P2 | P5 | Continuity must be pin-able offline evidence |
| P3 | P1, P5, forward CoE | Beta table changes CoE cents/fingerprints |
| P4 | Android RI path only | Orthogonal to operating router; feeds P0 COF-class QA |
| P2+P3 | P1 merge readiness | Avoid double Android rewrites of continuity/CoE |

**Hard product invariants (every track):** closed-world refuse; FinancialServices → RI only; no price/target in router; Yahoo forward + Yahoo target = one Quant Lens family; multi-name merge bar; live QA = profile `qa`.

---

## 3. Concurrency matrix

| Track | Can start after | Parallel with | Must not parallel-edit |
| --- | --- | --- | --- |
| **A — P0 Live QA** | main green | Everything (ops only; no source edits) | N/A (docs notes OK under `_bmad-output/implementation-artifacts/`) |
| **B — P2 Continuity** | P0 capture preferred | P4; P3 design; late P1 shell stubs | P5 fixture pins for continuity rows; `operating_valuation_runtime` ownership with P5 |
| **C — P3 Beta policy** | P0 preferred | P2 pure design; P4 | `dcf_model.rs` CoE + Android `DcfAnalysisEngine` CoE; shared beta policy file with P1 if P1 ports CoE early |
| **D — P4 Financial drivers** | anytime after P0 | P2, P3, early P1 | `quote_summary` retention parse only if B/C touch same lines carefully (usually safe) |
| **E — P1 Android runtime** | pure core exists; **prefer after P2+P3 pure land** for one-shot parity | P4; pure-contract tests only while Windows owns runtime | Windows `operating_valuation_runtime`, `commands`, `engine`, `quant_lens`; shared golden **values** while P3/P5 mutate them |
| **F — P5 Fixtures** | **after P2** (and ideally after P3 if CoE in fixtures) | P1 (if only Android app files) | `operating-valuation-router-v1.json` cohort inputs; `valuation_baseline` fixture; runtime distortion tokens |

**Safe parallel pairs:** P2∥P4, P3∥P4, P0∥any, P1(app-only)∥P4, P5∥P1(app-only) after P2 freeze.  
**Sequential chains:** P0 → (P2∥P3∥P4) → P5; P2+P3 → P1 merge; any CoE/policy bump → both platforms’ pure tests + baseline.

---

## 4. Task breakdown (P0–P5)

### P0 — Live QA baseline (operational)

**Owner modules**

- Ops / checklist: [`docs/valuation-live-qa-checklist.md`](../../docs/valuation-live-qa-checklist.md)
- Optional capture note: `_bmad-output/implementation-artifacts/live-qa-baseline-post-pr27-YYYY-MM-DD.md`
- Backend probe: `get_symbol_detail` via CDP (`http://127.0.0.1:9222`) as documented in checklist
- COF native e2e (if Detail contract doubt): `apps/windows` → `npm run test:e2e:native:cof`

**Acceptance criteria**

- [ ] All automated gates green on `main` before claiming “baseline clean.”
- [ ] Checklist rows 1–7 exercised on **profile `qa` only**.
- [ ] COF Detail shows residual income when Yahoo `summaryDetail` payout present (or explicit missing-driver reason if provider empty).
- [ ] No “backend green / UI mute dash” for known refuse/RI cases.
- [ ] Capture artifact under `_bmad-output/implementation-artifacts/`.

**Verification**

```text
cd apps/windows/src-tauri
cargo test --lib dcf_model::
cargo test --lib valuation_baseline::
cargo test --lib quant_lens::
cargo test --lib operating_valuation::
cd ../
npm run tauri:dev:qa
# optional: npm run test:e2e:native:cof
```

---

### P1 — Android runtime parity of evidence-routed router

**Deferred until P2+P3 land.** See full planner output in session notes.

**Acceptance criteria (summary)**

- [ ] Operating demand path produces selected | disputed | unavailable only.
- [ ] Cent-for-cent pure + fixture runtime parity vs Windows.
- [ ] `earningsTrend` not in full-universe refresh.
- [ ] Forward soft + correlated; Strong not crowning from target+forward alone.
- [ ] `scripts/validate-android.ps1` green.

---

### P2 — Source-continuity gate (SNDK-class)

**Owner modules**

| Concern | Path |
| --- | --- |
| Gate pure logic | New `source_continuity.rs` or pure section of `operating_valuation_runtime.rs` + Kotlin twin |
| SEC side | `sec_normalization.rs`, `edgar.rs` |
| Distortion emission | `derive_structural_distortions` in `operating_valuation_runtime.rs` |
| Contracts | `operating-valuation-router-v1.json` reason tokens; optional `source-continuity-v1.json` |

**Acceptance criteria**

- [ ] SNDK-class: stale/short SEC vs large current Yahoo cash → discontinuity + no silent absurd FCFF primary.
- [ ] Continuous issuer: gate does not force forward without other distortions.
- [ ] Reasons deterministic + versioned; fingerprints include continuity policy version.
- [ ] Merge bar green.

---

### P3 — Through-cycle / industry beta policy (DVN-class)

**Owner modules**

| Concern | Path |
| --- | --- |
| Windows CoE | `dcf_model.rs` (`industry_beta_millis`, `resolve_cost_of_equity`) |
| Android CoE | `DcfAnalysisEngine.kt` |
| Policy | `shared/contracts/industry-beta-policy-v1.json` |

**Acceptance criteria**

- [ ] Versioned table is sole prior source.
- [ ] DVN-class CoE not “bond-like” solely from low trailing beta; provenance cites policy version.
- [ ] No per-ticker magic floors.
- [ ] Merge bar green.

---

### P4 — Financial driver surface (COF-class generalization)

**Owner modules**

| Concern | Path |
| --- | --- |
| Windows Yahoo parse | `quote_summary.rs` |
| Android Yahoo | `YahooFinanceClient.kt` |
| Fixtures | bank / insurer / consumer-finance quoteSummary fixtures |

**Acceptance criteria**

- [ ] ≥3 financial fixtures resolve retention when provider has summaryDetail payout.
- [ ] Missing book/ROE/retention → specific reason codes; no FCFF-primary.
- [ ] COF native e2e still green if Detail path touched.

---

### P5 — Router holdout + distortion baseline fixtures

**After P2 (+ ideally P3).** Pins route status/model/reasons/fingerprints — not Street proximity. Active cohort quarantine = 0.

---

## 5. Branch / worktree strategy

| Track | Branch | Base | Merge order |
| --- | --- | --- | --- |
| P0 | docs-only optional | main | anytime |
| P4 | `valuation/p4-financial-driver-surface` | main | Early |
| P2 | `valuation/p2-source-continuity-gate` | main | Before P5, before P1 |
| P3 | `valuation/p3-through-cycle-beta-policy` | main | Before P5/P1 |
| P5 | `valuation/p5-distortion-baseline-fixtures` | main + P2 (+P3) | After P2/P3 |
| P1 | `valuation/p1-android-evidence-runtime` | main + P2+P3 | Last major |

**Worktrees:** `.worktrees/p2-source-continuity`, `.worktrees/p3-through-cycle-beta`, `.worktrees/p4-financial-drivers`, etc.

**Merge order:** P0 note → P4 → P2 → P3 → P5 → P1 → final dual-platform live QA.

---

## 6. File ownership (parallel agents)

| Path | Owner |
| --- | --- |
| `operating_valuation_runtime.rs` distortion/continuity | **P2** |
| `dcf_model.rs` CoE/beta | **P3** |
| `dcf_model.rs` RI only + `quote_summary` payout | **P4** |
| `YahooFinanceClient.kt` fundamentals/payout | **P4**; `earningsTrend` only **P1** |
| `DefaultDashboardRepository.kt` / Android Detail | **P1** |
| `valuation_baseline` + router goldens | **P5** (after P2) |
| Desktop | **Nobody** this program |

**Conflict protocol:** if two tracks need `commands.rs` / `engine.rs` / full `DcfAnalysisEngine.kt`, serialize: P4 → P2 → P3 → P1.

---

## 7. Definition of done (whole program)

1. P0 live `qa` capture documented.  
2. P2 dynamic continuity gate live; SNDK-class covered.  
3. P3 versioned industry/through-cycle beta on both pure engines.  
4. P4 multi-name financial fixtures; false unavailable reduced; RI-only.  
5. P5 distortion routes in contracts/baseline; 0 quarantine.  
6. P1 Android demand runtime + Detail/Quant Lens parity.  
7. Absolute constraints still hold; merge bars green.  
8. Standing rules promoted into `project-context.md` when new.  
9. Live QA re-run after engine tracks.

---

## 8. Orchestrator execution order

| Step | Action |
| --- | --- |
| 1 | P0 live QA / automated gates on main |
| 2 | Parallel: P4, P2, P3 worktrees |
| 3 | Merge P4 → P2 → P3 |
| 4 | P5 on post-P2(+P3) main |
| 5 | P1 Android runtime rebased on P2+P3 |
| 6 | Final Windows + Android `qa` smoke |

**Do not** start P5 golden freezes before P2 semantics exist.  
**Do not** merge P1 first if it reimplements crude discontinuity.  
**Do not** open full BMAD PRD/epic/sprint for these.
