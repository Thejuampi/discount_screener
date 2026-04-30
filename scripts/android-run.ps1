param()

$ErrorActionPreference = 'Stop'

$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot '..')
$RunLogDir = Join-Path $RepoRoot '.agents\workspace\tmp\android-run'
$EmulatorOutLog = Join-Path $RunLogDir 'emulator.out.log'
$EmulatorErrLog = Join-Path $RunLogDir 'emulator.err.log'

function Resolve-AdbPath {
    $sdkAdb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
    if (Test-Path $sdkAdb) {
        return $sdkAdb
    }

    $adbCommand = Get-Command adb -ErrorAction SilentlyContinue
    if ($adbCommand) {
        return $adbCommand.Source
    }

    throw 'adb.exe was not found. Install Android platform-tools or add adb to PATH.'
}

function Resolve-EmulatorPath {
    $sdkEmulator = Join-Path $env:LOCALAPPDATA 'Android\Sdk\emulator\emulator.exe'
    if (Test-Path $sdkEmulator) {
        return $sdkEmulator
    }

    $emulatorCommand = Get-Command emulator -ErrorAction SilentlyContinue
    if ($emulatorCommand) {
        return $emulatorCommand.Source
    }

    return $null
}

function Get-AdbDevices {
    param(
        [string]$AdbPath
    )

    $devices = @()
    $lines = & $AdbPath devices -l | Select-Object -Skip 1
    foreach ($line in $lines) {
        $trimmed = $line.Trim()
        if (-not $trimmed) {
            continue
        }

        $parts = $trimmed -split '\s+'
        if ($parts.Length -lt 2) {
            continue
        }

        $model = $null
        foreach ($part in $parts) {
            if ($part -match '^model:(.+)$') {
                $model = $matches[1]
                break
            }
        }

        $devices += [pscustomobject]@{
            Serial = $parts[0]
            State = $parts[1]
            Model = $model
            IsEmulator = $parts[0] -like 'emulator-*'
        }
    }

    $devices
}

function Get-OnlineDevices {
    param(
        [string]$AdbPath
    )

    Get-AdbDevices -AdbPath $AdbPath |
        Where-Object { $_.State -eq 'device' }
}

function Get-UnauthorizedPhysicalDevices {
    param(
        [string]$AdbPath
    )

    Get-AdbDevices -AdbPath $AdbPath |
        Where-Object { $_.State -eq 'unauthorized' -and -not $_.IsEmulator }
}

function Select-PreferredDevice {
    param(
        [object[]]$Devices
    )

    $Devices |
        Sort-Object @{ Expression = { if ($_.IsEmulator) { 1 } else { 0 } } }, Serial |
        Select-Object -First 1
}

function Format-DeviceLabel {
    param(
        [object]$Device
    )

    if (-not $Device) {
        return ""
    }

    if ($Device.Model) {
        "$($Device.Serial) [$($Device.Model)]"
        return
    }

    $Device.Serial
}

function Get-AvdNames {
    param(
        [string]$EmulatorPath
    )

    if (-not $EmulatorPath) {
        @()
        return
    }

    & $EmulatorPath -list-avds |
        ForEach-Object { $_.Trim() } |
        Where-Object { $_ }
}

function Get-EmulatorProcesses {
    $processes = @()
    $processes += Get-Process emulator -ErrorAction SilentlyContinue
    $processes += Get-Process qemu-system-x86_64 -ErrorAction SilentlyContinue
    $processes += Get-Process qemu-system-x86_64-headless -ErrorAction SilentlyContinue

    $processes |
        Where-Object { $_ } |
        Sort-Object Id -Unique
}

function Stop-StaleEmulatorProcesses {
    $processes = Get-EmulatorProcesses
    if (-not $processes) {
        return
    }

    $ids = ($processes | ForEach-Object { $_.Id }) -join ', '
    Write-Host "Stopping stale Android emulator process(es): $ids"
    foreach ($process in $processes) {
        try {
            Stop-Process -Id $process.Id -Force -ErrorAction Stop
        } catch {
            Write-Warning "Could not stop emulator process $($process.Id): $($_.Exception.Message)"
        }
    }
}

function Get-EmulatorLogSummary {
    param(
        [string[]]$LogPaths
    )

    $lines = @()
    foreach ($logPath in $LogPaths) {
        if (-not (Test-Path $logPath)) {
            continue
        }

        $logLines = Get-Content -Path $logPath -ErrorAction SilentlyContinue
        $interestingLines = $logLines |
            Where-Object { $_ -match '(?i)(error|fatal|panic|fail|cannot|not enough|insufficient|denied)' } |
            Select-Object -Last 12

        if ($interestingLines) {
            $lines += "From ${logPath}:"
            $lines += $interestingLines
        }
    }

    if (-not $lines) {
        foreach ($logPath in $LogPaths) {
            if (Test-Path $logPath) {
                $lines += "Last lines from ${logPath}:"
                $lines += Get-Content -Path $logPath -ErrorAction SilentlyContinue | Select-Object -Last 12
            }
        }
    }

    if (-not $lines) {
        return ''
    }

    return (($lines | Select-Object -First 40) -join [Environment]::NewLine)
}

function Format-CmdArgument {
    param(
        [string]$Value
    )

    return '"' + ($Value -replace '"', '\"') + '"'
}

