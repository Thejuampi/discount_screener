# Build Flutter Windows release without Developer Mode.
#
# Flutter's tool creates plugin *symlinks*, which need either Developer Mode or
# admin rights. Directory *junctions* work without either; if they already
# exist, Flutter skips symlink creation (force: false on build).
#
# Usage (from repo root):
#   pwsh scripts/build-flutter-windows.ps1
#   pwsh scripts/build-flutter-windows.ps1 -Run
#
# Output:
#   apps/flutter/build/windows/x64/runner/Release/discount_screener.exe
# (run from that folder - DLLs and data/ must sit next to the exe)

[CmdletBinding()]
param(
    [switch]$Run,
    [switch]$DebugBuild
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$flutterApp = Join-Path $repoRoot "apps\flutter"

function Resolve-Flutter {
    $cmd = Get-Command flutter -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }
    $candidates = @(
        "C:\src\flutter\bin\flutter.bat",
        "$env:LOCALAPPDATA\flutter\bin\flutter.bat",
        "$env:USERPROFILE\flutter\bin\flutter.bat"
    )
    foreach ($c in $candidates) {
        if (Test-Path $c) { return $c }
    }
    throw "flutter not found on PATH. Install Flutter or add it to PATH."
}

function Ensure-PluginJunctions {
    param(
        [string]$Platform,
        [string]$LinkRootRel,
        [string]$AppRoot
    )
    $depsFile = Join-Path $AppRoot ".flutter-plugins-dependencies"
    if (-not (Test-Path $depsFile)) {
        Write-Host "Missing $depsFile - run flutter pub get first"
        return
    }
    $deps = Get-Content $depsFile -Raw | ConvertFrom-Json
    $plugins = $deps.plugins.$Platform
    if (-not $plugins) {
        Write-Host "No $Platform plugins listed"
        return
    }
    $linkRoot = Join-Path $AppRoot $LinkRootRel
    New-Item -ItemType Directory -Force -Path $linkRoot | Out-Null
    foreach ($plugin in $plugins) {
        $name = $plugin.name
        $path = ($plugin.path -replace '\\\\', '\')
        $linkPath = Join-Path $linkRoot $name
        if (Test-Path $linkPath) {
            cmd /c "rmdir `"$linkPath`"" 2>$null | Out-Null
            Remove-Item -Force -Recurse $linkPath -ErrorAction SilentlyContinue
        }
        Write-Host "  [$Platform] junction $name"
        $null = cmd /c "mklink /J `"$linkPath`" `"$path`""
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to create junction for $name -> $path"
        }
    }
}

$flutter = Resolve-Flutter
Write-Host "Using Flutter: $flutter"
Set-Location $flutterApp

Write-Host "flutter pub get..."
& $flutter pub get
if ($LASTEXITCODE -ne 0) { throw "flutter pub get failed" }

Write-Host "Preparing plugin junctions (no Developer Mode required)..."
Ensure-PluginJunctions -Platform "windows" -LinkRootRel "windows\flutter\ephemeral\.plugin_symlinks" -AppRoot $flutterApp
# Flutter also walks the linux host folder when present; pre-seed those links too.
if (Test-Path (Join-Path $flutterApp "linux")) {
    Ensure-PluginJunctions -Platform "linux" -LinkRootRel "linux\flutter\ephemeral\.plugin_symlinks" -AppRoot $flutterApp
}

$mode = if ($DebugBuild) { "--debug" } else { "--release" }
Write-Host "flutter build windows $mode..."
& $flutter build windows $mode
if ($LASTEXITCODE -ne 0) { throw "flutter build windows failed" }

$exeDir = if ($DebugBuild) {
    Join-Path $flutterApp "build\windows\x64\runner\Debug"
} else {
    Join-Path $flutterApp "build\windows\x64\runner\Release"
}
$exe = Join-Path $exeDir "discount_screener.exe"
if (-not (Test-Path $exe)) {
    throw "Build finished but exe not found at $exe"
}

Write-Host ""
Write-Host "Built: $exe"
Write-Host "Run the whole folder (exe + flutter_windows.dll + data\):"
Write-Host "  $exeDir"

if ($Run) {
    Write-Host "Launching..."
    Start-Process -FilePath $exe -WorkingDirectory $exeDir
}
