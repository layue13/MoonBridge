param(
    [ValidateRange(1, 32)][int]$Connections = 4,
    [ValidateRange(1, 100000)][int]$Messages = 1000,
    [ValidateRange(0, 10000)][int]$Warmup = 100,
    [ValidateRange(1, 1048576)][int]$Payload = 1024,
    [ValidateRange(1, 10)][int]$Repeats = 2
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$installLib = Join-Path $repoRoot "proxy-core\build\install\strataproxy\lib"
$classes = Join-Path $PSScriptRoot "build"

Push-Location $repoRoot
try {
    & .\gradlew.bat :proxy-core:installDist
    if ($LASTEXITCODE -ne 0) { throw "installDist failed with exit code $LASTEXITCODE" }

    New-Item -ItemType Directory -Path $classes -Force | Out-Null
    & javac -cp "$installLib\*" -d $classes (Join-Path $PSScriptRoot "ProxySessionBenchmark.java")
    if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }

    & java -cp "$classes;$installLib\*" dev.strataproxy.core.session.ProxySessionBenchmark `
        --connections $Connections --messages $Messages --warmup $Warmup --payload $Payload --repeats $Repeats
    if ($LASTEXITCODE -ne 0) { throw "benchmark failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}
