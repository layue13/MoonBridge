# StrataProxy 运维说明书

这份说明书面向 StrataProxy 的部署、配置、运行、观测和故障处理。

StrataProxy 是 Java 25 Minecraft 代理，主要面向大型模组服网络。路由依据是后端能力、标签、协议范围、健康状态、负载、drain 状态和 metadata。它不是 BungeeCord 或 HexaCord 的兼容重写，但支持部分后端身份转发模式。

## 1. 前置要求

- JDK 25。
- 一个代理主机能访问的 Minecraft 后端。
- 玩家入口端口，默认 `25577`。
- Admin API 端口，默认 `127.0.0.1:8080`。
- Linux 生产环境需要较高文件描述符上限、native transport、足够 heap 和 direct memory。

Windows 本地构建：

```powershell
$env:JAVA_HOME='C:\Program Files\Zulu\zulu-25'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat --no-daemon check
```

## 2. 构建与安装

构建代理应用：

```powershell
.\gradlew.bat --no-daemon :proxy-app:installDist
```

构建运维 CLI：

```powershell
.\gradlew.bat --no-daemon :proxy-admin-cli:installDist
.\gradlew.bat --no-daemon :proxy-query:installDist
```

构建完整发行包：

```powershell
.\gradlew.bat --no-daemon release
Get-ChildItem .\build\release
```

## 3. 配置文件

主要示例：

- 开发配置：`proxy-app/src/main/resources/config/strataproxy.yml`
- 生产参考配置：`proxy-app/src/main/resources/config/strataproxy-production.yml`

安装后的发行目录会包含 `config/`。

启动前校验配置：

```powershell
.\proxy-app\build\install\strataproxy\bin\strataproxy.bat --validate-config C:\path\to\strataproxy.yml
```

字符串配置支持环境变量占位：

```yaml
admin:
  bearerToken: "${STRATAPROXY_ADMIN_TOKEN}"

forwarding:
  secret: "${STRATAPROXY_FORWARDING_SECRET}"
```

有安全默认值时可以用 `${NAME:default}`。

## 4. 最小可用配置

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

如果 `admin.bearerToken` 为空，Admin API 只能保持在 loopback。非 loopback 暴露必须配置强 token，最好启用 TLS 或 mTLS。

## 5. 启动与停止

使用默认配置启动：

```powershell
.\proxy-app\build\install\strataproxy\bin\strataproxy.bat
```

指定配置启动：

```powershell
.\proxy-app\build\install\strataproxy\bin\strataproxy.bat C:\path\to\strataproxy.yml
```

本地测试用 Ctrl+C 停止；生产环境用 systemd 或容器编排工具停止。关闭流程会依次关闭 listener、Admin API、健康检查、负载上报和 Netty event loop。

## 6. Admin CLI

常用命令：

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

带 bearer token：

```powershell
& $admin --base-url http://127.0.0.1:8080 --token "$env:STRATAPROXY_ADMIN_TOKEN" overview
```

## 7. 后端注册与路由

列出后端：

```powershell
& $admin --base-url http://127.0.0.1:8080 servers list
```

注册或替换后端：

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

灰度：

```powershell
& $admin --base-url http://127.0.0.1:8080 servers update survival-1 --weight 20 --metadata group=survival-canary
```

维护 drain：

```powershell
& $admin --base-url http://127.0.0.1:8080 servers drain survival-1
& $admin --base-url http://127.0.0.1:8080 servers undrain survival-1
```

预览路由：

```powershell
& $admin --base-url http://127.0.0.1:8080 routes preview `
  --route survival.example.net `
  --protocol-version 763 `
  --remote-address 127.0.0.1:50000 `
  --tag survival `
  --capability large-payload
```

## 8. 后端身份转发

支持模式：

- `none`
- `velocity-modern`
- `bungee-legacy`
- `bungee-guard`

Velocity modern 示例：

```yaml
forwarding:
  mode: "velocity-modern"
  secret: "${STRATAPROXY_FORWARDING_SECRET}"
```

Paper 兼容后端并启用 Velocity forwarding 时使用 `velocity-modern`。只有后端需要 BungeeCord 风格 IP forwarding 时才使用 `bungee-legacy` 或 `bungee-guard`。

## 9. Online Mode 与认证

```yaml
auth:
  onlineMode: true
  rsaKeyBits: 1024
  verifyTokenBytes: 4
  sessionVerification: true
  sessionVerificationTimeout: "5s"
