# Isolated warm-start bench. Does not open discount_screener_state.sqlite3.
# Usage: pwsh ./scripts/measure-android-warmstart.ps1

$ErrorActionPreference = "Stop"
$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$AndroidDir = Join-Path $RepoRoot "apps\android"
$ReportDir = Join-Path $RepoRoot ".agents\workspace\tmp\warmstart-bench"

New-Item -ItemType Directory -Force -Path $ReportDir | Out-Null

$env:DS_WARMSTART_BENCH = "1"
$env:DS_WARMSTART_BENCH_REPORT = Join-Path $ReportDir "report.txt"
Push-Location $AndroidDir
try {
    & .\gradlew.bat :app:testDebugUnitTest --tests com.discountscreener.android.data.persistence.WarmStartImpactBenchTest --rerun "-PdsWarmStartBench=1" "-PdsWarmStartBenchReport=$($env:DS_WARMSTART_BENCH_REPORT)"
    if ($LASTEXITCODE -ne 0) {
        throw "Warm-start bench failed with exit $LASTEXITCODE"
    }
} finally {
    Pop-Location
    Remove-Item Env:DS_WARMSTART_BENCH -ErrorAction SilentlyContinue
}

$GradleReport = Join-Path $AndroidDir "build\warmstart-bench\report.txt"
if (Test-Path $GradleReport) {
    Copy-Item -Force $GradleReport (Join-Path $ReportDir "report.txt")
    Write-Host "Report: $ReportDir\report.txt"
} else {
    Write-Host "Gradle finished. Report file was not found at $GradleReport"
}
