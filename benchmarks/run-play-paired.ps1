param(
    [Parameter(Mandatory)][string]$BaselineRepository,
    [ValidateRange(1, 10)][int]$Pairs = 5,
    [ValidateRange(1, 100000)][int]$Messages = 100000,
    [ValidateRange(0, 10000)][int]$Warmup = 10000,
    [ValidateRange(5, 1048576)][int]$Payload = 4096,
    [string]$LogPath = (Join-Path $env:TEMP ("moonbridge-play-paired-{0}.txt" -f (Get-Date -Format "yyyyMMdd-HHmmss")))
)

$ErrorActionPreference = "Stop"
$candidateRepository = (Resolve-Path -LiteralPath (Split-Path -Parent $PSScriptRoot)).Path
$baselineRepository = (Resolve-Path -LiteralPath $BaselineRepository).Path
if (-not (Test-Path -LiteralPath (Join-Path $baselineRepository "benchmarks\run-proxy-session.ps1") -PathType Leaf)) {
    throw "baseline repository does not contain the PLAY benchmark runner: $baselineRepository"
}

$cases = @(
    @{ Window = 1; Mode = "initial" },
    @{ Window = 1; Mode = "post-transfer" },
    @{ Window = 16; Mode = "initial" },
    @{ Window = 16; Mode = "post-transfer" }
)

foreach ($case in $cases) {
    for ($pair = 1; $pair -le $Pairs; $pair++) {
        $order = if (($pair % 2) -eq 1) { @("baseline", "candidate") } else { @("candidate", "baseline") }
        foreach ($side in $order) {
            $repository = if ($side -eq "baseline") { $baselineRepository } else { $candidateRepository }
            Add-Content -LiteralPath $LogPath -Value "CASE=window$($case.Window)-$($case.Mode) PAIR=$pair SIDE=$side"
            Push-Location $repository
            try {
                & .\benchmarks\run-proxy-session.ps1 -Connections 4 -Messages $Messages -Warmup $Warmup `
                    -Payload $Payload -Repeats 5 -Window $case.Window -Mode $case.Mode *>> $LogPath
                if ($LASTEXITCODE -ne 0) {
                    throw "PLAY benchmark failed: case=window$($case.Window)-$($case.Mode) pair=$pair side=$side exit=$LASTEXITCODE; see $LogPath"
                }
            } finally {
                Pop-Location
            }
        }
    }
}

Write-Host "Paired PLAY output: $LogPath"
