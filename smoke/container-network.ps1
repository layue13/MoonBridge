#Requires -Version 7.0
param()

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$install = Join-Path $repoRoot 'proxy-core/build/install/strataproxy'
$work = Join-Path $repoRoot 'build/container-network-smoke'
$pythonImage = 'docker.gitea.com/runner-images@sha256:fd911d7417bfbf0f454530e447da95b58001e1df41bbc5e1a8dd35d432575aae'
$javaImage = 'eclipse-temurin@sha256:97014c4b396021f9ddb7d592a7dbedb0c4e4215c29e03dc01c393558aefb71c2'
$suffix = [guid]::NewGuid().ToString('N').Substring(0, 10)
$network = "strataproxy-smoke-$suffix"
$createdNetwork = $false
$secret = [Convert]::ToHexString([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))

function InvokeDocker([string[]]$arguments) {
    $output = & docker @arguments
    if ($LASTEXITCODE -ne 0) {
        throw "docker $($arguments[0]) failed with exit code $LASTEXITCODE"
    }
    return $output
}

function WaitBackend([string]$name) {
    for ($attempt = 0; $attempt -lt 50; $attempt++) {
        $log = & docker logs $name 2>&1
        if ($log -match 'BACKEND_READY') { return }
        $running = & docker inspect -f '{{.State.Running}}' $name
        if ($running -ne 'true') { throw "Fake backend exited before listening: $log" }
        Start-Sleep -Milliseconds 100
    }
    throw "Fake backend $name did not start listening"
}

function WaitBackendExit([string]$name) {
    for ($attempt = 0; $attempt -lt 50; $attempt++) {
        $running = & docker inspect -f '{{.State.Running}}' $name
        if ($running -eq 'false') {
            $code = & docker inspect -f '{{.State.ExitCode}}' $name
            if ($code -ne '0') { throw "Fake backend exited with code $code`n$(& docker logs $name 2>&1)" }
            return
        }
        Start-Sleep -Milliseconds 100
    }
    throw "Fake backend $name did not finish after the client probe"
}

Push-Location $repoRoot
try {
    & .\gradlew.bat :proxy-core:installDist --no-daemon
    if ($LASTEXITCODE -ne 0) { throw "installDist failed with exit code $LASTEXITCODE" }
    New-Item -ItemType Directory -Path $work -Force | Out-Null
    InvokeDocker @('network', 'create', $network) | Out-Null
    $createdNetwork = $true

    foreach ($mode in @('static', 'dns', 'agent')) {
        $backend = "sp-backend-$suffix"
        $proxy = "sp-proxy-$suffix"
        $config = Join-Path $work "$suffix-$mode.yml"
        $probes = if ($mode -eq 'agent') { 2 } else { 1 }
        $backendConfig = if ($mode -eq 'static') {
            @"
backends:
  - name: remote
    address: "${backend}:25565"
"@
        } else { 'backends: []' }
        $enabled = switch ($mode) {
            'static' { '  enabled: {}' }
            'dns' {
                @"
  enabled:
    dev.strataproxy.plugins.dns.DnsDiscoveryPlugin:
      host: "$backend"
      port: "25565"
      refreshSeconds: "1"
"@
            }
            'agent' {
                @"
  enabled:
    dev.strataproxy.plugins.agent.AgentDiscoveryPlugin:
      host: "0.0.0.0"
      port: "28080"
      secret: "$secret"
"@
            }
        }
        @"
listen: "0.0.0.0:25577"
authentication: OFFLINE
plugins:
  directory: "/opt/strataproxy/plugins"
$enabled
$backendConfig
"@ | Set-Content -LiteralPath $config -Encoding utf8

        try {
            InvokeDocker @('run', '-d', '--network', $network, '--name', $backend,
                '-v', "${repoRoot}:/work:ro", '--entrypoint', 'python3', $pythonImage,
                '/work/smoke/container-network.py', 'backend', "$probes") | Out-Null
            WaitBackend $backend
            InvokeDocker @('run', '-d', '--network', $network, '--name', $proxy,
                '-v', "${install}:/opt/strataproxy:ro", '-v', "${config}:/opt/strataproxy.yml:ro",
                '--entrypoint', 'java', $javaImage, '-cp', '/opt/strataproxy/lib/*',
                'dev.strataproxy.app.ProxyMain', '--config', '/opt/strataproxy.yml') | Out-Null
            $clientArgs = @('run', '--rm', '--network', $network, '-v', "${repoRoot}:/work:ro")
            if ($mode -eq 'agent') { $clientArgs += @('-e', "STRATAPROXY_AGENT_SECRET=$secret") }
            $clientArgs += @('--entrypoint', 'python3', $pythonImage,
                '/work/smoke/container-network.py', 'client', $mode, $proxy, $backend)
            InvokeDocker $clientArgs | Write-Output
            WaitBackendExit $backend
            Write-Output "$mode`: cross-container DNS/TCP login and PLAY probe passed"
        } catch {
            Write-Warning "Proxy log ($mode): $(& docker logs $proxy 2>&1 | Select-Object -Last 20)"
            Write-Warning "Backend log ($mode): $(& docker logs $backend 2>&1 | Select-Object -Last 20)"
            throw
        } finally {
            & docker rm -f $proxy $backend 2>$null | Out-Null
            Remove-Item -LiteralPath $config -ErrorAction SilentlyContinue
        }
    }
} finally {
    if ($createdNetwork) { & docker network rm $network 2>$null | Out-Null }
    $secret = $null
    Pop-Location
}
