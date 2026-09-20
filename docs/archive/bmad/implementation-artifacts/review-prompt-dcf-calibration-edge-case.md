# Edge-case reviewer prompt

# Edge Case Hunter Review

**Goal:** You are a pure path tracer. Never comment on whether code is good or bad; only list missing handling.
When a diff is provided, scan only the diff hunks and list boundaries that are directly reachable from the changed lines and lack an explicit guard in the diff.
When no diff is provided (full file or function), treat the entire provided content as the scope.
Ignore the rest of the codebase unless the provided content explicitly references external functions.
A brief secondary deletion check runs as Step 4 when the diff removes code.

**Inputs:**
- **content** — Content to review: diff, full file, or function
- **also_consider** (optional) — Areas to keep in mind during review alongside normal edge-case analysis

**MANDATORY: Execute steps in the Execution section IN EXACT ORDER. DO NOT skip steps or change the sequence. When a halt condition triggers, follow its specific instruction exactly. Each action within a step is a REQUIRED action to complete that step.**

**Your method is exhaustive path enumeration — mechanically walk every branch, not hunt by intuition. Report ONLY paths and conditions that lack handling — discard handled ones silently. Do NOT editorialize or add filler. Do not assign severity labels, rankings, or priority levels.**


## EXECUTION

### Step 1: Receive Content

- Load the content to review strictly from the parent message that launched you (not from this instruction file)
- If content is empty, or cannot be decoded as text, return `[{"location":"N/A","trigger_condition":"Input empty or undecodable","guard_snippet":"Provide valid content to review","potential_consequence":"Review skipped — no analysis performed"}]` and stop
- Identify content type (diff, full file, or function) to determine scope rules

### Step 2: Exhaustive Path Analysis

**Walk every branching path and boundary condition within scope — report only unhandled ones.**

- If `also_consider` input was provided, incorporate those areas into the analysis
- Walk all branching paths: control flow (conditionals, loops, error handlers, early returns) and domain boundaries (where values, states, or conditions transition). Derive the relevant edge classes from the content itself — don't rely on a fixed checklist. Examples: missing else/default, unguarded inputs, off-by-one loops, arithmetic overflow, implicit type coercion, race conditions, timeout gaps
- Consider implicit branches: the diff special-cases or changes the handling of one or more members of a fixed set of values — enums, status codes, sentinels, type tags, flags, value ranges. The rest of the set is implicit branches (e.g. the diff changes the `RED` and `YELLOW` cases of a `RED`/`YELLOW`/`GREEN` enum; `GREEN` is the implicit branch)
- For each path: determine whether the content handles it
- Collect only the unhandled paths as findings — discard handled ones silently

### Step 3: Validate Completeness

- Revisit every edge class from Step 2 — e.g., missing else/default, null/empty inputs, off-by-one loops, arithmetic overflow, implicit type coercion, race conditions, timeout gaps
- Add any newly found unhandled paths to findings; discard confirmed-handled ones

### Step 4: Deletion Check

If the diff removed or replaced meaningful code (ignore pure renames and whitespace): load `references/deletion-check.md` and follow it.

### Step 5: Present Findings

Output all findings as a single JSON array following the Output Format specification exactly.


## OUTPUT FORMAT

Return ONLY a valid JSON array of objects. Each edge-case finding contains exactly these four fields:

```json
[{
  "location": "file:start-end (or file:line when single line, or file:hunk when exact line unavailable)",
  "trigger_condition": "one-line description (max 15 words)",
  "guard_snippet": "minimal code sketch that closes the gap (single-line escaped string, no raw newlines or unescaped quotes)",
  "potential_consequence": "what could actually go wrong (max 15 words)"
}]
```

No extra text, no explanations, no markdown wrapping. An empty array `[]` is valid when nothing is found. Deletion findings from Step 4, if any, go in the same array with the extra fields defined in `references/deletion-check.md`.


## HALT CONDITIONS

- If content is empty or cannot be decoded as text, return `[{"location":"N/A","trigger_condition":"Input empty or undecodable","guard_snippet":"Provide valid content to review","potential_consequence":"Review skipped — no analysis performed"}]` and stop
<reference path="references/deletion-check.md">
# Deletion Check

Secondary pass for the Edge Case Hunter — runs only when the diff removed meaningful code. Subordinate to the edge-case pass; findings are usually few or none.

For each chunk of removed or replaced code (ignore pure renames and whitespace), ask: did it carry behavior or a contract that the change neither re-established nor intentionally retired? Add a finding for any resulting regression, orphaned reference, or newly-dead code. Skip anything already covered by your edge-case findings.

Append each finding to the same JSON array as the edge-case findings, with the four standard fields plus:

- `kind`: `"deletion"`
- `confidence`: `"high"`, `"medium"`, or `"low"` — these are inferences; rate them

For a deletion finding the standard fields read as: `location` = the removed item; `trigger_condition` = the behavior or contract it enforced; `guard_snippet` = where or how to re-establish it; `potential_consequence` = the regression or orphan.

Add nothing if nothing qualifies.
</reference>

## CONTENT SOURCE

Load the review target from the parent message, or from a trailing `## REVIEW TARGET` section if present (offline fallback). This file has no `{review_content}` slot. If neither supplies content, treat content as empty and follow the empty-content halt rules above.


## REVIEW TARGET

# Review scope

This is the complete scoped diff for the approved DCF calibration hardening story, including its executable contracts, cross-platform implementation, UI diagnostics, compatibility correction, and durable artifacts. Installer-generated BMAD files and the independent TipRanks feature in the same dirty worktree are excluded because they are not part of this spec's review surface.

## TRACKED DIFF: apps/windows/src-tauri/src/dcf_model.rs

