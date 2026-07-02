param(
    [string] $JavaHome = "C:\Program Files\Zulu\zulu-25"
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Repo = Split-Path -Parent (Split-Path -Parent $Root)
$Config = Join-Path $Root "strataproxy-1.7.10-local.yml"
$Proxy = Join-Path $Repo "proxy-app\build\install\strataproxy\bin\strataproxy.bat"

if (-not (Test-Path -LiteralPath $Proxy)) {
    Push-Location $Repo
    try {
        $env:JAVA_HOME = $JavaHome
        & .\gradlew.bat --no-daemon :proxy-app:installDist
    } finally {
        Pop-Location
    }
}

& $Proxy $Config
