# Single source of truth for the app's date-based version. Prints two lines to stdout:
#   line 1: version string   (release: 2026.08.23.af12bce9[-dirty]; feature: 2026.08.23.<branch>.af12bce9[-dirty])
#   line 2: Android versionCode (YYYYMMDD)
#
# Called from apps/windows/src-tauri/build.rs and apps/android/app/build.gradle.kts so
# both platforms are stamped from exactly one place.

$ErrorActionPreference = "Stop"

$date = Get-Date -Format "yyyy.MM.dd"
$versionCode = Get-Date -Format "yyyyMMdd"

$branch = (git rev-parse --abbrev-ref HEAD).Trim()
if ($branch -eq "HEAD") {
    # Detached HEAD (e.g. CI checked out a tag/commit directly): treat as a feature build.
    $branch = "detached"
}

$shortHash = (git rev-parse --short HEAD).Trim()

$dirty = ((git status --porcelain) | Measure-Object).Count -gt 0
$dirtySuffix = if ($dirty) { "-dirty" } else { "" }

$isRelease = ($branch -eq "main") -or ($branch -eq "master")

if ($isRelease) {
    $version = "$date.$shortHash$dirtySuffix"
} else {
    $safeBranch = ($branch.ToLowerInvariant() -replace '[^a-z0-9-]', '-')
    $version = "$date.$safeBranch.$shortHash$dirtySuffix"
}

Write-Output $version
Write-Output $versionCode
