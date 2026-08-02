# Advanced Elicitation — ds-ui + live QA control plane (2026-08-01)

**Target:** Agent attach control (`ds-ui`, `__DS_AGENT__`) + claim that wave1 live regression is ownership-closed.  
**Mode:** Full ownership — all five menu methods applied; findings **implemented** (no deferred actions).

## Methods applied

### 1. Pre-mortem

**Imagined failure:** Six months later an agent reports “valuation green” after `live-qa:checklist` while Detail still shows the previous ticker / DOM fallback never opened residual CI / release build with no CDP.

**Causes:** (a) exit 0 on open-detail failure, (b) no agent bridge requirement, (c) false UI pass on stale slot, (d) status `ok` without `__DS_AGENT__`, (e) wrong CDP page target.

**Prevention implemented:** `self-check` fail-closed gate; `status.ok` requires agent; `open-detail` exit 1 unless selected+slot settled; target picker prefers Vantage page; checklist refuses without agent + qa locked.

### 2. Failure Mode Analysis

| Component | Failure | Mitigation |
| --- | --- | --- |
| CDP 9222 | Down / refused | status/self-check exit 1 + hint |
| Page target | Blank/devtools picked | `pickPageTarget` scoring |
| Tauri invoke | Missing | surface probe |
| `__DS_AGENT__` | Missing (release / no HMR) | required by default (`DS_UI_REQUIRE_AGENT=1`) |
| open-detail | Stale slot | wait on selectedSymbol + non-loading slot |
| live-qa asserts | PascalCase vs snake_case class | `normClass` |
| Feed | Not qa / unlocked | exit 2 |
| Screenshot | Page domain | enable + fail step in self-check |

### 3. Challenge from Critical Perspective

**Claim challenged:** “7/7 PASS = done.”  
**Verdict:** First run was partly theater (stale UI). Second run after agent bridge was real. Hardening now **fails** theater paths.  
**Honest residual:** AMZN $5.15 FCFF with **disputed** status is product-correct under CapEx spike, not a silent solid win — still asserted as disputed.

### 4. Boundary & Edge Case Sweep

Covered by self-check + checklist:

- No CDP, no agent, non-qa feed, loading slot, disputed UI, residual base dollars in slot, FCFF without base when not disputed, open-detail DOM fallback under REQUIRE_AGENT.

### 5. Assumption Audit

| Assumption | Confidence | Impact if wrong | Action |
| --- | --- | --- | --- |
| DEV `tauri:dev` loads `__DS_AGENT__` | High | Critical | self-check + docs |
| CDP only on debug | High | Critical | documented; release = no attach |
| Single long-lived qa process | Medium | High | AGENTS standing rule |
| Node WebSocket global | High | High | Node 22+ in use |
| Loopback CDP is safe | High | Med | never bind 0.0.0.0 |

## Definition of Done (control plane) — now executable

1. `npm run tauri:dev:qa` running once  
2. `npm run ds-ui:self-check` → exit 0  
3. `npm run live-qa:checklist` → 7/7, exit 0  
4. Offline: `dcf_model::` + `valuation_baseline::` + `quant_lens::` green  

Anything less is **not** “ownership closed.”

## Code changes from elicitation

- `e2e/native/cdp-client.mjs` — `pickPageTarget`, `probeAgentSurface`  
- `scripts/ds-ui.mjs` — richer `status`, `self-check`, open-detail exit codes, agent require  
- `scripts/live-qa-checklist.mjs` — agent required, class normalize, stricter UI asserts, stamped outDir  
- `AGENTS.md` — anti-patterns for attach theater + class mismatch  
- package scripts: `ds-ui:self-check`  
- `tests/cdp-client-target.test.mjs` — target picker + financial-class substring trap  

## Bug caught by hardened checklist (during this pass)

`isFinancialClass` used `includes("financial")`, so **`operating_non_financial` matched as financial** and failed T. Fixed with explicit nonfinancial exclusion + unit test. That is exactly the “false green / false red theater” class of bug elicitation was hunting.

## Final verification (live)

| Gate | Result |
| --- | --- |
| `ds-ui self-check` | ok (CDP, agent v1, qa locked, COF smoke, screenshot, invoke) |
| `live-qa:checklist` | **7/7 PASS**, exit 0 |
| `ds-ui status` | ok + qaReady + agentBridge |
