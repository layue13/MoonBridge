param()

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$install = Join-Path $repoRoot 'proxy-core\build\install\strataproxy'
$lib = Join-Path $install 'lib'
$plugins = Join-Path $install 'plugins'
if (-not (Test-Path -LiteralPath $plugins) -or
    @(Get-ChildItem -LiteralPath $plugins -Filter 'dns-discovery-*.jar').Count -ne 1 -or
    @(Get-ChildItem -LiteralPath $plugins -Filter 'agent-discovery-*.jar').Count -ne 1) {
    throw 'Run .\gradlew.bat :proxy-core:installDist before this smoke test'
}

function FreePort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try { return ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port }
    finally { $listener.Stop() }
}

function VarInt([int]$value) {
    $bytes = [System.Collections.Generic.List[byte]]::new()
    do {
        $part = $value -band 127
        $value = $value -shr 7
        if ($value -ne 0) { $part = $part -bor 128 }
        $bytes.Add([byte]$part)
    } while ($value -ne 0)
    return ,$bytes.ToArray()
}

function ReadVarInt([System.IO.Stream]$stream) {
    $value = 0
    for ($shift = 0; $shift -le 28; $shift += 7) {
        $part = $stream.ReadByte()
        if ($part -lt 0) { throw 'Unexpected EOF while reading VarInt' }
        $value = $value -bor (($part -band 127) -shl $shift)
        if (($part -band 128) -eq 0) { return $value }
    }
    throw 'Overlong VarInt'
}

function ReadExactly([System.IO.Stream]$stream, [int]$length) {
    $bytes = [byte[]]::new($length)
    $offset = 0
    while ($offset -lt $length) {
        $read = $stream.Read($bytes, $offset, $length - $offset)
        if ($read -eq 0) { throw 'Unexpected EOF while reading status response' }
        $offset += $read
    }
    return ,$bytes
}

function WriteFrame([System.IO.Stream]$stream, [byte[]]$body) {
    $prefix = VarInt $body.Length
    $stream.Write($prefix)
    $stream.Write($body)
}

function StatusMax([int]$port) {
    $client = [System.Net.Sockets.TcpClient]::new()
    try {
        $client.Connect([System.Net.IPAddress]::Loopback, $port)
        $client.ReceiveTimeout = 1000
        $stream = $client.GetStream()
        $handshake = [System.Collections.Generic.List[byte]]::new()
        $handshake.AddRange([byte[]](VarInt 0))
        $handshake.AddRange([byte[]](VarInt 5))
        $hostBytes = [System.Text.Encoding]::UTF8.GetBytes('localhost')
        $handshake.AddRange([byte[]](VarInt $hostBytes.Length))
        $handshake.AddRange($hostBytes)
        $handshake.Add(0x63)
        $handshake.Add(0xdd)
        $handshake.AddRange([byte[]](VarInt 1))
        WriteFrame $stream ($handshake.ToArray())
        WriteFrame $stream ([byte[]]@(0))
        $body = ReadExactly $stream (ReadVarInt $stream)
        $packetStream = [System.IO.MemoryStream]::new($body)
        if ((ReadVarInt $packetStream) -ne 0) { throw 'Unexpected status packet id' }
        $json = [System.Text.Encoding]::UTF8.GetString((ReadExactly $packetStream (ReadVarInt $packetStream))) | ConvertFrom-Json
        return [int]$json.players.max
    } finally { $client.Dispose() }
}

function WaitForMax([int]$port, [int]$expected) {
    $last = $null
    for ($attempt = 0; $attempt -lt 50; $attempt++) {
        try {
            $last = StatusMax $port
            if ($last -eq $expected) { return }
        } catch { $last = $_.Exception.Message }
        Start-Sleep -Milliseconds 100
    }
    throw "Expected status max=$expected on port $port; last result=$last"
}

function StartProxy([string]$config, [string]$stdout, [string]$stderr) {
    $java = (Get-Command java.exe -ErrorAction Stop).Source
    $classpath = Join-Path $lib '*'
    return Start-Process -FilePath $java -ArgumentList @(
        '-cp', ('"{0}"' -f $classpath), 'dev.strataproxy.app.ProxyMain',
        '--config', ('"{0}"' -f $config)
    ) -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
}

