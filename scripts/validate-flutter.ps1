# Validates the Flutter sibling app (domain contracts + analyze + widget tests).
$ErrorActionPreference = "Stop"
$RepoRoot = Split-Path -Parent $PSScriptRoot
$FlutterApp = Join-Path $RepoRoot "apps\flutter"
$DsCore = Join-Path $FlutterApp "packages\ds_core"
$DsData = Join-Path $FlutterApp "packages\ds_data"

function Require-Command($name) {
    if (-not (Get-Command $name -ErrorAction SilentlyContinue)) {
        throw "Required command not found: $name (install Flutter and add it to PATH)"
    }
}

Require-Command flutter
Require-Command dart

Push-Location $DsCore
try {
    Write-Host "== ds_core: dart pub get =="
    dart pub get
    Write-Host "== ds_core: dart test =="
    dart test
} finally {
    Pop-Location
}

Push-Location $DsData
try {
    Write-Host "== ds_data: dart pub get =="
    dart pub get
    Write-Host "== ds_data: dart test =="
    dart test
} finally {
    Pop-Location
}

Push-Location $FlutterApp
try {
    Write-Host "== flutter: pub get =="
    flutter pub get
    Write-Host "== flutter: analyze =="
    flutter analyze
    Write-Host "== flutter: test =="
    flutter test
} finally {
    Pop-Location
}

Write-Host "Flutter validation OK."
