param(
    [ValidateRange(1, 10)][int]$Repeats = 5,
    [switch]$RecordJfr,
    [switch]$OnlyNoSubscriber,
    [string]$RuntimeLibDirectory
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$installLib = Join-Path $repoRoot "proxy-core\build\install\moonbridge\lib"
$classes = Join-Path $PSScriptRoot "build"

Push-Location $repoRoot
try {
    & .\gradlew.bat :proxy-core:installDist
    if ($LASTEXITCODE -ne 0) { throw "installDist failed with exit code $LASTEXITCODE" }

    New-Item -ItemType Directory -Path $classes -Force | Out-Null
    & javac -cp "$installLib\*" -d $classes `
        (Join-Path $PSScriptRoot "TransferPathBenchmark.java") `
        (Join-Path $PSScriptRoot "TransferParticipantPlugin.java")
    if ($LASTEXITCODE -ne 0) { throw "javac failed with exit code $LASTEXITCODE" }

    $runtimeLib = $installLib
    if ($RuntimeLibDirectory) {
        $runtimeLib = (Resolve-Path -LiteralPath $RuntimeLibDirectory).Path
        if (-not (Test-Path -LiteralPath $runtimeLib -PathType Container)) {
            throw "runtime library directory does not exist: $runtimeLib"
        }
    }
    $javaArgs = @()
    if ($RecordJfr) {
        $recording = Join-Path $env:TEMP ("moonbridge-transfer-{0}.jfr" -f (Get-Date -Format "yyyyMMdd-HHmmss"))
        $javaArgs += "-XX:StartFlightRecording=filename=$recording,settings=profile,dumponexit=true"
        Write-Host "JFR profile recording: $recording"
    }
    $javaArgs += @("-cp", "$classes;$runtimeLib\*", "dev.moonbridge.core.session.TransferPathBenchmark", "--repeats", "$Repeats")
    if ($OnlyNoSubscriber) { $javaArgs += "--only-no-subscriber" }
    & java @javaArgs
    if ($LASTEXITCODE -ne 0) { throw "benchmark failed with exit code $LASTEXITCODE" }
} finally {
    Pop-Location
}
