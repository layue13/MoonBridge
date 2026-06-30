# StrataProxy

StrataProxy is a new Java 25 Minecraft proxy project for large modded networks. It is not a BungeeCord or HexaCord compatibility rewrite.

The core model intentionally does not contain `modpackId`. Routing decisions are based on capabilities, tags, protocol range, load, health, drain state, and metadata. Pack identity can be stored in `metadata` when operators need it.

Chinese documentation is available at `docs/zh-CN/README.md`.

## Technical Baseline

- Gradle Kotlin DSL multi-module build
- Java Toolchains with Java 25 baseline
- Version Catalog
- Convention plugins in `proxy-build-logic`
- Configuration cache enabled
- Gitea Actions CI in `.gitea/workflows/ci.yml` runs Java 25 `check`, installed distribution smoke tests, release audit, performance profile validation, and release bundle creation on `main` pushes and pull requests

The local machine currently has JDK 25 installed at `C:\Program Files\Zulu\zulu-25`, while the default `java` on PATH may still point to Java 8. Use `JAVA_HOME=C:\Program Files\Zulu\zulu-25` or update PATH before building.

## Modules

- `proxy-api`: stable public model and contracts
- `proxy-network`: Netty acceptor, lifecycle, pipeline, backpressure boundary
- `proxy-protocol`: protocol state, packet metadata, classifier
- `proxy-codec-minecraft`: Minecraft packet definitions and codec entry points
- `proxy-registry`: dynamic server registration, health, load, drain
- `proxy-routing`: tag/capacity/health-aware selection
- `proxy-compression`: adaptive compression decisions
- `proxy-observability`: metrics/event abstractions
- `proxy-packet-analysis`: anomaly rules and diagnostic findings
- `proxy-admin-api`: HTTP Admin API, Prometheus endpoint, registry operations, and incident reports
- `proxy-admin-cli`: scriptable command-line client for the Admin API
- `proxy-query`: Minecraft status and lightweight idle-connection probe CLI
- `proxy-app`: runnable distribution entry point

## Run

Use JDK 25:

```powershell
$env:JAVA_HOME='C:\Program Files\Zulu\zulu-25'
.\gradlew.bat --no-daemon :proxy-app:installDist
.\proxy-app\build\install\strataproxy\bin\strataproxy.bat
```

The process runs until it receives a shutdown signal such as Ctrl+C or service stop. Shutdown is centralized and closes the listener, Admin API, health checker, load reporter, and Netty event loops in reverse startup order.

By default the proxy reads `config/strataproxy.yml`. You can pass a config path as the first argument:

```powershell
.\proxy-app\build\install\strataproxy\bin\strataproxy.bat C:\path\to\strataproxy.yml
```

Validate a config without starting the proxy:

```powershell
.\proxy-app\build\install\strataproxy\bin\strataproxy.bat --validate-config C:\path\to\strataproxy.yml
```

Show startup help or version information:

```powershell
.\proxy-app\build\install\strataproxy\bin\strataproxy.bat --help
.\proxy-app\build\install\strataproxy\bin\strataproxy.bat --version
```

The packaged example config is available in `proxy-app/src/main/resources/config/strataproxy.yml`.
The packaged production-oriented config is available in `proxy-app/src/main/resources/config/strataproxy-production.yml` and is included in the installed distribution as `config/strataproxy-production.yml`.
String config values support environment placeholders in the form `${NAME}` and `${NAME:default}`, which is useful for secrets such as `STRATAPROXY_ADMIN_TOKEN`.

When StrataProxy runs behind a trusted TCP load balancer, enable HAProxy PROXY protocol v1 parsing so admission limits, routing, audit logs, and backend forwarding use the real client address:

```yaml
network:
  proxyProtocol: true
```

Only enable this on listeners that are not reachable directly by players. When enabled, every accepted connection must start with a valid PROXY v1 header before the Minecraft handshake.

Build a complete release bundle with checksums:

```powershell
.\gradlew.bat --no-daemon release
Get-ChildItem .\build\release
Get-Content .\build\release\strataproxy-0.1.0-SNAPSHOT.zip.sha256
```

The same core verification is automated in Gitea CI:

```text
./gradlew --no-daemon --configuration-cache check installDist :proxy-admin-cli:installDist :proxy-query:installDist
./gradlew --no-daemon --configuration-cache release
```

CI proves build, unit/integration tests, installed command smoke tests, release metadata/SBOM/checksum audits, deployment asset validation, and performance profile schema validation. It does not replace the dedicated Linux native acceptance run for the 10k idle / 2k active-player performance target; record that evidence with `deployment/performance/profile-result-template.json`.

The release bundle contains app, Admin CLI, query CLI distribution archives, deployment profiles, sample configs, observability assets, README, `RELEASE-MANIFEST.txt`, `strataproxy-<version>.sbom.cdx.json`, and `strataproxy-<version>.metadata.json`. The checksum file covers the outer release bundle, the three inner distribution archives, the SBOM, and release metadata.

Release metadata is unsigned by default for local builds. Set `STRATAPROXY_RELEASE_SIGNING_KEY` or Gradle property `strataproxy.releaseSigningKey` to emit an HMAC-SHA256 signature over the release metadata payload:

```powershell
$env:STRATAPROXY_RELEASE_SIGNING_KEY='replace-with-ci-secret'
.\gradlew.bat --no-daemon release
```

## Deployment

Production launch profiles live under `deployment/` and are packaged into `proxy-app` distributions:

- `deployment/systemd/strataproxy.service`: Linux service unit with `LimitNOFILE=1048576`, restart policy, restricted filesystem access, and dedicated `strataproxy` user.
- `deployment/systemd/strataproxy.env`: Java 25 runtime defaults using ZGC, fixed heap sizing, explicit Netty direct memory, and fail-fast OOM behavior.
- `deployment/container/Containerfile`: multi-stage Java 25 image build with a non-root runtime user and `/opt/strataproxy/data` volume.
- `deployment/observability/prometheus/strataproxy-alerts.yml`: Prometheus alert rules for availability, event-loop delay, memory pressure, route/backend failures, rejected connections, packet anomalies, and relay backpressure.
- `deployment/observability/grafana/strataproxy-overview.json`: Grafana dashboard for connection lifecycle, server capacity, bandwidth, compression, anomalies, and backpressure.
- `deployment/performance/profiles/*.json`: repeatable performance profiles for smoke, Linux native Java 25 acceptance, and compression rewrite validation.
- `deployment/performance/profile-result-template.json`: structured result record for load-test evidence.
- `deployment/README.md`: install commands, container run command, and JVM sizing guidance.

