# Project Guidelines

Agent-facing rules for Discount Screener. Prefer this file plus [`_bmad-output/project-context.md`](_bmad-output/project-context.md) before inventing valuation, ranking, or UI semantics.

## Product Audience

Discount Screener is currently a personal workstation for **Juan**, a single self-directed analyst/investor. Treat multi-user growth metrics, onboarding funnels, and generic consumer personas as out of scope unless Juan explicitly asks for them. Preserve professional, evidence-first presentation, provenance, uncertainty, and the no-investment-advice boundary.

## Monorepo Layout

| Path | Role |
| --- | --- |
| `apps/desktop` | Rust terminal workstation (`lib.rs`, `market_data.rs`, `persistence.rs`, `workstation/*`) |
| `apps/windows` | Tauri/React Windows workstation (`src-tauri/src/*`, `src/*`) |
| `apps/android` | Kotlin: `core/` pure domain, `app/` imperative shell |
| `shared/contracts` | Cross-platform goldens (ranking, DCF source, **valuation model family**) |
| `docs/` | Operator docs and indexes |
| `_bmad-output/` | Planning artifacts and project-context |

- Keep reusable business logic in the **owning module** (desktop `lib`/workstation, Windows `dcf_model`/`quant_lens`/`engine`, Android `core`). Entry points only orchestrate.
- Android: Compose screens are passive Views; presenters map state; **all valuation and scoring rules stay in `core`**.
- Prefer extending the module that owns a concern over cross-cutting hacks in UI shells.

## BMAD Method (when to use — not always)

BMAD is installed for this repo (`_bmad/`, skills under `.grok/skills` / `.agents/skills`, artifacts under `_bmad-output/`). Treat it as a **menu of workflows**, not a mandatory pipeline for every task.

You do **not** need to master the full BMAD catalog to work here. Learn **lanes + a few skills**; invoke `bmad-help` only when routing is unclear. Shipping code correctly under this file and `project-context.md` always beats performing BMAD ceremony.

**Default: do not start a full BMAD ceremony** (brief → PRD → UX → architecture → epics → readiness → sprint) unless the user asks for planning depth or the change is large enough to justify it. Prefer normal implementation against this file + `project-context.md`.

### Lanes by work size

| Work size | Default lane | Use |
| --- | --- | --- |
| Bugfix, rename, small tweak, spike, “just ship it” | **Direct** | Implement with TDD / existing tools; skip BMAD skills unless the user invokes one |
| Clear feature / refactor with bounded scope | **Express** | `bmad-quick-dev` (optional `bmad-code-review` after) |
| Ambiguous intent, multi-surface contract, or “lock the WHAT” | **Spec** | `bmad-spec` → then implement (`bmad-quick-dev` or direct) |
| Large / cross-platform / domain-hard change (valuation, ranking, Quant Lens semantics) | **Planning** | Only as needed: decision/spec + `bmad-prd` and/or `bmad-architecture` → implement directly with TDD and required gates |
| Idea still unproven | **Forge / recon** | `bmad-forge-idea` or `bmad-deep-recon` — not a full PRD yet |
| Lost in brownfield process state | **Help** | `bmad-help` once; do not dump the whole skill catalog |

### Full-scope new feature (not MVP/PoC) — planning lane only

When the user wants a **complete** feature from zero (new estimation model, full new screen/flow, multi-platform slice), use the long path **in order** — do not skip to code and do not invent a private process:

1. Read existing `_bmad-output/` + this file + `project-context.md` (reuse; don’t rewrite).
2. Optional: forge/recon if the idea is still soft.
3. `bmad-prd` → `bmad-ux` if UI → `bmad-architecture` (invariants + owning modules) → epics/stories → `bmad-check-implementation-readiness`.
4. Once the product/architecture decision is sufficient: implement directly, using focused `bmad-quick-dev` sessions when useful. Do not add sprint machinery unless Juan explicitly asks for it.
5. If reality diverges mid-build: `bmad-correct-course` and update artifacts — do not silently ignore the plan or silently rewrite everything.
6. When new hard product rules appear, update `project-context.md` / this file — not only chat memory.

**Stop and implement only when** the material product decisions, architecture invariants, executable scope, and verification gates exist for that slice (or the user explicitly waives planning). These can be lean documents; no sprint or role ceremony is required.

### Highest-value skills (prefer these)

