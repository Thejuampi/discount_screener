# Thread-continuity brief — SENSEI (plan review, rounds 1–3)

**Read this before spawning any Sensei review on this session.** The Sensei "thread" was never a
single resumable thread. See §0.

---

## 0. Continuity status — RECONSTITUTE, do not attempt to resume

The harness in use for rounds 1–3 exposed **no thread-resume capability to the orchestrator**.
`SendMessage` was referenced in tool descriptions and in task-completion notifications but was
**not present in the orchestrator's available function list**, so it could never be called.

Consequence, stated plainly because it is a deviation from the Stage 3 requirement that the same
Sensei thread be reused across iterations:

- **Every round spawned a fresh `sensei` agent.** Rounds 1, 2 and 3 are three independent agents
  with no shared memory.
- Each was given the prior round's findings **as text** and told to treat them as its own. That is
  a simulation of continuity, not continuity.
- Agent ids were issued by the harness but are marked internal-only and are not reproducible here;
  they are also per-run and would not survive a new session.
- **No `plan-review/sensei-r*.md` transcript files were ever written.** The only raw transcripts are
  harness-internal task output files under the session scratch directory, which the orchestrator was
  instructed not to read.

**One thread may still be resumable.** The round-3 agent is named **`Sensei plan review r3`**, task
id **`af533d773e6bee2f7`**, raw output at
`G:\dev\caches\tmp\claude\G--dev-repos-discount-screener\a5b7ed32-8a2d-4c54-ba78-5568775587f2\tasks\af533d773e6bee2f7.output`.
An agent that *does* have `SendMessage` can resume it by that id — and it is the round with the
richest context (it read all 2,525 lines of `plan.v2.md`). **Prefer resuming `af533d773e6bee2f7`
over spawning a fresh Sensei.** The round-1 and round-2 agent ids were not carried across a context
compaction and are unrecoverable.

**Therefore this brief is the continuity artifact for rounds 1–2**, and the safety net if
`af533d773e6bee2f7` cannot be resumed. A fresh Sensei must be given this file so it does not
re-litigate settled ground.

---

## 1. Standing instruction given to Sensei in every round

- Role: bar-raising review of the plan from cross-project experience; challenge mediocre solutions;
  prefer durable designs over convenient ones.
- **Hard constraint: Sensei does NOT read repository files.** It judges from the plan text, the
  brief, and the consolidated directives only. This was enforced in all three rounds and is why
  Sensei's findings are about reasoning and specification quality, while the Advisor's are about
  whether claims match the repo.
- Anticipatory requirement: run private multi-pass review — *if every fix you proposed were applied,
  what would you still flag?* — until a further pass adds nothing material, then return one package.
  Report the number of passes completed.
- Inputs supplied each round: `brief.md`, `plan-review/r1-consolidated-directives.md`, the plan
  revision under review, and the prior round's own findings as text.

**Round 3 additions** (carry these forward):
- An **evidence rule applied to the reviewer itself**: any claim that something is *verified*,
  *unreachable*, *unused*, *sole-writer* or *exhaustive* must carry the reasoning that establishes
  it and must state what a counterexample would look like and why the reasoning would have found it.
  Sensei cannot grep; where a claim needs one, it must name the pattern for the orchestrator to run.
- An explicit anti-formality instruction: an unexamined `approve` on a 2,525-line document is itself
  a finding, and Sensei must say what it checked if it finds nothing.

---

## 2. Round 1 findings and disposition

Sensei returned **revise**, 4 anticipatory passes, 5 P0s. Full text in
`plan-review/r1-consolidated-directives.md` §2 (as P0-A … P0-E) and §3.

