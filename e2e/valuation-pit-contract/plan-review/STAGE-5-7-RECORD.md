# Stages 5 and 7 — the specialist records the harness did not persist

Both agent output files (`ab4bcae96e2609744.output`, `aa462e2e026091fa7.output`) are **zero bytes**,
the same defect that made Wave 4's T4.8 checkpoint unrecoverable (R-29.1). This file is the
Orchestrator's transcription of what the two specialists returned, kept because the session transcript
is not durable.

Dispositions live in `ORCHESTRATOR-RULINGS.md` — R-32 for Stage 5, R-33 for Stage 7. **This file holds
only what those rulings do not already quote**, so that nothing is recorded twice and no reader has to
reconcile two versions of the same sentence.

---

## Stage 5 — implementation reviewer, verdict `REVISE`

Its blocking finding (five carried items absent from the latent-defect register) is quoted, verified
and disposed of in R-32.1–R-32.2. Its Finding 4 is in R-32.3. What follows is the part of its report
that exists nowhere else.

### Method it used, in its own terms

Read `plan.v6.md`, the rulings file, all six wave reports, then the merged tree directly — `edgar.rs`,
`driver_resolution.rs`, `dcf_model.rs`, `valuation_fixture_capture.rs`, `valuation_core_adapter.rs`,
`valuation-core/src/{projection,residual_income,numerics,evidence}.rs`, the cucumber features, the SEC
fixture corpus, and `docs/valuation-economic-contract.md`. It then **ran the full suite live** rather
than accepting the wave reports, and reverted the one fixture file the run rewrote. It ran three
explicit anticipatory passes before finalising.

### Independent reproduction of ruled predictions — the strongest evidence in the review

Not previously verified by any party other than the wave that produced them:

| claim | reviewer's independent result |
|---|---|
| suite state | **563 passed / 3 failed / 24 ignored**, the same three protected names |
| R-24.2, CHTR | resolves `model=disputed, resolved=ForwardEarningsPower` — the registered `disp:fwd` |
| R-23, MPWR | FCFF candidate reads **exactly `51585`**, the registered arm-D value to the dollar |
| R-24.2, BKR | passes the high-signal gate via `forward_earnings_power` after its accounting-channel refusal |

It also re-derived the economics by hand rather than trusting the Gherkin: the flat path with base
100, `g = r_terminal = 300bps`, `roc = 1200bps`, `wacc = 800bps` gives
`100 × (1 − 0.03/0.12) / (0.08 − 0.03) = 1500.00`, matching the Examples table; and it confirmed the
FCFF add-back sign at `dcf_model.rs:559` against the stated double-counting rationale.

`MAX_ABSOLUTE_Z = 3.0` confirmed as the crate's only z-threshold — `price_path.rs`'s
similarly-named constants are an unrelated ATR-band feature, established **by reading, not by
grep-matching**.

### Where it looked for a fourteenth instance and found none

The brief pointed it at the four places most likely to hide one. All four discriminate:

- **`driver_resolution.rs:568-657`** (T2.7 tests) — deliberately use *positive* interest values while
  setting `.with_interest_basis(Some(true))`, which directly falsifies a sign-based regression. The
  guard provably does not key on sign.
- **`valuation_core_adapter.rs:1233-1257`** — a *sanctioned* tautology whose own doc comment says it
  must be **deleted, not weakened**, when return-on-capital becomes measurable. Not a hidden defect.
- **Gherkin Examples** — `then_absence_reason` (`cucumber.rs:597-604`) does a real `assert_eq!` on the
  reason string; `return-absent` rows are genuinely distinguished from `not_reported` and
  `out_of_policy_range`.
- **Fixture corpus** — carries both sign directions (LIN net-expense filed negative, BAC net-income
  filed positive) and `sec_normalization.rs:399-488` asserts the whole resolved year-map.

It stated plainly that this is not a clean bill of health for the codebase — only for the four areas
named.

### Boy-scout item, accepted and deferred (R-32.4)

