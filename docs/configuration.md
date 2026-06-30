# Configuration Guide

StrataProxy uses one YAML file. The packaged examples are:

- `proxy-app/src/main/resources/config/strataproxy.yml`: local/staging example with one static backend.
- `proxy-app/src/main/resources/config/strataproxy-production.yml`: production-oriented defaults with dynamic registry persistence.

Start the proxy with a config path:

```powershell
.\proxy-app\build\install\strataproxy\bin\strataproxy.bat .\strataproxy.yml
```

Validate without opening sockets:

```powershell
.\proxy-app\build\install\strataproxy\bin\strataproxy.bat --validate-config .\strataproxy.yml
```

String values may use environment placeholders:

```yaml
admin:
  bearerToken: "${STRATAPROXY_ADMIN_TOKEN}"

forwarding:
  secret: "${STRATAPROXY_FORWARDING_SECRET:}"
```

## Minimal Config

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
  healthCheckMode: "tcp"

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
      group: "lobby"
      host: "localhost"
```

## Network

| Key | Meaning | Starting Point |
| --- | --- | --- |
| `bind` | Player listener address | `0.0.0.0:25577` |
| `workerThreads` | Netty worker threads, `0` auto-sizes | `0` |
| `nativeTransport` | Prefer native Netty transport when available | `true` |
| `maxFrameBytes` | Largest accepted Minecraft frame | `8mb` local, `16mb` production |
| `connectTimeoutMillis` | Backend connect timeout | `3000` to `5000` |
| `writeBufferLow` / `writeBufferHigh` | Netty write watermarks | `4mb` / `16mb` |
| `maxConnections` | Global active connection cap | size for host memory |
| `maxConnectionsPerAddress` | Per-IP active connection cap | `200` to `300` |
| `maxNewConnectionsPerSecond` | Global accept rate limit, `0` disables | `0` local, bounded in production |
| `maxNewConnectionsPerAddressPerSecond` | Per-IP accept rate limit, `0` disables | `0` local, bounded in production |
| `initialHandshakeTimeoutMillis` | Drops sockets that do not send handshake | `5000` |
| `proxyProtocol` | Parse HAProxy PROXY protocol v1 | only behind trusted load balancer |

Only enable `proxyProtocol` on a listener that players cannot reach directly.

## Backend Registry

```yaml
registry:
  staticServers: true
  persistenceEnabled: true
  persistencePath: "data/registry.json"
  healthCheckEnabled: true
  healthCheckInterval: "5s"
  healthCheckTimeout: "2s"
  healthCheckMode: "minecraft-status"
```

- `staticServers: true` loads the `servers:` list from YAML.
- `persistenceEnabled: true` stores Admin CLI server changes on disk.
- `healthCheckMode: tcp` checks that the port accepts TCP.
- `healthCheckMode: minecraft-status` checks that the backend speaks the Minecraft status protocol.

Relative `persistencePath` values are resolved from the active config file directory.

## Servers

```yaml
servers:
  - name: "survival-1"
    address: "10.0.0.12:25565"
    tags: ["survival", "forge"]
    capabilities: ["large-payload", "modern-forwarding"]
    protocolRange: "any"
    weight: 100
    softCapacity: 180
    hardCapacity: 220
    drainMode: false
    metadata:
      group: "survival"
      host: "survival.example.net"
```

Routing uses health, drain state, protocol range, tags, capabilities, load, weight, and metadata. Put pack or group identity in `metadata`; there is no dedicated `modpackId` field.

## Forwarding

```yaml
forwarding:
  mode: "none"
  secret: ""
```

Supported modes:

| Mode | Use When |
| --- | --- |
| `none` | Backend does not need proxy-forwarded identity |
| `velocity-modern` | Paper/modern backend expects Velocity forwarding |
| `bungee-legacy` | Backend expects legacy BungeeCord IP forwarding |
| `bungee-guard` | Backend expects BungeeGuard-style secret protection |

`velocity-modern` and `bungee-guard` require a shared secret.

## Online Mode

```yaml
auth:
  onlineMode: false
  rsaKeyBits: 1024
  verifyTokenBytes: 4
  sessionVerification: false
  sessionVerificationTimeout: "5s"
```

Use `onlineMode: true` only when the proxy should perform Minecraft encryption and session verification. Backend identity forwarding is configured separately under `forwarding`.

## Compression

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

Keep `codec: zlib` for normal Minecraft compatibility. Use `codec: zstd` only for controlled modded clients that negotiate the same codec and dictionary. See [Zstd Compression Tuning](compression-zstd.md).

## Admin API

```yaml
admin:
  enabled: true
  bind: "127.0.0.1:8080"
  bearerToken: "${STRATAPROXY_ADMIN_TOKEN:}"
  tls:
    enabled: false
    keyStorePath: ""
    keyStorePassword: ""
    keyStoreType: "PKCS12"
    trustStorePath: ""
    trustStorePassword: ""
    trustStoreType: "PKCS12"
    clientAuth: false
```

Safe defaults:

- Keep `bind: "127.0.0.1:8080"` for local-only administration.
- Set a strong `bearerToken` before binding to a non-loopback address.
- Use TLS or mTLS for remote automation.

## Status Ping

```yaml
status:
  enabled: true
  motd: "StrataProxy"
  protocolName: "StrataProxy"
  protocolVersion: -1
  maxPlayers: 1000
  favicon: ""
  faviconPath: ""
  samplePlayers: []
```

This controls the Minecraft server-list response served by the proxy.

## Packet Analysis And Observability

```yaml
packetAnalysis:
  largePayloadWarnBytes: "1mb"
  unknownChannelThrottleBytes: "256kb"
  moddedHandshakeWarnBytes: "2mb"
  customPayloadFloodMaxCount: 200
  customPayloadFloodWindow: "10s"

observability:
  prometheus: true
  packetTopN: 50
  anomalySampling: true
  flushIntervalSeconds: 5
```

These settings control bounded diagnostics. Full payloads are not retained unless you explicitly start a bounded capture through the Admin CLI.
