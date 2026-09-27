#Requires -Version 7.0
param(
    [Parameter(Mandatory)][string]$BundlePath,
    [string]$ProbeClassesPath,
    [string]$Java8Path = 'C:\Program Files\Zulu\zulu-8\bin\java.exe',
    [switch]$PrismClient,
    [string]$PrismInstance = '1.7.10',
    [string]$PrismInstanceFolder,
    [string]$PrismPath = (Join-Path $env:LOCALAPPDATA 'Programs\PrismLauncher\prismlauncher.exe')
)

$ErrorActionPreference = 'Stop'
if (-not $PrismInstanceFolder) { $PrismInstanceFolder = $PrismInstance }

function IsPrismInstanceProcess([object]$process) {
    if (-not $process.CommandLine) { return $false }
    $line = $process.CommandLine.Replace('/', '\')
    return $line.IndexOf('PrismLauncher', [StringComparison]::OrdinalIgnoreCase) -ge 0 -and
        $line.IndexOf("\instances\$PrismInstanceFolder\", [StringComparison]::OrdinalIgnoreCase) -ge 0
}

$repoRoot = Split-Path -Parent $PSScriptRoot
$bundle = (Resolve-Path -LiteralPath $BundlePath).Path
$probeClasses = if ($PrismClient) { $null } else {
    if (-not $ProbeClassesPath) { throw 'ProbeClassesPath is required without -PrismClient' }
    (Resolve-Path -LiteralPath $ProbeClassesPath).Path
}
$proxyLib = Join-Path $repoRoot 'proxy-core\build\install\moonbridge\lib'
if (-not (Test-Path -LiteralPath $Java8Path) -or -not (Test-Path -LiteralPath $proxyLib)) {
    throw 'Java 8 and the installed MoonBridge distribution are required'
}
if ((Get-Content -LiteralPath (Join-Path $bundle 'eula.txt') -Raw).Trim() -ne 'eula=true') {
    throw 'The supplied Uranium bundle must already contain eula=true'
}

function FreePort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try { return ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port }
    finally { $listener.Stop() }
}

function WaitForPort([int]$port, [System.Diagnostics.Process]$process, [int]$timeoutSeconds) {
    $deadline = [DateTime]::UtcNow.AddSeconds($timeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($process.HasExited) { throw "Process exited before port $port opened: $($process.ExitCode)" }
        $client = [System.Net.Sockets.TcpClient]::new()
        try {
            $client.Connect([System.Net.IPAddress]::Loopback, $port)
            return
        } catch [System.Net.Sockets.SocketException] {
            Start-Sleep -Milliseconds 200
        } finally { $client.Dispose() }
    }
    throw "Port $port did not open within $timeoutSeconds seconds"
}

$runDir = Join-Path $repoRoot ('build\local-uranium-proxy-' + [guid]::NewGuid().ToString('N'))
Copy-Item -LiteralPath $bundle -Destination $runDir -Recurse
$serverJar = @(Get-ChildItem -LiteralPath $runDir -Filter '*-server.jar')
if ($serverJar.Count -ne 1) { throw "Expected one Uranium server JAR in $runDir" }
$serverPort = FreePort
$proxyPort = FreePort
while ($proxyPort -eq $serverPort) { $proxyPort = FreePort }
@"
server-port=$serverPort
server-ip=127.0.0.1
online-mode=false
level-name=world
motd=MoonBridge local Uranium smoke
"@ | Set-Content -LiteralPath (Join-Path $runDir 'server.properties') -Encoding utf8

$serverInfo = [System.Diagnostics.ProcessStartInfo]::new()
$serverInfo.FileName = $Java8Path
$serverInfo.WorkingDirectory = $runDir
$serverInfo.UseShellExecute = $false
$serverInfo.CreateNoWindow = $true
$serverInfo.RedirectStandardInput = $true
$serverInfo.RedirectStandardOutput = $true
$serverInfo.RedirectStandardError = $true
foreach ($argument in @('-Xms512m', '-Xmx1024m', '-jar', $serverJar[0].Name, 'nogui')) {
    $serverInfo.ArgumentList.Add($argument)
}
$server = [System.Diagnostics.Process]::Start($serverInfo)
$serverOutput = $server.StandardOutput.ReadToEndAsync()
$serverErrors = $server.StandardError.ReadToEndAsync()
$proxy = $null
$initialJavawIds = @()
$clientLaunchAttempted = $false
try {
    WaitForPort $serverPort $server 120
    $readyLog = Join-Path $runDir 'logs\latest.log'
    $readyDeadline = [DateTime]::UtcNow.AddSeconds(120)
    while ([DateTime]::UtcNow -lt $readyDeadline) {
        if ($server.HasExited) { throw "Uranium exited during startup: $($server.ExitCode)" }
        if ((Test-Path -LiteralPath $readyLog) -and
            (Select-String -LiteralPath $readyLog -Pattern 'Done \(' -Quiet)) { break }
        Start-Sleep -Milliseconds 250
    }
    if (-not (Test-Path -LiteralPath $readyLog) -or
        -not (Select-String -LiteralPath $readyLog -Pattern 'Done \(' -Quiet)) {
        throw 'Uranium did not report ready within 120 seconds'
    }

    $config = Join-Path $runDir 'moonbridge.yml'
    @"
listen: "127.0.0.1:$proxyPort"
authentication: OFFLINE
plugins:
  directory: "proxy-plugins"
  enabled: {}
backends:
  - name: uranium
    address: "127.0.0.1:$serverPort"
"@ | Set-Content -LiteralPath $config -Encoding utf8
    $java = (Get-Command java.exe -ErrorAction Stop).Source
    $classpath = Join-Path $proxyLib '*'
    $proxy = Start-Process -FilePath $java -ArgumentList @(
        '-cp', ('"{0}"' -f $classpath), 'dev.moonbridge.app.ProxyMain',
        '--config', ('"{0}"' -f $config)
    ) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runDir 'proxy.stdout.log') `
      -RedirectStandardError (Join-Path $runDir 'proxy.stderr.log')
    WaitForPort $proxyPort $proxy 15

    if ($PrismClient) {
        if (-not (Test-Path -LiteralPath $PrismPath)) { throw "Prism Launcher not found: $PrismPath" }
        $initialJavawIds = @(Get-CimInstance Win32_Process -Filter "Name = 'javaw.exe'" |
            Select-Object -ExpandProperty ProcessId)
        Start-Process -FilePath $PrismPath -ArgumentList @(
            '--launch', $PrismInstance, '--offline', 'PrismSmoke',
            '--server', "127.0.0.1:$proxyPort"
        ) -WindowStyle Hidden | Out-Null
        $clientLaunchAttempted = $true
        $deadline = [DateTime]::UtcNow.AddSeconds(120)
        $serverLog = Join-Path $runDir 'logs\latest.log'
        while ([DateTime]::UtcNow -lt $deadline) {
            if ((Select-String -LiteralPath $serverLog -Pattern 'PrismSmoke.*logged in' -Quiet)) { break }
            if ($proxy.HasExited) { throw "Proxy exited while Prism was connecting: $($proxy.ExitCode)" }
            if ($server.HasExited) { throw "Uranium exited while Prism was connecting: $($server.ExitCode)" }
            Start-Sleep -Milliseconds 500
        }
        if (-not (Select-String -LiteralPath $serverLog -Pattern 'PrismSmoke.*logged in' -Quiet)) {
            throw 'Prism Forge client did not log into Uranium within 120 seconds'
        }
        Start-Sleep -Seconds 10
        if ((Select-String -LiteralPath $serverLog -Pattern 'PrismSmoke lost connection' -Quiet)) {
            throw 'Prism Forge client disconnected during the 10-second hold'
        }
        $newClient = @(Get-CimInstance Win32_Process -Filter "Name = 'javaw.exe'" |
            Where-Object { $_.ProcessId -notin $initialJavawIds -and (IsPrismInstanceProcess $_) })
        if ($newClient.Count -ne 1) {
            throw "Expected one new Prism Java client, found $($newClient.Count)"
        }
        Write-Output "REAL_PRISM_URANIUM_PASS serverPort=$serverPort proxyPort=$proxyPort holdSeconds=10"
    } else {
        $sourceDir = Join-Path $runDir 'probe-src\cc\uraniummc\rfg\smoke'
        New-Item -ItemType Directory -Path $sourceDir -Force | Out-Null
        $source = Join-Path $sourceDir 'RunThroughProxy.java'
        @'
package cc.uraniummc.rfg.smoke;
public final class RunThroughProxy {
    public static void main(String[] args) throws Exception {
        MinecraftProtocolProbe.run(Integer.parseInt(args[0]));
    }
}
'@ | Set-Content -LiteralPath $source -Encoding utf8
        $probeOutput = Join-Path $runDir 'probe-classes'
        New-Item -ItemType Directory -Path $probeOutput -Force | Out-Null
        & javac -cp $probeClasses -d $probeOutput $source
        if ($LASTEXITCODE -ne 0) { throw 'Could not compile Uranium protocol probe wrapper' }
        & java -cp "$probeOutput;$probeClasses" cc.uraniummc.rfg.smoke.RunThroughProxy $proxyPort
        if ($LASTEXITCODE -ne 0) { throw "Protocol probe failed through MoonBridge: $LASTEXITCODE" }
        Write-Output "REAL_URANIUM_PROXY_PASS serverPort=$serverPort proxyPort=$proxyPort"
    }
} finally {
    if ($clientLaunchAttempted) {
        Get-CimInstance Win32_Process -Filter "Name = 'javaw.exe'" |
            Where-Object { $_.ProcessId -notin $initialJavawIds -and (IsPrismInstanceProcess $_) } |
            ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    }
    if ($proxy -and -not $proxy.HasExited) {
        Stop-Process -Id $proxy.Id -Force
        Wait-Process -Id $proxy.Id -ErrorAction SilentlyContinue
    }
    if (-not $server.HasExited) {
        $server.StandardInput.WriteLine('stop')
        $server.StandardInput.Flush()
        if (-not $server.WaitForExit(30000)) {
            $server.Kill()
            $server.WaitForExit()
        }
    }
    $serverOutput.GetAwaiter().GetResult() | Set-Content -LiteralPath (Join-Path $runDir 'server.stdout.log')
    $serverErrors.GetAwaiter().GetResult() | Set-Content -LiteralPath (Join-Path $runDir 'server.stderr.log')
    Write-Output "Runtime logs: $runDir"
}
