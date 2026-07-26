# Project Guidelines

Agent-facing rules for Discount Screener. Prefer this file plus [`_bmad-output/project-context.md`](_bmad-output/project-context.md) before inventing valuation, ranking, or UI semantics.

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
| `FinancialServices` (banks, insurance, brokers, …) | **Residual income / excess return on equity** | **Cost of equity only** | Book equity + ROE path (fade to competitive long-run) |
| `NotEligible` (ETF, fund, crypto shell, …) | none | — | — |

- **Do not** run OCF − PPE CapEx as “FCF” for insurers/banks and treat it as owner earnings (ACGL failure mode: float OCF → absurd FCFF).
- **Do not** silent-fallback financials to FCFF when book/ROE is missing — return unavailable with a reason code.
- Classifier inputs: sector/industry keys and names (versioned policy), not price multiples.

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

- Strict TDD for behavior changes: failing test → smallest green → refactor while green.
- Desktop: `cargo test` from `apps/desktop`; `cargo run --manifest-path apps/desktop/Cargo.toml -- --smoke`.
- Windows: `cargo test` in `apps/windows/src-tauri` (include `dcf_model`, `quant_lens` when touching valuation/lens).
- Android: `scripts/validate-android.ps1` (always `:core:test`; app tasks when SDK configured).
- Valuation / Quant Lens: prefer goldens in `shared/contracts` and fixture regressions (e.g. ACGL residual income, TSLA disputed EV) over inventing market-proximity asserts.
- External providers: ≥5 distinct real upstream samples; never invent Yahoo/SEC payloads when live behavior matters.
- `cargo fmt` before finishing Rust changes.
- Mutation testing around changed logic when practical; state the gap if not.

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
