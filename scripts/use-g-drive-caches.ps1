# Apply G:\dev\caches tooling paths for the current PowerShell session.
# Permanent User env vars are set by setup-g-drive-caches.ps1; this reloads them
# into a long-lived shell that was started before that change.

$ErrorActionPreference = 'Stop'
$root = 'G:\dev\caches'

$map = @{
    CARGO_HOME             = "$root\cargo"
    RUSTUP_HOME            = "$root\rustup"
    GRADLE_USER_HOME       = "$root\gradle"
    ANDROID_HOME           = "$root\android-sdk"
    ANDROID_SDK_ROOT       = "$root\android-sdk"
    ANDROID_USER_HOME      = "$root\android-home"
    ANDROID_AVD_HOME       = "$root\android-home\avd"
    ANDROID_EMULATOR_HOME  = "$root\android-home"
    TMP                    = "$root\tmp"
    TEMP                   = "$root\tmp"
    CARGO_MUTANTS_OUTPUT   = "$root\mutants"
}

foreach ($kv in $map.GetEnumerator()) {
    Set-Item -Path "Env:$($kv.Key)" -Value $kv.Value
}

$prepend = @(
    "$root\cargo\bin",
    "$root\android-sdk\platform-tools",
    "$root\android-sdk\emulator"
) -join ';'
$env:Path = "$prepend;$env:Path"

Write-Host "Session caches on G:\"
$map.GetEnumerator() | Sort-Object Name | ForEach-Object { "  $($_.Key)=$($_.Value)" }
Write-Host "cargo -> $((Get-Command cargo -ErrorAction SilentlyContinue).Source)"
Write-Host "TMP   -> $env:TMP"
