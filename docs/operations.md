# 运维与验证

本文概括当前配置入口、启动约束和已有验证证据。架构边界见[架构说明](architecture.md)；简要项目说明见 [README](../README.md)。仓库记录的是开发与本地验证结果，不代表目标整合包或生产环境验收。

## 配置和启动

默认配置模板为 [`strataproxy.yml`](../proxy-core/src/main/resources/config/strataproxy.yml)。发行包将配置放在 `config/`，插件 JAR 放在 `plugins/`。相对 `plugins.directory` 从所选配置文件所在目录解析；`plugins.enabled` 以插件实现类全名为键，空映射不启用外部插件。插件可用设置由各插件在启动时校验。

本地安装发行包并启动：

```powershell
.\gradlew.bat :proxy-core:installDist
.\proxy-core\build\install\strataproxy\bin\strataproxy.bat --validate-config .\proxy-core\build\install\strataproxy\config\strataproxy.yml
.\proxy-core\build\install\strataproxy\bin\strataproxy.bat --config .\proxy-core\build\install\strataproxy\config\strataproxy.yml
```

`--validate-config` 校验核心 YAML 和静态后端注册；它不会加载插件，所以不会验证已启用插件的设置、后端连通性或登录流程。未知核心配置键会报错。

## 认证、监听和容量边界

- 必须显式设定 `authentication: OFFLINE` 或 `ONLINE_BUNGEE`。随附示例使用 `OFFLINE` 并绑定字面量 `127.0.0.1`。OFFLINE 信任客户端提交的名字；没有显式设置 `allowOfflinePublicAccess: true` 时只允许字面量回环 IP，避免主机名解析改变监听范围。
- 对外服务使用 `ONLINE_BUNGEE`，并将后端限制为代理可达，禁止客户端绕过代理直连；后端需启用兼容的旧版 Bungee 身份转发。代理代码中的会话验证与加密路径有自动化测试，但真实 Mojang 会话服务尚未作为此次文档工作的一部分实测。
- `maxConnections` 默认 4096，范围为 1–1,000,000；计数包括登录中的客户端。达到上限时新连接直接关闭。按部署资源和连接规模配置，不将其解释为后端容量。
- `plugins.accessTimeoutSeconds` 为 ConnectionEvent/LoginEvent 访问决策阶段的期限，默认 5 秒，可设为 1–30 秒。每个阶段按订阅注册顺序共享一个期限；无订阅者时跳过派发。访问队列容量为 128，最多 1024 个未决请求。队列饱和、超时、异常或空结果都会拒绝当前访问：连接阶段关闭 TCP 连接，登录阶段返回通用错误文本；插件可通过显式拒绝结果提供登录断开原因。会话通知使用独立的单线程有界队列（128）；过载时记录并尽力丢弃，不影响连接处理。
- 未认证帧上限为 4 KiB；后端 Login Success 后切换到协议 5 PLAY 帧上限。握手/身份验证与后端连接/登录各自有 15 秒期限；初始选服默认 15 秒，可由 `plugins.initialPlacementTimeoutSeconds` 设为 1–120 秒。成功登录后，客户端须在两分钟内完成首次 PLAY/Forge 握手。
- 登录拒绝消息和后端关闭/读失败的已提交前端写入最多排空等待 5 秒。转服切换阶段有 15 秒期限；Forge 客户端切换后目标握手最多等待 30 秒。各期限届满可能使当前登录失败或关闭当前会话。

## 可选后端发现

DNS 示例配置见 [`strataproxy-dns.example.yml`](../proxy-core/src/main/resources/config/strataproxy-dns.example.yml)。它将静态 `backends` 设为空，并通过 `plugins.enabled` 启用 DNS 插件，设置 DNS 主机名、端口、名称前缀和刷新周期。DNS 查找失败暂时保留旧地址；连续三次失败后移除过期地址。DNS 查询明确返回地址族无记录时，该族地址会按解析结果更新。`localhost` 使用系统回环地址解析；普通主机名由插件自己的解析器查询，不依赖 JVM 的全局地址缓存。

Agent 示例见[发现插件](discovery.md)和 [`AgentDiscoveryPlugin`](../plugins/agent-discovery/src/main/java/dev/strataproxy/plugins/agent/AgentDiscoveryPlugin.java)。启用其 HTTP 注册监听前，应在受控网络中配置随机共享密钥，并按插件的端口、并发和租约约束部署。代理核心没有内置云实例发现或健康探测。

连接和登录访问事件 API、检查顺序与 Ban 插件边界见[插件接入说明](plugins.md)。连接拒绝发生在 Minecraft 协议开始前，因此没有 Login Disconnect 可供显示；登录拒绝发生在身份解析后，离线模式中的名字/UUID 尚未认证，插件应检查 `LoginEvent.authenticated()`。会话通知不保证 Forge 或玩法世界就绪，也不能用于可靠审计或计费。

## 验证命令和现有证据

源码测试集中在四个模块。项目定义的常规验证入口为：

```powershell
.\gradlew.bat check
```

该任务包含插件 API、核心、DNS/Agent 插件测试，并在核心 `check` 中依赖安装发行包后的帮助、版本及配置校验烟测。详细烟测、性能测量方法与历史记录见[验证方法](testing.md)。

现有测试可用于定位行为范围：

- 登录、认证和协议：`proxy-core/src/test/java/dev/strataproxy/core/auth/` 与 `proxy-core/src/test/java/dev/strataproxy/core/protocol/`
- 会话、断开、转服与缓冲：`proxy-core/src/test/java/dev/strataproxy/core/session/`，重点包括 `SessionTransferTest`、`SessionTransferDispatchTest`、`TransferFrameBufferTest`
- 插件生命周期、访问检查和 API：`proxy-core/src/test/java/dev/strataproxy/core/plugin/PluginHostTest.java`、`proxy-core/src/test/java/dev/strataproxy/core/session/ProxySessionListenerTest.java`、`proxy-core/src/test/java/dev/strataproxy/app/ProxyConfigurationTest.java` 与 `proxy-plugin-api/src/test/`
- 发现器：`plugins/dns-discovery/src/test/` 与 `plugins/agent-discovery/src/test/`

仓库另有本地 Uranium 协议探针和 Prism Forge 客户端记录，见 [`smoke/results`](../smoke/results/2026-09-25-local-uranium.md)、[转服记录](../smoke/results/2026-09-25-local-uranium-transfer.md) 和 [真实客户端记录](../smoke/results/2026-09-25-prism-forge-client.md)。这些记录支持其明确写出的单机环境、软件版本和探针路径。已知也出现过真实 Forge 客户端转服候选后端登录停滞，见[调查记录](../smoke/results/2026-09-26-uranium-login-stalls.md)；已有成功样例不构成稳定性或生产验收。

尚未由上述证据覆盖的范围包括目标整合包和真实玩家流量、Mojang 在线校验服务的端到端运行、跨主机网络与故障恢复、生产容量/性能以及 Ban 策略插件的实现与验收。合成 TCP、单机烟测和基准结果应保持各自范围，不应外推为这些结论。
