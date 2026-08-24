# Copy an assembled APK into dist/ with the git version in the file name.
# Version string comes from scripts/version.ps1 (same stamp as versionName).
param(
    [Parameter(Mandatory = $true)]
    [string]$SourceApk,
    [Parameter(Mandatory = $true)]
    [string]$DistDir,
    [Parameter(Mandatory = $true)]
    [ValidateSet("debug", "release")]
    [string]$Kind
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path -LiteralPath $SourceApk)) {
    throw "APK not found: $SourceApk"
}

$versionLines = &(Join-Path $PSScriptRoot "version.ps1")
$version = [string]$versionLines[0]
$version = $version.Trim()
if ([string]::IsNullOrWhiteSpace($version)) {
    throw "version.ps1 did not print a version string"
}

$safeVersion = $version -replace '[<>:"/\\|?*]', '-'
$destName = "discount-screener-$Kind-$safeVersion.apk"
New-Item -ItemType Directory -Force -Path $DistDir | Out-Null
$dest = Join-Path $DistDir $destName
Copy-Item -Force -LiteralPath $SourceApk -Destination $dest
Write-Host "APK: $dest"
