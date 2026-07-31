---
project_name: 'discount_screener'
user_name: 'Juan'
date: '2026-04-25'
sections_completed:
  - technology_stack
  - language_rules
  - framework_rules
  - testing_rules
  - quality_rules
  - workflow_rules
  - anti_patterns
  - valuation_quant_lens_rules
status: 'complete'
date_updated: '2026-07-26'
rule_count: 54
optimized_for_llm: true
---

# Project Context for AI Agents

_Critical rules and patterns AI agents must follow when implementing code in this project. Focus on details agents are likely to miss._

---

## Technology Stack & Versions

- **Monorepo:** `apps/desktop` Rust terminal workstation, `apps/windows` Tauri/React workstation, `apps/android` native Android app, `shared/contracts` cross-platform fixtures/contracts.
- **Desktop Rust:** Rust edition 2024, `crossterm 0.29.0`, `ratatui 0.30.0`, `reqwest 0.12.12` with `rustls-tls`, `rusqlite 0.32.1` with bundled SQLite, `serde 1.0.217`, `serde_json 1.0.138`.
- **Android build:** Android Gradle Plugin `8.7.3`, Kotlin `2.0.21`, Java/JVM target 17, compile/target SDK 35, min SDK 26.
- **Android runtime libraries:** Compose BOM `2024.10.01`, Activity Compose `1.9.3`, Lifecycle `2.8.7`, Coroutines `1.9.0`, Kotlin Serialization JSON `1.7.3`, OkHttp `4.12.0`.
- **Android tests:** JUnit4 `4.13.2`, JUnit Jupiter `5.11.3` in `core`, AndroidX Test Core `1.6.1`, Compose UI Test, Coroutines Test `1.9.0`, Robolectric `4.14.1`.
- **Persistence:** SQLite on desktop and Android. Desktop uses `apps/desktop/src/persistence.rs`; Android uses `SQLiteOpenHelper` in `apps/android/app/src/main/kotlin/com/discountscreener/android/data/persistence/SQLiteStateStore.kt`.
- **External data:** Yahoo Finance public endpoints/HTML parsing. Treat provider shape as live external behavior, not stable documentation.

## Critical Implementation Rules

### Language-Specific Rules

- Preserve fixed-point financial values. Fields ending in `*_cents`, `*_bps`, `*_hundredths`, and `*_millis` stay integer-based unless a strong, documented reason exists.
- Prefer type-driven invariants. Model validated symbols, bounded values, non-empty collections, state-specific variants, and mutually exclusive states in types instead of repeating runtime checks.
- In Rust, keep reusable business logic and shared data types in `apps/desktop/src/lib.rs` or the owning module. Keep `apps/desktop/src/main.rs` focused on terminal UI flow and orchestration.
- In Kotlin, keep business rules in `apps/android/core`. Android `app` code may orchestrate, persist, fetch, and present, but should not own portable domain semantics.
- Use structured parsers and serializers already present in the stack. Avoid ad hoc string parsing for JSON, SQLite payloads, or provider data when typed models are available.
- Keep comments sparse and useful. Add comments only for non-obvious behavior, invariants, or operational traps.

### Framework-Specific Rules

- Android follows strict functional-core / imperative-shell boundaries: `core/` is pure Kotlin engine/model logic; `app/` contains Android `app`, `domain`, `data`, `presentation`, and `ui` packages.
- Compose screens are passive Views. They render state and emit actions only; no network calls, SQLite calls, persistence decisions, or business-rule interpretation in Compose.
- `DashboardViewModel` and other presentation classes act as Presenters. They map repository snapshots to UI state and route actions; they do not invent domain rules.
- Android `domain/` owns repository contracts and use cases. `data/` owns Yahoo client, profile loading, SQLite persistence, and repository implementation.
- `DefaultDashboardRepository` is the Android orchestration boundary for local state, Yahoo refresh, persistence, and `DashboardSnapshot` production. Keep it thin by moving pure interpretation to `core`.
- Desktop external boundaries are fixed: Yahoo fetching in `apps/desktop/src/market_data.rs`, SQLite restore/persistence in `apps/desktop/src/persistence.rs`, startup profiles in `apps/desktop/src/profiles.rs`.
- Desktop event loop state is owned by the main thread. Worker threads communicate through channels and publish events; do not add shared mutable locks around `AppState` or `TerminalState` unless architecture changes deliberately.
- Desktop rendering builds full `RenderLine` frames, then `ScreenRenderer` writes dirty rows only. Do not mix Yahoo/network/storage behavior into rendering code.

