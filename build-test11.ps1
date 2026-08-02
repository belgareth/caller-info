$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $projectRoot

$gradle = Join-Path $projectRoot 'gradlew.bat'
if (-not (Test-Path -LiteralPath $gradle)) {
    throw 'gradlew.bat was not found in the project root.'
}

Write-Host 'Running JVM tests...'
& $gradle test --no-daemon --console=plain
if ($LASTEXITCODE -ne 0) { throw 'JVM tests failed.' }

$adbCandidates = @(
    (Get-Command adb.exe -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source -ErrorAction SilentlyContinue),
    (Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe')
) | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -Unique

$adb = $adbCandidates | Select-Object -First 1
$connected = $false
if ($adb) {
    $deviceLines = & $adb devices 2>$null
    $connected = @($deviceLines | Where-Object { $_ -match '\sdevice$' }).Count -gt 0
}

if ($connected) {
    Write-Host 'Running connected instrumentation tests...'
    & $gradle connectedDebugAndroidTest --no-daemon --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Connected instrumentation tests failed.' }
} else {
    Write-Warning 'No Android device/emulator was detected. Connected tests were skipped.'
}

Write-Host 'Building debug APK...'
& $gradle assembleDebug --no-daemon --console=plain
if ($LASTEXITCODE -ne 0) { throw 'Debug APK build failed.' }

$sourceApk = Join-Path $projectRoot 'app\build\outputs\apk\debug\app-debug.apk'
if (-not (Test-Path -LiteralPath $sourceApk)) {
    throw "Built APK was not found at $sourceApk"
}

$assetDir = Join-Path $projectRoot 'release-assets'
New-Item -ItemType Directory -Force -Path $assetDir | Out-Null
$assetApk = Join-Path $assetDir 'caller-info-test-v1.1.0-test.11.apk'
$checksum = Join-Path $assetDir 'caller-info-test-v1.1.0-test.11.sha256'
Copy-Item -LiteralPath $sourceApk -Destination $assetApk -Force
$hash = (Get-FileHash -LiteralPath $assetApk -Algorithm SHA256).Hash.ToLowerInvariant()
[System.IO.File]::WriteAllText(
    $checksum,
    "$hash  caller-info-test-v1.1.0-test.11.apk`n",
    [System.Text.UTF8Encoding]::new($false)
)

Write-Host ''
Write-Host 'Build complete:'
Get-Item -LiteralPath $assetApk, $checksum | Select-Object FullName, Length
Write-Host "SHA-256: $hash"
Write-Host 'Use Android Studio/App Inspection or apksigner to verify package identity and signing certificate before installation.'
