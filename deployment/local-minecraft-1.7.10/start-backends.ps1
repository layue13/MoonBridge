param(
    [switch] $AcceptEula,
    [string] $JavaExe = "java",
    [string] $Xms = "256M",
    [string] $Xmx = "768M"
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Bin = Join-Path $Root "bin"
$Jar = Join-Path $Root "bin\minecraft_server.1.7.10.jar"
$ExpectedServerSha1 = "952438ac4e01b4d115c5fc38f891710c4941df29"

function Get-ServerJar {
    if (Test-Path -LiteralPath $Jar) {
        $ActualSha1 = (Get-FileHash -LiteralPath $Jar -Algorithm SHA1).Hash.ToLowerInvariant()
        if ($ActualSha1 -ne $ExpectedServerSha1) {
            throw "Server jar SHA-1 mismatch: expected $ExpectedServerSha1, got $ActualSha1"
        }
        return
    }

    New-Item -ItemType Directory -Path $Bin -Force | Out-Null
    $Manifest = Invoke-RestMethod -Uri "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    $Version = $Manifest.versions | Where-Object { $_.id -eq "1.7.10" } | Select-Object -First 1
    if (-not $Version) {
        throw "Could not find Minecraft 1.7.10 in Mojang version manifest"
    }
    $VersionMetadata = Invoke-RestMethod -Uri $Version.url
    $ServerUrl = $VersionMetadata.downloads.server.url
    if (-not $ServerUrl) {
        throw "Minecraft 1.7.10 manifest does not include a server download"
    }

    $TempJar = "$Jar.download"
    Invoke-WebRequest -Uri $ServerUrl -OutFile $TempJar
    $ActualSha1 = (Get-FileHash -LiteralPath $TempJar -Algorithm SHA1).Hash.ToLowerInvariant()
    if ($ActualSha1 -ne $ExpectedServerSha1) {
        Remove-Item -LiteralPath $TempJar -Force
        throw "Downloaded server jar SHA-1 mismatch: expected $ExpectedServerSha1, got $ActualSha1"
    }
    Move-Item -LiteralPath $TempJar -Destination $Jar -Force
    Write-Host "Downloaded Minecraft 1.7.10 server jar to $Jar"
}

function Ensure-BackendConfig {
    param(
        [string] $Dir,
        [string] $Name,
        [int] $Port
    )

    New-Item -ItemType Directory -Path $Dir -Force | Out-Null
    $ServerProperties = Join-Path $Dir "server.properties"
    if (Test-Path -LiteralPath $ServerProperties) {
        return
    }

    $Properties = @(
        "server-ip=127.0.0.1",
        "server-port=$Port",
        "online-mode=false",
        "enable-query=false",
        "enable-rcon=false",
        "motd=StrataProxy $Name",
        "max-players=100",
        "spawn-protection=0",
        "view-distance=6",
        "difficulty=1",
        "gamemode=0",
        "white-list=false",
        "generate-structures=false"
    )
    Set-Content -LiteralPath $ServerProperties -Value $Properties -Encoding ASCII
}

$Backends = @(
    @{ Name = "backend-lobby-1"; Port = 25565 },
    @{ Name = "backend-survival-1"; Port = 25566 },
    @{ Name = "backend-minigame-1"; Port = 25567 }
)

Get-ServerJar

foreach ($Backend in $Backends) {
    $Dir = Join-Path $Root $Backend.Name
    Ensure-BackendConfig -Dir $Dir -Name $Backend.Name -Port $Backend.Port
    $Eula = Join-Path $Dir "eula.txt"
    $PidFile = Join-Path $Dir "server.pid"
    $OutLog = Join-Path $Dir "stdout.log"
    $ErrLog = Join-Path $Dir "stderr.log"

    if (-not (Test-Path -LiteralPath $Eula)) {
        Set-Content -LiteralPath $Eula -Value "eula=false" -Encoding ASCII
    }
    if ($AcceptEula) {
        Set-Content -LiteralPath $Eula -Value "eula=true" -Encoding ASCII
    }
    if (-not (Select-String -LiteralPath $Eula -Pattern "^eula=true$" -Quiet)) {
        throw "EULA is not accepted for $($Backend.Name). Read Minecraft EULA, then rerun with -AcceptEula if you accept it."
    }

    if (Test-Path -LiteralPath $PidFile) {
        $ExistingPid = (Get-Content -LiteralPath $PidFile -Raw).Trim()
        if ($ExistingPid -and (Get-Process -Id ([int] $ExistingPid) -ErrorAction SilentlyContinue)) {
            Write-Host "$($Backend.Name) already running on port $($Backend.Port), pid $ExistingPid"
            continue
        }
    }

    $Args = @("-Xms$Xms", "-Xmx$Xmx", "-jar", $Jar, "nogui")
    $Process = Start-Process -FilePath $JavaExe `
        -ArgumentList $Args `
        -WorkingDirectory $Dir `
        -RedirectStandardOutput $OutLog `
        -RedirectStandardError $ErrLog `
        -WindowStyle Hidden `
        -PassThru
    Set-Content -LiteralPath $PidFile -Value $Process.Id -Encoding ASCII
    Write-Host "Started $($Backend.Name) on 127.0.0.1:$($Backend.Port), pid $($Process.Id)"
}
