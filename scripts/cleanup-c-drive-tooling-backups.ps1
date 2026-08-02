# After setup-g-drive-caches.ps1 and with no cargo/gradle daemons holding locks:
# - rename remaining real C: cache dirs
# - create junctions to G:\dev\caches
# - remove *.bak-c-drive trees to free C: space
#
# Review sizes before deleting backups.

$ErrorActionPreference = 'Continue'
$root = 'G:\dev\caches'

function Ensure-Junction([string]$Link, [string]$Target, [string]$Label) {
    if (-not (Test-Path $Target)) {
        Write-Host "[$Label] missing target $Target"
        return
    }
    if (Test-Path $Link) {
        $item = Get-Item $Link -Force
        if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) {
            Write-Host "[$Label] already junction: $Link"
            return
        }
        $bak = "$Link.bak-c-drive"
        if (Test-Path $bak) { $bak = "$Link.bak-c-drive-$(Get-Date -Format 'yyyyMMddHHmmss')" }
        Write-Host "[$Label] rename $Link -> $bak"
        Rename-Item -LiteralPath $Link -NewName (Split-Path $bak -Leaf) -Force
    }
    Write-Host "[$Label] junction $Link => $Target"
    cmd /c "mklink /J `"$Link`" `"$Target`"" | Out-Host
}

Ensure-Junction 'C:\Users\Juan\.cargo' "$root\cargo" 'cargo'
Ensure-Junction 'C:\Users\Juan\.rustup' "$root\rustup" 'rustup'
Ensure-Junction 'C:\Users\Juan\.gradle' "$root\gradle" 'gradle'
Ensure-Junction 'C:\Users\Juan\.android' "$root\android-home" 'android-home'
Ensure-Junction 'C:\Users\Juan\AppData\Local\Android\Sdk' "$root\android-sdk" 'android-sdk'
Ensure-Junction 'C:\Android\sdk' "$root\android-sdk" 'android-sdk-legacy'

Write-Host ''
Write-Host 'Backup candidates on C: (delete manually when sure):'
Get-ChildItem 'C:\Users\Juan' -Force -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -like '*.bak-c-drive*' } |
    ForEach-Object { $_.FullName }
Get-ChildItem 'C:\Users\Juan\AppData\Local\Android' -Force -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -like '*.bak-c-drive*' } |
    ForEach-Object { $_.FullName }

Write-Host ''
Write-Host 'To free space after junctions work (example):'
Write-Host '  Remove-Item -Recurse -Force $env:USERPROFILE\.cargo.bak-c-drive'
Write-Host '  Remove-Item -Recurse -Force $env:USERPROFILE\.gradle.bak-c-drive'
