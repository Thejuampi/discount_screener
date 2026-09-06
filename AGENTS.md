# Project Guidelines

Agent-facing rules for Discount Screener. Prefer this file plus [`_bmad-output/project-context.md`](_bmad-output/project-context.md) before inventing valuation, ranking, or UI semantics.

## Product Audience

Discount Screener is currently a personal workstation for **Juan**, a single self-directed analyst/investor. Treat multi-user growth metrics, onboarding funnels, and generic consumer personas as out of scope unless Juan explicitly asks for them. Preserve professional, evidence-first presentation, provenance, uncertainty, and the no-investment-advice boundary.

## Communication Style (mandatory)

Write each reply to Juan in **ASD-STE100 Simplified Technical English**. This applies to chat, commit bodies, PR text, and docs.

| Rule | Do | Do not |
| --- | --- | --- |
| Length | Keep sentences to 20 words or less | Write long chains of clauses |
| Voice | Use the active voice | Use the passive voice |
| Tense | Use the simple present, past, or future | Use the perfect tenses |
| Words | Use one word for one meaning | Use synonyms for variety |
| Verbs | Use `check`, `make sure`, `use`, `show` | Use `verify`, `ensure`, `utilize`, `leverage` |
| Forms | Use a full clause | Use `-ing` forms as nouns or adjectives |
| Articles | Write `the test`, `a commit` | Drop the articles to save space |
| Structure | Put complex data in a table or a vertical list | Put it in a long paragraph |

Technical names stay as they are: `AggressiveV3`, `robust_mean`, `:core`, commit, branch, holdout.

**Do not write walls of text.** Give the result first. Add the detail only if it changes what Juan does next.

**Do not add a TLDR at the end.** The reply is already short. A summary of a short reply is noise.

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

## BMAD Method

A requirement runs the closed cycle. No step is optional, and no step is skipped because the change looks small:

**PRD → Sensei+Advisor → spec → Sensei+Advisor → build → bmad-review**

| Step | Skill | Leaves behind |
| --- | --- | --- |
| PRD | `/bmad-prd` | The WHY and the acceptance bar. Init the spike memlog. Log the options. Lock one. |
| PRD review | `/sensei-advisor` | Sensei and Advisor on the PRD. Advisor wins. Append the verdict to the memlog. |
| Spec | `/bmad-spec` | The WHAT, locked. Read the PRD and the memlog first. |
| Spec review | `/sensei-advisor` | Sensei and Advisor on the spec. Advisor wins. Append the verdict to the memlog. |
| Build | `/bmad-build` | The code, TDD, plus the docs the change makes untrue. Read the memlog first. |
| Review | `/bmad-review` | Adversarial read of the diff against the spec. Append findings to the memlog. |

Do not start spec while the PRD review is `revise`. Do not start build while the spec review is `revise`.

Work is a **spike**. A spike explores more than one idea, logs each idea, and locks the best one for the task. The cycle builds that lock.

The memlog is the working memory of the spike. Artifacts are distilled from it. Use `uv run _bmad/scripts/memlog.py`. Home is `{spec-folder}/.memlog.md` once that folder exists. Append-only. A review that does not append to the memlog did not happen.

**Review does not close while docs are stale.** A user-visible change that leaves `docs/`, this file, `project-context.md`, or a contract describing the old behavior is not reviewed - it is half built. Write the docs in the build step so the review has something to check.

A bugfix, rename, or small tweak still ships direct with TDD. That exemption covers a fix, never a requirement.

Process, lanes, memlog, and skills: [`.grok/rules/bmad.md`](.grok/rules/bmad.md).

This file + `project-context.md` + contracts **outrank** generic BMAD templates. Unsure: `bmad-help` once.

## Architecture (desktop terminal)

Main thread owns mutable state. The loop is `mpsc::Receiver<AppEvent>` → one event → `render()`. No shared locks around `AppState` / `TerminalState`.

