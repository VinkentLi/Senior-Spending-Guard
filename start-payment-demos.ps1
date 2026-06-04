$Root = Join-Path $PSScriptRoot "payment-demos"
$Port = 8765

if (-not (Test-Path $Root)) {
    throw "Missing payment demo folder: $Root"
}

$existing = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if ($existing) {
    Write-Host "Payment demos already listening on port $Port."
    exit 0
}

$python = Get-Command python -ErrorAction SilentlyContinue
if (-not $python) {
    throw "Python is required to serve the demo site."
}

Start-Process -WindowStyle Hidden -FilePath $python.Source -ArgumentList @(
    "-m", "http.server", "$Port", "--bind", "0.0.0.0", "--directory", $Root
)
Write-Host "Payment demos started at http://localhost:$Port/ and http://10.0.2.2:$Port/"
