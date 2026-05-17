<#
.SYNOPSIS
    Local "release endpoint" for testing the in-app auto-update flow.

.DESCRIPTION
    Copies the latest built debug APK into .\local-release\, writes an
    update.json manifest that points at it, prints the URL you need to paste
    into the app's Settings -> Auto-update field, and starts a Python HTTP
    server on the chosen port.

    Typical test flow:
      1. Install the CURRENT app (versionCode=1) on the device.
      2. Bump app/build.gradle.kts to versionCode=2, versionName="1.0.1".
      3. Run: .\gradlew.bat :app:assembleDebug
      4. Run this script with -VersionCode 2 -VersionName 1.0.1.
      5. In the app's Settings tab, set Auto-update URL to the URL printed
         by this script (http://<your-ip>:<port>/update.json) and tap
         "Check for updates", or restart the app to trigger the silent check.

.PARAMETER VersionCode
    The versionCode to advertise in update.json (must be > the installed
    app's versionCode for the dialog to appear). Default: 2.

.PARAMETER VersionName
    Human-readable version string written into update.json. Default: 1.0.1.

.PARAMETER ApkPath
    Path to the .apk to publish. Default: app\build\outputs\apk\debug\app-debug.apk

.PARAMETER Port
    HTTP port. Default: 8081.

.PARAMETER ReleaseNotes
    Release notes text. Default: a short placeholder.
#>
[CmdletBinding()]
param(
    [int]    $VersionCode  = 2,
    [string] $VersionName  = "1.0.1",
    [string] $ApkPath      = "app\build\outputs\apk\debug\app-debug.apk",
    [int]    $Port         = 8081,
    [string] $ReleaseNotes = "Local test build."
)

$ErrorActionPreference = "Stop"

# Resolve repo root (this script lives in tools\).
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

$releaseDir = Join-Path $repoRoot "local-release"
if (-not (Test-Path $releaseDir)) {
    New-Item -ItemType Directory -Path $releaseDir | Out-Null
}

# Locate APK.
$apkFull = if ([System.IO.Path]::IsPathRooted($ApkPath)) { $ApkPath } else { Join-Path $repoRoot $ApkPath }
if (-not (Test-Path $apkFull)) {
    Write-Error "APK not found: $apkFull`nBuild it first: .\gradlew.bat :app:assembleDebug"
}

# Copy APK into the release dir with a versioned name.
$apkName = "scorereader-$VersionName.apk"
$apkDest = Join-Path $releaseDir $apkName
Copy-Item -Path $apkFull -Destination $apkDest -Force
$apkBytes = (Get-Item $apkDest).Length
Write-Host "[release] Copied APK -> $apkDest ($([math]::Round($apkBytes/1MB,2)) MB)"

# Pick a non-loopback IPv4 address.
$ip = (
    Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
        Where-Object {
            $_.IPAddress -notlike "127.*" -and
            $_.IPAddress -notlike "169.254.*" -and
            $_.PrefixOrigin -ne "WellKnown" -and
            $_.InterfaceAlias -notlike "*Loopback*"
        } |
        Sort-Object -Property InterfaceMetric |
        Select-Object -ExpandProperty IPAddress -First 1
)
if (-not $ip) { $ip = "127.0.0.1" }

$baseUrl     = "http://$($ip):$Port"
$apkUrl      = "$baseUrl/$apkName"
$manifestUrl = "$baseUrl/update.json"

# Write update.json.
$manifest = [ordered]@{
    versionCode  = $VersionCode
    versionName  = $VersionName
    apkUrl       = $apkUrl
    releaseNotes = $ReleaseNotes
}
$manifestPath = Join-Path $releaseDir "update.json"
$manifest | ConvertTo-Json -Depth 4 | Set-Content -Path $manifestPath -Encoding UTF8
Write-Host "[release] Wrote manifest -> $manifestPath"
Get-Content $manifestPath | Write-Host

Write-Host ""
Write-Host "=============================================================" -ForegroundColor Cyan
Write-Host " Paste this into the app's Settings -> Auto-update URL field:" -ForegroundColor Cyan
Write-Host "   $manifestUrl" -ForegroundColor Yellow
Write-Host "=============================================================" -ForegroundColor Cyan
Write-Host ""

# Start Python HTTP server in the release dir.
$python = $null
foreach ($candidate in @("python", "py", "python3")) {
    try {
        $v = & $candidate --version 2>&1
        if ($LASTEXITCODE -eq 0) { $python = $candidate; break }
    } catch { }
}
if (-not $python) {
    Write-Error "Python not found on PATH. Install Python or serve $releaseDir with another static file server on port $Port."
}

Write-Host "[release] Starting $python -m http.server $Port  (cwd=$releaseDir)"
Write-Host "[release] Press Ctrl+C to stop."
Push-Location $releaseDir
try {
    & $python -m http.server $Port --bind 0.0.0.0
} finally {
    Pop-Location
}