Start from `JAVA_OPTS="-Xms2g -Xmx2g -XX:MaxDirectMemorySize=2g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+ExitOnOutOfMemoryError"` and resize heap/direct memory from observed `/metrics` values.

Build the admin CLI distribution separately when you want local operator tooling:

```powershell
.\gradlew.bat --no-daemon :proxy-admin-cli:installDist
.\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat --base-url http://127.0.0.1:8080 health
.\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat --base-url http://127.0.0.1:8080 ready
.\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat --base-url http://127.0.0.1:8080 overview
.\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat --base-url http://127.0.0.1:8080 slo --require-ready --max-event-loop-delay-ms 5 --max-rejected 0 --max-anomalies 0
.\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat --base-url http://127.0.0.1:8080 native
.\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat --base-url http://127.0.0.1:8080 compression
.\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat --base-url http://127.0.0.1:8080 packets
.\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat --base-url http://127.0.0.1:8080 players
.\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat --base-url http://127.0.0.1:8080 routes preview --route survival.example.net --protocol-version 763 --remote-address 127.0.0.1:50000 --tag survival --capability large-payload
.\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat --base-url http://127.0.0.1:8080 anomalies
.\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat --base-url http://127.0.0.1:8080 anomalies --samples
.\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat --base-url http://127.0.0.1:8080 backpressure
.\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat --base-url http://127.0.0.1:8080 captures
.\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat --base-url http://127.0.0.1:8080 captures start --id survival-capture --server survival-1 --direction frontend_to_backend --max-samples 64 --max-bytes 256 --duration-ms 30000
.\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat --base-url http://127.0.0.1:8080 captures get survival-capture
.\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat --base-url http://127.0.0.1:8080 servers update survival-1 --weight 20 --soft-capacity 120 --hard-capacity 160 --metadata group=survival-canary
.\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat --base-url http://127.0.0.1:8080 diagnostics
```

Build the query/load probe CLI when you want to verify the proxy data plane:

```powershell
.\gradlew.bat --no-daemon :proxy-query:installDist
.\proxy-query\build\install\strataproxy-query\bin\strataproxy-query.bat --host 127.0.0.1 --port 25577 status --virtual-host play.example.net
.\proxy-query\build\install\strataproxy-query\bin\strataproxy-query.bat --host 127.0.0.1 --port 25577 idle-load --connections 1000 --hold-ms 10000 --parallelism 256 --settle-ms 100 --probe-timeout-ms 50 --fail-on-closed --min-connected 1000 --min-alive 1000 --max-failed 0
.\proxy-query\build\install\strataproxy-query\bin\strataproxy-query.bat --host 127.0.0.1 --port 25577 handshake-load --connections 2000 --virtual-host play.example.net --hold-ms 10000 --parallelism 256 --settle-ms 100 --probe-timeout-ms 50 --fail-on-closed --min-handshaken 2000 --min-alive 2000 --max-failed 0
.\proxy-query\build\install\strataproxy-query\bin\strataproxy-query.bat --host 127.0.0.1 --port 25577 route-storm --connections 2000 --routes 250 --virtual-host-template route-%d.example.net --parallelism 256 --settle-ms 100 --probe-timeout-ms 50 --min-handshaken 2000 --min-routes-handshaken 250 --max-failed 0
.\proxy-query\build\install\strataproxy-query\bin\strataproxy-query.bat --host 127.0.0.1 --port 25577 traffic-load --connections 2000 --virtual-host play.example.net --login-start --player-template load%05d --packets-per-connection 200 --payload-bytes 64 --parallelism 256 --min-handshaken 2000 --min-packets-sent 400000 --max-failed 0
.\proxy-query\build\install\strataproxy-query\bin\strataproxy-query.bat --host 127.0.0.1 --port 25577 traffic-load --connections 100 --virtual-host play.example.net --packets-per-connection 100 --payload-bytes 64 --parallelism 100 --measure-echo-latency --max-p99-latency-ms 5
.\proxy-query\build\install\strataproxy-query\bin\strataproxy-query.bat --host 127.0.0.1 --port 25577 traffic-load --connections 2000 --virtual-host play.example.net --packets-per-connection 200 --payload-bytes 64 --parallelism 256 --min-handshaken 2000 --min-packets-sent 400000 --max-failed 0 --json
.\proxy-query\build\install\strataproxy-query\bin\strataproxy-query.bat --host 127.0.0.1 --port 25577 compression-rewrite-load --connections 100 --virtual-host play.example.net --packets-per-connection 200 --payload-bytes 1024 --threshold 256 --split-frames --parallelism 100 --min-handshaken 100 --min-negotiated 100 --min-packets-sent 20000 --max-failed 0
.\proxy-query\build\install\strataproxy-query\bin\strataproxy-query.bat --host 127.0.0.1 --port 25577 load-suite --profile acceptance --virtual-host play.example.net --parallelism 512 --json
.\proxy-query\build\install\strataproxy-query\bin\strataproxy-query.bat slow-sink --bind-host 127.0.0.1 --port 25565 --duration-ms 30000 --read-chunk-bytes 1 --read-delay-ms 100
.\proxy-query\build\install\strataproxy-query\bin\strataproxy-query.bat slow-sink --bind-host 127.0.0.1 --port 25565 --duration-ms 30000 --read-chunk-bytes 8192 --read-delay-ms 0 --echo
```