Yahoo HTML → `MarketDataClient` → `FeedBatch` → `TerminalState` → dirty-row render. Valuation is a **second path**: fundamentals + drivers → model-family engine → cache / UI. Windows also runs residual income on fundamentals ingest and demand-driven from Quant Lens for financials.

## Valuation Model Family (critical)

Canonical design: [`_bmad-output/planning-artifacts/valuation-model-family-architecture.md`](_bmad-output/planning-artifacts/valuation-model-family-architecture.md).  
Contracts: [`shared/contracts/valuation-model-family.json`](shared/contracts/valuation-model-family.json).

### Route by business class — never one formula for all tickers

| BusinessClass | Primary model | Discount rate | Cash / driver |
| --- | --- | --- | --- |
| `OperatingNonFinancial` | FCFF + WACC; **or** factory + lender (`component-sotp/2`) when the parent industry hosts a captive and the filing prints both | WACC on the factory; cost of equity on the lender | Factory cash = NOPAT + depreciation − sustaining CapEx; lender book from the finance subsidiary filing. Software, IT services, and ratings stay on FCFF. |
| `FinancialServices` (banks, insurance, brokers, managed care / healthcare plans, **lenders** such as COF) | **Residual income / excess return on equity** | **Cost of equity only** | Book equity + ROE path; long-run ROE = min(ROE0, CoE+500) |
| Payment networks (`V`, `MA`) even if Yahoo says Credit Services | **FCFF + WACC** | WACC | Fee cash flow — residual on book prints ~book |
| `NotEligible` (ETF, fund, crypto shell, REIT, …) | none | — | — |
| `Unclassified` (missing or uncatalogued sector/industry) | **none — refuse** | — | — |

- **Closed world:** unknown or empty sector/industry → `Unclassified` → **valuation unavailable with a reason**. **Never** silent-default to FCFF (CI / Healthcare Plans failure mode).
- **Do not** run OCF − PPE CapEx as “FCF” for insurers/banks/managed care and treat it as owner earnings (ACGL/CI failure mode: float OCF → absurd FCFF).
- **Do not** silent-fallback financials to FCFF when book/ROE is missing — return unavailable with a reason code.
- Classifier inputs: sector/industry keys and names (versioned policy tables), not price multiples. Expand tables only with tests; unmapped text must keep failing closed.
- UI must surface refusal reasons (`valuation_unavailable_reason` / Detail DCF slot) — empty “—” without explanation is a product bug when the backend knows why.

### Dynamic parameters — no eternal magic constants

Prefer **live or versioned market/policy inputs** over frozen literals: `r_f` from market series / `MarketParams`, ERP from versioned policy, company beta **shrunk** toward industry/sector beta, near-term growth from a recent window (~3–5 years, not full-history CAGR), `g_stable = min(macro ceiling, r_f − buffer, r − ε)`, CoE/WACC derived from those. Cost of debt uses market yield, then a rated or coverage-synthetic spread (EBIT / interest), then the accounting coupon. The cheap coupon on old debt does not set WACC. Android engine knobs live in [`shared/contracts/valuation-policy.yaml`](shared/contracts/valuation-policy.yaml). Android pre-earnings counts and AV budget live in [`shared/contracts/earnings-gate-policy.yaml`](shared/contracts/earnings-gate-policy.yaml) (`earnings-gate-policy/2`). Edit those files. Do not put a second copy in Kotlin. Cell identities live in the engine.

- **Do not** use `MIN_WACC` / `MAX_WACC` as valuation truth.
- Bootstrap defaults (e.g. `MarketParams()` 430/450) are bootstrapping only when live series miss; mark them **provisional** in provenance, never high-confidence.
- Android production is wired: FRED DGS10 → Yahoo `^TNX` (1-day cache) → bootstrap; `ErpPolicy` (Damodaran implied *index* ERP, Kroll overlay, **no firm ICC**), `g_stable` via dated `MacroPolicy`. Windows is still on `from_live_risk_free` and is later work. Per-input detail: the canonical doc above.

### Structural constraints only — forbidden patches