```diff
diff --git a/apps/windows/src-tauri/src/dcf_model.rs b/apps/windows/src-tauri/src/dcf_model.rs
index 92f32bc..a099b66 100644
--- a/apps/windows/src-tauri/src/dcf_model.rs
+++ b/apps/windows/src-tauri/src/dcf_model.rs
@@ -10,3 +10,5 @@ use crate::engine::FundamentalSnapshot;
 pub const ENGINE_VERSION: &str = "valuation-model-family/1";
-pub const MODEL_POLICY_VERSION: &str = "business-class-policy/1";
+/// Policy bump: provisional WACC uplift, normalized FCF run-rate, and robust growth scenarios.
+/// See `_bmad-output/implementation-artifacts/spec-dcf-street-calibration-provisional-wacc.md`.
+pub const MODEL_POLICY_VERSION: &str = "business-class-policy/2";
 
@@ -19,2 +21,14 @@ const DEFAULT_TAX_RATE_BPS: i32 = 2_100;
 const DEFAULT_COST_OF_DEBT_BPS: i32 = 550;
+/// When CoD is a policy default (no bond yield), floor credit spread over rf so
+/// levered names do not collapse WACC toward after-tax 4% debt alone.
+const DEFAULT_COD_SPREAD_OVER_RF_BPS: i32 = 300;
+/// Cap market-implied debt weight when CoD is default — depressed market caps
+/// circularly inflate D/(D+E), crush WACC, and inflate intrinsic (T ~$65 vs ~$29).
+/// Not a hard WACC floor; a capital-structure estimation guard for soft rates only.
+const PROVISIONAL_MAX_DEBT_WEIGHT: f64 = 0.40;
+/// When CoD is policy default, soft CAPM+structure WACC is systematically cheap vs
+/// Street-implied discount rates on levered operating names (T reverse-DCF ≈ +170 bps
+/// at the debt-weight cap). Full uplift applies at `PROVISIONAL_MAX_DEBT_WEIGHT`;
+/// scales linearly with debt weight. Not an intrinsic/price clamp.
+const PROVISIONAL_WACC_BASE_UPLIFT_BPS: i32 = 175;
 const DEFAULT_RETENTION_BPS: i32 = 7_000; // 70% retained when payout unknown
@@ -25,2 +39,11 @@ const PROJECTION_YEARS: i32 = 5;
 const COE_SCENARIO_BAND_BPS: i32 = 75;
+/// FCFF scenarios stress discount rate when rates are market-sourced.
+const WACC_SCENARIO_BAND_BPS: i32 = 100;
+/// After provisional base uplift, bear still stresses rates further (not symmetric).
+/// Pre-uplift soft + full uplift (~175) + bear band (~150) ≈ soft + 325 bps stress path.
+const WACC_SCENARIO_BEAR_BAND_UNRELIABLE_BPS: i32 = 150;
+/// When the base rate is already known-biased low (policy defaults), do **not**
+/// invent a still-cheaper bull WACC. Bull band = 0 ⇒ bull uses the same soft base
+/// WACC (growth stress only). Bear alone encodes further discount-rate understatement.
+const WACC_SCENARIO_BULL_BAND_UNRELIABLE_BPS: i32 = 0;
 const ROE_BEAR_HAIRCUT_BPS: i32 = 300;
@@ -28,2 +51,5 @@ const ROE_BULL_BOOST_BPS: i32 = 200;
 const GROWTH_RECENT_WINDOW: usize = 4;
+/// Robust recent-growth signal stays within a dynamic band around the macro
+/// stable rate. This constrains noisy endpoint CAGR inputs, not valuation output.
+const MAX_NEAR_GROWTH_DISTANCE_FROM_STABLE_BPS: i32 = 1_200;
 /// Real-rate buffer so g_stable < rf (Gordon headroom identity).
@@ -116,2 +142,56 @@ impl WaccInputProvenance {
     }
+
+    /// Point intrinsic must not be shown as a single “truth” number when CoD,
+    /// tax, beta, or market params (rf/ERP) come from policy defaults.
+    pub fn point_estimate_unreliable(&self) -> bool {
+        self.cost_of_debt == WaccFieldSource::Default
+            || self.wacc_clamped
+            || self.beta == WaccFieldSource::Default
+            || self.tax_rate == WaccFieldSource::Default
+    }
+}
+
+/// Raw model inputs for UI/debug (avoids archaeology on odd DCF prints).
+#[derive(Debug, Clone, Serialize, Deserialize, Default)]
+pub struct DcfDiagnostics {
+    /// Most recent fiscal FCF observation, never a normalized replacement.
+    pub latest_fcf_dollars: Option<i64>,
+    /// FCFF run-rate actually used by the valuation model.
+    #[serde(default)]
+    pub fcf_run_rate_dollars: Option<i64>,
+    pub shares_outstanding: Option<u64>,
+    pub cost_of_equity_bps: Option<i32>,
+    pub cost_of_debt_bps: Option<i32>,
+    pub after_tax_cost_of_debt_bps: Option<i32>,
+    pub equity_weight_bps: Option<i32>,
+    pub debt_weight_bps: Option<i32>,
+    /// Fiscal years aligned with `fcf_annual_dollars` (oldest → newest).
+    #[serde(default)]
+    pub fcf_years: Vec<i32>,
+    #[serde(default)]
+    pub fcf_annual_dollars: Vec<i64>,
+    /// When true, UI must not present base as a trusted point estimate.
+    #[serde(default)]
+    pub point_estimate_unreliable: bool,
+    /// `growth_and_discount_rate` | `growth_only` | `none`
+    #[serde(default = "default_scenario_stress")]
+    pub scenario_stress: String,
+    /// Fiscal years where CapEx was interpolated/carried (taxonomy gaps).
+    #[serde(default)]
+    pub capex_imputed_years: Vec<i32>,
+    /// Effective WACC used for bear / bull scenarios (bps).
+    #[serde(default)]
+    pub wacc_bear_bps: Option<i32>,
+    #[serde(default)]
+    pub wacc_bull_bps: Option<i32>,
+    /// Provisional WACC base uplift applied (bps); 0 when rates are market-solid.
+    #[serde(default)]
+    pub provisional_wacc_uplift_bps: Option<i32>,
+    /// True when FCFF run-rate used the recent-window average (normalized), not only latest.
+    #[serde(default)]
+    pub fcf_run_rate_normalized: bool,
+}
+
+fn default_scenario_stress() -> String {
+    "none".into()
 }
@@ -174,2 +254,4 @@ pub struct DcfAnalysis {
     pub reason_codes: Vec<String>,
+    #[serde(default)]
+    pub diagnostics: DcfDiagnostics,
 }
@@ -197,2 +279,14 @@ pub struct FcfPoint {
     pub value_dollars: f64,
+    /// True when CapEx for this year was interpolated/carried (not filed under known tags).
+    pub capex_imputed: bool,
+}
+
+impl FcfPoint {
+    pub fn new(year: i32, value_dollars: f64) -> Self {
+        Self {
+            year,
+            value_dollars,
+            capex_imputed: false,
+        }
+    }
 }
@@ -292,5 +386,9 @@ pub fn compute_with_params(
         }
-        BusinessClass::OperatingNonFinancial => {
-            fcff_wacc(fundamentals, fcf_history, market_price_cents, market_params, source)
-        }
+        BusinessClass::OperatingNonFinancial => fcff_wacc(
+            fundamentals,
+            fcf_history,
+            market_price_cents,
+            market_params,
+            source,
+        ),
     }
@@ -338,4 +436,3 @@ fn residual_income(
 
-    let (re_base, beta_source, beta_provisional) =
-        cost_of_equity_bps(fundamentals, market_params);
+    let (re_base, beta_source, beta_provisional) = cost_of_equity_bps(fundamentals, market_params);
     let retention = DEFAULT_RETENTION_BPS as f64 / 10_000.0;
@@ -364,2 +461,11 @@ fn residual_income(
 
+    let wacc_inputs = WaccInputProvenance {
+        market_cap: WaccFieldSource::Reported,
+        beta: beta_source,
+        total_debt: WaccFieldSource::Reported,
+        total_cash: WaccFieldSource::Reported,
+        cost_of_debt: WaccFieldSource::Reported,
+        tax_rate: WaccFieldSource::Reported,
+        wacc_clamped: beta_provisional || market_params.provisional,
+    };
     let mut reasons = vec![
@@ -368,2 +474,3 @@ fn residual_income(
         "terminal_roe_fades_to_cost_of_equity".into(),
+        "scenario_stress=growth_and_discount_rate".into(),
     ];
@@ -372,2 +479,6 @@ fn residual_income(
     }
+    if wacc_inputs.point_estimate_unreliable() {
+        reasons.push("point_estimate=unreliable".into());
+    }
+    let shares_u = fundamentals.shares_outstanding.filter(|&s| s > 0);
 
@@ -380,11 +491,3 @@ fn residual_income(
         net_debt_dollars: 0,
-        wacc_inputs: WaccInputProvenance {
-            market_cap: WaccFieldSource::Reported,
-            beta: beta_source,
-            total_debt: WaccFieldSource::Reported,
-            total_cash: WaccFieldSource::Reported,
-            cost_of_debt: WaccFieldSource::Reported,
-            tax_rate: WaccFieldSource::Reported,
-            wacc_clamped: beta_provisional || market_params.provisional,
-        },
+        wacc_inputs: wacc_inputs.clone(),
         source: source.to_string(),
@@ -395,3 +498,5 @@ fn residual_income(
         discount_rate_kind: DiscountRateKind::CostOfEquity,
-        stable_growth_bps: market_params.stable_growth_bps().min(re_base - GORDON_RATE_EPSILON_BPS),
+        stable_growth_bps: market_params
+            .stable_growth_bps()
+            .min(re_base - GORDON_RATE_EPSILON_BPS),
         book_value_per_share_cents: Some(bvps_cents),
@@ -399,2 +504,21 @@ fn residual_income(
         reason_codes: reasons,
+        diagnostics: DcfDiagnostics {
+            latest_fcf_dollars: None,
+            fcf_run_rate_dollars: None,
+            shares_outstanding: shares_u,
+            cost_of_equity_bps: Some(re_base),
+            cost_of_debt_bps: None,
+            after_tax_cost_of_debt_bps: None,
+            equity_weight_bps: Some(10_000),
+            debt_weight_bps: Some(0),
+            fcf_years: vec![],
+            fcf_annual_dollars: vec![],
+            point_estimate_unreliable: wacc_inputs.point_estimate_unreliable(),
+            scenario_stress: "growth_and_discount_rate".into(),
+            capex_imputed_years: vec![],
+            wacc_bear_bps: Some(re_base + COE_SCENARIO_BAND_BPS),
+            wacc_bull_bps: Some((re_base - COE_SCENARIO_BAND_BPS).max(market_params.rf_bps + 50)),
+            provisional_wacc_uplift_bps: Some(0),
+            fcf_run_rate_normalized: false,
+        },
     })
@@ -469,6 +593,7 @@ fn fcff_wacc(
     }
-    let latest = fcf_history
-        .last()
-        .and_then(|p| (p.value_dollars > 0.0).then_some(p.value_dollars))
-        .ok_or_else(|| "latest annual free cash flow is not positive".to_string())?;
+    let (run_rate, fcf_normalized) = fcf_run_rate_dollars(fcf_history)
+        .ok_or_else(|| "insufficient positive free cash flow for run-rate".to_string())?;
+    if run_rate <= 0.0 {
+        return Err("free cash flow run-rate is not positive".into());
+    }
     let shares = fundamentals
@@ -479,7 +604,5 @@ fn fcff_wacc(
 
-    let g_near = recent_fcf_growth_bps(fcf_history)
+    let raw_g_near = recent_fcf_growth_bps(fcf_history)
         .ok_or_else(|| "insufficient positive free cash flow history for growth".to_string())?;
-    let g_stable = market_params
-        .stable_growth_bps()
-        .min(DEFAULT_RF_BPS); // will be re-clamped vs WACC below
+    let g_stable = market_params.stable_growth_bps().min(DEFAULT_RF_BPS); // will be re-clamped vs WACC below
 
@@ -489,16 +612,69 @@ fn fcff_wacc(
 
-    let g_stable = g_stable
+    let g_stable_base = g_stable
         .min(resolved.wacc_bps - GORDON_RATE_EPSILON_BPS)
         .max(MIN_STABLE_GROWTH_BPS);
+    let g_near = raw_g_near.clamp(
+        g_stable_base - MAX_NEAR_GROWTH_DISTANCE_FROM_STABLE_BPS,
+        g_stable_base + MAX_NEAR_GROWTH_DISTANCE_FROM_STABLE_BPS,
+    );
 
-    // Scenario growth paths: fade from near-term toward stable
+    // Scenario paths: fade growth AND stress WACC.
+    // Provisional path: base already includes debt-scaled WACC uplift (see derive_wacc).
+    //   bear: +additional band from that base
+    //   bull: +0 bps on WACC (do not cheapen further a known-soft base; growth still varies)
+    let rates_unreliable = resolved.inputs.point_estimate_unreliable();
+    let (bear_band, bull_band) = if rates_unreliable {
+        (
+            WACC_SCENARIO_BEAR_BAND_UNRELIABLE_BPS,
+            WACC_SCENARIO_BULL_BAND_UNRELIABLE_BPS,
+        )
+    } else {
+        (WACC_SCENARIO_BAND_BPS, WACC_SCENARIO_BAND_BPS)
+    };
     let bear_near = (g_near - 400).max(-1_200);
     let bull_near = (g_near + 400).min(2_400);
+    let bear_wacc = resolved.wacc_bps + bear_band;
+    let bull_wacc = (resolved.wacc_bps - bull_band)
+        .max(market_params.rf_bps + 50)
+        .max(g_stable_base + GORDON_RATE_EPSILON_BPS);
+    let bear_g_stable = g_stable_base
+        .min(bear_wacc - GORDON_RATE_EPSILON_BPS)
+        .max(MIN_STABLE_GROWTH_BPS);
+    let bull_g_stable = g_stable_base
+        .min(bull_wacc - GORDON_RATE_EPSILON_BPS)
+        .max(MIN_STABLE_GROWTH_BPS);
+
+    let bear = discounted_fcff_fade(
+        run_rate,
+        shares,
+        net_debt,
+        bear_near,
+        bear_g_stable,
+        bear_wacc,
+    )
+    .ok_or_else(|| "bear scenario invalid".to_string())?;
+    let base = discounted_fcff_fade(
+        run_rate,
+        shares,
+        net_debt,
+        g_near,
+        g_stable_base,
+        resolved.wacc_bps,
+    )
+    .ok_or_else(|| "base scenario invalid".to_string())?;
+    let bull = discounted_fcff_fade(
+        run_rate,
+        shares,
+        net_debt,
+        bull_near,
+        bull_g_stable,
+        bull_wacc,
+    )
+    .ok_or_else(|| "bull scenario invalid".to_string())?;
 
-    let bear = discounted_fcff_fade(latest, shares, net_debt, bear_near, g_stable, resolved.wacc_bps)
-        .ok_or_else(|| "bear scenario invalid".to_string())?;
-    let base = discounted_fcff_fade(latest, shares, net_debt, g_near, g_stable, resolved.wacc_bps)
-        .ok_or_else(|| "base scenario invalid".to_string())?;
-    let bull = discounted_fcff_fade(latest, shares, net_debt, bull_near, g_stable, resolved.wacc_bps)
-        .ok_or_else(|| "bull scenario invalid".to_string())?;
+    let capex_imputed_years: Vec<i32> = fcf_history
+        .iter()
+        .filter(|p| p.capex_imputed)
+        .map(|p| p.year)
+        .collect();
 
@@ -508,3 +684,14 @@ fn fcff_wacc(
         "growth=recent_window_fade_to_stable".into(),
+        "scenario_stress=growth_and_discount_rate".into(),
     ];
+    if g_near != raw_g_near {
+        reasons.push(format!(
+            "growth=recent_window_robustified:raw={raw_g_near}:used={g_near}"
+        ));
+    }
+    if fcf_normalized {
+        reasons.push("fcf_run_rate=recent_window_average".into());
+    } else {
+        reasons.push("fcf_run_rate=latest_positive".into());
+    }
     if market_params.provisional {
@@ -512,2 +699,31 @@ fn fcff_wacc(
     }
+    if rates_unreliable {
+        reasons.push("point_estimate=unreliable".into());
+        // Explicit: bull WACC band is 0 so we do not further cheapen a soft base.
+        reasons.push(format!(
+            "wacc_stress=asymmetric_provisional_bear+{bear_band}_bull=base_no_further_cheapening"
+        ));
+    }
+    if resolved.provisional_wacc_uplift_bps > 0 {
+        reasons.push(format!(
+            "wacc=provisional_base_uplift:{}",
+            resolved.provisional_wacc_uplift_bps
+        ));
+    }
+    if !capex_imputed_years.is_empty() {
+        reasons.push(format!(
+            "capex=imputed_years:{}",
+            capex_imputed_years
+                .iter()
+                .map(|y| y.to_string())
+                .collect::<Vec<_>>()
+                .join(",")
+        ));
+    }
+
+    let fcf_years: Vec<i32> = fcf_history.iter().map(|p| p.year).collect();
+    let fcf_annual_dollars: Vec<i64> = fcf_history
+        .iter()
+        .map(|p| p.value_dollars.round() as i64)
+        .collect();
 
@@ -520,3 +736,3 @@ fn fcff_wacc(
         net_debt_dollars: net_debt,
-        wacc_inputs: resolved.inputs,
+        wacc_inputs: resolved.inputs.clone(),
         source: source.to_string(),
@@ -527,3 +743,3 @@ fn fcff_wacc(
         discount_rate_kind: DiscountRateKind::Wacc,
-        stable_growth_bps: g_stable,
+        stable_growth_bps: g_stable_base,
         book_value_per_share_cents: fundamentals.book_value_per_share_cents,
@@ -531,2 +747,27 @@ fn fcff_wacc(
         reason_codes: reasons,
+        diagnostics: DcfDiagnostics {
+            latest_fcf_dollars: fcf_history
+                .last()
+                .map(|point| point.value_dollars.round() as i64),
+            fcf_run_rate_dollars: Some(run_rate.round() as i64),
+            shares_outstanding: fundamentals.shares_outstanding.filter(|&s| s > 0),
+            cost_of_equity_bps: Some(resolved.cost_of_equity_bps),
+            cost_of_debt_bps: Some(resolved.cost_of_debt_bps),
+            after_tax_cost_of_debt_bps: Some(resolved.after_tax_cost_of_debt_bps),
+            equity_weight_bps: Some(resolved.equity_weight_bps),
+            debt_weight_bps: Some(resolved.debt_weight_bps),
+            fcf_years,
+            fcf_annual_dollars,
+            point_estimate_unreliable: rates_unreliable,
+            scenario_stress: if rates_unreliable {
+                "growth_and_discount_rate_asymmetric_provisional".into()
+            } else {
+                "growth_and_discount_rate".into()
+            },
+            capex_imputed_years,
+            wacc_bear_bps: Some(bear_wacc),
+            wacc_bull_bps: Some(bull_wacc),
+            provisional_wacc_uplift_bps: Some(resolved.provisional_wacc_uplift_bps),
+            fcf_run_rate_normalized: fcf_normalized,
+        },
     })
@@ -534,13 +775,50 @@ fn fcff_wacc(
 
+/// Latest contiguous positive FCF suffix (oldest → newest within the suffix).
+///
+/// Missing fiscal years end the window so sparse observations are not given the
+/// same weight as consecutive annual reports.
+fn recent_positive_fcf_window(history: &[FcfPoint]) -> Vec<&FcfPoint> {
+    let Some(latest) = history.last().filter(|point| point.value_dollars > 0.0) else {
+        return Vec::new();
+    };
+    let mut suffix = Vec::with_capacity(GROWTH_RECENT_WINDOW);
+    let mut expected_year = latest.year;
+    for point in history.iter().rev() {
+        if suffix.len() == GROWTH_RECENT_WINDOW
+            || point.value_dollars <= 0.0
+            || point.year != expected_year
+        {
+            break;
+        }
+        suffix.push(point);
+        expected_year = expected_year.saturating_sub(1);
+    }
+    suffix.reverse();
+    suffix
+}
+
+/// FCFF run-rate: average of positive FCF in the recent window (normalized mid-cycle).
+/// Returns (run_rate_dollars, used_average). Single positive point → that point, not averaged.
+fn fcf_run_rate_dollars(history: &[FcfPoint]) -> Option<(f64, bool)> {
+    let window = recent_positive_fcf_window(history);
+    if window.is_empty() {
+        return None;
+    }
+    if window.len() == 1 {
+        return Some((window[0].value_dollars, false));
+    }
+    let sum: f64 = window.iter().map(|p| p.value_dollars).sum();
+    let avg = sum / window.len() as f64;
+    if !avg.is_finite() || avg <= 0.0 {
+        return None;
+    }
+    Some((avg, true))
+}
+
 /// CAGR over the last up-to-GROWTH_RECENT_WINDOW positive FCF points (not full history).
 fn recent_fcf_growth_bps(history: &[FcfPoint]) -> Option<i32> {
-    let positive: Vec<&FcfPoint> = history.iter().filter(|p| p.value_dollars > 0.0).collect();
-    if positive.len() < 2 {
+    let window = recent_positive_fcf_window(history);
+    if window.len() < 2 {
         return None;
     }
-    let window = if positive.len() > GROWTH_RECENT_WINDOW {
-        &positive[positive.len() - GROWTH_RECENT_WINDOW..]
-    } else {
-        &positive[..]
-    };
     let first = window.first()?;
@@ -594,2 +872,8 @@ struct ResolvedWacc {
     wacc_bps: i32,
+    cost_of_equity_bps: i32,
+    cost_of_debt_bps: i32,
+    after_tax_cost_of_debt_bps: i32,
+    equity_weight_bps: i32,
+    debt_weight_bps: i32,
+    provisional_wacc_uplift_bps: i32,
     inputs: WaccInputProvenance,
@@ -644,3 +928,7 @@ fn cost_of_equity_bps(
     let re = market_params.rf_bps + (raw * market_params.erp_bps as f64).round() as i32;
-    (re.max(market_params.rf_bps + 50), source, provisional || market_params.provisional)
+    (
+        re.max(market_params.rf_bps + 50),
+        source,
+        provisional || market_params.provisional,
+    )
 }
@@ -687,7 +975,13 @@ fn derive_wacc(
     let base = market_cap + net_debt;
-    let equity_w = if base > 0.0 { market_cap / base } else { 1.0 };
-    let debt_w = if base > 0.0 { net_debt / base } else { 0.0 };
+    let mut equity_w = if base > 0.0 { market_cap / base } else { 1.0 };
+    let mut debt_w = if base > 0.0 { net_debt / base } else { 0.0 };
 
+    // No live bond yield → policy CoD. Prefer rf + spread over a bare constant so
+    // rates move with regime (still provisional / default provenance).
     let (cost_of_debt_bps, cost_of_debt_source) = if total_debt > 0.0 {
-        (DEFAULT_COST_OF_DEBT_BPS, WaccFieldSource::Default)
+        let from_spread = market_params.rf_bps + DEFAULT_COD_SPREAD_OVER_RF_BPS;
+        (
+            DEFAULT_COST_OF_DEBT_BPS.max(from_spread),
+            WaccFieldSource::Default,
+        )
     } else {
@@ -696,2 +990,12 @@ fn derive_wacc(
 
+    // Soft-rate capital structure: when CoD is not market-sourced, do not let a
+    // depressed equity price dominate weights (cheap stock → higher D% → lower
+    // WACC → even higher intrinsic). Cap debt weight; renormalize.
+    let mut structure_guard = false;
+    if cost_of_debt_source == WaccFieldSource::Default && debt_w > PROVISIONAL_MAX_DEBT_WEIGHT {
+        debt_w = PROVISIONAL_MAX_DEBT_WEIGHT;
+        equity_w = 1.0 - debt_w;
+        structure_guard = true;
+    }
+
     let tax_rate_bps = DEFAULT_TAX_RATE_BPS.clamp(0, 3_500);
@@ -701,3 +1005,14 @@ fn derive_wacc(
     let weighted = (equity_w * cost_of_equity_bps as f64) + (debt_w * after_tax_debt as f64);
-    let wacc_bps = weighted.round() as i32;
+    let soft_wacc_bps = weighted.round() as i32;
+
+    // Debt-scaled provisional base uplift: full at structure cap (~T reverse-DCF
+    // +170 bps). Low-leverage names get a small share — not a blanket haircut.
+    let provisional_wacc_uplift_bps =
+        if cost_of_debt_source == WaccFieldSource::Default && debt_w > 0.0 {
+            let scale = (debt_w / PROVISIONAL_MAX_DEBT_WEIGHT).clamp(0.0, 1.0);
+            (PROVISIONAL_WACC_BASE_UPLIFT_BPS as f64 * scale).round() as i32
+        } else {
+            0
+        };
+    let wacc_bps = soft_wacc_bps + provisional_wacc_uplift_bps;
 
@@ -705,2 +1020,8 @@ fn derive_wacc(
         wacc_bps,
+        cost_of_equity_bps,
+        cost_of_debt_bps,
+        after_tax_cost_of_debt_bps: after_tax_debt,
+        equity_weight_bps: (equity_w * 10_000.0).round() as i32,
+        debt_weight_bps: (debt_w * 10_000.0).round() as i32,
+        provisional_wacc_uplift_bps,
         inputs: WaccInputProvenance {
@@ -712,3 +1033,7 @@ fn derive_wacc(
             tax_rate: tax_rate_source,
-            wacc_clamped: beta_prov || market_params.provisional,
+            // Structure guard, uplift, and policy CoD/rf keep point estimate unreliable.
+            wacc_clamped: beta_prov
+                || market_params.provisional
+                || structure_guard
+                || provisional_wacc_uplift_bps > 0,
         },
@@ -722,2 +1047,3 @@ mod tests {
     use super::*;
+    use serde::Deserialize;
 
@@ -754,18 +1080,6 @@ mod tests {
         vec![
-            FcfPoint {
-                year: 2021,
-                value_dollars: 80_000_000.0,
-            },
-            FcfPoint {
-                year: 2022,
-                value_dollars: 90_000_000.0,
-            },
-            FcfPoint {
-                year: 2023,
-                value_dollars: 100_000_000.0,
-            },
-            FcfPoint {
-                year: 2024,
-                value_dollars: 110_000_000.0,
-            },
+            FcfPoint::new(2021, 80_000_000.0),
+            FcfPoint::new(2022, 90_000_000.0),
+            FcfPoint::new(2023, 100_000_000.0),
+            FcfPoint::new(2024, 110_000_000.0),
         ]
@@ -801,20 +1115,14 @@ mod tests {
         let fake_float_fcf = vec![
-            FcfPoint {
-                year: 2022,
-                value_dollars: 3_800_000_000.0,
-            },
-            FcfPoint {
-                year: 2023,
-                value_dollars: 5_700_000_000.0,
-            },
-            FcfPoint {
-                year: 2024,
-                value_dollars: 6_600_000_000.0,
-            },
-            FcfPoint {
-                year: 2025,
-                value_dollars: 6_172_000_000.0,
-            },
+            FcfPoint::new(2022, 3_800_000_000.0),
+            FcfPoint::new(2023, 5_700_000_000.0),
+            FcfPoint::new(2024, 6_600_000_000.0),
+            FcfPoint::new(2025, 6_172_000_000.0),
         ];
-        let a = compute(&acgl_like_fund(), &fake_float_fcf, Some(10_336), "sec_edgar").expect("ri");
+        let a = compute(
+            &acgl_like_fund(),
+            &fake_float_fcf,
+            Some(10_336),
+            "sec_edgar",
+        )
+        .expect("ri");
         assert_eq!(a.model, ValuationModel::ResidualIncomeEquity);
@@ -840,2 +1148,535 @@ mod tests {
         assert!(a.bull_intrinsic_value_cents >= a.base_intrinsic_value_cents);
+        assert!(
+            a.diagnostics
+                .scenario_stress
+                .contains("growth_and_discount_rate"),
+            "scenario_stress={}",
+            a.diagnostics.scenario_stress
+        );
+        assert!(a.diagnostics.latest_fcf_dollars.is_some());
+        assert!(a.diagnostics.cost_of_equity_bps.is_some());
+        assert!(a.diagnostics.shares_outstanding.is_some());
+        assert!(!a.diagnostics.fcf_annual_dollars.is_empty());
+        // Policy defaults → not a trusted point estimate.
+        assert!(a.diagnostics.point_estimate_unreliable);
+        assert!(a
+            .reason_codes
+            .iter()
+            .any(|r| r == "point_estimate=unreliable"));
+    }
+
+    #[test]
+    fn wacc_stress_widens_scenario_band_vs_growth_only_shape() {
+        // Bear WACC higher than base → bear value must sit below base even if growth equalized.
+        let a = compute(&operating_fund(), &sample_fcf(), Some(1_000), "sec_edgar").expect("dcf");
+        let span = a.bull_intrinsic_value_cents - a.bear_intrinsic_value_cents;
+        // With provisional asymmetric stress + growth, band should be material relative to base.
+        assert!(
+            span as f64 / a.base_intrinsic_value_cents as f64 > 0.08,
+            "expected wider scenario span with rate stress, span={span} base={}",
+            a.base_intrinsic_value_cents
+        );
+    }
+
+    #[test]
+    fn provisional_wacc_stress_is_asymmetric_and_reaches_market_like_bear() {
+        // Default path: base includes debt-scaled uplift; bear adds a further band.
+        let a = compute(&operating_fund(), &sample_fcf(), Some(1_000), "sec_edgar").expect("dcf");
+        assert!(a.diagnostics.point_estimate_unreliable);
+        let base_w = a.wacc_bps;
+        let bear_w = a.diagnostics.wacc_bear_bps.expect("bear wacc");
+        let bull_w = a.diagnostics.wacc_bull_bps.expect("bull wacc");
+        assert_eq!(bear_w - base_w, WACC_SCENARIO_BEAR_BAND_UNRELIABLE_BPS);
+        // Bull must not cheapen further: same WACC as base (band = 0).
+        assert_eq!(bull_w, base_w);
+        assert_eq!(WACC_SCENARIO_BULL_BAND_UNRELIABLE_BPS, 0);
+        // Combined soft-path stress (uplift on levered names + bear band) stays material.
+        let uplift = a.diagnostics.provisional_wacc_uplift_bps.unwrap_or(0);
+        assert!(
+            uplift + (bear_w - base_w) >= 150,
+            "expected material provisional rate stress, uplift={uplift} bear_band={}",
+            bear_w - base_w
+        );
+        assert!(a.reason_codes.iter().any(|r| {
+            r.contains("wacc_stress=asymmetric_provisional")
+                && r.contains("bull=base_no_further_cheapening")
+        }));
+    }
+
+    /// Pinned T-class snapshot (Yahoo fixture + SEC OCF−ProductiveAssets FCF).
+    /// Pre-calibration soft path overstated base vs weighted Street ~$30.
+    fn t_class_fund() -> FundamentalSnapshot {
+        FundamentalSnapshot {
+            symbol: "T".into(),
+            sector_name: Some("Communication Services".into()),
+            industry_name: Some("Telecom Services".into()),
+            market_cap_dollars: Some(146_748_915_712),
+            shares_outstanding: Some(6_948_338_835),
+            beta_millis: Some(422),
+            total_debt_dollars: Some(159_750_995_968),
+            total_cash_dollars: Some(11_964_000_256),
+            ..Default::default()
+        }
+    }
+
+    fn t_class_fcf_edgar_ppe() -> Vec<FcfPoint> {
+        // SEC ProductiveAssets path (not OCF alone; not Yahoo TTM).
+        vec![
+            FcfPoint::new(2021, 26_420_000_000.0),
+            FcfPoint::new(2023, 20_460_000_000.0),
+            FcfPoint::new(2024, 18_510_000_000.0),
+            FcfPoint::new(2025, 19_440_000_000.0),
+        ]
+    }
+
+    #[test]
+    fn t_class_base_moves_toward_weighted_analyst_without_clamp() {
+        // Weighted / mean Street from Yahoo quoteSummary fixture (targetMeanPrice).
+        let weighted_consensus_cents: i64 = 3_002; // $30.02
+        let a = compute(
+            &t_class_fund(),
+            &t_class_fcf_edgar_ppe(),
+            Some(2_112),
+            "sec_edgar",
+        )
+        .expect("t dcf");
+        assert_eq!(a.model, ValuationModel::FcffWacc);
+        assert!(a.diagnostics.point_estimate_unreliable);
+        assert!(
+            a.diagnostics.provisional_wacc_uplift_bps.unwrap_or(0) > 0,
+            "levered soft path must apply provisional WACC uplift"
+        );
+        assert!(
+            a.reason_codes
+                .iter()
+                .any(|r| r.starts_with("wacc=provisional_base_uplift:")),
+            "uplift provenance missing: {:?}",
+            a.reason_codes
+        );
+        assert!(
+            a.reason_codes
+                .iter()
+                .all(|r| !r.starts_with("calibration_target=")),
+            "Street is an external development metric, not runtime provenance: {:?}",
+            a.reason_codes
+        );
+
+        let base = a.base_intrinsic_value_cents;
+        eprintln!(
+            "t_gap_metrics base_cents={} base_dollars={:.2} street_cents={} gap_cents={} wacc_bps={} uplift_bps={:?} run_rate={:?} normalized={} bear={} bull={}",
+            base,
+            base as f64 / 100.0,
+            weighted_consensus_cents,
+            base - weighted_consensus_cents,
+            a.wacc_bps,
+            a.diagnostics.provisional_wacc_uplift_bps,
+            a.diagnostics.fcf_run_rate_dollars,
+            a.diagnostics.fcf_run_rate_normalized,
+            a.bear_intrinsic_value_cents,
+            a.bull_intrinsic_value_cents
+        );
+        // Materially closer to Street than the pre-calibration ~$46–$55 band.
+        assert!(
+            base < 4_000,
+            "base ${} still in pre-calibration overstatement band",
+            base as f64 / 100.0
+        );
+        // Residual must remain a model output — not assigned to Street.
+        assert_ne!(base, weighted_consensus_cents);
+        // Gap to weighted consensus smaller than gap from a $50 soft mirage.
+        let gap_to_street = (base - weighted_consensus_cents).abs();
+        let gap_from_old_mirage = (5_000_i64 - weighted_consensus_cents).abs();
+        assert!(
+            gap_to_street < gap_from_old_mirage,
+            "gap to Street {gap_to_street}c not improved vs old mirage; base={}",
+            base as f64 / 100.0
+        );
+        // Pinned residual band from the shared executable contract (not equality).
+        assert!(
+            base >= 2_500 && base <= 3_500,
+            "base ${} outside honest residual band",
+            base as f64 / 100.0
+        );
+    }
+
+    #[test]
+    fn fcff_does_not_clamp_intrinsic_to_price_or_street() {
+        // Extremely high FCF must still be allowed to produce high intrinsic —
+        // proves we did not add intrinsic/price or Street assignment clamps.
+        let fund = FundamentalSnapshot {
+            symbol: "RICH".into(),
+            sector_name: Some("Technology".into()),
+            industry_name: Some("Software".into()),
+            market_cap_dollars: Some(50_000_000_000),
+            shares_outstanding: Some(1_000_000_000),
+            beta_millis: Some(1_000),
+            total_debt_dollars: Some(5_000_000_000),
+            total_cash_dollars: Some(20_000_000_000),
+            ..Default::default()
+        };
+        let fat_fcf = vec![
+            FcfPoint::new(2021, 40_000_000_000.0),
+            FcfPoint::new(2022, 45_000_000_000.0),
+            FcfPoint::new(2023, 50_000_000_000.0),
+            FcfPoint::new(2024, 55_000_000_000.0),
+        ];
+        let a = compute(&fund, &fat_fcf, Some(5_000), "test").expect("dcf");
+        let base_dollars = a.base_intrinsic_value_cents as f64 / 100.0;
+        assert!(
+            base_dollars > 100.0,
+            "expected unclamped high intrinsic, got ${base_dollars}"
+        );
+        // No reason code that implies price-multiple rejection.
+        assert!(a
+            .reason_codes
+            .iter()
+            .all(|r| !r.contains("intrinsic_price") && !r.contains("clamp_to_street")));
+    }
+
+    #[test]
+    fn amzn_capex_trough_keeps_normalized_scenarios_ordered() {
+        let path = concat!(
+            env!("CARGO_MANIFEST_DIR"),
+            "/../../../shared/contracts/valuation-model-family.json"
+        );
+        let contract: ValuationContract =
+            serde_json::from_str(&std::fs::read_to_string(path).expect("read valuation contract"))
+                .expect("parse valuation contract");
+        let fixture = contract
+            .regression_fixtures
+            .iter()
+            .find(|fixture| fixture.name == "amzn_capex_trough_does_not_invert_fcff_scenarios")
+            .expect("AMZN contract fixture");
+        let inputs: ContractTInputs =
+            serde_json::from_value(fixture.sampled_inputs.clone()).expect("parse AMZN inputs");
+        let expected: ContractTExpected =
+            serde_json::from_value(fixture.expected.clone()).expect("parse AMZN expected");
+        let fund = FundamentalSnapshot {
+            symbol: "AMZN".into(),
+            sector_name: Some(inputs.sector_name.clone()),
+            industry_name: Some(inputs.industry_name.clone()),
+            market_cap_dollars: Some(inputs.market_cap_dollars),
+            shares_outstanding: Some(inputs.shares_outstanding),
+            beta_millis: Some(inputs.beta_millis),
+            total_debt_dollars: Some(inputs.total_debt_dollars),
+            total_cash_dollars: Some(inputs.total_cash_dollars),
+            ..Default::default()
+        };
+        let fcf: Vec<FcfPoint> = inputs
+            .fcf_annual_dollars
+            .iter()
+            .map(|point| FcfPoint::new(point.year, point.value_dollars))
+            .collect();
+        let analysis = compute(
+            &fund,
+            &fcf,
+            Some((inputs.market_price_dollars * 100.0).round() as i64),
+            "contract",
+        )
+        .expect("AMZN DCF");
+        assert_eq!(
+            analysis.diagnostics.latest_fcf_dollars,
+            Some(expected.latest_fcf_dollars)
+        );
+        assert_eq!(
+            analysis.diagnostics.fcf_run_rate_dollars,
+            Some(expected.fcf_run_rate_dollars)
+        );
+        assert!(
+            analysis.bear_intrinsic_value_cents <= analysis.base_intrinsic_value_cents
+                && analysis.base_intrinsic_value_cents <= analysis.bull_intrinsic_value_cents,
+            "scenario inversion: bear={} base={} bull={} growth={}",
+            analysis.bear_intrinsic_value_cents,
+            analysis.base_intrinsic_value_cents,
+            analysis.bull_intrinsic_value_cents,
+            analysis.base_growth_bps
+        );
+    }
+
+    #[test]
+    fn fcf_run_rate_uses_recent_window_average() {
+        let hist = vec![
+            FcfPoint::new(2021, 10_000_000.0),
+            FcfPoint::new(2022, 20_000_000.0),
+            FcfPoint::new(2023, 30_000_000.0),
+            FcfPoint::new(2024, 40_000_000.0),
+        ];
+        let (run, normalized) = fcf_run_rate_dollars(&hist).expect("run");
+        assert!(normalized);
+        assert!(
+            (run - 25_000_000.0).abs() < 1.0,
+            "avg of four = 25M, got {run}"
+        );
+        let a = compute(&operating_fund(), &hist, Some(1_000), "test").expect("dcf");
+        assert!(a.diagnostics.fcf_run_rate_normalized);
+        assert_eq!(a.diagnostics.latest_fcf_dollars, Some(40_000_000));
+        assert_eq!(a.diagnostics.fcf_run_rate_dollars, Some(25_000_000));
+        assert!(a
+            .reason_codes
+            .iter()
+            .any(|r| r == "fcf_run_rate=recent_window_average"));
+    }
+
+    #[test]
+    fn fcf_run_rate_uses_latest_contiguous_positive_suffix() {
+        let hist = vec![
+            FcfPoint::new(2021, 10_000_000.0),
+            FcfPoint::new(2023, 30_000_000.0),
+            FcfPoint::new(2024, 40_000_000.0),
+            FcfPoint::new(2025, 50_000_000.0),
+        ];
+        let (run, normalized) = fcf_run_rate_dollars(&hist).expect("run");
+        assert!(normalized);
+        assert!(
+            (run - 40_000_000.0).abs() < 1.0,
+            "missing 2022 must break the averaging window; got {run}"
+        );
+    }
+
+    #[test]
+    fn provisional_uplift_scales_monotonically_with_debt_weight() {
+        let with_debt = |debt: i64| FundamentalSnapshot {
+            symbol: format!("D{debt}"),
+            market_cap_dollars: Some(100_000_000_000),
+            shares_outstanding: Some(1_000_000_000),
+            total_debt_dollars: Some(debt),
+            total_cash_dollars: Some(0),
+            beta_millis: Some(1_000),
+            sector_name: Some("Industrials".into()),
+            industry_name: Some("Conglomerates".into()),
+            ..Default::default()
+        };
+        let fcf = vec![
+            FcfPoint::new(2021, 14_000_000_000.0),
+            FcfPoint::new(2022, 15_000_000_000.0),
+            FcfPoint::new(2023, 16_000_000_000.0),
+            FcfPoint::new(2024, 17_000_000_000.0),
+        ];
+        let low = compute(&with_debt(10_000_000_000), &fcf, Some(1_000), "test").expect("low");
+        let mid = compute(&with_debt(40_000_000_000), &fcf, Some(1_000), "test").expect("mid");
+        let capped =
+            compute(&with_debt(200_000_000_000), &fcf, Some(1_000), "test").expect("capped");
+        let uplifts = [
+            low.diagnostics.provisional_wacc_uplift_bps.unwrap(),
+            mid.diagnostics.provisional_wacc_uplift_bps.unwrap(),
+            capped.diagnostics.provisional_wacc_uplift_bps.unwrap(),
+        ];
+        assert!(uplifts[0] > 0 && uplifts[0] < uplifts[1]);
+        assert!(uplifts[1] < uplifts[2]);
+        assert_eq!(uplifts[2], PROVISIONAL_WACC_BASE_UPLIFT_BPS);
+    }
+
+    #[test]
+    fn solid_rates_use_symmetric_wacc_band() {
+        let mut params = MarketParams::default_usd();
+        params.provisional = false;
+        // Still tax/CoD default → unreliable. Force non-unreliable inputs via custom path:
+        // derive_wacc always defaults CoD/tax today, so point_estimate_unreliable stays true.
+        // Document that solid band requires non-default CoD+tax; until then asymmetric stands.
+        let a = compute_with_params(
+            &operating_fund(),
+            &sample_fcf(),
+            Some(1_000),
+            &params,
+            "test",
+            false,
+        )
+        .unwrap();
+        // cost_of_debt remains Default → still unreliable asymmetric.
+        assert!(a.diagnostics.point_estimate_unreliable);
+        assert_eq!(
+            a.diagnostics.wacc_bear_bps.unwrap() - a.wacc_bps,
+            WACC_SCENARIO_BEAR_BAND_UNRELIABLE_BPS
+        );
+    }
+
+    #[test]
+    fn capex_imputed_years_surface_in_diagnostics() {
+        let mut hist = sample_fcf();
+        hist[1].capex_imputed = true; // 2022
+        hist[2].capex_imputed = true; // 2023
+        let a = compute(&operating_fund(), &hist, Some(1_000), "sec_edgar").expect("dcf");
+        assert_eq!(a.diagnostics.capex_imputed_years, vec![2022, 2023]);
+        assert!(a
+            .reason_codes
+            .iter()
+            .any(|r| r.contains("capex=imputed_years:2022,2023")));
+    }
+
+    /// Highly levered + depressed equity must not crush WACC toward after-tax CoD.
+    #[test]
+    fn levered_provisional_wacc_caps_debt_weight() {
+        let fund = FundamentalSnapshot {
+            symbol: "T".into(),
+            sector_name: Some("Communication Services".into()),
+            industry_name: Some("Telecom Services".into()),
+            // Small equity vs huge debt → raw D/(D+E) >> 40%.
+            market_cap_dollars: Some(160_000_000_000),
+            shares_outstanding: Some(7_170_000_000),
+            beta_millis: Some(700),
+            total_debt_dollars: Some(150_000_000_000),
+            total_cash_dollars: Some(5_000_000_000),
+            ..Default::default()
+        };
+        // T-scale FCF (not the tiny sample_fcf fixture).
+        let fcf = vec![
+            FcfPoint::new(2021, 16_000_000_000.0),
+            FcfPoint::new(2022, 17_000_000_000.0),
+            FcfPoint::new(2023, 18_000_000_000.0),
+            FcfPoint::new(2024, 18_500_000_000.0),
+        ];
+        let a = compute(&fund, &fcf, Some(2_300), "sec_edgar").expect("dcf");
+        let dw = a.diagnostics.debt_weight_bps.expect("debt weight");
+        assert!(
+            dw <= (PROVISIONAL_MAX_DEBT_WEIGHT * 10_000.0).round() as i32 + 1,
+            "debt weight {dw} should respect provisional max"
+        );
+        // Soft path still unreliable (no live CoD).
+        assert!(a.diagnostics.point_estimate_unreliable);
+        // Soft blend + full provisional uplift at debt cap → clearly above after-tax CoD.
+        assert!(
+            a.wacc_bps >= 800,
+            "expected WACC ≥ 8% on levered provisional path, got {}",
+            a.wacc_bps
+        );
+        assert_eq!(
+            a.diagnostics.provisional_wacc_uplift_bps,
+            Some(PROVISIONAL_WACC_BASE_UPLIFT_BPS)
+        );
+    }
+
+    #[derive(Deserialize)]
+    #[serde(rename_all = "camelCase")]
+    struct ValuationContract {
+        policy2_adoption: Policy2Adoption,
+        regression_fixtures: Vec<ContractRegressionFixture>,
+    }
+
+    #[derive(Deserialize)]
+    #[serde(rename_all = "camelCase")]
+    struct Policy2Adoption {
+        executable_surfaces: Vec<String>,
+        deferred_surfaces: Vec<String>,
+    }
+
+    #[derive(Deserialize)]
+    struct ContractRegressionFixture {
+        name: String,
+        #[serde(rename = "sampledInputs")]
+        sampled_inputs: serde_json::Value,
+        expected: serde_json::Value,
+    }
+
+    #[derive(Deserialize)]
+    #[serde(rename_all = "camelCase")]
+    struct ContractTInputs {
+        market_price_dollars: f64,
+        weighted_analyst_mean_dollars: f64,
+        shares_outstanding: u64,
+        market_cap_dollars: u64,
+        beta_millis: i32,
+        total_debt_dollars: i64,
+        total_cash_dollars: i64,
+        sector_name: String,
+        industry_name: String,
+        fcf_annual_dollars: Vec<ContractFcfPoint>,
+    }
+
+    #[derive(Deserialize)]
+    #[serde(rename_all = "camelCase")]
+    struct ContractFcfPoint {
+        year: i32,
+        value_dollars: f64,
+    }
+
+    #[derive(Deserialize)]
+    #[serde(rename_all = "camelCase")]
+    struct ContractTExpected {
+        model_policy_version: String,
+        base_intrinsic_range_dollars: Option<[f64; 2]>,
+        latest_fcf_dollars: i64,
+        fcf_run_rate_dollars: i64,
+    }
+
+    #[test]
+    fn shared_t_contract_executes_against_windows_engine() {
+        let path = concat!(
+            env!("CARGO_MANIFEST_DIR"),
+            "/../../../shared/contracts/valuation-model-family.json"
+        );
+        let contract: ValuationContract =
+            serde_json::from_str(&std::fs::read_to_string(path).expect("read valuation contract"))
+                .expect("parse valuation contract");
+        assert!(contract
+            .policy2_adoption
+            .executable_surfaces
+            .iter()
+            .any(|surface| surface == "windows"));
+        assert!(contract
+            .policy2_adoption
+            .deferred_surfaces
+            .iter()
+            .any(|surface| surface == "desktop"));
+        let fixture = contract
+            .regression_fixtures
+            .iter()
+            .find(|fixture| {
+                fixture.name
+                    == "t_class_provisional_fcff_calibrates_toward_weighted_analyst_not_clamp"
+            })
+            .expect("T contract fixture");
+        let inputs: ContractTInputs =
+            serde_json::from_value(fixture.sampled_inputs.clone()).expect("parse T inputs");
+        let expected: ContractTExpected =
+            serde_json::from_value(fixture.expected.clone()).expect("parse T expected");
+        let fund = FundamentalSnapshot {
+            symbol: "T".into(),
+            sector_name: Some(inputs.sector_name.clone()),
+            industry_name: Some(inputs.industry_name.clone()),
+            market_cap_dollars: Some(inputs.market_cap_dollars),
+            shares_outstanding: Some(inputs.shares_outstanding),
+            beta_millis: Some(inputs.beta_millis),
+            total_debt_dollars: Some(inputs.total_debt_dollars),
+            total_cash_dollars: Some(inputs.total_cash_dollars),
+            ..Default::default()
+        };
+        let fcf: Vec<FcfPoint> = inputs
+            .fcf_annual_dollars
+            .iter()
+            .map(|point| FcfPoint::new(point.year, point.value_dollars))
+            .collect();
+        let analysis = compute(
+            &fund,
+            &fcf,
+            Some((inputs.market_price_dollars * 100.0).round() as i64),
+            "contract",
+        )
+        .expect("contract valuation");
+        assert_eq!(analysis.model_policy_version, expected.model_policy_version);
+        assert_eq!(
+            analysis.diagnostics.latest_fcf_dollars,
+            Some(expected.latest_fcf_dollars)
+        );
+        assert_eq!(
+            analysis.diagnostics.fcf_run_rate_dollars,
+            Some(expected.fcf_run_rate_dollars)
+        );
+        let base = analysis.base_intrinsic_value_cents as f64 / 100.0;
+        let range = expected
+            .base_intrinsic_range_dollars
+            .expect("T base intrinsic range");
+        assert!(
+            base >= range[0] && base <= range[1],
+            "base {base} outside contract range {range:?}"
+        );
+        let street = inputs.weighted_analyst_mean_dollars;
+        assert_ne!(base, street);
+        assert!(
+            (base - street).abs() < (50.0 - street).abs(),
+            "contract base did not improve on pre-policy soft mirage"
+        );
+        assert!(analysis
+            .reason_codes
+            .iter()
+            .all(|reason| !reason.starts_with("calibration_target=")));
     }
@@ -881,6 +1722,3 @@ mod tests {
         let err = compute(&f, &fake_fcf, Some(10_000), "test").unwrap_err();
-        assert!(
-            err.contains("book"),
-            "expected missing book, got {err}"
-        );
+        assert!(err.contains("book"), "expected missing book, got {err}");
     }
```