`driver_resolution.rs:1-7` still reads *"DEPRECATED — superseded by the valuation-core crate. Still
shipping; do not extend"* while T2.7 added economically load-bearing logic to that exact module.
Sanctioned by Decision 2, but unstated at the point of the edit. Effort S; 1/8 boy-scout budget used,
0/3 blocking.

---

## Stage 7 — curator retro

Its adversarial finding — R-23's disposition citing a criterion R-18.5 forbade — is quoted and
accepted in R-33.1. The memory dispositions are in R-33.2 and the repo-doc dispositions in R-33.3.
What follows is the evidence chain it assembled, which is the part worth keeping.

### The signature defect, traced across the whole record

The curator's reconstruction of how one failure shape recurred, with the ruling that caught each:

| # | instance | ruling |
|---|---|---|
| 1 | exporter presented as instrument | R-6 |
| 2 | pre-registration keyed on extraction incidence, not published effect | R-7.1 |
| 3 | pin masked under a combined mutation | R-8.4 |
| 4 | fixture that cannot discriminate a sign convention | R-16.3 |
| 5 | control measuring T2.6-alone for a wave shipping T2.6 + T2.7 | R-17.1 |
| 6 | probe cohort disjoint from the affected population | R-18.7 |
| … | | |
| 13 | mutation-verification covering only the agreement set, never the two discriminating names | R-25.2 |
| — | **the verifier**: register marked "held" for surviving an edit, never checked for completeness | R-32.1 |

Its judgement on the last row is the one worth preserving: this is *"a different, less flattering
category"* — not a check that cannot fail, but the verifier choosing the property that was easy to
confirm.

### Its ranked candidate list, with dispositions

| # | candidate | disposition |
|---|---|---|
| 1 | sharpen "verify what an instrument measures" with the recursive clause | **taken** — memory updated |
| 2 | prefer a scope that cannot be wrong over discipline that must get scope right | **taken** — new memory entry |
| 3 | R-23 cites a forbidden criterion (the third instance asked for) | **taken** — R-33.1, annotated in place |
| 4 | pre-registration as transferable technique | **folded into #2** — one coherent fact, not two |
| 5 | isolated mutation, not combined, is what proves a test load-bearing | **taken** — new memory entry |
| 6 | verify a role's tool inventory; substitute the stronger invariant when a checkpoint is lost | **taken** — new memory entry |
| 7 | author-selected populations fail at *verification* time too | **folded into #2** as a named sub-case |
| 8 | `git checkout <ref> -- <path>` stages silently | **taken** — memory + `AGENTS.md` |
| 9 | `cargo fmt -- <files>` is not file-scoped | **taken** — `AGENTS.md` only, correctly repo-scoped |
| 10 | bare order statistics degenerate at small `n` | **taken** — memory updated (and a stale API corrected) |
| 11 | verify worktree isolation cheaply; measure before hypothesising | **taken** — `AGENTS.md`, tool-scoped with an expiry |

### What it refused to persist, and why the refusal is right

Commit hashes, LD row contents, the (D)-over-(A) choice, the Sensei's specific findings, and **the
instance tally itself**. Git history and `docs/valuation-economic-contract.md` already carry those,
and a memory duplicating a repo fact drifts the moment the code moves. On the tally: *"a fact about
this session, not a transferable number — the pattern is the durable part, not the count."*

It also declined to write up R-32.3's convergence (two lenses independently finding the same twenty
lines) as a rule, on the grounds that it is a single observation with no counterexample tested. That
restraint is the correct call and is recorded here because refusing a pleasant-sounding finding is
harder than accepting one.

### One measurement it surfaced from the record

The worktree pre-flight guard, measured directly at R-12.3: **31 seconds and ~17K tokens** with the
guard, against **27 minutes and ~1.6M tokens** for the identical discovery without it. It also noted
that the first diagnosis of *why* the isolation misbehaved — a timing race between "early" and "late"
worktrees — was itself an unmeasured, plausible-sounding story, and was wrong; the real cause was
deterministic and one `git reflog` call away (R-15).
