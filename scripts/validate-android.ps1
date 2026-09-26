$ErrorActionPreference = "Stop"

function Invoke-AndroidGradle {
    param([string[]]$GradleArgs)

    & ./gradlew @GradleArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle failed: $($GradleArgs -join ' ')"
    }
}

Push-Location "$PSScriptRoot/../apps/android"
try {
    Invoke-AndroidGradle -GradleArgs @(':core:test')

    $localProperties = Join-Path (Get-Location) "local.properties"
    $hasSdk = [bool]$env:ANDROID_HOME -or [bool]$env:ANDROID_SDK_ROOT -or (Test-Path $localProperties)
    if ($hasSdk) {
        # The app suite exceeds the three-minute Gradle kill switch as one task.
        # Each filter runs in its own bounded task and keeps that hang guard useful.
        Invoke-AndroidGradle -GradleArgs @(':app:testDebugUnitTest', '--tests', 'com.discountscreener.android.data.*', '--tests', 'com.discountscreener.android.presentation.*', '-PloadTimingProbes=exclude', '--rerun')
        # Wall-clock budgets require one probe at a time, without the rest of the suite competing for CPU.
        Invoke-AndroidGradle -GradleArgs @(':app:testDebugUnitTest', '-PloadTimingProbes=only', '--max-workers=1', '--rerun')
        Invoke-AndroidGradle -GradleArgs @(':app:testDebugUnitTest', '--tests', 'com.discountscreener.android.ui.*', '--rerun')
        Invoke-AndroidGradle -GradleArgs @(':app:testDebugUnitTest', '--tests', 'com.discountscreener.android.app.*', '--tests', 'com.discountscreener.android.domain.*', '--tests', 'com.discountscreener.android.performance.*', '--tests', 'com.discountscreener.android.StuckTestWatchdogTest', '--rerun')
        Invoke-AndroidGradle -GradleArgs @(':app:assembleDebug')
    } else {
        Write-Host "Android SDK not configured. Skipping :app:testDebugUnitTest and :app:assembleDebug."
    }
} finally {
    Pop-Location
}
