$ErrorActionPreference = "Stop"

Push-Location "$PSScriptRoot/../apps/android"
try {
    ./gradlew :core:test

    $localProperties = Join-Path (Get-Location) "local.properties"
    $hasSdk = [bool]$env:ANDROID_HOME -or [bool]$env:ANDROID_SDK_ROOT -or (Test-Path $localProperties)
    if ($hasSdk) {
        ./gradlew :app:testDebugUnitTest :app:assembleDebug
    } else {
        Write-Host "Android SDK not configured. Skipping :app:testDebugUnitTest and :app:assembleDebug."
    }
} finally {
    Pop-Location
}