**Allowed (identities / economics)**

- \(g_{stable} < r\) (Gordon identity)
- Financial services ↛ FCFF as primary
- Terminal ROE holds min(through-cycle ROE0, cost of equity + 500 bps); raw ROE0 does not run forever
- Missing required drivers → `Unavailable` / `NotEligible` with reason codes
- Beta missing → industry shrink
- Latest OCF is the run-rate only when the prior window already printed two positive OCF years. A first-cash ramp keeps the recent OCF centre.
- Latest FCFF margin is the base run-rate when the recent-window median is non-positive and that latest aligned year is positive (`fcff_margin=latest_positive_aligned_annual`). Refuse `non_positive_normalized_fcff` only when the latest year is also non-positive.
- Yahoo latest reported FCF sign does not block driver FCFF. `LatestFcfNonPositive` stays only when aligned OCF/CapEx/revenue years are short.
- When reported cash covers reported debt, a failed coupon path is not-applicable. WACC is cost of equity. Material net debt with no yield, spread, or filed interest still refuses.
- WACC tax is filing statutory first. When that series is empty, attach the versioned domicile row (`DomicileTaxProxy`, provisional) from `valuation-policy.yaml` `tax.rows` using the issuer country. Missing country stays unavailable. Historical effective tax is not the shield.

**Forbidden**

- Hard caps on `intrinsic / price` (e.g. reject if DCF > 3× market)
- Floor WACC “because ACGL looked wrong”
- Sector FCF haircut constants (`FCF × 0.3` for insurance)
- Disabling valuation for financials without a replacement model
- Acceptance tests that only assert “value near price”

Regression mindset: **ACGL must not emit FCFF-primary from float OCF**; use residual income (or unavailable if book/ROE missing).

### Honesty modes

Two typed modes. Street is the scoreboard only.

| Mode | Role |
| --- | --- |
| `Honest` | Working identity. Every input is evidence or economics. Exhaust this first. |
| `NonHonest` | Parallel signal. One-knob inversions that would match Street. Never a hidden mix. |

Every non-honest input is `ValuationHonesty.NonHonest` in the class model. Detail prints both dollars in the Model block. The non-honest line names the bent input. The Snapshot forecast is the analyst range. Policy: `street-implied-honesty/3`. The scoreboard reports `ape_h`, `ape_nh`, implied bps, delta, and stretch. `ape_nh` near 0 is inversion, not a win.

### Provenance, parity, and engine versioning

Every intrinsic should carry metadata for UI and scoring: `business_class`, `model` (`fcff_wacc` | `residual_income_equity` | …), `discount_rate_kind`, `engine_version` / `model_policy_version`, WACC/CoE input provenance (reported vs default vs derived), and reason codes (e.g. `model=residual_income_equity`). Cache keys and revisions must invalidate when engine/policy/source fingerprints change.

Implementations must not drift from the shared goldens (Windows `dcf_model.rs`, Android `DcfAnalysisEngine.kt`, Desktop `app_core.rs` FCFF fade + residual income routing); goldens live under `shared/contracts/valuation-model-family.json`.

Provider **source selection** (Yahoo vs SEC) is a separate layer: [`_bmad-output/implementation-artifacts/dcf-source-consistency-architecture.md`](_bmad-output/implementation-artifacts/dcf-source-consistency-architecture.md).

### SEC FCFF driver normalization

For domestic US-GAAP `10-K`/`10-K/A` operating issuers with a CIK, SEC facts cross a canonical normalization boundary before FCFF:

- Recurring development CapEx is consumed separately from the reviewed US-GAAP acquisition-cash set; acquisition facts stay visible as rejected evidence and are **never** added to FCFF.
- Sum plant, capitalized software (`PaymentsForSoftware`, `PaymentsToDevelopSoftware`), purchased intangibles (`PaymentsToAcquireIntangibleAssets`), and the oil well program. Drop software and intangibles when the tangible tag is `PaymentsToAcquireProductiveAssets` (those components are already inside that aggregate).
- `PaymentsToAcquireOilAndGasPropertyAndEquipment` is the well program. Sum it with other plant. `PaymentsToAcquireOilAndGasProperty` (no equipment) stays acreage acquisition.
- Material acquisition cash in fiscal year Y contaminates only the revenue-growth transition Y−1 → Y. Exclude that transition and retain clean recent observations when ≥2 exist and the latest is clean; otherwise use zero near-term growth and record `acquisition_normalized` provenance.
- `FinanceLeaseInterestExpense` is not an interest-expense equivalent. It is the lease subset. Signed net interest (`InterestIncomeExpenseNonoperatingNet`) uses the magnitude. Yahoo years that lack a coupon take that filed SEC interest on Detail, the same way they take statutory tax. Skip cash-paid tags.
- Missing coupon years: estimate when `coupon-resolution/1` confidence allows. Own last effective rate first. Else the median of similar issuers' filed rates. Label method and band. A later filed tag replaces the estimate. No own points and fewer than three peers stay Absent. Never invent a coupon from Other income, `InterestPaid*`, or a note rate range.
- Android `debt-resolution/1` owns stock, coupon, and published k_d. Stock is the filed year-end instant. Estimated coupons enter year-cash only. k_d stays market yield, then rated/coverage synthetic, then the filed coupon over average debt. `InterestPaid*` is not a filed coupon. A current instrument yield attaches through `issuer-market-yield/2` onto `marketYieldBps`. The yield sets k_d. It does not shrink the tax-year window. Android reads Markets Insider issuer bond rows, keeps USD quotes, and takes the median of remaining 4–15 year yields. Missing yield leaves the rung empty.
- Unknown issuer extensions are audit candidates, not inferred mappings. Missing approved CapEx, OCF, or tax evidence is **unavailable** — not zero.
- Financial services remain on residual income.
- The Android sieve is the boundary of what SEC facts exist downstream. It keeps an annual (`fp=FY`), consolidated `10-K`/`10-K/A` fact of a policy concept, with the seven fields a reader reads. A reader that needs a quarter, another form, or a dropped field must widen `SecCompanyFactsSieve` **and** bump `COMPANY_FACTS_SIEVE_VERSION`, or the cache answers with the old shape.

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

### Iterate on the harness first (mandatory while developing)

Use `QuantHarness.hardcoded()` → `cached()` → `live()` (`DS_QUANT_LIVE=true` to refresh a pack). Do **not** run device `make android-run-qa` until Juan says the product is ready for live QA.

### Live QA = profile `qa` only (mandatory)

**QA uses universe profile `qa` (≤20 symbols).** Silence is not permission for another universe.

| Surface | Launch | Must | Must not |
| --- | --- | --- | --- |
| Windows | From `apps/windows`: **`npm run tauri:dev:qa`** | Feed `qa`, locked, **one** long-lived process | Bare `npm run tauri:dev` / full `sp500`; `tauri dev -- -- --profile qa` (Cargo steals `--profile`) |
| Android | **`make android-run-qa`** | QA flag, boots `qa`. Pin: `apps/android/app/src/main/assets/profiles/qa.txt` | `make android-run` (that is `sp500`); `pm clear` / wipe app data (deletes on-device SQLite) |

Windows also accepts `$env:DS_UNIVERSE_PROFILE = "qa"` then `npm run tauri:dev`, or `discount-screener-windows.exe --universe qa` (`--profile qa` on the **exe** only). Alias `test` → `qa`. Invalid profile **fails closed**.

`qa` is a **≤20 persistent feed symbol** sample (SP500 ∩ latest snapshot gap≥25% ∩ score DESC ∩ top 20; thin DB → priority fill), not the whole product. Checklist names use one-shot `ensure_symbol_loaded` and must not grow the feed. Restart only after a native rebuild. Attach the running process via `npm run ds-ui -- …` (CDP `127.0.0.1:9222`, DEV `window.__DS_AGENT__`); gate with `npm run ds-ui:self-check` then `npm run live-qa:checklist`.