| Priority | Skill | Role |
| --- | --- | --- |
| 1 | Existing artifacts + `project-context.md` | Source of truth; read before writing |
| 2 | `bmad-quick-dev` | Default structured implement loop |
| 3 | `bmad-spec` | Lock WHAT when intent is muddy |
| 4 | `bmad-help` | Router when lost (once, not a tour) |
| 5 | `bmad-review` / `bmad-code-review` | Adversarial check on non-trivial diffs |

PRD, architecture, epics, readiness, forge, recon, and party mode are **situational**, not daily defaults. Sprint planning is excluded by default for this single-user/single-developer project.

### Common misuses (avoid)

| Misuse | Do instead |
| --- | --- |
| Running the full planning stack on a one-line fix | **Direct** lane; just code + tests |
| Opening BMAD because the skill list exists | Match **lane** to work size; user did not ask ⇒ no ceremony |
| Writing a new PRD/architecture when one already covers the slice | **Read and extend**; or `bmad-correct-course` if the plan is wrong |
| Planning and implementing in one giant context window | **Fresh session** per heavy skill; implement from artifacts, not from a 200-turn chat |
| Coding before readiness on a max-scope feature | Finish planning gates first (or get an explicit user waiver) |
| Treating BMAD templates as product law | **This file + `project-context.md` outrank** generic BMAD boilerplate (valuation, Quant Lens, fixed-point, etc.) |
| Dumping the entire skill catalog on the user | One next step via `bmad-help` or the lane table |
| Party mode / multi-persona by default | Only for contested product/architecture decisions |
| Hand-editing `.grok/skills` / `.agents/skills` copies | Reinstall/update BMAD; customize via `_bmad/custom` or project rules |
| Measuring success by “we ran many BMAD skills” | Success = correct code, tests, and **durable artifacts** worth re-reading |
| Leaving new domain invariants only in a PRD paragraph | Promote into `project-context.md` / contracts / this file when they are standing rules |

### Do / don’t

- **Do** read existing `_bmad-output/` artifacts and `project-context.md` before inventing product rules.
- **Do** use BMAD when the user explicitly asks (PRD, architecture, sprint status, party mode, “run bmad …”).
- **Do** keep BMAD outputs under `_bmad-output/` (planning vs implementation paths already configured).
- **Do** keep BMAD commits/tooling noise separate from unrelated product changes when practical.
- **Do** use BMAD as durable engineering memory: product decisions, architecture invariants, contracts, executable specs, and verification evidence.
- **Don’t** invent a PRD/epic/sprint for a one-line fix or pure mechanical change.
- **Don’t** introduce sprints, velocity, story points, backlog grooming, or simulated PM/architect/QA handoffs unless Juan explicitly requests that process.
- **Don’t** open party mode or multi-agent theater by default — only for contested product/architecture decisions.
- **Don’t** re-run the full Phase 1–3 stack if a recent PRD/architecture already covers the slice; extend or correct-course instead of rewriting.
- **Don’t** treat skill-manifest package paths as the runtime tree; installed skills live in IDE skill dirs; config/scripts live in `_bmad/`.
- **Don’t** require the human to “know BMAD”; agents choose the lane and name the skill only when needed.

### Orientation

- Unsure which skill fits: **`bmad-help`** (or user says “bmad help / what’s next”).
- Prefer **fresh context** for heavy BMAD workflows; keep implementation sessions focused on code + tests.
- Product invariants (valuation model family, Quant Lens) in this file and `project-context.md` **outrank** generic BMAD templates when they conflict.
- Grok quick-start (short): [`.grok/rules/bmad.md`](.grok/rules/bmad.md) — this section remains authoritative.

## Architecture (desktop terminal)

### Event Loop

The main loop is an `mpsc::Receiver<AppEvent>` that processes a single event then calls `render()`.

| Variant | Source thread | Purpose |
| --- | --- | --- |
| `Input(KeyEvent)` | crossterm reader | User keypresses |
| `Resize` | crossterm reader | Terminal resize |
| `FeedBatch(Vec<FeedEvent>)` | feed loop | Yahoo quote/fundamentals/coverage |
| `ChartData(ChartDataEvent)` | chart loop | Historical OHLC |
| `AnalysisData(AnalysisDataEvent)` | analysis worker | Valuation / DCF results |
| `HistoryLoaded { .. }` | persistence | SQLite warm-start restore |

