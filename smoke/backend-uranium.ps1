#Requires -Version 7.0
param(
    [Parameter(Mandatory)][string]$BundlePath,
    [Parameter(Mandatory)][string]$BukkitApiJar,
    [string]$Java8Home = 'C:\Program Files\Zulu\zulu-8',
    [string]$Java25Home = 'C:\Program Files\Zulu\zulu-25'
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$bundle = (Resolve-Path -LiteralPath $BundlePath).Path
if ((Get-Content (Join-Path $bundle 'eula.txt') -Raw).Trim() -ne 'eula=true') {
    throw 'Supply a Uranium bundle whose EULA has already been accepted.'
}
$serverJars = @(Get-ChildItem -LiteralPath $bundle -Filter '*-server.jar')
if ($serverJars.Count -ne 1) { throw 'Expected exactly one Uranium server JAR.' }
$serverJar = $serverJars[0]
$serverHash = (Get-FileHash -LiteralPath $serverJar.FullName -Algorithm SHA256).Hash
$dist = Join-Path $repo 'proxy-core/build/install/moonbridge'
$hostJars = @(Get-ChildItem (Join-Path $dist 'backend-host') -Filter 'moonbridge-backend-bukkit-*.jar')
if ($hostJars.Count -ne 1) { throw 'Build the renamed host and installDist first.' }
$run = Join-Path $repo ('build/backend-uranium-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Force $run | Out-Null
Write-Output "ACCEPTANCE_DIRECTORY=$run"
$utf8 = [Text.UTF8Encoding]::new($false)
function Write-Utf8([string]$Path, [string]$Value) { [IO.File]::WriteAllText($Path, $Value, $utf8) }
function New-Port {
    do {
        $socket = [Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback, 0)
        $socket.Start()
        $port = $socket.LocalEndpoint.Port
        $socket.Stop()
    } until ($ports.Add($port))
    return $port
}
$ports = [Collections.Generic.HashSet[int]]::new()
$aPort = New-Port
$bPort = New-Port
$controlPort = New-Port
$proxyPort = New-Port
$controlDir = Join-Path $run 'control'
$proxyPlugins = Join-Path $run 'proxy-plugins'
$probeClasses = Join-Path $run 'probe-classes'
$proxyClasses = Join-Path $run 'proxy-classes'
New-Item -ItemType Directory -Force $controlDir, $proxyPlugins, $probeClasses, $proxyClasses | Out-Null
$proxyClasspath = Join-Path $dist 'lib/*'
# Business plugins compile against public API artifacts only, never the host/transport.
$backendApiClasspath = Join-Path $dist 'backend-api/*'
& (Join-Path $Java8Home 'bin/javac.exe') -cp "$backendApiClasspath;$BukkitApiJar" -d $probeClasses `
    (Join-Path $PSScriptRoot 'BackendAcceptancePlugin.java') (Join-Path $PSScriptRoot 'BackendObserverPlugin.java')
if ($LASTEXITCODE -ne 0) { throw 'Bukkit probe compilation failed.' }
& (Join-Path $Java25Home 'bin/javac.exe') -cp $proxyClasspath -d $proxyClasses `
    (Join-Path $PSScriptRoot 'ProxyBackendAcceptancePlugin.java')
if ($LASTEXITCODE -ne 0) { throw 'Proxy probe compilation failed.' }
$services = Join-Path $proxyClasses 'META-INF/services'
New-Item -ItemType Directory -Force $services | Out-Null
Write-Utf8 (Join-Path $services 'dev.moonbridge.api.Plugin') 'dev.moonbridge.smoke.ProxyBackendAcceptancePlugin'
& (Join-Path $Java25Home 'bin/jar.exe') cf (Join-Path $proxyPlugins 'acceptance.jar') -C $proxyClasses .
if ($LASTEXITCODE -ne 0) { throw 'Proxy probe packaging failed.' }
foreach ($role in @('Probe', 'Observer')) {
    $main = if ($role -eq 'Probe') { 'BackendAcceptancePlugin' } else { 'BackendObserverPlugin' }
    Write-Utf8 (Join-Path $probeClasses 'plugin.yml') "name: Acceptance$role`nmain: dev.moonbridge.smoke.$main`nversion: 1.0`ndepend: [MoonBridgeBackend]`n"
    & (Join-Path $Java8Home 'bin/jar.exe') cf (Join-Path $run "Acceptance$role.jar") `
        -C $probeClasses "dev/moonbridge/smoke/$main.class" -C $probeClasses plugin.yml
    if ($LASTEXITCODE -ne 0) { throw 'Bukkit probe packaging failed.' }
}
# Each run is isolated. Copy runtime libraries, the server and the already accepted
# EULA; do not copy or alter live worlds, mods, credentials or plugin directories.
foreach ($node in @('a', 'b')) {
    $directory = Join-Path $run $node
    $pluginDir = Join-Path $directory 'plugins'
    $hostConfigDir = Join-Path $pluginDir 'MoonBridgeBackend'
    New-Item -ItemType Directory -Force $directory, $pluginDir, $hostConfigDir | Out-Null
    Copy-Item -LiteralPath $serverJar.FullName -Destination $directory
    Copy-Item -LiteralPath (Join-Path $bundle 'libraries') -Destination $directory -Recurse
    Copy-Item -LiteralPath (Join-Path $bundle 'eula.txt') -Destination $directory
    if ((Get-FileHash (Join-Path $directory $serverJar.Name)).Hash -ne $serverHash) {
        throw 'Source server artifact changed while creating the isolated runtime.'
    }
    Copy-Item -LiteralPath $hostJars[0].FullName -Destination $pluginDir
    foreach ($role in @('Probe', 'Observer')) {
        Copy-Item -LiteralPath (Join-Path $run "Acceptance$role.jar") -Destination $pluginDir
        $configDir = Join-Path $pluginDir "Acceptance$role"
        New-Item -ItemType Directory -Force $configDir | Out-Null
        Write-Utf8 (Join-Path $configDir 'config.yml') "backendName: $node`n"
    }
    $port = if ($node -eq 'a') { $aPort } else { $bPort }
    Write-Utf8 (Join-Path $directory 'server.properties') "server-ip=127.0.0.1`nserver-port=$port`nonline-mode=false`nlevel-type=FLAT`ngenerate-structures=false`nview-distance=2`nspawn-protection=0`nmax-players=1`n"
    Write-Utf8 (Join-Path $hostConfigDir 'config.yml') @"
proxyHost: 127.0.0.1
proxyPort: $controlPort
instanceId: probe-$node
backendName: $node
gameAddress: "tcp://127.0.0.1:$port"
keyId: primary
secret: isolated-acceptance-secret-at-least-32-bytes
"@
}
$controlYaml = $controlDir.Replace('\', '/')
$pluginYaml = $proxyPlugins.Replace('\', '/')
$config = Join-Path $run 'moonbridge.yml'
Write-Utf8 $config @"
listen: "127.0.0.1:$proxyPort"
authentication: OFFLINE
backends: []
plugins:
  directory: "$pluginYaml"
  enabled:
    dev.moonbridge.smoke.ProxyBackendAcceptancePlugin:
      controlDir: "$controlYaml"
      aPort: "$aPort"
      bPort: "$bPort"
backendChannel:
  listen: "127.0.0.1:$controlPort"
  leaseSeconds: 30
  maxConnections: 8
  clients:
    probe-a:
      backendName: a
      keyId: primary
      secret: isolated-acceptance-secret-at-least-32-bytes
      allowedHosts: [127.0.0.1]
      allowedNamespaces: [accept]
    probe-b:
      backendName: b
      keyId: primary
      secret: isolated-acceptance-secret-at-least-32-bytes
      allowedHosts: [127.0.0.1]
      allowedNamespaces: [accept]
"@
$owned = [Collections.Generic.List[object]]::new()
function Start-Owned([string]$Name, [string]$Executable, [string]$Directory, [string[]]$Arguments) {
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $Executable
    $info.WorkingDirectory = $Directory
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    foreach ($argument in $Arguments) { $info.ArgumentList.Add($argument) }
    $process = [Diagnostics.Process]::Start($info)
    $entry = [pscustomobject]@{ Name=$Name; Process=$process; Out=$process.StandardOutput.ReadToEndAsync(); Err=$process.StandardError.ReadToEndAsync() }
    $owned.Add($entry)
    return $entry
}
function Start-Proxy([string]$Name) {
    return Start-Owned $Name (Join-Path $Java25Home 'bin/java.exe') $run @('-cp', $proxyClasspath, 'dev.moonbridge.app.ProxyMain', '--config', $config)
}
function Start-Backend([string]$Node, [string]$Name) {
    $log = Join-Path $run "$Node/logs/latest.log"
    if (Test-Path -LiteralPath $log) {
        Move-Item -LiteralPath $log -Destination (Join-Path $run "$Name.previous-server.log")
    }
    $entry = Start-Owned $Name (Join-Path $Java8Home 'bin/java.exe') (Join-Path $run $Node) @('-Xms256m', '-Xmx1024m', '-jar', $serverJar.Name, 'nogui')
    $deadline = [DateTime]::UtcNow.AddSeconds(180)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($entry.Process.HasExited) { throw "$Name exited before readiness: $($entry.Err.Result)" }
        if ((Test-Path $log) -and (Select-String -LiteralPath $log -Pattern 'Done \(' -Quiet)) { return $entry }
        Start-Sleep -Milliseconds 200
    }
    throw "$Name startup deadline exceeded; inspect $log"
}
function Stop-Backend($Entry, [switch]$NoWait) {
    $Entry.Process.StandardInput.WriteLine('stop')
    $Entry.Process.StandardInput.Flush()
    if ($NoWait) { return }
    if (-not $Entry.Process.WaitForExit(45000)) { throw "$($Entry.Name) did not stop normally." }
    if ($Entry.Process.ExitCode -ne 0) { throw "$($Entry.Name) exited with $($Entry.Process.ExitCode)." }
}
function Kill-Owned($Entry) {
    if (-not $Entry.Process.HasExited) { $Entry.Process.Kill() }
    if (-not $Entry.Process.WaitForExit(5000) -or -not $Entry.Process.HasExited) {
        throw "$($Entry.Name) process $($Entry.Process.Id) did not exit after termination."
    }
}
function Invoke-Phase([string]$Phase) {
    $id = [guid]::NewGuid().ToString('N')
    $temporary = Join-Path $controlDir "$id.tmp"
    Write-Utf8 $temporary "$id $Phase"
    Move-Item -LiteralPath $temporary -Destination (Join-Path $controlDir 'command.txt') -Force
    $deadline = [DateTime]::UtcNow.AddSeconds(90)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($proxy.Process.HasExited) { throw 'Proxy exited during acceptance.' }
        $failure = Join-Path $controlDir "$id.fail"
        if (Test-Path $failure) { throw (Get-Content $failure -Raw) }
        $success = Join-Path $controlDir "$id.pass"
        if (Test-Path $success) {
            Write-Output (Get-Content $success -Raw)
            return
        }
        Start-Sleep -Milliseconds 100
    }
    throw "Acceptance phase $Phase exceeded its deadline."
}
try {
    $proxy = Start-Proxy 'proxy-initial'
    $a = Start-Backend a 'backend-a'
    $b = Start-Backend b 'backend-b'
    Invoke-Phase baseline
    Kill-Owned $proxy
    Remove-Item -LiteralPath (Join-Path $controlDir 'command.txt')
    $proxy = Start-Proxy 'proxy-restarted'
    Invoke-Phase reconnect
    Invoke-Phase disable
    Stop-Backend $b -NoWait
    Invoke-Phase b-absent-fast
    if (-not $b.Process.WaitForExit(45000) -or $b.Process.ExitCode -ne 0) {
        throw 'Backend B failed to stop cleanly after its registration was removed.'
    }
    $b = Start-Backend b 'backend-b-restarted'
    Invoke-Phase b-present
    Kill-Owned $b
    Invoke-Phase b-absent
    $b = Start-Backend b 'backend-b-after-crash'
    Invoke-Phase b-present
    Stop-Backend $b
    Stop-Backend $a
    Write-Utf8 (Join-Path $run 'acceptance-pass.txt') "BACKEND_URANIUM_PASS`nserver=$($serverJar.Name)`nserverSHA256=$serverHash`nhostSHA256=$((Get-FileHash $hostJars[0].FullName).Hash)`n"
    Write-Output "BACKEND_URANIUM_PASS directory=$run"
} finally {
    $cleanupFailed = $false
    foreach ($entry in $owned) {
        try { Kill-Owned $entry }
        catch { $cleanupFailed = $true; Write-Warning $_ }
        foreach ($stream in @(@{ Task=$entry.Out; Suffix='stdout' }, @{ Task=$entry.Err; Suffix='stderr' })) {
            if ($stream.Task.Wait(5000)) {
                Write-Utf8 (Join-Path $run "$($entry.Name).$($stream.Suffix).log") $stream.Task.GetAwaiter().GetResult()
            } else {
                $cleanupFailed = $true
                Write-Warning "Timed out draining $($entry.Name) $($stream.Suffix); runtime evidence remains in $run"
            }
        }
        $entry.Process.Dispose()
    }
    if ($cleanupFailed) { throw "Acceptance process cleanup failed; inspect $run" }
}
