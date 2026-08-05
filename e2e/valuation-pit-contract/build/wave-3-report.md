# Wave 3 — One robust aggregation primitive, and a fit that honours it

Branch `valuation/wave1-integration`, worktree `.claude/worktrees/agent-abc1254dc8b9c83fd`.
Nothing committed. Four paths touched, staged explicitly, listed in §7.

---

## 1. What was built

`robust_centre` / `RobustCentre` in `valuation-core::numerics`, with `robust_mean`
re-expressed over the *same* implementation, and both call sites in the adapter
(`:280` pooled growth, `:536` trailing growth **centre and variance**) moved onto it.

The one implementation is private `fn trimmed`. Both public entry points are
projections of it:

```rust
pub fn robust_mean(sample: &[f64], max_absolute_z: f64) -> Result<f64, AbsenceReason> {
    trimmed(sample, max_absolute_z).map(|centre| centre.centre())
}

pub fn robust_centre(sample: &[f64]) -> Result<RobustCentre, AbsenceReason> {
    trimmed(sample, MAX_ABSOLUTE_Z)
}
```

That is K7 by construction rather than by promise, and it is asserted directly by
`the_robust_mean_is_the_robust_centre_with_everything_but_the_point_dropped`.

`trimmed` consumes `standardize(sample)?.outliers(z)` rather than re-comparing
scores itself. This is what makes **K1** literally true: the only expression in
the workspace that compares a z-score to a threshold is `Standardized::outliers`
(`numerics.rs:103`). `trimmed` *reads* exclusions; it does not derive them — the
same discipline T3.4 imposes on the adapter.

---

## 2. Baselines (rule R-4 — measured in this worktree, start and exit)

| suite | command | start | exit |
|---|---|---|---|
| kernel lib | `cargo test -p valuation-core --lib` | 89 passed, 0 failed | **103 passed, 0 failed** |
| kernel schema | `cargo test -p valuation-core --test schema` | 7 passed, 0 failed | **7 passed, 0 failed** |
| kernel cucumber | `cargo test -p valuation-core --test cucumber` | 6 features, 95 scenarios (95 passed), 629 steps (629 passed) | **identical: 95/95, 629/629** |
| Shell lib | `cargo test --lib` | 517 passed, 4 failed, 22 ignored | **529 passed, 4 failed, 22 ignored** |

Deltas: kernel +14, Shell +12 — exactly the 26 tests this wave adds. No suite lost a test.

**The four Shell failures are the same four names at start and at exit:**

```
cross_platform_parity::export_random20_sp500_parity_snapshot
operating_valuation::tests::durable_reported_and_holdout_cohorts_recompute_in_normal_gate
valuation_baseline::baseline_megacap_amzn_class_not_penny_intrinsic
valuation_high_signal::high_signal_screener_cohort_all_members_pass
```

Three are the protected set named in the brief and are not counted. The fourth,
`export_random20_sp500_parity_snapshot`, is **a fourth pre-existing failure I did
not expect and am flagging**: it fails because the untracked input
`.agents/workspace/tmp/random20-inputs.json` does not exist in a fresh worktree.
It is an environment artifact, present at start, unchanged at exit, and unrelated
to Wave 3. It should not be read as a regression, but the orchestrator should
know the protected set is effectively four names in a clean worktree, not three.

---

## 3. Red-then-green evidence

The standing clause: *a check must be observed to fail before it is relied on*.
Every one of the 26 new tests was run against a broken state and seen to fail.
Three mutation rounds were needed because no single mutation can fire both the
"trimming works" family and the "clean input is left alone" family — a mutation
that breaks one satisfies the other.

Pristine files were checksummed before mutating and restored byte-for-byte after
(`md5sum` verified: `f6e221ca…` numerics, `6f4f24ed…` adapter, `741d2f1d…` lib).

### Round A — the actual defect reinstated

`trimmed` reduced to the pre-wave behaviour: no trimming, naked mean, naked
variance over the whole sample, no refusal.

```
test result: FAILED. 15 passed; 12 failed   (numerics::)
test result: FAILED. 20 passed;  8 failed   (valuation_core_adapter::)
```

Failed (18 new + 2 pre-existing):

