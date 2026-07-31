param(
    [switch]$MutationProbe
)

# Side-by-side Windows vs Android valuation parity on QA-scope fixed inputs.
# Scope: baseline_cohort 20 (QA-style High+gap pins) + checklist T/AMZN/ACGL.
# Requires: prior export tests that write:
#   .agents/workspace/tmp/parity-windows-qa.json
#   .agents/workspace/tmp/parity-android-qa.json

$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$winPath = Join-Path $root ".agents/workspace/tmp/parity-windows-qa.json"
$andPath = Join-Path $root ".agents/workspace/tmp/parity-android-qa.json"
$reportPath = Join-Path $root ".agents/workspace/tmp/parity-windows-android-qa-report.md"

if (-not (Test-Path $winPath)) { throw "Missing Windows export: $winPath" }
if (-not (Test-Path $andPath)) { throw "Missing Android export: $andPath" }

$win = Get-Content $winPath -Raw | ConvertFrom-Json
$and = Get-Content $andPath -Raw | ConvertFrom-Json

if ($MutationProbe) {
    $probe = $and.rows | Where-Object { $_.ok -and $null -ne $_.base_cents } | Select-Object -First 1
    if ($null -eq $probe) { throw "Mutation probe requires at least one successful valuation row" }
    $probe.base_cents = [int64]$probe.base_cents + 1
}

$winMap = @{}
foreach ($r in $win.rows) { $winMap["$($r.symbol)|$($r.case)"] = $r }
$andMap = @{}
foreach ($r in $and.rows) { $andMap["$($r.symbol)|$($r.case)"] = $r }

$keys = ($winMap.Keys + $andMap.Keys) | Sort-Object -Unique
$compareFields = @(
    "ok", "business_class", "model", "discount_rate_kind",
    "bear_cents", "base_cents", "bull_cents",
    "wacc_bps", "base_growth_bps", "net_debt_dollars",
    "provisional_wacc_uplift_bps", "latest_fcf_dollars", "fcf_run_rate_dollars",
    "fcf_run_rate_normalized", "debt_weight_bps", "point_estimate_unreliable",
    "scenario_stress", "wacc_bear_bps", "wacc_bull_bps",
    "valuation_driver", "latest_revenue_dollars", "normalized_fcff_dollars",
    "normalized_ocf_margin_bps", "normalized_capex_intensity_bps", "normalized_after_tax_interest_margin_bps", "capex_spike_years", "growth_driver",
    "driver_input_fingerprint", "driver_provenance",
    "wacc_market_cap_source", "wacc_beta_source", "wacc_total_debt_source", "wacc_total_cash_source",
    "wacc_cost_of_debt_source", "wacc_tax_source", "reason_codes",
    "engine_version", "model_policy_version"
)

$mismatches = @()
$exact = 0
$missing = 0
$lines = @()
$lines += "# Windows vs Android valuation parity (QA scope)"
$lines += ""
$lines += "- Windows surface: $($win.surface) / $($win.profile_scope)"
$lines += "- Android surface: $($and.surface) / $($and.profile_scope)"
$lines += "- Engine: W=$($win.engine_version) A=$($and.engine_version)"
$lines += "- Policy: W=$($win.model_policy_version) A=$($and.model_policy_version)"
$lines += "- Cases: $($keys.Count)"
$lines += ""
$lines += "| Symbol | Case | Match | Windows base | Android base | Delta base | Notes |"
$lines += "| --- | --- | --- | ---: | ---: | ---: | --- |"

function Get-Prop($obj, $name) {
    if ($null -eq $obj) { return $null }
    $p = $obj.PSObject.Properties[$name]
    if ($null -eq $p) { return $null }
    return $p.Value
}

foreach ($key in $keys) {
    $w = $winMap[$key]
    $a = $andMap[$key]
    if ($null -eq $w -or $null -eq $a) {
        $missing++
        $mismatches += $key
        $lines += "| $($key.Split('|')[0]) | $($key.Split('|')[1]) | MISSING | - | - | - | side missing |"
        continue
    }
    $diffs = @()
    foreach ($f in $compareFields) {
        $wv = Get-Prop $w $f
        $av = Get-Prop $a $f
        # Normalize bool-ish
        if ($wv -is [bool] -or $av -is [bool]) {
            $wv = [string]$wv
            $av = [string]$av
        }
        if ("$wv" -ne "$av") {
            $diffs += "${f}: W=$wv A=$av"
        }
    }
    $wb = Get-Prop $w "base_cents"
    $ab = Get-Prop $a "base_cents"
    $delta = if ($null -ne $wb -and $null -ne $ab) { [int64]$ab - [int64]$wb } else { $null }
    if ($diffs.Count -eq 0) {
        $exact++
        $lines += "| $($w.symbol) | $($w.case) | EXACT | $wb | $ab | 0 | |"
    } else {
        $mismatches += $key
        $note = ($diffs -join "; ")
        if ($note.Length -gt 120) { $note = $note.Substring(0, 117) + "..." }
        $lines += "| $($w.symbol) | $($w.case) | DIFF | $wb | $ab | $delta | $note |"
    }
}

$lines += ""
$lines += "## Summary"
$lines += ""
$lines += "- Exact matches: **$exact** / $($keys.Count)"
$lines += "- Diffs: **$($mismatches.Count)**"
$lines += "- Missing side: **$missing**"
$lines += ""

if ($MutationProbe) {
    if ($mismatches.Count -eq 0) { throw "Mutation probe failed: a one-cent drift was not detected" }
    $lines += "**PASS: one-cent mutation was rejected.**"
    $exit = 0
} elseif ($mismatches.Count -eq 0) {
    $lines += "**PASS: all compared fields are exactly equal.**"
    $exit = 0
} else {
    $lines += "**FAIL: engines diverge on one or more fields.**"
    $lines += ""
    $lines += "### Mismatch keys"
    foreach ($m in $mismatches) { $lines += "- ``$m``" }
    $exit = 1
}

$lines -join "`n" | Set-Content -Path $reportPath -Encoding utf8
Write-Host ($lines -join "`n")
Write-Host ""
Write-Host "Report: $reportPath"
exit $exit