```

`onlineMode` 在代理侧启用 Minecraft 协议加密。`sessionVerification` 会在 Encryption Response 后调用 Mojang session verification。后端身份转发仍然由 `forwarding` 单独配置。

## 10. 压缩

默认压缩配置：

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

普通 vanilla 兼容流量保持 `codec: zlib`。

只有明确希望代理在后端压缩协商后重写安全、有界的压缩帧时，才启用 `rewriteEnabled: true`。

实验性 Zstd：

```yaml
compression:
  codec: zstd
  minThreshold: 512
  zstdLevel: 1
  zstdDictionaryPath: "data/zstd/registry.zdict"
```

Zstd 需要匹配的 Mod 客户端/后端协商路径。样本采集、dictionary 训练、收益预期和上线检查见 [Zstd 压缩调参指南](compression-zstd.md)。

## 11. Zstd 样本采集快速流程

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

`export` 会写出 `.bin` 样本文件和 `manifest.json`。导出的样本可能包含玩家或 Modpack 敏感数据，按敏感文件处理。

## 12. 观测

Admin API 入口：

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

Prometheus 和 Grafana 资产在 `deployment/observability/`。

重点指标：

- readiness
- active connections
- rejected connections by reason
- event loop delay
- heap 和 direct memory
- 后端健康和 drain 状态
- compression ratio 和 saved bytes
- packet anomalies
- relay backpressure

## 13. Payload 诊断

只看元数据：

```powershell
& $admin --base-url http://127.0.0.1:8080 mod-payloads --samples
```

短时 prefix capture：

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

Prefix capture 是显式开启、有数量和字节上限、会自动过期的诊断功能。

## 14. Query 与压测工具

构建：

```powershell
.\gradlew.bat --no-daemon :proxy-query:installDist
```

状态查询：

```powershell
.\proxy-query\build\install\strataproxy-query\bin\strataproxy-query.bat --host 127.0.0.1 --port 25577 status --virtual-host play.example.net
```

流量压测：

```powershell
.\proxy-query\build\install\strataproxy-query\bin\strataproxy-query.bat --host 127.0.0.1 --port 25577 traffic-load --connections 2000 --virtual-host play.example.net --login-start --player-template load%05d --packets-per-connection 200 --payload-bytes 64 --parallelism 256 --min-handshaken 2000 --min-packets-sent 400000 --max-failed 0
```

Echo latency 需要 echo backend：

```powershell
.\proxy-query\build\install\strataproxy-query\bin\strataproxy-query.bat slow-sink --bind-host 127.0.0.1 --port 25565 --duration-ms 900000 --read-chunk-bytes 8192 --read-delay-ms 0 --echo
```

验收 profile：

```powershell
python deployment\performance\run_profile.py acceptance-linux-native-java25 --query-bin .\proxy-query\build\install\strataproxy-query\bin\strataproxy-query.bat --admin-bin .\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat --admin-url http://127.0.0.1:8080
```

## 15. 生产部署

使用 `deployment/` 下的资产：

- `systemd/strataproxy.service`
- `systemd/strataproxy.env`
- `container/Containerfile`
- `observability/prometheus/strataproxy-alerts.yml`
- `observability/grafana/strataproxy-overview.json`
- `performance/profiles/*.json`

Linux JVM 起点：

```text
-Xms2g -Xmx2g -XX:MaxDirectMemorySize=2g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+ExitOnOutOfMemoryError
```

高连接数场景设置 `LimitNOFILE=1048576` 或等价配置。

## 16. 安全 checklist

- Admin API 不配置 token 时只能绑定 loopback。
- 非 loopback Admin API 必须配置 bearer token。
- 远程自动化尽量使用 mTLS。
- PROXY protocol 只在可信负载均衡后启用。
- payload capture 和 Zstd samples 按敏感数据处理。
- forwarding secret 不要写死进提交的配置，用环境变量占位。

## 17. 故障处理

| 现象 | 优先检查 |
| --- | --- |
| 代理无法启动 | `--validate-config` 和 Java 25 |
| Admin CLI unauthorized | `--token` 和 `admin.bearerToken` |
| 没有后端被选中 | `ready`、`servers list`、drain、health、tag、capability、protocol range |
| 玩家登录断开 | `diagnostics`、anomalies、backend connect failure、auth/forwarding mode |
| p99 延迟高 | event loop delay、backpressure、compression rewrite、backend health |
| 内存高 | heap/direct memory 指标和 write buffer pressure |
| Zstd 解压失败 | 客户端/代理 dictionary hash 和 threshold |

## 18. 生产前验收

最低检查：

```powershell
.\gradlew.bat --no-daemon check installDist :proxy-admin-cli:installDist :proxy-query:installDist
.\gradlew.bat --no-daemon release
.\proxy-app\build\install\strataproxy\bin\strataproxy.bat --validate-config .\proxy-app\build\install\strataproxy\config\strataproxy.yml
```

生产验收应在启用 native transport 的 Linux 主机上完成，并用 `deployment/performance/profile-result-template.json` 记录证据。
