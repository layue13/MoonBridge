# Command Reference

This page groups commands by what an operator is trying to do. Build the CLIs first:

```powershell
.\gradlew.bat --no-daemon :proxy-admin-cli:installDist :proxy-query:installDist
```

Convenience variables:

```powershell
$admin='.\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat'
$query='.\proxy-query\build\install\strataproxy-query\bin\strataproxy-query.bat'
```

If the Admin API has a bearer token, add:

```powershell
--token "$env:STRATAPROXY_ADMIN_TOKEN"
```

## Health And Overview

```powershell
& $admin --base-url http://127.0.0.1:8080 health
& $admin --base-url http://127.0.0.1:8080 ready
& $admin --base-url http://127.0.0.1:8080 overview
& $admin --base-url http://127.0.0.1:8080 diagnostics
```

Use `health` for process liveness and `ready` for whether a backend can receive players.

SLO gate for automation:

```powershell
& $admin --base-url http://127.0.0.1:8080 slo --require-ready --max-event-loop-delay-ms 5 --max-rejected 0 --max-anomalies 0
```

## Backend Servers

List:

```powershell
& $admin --base-url http://127.0.0.1:8080 servers list
```

Get one server:

```powershell
& $admin --base-url http://127.0.0.1:8080 servers get survival-1
```

Register or replace:

```powershell
& $admin --base-url http://127.0.0.1:8080 servers register `
  --name survival-1 `
  --address 10.0.0.12:25565 `
  --tag survival,forge `
  --capability large-payload,modern-forwarding `
  --protocol-range any `
  --weight 100 `
  --soft-capacity 180 `
  --hard-capacity 220 `
  --metadata host=survival.example.net,group=survival
```

Change weight or metadata for a canary:

```powershell
& $admin --base-url http://127.0.0.1:8080 servers update survival-1 --weight 20 --metadata group=survival-canary
```

Drain for maintenance:

```powershell
& $admin --base-url http://127.0.0.1:8080 servers drain survival-1
& $admin --base-url http://127.0.0.1:8080 servers undrain survival-1
```

Remove:

```powershell
& $admin --base-url http://127.0.0.1:8080 servers remove survival-1
```

## Routing

Preview a route decision without connecting a player:

```powershell
& $admin --base-url http://127.0.0.1:8080 routes preview `
  --route survival.example.net `
  --protocol-version 763 `
  --remote-address 127.0.0.1:50000 `
  --tag survival `
  --capability large-payload
```

Use this when a player would be rejected or sent to an unexpected backend.

## Metrics And Diagnostics

```powershell
& $admin --base-url http://127.0.0.1:8080 metrics
& $admin --base-url http://127.0.0.1:8080 native
& $admin --base-url http://127.0.0.1:8080 compression
& $admin --base-url http://127.0.0.1:8080 packets
& $admin --base-url http://127.0.0.1:8080 mod-payloads --samples
& $admin --base-url http://127.0.0.1:8080 players
& $admin --base-url http://127.0.0.1:8080 anomalies --samples
& $admin --base-url http://127.0.0.1:8080 backpressure
```

Transfer an active player to another registered backend:

```powershell
& $admin --base-url http://127.0.0.1:8080 players transfer Steve survival-2
```

The transfer connects the target backend first, replays the player's backend login frames, consumes the target backend's login success, swaps the relay, and then closes the old backend connection.

Players can also use proxy-side commands in game:

```text
/server survival-2
/hub
/servers
```

These commands are intercepted by StrataProxy and are not forwarded to the current backend.

## Payload Captures

Captures are opt-in, bounded, and expire automatically.

```powershell
& $admin --base-url http://127.0.0.1:8080 captures start `
  --id survival-capture `
  --server survival-1 `
  --direction frontend_to_backend `
  --max-samples 64 `
  --max-bytes 256 `
  --duration-ms 30000

& $admin --base-url http://127.0.0.1:8080 captures get survival-capture
& $admin --base-url http://127.0.0.1:8080 captures stop survival-capture
```

Treat capture data as sensitive.

## Zstd Samples

Collect:

```powershell
& $admin --base-url http://127.0.0.1:8080 zstd-samples start `
  --id registry-train `
  --server survival-1 `
  --direction backend_to_frontend `
  --max-samples 5000 `
  --max-bytes 32768 `
  --duration-ms 300000
```

Export:

```powershell
& $admin --base-url http://127.0.0.1:8080 zstd-samples export registry-train --out .\samples\registry
```

Train:

```powershell
& $admin zstd-samples train --in .\samples\registry --out .\data\zstd\registry.zdict --max-dict 16384 --level 1
```

Stop:

```powershell
& $admin --base-url http://127.0.0.1:8080 zstd-samples stop registry-train
```

See [Zstd Compression Tuning](compression-zstd.md) before rolling this out to users.

## Query And Load Probes

Status ping:

```powershell
& $query --host 127.0.0.1 --port 25577 status --virtual-host play.example.net
```

Idle connection probe:

```powershell
& $query --host 127.0.0.1 --port 25577 idle-load --connections 1000 --hold-ms 10000 --parallelism 256 --min-connected 1000 --min-alive 1000 --max-failed 0
```

Handshake route probe:

```powershell
& $query --host 127.0.0.1 --port 25577 handshake-load --connections 2000 --virtual-host play.example.net --parallelism 256 --min-handshaken 2000 --min-alive 2000 --max-failed 0
```

Traffic relay probe:

```powershell
& $query --host 127.0.0.1 --port 25577 traffic-load --connections 2000 --virtual-host play.example.net --login-start --player-template load%05d --packets-per-connection 200 --payload-bytes 64 --parallelism 256 --min-handshaken 2000 --min-packets-sent 400000 --max-failed 0
```

Slow backend for backpressure tests:

```powershell
& $query slow-sink --bind-host 127.0.0.1 --port 25565 --duration-ms 30000 --read-chunk-bytes 1 --read-delay-ms 100
```