## TRACKED DIFF: apps/windows/src-tauri/src/engine.rs

```diff
diff --git a/apps/windows/src-tauri/src/engine.rs b/apps/windows/src-tauri/src/engine.rs
index 47e5184..06415a2 100644
--- a/apps/windows/src-tauri/src/engine.rs
+++ b/apps/windows/src-tauri/src/engine.rs
@@ -2242,2 +2242,8 @@ impl ScreenerState {
     pub fn ingest_dcf_analysis(&mut self, symbol: String, analysis: crate::dcf_model::DcfAnalysis) {
+        if analysis.engine_version != crate::dcf_model::ENGINE_VERSION
+            || analysis.model_policy_version != crate::dcf_model::MODEL_POLICY_VERSION
+        {
+            self.clear_dcf(&symbol);
+            return;
+        }
         // Never persist FCFF for financials — stale float-OCF DCFs (e.g. ACGL $875) must die here.
@@ -2273,2 +2279,9 @@ impl ScreenerState {
         };
+        let stale_policy = self.dcf_analyses.get(symbol).is_some_and(|analysis| {
+            analysis.engine_version != crate::dcf_model::ENGINE_VERSION
+                || analysis.model_policy_version != crate::dcf_model::MODEL_POLICY_VERSION
+        });
+        if stale_policy {
+            self.clear_dcf(symbol);
+        }
         let class = crate::dcf_model::classify_business(
@@ -2288,4 +2301,3 @@ impl ScreenerState {
                 a.model != crate::dcf_model::ValuationModel::ResidualIncomeEquity
-                    || a.business_class
-                        != crate::dcf_model::BusinessClass::FinancialServices
+                    || a.business_class != crate::dcf_model::BusinessClass::FinancialServices
                     || a.engine_version == "legacy"
@@ -2589,2 +2601,3 @@ mod valuation_routing_tests {
             reason_codes: vec![],
+            diagnostics: Default::default(),
         }
@@ -2649,2 +2662,31 @@ mod valuation_routing_tests {
     }
+
+    #[test]
+    fn reconciliation_drops_operating_analysis_from_stale_policy() {
+        let mut state = ScreenerState::new();
+        state.fundamentals.insert(
+            "OLD".into(),
+            FundamentalSnapshot {
+                symbol: "OLD".into(),
+                sector_name: Some("Industrials".into()),
+                industry_name: Some("Conglomerates".into()),
+                shares_outstanding: Some(1_000_000),
+                ..Default::default()
+            },
+        );
+        let mut stale = stale_fcff_acgl();
+        stale.business_class = BusinessClass::OperatingNonFinancial;
+        stale.model = ValuationModel::FcffWacc;
+        stale.engine_version = ENGINE_VERSION.into();
+        stale.model_policy_version = "business-class-policy/1".into();
+        state
+            .dcf_values
+            .insert("OLD".into(), stale.base_intrinsic_value_cents);
+        state.dcf_analyses.insert("OLD".into(), stale);
+
+        state.ensure_model_routed_valuation("OLD");
+
+        assert!(!state.dcf_analyses.contains_key("OLD"));
+        assert!(!state.dcf_values.contains_key("OLD"));
+    }
 }
```