| test | id |
|---|---|
| `the_robust_centre_reports_the_centre_of_what_it_kept` | W3-P01 |
| `the_contaminated_observation_is_counted_as_discarded` | W3-P01 |
| `the_discarded_observation_is_named_by_its_position_in_the_input` | W3-P01 |
| `the_width_of_the_centre_is_the_width_of_what_the_centre_kept` | T3.8 / K6 |
| `the_retained_count_is_what_survived_rather_than_what_arrived` | T3.8 |
| `a_sample_too_small_to_have_a_spread_has_no_robust_centre` | W3-N01 |
| `a_non_finite_observation_refuses_rather_than_poisoning_the_centre` | W3-N02 |
| `a_sample_whose_middle_has_no_width_has_no_robust_centre` | W3-E01 / K10 |
| `a_sample_trimmed_exactly_to_three_still_reports_a_centre` | W3-E02 |
| `a_sample_trimmed_below_three_refuses_rather_than_reporting_a_pair` | W3-E03 |
| `a_planted_extreme_growth_year_is_excluded_from_the_pooled_centre` | W3-P04 |
| `an_excluded_growth_year_takes_both_of_its_pairs_out_of_the_fit` | W3-P04 / K4 |
| `the_cost_in_pairs_is_reported_separately_from_the_count_of_bad_years` | W3-P04 / T3.5 |
| `an_excluded_year_at_the_edge_of_a_series_costs_only_the_one_pair_it_touched` | W3-P05 |
| `an_issuer_whose_whole_series_is_excluded_contributes_no_pairs` | W3-E04 |
| `a_broken_only_pair_is_not_bridged_across_the_gap` | W3-E05 / K4 |
| `the_trailing_channel_counts_the_years_it_kept_not_the_years_it_saw` | T3.8 / K9 |
| `a_contaminated_growth_year_does_not_widen_the_trailing_channel` | T3.8 / K6 |
| `a_contaminated_observation_does_not_move_the_robust_centre` | **pre-existing** |
| `trimming_below_a_usable_sample_refuses_rather_than_falling_back` | **pre-existing** |

The last two matter as evidence for **W3-R01 / K7**: the pre-existing `robust_mean`
tests fail under the reinstated defect, which proves they are still genuinely
guarding `robust_mean`'s behaviour through the rewrite rather than passing
vacuously against a moved implementation.

### Round B — clean-input and refusal pins

Three mutations, none of which round A can express: the threshold moved to `0.5`
at the `robust_centre` call site; absence fabricated as `0.0` with
`f64::MIN_POSITIVE` width in the trailing channel; the whole fit refusing when any
exclusion exists.

```
test result: FAILED. 17 passed; 10 failed   (numerics::)
test result: FAILED. 17 passed; 11 failed   (valuation_core_adapter::)
```

Newly red in this round (the pins round A could not reach):

| test | id |
|---|---|
| `a_sample_with_nothing_out_of_place_discards_nothing` | W3-P02 |
| `a_sample_with_nothing_out_of_place_centres_where_the_plain_mean_would` | W3-P02 |
| `the_robust_mean_is_the_robust_centre_with_everything_but_the_point_dropped` | W3-P03 / K7 |
| `no_five_observation_sample_can_be_trimmed_below_three` | W3-E03 (see §6) |
| `a_clean_cohort_fits_the_persistence_the_plain_mean_fitted` | W3-R02 |
| `a_flat_growth_history_leaves_the_trailing_channel_absent` | K10 / absence rule |
| `the_fit_still_resolves_when_one_issuer_is_entirely_excluded` | W3-E04 |

### Round B2 — one pin was masked, and that is worth recording

`a_clean_cohort_discards_nothing` **passed** in round B. Not because it is weak:
the whole-fit-refusal mutation returns `None` *before* `growth_pooled_discarded`
is written, so the counter stayed at `0` and the assertion held. One mutation
masked another. Isolating the threshold mutation alone:

```
test valuation_core_adapter::tests::a_clean_cohort_discards_nothing ... FAILED
test valuation_core_adapter::tests::a_clean_cohort_fits_the_persistence_the_plain_mean_fitted ... FAILED
test result: FAILED. 0 passed; 2 failed; 553 filtered out
```

I am reporting this rather than quietly counting round B as sufficient, because a
combined mutation round that shows "everything went red" is exactly the shape of
evidence that hides a test which never fired.

**Union of the three rounds = all 26 new tests observed failing.**

### Green

Restored from checksummed pristine copies, all four suites re-run — the exit
column of §2. `103 passed`, `7 passed`, `95/629`, `529 passed / 4 pre-existing`.

---

## 4. Acceptance checks, with actual output

