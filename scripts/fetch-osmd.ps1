param(
    [string]$Version = "1.8.7",
    [switch]$SkipTranspile
)

$ErrorActionPreference = "Stop"

$repoRoot   = Split-Path -Parent $PSScriptRoot
$assetDir   = Join-Path $repoRoot "app\src\main\assets\osmd"
$transpile  = Join-Path $repoRoot "tools\transpile"

if (-not (Test-Path $assetDir)) {
    New-Item -ItemType Directory -Path $assetDir -Force | Out-Null
}

$rawDest   = Join-Path $assetDir "opensheetmusicdisplay.raw.js"
$finalDest = Join-Path $assetDir "opensheetmusicdisplay.min.js"
$url       = "https://cdn.jsdelivr.net/npm/opensheetmusicdisplay@$Version/build/opensheetmusicdisplay.min.js"

Write-Host "Downloading OSMD $Version ..."
Write-Host "  from: $url"
Invoke-WebRequest -Uri $url -OutFile $rawDest -UseBasicParsing

if ($SkipTranspile) {
    Move-Item -Force $rawDest $finalDest
    Write-Host ("Saved (no transpile). {0} bytes." -f (Get-Item $finalDest).Length)
    return
}

# Transpile down to ES5 (Chromium 44 baseline) for Android 6.0 WebView compatibility.
if (-not (Test-Path (Join-Path $transpile "node_modules\.bin\babel.cmd"))) {
    Write-Host "Installing Babel toolchain (tools/transpile) ..."
    Push-Location $transpile
    try { npm install --no-audit --no-fund --silent } finally { Pop-Location }
}

Write-Host "Transpiling to ES5 (this can take ~30s for ~1MB bundle) ..."
Push-Location $transpile
try {
    & .\node_modules\.bin\babel.cmd $rawDest --out-file $finalDest
} finally {
    Pop-Location
}

Remove-Item -Force $rawDest
Write-Host ("Done. {0} bytes." -f (Get-Item $finalDest).Length)