Checklist: [`docs/valuation-live-qa-checklist.md`](docs/valuation-live-qa-checklist.md).

### Live QA report (mandatory after a QA session)

Write use-case scenarios (Sommerville / UML). Every executed path is one scenario. Paths not exercised are **Not run**.

| Field | Content |
| --- | --- |
| ID | `UC-n` plus a letter for an extension (`UC-3a`) |
| Use case | Goal the actor tries to complete |
| Precondition | State before the first step |
| Steps | Numbered actions the actor takes |
| Expected | Observable result if the product is correct |
| Actual | What the session showed |
| Status | Pass, Fail, or Not run |

Close with a count of Pass / Fail / Not run. List every **Not run** use case by name.

### Specification by example (mandatory)

Behaviour is a Gherkin `Scenario Outline` with an `Examples` table. Each row is one Case and one automated test. A bare `Scenario` is rejected. A table needs at least two Cases. Add a Case to an existing table before you write a new outline.

### Screen replay — experiment without the emulator (Android)

**Use this while tuning models.** Every number the Android dashboard draws comes out of one pure
function, `ScreenDataProjectionEngine.project(request)`, over one serializable input. Capture the
input once, then replay it on the JVM as many times as the experiment needs. No device, no network,
no database, and about two seconds per reading instead of an emulator boot.

1. **Capture** (needs a device, once per set of inputs). Arm the sink, let the app draw the screen,
   pull the file:
   ```
   adb shell touch /sdcard/Android/data/com.discountscreener.android/files/screen-capture/arm
   adb pull /sdcard/Android/data/com.discountscreener.android/files/screen-capture/request.json
   ```
   The sink writes one file and disarms itself, so a run nobody armed costs one `exists()` call.
2. **Replay** (no device), from `apps/android`:
   ```
   ./gradlew :core:replayScreen --args="--request=request.json"
   ./gradlew :core:replayScreen --args="--request=request.json --format=json --out=before.json"
   ```
   Two engine versions over one captured file differ only by the change under test, so `before.json`
   against `after.json` is a clean A/B of a scoring or valuation change.

**What it covers.** The projection: anchors, gaps, upside, confidence, tags, detail. **What it does
not.** Loading — governor, caches, store and provider all sit upstream of the request. Test those
through the Robolectric repository path (`ScreenCaptureReplaysTheScreenTest`), which also holds the
claim this tool rests on: a captured file replays into the rows the app had on screen.

Capture is the only step that touches a device, and it obeys the standing rule — no live provider
calls from a test. Replay reads a file.

### SEC companyfacts arrives sieved on both clients (mandatory)

A companyfacts file is about 4 MB and every reader uses a small part of it. Both clients cut it on
the stream: `SecCompanyFactsSieve` on Android, `sec_company_facts_sieve` on the desktop. Nothing
downstream sees a fact the sieve dropped, so widening a reader means widening its own sieve first.

The two field sets differ on purpose. See
[`docs/cross-platform-parity.md`](docs/cross-platform-parity.md) before you touch either.

### Network doubles — a client that streams has no string seam (mandatory)

Every http client takes its `OkHttpClient` as a defaulted constructor parameter. A client that
builds one inside itself cannot be reached by a test, and the path it owns ships unproven.

Two doubles live in `apps/android/app/src/test/.../`:

- `offlineHttpClient()` — throws an `AssertionError` naming the URL. Use it under any double that
  overrides some of a client's calls, so the calls it forgot land on the test.
- `cannedHttpClient(fragment, body)`, or `cannedHttpClient(routes)` for a flow that crosses several
  endpoints — answers a named URL with one body, and throws on the rest. Use it when the client
  consumes the response as a stream: no string of the body exists above the client, so the double
  must sit under it. Name the full URL, so a wrong path fails instead of matching by accident.

No test reaches a live provider. A red test says which URL leaked.

### Earnings gate (Android)

