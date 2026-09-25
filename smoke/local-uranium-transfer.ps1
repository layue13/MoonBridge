#Requires -Version 7.0
param(
    [Parameter(Mandatory)][string]$BundlePath,
    [string]$Java8Path = 'C:\Program Files\Zulu\zulu-8\bin\java.exe',
    [switch]$InstalledPlugin
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$bundle = (Resolve-Path -LiteralPath $BundlePath).Path
$proxyLib = Join-Path $repoRoot 'proxy-core\build\install\strataproxy\lib'
if (-not (Test-Path -LiteralPath $Java8Path) -or -not (Test-Path -LiteralPath $proxyLib)) {
    throw 'Java 8 and the installed StrataProxy distribution are required'
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

function WaitForReady([int]$port, [System.Diagnostics.Process]$process, [string]$directory) {
    $deadline = [DateTime]::UtcNow.AddSeconds(120)
    $readyLog = Join-Path $directory 'logs\latest.log'
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($process.HasExited) { throw "Uranium on port $port exited: $($process.ExitCode)" }
        if ((Test-Path -LiteralPath $readyLog) -and
            (Select-String -LiteralPath $readyLog -Pattern 'Done \(' -Quiet)) { return }
        Start-Sleep -Milliseconds 250
    }
    throw "Uranium on port $port did not become ready within 120 seconds"
}

function WaitForLog([string]$directory, [string]$pattern) {
    $path = Join-Path $directory 'logs\latest.log'
    $deadline = [DateTime]::UtcNow.AddSeconds(5)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ((Test-Path -LiteralPath $path) -and
            (Select-String -LiteralPath $path -Pattern $pattern -Quiet)) { return }
        Start-Sleep -Milliseconds 100
    }
    throw "Uranium log in $directory did not contain $pattern"
}

function WaitForPort([int]$port, [System.Diagnostics.Process]$process) {
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($process.HasExited) { throw "Proxy exited before port $port opened: $($process.ExitCode)" }
        $client = [System.Net.Sockets.TcpClient]::new()
        try {
            $client.Connect([System.Net.IPAddress]::Loopback, $port)
            return
        } catch [System.Net.Sockets.SocketException] {
            Start-Sleep -Milliseconds 200
        } finally { $client.Dispose() }
    }
    throw "Proxy port $port did not open within 15 seconds"
}

function WaitForProxyLog([string]$path, [string]$pattern) {
    $deadline = [DateTime]::UtcNow.AddSeconds(5)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ((Test-Path -LiteralPath $path) -and
            (Select-String -LiteralPath $path -Pattern $pattern -Quiet)) { return }
        Start-Sleep -Milliseconds 100
    }
    throw "Proxy log did not contain $pattern; inspect $path"
}