`idle-load` reports `connected`, `alive`, `closed`, and `failed` counts. `closed` means the TCP connect succeeded but the proxy closed the socket during the settle/probe window, which is useful for validating connection admission and storm protection.
`handshake-load` sends Minecraft login handshakes before probing sockets and reports `handshaken`, `alive`, `closed`, `failed`, and `handshakeRatePerSecond`, which is useful for validating route storms and backend-connect survivability without completing full player login.
`route-storm` sends Minecraft login handshakes across many generated virtual hosts and reports `routesAttempted`, `routesHandshaken`, `handshakeRatePerSecond`, and `routeRatePerSecond`, which is useful for validating routing decision throughput across many host/metadata route keys.
`traffic-load` sends generated Minecraft packet frames after the login handshake and reports `packetsSent`, `bytesSent`, `packetRatePerSecond`, and `byteRatePerSecond`, which is useful for validating transparent relay throughput and backend write pressure. With `--login-start`, it sends generated Login Start player names before traffic frames and reports `loginStartsSent` plus `loginStartBytesSent`, which exercises active-player session attribution in the proxy. With `--measure-echo-latency`, the backend must echo each received Minecraft frame; the command then reports `latencySamples`, `p50LatencyMillis`, `p95LatencyMillis`, `p99LatencyMillis`, and `maxLatencyMillis`.
`compression-rewrite-load` sends Login Start, waits for backend/proxy `Set Compression`, then sends generated Minecraft compression-mode frames. Use `--split-frames` to split each compressed frame across two writes so release checks exercise partial-frame buffering, recompression throughput, and rewrite guard behavior.
`load-suite` runs repeatable scenarios by orchestrating the built-in probes. The default `smoke` profile uses small counts for CI. The `acceptance` profile defaults to `10000` idle connections, `2000` active traffic connections, and `200` packets per active connection; use overrides such as `--idle-connections`, `--active-connections`, and `--traffic-packets-per-connection` to scale for the host. Add `--include-compression-rewrite` when the backend/proxy path negotiates compression and should be included in the suite.
All load commands support scriptable acceptance gates and `--json` machine-readable output for CI or load-test pipelines. `idle-load` supports `--min-connected`, `--min-alive`, `--max-closed`, `--max-failed`, and `--min-connect-rate`. `handshake-load` supports `--min-handshaken`, `--min-alive`, `--max-closed`, `--max-failed`, and `--min-handshake-rate`. `route-storm` supports `--min-handshaken`, `--min-alive`, `--min-routes-handshaken`, `--max-closed`, `--max-failed`, `--min-handshake-rate`, and `--min-route-rate`. `traffic-load` supports `--min-handshaken`, `--min-packets-sent`, `--min-bytes-sent`, `--max-failed`, `--min-packet-rate`, `--min-byte-rate`, and `--max-p99-latency-ms`. `compression-rewrite-load` supports `--min-handshaken`, `--min-negotiated`, `--min-packets-sent`, `--min-bytes-sent`, `--max-failed`, `--min-packet-rate`, and `--min-byte-rate`. `load-suite` exits non-zero if any child probe fails. The command exits non-zero when any configured gate is not met.
Release performance profiles live in `deployment/performance`. They define the commands, host prerequisites, JVM options, and gates for repeatable smoke, acceptance, and compression rewrite validation. Use `profile-result-template.json` to record actual host evidence; do not treat a profile as passed without captured command output and gate results.
`slow-sink` starts a local backend that accepts TCP connections and reads slowly, or holds sockets without reading when `--read-chunk-bytes 0` is used. Point a StrataProxy backend at it, run `traffic-load` against the proxy, then inspect `strataproxy-admin backpressure` to validate frontend-to-backend write pressure attribution. Add `--echo` with a large read chunk and no read delay when `traffic-load --measure-echo-latency` needs a byte-for-byte echo backend.

Compression rewrite is opt-in:

```yaml
compression:
  mode: adaptive
  minThreshold: 256
  maxThreshold: 8192
  cpuGuard: 0.75
  rewriteEnabled: false
  rewriteMaxEventLoopDelayMillis: 25
```

Leave `rewriteEnabled: false` for maximum transparent fast-path compatibility. Set it to `true` only when you want the proxy to apply the configured compression strategy to safe bounded compressed relay frames after backend compression negotiation. Partial frames are buffered per connection until a complete frame can be rewritten or safely forwarded by policy. `rewriteMaxEventLoopDelayMillis` disables live rewrite while observed event-loop delay is above the configured threshold; `0` disables this guard.

Dynamic registry persistence is enabled by default:

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

Static config is loaded first. Persisted dynamic entries are then replayed when their names are not already present in static config.
Relative `registry.persistencePath` values are resolved from the directory containing the active config file, so the packaged default `data/registry.json` lives next to `config/strataproxy.yml` instead of depending on the process working directory.
If the persisted registry file is corrupt or contains invalid entries, startup quarantines it with a `.invalid-<timestamp>` suffix, logs a warning, and continues from static config or an empty dynamic registry.
Set `registry.staticServers: false` for dynamic-only deployments. In that mode `servers:` entries in YAML are ignored, the proxy may start with zero registered backends, and operators can add servers through the Admin API or persisted registry state. If the Admin API is disabled, at least one backend server must be configured.
`healthCheckMode` accepts `tcp` or `minecraft-status`. `tcp` only verifies that the backend port accepts connections. `minecraft-status` sends a Minecraft server-list Handshake plus Status Request and marks the backend down unless it returns a valid Status Response, which is better for production backends whose process may keep the port open while the Minecraft protocol path is unhealthy.

Custom payload anomaly thresholds are configurable and accept byte units:

```yaml
packetAnalysis:
  largePayloadWarnBytes: "1mb"
  unknownChannelThrottleBytes: "256kb"
  moddedHandshakeWarnBytes: "2mb"
  customPayloadFloodMaxCount: 200
  customPayloadFloodWindow: "10s"
```

Packet anomaly reporting is controlled separately from rule evaluation:

```yaml
observability:
  prometheus: true
  packetTopN: 50
  anomalySampling: true
  flushIntervalSeconds: 5
```

`prometheus: false` disables the Prometheus text endpoint at `/metrics` while leaving structured Admin API reports such as `/overview`, `/compression-report`, `/packet-traffic`, and `/packet-anomalies` available.
`packetTopN` limits the sorted rule list returned by `/packet-anomalies`. `anomalySampling: false` keeps counters and Prometheus metrics active, but stops retaining recent anomaly samples.
`flushIntervalSeconds` controls how often proxy-observed bytes, packet counts, and event loop delay are converted into per-server `ServerLoad` rates for routing and admin metrics.

Minecraft server-list status can be answered directly by the proxy:

```yaml
status:
  enabled: true
  motd: "StrataProxy"
  protocolName: "StrataProxy"
  protocolVersion: -1
  maxPlayers: 1000
  faviconPath: "favicon.png"
  samplePlayers:
    - name: "Survival"
      id: "00000000-0000-0000-0000-000000000001"
```

