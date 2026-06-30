# StrataProxy Operations Manual

This manual is the operator guide for installing, configuring, running, observing, and troubleshooting StrataProxy.

StrataProxy is a Java 25 Minecraft proxy for large modded networks. It routes by server capability, tag, protocol range, health, load, drain state, and metadata. It is not a BungeeCord or HexaCord compatibility rewrite, although it supports selected backend forwarding modes.

## 1. Prerequisites

- JDK 25.
- A backend Minecraft server reachable from the proxy host.
- Open listener port for players, default `25577`.
- Admin API port, default loopback `127.0.0.1:8080`.
- For Linux production: high file descriptor limit, native transport enabled, and enough heap plus direct memory.

Windows local build example:

```powershell
$env:JAVA_HOME='C:\Program Files\Zulu\zulu-25'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat --no-daemon check
```

## 2. Build And Install

Build the proxy application:

```powershell
.\gradlew.bat --no-daemon :proxy-app:installDist
```

Build operator CLIs:

```powershell
.\gradlew.bat --no-daemon :proxy-admin-cli:installDist
.\gradlew.bat --no-daemon :proxy-query:installDist
```

Build a full release bundle:

```powershell
.\gradlew.bat --no-daemon release
Get-ChildItem .\build\release
```

## 3. Configuration Files

Main examples:

- Development config: `proxy-app/src/main/resources/config/strataproxy.yml`
- Production-oriented config: `proxy-app/src/main/resources/config/strataproxy-production.yml`

The installed distribution includes configs under `config/`.

Validate before starting:

```powershell
.\proxy-app\build\install\strataproxy\bin\strataproxy.bat --validate-config C:\path\to\strataproxy.yml
```

String values support environment placeholders:

```yaml
admin:
  bearerToken: "${STRATAPROXY_ADMIN_TOKEN}"

forwarding:
  secret: "${STRATAPROXY_FORWARDING_SECRET}"
```

Use `${NAME:default}` when a safe default exists.

## 4. Minimal Working Config

```yaml
network:
  bind: "0.0.0.0:25577"
  workerThreads: 0
  nativeTransport: true
  maxFrameBytes: "8mb"
  connectTimeoutMillis: 5000
  writeBufferLow: "4mb"
  writeBufferHigh: "16mb"
  maxConnections: 10000
  maxConnectionsPerAddress: 200
  maxNewConnectionsPerSecond: 0
  maxNewConnectionsPerAddressPerSecond: 0
  initialHandshakeTimeoutMillis: 5000
  proxyProtocol: false

registry:
  staticServers: true
  persistenceEnabled: true
  persistencePath: "data/registry.json"
  healthCheckEnabled: true
  healthCheckInterval: "5s"
  healthCheckTimeout: "2s"
  healthCheckMode: "minecraft-status"

compression:
  mode: adaptive
  codec: zlib
  minThreshold: 256
  maxThreshold: 8192
  cpuGuard: 0.75
  rewriteEnabled: false
  rewriteMaxEventLoopDelayMillis: 25
  zstdLevel: 1
  zstdDictionaryPath: ""

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
      host: "localhost"
      group: "lobby"
```

Keep Admin API on loopback if `bearerToken` is blank. If binding Admin API outside loopback, configure a strong token and preferably TLS or mTLS.

## 5. Start And Stop

Start with default packaged config:

```powershell
.\proxy-app\build\install\strataproxy\bin\strataproxy.bat
```

Start with an explicit config:

```powershell
.\proxy-app\build\install\strataproxy\bin\strataproxy.bat C:\path\to\strataproxy.yml
```

Stop with Ctrl+C for local testing, or with the service manager in production. Shutdown closes the listener, Admin API, health checker, load reporter, and Netty event loops.

## 6. Admin CLI

Common commands:

```powershell
$admin='.\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat'
& $admin --base-url http://127.0.0.1:8080 health
& $admin --base-url http://127.0.0.1:8080 ready
& $admin --base-url http://127.0.0.1:8080 overview
& $admin --base-url http://127.0.0.1:8080 native
& $admin --base-url http://127.0.0.1:8080 compression
& $admin --base-url http://127.0.0.1:8080 packets
& $admin --base-url http://127.0.0.1:8080 mod-payloads --samples
& $admin --base-url http://127.0.0.1:8080 players
& $admin --base-url http://127.0.0.1:8080 anomalies --samples
& $admin --base-url http://127.0.0.1:8080 backpressure
& $admin --base-url http://127.0.0.1:8080 diagnostics
```