- Policy: [`shared/contracts/earnings-gate-policy.yaml`](shared/contracts/earnings-gate-policy.yaml) (`earnings-gate-policy/2`). Edit that file. The loader reads the six live keys. Kotlin holds no literals for them. Do not add percents, caps, or floors. Identities live in the engine.
- Cell: event-move vs median |AR|. Cheap = price < DCF. High = ratio > 1. Stale = quote width ≥ straddle. Hedge iff cost bps < event-move bps. Quiet ≥ total refuses the event move.
- SUE from Alpha Vantage is a diagnostic. Fit the joined observations that are not foreign to the issuer SUE series or the issuer AR series (`isForeignTo`). `n` is the leftover count. Below `min_sue_quarters` the fit is `short_history`. The card prints the slope and `n`. The cell ignores SUE until Juan asks. Do not add a YAML max quarter cap.
- Revenue trail: last `min_revenue_trail_quarters` Yahoo prints. The latest print is the event. Centre is `robustCentre` of the prior window. Scale is the MAD of that window. A foreign print in the prior window refuses the trail. A short window refuses the trail. Hold + last print strictly more than one scale unit below that centre → half size. Flag `revenueTrailCut`. Cell stays `CheapNormalRisk`. A flat prior window (scale 0) cuts any latest print strictly below the mode. No percent floor. Median is not the trail level. Field `revenueTrailCentreCents`.
- Alpha Vantage key lives in `filesDir/earnings/alphavantage.key`. Never commit `alphavantage.key`. Blank Save is a no-op. Clear deletes the file. The screen says whether a key is on disk. Never print the key.
- Import book is one writer on Earnings, System, and Positions. Restore log is a second Earnings SAF action and never plans a lot write. Held is exact ticker equality. Pin is paint (`pinHeldFirst`). Lot qty does not write `positionSizeBps`. Positions paints every lot. Closeness is a Core enum on the New York session day. Off-feed tap is a no-op. Contract: `shared/contracts/advisor-csv-import-v1.yaml` (`advisor-csv-import/3`).

### Commands and gates

- Strict TDD for behavior changes: failing test → smallest green → refactor while green.
- Per-surface suites: Desktop `cargo test` (from `apps/desktop`) + `--smoke`; Windows `cargo test` in `apps/windows/src-tauri` (include `dcf_model`, `quant_lens` when touching valuation/lens); Android `scripts/validate-android.ps1` (always `:core:test`; app tasks when SDK configured).
- **`--rerun` binds only to the task it follows.** One per task; `UP-TO-DATE` on a test task means the suite did not run.
- Valuation / Quant Lens: prefer goldens in `shared/contracts` and fixture regressions (ACGL residual income, TSLA disputed EV) over market-proximity asserts.
- **Valuation merge bar (mandatory):** a classifier, FCFF/WACC, CapEx→FCF, residual income, or model policy version change **must** pass from `apps/windows/src-tauri`: `cargo test --lib valuation_baseline::` and `cargo test --lib dcf_model::`. Single-ticker green is **not** enough. See `_bmad-output/implementation-artifacts/valuation-multi-name-baseline-policy.md`.
- External providers: ≥5 distinct real upstream samples; never invent Yahoo/SEC payloads when live behavior matters.
- `cargo fmt` before finishing Rust changes.
- When practical, run mutation testing around changed logic; state the gap if not.
- **Mutate a constant in both directions**, or the round proves half of what it claims. A `>` assert survives one direction by construction.
- **A field the engine writes needs a reader.** Grep the field name under `src/main` and name the consumer before the work is done.
- **A numeric gate must state the sign it expects**, not only the magnitude.
- **A property no live QA can reach is verified by test or it is not verified.** Check `qa`-universe path reachability before arming a live stage.

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
| A past failure stayed in chat | Write a row in [`docs/operational-anti-patterns.md`](docs/operational-anti-patterns.md). Advisor names that row on the next plan |

### Aggregation — no naked averages (mandatory)

