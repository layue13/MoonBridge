# Quick Start

This guide gets one StrataProxy process running in front of one Minecraft backend. It is intentionally short; use the linked reference pages after the first successful run.

## 1. Requirements

- JDK 25.
- One backend Minecraft server, for example `127.0.0.1:25565`.
- A free proxy listener port, default `25577`.
- PowerShell examples below assume Windows. On Linux, use `./gradlew` and the shell scripts under `bin/`.

Set Java 25 if it is not already first on `PATH`:

```powershell
$env:JAVA_HOME='C:\Program Files\Zulu\zulu-25'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
java -version
```

## 2. Build

```powershell
.\gradlew.bat --no-daemon :proxy-app:installDist :proxy-admin-cli:installDist :proxy-query:installDist
```

The runnable proxy is now under:

```text
proxy-app\build\install\strataproxy\
```

## 3. Create A Config

Copy the packaged example to a working file:

```powershell
Copy-Item .\proxy-app\src\main\resources\config\strataproxy.yml .\strataproxy.yml
```

For the first run, edit only these fields:

```yaml
network:
  bind: "0.0.0.0:25577"

admin:
  enabled: true
  bind: "127.0.0.1:8080"
  bearerToken: ""

servers:
  - name: "lobby-1"
    address: "127.0.0.1:25565"
    tags: ["lobby"]
    capabilities: ["modern-forwarding"]
    protocolRange: "any"
    weight: 100
    softCapacity: 500
    hardCapacity: 600
    drainMode: false
    metadata:
      group: "lobby"
      host: "localhost"
```

Keep the Admin API on `127.0.0.1` if `bearerToken` is empty.

## 4. Validate

```powershell
.\proxy-app\build\install\strataproxy\bin\strataproxy.bat --validate-config .\strataproxy.yml
```

Fix any reported error before starting the proxy.

## 5. Start

```powershell
.\proxy-app\build\install\strataproxy\bin\strataproxy.bat .\strataproxy.yml
```

Players connect to the proxy port, not directly to the backend:

```text
127.0.0.1:25577
```

## 6. Check It

In another terminal:

```powershell
$admin='.\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat'
& $admin --base-url http://127.0.0.1:8080 health
& $admin --base-url http://127.0.0.1:8080 ready
& $admin --base-url http://127.0.0.1:8080 overview
& $admin --base-url http://127.0.0.1:8080 servers list
```

Run a Minecraft status ping through the proxy:

```powershell
.\proxy-query\build\install\strataproxy-query\bin\strataproxy-query.bat --host 127.0.0.1 --port 25577 status --virtual-host localhost
```

## 7. Next Steps

- To add servers without restart, use [Command Reference: Backend Servers](commands.md#backend-servers).
- To understand YAML fields, read [Configuration Guide](configuration.md).
- To deploy on Linux or containers, read [Deployment Notes](../deployment/README.md).
- To tune Zstd for modded traffic, read [Zstd Compression Tuning](compression-zstd.md).
