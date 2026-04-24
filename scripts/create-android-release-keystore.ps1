param(
    [string]$KeystorePath = "$(Join-Path $env:USERPROFILE '.discount-screener\discount-screener-release.jks')",
    [string]$KeyAlias = "discount-screener",
    [string]$StorePassword,
    [string]$KeyPassword,
    [switch]$UpdateLocalProperties,
    [switch]$Force,
    [switch]$ShowExample
)

$ErrorActionPreference = "Stop"

$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$AndroidDir = Join-Path $RepoRoot "apps\android"
$LocalPropertiesPath = Join-Path $AndroidDir "local.properties"

function Resolve-Keytool {
    $candidates = New-Object System.Collections.Generic.List[string]

    if ($env:JAVA_HOME) {
        $candidates.Add((Join-Path $env:JAVA_HOME "bin\keytool.exe"))
    }

    $keytoolCommand = Get-Command keytool -ErrorAction SilentlyContinue
    if ($keytoolCommand) {
        $candidates.Add($keytoolCommand.Source)
    }

    foreach ($candidate in ($candidates | Select-Object -Unique)) {
        if ($candidate -and (Test-Path $candidate)) {
            return $candidate
        }
    }

    throw "keytool.exe was not found. Install JDK 17+ and set JAVA_HOME or add keytool to PATH."
}

function New-RandomPassword([int]$Length = 24) {
    $allowed = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#$%^&*()-_=+"
    $bytes = New-Object byte[] ($Length * 2)
    [System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
    $builder = New-Object System.Text.StringBuilder
    for ($i = 0; $i -lt $Length; $i++) {
        $index = $bytes[$i] % $allowed.Length
        [void]$builder.Append($allowed[$index])
    }
    return $builder.ToString()
}

function Convert-ToGradlePath([string]$Path) {
    return (($Path -replace "\\", "\\\\") -replace ":", "\\:")
}

function Set-Or-AddPropertyLine {
    param(
        [string[]]$Lines,
        [string]$Key,
        [string]$Value
    )

    $updated = $false
    for ($index = 0; $index -lt $Lines.Length; $index++) {
        if ($Lines[$index] -match "^$([regex]::Escape($Key))=") {
            $Lines[$index] = "$Key=$Value"
            $updated = $true
        }
    }

    if (-not $updated) {
        $Lines += "$Key=$Value"
    }

    return ,$Lines
}

if ($ShowExample) {
    Write-Host "Example:"
    Write-Host "  pwsh .\scripts\create-android-release-keystore.ps1 -UpdateLocalProperties"
    Write-Host ""
    Write-Host "Optional overrides:"
    Write-Host "  -KeystorePath C:\secure\discount-screener-release.jks"
    Write-Host "  -KeyAlias discount-screener"
    Write-Host "  -StorePassword <value>"
    Write-Host "  -KeyPassword <value>"
    exit 0
}

$resolvedKeystorePath = [System.IO.Path]::GetFullPath($KeystorePath)
$storePasswordValue = if ($StorePassword) { $StorePassword } else { New-RandomPassword }
$keyPasswordValue = if ($KeyPassword) { $KeyPassword } else { New-RandomPassword }

if ((Test-Path $resolvedKeystorePath) -and -not $Force) {
    throw "Keystore already exists at '$resolvedKeystorePath'. Use -Force only if you intentionally want to replace it."
}

$keytool = Resolve-Keytool
$keystoreDirectory = Split-Path -Parent $resolvedKeystorePath
if ($keystoreDirectory) {
    New-Item -ItemType Directory -Force -Path $keystoreDirectory | Out-Null
}

if (Test-Path $resolvedKeystorePath) {
    Remove-Item -Force $resolvedKeystorePath
}

& $keytool `
    -genkeypair `
    -v `
    -keystore $resolvedKeystorePath `
    -alias $KeyAlias `
    -keyalg RSA `
    -keysize 2048 `
    -validity 10000 `
    -storepass $storePasswordValue `
    -keypass $keyPasswordValue `
    -dname "CN=Discount Screener, OU=Android, O=Discount Screener, L=Unknown, S=Unknown, C=US"

if ($LASTEXITCODE -ne 0 -or -not (Test-Path $resolvedKeystorePath)) {
    throw "Failed to generate the Android release keystore."
}

$localPropertiesLines = @()
if (Test-Path $LocalPropertiesPath) {
    $localPropertiesLines = Get-Content $LocalPropertiesPath
}

if ($UpdateLocalProperties) {
    $localPropertiesLines = Set-Or-AddPropertyLine -Lines $localPropertiesLines -Key "DISCOUNT_SCREENER_RELEASE_STORE_FILE" -Value (Convert-ToGradlePath $resolvedKeystorePath)
    $localPropertiesLines = Set-Or-AddPropertyLine -Lines $localPropertiesLines -Key "DISCOUNT_SCREENER_RELEASE_STORE_PASSWORD" -Value $storePasswordValue
    $localPropertiesLines = Set-Or-AddPropertyLine -Lines $localPropertiesLines -Key "DISCOUNT_SCREENER_RELEASE_KEY_ALIAS" -Value $KeyAlias
    $localPropertiesLines = Set-Or-AddPropertyLine -Lines $localPropertiesLines -Key "DISCOUNT_SCREENER_RELEASE_KEY_PASSWORD" -Value $keyPasswordValue
    Set-Content -Path $LocalPropertiesPath -Value $localPropertiesLines
}

Write-Host "Release keystore created:"
Write-Host "  $resolvedKeystorePath"
Write-Host ""
Write-Host "Signing values:"
Write-Host "  DISCOUNT_SCREENER_RELEASE_STORE_FILE=$resolvedKeystorePath"
Write-Host "  DISCOUNT_SCREENER_RELEASE_STORE_PASSWORD=$storePasswordValue"
Write-Host "  DISCOUNT_SCREENER_RELEASE_KEY_ALIAS=$KeyAlias"
Write-Host "  DISCOUNT_SCREENER_RELEASE_KEY_PASSWORD=$keyPasswordValue"
Write-Host ""

if ($UpdateLocalProperties) {
    Write-Host "apps\android\local.properties was updated with the signing values."
} else {
    Write-Host "To wire it into the build, either:"
    Write-Host "1. rerun this script with -UpdateLocalProperties"
    Write-Host "2. or add those four DISCOUNT_SCREENER_RELEASE_* entries to apps\android\local.properties"
    Write-Host "3. or set them as environment variables before running make android-release"
}