## TRACKED DIFF: apps/windows/src-tauri/src/quant_lens.rs

```diff
diff --git a/apps/windows/src-tauri/src/quant_lens.rs b/apps/windows/src-tauri/src/quant_lens.rs
index 50e1a3b..939fd49 100644
--- a/apps/windows/src-tauri/src/quant_lens.rs
+++ b/apps/windows/src-tauri/src/quant_lens.rs
@@ -240,6 +240,3 @@ fn evidence_strength(
     if let Some(a) = dcf {
-        metrics.push((
-            "valuation_model".into(),
-            model_metric_label(a.model).into(),
-        ));
+        metrics.push(("valuation_model".into(), model_metric_label(a.model).into()));
         metrics.push((
@@ -284,5 +281,3 @@ fn expected_value_range(detail: &SymbolDetail, dcf: Option<&DcfAnalysis>) -> Qua
     let disagreement = match (model, analyst_ok) {
-        (Some(a), true) => {
-            relative_disagreement_bps(a.base_intrinsic_value_cents, analyst_base)
-        }
+        (Some(a), true) => relative_disagreement_bps(a.base_intrinsic_value_cents, analyst_base),
         _ => None,
@@ -408,2 +403,6 @@ fn expected_value_range(detail: &SymbolDetail, dcf: Option<&DcfAnalysis>) -> Qua
     if let Some(a) = model {
+        metrics.push((
+            "model_bear_cents".into(),
+            a.bear_intrinsic_value_cents.to_string(),
+        ));
         metrics.push((
@@ -413,9 +412,10 @@ fn expected_value_range(detail: &SymbolDetail, dcf: Option<&DcfAnalysis>) -> Qua
         metrics.push((
-            "model_upside_bps".into(),
-            upside_bps(price, a.base_intrinsic_value_cents).to_string(),
+            "model_bull_cents".into(),
+            a.bull_intrinsic_value_cents.to_string(),
         ));
         metrics.push((
-            "discount_rate_bps".into(),
-            a.wacc_bps.to_string(),
+            "model_upside_bps".into(),
+            upside_bps(price, a.base_intrinsic_value_cents).to_string(),
         ));
+        metrics.push(("discount_rate_bps".into(), a.wacc_bps.to_string()));
         metrics.push((
@@ -428,2 +428,72 @@ fn expected_value_range(detail: &SymbolDetail, dcf: Option<&DcfAnalysis>) -> Qua
         ));
+        // Diagnostics (detail header stays overview-only; Quant Lens owns depth).
+        let d = &a.diagnostics;
+        if d.point_estimate_unreliable || a.wacc_inputs.is_provisional() {
+            metrics.push(("rate_quality".into(), "provisional".into()));
+        }
+        let labels = a.wacc_inputs.summary_labels();
+        if !labels.is_empty() {
+            metrics.push(("wacc_provenance".into(), labels.join("; ")));
+        }
+        if let Some(fcf) = d.latest_fcf_dollars {
+            metrics.push(("latest_fcf_dollars".into(), fcf.to_string()));
+        }
+        if let Some(fcf) = d.fcf_run_rate_dollars {
+            metrics.push(("fcf_run_rate_dollars".into(), fcf.to_string()));
+        }
+        if !d.fcf_annual_dollars.is_empty() {
+            let series: Vec<String> = d
+                .fcf_years
+                .iter()
+                .zip(d.fcf_annual_dollars.iter())
+                .map(|(y, v)| format!("{y}:{:.1}B", *v as f64 / 1e9))
+                .collect();
+            // Cap width for UI — last 6 years of the series.
+            let tail = if series.len() > 6 {
+                &series[series.len() - 6..]
+            } else {
+                &series[..]
+            };
+            metrics.push(("fcf_series".into(), tail.join(" · ")));
+        }
+        if !d.capex_imputed_years.is_empty() {
+            metrics.push((
+                "capex_imputed_years".into(),
+                d.capex_imputed_years
+                    .iter()
+                    .map(|y| y.to_string())
+                    .collect::<Vec<_>>()
+                    .join(","),
+            ));
+        }
+        metrics.push(("net_debt_dollars".into(), a.net_debt_dollars.to_string()));
+        if let Some(sh) = d.shares_outstanding {
+            metrics.push(("shares_outstanding".into(), sh.to_string()));
+        }
+        metrics.push(("g_near_bps".into(), a.base_growth_bps.to_string()));
+        metrics.push(("g_stable_bps".into(), a.stable_growth_bps.to_string()));
+        if let Some(re) = d.cost_of_equity_bps {
+            metrics.push(("cost_of_equity_bps".into(), re.to_string()));
+        }
+        if let Some(rd) = d.cost_of_debt_bps {
+            metrics.push(("cost_of_debt_bps".into(), rd.to_string()));
+        }
+        if let Some(at) = d.after_tax_cost_of_debt_bps {
+            metrics.push(("after_tax_cod_bps".into(), at.to_string()));
+        }
+        if let Some(ew) = d.equity_weight_bps {
+            metrics.push(("equity_weight_bps".into(), ew.to_string()));
+        }
+        if let Some(dw) = d.debt_weight_bps {
+            metrics.push(("debt_weight_bps".into(), dw.to_string()));
+        }
+        if let Some(wb) = d.wacc_bear_bps {
+            metrics.push(("wacc_bear_bps".into(), wb.to_string()));
+        }
+        if let Some(wu) = d.wacc_bull_bps {
+            metrics.push(("wacc_bull_bps".into(), wu.to_string()));
+        }
+        if !d.scenario_stress.is_empty() && d.scenario_stress != "none" {
+            metrics.push(("scenario_stress".into(), d.scenario_stress.clone()));
+        }
         if a.business_class == BusinessClass::FinancialServices {
@@ -745,3 +815,3 @@ mod tests {
             company_name: Some("Tesla".into()),
-            market_price_cents: 31_200, // ~$312
+            market_price_cents: 31_200,    // ~$312
             intrinsic_value_cents: 38_150, // analyst ~$381.5 → gap ~22%
@@ -809,2 +879,3 @@ mod tests {
             reason_codes: vec![],
+            diagnostics: Default::default(),
         }
@@ -852,3 +923,5 @@ mod tests {
         assert!(
-            ev_only.status == "Mixed" || ev_only.status == "Sparse" || ev_only.status == "Provisional",
+            ev_only.status == "Mixed"
+                || ev_only.status == "Sparse"
+                || ev_only.status == "Provisional",
             "status={}",
@@ -857,3 +930,6 @@ mod tests {
         assert!(
-            ev_only.metrics.iter().any(|(k, v)| k == "conflict" && v != "0"),
+            ev_only
+                .metrics
+                .iter()
+                .any(|(k, v)| k == "conflict" && v != "0"),
             "expected conflict when model and analyst diverge hard"
@@ -942,2 +1018,3 @@ mod tests {
             reason_codes: vec![],
+            diagnostics: Default::default(),
         };
```

