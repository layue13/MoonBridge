$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Backends = @("backend-lobby-1", "backend-survival-1", "backend-minigame-1")

foreach ($Backend in $Backends) {
    $PidFile = Join-Path (Join-Path $Root $Backend) "server.pid"
    if (-not (Test-Path -LiteralPath $PidFile)) {
        Write-Host "$Backend is not tracked"
        continue
    }
    $PidText = (Get-Content -LiteralPath $PidFile -Raw).Trim()
    if (-not $PidText) {
        Remove-Item -LiteralPath $PidFile -Force
        Write-Host "$Backend has an empty pid file"
        continue
    }
    $Process = Get-Process -Id ([int] $PidText) -ErrorAction SilentlyContinue
    if ($Process) {
        Stop-Process -Id $Process.Id -Force
        Write-Host "Stopped $Backend, pid $PidText"
    } else {
        Write-Host "$Backend pid $PidText is not running"
    }
    Remove-Item -LiteralPath $PidFile -Force
}
