# StrataProxy 中文文档

StrataProxy 是面向大型 Minecraft 模组服网络的新代理项目，不以 BungeeCord、HexaCord 或旧插件生态兼容为核心目标。核心模型只理解服务器能力、标签、协议范围、负载、健康状态、维护状态和 metadata；整合包标识如果需要，放入 `metadata`。

## 定位

文档入口：

- [快速开始](quick-start.md)：构建、改配置、启动、检查，一次跑通。
- [配置说明](configuration.md)：解释 YAML 里每个重要部分怎么填。
- [命令速查](commands.md)：按任务整理 Admin CLI 和 query/load-test 命令。
- [插件和游戏内命令](plugins.md)：玩家命令、插件 jar 入口和扩展点。
- [Zstd 压缩调参指南](compression-zstd.md)：样本采集、dictionary 训练和上线建议。
- [运维说明书](operations-manual.md)：完整生产运维、观测、排障和验收。
- [英文文档目录](../README.md)

目标是高并发、低 GC 压力、可观测、可动态调度的 Minecraft Proxy：

- 高连接数和高 PPS 下保持低 CPU/GC 压力
- 支持大型模组服登录、切服、custom payload、压缩流量观测
- 管理员能查看负载、包分布、异常包、带宽归因和 backpressure
- 后端服务器可动态注册、摘除、drain、维护和健康检查
- 协议、路由、观测、压缩和异常分析分层，便于跟进 Minecraft 版本

## 构建要求

- Java 25
- Gradle Wrapper，项目使用 Gradle Kotlin DSL、Version Catalog、Java Toolchains 和配置缓存

Windows 示例：

```powershell
$env:JAVA_HOME='C:\Program Files\Zulu\zulu-25'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat --no-daemon check installDist :proxy-admin-cli:installDist :proxy-query:installDist
```

Linux/macOS 示例：

```bash
export JAVA_HOME=/path/to/jdk-25
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew --no-daemon check installDist :proxy-admin-cli:installDist :proxy-query:installDist
```

构建完整发行包：

```powershell
.\gradlew.bat --no-daemon release
```

发行产物在 `build/release/`，包含应用包、Admin CLI、Query CLI、部署文件、观测资产、性能 profile、SBOM、metadata 和 checksum。

## 快速运行

安装应用发行目录：

```powershell
.\gradlew.bat --no-daemon :proxy-app:installDist
.\proxy-app\build\install\strataproxy\bin\strataproxy.bat
```

指定配置：

```powershell
.\proxy-app\build\install\strataproxy\bin\strataproxy.bat C:\path\to\strataproxy.yml
```

只校验配置，不启动监听：

```powershell
.\proxy-app\build\install\strataproxy\bin\strataproxy.bat --validate-config C:\path\to\strataproxy.yml
```

默认配置位于 `proxy-app/src/main/resources/config/strataproxy.yml`，生产参考配置位于 `proxy-app/src/main/resources/config/strataproxy-production.yml`。

如果 StrataProxy 部署在可信 TCP 负载均衡后面，可以启用 HAProxy PROXY protocol v1，让连接限流、路由、审计和后端身份转发使用真实玩家地址：

```yaml
network:
  proxyProtocol: true
```

只应在玩家不能直连的监听入口启用。启用后，每条连接都必须先发送合法 PROXY v1 头，再发送 Minecraft 握手。

公网入口建议显式配置 admission 速率限制。连接数上限负责限制已经占用的 socket 资源；每秒速率上限负责削平登录风暴和恶意建连冲击：

```yaml
network:
  maxConnections: 20000
  maxConnectionsPerAddress: 300
  maxNewConnectionsPerSecond: 3000
  maxNewConnectionsPerAddressPerSecond: 60
```

`maxNewConnectionsPerSecond` 和 `maxNewConnectionsPerAddressPerSecond` 设置为 `0` 表示关闭对应限速。被拒绝的连接会按低基数原因进入 Admin API、CLI、诊断报告和 Prometheus：`global_limit`、`per_address_limit`、`global_rate_limit`、`per_address_rate_limit`。

registry 健康检查支持两种模式：

