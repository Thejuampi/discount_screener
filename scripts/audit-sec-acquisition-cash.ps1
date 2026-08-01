param(
    [ValidateSet('russell', 'sp500', 'dow')]
    [string]$Profile = 'russell',
    [int]$RequestsPerSecond = 6,
    [int]$ShardIndex = 0,
    [int]$ShardCount = 1,
    [string]$OutputPath = ''
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$policy = Get-Content -Raw (Join-Path $root 'shared/contracts/sec-driver-normalization.json') | ConvertFrom-Json
$symbolsPath = switch ($Profile) {
    'russell' { Join-Path $root 'apps/windows/src-tauri/src/profile_data/russell.txt' }
    'dow' { Join-Path $root 'apps/windows/src-tauri/src/profile_data/dow.txt' }
    'sp500' { throw 'sp500 is defined in Rust; use russell or dow for the standalone SEC audit.' }
}
if (-not $OutputPath) {
    $OutputPath = Join-Path $root ".agents/workspace/tmp/sec-acquisition-audit-$Profile-shard-$ShardIndex-of-$ShardCount.json"
}
$allSymbols = Get-Content $symbolsPath | ForEach-Object { $_.Trim().ToUpperInvariant() } |
    Where-Object { $_ -and -not $_.StartsWith('#') } | Select-Object -Unique
if ($ShardCount -lt 1 -or $ShardIndex -lt 0 -or $ShardIndex -ge $ShardCount) {
    throw 'ShardIndex must be in [0, ShardCount).'
}
$symbols = for ($i = 0; $i -lt $allSymbols.Count; $i++) {
    if (($i % $ShardCount) -eq $ShardIndex) { $allSymbols[$i] }
}
$headers = @{ 'User-Agent' = 'DiscountScreener/1.0 (research@discountscreener.com)' }
$tickerPayload = Invoke-RestMethod -Uri 'https://www.sec.gov/files/company_tickers.json' -Headers $headers
$ciks = @{}
$tickerPayload.psobject.Properties | ForEach-Object {
    $ciks[$_.Value.ticker.ToUpperInvariant()] = ('{0:d10}' -f $_.Value.cik_str)
}
$known = @{}
foreach ($category in @('propertyAcquisition', 'businessAcquisition')) {
    foreach ($qname in $policy.investmentCategories.$category) { $known[$qname] = $category }
}
$development = @($policy.investmentCategories.development)
$revenueQNames = @($policy.drivers.revenue.qnames)
$minimumDays = [int]$policy.scope.durationDays[0]
$maximumDays = [int]$policy.scope.durationDays[1]
$delayMs = [math]::Ceiling(1000 / [math]::Max($RequestsPerSecond, 1))

function Get-AnnualUsdFacts([object]$concept) {
    if (-not $concept.units.USD) { return @() }
    $byEnd = @{}
    foreach ($fact in $concept.units.USD) {
        if ($fact.form -notin @('10-K', '10-K/A') -or -not $fact.start -or -not $fact.end -or $fact.segment) { continue }
        try { $days = ([datetime]$fact.end - [datetime]$fact.start).Days } catch { continue }
        if ($days -lt $minimumDays -or $days -gt $maximumDays) { continue }
        $previous = $byEnd[$fact.end]
        if (-not $previous -or $fact.filed -gt $previous.filed -or ($fact.filed -eq $previous.filed -and $fact.accn -gt $previous.accn)) {
            $byEnd[$fact.end] = $fact
        }
    }
    return @($byEnd.Values)
}

$alerts = [System.Collections.Generic.List[object]]::new()
$coverage = [ordered]@{ scanned = 0; noCik = 0; fetchFailed = 0; standardMappedFacts = 0; unmappedCandidates = @{} }
foreach ($symbol in $symbols) {
    $cik = $ciks[$symbol]
    if (-not $cik) { $coverage.noCik++; continue }
    try { $company = Invoke-RestMethod -Uri "https://data.sec.gov/api/xbrl/companyfacts/CIK$cik.json" -Headers $headers } catch { $coverage.fetchFailed++; Start-Sleep -Milliseconds $delayMs; continue }
    $coverage.scanned++
    $usGaap = $company.facts.'us-gaap'
    if (-not $usGaap) { Start-Sleep -Milliseconds $delayMs; continue }
    $revenueByYear = @{}
    foreach ($qname in $revenueQNames) {
        if (-not $usGaap.$qname) { continue }
        foreach ($fact in Get-AnnualUsdFacts $usGaap.$qname) {
            $year = ([string]$fact.end).Substring(0, 4)
            if (-not $revenueByYear[$year]) { $revenueByYear[$year] = [math]::Abs([double]$fact.val) }
        }
    }
    foreach ($property in $usGaap.psobject.Properties) {
        $qname = $property.Name
        $category = $known[$qname]
        $unmappedCandidate = -not $category -and $qname -like '*PaymentsToAcquire*' -and $qname -notin $development
        if (-not $category -and -not $unmappedCandidate) { continue }
        foreach ($fact in Get-AnnualUsdFacts $property.Value) {
            $year = ([string]$fact.end).Substring(0, 4)
            $revenue = $revenueByYear[$year]
            if (-not $revenue -or $revenue -le 0) { continue }
            $amount = [math]::Abs([double]$fact.val)
            $bps = [math]::Round($amount * 10000 / $revenue)
            if ($category) { $coverage.standardMappedFacts++ } else { $coverage.unmappedCandidates[$qname] = 1 + [int]$coverage.unmappedCandidates[$qname] }
            if ($bps -ge [int]$policy.valuationPolicy.materialAcquisitionRevenueBps) {
                $alerts.Add([pscustomobject]@{
                    symbol = $symbol; cik = $cik; fiscalYear = [int]$year; qname = $qname
                    category = if ($category) { $category } else { 'unmapped_candidate' }
                    acquisitionDollars = [int64]$amount; revenueDollars = [int64]$revenue; acquisitionRevenueBps = [int]$bps
                })
            }
        }
    }
    Start-Sleep -Milliseconds $delayMs
}
$report = [ordered]@{
    generatedAt = (Get-Date).ToUniversalTime().ToString('o'); profile = $Profile
    shard = [ordered]@{ index = $ShardIndex; count = $ShardCount; symbolCount = @($symbols).Count }
    policyFingerprint = $policy.fingerprint; thresholdBps = $policy.valuationPolicy.materialAcquisitionRevenueBps
    coverage = $coverage; alerts = @($alerts | Sort-Object symbol, fiscalYear, qname)
}
$report | ConvertTo-Json -Depth 8 | Set-Content -Path $OutputPath
Write-Output "SEC acquisition audit complete: $($alerts.Count) material observations; $OutputPath"
