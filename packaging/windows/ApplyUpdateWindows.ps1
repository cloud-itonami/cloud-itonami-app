param(
  [Parameter(Mandatory=$true)][string]$InstallDir,
  [Parameter(Mandatory=$true)][string]$Package,
  [Parameter(Mandatory=$true)][string]$PendingDir,
  [Parameter(Mandatory=$true)][int]$ParentPid
)
$ErrorActionPreference = "Stop"
Wait-Process -Id $ParentPid -ErrorAction SilentlyContinue
$stamp = (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmssZ")
$work = "$PendingDir.apply-$stamp"
$backup = "$InstallDir.previous"
$failed = "$InstallDir.failed-$stamp"
New-Item -ItemType Directory -Force -Path $work | Out-Null
Expand-Archive -LiteralPath $Package -DestinationPath $work -Force
$newInstall = Join-Path $work "Cloud Itonami"
if (-not (Test-Path (Join-Path $newInstall "CloudItonami.exe"))) {
  throw "Update archive contains no CloudItonami.exe"
}
$publisherPath = Join-Path $InstallDir "windows-publisher-sha256.txt"
if (-not (Test-Path $publisherPath)) {
  throw "Current installation has no Windows publisher identity"
}
$expectedPublisher = (Get-Content -Raw $publisherPath).Trim().ToUpperInvariant()
$signature = Get-AuthenticodeSignature (Join-Path $newInstall "CloudItonami.exe")
if ($signature.Status -ne "Valid" -or $null -eq $signature.SignerCertificate) {
  throw "Updated CloudItonami.exe has no valid Authenticode signature"
}
$sha256 = [System.Security.Cryptography.SHA256]::Create()
try {
  $actualPublisher = -join ($sha256.ComputeHash($signature.SignerCertificate.RawData) |
    ForEach-Object { $_.ToString("X2") })
} finally {
  $sha256.Dispose()
}
if ($actualPublisher -ne $expectedPublisher) {
  throw "Updated CloudItonami.exe is signed by a different publisher"
}
if (Test-Path $backup) { Move-Item $backup "$backup.$stamp" }
Move-Item $InstallDir $backup
try {
  Move-Item $newInstall $InstallDir
} catch {
  Move-Item $backup $InstallDir
  throw
}
Start-Process (Join-Path $InstallDir "CloudItonami.exe")
$healthy = $false
for ($i = 0; $i -lt 240; $i++) {
  try {
    $response = Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 http://127.0.0.1:1338/health
    if ($response.StatusCode -eq 200) { $healthy = $true; break }
  } catch {}
  Start-Sleep -Milliseconds 500
}
if (-not $healthy) {
  $dataDir = Split-Path (Split-Path $PendingDir -Parent) -Parent
  $serverPidFile = Join-Path $dataDir "server.pid"
  if (Test-Path $serverPidFile) {
    $serverPid = (Get-Content -Raw $serverPidFile).Trim()
    if ($serverPid -match '^\d+$') {
      & taskkill.exe /PID $serverPid /T /F 2>$null | Out-Null
    }
    Remove-Item $serverPidFile -Force -ErrorAction SilentlyContinue
  }
  Move-Item $InstallDir $failed
  Move-Item $backup $InstallDir
  Start-Process (Join-Path $InstallDir "CloudItonami.exe")
  throw "Updated app did not become healthy; rolled back"
}
Move-Item $PendingDir "$PendingDir.applied-$stamp"
Remove-Item $work -Recurse -Force -ErrorAction SilentlyContinue
