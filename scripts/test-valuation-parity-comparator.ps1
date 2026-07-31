$ErrorActionPreference = "Stop"
$comparator = Join-Path $PSScriptRoot "compare-windows-android-valuation-parity.ps1"
& $comparator -MutationProbe
if ($LASTEXITCODE -ne 0) {
    throw "Exact parity comparator did not reject the deliberate one-cent mutation."
}
Write-Host "PASS: exact parity comparator rejects a one-cent drift."