## TRACKED DIFF: apps/windows/src/api.ts

```diff
diff --git a/apps/windows/src/api.ts b/apps/windows/src/api.ts
index 19702a8..389151b 100644
--- a/apps/windows/src/api.ts
+++ b/apps/windows/src/api.ts
@@ -294,2 +294,3 @@ export type ForecastPanelState =
   | "empty"
+  | "unloaded"
   | "missing_key"
@@ -297,2 +298,3 @@ export type ForecastPanelState =
   | "quota_exhausted"
+  | "rate_limited"
   | "provider_unavailable"
@@ -300,2 +302,6 @@ export type ForecastPanelState =
 
+export type CacheFreshness = "fresh" | "aging" | "stale";
+export type ObservationFreshness = "current" | "aging" | "stale" | "empty";
+export type ForecastActionKind = "none" | "load" | "refresh";
+
 export interface ForecastObservation {
@@ -313,2 +319,5 @@ export interface ForecastObservation {
   identity: string | null;
+  stars_hundredths: number | null;
+  rank: number | null;
+  weight_hundredths: number | null;
 }
@@ -334,4 +343,4 @@ export interface ForecastPricePoint {
 
-export interface FmpQuotaView {
-  provider_day: string;
+export interface TipRanksQuotaView {
+  provider_month: string;
   attempts: number;
@@ -341,3 +350,14 @@ export interface FmpQuotaView {
   exhausted: boolean;
+  estimated: boolean;
   resets_at_epoch: number;
+  retry_after_epoch: number | null;
+}
+
+export interface ForecastAction {
+  kind: ForecastActionKind;
+  enabled: boolean;
+  call_cost: number;
+  remaining_after: number;
+  label: string;
+  confirmation_message: string | null;
 }
@@ -355,2 +375,5 @@ export interface AnalystForecastPanel {
   fetched_at_epoch: number | null;
+  latest_observation_epoch: number | null;
+  cache_freshness: CacheFreshness | null;
+  observation_freshness: ObservationFreshness;
   from_cache: boolean;
@@ -358,8 +381,10 @@ export interface AnalystForecastPanel {
   provider_label: string;
-  quota: FmpQuotaView;
+  quota: TipRanksQuotaView;
+  action: ForecastAction;
+  error_banner: string | null;
 }
 
-export interface FmpSettingsStatus {
+export interface TipRanksSettingsStatus {
   configured: boolean;
-  quota: FmpQuotaView;
+  quota: TipRanksQuotaView;
 }
@@ -427,2 +452,22 @@ export type DiscountRateKind = "wacc" | "cost_of_equity";
 
+export interface DcfDiagnostics {
+  latest_fcf_dollars?: number | null;
+  fcf_run_rate_dollars?: number | null;
+  shares_outstanding?: number | null;
+  cost_of_equity_bps?: number | null;
+  cost_of_debt_bps?: number | null;
+  after_tax_cost_of_debt_bps?: number | null;
+  equity_weight_bps?: number | null;
+  debt_weight_bps?: number | null;
+  fcf_years?: number[];
+  fcf_annual_dollars?: number[];
+  point_estimate_unreliable?: boolean;
+  scenario_stress?: string;
+  capex_imputed_years?: number[];
+  wacc_bear_bps?: number | null;
+  wacc_bull_bps?: number | null;
+  provisional_wacc_uplift_bps?: number | null;
+  fcf_run_rate_normalized?: boolean;
+}
+
 export interface DcfAnalysis {
@@ -454,2 +499,3 @@ export interface DcfAnalysis {
   reason_codes?: string[];
+  diagnostics?: DcfDiagnostics;
 }
@@ -515,7 +561,10 @@ export const api = {
     invoke<AnalystForecastPanel>("get_analyst_forecasts", { symbol }),
-  fmpSettingsStatus: () => invoke<FmpSettingsStatus>("fmp_settings_status"),
-  fmpSaveKey: (apiKey: string) =>
-    invoke<FmpSettingsStatus>("fmp_save_key", { apiKey }),
-  fmpDeleteKey: () => invoke<FmpSettingsStatus>("fmp_delete_key"),
-  fmpTestKey: () => invoke<AnalystForecastPanel>("fmp_test_key"),
+  loadAnalystForecasts: (symbol: string) =>
+    invoke<AnalystForecastPanel>("load_analyst_forecasts", { symbol }),
+  tipranksSettingsStatus: () =>
+    invoke<TipRanksSettingsStatus>("tipranks_settings_status"),
+  tipranksSaveKey: (apiKey: string) =>
+    invoke<TipRanksSettingsStatus>("tipranks_save_key", { apiKey }),
+  tipranksDeleteKey: () => invoke<TipRanksSettingsStatus>("tipranks_delete_key"),
+  tipranksTestKey: () => invoke<AnalystForecastPanel>("tipranks_test_key"),
   getCandles: (symbol: string, range: string) => invoke<Candle[]>("get_candles", { symbol, range }),
```

## TRACKED DIFF: apps/windows/src/components/QuantLensPanel.tsx

```diff
diff --git a/apps/windows/src/components/QuantLensPanel.tsx b/apps/windows/src/components/QuantLensPanel.tsx
index d1d5874..fe314ea 100644
--- a/apps/windows/src/components/QuantLensPanel.tsx
+++ b/apps/windows/src/components/QuantLensPanel.tsx
@@ -102,3 +102,5 @@ function metricLabel(key: string): string {
     upside_bps: "vs price",
+    model_bear_cents: "model bear",
     model_base_cents: "model base",
+    model_bull_cents: "model bull",
     model_upside_bps: "model vs price",
@@ -107,4 +109,22 @@ function metricLabel(key: string): string {
     model_analyst_diverge_bps: "model↔analyst",
-    discount_rate_bps: "discount rate",
+    discount_rate_bps: "WACC / rₑ",
     discount_rate_kind: "rate kind",
+    rate_quality: "rate quality",
+    wacc_provenance: "WACC inputs",
+    latest_fcf_dollars: "FCF latest fiscal",
+    fcf_run_rate_dollars: "FCF run-rate",
+    fcf_series: "FCF series",
+    capex_imputed_years: "CapEx imputed",
+    net_debt_dollars: "net debt",
+    shares_outstanding: "shares",
+    g_near_bps: "g near",
+    g_stable_bps: "g stable",
+    cost_of_equity_bps: "rₑ",
+    cost_of_debt_bps: "r_d",
+    after_tax_cod_bps: "r_d after-tax",
+    equity_weight_bps: "equity weight",
+    debt_weight_bps: "debt weight",
+    wacc_bear_bps: "bear WACC",
+    wacc_bull_bps: "bull WACC",
+    scenario_stress: "scenarios",
     bvps_cents: "BVPS",
@@ -117,3 +137,9 @@ function formatMetric(key: string, value: string): string {
   if (value === "n/a" || value === "null" || value === "—") return value;
-  if (key.endsWith("_cents") || key === "low_cents" || key === "base_cents" || key === "high_cents" || key === "bvps_cents") {
+  if (
+    key.endsWith("_cents")
+    || key === "low_cents"
+    || key === "base_cents"
+    || key === "high_cents"
+    || key === "bvps_cents"
+  ) {
     const n = Number(value);
@@ -121,9 +147,43 @@ function formatMetric(key: string, value: string): string {
   }
-  if (key.endsWith("_bps") || key === "gap_bps" || key === "upside_bps" || key === "scenario_width_bps") {
+  if (
+    key === "latest_fcf_dollars"
+    || key === "fcf_run_rate_dollars"
+    || key === "net_debt_dollars"
+  ) {
     const n = Number(value);
-    if (Number.isFinite(n)) return `${(n / 100).toFixed(1)}%`;
+    if (Number.isFinite(n)) {
+      const abs = Math.abs(n);
+      if (abs >= 1e9) return `$${(n / 1e9).toFixed(1)}B`;
+      if (abs >= 1e6) return `$${(n / 1e6).toFixed(0)}M`;
+      return `$${n.toFixed(0)}`;
+    }
   }
-  if (key === "discount_rate_bps" || key === "roe0_bps") {
+  if (key === "shares_outstanding") {
     const n = Number(value);
-    if (Number.isFinite(n)) return `${(n / 100).toFixed(2)}%`;
+    if (Number.isFinite(n) && n >= 1e6) return `${(n / 1e6).toFixed(0)}M`;
+  }
+  if (
+    key.endsWith("_bps")
+    || key === "gap_bps"
+    || key === "upside_bps"
+    || key === "scenario_width_bps"
+  ) {
+    const n = Number(value);
+    if (Number.isFinite(n)) {
+      // Rate-like bps at 2 decimals; gaps/upside at 1.
+      if (
+        key.includes("wacc")
+        || key.includes("discount_rate")
+        || key.includes("cost_of")
+        || key.includes("after_tax")
+        || key === "g_near_bps"
+        || key === "g_stable_bps"
+        || key === "roe0_bps"
+        || key === "equity_weight_bps"
+        || key === "debt_weight_bps"
+      ) {
+        return `${(n / 100).toFixed(2)}%`;
+      }
+      return `${(n / 100).toFixed(1)}%`;
+    }
   }
```