When enabled, Handshake `nextState=1` is handled locally and does not require a routable backend. This keeps the server list useful during maintenance, dynamic-only startup, or total backend outage. `online` is derived from active player sessions observed by the proxy; `maxPlayers`, MOTD, protocol display, optional PNG favicon, and sample player rows are operator-configured. `favicon` accepts a `data:image/png;base64,...` URI; `faviconPath` reads a PNG relative to the active config file.

Native CPU/runtime acceleration is enabled by default and can be adjusted explicitly:

```yaml
native:
  enabled: true
  autoDetect: true
  preferNativeTransport: true
  requireNativeTransport: false
  preferOpenSslTls: false
  preferNativeCompression: false
  disabledFeatures: []
  forcedFeatures: []
```

`autoDetect` records available CPU features such as AES, VAES, PCLMULQDQ, SHA-NI, CRC32, AVX2, AVX-512F, BMI2, POPCNT, NEON, and ARM crypto where the host exposes them. Java 25 and the JDK TLS/zlib implementations use their own intrinsics internally when available; StrataProxy records the selected runtime providers and exposes them through `/native-capabilities`, `/diagnostic-report`, and Prometheus. Set `disabledFeatures` to suppress a problematic feature in diagnostics and policy decisions, or `forcedFeatures` to model a capability that the detector cannot see. Set `requireNativeTransport: true` on Linux acceptance hosts when epoll/kqueue fallback to NIO should fail startup instead of silently reducing the performance envelope.

StrataProxy does not ship custom JNI code for the core data path while Netty and the JDK already provide the needed native pieces. The application distribution includes Netty native transport classifiers for Linux x86_64, Linux aarch64, macOS x86_64, and macOS aarch64. Runtime selection is automatic:

| Platform | Native path | Fallback |
| --- | --- | --- |
| Linux x86_64 / aarch64 | Netty epoll | NIO |
| macOS x86_64 / aarch64 | Netty kqueue | NIO |
| Windows x86_64 / aarch64 | NIO | NIO |

Windows support is intentionally NIO-first because Netty does not provide an IOCP server transport equivalent in the standard transport set. If future profiling shows Admin API TLS, proxy TLS, or a custom encrypted transport is CPU-bound, add Netty `netty-tcnative-boringssl-static` as a provider dependency before considering project-owned JNI. For Minecraft proxy packet forwarding, native epoll/kqueue plus JDK crypto/compression intrinsics are the default high-performance baseline.

Minecraft Java online-mode encryption is protocol-level RSA plus AES/CFB8, not TLS. StrataProxy provides Minecraft AES/CFB8 Netty cipher handlers, RSA shared-secret/verify-token handling, server hash calculation, and an online-mode login handler that can send Encryption Request, validate Encryption Response, install stream ciphers, and then continue backend relay with the plaintext Handshake/Login Start. Enable the staged entry point with:

```yaml
auth:
  onlineMode: true
  rsaKeyBits: 1024
  verifyTokenBytes: 4
  sessionVerification: true
  sessionVerificationTimeout: "5s"
```

When `sessionVerification` is enabled, StrataProxy calls Mojang `hasJoined` asynchronously after the Encryption Response and only continues backend relay for accepted profiles. Keep backend servers configured for the chosen forwarding mode; player-side online-mode encryption termination does not by itself define the backend identity-forwarding contract.

Backend identity forwarding is configured separately:

```yaml
forwarding:
  mode: "velocity-modern"
  secret: "${STRATAPROXY_FORWARDING_SECRET}"
```

Supported modes are `none`, `velocity-modern`, `bungee-legacy`, and `bungee-guard`.

`velocity-modern` intercepts the backend Login Plugin Request on `velocity:player_info` before compression negotiation and replies with a HMAC-SHA256 signed Login Plugin Response containing the client address, verified UUID, username, and Mojang profile properties. Backend Paper-compatible servers should run behind the proxy with backend `online-mode=false`, Velocity forwarding enabled, and the same secret. Version 1 identity/profile forwarding is always supported. When the backend requests version 2 and the client Login Start supplied chat signing key material, StrataProxy returns version 2 and appends the public-key expiry, encoded public key, and Mojang key signature. If no key material is available, it returns a version 1 payload rather than fabricating invalid chat-signing data.

`bungee-legacy` rewrites the backend Handshake host field to the classic BungeeCord NUL-separated format: requested host, client address, UUID without dashes, and profile properties JSON. In offline-mode proxy operation it waits for Login Start before connecting so it can derive the offline UUID from the player name instead of sending an incomplete legacy handshake. `bungee-guard` uses the same handshake format and appends the configured shared secret as the final NUL-separated field. Use these modes for Spigot/Paper servers configured with BungeeCord-style IP forwarding; prefer `bungee-guard` when the backend supports it.

Use `/healthz` for process liveness and `/readyz` for load balancer readiness. `/readyz` returns `200 READY` only when at least one registered backend can receive new connections; drained, down, maintenance, and hard-full backends make the proxy not ready when no other backend is available.

## Current Runtime Capability

