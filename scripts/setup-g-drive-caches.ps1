# One-shot / re-run: ensure G:\dev\caches layout, User env vars, and optional junctions.
# Safe to re-run. Does not delete C: backups automatically.

$ErrorActionPreference = 'Stop'
$root = 'G:\dev\caches'

$dirs = @(
    "$root\cargo",
    "$root\rustup",
    "$root\gradle",
    "$root\android-sdk",
    "$root\android-home",
    "$root\android-home\avd",
    "$root\tmp",
    "$root\mutants",
    "$root\cargo-target"
)
foreach ($d in $dirs) {
    New-Item -ItemType Directory -Force -Path $d | Out-Null
}

function Set-UserEnv([string]$Name, [string]$Value) {
    [Environment]::SetEnvironmentVariable($Name, $Value, 'User')
    Set-Item -Path "Env:$Name" -Value $Value
}

Set-UserEnv 'CARGO_HOME' "$root\cargo"
Set-UserEnv 'RUSTUP_HOME' "$root\rustup"
Set-UserEnv 'GRADLE_USER_HOME' "$root\gradle"
Set-UserEnv 'ANDROID_HOME' "$root\android-sdk"
Set-UserEnv 'ANDROID_SDK_ROOT' "$root\android-sdk"
Set-UserEnv 'ANDROID_USER_HOME' "$root\android-home"
Set-UserEnv 'ANDROID_AVD_HOME' "$root\android-home\avd"
Set-UserEnv 'ANDROID_EMULATOR_HOME' "$root\android-home"
Set-UserEnv 'TMP' "$root\tmp"
Set-UserEnv 'TEMP' "$root\tmp"
Set-UserEnv 'CARGO_MUTANTS_OUTPUT' "$root\mutants"

$userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
$parts = @()
if ($userPath) {
    $parts = $userPath -split ';' | Where-Object { $_ -and $_.Trim() -ne '' }
}
$parts = $parts | Where-Object {
    $_ -notmatch '\\.cargo\\bin$' -and $_ -ne 'C:\Users\Juan\.cargo\bin'
}
$prepend = @(
    "$root\cargo\bin",
    "$root\android-sdk\platform-tools",
    "$root\android-sdk\emulator"
)
foreach ($p in $prepend) {
    if ($parts -notcontains $p) { $parts = @($p) + @($parts) }
}
[Environment]::SetEnvironmentVariable('Path', ($parts -join ';'), 'User')

# Broadcast so Explorer / new children pick up HKCU without full logoff
try {
    Add-Type -Namespace Win32Env -Name Native -MemberDefinition @'
[DllImport("user32.dll", SetLastError=true, CharSet=CharSet.Auto)]
public static extern IntPtr SendMessageTimeout(IntPtr hWnd, uint Msg, UIntPtr wParam, string lParam, uint fuFlags, uint uTimeout, out UIntPtr lpdwResult);
'@ -ErrorAction SilentlyContinue
    $result = [UIntPtr]::Zero
    [void][Win32Env.Native]::SendMessageTimeout([IntPtr]0xffff, 0x1A, [UIntPtr]::Zero, 'Environment', 2, 5000, [ref]$result)
} catch { }

# Keep logon helper until Local\Temp is a junction to G tmp
$complete = "$root\complete-temp-junction.ps1"
if (Test-Path $complete) {
    $taskName = 'GDriveTempJunction'
    Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue
    $action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$complete`""
    $trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
    $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable
    Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Settings $settings -Force | Out-Null
}

Write-Host "Permanent User env vars point at $root (HKCU\Environment)."
Write-Host "  TMP/TEMP = $root\tmp  (survives reboot; new processes after logon/new terminal)"
Write-Host "Open a NEW terminal (or run scripts/use-g-drive-caches.ps1) so this session matches."
Write-Host "Local\Temp junction is completed by logon task GDriveTempJunction when C: locks release."
Write-Host "If C:\Users\Juan\.cargo or .gradle still hold old copies, close IDE/daemons then run:"
Write-Host "  scripts/cleanup-c-drive-tooling-backups.ps1"