```yaml
registry:
  healthCheckEnabled: true
  healthCheckInterval: "5s"
  healthCheckTimeout: "2s"
  healthCheckMode: "minecraft-status"
```

`tcp` 只检查后端端口能否建立连接，兼容性最好。`minecraft-status` 会发送 Minecraft server-list Handshake 和 Status Request，只有收到有效 Status Response 才标记为 UP，更适合作为生产路由安全检查，避免端口开着但 Minecraft 协议路径不可用的后端继续接收玩家。

## 模块说明

- `proxy-api`：服务器、健康、负载、drain、能力和协议范围模型
- `proxy-plugin-api`：插件、命令、事件、玩家和服务器服务接口
- `proxy-command`：代理侧命令注册和内置玩家命令
- `proxy-plugin`：插件 jar 加载和生命周期管理
- `proxy-network`：Netty acceptor、连接生命周期、relay、backpressure
- `proxy-protocol`：协议状态、packet metadata、packet classifier
- `proxy-codec-minecraft`：Minecraft VarInt、压缩帧、custom payload 分类、AES/CFB8 加密 codec
- `proxy-registry`：动态注册、健康、负载、quarantine、持久化
- `proxy-routing`：标签、容量、健康、drain 感知路由
- `proxy-compression`：固定/自适应压缩策略和 rewrite 决策
- `proxy-observability`：指标、事件、诊断快照
- `proxy-packet-analysis`：异常包规则和采样
- `proxy-admin-api`：HTTP Admin API、Prometheus、诊断导出
- `proxy-admin-cli`：Admin API 命令行工具
- `proxy-query`：状态查询、负载探针、echo backend、性能验收辅助工具
- `proxy-native`：CPU/native runtime 能力探测和运行时决策
- `proxy-app`：可运行应用入口和发行包

## 模组 payload 诊断

StrataProxy 会对 Forge/Fabric 登录阶段的 Login Plugin Request，以及 registry/configuration 阶段的 custom payload 做轻量分类和带宽归因。分类只解析受限长度的 channel 名称，不解析完整模组语义，也不默认保存完整 payload。

`GET /custom-payloads`、`GET /diagnostic-report` 和 `strataproxy-admin mod-payloads` 会返回按服务器、方向、类型和 channel 聚合的计数、字节数、最大包大小和首末次时间。`strataproxy-admin mod-payloads --samples` 额外输出最近 payload 元数据样本，包括 server、direction、kind、channel、payload size、compressed size、player、remote address、protocol state、packet id 和 timestamp。recent samples 固定最多保留 256 条，只保存元数据，不保存完整 payload 字节。

## Native 策略

StrataProxy 当前不自带项目自研 JNI。原因是 Netty 和 JDK 已经覆盖核心需求：

- Linux x86_64 / aarch64：Netty epoll
- macOS x86_64 / aarch64：Netty kqueue
- Windows：Netty NIO
- AES、SHA、CRC、zlib 等底层优化优先依赖 Java 25/JDK intrinsic 和平台库

发行包会带上 Netty epoll/kqueue 的跨平台 native classifier。启动时保持：

```yaml
network:
  nativeTransport: true

native:
  enabled: true
  autoDetect: true
```

Linux 生产验收环境建议打开：

```yaml
native:
  requireNativeTransport: true
```

Windows 不要打开 `requireNativeTransport`，因为标准 Netty server transport 没有等价的 IOCP 服务器实现。

## Minecraft 状态响应

Minecraft 客户端服务器列表的 status ping 可以由代理直接响应：

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

启用后，Handshake `nextState=1` 会在代理本地处理，不需要先选中或连接后端。这样维护、动态 registry 为空、后端全挂时，客户端服务器列表仍能看到清晰状态。`online` 来自代理观测到的活跃玩家会话；`maxPlayers`、MOTD、协议显示、PNG 图标和 sample player 行由管理员配置。`favicon` 可以直接填 `data:image/png;base64,...`，`faviconPath` 按当前配置文件目录解析相对 PNG 路径。

## Minecraft 加密

Minecraft Java online-mode 登录不是 TLS，而是协议内的 RSA + AES/CFB8：

