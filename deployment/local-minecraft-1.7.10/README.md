# Local Minecraft 1.7.10 Backend Pool

This folder contains scripts and config for a local three-backend Minecraft 1.7.10 pool for StrataProxy testing.

## Backends

| Name | Address | Route host | Tags |
| --- | --- | --- | --- |
| lobby-1 | 127.0.0.1:25565 | lobby.local | lobby, mc-1.7.10 |
| survival-1 | 127.0.0.1:25566 | survival.local | survival, mc-1.7.10 |
| minigame-1 | 127.0.0.1:25567 | minigame.local | minigame, mc-1.7.10 |

The server jar is downloaded on demand from Mojang version metadata into `bin/minecraft_server.1.7.10.jar`. The script verifies this SHA-1:

```text
952438ac4e01b4d115c5fc38f891710c4941df29
```

## Start

Read and accept the Minecraft EULA before starting the servers. If you accept it, run:

```powershell
.\deployment\local-minecraft-1.7.10\start-backends.ps1 -AcceptEula
```

Start StrataProxy with the local 1.7.10 config:

```powershell
.\deployment\local-minecraft-1.7.10\start-proxy.ps1
```

Connect a Minecraft 1.7.10 client to:

```text
127.0.0.1:25577
```

## Check and stop

```powershell
.\deployment\local-minecraft-1.7.10\status.ps1
.\deployment\local-minecraft-1.7.10\stop-backends.ps1
```

Backend stdout/stderr logs are written inside each backend directory.

Generated runtime files are intentionally ignored by Git:

- `bin/`
- `backend-*/`
- `*.pid`
- `*.log`
