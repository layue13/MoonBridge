param(
    [Parameter(Mandatory)][string]$BaselineLibDirectory,
    [ValidateRange(1, 10)][int]$Pairs = 5,
    [string]$LogPath = (Join-Path $env:TEMP ("moonbridge-transfer-paired-{0}.txt" -f (Get-Date -Format "yyyyMMdd-HHmmss")))
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$candidateLib = Join-Path $repoRoot "proxy-core\build\install\moonbridge\lib"
$baselineLib = (Resolve-Path -LiteralPath $BaselineLibDirectory).Path
if (-not (Test-Path -LiteralPath $baselineLib -PathType Container)) {
    throw "baseline library directory does not exist: $baselineLib"
}

Push-Location $repoRoot
try {
    for ($pair = 1; $pair -le $Pairs; $pair++) {
        $order = if (($pair % 2) -eq 1) { @("baseline", "candidate") } else { @("candidate", "baseline") }
        foreach ($side in $order) {
            $runtimeLib = if ($side -eq "baseline") { $baselineLib } else { $candidateLib }
            Add-Content -LiteralPath $LogPath -Value "PAIR=$pair SIDE=$side"
            & (Join-Path $PSScriptRoot "run-transfer-path.ps1") -Repeats 5 -OnlyNoSubscriber `
                -RuntimeLibDirectory $runtimeLib *>> $LogPath
            if ($LASTEXITCODE -ne 0) {
                throw "transfer benchmark failed: pair=$pair side=$side exit=$LASTEXITCODE; see $LogPath"
            }
        }
    }
} finally {
    Pop-Location
}

Write-Host "Paired transfer output: $LogPath"
