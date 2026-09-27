#Requires -Version 7.0
param(
    [string]$BundlePath = 'C:\Users\layue\Documents\ChatGPT\tdlm 2\Uranium\rfg\build\rfg-netty-compat-bundle',
    [string]$DistributionPath = (Join-Path (Split-Path -Parent $PSScriptRoot) 'proxy-core\build\install\moonbridge'),
    [string]$Java8Path = 'C:\Program Files\Zulu\zulu-8\bin\java.exe',
    [string]$Java8wPath = 'C:\Program Files\Zulu\zulu-8\bin\javaw.exe',
    [string]$PrismTemplateInstance = '1.7.10',
    [string]$PrismCacheRoot = (Join-Path $env:APPDATA 'PrismLauncher')
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$bundle = (Resolve-Path -LiteralPath $BundlePath).Path
$distribution = (Resolve-Path -LiteralPath $DistributionPath).Path
$template = Join-Path (Join-Path $PrismCacheRoot 'instances') $PrismTemplateInstance
$directHelper = Join-Path $PSScriptRoot 'permission-client-direct.cjs'
$distributionLib = Join-Path $distribution 'lib'
$proxyLib = Join-Path $distribution 'lib\*'
$packagedPlugins = Join-Path $distribution 'plugins'
$java25 = (Get-Command java.exe -ErrorAction Stop).Source
$javac25 = (Get-Command javac.exe -ErrorAction Stop).Source
$jar25 = (Get-Command jar.exe -ErrorAction Stop).Source
$node = (Get-Command node.exe -ErrorAction Stop).Source

foreach ($required in @($Java8Path, $Java8wPath, $template, $PrismCacheRoot, $distributionLib,
        $packagedPlugins, $directHelper)) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Required client acceptance input not found: $required" }
}
if ((Get-Content -LiteralPath (Join-Path $bundle 'eula.txt') -Raw).Trim() -ne 'eula=true') {
    throw 'The supplied Uranium bundle must already contain eula=true'
}
$serverJars = @(Get-ChildItem -LiteralPath $bundle -Filter '*-server.jar')
if ($serverJars.Count -ne 1) { throw "Expected exactly one Uranium server JAR in $bundle" }
$luckPermsJars = @(Get-ChildItem -LiteralPath $packagedPlugins -Filter 'luckperms-moonbridge-*.jar' |
    Where-Object { $_.Name -notlike '*-engine.jar' })
if ($luckPermsJars.Count -ne 1) { throw "Expected one packaged LuckPerms MoonBridge plugin in $packagedPlugins" }
$templateCfg = Join-Path $template 'instance.cfg'
$templatePack = Join-Path $template 'mmc-pack.json'
foreach ($required in @($templateCfg, $templatePack)) {
    if (-not (Test-Path -LiteralPath $required)) { throw "Prism template metadata missing: $required" }
}
$packJson = Get-Content -LiteralPath $templatePack -Raw | ConvertFrom-Json
$components = @($packJson.components | ForEach-Object { "$($_.uid)@$($_.version)" })
if (-not ($components -contains 'net.minecraft@1.7.10') -or
    -not ($components -contains 'net.minecraftforge@10.13.4.1614')) {
    throw "Prism template must be Minecraft 1.7.10 with Forge 10.13.4.1614; found $($components -join ', ')"
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
        try { $client.Connect([System.Net.IPAddress]::Loopback, $port); return }
        catch [System.Net.Sockets.SocketException] { Start-Sleep -Milliseconds 200 }
        finally { $client.Dispose() }
    }
    throw "Port $port did not open within $timeoutSeconds seconds"
}