| Id | Finding | Disposition |
|---|---|---|
| **P0-A** (Sensei P0-1) | The FR-29 fabrication survives in the only engine that publishes (`operating_valuation.rs:223`). Required a qualified completion statement, a characterization test, and a tracked id with trigger. | **APPLIED** as D5 + T5.7 + LD-3. **One sub-item DECLINED**: Sensei asked for a Gherkin row; verified not implementable — the feature files cover `valuation-core` only and the legacy engine has no Gherkin surface. A characterization test is the correct instrument. Recorded in v1 changelog item 15 with the reason. |
| **P0-B** (Sensei P0-2) | Reusing `AbsenceReason::NotReported` fabricates a *cause* and voids W5's own discriminating test. Add `EstimatorUnavailable`, add a `reason` column, assert the reason. | **APPLIED** as D3 + T5.1 + the `reason` column. This **superseded Advisor P1-1**, which found the same defect from the other end; Sensei's variant discriminates by construction. |
| **P0-C** (Sensei P0-3) | `known_from = max(sources.filed)` is not point-in-time — no vintages retained. | **APPLIED** as D1 (`AnnualSeries`, `IsoDate`, strict `filed < cutoff`, fiscal-year semantics pinned). |
| **P0-D** (Sensei P0-4) | "Common issuer-cutoff set" read as an intersection permits win-by-abstention; no multiplicity rule; MdAE/MAE conflated. | **APPLIED but incompletely in v1, completed in v2** — see r2 P0-4 below. Anchors left the veto set per constraint 9. |
| **P0-E** (Sensei P0-5, converged with Advisor P2) | Trimmed-sample variance is biased downward, so a contaminated channel reports tighter precision and is weighted **up**. Also: `robust_centre(sample, max_absolute_z)` reopens the threshold constraint — remove the parameter or make it crate-private. | **PARTIALLY APPLIED in v1, fully resolved in v2.** v1 renamed to `variance_of_centre` and documented the direction but shipped it with no caller, and moved the public knob to `robust_mean`. v2: bias fixed **by construction** (one kept set, T3.8); knob removed by **T5.11**. |

Sensei r1 P1s (§3) — all applied: semantic wave-dependency statements; protect the three failing
tests **by name** not by count; ±5% named as a convention; exhaustive property test instead of six
converted assertions (T5.8); intra-W4 checkpoint; no-outcome-observed attestation; freeze protocol.

---

## 3. Round 2 findings and disposition

Sensei returned **revise**, **5 anticipatory passes**, **10 P0s**. This was the strongest round.

