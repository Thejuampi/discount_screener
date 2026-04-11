param(
    [switch]$Worker,
    [switch]$Headless,
    [string]$AvdName = "discount_screener_api35",
    [string]$AppId = "com.discountscreener.android",
    [string]$LaunchActivity = "com.discountscreener.android.app.MainActivity",
    [int]$BootTimeoutMinutes = 15
)

$ErrorActionPreference = "Stop"

$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$AndroidDir = Join-Path $RepoRoot "apps/android"
$SdkRootFile = Join-Path $AndroidDir "local.properties"
$TempRoot = Join-Path $env:TEMP "discount-screener-android"
$WorkerLog = Join-Path $TempRoot "launch-worker.log"
$WorkerErr = Join-Path $TempRoot "launch-worker.err.log"
$EmuLog = Join-Path $TempRoot "emulator.log"
$EmuErr = Join-Path $TempRoot "emulator.err.log"

function Resolve-PowerShellHost {
    $candidates = @()
    $pwsh = Get-Command pwsh -ErrorAction SilentlyContinue
    if ($pwsh) { $candidates += $pwsh.Source }
    $powershell = Get-Command powershell -ErrorAction SilentlyContinue
    if ($powershell) { $candidates += $powershell.Source }
    $candidates += (Join-Path $PSHOME "pwsh.exe")
    $candidates += (Join-Path $PSHOME "powershell.exe")
    $candidates = $candidates | Where-Object { $_ } | Select-Object -Unique

    foreach ($candidate in $candidates) {
        if (Test-Path $candidate) {
            return $candidate
        }
    }

    throw "Could not locate a PowerShell host executable."
}

function Convert-ToGradlePath([string]$Path) {
    return (($Path -replace "\\", "\\\\") -replace ":", "\\:")
}

function Resolve-SdkRoot {
    foreach ($candidate in @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME)) {
        if ($candidate -and (Test-Path $candidate)) {
            return (Resolve-Path $candidate).Path
        }
    }

    if (Test-Path $SdkRootFile) {
        foreach ($line in Get-Content $SdkRootFile) {
            if ($line -match '^sdk\.dir=(.+)$') {
                return (($matches[1] -replace '\\:', ':') -replace '\\\\', '\')
            }
        }
    }

    throw "Android SDK not configured. Set ANDROID_SDK_ROOT or create apps/android/local.properties."
}

function Resolve-Tool([string]$SdkRoot, [string]$RelativePath) {
    $tool = Join-Path $SdkRoot $RelativePath
    if (-not (Test-Path $tool)) {
        throw "Missing Android SDK tool: $RelativePath"
    }
    return $tool
}

function Ensure-LocalProperties([string]$SdkRoot) {
    if (Test-Path $SdkRootFile) {
        return
    }

    $content = "sdk.dir=$(Convert-ToGradlePath $SdkRoot)"
    Set-Content -Path $SdkRootFile -Value $content -NoNewline
}

function Ensure-Avd([string]$SdkRoot, [string]$AvdName) {
    $emulator = Resolve-Tool $SdkRoot "emulator/emulator.exe"
    $avdManager = Resolve-Tool $SdkRoot "cmdline-tools/latest/bin/avdmanager.bat"
    $existing = & $emulator -list-avds 2>$null
    if ($existing -contains $AvdName) {
        return
    }

    "no" | & $avdManager create avd -n $AvdName -k "system-images;android-35;google_apis;x86_64" -d pixel --force | Out-Null
}

function Start-EmulatorDetached([string]$SdkRoot, [string]$AvdName, [switch]$Headless) {
    $emulator = Resolve-Tool $SdkRoot "emulator/emulator.exe"
    New-Item -ItemType Directory -Force -Path $TempRoot | Out-Null
    $arguments = @(
        "-avd", $AvdName,
        "-no-audio",
        "-no-boot-anim",
        "-no-snapshot-load",
        "-no-snapshot-save",
        "-gpu", "swiftshader_indirect"
    )
    if ($Headless) {
        $arguments += "-no-window"
    }
    Start-Process -FilePath $emulator -ArgumentList $arguments -RedirectStandardOutput $EmuLog -RedirectStandardError $EmuErr | Out-Null
}