**K1 — exactly one place filters a sample by z-score.** The only comparison is
`numerics.rs:103`, inside `Standardized::outliers`:

```
valuation-core/src/numerics.rs:103:            .filter(|(_, score)| score.abs() > max_absolute_z)
```

Every other site passes a threshold *into* it; none re-implements the comparison.

**K2 / W3-N03 — the threshold does not move, and no call site passes another value.**

```
valuation-core/src/numerics.rs:29:pub const MAX_ABSOLUTE_Z: f64 = 3.0;
```

Every call site in the workspace passes `MAX_ABSOLUTE_Z` and nothing else:
`valuation_probes.rs:465`, `:467`, `numerics.rs:261` (via `robust_centre`), and
the three test sites `:473`, `:566`, `:647`. No literal threshold anywhere.

**K3 — `robust_centre` takes no threshold parameter.**

```
260:pub fn robust_centre(sample: &[f64]) -> Result<RobustCentre, AbsenceReason> {
```

**K4 — an excluded observation leaves every pair it touched, and no pair bridges
the gap.** Enforced by `GrowthKey { issuer, step }` and asserted by
`an_excluded_growth_year_takes_both_of_its_pairs_out_of_the_fit`,
`an_excluded_year_at_the_edge_of_a_series_costs_only_the_one_pair_it_touched`,
and `a_broken_only_pair_is_not_bridged_across_the_gap`. All three red in round A.

**K5 — a retained count below three refuses; one or two is unreachable.**
`trimmed` returns `Err(InsufficientObservations)` when `kept.len() < 3`, and
`no_five_observation_sample_can_be_trimmed_below_three` pins the unreachability.

**K6 — centre and variance derive from the same retained set.** `RobustCentre` is
constructed in exactly one place (`numerics.rs:296`), from one `kept` vector. There
is no way to obtain a centre from one subset and a width from another.

**K7 — `robust_mean` signature and behaviour unchanged.** Signature unchanged;
both pre-existing `robust_mean` tests unmodified and still passing (and observed
failing in round A).

**K8 / W3-R03 — `valuation-core`'s dependency list is still empty.**

```toml
[dependencies]

[dev-dependencies]
cucumber = "0.21"
tokio = { version = "1", features = ["macros", "rt-multi-thread"] }
toml = "0.8"
```

Unchanged by this wave. No dependency added; `Cargo.toml` is not among the files I
touched.

**K9 — no naked `mean(` or `sample_variance(` at `:280` or `:536`, and
`observations` reports the retained count.** Both sites now call `robust_centre`
(`:334`, `:635`). The trailing channel reports `centre.retained() as u32`.

The `mean` / `sample_variance` helpers still exist and still have callers. Each
surviving caller is in the audit table with a reason:

- `:852` — the centre *inside* `sample_variance`. Definitional, not a summary of a
  measured series.
- `:880` — de-meaning inside the through-origin regression. Inherits T3.4's
  exclusions; trimming a regression's own residuals is a different estimator and
  would need its own pre-registration.
- `:417`, `:730`, `:736` — peer-group *dispersions*, not centres of a measured
  series.

**K10 — a zero-spread sample refuses rather than returning a zero-variance
centre.** `a_sample_whose_middle_has_no_width_has_no_robust_centre` and
`a_flat_growth_history_leaves_the_trailing_channel_absent`. Zero variance is
infinite precision under inverse-variance fusion, so one degenerate history would
otherwise dominate a posterior outright.

**Formatting.** `rustfmt --edition 2021 --check` on the three source files: clean,
no output.

**T3.7 / R4 — the refusal-rate change, measured rather than assumed.** On the
pinned cohort:

| quantity | before | after |
|---|---|---|
| growth persistence | 0.1709 | **0.2417** |
| fade rate `k` | 1.7666 | **1.4199** |
| pairs in the fit | 231 | **197** |
| pooled observations discarded | — | **22** |
| pairs dropped | — | **34** |
| published / refused | 18 / 2 | **18 / 2** |

**The refusal-rate change is zero.** The same two names refuse before and after
(MH and BWMN, `evidence / not_reported` — both refuse for missing evidence, not
for anything trimming did), and every published p50 is identical to the cent. The
R4 risk — that `standardize` would refuse a nearly-flat history where the naked
mean published — did not materialize on this cohort. Caveats in §6.

---

## 5. Why nothing published moved