Main thread owns mutable state — no shared locks around `AppState` / `TerminalState`.

### Data Flow (desktop)

```text
Yahoo Finance HTML
  → MarketDataClient (market_data.rs)
  → FeedBatch → TerminalState
  → render() → ScreenRenderer (dirty rows only)
```

Valuation is a **second path**: load fundamentals + cash-flow / book drivers → **model-family engine** → cache / UI. Windows also runs residual income on fundamentals ingest and demand-driven from Quant Lens for financials.

## Valuation Model Family (critical)

Canonical design: [`_bmad-output/planning-artifacts/valuation-model-family-architecture.md`](_bmad-output/planning-artifacts/valuation-model-family-architecture.md).  
Contracts: [`shared/contracts/valuation-model-family.json`](shared/contracts/valuation-model-family.json).

### Route by business class — never one formula for all tickers

| BusinessClass | Primary model | Discount rate | Cash / driver |
| --- | --- | --- | --- |
| `OperatingNonFinancial` | FCFF + WACC | WACC | Free cash flow series (source-resolved) |
| `FinancialServices` (banks, insurance, brokers, managed care / healthcare plans, …) | **Residual income / excess return on equity** | **Cost of equity only** | Book equity + ROE path (fade to competitive long-run) |
| `NotEligible` (ETF, fund, crypto shell, REIT, …) | none | — | — |
| `Unclassified` (missing or uncatalogued sector/industry) | **none — refuse** | — | — |

- **Closed world:** unknown or empty sector/industry → `Unclassified` → **valuation unavailable with a reason**. **Never** silent-default to FCFF (CI / Healthcare Plans failure mode).
- **Do not** run OCF − PPE CapEx as “FCF” for insurers/banks/managed care and treat it as owner earnings (ACGL/CI failure mode: float OCF → absurd FCFF).
- **Do not** silent-fallback financials to FCFF when book/ROE is missing — return unavailable with a reason code.
- Classifier inputs: sector/industry keys and names (versioned policy tables), not price multiples. Expand tables only with tests; unmapped text must keep failing closed.
- UI must surface refusal reasons (`valuation_unavailable_reason` / Detail DCF slot) — empty “—” without explanation is a product bug when the backend knows why.

### Dynamic parameters — no eternal magic constants as truth

Prefer **live or versioned market/policy inputs** over frozen literals:

| Input | Expected source of truth |
| --- | --- |
| Risk-free rate \(r_f\) | Market series / `MarketParams` (not a permanent `400` bps constant as sole truth) |
| Equity risk premium | Versioned policy / refreshable table (not eternal `500` bps alone) |
| Beta | Company beta **shrunk** toward industry/sector beta (shrink is intentional estimation, not “provisional noise”) |
| Near-term growth | Recent window of drivers (e.g. last ~3–5 years), not full-history CAGR from 2008 |
| Stable growth \(g_{stable}\) | \(\min(\text{macro ceiling},\ r_f - \text{buffer},\ r - \varepsilon)\) — moves with regime |
| Cost of equity / WACC | Derived from the above; **do not** use `MIN_WACC` / `MAX_WACC` as valuation truth |

Policy defaults may exist as **bootstrapping** when live series are missing; they must be marked **provisional** in provenance and must not silently masquerade as high-confidence inputs.

### Structural constraints only — forbidden patches

**Allowed (identities / economics)**

- \(g_{stable} < r\) (Gordon identity)
- Financial services ↛ FCFF as primary
- Terminal ROE fade toward competitive long-run (e.g. toward \(r_e\)) for residual income
- Missing required drivers → `Unavailable` / `NotEligible` with reason codes
- Beta missing → industry shrink

**Forbidden**

- Hard caps on `intrinsic / price` (e.g. reject if DCF > 3× market)
- Floor WACC “because ACGL looked wrong”
- Sector FCF haircut constants (`FCF × 0.3` for insurance)
- Disabling valuation for financials without a replacement model
- Acceptance tests that only assert “value near price”

Regression mindset: **ACGL must not emit FCFF-primary from float OCF**; use residual income (or unavailable if book/ROE missing).

### Provenance and engine versioning

Every intrinsic should carry enough metadata for UI and scoring:

- `business_class`, `model` (`fcff_wacc` | `residual_income_equity` | …)
- `discount_rate_kind` (`wacc` | `cost_of_equity`)
- `engine_version` / `model_policy_version`
- WACC / CoE input provenance (reported vs default vs derived)
- Reason codes (e.g. `model=residual_income_equity`, `growth=recent_window_fade_to_stable`)

Cache keys and revisions must invalidate when engine/policy/source fingerprints change.

### Cross-platform parity

- Windows: `apps/windows/src-tauri/src/dcf_model.rs`
- Android: `apps/android/core/.../DcfAnalysisEngine.kt`
- Desktop: `apps/desktop/src/workstation/app_core.rs` (FCFF fade + residual income routing)
- Shared goldens under `shared/contracts/valuation-model-family.json` — dual implementations must not drift.

Provider **source selection** (Yahoo vs SEC) is a separate layer: [`_bmad-output/implementation-artifacts/dcf-source-consistency-architecture.md`](_bmad-output/implementation-artifacts/dcf-source-consistency-architecture.md).

### SEC FCFF driver normalization

For domestic US-GAAP `10-K`/`10-K/A` operating issuers with a CIK, SEC facts cross a canonical normalization boundary before FCFF. Recurring development CapEx is consumed separately from the reviewed US-GAAP taxonomy set of property/business acquisition cash; acquisition facts stay visible as rejected evidence and are never added to FCFF. Material acquisition cash in fiscal year Y contaminates only the revenue-growth transition from Y−1 to Y. Exclude that transition and retain clean recent observations when at least two exist and the latest is clean; otherwise use zero near-term growth and record `acquisition_normalized` provenance. Unknown issuer extensions are audit candidates, not inferred mappings. Missing approved, consolidated USD annual evidence is unavailable—not zero or an imputed cash flow. Financial services remain on residual income.

## Quant Lens (signal vs noise)

Windows: `apps/windows/src-tauri/src/quant_lens.rs` + `QuantLensPanel.tsx` (model_version ≥ 4).

### Evidence = independent families

Count **families**, not every positive flag:

1. Complete analyst range (low / base / high)
2. Usable valuation model (solid or soft quality)
3. Price history depth
4. Optional **agreement** bonus when model base ≈ analyst base

**Do not** count analyst `gap_bps` as a second family on top of analyst range (double-counting).

**Strong** only when enough independent families agree, conflicts are zero, and model quality is **solid**. Soft/provisional models must not crown **Strong**.

### Expected value = model-aware, disagreement-honest

| Situation | EV behavior |
| --- | --- |
| Model solid and aligned with analyst | Prefer model scenarios as primary |
| Model soft but aligned | Prefer **analyst** as primary fair value |
| Model and analyst diverge materially | Status **`Disputed`** — show both anchors; **no** single absurd weighted upside |
| Only one complete source | That source |
| Neither | Unavailable |

- Label sources honestly: **FCFF DCF**, **Residual income**, **Analyst range**, **disputed**.
- Model quality: ordered bear≤base≤bull; provisional inputs / very wide scenarios → **soft**.
- Disagreement thresholds are **relative between model and analyst anchors**, not hard caps vs market price.

### Demand-driven valuation for financials

Opening Quant Lens may compute residual income from fundamentals when analysis is missing (financials skip FCFF/EDGAR FCF by design). Do not reintroduce FCFF-on-float for that path.

## Build And Test

> ### NON-NEGOTIABLE — Windows live / manual / agent QA uses profile `qa`
>
> **QA se hace con profile `qa`. Always.**
>
> | MUST | MUST NOT |
> | --- | --- |
> | Launch with **`npm run tauri:dev:qa`** (or equivalent `DS_UNIVERSE_PROFILE=qa` / `--universe qa`) | Launch bare `npm run tauri:dev` / full `sp500` for QA |
> | Confirm feed is **profile `qa`**, **≤20 symbols**, preferably **locked** | Cold-start ~500+ tickers “to be thorough” |
> | Reuse one long-lived `qa` process | Open/close the app repeatedly |
> | One-shot load missing checklist tickers only | Switch universe to `sp500` / `russell` / etc. during QA |
>
> **Exception only if the user explicitly orders another universe** (e.g. “QA against full SP500”). Silence is not permission — default is **`qa`**.
>
> Details: section **Windows live QA = profile `qa` only** below · [`docs/valuation-live-qa-checklist.md`](docs/valuation-live-qa-checklist.md).

