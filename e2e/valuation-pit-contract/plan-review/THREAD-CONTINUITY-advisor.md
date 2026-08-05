# Thread-continuity brief — ADVISOR (plan review, rounds 1–3)

**Read this before spawning any Advisor review on this session.** The Advisor "thread" was never a
single resumable thread. See §0.

---

## 0. Continuity status — RECONSTITUTE, do not attempt to resume

Identical to the Sensei situation. The harness exposed **no thread-resume capability to the
orchestrator**: `SendMessage` appeared in tool descriptions and in task-completion notifications but
was **not in the orchestrator's callable function list** at any point in rounds 1–3.

- **Every round spawned a fresh `advisor` agent.** Three independent agents, no shared memory.
- Prior findings were passed forward **as text** each round. That is simulated continuity.
- Harness agent ids are internal, per-run, and would not survive a new session.
- **No `plan-review/advisor-r*.md` transcript files were ever written.** Raw per-round output exists
  only in harness-internal task files under the session scratch directory.

**One thread may still be resumable.** The round-3 agent is named **`Advisor plan review r3`**, task
id **`a499f18655c5839ca`**, raw output at
`G:\dev\caches\tmp\claude\G--dev-repos-discount-screener\a5b7ed32-8a2d-4c54-ba78-5568775587f2\tasks\a499f18655c5839ca.output`.
An agent that *does* have `SendMessage` can resume it by that id, and it is the round with the
deepest repo tracing (41 tool uses, full call-graph walk). **Prefer resuming `a499f18655c5839ca`
over spawning a fresh Advisor.** The round-1 and round-2 agent ids were not carried across a context
compaction and are unrecoverable.

**This brief is the continuity artifact for rounds 1–2**, and the safety net if
`a499f18655c5839ca` cannot be resumed.

---

## 1. Standing instruction given to Advisor in every round

- Role: review the plan against **this project's** documentation, history, and hard-won constraints;
  prevent repeats of mistakes the repo already paid for; hold **Correctness Over Delivery
  Convenience**.
- Tools: `Read`, `Grep`, `Glob`. **The Advisor is the reader.** Its distinguishing value across all
  three rounds was checking whether the plan's claims about the codebase are *true*. It found the
  two false "verified" claims that forced the plan's evidence rule into existence.
- Anticipatory requirement: private multi-pass until a further pass adds nothing material; return one
  package; report pass count.
- Inputs supplied each round: `brief.md`, `refine.md`, `plan-review/r1-consolidated-directives.md`,
  the plan revision under review, prior-round findings as text, plus the repo itself.
- Authoritative project sources it was pointed at: `AGENTS.md`, `docs/index.md`,
  `shared/contracts/README.md`, `shared/contracts/sec-driver-normalization.json`,
  `_bmad-output/planning-artifacts/.../prd.md`, `manifest.toml`, the cucumber feature files.

**Round 3 additions** (carry these forward):
- The **evidence rule applied to the reviewer itself**: any claim that something is verified,
  unreachable, unused, sole-writer, or exhaustive **must carry the grep pattern or the `file:line`**.
  The named failure mode: *a sound conclusion resting on a search that could not have found the
  counterexample.* The Advisor is the reviewer this rule was derived from and the one best placed to
  apply it.
- Instruction to **search for the re-abs case rather than trust the count** — i.e. hunt for a fourth
  `.abs()` on the interest series anywhere in the crate, not just verify the three named sites.
- Anti-formality instruction: a dual `approve` on a 2,525-line document with nothing substantive
  found is itself reportable.

---

## 2. Round 1 findings and disposition

Advisor returned **revise**. Its findings are recorded as **P0-F … P0-I** in
`plan-review/r1-consolidated-directives.md` §2, with the binding RESOLUTIONs.

