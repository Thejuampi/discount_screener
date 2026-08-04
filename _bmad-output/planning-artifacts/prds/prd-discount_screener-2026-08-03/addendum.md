# PRD Addendum — Valuation Quant Core

*Depth that belongs downstream (architecture, solution design) or earned a place but does not fit the PRD narrative. Companion to `prd.md`, 2026-08-03.*

---

## A. Decisions and rejected alternatives

### A.1 Migration posture — greenfield core, old engine retired

**Chosen.** Build the pure kernel fresh with no reference to current module structure; port fixtures.

> **Superseded 2026-08-03, by the user.** The old lanes are **not** deleted at
> cutover. They stay in the Shell marked deprecated, and are retired module by
> module only once the core carries the behaviour each one is being retired for.
> This resolves Open Question 8 toward coexistence. It also removes the sharpest
> objection to greenfield — that nothing works until a lot of it works — because
> the shipping engine keeps working throughout, and it restores the per-step
> measurability that a hard cutover would have cost.

| Option | Why rejected |
|---|---|
| Shadow-run both engines, cut over on gate pass | Buys evidence but costs a dual-run period and doubles the surface that must stay correct. The Calibration Gate already supplies the evidence a shadow run would; running both mostly adds a reconciliation burden between two engines nobody intends to keep. |
| Step-by-step replacement in place | Each step is individually safe, but the current module structure is itself part of the defect — the two-lane split is the root cause, and an in-place sequence preserves it until the last step. Refactoring toward a shape you have already decided to abandon is wasted motion. |

**Consequence to manage:** nothing works until a lot of it works. Open Question 8 (coexistence behind a flag vs delete-at-cutover) is the practical form of this risk, and it matters most for capturing the pre-change gate baseline — FR-40 requires one, and it has to be taken from the *current* engine before the new one exists.

### A.2 BDD runner — real Gherkin with cucumber-rs

**Chosen.** Literal `.feature` files with `Scenario Outline` + `Examples`, executed by the `cucumber` crate, steps binding to the pure Core.

| Option | Trade |
|---|---|
| Table fixtures in `shared/contracts` | Cross-platform parity for free and no new dependency, but Given/When/Then lives in the harness rather than in the artifact — which defeats the readability that motivates BDD here. Retained as the likely **serialization** format for large Examples tables (see C.3). |
| `#[rstest]` case tables | Fastest to write, closest to the code, zero ceremony. Rejected because the tables would be Rust source: unreadable as a specification, and unusable by a future Kotlin implementation, which FR-46 requires. |

**Why the readability matters more than the dependency cost.** The point of the table discipline is that a reviewer can read down a column and see the boundary between behaviours. That only works if the table is the artifact a human reads. A Rust attribute macro is not.

### A.3 Gate universe — pinned S&P 500 snapshot

**Chosen.** Fetch once into a versioned point-in-time artifact; the gate runs offline against it.

| Option | Why rejected |
|---|---|
| Live fetch each gate run | What `valuation_high_signal` does today. Always current, but rate-limit exposed, slow, and a provider outage blocks the gate. A gate that cannot run is a gate that gets skipped. Failure mode §17 is the recorded cost. |
| ~100 stratified names | Cheap to refresh and adequate for correlations, but thin for empirical-Bayes hyperparameters, and the stratification criteria become an attack surface — every "is this cohort representative?" argument is one the snapshot avoids by simply being the whole index. |

**Cost accepted:** the snapshot ages. Mitigated by explicit versioning (FR-41) and a stated refresh cadence, both of which make staleness visible rather than silent.

### A.4 Scope — core plus shell contract, not surfaces

UI rendering of intervals and variance attribution is genuinely a UX problem (how do you draw a distribution in a dense workstation table without it becoming noise?) and deserves its own document rather than a paragraph here.

### A.5 Rejected: stronger enforcement of the new-outline rule

