param(
    [int]$Connections = 4,
    [int]$Messages = 1000,
    [int]$Warmup = 100,
    [int]$Payload = 1024
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
    & javac -cp "$installLib\*" -d $classes (Join-Path $PSScriptRoot "RawRelayBenchmark.java")
    if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }

    & java -cp "$classes;$installLib\*" RawRelayBenchmark `
        --connections $Connections --messages $Messages --warmup $Warmup --payload $Payload
    if ($LASTEXITCODE -ne 0) { throw "benchmark failed with exit code $LASTEXITCODE" }
}
finally {
    Pop-Location
}