- Strict TDD for behavior changes: failing test → smallest green → refactor while green.
- Desktop: `cargo test` from `apps/desktop`; `cargo run --manifest-path apps/desktop/Cargo.toml -- --smoke`.
- Windows: `cargo test` in `apps/windows/src-tauri` (include `dcf_model`, `quant_lens` when touching valuation/lens).
- Android: `scripts/validate-android.ps1` (always `:core:test`; app tasks when SDK configured).
- **Android live / agent QA uses profile `qa` only (≤20 symbols).** Debug installs (`make android-run` / `installDebug`) cold-start on `qa`. Never QA on full `sp500` (~500 tickers). See **Android live QA = profile `qa` only** below.
- Valuation / Quant Lens: prefer goldens in `shared/contracts` and fixture regressions (e.g. ACGL residual income, TSLA disputed EV) over inventing market-proximity asserts.
- **Valuation merge bar (mandatory):** any change to classifier, FCFF/WACC, CapEx→FCF, residual income, or model policy version **must** pass:
  - `cargo test --lib valuation_baseline::` (from `apps/windows/src-tauri`)
  - `cargo test --lib dcf_model::`
  Single-ticker green is **not** enough. See `_bmad-output/implementation-artifacts/valuation-multi-name-baseline-policy.md`.
- External providers: ≥5 distinct real upstream samples; never invent Yahoo/SEC payloads when live behavior matters.
- `cargo fmt` before finishing Rust changes.
- Mutation testing around changed logic when practical; state the gap if not.

### Android live QA = profile `qa` only (mandatory)

**QA on the Android app is always done with universe profile `qa` (≤20 symbols).**

| | |
| --- | --- |
| **Profile** | **`qa`** — pin in `apps/android/app/src/main/assets/profiles/qa.txt` (hard cap 20) |
| **When** | Any live UI QA, agent “check the app,” post-change smoke on Android |
| **Launch** | `make android-run` — debug builds bootstrap **`qa`** and clear app data on install |
| **Unless** | User **explicitly** orders another profile (`sp500`, `dow`, …). Silence → **`qa`** |
| **Forbidden** | Cold-start full `sp500` / 500+ Yahoo thrash “to be thorough” |

Checklist names (T, AMZN, CI, JPM/ACGL, AAPL, …) live in the qa pin; one-shot load extras only if needed — never switch to full SP500 for agent QA.

### Windows live QA = profile `qa` only (mandatory)

**QA on the Windows app is always done with universe profile `qa`.**

That is the standing rule for agents and humans. It is not optional, not “preferred if convenient,” and not something you skip for speed. Full-market cold starts are an operational failure mode.

| | |
| --- | --- |
| **Profile name** | **`qa`** (alias `test` → `qa`) |
| **When** | Any live UI QA, manual regression, valuation live path, agent “check the app,” post-change smoke on Windows |
| **Unless** | User **explicitly** says to use another universe (`sp500`, `dow`, …). If they did not say so → **`qa`** |
| **Command** | From `apps/windows`: **`npm run tauri:dev:qa`** |

| Rule | Detail |
| --- | --- |
| Launch (required for QA) | `npm run tauri:dev:qa` — sets `DS_UNIVERSE_PROFILE=qa` and locks membership |
| Launch (env equivalent) | `$env:DS_UNIVERSE_PROFILE = "qa"` then `npm run tauri:dev` |
| Launch (binary) | `discount-screener-windows.exe --universe qa` (or `--profile qa` on the **exe** only) |
| Membership | **≤20 persistent feed symbols**: SP500 ∩ latest snapshot gap≥25% ∩ score DESC ∩ top 20; thin DB → priority fill; **never** full SP500 |
| Hard cap | `persistent_feed_workers ≤ 20` (fail closed). Checklist names: **one-shot** `ensure_symbol_loaded` only — must not grow the feed |
| Process | **One** long-lived `qa` process; reuse it. Restart only after native rebuild, then one `qa` start again |
| Lock | Launch lock blocks UI/`localStorage` from switching to `sp500` — leave it locked for the QA session |
| Invalid profile | Explicit bad flag / env **fails closed** — never silent full universe |
| Coverage honesty | `qa` is a **top-ranking sample**, not the whole product surface. Checklist (T/AMZN/CI/…) uses names already in the 20 or one-shot loads |

**Forbidden launch forms** (Cargo steals `--profile` as a *compile* profile):