function WaitForRealClient([int]$processId, [string]$instanceFolder, [int]$timeoutSeconds) {
    $deadline = [DateTime]::UtcNow.AddSeconds($timeoutSeconds)
    while ([DateTime]::UtcNow -lt $deadline) {
        $pathPart = "\instances\$instanceFolder\"
        $process = Get-CimInstance Win32_Process -Filter "ProcessId = '$processId'" -ErrorAction SilentlyContinue
        if ($process) {
            if ($process.Name -ne 'javaw.exe') { throw "Expected javaw.exe for real client PID $processId, found $($process.Name)" }
            if (-not $process.CommandLine -or
                $process.CommandLine.Replace('/', '\').IndexOf($pathPart, [StringComparison]::OrdinalIgnoreCase) -lt 0) {
                throw "Real client PID $processId does not reference the isolated profile $instanceFolder"
            }
            return $process
        }
        Start-Sleep -Milliseconds 500
    }
    throw "Direct Java 8 client PID $processId was not running within $timeoutSeconds seconds"
}

function SaveClientLogs([string]$profile, [string]$destination) {
    $source = Join-Path $profile 'minecraft\logs'
    $saved = $false
    New-Item -ItemType Directory -Path $destination -Force | Out-Null
    if (Test-Path -LiteralPath $source) {
        Get-ChildItem -LiteralPath $source -Force | Copy-Item -Destination $destination -Recurse -Force
        $saved = $true
    }
    Get-ChildItem -LiteralPath $profile -File -Filter 'direct-client.*' -ErrorAction SilentlyContinue |
        Copy-Item -Destination $destination -Force
    if (Test-Path -LiteralPath (Join-Path $destination 'direct-client.stdout.log')) { $saved = $true }
    if (Test-Path -LiteralPath (Join-Path $destination 'direct-client.stderr.log')) { $saved = $true }
    return $saved
}

$runDir = Join-Path $repoRoot ('build\permission-client-' + [guid]::NewGuid().ToString('N'))
$instancesRoot = Join-Path $runDir 'instances'
$backendDir = Join-Path $runDir 'backend'
$pluginDir = Join-Path $runDir 'proxy-plugins'
$pluginClasses = Join-Path $runDir 'plugin-classes'
$evidenceDir = Join-Path $runDir 'evidence'
$profileName = 'MoonBridgePermission-' + [guid]::NewGuid().ToString('N')
$temporaryProfile = Join-Path $instancesRoot $profileName
$clientPid = $null
$clientLogsCopied = $false
$server = $null
$serverOutput = $null
$serverErrors = $null
$proxy = $null
$helperProcess = $null

try {
    New-Item -ItemType Directory -Path $runDir, $backendDir, $pluginDir, $pluginClasses, $evidenceDir `
        -Force | Out-Null
    Get-ChildItem -LiteralPath $bundle | Copy-Item -Destination $backendDir -Recurse
    $serverJar = @(Get-ChildItem -LiteralPath $backendDir -Filter '*-server.jar')
    if ($serverJar.Count -ne 1) { throw "Expected one copied Uranium server JAR in $backendDir" }
    $bundleHash = (Get-FileHash -LiteralPath $serverJar[0].FullName -Algorithm SHA256).Hash

    $serverPort = FreePort
    $proxyPort = FreePort
    while ($proxyPort -eq $serverPort) { $proxyPort = FreePort }
    @"
server-port=$serverPort
server-ip=127.0.0.1
online-mode=false
level-name=world
motd=MoonBridge LuckPerms client acceptance
"@ | Set-Content -LiteralPath (Join-Path $backendDir 'server.properties') -Encoding utf8

    $info = [System.Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $Java8Path
    $info.WorkingDirectory = $backendDir
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    foreach ($argument in @('-Xms512m', '-Xmx1024m', '-jar', $serverJar[0].Name, 'nogui')) {
        $info.ArgumentList.Add($argument)
    }
    $server = [System.Diagnostics.Process]::Start($info)
    $serverOutput = $server.StandardOutput.ReadToEndAsync()
    $serverErrors = $server.StandardError.ReadToEndAsync()
    WaitForPort $serverPort $server 120
    $serverLog = Join-Path $backendDir 'logs\latest.log'
    $readyDeadline = [DateTime]::UtcNow.AddSeconds(120)
    while ([DateTime]::UtcNow -lt $readyDeadline) {
        if ($server.HasExited) { throw "Uranium exited during startup: $($server.ExitCode)" }
        if ((Test-Path -LiteralPath $serverLog) -and
            (Select-String -LiteralPath $serverLog -Pattern 'Done \(' -Quiet)) { break }
        Start-Sleep -Milliseconds 250
    }
    if (-not (Test-Path -LiteralPath $serverLog) -or
        -not (Select-String -LiteralPath $serverLog -Pattern 'Done \(' -Quiet)) {
        throw 'Uranium did not report ready within 120 seconds'
    }

    & $javac25 -cp $proxyLib -d $pluginClasses `
        (Join-Path $PSScriptRoot 'PermissionClientAcceptancePlugin.java')
    if ($LASTEXITCODE -ne 0) { throw 'Could not compile PermissionClientAcceptancePlugin against the installed distribution' }
    $serviceDir = Join-Path $pluginClasses 'META-INF\services'
    New-Item -ItemType Directory -Path $serviceDir -Force | Out-Null
    'dev.moonbridge.smoke.PermissionClientAcceptancePlugin' |
        Set-Content -LiteralPath (Join-Path $serviceDir 'dev.moonbridge.api.Plugin') -Encoding utf8
    & $jar25 -cf (Join-Path $pluginDir 'permission-client-acceptance.jar') -C $pluginClasses .
    if ($LASTEXITCODE -ne 0) { throw 'Could not package the permission client acceptance plugin' }
    Copy-Item -LiteralPath $luckPermsJars[0].FullName -Destination $pluginDir
    $luckPermsData = Join-Path $pluginDir 'data\dev.moonbridge.luckperms.LuckPermsMoonBridgePlugin'
    New-Item -ItemType Directory -Path $luckPermsData -Force | Out-Null
    @'
server: acceptance-proxy
storage-method: h2
messaging-service: none
'@ | Set-Content -LiteralPath (Join-Path $luckPermsData 'config.yml') -Encoding utf8

    $proxyConfig = Join-Path $runDir 'moonbridge.yml'
    $pluginDirYaml = $pluginDir.Replace('\', '/')
    $evidenceDirYaml = $evidenceDir.Replace('\', '/')
    @"
listen: "127.0.0.1:$proxyPort"
authentication: OFFLINE
initialRouting:
  servers: [uranium]
  timeoutSeconds: 15
plugins:
  directory: "$pluginDirYaml"
  enabled:
    dev.moonbridge.luckperms.LuckPermsMoonBridgePlugin:
      proxy-id: acceptance-proxy
    dev.moonbridge.smoke.PermissionClientAcceptancePlugin:
      evidenceDir: "$evidenceDirYaml"
backends:
  - name: uranium
    address: "127.0.0.1:$serverPort"
"@ | Set-Content -LiteralPath $proxyConfig -Encoding utf8
    $proxy = Start-Process -FilePath $java25 -ArgumentList @(
        '-cp', ('"{0}"' -f $proxyLib), 'dev.moonbridge.app.ProxyMain',
        '--config', ('"{0}"' -f $proxyConfig)
    ) -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runDir 'proxy.stdout.log') `
      -RedirectStandardError (Join-Path $runDir 'proxy.stderr.log')
    WaitForPort $proxyPort $proxy 20

    New-Item -ItemType Directory -Path $instancesRoot -Force | Out-Null
    $templateCfgText = Get-Content -LiteralPath $templateCfg -Raw
    $templateCfgText = $templateCfgText -replace '(?m)^name=.*$', "name=$profileName"
    New-Item -ItemType Directory -Path $temporaryProfile -Force | Out-Null
    Set-Content -LiteralPath (Join-Path $temporaryProfile 'instance.cfg') -Value $templateCfgText -Encoding utf8
    Copy-Item -LiteralPath $templatePack -Destination $temporaryProfile
    New-Item -ItemType Directory -Path (Join-Path $temporaryProfile 'minecraft') -Force | Out-Null
    $profileMods = @(Get-ChildItem (Join-Path $temporaryProfile 'minecraft') -Filter '*.jar' -Recurse -File)
    if ($profileMods.Count -ne 0) { throw 'Temporary Prism profile unexpectedly contains client mods' }

    $helperStdout = Join-Path $runDir 'direct-helper.stdout.log'
    $helperStderr = Join-Path $runDir 'direct-helper.stderr.log'
    $helperProcess = Start-Process -FilePath $node -ArgumentList @(
        ('"{0}"' -f $directHelper), ('"{0}"' -f $temporaryProfile), [string]$proxyPort,
        ('"{0}"' -f $PrismCacheRoot), ('"{0}"' -f $Java8wPath)
    ) -WorkingDirectory $repoRoot -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput $helperStdout -RedirectStandardError $helperStderr
    if (-not $helperProcess.WaitForExit(60000)) {
        Stop-Process -Id $helperProcess.Id -Force -ErrorAction SilentlyContinue
        throw 'Direct Minecraft launcher helper did not finish within 60 seconds'
    }
    if ($helperProcess.ExitCode -ne 0) { throw "Direct Minecraft launcher failed with exit code $($helperProcess.ExitCode); inspect $helperStderr" }
    $helperResult = Get-Content -LiteralPath $helperStdout -Raw | ConvertFrom-Json
    if ([IO.Path]::GetFullPath([string]$helperResult.profile) -ne [IO.Path]::GetFullPath($temporaryProfile)) {
        throw 'Direct Minecraft launcher returned a profile other than this run''s isolated profile'
    }
    if ($helperResult.accountDataRead -ne $false) { throw 'Direct Minecraft launcher must not read account data' }
    if ([int]$helperResult.libraryCount -le 0) { throw 'Direct Minecraft launcher resolved no cached libraries' }
    $clientPid = [int]$helperResult.pid
    $null = WaitForRealClient $clientPid $profileName 30
    $resultLog = Join-Path $evidenceDir 'result.log'
    $deadline = [DateTime]::UtcNow.AddSeconds(360)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ($proxy.HasExited) { throw "MoonBridge exited during client acceptance: $($proxy.ExitCode)" }
        if ($server.HasExited) { throw "Uranium exited during client acceptance: $($server.ExitCode)" }
        if (-not (Get-Process -Id $clientPid -ErrorAction SilentlyContinue)) {
            throw 'Real Forge Minecraft client exited before permission acceptance completed'
        }
        if (Test-Path -LiteralPath $resultLog) {
            if (Select-String -LiteralPath $resultLog -Pattern '^PERMISSION_CLIENT_ACCEPTANCE_FAIL ' -Quiet) {
                throw "Permission client plugin reported failure; inspect $resultLog"
            }
            if (Select-String -LiteralPath $resultLog -Pattern '^PERMISSION_CLIENT_ACCEPTANCE_PASS ' -Quiet) { break }
        }
        Start-Sleep -Milliseconds 500
    }
    if (-not (Test-Path -LiteralPath $resultLog) -or
        -not (Select-String -LiteralPath $resultLog -Pattern '^PERMISSION_CLIENT_ACCEPTANCE_PASS ' -Quiet)) {
        throw "Permission client acceptance did not finish within 360 seconds; inspect $resultLog"
    }
    if (-not (Select-String -LiteralPath $serverLog -Pattern 'PrismSmoke.*logged in' -Quiet)) {
        throw 'Uranium did not record the real Prism client login'
    }
    $forgeServerLog = Join-Path $backendDir 'logs\fml-server-latest.log'
    if (-not (Test-Path -LiteralPath $forgeServerLog) -or
        -not (Select-String -LiteralPath $forgeServerLog -Pattern 'Server side modded connection established' -Quiet)) {
        throw 'Uranium did not record completion of the Forge modded handshake'
    }
    if (Select-String -LiteralPath $serverLog -Pattern 'PrismSmoke lost connection' -Quiet) {
        throw 'Uranium recorded a PrismSmoke disconnect during acceptance'
    }

    $clientLogCopy = Join-Path $runDir 'client-logs'
    $clientLogsCopied = SaveClientLogs $temporaryProfile $clientLogCopy
    if (-not $clientLogsCopied) { throw 'Temporary Forge profile has no Minecraft client logs' }
    $clientLog = Join-Path $clientLogCopy 'latest.log'
    $fmlLog = Join-Path $clientLogCopy 'fml-client-latest.log'
    $parserPattern = 'JsonParseException|Unable to parse|Failed to parse.{0,40}(chat|component)|ChatComponent.{0,40}(Exception|error)'
    foreach ($log in @($clientLog, $fmlLog)) {
        if ((Test-Path -LiteralPath $log) -and (Select-String -LiteralPath $log -Pattern $parserPattern -Quiet)) {
            throw "Client log contains a chat/component parsing error: $log"
        }
    }
    if (-not (Select-String -LiteralPath $clientLog -Pattern '\[CHAT\].*PERMISSION_CLIENT_COMMAND_PASS' -Quiet)) {
        throw 'Client log did not record the permission-gated command marker'
    }
    if (-not (Select-String -LiteralPath $clientLog -Pattern '\[CHAT\].*PERMISSION_CLIENT_RICH_TEXT_PASS' -Quiet)) {
        throw 'Client log did not record the rich-text marker'
    }
    if (Select-String -LiteralPath $clientLog -Pattern 'luckperms\.command\.' -Quiet) {
        throw 'Client log contains an untranslated LuckPerms command key'
    }
    $null = WaitForRealClient $clientPid $profileName 5
    Write-Output "REAL_FORGE_LUCKPERMS_CLIENT_PASS instance=$profileName proxyPort=$proxyPort serverPort=$serverPort"
    Write-Output "Uranium server JAR SHA-256: $bundleHash"
} finally {
    if ($clientPid) {
        $clientProcess = Get-CimInstance Win32_Process -Filter "ProcessId = '$clientPid'" -ErrorAction SilentlyContinue
        if ($clientProcess -and $clientProcess.Name -eq 'javaw.exe' -and $clientProcess.CommandLine -and
            $clientProcess.CommandLine.Replace('/', '\').IndexOf("\instances\$profileName\", [StringComparison]::OrdinalIgnoreCase) -ge 0) {
            Stop-Process -Id $clientPid -Force -ErrorAction SilentlyContinue
            Wait-Process -Id $clientPid -ErrorAction SilentlyContinue
        }
    }
    if ($proxy -and -not $proxy.HasExited) {
        Stop-Process -Id $proxy.Id -Force
        Wait-Process -Id $proxy.Id -ErrorAction SilentlyContinue
    }
    if ($server -and -not $server.HasExited) {
        $server.StandardInput.WriteLine('stop')
        $server.StandardInput.Flush()
        if (-not $server.WaitForExit(30000)) {
            $server.Kill()
            $server.WaitForExit()
        }
    }
    if ($serverOutput) {
        $serverOutput.GetAwaiter().GetResult() | Set-Content -LiteralPath (Join-Path $runDir 'server.stdout.log')
        $serverErrors.GetAwaiter().GetResult() | Set-Content -LiteralPath (Join-Path $runDir 'server.stderr.log')
    }
    if (-not $clientLogsCopied -and $temporaryProfile -and (Test-Path -LiteralPath $temporaryProfile)) {
        $clientLogsCopied = SaveClientLogs $temporaryProfile (Join-Path $runDir 'client-logs')
    }
    if ($temporaryProfile -and (Test-Path -LiteralPath $temporaryProfile)) {
        if (-not (Test-Path -LiteralPath $instancesRoot -PathType Container)) {
            throw "Refusing to remove temporary profile because this run's instances root is missing: $instancesRoot"
        }
        $resolvedRoot = [IO.Path]::GetFullPath($instancesRoot).TrimEnd('\') + '\'
        $resolvedTarget = [IO.Path]::GetFullPath($temporaryProfile)
        if (-not $resolvedTarget.StartsWith($resolvedRoot, [StringComparison]::OrdinalIgnoreCase) -or
            (Split-Path -Leaf $resolvedTarget) -notlike 'MoonBridgePermission-*') {
            throw "Refusing to remove a Prism path outside this smoke's temporary profile: $resolvedTarget"
        }
        Remove-Item -LiteralPath $resolvedTarget -Recurse -Force
    }
    Write-Output "Runtime evidence: $runDir"
}