| Id | Finding | Disposition |
|---|---|---|
| **r2 P0-1** | **T3.6 keeps the naked mean at `:536`**, reversing brief §156/§247 (*"`:536` is the worse of the two"*) with a coverage-preservation argument Juan's closing line forbids. Sensei called this the worst finding and said it belongs to Juan, not to a table cell. | **ESCALATED TO JUAN → RULED: REPLACE.** Juan went further than Sensei asked: centre **and** width from the same kept set, `observations` = kept count. Now **T3.8**. Sensei's refusal-rate objection to the planner's reasoning was verified correct (n is typically 9–18; refusal needs kept<3). |
| **r2 P0-2** | `variance_of_centre` is labelled, not fixed, **and has no consumer at all**. Bias is *monotone in contamination*. Recommendation: **do not ship the accessor.** | **OVERRULED BY JUAN'S RULING, DELIBERATELY.** Juan's Q2 ruling supplied a live consumer — which is the exact condition Sensei's own second-order note named (*"with `:536` converted, a precision term is required and this cannot be deferred"*). The accessor ships with the monotone bias fixed **by construction** rather than documented. **This conflict is recorded in plan.v2 §7 changelog and must not be silently reversed.** Round 3 was asked to check whether the substitute is genuinely stronger. |
| **r2 P0-3** | `robust_mean`'s public threshold parameter survives; the knob was relocated, not removed. A convention plus a grep is a promise, not a constraint. | **APPLIED as T5.11**, deferred to R3 for a **file-collision reason Sensei could not see** (it does not read files): `robust_mean`'s only external caller is `valuation_probes.rs`, which Wave 1 owns concurrently in R1. Scheduling constraint, not a deferral — the knob is gone before the run ends. |
| **r2 P0-4** | Pre-registration's primary endpoint is **not the brief's endpoint** (different quantity, different units, three-year horizon dropped); win-by-abstention still open because "same issuers/years/cutoffs" is a *pairing* rule, not a *set-construction* rule. | **APPLIED IN FULL.** Endpoint restored verbatim; cross-section pre-declared independently of any candidate's ability to resolve it; **abstention is scored, not dropped**; dropping abstained cells named as a prohibited analysis. |
| **r2 P0-5** | Wave 4's required content does not match the brief; the **target specification is essentially unplanned** (~17 decisions absent from the entire plan). | **APPLIED IN FULL.** T4.1 rewritten to the brief's enumeration item-for-item including the reinvestment identity, its sequencing fact, and **financial-issuer semantics as its own section**. New **T4.5 — `docs/roic-target-specification.md`** with all 17 decisions. T4.8 checkpoint now gates on *coverage of the brief's enumerations*, not prose quality. |
| **r2 P0-6** | P0-F's binding mandatory gate is not in the plan; COF and the 26-name cohort were dropped from Wave 2. (**Converged independently with Advisor r2 P0-1.**) | **APPLIED IN FULL.** All four gate commands restored by name to Wave 2 **and** Wave 5; COF restored (v1 had substituted LIN, which is not one of the four measured issuers); 26-name cohort restored; Wave 2 gained its own pause triggers. |
| **r2 P0-7** | T2.6 proves less than invariance: the sole-writer claim rests on a grep pattern that **cannot match Rust struct-literal init**; sole-consumer of the *driver series* never established; and the plan applies its own evidence standard inconsistently. | **METHODOLOGICALLY CORRECT AND ACTED ON.** The search was re-run with three patterns and the conclusion held. **Then the whole question was mooted** by Juan's Q1 ruling — LD-1 is in scope, the invariance proof is deleted with its premise, and live QA runs in full. |
| **r2 P0-8** | **Wave 2 is economically inert** — `.abs()` annihilates the corrected sign for *both* filer classes, so the change buys nothing; Q1's option table overstates the benefit; work-order item 2 is half-delivered and reported as delivered. | **CORRECT, AND IT CHANGED THE RUN.** Escalated to Juan with the corrected framing → **Juan ruled LD-1 into scope.** Wave 2 now removes all three `.abs()` sites. This is the single highest-value finding of the review process. |
| **r2 P0-9** | The Kotlin `qnameSigns` default (`= List(qnames.size) { 1 }`) is the exact silent default the plan forbids on the Rust side two tasks earlier; the length check cannot catch it. Also: the positional-array rationale is partly circular. | **APPLIED.** Default removed; parameter required so the compiler enumerates construction sites. The circularity is **conceded in v2's D6** rather than defended, `KotlinList`'s empty-case defect is fixed anyway, and an **alignment** test (reconstruct signs from `negatedQnames`) is added because a length check cannot catch a wrong value at a right length. |
| **r2 P0-10** | `AnnualSeries` cannot represent a composed driver, so `as_of` covers single-concept drivers only — which excludes the entire FCFF bridge. The plan presents PIT as general. | **APPLIED as a named limitation + LD-6**, with the fix that will be needed at item 6 (compose inside the vintage layer). Sensei accepted that naming it is sufficient since item 6 is out of scope. |

**r2 P1s — dispositions:** P1-1 (`InsufficientObservations` unverified) — **verified to already exist**,
no `evidence.rs` edit needed, collision risk void. P1-2/P1-3 (T1.7 sample unspecified; measures the
wrong thing) — **applied**: sample named explicitly (4 anchors + 4 Wave 2 issuers + oldest-history
slice of the cohort), third column added counting `(concept, period_end)` pairs with differing
vintages, per-issuer identification of specific dropped facts required. P1-4 (serialization relocates
attribution onto the time axis) — **applied**: cache companyfacts payloads at R1 baseline, reuse for
R2's before/after, record retrieval timestamps, re-establish the **live** baseline at R1 exit.
P1-5 (Wave 4 documents a Wave 2 that may never land) — **moot**, Q1 answered. P1-6 (W5 names no
automated gate; F1 is grep evidence) — **applied**: gate named, plus **T5.12** converts F1 into a
compile-enforced proof. P1-7 (materiality derivation pre-authorised as judgement) — **applied**,
derivation now required through `FCFF = NOPAT × (1 − g/r)`. P1-8 (`persistence` old/new missing from
the committed audit doc) — **applied**. P1-9 (T3.4 outlier indices cross a flatten boundary unguarded)
— **applied**, typed keys or carried `(issuer, year)` required. P1-10 (T5.4 lets the builder rewrite
the spec) — **applied and inverted**: builder now stops and reports; **Juan confirmed** this.
P1-11 (D5 absent from the run-level report) — **applied**. P1-12 (register triggers have no detector)
— **applied**, detector column added; rows with no mechanical detector say so. P1-13 (cross-platform
parity asserted with no instrument) — **applied**, stated as a known gap rather than implied away.
P1-14 (§4 omits the cucumber baseline) — **applied**. P1-15 (`terminal_payout_bps` `pub` unverified)
— **verified `pub` at `operating_valuation.rs:212`**; no fallback needed.