function Stop-StaleEmulator {
    $processes = @()
    $processes += Get-Process emulator -ErrorAction SilentlyContinue
    $processes += Get-Process qemu-system-x86_64 -ErrorAction SilentlyContinue
    $processes += Get-Process qemu-system-x86_64-headless -ErrorAction SilentlyContinue
    $processes = $processes | Where-Object { $_ } | Sort-Object Id -Unique

    foreach ($process in $processes) {
        try {
            Stop-Process -Id $process.Id -Force -ErrorAction Stop
        } catch {
            Write-Warning "Could not stop emulator process $($process.Id): $($_.Exception.Message)"
        }
    }
}

function Get-AdbDeviceState([string]$SdkRoot) {
    $adb = Resolve-Tool $SdkRoot "platform-tools/adb.exe"
    $devices = & $adb devices
    foreach ($line in $devices) {
        if ($line -match '^(emulator-\d+)\s+(device|offline)\b') {
            return [pscustomobject]@{
                Serial = $matches[1]
                State  = $matches[2]
            }
        }
    }
    return $null
}

function Wait-For-Emulator([string]$SdkRoot, [string]$Serial, [int]$BootTimeoutMinutes) {
    $adb = Resolve-Tool $SdkRoot "platform-tools/adb.exe"
    $deadline = (Get-Date).AddMinutes($BootTimeoutMinutes)

    while ((Get-Date) -lt $deadline) {
        $device = Get-AdbDeviceState $SdkRoot
        if ($device) {
            if (-not $Serial) {
                $Serial = $device.Serial
            }

            if ($device.Serial -eq $Serial -and $device.State -eq "device") {
                $bootOutput = & $adb -s $Serial shell getprop sys.boot_completed 2>$null
                $booted = if ($null -ne $bootOutput) { $bootOutput.ToString().Trim() } else { "" }
                if ($booted -eq "1") {
                    return $Serial
                }
            }
        }

        Start-Sleep -Seconds 5
    }

    throw "Emulator did not reach boot completion within $BootTimeoutMinutes minutes."
}

function Launch-App([string]$SdkRoot, [string]$Serial, [string]$AppId, [string]$LaunchActivity) {
    $adb = Resolve-Tool $SdkRoot "platform-tools/adb.exe"
    & $adb -s $Serial install -r (Join-Path $AndroidDir "app/build/outputs/apk/debug/app-debug.apk") | Out-Host
    & $adb -s $Serial shell am start -W -n "$AppId/$LaunchActivity" | Out-Host
}

if (-not $Worker) {
    New-Item -ItemType Directory -Force -Path $TempRoot | Out-Null
    $hostExe = Resolve-PowerShellHost
    $args = @(
        "-NoProfile",
        "-ExecutionPolicy", "Bypass",
        "-File", $PSCommandPath,
        "-Worker",
        "-AvdName", $AvdName,
        "-AppId", $AppId,
        "-LaunchActivity", $LaunchActivity,
        "-BootTimeoutMinutes", $BootTimeoutMinutes
    )
    if ($Headless) {
        $args += "-Headless"
    }
    $process = Start-Process -FilePath $hostExe -ArgumentList $args -PassThru -WindowStyle Hidden -RedirectStandardOutput $WorkerLog -RedirectStandardError $WorkerErr
    Write-Host "Android launch worker started in the background."
    Write-Host "Worker PID: $($process.Id)"
    Write-Host "Logs: $WorkerLog"
    Write-Host "Errors: $WorkerErr"
    Write-Host "Emulator logs: $EmuLog"
    Write-Host "Emulator errors: $EmuErr"
    return
}

$SdkRoot = Resolve-SdkRoot
Ensure-LocalProperties $SdkRoot

Push-Location $AndroidDir
try {
    ./gradlew :app:assembleDebug
} finally {
    Pop-Location
}

Ensure-Avd $SdkRoot $AvdName

$device = Get-AdbDeviceState $SdkRoot
$serial = $null
if (-not $device) {
    Start-EmulatorDetached -SdkRoot $SdkRoot -AvdName $AvdName
    $serial = Wait-For-Emulator $SdkRoot $null $BootTimeoutMinutes
} elseif ($device.State -ne "device") {
    Stop-StaleEmulator
    Start-Sleep -Seconds 5
    Start-EmulatorDetached -SdkRoot $SdkRoot -AvdName $AvdName
    $serial = Wait-For-Emulator $SdkRoot $device.Serial $BootTimeoutMinutes
} else {
    $serial = $device.Serial
}

Launch-App $SdkRoot $serial $AppId $LaunchActivity
