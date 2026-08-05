# Three-arm probe: published value under `NetInterestPolicy::RefuseIssuerOnSign` vs `DropYearOnBasis`

Branch: `measure-guard-rules` (throwaway, never merges). This report is a
measurement, not a proposal. `NetInterestPolicy` is a diagnostic-only knob;
leaving it in shipped code is a defect (see doc comment on the enum in
`driver_resolution.rs`).

Raw probe output: `three-arm-published-value-raw.txt` (same directory).
Predecessor ground truth this report checks against:
`basis-versus-sign.md` / `basis-versus-sign-raw.txt` ("Probe H").

Retrieved 2026-08-05T00:27:24Z. Live risk-free 463bps as of Unix ts
1785869994. Universe: `VALUATION_ANCHORS ∪ INTEREST_SIGN_AFFECTED_COHORT ∪
PROBE_COHORT`, deduplicated = 52 distinct symbols. Fetched 51/52; `COF` is
`not_operating` (classified `FinancialServices`, out of scope for the fcff
guard rule this probe measures — not a fetch failure). No `unmeasurable`
issuers, no fetch failures.

All three arms (base / A / D) are computed from **one** fetch per issuer
(`fetch_fcf_history` called once; base reuses
`history_as_published_before_the_sign_correction` verbatim, A and D reuse the
same post-sign-correction history through
`compute_with_params_and_net_interest_policy` with the two `NetInterestPolicy`
variants).

## Fast-test baseline

`cargo test --lib` (unfiltered fast suite): 557 passed / 4 failed / 26 ignored
— identical pass/fail counts to the pre-existing base measured before this
session's changes (base was 557/4/25 ignored, with Probe H already present);
the only delta is `+1` ignored test, this session's new
`probe_published_value_under_net_interest_policies`. No new failures, no test
threshold or refusal path touched.

`cargo check --tests --lib` after the new code: clean for everything this
session added (`ThreeArmRow`, `NetInterestPolicy`, the new probe). The 17
warnings present in the raw output are pre-existing dead-code /
unused-variable warnings in `valuation_gap_attribution.rs` and `dcf_model.rs`
that predate this branch and are untouched by this work (verified by symbol
name — none reference `NetInterestPolicy`, `interest_is_net_basis`,
`ThreeArmRow`, or the new probe function).

## Per-issuer table

Columns: `yrs` = number of years with a measurable base/A/D delta (rows with
`yrs=0` are bit-identical across all three arms for every fiscal year in
history — printed for completeness, not because they moved). `c` = cents.
`d*` = delta vs base. `cod *` = the cost-of-debt channel's resolved rate or
refusal reason under that arm. `lane *` = which valuation lane the issuer
routed to (`sel:fcff`, `sel:fwd`, `disp:fwd`) under that arm. `flip` = which
arm(s), if any, changed lane vs base (`A`, `D`, `AD`, or `-`).