`valuation_core_adapter::value()` and its whole downstream subtree are test-only
in production, as the orchestrator proved by compiler. Wave 3 cannot move a
published number, and the measured refusal-rate change of zero is consistent with
that. The persistence and fade figures above *did* move; they are simply not
reachable from any published output yet.

Independently of reachability, uncontaminated behaviour is bit-identical by
construction: `variance_of_centre` is `var(kept)/kept.len()`, which is exactly the
old `sample_variance/n` when nothing is trimmed. The clean-cohort persistence
literal is identical before and after — pinned as
`PERSISTENCE_BEFORE_WAVE_3 = 0.475_680_263_352_406_93`.

---

## 6. Not completed, deviated, or flagged

**1. W3-E03 as written is mathematically impossible. Implemented in its reachable
form; not silently adapted.** The plan asks for *"a five-observation sample where
two survive"*. No such sample exists. MAD is the *middle* deviation, so at `n = 5`
at most the two largest deviations can exceed any positive multiple of it — three
observations always survive. It also contradicts the plan's own K5, which calls a
retained count of two *unreachable*: a test asserting a state that K5 declares
unreachable cannot both pass and leave K5 true.

Implemented instead:
- `a_sample_trimmed_below_three_refuses_rather_than_reporting_a_pair` — the
  reachable form of the same intent (3 observations, one extreme → refuses).
- `no_five_observation_sample_can_be_trimmed_below_three` — the impossibility
  itself, asserted over three adversarial five-observation samples, so the claim
  is checked rather than argued in a comment.

This is a **plan-versus-mathematics contradiction**, which the brief says to
report rather than decide. Recorded here and in the audit doc. Nothing was
weakened: the refusal is stricter than the plan's version, not looser.

**2. The pinned cohort has 20 members, not the 28 the plan states.**
`baseline_cohort_2026-07-30.json` contains 20 entries, all `status == "ok"`, none
quarantined. Every count in §4 is over those 20. If a 28-name cohort was intended,
the fixture does not contain it and the T3.7 numbers are over the smaller set.

**3. The T3.7 diagnostic fixture is stale.** The plan flags this itself. The
measured refusal-rate change of zero is a statement about the pinned fixture, not
about live filings.

**4. Two pre-existing clippy errors in `numerics.rs`, deliberately not fixed.**
`:117` `manual_is_multiple_of` and `:149` `neg_cmp_op_on_partial_ord`. Proved
pre-existing by checking out the HEAD versions of all three owned files, running
clippy (same two errors), and restoring. I did not fix `:149`: `!(scale > 0.0)` is
a **refusal path**, and rewriting it as `scale <= 0.0` changes NaN behaviour —
`!(NaN > 0.0)` is `true` (refuse), `NaN <= 0.0` is `false` (proceed). The lint's
suggested rewrite would silently open a NaN path through a refusal. Left as is,
flagged for a wave that owns the file's lint status.

**5. `AGENTS.md` deliberately not edited.** Wave 4 owns it. The aggregation rule
quoted in the audit doc is quoted from `AGENTS.md:347-361` as it stands, not
rewritten.

**6. `variance_of_centre` has one consumer today, and a known future hazard.**
Recorded as an LD-5 register row in the audit doc rather than left implicit: when
a forward channel first fuses against the trailing channel, a clean centre paired
with a contaminated width means a *dirtier* sample can earn a *larger* weight
(monotone-in-contamination weighting bias). Trigger: the first forward channel.
Detector: **human review checkpoint — there is no mechanical detector**, which is
stated plainly rather than papered over with a test that would not catch it.

**7. Deferred, not run by me (>10s or integration).** Full workspace test,
cucumber under release, the live-QA cohort run. Per the brief's live-QA exemption
these belong to the orchestrator/QA stage. The `cargo test --lib` Shell run *was*
run (16.85s) because the four-failure baseline could not be established without it.

**Nothing was left "for later" that is not on this list.** No TODO encoding
required correctness work was introduced, no test or threshold was moved, no
refusal path was relaxed, and no absence was turned into a zero or a floor.

---

## 7. Files touched (staged explicitly; nothing committed)

```
M apps/windows/src-tauri/src/valuation_core_adapter.rs
M apps/windows/src-tauri/valuation-core/src/lib.rs
M apps/windows/src-tauri/valuation-core/src/numerics.rs
? docs/valuation-aggregation-audit.md          (new)
```

`git add` was issued path by path. `git add -A` was never used. One fixture,
`tests/fixtures/valuation/high_signal_screener_observation_2026-08-02.json`, is
rewritten as a side effect of `cargo test --lib`; it was restored with
`git checkout --` after every run and never staged.