With bearer token:

```powershell
& $admin --base-url http://127.0.0.1:8080 --token "$env:STRATAPROXY_ADMIN_TOKEN" overview
```

## 7. Backend Registry And Routing

List servers:

```powershell
& $admin --base-url http://127.0.0.1:8080 servers list
```

Register or replace a backend:

```powershell
& $admin --base-url http://127.0.0.1:8080 servers register `
  --name survival-1 `
  --address 10.0.0.12:25565 `
  --tag survival,forge `
  --capability large-payload,modern-forwarding `
  --protocol-range 763 `
  --soft-capacity 180 `
  --hard-capacity 220 `
  --metadata host=survival.example.net,group=survival
```

Gray rollout:

```powershell
& $admin --base-url http://127.0.0.1:8080 servers update survival-1 --weight 20 --metadata group=survival-canary
```

Drain for maintenance:

```powershell
& $admin --base-url http://127.0.0.1:8080 servers drain survival-1
& $admin --base-url http://127.0.0.1:8080 servers undrain survival-1
```

Preview routing:

```powershell
& $admin --base-url http://127.0.0.1:8080 routes preview `
  --route survival.example.net `
  --protocol-version 763 `
  --remote-address 127.0.0.1:50000 `
  --tag survival `
  --capability large-payload
```

## 8. Backend Identity Forwarding

Supported modes:

- `none`
- `velocity-modern`
- `bungee-legacy`
- `bungee-guard`

Velocity modern example:

```yaml
forwarding:
  mode: "velocity-modern"
  secret: "${STRATAPROXY_FORWARDING_SECRET}"
```

Use `velocity-modern` for Paper-compatible backends configured with Velocity forwarding. Use `bungee-legacy` or `bungee-guard` only for backends expecting BungeeCord-style IP forwarding.

## 9. Online Mode And Authentication

```yaml
auth:
  onlineMode: true
  rsaKeyBits: 1024
  verifyTokenBytes: 4
  sessionVerification: true
  sessionVerificationTimeout: "5s"
```

`onlineMode` enables Minecraft protocol encryption at the proxy. `sessionVerification` calls Mojang session verification after Encryption Response. Backend identity forwarding is still configured separately through `forwarding`.

## 10. Compression

Default compression config:

```yaml
compression:
  mode: adaptive
  codec: zlib
  minThreshold: 256
  maxThreshold: 8192
  cpuGuard: 0.75
  rewriteEnabled: false
  rewriteMaxEventLoopDelayMillis: 25
  zstdLevel: 1
  zstdDictionaryPath: ""
```

Keep `codec: zlib` for vanilla-compatible traffic.

Use `rewriteEnabled: true` only when you intentionally want StrataProxy to rewrite safe bounded compressed frames after backend compression negotiation.

Experimental Zstd:

```yaml
compression:
  codec: zstd
  minThreshold: 512
  zstdLevel: 1
  zstdDictionaryPath: "data/zstd/registry.zdict"
```

Zstd requires a matching modded client/backend negotiation path. See [Zstd Compression Tuning](compression-zstd.md) for sample collection, dictionary training, expected gains, and rollout checks.

## 11. Zstd Sample Collection Quick Path

```powershell
$admin='.\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat'

& $admin --base-url http://127.0.0.1:8080 zstd-samples start `
  --id registry-train `
  --server survival-1 `
  --direction backend_to_frontend `
  --max-samples 5000 `
  --max-bytes 32768 `
  --duration-ms 300000

& $admin --base-url http://127.0.0.1:8080 zstd-samples export registry-train --out .\samples\registry

& $admin zstd-samples train `
  --in .\samples\registry `
  --out .\data\zstd\registry.zdict `
  --max-dict 16384 `
  --level 1

& $admin --base-url http://127.0.0.1:8080 zstd-samples stop registry-train
```

The export command writes `.bin` sample files and `manifest.json`. Treat exported samples as potentially sensitive player/modpack data.

## 12. Observability

Admin API endpoints:

- `GET /healthz`
- `GET /readyz`
- `GET /overview`
- `GET /metrics`
- `GET /diagnostic-report`
- `GET /compression-report`
- `GET /packet-traffic`
- `GET /packet-anomalies`
- `GET /custom-payloads`
- `GET /player-sessions`
- `GET /payload-captures`

