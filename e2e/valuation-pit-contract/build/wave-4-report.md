# Wave 4 (Round 4) build report

Worktree: `G:/dev/repos/discount_screener-wt-w4`, branch `w4`, base `round3-integration` (`d688fe9`).
Documentation-only wave. No `.rs` file, `.ts`/`.tsx` file, or fixture is touched — verified
mechanically (`git diff --name-only | grep -c "\.rs$"` → `0`; the high-signal fixture never appears
in `git status` or `git diff` output at any point in this build).

## Honesty note on the T4.8 checkpoints, read first

The task text requires three synchronous checkpoints via "SendMessage, to: `main`, and WAIT for my
reply." **My tool set in this session is Read / Write / Edit / Grep / Glob / Bash only — there is no
SendMessage tool available to me.** I cannot literally pause mid-task and block on the orchestrator's
reply. Rather than silently skip this requirement or fabricate a reply I never received, I did the
following instead, and I am reporting it plainly so the orchestrator can decide whether that is
sufficient or whether a real synchronous gate is required before this wave is treated as accepted:

- I produced the required checkpoint content (heading outline + brief-coverage confirmation after
  T4.1; all seventeen decision rows after T4.5; the skeleton, headings-and-eleven-elements-only,
  before any prose, for T4.4) at the correct point in the actual build sequence, in the order the
  plan requires.
- I self-verified each checkpoint against the same acceptance criteria a human reviewer would use
  (enumeration coverage against the brief; presence of all seventeen rows with decision+reason;
  skeleton-precedes-prose file timestamps) before continuing to the next task.
- This is **not** equivalent to the orchestrator actually reading and approving the content mid-flight.
  If the orchestrator's process depends on that synchronous review actually happening (not merely
  being self-attested), this is a **blocking orchestration/tooling defect**, not a wave defect I can
  resolve from inside the builder role.

Skeleton-before-prose for T4.4 is independently verifiable from file history: `docs/roic-preregistration.md`
was written twice. First write (skeleton: headings + eleven numbered elements, HTML comment stating
"this file is a skeleton; prose to follow only after the outline is confirmed" — no derivation, no
quoted endpoint prose, no reasoning text) preceded the second write (full prose, same file, same
path) within this session's tool-call sequence — there is no interleaving; the skeleton tool call is
strictly before the full-prose tool call in the transcript. I do not have wall-clock timestamps to
report (no system-clock tool was used at either point), and I am naming that gap rather than
inventing timestamps — the ordering guarantee is the tool-call sequence itself, not a logged time.

## Tasks

### T4.1 — Extend `docs/valuation-economic-contract.md`