**r2 P2s** — applied: constraint miscitations corrected (anchors are constraint **9** not 12;
`status: draft` is **brief §2** not constraint 13); ±5% relocated out of the pre-registration;
`accn` fail-closed added; W5-E02 doc comment now says *delete, do not weaken*; clippy added to
Wave 2's fast checks; `:631`/`:637` given register treatment. **Not applied:** the `get(..4)`
pattern-scope broadening and the bank-vs-operating adapter-ordering pin were noted but are not
separately tasked — see §5 residual.

---

## 4. Round 3 — instruction given

Round 3 asked Sensei specifically to judge:

1. Whether each r2 P0 was **implemented or merely acknowledged**, with two named checks:
   (a) does T3.8 compute centre **and** width from the same kept set with a kept-count
   `observations`, or does it robust-trim the centre and leave the width untouched — which Sensei
   itself called *worse than doing nothing*;
   (b) does Wave 2 remove all three `.abs()` sites in one commit, and is there any downstream re-abs.
2. Whether **two tests are enough** for a branch that has never executed anywhere. (Verified:
   `driver_resolution.rs` has 10 tests, all routing through the `point()` helper at `:326`, which
   abs-es — so `interest < 0.0` at `:118` has never run in production *or* in a test. Wave 2 enables
   it in the wave that moves published numbers.)
3. Whether the **guard ruling in T2.7 is right** — refuse the cost-of-debt channel for an issuer with
   any negative-interest year, rather than silently dropping the year. Rationale offered:
   `filter_map` keeping `interest > 0.0` retains the years where interest expense was high relative
   to income and discards the ones where it was low, biasing the fitted rate upward — selection on
   the dependent variable. **This ruling was made by the orchestrator, not by Juan or a specialist**,
   and was flagged as such for attack.
4. The least-reviewed new material: T4.5, T5.11, T5.12, the D7 detector column, the LD restructuring.
5. Whether overruling its own P0-2 produced a genuinely stronger result or a rationalisation.

**Verdict: see the orchestrator's final report / §6 below.**

---

## 5. Sensei positions plan.v2 has NOT satisfied

Carry these into any future round — they are open, not settled:

- **The pre-registration is self-certified.** No external party attests the freeze; T4.8's
  checkpoint mitigates ordering, not incentive. v2 requires the document to *say so about itself*,
  which is disclosure, not a fix.
- **Four serial rounds against a live external provider cannot be made fully attributable.**
  Caching narrows the window; it does not close it.
- **`extract_driver_vintages` ships with no production caller** and is validated by inline fixtures
  plus one live count. Item 6 will be its first real consumer and will find what fixtures did not.
- **F1 is load-bearing for two waves' live-QA posture and is point-in-time.** Even as a
  compile-enforced proof (T5.12), the first wiring of `valuation_core_adapter::value()` to production
  silently invalidates both waves' anchor reasoning. v2 records this as a standing condition in D7.
- **The three protected failing tests remain unexplained.** Every wave asserts they are unchanged;
  none knows why they fail. A wave that accidentally repairs one produces a report nobody can
  interpret.
- **Wave 2's economic ruling (T2.7) is orchestrator-made.** It has been reviewed but not
  independently derived by a domain specialist.