A plain `sum / n` over an issuer series is a defect. Use the one implementation in [`valuation-core/src/numerics.rs`](apps/windows/src-tauri/valuation-core/src/numerics.rs). Do not write a second one.

| Situation | Use |
| --- | --- |
| Centre of an issuer annual series (ROIC, margin, growth, coupon) | `valuation_core::robust_mean(&sample, MAX_ABSOLUTE_Z)` |
| "Is this observation contamination?" | `valuation_core::standardize(&sample)` then `.outliers(MAX_ABSOLUTE_Z)` |
| Cross-sectional summary across issuers | Median, and say so |
| A genuine population total (shares, dollars to a balance) | A sum is correct; this rule is about *estimates* |

- **Scores are median/MAD, not mean/sd.** Mean/sd cannot flag outliers at `n ≤ 10` and the outlier inflates its own scale.
- **`MAX_ABSOLUTE_Z = 3.0` does not move.** Trimming must **refuse**, never fall back to the untrimmed mean. Report how many observations were discarded.

### Numerical conclusions — pre-presentation checklist (mandatory)

Before you present a dollar / share / rate to Juan as a **conclusion**, show this list. Juan is not the first sniff test.

1. **Sniff vs 10-K/10-Q or fixture.** If >~50% off, find the bug first (units, shares, CapEx, rate kind).
2. **A cluster claim needs ≥2 names.** One name is specific to that ticker.
3. **Declare the neutral baseline before the experiment**, not after the result.
4. **Mark dubious inputs pending.** Do not call the result clean evidence.

### Analyst-method lifecycle closure (mandatory when in scope)

Applies to evidence-ledger, analyst-import, model-run, or current-projection work. Full proof table: [`docs/analyst-method-lifecycle.md`](docs/analyst-method-lifecycle.md). **Read that file before you implement or close that work.**

Keep three states distinct: **design-ready**, **implemented**, and **independently closed**. A green builder handoff is not independent closure.

Reserved claims: `evidence-bound`, `atomic`, `idempotent`, `reconstructible`, `dual-lock`, `fail-closed`. Green tests prove only the encoded properties.

### Manual procedures (must follow)

When classifier, CapEx→FCF, WACC/CoE, residual income, model policy version, or demand-valuation change:

1. **Automated gate.** From `apps/windows/src-tauri`: `cargo test --lib dcf_model::` and `valuation_baseline::`. Add `quant_lens::` if EV agreement is in scope. Add `npm run test:e2e:native:cof` from `apps/windows` if Detail valuation routing changes.
2. **Live QA after UI-visible model changes.** Profile `qa` only. Six-name path: [`docs/valuation-live-qa-checklist.md`](docs/valuation-live-qa-checklist.md). Reuse one process. One-shot load missing names. Never switch to `sp500`.
3. **After policy bumps.** Detail must clear stale DCF. `valuation_unavailable_reason` must be visible on refuse.
4. **Desktop** may lag Windows numbers. It must still **fail-closed** on unclassified.
5. **New sector/industry.** Tokens + class tests + a fixture that would have failed under the old model + merge bar.
6. **New evidence-led lane in Detail / Quant Lens.** Fail-closed ticker / security / identity / share basis (no lexical `LIMIT 1`). Exercise the production presenter. Cross IPC fixed-point as decimal strings. Add a DOM-scoped native assert. Run the architecture-named Android, native E2E, and live `qa` gates. A plan may not downgrade those gates.

### Anti-patterns that already bit us

Full ledger: [`docs/operational-anti-patterns.md`](docs/operational-anti-patterns.md).

- Read that file before a change that can re-trigger the same failure shape.
- Add a new row when a failure mode appears. Do not leave it only in chat.
- Do not paste the table into this file. The pointer here is the standing gate.

### Advisor (docs gate)

Advisor reads standing docs and names drift. Advisor does not invent product rules.

Every Advisor review opens this file, [`_bmad-output/project-context.md`](_bmad-output/project-context.md), the spike contract, [`docs/operational-anti-patterns.md`](docs/operational-anti-patterns.md), and the spike memlog (`{spec-folder}/.memlog.md`, or the PRD memlog if the spec folder does not exist yet).