## TRACKED DIFF: apps/android/core/src/main/kotlin/com/discountscreener/core/engine/DcfAnalysisEngine.kt

```diff
diff --git a/apps/android/core/src/main/kotlin/com/discountscreener/core/engine/DcfAnalysisEngine.kt b/apps/android/core/src/main/kotlin/com/discountscreener/core/engine/DcfAnalysisEngine.kt
index 62d66c7..6b67f3b 100644
--- a/apps/android/core/src/main/kotlin/com/discountscreener/core/engine/DcfAnalysisEngine.kt
+++ b/apps/android/core/src/main/kotlin/com/discountscreener/core/engine/DcfAnalysisEngine.kt
@@ -3,2 +3,3 @@ package com.discountscreener.core.engine
 import com.discountscreener.core.model.BusinessClass
+import com.discountscreener.core.model.AnnualReportedValue
 import com.discountscreener.core.model.DcfAnalysis
@@ -21,3 +22,4 @@ import kotlin.math.roundToLong
 private const val ENGINE_VERSION = "valuation-model-family/1"
-private const val MODEL_POLICY_VERSION = "business-class-policy/1"
+/** Parity with Windows policy/2 (provisional WACC, normalized FCF, robust scenarios). */
+private const val MODEL_POLICY_VERSION = "business-class-policy/2"
 private const val DEFAULT_RF_BPS = 430
@@ -26,2 +28,3 @@ private const val DEFAULT_TAX_RATE_BPS = 2_100
 private const val DEFAULT_COST_OF_DEBT_BPS = 550
+private const val DEFAULT_COD_SPREAD_OVER_RF_BPS = 300
 private const val DEFAULT_RETENTION_BPS = 7_000
@@ -35,2 +38,4 @@ private const val ROE_BULL_BOOST_BPS = 200
 private const val GROWTH_RECENT_WINDOW = 4
+/** Dynamic robustification band around stable growth; constrains inputs, not output. */
+private const val MAX_NEAR_GROWTH_DISTANCE_FROM_STABLE_BPS = 1_200
 private const val STABLE_GROWTH_RF_BUFFER_BPS = 100
@@ -42,2 +47,6 @@ private const val MIN_COST_OF_DEBT_BPS = 200
 private const val MAX_COST_OF_DEBT_BPS = 1_200
+/** Soft-rate debt-weight cap (Windows parity). */
+private const val PROVISIONAL_MAX_DEBT_WEIGHT = 0.40
+/** Full uplift at debt-weight cap when CoD is policy default (T reverse-DCF ≈ +170 bps). */
+private const val PROVISIONAL_WACC_BASE_UPLIFT_BPS = 175
 
@@ -55,2 +64,4 @@ private data class ResolvedWacc(
     val waccBps: Int,
+    val provisionalWaccUpliftBps: Int = 0,
+    val debtWeightBps: Int = 0,
     val inputs: WaccInputProvenance,
@@ -225,7 +236,7 @@ object DcfAnalysisEngine {
         }
-        val latestFcf = timeseries.freeCashFlow.lastOrNull()?.value?.takeIf { it > 0.0 }
-            ?: error("DCF unavailable: latest annual free cash flow is not positive.")
+        val (runRate, fcfNormalized) = fcfRunRateDollars(timeseries)
+            ?: error("DCF unavailable: free cash flow run-rate is not positive.")
         val currentShares = latestShareCount(fundamentals, timeseries)
             ?: error("DCF unavailable: share count is missing.")
-        val gNear = recentFcfGrowthBps(timeseries)
+        val rawGNear = recentFcfGrowthBps(timeseries)
             ?: error("DCF unavailable: insufficient positive free cash flow history for growth.")
@@ -236,2 +247,6 @@ object DcfAnalysisEngine {
             .coerceAtLeast(MIN_STABLE_GROWTH_BPS)
+        val gNear = rawGNear.coerceIn(
+            gStable - MAX_NEAR_GROWTH_DISTANCE_FROM_STABLE_BPS,
+            gStable + MAX_NEAR_GROWTH_DISTANCE_FROM_STABLE_BPS,
+        )
 
@@ -240,7 +255,7 @@ object DcfAnalysisEngine {
 
-        val bear = discountedFcffFade(latestFcf, currentShares, netDebtDollars, bearNear, gStable, resolvedWacc.waccBps)
+        val bear = discountedFcffFade(runRate, currentShares, netDebtDollars, bearNear, gStable, resolvedWacc.waccBps)
             ?: error("DCF unavailable: bear scenario produced an invalid value.")
-        val base = discountedFcffFade(latestFcf, currentShares, netDebtDollars, gNear, gStable, resolvedWacc.waccBps)
+        val base = discountedFcffFade(runRate, currentShares, netDebtDollars, gNear, gStable, resolvedWacc.waccBps)
             ?: error("DCF unavailable: base scenario produced an invalid value.")
-        val bull = discountedFcffFade(latestFcf, currentShares, netDebtDollars, bullNear, gStable, resolvedWacc.waccBps)
+        val bull = discountedFcffFade(runRate, currentShares, netDebtDollars, bullNear, gStable, resolvedWacc.waccBps)
             ?: error("DCF unavailable: bull scenario produced an invalid value.")
@@ -251,3 +266,11 @@ object DcfAnalysisEngine {
             add("growth=recent_window_fade_to_stable")
+            if (fcfNormalized) add("fcf_run_rate=recent_window_average")
+            else add("fcf_run_rate=latest_positive")
+            if (gNear != rawGNear) {
+                add("growth=recent_window_robustified:raw=$rawGNear:used=$gNear")
+            }
             if (marketParams.provisional) add("market_params=provisional")
+            if (resolvedWacc.provisionalWaccUpliftBps > 0) {
+                add("wacc=provisional_base_uplift:${resolvedWacc.provisionalWaccUpliftBps}")
+            }
         }
@@ -271,2 +294,7 @@ object DcfAnalysisEngine {
             reasonCodes = reasons,
+            latestFcfDollars = timeseries.freeCashFlow.lastOrNull()?.value?.roundToLong(),
+            fcfRunRateDollars = runRate.roundToLong(),
+            fcfRunRateNormalized = fcfNormalized,
+            provisionalWaccUpliftBps = resolvedWacc.provisionalWaccUpliftBps,
+            debtWeightBps = resolvedWacc.debtWeightBps,
         )
@@ -274,10 +302,28 @@ object DcfAnalysisEngine {
 
-    private fun recentFcfGrowthBps(timeseries: FundamentalTimeseries): Int? {
-        val positive = timeseries.freeCashFlow.filter { it.value > 0.0 }
-        if (positive.size < 2) return null
-        val window = if (positive.size > GROWTH_RECENT_WINDOW) {
-            positive.takeLast(GROWTH_RECENT_WINDOW)
-        } else {
-            positive
+    private fun recentPositiveFcfWindow(timeseries: FundamentalTimeseries): List<AnnualReportedValue> {
+        val suffix = mutableListOf<AnnualReportedValue>()
+        var expectedYear: Int? = null
+        for (point in timeseries.freeCashFlow.asReversed()) {
+            val year = parseYmd(point.asOfDate)?.year ?: break
+            if (point.value <= 0.0 || (expectedYear != null && year != expectedYear)) break
+            suffix += point
+            if (suffix.size == GROWTH_RECENT_WINDOW) break
+            expectedYear = year - 1
         }
+        return suffix.asReversed()
+    }
+
+    /** Average of positive FCF in the recent window (Windows parity). */
+    private fun fcfRunRateDollars(timeseries: FundamentalTimeseries): Pair<Double, Boolean>? {
+        val window = recentPositiveFcfWindow(timeseries)
+        if (window.isEmpty()) return null
+        if (window.size == 1) return window.first().value to false
+        val avg = window.map { it.value }.average()
+        if (!avg.isFinite() || avg <= 0.0) return null
+        return avg to true
+    }
+
+    private fun recentFcfGrowthBps(timeseries: FundamentalTimeseries): Int? {
+        val window = recentPositiveFcfWindow(timeseries)
+        if (window.size < 2) return null
         val first = window.first()
@@ -410,4 +456,4 @@ object DcfAnalysisEngine {
         val debtWeightBase = marketCap + netDebt
-        val equityWeight = if (debtWeightBase > 0.0) marketCap / debtWeightBase else 1.0
-        val debtWeight = if (debtWeightBase > 0.0) netDebt / debtWeightBase else 0.0
+        var equityWeight = if (debtWeightBase > 0.0) marketCap / debtWeightBase else 1.0
+        var debtWeight = if (debtWeightBase > 0.0) netDebt / debtWeightBase else 0.0
 
@@ -422,3 +468,3 @@ object DcfAnalysisEngine {
                 costOfDebtSource = WaccFieldSource.Default
-                DEFAULT_COST_OF_DEBT_BPS
+                maxOf(DEFAULT_COST_OF_DEBT_BPS, marketParams.rfBps + DEFAULT_COD_SPREAD_OVER_RF_BPS)
             }
@@ -429,2 +475,9 @@ object DcfAnalysisEngine {
 
+        var structureGuard = false
+        if (debtWeight > PROVISIONAL_MAX_DEBT_WEIGHT) {
+            debtWeight = PROVISIONAL_MAX_DEBT_WEIGHT
+            equityWeight = 1.0 - debtWeight
+            structureGuard = true
+        }
+
         val taxRateSource =
@@ -435,4 +488,12 @@ object DcfAnalysisEngine {
         val afterTaxCostOfDebtBps = (costOfDebtBps * (1.0 - taxRateBps / 10_000.0)).roundToInt()
-        val weighted = (equityWeight * costOfEquityBps) + (debtWeight * afterTaxCostOfDebtBps)
-        val waccBps = weighted.roundToInt()
+        val softWaccBps =
+            ((equityWeight * costOfEquityBps) + (debtWeight * afterTaxCostOfDebtBps)).roundToInt()
+        val provisionalUplift =
+            if (costOfDebtSource == WaccFieldSource.Default && debtWeight > 0.0) {
+                val scale = (debtWeight / PROVISIONAL_MAX_DEBT_WEIGHT).coerceIn(0.0, 1.0)
+                (PROVISIONAL_WACC_BASE_UPLIFT_BPS * scale).roundToInt()
+            } else {
+                0
+            }
+        val waccBps = softWaccBps + provisionalUplift
 
@@ -440,2 +501,4 @@ object DcfAnalysisEngine {
             waccBps = waccBps,
+            provisionalWaccUpliftBps = provisionalUplift,
+            debtWeightBps = (debtWeight * 10_000.0).roundToInt(),
             inputs = WaccInputProvenance(
@@ -447,3 +510,3 @@ object DcfAnalysisEngine {
                 taxRate = taxRateSource,
-                waccClamped = betaProv || marketParams.provisional,
+                waccClamped = betaProv || marketParams.provisional || structureGuard || provisionalUplift > 0,
             ),
```

## TRACKED DIFF: apps/android/core/src/main/kotlin/com/discountscreener/core/model/Models.kt

```diff
diff --git a/apps/android/core/src/main/kotlin/com/discountscreener/core/model/Models.kt b/apps/android/core/src/main/kotlin/com/discountscreener/core/model/Models.kt
index 4d90e7b..4838a95 100644
--- a/apps/android/core/src/main/kotlin/com/discountscreener/core/model/Models.kt
+++ b/apps/android/core/src/main/kotlin/com/discountscreener/core/model/Models.kt
@@ -674,2 +674,9 @@ data class DcfAnalysis(
     val reasonCodes: List<String> = emptyList(),
+    /** Most recent fiscal FCF observation; never replaced by normalization. */
+    val latestFcfDollars: Long? = null,
+    /** FCFF run-rate actually used by the valuation model. */
+    val fcfRunRateDollars: Long? = null,
+    val fcfRunRateNormalized: Boolean = false,
+    val provisionalWaccUpliftBps: Int = 0,
+    val debtWeightBps: Int = 0,
 )
```

## TRACKED DIFF: apps/android/core/src/test/kotlin/com/discountscreener/core/contracts/ContractFixtureTest.kt