### Testing Rules

- Use strict TDD for behavior changes: write the failing test first, implement the smallest change to reach green, then refactor while tests remain green.
- Rust verification: run `cargo fmt` after Rust edits, then `cargo test` from `apps/desktop`. Use targeted `cargo test --bin discount_screener -- <name>` only for fast iteration.
- Desktop smoke verification: run `cargo run --manifest-path apps/desktop/Cargo.toml -- --smoke` for non-interactive binary validation.
- Android verification: use `scripts/validate-android.ps1` from repo root. It always runs `:core:test` and, when SDK is configured, `:app:testDebugUnitTest` and `:app:assembleDebug`.
- Android live QA is required when behavior reaches the installed app surface. Run `make android-run` (debug boots profile **`qa`**, ≤20 symbols — never full `sp500` for agent QA), verify the app launches, confirm the profile chip is QA, inspect UI/logs when relevant, and report blockers.
- **Windows live / agent / manual QA always uses universe profile `qa`.** Launch `npm run tauri:dev:qa` from `apps/windows`. Do not cold-start full `sp500` for QA unless the user explicitly orders another universe. See `Agents.md` and `docs/valuation-live-qa-checklist.md`.
- For external Yahoo/provider behavior, gather at least 5 distinct real upstream samples. Do not invent provider payloads from assumptions.
- Add persistence tests before changing SQLite schema, warm-start restore, pruning, dedupe, or migration semantics.
- Add startup/performance regression tests when changing warm restore, chart history, DCF/valuation analysis startup, or profile hydration paths.
- Run mutation testing around changed behavior when practical. Prefer `cargo-mutants` for Rust; otherwise perform manual mutation checks and state the gap.
- Valuation and Quant Lens changes must cover classifier routing, residual-income financials, FCFF operating path, and disagreement/Disputed EV cases — not “value near market” caps.

### Valuation & Quant Lens Rules