| Id | Finding | Disposition |
|---|---|---|
| **P0-F** (Advisor P0-1) | The plan's §6.2 wrote *"no live QA required"*. Verified `AGENTS.md:486` — the mandatory list includes model policy version, and the anti-pattern table carries *"Required native/live gates relabeled optional"*. | **APPLIED, then REGRESSED, then RE-APPLIED.** v0→v1 adopted the unconditional gate (`cargo test --lib dcf_model::`, `valuation_baseline::`, `quant_lens::`, `npm run test:e2e:native:cof`). **v1 silently dropped it from Wave 2** along with COF and the 26-name cohort — caught in r2 by *both* reviewers independently and by Juan. v2 restores all four commands by name to **Wave 2 and Wave 5**, restores COF (v1 had substituted LIN, which is not one of the four measured issuers), and restores the cohort. The word "optional" appears nowhere. |
| **P0-G** (Advisor P0-2) | W1's fail-closed rules change what `extract_driver_annual` returns from **live** companyfacts, so W1 — not only W2 — perturbs live inputs. "Expected delta exactly zero" was fixture evidence making a live claim. | **APPLIED IN FULL.** W1 and W2 scheduled in **different rounds**. W2's report extended from anchors to a named-issuer table for **COF, DAL, CHTR, BKR** plus the 26-name cohort. *"An operating issuer goes from valued to unavailable"* is pause trigger (c). The `valuation_high_signal` fixture-rewrite caveat (constraint 8) is carried: read as a table, leave unstaged. |
| **P0-H** (Advisor P0-3) | The `InterestPaidNet` precedent does **not** cover this case. Verified `AGENTS.md:565` and `sec-driver-normalization.json:76`: the committed rule is a **cross-statement** rule; `InterestIncomeExpenseNet` and `…NonoperatingNet` are income-statement concepts, so the existing rule read literally does not forbid them. | **APPLIED, with the rule restated rather than adopted as written.** Advisor wrote R2 as a prohibition; under the sign convention it is an **admission rule**. Binding text: **R1** — a class holds one statement's concept only; **R2** — a class holds one **measurement basis**, and a netted concept enters only through a **declared sign convention** mapping it onto that basis; absent a convention it reads **absent, not equivalent**. Stated in `shared/contracts/README.md`, `AGENTS.md`, and the extended `rationale`; the seven-name list is pinned against **both rules by name** in the failure message. Advisor's second-order note adopted: `stockholdersEquity` (`:97`) mixes including/excluding-NCI bases — named as a follow-up audit (**LD register**) so R2 is not decorative. |
| **P0-I** (Advisor P0-4) | `refine.md:49-50` marks Q1 **OPEN, FOR JUAN**; the plan decided it anyway and defended option (i) with reversal cost — a delivery-convenience argument against `AGENTS.md:36` and brief §5. | **APPLIED AND THEN OVERTAKEN.** v1 carried Q1 at its head and blocked W2 on Juan's answer. The escalation itself changed twice: first to option **(iii)** (the sign convention, not among the two options originally put to Juan), then — after Sensei r2 P0-8 proved (iii) alone is economically inert — to the LD-1 three-site removal. **Juan ruled: LD-1 is in scope.** Q1 is now closed. |
| **Advisor P1-1** (r1) | `AbsenceReason` is dropped in the `let-else` and the adapter supplies `ProviderUnavailable`; offered (a) propagate or (b) record the inaccuracy. | **SUPERSEDED by Sensei P0-B.** Advisor's own second-order note showed (a) breaks the bank test's only discriminator. The `EstimatorUnavailable` variant resolves both by construction. Neither (a) nor (b) adopted. Advisor's verified detail retained: Core tests at `projection.rs:590,653,672` assert `Some(NotReported)` and are constructed with `NotReported` inputs at `:433`, so they stay green; the cost is confined to the bank test, which is where the discrimination belongs. |
| **Advisor P1-2** (converged with Sensei P1-2) | W3 trims the mean and feeds the outlier back into the fit: `robust_centre` replaces `mean` at `:280` for `pooled_mean` only, but the contaminated observation stays in `pairs` at `:282-290` and `persistence = cross/square` at `:295-297` runs over those pairs. Centre from one sample, slope from another. | **APPLIED.** Decided in the plan; `growth_pooled_discarded` reports the count that actually affects the fit; old/new `persistence` shown in `docs/valuation-aggregation-audit.md`; T3.5's audit table gained the missing Keep/Fix row for the through-origin fit at `:295-296`. |
| **Advisor P1-3** | W4 consumes W5's output in the same round — `AGENTS.md` would ship a rule for behaviour that does not exist and `docs/index.md` a dead link (`AGENTS.md:173`). | **APPLIED.** W4 scheduled after W5 (R4 vs R3). |
| **Advisor P1-4** (converged with Sensei P1-5) | W1's blast radius unproven on live data; `edgar.rs:196` admits a fact with no `filed` via `unwrap_or("")` and W1 drops it. | **APPLIED.** `#[ignore]` probe on the `valuation_probes.rs` convention over ≥5 real issuers (`AGENTS.md:366`); *"any non-zero delta must be explained by that count or is a defect"*. If the count is non-zero W1 becomes coverage-reducing on the production FCFF path and inherits P0-F and P0-G. |
| **Advisor P1-5** | Residual income loses its FR anchor. Verified `manifest.toml:56`: `residual-income-on-book` has `frs = ["FR-30","FR-31","FR-32"]` — **no FR-29**. T5.5's claim that *"`frs` keeps FR-29"* for both entries was **false**. | **APPLIED.** `FR-29` added to that entry; one sentence added to FR-29's rewritten prose stating the residual-income form. **FR-31 deliberately not opened** — `prd.md:437` carries an assumption and open question 5 that would pull COF provision normalization into scope. |
| **Advisor P1-6** | W1/W2 disjointness holds only if `SecFact` is frozen; `SecFact.value_dollars` is `i64` but T1.3 routes `extract_annual_percent_any` (unit `"pure"`) through it, so a builder may "fix" `sec_normalization.rs`, which W2 owns. | **APPLIED as invariant I6** — Wave 1 does not modify `sec_normalization.rs`; pre-decided that non-dollar facts store the filed integer with the true `unit` string. |
| **Advisor P2** (converged into P0-E) | Trimmed-sample variance biased downward → contaminated channel reports tighter precision and is weighted **up** under inverse-variance fusion. | See Sensei brief P0-E. Resolved in v2 by construction (T3.8). |

