param(
    [Parameter(Mandatory = $true)][string]$Java8Home,
    [Parameter(Mandatory = $true)][string]$Java25Home
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$fixtureClasses = Join-Path $repo 'build/channel-smoke/router'
$probeClasses = Join-Path $repo 'build/channel-smoke/probe'
$proxyClasspath = Join-Path $repo 'proxy-core/build/install/moonbridge/lib/*'
$backendClasspath = Join-Path $repo 'proxy-core/build/install/moonbridge/backend-client/*'
if (-not (Test-Path (Join-Path $repo 'proxy-core/build/install/moonbridge/lib'))) {
    throw 'Run gradlew :proxy-core:installDist first.'
}
New-Item -ItemType Directory -Force $fixtureClasses, $probeClasses | Out-Null
& (Join-Path $Java25Home 'bin/javac.exe') -cp $proxyClasspath -d $fixtureClasses (Join-Path $PSScriptRoot 'ChannelRouterFixture.java')
if ($LASTEXITCODE -ne 0) { throw 'Router fixture compilation failed.' }
& (Join-Path $Java8Home 'bin/javac.exe') -cp $backendClasspath -d $probeClasses (Join-Path $PSScriptRoot 'ChannelJava8Probe.java')
if ($LASTEXITCODE -ne 0) { throw 'Java 8 probe compilation failed.' }

function Start-ProbeProcess([string]$Executable, [string[]]$Arguments) {
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = $Executable
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardInput = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    # JVM paths on Windows cannot contain quotes. Arguments here are local paths/class names/numeric ports.
    $info.Arguments = ($Arguments | ForEach-Object { '"' + $_ + '"' }) -join ' '
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $info
    [void]$process.Start()
    return $process
}

$router = $null
$probe = $null
try {
    $router = Start-ProbeProcess (Join-Path $Java25Home 'bin/java.exe') @('-cp', "$fixtureClasses;$proxyClasspath", 'ChannelRouterFixture')
    $routerErrors = $router.StandardError.ReadToEndAsync()
    $readyDeadline = [DateTime]::UtcNow.AddSeconds(15)
    do {
        $ready = $router.StandardOutput.ReadLineAsync()
        $remainingMillis = [int]($readyDeadline - [DateTime]::UtcNow).TotalMilliseconds
        if ($remainingMillis -le 0 -or -not $ready.Wait($remainingMillis)) {
            throw 'Router did not become ready within 15 seconds.'
        }
        $line = $ready.Result
        if ($null -eq $line) { throw "Router exited before readiness: $($routerErrors.Result)" }
    } until ($line -match '^CHANNEL_FIXTURE_READY (\d+)$')
    $port = $Matches[1]
    Write-Output $line
    $routerOutput = $router.StandardOutput.ReadToEndAsync()
    $probe = Start-ProbeProcess (Join-Path $Java8Home 'bin/java.exe') @('-cp', "$probeClasses;$backendClasspath", 'ChannelJava8Probe', $port)
    $probeOutput = $probe.StandardOutput.ReadToEndAsync()
    $probeErrors = $probe.StandardError.ReadToEndAsync()
    if (-not $probe.WaitForExit(25000)) { throw 'Java 8 probe exceeded its 25 second deadline.' }
    Write-Output $probeOutput.Result.Trim()
    if ($probe.ExitCode -ne 0) { throw "Java 8 probe failed: $($probeErrors.Result)" }
    $router.StandardInput.WriteLine()
    $router.StandardInput.Flush()
    if (-not $router.WaitForExit(5000)) { throw 'Router did not shut down cleanly.' }
    if ($router.ExitCode -ne 0) { throw "Router failed: $($routerErrors.Result)" }
} finally {
    foreach ($ownedProcess in @($probe, $router)) {
        if ($null -ne $ownedProcess) {
            if (-not $ownedProcess.HasExited) { $ownedProcess.Kill(); [void]$ownedProcess.WaitForExit(5000) }
            $ownedProcess.Dispose()
        }
    }
}