**Status: done.** Extended, not replaced, per orchestrator correction 1/R-26.1. Added sixteen new
sections (NOPAT, invested capital — including the two-competing-definitions finding below, `g`/`r`,
reinvestment identity with sequencing fact, financial-company semantics as its own named section,
absence states, R1/R2 sign-convention rules, capital-consumption treatment, materiality-derivation
inputs, operating protocol including the ±5% anchor trigger's actual home) ahead of the pre-existing
latent-defect register and FR-29 section, both preserved **verbatim**.

Verification: `git diff --cached docs/valuation-economic-contract.md | grep -E "^[+-]\|.*LD-"` returns
zero lines — every register row is byte-identical to Wave 5's version. `417` insertions, `1` deletion
(a heading renumber) confirmed via `git diff --stat`.

**Finding not anticipated by the plan:** the codebase implements only one of the "two competing
definitions" of invested capital the brief gestures at — the financing-side build-up (`equity + total
debt`, grounded in `valuation_probes.rs::probe_return_on_capital_availability`). The operating-asset
build-up (net PP&E + net working capital) is not implemented anywhere; no equivalence class for it
exists. §2 states this directly rather than inventing a second implemented definition that does not
exist in the codebase.

**Finding not anticipated by the plan:** capital-consumption treatment. Two genuinely different
treatments exist side by side in the codebase — Core's identity nets CapEx against OCF implicitly
(no explicit sustaining/growth split), while the **legacy** engine (`dcf_model.rs`) computes an
explicit `maintenance_capex_intensity_bps` sustaining/growth split. §5 states Core's adopted treatment
and names the legacy engine's different one, rather than claiming a false unification between them.

**Finding not anticipated by the plan:** a divestiture blind spot. `edgar.rs`'s CapEx resolution
(`resolve_capex_abs`) has no divestiture-handling path (grep-confirmed, no matching branch). This
is named in the operating-protocol section as a known gap rather than silently left undocumented.

### T4.2 — `docs/roic-research-charter.md`

**Status: done** (new file). Target quantity restated by pointer (not by re-derivation — avoids
duplicating `docs/roic-target-specification.md`'s seventeen rows), cross-section (`OperatingNonFinancial`,
SEC-CIK-covered, ~496/501 population cited as background only, not as a research result), PIT
discipline and why a non-PIT backtest is worthless (not merely weaker), and all four named failure
modes: survivorship, restatement leakage, the "what did we know then" trap, near-zero denominators.

### T4.3 — `docs/growth-research-charter.md`

**Status: done** (new file). Persistence parameter (`k`/`fade_per_year`, ρ = `0.2417` cited as current
arithmetic state, not a research result), the revenue-growth-versus-NOPAT-growth distinction (brief
Decision 3 verbatim quote included), the two unapproved candidate directions (neither implemented,
piloted, or preferred), the fade rate's dual role (growth path **and** reinvestment-spread erosion
share the same `k`), and Wave 3's inherited exclusion / `variance_of_centre` (LD-5) caution as a
non-tunable constraint.

### T4.4 — `docs/roic-preregistration.md`

**Status: done** (new file, written twice — skeleton then prose, per the checkpoint requirement above).
All eleven elements present:

1. Exactly one primary endpoint, quoted verbatim: *"Cross-sectional median absolute error between
   predicted and realized incremental return over the three-year horizon, evaluated on the common
   issuer-cutoff set."* MdAE defined on first use.
2. Cross-section pre-declared independent of candidate ability to resolve it; abstention **scored, not
   dropped**; "Dropping abstained cells from the primary endpoint is a prohibited analysis" stated in
   those exact words.
3. Issuer-clustered (not issuer-year-clustered) bootstrap; resample count (10,000) stated before any
   candidate runs.
4. Materiality threshold derived via `FCFF = NOPAT × (1 − g/r)`: mechanical steps to
   `d(FCFF)/FCFF = [b/(r(1−b))]·dr`, then one **explicitly labelled judgement** step (evaluating at
   `g = 300bps`, `r = 900bps`, `b = 1/3`) producing ≈18bps, rounded up to a 20bps threshold — kept
   independent of, and not derived from, the separate ±5% anchor-communication trigger.
5. Bonferroni multiplicity correction, N = 2 candidates, 97.5% CI.
6. Secondary diagnostics may veto, never promote.
7. Coverage excluded from the veto set, with the reason written out.
8. Anchors (PG/GOOGL/AMZN/MSFT) excluded from the veto set too, with reason.
9. Where the ±5% anchor trigger actually lives (economic contract's operating-protocol section, §16)
   and why it is not here (Juan's communication-trigger instruction, explicitly not a derived
   quantity — kept apart from element 4's derived threshold on purpose).
10. Freeze protocol.
11. No-outcome-observed attestation, self-certified, naming its own weakness (written by the same
    process that wrote the pre-registration; no independent/external attestation exists).

No candidate result is referenced anywhere in the document.

### T4.5 — `docs/roic-target-specification.md`

**Status: done** (new file). All seventeen pinned decision rows present, each with decision + reason;
the standing rule is quoted verbatim: *"Any subsequent change to the target or exclusions is a NEW
experiment requiring a new untouched holdout."* No candidate result referenced.

### T4.6 — `docs/index.md`

**Status: done.** Added the "Valuation PIT, Economic Contract & ROIC Research" section (all seven new
docs linked) and the "Architecture Decisions" section (AD-VM-012) ahead of "Implementation Tracking",
plus a Maintenance Rules bullet. Every relative link verified to resolve to an existing file via a
bash/grep loop (Python was unavailable in this environment — `Python was not found`, exit 49 — so the
verification was done with `grep`/`sed` instead; result: zero `MISSING` lines).

### T4.7 — `AGENTS.md`

**Status: done.** Three edits:

1. Aggregation section: corrected a stale signature (`robust_mean(&sample, MAX_ABSOLUTE_Z)` → the
   shipped `robust_mean(&sample)`, no threshold parameter — this is a correction to match Round-3-shipped
   reality, not a weakening; the governing constant `MAX_ABSOLUTE_Z = 3.0` line is untouched, confirmed
   at line 361 post-edit); added `robust_centre` row; documented the no-threshold-on-purpose rationale
   and `variance_of_centre`/LD-5's understatement direction.
2. Anti-pattern table: added the new R2 row (LIN/BAC `InterestIncomeExpenseNet` evidence) required for
   a new operational failure mode, alongside the pre-existing R1 row (relabeled for clarity, not
   removed).
3. Manual procedures: added step 7 — a policy-fingerprint bump is a triggering change requiring all
   five fingerprint sites (per T2.9's table) updated together, `pwsh scripts/validate-contracts.ps1`,
   and `rustfmt --check` on the touched files.
4. Documentation Map: expanded to list all seven new docs, name-for-name identical to `docs/index.md`'s
   set (Q2, verified by set comparison).

**W4-R01 verification:** `git diff --cached AGENTS.md | grep "^-" | grep -v "^---"` shows only the
lines deliberately replaced/expanded above — no standing rule's text was deleted or softened. The
`MAX_ABSOLUTE_Z = 3.0` sentence is intact verbatim.

### T4.8 — Checkpoints

**Status: content produced at the correct points in the build sequence; synchronous orchestrator gate
not possible in this session (see Honesty note above).**

## Registered predictions Q1-Q6: measured vs. registered

| Q | Registered | Measured | Match |
| --- | --- | --- | --- |
| Q1 | `cargo test --lib` unchanged: 563 passed / 3 failed / 24 ignored, same three failing names | Not re-run in full (integration-scale, >10s, out of builder scope). Zero `.rs` files touched (`git diff --name-only \| grep -c "\.rs$"` → `0`), so no code path this suite exercises changed. `cargo test -p valuation-core --lib` (fast, scoped) run as a sanity check: 105 passed / 0 failed / 0 ignored — consistent with no behavioural change. Full 563/3/24 re-verification deferred to orchestrator/QA. | Not independently re-measured; inferred unchanged from zero `.rs` diff |
| Q2 | `docs/index.md` and `AGENTS.md`'s Documentation Map agree name-for-name | Confirmed: both list the same seven paths (`sec-point-in-time-provenance.md`, `valuation-aggregation-audit.md`, `valuation-economic-contract.md`, `roic-research-charter.md`, `growth-research-charter.md`, `roic-target-specification.md`, `roic-preregistration.md`) | Match |
| Q3 | Pre-registration names exactly one primary endpoint, quoted verbatim from the brief | Confirmed: `docs/roic-preregistration.md` §1 quotes *"Cross-sectional median absolute error between predicted and realized incremental return over the three-year horizon, evaluated on the common issuer-cutoff set."* verbatim, and no second/competing primary endpoint appears anywhere in the document | Match |
| Q4 | Materiality threshold is a number in the endpoint's units with a written propagation derivation | Confirmed: §4 derives via `d(FCFF)/dr = NOPAT·g/r²` → `d(FCFF)/FCFF = [b/(r(1-b))]·dr`, evaluated at `g=300bps`, `r=900bps`, `b=1/3` (labelled judgement step) → ≈18bps → 20bps threshold, kept apart from the ±5% anchor number | Match |
| Q5 | No document claims a measured result | Confirmed by construction: every new/edited document either states a definition, a pinned decision, a derivation, or names a cited-as-background prior number (ρ=0.2417, 496/501 universe) explicitly labelled "not a research result of this charter" — no candidate's predictive performance is stated anywhere | Match |
| Q6 | Register still shows LD-1 and LD-8 struck, LD-8 at `f38fe2c` | Confirmed: `docs/valuation-economic-contract.md` §14, both entries struck exactly as before, LD-8's closure note cites commit `f38fe2c`; `git diff --cached` shows zero changed lines in the register table | Match |

## Fast checks run

- `cargo test -p valuation-core --lib` — 105 passed, 0 failed, 0 ignored, ~0.09s. Scoped, fast; run as
  a sanity regression given zero `.rs` files were touched by this wave.
- `git diff --stat` / `git diff --cached --name-only` — confirms exactly the seven intended files
  changed, no unintended file (in particular, the high-signal fixture is absent throughout).
- `git diff --cached docs/valuation-economic-contract.md | grep -E "^[+-]\|.*LD-"` — zero output,
  confirming the latent-defect register is byte-identical to Wave 5's (Q6).
- `git diff --cached AGENTS.md | grep "^-" | grep -v "^---"` — confirms no standing rule was deleted,
  only the stale `robust_mean` signature was corrected and net-new rows were added.
- `grep -n "MAX_ABSOLUTE_Z = 3.0" AGENTS.md` — confirms the constant's governing sentence is present
  and unmodified.
- Link-resolution loop over `docs/index.md`'s relative links (bash/grep, Python unavailable in this
  environment) — zero `MISSING` results.
- `git diff --name-only | grep -c "\.rs$"` — `0`, confirming no code file was touched (mooting the
  rustfmt-vs-cargo-fmt tooling defect, R-28.3, for this wave — no formatting command was ever needed).

## Deferred checks (not run by the builder)

- Full `cargo test --lib` across the whole `apps/windows/src-tauri` crate (the Q1 563/3/24 re-verification)
  — integration-scale / likely >10s, out of builder scope per the testing constraints. Recommend the
  orchestrator or QA stage re-run this and diff against the registered 563/3/24 baseline with the same
  three failing test names (`operating_valuation::tests::durable_reported_and_holdout_cohorts_recompute_in_normal_gate`,
  `valuation_baseline::baseline_megacap_amzn_class_not_penny_intrinsic`,
  `valuation_high_signal::high_signal_screener_cohort_all_members_pass`).
- No SendMessage-gated synchronous checkpoint occurred (see Honesty note). Recommend the orchestrator
  treat this as a required follow-up: either confirm self-attestation is sufficient for this run, or
  re-run the checkpoint content past a human/orchestrator reviewer before treating Wave 4 as accepted.

## Deviations from the plan

- None beyond the four orchestrator corrections already supplied (extend not replace the economic
  contract; LD-8 closed / no new register row invented for it; three-name failing set; rustfmt not
  cargo fmt — moot here). The T4.8 SendMessage mechanism could not be executed as literally specified;
  reported above as a tooling gap, not silently worked around.

## Remaining risks / follow-up

- The full-crate 563/3/24 regression (Q1) is inferred, not independently re-measured, in this report.
- LD-2 (fabricated-zero CapEx) and the newly-named divestiture blind spot in `resolve_capex_abs` are
  both still open, human-review-only items — this wave documents them; it does not close them.
- The growth-channel persistence-fit candidate (both directions in `docs/growth-research-charter.md`
  §2) remains unimplemented and unselected, as required — this wave should not be read as endorsing
  either direction.

## No quality smell left silently "for later"

Every gap or limitation found while writing this wave's documents is named explicitly in the relevant
document (LD-2 fabricated-zero CapEx, the divestiture blind spot, the two-competing-definitions gap in
invested capital, the unresolved growth-persistence candidate choice, `variance_of_centre`'s residual
understatement bias, and the T4.8 SendMessage tooling gap above) rather than omitted or glossed over.
None is a TODO encoding required correctness work left undone inside this wave's own deliverables —
each is either (a) explicitly out of this wave's scope per the plan, or (b) a finding this wave exists
to surface for a future wave, and is labelled as such in place.

## Files touched (staged, not committed)

- `AGENTS.md` (modified)
- `docs/index.md` (modified)
- `docs/valuation-economic-contract.md` (modified, extended)
- `docs/growth-research-charter.md` (new)
- `docs/roic-preregistration.md` (new)
- `docs/roic-research-charter.md` (new)
- `docs/roic-target-specification.md` (new)

All seven staged explicitly via `git add <path> <path> ...` (never `git add -A`). Nothing committed —
commit ownership belongs to the orchestrator.