A proposed step that matches a ledger row is a P0 until the plan uses that row's **Do instead**.

A proposed step with no doc home is a doc gap.


## Conventions (general)

- Fixed-point money: `*_cents`, `*_bps`, `*_hundredths`, `*_millis` stay integers unless strongly justified.
- Type-driven design: encode invariants in types; validate at boundaries; keep invalid states unrepresentable.
- Decouple market-data, persistence, and UI/rendering.
- Temp work only under `.agents/workspace/tmp`.
- Seek decision log: append nodes to [`.grok/decisions.json`](.grok/decisions.json). Do not replace the tree.
- User-visible behavior changes: update or link docs (this file, project-context, contracts, operator docs) — do not bury long operational guidance only in comments. One home per fact. Edit the home. Pointers stay pointers.
- Demand-driven expensive work: history, valuation, and heavy fetches stay bounded and on-demand where practical.
- Android Detail second open of a warm ticker paints from the session cache. Skip disk and network when memory already holds the chart and DCF. Leftover and dip boards reuse the last assemble when the input fingerprint is unchanged. Session flags (`revisionHistoryHydrated`, `pricingHistoryHydrated`, `liveDcfResolvedSymbols`, replay backing) clear in `resetInMemoryLocked`.
- Sparse/unavailable/stale states must be explicit — never smooth missing valuation into a fake “Strong” story.

## Documentation Map

Hub: [`docs/index.md`](docs/index.md). Read [`_bmad-output/project-context.md`](_bmad-output/project-context.md) before inventing product rules.

| When | Read |
| --- | --- |
| Advisor plan review | This file + [`_bmad-output/project-context.md`](_bmad-output/project-context.md) + [`docs/operational-anti-patterns.md`](docs/operational-anti-patterns.md) + the spike contract + the spike memlog |
| Spike memory | `{spec-folder}/.memlog.md` via [`_bmad/scripts/memlog.py`](_bmad/scripts/memlog.py) — append-only; read before you invent |
| Live valuation QA | [`docs/valuation-live-qa-checklist.md`](docs/valuation-live-qa-checklist.md) |
| Known operational failure | [`docs/operational-anti-patterns.md`](docs/operational-anti-patterns.md) |
| Analyst-method / ledger | [`docs/analyst-method-lifecycle.md`](docs/analyst-method-lifecycle.md) |
| Valuation ADRs | [`_bmad-output/planning-artifacts/valuation-model-family-architecture.md`](_bmad-output/planning-artifacts/valuation-model-family-architecture.md) |
| Contracts | [`shared/contracts/README.md`](shared/contracts/README.md) |
| Advisor CSV import | [`docs/advisor-csv-import.md`](docs/advisor-csv-import.md) · [`shared/contracts/advisor-csv-import-v1.yaml`](shared/contracts/advisor-csv-import-v1.yaml) (`advisor-csv-import/3`) · Android: [`_bmad-output/specs/spec-android-chase-portfolio/SPEC.md`](_bmad-output/specs/spec-android-chase-portfolio/SPEC.md) |
| Pre-earnings risk gate | [`_bmad-output/planning-artifacts/prd-pre-earnings-risk-gate-2026-08-27.md`](_bmad-output/planning-artifacts/prd-pre-earnings-risk-gate-2026-08-27.md) · [`shared/contracts/earnings-gate-policy.yaml`](shared/contracts/earnings-gate-policy.yaml) (`earnings-gate-policy/2`) · [`_bmad-output/specs/spec-earnings-gate-identities/SPEC.md`](_bmad-output/specs/spec-earnings-gate-identities/SPEC.md) |
| BMAD process | [`.grok/rules/bmad.md`](.grok/rules/bmad.md) |
| Seek decision log | [`.grok/decisions.json`](.grok/decisions.json) — append `open` / `taken` / `failed` / `skipped` nodes. Do not replace the tree. |