function Start-AndroidEmulator {
    param(
        [string]$EmulatorPath,
        [string]$AvdName,
        [string]$StdoutLog,
        [string]$StderrLog
    )

    New-Item -ItemType Directory -Force -Path (Split-Path $StdoutLog -Parent) | Out-Null
    Remove-Item -LiteralPath $StdoutLog, $StderrLog -ErrorAction SilentlyContinue

    $arguments = @(
        '-avd', $AvdName,
        '-no-audio',
        '-no-boot-anim'
    )

    $emulatorCommand = ((@($EmulatorPath) + $arguments | ForEach-Object { Format-CmdArgument $_ }) -join ' ')
    $redirectedCommand = "$emulatorCommand > $(Format-CmdArgument $StdoutLog) 2> $(Format-CmdArgument $StderrLog)"

    return Start-Process `
        -FilePath $env:ComSpec `
        -ArgumentList @('/d', '/s', '/c', "`"$redirectedCommand`"") `
        -PassThru `
        -WindowStyle Hidden
}

function Wait-ForOnlineDevices {
    param(
        [string]$AdbPath,
        [int]$TimeoutSeconds,
        [System.Diagnostics.Process]$EmulatorProcess,
        [string[]]$EmulatorLogPaths
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $devices = Get-OnlineDevices -AdbPath $AdbPath
        if ($devices) {
            return $devices
        }
        if ($EmulatorProcess) {
            $EmulatorProcess.Refresh()
            if ($EmulatorProcess.HasExited) {
                $logSummary = Get-EmulatorLogSummary -LogPaths $EmulatorLogPaths
                $message = "Android emulator process exited before a ready device appeared."
                if ($logSummary) {
                    $message = "$message$([Environment]::NewLine)$logSummary"
                }
                [Console]::Error.WriteLine($message)
                exit 1
            }
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)

    return @()
}

function Wait-ForBootCompletion {
    param(
        [string]$AdbPath,
        [string]$Serial,
        [int]$TimeoutSeconds
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $bootCompleted = (& $AdbPath -s $Serial shell getprop sys.boot_completed 2>$null | Out-String).Trim()
        if ($bootCompleted -eq '1') {
            return $true
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)

    return $false
}

try {
$adb = Resolve-AdbPath
$emulator = Resolve-EmulatorPath
& $adb start-server | Out-Null

$onlineDevices = Get-OnlineDevices -AdbPath $adb
if (-not $onlineDevices) {
    & $adb kill-server | Out-Null
    & $adb start-server | Out-Null
    Start-Sleep -Seconds 2
    $onlineDevices = Get-OnlineDevices -AdbPath $adb
}

$unauthorizedPhysicalDevices = Get-UnauthorizedPhysicalDevices -AdbPath $adb

if (-not $onlineDevices) {
    if ($unauthorizedPhysicalDevices) {
        $serials = ($unauthorizedPhysicalDevices | ForEach-Object { Format-DeviceLabel $_ }) -join ', '
        throw "Android phone detected but not authorized for USB debugging: $serials. Unlock the phone, accept the USB debugging prompt, then rerun make android-run."
    }

    $avdNames = Get-AvdNames -EmulatorPath $emulator
    if (-not $avdNames) {
        throw 'No ready Android device or emulator detected, and no Android Virtual Devices were found. Start a device manually, then rerun make android-run.'
    }

    $preferredAvd = $avdNames | Where-Object { $_ -eq 'discount_screener_api35' } | Select-Object -First 1
    $selectedAvd = if ($preferredAvd) { $preferredAvd } else { $avdNames[0] }

    $knownEmulatorDevices = Get-AdbDevices -AdbPath $adb | Where-Object { $_.IsEmulator }
    if (-not $knownEmulatorDevices -and (Get-EmulatorProcesses)) {
        Stop-StaleEmulatorProcesses
        Start-Sleep -Seconds 2
    }

    $emulatorProcess = $null
    if (-not (Get-EmulatorProcesses)) {
        Write-Host "Starting Android emulator '$selectedAvd'..."
        $emulatorProcess = Start-AndroidEmulator `
            -EmulatorPath $emulator `
            -AvdName $selectedAvd `
            -StdoutLog $EmulatorOutLog `
            -StderrLog $EmulatorErrLog
        Start-Sleep -Seconds 5
    } else {
        Write-Host "Waiting for running Android emulator..."
    }

    $onlineDevices = Wait-ForOnlineDevices `
        -AdbPath $adb `
        -TimeoutSeconds 180 `
        -EmulatorProcess $emulatorProcess `
        -EmulatorLogPaths @($EmulatorOutLog, $EmulatorErrLog)
}

if (-not $onlineDevices) {
    throw 'No ready Android device or emulator detected after waiting for boot. Ensure the emulator reaches the Android home screen, then rerun make android-run.'
}

$selectedDevice = Select-PreferredDevice -Devices $onlineDevices

if ($selectedDevice.IsEmulator -and $unauthorizedPhysicalDevices) {
    $serials = ($unauthorizedPhysicalDevices | ForEach-Object { Format-DeviceLabel $_ }) -join ', '
    throw "A USB phone is connected but not authorized for debugging: $serials. Unlock the phone, accept the USB debugging prompt, then rerun make android-run."
}

$env:ANDROID_SERIAL = $selectedDevice.Serial
Write-Host "Installing on Android device '$(Format-DeviceLabel $selectedDevice)'..."

if (-not (Wait-ForBootCompletion -AdbPath $adb -Serial $selectedDevice.Serial -TimeoutSeconds 180)) {
    throw "Android device '$($selectedDevice.Serial)' did not finish booting in time."
}

Push-Location (Join-Path $PSScriptRoot '..\apps\android')
try {
    & .\gradlew.bat installDebug
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
} finally {
    Pop-Location
}

& $adb -s $selectedDevice.Serial shell am start -n com.discountscreener.android/.app.MainActivity
if ($LASTEXITCODE -ne 0) {
    exit $LASTEXITCODE
}
} catch {
    $message = $_.Exception.Message
    if (-not $message) {
        $message = ($_ | Out-String).Trim()
    }
    [Console]::Error.WriteLine($message)
    exit 1
}