**Advisor r1 P2s — all applied** (`r1-consolidated-directives.md` §4): `shared/contracts/README.md`
`## Files` omits `sec-driver-normalization.json` and its fixtures (also absent from `docs/index.md`);
`AGENTS.md` `## Documentation Map` (585-601) not updated and **`AGENTS.md:573` requires a manual-procedure
step, not only anti-pattern rows** — the policy-fingerprint-bump procedure step was added;
`valuation_probes.rs:465-466` calls `robust_mean` then `standardize` again to recover the discarded
count, using a fully-qualified path against constraint 10 — named in the audit doc as a follow-up
(and now the reason T5.11 lands in R3, not R1); **line-number drift** (`residual_income.rs`
`unwrap_or(cost_of_equity)` at **:111** not :108; `compute_dcf` called at **:1185**, defined at
**:681**; `return_on_capital` at **:557**) with the standing instruction to **prefer symbol names
over line numbers**; `schema.rs` has **seven** `#[test]`s (139,158,171,196,228,244,279) not six —
the plan had repeated the brief's error; `AGENTS.md` carries uncommitted working-tree edits and W4
must preserve them and stage only `AGENTS.md` (constraint 7); F7's file enumeration was wrong.

---

## 3. Round 2 findings and disposition

Advisor returned **revise**. Its round-2 contribution was smaller in count than Sensei's but
contained the two findings that changed how the whole plan is written.