1. 玩家发送 Handshake 和 Login Start。
2. 服务器发送 Encryption Request，包含 RSA 公钥和 verify token。
3. 玩家生成 16 字节 shared secret，并用 RSA 加密 shared secret 和 verify token。
4. 双方启用 `AES/CFB8/NoPadding` 流加密。
5. online-mode 还需要使用 server hash 向 Mojang session server 校验正版会话。

`proxy-codec-minecraft` 已提供 Minecraft AES/CFB8 encoder/decoder、RSA shared secret 解密和 server hash 计算基础组件。`proxy-network` 已提供 online-mode 登录 handler：读取 Login Start，发送 Encryption Request，校验 Encryption Response，安装 AES/CFB8 stream cipher，然后把明文 Handshake/Login Start 交给后续 backend relay。

配置入口：

```yaml
auth:
  onlineMode: true
  rsaKeyBits: 1024
  verifyTokenBytes: 4
  sessionVerification: true
  sessionVerificationTimeout: "5s"
```

`sessionVerification` 是显式开关。启用后，StrataProxy 会在收到 Encryption Response 后异步调用 Mojang `hasJoined`，只有校验通过才继续连接后端。玩家侧 online-mode 加密终止不等于后端身份转发策略，后端仍需要按选定 forwarding 模式配置。

登录阶段如果没有可用后端路由、握手后 pipelined 登录数据超过限制，或已选中后端但连接失败，StrataProxy 会返回 Minecraft Login Disconnect JSON 包，而不是只关闭 TCP 连接。无路由场景会等客户端进入 Login Start 后再响应，这样客户端看到的是协议层断开原因；握手帧本身 malformed 时仍会直接关闭连接。

路由前会规范化握手里的 virtual host，去掉 DNS 尾点和 Forge/FML NUL 后缀，避免模组客户端因为 host 附加标记导致已配置的 host alias 匹配失败。

后端身份转发单独配置：

```yaml
forwarding:
  mode: "velocity-modern"
  secret: "${STRATAPROXY_FORWARDING_SECRET}"
```

支持的模式是 `none`、`velocity-modern`、`bungee-legacy` 和 `bungee-guard`。

`velocity-modern` 会在压缩协商前拦截后端发来的 `velocity:player_info` Login Plugin Request，并回复带 HMAC-SHA256 签名的 Login Plugin Response。payload 内包含客户端地址、已校验 UUID、用户名和 Mojang profile properties。Paper 兼容后端应放在代理后面运行，后端 `server.properties` 使用 `online-mode=false`，Paper 的 Velocity forwarding 打开，并配置同一个 secret。v1 身份/profile 转发始终支持。后端请求 v2 且客户端 Login Start 提供聊天签名 key 材料时，StrataProxy 会返回 v2，并追加 public-key expiry、encoded public key 和 Mojang key signature。没有 key 材料时返回 v1 payload，不伪造无效聊天签名数据。

`bungee-legacy` 会把发往后端的 Handshake host 字段改写为经典 BungeeCord NUL 分隔格式：原始请求 host、客户端地址、去横线 UUID、profile properties JSON。代理 offline-mode 运行时，它会先等待 Login Start，再连接后端，这样可以用玩家名生成 offline UUID，而不是把身份不完整的 legacy handshake 发给后端。`bungee-guard` 使用同样格式，并在最后追加配置的共享 secret。Spigot/Paper 后端使用 BungeeCord 风格 IP forwarding 时可以选择这些模式；后端支持 BungeeGuard 时优先用 `bungee-guard`。

实验性 Zstd 压缩 codec 通过 `compression.codec: zstd` 显式启用，默认仍是 vanilla 兼容的 `zlib`。`zstdDictionaryPath` 可指向由 NBT、registry、chunk palette 和大型 Mod custom payload 样本训练出的字典；客户端、代理和后端必须使用完全相同的字典字节。配套 1.7.10 客户端原型在单独的 `StrataProxyZstdClient` 仓库，使用 GTNH 维护的 RetroFuturaGradle 工具链，并且只有设置 `-Dstrataproxy.zstd.enabled=true` 时才会插入客户端 Netty handler。

完整启用条件、字典训练、阈值选择和 Minecraft 场景下的收益预期见 [Zstd 压缩调参指南](compression-zstd.md)。

