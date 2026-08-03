# Live QA baseline capture — post-PR #27

**Track:** P0 (ops / regression oracle) — plan  
[`plan-valuation-p0-p5-2026-08-01.md`](plan-valuation-p0-p5-2026-08-01.md)

**Date:** 2026-08-01  
**Branch:** `valuation/p0-live-qa-baseline`  
**Baseline main commit:** `32b5c966133d7e6513713f8a1143e3380edb83e6`  
(`docs: plan valuation P0-P5 continuity tracks after PR #27`)  
**PR #27 merge on ancestry:** `923df76` (*Merge pull request #27 … explicit-driver-resolution-parity*) — ancestor of this tip.  
**Profile rule:** live UI QA = **`qa` only** (≤20 symbols, locked). Never cold-start full SP500 for this track.

**Scope of this capture:** automated merge bars + capture template. **Live Detail / Quant Lens UI rows are not exercised in this session** (no `tauri:dev:qa` process; no headless Detail-slot proof). Fill `PENDING_LIVE` → `PASS`/`FAIL` on a long-lived `npm run tauri:dev:qa` session per [`docs/valuation-live-qa-checklist.md`](../../docs/valuation-live-qa-checklist.md).

---

## 1. Automated gates (Windows `apps/windows/src-tauri`)

Run from: `apps/windows/src-tauri` on commit `32b5c96` (worktree branch above).

| Gate | Command | Result | Counts |
| --- | --- | --- | --- |
| FCFF / RI / classifier | `cargo test --lib dcf_model::` | **PASS** | **35** passed; 0 failed; 0 ignored |
| Multi-name baseline | `cargo test --lib valuation_baseline::` | **PASS** | **10** passed; 0 failed; **1** ignored (`live_headless_current_engine_poc`) |
| Quant Lens / EV | `cargo test --lib quant_lens::` | **PASS** | **8** passed; 0 failed; 0 ignored |
| Operating valuation router | `cargo test --lib operating_valuation::` | **PASS** | **13** passed; 0 failed; **1** ignored (`headless_fixed_point_operating_core_poc`) |

**Aggregate Windows lib merge bar:** **66** executed tests green; **2** intentionally ignored live/headless PoCs.

Compile notes (non-blocking): lib test build emits existing `dead_code` warnings in `dcf_model` / `edgar` / related; **0 failures**.

### Optional Android pure core

| Gate | Command | Result | Counts |
| --- | --- | --- | --- |
| Android pure domain | `apps/android` → `.\gradlew.bat :core:test` | **PASS** | **282** tests; 0 failures; 0 errors; **1** skipped |

Feasible without full app SDK install in this environment. Full `scripts/validate-android.ps1` / app tasks not required for P0 docs capture.

### Optional native COF Detail e2e

| Gate | Command | Result | Notes |
| --- | --- | --- | --- |
| COF native e2e | `apps/windows` → `npm run test:e2e:native:cof` | **NOT_RUN** | Worktree has **no** `apps/windows/node_modules`. Script exists (`e2e/native/cof-detail.native.e2e.mjs` → `tauri build --debug --no-bundle` then Node probe). Not started: avoids full frontend install + debug Tauri build for docs-only P0. Does **not** prove `.price-summary .dcf-slot`. |

---

## 2. Live UI checklist (profile `qa`)

**Process requirement when filling rows:** one long-lived `npm run tauri:dev:qa` from `apps/windows`; leave launch lock on; one-shot load missing names only.

Status legend:

| Status | Meaning |
| --- | --- |
| `PENDING_LIVE` | Not exercised on a live `qa` UI / CDP Detail slot in this capture |
| `PASS` | Observed on live `qa` (or e2e that asserts Detail slot) and matches expected path |
| `FAIL` | Observed and wrong path / mute dash / absurd path |

