$env:ANDROID_HOME     = "C:\Android\sdk"
$env:ANDROID_SDK_ROOT = "C:\Android\sdk"
$env:JAVA_HOME        = "C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot"
$env:PATH             = "$env:PATH;C:\Android\sdk\platform-tools;C:\Android\sdk\emulator;C:\Android\sdk\cmdline-tools\latest\bin;$env:JAVA_HOME\bin"

$adb = "C:\Android\sdk\platform-tools\adb.exe"
$emulator = "C:\Android\sdk\emulator\emulator.exe"

# Check if emulator is already running
$running = & $adb devices 2>$null | Select-String "emulator"
if (-not $running) {
    Write-Host "Starting emulator..."
    Start-Process -FilePath $emulator -ArgumentList "-avd","ds_pixel","-no-snapshot","-gpu","auto"

    Write-Host "Waiting for device..."
    & $adb wait-for-device

    Write-Host "Waiting for full boot..."
    do {
        Start-Sleep -Seconds 3
        $booted = & $adb shell getprop sys.boot_completed 2>$null
    } while ($booted.Trim() -ne "1")

    Write-Host "Booted. Launching Discount Screener..."
    Start-Sleep -Seconds 2
} else {
    Write-Host "Emulator already running. Launching Discount Screener..."
}

& $adb shell monkey -p com.discountscreener.android -c android.intent.category.LAUNCHER 1 | Out-Null
