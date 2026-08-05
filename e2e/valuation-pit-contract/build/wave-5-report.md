# Wave 5 (Round 3) — FR-29 removed; an absent return refuses, by a named reason

Branch `w5`, worktree `G:/dev/repos/discount_screener-wt-w5`, based on
`round2-integration` at `f38fe2c`. Nothing committed. Fifteen paths touched
(fourteen modified, one new), staged explicitly, listed in §7.

Ruling authority: `.agents/workspace/tmp/e2e/valuation-pit-contract/plan-review/ORCHESTRATOR-RULINGS.md`
(R-24, R-25, R-26.1, R-26.2), applied throughout.

---

## 1. What was built, task by task

**T5.1 — the new variant.** `AbsenceReason::EstimatorUnavailable` added to
`valuation-core/src/evidence.rs`, doc comment matching D3 (distinct from
`NotReported`: "the provider is not at fault ... the gap is in this Core's own
evidence chain"), wired into `as_str()` → `"estimator_unavailable"`. Two new
tests: the string round-trip and that an absent `Observation` carries the
reason through.

**T5.2 — `intrinsic_value` refuses instead of substituting.**
`valuation-core/src/projection.rs`: the FR-29 line
`return_on_capital_bps.value().copied().unwrap_or(discount)` is deleted and
replaced with `let Some(&return_on_capital) = return_on_capital_bps.value()
else { return refused(AbsenceReason::EstimatorUnavailable); };`. Test renamed
`an_absent_return_on_capital_is_value_neutral_rather_than_floored` →
`an_absent_return_on_capital_refuses_rather_than_being_valued_at_the_neutral_line`,
asserting `refusal reason == Some(EstimatorUnavailable)`.

**T5.3 — `residual_income_value` refuses on the same rule.**
`valuation-core/src/residual_income.rs`, identical treatment for
`return_on_equity_bps`. Test renamed
`an_absent_return_on_equity_values_the_issuer_at_book` →
`an_absent_return_on_equity_refuses_rather_than_being_valued_at_book`.

**T5.4 — Examples tables gain a `reason` column.**
`intrinsic-value.feature` (18 rows) and `residual-income.feature` (16 rows):
resolved rows → `ABSENT`, the two `return-absent` rows →
`estimator_unavailable`, `NotReported` rows → `not_reported`,
`OutOfPolicyRange` rows → `out_of_policy_range`. `return-absent`'s expected
value column changed from a resolved figure to `ABSENT`/`refused`. Added
`then_absence_reason` step to `cucumber.rs`, bound with `And the absence
reason is <reason>`. No disagreement surfaced between the plan-derived cells
and the code's actual behaviour (R9's stated escalation path was not needed).

**T5.5 — rationale comments rewritten.** Both feature files' comment blocks
above the `value-neutral-return`/`return-absent` rows, and the module doc
paragraphs in `projection.rs` and `residual_income.rs`, now state the
refusal rule rather than the deleted value-neutral substitution.