```text
tauri dev -- -- --profile qa
cargo tauri dev -- -- --profile qa
```

**Correct:**

```text
# from apps/windows — THE command for live / agent QA
npm run tauri:dev:qa
```

## Preventing repeat operational errors (critical)

Automated tests and baselines **reduce** risk; they do **not** eliminate operational mistakes. Agents and humans still ship one-ticker “wins” that break other names, skip live QA, or treat quarantine as success. **Avoid repeating those errors** by treating the procedures below as mandatory, not optional ceremony.

### Principle

| Reality | Rule |
| --- | --- |
| Shared pure math is multi-tenant | A fix for one symbol can change every ticker — never declare valuation done on a single-name green |
| Fail-open defaults invent numbers | Prefer **refuse + reason** over a default model (FCFF) when class/drivers are unknown |
| Green CI with wrong asserts is theater | Assert user-visible failure modes (wrong class, penny mega-cap, inverted scenarios), not only constants |
| Quarantine is a ticket, not a trophy | Do not claim “N names green” while slots are quarantined unless acceptance **explicitly** allows reduced N |
| Stale UI hides backend truth | After policy bumps, verify Detail does not keep a previous absurd DCF |

### Manual procedures (must follow — write them here so they stay visible)

When **any** of these change: classifier, CapEx→FCF, WACC/CoE, residual income, model policy version, or demand-valuation paths:

1. **Automated gate (always)**  
   From `apps/windows/src-tauri`:
   - `cargo test --lib dcf_model::`
   - `cargo test --lib valuation_baseline::`
   - `cargo test --lib quant_lens::` if Quant Lens / EV agreement is in scope  

2. **Live valuation QA (always after model changes that affect UI numbers)**  
   Full checklist: [`docs/valuation-live-qa-checklist.md`](docs/valuation-live-qa-checklist.md).  
   **Profile MUST be `qa`** — start with `npm run tauri:dev:qa` if nothing is running; do **not** QA on full `sp500`.  
   Minimum path on a **running** `qa`-profile Windows app (reuse process; do not start a second instance):

   | # | Symbol / case | Must see |
   | --- | --- | --- |
   | 1 | **T** | FCFF path; not FCF≈OCF; soft rates not sold as solid truth |
   | 2 | **AMZN** | bear ≤ base ≤ bull; not ~$1 / inverted scenarios |
   | 3 | **CI** (or UNH/ELV) | **Residual income**, not FCFF float mirage |
   | 4 | **JPM** or **ACGL** | Residual income / financial; not FCFF-primary |
   | 5 | **AAPL** or industrial operating | FCFF; order-of-magnitude sanity vs market |
   | 6 | Unclassified / missing sector (if reproducible) | Slot **unavailable** with refuse copy — no invented DCF |

   Prefer checklist names already in the QA 20. If missing, **one-shot** load that symbol only — never switch universe to `sp500`.

3. **After demand-valuation / policy version bumps**  
   - Confirm Detail clears stale DCF when class is financials-reclassified or unclassified.  
   - Confirm `valuation_unavailable_reason` (or equivalent) is visible when model is refused.

4. **Desktop**  
   May lag Windows WACC uplift / FCF normalize, but must **fail-closed** on unclassified (no silent FCFF). If desktop numbers disagree with Windows on the same class, call it out in the change notes — do not assume parity.

5. **When adding a sector/industry to the classifier**  
   - Add the token(s) + unit tests for the class.  
   - Add or extend a fixture that would have failed under the old wrong model (e.g. managed care → not FCFF).  
   - Run the merge bar above.  
   - If the name is High-SNR for operators, consider one live Detail check.

### Anti-patterns that already bit us (do not repeat)

