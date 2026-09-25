# StrataProxy

StrataProxy 是面向 Minecraft 1.7.10 Forge 的玩家会话代理。项目正在从零重写；当前分支已有离线和在线模式的登录、协议转发与跨后端切换路径，尚未达到生产可用标准。

## 核心边界

- **玩家会话**：代理负责入服、连接后端、转发、切换后端和断线清理。玩家身份包含 UUID 与连接代次，避免旧连接的异步结果作用到新连接。
- **服务器目录**：静态配置与插件调用同一个注册接口。目录维护地址、声明的容量，以及本代理连接和入服预留的数量；它不采集后端 CPU、TPS、内存等负载。同名重注册生成新句柄，旧异步结果不能修改新条目。
- **插件 API**：插件可查询 `PlayerView`、`ServerView`，动态注册后端，并以一个异步回调决定初始落点。空岛分配、实例唤醒和玩法数据由插件自行实现。
- **发现方式**：DNS 是独立插件，核心不认识 DNS 或 Agent。配置只启用列出的插件；插件关闭时其注册会清理。
- **协议热路径**：会话始终保留协议 5 的帧边界，普通数据包不重新编码并尽量复用 Netty `ByteBuf`；转服后只改写少数与玩家实体 ID 相关的包，并按目标通道可写状态控制读取。
- **日志**：SLF4J API 与 Logback 运行时。

## 当前可运行范围

配置必须显式选择 `authentication: OFFLINE` 或 `ONLINE_BUNGEE`。两种模式均解析协议 5 Handshake 与 Login Start，等待异步落点，预留容量，连接后端，确认 Login Success，然后转发普通字节流。在线模式执行加密握手、异步会话校验，并向可信的 Bungee 兼容后端转发已验证身份。状态查询由代理直接回答。`Players.transfer` 会在旧后端继续服务时登录候选后端；普通后端等到 Join Game 与 Position and Look，Forge 后端收到 ServerHello 后先切换握手链路，待客户端完成握手并收到 Join Game 时再合成 Respawn。切换后的玩家实体 ID 会映射到原客户端 ID；候选登录失败时保留旧链路。DNS 与 Agent 是调用通用注册 API 的独立插件。

**验证边界**：跨后端切换已通过普通协议与 Forge 握手的合成 TCP 测试，包括失败回退、同维度 Respawn、握手后 Join Game、维度覆盖、玩家实体 ID 映射和中途断线清理。在线认证仅通过注入会话校验器的合成 TCP 测试。真实 Mojang 服务、Uranium/Bungee 兼容后端和 1.7.10 Forge 整合包尚未联机验证，也未做同条件性能验收；当前不能宣称生产可用或实服 Forge 转服兼容。

## 配置与运行

默认配置：`proxy-core/src/main/resources/config/strataproxy.yml`。安装包包含静态后端示例和 `strataproxy-dns.example.yml`；后者通过 `plugins.enabled` 启用 DNS 插件，`backends: []` 表示不使用静态后端。相对插件目录从启动时的工作目录解析。`ONLINE_BUNGEE` 只能连接已启用旧版 Bungee 身份转发、且限制直连的可信后端。

```powershell
.\gradlew.bat :proxy-core:installDist
.\proxy-core\build\install\strataproxy\bin\strataproxy.bat --validate-config .\proxy-core\build\install\strataproxy\config\strataproxy.yml
.\proxy-core\build\install\strataproxy\bin\strataproxy.bat --config .\proxy-core\build\install\strataproxy\config\strataproxy.yml
```

使用 DNS 插件时，把安装包的 `plugins` 目录和配置文件放在启动工作目录可访问的位置，并在示例配置中换成自己的 DNS 主机名。

### Agent 动态注册插件

Agent 插件同样由 `plugins.enabled` 显式启用。它通过一个独立 HTTP 端点接收实例注册和续租，再调用通用 `Servers` API；代理核心不包含 Agent 协议或云平台发现逻辑。端点默认只绑定 `127.0.0.1:28080`，请求使用至少 32 字节的共享密钥做 HMAC-SHA256 签名，带 60 秒时间窗和一次性随机 nonce。请求体、并发请求数、实例数和租约时长都有上限。每个 Agent 进程使用新的 UUID generation；活动租约不能被其他 generation 覆盖。注销或过期的 generation 会保留到最大租约时长加 60 秒签名时间窗之后，再由周期清理器删除；这能拦截在租约切换期间延迟到达的旧请求，同时避免 Agent 重启次数累积导致注册耗尽。异常退出后，租约到期会移除后端。

```yaml
plugins:
  directory: plugins
  enabled:
    dev.strataproxy.plugins.agent.AgentDiscoveryPlugin:
      secret: "replace-with-a-private-random-secret-of-32-bytes-or-more"
      # host: 127.0.0.1
      # port: "28080"
      # concurrency: "4"
      # maxBodyBytes: "4096"
      # maxLeaseSeconds: "60"
      # maxInstances: "128"
```

密钥不要提交到仓库；插件和 Agent 必须使用同一个值。跨主机部署时，应绑定到私有网络，并通过可信 TLS 终止器或私有链路保护传输；HMAC 验证身份和请求完整性，但不加密流量。`AgentRegistrationClient` 是仅依赖 JDK 的示例客户端：设置 `STRATAPROXY_AGENT_SECRET`，然后运行 `AgentExampleMain <http-endpoint> <agent-id> <backend-name> <tcp-address> <capacity>`。示例每 10 秒续租一次，租约 30 秒；正常退出时尝试注销，异常退出由租约过期清理。Agent 协议只注册后端地址和声明容量，不上报业务负载，也不需要消息队列。

## 开发验证

```powershell
.\gradlew.bat check
.\gradlew.bat :proxy-core:installedDistSmokeTest
```

定向测试覆盖目录代次与容量并发、插件生命周期与超时、协议边界、relay 背压，以及合成 TCP 登录和切换。真实客户端和服务端整合包尚未提供；合成测试不替代实服验证。

## 合成 relay 基准

`benchmarks/run-relay.ps1` 比较同一 JVM、同一个本机回声后端上的直连、原始字节 relay 和当前会话使用的按帧 relay。每个连接先预热，再重复发送固定长度的合成 Minecraft 帧并读取同样长度的回声；连接数、每连接消息数、预热数、帧负载字节数和重复轮数均可配置。基准在轮次间交替执行两种 relay，并在首尾测直连基线。输出往返吞吐、按单向传输字节计算的 MiB/s、往返延迟 p50/p95/p99、JVM GC 次数/耗时和堆已用量变化。

```powershell
.\benchmarks\run-relay.ps1 -Connections 8 -Messages 2000 -Warmup 200 -Payload 4096
```

该基准只覆盖本机 TCP 回声、原始 relay 和按帧 relay 数据路径。负载是合成帧，不含登录、Forge 握手、模组流量或真实客户端/后端行为；结果不代表 1.7.10 整合包等价性能，也不设 CI 性能门槛。堆变化是阶段前后的粗略观测，不是分配速率；请在目标机器、JDK 和连接规模上多轮运行并记录环境，避免把单次结果当成容量承诺。

本地一次测量的环境、参数与原始输出见 `benchmarks/results/2026-09-25-local-relay.md`。