function StopProxy($process) {
    if ($process -and -not $process.HasExited) {
        Stop-Process -Id $process.Id -Force
        Wait-Process -Id $process.Id -ErrorAction SilentlyContinue
    }
}

$temporary = Join-Path $repoRoot 'build\installed-discovery-smoke'
New-Item -ItemType Directory -Path $temporary -Force | Out-Null
$prefix = [guid]::NewGuid().ToString('N')
$dnsConfig = Join-Path $temporary "$prefix-dns.yml"
$agentConfig = Join-Path $temporary "$prefix-agent.yml"
$stdout = Join-Path $temporary "$prefix.stdout.log"
$stderr = Join-Path $temporary "$prefix.stderr.log"
$dns = $null
$agent = $null
try {
    $pluginPath = $plugins.Replace('\', '/')
    $dnsPort = FreePort
    @"
listen: "127.0.0.1:$dnsPort"
authentication: OFFLINE
backends: []
plugins:
  directory: "$pluginPath"
  enabled:
    dev.strataproxy.plugins.dns.DnsDiscoveryPlugin:
      host: localhost
      port: "25565"
      capacity: "17"
      refreshSeconds: "1"
"@ | Set-Content -LiteralPath $dnsConfig -Encoding utf8
    $dns = StartProxy $dnsConfig $stdout $stderr
    $dnsAddresses = [System.Net.Dns]::GetHostAddresses('localhost').Length
    WaitForMax $dnsPort (17 * $dnsAddresses)
    Write-Output "DNS plugin: $dnsAddresses address(es), status max=$(StatusMax $dnsPort)"
    StopProxy $dns
    $dns = $null

    $proxyPort = FreePort
    $agentPort = FreePort
    while ($agentPort -eq $proxyPort) { $agentPort = FreePort }
    $secret = [Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
    @"
listen: "127.0.0.1:$proxyPort"
authentication: OFFLINE
backends: []
plugins:
  directory: "$pluginPath"
  enabled:
    dev.strataproxy.plugins.agent.AgentDiscoveryPlugin:
      host: "127.0.0.1"
      port: "$agentPort"
      secret: "$secret"
"@ | Set-Content -LiteralPath $agentConfig -Encoding utf8
    $agent = StartProxy $agentConfig $stdout $stderr
    WaitForMax $proxyPort 0
    $generation = [guid]::NewGuid().ToString()
    $agentId = 'installed-smoke'
    $timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString()
    $nonce = [Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(16))
    $endpoint = "http://127.0.0.1:$agentPort/registration"
    function SendAgent([string]$body) {
        $canonical = "POST`n/registration`n$timestamp`n$nonce`n$agentId`n$body"
        $hmac = [System.Security.Cryptography.HMACSHA256]::new([System.Text.Encoding]::UTF8.GetBytes($secret))
        try { $signature = [Convert]::ToHexString($hmac.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($canonical))) }
        finally { $hmac.Dispose() }
        return Invoke-WebRequest -Uri $endpoint -Method Post -Body $body -ContentType 'application/x-www-form-urlencoded; charset=utf-8' -Headers @{
            'X-Agent-Id' = $agentId
            'X-Timestamp' = $timestamp
            'X-Nonce' = $nonce
            'X-Signature' = $signature
        } -TimeoutSec 5
    }
    $registerBody = "action=register&generation=$generation&name=installed-smoke-backend&address=tcp%3A%2F%2F127.0.0.1%3A25565&capacity=19&leaseSeconds=30"
    $registered = SendAgent $registerBody
    if ($registered.StatusCode -ne 201) { throw "Agent registration returned $($registered.StatusCode)" }
    WaitForMax $proxyPort 19
    Write-Output "Agent plugin: registration status=$($registered.StatusCode), status max=$(StatusMax $proxyPort)"
    $nonce = [Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(16))
    $unregistered = SendAgent "action=unregister&generation=$generation"
    if ($unregistered.StatusCode -ne 204) { throw "Agent unregister returned $($unregistered.StatusCode)" }
    WaitForMax $proxyPort 0
    Write-Output 'Agent plugin: unregister status=204, status max=0'
} finally {
    StopProxy $dns
    StopProxy $agent
    Remove-Item -LiteralPath $dnsConfig, $agentConfig, $stdout, $stderr -ErrorAction SilentlyContinue
    $secret = $null
}