- Binds a Minecraft proxy listener.
- Supports `--validate-config` to catch unsafe network, compression, packet analysis, registry, admin, and backend server settings before startup.
- Supports `--help` and `--version` in the installed `strataproxy` command.
- Reports startup failures such as invalid config, occupied ports, and bind failures with a short operator-facing error.
- Runs as a long-lived service until shutdown, with one centralized shutdown hook and idempotent reverse-order resource cleanup.
- Reports the actual bound proxy address after startup, including when the configured port is `0`.
- Honors `network.nativeTransport`: uses Netty epoll/kqueue when available and falls back to NIO otherwise; startup logs the selected transport.
- Detects native CPU/runtime capabilities at startup and records low-cardinality feature flags plus selected TLS/compression providers for operations and performance reports.
- Supports Minecraft online-mode player-side encryption termination with optional Mojang session verification.
- Supports proxy-level Minecraft server-list status responses without opening a backend connection, including configurable MOTD, protocol display, max players, favicon, and sample player rows.
- Sends Minecraft Login Disconnect JSON frames for routable login failures such as no available backend route, oversized pipelined login data, or backend connect failure, instead of exposing clients to a bare TCP close.
- Supports Velocity modern forwarding v1/v2 by answering backend `velocity:player_info` login plugin requests with signed player identity/profile payloads and optional 1.19+ chat signing key material.
- Supports BungeeCord legacy IP forwarding and BungeeGuard secret forwarding by rewriting the backend Handshake after player identity is known.
- Parses the first Minecraft handshake frame using bounded VarInt/frame checks.
- Rejects excessive bytes pipelined after the initial handshake before backend connect; pre-route pending data is capped by `network.maxFrameBytes`.
- Provides Minecraft compression frame codec and Netty encoder/decoder handlers with bounded VarInt parsing, threshold checks, maximum uncompressed-size guard, partial-frame handling, and malformed zlib rejection.
- Provides a bounded custom payload classifier for inspection paths. It identifies brand, Forge/FML handshake, Fabric handshake, registry/config sync, unknown channels, and large payloads without consuming the original `ByteBuf`.
- Provides configurable custom payload anomaly policies for large payloads, large unknown channels, oversized Forge/Fabric handshake payloads, and per-connection custom payload flood windows.
- Runs custom payload classification and anomaly policies on uncompressed client-to-backend configuration payload frames in the live relay. After compression negotiation, it also inspects a bounded early compressed-frame window for configuration custom payloads. WARN findings record anomaly counters and recent samples without changing forwarded bytes; THROTTLE findings close the relay before forwarding the triggering payload.
- Records classified custom payload counters by server, direction, kind, and bounded channel for Forge/Fabric/registry diagnostics. Unknown channels are grouped as `unknown` to avoid unbounded metric cardinality.
- Provides configured compression strategies for `off`, `fixed`, and `adaptive` modes. Adaptive decisions use packet size, RTT, historical compression ratio, threshold bounds, and the configured CPU guard.
- Provides a standalone compressed-frame rewriter that can decode one Minecraft compression-mode frame, re-encode it with a target threshold, and return before/after frame size attribution.
- Supports opt-in live compressed-frame rewrite after backend compression negotiation through `compression.rewriteEnabled`. The default remains `false`; when enabled, the relay rewrites safe bounded compressed-frame batches selected by the configured compression strategy, including frames split across reads, and records low-cardinality rewrite outcomes plus CPU time.
- Routes to a backend by requested host, server name, tag, metadata `host`, metadata `route`, protocol range, health, drain state, capacity, and effective weight.
- Distributes matching requests with deterministic weighted selection, so gray/canary backends receive traffic according to weight while repeated requests from the same source remain stable.
- Exposes route preview with candidate explanations through the Admin API and CLI so operators can verify host/tag/capability/protocol decisions, effective weights, and rejection reasons before changing DNS, weights, or drain mode.
- Falls back to a generic healthy backend when no host-specific backend matches, which makes a one-backend setup work out of the box.
- Relays client/backend traffic transparently after routing.
- Uses Netty with manual read backpressure: both frontend and backend channels keep `AUTO_READ=false` during relay and only read after writes complete.
- Applies configured backend connect timeout and write-buffer watermarks to frontend/backend channels.
- Enforces global and per-address connection admission limits before handshake routing to reduce connection storm impact.
- Supports optional HAProxy PROXY protocol v1 parsing on trusted listener deployments, so per-address limits, routing, player attribution, and Bungee/Velocity forwarding use the forwarded client address.
- Attributes admission rejections by low-cardinality reason (`global_limit` or `per_address_limit`) so operators can distinguish total saturation from one-address storms without high-cardinality client labels.
- Closes connections that do not send the initial Minecraft handshake within `network.initialHandshakeTimeoutMillis`.
- Uses Netty pooled `ByteBuf` allocation for listener and backend connections.
- Tracks accepted connections, active connections, rejected connections by reason, routed connections, route failures, backend connect failures, and bidirectional bytes with low-contention `LongAdder` counters.
- Tracks proxy-observed active and total routed connections per backend server.
- Tracks active player sessions by bounded Login Start username parsing and attributes each player to the selected backend server without decoding mod payloads.
- Attributes real forwarded bytes to each selected backend server for bandwidth auditing.
- Samples uncompressed Minecraft frame packet IDs on the transparent relay path without deep payload decoding, tracking packet count and byte totals by server, direction, state, and packet ID.
- Periodically converts proxy-observed traffic deltas into per-server load rates while preserving externally supplied player counts and capacity values.
- Detects backend login `Set Compression` negotiation on the transparent fast path and records the selected threshold without changing forwarded bytes.
- Samples backend-to-client and client-to-backend compressed-frame wire size versus declared uncompressed packet size after negotiation for compression bandwidth audit.
- Enforces a lightweight compressed-frame safety gate after compression negotiation: malformed compressed frames and frames whose declared uncompressed size exceeds `network.maxFrameBytes` are recorded as anomalies and the relay is closed instead of forwarding a possible compression bomb.
- Records compression audit samples globally, per backend server, and per relay direction: raw bytes, compressed bytes, saved bytes, ratio, and CPU time.
- Runs the configured compression strategy against live compression audit samples and exports decision counts by server, direction, action, and threshold. This observes the live policy outcome without rewriting forwarded bytes yet.
- Tracks malformed initial frame, malformed initial handshake, and no-route handshake anomalies by rule name.
- Samples event loop delay, pooled direct memory, and JVM heap metrics for runtime performance diagnosis.
- Exposes per-server health, load, capacity, bandwidth, and packet-rate metrics.
- Exposes per-server proxy-observed active connections and routed connection totals.
- Exposes per-server cumulative frontend-to-backend and backend-to-frontend forwarded bytes.
- Exposes global compression negotiation count and per-server negotiated compression threshold.
- Exposes global, per-server, and per-direction compression audit metrics for compression strategy tuning.
- Exposes compression strategy decision counters as `strataproxy_compression_decisions_total`.
- Exposes opt-in compression rewrite outcome counters as `strataproxy_compression_rewrites_total` and CPU cost as `strataproxy_compression_rewrite_cpu_seconds_total`.
- Exposes packet traffic counters as `strataproxy_packet_traffic_packets_total`, `strataproxy_packet_traffic_raw_bytes_total`, and `strataproxy_packet_traffic_compressed_bytes_total`.
- Exposes relay backpressure counters and gauges as `strataproxy_relay_backpressure_events_total`, `strataproxy_relay_backpressure_last_bytes_before_writable`, and `strataproxy_relay_backpressure_max_bytes_before_writable`.
- Exposes active player sessions as `strataproxy_player_session_active`, `GET /player-sessions`, `strataproxy-admin players`, and diagnostic reports.
- Exposes JVM GC collection and thread-count metrics.
- Exposes unauthenticated liveness and readiness probes: `/healthz` reports process health, while `/readyz` returns 503 until at least one backend is routable.
- Exposes a structured packet anomaly report sorted by rule count and limited by `observability.packetTopN`, plus an optional fixed-size recent sample ring buffer for operators and CLI tooling.
- Exposes a structured packet traffic report sorted by raw bytes and limited by `observability.packetTopN`.
- Exposes a structured diagnostic report combining overview, admission rejection reason breakdown, server state, compression audit, packet traffic, classified custom payload traffic, packet anomaly samples, relay backpressure rows, and active payload capture state for incident export.
- Ships packaged Prometheus alert rules and a Grafana overview dashboard under `deployment/observability`.
- Supports operator-toggled short-lived payload prefix captures with fixed-size ring buffers. Captures are disabled by default, expire automatically, store only the first configured bytes per matching relay buffer, and include player plus remote-address attribution when the connection identity is known.
- Persists dynamic registry changes to `registry.persistencePath` with temp-file write plus atomic move when supported.
- Resolves relative registry persistence paths from the active config directory, making installed distributions independent of the shell working directory.
- Quarantines unreadable or invalid persisted registry files during startup and continues from static config or dynamic-only Admin API recovery.
- Resolves relative Admin TLS keystore and truststore paths from the active config directory during both validation and startup.
- Supports `${NAME}` and `${NAME:default}` environment placeholders in YAML config values before strong config mapping.
- Rejects unauthenticated Admin API exposure on non-loopback bind addresses unless mTLS client authentication is enabled.
- Persists drain mode changes so maintenance state survives restart.
- Replays persisted registry entries on startup after static config is loaded.
- Validates Admin API JSON request bodies and returns 400 for malformed or unsafe registration, health, and load updates.
- Treats `POST /servers` as an upsert: new servers return 201, existing names return 200 and replace descriptor fields while preserving current health/load samples.
- Returns 404 for health/load updates to unknown servers and rolls back in-memory registration when registry persistence fails.
- Runs optional backend health checks and marks unhealthy servers `DOWN`, causing routing to avoid them. Checks can use plain TCP connect or Minecraft status protocol validation.
- Exposes:
  - `GET /healthz`
  - `GET /readyz`
  - `GET /overview`
  - `GET /native-capabilities`
  - `GET /compression-report`
  - `GET /diagnostic-report`
  - `GET /metrics` in Prometheus text format when `observability.prometheus` is enabled
  - `GET /packet-anomalies`
  - `GET /packet-traffic`
  - `GET /custom-payloads`
  - `GET /player-sessions`
  - `GET /routes/preview`
  - `GET /payload-captures`
  - `POST /payload-captures`
  - `GET /payload-captures/{id}`
  - `DELETE /payload-captures/{id}`
  - `GET /servers`
  - `GET /servers/{name}`
  - `POST /servers`
  - `PATCH /servers/{name}`
  - `DELETE /servers/{name}`
  - `POST /servers/{name}/drain`
  - `POST /servers/{name}/undrain`
  - `POST /servers/{name}/health`
  - `POST /servers/{name}/load`