FR-44 settles for a manifest entry. Considered and rejected:

- **Require the commit to show a search of existing tables.** Unenforceable — no way to verify a search happened.
- **Cap the number of Scenario Outlines per feature file.** Creates the wrong incentive: it pushes toward a new *file* rather than toward a new Case.
- **Require review approval for a new outline.** Meaningless for single-developer delivery.

The manifest works because it forces the author to write down what the new outline covers that no existing table does, which is the actual thinking the rule is trying to provoke. If outline count grows faster than Case count (SM-6), the rule is not working and needs revisiting.

---

## B. Mathematical detail

Full derivations are in `_bmad-output/implementation-artifacts/quant-method-mathematical-specification-2026-08-03.md`. Reproduced here only where an FR depends on the exact form.

### B.1 Growth Posterior (FR-12)

$$\hat g = \frac{\sigma^{-2}_{\text{tr}} g_{\text{tr}} + \sigma^{-2}_{\text{fw}} g_{\text{fw}}}{\sigma^{-2}_{\text{tr}} + \sigma^{-2}_{\text{fw}}}, \qquad \mathrm{Var}(\hat g) = \big(\sigma^{-2}_{\text{tr}} + \sigma^{-2}_{\text{fw}}\big)^{-1}$$

$$\sigma^2_{\text{tr}} = \frac{1}{n(n-1)}\sum_{t}(g_t - \bar g)^2, \qquad \sigma^2_{\text{fw}} = \frac{1}{n_a}\left(\frac{g^{\text{hi}} - g^{\text{lo}}}{2z}\right)^2$$

Absence is the limit $\sigma^2 \to \infty$, giving weight exactly zero — which is why FR-7 (Absent ≠ zero) is a correctness requirement and not a style preference. The $z$ in the forward term converts a high/low range to a standard deviation and depends on what the provider's high/low actually represents (full range vs some quantile). **This is unresolved and folds into Open Question 1.**

### B.2 Growth Persistence (FR-16, FR-17)

$$\kappa = -\ln \rho_1, \qquad t_{1/2} = \frac{\ln 2}{\kappa}$$

with $\rho_1$ the lag-1 autocorrelation of the realized growth series. Shrinkage toward the pooled prior:

$$\hat\rho_i = \frac{(n_i/\sigma^2_w)\,\rho_i + (1/\sigma^2_b)\,\bar\rho}{n_i/\sigma^2_w + 1/\sigma^2_b}$$

$\sigma^2_w$ within-Issuer, $\sigma^2_b$ between-Issuer, both estimated from the Universe Snapshot.

**Practical guards.** $\rho_1 \le 0$ means no persistence — $\kappa \to \infty$, immediate reversion. $\rho_1 \to 1$ means $\kappa \to 0$ and no reversion at all, which makes the integral in B.3 diverge unless the discount rate exceeds the growth rate. Both are handled by the FR-27 arithmetic guard, and both must appear as Cases in the persistence Examples table.

### B.3 Projection (FR-18)

$$R(t) = R_0 \exp\!\left[g_\infty t + \frac{\hat g - g_\infty}{\kappa}\left(1 - e^{-\kappa t}\right)\right], \qquad V = \int_0^\infty R(t)\,m(t)\,e^{-rt}\,dt$$

Evaluated by quadrature. Resolution is a runtime/accuracy trade bounded by the FR-4 tolerance, not an economic parameter — it may be tuned freely as long as the tolerance holds, and it is therefore **not** a §5.3 constant.

### B.4 Cost of capital (FR-20 – FR-25)

$$r_d = r_f + s(\text{coverage},\ \text{leverage},\ \sigma_{\text{EBIT}}), \qquad \beta^* = \frac{\mathrm{se}(\beta)^{-2}\beta_{\text{own}} + \tau^{-2}\beta_{\text{peer}}}{\mathrm{se}(\beta)^{-2} + \tau^{-2}}$$

