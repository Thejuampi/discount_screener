# Valuation economic contract

The canonical home of the latent-defect register: what the valuation rebuild knowingly does not fix
yet, named so that each item is never later mistaken for an oversight. Established by binding
decision D7 of the valuation PIT & contract run (`valuation/wave1-integration`), written by Wave 5
(`AD-VM-012` in `_bmad-output/planning-artifacts/valuation-model-family-architecture.md` links here
and summarizes it).

This document is **living**: its ownership belongs to this plan's successor run, and the register
grows as later work finds and defers new items. It does not belong embedded in a point-in-time
decision record that a later wave would otherwise have to keep re-editing.

---

## The latent-defect register

Every trigger below carries either a mechanical detector — a test, a probe, an assertion — or an
explicit human-review checkpoint with an owner. Where neither exists, the row says so plainly,
rather than implying an enforcement that is not there.

| Id | Defect | Why not now | Trigger | Detector |
| --- | --- | --- | --- | --- |
| ~~**LD-1**~~ | ~~blanket `.map(f64::abs)` on `interest_expense_dollars`~~ | **CLOSED BY WAVE 2.** Juan's Q1 ruling pulled it into scope; all three sites removed. | — | `W2-R01` fails if an `.abs()` is ever restored |
| **LD-2** | `resolve_capex_abs` returns a zero CapEx when no series exists (`edgar.rs:604-607`) — a real fabricated zero on the production FCF path. | On the production FCF path; would move published anchors. | Any wave that touches the CapEx-to-FCF bridge. | **Human review checkpoint**, owner below. No mechanical detector exists; stated plainly rather than implied. |
| **LD-3** | `operating_valuation::terminal_payout_bps` substitutes the cost of equity for an absent return on capital (`:223`) — the legacy FR-29. | Decision 2 allows the legacy engine to stay live during module-by-module replacement. | Retirement of the legacy engine, or the first router row whose decision inverts on the substitution. | Wave 5's `the_legacy_engine_still_substitutes_the_cost_of_equity_for_an_absent_return` fails the moment the substitution changes. The *router-inversion* half has **no** detector — human review. |
| **LD-4** | `stockholdersEquity`'s equivalence class mixes NCI-inclusive and NCI-exclusive concepts — one line, two measurement bases, under R2. | Out of this run's scope; changing it moves invested capital for every issuer with a material minority. | R2 is adopted (Wave 2); the audit is due before any invested-capital estimator is pre-registered against filed equity. | The target specification that pins "invested capital" cannot be written without resolving the NCI basis, so writing it forces the audit. |
| **LD-5** | `variance_of_centre` remains a mild understatement: the retained sample is narrower than the population it estimates. | The alternative — a MAD-based scale over the full sample — would describe a *different estimator* than the one that produced the point. The perverse monotone-in-contamination component is fixed (Wave 3); only the residual bias remains. | The first forward channel that fuses against the trailing channel. | **Human review checkpoint** at the point `fuse` gains a second live channel. No mechanical detector. |
| **LD-6** | `AnnualSeries::as_of` resolves single-concept drivers only; composed drivers (total debt, FCF, development) carry provenance but have no cutoff-aware resolution. | The rolling point-in-time harness is out of scope, and composing inside the vintage layer is its own design. | Construction of that harness. | **Mechanical**: `extract_driver_vintages` has no composed-driver caller, so the harness cannot be built without hitting this. |
| **LD-7** | `interest == 0` with `debt > 0` is dropped from the accounting cost-of-debt fit. Either a genuine zero-coupon situation or missing data; the two are not distinguished. | T2.7 ruled on the negative case and declined to re-adjudicate the zero case in the same wave. | Any wave that revisits `resolve_rate_inputs`. | **Human review checkpoint**, owner below. |
| ~~**LD-8**~~ | ~~T2.7's sign rule was one-sided: refusing on `interest < 0.0` caught net-**income** filers but never fired for a net-**expense** filer, whose `InterestIncomeExpenseNet` series is equally net and was fitted as though it were gross interest expense — understating the cost of debt for that issuer with a plausible-looking number.~~ | **CLOSED, at commit `f38fe2c`** (*"fix(cost-of-debt): a netted interest year is not a measurement of gross interest"*). `FcfPoint` now carries per-field concept provenance; `driver_resolution.rs` keys on the filed basis and `winning_qname_is_net_basis` reads `INTEREST_EXPENSE.qname_signs` per year — the precondition the original entry named as unimplementable is now implemented. | — | Regression on `driver_resolution.rs`'s basis-keyed resolution would reopen this; no new detector was added because the closure is structural, not a rule this register enforces going forward. |
| **LD-9** | `posterior::measured_sample_size` (`posterior.rs:119-125`) sums `basis().sample_size()` across channels without regard to variant, adding `SampleVariance{observations}` (periods) to `AnalystDispersion{analysts}` (people) to `Propagated{inputs}`. The fused posterior is then labelled with that sum. | Cosmetic today: nothing consumes `sample_size()` arithmetically — `fuse` weights by `Observation::precision()` = `1.0/variance` only. It becomes load-bearing the moment any consumer reads the fused basis as an `n`. | The first consumer that reads a fused `UncertaintyBasis` for anything but display. | **Mechanical**: any new call site of `UncertaintyBasis::sample_size()` outside `posterior.rs`. |
| **LD-10** | Rust and Android disagree on the sign of net interest from Wave 2b onward. Rust negates at extraction and keeps the sign; `DcfAnalysisEngine.kt:535-541` and `:802` still apply an unconditional `abs()`. A real divergence, introduced deliberately by shipping one platform and not the other. | Windows ships first; Android is ported once the Windows solution is good (scope ruling, 2026-08-04). Porting the behavioural half now would ship a change to a platform whose model is not yet settled. | The Android port begins. | **Mechanical**, two one-line greps, either sufficient: (a) `DcfAnalysisEngine.kt:537` / `:802` still call `abs()` on interest; (b) the parity fixture still contains zero negative-interest rows. |
| **LD-11** | The cross-platform parity suite is an exporter, not a check, and its comparator is wired to nothing. Both `#[test]`s in `cross_platform_parity.rs` write JSON and assert only `path.is_file()` and `members.len() == 20` — no value assertion anywhere. The real comparison, `scripts/compare-windows-android-valuation-parity.ps1`, is invoked by nothing but its own self-test. | Wiring a cross-platform comparator into CI requires an Android build in the loop — precisely what the scope ruling defers. | The Android port begins, or any claim that "parity passes" is made about this suite. | **Mechanical**: `grep` for `compare-windows-android-valuation-parity` outside `scripts/` and the spec docs returns nothing. |

**Owner for all open items:** the valuation quant workstream (this plan's successor run).

**Register-wide risk, load-bearing for two waves.** F1 (`valuation_core_adapter::value()` has no
non-test caller) is a point-in-time property carrying both Wave 3's and Wave 5's live-QA posture.
The first wiring of `value()` to production silently invalidates the reasoning behind both waves'
anchor expectations. T5.12 converted F1 from a grep into a compile-enforced proof — gating `value()`
behind `#[cfg(test)]` locally and confirming the crate still builds — and it held.

---

## FR-29 — an absent return refuses, by a named reason

Wave 5 removed the substitution `r := w` (return on capital defaults to the discount rate when
absent) from both the operating (`projection.rs`) and residual-income (`residual_income.rs`) forms.
An absent return now refuses with `AbsenceReason::EstimatorUnavailable`, distinct from
`NotReported` because the gap is in this Core's own evidence chain rather than in the filing. See
`AD-VM-012` for the full decision record.
