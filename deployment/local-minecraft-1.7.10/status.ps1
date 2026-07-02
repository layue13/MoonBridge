$ErrorActionPreference = "Stop"
$Backends = @(
    @{ Name = "lobby-1"; Port = 25565 },
    @{ Name = "survival-1"; Port = 25566 },
    @{ Name = "minigame-1"; Port = 25567 }
)

function Test-Port {
    param(
        [string] $HostName,
        [int] $Port,
        [int] $TimeoutMillis = 500
    )

    $Client = [System.Net.Sockets.TcpClient]::new()
    try {
        $Connect = $Client.BeginConnect($HostName, $Port, $null, $null)
        if (-not $Connect.AsyncWaitHandle.WaitOne($TimeoutMillis)) {
            return $false
        }
        $Client.EndConnect($Connect)
        return $true
    } catch {
        return $false
    } finally {
        $Client.Close()
    }
}

foreach ($Backend in $Backends) {
    $State = if (Test-Port -HostName "127.0.0.1" -Port $Backend.Port) { "open" } else { "closed" }
    Write-Host "$($Backend.Name) 127.0.0.1:$($Backend.Port) $State"
}

$ProxyState = if (Test-Port -HostName "127.0.0.1" -Port 25577) { "open" } else { "closed" }
Write-Host "strataproxy 127.0.0.1:25577 $ProxyState"
