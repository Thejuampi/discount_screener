# Research: T-first DCF gap attribution; multi-name qualitative snapshot

**Status:** decision-grade evidence (recon)  
**Date:** 2026-07-30  
**Method:** shipped `dcf_model::discounted_fcff_fade` math + live SEC companyfacts (CIK 0000732717) + Yahoo quoteSummary fixture `T.json`  
**Anchors (dated snapshot):** weighted/mean Street ≈ **$30.02** (Yahoo `targetMeanPrice`); JPM note reference **~$33**; market fixture **~$21.12**. Model pre-calibration base often **~$46–$55** on PPE-CapEx FCF + soft WACC.

## Question

Is ~$46–$55 vs ~$29–$33 “Street wrong,” “we wrong,” or mixed? Prefer evidence, then calibrate drivers toward **weighted analyst mean** without output clamps.

## T driver decomposition

| Driver | Our pre-calibration path | Street-compatible (reverse) | Classification |
| --- | --- | ---: | --- |
| CapEx / FCF definition | OCF − multi-tag CapEx (`PaymentsToAcquireProductiveAssets` etc.). SEC FY2024: OCF **$38.77B** − CapEx **$20.26B** ⇒ FCF **~$18.5B**. FY2025: **~$19.4B**. | Reverse-DCF at soft WACC **~6.5%** needs FCF **~$12.5B** for $30 / **~$13.2B** for $33 | **Bug fixed earlier** (CapEx=0 → FCF≈OCF → $100+). Residual: PPE-only FCF still **above** Yahoo TTM FCF (**$8.85B**) and above reverse-implied FCF at soft rates |
| FCF run-rate choice | `latest` annual point only | Mid-cycle / normalized often used by sell-side | **Method** — peak/latest bias; secondary vs rates for T |
| WACC (soft/provisional) | CAPM CoE + default CoD (rf+spread), tax default, **debt weight capped 40%** when CoD default → WACC **~6.5–7%** | Same FCF **$18.5B** needs WACC **~8.25%** for $30 / **~8.0%** for $33 | **Provisional-input bias (primary remaining)** — soft rates ~**150–200 bps** cheap vs Street-implied |
| Growth near-term | Recent positive FCF window CAGR fade → g_stable | Telco models often low single-digit / fading | **Method** — secondary for mature T |
| g_stable | min(macro 3%, rf−1%, wacc−ε) | Similar Gordon identity | **Method / identity** — OK |
| Net debt | totalDebt − cash (Yahoo ~**$148B**) | Street may add leases/pension/other | **Unexplained residual / incomplete bridge** — not yet modeled |
| Shares | ~**6.95B** (fixture) | Same order | **OK** |
| Terminal share of EV | Dominant (5y fade + Gordon) | Same family | **Method** — amplifies rate errors |
| Output clamps | None (project law) | N/A | **Must not use** to close gap |

### Reverse-implied Street assumptions (engine-identical fade)

Holding shares/net debt from Yahoo fixture, g_near≈200 bps, g_stable=300 bps:

| Hold fixed | Solve for | Value for Street **$30.02** | Value for JPM **$33** |
| --- | --- | ---: | ---: |
| WACC = soft **654 bps** | FCF run-rate | **$12.48B** | **$13.21B** |
| FCF = **$18.5B** (OCF−CapEx) | WACC | **~825 bps** | **~797 bps** |

**Attribution of pre-calibration ~$46–$55 base:**

1. **Bug (fixed):** CapEx taxonomy miss → FCF≈OCF (was order **$100+**).  
2. **Provisional rates (primary open gap):** soft WACC understates Street-implied discount by **~+170 bps** on T-like FCF.  
3. **Method:** latest-year FCF; no lease debt; no spectrum-beyond-ProductiveAssets.  
4. **Residual after rate fix:** if base lands near $28–$33 on $18–19B FCF, residual vs $30 is **method noise / timing**, not “Street always right.” Yahoo TTM FCF $8.85B would **undershoot** Street (~$15 at soft WACC) — do **not** blindly adopt Yahoo FCF without reconciling definitions.

## Multi-name qualitative snapshot

| Profile | Soft-rate pathology | Expected bias vs Street |
| --- | --- | --- |
| **T-class** (high book debt, depressed equity, CoD default, debt-weight guard) | Soft WACC dragged by after-tax debt + provisional CoD | **Systematic overvaluation** of FCFF base |
| **Low-leverage operating** (debt weight small) | WACC ≈ CoE; uplift should scale with debt weight | Mild; not the T failure mode |
| **ACGL / financials** | Must stay **residual income**, never FCFF on float OCF | Separate contract; FCFF “fix” must not re-route financials |

Conclusion: gap is **not** “analysts wrong 100%.” Primary remaining economic error on T is **provisional discount-rate construction for levered issuers**, not a missing intrinsic/price cap.

## Development interpretation

- Weighted analyst mean is an **external development metric for measuring bias**, never an input read by valuation compute and never runtime provenance.
- The executable policy and acceptance live in `../implementation-artifacts/spec-dcf-street-calibration-provisional-wacc.md` and `../../shared/contracts/valuation-model-family.json`; this document remains attribution evidence.
- Analyst and model anchors remain parallel; material residual stays `Disputed`.

## Appendix: post-change T observation (2026-07-30)

Fixture call to `dcf_model::compute` (T-class fund + EDGAR OCF−ProductiveAssets FCF series):

| Metric | Value |
| --- | ---: |
| Base intrinsic | **$27.92** |
| Weighted Street (Yahoo mean) | **$30.02** |
| Gap base − Street | **−$2.10** |
| Pre-calibration soft band | ~$46–$55 |
| WACC (with uplift) | **829 bps** |
| Provisional uplift | **+175 bps** (full at debt-weight cap) |
| Latest fiscal FCF | **$19.44B** |
| FCF run-rate (latest contiguous window avg) | **$19.47B** |
| Bear / Bull | **$14.29 / $31.77** |

**T-specific verdict:** The primary T overstatement is attributable to our soft provisional WACC plus the earlier CapEx taxonomy bug. After driver/parameter fixes, base sits slightly below weighted Street; the residual remains methodological and visible—not a clamp and not a general proof across operating names.

## Sources / snapshots

- SEC companyfacts CIK0000732717 (fetched 2026-07-30 session): ProductiveAssets CapEx, OCF  
- `apps/windows/src-tauri/tests/fixtures/yahoo/quoteSummary/T.json` — price, targets, debt, cash, shares, beta, Yahoo FCF  
- Engine math: `dcf_model::fcff_wacc` / `discounted_fcff_fade`  
- JPM PT ~$33: operator-supplied research PDF reference (not re-fetched live in this artifact)  
- Live test capture: `t_gap_metrics base_cents=2750 … street_cents=3002`