| Id | Finding | Disposition |
|---|---|---|
| **r2 P0-1** | The P0-F mandatory gate, COF, and the 26-name cohort were **dropped from Wave 2** in v1 despite being binding r1 resolutions. Converged independently with **Sensei r2 P0-6** and with Juan's own reading. | **APPLIED IN FULL** — see P0-F above. This is the clearest instance in the run of a binding resolution silently regressing between revisions, and is why v2 §7 carries an explicit changelog. |
| **r2 P1-1** | **v1's claim that the `driver_resolution.rs:117` guard is test-reachable is false.** | **CORRECT; INDEPENDENTLY RE-VERIFIED AND THE FINDING INVERTED.** `fn point(..)` at `driver_resolution.rs:326` constructs via `FcfPoint::new(..).with_operating_drivers(..)`; there is **no struct-literal construction anywhere in the file**, so every one of the file's **ten** tests routes through the setter at `dcf_model.rs:907`, which abs-es. The `interest < 0.0` branch at `:118` is unreachable from production **and** from the test suite. v2 does not merely correct the claim — it reverses its direction: the guard is **untested code about to go live in the wave that moves published numbers**, not a safety net. T2.7 therefore requires direct branch coverage at the `resolve_rate_inputs` boundary, not only end-to-end coverage. |
| **r2 P2-1** | **v1's claim that `RustSlice` is malformed on an empty collection is false.** | **CORRECT.** Verified `scripts/generate-sec-driver-normalization-policy.ps1`: only `KotlinList` (`:22-32`) is broken on empty; `RustSlice` (`:74-81`) is fine. **D6 and T2.11 corrected**, and — more importantly — the rationale for the positional-parallel-array design was **rewritten with honest caveats** instead of resting on a defect that does not exist. `KotlinList`'s empty case is fixed anyway. |

**Effect of the two false claims.** Together with Sensei r2 P0-7 (the unsound sole-writer grep),
these are the three instances of the run's recurring failure class. They produced the standing rule
now at the head of `plan.v2.md`:

> v1 marked twenty-two claims "verified". **Two did not survive contact with the code.** Therefore,
> in this document, a load-bearing claim marked "verified" carries the evidence that establishes it
> — the search pattern, or the `file:line`.

Juan then made this binding: *"Do not let the planner mark a claim 'verified' again without the
evidence attached."* Round 3 extended it to the reviewers themselves.

---

## 4. Round 3 — instruction given

Round 3 asked the Advisor specifically to:

1. **Verify against the repo** whether Juan's two rulings are implemented or merely acknowledged:
   (a) does **T3.8** compute centre **and** width from the **same kept set**, with `observations`
   reporting the **kept** count — or does it robust-trim the centre and leave `sample_variance`
   untouched (which Juan called *worse than doing nothing*: a clean level with a contaminated width,
   silently reassigning weight between channels under `fuse`);
   (b) does **Wave 2** remove **all three** `.abs()` sites in one commit — `dcf_model.rs:907`
   (setter), `:551` (FCFF driver audit), `:1590` (aligned-driver bridge) — and **is there a fourth
   site anywhere in the crate that would silently restore the defect**. Search for it; do not trust
   the count.
2. Apply the evidence rule to itself: every verified / unreachable / unused / sole-writer claim
   carries its grep pattern or `file:line`.
3. Check the **new, least-reviewed** material against the repo: T4.5 (target specification),
   T5.11 (threshold-knob removal and the `valuation_probes.rs` collision that motivated its R3
   scheduling), T5.12 (F1 as a compile-enforced proof), the D7 detector column, LD-5/6/7.
4. Check the **J6 acceptance grep** — see §5, first residual item.
5. Confirm that no r1 or r2 resolution has regressed again between v1 and v2.

**Verdict: see the orchestrator's final report / §6 below.**

---

## 5. Advisor positions plan.v2 has NOT satisfied

- **The J6 grep defect is the orchestrator's own, found late and self-reported.** v2's original
  acceptance search for LD-1, `grep -nE "interest.*abs\(\)"`, is **blind to the setter at `:907`**,
  because `.map(f64::abs)` has no parentheses after `abs`. A builder could have removed the two
  consumer sites, run the plan's own check, watched it pass, and shipped with the setter intact —
  which changes nothing observable, i.e. exactly the subset-removal failure the wave exists to
  prevent. Corrected to `grep -nE "interest.*(abs\(\)|f64::abs)"` **and recorded in the plan as a
  defect** rather than quietly fixed. This is a fourth instance of the recurring failure class and
  it argues the class is not yet contained.