```
symbol   yrs    base c       A c       D c     dA c     dD c   dA bps   dD bps cod base                 cod A                    cod D                    lane b     lane A     lane D     flip 
PG         0     18109     18109     18109       +0       +0       +0       +0 273bps                   273bps                   273bps                   sel:fcff   sel:fcff   sel:fcff   -    
GOOGL      0     35679     35679     35679       +0       +0       +0       +0 245bps                   245bps                   245bps                   sel:fwd    sel:fwd    sel:fwd    -    
AMZN       0     16185     16185     16185       +0       +0       +0       +0 894bps                   894bps                   894bps                   sel:fwd    sel:fwd    sel:fwd    -    
MSFT       0     57139     57139     57139       +0       +0       +0       +0 362bps                   362bps                   362bps                   sel:fwd    sel:fwd    sel:fwd    -    
ABBV       1     41346     41346     41346       +0       +0       +0       +0 319bps                   REFUSED(filed interest is net of interest income in 2011, so gross interest expense is not measurable for this issuer) 319bps                   sel:fwd    sel:fwd    sel:fwd    -    
ADSK       6     30174     29739     29739     -435     -435     -144     -144 304bps                   REFUSED(filed interest is net of interest income in 2008,2009,2010,2011,2012,2013, so gross interest expense is not measurable for this issuer) REFUSED(no aligned market yield, spread, or SEC interest/debt periods) sel:fwd    sel:fwd    sel:fwd    -    
AXON      10     19362     18519     19362     -843       +0     -435       +0 520bps                   REFUSED(filed interest is net of interest income in 2011,2012,2013,2015,2017,2018,2019,2020,2021,2022, so gross interest expense is not measurable for this issuer) 520bps                   sel:fwd    sel:fwd    sel:fwd    -    
CARR       2      4511      4511      4511       +0       +0       +0       +0 322bps                   REFUSED(filed interest is net of interest income in 2018,2019, so gross interest expense is not measurable for this issuer) 322bps                   sel:fwd    sel:fwd    sel:fwd    -    
COR        1     54161     55434     55434    +1273    +1273     +235     +235 453bps                   REFUSED(filed interest is net of interest income in 2008, so gross interest expense is not measurable for this issuer) REFUSED(no aligned market yield, spread, or SEC interest/debt periods) sel:fwd    sel:fwd    sel:fwd    -    
CPRT       1      2271      2969      2248     +698      -23    +3074     -101 408bps                   REFUSED(filed interest is net of interest income in 2023, so gross interest expense is not measurable for this issuer) 309bps                   sel:fcff   sel:fwd    sel:fcff   A    
DDOG       1      3965      3965      3965       +0       +0       +0       +0 REFUSED(no aligned market yield, spread, or SEC interest/debt periods) REFUSED(filed interest is net of interest income in 2018, so gross interest expense is not measurable for this issuer) REFUSED(no aligned market yield, spread, or SEC interest/debt periods) sel:fwd    sel:fwd    sel:fwd    -    
JKHY       2     16460     14968     16325    -1492     -135     -906      -82 515bps                   REFUSED(filed interest is net of interest income in 2024,2025, so gross interest expense is not measurable for this issuer) 274bps                   sel:fcff   sel:fwd    sel:fcff   A    
MPWR      12     91657     91300     91300     -357     -357      -39      -39 n/a                      n/a                      n/a                      disp:fwd   disp:fwd   disp:fwd   -    
NKE        6      5492      5198      5198     -294     -294     -535     -535 141bps                   REFUSED(filed interest is net of interest income in 2009,2013,2023,2024,2025,2026, so gross interest expense is not measurable for this issuer) REFUSED(no aligned market yield, spread, or SEC interest/debt periods) sel:fcff   sel:fwd    sel:fwd    AD   
NWS        7      2054      2054      2054       +0       +0       +0       +0 297bps                   REFUSED(filed interest is net of interest income in 2012,2013,2014,2015,2016,2017,2025, so gross interest expense is not measurable for this issuer) REFUSED(no aligned market yield, spread, or SEC interest/debt periods) sel:fwd    sel:fwd    sel:fwd    -    
NWSA       7      2054      2054      2054       +0       +0       +0       +0 297bps                   REFUSED(filed interest is net of interest income in 2012,2013,2014,2015,2016,2017,2025, so gross interest expense is not measurable for this issuer) REFUSED(no aligned market yield, spread, or SEC interest/debt periods) sel:fwd    sel:fwd    sel:fwd    -    
OTIS       1      7955      8017      7955      +62       +0      +78       +0 236bps                   REFUSED(filed interest is net of interest income in 2018, so gross interest expense is not measurable for this issuer) 236bps                   sel:fcff   sel:fwd    sel:fcff   A    
PAYX       2     12102      8596     12102    -3506       +0    -2897       +0 459bps                   REFUSED(filed interest is net of interest income in 2012,2013, so gross interest expense is not measurable for this issuer) 459bps                   sel:fcff   sel:fwd    sel:fcff   A    
RMD        1     16670     24985     16670    +8315       +0    +4988       +0 250bps                   REFUSED(filed interest is net of interest income in 2025, so gross interest expense is not measurable for this issuer) 250bps                   sel:fcff   sel:fwd    sel:fcff   A    
ROL        3      3421      3421      3421       +0       +0       +0       +0 587bps                   REFUSED(filed interest is net of interest income in 2016,2017,2018, so gross interest expense is not measurable for this issuer) 587bps                   sel:fwd    sel:fwd    sel:fwd    -    
ROST       3     17767     18237     17488     +470     -279     +265     -157 505bps                   REFUSED(filed interest is net of interest income in 2024,2025,2026, so gross interest expense is not measurable for this issuer) 474bps                   sel:fwd    sel:fwd    sel:fwd    -    
TPR        3     10707      9863     10707     -844       +0     -788       +0 462bps                   REFUSED(filed interest is net of interest income in 2010,2011,2012, so gross interest expense is not measurable for this issuer) 462bps                   sel:fwd    sel:fwd    sel:fwd    -    
TTD        2      4339      4339      4339       +0       +0       +0       +0 REFUSED(no aligned market yield, spread, or SEC interest/debt periods) REFUSED(filed interest is net of interest income in 2019,2020, so gross interest expense is not measurable for this issuer) REFUSED(no aligned market yield, spread, or SEC interest/debt periods) sel:fwd    sel:fwd    sel:fwd    -    
TYL        1     23336     29676     23336    +6340       +0    +2717       +0 83bps                    REFUSED(filed interest is net of interest income in 2009, so gross interest expense is not measurable for this issuer) 83bps                    sel:fcff   sel:fwd    sel:fcff   A    
ULTA      10     68134     68788     68788     +654     +654      +96      +96 287bps                   REFUSED(filed interest is net of interest income in 2014,2015,2016,2017,2018,2019,2020,2023,2024,2025, so gross interest expense is not measurable for this issuer) REFUSED(no aligned market yield, spread, or SEC interest/debt periods) sel:fwd    sel:fwd    sel:fwd    -    
WSM        4     15941     15928     15928      -13      -13       -8       -8 542bps                   REFUSED(filed interest is net of interest income in 2023,2024,2025,2026, so gross interest expense is not measurable for this issuer) REFUSED(no aligned market yield, spread, or SEC interest/debt periods) sel:fwd    sel:fwd    sel:fwd    -    
XYZ        1      5979      5512      5979     -467       +0     -781       +0 427bps                   REFUSED(filed interest is net of interest income in 2016, so gross interest expense is not measurable for this issuer) 427bps                   sel:fwd    sel:fwd    sel:fwd    -    
YUM        1     19998     14241     19998    -5757       +0    -2879       +0 517bps                   REFUSED(filed interest is net of interest income in 2007, so gross interest expense is not measurable for this issuer) 517bps                   sel:fcff   sel:fwd    sel:fcff   A    
ZBRA       1     24069     25423     23987    +1354      -82     +563      -34 599bps                   REFUSED(filed interest is net of interest income in 2022, so gross interest expense is not measurable for this issuer) 617bps                   sel:fcff   sel:fwd    sel:fcff   A    
DVN        0      6766      6766      6766       +0       +0       +0       +0 573bps                   573bps                   573bps                   disp:fwd   disp:fwd   disp:fwd   -    
FIS        0      9112      9112      9112       +0       +0       +0       +0 360bps                   360bps                   360bps                   sel:fwd    sel:fwd    sel:fwd    -    
AVY        0     21380     21380     21380       +0       +0       +0       +0 722bps                   722bps                   722bps                   sel:fwd    sel:fwd    sel:fwd    -    
SW         0      6431      6431      6431       +0       +0       +0       +0 614bps                   614bps                   614bps                   disp:fwd   disp:fwd   disp:fwd   -    
APH        0     13107     13107     13107       +0       +0       +0       +0 335bps                   335bps                   335bps                   disp:fwd   disp:fwd   disp:fwd   -    
EME        0     81197     81197     81197       +0       +0       +0       +0 447bps                   447bps                   447bps                   sel:fwd    sel:fwd    sel:fwd    -    
CHTR       0     65952     65952     63663       +0    -2289       +0     -347 513bps                   513bps                   708bps                   sel:fwd    sel:fwd    disp:fwd   D    
BKR        0      3847      3847      6882       +0    +3035       +0    +7889 411bps                   411bps                   REFUSED(no aligned market yield, spread, or SEC interest/debt periods) sel:fcff   sel:fcff   sel:fwd    D    
INTU       0     57166     57166     57166       +0       +0       +0       +0 601bps                   601bps                   601bps                   sel:fwd    sel:fwd    sel:fwd    -    
TER        0     28869     28869     28869       +0       +0       +0       +0 1504bps                  1504bps                  1504bps                  disp:fwd   disp:fwd   disp:fwd   -    
AVGO       0     56334     56334     56334       +0       +0       +0       +0 475bps                   475bps                   475bps                   disp:fwd   disp:fwd   disp:fwd   -    
EPAM       0     18549     18549     18549       +0       +0       +0       +0 REFUSED(no aligned market yield, spread, or SEC interest/debt periods) REFUSED(no aligned market yield, spread, or SEC interest/debt periods) REFUSED(no aligned market yield, spread, or SEC interest/debt periods) sel:fwd    sel:fwd    sel:fwd    -    
T          0      4950      4950      4950       +0       +0       +0       +0 526bps                   526bps                   526bps                   disp:fwd   disp:fwd   disp:fwd   -    
GEHC       0      7180      7180      7180       +0       +0       +0       +0 440bps                   440bps                   440bps                   sel:fcff   sel:fcff   sel:fcff   -    
DAL        0     13451     13451     13451       +0       +0       +0       +0 816bps                   816bps                   816bps                   disp:fwd   disp:fwd   disp:fwd   -    
WDC        0     30649     30649     30649       +0       +0       +0       +0 432bps                   432bps                   432bps                   sel:fwd    sel:fwd    sel:fwd    -    
HPE        0      5242      5242      5242       +0       +0       +0       +0 222bps                   222bps                   222bps                   disp:fwd   disp:fwd   disp:fwd   -    
CRM        0     25645     25645     25645       +0       +0       +0       +0 376bps                   376bps                   376bps                   sel:fcff   sel:fcff   sel:fcff   -    
SLB        0      7581      7581      7581       +0       +0       +0       +0 468bps                   468bps                   468bps                   sel:fcff   sel:fcff   sel:fcff   -    
EXE        0      8994      8994      8994       +0       +0       +0       +0 412bps                   412bps                   412bps                   disp:fwd   disp:fwd   disp:fwd   -    
OMC        0     16186     16186     16186       +0       +0       +0       +0 REFUSED(aligned interest/debt implies invalid cost of debt) REFUSED(aligned interest/debt implies invalid cost of debt) REFUSED(aligned interest/debt implies invalid cost of debt) sel:fwd    sel:fwd    sel:fwd    -    
PTC        0     16480     16480     16480       +0       +0       +0       +0 642bps                   642bps                   642bps                   sel:fwd    sel:fwd    sel:fwd    -    
```

