# 配置说明

StrataProxy 使用一个 YAML 配置文件。仓库内置两份示例：

- `proxy-app/src/main/resources/config/strataproxy.yml`：本地或 staging 示例，带一个静态后端。
- `proxy-app/src/main/resources/config/strataproxy-production.yml`：生产参考配置，默认用动态 registry 持久化。

指定配置启动：

```powershell
.\proxy-app\build\install\strataproxy\bin\strataproxy.bat .\strataproxy.yml
```

只校验配置，不打开监听端口：

```powershell
.\proxy-app\build\install\strataproxy\bin\strataproxy.bat --validate-config .\strataproxy.yml
```

字符串配置支持环境变量占位：

```yaml
admin:
  bearerToken: "${STRATAPROXY_ADMIN_TOKEN}"

forwarding:
  secret: "${STRATAPROXY_FORWARDING_SECRET:}"
```

## 最小配置

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

| 字段 | 含义 | 起点建议 |
| --- | --- | --- |
| `bind` | 玩家入口监听地址 | `0.0.0.0:25577` |
| `workerThreads` | Netty worker 线程数，`0` 自动 | `0` |
| `nativeTransport` | 可用时优先使用 Netty native transport | `true` |
| `maxFrameBytes` | 最大 Minecraft frame | 本地 `8mb`，生产 `16mb` |
| `connectTimeoutMillis` | 后端连接超时 | `3000` 到 `5000` |
| `writeBufferLow` / `writeBufferHigh` | Netty 写缓冲水位 | `4mb` / `16mb` |
| `maxConnections` | 全局连接上限 | 按主机内存设置 |
| `maxConnectionsPerAddress` | 单 IP 连接上限 | `200` 到 `300` |
| `maxNewConnectionsPerSecond` | 全局每秒新连接限制，`0` 关闭 | 本地 `0`，生产按入口规模设置 |
| `maxNewConnectionsPerAddressPerSecond` | 单 IP 每秒新连接限制，`0` 关闭 | 本地 `0`，生产建议开启 |
| `initialHandshakeTimeoutMillis` | 握手超时，防止空连占位 | `5000` |
| `proxyProtocol` | 解析 HAProxy PROXY protocol v1 | 只在可信负载均衡后启用 |

只有玩家不能直连该监听端口时，才启用 `proxyProtocol`。

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

- `staticServers: true` 从 YAML 的 `servers:` 加载后端。
- `persistenceEnabled: true` 把 Admin CLI 的服务器变更写入磁盘。
- `healthCheckMode: tcp` 只检查 TCP 端口能否连接。
- `healthCheckMode: minecraft-status` 检查后端能否响应 Minecraft status。

相对路径 `persistencePath` 会按当前配置文件所在目录解析。

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

路由会综合 health、drain、协议范围、tag、capability、负载、weight 和 metadata。整合包或分组信息放在 `metadata`，没有单独的 `modpackId` 字段。

## Forwarding

```yaml
forwarding:
  mode: "none"
  secret: ""
```

支持模式：

| 模式 | 什么时候用 |
| --- | --- |
| `none` | 后端不需要代理转发玩家身份 |
| `velocity-modern` | Paper/现代后端启用了 Velocity forwarding |
| `bungee-legacy` | 后端需要传统 BungeeCord IP forwarding |
| `bungee-guard` | 后端需要 BungeeGuard 风格 secret 保护 |

`velocity-modern` 和 `bungee-guard` 需要共享 secret。

## Online Mode

```yaml
auth:
  onlineMode: false
  rsaKeyBits: 1024
  verifyTokenBytes: 4
  sessionVerification: false
  sessionVerificationTimeout: "5s"
```

只有希望代理负责 Minecraft 加密和 Mojang session verification 时，才开启 `onlineMode: true`。后端身份转发仍然由 `forwarding` 单独配置。

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

普通兼容流量保持 `codec: zlib`。只有在受控 Mod 客户端已经协商同一 codec 和 dictionary 时，才使用 `codec: zstd`。细节见 [Zstd 压缩调参指南](compression-zstd.md)。

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

安全起点：

- 本机管理时保持 `bind: "127.0.0.1:8080"`。
- 绑定非 loopback 地址前必须设置强 `bearerToken`。
- 远程自动化尽量使用 TLS 或 mTLS。

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

这里控制代理返回给 Minecraft 服务器列表的状态信息。

## Packet Analysis 与 Observability

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

这些配置控制有界诊断。除非通过 Admin CLI 显式开启 capture，否则不会保留完整 payload。