- Two r2 P2s were noted and not separately tasked: `get(..4)` is a pattern-scoped check presented as
  exhaustive (`&s[0..4]` and `chars().take(4)` also slice), and W5-N04's validity depends on adapter
  ordering at `valuation_core_adapter.rs:349-364` refusing on `book_value` first.

---

## 6. Round 3 verdict and findings — ALL OPEN

**Verdict: `revise`. 5 anticipatory passes. 12 P0, 8 P1, 7 P2.**

**No plan.v3 was written.** The orchestrator was instructed to report and stop at the end of round 3.
Every finding below is **OPEN and unapplied**. This section is the work order for whoever writes
plan.v3.

Sensei's answers to the four priority questions:

- **Ruling Q2 (T3.8) — IMPLEMENTED, not merely acknowledged.** *"It fixes it… That is the strong
  form, not the worse-than-nothing form."* All three parts present, D2 states the one-kept-set
  property as the mechanism, K6/K9 pin it, T3.2 plants an outlier and asserts the reported width is
  the retained width. **Caveat that keeps it from being fully discharged: verified only at the
  producer** — see r3 P0-1.
- **Ruling Q1 (three-site `.abs()`) — IMPLEMENTED IN INTENT, VERIFIER BROKEN.** *"'One commit' is
  stated (T2.6) and J6 asserts it. **The verifier is broken and the enumeration is unsound.**"*
- **Two tests for the never-executed branch — NO**, and for a reason not previously seen: see P0-4.
- **The T2.7 guard ruling — diagnosis right, rule shape wrong**: see P0-6.
- **On being overruled (r2 P0-2): Sensei agrees it was the correct call.** *"My P0-2 was conditional
  on there being no consumer, and my own second-order note named the supplying of a consumer as the
  condition under which it must ship. Juan supplied one… the correct call was to overrule me."*
  Two caveats: P0-1 and P1-7.

### Round 3 P0s