`fetched: 51/52  not_operating: [COF(FinancialServices)]  unmeasurable: []`

## Summaries

- **Movers under A**: 18 — `ADSK(-435c) AXON(-843c) COR(+1273c) CPRT(+698c)
  JKHY(-1492c) MPWR(-357c) NKE(-294c) OTIS(+62c) PAYX(-3506c) RMD(+8315c)
  ROST(+470c) TPR(-844c) TYL(+6340c) ULTA(+654c) WSM(-13c) XYZ(-467c)
  YUM(-5757c) ZBRA(+1354c)`
- **Movers under D**: 12 — `ADSK(-435c) COR(+1273c) CPRT(-23c) JKHY(-135c)
  MPWR(-357c) NKE(-294c) ROST(-279c) ULTA(+654c) WSM(-13c) ZBRA(-82c)
  CHTR(-2289c) BKR(+3035c)`
- **Lane flips under A**: 9 — `CPRT JKHY NKE OTIS PAYX RMD TYL YUM ZBRA`
- **Lane flips under D**: 3 — `NKE CHTR BKR`
- **Delta distribution under A (bps)**: n=18, min=-2897, **median=-24**,
  max=4988 (median, not a naked mean — no `sum/n` was computed anywhere in
  this probe; only counts and a `median` of a small discrete set were
  aggregated, so `valuation_core::robust_mean` was not needed here).