| Symbol | Expected path | Status | Notes |
| --- | --- | --- | --- |
| **T** | FCFF operating; not FCF≈OCF; soft/provisional rates not sold as solid | **PENDING_LIVE** | Automated: `dcf_model` T-class + shared T contract green. Need Detail label + soft badge on live `qa`. |
| **AAPL** | FCFF operating; OOM sanity vs market | **PENDING_LIVE** | Operating FCFF suite green offline. Live Detail still required. |
| **AMZN** | Ordered bear≤base≤bull; not penny / inverted | **PENDING_LIVE** | Offline: `amzn_capex_trough_*`, `baseline_megacap_amzn_class_not_penny_intrinsic` green. |
| **ORCL** / **MU** | CapEx / cycle distortion awareness; MU not silent OOM | **PENDING_LIVE** | Offline: `mu_cycle_*`, `baseline_mu_class_order_of_magnitude_is_detected` green. Prefer names already in QA 20; one-shot if missing. |
| **CI** | **Residual income** (managed care / financials) — not FCFF float | **PENDING_LIVE** | Offline: `ci_like_managed_care_*`, `baseline_ci_managed_care_not_fcff_primary` green. |
| **JPM** / **ACGL** | Residual income / financial; not FCFF-primary | **PENDING_LIVE** | Offline: `acgl_uses_residual_income_*`, `baseline_financials_safety_acgl_not_fcff_primary` green. |
| **COF** | Residual income when Yahoo `summaryDetail` payout present; else explicit missing-driver reason | **PENDING_LIVE** | History includes native COF contract coverage on main (`5eb3142`); live Detail + optional `test:e2e:native:cof` not re-run here. |
| *Unclassified / garbage sector* (if forceable) | Slot **unavailable** + refuse copy; no invented DCF | **PENDING_LIVE** | Offline: unclassified refuse tests green. UI must show `valuation_unavailable_reason` / i18n refuse — not mute “—”. |

### Detail slot presentation (when live)

Confirm per checklist:

- Loading → “Valoración…” only  
- Soft rates → value + “no confiable aún”  
- Unclassified → unavailable + categoría no catalogada  
- Financial RI → residual income path, not FCFF DCF copy  
- No backend-green / UI-mute-dash for known RI or refuse cases  
- Prefer scoped assert on `.price-summary .dcf-slot` (not whole Detail panel text)

### Backend probe (debug builds only)

With WebView2 CDP at `http://127.0.0.1:9222` (debug only):

```js
await window.__TAURI_INTERNALS__.invoke("get_symbol_detail", { symbol: "COF" })
```

Capture `dcf_analysis`, `valuation_status`, `valuation_unavailable_reason`, and rendered `.detail-panel` / dcf-slot text together.

---

## 3. P0 acceptance checklist (this artifact)

| Criterion | State |
| --- | --- |
| Automated gates green on main tip before engine work | **Met** (all four Windows lib filters + Android `:core:test`) |
| Capture artifact under `_bmad-output/implementation-artifacts/` | **Met** (this file) |
| Checklist rows 1–7 exercised on profile `qa` | **Open** — all live rows `PENDING_LIVE` |
| COF Detail residual income (or explicit reason) on live | **Open** |
| No mute dash for known refuse/RI | **Open** (needs live UI) |

**Baseline claim:** automated multi-name merge bar is **clean** on `32b5c96`. Live UI oracle is **not yet filled** — do not treat this file alone as full P0 live sign-off until `PENDING_LIVE` rows are completed on `qa`.

---

## 4. Blockers / follow-ups for live QA

| Item | Detail |
| --- | --- |
| Live app not started | By design for this prep commit (docs-only; no full-universe launch). Operator must run `npm run tauri:dev:qa` and fill the table. |
| `node_modules` missing in worktree | Blocks `npm run test:e2e:native:cof` until `npm install` in `apps/windows` + debug Tauri build. |
| Two ignored headless PoCs | `valuation_baseline::live_headless_current_engine_poc`, `operating_valuation::headless_fixed_point_operating_core_poc` — not part of required merge bar; do not count as green live proof. |
| QA membership | Checklist names may need **one-shot** `ensure_symbol_loaded` if absent from the top-20 `qa` sample; never switch universe to `sp500`. |
| Product code | Untouched in this P0 prep. |

---

## 5. How to complete the live half

```text
# from apps/windows — REQUIRED profile
npm run tauri:dev:qa

# optional after npm install (proves COF Detail slot, not full checklist)
npm run test:e2e:native:cof
```

Then walk symbols T, AAPL, AMZN, ORCL/MU, CI, JPM/ACGL, COF (+ refuse case if reproducible), update statuses in §2, and append observed labels / refuse reasons / base cents if useful for later P2–P5 diffs.

---

## 6. Provenance

| Field | Value |
| --- | --- |
| Capture authoring | Agent P0 prep session 2026-08-01 |
| Plan | `_bmad-output/implementation-artifacts/plan-valuation-p0-p5-2026-08-01.md` §P0 |
| Checklist authority | `docs/valuation-live-qa-checklist.md`, `AGENTS.md` live QA = `qa` |
| Product code changed | **None** |