```diff
diff --git a/apps/android/core/src/test/kotlin/com/discountscreener/core/contracts/ContractFixtureTest.kt b/apps/android/core/src/test/kotlin/com/discountscreener/core/contracts/ContractFixtureTest.kt
index f23c8b1..3704426 100644
--- a/apps/android/core/src/test/kotlin/com/discountscreener/core/contracts/ContractFixtureTest.kt
+++ b/apps/android/core/src/test/kotlin/com/discountscreener/core/contracts/ContractFixtureTest.kt
@@ -6,2 +6,3 @@ import com.discountscreener.core.engine.ReportingEngine
 import com.discountscreener.core.engine.DcfSourceSelectionPolicy
+import com.discountscreener.core.engine.DcfAnalysisEngine
 import com.discountscreener.core.model.AnnualReportedValue
@@ -28,2 +29,3 @@ import kotlin.test.Test
 import kotlin.test.assertEquals
+import kotlin.test.assertTrue
 
@@ -71,2 +73,51 @@ class ContractFixtureTest {
 
+    @Test
+    fun valuation_model_family_policy2_fixtures_execute_against_core() {
+        val fixture = loadValuationModelFamilyFixture()
+        assertTrue("android" in fixture.policy2Adoption.executableSurfaces)
+        assertTrue("desktop" in fixture.policy2Adoption.deferredSurfaces)
+
+        fixture.regressionFixtures
+            .filter { it.name in executablePolicy2FixtureNames }
+            .forEach { case ->
+                val input = case.sampledInputs
+                val expected = case.expected
+                val analysis = DcfAnalysisEngine.compute(
+                    fundamentals = FundamentalSnapshot(
+                        symbol = case.symbol,
+                        sectorName = input.sectorName,
+                        industryName = input.industryName,
+                        marketCapDollars = input.marketCapDollars,
+                        sharesOutstanding = input.sharesOutstanding,
+                        betaMillis = input.betaMillis,
+                        totalDebtDollars = input.totalDebtDollars,
+                        totalCashDollars = input.totalCashDollars,
+                    ),
+                    timeseries = FundamentalTimeseries(
+                        freeCashFlow = input.fcfAnnualDollars.map { point ->
+                            AnnualReportedValue("${point.year}-12-31", point.valueDollars)
+                        },
+                    ),
+                    marketPriceCents = (input.marketPriceDollars * 100.0).toLong(),
+                ).getOrThrow()
+
+                assertEquals(expected.businessClass, analysis.businessClass.name, case.name)
+                assertEquals(expected.model, analysis.model.name, case.name)
+                assertEquals(expected.discountRateKind, analysis.discountRateKind.name, case.name)
+                assertEquals(expected.modelPolicyVersion, analysis.modelPolicyVersion, case.name)
+                assertEquals(expected.latestFcfDollars, analysis.latestFcfDollars, case.name)
+                assertEquals(expected.fcfRunRateDollars, analysis.fcfRunRateDollars, case.name)
+                assertTrue(
+                    analysis.bearIntrinsicValueCents <= analysis.baseIntrinsicValueCents &&
+                        analysis.baseIntrinsicValueCents <= analysis.bullIntrinsicValueCents,
+                    "${case.name} scenarios must be ordered",
+                )
+                expected.baseIntrinsicRangeDollars?.let { range ->
+                    val base = analysis.baseIntrinsicValueCents / 100.0
+                    assertTrue(base in range[0]..range[1], "${case.name} base $base outside $range")
+                }
+                assertTrue(analysis.reasonCodes.none { it.startsWith("calibration_target=") })
+            }
+    }
+
     @Test
@@ -148,2 +199,7 @@ class ContractFixtureTest {
 
+    private fun loadValuationModelFamilyFixture(): ValuationModelFamilyFixture {
+        val path = findFixturePath("valuation-model-family.json")
+        return contractJson.decodeFromString(Files.readString(path))
+    }
+
     private fun candidate(source: DcfSource, fixtureState: String): DcfSourceCandidate? = when (fixtureState) {
@@ -211,2 +267,57 @@ class ContractFixtureTest {
 
+private val executablePolicy2FixtureNames = setOf(
+    "t_class_provisional_fcff_calibrates_toward_weighted_analyst_not_clamp",
+    "amzn_capex_trough_does_not_invert_fcff_scenarios",
+)
+
+@Serializable
+private data class ValuationModelFamilyFixture(
+    val policy2Adoption: Policy2Adoption,
+    val regressionFixtures: List<ValuationRegressionFixture>,
+)
+
+@Serializable
+private data class Policy2Adoption(
+    val executableSurfaces: List<String>,
+    val deferredSurfaces: List<String>,
+)
+
+@Serializable
+private data class ValuationRegressionFixture(
+    val name: String,
+    val symbol: String,
+    val sampledInputs: ValuationSampledInputs,
+    val expected: ValuationExpected,
+)
+
+@Serializable
+private data class ValuationSampledInputs(
+    val marketPriceDollars: Double = 0.0,
+    val sharesOutstanding: Long? = null,
+    val marketCapDollars: Long? = null,
+    val betaMillis: Int? = null,
+    val totalDebtDollars: Long? = null,
+    val totalCashDollars: Long? = null,
+    val sectorName: String? = null,
+    val industryName: String? = null,
+    val fcfAnnualDollars: List<ValuationFcfPoint> = emptyList(),
+)
+
+@Serializable
+private data class ValuationFcfPoint(
+    val year: Int,
+    val valueDollars: Double,
+)
+
+@Serializable
+private data class ValuationExpected(
+    val businessClass: String,
+    val model: String,
+    val discountRateKind: String,
+    val modelPolicyVersion: String = "legacy",
+    val baseIntrinsicRangeDollars: List<Double>? = null,
+    val latestFcfDollars: Long? = null,
+    val fcfRunRateDollars: Long? = null,
+)
+
 @Serializable
```

## TRACKED DIFF: apps/android/core/src/test/kotlin/com/discountscreener/core/engine/DcfAnalysisEngineTest.kt

```diff
diff --git a/apps/android/core/src/test/kotlin/com/discountscreener/core/engine/DcfAnalysisEngineTest.kt b/apps/android/core/src/test/kotlin/com/discountscreener/core/engine/DcfAnalysisEngineTest.kt
index 884d573..1c19cb2 100644
--- a/apps/android/core/src/test/kotlin/com/discountscreener/core/engine/DcfAnalysisEngineTest.kt
+++ b/apps/android/core/src/test/kotlin/com/discountscreener/core/engine/DcfAnalysisEngineTest.kt
@@ -119,2 +119,101 @@ class DcfAnalysisEngineTest {
 
+    @Test
+    fun policy2_exposes_latest_and_normalized_run_rate_without_runtime_street_reason() {
+        val analysis = DcfAnalysisEngine.compute(
+            fundamentals = completeFundamentals(),
+            timeseries = completeTimeseries().copy(
+                freeCashFlow = listOf(
+                    AnnualReportedValue("2021-12-31", 10_000_000.0),
+                    AnnualReportedValue("2022-12-31", 20_000_000.0),
+                    AnnualReportedValue("2023-12-31", 30_000_000.0),
+                    AnnualReportedValue("2024-12-31", 40_000_000.0),
+                ),
+                interestExpense = emptyList(),
+            ),
+        ).getOrThrow()
+
+        assertEquals(40_000_000L, analysis.latestFcfDollars)
+        assertEquals(25_000_000L, analysis.fcfRunRateDollars)
+        assertTrue(analysis.fcfRunRateNormalized)
+        assertTrue(analysis.provisionalWaccUpliftBps > 0)
+        assertTrue(analysis.reasonCodes.none { it.startsWith("calibration_target=") })
+    }
+
+    @Test
+    fun policy2_run_rate_uses_latest_contiguous_positive_suffix() {
+        val analysis = DcfAnalysisEngine.compute(
+            fundamentals = completeFundamentals(),
+            timeseries = completeTimeseries().copy(
+                freeCashFlow = listOf(
+                    AnnualReportedValue("2021-12-31", 10_000_000.0),
+                    AnnualReportedValue("2023-12-31", 30_000_000.0),
+                    AnnualReportedValue("2024-12-31", 40_000_000.0),
+                    AnnualReportedValue("2025-12-31", 50_000_000.0),
+                ),
+            ),
+        ).getOrThrow()
+
+        assertEquals(50_000_000L, analysis.latestFcfDollars)
+        assertEquals(40_000_000L, analysis.fcfRunRateDollars)
+    }
+
+    @Test
+    fun interest_derived_cost_of_debt_still_guards_extreme_market_weights() {
+        val analysis = DcfAnalysisEngine.compute(
+            fundamentals = completeFundamentals().copy(
+                marketCapDollars = 10_000_000_000L,
+                sharesOutstanding = 1_000_000_000L,
+                totalDebtDollars = 90_000_000_000L,
+                totalCashDollars = 0L,
+            ),
+            timeseries = completeTimeseries().copy(
+                freeCashFlow = listOf(
+                    AnnualReportedValue("2021-12-31", 14_000_000_000.0),
+                    AnnualReportedValue("2022-12-31", 15_000_000_000.0),
+                    AnnualReportedValue("2023-12-31", 16_000_000_000.0),
+                    AnnualReportedValue("2024-12-31", 17_000_000_000.0),
+                ),
+                interestExpense = listOf(AnnualReportedValue("2024-12-31", 4_500_000_000.0)),
+            ),
+        ).getOrThrow()
+
+        assertEquals(WaccFieldSource.InterestOverDebt, analysis.waccInputs.costOfDebt)
+        assertTrue(analysis.debtWeightBps <= 4_000)
+        assertEquals(0, analysis.provisionalWaccUpliftBps)
+        assertTrue(analysis.waccInputs.waccClamped)
+    }
+
+    @Test
+    fun amzn_capex_trough_keeps_normalized_scenarios_ordered() {
+        val analysis = DcfAnalysisEngine.compute(
+            fundamentals = FundamentalSnapshot(
+                symbol = "AMZN",
+                sectorName = "Consumer Cyclical",
+                industryName = "Internet Retail",
+                marketCapDollars = 2_574_493_679_616L,
+                sharesOutstanding = 10_757_109_436L,
+                betaMillis = 1_461,
+                totalDebtDollars = 235_540_004_864L,
+                totalCashDollars = 143_088_992_256L,
+            ),
+            timeseries = FundamentalTimeseries(
+                freeCashFlow = listOf(
+                    AnnualReportedValue("2020-12-31", 25_924_000_000.0),
+                    AnnualReportedValue("2021-12-31", -14_726_000_000.0),
+                    AnnualReportedValue("2022-12-31", -16_893_000_000.0),
+                    AnnualReportedValue("2023-12-31", 32_217_000_000.0),
+                    AnnualReportedValue("2024-12-31", 32_878_000_000.0),
+                    AnnualReportedValue("2025-12-31", 7_695_000_000.0),
+                ),
+            ),
+            marketPriceCents = 23_933,
+        ).getOrThrow()
+
+        assertEquals(7_695_000_000L, analysis.latestFcfDollars)
+        assertEquals(24_263_333_333L, analysis.fcfRunRateDollars)
+        assertTrue(analysis.bearIntrinsicValueCents <= analysis.baseIntrinsicValueCents)
+        assertTrue(analysis.baseIntrinsicValueCents <= analysis.bullIntrinsicValueCents)
+        assertTrue(analysis.reasonCodes.any { it.startsWith("growth=recent_window_robustified:") })
+    }
+
     @Test
```

## TRACKED DIFF: apps/desktop/src/lib.rs

```diff
diff --git a/apps/desktop/src/lib.rs b/apps/desktop/src/lib.rs
index a66a9ff..b340fba 100644
--- a/apps/desktop/src/lib.rs
+++ b/apps/desktop/src/lib.rs
@@ -1270,4 +1270,4 @@ fn decode_external_entry(parts: &[&str]) -> Result<JournalEntry, String> {
 fn decode_fundamentals_entry(parts: &[&str]) -> Result<JournalEntry, String> {
-    if parts.len() != 24 {
-        return Err("fundamentals entry should have 24 fields".to_string());
+    if parts.len() != 24 && parts.len() != 25 {
+        return Err("fundamentals entry should have 24 or 25 fields".to_string());
     }
```

## TRACKED DIFF: shared/contracts/valuation-model-family.json

```diff
diff --git a/shared/contracts/valuation-model-family.json b/shared/contracts/valuation-model-family.json
index 0d1db02..14222b9 100644
--- a/shared/contracts/valuation-model-family.json
+++ b/shared/contracts/valuation-model-family.json
@@ -2,8 +2,12 @@
   "contract": "valuation-model-family",
-  "version": "1",
+  "version": "2",
   "architecture": "_bmad-output/planning-artifacts/valuation-model-family-architecture.md",
   "notes": [
-    "Golden cases for classifier + model selection. Numeric residual-income expected values are filled when engine Phase 1 lands; until then selection assertions are authoritative.",
-    "ACGL fundamentals sampled 2026-07-25 from public sources (Macrotrends/Yahoo/EDGAR/IR). Do not invent replacements without re-sampling."
+    "Golden cases for classifier, model selection, and executable policy/2 FCFF regressions.",
+    "ACGL fundamentals sampled 2026-07-25 from public sources (Macrotrends/Yahoo/EDGAR/IR). T and AMZN inputs were sampled 2026-07-30 from Yahoo plus SEC companyfacts. Do not invent replacements without re-sampling."
   ],
+  "policy2Adoption": {
+    "executableSurfaces": ["windows", "android"],
+    "deferredSurfaces": ["desktop"]
+  },
   "forbiddenAcceptancePatterns": [
@@ -80,2 +84,87 @@
       }
+    },
+    {
+      "name": "t_class_provisional_fcff_calibrates_toward_weighted_analyst_not_clamp",
+      "symbol": "T",
+      "sampledInputs": {
+        "asOf": "2026-07-30",
+        "marketPriceDollars": 21.12,
+        "weightedAnalystMeanDollars": 30.02,
+        "jpmReferenceTargetDollars": 33.0,
+        "sharesOutstanding": 6948338835,
+        "marketCapDollars": 146748915712,
+        "betaMillis": 422,
+        "totalDebtDollars": 159750995968,
+        "totalCashDollars": 11964000256,
+        "sectorName": "Communication Services",
+        "industryName": "Telecom Services",
+        "fcfAnnualDollars": [
+          { "year": 2021, "valueDollars": 26420000000 },
+          { "year": 2023, "valueDollars": 20460000000 },
+          { "year": 2024, "valueDollars": 18510000000 },
+          { "year": 2025, "valueDollars": 19440000000 }
+        ],
+        "preCalibrationSoftBaseBandDollars": [46.0, 55.0],
+        "legacyFailureModes": [
+          "CapexTaxonomyMissFcfEqualsOcf",
+          "ProvisionalWaccTooCheapOnLeveredIssuer"
+        ]
+      },
+      "expected": {
+        "businessClass": "OperatingNonFinancial",
+        "model": "FcffWacc",
+        "discountRateKind": "Wacc",
+        "modelPolicyVersion": "business-class-policy/2",
+        "baseIntrinsicRangeDollars": [25.0, 35.0],
+        "latestFcfDollars": 19440000000,
+        "fcfRunRateDollars": 19470000000,
+        "acceptance": [
+          "uses_provisional_wacc_base_uplift_when_cod_default_and_levered",
+          "fcf_run_rate_recent_window_average",
+          "base_materially_closer_to_weighted_analyst_than_pre_calibration_band",
+          "base_not_assigned_equal_to_street_or_price",
+          "no_intrinsic_price_hard_cap",
+          "point_estimate_unreliable_while_rates_provisional"
+        ]
+      },
+      "evidence": "_bmad-output/planning-artifacts/research-dcf-vs-street-gap-T-2026-07-30.md"
+    },
+    {
+      "name": "amzn_capex_trough_does_not_invert_fcff_scenarios",
+      "symbol": "AMZN",
+      "sampledInputs": {
+        "asOf": "2026-07-30",
+        "marketPriceDollars": 239.33,
+        "weightedAnalystMeanDollars": 313.07,
+        "sharesOutstanding": 10757109436,
+        "marketCapDollars": 2574493679616,
+        "betaMillis": 1461,
+        "totalDebtDollars": 235540004864,
+        "totalCashDollars": 143088992256,
+        "sectorName": "Consumer Cyclical",
+        "industryName": "Internet Retail",
+        "fcfAnnualDollars": [
+          { "year": 2020, "valueDollars": 25924000000 },
+          { "year": 2021, "valueDollars": -14726000000 },
+          { "year": 2022, "valueDollars": -16893000000 },
+          { "year": 2023, "valueDollars": 32217000000 },
+          { "year": 2024, "valueDollars": 32878000000 },
+          { "year": 2025, "valueDollars": 7695000000 }
+        ]
+      },
+      "expected": {
+        "businessClass": "OperatingNonFinancial",
+        "model": "FcffWacc",
+        "discountRateKind": "Wacc",
+        "modelPolicyVersion": "business-class-policy/2",
+        "latestFcfDollars": 7695000000,
+        "fcfRunRateDollars": 24263333333,
+        "acceptance": [
+          "bear_less_than_or_equal_to_base_less_than_or_equal_to_bull",
+          "volatile_endpoint_growth_is_robustified_before_scenario_projection",
+          "latest_fcf_and_normalized_run_rate_remain_distinct",
+          "material_model_analyst_disagreement_remains_visible_not_blended"
+        ]
+      },
+      "evidence": "user-reported Windows detail snapshot plus SEC companyfacts sampled 2026-07-30"
     }
@@ -86,3 +175,4 @@
     "terminal_roe_fades_to_competitive_long_run",
-    "missing_book_or_roe_is_unavailable_not_silent_fcff_fallback"
+    "missing_book_or_roe_is_unavailable_not_silent_fcff_fallback",
+    "provisional_wacc_base_uplift_debt_scaled_not_output_clamp"
   ]
```