---

## 8. Baseline reconciliation (orchestrator query, R-8 follow-up)

**Finding: the plan's §4 Shell baseline of 520 passing is wrong. The correct
figure for a clean shared checkout at `4d1e916` is 518. The shell lib at that
commit contains exactly 543 tests, and 520 + 3 + 22 = 545 has never existed on
this branch.**

### The three triples

| tree | passed | failed | ignored | total |
|---|---|---|---|---|
| this worktree, pre-wave | 517 | 4 | 22 | **543** |
| shared checkout, same commit, clean | 518 | 3 | 22 | **543** |
| plan §4 as recorded | 520 | 3 | 22 | **545** |

There are two *independent* discrepancies, and conflating them is what made this
look unexplainable:

1. **±1, parity attribution.** `export_random20_sp500_parity_snapshot` passes in
   the shared checkout and fails in every worktree. This moves one test between
   `passed` and `failed` and **leaves the total at 543**. Already owned by the
   orchestrator.
2. **+2, in the total itself.** The plan's total is 545. This is not an
   attribution difference and the parity test cannot explain it, which is exactly
   the observation that prompted the query.

### Total test count, established two independent ways

`cargo test --lib -- --list` in this worktree: **555 tests, 0 benchmarks**.
My staged diff adds 12 `#[test]` to the shell src and removes 0, so pre-wave =
**543**. Cross-check: 555 = 529 passed + 4 failed + 22 ignored, with
`0 filtered out`.

Independently, `#[test]` attributes in `apps/windows/src-tauri/src` by commit:

```
4d1e916  543      <- my HEAD, == valuation/wave1-integration tip
3d01d5a  543
e4e152e  543
131e72b  543
2166c5e  542
32b5c96  336      <- main
```

The count has been 542 then 543 across this branch's recent history. **545 never
occurred.**

### Ruled out as the source of the two

- **Branch drift** — `git rev-parse HEAD` = `4d1e9167a80…` = the
  `valuation/wave1-integration` tip. My worktree is not behind.
- **Filtering** — the run reports `0 filtered out`.
- **Newly ignored tests** — ignored is 22 at start and at exit; my staged diff
  adds and removes zero `#[ignore]` attributes; none of the 22 ignored names is
  in a file I touched.
- **Feature-gated tests** — zero occurrences of `cfg(feature` in the shell `src`.
- **A second workspace member being included** — `members = ["valuation-core"]`
  only, and `valuation-core` had 89 lib tests pre-wave, not 2.
- **Uncommitted `.rs` in the shared checkout** — the session-start status showed
  only `AGENTS.md`, two `.memlog.md`, `project-context.md`, one fixture `.json`,
  and untracked `.grok/skills/`. No Rust source. Zero tests contributed.

### What I cannot distinguish from inside isolation

Two candidates remain, and I will not guess between them:

- **The plan's baseline was measured inside a builder worktree with work already
  in it.** Both other worktrees sit at `4d1e916` with their own working trees. A
  builder two tests in would produce 520 exactly. This is the baseline
  instability §4.2 names as a Stage 4 blocker, caught in the act.
- **Transcription** — 518 recorded as 520.

The orchestrator can separate these by re-measuring the shared checkout at
`4d1e916` with a clean tree. If it reports 518 / 3 / 22, the plan figure is
simply wrong; if it reports 520, then two tests exist that are not in the
committed tree and that is the more serious finding.

### Evidence provenance, stated honestly

My **exit** line is verbatim from the run:

```
test result: FAILED. 529 passed; 4 failed; 22 ignored; 0 measured; 0 filtered out; finished in 16.85s
```

My **start** triple (517 / 4 / 22) is a *recorded* triple; I did not save the raw
summary string to a file at the time. I am flagging that rather than presenting a
reconstructed string as if it were captured output. It is confirmed by two
independent routes — 555 total minus my 12 added tests, and the 543 `#[test]`
count at `4d1e916` — which agree exactly. I did not re-measure it by reverting my
work, because the query said to change nothing; I can produce a captured start
line on request.

The four failing names at start are the four in §2, identical to exit.

### Recommendation

Correct plan §4 to **518 passing / 3 failing / 22 ignored (543 total)** for a
clean shared checkout, and note that any worktree reads **517 / 4 / 22** until
`random20-inputs.json` is tracked or the parity test is taught to skip when its
input is absent.
