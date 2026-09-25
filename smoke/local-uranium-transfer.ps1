#Requires -Version 7.0
param(
    [Parameter(Mandatory)][string]$BundlePath,
    [string]$Java8Path = 'C:\Program Files\Zulu\zulu-8\bin\java.exe',
    [switch]$InstalledPlugin,
    [switch]$DebugSession,
    [switch]$PrismClient,
    [switch]$ReturnToOld,
    [string]$PrismInstance = '1.7.10',
    [string]$PrismInstanceFolder,
    [string]$PrismPath = (Join-Path $env:LOCALAPPDATA 'Programs\PrismLauncher\prismlauncher.exe')
)

$ErrorActionPreference = 'Stop'
if ($PrismClient -and -not $InstalledPlugin) { throw '-PrismClient requires -InstalledPlugin' }
if ($DebugSession -and -not $InstalledPlugin) { throw '-DebugSession requires -InstalledPlugin' }
if ($ReturnToOld -and -not $InstalledPlugin) {
    throw '-ReturnToOld requires -InstalledPlugin'
}
if (-not $PrismInstanceFolder) { $PrismInstanceFolder = $PrismInstance }
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

function IsPrismInstanceProcess([object]$process) {
    if (-not $process.CommandLine) { return $false }
    $line = $process.CommandLine.Replace('/', '\')
    return $line.IndexOf('PrismLauncher', [StringComparison]::OrdinalIgnoreCase) -ge 0 -and
        $line.IndexOf("\instances\$PrismInstanceFolder\", [StringComparison]::OrdinalIgnoreCase) -ge 0
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
    if ($DebugSession) {
        $cauldronConfig = Join-Path $directory 'cauldron.yml'
        if (Test-Path -LiteralPath $cauldronConfig) {
            $contents = Get-Content -LiteralPath $cauldronConfig -Raw
            if ($contents -match '(?m)^[ \t]*user-login:[ \t]*(?:true|false)[ \t]*$') {
                $contents = $contents -replace '(?m)^([ \t]*user-login:[ \t]*)(?:true|false)[ \t]*$', '${1}true'
            } elseif ($contents -match '(?m)^logging:[ \t]*$') {
                $contents = $contents -replace '(?m)^logging:[ \t]*$', "logging:`n  user-login: true"
            } else {
                $contents += "`nlogging:`n  user-login: true`n"
            }
            Set-Content -LiteralPath $cauldronConfig -Value $contents -Encoding utf8
        } else {
            "logging:`n  user-login: true" |
                Set-Content -LiteralPath $cauldronConfig -Encoding utf8
        }
    }
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
$prismLauncher = $null
$initialJavawIds = @()
$clientLaunchAttempted = $false
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
        $returnToOldSetting = if ($ReturnToOld) { 'true' } else { 'false' }
        @"
listen: "127.0.0.1:$proxyPort"
authentication: OFFLINE
plugins:
  directory: "$pluginDirYaml"
  enabled:
    dev.strataproxy.smoke.UraniumTransferPlugin:
      newPort: "$newPort"
      returnToOld: "$returnToOldSetting"
backends:
  - name: old
    address: "127.0.0.1:$oldPort"
"@ | Set-Content -LiteralPath $config -Encoding utf8
        $java = (Get-Command java.exe -ErrorAction Stop).Source
        $proxyLog = Join-Path $runDir 'proxy.stdout.log'
        $debugJvmArgs = @()
        if ($DebugSession) {
            $logbackConfig = Join-Path $runDir 'logback-debug.xml'
            @'
<configuration>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder><pattern>%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] %logger{36} - %msg%n</pattern></encoder>
    </appender>
    <logger name="dev.strataproxy.core.session.Session" level="DEBUG" />
    <root level="INFO"><appender-ref ref="CONSOLE" /></root>
</configuration>
'@ | Set-Content -LiteralPath $logbackConfig -Encoding utf8
            $debugJvmArgs = @(('"-Dlogback.configurationFile={0}"' -f $logbackConfig.Replace('\', '/')))
        }
        $proxy = Start-Process -FilePath $java -ArgumentList @(
            $debugJvmArgs
            '-cp', ('"{0}"' -f $classpath), 'dev.strataproxy.app.ProxyMain',
            '--config', ('"{0}"' -f $config)
        ) -WindowStyle Hidden -PassThru -RedirectStandardOutput $proxyLog `
          -RedirectStandardError (Join-Path $runDir 'proxy.stderr.log')
        WaitForPort $proxyPort $proxy
        if ($PrismClient) {
            if (-not (Test-Path -LiteralPath $PrismPath)) { throw "Prism Launcher not found: $PrismPath" }
            $existingSmokeLauncher = @(Get-CimInstance Win32_Process -Filter "Name = 'prismlauncher.exe'" |
                Where-Object { $_.CommandLine -match '--offline\s+PrismSmoke' })
            if ($existingSmokeLauncher.Count -ne 0) {
                throw 'A PrismSmoke launcher from another run is still active'
            }
            $initialJavawIds = @(Get-CimInstance Win32_Process -Filter "Name = 'javaw.exe'" |
                Select-Object -ExpandProperty ProcessId)
            $prismLauncher = Start-Process -FilePath $PrismPath -ArgumentList @(
                '--launch', $PrismInstance, '--offline', 'PrismSmoke',
                '--server', "127.0.0.1:$proxyPort"
            ) -WindowStyle Hidden -PassThru
            $clientLaunchAttempted = $true
            $deadline = [DateTime]::UtcNow.AddSeconds(120)
            $oldLog = Join-Path $old.Directory 'logs\latest.log'
            $newLog = Join-Path $new.Directory 'logs\latest.log'
            while ([DateTime]::UtcNow -lt $deadline) {
                $oldJoined = Select-String -LiteralPath $oldLog -Pattern 'PrismSmoke.*logged in' -Quiet
                $newJoined = Select-String -LiteralPath $newLog -Pattern 'PrismSmoke.*logged in' -Quiet
                $transferred = Select-String -LiteralPath $proxyLog `
                    -Pattern 'SMOKE_PLUGIN_TRANSFER_PASS status=NETWORK_READY' -Quiet
                $returned = $false
                $secondOldLogin = $false
                $newDisconnected = $false
                if ($ReturnToOld) {
                    $returned = Select-String -LiteralPath $proxyLog `
                        -Pattern 'SMOKE_PLUGIN_RETURN_PASS status=NETWORK_READY' -Quiet
                    $secondOldLogin = @(Select-String -LiteralPath $oldLog -Pattern 'PrismSmoke.*logged in').Count -ge 2
                    $newDisconnected = Select-String -LiteralPath $newLog -Pattern 'PrismSmoke lost connection' -Quiet
                }
                if ($oldJoined -and $newJoined -and $transferred -and
                    (-not $ReturnToOld -or ($returned -and $secondOldLogin -and $newDisconnected))) { break }
                if (Select-String -LiteralPath $proxyLog -Pattern 'SMOKE_PLUGIN_TRANSFER_FAILED' -Quiet) {
                    throw 'Proxy rejected the Prism Forge transfer; inspect proxy.stdout.log and the target server log'
                }
                if (Select-String -LiteralPath $proxyLog -Pattern 'SMOKE_PLUGIN_RETURN_FAILED' -Quiet) {
                    throw 'Proxy rejected the return Forge transfer; inspect proxy.stdout.log and the old server log'
                }
                if (-not $ReturnToOld -and
                    (Select-String -LiteralPath $newLog -Pattern 'PrismSmoke lost connection' -Quiet)) {
                    throw 'Target Uranium disconnected the Prism Forge client during transfer'
                }
                if ($proxy.HasExited) { throw "Proxy exited while Prism was transferring: $($proxy.ExitCode)" }
                if ($old.Process.HasExited -or $new.Process.HasExited) { throw 'Uranium exited during Prism transfer' }
                Start-Sleep -Milliseconds 500
            }
            if (-not $oldJoined -or -not $newJoined -or -not $transferred -or
                ($ReturnToOld -and (-not $returned -or -not $secondOldLogin -or -not $newDisconnected))) {
                throw 'Prism Forge client did not finish Uranium transfer sequence within 120 seconds'
            }
            Start-Sleep -Seconds 10
            if ($ReturnToOld) {
                if (@(Select-String -LiteralPath $oldLog -Pattern 'PrismSmoke lost connection').Count -ge 2) {
                    throw 'Prism Forge client disconnected after returning to old during the 10-second hold'
                }
            } elseif (Select-String -LiteralPath $newLog -Pattern 'PrismSmoke lost connection' -Quiet) {
                throw 'Prism Forge client disconnected from target during the 10-second hold'
            }
            $newClient = @(Get-CimInstance Win32_Process -Filter "Name = 'javaw.exe'" |
                Where-Object { $_.ProcessId -notin $initialJavawIds -and (IsPrismInstanceProcess $_) })
            if ($newClient.Count -ne 1) {
                throw "Expected one new Prism Java client, found $($newClient.Count)"
            }
            if ($ReturnToOld) {
                Write-Output 'REAL_PRISM_URANIUM_ROUNDTRIP_PASS dynamicRegistration=true transfers=2 status=NETWORK_READY holdSeconds=10'
            } else {
                Write-Output 'REAL_PRISM_URANIUM_TRANSFER_PASS dynamicRegistration=true status=NETWORK_READY holdSeconds=10'
            }
        } else {
            $probeMode = if ($ReturnToOld) { '--external-roundtrip' } else { '--external' }
            & java -cp "$classes;$classpath" dev.strataproxy.smoke.UraniumTransferProbe $probeMode $proxyPort
            if ($LASTEXITCODE -ne 0) { throw "Installed-plugin transfer probe failed: $LASTEXITCODE" }
            if ($ReturnToOld) {
                Write-Output 'REAL_URANIUM_PLUGIN_ROUNDTRIP_PASS dynamicRegistration=true transfers=2 status=NETWORK_READY'
            } else {
                Write-Output 'REAL_URANIUM_PLUGIN_TRANSFER_PASS dynamicRegistration=true status=NETWORK_READY'
            }
        }
        WaitForProxyLog $proxyLog 'SMOKE_PLUGIN_REGISTER_PASS name=new'
        WaitForProxyLog $proxyLog 'SMOKE_PLUGIN_TRANSFER_PASS status=NETWORK_READY'
        if ($ReturnToOld) { WaitForProxyLog $proxyLog 'SMOKE_PLUGIN_RETURN_PASS status=NETWORK_READY' }
    } else {
        & java -cp "$classes;$classpath" dev.strataproxy.smoke.UraniumTransferProbe $oldPort $newPort
        if ($LASTEXITCODE -ne 0) { throw "Uranium transfer probe failed: $LASTEXITCODE" }
    }
    $playerName = if ($PrismClient) { 'PrismSmoke' } else { 'NettyProbe' }
    WaitForLog $old.Directory "$playerName.*logged in"
    WaitForLog $new.Directory "$playerName.*logged in"
    WaitForLog $old.Directory "$playerName lost connection"
    Write-Output 'REAL_URANIUM_BACKEND_LOGS_PASS oldLogin=true newLogin=true oldDisconnected=true'
    if ($ReturnToOld) {
        if (@(Select-String -LiteralPath (Join-Path $old.Directory 'logs\latest.log') `
                -Pattern "$playerName.*logged in").Count -lt 2) {
            throw 'Old Uranium did not log the returning player'
        }
        WaitForLog $new.Directory "$playerName lost connection"
        Write-Output 'REAL_URANIUM_ROUNDTRIP_LOGS_PASS oldLogins=2 newLogin=true newDisconnected=true'
    }
} finally {
    if ($clientLaunchAttempted) {
        Get-CimInstance Win32_Process -Filter "Name = 'javaw.exe'" |
            Where-Object { $_.ProcessId -notin $initialJavawIds -and (IsPrismInstanceProcess $_) } |
            ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    }
    if ($prismLauncher -and -not $prismLauncher.HasExited) {
        Stop-Process -Id $prismLauncher.Id -Force -ErrorAction SilentlyContinue
    }
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