- **The `stockholdersEquity` R2 audit is named, not scheduled.** R2 binds every other
  `select_one_equivalent` list; only the follow-up is recorded.
- **Line-number drift persists** as a structural risk. The plan still cites `file:line` extensively
  because Juan's evidence rule demands it, while the Advisor's own r1 P2 advises symbol names.
  These two instructions pull in opposite directions and v2 resolves it only by convention
  (`symbol` plus a line as of the baseline commit).
- **`AGENTS.md` uncommitted working-tree edits** are still live and W4 is still its sole owner;
  the preservation requirement is a builder instruction, not an enforced check.
- **`prd.md` remains `status: draft`** (brief §2) — the plan's citations to it are to a document
  that has not been ratified.
- The three protected failing tests are protected but **not diagnosed** — see the Sensei brief.

---

## 6. Round 3 verdict and findings — ALL OPEN

**Verdict: `revise`. 5 anticipatory passes. 2 P0, 1 P1, plus an explicit confirmed-clean list.**

**No plan.v3 was written.** The orchestrator was instructed to report and stop at the end of round 3.
Every finding below is **OPEN and unapplied**.

The Advisor's bar check on v2, verbatim in substance: *"v2 is materially stronger than v1 — it
reversed its own inertness proof, pulled LD-1 into scope at real cost… It did not try to lower the
bar on anything I checked in this pass: no coverage-preservation move, no threshold relaxation, no
scope-cutting disguised as convenience. What I found and am rejecting on is different in kind from
v1's errors — not a plan that softened a check, but a plan whose own 'verified' call-graph analysis
for T2.7 stopped one function short of where the real risk lives."*

### Round 3 P0s

| Id | Finding | Status |
|---|---|---|
| **r3 P0-1** | **T2.7's "fall through to the other cost-of-debt path" is DEAD IN PRODUCTION, and the pause-trigger carve-out then exempts the behaviour most likely to hit an anchor.** Evidence chain, all re-read: `driver_resolution.rs:1-7` module doc states the first two rungs are dead because every call site passes `None`; **confirmed at the sole production call site `edgar.rs:1096-1101`**, which passes `None, None` for `market_yield_bps` and `rated_or_synthetic_spread_bps`. So in `resolve_rate_inputs_for_source` (`:172-240`), emptying `accounting_common` hits the terminal `else` at `:236-239` and returns `Err(...)`, which propagates by `?` through `derive_wacc` (`dcf_model.rs:3060`) and `fcff_wacc` (`:2366`) — **the entire FCFF DCF valuation for that issuer fails.** T2.7's refusal is not "refuse the channel"; it is "refuse the valuation". Wave 2's pause trigger (b) exempts *"other than through T2.7's declared refusal"* and **does not carve out the four anchors**; going fully unavailable is neither a percentage move nor unambiguously a gate-side change, so an anchor could go dark with no forced stop — against Juan's explicit instruction not to decide for him whether a move away from an anchor is worth it. **Fix: (1)** state with citation that the fall-through rungs are dead today, so no reader infers a safety net; **(2)** amend the pause triggers so **any anchor going valued→unavailable by any cause, including T2.7, is an unconditional stop**; keep the carve-out for non-anchors only. **Second-order:** T2.7's acceptance tests only at the `driver_resolution.rs` boundary — add a `dcf_model`-level test asserting the full DCF returns `Err`. | OPEN — **converges with Sensei r3 P0-10** on the anchor exposure |
| **r3 P0-2** | **The mandatory gate, "restored in full," misses the changed function's second production caller.** `resolve_rate_inputs_for_source` → `derive_wacc` (`dcf_model.rs:3060`) → **two** callers: `fcff_wacc` (`:2366`) and `resolve_attribution_wacc` (`dcf_model.rs:608-646`, `pub fn`). The latter's sole workspace caller is **`valuation_gap_attribution.rs:1722`**, inside a path explicitly commented *"CHTR-class ordering failures"* (`:1707-1708`) — a live path for exactly the issuer class Wave 2 measures. That file has **15 `#[test]`s**, and **none of the four mandatory gate commands exercises them** (`quant_lens.rs` calls none of the three functions; grep, zero hits). *"The gate list was checked against v1's omissions, not against the actual call graph of the function T2.7 changes."* **Fix:** add `cargo test --lib valuation_gap_attribution::` to Wave 2's deferred-checks table and Done-when gate, justified in the same style as `quant_lens::`. **Second-order:** re-ask one level up and **state in the wave report that the call graph was checked**, not merely that one target was added. | OPEN |