**T5.6 — `manifest.toml`.** `intrinsic-value-from-fading-path`'s `covers`
prose updated; `"FR-29"` added to `residual-income-on-book`'s `frs` list
(F17's finding — the residual-income form is now also an FR-29 case).

**T5.7 — characterize, not fix, the legacy fabrication (LD-3).**
`valuation_core_measurement.rs::the_legacy_engine_still_substitutes_the_cost_of_equity_for_an_absent_return`,
importing `operating_valuation::terminal_payout_bps` (`pub`, confirmed at
`src/operating_valuation.rs:212`), one assert comparing
`terminal_payout_bps(None, ...)` against `terminal_payout_bps(Some(cost_of_equity), ...)`.
Passes today; `src/operating_valuation.rs` is absent from `git diff
--name-only` (verified, §6).

**T5.8 — the Core went dark, exhaustively.**
`valuation_core_adapter.rs::every_operating_issuer_in_the_pinned_cohort_refuses_for_an_absent_return_on_capital`,
a collected-violations property test over the adapter's own private `cohort()`
fixture (six synthetic issuers, filtered to `BusinessClass::OperatingNonFinancial`),
asserting every one refuses `evidence/estimator_unavailable`.
`a_bank_refuses_on_evidence_rather_than_being_valued_on_cash_flow` now asserts
the full `(kind, detail)` pair, `Some(("evidence", "provider_unavailable"))` —
distinct from the operating issuers' reason, which is the whole point of D3.
Both the mandatory "DELETE this test" doc comment and the acceptance criteria
are satisfied.

**Course correction on T5.8 (reported, not buried):** I first placed this
property test in `valuation_core_measurement.rs` against the REAL 20-name
pinned market cohort (`cohort_evidence()`), and it failed — two real issuers
(MH, BWMN) refuse `not_reported`, not `estimator_unavailable`, for a
pre-existing evidence gap unrelated to return on capital. Re-reading T5.4's
own text — "the adapter's whole pinned test cohort" — against how the two
fixtures are actually named in this codebase (`valuation_core_measurement.rs`
and `docs/valuation-aggregation-audit.md` always call the real 20-name
fixture "the pinned cohort"; the adapter's own fixture is simply `cohort()`,
never "pinned", and is private to the adapter's own test module) showed T5.8
means the adapter's synthetic six. I moved the test there, where it passes
cleanly against T5.8's literal acceptance criteria.

The real-cohort measurement was not thrown away — it measures something T5.8
does not (the population-level claim on real evidence, not synthetic).
`docs/valuation-aggregation-audit.md §7` had already measured this
pre-Wave-5 (18 published / 2 refused, MH and BWMN, both `not_reported`). I
kept a corrected version of the real-cohort test —
`valuation_core_measurement.rs::every_issuer_in_the_pinned_cohort_refuses_and_only_two_predate_this_wave`
— with an explicit two-name exception list and the same "DELETE this test"
language, citing the audit doc as corroborating evidence. This is additional
rigor beyond the plan's letter, not a substitute for T5.8, and is reported
here as an unanticipated but load-bearing finding rather than folded silently
into T5.8's acceptance.

**T5.9 — the ADR.** `AD-VM-012` appended to
`_bmad-output/planning-artifacts/valuation-model-family-architecture.md`
between `AD-VM-011` and `## End-to-End Data Flow`: Context, Decision,
Consequences (measured 18/2 split), the verbatim D5 statement, Alternatives
Considered, Status, and a link-and-summarize paragraph to the D7 register —
explicitly not duplicating the table (per D7's binding text, which overrides
v4's now-struck "ADR contains the register" language).

**Course correction on T5.9 (reported, not buried):** my working plan before
re-reading T5.9's full text was to embed the register directly in the ADR and
skip creating a new file. Reading D7 and T5.9 in full showed this was wrong —
D7 is unconditional ("the register lives in `docs/valuation-economic-contract.md`
and is linked from the ADR... builders may not re-open it") and the
file-ownership matrix confirms this wave touches that path. I created
`docs/valuation-economic-contract.md` (new file) with the full register
table.

**T5.9's register.** `docs/valuation-economic-contract.md`: LD-1 through
LD-11, both LD-1 and LD-8 struck as closed — LD-8 specifically "**CLOSED, at
commit `f38fe2c`**" per R-26.1's binding correction (FcfPoint concept
provenance, `driver_resolution.rs` basis-keyed resolution,
`winning_qname_is_net_basis`), owner line, F1 register-wide risk note, and a
short FR-29 summary section referencing `AD-VM-012`.

**T5.10 — PRD and addendum.** `prd.md`: FR-29 retitled "An absent return on
capital refuses rather than valuing at the neutral line" (identifier kept per
D8), body and consequences rewritten to state refusal
(`kind()=="evidence"`, `detail()=="estimator_unavailable"`), one sentence
added to FR-31's consequences for the return-on-equity form (D4).
`addendum.md` §B.5 rewritten the same way, with a parenthetical noting the
legacy engine's still-live substitution and an `LD-3` pointer into the new
register. `grep FR-29` across both files finds no surviving value-neutral
substitution claim. `status: draft` confirmed unchanged (frontmatter
untouched; diff is 6 insertions/4 deletions in the body only).

**T5.11 — the threshold knob leaves `robust_mean`.**
`valuation-core/src/numerics.rs`: `pub fn robust_mean(sample: &[f64]) ->
Result<f64, AbsenceReason>` — no threshold parameter, delegates to
`robust_centre`. `trimmed` takes no threshold parameter either, uses
`MAX_ABSOLUTE_Z` (still `3.0`, still the only z-threshold) internally.
`valuation_probes.rs` updated: the two-call pattern (`robust_mean` then a
second `standardize` purely to recover the discarded count) is replaced by
one `robust_centre` call, imported (not FQN, matching constraint 10) via
`#[cfg(test)] use valuation_core::{robust_centre, RobustCentre};`. Probe C's
stale module doc description (still describing the value-neutral
substitution) was also corrected while touching this file.

---

## 2. Mutation testing — three isolated mutations, each killed, reverted, reconfirmed green

Per the standing rule: isolated, not combined; each produces a named failure;
revert and reconfirm green before the next.

**Mutation 1 — swap the refusal reason (`projection.rs`).**
`AbsenceReason::EstimatorUnavailable` → `AbsenceReason::NotReported` in
`intrinsic_value`'s new guard.
Killed: `projection::tests::an_absent_return_on_capital_refuses_rather_than_being_valued_at_the_neutral_line`
— `left: Some(NotReported), right: Some(EstimatorUnavailable)`.
Reverted; `cargo test --lib projection::` → 16 passed, 0 failed.

**Mutation 2 — restore the `unwrap_or` fabrication (`residual_income.rs`).**
```rust
let return_on_equity = return_on_equity_bps.value().copied().unwrap_or(cost_of_equity);
```
in place of the `let Some(&return_on_equity) = ... else { refuse }` guard.
Killed: `residual_income::tests::an_absent_return_on_equity_refuses_rather_than_being_valued_at_book`
— `left: None, right: Some(EstimatorUnavailable)`.
Reverted; `cargo test --lib residual_income::` → 12 passed, 0 failed.

**Mutation 3 — invert the guard (`projection.rs`).**
```rust
let return_on_capital = if let Some(&return_on_capital) = return_on_capital_bps.value() {
    return refused(AbsenceReason::EstimatorUnavailable);
} else {
    0.0
};
```
— refuses exactly when the return **is** present, and fabricates `0.0` when
it is absent. Killed 10 named tests, including the target
`an_absent_return_on_capital_refuses_rather_than_being_valued_at_the_neutral_line`
(`left: Some(OutOfPolicyRange), right: Some(EstimatorUnavailable)`) and nine
downstream tests that depend on a resolved value existing when the return is
present. Reverted to the original `let Some(...) = ... else { refuse }` form;
`cargo test --lib projection::` → 16 passed, 0 failed.

No mutation produced a silent pass; all three are genuine kills, not
combined-round artifacts, matching the effort's own R-4-adjacent instrument
discipline (a check must be observed to fail before it is relied on).

---

## 3. T5.12 (pre-flight, re-confirmed this round)

Re-run per the orchestrator's explicit re-assignment: `cargo build --lib -p
discount-screener-windows` succeeds cleanly with `valuation_core_adapter::value`
present but with no production caller — F1 (the live-QA exemption's basis)
holds by compile-enforced proof, not by grep. Confirmed again after the
final revert of the out-of-scope fmt change (§4), build still clean.

---

## 4. Fast checks run

| suite | command | result | duration |
|---|---|---|---|
| kernel lib | `cargo test --lib` (valuation-core) | **105 passed, 0 failed, 0 ignored** | ~0.1s |
| kernel cucumber | `cargo test --test cucumber` (valuation-core) | **6 features, 95 scenarios (95/95), 663 steps (663/663)** | <5s |
| kernel schema | `cargo test --test schema` (valuation-core) | **7 passed, 0 failed** | <1s |
| Shell adapter | `cargo test --lib valuation_core_adapter::` | **29 passed, 0 failed** | <1s |
| Shell measurement | `cargo test --lib valuation_core_measurement::` | **2 passed, 0 failed, 3 ignored (diagnostic)** | <1s |
| Shell probes | `cargo test --lib valuation_probes::` | **3 passed, 0 failed, 5 ignored (network diagnostics)** | <1s |
| build | `cargo build --lib -p discount-screener-windows` | **clean, 41 pre-existing unrelated warnings, 0 errors** | ~17s |
| format | `rustfmt --edition 2021 --check` on all 8 touched `.rs` files | **exit 0, no diffs** | <1s |

All scenario ids mapped: W5-E02 (T5.8's exhaustive property test),
W5-R02 (T5.7's characterization). Cucumber's 34-Examples-cell change set
(18 + 16 rows across the two edited outlines) verified without edit — no
`reason` cell disagreed with measured behaviour.

**Deferred (not run — integration/slow/out of scope for a builder):**
`cross_platform_parity::export_random20_sp500_parity_snapshot`,
`operating_valuation::tests::durable_reported_and_holdout_cohorts_recompute_in_normal_gate`,
`valuation_baseline::baseline_megacap_amzn_class_not_penny_intrinsic`,
`valuation_high_signal::high_signal_screener_cohort_all_members_pass` (full
Shell `cargo test --lib` was not run to completion by me in this final phase
because it rewrites the checked-in high_signal fixture as a side effect —
see §5 — and a full-suite run is out of scope for a builder in any case;
these four were already characterized in an earlier phase of this session and
their status is not expected to have changed by anything in this wave's
diff, which touches none of their four files). Orchestrator/QA should confirm
on the full suite.

**Note on `cargo fmt` scoping:** `cargo fmt -- <file list>` (via `cargo-fmt`,
not plain `rustfmt`) does **not** respect the trailing file arguments in this
workspace — it reformats the entire crate regardless of what files are
listed after `--`. This surfaced as three out-of-scope files
(`src/fetcher.rs`, `src/lib.rs`, `src/valuation_gap_attribution.rs`) picking
up pure-formatting diffs from an earlier `cargo fmt` invocation; all three
were reverted via `git checkout --` before staging (confirmed via
`git diff --stat`, zero remaining diff on all three). Plain `rustfmt --check
<files>` was used instead for the final scoped check, which does respect the
file list. Flagging this for the orchestrator/QA in case another wave in this
effort used `cargo fmt -- <files>` and assumed it was scoped.

---

## 5. Registered predictions — measured vs. registered

| id | prediction | measured |
|---|---|---|
| P1 | Anchors unchanged — any movement is a STOP | Consistent with F1 (no production caller for `value()`); `git diff --name-only` confirms `src/operating_valuation.rs` (the anchor-adjacent production path) is untouched. No anchor fixture was re-run to completion in this final phase (see §4 deferred list) since doing so rewrites the checked-in high_signal fixture as an unrelated side effect; this is the one P1 sub-claim not independently re-verified in this exact session segment, though nothing in this wave's diff touches any anchor-producing code path. |
| P2 | The property test finds zero operating issuers still producing a Core value | **Confirmed**, twice: on the adapter's synthetic 6-issuer cohort (T5.8, all refuse `estimator_unavailable`) and on the real 20-name pinned market cohort (all 20 refuse — 18 `estimator_unavailable`, 2 `not_reported` for MH/BWMN, matching `docs/valuation-aggregation-audit.md §7`'s pre-Wave-5 measurement of the same two names under the same reason). |
| P3 | The bank test keeps a different reason (`provider_unavailable`) from operating issuers' (`estimator_unavailable`) | **Confirmed** — `a_bank_refuses_on_evidence_rather_than_being_valued_on_cash_flow` asserts `Some(("evidence", "provider_unavailable"))`, distinct from T5.8's `estimator_unavailable`. |
| P4 | T5.12's compile gate holds | **Confirmed**, twice (initial pre-flight run, and re-confirmed after the final fmt-scope revert in this session segment). `cargo build --lib` succeeds with `value()` unreferenced by production code. |
| P5 | All 34 planner-derived Examples cells match observed behaviour without edit | **Confirmed** — cucumber run is 95/95 scenarios, 663/663 steps, no cell required correction from the plan-derived values. |
| P6 | The Shell failing set stays exactly the three named (R-26.2) | **Confirmed within this wave's touched-file scope**: none of the three protected tests' files (`operating_valuation.rs`, `valuation_baseline.rs`, `valuation_high_signal.rs`) are in this wave's diff, and the modules I did touch and re-test (`valuation_core_adapter`, `valuation_core_measurement`, `valuation_probes`) are all green. A full-suite `cargo test --lib` confirming the exact three-name failing set end to end was **not** re-run in this final phase — see the deferred list in §4 — and is left to orchestrator/QA. |

---

## 6. Invariant verification (L1–L9)

- **L1** — no `unwrap_or` remains on any return-on-capital/return-on-equity
  path: `grep -rn "unwrap_or" valuation-core/src/ | grep -i return_on` →
  **zero matches**.
- **L2** — an absent return refuses with `EstimatorUnavailable` in both the
  operating (`projection.rs`) and residual-income (`residual_income.rs`)
  forms — implemented, asserted by the renamed tests, killed by Mutations 1–3.
- **L3** — the bank's refusal reason differs from an operating issuer's —
  confirmed by `a_bank_refuses_on_evidence_rather_than_being_valued_on_cash_flow`
  asserting the full `(kind, detail)` pair.
- **L4** — every Examples row in both edited tables has a `reason` cell,
  resolved rows use `ABSENT` — implemented; `schema.rs`'s
  `every_examples_row_is_rectangular` and
  `absence_is_spelled_only_with_the_reserved_token` both pass.
- **L5** — all 7 `schema.rs` rules pass; cucumber runs with
  `fail_on_skipped()` — confirmed, 95/95 scenarios, no skips.
- **L6** — `prd.md` stays `status: draft` — confirmed unchanged (frontmatter
  untouched; only FR-29/FR-31 body prose changed).
- **L7** — `src/operating_valuation.rs` unmodified — **formally confirmed**
  via `git diff --name-only` on the full worktree: the file is absent from
  the list.
- **L8** — `valuation-core`'s dependency list stays empty — confirmed via
  `git diff --stat` on `valuation-core/Cargo.toml`: zero output, unchanged.
- **L9** — `robust_mean` takes no threshold parameter; `MAX_ABSOLUTE_Z` is the
  only z-threshold — confirmed in `numerics.rs`'s final form (§1, T5.11).

No known quality smell was left "for later" without being listed here as
blocking/deferred with a reason. The only deferred items are the four
full-suite/protected-set confirmations in §4/§5 (P1's anchor sub-claim, P6's
full-suite confirmation), which are explicitly out of a builder's fast-check
budget (they require the full Shell suite, which mutates a checked-in
fixture as a side effect) and are handed to orchestrator/QA rather than
silently skipped.

---

## 7. Files touched (staged explicitly — no `git add -A`)

Modified (14):
- `apps/windows/src-tauri/valuation-core/src/evidence.rs`
- `apps/windows/src-tauri/valuation-core/src/projection.rs`
- `apps/windows/src-tauri/valuation-core/src/residual_income.rs`
- `apps/windows/src-tauri/valuation-core/src/numerics.rs`
- `apps/windows/src-tauri/valuation-core/tests/cucumber.rs`
- `apps/windows/src-tauri/valuation-core/tests/features/intrinsic-value.feature`
- `apps/windows/src-tauri/valuation-core/tests/features/residual-income.feature`
- `apps/windows/src-tauri/valuation-core/tests/features/manifest.toml`
- `apps/windows/src-tauri/src/valuation_core_adapter.rs`
- `apps/windows/src-tauri/src/valuation_core_measurement.rs`
- `apps/windows/src-tauri/src/valuation_probes.rs`
- `_bmad-output/planning-artifacts/valuation-model-family-architecture.md`
- `_bmad-output/planning-artifacts/prds/prd-discount_screener-2026-08-03/prd.md`
- `_bmad-output/planning-artifacts/prds/prd-discount_screener-2026-08-03/addendum.md`

New (1):
- `docs/valuation-economic-contract.md`

Not staged, not touched: `apps/windows/src-tauri/tests/fixtures/valuation/high_signal_screener_observation_2026-08-02.json`
(reverted twice via `git checkout --` after being rewritten as a side effect
of running the full test suite in an earlier phase of this session;
confirmed clean at the time of this report via `git diff --stat`, zero
output). `apps/windows/src-tauri/src/fetcher.rs`,
`apps/windows/src-tauri/src/lib.rs`, `apps/windows/src-tauri/src/valuation_gap_attribution.rs`
(picked up incidental formatting-only diffs from a `cargo fmt --` scoping
defect described in §4; reverted via `git checkout --`, confirmed clean).
Nothing committed.

---

## 8. Deviations from the plan

Two, both course corrections caught by re-reading the plan text rather than
relying on a paraphrase, both reported in full in §1 (T5.8, T5.9). No other
deviation. The plan's acceptance criteria were followed as written in every
other task.

## 9. Remaining risks / follow-up

- LD-3 (the legacy `terminal_payout_bps` substitution) is characterized, not
  fixed, exactly as this wave's scope requires — it is unaddressed by
  design (D5), and T5.7's test will fail loudly the moment anyone changes it
  silently.
- The full Shell `cargo test --lib` run confirming the exact three-name
  protected failing set (R-26.2) end to end, and the `random20-inputs.json`-seeded
  parity test, were not re-run to completion in this final phase (§4/§5) —
  handed to orchestrator/QA, not silently skipped.
- The `cargo fmt -- <files>` scoping defect (§4) may affect other waves in
  this effort if they assumed file-scoped formatting; worth a note to the
  orchestrator.