- **Route by business class (closed world).** `OperatingNonFinancial` → FCFF+WACC; `FinancialServices` → residual income / excess ROE on book with cost of equity; `NotEligible` → no intrinsic (ETF/fund/crypto/REIT); **`Unclassified` → valuation unavailable** (missing/unknown sector·industry — **never** silent-default to FCFF). Never run OCF−PPE CapEx FCFF as primary for banks/insurers **or managed care / healthcare plans** (ACGL/CI class of bug). Classifier must match `healthcare plans` / `managed care` without bare `health` (pharma stays FCFF). Policy tables must cover GICS-style operating sectors; unknown text fails closed.
- **Parameters are dynamic.** Risk-free rate, ERP, beta (industry shrink), near-term growth (recent window), and \(g_{stable}=\min(\text{macro}, r_f-\text{buffer}, r-\varepsilon)\) come from market/policy inputs. Frozen `rf`/`ERP`/`MIN_WACC`/growth max constants are not valuation truth; defaults must be provisional when used.
- **Driver-based FCFF (policy/6).** Windows and Android preserve reported FCF separately but project operating FCFF from aligned annual drivers: `OCF + after-tax interest − CapEx`, recent-window revenue growth, normalized OCF margin, normalized after-tax-interest margin, and normalized CapEx intensity. The base bridge is calculated from those components rather than taking a median of mismatched annual FCFF margins. Recent driver persistence/dispersion classifies secular expansion, stable operation, or cyclical/transition regimes; cyclical regimes blend recent and prior windows without ticker exceptions. CapEx spikes require both a material ratio jump and an absolute intensity jump; persistent investment regimes remain in the recent baseline. Negative FCFF years remain evidence instead of being silently discarded. Scenarios use recent driver dispersion; the base never combines a normalized cash-flow level with raw endpoint FCF CAGR. Weighted analyst mean and market price remain **external validation metrics only**—never runtime model inputs, caps, or substitutes. The debt-scaled provisional WACC uplift remains provisional at 175 bps initially. If required drivers are insufficient, the engine refuses with an explicit reason; it never invents precision. Desktop is explicitly deferred. `MODEL_POLICY_VERSION = business-class-policy/6-regime-driver-fcff`.
- **Multi-name baseline is the policy merge bar.** Valuation engine/policy changes must keep `valuation_baseline` tests green (pinned High + ≥20% discount cohort under `apps/windows/src-tauri/tests/fixtures/valuation/`). Single-ticker greens are not enough. The 20-slot fixture requires **zero quarantines** (replace unusable names). See `_bmad-output/implementation-artifacts/valuation-multi-name-baseline-policy.md` and `Agents.md` Build section.
- **Valuation refusal must be user-visible.** `SymbolDetail.valuation_unavailable_reason` + Detail DCF slot i18n for unclassified / not-eligible / missing FCF or book — never a silent invent or mute dash without reason when backend knows why.
- **Desktop** may lag Windows WACC uplift/FCF normalize, but must **fail-closed** on unclassified (no silent FCFF). Live QA: `docs/valuation-live-qa-checklist.md` (**profile `qa` only** on Windows).
- **Tests do not replace operational discipline.** Repeat failures (one-ticker green, silent FCFF default, quarantine-as-success, full-SP500 agent thrash) are documented as mandatory procedures in root `Agents.md` → **Preventing repeat operational errors** and **Windows live QA = profile `qa` only** — follow those after every valuation model change.
- **Structural constraints only.** Allowed: \(g < r\), model eligibility, clean-surplus identities, missing-driver unavailability, debt-scaled provisional WACC uplift. Forbidden: hard `intrinsic/price` caps, sector FCF haircuts, silent FCFF fallback for financials, acceptance tests that only require market proximity.
- **Provenance is mandatory** for model id, business class, discount-rate kind, engine/policy version, and WACC/CoE input sources. UI labels must distinguish FCFF DCF vs residual income vs analyst.
- **Quant Lens is high-SNR.** Count independent evidence families (analyst range, model quality, history, agreement) — do not double-count gap + analyst. Strong requires solid model quality and zero conflicts. When model and analyst diverge materially, EV status is **Disputed** (show both anchors; no single absurd weighted upside). Soft model + agreement → prefer analyst as primary fair value.
- **Valuation decision policy is core-owned.** Keep `Availability`, `Coverage`, freshness, confidence, and relation separate. For compatible positive anchors use integer half-up `differenceBps`; reduce all pairs to `Aligned` (≤2500), `Tension` (2501–5000), or `Disputed` (>5000). Never synthesize cross-provider analyst consensus. TipRanks is per-symbol, cache-first, explicit-load-only, and needs three distinct observations no older than 90 days before it is decision eligible.
- **TipRanks credentials and budget are durable boundaries.** Keep API keys in an Android Keystore AES-GCM envelope excluded from backup; never log/render them. Persist forecast cache separately from a conservative `reserved`/`sent` ledger and provider usage snapshots. No automatic retries; only explicit forecast calls consume the monthly forecast budget.
- **Contracts:** `shared/contracts/valuation-model-family.json` and source-selection contracts; keep Windows/Android/desktop engines aligned.
- Design authority: `_bmad-output/planning-artifacts/valuation-model-family-architecture.md` and root `AGENTS.md` / `Agents.md`.

### Code Quality & Style Rules