Prometheus and Grafana assets are under `deployment/observability/`.

Important signals:

- readiness status
- active connections
- rejected connections by reason
- event loop delay
- heap and direct memory
- backend health and drain state
- compression ratio and saved bytes
- packet anomalies
- relay backpressure

## 13. Payload Diagnostics

Metadata only:

```powershell
& $admin --base-url http://127.0.0.1:8080 mod-payloads --samples
```

Short prefix capture:

```powershell
& $admin --base-url http://127.0.0.1:8080 captures start `
  --id survival-capture `
  --server survival-1 `
  --direction frontend_to_backend `
  --max-samples 64 `
  --max-bytes 256 `
  --duration-ms 30000

& $admin --base-url http://127.0.0.1:8080 captures get survival-capture
```

Prefix captures are opt-in, bounded, and expire automatically.

## 14. Query And Load Probes

Build:

```powershell
.\gradlew.bat --no-daemon :proxy-query:installDist
```

Status:

```powershell
.\proxy-query\build\install\strataproxy-query\bin\strataproxy-query.bat --host 127.0.0.1 --port 25577 status --virtual-host play.example.net
```

Traffic load:

```powershell
.\proxy-query\build\install\strataproxy-query\bin\strataproxy-query.bat --host 127.0.0.1 --port 25577 traffic-load --connections 2000 --virtual-host play.example.net --login-start --player-template load%05d --packets-per-connection 200 --payload-bytes 64 --parallelism 256 --min-handshaken 2000 --min-packets-sent 400000 --max-failed 0
```

Echo latency requires an echo backend:

```powershell
.\proxy-query\build\install\strataproxy-query\bin\strataproxy-query.bat slow-sink --bind-host 127.0.0.1 --port 25565 --duration-ms 900000 --read-chunk-bytes 8192 --read-delay-ms 0 --echo
```

Acceptance profiles:

```powershell
python deployment\performance\run_profile.py acceptance-linux-native-java25 --query-bin .\proxy-query\build\install\strataproxy-query\bin\strataproxy-query.bat --admin-bin .\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat --admin-url http://127.0.0.1:8080
```

## 15. Production Deployment

Use assets under `deployment/`:

- `systemd/strataproxy.service`
- `systemd/strataproxy.env`
- `container/Containerfile`
- `observability/prometheus/strataproxy-alerts.yml`
- `observability/grafana/strataproxy-overview.json`
- `performance/profiles/*.json`

Linux starting JVM options:

```text
-Xms2g -Xmx2g -XX:MaxDirectMemorySize=2g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+ExitOnOutOfMemoryError
```

Set `LimitNOFILE=1048576` or equivalent for high connection counts.

## 16. Security Checklist

- Keep Admin API on loopback unless token and TLS/mTLS are configured.
- Use bearer tokens for non-loopback Admin API.
- Use mTLS for remote automation when possible.
- Enable PROXY protocol only behind trusted load balancers.
- Treat payload captures and Zstd samples as sensitive data.
- Keep forwarding secrets out of static committed config; use environment placeholders.

## 17. Troubleshooting

| Symptom | First Check |
| --- | --- |
| Proxy does not start | Run `--validate-config` and check Java 25 |
| Admin CLI unauthorized | Check `--token` and `admin.bearerToken` |
| No backend selected | Check `ready`, `servers list`, drain state, health, tags, capabilities, protocol range |
| Players disconnect during login | Check `diagnostics`, anomalies, backend connect failures, auth/forwarding mode |
| High p99 latency | Check event loop delay, backpressure, compression rewrite, backend health |
| High memory | Check heap/direct memory metrics and write buffer pressure |
| Zstd decompression failure | Check client/proxy dictionary hash and threshold |

## 18. Verification Before Production

Minimum checks:

```powershell
.\gradlew.bat --no-daemon check installDist :proxy-admin-cli:installDist :proxy-query:installDist
.\gradlew.bat --no-daemon release
.\proxy-app\build\install\strataproxy\bin\strataproxy.bat --validate-config .\proxy-app\build\install\strataproxy\config\strataproxy.yml
```

Production acceptance should be run on Linux with native transport enabled. Record evidence with `deployment/performance/profile-result-template.json`.