| Id | Finding | Status |
|---|---|---|
| **r3 P0-1** | **The width now feeds `fuse` and the plan never states what `fuse` does with it.** If `SampleVariance { variance, observations }` and `fuse` divides by `observations`, then passing `variance_of_centre()` (already a squared standard error) *plus* `observations = retained()` **divides twice and overstates precision by ~n** — a larger weighting error than the one being removed, introduced by the fix, in the channel the fix is about. Requires quoting `UncertaintyBasis::SampleVariance`'s definition and `fuse`'s arithmetic, and an acceptance assert on the **fused weight**, not the two reported fields. Verification: `rg -n "SampleVariance" apps/windows/src-tauri/valuation-core/src`. | OPEN |
| **r3 P0-2** | **The Wave 2 fast-checks grep is vacuous.** J6 has the correct pattern; the **Fast checks** table — the thing a builder copies — has `grep -nE "interest.*(abs\(\)\|f64::abs)"`, and under `-E` GNU grep treats `\|` as a **literal pipe**, so it matches nothing and returns empty with all three sites intact. Markdown pipe-escaping is the mechanism; it looks correct rendered. Third appearance ("evidence of pass") asks for the naive blind pattern. **Also: the repo is PowerShell-primary and every acceptance check says `grep`** — name the tool. | OPEN |
| **r3 P0-3** | **The "three sites" enumeration rests on a search that could not have found a fourth.** §0 cites `grep -n "interest.*abs()" src/dcf_model.rs` — the pattern J6 proves blind, scoped to one file. Required re-establishment **by field name**: `rg -n "interest_expense_dollars" apps/windows/src-tauri/src` and `rg -n "f64::abs\|\.abs\(\)" apps/windows/src-tauri/src`. Named counterexample no regex can find: `let i = point.interest_expense_dollars; … i.abs()`. **Also a live contradiction**: the blast-radius table omits `quant_lens.rs` while the gate includes `quant_lens::` justified as *"reads driver series outside the FCFF bridge"* — both cannot be true. **(The Advisor independently ran the field-name sweep and found no fourth site — see the Advisor brief. The conclusion holds; the plan's evidence for it does not.)** | OPEN (evidence gap; conclusion independently confirmed) |
| **r3 P0-4** | **Removing the setter changes the meaning of every test that relied on it, and nobody audits them.** The plan uses the 10 `driver_resolution.rs` tests routing through the abs setter to prove the guard is dead — and never draws the consequence that T2.6 changes those tests' inputs underneath them. *"The plan searched for the sites that abs; it never searched for the sites that depended on the abs."* Requires `rg -n "with_operating_drivers"` over the tree and a per-site ruling. **(4b)** Same for Android: T2.5 moves Android's drivers with no executable cross-platform check. **Second-order (important): `valuation_baseline.rs:900` is a read site inside a file no wave may touch (§2.0)** — if its expectations move, Wave 2 deadlocks between "no fourth failing test" and "untouchable file". Pre-decide: stop and report to Juan. | OPEN |
| **r3 P0-5** | **T2.7's acceptance cannot prove the guard caused the refusal.** The issuer may refuse for an unrelated precondition and the assert passes while the branch never runs — on a branch with zero execution history. Fix: two asserts — the negative-year issuer refuses, and the *same* issuer with that year replaced by its absolute value **resolves**. | OPEN |
| **r3 P0-6** | **The T2.7 ruling is a one-sided proxy presented as a complete rule.** (a) Refusing on `interest < 0.0` is a predicate on a *value* standing in for a rule about *measurement basis*; it fires for net-**income** filers and **never fires for net-expense filers**, whose series is equally net and is then fitted as if gross — R2 violated by the estimator one layer below where R2 is enforced. Sensei concedes the basis rule is not implementable in W2 (`resolve_rate_inputs` reads `FcfPoint`, which D1 leaves provenance-free), so the sign rule is the right **approximation** — but the plan must say so and open **an LD for the net-expense-filer half**, trigger *"`FcfPoint` gains provenance (item 6)"*. (b) The fallback path is unnamed — if it is a constant or a floor, refusing a measured channel to land on a fabricated one breaches brief constraint 5. (c) Granularity unstated: *"refuse for that issuer"* needs a whole-series precondition, but `filter_map` is per-year — a builder may implement "return `None` for that year", **which is today's behaviour renamed**. (d) The 2×2 omits `debt == 0, interest > 0` and `debt <= 0`. | OPEN |
| **r3 P0-7** | **D7's only mechanical detector contradicts §6.4.** LD-4's detector (`:576`) says T4.5 forces the NCI-basis audit; §6.4 (`:2348`) lists that audit as out of scope. Recommended resolution: T4.5 rows 2 and 13 **declare the basis and record that the reconciling audit is undischarged**; the detector becomes checkable and consistent. | OPEN |
| **r3 P0-8** | **T4.5 is planned in a task and dropped from both deliverable lists.** Present at `:2017`, `:2057`, `:2443`; absent from Wave 4's Documentation deliverables (six items) and §5's consolidated table (four docs), while T4.8 and evidence-of-pass both say **seven**. *"This is v1's failure, verbatim, in the v2 section written to correct it."* | OPEN |
| **r3 P0-9** | **T5.12 licenses Wave 3 and runs two rounds after Wave 3 merges.** F1 carries the live-QA exemption for W3 and W5; T5.12 converts F1 into a compile-enforced proof but sits in W5/R3 while W3 ships in R1 — retroactive. Fix: move to an **orchestrator pre-flight before R1**, re-run at each round end. **And name the command**: `cargo check --workspace --all-features` (not `--all-targets`), plus separate confirmation that any `--all-targets` failures are in `#[cfg(test)]` modules. | OPEN |
| **r3 P0-10** | **Wave 2 moves published numbers with no pre-stated expectation.** Pause trigger (c) — *"refuses for more issuers than T2.7's ruling predicts"* — is uncheckable because T2.7 predicts no number. Three expectations are derivable now: (1) **direction and magnitude** — for a net-income filer published FCFF **falls by roughly `2 × interest × (1 − tax)`**, a double move; every other issuer is bit-identical; (2) **the affected set by name**, and **critically whether any of PG/GOOGL/AMZN/MSFT is in it** — GOOGL and MSFT are cash-rich and plausibly file net interest income, and BAC-class magnitudes make a ±5% anchor trigger certain; (3) **the predicted refusal set**, which includes issuers with **no net concept at all** (a gross concept filing a negative value is abs'd positive today and will trip the guard after T2.6 — an unbudgeted refusal source caused by LD-1's removal, not by the sign convention). Cheap mechanism: extend T1.7's probe to emit which qname supplied the interest series per issuer-year. | OPEN — **converges with Advisor r3 P0-1** |
| **r3 P0-11** | **§6.2 Rollout still carries the deleted premise.** *"No published-behaviour change is intended by any wave… Wave 2's is proved absent by T2.6"* — v1 text, false in v2, contradicting §0/J7/T2.6. *"This is the one sentence in the document that would let a reader conclude Wave 2 merges without live QA."* §6.2 and §6.1 also still carry the Q1-blocked paths; Q1 is answered. **Also: §6.2 and T4.7 both cite "the five sites in T2.8" for the fingerprint table — it is T2.9.** | OPEN |
| **r3 P0-12** | **§6.1's "mandatory" caching mitigation has no named mechanism.** The repo's only payload-cache artifact (`core_driver_data_deep.json`) is stale relative to `/8` and the brief forbids re-capturing it; T1.7 is a network probe. Name the mechanism or state honestly that the mitigation is timestamps plus stop-and-ask. | OPEN |

### Round 3 P1s (all OPEN)

- **P1-1** Zero retained variance among ≥3 survivors is an infinite fused weight at a legal count; only n=1/n=2 are proved unreachable.
- **P1-2** T3.4's index-mapping test passes under an off-by-one within the same issuer; plant outliers in **two** issuers at different flattened offsets.
- **P1-3** `observations` changes meaning (raw n → retained n) without changing name — the exact thing D1 refused to do.
- **P1-4** T5.11's R3 deferral leaves constraint 6 *"satisfied by convention"* for two rounds when a two-line edit to a file W1 already edits would close it in R1. If the deferral stands, say plainly it is a scheduling convenience accepted against a standing constraint.
- **P1-5** T3.7 merges two different refusal mechanisms into one number.
- **P1-6** K1's *"exactly one place in the workspace"* is Rust-scoped; constraint 6 is repo-level.
- **P1-7** T3.9's ban on the "no effect today" note is an **overcorrection** — having a caller is not having an effect; with the forward channel at `:547` absent, `fuse` reassigns no weight today. The honest doc says both.
- **P1-8** F18's "exactly one external caller" needs its pattern stated. *(Independently re-verified by the Advisor.)*

### Round 3 P2s (all OPEN)

T5.7 and W2-R01 lack the *"delete, do not weaken"* doc-comment instrument W5-E02 has; `dcf_model.rs:795` is a named risk with no ordered action; `-0.0` serialisation in W2-E02; T2.4's panic should be labelled intentional; T4.4 element 10 must decide now where the frozen copy lives (file at a recorded commit hash, never amended in place); **§6.4 still lists "LD-1 through LD-4"** though LD-1 is closed and LD-5/6/7 exist; pause trigger (b) says *"operating issuer"* but **COF is on the bank path and is the most-affected issuer**.

### What Sensei says v2 got right (do not churn)

§0 is *"the best section in the document"*; the J6 self-reported grep defect is the right instinct; T5.4's inverted correction rule closes a real spec-laundering path; removing the Kotlin `qnameSigns` default; **D7's detector column including the honest "no detector" rows**; T4.4's abstention-scored-not-dropped; the verification-claim header rule — *"it just needs to bind §0 as tightly as it binds everything else."*

### Sensei's standing open risks (beyond the findings)

Rust/Kotlin parity is structural and unmeasured; live-provider drift across four rounds is narrowed, not closed; T4.4's attestation is self-certified; **Wave 4 is one builder and seven documents in the last round with no downstream reviewer, controlled only by T4.8's checkpoints read by the same orchestrator that assigned the work**; and **the T2.7 sign rule remains a proxy — net-expense filers will be fitted on a net basis for the life of this design, and the numbers will look plausible.**