- Prefer extending the module that owns the concern over adding cross-cutting logic to entrypoints or UI files.
- Keep Android UI dense and operational. This app is a workstation-style financial tool, not a marketing surface.
- Keep UI text professional and data-focused. Do not add visible instructional prose that describes features, shortcuts, or implementation details.
- Avoid nested cards and decorative UI. Use cards only for repeated items, modals, and genuinely framed tools.
- Keep chart and toolbar dimensions stable. Dynamic labels, hover states, replay controls, and chips must not shift layout unpredictably.
- Use existing helpers and fixtures in tests before hand-building large snapshots inline.
- Keep temp operations inside `.agents/workspace/tmp`.
- Do not duplicate long operational guidance in code comments. Update or link existing docs when user-visible behavior changes.

### Development Workflow Rules

- Respect dirty worktrees. Never revert user changes or unrelated files. If existing changes affect the task, work with them and call out conflicts only when necessary.
- Use `apply_patch` for manual edits. Avoid shell write tricks for source changes.
- Use `rg`/`rg --files` first for search. Prefer parallel reads for independent file inspection.
- For Android SDK-dependent work, ensure `ANDROID_HOME`, `ANDROID_SDK_ROOT`, or `apps/android/local.properties` is configured before expecting app Gradle tasks to pass.
- For live Android QA, prefer `make android-run` from repo root. It builds, installs, clears app data (fresh **`qa`** membership), launches, and uses `.agents/workspace/tmp/android-run` for run artifacts. Do not QA on full SP500.
- For cross-platform behavior changes, update or add shared contract fixtures under `shared/contracts` and verify both platform interpretations where applicable.
- When a feature depends on local persistence plus app UI, validate three layers: pure rule/unit tests, SQLite/repository tests, and installed-app behavior.

### Critical Don't-Miss Rules

- **Startup must stay bounded.** Warm start restores only the current/bounded state needed for initial UI readiness. Do not load or backfill complete per-symbol pricing/history data during app startup.
- **Complete pricing/history data is per-ticker and on-demand.** Load full saved candle history when opening/selecting a ticker detail or history surface, not globally.
- **SQLite is persistence infrastructure, not a domain-rules dumping ground.** Put dedupe/merge semantics in pure core logic where possible; enforce uniqueness in SQLite as a boundary guarantee.
- **Historical revisions are facts.** Do not rebuild past revisions from current in-memory detail; persisted history must reconstruct the original saved payload truthfully.
- **Yahoo data is unstable.** Parser or provider-integration changes need live samples and failure behavior for missing/sparse fields.
- **No business rules in Compose.** If a rule affects valuation, ranking, provenance, dedupe, confidence, or trend interpretation, move it upstream.
- **No network/storage in rendering.** Rendering should consume state already prepared by the app or core layers.
- **Preserve decoupling between market data, persistence, and UI.** Mixing them caused regressions and makes live QA harder.
- **Do not hide sparse data states.** Show empty, stale, unavailable, no-baseline, and no-analyst-target states explicitly rather than smoothing or omitting them.
- **Prefer demand-driven expensive work.** History loading, valuation/DCF analysis, Yahoo fetches, and Android startup flows must be bounded, cancellable where practical, and observable enough to debug. Financial residual income may run on fundamentals ingest or Quant Lens demand; operating FCFF still needs cash-flow history.
- **Live QA findings override build confidence.** Passing unit tests and Gradle builds is insufficient when the installed app hangs, fails to launch, or renders the wrong surface.
- **Do not “fix” valuation noise with output clamps.** Fix model routing, driver definitions, parameter dynamics, and Quant Lens agreement policy instead.

---

## Usage Guidelines

**For AI Agents:**

- Read this file and root `Agents.md` / `AGENTS.md` before implementing code in this repository.
- Follow all rules exactly as documented.
- When rules conflict, prefer the stricter boundary or verification requirement.
- Update this file when new durable project patterns emerge (especially valuation and scoring).

**For Humans:**

- Keep this file lean and focused on agent needs.
- Update it when technology versions, architecture boundaries, or verification gates change.
- Remove rules that become obsolete or too obvious to preserve LLM context efficiency.

Last Updated: 2026-07-26