- Provides `strataproxy-admin` CLI commands for health, readiness, structured operational overview with admission rejection reasons independent of Prometheus, scriptable SLO gates for readiness, event-loop delay, active connections, rejected connections, anomaly count, and native transport, native CPU/runtime capability summaries, metrics, diagnostic report export, direction-aware compression audit, strategy decision, and live rewrite outcome summaries independent of Prometheus, packet traffic summaries, classified custom payload summaries, active player sessions, route preview, structured packet anomaly summary with optional recent samples, relay backpressure summaries, payload capture list/start/get/stop, server list/get/register/update/remove/drain/undrain, and health/load updates.
- Supports optional Admin API HTTPS and mTLS using Java keystore/truststore configuration.
- Provides `strataproxy-query` CLI commands for Minecraft status checks, idle TCP connection load probes, Minecraft handshake route-load probes, multi-virtual-host route storm probes, generated packet traffic-load probes, compression rewrite load probes with partial-frame writes, repeatable smoke/acceptance load-suite orchestration, JSON load-test result output for automation, and a slow-reading backend sink for backpressure validation.

If `admin.bearerToken` is set, all admin endpoints except `/healthz` and `/readyz` require:

```text
Authorization: Bearer <token>
```

`--validate-config` rejects an Admin API bound to a non-loopback address with a blank `admin.bearerToken` unless Admin TLS `clientAuth` is enabled. The packaged default binds Admin API to `127.0.0.1`, where a blank token is allowed but still reported as a warning.

Admin HTTPS and optional mTLS can be enabled with a Java keystore. `clientAuth: true` requires clients to present a certificate trusted by the configured truststore:

```yaml
admin:
  enabled: true
  bind: "127.0.0.1:8443"
  bearerToken: "replace-with-a-strong-token"
  tls:
    enabled: true
    keyStorePath: "conf/admin-server.p12"
    keyStorePassword: "changeit"
    keyStoreType: "PKCS12"
    trustStorePath: "conf/admin-clients.p12"
    trustStorePassword: "changeit"
    trustStoreType: "PKCS12"
    clientAuth: true
```

Relative Admin TLS `keyStorePath` and `trustStorePath` values are resolved from the active config directory during validation and startup.

When TLS is enabled, point the admin CLI at an `https://` base URL:

```powershell
.\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat `
  --base-url https://127.0.0.1:8443 `
  --token replace-with-a-strong-token `
  --trust-store-path conf/admin-server-ca.p12 `
  --trust-store-password changeit `
  --trust-store-type PKCS12 `
  health
```

When Admin API mTLS `clientAuth` is enabled, also provide the client keystore:

```powershell
.\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat `
  --base-url https://127.0.0.1:8443 `
  --token replace-with-a-strong-token `
  --trust-store-path conf/admin-server-ca.p12 `
  --trust-store-password changeit `
  --key-store-path conf/admin-client.p12 `
  --key-store-password changeit `
  health
```

Example short diagnostic payload prefix capture:

```powershell
.\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat `
  --base-url http://127.0.0.1:8080 `
  --token replace-with-a-strong-token `
  captures start `
  --id survival-capture `
  --server survival-1 `
  --direction frontend_to_backend `
  --max-samples 64 `
  --max-bytes 256 `
  --duration-ms 30000

.\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat `
  --base-url http://127.0.0.1:8080 `
  --token replace-with-a-strong-token `
  captures get survival-capture

Invoke-RestMethod `
  -Method POST `
  -Uri http://127.0.0.1:8080/payload-captures `
  -Headers @{Authorization = "Bearer replace-with-a-strong-token"} `
  -ContentType "application/json" `
  -Body '{"id":"survival-capture","server":"survival-1","direction":"frontend_to_backend","maxSamples":64,"maxBytesPerSample":256,"durationMillis":30000}'

Invoke-RestMethod `
  -Uri http://127.0.0.1:8080/payload-captures/survival-capture `
  -Headers @{Authorization = "Bearer replace-with-a-strong-token"}
```

Example dynamic registration:

```powershell
.\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat `
  --base-url http://127.0.0.1:8080 `
  --token replace-with-a-strong-token `
  servers register `
  --name survival-1 `
  --address 10.0.0.12:25565 `
  --tag survival,forge `
  --capability large-payload,modern-forwarding `
  --protocol-range 763 `
  --soft-capacity 180 `
  --hard-capacity 220 `
  --metadata host=survival.example.net,group=survival
```

Example gray rollout adjustment without re-registering the whole server descriptor:

```powershell
.\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat `
  --base-url http://127.0.0.1:8080 `
  --token replace-with-a-strong-token `
  servers update survival-1 `
  --weight 20 `
  --soft-capacity 120 `
  --hard-capacity 160 `
  --tag survival,forge,canary `
  --metadata host=survival.example.net,group=survival-canary
