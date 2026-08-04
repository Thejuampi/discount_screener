# Emits the SEC driver normalization policy for both platforms from the one
# contract that defines it.
#
# Every constant is emitted by iterating the contract, never by naming a key.
# Hand-listing the keys is what let the script drift from its own output: it
# stopped emitting `developmentSoftware` and `developmentAggregate` while the
# committed Rust still carried them, so running the generator would have deleted
# two constants the app compiles against. A loop cannot drift.
#
# Each driver also emits `qname_signs` / `qnameSigns`, one integer per qname,
# positional and parallel to `qnames`. A qname listed in the driver's
# `negatedQnames` gets -1; every other qname gets +1. The sign is derived from
# the contract, never hand-authored, so a driver with no `negatedQnames` key
# emits an all-+1 array with the same shape as every other driver.
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
$drivers = $contract.drivers
function KotlinSet($values) {
    $quoted = @($values | ForEach-Object { '        "' + $_ + '"' }) -join ",`n"
    "setOf(`n$quoted,`n    )"
}
function KotlinList($values, [switch]$NoQuote) {
    $quoted = @($values | ForEach-Object {
        if ($NoQuote) { '        ' + $_ } else { '        "' + $_ + '"' }
    }) -join ",`n"
    "listOf(`n$quoted,`n    )"
}
# A qname's sign is a static property of the concept, never of the filed
# value: -1 when the contract lists it in `negatedQnames`, +1 otherwise.
# Positional and parallel to `driver.qnames`.
function QnameSigns($driver) {
    $negated = @($driver.negatedQnames)
    @($driver.qnames | ForEach-Object { if ($negated -contains $_) { -1 } else { 1 } })
}
function KotlinOperator($driver) {
    @"
    GeneratedSecDriverOperator(
        qnames = $(KotlinList $driver.qnames),
        qnameSigns = $(KotlinList (QnameSigns $driver) -NoQuote),
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
    val qnameSigns: List<Int>,
    val unit: String,
    val periodShape: String,
    val operation: String,
)

internal object GeneratedSecDriverNormalizationPolicy {
    const val fingerprint = "$($contract.fingerprint)"
    const val requiredUnit = "$($contract.scope.unit)"
    const val minimumDurationDays = $($contract.scope.durationDays[0])
    const val maximumDurationDays = $($contract.scope.durationDays[1])
    const val materialAcquisitionRevenueBps = $($contract.valuationPolicy.materialAcquisitionRevenueBps)
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
function RustSlice($values, [int]$nestedIndent = 0, [switch]$NoQuote) {
    $items = @($values | ForEach-Object { if ($NoQuote) { "$_" } else { '"' + $_ + '"' } })
    $oneLine = '&[' + ($items -join ', ') + ']'
    # rustfmt collapses an array literal onto one line whenever it fits its
    # column budget; matching that here keeps generated output a fixed point
    # under `cargo fmt --check` without hand-editing the DO NOT EDIT output.
    # 80 leaves headroom for the field-name prefix and const-block indent that
    # this function does not itself see.
    if (@($values).Count -le 1 -or (4 + $nestedIndent + $oneLine.Length) -le 80) {
        return $oneLine
    }
    $indent = ' ' * (4 + $nestedIndent)
    $quoted = @($items | ForEach-Object { $indent + $_ + ',' }) -join "`n"
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
    qname_signs: $(RustSlice (QnameSigns $driver) 4 -NoQuote),
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
pub const MATERIAL_ACQUISITION_REVENUE_BPS: i32 = $($contract.valuationPolicy.materialAcquisitionRevenueBps);
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
    pub qname_signs: &'static [i8],
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
