param(
    [switch]$Bootstrap,
    [string]$SwiplPath
)

$ErrorActionPreference = 'Stop'

$toolVersion = '10.0.2'
$wingetPackage = 'SWI-Prolog.SWI-Prolog'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$modelPath = Join-Path $repositoryRoot 'lab\scoring-process\scoring_evaluation_process.pl'
$installedCandidates = @(
    (Join-Path $env:ProgramFiles 'swipl\bin\swipl.exe'),
    (Join-Path $env:LOCALAPPDATA 'Programs\swipl\bin\swipl.exe')
)

function Get-SwiplVersion {
    param([Parameter(Mandatory)][string]$Executable)

    $versionOutput = & $Executable --version 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "SWI-Prolog version check failed with exit code $LASTEXITCODE."
    }

    $versionText = ($versionOutput | Out-String).Trim()
    if ($versionText -notmatch "SWI-Prolog version $([regex]::Escape($toolVersion))(?:\s|$)") {
        throw "Expected SWI-Prolog $toolVersion. Found: $versionText"
    }

    return $versionText
}

function Install-PinnedSwipl {
    $winget = Get-Command winget -ErrorAction SilentlyContinue
    if ($null -eq $winget) {
        throw 'winget is unavailable. Supply -SwiplPath with SWI-Prolog 10.0.2.'
    }

    & $winget.Source install --id $wingetPackage --version $toolVersion --exact `
        --silent --accept-package-agreements --accept-source-agreements `
        --disable-interactivity
    if ($LASTEXITCODE -ne 0) {
        throw "winget failed with exit code $LASTEXITCODE."
    }
}

function Find-PinnedSwipl {
    foreach ($candidate in $installedCandidates) {
        if (Test-Path -LiteralPath $candidate) {
            return $candidate
        }
    }

    $systemSwipl = Get-Command swipl -ErrorAction SilentlyContinue
    if ($null -ne $systemSwipl) {
        return $systemSwipl.Source
    }

    return $null
}

if (-not (Test-Path -LiteralPath $modelPath)) {
    throw "Scoring process model not found: $modelPath"
}

if ([string]::IsNullOrWhiteSpace($SwiplPath)) {
    $SwiplPath = Find-PinnedSwipl
    if ([string]::IsNullOrWhiteSpace($SwiplPath) -and $Bootstrap) {
        Install-PinnedSwipl
        $SwiplPath = Find-PinnedSwipl
    }
    if ([string]::IsNullOrWhiteSpace($SwiplPath)) {
        throw 'SWI-Prolog is unavailable. Run this script again with -Bootstrap.'
    }
}

$resolvedSwiplPath = (Resolve-Path -LiteralPath $SwiplPath).Path
$versionText = Get-SwiplVersion -Executable $resolvedSwiplPath

Write-Output "SWIPL_PATH=$resolvedSwiplPath"
Write-Output "SWIPL_VERSION=$versionText"
Write-Output "MODEL=$modelPath"

Push-Location $repositoryRoot
try {
    & $resolvedSwiplPath -q -s $modelPath -g run_tests -t halt
    if ($LASTEXITCODE -ne 0) {
        throw "PLUnit failed with exit code $LASTEXITCODE."
    }
}
finally {
    Pop-Location
}

Write-Output 'PLUNIT_RESULT=PASS'
