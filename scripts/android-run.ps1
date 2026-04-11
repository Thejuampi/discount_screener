param()

$ErrorActionPreference = 'Stop'

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

    $lines = & $AdbPath devices -l | Select-Object -Skip 1
    return $lines |
        ForEach-Object { $_.Trim() } |
        Where-Object { $_ } |
        ForEach-Object {
            $parts = $_ -split '\s+'
            if ($parts.Length -lt 2) {
                return
            }
            $model = $null
            foreach ($part in $parts) {
                if ($part -match '^model:(.+)$') {
                    $model = $matches[1]
                    break
                }
            }
            [pscustomobject]@{
                Serial = $parts[0]
                State = $parts[1]
                Model = $model
                IsEmulator = $parts[0] -like 'emulator-*'
            }
        } |
        Where-Object { $_.State -ne 'offline' }
}

function Get-OnlineDevices {
    param(
        [string]$AdbPath
    )

    return Get-AdbDevices -AdbPath $AdbPath |
        Where-Object { $_.State -eq 'device' }
}

function Get-UnauthorizedPhysicalDevices {
    param(
        [string]$AdbPath
    )

    return Get-AdbDevices -AdbPath $AdbPath |
        Where-Object { $_.State -eq 'unauthorized' -and -not $_.IsEmulator }
}

function Select-PreferredDevice {
    param(
        [object[]]$Devices
    )

    return $Devices |
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
        return "$($Device.Serial) [$($Device.Model)]"
    }

    return $Device.Serial
}

function Get-AvdNames {
    param(
        [string]$EmulatorPath
    )

    if (-not $EmulatorPath) {
        return @()
    }

    return (& $EmulatorPath -list-avds) |
        ForEach-Object { $_.Trim() } |
        Where-Object { $_ }
}

function Wait-ForOnlineDevices {
    param(
        [string]$AdbPath,
        [int]$TimeoutSeconds
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $devices = Get-OnlineDevices -AdbPath $AdbPath
        if ($devices) {
            return $devices
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

    if (-not (Get-Process emulator -ErrorAction SilentlyContinue)) {
        Write-Host "Starting Android emulator '$selectedAvd'..."
        Start-Process -FilePath $emulator -ArgumentList @('-avd', $selectedAvd)
        Start-Sleep -Seconds 5
    }

    $onlineDevices = Wait-ForOnlineDevices -AdbPath $adb -TimeoutSeconds 180
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