## TRACKED DIFF: _bmad-output/project-context.md

```diff
diff --git a/_bmad-output/project-context.md b/_bmad-output/project-context.md
index 6961b0e..56102a2 100644
--- a/_bmad-output/project-context.md
+++ b/_bmad-output/project-context.md
@@ -74,3 +74,4 @@ _Critical rules and patterns AI agents must follow when implementing code in thi
 - **Parameters are dynamic.** Risk-free rate, ERP, beta (industry shrink), near-term growth (recent window), and \(g_{stable}=\min(\text{macro}, r_f-\text{buffer}, r-\varepsilon)\) come from market/policy inputs. Frozen `rf`/`ERP`/`MIN_WACC`/growth max constants are not valuation truth; defaults must be provisional when used.
-- **Structural constraints only.** Allowed: \(g < r\), model eligibility, clean-surplus identities, missing-driver unavailability. Forbidden: hard `intrinsic/price` caps, sector FCF haircuts, silent FCFF fallback for financials, acceptance tests that only require market proximity.
+- **Provisional-rate calibration (policy/2).** Windows and Android apply a **debt-scaled provisional WACC base uplift** when CoD is policy default (full at the soft debt-weight cap) and use the latest contiguous positive-window average as FCFF run-rate while preserving true latest fiscal FCF separately. Robustify noisy endpoint CAGR within a dynamic band around stable growth before scenario projection so bear≤base≤bull remains semantic. Weighted analyst mean is an **external development bias metric only**—never a runtime engine input or reason code. Desktop is explicitly deferred from policy/2 until ported and contract-tested. Never assign base = Street/price; residual stays visible / Disputed when material. Evidence: `_bmad-output/planning-artifacts/research-dcf-vs-street-gap-T-2026-07-30.md`.
+- **Structural constraints only.** Allowed: \(g < r\), model eligibility, clean-surplus identities, missing-driver unavailability, debt-scaled provisional WACC uplift. Forbidden: hard `intrinsic/price` caps, sector FCF haircuts, silent FCFF fallback for financials, acceptance tests that only require market proximity.
 - **Provenance is mandatory** for model id, business class, discount-rate kind, engine/policy version, and WACC/CoE input sources. UI labels must distinguish FCFF DCF vs residual income vs analyst.
```

## NEW FILE: apps/windows/tests/dcfDiagnosticsBoundary.test.ts

```text
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { describe, it } from "node:test";

const apiSource = readFileSync(new URL("../src/api.ts", import.meta.url), "utf8");
const quantLensSource = readFileSync(
  new URL("../src/components/QuantLensPanel.tsx", import.meta.url),
  "utf8",
);
const backendSource = readFileSync(
  new URL("../src-tauri/src/quant_lens.rs", import.meta.url),
  "utf8",
);

describe("DCF diagnostic UI boundary", () => {
  it("transports latest fiscal FCF and normalized run-rate as distinct metrics", () => {
    assert.match(apiSource, /latest_fcf_dollars\?: number \| null/);
    assert.match(apiSource, /fcf_run_rate_dollars\?: number \| null/);
    assert.match(backendSource, /"latest_fcf_dollars"/);
    assert.match(backendSource, /"fcf_run_rate_dollars"/);
  });

  it("labels both FCF meanings honestly", () => {
    assert.match(quantLensSource, /latest_fcf_dollars: "FCF latest fiscal"/);
    assert.match(quantLensSource, /fcf_run_rate_dollars: "FCF run-rate"/);
    assert.match(
      quantLensSource,
      /key === "latest_fcf_dollars"[\s\S]*key === "fcf_run_rate_dollars"/,
    );
  });
});

```

## NEW FILE: _bmad-output/implementation-artifacts/spec-dcf-street-calibration-provisional-wacc.md

```text
# SPEC: FCFF provisional-rate calibration toward weighted analyst mean

**Status:** implement  
**Date:** 2026-07-30  
**Evidence:** `_bmad-output/planning-artifacts/research-dcf-vs-street-gap-T-2026-07-30.md`  
**Engine:** Windows `dcf_model.rs` (primary); keep financial RI routing unchanged  

## Intent (WHAT)

1. Reduce **systematic FCFF overvaluation** when discount rates are provisional (default CoD/tax, soft structure), measured against **weighted analyst consensus**, not market price.  
2. Preserve honest dual anchors: model base remains a **model** number with provenance; analysts remain parallel; material residual → **Disputed** / unreliable point estimate (existing Quant Lens rules).  
3. Never close the gap with intrinsic/price caps, sector FCF haircuts, or forcing base = Street.

## Decisions

| ID | Decision |
| --- | --- |
| D1 | Weighted analyst mean is an **external development metric for measuring bias** (when usable). Runtime valuation compute never reads it and never emits it as provenance. |
| D2 | When CoD is policy **Default**, apply a **provisional WACC base uplift** scaled by debt weight / `PROVISIONAL_MAX_DEBT_WEIGHT` (full uplift at the structure cap). Rationale: reverse-DCF on T shows ~170 bps soft-rate understatement at high leverage. |
| D3 | FCFF **run-rate** = average of the latest contiguous positive FCF window (normalized), while diagnostics preserve the true latest fiscal FCF separately. |
| D4 | Uplift and normalization are **inputs/parameters** with reason codes; not output clamps. |
| D5 | ACGL-class financials remain residual-income primary; no FCFF-from-float path. |
| D6 | Bump `MODEL_POLICY_VERSION` so caches/UI can invalidate. |

## Anti-goals

- `assert(intrinsic ≈ price)`  
- Hard reject if `intrinsic/price > N`  
- `FCF × sector_constant`  
- Silent blend of model and Street into one absurd EV  

## Acceptance

- T-class fixture: base intrinsic **materially closer** to pinned weighted consensus (~$30) than pre-uplift soft path with same FCF, without setting base equal to consensus by assignment.  
- No new clamp patterns in valuation change set.  
- ACGL-class still RI, not FCFF.  
- Diagnostics/reason_codes surface uplift and normalized run-rate.
- Windows and Android execute the shared policy/2 T contract; runtime reasons contain the applied WACC policy, not the development metric.
- Stale engine/policy analyses are rejected before detail or Quant Lens can serve them.

## Out of scope

- Live bond CoD / live rf feed (desired later; this SPEC is provisional bias correction).  
- Lease debt bridge.  
- Full multi-provider FCF reconciliation (Yahoo vs EDGAR).  
- Desktop terminal policy/2 adoption; desktop remains explicitly deferred in the shared contract until its FCFF engine is ported and tested.

```

## NEW FILE: _bmad-output/implementation-artifacts/spec-harden-provisional-dcf-calibration.md

```text
---
title: 'Harden provisional DCF calibration semantics and verification'
type: 'bugfix'
created: '2026-07-30'
status: 'in-review'
review_loop_iteration: 0
baseline_commit: '271873e6ea8dd6cc074e19073886aab378ea643f'
context:
  - '{project-root}/_bmad-output/project-context.md'
  - '{project-root}/_bmad-output/planning-artifacts/valuation-model-family-architecture.md'
---

<frozen-after-approval reason="human-owned intent — do not modify unless human renegotiates">

## Intent

**Problem:** Policy/2 currently calibrates provisional FCFF rates and normalizes FCF, but its diagnostics relabel the run-rate as the latest fiscal value, Android adoption is weakly verified, the shared T fixture is documentation-only, and operating caches can retain an older policy. A reported AMZN runtime case also proves raw endpoint CAGR can invert bear/base/bull ($11.59/$1.39/$2.48). The research and SPEC overstate the breadth and runtime role of the Street evidence.

**Approach:** Preserve the no-clamp, model-family design while making latest FCF and the valuation run-rate separate additive fields, executing the shared calibration contract on Windows and Android, rejecting stale engine/policy results, and making the evidence and supported-surface scope explicit.

## Boundaries & Constraints

**Always:** Keep weighted analyst mean as a development-time bias metric only; preserve the recent-window average as policy/2's named mid-cycle run-rate; keep ACGL-class financials on residual income; keep fixed-point public values and additive serialization; expose provisional provenance and policy versions.

**Ask First:** Changing the 175 bps maximum uplift, replacing recent-window normalization with a different economic policy, or declaring desktop policy/2 parity requires new multi-name evidence and user approval.

**Never:** Read analyst targets inside valuation compute; emit a runtime `calibration_target` reason; cap intrinsic value against price/Street; silently label a normalized average as latest; claim desktop contract adoption before it is implemented.

## I/O & Edge-Case Matrix

| Scenario | Input / State | Expected Output / Behavior | Error Handling |
|----------|--------------|---------------------------|----------------|
| Normalized FCFF | Positive recent annual window | Preserve true latest and expose separate run-rate plus normalized flag | Sparse/non-positive input remains unavailable |
| Fiscal gap | Missing year inside history | Average only the latest contiguous positive suffix | Fewer than required usable points follows existing unavailable path |
| Provisional leverage | Default CoD with low/mid/capped debt weights | Uplift scales monotonically to the versioned maximum | No analyst/price output assignment |
| Android high leverage | Interest-derived CoD with extreme market weights | Structure guard prevents circular debt dominance even without uplift | Mark inputs provisional |
| Stale analysis | Engine or model-policy mismatch | Remove stale value; financials recompute from fundamentals and operating names become demand-recompute eligible | Never serve the stale intrinsic |
| Volatile FCF trough | AMZN-like positive contiguous window ending in a CapEx-driven trough | Robustify endpoint growth around dynamic stable growth; preserve bear≤base≤bull and expose latest/run-rate separately | Keep analyst disagreement visible; never blend or clamp output |

</frozen-after-approval>

## Code Map

- `apps/windows/src-tauri/src/dcf_model.rs` -- Owns policy/2 FCFF run-rate, WACC uplift, diagnostics, and T regression.
- `apps/windows/src-tauri/src/engine.rs` / `commands.rs` -- Cache admission/reconciliation and demand-driven recompute gate.
- `apps/windows/src-tauri/src/quant_lens.rs`, `apps/windows/src/api.ts`, `apps/windows/src/components/QuantLensPanel.tsx` -- Diagnostic transport, formatting, and labels.
- `apps/android/core/src/main/kotlin/com/discountscreener/core/engine/DcfAnalysisEngine.kt` and `model/Models.kt` -- Kotlin policy mirror and additive diagnostics.
- `apps/android/core/src/test/.../DcfAnalysisEngineTest.kt` and `contracts/ContractFixtureTest.kt` -- Android behavior and shared-contract consumers.
- `shared/contracts/valuation-model-family.json` -- Typed T inputs, numeric acceptance, and explicit Windows/Android adoption with desktop deferred.
- `_bmad-output/planning-artifacts/research-dcf-vs-street-gap-T-2026-07-30.md`, existing calibration SPEC, and project context -- Evidence claims, development/runtime distinction, and surface scope.
- `apps/desktop/src/workstation/app_core.rs` -- Read-only evidence for the declared policy/1 deferral.

## Tasks & Acceptance

**Execution:**
- [x] Add dual latest/run-rate diagnostics, contiguous-window handling, honest reasons, tighter T assertions, leverage controls, and policy-version cache invalidation in Windows.
- [x] Update Quant Lens transport/UI so both FCF concepts are distinctly named and formatted.
- [x] Add Android diagnostics, structure guard parity, policy/2 unit coverage, and executable shared-contract coverage.
- [x] Make the shared fixture machine-checkable and edit research/SPEC/context to state T-first evidence, external metric semantics, and desktop deferral.

**Acceptance Criteria:**
- Given the T shared fixture, when both engines compute policy/2, then each selects FCFF/WACC, applies a provisional uplift, preserves latest versus normalized run-rate, lands inside the pinned honest residual band, and never equals Street/price by assignment.
- Given low-, mid-, and cap-leverage controls, when default CoD is used, then uplift is monotonic and debt-scaled.
- Given a cached operating analysis from policy/1, when detail or Quant Lens is requested, then it is cleared and demand recomputation is allowed.
- Given ACGL-like fundamentals, when valuation runs, then residual income remains primary.
- Given the shared AMZN trough fixture, when Windows and Android compute FCFF, then latest FCF remains $7.695B, normalized run-rate remains $24.263B, endpoint growth is robustified, and bear≤base≤bull.

## Spec Change Log

## Design Notes

The 175 bps maximum remains explicitly provisional and T-first. Tests may prove scaling and non-clamping, but documentation must not turn synthetic leverage controls into multi-name empirical validation. AMZN adds a distinct structural scenario-ordering regression, not another calibration target.

## Verification

**Commands:**
- `cargo fmt --manifest-path apps/windows/src-tauri/Cargo.toml -- --check` -- expected: clean formatting.
- `cargo test` from `apps/windows/src-tauri` -- expected: Rust valuation, cache, contract, and Quant Lens tests pass.
- `npm test -- --run` from `apps/windows` -- expected: frontend tests pass.
- `scripts/validate-android.ps1` -- expected: Android core tests pass; app tasks pass when SDK is configured.
- `cargo test` from `apps/desktop` -- expected: declared deferred surface remains regression-free.

```

## NEW FILE: _bmad-output/planning-artifacts/research-dcf-vs-street-gap-T-2026-07-30.md

```text
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

```