## Admin API 和 CLI

构建 CLI：

```powershell
.\gradlew.bat --no-daemon :proxy-admin-cli:installDist
```

常用命令：

```powershell
.\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat --base-url http://127.0.0.1:8080 health
.\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat --base-url http://127.0.0.1:8080 ready
.\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat --base-url http://127.0.0.1:8080 overview
.\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat --base-url http://127.0.0.1:8080 native
.\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat --base-url http://127.0.0.1:8080 backpressure
.\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat --base-url http://127.0.0.1:8080 zstd-samples start --id registry-train --server survival-1 --direction backend_to_frontend --max-samples 5000 --max-bytes 32768 --duration-ms 300000
.\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat --base-url http://127.0.0.1:8080 zstd-samples export registry-train --out samples/registry
.\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat zstd-samples train --in samples/registry --out data/zstd/registry.zdict --max-dict 16384 --level 1
.\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat --base-url http://127.0.0.1:8080 diagnostics
```

公开 Admin API 前必须配置 token，并按需要启用 TLS/mTLS。

## 负载与验收

构建 query 工具：

```powershell
.\gradlew.bat --no-daemon :proxy-query:installDist
```

基础状态查询：

```powershell
.\proxy-query\build\install\strataproxy-query\bin\strataproxy-query.bat --host 127.0.0.1 --port 25577 status --virtual-host play.example.net
```

连接和流量探针：

```powershell
.\proxy-query\build\install\strataproxy-query\bin\strataproxy-query.bat --host 127.0.0.1 --port 25577 idle-load --connections 1000 --hold-ms 10000 --parallelism 256 --settle-ms 100 --probe-timeout-ms 50 --fail-on-closed --min-connected 1000 --min-alive 1000 --max-failed 0
.\proxy-query\build\install\strataproxy-query\bin\strataproxy-query.bat --host 127.0.0.1 --port 25577 traffic-load --connections 2000 --virtual-host play.example.net --login-start --player-template load%05d --packets-per-connection 200 --payload-bytes 64 --parallelism 256 --min-handshaken 2000 --min-packets-sent 400000 --max-failed 0
```

用于 p99 echo latency 的本地 backend：

```powershell
.\proxy-query\build\install\strataproxy-query\bin\strataproxy-query.bat slow-sink --bind-host 127.0.0.1 --port 25565 --duration-ms 900000 --read-chunk-bytes 8192 --read-delay-ms 0 --echo
```

完整性能 profile：

```powershell
python deployment\performance\run_profile.py acceptance-linux-native-java25 --query-bin .\proxy-query\build\install\strataproxy-query\bin\strataproxy-query.bat --admin-bin .\proxy-admin-cli\build\install\strataproxy-admin\bin\strataproxy-admin.bat --admin-url http://127.0.0.1:8080
```

10k idle / 2k active / p99 < 5ms 的验收需要在 Linux native transport 主机上实测，不能只用本地 Windows 构建结果替代。

## 生产部署

`deployment/` 中包含：

- `systemd/strataproxy.service`
- `systemd/strataproxy.env`
- `container/Containerfile`
- `observability/prometheus/strataproxy-alerts.yml`
- `observability/grafana/strataproxy-overview.json`
- `performance/profiles/*.json`

Linux 主机建议：

```bash
ulimit -n 1048576
```

JVM 起点：

```text
-Xms2g -Xmx2g -XX:MaxDirectMemorySize=2g -XX:+UseZGC -XX:+AlwaysPreTouch -XX:+ExitOnOutOfMemoryError
```

后续根据 `/metrics` 中的 heap、direct memory、event loop delay、pending writes 和 bandwidth 数据调整。

## 当前未完成的高风险项

- Velocity modern forwarding v1/v2、BungeeCord legacy forwarding 和 BungeeGuard forwarding 已实现；v2 聊天签名 key 还需要真实 1.19+ 客户端和 Paper 后端端到端验收
- online-mode 还需要真实 Minecraft 客户端和 Mojang session server 的端到端验收
- 10k idle / 2k active 的 Linux native acceptance 需要真实主机证据

这些项完成前，不应宣称项目已经达到完整生产验收。
