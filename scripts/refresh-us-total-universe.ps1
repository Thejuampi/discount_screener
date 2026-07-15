# Regenerates apps/android/app/src/main/assets/universes/us_total.txt from the
# official NASDAQ Trader symbol directory (HTTPS).
#
# Usage (repo root):
#   pwsh ./scripts/refresh-us-total-universe.ps1

$ErrorActionPreference = 'Stop'
$ua = 'DiscountScreener/1.0 (universe refresh script)'
$out = Join-Path $PSScriptRoot '..\apps\android\app\src\main\assets\universes\us_total.txt' | Resolve-Path -ErrorAction SilentlyContinue
if (-not $out) {
    $outDir = Join-Path $PSScriptRoot '..\apps\android\app\src\main\assets\universes'
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
    $out = Join-Path $outDir 'us_total.txt'
}

function Get-YahooSymbol([string]$sym) {
    return ($sym.Trim().ToUpperInvariant() -replace '\.', '-')
}

function Read-Directory([string]$url, [int]$symbolIndex, [int]$etfIndex, [int]$testIndex) {
    $body = (Invoke-WebRequest -Uri $url -UserAgent $ua -TimeoutSec 60).Content
    $lines = $body -split "`r?`n"
    $set = New-Object System.Collections.Generic.List[string]
    for ($i = 1; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        if ($line.StartsWith('File Creation Time')) { continue }
        $p = $line -split '\|'
        if ($p.Count -le [Math]::Max($etfIndex, $testIndex)) { continue }
        if ($p[$etfIndex] -eq 'Y' -or $p[$testIndex] -eq 'Y') { continue }
        $sym = $p[$symbolIndex]
        if ($sym -match '\$' -or [string]::IsNullOrWhiteSpace($sym)) { continue }
        $set.Add((Get-YahooSymbol $sym)) | Out-Null
    }
    return $set
}

$nasdaq = Read-Directory 'https://www.nasdaqtrader.com/dynamic/SymDir/nasdaqlisted.txt' 0 6 3
$other = Read-Directory 'https://www.nasdaqtrader.com/dynamic/SymDir/otherlisted.txt' 0 4 6
$all = ($nasdaq + $other) | Sort-Object -Unique
$all | Set-Content -Path $out -Encoding utf8NoBOM
Write-Host "Wrote $($all.Count) Yahoo-compatible equities to $out"
if ($all.Count -lt 1000) {
    throw "symbol count unexpectedly low: $($all.Count)"
}
