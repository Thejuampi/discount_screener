# Single source of truth for the app's date-based version. Prints two lines to stdout:
#   line 1: version string   (release: 2026.08.23.1432.af12bce9[-dirty]; feature: 2026.08.23.1432.<branch>.af12bce9[-dirty])
#   line 2: Android versionCode (YYYYMMDD)
#
# Date and time come from the commit being built, not the build clock: two builds of the
# same commit always produce the same version. The HHmm component is what makes two
# same-day releases orderable — the short hash alone ties a build to a git point but two
# hashes don't tell you which one is newer.
#
# Called from apps/windows/src-tauri/build.rs and apps/android/app/build.gradle.kts so
# both platforms are stamped from exactly one place.

$ErrorActionPreference = "Stop"

$commitStamp = (git log -1 --format="%cd" --date="format:%Y.%m.%d.%H%M").Trim()
$versionCode = (git log -1 --format="%cd" --date="format:%Y%m%d").Trim()

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
    $version = "$commitStamp.$shortHash$dirtySuffix"
} else {
    $safeBranch = ($branch.ToLowerInvariant() -replace '[^a-z0-9-]', '-')
    $version = "$commitStamp.$safeBranch.$shortHash$dirtySuffix"
}

Write-Output $version
Write-Output $versionCode