function StartUranium([string]$directory, [int]$port) {
    Copy-Item -LiteralPath $bundle -Destination $directory -Recurse
    $serverJar = @(Get-ChildItem -LiteralPath $directory -Filter '*-server.jar')
    if ($serverJar.Count -ne 1) { throw "Expected one Uranium server JAR in $directory" }
    @"
server-port=$port
server-ip=127.0.0.1
online-mode=false
level-name=world
motd=StrataProxy Uranium transfer smoke
"@ | Set-Content -LiteralPath (Join-Path $directory 'server.properties') -Encoding utf8
    $info = [System.Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $Java8Path
    $info.WorkingDirectory = $directory
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    foreach ($argument in @('-Xms512m', '-Xmx1024m', '-jar', $serverJar[0].Name, 'nogui')) {
        $info.ArgumentList.Add($argument)
    }
    $process = [System.Diagnostics.Process]::Start($info)
    return [pscustomobject]@{
        Process = $process
        Output = $process.StandardOutput.ReadToEndAsync()
        Errors = $process.StandardError.ReadToEndAsync()
        Directory = $directory
        Port = $port
    }
}

$runDir = Join-Path $repoRoot ('build\local-uranium-transfer-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $runDir -Force | Out-Null
$oldPort = FreePort
$newPort = FreePort
while ($newPort -eq $oldPort) { $newPort = FreePort }
$servers = [System.Collections.Generic.List[object]]::new()
$proxy = $null
try {
    $old = StartUranium (Join-Path $runDir 'old') $oldPort
    $servers.Add($old)
    WaitForReady $oldPort $old.Process $old.Directory
    $new = StartUranium (Join-Path $runDir 'new') $newPort
    $servers.Add($new)
    WaitForReady $newPort $new.Process $new.Directory

    $classes = Join-Path $runDir 'probe-classes'
    New-Item -ItemType Directory -Path $classes -Force | Out-Null
    $classpath = Join-Path $proxyLib '*'
    & javac -cp $classpath -d $classes (Join-Path $PSScriptRoot 'UraniumTransferProbe.java')
    if ($LASTEXITCODE -ne 0) { throw 'Could not compile Uranium transfer probe' }
    if ($InstalledPlugin) {
        $pluginClasses = Join-Path $runDir 'plugin-classes'
        New-Item -ItemType Directory -Path $pluginClasses -Force | Out-Null
        & javac -cp $classpath -d $pluginClasses (Join-Path $PSScriptRoot 'UraniumTransferPlugin.java')
        if ($LASTEXITCODE -ne 0) { throw 'Could not compile Uranium transfer plugin' }
        $serviceDir = Join-Path $pluginClasses 'META-INF\services'
        New-Item -ItemType Directory -Path $serviceDir -Force | Out-Null
        'dev.strataproxy.smoke.UraniumTransferPlugin' |
            Set-Content -LiteralPath (Join-Path $serviceDir 'dev.strataproxy.api.Plugin')
        $pluginDir = Join-Path $runDir 'proxy-plugins'
        New-Item -ItemType Directory -Path $pluginDir -Force | Out-Null
        & jar -cf (Join-Path $pluginDir 'uranium-transfer-smoke.jar') -C $pluginClasses .
        if ($LASTEXITCODE -ne 0) { throw 'Could not package Uranium transfer plugin' }
        $proxyPort = FreePort
        while ($proxyPort -eq $oldPort -or $proxyPort -eq $newPort) { $proxyPort = FreePort }
        $config = Join-Path $runDir 'strataproxy.yml'
        $pluginDirYaml = $pluginDir.Replace('\', '/')
        @"
listen: "127.0.0.1:$proxyPort"
authentication: OFFLINE
plugins:
  directory: "$pluginDirYaml"
  enabled:
    dev.strataproxy.smoke.UraniumTransferPlugin: {}
backends:
  - name: old
    address: "127.0.0.1:$oldPort"
    capacity: 10
  - name: new
    address: "127.0.0.1:$newPort"
    capacity: 10
"@ | Set-Content -LiteralPath $config -Encoding utf8
        $java = (Get-Command java.exe -ErrorAction Stop).Source
        $proxyLog = Join-Path $runDir 'proxy.stdout.log'
        $proxy = Start-Process -FilePath $java -ArgumentList @(
            '-cp', ('"{0}"' -f $classpath), 'dev.strataproxy.app.ProxyMain',
            '--config', ('"{0}"' -f $config)
        ) -WindowStyle Hidden -PassThru -RedirectStandardOutput $proxyLog `
          -RedirectStandardError (Join-Path $runDir 'proxy.stderr.log')
        WaitForPort $proxyPort $proxy
        & java -cp "$classes;$classpath" dev.strataproxy.smoke.UraniumTransferProbe --external $proxyPort
        if ($LASTEXITCODE -ne 0) { throw "Installed-plugin transfer probe failed: $LASTEXITCODE" }
        WaitForProxyLog $proxyLog 'SMOKE_PLUGIN_TRANSFER_PASS status=NETWORK_READY'
        Write-Output 'REAL_URANIUM_PLUGIN_TRANSFER_PASS status=NETWORK_READY'
    } else {
        & java -cp "$classes;$classpath" dev.strataproxy.smoke.UraniumTransferProbe $oldPort $newPort
    }
    if ($LASTEXITCODE -ne 0) { throw "Uranium transfer probe failed: $LASTEXITCODE" }
    WaitForLog $old.Directory 'NettyProbe.*logged in'
    WaitForLog $new.Directory 'NettyProbe.*logged in'
    WaitForLog $old.Directory 'NettyProbe lost connection'
    Write-Output 'REAL_URANIUM_BACKEND_LOGS_PASS oldLogin=true newLogin=true oldDisconnected=true'
} finally {
    if ($proxy -and -not $proxy.HasExited) {
        Stop-Process -Id $proxy.Id -Force
        Wait-Process -Id $proxy.Id -ErrorAction SilentlyContinue
    }
    foreach ($server in $servers) {
        if (-not $server.Process.HasExited) {
            $server.Process.StandardInput.WriteLine('stop')
            $server.Process.StandardInput.Flush()
            if (-not $server.Process.WaitForExit(30000)) {
                $server.Process.Kill()
                $server.Process.WaitForExit()
            }
        }
        $server.Output.GetAwaiter().GetResult() |
            Set-Content -LiteralPath (Join-Path $server.Directory 'server.stdout.log')
        $server.Errors.GetAwaiter().GetResult() |
            Set-Content -LiteralPath (Join-Path $server.Directory 'server.stderr.log')
    }
    Write-Output "Runtime logs: $runDir"
}
