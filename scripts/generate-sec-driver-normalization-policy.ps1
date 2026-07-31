param([switch]$Check)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$contract = Get-Content -Raw (Join-Path $root 'shared/contracts/sec-driver-normalization.json') | ConvertFrom-Json
$categories = $contract.investmentCategories
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
    const val materialAcquisitionRevenueBps = $($contract.valuationPolicy.materialAcquisitionRevenueBps)
    val acceptedForms = $(KotlinSet $contract.scope.forms)
    val development = $(KotlinSet $categories.development)
    val propertyAcquisition = $(KotlinSet $categories.propertyAcquisition)
    val businessAcquisition = $(KotlinSet $categories.businessAcquisition)
    val operatingCashFlow = $(KotlinOperator $drivers.operatingCashFlow)
    val revenue = $(KotlinOperator $drivers.revenue)
    val interestExpense = $(KotlinOperator $drivers.interestExpense)
    val totalDebt = $(KotlinOperator $drivers.totalDebt)
    val currentDebt = $(KotlinOperator $drivers.currentDebt)
    val nonCurrentDebt = $(KotlinOperator $drivers.nonCurrentDebt)
    val taxExpense = $(KotlinOperator $drivers.taxExpense)
    val pretaxIncome = $(KotlinOperator $drivers.pretaxIncome)
    val marginalTaxReference = $(KotlinOperator $drivers.marginalTaxReference)
    val dilutedAverageShares = $(KotlinOperator $drivers.dilutedAverageShares)
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
pub const MATERIAL_ACQUISITION_REVENUE_BPS: i32 = $($contract.valuationPolicy.materialAcquisitionRevenueBps);
pub const ACCEPTED_FORMS: &[&str] = $(RustSlice $contract.scope.forms);
pub const DEVELOPMENT: &[&str] = $(RustSlice $categories.development);
pub const PROPERTY_ACQUISITION: &[&str] = $(RustSlice $categories.propertyAcquisition);
pub const BUSINESS_ACQUISITION: &[&str] = $(RustSlice $categories.businessAcquisition);
pub struct DriverOperator {
    pub qnames: &'static [&'static str],
    pub unit: &'static str,
    pub period_shape: &'static str,
    pub operation: &'static str,
}
$(
    $driverRust = @()
    foreach ($property in $drivers.psobject.Properties) {
        $driver = $property.Value
        $constantName = [regex]::Replace(
            $property.Name,
            '([a-z0-9])([A-Z])',
            { param($match) "$($match.Groups[1].Value)_$($match.Groups[2].Value)" }
        ).ToUpperInvariant()
        $driverRust += "pub const ${constantName}: DriverOperator = $(RustOperator $driver);"
    }
    $driverRust -join "`n"
)
"@
$targets = @(
    @{ path = Join-Path $root 'apps/android/core/src/main/kotlin/com/discountscreener/core/engine/SecDriverNormalizationPolicyGenerated.kt'; content = $kotlinOutput },
    @{ path = Join-Path $root 'apps/windows/src-tauri/src/sec_driver_normalization_policy_generated.rs'; content = ($rustOutput.TrimEnd() + "`n") }
)
if ($Check) {
    foreach ($target in $targets) {
        if ((Get-Content -Raw $target.path) -ne $target.content) { throw "generated SEC policy is stale: $($target.path); run scripts/generate-sec-driver-normalization-policy.ps1" }
    }
} else {
    foreach ($target in $targets) {
        Set-Content -NoNewline -Path $target.path -Value $target.content
    }
}
