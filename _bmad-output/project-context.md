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
status: 'complete'
rule_count: 47
optimized_for_llm: true
---

# Project Context for AI Agents

_Critical rules and patterns AI agents must follow when implementing code in this project. Focus on details agents are likely to miss._

---

## Technology Stack & Versions

- **Monorepo:** `apps/desktop` Rust terminal workstation, `apps/android` native Android app, `shared/contracts` cross-platform fixtures/contracts.
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
- Android live QA is required when behavior reaches the installed app surface. Run `make android-run`, verify the app launches, inspect UI/logs when relevant, and report blockers.
- For external Yahoo/provider behavior, gather at least 5 distinct real upstream samples. Do not invent provider payloads from assumptions.
- Add persistence tests before changing SQLite schema, warm-start restore, pruning, dedupe, or migration semantics.
- Add startup/performance regression tests when changing warm restore, chart history, DCF analysis startup, or profile hydration paths.
- Run mutation testing around changed behavior when practical. Prefer `cargo-mutants` for Rust; otherwise perform manual mutation checks and state the gap.

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
- For live Android QA, prefer `make android-run` from repo root. It builds, installs, launches, and uses `.agents/workspace/tmp/android-run` for run artifacts.
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
- **Prefer demand-driven expensive work.** History loading, DCF analysis, Yahoo fetches, and Android startup flows must be bounded, cancellable where practical, and observable enough to debug.
- **Live QA findings override build confidence.** Passing unit tests and Gradle builds is insufficient when the installed app hangs, fails to launch, or renders the wrong surface.

---

## Usage Guidelines

**For AI Agents:**

- Read this file before implementing code in this repository.
- Follow all rules exactly as documented.
- When rules conflict, prefer the stricter boundary or verification requirement.
- Update this file when new durable project patterns emerge.

**For Humans:**

- Keep this file lean and focused on agent needs.
- Update it when technology versions, architecture boundaries, or verification gates change.
- Remove rules that become obsolete or too obvious to preserve LLM context efficiency.

Last Updated: 2026-04-25