$$P_{\text{index}} = \sum_t \frac{\mathrm{CF}_t}{(1+r_m)^t} + \frac{\mathrm{CF}_T(1+g_\infty)}{(r_m-g_\infty)(1+r_m)^T} \;\Longrightarrow\; \pi = r_m - r_f$$

The defining property of $s(\cdot)$ is $\partial r_d/\partial(D/E) > 0$ (FR-20), which the current $I_t/D_t$ does not have. Functional form of $s$ is a solution-design decision; a monotone fit is the requirement.

### B.5 Terminal value (FR-28, FR-29)

$$V_T = \frac{E_T\left(1 - g_\infty/\mathrm{ROIC}\right)(1+g_\infty)}{r - g_\infty}$$

Carried over unchanged from the current forward lane. Absent ROIC ⇒ retention charge of zero ⇒ $V_T = E_T/r$ (FR-29). An *observed* ROIC is used as observed, never floored at the cost of capital — flooring is what collapsed SW (1.5%), OMC (2.9%) and CHTR (5.0%) onto one payout and erased the differentiation the charge exists to create.

---

## C. Implementation shape (solution-design input, not requirement)

### C.1 Module layout

```
apps/windows/src-tauri/
  valuation-core/          # new crate: pure, no I/O deps
    src/
      evidence.rs          # Observation, Uncertainty, Provenance, EvidenceBundle
      posterior.rs         # Growth Posterior, inverse-variance fusion
      persistence.rs       # autocorrelation, shrinkage, kappa
      projection.rs        # closed-form revenue path + quadrature
      capital.rs           # cost of debt/equity, WACC, market frame
      terminal.rs          # retention charge
      routing.rs           # business class, refusals
      publish.rs           # posterior -> percentiles, fixed-point boundary
    tests/
      features/*.feature   # Gherkin, the specification
      manifest.toml        # outline -> behaviour, FR mapping (FR-44, FR-45)
      steps/               # cucumber step definitions
  src/                     # existing crate becomes the Shell
```

Purity is enforced by the new crate's dependency list, checkable in CI (`cargo-deny` or an explicit allowlist) — this is the mechanism behind FR-1's build-time lint.

### C.2 Boundary types

`Observation<T>` as a sum type over `Measured { value, variance, provenance }` and `Absent { reason, provenance }` makes FR-7 unrepresentable-if-wrong rather than checked-at-runtime, consistent with the repository's type-driven-invariants rule. The absence of a `Default` impl and of any public constructor taking a bare value is what enforces FR-6.

### C.3 Large Examples tables

Gherkin tables become unreadable past roughly 12 columns. For the wide cases — full Evidence Bundles for cohort-level scenarios — the likely shape is a narrow `.feature` table referencing named fixture rows in `shared/contracts`, keeping the Given/When/Then in Gherkin while the bulk data stays tabular and diffable. This is where option A.2's runner-up returns as a serialization format rather than a runner.

### C.4 Gate implementation

The Calibration Gate is Shell code, not Core: it runs the Core over the snapshot, computes Residuals against market price, and evaluates A1–A5. Market price therefore never enters the Core, satisfying FR-35. The baseline required by FR-40 is a checked-in artifact keyed by snapshot version.

---

## D. Sequencing note

The `quant-method-mathematical-specification-2026-08-03.md` Part IV sequence was written for in-place stepwise replacement, which A.1 rejected. Under greenfield the ordering is different: the Evidence Intake Contract and the scenario harness come first because everything else is specified against them, and the pieces with the largest measured impact (cost of debt, growth fusion) come first *within* the Core. The old Part IV table should be read as an impact ranking, not as a plan.

**One item survives from it unchanged and is easy to lose:** the pre-change gate baseline must be captured from the *current* engine, against the *first* pinned snapshot, before the new Core exists. Without it FR-40 has nothing to compare to and the first gate run can only report absolute values.
