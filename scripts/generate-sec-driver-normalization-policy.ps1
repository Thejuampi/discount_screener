# Emits the SEC driver normalization policy for both platforms from the one
# contract that defines it.
#
# Every constant is emitted by iterating the contract, never by naming a key.
# Hand-listing the keys is what let the script drift from its own output: it
# stopped emitting `developmentSoftware` and `developmentAggregate` while the
# committed Rust still carried them, so running the generator would have deleted
# two constants the app compiles against. A loop cannot drift.
param(
    [switch]$Check,
    # Write somewhere other than the repo, so a regeneration can be compared
    # against the committed files without overwriting them first.
    [string]$OutputRoot
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (-not $OutputRoot) { $OutputRoot = $root }
$contract = Get-Content -Raw (Join-Path $root 'shared/contracts/sec-driver-normalization.json') | ConvertFrom-Json
$categories = $contract.investmentCategories
# Numbers only. The policy words next to them ("exclude_current_year_revenue_growth")
# name a decision for a reader; neither platform dispatches on them, and emitting
# them as constants would invite code that does.
$policy = $contract.valuationPolicy
$drivers = $contract.drivers
function KotlinSet($values) {
    $quoted = @($values | ForEach-Object { '        "' + $_ + '"' }) -join ",`n"
    "setOf(`n$quoted,`n    )"
}
function KotlinList($values) {
    $quoted = @($values | ForEach-Object { '        "' + $_ + '"' }) -join ",`n"
    "listOf(`n$quoted,`n    )"
}
function KotlinOperator($driver) {
    @"
    GeneratedSecDriverOperator(
        qnames = $(KotlinList $driver.qnames),
        unit = "$($driver.unit)",
        periodShape = "$($driver.periodShape)",
        operation = "$($driver.operation)",
    )
"@
}
$kotlinOutput = @"
// GENERATED FROM shared/contracts/sec-driver-normalization.json. DO NOT EDIT.
package com.discountscreener.core.engine

internal data class GeneratedSecDriverOperator(
    val qnames: List<String>,
    val unit: String,
    val periodShape: String,
    val operation: String,
)

internal object GeneratedSecDriverNormalizationPolicy {
    const val fingerprint = "$($contract.fingerprint)"
    const val requiredUnit = "$($contract.scope.unit)"
    const val minimumDurationDays = $($contract.scope.durationDays[0])
    const val maximumDurationDays = $($contract.scope.durationDays[1])
$(
    $policyKotlin = @()
    foreach ($property in $policy.psobject.Properties) {
        if ($property.Value -is [string]) { continue }
        $policyKotlin += "    const val $($property.Name) = $($property.Value)"
    }
    $policyKotlin -join "`n"
)
    val acceptedForms = $(KotlinSet $contract.scope.forms)
$(
    $categoryKotlin = @()
    foreach ($property in $categories.psobject.Properties) {
        $categoryKotlin += "    val $($property.Name) = $(KotlinSet $property.Value)"
    }
    $categoryKotlin -join "`n"
)
$(
    $driverKotlin = @()
    foreach ($property in $drivers.psobject.Properties) {
        $driverKotlin += "    val $($property.Name) = $(KotlinOperator $property.Value)"
    }
    $driverKotlin -join "`n"
)
}
"@
function RustSlice($values, [int]$nestedIndent = 0) {
    if (@($values).Count -eq 1 -or ($nestedIndent -eq 0 -and @($values).Count -le 2)) {
        return '&[' + (@($values | ForEach-Object { '"' + $_ + '"' }) -join ', ') + ']'
    }
    $indent = ' ' * (4 + $nestedIndent)
    $quoted = @($values | ForEach-Object { $indent + '"' + $_ + '",' }) -join "`n"
    "&[`n$quoted`n$(' ' * $nestedIndent)]"
}
function RustConstantName([string]$camelCase) {
    [regex]::Replace(
        $camelCase,
        '([a-z0-9])([A-Z])',
        { param($match) "$($match.Groups[1].Value)_$($match.Groups[2].Value)" }
    ).ToUpperInvariant()
}
function RustOperator($driver) {
@"
DriverOperator {
    qnames: $(RustSlice $driver.qnames 4),
    unit: "$($driver.unit)",
    period_shape: "$($driver.periodShape)",
    operation: "$($driver.operation)",
}
"@
}
$rustOutput = @"
// GENERATED FROM shared/contracts/sec-driver-normalization.json. DO NOT EDIT.

pub const POLICY_FINGERPRINT: &str = "$($contract.fingerprint)";
pub const REQUIRED_UNIT: &str = "$($contract.scope.unit)";
pub const MINIMUM_DURATION_DAYS: i64 = $($contract.scope.durationDays[0]);
pub const MAXIMUM_DURATION_DAYS: i64 = $($contract.scope.durationDays[1]);
$(
    $policyRust = @()
    foreach ($property in $policy.psobject.Properties) {
        if ($property.Value -is [string]) { continue }
        $policyRust += "pub const $(RustConstantName $property.Name): i32 = $($property.Value);"
    }
    $policyRust -join "`n"
)
pub const ACCEPTED_FORMS: &[&str] = $(RustSlice $contract.scope.forms);
$(
    $categoryRust = @()
    foreach ($property in $categories.psobject.Properties) {
        $categoryRust += "pub const $(RustConstantName $property.Name): &[&str] = $(RustSlice $property.Value);"
    }
    $categoryRust -join "`n"
)
pub struct DriverOperator {
    pub qnames: &'static [&'static str],
    pub unit: &'static str,
    pub period_shape: &'static str,
    pub operation: &'static str,
}
$(
    $driverRust = @()
    foreach ($property in $drivers.psobject.Properties) {
        $driverRust += "pub const $(RustConstantName $property.Name): DriverOperator = $(RustOperator $property.Value);"
    }
    $driverRust -join "`n"
)
"@
$targets = @(
    @{ path = Join-Path $OutputRoot 'apps/android/core/src/main/kotlin/com/discountscreener/core/engine/SecDriverNormalizationPolicyGenerated.kt'; content = $kotlinOutput },
    @{ path = Join-Path $OutputRoot 'apps/windows/src-tauri/src/sec_driver_normalization_policy_generated.rs'; content = ($rustOutput.TrimEnd() + "`n") }
)
if ($Check) {
    # Every stale target, not just the first. Throwing on the Kotlin file meant
    # the Rust one was never compared, which is how two drifts accumulated
    # behind one error message.
    $stale = @($targets | Where-Object { (Get-Content -Raw $_.path) -ne $_.content } | ForEach-Object { $_.path })
    if ($stale.Count -gt 0) {
        throw "generated SEC policy is stale:`n  $($stale -join "`n  ")`nrun scripts/generate-sec-driver-normalization-policy.ps1"
    }
} else {
    foreach ($target in $targets) {
        Set-Content -NoNewline -Path $target.path -Value $target.content
    }
}
