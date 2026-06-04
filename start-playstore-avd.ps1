param(
    [switch]$Visible
)

$ErrorActionPreference = "Stop"

$sdk = Join-Path $env:LOCALAPPDATA "Android\Sdk"
$emulator = Join-Path $sdk "emulator\emulator.exe"

if (-not (Test-Path $emulator)) {
    throw "Android Emulator not found at $emulator"
}

$argsList = @(
    "-netdelay", "none",
    "-netspeed", "full",
    "-avd", "SeniorSpendingGuard_PlayStore_API35",
    "-no-snapshot-load"
)

if (-not $Visible) {
    $argsList += "-no-window"
}

$windowStyle = if ($Visible) { "Normal" } else { "Hidden" }
Start-Process -FilePath $emulator -ArgumentList $argsList -WindowStyle $windowStyle

Write-Host "Started SeniorSpendingGuard_PlayStore_API35. Do not wipe data if you want installed apps and sign-in state to persist."
if (-not $Visible) {
    Write-Host "Launched headless for automation. Re-run with -Visible for manual Google sign-in."
}