- **Delta distribution under D (bps)**: n=12, min=-535, **median=-60**,
  max=7889.
- **Issuers with a non-positive FCFF candidate in A or D**: 0 (none).

A = arm A (`RefuseIssuerOnSign`, byte-for-byte today's shipped behavior)
reproduces Probe G's original numbers exactly: 18 movers, 9 lane flips. That
internal cross-check gives confidence the three-arm harness's arm-A path is
faithful to the shipped guard rule, not a reimplementation that happens to
agree by coincidence.

## Q1-Q9 verdicts

| # | Claim | Verdict | Deciding number |
|---|---|---|---|
| Q1 | Anchors PG/GOOGL/AMZN/MSFT move $0.00 in both arms | **SURVIVED** | `moved=[]` — all four anchors show `dA c=+0, dD c=+0` |
| Q2 | CHTR and DAL move in D, bit-identical in A | **FALSIFIED** | CHTR: `dA=Some(0)c dD=Some(-2289)c` (matches half the claim); DAL: `dA=Some(0)c dD=Some(0)c` — DAL does not move in D at all |
| Q3 | CHTR and DAL move DOWN under D | **FALSIFIED** | CHTR moves down (-2289c), but DAL doesn't move at all (0c) — half the pair falsifies the claim |
| Q4 | Arm-D movers < arm-A's 18; point estimate 14-20; `>=18` or `<10` falsifies | **SURVIVED** | `movers_a=18 movers_d=12` — 12 is inside the falsification-avoiding range and below 18 |
| Q5 | Arm-D lane flips < arm-A's 9; point estimate <=5 | **SURVIVED** | `flips_a=9 flips_d=3` |
| Q6 | YUM/TYL/ABBV read a fitted rate under D (not REFUSED) | **SURVIVED** | `ABBV=319bps TYL=83bps YUM=517bps` |
| Q7 | COR and BKR are REFUSED under D | **SURVIVED** | `COR=REFUSED(no aligned market yield, spread, or SEC interest/debt periods)` `BKR=REFUSED(no aligned market yield, spread, or SEC interest/debt periods)` |
| Q8 | MPWR/TTD/DDOG are bit-identical between A and D (no filed debt) | **FALSIFIED** | Published cents ARE bit-identical for all three (`MPWR=91300c both, TTD=4339c both, DDOG=3965c both`), but the `cod` string differs for DDOG and TTD between arms (different REFUSED reasons — see "What surprised me" below); MPWR's `cod` is `n/a`/`n/a`, truly identical |
| Q9 | 24-vs-25 reconciliation | **Measured: 22 of 25** | 22 of 25 `INTEREST_SIGN_AFFECTED_COHORT` members lose a fitted accounting rate under A vs base: `[ABBV ADSK AXON CARR COR CPRT JKHY NKE NWS NWSA OTIS PAYX RMD ROL ROST TPR TYL ULTA WSM XYZ YUM ZBRA]`. This matches **neither** the W2b 24 nor the Probe H 25 reference figure. MPWR is confirmed **NOT** in this set. |

### Q9 detail — why 22 matches neither prior number

The measured 22 counts names whose `cod_base` carried a fitted rate (a
`...bps` string) **and** whose `cod_a` is `REFUSED`. It does not count names
that merely trip rule (A)'s net-year predicate (Probe H's 25) — that count
includes issuers whose predicate fires but whose `resolve_rate_inputs` never
reaches the net-year check at all, because it short-circuits earlier for an
unrelated reason (zero reported total debt). The three names in
`INTEREST_SIGN_AFFECTED_COHORT` excluded from the 22 are exactly `DDOG`,
`MPWR`, `TTD` — the same trio Q8 asks about. All three never had a fitted
`cod_base` to begin with: `MPWR`'s `cod_base` is `n/a` (zero total debt,
short-circuited before the net-year branch ever runs); `DDOG`'s and `TTD`'s
`cod_base` is `REFUSED(no aligned market yield, spread, or SEC
interest/debt periods)` (the accounting candidate set was already empty
before rule A's net-year filter mattered). You cannot lose a fitted rate you
never had, so this operationalization of "loses a fitted rate" correctly
excludes all three, landing at `25 - 3 = 22`. Neither the W2b 24 nor Probe
H's 25 used this exact definition, which is why 22 reconciles neither.

## ROL: registered-in-advance minimum-observation finding

`ROL` arm-D rate = **587bps fitted from 1 observation, fiscal year [2025]**.
No minimum-observation threshold exists in `resolve_rate_inputs_for_source`
today, and none was added by this probe. This is reported as a finding, not
fixed — per the brief, adding a threshold is explicitly out of scope for
this measurement.

## What surprised me

1. **DAL does not move at all, contradicting the Q2/Q3 pre-registration.**
   DAL's `cod` is `816bps` identically across base/A/D, and its lane
   (`disp:fwd`) never changes. Whatever basis fact made DAL a plausible
   candidate for movement in the pre-registration did not show up in this
   fetch — either DAL's winning interest concept for every fiscal year is
   gross (not net), or DAL never lands in the accounting candidate set for a
   reason unrelated to basis (its lane is `disp:fwd`, i.e. it is already
   routed off the fcff lane by something other than the cost-of-debt channel,
   so the cost-of-debt result is print-only and does not move the published
   number even where the underlying rate itself might theoretically differ).
   The claim was falsified plainly, not softened.

2. **Q8's "bit-identical" claim survives on published cents but not on the
   `cod` string, for DDOG and TTD specifically.** DDOG's and TTD's dollar
   output is identical between A and D (the guard rule doesn't reach the
   lane-selection decision either way — both arms route these two to
   `sel:fwd`/`disp:fwd` regardless), but the *reason string* differs: under A
   they're refused for a *sign* reason (net-of-income basis detected), while
   under D they're refused for a *different, upstream* reason (no aligned
   market yield, spread, or SEC interest/debt periods at all — an empty
   accounting set before basis is even consulted). This is a real behavioral
   difference in *why* the rate is unavailable, even though it happens not to
   move the number for these two issuers. Grading Q8 on cents alone would
   have called it SURVIVED; grading on the full `cod` output (as instructed
   — "cost-of-debt channel under each arm") correctly calls it FALSIFIED.