```

## Performance Design

The first usable runtime favors a high-throughput transparent fast path:

- Only the first handshake packet is decoded for routing.
- Bytes pipelined with the first handshake are bounded before the backend connection is opened, preventing pre-route memory amplification under connection storms.
- Initial handshake anomaly detection is bounded to the first frame and does not add deep inspection to the relay fast path.
- Post-route packets are forwarded as Netty `ByteBuf` objects without full packet decode.
- Relay uses event-loop-local backend connects and does not run blocking work on Netty event loops.
- Native Netty transports are selected once at startup and reused for listener and backend connections.
- Routing uses deterministic weighted sampling instead of global highest-score selection, avoiding a hot backend under equal pools while still reducing effective weight for high capacity pressure, recent failures, backend latency, and event-loop delay.
- Backpressure is connection-local and write-driven, avoiding unbounded pending reads under slow backend/client conditions.
- Relay handlers record per-server, per-direction backpressure only when the target Netty channel is not writable, giving operators slow-backend evidence without adding work to the normal writable path.
- Per-server bandwidth attribution is recorded in the relay path with `LongAdder` counters and does not decode forwarded packets.
- Compression negotiation detection only parses bounded pre-compression login frames and disables itself after a threshold is found or malformed input is observed.
- Compression frame audit parses only frame length and declared data length after negotiation in both relay directions; it does not inflate payloads or alter forwarded buffers.
- Compression safety checks use only VarInt/frame metadata and close unsafe relays before forwarding malformed or oversized compressed frames.
- Compression audit counters and strategy decision counters use `LongAdder`; configured compression strategy selection is created at startup, and runtime policy observation only runs on already-sampled compressed frames.
- Compression rewrite is isolated behind a single-frame codec primitive and an opt-in Netty relay path, so live threshold changes are guarded by exact frame boundaries, max-uncompressed-size checks, and explicit ByteBuf ownership.
- Live compression rewrite is conservative: it only runs after negotiated compression, buffers partial compressed frames with bounded per-connection state, rewrites only audited complete compressed-mode frame batches, and falls back to forwarding the original `ByteBuf` for bypass decisions, mixed per-frame policy targets, high event-loop delay, unsafe frame shapes, or rewrite failures.
- Packet anomaly recent samples use a fixed-size ring buffer and record bounded metadata only; full payload bytes are not retained.
- Payload prefix captures are opt-in, bounded by sample count and bytes per sample, and expire automatically. Relay handlers copy bytes only when a matching capture is active.
- Custom payload classification performs bounded channel-name parsing only; full mod payload semantics are not decoded on the fast path. Live inspection covers uncompressed client-to-backend configuration payload frames and a bounded early compressed-frame window after compression negotiation, then closes the deep inspection sampler so normal play traffic returns to metadata-only auditing.
- Custom payload anomaly policies operate on classifier metadata only and do not retain full payloads. THROTTLE enforcement for large unknown channels and per-connection custom payload floods happens before backend forwarding, protecting modded login/configuration phases from oversized or high-rate unknown payload abuse.
- Minecraft compression codec and Netty handler instances reuse zlib state and scratch buffers. Compressed custom-payload inspection inflates only bounded early frames and immediately releases decoded buffers.
- `network.writeBufferLow` and `network.writeBufferHigh` are applied as Netty watermarks to protect pending write queues.
- `network.connectTimeoutMillis` is applied to backend connection attempts so failed routes do not hang.
- `network.maxConnections` and `network.maxConnectionsPerAddress` reject excess connections before route/backend work starts.
- Admission rejects are exported as `/overview` and `/diagnostic-report` JSON reason maps, CLI overview reason rows, a compatible total counter, and `strataproxy_connections_rejected_total{reason="global_limit|per_address_limit"}` for storm attribution.
- `network.initialHandshakeTimeoutMillis` prevents idle pre-handshake sockets from holding connection slots indefinitely.
- Metrics use `LongAdder` and stay off the hot path beyond simple increments.
- Global connection lifecycle is accounted at admission; per-server lifecycle starts only after backend route/connect succeeds.
- Runtime monitoring samples once per second by scheduling tiny tasks onto worker event loops; player identity attribution only parses the bounded Login Start username before compression and does not decode mod payloads.
- Server and JVM metrics are collected only when `/metrics` is scraped.
- Admin HTTP uses Java 25 virtual threads and is isolated from Netty event loops.
- Startup failure rolls back already-started admin, health-check, load-reporting, and Netty resources.
- Runtime close paths are idempotent; Netty shutdown waits for graceful completion from non-event-loop callers and avoids blocking inside Netty event loops.
- Admin bearer-token checks are simple constant-time comparisons outside the packet forwarding path.
- Admin TLS and client-certificate checks are isolated to the Admin API listener and never run on Netty proxy event loops.
- Registry persistence only runs on admin mutations and startup, never on the packet forwarding path.
- Health checks run on a dedicated daemon scheduler and update registry health out of band.

## Verification

Known-good checks:

```powershell
$env:JAVA_HOME='C:\Program Files\Zulu\zulu-25'
.\gradlew.bat --no-daemon check installDist :proxy-admin-cli:installDist :proxy-query:installDist
.\gradlew.bat --no-daemon release
.\gradlew.bat --no-daemon :proxy-app:installedDistSmokeTest
.\gradlew.bat --no-daemon :proxy-app:installedDistProductionConfigSmokeTest
.\gradlew.bat --no-daemon :proxy-app:deploymentAssetsSmokeTest
.\gradlew.bat --no-daemon performanceProfilesSmokeTest
.\gradlew.bat --no-daemon :proxy-app:installedDistHelpSmokeTest
.\gradlew.bat --no-daemon :proxy-app:installedDistVersionSmokeTest
.\gradlew.bat --no-daemon :proxy-admin-cli:installedDistSmokeTest
.\gradlew.bat --no-daemon :proxy-query:installedDistSmokeTest
.\proxy-app\build\install\strataproxy\bin\strataproxy.bat --validate-config .\proxy-app\build\install\strataproxy\config\strataproxy.yml
```

Smoke-tested runtime:

- started installed distribution with a temporary config
- verified `/healthz`
- verified `/readyz` and `strataproxy-admin ready`
- verified `/metrics`
- confirmed listener/admin ports were released after shutdown
- covered app-level YAML startup smoke: temporary config -> running proxy -> Minecraft status request -> backend -> proxy -> client, with runtime resources closed inside the test
- covered proxy-level Minecraft server-list status response and Pong without a backend route, including config-loaded favicon and sample player rows
- covered config-relative registry persistence path resolution at runtime
- covered TCP and Minecraft status backend health-check modes, including protocol-invalid backends being marked down
- covered corrupt persisted registry quarantine plus successful runtime startup
- covered environment placeholder expansion in YAML config values
- covered installed distribution validation of both default and production-oriented packaged configs
- covered deployment observability asset validation, including Grafana JSON parsing and required Prometheus alert/dashboard metric references
- covered release packaging with app/Admin CLI/query CLI archives, deployment profiles, configs, observability assets, manifest, CycloneDX-style SBOM, release metadata, optional HMAC-SHA256 metadata signing, and SHA-256 checksums
- covered performance profile asset validation, including Java 25 target checks, command/gate presence, and result template structure
- covered config-relative Admin TLS keystore path validation
- covered rejection of unauthenticated non-loopback Admin API binds while allowing mTLS client-authenticated Admin API
- covered deterministic weighted routing for gray rollout distribution, stable same-source selection, and capacity-pressure load shifting
- covered Admin API and Admin CLI route preview for selected and rejected routing decisions, including candidate eligibility, effective weight, and rejection reason output
- covered `/readyz` reporting 200 only when a backend can receive new connections and 503 for empty, draining, down, or hard-full pools
- covered idempotent runtime close, shutdown waiter release, and listener port release
- covered active player session tracking from split Login Start frames plus Prometheus, direct Admin API, Admin CLI, and diagnostic report export
- covered a real TCP proxy smoke test in `proxy-network`: client handshake -> proxy -> backend -> proxy -> client, including routed connection count and per-server byte attribution
- covered HAProxy PROXY protocol v1 parsing over real TCP, including forwarded source address use during routing and stripping the PROXY header before backend relay
- covered real TCP Minecraft Login Disconnect responses for no-route, oversized pending-login-data, and backend-connect-failure login paths
- covered a real TCP per-address connection storm test in `proxy-network`: concurrent virtual-thread clients exceed the per-address admission limit and rejected connections are counted
- covered connection admission rejection reasons from control logic through Netty handler metrics, Prometheus output, Admin API diagnostic JSON, and CLI overview output
- covered `proxy-query` status protocol encode/decode and a real local status-query exchange
- covered `proxy-query` Minecraft login handshake load probe against a real local socket server
- covered `proxy-query` route-storm probe across multiple generated virtual hosts, JSON output, and route threshold failure
- covered `proxy-query` generated packet traffic-load probe against a real local socket server, including optional Login Start player-name frames
- covered `proxy-query` compression rewrite load probe against a real local socket server, including Set Compression negotiation, generated compressed packet frames, split-frame writes, and JSON output
- covered `proxy-query` load-suite orchestration against a real local socket server, including idle and active traffic child probes plus JSON suite summaries
- covered `proxy-query` idle, handshake, and traffic load acceptance gates returning non-zero when configured thresholds are not met
- covered `proxy-query` idle, handshake, and traffic load JSON output for machine-readable performance gates
- covered `proxy-query` traffic-load echo latency sampling and p99 latency gate output
- covered `proxy-query` slow-reading backend sink startup, connection acceptance, byte accounting, and bounded shutdown
- covered Admin API HTTPS server construction plus TLS config parsing and validation
- covered Admin CLI HTTPS/mTLS with Java `keytool`-generated PKCS12 server/client stores and a real client-certificate handshake
- covered compressed client-to-backend custom payload inspection, classified custom payload Admin API/CLI/Prometheus reporting, and THROTTLE enforcement before forwarding
- covered payload prefix capture ring buffers, Admin API capture lifecycle, relay prefix capture, and frontend/backend capture sample player identity attribution
- covered `strataproxy-admin slo` SLO gates returning non-zero when event-loop delay or rejected-connection thresholds are exceeded
- covered `strataproxy-admin slo --require-ready` returning non-zero when no backend can receive new connections

## Next Milestones

1. Broaden optional deep payload diagnostics for Forge/Fabric login and configuration phases beyond bounded channel/kind/byte counters.
2. Run and publish actual acceptance profile results on a dedicated Linux host with native transport enabled.
3. Add native-image or jlink runtime experiments only if profiling shows startup/runtime packaging pressure is worth the added release complexity.