### Round 3 P1

- **r3 P1-1 — a new, competing ADR location.** `AGENTS.md:590`'s Documentation Map already links `_bmad-output/planning-artifacts/valuation-model-family-architecture.md`, labelled **"valuation ADRs"**, a live numbered decision log **`AD-VM-001` … `AD-VM-011`** covering FCFF, WACC, residual income and `ValuationResult`. The brief's *"no existing ADR file anywhere in the tracked tree"* is true **only within `docs/`**, and the plan (T4.6, T4.7, F16) treats it as globally true, then invents a second series (`docs/adr-0001-fr-29-…`, new `ADR-000#` numbering, new `## Architecture Decisions` section in `docs/index.md`). Splitting the valuation decision record across two conventions is the fragmentation the repo's documentation-map discipline exists to prevent. **Fix: either** record FR-29 as **`AD-VM-012`** in the existing file and drop the new file and section entirely, **or** declare the new series deliberately, cross-link both directions, and update AGENTS.md's "valuation ADRs" line. If AD-VM-012 is chosen, first verify that heading shape can carry a 7-row latent-defect register cleanly. | OPEN

### Confirmed clean by the Advisor in round 3 (do not re-litigate)

Stated explicitly by the Advisor because an unexamined pass is itself a finding:

- **The three `.abs()` sites are exactly three.** `grep -nE "abs\(\)|f64::abs" src/*.rs` across the whole crate, manually filtered: only `dcf_model.rs:551`, `:907`, `:1590` touch interest. **No fourth site exists.** *(This independently discharges the conclusion Sensei r3 P0-3 challenged; the plan's own evidence for it is still unsound and must be replaced.)*
- **The sole-writer claim holds** under all three corrected patterns, re-run; results match the plan's table exactly.
- **The guard is dead everywhere**: 10 tests, one construction path (`point()` at `:326`), no struct literal; `:118` has never executed.
- **T2.8's sweep targets are real and correctly located**: `valuation_fixture_capture.rs:131`, `edgar.rs:987` and `:1083`.
- **T5.11's premise holds**: `robust_mean`'s only external caller is `valuation_probes.rs:465`; the F18 collision reasoning is sound.
- **T4.5's 17 rows match the brief item-for-item**, counted side by side.
- **`RustSlice`/`KotlinList`**: traced the PowerShell functions — `RustSlice` on empty returns `&[]` (compaction branch, `nestedIndent -eq 0`, count ≤ 2); `KotlinList` on empty emits `listOf(\n,\n    )`, a stray leading comma, invalid Kotlin. **v2's correction of v1 is accurate.**
- **`AGENTS.md:486`'s manual-procedure trigger list covers Wave 2's change** (WACC/CoD resolution plus a model-policy-version bump), so the plan honours the named standing procedure.

### Regression traps the Advisor names for the next round

- *"Same class as r2's finding on `driver_resolution.rs:117`: a claim of 'verified' that stopped at the first function in a call chain rather than the last."*
- *"The 'mandatory gate restored in full' narrative is compelling and risks being read as complete because it corrects v1's specific omissions… exactly the kind of narrow, itemized correction that leaves a structurally different gap unnoticed. This is the run's recurring failure mode restated at one level higher: fixing the named counterexample without re-deriving the search that should have found all of them."*