3. **Rule (D)'s deltas closely, and in four cases exactly, reproduce the old
   pre-T2.7 isolated-counterfactual reference numbers
   (`PRE_REGISTERED_MOVERS` in `probe_published_value_under_the_corrected_
   interest_sign`, R-13.1) — but not universally.** That constant records
   what the sign correction alone was measured to move, in an earlier probe,
   before T2.7's issuer-wide refusal rule existed:
   `ROST(-279) MPWR(-357) JKHY(-135) ULTA(-124) CPRT(-23) NKE(-12)`.
   This run's arm-D deltas: `ROST(-279) MPWR(-357) JKHY(-135) CPRT(-23)` are
   **exact bit-for-bit matches** to that old reference — four of six. But
   `ULTA` (arm D: **+654**, not -124) and `NKE` (arm D: **-294**, not -12)
   diverge sharply. The divergence has a clean explanation, not a mystery:
   both `ULTA` and `NKE` now have *more* net-basis years than at the time the
   old reference was measured (`ULTA` shows `yrs=10`, i.e. all ten of its
   in-history years are net-of-income; `NKE` has 6 net years spanning most of
   its history). Rule (D) drops every net year; when that leaves too few
   years to fit an accounting rate at all, the issuer gets refused under D
   too (`ULTA` and `NKE`'s `cod D` are both `REFUSED`), forcing the same
   `sel:fwd`/`disp:fwd` lane and value as arm A — which is why their D deltas
   match their A deltas exactly (`ULTA: dA=dD=+654`, `NKE: dA=dD=-294`)
   rather than the old, smaller isolated-counterfactual figures. In other
   words: rule (D) is not "the same as the old sign-only counterfactual" in
   general — it degrades toward rule (A)'s answer exactly when an issuer's
   net-year coverage has grown enough to exhaust its fittable set even after
   per-year dropping, which is a legitimate difference in the underlying SEC
   filing history since that older probe ran, not a bug in this
   measurement.

4. **Arm A reproduces Probe G's original 18-movers/9-flips exactly**, which
   is not something the brief asked to check but is a strong internal
   consistency signal that this harness's arm-A path is a faithful
   re-derivation of the shipped guard rule and not an independent
   reimplementation that happened to agree.

## Anything contradicting Probe H's basis table

Nothing in this run contradicts Probe H's basis-vs-sign findings. Points of
direct overlap that were re-confirmed here:

- `COR` and `BKR` are refused under (D) in both Probe H's per-year analysis
  and this run's `cod_d` (`COR=REFUSED(...)`, `BKR=REFUSED(no aligned market
  yield, spread, or SEC interest/debt periods)`), consistent with Probe H's
  finding that both are always-net filers with no gross year to fall back on
  once net years are dropped.
- `MPWR`/`TTD`/`DDOG` having zero filed per-year debt/interest pairs usable
  by the accounting candidate builder is consistent with Probe H's
  zero-filed-debt finding for these three, and explains both the Q8 nuance
  above and the Q9 exclusion set.
- `ROL`'s single fittable net year and single fittable gross year (Probe H)
  is exactly why arm D here fits it from **one** observation — this run
  reproduces that structural fact operationally rather than just
  structurally.

The one new data point this run adds beyond Probe H's static basis table is
behavioral, not structural: DAL, despite being part of the sign-affected
cohort Probe H's table describes, produces **zero** measurable movement
under any arm in an actual DCF run — Probe H's table records *basis*, not
*whether that basis reaches a published number*, and this run shows those
are not the same question for DAL.
