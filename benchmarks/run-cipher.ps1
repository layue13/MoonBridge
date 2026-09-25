param(
    [int]$Bytes = 4096,
    [int]$Iterations = 12000,
    [int]$Warmup = 2000,
    [int]$Rounds = 4
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
    & javac -cp "$installLib\*" -d $classes (Join-Path $PSScriptRoot "CipherBenchmark.java")
    if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }

    & java -cp "$classes;$installLib\*" dev.strataproxy.core.session.CipherBenchmark `
        $Bytes $Iterations $Warmup $Rounds
    if ($LASTEXITCODE -ne 0) { throw "benchmark failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}