| Anti-pattern | What happened | Do instead |
| --- | --- | --- |
| “T looks good vs Street → ship” | Soft WACC / FCF run-rate changes broke other names (e.g. AMZN-class) | Multi-name baseline green **before** claiming done |
| Default “not financial ⇒ FCFF” | CI Healthcare Plans → absurd DCF; UI did not refuse | Closed-world `Unclassified` + reason in UI |
| Weak absurd checks / quarantine-as-green | Suite green while MU-class OOM or many quarantines | Order-of-magnitude + business-class asserts; 20-slot fixture = **0** quarantine |
| Backend refuse, UI mute dash | User cannot tell model refused vs still loading | Surface `valuation_unavailable_reason` / i18n refuse copy |
| Backend DCF green, Detail still unavailable | COF returned valid residual income while the UI discarded it because operating `valuation_status` was null | Probe the active Tauri invoke over local CDP and assert the rendered Detail; typed residual income is independently publishable |
| Only automated tests | Live still shows stale cache or wrong label | Live checklist after model changes |
| Cold-start full SP500 for every agent QA | Thousands of Yahoo requests; rate limits; wasted time | **QA = profile `qa` only** → Windows `npm run tauri:dev:qa`; Android `make android-run` (debug → `qa`); reuse one process; one-shot checklist loads |
| Android QA on default `sp500` | 500+ tickers; same thrash as Windows full universe | Debug boots **`qa`**; `pm clear` on `android-run`; never switch UI to sp500 for agent QA |
| `tauri dev -- -- --profile qa` | Cargo steals `--profile` → compile error / full universe fallback | `npm run tauri:dev:qa` or `DS_UNIVERSE_PROFILE=qa`; binary `--universe qa` |
| “I’ll just open the normal app for QA” | Full universe + thrash restarts | Wrong. **QA is profile `qa`.** No silent default to `sp500` |
| One acquisition zeroes the whole growth window | Historical M&A made BSX/ADSK/AVGO-class estimates ignore later clean growth | Contaminate only Y−1→Y; require two clean recent transitions and a clean latest transition, otherwise zero growth explicitly |
| Analyst gap pill sits beside DCF with no source | A positive Street upside looked like it described a below-market DCF | Label the analyst relation and show DCF-vs-market independently |

### Where longer checklists live

- Multi-name baseline policy: [`_bmad-output/implementation-artifacts/valuation-multi-name-baseline-policy.md`](_bmad-output/implementation-artifacts/valuation-multi-name-baseline-policy.md)
- Live QA detail (**profile `qa` only**): [`docs/valuation-live-qa-checklist.md`](docs/valuation-live-qa-checklist.md)
- Calibration session lessons: [`_bmad-output/implementation-artifacts/retro-valuation-calibration-session-2026-07-30.md`](_bmad-output/implementation-artifacts/retro-valuation-calibration-session-2026-07-30.md)

**If a new operational failure mode appears, add a row to the anti-pattern table and a step to the manual procedures in this file** — do not leave it only in chat.

## Conventions (general)

- Fixed-point money: `*_cents`, `*_bps`, `*_hundredths`, `*_millis` stay integers unless strongly justified.
- Type-driven design: encode invariants in types; validate at boundaries; keep invalid states unrepresentable.
- Decouple market-data, persistence, and UI/rendering.
- Temp work only under `.agents/workspace/tmp`.
- User-visible behavior changes: update or link docs (this file, project-context, contracts, operator docs) — do not bury long operational guidance only in comments.
- Demand-driven expensive work: history, valuation, and heavy fetches stay bounded and on-demand where practical.
- Sparse/unavailable/stale states must be explicit — never smooth missing valuation into a fake “Strong” story.

## Documentation Map

- [`README.md`](README.md) — product overview and commands
- [`docs/index.md`](docs/index.md) — documentation hub
- [`_bmad-output/project-context.md`](_bmad-output/project-context.md) — lean AI implementation rules
- [`_bmad-output/planning-artifacts/valuation-model-family-architecture.md`](_bmad-output/planning-artifacts/valuation-model-family-architecture.md) — valuation ADRs
- [`shared/contracts/README.md`](shared/contracts/README.md) — contract fixtures
- [`apps/android/README.md`](apps/android/README.md) — Android module map
- Desktop operator docs under `apps/desktop/docs/`
- Windows regression notes under `docs/windows-dashboard-2.0-manual-regression.md`
- Live valuation QA (**always profile `qa`**): [`docs/valuation-live-qa-checklist.md`](docs/valuation-live-qa-checklist.md)
- Multi-name valuation baseline: [`_bmad-output/implementation-artifacts/valuation-multi-name-baseline-policy.md`](_bmad-output/implementation-artifacts/valuation-multi-name-baseline-policy.md)
- BMAD process guidance: section **BMAD Method** above; Grok quick-start rule [`.grok/rules/bmad.md`](.grok/rules/bmad.md)
- **Preventing repeat errors:** section **Preventing repeat operational errors** above (manual procedures stay in this file)
