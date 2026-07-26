# Stop leftover Windows (Tauri/Vantage) dev sessions so windows-run can bind cleanly.
# Safe targets: Vite port, Tauri app process, and listeners on the Vite port only.

param(
    [int]$VitePort = 5173,
    [switch]$Quiet
)

$ErrorActionPreference = "SilentlyContinue"
$stopped = New-Object System.Collections.Generic.List[string]

function Write-Info([string]$msg) {
    if (-not $Quiet) { Write-Host $msg }
}

# 1) Tauri / app window process
Get-Process -Name "discount-screener-windows" -ErrorAction SilentlyContinue | ForEach-Object {
    Write-Info "Stopping app process PID $($_.Id) ($($_.ProcessName))"
    Stop-Process -Id $_.Id -Force
    $stopped.Add("app:$($_.Id)") | Out-Null
}

# 2) Anything listening (or connected) on the Vite port
$pidsOnPort = @(
    Get-NetTCPConnection -LocalPort $VitePort -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty OwningProcess -Unique |
        Where-Object { $_ -and $_ -gt 0 }
)
foreach ($procId in $pidsOnPort) {
    $p = Get-Process -Id $procId -ErrorAction SilentlyContinue
    if (-not $p) { continue }
    Write-Info "Stopping PID $procId ($($p.ProcessName)) on port $VitePort"
    Stop-Process -Id $procId -Force
    $stopped.Add("port:$procId") | Out-Null
}

# 3) Brief settle so the port is released
if ($stopped.Count -gt 0) {
    Start-Sleep -Milliseconds 800
}

$still = Get-NetTCPConnection -LocalPort $VitePort -State Listen -ErrorAction SilentlyContinue
if ($still) {
    Write-Info "WARNING: port $VitePort still in use after cleanup"
    exit 1
}

if ($stopped.Count -eq 0) {
    Write-Info "No previous Windows dev session found (port $VitePort free)"
} else {
    $joined = [string]::Join(", ", $stopped)
    Write-Info "Closed previous session(s): $joined"
}
exit 0
